Notion 원본: https://www.notion.so/36e5a06fd6d38109a784e27c9b21cbb9

# Java JIT C2 Tiered Compilation Escape Analysis와 OSR

> 2026-05-28 신규 주제 · 확장 대상: JAVA — HotSpot JIT 컴파일러 내부 동작

## 학습 목표

- HotSpot 5단계 tiered compilation 흐름 (interpreter → C1 → C2) 을 단계별로 짚는다.
- Escape Analysis 가 어떤 조건에서 스칼라 치환 / 스택 할당 / lock elision 을 트리거하는지 식별.
- On-Stack Replacement (OSR) 이 긴 루프 메서드에서 어떻게 동작하는지 정리.
- JFR / PrintCompilation / JITWatch 로 컴파일 결과 관찰.

## 1. Tiered Compilation 전반

| Tier | 컴파일러 | 특징 |
|---|---|---|
| 0 | Interpreter | 첫 호출, profiling |
| 1 | C1 | profiling 없음 |
| 2 | C1 | 약한 profiling |
| 3 | C1 | full profiling |
| 4 | C2 | profiling 기반 최적화 |

기본 경로: Interpreter → Tier 3 → Tier 4. CompileThreshold, Tier3InvocationThreshold 카운터가 전환 결정.

```
-XX:+PrintCompilation 출력
    93   1   3   java.lang.Object::<init> (1 bytes)
   137  13   4   java.util.HashMap::put (300 bytes)
        15   3   ! com.example.MyApp::loop (180 bytes)
```

! exception handler, % OSR, s synchronized, n native wrapper.

## 2. C1 vs C2 의 최적화 깊이

C1: constant folding, dead code elimination, simple inlining, range check elimination. C2: SSA IR 위 GVN, loop unrolling, vectorization, escape analysis, type speculation.

C2 인라이닝 조건: 호출 빈도, monomorphic/bimorphic callsite, bytecode 크기, recursion depth.

-XX:+PrintInlining 으로 결정 사유 관찰.

## 3. Escape Analysis 3 산출물

| Level | 의미 | 가능한 최적화 |
|---|---|---|
| NoEscape | 메서드 밖으로 노출 X | Scalar Replacement, Lock Elision |
| ArgEscape | 인자로 전달 | 부분 최적화 |
| GlobalEscape | 정적/스레드/반환 | 불가 |

```java
public Point translate(int dx, int dy) {
    Point tmp = new Point(x + dx, y + dy);   // EA 대상
    return new Point(tmp.x, tmp.y);
}
```

tmp 는 noescape → 레지스터로 분산, 객체 생성 X.

## 4. OSR — On-Stack Replacement

이미 실행 중인 인터프리터 프레임을 컴파일된 코드로 교체. 루프 백 엣지 카운터(BackedgeThreshold) 임계치 도달 시 트리거. JMH warmup 도 OSR + tier-up 안정을 위함.

## 5. Inlining 과 Polymorphism

Monomorphic(1종) → 인라이닝, Bimorphic(2종) → 둘 다 inline, Megamorphic(3+) → vtable dispatch.

`sealed interface` 는 closed-world 보장으로 JIT 친화적.

## 6. Deoptimization 과 Speculation 실패

C2 는 낙관적 가정 + type guard. 실패 시 deoptimization — interpreter 로 떨어진 후 재프로파일링. 빈번한 deopt 는 throughput 저해. JFR Compilation event 로 모니터링.

## 7. 측정 — JITWatch 와 JFR

JITWatch: -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation. JFR: -XX:StartFlightRecording=duration=60s,filename=app.jfr. JDK Mission Control JIT Compilations 뷰. CodeCache full 경고 시 ReservedCodeCacheSize 240m→480m.

## 8. 실측 — EA 와 Scalar Replacement

```
-XX:-DoEscapeAnalysis  2.45 ms/op, 16 MB/op allocation
-XX:+DoEscapeAnalysis  0.18 ms/op,  ~0 B/op  (14x throughput)
```

디버깅 reference 저장 / 예외 throw 에서 EA 깨짐.

## 9. 운영 가이드 — JIT 친화 코드

메서드 320 byte 미만. hot path hierarchy 는 sealed/final. Stream/Optional 은 hot path 에서 신중. -XX:+PrintInlining 으로 too big 사유 검사. 코드 캐시 사용량 모니터링.

## 10. Graal JIT 와 비교

Graal: Partial Escape Analysis, Polymorphic Inlining, Loop Versioning. 컴파일 시간 길음. 데이터 분석/장기 batch 이 우위.

## 11. AOT 와의 차이

| 차원 | JIT | AOT (Native) |
|---|---|---|
| 시동 | 느림 | ~100ms |
| 메모리 | 높음 | 50~70% 감소 |
| Peak throughput | 높음 | 10~20% 손해 |
| 동적 reflection | 자유 | metadata 등록 |
| 빌드 시간 | 짧음 | 5~15분 |

서버리스 (Lambda SnapStart) 는 AOT 유리. 24x7 장기 API 는 JIT.

## 참고

- HotSpot JIT 소스 (OpenJDK src/hotspot/share/opto/)
- Scott Oaks, Java Performance, O'Reilly
- JITWatch: https://github.com/AdoptOpenJDK/jitwatch
- JEP 295 AOT
- Aleksey Shipilev 블로그
- JDK Flight Recorder Documentation
