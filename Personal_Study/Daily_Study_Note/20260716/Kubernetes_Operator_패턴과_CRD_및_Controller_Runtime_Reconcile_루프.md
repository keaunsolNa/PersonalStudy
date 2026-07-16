Notion 원본: https://www.notion.so/39f5a06fd6d38137a5a1dd26ebd21880

# Kubernetes Operator 패턴과 CRD 및 Controller Runtime Reconcile 루프

> 2026-07-16 신규 주제 · 확장 대상: Kubernetes

## 학습 목표

- Informer·Workqueue·Reconciler 로 이어지는 제어 루프 구조를 추적한다
- Level-triggered 조정이 Edge-triggered 이벤트 처리보다 견고한 이유를 설명한다
- CRD 스키마·서브리소스·finalizer 를 매니페스트 수준에서 구성한다
- 멱등한 Reconcile 함수를 작성하고 실패 재큐 전략을 설계한다

## 1. Operator 는 컨트롤러 패턴의 확장이다

Kubernetes 의 근본 설계는 **선언적 제어 루프**다. 사용자가 "원하는 상태(spec)" 를 선언하면, 컨트롤러가 "현재 상태(status)" 를 관찰해 둘이 같아지도록 계속 조정한다.

```
   ┌──────── watch ────────┐
   │                       ▼
[etcd] ← update status  [Controller] ── create/update/delete ──> [실제 리소스]
   ▲                       │
   └────── desired spec ───┘
```

Deployment 컨트롤러는 "replicas: 3" 을 보고 Pod 이 2개면 하나 더 만든다. ReplicaSet 컨트롤러, Node 컨트롤러, Service 컨트롤러 전부 같은 구조다.

**Operator 는 이 패턴을 도메인 지식에 적용한 것**이다. Deployment 는 "Pod 3개" 같은 일반적 개념만 안다. 하지만 PostgreSQL 클러스터를 운영하려면 "primary 가 죽으면 replica 중 하나를 승격하고, 나머지가 새 primary 를 따라가게 하고, 그 사이 쓰기를 차단한다" 같은 도메인 지식이 필요하다. 이런 지식을 코드로 만든 컨트롤러가 Operator 다.

CoreOS 가 2016년에 제안한 원래 정의는 "사람 운영자(operator)가 하던 절차를 소프트웨어로 인코딩한 것" 이다. 야간 대기 중인 DBA 가 하던 판단을 코드가 대신한다.

**Operator = CRD + Custom Controller** 다. CRD 로 새 리소스 타입을 정의하고, 컨트롤러가 그 리소스를 감시하며 조정한다.

## 2. Level-triggered 가 Edge-triggered 를 이긴다

Kubernetes 컨트롤러를 이해하는 데 가장 중요한 개념이다. 하드웨어 인터럽트 용어에서 왔다.

**Edge-triggered**: 변화(이벤트) 자체에 반응한다. "Pod 이 삭제됐다" 는 이벤트를 받아 처리한다.
**Level-triggered**: 현재 상태를 보고 반응한다. "지금 Pod 이 2개고 원하는 건 3개다" 를 보고 처리한다.

Kubernetes 컨트롤러는 **level-triggered** 다. 이 선택이 시스템 전체의 견고함을 만든다.

```go
// Edge-triggered 사고 — 위험
func onPodDeleted(pod *v1.Pod) {
	createPod()   // 이벤트를 놓치면? 두 번 받으면? 순서가 뒤바뀌면?
}

// Level-triggered 사고 — 안전
func Reconcile(ctx context.Context, req Request) (Result, error) {
	desired := 3
	current := countPods()
	if current < desired {
		createPods(desired - current)   // 몇 번 호출해도 결과가 같다
	}
	return Result{}, nil
}
```

**왜 이것이 중요한가.** 분산 시스템에서 이벤트는 반드시 유실된다. 컨트롤러가 재시작되는 동안 발생한 이벤트는 사라진다. 네트워크 파티션 중 watch 커넥션이 끕기면 그 사이 변경을 놓친다. Edge-triggered 라면 이때 상태가 영구히 어긋난다.

