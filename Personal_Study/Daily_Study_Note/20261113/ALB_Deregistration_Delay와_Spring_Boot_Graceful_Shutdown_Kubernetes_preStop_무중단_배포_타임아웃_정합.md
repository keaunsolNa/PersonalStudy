Notion 원본: https://www.notion.so/4185a06fd6d3813284bbc140c3e6ea68

# ALB Deregistration Delay 와 Spring Boot Graceful Shutdown, Kubernetes preStop: 무중단 배포 타임아웃 정합

> 2026-11-13 신규 주제 · 확장 대상: AWS(ELB와 배포), Docker&CI(롤링 배포), Spring(내장 톰캣 설정)

## 학습 목표

- 롤링 배포 중 502·504 가 발생하는 지점을 로드밸런서, 오케스트레이터, 애플리케이션 세 계층의 종료 순서로 추적한다
- Spring Boot graceful shutdown 의 동작을 immediate 모드와 비교 실행해 처리 중 요청의 결과 차이를 확인한다
- ALB 대상 그룹, Kubernetes Pod, ECS 태스크의 종료 타임아웃을 하나의 부등식으로 맞춘다
- keep-alive 유휴 타임아웃 불일치로 생기는 간헐적 502 를 설정으로 제거한다

## 1. 배포할 때만 나타나는 5xx

평소에는 오류율이 0 에 가까운 서비스가 배포 시간대에만 502 와 504 를 소량 기록하는 일이 흔하다. 건수가 적어 재시도로 가려지기 쉽지만, 결제나 주문처럼 멱등하지 않은 요청에서는 중복 처리나 유실로 이어진다. 이 오류는 애플리케이션 코드의 결함이 아니라 "누가 언제 트래픽을 끊고, 누가 언제 프로세스를 끝내는가"에 대한 세 계층의 시간표가 서로 맞지 않아서 생긴다. 로드밸런서는 대상을 목록에서 빼는 데 시간이 걸리고, 오케스트레이터는 정해진 유예 시간이 지나면 프로세스를 강제 종료하며, 애플리케이션은 종료 신호를 받은 뒤 처리 중인 요청을 어떻게 할지 스스로 정한다. 세 타이머의 기본값은 서로를 고려하지 않고 정해져 있으므로 운영자가 관계를 직접 맞춰야 한다.

종료 과정에서 요청이 실패하는 경로는 두 가지로 나뉜다. 하나는 이미 처리 중이던 요청이 프로세스 종료로 끊기는 경우이고, 다른 하나는 종료가 시작된 인스턴스로 새 요청이 계속 들어오는 경우다. 전자는 애플리케이션의 graceful shutdown 으로, 후자는 로드밸런서 등록 해제와 종료 신호 사이의 순서 조정으로 해결하며, 둘 중 하나만 적용하면 오류가 남는다.

## 2. Spring Boot graceful shutdown 의 동작

`server.shutdown=graceful` 로 설정하면 애플리케이션 컨텍스트가 닫힐 때 웹 서버가 먼저 유예 단계에 들어간다. Tomcat, Jetty, Reactor Netty 는 네트워크 계층에서 새 요청 수락을 중단하고, Undertow 는 요청을 받되 즉시 503 으로 응답한다. 처리 중인 요청은 `spring.lifecycle.timeout-per-shutdown-phase` 에 지정한 시간(기본 30초)까지 완료를 기다리고, 시간 안에 끝나지 않으면 남은 요청을 버리고 다음 종료 단계로 넘어간다. Spring Boot 3.4 부터는 graceful 이 기본값이며 그 이전 버전에서는 immediate 가 기본이므로 명시적으로 설정해야 한다.

```yaml
server:
  shutdown: graceful
  tomcat:
    keep-alive-timeout: 75s
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
management:
  endpoint:
    health:
      probes:
        enabled: true
```

두 모드의 차이를 Spring Boot 3.4.1, Java 21, 내장 Tomcat 환경에서 직접 실행해 확인했다. 응답에 3초가 걸리는 엔드포인트에 요청을 보내고 0.5초 뒤 컨텍스트를 닫았으며, 닫기 시작 후 0.5초 시점에 두 번째 요청을 새 연결로 보냈다.

```java
@SpringBootApplication
@RestController
static class App {
	@GetMapping("/slow")
	public String slow() throws InterruptedException {
		Thread.sleep(3000);
		return "done";
	}
}

private void run(String mode) throws Exception {
	ConfigurableApplicationContext context = SpringApplication.run(App.class, "--server.port=0",
		"--server.shutdown=" + mode, "--spring.lifecycle.timeout-per-shutdown-phase=10s");
	int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
	CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> call(port));
	Thread.sleep(500);
	CompletableFuture<String> late = CompletableFuture.supplyAsync(() -> {
		sleepQuietly(500);
		return call(port);
	});
	long start = System.currentTimeMillis();
	context.close();
	long elapsed = System.currentTimeMillis() - start;
	System.out.println("mode=" + mode + " closeMs=" + elapsed + " inFlight=" + inFlight.get() + " late=" + late.get());
}
```

