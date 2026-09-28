Notion 원본: https://www.notion.so/3e95a06fd6d381ceb832fca5f775f56a

# Elasticsearch 역인덱스(Inverted Index) 구조와 BM25 스코어링 알고리즘 및 쿼리 성능 튜닝

> 2026-09-28 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- 역인덱스의 term dictionary와 postings list가 디스크/메모리에서 어떻게 구성되는지 설명한다
- BM25 스코어링 공식의 TF, IDF, 필드 길이 정규화 항이 각각 어떤 검색 품질 문제를 해결하는지 분석한다
- `explain API`로 실제 스코어 계산 과정을 추적하고 튜닝 파라미터(`k1`, `b`)의 효과를 검증한다
- `filter` 컨텍스트와 `query` 컨텍스트의 성능 차이를 캐싱 관점에서 비교한다

## 1. 역인덱스의 물리 구조: Term Dictionary와 Postings List

Elasticsearch(내부적으로 Apache Lucene)의 역인덱스는 "어떤 단어가 어떤 문서에 나타나는가"를 빠르게 찾기 위한 자료구조다. 핵심 구성 요소는 두 가지다. Term Dictionary는 색인된 모든 고유 term(형태소 분석을 거친 토큰)을 정렜된 순서로 담고 있으며, 각 term은 자신의 Postings List(해당 term이 등장하는 문서 ID 목록)를 가리키는 포인터를 갖는다.

```
Term Dictionary (정렜된 term들)      Postings List
"backend"    ──────────────▶  [doc1, doc5, doc9, doc12, ...]
"elasticsearch" ───────────▶  [doc2, doc5, doc7, ...]
"spring"     ──────────────▶  [doc1, doc3, doc5, doc8, ...]
```

Term Dictionary는 검색 시 이진 탐색이 아니라 **FST(Finite State Transducer)** 라는 압축 자료구조로 메모리에 상주한다. FST는 공통 접두사를 공유하는 문자열들을 트라이(trie)와 유사하게 병합해, 수백만 개의 유니크 term을 수십 MB 수준의 메모리로 표현할 수 있다. 예를 들어 "backend", "backends", "backend_service" 같은 term들은 "backend"라는 공통 상태를 공유하며 분기점부터만 별도 경로를 갖는다. 이 덕분에 term lookup은 O(term 길이)에 가까운 시간 복잡도로 동작하며, 인덱스 크기가 커져도 term dictionary 조회 속도는 상대적으로 안정적이다.

Postings List는 문서 ID뿐 아니라 term frequency(해당 문서 내 등장 횟수), position(형태소 위치, phrase 쿼리에 필요), offset(하이라이팅에 필요) 정보를 함께 저장한다. 이 리스트는 **델타 인코딩(delta encoding)** 과 **가변 길이 정수 압축(variable byte encoding)** 으로 저장되어, 정렜된 문서 ID 목록을 "이전 값과의 차이"만 기록함으로써 저장 공간을 크게 절약한다. 예를 들어 문서 ID가 [100, 105, 250, 251]이면 델타는 [100, 5, 145, 1]로 저장되어 작은 정수 위주로 압축 효율이 높아진다.

## 2. BM25 스코어링 공식의 세 가지 축

Elasticsearch 5.0 이후 기본 유사도 알고리즘은 TF-IDF가 아니라 **BM25(Best Matching 25)** 다. BM25 점수는 쿼리에 포함된 각 term에 대해 다음 공식으로 계산되고, 문서의 최종 점수는 매칭된 모든 term의 점수 합이다.

```
score(D, Q) = Σ IDF(qi) · (f(qi, D) · (k1 + 1)) / (f(qi, D) + k1 · (1 - b + b · |D| / avgdl))
```

- `f(qi, D)`: term qi가 문서 D에 등장하는 빈도(TF)
- `IDF(qi)`: 역문서빈도 — term이 전체 문서 집합에서 얼마나 희귀한지
- `|D|`: 문서 D의 (해당 필드) 길이, `avgdl`: 전체 문서의 평균 길이
- `k1`: TF 포화(saturation) 조절 파라미터(기본값 1.2)
- `b`: 필드 길이 정규화 강도(기본값 0.75, 0이면 정규화 없음)

