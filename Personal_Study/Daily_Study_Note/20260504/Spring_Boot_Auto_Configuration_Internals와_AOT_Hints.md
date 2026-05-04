Notion 원본: https://www.notion.so/3565a06fd6d381399025efdcbc26324a

# Spring Boot Auto-configuration Internals와 AOT Hints — @AutoConfiguration 처리 흐름

> 2026-05-04 신규 주제 · 확장 대상: Spring AOP Internals, Spring Modulith

## 학습 목표

- Spring Boot 3 의 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 가 부트스트랩 시점에 어떻게 로드되어 ApplicationContext 에 들어가는지 흐름을 단계별로 정리한다
- `@AutoConfiguration`, `@Conditional`, `@AutoConfigureBefore/After`, `@AutoConfigureOrder` 가 결정 트리에 미치는 영향을 식별한다
- `AutoConfigurationImportSelector` → `ConditionEvaluationReport` → `BeanDefinitionRegistry` 까지 코드 레벨 진입점을 추적해 디버깅 포인트를 만든다
- AOT(Native Image) 컴파일에서 자동 설정이 어떻게 *런타임 평가에서 빌드타임 평가로* 옮겨가는지, 그리고 RuntimeHints 등록을 사용자 코드에서 어떻게 보강하는지 결정한다

## 1. spring.factories 시대와 .imports 시대의 차이

Spring Boot 2 까지의 자동 설정 진입점은 `META-INF/spring.factories` 였다.

```
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration,\
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
...
```

이 단일 키 아래 수십 개의 클래스명이 줄로 이어져 있었고, 키-값 형식이 일반 `Properties` 파서로 다뤄졌기 때문에 빌드 도구의 처리(머지, AOT 분석)가 까다로웠다. Spring Boot 2.7 부터 새 위치 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 가 도입됐고, Boot 3 에서는 이쪽이 표준이다.

```
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
org.example.MyAutoConfiguration
org.example.OtherAutoConfiguration
```

값마다 한 줄이고, `#` 로 주석을 달 수 있다. 이 단순함 덕분에 GraalVM AOT 단계에서 import 목록을 정적 파일로 바로 읽어 SubstrateVM 에 클래스 등록 힌트를 만들 수 있다.

라이브러리를 자동 설정 가능하게 만드는 절차도 단순해졌다. `@AutoConfiguration` 을 붙인 클래스를 작성하고, 위 파일에 정규화된 클래스명을 한 줄 적어 두면 끝이다. `@AutoConfiguration` 자체는 `@Configuration(proxyBeanMethods = false)` + `@AutoConfigureBefore/After` 메타데이터를 합친 합성 어노테이션이다.

## 2. SpringApplication 부트스트랩과 import 시점

`SpringApplication.run` 호출 흐름에서 자동 설정이 들어오는 위치는 다음과 같다.

```
SpringApplication.run
└─ refreshContext
   └─ ConfigurableApplicationContext.refresh
      └─ invokeBeanFactoryPostProcessors
         └─ ConfigurationClassPostProcessor.processConfigBeanDefinitions
            ├─ ConfigurationClassParser.parse
            │  └─ @Import 처리
            │     └─ AutoConfigurationImportSelector.selectImports
            │        ├─ getCandidateConfigurations  ← .imports 파일 로드
            │        ├─ removeDuplicates
            │        ├─ getExclusions / sort       ← @AutoConfigureBefore/After
            │        └─ filter                     ← @ConditionalOn... 평가
            └─ loadBeanDefinitions
```

핵심은 `AutoConfigurationImportSelector` 가 *지연 import selector* 라는 점이다. 즉 사용자가 `@SpringBootApplication` 의 `@EnableAutoConfiguration` 을 통해 명시한 자동 설정 후보들이, 일반 `@Configuration` 들이 모두 처리된 *뒤* 마지막에 한 번에 import 된다. 사용자 정의 빈이 자동 설정 빈보다 우선권을 가지는 메커니즘의 실체다.

`ConditionEvaluationReport` 는 자동 설정 후보 각각에 대해 (1) 어떤 조건이 평가됐고 (2) match/no-match 였는지를 누적한다. `--debug` 플래그로 Boot 를 띄우면 부팅 마지막에 이 리포트를 출력한다. 흔히 "왜 내 빈이 등록 안 됐지" 의 답이 여기 있다.

## 3. @Conditional 어노테이션 결정 트리

자동 설정의 정밀도는 `@ConditionalOn...` 들의 조합으로 결정된다. 자주 쓰이는 것들의 의미와 평가 시점은 다음과 같다.

