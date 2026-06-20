Notion 원본: https://app.notion.com/p/3855a06fd6d38123b9dadf7ff105db51

# Spring AOP 프록시 내부와 JDK 동적프록시·CGLIB 및 자기호출 트랜잭션 경계

> 2026-06-20 신규 주제 · 확장 대상: Spring

## 학습 목표

- JDK 동적 프록시와 CGLIB 서브클래싱의 바이트코드 수준 차이를 구분한다
- `@Transactional`·`@Async`·`@Cacheable` 이 프록시 경계에서만 동작하는 이유를 설명한다
- 자기호출(self-invocation) 로 어드바이스가 누락되는 현상을 재현하고 우회한다
- AnnotationAwareAspectJAutoProxyCreator 가 빈 후처리 단계에서 프록시를 생성하는 흐름을 추적한다

## 1. 프록시 기반 AOP 의 본질

Spring AOP 는 AspectJ 같은 위빙(weaving) 컴파일러가 아니라 **런타임 프록시**다. 타깃 빈을 감싸는 대리 객체를 만들고, 메서드 호출이 프록시를 거칠 때만 부가 기능(어드바이스)을 끼워 넣는다. 이 한 문장이 Spring AOP 의 모든 제약을 만든다. 프록시를 "통과하지 않는" 호출 — 같은 객체 내부에서의 `this.method()` 호출, `private`/`final`/`static` 메서드 — 에는 어드바이스가 적용될 수 없다.

프록시는 두 가지 방식으로 생성된다. 타깃이 인터페이스를 구현하면 기본적으로 JDK 동적 프록시를, 구현하지 않으면 CGLIB 서브클래스를 만든다. Spring Boot 는 2.x 부터 `spring.aop.proxy-target-class=true` 가 기본값이라 **인터페이스가 있어도 CGLIB 를 선호**한다. 이 차이는 단순 취향이 아니라 캐스팅 가능성과 final 제약에 직접 영향을 준다.

## 2. JDK 동적 프록시의 동작

JDK 동적 프록시는 `java.lang.reflect.Proxy` 가 런타임에 인터페이스 목록으로부터 `$Proxy12` 같은 클래스를 만든다. 모든 메서드 호출은 단일 `InvocationHandler.invoke()` 로 라우팅된다.

```java
public interface OrderService {
    Order place(Long memberId);
}

// Spring 내부가 만드는 핸들러의 단순화 버전
public final class LoggingInvocationHandler implements InvocationHandler {

    private final Object target;

    public LoggingInvocationHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return method.invoke(target, args); // 실제 타깃 위임
        } finally {
            long elapsedMicros = (System.nanoTime() - startNanos) / 1_000L;
            log.debug("{} took {}us", method.getName(), elapsedMicros);
        }
    }
}

OrderService proxy = (OrderService) Proxy.newProxyInstance(
        OrderService.class.getClassLoader(),
        new Class[] {OrderService.class},
        new LoggingInvocationHandler(realService));
```

핵심 제약은 **인터페이스 타입으로만 캐스팅 가능**하다는 점이다. `(OrderServiceImpl) proxy` 는 `ClassCastException` 을 던진다. `$Proxy12` 는 `OrderServiceImpl` 의 서브타입이 아니기 때문이다. 그래서 구현 클래스 타입으로 `@Autowired` 하면 주입이 깨진다.

## 3. CGLIB 서브클래싱의 동작

CGLIB 는 타깃 클래스를 **상속한 동적 서브클래스**를 ASM 으로 생성하고, 각 메서드를 오버라이드해 `MethodInterceptor` 로 가로챈다.

```java
public class CglibLoggingInterceptor implements MethodInterceptor {

    @Override
    public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return proxy.invokeSuper(obj, args); // super.method() 호출 = 타깃 로직
        } finally {
            log.debug("{} took {}ns", method.getName(), System.nanoTime() - startNanos);
        }
    }
}
```

`proxy.invokeSuper()` 는 리플렉션이 아니라 CGLIB 가 생성한 **FastClass 인덱스 기반 디스패치**를 쓴다. 메서드마다 정수 인덱스를 부여해 `switch` 로 직접 호출하므로 JDK 프록시의 `Method.invoke()` 리플렉션보다 빠르다. 다만 서브클래싱이므로 `final` 클래스는 프록시 불가, `final`/`private` 메서드는 오버라이드 불가라 어드바이스가 조용히 누락된다. 생성자도 두 번(타깃 1회, 프록시 1회) 호출되므로 생성자에 부수효과를 두면 안 된다. Spring 6 / Boot 3 의 CGLIB 는 `Objenesis` 로 생성자 호출을 우회해 이 문제를 완화한다.

JDK 프록시와 CGLIB 의 트레이드오프는 다음과 같다.

