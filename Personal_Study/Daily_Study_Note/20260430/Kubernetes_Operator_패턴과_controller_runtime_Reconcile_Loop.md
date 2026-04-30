Notion 원본: https://app.notion.com/p/3525a06fd6d3817cb503fef58ef11fd7

# Kubernetes Operator 패턴과 controller-runtime Reconcile Loop 설계

> 2026-04-30 신규 주제 · 확장 대상: Kubernetes · GitOps

## 학습 목표

- Operator 가 Custom Resource 를 통해 도메인 지식을 Kubernetes 에 주입하는 모델 이해
- `controller-runtime` 의 Manager / Controller / Reconciler 계층 분석
- Reconcile 함수 작성 규칙 — idempotent / level-triggered / 부분 실패 허용
- Finalizer · Owner Reference · Status 패턴으로 안전한 리소스 수명주기 관리

## 1. Operator 패턴이란

CoreOS 가 2016년 정의한 Operator Pattern 의 골자는 다음 한 줄이다. **"운영자(SRE)의 머리속 지식을 코드로 옮긴 컨트롤러를 클러스터에 두자."**

흔한 예: Postgres 백업이라는 도메인 작업은 (a) snapshot 생성 → (b) S3 업로드 → (c) WAL archive 감시 → (d) 실패 시 alert. 이를 Bash/Cron 으로 운영하면 사람이 항상 들여다봐야 한다. Operator 는 `PostgresBackup` 이라는 CRD 를 정의하고, 그 spec 을 보고 **현재 상태를 원하는 상태에 맞추는** 컨트롤러를 추가한다.

핵심 원칙 두 가지.

- **Custom Resource Definition (CRD)** 으로 새 API 타입 등록.
- **Reconciler** 가 그 타입의 오브젝트를 watch 하고 외부 시스템과 동기화.

## 2. controller-runtime 아키텍처

Operator SDK / Kubebuilder 모두 내부에서 `sigs.k8s.io/controller-runtime` 을 쓴다. 계층은 다음과 같다.

```
Manager
├── Cache (informer 기반, kind 별 in-memory 인덱스)
├── Client (cache → API server fallback)
├── Controller A
│   ├── Source (Kind / Channel / Webhook)
│   ├── EventHandler (EnqueueRequestForObject 등)
│   └── Reconciler (사용자 코드)
└── Controller B
    ├── ...
```

Manager 는 모든 controller 의 공통 라이프사이클을 잡는다(start/stop, leader election, signal handling). Controller 는 한 종류의 리소스(예: PostgresBackup)를 책임진다. Source 는 그 리소스의 변경 이벤트를 수신해 work queue 에 enqueue 한다. Reconciler 가 큐에서 NamespacedName 을 꺼내 해당 객체를 다시 읽고 원하는 상태로 수렴시킨다.

## 3. Reconcile 의 세 가지 계명

`controller-runtime` 의 Reconciler 는 다음 시그니처를 가진다.

```go
type Reconciler interface {
    Reconcile(ctx context.Context, req Request) (Result, error)
}
```

작성 규칙은 세 가지.

1. **Idempotent**: 같은 입력에 대해 몇 번을 호출하든 결과가 같아야 한다. "1회만 동작하는 부수효과(이메일 발송 등)" 는 별도 phase 로 분리해 status 에 마킹.
2. **Level-Triggered**: "변경 이벤트" 가 아니라 "현재 상태" 만 보고 판단해야 한다. Edge(이벤트 종류)를 신뢰하면 informer 재동기화 시 누락 발생.
3. **부분 실패 허용**: 외부 시스템 호출 실패는 흔하다. 에러를 리턴하면 controller-runtime 이 exponential backoff 로 재큐잉. `Result{Requeue: true}` 또는 `Result{RequeueAfter: 30 * time.Second}` 로 명시적 재시도 간격 지정 가능.

## 4. 최소 Operator — PostgresBackup 예제

CRD 정의 (Kubebuilder 마커):

```go
// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`
type PostgresBackup struct {
    metav1.TypeMeta   `json:",inline"`
    metav1.ObjectMeta `json:"metadata,omitempty"`
    Spec   PostgresBackupSpec   `json:"spec,omitempty"`
    Status PostgresBackupStatus `json:"status,omitempty"`
}

type PostgresBackupSpec struct {
    InstanceRef corev1.LocalObjectReference `json:"instanceRef"`
    S3Bucket    string                      `json:"s3Bucket"`
    Schedule    string                      `json:"schedule,omitempty"`
}

