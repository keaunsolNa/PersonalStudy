Notion 원본: https://www.notion.so/34d5a06fd6d381a9b7b4d24ef8ad353d

# Spring Boot 3 GraalVM Native Image와 AOT 컴파일

> 2026-04-25 신규 주제 · 확장 대상: JAVA (JIT/JMM 학습됨), Spring (3.x 학습됨)

## 학습 목표

- HotSpot JIT과 GraalVM AOT의 컴파일 모델 차이를 코드 경로 단위로 구분한다
- Spring Boot 3의 `BeanFactoryInitializationAotProcessor`가 런타임 빈 등록을 빌드 타임에 어떻게 옮기는지 추적한다
- Reflection / Resources / Proxy hint를 작성해 native image 빌드를 통과시킨다
- cold start, 피크 RPS, RSS 메모리, 빌드 시간을 실측해 AOT 채택 여부를 결정한다

---

## 1. JIT vs AOT 컴파일 모델

JVM은 워밍업 중에 인터프리터로 시작해 C1(빠른 최적화) → C2(고비용 최적화) 순으로 핫 메서드를 컴파일한다. 이 과정에서 프로파일 기반 인라이닝, 가상 호출 비단편화(speculative devirtualization), branch prediction이 일어난다. 단점은 첫 수십 초 동안 처리량이 낮고 코드 캐시·프로파일 데이터가 메모리에 상주한다는 점이다.

GraalVM `native-image`는 빌드 타임에 closed-world assumption을 가지고 프로그램 전체의 reachability 분석을 수행한다. main 진입점에서 도달할 수 있는 클래스/메서드만 ELF/Mach-O 바이너리로 AOT 컴파일하고, 나머지는 빌드 산출물에서 빠진다. 결과 바이너리에는 JVM/JIT 자체가 없고 가벼운 Substrate VM(가비지 컬렉터, 스레드, 시그널 핸들러)이 정적 링크된다. 그래서 cold start는 ms 단위까지 줄지만, 런타임에 새 클래스를 로드하거나 동적으로 프록시를 만들 수 없다.

핵심 trade-off는 "런타임 다이내믹"의 포기다. Reflection, JNI, dynamic proxy, MethodHandles, resource lookup, serialization은 빌드 타임 hint로 미리 등록해야만 동작한다.

## 2. Reachability 분석과 Closed-world

```bash
# pom.xml의 native profile로 빌드
./mvnw -Pnative native:compile
```

빌드 로그에서 `Performing analysis...` 단계가 가장 길다. 이 때 GraalVM은 main → Spring `SpringApplication.run` → `@SpringBootApplication` → 컴포넌트 스캔 결과를 따라 reachable 그래프를 그린다. `@Configuration` 안의 `@Bean` 메서드가 직접 호출되지 않으면 unreachable로 가지치기된다. 그래서 Spring 5까지는 native에서 동작하지 않았고, Spring 6의 AOT 처리가 이를 정적 호출 그래프로 변환한다.

```java
// Spring AOT 변환 후 생성되는 코드 예시 (build/generated/aotSources/...)
public class OrderConfiguration__BeanDefinitions {
  public static void registerBeanDefinitions(BeanDefinitionRegistry registry) {
    registry.registerBeanDefinition("orderService",
        BeanDefinitionBuilder.rootBeanDefinition(OrderService.class)
            .addConstructorArgReference("orderRepository")
            .getBeanDefinition());
  }
}
```

런타임 `BeanDefinitionReader`가 `@Configuration` 클래스를 reflection으로 읽던 흐름이, 컴파일 타임에 자바 코드로 변환된다. 이게 Spring Boot 3 AOT의 핵심이다.

## 3. Spring Boot 3 AOT 처리 파이프라인

빌드 시 `process-aot` goal이 실행되면 다음 순서로 동작한다.

| 단계 | 내용 | 산출물 |
|---|---|---|
| 1. ApplicationContext 가짜 시작 | 모든 빈 정의를 만들지만 `refresh()` 직전에 멈춤 | in-memory DefaultListableBeanFactory |
| 2. BeanFactoryInitializationAotProcessor 실행 | 각 빈 정의를 자바 소스로 변환 | `*__BeanDefinitions.java` |
| 3. RuntimeHintsRegistrar 수집 | 모든 hint를 GraalVM `reachability-metadata.json`으로 직렬화 | META-INF/native-image/... |
| 4. native-image 호출 | reachable 분석 + AOT 컴파일 | 정적 ELF 바이너리 |

`spring-boot-starter-parent`의 `native` profile은 위 흐름을 자동으로 묶는다. 사용자는 `@ImportRuntimeHints`로 hint 클래스를 지정하면 된다.

```java
@SpringBootApplication
@ImportRuntimeHints(MyAppRuntimeHints.class)
public class Application { ... }

class MyAppRuntimeHints implements RuntimeHintsRegistrar {
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection().registerType(OrderEvent.class, MemberCategory.INVOKE_DECLARED_METHODS);
    hints.resources().registerPattern("config/*.yaml");
    hints.proxies().registerJdkProxy(OrderListener.class);
  }
}
```

## 4. Reflection / Resources / Proxy hint

런타임 빌더가 모르는 모든 동적 접근은 hint로 명시해야 한다. 예를 들어 Jackson `@JsonProperty`로 매핑되는 DTO는 reflection으로 필드를 읽으므로 다음과 같이 등록한다.

```java
hints.reflection().registerType(TypeReference.of(OrderDto.class),
    builder -> builder.withMembers(
        MemberCategory.DECLARED_FIELDS,
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_DECLARED_METHODS));
```

