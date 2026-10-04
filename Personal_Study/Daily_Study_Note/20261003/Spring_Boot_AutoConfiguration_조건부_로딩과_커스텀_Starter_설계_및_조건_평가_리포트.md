Notion 원본: https://www.notion.so/3ef5a06fd6d38108a2b9ebca811fb4ac

# Spring Boot AutoConfiguration 조건부 로딩과 커스텀 Starter 설계 및 조건 평가 리포트

> 2026-10-03 신규 주제 · 확장 대상: Spring, Spring 트랜잭션 전파와 Transactional Outbox 패턴

## 학습 목표

- `AutoConfiguration.imports` 로딩 경로와 `@AutoConfiguration`의 순서 제어(`before`/`after`)를 구분한다.
- `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`의 평가 시점과 함정을 설명한다.
- 사내 공통 모듈을 autoconfigure 모듈과 starter 모듈로 분리해 설계한다.
- `--debug` 조건 평가 리포트와 `ApplicationContextRunner`로 자동 구성을 검증한다.

## 1. 자동 구성은 어떻게 시작되는가

`@SpringBootApplication`은 `@EnableAutoConfiguration`을 포함하고, 이 어노테이션은 `AutoConfigurationImportSelector`를 `@Import`한다. 셀렉터는 클래스패스의 모든 jar에서 후보 목록 파일을 읽어 **자동 구성 클래스 이름 목록**을 만든다. 그 목록이 곧 "컨테이너에 등록을 시도할 설정 클래스 후보"다. 후보가 모두 등록되는 것은 아니고, 각 클래스에 붙은 조건이 참일 때만 빈이 만들어진다.

후보 목록 파일은 Spring Boot 2.7부터 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`로 이동했다. 파일 형식은 한 줄에 클래스 이름 하나다. 기존의 `spring.factories`에 `EnableAutoConfiguration` 키로 등록하던 방식은 2.7에서 deprecated 되었고 3.0에서 지원이 제거되었다. 서드파티 라이브러리를 3.x용으로 만들 때 이 파일 이름을 틀려 자동 구성이 "조용히" 동작하지 않는 사고가 흔하다. 에러가 나지 않고 단지 빈이 없을 뿐이기 때문이다.

```text
# src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.audit.autoconfigure.AuditAutoConfiguration
```

일반 `@Configuration`과 자동 구성 클래스는 처리 시점이 다르다. 자동 구성은 사용자 정의 설정이 모두 처리된 **뒤**에 처리된다(`DeferredImportSelector`). 이 순서가 `@ConditionalOnMissingBean`의 전제다. 사용자가 이미 정의한 빈이 등록된 이후에 "없을 때만 기본 빈을 만든다"를 판단할 수 있기 때문이다.

## 2. @AutoConfiguration과 순서 제어

2.7에서 추가된 `@AutoConfiguration`은 `@Configuration(proxyBeanMethods = false)`의 메타 어노테이션이며, `before`, `after`, `beforeName`, `afterName` 속성으로 자동 구성 간 순서를 선언한다. 컴포넌트 스캔 대상이 되면 안 되므로 **자동 구성 클래스는 `@ComponentScan` 범위 밖 패키지**에 두는 것이 원칙이다.

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AuditWriter auditWriter(JdbcTemplate jdbcTemplate, AuditProperties properties) {
		return new JdbcAuditWriter(jdbcTemplate, properties.getTableName());
	}
}
```

`proxyBeanMethods = false`는 CGLIB 프록시를 만들지 않아 시작 시간과 메모리를 줄인다. 대신 같은 설정 클래스 안에서 `@Bean` 메서드를 직접 호출해 다른 빈을 참조하면 싱글턴이 보장되지 않는다. 의존 빈은 **메서드 파라미터로 주입**받는 것이 규칙이다. 위 예시가 그 형태다.

순서 속성은 "A가 B보다 먼저 처리되어야 B의 조건이 올바르게 평가되는" 경우에만 쓴다. 예를 들어 `DataSource` 빈이 존재해야 하는 설정은 `DataSourceAutoConfiguration` 뒤에 둬야 `@ConditionalOnBean(DataSource.class)`가 정확히 평가된다. 순서를 지정하지 않으면 알파벳 순서 등 결정적이지만 의미 없는 순서가 쓰이므로, 조건이 환경에 따라 간헐적으로 틀어질 수 있다.

## 3. 조건 어노테이션의 평가 시점

조건은 크게 두 단계로 평가된다. **PARSE_CONFIGURATION** 단계는 설정 클래스를 파싱할 때 평가되어 클래스 자체의 등록 여부를 결정한다. **REGISTER_BEAN** 단계는 빈 정의를 등록할 때 평가된다. `@ConditionalOnBean`/`@ConditionalOnMissingBean`은 후자라서, 평가 시점까지 등록된 빈 정의만 본다. 이것이 순서에 민감한 이유다.