Level-triggered 는 이 문제가 없다. 이벤트를 놓쳐도 다음 조정 때 현재 상태를 다시 보고 맞춘다. **이벤트는 "지금 확인해봐" 라는 힌트일 뿐, 정보의 원천이 아니다.**

그래서 컨트롤러는 이벤트가 없어도 주기적으로 전체를 재조정한다(resync). Informer 의 `resyncPeriod` 가 그것이다. 이 안전망이 있어 컨트롤러가 며칠간 이벤트를 놓쳐도 결국 수렴한다.

**Reconcile 함수의 시그니처가 이 철학을 강제한다.**

```go
func (r *Reconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error)
```

`req` 에는 **네임스페이스와 이름만** 들어 있다. 무슨 이벤트였는지(생성인지 수정인지 삭제인지), 이전 값이 무엇이었는지 알려주지 않는다. 의도적이다. 이벤트 종류에 따라 다르게 동작하는 코드를 쓸 수 없게 만든다.

## 3. Informer — watch 를 직접 쓰지 않는 이유

컨트롤러가 API Server 를 매번 조회하면 부하가 감당되지 않는다. 노드 5000개 클러스터에서 컨트롤러 수십 개가 각자 폴링하면 API Server 가 죽는다.

**Informer** 가 이 문제를 푸는다. 로컬에 캐시를 두고 watch 로 갱신한다.

```
API Server
    │ 1) List — 전체 스냅샷 + resourceVersion
    │ 2) Watch — resourceVersion 이후의 변경만 스트리밍
    ▼
[Reflector] ──> [DeltaFIFO] ──> [Indexer/Store] (로컬 캐시)
                     │
                     └──> [EventHandler] ──> [Workqueue] ──> [Reconciler]
```

각 구성 요소의 책임이다.

| 구성 요소 | 책임 |
|---|---|
| Reflector | List & Watch 로 API Server 와 동기화 |
| DeltaFIFO | 변경 델타를 순서대로 큐잉 |
| Indexer | 로컬 캐시 + 인덱스 (namespace 등으로 조회) |
| EventHandler | Add/Update/Delete 콜백 → 워크큐에 키 투입 |
| Workqueue | 중복 제거 + rate limit + 재큐 |

**Indexer 덕분에 Reconcile 안의 `Get` 은 네트워크를 타지 않는다.** 로컬 메모리 조회다. controller-runtime 의 `client.Get` 은 기본적으로 캐시를 읽는다.

```go
// 캐시 읽기 — 빠르지만 약간 stale 할 수 있다
var pod corev1.Pod
r.Client.Get(ctx, req.NamespacedName, &pod)

// API Server 직접 읽기 — 필요할 때만
r.APIReader.Get(ctx, req.NamespacedName, &pod)
```

캐시가 stale 할 수 있다는 것이 함정이다. 방금 `Update` 한 객체를 바로 `Get` 하면 이전 값이 올 수 있다. watch 이벤트가 아직 도착하지 않았기 때문이다. **이것이 Reconcile 을 멱등하게 써야 하는 또 하나의 이유다.** 다음 조정 때 최신 값을 보고 다시 맞추면 된다.

**Workqueue 의 세 가지 능력**이 중요하다.

첫째, **중복 제거**. 같은 키가 이미 큐에 있으면 추가하지 않는다. 한 객체가 초당 100번 수정돼도 Reconcile 은 처리 속도만큼만 돌는다. 자동 배칭이다.

둘째, **동시 처리 방지**. 같은 키는 절대 두 워커가 동시에 처리하지 않는다. `MaxConcurrentReconciles: 10` 으로 설정해도 서로 다른 객체만 병렬이다. 같은 객체에 대한 락을 신경 쓸 필요가 없다.

셋째, **rate limiting**. 실패하면 지수 백오프로 재큐한다. 기본은 5ms 에서 시작해 1000초까지 늘어난다.

## 4. CRD 정의 — 스키마와 서브리소스

CRD 는 새 리소스 타입을 API Server 에 등록한다. Kubebuilder 를 쓰면 Go 타입에서 생성한다.

