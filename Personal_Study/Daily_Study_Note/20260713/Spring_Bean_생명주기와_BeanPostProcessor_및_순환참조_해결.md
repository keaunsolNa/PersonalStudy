Notion 원본: https://app.notion.com/p/39c5a06fd6d381dda93ecfe263a93590

# Spring Bean 생명주기와 BeanPostProcessor 및 순환 참조 해결

> 2026-07-13 신규 주제 · 확장 대상: Spring

## 학습 목표

- 컨테이너가 빈을 인스턴스화·의존성 주입·초기화하는 단계별 순서를 정확히 나열한다
- `BeanPostProcessor`가 AOP 프록시·`@Autowired` 처리에 개입하는 지점을 분해한다
- 3-level 캐시가 순환 참조를 어떻게 해소하고 어디서 실패하는지 이해한다
- 생성자 주입에서 순환 참조가 왜 반드시 실패하는지와 올바른 설계 대안을 정리한다

## 1. 빈 생명주기 큰 그림

```
1. BeanDefinition 로딩
2. 인스턴스화 (생성자 호출) — createBeanInstance
3. 프로퍼티 채우기 (의존성 주입) — populateBean
4. Aware 콜백
5. BeanPostProcessor.postProcessBeforeInitialization
6. 초기화 (@PostConstruct -> afterPropertiesSet -> init-method)
7. BeanPostProcessor.postProcessAfterInitialization  <- AOP 프록시 생성
8. 사용 가능 (싱글턴 풀 등록)
9. 소멸 (@PreDestroy -> destroy -> destroy-method)
```

핵심은 인스턴스화(2)와 초기화(6)가 별개 단계라는 점, AOP 프록시가 초기화 이후인 7단계에서 원본을 감싼다는 점이다.

## 2. 인스턴스화와 의존성 주입의 분리

```java
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {
  BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args); // 생성자
  Object bean = instanceWrapper.getWrappedInstance();
  addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean)); // 조기 노출
  populateBean(beanName, mbd, instanceWrapper);      // @Autowired 주입
  Object exposedObject = initializeBean(beanName, bean, mbd); // 초기화 + 후처리
  return exposedObject;
}
```

생성자 주입은 `createBeanInstance` 단계에서 완결되어야 하므로 인자 빈이 먼저 완성돼야 한다. 필드/세터 주입은 `populateBean` 단계라 인스턴스는 이미 존재하고 참조만 나중에 채운다. 이 시점 차이가 순환 참조 해소 가능 여부를 가른다.

## 3. BeanPostProcessor: 컨테이너 확장 훅

```java
public interface BeanPostProcessor {
  default Object postProcessBeforeInitialization(Object bean, String beanName) { return bean; }
  default Object postProcessAfterInitialization(Object bean, String beanName) { return bean; }
}
```

`AutowiredAnnotationBeanPostProcessor`는 `@Autowired` 주입을, `CommonAnnotationBeanPostProcessor`는 `@PostConstruct`를 처리한다. AOP는 `AnnotationAwareAspectJAutoProxyCreator`가 `postProcessAfterInitialization`에서 원본을 프록시로 교체한다. 반환값이 원본과 다른 프록시라는 점이 중요하다. 다른 빈이 이 빈을 주입받을 때 프록시를 받아야 트랜잭션·캐시 어드바이스가 동작한다.

## 4. 3-level 캐시: 순환 참조 해소

| 캐시 | 이름 | 담는 것 |
|---|---|---|
| 1차 | singletonObjects | 완성된 싱글턴 |
| 2차 | earlySingletonObjects | 조기 노출된 미완성 객체 |
| 3차 | singletonFactories | 조기 참조를 만드는 ObjectFactory |

필드 주입 순환 A<->B: A 생성 후 A의 ObjectFactory를 3차에 등록, A.populateBean에서 B 필요 -> B 생성, B.populateBean에서 A 필요 -> 3차 캐시의 A 팩토리 실행해 early A 반환(2차 승격), B는 early A로 초기화 완료(1차 등록), A는 완성된 B를 주입받아 완료.

3차 캐시가 `ObjectFactory`(지연 실행)인 이유는 AOP 때문이다. `getEarlyBeanReference` 호출 순간 필요하면 프록시를 미리 만들어 순환 상대에게 원본이 아니라 프록시를 넘긴다. 단순 참조였다면 B가 원본 A를 들고, 나중에 A가 프록시로 교체돼도 B의 참조는 원본에 남아 트랜잭션이 누락된다.

## 5. 생성자 주입 순환 참조는 왜 반드시 실패하는가

```java
@Component class A { A(B b) { } }
@Component class B { B(A a) { } }
```

A를 만들려면 완성된 B가, B를 만들려면 완성된 A가 필요하다. 어느 것도 먼저 존재할 수 없어 3차 캐시에 조기 노출할 raw 객체 자체가 안 만들어진다. 결과는 `BeanCurrentlyInCreationException`이다. Spring Boot 2.6부터 순환 참조를 기본 금지한다(`spring.main.allow-circular-references=false`).

## 6. 순환 참조의 올바른 해결

중간 계층 추출이 가장 근본적이다. A와 B의 공통 로직을 제3의 C로 빼면 A->C, B->C 단방향이 된다. 또는 한쪽을 `ApplicationEventPublisher`로 대체해 컴파일 타임 의존을 끊는다.

```java
@Component
class OrderService {
  private final ApplicationEventPublisher publisher;
  OrderService(ApplicationEventPublisher publisher) { this.publisher = publisher; }
  void placeOrder(Order order) {
    publisher.publishEvent(new OrderPlacedEvent(order.getId()));
  }
}
```

최후 수단으로만 `@Lazy`를 쓴다. 의존성이 프록시로 주입되고 실제 사용 시점에 초기화되지만, 프록시 오버헤드와 지연 초기화 예외라는 새 위험이 생긴다.

## 7. 초기화 콜백 순서와 흔한 실수

초기화 훅 순서는 `@PostConstruct` -> `InitializingBean.afterPropertiesSet()` -> `@Bean(initMethod)`이다. 생성자 시점에는 주입된 빈이 아직 초기화 전(early reference)일 수 있으므로, 의존 빈 상태에 의존하는 로직은 `@PostConstruct`로 미룬다.

```java
@Component
class CacheWarmer {
  private final CatalogRepository repository;
  CacheWarmer(CatalogRepository repository) { this.repository = repository; }
  @PostConstruct
  void warmUp() { repository.loadAll(); }
}
```

## 8. 정리

Spring 빈 문제 대부분은 언제 무엇이 완성되는가를 오해한 데서 온다. 인스턴스화와 초기화는 분리되고, AOP 프록시는 초기화 후 후처리기에서 생성되며, 순환 참조는 3차 캐시가 필드/세터 주입에 한해서만 끊는다. 생성자 주입은 순환을 못 끊는 대신 불변성과 명확한 의존 그래프를 얻으므로, 이를 기본으로 삼되 순환이 생기면 설정으로 뚫지 말고 설계를 나눈다.

## 참고

- Spring Framework Reference: The IoC Container - Bean Lifecycle, BeanPostProcessor
- spring-framework 소스: AbstractAutowireCapableBeanFactory, DefaultSingletonBeanRegistry
- Spring Boot 2.6 릴리스 노트: Circular References Prohibited by Default
- Spring Reference: AOP - Proxying Mechanisms
