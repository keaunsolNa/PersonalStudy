Notion 원본: https://www.notion.so/3db5a06fd6d3818f8396dfc8281597b1

# Apache Flink 체크포인트 배리어 정렬과 상태 백엔드 및 Exactly-Once 싱크

> 2026-09-15 신규 주제 · 확장 대상: Kafka Exactly-Once Semantics와 멱등 프로듀서 및 트랜잭션 코디네이터

## 학습 목표

- 비동기 배리어 스냅샷(ABS)이 분산 스트림의 일관된 상태를 어떻게 잘라내는지 설명한다
- 정렬(aligned)과 비정렬(unaligned) 체크포인트의 비용 구조를 구분해 선택한다
- HashMap·RocksDB 상태 백엔드와 증분 체크포인트의 실제 I/O 특성을 파악한다
- 2단계 커밋 싱크로 종단 간 정확히 한 번을 구성하고 트랜잭션 타임아웃을 계산한다

## 1. 문제 정의 — 흘르는 데이터의 일관된 스냅샷

배치 잡은 실패하면 처음부터 다시 돌리면 된다. 스트림 잡은 끝이 없으므로 그럴 수 없고, 주기적으로 상태를 저장한 뒤 실패 시 거기서 재개해야 한다. 어려운 점은 "언제의 상태인가"다.

파이프라인이 `source → keyBy → aggregate → sink` 로 되어 있고 각 연산자가 여러 병렬 인스턴스로 흘어져 있다고 하자. 전역 시계로 "지금 멈추고 전부 저장" 하려면 처리를 멈춰야 한다. 처리량이 곷 가치인 시스템에서 이것은 받아들이기 어렵다.

Flink 는 Chandy-Lamport 분산 스냅샷 알고리즘을 스트림에 맞게 변형한 **비동기 배리어 스냅샷(Asynchronous Barrier Snapshotting)** 으로 이 문제를 푸다. 처리를 멈추는 대신, 데이터 스트림 안에 **체크포인트 배리어**라는 특수 레코드를 흘려보낸다. 배리어 n 보다 앞선 레코드의 효과는 스냅샷 n 에 포함되고, 뒤선 레코드는 포함되지 않는다. 배리어가 전체 토폴로지를 통과하면 그 시점의 전역 상태가 일관되게 정의된다.

## 2. 배리어의 생애

JobManager 안의 `CheckpointCoordinator` 가 주기적으로 체크포인트를 트리거한다.

1. 코디네이터가 모든 소스 태스크에 `triggerCheckpoint(n)` 을 보낸다.
2. 소스는 현재 오프셋을 자기 상태로 스냅샷하고, 출력 스트림에 배리어 n 을 삽입한다.
3. 중간 연산자는 **모든 입력 채널**에서 배리어 n 을 받으면 자기 상태를 스냅샷하고 배리어 n 을 하류로 보낸다.
4. 싱크가 배리어 n 을 받고 스냅샷을 마치면 코디네이터에 ack 한다.
5. 모든 태스크의 ack 가 모이면 코디네이터가 체크포인트 n 을 완료 표시하고, 모든 태스크에 `notifyCheckpointComplete(n)` 을 보낸다.

3번이 **정렬(alignment)** 이다. 입력 채널이 여러 개인 연산자(조인, keyBy 셔플 이후)는 채널마다 배리어 도착 시각이 다르다. 먼저 배리어를 받은 채널의 후속 데이터는 스냅샷 n 에 들어가면 안 되므로, 나머지 채널의 배리어가 도착할 때까지 **버퍼에 담아두고 처리하지 않는다**.

여기서 정확히 한 번(exactly-once)과 최소 한 번(at-least-once)이 갈린다. `at-least-once` 모드는 정렬을 하지 않고 배리어를 받는 즉시 스냅샷한다. 빠르지만, 복구 시 일부 레코드가 이미 상태에 반영된 채로 재처리되어 카운트가 부풀 수 있다.

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
CheckpointConfig cfg = env.getCheckpointConfig();

env.enableCheckpointing(60_000, CheckpointingMode.EXACTLY_ONCE);
cfg.setMinPauseBetweenCheckpoints(30_000);
cfg.setCheckpointTimeout(600_000);
cfg.setMaxConcurrentCheckpoints(1);
cfg.setTolerableCheckpointFailureNumber(3);
cfg.setExternalizedCheckpointRetention(
        ExternalizedCheckpointRetention.RETAIN_ON_CANCELLATION);
