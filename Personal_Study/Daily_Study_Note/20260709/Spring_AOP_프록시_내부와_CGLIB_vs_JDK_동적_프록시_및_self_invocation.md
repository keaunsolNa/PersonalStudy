Notion 원본: https://app.notion.com/p/3985a06fd6d381fb9d04f10df4245ed4

# Spring AOP 프록시 내부와 CGLIB vs JDK 동적 프록시 및 self-invocation

> 2026-07-09 신규 주제 · 확장 대상: Backend

## 학습 목표

- Spring AOP 가 런타임 프록시 기반이라는 점과 JDK 동적 프록시·CGLIB 의 선택 기준을 구분한다
- 프록시가 어드바이스 체인을 통과시키는 호출 흐름을 단계별로 추적한다
- self-invocation 이 어드바이스를 우회하는 근본 원인과 우회책을 설명한다
- @Transactional·@Async 가 프록시 제약 때문에 실패하는 패턴을 진단한다

## 1. Spring AOP 는 런타임 프록시다

Spring AOP 는 런타임에 프록시 객체를 만들어 부가기능(어드바이스)을 끼운다. 빈 초기화 후처리기(AbstractAutoProxyCreator 계열)가 적용 어드바이저를 찾아 매칭되면 원본 대신 프록시를 등록한다. 핵심 한계는 프록시가 외부에서 들어오는 호출만 가로챠 수 있다는 것이며, 이것이 self-invocation 문제의 뿌리다.

## 2. JDK 동적 프록시 vs CGLIB

대상이 인터페이스를 구현하면 기본적으로 JDK 동적 프록시를, 없으면 CGLIB 를 쓴다. Spring Boot 2.0 부터는 기본이 CGLIB(proxyTargetClass=true)다. JDK 프록시는 java.lang.reflect.Proxy 로 인터페이스를 구현해 인터페이스 타입으로만 캐스팅되고 인터페이스 메서드만 가로채다. CGLIB 는 대상 클래스를 상속한 서브클래스를 바이트코드로 생성해 메서드를 오버라이드하므로 final 클래스·final/private 메서드는 가로챠 수 없다.

| 항목 | JDK 동적 프록시 | CGLIB |
|---|---|---|
| 기반 | 인터페이스 구현 | 클래스 상속 |
| 제약 | 인터페이스 메서드만 | final·private 불가 |
| 캐스팅 | 인터페이스 타입만 | 서브클래스 타입 |
| Boot 기본 | — | 기본값 |

## 3. 어드바이스 체인 호출 흐름

외부 호출 시 프록시가 MethodInterceptor 체인(ReflectiveMethodInvocation)을 구성하고, 각 인터셉터가 proceed() 로 다음으로 진행해 마지막에 실제 메서드가 리플렉션으로 실행된다.

```java
@Aspect
@Component
public class TimingAspect {
	@Around("@annotation(Timed)")
	public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
		long start = System.nanoTime();
		try {
			return joinPoint.proceed();
		} finally {
			log.info("{} took {}ms", joinPoint.getSignature(), (System.nanoTime() - start) / 1_000_000L);
		}
	}
}
```

proceed() 를 븠뜨리면 대상이 실행되지 않는다. 순서는 @Order/Ordered 로 제어하며 낮은 값이 바깥쪽이다.

## 4. self-invocation 문제

같은 클래스 내 메서드가 다른 메서드를 직접 호출하면 this 는 프록시가 아니라 실제 대상이라 어드바이스가 무시된다.

```java
@Service
public class OrderService {
	@Transactional
	public void placeOrder(Order order) {
		save(order);
		charge(order); // this.charge() → 프록시 우회, REQUIRES_NEW 무시
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void charge(Order order) { }
}
```

우회책: (1) 자기 프록시 주입(@Lazy) 또는 AopContext.currentProxy()(exposeProxy=true), (2) 어드바이스 필요 메서드를 별도 빈으로 분리(가장 깔끔), (3) TransactionTemplate 로 프로그래밍적 경계.

```java
@Service
public class OrderService {
	private final PaymentService paymentService;
	public OrderService(PaymentService paymentService) { this.paymentService = paymentService; }
	@Transactional
	public void placeOrder(Order order) {
		save(order);
		paymentService.charge(order); // 외부 빈 호출 → 프록시 경유
	}
}
```

## 5. private·final 메서드와 프록시 무시

CGLIB 는 오버라이드로 가로채므로 private/final 메서드·final 클래스·static 메서드에는 어드바이스를 걸 수 없다. 근본 해결은 AspectJ 위빙으로의 전환이다.

## 6. 초기화 시점 함정과 진단

@PostConstruct·생성자에서 자신의 @Transactional/@Async 메서드를 호출하면 프록시 생성 전이거나 self-invocation 이라 무시된다. 진단은 getClass().getName() 에 $$SpringCGLIB$$ / $Proxy 포함 여부, 트랜잭션 TRACE 로그로 확인한다.

```properties
logging.level.org.springframework.transaction.interceptor=TRACE
```

## 7. 검증 예시

별도 빈 분리 후 REQUIRES_NEW 가 독립 커밋되는지 통합 테스트로 검증한다.

```java
@SpringBootTest
class OrderServiceTest {
	@Autowired OrderService orderService;
	@Autowired OrderRepository orderRepository;
	@Test
	void charge_commits_independently_when_outer_rolls_back() {
		assertThatThrownBy(() -> orderService.placeOrderThenFail(new Order("A-1")))
			.isInstanceOf(IllegalStateException.class);
		assertThat(orderRepository.findChargeLog("A-1")).isPresent();
	}
}
```

단위 수준에서는 AopUtils.isAopProxy(bean)·isCglibProxy(bean) 로 프록시 타입을 단언한다.

## 참고

- Spring Framework Reference — Aspect Oriented Programming with Spring
- Spring Framework Reference — Understanding AOP Proxies (self-invocation)
- Spring Framework Reference — Declarative Transaction Management
- CGLIB / Objenesis 프로젝트 문서