```go
// api/v1alpha1/database_types.go
package v1alpha1

type DatabaseSpec struct {
	// +kubebuilder:validation:Enum=postgres;mysql
	// +kubebuilder:default=postgres
	Engine string `json:"engine"`

	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=9
	Replicas int32 `json:"replicas"`

	// +kubebuilder:validation:Required
	StorageSize resource.Quantity `json:"storageSize"`

	// +optional
	BackupSchedule string `json:"backupSchedule,omitempty"`
}

type DatabaseStatus struct {
	// +optional
	Phase string `json:"phase,omitempty"`

	// +optional
	ReadyReplicas int32 `json:"readyReplicas"`

	// +optional
	// +patchMergeKey=type
	// +patchStrategy=merge
	Conditions []metav1.Condition `json:"conditions,omitempty"`

	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:subresource:scale:specpath=.spec.replicas,statuspath=.status.readyReplicas
// +kubebuilder:printcolumn:name="Engine",type=string,JSONPath=`.spec.engine`
// +kubebuilder:printcolumn:name="Phase",type=string,JSONPath=`.status.phase`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`
type Database struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   DatabaseSpec   `json:"spec,omitempty"`
	Status DatabaseStatus `json:"status,omitempty"`
}
```

`make manifests` 로 OpenAPI v3 스키마를 가진 CRD YAML 이 생성된다. API Server 가 이 스키마로 검증하므로 잘못된 매니페스트는 `kubectl apply` 단계에서 거부된다.

**status 서브리소스**가 중요하다. `+kubebuilder:subresource:status` 를 붙이면 `/status` 엔드포인트가 분리된다.

```go
// spec 수정 — status 변경은 무시된다
r.Update(ctx, &db)