| 항목 | JDK 동적 프록시 | CGLIB |
|---|---|---|
| 전제 조건 | 인터페이스 필요 | 구체 클래스 상속 |
| 캐스팅 | 인터페이스 타입만 | 구체 클래스 타입 가능 |
| final 제약 | 없음 | final 클래스/메서드 불가 |
| 호출 비용 | Method.invoke 리플렉션 | FastClass 직접 디스패치(더 빠름) |
| 생성 비용 | 가벼움 | 클래스 생성 무거움(첫 호출 지연) |

## 4. 자동 프록시 생성 흐름

`@EnableAspectJAutoProxy`(또는 Boot 의 AopAutoConfiguration) 는 `AnnotationAwareAspectJAutoProxyCreator` 라는 `BeanPostProcessor` 를 등록한다. 빈 생성 마지막 단계인 `postProcessAfterInitialization()` 에서, 해당 빈에 적용 가능한 어드바이저가 하나라도 있으면 `ProxyFactory` 로 프록시를 만들어 **원본 빈 대신 컨테이너에 등록**한다.

```java
// AbstractAutoProxyCreator 의 핵심 골격 (단순화)
protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
    Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
    if (specificInterceptors == DO_NOT_PROXY) {
        return bean; // 적용 대상 없음 → 원본 그대로
    }
    ProxyFactory proxyFactory = new ProxyFactory();
    proxyFactory.copyFrom(this);
    proxyFactory.setTarget(bean);
    proxyFactory.addAdvisors(buildAdvisors(beanName, specificInterceptors));
    return proxyFactory.getProxy(getProxyClassLoader());
}
```

여기서 중요한 사실: 의존성 주입은 **프록시를 받는다**. 따라서 다른 빈이 보는 것은 항상 프록시다. 그런데 타깃 객체 자신이 보는 `this` 는 프록시가 아니라 원본이다. 이 비대칭이 다음 절의 자기호출 문제를 만든다.

## 5. 자기호출 문제 재현

```java
@Service
public class ReportService {

    public List<Report> generateAll(List<Long> ids) {
        return ids.stream()
                .map(this::generateOne) // this = 원본 객체 → 프록시 미경유
                .toList();
    }

    @Transactional
    @Cacheable("report")
    public Report generateOne(Long id) {
        // @Transactional, @Cacheable 모두 동작하지 않는다!
        return repository.findReport(id);
    }
}
```

`generateAll` 이 `this.generateOne()` 을 부르면 호출은 프록시를 거치지 않으므로 트랜잭션도 캐시도 적용되지 않는다. 외부에서 `reportService.generateOne(id)` 를 직접 부를 때만 동작한다. 이 버그는 단위 테스트에서 외부 호출만 검증하면 통과하다가, 실제 내부 반복 호출 경로에서 트랜잭션이 사라져 무결성 사고로 이어진다.

## 6. 자기호출 우회 전략

세 가지 실무 우회법이 있고 각각 트레이드오프가 다르다.

```java
// 전략 1: 자기 프록시 주입 (AopContext) — proxy-target-class 와 exposeProxy=true 필요
@EnableAspectJAutoProxy(exposeProxy = true)
// ...
public List<Report> generateAll(List<Long> ids) {
    ReportService self = (ReportService) AopContext.currentProxy();
    return ids.stream().map(self::generateOne).toList();
}

// 전략 2: 자기 자신을 지연 주입 (순환 의존 → ObjectProvider 로 회피)
@Service
public class ReportService {
    private final ObjectProvider<ReportService> selfProvider;
    public ReportService(ObjectProvider<ReportService> selfProvider) {
        this.selfProvider = selfProvider;
    }
    public List<Report> generateAll(List<Long> ids) {
        ReportService self = selfProvider.getObject();
        return ids.stream().map(self::generateOne).toList();
    }
}
```

가장 권장되는 것은 **전략 3: 책임 분리**다. 트랜잭션 경계를 가진 메서드를 별도 빈으로 추출하면 호출이 자연스럽게 프록시를 거친다. `AopContext` 는 코드에 프레임워크 의존을 새기고, `exposeProxy` 는 ThreadLocal 비용을 추가한다. 설계로 푸는 편이 항상 더 깨끗하다.

## 7. 트랜잭션 경계와 전파의 상호작용

자기호출이 위험한 진짜 이유는 `Propagation.REQUIRES_NEW` 같은 전파 옵션이 무력화되기 때문이다. 별도 트랜잭션을 의도했는데 같은 트랜잭션에 묶이거나 아예 트랜잭션 없이 실행된다. 아래는 흔한 함정이다.

```java
@Transactional // 부모 트랜잭션
public void process(List<Item> items) {
    for (Item item : items) {
        this.saveInNewTx(item); // REQUIRES_NEW 가 무시됨 → 부모 롤백 시 전부 롤백
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void saveInNewTx(Item item) { repository.save(item); }
```

"실패한 항목만 건너뛰고 나머지는 커밋" 을 기대했지만, 자기호출이라 독립 트랜잭션이 생기지 않아 전부 함께 롤백된다. 별도 빈으로 분리해야 비로소 `REQUIRES_NEW` 가 새 물리 트랜잭션을 연다.

