Notion 원본: https://www.notion.so/35b5a06fd6d3811da3bec345fd5315fa

# Spring AOP Proxy 메커니즘과 CGLIB JDK Dynamic Proxy 선택 전략

> 2026-05-09 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring AOP 가 어떤 클래스에 대해 JDK Dynamic Proxy 를, 어떤 클래스에 대해 CGLIB 서브클래싱을 선택하는지 결정 알고리즘을 따라간다
- `@Transactional`, `@Async`, `@Cacheable` 이 self-invocation 으로 깨지는 근본 원인을 프록시 객체 그래프 관점에서 설명한다
- Spring Boot 2.x 부터 기본값이 `proxy-target-class=true` 로 바뀐 배경, 그로 인해 final 메서드/클래스 제약이 어떻게 작용하는지 익힌다
- AspectJ Load-Time Weaving / Compile-Time Weaving 이 프록시 한계를 어떻게 우회하는지, 트레이드오프를 정리한다

## 1. AOP 가 동작하는 전제: 빈은 *프록시 인스턴스*

`@Service class OrderService { ... }` 에 `@Transactional` 메서드가 있다고 하자. 컨테이너 시작 시 `BeanPostProcessor` 중 `AnnotationAwareAspectJAutoProxyCreator` 가 빈 후처리 단계에서 *원본 인스턴스* 대신 *프록시 인스턴스* 를 컨테이너에 등록한다. 다른 빈에 주입되는 것은 프록시이고, 프록시가 메서드 호출을 가로채 어드바이스 체인(Around → Before → 본체 → After)을 구동한다.

이 사실에서 따라오는 결과:

- `this.someTxMethod()` 처럼 빈 *내부에서 자기 자신을 호출* 하면 프록시를 거치지 않으므로 트랜잭션이 시작되지 않는다 (self-invocation 문제).
- 빈 컬렉션에서 `getClass()` 는 프록시 클래스 이름을 반환한다.
- final 메서드는 CGLIB 가 override 할 수 없으므로 어드바이스가 적용되지 않는다.

## 2. 프록시 종류 결정 알고리즘 (Spring 5.x ~ 6.x)

`DefaultAopProxyFactory#createAopProxy(AdvisedSupport)` 가 다음 순서로 결정한다.

```java
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
    if (!NativeDetector.inNativeImage() &&
        (config.isOptimize() || config.isProxyTargetClass()
         || hasNoUserSuppliedProxyInterfaces(config))) {
        Class<?> targetClass = config.getTargetClass();
        if (targetClass.isInterface() || Proxy.isProxyClass(targetClass)
            || ClassUtils.isLambdaClass(targetClass)) {
            return new JdkDynamicAopProxy(config);
        }
        return new ObjenesisCglibAopProxy(config);
    }
    return new JdkDynamicAopProxy(config);
}
```

요약:

1. `proxyTargetClass=true` 이거나 *대상이 어떤 인터페이스도 구현하지 않으면* → CGLIB
2. 대상이 인터페이스이거나 이미 JDK 프록시 클래스이거나 람다이면 → JDK
3. 그 외 (인터페이스를 구현한 클래스이고 `proxyTargetClass=false`) → JDK

Spring Boot 2.x 부터 `spring.aop.proxy-target-class` 의 기본이 `true` 로 바뀌었다. 이전(Spring Boot 1.x)에는 인터페이스가 있으면 JDK 를 쓰는 게 기본이라, 인터페이스 변경 시 두 곳을 동시에 수정해야 하는 비용이 있었다. Boot 2.x 이후는 *항상 CGLIB* 가 기본이라 인터페이스 유무와 무관하게 동작이 일정하다.

## 3. JDK Dynamic Proxy 의 동작과 비용

`Proxy.newProxyInstance(loader, interfaces, handler)` 가 *런타임에* 프록시 클래스를 생성한다. 클래스 명은 `$Proxy0`, `$Proxy1` 같은 형태. 메서드 호출은 `InvocationHandler#invoke(Object proxy, Method method, Object[] args)` 로 디스패치된다.

