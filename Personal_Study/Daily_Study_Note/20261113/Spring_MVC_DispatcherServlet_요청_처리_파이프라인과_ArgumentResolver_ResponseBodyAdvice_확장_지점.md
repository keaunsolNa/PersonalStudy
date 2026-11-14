Notion 원본: https://www.notion.so/4185a06fd6d381bb85a6cc1481483e53

# Spring MVC DispatcherServlet 요청 처리 파이프라인과 ArgumentResolver·ResponseBodyAdvice 확장 지점

> 2026-11-13 신규 주제 · 확장 대상: Spring(MVC 기본 흐름), Servlet(필터와 서블릿 생명주기)

## 학습 목표

- doDispatch 의 호출 순서를 HandlerMapping, HandlerAdapter, 인터셉터, 예외 리졸버 단위로 추적한다
- HandlerMethodArgumentResolver 를 구현해 컨트롤러 매개변수 주입을 확장하고 등록 순서의 영향을 판단한다
- 응답 본문 가공 위치로 postHandle 대신 ResponseBodyAdvice 를 선택하는 근거를 테스트로 확인한다
- 필터, 인터셉터, 리졸버, 어드바이스 중 요구 사항에 맞는 확장 지점을 고른다

## 1. DispatcherServlet 이 하는 일의 범위

Spring MVC 를 처음 배울 때는 "DispatcherServlet 이 요청을 받아 컨트롤러를 호출한다" 정도로 이해하고 넘어가지만, 실무에서 만나는 문제의 상당수는 그 한 문장 안에 숨은 단계들에서 생긴다. 인증 사용자 객체를 컨트롤러 매개변수로 받고 싶을 때, 모든 응답을 공통 포맷으로 감싸고 싶을 때, 예외가 어떤 경우에는 `@ExceptionHandler` 로 잡히고 어떤 경우에는 잡히지 않는지 판단해야 할 때, 필요한 것은 요청 한 건이 지나가는 정확한 경로와 각 지점에서 응답이 어떤 상태인지에 대한 지식이다.

DispatcherServlet 은 서블릿 컨테이너 입장에서 평범한 서블릿 하나다. 컨테이너의 필터 체인이 모두 통과된 뒤에야 `service` 가 호출되고, 그 안에서 `doDispatch` 가 Spring 고유의 처리를 수행한다. 따라서 필터에서 던진 예외는 DispatcherServlet 에 도달한 적이 없으므로 `@ControllerAdvice` 의 대상이 아니다. Spring Security 의 인증 실패가 `@ExceptionHandler` 에 잡히지 않고 별도의 `AuthenticationEntryPoint` 로 처리되는 이유가 이 경계에 있다.

## 2. doDispatch 의 단계

`doDispatch` 는 한 메서드 안에서 다음 순서를 고정적으로 수행한다. 멀티파트 요청이면 `MultipartResolver` 로 요청을 감싼다. 등록된 `HandlerMapping` 들을 순서대로 조회해 처음으로 null 이 아닌 결과를 돌려준 것의 `HandlerExecutionChain` 을 얻는데, 이 체인은 핸들러 객체와 그 경로에 적용될 인터셉터 목록을 묶은 것이다. 애노테이션 기반 컨트롤러는 `RequestMappingHandlerMapping` 이 담당하며 핸들러는 `HandlerMethod` 로 표현된다. 핸들러를 찾지 못하면 404 처리로 넘어간다.

다음으로 그 핸들러를 실행할 수 있는 `HandlerAdapter` 를 고른다. DispatcherServlet 은 핸들러의 구체 타입을 모르며, 호출 방법은 어댑터가 안다. `HandlerMethod` 는 `RequestMappingHandlerAdapter` 가 처리한다. 그 뒤 인터셉터의 `preHandle` 을 등록 순서대로 호출하고, 하나라도 false 를 반환하면 그 자리에서 처리를 끝낸다. 모두 통과하면 어댑터의 `handle` 이 실제 컨트롤러 메서드를 실행하고, 이어서 인터셉터의 `postHandle` 을 역순으로 호출한다. 마지막 `processDispatchResult` 에서는 예외가 있었으면 `HandlerExceptionResolver` 체인에 넘기고, `ModelAndView` 가 있으면 뷰를 렌더링하며, 어떤 경로로 끝나든 `preHandle` 이 true 를 반환했던 인터셉터들의 `afterCompletion` 을 역순으로 호출한다.

