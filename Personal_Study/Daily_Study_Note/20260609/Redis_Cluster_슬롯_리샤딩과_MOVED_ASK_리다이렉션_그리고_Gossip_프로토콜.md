Notion 원본: https://www.notion.so/37a5a06fd6d381e4b14fded4dbb0dea7

# Redis Cluster 슬롯 리샤딩과 MOVED/ASK 리다이렉션 그리고 Gossip 프로토콜

> 2026-06-09 신규 주제 · 확장 대상: Redis

## 학습 목표

- 16384 해시 슬롯 기반 데이터 분산과 키-슬롯 매핑(CRC16)을 설명한다
- 리샤딩 중 MOVED와 ASK 리다이렉션의 차이와 클라이언트 동작을 구현 관점에서 이해한다
- Gossip 기반 클러스터 버스로 노드 상태와 failover가 전파되는 과정을 분석한다
- hash tag, MIGRATE, 클라이언트 슬롯 맵 캐싱 등 운영 함정을 다룬다

## 1. 16384 해시 슬롯과 키 매핑

Redis Cluster는 키를 16384개의 고정 해시 슬롯에 분산한다. 키의 슬롯은 `CRC16(key) mod 16384`로 결정되고, 각 마스터 노드가 슬롯 구간을 소유한다. 슬롯 수가 16384인 이유는 노드 간 상태 교환 시 슬롯 비트맵(2KB)이 충분히 작으면서도, 현실적 노드 수(수백) 대비 슬롯 분할 입도가 적절하기 때문이다.

```
CLUSTER KEYSLOT user:1000   → 예: 5798
CLUSTER NODES               → 각 노드의 slot 범위 확인
# 노드 A: 0-5460, 노드 B: 5461-10922, 노드 C: 10923-16383
```

여러 키를 한 명령(MSET, 트랜잭션, Lua)으로 묶으려면 같은 슬롯에 있어야 한다. 이를 위한 hash tag는 `{}` 안의 부분만 해싱한다. `user:{1000}:profile`와 `user:{1000}:cart`는 `1000`만 해싱되어 같은 슬롯에 떨어진다.

## 2. 리샤딩의 본질: 슬롯 단위 이동

스케일 아웃·인은 슬롯을 노드 간 이동하는 것으로 구현된다. `redis-cli --cluster reshard`는 대상 슬롯들을 출발 노드(source)에서 도착 노드(target)로 옮긴다. 슬롯 이동은 슬롯에 속한 키들을 하나씩 `MIGRATE`로 원자 전송하는 과정의 연속이다.

```
# 슬롯 마이그레이션의 내부 단계
1) CLUSTER SETSLOT <slot> IMPORTING <source-id>   (target 에서)
2) CLUSTER SETSLOT <slot> MIGRATING <target-id>   (source 에서)
3) source 에서 키 목록 추출: CLUSTER GETKEYSINSLOT <slot> <count>
4) 각 키: MIGRATE <target-host> <port> "" 0 <timeout> KEYS <key...>
5) 완료 후: CLUSTER SETSLOT <slot> NODE <target-id>  (양쪽 + 전파)
```

이동 중 슬롯은 source에서 MIGRATING, target에서 IMPORTING 상태가 된다. 이 "절반 옮겨진" 상태에서 클라이언트 요청을 어떻게 처리하느냐가 MOVED/ASK 리다이렉션의 핵심이다.

## 3. MOVED 리다이렉션: 영구 재배치 통지

클라이언트가 슬롯을 소유하지 않은 노드에 요청하면, 그 노드는 `-MOVED <slot> <host>:<port>` 에러로 올바른 노드를 알려준다. MOVED는 "이 슬롯은 이제 저 노드 소유다, 슬롯 맵을 갱신하라"는 영구적 신호다.

```
GET user:1000
-MOVED 5798 127.0.0.1:7001
# 클라이언트는 7001 로 재요청하고, 로컬 슬롯 맵에서 5798 → 7001 을 갱신
```

