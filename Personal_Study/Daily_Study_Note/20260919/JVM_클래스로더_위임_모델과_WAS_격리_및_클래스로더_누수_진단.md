Notion 원본: https://www.notion.so/3e05a06fd6d381ffbe74d756f1d6c9b4

# JVM 클래스로더 위임 모델과 WAS 격리 및 클래스로더 누수 진단

> 2026-09-19 신규 주제 · 확장 대상: JAVA, JBoss, Servlet

## 학습 목표

- 부모 위임(parent delegation) 알고리즘과 JDK 9 이후 플랫폼/앱 로더 재편을 코드로 추적한다
- WAS가 위임 순서를 뒤집는(child-first) 이유와 그때 생기는 `LinkageError` 계열 오류를 구분한다
- 클래스로더 누수의 네 가지 대표 경로(ThreadLocal, JDBC Driver, 스레드 컨텍스트 로더, 정적 캐시)를 힙덤프에서 식별한다
- `jcmd`, MAT, `-Xlog:class+load`로 로더 그래프를 잘라내는 실측 절차를 수행한다

## 1. 클래스 유일성의 정의 — 로더는 이름의 일부다

JVM에서 클래스의 런타임 식별자는 FQCN 단독이 아니라 `(FQCN, defining class loader)` 쌍이다. JVMS §5.3에서 이를 "런타임 패키지(run-time package)"와 함께 정의한다. 같은 바이트코드 `com.acme.Money`를 두 로더가 각각 정의하면 JVM 안에는 서로 다른 두 타입이 존재하고, 한쪽 인스턴스를 다른 쪽 타입으로 캐스팅하면 `ClassCastException`이 난다. 메시지에 같은 이름이 두 번 찍히는 그 현상이다.

```
java.lang.ClassCastException: class com.acme.Money cannot be cast to class com.acme.Money
  (com.acme.Money is in unnamed module of loader 'app';
   com.acme.Money is in unnamed module of loader org.jboss.modules.ModuleClassLoader @6d06d69c)
```

JDK 9부터 예외 메시지에 모듈과 로더 이름이 함께 찍히도록 개선되어(JEP 261 모듈 시스템 도입 시 진단 메시지 보강) 이 진단이 훨씬 쉬워졌다. 괄호 안의 두 로더 이름이 다르면 무조건 로더 격리 문제이지 버전 충돌이 아니다.

`defining loader`와 `initiating loader`를 구분해야 한다. `A.class.getClassLoader()`가 돌려주는 것은 정의 로더다. 위임으로 부모가 실제 정의했다면 자식은 개시 로더일 뿐이고 `getClassLoader()`는 부모를 가리킨다. 이 차이가 "분명히 내 WAR 안에 jar를 넣었는데 왜 서버의 구버전이 로드되는가"의 원인이다.

## 2. 부모 위임 알고리즘과 JDK 9 이후의 로더 계층

