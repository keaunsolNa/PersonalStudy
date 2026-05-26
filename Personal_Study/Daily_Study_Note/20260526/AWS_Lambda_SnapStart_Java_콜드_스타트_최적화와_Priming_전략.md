Notion 원본: https://www.notion.so/36c5a06fd6d38182bbe6e3411ab15150

# AWS Lambda SnapStart — Java 콜드 스타트 최적화와 Priming 전략

> 2026-05-26 신규 주제 · 확장 대상: AWS / JAVA / Spring

## 학습 목표
- Lambda 콜드 스타트의 INIT/INVOKE 분해를 이해하고 Java 가 가장 느린 구간을 식별한다
- SnapStart 의 Firecracker microVM snapshot + CRaC 메커니즘을 설명하고 SAM/CDK 로 적용한다
- `BeforeCheckpoint` 후크와 `org.crac.Resource` 를 이용한 Priming 패턴을 구현한다
- Spring Boot 3.2+ checkpoint 통합과 Provisioned Concurrency 와의 트레이드오프를 결정한다

## 1. Lambda 콜드 스타트 분해 — Java 가 가장 느린 이유

콜드 스타트는 한 덩어리가 아니라 네 단계로 쪼개진다. AWS X-Ray 의 `Initialization` subsegment 를 펼치면 다음 순서가 보인다.

1. **Download** — 함수 코드(.zip) 또는 컨테이너 이미지 레이어를 S3 → worker 로 끌어온다. 50MB Java fat jar 의 경우 200~400ms
2. **Extract** — zip 해제 / 컨테이너 layer mount. 100~200ms
3. **Runtime Init** — JVM 부트스트랩. `java -jar` 시작, system class loader 초기화. 600~900ms (Corretto 21 기준)
4. **Handler Init** — 사용자 코드의 static block, Spring `ApplicationContext refresh`, Hibernate `EntityManagerFactory` 빌드. 여기서 2~5초가 추가된다

Python/Node 가 평균 200~500ms 인 반면 Spring Boot Java 는 평균 4~6초로 측정된다. 차이의 90% 는 (3) + (4) 다. JVM 은 시작 시 수천 개의 클래스를 lazy 로드하며 reflection/proxy 기반 프레임워크(Spring, Jackson, Hibernate)는 첫 호출에서 ASM 으로 클래스 생성, AOP proxy 생성, JPQL 파싱 등을 수행한다.

INVOKE 단계 자체는 동일하므로(코드 수행), 최적화 타깃은 (3) + (4) 의 결과를 어떻게 "캐싱"하느냐다. 기존 해법은 두 가지뿐이었다 — Provisioned Concurrency(콜드 스타트를 제거하는 대신 항상 비용 발생), GraalVM Native Image(빌드 복잡도와 reflection 메타데이터 지옥). SnapStart 는 세 번째 해법으로 등장했다.

## 2. SnapStart 메커니즘 — Firecracker MicroVM Snapshot

SnapStart 는 함수 `PublishVersion` 시점에 한 번 INIT 을 끝까지 수행한 뒤, **Firecracker microVM 의 메모리/디스크 상태 전체를 snapshot 으로 저장**한다. INVOKE 요청이 들어오면 새 microVM 을 spin-up 하지 않고 저장된 snapshot 을 restore 한다.

- **Firecracker snapshot** — VMM 레벨에서 게스트 메모리 페이지와 vCPU 레지스터 상태를 저장. AWS 는 이를 S3 와 worker-local cache 양쪽에 두고, 동일 AZ 의 worker 에 lazy page-in 으로 복원한다(Copy-on-Read)
- **CRaC(Coordinated Restore at Checkpoint)** — OpenJDK 프로젝트로, JVM 이 자신의 내부 상태(thread, JIT 코드, heap)를 checkpoint/restore 할 수 있도록 협조한다. AWS Corretto 17/21 에 CRaC 가 포함되어 있다

CRaC 는 checkpoint 직전 `beforeCheckpoint()` 콜백으로 "정리할 시간" 을 주고, restore 직후 `afterRestore()` 콜백으로 "다시 열 시간" 을 준다. 결과적으로 INVOKE 시 (3) Runtime Init + (4) Handler Init 이 통째로 제거된다. 남는 건 snapshot restore (메모리 페이지 매핑 + JVM resume) 약 200~400ms.

## 3. 적용 절차 — SAM / CDK 설정

