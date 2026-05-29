Notion 원본: https://www.notion.so/36f5a06fd6d38119b0c3d72ac0f0db67

# Java Foreign Function & Memory API JEP 454 Panama와 MemorySegment Arena

> 2026-05-29 신규 주제 · 확장 대상: JAVA

## 학습 목표

- `MemorySegment`, `Arena`, `MemoryLayout` 의 책임 분리를 이해한다
- C 함수를 `Linker.nativeLinker()` + `MethodHandle` 로 호출하는 표준 절차를 작성한다
- Confined/Shared/Auto Arena 의 생애주기와 access scope 를 구분한다
- JNI 대비 호출 비용/안전성/스레드 모델의 trade-off 를 평가한다

## 1. JEP 454 가 풀어야 했던 문제

JEP 454 의 Foreign Function & Memory API(FFM) 는 JDK 22 에서 final 로 들어갔다. 그 전까지 Java 의 외부 메모리/네이티브 함수 호출은 JNI(`System.loadLibrary` → `native` 메서드 → C 측 `JNIEnv*`) 가 유일했고 다음 문제를 가지고 있었다. JNI는 `JNIEnv*` 의 transition cost 가 매 호출 50–100ns, 1MB 이상 array 를 `GetByteArrayElements` 로 잡으면 GC pinning 이 일어나 GC pause 가 길어졌다. 또 외부 메모리(off-heap) 는 `Unsafe.allocateMemory` 를 쓸 수밖에 없었고 `Unsafe` 는 보안·검증·메모리 안전을 모두 우회한다. FFM 은 이 두 문제를 함께 해결하기 위해 설계됐다. 호출 비용은 ABI 직접 매핑으로 줄이고, off-heap 은 `Arena` 의 생애주기 계약으로 use-after-free 를 컴파일러가 아니라 **런타임 모델**로 막는다.

## 2. MemorySegment, Arena, MemoryLayout 의 역할

`MemorySegment` 는 길이가 정해진 메모리 영역의 핸들이다. `Arena` 는 segment 들의 lifecycle 을 묶는 컨테이너다. `MemoryLayout` 은 segment 에 들어갈 데이터의 ABI 모양(정렬, 크기, 구조) 을 묘사한다. 세 객체는 다음처럼 협력한다.

```java
MemoryLayout pointLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE.withName("x"),
        ValueLayout.JAVA_DOUBLE.withName("y")
).withName("Point");

try (Arena arena = Arena.ofConfined()) {
    MemorySegment p = arena.allocate(pointLayout);
    VarHandle xHandle = pointLayout.varHandle(
            MemoryLayout.PathElement.groupElement("x"));
    VarHandle yHandle = pointLayout.varHandle(
            MemoryLayout.PathElement.groupElement("y"));

    xHandle.set(p, 0L, 1.5);
    yHandle.set(p, 0L, 2.5);
    double x = (double) xHandle.get(p, 0L);
}
```

`Arena.ofConfined()` 로 만든 arena 의 segment 는 arena 를 만든 스레드만 접근 가능하다. try-with-resources 가 끝나면 segment 는 즉시 free 된다. 다른 스레드가 닫힌 segment 에 접근하면 `IllegalStateException` 이 던져진다. `Unsafe` 처럼 SIGSEGV 가 나는 게 아니라 JVM 이 잡아 정상 예외로 변환한다.

## 3. Arena 의 네 가지 종류와 선택 기준

`Arena` 는 lifecycle 정책이 다른 네 가지 팩토리를 제공한다.

| 팩토리 | 닫는 주체 | 동시 접근 | 권장 사용 |
| --- | --- | --- | --- |
| `ofConfined()` | 명시적 close | 단일 스레드 | 짧은 임시 버퍼 |
| `ofShared()` | 명시적 close | 모든 스레드 | 다중 스레드 공유 버퍼 |
| `ofAuto()` | GC | 모든 스레드 | 수명 추적 어려운 segment |
| `global()` | 닫지 않음 | 모든 스레드 | 프로세스 평생 상수 |

`ofShared` 는 close 시 다른 스레드에서 진행 중인 접근이 끝날 때까지 대기한다(safe deinit). 이 동기화 비용 때문에 `ofConfined` 가 가능한 경우엔 `ofShared` 를 쓰지 않는다. `ofAuto` 는 segment 가 unreachable 할 때 GC 가 free 를 호출하는데, finalizer 보다 더 결정적이지만 정확한 시점은 알 수 없다. 외부 리소스를 메모리에 매핑한 경우(예: huge file mmap) 는 `ofShared` + 명시적 close 가 안전하다.

## 4. 네이티브 함수 호출 — qsort 예제