// status 수정 — spec 변경은 무시된다
r.Status().Update(ctx, &db)
```

이 분리가 없으면 컨트롤러가 status 를 쓰다가 사용자의 spec 변경을 덮어쓰는 사고가 난다. **spec 은 사용자의 것, status 는 컨트롤러의 것** 이라는 경계가 API 레벨에서 강제된다.

또 하나, status 만 변경하면 `metadata.generation` 이 증가하지 않는다. 그래서 `observedGeneration` 패턴이 성립한다.

```go
if db.Status.ObservedGeneration == db.Generation {
	// spec 이 바뀌지 않았다 — 이미 반영된 상태
}
```

**scale 서브리소스**를 정의하면 `kubectl scale` 과 HPA 가 커스텀 리소스에 동작한다.

```bash
kubectl scale database/mydb --replicas=5
```

**Conditions** 는 Kubernetes 표준 status 표현이다.

```go
meta.SetStatusCondition(&db.Status.Conditions, metav1.Condition{
	Type:               "Ready",
	Status:             metav1.ConditionFalse,
	Reason:             "ReplicasNotReady",
	Message:            fmt.Sprintf("%d/%d replicas ready", ready, desired),
	ObservedGeneration: db.Generation,
})
```

`Type`/`Status`/`Reason`/`Message` 사중 구조를 지키면 `kubectl wait --for=condition=Ready` 가 동작한다.

## 5. Reconcile 구현 — 멱등성과 finalizer

```go
func (r *DatabaseReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	log := log.FromContext(ctx)

	// 1) 조회 — NotFound 는 정상 종료 (이미 삭제됨)
	var db dbv1alpha1.Database
	if err := r.Get(ctx, req.NamespacedName, &db); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// 2) 삭제 처리 — finalizer
	if !db.DeletionTimestamp.IsZero() {
		return r.handleDeletion(ctx, &db)
	}

	// 3) finalizer 등록
	if !controllerutil.ContainsFinalizer(&db, finalizerName) {
		controllerutil.AddFinalizer(&db, finalizerName)
		if err := r.Update(ctx, &db); err != nil {
			return ctrl.Result{}, err
		}
		return ctrl.Result{}, nil   // Update 가 새 이벤트를 유발 — 여기서 종료
	}

	// 4) 원하는 상태 구성 (선언적)
	sts := r.buildStatefulSet(&db)
	if err := controllerutil.SetControllerReference(&db, sts, r.Scheme); err != nil {
		return ctrl.Result{}, err
	}

	// 5) 적용 — CreateOrUpdate 로 멱등하게
	op, err := controllerutil.CreateOrUpdate(ctx, r.Client, sts, func() error {
		sts.Spec.Replicas = &db.Spec.Replicas
		sts.Spec.Template.Spec.Containers[0].Image = r.imageFor(&db)
		return nil
	})
	if err != nil {
		return ctrl.Result{}, err
	}
	if op != controllerutil.OperationResultNone {
		log.Info("statefulset reconciled", "operation", op)
	}

	// 6) status 갱신
	var current appsv1.StatefulSet
	if err := r.Get(ctx, client.ObjectKeyFromObject(sts), &current); err != nil {
		return ctrl.Result{}, err
	}

	db.Status.ReadyReplicas = current.Status.ReadyReplicas
	db.Status.ObservedGeneration = db.Generation
	if current.Status.ReadyReplicas == db.Spec.Replicas {
		db.Status.Phase = "Running"
		meta.SetStatusCondition(&db.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionTrue,
			Reason: "AllReplicasReady", ObservedGeneration: db.Generation,
		})
	} else {
		db.Status.Phase = "Progressing"
		meta.SetStatusCondition(&db.Status.Conditions, metav1.Condition{
			Type: "Ready", Status: metav1.ConditionFalse,
			Reason:  "ReplicasNotReady",
			Message: fmt.Sprintf("%d/%d ready", current.Status.ReadyReplicas, db.Spec.Replicas),
			ObservedGeneration: db.Generation,
		})
		// 아직 준비 안 됨 — 재확인 예약
		if err := r.Status().Update(ctx, &db); err != nil {
			return ctrl.Result{}, err
		}
		return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
	}

	if err := r.Status().Update(ctx, &db); err != nil {
		return ctrl.Result{}, err
	}
	return ctrl.Result{}, nil
}
```

**멱등성이 절대 조건이다.** 같은 Reconcile 이 100번 돌아도 결과가 같아야 한다. `CreateOrUpdate` 는 없으면 만들고 있으면 갱신하므로 멱등하다. 반면 다음은 위험하다.

```go
// 나쁨 — 두 번 돌면 두 개 생긴다
r.Create(ctx, buildBackupJob(&db))

