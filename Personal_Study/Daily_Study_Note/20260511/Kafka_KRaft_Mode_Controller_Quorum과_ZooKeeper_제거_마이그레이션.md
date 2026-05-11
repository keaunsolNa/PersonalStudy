Notion 원본: https://www.notion.so/35d5a06fd6d381b1b412cfa4b0865fca

# Kafka KRaft Mode Controller Quorum과 ZooKeeper 제거 마이그레이션

> 2026-05-11 신규 주제 · 확장 대상: Kafka

## 학습 목표

- Kafka 의 메타데이터 저장소가 ZooKeeper → KRaft Raft quorum 으로 어떻게 옮겨졌고, controller / broker 역할 분리(`process.roles`) 가 무엇을 의미하는지 본다
- `__cluster_metadata` 토픽이 controller 가 만든 *snapshot + log* 구조 위에 어떻게 올라가고, controller leader election, fencing, broker registration 흐름을 단계별로 정리
- ZooKeeper-기반 → KRaft 로의 *bridge 모드* 마이그레이션 절차와 rollback 가능 시점, 그리고 ZooKeeper 모드가 Kafka 4.0 에서 제거된 이후의 운영 변화
- KRaft 도입 후 변경된 핵심 메트릭, ACL/SCRAM 관리, 운영자 입장의 클러스터 사이즈 산정

## 1. 왜 ZooKeeper 를 걷어내나

Kafka 의 메타데이터 (브로커 목록, topic 설정, ISR, ACL)는 historically ZooKeeper 에 저장됐다. 이 구조의 단점:

- ZooKeeper 자체가 *별도 클러스터* — 운영 부담 두 배
- ZK watcher 의 *one-shot* 특성 때문에 *thundering herd* 가 발생
- 메타데이터 변경이 ZK 의 ZAB 프로토콜에 종속 — Kafka 자체의 성능 특성과 분리되어 튜닝이 어려움
- 대형 클러스터(10만+ partition)에서 controller failover 가 분 단위로 걸림 — full metadata reload

KIP-500 (2020 제안) 의 목표는 ZK 를 *Kafka 내부의 Raft 구현* 으로 대체. 2.8 부터 preview, 3.3 부터 production-ready, 4.0(2025)에서 ZK 모드 *완전 제거*.

## 2. `process.roles` — 세 가지 운영 모드

KRaft 의 노드는 `process.roles` 옵션으로 자기 역할을 정한다.

| 값 | 의미 |
|---|---|
| `broker` | 데이터 plane 만 담당. controller 와 통신하는 클라이언트 |
| `controller` | 메타데이터 plane. Raft quorum 멤버 |
| `broker,controller` | *combined* 모드 — 동일 프로세스가 둘 다 (개발 / 소규모) |

production 권장은 *분리* 다. 3~5 노드 controller quorum 을 별도 호스트에 두고, broker 는 별도 노드.

```properties
# controller-only 노드
node.id=1
process.roles=controller
controller.quorum.voters=1@c1:9093,2@c2:9093,3@c3:9093
listeners=CONTROLLER://:9093
controller.listener.names=CONTROLLER
log.dirs=/var/kafka/meta
```

```properties
# broker-only 노드
node.id=11
process.roles=broker
controller.quorum.voters=1@c1:9093,2@c2:9093,3@c3:9093
listeners=PLAINTEXT://:9092
controller.listener.names=CONTROLLER
log.dirs=/var/kafka/data
```

## 3. `__cluster_metadata` 토픽 + snapshot

controller quorum 멤버들은 *내부* 토픽 `__cluster_metadata` 를 공유한다. 이 토픽이 Raft log 의 그릇이다.

쓰기 path:

1. 클러스터 변경 요청이 leader controller 로 도착
2. leader 가 Raft entry 를 append 하고 followers 에게 broadcast
3. quorum (과반)이 fsync 완료를 ack
4. leader 가 commit 처리 후 응답

읽기 path:

- broker 는 controller leader 와 *fetcher* 연결을 유지. metadata delta 가 들어오면 incremental 적용. 전체 reload 불필요.

snapshot:

- Raft log 가 무한히 자라지 않게, 일정 주기로 *full state* 의 snapshot 을 만들고 그 이전의 로그를 잘라낸다(`metadata.log.max.snapshot.interval.ms`, 기본 1h).
- 신규 broker join 시 snapshot 부터 받아 fast catch-up 가능.

## 4. controller leader election

leader 선출은 Raft 표준 절차다.

- 시작: 모든 controller 노드가 `Follower` 상태로 시작, election timeout (랜덤 150~300ms)
- timeout: `Candidate` 로 승격, term 증가, 자기 자신에게 vote
- 다른 follower 에게 `Vote` 요청 — 받는 쪽은 *current term 이하인지*, *자기 log 보다 동등 이상으로 최신인지* 검사
- 과반 vote 획득 → `Leader`. heartbeat 전파.

따라서 controller quorum 은 *홀수* 가 권장된다. 짝수면 split-brain 시 quorum 불가능. 3 노드 quorum 은 1 노드 failure 까지, 5 노드 quorum 은 2 노드 failure 까지 견딜다.

failover 시간: 보통 *수백 ms ~ 2초*. ZK 시절 분 단위 metadata reload 가 사라졌다. 큰 클러스터일수록 이 차이가 극적이다.

