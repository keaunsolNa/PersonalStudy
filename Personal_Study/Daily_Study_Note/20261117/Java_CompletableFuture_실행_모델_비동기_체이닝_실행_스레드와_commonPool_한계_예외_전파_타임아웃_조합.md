Notion 원본: https://www.notion.so/41b5a06fd6d3812db14fc5cd7cef69ce

# Java CompletableFuture 실행 모델: 체이닝 콜백의 실행 스레드, commonPool 의 한계, 예외 전파와 타임아웃·조합

> 2026-11-17 신규 주제 · 확장 대상: JAVA(Thread·ExecutorService·Future), Spring(@Async 와 TaskExecutor)

## 학습 목표

- `thenApply` 와 `thenApplyAsync` 의 콜백이 어느 스레드에서 실행되는지 다섯 가지 경우로 재현한다
- 기본 비동기 풀이 `ForkJoinPool.commonPool` 일 때와 아닐 때를 구분하고 블로킹 호출의 처리량을 측정한다
- `CompletionException` 래핑 규칙을 확인하고 `exceptionally`·`handle`·`whenComplete` 를 용도에 맞게 고른다
- `allOf`·`thenCompose`·`thenCombine`·`orTimeout` 으로 병렬 호출을 조합하고 지연 평가 스트림의 순차 실행 함정을 피한다

## 1. Future 에서 CompletionStage 로

`Future.get()` 은 결과를 얻는 유일한 방법이 블로킹이다. 호출 세 개를 병렬로 보내고 결과를 합치려면 스레드 하나가 `get()` 에 묶여 있어야 하고, "A 가 끝나면 그 결과로 B 를 호출" 같은 의존 관계는 코드로 직접 엮어야 한다. `CompletableFuture` 는 `Future` 에 `CompletionStage` 를 더해 완료 시점에 실행할 의존 단계를 등록하게 한다. 각 단계는 새 `CompletableFuture` 를 반환하고, 앞 단계가 완료되면 스택에 쌓아 둔 의존 단계를 꺼내 실행한다.

이 구조에서 실무 문제의 대부분은 세 가지 질문으로 정리된다. 콜백이 어느 스레드에서 실행되는가, 그 스레드가 속한 풀은 블로킹을 감당할 수 있는가, 실패는 어떤 모양으로 다음 단계에 전달되는가. 아래 절은 이 순서로 확인한다.

## 2. 콜백을 실행하는 스레드

메서드 이름에 `Async` 가 없는 단계(`thenApply`, `thenAccept`, `thenCompose` 등)는 별도 스레드로 넘기지 않는다. 등록 시점에 앞 단계가 이미 완료되어 있으면 등록하는 스레드가 그 자리에서 실행하고, 아직이면 나중에 앞 단계를 완료시키는 스레드가 실행한다.

```java
import java.util.concurrent.*;

public class ThreadDemo {
    static String t() { return Thread.currentThread().getName().replaceAll("\\d+$", "N"); }

    public static void main(String[] args) throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2, r -> new Thread(r, "io-pool"));

        // 1) 이미 완료된 future 에 붙인 비-Async 콜백은 등록하는 스레드가 그 자리에서 실행한다
        CompletableFuture.completedFuture(1)
            .thenAccept(v -> System.out.println("1) completed + thenAccept : " + t()));

        // 2) 아직 완료되지 않은 future 에 붙이면 완료시킨 스레드가 실행한다
        CompletableFuture.supplyAsync(() -> { sleep(100); return 1; }, io)
            .thenAccept(v -> System.out.println("2) pending + thenAccept   : " + t())).join();

        // 3) Async 변형에 executor 를 주지 않으면 기본 비동기 풀
        CompletableFuture.completedFuture(1)
            .thenAcceptAsync(v -> System.out.println("3) thenAcceptAsync        : " + t())).join();

        // 4) executor 를 명시하면 그 풀에서 실행된다
        CompletableFuture.completedFuture(1)
            .thenAcceptAsync(v -> System.out.println("4) thenAcceptAsync(io)    : " + t()), io).join();

        // 5) 직접 complete 하면 complete 를 호출한 스레드가 의존 단계를 실행한다
        CompletableFuture<String> manual = new CompletableFuture<>();
        CompletableFuture<Void> dep = manual.thenAccept(v -> System.out.println("5) manual complete        : " + t()));
        Thread callback = new Thread(() -> manual.complete("x"), "driver-callback");
        callback.start();
        dep.join();
        io.shutdown();
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
```

