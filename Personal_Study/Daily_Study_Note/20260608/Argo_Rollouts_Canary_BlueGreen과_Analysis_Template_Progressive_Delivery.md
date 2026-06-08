Notion 원본: https://www.notion.so/3795a06fd6d381fcb0b3d31679edf326

# Argo Rollouts Canary/Blue-Green과 Analysis Template Progressive Delivery

> 2026-06-08 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- Kubernetes 기본 Deployment 의 롤링 업데이트 한계를 짚고 Argo Rollouts 가 채우는 공백을 정의한다
- Canary 와 Blue-Green 전략을 step·트래픽 분할·롤백 관점에서 구분한다
- AnalysisTemplate 으로 Prometheus 지표 기반 자동 승격/롤백을 구성한다
- 트래픽 라우팅 연동과 실패 시 자동 abort 흐름을 설계한다

## 1. 기본 Deployment 의 한계

쿠버네티스 기본 `Deployment` 는 `RollingUpdate` 로 Pod 를 점진 교체하지만 두 한계가 있다. 첫째, **트래픽 가중치를 세밀하게 제어할 수 없다.** 신버전 Pod 가 Ready 가 되면 Service 가 곧바로 동일 가중치로 트래픽을 보낸다. 둘째, **자동 분석·자동 롤백이 없다.** 5xx 를 뿜어도 Pod 가 Ready 인 한 교체를 계속 진행한다. Progressive Delivery 는 이 두 공백을 메우는 배포 방식이고 Argo Rollouts 가 이를 CRD 로 구현한다.

## 2. Rollout CRD

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: orders-api
spec:
  replicas: 10
  strategy:
    canary:
      canaryService: orders-api-canary
      stableService: orders-api-stable
      trafficRouting:
        nginx:
          stableIngress: orders-ingress
      steps:
        - setWeight: 5
        - pause: { duration: 5m }
        - setWeight: 25
        - pause: { duration: 10m }
        - setWeight: 50
        - pause: {}
        - setWeight: 100
```

`pause: {}`(duration 없음)는 `kubectl argo rollouts promote` 를 칠 때까지 멈춘다. `pause: { duration }` 은 자동 진행이다.

## 3. Canary vs Blue-Green

| 구분 | Canary | Blue-Green |
|---|---|---|
| 트래픽 전환 | 점진(5→25→50→100%) | 일괄(즉시 스위치) |
| 리소스 | 신·구 일부 공존 | 두 환경 풀 용량 |
| 롤백 속도 | 가중치 되돌림 | 셰렉터 즉시 복귀 |
| 적합 | 점진 검증·메트릭 분석 | 즉시 전환·빠른 롤백 |

```yaml
strategy:
  blueGreen:
    activeService: orders-api-active
    previewService: orders-api-preview
    autoPromotionEnabled: false
    prePromotionAnalysis:
      templates:
        - templateName: success-rate
```

## 4. AnalysisTemplate: 메트릭 기반 자동 판정

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: success-rate
spec:
  args:
    - name: service-name
  metrics:
    - name: success-rate
      interval: 1m
      count: 5
      successCondition: result[0] >= 0.99
      failureLimit: 2
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            sum(rate(http_requests_total{service="{{args.service-name}}", code!~"5.."}[2m]))
            / sum(rate(http_requests_total{service="{{args.service-name}}"}[2m]))
```

canary step 에 analysis 를 인라인으로 붙이면, 가중치 25% 상태에서 5xx 비율이 1% 를 넘기면 분석이 실패해 자동 abort 되어 stable 로 되돌린다. provider 는 Prometheus 외에 Datadog, New Relic, CloudWatch, Job 등을 지원한다.

## 5. 트래픽 라우팅 연동