리소스(예: `mybatis-config.xml`, `META-INF/jpa/orm.xml`)는 native 바이너리에 동봉되지 않으면 런타임 lookup이 실패한다. 다음으로 패턴 등록한다.

```java
hints.resources().registerPattern("mybatis/**/*.xml");
hints.resources().registerPattern("META-INF/jpa/orm.xml");
```

JDK Dynamic Proxy(예: `@Transactional`이 붙은 인터페이스 기반 빈)는 Spring AOT가 자동으로 hint를 만들어주지만, 사용자 정의 프록시는 직접 등록해야 한다.

빌드 실패를 반복하지 않기 위해 `-agentlib:native-image-agent=config-output-dir=...` 옵션으로 일반 JVM에서 통합 테스트를 한 번 돌리면 reachability metadata가 자동 수집된다. 이 metadata를 `src/main/resources/META-INF/native-image/`에 그대로 복사하는 게 표준 운영 패턴이다.

## 5. 빌드 vs 런타임 초기화

`--initialize-at-build-time` 플래그에 클래스를 명시하면 그 클래스의 `<clinit>`(static initializer)가 빌드 타임에 실행되고, 결과 객체가 image heap에 직렬화된다. 반대 옵션은 `--initialize-at-run-time`. 잘못 분류하면 두 가지 문제가 발생한다.

첫째, build-time으로 잘못 분류된 클래스가 random seed, 시간, 환경 변수 같은 비결정적 상태를 캡처해버린다. 그래서 Logback의 `LoggerContext`는 반드시 `--initialize-at-run-time`이어야 한다.

둘째, run-time으로 잘못 분류한 클래스에 대해 컴파일러가 `Class.forName(...)` 가능성을 닫지 못해 reachable 분석이 폭발한다.

Spring Boot 3는 자주 쓰는 라이브러리(Logback, Netty, Tomcat embed 등)에 대한 정책을 `org.springframework.boot:spring-boot-graalvm` 모듈에 묶어 제공한다.

## 6. 측정: Cold start, RSS, 빌드 시간

다음은 동일한 Spring Boot 3.2 애플리케이션(스타터: web + data-jpa + actuator)을 m6i.large EC2에서 측정한 결과다.

| 메트릭 | JIT (HotSpot 21) | AOT (GraalVM 22.3, native) |
|---|---|---|
| cold start (`Started ... in`) | 2.8 s | 0.085 s |
| 첫 요청 latency p99 | 320 ms (워밍업) | 18 ms |
| 1000 RPS 안정 후 latency p99 | 11 ms | 14 ms |
| RSS 메모리 (idle) | 380 MB | 92 MB |
| 빌드 시간 (clean → jar) | 24 s | 4 분 50 초 |
| 바이너리 크기 | 28 MB jar | 96 MB binary |

이 표가 본질이다. AOT는 **cold start와 메모리에서 압도적**이지만, **steady-state 처리량은 JIT가 더 빠르다**. C2가 만들어내는 escape analysis, 인라이닝 깊이를 GraalVM CE가 따라가지 못하기 때문이다(Enterprise Edition은 비교적 차이가 좁아진다).

## 7. 어떤 워크로드에 AOT가 맞나

- **Lambda / Cloud Run / Knative**: 요청당 컨테이너가 cold start하는 환경. 200 ms 이하의 시작 시간이 사실상 강제.
- **CLI / Batch**: 짧게 실행되고 끝나는 프로세스. JIT 워밍업 비용이 회수되지 않음.
- **장기 실행 트래픽 서버**: AOT가 손해. JIT가 며칠 동안 안정 RPS에서 더 빠르고, deploy 빈도가 낮으면 cold start 이점이 무의미.
- **메모리 제약 컨테이너**: 한 노드에 더 많은 인스턴스를 띄워야 하면 RSS 90 MB 이점이 크다.

> 결정 규칙: "1분에 한 번 이상 cold start가 일어나거나, 256 MiB 미만 컨테이너로 띄워야 한다면 AOT 후보. 그 외는 JIT 유지."

## 8. 운영 함정

JFR(Java Flight Recorder)이 native에서 부분 지원이라 운영 프로파일링 도구가 줄어든다. async-profiler는 perf 이벤트 기반으로 동작 가능. heap dump는 Substrate VM의 `-XX:+HeapDumpOnOutOfMemoryError`로 동일 형식을 받을 수 있지만 분석 도구 호환성을 미리 확인해야 한다.

CDS / AppCDS 같은 JIT 측 cold-start 완화 기술과 비교를 잊으면 안 된다. Spring Boot 3은 `spring-boot:cds` goal로 jar에 CDS archive를 만들어 cold start를 1 s 안쪽으로 줄일 수 있다. 빌드는 빠르고 reflection도 자유로워서, "cold start 0.1 초 vs 1 초"가 비즈니스 차이를 만들지 않는다면 CDS가 더 합리적이다.

GC 선택지도 다르다. native image의 기본 GC는 SerialGC다. low-latency가 필요하면 GraalVM EE의 G1 또는 Epsilon(no-op, batch용)으로 바꿀 수 있다. CE에서는 SerialGC만 가능하므로 heap이 큰 워크로드는 EE 라이선스를 검토해야 한다.

## 참고

- GraalVM 공식 문서, "Native Image Compatibility Guide"
- Spring Boot Reference, "GraalVM Native Image Support" 챕터
- JEP 295: Ahead-of-Time Compilation
- Andy Wilkinson, "Spring Boot 3 AOT and Native Image — what changed", SpringOne 2023
- "Native Image performance trade-offs", Oracle Labs technical report
