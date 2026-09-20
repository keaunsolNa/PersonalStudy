Notion 원본: https://www.notion.so/3e15a06fd6d381349347c8f84931867b

# Spring Boot GraalVM 네이티브 이미지 AOT 처리와 리플렉션 힌트 및 빌드타임 초기화

> 2026-09-20 신규 주제 · 확장 대상: Spring, JAVA

## 학습 목표

- closed-world assumption 이 리플렉션·동적 프록시·리소스 로딩을 깨뜨리는 지점을 지목한다
- `process-aot` 산출물(`*__BeanDefinitions`, `*__ApplicationContextInitializer`, 메타데이터 JSON)을 직접 열어 읽는다
- `RuntimeHintsRegistrar` 와 Tracing Agent 로 누락 힌트를 메우고, 각 방식의 한계를 구분한다
- 빌드타임 초기화가 이미지 힙을 오염시키는 사례를 재현하고 런타임 초기화로 되돌린다
- 기동 시간·RSS·피크 처리량·빌드 시간 4축으로 네이티브 채택 여부를 판단한다

## 1. closed-world assumption: 네이티브가 포기하는 것

GraalVM `native-image` 는 `main` 에서 출발한 호출 그래프에 도달하는 코드만 바이너리에 담는다. 실행 중 새 클래스가 나타나지 않는다고 가정해야 가상 호출을 직접 호출로 바꾸고 미사용 코드를 제거할 수 있다. 기동·메모리 이득은 전부 이 closed-world assumption 에서 나온다. 문제는 Spring 이 이 가정을 정면으로 위반한다는 점이다. `Class.forName` 의 인자가 프로퍼티 문자열이면 분석기는 그 클래스가 필요한지 알 수 없고, `ObjectMapper` 가 쓰는 DTO 필드도 직접 참조가 없으면 제거되며, 동적 프록시와 `getResourceAsStream` 으로 읽는 리소스는 미리 선언하지 않으면 바이너리에 존재하지 않는다.

```java
String impl = env.getProperty("app.strategy.class"); // 런타임 값
Class<?> c = Class.forName(impl);                    // 도달 불가로 판단 → 제거됨
Object o = c.getDeclaredConstructor().newInstance(); // 네이티브에서 ClassNotFoundException
```

깨지는 방식이 고약하다. 빌드는 성공하는데 런타임에 `NoSuchMethodException` 이 나거나 `getResourceAsStream` 이 조용히 `null` 을 반환하고, 그 경로가 실행될 때만 드러난다. GraalVM 은 이 구멍을 *reachability metadata* 로 메운다. `reflect-config.json` 계열은 JDK 23 부터 `reachability-metadata.json` 으로 통합됐지만 Spring Boot 3.x 는 아직 구 형식을 쓴다.

## 2. Spring 이 이 문제를 푸는 방식: 빌드 시점 컨텍스트 재구성

Spring 은 힌트를 손으로 쓰게 두지 않는다. 빌드 시점에 컨텍스트를 **한 번 실제로 리프레시**해 어떤 빈이 어떤 타입으로 생성되는지 확정하고, 런타임의 컴포넌트 스캔·조건 평가·프록시 생성을 Java 소스와 메타데이터 JSON 으로 내보낸다. `spring-boot-maven-plugin` 의 `process-aot` 골이 이 일을 하고 `native-maven-plugin` 이 산출물을 받아 `native-image` 를 호출하는데, 전자는 순수 JVM 작업이라 GraalVM 없이도 돈다.

```xml
<configuration><buildArgs>
  <buildArg>--initialize-at-run-time=com.example.legacy.StaticCache</buildArg>
</buildArgs></configuration>
```

`mvn -Pnative native:compile` 은 `target/spring-aot/main/` 아래 생성 소스(`*__BeanDefinitions.java`)와 그 컴파일 결과, `META-INF/native-image/…` 메타데이터를 만든다. 핵심은 AOT 모드가 네이티브 전용이 아니라는 점이다. `-Dspring.aot.enabled=true` 로 일반 JVM 에서 산출물을 쓰면 스캔과 조건 평가가 생략되어 기동이 짧아지고, 애플리케이션이 AOT 의 조건 고정 제약을 견디는지를 네이티브 빌드 비용 없이 확인할 수 있다.

## 3. 생성된 코드 읽기: `__BeanDefinitions` 와 `__ApplicationContextInitializer`

