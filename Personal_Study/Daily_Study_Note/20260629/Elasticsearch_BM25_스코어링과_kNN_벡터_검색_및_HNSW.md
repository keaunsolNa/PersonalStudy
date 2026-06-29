Notion 원본: https://app.notion.com/p/38e5a06fd6d3812f9036d02f67814bd0

# Elasticsearch BM25 스코어링과 kNN 벡터 검색 및 HNSW

> 2026-06-29 신규 주제 · 확장 대상: DB

## 학습 목표

- BM25 의 TF 포화와 문서 길이 정규화가 TF-IDF 와 무엇이 다른지 수식으로 설명한다
- `k1`, `b` 파라미터 튜닝이 점수에 미치는 영향을 조정한다
- HNSW 그래프 기반 ANN 의 색인 구조와 `m`, `ef_construction` 의 역할을 파악한다
- BM25 와 kNN 을 RRF 로 결합하는 하이브리드 검색을 구성하고 비용을 비교한다

## 1. BM25 — 왜 TF-IDF 가 아니라 BM25 인가

Elasticsearch 의 기본 점수 함수는 Lucene 의 BM25 다. 고전 TF-IDF 의 두 가지 약점을 보완한다. 첫째, 단어 빈도(TF)가 무한정 점수를 올리지 못하도록 *포화(saturation)* 시킨다. 둘째, 긴 문서가 단지 길어서 유리해지지 않도록 *문서 길이로 정규화* 한다.

BM25 의 핵심 수식은 다음과 같다.

```
score(D, Q) = Σ IDF(qi) · ( f(qi,D) · (k1 + 1) ) / ( f(qi,D) + k1 · (1 - b + b · |D|/avgdl) )

  f(qi,D)  : 문서 D 에서 단어 qi 의 빈도
  |D|      : 문서 D 의 길이(토큰 수)
  avgdl    : 전체 문서 평균 길이
  k1       : TF 포화 강도 (기본 1.2)
  b        : 길이 정규화 강도 (기본 0.75)
  IDF(qi)  = ln(1 + (N - n + 0.5) / (n + 0.5))
```

`f(qi,D)` 가 커져도 분모에 같은 항이 있어 점수가 점근적으로 `k1+1` 에 수렴한다. 이것이 포화다. 단어가 100번 나온 문서가 10번 나온 문서보다 *10배가 아니라 약간만* 높은 점수를 받는다. 또 `b·|D|/avgdl` 항이 긴 문서의 TF 를 깍아 길이 편향을 보정한다.

## 2. k1, b 튜닝과 유사도 커스터마이징

`k1` 은 TF 포화 속도를, `b` 는 길이 정규화 강도를 제어한다. 짧고 균일한 문서(제품명, 태그)에는 `b` 를 낮춰 길이 영향을 줄이고, 길이가 검색 의도와 무관한 로그성 문서에는 `b=0` 에 가깝게 둔다.

```json
PUT /products
{
  "settings": {
    "index": {
      "similarity": {
        "custom_bm25": {
          "type": "BM25",
          "k1": 1.0,
          "b": 0.3
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": { "type": "text", "similarity": "custom_bm25" }
    }
  }
}
```

`b=0` 이면 길이 정규화를 끄고, `b=1` 이면 최대로 적용한다. `k1=0` 이면 TF 를 무시하고 단순 단어 존재 여부(binary)에 가깝워진다. 튜닝 효과는 `_explain` API 로 점수 분해를 보며 검증해야 추측이 아닌 근거 기반으로 조정할 수 있다.

```json
GET /products/_explain/1
{ "query": { "match": { "title": "wireless keyboard" } } }
```

## 3. dense_vector 와 kNN 검색

키워드 매칭은 동의어·의미 유사성을 못 잡는다. "노트북 가방"과 "랩탑 케이스"는 BM25 점수가 0 이다. 이를 해결하는 것이 임베딩 벡터의 최근접 이웃(kNN) 검색이다. 텍스트를 임베딩 모델로 벡터화해 `dense_vector` 필드에 저장하고, 질의 벡터와 코사인 유사도가 높은 문서를 찾는다.