**@ConditionalOnClass.** 특정 클래스가 클래스패스에 있을 때만 활성화된다. 중요한 함정이 있다. `@Bean` 메서드의 반환 타입이나 파라미터 타입에 선택적 의존성 클래스를 쓰면, 스프링이 어노테이션 메타데이터를 읽기 전에 JVM이 클래스를 로딩하려다 `NoClassDefFoundError`가 날 수 있다. 그래서 선택적 의존성은 **중첩 static 설정 클래스**로 분리해 그 클래스에 `@ConditionalOnClass`를 붙이거나, 속성에 클래스 대신 `name = "..."` 문자열을 쓴다. Spring Boot는 어노테이션 메타데이터를 ASM으로 읽어 클래스 로딩 없이 조건을 먼저 평가한다.

```java
@AutoConfiguration
public class AuditAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
	static class AuditMetricsConfiguration {
		@Bean
		AuditMetrics auditMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
			return new AuditMetrics(registry);
		}
	}
}
```

**@ConditionalOnMissingBean.** 사용자가 같은 타입의 빈을 정의하면 기본 빈이 물러난다. 속성 없이 `@Bean` 메서드에 붙이면 반환 타입을 기준으로 판단한다. 라이브러리의 "덮어쓸 수 있는 기본값" 설계의 핵심이다. 주의점은 이 어노테이션이 **자동 구성 클래스에서만 신뢰할 수 있다**는 것이다. 일반 설정 클래스에서는 처리 순서가 보장되지 않아 의도와 다르게 평가된다. 공식 문서도 이를 명시한다.

**@ConditionalOnProperty.** `prefix`, `name`, `havingValue`, `matchIfMissing`을 쓴다. 기본값이 켜짐이어야 한다면 `matchIfMissing = true`로 한다. `havingValue`를 생략하면 값이 `false`가 아니면 참이다. 켜고 끄는 스위치는 `acme.audit.enabled` 같은 명확한 이름을 쓰는 것이 관례다.

```java
@ConditionalOnProperty(prefix = "acme.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
```

**기타.** `@ConditionalOnWebApplication`(SERVLET/REACTIVE 구분), `@ConditionalOnBean`, `@ConditionalOnSingleCandidate`, `@ConditionalOnExpression`, `@ConditionalOnResource`가 있다. `@ConditionalOnExpression`은 SpEL을 쓰므로 유연하지만 정적 분석이 어려워 마지막 수단으로 둔다.

## 4. 설정 프로퍼티와 메타데이터

사용자가 조정할 값은 `@ConfigurationProperties`로 바인딩한다. `spring-boot-configuration-processor`를 `annotationProcessor`로 추가하면 빌드 시 `META-INF/spring-configuration-metadata.json`이 생성되어 IDE에서 자동 완성과 설명이 제공된다.

```java
@ConfigurationProperties(prefix = "acme.audit")
public class AuditProperties {

	/** 감사 로그를 기록할 테이블 이름. */
	private String tableName = "audit_log";

	/** 비동기 기록 시 큐 크기. */
	private int queueCapacity = 1024;

	public String getTableName() { return tableName; }
	public void setTableName(String tableName) { this.tableName = tableName; }
	public int getQueueCapacity() { return queueCapacity; }
	public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
}
```

필드 Javadoc이 메타데이터의 description이 된다. 프로퍼티 이름은 kebab-case를 권장하며, 바인딩은 relaxed binding으로 `tableName`, `table-name`, `TABLE_NAME` 환경변수 모두 매핑된다.

## 5. Starter 설계: autoconfigure와 starter 분리

Spring Boot 자체와 공식 라이브러리는 **autoconfigure 모듈**(자동 구성 코드)과 **starter 모듈**(의존성 묶음만 있는 빈 jar)을 분리한다. 사내 공통 모듈도 같은 구조를 따르면 이점이 있다. 사용자가 starter 하나만 추가하면 필요한 전이 의존성이 따라오고, autoconfigure 모듈의 선택적 의존성(`optional`)은 사용자가 필요할 때만 추가할 수 있다.

명명 규칙은 서드파티가 `acme-spring-boot-starter`처럼 자기 이름을 앞에 두는 것이다. `spring-boot-starter-*` 접두는 Spring Boot 팀 전용이다. 프로퍼티 prefix도 `spring.`이나 `server.` 같은 Boot의 네임스페이스와 겹치지 않게 한다.

```text
acme-audit/
 ├─ acme-audit-spring-boot-autoconfigure/   (AuditAutoConfiguration, AuditProperties, imports 파일)
 ├─ acme-audit-spring-boot-starter/          (pom/gradle 의존성만: autoconfigure + 필수 라이브러리)
 └─ acme-audit-core/                         (Spring 비의존 도메인 로직)
```

