Notion 원본: https://www.notion.so/39f5a06fd6d3816cb2b2f3e364c6d6bc

# Spring Boot Auto Configuration과 조건부 평가 순서 및 커스텀 스타터 설계

> 2026-07-16 신규 주제 · 확장 대상: Spring

## 학습 목표

- `AutoConfiguration.imports` 로드부터 빈 등록까지의 단계를 추적한다
- `@Conditional` 평가가 두 페이즈로 나뉘는 이유를 설명한다
- 자동 설정 순서 제어 어노테이션 셋의 차이를 구분한다
- 사내 공통 모듈을 스타터로 패키징한다

## 1. 자동 설정은 결국 조건부 `@Bean` 이다

Spring Boot 의 자동 설정은 마법이 아니다. 핵심은 세 줄로 요약된다. 클래스패스에 있는 jar 들이 자기 자신을 등록할 설정 클래스 목록을 파일로 선언하고, Boot 가 그 목록을 전부 읽은 뒤, 각 설정 클래스에 붙은 조건을 평가해 통과한 것만 빈으로 등록한다.

`@SpringBootApplication` 은 세 어노테이션의 합성이다.

```java
@SpringBootConfiguration
@ComponentScan
@EnableAutoConfiguration
public @interface SpringBootApplication { }
```

이 중 `@EnableAutoConfiguration` 이 `AutoConfigurationImportSelector` 를 임포트한다. 이 셀렉터가 전 과정의 시작점이다.

```java
@AutoConfigurationPackage
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration { }
```

`AutoConfigurationImportSelector` 는 `DeferredImportSelector` 를 구현한다. "deferred" 가 중요하다. 일반 `ImportSelector` 는 `@Configuration` 파싱 도중 즉시 처리되지만, deferred 는 **사용자 정의 설정을 전부 처리한 뒤 맨 마지막에** 실행된다. 이 순서 덕분에 `@ConditionalOnMissingBean` 이 의미를 갖는다. 사용자가 직접 만든 빈이 먼저 등록돼 있어야 "없으면 만든다" 가 성립한다.

## 2. 목록 로딩 — spring.factories 에서 AutoConfiguration.imports 로

Boot 2.7 이전에는 `META-INF/spring.factories` 를 썼다.

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.example.FooAutoConfiguration,\
com.example.BarAutoConfiguration
```

Boot 2.7 에서 새 방식이 도입되고 3.0 에서 구 방식이 제거됐다.

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

```
com.example.FooAutoConfiguration
com.example.BarAutoConfiguration
# 주석 지원, 한 줄에 하나
```

바뀐 이유는 성능과 명확성이다. `spring.factories` 는 여러 종류의 확장점을 한 파일에 섞어 담아 파싱 후 필터링해야 했고, 이스케이프된 백슬래시 연속 문법이 오류를 유발했다. 새 형식은 자동 설정 전용이며 한 줄에 하나라 파싱이 단순하다.

로딩 순서는 다음과 같다.

```
1. ImportCandidates.load(AutoConfiguration.class, classLoader)
   → 클래스패스의 모든 jar 에서 AutoConfiguration.imports 를 수집 (150개 내외)
2. removeDuplicates()
3. getExclusions() → spring.autoconfigure.exclude 프로퍼티, @SpringBootApplication(exclude=...) 반영
4. filter(configurations, autoConfigurationMetadata)   ← 1차 필터 (§3)
5. fireAutoConfigurationImportEvents()
6. sort()                                              ← 순서 결정 (§4)
```

`spring-boot-autoconfigure` 하나만 해도 자동 설정 클래스가 150개를 넘는다. 이걸 전부 로드해서 조건을 평가하면 기동이 느려진다. 4단계 필터가 그 문제를 푸는다.

## 3. 조건 평가의 두 페이즈

조건 평가는 두 번 일어난다. 이 구조를 모르면 `@Conditional` 이 왜 어떤 때는 먹고 어떤 때는 안 먹는지 이해할 수 없다.

**페이즈 1 — AutoConfigurationImportFilter (클래스 로드 전)**

`spring-boot-autoconfigure` jar 안에는 `META-INF/spring-autoconfigure-metadata.properties` 가 들어 있다. 빌드 시 애노테이션 프로세서가 생성한 파일이다.

```properties
com.example.FooAutoConfiguration.ConditionalOnClass=com.example.FooClient
com.example.FooAutoConfiguration.AutoConfigureAfter=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