`call` 은 `java.net.http.HttpClient` 로 GET 요청을 보내 상태 코드와 본문을 문자열로 돌려주고 예외가 나면 예외 클래스 이름을 돌려주는 보조 메서드다. 실행 결과는 다음과 같았다. graceful 모드에서는 `close` 가 2,842ms 동안 블로킹됐고 처리 중이던 요청은 `200 done` 을 받았으며, 종료 중에 보낸 새 요청은 `ConnectException` 으로 실패했다. immediate 모드에서는 `close` 가 130ms 만에 끝났고 처리 중이던 요청은 `IOException` 으로 끊겼으며 서버 로그에는 `InterruptedException: sleep interrupted` 가 남았다.

이 결과에서 두 가지를 읽을 수 있다. graceful 은 처리 중 요청을 실제로 보호한다. 그리고 graceful 이더라도 종료가 시작된 뒤 도착한 새 연결은 거부된다. 로드밸런서가 아직 이 인스턴스를 대상으로 알고 있다면 그 거부가 클라이언트에게 502 로 보인다. 따라서 애플리케이션 설정만으로는 무중단이 완성되지 않고, 종료 신호가 도착하기 전에 트래픽이 먼저 끊겨 있어야 한다.

## 3. ALB 대상 그룹의 등록 해제 지연

ALB 는 대상이 등록 해제되면 그 대상을 draining 상태로 바꾸고 새 요청을 더 보내지 않는다. 이미 전달된 요청은 계속 처리되며, `deregistration_delay.timeout_seconds` (기본 300초, 0~3600) 가 지나면 남은 연결과 무관하게 해제를 완료한다. 이 값은 "처리 중 요청이 끝날 때까지 기다려 주는 최대 시간"이므로 애플리케이션의 최장 요청 처리 시간보다 조금 길면 충분하다. 기본값 300초를 그대로 두면 인스턴스 하나를 교체할 때마다 5분을 기다리게 되어 배포 전체가 길어진다.

```hcl
resource "aws_lb_target_group" "api" {
  name                 = "api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
    timeout             = 5
  }
}
```

주의할 점은 등록 해제 요청이 접수된 순간과 ALB 의 모든 노드가 그 대상으로 전달을 멈추는 순간 사이에 전파 지연이 있다는 것이다. 이 구간에는 draining 으로 표시된 대상에도 요청이 도착할 수 있으므로, 애플리케이션은 등록 해제가 시작된 직후 몇 초 동안은 평소처럼 새 요청을 받아 처리할 수 있어야 한다. 헬스 체크 실패로 대상을 빼는 방식은 `interval × unhealthy_threshold` 만큼, 위 설정이면 20초가 걸리므로 종료 절차의 주된 수단으로 삼기에는 느리고, 명시적인 등록 해제가 우선이다.

## 4. Kubernetes 의 종료 순서와 preStop

Pod 삭제가 요청되면 두 가지 일이 병렬로 시작된다. kubelet 은 preStop 훅을 실행한 뒤 컨테이너에 SIGTERM 을 보내고, 동시에 컨트롤 플레인은 Pod 를 EndpointSlice 에서 제거하며 그 변경이 kube-proxy 와 인그레스 컨트롤러, AWS Load Balancer Controller 로 전파되어 대상 그룹 등록 해제로 이어진다. 두 경로 사이에 순서 보장이 없다는 것이 문제의 핵심이다. SIGTERM 이 먼저 도착하면 애플리케이션은 새 연결을 거부하기 시작하는데 로드밸런서는 아직 요청을 보내고 있다. 2절의 실험에서 본 `ConnectException` 이 바로 이 구간에서 사용자에게 502 로 나타난다.

표준적인 해법은 preStop 훅에서 일정 시간 대기해 SIGTERM 을 늦추는 것이다. 대기하는 동안 애플리케이션은 정상적으로 요청을 받고, 그 사이에 등록 해제가 전파된다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
spec:
  strategy:
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    spec:
      terminationGracePeriodSeconds: 60
      containers:
        - name: api
          image: registry.example.com/api:1.0.0
          ports:
            - containerPort: 8080
          lifecycle:
            preStop:
              sleep:
                seconds: 15
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            periodSeconds: 5
            failureThreshold: 2
