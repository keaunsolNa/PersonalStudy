Notion 원본: https://app.notion.com/p/3e75a06fd6d381b7b0a5d09e623c9fd3

# Redis Streams Consumer Group 처리 모델과 Cluster 슬롯 재배치 및 페일오버

> 2026-09-26 신규 주제 · 확장 대상: Redis(캐시 중심 학습) · Redisson 분산 락

## 학습 목표

- Redis Stream의 PEL(Pending Entries List)과 Consumer Group의 메시지 배분·재전달 모델을 재구성한다
- `XCLAIM`/`XAUTOCLAIM`으로 죽은 컨슈머의 미확인 메시지를 회수하는 절차를 설계한다
- Redis Cluster의 16384 해시 슬롯 배정 원리와 리샤딩(slot migration) 중 요청 처리 흐름을 추적한다
- 클러스터 페일오버 시 슬롯 소유권 이전과 클라이언트 재라우팅(MOVED/ASK) 동작을 구분한다

## 1. Stream이 Pub/Sub, List와 다른 지점

Redis의 기존 Pub/Sub은 발행 시점에 연결되어 있지 않은 구독자에게는 메시지가 전달되지 않고 즉시 소실된다. List 기반 큐(`LPUSH`/`BRPOP`)는 메시지가 소비되면 삭제되어 재처리나 다중 그룹 소비가 불가능하다. Stream은 이 두 한계를 로그 구조 자료구조로 해결한다. 각 엔트리는 `<millisecondsTime>-<sequenceNumber>` 형태의 단조 증가 ID를 가지며, 소비되어도 스트림에서 즉시 삭제되지 않고 별도의 트리밍 정책(`MAXLEN`, `MINID`)이 적용될 때까지 남아있어 여러 Consumer Group이 각자의 오프셋으로 동일 스트림을 독립적으로 소비할 수 있다.

```bash
XADD orders * userId 1001 amount 50000
XGROUP CREATE orders order-processors $ MKSTREAM
```

`XGROUP CREATE`의 `$`는 그룹 생성 시점 이후의 엔트리만 소비 대상으로 삼겠다는 의미이며, 과거 이력을 모두 재처리하려면 `0`을 지정한다.

## 2. Consumer Group의 배분 모델과 PEL

Consumer Group에 속한 여러 컨슈머는 `XREADGROUP`을 호출해 아직 다른 컨슈머에게 배정되지 않은 엔트리를 가져온다. 이때 서버는 해당 엔트리를 그룹의 PEL(Pending Entries List)에 "이 컨슈머가 이 시각에 가져갔고 아직 몇 번 재전달되었다"는 메타데이터와 함께 등록한다.

```bash
XREADGROUP GROUP order-processors consumer-1 COUNT 10 STREAMS orders >
```

`>`는 "아직 아무 컨슈머에게도 배분되지 않은 새 엔트리"를 의미하는 특수 ID다. 컨슈머가 처리를 마치면 `XACK`로 PEL에서 해당 엔트리를 제거해야 하는데, 이 확인 응답이 오기 전에 컨슈머 프로세스가 죽으면 엔트리는 PEL에 남아 다른 컨슈머가 아직 처리 중인 것으로 계속 남는다.

```bash
XACK orders order-processors 1695700000000-0
```

PEL을 확인하려면 `XPENDING`을 쓴다.

```bash
XPENDING orders order-processors - + 100
# 출력: [엔트리ID, 컨슈머명, 유휴시간(ms), 배달횟수]
```

## 3. 죽은 컨슈머의 메시지 회수: XCLAIM과 XAUTOCLAIM

컨슈머가 크래시하면 그 컨슈머에게 배정된 PEL 엔트리는 아무도 처리하지 않는 상태로 남는다. 이를 회수하는 전통적인 방법이 `XCLAIM`이다.

```bash
XCLAIM orders order-processors consumer-2 60000 1695700000000-0
```

