Notion 원본: https://www.notion.so/35d5a06fd6d381809c37d4b69f833db4

# Spring AI ChatClient Function Calling과 Tool Binding LLM Integration

> 2026-05-11 신규 주제 · 확장 대상: Spring (Spring AI 1.0)

## 학습 목표

- Spring AI 1.0 의 `ChatClient` fluent API 가 prompt → tool selection → tool invocation → response 흐름을 어떻게 enclose 하는지 코드 단위로 본다
- `@Tool` annotation 으로 일반 Spring bean 의 메서드를 LLM tool 로 노출하는 메커니즘과, 그 과정에서 `MethodToolCallback`, `JsonSchema`, `ToolContext` 가 맡는 역할
- OpenAI / Anthropic / Bedrock 등 model provider 별로 function calling 스펙이 다른데 Spring AI 가 이를 어떻게 통합 추상화하고, multi-step tool loop 와 streaming 호출을 어떻게 처리하는지
- Tool 호출 안정성 — timeout, retry, idempotency, security(`@PreAuthorize`), observability(Micrometer trace span) 를 production 운영 기준에서 정리한다

## 1. ChatClient — 1.0 의 1차 진입점

Spring AI 0.8 까지는 `ChatModel` 을 직접 호출했지만, 1.0 GA(2025-05 릴리즈)에서 *권장* 진입점이 `ChatClient` 로 바뀌었다. fluent builder + 자동 retry + observation 통합이 묶여 있다.

```java
@Configuration
public class AiConfig {

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder) {
		return builder
				.defaultSystem("당신은 사내 운영 도구 어시스턴트입니다.")
				.defaultOptions(OpenAiChatOptions.builder()
						.model("gpt-4.1-mini")
						.temperature(0.2)
						.build())
				.build();
	}
}
```

이제 호출은 다음과 같다.

```java
String reply = chatClient.prompt()
		.user("강남 지점의 지난주 매출 합계를 알려줘")
		.call()
		.content();
```

`.stream()` 으로 바꾸면 `Flux<String>` 이 떨어져 SSE 응답으로 그대로 흘려보낼 수 있다.

## 2. `@Tool` — Spring bean 메서드를 함수 노출

`@Tool` 은 0.8 의 `@FunctionCallback` 을 대체한다. 메서드에 붙이고, `description` 으로 LLM 에게 의도를 알려 준다. 매개변수 타입에서 JSON Schema 가 자동 생성된다.

```java
@Component
public class StoreSalesTools {

	private final StoreSalesRepository repo;

	public StoreSalesTools(StoreSalesRepository repo) {
		this.repo = repo;
	}

	@Tool(description = "특정 지점의 일별 매출 합계를 조회한다")
	public DailySalesView getDailySales(
			@ToolParam(description = "지점 코드 (예: GN_001)") String storeCode,
			@ToolParam(description = "조회 시작일 (YYYY-MM-DD)") LocalDate from,
			@ToolParam(description = "조회 종료일 (YYYY-MM-DD)") LocalDate to
	) {
		return repo.aggregateDaily(storeCode, from, to);
	}
}
```

호출 시점에 tool 을 명시적으로 연결한다.

```java
String reply = chatClient.prompt()
		.user("강남(GN_001)의 지난주 매출 합계를 알려줘")
		.tools(storeSalesTools)
		.call()
		.content();
```

## 3. JSON Schema 자동 생성 — 내부 동작

`@Tool` 메서드를 발견하면 Spring AI 는 `MethodToolCallback` 인스턴스를 만든다. 이 안에서 `JsonSchemaGenerator` 가 시그니처와 `@ToolParam` 메타데이터를 합쳐 OpenAPI 3 호환 JSON Schema 를 만든다.

```json
{
  "name": "getDailySales",
  "description": "특정 지점의 일별 매출 합계를 조회한다",
  "parameters": {
    "type": "object",
    "properties": {
      "storeCode": { "type": "string" },
      "from": { "type": "string", "format": "date" },
      "to":   { "type": "string", "format": "date" }
    },
    "required": ["storeCode", "from", "to"]
  }
}
```

LocalDate 가 `"type":"string","format":"date"` 로 매핑되는 게 핵심이다. Jackson 의 `JsonFormatVisitor` 가 사용되며, custom 직렬화 형식이 있으면 그에 맞춰 schema 도 바뀐다.

## 4. Model provider 별 차이의 추상화

OpenAI 는 tool 결과를 다시 `role:"tool"` 로 메시지에 넣고 다시 호출하는 *multi-step* 형태고, Anthropic 은 `tool_use` 블록과 `tool_result` 블록을 명시적으로 페어링한다. Bedrock 의 Claude / Titan 은 또 약간씩 다르다. Spring AI 는 이걸 `ToolCallingManager` 한 군데에서 추상화한다.

`ToolCallingManager.executeToolCalls` 가 다음 루프를 수행한다.

1. provider 가 응답에 `tool_calls` 를 포함했는가?
2. 포함했다면 `MethodToolCallback.apply` 로 실제 메서드 호출
3. 결과를 메시지 배열에 추가
4. 다시 provider 에 호출

```java
.defaultOptions(OpenAiChatOptions.builder()
		.toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO)
		.maxToolCalls(5)
		.build())
```

## 5. Streaming + tool 호출 동시 사용

