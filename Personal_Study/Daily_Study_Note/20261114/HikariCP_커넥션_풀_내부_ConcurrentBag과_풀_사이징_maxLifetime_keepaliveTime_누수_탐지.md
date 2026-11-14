Notion 원본: https://www.notion.so/4185a06fd6d3813e8c0ff58c86348f5c

# HikariCP 커넥션 풀 내부와 운영 설정: ConcurrentBag 대여 경로, 풀 사이징, maxLifetime·keepaliveTime, 누수 탐지

> 2026-11-14 신규 주제 · 확장 대상: Spring(DataSource 와 트랜잭션), JAVA(동시성 유틸리티)

## 학습 목표

- `getConnection()` 이 ConcurrentBag 의 세 단계를 거쳐 커넥션을 대여하는 경로를 소스 기준으로 추적한다
- 주요 설정의 기본값을 README 와 실제 getter 출력으로 대조한다
- 풀 크기를 공식으로 산정하고 중첩 획득 교착을 재현한다
- 풀 고갈 예외를 MXBean 지표와 누수 탐지 로그로 진단한다

## 1. 커넥션 풀이 해결하는 비용

풀이 없으면 요청마다 물리 커넥션을 열면서 TCP 연결, 인증, 세션 초기화를 반복한다. 풀은 이 비용을 미리 치러 두고 열린 커넥션을 재사용하며, DB 로 향하는 동시 세션 수의 상한(`maximumPoolSize`) 역할도 한다. 대신 유휴 커넥션이 DB 세션을 계속 점유하고, 쥐고 있던 커넥션이 DB·방화벽 쪽에서 먼저 끊기는 상황을 풀이 관리해야 한다.

아래는 모든 실험이 공유하는 준비 코드다. `HikariDemo.java` 한 파일에 시나리오별 static 메서드를 두고 실행했고, 본문 코드는 그 파일에서 옮긴 것이다(import 는 `com.zaxxer.hikari.*`, `java.sql.*`, `java.util.concurrent.*`).

```java
static HikariConfig config(String db, int size) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
    cfg.setPoolName("demo");
    cfg.setMaximumPoolSize(size);
    return cfg;
}

static String stats(HikariDataSource ds) {
    HikariPoolMXBean mx = ds.getHikariPoolMXBean();
    return "total=" + mx.getTotalConnections() + " active=" + mx.getActiveConnections()
        + " idle=" + mx.getIdleConnections() + " waiting=" + mx.getThreadsAwaitingConnection();
}
```

## 2. 대여 경로: getConnection 에서 ConcurrentBag.borrow 까지

5.1.0 소스에서 `HikariDataSource.getConnection()` 은 `fastPathPool.getConnection()` 으로 위임하고, `HikariPool.getConnection(hardTimeout)` 은 `connectionBag.borrow(timeout, MILLISECONDS)` 를 호출한다. `borrow` 가 null 을 돌려주면 타임아웃 예외를 만든다. 엔트리를 받았더라도 evict 표시가 있거나, 마지막 사용 후 500ms(`aliveBypassWindowMs`)가 지났는데 유효성 검사(`isConnectionDead`)에 실패하면 그 커넥션을 닫고 남은 시간으로 다시 빌린다.

`ConcurrentBag.borrow` 는 세 단계다. 첫째, 스레드 로컬 리스트를 뒤에서부터 꺼내 `compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)` 에 성공하면 반환한다. 둘째, `waiters` 를 증가시키고 `CopyOnWriteArrayList` 인 `sharedList` 전체를 같은 CAS 로 훑는다. 셋째, 둘 다 실패하면 `listener.addBagItem(waiting)` 으로 커넥션 추가를 요청하고 `SynchronousQueue`(fair 모드) 인 `handoffQueue.poll` 로 남은 시간만큼 기다린다.

```java
// ConcurrentBag.borrow (5.1.0), 3단계의 핵심
final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
   return bagEntry;
}
```

