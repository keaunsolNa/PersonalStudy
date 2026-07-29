Notion 원본: https://app.notion.com/p/3ac5a06fd6d38170af86c3500b2f5130

# Kubernetes Operator 패턴과 CRD 및 컨트롤러 리컨실 루프

> 2026-07-30 신규 주제 · 확장 대상: Docker & CI / Kubernetes(기본 워크로드 이후의 확장 메커니즘)

## 학습 목표

- CRD 로 쿠버네티스 API 를 확장해 도메인 리소스를 선언적으로 정의하는 방법을 정리한다.
- 컨트롤러의 reconcile 루프가 desired state 와 actual state 를 수렴시키는 원리를 파악한다.
- level-triggered(수준 기반) 조정이 edge-triggered(이벤트 기반)보다 견고한 이유를 구분한다.
- 간단한 Operator 의 reconcile 로직·상태 서브리소스·finalizer 를 직접 설계해 멱등성을 검증한다.

## 1. Operator 가 푸는 문제

기본 쿠버네티스는 Deployment·Service·ConfigMap 같은 범용 리소스만 안다. 그런데 "PostgreSQL 클러스터", "Kafka 토픽", "Redis 센티넬 구성" 같은 **상태 있는 애플리케이션의 운영 지식**(백업, 페일오버, 버전 업그레이드 순서)은 범용 리소스로 표현되지 않는다. 사람이 runbook 을 보며 수동으로 하던 이 작업을, 코드로 담아 클러스터 안에서 자동 실행하는 것이 Operator 다.

Operator = **CRD(새 리소스 타입) + Controller(그 리소스를 조정하는 제어 루프)**. 사용자는 `kind: PostgresCluster, replicas: 3` 같은 원하는 상태를 선언하고, 컨트롤러가 실제로 그 상태가 되도록 계속 조정한다. 이는 쿠버네티스 자체의 작동 방식(선언적 desired state + 조정 루프)을 사용자 도메인으로 확장한 것이다.

## 2. CRD — API 를 확장한다

CustomResourceDefinition 을 등록하면 쿠버네티스 API 서버에 새로운 엔드포인트(`/apis/db.example.com/v1/...`)가 생기고, `kubectl get postgrescluster` 가 동작한다. 스키마는 OpenAPI v3 로 검증되어 잘못된 필드는 API 서버가 거부한다.

```yaml
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: postgresclusters.db.example.com
spec:
  group: db.example.com
  scope: Namespaced
  names:
    kind: PostgresCluster
    plural: postgresclusters
    shortNames: ["pgc"]
  versions:
    - name: v1
      served: true
      storage: true
      subresources:
        status: {}                # status 서브리소스 활성화 (§5)
      schema:
        openAPIV3Schema:
          type: object
          properties:
            spec:
              type: object
              required: ["replicas", "version"]
              properties:
                replicas: { type: integer, minimum: 1, maximum: 9 }
                version:  { type: string }
            status:
              type: object
              properties:
                readyReplicas: { type: integer }
                phase: { type: string }
```

`storage: true` 인 버전이 etcd 에 실제 저장되는 형태다. 여러 버전을 두면 conversion webhook 으로 버전 간 변환을 제공해야 한다. `subresources.status` 를 켜면 spec 과 status 를 분리해 업데이트할 수 있어(권한·낙관적 락 분리), 컨트롤러 설계가 깔끔해진다.

## 3. Reconcile 루프 — 수렴이 전부다

컨트롤러의 심장은 reconcile 함수다. 시그니처는 단순하다: "이 리소스의 이름을 줄 테니, **현재 상태를 원하는 상태로 만들어라**". 이벤트가 무엇이었는지(생성/수정/삭제)는 신경 쓰지 않는다. 매번 실제 클러스터 상태를 조회하고, desired 와 비교해 차이를 메운다.

```go
func (r *PostgresReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
    // 1) desired state 읽기
    var pgc dbv1.PostgresCluster
    if err := r.Get(ctx, req.NamespacedName, &pgc); err != nil {
        // NotFound = 삭제됨 → 조정할 것 없음 (GC 가 소유 리소스 정리)
        return ctrl.Result{}, client.IgnoreNotFound(err)
    }

    // 2) actual state 읽기 (이 CR 이 소유한 StatefulSet)
    var sts appsv1.StatefulSet
    err := r.Get(ctx, types.NamespacedName{Name: pgc.Name, Namespace: pgc.Namespace}, &sts)

    // 3) 없으면 생성, 다르면 수정 — 멱등하게
    if apierrors.IsNotFound(err) {
        sts = r.buildStatefulSet(&pgc)
        ctrl.SetControllerReference(&pgc, &sts, r.Scheme)  // 소유 관계 → GC 연동
        if err := r.Create(ctx, &sts); err != nil {
            return ctrl.Result{}, err
        }
    } else if *sts.Spec.Replicas != int32(pgc.Spec.Replicas) {
        sts.Spec.Replicas = ptr.To(int32(pgc.Spec.Replicas))
        if err := r.Update(ctx, &sts); err != nil {
            return ctrl.Result{}, err
        }
    }

    // 4) status 반영
    pgc.Status.ReadyReplicas = int(sts.Status.ReadyReplicas)
    pgc.Status.Phase = phaseFrom(&sts)
    return ctrl.Result{}, r.Status().Update(ctx, &pgc)
}
```

