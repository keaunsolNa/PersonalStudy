Notion 원본: https://www.notion.so/3b65a06fd6d3819eaff4f56b18116cbf

# Java JIT 컴파일러와 티어드 컴파일 및 탈최적화 진단

> 2026-08-08 신규 주제 · 확장 대상: JAVA (JVM 문법·G1 GC·invokedynamic·가상 스레드 기학습)

## 학습 목표

- 인터프리터 → C1 → C2로 이어지는 티어드 컴파일 5단계와 승격 임계값 산식을 계산한다
- 인라이닝·탈출 분석·락 생략 등 C2의 핵심 최적화가 적용되는 조건과 무효화되는 조건을 구분한다
- 탈최적화(deoptimization)의 종류(uncommon trap, made not entrant)를 로그로 판독한다
- JITWatch·JFR·`-XX:+PrintCompilation`으로 실서비스의 컴파일 동작을 진단한다

## 1. 왜 티어드인가 — 해석 실행과 컴파일의 트레이드오프

HotSpot은 모든 메서드를 처음에는 인터프리터로 실행한다. 컴파일은 비용이므로(C2 기준 메서드당 수 ms, 수 MB 코드 캐시) 자주 실행되는 "핫" 코드에만 투자해야 하고, 어떤 코드가 핫한지는 실행해 봐야 안다. 여기서 나온 구조가 티어드 컴파일이다. 빠르게 만들 수 있지만 덜 최적화된 코드(C1)로 먼저 전환해 프로파일을 수집하고, 그 프로파일을 근거로 고급 최적화 컴파일러(C2)가 최종 코드를 만든다. 레벨은 5단계다.

| 레벨 | 실행 방식 | 프로파일링 | 용도 |
|---|---|---|---|
| 0 | 인터프리터 | 전체(호출·분기 카운터) | 초기 실행 |
| 1 | C1 full opt | 없음 | 자명하게 단순한(trivial) 메서드 종착지 |
| 2 | C1 limited profile | 호출·백엣지 카운터만 | C2 큐 포화 시 우회 |
| 3 | C1 full profile | 호출·분기·타입 프로파일 | C2 승격 전 표준 경로 |
| 4 | C2 | 없음 | 최종 최적화 코드 |

표준 흐름은 0 → 3 → 4다. 승격 판정은 단순 호출 횟수가 아니라 호출 수 i와 루프 백엣지 수 b를 결합한 산식으로, 레벨 3→4는 대략 `i > Tier4InvocationThreshold(기본 15,000)` 또는 `i > Tier4MinInvocationThreshold(600) && i + b > Tier4CompileThreshold(15,000)`을 넘을 때 트리거된다. 루프가 긴 메서드는 호출 1회로도 백엣지가 임계값을 넘을 수 있는데, 이때 메서드 전체가 아니라 **루프 본문만 컴파일해 실행 중 교체하는 OSR(On-Stack Replacement)** 이 일어난다. `-XX:+PrintCompilation` 출력에서 `%` 표시가 OSR 컴파일이다.

## 2. 코드 캐시와 컴파일 스레드

컴파일된 네이티브 코드는 코드 캐시(기본 240MB, `ReservedCodeCacheSize`)에 저장되며 JDK 9+에서는 non-method / profiled(C1) / non-profiled(C2) 3개 세그먼트로 나뉜다. 코드 캐시가 가득 차면 **컴파일이 중단되고 경고(`CodeCache is full. Compiler has been disabled`)와 함께 이후 코드는 인터프리터/기존 코드로만 실행**되므로, 대형 애플리케이션에서 원인 불명의 점진적 성능 저하가 있다면 코드 캐시 고갈을 먼저 의심한다. `jcmd <pid> Compiler.codecache`로 사용량을 확인할 수 있다.

