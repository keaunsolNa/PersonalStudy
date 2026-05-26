Notion 원본: https://www.notion.so/36c5a06fd6d38137beb0e1d6c6ff28ff

# Elasticsearch Hybrid Search — BM25와 kNN dense_vector RRF 점수 결합

> 2026-05-26 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표
- BM25 lexical 검색과 kNN semantic 검색의 실패 모드를 비교하고, 왜 단일 방식으로는 production 검색 품질이 부족한지 정량적으로 정리한다.
- Elasticsearch 8.14+의 `dense_vector` 필드와 HNSW 파라미터(`m`, `ef_construction`, `num_candidates`)를 운영 관점에서 설계한다.
- RRF(Reciprocal Rank Fusion) 수식과 Linear combination 방식의 trade-off를 이해하고, Retriever API로 sub_retriever 트리를 구성한다.
- recall@k, MRR, P99 latency 를 `profile: true` 와 함께 측정해 BM25 단독 / kNN 단독 / RRF 융합의 ROI 를 비교한다.

## 1. BM25 vs Dense Vector — 본질 차이와 실패 모드

BM25 는 term frequency × inverse document frequency 기반의 **lexical** 일치 점수다. "스프링 부트 의존성 주입" 을 질의하면 토큰 `스프링`, `부트`, `의존성`, `주입` 이 본문에 등장한 횟수로 점수를 계산한다. 반대로 dense vector 는 텍스트를 384/768/1024차원 임베딩으로 매핑한 뒤 cosine similarity 로 **semantic** 유사도를 잰다.

각 방식의 실패 모드를 4년 운영하며 가장 자주 본 사례 기준으로 정리한다.

| 방식 | 강점 | 실패 모드 |
|------|------|-----------|
| BM25 | 정확한 키워드, 사번/제품코드, 신조어 | synonym 부재 (`DI` vs `의존성 주입`), 동의어 oversight, 오탈자 |
| Dense Vector | 의미 검색, multilingual, paraphrase | lexical drift (정확 ID 검색 실패), out-of-domain term, 임베딩 모델 bias |

실제 사내 검색 로그에서 BM25 단독 recall@10 은 0.62 였고, multilingual-e5-base 단독은 0.71 이었다. 둘을 RRF 로 합치자 0.84 로 뛰었다. 이는 두 방식이 **상호 보완적 오류 분포** 를 갖기 때문이다 — BM25 가 못 잡는 paraphrase 를 dense 가 잡고, dense 가 못 잡는 exact ID 를 BM25 가 잡는다.

## 2. ES 8.x `dense_vector` 필드 설계

Elasticsearch 8.x 의 `dense_vector` 는 `index: true` 로 두어야 kNN 검색이 가능하다. `index: false` 면 `script_score` brute-force 만 가능하며 latency 가 비선형적으로 증가한다.

```json
PUT /docs-v3
{
  "mappings": {
    "properties": {
      "title":    { "type": "text", "analyzer": "nori" },
      "body":     { "type": "text", "analyzer": "nori" },
      "category": { "type": "keyword" },
      "embedding": {
        "type": "dense_vector",
        "dims": 768,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "int8_hnsw",
          "m": 16,
          "ef_construction": 200
        }
      }
    }
  }
}
```

핵심 파라미터.

- `similarity`: `cosine` / `dot_product` / `l2_norm` / `max_inner_product`. 모델이 정규화된 벡터를 내보내면 `dot_product` 가 가장 빠르다(magnitude 계산 생략). multilingual-e5 처럼 L2 정규화 출력이면 `dot_product` 가 사실상 cosine 과 동치다.
- `m`: HNSW 그래프의 노드당 연결 수. 기본 16. 클수록 recall↑, 메모리·build time↑. 운영에서 16~32 권장.
- `ef_construction`: 인덱싱 시 탐색 폭. 기본 100, 200~400 으로 올리면 build 시간 1.5~2배 늘지만 query-time recall 이 안정적이다.
- `index_options.type`: `hnsw` / `int8_hnsw` / `int4_hnsw` / `bbq_hnsw` (8.14+). int8 양자화는 메모리 75% 절감, recall 손실 1~2%p 수준이라 default 로 채택해도 무방하다.

