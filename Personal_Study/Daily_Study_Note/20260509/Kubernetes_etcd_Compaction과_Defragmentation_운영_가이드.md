Notion 원본: https://www.notion.so/35b5a06fd6d381538802fb572735b27c

# Kubernetes etcd Compaction과 Defragmentation 운영 가이드

> 2026-05-09 신규 주제 · 확장 대상: Kubernetes / DevOps

## 학습 목표

- etcd 의 MVCC 키 버전 관리 구조와 revision 의 의미를 분해해 이해한다
- compaction 이 어떤 데이터를 무엇으로부터 회수하고, defragmentation 이 무엇을 추가로 회수하는지 정확히 구분한다
- Kubernetes 가 기본 제공하는 auto-compaction 정책 (`--auto-compaction-mode=periodic`) 의 동작을 운영 관점에서 추적한다
- "etcdserver: mvcc: database space exceeded" 에러 발생 시 무중단 복구 절차를 단계별로 정리한다

## 1. etcd 의 데이터 모델: BoltDB + MVCC 인덱스

etcd 는 단일 BoltDB(이후 bbolt) 파일에 데이터를 보관한다. 이 파일은 *B+Tree* 구조이며, 키 공간이 두 개로 나뉜다.

- **key bucket**: revision 단위의 *key=`<mod_revision, sub_revision>`*, value=값 페이로드. 하나의 키에 대한 모든 과거 버전이 누적된다
- **meta/index bucket**: 사용자가 보는 키 → 가장 최신 mod_revision 매핑

매 트랜잭션마다 *revision 카운터* 가 1 증가한다. 사용자가 `kubectl apply` 한 번에 여러 객체를 만드는 시나리오라면 revision 이 일괄 증가한다. revision 은 *전 클러스터 단조 증가* 하는 글로벌 시간축으로 작동한다. `etcdctl get --rev=12345 /key` 처럼 시점 조회가 가능한 이유다.

## 2. revision 누적이 만드는 두 가지 비용

1. **논리 공간**: 옛 revision 의 키-값이 그대로 BoltDB 안에 남아 점유한다. `kubectl get pods -A` 결과의 매 변경 ResourceVersion 이 새로운 revision 을 만든다고 보면 된다
2. **물리 공간**: BoltDB 의 페이지가 *프리리스트* 로 회수되어도 파일 크기 자체는 줄지 않는다 (BoltDB 는 페이지 단위 reuse 만 함, 파일 truncate 안 함)

따라서 두 단계의 청소가 필요하다.

| 단계 | 의미 | 영향 |
|---|---|---|
| Compaction | 특정 revision 이전의 *옛 버전* 을 BoltDB 에서 삭제, 페이지를 프리리스트로 회수 | DB *내부 free* 증가, 파일 크기 *감소 없음* |
| Defragmentation | BoltDB 파일 자체를 새 파일로 다시 쓰면서 free 페이지 제거 | 파일 크기 줄어듦. 단 *해당 노드 일시 중단* |

이 둘이 분리되어 있다는 게 운영자의 1번 헷갈림 포인트.

## 3. Compaction 의 종류

### 3.1 Manual

```bash
ETCDCTL_API=3 etcdctl compact $(etcdctl endpoint status -w json | \
  jq -r '.[0].Status.header.revision')
```

특정 revision 이전을 모두 회수. revision 은 보통 *현재 - 보유 원하는 윈도우* 로 계산한다.

### 3.2 etcd 내장 Auto-Compaction

`--auto-compaction-mode={periodic|revision}` + `--auto-compaction-retention=<value>` 조합.

- `periodic 1h`: 매 시간, *현재 - 1시간* 시점의 가장 오래된 revision 까지 compact
- `revision 1000`: *현재 - 1000* revision 이전을 compact
- `--auto-compaction-mode=periodic --auto-compaction-retention=10m`: 10분 윈도우만 보유

Kubernetes 컨트롤 플레인은 일반적으로 `--auto-compaction-mode=periodic --auto-compaction-retention=5m` 같은 짧은 윈도우를 권장. 다만 Velero 같은 백업 도구가 *시점 조회* 를 쓰면 충분히 길게 둬야 한다.

### 3.3 kube-apiserver 측 Compaction

kube-apiserver 도 `--etcd-compaction-interval=5m` 옵션으로 etcd 에 compact 요청을 보낸다. etcd auto-compaction 과 *동시에 켜면 두 번 compact* 가 일어난다. 한 쪽만 사용하는 게 권장.

## 4. Defragmentation

```bash
ETCDCTL_API=3 etcdctl defrag --endpoints=https://127.0.0.1:2379 \
  --cacert=ca.crt --cert=client.crt --key=client.key
```

특징:

- **블로킹**: defrag 진행 중인 etcd 멤버는 *읽기/쓰기 모두 거부*. 멤버 1대씩 *롤링* 으로 수행해야 한다.
- 3-멤버 클러스터에서 1대 defrag → 잠시 quorum 2/3 → 다음 멤버
- 5-멤버 클러스터라면 동시 1대 defrag 도 안전 여유가 더 있다
- defrag 직후 *해당 멤버* 의 DB 파일 크기 감소. 전체 클러스터 평균이 아니라 *멤버별* 측정해야 한다

운영 트리거 임계: `etcd_mvcc_db_total_size_in_bytes` 가 `quota-backend-bytes` (기본 2GiB, 권장 8GiB)의 75% 도달 시점, 또는 `etcd_mvcc_db_total_size_in_use_bytes` 와의 비율(in_use / total)이 50% 미만일 때.