SnapStart 는 함수 단위 속성이며 **published version** 에서만 동작한다. `$LATEST` alias 는 SnapStart 가 적용되지 않으므로 alias 기반 배포가 사실상 강제된다.

```yaml
Resources:
  OrderApi:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: order-api
      Runtime: java21
      Handler: com.example.OrderHandler::handleRequest
      MemorySize: 2048
      Timeout: 30
      CodeUri: ./build/libs/order-api.jar
      SnapStart:
        ApplyOn: PublishedVersions
      AutoPublishAlias: live
      AutoPublishAliasAllProperties: true
```

CDK v2 (TypeScript):

```typescript
import { Function, Runtime, Code, SnapStartConf } from 'aws-cdk-lib/aws-lambda';

const fn = new Function(this, 'OrderApi', {
  runtime: Runtime.JAVA_21,
  handler: 'com.example.OrderHandler::handleRequest',
  code: Code.fromAsset('build/libs/order-api.jar'),
  memorySize: 2048,
  timeout: Duration.seconds(30),
  snapStart: SnapStartConf.ON_PUBLISHED_VERSIONS,
});
```

**비용** — snapshot 은 version 당 캐시되며 AWS 가 별도 과금한다. 자주 publish 하면서 오래된 version 을 정리하지 않으면 비용이 누적된다.

## 4. Priming 패턴 — BeforeCheckpoint 에서 사전 캐싱

SnapStart 의 본질은 "INIT 시점에 최대한 많은 일을 끝내두면, INVOKE 가 빠르다" 다. 따라서 평소엔 lazy 로 두던 작업을 INIT 단계에 강제로 끌어와야 한다. 이것이 Priming 이다.

```java
import org.crac.Core;
import org.crac.Resource;
import org.crac.Context;

public class HttpClientPrimer implements Resource {

    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpClientPrimer() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.mapper = new ObjectMapper();
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> ctx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.example.com/health"))
            .GET().build();
        client.send(req, HttpResponse.BodyHandlers.discarding());

        mapper.writeValueAsString(new OrderDto("warmup", BigDecimal.ZERO));
        mapper.readValue("{\"id\":\"x\",\"amount\":0}", OrderDto.class);
    }

    @Override
    public void afterRestore(Context<? extends Resource> ctx) throws Exception {
        // restore 직후엔 HTTP keep-alive connection 이 죽어 있으므로 새로 만든다
    }
}
```

핵심 원칙은 **"checkpoint 이전에 CPU intensive 한 일을, 이후엔 socket 같은 외부 자원만 다시 연다"** 다.

## 5. Spring Boot 3.2+ SnapStart 통합

Spring Boot 3.2 부터 정식으로 CRaC 를 지원한다.

```properties
spring.context.checkpoint=onRefresh
```

이 설정은 `ApplicationContext.refresh()` 가 끝난 직후 자동으로 `Core.checkpointRestore()` 를 호출한다. Bean graph 가 완전히 구축되고 `@PostConstruct` 까지 끝난 상태에서 snapshot 이 떠진다.

```java
@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}

@Component
class JdbcWarmup implements Resource {
    private final DataSource dataSource;

    JdbcWarmup(DataSource dataSource) {
        this.dataSource = dataSource;
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> ctx) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        }
    }

    @Override
    public void afterRestore(Context<? extends Resource> ctx) throws Exception {
        // HikariCP 는 자동으로 connection 재생성
    }
}
```

**AOT 와의 결합** — Spring Boot AOT processing 으로 reflection 메타데이터를 빌드타임에 생성해두면 SnapStart INIT 자체가 더 빠르다.

**GraalVM Native Image vs SnapStart** — Native Image 는 콜드 스타트 100ms 이하까지 잡지만 reflection 등록 지옥, 호환성 문제, 빌드 시간 5~10분이 비용. SnapStart 는 200~400ms 정도지만 빌드는 일반 jar 그대로다.

## 6. Snapshot 안전성 — Ephemeral State 재초기화

snapshot 에 박제되면 안 되는 상태가 몇 가지 있다.

| 상태 | 위험 | 해결 |
|---|---|---|
| `SecureRandom` seed | 모든 instance 가 동일한 seed → 보안 결함 | `afterRestore` 에서 reseed |
| `UUID.randomUUID()` 캐시 | 동일 | 위와 동일 |
| JDBC/Redis socket | restore 후 broken pipe | `afterRestore` 에서 reconnect |
| 시간 기반 캐시 TTL | snapshot 시점 timestamp 가 박제됨 | `afterRestore` 에서 clear |
| AWS SDK credentials | IAM session token 만료 가능 | SDK v2 자동 refresh |