trafficRouting 없이 canary 를 쓰면 Argo 는 replica 수 비율로 가중치를 근사한다(10개 중 1개 ≈ 10%). 정밀한 5% 같은 가중치는 불가능하다. 서비스 메시(Istio, Linkerd) 또는 인그레스(NGINX, ALB, Traefik)가 가중 라우팅을 수행하면 replica 수와 무관하게 가중치가 정확히 적용된다.

```yaml
trafficRouting:
  istio:
    virtualService:
      name: orders-vsvc
      routes: [primary]
```

## 6. 실패·롤백 흐름과 운영 명령

```bash
kubectl argo rollouts get rollout orders-api --watch
kubectl argo rollouts promote orders-api
kubectl argo rollouts promote orders-api --full
kubectl argo rollouts abort orders-api
kubectl argo rollouts undo orders-api --to-revision=3
```

Rollout 은 stable/canary ReplicaSet 을 모두 보존하므로 롤백이 이미지 재 pull 없이 즉각적이다.

## 7. trade-off 와 도입 판단

이득은 "나쁜 배포의 폭발 반경(blast radius)을 소수 트래픽으로 제한하고 판정을 자동화"하는 것이다. 비용은 트래픽 라우팅 인프라 의존, AnalysisTemplate 튜닝 부담, 배포가 분 단위로 길어지는 시간이다. 신뢰할 SLI 가 Prometheus 에 이미 노출되어 있고 배포 사고 비용이 큰 서비스일수록 도입 가치가 크다.

## 8. AnalysisRun 의 종류: inline, background, prePromotion

inline analysis 는 step 사이에 배치되어 그 시점에 한 번 실행되고 끝날 때까지 진행을 막는다(게이트). background analysis 는 strategy.canary.analysis 에 선언해 롤아웃 시작부터 끝까지 지속 감시하며 실패 조건을 만나면 즉시 abort 한다. prePromotion/postPromotion 은 Blue-Green 에서 전환 직전·직후 검증한다.

```yaml
strategy:
  canary:
    analysis:
      templates:
        - templateName: error-rate
      startingStep: 2
    steps:
      - setWeight: 10
      - pause: { duration: 2m }
      - setWeight: 30
```

failureLimit 과 inconclusiveLimit 의 구분도 중요하다. 트래픽이 너무 적어 통계가 무의미하면 inconclusive 가 되므로, 이를 abort 가 아닌 사람 판단 대기로 처리해 저트래픽 시간대의 오판 롤백을 막는다.

## 9. 실전 워크플로우: GitOps 연동

CI 가 이미지를 푸시하고 Git 의 매니페스트(이미지 태그)를 갱신하면, Argo CD 가 Git 변경을 감지해 Rollout 을 동기화하고 canary 시퀀스가 자동 발동한다. 분석 통과 시 자동 승격, 실패 시 자동 abort 된다. 단 abort 후에도 Git 태그는 신버전을 가리키므로, abort 후 Git 태그를 revert 하는 절차를 파이프라인에 포함해 실패한 버전이 자동 재배포되는 루프를 끊어야 한다.

## 10. 안티패턴과 운영 체크리스트

첫째, 헬스체크만으로 충분하다는 착각 — readiness probe 통과와 비즈니스 정상은 별개다. 둘째, 너무 짧은 측정 구간은 워밍업 직후 일시 에러를 회귀로 오판한다. 셋째, 트래픽 라우팅 없이 정밀 가중치를 가정하면 어긋난다. 도입 체크리스트: 신뢰 SLI 노출, canary/stable Service 와 트래픽 라우팅 구성, abort 시 Git 태그 revert 자동화, 저트래픽 시간대 inconclusive 처리, 핫픽스용 promote --full 비상 경로 문서화.

## 참고

- Argo Rollouts Documentation — Canary / BlueGreen Strategy, Analysis & Progressive Delivery
- Argo Rollouts: Traffic Management (Istio, NGINX, ALB, SMI)
- "Progressive Delivery" — Weaveworks / CNCF 자료
- Kubernetes Deployment RollingUpdate 공식 문서
