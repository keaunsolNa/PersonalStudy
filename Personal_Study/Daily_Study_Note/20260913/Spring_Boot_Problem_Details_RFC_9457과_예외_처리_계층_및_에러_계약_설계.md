Notion 원본: https://app.notion.com/p/3da5a06fd6d381cfbad8eb166986667a?pvs=204

# Spring Boot Problem Details RFC 9457과 예외 처리 계층 및 에러 계약 설계

> 2026-09-13 신규 주제 · 확장 대상: Spring / REST_API

## 학습 목표

- RFC 9457 의 필드 의미와 확장 규칙을 명세 수준에서 파악한다
- Spring 의 `ProblemDetail`, `ErrorResponse`, `ErrorResponseException` 계층을 구분한다
- 표준 예외와 도메인 예외의 변환 경로를 이중화 없이 설계한다
- 필터 계층·비동기 경계에서 문제 응답이 새는 지점을 막는다

## 1. 에러 응답을 표준화해야 하는 이유

REST API 에서 에러 본문은 관례적으로 각자 만들어 왔다.

```json
{ "code": "USER_NOT_FOUND", "message": "사용자를 찾을 수 없습니다", "timestamp": 1757000000 }
```

이 형태에 기술적 결함은 없다. 문제는 **클라이언트가 서버마다 다른 파서를 가져야 한다**는 것이다. 게이트웨이, 서비스 메시, 서드파티 SDK, 브라우저 확장까지 에러를 읽어야 하는 주체가 늘어나면 비용이 곱해진다.

RFC 7807 (2016) 이 이 문제에 표준을 제시했고, RFC 9457 (2023) 이 이를 대체했다. 9457 은 7807 의 필드를 그대로 유지하면서 몇 가지를 명확히 했다 — 특히 `type` URI 의 역참조 여부와 확장 멤버 등록에 관한 부분이다. 실무적으로 7807 을 지원하는 구현은 9457 과 와이어 호환된다.

미디어 타입은 `application/problem+json` 이다. 이 헤더가 핵심이다. 클라이언트는 상태 코드와 이 미디어 타입만 보고 본문 구조를 확신할 수 있다.

## 2. 필드 규격

기본 멤버는 다섯이며 전부 선택적이다.

| 필드 | 타입 | 의미 |
|---|---|---|
| `type` | URI | 문제 유형 식별자. 생략 시 `about:blank` |
| `title` | string | 유형에 대한 사람이 읽는 짧은 요약. 발생마다 바뀌면 안 됨 |
| `status` | number | HTTP 상태 코드 사본 |
| `detail` | string | **이번 발생**에 대한 설명 |
| `instance` | URI | 이번 발생을 가리키는 식별자 |

혼동이 잦은 두 쌍을 정리한다.

`type` 과 `instance` 의 관계는 클래스와 인스턴스다. `type` 은 "잔액 부족"이라는 **유형**을, `instance` 는 "2026-09-13 14:02 에 계좌 A 에서 일어난 그 건"을 가리킨다.

`title` 과 `detail` 의 관계도 마찬가지다. `title` 은 `type` 이 같으면 항상 같아야 한다. 로컬라이제이션 때문에 언어가 달라지는 것은 허용되지만, 값에 따라 달라지면 안 된다. 반대로 `detail` 은 구체적이어야 한다.

```json
{
  "type": "https://api.example.com/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "잔액 12,000원으로 35,000원을 출금할 수 없습니다",
  "instance": "/accounts/12345/withdrawals/98765",
  "balance": 12000,
  "requested": 35000
}
```

`balance`, `requested` 가 **확장 멤버**다. 9457 은 확장 멤버를 최상위에 두는 것을 허용하며, 이름 충돌을 피하기 위해 `type` 별로 문서화하라고 요구한다. 중첩 객체(`"extensions": {...}`)에 넣는 것은 표준이 아니다 — 실제로 쓰는 구현도 있지만 상호운용성이 떨어진다.

`type` 이 `about:blank` 일 때는 `title` 이 HTTP 상태 코드의 표준 문구여야 한다. Spring 이 기본 동작으로 이 규칙을 지킨다.

`detail` 에 스택 트레이스나 SQL 을 넣지 않는다. 이 필드는 클라이언트에 노출되며, 내부 구조를 드러내는 순간 정보 노출 취약점이 된다.

## 3. Spring 의 세 계층

Spring Framework 6 / Boot 3 이 도입한 타입은 셋이고 역할이 다르다.