`java -XX:ActiveProcessorCount=4 ThreadDemo` 의 출력이다.

```text
1) completed + thenAccept : main
2) pending + thenAccept   : io-pool
3) thenAcceptAsync        : ForkJoinPool.commonPool-worker-N
4) thenAcceptAsync(io)    : io-pool
5) manual complete        : driver-callback
```

1번과 2번은 같은 코드가 타이밍에 따라 다른 스레드에서 실행될 수 있음을 뜻한다. 원격 호출이 캐시 적중으로 즉시 완료되면 콜백이 요청 스레드에서 돌고, 그렇지 않으면 I/O 풀에서 돈다. 5번은 비동기 드라이버를 감쌀 때 문제가 된다. Netty 이벤트 루프나 DB 드라이버의 콜백 스레드가 `complete` 를 호출하면 뒤에 붙은 비-Async 단계 전체가 그 스레드에서 실행되므로, 거기서 블로킹 호출이나 무거운 변환을 하면 이벤트 루프가 멈춘다. 실행 위치가 중요한 단계는 4번처럼 executor 를 명시한 `Async` 변형으로 고정한다.

## 3. 기본 비동기 풀: commonPool 과 그 예외

executor 를 생략한 `supplyAsync`·`thenApplyAsync` 는 `ForkJoinPool.commonPool()` 을 쓴다. 이 풀의 병렬도는 기본적으로 `코어 수 - 1` 이고 JVM 전체가 공유한다. 병렬 스트림도 같은 풀을 쓴다. CPU 연산용으로 설계된 풀이라 블로킹 호출을 넣으면 워커가 금방 소진된다.

```java
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class CommonPoolDemo {
    public static void main(String[] args) {
        System.out.println("cores=" + Runtime.getRuntime().availableProcessors()
            + ", commonPool parallelism=" + ForkJoinPool.commonPool().getParallelism());
        int tasks = 12;

        long t0 = System.nanoTime();
        List<CompletableFuture<Integer>> onDefault = IntStream.range(0, tasks)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> blockingCall(i))).toList();
        onDefault.forEach(CompletableFuture::join);
        System.out.println("default pool    : " + ms(t0) + " ms");

        ExecutorService io = Executors.newFixedThreadPool(tasks);
        long t1 = System.nanoTime();
        List<CompletableFuture<Integer>> onIo = IntStream.range(0, tasks)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> blockingCall(i), io)).toList();
        onIo.forEach(CompletableFuture::join);
        System.out.println("fixed(12)       : " + ms(t1) + " ms");
        io.shutdown();

        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            long t2 = System.nanoTime();
            List<CompletableFuture<Integer>> onVt = IntStream.range(0, tasks)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> blockingCall(i), vt)).toList();
            onVt.forEach(CompletableFuture::join);
            System.out.println("virtual threads : " + ms(t2) + " ms");
        }
    }

    static int blockingCall(int i) {
        try { Thread.sleep(200); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        return i;
    }

    static long ms(long start) { return Math.round((System.nanoTime() - start) / 1e8) * 100; }
}
```

200ms 짜리 블로킹 호출 12개를 보낸 결과다. 위는 `-XX:ActiveProcessorCount=4`, 아래는 `-XX:ActiveProcessorCount=2` 로 실행했다.

```text
cores=4, commonPool parallelism=3
default pool    : 800 ms
fixed(12)       : 200 ms
virtual threads : 200 ms

cores=2, commonPool parallelism=1
default pool    : 200 ms
fixed(12)       : 200 ms
virtual threads : 200 ms
```

4코어에서는 워커 3개가 12개를 4번에 나눠 처리해 800ms 가 걸렸다. 2코어 결과가 200ms 인 것은 풀이 빨라져서가 아니다. `CompletableFuture` 는 commonPool 의 병렬도가 1 이하이면 commonPool 을 쓰지 않고 작업마다 새 스레드를 만드는 내부 executor(`ThreadPerTaskExecutor`)로 대체한다. 2절 예제를 2코어로 실행하면 3번 줄이 `Thread-N` 으로 찍히는 것으로 확인할 수 있다. CPU 제한이 1~2코어인 컨테이너에서는 기본 풀이 스레드 수 상한이 없는 실행기가 되고, 코어가 늘면 갑자기 3개짜리 풀로 바뀌는 셈이다. 어느 쪽도 I/O 작업의 기본값으로 삼을 수 없으므로 블로킹 호출에는 항상 전용 executor 를 넘긴다. Java 21 이라면 가상 스레드 executor 가 풀 크기 산정 문제를 없애 준다.

