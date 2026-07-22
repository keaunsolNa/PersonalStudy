Notion 원본: https://app.notion.com/p/3a55a06fd6d381beb7b0c0614c3fd579

# Redis Stream Consumer Group과 XCLAIM 및 소비자 장애 복구

> 2026-07-22 신규 주제 · 확장 대상: Redis(자료구조·메시징)

## 학습 목표

- Redis Stream의 엔트리 ID 구조와 append-only 로그로서의 특성을 파악한다.
- Consumer Group의 PEL(Pending Entries List)이 at-least-once 전달을 보장하는 원리를 분석한다.
- XACK·XCLAIM·XAUTOCLAIM으로 죽은 소비자의 미처리 메시지를 회수하는 흐름을 구성한다.
- Stream 기반 메시징을 Pub/Sub·List와 비교하고 소비자 장애 복구 전략을 세운다.

## 1. Redis Stream — append-only 로그 자료구조

Redis Stream은 5.0에서 추가된 로그형 자료구조다. Pub/Sub이 메시지를 저장하지 않고 흘려보내고, List가 소비 시 원소를 제거하는 것과 달리, Stream은 메시지를 **추가만 하고 지우지 않는** 불변 로그다. Kafka의 토픽 파티션과 개념이 유사하다. 각 엔트리는 자동 생성되는 ID와 필드-값 쌍의 맵을 가진다.

```
XADD orders * userId 42 amount 15000 status pending
  → "1721650800000-0"   (밀리초 타임스탬p - 시퀀스)
```

엔트리 ID는 `<밀리초 타임스탬p>-<시퀀스>` 형식이다. 같은 밀리초에 여러 엔트리가 들어오면 시퀀스가 증가한다. 이 ID는 단조 증가하므로 시간순 정렬과 범위 조회(`XRANGE`, `XREVRANGE`)의 기준이 된다. `*`는 서버가 ID를 자동 생성하라는 의미다. Stream은 지워지지 않으므로 무한히 커지는 것을 막기 위해 `XADD ... MAXLEN ~ 10000`처럼 근사 트리밍이나 `XTRIM MINID`로 오래된 엔트리를 잘라낸다. `~`는 근사 트리밍으로, 정확히 10000이 아니라 매크로노드 경계에서 잘라 성능을 확보한다.

## 2. 단순 소비 vs Consumer Group

Stream 소비는 두 방식이 있다. `XREAD`는 offset을 클라이언트가 관리하며 팬아웃(모든 소비자가 모든 메시지)에 쓴다. 반면 **Consumer Group**은 하나의 그룹에 속한 여러 소비자가 메시지를 **나눠 갖는** 부하 분산 모델이다. 같은 그룹의 소비자들은 서로 다른 엔트리를 받아 병렬 처리한다.

```
XGROUP CREATE orders order-workers $ MKSTREAM
  # $ = 그룹 생성 시점 이후 새 메시지부터. 0 = 처음부터. MKSTREAM = 스트림 없으면 생성

XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 5000 STREAMS orders >
  # > = 아직 아무 소비자에게도 전달 안 된 새 메시지를 요청
```

`>` 특수 ID는 "이 그룹의 어떤 소비자도 아직 받지 않은 새 메시지"를 뜻한다. `XREADGROUP`으로 메시지를 받는 순간, 그 엔트리는 해당 소비자의 **PEL(Pending Entries List)**에 등록된다. 즉 "전달됐지만 아직 확인(ACK)되지 않은" 상태로 기록된다. 이 PEL이 Consumer Group 신뢰성의 핵심이다.

## 3. PEL과 at-least-once 전달 보장

PEL은 각 소비자별로 "받았지만 아직 XACK 하지 않은 엔트리 목록"이다. 소비자가 메시지를 처리하고 성공하면 `XACK`으로 PEL에서 제거해야 한다. ACK 하지 않으면 그 엔트리는 영원히 PEL에 남아 "미처리"로 간주된다.

```
# 메시지 처리 성공 후 반드시 ACK
XACK orders order-workers 1721650800000-0

# 특정 소비자의 미처리 목록 확인
XPENDING orders order-workers
  # → 총 pending 수, 최소/최대 ID, 소비자별 pending 수
```

이 구조가 at-least-once를 보장한다. 소비자가 메시지를 받고 처리 중 죽으면, 그 엔트리는 ACK 되지 않아 PEL에 남는다. 다른 소비자가 나중에 이를 회수(claim)해 재처리할 수 있다. "적어도 한 번" 전달되지만, 회수-재처리 과정에서 같은 메시지가 두 번 처리될 수 있으므로 **소비자 로직은 멱등**해야 한다. 예를 들어 주문 처리라면 주문 ID로 중복 삽입을 막는 `INSERT ... ON CONFLICT DO NOTHING`이나 처리 완료 플래그를 확인해야 한다.

## 4. XCLAIM — 죽은 소비자의 메시지 회수

소비자가 죽으면 그 소비자의 PEL 엔트리는 방치된다. 다른 소비자가 이를 넘겨받으려면 `XCLAIM`을 쓴다. `XCLAIM`은 "일정 시간(idle) 이상 처리되지 않은 엔트리의 소유권을 다른 소비자로 이전"한다.