## 5. quota-backend-bytes 와 알람

etcd 는 backend 크기가 quota 를 넘으면 *NOSPACE 알람* 을 켜고 *모든 쓰기를 거부* 한다. apiserver 는 이때부터 `etcdserver: mvcc: database space exceeded` 를 반환하며 클러스터가 *읽기 전용* 처럼 보인다.

복구 절차:

```bash
# 1. compact
ETCDCTL_API=3 etcdctl compact <rev>

# 2. defrag (멤버별)
ETCDCTL_API=3 etcdctl defrag --endpoints=https://m1:2379
ETCDCTL_API=3 etcdctl defrag --endpoints=https://m2:2379
ETCDCTL_API=3 etcdctl defrag --endpoints=https://m3:2379

# 3. NOSPACE 알람 해제
ETCDCTL_API=3 etcdctl alarm disarm
```

알람을 disarm 하기 전 까지는 quota 가 다시 충분해도 쓰기가 막혀 있다는 점을 잊으면 안 된다.

## 6. Kubernetes 에서의 etcd 부하 패턴

대표적인 부하원:

- 다수의 `Lease` 객체 (kube-controller-manager 의 leader election, kubelet heartbeat)
- 큰 `Event` 누적 — `--event-ttl=1h` 등으로 짧게
- 다수의 `ConfigMap`/`Secret` 변경
- 컨트롤러가 매번 status update 해서 revision 을 빠르게 소진하는 케이스 (잘못 만든 사용자 컨트롤러)

```bash
# revision 증가 속도 모니터링
watch -n5 "ETCDCTL_API=3 etcdctl endpoint status -w table"

# 큰 키 찾기
ETCDCTL_API=3 etcdctl get / --prefix --keys-only | \
  awk -F'/' '{print $2}' | sort | uniq -c | sort -rn | head
```

`/registry/events`, `/registry/leases` 가 상위에 보이면 위 정책 튜닝 후보.

## 7. Backup / Restore 와 compaction 윈도우

Velero 와 etcd 의 snapshot save:

```bash
ETCDCTL_API=3 etcdctl snapshot save backup.db \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=ca.crt --cert=client.crt --key=client.key
```

snapshot 은 *현재 시점의 BoltDB 파일 사본* 이다. 시점 복구는 *그 시점에 compaction 되지 않았던 revision 까지* 가능. compaction 윈도우가 짧으면 시점 복구 가능 범위도 좁다. RTO/RPO 와 retention 정책의 trade-off 를 명시적으로 설계해야 한다.

복구는 새 데이터 디렉터리 만들고 `etcdctl snapshot restore` → 새 멤버로 부팅 → 클러스터 멤버 추가 순서. *기존 클러스터 옆에서 새 클러스터 만들고 apiserver 의 etcd endpoint 변경* 이 가장 안전하다.

## 8. 함정과 흔한 실수

1. **defrag 중 quorum 손실**: 3-멤버 클러스터에서 *동시에 2 멤버 defrag*. quorum 1 / 3 으로 떨어져 쓰기 불가. 필히 1대씩
2. **kube-apiserver 와 etcd 양쪽 auto-compaction 켜둠**: revision 두 번 정리. 동작은 멀쩡한데 메트릭 이중 카운트로 진단이 어려워진다
3. **`quota-backend-bytes` 너무 작게 설정**: production 에서는 8GiB 권장. 2GiB 에선 큰 클러스터가 금세 NOSPACE
4. **snapshot restore 후 compact 미수행**: 새 클러스터의 revision 이 0 부터라 한동안은 정상이지만, 옛 객체들이 동시에 들어왔을 때 한 번에 mod_revision 이 큰 값들을 가져 *과거 형태로 복원* 시 `--initial-cluster-token` 일치까지 챙기지 않으면 split brain
5. **TLS 키 권한 600 미적용**: `etcdctl` 호출 시 권한 거부

## 9. 실측: 4-노드 K8s 1.30 클러스터 1주

| 메트릭 | 값 |
|---|---|
| revision 증가 속도 | ~30 rps (피크 80) |
| etcd_mvcc_db_total_size_in_bytes | 1.2 GiB |
| etcd_mvcc_db_total_size_in_use_bytes | 350 MiB |
| in_use 비율 | 29% |
| compaction 주기 (auto periodic) | 5분, 12 시간 retention |
| defrag 주기 | 매주 일요일 02:00 롤링 |
| defrag 1회 소요 | 멤버당 18~25 초 |

이 정도 규모는 안정적. revision 증가 속도가 *수백 rps* 를 넘기는 멀티테넌트 클러스터는 etcd 자체를 *별도 머신* 으로 빼고, NVMe + jumbo frame 네트워크를 권장. etcd 는 `fdatasync` 동기 디스크 I/O 가 핵심 병목이라 디스크 latency 5ms 이상이면 leader election timeout 에 닿기 시작한다.

## 참고

- etcd 공식 문서 — Maintenance / Compaction / Defragmentation
- Kubernetes etcd Operations 문서
- "etcd: A Distributed, Reliable Key-Value Store" — bbolt 설계 슬라이드
- Red Hat Customer Portal — "Recovering from etcd database space exceeded"
- KubeCon Talks — "Tuning etcd for Large Kubernetes Clusters"
