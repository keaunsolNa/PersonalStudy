Notion 원본: https://app.notion.com/p/3e65a06fd6d38157abdbe176dbcaa78a

# 확률적 자료구조 심화: Count-Min Sketch와 HyperLogLog의 오차 경계 및 실무 적용

> 2026-09-25 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- Count-Min Sketch의 해시 충돌(과대추정)이 발생하는 구조적 이유와 오차 상한 공식을 유도한다
- HyperLogLog가 선행 0의 개수로 카디널리티를 추정하는 원리와 조화평균 보정을 설명한다
- 두 자료구조의 정확한 자료구조(HashMap, HashSet) 대비 메모리 절감 폭을 정량적으로 비교한다
- Redis의 `PFADD`/`PFCOUNT`와 Java 커스텀 구현 관점에서 실무 적용 지점을 판단한다

## 1. 왜 근사(approximate) 자료구조가 필요한가

정확한 카디널리티 계산(`SELECT COUNT(DISTINCT user_id)`)이나 정확한 빈도 계산(`HashMap<String, Integer>`로 단어별 등장 횟수 집계)는 데이터 규모가 수억~수십억 건으로 커지면 메모리 요구량이 선형으로 증가해 단일 서버 메모리로는 감당할 수 없는 지점에 도달한다. 예를 들어 하루 방문자 1억 명의 순 방문자 수를 정확히 세려면 사용자 ID 각각을 저장하는 `HashSet<String>`이 필요하고, 이는 UUID 기준으로도 수 GB의 메모리를 요구한다. 확률적 자료구조는 "정확도를 수 %대로 희생하는 대신 메모리를 수백~수천 배 절감"하는 트레이드오프를 택한다.

## 2. Count-Min Sketch: 구조와 동작

Count-Min Sketch(CMS)는 항목별 등장 빈도를 근사하는 자료구조로, `d`개의 해시 함수와 `w`개의 버켓을 가진 `d × w` 크기의 2차원 카운터 배열로 구성된다.

**삽입**: 항목 `x`가 들어오면 `d`개의 해시 함수 `h_1, ..., h_d`로 각각 하나의 열 인덱스를 계산하고, 해당하는 `d`개의 카운터를 모두 1씩 증가시킨다.

```
h_1(x) = 3  →  table[0][3] += 1
h_2(x) = 7  →  table[1][7] += 1
h_3(x) = 1  →  table[2][1] += 1
```

**조회**: 항목 `x`의 추정 빈도는 `d`개의 해시로 얻은 카운터 값 중 **최솟값**이다.

```
estimate(x) = min(table[0][h_1(x)], table[1][h_2(x)], table[2][h_3(x)])
```

최솟값을 취하는 이유가 핵심이다. 여러 항목이 같은 버켓에 해시 충돌을 일으키면 해당 버켓의 카운터는 실제보다 **항상 크게만** 증가한다(감소는 없다). 따라서 `d`개 행 중 충돌이 없었던 행이 하나라도 있다면 그 값이 정확한 빈도이고, 나머지 충돌이 있었던 행의 값은 그보다 크다. 최솟값을 취하면 충돌의 영향을 최소화하면서 "추정값은 항상 실제값 이상"이라는 보수적 상한을 보장한다.

## 3. 오차 경계의 수학적 유도

CMS의 오차는 파라미터 `ε`(허용 오차율)와 `δ`(신뢰도)로 제어된다. 폭 `w = ⌈e/ε⌉`, 깊이 `d = ⌈ln(1/δ)⌉`로 설정하면, 다음이 보장된다.

```
P( estimate(x) ≤ true_count(x) + ε * N ) ≥ 1 - δ
```