`ClassLoader.loadClass(String, boolean)`의 기본 구현이 위임 규약 그 자체다.

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);   // 1. 이미 이 로더가 개시한 적이 있는가
        if (c == null) {
            try {
                if (parent != null) {
                    c = parent.loadClass(name, false);   // 2. 부모에게 먼저
                } else {
                    c = findBootstrapClassOrNull(name);  // 3. 부트스트랩
                }
            } catch (ClassNotFoundException e) {
                // 부모가 못 찾은 것은 정상 흐름
            }
            if (c == null) {
                c = findClass(name);   // 4. 그래야 내가 찾는다
            }
        }
        if (resolve) { resolveClass(c); }
        return c;
    }
}
```

JDK 8까지의 3계층(Bootstrap → Ext → App)은 JDK 9에서 Bootstrap → Platform → Application으로 바뀜다. 핵심 차이는 두 가지다. 첫째, Platform 로더(`ClassLoader.getPlatformClassLoader()`)는 더 이상 `URLClassLoader`가 아니다. JDK 8에서 흔히 쓰던 `((URLClassLoader) ClassLoader.getSystemClassLoader()).addURL(...)` 리플렉션 해킹이 JDK 9 이후 `ClassCastException`으로 죽는 이유가 이것이다. 둘째, `jdk.internal.loader.ClassLoaders$AppClassLoader`는 이름이 `"app"`으로 등록되어 있고, 플랫폼 로더가 정의하는 클래스는 대부분 named module에 속한다.

`getClassLoadingLock`은 JDK 7의 병렬 로딩 지원(`registerAsParallelCapable`)과 쌍을 이룬다. 병렬 가능으로 등록하지 않은 커스텀 로더는 로더 객체 자체를 락으로 잡으므로, 멀티스레드 부팅 시 두 스레드가 서로 다른 클래스를 각각의 로더에서 로드하며 교차 위임하면 데드락이 난다. 커스텀 로더를 만든다면 정적 초기화 블록에 다음 한 줄은 사실상 필수다.

```java
public final class PluginClassLoader extends URLClassLoader {
    static { ClassLoader.registerAsParallelCapable(); }
    // ...
}
```

## 3. child-first 위임 — WAS가 규약을 뒤집는 지점

Servlet 명세(Servlet 6.0 §10.7.2)는 웹 애플리케이션 로더가 `WEB-INF/classes`와 `WEB-INF/lib`를 우선 탐색하도록 "권장"하며, 단 `java.*` 같은 일부 패키지는 반드시 위임하라고 규정한다. Tomcat의 `WebappClassLoaderBase.loadClass`가 이 규칙을 그대로 구현한다. 순서는 대략 다음과 같다.

1. 로컬 캐시(`findLoadedClass0`) → JVM 캐시(`findLoadedClass`)
2. `JavaseLoader`(부트스트랩 계열)에 위임 — `java.*`를 가로채지 못하도록 방어
3. `delegate` 플래그가 true면 부모 먼저, false면 `findClass`로 웹앱 자신 먼저
4. 실패 시 반대쪽

JBoss/WildFly는 아예 다른 모델을 택했다. `jboss-modules`는 계층형 위임 대신 **모듈 그래프**를 쓴다. 각 모듈이 `module.xml`이나 `jboss-deployment-structure.xml`로 의존을 명시하고, 명시하지 않은 모듈의 클래스는 보이지 않는다. OSGi와 유사한 발상이다.

```xml
<jboss-deployment-structure>
  <deployment>
    <exclusions>
      <!-- 서버가 밀어 넣는 구버전 Jackson을 차단하고 WAR 안의 것을 쓴다 -->
      <module name="com.fasterxml.jackson.core.jackson-databind"/>
    </exclusions>
    <dependencies>
      <module name="org.apache.commons.lang3" export="true"/>
    </dependencies>
  </deployment>