성숙한 클라이언트(Lettuce, Redisson, redis-py-cluster)는 시작 시 `CLUSTER SLOTS`로 전체 슬롯 맵을 캐싱하고, MOVED를 받으면 맵을 갱신한다. 매 요청마다 리다이렉션을 격지 않도록 맵을 미리 갖고 있는 것이 정상 경로이며, MOVED는 토폴로지 변경 직후 한 번만 발생하는 것이 이상적이다.

## 4. ASK 리다이렉션: 일시적 진행 중 이동

리샤딩이 진행 중인 슬롯에 대해서는 ASK가 쓰인다. source 노드는 자신에게 키가 아직 있으면 응답하고, 없으면(이미 target으로 옮겨졌으면) `-ASK <slot> <target>`을 반환한다. ASK는 "이 키 하나는 지금 저쪽에 있다, 단 슬롯 소유권이 바뀜 건 아니다"라는 일시적 신호다.

```
# source(MIGRATING 상태)에 GET 요청, 키가 이미 옮겨졌으면:
-ASK 5798 127.0.0.1:7001
# 클라이언트는 target 에 ASKING 먼저 보낸 뒤 명령 재전송:
ASKING
GET user:1000
```

MOVED와 결정적 차이는 (1) ASK는 슬롯 맵을 갱신하지 않는다 — 슬롯 소유권은 여전히 source다. (2) target에 보내기 전에 반드시 `ASKING`을 선행해야 한다. `ASKING`은 IMPORTING 슬롯의 키를 일시적으로 처리하도록 허용하는 플래그다. 이 한 키만 예외 처리하고, 다음 요청은 다시 source로 간다.

| 구분 | MOVED | ASK |
|---|---|---|
| 의미 | 슬롯 영구 재배치 | 키 단위 일시 이동(진행 중) |
| 슬롯 맵 갱신 | 한다 | 하지 않는다 |
| 선행 명령 | 없음 | `ASKING` 필수 |
| 발생 시점 | 리샤딩 완료 후 | 리샤딩 진행 중 |

## 5. Gossip 프로토콜과 클러스터 버스

노드들은 데이터 포트와 별도의 클러스터 버스 포트(데이터 포트 + 10000)를 통해 바이너리 Gossip 프로토콜로 상태를 교환한다. 각 노드는 주기적으로 임의의 다른 노드에 PING을 보내고 PONG을 받으며, 메시지 안에 자신이 아는 다른 노드들의 상태(살아있음/PFAIL/FAIL)를 함께 싣어 전파한다.

```
# 노드가 알게 되는 정보 (Gossip 으로 수렴)
- 각 노드의 IP/포트, 마스터/레플리카 역할
- 슬롯 소유권 맵 (epoch 로 버전 관리)
- 노드 헬스 상태: PFAIL(의심) → FAIL(과반 동의 시 확정)
```

특정 노드가 `cluster-node-timeout` 동안 응답이 없으면 PFAIL(possible fail)로 표시하고, 이 의심을 Gossip으로 퍼뜨린다. 과반수 마스터가 같은 노드를 PFAIL로 보고하면 FAIL로 승ꈁ된다. 이 분산 합의가 단일 장애 판정자를 없애 split-brain 위험을 줄인다.

## 6. Failover와 epoch 충돌 해소

마스터가 FAIL로 확정되면 그 레플리카들이 선거를 시작한다. 레플리카는 currentEpoch를 올려 다른 마스터들에게 투표를 요청(FAILOVER_AUTH_REQUEST)하고, 과반 마스터의 표를 얻으면 새 마스터로 승ꈁ해 슬롯 소유권을 가져온다. configEpoch가 더 높은 노드의 슬롯 주장(claim)이 우선하므로, 충돌 시 더 최신 epoch가 이긴다.

```
# failover 흐름
1) replica: cluster-node-timeout 후 마스터 FAIL 감지
2) replica: currentEpoch++ 후 FAILOVER_AUTH_REQUEST 브로드캐스트
3) 다른 마스터들: 에포크당 1표, 과반 시 승인
4) 승격된 replica: 슬롯을 자기 소유로 claim, 더 높은 configEpoch 로 전파
```

`cluster-require-full-coverage=yes`(기본)이면 단 하나의 슬롯이라도 담당 노드가 없을 때 클러스터 전체가 쓰기를 거부한다. 가용성을 위해 `no`로 두면 살아있는 슬롯만 서비스하지만 부분 데이터 손실을 감수한다.

