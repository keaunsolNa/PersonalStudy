Notion 원본: https://app.notion.com/p/3c15a06fd6d381ef8d93d6890933f2e7?pvs=204

# Redis Streams Consumer Group과 XAUTOCLAIM 및 장애 복구 설계

> 2026-08-19 신규 주제 · 확장 대상: Redis

## 학습 목표

- PEL(Pending Entries List)의 상태 전이를 XADD~XACK 전 구간에서 추적한다
- XCLAIM 과 XAUTOCLAIM 의 차이를 delivery_count·idle 기준으로 구분해 재처리 정책을 설계한다
- XADD MAXLEN/MINID 트리밍이 미확인 메시지를 삭제할 때의 데이터 손실 경로를 차단한다
- Kafka 컨슈머 그룹과의 구조적 차이를 근거로 Streams 의 적용 한계를 정한다

## 1. Streams 가 List·Pub/Sub 과 다른 지점

Redis 로 큐를 만드는 전통적 방법은 `LPUSH` + `BRPOP` 이었다. 문제는 소비자가 `BRPOP` 으로 메시지를 꺼낸 직후 죽으면 그 메시지가 영원히 사라진다는 것이다. 리스트에는 "꺼냈지만 아직 처리 완료되지 않음" 이라는 중간 상태가 없다. `RPOPLPUSH` 로 처리 중 리스트를 따로 두는 우회가 있지만, 소유자 추적·타임아웃 회수·다중 소비자 분배를 전부 애플리케이션이 구현해야 한다.

Pub/Sub 은 더 약하다. 구독자가 없으면 메시지는 그냥 버려지고, 재연결 중 발행된 것도 받지 못한다. fire-and-forget 알림 외에는 쓸 수 없다.

Streams 는 이 중간 상태를 자료구조 안에 넣었다. 소비자가 `XREADGROUP` 으로 메시지를 받으면 그 메시지는 해당 소비자 이름으로 **PEL** 에 등록되고, `XACK` 이 오기 전까지 남는다. 소비자가 죽으면 PEL 에 남은 항목을 다른 소비자가 소유권을 뺏어 처리할 수 있다. 즉 at-least-once 를 자료구조 수준에서 보장한다.

## 2. 엔트리 ID 와 스트림의 물리 구조

각 엔트리 ID 는 `<밀리초 타임스탬프>-<시퀀스>` 형태다. `1755600000000-0` 처럼 생겼고 단조 증가가 보장된다. 같은 밀리초에 여러 건이 들어오면 시퀀스가 올라간다. 명시적으로 ID 를 지정할 수도 있는데, 이때도 마지막 ID 보다 커야 한다.

```bash
XADD orders '*' orderId 1001 amount 25000 status PAID
# → "1755600123456-0"

XADD orders 1755600123456-5 orderId 1002 amount 31000 status PAID
# 명시 ID. 이후 XADD 는 이보다 커야 함
```

내부적으로 Streams 는 radix tree(rax) 위에 **listpack 매크로 노드**를 매단 구조다. 여러 엔트리를 하나의 listpack 에 델타 인코딩으로 밀어 넣고, 필드명이 반복되면 첫 엔트리의 마스터 필드 목록을 참조해 생략한다. 그래서 동일 스키마 메시지를 계속 넣으면 메모리 효율이 매우 좋다. 반대로 매 엔트리마다 필드 이름이 다르면 이 최적화가 무너진다. 실무 규칙은 **필드 스키마를 고정**하고 가변 데이터는 하나의 payload 필드에 직렬화해 넣는 것이다.

```bash
XADD events '*' type ORDER_PAID ts 1755600123456 payload '{"orderId":1001,"amount":25000}'
```

## 3. 컨슈머 그룹의 상태 모델

그룹 생성 시 시작 지점을 정한다. `0` 은 처음부터, `$` 는 지금 이후 새 메시지부터다.

```bash
XGROUP CREATE orders order-workers $ MKSTREAM
```

`MKSTREAM` 은 스트림이 없으면 만들어 준다. 이게 없으면 배포 순서에 따라 소비자가 먼저 떠서 `NOGROUP` 에러를 뿜는다.

