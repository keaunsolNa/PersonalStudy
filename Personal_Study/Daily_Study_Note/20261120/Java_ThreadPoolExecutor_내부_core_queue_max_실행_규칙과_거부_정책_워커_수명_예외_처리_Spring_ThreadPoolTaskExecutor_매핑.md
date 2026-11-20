Notion 원본: https://www.notion.so/41e5a06fd6d381f5af3bd3d0101567d4

# Java ThreadPoolExecutor 내부: core → queue → max 실행 규칙, 거부 정책, 워커 수명과 예외 처리, Spring ThreadPoolTaskExecutor 매핑

> 2026-11-20 신규 주제 · 확장 대상: JAVA(Thread·Executor 기초), Spring(@Async·TaskExecutor 설정)

## 학습 목표

- `execute()` 가 코어 스레드 생성, 큐 적재, 추가 스레드 생성, 거부를 어떤 순서로 시도하는지 풀 크기와 큐 길이 출력으로 확인한다
- 큐 종류(유계·무계·`SynchronousQueue`)에 따라 `maximumPoolSize` 가 의미를 갖는 조건을 구분한다
- 네 가지 거부 정책의 실행 결과를 비교하고 `submit()` 과 조합될 때의 함정을 재현한다
- `execute()` 와 `submit()` 의 예외 처리 차이, `shutdown()` 과 `shutdownNow()` 의 차이를 실행해 확인한다

## 1. ctl 필드와 execute 의 세 단계

`ThreadPoolExecutor` 는 풀 상태와 워커 수를 `AtomicInteger ctl` 하나에 담는다. 상위 3비트가 실행 상태(`RUNNING`·`SHUTDOWN`·`STOP`·`TIDYING`·`TERMINATED`), 하위 29비트가 워커 수다. 두 값을 한 워드에 넣은 이유는 "실행 중이고 워커가 코어 수 미만이면 워커를 늘린다" 같은 복합 조건을 락 없이 CAS 한 번으로 판정·갱신하기 위해서다.

`execute(Runnable)` 의 본문은 세 단계다. 워커 수가 `corePoolSize` 미만이면 이 작업을 첫 작업으로 갖는 새 워커를 만든다. 그렇지 않으면 큐에 `offer` 한다. `offer` 가 실패하면(큐가 가득 참) `maximumPoolSize` 를 한도로 새 워커를 만들고, 그것도 실패하면 거부 핸들러를 호출한다. 많은 사람이 기대하는 "코어 → 최대 → 큐" 가 아니라 "코어 → 큐 → 최대" 순서라는 점이 설정 실수의 대부분을 만든다.

```java
import java.util.concurrent.*;

public class Order {
	public static void main(String[] args) throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
		for (int i = 1; i <= 7; i++) {
			try {
				pool.execute(() -> {
					try {
						gate.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				System.out.printf("task %d -> poolSize=%d queue=%d%n", i, pool.getPoolSize(), pool.getQueue().size());
			} catch (RejectedExecutionException e) {
				System.out.printf("task %d -> rejected (%s)%n", i, e.getClass().getSimpleName());
			}
		}
		gate.countDown();
		pool.shutdown();
		System.out.println(pool.awaitTermination(5, TimeUnit.SECONDS) + " completed=" + pool.getCompletedTaskCount() + " largest=" + pool.getLargestPoolSize());
	}
}
```

```text
task 1 -> poolSize=1 queue=0
task 2 -> poolSize=2 queue=0
task 3 -> poolSize=2 queue=1
task 4 -> poolSize=2 queue=2
task 5 -> poolSize=3 queue=2
task 6 -> poolSize=4 queue=2
task 7 -> rejected (RejectedExecutionException)
true completed=6 largest=4
```

코어 2, 최대 4, 큐 용량 2 인 풀에 끝나지 않는 작업을 일곱 개 넣었다. 1·2번은 코어 스레드를 만들고, 3·4번은 큐에 쌓이고, 큐가 찬 뒤인 5·6번에서야 세 번째·네 번째 스레드가 생긴다. 7번은 거부된다. 동시에 수용 가능한 작업 수는 `maximumPoolSize + 큐 용량` 인 6 이다.

