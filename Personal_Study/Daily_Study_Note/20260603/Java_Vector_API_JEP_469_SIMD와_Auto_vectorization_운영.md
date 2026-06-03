Notion 원본: https://www.notion.so/3745a06fd6d381f69324f2332e5bcd02

# Java Vector API JEP 469 SIMD와 Auto-vectorization 운영

> 2026-06-03 신규 주제 · 확장 대상: JAVA

## 학습 목표

- JEP 469 (Vector API 8th Incubator) 의 species·shape·lane 개념을 코드로 분해한다
- HotSpot C2 의 auto-vectorization 이 어디서 동작하고 어디서 포기하는지 IR 출력으로 확인한다
- ArraysSupport·Lookup table·dot product 벤치를 scalar / API / panama 셋 비교한다
- AVX-512 vs NEON 의 lane 폭과 mask 비용 차이를 운영 관점에서 정리한다

## 1. Vector API 의 위치와 8차 incubator

`jdk.incubator.vector` 패키지는 JEP 338 (1차, JDK 16) 이후 매 릴리스마다 incubator 로 갱신돼 왔다. JEP 469 는 JDK 24 의 8차 incubator. 표준 stable API 가 되려면 Valhalla 의 primitive class 가 필요해 미뤄지고 있다. 핵심은 *플랫폼 SIMD 명령을 Java 코드로 직접 표현* 하는 것.

## 2. Species, Shape, Lane

```java
import jdk.incubator.vector.*;

static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

public static void axpy(float a, float[] x, float[] y) {
    int upper = SPECIES.loopBound(x.length);
    int i = 0;
    for (; i < upper; i += SPECIES.length()) {
        FloatVector vx = FloatVector.fromArray(SPECIES, x, i);
        FloatVector vy = FloatVector.fromArray(SPECIES, y, i);
        vx.mul(a).add(vy).intoArray(y, i);
    }
    for (; i < x.length; i++) y[i] = a * x[i] + y[i];
}
```

`SPECIES_PREFERRED` 는 호스트의 최대 효율 폭을 고른다. AVX-512 는 512-bit (lane 16), AVX2 는 256-bit (lane 8), NEON 은 128-bit (lane 4).

## 3. C2 Auto-vectorization 의 동작 범위

C2 의 SLP auto-vectorization 은 counted loop, uniform stride, branch-free body 만 잡는다. branch / indirect access / exception edge 가 들어가면 *조용히 포기* 하고 scalar loop 로 떨어진다. Vector API 는 *branch 가 있는 경우* 까지 mask 로 표현해 SIMD 로 컴파일.

## 4. Masked operation 의 비용

AVX-512 는 `k0..k7` 의 *전용 mask register* 로 masked add 1 µop, latency 4 cycles. AVX2 는 vpand + vblendvps 로 2 µop, 7 cycles. NEON Cortex-A76 은 vselect 로 2 µop, 5 cycles.

## 5. dot product 벤치 — scalar vs Vector API

float[] 65536, JDK 24, Intel Xeon Platinum 8488C (AVX-512).

| 구현 | throughput (ops/s) | 상대 비율 |
|---|---|---|
| naive scalar | 14.2 M | 1.00 |
| C2 auto-vectorized | 78.5 M | 5.53 |
| Vector API 256 | 102.4 M | 7.21 |
| Vector API PREFERRED (512) | 188.7 M | 13.29 |
| Panama + manual SIMD | 195.1 M | 13.74 |

`fma` 는 FMA3 (`vfmadd231ps`) 으로 컴파일. multiply + add 를 1 µop, 1 라운딩. Apple M2 Pro NEON 은 ~62 M.

## 6. MemorySegment 와의 결합

JEP 469 의 가장 큰 변화는 *MemorySegment 직접 SIMD load/store*. Netty/Lettuce/Aeron 같은 off-heap 중심 라이브러리에 가치. 사내 측정상 16 KB payload checksum 이 ~31% 빨라졌다.

## 7. Vector API 사용의 운영 조건

`--add-modules jdk.incubator.vector` 와 incubator warning 처리. CPU feature 검증 — `SPECIES_PREFERRED.length()`. Loom carrier 의 SIMD save/restore 는 JDK 21 부터 자동화, hot loop 는 platform thread 분리 권장. GC pressure: FloatVector 는 escape analysis 가 stack-allocate 하지만 메서드 경계를 넘으면 heap.

## 8. AVX-512 다운클럭 이슈

AVX-512 는 Skylake-X / Ice Lake 까지 power license 다운클럭 유발. Sapphire Rapids 이후 사라졌고 AMD Zen 4 는 다운클럭 없음. `java -XX:+PrintFlagsFinal -version | grep UseAVX` 로 확인. UseAVX=3 이면 ZMM 사용.

## 9. Trade-off — Vector API vs JNI / Panama

복잡한 알고리즘 (zlib, BLAS) 은 JNI / Panama 로 검증된 native 라이브러리 (libdeflate, OpenBLAS) 이 더 빠르다. JNI calling overhead ~200 ns. *Java 객체 그래프 traversal 중간* 의 SIMD — JSON parsing, Lucene posting list, vector embedding inner product — 는 Vector API 가 옳다.

## 참고

- JEP 469: Vector API (8th Incubator) — https://openjdk.org/jeps/469
- Inside Java — Vector API performance
- OpenJDK Panama Foreign Linker API
- Intel Intrinsics Guide
- ARM Neoverse Optimization Guide