```json
PUT /docs
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "dense_vector",
        "dims": 768,
        "index": true,
        "similarity": "cosine"
      }
    }
  }
}

POST /docs/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.12, -0.03],
    "k": 10,
    "num_candidates": 100
  }
}
```

정확한 kNN(brute-force)은 모든 벡터와 거리를 계산해 O(N·d) 라 대규모에서 비현실적이다. 그래서 Elasticsearch 는 근사 최근접 이웃(ANN) 알고리즘으로 HNSW 를 쓴다.

## 4. HNSW — 계층적 탐색 가능한 작은 세계 그래프

HNSW(Hierarchical Navigable Small World)는 벡터를 다층 그래프로 색인한다. 상위 층은 노드가 적고 멀리 점프하는 "고속도로"이고, 하위 층으로 갈수록 촌촆해진다. 탐색은 최상위 층에서 시작해 질의 벡터와 가까운 이웃으로 그리디하게 내려가며, 각 층에서 지역 최적에 도달하면 한 층 아래로 내려간다. 이 구조로 평균 O(log N) 탐색이 가능하다.

색인 품질을 결정하는 두 파라미터가 있다.

| 파라미터 | 의미 | 트레이드오프 |
|---|---|---|
| `m` | 노드당 최대 이웃 수 | 클수록 정확·메모리↑·색인↓ |
| `ef_construction` | 색인 시 탐색 후보 수 | 클수록 그래프 품질↑·색인 시간↑ |
| `num_candidates` | 질의 시 탐색 후보 수 | 클수록 recall↑·지연↑ |

```json
PUT /docs
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "dense_vector", "dims": 768, "index": true,
        "similarity": "cosine",
        "index_options": { "type": "hnsw", "m": 16, "ef_construction": 100 }
      }
    }
  }
}
```

ANN 은 100% 정확하지 않다 — recall 과 지연의 트레이드오프다. `num_candidates` 를 올리면 진짜 최근접을 놓칠 확률이 줄지만 질의 지연이 늘어난다. 정확도가 절대적인 도메인이라면 ANN 대신 exact kNN 을 써야 하지만 대규모에서는 비용이 급증한다.

## 5. 하이브리드 검색 — RRF 결합

실무 최선은 BM25(키워드 정밀)와 kNN(의미 유사)을 결합하는 하이브리드 검색이다. 두 점수는 스케일이 달라 단순 합산이 어렵다. Reciprocal Rank Fusion(RRF)은 점수가 아니라 *순위* 를 결합해 이 문제를 우회한다.

```
RRF_score(d) = Σ 1 / (k + rank_i(d))     # 보통 k = 60
```

각 검색 결과에서의 순위 역수를 더하므로, 두 방식 모두에서 상위에 오른 문서가 높은 종합 점수를 받는다. 점수 정규화나 가중치 튜닝 없이도 견고하게 동작하는 것이 RRF 의 장점이다.

```json
POST /docs/_search
{
  "retriever": {
    "rrf": {
      "retrievers": [
        { "standard": { "query": { "match": { "text": "노트북 가방" } } } },
        { "knn": { "field": "embedding", "query_vector": [], "k": 50, "num_candidates": 200 } }
      ],
      "rank_window_size": 50,
      "rank_constant": 60
    }
  }
}
```

## 6. 자원·지연 비용 비교

벡터 검색은 BM25 대비 메모리와 색인 비용이 크게 다르다.

| 항목 | BM25 (역색인) | kNN (HNSW) |
|---|---|---|
| 색인 메모리 | 포스팅 리스트, 비교적 작음 | 벡터 + 그래프, 큼 (벡터는 힙/오프힙 상주 권장) |
| 색인 속도 | 빠름 | `ef_construction` 에 비례해 느림 |
| 질의 지연 | 매우 빠름 (ms) | `num_candidates` 에 비례 |
| 메모리 추산 | 텍스트 길이 의존 | 약 `dims × 4byte × 문서수 × (1 + m 관련 오버헤드)` |