두 가지를 더 읽어야 한다. 5번 작업은 큐에 먼저 들어간 3·4번보다 먼저 실행된다. 큐가 가득 차서 만들어진 스레드는 큐의 머리가 아니라 자신을 만들게 한 작업을 첫 작업으로 받기 때문이다. 유계 큐를 쓰는 풀은 제출 순서와 실행 순서가 같지 않다. 그리고 풀은 생성 직후 스레드가 0 개다. 코어 스레드는 작업이 들어올 때 하나씩 만들어지며, 기존 코어 스레드가 놀고 있어도 워커 수가 `corePoolSize` 미만이면 새 스레드를 만든다.

## 2. 큐 종류가 maximumPoolSize 의 의미를 결정한다

추가 스레드는 `offer` 가 실패해야만 만들어지므로, 큐가 가득 찰 수 없으면 `maximumPoolSize` 는 쓰이지 않는다.

```java
		CountDownLatch gate2 = new CountDownLatch(1);
		ThreadPoolExecutor unbounded = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
		for (int i = 0; i < 100; i++) {
			unbounded.execute(() -> {
				try {
					gate2.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}
		System.out.printf("unbounded: poolSize=%d queue=%d%n", unbounded.getPoolSize(), unbounded.getQueue().size());
		gate2.countDown();
		unbounded.shutdown();

		CountDownLatch gate3 = new CountDownLatch(1);
		ThreadPoolExecutor direct = new ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
		for (int i = 1; i <= 4; i++) {
			try {
				direct.execute(() -> {
					try {
						gate3.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				System.out.printf("direct %d -> poolSize=%d queue=%d%n", i, direct.getPoolSize(), direct.getQueue().size());
			} catch (RejectedExecutionException e) {
				System.out.printf("direct %d -> rejected%n", i);
			}
		}
		gate3.countDown();
		direct.shutdown();
```

```text
unbounded: poolSize=2 queue=98
direct 1 -> poolSize=1 queue=0
direct 2 -> poolSize=2 queue=0
direct 3 -> poolSize=3 queue=0
direct 4 -> rejected
```

용량을 주지 않은 `LinkedBlockingQueue` 는 `Integer.MAX_VALUE` 까지 받으므로 작업 100 개 중 98 개가 큐에 쌓이고 스레드는 코어 2 개에서 늘지 않는다. `Executors.newFixedThreadPool(n)` 이 정확히 이 구성(`core = max = n`, 무계 큐)이다. 처리 속도보다 유입이 빠르면 큐가 힙을 다 쓸 때까지 자라고, 그 전에 응답 지연이 먼저 무한히 커진다. 거부도 일어나지 않으므로 과부하가 밖으로 드러나지 않는다.

`SynchronousQueue` 는 용량이 0 인 전달 지점이다. `offer` 는 그 순간 `poll` 로 대기 중인 워커가 있을 때만 성공한다. 놀고 있는 워커가 없으면 즉시 실패해 새 스레드를 만들고, 최대치에 닿으면 거부한다. `Executors.newCachedThreadPool()` 은 `core = 0`, `max = Integer.MAX_VALUE`, 유휴 60초, `SynchronousQueue` 구성이어서 큐 대기는 없지만 스레드 수에 상한이 없다.

운영 설정으로는 세 형태가 남는다. 유계 큐에 `core < max` 를 두어 평소에는 코어로 처리하고 큐가 찬 뒤에만 스레드를 늘리는 형태, `core = max` 에 유계 큐를 두어 스레드 수를 고정하고 큐 길이로 대기를 제한하는 형태, `SynchronousQueue` 에 유한한 `max` 를 두어 대기 없이 동시 실행 수만 제한하는 형태다. 어느 쪽이든 큐 용량과 `max` 가 모두 유한해야 거부 정책이 동작한다.

## 3. 거부 정책 네 가지

거부는 수용 한도를 넘었을 때와 `shutdown()` 이후 제출했을 때 발생한다. 스레드 1 개, 큐 용량 1 인 풀에 작업 네 개를 넣어 정책별로 어떤 작업이 어느 스레드에서 실행되는지 기록했다.

