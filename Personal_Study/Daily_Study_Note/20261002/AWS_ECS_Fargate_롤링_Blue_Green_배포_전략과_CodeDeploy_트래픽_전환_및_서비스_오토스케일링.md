Notion 원본: https://www.notion.so/3ed5a06fd6d381138b12da0faa127b29

# AWS ECS Fargate 롤링·Blue/Green 배포 전략과 CodeDeploy 트래픽 전환 및 서비스 오토스케일링

> 2026-10-02 신규 주제 · 확장 대상: AWS

## 학습 목표

- ECS 롤링 배포 파라미터(minimumHealthyPercent, maximumPercent)를 조정해 가용성과 배포 속도를 맞춘다.
- Deployment circuit breaker와 CloudWatch 알람으로 실패한 배포를 자동 롤백한다.
- CodeDeploy Blue/Green의 AppSpec, Target Group 2개, 테스트 리스너, 라이프사이클 훅을 구성한다.
- Application Auto Scaling 정책을 배포 중 동작과 함께 설계한다.

## 1. ECS 배포 컨트롤러와 전략 선택

ECS 서비스는 `deploymentController.type`으로 배포를 누가 지휘하는지 정한다. 기본값 `ECS`는 서비스 스케줄러가 태스크를 직접 교체하는 롤링 업데이트이고, `CODE_DEPLOY`는 CodeDeploy가 Blue/Green 트래픽 전환을 맡으며, `EXTERNAL`은 서드파티 컨트롤러가 태스크 세트를 관리한다. 컨트롤러 종류는 서비스 생성 후 바꾸기 어려우므로 Blue/Green 가능성이 있으면 처음부터 반영하는 편이 안전하다. 최근에는 ECS 자체에도 Blue/Green 전략이 내장되었다고 알려져 있으나, 지원 범위와 옵션은 바뀔 수 있으니 도입 전에 공식 문서의 최신 상태를 확인해야 한다.

Heroku 경험에 비추면 롤링은 기본 릴리스 흐름과 닮았고, Blue/Green은 새 환경을 먼저 띄워 검증한 뒤 대상을 바꾸며 전환 비율과 관찰 시간을 직접 통제한다.

| 구분 | 롤링(ECS 컨트롤러) | Blue/Green(CodeDeploy) |
|---|---|---|
| 트래픽 전환 | 태스크 단위 점진 교체 | 리스너 대상을 Target Group 단위로 전환 |
| 추가 용량 | maximumPercent 범위 | 신규 환경 전체(일시적으로 약 2배) |
| 롤백 | 이전 태스크 정의로 재배포 | 대기 시간 내 이전 Target Group으로 복귀 |
| 사전 검증 | 어려움 | 테스트 리스너와 훅으로 가능 |
| 구성 복잡도 | 낮음 | 높음(ALB, TG 2개, CodeDeploy 앱) |

스키마가 하위 호환이고 롤백에 몇 분을 감수할 수 있으면 롤링으로 충분하다. 결제나 주문처럼 실트래픽 전에 검증하거나 일부 사용자에게만 먼저 노출해야 하면 Blue/Green이 낫다. 어느 쪽이든 DB 마이그레이션의 하위 호환은 별도로 책임져야 한다. Blue/Green이 코드를 되돌려 줘도 이미 적용된 스키마 변경은 되돌려 주지 않는다.

## 2. 롤링 배포 파라미터와 종료 처리

핵심은 두 값이다. `minimumHealthyPercent`는 배포 중 유지할 정상 태스크의 하한이고, `maximumPercent`는 `desiredCount` 대비 동시에 존재할 수 있는 태스크의 상한이다. REPLICA 서비스 기본값은 100과 200이다. `desiredCount=4`이면 최대 8개까지 띄우되 정상 태스크가 4개 아래로 내려가지 않으므로 용량 손실이 없다. 대신 Fargate vCPU를 일시적으로 2배 쓰므로 계정의 Fargate vCPU 쿼터를 확인해야 한다.

```hcl
resource "aws_ecs_service" "api" {
  name            = "order-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = 4
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 90

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # load_balancer, network_configuration 생략

  lifecycle {
    ignore_changes = [desired_count] # 오토스케일링 결과를 apply가 덮어쓰지 않게 한다
  }
}
```

