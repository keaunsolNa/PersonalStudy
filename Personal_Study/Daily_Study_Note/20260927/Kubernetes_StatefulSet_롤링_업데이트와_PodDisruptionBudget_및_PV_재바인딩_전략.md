Notion 원본: https://www.notion.so/3e85a06fd6d38155932dc3a66af4ef52

# Kubernetes StatefulSet 롤링 업데이트와 PodDisruptionBudget 및 PersistentVolume 재바인딩 전략

> 2026-09-27 신규 주제 · 확장 대상: Docker&CI, AWS

## 학습 목표

- StatefulSet의 Pod Management Policy와 `partition` 필드를 조합해 Kafka/PostgreSQL 클러스터를 무중단 카나리 방식으로 업데이트한다
- PDB의 `minAvailable`/`maxUnavailable`을 쿼럼 기반 워크로드의 실제 내성 한계에 맞춰 설계하고, `disruptionsAllowed` 값으로 드레인/오토스케일러 동작을 예측한다
- StorageClass의 `WaitForFirstConsumer`와 PVC 템플릿으로 노드 재스케줄 시 데이터 볼륨이 안전하게 따라가도록 구성한다
- 헤드리스 서비스 기반 DNS 피어 디스커버리와, 노드 장애 시 강제 삭제로 인한 좀비 파드/데이터 정합성 문제를 차단하는 안전장치를 수립한다

## 1. StatefulSet vs Deployment: 근본적 차이

StatefulSet은 겉모습은 비슷하지만 세 가지 근본적인 계약(guarantee)이 다르다.

**1) 고정된 네트워크 ID.** Deployment의 Pod 이름은 랜덤 해시가 붙지만, StatefulSet은 `kafka-0`, `kafka-1`처럼 순서형 인덱스가 고정된다. 재시작 후에도 이름과 헤드리스 서비스 DNS가 유지되므로, `broker.id` 같은 식별자를 Pod 이름에 매핑해두면 재구성이 불필요하다.

**2) 순서 보장.** 기본값(OrderedReady)은 Pod가 순서대로 생성되고 이전 Pod가 Ready여야 다음이 생성된다. 삭제는 역순이다. 리더 선출이나 PostgreSQL primary가 먼저 뜨는 초기 부트스트랩에 필수적이다.

**3) PVC 템플릿(`volumeClaimTemplates`).** Pod마다 독립된 PVC를 자동 생성한다(`kafka-0` ↔ `data-kafka-0`). Pod가 삭제·재생성되어도 **같은 이름의 PVC에 재바인딩**된다 — 이 매핑이 스토리지 모델의 핵심이다.

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: streaming
spec:
  serviceName: "kafka-headless"
  replicas: 3
  podManagementPolicy: OrderedReady
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      containers:
        - name: kafka
          image: confluentinc/cp-kafka:7.6.0
          ports:
            - containerPort: 9092
              name: broker
          volumeMounts:
            - name: data
              mountPath: /var/lib/kafka/data
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: "gp3-retain"
        resources:
          requests:
            storage: 100Gi
```

| 항목 | Deployment | StatefulSet |
| --- | --- | --- |
| Pod 이름 | 랜덤 해시 접미사 | 고정 순서형 인덱스 |
| 네트워크 ID | 재생성마다 변경 | 헤드리스 서비스 DNS 고정 |
| 스토리지 | 공유 PVC 또는 무상태 | Pod별 전용 PVC, 재바인딩 보장 |
| 생성/삭제 순서 | 무순서(병렬) | 순서 보장(OrderedReady) |
| 스케일다운 시 PVC | 해당 없음 | 기본 유지(고아 상태) |
| 적합 워크로드 | REST API, 워커 컨슈머 | Kafka, PostgreSQL, Redis Cluster |

## 2. Pod Management Policy: OrderedReady vs Parallel

`podManagementPolicy`는 Pod 생성/삭제 순서를 제어한다. 기본값 **OrderedReady**는 안전하지만 느리다 — 3노드 Kafka 클러스터를 처음 만들면 `kafka-0`이 Ready가 될 때까지 `kafka-1`은 생성조차 안 돼, 노드당 30초 기준 최소 90초가 걸린다.

**Parallel**은 모든 Pod를 동시에 생성/삭제한다. Redis Cluster처럼 각 노드가 독립 부트스트랩된 뒤 `redis-cli --cluster create`로 나중에 클러스터를 구성하거나, Cassandra처럼 순서 의존성이 없는 워크로드에 적합하다.

```yaml
spec:
  podManagementPolicy: Parallel   # 순서 의존성이 없는 Redis Cluster/Cassandra에 적합