```

`minPauseBetweenCheckpoints` 는 실무에서 가장 중요한 손잡이다. 체크포인트가 45초 걸리는데 간격이 60초면 잡은 사실상 계속 체크포인트 중이고, 정렬 버퍼링이 상시화되어 지연이 누적된다. 최소 정지 시간을 두면 "직전 체크포인트 완료 후 최소 30초는 순수 처리"가 보장된다.

## 3. 정렬이 무너지는 순간 — 백프레셔

정렬 시간은 배리어가 가장 느린 채널을 통과하는 데 걸리는 시간이다. 백프레셔가 걸리면 네트워크 버퍼가 가득 차고, 배리어는 그 버퍼 뒤에 줄을 선다. 즉 **백프레셔가 심할수록 정렬 시간이 폭증**하고, 정렬 중에는 처리가 더 막히므로 악순환이 된다. 체크포인트가 타임아웃으로 실패하고, 실패가 누적되어 잡이 죽는 전형적인 장애 경로다.

Flink 1.11 부터 도입된 **비정렬 체크포인트(unaligned checkpoint)** 는 이 고리를 끊는다. 연산자는 어느 한 입력 채널에서 배리어를 받으면 즉시 출력 버퍼 맨 앞으로 배리어를 밀어넣어 하류로 보내고, **아직 처리되지 않은 인플라이트 데이터를 스냅샷의 일부로 함께 저장**한다. 배리어가 데이터를 추월하는 셋이다.

```yaml
# flink-conf.yaml
execution.checkpointing.unaligned.enabled: true
execution.checkpointing.aligned-checkpoint-timeout: 30s
execution.checkpointing.unaligned.max-subtasks-per-channel-state-file: 5
```

`aligned-checkpoint-timeout` 는 하이브리드 전략이다. 먼저 정렬을 시도하다가 지정 시간 안에 끝나지 않으면 그 체크포인트를 비정렬로 전환한다. 평상시에는 정렬의 작은 상태 크기를 누리고, 백프레셔 구간에서만 비정렬로 빠져나온다. 대부분의 운영 잡에 권장할 만한 기본 구성이다.

| 항목 | 정렬 체크포인트 | 비정렬 체크포인트 |
|---|---|---|
| 배리어 처리 | 모든 채널 대기 후 스냅샷 | 첫 배리어 즉시 전달 |
| 상태 크기 | 연산자 상태만 | 연산자 상태 + 인플라이트 버퍼 |
| 백프레셔 영향 | 정렬 시간이 선형 이상으로 증가 | 거의 무관 |
| 복구 비용 | 낮음 | 채널 상태 복원으로 증가 |
| 리스케일 | 자유 | 지원되나 제약이 더 많음 |
| 권장 상황 | 백프레셔 없는 안정 구간 | 버스트·핫키·싱크 지연 구간 |

비정렬이 만능은 아니다. 인플라이트 데이터가 상태에 들어가므로 체크포인트 크기가 커지고, 원격 스토리지 쓰기량이 늘어난다. 네트워크 버퍼가 크게 설정된 잡에서는 이 증가폭이 수 GB 에 달할 수 있다. 근본 원인이 싱크 지연이나 핫키 스큐라면 비정렬은 증상 완화이지 해결이 아니다.

## 4. 상태 백엔드와 체크포인트 스토리지

Flink 1.13 부터 "상태를 어디에 두는가"와 "체크포인트를 어디에 쓰는가"가 분리됐다.

```java
// 로컬 상태: TaskManager JVM 힙
env.setStateBackend(new HashMapStateBackend());
// 로컬 상태: 임베디드 RocksDB (디스크)
env.setStateBackend(new EmbeddedRocksDBStateBackend(true)); // true = 증분 체크포인트