이는 "60초 이상 유휴 상태인 엔트리 1695700000000-0을 consumer-2에게 소유권을 이전하라"는 명령이지만, 실전에서는 어떤 엔트리 ID가 유휴 상태인지 먼저 `XPENDING`으로 조회한 뒤 각각에 대해 `XCLAIM`을 호출해야 해서 절차가 번거롭고, 그 사이 레이스 컨디션의 여지가 있었다. Redis 6.2부터 도입된 `XAUTOCLAIM`은 이 두 단계를 원자적으로 통합한다.

```bash
XAUTOCLAIM orders order-processors consumer-2 60000 0
# 반환: [다음 스캔 시작 커서, 회수된 엔트리 목록, 삭제된 엔트리 ID 목록]
```

`XAUTOCLAIM`은 커서 기반으로 PEL을 스캔하며 유휴 시간이 임계값을 넘긴 엔트리를 자동으로 지정된 컨슈머에게 재할당한다. 세 번째 반환값(삭제된 엔트리 ID 목록)은 Redis 7.0에서 추가된 것으로, 스트림 자체에서는 트리밍으로 이미 사라졌지만 PEL에는 잔존해 있던 "고아 엔트리"를 정리한 결과를 알려준다. 실전 워커 구현에서는 주기적으로(예: 30초 간격) `XAUTOCLAIM`을 폴링해 죽은 컨슈머의 작업을 흡수하는 백그라운드 루프를 별도로 둔다.

## 4. 재전달 횟수 기반 데드레터 처리

`XPENDING`이 반환하는 배달 횟수(delivery count)가 임계값을 초과한 엔트리는 처리 자체에 반복적으로 실패하고 있다는 신호이며, 무한정 재시도하게 두면 다른 정상 메시지의 처리량을 갉아먹는다. 실전에서는 이를 데드레터 스트림으로 옮기는 패턴을 직접 구현한다.

```python
claimed = r.xautoclaim("orders", "order-processors", "consumer-2", min_idle_time=60000, start_id="0")
for entry_id, fields in claimed[1]:
    delivery_count = r.xpending_range("orders", "order-processors", entry_id, entry_id, count=1)[0]["times_delivered"]
    if delivery_count > 5:
        r.xadd("orders:dlq", fields)
        r.xack("orders", "order-processors", entry_id)
    else:
        process(fields)
        r.xack("orders", "order-processors", entry_id)
```

Kafka의 컨슈머 그룹은 파티션 단위로 오프셋을 관리해 파티션당 하나의 컨슈머만 붙는 반면, Redis Stream의 Consumer Group은 엔트리 단위로 배분하고 PEL로 개별 확인 응답을 추적한다는 점이 근본적인 설계 차이다. 이 차이 때문에 Redis Stream은 Kafka보다 세밀한 재시도 제어(엔트리 단위 재전달)가 가능하지만, 처리량 측면에서는 파티션 단위 순차 처리를 전제로 하는 Kafka만큼의 처리량 보장을 하지 않는다.

## 5. Redis Cluster의 해시 슬롯 모델

Redis Cluster는 데이터를 노드에 분산하기 위해 16384개의 고정된 해시 슬롯을 사용한다. 키는 `CRC16(key) % 16384` 계산으로 슬롯 번호가 정해지고, 각 슬롯은 클러스터의 특정 마스터 노드에 배정된다. 슬롯 수를 노드 수가 아니라 16384라는 고정값으로 둔 이유는, 노드 수가 변해도(스케일 인/아웃) 슬롯 자체는 재계산할 필요 없이 슬롯의 소유권만 재배정하면 되도록 설계했기 때문이다.

```bash
redis-cli -c CLUSTER KEYSLOT orders:1001
# (integer) 12182
redis-cli -c CLUSTER NODES
# <id> <ip:port> master - 0 <ping> <pong> <epoch> connected 0-5460
```

같은 트랜잭션이나 Lua 스크립트에서 여러 키를 함께 다루려면 그 키들이 반드시 같은 슬롯에 있어야 한다. 이를 강제하는 것이 해시 태그(hash tag)다.