```

OrderedReady는 스케일 속도가 O(N)으로 늘고, 한 Pod가 CrashLoopBackOff에 빠지면 뒤따르는 모든 생성이 멈춘다("스케일업이 멈췄는데 이벤트 로그가 비어있다" 장애의 원인 — 실제로는 `kubectl describe pod <n-1번째>`를 봐야 한다). Parallel은 빠르지만 초기 멤버십/쿼럼 형성 로직을 애플리케이션이 직접 구현해야 한다.

```bash
kubectl get pods -l app=kafka -o wide
kubectl describe pod kafka-1 | grep -A 10 Events
```

## 3. 롤링 업데이트 전략: partition 필드를 이용한 카나리 순차 업데이트

기본 업데이트 전략 `RollingUpdate`는 **가장 높은 인덱스부터** 하나씩 종료·재생성한다(생성 순서와 반대). 핵심은 `partition` 필드: `N` 이상인 Pod만 새 스펙으로 업데이트되고 N 미만은 유지된다. "1개만 카나리 → 정상 확인 후 partition을 낮춰 점진 확대"에 쓴다.

```yaml
spec:
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: 2   # 인덱스 2(마지막 replica)만 업데이트, 0/1은 유지
```

```bash
# 1) partition=2 → kafka-2만 새 이미지 적용
kubectl patch statefulset kafka -p \
  '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":2}}}}'
kubectl set image statefulset/kafka kafka=confluentinc/cp-kafka:7.7.0

# 2) 정상 확인 후 partition=1 → kafka-1 적용, 이어서 partition=0
kubectl patch statefulset kafka -p \
  '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":0}}}}'
kubectl rollout status statefulset/kafka   # 자동 롤백은 없다
```

StatefulSet에는 `maxSurge`가 없다 — **기존 Pod를 먼저 종료하고 같은 이름/PVC로 재생성**한다. 카나리 인덱스가 실패하면 해당 인덱스만 `CrashLoopBackOff`에 머물지만, 그 순간 가용 replica 수가 줄어드는 것은 피할 수 없다 — 이 지점에서 PDB가 필요하다. `rollout undo`로 롤백해도 `partition`이 걸려 있으면 롤백 대상 역시 그 조건을 따른다는 점을 자주 놓친다.

## 4. PodDisruptionBudget으로 자발적 중단 제한하기

**자발적 중단**은 사람/컨트롤러가 의도적으로 Pod를 종료하는 경우다 — 노드 드레인, 오토스케일러 축소, `delete pod`, 노드 업그레이드. 노드 하드웨어 장애 같은 **비자발적 중단**과 대비되며, PDB는 후자를 막지 못한다 — 컨트롤 플레인이 통제할 수 없기 때문이다.

Kafka 3-broker(`RF=3`, `isr=2`)에서 동시 2개 다운 시 쓰기가 불가능해진다. PDB는 **동시 최대 1개까지만** 자발적 중단을 허용한다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: kafka-pdb
  namespace: streaming
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      app: kafka
```

`minAvailable`/`maxUnavailable`은 하나만 쓴다. 고정 replica면 `maxUnavailable`이 직관적이지만, HPA로 변할 수 있다면 `minAvailable: 66%`가 안전하다. PostgreSQL 3노드(Patroni)는 과반수 선출이 필요하므로 `minAvailable: 2`로 과반 붕괴를 차단한다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: postgres-pdb
spec:
  minAvailable: 2
  selector: { matchLabels: { app: postgres-ha } }