100만 문서 × 768dim float32 는 약 3.0 GB, int8_hnsw 로 양자화하면 약 0.8 GB 다. heap 외 off-heap 메모리에 들어가므로 OS page cache 가 충분한지 확인해야 한다.

## 3. kNN 쿼리 — top-level `knn`, filtered kNN

ES 8.x 에서 kNN 은 두 가지 진입점이 있다. top-level `knn` 절과 `query` 내부의 `knn` 쿼리다. 8.14+ 부터는 retriever API 가 권장 경로지만, 레거시 호환을 위해 둘 다 안다.

```json
POST /docs-v3/_search
{
  "knn": {
    "field": "embedding",
    "query_vector_builder": {
      "text_embedding": {
        "model_id": "intfloat__multilingual-e5-base",
        "model_text": "스프링에서 의존성을 주입하는 방법"
      }
    },
    "k": 10,
    "num_candidates": 100,
    "filter": { "term": { "category": "spring" } }
  },
  "size": 10
}
```

- `k`: 최종 반환 수.
- `num_candidates`: 각 shard 에서 후보로 뽑을 수. `k` 보다 충분히 커야 recall 이 산다. 일반적으로 `num_candidates = max(100, 10 × k)`.
- `query_vector_builder.text_embedding`: ES 의 inference endpoint 를 사용해 서버 사이드 임베딩. 모델은 사전에 `_inference` API 로 배포해야 한다. 클라이언트에서 미리 임베딩을 만들어 `query_vector` 로 넘기는 방법도 있다.
- `filter`: pre-filter. HNSW 그래프 탐색 중 필터를 적용하므로 cardinality 가 낮은 필터는 recall 을 떨어뜨릴 수 있다(8.12+ 부터 ACORN 알고리즘으로 개선됨).

Java client 예시 (Elasticsearch Java API Client 8.14+, 구 High Level Rest Client 는 deprecated).

```java
ElasticsearchClient client = ...;

KnnSearch knn = KnnSearch.of(k -> k
    .field("embedding")
    .queryVectorBuilder(qvb -> qvb
        .textEmbedding(te -> te
            .modelId("intfloat__multilingual-e5-base")
            .modelText("스프링에서 의존성을 주입하는 방법")))
    .k(10)
    .numCandidates(100)
    .filter(f -> f.term(t -> t.field("category").value("spring"))));

SearchResponse<Doc> resp = client.search(s -> s
    .index("docs-v3")
    .knn(knn)
    .size(10), Doc.class);
```

## 4. RRF(Reciprocal Rank Fusion)

RRF 는 Cormack, Clarke, Buettcher (SIGIR 2009) 가 제안한 rank-based fusion 이다. 점수 정규화가 필요 없는 게 핵심이다 — 각 retriever 가 매긴 **순위(rank)** 만 본다.

수식:

```
score_rrf(d) = Σ_i  1 / (k + rank_i(d))
```

`rank_i(d)` 는 i번째 retriever 에서 문서 d 의 순위(1-indexed). `k` 는 smoothing 상수, 기본 60. 1위 문서의 기여도는 1/61 ≈ 0.0164, 10위는 1/70 ≈ 0.0143 으로 차이가 작아 **상위권 안정성** 을 제공한다.

ES 8.8+ 의 `rrf` retriever:

```json
POST /docs-v3/_search
{
  "retriever": {
    "rrf": {
      "retrievers": [
        {
          "standard": {
            "query": {
              "match": { "body": "스프링 의존성 주입" }
            }
          }
        },
        {
          "knn": {
            "field": "embedding",
            "query_vector_builder": {
              "text_embedding": {
                "model_id": "intfloat__multilingual-e5-base",
                "model_text": "스프링에서 의존성을 주입하는 방법"
              }
            },
            "k": 50,
            "num_candidates": 200
          }
        }
      ],
      "rank_window_size": 50,
      "rank_constant": 60
    }
  },
  "size": 10
}
```

- `rank_window_size`: 각 sub-retriever 에서 가져올 후보 수. RRF 점수 계산은 이 윈도우 내 문서에 대해서만 수행되므로, recall@10 이 목표면 50~100 권장.
- `rank_constant`: 위 수식의 k. 60 이 논문 default 이고 거의 모든 도메인에서 robust 하다. 1~10 으로 낮추면 1위 우대가 강해진다.