`@Configuration` 클래스 하나당 `Xxx__BeanDefinitions.java` 가 생성되고, `@Bean` 메서드가 리플렉션 없는 직접 호출과 `RootBeanDefinition` 조립 코드로 풀려 있다.

```java
return BeanInstanceSupplier.<HikariDataSource>forFactoryMethod(
        DataSourceConfig.class, "orderDataSource", DataSourceProperties.class)
    .withGenerator((registeredBean, args) -> registeredBean.getBeanFactory()
        .getBean(DataSourceConfig.class).orderDataSource(args.get(0)));
```

`withGenerator` 의 람다가 핵심이다. `Method.invoke` 로 팩터리 메서드를 부르던 자리가 컴파일된 직접 호출로 바뀌어 이 경로에는 리플렉션 힌트가 필요 없고, 이것이 Spring AOT 가 힌트 개수를 줄이는 1차 메커니즘이다. 애플리케이션당 하나씩 생성되는 `XxxApplication__ApplicationContextInitializer` 가 이 정의들을 `BeanFactory` 에 등록하는 진입점이고, 네이티브 실행 파일은 컴포넌트 스캔 대신 `META-INF/spring/aot.factories` 로 이 클래스를 호출한다.

```json
[ { "name": "com.example.order.OrderRequest", "allDeclaredFields": true, "allDeclaredMethods": true } ]
```

같은 디렉터리의 `reflect-config.json` 에는 자동 추출 항목이 이렇게 들어간다. `NoSuchMethodException` 이 나면 먼저 이 JSON 을 `grep` 해, 타입이 없으면 힌트 누락으로, 있는데도 실패하면 `allDeclaredMethods` 대신 `queryAllDeclaredMethods` 만 등록된 경우(조회는 되지만 호출은 안 되는 상태)로 의심한다.

## 4. RuntimeHints API: 손으로 메워야 하는 구멍

Spring 이 알아서 못 잡는 영역은 개발자가 선언한다. 가장 저수준인 `RuntimeHintsRegistrar` 에 힌트를 등록하고 `@ImportRuntimeHints(OrderRuntimeHints.class)` 를 빈 정의에 붙이면 AOT 처리 시 수집된다.

```java
public class OrderRuntimeHints implements RuntimeHintsRegistrar {
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader cl) {
    hints.reflection().registerType(OrderRequest.class,          // Jackson 바인딩 대상
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS);
    hints.reflection().registerTypeIfPresent(cl, "com.example.order.FifoStrategy",
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);            // 프로퍼티로 로딩되는 구현체
    hints.resources().registerPattern("db/migration/.*\\.sql");  // 정규식, glob 아님
    hints.proxies().registerJdkProxy(OrderNotifier.class, SpringProxy.class);
  }
}
```

`registerPattern` 의 인자가 glob 이 아니라 정규식이라는 점은 반복적으로 사람을 속인다. `db/migration/*.sql` 은 조용히 아무것도 매칭하지 않고, Flyway 는 마이그레이션이 0개라고 보고한 뒤 스키마 없이 기동한다. 애플리케이션은 뜨는데 테이블만 없는 실패다.

DTO 바인딩만 필요하다면 중첩 타입까지 따라가는 `@RegisterReflectionForBinding({ PaymentResponse.class })` 가 더 짧다. `@RequestBody`/`@ResponseBody` 로 컨트롤러 시그니처에 드러난 타입은 AOT 가 자동으로 잡지만, `body(SomeType.class)` 처럼 메서드 인자로만 등장하는 타입은 명시해야 한다.

## 5. Tracing Agent: 수집은 쉽고 신뢰는 어렵다

서드파티 라이브러리가 무엇을 리플렉션으로 건드리는지 소스 없이 알아내려면 Tracing Agent 를 JVM 에 붙인다. 리플렉션·리소스·프록시 호출을 가로채 메타데이터 JSON 으로 떨군다.

```bash
# 여러 실행을 합칠 때는 config-merge-dir, 잡음 제거는 access-filter-file
java -agentlib:native-image-agent=config-output-dir=META-INF/native-image,access-filter-file=filter.json -jar app.jar
```

한계가 본질적이다. 에이전트는 **실제로 실행된 경로만** 기록하므로, 결제 실패 분기를 한 번도 타지 않았다면 그 분기의 예외 DTO 는 메타데이터에 없고 바이너리는 장애 상황에서 2차 장애를 낸다. 메타데이터의 완전성이 테스트 커버리지에 종속된다.

