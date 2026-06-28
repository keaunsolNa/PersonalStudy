Notion 원본: https://app.notion.com/p/38d5a06fd6d38167b71eccc433dee333

# Java JIT C2 컴파일러와 인라이닝 및 Escape Analysis Scalar Replacement

> 2026-06-28 신규 주제 · 확장 대상: JAVA

## 학습 목표

- HotSpot의 계층형 컴파일(C1/C2)과 프로파일 기반 핫스팯 탐지 흐름을 추적한다
- 메서드 인라이닝의 결정 규칙과 호출 사이트 단형성 영향을 분석한다
- Escape Analysis가 스칼라 치환과 락 제거를 가능하게 하는 조건을 코드 수준으로 판별한다
- PrintInlining·PrintEliminateAllocations로 최적화 적용 여부를 직접 검증한다

## 계층형 컴파일

HotSpot은 바이트코드를 인터프리터로 실행하며 호출 횟수·루프 백엣지를 카운트한다. JDK 8+ 기본은 Tiered Compilation으로 Level 0~4를 거친다. C1은 빠르게 컴파일해 워밍업을 줄이고 Level 3에서 분기 확률·타입 프로파일을 모은다. C2는 그 프로파일을 근거로 공격적 최적화를 적용한다. 가정이 깨지면 deoptimization으로 인터프리터에 복귀한다.

## 메서드 인라이닝

인라이닝은 호출 메서드 본문을 호출 지점에 펀쳐 넣는 것으로, 이게 되어야 그 안의 코드가 escape analysis·상수 폴딩의 대상이 된다. 핵심은 호출 사이트의 단형성이다. monomorphic(타입 1, 무조건 인라인), bimorphic(2), megamorphic(3+, 인라인 포기)로 분류한다.

```java
public long sum(List<Integer> list) {
    long s = 0;
    for (int i = 0; i < list.size(); i++) s += list.get(i);
    return s;
}
```

ArrayList로만 호출하면 size/get이 인라인되어 배열 접근으로 펀쳐진다. 여러 구현을 섞어 호출하면 megamorphic이 되어 인라이닝이 막힌다 — 벤치마크 함정이 여기서 나온다.

## Escape Analysis

NoEscape(메서드 내부만), ArgEscape, GlobalEscape 세 단계다. NoEscape면 스칼라 치환(할당 제거)과 락 제거가 열린다.

```java
public double distance(double x1, double y1, double x2, double y2) {
    Point p1 = new Point(x1, y1); Point p2 = new Point(x2, y2);
    return Math.hypot(p1.x - p2.x, p1.y - p2.y);
}
// EA 후: Point 할당 제거, 지역 변수로 직접 계산
```

StringBuffer도 NoEscape면 append의 락이 제거되어 StringBuilder와 동등해진다. 이 최적화는 인라이닝이 선행되어야 동작한다.

## 검증

```
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining -XX:+PrintEliminateAllocations -jar app.jar
```

| 최적화 | 활성 조건 | 끄는 플래그 |
|---|---|---|
| 인라이닝 | 핫 + 크기 한계 | -XX:-Inline |
| 스칼라 치환 | NoEscape+인라인 | -XX:-EliminateAllocations |
| 락 제거 | NoEscape 락 | -XX:-EliminateLocks |

`failed to inline: virtual call`이 보이면 megamorphic이므로 단형화하거나 final로 만드는 조치를 검토한다.

## Deoptimization

C2의 모든 최적화는 추측이다. uncommon trap이 심겨 가정 위반 시 인터프리터로 복귀한다. deopt는 프레임 재구성이 필요해 비싸다. PrintCompilation에 made not entrant가 반복되면 deopt가 잦다는 신호다. range check elimination 덕분에 단순 루프가 화려한 인덱스 산술보다 빠르다.

## 실무 체크리스트

호출 사이트를 단형으로, 메서드를 작게, 임시 객체를 메서드 밖으로 안 새게. 측정 없이 추측하지 않고 PrintInlining과 JMH -prof gc로 확인한 뒤에만 최적화를 판단한다. 객체 풀링은 GlobalEscape를 유발해 EA를 막는 역효과가 있다.

## 참고

- HotSpot Tiered Compilation: https://docs.oracle.com/en/java/javase/17/vm/java-hotspot-virtual-machine-performance-enhancements.html
- OpenJDK Compiler Wiki: https://wiki.openjdk.org/display/HotSpot/Compiler
- JMH: https://github.com/openjdk/jmh