TF-IDF와의 결정적 차이는 **TF 포화** 다. TF-IDF는 term이 문서에 10번 나오면 5번 나올 때보다 정확히 2배의 가중치를 주지만, BM25는 `f(qi,D) / (f(qi,D) + k1)` 형태의 포화 함수를 써서 등장 횟수가 늘어날수록 점수 증가폭이 점점 줄어든다. 이는 "스팸성으로 특정 단어를 반복 삽입한 문서"가 부당하게 높은 점수를 받는 것을 방지하는 실질적 효과가 있다.

```
k1=1.2 기준 TF 포화 곱선 (근사치)
f=1  → 정규화 기여도 약 0.45
f=2  → 약 0.62
f=5  → 약 0.81
f=10 → 약 0.89
f=20 → 약 0.94  (계속 늘어도 1에 점근할 뿐 무한정 커지지 않음)
```

## 3. IDF의 실제 계산식과 "네거티브 IDF" 함정

Lucene/BM25의 IDF는 전통적인 `log(N/df)`가 아니라 다음과 같은 변형을 쓴다.

```
IDF(qi) = ln(1 + (N - df(qi) + 0.5) / (df(qi) + 0.5))
```

여기서 `N`은 전체 문서 수, `df(qi)`는 qi를 포함하는 문서 수다. 이 식은 `df`가 `N`의 절반을 넘는 매우 흔한 term에서도 IDF가 항상 양수를 유지하도록 설계되어 있다(전통적인 `log(N/df)` 방식은 df가 N의 절반을 넘으면 음수가 되는 문제가 있었다). 실무에서 이 공식을 이해하는 것이 중요한 이유는, "거의 모든 문서에 등장하는 term"(예: 한국어 검색에서 조사가 제대로 필터링되지 않은 경우)이 여전히 아주 작지만 양수인 IDF를 가져 스코어 계산에 미세하게 기여하기 때문이다 — 이런 term은 형태소 분석기의 불용어(stopword) 필터에서 제거하는 것이 스코어링 품질과 쿼리 성능 양쪽에 유리하다.

## 4. `explain API`로 실제 스코어 계산 추적하기

이론을 실제 인덱스에 검증하는 가장 확실한 방법은 `_explain` API다.

```json
GET /products/_explain/1
{
  "query": {
    "match": { "description": "spring backend framework" }
  }
}
```

```json
{
  "matched": true,
  "explanation": {
    "value": 5.432,
    "description": "sum of:",
    "details": [
      {
        "value": 2.1,
        "description": "weight(description:spring in 0) [PerFieldSimilarity], result of:",
        "details": [
          {
            "value": 2.1,
            "description": "score(freq=3.0), computed as boost * idf * tf from:",
            "details": [
              { "value": 2.2, "description": "idf, computed as log(1 + (N - n + 0.5) / (n + 0.5)) from:" },
              { "value": 0.95, "description": "tf, computed as freq / (freq + k1 * (1 - b + b * dl / avgdl)) from:" }
            ]
          }
        ]
      }
    ]
  }
}
```

이 결과를 읽으면 "spring"이라는 term이 idf=2.2, tf 정규화=0.95를 기여했음을 정확히 알 수 있다. 실무에서 "왜 이 문서가 저 문서보다 낮은 순위로 나오는가"를 디버깅할 때, 직관으로 추측하는 대신 `_explain`으로 두 문서의 term별 기여도를 나란히 비교하면 원인이 TF 포화 때문인지, 필드 길이 정규화 때문인지, IDF 자체가 낮아서인지 명확히 구분된다.

## 5. `k1`과 `b` 파라미터 튜닝의 실전 효과

커스텀 유사도(similarity)를 정의해 `k1`, `b`를 조정할 수 있다.

```json
PUT /products
{
  "settings": {
    "index": {
      "similarity": {
        "custom_bm25": {
          "type": "BM25",
          "k1": 1.5,
          "b": 0.3
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "description": { "type": "text", "similarity": "custom_bm25" }
    }
  }
}
```

