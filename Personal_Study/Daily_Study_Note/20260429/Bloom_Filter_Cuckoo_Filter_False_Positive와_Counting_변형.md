Notion 원본: https://www.notion.so/3515a06fd6d381b98c38cdc5f61f7841

# Bloom Filter / Cuckoo Filter — False Positive 분석과 Counting 변형

> 2026-04-29 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- Bloom Filter 의 false positive rate(FPR) 공식을 유도하고 적정 m, k 값 계산
- 표준 Bloom Filter 가 삭제를 지원하지 못하는 이유와 Counting Bloom Filter 의 트레이드오프
- Cuckoo Filter 의 부분키 해시(partial-key cuckoo hashing) 와 삭제 가능성 메커니즘
- LSM 엔진의 SSTable / Redis BF.RESERVE 등 실전 적용 사례에서의 파라미터 선택

## 1. 멤버십 쿼리의 비용

대용량 키-값 저장소에서 "이 키가 존재하는가?" 라는 질문은 빈번하다. 디스크 기반 인덱스(B-tree, LSM-tree) 에서 negative lookup 은 한 번 이상의 디스크 IO 를 동반하므로, 캐시되지 않은 키에 대한 조회는 hit 보다 느리다. 이 문제를 정확한 자료구조(HashSet) 로 푸는 대신, 메모리를 적게 쓰면서 false positive 를 일부 허용하는 **확률적 자료구조(probabilistic data structure)** 가 효과적이다.

확률적 멤버십 자료구조의 공통 정의는 다음과 같다.

| 연산 | 정확성 |
| --- | --- |
| `add(x)` | 항상 성공 |
| `query(x)` | x 가 들어간 적 있으면 항상 true |
| `query(x)` | x 가 들어간 적 없어도 일정 확률로 true (false positive) |
| `query(x)` | false negative 는 절대 없음 |

이 비대칭성이 실용성을 만든다. "BF 가 false 면 디스크 안 봐도 됨" 이라는 단방향 결론으로 수많은 IO 를 절약한다.

## 2. Bloom Filter — 비트 배열과 k 개의 해시

표준 Bloom Filter 는 길이 m 의 비트 배열과 독립적인 k 개의 해시 함수로 정의된다.

```
초기 상태: bits = [0, 0, ..., 0]  (length m)

add(x):
  for i in 1..k:
    bits[h_i(x) mod m] = 1

query(x):
  for i in 1..k:
    if bits[h_i(x) mod m] == 0:
      return false
  return true
```

`add` 와 `query` 모두 O(k) 시간이며, k 는 보통 4~10 사이의 작은 상수다. 직관적으로 m 이 클수록, k 가 너무 크지도 너무 작지도 않을 때 false positive 가 최소화된다.

## 3. False Positive Rate 공식 유도

n 개의 원소를 m 비트의 BF 에 넣었을 때, 임의 비트가 여전히 0 일 확률은 다음과 같다.

```
P(특정 비트가 0) = (1 - 1/m)^(k * n) ≈ exp(-k * n / m)
```

따라서 비트가 1 일 확률은 `1 - exp(-k * n / m)`. query(x) 의 false positive 는 k 개 해시가 모두 1 인 비트를 가리킬 확률이다.

```
FPR ≈ (1 - exp(-k * n / m))^k
```

원하는 FPR `p` 에 대해 m, k 를 푸는 식.

```
m = -(n * ln p) / (ln 2)^2
k = (m / n) * ln 2 ≈ 0.693 * (m / n)
```

n=1,000,000 / p=0.01 (1%) 으로 풀면 m ≈ 9,585,059 비트(≈ 1.14 MB), k ≈ 7. 1억개 원소 기준이면 약 114 MB 의 메모리로 1% FPR 을 달성한다 — 같은 키를 HashSet 으로 저장할 때(약 4~5 GB 이상) 와 비교하면 30배 이상 절약이다.

| 원소 수 n | 목표 FPR | m (비트) | m (MB) | k |
| --- | --- | --- | --- | --- |
| 1M | 1% | 9.6M | 1.14 MB | 7 |
| 1M | 0.1% | 14.4M | 1.71 MB | 10 |
| 100M | 1% | 958.5M | 114 MB | 7 |
| 100M | 0.01% | 1.92B | 228 MB | 14 |

FPR 이 10배 작아질 때 m 은 선형이 아니라 `log(1/p)` 비례로 증가한다. 즉 FPR 을 1% 에서 0.1% 로 줄이는 데 메모리는 1.5배만 더 든다.

## 4. 표준 BF 의 삭제 불가능성

