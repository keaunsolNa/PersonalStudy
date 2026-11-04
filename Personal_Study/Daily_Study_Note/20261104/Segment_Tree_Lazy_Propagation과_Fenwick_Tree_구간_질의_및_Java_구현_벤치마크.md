Notion 원본: https://app.notion.com/p/40e5a06fd6d38142ad5ff67adce961c5?pvs=204

# Segment Tree Lazy Propagation과 Fenwick Tree(BIT) 구간 질의: 복잡도·메모리 레이아웃 및 Java 구현 벤치마크

> 2026-11-04 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- Fenwick Tree의 lowbit 이동 규칙과 2개 BIT를 이용한 구간 갱신·구간 합 공식을 직접 유도한다.
- 재귀 4n 배열과 반복 2n 배열 segment tree의 메모리 레이아웃을 바이트 단위로 계산해 비교한다.
- lazy propagation의 불변식을 기준으로 재귀·반복 구현을 작성하고 브루트포스 대조 테스트로 검증한다.
- N=1e6 벤치마크 결과를 근거로 질의 유형별(점 갱신/구간 갱신) 자료구조 선택 기준을 세운다.

## 1. 문제 정의와 복잡도 개요

배열 A[0..n)에 대해 "구간 [l, r)에 v를 더한다"와 "구간 [l, r)의 합을 구한다"를 반복 처리하는 문제를 생각한다. 갱신이 점 단위면 Fenwick Tree 하나로 충분하고, 갱신이 구간 단위가 되면 선택지가 둘로 갈린다. 하나는 segment tree에 lazy 태그를 붙이는 방법이고, 다른 하나는 차분 배열의 성질을 이용해 Fenwick Tree 두 개로 같은 연산을 흉내 내는 방법이다. 두 방법 모두 연산당 O(log n)이지만 상수와 메모리, 확장성이 크게 다르다. 아래 표는 이 문서에서 구현하고 측정한 다섯 구조의 이론적 성질이다.

| 구조 | 갱신 | 질의 | 빌드 | 배열 크기(long) |
|---|---|---|---|---|
| Fenwick (점 갱신) | O(log n) | O(log n) | O(n) | n+1 |
| 2-BIT (구간 갱신) | O(log n) | O(log n) | O(n) | 3n 안팎 |
| 반복 segment tree (lazy 없음) | O(log n) | O(log n) | O(n) | 2n |
| 반복 lazy segment tree | O(log n) | O(log n) | O(n) | 3n (올림 후) |
| 재귀 lazy segment tree | O(log n) | O(log n) | O(n) | 8n |

점근 복잡도가 모두 같으므로 차이는 상수 계수, 배열 접근 패턴, 확장 가능한 연산 범위에서 나온다. Fenwick은 합처럼 역원이 있는 연산에 묶이고, segment tree는 결합법칙만 성립하면 min, max, gcd로 확장된다. 이 차이가 실무 선택의 첫 번째 기준이 된다. 모든 코드는 반열린 구간 [l, r)과 0-based 인덱스를 쓴다. 닫힌 구간과 섞어 쓰면 off-by-one이 가장 먼저 터지는 지점이므로 한 가지로 통일하는 편이 안전하다.

## 2. Fenwick Tree의 구조와 구현

Fenwick Tree는 1-based 배열 f에서 f[i]가 구간 (i - lowbit(i), i]의 합을 책임지게 하는 구조다. lowbit(i) = i & -i는 i의 최하위 1비트 값이다. 접두사 합은 i에서 lowbit를 빼며 내려가고(최대 log n번), 점 갱신은 lowbit를 더하며 올라간다. 포인터도 자식 배열도 없이 정수 하나의 비트 연산만으로 트리 탐색이 끝나므로 메모리가 n+1개 long이면 충분하다. 또한 빌드를 O(n log n) 번 add로 하지 않고, 각 i의 값을 부모 i + lowbit(i)에 한 번씩 밀어 올리면 O(n)에 끝난다.

```java
static final class Fenwick {
    final int n; final long[] f;
    Fenwick(long[] a) {                    // O(n) build
        n = a.length; f = new long[n + 1];
        for (int i = 1; i <= n; i++) {
            f[i] += a[i - 1];
            int j = i + (i & -i);
            if (j <= n) f[j] += f[i];
        }
    }
    void add(int i, long v) { for (i++; i <= n; i += i & -i) f[i] += v; }
    long prefix(int r) { long s = 0; for (; r > 0; r -= r & -r) s += f[r]; return s; }
    long query(int l, int r) { return prefix(r) - prefix(l); }
}
```