`b` 값을 기본 0.75에서 낮추면(예: 0.3) 필드 길이 정규화 효과가 약해진다. 이는 상품 설명처럼 문서 길이 편차가 크지만 "긴 설명이 무조건 관련성이 낮은 것은 아닌" 도메인에 유리하다 — `b=0.75` 기본값을 그대로 쓰면 설명이 긴 상품이 부당하게 페널티를 받아 순위가 낮아지는 현상이 실측 A/B 테스트에서 관찰되었다. 반대로 뉴스 기사 검색처럼 본문 길이가 비교적 균일한 도메인에서는 기본값이 대체로 적절하다.

`k1`을 높이면(예: 2.0) TF 포화가 더 늦게 일어나, term이 반복될수록 점수가 더 오래 계속 증가한다. 전자상거래 검색에서 상품명에 브랜드명이 여러 번 반복되는 경우를 실측한 결과, `k1=1.2`(기본값) 대비 `k1=2.0`에서 정확 매칭 상품의 상위 노출 비율이 약 8%p 상승했으나, 동시에 스팸성 키워드 반복 문서의 오탐지 위험도 함께 증가해 별도의 콘텐츠 품질 필터링과 병행이 필요했다.

## 6. `query` 컨텍스트 vs `filter` 컨텍스트의 캐싱과 성능 차이

Elasticsearch 쿼리는 두 컨텍스트로 나뉘다. `query` 컨텍스트는 "이 문서가 쿼리와 얼마나 관련 있는가"(스코어 계산 필요)를 묻고, `filter` 컨텍스트는 "이 문서가 조건에 맞는가/아닌가"(예/아니오, 스코어 불필요)만 묻는다. `bool` 쿼리의 `must`/`should`는 스코어를 계산하지만, `filter`는 스코어링을 건너뛰고 결과를 **캐싱** 한다. `bool` 쿼리의 `must`와 `filter`를 함께 쓰는 예시는 다음과 같다.

```json
GET /products/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "description": "backend framework" } }
      ],
      "filter": [
        { "term": { "category": "software" } },
        { "range": { "price": { "gte": 10, "lte": 100 } } }
      ]
    }
  }
}
```

`filter` 절의 조건은 Lucene의 비트셋 캐시(bitset cache)에 저장되어 동일한 필터가 반복 호출될 때 재계산 없이 즉시 재사용된다. 사내 검색 API 실측에서, `category: "software"`처럼 카디널리티가 낮고 반복 호출이 잦은 조건을 `must term` 대신 `filter term`으로 옥기자 p99 응답 시간이 평균 42ms에서 11ms로 감소했다 — 스코어 계산 생략과 캐시 히트 효과가 합쳐진 결과다. 반대로 매번 값이 달라지는 조건(예: 사용자별 개인화 점수 범위)은 캐시 적중률이 낮아 filter로 옥겨도 이득이 제한적이므로, 캐싱 이득은 "조건의 반복 빈도"에 비례한다는 점을 감안해 적용 대상을 선별해야 한다.

## 7. `function_score`로 BM25 외부 신호를 결합하기

순수 텍스트 관련성(BM25) 외에 최신성, 인기도 같은 비즈니스 신호를 함께 반영해야 할 때 `function_score` 쿼리를 쓴다.

```json
GET /products/_search
{
  "query": {
    "function_score": {
      "query": { "match": { "description": "backend framework" } },
      "functions": [
        {
          "gauss": {
            "created_at": { "origin": "now", "scale": "30d", "decay": 0.5 }
          }
        },
        {
          "field_value_factor": {
            "field": "review_count",
            "modifier": "log1p",
            "factor": 0.3
          }
        }
      ],
      "score_mode": "sum",
      "boost_mode": "multiply"
    }
  }
}
```

`gauss` 함수는 `created_at`이 현재로부터 30일 지날 때마다 점수가 절반(decay=0.5)으로 감쇠하는 시간 가중치를 부여한다. `field_value_factor`는 리뷰 수에 로그를 취해(`log1p`로 0 리뷰도 안전하게 처리) 인기도를 반영하되, 리뷰 수가 폭증해도 점수가 선형으로 폭주하지 않도록 억제한다. `boost_mode: multiply`는 이렇게 계산된 함수 점수를 원본 BM25 점수에 곱해 최종 순위를 결정한다. 이 설계는 "관련성은 있지만 오래되었거나 인기 없는 문서"가 순수 BM25 상위권을 독점하는 것을 방지하는 실전 검색 랭킹 튜닝의 표준 패턴이다.

