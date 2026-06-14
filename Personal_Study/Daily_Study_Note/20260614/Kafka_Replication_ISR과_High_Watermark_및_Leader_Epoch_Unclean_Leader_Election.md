Notion 원본: https://www.notion.so/37f5a06fd6d3810893b2cf74e1c96d0c

# Kafka Replication ISR과 High Watermark 및 Leader Epoch Unclean Leader Election

> 2026-06-14 신규 주제 · 확장 대상: Spring / AI_Multi-Agent

## 학습 목표

- ISR 의 정의와 leader/follower 복제 흐름을 LEO·HW 관점에서 추적한다
- acks 와 min.insync.replicas 조합이 만드는 내구성 보장을 구분한다
- unclean leader election 이 일으키는 데이터 손실 시나리오를 설명한다
- leader epoch 가 log divergence 와 truncation 버그를 어떻게 막는지 설명한다

## 1. 복제 구조와 ISR

Kafka 의 각 파티션은 하나의 leader 와 여러 follower replica 로 구성된다. 프로듀서·컨슈머의 모든 읽기·쓰기는 leader 가 처리하고, follower 는 leader 의 로그를 fetch 해 복제만 한다(읽기 부하 분산이 아니라 내구성 목적). ISR(In-Sync Replicas)은 leader 와 충분히 동기화된 replica 집합이다. follower 가 `replica.lag.time.max.ms`(기본 30초) 안에 leader 의 최신 오프셋까지 fetch 요청을 보내면 ISR 에 머물고, 그보다 뒤처지면 ISR 에서 제외된다. 과거에는 메시지 개수 기준(`replica.lag.max.messages`)도 있었으나 버스트 트래픽에서 오작동이 질아 시간 기준만 남았다.

각 replica 는 LEO(Log End Offset, 자기 로그의 다음 쓸 위치)를 갖는다. leader 는 ISR 에 속한 모든 replica 의 LEO 중 최소값을 High Watermark(HW)로 정한다. HW 는 "ISR 전체가 복제 완료한 지점"이며, 컨슈머는 HW 미만의 메시지만 읽을 수 있다. HW 위의 메시지는 아직 모든 ISR 에 복제되지 않았으므로 leader 가 바뀜면 사라질 수 있어 노출하지 않는 것이다.

## 2. 쓰기 흐름과 HW 진전

프로듀서가 메시지를 보내면 leader 가 자기 로그에 append 하고 LEO 를 올린다. follower 들은 주기적으로 fetch 하면서 자신이 받은 마지막 오프셋을 leader 에 알린다. leader 는 이 정보로 각 follower 의 LEO 를 추적하고, ISR 최소 LEO 까지 HW 를 전진시킨다. 진전된 HW 는 다음 fetch 응답에 실려 follower 에게도 전파된다.

```
시점 t0: leader LEO=5, HW=3 / follower A LEO=4 / follower B LEO=3
시점 t1: A,B 가 fetch 로 offset 5 까지 복제 → 모두 LEO=5
시점 t2: leader 가 ISR 최소 LEO(=5)로 HW=5 갱신 → 컨슈머가 offset 4 읽기 가능
```

핵심은 메시지가 컨슈머에게 보이는 시점(HW 도달)과 프로듀서에게 ack 가 가는 시점이 acks 설정에 따라 달라진다는 점이다.

## 3. acks 와 min.insync.replicas

프로듀서의 `acks` 는 leader 가 언제 응답할지를 정한다. `acks=0` 은 전송 즉시 성공 처리(손실 가능), `acks=1` 은 leader 로그 append 후 응답(leader 가 복제 전에 죽으면 손실), `acks=all`(=-1) 은 ISR 전체가 복제한 뒤 응답한다.

그런데 `acks=all` 만으로는 부족하다. ISR 이 leader 하나로 줄어든 상태에서 `acks=all` 은 그 하나만 받으면 성공으로 처리하므로, 그 leader 가 죽으면 데이터가 사라진다. 이를 막는 것이 토픽/브로커 설정 `min.insync.replicas` 다. 이 값은 `acks=all` 쓰기가 성공하려면 ISR 크기가 최소 몇이어야 하는지를 강제한다. ISR 이 그 미만이면 leader 는 쓰기를 거부(`NotEnoughReplicasException`)한다.