## 7. 클라이언트·운영 함정

가장 흔한 실수는 (1) hash tag 없이 멀티키 연산을 시도해 `CROSSSLOT` 에러를 만나는 것, (2) 클라이언트 슬롯 맵 캐시가 토폴로지 변경 후 갱신되지 않아 MOVED 폭주가 발생하는 것이다. Redisson·Lettuce는 `CLUSTER SLOTS`를 주기 갱신하거나 MOVED 수신 시 즉시 갱신한다.

```java
// Lettuce: 주기적 토폴로지 갱신 활성화 (운영 권장)
ClusterTopologyRefreshOptions topologyRefresh =
    ClusterTopologyRefreshOptions.builder()
        .enablePeriodicRefresh(Duration.ofSeconds(30))
        .enableAllAdaptiveRefreshTriggers() // MOVED/ASK 등 트리거 시 갱신
        .build();

ClusterClientOptions options = ClusterClientOptions.builder()
        .topologyRefreshOptions(topologyRefresh)
        .build();
```

리샤딩 중 대량 ASK가 발생하면 클라이언트 왕복이 늘어 지연이 증가한다. 따라서 리샤딩은 트래픽이 적은 시간대에 `--cluster-pipeline`으로 슬롯당 키 이동 배치 크기를 조정하며 점진 수행하는 것이 안전하다.

## 8. Redisson 분산 락과 슬롯 인지

클러스터에서 분산 락·세마포어를 쓸 때도 슬롯이 관여한다. Redisson의 `RLock`은 락 키 하나에 묶이므로 해당 키의 슬롯을 소유한 노드에서 처리되고, failover 시 그 노드의 레플리카로 락 상태가 넘어간다. 단, Redis 복제는 비동기라서 마스터가 락을 부여한 직후 죽으면 레플리카에 락 정보가 없어 두 클라이언트가 동시에 락을 쥐 수 있다. 이 안전성 간극을 줄이는 것이 RedLock 알고리즘(여러 독립 마스터 과반 획득)이다.

```java
RedissonClient redisson = Redisson.create(config); // clusterServers 설정
RLock lock = redisson.getLock("order:lock:{1000}"); // hash tag 로 슬롯 고정
// 락 획득(대기 10s, 점유 30s 후 자동 해제 — watchdog 로 연장)
if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
    try { /* 임계 구역 */ }
    finally { lock.unlock(); }
}
```

`watchdog`은 작업이 길어지면 점유 시간을 자동 연장(기본 30초)해, 처리 중 락이 만료되어 다른 클라이언트가 끌어드는 것을 막는다. 분산 락은 복제 비동기성 때문에 절대적 상호배제는 보장되지 않는다는 한계를 인지해야 한다.

## 9. trade-off 정리

Redis Cluster는 단일 노드 메모리·처리량 한계를 슬롯 분산으로 넘지만, 멀티키 연산이 같은 슬롯으로 제약되고(hash tag 설계 부담), 리샤딩 중 ASK 리다이렉션으로 일시 지연이 생긴다. Gossip 기반 분산 장애 판정은 단일 장애점을 없애지만 `cluster-node-timeout` 설정에 따라 failover 민감도와 오탐(false positive)이 trade-off된다. 핵심 운영 원칙은 (1) 연관 키에 hash tag로 동일 슬롯 보장, (2) 클라이언트 토폴로지 자동 갱신 활성화, (3) 리샤딩은 저부하 시간대 점진 수행, (4) `cluster-node-timeout`을 네트워크 특성에 맞게 조정하는 것이다. 수평 확장성과 연산 단순성은 명백한 trade-off이며, 단일 인스턴스로 충분한 워크로드에 클러스터를 도입하면 복잡성만 늘어난다.

## 참고

- Redis 공식 문서 — Cluster Specification (https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)
- Redis 공식 문서 — Scale with Redis Cluster (resharding/ASK/MOVED)
- Lettuce Reference — Redis Cluster / Topology Refresh
- "Redis in Action" — 클러스터 슬롯과 가용성 설계 장