핵심은 **멱등성**이다. reconcile 은 몇 번 호출되든 같은 결과에 수렴해야 한다. 같은 리소스에 대해 중복 호출되거나, 처리 중 컨트롤러가 재시작되거나, 이벤트가 유실돼도 안전해야 한다. "이미 존재하면 만들지 않고, 다르면 맞추고, 같으면 아무것도 안 한다"가 기본 골격이다.

## 4. Level-triggered vs Edge-triggered

전통적 이벤트 처리는 edge-triggered — "변경 이벤트가 왔으니 그 델타를 처리"한다. 문제는 이벤트를 놓치면(컨트롤러 다운, 큐 오버플로) 영영 처리 못 한다는 것이다. 쿠버네티스 컨트롤러는 **level-triggered** 로 설계된다 — 이벤트는 "지금 이 리소스를 다시 봐라"는 신호일 뿐이고, 실제 판단은 항상 현재 전체 상태를 다시 읽어서 한다.

그래서 informer 는 주기적으로 **resync** 를 돌려, 이벤트가 없어도 모든 리소스를 재조정 큐에 넣는다. 이벤트를 놓쳤어도 다음 resync 에서 반드시 따라잡는다. reconcile 함수가 "무슨 일이 일어났는지"가 아니라 "지금 어떤 상태여야 하는지"만 보는 이유가 여기 있다. 이 견고함이 분산 시스템에서 Operator 를 신뢰할 수 있게 만든다.

## 5. Status 서브리소스와 관찰된 세대

spec 은 사용자가 쓰고, status 는 컨트롤러가 쓴다. 이 둘을 분리(status subresource)하면 사용자가 status 를 조작하지 못하게 막고, spec 업데이트와 status 업데이트가 서로의 낙관적 락(resourceVersion)을 방해하지 않는다.

성숙한 Operator 는 `status.observedGeneration` 을 둔다. `metadata.generation` 은 spec 이 바뀔 때마다 증가하고, 컨트롤러는 조정을 마치면 그 시점의 generation 을 `observedGeneration` 에 기록한다. `observedGeneration < generation` 이면 "아직 최신 spec 을 반영하지 못했다"는 뜻이라, 모니터링·`kubectl wait` 이 조정 완료를 정확히 판단한다.

```go
meta.SetStatusCondition(&pgc.Status.Conditions, metav1.Condition{
    Type:               "Ready",
    Status:             metav1.ConditionTrue,
    ObservedGeneration: pgc.Generation,   // 이 세대까지 반영 완료
    Reason:             "AllReplicasReady",
})
```

## 6. Finalizer — 삭제 전 정리 훅

CR 이 삭제될 때 클러스터 밖 리소스(외부 클라우드 볼륨, DNS 레코드, S3 백업)를 정리해야 할 때가 있다. 그냥 두면 API 서버가 즉시 리소스를 지워버려 정리할 틈이 없다. **finalizer** 는 `metadata.finalizers` 에 문자열을 넣어 "이게 남아있는 한 실제 삭제를 보류"하게 한다.

```go
const finalizer = "db.example.com/cleanup"

if pgc.DeletionTimestamp.IsZero() {
    // 살아있음 → finalizer 없으면 추가
    if !controllerutil.ContainsFinalizer(&pgc, finalizer) {
        controllerutil.AddFinalizer(&pgc, finalizer)
        return ctrl.Result{}, r.Update(ctx, &pgc)
    }
} else {
    // 삭제 요청됨 (DeletionTimestamp 존재) → 외부 정리 후 finalizer 제거
    if controllerutil.ContainsFinalizer(&pgc, finalizer) {
        if err := r.cleanupExternalResources(ctx, &pgc); err != nil {
            return ctrl.Result{}, err   // 실패 시 재시도 (삭제는 여전히 보류)
        }
        controllerutil.RemoveFinalizer(&pgc, finalizer)
        return ctrl.Result{}, r.Update(ctx, &pgc)  // 마지막 finalizer 제거 → 실제 삭제
    }
}
```

finalizer 정리가 실패하면 리소스는 `Terminating` 상태에 영원히 걸린다. 그래서 정리 로직도 멱등하고 재시도 가능해야 하며, 외부 리소스가 이미 없으면 "성공"으로 처리해야 한다.

