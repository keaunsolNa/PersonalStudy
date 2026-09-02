Notion 원본: https://www.notion.so/3cf5a06fd6d3819a82b6cbba4c79e721

# Temporal 워크플로 결정론 실행과 이벤트 히스토리 리플레이 및 버저닝

> 2026-09-02 신규 주제 · 확장 대상: Saga 패턴과 Transactional Outbox, Spring Batch 분산 처리

## 학습 목표

- Temporal 의 이벤트 소싱 기반 실행 모델과 Command/Event 왕복 구조를 추적한다
- 결정론(determinism) 제약이 코드 레벨에서 무엇을 금지하는지 구분한다
- `Workflow.getVersion` 과 Worker Versioning 으로 배포 중 리플레이 실패를 회피한다
- Replay 테스트를 CI 에 넣어 비호환 변경을 배포 전에 잡아낸다

## 1. Saga 를 코드로 되돌리는 실행 모델

Outbox + Saga 패턴은 분산 트랜잭션을 "각 서비스가 로컬 트랜잭션을 커밋하고 이벤트를 흘리면, 오케스트레이터가 상태 머신을 돌린다" 로 환원한다. 문제는 그 상태 머신이 결국 DB 테이블의 `status` 컬럼과 수십 개의 `if` 로 흩어진다는 점이다. 보상 트랜잭션 하나를 추가하려면 상태 enum, 전이 테이블, 재시도 스케줄러를 모두 손대야 한다.

Temporal 의 접근은 반대다. 상태 머신을 코드의 제어 흐름 그 자체로 표현하고, 그 제어 흐름의 *진행 상태* 를 프레임워크가 영속화한다. 아래 코드는 결제 실패 시 재고를 되돌리는 Saga 인데, 상태 컬럼이 하나도 없다.

```java
@WorkflowInterface
public interface OrderWorkflow {

	@WorkflowMethod
	OrderResult placeOrder(OrderRequest request);

	@SignalMethod
	void cancel(String reason);
}

public class OrderWorkflowImpl implements OrderWorkflow {

	private static final RetryOptions RETRY = RetryOptions.newBuilder()
			.setInitialInterval(Duration.ofSeconds(1))
			.setBackoffCoefficient(2.0)
			.setMaximumAttempts(5)
			.setDoNotRetry(InsufficientFundsException.class.getName())
			.build();

	private final InventoryActivities inventory = Workflow.newActivityStub(
			InventoryActivities.class,
			ActivityOptions.newBuilder()
					.setStartToCloseTimeout(Duration.ofSeconds(30))
					.setRetryOptions(RETRY)
					.build());

	private final PaymentActivities payment = Workflow.newActivityStub(
			PaymentActivities.class,
			ActivityOptions.newBuilder()
					.setStartToCloseTimeout(Duration.ofSeconds(30))
					.setRetryOptions(RETRY)
					.build());

	private boolean cancelled = false;

	@Override
	public OrderResult placeOrder(OrderRequest request) {
		String reservationId = inventory.reserve(request.getSkuId(), request.getQuantity());
		Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
		saga.addCompensation(inventory::release, reservationId);

		try {
			Workflow.await(Duration.ofMinutes(15), () -> cancelled);
			if (cancelled) {
				throw ApplicationFailure.newFailure("cancelled by user", "CANCELLED");
			}
			String receiptId = payment.charge(request.getUserId(), request.getAmount());
			saga.addCompensation(payment::refund, receiptId);
			return new OrderResult(reservationId, receiptId);
		}
		catch (ActivityFailure | ApplicationFailure e) {
			saga.compensate();
			throw e;
		}
	}

	@Override
	public void cancel(String reason) {
		this.cancelled = true;
	}
}
```

`Workflow.await(Duration.ofMinutes(15), ...)` 는 워커 프로세스를 15분간 붙잡지 않는다. 워크플로 스레드가 그 지점에서 중단되고, 서버에 타이머가 등록되고, 워커 메모리에서 워크플로가 통째로 제거된다. 15분 뒤 혹은 시그널이 도착하는 순간 아무 워커나 그 워크플로를 *처음부터 다시 실행* 해서 같은 지점까지 도달시킨 뒤 이어간다. 이 "처음부터 다시 실행" 이 리플레이이고, Temporal 의 모든 제약은 여기서 파생된다.

## 2. Command / Event 왕복과 이벤트 히스토리