```bash
SET "{user:1001}:profile" "..."
SET "{user:1001}:orders" "..."
# 중괄호 안의 "user:1001"만 해시 계산에 사용되어 두 키가 같은 슬롯으로 강제된다
```

## 6. 슬롯 재배치(리샤딩)와 진행 중 요청 처리

클러스터에 노드를 추가하거나 제거할 때는 기존 슬롯의 일부를 새 노드로 옮기는 리샤딩이 필요하다. 이 과정은 원자적 일괄 이동이 아니라 슬롯 단위로 점진적으로 진행되며, 이동 중인 슬롯에 대한 개별 키 이전은 `MIGRATE` 명령으로 수행된다.

```bash
CLUSTER SETSLOT 12182 IMPORTING <source-node-id>   # 대상 노드에서 실행
CLUSTER SETSLOT 12182 MIGRATING <target-node-id>   # 원본 노드에서 실행
MIGRATE <target-ip> <target-port> "" 0 5000 KEYS "{user:1001}:profile"
CLUSTER SETSLOT 12182 NODE <target-node-id>          # 이전 완료 후 모든 노드에 통지
```

문제는 `MIGRATING` 상태와 `NODE` 확정 사이의 구간에, 이미 이전된 키에 대한 요청이 원본 노드로 들어오는 경우다. 이때 원본 노드는 자신이 더 이상 그 키를 갖고 있지 않음을 알고 있으므로 `-ASK` 리다이렉트를 응답한다.

```
-ASK 12182 target-ip:target-port
```

클라이언트는 `-ASK`를 받으면 해당 요청에 한해(영구적인 슬롯 소유권 갱신 없이) `ASKING` 명령을 대상 노드에 먼저 보낸 뒤 원래 명령을 재전송한다. 이는 슬롯 이전이 완전히 끝나지 않은 과도기에도 클라이언트가 올바른 노드를 찾아갈 수 있게 하는 임시 라우팅 메커니즘이다. 반면 슬롯 소유권이 완전히 확정된 이후 잘못된 노드에 요청이 가면 영구적인 `-MOVED` 응답을 받으며, 클라이언트는 이때 자신의 슬롯-노드 매핑 캐시를 갱신한다.

<table header-row="true"><tr><td>리다이렉트</td><td>발생 시점</td><td>클라이언트 동작</td><td>캐시 갱신</td></tr><tr><td>-MOVED</td><td>슬롯 소유권이 확정적으로 변경됨</td><td>대상 노드로 재요청</td><td>영구 갱신</td></tr><tr><td>-ASK</td><td>슬롯 이전이 진행 중(과도기)</td><td>ASKING 후 재요청</td><td>갱신하지 않음(일회성)</td></tr></table>

## 7. 페일오버와 슬롯 소유권 이전

마스터 노드가 장애로 응답하지 않으면, 클러스터의 다른 노드들이 게시프 프로토콜(gossip)로 장애를 감지하고 `CLUSTER-FAIL` 상태를 합의한 뒤 해당 마스터에 연결된 레플리카 중 하나가 승격 투표를 진행한다. 승격된 레플리카는 원래 마스터가 소유하던 슬롯 전체를 그대로 이어받으며, 슬롯 재배치와 달리 페일오버는 슬롯 소유권을 원자적으로 통째로 이전한다는 점이 다르다.

```bash
redis-cli -c CLUSTER FAILOVER  # 레플리카에서 수동 페일오버 트리거(계획된 유지보수 시)
```

수동 페일오버는 `FAILOVER` 없이 그냥 강제 승격하는 것보다 안전한데, 기본 모드에서는 마스터에게 먼저 클라이언트 요청을 잠시 중단시키고(`CLIENT PAUSE`) 레플리카의 복제 오프셋이 마스터를 완전히 따라잡을 때까지 대기한 뒤 승격을 진행해 데이터 유실 없이 전환할 수 있게 하기 때문이다. 반면 실제 장애로 인한 자동 페일오버는 마스터가 이미 응답 불가 상태이므로 마지막 복제 오프셋 이후의 쓰기(비동기 복제 지연 구간)는 유실될 수 있다는 트레이드오프가 있다.