Boot 는 이 메타데이터만 읽고 `OnClassCondition`, `OnBeanCondition`, `OnWebApplicationCondition` 세 필터를 돌린다. **자동 설정 클래스를 로드하지 않고** 문자열 비교만으로 후보를 걸러낸다. `FooClient` 가 클래스패스에 없으면 `FooAutoConfiguration` 은 바이트코드조차 읽히지 않는다.

`OnClassCondition` 은 CPU 코어가 2개 이상이면 후보 목록을 절반으로 나눠 별도 스레드에서 병렬 평가한다. 실측상 이 필터 하나가 기동 시간의 상당 부분을 줄인다. 150개 후보 중 실제 통과하는 것은 보통 20~30개다.

**페이즈 2 — ConditionEvaluator (클래스 로드 후)**

1차를 통과한 클래스가 `@Configuration` 파싱 대상이 되면, `ConfigurationClassParser` 가 클래스 레벨과 메서드 레벨의 모든 `@Conditional` 을 평가한다. 여기서 실제 `BeanDefinition` 조회가 일어난다.

```java
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)              // 1차 + 2차 모두
@ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true")
public class CacheAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean                          // 2차만
	public CacheManager cacheManager(RedisConnectionFactory cf) {
		return RedisCacheManager.builder(cf).build();
	}
}
```

`ConfigurationCondition` 인터페이스는 평가 시점을 지정한다.

| ConfigurationPhase | 시점 | 대표 조건 |
|---|---|---|
| `PARSE_CONFIGURATION` | 설정 클래스 파싱 중 | `OnClassCondition`, `OnPropertyCondition` |
| `REGISTER_BEAN` | 빈 정의 등록 시 | `OnBeanCondition` |

`OnBeanCondition` 이 `REGISTER_BEAN` 인 이유는 명확하다. "이 타입의 빈이 이미 있는가" 를 물으려면 다른 빈들이 먼저 등록돼 있어야 한다. 파싱 단계에서 물으면 아직 아무것도 없다.

**여기서 가장 흔한 함정이 나온다.**

```java
// 위험 — @Configuration 클래스 레벨의 @ConditionalOnMissingBean
@AutoConfiguration
@ConditionalOnMissingBean(DataSource.class)  // 언제 평가되는지 예측 불가
public class MyDataSourceAutoConfiguration { }

// 안전 — @Bean 메서드 레벨
@AutoConfiguration
public class MyDataSourceAutoConfiguration {
	@Bean
	@ConditionalOnMissingBean
	public DataSource dataSource() { ... }
}
```

클래스 레벨 `@ConditionalOnMissingBean` 은 그 설정 클래스가 파싱되는 시점에 등록된 빈만 볼 수 있다. 아직 파싱되지 않은 다른 설정 클래스가 나중에 `DataSource` 를 등록할 수 있고, 그러면 빈이 두 개가 된다. 공식 문서가 "`@ConditionalOnMissingBean` 은 반드시 `@Bean` 메서드에만 붙여라" 라고 못 박는 이유다.

`@ConditionalOnMissingBean` 은 파라미터 없이 쓰면 **메서드 반환 타입**을 대상으로 삼는다. 위 예에서 `DataSource` 타입 빈이 없을 때만 등록된다는 뜻이다.

## 4. 순서 제어 — 세 어노테이션의 차이

자동 설정 순서는 세 가지로 제어한다. 혼동이 잦은 지점이다.

| 어노테이션 | 대상 | 의미 | 강제성 |
|---|---|---|---|
| `@AutoConfigureBefore` | 자동 설정 클래스 | 지정 클래스보다 먼저 | 클래스패스에 없으면 무시 |
| `@AutoConfigureAfter` | 자동 설정 클래스 | 지정 클래스보다 나중 | 클래스패스에 없으면 무시 |
| `@AutoConfigureOrder` | 자동 설정 클래스 | 절대 순서값 | Before/After 보다 약함 |

정렬 알고리즘은 `AutoConfigurationSorter` 에 있고 세 단계다.

```
1. 알파벳순 정렬          — 결정론적 기준선 확보
2. @AutoConfigureOrder 로 재정렬
3. @AutoConfigureBefore/After 로 위상 정렬(topological sort)
```

1단계가 있는 이유는 재현성이다. 클래스패스 스캔 순서는 JVM·OS·빌드 도구에 따라 달라질 수 있는데, 알파벳순으로 한 번 고정하면 어디서 빌드하든 같은 순서가 나온다.