워커와 Temporal 서버는 gRPC 롱폴링으로 워크플로 태스크를 주고받는다. 한 사이클은 다음과 같다.

1. 워커가 `PollWorkflowTaskQueue` 로 태스크를 받는다. 응답에는 해당 워크플로 실행의 이벤트 히스토리(또는 마지막 체크포인트 이후 증분)가 들어 있다.
2. 워커가 히스토리를 재생하며 워크플로 코드를 실행한다. 이미 히스토리에 결과가 있는 액티비티 호출은 실제로 호출하지 않고 기록된 결과를 즉시 반환한다.
3. 히스토리 끝에 도달해 코드가 새로운 부수효과를 요구하면 워커는 그것을 *Command* 로 모은다: `ScheduleActivityTask`, `StartTimer`, `CompleteWorkflowExecution` 등.
4. `RespondWorkflowTaskCompleted` 로 Command 배열을 서버에 보낸다. 서버는 이를 이벤트로 변환해 히스토리에 append 하고, 실제 액티비티 태스크를 큐에 넣는다.

히스토리 이벤트는 크게 세 부류다.

| 부류 | 예시 이벤트 | 생성 주체 |
|---|---|---|
| 외부 입력 | `WorkflowExecutionStarted`, `WorkflowExecutionSignaled`, `ActivityTaskCompleted`, `TimerFired` | 서버/외부 |
| 워커 결정 | `ActivityTaskScheduled`, `TimerStarted`, `MarkerRecorded` | 워커 Command |
| 태스크 경계 | `WorkflowTaskScheduled`, `WorkflowTaskStarted`, `WorkflowTaskCompleted` | 서버 |

리플레이 시 워커는 자신이 만들어낸 Command 시퀀스가 히스토리의 "워커 결정" 이벤트 시퀀스와 순서·타입·식별자까지 일치하는지 검증한다. 불일치하면 `NonDeterministicException` 이 발생하고 워크플로 태스크가 실패한다. 중요한 점은 이때 **워크플로가 실패하지 않는다**는 것이다. 워크플로 태스크만 실패하고 무한 재시도되므로, 잘못된 코드를 롤백하면 워크플로는 아무 데이터 손실 없이 그대로 진행된다. 이 "태스크 실패 ≠ 워크플로 실패" 분리가 운영상 가장 큰 안전망이다.

## 3. 결정론이 실제로 금지하는 것

워크플로 코드는 같은 히스토리를 입력받으면 항상 같은 Command 를 뱉어야 한다. 따라서 아래는 전부 금지다.

```java
// 금지 — 리플레이할 때마다 다른 값
Instant now = Instant.now();
UUID id = UUID.randomUUID();
int pick = ThreadLocalRandom.current().nextInt(3);
new Thread(task).start();
CompletableFuture.supplyAsync(task);
jdbcTemplate.query(...);         // I/O 는 전부 액티비티로
Collections.shuffle(list);
```

대체 API 는 다음과 같다.

```java
Instant now = Instant.ofEpochMilli(Workflow.currentTimeMillis());
UUID id = Workflow.randomUUID();
int pick = Workflow.newRandom().nextInt(3);
Promise<String> p = Async.function(activities::charge, userId, amount);
Workflow.sleep(Duration.ofHours(1));
```

`Workflow.currentTimeMillis()` 는 현재 처리 중인 `WorkflowTaskStarted` 이벤트의 서버 타임스탬프를 돌려준다. 리플레이 시에도 히스토리에 박힌 값이 그대로 나오므로 결정적이다. `Workflow.randomUUID()` 는 워크플로 실행 ID 와 이벤트 시퀀스로 시드된 결정론적 난수를 쓴다.

`HashMap` 순회 순서 같은 미묘한 비결정성도 문제가 된다. JVM 의 `HashMap` 은 같은 삽입 순서면 같은 순회 순서를 주지만, 키가 `Object::hashCode` 기본 구현(아이덴티티 해시)에 의존하는 타입이면 JVM 실행마다 달라진다. 순회 순서가 액티비티 스케줄 순서에 영향을 준다면 `LinkedHashMap` 이나 `TreeMap` 으로 고정해야 한다.

컬렉션 병렬 스트림도 위험하다. `list.parallelStream().map(activities::process)` 는 ForkJoinPool 에서 실행되어 워크플로 스레드 컨텍스트를 벗어나고, 완료 순서도 비결정적이다. 반드시 `Async.function` 과 `Promise.allOf` 로 바꿔다.

