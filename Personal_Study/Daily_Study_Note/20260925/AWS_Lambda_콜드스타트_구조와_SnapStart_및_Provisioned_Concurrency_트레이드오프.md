Notion 원본: https://app.notion.com/p/3e65a06fd6d3819f96f1e02d83f9f22e

# AWS Lambda 콜드스타트 구조와 SnapStart 및 Provisioned Concurrency 비용·지연 트레이드오프

> 2026-09-25 신규 주제 · 확장 대상: AWS

## 학습 목표

- 콜드스타트의 4단계(다운로드/초기화/런타임 부트스트랩/핸들러 실행)를 구분하고 각 단계의 지연 요인을 설명한다
- SnapStart의 체크포인트-복원(checkpoint-restore) 방식과 그것이 초기화 지연을 없애는 원리를 설명한다
- Provisioned Concurrency와 SnapStart의 적용 범위 차이(언어별 지원)를 구분한다
- 비용-지연 트레이드오프를 실측 기준으로 비교해 워크로드에 맞는 선택 기준을 세운다

## 1. 콜드스타트의 4단계 분해

Lambda의 콜드스타트는 흔히 "느리다"는 한 마디로 뭉뚱그려지지만, 실제로는 네 단계로 나뉘고 각 단계가 서로 다른 요인에 좌우된다.

**(1) 다운로드(Download) 단계**: Lambda 서비스가 요청을 받은 실행 환경(MicroVM, Firecracker 기반)에 배포 패키지(zip 또는 컨테이너 이미지)를 준비하는 단계다. 컨테이너 이미지 기반 Lambda는 레이어 캐싱 덕분에 이미지 전체를 매번 내려받지 않지만, 이미지 크기가 클수록(특히 1GB에 근접) 이 단계의 지연이 커진다.

**(2) 초기화(Init) 단계**: 런타임(JVM, Node.js 등)을 부팅하고 전역 스코프 코드(Spring이라면 `ApplicationContext` 로딩)를 실행하는 단계다. Java + Spring 조합에서 이 단계가 가장 크게 문제되는데, 컴포넌트 스캔과 빈 생성, AOP 프록시 구성이 수 초씩 걸릴 수 있다.

**(3) 런타임 부트스트랩**: 언어별 런타임 인터페이스 클라이언트가 Lambda 서비스와 통신을 초기화하는 단계로, 대개 수십 밀리초 수준이다.

**(4) 핸들러 실행(Invoke)**: 실제 비즈니스 로직이 실행되는 단계이며, 콜드스타트든 웜스타트든 동일하게 발생한다.

```
[콜드스타트]  Download → Init → Bootstrap → Invoke
[웜스타트]                                    Invoke   (이미 초기화된 실행 환경 재사용)
```

실측 기준(오픈소스 벤치마크 및 AWS 자체 발표 자료 종합)으로 Java + Spring Boot 조합은 Init 단계만으로 3~10초가 소요될 수 있는 반면, Go나 경량 Node.js 핸들러는 100ms 미만인 경우가 흔하다. 이 차이의 핵심은 JVM 클래스 로딩과 JIT 워밍업, 그리고 Spring의 리플렉션 기반 빈 초기화 비용이다.

## 2. SnapStart의 체크포인트-복원 방식

SnapStart(현재 Java, 이후 다른 런타임으로 확장 중인 기능)는 Init 단계를 "매 콜드스타트마다 반복"하는 대신 **한 번만 수행하고 그 결과를 재사용**하는 접근이다. 함수를 게시(publish)하는 시점에 AWS는 실제로 Init 단계를 한 번 실행해 JVM이 완전히 초기화된 상태(힙, 스택, 로드된 클래스 전체)를 **Firecracker microVM 스냅샷**으로 캡처하고, 암호화하여 저장한다.

이후 콜드스타트가 발생하면 Init 단계를 처음부터 실행하는 대신, 저장된 스냅샷을 캐시에서 가져와 메모리에 복원(resume)한다. 이는 처음부터 부팅하는 것보다 훨씬 빠른데, VM 메모리 페이지 복원 자체가 CPU 집약적 초기화 코드 실행보다 저렴하기 때문이다. AWS 발표 자료 기준으로 Java 함수의 P99 콜드스타트 지연이 SnapStart 적용 후 최대 10배까지 감소한 사례가 보고되었다.

```yaml
# SAM 템플릿에서 SnapStart 활성화
Resources:
  OrderProcessorFunction:
    Type: AWS::Serverless::Function
    Properties:
      Runtime: java21
      Handler: com.example.OrderHandler::handleRequest
      SnapStart:
        ApplyOn: PublishedVersions   # 버전 게시 시에만 적용, $LATEST에는 적용 안 됨
```

중요한 제약은 SnapStart가 **버전이 게시(publish)될 때만** 스냅샷을 생성한다는 점이다. `$LATEST` 별칭으로 직접 호출하면 SnapStart의 이점을 받지 못하므로, 반드시 버전을 게시하고 별칭(alias)이 해당 버전을 가리키게 구성해야 한다.

