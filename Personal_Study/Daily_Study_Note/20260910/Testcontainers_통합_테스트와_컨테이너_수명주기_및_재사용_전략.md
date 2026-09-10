Notion 원본: https://www.notion.so/3d75a06fd6d381daa846df736e5204e4

# Testcontainers 통합 테스트와 컨테이너 수명주기 및 재사용 전략

> 2026-09-10 신규 주제 · 확장 대상: TDD

## 학습 목표

- 프로덕션과 같은 DB 이미지로 통합 테스트를 구성해 H2 대체가 만드는 미탐을 제거한다.
- `@Container` 필드 위치, 컨텍스트 캐시, 재사용을 조합해 스위트 실행 시간을 설계한다.
- 트랜잭션 롤백 / truncate / 컨테이너 재생성 중 맞는 격리 전략을 고른다.
- CI 의 Docker 제약을 파악하고 테스트를 태그로 분리해 파이프라인을 통제한다.

## 1. 통합 테스트의 신뢰성 문제

속도를 이유로 H2 를 끼우면 검증 대상이 "진짜 DB 와 동작하는가"에서 "H2 와 동작하는가"로 바뀐다. H2 에서 통과한 SQL 이 운영에서 깨지면 미탐이고, H2 가 지원하지 않아 우회하면 그 경로는 영영 검증되지 않는다. 차이는 upsert, `jsonb` 와 `->>`, `generate_series`, 시퀀스 캐시, 윈도 함수 프레임 절에서 드러난다. `MODE=PostgreSQL` 은 호환 "모드"일 뿐 구현체가 아니다.

```java
@Modifying
@Query(value = """
        INSERT INTO member_profile (member_id, attributes) VALUES (:memberId, CAST(:attrs AS jsonb))
        ON CONFLICT (member_id)
        DO UPDATE SET attributes = member_profile.attributes || EXCLUDED.attributes
        """, nativeQuery = true)
void upsertProfile(@Param("memberId") long memberId, @Param("attrs") String attrs);
```

H2 로는 스키마 생성부터 막히고, 막히면 보통 테스트를 지운다. 가장 복잡한 SQL 이 가장 검증되지 않는 역설이다. 공유 개발 DB 도 답이 아니다. 두 명이 동시에 같은 테이블을 비우면 원인이 코드인지 타이밍인지 구분할 수 없다. Testcontainers 는 프로덕션과 같은 태그의 이미지를 직접 띄우고 인스턴스를 해당 실행에만 귀속시켜 방언과 격리를 함께 확보한다.

## 2. 동작 원리와 wait strategy

Testcontainers 는 Docker 데몬을 향한 자바 클라이언트다. `DockerClientFactory` 가 `DOCKER_HOST` 와 Docker Desktop·Colima·Podman 소켓 순으로 데몬을 찾고 없으면 즉시 예외를 던진다. `start()` 는 이미지 풀 → 생성 → 시작 → wait 대기 순으로 진행한다. 선언한 내부 포트는 호스트의 **임의의 빈 포트**에 매핑되므로, 병렬 실행에도 충돌이 없지만 접속 정보는 런타임에 조회해야 한다.

```java
GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        .withStartupTimeout(Duration.ofSeconds(120));
redis.start();
Integer port = redis.getMappedPort(6379); // 내부 6379 -> 호스트 랜덤 포트
```

flaky 테스트의 최대 원인은 wait strategy 선택 실패다. 기본값인 포트 리스닝은 소켓만 열리면 통과해 초기화 스크립트를 도는 DB 를 준비 완료로 오인한다.

| wait strategy | 판정 기준 | 주의점 |
| --- | --- | --- |
| `Wait.forListeningPort()` | TCP 연결 성공 | 부팅 중 조기 통과 |
| `Wait.forLogMessage(regex, n)` | 로그 정규식 n 회 일치 | 이미지 버전 바뀌면 깨짐 |
| `Wait.forHttp("/health")` | HTTP 응답 코드 | 경로·포트 지정 필요 |
| `Wait.forHealthcheck()` | Docker HEALTHCHECK | 이미지가 정의해야 동작 |