**`ProblemDetail`** — 본문 자체를 표현하는 데이터 홀더다. 프레임워크 의존이 없는 값 객체에 가깝다.

```java
ProblemDetail detail = ProblemDetail.forStatusAndDetail(
		HttpStatus.UNPROCESSABLE_ENTITY, "잔액이 부족합니다");
detail.setType(URI.create("https://api.example.com/problems/insufficient-funds"));
detail.setTitle("Insufficient funds");
detail.setProperty("balance", 12000L);
```

`setProperty` 가 확장 멤버를 넣는 통로다. 내부적으로 `Map<String, Object>` 에 담기고 Jackson 이 `@JsonAnyGetter` 로 평탄화해 직렬화한다.

**`ErrorResponse`** — "상태 코드 + 헤더 + ProblemDetail" 을 함께 들고 있는 인터페이스다. Spring MVC 내부의 표준 예외들이 이 인터페이스를 구현한다.

**`ErrorResponseException`** — `ErrorResponse` 를 구현한 기본 예외 클래스. 직접 던질 수도 있고 상속할 수도 있다.

활성화는 프로퍼티 한 줄이다.

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
  # WebFlux 라면
  webflux:
    problemdetails:
      enabled: true
```

이 값을 켜면 `ResponseEntityExceptionHandler` 가 등록되어 `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `NoResourceFoundException` 등 Spring 표준 예외가 자동으로 `application/problem+json` 으로 나간다.

중요한 제약: 이 자동 설정은 **Spring MVC 가 아는 예외에만** 적용된다. 도메인 예외는 직접 매핑해야 한다.

## 4. 도메인 예외 매핑 설계

여기서 설계 판단이 갈린다. 세 가지 방식이 있다.

**방식 A — 도메인 예외가 `ErrorResponseException` 을 상속**

```java
public class InsufficientFundsException extends ErrorResponseException {

	public InsufficientFundsException(long balance, long requested) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, asProblemDetail(balance, requested), null);
	}

	private static ProblemDetail asProblemDetail(long balance, long requested) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"잔액 %,d원으로 %,d원을 출금할 수 없습니다".formatted(balance, requested));
		detail.setType(URI.create("https://api.example.com/problems/insufficient-funds"));
		detail.setTitle("Insufficient funds");
		detail.setProperty("balance", balance);
		detail.setProperty("requested", requested);
		return detail;
	}
}
```

핸들러 코드가 아예 없어도 동작한다. 대신 **도메인 계층이 HTTP 를 안다**는 대가를 치른다. 같은 도메인을 gRPC 나 배치에서 재사용할 계획이 있으면 부담이 된다.

**방식 B — 순수 도메인 예외 + `@ControllerAdvice` 변환**

```java
public class InsufficientFundsException extends RuntimeException {

	private final long balance;
	private final long requested;

	public InsufficientFundsException(long balance, long requested) {
		super("insufficient funds: balance=%d requested=%d".formatted(balance, requested));
		this.balance = balance;
		this.requested = requested;
	}

	public long getBalance() {
		return balance;
	}

	public long getRequested() {
		return requested;
	}
}
```

```java
@RestControllerAdvice
public class DomainExceptionHandler {

	private static final String TYPE_BASE = "https://api.example.com/problems/";

	@ExceptionHandler(InsufficientFundsException.class)
	public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_ENTITY,
				"잔액 %,d원으로 %,d원을 출금할 수 없습니다"
						.formatted(ex.getBalance(), ex.getRequested()));
		detail.setType(URI.create(TYPE_BASE + "insufficient-funds"));
		detail.setTitle("Insufficient funds");
		detail.setProperty("balance", ex.getBalance());
		detail.setProperty("requested", ex.getRequested());
		return detail;
	}
}
```

핸들러가 `ProblemDetail` 을 그대로 반환하면 Spring 이 `status` 필드를 보고 응답 상태를 맞춘다. 도메인은 HTTP 를 모른다.

**방식 C — 에러 코드 열거형 + 단일 핸들러**

예외 종류가 수십 개가 되면 A 도 B 도 반복이 심하다. 매핑을 데이터로 뺀다.