읽기는 두 가지 모드다.

```bash
# 새 메시지 (last_delivered_id 이후)
XREADGROUP GROUP order-workers worker-1 COUNT 10 BLOCK 5000 STREAMS orders '>'

# 이 소비자의 PEL 에 이미 있는 것 재조회 (delivery_count 증가 없음)
XREADGROUP GROUP order-workers worker-1 COUNT 10 STREAMS orders 0
```

`>` 는 그룹의 `last_delivered_id` 를 전진시키며 새 메시지를 배분한다. `0` 은 자기 PEL 을 처음부터 다시 읽는다. 후자는 **재시작 직후 자기가 처리 중이던 것을 회수**하는 용도다. 소비자 부팅 시퀀스는 항상 이렇게 짠다.

```java
// 1) 재시작 복구: 내 PEL 부터 비운다
String cursor = "0";
while (true) {
	List<MapRecord<String, String, String>> pending = ops.read(
			Consumer.from(GROUP, consumerName),
			StreamReadOptions.empty().count(100),
			StreamOffset.create(STREAM, ReadOffset.from(cursor)));
	if (pending == null || pending.isEmpty()) {
		break;
	}
	pending.forEach(this::handleAndAck);
	cursor = pending.get(pending.size() - 1).getId().getValue();
}

// 2) 정상 루프: 새 메시지
while (running) {
	List<MapRecord<String, String, String>> records = ops.read(
			Consumer.from(GROUP, consumerName),
			StreamReadOptions.empty().count(50).block(Duration.ofSeconds(5)),
			StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
	if (records != null) {
		records.forEach(this::handleAndAck);
	}
}
```

여기서 `consumerName` 이 재시작 사이에 **안정적으로 같아야** 한다. 쿠버네티스라면 Deployment 의 랜덤 파드명 대신 StatefulSet 의 ordinal(`worker-0`, `worker-1`)을 쓰거나, 환경변수로 고정된 이름을 주입한다. 파드명이 매번 바뀌면 죽은 소비자의 PEL 이 계속 누적되어 아무도 회수하지 않는 고아 항목이 쌓인다.

## 4. PEL 관찰과 XPENDING

`XPENDING` 은 두 가지 형태가 있다. 요약형은 O(1) 에 가깝고 상세형은 범위 스캔이다.

```bash
# 요약: 총 개수, 최소/최대 ID, 소비자별 개수
XPENDING orders order-workers
# 1) (integer) 37
# 2) "1755600100000-0"
# 3) "1755600180000-3"
# 4) 1) 1) "worker-1"
#       2) "25"
#    2) 1) "worker-2"
#       2) "12"

# 상세: idle 시간 필터 포함
XPENDING orders order-workers IDLE 60000 - + 100
# 각 항목: [id, consumer, idle_ms, delivery_count]
```

모니터링은 세 지표로 충분하다. 첫째 PEL 총 개수 — 소비 지연의 직접 지표. 둘째 최대 idle 시간 — 회수되지 않는 항목의 존재. 셋째 `delivery_count` 최댓값 — 독약 메시지(poison message)의 존재. `delivery_count` 가 계속 오르는 항목은 처리할 때마다 예외가 나는 메시지이므로 무한 재처리를 하고 있다는 뜻이다.

## 5. XCLAIM 과 XAUTOCLAIM

소유권 이전에는 두 명령이 있다. `XCLAIM` 은 ID 를 명시해야 하므로 `XPENDING` 으로 목록을 먼저 뽑아야 한다. Redis 6.2 에서 추가된 `XAUTOCLAIM` 은 스캔과 클레임을 한 번에 한다.

```bash
XAUTOCLAIM orders order-workers worker-3 60000 0-0 COUNT 50
# 반환:
# 1) "1755600150000-0"   ← 다음 스캔 커서
# 2) 1) 1) "1755600100000-0"
#       2) 1) "type" 2) "ORDER_PAID" ...
# 3) 1) "1755600105000-0"   ← 스트림에서 이미 삭제된 항목 (Redis 7.0+)
```

