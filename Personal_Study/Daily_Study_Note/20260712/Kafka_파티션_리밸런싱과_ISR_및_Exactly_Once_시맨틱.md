Notion 원본: https://www.notion.so/39b5a06fd6d381bea59deaeb275a491a

# Kafka 파티션 리밸런싱과 ISR 및 Exactly-Once 시맨틱

> 2026-07-12 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- Consumer Group 리밸런싱의 Eager vs Cooperative(Incremental) 프로토콜 차이를 설명한다.
- ISR(In-Sync Replica)과 `acks`, `min.insync.replicas` 가 내구성·가용성 트레이드오프를 어떻게 만드는지 파악한다.
- 멱등 프로듀서와 트랜잭션으로 Exactly-Once 시맨틱(EOS)을 구성한다.
- consume-transform-produce 파이프라인에서 오프셋 커미트를 트랜잭션에 포함시키는 이유를 이해한다.

## 1. 파티션과 Consumer Group 배분

토픽은 여러 파티션으로 나누고, 파티션이 병렬성의 단위다. 한 Consumer Group 안에서 각 파티션은 **정확히 한 소비자**에게만 배정된다. 따라서 그룹 내 최대 유효 병렬도는 파티션 수를 넘지 못한다. 소비자가 파티션보다 많으면 남는 소비자는 놀고, 적으면 한 소비자가 여러 파티션을 맡는다.

파티션 배정은 그룹 코디네이터(브로커)가 조율하고, 실제 배정 로직은 클라이언트의 `partition.assignment.strategy`(RangeAssignor, RoundRobinAssignor, StickyAssignor, CooperativeStickyAssignor)가 수행한다. 소비자가 합류·이탈하거나 파티션 수가 바뀌면 **리밸런싱**이 트리거된다.

## 2. Eager vs Cooperative 리밸런싱

**Eager(전통, stop-the-world):** 리밸런싱이 시작되면 모든 소비자가 자신의 **모든** 파티션 소유권을 반납(revoke)하고, 재배정을 받은 뒤 다시 소비를 시작한다. 리밸런싱 동안 그룹 전체가 처리를 멈추므로("stop-the-world"), 소비자가 많거나 리밸런싱이 잦으면 처리 공백이 크다.

**Cooperative(Incremental, `CooperativeStickyAssignor`):** 바뀌어야 하는 파티션만 반납한다. 대다수 소비자는 자기 파티션을 계속 유지한 채 소비를 이어가고, 이동이 필요한 소수 파티션만 두 번의 리밸런싱(revoke → assign)으로 넘긴다. Kafka 2.4+ 에서 도입됐고, 소비자 수가 많은 그룹의 배포·오토스케일링 시 중단을 크게 줄인다.

```java
Properties props = new Properties();
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
        "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, "worker-1"); // static membership
```

`group.instance.id` 를 지정한 **static membership** 은 소비자 재시작 시 세션 타임아웃 내에 같은 ID 로 복귀하면 리밸런싱을 아예 건너뛴다. 롤링 배포처럼 짧은 재시작이 잦은 환경에서 불필요한 리밸런싱을 없애는 강력한 수단이다.

리밸런싱 안전성의 핵심은 `ConsumerRebalanceListener` 다. `onPartitionsRevoked` 에서 처리 중이던 오프셋을 커미트해야 재배정 후 중복·유실을 줄인다. Cooperative 에서는 `onPartitionsLost` 콜백도 처리해야 한다.

## 3. 복제와 ISR

각 파티션은 한 리더와 여러 팔로워 복제본을 가진다. 프로듀서·컴슈머는 리더와만 통신하고, 팔로워는 리더에서 데이터를 fetch 해 복제한다. **ISR(In-Sync Replica)** 은 리더와 충분히 동기화된(설정 `replica.lag.time.max.ms` 안에 따라잡은) 복제본 집합이다. 리더 장애 시 ISR 안의 복제본만 새 리더가 될 자격이 있다.

`acks` 프로듀서 설정이 내구성을 결정한다.

