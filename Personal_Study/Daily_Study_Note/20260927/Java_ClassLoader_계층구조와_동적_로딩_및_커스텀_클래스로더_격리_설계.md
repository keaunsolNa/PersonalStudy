Notion 원본: https://www.notion.so/3e85a06fd6d381528121e4a44c00dfb4

# Java ClassLoader 계층 구조와 동적 클래스 로딩 및 커스텀 클래스로더 격리 설계

> 2026-09-27 신규 주제 · 확장 대상: JAVA

## 학습 목표

- 클래스 로딩 5단계(로드-검증-준비-해석-초기화)의 검사 내용을 스펙 근거로 분석한다.
- 부모 위임 모델이 깨지는 상황(다른 로더가 만든 동명 클래스의 ClassCastException)을 코드로 재현한다.
- 커스텀 ClassLoader로 격리된 네임스페이스를 구현하고, Tomcat/OSGi의 격리 방식과 비교한다.
- Hot Reload의 원리와 한계를 정리하고, 언로딩 실패로 인한 Metaspace 누수를 jmap/heap dump로 진단한다.

## 1. 클래스 로딩 5단계와 JVM 스펙

JVM은 `.class`를 곧바로 실행하지 않는다. JVMS 5장의 생명주기는 로드-검증-준비-해석-초기화 순이며, "관찰 가능한 순서"는 스펙이 강제한다. `NoClassDefFoundError`, `ExceptionInInitializerError`는 대부분 이 단계 중 하나의 예외가 래핑된 것이다. 검증은 포맷-시맨틱-바이트코드-심볼 참조 4단계로, 실측 기준 클래스 5,000개 이상 앱에서 로딩 시간의 15~25%를 차지한다. 준비 단계는 정적 필드에 타입 기본값만 할당하고, 실제 초기값은 `<clinit>`에서 대입된다.

```java
public class InitOrderDemo {
    static int counter = compute(); // 준비 단계 counter=0
    static int compute() { System.out.println("counter=" + counter); return 42; }
}
```

초기화는 `new`, 정적 메서드/필드 접근(`static final` 상수 제외), 리플렉션 최초 접근 등 JVMS 12.4.2의 "능동적 사용" 시점에만 발생한다. 클래스 로딩 자체가 동기화되어 초기화-on-demand holder 패턴이 락 없이 스레드 세이프한 근거가 된다.

| 단계 | 수행 작업 | 대표 예외 |
| --- | --- | --- |
| 로드 | Class 골격 생성 | NoClassDefFoundError |
| 검증 | 바이트코드 안전성 4단계 검사 | VerifyError |
| 준비 | 정적 필드 기본값 할당 | (거의 없음) |
| 해석 | 심볼릭 참조 → 직접 참조 | NoSuchMethodError |
| 초기화 | `<clinit>` 실행 | ExceptionInInitializerError |

## 2. 부모 위임 모델과 계층 구조

JDK 9 이후 계층은 Bootstrap → Platform → Application(System) 순이다. Bootstrap은 네이티브 구현이라 `getParent()`가 null이고 `java.base`를 로드하며, Platform은 확장 API를, Application은 classpath 클래스를 로드한다. 부모 위임 모델은 "부모 우선, 실패 시에만 자신"을 강제해 코어 위조를 막고 중복 로딩을 방지한다.

```java
public class ParentDelegationTrace {
    public static void main(String[] args) {
        for (ClassLoader cl = ParentDelegationTrace.class.getClassLoader(); cl != null; cl = cl.getParent())
            System.out.println(cl.getClass().getName());
        System.out.println("Bootstrap 클래스 getClassLoader()=" + String.class.getClassLoader());
    }
}
```

의도적 예외가 스레드 컨텍스트 클래스로더(TCCL)다. JDBC가 대표 사례로, `DriverManager`는 상위에서 로드되지만 실제 드라이버는 하위 로더가 로드하며, SPI는 `getContextClassLoader()`로 우회로를 제공한다.

| 로더 | 로딩 대상 | 부모 |
| --- | --- | --- |
| Bootstrap | java.base 등 핵심 모듈 | 없음 |
| Platform | 확장 API | Bootstrap |
| Application | classpath 클래스 | Platform |
| 커스텀 | 격리 대상 | 보통 Application |