`health_check_grace_period_seconds`는 기동 중인 태스크를 ALB 헬스체크 실패로 죽이지 않고 기다려 주는 시간이다. Spring Boot는 JVM 기동과 커넥션 풀 초기화에 수십 초가 걸리는 일이 흔하므로 측정한 기동 시간에 여유를 더해 지정한다. 너무 작으면 정상 기동 중인 태스크가 반복 종료되어 배포가 끝나지 않는다.

종료 쪽도 중요하다. ECS는 컨테이너에 SIGTERM을 보내고 `stopTimeout`(Fargate 기본 30초, 최대 120초) 뒤 SIGKILL을 보낸다. 동시에 ALB는 `deregistration_delay`(기본 300초) 동안 연결이 비워지기를 기다린다. Spring Boot는 우아한 종료를 켜 두어야 처리 중인 요청이 끊기지 않는다.

```yaml
# application.yml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s   # stopTimeout(30s)보다 짧게
```

`deregistration_delay` 기본 300초는 롤링 한 번의 총 시간을 늘린다. 응답 시간이 수 초 이내인 API라면 30~60초로 줄이는 사례가 많은데, 자신의 p99 응답 시간과 롱폴링 유무를 보고 정해야 한다.

## 3. Deployment Circuit Breaker와 알람 롤백

신규 태스크가 계속 기동에 실패하면 서비스는 재시도 루프에 빠진다. Circuit breaker는 연속 실패가 임계값을 넘으면 배포를 FAILED로 표시하고, `rollback=true`이면 마지막 성공 배포로 자동 복귀한다. 공식 문서 기준 임계값은 `desiredCount`의 절반이며 최소 3, 최대 200으로 제한된다. 즉 `desiredCount=4`여도 실패 3회가 쌓여야 감지된다.

감지 대상은 이미지 풀 실패, 헬스체크 전 컨테이너 종료, ALB 헬스체크 불통과 같은 기동 문제다. 정상 기동했지만 비즈니스 로직이 틀린 경우는 잡지 못한다. 이때는 CloudWatch 알람을 배포 롤백 조건에 연결한다.

```bash
aws ecs update-service \
  --cluster main --service order-api \
  --deployment-configuration '{
    "deploymentCircuitBreaker": {"enable": true, "rollback": true},
    "alarms": {
      "alarmNames": ["order-api-5xx-high"],
      "enable": true,
      "rollback": true
    },
    "minimumHealthyPercent": 100,
    "maximumPercent": 200
  }'
```

## 4. CodeDeploy Blue/Green 구조

ALB 하나에 Target Group 두 개(blue, green)를 두고, 프로덕션 리스너(예: 443)와 선택적인 테스트 리스너(예: 9001)를 둔다. 배포가 시작되면 CodeDeploy는 새 태스크 정의로 replacement 태스크 세트를 만들어 green에 등록하고, 먼저 테스트 리스너로 검증할 시간을 준다. 이어 프로덕션 리스너를 green으로 옮겨 트래픽을 전환하고, 대기 시간이 지나면 blue 태스크를 종료한다. 서비스는 `deploymentController.type=CODE_DEPLOY`로 만들어야 하며, 태스크 정의 변경은 CodeDeploy가 지휘하므로 Terraform이 이를 건드리지 않도록 `ignore_changes`가 필요하다.

```hcl
resource "aws_ecs_service" "api_bg" {
  name            = "order-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = 4
  launch_type     = "FARGATE"

  deployment_controller { type = "CODE_DEPLOY" }

  load_balancer {
    target_group_arn = aws_lb_target_group.blue.arn
    container_name   = "app"
    container_port   = 8080
  }

  # network_configuration 생략

  lifecycle {
    ignore_changes = [task_definition, load_balancer, desired_count]
  }
}

resource "aws_codedeploy_deployment_group" "api" {
  app_name               = aws_codedeploy_app.ecs.name
  deployment_group_name  = "order-api-dg"
  service_role_arn       = aws_iam_role.codedeploy.arn
  deployment_config_name = "CodeDeployDefault.ECSCanary10Percent5Minutes"

  ecs_service {
    cluster_name = aws_ecs_cluster.main.name
    service_name = aws_ecs_service.api_bg.name
  }

  deployment_style {
    deployment_type   = "BLUE_GREEN"
    deployment_option = "WITH_TRAFFIC_CONTROL"
  }

  blue_green_deployment_config {
    deployment_ready_option { action_on_timeout = "CONTINUE_DEPLOYMENT" }
    terminate_blue_instances_on_deployment_success {
      action                           = "TERMINATE"
      termination_wait_time_in_minutes = 30
    }
  }

  load_balancer_info {
    target_group_pair_info {
      prod_traffic_route { listener_arns = [aws_lb_listener.https.arn] }
      test_traffic_route { listener_arns = [aws_lb_listener.test.arn] }
      target_group { name = aws_lb_target_group.blue.name }
      target_group { name = aws_lb_target_group.green.name }
    }
  }

  auto_rollback_configuration {
    enabled = true
    events  = ["DEPLOYMENT_FAILURE", "DEPLOYMENT_STOP_ON_ALARM"]
  }
}
```

