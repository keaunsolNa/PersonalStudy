Notion 원본: https://www.notion.so/39b5a06fd6d381d3b087f513caaa9015

# Redis Stream Consumer Group과 XCLAIM 및 Cluster 슬롯 리샤딩

> 2026-07-12 신규 주제 · 확장 대상: Redis

## 학습 목표

- Redis Stream 의 엔트리 ID 구조와 Consumer Group 의 PEL(Pending Entries List) 동작을 설명한다.
- `XREADGROUP`, `XACK`, `XCLAIM`, `XAUTOCLAIM` 으로 at-least-once 소비와 장애 복구를 구현한다.
- Stream 이 무한 성장하지 않도록 `MAXLEN` / `MINID` 트리밍과 소비 지연을 관리한다.
- Cluster 모드에서 16384 해시 슬롯과 리샤딩이 Stream 키에 미치는 영향을 파악한다.

## 1. Stream 자료구조와 엔트리 ID

Redis Stream 은 append-only 로그다. 각 엔트리는 `<millisecondsTime>-<sequenceNumber>` 형식의 단조 증가 ID 를 가진다(예: `1720000000000-0`). 같은 밀리초에 여러 건이 들어오면 시퀀스가 증가한다. 이 ID 는 시간 순서를 보장하므로 범위 조회(`XRANGE`)와 재개(resume)의 커서 역할을 한다.

```
XADD orders * userId 42 amount 15000
# => "1720000000000-0"  (서버가 * 를 실제 ID 로 치환)
XLEN orders          # 엔트리 개수
XRANGE orders - +    # 처음(-)부터 끝(+)까지 전체 조회
```

Kafka 와 유사하게 Stream 은 소비돼도 데이터가 사라지지 않는다(명시 트리밍 전까지). 이 점이 Pub/Sub(fire-and-forget)이나 List(pop 시 소멸)와 근본적으로 다르며, 여러 소비자 그룹이 같은 로그를 독립적으로 재생할 수 있게 한다.

## 2. Consumer Group 과 PEL

Consumer Group 은 하나의 Stream 을 여러 소비자가 **분담**해서 처리하게 한다. 그룹은 "마지막으로 배분한 ID(last-delivered-id)"를 기억하고, `XREADGROUP` 요청마다 아직 배분 안 된 엔트리를 각 소비자에게 나눠 준다. 배분된 엔트리는 소비자별 PEL 에 "미확인(pending)" 상태로 올라가고, 소비자가 `XACK` 하기 전까지 남는다. 이 PEL 이 at-least-once 전달과 장애 복구의 핵심이다.

```
XGROUP CREATE orders workers $ MKSTREAM
# $ = 그룹 생성 시점 이후의 새 엔트리부터 소비. 0 이면 처음부터.

XREADGROUP GROUP workers consumer-1 COUNT 10 BLOCK 2000 STREAMS orders >
# > = "이 그룹에 아직 배분 안 된 새 메시지" 를 달라는 특수 ID
```

`>` 대신 구체 ID(예: `0`)를 주면 해당 소비자의 PEL 중 그 ID 이후 미확인분을 다시 받아온다. 프로세스 재시작 후 자기 PEL 을 먼저 재처리(`0` 조회 → 처리 → XACK)한 다음 `>` 로 새 메시지를 받는 패턴이 표준 복구 루틴이다.

```
XACK orders workers 1720000000000-0   # 처리 완료 통보 -> PEL 에서 제거
XPENDING orders workers               # 그룹 전체 미확인 요약(개수, 최소/최대 ID, 소비자별)
```

## 3. XCLAIM / XAUTOCLAIM — 죽은 소비자 복구

소비자 프로세스가 죽으면 그가 받아 간 엔트리는 그의 PEL 에 영원히 남아 처리되지 않는다. 다른 살아있는 소비자가 이를 **넘겨받아야(claim)** 한다. `XCLAIM` 은 idle 시간(마지막 배분 이후 경과)이 임계치를 넘은 엔트리의 소유권을 지정 소비자로 올긴다.

```
# idle 이 60초 넘은 pending 엔트리를 consumer-2 가 인수
XCLAIM orders workers consumer-2 60000 1720000000000-0 1720000000000-1
```

수동으로 ID 를 나열하는 대신 Redis 6.2+ 의 `XAUTOCLAIM` 이 스캔+claim 을 한 번에 한다.