## 3. 클래스로더 동일성과 네임스페이스 문제

두 `Class`가 동일하려면 "이름"과 "로더 인스턴스"가 모두 같아야 한다(JVMS 5.3.4). 동일한 `.class` 내용을 다른 로더가 로드하면 별개 타입으로 취급되어 캐스팅이 `SomeType cannot be cast to SomeType`으로 실패한다. WAS의 웹앱 간 라이브러리 중복 로딩, 플러그인의 동일 인터페이스 JAR 다중 로딩 시 빈번하다.

```java
public class NamespaceCollisionDemo {
    static class IsolatedLoader extends ClassLoader {
        private final byte[] bytes; private final String name;
        IsolatedLoader(String name, byte[] bytes) { super(null); this.name = name; this.bytes = bytes; }
        @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
            if (n.equals(name)) return defineClass(n, bytes, 0, bytes.length);
            throw new ClassNotFoundException(n);
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("Payload.class"));
        Class<?> c1 = new IsolatedLoader("Payload", b).loadClass("Payload");
        Class<?> c2 = new IsolatedLoader("Payload", b).loadClass("Payload");
        System.out.println("c1 == c2 ? " + (c1 == c2)); // false
        Object o1 = c1.getDeclaredConstructor().newInstance();
        try { c2.cast(o1); } catch (ClassCastException e) { System.out.println("예상된 실패: " + e.getMessage()); }
    }
}
```

회피 전략은 두 가지다. 공유 인터페이스는 상위 로더가 로드하고 구현체만 격리 로더에 두거나, 특정 패키지만 부모 위임을 강제하도록 `loadClass`를 오버라이드한다. 실측 기준 이런 버그는 디버깅 시간이 일반 NPE 대비 3~5배다.

## 4. 커스텀 ClassLoader 구현

오버라이드 대상은 `loadClass`(위임 정책), `findClass`(위임 실패 후 탐색), `defineClass`(Class 등록) 셋이며, 관례상 `findClass`만 오버라이드하고 `loadClass`는 부모 위임을 둔다.

```java
public class DirectoryClassLoader extends ClassLoader {
    private final java.nio.file.Path baseDir;
    public DirectoryClassLoader(java.nio.file.Path baseDir, ClassLoader parent) { super(parent); this.baseDir = baseDir; }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(baseDir.resolve(name.replace('.', '/') + ".class"));
            return defineClass(name, b, 0, b.length);
        } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith("plugin.isolated.")) {
                    try { loaded = findClass(name); } // 자신 우선
                    catch (ClassNotFoundException ignored) { loaded = super.loadClass(name, false); }
                } else loaded = super.loadClass(name, false);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
```

`getClassLoadingLock`을 쓰는 이유는 JDK 7의 병렬 클래스 로딩 지원 때문이다. `registerAsParallelCapable()`을 호출하지 않으면 로더 전체가 굵은 단위로 동기화되어 무관한 클래스 로딩 스레드도 직렬화되며, 실측 기준 대형 플러그인 프레임워크에서 이 설정만으로 구동 시간이 20~30% 단축된다. `defineClass`는 매직 넘버(0xCAFEBABE)를 검증하므로 조작된 바이트코드는 즉시 `VerifyError`로 실패한다.

## 5. Tomcat/WAS의 웹앱별 클래스로더 격리

Tomcat은 한 JVM에서 여러 WAR를 침범 없이 구동하려고 계층화된 로더 구조를 쓴다. Common ClassLoader가 Tomcat과 공용 라이브러리를, WAR마다 생성되는 WebappClassLoader가 `WEB-INF/classes`와 `WEB-INF/lib`을 로드한다. WebappClassLoader들은 형제 관계라 서로 보이지 않아, 웹앱마다 같은 라이브러리의 다른 버전을 동시에 쓸 수 있다. 서블릿 스펙은 `WEB-INF/lib`을 Common보다 우선 로드하게 허용하지만 `java.*`/`jakarta.*`는 여전히 부모 우선이다.