이 구조의 한계는 명확하다. 질의가 prefix(r) - prefix(l)로 환원되려면 연산에 역원이 있어야 하므로 구간 최솟값은 직접 지원하지 못한다. 반대로 합, XOR, 모듈러 합에는 이보다 짧고 빠른 코드를 찾기 어렵다. 루프 안에 분기가 없고 배열이 연속 메모리 하나이므로 JIT가 단순한 루프로 컴파일하기 쉽다는 점도 뒤의 벤치마크에서 수치로 확인한다.

## 3. Segment Tree의 메모리 레이아웃: 재귀 4n vs 반복 2n

재귀 segment tree는 루트를 1번으로 두고 노드 o의 자식을 2o, 2o+1에 배치하는 힙 인덱싱을 쓴다. n이 2의 거듭제곱이 아니면 트리가 불완전하게 자라 최대 인덱스가 4n 근처까지 갈 수 있어, 안전하게 4n칸을 잡는 것이 관행이다. lazy 배열까지 두면 long 배열 2개이므로 n=1e6에서 4,000,000 × 8B × 2 = 64,000,000B(약 61MiB)다. 반면 bottom-up 반복 구현은 리프를 [n, 2n)에 그대로 놓고 내부 노드를 [1, n)에 두므로 합 배열이 2n칸(16,000,000B)이고, lazy가 필요한 AtCoder 방식은 n을 2의 거듭제곱으로 올려(1e6 → 2^20 = 1,048,576) d 배열 2·size, lz 배열 size를 쓰므로 합계 약 25.2MB가 된다.

| 구조 (N=1e6) | long 개수 | 바이트 |
|---|---|---|
| Fenwick | 1,000,001 | 8,000,008 |
| 2-BIT (B1, B2) + base Fenwick | 3 × 1,000,002 | 약 24.0MB |
| 반복 segment tree (lazy 없음) | 2,000,000 | 16,000,000 |
| 반복 lazy segment tree | 3 × 1,048,576 | 25,165,824 |
| 재귀 lazy segment tree | 8,000,000 | 64,000,000 |

위 수치는 배열 선언 크기에서 계산한 값이며 객체 헤더는 무시했다. 재귀 구현은 메모리를 2.5배 더 쓰면서도 노드마다 구간 [lo, hi)를 인자로 들고 다녀야 하는 반면, 반복 구현은 인덱스 산술만으로 구간을 복원한다. 힙 인덱싱에서 노드 k의 담당 길이는 size >> (31 - numberOfLeadingZeros(k))로 O(1)에 구해지는데, 이 한 줄이 반복 lazy 구현에서 구간 길이를 따로 저장하지 않게 해 준다.

## 4. Lazy Propagation의 불변식과 재귀·반복 구현

Lazy propagation의 핵심은 하나의 불변식이다. 노드 o의 sum[o]는 o의 조상들에 쌓인 태그를 제외하고 자기 구간에 대해 이미 올바른 값이며, lz[o]는 o의 자식들에게는 아직 적용되지 않은 보류 중 갱신이다. 구간이 완전히 덮이면 sum에 v × 길이를 더하고 태그만 남긴 채 내려가지 않는다. 구간이 부분적으로만 겹칠 때는 내려가기 전에 반드시 push로 태그를 자식에게 밀어내야 하는데, 이를 빼먹으면 자식이 오래된 값을 기준으로 갱신되어 조용히 틀린 답이 나온다. 덧셈 태그는 가환이라 순서 문제가 없지만, 대입과 덧셈을 섞는 경우는 태그 합성 순서를 별도로 정의해야 한다.

```java
void apply(int o, int len, long v) { sum[o] += v * len; lz[o] += v; }
void push(int o, int lo, int hi) {
    if (lz[o] != 0) {
        int m = (lo + hi) >>> 1;
        apply(2*o, m - lo, lz[o]); apply(2*o+1, hi - m, lz[o]); lz[o] = 0;
    }
}
void add(int o, int lo, int hi, int l, int r, long v) {
    if (r <= lo || hi <= l) return;
    if (l <= lo && hi <= r) { apply(o, hi - lo, v); return; }
    push(o, lo, hi);
    int m = (lo + hi) >>> 1;
    add(2*o, lo, m, l, r, v); add(2*o+1, m, hi, l, r, v);
    sum[o] = sum[2*o] + sum[2*o+1];
}
```