장점: 표준 JDK API, GraalVM Native Image 가 reflection 메타데이터로 처리하기 쉬움 (Spring Native 에서 reachability 분석이 단순).

단점:
- 인터페이스 메서드만 가로챌 수 있다. 인터페이스에 없는 public 메서드는 어드바이스 적용 X
- `Method` 객체 디스패치 비용 (메서드 핸들 캐싱 + reflection.invoke). JIT 가 인라이닝하기 어려움
- 인터페이스로 캐스팅된 참조에서만 메서드 호출 가능. `OrderService` 가 `OrderServiceImpl` 라는 구현이라면 `@Autowired OrderService` 로 주입해야 함. `@Autowired OrderServiceImpl` 은 ClassCastException

## 4. CGLIB (Spring 6 부터 ASM-기반 자체 포크) 동작과 비용

CGLIB 는 *대상 클래스를 상속한 서브클래스* 를 바이트코드로 생성한다. Spring 5 까지 cglib-nodep 의존, Spring 6 부터는 spring-aop 모듈이 ASM 위에 포크한 cglib 을 내장한다(Java 21 클래스 파일 호환을 위해).

서브클래싱 방식이라 따라오는 제약:

- `final` 클래스: 상속 불가 → 프록시 생성 실패 (BeanCreationException)
- `final` 메서드: override 불가 → 어드바이스 적용 X (예외는 안 나지만 동작 안 함)
- private 메서드: override 불가 → 어드바이스 적용 X
- 생성자: 호출 시점이 까다로움. Spring 은 Objenesis 로 *생성자 호출 없이* 인스턴스화. 따라서 `@PostConstruct` 가 아닌 *생성자 본문 부수 효과* 는 프록시에서 발생하지 않을 수 있음

CGLIB 프록시 메서드 디스패치는 *직접 메서드 호출* 에 가깝게 컴파일되어 JIT 인라이닝에 유리하다. 그래서 메서드 호출이 매우 빈번한 경로에선 CGLIB 가 JDK Proxy 보다 빠른 경향이 측정된다(차이는 보통 5~15ns/호출 수준이라 비즈니스 코드에서 의미 있는 경우는 드물다).

## 5. self-invocation 문제 — 정확히 어떤 호출이 깨지는가

```java
@Service
public class OrderService {

    @Transactional
    public void outer() {
        innerNotTx();           // 이 호출은 프록시를 거치지 않는다
        this.innerTx();         // 이 호출도 프록시를 거치지 않는다
    }

    public void innerNotTx() { /* ... */ }

    @Transactional(propagation = REQUIRES_NEW)
    public void innerTx() {
        // 새 트랜잭션이 시작될 것이라 기대했지만, 시작되지 않는다
    }
}
```

`outer()` 가 호출되는 시점은 *외부 빈 → 프록시 → 본체* 경로. 본체 안에서 `this.innerTx()` 의 `this` 는 *원본 인스턴스* 이지 프록시가 아니라서, 어드바이스 체인이 다시 구동되지 않는다.

해결 패턴:

1. *분리*: `innerTx()` 를 다른 빈 `OrderTxOperations` 로 옮기고 `OrderService` 가 그 빈을 주입받아 호출. 가장 정석.
2. `AopContext.currentProxy()`: `@EnableAspectJAutoProxy(exposeProxy=true)` 켜고 `((OrderService) AopContext.currentProxy()).innerTx()` 로 호출. 권장하지 않음 — 빈 인스턴스에 대한 ThreadLocal 의존, 코드 가독성 저하.
3. AspectJ LTW/CTW: 프록시 없이 *바이트코드 위빙* 하므로 self-invocation 도 어드바이스가 걸린다. 운영 도입 비용이 크다.

## 6. AspectJ 로의 이행

Spring AOP 는 *프록시 기반 method-execution 조인포인트만* 지원한다. 필드 set/get, 생성자 실행, 정적 메서드, finally 블록 등은 잡지 못한다. 진짜 다양한 조인포인트가 필요하면 AspectJ 로 가야 한다.

```xml
<!-- AspectJ LTW: java -javaagent:aspectjweaver.jar 로 실행 -->
<context:load-time-weaver/>
```