## 3. 고유성(Uniqueness) 문제와 사후 훅

스냅샷 복원 방식의 가장 큰 함정은 **초기화 시점에 생성된 고유 값(암호화 키, 난수 시드, 커넥션의 소스 포트 등)이 모든 복원 인스턴스에서 동일하게 재사용될 수 있다**는 것이다. 예를 들어 Init 단계에서 `SecureRandom`으로 생성한 시드나 UUID를 전역 변수에 캐시해두면, 스냅샷에서 복원된 모든 실행 환경이 같은 값을 갖게 되어 보안 취약점(예측 가능한 토큰) 또는 DB 커넥션 충돌로 이어질 수 있다.

AWS는 이를 위해 **런타임 훅(Runtime Hooks)** API를 제공한다. `beforeCheckpoint`와 `afterRestore` 콜백을 등록하면, 체크포인트 직전과 복원 직후에 임의 코드를 실행할 수 있다.

```java
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

public class ConnectionPoolResource implements Resource {
    private HikariDataSource dataSource;

    public ConnectionPoolResource() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        dataSource.close(); // 체크포인트 전 커넥션을 반드시 닫음
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        dataSource = createDataSource(); // 복원 후 커넥션을 새로 맺음
        SecureRandom.getInstanceStrong(); // 난수 시드도 복원 시점에 재생성
    }
}
```

이 `org.crac`(Coordinated Restore at Checkpoint) API는 Spring Boot 3.2+에서 `spring-boot-starter`에 통합되어, `ApplicationContext`가 자동으로 `beforeCheckpoint`/`afterRestore` 라이프사이클을 관리해준다. DB 커넥션 풀, TLS 세션, 파일 핸들처럼 "네트워크 소켓을 붙들고 있는" 리소스는 반드시 체크포인트 전에 닫고 복원 후 재생성하도록 설계해야 한다. 이를 누락하면 스냅샷에 죽은 소켓 상태가 그대로 박제되어, 복원된 함수가 첫 호출에서 즉시 실패하는 형태로 나타난다.

## 4. Provisioned Concurrency와의 비교

Provisioned Concurrency(PC)는 SnapStart와 전혀 다른 접근이다. Init 단계를 빠르게 만드는 대신, **콜드스타트 자체가 발생하지 않도록 실행 환경을 미리 예열해 대기시켜 놓는** 방식이다. 지정한 동시성 수만큼 실행 환경을 상시 초기화된 상태로 유지하며, 요청이 오면 즉시 웜스타트로 응답한다.

```bash
aws lambda put-provisioned-concurrency-config \
  --function-name OrderProcessorFunction \
  --qualifier prod \
  --provisioned-concurrent-executions 10
```

PC는 모든 언어 런타임에 적용 가능하지만, **예약한 동시성 수만큼 유휴 상태에서도 과금**된다는 결정적 차이가 있다. 반면 SnapStart는 Java(현재 기준)에만 적용되지만, 실제 호출이 발생한 콜드스타트에 대해서만 비용이 발생하고(스냅샷 저장에 약간의 추가 비용은 있음) 유휴 과금이 없다.

## 5. 비용-지연 트레이드오프 비교

| 항목 | SnapStart | Provisioned Concurrency |
|---|---|---|
| 적용 언어 | Java(주력), 확장 진행 중 | 모든 런타임 |
| 콜드스타트 자체 발생 여부 | 발생하되 매우 빠르게 복원 | 발생 안 함(예열 상태 유지) |
| 유휴 시 과금 | 없음(요청 시에만) | 있음(예약 동시성만큼 상시 과금) |
| 트래픽 스파이크 대응 | 스냅샷 캐시 히트율에 의존 | 예약 수를 초과하면 콜드스타트 발생 |
| 추가 코드 작업 | Runtime Hooks 구현 필요할 수 있음 | 없음(설정만으로 적용) |
| 적합한 워크로드 | 트래픽이 간헐적이거나 예측 어려운 경우 | 트래픽 패턴이 예측 가능하고 SLA가 엄격한 경우 |

실무적으로는 두 방식이 배타적이지 않다. 예측 가능한 기본 트래픽에는 PC를 낮은 수준으로 설정해 기본 SLA를 보장하고, 그 위로 튀는 스파이크 트래픽에 대해서는 SnapStart로 콜드스타트 자체의 비용을 낮추는 **혼합 전략**이 Java 워크로드에서 합리적인 선택지가 된다. AWS는 두 기능을 동시에 활성화할 수 있도록 지원한다.

## 6. 실측 관점의 벤치마크 해석