반납(`requite`)은 상태를 `STATE_NOT_IN_USE` 로 바꾼 뒤 대기자가 있으면 `handoffQueue.offer` 로 직접 넘기고, 없으면 자기 스레드 로컬 리스트(50개 미만일 때)에 넣는다. 락 대신 엔트리 단위 CAS 를 쓰는 구조이고, 스레드 로컬 리스트의 엔트리는 다른 스레드가 `sharedList` 에서 먼저 가져갔을 수 있어 CAS 실패를 전제로 한다. 3절 실험에서 마지막에 반납한 커넥션이 먼저 다시 나오는 것으로 1단계를 관찰했다.

## 3. ProxyConnection 과 close 시 반납·롤백·상태 리셋

애플리케이션이 받는 객체는 물리 커넥션이 아니라 `ProxyConnection` 하위 클래스다. `close()` 는 물리 연결을 닫지 않고, 열린 Statement 를 닫고, 누수 탐지 태스크를 취소하고, autoCommit 이 false 인 상태에서 커밋되지 않은 작업이 있으면 `rollback()` 하고, 바뀐 속성(readOnly, autoCommit, 격리 수준, catalog, 네트워크 타임아웃, schema)을 풀 기본값으로 되돌린 뒤 `poolEntry.recycle()` 로 bag 에 반납한다. 이후 프록시의 delegate 는 `ClosedConnection` 으로 바뀐다.

```java
Connection c1 = ds.getConnection();
Connection c2 = ds.getConnection();
Connection raw1 = c1.unwrap(Connection.class);
Connection raw2 = c2.unwrap(Connection.class);
System.out.println("proxy class = " + c1.getClass().getName());
try (Statement s = c1.createStatement()) {
    s.execute("create table t(id int)");
}
c1.setAutoCommit(false);
try (Statement s = c1.createStatement()) {
    System.out.println("inserted = " + s.executeUpdate("insert into t values (1)"));
}
c1.close();                       // no commit
c2.close();                       // returned last
```

이어서 닫힌 `c1` 을 사용해 보고, 두 개를 다시 빌려 물리 커넥션 동일성, autoCommit, 행 수를 출력했다.

```text
proxy class = com.zaxxer.hikari.pool.HikariProxyConnection
inserted = 1
use after close: Connection is closed
first re-borrow is raw2 = true
second is raw1 = true, autoCommit = true
rows = 0
```

커밋하지 않은 insert 는 롤백됐고 autoCommit 은 true 로 복원됐다. 이 안전장치 때문에 커밋 누락은 예외 없이 롤백으로 끝난다.

## 4. 주요 설정의 의미와 기본값

README(dev 브랜치)의 설명과 5.1.0, 7.1.0 의 getter 출력을 대조했다.

| 설정 | 의미 | README 기본값 | 5.1.0 실측 |
| --- | --- | --- | --- |
| maximumPoolSize | idle 과 in-use 를 합친 최대 커넥션 수 | 10 | 10 (시작 전 -1) |
| minimumIdle | 유지하려는 최소 idle 수 | maximumPoolSize 와 동일 | 10 (시작 전 -1) |
| connectionTimeout | 대여 대기 한도, 최소 250ms | 30000 | 30000 |
| idleTimeout | idle 제거 기준, minimumIdle 이 더 작을 때만 적용 | 600000 | 600000 |
| maxLifetime | 커넥션 최대 수명, 최소 30000ms | 1800000 | 1800000 |
| keepaliveTime | idle 커넥션 ping 주기, 최소 30000ms | 120000 | 0 (비활성) |
| validationTimeout | 유효성 검사 한도 | 5000 | 5000 |
| leakDetectionThreshold | 누수 의심 로그 기준, 최소 2000ms | 0 | 0 |

```java
HikariConfig cfg = new HikariConfig();
System.out.println("before start: " + values(cfg));   // values() 는 getter 8개를 이어 붙인다
cfg.setJdbcUrl("jdbc:h2:mem:defaults");
try (HikariDataSource ds = new HikariDataSource(cfg)) {
    System.out.println("after start : " + values(ds));
}
```