```

`ALLOWED DISRUPTIONS` 값을 반드시 확인한다. 0이면 드레인이 영구 블록되며, Ready 아닌 Pod가 하나라도 있으면 0으로 떨어진다.

```bash
kubectl get pdb -n streaming
# NAME        MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
# kafka-pdb   N/A             1                 1                     10d
```

| 워크로드 | 내성 조건 | 권장 PDB | 근거 |
| --- | --- | --- | --- |
| Kafka (RF=3, isr=2) | 1개 다운까지 쓰기 가능 | `maxUnavailable: 1` | 2개 다운 시 ISR 부족 |
| PostgreSQL(Patroni, 3노드) | 과반 유지 필요 | `minAvailable: 2` | 과반 붕괴 시 리더 선출 불가 |
| Redis Cluster (6노드) | 샤드별 replica 생존 | `maxUnavailable: 1`(샤드별 분리 권장) | 마스터+replica 동시 중단 시 슬롯 유실 |
| Elasticsearch (3노드) | 과반 + shard 가용성 | `minAvailable: 2` | yellow/red 전환 방지 |

## 5. 노드 드레인/오토스케일러 축소와 PDB-StatefulSet 상호작용

`kubectl drain`과 Cluster Autoscaler는 **Eviction API**(`/eviction` 서브리소스)를 호출한다. `delete pod`와 달리 PDB를 검사하며, `disruptionsAllowed`가 0이면 `429`로 거부된다.

```bash
kubectl drain node-3 --ignore-daemonsets --delete-emptydir-data --timeout=300s
# error when evicting pod "kafka-1": Cannot evict pod as it would
# violate the pod's disruption budget.
```

Autoscaler는 evict가 일정 시간(기본 최대 10분) 내 성공 못 하면 스케일다운을 포기하고 다음 노드로 넘어간다. PDB가 과도하게 보수적이면(`maxUnavailable: 0`) 노드를 영원히 못 비워 **비용 최적화가 막히는** 부작용이 생긴다. 실무에서는 두 가지를 함께 본다.

1. **Pod Anti-Affinity**로 Pod를 다른 노드/AZ에 분산시켜, 드레인 1회가 최대 1개 replica에만 영향을 주게 한다.
2. `terminationGracePeriodSeconds`를 실제 셧다운 시간(리더 이양, WAL flush)에 맞게 60~120초로 설정한다.

```yaml
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 90
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchLabels:
                  app: kafka
              topologyKey: "topology.kubernetes.io/zone"
```

EKS Managed Node Group 업그레이드나 Karpenter 통합도 동일하게 Eviction API를 경유해 PDB가 적용된다. 반면 스팟 인스턴스 회수는 2분 유예만 주는 **비자발적** 이벤트라 PDB로 막을 수 없다 — 쿼럼 Pod는 온디맨드 노드 그룹에 고정하는 것이 정석이다.

## 6. PVC 템플릿과 PV 재바인딩: StorageClass와 WaitForFirstConsumer

PVC-Pod 이름 매핑(`data-kafka-0` ↔ `kafka-0`)은 StatefulSet이 존재하는 한 고정된다. Pod가 삭제·재생성돼도(노드 장애 재스케줄 포함) **동일 PVC에 재바인딩**되며, PV가 클라우드 블록 스토리지면 새 노드에 다시 attach된다.

핵심 함정은 **PV의 AZ 고정**이다. AWS EBS는 특정 AZ에 묶이므로, `kafka-1`의 PVC가 `us-east-1a` EBS에 바인딩된 채 Pod가 `us-east-1b` 노드로 스케줄되면 attach가 영구 실패한다. 이를 막는 것이 `WaitForFirstConsumer`다.

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3-retain
provisioner: ebs.csi.aws.com
volumeBindingMode: WaitForFirstConsumer   # 스케줄 후 PV 프로비저닝
reclaimPolicy: Retain                      # PVC 삭제돼도 데이터 보존
allowVolumeExpansion: true
parameters:
  type: gp3
  encrypted: "true"
```

기본값 `Immediate`는 PVC 생성 즉시 PV를 프로비저닝해 스케줄러 배치보다 먼저 AZ가 확정된다 — 여러 AZ EKS 클러스터에서 "Pending에 멈추고 `node(s) had volume node affinity conflict`가 뜨는" 장애의 원인이다. `WaitForFirstConsumer`는 스케줄러가 노드를 먼저 정하게 하고 그 AZ에 맞춰 PV를 나중에 만든다.

`reclaimPolicy: Retain`도 필수다. 기본값 `Delete`는 PVC 삭제 시 EBS 볼륨까지 함께 삭제한다. 스케일 다운 시 PVC는 **자동 삭제되지 않고 고아로 남아** 재사용을 위해 남겨지도록 설계돼 있지만, 사람의 실수 삭제까지 대비하려면 `Retain`으로 이중 안전장치를 둔다.

```bash
# 스케일 다운해도 kafka-2 Pod만 사라지고 PVC는 고아 상태로 남는다
kubectl scale statefulset kafka --replicas=2
kubectl get pvc -l app=kafka
# data-kafka-2   Bound   pvc-ghi789   100Gi   <- 고아 상태

# 재스케일업 시 kafka-2는 기존 PVC/데이터를 그대로 재사용
kubectl scale statefulset kafka --replicas=3
```

`allowVolumeExpansion: true`를 켜두면 CSI 드라이버가 온라인 리사이즈를 지원할 때 PVC `storage` 값만 늘려도 재시작 없이 확장된다 — 디스크 사용량이 꾸준히 느는 Kafka 등에 유용하다.

## 7. 실전 사례: 헤드리스 서비스와 DNS 기반 피어 디스커버리