두 번째는 잡음이다. 필터 없이 돌리면 JDK 내부와 프레임워크 클래스까지 수천 줄이 쌓인다. `access-filter-file` 로 자사 패키지만 남기고 나머지는 GraalVM Reachability Metadata Repository 에 맡긴다. 세 번째로 산출물이 힌트로 고정되어 과잉 등록을 유발하므로, 순서는 "AOT 자동 검출 → 실패 로그 기반 수동 `RuntimeHintsRegistrar` → 그래도 막히면 에이전트" 다.

## 6. 빌드타임 초기화와 이미지 힙 오염

`native-image` 는 빌드 중 초기화된 클래스의 정적 필드 상태를 그대로 바이너리의 **이미지 힙(image heap)** 에 직렬화해 박아 넣는다. 실행 시 매핑만 하면 되므로 기동이 빨라지는데, `--initialize-at-build-time` 이 주는 이득의 정체가 이것이다. 대가는 빌드 머신의 상태가 바이너리에 영속된다는 것이다. 로거를 정적 필드에서 얻으면 런타임에 `logback-spring.xml` 을 바꿔도 먹히지 않고, `SecureRandom` 인스턴스가 박히면 모든 배포 인스턴스가 같은 시드에서 출발한다.

```java
// 안티패턴: 빌드타임에 초기화되면 이미지 힙에 박힌다
public final class TokenFactory {
  private static final SecureRandom RNG = new SecureRandom();      // 시드 고정
  private static final Instant BOOTED_AT = Instant.now();          // 빌드 시각이 박힘
  private static final Path TMP = Files.createTempDirectory("tk"); // 빌드 머신 경로
}
```

```bash
--initialize-at-run-time=com.example.security.TokenFactory,io.netty.channel.epoll
--trace-object-instantiation=java.security.SecureRandom  # 무엇이 왜 힙에 들어갔는지 추적
```

JDK 21 에서 도입된 `--strict-image-heap` 은 JDK 22 에서 기본값이 되었고 JDK 23 에서 구 초기화 전략이 제거되며 효력이 없어졌으므로, 이 플래그를 붙이라는 자료는 이미 낡은 지침이다. 실무 규칙으로 압축하면 **환경 의존적인 상태(난수, 시각, 파일 경로, 네트워크 핸들)는 static initializer 에 두지 않는다.**

## 7. 조건부 빈과 프로파일이 빌드 시점에 고정된다는 것

이 절이 운영 관점에서 가장 위험하다. AOT 처리는 빌드 타임에 컨텍스트를 리프레시하면서 `@ConditionalOnProperty`, `@ConditionalOnClass`, `@Profile` 을 **그 시점에 평가하고 결과를 고정**한다. 탈락한 빈은 `__BeanDefinitions` 에 생성되지 않고 클래스는 도달 불가로 판단되어 제거된다.

```java
@Bean @ConditionalOnProperty(name = "app.cache.mode", havingValue = "redis")
RedisCacheManager redisCacheManager(RedisConnectionFactory cf) { ... }

@Bean @ConditionalOnProperty(name = "app.cache.mode", havingValue = "caffeine", matchIfMissing = true)
CaffeineCacheManager caffeineCacheManager() { ... }
```

JVM 이라면 `-Dapp.cache.mode=redis` 로 재기동하면 끝이지만, 네이티브에서는 빌드 시 값이 `caffeine` 이었다면 `RedisCacheManager` 경로 자체가 바이너리에 없어 런타임에 프로퍼티를 바꿔도 아무 일도 일어나지 않는다. 즉 **설정 스위치가 배포 파라미터에서 빌드 파라미터로 승격**된다. 환경별로 프로파일이 다르면 별도 빌드가 필요해, stg 에서 검증한 바이너리를 prod 로 승격한다는 불변 아티팩트 원칙이 깨진다.

대응은 세 가지다. 프로파일로 **빈 구성**을 가르지 말고 값(엔드포인트·타임아웃)만 다루게 한다. 꼭 분기가 필요하면 두 구현을 모두 등록하고 런타임에 고르는 전략 패턴으로 바꾼다. 환경별 빌드가 불가피하면 `process-aot` 에 프로파일(`<profiles>prod</profiles>`)을 명시하고 그 값을 아티팩트 태그에 박는다.

## 8. 실측 트레이드오프: 4축으로 보기

숫자는 규모·의존성·하드웨어에 따라 달라지므로 아래는 **자릿수 감각**으로만 쓰고 자기 워크로드에서 측정해야 한다.