```

`preStop.sleep` 은 Kubernetes 1.30 부터 기본으로 사용할 수 있는 내장 동작이고, 그 이전 버전에서는 `exec` 로 `sleep 15` 를 실행한다. 후자는 이미지에 sleep 바이너리가 있어야 하므로 distroless 이미지에서는 쓸 수 없다. `terminationGracePeriodSeconds` 는 preStop 실행 시간까지 포함한 전체 한도라는 점을 놓치기 쉽다. 기본값 30초에서 preStop 이 15초를 쓰면 애플리케이션에 남는 시간은 15초뿐이고, 그 안에 종료하지 못하면 SIGKILL 을 받는다. Spring Boot 는 SIGTERM 으로 종료가 시작되면 readiness 상태를 REFUSING_TRAFFIC 으로 바꾸지만, 그 시점은 이미 preStop 이 끝난 뒤이므로 프로브에 의한 제외는 보조 수단일 뿐이다.

## 5. ECS 의 종료 순서

ECS 서비스는 순서가 처음부터 직렬로 정해져 있어 preStop 에 해당하는 장치가 필요 없다. 로드밸런서에 연결된 서비스의 태스크를 중지할 때 ECS 는 먼저 대상 그룹에서 태스크를 등록 해제하고, 등록 해제 지연 시간이 지나 draining 이 끝난 뒤에 컨테이너에 SIGTERM 을 보낸다. 그 후 태스크 정의의 `stopTimeout` (기본 30초, Fargate 최대 120초) 동안 종료를 기다렸다가 SIGKILL 을 보낸다. 따라서 ECS 에서는 등록 해제 지연을 최장 요청 시간에 맞추고, `stopTimeout` 을 Spring 의 종료 단계 타임아웃보다 길게 두는 두 가지만 지키면 된다.

ECS 에서 흔한 실수는 컨테이너의 진입점이 셸 스크립트여서 SIGTERM 이 JVM 에 전달되지 않는 경우다. `sh -c "java -jar app.jar"` 형태로 실행하면 PID 1 이 셸이 되고, 셸이 신호를 자식에게 넘기지 않으면 JVM 은 `stopTimeout` 이 끝날 때까지 아무것도 모르다가 SIGKILL 을 받는다. graceful shutdown 설정이 전혀 동작하지 않는 것처럼 보일 때 가장 먼저 확인할 지점이며, 진입점을 exec 형식(`ENTRYPOINT ["java", "-jar", "app.jar"]`)으로 쓰거나 스크립트의 마지막 줄에서 `exec java ...` 로 프로세스를 교체해 해결한다. 이 문제는 Kubernetes 에서도 동일하다.

## 6. 타임아웃 부등식으로 정리하기

세 계층의 값을 다음 관계로 맞춘다. 가장 안쪽에 애플리케이션의 최장 요청 처리 시간이 있고, Spring 의 종료 단계 타임아웃은 그보다 길어야 처리 중 요청이 끝날 수 있다. Kubernetes 에서는 `terminationGracePeriodSeconds` 가 preStop 대기 시간과 Spring 종료 타임아웃의 합보다 여유 있게 길어야 하고, preStop 대기 시간은 등록 해제 전파에 걸리는 시간보다 길어야 한다. ALB 의 등록 해제 지연은 최장 요청 처리 시간 이상이면 되며, preStop 과 종료 타임아웃의 합에 맞추면 Pod 가 사라지기 전에 draining 이 끝난다.

앞의 설정값을 대입하면 최장 요청 10초를 가정할 때 Spring 종료 타임아웃 20초, preStop 15초, 유예 기간 60초, 등록 해제 지연 30초다. 종료가 시작되고 15초 동안은 정상 서비스하며 전파를 기다리고, 15초 시점에 SIGTERM 을 받아 최대 20초 동안 남은 요청을 처리하며, 최악의 경우에도 35초 시점에는 프로세스가 끝나 60초 한도까지 25초의 여유가 남는다. 이 여유는 JVM 종료 훅과 커넥션 풀 정리, 메시지 컨슈머 중지 같은 후속 단계가 쓰는 시간이다. `timeout-per-shutdown-phase` 는 이름대로 단계별 타임아웃이므로, 웹 서버 외에 종료에 시간이 걸리는 SmartLifecycle 빈이 다른 단계에 있다면 그 시간도 더해서 계산해야 한다.

값을 무작정 크게 잡는 것도 비용이다. Pod 하나의 교체 시간이 preStop 과 종료 시간만큼 늘어나므로 replica 가 수십 개인 서비스에서 `maxSurge: 1` 로 배포하면 전체 시간이 그만큼 곱해진다. 최장 요청 시간을 실측해 그에 맞는 최소값을 쓰고, 예외적으로 오래 걸리는 요청은 비동기 작업으로 분리하는 것이 배포 속도와 안정성을 함께 얻는 방향이다.

## 7. 배포와 무관한 502: keep-alive 유휴 타임아웃

종료 절차를 모두 맞췄는데도 간헐적인 502 가 남는다면 원인은 대개 유휴 연결이다. ALB 는 대상과의 연결을 재사용하며, 유휴 상태가 `idle_timeout` (기본 60초) 을 넘으면 ALB 쪽에서 연결을 닫는다. 그런데 대상 서버의 keep-alive 타임아웃이 이보다 짧거나 같으면 서버가 먼저 연결을 닫을 수 있고, 그 순간 ALB 가 같은 연결로 요청을 보내면 RST 를 받아 502 를 반환한다. 요청이 서버 애플리케이션에 도달한 적이 없으므로 액세스 로그에는 흔적이 없고 ALB 로그에만 502 가 남는 것이 특징이다.

Tomcat 의 `keepAliveTimeout` 은 지정하지 않으면 `connectionTimeout` 값을 따른다. 규칙은 "연결을 닫는 주체는 항상 로드밸런서여야 한다"이고, 이를 위해 서버의 keep-alive 타임아웃을 ALB 유휴 타임아웃보다 길게 둔다. 2절의 설정에서 `server.tomcat.keep-alive-timeout` 을 75초로 둔 것이 이 때문이다. ALB 의 유휴 타임아웃을 늘렸다면 서버 값도 함께 늘려야 하며, 앞단에 Nginx 가 한 겹 더 있다면 Nginx 의 upstream keep-alive 타임아웃과 애플리케이션 사이에도 같은 규칙을 적용한다.

## 8. 검증 방법과 선택 기준

설정이 맞는지는 배포 중 부하를 걸어 확인한다. 일정한 속도로 요청을 보내는 부하 도구를 실행한 상태에서 `kubectl rollout restart` 로 롤링 재시작을 수행하고, 2xx 가 아닌 응답과 연결 오류의 수가 0 인지 본다. 처리 시간이 긴 요청의 보호 여부를 보려면 2절의 느린 엔드포인트처럼 수 초가 걸리는 요청을 섞어야 하며, 짧은 요청만으로는 graceful shutdown 이 꺼져 있어도 통과할 수 있다. 실패가 관측되면 ALB 액세스 로그의 대상 상태 코드 필드를 확인한다. 대상 상태 코드가 비어 있는 502 는 연결 수립이나 재사용 실패, 즉 4절이나 7절의 문제이고, 504 는 등록 해제 지연이나 종료 타임아웃이 요청 처리 시간보다 짧았다는 뜻이다.

어디까지 적용할지는 요청의 성격으로 정한다. 모든 요청이 멱등하고 클라이언트가 재시도한다면 graceful shutdown 과 짧은 preStop 정도로 충분하다. 멱등하지 않은 쓰기 요청이 있다면 이 문서의 부등식을 전부 맞추고 배포 파이프라인에 위의 부하 검증을 포함시키는 것이 맞다. WebSocket 이나 SSE 처럼 수명이 긴 연결은 어떤 타임아웃으로도 보호할 수 없으므로 클라이언트 재연결 로직을 전제로 설계한다.

이 문서에서 2절의 Spring Boot 동작은 실제 실행으로 확인한 결과이고, ALB, Kubernetes, ECS 의 동작과 기본값은 각 공식 문서에 기술된 내용이며 이 노트를 만드는 환경에서 클러스터에 배포해 측정하지는 않았다.

## 참고

- Spring Boot Reference, Graceful Shutdown: https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html
- Spring Boot Reference, Kubernetes Probes 와 Application Availability: https://docs.spring.io/spring-boot/reference/features/spring-application.html
- AWS Elastic Load Balancing, Target group attributes 의 Deregistration delay: https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html
- AWS Elastic Load Balancing, Troubleshoot your Application Load Balancers (HTTP 502): https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-troubleshooting.html
- Kubernetes 문서, Pod Lifecycle 의 Termination of Pods: https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/
- Kubernetes 문서, Container Lifecycle Hooks: https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/
- Amazon ECS 개발자 안내서, 태스크 정의 파라미터의 stopTimeout: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html
- AWS Containers Blog, Graceful shutdowns with ECS: https://aws.amazon.com/blogs/containers/graceful-shutdowns-with-ecs/
- Apache Tomcat 설정 문서, HTTP Connector 의 keepAliveTimeout: https://tomcat.apache.org/tomcat-10.1-doc/config/http.html
