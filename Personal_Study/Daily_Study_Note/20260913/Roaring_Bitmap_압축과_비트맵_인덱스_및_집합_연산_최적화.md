Notion 원본: https://app.notion.com/p/3da5a06fd6d381a2880adbc0edccfec2?pvs=204

# Roaring Bitmap 압축과 비트맵 인덱스 및 집합 연산 최적화

> 2026-09-13 신규 주제 · 확장 대상: Bloom Filter / Elasticsearch 인덱스 구조

## 학습 목표

- 비트맵 인덱스가 카디널리티에 따라 유리·불리해지는 지점을 계산으로 규명한다
- RLE 계열 압축(WAH/EWAH)의 한계와 Roaring 의 컨테이너 분할 전략을 비교한다
- 컨테이너 타입별 집합 연산 알고리즘과 SIMD 적용 지점을 분석한다
- Lucene·Elasticsearch·Druid 에서 실제로 어디에 쓰이는지 확인한다

## 1. 비트맵 인덱스의 기본 산술

카테고리 컴럼에 대한 필터를 생각하자. 1억 행 테이블에서 `status = 'ACTIVE'` 를 찾는다.

B-tree 인덱스는 정렬된 키에서 해당 범위의 리프를 찾아 rowid 목록을 얻는다. rowid 가 8바이트이고 일치 행이 3천만이면 240MB 를 읽는다.

비트맵 인덱스는 값마다 비트 벡터를 둔다. `status` 의 고유값이 4개면 벡터 4개, 각 벡터는 1억 비트 = 12.5MB 다. 전체 50MB.

```
status='ACTIVE'   : 1 0 0 1 1 0 1 ...
status='INACTIVE' : 0 1 0 0 0 1 0 ...
status='PENDING'  : 0 0 1 0 0 0 0 ...
```

진짜 이득은 조합 질의에서 나온다.

```sql
SELECT COUNT(*) FROM users
WHERE status = 'ACTIVE' AND region = 'KR' AND plan = 'PRO';
```

세 비트 벡터를 AND 하면 끝이다. 12.5MB 씩 세 번 읽어 워드 단위 AND 를 돌린다. 64비트 워드로 처리하면 1억 비트가 156만 워드이고, 현대 CPU 에서 수 ms 다. B-tree 세 개로 rowid 목록을 만들어 해시 조인하는 것과 비교가 안 된다.

문제는 카디널리티다. 고유값이 100만 개면 벡터가 100만 개이고, 각 12.5MB 이므로 12.5TB 다. 대부분의 벡터는 1이 몇 개뿐인데 12.5MB 를 차지한다. 압축이 필수인 이유다.

밀도 관점에서 정리하면 이렇다.

| 집합 밀도 | 비압축 비트맵 | 정렬된 정수 배열 |
|---|---|---|
| 1/1,000,000 | 8KB (65536비트 블록당) | 4바이트 × 소수 |
| 1/100 | 8KB | 2.6KB |
| 1/2 | 8KB | 131KB |

밀도가 낮으면 배열이, 높으면 비트맵이 유리하다. 교차점은 대략 **1/16** 근처다(4바이트 정수 하나 = 32비트 = 비트맵 32칸). Roaring 의 설계는 이 관찰에서 출발한다.

## 2. RLE 계열 압축의 한계

Roaring 이전의 표준은 워드 정렬 런렉스 부호화였다. WAH(Word-Aligned Hybrid)와 그 변형인 EWAH 가 대표적이다.

WAH 는 31비트 단위로 비트열을 자르고, 각 워드를 두 종류로 나눈다.

- **리터럴 워드**: 최상위 비트 0 + 실제 31비트
- **필 워드**: 최상위 비트 1 + 채움값(0 또는 1) + 반복 횟수 30비트

```
원본: 0000...0000 (31비트 × 1000) 1010...
WAH : [필: 0을 1000회] [리터럴: 1010...]
```

희소 데이터에서 압축률이 뛰어나다. 100만 개의 0 이 워드 하나로 줄어든다.

한계는 두 가지다.

**임의 접근 불가.** "1,234,567번 비트가 켜져 있는가"를 알려면 앞에서부터 필 워드의 길이를 누적해야 한다. O(n) 이다. AND/OR 는 두 스트림을 동시에 순회하면 되므로 괜찮지만, 단일 조회나 교집합 후 특정 위치 확인 같은 작업에서 불리하다.

**패턴 의존성.** 0 이 길게 이어져야 압축된다. 값이 균등하게 훩어지면 필 워드가 만들어지지 않고 리터럴만 남아 **비압축보다 커진다**(1비트의 헤더 오버헤드 때문에 31/32 효율). 실제 데이터에서는 이 경우가 흔하다.

## 3. Roaring 의 분할 전략