```java
public enum ErrorCode {

	INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", "Insufficient funds"),
	ACCOUNT_FROZEN(HttpStatus.CONFLICT, "account-frozen", "Account frozen"),
	DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "daily-limit-exceeded", "Daily limit exceeded");

	private final HttpStatus status;
	private final String slug;
	private final String title;

	ErrorCode(HttpStatus status, String slug, String title) {
		this.status = status;
		this.slug = slug;
		this.title = title;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public URI toTypeUri() {
		return URI.create("https://api.example.com/problems/" + slug);
	}

	public String getTitle() {
		return title;
	}
}
```

```java
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Map<String, Object> properties;

	public BusinessException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
		super(detail);
		this.errorCode = errorCode;
		this.properties = properties == null ? Map.of() : Map.copyOf(properties);
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Map<String, Object> getProperties() {
		return properties;
	}
}
```

```java
@ExceptionHandler(BusinessException.class)
public ProblemDetail handleBusiness(BusinessException ex) {
	ErrorCode code = ex.getErrorCode();
	ProblemDetail detail = ProblemDetail.forStatusAndDetail(code.getStatus(), ex.getMessage());
	detail.setType(code.toTypeUri());
	detail.setTitle(code.getTitle());
	ex.getProperties().forEach(detail::setProperty);
	return detail;
}
```

트레이드오프는 명확하다. C 는 신규 에러 추가 비용이 열거형 한 줄로 줄지만, 예외 타입이 하나뿐이라 **컴파일러가 catch 를 구분해 주지 못한다**. 호출자가 특정 에러만 복구하려면 `errorCode` 를 런타임에 비교해야 한다. 복구 로직이 있는 에러는 B 로, 그냥 응답만 만들면 되는 에러는 C 로 나누는 혼합이 실용적이다.

## 5. 검증 에러의 표준화

`@Valid` 실패는 필드 단위 정보를 담아야 쓸모가 있다. Spring 기본 동작은 `detail` 에 요약 문구만 넣으므로 확장이 필요하다.

```java
@RestControllerAdvice
public class ValidationExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex,
			HttpHeaders headers,
			HttpStatusCode status,
			WebRequest request) {

		ProblemDetail detail = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "요청 본문 검증에 실패했습니다");
		detail.setType(URI.create("https://api.example.com/problems/validation-error"));
		detail.setTitle("Validation failed");

		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> Map.of(
						"field", fieldError.getField(),
						"reason", Objects.requireNonNullElse(fieldError.getDefaultMessage(), "invalid")))
				.toList();
		detail.setProperty("errors", errors);

		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body(detail);
	}
}
```

`ResponseEntityExceptionHandler` 를 상속하면 Spring 표준 예외 처리를 전부 물려받으면서 필요한 메서드만 덮어쓸 수 있다. 주의: 이 클래스를 상속한 `@ControllerAdvice` 가 있으면 `spring.mvc.problemdetails.enabled` 로 등록되는 기본 핸들러와 **중복 등록 충돌**이 난다. 상속해서 쓸 거면 프로퍼티를 켜지 않는다.

`fieldError.getDefaultMessage()` 를 그대로 노출할 때는 메시지 소스가 사용자 대상 문구인지 확인해야 한다. 기본값은 `jakarta.validation` 의 영문 템플릿이며, `messages.properties` 로 한글화하는 것이 보통이다.

## 6. 예외가 새는 지점 세 곳

`@ControllerAdvice` 는 **DispatcherServlet 안에서 발생한 예외**만 잡는다. 밖에서 터지면 컨테이너 기본 에러 페이지가 나가고, 그 순간 `application/problem+json` 계약이 깨진다.

**첫째, 서블릿 필터.** Spring Security 필터 체인은 DispatcherServlet 앞에 있다. 인증 실패(401)와 인가 실패(403)는 `@ControllerAdvice` 로 안 잡힌다.

```java
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		ProblemDetail detail = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNAUTHORIZED, "인증이 필요합니다");
		detail.setType(URI.create("https://api.example.com/problems/unauthenticated"));
		detail.setTitle("Unauthorized");
		detail.setInstance(URI.create(request.getRequestURI()));

		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), detail);
	}
}
```

`AccessDeniedHandler` 도 같은 방식으로 구현해 `HttpSecurity.exceptionHandling(...)` 에 등록한다.

**둘째, `/error` 폴백.** 필터에서 `response.sendError()` 가 호출되면 서블릿 컨테이너가 `/error` 로 포워딩하고 `BasicErrorController` 가 `application/json` 으로 Whitelabel 구조를 뱉는다. `ErrorAttributes` 를 교체해 이 경로도 문제 형식으로 통일한다.

