Notion 원본: https://www.notion.so/3615a06fd6d381e290f6d5bc331b82d1

# HyperLogLog Sketch와 Probabilistic Cardinality 추정 — Linear Counting / HLL++ / Merge

> 2026-05-16 신규 주제 · 확장 대상: CS (확률적 자료구조)

## 학습 목표

- 카디널리티 추정 문제와 정확 카운팅(`HashSet`) 의 메모리 한계, 확률적 sketch 의 필요성을 설명한다
- HyperLogLog 의 leading-zero counting 직관과 harmonic mean 기반 추정자 유도를 단계별로 구현한다
- Linear Counting 보정과 HLL++ 의 sparse representation, bias correction lookup table 의 동작을 코드로 확인한다
- Redis PFCOUNT / BigQuery APPROX_COUNT_DISTINCT 의 정확도 ε ≈ 1.04 / √m 와 m 선택의 트레이드오프를 정량 분석한다

## 1. 정확 카운팅의 메모리 비용과 한계

서로 다른 사용자 수, 유니크 검색어 수 같은 *카디널리티* 측정은 정확하게 하려면 본 적 있는 요소를 모두 기억해야 한다. 1억 unique user 를 `HashSet<UUID>` 로 저장하면 약 64bit × (1.5 ~ 2.0 로드팩터) × 1억 = 약 16GB 메모리. 실시간 스트리밍 분석에서는 절대 감당 불가.

확률적 sketch 는 *고정 메모리*로 *추정값*을 제공한다. HLL 의 경우 단지 12KB(약) 의 메모리로 *1조* 카디널리티까지 평균 오차 약 0.81% 로 추정 가능. 트레이드오프는 정확도 손실이지만, 분석 대시보드 / A/B 테스트 / 광고 도달 측정 등에서는 충분히 받아들일 수 있다.

## 2. 핵심 직관 — Leading Zero 개수의 정보 가치

대상을 균등 분포 해시 함수 h(x) ∈ {0,1}^L 로 변환했다고 하자. 이 해시값의 *맨 앞 0 의 개수*(leading zeros) k 가 큰 값일수록 그런 해시가 나올 확률은 2^-k 로 낮다. 즉 k 가 큰 값이 *한 번이라도 관측*됐다면 서로 다른 값을 약 2^k 개 정도 봤을 가능성이 높다.

이 직관을 그대로 estimator 로 쓰면 분산이 크다. 한 hash 의 운에 의존하기 때문이다. 대신 sketch 를 *m 개의 bucket* 으로 나누고, 각 bucket 마다 *지금까지 본 leading zero 최대값* M[i] 를 기록한 뒤, 평균을 내면 분산이 √m 만큼 줄어든다.

```
m = 2^p, p = 14 라면 m = 16384 bucket
한 입력 x:
  h = hash64(x)
  i = h 의 상위 p bit (bucket index)
  w = h 의 나머지 bit
  M[i] = max(M[i], 1 + clz(w))
```

m 개 bucket 의 최대값을 모은 뒤, harmonic mean 기반 추정자:

```
E = α_m * m^2 * (Σ_i 2^-M[i])^-1
```

여기서 α_m 은 m 에 따른 보정 상수(예: α_16384 ≈ 0.7213/(1 + 1.079/m)).

## 3. Python 으로 단계별 구현

```python
import math
import hashlib

class HyperLogLog:
    def __init__(self, p=14):
        self.p = p
        self.m = 1 << p
        self.M = [0] * self.m
        if self.m == 16:
            self.alpha = 0.673
        elif self.m == 32:
            self.alpha = 0.697
        elif self.m == 64:
            self.alpha = 0.709
        else:
            self.alpha = 0.7213 / (1 + 1.079 / self.m)

    def _hash64(self, x: bytes) -> int:
        return int.from_bytes(hashlib.sha1(x).digest()[:8], 'big')

    def add(self, x: bytes) -> None:
        h = self._hash64(x)
        idx = h >> (64 - self.p)
        w = (h << self.p) & ((1 << 64) - 1)
        if w == 0:
            rank = 64 - self.p + 1
        else:
            rank = 1
            while (w & (1 << 63)) == 0:
                w <<= 1
                w &= (1 << 64) - 1
                rank += 1
        if rank > self.M[idx]:
            self.M[idx] = rank

    def count(self) -> int:
        z = sum(2.0 ** -m for m in self.M)
        e = self.alpha * self.m * self.m / z
        if e <= 2.5 * self.m:
            v = self.M.count(0)
            if v != 0:
                e = self.m * math.log(self.m / v)
        return int(round(e))
```

위 구현으로 1,000,000 개 unique 항목을 넣어 `count()` 를 호출하면 약 ±0.81% 오차로 추정값을 얻는다.

## 4. Linear Counting 보정의 이유

소규모 카디널리티(예: m 의 5배 이하) 에서는 HLL 의 추정자가 *과대* 평가하는 경향이 있다. bucket 의 다수가 비어 있는데 비어있는 bucket 은 `M[i] = 0` 으로 카운트되며 식의 `2^-0 = 1` 이 누적된다. 이를 보정하기 위해 *비어있는 bucket 수 v* 를 활용한 Linear Counting 추정자로 대체한다.

```
E_LC = m * ln(m / v)
```

