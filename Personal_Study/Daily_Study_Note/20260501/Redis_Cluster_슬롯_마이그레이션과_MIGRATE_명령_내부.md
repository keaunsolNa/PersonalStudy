Notion 원본: https://app.notion.com/p/3535a06fd6d381d393c4c239154d4917

# Redis Cluster 슬롯 마이그레이션과 MIGRATE 명령 내부 동작

> 2026-05-01 신규 주제 · 확장 대상: Redis

## 학습 목표

- Redis Cluster 의 16384 슬롯 분배 모델과 CRC16 해싱 동작 분석
- `CLUSTER SETSLOT IMPORTING/MIGRATING/STABLE` 상태 전이와 ASK/MOVED 리다이렉트 차이
- `MIGRATE` 명령의 RESTORE/DUMP 페이로드 포맷과 atomicity 보장 메커니즘
- 운영 시 Hot key 마이그레이션의 성능 영향과 Resharding 전략 수립

## 1. 16384 슬롯 모델 — 왜 16384인가

Redis Cluster 는 키스페이스를 16384(=2^14)개 슬롯으로 나눈다. 각 키의 슬롯 번호는 `CRC16(key) % 16384` 로 계산된다. 16384 라는 숫자는 임의가 아니라 두 제약의 균형점이다.

첫째, **gossip 메시지 크기**. 각 노드는 자신이 소유한 슬롯 비트맵을 PING/PONG 메시지에 실어 다른 노드에 전파한다. 16384 비트 = 2KB 다. 노드 100개 클러스터에서 매초 ping 이 오가면 200KB/s. 이게 65536 슬롯이라면 비트맵만 8KB, 메시지 부담이 4배가 된다. 둘째, **운영 단위의 합리성**. 1000개 노드 클러스터를 가정해도 노드당 약 16 슬롯이라 이동 단위가 너무 잘게 쪼개지지 않는다.

```bash
$ redis-cli -c -p 7000 cluster keyslot "user:1234"
(integer) 12783

$ redis-cli -c -p 7000 cluster countkeysinslot 12783
(integer) 1

$ redis-cli -c -p 7000 cluster getkeysinslot 12783 100
1) "user:1234"
```

## 2. Hash Tag — 같은 슬롯에 키를 묶는 유일한 방법

`MULTI/EXEC`, `EVAL`, `SUNION` 처럼 여러 키를 다루는 명령은 모든 키가 같은 슬롯에 있어야 한다. 클러스터 모드에서는 명시적으로 `{...}` 브래킷을 써서 강제한다.

```
user:{1234}:profile  → CRC16("1234") % 16384
user:{1234}:orders   → CRC16("1234") % 16384  ← 같은 슬롯
user:1234:profile    → CRC16("user:1234:profile") % 16384  ← 다른 슬롯
```

태그는 첫 `{` 와 첫 `}` 사이의 문자열만 본다. 비어 있거나 찾지 못하면 키 전체를 해싱한다. 이 규칙은 마이그레이션 단위와도 직결된다 — 같은 슬롯의 키는 묶여서 이동하니, 관계 있는 키는 반드시 같은 hash tag 를 갖도록 키 네이밍을 설계해야 한다.

## 3. SETSLOT 4단계 상태 전이

슬롯 12000을 노드 A → 노드 B 로 옮기는 흐름.

```
1. (B) CLUSTER SETSLOT 12000 IMPORTING <nodeA-id>
2. (A) CLUSTER SETSLOT 12000 MIGRATING <nodeB-id>
3. (A) 슬롯 내 키 K1, K2, K3 ... 를 MIGRATE 로 B 에 전송
4. (A, B) CLUSTER SETSLOT 12000 NODE <nodeB-id>
   (또는 게이트웨이 노드에 브로드캐스트)
```

상태 1+2 동안 A 는 "이 슬롯을 옮기는 중" 표시를 띄운다. 클라이언트가 A 에 GET 을 보내면, 키가 아직 A 에 있으면 정상 응답하고, A 에 없으면 `-ASK <slot> <nodeB-host:port>` 응답을 돌려준다. ASK 는 일회성 리다이렉트다. 클라이언트는 B 에 `ASKING` 명령 한 번 + 실제 명령 한 번을 보내야 응답을 받는다.

```
client → A: GET user:{1234}:profile
A     → client: -ASK 12000 192.168.0.2:7001
client → B: ASKING
B     → client: OK
client → B: GET user:{1234}:profile
B     → client: "..."
```