## 4. 예외가 전달되는 모양

실패한 단계 뒤의 `thenApply` 류는 실행되지 않고 예외만 다음 단계로 넘어간다. 이때 예외가 `CompletionException` 으로 감싸져 있는지 여부가 위치에 따라 달라진다.

```java
import java.util.concurrent.*;

public class ExceptionDemo {
    static String name(Throwable t) { return t.getClass().getSimpleName(); }

    /** 단계 위치에 따라 감싸져 있을 수도 아닐 수도 있으므로 원인을 꺼내는 함수를 하나 둔다 */
    static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null ? t.getCause() : t;
    }

    public static void main(String[] args) throws Exception {
        CompletableFuture<Integer> async = CompletableFuture.supplyAsync(() -> {
            if (System.nanoTime() > 0) throw new IllegalStateException("boom");
            return 1;
        });
        try { async.join(); } catch (CompletionException e) { System.out.println("join : " + name(e) + " <- " + name(e.getCause())); }
        try { async.get(); } catch (ExecutionException e) { System.out.println("get  : " + name(e) + " <- " + name(e.getCause())); }

        // exceptionally 가 받는 예외의 모양은 실패가 어디서 났는지에 따라 다르다
        CompletableFuture<Integer> direct = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        direct.exceptionally(ex -> { System.out.println("failedFuture, direct    : " + name(ex)); return 0; }).join();
        direct.thenApply(v -> v + 1)
              .exceptionally(ex -> { System.out.println("failedFuture, 1 stage   : " + name(ex) + " <- " + name(ex.getCause())); return 0; }).join();
        async.exceptionally(ex -> { System.out.println("supplyAsync, direct     : " + name(ex) + " <- " + name(ex.getCause())); return 0; }).join();
        async.exceptionally(ex -> { System.out.println("unwrap                  : " + name(unwrap(ex))); return 0; }).join();

        // handle 은 값으로 복구하고, whenComplete 는 결과를 바꾸지 못한다
        int recovered = async.handle((v, ex) -> ex == null ? v : -1).join();
        CompletableFuture<Integer> observed = async.whenComplete((v, ex) -> { });
        System.out.println("handle -> " + recovered + ", whenComplete still failed = " + observed.isCompletedExceptionally());

        // 실패하면 thenApply 단계는 건너뛴다
        boolean[] ran = {false};
        async.thenApply(v -> { ran[0] = true; return v; }).exceptionally(ex -> 0).join();
        System.out.println("thenApply ran on failure = " + ran[0]);

        // 타임아웃 (Java 9+)
        try {
            CompletableFuture.supplyAsync(() -> { sleep(500); return "late"; }).orTimeout(100, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) { System.out.println("orTimeout : " + name(e.getCause())); }
        String fallback = CompletableFuture.supplyAsync(() -> { sleep(500); return "late"; })
            .completeOnTimeout("fallback", 100, TimeUnit.MILLISECONDS).join();
        System.out.println("completeOnTimeout : " + fallback);

        // cancel(true) 는 실행 중인 작업 스레드를 인터럽트하지 않는다
        boolean[] interrupted = {false}, finished = {false};
        CompletableFuture<Void> job = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(300); finished[0] = true; } catch (InterruptedException e) { interrupted[0] = true; }
        });
        Thread.sleep(50);
        System.out.println("cancel returned " + job.cancel(true) + ", isCancelled = " + job.isCancelled());
        Thread.sleep(400);
        System.out.println("task interrupted = " + interrupted[0] + ", task finished anyway = " + finished[0]);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
```