```java
	static void run(String label, RejectedExecutionHandler handler) throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		List<String> ran = Collections.synchronizedList(new ArrayList<>());
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
			r -> new Thread(r, "worker"), handler);
		for (int i = 1; i <= 4; i++) {
			int id = i;
			try {
				pool.execute(() -> {
					if (!Thread.currentThread().getName().equals("main")) {
						try {
							gate.await();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					ran.add(id + "@" + Thread.currentThread().getName());
				});
			} catch (RejectedExecutionException e) {
				ran.add(id + "!rejected");
			}
		}
		gate.countDown();
		pool.shutdown();
		pool.awaitTermination(5, TimeUnit.SECONDS);
		System.out.println(label + " " + ran);
	}
```

```text
AbortPolicy         [3!rejected, 4!rejected, 1@worker, 2@worker]
CallerRunsPolicy    [3@main, 4@main, 1@worker, 2@worker]
DiscardPolicy       [1@worker, 2@worker]
DiscardOldestPolicy [1@worker, 4@worker]
```

기본값인 `AbortPolicy` 는 `RejectedExecutionException` 을 던져 제출자가 실패를 알게 한다. `CallerRunsPolicy` 는 제출한 스레드가 작업을 직접 실행한다. 제출 스레드가 그동안 다음 작업을 제출하지 못하므로 유입 속도가 처리 속도에 맞춰 느려지는 배압 효과가 생긴다. 다만 제출 스레드가 Tomcat 요청 스레드나 Netty 이벤트 루프라면 그 스레드가 묶이는 것이므로, 배압이 필요한 배치·컨슈머 성격의 제출자에게만 맞는다. `DiscardPolicy` 는 조용히 버리고, `DiscardOldestPolicy` 는 큐의 머리를 버리고 다시 제출한다. 위 결과에서 2번이 3번에 밀려 버려지고 3번이 다시 4번에 밀려 버려졌다.

버리는 정책은 `submit()` 과 함께 쓰면 반환된 `Future` 가 영원히 완료되지 않는다.

```java
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
			new ThreadPoolExecutor.DiscardPolicy());
		CountDownLatch gate = new CountDownLatch(1);
		pool.execute(() -> {
			try {
				gate.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		pool.submit(() -> "queued");
		Future<String> dropped = pool.submit(() -> "dropped");
		gate.countDown();
		try {
			dropped.get(300, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			System.out.println("discarded future never completes: isDone=" + dropped.isDone());
		}
```

```text
discarded future never completes: isDone=false
```

`submit` 은 `FutureTask` 를 만든 뒤 `execute` 에 넘기는데, 핸들러가 그것을 버리면 `FutureTask` 는 실행도 취소도 되지 않은 상태로 남는다. 타임아웃 없는 `get()` 은 영구 대기한다. 버리는 정책을 쓰려면 핸들러 안에서 `Future` 를 `cancel` 하는 사용자 정의 핸들러를 쓰거나, 호출 측이 반드시 타임아웃을 준다.

## 4. 워커의 수명: 생성, 유휴 종료, 크기 변경

워커 스레드는 `getTask()` 로 큐에서 작업을 꺼내는 루프를 돈다. 워커 수가 `corePoolSize` 를 넘거나 `allowCoreThreadTimeOut` 이 켜져 있으면 `poll(keepAliveTime)` 으로 기다리다 시간이 지나면 종료하고, 그렇지 않으면 `take()` 로 무기한 대기한다.

```java
		ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 200, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
		System.out.println("new: poolSize=" + pool.getPoolSize());
		System.out.println("prestarted=" + pool.prestartAllCoreThreads() + " poolSize=" + pool.getPoolSize());
		CountDownLatch gate = new CountDownLatch(1);
		for (int i = 0; i < 5; i++) {
			pool.execute(() -> {
				try {
					gate.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}
		System.out.println("burst: poolSize=" + pool.getPoolSize());
		gate.countDown();
		Thread.sleep(600);
		System.out.println("idle 600ms: poolSize=" + pool.getPoolSize());
		pool.allowCoreThreadTimeOut(true);
		Thread.sleep(600);
		System.out.println("allowCoreThreadTimeOut: poolSize=" + pool.getPoolSize());
```