`add(x)` 가 비트를 1 로 만들지만, 같은 비트가 다른 원소 y, z 의 비트와 겹칠 수 있다. 따라서 단순히 `bits[h_i(x)] = 0` 으로 삭제하면 y, z 의 멤버십도 깨진다. false negative 가 발생할 위험은 BF 의 정의에 위배된다.

해법은 비트 대신 카운터를 쓰는 **Counting Bloom Filter** 다.

```
counters: int[m]   // 보통 4비트로 충분

add(x):
  for i in 1..k: counters[h_i(x)] += 1

remove(x):
  for i in 1..k: counters[h_i(x)] -= 1

query(x):
  for i in 1..k:
    if counters[h_i(x)] == 0: return false
  return true
```

4비트 카운터는 0~15 까지 표현하며, 같은 위치에 16번 이상 add 가 누적되면 saturation 으로 카운트가 멈춘다(또는 overflow 후 일관성 깨짐). 일반적인 워크로드에서 4비트면 충분하다는 것은 [Fan et al., 2000] 의 분석에서 다뤄졌다.

CBF 의 단점은 메모리가 4배(1비트 → 4비트) 라는 점이다. 그래서 삭제가 필요 없는 워크로드(LSM compaction 시 SSTable 별 BF 재구성, 한번 인덱싱된 후 read-only 인 데이터셋) 에서는 표준 BF 를 그대로 쓰고, 삭제가 필수인 워크로드에서는 다음 절의 Cuckoo Filter 가 더 매력적이다.

## 5. Cuckoo Filter — Partial-Key Cuckoo Hashing

Cuckoo Filter 는 [Fan et al., 2014, "Cuckoo Filter: Practically Better Than Bloom"] 가 제안했다. 핵심 아이디어는 BF 처럼 비트 배열을 쓰지 않고, 각 원소의 짧은 fingerprint(예: 8~16 비트) 를 두 개의 후보 버킷 중 하나에 저장한다는 것이다.

```
init:
  table: bucket[NB]   // bucket = fingerprint slots, 보통 4 slot

add(x):
  f = fingerprint(x)        // 짧은 해시
  i1 = hash(x) mod NB
  i2 = (i1 XOR hash(f)) mod NB
  if bucket[i1] has slot: place f, return
  if bucket[i2] has slot: place f, return
  // 둘 다 가득 차 있으면 cuckoo eviction
  i = pick(i1, i2)
  for n in 1..MAX_KICKS:
    swap f with random slot in bucket[i]
    i = i XOR hash(f)
    if bucket[i] has slot: place f, return
  // 실패 — 테이블이 너무 가득 참

query(x):
  f = fingerprint(x)
  i1 = hash(x) mod NB
  i2 = i1 XOR hash(f)
  return bucket[i1].contains(f) or bucket[i2].contains(f)

remove(x):
  f = fingerprint(x); i1, i2 동일
  if bucket[i1].contains(f): remove first match, return
  if bucket[i2].contains(f): remove first match, return
```

여기서 결정적 트릭은 `i2 = i1 XOR hash(f)` 다. 이 관계는 양방향으로 성립해서, 어느 버킷에 fingerprint 가 있는 다른 후보 버킷의 위치를 fingerprint 만으로 계산할 수 있다. cuckoo eviction 중에 원본 키 x 를 다시 알 필요가 없다.

### 5.1 FPR 과 메모리

f 비트의 fingerprint, b 슬롯/bucket 일 때 FPR 의 상한은 다음과 같다.

```
FPR ≤ (2 * b) / 2^f
```

대표 파라미터: f=12, b=4 → FPR ≤ 8 / 4096 ≈ 0.2%. 이 때 한 원소당 메모리는 약 12비트, 표준 BF 가 같은 0.2% 를 달성하는 데 필요한 메모리는 약 13.4비트다 — Cuckoo Filter 가 약간 적게 쓴다. FPR 이 작아질수록 차이는 더 벌어진다(BF 는 k 가 늘어나며 lookup 이 느려지지만 CF 는 항상 두 버킷만 보면 됨).

### 5.2 삭제와 false negative 위험

Cuckoo Filter 는 같은 fingerprint 가 동일 버킷에 2개 이상 들어갈 수 있다(서로 다른 키지만 fingerprint 가 같은 경우). `remove(x)` 는 첫 매치만 지우는데, 만약 (a, b) 가 같은 fingerprint 를 가져 같은 버킷에 둘 다 들어 있고, 사용자가 `remove(b)` 를 호출했는데 a 의 fingerprint 가 먼저 매칭되어 지워지면 a 의 멤버십이 깨진다.