컴파일 자체는 백그라운드 컴파일 스레드가 수행한다(C1용·C2용 분리, 개수는 CPU 코어 기반 자동). 큐가 밀리면 레벨 2 우회 경로가 쓰이는 이유이기도 하다. 시작 직후 수 분간 CPU가 애플리케이션 로직보다 컴파일에 쓰이는 "워밍업 구간"이 존재하며, 지연에 민감한 서비스는 이 구간을 트래픽 램프업으로 흡수하거나 CDS/AOT(§8)로 줄인다.

## 3. C2의 핵심 최적화 1 — 인라이닝

인라이닝은 모든 최적화의 어머니다. 호출 경계가 사라져야 탈출 분석·상수 전파·루프 최적화가 호출 건너편까지 미친다. 기본 규칙은 바이트코드 크기 기반이다: 핫 메서드는 `FreqInlineSize`(기본 325바이트) 이하, 일반 메서드는 `MaxInlineSize`(35바이트) 이하면 인라인된다. 인라이닝 깊이는 `MaxInlineLevel`(15) 제한을 받는다.

가상 호출(다형성)은 그대로는 인라인할 수 없으므로 **타입 프로파일 기반 추측 인라이닝**이 동작한다. 레벨 3에서 수집한 리시버 타입 분포를 보고, 단형(monomorphic)이면 타입 체크 가드 + 직접 인라인으로, 2형(bimorphic)이면 두 갈래 가드로 인라인한다. **3형 이상(megamorphic)이 되는 순간 인라이닝이 포기되고 vtable 디스패치로 떨어진다** — 같은 호출 지점(call site)에 세 타입 이상이 흘러들지 않게 설계하는 것이 저수준 성능 튜닝의 고전적 요령이다. 진단은 다음 플래그로 한다.

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
     -XX:+PrintInlining MyApp
# 출력 예:
#   @ 12  com.example.Order::total (28 bytes)   inline (hot)
#   @ 30  java.util.Iterator::next (—)          virtual call  ← megamorphic 포기
#   @ 45  com.example.Big::calc (412 bytes)     too large     ← FreqInlineSize 초과
```

`too large`로 인라인이 막힌 핫 메서드는 실제로 메서드 분리(작은 메서드로 쪼개기)가 성능 개선으로 이어지는 드문 사례다. 단, 추측으로 쪼개지 말고 반드시 PrintInlining 증거를 확보한 뒤에 한다.

## 4. C2의 핵심 최적화 2 — 탈출 분석과 락 최적화

탈출 분석(Escape Analysis)은 객체 참조가 생성 스코프를 벗어나는지(다른 스레드/힙으로 탈출) 판정한다. 탈출하지 않는(NoEscape) 객체에는 **스칼라 치환(Scalar Replacement)** 이 적용되어, 객체 할당 자체가 사라지고 필드가 레지스터/스택 변수로 분해된다. "짧게 살고 스코프를 안 벗어나는 객체는 사실상 공짜"라는 통념의 근거다.

```java
// Iterator, 임시 Point 등이 스칼라 치환의 전형적 수혜자
long sumDistance(List<Point> points) {
	long acc = 0L;
	for (Point p : points) {          // Iterator 할당 — 탈출 안 함 → 제거
		Point delta = new Point(p.x - 1, p.y - 1); // 탈출 안 함 → 필드 분해
		acc += delta.x + delta.y;
	}
	return acc;
}
```

단, 스칼라 치환은 인라이닝이 선행돼야 하고(생성자·사용처가 한 컴파일 단위 안에 있어야 판정 가능), 분기에 따라 탈출 여부가 갈리는 경우(부분 탈출)는 HotSpot C2가 보수적으로 포기한다 — 부분 탈출까지 치환하는 것은 GraalVM 컴파일러의 차별점이다. 탈출 분석의 부산물로 락 최적화도 이뤄진다. 탈출하지 않는 객체에 대한 `synchronized`는 통째로 제거되고(lock elision), 인접한 동일 모니터 락은 병합된다(lock coarsening). `StringBuffer`를 지역 변수로만 쓰면 동기화 비용이 0에 수렴하는 이유다.

## 5. 추측 최적화와 uncommon trap

C2 코드는 "지금까지 관측된 사실이 앞으로도 유지된다"는 가정 위에 만들어진 **추측(speculative) 코드**다. 한 번도 타지 않은 분기, 한 번도 발생하지 않은 예외 경로, 로드된 적 없는 서브클래스는 코드로 만들지 않고 **uncommon trap**이라는 탈출구만 심는다. 실행 중 가정이 깨지면 트랩이 발동해 컴파일 코드의 레지스터 상태를 인터프리터 프레임으로 복원(deoptimization)하고 해석 실행으로 계속한다. 이후 프로파일이 갱신되면 재컴파일된다.

대표 트리거는 세 가지다. 첫째, **클래스 계층 변화** — CHA(Class Hierarchy Analysis)로 "구현체가 하나뿐"이라 보고 가드 없이 인라인했는데 새 서브클래스가 로드되면, 해당 가정에 의존한 모든 nmethod가 `made not entrant`(신규 진입 금지) 처리된다. 둘째, **타입 프로파일 배반** — 단형 가드에 새 타입이 도달. 셋째, **미실행 분기 도달** — null 최초 관측, 예외 최초 발생 등. 로그로 관측하려면:

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=comp.log MyApp
# comp.log 내 <uncommon_trap reason="class_check" action="maybe_recompile" .../>
# PrintCompilation 출력의 "made not entrant" / "made zombie"가 폐기 수순
```