**이 순서는 빈 생성 순서가 아니라 빈 *정의 등록* 순서다.** 실제 인스턴스화 순서는 의존성 그래프가 결정한다. 자동 설정 순서가 중요한 이유는 오직 `@ConditionalOnMissingBean` 평가 결과가 달라지기 때문이다.

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
public class MyJdbcAutoConfiguration {

	@Bean
	@ConditionalOnSingleCandidate(DataSource.class)
	@ConditionalOnMissingBean
	public JdbcTemplate jdbcTemplate(DataSource ds) {
		return new JdbcTemplate(ds);
	}
}
```

Boot 2.7 부터 `@AutoConfiguration(after = ...)` 형태로 어노테이션 속성에 직접 쓸 수 있다. `@AutoConfiguration` 자체가 `@Configuration(proxyBeanMethods = false)` + `@AutoConfigureBefore` + `@AutoConfigureAfter` 의 합성이다.

`proxyBeanMethods = false` 가 기본인 것도 의도적이다. CGLIB 프록시를 만들지 않으므로 기동이 빨라지고 네이티브 이미지 호환성이 올라간다. 대신 `@Bean` 메서드끼리 직접 호출하면 싱글톤 보장이 깨지므로, 다른 빈이 필요하면 메서드 파라미터로 주입받아야 한다.

```java
// 잘못 — proxyBeanMethods=false 에서 매번 새 인스턴스
@Bean
public B b() { return new B(a()); }  // a() 직접 호출

// 올바름 — 파라미터 주입
@Bean
public B b(A a) { return new B(a); }
```

`@ConditionalOnSingleCandidate` 는 `@ConditionalOnBean` 보다 정확하다. 빈이 정확히 하나거나, 여러 개지만 `@Primary` 가 하나 있을 때 통과한다. `DataSource` 가 두 개인 멀티 DB 환경에서 `JdbcTemplate` 을 자동 생성하면 어느 것을 쓸지 모호해지므로 아예 만들지 않는 편이 낫다.

## 5. 조건 어노테이션 전체 지도

| 어노테이션 | 판정 대상 | 주의점 |
|---|---|---|
| `@ConditionalOnClass` | 클래스패스에 클래스 존재 | 시그니처에 노출되면 `NoClassDefFoundError` |
| `@ConditionalOnMissingClass` | 클래스 부재 | 문자열로 지정 |
| `@ConditionalOnBean` | 빈 존재 | 자동 설정에서만 신뢰 가능 |
| `@ConditionalOnMissingBean` | 빈 부재 | `@Bean` 메서드에만 |
| `@ConditionalOnSingleCandidate` | 유일 빈 또는 `@Primary` | 멀티 DB 환경 필수 |
| `@ConditionalOnProperty` | 프로퍼티 값 | `matchIfMissing` 기본 false |
| `@ConditionalOnResource` | 리소스 존재 | `classpath:`, `file:` |
| `@ConditionalOnWebApplication` | 웹 앱 타입 | SERVLET / REACTIVE / ANY |
| `@ConditionalOnExpression` | SpEL 결과 | 최후 수단, 느리다 |
| `@ConditionalOnJava` | JVM 버전 | 범위 지정 가능 |

`@ConditionalOnClass` 의 함정이 실무에서 가장 자주 터진다.

```java
// 위험 — 파라미터 타입이 시그니처에 있어 클래스 로드가 강제된다
@AutoConfiguration
public class Bad {
	@Bean
	@ConditionalOnClass(RedisClient.class)
	public RedisClient redisClient() { ... }   // 조건 평가 전에 반환 타입 해석 시도
}

// 안전 — 별도 내부 클래스로 격리
@AutoConfiguration
public class Good {
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RedisClient.class)
	static class RedisConfig {
		@Bean
		public RedisClient redisClient() { ... }
	}
}
```

`@ConditionalOnClass` 는 어노테이션 속성이라 ASM 으로 바이트코드를 읽어 평가하므로 클래스 로드 없이 판정된다. 하지만 `@Bean` 메서드의 반환 타입은 리플렉션으로 해석되므로 클래스가 없으면 즉시 터진다. 조건이 붙은 타입은 반드시 별도 클래스 안으로 격리한다.

## 6. 커스텀 스타터 설계

사내 공통 모듈을 스타터로 만드는 실제 구조다. 관례상 모듈을 둘로 나눈다.

```
company-audit-spring-boot-autoconfigure/   ← 자동 설정 코드
company-audit-spring-boot-starter/         ← 의존성만 모은 빈 껍데기
```

**네이밍 규칙**: 서드파티는 `xxx-spring-boot-starter`, Spring 공식은 `spring-boot-starter-xxx`. `spring-boot-starter-` 접두사는 예약돼 있으므로 사용하지 않는다.

**1) 프로퍼티 클래스**

```java
@ConfigurationProperties(prefix = "company.audit")
public class AuditProperties {