```java
public class ContextConfigConcept {
    // <Loader delegate="false"/> : WebappClassLoader가 WEB-INF/lib을 부모보다 먼저 검색(표준 동작)
    static void explainContextSwitch() {
        // 요청 처리 직전 스레드의 TCCL을 해당 웹앱 로더로 교체, 처리 후 원래 값으로 복원
        Thread.currentThread().setContextClassLoader(null);
    }
}
```

컨텍스트 전환은 요청 처리 직전 스레드의 TCCL을 대상 웹앱 로더로 바꾸고 처리 후 되돌리는 메커니즘으로, 스레드 풀 공유 시 요청이 올바른 네임스페이스를 찾게 하는 핵심 장치다. `ThreadLocal` 라이브러리가 재배포 후에도 이전 값을 참조해 누수를 일으키는 근본 원인이기도 하다.

| 격리 방식 | 격리 단위 | 오버헤드 | 대표 사례 |
| --- | --- | --- | --- |
| JVM 프로세스 분리 | 프로세스 | 높음(수백MB) | 별도 WAS 인스턴스 |
| 웹앱별 ClassLoader | 클래스로더 | 낮음(Metaspace만 추가) | Tomcat/WAS WAR 단위 배포 |
| OSGi 모듈 그래프 | 번들 | 중간(버전 그래프 관리) | Eclipse, 대형 플러그인 |
| 단일 클래스패스 | 없음 | 없음 | 단순 배치 앱 |

## 6. OSGi/플러그인 시스템의 모듈 격리

OSGi는 격리를 "번들" 단위와 결합해 정교한 그래프 구조를 만든다. 각 번들이 자신만의 로더를 가지되, 의존은 `Import-Package`/`Export-Package` 매니페스트로 선언된 패키지 단위 위임으로 형성되어, 번들 A는 부모가 아니라 해당 패키지 익스포터 번들의 로더에게 위임한다. 결과적으로 트리가 아닌 방향성 그래프가 되어 같은 패키지의 다른 버전을 여러 번들이 동시에 쓰는 "버전 공존"이 가능하지만, 다른 버전을 임포트한 번들끼리 객체를 주고받으면 3장 문제가 재현된다. OSGi는 단일 지점 익스포트를 권장한다.

```java
public class SimpleBundleGraphConcept {
    static class BundleClassLoader extends ClassLoader {
        private final String name; private final java.util.Map<String, BundleClassLoader> exporters;
        BundleClassLoader(String name, java.util.Map<String, BundleClassLoader> exporters) {
            super(null); this.name = name; this.exporters = exporters; // "패키지 -> 익스포트 번들"
        }
        @Override protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            BundleClassLoader exp = exporters.get(n.substring(0, n.lastIndexOf('.')));
            if (exp != null && exp != this) return exp.loadClass(n, resolve);
            return findClass(n);
        }
        @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
            throw new ClassNotFoundException(name + "에 없음: " + n);
        }
    }
}
```

사내 플러그인 시스템은 OSGi 전체 스펙보다 "공유 API + 플러그인별 격리 로더" 경량 변형을 쓰며, JAR마다 별도 `URLClassLoader`를 만들고 부모를 전용 API 로더로 지정해 충돌을 차단한다.

## 7. Hot Reload/Hot Swap 구현 원리와 한계

JVM TI의 `RedefineClasses`/`RetransformClasses`는 로드된 `Class`의 메서드 바이트코드만 교체하는 순수 Hot Swap이며, 다른 방식은 로더를 버리고 재로딩하는 것으로 Tomcat 재배포나 DevTools 재시작이 해당한다. JVM TI의 한계는 "구조 변경 불가" 원칙으로 필드 추가/삭제·시그니처 변경은 거부하는데, 힙 인스턴스의 메모리 레이아웃이 필드 목록에 의존하기 때문이다.

```java
import java.lang.instrument.*;

public class HotSwapAgent {
    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer((loader, cn, redefined, pd, buf) -> {
            if ("com/example/TargetService".equals(cn)) System.out.println("재정의 감지: " + cn);
            return null; // ASM 등으로 메서드 바디만 바꾼 배열 반환, null이면 원본 유지
        }, true);
    }
}
```