```text
join : CompletionException <- IllegalStateException
get  : ExecutionException <- IllegalStateException
failedFuture, direct    : IllegalStateException
failedFuture, 1 stage   : CompletionException <- IllegalStateException
supplyAsync, direct     : CompletionException <- IllegalStateException
unwrap                  : IllegalStateException
handle -> -1, whenComplete still failed = true
thenApply ran on failure = false
orTimeout : TimeoutException
completeOnTimeout : fallback
cancel returned true, isCancelled = true
task interrupted = false, task finished anyway = true
```

`completeExceptionally` 나 `failedFuture` 로 직접 실패시킨 future 에 바로 붙인 `exceptionally` 는 원본 예외를 받는다. 한 단계라도 거치거나 `supplyAsync` 의 람다가 던진 경우에는 `CompletionException` 으로 감싸져 온다. 그래서 `ex instanceof MyBusinessException` 으로 분기하는 코드는 체인 구조를 바꾸는 순간 조용히 깨진다. 예외 종류로 분기할 때는 `unwrap` 같은 함수로 원인을 먼저 꺼낸다.

세 가지 종료 처리 메서드의 역할은 다음과 같이 나뉜다. `exceptionally` 는 실패했을 때만 호출되어 대체 값을 돌려준다. `handle` 은 성공과 실패 모두에서 호출되어 결과를 변환한다. `whenComplete` 는 결과를 관찰만 하며 원래의 성공 값이나 예외가 그대로 다음 단계로 전달되므로, 로깅과 메트릭 기록에 쓴다.

## 5. 타임아웃과 취소의 실제 범위

`orTimeout` 은 시간이 지나면 future 를 `TimeoutException` 으로 완료시키고, `completeOnTimeout` 은 대체 값으로 완료시킨다. 둘 다 future 의 상태만 바꾼다. 위 출력의 마지막 두 줄이 보여 주듯 `cancel(true)` 조차 실행 중인 작업을 인터럽트하지 않는다. `CompletableFuture` 는 자신을 실행하는 스레드에 대한 참조를 갖지 않기 때문이고, Javadoc 도 `mayInterruptIfRunning` 인자가 효과가 없다고 명시한다.

따라서 타임아웃이 난 뒤에도 원격 호출과 그 스레드는 끝날 때까지 점유된다. 느린 의존 서비스가 있을 때 `orTimeout` 만 걸면 호출자는 빨리 실패하지만 풀의 스레드는 계속 쌓인다. 실제 자원 회수는 하위 계층의 타임아웃이 담당해야 한다. HTTP 클라이언트의 연결·응답 타임아웃, JDBC 의 `queryTimeout`, 소켓 타임아웃을 `orTimeout` 보다 짧거나 같게 설정한다.

## 6. 조합: allOf, thenCompose, thenCombine, anyOf

```java
import java.util.List;
import java.util.concurrent.*;

public class ComposeDemo {
    record Price(String shop, int won) {}

    public static void main(String[] args) {
        ExecutorService io = Executors.newFixedThreadPool(8);
        List<String> shops = List.of("a", "b", "c", "d", "e", "f", "g", "h");

        long t0 = System.nanoTime();
        List<CompletableFuture<Price>> futures = shops.stream()
            .map(s -> CompletableFuture.supplyAsync(() -> fetch(s), io))
            .toList();                                   // 먼저 전부 제출하고
        List<Price> prices = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).toList())
            .join();                                     // 그 다음에 한 번만 대기한다
        System.out.println("allOf      : " + prices.size() + " prices, " + ms(t0) + " ms");

        long t1 = System.nanoTime();
        List<Price> lazy = shops.stream()
            .map(s -> CompletableFuture.supplyAsync(() -> fetch(s), io))
            .map(CompletableFuture::join)                // 스트림이 원소 단위로 제출-대기를 반복한다
            .toList();
        System.out.println("lazy join  : " + lazy.size() + " prices, " + ms(t1) + " ms");

        // thenCompose 는 future 를 반환하는 단계를 평탄화하고, thenCombine 은 독립된 두 결과를 합친다
        CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> fetch("a"), io)
            .thenCompose(p -> CompletableFuture.supplyAsync(() -> p.won() + shipping(p.shop()), io))
            .thenCombine(CompletableFuture.supplyAsync(() -> 500, io), (sum, coupon) -> sum - coupon);
        System.out.println("compose    : " + total.join());

        // anyOf 는 가장 먼저 끝난 하나(실패 포함)로 완료된다
        Object first = CompletableFuture.anyOf(
            CompletableFuture.supplyAsync(() -> { sleep(200); return "slow"; }, io),
            CompletableFuture.supplyAsync(() -> { sleep(50); return "fast"; }, io)).join();
        System.out.println("anyOf      : " + first);
        io.shutdown();
    }

    static Price fetch(String shop) { sleep(200); return new Price(shop, 10_000 + shop.charAt(0)); }
    static int shipping(String shop) { sleep(100); return 3_000; }
    static long ms(long start) { return Math.round((System.nanoTime() - start) / 1e8) * 100; }
    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
```