Roaring Bitmap 은 압축 알고리즘을 하나 고르는 대신, **범위를 쪼개고 각 조각에 맞는 표현을 고른다**.

32비트 정수를 상위 16비트와 하위 16비트로 나눈다. 상위 16비트가 같은 값들이 하나의 **컨테이너**에 들어간다. 즉 컨테이너 하나는 65536 개의 값 공간을 담당한다.

```
값 0x0003_A1F0 → 컨테이너 키 0x0003, 컨테이너 내부 값 0xA1F0
```

최상위 구조는 `(키, 컨테이너)` 쌍을 키로 정렬한 배열이다. 키로 이진 탐색하면 O(log n) 에 컨테이너를 찾는다.

컨테이너 타입은 셋이다.

| 타입 | 조건 | 표현 | 크기 |
|---|---|---|---|
| Array | 원소 ≤ 4096 | 정렬된 `uint16[]` | 2바이트 × n |
| Bitmap | 원소 > 4096 | 8192바이트 고정 비트맵 | 8KB |
| Run | 런 수가 적을 때 | `(시작, 길이)` 쌍 배열 | 4바이트 × 런 수 |

임계값 4096 이 핵심 숫자다. 원소가 4096개일 때 Array 는 2 × 4096 = 8192바이트로 Bitmap 과 같아진다. 그 이상이면 Bitmap 이 작다. **어떤 컨테이너도 8KB 를 넘지 않는다**는 보장이 여기서 나온다.

Run 컨테이너는 Roaring 포맷 확장으로 추가됐다. `[100, 5000]` 같은 연속 구간은 런 하나(4바이트)로 표현된다. 삽입 시에는 만들지 않고, `runOptimize()` 를 명시적으로 호출할 때 각 컨테이너를 검사해 Run 이 더 작으면 변환한다.

```java
RoaringBitmap bitmap = new RoaringBitmap();
bitmap.add(1_000_000L, 2_000_000L);   // 100만 개 연속
System.out.println(bitmap.getSizeInBytes());   // Bitmap 컨테이너 여러 개

bitmap.runOptimize();
System.out.println(bitmap.getSizeInBytes());   // 극적으로 감소
```

이 분할이 주는 것은 압축률만이 아니다.

**임의 접근이 O(log n).** 상위 16비트로 컨테이너를 이진 탐색하고, Array 면 다시 이진 탐색, Bitmap 이면 O(1) 비트 검사다. WAH 의 O(n) 과 결정적 차이다.

**최악의 경우 경계.** 데이터 패턴이 아무리 나빤도 컨테이너당 8KB 를 넘지 않는다. WAH 처럼 비압축보다 커지는 일이 없다.

**rank/select 지원.** 각 컨테이너의 누적 카디널리티를 들고 있으면 "k번째로 작은 원소"를 빠르게 찾을 수 있다.

## 4. 컨테이너 타입별 집합 연산

AND/OR/XOR/ANDNOT 은 두 컨테이너 타입 조합마다 다른 알고리즘을 쓴다. 3 × 3 = 9 가지 조합이지만 대칭성으로 실제 구현은 6가지다.

**Bitmap ∩ Bitmap** — 가장 단순하고 가장 빠르다. 1024개의 64비트 워드를 AND 한다.

```java
for (int i = 0; i < 1024; i++) {
	result[i] = a[i] & b[i];
}
```

AVX-512 라면 512비트씩 16회 반복으로 끝난다. `VPOPCNTQ` 로 결과 카디널리티까지 같은 루프에서 센다. CRoaring(C 구현)이 이 경로에 런타임 CPU 디스패치를 넣어 AVX2/AVX-512 를 자동 선택한다.

**Array ∩ Array** — 두 정렬 배열의 교집합이다. 크기가 비슷하면 병합 스캔, 한쪽이 훨씬 작으면 작은 쪽 원소마다 큰 쪽을 갤로핑 탐색(exponential search)한다.

```
|A| = 10, |B| = 4000  →  갤로핑: 10 × log(4000) ≈ 120 비교
                          병합:   10 + 4000 = 4010 비교
```

임계 비율은 구현마다 다르지만 대략 1:64 를 넘으면 갤로핑으로 전환한다. SIMD 를 쓰는 변형도 있다. `_mm_cmpestrm` 계열 문자열 비교 명령으로 16비트 값 8개씩 교차 비교하는 기법이 CRoaring 에 구현되어 있다.

**Array ∩ Bitmap** — 배열의 각 원소를 비트맵에서 O(1) 로 검사한다. |A| 회 반복이며 결과는 항상 Array 다.

**Run ∩ Run** — 두 구간 목록의 교차 구간을 병합 스캔으로 구한다.

**Run ∩ Bitmap** — 각 런에 해당하는 비트 범위를 통로 복사한다. 런이 길면 워드 단위 복사가 되어 매우 빠르다.