`termination_wait_time_in_minutes`는 전환 성공 후 blue를 남겨 두는 시간이다. 이 동안 문제가 발견되면 즉시 blue로 되돌릴 수 있지만 그만큼 Fargate 비용이 두 배로 든다.

## 5. AppSpec과 라이프사이클 훅

ECS용 AppSpec은 어떤 태스크 정의를 어느 컨테이너와 포트에 연결할지 선언한다. 태스크 정의 ARN 자리에는 파이프라인이 등록한 새 리비전이 들어간다. ECS 배포에서 쓸 수 있는 훅은 `BeforeInstall`, `AfterInstall`, `AfterAllowTestTraffic`, `BeforeAllowTraffic`, `AfterAllowTraffic`이며, Lambda 함수 이름으로 지정한다.

```yaml
version: 0.0
Resources:
  - TargetService:
      Type: AWS::ECS::Service
      Properties:
        TaskDefinition: "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/order-api:42"
        LoadBalancerInfo:
          ContainerName: "app"
          ContainerPort: 8080
        PlatformVersion: "LATEST"
Hooks:
  - AfterAllowTestTraffic: "order-api-smoke-test"
```

`AfterAllowTestTraffic`는 green 태스크가 테스트 리스너에 연결된 직후 실행되므로 스모크 테스트에 적합하다. 훅 Lambda는 결과를 반드시 CodeDeploy에 보고해야 한다. 보고하지 않으면 배포가 타임아웃까지 기다린다.

```python
import urllib.request
import boto3

codedeploy = boto3.client("codedeploy")
TEST_URL = "http://internal-alb.example.com:9001/actuator/health"

def handler(event, context):
    try:
        ok = urllib.request.urlopen(TEST_URL, timeout=5).status == 200
    except Exception:
        ok = False
    status = "Succeeded" if ok else "Failed"
    codedeploy.put_lifecycle_event_hook_execution_status(
        deploymentId=event["DeploymentId"],
        lifecycleEventHookExecutionId=event["LifecycleEventHookExecutionId"],
        status=status,
    )
```

Lambda가 테스트 리스너에 닿도록 같은 VPC에 두거나 경로를 열어야 한다. GitHub Actions에서는 `aws-actions/amazon-ecs-deploy-task-definition`의 `codedeploy-appspec`, `codedeploy-application`, `codedeploy-deployment-group` 입력으로 CodeDeploy 배포를 생성할 수 있다.

## 6. 트래픽 전환: AllAtOnce, Linear, Canary

CodeDeploy는 ECS용 사전 정의 구성을 제공한다.

| 구성 이름 | 동작 |
|---|---|
| CodeDeployDefault.ECSAllAtOnce | 한 번에 100% 전환 |
| CodeDeployDefault.ECSLinear10PercentEvery1Minutes | 1분마다 10%씩 증가 |
| CodeDeployDefault.ECSLinear10PercentEvery3Minutes | 3분마다 10%씩 증가 |
| CodeDeployDefault.ECSCanary10Percent5Minutes | 10%로 5분 관찰 후 나머지 전환 |
| CodeDeployDefault.ECSCanary10Percent15Minutes | 10%로 15분 관찰 후 나머지 전환 |

사전 정의가 맞지 않으면 `aws deploy create-deployment-config`에서 `TimeBasedCanary`(canaryPercentage, canaryInterval)나 `TimeBasedLinear`로 직접 만들 수 있다.

카나리 비율은 표본 크기와 피해 범위의 절충이다. 분당 1,000건 서비스에서 10%는 분당 100건이라 5분이면 약 500건의 표본이 쌓인다(설명용 가상 수치). 트래픽이 적은 서비스일수록 비율이 아니라 관찰 시간을 늘려야 한다. 또한 ALB의 가중치 전환은 요청 단위 분배이므로 같은 사용자가 요청마다 다른 버전을 만날 수 있다.