## 5. Linear Combination — function_score / script_score

RRF 가 default 면 Linear combination 은 언제 쓰는가. **점수의 절대값** 이 의미를 갖거나 (예: 0.7 이상만 노출), retriever 간 신뢰도 가중치를 명시적으로 다르게 두고 싶을 때다.

```json
POST /docs-v3/_search
{
  "query": {
    "script_score": {
      "query": {
        "match": { "body": "스프링 의존성 주입" }
      },
      "script": {
        "source": "0.3 * _score + 0.7 * cosineSimilarity(params.q, 'embedding') + 1.0",
        "params": {
          "q": [0.012, -0.034, ...]
        }
      }
    }
  }
}
```

함정 — BM25 의 `_score` 는 **unbounded** 다 (term 수, doc length 따라 0.x ~ 30+). cosine similarity 는 `[-1, 1]`, `+1.0` 보정 후 `[0, 2]`. 둘을 그대로 `0.3 * + 0.7 *` 하면 BM25 가 압도한다. 운영에서는 다음 중 하나로 정규화한다.

- **Min-Max scaling per query**: top-N 문서의 점수를 [0,1] 로 압축. 쿼리마다 분포가 달라 재현성이 떨어짐.
- **Sigmoid**: `1 / (1 + exp(-α(s - β)))`. α, β 를 그리드 서치로 튜닝.
- **Rank-based**: 결국 RRF 로 회귀한다.

이런 이유로 8.14+ 환경에서는 **RRF retriever 가 default**, linear combination 은 특수 케이스로만 쓴다.

## 6. Retriever API — sub_retriever 트리 구성

8.14+ 의 retriever API 는 검색 파이프라인을 트리 구조로 표현한다. `standard`, `knn`, `rrf`, `text_similarity_reranker` 가 노드 타입이다.

3-stage 파이프라인 예시 — BM25 + dense 를 RRF 로 합치고, 상위 50개를 cross-encoder 로 rerank.

```json
POST /docs-v3/_search
{
  "retriever": {
    "text_similarity_reranker": {
      "retriever": {
        "rrf": {
          "retrievers": [
            { "standard": { "query": { "match": { "body": "스프링 DI" } } } },
            { "knn": {
                "field": "embedding",
                "query_vector_builder": {
                  "text_embedding": {
                    "model_id": "intfloat__multilingual-e5-base",
                    "model_text": "스프링 DI"
                  }
                },
                "k": 50, "num_candidates": 200
            } }
          ],
          "rank_window_size": 50
        }
      },
      "field": "body",
      "inference_id": "cohere-rerank-v3",
      "inference_text": "스프링 DI",
      "rank_window_size": 50
    }
  },
  "size": 10
}
```

sub_retriever 트리의 장점은 **합성** 이다. 카테고리별로 다른 임베딩 모델을 쓰는 multi-index 검색도 RRF 의 retrievers 배열에 각각의 `knn` 노드를 넣어 구현한다. 트리는 사실상 검색 DAG 의 선언적 정의다.

## 7. 운영 — 모델 버전, reindex, quantization

**임베딩 모델 버전 관리**. 모델을 v1 → v2 로 바꾸면 차원이 같더라도 벡터 공간이 다르다. v1 문서와 v2 쿼리를 섞으면 cosine 이 무의미하다. 대응 패턴:

1. 새 인덱스 `docs-v4` 를 v2 모델로 풀 색인.
2. alias `docs-current` 를 atomic 하게 swap.
3. v1 인덱스는 7일 후 삭제 (롤백 마진).

```json
POST /_aliases
{
  "actions": [
    { "remove": { "index": "docs-v3", "alias": "docs-current" } },
    { "add":    { "index": "docs-v4", "alias": "docs-current" } }
  ]
}
```

**Reindex 비동기 실행**. 1000만 문서 reindex 는 수 시간이 걸리므로 `wait_for_completion=false` 로 task 화한다.

```json
POST /_reindex?wait_for_completion=false&slices=auto
{
  "source": { "index": "docs-v3" },
  "dest":   { "index": "docs-v4", "pipeline": "embed-v2" }
}
```