이는 *coupon collector* 문제의 역수. threshold (전환 시점) 가 m 의 약 2.5배인 이유는 두 추정자의 *상대 분산* 이 그 지점에서 교차하기 때문이다.

## 5. HLL++ 의 개선점

| 개선점 | 내용 |
|---|---|
| 64bit hash | 32bit 보다 collision 확률 감소, large range correction 불필요 |
| Sparse representation | 작은 카디널리티에서 bucket 배열 대신 (index, rank) 페어 리스트로 저장 — 메모리 절감 |
| Bias correction | 작은 카디널리티 영역의 bias 를 실측 lookup table 로 보정 |

Sparse representation 이 특히 실용적이다. 카디널리티가 작을 때(예: m 의 5% 이하만 채워짐) 12KB 를 통째로 할당하는 게 낭비. 대신 `(bucket_idx, rank)` 페어를 정렬 리스트로 유지하다가 임계값을 넘으면 dense 로 전환.

## 6. Merge 가능성 — 분산 환경에서의 활용

```python
def merge(self, other: 'HyperLogLog') -> 'HyperLogLog':
    assert self.p == other.p
    new = HyperLogLog(self.p)
    new.M = [max(a, b) for a, b in zip(self.M, other.M)]
    return new
```

이는 *associative* 하고 *commutative* 다. MapReduce / Spark / Flink 의 reduce 연산자로 자연스럽게 표현 가능.

```sql
-- BigQuery
INSERT INTO daily_sketches
SELECT date, HLL_COUNT.INIT(user_id, 14) AS sketch FROM events GROUP BY date;

SELECT HLL_COUNT.MERGE(sketch) FROM daily_sketches
WHERE date BETWEEN '2026-05-09' AND '2026-05-15';
```

## 7. Redis HyperLogLog 의 구현 디테일

Redis 의 `PFADD` / `PFCOUNT` / `PFMERGE` 는 p=14, 즉 16384 bucket 을 사용한다. 각 bucket 은 6bit 로 leading-zero rank 를 저장. 16384 × 6 / 8 = 12288 byte = 12KB. Redis 는 sparse 와 dense 두 표현을 자동 전환한다.

`PFCOUNT key1 key2` 는 *내부적으로* PFMERGE 를 거쳐 추정하므로 매우 빠르다(O(m)).

표준 오차 σ ≈ 1.04 / √m. p=14 면 σ ≈ 0.81%.

## 8. m 선택과 정확도 / 메모리 트레이드오프

| p | m | 메모리(byte) | 표준 오차 |
|---|---|---|---|
| 4 | 16 | 12 | 26% |
| 10 | 1024 | 768 | 3.25% |
| 12 | 4096 | 3072 | 1.625% |
| 14 | 16384 | 12288 | 0.8125% |
| 16 | 65536 | 49152 | 0.40625% |
| 18 | 262144 | 196608 | 0.203% |

분석 대시보드라면 p=14 가 sweet spot. 광고 reach 측정처럼 더 정확이 필요하면 p=16~18.

## 9. 관련 sketch 와 비교

| sketch | 추정 대상 | 메모리 | 보너스 |
|---|---|---|---|
| HyperLogLog | 카디널리티 | 12KB | mergeable, 빠른 PFCOUNT |
| Linear Counting | 카디널리티(소규모) | O(m) | HLL 의 작은 범위 보정용 |
| Bloom Filter | membership | k bit / item | 가입 여부 yes/no |
| Cuckoo Filter | membership + deletion | 비슷 | 삭제 가능, false positive 낮음 |
| Count-Min Sketch | frequency 추정 | width × depth | top-k heavy hitter 추적 |
| t-digest | quantile 추정 | O(δ^-1 log εn) | p99 / 분위수 |

HLL 은 카디널리티 *전용*. top user 역추적 불가.

운영 사례: 광고 reach 측정에 HLL p=15 (1.15% σ), 1일 캠페인 1000개 × 평균 1억 reach. 캠페인당 32KB.

## 10. 시간 윈도우 카디널리티 — Sliding HLL 과 운영 패턴

표준 HLL 은 *지금까지 본 전체*의 카디널리티만 추정. sliding window 질의에 적용 불가. 해결책 3가지:

1. **Tumbling buckets**: 5분 단위 별도 HLL, PFMERGE 합산
2. **Time-decaying HLL**: (rank, last_update_time) 페어
3. **Stream processor windowed aggregation**: Flink / Spark

```sql
-- ClickHouse 예시
CREATE TABLE events_5min (
  ts        DateTime,
  user_hll  AggregateFunction(uniqHLL12, UUID)
) ENGINE = AggregatingMergeTree
ORDER BY ts;

SELECT uniqHLL12Merge(user_hll) FROM events_5min
WHERE ts >= now() - INTERVAL 1 HOUR;
```

추가 함정: 너무 짧은 윈도우는 Linear Counting 영역. 정확 카운팅이 더 효율적일 수 있음.

## 참고

- Flajolet, Fusy, Gandouet, Meunier — "HyperLogLog" (DMTCS 2007)
- Heule, Nunkesser, Hall — "HyperLogLog in Practice" (EDBT 2013) — HLL++ 원논문
- Redis Documentation — HyperLogLog (https://redis.io/docs/data-types/probabilistic/hyperloglogs/)
- BigQuery Documentation — HLL_COUNT functions
- Andrei Broder, sketch theory 일반론