기억해 둘 점은 `postHandle` 이 예외 경로에서는 호출되지 않는다는 것과, `afterCompletion` 은 성공과 실패 모두에서 호출된다는 것이다. 요청 단위 자원의 정리나 MDC 초기화는 반드시 `afterCompletion` 에 두어야 한다.

## 3. RequestMappingHandlerAdapter 내부: 인자 해석과 반환값 처리

어댑터의 `handle` 은 `HandlerMethod` 를 `ServletInvocableHandlerMethod` 로 감싸 실행한다. 실행은 세 단계로 나뉜다. 메서드의 각 매개변수에 대해 `HandlerMethodArgumentResolver` 목록을 순회하며 `supportsParameter` 가 true 인 첫 리졸버에게 값을 만들게 하고, 리플렉션으로 메서드를 호출한 뒤, 반환값에 대해 `HandlerMethodReturnValueHandler` 목록에서 같은 방식으로 처리기를 찾는다. `@RequestParam`, `@PathVariable`, `@RequestBody`, `HttpServletRequest` 같은 매개변수가 동작하는 것은 각각에 대응하는 내장 리졸버가 등록되어 있기 때문이다. 매개변수별로 어떤 리졸버가 선택됐는지는 `HandlerMethodArgumentResolverComposite` 가 캐시에 보관하므로 순회 비용은 최초 한 번만 든다.

`@RequestBody` 와 `@ResponseBody` 는 `RequestResponseBodyMethodProcessor` 하나가 리졸버와 반환값 처리기 역할을 겸한다. 이 프로세서는 `HttpMessageConverter` 목록을 순회해 요청의 Content-Type 을 읽을 수 있는 컨버터로 본문을 역직렬화하고, 응답 쪽에서는 요청의 Accept 헤더와 컨버터가 생산 가능한 미디어 타입을 맞춰 보는 콘텐츠 협상으로 컨버터를 고른다. 여기서 핵심은 `@ResponseBody` 반환값의 직렬화와 응답 스트림 쓰기가 반환값 처리기 안에서, 즉 `handle` 호출이 끝나기 전에 일어난다는 점이다. 이 사실이 5절의 결론으로 이어진다.

## 4. HandlerMethodArgumentResolver 구현과 등록 순서

로그인 사용자 정보를 컨트롤러에서 매번 요청 속성으로 꺼내는 대신 매개변수로 선언해 받도록 만들어 본다. 필터나 인터셉터가 인증을 끝내고 요청 속성에 `LoginUser` 를 넣어 두었다고 가정한다.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}

public record LoginUser(long id, String role) {
}

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

	public static final String ATTRIBUTE = "loginUser";

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentUser.class)
			&& LoginUser.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Object attribute = webRequest.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (attribute instanceof LoginUser loginUser) {
			return loginUser;
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
	}
}

@RestController
public class MeController {

	@GetMapping("/me")
	public LoginUser me(@CurrentUser LoginUser loginUser) {
		return loginUser;
	}

	@GetMapping("/ping")
	public String ping() {
		return "pong";
	}
}
```

애플리케이션에서는 `WebMvcConfigurer.addArgumentResolvers` 로 등록한다. 이때 순서에 관한 제약을 알아야 한다. 이 메서드로 추가한 리졸버는 내장 리졸버들 뒤에, 그리고 맨 끝에 놓이는 포괄 리졸버들(애노테이션 없는 단순 타입을 요청 파라미터로, 그 외 객체를 모델 속성으로 처리하는 것들) 앞에 배치된다. 따라서 내장 리졸버가 이미 지원하는 매개변수는 커스텀 리졸버까지 순서가 오지 않는다. 예를 들어 매개변수 타입을 `Map` 이나 `Principal` 로 선언하면 내장 리졸버가 먼저 가로챈다. 전용 애노테이션과 전용 타입을 조합해 `supportsParameter` 조건을 좁게 잡은 것은 이 충돌을 피하기 위해서다. 조건이 좁으면 "애노테이션을 빠뜨린 `LoginUser` 매개변수"가 모델 속성 바인딩으로 흘러간다는 부작용이 있다. Spring Boot 3.4.1 에서 확인해 보면 이 경우 레코드 생성자에 요청 파라미터를 바인딩하려다 `id` 값이 없어 `MethodArgumentNotValidException` 과 함께 400 이 반환되므로, 조용히 빈 객체가 주입되지는 않고 오류로 드러난다.

리졸버가 던지는 예외는 컨트롤러 메서드가 던진 예외와 같은 경로로 `HandlerExceptionResolver` 체인에 전달된다. `ResponseStatusException` 은 내장 `ResponseStatusExceptionResolver` 가 상태 코드로 변환하므로 별도 핸들러 없이 401 응답이 된다.

## 5. 응답 본문 가공: postHandle 이 아니라 ResponseBodyAdvice

모든 JSON 응답을 `{"data": ...}` 로 감싸는 요구를 인터셉터의 `postHandle` 에서 처리하려는 시도는 실패한다. 3절에서 본 대로 `@ResponseBody` 응답은 어댑터 안에서 이미 직렬화되어 스트림에 쓰였고, `postHandle` 시점에는 응답이 커밋된 상태이며 `ModelAndView` 는 null 이다. 본문이 쓰이기 직전에 개입할 수 있도록 Spring 이 마련한 지점이 `ResponseBodyAdvice` 다. 컨버터가 선택된 뒤, 쓰기 직전에 호출된다.

```java
public record ApiEnvelope<T>(T data) {
}