	/** 감사 로그 활성화 여부. */
	private boolean enabled = true;

	/** 감사 로그 저장 대상 테이블명. */
	private String tableName = "audit_log";

	/** 비동기 기록 시 큐 용량. */
	private int queueCapacity = 1_000;

	/** 기록 실패 시 재시도 횟수. */
	private int maxRetries = 3;

	// getter / setter
}
```

Javadoc 주석이 중요하다. `spring-boot-configuration-processor` 가 이 주석을 읽어 `META-INF/spring-configuration-metadata.json` 을 생성하고, IntelliJ 가 이를 읽어 `application.yml` 에서 자동완성과 설명을 띄운다.

```gradle
dependencies {
	annotationProcessor "org.springframework.boot:spring-boot-configuration-processor"
	compileOnly "org.springframework.boot:spring-boot-autoconfigure"
	compileOnly "org.springframework.boot:spring-boot-configuration-processor"
}
```

**2) 자동 설정 클래스**

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "company.audit", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

	@Bean
	@ConditionalOnSingleCandidate(DataSource.class)
	@ConditionalOnMissingBean
	public AuditRepository auditRepository(DataSource dataSource, AuditProperties props) {
		return new JdbcAuditRepository(new JdbcTemplate(dataSource), props.getTableName());
	}

	@Bean
	@ConditionalOnMissingBean
	public AuditService auditService(AuditRepository repository, AuditProperties props) {
		return new AsyncAuditService(repository, props.getQueueCapacity(), props.getMaxRetries());
	}

	@Bean
	@ConditionalOnWebApplication(type = Type.SERVLET)
	@ConditionalOnMissingBean
	public FilterRegistrationBean<AuditFilter> auditFilter(AuditService service) {
		FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(new AuditFilter(service));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
		return registration;
	}
}
```

`matchIfMissing = true` 로 기본 활성화하되 `company.audit.enabled=false` 로 끌 수 있게 한다. 모든 `@Bean` 에 `@ConditionalOnMissingBean` 을 붙여 사용자 재정의를 허용하는 것이 스타터의 기본 예의다.

**3) imports 파일**

`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.company.audit.autoconfigure.AuditAutoConfiguration
```

**4) 테스트 — `ApplicationContextRunner`**

스타터 테스트에는 `@SpringBootTest` 를 쓰지 않는다. 전체 컨텍스트를 띄우면 느리고 조건 분기를 검증할 수 없다.

```java
class AuditAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
			.withBean(DataSource.class, () -> new EmbeddedDatabaseBuilder().build());

	@Test
	void 기본값으로_감사_빈이_등록된다() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(AuditService.class);
			assertThat(context).hasSingleBean(AuditRepository.class);
		});
	}

	@Test
	void enabled_false면_빈이_등록되지_않는다() {
		runner.withPropertyValues("company.audit.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(AuditService.class));
	}

	@Test
	void 사용자_정의_빈이_우선한다() {
		runner.withUserConfiguration(CustomAuditConfig.class)
				.run(context -> {
					assertThat(context).hasSingleBean(AuditService.class);
					assertThat(context.getBean(AuditService.class))
							.isInstanceOf(CustomAuditService.class);
				});
	}

	@Test
	void JdbcTemplate이_없으면_비활성화된다() {
		runner.withClassLoader(new FilteredClassLoader(JdbcTemplate.class))
				.run(context -> assertThat(context).doesNotHaveBean(AuditService.class));
	}

	@Test
	void DataSource가_둘이면_등록하지_않는다() {
		runner.withBean("ds2", DataSource.class, () -> new EmbeddedDatabaseBuilder().build())
				.run(context -> assertThat(context).doesNotHaveBean(AuditRepository.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAuditConfig {
		@Bean
		AuditService auditService() {
			return new CustomAuditService();
		}
	}
}
```