```properties
# 브로커/토픽 설정
replication.factor=3
min.insync.replicas=2     # ISR 이 2 미만이면 acks=all 쓰기 거부
# 프로듀서 설정
acks=all
enable.idempotence=true   # 중복·순서 보장(내부적으로 acks=all 강제)
```

```java
Properties props = new Properties();
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
```

`replication.factor=3` 과 `min.insync.replicas=2` 조합이 표준 권장이다. replica 3개 중 1개가 죽어도 ISR 이 2 이므로 쓰기가 계속되고, 2개가 죽어 ISR 이 1 이 되면 쓰기를 막아 손실을 예방한다. `min.insync.replicas` 를 `replication.factor` 와 같게(3) 두면 한 대만 죽어도 쓰기가 멈추니, 가용성과 내구성의 균형점으로 N-1 을 쓴다.

## 4. unclean leader election 의 손실

leader 가 죽으면 컨트롤러가 새 leader 를 뽑는다. 기본값 `unclean.leader.election.enable=false` 에서는 ISR 에 속한 replica 중에서만 leader 를 선출한다. 만약 ISR 의 모든 replica 가 죽고 ISR 밖의 뒤처진 replica 만 살아 있다면, 새 leader 를 뽑지 못해 파티션이 오프라인이 된다. 가용성은 잃지만 데이터는 보존된다.

`unclean.leader.election.enable=true` 로 켜면 ISR 밖의 뒤처진 replica 도 leader 가 될 수 있다. 파티션은 즉시 살아나지만, 그 replica 는 HW 위의 커밋된 메시지를 갖고 있지 않을 수 있어 이미 컨슈머가 읽었거나 ack 받은 메시지가 영구 손실된다. 즉 unclean election 은 "데이터를 버리고 가용성을 산다". 금융·주문처럼 손실이 치명적이면 false, IoT 텔레메트리처럼 일부 손실보다 중단이 더 나쁜 경우에만 true 를 신중히 고려한다.

## 5. leader epoch: truncation 버그 방지

HW 기반 복제에는 미묘한 결함이 있었다. follower 가 재시작하면 자기 HW 까지만 신뢰하고 그 위를 잘라낸(truncate) 뒤 leader 를 따라가는데, leader 와 follower 의 HW 전파에 시차가 있어 두 로그가 서로 다르게 갈라지는(log divergence) 사례가 있었다. 예컨대 follower 가 HW 를 늦게 받은 상태에서 leader 가 되면, 새로 들어온 follower 가 자기 메시지를 부당하게 잘라내거나, 반대로 갈라진 메시지를 남겨 두 replica 의 같은 오프셋에 다른 메시지가 존재하는 일이 생겼다.

leader epoch 가 이를 해결한다. epoch 는 leader 가 바뀔 때마다 1씩 증가하는 단조 번호로, 각 메시지 구간에 "이 구간을 쓴 leader 의 epoch 과 시작 오프셋"을 기록한 leader epoch cache 를 둔다. follower 는 재시작 시 자기 HW 를 무조건 믿고 자르는 대신, leader 에게 `OffsetForLeaderEpoch` 요청을 보내 "내 마지막 epoch 의 끝 오프셋이 어디냐"를 물어본다. leader 의 응답과 자기 로그를 비교해 정확히 갈라지는 지점만 잘라내므로, 시차로 인한 과도·과소 truncation 이 사라진다.

```
follower epoch cache: epoch=5 → startOffset=100
follower 가 leader 에 OffsetForLeaderEpoch(epoch=5) 질의
leader 응답: epoch=5 의 끝은 offset=130 (leader epoch=6 은 130부터)
→ follower 는 130 까지 보존하고 그 위만 잘라 정확히 정합
```

