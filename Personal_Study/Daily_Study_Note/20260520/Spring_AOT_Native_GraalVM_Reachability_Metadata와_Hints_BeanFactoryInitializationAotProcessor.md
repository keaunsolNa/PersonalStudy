Notion 원본: https://www.notion.so/3665a06fd6d381c0b399c5953986ecf0

# Spring AOT·Native Image — GraalVM Reachability Metadata와 Hints, BeanFactoryInitializationAotProcessor

> 2026-05-20 신규 주제 · 확장 대상: Spring

## 학습 목표

- Spring Boot 3 AOT 처리 단계가 BeanDefinition을 정적 Java 소스(`__BeanDefinitions`)로 직렬화하는 흐름을 추적한다
- GraalVM `native-image`가 closed-world 가정 아래 도달 가능성(reachability)을 계산하는 방식과, reflection/proxy/resource를 메타데이터로 표현하는 형식을 식별한다
- `RuntimeHintsRegistrar`·`@ImportRuntimeHints`·`BeanFactoryInitializationAotProcessor`·`BeanRegistrationAotProcessor`의 책임 경계를 구분한다
- 라이브러리에 hints가 없을 때 GraalVM Reachability Metadata Repository(GRMR) 또는 `reachability-metadata.json`을 작성해 빌드 실패를 회피한다

## 1. AOT 처리란 무엇을 미리 하는가

Spring Boot 3에서 도입된 AOT(ahead-of-time) 처리는 *애플리케이션 컨텍스트의 정적 형태*를 빌드 시점에 산출하는 단계다. 런타임에 `@ComponentScan`이 클래스패스를 훑고 `BeanDefinitionReader`가 `@Bean` 메서드를 평가하던 작업을, 빌드 단계에서 한 번 수행해 결과를 자바 소스 코드(`*__BeanDefinitions.java`, `*__BeanFactoryRegistrations.java`)와 hint 메타데이터(`META-INF/native-image/<group>/<artifact>/reflect-config.json` 등)로 떨궈둔다. 런타임에는 이 정적 등록 코드를 호출만 하면 되므로 클래스패스 스캔과 일부 자기참조 후보 평가가 사라지고, 더 중요하게는 GraalVM이 빌드 시점에 필요한 reachability 정보를 모두 확보하게 된다.

AOT는 두 가지 다른 시나리오에서 의미가 다르다. JVM 위에서도 AOT 결과를 활용하면 부팅이 단축되지만 reflection·classpath scan은 여전히 가능하다. 반면 GraalVM `native-image`로 빌드할 때는 AOT 결과가 *유일한 진실*이 된다. native binary는 closed-world이라 빌드 후 새 클래스가 등장하거나 reflection 호출 대상이 동적으로 결정될 수 없고, 메타데이터에 등록되지 않은 reflection은 런타임에 `ClassNotFoundException`·`MissingReflectionRegistrationException`으로 떨어진다.

## 2. AOT 처리 파이프라인 — 어디서 무엇이 실행되는가

`spring-boot-maven-plugin`의 `process-aot` goal(또는 Gradle `bootBuildImage` 전 단계)이 호출되면 별도 JVM에서 `SpringApplication`을 *aot mode*로 가동한다. 이때 `AotApplicationContextInitializer`는 컨텍스트를 정상적으로 refresh하지 않고, `BeanFactoryInitializationAotProcessor`·`BeanRegistrationAotProcessor`를 차례로 적용해 정적 코드를 생성한다.

```java
public class TenantBeanRegistrationProcessor implements BeanRegistrationAotProcessor {
    @Override
    public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean rb) {
        if (!rb.getBeanClass().isAnnotationPresent(MultiTenant.class)) {
            return null;
        }
        return (ctx, code) -> code.getMethods()
            .add("registerTenantHooks", method -> method.addStatement(
                "$T.registerHooks(beanFactory, $S)",
                TenantSupport.class, rb.getBeanName()));
    }
}
```

`BeanFactoryInitializationAotProcessor`는 *전체 beanFactory* 수준에서 한 번 호출되어, 예를 들어 모든 `@ConfigurationProperties` 클래스에 reflection hint를 추가하는 식으로 사용된다. `BeanRegistrationAotProcessor`는 *개별 RegisteredBean마다* 호출되어 그 빈을 등록하는 정적 메서드의 코드를 가공한다.

## 3. RuntimeHints API

```java
public class CryptoHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(BCrypt.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.resources().registerPattern("crypto/keys/*.pem");
        hints.proxies().registerJdkProxy(Hashing.class, Versioned.class);
        hints.serialization().registerType(Token.class);
        hints.jni().registerType(NativeAdapter.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
}
```

`@ImportRuntimeHints(CryptoHints.class)`로 가져오거나 `META-INF/spring/aot.factories`에 등록하면 AOT 단계에서 평가되어 `META-INF/native-image/<artifact>/reflect-config.json` 등에 누적된다. `MemberCategory`는 INVOKE_PUBLIC_METHODS, INTROSPECT_DECLARED_METHODS 등 여덟 단계로 나뉜다.

특히 `INVOKE_PUBLIC_METHODS`와 `INTROSPECT_PUBLIC_METHODS`의 차이가 자주 함정이 된다. Jackson은 둘 다 요구하기 때문에 모델 클래스에 `@RegisterReflectionForBinding`을 다는 게 일반적이다.

