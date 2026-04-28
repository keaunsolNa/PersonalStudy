Notion 원본: https://www.notion.so/3505a06fd6d38173b414d6521c18e647

# Spring AOP Internals — Proxy 메커니즘과 Self-Invocation 한계

> 2026-04-28 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring AOP가 JDK Dynamic Proxy와 CGLIB 중 어떤 기준으로 프록시 타입을 결정하는지 코드 레벨에서 추적한다.
- self-invocation 시 어드바이스가 동작하지 않는 근본 원인을 호출 스택과 ProxyFactory 동작으로 설명한다.
- AspectJ Compile-time / Load-time Weaving이 Spring AOP의 한계를 어떻게 보완하는지 비교한다.
- 실서비스 트랜잭션 어노테이션 누락 사고를 재현·진단·수정하는 코드를 작성한다.

## 1. Spring AOP는 무엇을 프록시하는가

Spring AOP는 컨테이너에 등록된 빈을 감싸는 "런타임 프록시"를 만든다. 실제 위빙(weaving)을 하지 않으므로 호출자가 빈에 접근할 때 사용되는 참조가 프록시이면 어드바이스가 동작하고, 참조가 원본 객체이면 동작하지 않는다. 이 단순한 사실이 AOP 사고의 80% 이상을 만든다. Spring 컨테이너는 `BeanPostProcessor` 체인 중 `AnnotationAwareAspectJAutoProxyCreator`가 빈 초기화 직후(`postProcessAfterInitialization`) 어드바이스가 적용 가능한 빈을 감지하고, `ProxyFactory.getProxy()`로 프록시 인스턴스를 만들어 그것을 컨테이너에 등록한다. 즉 `@Autowired`로 주입받는 객체는 원본이 아니라 프록시이다.

프록시 결정 분기는 `DefaultAopProxyFactory.createAopProxy()`에 있다.

```java
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
    if (!NativeDetector.inNativeImage()
            && (config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config))) {
        Class<?> targetClass = config.getTargetClass();
        if (targetClass == null) {
            throw new AopConfigException("TargetSource cannot determine target class: ...");
        }
        if (targetClass.isInterface() || Proxy.isProxyClass(targetClass) || ClassUtils.isLambdaClass(targetClass)) {
            return new JdkDynamicAopProxy(config);
        }
        return new ObjenesisCglibAopProxy(config);
    }
    return new JdkDynamicAopProxy(config);
}
```

분기 규칙은 다음과 같다. 첫째, `proxyTargetClass=true`거나 인터페이스 후보가 하나도 없으면 CGLIB. 둘째, 인터페이스가 하나라도 있고 강제 옵션이 없으면 JDK Dynamic Proxy. Spring Boot 2.0부터는 기본값이 `proxyTargetClass=true`라서 인터페이스가 있어도 CGLIB이 선택된다. JDK 프록시는 `java.lang.reflect.Proxy.newProxyInstance()`로 인터페이스만 구현한 동적 클래스를 생성하므로 캐스팅이 인터페이스 타입으로만 가능하다. CGLIB은 `Enhancer`로 대상 클래스의 서브클래스를 ASM 바이트코드로 생성해 메서드를 오버라이드하므로 final 클래스/메서드에서 실패하고, private/static 메서드는 오버라이드 자체가 불가능하다.

## 2. ProxyFactory의 호출 흐름과 Advisor Chain

프록시가 메서드 호출을 가로채면 `JdkDynamicAopProxy.invoke()` 또는 `CglibAopProxy.DynamicAdvisedInterceptor.intercept()`가 진입점이 된다. 거기서 `AdvisedSupport.getInterceptorsAndDynamicInterceptionAdvice()`가 메서드에 매칭되는 `MethodInterceptor` 리스트를 빌드한다. 반환된 체인은 `ReflectiveMethodInvocation.proceed()`가 인덱스를 증가시키며 재귀 호출하는 책임 연쇄(Chain of Responsibility) 형태로 실행된다.

```java
public Object proceed() throws Throwable {
    if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size() - 1) {
        return invokeJoinpoint();
    }
    Object interceptorOrInterceptionAdvice =
            this.interceptorsAndDynamicMethodMatchers.get(++this.currentInterceptorIndex);
    if (interceptorOrInterceptionAdvice instanceof InterceptorAndDynamicMethodMatcher dm) {
        if (dm.matcher().matches(this.method, this.targetClass, this.arguments)) {
            return dm.interceptor().invoke(this);
        } else {
            return proceed();
        }
    }
    return ((MethodInterceptor) interceptorOrInterceptionAdvice).invoke(this);
}
```