```text
new: poolSize=0
prestarted=2 poolSize=2
burst: poolSize=4
idle 600ms: poolSize=2
allowCoreThreadTimeOut: poolSize=0
```

`prestartAllCoreThreads()` 는 첫 요청들이 스레드 생성 비용을 치르지 않도록 코어 스레드를 미리 만든다. 부하가 지나가고 유휴 시간이 `keepAliveTime` 을 넘으면 초과분이 정리되어 코어 수로 돌아온다. `allowCoreThreadTimeOut(true)` 는 코어 스레드에도 같은 규칙을 적용해 풀을 0 까지 줄인다. 하루에 몇 번만 쓰이는 풀이 스레드를 계속 붙들지 않게 하고, 종료되지 않은 풀이 JVM 종료를 막는 일도 줄인다. `setCorePoolSize`·`setMaximumPoolSize` 는 실행 중에 호출할 수 있어, 설정 서버 값에 따라 풀 크기를 조정하는 구성이 가능하다.

## 5. 작업이 던진 예외: execute 와 submit 의 차이

```java
		AtomicInteger seq = new AtomicInteger();
		ThreadFactory tf = r -> {
			Thread t = new Thread(r, "w-" + seq.incrementAndGet());
			t.setUncaughtExceptionHandler((th, ex) -> System.out.println("uncaught in " + th.getName() + ": " + ex.getMessage()));
			return t;
		};
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tf) {
			@Override
			protected void afterExecute(Runnable r, Throwable t) {
				super.afterExecute(r, t);
				if (t == null && r instanceof Future<?> f && f.isDone()) {
					try {
						f.get();
					} catch (ExecutionException e) {
						t = e.getCause();
					} catch (CancellationException | InterruptedException e) {
						t = e;
					}
				}
				System.out.println("afterExecute on " + Thread.currentThread().getName() + " throwable=" + (t == null ? null : t.getMessage()));
			}
		};
		pool.execute(() -> System.out.println("A on " + Thread.currentThread().getName()));
		pool.execute(() -> {
			throw new IllegalStateException("execute-boom");
		});
		Thread.sleep(200);
		pool.execute(() -> System.out.println("B on " + Thread.currentThread().getName()));
		Future<?> f = pool.submit(() -> {
			throw new IllegalStateException("submit-boom");
		});
		try {
			f.get();
		} catch (ExecutionException e) {
			System.out.println("Future.get -> " + e.getCause());
		}
		pool.execute(() -> System.out.println("C on " + Thread.currentThread().getName()));
		pool.shutdown();
		pool.awaitTermination(5, TimeUnit.SECONDS);
		System.out.println("threads created=" + seq.get());
```

```text
A on w-1
afterExecute on w-1 throwable=null
afterExecute on w-1 throwable=execute-boom
uncaught in w-1: execute-boom
B on w-2
afterExecute on w-2 throwable=null
afterExecute on w-2 throwable=submit-boom
Future.get -> java.lang.IllegalStateException: submit-boom
C on w-2
afterExecute on w-2 throwable=null
threads created=2
```

`execute` 로 넣은 작업이 예외를 던지면 예외가 워커의 실행 루프 밖으로 전파되어 그 스레드(`w-1`)가 죽고, 스레드의 `UncaughtExceptionHandler` 가 호출된 뒤 풀이 대체 워커(`w-2`)를 만든다. 실패하는 작업이 많으면 스레드 생성 비용이 반복되고 `ThreadLocal` 에 캐시한 값도 매번 사라진다.