첫 컨테이너와 함께 `testcontainers/ryuk` 사이드카가 올라가고 생성된 모든 자원에 세션 라벨이 붙는다. JVM 이 죽으면 Ryuk 이 소켓 끊김을 감지해 정리하므로 종료 훅이 돌지 않아도 자원이 남지 않는다. 특권 컨테이너가 금지된 환경은 `TESTCONTAINERS_RYUK_DISABLED=true` 로 끄는데, 정리 책임이 파이프라인으로 넘어온다.

## 3. JUnit 5 수명주기

`@Testcontainers` 확장이 `@Container` 필드에 start/stop 을 걸어 준다. 핵심은 **필드가 static 인지가 곧 수명주기**라는 점이다.

```java
@Testcontainers
class ContainerLifecycleTest {

    // 클래스당 1회: BeforeAll 에서 start, AfterAll 에서 stop
    @Container
    static final PostgreSQLContainer<?> SHARED = new PostgreSQLContainer<>("postgres:16-alpine");

    // 테스트 메서드마다 1회: BeforeEach 에서 start, AfterEach 에서 stop
    @Container
    private final GenericContainer<?> perTest = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);
}
```

인스턴스 필드는 완전한 격리를 주지만 비용이 가혹하다. 기동 3초짜리를 테스트 40개에 붙이면 그것만으로 2분이 사라진다. 기본값은 static 이고, 인스턴스 필드는 브로커를 재시작시키는 테스트처럼 되돌릴 수 없는 케이스에만 쓴다. 메서드를 `concurrent` 로 병렬화하면 static 컨테이너를 공유해 경합이 생기므로 `@ResourceLock` 으로 직렬화한다.

## 4. Spring Boot 연동

3.1 이전에는 `@DynamicPropertySource` 가 유일했다. static 메서드에서 `Supplier` 를 등록하면 `Environment` 생성 시 반영된다. 3.1 부터는 `spring-boot-testcontainers` 의 `@ServiceConnection` 이 이를 대신한다. 컨테이너 타입을 보고 프레임워크가 커넥션 정보를 판단하므로 키를 외울 필요도, 오타로 실패할 일도 없다.

```java
@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis") // 이미지명이 관례와 다르면 name 으로 지정
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    }
}

@SpringBootTest
@Import(IntegrationTestContainers.class)
class OrderServiceIntegrationTest {
    // 프로퍼티 등록 없이 컨테이너에 연결된 DataSource 를 주입받는다
}
```

컨테이너를 `@Bean` 으로 올리면 시작·종료 책임이 컨텍스트로 넘어가 **컨테이너 수명주기가 컨텍스트 캐시에 종속**된다. 지원 타입(JDBC, R2DBC, Redis, Kafka, MongoDB, Elasticsearch 등)이면 `@ServiceConnection`, 커스텀 키가 필요하면 `@DynamicPropertySource` 를 쓰고 섞어도 된다.

## 5. 컨텍스트 캐싱과 실행 시간

캐시 키는 설정 클래스, 활성 프로파일, `@TestPropertySource` 값, 컨텍스트 커스터마이저, 웹 환경 종류를 합친 값이고 하나라도 다르면 컨텍스트가 새로 만들어진다. 기본 캐시 크기는 32 이며 `spring.test.context.cache.maxSize` 로 조절한다. 클래스마다 프로퍼티를 다르게 주면 컨텍스트가 갈라지고, 컨테이너를 빈으로 올렸다면 컨테이너도 늘어난다. `@DirtiesContext` 는 컨텍스트를 닫아 다음 테스트가 전체 부팅을 다시 겪게 하므로 데이터 정리로 대신해야 한다.

가장 확실한 최적화는 컨테이너를 컨텍스트에서 떼어내 **JVM 전역 싱글턴**으로 올리는 것이다.

```java
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("app");
        REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
        // 클래스 로딩 시 1회. 병렬 기동으로 시간을 줄이고 stop() 은 호출하지 않는다
        Startables.deepStart(POSTGRES, REDIS).join();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
```