@RestControllerAdvice
public class EnvelopeAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
		Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
		ServerHttpResponse response) {
		if (body instanceof ApiEnvelope<?>) {
			return body;
		}
		return new ApiEnvelope<>(body);
	}
}
```

`supports` 에서 컨버터 타입을 Jackson 으로 제한한 것이 중요하다. 컨트롤러가 `String` 을 반환하면 `StringHttpMessageConverter` 가 선택되는데, 이때 본문을 `ApiEnvelope` 객체로 바꿔 버리면 문자열 컨버터가 객체를 쓰려다 `ClassCastException` 이 발생한다. 응답 래핑을 도입한 프로젝트에서 문자열 반환 엔드포인트만 500 이 나는 전형적인 원인이다. 요청 쪽에는 대칭되는 `RequestBodyAdvice` 가 있어 역직렬화 전후에 개입할 수 있고, 복호화나 감사 로깅에 쓰인다.

## 6. 테스트로 확인한 호출 순서와 응답 상태

위 코드를 Spring Boot 3.4.1, Java 21 환경에서 standalone MockMvc 로 검증했다. 인터셉터가 각 콜백 시점의 응답 커밋 여부를 기록하게 하고, 정상 요청과 속성이 없는 요청을 보냈다.

```java
class MeControllerTest {

