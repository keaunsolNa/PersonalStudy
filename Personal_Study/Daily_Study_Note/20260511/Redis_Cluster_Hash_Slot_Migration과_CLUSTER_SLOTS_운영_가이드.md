Notion 원본: https://www.notion.so/35d5a06fd6d381b3ad45fd2622aca334

# Redis Cluster Hash Slot Migration과 CLUSTER SLOTS 운영 가이드

> 2026-05-11 신규 주제 · 확장 대상: Redis

## 학습 목표

- Redis Cluster 의 16,384개 hash slot 이 어떻게 노드에 분배되고, key → slot 매핑이 어떤 함수(CRC16)로 결정되는지 본다
- slot reshard 또는 노드 추가/제거 시 일어나는 *MIGRATING / IMPORTING* 상태 머신, `ASK` 와 `MOVED` redirection 의 차이, multi-key command 와 hashtag 의 제약을 코드와 함께 정리한다
- `CLUSTER SLOTS` / `CLUSTER SHARDS` / `CLUSTER COUNTKEYSINSLOT` / `CLUSTER GETKEYSINSLOT` 을 활용한 실제 migration 절차와, redis-cli `--cluster reshard` 의 내부 동작
- production 의 무중단 reshard 운영 — 클라이언트 (Lettuce / Jedis / redis-py) 가 redirection 을 어떻게 처리하는지, latency 충격, replica 우선 운영, 슬롯 imbalance 자동 감지

## 1. 16,384 slots — 왜 그 숫자인가

Redis Cluster 는 key 를 16,384(=2^14) 개의 hash slot 으로 나눈다. 각 노드는 일부 slot 의 *master* 이고, 동일 slot 의 *replica* 가 다른 노드에 존재한다.

```
slot = CRC16(key) mod 16384
```

`{...}` (hashtag) 가 key 에 있으면 그 중괄호 안의 부분 문자열로만 CRC16 을 계산한다. `user:{12345}:cart` 와 `user:{12345}:profile` 은 같은 slot 으로 가서 *MULTI / 트랜잭션 / Lua* 가 가능해진다.

16,384 가 선택된 이유는 *gossip 메시지 크기 최적화* 다. cluster bus 의 ping/pong 메시지가 자기 slot bitmap 을 16 KB / 8 = 2 KB 로 운반하기 때문. 4 KB 였다면 65,536 slot 도 가능했겠지만 그만큼 cluster bus 트래픽이 두 배가 된다.

## 2. CLUSTER SLOTS / CLUSTER SHARDS

`CLUSTER SLOTS` 는 *deprecated* 다(8.0 에서 표시). 권장은 `CLUSTER SHARDS`.

```
CLUSTER SHARDS
1) 1) "slots"
   2) (integer) 0
   3) (integer) 5460
   4) "nodes"
   5) 1) 1) "id"
         2) "07c37dfe..."
         3) "port"
         4) (integer) 6379
         5) "role"
         6) "master"
```

기존 `CLUSTER SLOTS` 의 한계는 *replication offset 노출이 없어* replica 가 얼마나 뒤처졌는지를 클라이언트가 알 수 없다는 점. 7.0 이후엔 `CLUSTER SHARDS` 로 마이그레이션 권장.

## 3. MIGRATING / IMPORTING 상태 머신

slot 5,000 을 노드 A → B 로 옮긴다고 하자. 절차는 다음과 같다.

```
[B] CLUSTER SETSLOT 5000 IMPORTING <A-id>
[A] CLUSTER SETSLOT 5000 MIGRATING <B-id>
loop:
  [A] CLUSTER GETKEYSINSLOT 5000 100  → 키 100 개 추출
  [A] MIGRATE <B-host> <B-port> "" 0 5000 KEYS k1 k2 ...
[A,B 그리고 모든 노드] CLUSTER SETSLOT 5000 NODE <B-id>
```

이 시퀀스 중에 클라이언트가 slot 5000 의 key 를 읽으려 하면 두 가지 redirection 이 발생한다.