// 체크포인트 목적지
env.getCheckpointConfig().setCheckpointStorage("s3://flink-checkpoints/job-a/");
```

`HashMapStateBackend` 는 상태를 자바 객체 그대로 힙에 둔다. 접근이 포인터 역참조 한 번이라 가장 빠르지만, 상태가 힙 크기에 갇히고 GC 압박을 직접 받는다. 상태가 수 GB 를 넘어가면 풀 GC 정지가 체크포인트 타임아웃을 유발한다.

`EmbeddedRocksDBStateBackend` 는 상태를 직렬화해 LSM 트리에 저장한다. 접근마다 직렬화·역직렬화가 들어가 단건 지연은 몇 배 느리지만, 상태 크기가 디스크 용량까지 확장되고 힙과 무관해진다. 결정 기준은 단순하다. **상태가 힙에 들어가면 HashMap, 아니면 RocksDB.**

증분 체크포인트는 RocksDB 에서만 동작한다. RocksDB 의 SST 파일은 불변이므로, 직전 체크포인트 이후 새로 만들어진 SST 만 업로드하고 나머지는 참조만 남긴다. 상태 100GB 에 변경분이 2GB 라면 업로드도 2GB 다. 대신 복구 시에는 여러 체크포인트에 걸친 SST 조각을 모두 내려받아야 하므로 복구 시간이 길어진다. 컴팩션이 일어나면 큰 SST 가 새로 생기면서 특정 체크포인트만 유독 커지는 퍡니 패턴도 나타난다.

RocksDB 튜닝에서 먼저 볼 항목은 메모리 관리다.

```yaml
state.backend.rocksdb.memory.managed: true          # Flink 관리 메모리에서 할당
taskmanager.memory.managed.fraction: 0.5
state.backend.rocksdb.memory.write-buffer-ratio: 0.5
state.backend.rocksdb.memory.high-prio-pool-ratio: 0.1
state.backend.rocksdb.timer-service.factory: HEAP   # 타이머는 힙에
state.backend.rocksdb.predefined-options: SPINNING_DISK_OPTIMIZED_HIGH_MEM
```

타이머를 힙에 두는 선택이 효과가 큰 경우가 많다. 윈도우 잡은 타이머 접근이 매우 빈번한데, 타이머 수가 관리 가능한 규모라면 RocksDB 왕복을 없애는 것만으로 처리량이 눈에 띄게 오른다. 반대로 타이머가 수억 개면 힙이 터지므로 RocksDB 에 두어야 한다.

Flink 1.15 이후의 **Changelog 상태 백엔드**는 또 다른 축이다. 상태 변경을 변경 로그로 지속적으로 원격에 쓰고, 체크포인트 시점에는 로그 위치만 확정한다. 체크포인트 지속 시간이 짧고 균일해져 초 단위 간격이 현실적이 되지만, 상시 쓰기 비용과 복구 시 로그 재생 비용이 추가된다.

## 5. 종단 간 정확히 한 번 — 2단계 커밋 싱크

체크포인트는 Flink **내부** 상태의 정확성만 보장한다. 외부 시스템에 이미 써버린 데이터는 되돌릴 수 없으므로, 싱크가 체크포인트와 협조해야 한다. 그 프로토콜이 2단계 커밋이다.

- **pre-commit**: 체크포인트 스냅샷 시점에, 싱크는 지금까지의 출력을 "미확정" 상태로 외부 시스템에 밀어넣고 트랜잭션 식별자를 자기 상태에 저장한다. Kafka 면 열린 트랜잭션, 파일 싱크면 임시 파일이다.
- **commit**: 체크포인트가 전역 완료되어 `notifyCheckpointComplete(n)` 이 오면 트랜잭션을 커밋한다. Kafka 면 `commitTransaction()`, 파일이면 임시 파일을 최종 경로로 rename 한다.

실패 시나리오가 두 개다. pre-commit 이후 commit 전에 죽으면, 복구된 태스크가 상태에 남은 트랜잭션 식별자로 **커밋을 재개**한다. pre-commit 중에 죽으면 그 트랜잭션은 버려지고, 체크포인트 n-1 에서 재처리된다.

```java
KafkaSink<Event> sink = KafkaSink.<Event>builder()
        .setBootstrapServers(brokers)
        .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                .setTopic("events-out")
                .setValueSerializationSchema(new EventSerializer())
                .build())
        .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
        .setTransactionalIdPrefix("events-pipeline-v3")
        .setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, "900000")
        .build();
```

여기서 두 설정이 운영을 좌우한다.

**`transactionalIdPrefix`** 는 잡마다 고유해야 한다. 같은 클러스터의 다른 잡이 같은 접두사를 쓰면 트랜잭션 코디네이터가 서로를 좀비로 판정해 펜싱하고, 양쪽 다 죽는다. 또한 잡을 세이브포인트 없이 완전히 새로 시작하면서 같은 접두사를 재사용하면 이전 잡의 열린 트랜잭션과 충돌할 수 있으므로, 파이프라인 대개편 시에는 접두사에 버전을 붙여 올린다.

**`transaction.timeout.ms`** 는 체크포인트 간격보다 충분히 커야 한다. 트랜잭션은 pre-commit 시점에 열려서 다음 체크포인트 완료 시점에 커밋되는데, 장애가 나면 커밋이 복구 이후로 밀린다. 타임아웃이 짧으면 브로커가 먼저 트랜잭션을 중단(abort)시키고, 복구된 잡은 커밋할 수 없어 데이터 유실이 발생한다. 계산식은 대략 이렇다.

```
transaction.timeout.ms
  >= checkpoint interval
   + 최대 체크포인트 소요 시간
   + 예상 복구 시간(재시작 + 상태 다운로드)
   + 여유