```text
allOf      : 8 prices, 200 ms
lazy join  : 8 prices, 1600 ms
compose    : 12597
anyOf      : fast
```

두 번째 줄이 가장 흔한 실수다. 한 스트림 파이프라인 안에서 `supplyAsync` 와 `join` 을 이어 쓰면 스트림이 원소 하나를 끝까지 처리한 뒤 다음 원소로 넘어가므로 8개가 순차 실행되어 1600ms 가 걸린다. future 목록을 먼저 만들어 모두 제출한 다음 `allOf` 로 기다려야 200ms 가 된다. `allOf` 는 `CompletableFuture<Void>` 를 반환하므로 결과는 원래 목록에서 `join` 으로 꺼내며, 이 시점에는 모두 완료되어 있어 블로킹되지 않는다. `allOf` 는 하나가 실패해도 나머지가 끝날 때까지 기다린 뒤 실패로 완료된다. 빠른 실패가 필요하면 각 future 에 `exceptionally` 로 공용 future 를 실패시키는 코드를 붙이거나 Java 21 의 구조적 동시성(프리뷰)을 검토한다.

## 7. Spring 에서 쓸 때 확인할 것

`@Async` 메서드가 `CompletableFuture` 를 반환하면 호출자는 위의 조합 연산을 그대로 쓸 수 있다. 메서드 본문은 Spring 의 `TaskExecutor` 에서 실행되지만, 반환된 future 에 호출자가 붙인 비-Async 단계는 2절의 규칙대로 그 executor 스레드 또는 호출 스레드에서 실행된다. 세 가지를 확인한다.

첫째, 스레드에 묶인 문맥은 넘어가지 않는다. 트랜잭션, `SecurityContext`, MDC, `RequestContextHolder` 는 모두 `ThreadLocal` 기반이라 다른 스레드에서 실행되는 단계에서는 비어 있다. 로깅 문맥과 보안 문맥은 `TaskDecorator` 나 `DelegatingSecurityContextExecutor` 로 복사하고, 트랜잭션은 넘기려 하지 말고 비동기 단계 안에서 새로 시작한다. 지연 로딩 엔티티를 다른 스레드로 넘기면 영속성 컨텍스트 밖에서 초기화가 시도되므로 DTO 로 변환해 넘긴다.

둘째, executor 의 큐 설정이다. `ThreadPoolTaskExecutor` 는 코어 스레드가 모두 바쁘면 큐를 먼저 채우고, 큐가 가득 차야 최대 스레드 수까지 늘린다. 큐 용량이 무제한이면 최대 스레드 수 설정은 쓰이지 않고 지연만 쌓인다. 큐 용량과 거부 정책(`CallerRunsPolicy` 등)을 명시한다.

셋째, 요청 스레드에서 `join()` 으로 기다리는 구조라면 요청 스레드 하나와 작업 스레드 여러 개를 동시에 점유한다. 병렬화로 응답 시간은 줄지만 서버 전체의 스레드 사용량은 늘어난다. 동시 요청 수에 호출 팬아웃을 곱한 값이 executor 와 하위 커넥션 풀의 용량 안에 들어오는지 계산해 둔다.

검증 환경: OpenJDK 21.0.12, Linux x86_64. 코어 수에 따라 달라지는 결과는 `-XX:ActiveProcessorCount` 로 고정해 측정했고, 소요 시간은 100ms 단위로 반올림한 값이다.

## 참고

- Java SE 21 API, CompletableFuture: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- Java SE 21 API, CompletionStage: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionStage.html
- Java SE 21 API, ForkJoinPool (common pool 설정): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinPool.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- Spring Framework Reference, Task Execution and Scheduling: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Brian Goetz 외, Java Concurrency in Practice, Addison-Wesley, 2006