- **MOVED <slot> <host:port>**: 슬롯 소유권이 영구 이동되었음. 클라이언트는 *cluster topology 캐시를 갱신* 하고 그쪽으로 재시도.
- **ASK <slot> <host:port>**: 임시. 해당 키만 다른 노드에 있다는 뜻. 클라이언트는 `ASKING` 명령을 먼저 보내고 그 다음에 원래 명령을 보낸다. *topology 는 갱신하지 않는다*.

MIGRATING 중인 슬롯에서 *아직 안 옮긴 키* 는 A 에 그대로 있고, *옮긴 키* 는 B 에 있다. A 가 자기에게 없는 키 요청을 받으면 `ASK` 를 답하고, 이미 다 끝나서 SETSLOT NODE 가 모든 노드에 전파된 뒤부퀶 `MOVED` 가 답이다.

## 4. redis-cli --cluster reshard 의 내부

`redis-cli --cluster reshard <host>:<port> --cluster-from <src-ids> --cluster-to <dst-id> --cluster-slots <n>` 는 위 시퀀스를 자동화한다. 내부는 Ruby/C 시절의 `redis-trib.rb` 가 redis-cli 로 통합된 것.

핵심 옵션:

- `--cluster-yes`: 확인 프롬프트 스킵 (자동화에 필수)
- `--cluster-pipeline N`: 한 번에 옮길 키 수 (기본 10). 100 으로 올리면 latency 가 늘지만 reshard 가 빨라짐
- `--cluster-timeout MS`: MIGRATE 호출의 timeout

production 에서 *latency 충격 최소화* 가 목표면 `--cluster-pipeline 1` 으로 한 키씩 옮기되 reshard 시간이 길어지는 것을 감수한다.

## 5. multi-key command 와 hashtag

cluster 모드에서 multi-key 명령 (`MGET`, `SUNIONSTORE`, `EVAL with KEYS`, `MULTI`) 은 *모든 키가 같은 slot* 일 때만 동작한다. 다르면 `CROSSSLOT Keys in request don't hash to the same slot` 에러.

해법 1 — hashtag 사용:

```
SET user:{12345}:cart "..."
SET user:{12345}:profile "..."
MGET user:{12345}:cart user:{12345}:profile
```

해법 2 — Lua / EVAL 회피: 각 노드별로 키를 분배해 pipeline 로 분산 호출. 클라이언트가 노드 매핑을 알고 있어야 함. Lettuce 의 `NodeSelection` API 가 이 패턴 지원.

hashtag 를 함부로 쓰면 *모든 키가 한 슬롯* 으로 몰려 hotspot 노드가 생긴다. 사용자 id 같이 *distribution 균등한 키* 만 hashtag 안에 넣는 게 원칙.

## 6. 클라이언트 라이브러리의 redirection 처리

**Lettuce (Java)**: `RedisClusterClient` 가 topology refresh 를 자동/주기/이벤트 기반으로 한다.

```java
ClusterClientOptions options = ClusterClientOptions.builder()
		.topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
				.enablePeriodicRefresh(Duration.ofSeconds(30))
				.enableAllAdaptiveRefreshTriggers()
				.build())
		.maxRedirects(3)
		.build();
RedisClusterClient client = RedisClusterClient.create(uri);
client.setOptions(options);
```

`enableAllAdaptiveRefreshTriggers` 가 MOVED 응답을 보면 즉시 topology 를 다시 받아 클라이언트 캐시를 갱신한다. `maxRedirects` 가 3 이면 redirection 이 3번 연속이면 포기 후 예외.

**redis-py (Python)**: `redis.RedisCluster` 가 동일 패턴.

```python
from redis.cluster import RedisCluster, ClusterNode

rc = RedisCluster(
    startup_nodes=[ClusterNode("redis-0", 6379)],
    require_full_coverage=False,
    reinitialize_steps=10,
)
```