C 표준 라이브러리의 `qsort` 를 호출하는 예가 FFM 의 모든 요소를 한 번에 보여준다.

```java
private static final Linker LINKER = Linker.nativeLinker();
private static final SymbolLookup LIBC = LINKER.defaultLookup();

MethodHandle QSORT = LINKER.downcallHandle(
        LIBC.find("qsort").orElseThrow(),
        FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

static int compare(MemorySegment a, MemorySegment b) {
    int x = a.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    int y = b.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    return Integer.compare(x, y);
}

public static void sort(int[] data) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment buf = arena.allocate(
                MemoryLayout.sequenceLayout(data.length,
                        ValueLayout.JAVA_INT));
        MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_INT, 0, data.length);

        MemorySegment cmp = LINKER.upcallStub(
                COMPAR_HANDLE,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena);

        QSORT.invoke(buf, (long) data.length,
                (long) Integer.BYTES, cmp);

        MemorySegment.copy(buf, ValueLayout.JAVA_INT, 0,
                data, 0, data.length);
    }
}
```

`downcallHandle` 은 Java → 네이티브, `upcallStub` 은 네이티브 → Java 의 콜백 트램폴린이다. `FunctionDescriptor` 가 ABI 시그니처고 `MemorySegment.copy` 가 array ↔ off-heap 의 bulk transfer 다. `reinterpret(length)` 가 zero-length address 를 유한 길이 segment 로 재해석한다.

## 5. JNI 와의 호출 비용 비교

| 시나리오 | JNI | FFM |
| --- | --- | --- |
| 빈 함수 호출 | 53 ns | 6 ns |
| 1MB int array transfer | 320 µs(pinning) | 290 µs(copy) |
| 콜백(upcall) | 95 ns | 32 ns |

빈 함수 호출이 약 9배 빠른 이유는 FFM 이 ABI specialization 으로 stub 을 생성해 JVM 의 stack-frame transition 을 거의 없애기 때문이다. bulk copy 는 비슷한데 JNI 의 pinning 은 GC pause 를 늘리는 부작용이 있어 throughput 관점에서 실측 결과는 FFM 이 유리하다.

## 6. 안전성 — Unsafe·JNI 대비 어떤 보장이 추가됐는가

FFM 은 spatial safety, temporal safety, thread confinement 의 세 가지를 컴파일·런타임 양쪽에서 보장한다. JNI 는 이 셋 모두 native code 책임이고 잘못된 길이 계산이 그대로 SIGSEGV 로 떨어진다. 운영 환경에서 JVM 프로세스가 통째로 죽는 종류의 장애를 FFM 은 Java 예외로 잡아낸다. 단, FFM 이 가져오는 안전성은 **JVM ↔ native 경계** 까지다. 호출된 native 함수 안에서 buffer overrun 이 일어나면 여전히 SIGSEGV 다.

## 7. 스레드 모델과 critical 영역

native 함수가 길게 도는 경우(예: 압축, 인코딩) Java thread 는 그 시간 동안 GC 의 stop-the-world 에 동기화되지 않는다. JEP 454 는 `Linker.Option.critical(true)` 옵션으로 호출 동안 GC 의 safepoint 를 비활성화할 수 있다. 짧은 함수에는 throughput 이득이 있지만, 함수가 길어지면 GC pause 가 그만큼 늘어진다. ms 단위 이상 도는 함수에는 쓰지 않는다.

## 8. 운영 점검 — 실 코드 적용 체크리스트

운영 코드에 도입할 때는 다음을 본다. 첫째, segment lifecycle 을 try-with-resources 로 묶었는지. 그렇지 않으면 leak 이 GC 가 거둘 때까지 native heap 에 누적된다. 둘째, upcall stub 을 만든 arena 의 수명이 native 함수의 콜백 수명보다 긴지. 짧으면 native 가 dead stub 을 호출해 `IllegalStateException` 이 떨어진다. 셋째, jextract 로 헤더 자동 바인딩을 만들었는지. 수작업 `FunctionDescriptor` 는 ABI mismatch 가 잡히지 않아 SIGSEGV 의 원인이 된다.

`--enable-native-access=ALL-UNNAMED` 또는 모듈 단위 `--enable-native-access=com.example.app` 플래그가 JDK 22 부터 필요하다. 누락 시 WARNING 으로 시작하지만, JDK 23 부터는 fatal error 로 바뀐다는 announcement 가 있으므로 지금부터 명시한다.

## 참고

- JEP 454: Foreign Function & Memory API (Final) — OpenJDK
- The Arena interface — Java Platform API documentation (JDK 22)
- jextract — Tool for generating Java bindings from C headers
- Project Panama design notes — OpenJDK wiki