또는 `aspectj-maven-plugin` 으로 빌드 시점에 `.class` 파일을 위빙(CTW). 위빙된 클래스는 더 이상 프록시 객체가 아니라 *원본 클래스 자체* 이며, `this` 호출도 어드바이스가 적용된다. 단점: 클래스 로딩이 살짝 느려지고, IDE 표시·디버깅이 까다로워진다. Spring 진영에서는 일반적으로 self-invocation 이 *재설계로 해결되는 신호* 라고 보고 AspectJ 도입은 신중을 기한다.

## 7. proxy-target-class 의 부작용

CGLIB 가 기본이 되면서 다음 케이스가 깨질 수 있다.

- Kotlin: 클래스가 기본 `final`. 빌드 플러그인 `kotlin-spring` 이 `@Component`/`@Service` 등을 `open` 으로 보정해주는 이유다. 미적용 시 BeanCreationException.
- Lombok `@FinalFieldsConstructorRequired` 류: 대상 클래스에 final 필드가 있는 건 OK. CGLIB 가 막히는 건 *클래스 자체가 final* 이거나 *인터셉트 대상 메서드가 final* 일 때.
- 일부 라이브러리(Guice, MapStruct 등)와 함께 *동일 빈에 다중 인스턴스화* 가 발생하면 프록시 캐시 키가 어긋날 수 있다.

## 8. 측정과 디버깅

```java
@Autowired ApplicationContext ctx;

@PostConstruct
void debug() {
    Object bean = ctx.getBean(OrderService.class);
    System.out.println(bean.getClass().getName());
    // ex) com.example.OrderService$$SpringCGLIB$$0
}
```

이름에 `$$SpringCGLIB$$` 가 들어가면 CGLIB, `$Proxy<숫자>` 가 들어가면 JDK. 어드바이스가 *적용 안 되는* 의심이 들면 다음을 확인.

1. `bean.getClass().getName()` 이 프록시 이름인가
2. 호출 메서드가 final/private/static 이 아닌가
3. self-invocation 인가 (스택 추적: `at OrderService.outer ... at OrderService.innerTx`)
4. `@EnableTransactionManagement(proxyTargetClass=...)` 가 아키텍처 의도와 일치하는가
5. `BeanFactoryPostProcessor` 가 프록시 생성 전에 빈을 *프록시 불가능한 형태로* 바꾸지 않았는가

| 시나리오 | 프록시 종류 | 어드바이스 적용 |
|---|---|---|
| 일반 클래스 + 인터페이스 + Boot 2.x 기본값 | CGLIB | O |
| 일반 클래스, no interface | CGLIB | O |
| `final` 클래스 | 생성 실패 | - |
| 메서드 `final` | CGLIB | X (동작 안 함) |
| Kotlin 기본 클래스(`open` 미적용) | 생성 실패 | - |
| `@Configuration` 클래스 자체 | CGLIB(기본) — `@Bean` 메서드 호출 가로채기 위해 | O |

## 9. 실측: Boot 3.x 기준 빈 100개 시나리오

`spring-boot-starter` 3.2 기반에서 `@Service` 100 개, 각 `@Transactional` 메서드 1개를 갖는 마이크로 벤치를 돌리면, JDK→CGLIB 전환 시 컨테이너 시작 시간 차이는 수십 ms 수준이다. 메서드 호출 자체의 오버헤드는 마이크로벤치에서 차이가 보이지만 실제 JDBC/HTTP IO 가 끼어드는 비즈니스 시나리오에서는 무시 가능. 그러므로 *성능* 보다 *예측 가능성* 측면에서 `proxyTargetClass=true` 의 일관성을 우선 가져가는 게 일반적인 선택이다.

## 참고

- Spring Framework Reference — AOP / Proxying Mechanisms
- Spring Framework 소스 — `DefaultAopProxyFactory`, `JdkDynamicAopProxy`, `CglibAopProxy`
- Spring Boot 2.0 Release Notes — proxyTargetClass default 변경
- AspectJ Programming Guide — Load-Time Weaving
- Kotlin all-open / kotlin-spring 플러그인 문서
- "Pro Spring 6" — AOP 챕터