컨텍스트가 몇 개로 갈라지든 컨테이너는 JVM 당 한 세트다. 단 `maxParallelForks` 로 JVM 을 여러 개 띄우면 그 수만큼 세트가 생긴다.

## 6. 컨테이너 재사용(reuse)

싱글턴을 써도 JVM 이 새로 뜰 때마다 컨테이너는 다시 시작된다. `withReuse(true)` 는 JVM 종료 후에도 컨테이너를 살려 두고 다음 실행에서 그대로 붙는다. 활성화 조건은 두 겹이다. 코드의 `withReuse(true)` 와 `~/.testcontainers.properties` 의 `testcontainers.reuse.enable=true` 가 모두 참이어야 한다. 후자는 커밋되지 않는 개인 설정이라 로컬에서만 켜진다.

재사용 판단은 해시로 이뤄진다. 이미지, 환경 변수, 커맨드, 노출 포트, 라벨, 복사 파일 등 설정을 모아 해시를 만들어 라벨로 붙이고, 같은 해시의 실행 중 컨테이너가 있으면 재사용한다. 설정을 한 글자만 바꿔도 새 컨테이너가 뜨고 옛 것은 남으며, 재사용 대상은 Ryuk 의 정리에서 제외된다.

CI 에서 켜면 안 된다. 러너가 잡마다 폐기되면 이득이 0 이고, 상태가 남는 자체 호스팅에서는 앞선 빌드 데이터가 다음 빌드로 샌다. 브랜치 A 가 만든 행을 브랜치 B 가 보는 상황은 디버깅이 거의 불가능하다. 로컬에서도 컨테이너가 살면 데이터가 남으므로 Flyway clean 이나 전체 truncate 훅이 반드시 따라붙어야 한다.

## 7. 데이터 초기화와 격리 전략

Flyway·Liquibase 는 운영과 같은 마이그레이션을 태우므로 스크립트 자체가 검증된다. 이를 기본으로 쓰고 테스트별 데이터만 `@Sql` 로 얹는 조합이 무난하며, `ddl-auto=create` 는 엔티티와 스키마의 불일치를 감추므로 피한다.

| 정리 전략 | 속도 | 격리 수준 | 한계 |
| --- | --- | --- | --- |
| 트랜잭션 롤백 | 가장 빠름 | 단일 커넥션 | 다중 커넥션·비동기에서 무력 |
| truncate 후 재삽입 | 중간 | 스키마 전체 | 테이블 많으면 비용, FK 순서 |
| 컨테이너 재생성 | 가장 느림 | 완전 | 테스트당 수 초, 전면 적용 불가 |

롤백이 통하지 않는 세 경우가 있다. `webEnvironment = RANDOM_PORT` 로 실제 HTTP 요청을 보내면 서버가 별도 커넥션에서 커밋하므로 테스트 스레드의 롤백이 닿지 않는다. `Propagation.REQUIRES_NEW` 로 분리된 트랜잭션은 바깥이 롤백돼도 커밋된 채 남는다. `@Async` 나 `@TransactionalEventListener(phase = AFTER_COMMIT)` 는 테스트 트랜잭션에 참여하지 않고 롤백 시 실행조차 되지 않는다. 이 셋은 truncate 로 내려간다.

```java
@Transactional
public void clear() {
    entityManager.flush();
    // FK 제약을 잠시 무시하면 삭제 순서를 신경 쓰지 않아도 된다
    entityManager.createNativeQuery("SET session_replication_role = 'replica'").executeUpdate();
    for (String tableName : tableNames) {
        entityManager.createNativeQuery("TRUNCATE TABLE " + tableName + " RESTART IDENTITY CASCADE")
                .executeUpdate();
    }
    entityManager.createNativeQuery("SET session_replication_role = 'origin'").executeUpdate();
}
```

`RESTART IDENTITY` 는 시퀀스까지 되돌려 ID 에 의존하는 테스트가 실행 순서에 흔들리지 않게 해 준다.

## 8. DB 밖의 활용

외부 의존성을 모킹 대신 진짜로 띄우면 직렬화 포맷, 타임아웃, 재시도 같은 통합 지점의 버그가 드러난다.