반복 버전은 같은 불변식을 다른 순서로 지킨다. 구간 경계 l, r의 조상들을 위에서 아래로 push하고(루트에서 리프 쪽으로 log번), 아래에서 위로 올라가며 완전히 덮이는 노드에만 apply를 한 뒤, 경계 조상들을 다시 pull로 재계산한다. 재귀 호출과 스택 프레임이 사라지는 대신 경계 처리 조건 `((l >> i) << i) != l` 같은 비트 식이 가독성을 해친다. 정확성을 눈으로 확인하기 어렵기 때문에, 이런 코드일수록 다음 절의 브루트포스 대조 테스트가 필수다.

## 5. 2개 BIT로 구간 갱신·구간 합 만들기

구간 갱신을 Fenwick으로 처리하는 길은 차분 배열 D에서 시작한다. 구간 [l, r)에 v를 더하는 것은 D[l] += v, D[r] -= v와 같고, 원소 A[i]는 D의 접두사 합이다. 그러면 접두사 합 prefix(x) = `Σ(i<x) A[i]`는 각 갱신 지점 `j<x`가 (x - j) × D[j]만큼 기여하므로 x × Σ D[j] - Σ j × D[j]로 쓸 수 있다. 따라서 D[j]를 모으는 BIT(B1)와 j × D[j]를 모으는 BIT(B2)를 따로 두면 두 번의 접두사 질의로 prefix(x)가 나온다. 초기 배열의 합은 별도의 일반 Fenwick(base)이 담당한다.

```java
void add(int l, int r, long v) {            // [l, r)
    upd(b1, l + 1, v);     upd(b1, r + 1, -v);
    upd(b2, l + 1, v * l); upd(b2, r + 1, -v * r);
}
long prefix(int x) { return base.prefix(x) + get(b1, x) * x - get(b2, x); }
long query(int l, int r) { return prefix(r) - prefix(l); }
```

이 방식은 lazy 태그도 push도 없어 코드가 짧고 틀릴 지점이 적다. 대신 접두사 질의 하나가 BIT 세 번 순회(base, B1, B2)이고 갱신은 BIT 두 개에 각 두 번씩 쓰기가 일어난다. 값 범위도 주의해야 한다. B2에는 v × l이 쌓이므로 v가 1e9, l이 1e6이면 한 번의 갱신으로 1e15가 되어, 갱신이 수천 번 누적되면 long(약 9.2e18)의 경계에 다가간다. segment tree 쪽도 v × 길이를 곱하므로 사정은 같아서, 문제의 값 상한을 먼저 계산해 두는 습관이 필요하다.

## 6. 브루트포스 대조 테스트 결과

정확성은 naive 배열과의 직접 비교로 검증했다. n을 1, 2, 3, 7, 16, 100, 1000, 4097로 바꿔 가며(공백 구간 l=r과 n=1 같은 경계 포함) 각 크기마다 구간 갱신/질의 또는 점 갱신/질의를 무작위로 섞어 실행하고, 매 질의마다 naive의 이중 루프 결과와 값이 다르면 AssertionError를 던진다. RecLazy, IterLazy, RangeBit은 같은 구간 갱신 시퀀스를 공유하고, IterSeg와 Fenwick은 별도의 점 갱신 시퀀스를 공유한다. 시드는 42로 고정해 재현 가능하다.

```text
$ javac SegBit.java && java SegBit test
TEST PASS: 1200526 sum queries matched naive across 8 sizes, 5 structures
```

질의 약 120만 건이 모두 일치했고, 한 번의 불일치도 없었다. 다만 이 테스트가 보증하는 범위는 long 오버플로가 일어나지 않는 값 범위(원소와 갱신값이 ±1000 이내)이며, 오버플로 경계는 별도로 다루지 않았다. 또한 재귀·반복 lazy 구현이 서로 같은 결과를 내는지는 벤치마크의 checksum이 한 번 더 확인해 준다. 아래 벤치마크에서 세 구조의 checksum은 정확히 같은 값이다.

## 7. N=1e6 벤치마크

측정 환경은 Linux 6.18, Intel Xeon 2.10GHz 2 vCPU, RAM 8GB의 가상 머신이고 JVM은 OpenJDK 21.0.12.1(64-Bit Server VM, 기본 힙 상한 1496MB)이다. N=1e6, 질의 수 Q=1e6이며 갱신과 질의를 50:50으로 섞고 구간은 [0, N] 범위에서 균등 무작위로 뽑았다. 연산 배열은 미리 생성해 생성 시간이 측정에서 빠지게 했고, 각 시나리오를 워밍업 3회 뒤 5회 측정해 중앙값과 최솟값을 기록했다. 실행 시간에는 구조 빌드가 포함된다. 아래는 출력 그대로다.