마이그레이션 완료 후의 4단계는 새 슬롯 owner 를 클러스터 전체에 전파한다. 이후 A 에 보낸 명령은 `-MOVED` 응답으로 영구 리다이렉트된다. ASK 와 MOVED 의 차이는 영속성: ASK 는 "이번만", MOVED 는 "이제 항상".

## 4. MIGRATE 명령 — RESTORE 위에 얹은 atomicity

`MIGRATE` 는 키 한 개 또는 여러 개를 다른 인스턴스로 옮긴다. 내부적으로 다음 시퀀스를 수행한다.

```
1. (source) DUMP <key>           → serialized binary payload
2. (source) -> (dest) RESTORE <key> <ttl> <payload> [REPLACE]
3. (dest) -> (source) +OK
4. (source) DEL <key>            (REPLACE 안 됐을 때만)
```

페이로드는 RDB 직렬화 포맷에 6바이트 footer(2바이트 RDB 버전 + 4바이트 CRC64) 가 붙은 형태다. 같은 메이저 RDB 버전에서만 호환되므로, 클러스터 노드의 Redis 버전이 다르면 마이그레이션이 실패할 수 있다.

```bash
$ redis-cli -p 7000 MIGRATE 192.168.0.2 7001 "user:{1234}:profile" 0 5000 REPLACE
OK
$ redis-cli -p 7000 MIGRATE 192.168.0.2 7001 "" 0 5000 REPLACE KEYS k1 k2 k3
OK
```

`KEYS` 옵션으로 여러 키를 한 번에 보낼 때는 dest 에 pipeline 형태로 RESTORE 가 일괄 전송된다. 네트워크 라운드트립이 줄어들어 처리량이 5~10배 향상된다 — `redis-cli --cluster reshard` 가 내부적으로 쓰는 방식이다.

원자성은 source 의 GIL 같은 단일 스레드 모델 덕분이다. MIGRATE 가 실행되는 동안 source 는 다른 명령을 처리하지 않는다. 따라서 "이전됐는지 안 됐는지 중간 상태" 가 외부에서 보이지 않는다. 하지만 dest 가 비동기로 응답하기 전에 네트워크가 끊기면 source 는 키를 지우지 않으므로, 양쪽에 같은 키가 잠시 존재할 수 있다 — REPLACE 옵션이 이때 안전망이 된다.

## 5. 큰 키의 함정 — Latency Spike

MIGRATE 는 **단일 명령 내에서 동기적**으로 dump-send-restore-delete 를 한다. 키 하나가 100MB 라면 그 시간 동안 source 는 모든 클라이언트 요청을 블록한다.

| 키 크기 | DUMP 시간 | 네트워크 전송 (1Gbps) | 총 차단 시간 |
|---|---|---|---|
| 1KB | <1ms | <1ms | ~1ms |
| 1MB | 5ms | 8ms | ~15ms |
| 10MB | 40ms | 80ms | ~120ms |
| 100MB | 500ms | 800ms | ~1.5s |

100MB 키의 마이그레이션이 진행되는 1.5초 동안 모든 다른 명령이 대기한다. 이 때문에 **Big Key 발견과 분할** 이 운영 책임이다. `redis-cli --bigkeys` 또는 `MEMORY USAGE <key>` 로 사전 점검 후, 큰 hash/sorted-set 은 hash tag 를 활용한 partition 으로 쪼갠다.

```
# Bad
big-set            10M elements

# Good (hash tag로 같은 슬롯 보장)
big-set:{shard0}    1M elements
big-set:{shard1}    1M elements
...
big-set:{shard9}    1M elements
```

## 6. Resharding — 운영 자동화

`redis-cli --cluster reshard <ip:port>` 가 노드 간 슬롯 재분배를 자동화한다. 내부 흐름.

1. 클러스터 토폴로지 조회 (CLUSTER NODES)
2. 옮길 슬롯 수와 source/dest 결정
3. 각 슬롯에 대해 SETSLOT IMPORTING/MIGRATING 발급
4. `CLUSTER GETKEYSINSLOT <slot> 100` 으로 100개씩 키 가져와 MIGRATE
5. 슬롯이 비면 SETSLOT NODE 로 owner 변경 브로드캐스트

`--pipeline` 옵션으로 한 번에 옮기는 키 개수를 늘릴 수 있다(기본 10). 네트워크 대역폭과 source latency 사이의 트레이드오프다. 운영 환경에서는 보통 16~32 정도가 무난하다.