```java
List<Promise<String>> promises = items.stream()
		.map(item -> Async.function(activities::process, item))
		.toList();
Promise.allOf(promises).get();
List<String> results = promises.stream().map(Promise::get).toList();
```

## 4. 사이드이펙트와 마커 이벤트

결정론을 유지하면서 비결정적 값을 한 번만 계산하고 싶을 때 `Workflow.sideEffect` 를 쓴다. 첫 실행 결과가 `MarkerRecorded` 이벤트로 히스토리에 박히고, 리플레이 시에는 마커에서 값을 읽는다.

```java
String correlationId = Workflow.sideEffect(String.class, () -> ExternalIdGenerator.next());
```

주의점이 두 가지다. 첫째, `sideEffect` 는 실패 시 재시도되지 않는다. 예외가 나면 워크플로 태스크가 실패하고 전체가 다시 리플레이된다. 외부 호출은 여기에 넣지 말고 액티비티로 뺀다. 둘째, `sideEffect` 는 리플레이 시 절대 실행되지 않으므로 그 안에서 로깅이나 메트릭을 남기면 최초 1회만 기록된다 — 이건 오히려 장점으로 쓸 수 있다.

조건부 로직에 쓰는 `mutableSideEffect` 는 다르다. 매 리플레이가 아닌 매 *신규 실행* 마다 함수를 호출하고, 이전 값과 다를 때만 새 마커를 기록한다. 설정값 폴링처럼 값이 자주 안 바뀌는 경우 히스토리 크기를 아낀다.

로깅은 `Workflow.getLogger` 를 쓴다. 이 로거는 `Workflow.isReplaying()` 을 내부에서 확인해 리플레이 중에는 출력을 억제하므로, 워커 재시작 때마다 같은 로그가 수천 줄 쏟아지는 사태를 막는다.

## 5. 히스토리 크기 한계와 Continue-As-New

이벤트 히스토리는 무한히 자랄 수 없다. Temporal 서버는 기본적으로 **50,000 이벤트** 또는 **50MB** 에서 워크플로를 강제 종료하며, 10,000 이벤트 / 10MB 를 넘으면 히스토리에 경고 이벤트를 남긴다. 무한 루프형 워크플로(구독 갱신, 주기적 배치)는 반드시 Continue-As-New 로 히스토리를 잘라야 한다.

```java
@Override
public void runSubscription(SubscriptionState state) {
	for (int i = 0; i < 12; i++) {
		Workflow.sleep(Duration.ofDays(30));
		activities.charge(state.getUserId(), state.getMonthlyFee());
		state = state.withCycle(state.getCycle() + 1);
	}
	Workflow.continueAsNew(state);
}
```

`continueAsNew` 는 현재 실행을 `ContinuedAsNew` 상태로 닫고, 같은 Workflow ID 로 새 Run ID 의 실행을 시작한다. 새 실행의 히스토리는 `WorkflowExecutionStarted` 하나로 시작하므로 리플레이 비용이 초기화된다. 루프 횟수는 "한 사이클 히스토리 이벤트 수 × N < 10,000" 을 기준으로 잡는다. 액티비티 1회는 보통 `ActivityTaskScheduled` + `ActivityTaskStarted` + `ActivityTaskCompleted` + 태스크 경계 3개 ≈ 6 이벤트를 만든다.

## 6. 버저닝 — 배포 중 리플레이 깨짐 방지

실행 중인 워크플로가 수천 개 있는 상태에서 워크플로 코드를 바꾸면, 구 실행의 히스토리를 신 코드로 리플레이하게 되어 결정론이 깨진다. 안전한 변경과 위험한 변경을 구분해야 한다.

| 변경 | 안전 여부 | 이유 |
|---|---|---|
| 액티비티 추가/제거/순서 변경 | 위험 | Command 시퀀스 불일치 |
| `Workflow.sleep` 기간 변경 | 위험 | `TimerStarted` 파라미터 불일치 |
| 액티비티 재시도 정책 변경 | 위험 | `ScheduleActivityTask` 속성 불일치 |
| 액티비티 구현체 내부 로직 변경 | 안전 | 액티비티는 리플레이 대상이 아님 |
| 워크플로 내 순수 계산 로직 변경 | 대체로 안전 | Command 를 만들지 않으면 무해 |
| 로컬 변수/로깅 추가 | 안전 | 히스토리에 남지 않음 |
| 시그널/쿼리 메서드 추가 | 안전 | 신규 진입점 |