| 어노테이션 | 평가 시점 | 의미 |
|---|---|---|
| `@ConditionalOnClass` | parse 단계 | 해당 클래스가 classpath 에 있을 때만 import |
| `@ConditionalOnMissingClass` | parse 단계 | 반대 |
| `@ConditionalOnBean` | register 단계 | 특정 타입의 빈이 *이미* 등록돼 있을 때 |
| `@ConditionalOnMissingBean` | register 단계 | 반대. 사용자 빈 우선권의 핵심 |
| `@ConditionalOnProperty` | parse 단계 | properties 값이 특정 값일 때 |
| `@ConditionalOnWebApplication` | parse 단계 | servlet/reactive 환경 매칭 |
| `@ConditionalOnExpression` | parse 단계 | SpEL 평가 결과로 분기 |

`parse 단계` 와 `register 단계` 의 구분이 중요하다. `@ConditionalOnBean` / `@ConditionalOnMissingBean` 은 *모든 자동 설정이 등록을 마친 직후* 한 번 더 평가된다. 그래서 자동 설정 클래스 안에서만 `@Bean` 메서드 단위로 사용해야 안정적이고, 클래스 레벨에 붙이면 평가 시점에 BeanDefinition 이 아직 안 만들어진 다른 자동 설정의 빈을 못 봐서 의도한 것과 반대로 동작한다.

## 4. @AutoConfigureBefore/After 와 위상 정렬

자동 설정 사이에 의존성이 있을 때(예: DataSourceAutoConfiguration → JpaRepositoriesAutoConfiguration), `@AutoConfigureAfter` 는 *내가 늦게 올라가야 한다* 는 사실을 정적으로 선언한다. 평가는 `AutoConfigurationSorter` 가 위상 정렬로 처리한다.

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(EntityManager.class)
@ConditionalOnBean(DataSource.class)
public class JpaRepositoriesAutoConfiguration {
    // ...
}
```

위 두 어노테이션의 역할을 분리해서 봐야 한다. `after = DataSourceAutoConfiguration.class` 는 *순서* 만 보장한다. DataSource 가 조건 미달로 안 올라와도 위상 정렬 자체에는 영향이 없다. 실제 의존은 `@ConditionalOnBean(DataSource.class)` 가 책임진다. 둘이 같이 쓰여야 안전하게 동작한다.

`@AutoConfigureOrder` 는 정수 우선순위로 더 거친 정렬을 강제한다. `Ordered.HIGHEST_PRECEDENCE` 일수록 먼저 평가된다. 같은 후보 그룹 안에서 `Before/After` 보다 우선한다.

## 5. ConditionEvaluationReport 디버깅 활용

부팅이 *되긴 하지만* 의도와 다른 빈이 들어가 있을 때 사용자가 찾아보는 첫 번째 자료가 ConditionEvaluationReport 다. 코드에서 직접 꺼내올 수도 있다.

```java
@Component
public class StartupReportLogger implements ApplicationListener<ApplicationReadyEvent> {

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    var ctx = event.getApplicationContext();
    var report = ConditionEvaluationReport.get((ConfigurableListableBeanFactory)
        ((ConfigurableApplicationContext) ctx).getBeanFactory());