KRaft 모드(ZooKeeper 제거)에서도 복제 의미는 동일하며, 메타데이터 자체가 `__cluster_metadata` 라는 내부 토픽에 동일한 leader epoch·복제 규칙으로 관리된다. 즉 ISR·HW·epoch 모델은 데이터 토픽뿐 아니라 클러스터 메타데이터에도 일관되게 적용된다.

## 6. 컨슈머 관점의 정합성: HW 와 LSO

복제 정합성은 컨슈머가 무엇을 읽을 수 있는지로도 드러난다. 컨슈머는 HW 미만만 읽는다고 했지만, 트랜잭션 프로듀서를 쓰면 한 단계가 더 있다. read_committed 격리에서 컨슈머는 LSO(Last Stable Offset)까지만 읽는다. LSO 는 아직 커밋·어보트가 결정되지 않은 미결 트랜잭션의 시작 지점 직전이다. 진행 중인 트랜잭션의 메시지는 HW 아래에 있어도 결과가 확정될 때까지 컨슈머에게 보이지 않는다.

```properties
# 컨슈머: 커밋된 트랜잭션 메시지만 소비
isolation.level=read_committed   # 기본값 read_uncommitted 는 미결 메시지도 노출
```

이 덕분에 exactly-once 처리에서 "어보트된 트랜잭션의 메시지를 컨슈머가 읽어 버리는" 일이 없다. 브로커는 어보트된 트랜잭션의 오프셋을 abort index 에 기록해 두고, read_committed 컨슈머에게 해당 메시지를 걸러서 전달한다.

## 7. 운영 모니터링과 ISR 진동

실무에서 가장 자주 보는 경보는 ISR 축소(shrink)와 복원(expand)의 반복, 이른바 ISR 진동이다. follower 가 GC stall, 디스크 I/O 지연, 네트워크 지연으로 일시적으로 `replica.lag.time.max.ms` 를 넘기면 ISR 에서 빠졌다가 다시 따라잡으면 복귀한다. 잦은 진동은 그 자체로 장애는 아니지만 leader 선출 후보 부족과 `min.insync.replicas` 위반 위험을 키운다.

```
# 핵심 모니터링 지표
kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions   # 0 이어야 정상
kafka.server:type=ReplicaManager,name=IsrShrinksPerSec            # 지속 증가 시 조사
kafka.server:type=ReplicaManager,name=UnderMinIsrPartitionCount   # 0 초과 시 쓰기 거부 위험
```

`UnderReplicatedPartitions` 가 0 이 아니면 어떤 파티션이 복제 부족 상태라는 뜻이고, `UnderMinIsrPartitionCount` 가 0 을 넘으면 곳 쓰기 거부가 발생한다. 원인은 대개 브로커 디스크 포화, 네트워크 대역 부족, 또는 한 브로커에 leader 가 몰린 불균형이다. `kafka-reassign-partitions` 로 leader·replica 분포를 재조정하거나 follower fetch 스레드(`num.replica.fetchers`)를 늘려 복제 처리량을 확보한다. ISR 진동이 GC 때문이면 브로커 힙·GC 튜닝(G1 region, pause target)이 근본 처방이다.

## 8. 정리

내구성 있는 Kafka 구성은 단일 설정이 아니라 조합이다. `replication.factor=3`, `min.insync.replicas=2`, 프로듀서 `acks=all`+idempotence, `unclean.leader.election.enable=false` 가 손실 없는 표준 세트다. HW 는 컨슈머 가시성과 복구 안전선을, leader epoch 은 장애 후 로그 정합을 책임진다. "한 건도 잃지 않게 할 수 있는가"의 답은 이 조합에서 Yes 이며, 그 대가로 ISR 부족 시 쓰기가 막히는 가용성 제약을 받아들이는 것이다.

## 참고

- Apache Kafka Documentation — Replication, ISR, Durability and Availability
- KIP-101: Leader Epoch 기반 truncation 정합성 개선
- KIP-279, KIP-320: OffsetForLeaderEpoch 프로토콜 확장
- Confluent, "Hands Free Kafka Replication" 및 Kafka Definitive Guide 2nd ed. (복제·내구성 장)