탈최적화 자체는 정상 동작이지만, **같은 지점에서 반복되는 recompile 사이클**(트랩 → 재컴파일 → 다시 트랩)은 성능 문제다. 전형적 원인은 런타임에 타입 분포가 계속 바뀌는 call site, 또는 벤치마크와 실트래픽의 데이터 분포 차이다.

## 6. 벤치마크 함정 — JIT 앞에서 나노벤치마크가 무의미해지는 이유

JIT의 추측 최적화 때문에 순진한 마이크로벤치마크는 거의 항상 거짓말을 한다. 결과를 사용하지 않는 연산은 죽은 코드 제거(DCE)로 통째로 사라지고, 루프 안 불변 계산은 루프 밖으로 호이스팅되며, 상수 입력은 상수 접기로 컴파일 타임에 계산된다. 워밍업 전 측정은 인터프리터를 재고, 워밍업 후엔 벤치마크 하네스 코드까지 인라인되어 실측 대상이 왜곡된다. JMH가 표준인 이유가 이것들의 체계적 차단이다: `Blackhole.consume`으로 DCE 차단, `@State` 필드 주입으로 상수 접기 차단, fork로 프로파일 오염 격리, 워밍업 이터레이션 분리. JIT 동작을 이해하지 못하면 JMH 결과조차 오독한다 — 예컨대 단일 구현만 워밍업한 벤치는 단형 인라이닝 혜택을 받아, 실서비스의 megamorphic 현실보다 항상 낙관적이다. 인터페이스 구현 2~3개를 섞어 호출하는 시나리오를 별도 케이스로 두는 것이 올바른 설계다.

## 7. 실전 진단 워크플로우

실서비스에서 "JIT가 의심될 때"의 진단 순서를 정리한다. (1) `jcmd <pid> Compiler.codecache`로 코드 캐시 고갈 여부 확인 — 고갈이면 `ReservedCodeCacheSize` 증설로 종결. (2) JFR 녹화(`jcmd <pid> JFR.start duration=120s filename=rec.jfr`)에서 Compilation 이벤트로 컴파일 폭주·큐 대기 확인, Deoptimization 이벤트(JDK 14+)로 반복 탈최적화 지점 식별. (3) 특정 메서드가 문제로 좁혀지면 `-XX:+LogCompilation`을 재현 환경에서 켜고 **JITWatch**로 로그를 연다 — 메서드별 인라이닝 트리, 컴파일 레벨 천이, 탈최적화 사유가 시각화된다. (4) 어셈블리 수준 확인이 필요하면 hsdis 라이브러리를 설치하고 `-XX:CompileCommand=print,com.example.Hot::method`로 해당 메서드만 디스어셈블한다. 어셈블리까지 내려가는 경우는 드물지만, SIMD 자동 벡터화 적용 여부나 바운즈 체크 제거 확인에는 이 방법뿐이다.