```text
before start: maximumPoolSize=-1 minimumIdle=-1 connectionTimeout=30000 idleTimeout=600000 maxLifetime=1800000 keepaliveTime=0 validationTimeout=5000 leakDetectionThreshold=0
after start : maximumPoolSize=10 minimumIdle=10 connectionTimeout=30000 idleTimeout=600000 maxLifetime=1800000 keepaliveTime=0 validationTimeout=5000 leakDetectionThreshold=0
```

5.1.0 에서 풀 크기 계열은 -1 로 있다가 풀 시작 시 10 으로 확정된다. `keepaliveTime` 은 5.1.0 에서 0 인데, 같은 코드를 7.1.0 jar 로 실행하면 시작 전부터 `maximumPoolSize=10 ... keepaliveTime=120000` 이 나온다. README 는 최신 버전 기준이므로 기본값은 쓰는 버전의 getter 로 확인해야 한다. README 는 `minimumIdle` 을 따로 두지 말고 고정 크기 풀로 쓰기를 권한다. 고정 크기 풀은 한가한 시간에도 DB 세션을 풀 크기만큼 점유한다.

## 5. 풀 사이징 원칙과 pool-locking

위키 "About Pool Sizing" 은 PostgreSQL 프로젝트가 제시한 `connections = ((core_count * 2) + effective_spindle_count)` 를 출발점으로 소개한다. 근거는 코어 수를 넘는 동시 실행은 컨텍스트 스위칭만 늘리고, 디스크·네트워크 I/O 로 블로킹되는 만큼만 코어 수보다 많은 커넥션이 이득이라는 것이다. 위키는 부하 테스트로 이 출발점 주변을 조정하라고 하며, 작은 풀에 스레드가 대기하는 형태를 권한다.

작은 풀의 대가는 대기다. 풀 크기 2, `connectionTimeout` 1000ms 에서 두 개를 쥔 채 세 번째 스레드가 요청하게 했다.

```text
2 borrowed  : total=2 active=2 idle=0 waiting=0
3rd waiting : total=2 active=2 idle=0 waiting=1
java.sql.SQLTransientConnectionException: demo - Connection is not available, request timed out after 1000ms (total=2, active=2, idle=0, waiting=0)
all returned: total=2 active=0 idle=2 waiting=0
```

한 스레드가 커넥션을 동시에 여러 개 쥐면 교착이 생길 수 있다. 위키의 공식은 `pool size = Tn x (Cm - 1) + 1` 이다(Tn 은 최대 스레드 수, Cm 은 한 스레드가 동시에 쥐는 최대 커넥션 수). 스레드 3개가 각각 2개를 쓰면 최소 4 가 필요하다.

```java
CyclicBarrier allHoldOuter = new CyclicBarrier(threads);   // threads = 3
ExecutorService workers = Executors.newFixedThreadPool(threads);
for (int i = 0; i < threads; i++) {
    workers.submit(() -> {
        String name = Thread.currentThread().getName();
        try (Connection outer = ds.getConnection()) {
            allHoldOuter.await();                         // all 3 threads hold one
            try (Connection inner = ds.getConnection()) { // then each asks for a second
                System.out.println(name + " OK");
            }
        } catch (Exception e) {
            System.out.println(name + " FAIL " + e.getMessage());
        }
    });
}
```

풀 크기 3 (`connectionTimeout` 2000ms) 에서는 세 스레드가 모두 실패했고(아래는 그중 두 줄), 4 에서는 모두 `OK` 를 출력했다.

```text
pool-1-thread-3 FAIL demo - Connection is not available, request timed out after 2000ms (total=3, active=3, idle=0, waiting=2)
pool-1-thread-1 FAIL demo - Connection is not available, request timed out after 2002ms (total=3, active=3, idle=0, waiting=0)
```

