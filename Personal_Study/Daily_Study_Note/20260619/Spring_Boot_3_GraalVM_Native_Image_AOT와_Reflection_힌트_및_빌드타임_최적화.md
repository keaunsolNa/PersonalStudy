Notion 원본: https://www.notion.so/3845a06fd6d3813181cfd66985ad666b

# Spring Boot 3 GraalVM Native Image AOT와 Reflection 힌트 및 빌드타임 최적화

> 2026-06-19 신규 주제 · 확장 대상: Spring

## 학습 목표

- GraalVM Native Image의 closed-world 가정과 AOT 컴파일 모델을 이해한다
- Spring AOT 엔진이 빈 정의를 빌드타임 코드로 전환하는 과정을 분석한다
- Reflection / Resource / Proxy 힌트를 RuntimeHints API로 등록한다
- 시작 시간·메모리·빌드 시간 트레이드오프를 근거로 네이티브 채택을 판단한다

## 1. JIT vs AOT: 실행 모델의 근본 차이

기존 JVM은 바이트코드를 로드해 인터프리터로 실행하다가 핫스팟을 JIT(C1/C2)이 기계어로 변환한다. 런타임 동적 기능이 자유롭지만 워밍업과 큰 메모리가 따른다.

GraalVM Native Image는 빌드타임에 애플리케이션 전체를 정적 분석(points-to)해 도달 가능한 코드만 추려 단일 네이티브 실행 파일로 AOT 컴파일한다. 시작이 수십 ms, 메모리도 작다. 대신 빌드 시 모든 코드 경로를 알아야 하는 closed-world 가정이 제약이다. 리플렉션·프록시·리소스 접근은 메타데이터(힌트)로 미리 알려줘야 포함된다.

## 2. Spring AOT 엔진의 역할

Spring은 리플렉션과 동적 프록시에 크게 의존한다. Spring Boot 3 / Framework 6은 AOT 처리 단계를 도입해 빌드 시 ApplicationContext를 부분 기동해 빈 정의를 분석하고, 런타임 리플렉션 대신 명시적 Java 코드와 힌트 메타데이터를 생성한다.

```bash
mvn spring-boot:process-aot
mvn -Pnative native:compile
```

중요한 귀결: @Conditional이 빌드타임에 평가되므로 네이티브 바이너리의 빈 구성은 빌드 시점에 고정된다. 런타임 프로파일 전환으로 조건부 빈 구성이 바뀌는 패턴은 제한된다.

## 3. RuntimeHints: 동적 기능을 빌드에 알리기

정적 분석이 놓치는 동적 접근은 RuntimeHintsRegistrar로 등록한다.

```java
public class MyRuntimeHints implements RuntimeHintsRegistrar {
	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		hints.reflection().registerType(MyDto.class,
				MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
				MemberCategory.INVOKE_DECLARED_METHODS);
		hints.resources().registerPattern("templates/*.html");
		hints.serialization().registerType(MyEvent.class);
	}
}
```

@ImportRuntimeHints로 등록하거나, 직렬화 대상은 @RegisterReflectionForBinding으로 간편히 등록한다.

```java
@Configuration
@RegisterReflectionForBinding({ UserDto.class, OrderDto.class })
public class JacksonHintsConfig { }
```

## 4. AOT 호환을 깨뜨리는 패턴들

런타임에 클래스를 동적으로 결정하는 코드가 가장 자주 깨진다.

```java
Class<?> clazz = Class.forName(props.getHandlerClassName()); // 힌트 필요
Object proxy = Proxy.newProxyInstance(cl, interfaces, handler); // 인터페이스 힌트 필요
```

클래스패스 스캐닝 플러그인 구조, 런타임 동적 프록시 모두 힌트 등록 또는 설계 변경이 필요하다. Spring 내부 프록시(트랜잭션 등)는 빌드타임 프록시로 미리 만들지만, 애플리케이션이 직접 만드는 동적 프록시는 직접 힌트를 줘야 한다.

## 5. GraalVM Reachability Metadata 생태계

널리 쓰이는 라이브러리의 힌트는 Reachability Metadata Repository가 모아 둔다. native-build-tools가 자동으로 가져온다. 없는 것은 native-image-agent로 추적한다.

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar app.jar
```

에이전트는 실행된 경로만 기록하므로, 테스트가 커버하지 못한 경로는 누락되어 네이티브에서 터진다. 통합 테스트로 주요 경로를 모두 밟는 것이 메타데이터 완성도의 관건이다.

## 6. 트레이드오프: 언제 네이티브를 택하는가

| 항목 | JVM (JIT) | Native Image (AOT) |
|---|---|---|
| 시작 시간 | 수 초 | 수십 ms~수백 ms |
| 유휴 메모리 | 상대적으로 큼 | 상대적으로 작음 |
| 피크 처리량 | 워밍업 후 최고 | 대체로 JIT보다 낮음 |
| 빌드 시간 | 짧음 | 매우 김(수 분) |
| 동적 기능 | 자유 | 힌트 필요 |

네이티브는 시작 속도·메모리 효율을 얻는 대신 피크 처리량·빌드 편의·동적 유연성을 희생한다. 서버리스, 빠른 스케일, 고밀도 배포, CLI에 적합하고, 장시간 구동·최대 처리량이 중요하면 JVM JIT이 더 유리한 경우가 많다.

## 7. 빌드 파이프라인 최적화

일반 JVM 테스트는 매 커밋 빠르게, 네이티브 빌드와 nativeTest는 별도 워크플로로 분리한다. 불필요 자동 구성 제외와 미사용 스타터 제거로 분석 그래프를 줄이면 빌드가 빨라진다. 메모리·시작 시간 개선은 반드시 동일 워크로드로 JVM 버전과 측정 비교해 근거로 남긴다.

## 8. 네이티브 테스트와 흔한 런타임 에러 해부

| 증상 | 근본 원인 | 해결 |
|---|---|---|
| ClassNotFoundException | 리플렉션 힌트 누락 | RuntimeHints 등록 / 에이전트 재수집 |
| 리소스 not found | resource 힌트 누락 | registerPattern |
| 역직렬화 실패 | 바인딩 타입 힌트 누락 | @RegisterReflectionForBinding |

까다로운 것은 빌드 시점 초기화다. 빌드타임에 초기화된 클래스가 빌드 환경 값(시각·환경변수·난수 시드)을 정적 필드에 캐시하면 그 값이 바이너리에 박힌다. 보안 난수·암호 키는 반드시 런타임 초기화로 강제한다.

```bash
--initialize-at-run-time=com.example.SecureRandomHolder
```

핵심은 "통합 테스트가 모든 리플렉션 경로를 한 번씩 밟게 하라"는 원칙이다.

## 참고

- Spring Boot Reference — GraalVM Native Image Support
- Spring Framework Reference — Ahead of Time Optimizations / RuntimeHints
- GraalVM Native Image 공식 문서 — Reachability Metadata
- GraalVM Reachability Metadata Repository
- native-build-tools 공식 문서