768차원 float32 벡터 100만 개면 벡터 데이터만 약 3GB 이고 HNSW 그래프가 추가된다. 이 메모리가 노드 힙/페이지 캐시에 상주해야 지연이 낮으므로, 벡터 검색은 BM25 보다 노드 사이징에 훨씬 민감하다. int8/스칼라 양자화로 메모리를 1/4 수준으로 줄이는 옵션도 정확도 손실을 감수하고 쓸 수 있다.

## 7. 선택 기준

순수 키워드로 충분한 검색(코드, ID, 정확한 명칭 매칭)에는 BM25 가 더 빠르고 메모리 효율적이며 결과가 설명 가능하다. 의미 기반 검색, 동의어·다국어, RAG 의 문서 검색 단계에는 벡터 검색이 필요하다. 대부분의 실서비스 검색은 하이브리드가 최선인데, 사용자가 정확한 단어를 입력하면 BM25 가, 모호하게 입력하면 kNN 이 보완하기 때문이다. 다만 임베딩 모델 운영·재색인 비용, 벡터 차원에 따른 메모리 부담을 감당할 수 있는지가 도입 전 점검 포인트다.

## 8. 벡터 양자화로 메모리 줄이기

768~1536차원 float32 벡터는 메모리를 많이 먹는다. Elasticsearch 는 정확도를 약간 희생하고 메모리를 줄이는 양자화 옵션을 제공한다. `int8_hnsw` 는 각 차원을 1바이트로 압축해 메모리를 약 1/4 로 줄이고, `bbq`(better binary quantization)는 비트 단위로 압축해 더 공격적으로 줄인다.

```json
PUT /docs
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "dense_vector", "dims": 768, "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "int8_hnsw",
          "m": 16, "ef_construction": 100
        }
      }
    }
  }
}
```

양자화의 핵심 트릭은 *압축된 벡터로 후보를 빠르게 좁히고, 원본(또는 더 정밀한) 벡터로 상위 후보만 재채점(rescore)* 하는 2단계 전략이다. 이렇게 하면 메모리·속도 이득을 얻으면서 최종 정확도 손실을 작게 유지한다. 다만 양자화는 임베딩 분포에 민감해, 코사인 유사도 차이가 미세한 도메인에서는 recall 저하가 눈에 띄게 될 수 있으므로 도입 전 실제 데이터로 recall@k 를 측정해야 한다.

## 9. 필터링과 색인 설계 함정

실서비스 kNN 은 거의 항상 메타데이터 필터(카테고리, 가격대, 권한)와 함께 쓰인다. 여기에 "pre-filter vs post-filter" 문제가 있다. post-filter(먼저 kNN 으로 k개를 뽑고 나중에 필터)는 필터 통과 문서가 k개보다 적어질 수 있다. Elasticsearch 의 `knn` + `filter` 는 HNSW 그래프 탐색 *중에* 필터를 적용하는 pre-filter 방식이라 이 문제를 완화한다.

```json
POST /docs/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [],
    "k": 10,
    "num_candidates": 100,
    "filter": { "term": { "category": "laptop" } }
  }
}
```

주의할 함정이 몇 가지 있다. 첫째, 필터가 매우 선택적(전체의 0.1%만 통과)이면 HNSW 그래프 탐색이 필터를 통과하는 이웃을 찾느라 `num_candidates` 를 크게 늘려야 하고, 극단적으로는 brute-force 가 더 빠를 수 있다. 둘째, `dense_vector` 는 한번 색인하면 `dims` 나 `similarity` 를 바꿀 수 없어 reindex 가 필요하므로, 임베딩 모델 교체 계획을 색인 설계 단계에서 미리 고려해야 한다. 셋째, 세그먼트별로 HNSW 그래프가 따로 만들어지므로 `force_merge` 로 세그먼트를 합치면 질의 지연이 줄지만 색인 시점 비용이 크다 — 쓰기가 멈춘 정적 색인에만 적용하는 것이 안전하다.

## 참고

- Elasticsearch Reference — Similarity module (BM25), kNN search, dense_vector
- Lucene BM25Similarity 소스
- Malkov & Yashunin, "Efficient and robust approximate nearest neighbor search using HNSW graphs" (2016)
- Elastic Blog — Reciprocal Rank Fusion (RRF) and hybrid retrieval