task id 로 `GET /_tasks/{task_id}` 폴링하며 throttling (`requests_per_second`) 으로 클러스터 부하를 조절한다. `slices=auto` 는 shard 수만큼 병렬 처리한다.

**Vector quantization**. 8.14+ 의 옵션.

- `int8_hnsw`: float32 → int8, 메모리 1/4. recall 손실 1~2%p.
- `int4_hnsw`: 1/8. recall 손실 3~5%p, 평가 후 채택.
- `bbq_hnsw` (Better Binary Quantization): 1/32. ANN 후보 생성용으로 raw float 과 rescore 조합.

실측 — 500만 문서, 768dim:

| 옵션 | 메모리 | 색인 시간 | recall@10 | P99 latency |
|------|--------|-----------|-----------|-------------|
| hnsw (float32) | 14.6 GB | 42 min | 0.913 | 38 ms |
| int8_hnsw | 3.8 GB | 39 min | 0.901 | 31 ms |
| int4_hnsw | 1.9 GB | 38 min | 0.872 | 28 ms |
| bbq_hnsw + rescore | 0.6 GB | 35 min | 0.905 | 35 ms |

운영 default 는 `int8_hnsw`. 메모리 압박 심하면 `bbq_hnsw + rescore_vector`.

## 8. 성능 — recall@k, MTEB-style 평가, profile

**recall@k 측정**. golden set 이 필요하다. 사내 로그에서 클릭/체류시간이 충분히 긴 쿼리-문서 쌍을 추출해 500~2000건의 (query, relevant_doc_ids) 셋을 만든다.

```python
def recall_at_k(es, query, golden_ids, k=10, mode="rrf"):
    body = build_body(query, k, mode)
    resp = es.search(index="docs-current", body=body)
    hits = [h["_id"] for h in resp["hits"]["hits"]]
    return len(set(hits) & set(golden_ids)) / len(golden_ids)
```

실측 비교 (사내 2,300 query golden set):

| 모드 | recall@10 | MRR@10 | P50 (ms) | P99 (ms) |
|------|-----------|--------|----------|----------|
| BM25 only | 0.624 | 0.481 | 8 | 24 |
| kNN only (e5-base) | 0.712 | 0.553 | 18 | 48 |
| RRF (BM25 + kNN) | 0.841 | 0.687 | 22 | 58 |
| RRF + cohere-rerank | 0.892 | 0.748 | 95 | 210 |

RRF 만으로 recall +21.7%p 이득에 latency 는 ~2배. rerank 까지 가면 품질 +5%p 더 얻지만 latency 4배라서 검색 결과 페이지 외 추천/연관문서 같은 비-critical path 에 한정한다.

**`profile: true`** 로 단계별 비용을 본다.

```json
POST /docs-current/_search
{
  "profile": true,
  "retriever": { "rrf": { ... } },
  "size": 10
}
```

응답의 `profile.shards[].dfs.knn` 에 HNSW 탐색의 `vector_operations_count` 가 찍힌다. `num_candidates` 를 200 → 400 으로 늘려 recall 이 0.5%p 만 오르고 vector_ops 가 2배 늘면 ROI 가 없다는 신호다. RRF retriever 는 sub-retriever 별 latency 도 분리해 보여준다.

## 참고
- Elastic Docs — Dense vector field type (https://www.elastic.co/guide/en/elasticsearch/reference/current/dense-vector.html)
- Elastic Docs — kNN search (https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html)
- Elastic Docs — Retrievers and RRF (https://www.elastic.co/guide/en/elasticsearch/reference/current/retrievers-overview.html)
- Elastic Docs — Vector quantization, BBQ (https://www.elastic.co/guide/en/elasticsearch/reference/current/dense-vector.html#dense-vector-quantization)
- Cormack, Clarke, Buettcher, "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods", SIGIR 2009
- Malkov & Yashunin, "Efficient and robust approximate nearest neighbor search using Hierarchical Navigable Small World graphs", IEEE TPAMI 2018
- Apache Lucene — HNSW implementation (https://lucene.apache.org/core/9_10_0/core/org/apache/lucene/util/hnsw/package-summary.html)
- Elasticsearch Java API Client 8.x 마이그레이션 가이드 (https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/migrate-hlrc.html)