위험한 변경에는 `Workflow.getVersion` 을 쓴다.

```java
int v = Workflow.getVersion("add-fraud-check", Workflow.DEFAULT_VERSION, 1);
if (v >= 1) {
	fraud.screen(request.getUserId(), request.getAmount());
}
String receiptId = payment.charge(request.getUserId(), request.getAmount());
```

첫 호출 시 워커는 `MarkerRecorded` 로 `add-fraud-check = 1` 을 히스토리에 남긴다. 이 마커가 없는 구 실행에서는 `DEFAULT_VERSION`(-1)이 반환되어 `fraud.screen` 을 건너뛴다. 마커가 있는 신 실행에서는 1 이 반환된다. 즉 같은 코드가 두 히스토리 모두에서 결정적으로 동작한다.

구 실행이 전부 끝난 뒤 정리할 때는 두 단계로 나눈다. 먼저 `Workflow.getVersion("add-fraud-check", 1, 1)` 로 최소 버전을 올려 배포하고(이제 -1 이 반환되면 예외), 그다음 릴리스에서 호출 자체를 제거한다. 한 번에 제거하면 아직 남은 구 실행이 깨진다.

대안으로 Worker Versioning(Build ID 기반)이 있다. 워커에 Build ID 를 부여하고 태스크 큐에 버전 셋을 등록하면, 서버가 각 워크플로 실행을 시작 시점의 Build ID 를 가진 워커로만 라우팅한다. `getVersion` 분기 없이 구 코드를 그대로 남겨두고 신규 실행만 새 코드로 보내는 방식이라 코드가 깔끔해지지만, 구 워커 프로세스를 실행이 다 끝날 때까지 계속 띄워둬야 하고 배포 파이프라인이 복잡해진다. 실행 수명이 짧으면(수 분~수 시간) Worker Versioning, 수개월짜리 장수 워크플로가 섞여 있으면 `getVersion` 이 현실적이다.

## 7. Replay 테스트로 CI 에서 잡기

버저닝 실수를 배포 후에 발견하면 워크플로 태스크가 무한 재시도되며 알람이 울린다. 프로덕션 히스토리를 내려받아 CI 에서 신 코드로 리플레이해보면 배포 전에 잡을 수 있다.

```bash
temporal workflow show --workflow-id order-8842 --output json > src/test/resources/history/order-8842.json
```

```java
class OrderWorkflowReplayTest {

	@Test
	void replaysProductionHistoriesWithoutNonDeterminism() throws Exception {
		WorkflowReplayer.replayWorkflowExecutionFromResource(
				"history/order-8842.json", OrderWorkflowImpl.class);
	}

	@Test
	void compensatesInventoryWhenPaymentFails() {
		TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
		Worker worker = env.newWorker("orders");
		worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

		InventoryActivities inventory = mock(InventoryActivities.class);
		PaymentActivities payment = mock(PaymentActivities.class);
		when(inventory.reserve("SKU-1", 2)).thenReturn("RSV-1");
		when(payment.charge(anyString(), any()))
				.thenThrow(ApplicationFailure.newNonRetryableFailure("declined", "PAYMENT"));
		worker.registerActivitiesImplementations(inventory, payment);
		env.start();

		OrderWorkflow wf = env.getWorkflowClient().newWorkflowStub(
				OrderWorkflow.class,
				WorkflowOptions.newBuilder().setTaskQueue("orders").build());

		WorkflowClient.start(wf::placeOrder, new OrderRequest("u-1", "SKU-1", 2, BigDecimal.TEN));
		env.sleep(Duration.ofMinutes(16));   // 가상 시계 — 실제로는 즉시

		assertThatThrownBy(() -> WorkflowStub.fromTyped(wf).getResult(OrderResult.class))
				.isInstanceOf(WorkflowFailedException.class);
		verify(inventory).release("RSV-1");
		verify(payment, never()).refund(anyString());
		env.close();
	}
}
```

`TestWorkflowEnvironment` 의 가상 시계가 핵심이다. `Workflow.sleep(Duration.ofDays(30))` 이 걸린 워크플로도 테스트에서는 밀리초 단위로 지나간다. 15분 타임아웃 분기를 실제로 15분 기다려 검증할 필요가 없다.