	private final List<String> events = new ArrayList<>();
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		HandlerInterceptor recorder = new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				events.add("preHandle committed=" + response.isCommitted());
				return true;
			}

			@Override
			public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
				ModelAndView modelAndView) {
				events.add("postHandle committed=" + response.isCommitted() + " mav=" + modelAndView);
			}

			@Override
			public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
				Exception ex) {
				events.add("afterCompletion status=" + response.getStatus());
			}
		};
		mockMvc = MockMvcBuilders.standaloneSetup(new MeController())
			.setCustomArgumentResolvers(new CurrentUserArgumentResolver())
			.setControllerAdvice(new EnvelopeAdvice())
			.addInterceptors(recorder)
			.build();
	}

	@Test
	void resolvesLoginUserAndWrapsBody() throws Exception {
		mockMvc.perform(get("/me").requestAttr(CurrentUserArgumentResolver.ATTRIBUTE, new LoginUser(7L, "ADMIN"))
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(7))
			.andExpect(jsonPath("$.data.role").value("ADMIN"));
		assertThat(events).hasSize(3);
		assertThat(events.get(1)).startsWith("postHandle committed=true");
	}

	@Test
	void rejectsWhenAttributeMissing() throws Exception {
		mockMvc.perform(get("/me")).andExpect(status().isUnauthorized());
		assertThat(events).noneMatch(event -> event.startsWith("postHandle"));
	}

	@Test
	void stringBodyIsNotWrapped() throws Exception {
		mockMvc.perform(get("/ping")).andExpect(status().isOk()).andExpect(content().string("pong"));
	}
}
```

세 테스트가 모두 통과했고 기록된 이벤트는 다음과 같았다. 정상 요청은 `preHandle committed=false`, `postHandle committed=true mav=null`, `afterCompletion status=200` 순서였다. `postHandle` 에 도달했을 때 응답이 이미 커밋되어 있고 `ModelAndView` 가 null 이라는 점이 5절의 설명과 일치한다. 속성이 없는 요청은 `preHandle committed=false`, `afterCompletion status=401` 두 건만 기록됐다. 리졸버에서 예외가 발생하면 `postHandle` 은 건너뛰고, `afterCompletion` 은 예외 리졸버가 상태 코드를 확정한 뒤에 호출된다는 것을 보여 준다. 세 번째 테스트는 문자열 응답이 래핑 대상에서 제외되어 그대로 나가는 것을 확인한다.

## 7. 메시지 컨버터 설정에서 자주 틀리는 부분

`WebMvcConfigurer` 에는 컨버터와 관련된 메서드가 둘 있고 효과가 다르다. `configureMessageConverters` 는 목록에 무언가를 추가하는 순간 기본 컨버터 등록을 끈다. 커스텀 컨버터 하나를 넣으려다 Jackson 과 문자열 컨버터가 모두 사라져 다른 엔드포인트가 406 이나 415 를 반환하게 되는 사고가 여기서 나온다. 기본 구성을 유지하면서 추가하거나 순서를 바꾸려면 `extendMessageConverters` 를 쓴다. Spring Boot 에서는 `HttpMessageConverter` 빈을 등록하는 것만으로 기본 목록에 합쳐지므로 대부분 이 방법이 가장 안전하고, Jackson 동작을 바꾸는 목적이라면 컨버터가 아니라 `Jackson2ObjectMapperBuilderCustomizer` 빈으로 `ObjectMapper` 구성을 조정하는 편이 영향 범위가 작다.

컨버터 순서도 결과에 영향을 준다. 응답 컨버터는 목록 순서대로 "이 반환 타입을 이 미디어 타입으로 쓸 수 있는가"를 질의받는다. Accept 헤더가 없거나 `*/*` 이면 앞쪽 컨버터가 우선하므로, XML 컨버터 의존성을 추가한 뒤 일부 클라이언트가 갑자기 XML 응답을 받는 일이 생길 수 있다. 응답 형식이 고정된 API 라면 `@RequestMapping` 의 `produces` 를 명시해 협상 결과를 고정하는 것이 방어적이다.

## 8. 확장 지점 선택 기준

요구 사항별로 맞는 지점은 다음과 같이 정리된다. 요청 본문 스트림 자체를 감싸거나, Spring MVC 바깥의 정적 자원과 에러 디스패치까지 포함해 모든 요청에 적용해야 하면 서블릿 필터를 쓴다. 필터는 핸들러가 무엇인지 모른다는 한계가 있다. 어떤 컨트롤러 메서드가 실행될지 알아야 하는 공통 처리, 예를 들어 메서드에 붙은 애노테이션으로 권한을 검사하는 일은 인터셉터의 `preHandle` 이 맞다. `handler` 인자가 `HandlerMethod` 로 전달되기 때문이다. 컨트롤러에 값을 공급하는 일은 리졸버가, 본문 직렬화 직전의 가공은 바디 어드바이스가, 예외를 응답으로 바꾸는 일은 `@ExceptionHandler` 가 담당한다.

비용 측면에서 인터셉터와 리졸버는 요청당 메서드 호출 몇 번 수준이라 부담이 없지만, 필터에서 본문을 여러 번 읽기 위해 `ContentCachingRequestWrapper` 를 쓰면 본문 전체가 힙에 복사된다. 대용량 업로드 경로에는 적용 범위를 제한해야 한다. 또 하나의 기준은 테스트 용이성이다. 리졸버와 어드바이스는 6절처럼 컨텍스트 없이 standalone MockMvc 로 검증할 수 있어 피드백이 빠르고, 필터는 `addFilters` 로 같은 방식의 테스트에 포함시킬 수 있다.

## 참고

- Spring Framework Reference, DispatcherServlet 처리 흐름: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html
- Spring Framework Reference, Handler Methods 의 Method Arguments: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/arguments.html
- Spring Framework Reference, Interception: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/handlermapping-interceptor.html
- Spring Framework Reference, MVC Config 의 Message Converters: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/message-converters.html
- Spring Framework Javadoc, ResponseBodyAdvice: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseBodyAdvice.html
- Spring Framework 소스, DispatcherServlet.doDispatch: https://github.com/spring-projects/spring-framework/blob/main/spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java