## 7. 재시도·레이트리밋·워크큐

reconcile 이 에러를 반환하면 controller-runtime 은 그 요청을 워크큐에 되돌려 **지수 백오프** 로 재시도한다. 일시적 정리 필요(예: 하위 리소스가 아직 준비 중)면 `ctrl.Result{RequeueAfter: 30*time.Second}` 로 명시적 재큐를 요청한다. 이 백오프·레이트리밋 덕분에, 지속 실패하는 리소스가 API 서버를 폭격하지 않는다.

운영에서 흔한 실수는 reconcile 안에서 sleep 하며 기다리는 것이다. reconcile 은 짧게 끝내고 "나중에 다시 보자"를 RequeueAfter 로 위임해야 한다. 한 리소스에서 오래 블록하면 워크큐의 다른 리소스가 굶는다. Operator 는 결국 "빠르게 판단하고, 미완이면 재큐하고, 매번 현재 상태로 다시 계산한다"는 규율의 반복이다. 이 규율이 지켜지면 노드 장애·컨트롤러 재시작·이벤트 유실을 모두 자연스럽게 흡수하는 self-healing 시스템이 된다.

## 8. Informer·캐시·낙관적 동시성

컨트롤러가 매 reconcile 마다 API 서버를 직접 GET 하면 서버에 부하가 몰린다. controller-runtime 은 **informer** 로 대상 리소스를 watch 하며 로컬 인메모리 캐시를 유지하고, `r.Get` 은 실제로 이 캐시에서 읽는다(read-through cache). watch 스트림으로 변경을 받아 캐시를 갱신하고 관련 리소스를 워크큐에 넣는다. 그래서 클러스터 규모가 커져도 API 서버 읽기 부하가 폭증하지 않는다.

다만 캐시는 약간 stale 할 수 있다는 점이 중요하다. reconcile 이 캐시로 읽은 리소스를 수정해 `Update` 를 보낼 때, 그 사이 다른 클라이언트가 먼저 바꿨으면 `resourceVersion` 불일치로 **409 Conflict** 가 난다. 이는 낙관적 동시성 제어이며, 정상 상황이다 — 에러를 반환해 재큐하면 다음 reconcile 이 최신 상태로 다시 계산해 자연히 해소된다.

```go
if err := r.Update(ctx, &sts); err != nil {
    if apierrors.IsConflict(err) {
        // 최신 버전이 아니었음 → 재큐하면 informer 가 갱신한 최신본으로 다시 계산
        return ctrl.Result{Requeue: true}, nil
    }
    return ctrl.Result{}, err
}
```

이 때문에 reconcile 은 read-modify-write 를 한 번에 크게 하기보다, 작은 변경 단위로 나누고 conflict 를 재시도로 흡수하도록 설계한다. status 서브리소스를 분리하는 것도 spec 과 status 의 낙관적 락을 독립시켜 conflict 빈도를 낮추는 효과가 있다.

## 9. Operator 성숙도와 안티패턴

CNCF 의 Operator Capability Model 은 성숙도를 다섯 단계(설치 → 업그레이드 → 전체 수명주기 → 인사이트/메트릭 → 오토파일럿)로 나눈다. 초기 Operator 는 리소스 생성만 하지만, 성숙한 것은 백업·복원, 무중단 롤링 업그레이드, 장애 자동 복구까지 담는다. 이 단계가 올라갈수록 reconcile 의 상태 기계가 복잡해지므로, `status.conditions` 로 각 단계를 명시적으로 표현하고 관찰 가능하게 만드는 것이 필수다.

흔한 안티패턴 세 가지를 피해야 한다. 첫째, reconcile 에서 무슨 이벤트였는지에 따라 다르게 동작하려는 시도 — level-triggered 원칙을 깨고 이벤트 유실에 취약해진다. 둘째, 외부 상태를 캐시하지 않고 매번 재계산하되 멱등하지 않게 만드는 것 — 중복 생성·중복 요금이 발생한다. 셋째, finalizer 정리 로직이 실패에 취약한 것 — 리소스가 Terminating 에 영구히 걸린다. 결국 좋은 Operator 는 화려한 기능보다 "몇 번을 호출해도 같은 결과에 수렴하고, 실패하면 안전하게 재시도되는" 기본기를 지킨 것이다.

## 참고

- Kubernetes Documentation — Custom Resources, Operator pattern, Controllers
- Kubebuilder Book (book.kubebuilder.io) — Reconcile, Finalizers, Status
- controller-runtime Godoc — `reconcile.Reconciler`, workqueue rate limiting
- Programming Kubernetes (Hausenblas & Schimanski, O'Reilly)