`reinitialize_steps=10` 은 10 번의 MOVED 마다 cluster topology 를 다시 fetch 한다.

## 7. 무중단 reshard — production runbook

1. *replica 우선 확인* — `INFO replication` 으로 모든 master 의 lag < 50ms 확인
2. cluster-bus 가 사용하는 +10000 port 가 모든 노드 간 양방향 열려 있나
3. `redis-cli --cluster check` 로 *epoch / config 일관성* 확인
4. peak 시간 회피 — reshard 도중 latency p99 가 1.5~3x 로 증가하는 게 일반적
5. `--cluster-pipeline` 을 처음엔 10 (기본) 으로 두고, 모니터링 보면서 점진 상향
6. *모든* node 가 SETSLOT NODE 메시지를 받았는지 확인 (gossip 전파에 보통 수 초)
7. 작업 후 `CLUSTER COUNTKEYSINSLOT` 으로 *원본 노드에 잔여 키 0* 확인

reshard 중 latency 가 튀는 주된 원인은 *MIGRATE blocking*, *cluster bus 트래픽 증가*, *클라이언트 redirection 누적* 의 셋이다.

`SLOWLOG GET 128` 으로 reshard 직후의 slow command 를 확인하고, 100ms 이상 걸린 MIGRATE 가 보이면 pipeline 을 낮춰야 한다.

## 8. slot imbalance 감지 자동화

```bash
#!/usr/bin/env bash
# slot-balance.sh — 노드별 슬롯 수가 평균 ±10% 이내인지 검사
set -euo pipefail
HOST=${1:-127.0.0.1}
PORT=${2:-6379}

redis-cli -h "$HOST" -p "$PORT" cluster nodes \
  | awk '$3 ~ /master/ { n=split($0,a," "); slots=0;
      for (i=9; i<=n; i++) {
        if (a[i] ~ /^[0-9]+-[0-9]+$/) { split(a[i],r,"-"); slots+=r[2]-r[1]+1 }
        else if (a[i] ~ /^[0-9]+$/) { slots+=1 }
      }
      print $2, slots
    }'
```

이걸 30분마다 CI/CD 가 돌려 Slack 으로 알리면, gossip 중 누락된 SETSLOT 같은 *조용한 sklew* 를 빠르게 알아채일 수 있다.

## 9. 자주 빠지는 함정

1. **hashtag 남용**: 모든 사용자 키를 `{tenant_id}` 로 감싸면 tenant 가 거대해질수록 한 슬롯에 몰림. 핫 슬롯의 master CPU 가 saturate 된다.
2. **`require_full_coverage` 의 의미**: 일부 slot 의 master 가 down 이면 *전체 cluster* 가 read/write 거부. 운영상 false 로 두는 게 더 가용성 높다.
3. **TTL 큰 키 MIGRATE 의 cost**: MIGRATE 는 *직렬화 + 네트워크 전송* 이라 큰 hash/zset 한 개가 100ms 이상 잡힌다. 키 단위 size 를 `MEMORY USAGE key` 로 사전 측정.
4. **Lua 스크립트의 cross-slot**: `EVAL` 의 KEYS 인자는 동일 slot 이어야 한다. ARGV 로 키처럼 보이는 값을 넣어도 Redis 는 검사 못 한다.
5. **failover 와 reshard 동시 진행**: master 가 죽고 replica 가 promote 되는 동안 reshard 명령은 *fail* 한다. peak 시간엔 둘 중 하나만.

## 참고

- Redis Cluster Specification — https://redis.io/docs/management/scaling/
- *Redis in Action* (Josiah L. Carlson), Ch.10 Scaling
- Antirez 블로그 — *Redis Cluster: A Pragmatic Approach to Distribution*
- Lettuce Cluster API — https://lettuce.io/core/release/reference/index.html#redis.cluster
- redis-py Cluster Docs — https://redis-py.readthedocs.io/en/stable/clustering.html
- *Designing Data-Intensive Applications* (Martin Kleppmann), Ch.6 Partitioning