## 5. broker 등록과 fencing

broker 가 시작하면 *controller leader 에게 RegisterBrokerRequest* 를 보낸다. controller 는 broker 의 incarnation id 를 발행하고 `__cluster_metadata` 에 RegisterBroker 레코드를 append 한다.

heartbeat 가 일정 시간(`broker.session.timeout.ms`, 기본 18 초) 끊기면 broker 는 *fenced* 상태로 표시되어 ISR / leader 후보에서 자동 제외. 이전 ZK 모드의 `/brokers/ids/{id}` ephemeral znode 와 같은 역할이지만, *Raft 로그 단위로 deterministic* 하게 변경된다.

## 6. ZK → KRaft bridge 마이그레이션 절차

3.5+ 에서 가능, 3.6 GA, 4.0 에서 ZK mode 제거. 절차 요지:

1. ZK 클러스터의 *모든 broker 가 3.5+* 인지 확인. 낮으면 rolling upgrade 먼저.
2. KRaft controller quorum 을 *별도 호스트* 에 시작. `zookeeper.metadata.migration.enable=true`.
3. controller 가 *ZK 로부터 메타데이터 한 번 전체 fetch* 해서 자기 Raft log 에 import.
4. broker 들을 *one by one* `migration` 모드로 재시작 — broker 는 동시에 ZK 와 controller 를 본다(*dual-write* 단계).
5. 모든 broker 가 migration 모드면, `zookeeper.metadata.migration.enable=false` 로 *KRaft only* 모드 전환.
6. ZK 자체는 *유지* (rollback 대비). 안전 확인 후 떼어낸다.

rollback 가능 시점은 *5번* 직전까지. 그 이후엔 controller 의 metadata 가 ZK 의 그것과 divergence 가 생겨 되돌릴 수 없다.

## 7. 4.0 이후 변경된 운영 관점

ZK 모드가 제거된 4.0 에서:

- `zookeeper.connect` 설정이 *제거*. 옇 클라이언트 도구(zookeeper-shell.sh)도 의미 없음.
- ACL / SCRAM 관리가 controller API 로 단일화. `kafka-configs.sh --bootstrap-server` 와 `kafka-acls.sh --bootstrap-server` 모두 controller 와 통신한다.
- `--zookeeper` 옵션이 들어가는 모든 명령은 deprecated. CI/CD 스크립트 점검 필요.

새로 등장한 메트릭(JMX):

- `kafka.controller:type=KafkaController,name=ActiveControllerCount`
- `kafka.controller:type=KafkaController,name=MetadataLogCommitLatencyMs` — Raft log commit p99
- `kafka.server:type=BrokerToControllerChannelManager,name=RequestQueueTimeMs`
- `kafka.controller:type=KafkaController,name=GlobalPartitionCount`

production 알람 임계:

- MetadataLogCommitLatencyMs p99 > 50ms 지속 → controller disk IO 점검
- RequestQueueTimeMs p99 > 200ms → controller 부하, quorum 분리/확장 필요
- ActiveControllerCount != 1 → split-brain 의심

## 8. controller quorum 사이즈 산정

- 3 노드 — 가장 흔한 production 시작점. 1 노드 failure 허용.
- 5 노드 — 대형 클러스터 / 멀티 zone. 2 노드 failure 허용. 단 write latency 가 약간 증가 (과반 ack 가 3 노드 필요).
- 7 노드 — 거의 없음. write latency 증가 대비 가용성 이득이 적음.

controller 머신 사양 권장: CPU 4 core, RAM 8 GB, SSD 100 GB. 메타데이터 토픽이 작아 IOPS 가 크지 않지만, fsync latency 가 중요. NVMe 권장.

## 9. 자주 하는 실수

1. **`controller.quorum.voters` 의 *id* 불일치**: 모든 노드의 quorum 설정이 *완전히 동일* 해야 한다. 한 노드라도 다르면 join 실패.
2. **storage format 미실행**: KRaft 노드는 처음 부팅 전에 `kafka-storage.sh format --cluster-id <uuid> --config server.properties` 를 *반드시* 실행해야 한다. cluster-id 는 동일해야 함.
3. **controller 의 disk 가득**: metadata 토픽 retention 은 `metadata.log.max.record.bytes.between.snapshots` 와 snapshot 주기로 관리. 디스크가 차면 controller 가 멈춰 전체 admin API 가 timeout.
4. **bridge mode 에서 dual-write 검증 누락**: ZK 와 controller 사이 데이터 divergence 가 발생해도 알람이 안 울리면 rollback 시점이 사라진다.
5. **client 라이브러리 호환성**: KRaft 자체는 클라이언트 protocol 을 깨지 않지만, 일부 admin client 가 ZK 직접 접근을 가정하면 동작 불가.

## 참고

- KIP-500 — Replace ZooKeeper with a Self-Managed Metadata Quorum
- KIP-866 — ZooKeeper to KRaft Migration
- Apache Kafka Documentation — KRaft section: https://kafka.apache.org/documentation/#kraft
- Confluent Blog — *Kafka Without ZooKeeper* (Colin McCabe)
- *Kafka: The Definitive Guide* 2nd Ed. (Gwen Shapira et al.), Chapter 5 Brokers
- Raft Consensus Paper — *In Search of an Understandable Consensus Algorithm* (Ongaro & Ousterhout, 2014)