| acks | 확인 조건 | 내구성 | 지연/처리량 |
|---|---|---|---|
| 0 | 전송 즉시 성공 처리 | 매우 낮음(유실 가능) | 최고 처리량 |
| 1 | 리더만 기록 | 중간(리더 장애 시 유실) | 중간 |
| all(-1) | ISR 전체 기록 | 높음 | 가장 느림 |

`acks=all` 만으로는 부족하다. ISR 이 리더 하나로 줄어든 상태에서 `acks=all` 은 리더 한 대 기록만으로 성공 처리되어, 그 리더가 죽으면 유실된다. 이를 막으려 `min.insync.replicas=2` 를 함께 건다. 그러면 ISR 이 2 미만이면 프로듀서가 `NotEnoughReplicasException` 을 받아 쓰기를 거부한다. 즉 **내구성을 위해 가용성을 희생**하는 것이다. 복제 팩터 3 + `min.insync.replicas=2` + `acks=all` 이 표준 내구성 조합으로, 한 대 장애를 견디면서 무손실을 보장한다.

`unclean.leader.election.enable=false`(기본)는 ISR 밖의 뒤처진 복제본이 리더가 되는 것을 막아 데이터 유실을 방지한다. true 로 켜면 가용성은 오르지만 커미된 데이터가 사라질 수 있다.

## 4. 멱등 프로듀서 — 중복 없는 전송

네트워크 재시도는 같은 메시지를 두 번 쓰는 중복을 만든다. **멱등 프로듀서**(`enable.idempotence=true`, Kafka 3.0+ 기본 활성)는 각 프로듀서에 PID(Producer ID)를 부여하고 파티션별 시퀀스 번호를 붙인다. 브로커는 (PID, 파티션, 시퀀스)로 중복을 감지해 한 번만 기록한다. 이는 단일 프로듀서 세션 내 한 파티션에 대한 중복 제거를 보장한다.

```java
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.ACKS_CONFIG, "all");            // 멱등성 요구
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5); // 순서 유지 한계
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
```

멱등성은 `acks=all` 을 요구하고, in-flight 요청 5 이하에서 순서를 보장한다. 다만 멱등성만으로는 프로듀서 재시작(새 PID) 간 중복이나 여러 파티션에 걸친 원자성은 보장하지 못한다. 그것이 트랜잭션의 영역이다.

## 5. 트랜잭션과 Exactly-Once 시맨틱

**트랜잭션**은 여러 파티션에 대한 쓰기와 컴슈머 오프셋 커미트를 하나의 원자 단위로 묶는다. `transactional.id` 를 부여하면 프로듀서 재시작을 넘어서도 동일 트랜잭션 상태를 이어받아, 좍비 프로듀서(같은 id 의 이전 인스턴스)를 **펜싱(fencing)** 으로 배제한다.

EOS 가 가장 빛나는 곳은 **consume-transform-produce** 파이프라인이다. 입력 토픽에서 읽고, 가공해, 출력 토픽에 쓴 뒤, 입력 오프셋을 커미트하는 흐름에서 "출력 쓰기"와 "오프셋 커미트"가 원자적이어야 정확히 한 번이 성립한다. 둘이 분리되면 출력은 썬는데 오프셋 커미트 전에 죽어 재처리(중복)되거나, 오프셋만 커미되고 출력 실패로 유실된다.

```java
producer.initTransactions();
while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, String> record : records) {
            producer.send(new ProducerRecord<>("output", transform(record.value())));
        }
        // 오프셋 커미트를 트랜잭션에 포함 — 핵심
        Map<TopicPartition, OffsetAndMetadata> offsets = currentOffsets(records);
        producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
        producer.commitTransaction();
    } catch (KafkaException e) {
        producer.abortTransaction(); // 실패 시 전체 롤백
    }
}
```

소비 측은 `isolation.level=read_committed` 로 설정해야 커미된 트랜잭션의 메시지만 읽고, 진행 중·중단된 트랜잭션의 메시지는 건너뛴다. `read_uncommitted`(기본)면 aborted 메시지까지 읽혀 EOS 가 깨진다.