여기서 `N`은 전체 삽입 횟수의 합이다. 즉 "확률 `1-δ` 이상으로, 추정 오차가 전체 스트림 크기의 `ε`배를 넘지 않는다"는 뜻이다. 예를 들어 `ε = 0.001`(0.1% 오차), `δ = 0.01`(99% 신뢰도)를 원하면 `w = ⌈e/0.001⌉ ≈ 2719`, `d = ⌈ln(100)⌉ ≈ 5`이므로 `5 × 2719 = 13,595`개의 카운터(정수형 기준 약 54KB, 64비트 기준 약 109KB)만으로 수십억 건 스트림에서도 이 오차 보장을 유지한다. 동일한 정확도를 `HashMap`으로 구현하려면 유니크 항목 수에 비례해 메모리가 증가하므로, 유니크 항목이 많을수록 CMS의 상대적 이점이 커진다.

```java
public class CountMinSketch {
    private final int[][] table;
    private final int depth;
    private final int width;
    private final int[] seeds;

    public CountMinSketch(double epsilon, double delta) {
        this.width = (int) Math.ceil(Math.E / epsilon);
        this.depth = (int) Math.ceil(Math.log(1 / delta));
        this.table = new int[depth][width];
        this.seeds = new int[depth];
        Random random = new Random(42);
        for (int i = 0; i < depth; i++) seeds[i] = random.nextInt();
    }

    public void add(String item) {
        for (int i = 0; i < depth; i++) {
            int hash = hash(item, seeds[i]) % width;
            table[i][hash]++;
        }
    }

    public int estimate(String item) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < depth; i++) {
            int hash = hash(item, seeds[i]) % width;
            min = Math.min(min, table[i][hash]);
        }
        return min;
    }

    private int hash(String item, int seed) {
        return Math.abs((item.hashCode() ^ seed) * 0x9E3779B1);
    }
}
```

## 4. HyperLogLog: 선행 0 개수로 카디널리티 추정

HyperLogLog(HLL)는 CMS와 전혀 다른 문제, 즉 **유니크 항목 수(카디널리티) 추정**을 해결한다. 핵심 관찰은 "균등 분포된 무작위 비트열에서 선행 0(leading zero)이 `k`개 연속으로 나올 확률은 `1/2^k`이므로, 관측된 최대 선행 0 개수가 크다는 것은 그만큼 많은 유니크 항목을 해시했다는 증거"라는 통계적 성질이다.

```
해시값 예시 (32비트):  0001 0110 ...   → 선행 0이 3개
                       0000 0001 ...   → 선행 0이 7개  (더 드물 패턴 → 유니크 항목이 많다는 신호)
```

단일 최대값만 쓰면 분산이 너무 커서 실용적이지 않으므로, HLL은 해시값의 앞 `b`비트를 버켓 인덱스로 사용해 `2^b`개의 레지스터로 값을 분산시킨 뒤(스트림 분할, stochastic averaging), 각 레지스터가 관측한 "선행 0 최대 개수 + 1"을 저장한다. 최종 카디널리티는 이 `2^b`개 레지스터 값들의 **조화평균(harmonic mean)**에 보정 상수 `α_m`을 곱해 계산한다.

```
E = α_m * m^2 / Σ(2^(-register[j]))     (m = 2^b, 레지스터 개수)
```

조화평균을 쓰는 이유는 산술평균과 달리 이상치(비정상적으로 큰 레지스터 값)에 덜 민감해, 일부 레지스터가 우연히 큰 값을 가져도 전체 추정치가 왜곡되지 않기 때문이다. Redis의 HLL 구현은 레지스터당 6비트를 사용하고 `m = 16384`(2^14) 레지스터를 기본으로 사용해, 표준오차 약 0.81%로 카디널리티를 추정하면서 자료구조 전체 크기를 약 12KB로 고정한다 — 유니크 항목이 100건이든 10억 건이든 메모리 사용량이 동일하다는 점이 HLL의 가장 강력한 실무적 장점이다.

## 5. Redis에서의 실무 사용