```
# worker-1 이 30초 이상 처리 못 한 엔트리를 worker-2 가 회수
XCLAIM orders order-workers worker-2 30000 1721650800000-0
  # 30000ms = min-idle-time. 이 시간 이상 idle 인 것만 회수 (경쟁 안전)
```

`min-idle-time`이 중요하다. 이 값보다 오래 idle 상태인 엔트리만 회수되므로, 두 소비자가 동시에 같은 엔트리를 회수하려 해도 첫 회수로 idle 시간이 0으로 리셋돼 두 번째 회수는 실패한다. 이것이 회수 경쟁의 원자성을 보장한다. 회수 시 전달 횟수(delivery count)가 증가하는데, 이 카운트가 임계값을 넘으면 "독약 메시지(poison message)"로 판단해 dead-letter 스트림으로 옮기는 패턴을 쓴다. 특정 메시지가 계속 처리에 실패해 무한 회수-실패를 반복하는 것을 막기 위함이다.

## 5. XAUTOCLAIM — 회수 자동화

`XCLAIM`은 회수할 엔트리 ID를 직접 알아야 한다. 실무에서는 `XPENDING`으로 목록을 훑고 하나씩 `XCLAIM` 하는 대신, Redis 6.2가 추가한 `XAUTOCLAIM`으로 한 번에 처리한다. 이는 커서 기반으로 idle 임계를 넘은 엔트리를 자동 스캔·회수한다.

```
XAUTOCLAIM orders order-workers worker-2 30000 0 COUNT 100
  # 시작 커서 0 부터, idle 30초 넘은 pending 을 최대 100개 회수
  # 반환: 다음 커서, 회수된 엔트리들, 삭제된(스트림에서 사라진) 엔트리 ID
```

`XAUTOCLAIM`은 반환값으로 다음 순회 커서를 주므로, 이를 반복 호출하면 전체 PEL을 순회하며 방치된 엔트리를 재분배할 수 있다. 실무 소비자 루프는 보통 두 단계로 구성한다. 먼저 `XREADGROUP ... >`로 새 메시지를 받고, 주기적으로(또는 유휴 시) `XAUTOCLAIM`으로 죽은 동료의 미처리 메시지를 회수한다. 이 이중 루프가 소비자 장애에 대한 자동 복구를 만든다.

## 6. 재시작 소비자의 PEL 재처리

소비자가 재시작되면 자신의 PEL에 남은 엔트리부터 정리해야 한다. `XREADGROUP`에서 `>` 대신 `0`을 ID로 주면 "새 메시지가 아니라 내 PEL에 이미 있는 미처리 엔트리"를 다시 받는다.

```
# 재시작 직후: 내 PEL 의 미처리분부터 재처리
XREADGROUP GROUP order-workers worker-1 COUNT 10 STREAMS orders 0
  # 0 = 이 소비자의 PEL 처음부터 이미 전달됐던 엔트리 재조회

# PEL 이 비면 다시 > 로 전환해 새 메시지 소비
XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 5000 STREAMS orders >
```

이 패턴 덕분에 소비자는 크래시 전에 받았지만 ACK 못한 메시지를 재시작 후 스스로 마무리할 수 있다. `0`으로 조회해 결과가 비면 자신의 PEL이 깨끗하다는 뜻이므로 `>`로 전환한다. 이 전환 로직을 빠뜨리면 소비자가 계속 옛 메시지만 재조회하며 새 메시지를 소비하지 못하는 버그가 생긴다.

## 7. Stream vs Pub/Sub vs List

세 메시징 자료구조의 성격이 다르다. Pub/Sub은 저장하지 않아 구독자가 없으면 메시지가 사라지고 재전달·확인이 없다 — 실시간 알림처럼 유실을 허용하는 팬아웃에 적합하다. List(`LPUSH`/`BRPOP`)는 큐로 쓸 수 있지만 소비 시 원소가 제거돼 미처리 추적이 없고, 소비자가 pop 후 죽으면 메시지가 사라진다. Stream은 로그를 보존하고 PEL로 미처리를 추적하므로 신뢰성 있는 작업 큐에 적합하다.

| 특성 | Pub/Sub | List | Stream + Group |
|---|---|---|---|
| 메시지 보존 | 없음 | pop 시 제거 | 유지(트리밍 전까지) |
| 전달 보장 | at-most-once | 없음(pop 후 유실) | at-least-once |
| 미처리 추적 | 없음 | 없음 | PEL |
| 재처리/회수 | 불가 | 불가 | XCLAIM/XAUTOCLAIM |
| 소비 모델 | 팬아웃 | 단순 큐 | 그룹 부하분산 |

Stream의 trade-off는 메모리다. 메시지를 보존하므로 트리밍을 하지 않으면 메모리가 무한 증가한다. `MAXLEN`/`MINID` 트리밍 정책과 소비자 ACK 진척을 함께 관리해야 한다. 또 Kafka만큼의 디스크 기반 무한 보존이나 파티션 리밸런싱은 없으므로, 초대용량·장기 보존이 필요하면 Kafka가 낫다. Redis Stream은 "Redis를 이미 쓰는 환경에서 가벼운 신뢰성 큐"라는 자리를 차지한다.

