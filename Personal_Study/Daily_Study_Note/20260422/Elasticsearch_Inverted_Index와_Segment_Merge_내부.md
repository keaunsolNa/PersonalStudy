Notion 원본: https://www.notion.so/34a5a06fd6d381ed8446cdaff6c2d71d

# Elasticsearch Inverted Index와 Segment Merge 내부 구조

> 2026-04-22 신규 주제 · 확장 대상: Elasticsearch (쿼리 DSL 중심 → 색인 엔진 내부 심화)

## 학습 목표

- Lucene의 Inverted Index 물리 구조(Term Dictionary FST, Posting List, Doc Values)를 파일 단위로 이해한다
- Refresh → Flush → Commit 타임라인과 Translog/Durability 설정이 쓰기 성능과 데이터 유실 가능성에 미치는 영향을 분석한다
- Tiered Merge Policy의 동작 원리를 알고 Force Merge가 왜 운영 중에는 위험한지 설명한다
- 샤드 수, Mapping 타입(`text` vs `keyword`, `nested` vs `join`), 쿼리 context(scoring vs filter) 선택을 **숫자 근거**로 결정한다

---

## 1. Lucene이 곧 Elasticsearch의 엔진이다

Elasticsearch는 Lucene을 샤드/분산/REST로 감싼 래퍼에 가깝다. 성능과 운영 이슈의 90%는 Lucene 레벨에서 이해해야 풀린다. 각 샤드 = 하나의 Lucene 인덱스 = 디렉터리에 쌓이는 여러 **Segment 파일**들의 집합이다.

세그먼트 하나는 자체 완결된 mini-index로 다음 파일들로 구성된다.

| 확장자 | 내용 |
|---|---|
| `.tim`, `.tip` | Term Dictionary (FST) + Term Index |
| `.doc` | Posting List (어떤 doc에 term이 등장하는지) |
| `.pos` | Position 정보 (phrase query용) |
| `.pay` | Payload, offset |
| `.fdt`, `.fdx` | Stored Fields 본문 (압축된 `_source`) |
| `.dvd`, `.dvm` | Doc Values (열 지향 저장, sort/aggregation용) |
| `.nvd`, `.nvm` | Norms (길이 정규화 factor) |
| `.liv` | Live Docs (삭제 표시 비트맵) |
| `.cfs`, `.cfe` | 작은 세그먼트들을 묶은 Compound File |

색인은 **한 번 쓰면 불변**이다. 문서 수정은 "기존 문서 soft-delete + 새 문서 추가"로 구현된다. 이것이 뒤에서 말할 Segment Merge가 필요한 이유다.

## 2. Inverted Index의 물리 구조

전통적인 행 지향 DB 인덱스(B-Tree)와 가장 큰 차이는 **Term → [DocID 리스트]** 로 뒤집혀 있다는 점. "apple"이라는 term이 어떤 문서들에 등장하는지 O(1)에 가깝게 찾을 수 있다.

**Term Dictionary(FST)**. Finite State Transducer는 prefix 공유 자료구조다. "apple", "apply", "applies"는 공통 접두사 "appl"을 한 번만 저장한다. 10M개 term이 있어도 수백 MB 수준으로 압축되고, 이진 탐색보다 메모리 지역성이 좋아 디스크에서 mmap으로 읽어도 빠르다. Term Index(.tip)는 FST의 한 레벨 위 요약으로 메모리에 상주한다.

**Posting List(.doc)**. 각 term에 딸린 `[docId1, docId2, ...]` 정렬된 리스트. 다음 두 기법으로 압축된다.

- **Delta Encoding**: `[1003, 1005, 1020]` → `[1003, +2, +15]`. 차이값이 작아 variable-length integer로 아주 작게 저장.
- **Skip List**: `AND` 쿼리에서 빠르게 건너뛰기 위한 인덱스. 예: "apple AND banana"에서 apple의 포스팅에 doc #1000이 있고 banana는 doc #5000부터 있으면, apple의 skip 포인터로 곧장 #5000 근처로 점프.