핵심은 `invokeJoinpoint()`이다. 이 메서드는 reflection으로 **원본 객체의 메서드**를 호출한다. 즉 일단 원본 메서드 본체에 진입하면, 그 안에서 `this.someOtherMethod()`로 다시 호출되는 메서드는 프록시를 거치지 않는다. 이것이 self-invocation이 어드바이스를 우회하는 정확한 메커니즘이다.

## 3. Self-Invocation 사고 재현

```java
@Service
public class OrderService {
    @Transactional
    public void placeOrder(Long userId) {
        validate(userId);
        chargePayment(userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void chargePayment(Long userId) {
        // 별도 트랜잭션을 기대했지만 ...
    }
}
```

기대는 `chargePayment()`가 새 트랜잭션에서 동작하는 것이지만, 실제로는 `placeOrder()` 트랜잭션에 합류한다. 이유는 `this.chargePayment(userId)`가 프록시가 아닌 원본 객체의 메서드를 직접 호출하기 때문이다. 프록시는 외부 호출자에게서만 보이는 래퍼이기 때문에 원본 객체 내부 `this`로는 절대 다시 통과되지 않는다. 결과적으로 `REQUIRES_NEW`는 무시되고, 결제 실패 시 주문 검증까지 함께 롤백되는 사고가 생긴다.

## 4. 우회 방법 비교

| 해결책 | 동작 원리 | 장점 | 단점 |
| --- | --- | --- | --- |
| 빈 분리 | `PaymentService`로 메서드를 옮겨 다른 빈으로 분리 | 가장 안전. 도메인 응집도 향상 | 리팩터링 비용 |
| `AopContext.currentProxy()` | 프록시를 ThreadLocal에 노출시켜 직접 캐스팅 | 빠른 핫픽스 | `exposeProxy=true` 필요, 리플렉션 의존, 테스트 복잡 |
| ApplicationContext 자기 주입 | 자기 자신의 프록시 빈을 `@Autowired`로 주입 | 명시적 | 순환 참조 경고, 가독성 저하 |
| `@Async`/`@Transactional` 자기 호출 금지 정적 분석 | ArchUnit 룰로 빌드 차단 | 사고 재발 방지 | 도구 도입 |
| AspectJ LTW | 컴파일 타임/로딩 타임 위빙 | self-invocation 정상 동작 | weaver agent 필요, 학습 곡선 |

자기 주입 패턴은 다음과 같이 작성한다.

```java
@Service
public class OrderService {
    @Lazy private final OrderService self;
    public OrderService(@Lazy OrderService self) { this.self = self; }

    @Transactional
    public void placeOrder(Long userId) {
        validate(userId);
        self.chargePayment(userId);   // 프록시 경유 → REQUIRES_NEW 정상 동작
    }
}
```

`@Lazy`가 없으면 컨테이너가 빈 초기화 단계에서 자기 자신을 주입하다 순환 참조로 실패한다.

## 5. AspectJ Load-Time Weaving 비교

AspectJ는 Spring AOP와 달리 클래스 바이트코드 자체를 수정한다. Compile-Time Weaving은 `ajc` 컴파일러가 `.class`에 직접 어드바이스 코드를 삽입하고, Load-Time Weaving은 JVM 시작 시 `-javaagent:aspectjweaver.jar`로 등록된 weaver가 클래스 로딩 시점에 변환한다. 결과 클래스는 자기 자신을 포함해 모든 호출 지점에서 어드바이스가 트리거된다. private 메서드, static 메서드, 생성자도 위빙 가능하다.

Spring Boot에서 `@EnableLoadTimeWeaving(aspectjWeaving=AUTODETECT)`를 켜고 `META-INF/aop.xml`을 작성하면 LTW가 활성화된다. 단점은 다음과 같다. 로딩 타임 변환 비용으로 콜드 스타트가 50~200ms 증가하고, 디버깅 시 BCI(byte code instrumentation)된 라인 번호와 소스가 어긋나며, 일부 GraalVM Native Image 빌드에서는 위빙된 클래스를 정적 분석으로 추적할 수 없어 reflection 힌트 파일을 추가로 작성해야 한다.