`submit` 은 작업을 `FutureTask` 로 감싼다. `FutureTask.run()` 이 예외를 잡아 내부에 저장하므로 워커는 죽지 않고(`C` 가 계속 `w-2` 에서 실행된다), `UncaughtExceptionHandler` 도 호출되지 않는다. 예외는 `Future.get()` 을 호출해야만 `ExecutionException` 으로 나온다. 반환된 `Future` 를 버리면 실패는 로그 한 줄 없이 사라진다. `afterExecute` 의 두 번째 인수도 `submit` 경로에서는 `null` 이라, 위 코드처럼 `Future` 를 직접 열어 봐야 실패를 관측할 수 있다. 이 패턴은 `ThreadPoolExecutor` 의 Javadoc 이 제시하는 방법이다. 풀 수준에서 누락 없는 오류 로깅이 필요하면 이 훅을 두고, 그렇지 않으면 작업 본문을 `try-catch` 로 감싸는 래퍼를 제출 지점에 둔다.

## 6. shutdown 과 shutdownNow

```java
		ThreadPoolExecutor p2 = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
		CountDownLatch started = new CountDownLatch(1);
		p2.execute(() -> {
			started.countDown();
			try {
				Thread.sleep(10_000);
			} catch (InterruptedException e) {
				System.out.println("running task interrupted");
			}
		});
		p2.execute(() -> System.out.println("never runs"));
		p2.execute(() -> System.out.println("never runs"));
		started.await();
		p2.shutdown();
		System.out.println("shutdown: isShutdown=" + p2.isShutdown() + " isTerminating=" + p2.isTerminating() + " queue=" + p2.getQueue().size());
		try {
			p2.execute(() -> { });
		} catch (RejectedExecutionException e) {
			System.out.println("execute after shutdown -> rejected");
		}
		List<Runnable> drained = p2.shutdownNow();
		System.out.println("shutdownNow drained=" + drained.size());
		System.out.println("terminated=" + p2.awaitTermination(2, TimeUnit.SECONDS) + " " + p2);
```

```text
shutdown: isShutdown=true isTerminating=true queue=2
execute after shutdown -> rejected
running task interrupted
shutdownNow drained=2
terminated=true java.util.concurrent.ThreadPoolExecutor@436813f3[Terminated, pool size = 0, active threads = 0, queued tasks = 0, completed tasks = 1]
```

`shutdown()` 은 상태를 `SHUTDOWN` 으로 바꿔 새 제출을 거부하지만 실행 중인 작업과 큐에 있는 작업은 끝까지 처리한다. `shutdownNow()` 는 `STOP` 으로 바꾸고 큐를 비워 미실행 작업 목록을 반환하며, 실행 중인 워커에 인터럽트를 건다. 인터럽트는 요청일 뿐이어서 작업이 `InterruptedException` 을 삼키거나 인터럽트에 반응하지 않는 블로킹 호출 안에 있으면 풀은 종료되지 않는다. 정상 종료 절차는 `shutdown()` 후 `awaitTermination` 으로 기한을 주고, 기한이 지나면 `shutdownNow()` 를 호출한 뒤 반환된 미실행 작업을 기록하는 순서다. Java 19 부터 `ExecutorService` 가 `AutoCloseable` 이어서 `try-with-resources` 로 감싸면 `close()` 가 `shutdown()` 과 무기한 대기를 수행한다.

## 7. Spring ThreadPoolTaskExecutor 와의 대응

이 절은 Spring Framework 와 Spring Boot 의 레퍼런스 문서에 근거한 정리이며 이 문서에서 실행해 검증하지는 않았다. `ThreadPoolTaskExecutor` 는 `ThreadPoolExecutor` 를 감싼 빈이고 기본값은 `corePoolSize = 1`, `maxPoolSize = Integer.MAX_VALUE`, `queueCapacity = Integer.MAX_VALUE`, `keepAliveSeconds = 60` 이다. `queueCapacity` 가 양수이면 `LinkedBlockingQueue`, 0 이면 `SynchronousQueue` 를 만든다. 기본값 그대로면 2절의 무계 큐 구성이어서 스레드 1 개가 모든 작업을 순서대로 처리하고 `maxPoolSize` 는 쓰이지 않는다. Spring Boot 가 자동 구성하는 `applicationTaskExecutor` 는 `spring.task.execution.pool.core-size` 기본값이 8 이고 큐 용량과 최대 크기는 역시 무제한이다. `@Async` 작업이 밀릴 수 있는 서비스는 `queue-capacity` 와 `max-size` 를 유한한 값으로 지정하고 `setRejectedExecutionHandler` 로 정책을 정해야 1~3절의 규칙이 의도대로 작동한다.