핵심은 **core를 Spring에서 분리**하는 것이다. 그래야 자동 구성 없이도 단위 테스트가 가능하고, 다른 프레임워크에서 재사용할 수 있다. 자동 구성 클래스는 얇은 어댑터로 유지한다.

## 6. 조건 평가 리포트로 디버깅하기

자동 구성이 왜 적용되지 않았는지 알려면 `--debug` 또는 `debug=true`로 시작한다. 콘솔에 **CONDITIONS EVALUATION REPORT**가 출력되며 Positive matches, Negative matches, Exclusions, Unconditional classes로 나뉜다. Negative matches에는 `@ConditionalOnClass did not find required class '...'` 같은 사유가 나온다. 운영 중에는 Actuator의 `conditions` 엔드포인트(`/actuator/conditions`)로 같은 정보를 조회할 수 있으나, 내부 구조가 노출되므로 접근 제한이 필요하다.

특정 자동 구성을 끄려면 `@SpringBootApplication(exclude = ...)` 또는 `spring.autoconfigure.exclude` 프로퍼티를 쓴다. 단, 존재하지 않는 클래스를 `excludeName`에 쓰면 오타를 알려주지 않는다는 점에 유의한다.

## 7. 설계 시 trade-off

첫째, **마법의 대가**. 자동 구성이 많을수록 사용자는 쉽지만 디버깅이 어렵다. 따라서 기본 빈은 모두 `@ConditionalOnMissingBean`으로 덮어쓸 수 있게 하고, 큰 기능은 `enabled` 스위치를 둔다. 둘째, **시작 시간**. 조건이 많은 자동 구성은 시작 시 평가 비용이 든다. 수백 개 수준에서는 체감이 작지만, GraalVM 네이티브 이미지에서는 조건이 빌드 시점에 평가되므로 런타임 프로퍼티로 켜고 끄는 `@ConditionalOnProperty`가 기대와 다르게 동작할 수 있다. 네이티브를 지원한다면 `RuntimeHints`와 AOT 처리를 별도로 검증해야 한다. 셋째, **하위 호환**. 자동 구성 클래스 이름이 공개 API처럼 쓰인다(`exclude`로 참조되므로). 이름을 바꾸면 사용자의 exclude 설정이 깨진다.

## 8. 검증: ApplicationContextRunner 테스트

자동 구성은 `ApplicationContextRunner`로 컨텍스트를 가볍게 띄워 조건별로 검증한다. `@SpringBootTest`보다 빠르고 조건 조합을 표현하기 쉽다. 아래 테스트는 위 `AuditAutoConfiguration`을 대상으로 하며, 본 노트 작성 환경에서는 Java 빌드를 실행하지 않았으므로 프로젝트에 적용한 뒤 실행 결과를 확인해야 한다.

```java
class AuditAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

	@Test
	void JdbcTemplate이_없으면_빈을_만들지_않는다() {
		runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AuditWriter.class));
	}

	@Test
	void JdbcTemplate이_있으면_기본_AuditWriter를_등록한다() {
		runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
			.run(ctx -> assertThat(ctx).hasSingleBean(JdbcAuditWriter.class));
	}

	@Test
	void 사용자_정의_빈이_있으면_기본_빈이_물러난다() {
		AuditWriter custom = mock(AuditWriter.class);
		runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
			.withBean("customWriter", AuditWriter.class, () -> custom)
			.run(ctx -> {
				assertThat(ctx).hasSingleBean(AuditWriter.class);
				assertThat(ctx.getBean(AuditWriter.class)).isSameAs(custom);
			});
	}

	@Test
	void 프로퍼티로_테이블_이름을_바꿀_수_있다() {
		runner.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
			.withPropertyValues("acme.audit.table-name=my_audit")
			.run(ctx -> assertThat(ctx.getBean(AuditProperties.class).getTableName()).isEqualTo("my_audit"));
	}
}
```

클래스패스 부재 조건은 `FilteredClassLoader`로 검증한다. `runner.withClassLoader(new FilteredClassLoader(JdbcTemplate.class))`를 쓰면 해당 클래스가 없는 환경을 흉내 낼 수 있다. 추가로 `.imports` 파일에 클래스 이름이 올바르게 적혀 있는지는 `ImportCandidates.load(AutoConfiguration.class, classLoader)` 결과에 포함되는지 확인하는 테스트를 하나 두면 파일명 오타 사고를 막을 수 있다.

## 참고

- Spring Boot Reference: Creating Your Own Auto-configuration
- Spring Boot Reference: Condition Annotations
- Spring Boot 2.7 Release Notes: Auto-configuration Registration
- Spring Boot 3.0 Migration Guide: `spring.factories` 지원 제거
- Spring Boot API: `ApplicationContextRunner`, `FilteredClassLoader`