Posting List는 디스크 mmap에서 순차 읽기가 잘 먹히므로 I/O 효율이 매우 높다.

**Doc Values(.dvd)**. 반대로 정렬/집계용으로는 "DocID → 값" 방향이 필요하다. Doc Values는 **열 지향(columnar)** 으로 저장된 보조 구조다. `sort: { price: asc }`, `aggs: { avg_price: { avg: { field: price } } }` 쿼리는 Doc Values에서 해당 필드 컬럼을 sequential scan 한다.

Doc Values는 기본 활성화되지만 분석 대상이 아닌 텍스트에는 비활성화할 수 있다(`doc_values: false`). 대신 Doc Values가 없으면 그 필드는 정렬/집계가 불가능해진다.

## 3. 쓰기 경로 — Refresh, Flush, Translog

쓰기 요청이 들어오면 다음 세 단계를 거친다.

```
POST /my-index/_doc
       │
       ▼
1. In-memory Buffer + Translog append (fsync)
       │
       │  (기본 1초 주기 refresh)
       ▼
2. 새 Segment 파일 생성 (OS page cache, 디스크 미보장)
       │  → 이 순간부터 검색 가능 (Near-Real-Time)
       │
       │  (버퍼 꽉참 또는 주기적 flush)
       ▼
3. Lucene Commit: 모든 Segment fsync + 새 commit point 작성
       │  → Translog 잘라냄 (truncate)
```

**Refresh(1초)** 는 in-memory buffer를 새 Segment 파일로 만든다. 이 순간부터 해당 문서가 검색된다. 디스크 fsync는 안 하므로 빠르지만 내구성은 Translog에 의존한다.

**Flush** 는 Lucene Commit을 수행해 Segment들을 디스크에 확정하고 Translog를 잘라낸다. 기본 30분 또는 Translog 크기가 임계(기본 512MB)에 도달하면.

**Translog(durability)**. 설정이 두 가지다.

- `index.translog.durability: request` (기본): 매 요청마다 fsync. 안전하지만 느림.
- `index.translog.durability: async`: 5초마다 fsync. 최대 5초치 유실 가능, 쓰기 처리량은 2~3배.

로그 수집 같은 "약간의 유실 허용"인 워크로드는 `async`가 타당하다. 금융 거래 같으면 `request` 유지.

**쓰기 최적화 팁**: 대량 bulk 삽입 시에만 일시적으로 `refresh_interval: -1`, `number_of_replicas: 0`로 두고, 완료 후 원래 값으로 돌리면 10배 이상 빨라진다. 검색이 필요 없는 배치 재색인에 특히 효과적.

## 4. Segment Merge — 왜 필요하고, 왜 위험한가

Refresh는 1초마다 새 세그먼트를 만든다. 한 시간이면 3600개. 세그먼트 수가 많을수록 쿼리는 모든 세그먼트를 순회해야 하므로 느려진다. Lucene은 **Tiered Merge Policy**로 주기적으로 작은 세그먼트들을 큰 세그먼트로 합친다.

기본 설정 요지:

- `index.merge.policy.max_merge_at_once`: 10 (한 번에 합치는 세그먼트 개수)
- `index.merge.policy.segments_per_tier`: 10 (각 티어에 허용되는 세그먼트 개수)
- `index.merge.policy.max_merged_segment`: 5GB (이 크기 이상은 더 합치지 않음)

Merge는 **백그라운드 스레드**가 실행하고 I/O를 점유하므로 쓰기 부하가 급증한다. 운영 중 IOPS가 튀는 주요 원인.

**Force Merge(`_forcemerge?max_num_segments=1`)** 는 수동으로 세그먼트를 합치는 명령이다. 주의사항:

- **I/O 폭주**: 수십 GB 인덱스를 1개 세그먼트로 합치려면 전체를 다시 쓰므로 수 시간 소요. 라이브 트래픽이 있는 인덱스에 절대 실행하지 말 것.
- **삭제 문서 청소**: Soft-deleted 문서는 Merge 시에만 실제로 제거된다. 삭제가 많은 인덱스에서 Force Merge는 디스크 공간을 극적으로 회수하기도 한다.
- **읽기 전용 인덱스에만 안전**: ILM으로 "warm tier"로 넘어간 과거 데이터(예: 로그 인덱스)를 1 세그먼트로 합쳐두면 쿼리 레이턴시가 안정된다.

ILM 정책의 Shrink/Force Merge 단계에서 자동으로 하도록 두고, 수동 실행은 지양한다.

## 5. 샤드 설계 수식

샤드 수는 **한 번 정하면 바꾸기 어렵다**(Reindex 필요). 처음 설계할 때 숫자를 맞춰야 한다.

**권장 샤드 크기**: 10~50GB. 너무 작으면 오버헤드(샤드당 metadata, scatter-gather cost), 너무 크면 merge 비용과 복구 시간.

**프라이머리 샤드 수 공식**:

```
primary_shards = ceil( total_data_size / target_shard_size )
```

예: 연간 1TB 로그를 매일 인덱스 롤오버하면 하루 ~2.7GB. 너무 작으므로 **주간 롤오버 + 단일 샤드**가 합리적이다. 반대로 하루 300GB 로그라면 10~15개 샤드로 분할.

**Replica 수와 쓰기 증폭**. `number_of_replicas: 2`면 쓰기 요청이 3번 실행된다(primary + 2 replica). 쓰기 TPS와 저장 용량을 3배로 계산해야 한다.

**노드당 샤드 개수 상한**: 힙 1GB당 ~20 샤드가 Elastic 공식 가이드라인. 32GB 힙이면 약 600~700개. 이를 넘으면 클러스터 상태 관리 오버헤드로 마스터 노드가 느려진다.

**Routing**. 특정 필드(예: `customer_id`) 기준으로 라우팅하면 그 값의 모든 문서가 같은 샤드에 모인다. 쿼리 시 `?routing=customer_id=123`로 **해당 샤드만 조회**하므로 p99 레이턴시가 크게 줄어든다. 단 불균형 샤드(hotspot) 위험 있음.

## 6. Mapping 최적화

Mapping은 Elasticsearch 성능의 30% 이상을 결정한다.

**`text` vs `keyword`**. `text`는 분석기를 거치며 term으로 분해되어 full-text search 대상. `keyword`는 원문 그대로 저장, 정렬/집계/완전일치에 사용. 실무에서 많은 필드가 **둘 다 필요**하므로 multi-field를 쓴다.

```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "nori",
        "fields": {
          "raw": { "type": "keyword", "ignore_above": 256 }
        }
      }
    }
  }
}
```

**불필요한 필드는 꺼라**. 읽지 않는 필드의 `index: false`, `doc_values: false`는 디스크와 색인 시간을 절약한다. 특히 `_source`에 저장하고 결코 쿼리 대상이 되지 않는 필드.

**`dynamic: strict`**. 기본 `dynamic: true`는 알 수 없는 필드를 자동으로 매핑에 추가한다. 로그 집계에서 오타 필드가 매핑에 수천 개 쌓이는 **매핑 폭발**의 원인. 프로덕션 인덱스는 반드시 `strict`로 설정해 명시적으로 정의된 필드만 허용한다.

**`nested` vs `join` vs Denormalization**. 중첩 데이터 처리 옵션.

- **Denormalization(평면화)** 가 대부분의 경우 가장 빠르다. 쿼리가 단순해지고 JOIN이 필요 없다.
- **nested**: 배열 내부 객체들을 독립적으로 쿼리해야 할 때. 내부적으로 각 nested 객체가 별도 hidden document로 색인되어 **문서 수 증폭**(1 부모 + N 자식).
- **join**: 부모-자식 관계를 유지해야 할 때(예: 블로그 글 + 댓글). 같은 샤드에 강제 배치되어 routing에 제약이 생김. 거의 쓰지 말 것.

정말 필요한 경우가 아니면 Denormalization으로 해결한다.

## 7. 쿼리 Context — Score vs Filter