```
XAUTOCLAIM orders workers consumer-2 60000 0 COUNT 25
# start=0 부터 스캔, idle>60s 인 것 최대 25개를 consumer-2 로 이관.
# 반환값의 첫 요소가 다음 스캔 커서.
```

주의: claim 은 배송 카운터(delivery count)를 증가시킨다. `XPENDING` 확장 형식으로 각 엔트리의 배송 횟수를 확인해, N 회 이상 재시도해도 실패하는 "독약 메시지(poison message)"는 별도 dead-letter Stream 으로 `XADD` 후 원본에서 `XACK` 해 무한 재시도 루프를 끕어야 한다.

```
XPENDING orders workers - + 10
# 각 행: [entryId, consumer, idleMs, deliveryCount]
# deliveryCount >= 5 이면 DLQ 로 이동시키는 로직을 애플리케이션에서 구현
```

## 4. 트리밍 — Stream 무한 성장 방지

Stream 은 명시 트리밍 없이는 계속 자란다. `XADD` 에 트리밍 옵션을 붙이거나 주기적으로 `XTRIM` 한다. 근사 트리밍(`~`)은 라딕스 트리 노드 경계에서 자르므로 정확 트리밍보다 훨씬 빠르다.

```
XADD orders MAXLEN ~ 1000000 * field value   # 대략 100만 건 유지(근사)
XTRIM orders MINID ~ 1719900000000-0          # 특정 시각 이전 엔트리 제거
```

`MAXLEN` 은 개수 기준, `MINID` 는 시간(ID) 기준이다. 보존 정책이 "최근 24시간"이면 `MINID` 가, "최대 N건"이면 `MAXLEN` 이 맞다. 중요한 함정: 트리밍은 아직 소비되지 않은 엔트리도 지운다. 느린 소비자가 있으면 트리밍이 미처리 데이터를 유실시킬 수 있으므로, `XINFO GROUPS orders` 로 각 그룹의 lag(마지막 엔트리와 last-delivered 간 차이)을 모니터링하며 보존 창을 소비 속도보다 넣넨히 잡아야 한다.

```
XINFO GROUPS orders
# 각 그룹의 pending 수, last-delivered-id, lag 확인
```

## 5. Cluster 모드와 해시 슬롯

Redis Cluster 는 키 공간을 16384 개 해시 슬롯으로 나눈다. 키의 슬롯은 `CRC16(key) mod 16384` 로 결정되고, 각 마스터 노드가 슬롯 구간을 소유한다. Stream 도 하나의 키이므로 **한 Stream 은 통째로 한 슬롯(=한 노드)에 산다**. 즉 단일 Stream 의 처리량은 그 노드의 단일 코어 성능에 묶인다.

높은 처리량이 필요하면 Stream 을 샤딩해야 한다. 예를 들어 `orders:{0}` ~ `orders:{15}` 처럼 키를 나누되, 해시태그 `{}` 로 슬롯을 명시 제어한다. 해시태그 안의 문자열만 CRC16 대상이 되므로, `{0}` 가 특정 슬롯에 고정된다.

```
XADD orders:{3} * ...   # {3} 부분만 해시 -> 항상 같은 슬롯/노드
# 소비자는 shard 번호 % N 으로 키를 계산해 병렬 소비
```

리샤딩(reshard)은 슬롯을 노드 간에 옷기는 작업이다. `CLUSTER SETSLOT ... MIGRATING/IMPORTING` 과 `MIGRATE` 명령으로 슬롯 내 키를 원자적으로 이동한다. 마이그레이션 중인 슬롯의 키에 접근하면 클라이언트는 `ASK` 리다이렉션을, 이동 완료 후에는 `MOVED` 리다이렉션을 받는다. 스마트 클라이언트(Lettuce, redis-py-cluster)는 이 리다이렉션을 투명 처리하지만, `MULTI`/`EXEC` 트랜잭션이나 Lua 스크립트가 여러 슬롯의 키를 건드리면 `CROSSSLOT` 에러가 난다. 따라서 한 트랜잭션에서 함께 다뤄야 하는 Stream 들은 동일 해시태그로 같은 슬롯에 묶어야 한다.

| 상황 | 리다이렉션 | 의미 |
|---|---|---|
| 슬롯이 다른 노드로 이미 이동됨 | MOVED | 클라이언트가 슬롯 맵 갱신 후 재요청 |
| 슬롯 마이그레이션 진행 중, 키가 아직 원본에 | - | 원본 노드가 처리 |
| 마이그레이션 중, 키가 이미 대상으로 이동 | ASK | 일회성으로 대상 노드에 재요청 |