`FilteredClassLoader` 가 핵심 도구다. 클래스패스에서 특정 클래스를 지운 것처럼 시뮬레이션해 `@ConditionalOnClass` 분기를 실제로 검증한다. 이 다섯 개 테스트가 스타터 검증의 최소 집합이다. 각 실행이 수십 ms 로 끝나므로 `@SpringBootTest` 대비 압도적으로 빠르다.

## 7. 디버깅 — 왜 이 빈이 안 생기는가

증상별 진단 순서다.

**1) 조건 평가 리포트**

```bash
java -jar app.jar --debug
```

또는 `application.yml` 에 `debug: true`. 출력은 네 섹션이다.

```
============================
CONDITIONS EVALUATION REPORT
============================

Positive matches:
-----------------
   AuditAutoConfiguration matched:
      - @ConditionalOnClass found required class 'org.springframework.jdbc.core.JdbcTemplate' (OnClassCondition)
      - @ConditionalOnProperty (company.audit.enabled) matched (OnPropertyCondition)

Negative matches:
-----------------
   RedisAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'org.springframework.data.redis.core.RedisTemplate' (OnClassCondition)

Exclusions:
-----------
   org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration

Unconditional classes:
----------------------
   org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
```

Negative matches 에서 원하는 클래스를 찾아 사유를 읽는 것이 첫 단계다. 대부분 여기서 끝난다.

**2) Actuator 엔드포인트**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: conditions,beans,configprops
```

`/actuator/conditions` 는 위 리포트를 JSON 으로, `/actuator/beans` 는 등록된 빈 전체를, `/actuator/configprops` 는 바인딩된 프로퍼티 실제 값을 준다. 운영 환경에서 "설정이 먹었는가" 를 확인할 때 `configprops` 가 가장 유용하다.

**3) 자주 나오는 원인**

| 증상 | 원인 |
|---|---|
| imports 파일을 썼는데 무시됨 | 경로 오타. `META-INF/spring/` 하위여야 함 |
| 사용자 빈이 있는데 자동 설정 빈도 생김 | 클래스 레벨 `@ConditionalOnMissingBean` |
| `NoClassDefFoundError` at startup | 조건부 타입이 `@Bean` 시그니처에 노출 |
| 프로퍼티가 안 먹음 | `@EnableConfigurationProperties` 누락 |
| 순서가 안 맞음 | `@AutoConfigureAfter` 대상이 클래스패스에 없음 |
| 같은 앱 안 `@Component` 가 무시됨 | 자동 설정 클래스는 컴포넌트 스캔 대상이면 안 됨 |

마지막 항목이 미묘하다. 자동 설정 클래스가 `@ComponentScan` 범위 안에 있으면 imports 파일과 스캔 양쪽으로 두 번 등록되어 `@ConditionalOnMissingBean` 이 자기 자신을 보고 오작동한다. 스타터의 패키지는 반드시 애플리케이션 루트 패키지 바깥에 둔다.

## 8. 정리

자동 설정의 전체 흐름은 다음 한 줄로 압축된다. **`AutoConfiguration.imports` 수집 → 메타데이터 기반 1차 고속 필터 → 정렬 → 사용자 설정 파싱 후 deferred 실행 → 2차 조건 평가 → 빈 정의 등록.**

설계 시 지켜야 할 원칙은 넷이다. 첫째, `@ConditionalOnMissingBean` 은 `@Bean` 메서드에만 붙인다. 둘째, 조건부 타입은 별도 내부 설정 클래스로 격리한다. 셋째, `ApplicationContextRunner` 와 `FilteredClassLoader` 로 모든 조건 분기를 테스트한다. 넷째, 프로퍼티에 Javadoc 을 달아 메타데이터를 생성한다.

이 넷을 지키면 스타터가 어떤 클래스패스 조합에서도 예측 가능하게 동작한다.

## 참고

- Spring Boot Reference — Creating Your Own Auto-configuration (https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)
- Spring Boot Source — `AutoConfigurationImportSelector`, `AutoConfigurationSorter`, `OnClassCondition`
- Spring Boot Reference — Condition Annotations
- Spring Boot Release Notes 2.7 — AutoConfiguration.imports 도입 배경
- Spring Framework Reference — `@Conditional`, `ConfigurationCondition`
- Spring Boot Test — `ApplicationContextRunner`, `FilteredClassLoader` API 문서