// 좋음 — 결정론적 이름으로 멱등하게
job := buildBackupJob(&db)
job.Name = fmt.Sprintf("%s-backup-%d", db.Name, db.Generation)
if err := r.Create(ctx, job); err != nil && !apierrors.IsAlreadyExists(err) {
	return ctrl.Result{}, err
}
```

**finalizer** 는 외부 리소스 정리에 쓴다. finalizer 가 있으면 API Server 는 삭제 요청을 받아도 객체를 지우지 않고 `deletionTimestamp` 만 찍는다. 컨트롤러가 정리를 마치고 finalizer 를 제거해야 실제로 사라진다.

```go
func (r *DatabaseReconciler) handleDeletion(ctx context.Context, db *dbv1alpha1.Database) (ctrl.Result, error) {
	if !controllerutil.ContainsFinalizer(db, finalizerName) {
		return ctrl.Result{}, nil
	}

	// 외부 리소스 정리 — S3 백업, 외부 DNS, 클라우드 볼륨 등
	if err := r.deleteExternalBackups(ctx, db); err != nil {
		// 실패하면 finalizer 를 남긴 채 재시도
		return ctrl.Result{}, err
	}

	controllerutil.RemoveFinalizer(db, finalizerName)
	return ctrl.Result{}, r.Update(ctx, db)
}
```

**finalizer 의 최대 위험**은 객체가 영원히 지워지지 않는 것이다. 정리 로직이 계속 실패하면(예: 이미 사라진 외부 리소스를 지우려다 에러) 네임스페이스 삭제까지 멈췬다. 정리 로직은 "이미 없음" 을 성공으로 처리해야 한다.

```go
if err := r.deleteS3Bucket(ctx, name); err != nil {
	var nsk *types.NoSuchBucket
	if !errors.As(err, &nsk) {   // 이미 없으면 성공
		return err
	}
}
```

응급 시 강제 제거는 다음이지만, 외부 리소스가 누수된다.

```bash
kubectl patch database mydb -p '{"metadata":{"finalizers":[]}}' --type=merge
```

**OwnerReference** 는 finalizer 와 다른 메커니즘이다. `SetControllerReference` 로 소유권을 설정하면 부모가 삭제될 때 GC 컨트롤러가 자식을 자동 삭제한다. 클러스터 내부 리소스는 이것으로 충분하고, finalizer 는 클러스터 바깥 리소스에만 필요하다.

## 6. 컨트롤러 등록과 이벤트 매핑

```go
func (r *DatabaseReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbv1alpha1.Database{}).            // 주 리소스
		Owns(&appsv1.StatefulSet{}).            // 소유 리소스 — 변경 시 부모로 매핑
		Owns(&corev1.Service{}).
		Watches(                                 // 소유하지 않는 리소스
			&corev1.Secret{},
			handler.EnqueueRequestsFromMapFunc(r.secretToDatabases),
		).
		WithOptions(controller.Options{MaxConcurrentReconciles: 5}).
		Complete(r)
}
```

`Owns` 는 OwnerReference 를 따라 자동으로 부모 키를 큐에 넣는다. StatefulSet 이 수정되면 그 소유자인 Database 의 Reconcile 이 돌다.

`Watches` + `MapFunc` 는 소유 관계가 없는 리소스를 감시할 때 쓴다. 예를 들어 여러 Database 가 공유하는 Secret 이 바뀌면 관련된 모든 Database 를 재조정해야 한다.

```go
func (r *DatabaseReconciler) secretToDatabases(ctx context.Context, obj client.Object) []reconcile.Request {
	var list dbv1alpha1.DatabaseList
	if err := r.List(ctx, &list, client.MatchingFields{"spec.secretRef": obj.GetName()}); err != nil {
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for _, db := range list.Items {
		reqs = append(reqs, reconcile.Request{
			NamespacedName: types.NamespacedName{Name: db.Name, Namespace: db.Namespace},
		})
	}
	return reqs
}
```

`MatchingFields` 를 쓰려면 인덱스를 미리 등록해야 한다.

```go
mgr.GetFieldIndexer().IndexField(ctx, &dbv1alpha1.Database{}, "spec.secretRef",
	func(o client.Object) []string {
		return []string{o.(*dbv1alpha1.Database).Spec.SecretRef}
	})
```

인덱스 없이 전체 List 후 필터링하면 대규모 클러스터에서 Reconcile 마다 수천 개 객체를 순회한다.

## 7. 반환값 설계와 재큐 전략

`ctrl.Result` 와 `error` 조합이 다음 동작을 결정한다.

| 반환 | 동작 |
|---|---|
| `Result{}, nil` | 완료. 다음 이벤트까지 대기 |
| `Result{}, err` | rate-limited 재큐 (지수 백오프 5ms → 1000s) |
| `Result{Requeue: true}, nil` | 즉시 재큐 (rate limiter 적용) |
| `Result{RequeueAfter: 30*time.Second}, nil` | 30초 후 재큐 |

**에러 반환과 RequeueAfter 를 구분해야 한다.**

```go
// 나쁨 — "아직 준비 안 됨" 을 에러로 반환
if !ready {
	return ctrl.Result{}, fmt.Errorf("not ready yet")
}
// → 에러 로그가 쌓이고, 백오프가 1000초까지 늘어 반응이 느려진다

// 좋음 — 정상적 대기
if !ready {
	return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
}
```

에러는 "예상치 못한 문제" 에만 쓴다. "아직 진행 중" 은 정상 상태이므로 `RequeueAfter` 다. 이 구분을 안 하면 알림이 오탐으로 가득 찬다.

**충돌(Conflict) 처리**도 흔한 지점이다.

```go
if err := r.Status().Update(ctx, &db); err != nil {
	if apierrors.IsConflict(err) {
		// 다른 곳에서 먼저 수정 — 재조회 후 재시도
		return ctrl.Result{Requeue: true}, nil   // 에러가 아니다
	}
	return ctrl.Result{}, err
}
```

낙관적 락 충돌은 정상적으로 발생한다. 에러로 취급하면 로그가 오염된다.

## 8. 테스트 — envtest

Operator 테스트는 `envtest` 로 한다. 실제 `kube-apiserver` 와 `etcd` 바이너리를 로컬에 띄우고 그 위에서 검증한다. kubelet 은 없으므로 Pod 이 실제로 뜨지는 않는다.

```go
var _ = Describe("Database Controller", func() {
	const timeout = time.Second * 10
	const interval = time.Millisecond * 250

	It("StatefulSet 을 생성한다", func() {
		ctx := context.Background()
		db := &dbv1alpha1.Database{
			ObjectMeta: metav1.ObjectMeta{Name: "test-db", Namespace: "default"},
			Spec: dbv1alpha1.DatabaseSpec{
				Engine: "postgres", Replicas: 3,
				StorageSize: resource.MustParse("10Gi"),
			},
		}
		Expect(k8sClient.Create(ctx, db)).To(Succeed())

		var sts appsv1.StatefulSet
		Eventually(func() error {
			return k8sClient.Get(ctx,
				types.NamespacedName{Name: "test-db", Namespace: "default"}, &sts)
		}, timeout, interval).Should(Succeed())

		Expect(*sts.Spec.Replicas).To(Equal(int32(3)))
		Expect(sts.OwnerReferences).To(HaveLen(1))
		Expect(sts.OwnerReferences[0].Name).To(Equal("test-db"))
	})

	It("스키마 검증이 잘못된 spec 을 거부한다", func() {
		db := &dbv1alpha1.Database{
			ObjectMeta: metav1.ObjectMeta{Name: "bad-db", Namespace: "default"},
			Spec: dbv1alpha1.DatabaseSpec{
				Engine: "oracle",   // Enum 위반
				Replicas: 3, StorageSize: resource.MustParse("10Gi"),
			},
		}
		Expect(k8sClient.Create(context.Background(), db)).NotTo(Succeed())
	})

	It("finalizer 가 등록된다", func() {
		var db dbv1alpha1.Database
		Eventually(func() []string {
			k8sClient.Get(context.Background(),
				types.NamespacedName{Name: "test-db", Namespace: "default"}, &db)
			return db.Finalizers
		}, timeout, interval).Should(ContainElement(finalizerName))
	})
})
```

**`Eventually` 를 쓰는 이유**가 중요하다. Reconcile 은 비동기다. `Create` 직후 `Get` 하면 아직 컨트롤러가 안 돌았을 수 있다. 동기적으로 검증하면 테스트가 간헐적으로 실패한다(flaky). `Eventually` 로 수렴을 기다리는 것이 올바른 방식이며, 이는 level-triggered 시스템을 테스트하는 표준 방법이다.

**envtest 의 한계**는 kubelet 부재다. Pod 이 Running 이 되지 않으므로, Pod 상태에 의존하는 로직은 status 를 수동으로 조작해 시뮬레이션해야 한다. 전체 흐름 검증은 kind 클러스터에서 e2e 로 한다.

## 9. 운영 — 리더 선출과 관측

**리더 선출**은 HA 배포에서 필수다.

```go
mgr, err := ctrl.NewManager(ctrl.GetConfigOrDie(), ctrl.Options{
	LeaderElection:          true,
	LeaderElectionID:        "database-operator.example.com",
	LeaderElectionNamespace: "operator-system",
	LeaseDuration:           &leaseDuration,   // 15s
	RenewDeadline:           &renewDeadline,   // 10s
	RetryPeriod:             &retryPeriod,     // 2s
})
```

Lease 오브젝트로 구현된다. 리더만 Reconcile 을 돌리고 나머지는 대기한다. 두 컨트롤러가 동시에 같은 리소스를 조정하면 서로 덮어쓰기를 반복하는 무한 루프가 생긴다.

**controller-runtime 이 자동 노출하는 메트릭**이다.

| 메트릭 | 의미 |
|---|---|
| `controller_runtime_reconcile_total{result}` | 결과별 조정 횟수 |
| `controller_runtime_reconcile_errors_total` | 에러 횟수 |
| `controller_runtime_reconcile_time_seconds` | 조정 소요 시간 |
| `workqueue_depth` | 큐 깊이 |
| `workqueue_adds_total` | 큐 투입 총량 |
| `workqueue_retries_total` | 재시도 횟수 |

**증상별 진단**

| 증상 | 원인 | 확인 |
|---|---|---|
| `workqueue_depth` 지속 증가 | Reconcile 이 느림 | `reconcile_time_seconds` p99 |
| `reconcile_errors_total` 급증 | 로직 에러 또는 API 문제 | 로그 |
| `workqueue_retries_total` 폭증 | 무한 재큐 루프 | 아래 참조 |
| 객체가 삭제 안 됨 | finalizer 정리 실패 | `deletionTimestamp` + 로그 |
| CPU 100% | hot loop | 아래 참조 |

**무한 재큐 루프**가 가장 흔한 사고다. Reconcile 이 자기가 감시하는 객체를 매번 수정하면 그 수정이 새 이벤트를 만들고, 다시 Reconcile 이 돌아 또 수정한다.

```go
// 위험 — 매번 Update 호출
db.Status.LastChecked = metav1.Now()   // 항상 값이 바뀜다!
r.Status().Update(ctx, &db)            // → 새 이벤트 → 무한 루프

// 안전 — 실제 변경이 있을 때만
if db.Status.ReadyReplicas != current.Status.ReadyReplicas {
	db.Status.ReadyReplicas = current.Status.ReadyReplicas
	r.Status().Update(ctx, &db)
}
```

타임스탬프를 status 에 매번 쓰는 것이 전형적인 실수다. `workqueue_adds_total` 이 초당 수백 이상이면 이것을 의심한다.

## 10. 정리 — 언제 Operator 를 만들 것인가

**만들 가치가 있는 경우**는 도메인 지식이 운영 절차에 깊이 박혀 있고, 그 절차가 반복적이며, 사람이 밤에 깨어 처리하고 있는 경우다. DB 페일오버, 인증서 갱신, 백업·복구, 스케일링 판단 등이 해당한다.

**만들지 말아야 할 경우**는 Deployment + ConfigMap + Job 조합으로 충분한 경우다. Operator 는 그 자체가 운영 부담이다. 컨트롤러가 죽으면 조정이 멈추고, 버그가 있으면 리소스를 잘못 지운다. Helm 차트로 끝날 일에 Operator 를 만드는 것은 과잉이다.

**작성 시 핵심 원칙 다섯**

첫째, Reconcile 은 반드시 멱등해야 한다. 이벤트 종류를 알려고 하지 말고 현재 상태만 보고 판단한다.

둘째, spec 은 사용자의 것, status 는 컨트롤러의 것이다. status 서브리소스로 분리한다.

셋째, "아직 준비 안 됨" 은 에러가 아니라 `RequeueAfter` 다.

넷째, finalizer 정리 로직은 "이미 없음" 을 성공으로 처리한다.

다섯째, status 에 항상 바뀌는 값(타임스탬프 등)을 쓰지 않는다. 무한 루프의 원인이다.

## 참고

- Kubernetes Docs — Operator pattern, Custom Resources (https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)
- Kubebuilder Book (https://book.kubebuilder.io/)
- controller-runtime GoDoc — `Reconciler`, `Manager`, `builder` 패키지
- Kubernetes Docs — API Conventions (Conditions, ObservedGeneration, Finalizers)
- client-go Source — `Reflector`, `DeltaFIFO`, `SharedIndexInformer`
- Operator SDK Documentation — Capability Levels (https://sdk.operatorframework.io/docs/overview/operator-capabilities/)
- James Bowes — Level Triggering and Reconciliation in Kubernetes