연산 후에는 **결과 타입을 재결정**한다. 교집합 결과의 카디널리티가 4096 이하로 떨어지면 Bitmap 에서 Array 로 변환한다. 이 정규화가 없으면 반복 연산 중 메모리가 부풀어 오른다.

## 5. Java 에서의 사용

`RoaringBitmap` 라이브러리가 사실상 표준이다.

```java
import org.roaringbitmap.RoaringBitmap;

public class SegmentAnalyzer {

	public RoaringBitmap findTargetUsers(
			RoaringBitmap activeUsers,
			RoaringBitmap koreanUsers,
			RoaringBitmap proPlanUsers,
			RoaringBitmap churnRiskUsers) {

		RoaringBitmap target = RoaringBitmap.and(activeUsers, koreanUsers);
		target.and(proPlanUsers);
		target.andNot(churnRiskUsers);
		return target;
	}
}
```

`RoaringBitmap.and(a, b)` 는 새 객체를 만들고, 인스턴스 메서드 `a.and(b)` 는 `a` 를 제자리에서 수정한다. 연쇄 연산에서는 제자리 변형이 할당을 줄여 유리하다.

여러 비트맵을 한 번에 OR 할 때는 전용 메서드를 쓴다.

```java
RoaringBitmap union = RoaringBitmap.or(bitmaps);        // 순차
RoaringBitmap fast  = FastAggregation.or(bitmaps);      // 휴리스틱 선택
RoaringBitmap heap  = FastAggregation.horizontal_or(bitmaps);  // 우선순위 큐
```

`horizontal_or` 는 작은 것부터 병합해 중간 결과 크기를 억제한다. 비트맵 수가 많고 크기가 제각각일 때 효과가 크다.

직렬화는 이식 가능한 포맷을 따른다. Java, C, Go, Python 구현이 같은 바이트열을 읽는다.

```java
ByteArrayOutputStream out = new ByteArrayOutputStream();
try (DataOutputStream dos = new DataOutputStream(out)) {
	bitmap.runOptimize();
	bitmap.serialize(dos);
}
byte[] bytes = out.toByteArray();
```

```java
RoaringBitmap restored = new RoaringBitmap();
restored.deserialize(new DataInputStream(new ByteArrayInputStream(bytes)));
```

역직렬화 없이 바이트 버퍼 위에서 바로 연산하는 변형도 있다.

```java
ImmutableRoaringBitmap mapped =
		new ImmutableRoaringBitmap(byteBuffer);
```

`ImmutableRoaringBitmap` 은 `MappedByteBuffer` 를 그대로 쓸 수 있어, 메모리 맵 파일에 저장된 인덱스를 힙에 올리지 않고 질의할 수 있다. Druid 가 세그먼트 인덱스를 이 방식으로 다룬다.

64비트 값이 필요하면 `Roaring64NavigableMap` 을 쓴다. 상위 32비트로 다시 한 층을 나누는 구조다.

## 6. Lucene 과 Elasticsearch 에서의 위치

Lucene 은 Roaring 을 그대로 쓰지는 않지만 **같은 아이디어**를 `RoaringDocIdSet` 에 구현했다. 문서 ID 공간을 65536 단위 블록으로 나누고, 블록마다 밀도에 따라 `ShortArrayDocIdSet` 또는 `FixedBitSet` 을 고른다.

Elasticsearch 의 필터 캐시가 이 구조 위에 있다. 필터 질의가 반복되면 결과 문서 집합을 `RoaringDocIdSet` 으로 캐싱한다.

```json
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "status": "ACTIVE" } },
        { "range": { "created_at": { "gte": "2026-01-01" } } }
      ]
    }
  }
}
```

`filter` 절은 점수를 계산하지 않으므로 결과가 순수한 문서 집합이고, 캐싱 대상이 된다. 두 필터의 캐시된 비트셋을 AND 하면 스코링 없이 후보가 나온다. `must` 에 넣으면 점수 계산 때문에 이 최적화를 못 받는다 — Elasticsearch 성능 튜닝에서 "점수가 필요 없으면 filter 를 써라"가 나오는 근거다.

Lucene 은 캐싱 여부를 휴리스틱으로 결정한다. 세그먼트가 충분히 크고(기본 1만 문서 이상), 같은 필터가 일정 횟수 이상 사용됐을 때만 캐싱한다. 작은 세그먼트는 계산이 싸고 곳 병합될 것이므로 캐싱 비용이 손해다.

Apache Druid 는 더 직접적이다. 차원 컴럼마다 값별 비트맵 인덱스를 만들고, Roaring 또는 CONCISE(WAH 변형) 중 선택할 수 있다. 기본값이 Roaring 으로 바뀜 것은 임의 접근 성능과 최악의 경우 보장 때문이다.