위키는 이 값이 최적이 아니라 교착을 피하는 최소치이며, 풀을 키우기 전에 애플리케이션에서 중첩 획득을 없애는 쪽을 먼저 검토하라고 한다.

## 6. maxLifetime 과 keepaliveTime

DB 나 중간 네트워크 장비는 오래 쉰 연결을 끊는다. MySQL 8.4 매뉴얼 기준 `wait_timeout` 의 기본값은 28800초이고, 서버는 비대화형 연결에 활동이 없으면 이 시간 뒤에 닫는다. HikariCP 는 대여 시 유효성 검사에 실패한 커넥션을 닫고 `Possibly consider using a shorter maxLifetime value` 라는 WARN 을 남기고 다른 커넥션을 다시 빌린다(소스 확인, 재현하지 않음). 그래서 README 는 `maxLifetime` 을 DB·인프라가 강제하는 한도보다 몇 초 짧게 두라고 강하게 권한다. 사용 중인 커넥션은 수명이 지나도 회수되지 않고 반납 후 제거된다. 소스에서는 커넥션마다 `maxLifetime` 의 최대 2.5% 를 뺀 시각에 `MaxLifetimeTask` 를 예약해 동시 만료를 피한다. `keepaliveTime` 은 idle 커넥션만 잠시 예약(reserve)해 `isValid()` 로 확인하고 죽었으면 교체한다.

풀 크기 1, `maxLifetime` 30000, `keepaliveTime` 10000(최소값 미만)으로 두고 32초 뒤 다시 빌렸다(pool 로거 debug).

```text
11:40:34.656 [main] WARN com.zaxxer.hikari.HikariConfig - demo - keepaliveTime is less than 30000ms, disabling it.
effective keepaliveTime = 0
11:41:04.607 [demo connection closer] DEBUG com.zaxxer.hikari.pool.PoolBase - demo - Closing connection conn0: url=jdbc:h2:mem:life user=: (connection has passed maxLifetime)
before = conn0: url=jdbc:h2:mem:life user=, after = conn1: url=jdbc:h2:mem:life user=
```

잘못된 값은 예외가 아니라 WARN 후 보정되므로 기동 로그를 확인해야 한다. `maxLifetime` 을 짧게 잡을수록 끊긴 커넥션을 만날 위험은 줄지만 재연결이 잦아진다.

## 7. Spring Boot 설정과 @Transactional·OSIV 의 영향

Spring Boot 레퍼런스는 풀 구현으로 HikariCP 를 우선 선택하며, 구현별 설정은 `spring.datasource.hikari.*` 로 넘긴다고 설명한다. 아래 키 이름은 공식 프로퍼티 목록에서 확인했고, 값은 예시다.

```yaml
spring:
  datasource:
    hikari:
      pool-name: order-pool
      maximum-pool-size: 10
      connection-timeout: 3000
      max-lifetime: 1740000        # DB wait_timeout, 방화벽 한도보다 짧게
      keepalive-time: 120000
      leak-detection-threshold: 5000
  jpa:
    open-in-view: false
```

풀 크기만큼 중요한 것이 커넥션 점유 시간이다. `DataSourceTransactionManager.doBegin` 은 트랜잭션 시작 시점에 `getConnection()` 을 호출하고 완료 후 정리 단계에서 반납한다. 따라서 `@Transactional` 메서드 안에서 외부 API 를 호출하면 그 시간 내내 커넥션 하나가 묶인다. 또 Spring Boot 는 웹 애플리케이션에서 기본으로 `OpenEntityManagerInViewInterceptor` 를 등록하며(`spring.jpa.open-in-view` 기본값 true), 이는 EntityManager 를 요청 처리 전체에 바인딩한다. 레퍼런스는 이 패턴의 목적을 웹 뷰에서의 지연 로딩이라고 설명하므로, 끄면 필요한 연관을 트랜잭션 안에서 미리 읽어야 한다.