```java
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // 오프셋은 트랜잭션이 커미
```

## 6. EOS 의 비용과 경계

EOS 는 공짜가 아니다. 트랜잭션 마커 쓰기, 2단계 커미트 유사 프로토콜, `read_committed` 의 LSO(Last Stable Offset) 대기 때문에 지연이 늘고 처리량이 준다. 실측상 트랜잭션 배치를 너무 작게(레코드 몇 개) 잡으면 마커 오버헤드가 커지므로, 적절한 배치 크기로 커미트 빈도를 낮춰야 한다. 또한 EOS 는 Kafka 내부(토픽→토픽) 경계에서만 완결적이다. 외부 DB 로 쓰는 순간 Kafka 트랜잭션과 DB 트랜잭션은 별개이므로, outbox 패턴이나 멱등 upsert 로 외부 시스템의 중복을 별도 방어해야 한다.

## 7. 오프셋 커미트 시맨틱과 중복·유실

EOS 를 안 쓰는 대다수 서비스에서 정확성의 핵심은 오프셋 커미트 타이밍이다. `enable.auto.commit=true` 는 백그라운드에서 주기적으로(`auto.commit.interval.ms`) 커미하는데, "처리 완료"가 아니라 "poll 로 가져옴"을 기준으로 커미하므로 처리 도중 죽으면 유실이 난다. 정확성이 중요하면 자동 커미트를 끄고 처리 후 수동 커미트한다.

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// ...
for (ConsumerRecord<String, String> record : records) {
    process(record); // 먼저 처리
}
consumer.commitSync(); // 처리 성공 후 커미트 -> at-least-once
```

"먼저 처리 후 커미트"는 at-least-once(중복 가능, 유실 없음)를, "먼저 커미트 후 처리"는 at-most-once(유실 가능, 중복 없음)를 만든다. 대다수 실무는 at-least-once 를 택하고 소비 측을 멱등하게(같은 키 upsert, 처리 여부 dedup 테이블) 설계해 실질적 정확성을 확보한다. `commitSync` 는 안전하지만 느리고, `commitAsync` 는 빠르지만 실패 시 재시도를 직접 관리해야 한다. 흔한 절충은 루프에서는 `commitAsync`, 리밸런싱·종료 시점에만 `commitSync` 로 확정하는 방식이다.

배치 처리 시 개별 레코드 오프셋을 명시 지정해 부분 성공을 반영할 수도 있다.

```java
Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
offsets.put(new TopicPartition(record.topic(), record.partition()),
        new OffsetAndMetadata(record.offset() + 1)); // +1: 다음에 읽을 위치
consumer.commitSync(offsets);
```

`offset + 1` 을 커미트하는 이유는 커미 값이 "다음에 읽을 오프셋"을 의미하기 때문이다. 처리한 마지막 오프셋 그대로 커미하면 재시작 시 그 레코드를 한 번 더 읽는 흔한 버그가 된다.

## 8. 운영 관점 체크리스트

내구성 표준 조합은 복제 팩터 3, `min.insync.replicas=2`, `acks=all`, `unclean.leader.election.enable=false` 다. 리밸런싱 안정화를 위해 `CooperativeStickyAssignor` 와 static membership 을 쓰고, `max.poll.interval.ms` 를 처리 시간보다 넣넨히 잡아 처리 지연이 세션 이탈로 오해되지 않게 한다. EOS 가 정말 필요한지 먼저 판단하는 것이 중요하다. 다수 워크로드는 멱등 프로듀서(중복 방지) + 소비 측 멱등 처리(같은 키 upsert)만으로 실무적 "effectively once" 를 달성할 수 있고, 완전한 트랜잭션 EOS 는 금융 정산처럼 원자성이 계약 수준으로 요구될 때 도입하는 것이 비용 대비 합리적이다.

## 참고

- Apache Kafka Documentation — Design, Replication, Consumer Rebalancing
- KIP-429: Incremental Cooperative Rebalancing
- KIP-98: Exactly Once Delivery and Transactional Messaging
- "Kafka: The Definitive Guide" 2nd ed. (O'Reilly)
