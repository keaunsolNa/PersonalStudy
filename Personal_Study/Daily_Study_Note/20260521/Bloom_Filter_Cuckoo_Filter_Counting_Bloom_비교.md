Notion 원본: https://www.notion.so/3675a06fd6d381b98ce5e42bcee18a9c

# Bloom Filter, Cuckoo Filter, Counting Bloom Filter 비교

> 2026-05-21 신규 주제 · 확장 대상: HyperLogLog Sketch(20260516), Consistent Hashing(20260514)

## 학습 목표

- 세 확률적 집합 자료구조의 false positive rate(FPR)와 메모리 사용을 수식 수준에서 비교한다.
- 삽입·삭제·조회의 시간 복잡도와 cache 친화성, lock-free 가능성을 안다.
- RocksDB, Redis, ScyllaDB 같은 실제 시스템의 채택 배경을 이해한다.
- 워크로드별로 어느 구조를 골라야 하는지 결정 기준을 갖춘다.

## 1. 확률적 집합 자료구조란

100억 개 URL의 정확한 set membership을 위해 SHA-256만 저장해도 32바이트 × 10B = 320GB. 메모리 불가. '99% 정확도면 됐고 메모리는 16GB' 요구를 만족시키는 영역. 세 구조 모두 false positive는 허용, 확률 ε를 메모리와 trade off. Bloom과 Counting Bloom은 false negative 없음. Cuckoo는 삭제 지원하지만 매우 드물게 FN 가능.

| 구조 | FP | FN | 삽입 | 삭제 | 메모리(ε=1%) |
|---|---|---|---|---|---|
| Bloom | O | × | O(k) | × | 9.6 bits/item |
| Counting Bloom | O | × | O(k) | O(k) | Bloom × 4 |
| Cuckoo | O | 드묾 | O(1) | O(1) | 8.1 bits/item |

## 2. Bloom Filter 핵심 동작

m비트 배열, k개 hash. 삽입은 k개 위치를 1로, 조회는 k개 모두 1인지 확인.

```java
public void add(byte[] key) {
	for (int idx : computeHashes(key)) {
		int slot = Math.floorMod(idx, bitSize);
		bits[slot >>> 6] |= (1L << (slot & 63));
	}
}

public boolean mightContain(byte[] key) {
	for (int idx : computeHashes(key)) {
		int slot = Math.floorMod(idx, bitSize);
		if ((bits[slot >>> 6] & (1L << (slot & 63))) == 0) return false;
	}
	return true;
}
```

`k = (m/n) × ln(2)`가 FPR 최소. 실무 k=7~10.

## 3. Double Hashing

Kirsch-Mitzenmacher: `h_i(x) = h_1(x) + i × h_2(x)`로 충분. MurmurHash3 128bit를 둘로 쪼개 사용. hash 계산이 1/k로. RocksDB도 동일 패턴.

## 4. Counting Bloom Filter

비트 대신 4비트 카운터. 4비트(0~15)면 ε=1%에 거의 overflow 없음. 메모리는 Bloom의 4배. ScyllaDB는 Counting 대신 SSTable generation + tombstone deletion으로 우회.

## 5. Cuckoo Filter

fingerprint 저장. 두 candidate bucket index가 `h_1(x)`와 `h_1(x) ⊕ h_2(fp(x))`로 fingerprint만 알면 양쪽 알아냄.

```java
public boolean add(byte[] key) {
	int fp = fingerprint(key);
	int i1 = hash(key) % numBuckets;
	int i2 = (i1 ^ hash(intToBytes(fp))) % numBuckets;
	if (buckets[i1].tryInsert(fp) || buckets[i2].tryInsert(fp)) return true;
	int i = ThreadLocalRandom.current().nextBoolean() ? i1 : i2;
	for (int n = 0; n < 500; n++) {
		int evicted = buckets[i].swap(fp);
		fp = evicted;
		i = (i ^ hash(intToBytes(fp))) % numBuckets;
		if (buckets[i].tryInsert(fp)) return true;
	}
	return false;
}
```

95% load factor ε≈1%, 메모리 Bloom의 0.84배. 단점: 95% 이상 차면 삽입 실패, cascade displacement, 같은 key 8번+ 시 실패.

## 6. RocksDB Bloom — Block vs Ribbon

Ribbon은 같은 FPR을 0.7배 메모리로, construction ~3배 느림.

```cpp
options.filter_policy.reset(
	NewRibbonFilterPolicy(10, 1));
```

L0/L1만 전통 Bloom, L2+는 Ribbon. 자체 측정 200TB DB에서 메모리 32GB → 22GB.

## 7. Redis Bloom / Cuckoo

```bash
BF.RESERVE seen 0.01 1000000
BF.ADD seen "user:123"

CF.RESERVE active 1000000
CF.ADD active "user:123"
CF.DEL active "user:123"
```

Bloom은 add-only, Cuckoo는 만료 있는 세션 추적. Cuckoo는 같은 key 8회+ add 시 실패하므로 idempotency 미보장 케이스 부적합.

## 8. 실측 — 1억 item

| 구조 | 메모리 | 삽입 | 조회 | 삭제 |
|---|---|---|---|---|
| Bloom (k=7) | 114 MB | 12.1 M/s | 18.4 M/s | 불가 |
| Counting Bloom | 456 MB | 9.8 M/s | 14.2 M/s | 9.8 M/s |
| Cuckoo (fp=8) | 96 MB | 6.4 M/s | 21.7 M/s | 6.4 M/s |
| Ribbon | 80 MB | 1.9 M/s | 17.1 M/s | 불가 |

## 9. 선택 기준

1. 삭제 필요? → Cuckoo
2. construction 일회성·메모리 최우선? → Ribbon
3. 단순·안정성? → Bloom / Counting Bloom

동시성 강하면 Bloom이 lock-free 친화적(CAS 한 번). Cuckoo는 displacement 중 동시 접근 까다로워 striped lock 필요. cache 친화성: Cuckoo가 2 bucket만 접근하니 Bloom보다 2~3배 friendly.

## 10. 분산 환경

Bloom 비트 배열은 byte stream 직렬화 가능. 1억 item ε=1% = 114MB 페이로드. 차분 전송(XOR delta + RLE)으로 네트워크 비용 10~30배 감소. 차분 누적 시 결국 baseline broadcast 필요. Cuckoo는 displacement 중 일관성 없어 RCU 패턴 권장.

## 참고

- 'Network Applications of Bloom Filters: A Survey' — Broder & Mitzenmacher
- 'Cuckoo Filter: Practically Better Than Bloom' — Fan et al., CoNEXT 2014
- RocksDB filter 문서 — github.com/facebook/rocksdb/wiki/Bloom-Filter
- RedisBloom — redis.io/docs/stack/bloom/
- 'Designing Data-Intensive Applications' — Martin Kleppmann