## 8. CROSSSLOT 오류와 멀티 키 연산의 제약

Redis Cluster는 트랜잭션(`MULTI`/`EXEC`)이나 Lua 스크립트, 그리고 `MSET`, `SUNIONSTORE`처럼 여러 키를 동시에 다루는 명령에서 모든 키가 동일한 슬롯에 있을 것을 강제한다. 이를 위반하면 클라이언트는 다음과 같은 오류를 받는다.

```
(error) CROSSSLOT Keys in request don't hash to the same slot
```

싱글 노드 Redis에서는 아무 문제 없이 동작하던 코드가 클러스터 모드로 전환하는 순간 이 오류로 실패하는 것이 클러스터 마이그레이션에서 가장 흔한 초기 장애 유형이다. 해결책은 5절에서 다룬 해시 태그로 관련 키를 강제로 같은 슬롯에 배치하는 것이지만, 이는 동시에 "그 해시 태그로 묶인 모든 데이터가 하나의 슬롯, 결국 하나의 노드에 집중된다"는 의미이기도 한다. 특정 테넌트나 사용자의 데이터를 모두 하나의 해시 태그로 묶는 설계는 트랜잭션 편의성과 슬롯 분산(핫스팟 방지) 사이의 트레이드오프를 항상 동반하며, 대형 테넌트가 존재하는 멀티테넌시 시스템에서는 이 핫스팟이 특정 노드에 부하가 집중되는 원인이 되기도 한다.

## 9. 클라이언트 측 토폴로지 캐싱과 Redisson/Lettuce의 대응

애플리케이션에서 클러스터와 통신하는 클라이언트 라이브러리(Lettuce, Redisson, Jedis Cluster)는 각자 슬롯-노드 매핑을 캐싱하고 `-MOVED` 응답을 받을 때마다 이를 갱신하는 전략을 갖고 있다. Redisson은 기본적으로 `CLUSTER NODES` 결과를 주기적으로 폴링해 토폴로지 변화를 미리 감지하는 백그라운드 스캔과, `-MOVED`를 받는 즉시 해당 슬롯만 갱신하는 반응형 갱신을 함께 사용한다.

```java
Config config = new Config();
config.useClusterServers()
    .addNodeAddress("redis://node1:6379", "redis://node2:6379")
    .setScanInterval(2000) // 토폴로지 스캔 주기(ms)
    .setRetryAttempts(3)
    .setRetryInterval(1500);
RedissonClient redisson = Redisson.create(config);
```

`scanInterval`을 너무 짧게 잡으면 클러스터 노드 전체에 대한 `CLUSTER NODES` 호출이 잦아져 관리 트래픽이 늘어나고, 너무 길게 잡으면 리샤딩 직후 일정 시간 동안 `-MOVED` 리다이렉트에 의존해야 해서 요청 지연이 늘어난다. 실무에서는 계획된 리샤딩 작업 직전에 일시적으로 스캔 주기를 짧게 낮췄다가 작업 완료 후 원래 값으로 되돌리는 방식으로 이 트레이드오프를 관리하기도 한다. 페일오버가 발생했을 때도 동일한 토폴로지 갱신 메커니즘이 작동하지만, 승격이 완료되기 전까지의 짧은 구간에는 해당 슬롯에 대한 요청이 연속적으로 실패하거나 재시도될 수 있어 `retryAttempts`와 `retryInterval` 설정이 애플리케이션이 체감하는 페일오버 복구 시간에 직접 영향을 준다.

## 참고

- Redis 공식 문서, "Redis Streams", "Redis Cluster Specification"
- Redis 공식 문서, "Scaling with Redis Cluster" 튜토리얼
- Redis 7.0 Release Notes, XAUTOCLAIM 개선 사항
- Salvatore Sanfilippo(antirez), Redis Cluster 설계 블로그 포스트