## 6. CGLIB 한계와 final 키워드 사고

CGLIB은 대상 클래스의 서브클래스를 만들기 때문에 final 클래스를 프록시할 수 없다. Kotlin은 모든 클래스가 기본 final이라 `kotlin-spring` 플러그인을 사용해 `@Service`, `@Controller` 같은 어노테이션 대상 클래스에 자동으로 `open` 키워드를 추가해야 한다. final 메서드는 오버라이드되지 않아 어드바이스가 조용히 무시된다. 이 케이스는 컴파일 타임 경고가 없어 운영 단계에서 발견되기 쉽다.

```kotlin
// build.gradle.kts
plugins {
    kotlin("plugin.spring") version "1.9.25"
}
```

플러그인 적용 후 빌드 결과 바이트코드를 `javap -p`로 확인하면 `final` 한정자가 제거된 것을 볼 수 있다.

## 7. 실측 — 프록시 오버헤드와 캐싱

프록시 호출은 reflection 1회 + interceptor chain 순회를 추가한다. JIT 컴파일 후 안정 상태에서 단순 메서드의 평균 호출 비용은 다음과 같다(JDK 21, x86_64, JMH 1.37, 5 fork × 10 warmup × 10 measurement).

| 호출 형태 | 평균 ns/op | 비고 |
| --- | --- | --- |
| 직접 호출 | 1.4 | baseline |
| JDK Dynamic Proxy | 11.2 | reflection lookup cache |
| CGLIB | 4.6 | FastClass index lookup |
| AspectJ Compile-Time | 1.8 | inline 수준 |

`@Transactional`처럼 무거운 어드바이스가 붙은 호출에서는 프록시 오버헤드가 전체의 5% 미만이라 일반적으로 문제가 되지 않지만, 도메인 객체의 setter처럼 초당 수백만 번 호출되는 경로에는 AOP를 적용하지 않는 게 좋다.

## 8. ArchUnit으로 self-invocation 차단

```java
@Test
void noSelfInvocationOfTransactional() {
    JavaClasses classes = new ClassFileImporter().importPackages("com.example.app");
    methods()
        .that().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
        .and().areAnnotatedWith(Transactional.class)
        .should(new ArchCondition<JavaMethod>("not be invoked from within the same class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getCallsOfSelf().forEach(call -> {
                    if (call.getOriginOwner().equals(method.getOwner())) {
                        events.add(SimpleConditionEvent.violated(call,
                            "self-invocation of @Transactional method: " + call.getDescription()));
                    }
                });
            }
        }).check(classes);
}
```

이 규칙을 CI 단계에 포함하면 `@Transactional`/`@Async`/`@Cacheable` 자기 호출 패턴이 머지되기 전에 차단된다.

## 9. 트러블슈팅 체크리스트

운영 환경에서 어드바이스가 동작하지 않는다고 의심될 때 확인할 항목은 정해져 있다. 첫째, `((Advised) bean).getProxiedInterfaces()` 또는 `bean.getClass().getName()`을 로깅해 프록시 여부를 확인. CGLIB 프록시는 `$$EnhancerByCGLIB$$` 또는 `$$SpringCGLIB$$` 접미사를 가진다. 둘째, 메서드가 public이고 final이 아닌지 확인. 셋째, 호출이 외부 빈에서 들어오는지 확인. 넷째, `@EnableTransactionManagement(proxyTargetClass=true)` 설정이 일관된지 확인. 다섯째, Kotlin 프로젝트라면 `kotlin-spring` 플러그인이 적용되었는지 확인.

## 참고

- Spring Framework Reference: Aspect Oriented Programming with Spring (docs.spring.io/spring-framework/reference/core/aop.html)
- AspectJ Programming Guide — Load-Time Weaving (eclipse.dev/aspectj/doc/released/devguide/ltw.html)
- Rod Johnson, "Expert One-on-One J2EE Design and Development", Chapter 8 — AOP
- JEP 335: Deprecate the Nashorn JavaScript Engine 외 Spring 6.x ProxyFactory 변경 노트
- Tomasz Nurkiewicz, "Reactive Programming with Java" 부록 D — Proxy Internals