## 8. 측정과 검증 관점

프록시 종류는 런타임에 검증할 수 있다. `AopUtils.isAopProxy(bean)`, `AopUtils.isJdkDynamicProxy(bean)`, `AopUtils.isCglibProxy(bean)` 로 확인하고, `bean.getClass().getName()` 에 `$$SpringCGLIB$$` 또는 `$Proxy` 가 포함되는지로 구분한다. 자기호출 누락을 테스트로 잡으려면, 트랜잭션 동기화 여부를 `TransactionSynchronizationManager.isActualTransactionActive()` 로 단언하는 통합 테스트를 작성하는 것이 신뢰할 수 있다.

성능 측면에서 프록시 1회 통과 비용은 보통 수십~수백 나노초로, 비즈니스 로직 대비 무시할 수준이다. 다만 CGLIB 프록시는 클래스 생성과 첫 호출의 FastClass 초기화 때문에 **콜드 스타트가 느리다**. GraalVM Native Image 환경에서는 런타임 프록시 생성이 제한되므로, AOT 단계에서 프록시 클래스를 미리 생성하거나 인터페이스 기반 설계로 전환하는 편이 안전하다.

## 9. 포인트컷과 어드바이스 순서

어떤 메서드에 어드바이스를 끼울지는 포인트컷 표현식이 결정한다. Spring 은 AspectJ 의 포인트컷 문법 부분집합을 빌려 쓴다. 자주 쓰는 지정자는 `execution`(메서드 시그니처 매칭), `within`(타입 한정), `@annotation`(특정 애너테이션이 붙은 메서드), `bean`(빈 이름)이다.

```java
@Aspect
@Component
public class MetricAspect {

    // com.app.service 패키지의 public 메서드 전체
    @Pointcut("execution(public * com.app.service..*(..))")
    private void serviceLayer() { }

    // @Audited 가 붙은 메서드
    @Pointcut("@annotation(com.app.Audited)")
    private void audited() { }

    @Around("serviceLayer() && audited()")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return pjp.proceed(); // 타깃(또는 다음 어드바이스) 호출
        } finally {
            registry.timer(pjp.getSignature().getName())
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }
}
```

여러 어드바이스가 한 조인포인트에 겹치면 순서가 문제된다. 같은 애스펙트 안에서는 어드바이스 종류별 우선순위(Around·Before 가 진입 시, AfterReturning·After 가 복귀 시)가 적용되고, 서로 다른 애스펙트 사이에는 `@Order` 또는 `Ordered` 인터페이스로 명시한다. 트랜잭션 애스펙트(`@Transactional`)는 기본적으로 `Ordered.LOWEST_PRECEDENCE` 라 가장 안쪽에 위치한다. 만약 트랜잭션 시작 전에 어떤 검증을 수행하고 싶다면, 그 애스펙트의 order 를 트랜잭션보다 작게(더 높은 우선순위로) 설정해 바깥에 배치해야 한다. 순서를 명시하지 않으면 적용 순서가 비결정적이라, 재현 안 되는 버그의 원인이 된다.

## 10. Spring AOP 와 AspectJ 의 경계

Spring AOP 의 프록시 한계(자기호출·final·생성자)가 본질적으로 거슬린다면 **컴파일/로드 타임 위빙 AspectJ** 로 전환할 수 있다. AspectJ 는 바이트코드를 직접 수정해 `this.method()` 내부 호출, 필드 접근, 생성자까지 가로챌 수 있다. 대신 빌드 도구(ajc 컴파일러)나 로드타임 위버 에이전트(`-javaagent`)가 필요해 설정 복잡도가 오른다.

| 항목 | Spring AOP(프록시) | AspectJ(위빙) |
|---|---|---|
| 조인포인트 | public 메서드 호출만 | 메서드·필드·생성자·내부 호출 |
| 자기호출 | 미적용 | 적용됨 |
| 설정 | 의존성만 추가 | 컴파일러/에이전트 필요 |
| 성능 | 프록시 통과 비용 | 위빙되어 호출 비용 거의 없음 |

실무 다수는 Spring AOP 로 충분하다. 자기호출까지 잡아야 하는 드문 경우에만 AspectJ 를 도입하고, 대개는 "설계로 경계를 분리" 하는 편이 운영·디버깅이 쉽다. AOP 는 강력하지만 흐름을 코드에서 숨기므로, 로깅·트랜잭션·메트릭 같은 횡단 관심사에 한정하고 비즈니스 분기를 애스펙트에 넣지 않는 것이 유지보수의 원칙이다.

## 참고

- Spring Framework Reference — Core Technologies: Aspect Oriented Programming with Spring
- Spring Framework Reference — Proxying Mechanisms (JDK Dynamic vs CGLIB)
- CGLIB / ASM 공식 문서, Objenesis project documentation
- "Pro Spring 6" — AOP and Transaction Management 챕터