| 축 | JVM (JIT) | 네이티브 이미지 | 방향성 |
| --- | --- | --- | --- |
| 기동 시간 | 1~3초대 | 수십 밀리초 | 한 자릿수~두 자릿수 배 개선 |
| 기동 직후 메모리(RSS) | 200~400MB 급 | 50~150MB 급 | 절반 이하로 감소 |
| 피크 처리량(워밍업 후) | 기준선 | PGO 없으면 대체로 열세 | JIT 가 유리한 경우가 많음 |
| 빌드 시간·메모리 | 수십 초 | 수 분, 수 GB | CI 사양 상향 필요 |

기동이 빠른 이유는 클래스 로딩·검증·JIT 워밍업·컴포넌트 스캔이 사라졌기 때문이고, 피크 처리량이 불리한 이유는 C2 가 런타임 프로파일로 하는 공격적 인라이닝과 추측 최적화를 AOT 가 정적 정보만으로 재현하지 못하기 때문이다.

따라서 판단은 워크로드 형태로 갈린다. 짧게 뜨고 지는 워크로드(서버리스, CLI, 배치 잡)는 기동·메모리 축의 이득이 지배적인 반면, 며칠씩 떠 있으며 높은 TPS 를 유지하는 서비스는 워밍업 비용을 한 번만 내면 되므로 JIT 의 피크 성능이 이긴다. 하루 종일 도는 주문 처리 서버라면 기동 2초를 50ms 로 줄이는 이득은 사실상 0이다. 비교는 JVM 쪽에 충분한 워밍업을 준 뒤에 해야 공정하다.

## 9. PGO, G1, 라이선스, 그리고 쓰지 말아야 할 때

피크 처리량 격차를 좁히는 수단이 PGO(Profile-Guided Optimization)다. 계측 바이너리로 대표 부하를 태우고, 수집한 프로파일로 최종 바이너리를 다시 빌드한다.

```bash
native-image --pgo-instrument -cp app.jar com.example.App app-instrumented
./app-instrumented   # 대표 워크로드 실행 → 종료 시 default.iprof 기록
native-image --pgo=default.iprof -cp app.jar com.example.App app-optimized
```

여기서 라이선스를 확인해야 한다. PGO 와 G1 GC(`--gc=G1`)는 **Oracle GraalVM** 의 기능이고 오픈소스 Community Edition 에는 없다. Oracle GraalVM 은 GFTC 하에 운영 환경에서도 무상 사용이 가능하지만 이는 "무상"이지 "오픈소스"가 아니다. 또한 PGO 는 빌드를 두 번 하는 것이라 이미 느린 빌드가 두 배가 되고, 트래픽 패턴이 바뀌면 프로파일 재수집이 필요하다.

정리하면 네이티브를 **쓰지 말아야 할 조건**은 이렇다. 장수명 고TPS 서비스라 피크 처리량이 지배적일 때. 조건부 빈으로 환경별 구성을 가르고 있어 불변 아티팩트 원칙을 포기할 수 없을 때. 런타임 바이트코드 생성에 의존하는 라이브러리가 스택에 있을 때.

반대로 채택할 만한 조건은 좁다. 스케일 이벤트가 잦아 기동 시간이 비용으로 직결되거나, 인스턴스당 메모리가 과금 단위이거나, 콜드 스타트가 SLA 인 경우다. 여기 해당하지 않는 사내 백오피스라면 `-Dspring.aot.enabled=true` 로 JVM 위에서 AOT 이득만 취하고 CDS 나 Project Leyden 을 지켜보는 편이 낫다. 네이티브 이미지는 "더 빠른 Java" 가 아니라 **다른 트레이드오프 곡선 위의 Java** 다.

## 참고

- Spring Boot Reference — Introducing GraalVM Native Images: https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html
- Spring Framework Reference — Ahead of Time Optimizations: https://docs.spring.io/spring-framework/reference/core/aot.html
- GraalVM Native Image — Reachability Metadata: https://www.graalvm.org/latest/reference-manual/native-image/metadata/
- GraalVM — Configure Native Image with the Tracing Agent: https://www.graalvm.org/latest/reference-manual/native-image/guides/configure-with-tracing-agent/
- GraalVM — Specify Class Initialization Explicitly: https://www.graalvm.org/latest/reference-manual/native-image/guides/specify-class-initialization/
- GraalVM Release Notes (JDK 21/22/23, strict image heap 변경 이력): https://www.graalvm.org/release-notes/JDK_23/
- GraalVM Native Build Tools — Maven Plugin: https://graalvm.github.io/native-build-tools/latest/maven-plugin.html