```

Kafka 프로듀서 기본값 60초는 거의 항상 부족하다. 브로커의 `transaction.max.timeout.ms`(기본 15분) 이하 범위에서 올려야 하고, 더 필요하면 브로커 설정도 함께 올린다.

소비 측도 짝을 맞춰야 한다. `isolation.level=read_committed` 가 아니면 미확정 메시지가 그대로 읽히므로 정확히 한 번이 무의미해진다.

그리고 **지연의 대가**를 명확히 인식해야 한다. 트랜잭션은 체크포인트 완료 시점에만 커밋되므로, 다운스트림이 데이터를 볼 수 있는 시점은 최대 한 체크포인트 간격만큼 늦다. 체크포인트 간격 60초면 종단 지연 하한이 60초다. 초 단위 지연이 필요하면 간격을 줄이거나(체크포인트 오버헤드 증가), `AT_LEAST_ONCE` 로 내리고 다운스트림을 멱등하게 설계해야 한다.

## 6. 운영에서 보는 지표

```
Checkpoint 지표
├─ checkpointDuration          : 트리거 → 전역 완료
│   ├─ syncDuration            : 동기 스냅샷(짧아야 정상)
│   ├─ asyncDuration           : 원격 업로드(상태 크기에 비례)
│   └─ alignmentDuration       : 정렬 대기(백프레셔 지표)
├─ startDelay                  : 트리거 → 태스크가 실제 처리 시작
├─ stateSize / incrementalSize
└─ numberOfFailedCheckpoints
```

진단 규칙은 다음과 같다.

- `alignmentDuration` 이 크다 → 백프레셔. 비정렬 전환을 고려하되, 원인(싱크 지연/스큐/리소스 부족)을 먼저 본다.
- `asyncDuration` 이 크다 → 상태 크기 또는 오브젝트 스토리지 처리량. 증분 체크포인트, 병렬도 대비 파일 수, S3 멀티파트 설정을 본다.
- `syncDuration` 이 크다 → 힙 GC 또는 RocksDB 스냅샷 경합. 상태 백엔드 선택을 재검토한다.
- `startDelay` 가 크다 → 태스크가 배리어를 받기까지 오래 걸림. 상류 백프레셔 신호다.

## 7. 세이브포인트와 체크포인트의 구분

체크포인트는 시스템이 장애 복구용으로 만들고 관리하는 산출물이고, 세이브포인트는 사람이 의도적으로 만드는 이미지다. 잡 업그레이드, 병렬도 변경, 상태 마이그레이션에 쓴다.

```bash
# 정지하면서 세이브포인트 생성
flink stop --savepointPath s3://flink-savepoints/ <jobId>

# 세이브포인트에서 재개 (연산자가 사라졌다면 허용 플래그 필요)
flink run -s s3://flink-savepoints/savepoint-abc123 --allowNonRestoredState app.jar
```

세이브포인트 포맷은 두 가지다. **canonical** 은 백엔드 독립 포맷이라 HashMap ↔ RocksDB 전환이 가능하지만 느리고, **native** 는 백엔드 고유 포맷이라 빠르지만 같은 백엔드로만 복원된다. 상태 백엔드를 바꾸는 마이그레이션이라면 canonical 로 뽑아야 한다.

`--allowNonRestoredState` 는 편리한 만큼 위험하다. UID 오타 하나로 상태가 조용히 버려질 수 있으므로, 모든 스테이트풀 연산자에 `uid()` 를 명시하고 이 플래그는 의도적 제거가 있을 때만 쓴다.

```java
stream.keyBy(Event::userId)
      .process(new SessionFunction())
      .uid("session-aggregator-v2")   // 절대 자동 생성에 맡기지 않는다
      .name("Session Aggregator");
```

## 8. 정리

Flink 의 정확히 한 번은 세 겹이다. 첫째, ABS 가 분산 상태의 일관된 단면을 만든다. 둘째, 상태 백엔드가 그 단면을 감당 가능한 비용으로 저장한다. 셋째, 2단계 커밋 싱크가 외부 시스템의 가시성을 체크포인트 경계에 맞춘다. 셋 중 하나만 빠져도 보장은 성립하지 않는다.

그리고 모든 선택이 지연과 비용의 교환이다. 체크포인트 간격을 줄이면 복구 시 재처리량과 종단 지연이 줄지만 오버헤드가 늘고, 비정렬로 바꾸면 백프레셔 내성이 생기지만 상태가 커지며, RocksDB 로 옮기면 상태가 무한해지지만 단건 지연이 몇 배가 된다. 잡의 SLA 를 숫자로 정한 다음 이 표를 역산하는 순서가 맞고, 그 반대는 거의 실패한다.

## 참고

- Apache Flink 공식 문서 — Stateful Stream Processing, Checkpointing
- Apache Flink 공식 문서 — Unaligned Checkpoints, State Backends, Savepoints
- Carbone et al., "Lightweight Asynchronous Snapshots for Distributed Dataflows" (2015)
- Chandy & Lamport, "Distributed Snapshots: Determining Global States of Distributed Systems" (1985)
- Apache Kafka 공식 문서 — Transactions, `transaction.max.timeout.ms`, `isolation.level`
- Apache Flink 공식 문서 — Kafka Connector, Delivery Guarantees