type PostgresBackupStatus struct {
    Phase           string       `json:"phase,omitempty"`
    StartedAt       *metav1.Time `json:"startedAt,omitempty"`
    CompletedAt     *metav1.Time `json:"completedAt,omitempty"`
    SnapshotID      string       `json:"snapshotId,omitempty"`
    Message         string       `json:"message,omitempty"`
    ObservedGeneration int64     `json:"observedGeneration,omitempty"`
}
```

Reconciler:

```go
func (r *PostgresBackupReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
    log := log.FromContext(ctx)

    var backup dbv1.PostgresBackup
    if err := r.Get(ctx, req.NamespacedName, &backup); err != nil {
        return ctrl.Result{}, client.IgnoreNotFound(err)
    }

    // 1. 삭제 처리
    if !backup.ObjectMeta.DeletionTimestamp.IsZero() {
        return r.handleDeletion(ctx, &backup)
    }

    // 2. Finalizer 등록
    if !controllerutil.ContainsFinalizer(&backup, finalizerName) {
        controllerutil.AddFinalizer(&backup, finalizerName)
        if err := r.Update(ctx, &backup); err != nil {
            return ctrl.Result{}, err
        }
        return ctrl.Result{Requeue: true}, nil
    }

    // 3. Phase 분기
    switch backup.Status.Phase {
    case "":
        return r.startBackup(ctx, &backup)
    case "Running":
        return r.checkBackupProgress(ctx, &backup)
    case "Succeeded", "Failed":
        return ctrl.Result{}, nil
    default:
        log.Info("unknown phase, resetting", "phase", backup.Status.Phase)
        backup.Status.Phase = ""
        return ctrl.Result{Requeue: true}, r.Status().Update(ctx, &backup)
    }
}
```

`handleDeletion` 에서 외부 자원(스냅샷)을 정리한 뒤 Finalizer 를 제거한다. 그래야 Kubernetes 가 객체 삭제를 완료한다.

## 5. Finalizer — 외부 자원 누수 방지의 표준

Kubernetes 객체에 `metadata.finalizers` 가 비어 있지 않으면 `kubectl delete` 가 즉시 사라지지 않는다. `deletionTimestamp` 만 찍히고 객체는 살아 있다. 이 시점에서 reconciler 가 "S3 bucket 의 임시 객체 삭제 → finalizer 문자열 제거" 를 해 줘야 객체가 진짜로 사라진다.

```go
func (r *PostgresBackupReconciler) handleDeletion(ctx context.Context, b *dbv1.PostgresBackup) (ctrl.Result, error) {
    if !controllerutil.ContainsFinalizer(b, finalizerName) {
        return ctrl.Result{}, nil
    }
    if err := r.s3.DeletePrefix(ctx, b.Spec.S3Bucket, "tmp/"+string(b.UID)); err != nil {
        return ctrl.Result{}, err
    }
    controllerutil.RemoveFinalizer(b, finalizerName)
    return ctrl.Result{}, r.Update(ctx, b)
}
```

Finalizer 가 없으면 사용자가 `kubectl delete` 후 외부 자원이 그대로 남는다 → 비용/보안 누수.

## 6. Owner Reference 와 Garbage Collection

Operator 가 자식 리소스(예: 백업 잡 Pod)를 만들 때 `controllerutil.SetControllerReference` 를 호출하면 `metadata.ownerReferences` 가 박힌다. 부모(`PostgresBackup`)가 삭제될 때 Kubernetes 가 자식을 자동 삭제한다.

```go
job := &batchv1.Job{ObjectMeta: metav1.ObjectMeta{
    Name:      "backup-" + b.Name,
    Namespace: b.Namespace,
}}
if err := controllerutil.SetControllerReference(b, job, r.Scheme); err != nil {
    return ctrl.Result{}, err
}
if err := r.Create(ctx, job); err != nil {
    return ctrl.Result{}, err
}
```

Operator 코드가 자식 정리 책임을 안 지도록 위임한다.

## 7. Status Subresource 와 Generation

`/status` subresource 를 켜면 spec 과 status 의 update 가 분리된다. spec 변경은 generation 을 1 증가시키지만 status 변경은 안 한다. Reconciler 는 자기 작업이 끝날 때마다 `status.observedGeneration = metadata.generation` 으로 표시한다.

```go
b.Status.ObservedGeneration = b.Generation
b.Status.Phase = "Running"
b.Status.StartedAt = &metav1.Time{Time: time.Now()}
if err := r.Status().Update(ctx, b); err != nil {
    return ctrl.Result{}, err
}
```

운영자는 `metadata.generation != status.observedGeneration` 인 객체를 모니터링해 "spec 이 바뀌었는데 reconciler 가 못 따라잡은" 상황을 감지한다.

## 8. SetupWithManager 와 watch 대상 선언

```go
func (r *PostgresBackupReconciler) SetupWithManager(mgr ctrl.Manager) error {
    return ctrl.NewControllerManagedBy(mgr).
        For(&dbv1.PostgresBackup{}).
        Owns(&batchv1.Job{}).
        Watches(
            &corev1.Secret{},
            handler.EnqueueRequestsFromMapFunc(r.findBackupsForSecret),
        ).
        WithOptions(controller.Options{MaxConcurrentReconciles: 4}).
        Complete(r)
}
```

`Owns` 는 자식 리소스 변경 시 부모를 enqueue. `Watches` 는 외부 리소스 변경을 부모로 매핑(예: 인증 정보가 들어 있는 Secret 이 갱신되면 백업도 재구성). `MaxConcurrentReconciles` 는 동일 컨트롤러의 병렬도. 같은 namespacedName 은 직렬화되므로 race 걱정은 없다.

## 9. 운영 시 흔한 실수

- **status 만 보고 spec 안 봄**: status 는 결과 캐시. 진실은 항상 spec + 외부 시스템에서 다시 계산해야 idempotent.
- **Reconcile 안에서 sleep**: work queue 점유. `RequeueAfter` 로 양보.
- **External webhook 로 직접 외부 호출**: webhook 은 admission 단계라 시간 제약(~10초). 긴 작업은 Reconcile 비동기 처리.
- **leader election 미설정**: 다중 replica 운영 시 중복 reconcile. `Manager` 옵션의 `LeaderElection: true` 필수.
- **CRD 의 schema 변경 호환성**: 한 번 v1 으로 굳힌 spec 필드는 삭제 불가. v1beta1 → v1 conversion webhook 으로 마이그레이션.

## 참고

- Kubernetes 공식 문서, "Operator pattern" / "Custom Resources"
- Kubebuilder Book (book.kubebuilder.io) — 가장 정돈된 한글 못 미치는 영문 가이드
- "Programming Kubernetes" — Michael Hausenblas, Stefan Schimanski
- controller-runtime godoc — `pkg/reconcile`, `pkg/controller`, `pkg/source`