CI 파이프라인에서는 최근 N일치 히스토리 샘플(정상 완료 / 보상 실행 / 장기 대기 각각)을 저장소에 커밋해두고 매 빌드마다 리플레이한다. 히스토리 JSON 은 수백 KB 수준이라 부담이 크지 않다.

## 8. 운영 관점 트레이드오프

Temporal 은 공짜가 아니다. 도입 전에 따져야 할 비용은 다음과 같다.

- **인프라**: Temporal 서버(Frontend/History/Matching/Worker 4개 롤)와 Cassandra 또는 PostgreSQL/MySQL 퍼시스턴스, 가시성용 Elasticsearch 가 필요하다. 셀프호스팅 시 History 샤드 수(`numHistoryShards`)는 클러스터 생성 후 변경할 수 없으므로 처음부터 여유 있게(수천 단위) 잡는다.
- **레이턴시**: 액티비티 1회마다 gRPC 왕복 + 히스토리 append 가 발생한다. 로컬 메서드 호출 대비 수 ms~수십 ms 오버헤드가 붙으므로, 밀리초 단위 응답이 필요한 동기 API 경로에 그대로 얹으면 안 된다. 짧고 순수한 계산은 `LocalActivity` 로 돌려 히스토리 쓰기를 줄일 수 있다(단 LocalActivity 는 워커 크래시 시 재시도 보장이 약하다).
- **페이로드 크기**: 액티비티 인자/결과는 기본 2MB(gRPC 메시지 4MB) 제한이 있다. 큰 데이터는 S3 에 올리고 키만 넘기거나, Data Converter 에 코덱을 붙여 압축/암호화한다.
- **디버깅 곡선**: 스택트레이스가 리플레이 컨텍스트에서 나오므로 익숙해지기 전에는 읽기 어렵다. `temporal workflow show` 로 히스토리를 직접 읽는 습관이 필수다.

Outbox + 커스텀 상태 머신 대비 이득은 명확하다. 재시도·타임아웃·보상·타이머·시그널이 프레임워크 보장이 되고, 상태 조회가 `temporal workflow describe` 한 줄이며, 15분짜리 대기가 스케줄러 테이블 없이 표현된다. 반대로 워크플로가 5개 미만이고 각각 3단계 이하라면 Outbox + `@Scheduled` 조합이 운영 부담이 훨씬 적다. 손익분기는 대략 "장기 실행(분 단위 이상) 상태 머신이 10개 이상이고, 각각 보상 경로가 있는가" 다.

## 9. 마이그레이션 시 체크리스트

기존 Saga 오케스트레이터를 옮길 때 순서는 다음을 권한다.

1. 액티비티 인터페이스부터 정의한다. 기존 서비스 메서드를 거의 그대로 옮길 수 있어야 한다. 여기서 멱등성을 점검한다 — 액티비티는 최소 1회(at-least-once) 실행이므로 중복 호출에 안전해야 한다. 결제처럼 위험한 호출은 `ActivityOptions` 에서 재시도를 끄고 워크플로 레벨에서 명시적으로 처리하거나, 멱등키를 `Workflow.randomUUID()` 로 만들어 넘긴다.
2. 워크플로를 새 코드로 작성하되 초기에는 신규 주문만 라우팅한다. 구 오케스트레이터는 기존 건이 소진될 때까지 둔다.
3. Workflow ID 를 비즈니스 키(`order-{orderId}`)로 잡고 `WorkflowIdReusePolicy.REJECT_DUPLICATE` 를 걸어 중복 실행을 서버 레벨에서 막는다. 이것만으로 애플리케이션의 중복 주문 방어 로직 상당 부분이 대체된다.
4. Search Attribute 를 등록해(`OrderStatus`, `CustomerTier` 등) 운영자가 UI 에서 필터링할 수 있게 한다. 상태 조회용 별도 테이블을 만들지 않아도 된다.
5. Replay 테스트를 첫 배포 전에 CI 에 넣는다. 나중에 넣으면 이미 히스토리가 갈라진 뒤다.

## 참고

- Temporal Documentation — Workflow determinism and versioning (https://docs.temporal.io/workflow-definition)
- Temporal Java SDK Developer's Guide — Versioning (https://docs.temporal.io/develop/java/versioning)
- Temporal Documentation — Event History and Continue-As-New (https://docs.temporal.io/workflow-execution/event)
- Gregor Hohpe, *Enterprise Integration Patterns* — Process Manager
- Martin Kleppmann, *Designing Data-Intensive Applications*, Ch. 11 Stream Processing