```java
@Override
public void beforeCheckpoint(Context<? extends Resource> ctx) {
    redisConnection.close();
    httpClient.close();
}

@Override
public void afterRestore(Context<? extends Resource> ctx) {
    this.redisConnection = redisClient.connect();
    this.httpClient = HttpClient.newHttpClient();
    this.secureRandom = SecureRandom.getInstanceStrong();
}
```

## 7. 측정 — Restore Subsegment 와 P99 Latency

| 시나리오 | Spring Boot 3.2 / Corretto 21 / 2048MB | 비고 |
|---|---|---|
| SnapStart OFF, cold | 4800ms ~ 6200ms | Spring context refresh 가 지배적 |
| SnapStart ON, first restore (colder-than-cold) | 600ms ~ 900ms | snapshot S3 → worker fetch 포함 |
| SnapStart ON, warm restore | 220ms ~ 380ms | worker-local snapshot cache hit |
| Warm invoke (재사용된 컨테이너) | 5ms ~ 30ms | 동일, SnapStart 영향 없음 |
| Provisioned Concurrency | 5ms ~ 30ms | 항상 warm, 24/7 비용 |

**Colder-than-cold** — snapshot 자체가 worker-local 에 캐시되어 있지 않은 첫 invoke 는 S3 에서 끌어와야 한다.

```
REPORT RequestId: ...  Duration: 312.45 ms  Billed Duration: 313 ms
Memory Size: 2048 MB  Max Memory Used: 412 MB
Init Duration: 0.74 ms  Restore Duration: 287.12 ms
```

`Init Duration` 이 1ms 미만이라는 점에 주목한다 — INIT 은 publish 시점에 이미 끝났기 때문이다.

## 8. 한계와 함정 — Provisioned Concurrency 와의 비교

- **트리거 제약** — SnapStart 함수는 `lambda:InvokeFunction` 와 함께 SQS/Kinesis/DynamoDB Stream 등 주요 event source 를 지원
- **Container Image 지원 시기** — 2024년 중반 이후 컨테이너 이미지 런타임 지원 점진적 추가
- **Version Pinning 강제** — `$LATEST` 에는 동작하지 않으므로 모든 호출이 version 또는 alias 로 가야 한다
- **Publish 시 INIT 실패 = 배포 실패** — 정적 초기화에서 외부 API 를 때리는 코드는 위험
- **Provisioned Concurrency 와의 선택** — PC 는 5ms 콜드 스타트 보장하지만 시간당 고정 비용. SnapStart 는 200~400ms 로 만족할 수 있는 트래픽에 적합. 결제 API 처럼 P99 100ms 가 요구되면 PC + SnapStart 병용
- **메모리 사용** — snapshot restore 직후 JVM 은 이미 warmed up 된 상태로 보이지만, JIT C2 컴파일까지 끝난 것은 아니다

운영 체크리스트 — (1) AutoPublishAlias 설정, (2) `spring.context.checkpoint=onRefresh`, (3) JDBC/Redis 같은 socket resource 에 `Resource` 구현, (4) X-Ray `Restore` subsegment 모니터링, (5) 오래된 version snapshot 정리 Lambda, (6) PC 와의 비용/latency 트레이드오프 결정.

## 참고
- AWS Docs — Improving startup performance with Lambda SnapStart (https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html)
- AWS Docs — Lambda SnapStart for Java runtimes (Corretto 11/17/21)
- AWS Blog — "Reducing Java cold starts on AWS Lambda functions with SnapStart" (Mark Sailes, 2022)
- AWS Blog — "Optimizing Spring Boot applications on AWS Lambda with SnapStart" (2023)
- OpenJDK CRaC project — https://openjdk.org/projects/crac/
- Spring Boot Reference — Checkpoint and Restore with CRaC (3.2+ 문서 섹션)
- re:Invent 2022 SVS402 — "Best practices for advanced serverless developers" (SnapStart 발표)
- re:Invent 2023 SVS307 — "Building serverless Java applications with AWS"
- Firecracker design doc — microVM snapshot/restore (GitHub firecracker-microvm/firecracker)