## 8. 소비자 장애 복구 설계 정리

견고한 Stream 소비자는 다음을 갖춘다. 첫째, 멱등 처리 — at-least-once이므로 같은 메시지 재처리를 감당해야 한다. 처리 결과를 별도 키·테이블에 기록하고 중복을 무시한다. 둘째, ACK 타이밍 — 반드시 **처리 성공 후** XACK 한다. 받자마자 ACK 하면 처리 중 크래시 시 메시지가 유실된다(at-most-once로 퇴화). 셋째, 회수 루프 — 주기적 `XAUTOCLAIM`으로 죽은 소비자의 PEL을 회수하되, 전달 횟수 임계를 넘는 독약 메시지는 dead-letter 스트림(`XADD orders-dlq ...`)으로 격리하고 원본은 `XACK`으로 정리한다. 넷째, 재시작 시 `0`으로 자신의 PEL을 먼저 비우고 `>`로 전환한다.

이 넷 가지가 맞물리면 소비자 몇 개가 죽어도 메시지 유실 없이 나머지 소비자가 회수해 마무리하는 자기 치유 파이프라인이 된다. 핵심은 "Redis가 신뢰성을 절반 제공하고(PEL·회수), 나머지 절반(멱등·ACK 타이밍·DLQ)은 소비자 코드가 책임진다"는 역할 분담을 이해하는 것이다.

## 9. Spring Data Redis 통합과 운영 모니터링

JVM 환경에서는 Spring Data Redis의 `StreamMessageListenerContainer`로 Consumer Group 소비를 선언적으로 구성한다. 컨테이너가 `XREADGROUP` 폴링과 스레드 관리를 대신하고, 개발자는 리스너와 ACK·에러 정책만 정의한다.

```java
StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
    StreamMessageListenerContainer.create(connectionFactory, options);

container.receive(
    Consumer.from("order-workers", "worker-1"),
    StreamOffset.create("orders", ReadOffset.lastConsumed()),   // ">" 에 해당
    message -> {
        processOrder(message);
        redisTemplate.opsForStream().acknowledge("order-workers", message);  // 처리 성공 후 ACK
    });
container.start();
```

`ReadOffset.lastConsumed()`가 `>` 시맨틱이다. 여기서 자동 ACK(`autoAck`)를 켜지 않는 것이 중요하다 — 자동 ACK는 메시지 수신 즉시 ACK 하므로 처리 중 크래시 시 유실된다. 반드시 리스너 내부에서 처리 성공을 확인한 뒤 명시적으로 `acknowledge`를 호출해야 at-least-once가 보장된다.

운영에서는 두 지표를 상시 감시한다. 첫째 **PEL 크기**(`XPENDING`의 총 pending 수)로, 이 값이 계속 증가하면 소비 속도가 생산 속도를 못 따라가거나 소비자가 ACK를 빠뜨리고 있다는 신호다. 둘째 **소비자 lag**(스트림 최신 ID와 그룹의 `last-delivered-id` 차이)로, `XINFO GROUPS orders`가 그룹별 지연을 보여준다. 이 두 지표를 Prometheus로 수집하고 임계 초과 시 소비자 오토스케일이나 알림을 트리거하는 것이 안정적 운영의 기본이다. 스트림 길이(`XLEN`)도 함께 보아 트리밍이 소비 진척을 앞지르지 않는지 확인한다.

트리밍과 소비의 경합은 실무에서 특히 조심할 지점이다. `XADD ... MAXLEN`으로 스트림을 짧게 유지하면 메모리는 절약되지만, 느린 소비자나 죽은 소비자의 PEL이 참조하는 엔트리가 트리밍으로 사라질 수 있다. 이 경우 `XAUTOCLAIM`이 회수하려 해도 원본 엔트리가 없어 "삭제된 엔트리"로 반환되고, 그 메시지는 영구 유실된다. 따라서 트리밍 임계는 최악의 소비 지연을 충분히 상회하도록 넘넘히 잡아야 한다. 안전한 접근은 소비 진척(그룹의 `last-delivered-id`와 모든 소비자 PEL의 최소 ID)을 추적해, 그 지점보다 오래된 엔트리만 `XTRIM MINID`로 잘라내는 것이다. 시간 기반이 아니라 소비 진척 기반 트리밍이 유실 없는 메모리 관리의 핵심이다. Redis 자체는 이 소비 진척 기반 자동 트리밍을 제공하지 않으므로, 별도 청소 잡이 주기적으로 PEL 최소 ID를 계산해 트리밍하는 로직을 두어야 한다.

## 참고

- Redis Documentation — Streams intro, Consumer Groups
- Redis Command Reference — XREADGROUP, XACK, XCLAIM, XAUTOCLAIM, XPENDING
- Redis 6.2 Release Notes — XAUTOCLAIM 도입
- "Redis in Action" 및 Redis Stream 설계 문서 (antirez 블로그)