## 4. GraalVM closed-world와 reachability

GraalVM `native-image`는 entry point(`main`)에서 시작해 모든 *정적으로 도달 가능한 메서드와 필드*를 분석한다. 이 분석은 *type-flow analysis*로, 변수에 흐를 수 있는 타입 집합을 추적해 어떤 메서드가 어떤 객체로 dispatch될 수 있는지 추론한다. closed-world 가정이 깨지는 지점이 셋이다. (1) reflection (2) resource loading (3) dynamic proxy/serialization/JNI.

빌드 시 `-H:+ReportUnsupportedElementsAtRuntime`을 켜면 unsupported 호출을 런타임 오류로 미루지만, *실제 운영에서는 빌드 실패로 처리*하는 게 권장된다.

## 5. Reachability Metadata Repository

GraalVM 팀과 커뮤니티는 자주 쓰이는 OSS 라이브러리의 메타데이터를 *GRMR*에 모아 두었다. `native-maven-plugin` 0.10 이상은 빌드 시 의존성의 GAV를 키로 GRMR에서 메타데이터를 가져와 자동 병합한다.

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <configuration>
        <metadataRepository>
            <enabled>true</enabled>
            <version>0.3.13</version>
        </metadataRepository>
    </configuration>
</plugin>
```

라이브러리가 GRMR에 없거나 버전 차이가 크면 직접 `META-INF/native-image/<group>/<artifact>/reachability-metadata.json`을 작성한다. JSON 스키마는 GraalVM 24.1부터 통합 포맷이다.

## 6. 동적 reflection·proxy·resource — 실전 함정

JPA `@Entity` 모델은 Spring AOT가 자동으로 reflection hint를 추가하지만, *enumerated values*, *embedded types*, *custom `AttributeConverter`*는 누락되기 쉽다.

```java
@RegisterReflection(classes = {Status.class, AddressConverter.class},
    memberCategories = {INVOKE_DECLARED_CONSTRUCTORS, INVOKE_DECLARED_METHODS})
@Configuration
class JpaReflectionHints {}
```

Spring `@Async`·`@Transactional` 같은 AOP 프록시는 JDK dynamic proxy가 만들어진다. AOT는 인터페이스 기반 프록시는 자동으로 등록하지만, *클래스 기반(CGLIB) 프록시*는 별도로 등록해야 한다.

리소스 패턴은 *glob*이 아니라 *정규식*이다. `static/**/*.png`은 동작하지 않고 `static/.*\\.png`으로 써야 한다.

## 7. 빌드 옵션·성능·메모리 트레이드오프

| 항목 | JVM (Boot 3, JIT 워밍업 후) | Native Image |
|---|---|---|
| 콜드 스타트(부팅) | 2.5~4초 | 0.05~0.15초 |
| 첫 응답 RPS(워밍업 전) | 200~500 | 1500~3000 |
| 정상 상태 throughput | 8000~12000 RPS | 5000~8000 RPS |
| RSS 메모리 | 350~500MB | 60~120MB |
| 빌드 시간 | 30초 | 3~6분 |
| reflection 한계 | 무제한 | 메타데이터 필수 |

JIT가 충분히 워밍업된 뒤 *정상 상태 throughput* 자체는 native가 종종 떨어진다. PGO를 도입하면 격차가 좁혀지지만 두 단계 빌드가 필요해 CI가 복잡해진다. *콜드 스타트와 메모리가 핵심인 워크로드*에는 native, *지속적인 고처리량 RPC*는 JVM이 일반적 선택이다.

## 8. 검증 — 빠진 hint를 어떻게 찾는가

GraalVM은 `-agentlib:native-image-agent=config-output-dir=...`로 *기존 JVM 실행*을 추적해 메타데이터 초안을 만들어 준다.

```bash
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/com.example/app \
     -jar build/libs/app.jar
```

agent는 *실제 실행 경로*를 보기 때문에 테스트 커버리지가 낮으면 hint도 그만큼 빠진다. e2e 테스트, smoke 시나리오, 관리자 화면 등 reflection이 일어나는 모든 경로를 한 번씩은 밟아야 한다.

## 9. 운영 단계 권장 패턴

테스트는 GraalVM CE의 `nativeTest` 태스크로 별도 실행한다. JUnit 5는 `@DisabledInNativeImage`·`@EnabledInNativeImage`로 native 한정 분기를 표시할 수 있다. CI 비용을 고려해 PR 단위에서는 JVM 테스트만, main 머지 후 nightly로 nativeTest를 돌리는 구성이 흔하다. 운영 배포에서는 멀티 아키텍처(arm64/amd64) 빌드를 분리한다. native binary는 `linux-amd64`·`linux-arm64`가 ABI 호환되지 않으므로 K8s 노드 아키텍처에 맞춰 이미지 두 종을 생성한다.

## 참고

- Spring Boot Reference — Ahead-of-Time Processing
- GraalVM Documentation — Reachability Metadata, native-image options
- Spring Framework Reference — Runtime Hints API, AotProcessor
- GraalVM Reachability Metadata Repository (oracle/graalvm-reachability-metadata GitHub)
- Sébastien Deleuze, "Spring Boot 3.x and GraalVM Native Image" (Spring blog)
