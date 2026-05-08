Notion 원본: https://www.notion.so/35a5a06fd6d381fdba72fe9ca3a68c95

# Bloom Filter와 Counting Bloom Filter 확률적 자료구조

> 2026-05-08 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- Bloom Filter 의 false positive 확률 공식과 최적 hash 함수 개수 결정 과정을 이해한다
- Counting Bloom Filter 가 일반 Bloom Filter 의 어떤 한계를 어떤 비용으로 푸는지 정확히 구분한다
- Bloom Filter 가 Cassandra, RocksDB, CDN cache, network anti-spoofing 같은 시스템에서 어떻게 쓰이는지 사례별로 안다
- Cuckoo Filter 같은 후속 자료구조와의 trade-off 를 비교 평가한다

## 1. Bloom Filter 의 동작 원리

Bloom Filter 는 1970 년 Burton Bloom 이 제안한 공간 효율적인 확률적 set membership 자료구조다.

`m` 비트의 비트 배열과 `k` 개의 독립적인 hash 함수를 사용한다. insert(x):

```
for i in 1..k:
    bit[h_i(x) mod m] = 1
```

contains(x):

```
for i in 1..k:
    if bit[h_i(x) mod m] == 0: return False
return True
```

핵심은 **false negative 가 절대 발생하지 않는다**는 점이다.

## 2. 최적 파라미터

```
p ≈ (1 - e^(-kn/m))^k
k_opt = (m / n) * ln 2 ≈ 0.693 * (m / n)
p = (1/2)^k_opt = (0.6185)^(m/n)
```

| bits/item (m/n) | k_opt | false positive p |
|---|---|---|
| 6 | 4 | 0.0561 |
| 8 | 6 | 0.0216 |
| 10 | 7 | 0.0082 |
| 14 | 10 | 0.0010 |
| 20 | 14 | 0.000067 |

cassandra, RocksDB 가 사용하는 `m/n = 10`, `k = 7` 이 사실상 표준이다.

## 3. hash 함수 — double hashing 트릭

```
h_i(x) = h1(x) + i * h2(x)   (mod m), for i = 0..k-1
```

```python
import mmh3, math

class BloomFilter:
    def __init__(self, n, p):
        self.m = math.ceil(-(n * math.log(p)) / (math.log(2) ** 2))
        self.k = max(1, round((self.m / n) * math.log(2)))
        self.bits = bytearray((self.m + 7) // 8)

    def _idx(self, item):
        h1, h2 = mmh3.hash64(item, signed=False)
        for i in range(self.k):
            yield (h1 + i * h2) % self.m

    def add(self, item):
        for idx in self._idx(item):
            self.bits[idx >> 3] |= (1 << (idx & 7))

    def __contains__(self, item):
        return all(self.bits[idx >> 3] & (1 << (idx & 7)) for idx in self._idx(item))
```

## 4. Bloom Filter 의 한계 — 삭제 불가, Union 가능, Intersection 불완전

- **삭제 불가**: 비트를 0 으로 되돌리면 다른 아이템의 contains 결과까지 false negative 로 만들 수 있음
- **Union(합집합)**: 같은 m, k, hash 함수를 쓰면 비트 OR 로 union 가능
- **Intersection(교집합)**: 비트 AND 로 근사 가능하지만 정확하지 않음
- **Cardinality 추정**: |S| ≈ -m/k * ln(1 - X/m)

## 5. Counting Bloom Filter — 삭제 지원의 비용

```
insert(x): for i in 1..k: counter[h_i(x)] += 1
delete(x): for i in 1..k: counter[h_i(x)] -= 1
contains(x): for i in 1..k: counter[h_i(x)] > 0
```

`m` 칸 각각이 4비트 카운터면 메모리 사용량은 일반 Bloom Filter 의 4배다.

단점:

- 카운터 overflow: 카운터가 최대값(4비트면 15)에 도달하면 false negative 가 발생할 수 있음
- 들어오지 않은 아이템을 delete 하면 다른 아이템이 false negative 를 일으킴

## 6. 실제 시스템에서의 활용

| 시스템 | 사용 위치 | 효과 |
|---|---|---|
| Cassandra | SSTable 별 row key Bloom Filter | 디스크 IO 를 60~90% 감소 |
| RocksDB | SST file 의 leaf block 단위 | 같은 원리. Ribbon Filter 도 있음 |
| HBase | StoreFile 별 row/qualifier filter | get/scan 전 SSTable 후보 줄이기 |
| Bitcoin SPV | BIP-37 Bloom Filter | 매칭 트랜잭션만 회신 |
| CDN admission | TinyLFU frequency sketch | 한 번만 본 콘텐츠 admit 거부 |
| Web crawler | 이미 방문한 URL 검사 | 수십억 개 URL 도 수 GB 안에서 처리 |

## 7. Cuckoo Filter — Bloom 의 대안

- 삭제 지원 (CBF 처럼 카운터 없이도 가능)
- 같은 false positive 율 기준 더 작은 메모리(약 7~10% 절약)
- 캐시 친화적 lookup

단점: 채움률이 95% 를 넘으면 insert 가 실패하기 시작, 같은 아이템을 여러 번 삽입하면 한도(보통 7) 가 정해져 있음.

## 8. 현장 적용 체크리스트

- 예상 아이템 수 `n` 의 P95
- 허용 false positive 율 `p`
- hash 알고리즘 — non-cryptographic, 64비트 출력 (xxHash, MurmurHash3)
- 모니터링 — fill rate 가 50% 를 넘으면 rebuild 트리거
- 스트리밍 환경에서는 **rolling Bloom Filter** 패턴 계산

## 참고

- Burton H. Bloom, 1970, "Space/Time Trade-offs in Hash Coding with Allowable Errors"
- Kirsch & Mitzenmacher, 2008, "Less Hashing, Same Performance"
- Fan et al., 2014, "Cuckoo Filter: Practically Better Than Bloom"
- Cassandra 공식 문서 — Bloom Filter 설정
- RocksDB Wiki — Full Filter / Ribbon Filter