```bash
# 방문자 ID를 HLL에 추가 (실제 ID를 저장하지 않고 레지스터만 갱신)
PFADD daily_visitors:2026-09-25 user_1001 user_1002 user_1003

# 근사 유니크 카운트 조회
PFCOUNT daily_visitors:2026-09-25
# (integer) 3

# 여러 날짜의 HLL을 병합해 주간 유니크 방문자 근사치 계산 (합집합 연산)
PFMERGE weekly_visitors daily_visitors:2026-09-19 daily_visitors:2026-09-20 \
                        daily_visitors:2026-09-21 daily_visitors:2026-09-22 \
                        daily_visitors:2026-09-23 daily_visitors:2026-09-24 \
                        daily_visitors:2026-09-25
PFCOUNT weekly_visitors
```

`PFMERGE`가 특히 강력한 이유는, HLL의 레지스터별 최대값 연산이 합집합에 대해 **결합법칙이 성립**하기 때문이다. 즉 일별로 HLL을 미리 계산해 두면, 주간/월간 유니크 방문자 수를 원본 데이터를 다시 스캔하지 않고 레지스터 병합만으로 즉시 얻을 수 있다. 이는 `COUNT(DISTINCT)`를 매번 원본 로그 테이블에 실행하는 것과 비교해 압도적으로 저렴하다.

## 6. 두 자료구조의 정확한 구조 대비 메모리 절감 비교

| 자료구조 | 정확한 구현 | 확률적 구현 | 규모(1억 유니크 항목 기준) 메모리 |
|---|---|---|---|
| 카디널리티 카운트 | `HashSet<String>` | HyperLogLog | 수 GB → 약 12KB (수십만 배 절감) |
| 항목별 빈도 카운트 | `HashMap<String, Integer>` | Count-Min Sketch | 유니크 키 수에 비례 → 수십~수백 KB(오차율에 비례, 유니크 키 수와 무관) |
| 집합 멤버십 확인 | `HashSet<String>` | Bloom Filter | 수 GB → 항목당 약 1.2바이트(오차율 1% 기준) |

Bloom Filter는 이번 주제의 범위 밖이지만, "멤버십 확인"이라는 세 번째 대표적 확률적 자료구조로서 CMS·HLL과 함께 언급할 가치가 있다. 세 구조 모두 "해시 함수의 충돌을 통계적으로 다뤄 정확도와 메모리를 교환한다"는 공통된 설계 철학을 공유한다.

## 7. 오차가 누적되는 실패 시나리오

확률적 자료구조를 도입할 때 흔히 간과하는 함정은 "오차율이 항상 독립적으로 작다"고 가정하는 것이다. 예를 들어 CMS로 여러 시간대별 빈도를 각각 추정한 뒤 이를 합산해 일별 빈도를 구하면, 개별 추정치의 과대추정 오차가 누적되어 최종 오차가 더 커질 수 있다. 이런 경우에는 시간대별로 별도 CMS를 유지하는 대신 하루 전체를 커버하는 단일 CMS를 사용하거나, 병합이 필요한 경우 CMS의 폭(`w`)과 깊이(`d`)를 동일하게 맞춰 원소별로 카운터를 더하는(`table[i][j] += other.table[i][j]`) 방식으로 병합해야 오차 보장이 유지된다. HLL 역시 서로 다른 정밀도(`b` 값)로 생성된 두 HLL을 `PFMERGE`하면 낮은 정밀도 쪽에 맞춰 정확도가 저하되므로, 병합 대상 HLL들의 정밀도 파라미터를 통일해야 한다.

## 8. 도입 판단 기준

확률적 자료구조는 "정확한 값이 반드시 필요한가"라는 질문에 답이 "아니오"일 때만 도입해야 한다. 실시간 대시보드의 DAU/WAU 표시, 이상 탐지를 위한 트래픽 스파이크 감지, 캐시 히트율 추정처럼 근사치로도 의사결정에 충분한 지표에는 HLL과 CMS가 메모리와 연산 비용을 극적으로 줄여준다. 반면 정산, 과금, 재고처럼 오차가 곳 금전적 손실이나 법적 책임으로 이어지는 영역에는 확률적 자료구조를 절대 사용해서는 안 되며, 이 경계를 명확히 설계 문서에 남겨두는 것이 실무에서 발생하는 오남용을 예방하는 가장 효과적인 방법이다.