세 번째 반환값이 중요하다. `XDEL` 이나 트리밍으로 원본 엔트리가 사라졌지만 PEL 에는 남은 "좀비 항목"인데, `XAUTOCLAIM` 이 이를 자동으로 PEL 에서 제거하고 목록으로 알려준다. Redis 6.2 에서는 이 항목들이 빈 필드로 반환되어 처리 코드가 NPE 를 냈다. 7.0 이상을 쓰는 것이 좋다.

min-idle-time(위 예에서 60000ms)은 신중히 정한다. 정상 처리 시간의 최댓값보다 충분히 커야 한다. 처리에 평균 2초, p99 가 30초 걸리는 작업에 min-idle 을 10초로 잡으면, 아직 살아서 처리 중인 소비자의 메시지를 다른 소비자가 뺏어가 **중복 처리**가 일상이 된다. 규칙은 `min_idle > p99_처리시간 * 2` 정도다.

주기적 회수 루프는 별도 스케줄러로 돌린다.

```java
@Scheduled(fixedDelay = 30_000)
public void reclaimStalled() {
	String cursor = "0-0";
	do {
		AutoClaimResult result = streamCommands.xAutoClaim(
				STREAM, GROUP, consumerName, Duration.ofMinutes(2),
				RecordId.of(cursor), 50);
		cursor = result.getNextId().getValue();
		result.getRecords().forEach(record -> {
			long deliveries = deliveryCountOf(record.getId());
			if (deliveries > MAX_RETRY) {
				moveToDeadLetter(record);
				streamCommands.xAck(STREAM, GROUP, record.getId());
			} else {
				handleAndAck(record);
			}
		});
	} while (!"0-0".equals(cursor));
}
```

`delivery_count` 초과분을 DLQ 스트림으로 옮기고 `XACK` 으로 PEL 에서 떼는 것이 핵심이다. 이 분기가 없으면 처리 불가 메시지 하나가 소비자 전체를 영원히 붙잡는다.

## 6. 트리밍이 만드는 데이터 손실

스트림은 무한히 자란다. 트리밍이 필수인데, 여기에 함정이 있다.

```bash
# 근사 트리밍 — 매크로 노드 경계에서만 자름, O(1) 에 가까움
XADD orders MAXLEN '~' 1000000 '*' field value

# 정확 트리밍 — 정확히 100만 개로 자름, 비용 큼
XADD orders MAXLEN 1000000 '*' field value

# ID 기준 — 특정 시각 이전 삭제
XTRIM orders MINID '~' 1755513600000
```

`XTRIM` 은 **PEL 을 확인하지 않는다**. 아직 아무도 ACK 하지 않은 메시지도 그냥 지운다. 지워진 엔트리의 PEL 항목은 남지만 본문은 없으므로 재처리가 불가능하다. 소비가 밀린 상태에서 트리밍이 돌면 조용히 데이터가 사라진다.

방어는 두 가지다. 첫째, MAXLEN 을 최대 예상 백로그의 몇 배로 넉넉히 잡는다. 초당 1,000건 유입에 소비 지연 허용치가 1시간이면 최소 360만 건이고, 여유를 두어 1,000만으로 잡는다. 둘째, MINID 트리밍을 쓸 때 `XPENDING` 의 최소 ID 보다 앞선 지점만 자른다.

```java
PendingMessagesSummary summary = streamCommands.xPending(STREAM, GROUP);
RecordId oldestPending = summary.minMessageId();
long safeCutoff = Math.min(
		System.currentTimeMillis() - RETENTION_MS,
		parseTimestamp(oldestPending));
streamCommands.xTrim(STREAM, MinId.of(safeCutoff), true);
```

`~`(근사) 옵션은 매크로 노드 단위로만 자르므로 지정값보다 항상 많이 남는다. 즉 근사 트리밍은 "덜 지운다" 방향이라 안전 쪽으로 치우친다. 정확 트리밍은 큰 스트림에서 O(n) 삭제를 유발해 메인 스레드를 블록하므로, 대량 스트림에서는 `~` 를 기본으로 쓴다.