```bash
redis-cli --cluster reshard 192.168.0.1:7000 \
    --cluster-from <nodeA> \
    --cluster-to <nodeB> \
    --cluster-slots 100 \
    --cluster-yes \
    --cluster-pipeline 16
```

## 7. CLUSTER FAILOVER 와의 상호작용

마이그레이션 중에 source 가 죽으면? source 의 replica 가 승격되면서 IMPORTING/MIGRATING 상태가 그대로 인계된다(상태는 cluster bus gossip 으로 전파된 상태이므로 새 master 도 알고 있다). 클라이언트는 일시적으로 `-ASK` 응답을 받지만 결국 정합성을 유지한다.

다만 **수동 failover 로 마이그레이션 중 master 를 바꾸는 것은 금지**다. `CLUSTER FAILOVER FORCE` 는 정상 인계 절차를 건너뛰므로, 마이그레이션 미완료 키를 잃을 수 있다. 운영 룰: resharding 중에는 failover 작업을 하지 않는다.

## 8. 클라이언트 측 캐시 — Slot Map

Java 의 Lettuce, Jedis Cluster, Node.js 의 ioredis 등 모든 클라이언트는 `CLUSTER SLOTS`(또는 `CLUSTER SHARDS`) 결과를 로컬 캐시한다. MOVED 응답을 받으면 캐시를 갱신한다. ASK 응답은 캐시를 갱신하지 않는다(일회성이므로).

Lettuce 기본 설정 예시.

```java
RedisURI uri = RedisURI.builder()
    .withHost("192.168.0.1").withPort(7000)
    .build();

RedisClusterClient client = RedisClusterClient.create(uri);
client.setOptions(ClusterClientOptions.builder()
    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
        .enableAllAdaptiveRefreshTriggers()
        .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30))
        .enablePeriodicRefresh(Duration.ofMinutes(5))
        .build())
    .build());
```

`enableAllAdaptiveRefreshTriggers()` 가 MOVED/ASK/persistent reconnect 같은 신호를 감지해 토폴로지를 즉시 다시 받는다. 이게 꺼져 있으면 마이그레이션 후 클라이언트가 한참 동안 잘못된 노드로 요청을 보내고 매번 MOVED 를 받는 패턴이 나온다.

## 9. 모니터링 지표

| 지표 | 의미 | 주의 임계 |
|---|---|---|
| `cluster_slots_assigned` | 16384 외 값이면 slot 누락 | != 16384 |
| `cluster_slots_pfail` | 의심 노드 수 | > 0 즉시 조사 |
| `cluster_state` | ok / fail | fail 이면 일부 슬롯 unavailable |
| `migrate_cached_sockets` | source ↔ dest 재사용 소켓 수 | 1000 넘으면 메모리 낭비 점검 |
| 응답 시간 P99 | 마이그레이션 중 spike | 평소 대비 5배 넘으면 pause |

`CLUSTER INFO` 와 `INFO migrate` 섹션을 주기적으로 수집해 Prometheus + Grafana 대시보드로 가시화한다.

## 10. 트레이드오프 정리

- **장점**: 다운타임 0의 capacity 확장. 키 단위로 점진적 이동.
- **단점**: 큰 키 한 개가 lock 시간을 끌고, 마이그레이션 중 ASK 리다이렉트로 latency 가 증가한다(평균 +50~100% RTT).
- **대안**: Cluster 가 아닌 Sentinel + 여러 샤드 vs. 외부 proxy(Twemproxy, codis). 운영 부담은 줄지만 atomic 마이그레이션이 없다.

대부분의 경우 Cluster 가 정답이다. 단, 키 사이즈를 1MB 미만으로 통제하고 hash tag 를 일관되게 쓴다는 두 규칙을 지켜야 운영이 평온하다.

## 참고

- Redis Cluster Specification — https://redis.io/docs/management/scaling/
- Redis Cluster Tutorial — https://redis.io/docs/manual/scaling/
- "Redis in Action" by Josiah Carlson, Manning
- antirez(Salvatore Sanfilippo) 블로그: Cluster 설계 결정 — http://antirez.com/news/79
- Lettuce 공식 문서: Cluster — https://lettuce.io/core/release/reference/#redis-cluster
- Redis 7 RESP3 spec — https://github.com/redis/redis-specifications/blob/master/protocol/RESP3.md