`void` 를 반환하는 `@Async` 메서드의 예외는 호출자에게 전달되지 않고 `AsyncUncaughtExceptionHandler` 로 넘어간다. 기본 구현은 로그만 남기므로, 알림이나 메트릭이 필요하면 `AsyncConfigurer` 로 직접 등록한다. `setWaitForTasksToCompleteOnShutdown(true)` 와 `setAwaitTerminationSeconds` 는 6절의 `shutdown()` + `awaitTermination` 절차를 컨텍스트 종료 시점에 수행하게 한다. 요청 스레드의 `MDC` 나 `SecurityContext` 를 작업 스레드로 넘기려면 `TaskDecorator` 로 제출 시점의 값을 캡처한다.

Tomcat 의 요청 처리 풀은 같은 클래스 이름을 쓰지만 동작이 다르다. Tomcat 은 자체 `TaskQueue` 에서 현재 스레드 수가 최대치보다 작으면 `offer` 가 `false` 를 반환하게 만들어 "코어 → 최대 → 큐" 순서를 구현한다. 요청 처리처럼 대기보다 즉시 실행이 중요한 풀에 맞춘 선택이다.

## 8. 크기 결정 기준

CPU 를 계속 쓰는 작업은 코어 수와 같거나 하나 많은 스레드가 상한이다. 그 이상은 컨텍스트 스위칭만 늘린다. 대기 시간이 섞인 작업은 Goetz 의 식 `스레드 수 = 코어 수 × 목표 CPU 사용률 × (1 + 대기 시간 / 계산 시간)` 을 출발점으로 삼는다. 4 코어에서 작업당 계산 10ms, 외부 호출 대기 90ms 이면 `4 × 1 × (1 + 9) = 40` 이다. 이 값은 상한의 추정치이고 실제 한도는 풀 뒤의 자원이 정한다. DB 커넥션 풀이 10 개인데 스레드가 40 개면 30 개는 커넥션을 기다릴 뿐이다.

큐 용량은 "허용 가능한 대기 시간 × 처리율" 로 정한다. 초당 200 건을 처리하는 풀에서 대기를 최대 0.5 초까지 허용하면 큐는 100 이다. 큐가 그보다 길면 타임아웃된 요청을 뒤늦게 처리하느라 자원을 쓰게 된다. `getQueue().size()`, `getActiveCount()`, `getLargestPoolSize()`, 거부 횟수를 메트릭으로 내보내야 설정이 맞는지 판단할 수 있다. 대기 위주의 작업이 대부분이면 Java 21 의 가상 스레드(`Executors.newVirtualThreadPerTaskExecutor()`)가 풀 크기 조정 문제 자체를 없애는 대안이며, 그 경우 동시 실행 제한은 풀 크기가 아니라 `Semaphore` 로 건다.

검증 환경: OpenJDK 21.0.13 에서 `java <파일>.java` 로 실행한 출력이다. 1·2절은 `Order.java`, 3절은 `Reject.java`, 4·6절은 `Life.java`, 5절은 `Failure.java` 의 발췌다. 생략한 부분은 `import` 와 `main` 선언, 3절의 정책별 `run` 호출 네 줄, 4절의 `setCorePoolSize`·`setMaximumPoolSize` 호출과 그 출력 한 줄이다. 6절 마지막 줄의 `@436813f3` 은 실행마다 달라지는 식별 해시다.

## 참고

- Java SE 21 API, ThreadPoolExecutor: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- Java SE 21 API, Executors: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html
- Java SE 21 API, ExecutorService (close): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html
- OpenJDK 소스, ThreadPoolExecutor.java: https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ThreadPoolExecutor.java
- Spring Framework Reference, Task Execution and Scheduling: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring Boot Reference, Task Execution and Scheduling: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- Apache Tomcat 소스, TaskQueue.java: https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/threads/TaskQueue.java
- Brian Goetz 외, Java Concurrency in Practice, 8장 Applying Thread Pools