## 7. Kafka 와의 구조적 차이

| 항목 | Redis Streams | Kafka |
|---|---|---|
| 병렬 단위 | 소비자 수(파티션 개념 없음) | 파티션 수 |
| 순서 보장 | 스트림 전체 순서, 그룹 분배 시 소비자 간 순서 없음 | 파티션 내 순서 |
| 진행 상태 | PEL(메시지 단위 추적) | 오프셋(단일 정수) |
| 재처리 | 개별 메시지 XCLAIM | 오프셋 되감기(전체) |
| 보존 | 메모리 기반, 트리밍 필수 | 디스크 기반, 장기 보존 |
| 내구성 | AOF/RDB 의존, 복제는 비동기 | 복제 팩터 + ISR + acks=all |

가장 큰 차이는 내구성이다. Redis 복제는 기본이 비동기라 마스터가 죽으면 아직 복제되지 않은 `XADD` 가 유실된다. `WAIT numreplicas timeout` 으로 동기 대기를 흉내낼 수 있지만 Kafka 의 ISR 보장과는 성격이 다르고, 지연을 크게 늘린다. 금융 거래처럼 유실이 허용되지 않는 경로에는 Streams 를 단독으로 쓰지 않는다. 대신 **DB 트랜잭션 + Outbox 테이블**에 먼저 쓰고, 별도 릴레이가 Streams 로 밀어 넣는 구조로 유실 구간을 없앤다.

반대로 Streams 가 유리한 지점도 명확하다. 파티션 수에 병렬도가 묶이지 않으므로 소비자를 자유롭게 늘릴 수 있고, 메시지 단위 재시도가 자연스럽다. Kafka 에서 특정 메시지 하나만 재시도하려면 별도 retry 토픽 체인을 만들어야 하는데, Streams 는 `XCLAIM` 한 줄이다. 지연 시간도 인메모리라 밀리초 단위다. 작업 큐(job queue) 성격의 워크로드에는 Streams 가, 이벤트 로그·스트림 처리에는 Kafka 가 맞는다.

## 8. 멱등 처리와 중복 방어

at-least-once 이므로 소비자는 반드시 멱등해야 한다. 실무에서 쓰는 패턴은 처리 완료 마킹을 원자적으로 하는 것이다.

```java
private void handleAndAck(MapRecord<String, String, String> record) {
	String messageId = record.getId().getValue();
	Boolean firstTime = redis.opsForValue()
			.setIfAbsent("processed:" + messageId, "1", Duration.ofHours(24));
	if (Boolean.FALSE.equals(firstTime)) {
		streamCommands.xAck(STREAM, GROUP, record.getId());
		return;
	}
	try {
		businessHandler.handle(record.getValue());
		streamCommands.xAck(STREAM, GROUP, record.getId());
	} catch (Exception e) {
		redis.delete("processed:" + messageId);
		log.error("handling failed, will be reclaimed: {}", messageId, e);
	}
}
```

실패 시 마킹 키를 지워야 재처리가 가능하다. 이 부분을 빠뜨리면 한 번 실패한 메시지가 영원히 "이미 처리됨"으로 스킵된다. 더 견고하게는 비즈니스 DB 트랜잭션 안에 처리 이력 테이블 삽입을 함께 넣어 unique 제약으로 중복을 막는다. Redis 키 방식은 Redis 자체가 유실되면 무너지므로, 중요도에 따라 선택한다.

TTL 을 24시간으로 둔 이유는 무한 증가 방지다. min-idle-time 과 최대 재시도 횟수를 곱한 값보다 충분히 크면 된다.

## 참고

- Redis Documentation — Streams Introduction, `XADD`/`XREADGROUP`/`XAUTOCLAIM` 커맨드 레퍼런스
- Redis Source — `t_stream.c`, rax 및 listpack 매크로 노드 구현
- Redis Documentation — Replication, `WAIT` 커맨드 semantics
- Spring Data Redis Reference — Redis Streams 지원
- Martin Kleppmann, *Designing Data-Intensive Applications* — 11장 Stream Processing