```text
java 21.0.12.1 / OpenJDK 64-Bit Server VM / 2 cpu / maxHeap 1496 MB
N=1000000 Q=1000000 (50% update, 50% query, 워밍업 3회 후 5회 측정)
[A] range add + range sum
RecLazy (recursive)                median  1057.1 ms  min   952.5 ms  checksum -16717071692295269
IterLazy (bottom-up)               median   738.1 ms  min   584.8 ms  checksum -16717071692295269
RangeBit (2 BIT + base)            median   405.1 ms  min   396.9 ms  checksum -16717071692295269
[B] point add + range sum
RecLazy (point = [i,i+1))          median   710.7 ms  min   630.0 ms  checksum 80280748667
IterSeg (no lazy)                  median   133.3 ms  min   129.2 ms  checksum 80280748667
Fenwick                            median    73.7 ms  min    65.4 ms  checksum 80280748667
[C] build only
RecLazy build                      median    17.0 ms  min    15.9 ms  checksum 381527
IterLazy build                     median     6.9 ms  min     6.4 ms  checksum 381527
Fenwick build O(n)                 median     3.4 ms  min     3.2 ms  checksum -2303
```

구간 갱신 시나리오(A)에서 2-BIT는 재귀 lazy segment tree보다 약 2.6배, 반복 lazy보다 약 1.8배 빨랐다. 점 갱신 시나리오(B)에서는 Fenwick이 lazy 없는 반복 segment tree보다 약 1.8배, 같은 문제를 lazy 재귀 트리로 푼 경우보다 약 9.6배 빨랐다. 반복 구현이 재귀 구현보다 일관되게 빠르다는 점도 확인되었다. 이 측정은 vCPU 2개짜리 단일 환경의 한 번 실행이고 중앙값과 최솟값의 격차가 20%를 넘는 항목(IterLazy)이 있으므로, 절대값보다 순서와 대략적 배수로 읽는 것이 맞다. 캐시 지역성이 원인이라는 해석은 가능하지만 이번에는 하드웨어 카운터로 확인하지 않았으므로 가설로만 남긴다.

## 8. 선택 기준과 실무 함정

합 질의만 있다면 점 갱신은 Fenwick, 구간 갱신은 2-BIT가 코드량과 속도 양면에서 우세하다. 이번 측정에서 2-BIT는 반복 lazy segment tree의 절반 남짓한 시간(405ms 대 738ms)에 끝났고 메모리도 비슷하거나 작았다. 반대로 구간 최솟값에 구간 덧셈을 얹거나, 구간 대입과 구간 합을 함께 지원하거나, 구간 위에서 이분 탐색(세그먼트 트리 위 descent)이 필요하면 BIT로는 표현이 안 되므로 lazy segment tree를 써야 한다. 이때 재귀 구현은 작성이 쉽지만 반복 구현 대비 시간과 메모리를 더 쓰므로, 1e6 이상 규모에서는 반복 구현으로 바꾸는 이득이 측정으로 확인되었다.

실무 함정은 세 가지로 요약된다. 첫째, 구간 표기를 반열린으로 통일하지 않으면 경계 오류가 난다. 둘째, 2-BIT의 B2나 lazy의 v × 길이에서 long 오버플로가 가능하므로 값 상한을 먼저 계산하거나 모듈러 연산을 일관되게 적용한다. 셋째, lazy의 push 누락은 작은 입력에서는 우연히 맞다가 큰 입력에서 터지므로, 반드시 작은 n과 경계 조건을 포함한 무작위 대조 테스트를 CI에 넣어 둔다. 이 문서의 테스트는 그 최소 형태이며, 오버플로 경계와 비가환 태그(대입+덧셈)는 후속 과제로 남긴다.

## 참고

- Peter M. Fenwick, "A New Data Structure for Cumulative Frequency Tables", Software: Practice and Experience 24(3), 1994.
- Thomas H. Cormen 외, Introduction to Algorithms (4th ed.), MIT Press, Chapter 14 Augmenting Data Structures.
- AtCoder Library 공식 문서: lazy_segtree, fenwick_tree (atcoder.github.io/ac-library).
- Oracle, Java SE 21 Language Specification, Chapter 15.19 Shift Operators 및 Chapter 4.2 Integral Types (오버플로 의미론).
- OpenJDK Code Tools, JMH (Java Microbenchmark Harness) 공식 문서 (워밍업과 마이크로벤치마크 방법론).