```java
// Kafka 는 컨슈머 그룹·오프셋을, Elasticsearch 는 분석기·매핑 오류를 실제로 드러낸다
@Container
static final KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

@Container
static final ElasticsearchContainer ES = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.13.4"))
        .withEnv("xpack.security.enabled", "false");

// LocalStack 은 S3/SQS 를, MockServer 는 파트너 API 의 5xx·지연 응답을 대체한다
@Container
static final LocalStackContainer AWS = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.5")).withServices(Service.S3);
```

`@ServiceConnection` 은 Kafka·Elasticsearch 도 인식한다. LocalStack 이나 `MockServerContainer` 처럼 커스텀 키가 필요하면 `registry.add("partner.api.base-url", mockServer::getEndpoint)` 로 등록한다. 컨테이너끼리 통신할 때는 매핑 포트가 아니라 네트워크 내부 별칭을 써야 한다.

```java
static final Network NETWORK = Network.newNetwork();

@Container
static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(NETWORK)
        .withNetworkAliases("db"); // 내부에서는 jdbc:postgresql://db:5432 로 접근

@Container
static final GenericContainer<?> APP = new GenericContainer<>("my-service:local")
        .withNetwork(NETWORK)
        .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/app")
        .dependsOn(DB);
```

`docker-compose.yml` 이 있다면 `ComposeContainer`(compose v2, 1.19+) 로 재사용할 수 있고 구버전 `DockerComposeContainer` 도 동작한다. 다만 운영 구성 변경이 테스트를 깨뜨리는 결합이 생긴다.

## 9. CI 와 운영

`ubuntu-latest` 러너에는 Docker 데몬이 있어 별도 설정 없이 동작한다. 반면 잡을 컨테이너 안에서 실행하는 구성이나 쿠버네티스 기반 자체 호스팅 러너는 dind 또는 소켓 마운트가 필요하다. rootless Docker 는 소켓이 `$XDG_RUNTIME_DIR/docker.sock` 이므로 `DOCKER_HOST` 를 지정하고, dind 에서 컨테이너가 보고하는 주소에 닿지 않으면 `TESTCONTAINERS_HOST_OVERRIDE` 를 쓴다. 이미지 pull 은 Docker Hub rate limit 의 원인이므로 사내 미러나 캐시로 줄인다.

스위트가 커지면 PR 파이프라인 전체를 느리게 두지 말고 `@Tag("integration")` 으로 등급을 나눈다.

```groovy
// build.gradle - PR 은 단위 테스트만, 통합 테스트는 별도 태스크로
tasks.named("test") { useJUnitPlatform { excludeTags "integration" } }

tasks.register("integrationTest", Test) {
    useJUnitPlatform { includeTags "integration" }
    maxParallelForks = 1 // 컨테이너 세트가 JVM 수만큼 늘어나는 것을 막는다
}
```

```yaml
# .github/workflows/ci.yml
on:
  pull_request:
  schedule:
    - cron: "0 18 * * *"   # UTC 18:00 = KST 03:00 야간 통합 테스트
jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - run: ./gradlew test
  integration:
    if: github.event_name == 'schedule'
    runs-on: ubuntu-latest
    env:
      TESTCONTAINERS_REUSE_ENABLE: "false"   # CI 에서는 재사용을 끈다
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew integrationTest
```

대가는 회귀 발견이 최대 하루 늦어진다는 점이며, 절충안은 핵심 경로만 PR 에 남기는 것이다. Testcontainers Cloud 는 러너에 Docker 를 설치할 수 없을 때 유효하지만, 유료이고 네트워크 왕복이 추가되며 바인드 마운트에 제약이 있다.

## 참고

- [Testcontainers for Java](https://java.testcontainers.org/)
- [Testcontainers - Reuse](https://java.testcontainers.org/features/reuse/)
- [Testcontainers - Waits](https://java.testcontainers.org/features/startup_and_waits/)
- [Spring Boot - Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Spring Framework - Testing](https://docs.spring.io/spring-framework/reference/testing.html)