같은 조건이라도 **어느 context에 넣느냐**에 따라 성능이 다르다.

```json
GET /my-index/_search
{
  "query": {
    "bool": {
      "must":   [{ "match": { "title": "elasticsearch" } }],    // scoring
      "filter": [{ "term":  { "status": "published" } },        // no scoring, cached
                 { "range": { "created": { "gte": "2026-01-01" } } }]
    }
  }
}
```

**Query context(`must`, `should`)** 는 BM25 스코어를 계산해 "얼마나 잘 매치되는지"를 판단. 느리다.

**Filter context(`filter`, `must_not`)** 는 점수 계산을 건너뛰고 **Filter Cache**에 결과를 저장한다. 두 번째부터는 bitmap 단순 교집합이라 극도로 빠르다.

규칙: **정렬에 영향을 주지 않는 조건은 전부 `filter`에 넣는다**. 제품 목록의 "재고 있는 것만", "특정 카테고리"는 모두 filter. 키워드 매칭만 must에.

`_source` 필터링 vs Doc Values 접근.

- `"_source": ["id", "title"]`: `_source` 전체를 가져온 뒤 필드만 추린다. 여전히 fdt 파일을 읽어야 함.
- `"stored_fields"` 또는 `"docvalue_fields"`: Doc Values 컬럼만 읽으므로 I/O가 훨씬 작음.

대용량 결과 반환(Scroll, Search After)에서는 `docvalue_fields`가 3~5배 빠를 수 있다.

## 8. 운영 관측과 대응

평소에 봐야 할 주요 지표와 대응 패턴.

**`GET _cat/indices?v&s=store.size:desc`** — 크기 순 인덱스 확인. 예상보다 크면 Force Merge 대상 또는 보존 정책 재검토.

**`GET _nodes/stats/indices,thread_pool,jvm`** — JVM 힙 사용률, Thread Pool 포화(rejected count), Merge 중 세그먼트 수를 한눈에.

**Search Slow Log**. `index.search.slowlog.threshold.query.warn: 5s`로 설정하면 느린 쿼리가 로그에 찍힌다. Fetch와 Query를 구분해 진단 가능.

**흔한 이슈와 원인**:

- **쿼리 느려짐**: 세그먼트 수 폭증(merge 지연), 캐시 miss, 매핑 폭발, Deep Pagination(`from > 10000`).
- **색인 느려짐**: `refresh_interval` 너무 짧음, Replica 과다, Translog fsync 과다, Disk IOPS 포화.
- **힙 OOM**: `fielddata`(text 필드 집계), 매핑 폭발, Aggregation 중간 결과 과다. 필드 캐디널리티 폭발이 원인인 경우가 대부분.

**Hot/Warm/Cold Tier**. 시계열 데이터(로그, 메트릭)는 ILM으로:

- Hot(SSD, 고사양): 최근 7일, 활발한 쓰기/읽기
- Warm(HDD, 중급): 7~30일, 읽기 위주
- Cold(아카이브): 30일 이상, 가끔 읽기

티어 이동 시 Shrink(샤드 수 축소)와 Force Merge로 읽기 전용 최적화한다. 비용을 절반 이하로 낮출 수 있는 가장 실용적인 패턴.

---

## 참고

- 기학습 연계: [Elasticsearch](./Elasticsearch.md), [Oracle](./Oracle.md) (B-Tree 인덱스와 대비), [최적화 기본](./최적화_기본.md)
- [Elastic Blog — "A Heap of Trouble"](https://www.elastic.co/blog/a-heap-of-trouble)
- [Elastic Blog — "How Lucene handles deletes"](https://www.elastic.co/blog/lucenes-handling-of-deleted-documents)
- *Elasticsearch: The Definitive Guide* (구 버전이지만 내부 원리는 유효)
- [Lucene 소스 — SegmentInfos, IndexWriter](https://github.com/apache/lucene)
- [Michael McCandless 블로그](http://blog.mikemccandless.com/) — Lucene 커미터의 내부 분석 글