## 7. 실무 적용 패턴 — 세그먼트 엔진

사용자 세그먼트 계산이 Roaring 의 전형적 활용처다. 마케팅 도구에서 "한국 거주 + PRO 플랜 + 최근 30일 로그인 + 이탈 위험 제외" 같은 조건을 실시간으로 계산해야 한다.

RDB 로 하면 `JOIN` 과 `EXISTS` 가 중첩되어 수 초가 걸린다. 비트맵으로 하면 밀리초다.

설계는 이렇다. 사용자마다 0부터 시작하는 조밀한 정수 ID 를 부여하고, 속성값마다 비트맵을 만들어 Redis 나 객체 스토리지에 저장한다.

```java
@Service
public class SegmentService {

	private final BitmapStore store;

	public long countSegment(SegmentDefinition definition) {
		RoaringBitmap result = null;

		for (Condition include : definition.getIncludes()) {
			RoaringBitmap bitmap = store.load(include.getKey());
			if (result == null) {
				result = bitmap.clone();
			} else {
				result.and(bitmap);
			}
			if (result.isEmpty()) {
				return 0L;
			}
		}

		if (result == null) {
			return 0L;
		}

		for (Condition exclude : definition.getExcludes()) {
			result.andNot(store.load(exclude.getKey()));
		}

		return result.getLongCardinality();
	}
}
```

두 가지 최적화가 들어 있다. 중간 결과가 비면 즉시 반환하고, AND 대상을 **카디널리티 오름차순으로 정렬**해 두면 결과가 빨리 작아진다.

```java
List<RoaringBitmap> sorted = includes.stream()
		.map(condition -> store.load(condition.getKey()))
		.sorted(Comparator.comparingLong(RoaringBitmap::getLongCardinality))
		.toList();
```

ID 할당 방식도 성능에 직결된다. **연관된 사용자가 인접한 ID 를 받도록** 배치하면 컨테이너가 조밀해지고 Run 컨테이너로 압축될 여지가 커진다. 가입일 순 정렬이 자연스러운 선택이며, "최근 가입자" 같은 조건이 연속 구간이 되어 런 하나로 표현된다.

반대로 UUID 해시를 ID 로 쓰면 모든 비트맵이 균등하게 훩어져 압축이 거의 안 된다. 조밀한 정수 ID 와 원본 키의 매핑 테이블을 따로 두는 비용을 감수할 가치가 있다.

## 8. 한계와 대안 선택

Roaring 이 항상 답은 아니다.

**카디널리티만 필요하면 HyperLogLog.** 정확한 집합이 아니라 개수만 필요하고 1~2% 오차를 허용한다면 HLL 이 훨씬 작다. 12KB 로 수십억 카디널리티를 추정한다. Roaring 은 정확한 대신 원소 수에 비례한다.

**멤버십만 필요하면 Bloom Filter.** "이 원소가 있는가"만 묻고 거짓 양성을 허용하면 Bloom 이 작다. 다만 Bloom 은 집합 연산 결과를 다시 순회할 수 없고, 삭제도 안 된다(Counting Bloom 은 가능하지만 커진다).

**값 공간이 희소하고 넓으면 재매핑 필요.** 64비트 ID 를 그대로 쓰면 컨테이너가 훩어져 최상위 배열이 커진다. `Roaring64NavigableMap` 이 있지만, 조밀한 32비트 ID 로 재매핑하는 편이 대개 더 빠르다.

**쓰기가 매우 잦으면 부적합.** 컨테이너 타입 전환과 배열 삽입이 O(n) 이므로, 초당 수만 건의 개별 삽입이 들어오면 병목이 된다. 배치로 모아 `addN()` 이나 `add(range)` 를 쓰는 것이 정석이다.

```java
// 나쁨
for (int id : ids) {
	bitmap.add(id);
}

// 좋음 — 정렬된 배열이면 훨씬 빠른 경로
Arrays.sort(ids);
bitmap.addN(ids, 0, ids.length);
```

실측 기준으로, 정렬된 입력에 `addN` 을 쓰면 개별 `add` 대비 수 배 빠르다. 정렬 비용을 감안해도 대개 이득이다.

## 참고

- Chambi, Lemire, Kaser, Godin — "Better bitmap performance with Roaring bitmaps", Software: Practice and Experience, 2016
- Lemire, Ssi-Yan-Kai, Kaser — "Consistently faster and smaller compressed bitmaps with Roaring", SPE, 2016
- RoaringBitmap Java 구현 (https://github.com/RoaringBitmap/RoaringBitmap)
- CRoaring — C/C++ 구현과 SIMD 최적화 (https://github.com/RoaringBitmap/CRoaring)
- Apache Lucene — `RoaringDocIdSet` 소스 (https://lucene.apache.org/)