## 6. Spring Data Redis 연동

Spring Data Redis 는 `StreamMessageListenerContainer` 로 Consumer Group 소비를 추상화한다. 자동 ack 와 수동 ack 를 선택할 수 있다.

```java
StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(2))
                .batchSize(10)
                .build();

StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(connectionFactory, options);

container.receive( // 수동 ack: 예외 시 재처리 가능
        Consumer.from("workers", "consumer-1"),
        StreamOffset.create("orders", ReadOffset.lastConsumed()),
        message -> {
            handle(message.getValue());
            redisTemplate.opsForStream().acknowledge("orders", "workers", message.getId());
        });

container.start();
```

`ReadOffset.lastConsumed()` 는 내부적으로 `>` 를 사용해 새 메시지를 받는다. 컨테이너를 재시작하면 자신의 PEL 이 자동으로 재처리되지 않으므로, 애플리케이션 기동 시 `XPENDING` 기반 복구 배치를 별도로 돌리는 것이 안전하다.

## 7. 블로킹 소비와 폴링 튜닝

Consumer 는 `XREADGROUP ... BLOCK <ms>` 로 새 메시지가 올 때까지 블로킹 대기할 수 있다. `BLOCK 0` 은 무한 대기다. 이 롯폴링은 busy-loop 폴링보다 CPU 와 네트워크 왕복을 크게 줄인다. 다만 블로킹 중인 커넥션은 다른 명령에 쓸 수 없으므로, 소비 전용 커넥션을 별도 풀에서 관리해야 한다. Lettuce 는 이를 위해 전용 커넥션을 잡고, 애플리케이션의 일반 명령용 커넥션과 분리한다.

```
XREADGROUP GROUP workers c1 COUNT 20 BLOCK 5000 STREAMS orders >
# 최대 20건, 없으면 5초 대기 후 빈 응답 -> 루프 재진입
```

`COUNT` 는 한 번에 받을 최대 건수다. 크게 잡으면 배치 처리 효율이 오르지만 한 배치 처리 시간이 길어져 다른 소비자의 부하 균형이 나빠질 수 있다. 처리 시간이 균일하면 크게, 편차가 크면 작게 잡아 work-stealing 효과를 살린다.

## 8. 메모리와 영속성 상호작용

Stream 은 메모리에 상주하므로 트리밍 정책이 곳 메모리 예산이다. AOF 를 켜면 모든 `XADD`/`XACK` 가 로그에 기록돼 재시작 시 PEL 까지 복구된다. RDB 스냅샷만 쓰면 마지막 스냅샷 이후 데이터는 유실될 수 있어, 작업 큐 용도라면 `appendonly yes` + `appendfsync everysec` 조합이 내구성·성능 균형점이다. Consumer Group 메타데이터(각 그룹의 last-delivered-id, 각 소비자 PEL)도 Stream 키의 일부로 함께 영속화되므로, 복구 후에도 소비 진행 상태가 보존된다는 점이 List 기반 큐 대비 큰 장점이다.

| 영속성 설정 | 재시작 후 PEL 복구 | 성능 |
|---|---|---|
| RDB only | 마지막 스냅샷 시점까지 | 가장 빠름 |
| AOF everysec | 최대 1초 손실 | 균형 |
| AOF always | 거의 무손실 | 가장 느림 |

## 9. Kafka 와의 실무 비교

Redis Stream 은 밀리초 단위 지연과 단순한 운영이 강점이지만, Kafka 대비 파티션 병렬성·장기 보존·정확히 한 번(EOS) 시맨틱은 약하다. Stream 은 슬롯당 단일 노드이므로 초당 수십만 건 이상은 샤딩 설계가 필수다. 보존은 메모리(또는 AOF/RDB) 용량에 직접 묶이므로 며칠~몇 주 규모 로그를 담기엔 부적합하다. 반면 이미 Redis 를 캐시로 쓰는 스택에서 "가벼운 작업 큐 + 재처리 보장 + 소비자 그룹"이 필요하면 별도 브로커 없이 즉시 도입할 수 있어 운영 비용이 낮다. 선택 기준은 처리량·보존 기간·기존 인프라의 함수다.

## 참고

- Redis Documentation — Streams intro / Consumer Groups (redis.io)
- Redis Commands: XREADGROUP, XCLAIM, XAUTOCLAIM, XPENDING, XTRIM
- Redis Cluster Specification — hash slots and resharding
- Spring Data Redis Reference — Redis Streams