streaming 모드에서 tool 호출이 발생하면, Spring AI 는 *내부적으로* 한 번 non-streaming 으로 fall back 해 tool 결과를 받은 됆, 그 결과를 다시 streaming 호출로 보낸다. 클라이언트 입장엔 token by token 으로 들어오던 응답이 잠시 멈고, tool 결과 처리 후 후속 토큰이 나온다.

UX 관점에서 이 *멈는 시간* 을 가리려면 SSE 에 keep-alive ping 또는 "Looking up sales data..." 같은 placeholder 를 사용자 측에서 미리 보여 주는 게 좋다.

## 6. ToolContext — 호출별 상태 전달

LLM 이 tool 을 부를 때 *사용자 컨텍스트* (예: 인증된 user-id, tenant-id)를 안전하게 넣어야 한다. tool 의 매개변수로 노출하면 LLM 이 임의로 바꿀 위험이 있으므로 *out-of-band* 로 전달한다.

```java
@Tool(description = "현재 로그인 사용자의 미결 결재 목록을 가져온다")
public List<Approval> myApprovals(ToolContext ctx) {
	String userId = (String) ctx.getContext().get("userId");
	return approvalService.findOpenByOwner(userId);
}
```

호출자는 다음과 같이 컨텍스트를 채워 준다.

```java
chatClient.prompt()
		.user("내 결재 좀 정리해 줘")
		.tools(approvalTools)
		.toolContext(Map.of("userId", SecurityContextHolder.getContext().getAuthentication().getName()))
		.call()
		.content();
```

이 패턴이 정착되면 *LLM 이 tool 의 보안 컨텍스트를 위조* 하는 공격면이 거의 사라진다. tool 메서드는 `@PreAuthorize("hasRole('USER')")` 도 같이 붙여 *추가 방어* 가 가능하다.

## 7. 안정성 — timeout, retry, idempotency

tool 메서드는 결국 외부 시스템(DB, REST API)을 부른다. LLM 루프 안에서 tool 호출이 30초씩 걸리면 사용자 체감이 폭망한다. Spring AI 는 `RetryTemplate` 을 ChatModel 레벨에서 묶어 두지만 tool 메서드 *내부* 의 timeout 은 직접 관리해야 한다.

```java
@Tool(description = "외부 ERP 의 재고 조회")
@Retryable(maxAttempts = 2, backoff = @Backoff(delay = 200))
@TimeLimiter(name = "erpStock", fallbackMethod = "fallbackStock")
public CompletionStage<StockView> getStock(@ToolParam String sku) {
	return CompletableFuture.supplyAsync(() -> erpClient.getStock(sku),
			Executors.newVirtualThreadPerTaskExecutor());
}

public CompletionStage<StockView> fallbackStock(String sku, Throwable t) {
	return CompletableFuture.completedFuture(StockView.unknown(sku));
}
```

idempotency 는 *write* tool 에서 중요하다. 같은 tool 호출이 LLM 루프 안에서 두 번 일어날 수 있다(LLM 의 자체 retry). DB 쓰기 tool 에는 idempotency key 를 매개변수로 받고, repo 단에서 *중복 키 무시* 처리.

## 8. Observability — Micrometer 통합

Spring AI 1.0 은 Micrometer `Observation` 을 정식 지원한다. `ChatClientObservationConvention` 으로 다음 메트릭이 자동 발행된다.

- `spring.ai.chat.client` — duration, success/failure
- `spring.ai.chat.model` — token usage (prompt/completion), model 이름
- `spring.ai.tool.call` — tool 별 duration, success/failure, tool 이름

Grafana Tempo / Jaeger 에서 trace 가 다음 계층으로 보인다.

```
[User Request]
└── [ChatClient.prompt]
    ├── [ChatModel.call #1]
    │   └── [tool.getDailySales]
    │       └── [JDBC SELECT ...]
    └── [ChatModel.call #2]
```

production 에서 *어떤 tool 이 가장 비싼지*, *몇 번씩 도는 평균 multi-step depth* 가 한눈에 보인다.

## 9. Function Calling 의 한계 + Tool Streaming

현재 (Spring AI 1.0) function calling 의 알려진 한계는 다음과 같다.

- **JSON 응답 형식 강제 모드와 동시 사용 어려움**: OpenAI `response_format: { type: "json_schema" }` 는 tool calling 과 *상호 배타적* 인 케이스가 많다. Bedrock Claude 는 둘 다 가능.
- **Parallel tool calling 의 idempotency 책임**: 한 응답에서 tool 을 여러 개 동시 호출 시 race condition 가능. tool 자체가 reentrant 해야 한다.
- **Tool description 길이가 token 비용**: tool 5개 노출 시 system prompt 길이가 폭증한다. *세션 단위로 필요한 tool 만 동적으로 노출* 하는 패턴이 안전하다.

## 참고

- Spring AI Reference — https://docs.spring.io/spring-ai/reference/
- Spring AI GA Release Notes (1.0) — https://spring.io/blog/2025/05/20/spring-ai-1-0-ga
- OpenAI Function Calling — https://platform.openai.com/docs/guides/function-calling
- Anthropic Tool Use — https://docs.anthropic.com/en/docs/tool-use
- Micrometer Observation — https://micrometer.io/docs/observation
- Resilience4j Reference — https://resilience4j.readme.io/
