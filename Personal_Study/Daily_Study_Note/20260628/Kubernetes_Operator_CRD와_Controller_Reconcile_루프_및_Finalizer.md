Notion 원본: https://app.notion.com/p/38d5a06fd6d381f99848c16856c21899

# Kubernetes Operator CRD와 Controller Reconcile 루프 및 Finalizer

> 2026-06-28 신규 주제 · 확장 대상: Docker&CI / Kubernetes

## 학습 목표

- CRD로 API를 확장하고 Operator가 도메인 운영 지식을 코드화하는 구조를 설명한다
- reconcile 루프가 선언적 desired/actual 비교로 수렴하는 멱등성 원리를 구현 수준으로 이해한다
- Finalizer를 이용한 외부 리소스 정리와 graceful deletion 흐름을 추적한다
- Informer/WorkQueue 기반 이벤트 처리와 재시도·백오프 전략을 트레이드오프로 판단한다

## CRD

CRD는 사용자 정의 리소스 타입을 추가한다. 등록하면 kubectl get으로 조회 가능한 새 API 종류가 생기고 etcd가 저장하며 API 서버가 검증·RBAC을 그대로 적용한다. CRD만으로는 etcd에 desired만 저장될 뿐, 그 욕망을 구현하는 주체가 Operator다.

## Operator

Operator는 사람 운영자의 판단을 컨트롤러로 코드화한 것이다. replicas가 3이면 StatefulSet을 만들고 프라이머리를 선출하는 절차적 지식을 담는다. 핵심은 선언적 — 사용자는 무엇을만 선언하고 Operator가 현재 상태를 desired로 수렴시킨다.

## Reconcile 루프

멱등성이 전부다 — 같은 입력으로 몇 번을 호출해도 결과가 같아야 한다.

```go
func (r *Reconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
    var pg dbv1.PostgreSQL
    if err := r.Get(ctx, req.NamespacedName, &pg); err != nil {
        return ctrl.Result{}, client.IgnoreNotFound(err)
    }
    desired := r.buildStatefulSet(&pg)
    var actual appsv1.StatefulSet
    err := r.Get(ctx, client.ObjectKeyFromObject(desired), &actual)
    if apierrors.IsNotFound(err) {
        r.Create(ctx, desired)
    } else if actual.Spec.Replicas != desired.Spec.Replicas {
        actual.Spec.Replicas = desired.Spec.Replicas
        r.Update(ctx, &actual)
    }
    return ctrl.Result{}, nil
}
```

두 번 호출되어도 두 번째는 이미 맞다고 보고 아무것도 안 한다. ctrl.Result로 재큐를 제어한다 — RequeueAfter는 주기 점검, err 반환은 백오프 재큐.

## Finalizer

CR 삭제 시 클러스터 밖 리소스(LB, S3)는 자동 정리되지 않는다. finalizers 배열이 비어있지 않으면 API 서버는 객체를 실제 삭제하지 않고 deletionTimestamp만 설정한다.

```go
if pg.DeletionTimestamp.IsZero() {
    if !controllerutil.ContainsFinalizer(&pg, finalizerName) {
        controllerutil.AddFinalizer(&pg, finalizerName)
        return ctrl.Result{}, r.Update(ctx, &pg)
    }
} else {
    if controllerutil.ContainsFinalizer(&pg, finalizerName) {
        if err := r.cleanupExternalResources(ctx, &pg); err != nil {
            return ctrl.Result{}, err
        }
        controllerutil.RemoveFinalizer(&pg, finalizerName)
        return ctrl.Result{}, r.Update(ctx, &pg)
    }
}
```

정리 로직도 멱등이어야 한다 — 이미 지운 리소스를 또 지우려 할 때 에러 없이 통과해야 무한 Terminating에 빠지지 않는다.

## Informer와 WorkQueue

Informer가 watch로 변경 스트림을 받아 로컬 캐시를 유지하고 이벤트를 WorkQueue에 넣는다. WorkQueue는 디둁리케이션, 지수 백오프, MaxConcurrentReconciles 동시성 제어를 제공한다. `Owns(&StatefulSet{})`는 외부 요인으로 StatefulSet이 바뀌면 부모 CR reconcile을 트리거해 드리프트를 자동 교정한다.

## Status 서브리소스와 observedGeneration

status 서브리소스로 spec/status를 분리해 status 갱신이 spec watch를 트리거하지 않게 한다. observedGeneration = generation 비교로 "최신 spec 반영 완료"를 판정한다. Conditions 배열은 kubectl wait와 ArgoCD 동기 판정에 쓰이는 표준 표현이다.

## 운영 안전장치

리더 선출로 다중 인스턴스 중 하나만 reconcile, 타임아웃·백오프 상한, 파괴적 액션에는 확인 게이트. 캐시는 eventually consistent이므로 결정적 이름+API 서버 유니크 제약으로 멱등성을 받쳐야 안전하다.

## 참고

- Kubernetes Operator Pattern: https://kubernetes.io/docs/concepts/extend-kubernetes/operator/
- Kubebuilder Book: https://book.kubebuilder.io/
- controller-runtime: https://pkg.go.dev/sigs.k8s.io/controller-runtime
- Finalizers: https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/