공개된 벤치마크(AWS re:Invent 발표, 커뮤니티 부하테스트)를 종합하면, Spring Boot 기반 Java 21 Lambda의 경우 SnapStart 미적용 시 P99 콜드스타트가 4~8초 수준이던 것이 SnapStart 적용 후 300ms~1초대로 감소하는 사례가 다수 보고된다. 다만 이 수치는 함수의 메모리 설정(더 큰 메모리는 더 빠른 CPU 할당을 의미), 초기화 코드의 복잡도(빈 개수, AOP 사용 여부), 그리고 스냅샷 캐시의 히트율(동일 리전/AZ에서 최근에 복원된 적이 있는지)에 따라 크게 달라진다. 따라서 "SnapStart를 켜면 몇 배 빨라진다"는 일반화된 수치를 그대로 신뢰하기보다, 실제 함수에 대해 X-Ray 트레이스로 Init 구간의 서브세그먼트를 측정해 적용 전후를 비교하는 것이 신뢰할 수 있는 판단 근거가 된다.

```bash
# CloudWatch Logs Insights로 콜드스타트 여부와 Init Duration을 함께 추출
fields @timestamp, @initDuration, @duration, @billedDuration
| filter @type = "REPORT"
| filter ispresent(@initDuration)
| stats avg(@initDuration), pct(@initDuration, 99) by bin(5m)
```

`@initDuration` 필드가 존재하는 로그 레코드만 필터링하면 콜드스타트가 발생한 호출만 골라낼 수 있고, 이를 시간대별로 집계하면 SnapStart 적용 전후의 실제 효과를 정량적으로 검증할 수 있다.

## 7. 컨테이너 이미지 vs zip 패키징과 콜드스타트

Lambda는 zip 배포와 컨테이너 이미지(최대 10GB) 배포를 모두 지원한다. 컨테이너 이미지는 대용량 의존성(ML 모델, 네이티브 라이브러리)을 담기 유리하지만, 이미지 레이어가 최적화되지 않으면 다운로드 단계 지연이 커진다. AWS는 컨테이너 이미지에 대해 자체 캐싱 레이어를 운영하므로, 같은 이미지로 반복 호출되는 함수는 이 단계의 영향이 크지 않지만, 배포 직후 첫 트래픽이나 스케일 아웃 시점에는 zip 패키징 대비 초기 지연이 더 클 수 있다. Java 기반이라면 zip 패키징 + SnapStart 조합이 대체로 컨테이너 이미지보다 예측 가능한 콜드스타트 특성을 보인다.

## 8. 실무 적용 체크리스트

Java Lambda에 SnapStart를 도입할 때는 다음을 순서대로 점검한다. 먼저 DB 커넥션 풀, Kafka 클라이언트, 파일 디스크립터 등 소켓 기반 리소스를 전수 조사해 `beforeCheckpoint`/`afterRestore` 훅이 필요한지 판단한다. 다음으로 `SecureRandom`, UUID 생성, 타임스탬프 기반 시드처럼 "초기화 시점에 고정되면 안 되는" 값을 찾아 `afterRestore`에서 재생성하도록 수정한다. 그 다음 버전 게시와 별칭 전환을 CI/CD 파이프라인에 통합해, 배포마다 새 스냅샷이 생성되도록 보장한다. 마지막으로 스테이징 환경에서 X-Ray와 CloudWatch Logs Insights로 Init Duration을 측정해 개선 폭을 정량화하고, PC 병행 여부를 트래픽 패턴에 따라 재평가한다.

## 9. 오토스케일링과의 상호작용

Lambda의 동시성은 계정/함수 단위의 동시 실행 한도(concurrency limit) 안에서 자동으로 스케일링된다. 트래픽이 급증하면 Lambda는 기존 웜 실행 환경으로 처리할 수 없는 초과분에 대해 새 실행 환경을 계속 생성하며, 이 신규 생성분은 예외 없이 콜드스타트를 거친다. Provisioned Concurrency로 예약한 수를 초과하는 트래픽은 그 초과분만 온디맨드 콜드스타트로 처리되므로, PC 설정값을 트래픽의 평균이 아니라 예측 가능한 피크에 맞춰야 실질적인 효과가 있다. Application Auto Scaling을 PC에 연동하면 CloudWatch 지표(예: 특정 시간대 호출량) 기반으로 예약 수를 스케줄에 따라 자동 조정할 수 있어, 상시 최대치를 예약해 두는 것보다 비용 효율적이다.

```bash
aws application-autoscaling register-scalable-target \
  --service-namespace lambda \
  --resource-id function:OrderProcessorFunction:prod \
  --scalable-dimension lambda:function:ProvisionedConcurrency \
  --min-capacity 2 --max-capacity 20
```

이 스케일링 설정과 SnapStart를 함께 사용하면, 예약된 PC 범위 안에서는 웜스타트로, 그 범위를 넘어서는 순간적 스파이크는 SnapStart의 빠른 콜드스타트로 흡수하는 이중 방어 구조를 만들 수 있다.

## 참고

- AWS Lambda Developer Guide, "Improving startup performance with AWS Lambda SnapStart"
- AWS Lambda Developer Guide, "Configuring provisioned concurrency"
- Coordinated Restore at Checkpoint(CRaC) 프로젝트 문서, OpenJDK
- AWS re:Invent, "Deep dive on AWS Lambda SnapStart" 세션 자료
