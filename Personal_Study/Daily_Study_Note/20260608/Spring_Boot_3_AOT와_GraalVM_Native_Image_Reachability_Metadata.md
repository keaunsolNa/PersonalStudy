Notion 원본: https://www.notion.so/3795a06fd6d381bd9df4f4acf43eee4f

# Spring Boot 3 AOT 처리와 GraalVM Native Image Reachability Metadata

> 2026-06-08 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring Boot 3 의 AOT(Ahead-Of-Time) 엔진이 빌드 타임에 BeanDefinition 을 어떻게 코드로 고정하는지 추적한다
- GraalVM Native Image 의 closed-world 가정과 reachability metadata(reflect/resource/proxy) 의 역할을 구분한다
- 리플렉션·프록시·리소스 접근을 `RuntimeHints` API 로 등록하는 방법을 손에 익힌다
- JIT(JVM) 대비 네이티브 이미지의 기동시간·메모리·피크 처리량 trade-off 를 실측 기준으로 판단한다

## 1. 왜 AOT 인가: closed-world 가정

GraalVM Native Image 는 애플리케이션을 단일 실행 파일로 AOT 컴파일한다. 핵심 제약은 **closed-world assumption** 이다. 빌드 시점에 도달 가능한(reachable) 모든 코드 경로가 정적 분석으로 결정되어야 하고, 그 분석에 잡히지 않은 클래스·메서드·필드는 최종 바이너리에서 제거된다(dead code elimination). JVM 에서 당연하던 "런타임에 클래스 이름 문자열로 `Class.forName()` 해서 인스턴스화" 같은 동적 동작은 정적 분석기가 추적할 수 없으므로, 별도 메타데이터로 "이건 살려둬라"를 명시하지 않으면 `ClassNotFoundException` 또는 `MissingReflectionRegistrationError` 로 죽는다.

Spring 은 본질적으로 동적이다. `@Configuration` 클래스를 런타임에 파싱하고, `@Conditional` 로 빈 등록을 분기하고, CGLIB 프록시를 동적으로 생성한다. 이 동적성을 네이티브 이미지의 정적 세계에 맞추기 위해 Spring Boot 3 는 **빌드 타임 AOT 처리** 단계를 도입했다. AOT 엔진은 애플리케이션 컨텍스트를 빌드 중에 한 번 "리허설"로 refresh 해서, 어떤 빈이 어떤 순서로 등록되는지를 결정하고 그 결과를 생성된 Java 소스 코드로 박제한다.

## 2. AOT 처리 파이프라인

`spring-boot-maven-plugin` 의 `process-aot` goal(또는 Gradle 의 `processAot` 태스크)이 실행되면 `SpringApplicationAotProcessor` 가 동작한다. 이 단계는 생성된 `*__BeanDefinitions.java` 소스와 reflect/resource/proxy-config.json 을 산출한다. 생성 코드의 핵심은 `ApplicationContextInitializer` 구현이다. 런타임 컴포넌트 스캔과 `@Configuration` 파싱 대신, 빌드 때 결정된 빈 등록을 명령형 코드로 재생한다.

```java
public class MyService__BeanDefinitions {
    public static BeanDefinition getMyServiceBeanDefinition() {
        RootBeanDefinition beanDefinition = new RootBeanDefinition(MyService.class);
        beanDefinition.setInstanceSupplier(MyService::new);
        return beanDefinition;
    }
}
```

런타임에는 이 초기화 코드가 곧바로 실행되므로 classpath 스캐닝, 애너테이션 파싱, 조건 평가가 사라진다. 이것이 네이티브 이미지뿐 아니라 일반 JVM 실행에서도 기동을 빠르게 만드는 부수 효과를 낳는다(`-Dspring.aot.enabled=true`). 중요한 함의는 **`@Conditional` 이 빌드 타임에 한 번 평가되어 고정된다**는 점이다. 빌드 환경의 프로파일·환경변수가 런타임과 다르면 빈 구성이 어긋난다.

## 3. Reachability Metadata 3종

정적 분석으로 잡히지 않는 동적 접근은 세 가지 JSON 으로 메타데이터화한다.

| 메타데이터 | 파일 | 해결 대상 |
|---|---|---|
| Reflection | reflect-config.json | 리플렉션 생성/필드/메서드 접근 |
| Resource | resource-config.json | classpath 리소스 로딩 |
| Proxy | proxy-config.json | JDK 동적 프록시 인터페이스 조합 |

GraalVM 은 `META-INF/native-image/` 경로의 이 파일들을 자동으로 인식한다. Spring 의 AOT 엔진은 자신이 등록하는 빈에 대한 메타데이터를 생성하지만, 애플리케이션 코드가 직접 리플렉션을 쓰면 개발자가 보강해야 한다.

## 4. RuntimeHints 로 직접 등록하기