    report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
      var matched = outcomes.isFullMatch();
      log.info("AutoConfig {} -> {}", source, matched ? "MATCH" : "NO");
      outcomes.forEach(o -> log.debug("  {} : {}", o.getCondition(), o.getOutcome()));
    });
  }
}
```

이 리포트를 production 환경에서 INFO 로 항상 출력하지는 말되, 신규 의존성이 들어왔을 때 한 번씩 enable 해서 *어떤 자동 설정이 새로 활성화됐는지* 확인하는 용도로 두면 의도하지 않은 빈 등록을 빠르게 감지할 수 있다.

## 6. AOT 처리에서 자동 설정의 변환

GraalVM Native Image 빌드(또는 Spring Boot 의 `process-aot` 단계)에서는 자동 설정의 *런타임 평가* 가 *빌드타임 평가* 로 옮겨간다. 핵심 변화 두 가지.

첫째, `@Conditional` 평가가 빌드 시점에 *한 번* 수행되어 결과가 정적으로 코드 생성된다. AOT 컴파일러는 후보 자동 설정 각각에 대해 조건을 평가하고, 매치된 빈만 코드로 emit 한다. 런타임에 classpath 가 바뀌어도 이 결과는 고정된다.

둘째, 결과로 emit 되는 코드는 BeanDefinition 등록을 직접 호출하는 *생성된 ApplicationContextInitializer* 형태다. 일반 Boot 의 reflection 기반 빈 등록을 우회한다. 그래서 시작 시간이 수 초 → 수십 ms 로 줄어든다.

```java
// AOT 가 생성하는 코드(개념적 예시)
public class GeneratedApplicationContextInitializer
    implements ApplicationContextInitializer<GenericApplicationContext> {

  @Override
  public void initialize(GenericApplicationContext ctx) {
    BeanDefinitionRegistrar.of("dataSource", DataSource.class)
      .instanceSupplier(MyAutoConfiguration::dataSource)
      .register(ctx);
    // ... 매치된 모든 빈 등록
  }
}
```

런타임에 조건이 바뀔 가능성이 있는 코드(예: `@ConditionalOnExpression` 의 SpEL 결과가 환경변수에 의존)는 AOT 와 충돌한다. 이런 경우 자동 설정을 둘로 쪼개거나 `@Profile` 로 빌드 시점에 결정 가능한 분기로 바꿔야 한다.

## 7. RuntimeHints 로 Native Image 보강

라이브러리가 자체 자동 설정을 제공하면서 Native 환경을 지원하려면 *reflection · resource · proxy · serialization* 힌트를 등록해야 한다. Spring Boot 3 는 `RuntimeHintsRegistrar` 인터페이스로 이걸 표준화했다.

```java
public class MyHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection()
        .registerType(MyEntity.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS);

    hints.resources().registerPattern("META-INF/my-config/*.json");

    hints.proxies()
        .registerJdkProxy(MyApi.class, AutoCloseable.class);
  }
}

@AutoConfiguration
@ImportRuntimeHints(MyHints.class)
public class MyAutoConfiguration { /* ... */ }
```

`@ImportRuntimeHints` 가 자동 설정 클래스에 붙어 있으면 AOT 단계에서 힌트가 자동 수집된다. 잘못된 힌트는 런타임에 `ClassNotFoundException` / `MissingResourceException` 으로 폭발하므로, Native 빌드를 CI 에 포함시켜 빠르게 검증해야 한다.

흔한 실수는 *Jackson 직렬화 대상 DTO* 의 등록을 빠뜨리는 것이다. Boot 가 컨트롤러 응답 DTO 는 자동으로 등록해 주지만, `RestTemplate` / WebClient 로 외부 API 응답을 매핑하는 DTO 는 자동 감지가 어려워 명시적으로 `RegisterReflectionForBinding` 또는 위 `RuntimeHints` 로 등록해야 한다.

## 8. 자동 설정을 새로 만들 때의 권장 절차

자체 라이브러리를 자동 설정 가능하게 만들 때의 권장 패턴은 다음과 같다.

```java
@AutoConfiguration
@ConditionalOnClass(MyClient.class)
@EnableConfigurationProperties(MyProperties.class)
public class MyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MyClient myClient(MyProperties props) {
    return MyClient.builder()
        .endpoint(props.endpoint())
        .timeout(props.timeout())
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "my", name = "metrics.enabled", havingValue = "true",
                         matchIfMissing = true)
  public MyMetrics myMetrics(MeterRegistry registry, MyClient client) {
    return new MyMetrics(registry, client);
  }
}

@ConfigurationProperties(prefix = "my")
public record MyProperties(String endpoint, Duration timeout) {}
```

함께 등록할 파일:

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└─ org.example.my.MyAutoConfiguration

src/main/resources/META-INF/additional-spring-configuration-metadata.json
└─ my.endpoint, my.timeout, my.metrics.enabled 메타데이터 정의
```

세 가지 원칙: (1) 모든 빈에 `@ConditionalOnMissingBean` 을 붙여 사용자 오버라이드를 허용한다. (2) `@ConfigurationProperties` 는 `@EnableConfigurationProperties` 로 자동 등록 받는다. (3) 사용자가 IDE 자동완성을 받을 수 있도록 metadata json 을 직접 작성하거나 `spring-boot-configuration-processor` 를 의존에 넣어 빌드 시점에 자동 생성한다.

## 참고

- Spring Boot Reference — Auto-configuration: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration
- Spring Boot Reference — Ahead of Time Optimizations: https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html
- Spring Framework — RuntimeHints API: https://docs.spring.io/spring-framework/reference/core/aot.html
- Spring Boot 2.7 Release Notes — AutoConfiguration.imports: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.7-Release-Notes
- ConditionEvaluationReport JavaDoc: https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/autoconfigure/condition/ConditionEvaluationReport.html