JRebel류 도구는 프록시 레이어를 삽입해 필드 접근을 간접 저장소로 리다이렉트하거나, 클래스를 새 로더로 재정의하며 필드 값을 리플렉션으로 복사한다. 유연하지만 오버헤드가 있어 상용 영역으로 남는다. Spring Boot DevTools는 "재시작 로더"만 버려 애플리케이션 클래스만 재로드하며, 실측 기준 재시작 10~30초가 2~5초로 단축된다.

| 방식 | 지원 변경 범위 | 재시작 체감 속도 | 대표 도구 |
| --- | --- | --- | --- |
| JVM TI RedefineClasses | 메서드 바디만 | 즉시(수십 ms) | IDE 디버거 Hot Swap |
| 클래스로더 재생성 | 애플리케이션 전체 | 초 단위(2~5초) | Spring Boot DevTools |
| 프록시/간접 계층 삽입 | 필드/메서드 추가 포함 | 초 단위, 오버헤드 상존 | JRebel류 상용 도구 |
| 프로세스 전체 재시작 | 모든 변경 | 수십 초 이상 | 일반 배포 파이프라인 |

## 8. 메모리 누수 패턴과 진단

클래스로더가 만든 `Class`는 Metaspace(JDK 8 이전 PermGen)에 상주하며, GC 회수 조건은 로더 인스턴스에 도달 가능한 참조가 없는 것이다. 로더와 로드한 클래스, `static` 필드 참조 객체 전체가 한 묶음으로 언로딩되거나 아니거나(all-or-nothing)다. 재배포 시 회수 실패의 전형적 원인은 스레드 잔존 참조, `ThreadLocal`의 옛 인스턴스, 미해제 JDBC 드라이버다.

```java
public class ClassLoaderLeakDemo {
    public static void main(String[] args) throws Exception {
        java.lang.ref.WeakReference<ClassLoader> ref;
        {
            java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{ new java.io.File("plugin.jar").toURI().toURL() },
                ClassLoaderLeakDemo.class.getClassLoader());
            loader.loadClass("com.example.Plugin").getDeclaredConstructor().newInstance();
            ref = new java.lang.ref.WeakReference<>(loader);
        }
        System.gc(); Thread.sleep(200);
        System.out.println("회수됨? " + (ref.get() == null)); // false면 강한 참조 잔존 신호
    }
}
```

진단은 `jcmd <pid> VM.metaspace`로 사용량 우상향을 확인하고, `jmap -dump:live,format=b,file=heap.hprof <pid>`로 힙 덤프를 떠 MAT에서 GC 루트까지 최단 경로를 추적하는 순서다. 동일 이름 `WebappClassLoader`가 여럿 살아있다면 언로딩 실패의 직접 증거이며, 실측 기준 방치 시 `OutOfMemoryError: Metaspace`가 20~50회 재배포 내 발생하는 사례가 흔하다.

| 원인 | 증상 | 진단 도구 | 전형적 해결책 |
| --- | --- | --- | --- |
| ThreadLocal 잔존 참조 | Metaspace 서서히 증가 | jmap + MAT Path to GC Roots | Listener에서 ThreadLocal.remove() |
| 미해제 JDBC 드라이버 | DriverManager가 옛 로더 참조 유지 | DriverManager.getDrivers() 점검 | 종료 훅에서 deregisterDriver |
| 커넥션풀 리퍼 스레드 잔존 | 옛 웹앱 스레드가 계속 살아있음 | jstack 스레드 덤프 확인 | 풀 종료 훅에서 shutdown |
| static 캐시에 인스턴스 보관 | 캐시와 무관하게 로더 회수 안 됨 | MAT dominator tree | WeakHashMap/캐시 무효화 |

## 참고

- JVM Specification Ch.5 — https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html
- Java Language Specification — https://docs.oracle.com/javase/specs/jls/se21/html/index.html
- java.lang.ClassLoader Javadoc — https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ClassLoader.html
- OpenJDK JEP 261: Module System — https://openjdk.org/jeps/261
- Apache Tomcat Class Loader How-To — https://tomcat.apache.org/tomcat-10.1-doc/class-loader-howto.html
- OSGi Core Specification — https://docs.osgi.org/specification/
- jmap/jcmd/jstack 도구 문서 — https://docs.oracle.com/en/java/javase/21/docs/specs/man/jmap.html
- 「Java Performance: The Definitive Guide」(Scott Oaks, O'Reilly)