```java
@Component
public class ProblemErrorAttributes extends DefaultErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
		Map<String, Object> defaults = super.getErrorAttributes(request, options);
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "about:blank");
		problem.put("title", defaults.get("error"));
		problem.put("status", defaults.get("status"));
		problem.put("detail", defaults.getOrDefault("message", ""));
		problem.put("instance", defaults.get("path"));
		return problem;
	}
}
```

**셋째, 비동기 경계.** `@Async` 메서드나 `CompletableFuture` 안에서 던진 예외는 호출 스레드로 전파되지 않는다. `AsyncUncaughtExceptionHandler` 를 등록하지 않으면 로그만 남고 사라진다. 컨트롤러가 `CompletableFuture` 를 반환하는 경우에는 예외가 `ExecutionException` 으로 감싸져 오므로, 핸들러에서 `getCause()` 를 풀어야 원래 도메인 예외를 볼 수 있다.

## 7. 관측성과의 연결

`instance` 필드에 추적 식별자를 넣으면 고객 문의와 로그를 잇는 비용이 급감한다.

```java
@ExceptionHandler(BusinessException.class)
public ProblemDetail handleBusiness(BusinessException ex, HttpServletRequest request) {
	ProblemDetail detail = toProblemDetail(ex);
	detail.setInstance(URI.create(request.getRequestURI()));

	Span span = Span.current();
	if (span.getSpanContext().isValid()) {
		detail.setProperty("traceId", span.getSpanContext().getTraceId());
	}
	return detail;
}
```

`traceId` 를 확장 멤버로 노출하는 것은 정보 노출인가? 트레이스 ID 자체는 랜덤 128비트이며 그것만으로 내부 구조를 알 수 없다. 다만 이 ID 로 조회 가능한 내부 대시보드가 외부에 열려 있으면 안 된다. 대부분의 조직에서 노출 편익이 위험보다 크다고 판단한다.

로깅 레벨 정책도 정해야 한다. 4xx 계열 도메인 예외는 **정상 흐름**이다. `error` 로 찍으면 알림이 울려 실제 장애를 가린다.

| 상태 | 로그 레벨 | 스택 트레이스 |
|---|---|---|
| 400·422 검증 실패 | debug | 미포함 |
| 401·403 | info | 미포함 |
| 404 | debug | 미포함 |
| 409 낙관적 잠금 충돌 | info | 미포함 |
| 5xx | error | 포함 |

## 8. 계약으로서의 `type` URI 관리

`type` URI 는 클라이언트가 분기 조건으로 쓰는 순간 **공개 API 의 일부**가 된다. 세 가지 규율이 필요하다.

URI 를 바꾸지 않는다. 문구(`title`, `detail`)는 자유롭게 고쳐도 되지만 `type` 은 고정이다. 바꿔야 한다면 새 `type` 을 추가하고 구 버전을 일정 기간 함께 내보낸다.

URI 가 실제로 문서를 가리키게 한다. 9457 은 역참조 가능성을 요구하지 않지만, 가능하면 그 주소에 원인·해결법·확장 멤버 스키마를 올려 두는 것이 개발자 경험상 압도적으로 낫다.

OpenAPI 에 반영한다. springdoc 을 쓴다면 `@ApiResponse` 로 스키마를 붙인다.

```java
@ApiResponse(
		responseCode = "422",
		description = "잔액 부족",
		content = @Content(
				mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
				schema = @Schema(implementation = ProblemDetail.class)))
@PostMapping("/accounts/{id}/withdrawals")
public WithdrawalResponse withdraw(@PathVariable Long id, @Valid @RequestBody WithdrawalRequest request) {
	return withdrawalService.withdraw(id, request);
}
```

`ProblemDetail` 스키마에는 확장 멤버가 안 나오므로, 확장이 있는 유형은 별도 스키마 클래스를 만들어 붙이는 편이 정확하다. 이 작업을 하지 않으면 문서와 실제 응답이 어긋나 클라이언트가 결국 실제 응답을 관찰해 추측하게 된다 — 표준화의 목적이 반쯤 무너진다.

## 참고

- RFC 9457 — Problem Details for HTTP APIs (https://www.rfc-editor.org/rfc/rfc9457.html)
- Spring Framework Reference — Error Responses (https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- Spring Boot Reference — Error Handling (https://docs.spring.io/spring-boot/reference/web/servlet.html)
- Spring Security Reference — Exception Handling (https://docs.spring.io/spring-security/reference/servlet/architecture.html)