## 8. 풀 고갈 진단

고갈의 1차 신호는 5절의 `SQLTransientConnectionException` 이며, 메시지 끝의 `(total, active, idle, waiting)` 이 예외 시점의 풀 상태다. 같은 값은 `HikariPoolMXBean` 의 `getTotalConnections`, `getActiveConnections`, `getIdleConnections`, `getThreadsAwaitingConnection` 으로 상시 읽을 수 있다(1절의 `stats`).

누가 오래 쥐고 있는지는 `leakDetectionThreshold` 가 알려 준다. 2000ms 로 설정하고 3초간 쥐었다가 반납했다.

```text
11:40:32.592 [demo housekeeper] WARN com.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for conn0: url=jdbc:h2:mem:leak user= on thread main, stack trace follows
java.lang.Exception: Apparent connection leak detected
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:99)
	at HikariDemo.leak(HikariDemo.java:125)
	at HikariDemo.main(HikariDemo.java:154)
11:40:33.625 [main] INFO com.zaxxer.hikari.pool.ProxyLeakTask - Previously reported leaked connection conn0: url=jdbc:h2:mem:leak user= on thread main was returned to the pool (unleaked)
```

스택 트레이스는 커넥션을 빌린 지점을 가리킨다. 이 로그는 회수가 아닌 경고이고 느린 정상 쿼리도 걸리므로 임계값은 가장 긴 정상 트랜잭션보다 길게 잡는다. Micrometer 연동 시 메트릭 이름은 소스(`MicrometerMetricsTracker`)에 `hikaricp.connections`, `.active`, `.idle`, `.pending`, `.max`, `.min`, `.acquire`, `.usage`, `.creation`, `.timeout` 으로 정의돼 있고 `pool` 태그에 풀 이름이 붙는다. `pending` 이 MXBean 의 waiting 에 해당한다.

## 9. 운영 체크리스트

- 풀 크기는 코어 수 기반 공식에서 출발해 부하 테스트로 조정하고, 인스턴스 수를 곱한 합이 DB 최대 세션 수를 넘지 않는지 확인한다.
- 한 스레드가 커넥션을 중첩 획득하는 경로가 있으면 `Tn x (Cm - 1) + 1` 을 최소치로 삼거나 중첩을 제거한다.
- `maxLifetime` 은 DB 의 `wait_timeout` 과 방화벽 idle 한도보다 짧게 두고, `keepaliveTime` 은 쓰는 버전의 실제 기본값을 getter 로 확인한다.
- `connectionTimeout` 기본 30초가 상위 호출의 타임아웃보다 길지 않은지 확인한다.
- active, idle, pending, timeout 지표를 수집하고 `@Transactional` 범위 안의 외부 호출과 OSIV 설정을 점검한다.

검증 환경은 OpenJDK 21.0.12, HikariCP 5.1.0, H2 2.2.224, slf4j-simple 2.0.13 이며 Maven Central 의 jar 로 `javac`·`java` 를 직접 실행했다. 본문의 출력은 모두 이 실행 결과이고, 기본값 대조만 7.1.0 으로 한 번 더 실행했다. 내부 동작 설명은 5.1.0 sources jar 를 읽은 것이다. Spring Boot yml, `@Transactional`·OSIV 동작, MySQL 연동과 `wait_timeout` 에 의한 실제 끊김, Micrometer 메트릭 수집은 실행하지 않았고 공식 문서와 소스 확인에만 근거한다.

## 참고

- HikariCP README: https://github.com/brettwooldridge/HikariCP
- HikariCP 위키 About Pool Sizing: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
- ConcurrentBag 소스: https://github.com/brettwooldridge/HikariCP/blob/dev/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java
- Spring Boot 레퍼런스 SQL Databases: https://docs.spring.io/spring-boot/reference/data/sql.html
- Spring Boot 공통 프로퍼티 목록: https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- MySQL 8.4 Server System Variables: https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html