체크리스트로 기억할 안티패턴: 시작 스크립트에 남아 있는 `-Xint`(해석 전용)·`-XX:TieredStopAtLevel=1`(테스트용 설정의 프로덕션 유출), 거대 단일 메서드(생성 코드·파서 등이 8KB 바이트코드 한계 `DontCompileHugeMethods`에 걸려 영원히 인터프리터 실행), 과도한 리플렉션 경유 호출로 인한 프로파일 오염.

## 8. 워밍업 문제와 AOT·CRaC — JIT 모델의 보완

티어드 컴파일의 구조적 약점은 워밍업이다. 최고 성능 도달까지 수천~수만 호출이 필요하므로, 짧게 살고 죽는 컨테이너·서버리스 환경과 상성이 나쁘다. 보완 기술이 단계적으로 도입됐다. **AppCDS**는 클래스 메타데이터를 아카이브해 로딩 시간을 줄이고, JDK 24의 **AOT 캐시(Project Leyden, JEP 483)** 는 이전 실행의 로딩·링킹 결과와 프로파일을 재사용해 워밍업을 단축한다. **CRaC**은 워밍업 완료 시점의 프로세스를 체크포인트로 떠서 복원하는 접근으로, 복원 즉시 C2 코드로 실행된다는 장점이 있으나 열린 리소스(소켓·파일)의 체크포인트 훅 처리가 필요하다. GraalVM **Native Image**는 아예 AOT 컴파일로 JIT을 제거해 기동 수십 ms를 얻지만, 닫힌 세계 가정(리플렉션 사전 등록)과 피크 성능 저하(PGO 없이는 C2 대비 열세) 트레이드오프가 있다. 선택 기준은 워크로드 수명이다: 장수 서버는 JIT 그대로, 스케일-투-제로·CLI는 Native Image, 그 중간(빠른 기동 + JIT 피크 성능 모두 필요)이 CRaC/AOT 캐시의 자리다.

## 9. 정리 — 설계에 반영할 실무 원칙

JIT 내부 지식이 코드 설계로 환원되는 지점을 정리한다. 첫째, **작고 단형적인 메서드**가 유리하다 — 인라이닝 한계(325B)와 타입 프로파일(2형까지)을 넘지 않는 구조가 최적화 파이프라인 전체를 살린다. 둘째, **할당을 두려워하되 측정 후에** — 탈출하지 않는 임시 객체는 스칼라 치환으로 공짜가 되므로, 할당 제거 리팩터링은 JFR의 TLAB 할당 프로파일로 실제 힙 압력을 확인한 뒤에 한다. 셋째, **성능 주장에는 JIT 증거를** — "이 코드가 더 빠르다"는 주장은 JMH + PrintInlining/JITWatch 증거가 있을 때만 신뢰한다. 넷째, **운영 파라미터는 세 개만 기억** — `ReservedCodeCacheSize`(고갈 방지), JFR 상시 녹화(진단 가능성), 워밍업 전략(램프업 또는 AOT 캐시). 나머지 컴파일러 플래그 튜닝은 증거 없이 만지지 않는 것이 기본값이다.

## 참고

- Aleksey Shipilëv, "JVM Anatomy Quarks" 시리즈 (shipilev.net) — Lock Elision, Scalar Replacement 편
- Chris Newland, JITWatch Wiki (github.com/AdoptOpenJDK/jitwatch)
- OpenJDK — Tiered Compilation 소스 주석 (compilationPolicy.cpp)
- JEP 483: Ahead-of-Time Class Loading & Linking (Project Leyden)
- Scott Oaks, 『Java Performance』 2nd Ed. — 4장 JIT Compiler