```java
public class MyRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(OrderDto.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS);
        hints.resources().registerPattern("templates/*.json");
        hints.proxies().registerJdkProxy(PaymentGateway.class);
    }
}
```

등록한 Registrar 는 `@ImportRuntimeHints(MyRuntimeHints.class)` 로 연결한다. 직렬화/바인딩 대상만 콕 집으려면 `@RegisterReflectionForBinding(OrderDto.class)` 를 쓴다. Jackson 바인딩 DTO 누락이 네이티브에서 비는 가장 흔한 원인이다. `MemberCategory` 를 과하게 잡으면 바이너리 크기·빌드 시간이 늘고 dead-code 제거가 약해지므로 필요한 카테고리만 좁게 등록한다.

## 5. 빌드와 메타데이터 자동 수집(Tracing Agent)

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/app.jar
```

agent 는 실제로 실행된 경로만 기록하므로 테스트 커버리지가 곧 메타데이터 커버리지다. 분기 하나를 안 타면 그 경로의 리플렉션은 누락되어 운영에서 터진다. agent 산출물은 출발점일 뿐 conditional·예외 경로를 사람이 검수해야 한다. 네이티브 이미지 빌드는 `./mvnw -Pnative native:compile` 또는 `spring-boot:build-image` 로 수행한다.

## 6. JIT vs Native: 실측 trade-off

| 지표 | JVM(JIT, C2) | Native Image |
|---|---|---|
| 기동 시간 | 2~4 s | 0.05~0.1 s |
| 워밍업 후 피크 처리량 | 기준(100%) | 약 70~95% |
| RSS(유휴 메모리) | 250~400 MB | 60~130 MB |
| 빌드 시간 | 수십 초 | 수 분 |
| 최대 GC 처리량 | 높음(G1/ZGC) | 제한적 |

JIT 는 런타임 프로파일을 보고 핫패스를 재최적화하므로 장시간 구동되는 높은 처리량 서비스에서 네이티브보다 빠른 피크 성능을 낸다. 네이티브 이미지는 빠른 기동과 낮은 메모리가 결정적인 환경(서버리스, FaaS, 스케일-투-제로, CLI)에 압도적으로 유리하다. PGO(`--pgo-instrument` → `--pgo`)로 격차를 좁힐 수 있으나 빌드가 2-pass 로 복잡해진다.

## 7. 흔한 실패와 디버깅

가장 빈번한 런타임 실패는 `MissingReflectionRegistrationError` 와 리소스 null 반환이다. `-H:+PrintAnalysisCallTree` 로 reachability 를 추적한다. 빌드 타임에 무거운 초기화가 발생하면 `--initialize-at-run-time` 으로 미루고, 상수 폴딩 가능한 초기화는 `--initialize-at-build-time` 으로 당긴다. 이 분류 실수가 빌드는 되는데 런타임 상태가 비는 미묘한 버그를 만든다.

## 8. BeanFactoryInitializationAotProcessor 와 라이브러리 확장

라이브러리가 빈 등록 방식을 AOT 에 알리려면 `BeanRegistrationAotProcessor`(개별 빈)와 `BeanFactoryInitializationAotProcessor`(팩토리 전체)를 구현한다. `META-INF/spring/aot.factories` 에 등록되며 `processAheadOfTime` 가 contribution 을 반환한다.

```java
public class MetricsAotProcessor implements BeanFactoryInitializationAotProcessor {
    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        return (generationContext, code) -> {
            RuntimeHints hints = generationContext.getRuntimeHints();
            hints.reflection().registerType(MetricsBinder.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        };
    }
}
```

이 덕분에 Spring Data·Security·Batch 가 자신의 동적 동작에 필요한 힌트를 자동 기여한다. 사내 공통 라이브러리가 리플렉션·프록시를 쓴다면 이 패턴으로 힌트를 동봉하는 것이 소비 측 부담을 없애는 정석이다.

## 9. AOT 코드의 테스트 전략

AOT 처리는 빌드 타임 리허설이므로 네이티브 이미지를 빌드하지 않고 JVM 위에서 검증할 수 있다. `java -Dspring.aot.enabled=true -jar app.jar` 로 AOT 산출물이 정상 부팅하는지(빈 누락·조건 분기 어긋남) 먼저 확인하고, 그다음 네이티브 빌드로 리플렉션·리소스 누락을, 마지막으로 부하 테스트로 처리량·메모리를 JVM 기준선과 비교한다. 빌드 시간이 큰 비용이므로 가능한 많은 문제를 JVM AOT 단계에서 선제적으로 거르는 것이 핵심 규율이다.

## 참고

- Spring Boot Reference: GraalVM Native Image Support
- GraalVM Native Image Documentation — Reachability Metadata
- Spring Framework: `RuntimeHints` / `RuntimeHintsRegistrar` API 문서
- GraalVM Reachability Metadata Repository (oracle/graalvm-reachability-metadata)
- Profile-Guided Optimizations (PGO) — GraalVM 문서