</jboss-deployment-structure>
```

이 모델의 장점은 "왜 이 클래스가 보이는가"를 그래프로 설명할 수 있다는 것이고, 단점은 전이 의존이 자동으로 따라오지 않아 `NoClassDefFoundError`가 배포 시점에 몰린다는 것이다.

Spring Boot의 실행 가능 fat jar는 또 다른 변형이다. `LaunchedClassLoader`(3.2 이후 `LaunchedClassLoader`/`JarLauncher` 구조 재편)는 중첩 jar(`BOOT-INF/lib/*.jar`)를 압축 해제 없이 읽기 위해 커스텀 `URLStreamHandler`를 등록한다. 표준 `java -jar`만으로 동작해야 하므로 위임은 부모 우선을 유지하되, `BOOT-INF/classes`가 앱 로더의 클래스패스가 아니라 이 로더의 탐색 경로에 들어간다.

## 4. LinkageError 삼형제 구분법

로더 문제는 세 예외로 갈라지는데, 원인이 완전히 다르다.

| 예외 | 발생 시점 | 전형적 원인 | 1차 조치 |
|---|---|---|---|
| `ClassNotFoundException` | `loadClass`/`Class.forName` 호출 시 | 경로에 클래스가 아예 없음. checked 예외 | 클래스패스·모듈 의존 확인 |
| `NoClassDefFoundError` | 이미 링크된 코드가 참조할 때 | 컴파일 시엔 있었으나 런타임에 없음, 또는 **정적 초기화 실패한 클래스 재참조** | 최초 `ExceptionInInitializerError` 로그를 찾아라 |
| `LinkageError: loader constraint violation` | 메서드 시그니처 검증 시 | 두 로더가 같은 이름의 다른 타입을 정의 | 중복 jar 제거 또는 위임 조정 |

세 번째가 가장 헷갈린다. 메시지 형태는 이렇다.

```
java.lang.LinkageError: loader constraint violation:
  when resolving method 'void com.acme.Repo.save(com.acme.Money)'
  the class loader 'app' of the current class, com.acme.Service,
  and the class loader org.jboss.modules.ModuleClassLoader @1f2a3b
  for the method's defining class, com.acme.Repo,
  have different Class objects for the type com.acme.Money used in the signature
```

JVM이 강제하는 "로더 제약(loader constraint)"은 시그니처에 등장하는 모든 타입이 호출자와 피호출자 양쪽에서 동일한 `Class` 객체로 해석되어야 한다는 규칙이다(JVMS §5.3.4). 중복 jar가 두 로더에 각각 있을 때 터진다.

`NoClassDefFoundError`의 두 번째 원인은 특히 실무에서 시간을 잡아먹는다. 정적 초기화가 한 번 실패한 클래스는 JVM이 "erroneous" 상태로 표시하고, 이후 모든 참조에 대해 원인 없이 `NoClassDefFoundError: Could not initialize class X`만 던진다. 로그를 시간 역순으로 올라가 최초의 `ExceptionInInitializerError` 스택을 찾아야 진짜 원인(대개 설정 파일 누락이나 `static { }` 안의 NPE)이 나온다.

## 5. 스레드 컨텍스트 클래스로더(TCCL) — 위임 모델의 탈출구

부모 위임은 "부모가 자식의 클래스를 볼 수 없다"는 단방향 제약을 만든다. 그런데 JDBC, JAXP, JNDI 같은 SPI는 **부트스트랩/플랫폼 로더가 정의한 인터페이스**가 **앱 로더가 정의한 구현체**를 인스턴스화해야 한다. 이 역방향을 뚫기 위해 `Thread.currentThread().getContextClassLoader()`가 있다.

```java
// ServiceLoader의 전형적 사용 — 인터페이스는 상위 로더, 구현체는 TCCL이 찾는다
ServiceLoader<Driver> loaders =
        ServiceLoader.load(Driver.class, Thread.currentThread().getContextClassLoader());
```

WAS는 서블릿 호출 직전에 TCCL을 해당 웹앱의 로더로 바꾸고, 호출이 끝나면 되돌린다. 문제는 **스레드 풀**이다. 애플리케이션 코드가 자신이 만든 스레드에 TCCL을 세팅했는데 그 스레드가 재배포 후에도 살아 있으면, 죽은 웹앱 로더가 스레드 객체를 통해 GC Root에 매달린다. 라이브러리를 만든다면 TCCL을 바꾼 뒤 반드시 finally로 복원하는 관용구를 쓴다.

```java
ClassLoader saved = Thread.currentThread().getContextClassLoader();
try {
    Thread.currentThread().setContextClassLoader(pluginLoader);
    return plugin.execute(request);
} finally {
    Thread.currentThread().setContextClassLoader(saved);
}
```

## 6. 클래스로더 누수 — 왜 재배포 몇 번에 Metaspace가 터지는가

로더가 수집되려면 "그 로더 자신, 그 로더가 정의한 모든 클래스, 그 클래스들의 모든 인스턴스"가 전부 도달 불가여야 한다(JLS §12.7). 하나라도 외부에서 붙잡으면 전체가 남는다. 웹앱 로더 하나가 보통 수십 MB의 Metaspace + 클래스 메타데이터를 들고 있으므로 재배포 5~10회면 `OutOfMemoryError: Metaspace`가 난다.

대표 경로 네 가지.

**(a) ThreadLocal.** 값이 웹앱 클래스의 인스턴스이고, 그 ThreadLocal을 WAS의 공용 스레드가 들고 있는 경우. `ThreadLocalMap.Entry`의 키는 약참조지만 **값은 강참조**라서, 키가 수집되어도 스레드가 살아 있는 한 값은 남는다(`expungeStaleEntries`가 돌기 전까지). Tomcat은 `WebappClassLoaderBase.checkThreadLocalsForLeaks()`로 이를 탐지해 경고 로그를 남긴다.

**(b) JDBC Driver.** `DriverManager`는 부트스트랩/플랫폼 로더 소속이고 등록된 Driver 인스턴스를 정적 리스트로 들고 있다. 웹앱 jar에 들어 있던 드라이버를 해제하지 않으면 영구히 남는다. 해법은 `ServletContextListener`에서 명시적 해제.

```java
@WebListener
public class DriverCleanupListener implements ServletContextListener {
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        ClassLoader webappLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == webappLoader) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException e) {
                    event.getServletContext().log("deregister failed", e);
                }
            }
        }
    }
}
```

**(c) 살아남은 스레드.** 앱이 띄운 타이머/스케줄러/커넥션 풀 청소 스레드를 `contextDestroyed`에서 종료하지 않으면, 스레드의 `Runnable`이 웹앱 클래스이므로 로더가 붙잡힌다. HikariCP, Log4j2, Quartz 모두 명시적 shutdown API를 제공한다.

**(d) 상위 로더의 정적 캐시.** `java.beans.Introspector`의 캐시, `ResourceBundle` 캐시, `javax.security.auth.login.Configuration` 등이 웹앱 클래스를 키나 값으로 들고 있는 경우다. Tomcat은 `JreMemoryLeakPreventionListener`로 위험한 캐시를 미리 워밍업해 상위 로더 소속으로 만들어 버린다.

## 7. 힙덤프로 누수 로더 잘라내기 — 실측 절차

먼저 로더가 몇 개 살아 있는지 센다. `jcmd`의 `VM.classloaders`(JDK 11+)가 계층을 트리로 보여준다.

```bash
jcmd <pid> VM.classloader_stats        # 로더별 클래스 수 / Metaspace 사용량
jcmd <pid> VM.classloaders show-classes=false
jcmd <pid> GC.class_stats              # -XX:+UnlockDiagnosticVMOptions 필요
```

`VM.classloader_stats` 출력에서 같은 `WebappClassLoader` 유형이 배포 횟수만큼 반복되면 확정이다. 다음으로 힙덤프를 맜다.

```bash
jcmd <pid> GC.heap_dump /tmp/leak.hprof
```

Eclipse MAT에서 OQL로 대상 로더를 고르고, "Path to GC Roots → exclude weak/soft references"를 본다. 약참조를 제외해야 진짜 붙잡는 강참조 체인만 남는다.

```sql
SELECT * FROM org.apache.catalina.loader.ParallelWebappClassLoader c
WHERE c.started = false
```

체인이 `java.lang.Thread` → `threadLocals` → `ThreadLocalMap$Entry` → `value`로 끝나면 (a), `java.sql.DriverManager` → `registeredDrivers`로 끝나면 (b)다.

로딩 자체를 추적할 땐 통합 로깅이 가장 싸다.

```bash
# 어떤 로더가 무엇을 정의했는지
java -Xlog:class+load=info:file=class-load.log:time,tags -jar app.jar
# 로더 제약 위반의 배후를 볼 때
java -Xlog:class+loader+constraints=debug -jar app.jar
# 언로딩이 실제로 일어나는지
java -Xlog:class+unload=info -jar app.jar
```

`class+unload` 로그가 재배포 후에도 비어 있으면 누수가 확정이다. 참고로 클래스 언로딩은 풀 GC 사이클에서 일어나므로, 확인 전에 `jcmd <pid> GC.run`을 한 번 돌려 줄 것. ZGC/Shenandoah는 동시 언로딩을 하지만 기본 주기가 길어 관찰까지 시간이 걸린다.

## 8. 설계 지침 — 격리가 필요할 때와 아닐 때

플러그인 아키텍처를 직접 만든다면 세 가지를 먼저 정한다.

**공유 API 경계.** 호스트와 플러그인이 주고받는 타입은 반드시 부모 로더가 정의해야 한다. 이를 어기면 §4의 loader constraint violation이 난다. 실무에서는 `plugin-api` 모듈을 별도 jar로 뽑아 호스트 클래스패스에 두고, 플러그인 jar에는 `provided` 스코프로만 둔다.

**언로딩 계약.** 플러그인을 내릴 때 호출할 `close()` 훅을 API에 넣고, 호스트가 로더에 대한 참조를 끕기 전에 반드시 호출한다. `URLClassLoader.close()`는 열린 jar 파일 핸들을 닫아 Windows에서 파일 잠금이 풀리게 하는 효과도 있다(JDK 7+).

**위임 방향 명시.** child-first는 기본값으로 두지 말고 패키지 프리픽스 화이트리스트로 제한한다.

```java
@Override
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            boolean selfFirst = name.startsWith("com.acme.plugin.")
                    && !name.startsWith("com.acme.plugin.api.");   // API는 항상 부모
            if (selfFirst) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, false);
                }
            } else {
                loaded = super.loadClass(name, false);
            }
        }
        if (resolve) { resolveClass(loaded); }
        return loaded;
    }
}
```

반대로, 격리가 필요 없는 경우가 훨씬 많다는 점도 중요하다. 컨테이너 이미지 하나에 애플리케이션 하나를 담는 요즘 배포 방식에서는 WAS 다중 배포 자체가 사라졌고, 그러면 위임 모델을 건드릴 이유도 없다. 의존 충돌은 로더가 아니라 Gradle의 `dependencyInsight`나 Maven Enforcer의 `banDuplicateClasses`로 빌드 시점에 잡는 편이 훨씬 싼다.

```bash
./gradlew dependencyInsight --dependency jackson-databind --configuration runtimeClasspath
```

셰이딩(Shadow/Shade 플러그인의 `relocate`)은 세 번째 선택지다. 패키지명을 물리적으로 바꿔 버리므로 로더 격리 없이도 버전 공존이 가능하지만, 리플렉션으로 클래스명을 문자열로 다루는 라이브러리(Spring, Jackson의 일부 기능)에서는 조용히 깨진다. 적용 후 통합 테스트로 반드시 검증해야 한다.

## 참고

- JVM Specification SE 21, §5.3 Creation and Loading / §5.3.4 Loading Constraints
- JDK API: `java.lang.ClassLoader`, `java.lang.ModuleLayer`
- JEP 261: Module System (로더 계층 재편 및 진단 메시지)
- Apache Tomcat 11 Documentation — Class Loader HOW-TO
- Jakarta Servlet Specification 6.0, §10.7 Web Application Class Loader
- WildFly Documentation — Class Loading in WildFly
- Eclipse MAT Documentation — Finding Memory Leaks / Object Query Language