## 9. Bloom Filter와의 구조적 비교

CMS와 HLL을 다루었으니, 같은 계열의 Bloom Filter가 어떻게 다른 문제를 푸는지 짚어볼 필요가 있다. Bloom Filter는 "이 항목이 집합에 존재하는가"라는 멤버십 질의에 답하는 자료구조로, `m`비트 배열과 `k`개의 해시 함수로 구성된다. 삽입 시 `k`개의 해시로 얻은 비트를 모두 1로 설정하고, 조회 시 `k`개의 비트가 모두 1이면 "존재할 수도 있다(maybe)", 하나라도 0이면 "확실히 존재하지 않는다(definitely not)"고 답한다.

```java
public class BloomFilter {
    private final BitSet bits;
    private final int size;
    private final int hashCount;

    public BloomFilter(int expectedItems, double falsePositiveRate) {
        this.size = (int) Math.ceil(-(expectedItems * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
        this.hashCount = (int) Math.round((size / (double) expectedItems) * Math.log(2));
        this.bits = new BitSet(size);
    }

    public void add(String item) {
        for (int i = 0; i < hashCount; i++) {
            bits.set(Math.abs(hash(item, i)) % size);
        }
    }

    public boolean mightContain(String item) {
        for (int i = 0; i < hashCount; i++) {
            if (!bits.get(Math.abs(hash(item, i)) % size)) return false;
        }
        return true; // 위양성(false positive) 가능
    }

    private int hash(String item, int seed) {
        return (item.hashCode() * 31 + seed) ^ (item.hashCode() >>> 16);
    }
}
```

Bloom Filter는 CMS·HLL과 달리 **거짓 음성(false negative)이 절대 없다**는 성질을 갖는다. 즉 "없다"고 답하면 100% 확실히 없는 것이고, "있을 수 있다"고 답할 때만 오차(거짓 양성)가 발생한다. 이 성질 때문에 실무에서는 "비싼 조회를 하기 전에 먼저 거르는 사전 필터" 용도로 널리 쓰인다. 대표적으로 LSM-Tree 기반 스토리지 엔진(Cassandra, RocksDB, LevelDB)은 디스크의 각 SSTable마다 Bloom Filter를 두어, 키가 해당 SSTable에 없다는 것이 Bloom Filter로 확인되면 디스크 I/O 자체를 생략한다. CDN이나 캐시 계층에서 "한 번도 요청되지 않은 콘텐츠"를 캐싱 대상에서 제외하는 캐시 오염 방지(cache admission) 용도로도 쓰인다.

세 자료구조를 나란히 놓으면 각각이 푸는 문제가 명확히 구분된다. Bloom Filter는 멤버십(존재 여부), Count-Min Sketch는 빈도(몇 번 등장했는가), HyperLogLog는 카디널리티(몇 개의 유니크 항목이 있는가)를 근사한다. 세 문제를 혼동해 잘못된 자료구조를 적용하면(예: 빈도 추정에 Bloom Filter를 억지로 활용) 원하는 정확도를 전혀 얻지 못하므로, 설계 단계에서 "지금 풀려는 질문이 정확히 무엇인가"를 먼저 명확히 하는 것이 중요하다.

## 참고

- Cormode & Muthukrishnan, "An Improved Data Stream Summary: The Count-Min Sketch and its Applications" (2005)
- Flajolet et al., "HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm" (2007)
- Redis 공식 문서, "HyperLogLog" (PFADD/PFCOUNT/PFMERGE)
- Redis 공식 블로그, "Redis new data structure: the HyperLogLog"