이를 막으려면 사용자가 "정말 add 한 적 있는 key 만 remove" 하도록 호출 책임을 진다. BF 의 삭제 불가능성보다는 운영 비용이 적지만, 무작위 remove 호출이 일관성을 깰 수 있다는 점을 인지해야 한다.

### 5.3 부하율과 삽입 실패

CF 의 적재율(load factor) 은 b=4 일 때 약 95% 까지 안정적이다. 95% 를 넘기 시작하면 cuckoo eviction 이 무한 루프에 가까워져 MAX_KICKS(보통 500) 안에 자리를 못 찾을 가능성이 급격히 높아진다. BF 와 달리 CF 는 "삽입 실패" 라는 상태가 존재한다는 점이 운영 차이다. 일반적으로 capacity 의 90% 에 도달하면 더 큰 테이블로 rebuild 한다.

## 6. 실전 적용

### 6.1 LSM-tree SSTable (RocksDB, LevelDB)

각 SSTable 파일에는 그 파일에 포함된 키를 표현하는 BF 가 함께 저장된다. read 요청이 들어오면 메모리에 캐시된 BF 를 먼저 확인하고, false 면 그 SSTable 은 건너뛴다. 평균 IO 가 절반 이상 줄어들며, RocksDB 기본은 키당 10비트 BF(FPR ≈ 1%) 다. compaction 으로 SSTable 이 새로 만들어질 때마다 BF 도 재구성되므로 삭제가 필요 없어 표준 BF 가 충분하다.

### 6.2 Redis BF.RESERVE / CF.RESERVE

RedisBloom 모듈은 두 자료구조 모두 1급으로 제공한다.

```redis
BF.RESERVE userkeys 0.001 1000000
BF.ADD userkeys "u123"
BF.EXISTS userkeys "u123"   -- 1
BF.EXISTS userkeys "u999"   -- 0 또는 1 (FPR 0.1%)

CF.RESERVE deleted-coupons 1000000
CF.ADD deleted-coupons "C-42"
CF.DEL deleted-coupons "C-42"
```

쿠폰 코드 중복 발급 방지처럼 삭제가 필요한 워크로드는 CF, 단순 cache penetration 차단(존재하지 않는 키에 대한 DB 조회 차단) 은 BF 가 자연스럽다.

### 6.3 Cache Penetration 차단 패턴

악의적 트래픽이 존재하지 않는 PK 로 대량 GET 을 보내면 캐시 미스로 모두 DB 까지 흘러간다. BF 를 캐시 앞에 둬서 "이 PK 가 DB 에 존재한 적 있는가" 를 먼저 확인한다. BF 가 false 면 즉시 404, true 면 캐시 → DB 순으로 간다.

```java
public Optional<Member> findById(Long id) {
    if (!memberBloomFilter.mightContain(id)) {
        return Optional.empty();              // 디스크 IO 없이 응답
    }
    return cache.get(id).or(() -> repository.findById(id));
}
```

BF 는 거짓 양성을 허용하지만 거짓 음성은 없으므로, 진짜 존재하는 ID 가 false 로 차단되는 일은 없다. 신규 멤버 가입 시 BF 에도 add 만 잊지 않으면 일관성이 유지된다.

## 7. 어느 것을 골라야 하나

| 상황 | 추천 |
| --- | --- |
| Read-only / append-only 데이터, 삭제 없음 | 표준 Bloom Filter |
| FPR 매우 낮춤(0.01% 미만) 필요 | Cuckoo Filter (BF 는 k 가 커져 느려짐) |
| 삭제 빈번 + 메모리 4배 허용 | Counting Bloom Filter |
| 삭제 빈번 + 메모리 절약 | Cuckoo Filter (호출자가 add 검증 책임) |
| 적재율 90% 이상 운영 가능성 | Bloom (CF 는 삽입 실패 위험) |

대부분의 데이터베이스 엔진은 표준 BF 를 쓰고, 운영 워크로드(쿠폰, 세션 등) 는 Cuckoo Filter 가 합리적이다. 두 자료구조는 같은 문제를 푸는 다른 트레이드오프이며, 워크로드의 read/write/delete 비율로 선택이 갈린다.

## 참고

- Burton Bloom, "Space/Time Trade-offs in Hash Coding with Allowable Errors" (1970)
- Bin Fan et al., "Cuckoo Filter: Practically Better Than Bloom" (2014)
- Li Fan et al., "Summary Cache: A Scalable Wide-Area Web Cache Sharing Protocol" (Counting BF, 2000)
- Sanjay Ghemawat / Jeff Dean, LevelDB design docs
- RedisBloom 모듈 문서 (https://redis.io/docs/stack/bloom/)