## 7. 서비스 오토스케일링(Application Auto Scaling)

태스크 수는 Application Auto Scaling의 `ecs:service:DesiredCount` 차원으로 조절한다. 대상을 등록하고 정책을 붙이며, 가장 흔한 선택은 지표를 목표값 근처로 유지하는 Target Tracking이다.

```bash
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/main/order-api \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 4 --max-capacity 20

aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/main/order-api \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-tracking \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 55.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleOutCooldown": 60,
    "ScaleInCooldown": 300
  }'
```

사전 정의 지표는 `ECSServiceAverageCPUUtilization`, `ECSServiceAverageMemoryUtilization`, `ALBRequestCountPerTarget`이며, 마지막은 `ResourceLabel`이 필요하다. JVM 서비스는 힙 설정 때문에 메모리가 일정하게 높은 경우가 많아 메모리 추적은 부적합하고 CPU나 요청 수가 안정적이다.

스케일 아웃은 빠르게, 스케일 인은 느리게 두는 것이 정석이다. 목표값 55%는 설명용 숫자이며, 신규 태스크가 서비스 가능해지기까지의 지연(이미지 풀, JVM 기동, 그레이스 기간)을 감안해 부하 테스트로 정해야 한다. 예측 가능한 패턴은 Scheduled Scaling(`put-scheduled-action`)으로 최소 용량을 미리 올리고, Step Scaling은 Target Tracking으로 부족할 때만 보완용으로 쓴다.

## 8. 배포와 오토스케일링의 상호작용, 운영 체크리스트

문서에 따르면 ECS 롤링 배포 중에는 Application Auto Scaling의 스케일 인이 멈추고 스케일 아웃만 허용된다. 배포 도중 부하가 올라도 확장은 되지만 축소는 배포 이후에 일어난다. Blue/Green에서는 `desiredCount`가 CodeDeploy와 오토스케일링 양쪽의 영향을 받으므로, 배포 직전 값이 현재 확장 규모로 반영되는지 스테이징에서 한 번 점검하는 것이 좋다. IaC가 `desired_count`를 선언하면서 `ignore_changes`가 없으면 `terraform apply`가 오토스케일링 결과를 덮어써 서비스를 축소시킬 수 있다.

운영 체크는 세 가지다. 첫째, ALB 헬스체크 경로는 `/actuator/health/readiness`처럼 의존성까지 확인하는 경로로 두고 그레이스 기간은 실측 기동 시간의 1.5배 이상으로 잡는다. 둘째, `stopTimeout`, 스프링 종료 타임아웃, ALB deregistration delay가 서로 모순되지 않는지 확인한다. 셋째, 롤백 경로를 연습한다. 서킷 브레이커, 알람 롤백, CodeDeploy 수동 롤백을 스테이징에서 의도적으로 실패시켜 보면 장애 시 대응 속도가 달라진다.

정리하면 일반 내부 API는 서킷 브레이커를 켠 롤링으로, 장애 비용이 큰 외부 노출 핵심 경로는 알람과 훅을 붙인 카나리 Blue/Green으로 시작한다. 오토스케일링은 CPU 또는 요청 수 Target Tracking에 스케줄 최솟값을 더하는 조합을 기본값으로 삼는다.

## 참고

- Amazon ECS Developer Guide, "Deployment types" 및 "Deploy Amazon ECS services by replacing tasks"
- Amazon ECS Developer Guide, "How the Amazon ECS deployment circuit breaker detects failures"
- Amazon ECS Developer Guide, "Amazon ECS blue/green deployments" (ECS 내장 Blue/Green)
- AWS CodeDeploy User Guide, "Deployments on an Amazon ECS compute platform" 및 "AppSpec 'hooks' section for an Amazon ECS deployment"
- AWS CodeDeploy User Guide, "Deployment configurations on an Amazon ECS compute platform"
- Amazon ECS Developer Guide, "Automatically scale your Amazon ECS service" 및 Application Auto Scaling User Guide, "Target tracking scaling policies"
- Elastic Load Balancing User Guide, "Target groups for your Application Load Balancers" (deregistration delay)
- Martin Fowler, "BlueGreenDeployment" (bliki)