StatefulSet은 `serviceName`으로 지정된 **헤드리스 서비스**(ClusterIP: None)와 함께 사용한다. 일반 Service는 단일 VIP로 로드밸런싱하지만, 헤드리스 서비스는 각 Pod에 개별 DNS A 레코드를 만든다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka-headless
  namespace: streaming
spec:
  clusterIP: None
  selector:
    app: kafka
  ports:
    - port: 9092
      name: broker
```

이 설정으로 `kafka-0.kafka-headless.streaming.svc.cluster.local` 등이 개별 Pod IP로 해석된다. `advertised.listeners`에 이 DNS를 쓰면, 재스케줄로 IP가 바뀌어도 재연결 주소는 변하지 않는다.

```bash
kubectl run -it --rm dns-test --image=busybox --restart=Never -- \
  nslookup kafka-1.kafka-headless.streaming.svc.cluster.local
```

PostgreSQL(Patroni)도 동일 패턴이다: replica가 헤드리스 서비스 DNS로 primary를 탐색해 `primary_conninfo`에 쓴다. Redis Cluster는 gossip(포트 16379)이 IP 변경을 자체 감지하지만, 재조인이 필요할 수 있어 Redis Operator를 권장한다.

## 8. 장애 시나리오: 강제 종료와 좀비 파드, 데이터 정합성

노드가 네트워크 파티션/커널 패닉으로 응답 불능이 되면 kubelet이 하트비트를 못 보내 `NotReady`가 된다. 기본 5분 뒤 컨트롤 플레인은 Pod를 `Terminating`으로 표시하지만, **kubelet 자신이 죽어 있어 실제 프로세스는 계속 실행 중일 수 있다** — "좀비 파드" 문제다.

StatefulSet에서는 치명적이다. Kubernetes는 **동일 Pod 이름이 동시에 두 곳에 존재하는 것을 허용하지 않는다** — 두 프로세스가 같은 PVC에 동시 쓰기를 시도해 데이터를 손상시키는 것을 막는 설계다. API 서버는 죽은 노드의 Pod를 `Terminating`에 묶어두고, 종료 확인 전까지 재생성하지 않는다.

운영자가 `--grace-period=0 --force`로 **강제 삭제**하면 Pod 오브젝트가 즉시 사라지고 새 노드에 재생성된다. 그러나 kubelet의 정상 종료 확인을 기다리지 않아, **원래 노드의 프로세스가 살아서 같은 볼륨에 쓰기를 계속할 위험**이 있다(단일 attach 블록 스토리지는 CSI가 차단하지만 NFS/CephFS ReadWriteMany는 막지 않는다).

```bash
# 최후의 수단 - 노드 사망을 AWS 콘솔 등으로 검증한 뒤에만 실행
kubectl get node node-3 -o jsonpath='{.status.conditions[?(@.type=="Ready")]}'
kubectl delete pod kafka-1 --grace-period=0 --force
```

**안전장치 체계:**

1. **ReadWriteOnce 블록 스토리지** — CSI가 하나의 EBS를 두 노드에 동시 attach하는 것을 막는다. 새 Pod의 attach가 "already attached"로 실패·재시도되며 좀비 프로세스가 정리될 시간을 번다.
2. **애플리케이션 레벨 펜싱** — Patroni는 DCS(etcd/Consul)에 TTL 리더 락을 걸어, 갱신 실패 시 read-only 전환/종료시킨다(STONITH). Kafka는 컨트롤러 epoch/KRaft 세션으로 오래된 세션의 쓰기를 거부한다.
3. `terminationGracePeriodSeconds`/`preStop`으로 정상 종료를 최대한 활용하고, 강제 삭제는 **노드 사망 검증 후에만** 런북화된 절차로 수행한다.
4. PDB는 수동 `--force`에는 관여하지 않는다 — Eviction API를 거치지 않아 PDB를 우회하므로, 실행 권한을 RBAC로 제한하고 2인 확인 후 실행한다.

```yaml
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "kafka-server-stop.sh && sleep 20"]
```

## 참고

- https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/
- https://kubernetes.io/docs/tasks/run-application/run-replicated-stateful-application/
- https://kubernetes.io/docs/concepts/scheduling-eviction/pod-disruption-budget/
- https://kubernetes.io/docs/tasks/run-application/configure-pdb/
- https://kubernetes.io/docs/concepts/storage/storage-classes/#volume-binding-mode
- https://kubernetes.io/docs/concepts/storage/persistent-volumes/#reclaiming
- https://kubernetes.io/docs/tasks/administer-cluster/safely-drain-node/
- https://kubernetes.io/docs/concepts/services-networking/service/#headless-services