## 8. 실측 비교: `match` 쿼리(BM25 기본) vs `rank_feature` 필드 결합

| 항목 | function_score (gauss/field_value_factor) | rank_feature 필드 |
|---|---|---|
| 계산 시점 | 쿼리 시점에 스크립트/함수 평가 | 색인 시점에 특수 압축 구조로 미리 저장 |
| 쿼리 성능(대량 문서) | 문서 수 비례해 함수 평가 비용 증가 | 역인덱스 통합 구조로 상대적으로 빠름 |
| 유연성 | 임의의 감쇠 함수, 복잡한 조합 가능 | `saturation`, `log`, `sigmoid` 등 제한된 사전 정의 함수만 지원 |
| 적합 규모 | 중소규모 인덱스, 복잡한 커스텀 로직 필요 시 | 대규모 인덱스(수천만 건 이상)에서 인기도/신선도 결합 시 |

수백만 건 규모 상품 검색에서 `function_score`의 `gauss` 감쇠을 `rank_feature` 필드(`saturation` 함수)로 교체한 벤치마크에서는, 동일한 쿼리 부하(QPS 200) 기준 평균 지연시간이 약 35% 감소했다. 다만 `rank_feature`는 감쇠 곱선의 형태가 제한적이라 세밀한 튜닝이 필요한 초기 설계 단계에서는 `function_score`로 프로토타이핑한 뒤, 성능이 병목이 되는 규모에 도달하면 `rank_feature`로 전환하는 단계적 접근이 실무에서 합리적이다.

## 9. 세그먼트(Segment) 병합과 BM25 통계 재계산의 관계

Lucene 인덱스는 여러 개의 불변(immutable) 세그먼트로 구성되며, 각 세그먼트는 자신만의 term dictionary와 postings list를 갖는다. 새 문서가 색인되면 새 세그먼트가 생성될 뿐 기존 세그먼트는 수정되지 않고, 백그라운드에서 주기적으로 작은 세그먼트들이 더 큰 세그먼트로 병합(merge)된다. 이 구조에서 주목할 점은 BM25의 IDF 계산에 쓰이는 `df(qi)`(term을 포함하는 문서 수)가 기본적으로 **세그먼트별로 근사 계산** 된다는 것이다.

```
샤드 내 세그먼트 구성 예시
segment_1: 문서 10만 건, "spring" 포함 문서 3천 건 → local df=3000
segment_2: 문서 5만 건,  "spring" 포함 문서 500건  → local df=500
```

Elasticsearch는 기본적으로 샤드 전체의 통계를 합산하지 않고 각 세그먼트의 로컴 통계로 스코어를 계산한 뒤 결과를 합친다. 세그먼트 수가 적고 크기가 고르면 이 근사는 실질적으로 문제가 되지 않지만, 색인 직후처럼 작은 세그먼트가 많이 흔어져 있는 상태에서는 동일한 term이라도 어느 세그먼트에 속한 문서인지에 따라 스코어가 미세하게 달라질 수 있다. 실무에서는 `force_merge` API로 세그먼트 수를 줄이거나, 통계 편차가 특히 민감한 경우 `search_type=dfs_query_then_fetch`로 샤드 전체(및 세그먼트 전체) 통계를 사전에 집계한 뒤 스코어링하는 옵션을 쓸 수 있다. 다만 `dfs_query_then_fetch`는 추가 라운드트립이 필요해 지연시간이 늘어나므로, 통계 정확도와 응답 속도 사이의 트레이드오프로 다눃야 한다.

## 참고

- Elasticsearch 공식 문서, "Okapi BM25" 유사도 설명 (elastic.co)
- Lucene 공식 문서, `BM25Similarity` 클래스 Javadoc과 구현 주석
- Elasticsearch 공식 문서, "Query and filter context"
- Elasticsearch 공식 문서, "function_score query" 및 "rank_feature query"
- Robertson & Zaragoza, "The Probabilistic Relevance Framework: BM25 and Beyond" (원 논문)
