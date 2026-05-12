Notion 원본: https://www.notion.so/35e5a06fd6d38193bef3e7a998e41687

# RAG Hybrid Search — BM25 + Dense Vector Reciprocal Rank Fusion과 Cross-Encoder Re-ranking

> 2026-05-12 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트 / Elasticsearch

## 학습 목표

- BM25 의 term frequency 한계와 dense embedding 의 의미 일반화가 보완 관계임을 이해
- Reciprocal Rank Fusion(RRF) 의 수식과 Elasticsearch / OpenSearch / Weaviate 구현 비교
- Cross-Encoder 재순위(re-ranking)가 추가하는 정확도와 latency cost 산정
- 운영 시 sparse/dense/RRF/rerank 4단 파이프라인의 SLA 설계

## 1. RAG 검색 단계가 왜 어려운가

LLM 응답 품질은 *retrieval 단계의 recall@k* 가 결정한다. 정답 문서가 top-k 안에 들어와야 generation 이 인용할 수 있다.

검색 방법은 크게 두 갈래다.

| 방법 | 강점 | 약점 |
|---|---|---|
| **Sparse(BM25)** | 정확한 term 매칭, 고유명사·코드·숫자에 강함 | 동의어·문맥 일반화 약함 |
| **Dense(embedding)** | 의미 유사도, 다국어, 패러프레이즈 | OOV term, 정확한 코드 토큰 누락 가능 |

실제 query mix 는 두 경향이 섞여 있다. "JDBC URL 에 useSSL=false 옵션이 뭐냐" 는 정확한 token 매칭이 핵심이고, "DB 연결이 자꾸 끊기는데 원인은?" 은 의미 유사도가 핵심이다. 한 가지 방법만 쓰면 절반 query 에서 망한다.

## 2. BM25 한 줄 복습

`score(q, d) = Σ IDF(t) · (tf(t, d) · (k1 + 1)) / (tf(t, d) + k1 · (1 - b + b · |d| / avgdl))`

- `k1` (보통 1.2~2.0): tf 의 saturation 강도
- `b` (보통 0.75): 길이 정규화. 짧은 doc 에 페널티
- `IDF(t) = log((N - df + 0.5) / (df + 0.5) + 1)`

ES/OpenSearch 의 `similarity: BM25` 가 기본. 토크나이저(`standard`, `nori` 등)와 stopword 가 결과의 70% 를 좌우한다. 한국어는 `nori_user_dict` 로 도메인 용어를 명시해야 recall 이 안정된다.

## 3. Dense Embedding 의 실전

embedding 모델 선택축:

| 축 | 옵션 | 비고 |
|---|---|---|
| 차원 | 384 / 768 / 1024 / 1536 | 차원↑ → recall↑, 인덱스 비용↑ |
| 언어 | mono(영어 위주) vs multilingual(BGE-m3, E5-multilingual) | 한국어 포함 시 multilingual 필수 |
| 길이 | 512 / 8k / 32k 토큰 | 긴 문맥은 chunk 전략과 연계 |
| 라이센스 | Apache-2.0(BGE), CC-BY-NC, 상용 API | 운영 정책 점검 |

벡터 인덱스는 HNSW(Hierarchical Navigable Small World) 가 사실상 표준. `ef_construction`, `M`, `ef_search` 의 트레이드오프:

| 파라미터 | 큰 값 | 작은 값 |
|---|---|---|
| `M` | 그래프 degree ↑, recall↑, RAM↑ | 인덱스 작음, recall↓ |
| `ef_construction` | 인덱스 품질↑, 빌드 시간↑ | 빠른 빌드, 품질↓ |
| `ef_search` | recall↑, latency↑ | 빠른 검색, recall↓ |

운영 권장(중규모): `M=16, ef_construction=200, ef_search=100`. recall@10 가 0.95 미만이면 `ef_search` 부터 올린다.

## 4. Reciprocal Rank Fusion(RRF)

서로 다른 ranker 의 결과를 *점수 보정 없이* 합치는 방법.

`RRF_score(d) = Σ_r 1 / (k + rank_r(d))`

- `k` 는 보통 60(원논문 권장)
- `rank_r(d)` 는 ranker r 가 doc d 에게 부여한 순위(1부터)
- 점수 분포가 달라도 순위만 보므로 normalization 불필요

장점:

1. 두 ranker 의 점수 스케일이 달라도 그대로 결합 가능
2. 한 ranker 에서 1등인 doc 이 다른 ranker 에서 100등이어도 합쳐서 상위에 둘
3. ranker 추가가 단순 합산이라 ensemble 확장 쉬움

ES 8.8+ 와 OpenSearch 2.10+ 가 native `rrf` retriever 를 제공한다.

```json
POST /docs/_search
{
  "retriever": {
    "rrf": {
      "retrievers": [
        { "standard": { "query": { "match": { "body": "JDBC useSSL 옵션" } } } },
        { "knn": { "field": "body_embed", "query_vector_builder": {
            "text_embedding": { "model_id": "bge-m3", "model_text": "JDBC useSSL 옵션" }
          }, "k": 50, "num_candidates": 200 } }
      ],
      "rank_window_size": 100,
      "rank_constant": 60
    }
  },
  "size": 10
}
```

`rank_window_size` 는 각 retriever 의 상위 N 만 RRF 에 참여. 작게 잡으면 latency 가 줄지만 long-tail recall 이 손해. 보통 50~200.

## 5. Cross-Encoder Re-ranking

Bi-encoder(embedding) 는 query 와 doc 을 *독립적으로* 인코딩한 뒤 cosine 비교한다. Cross-encoder 는 `[CLS] query [SEP] doc [SEP]` 처럼 *둘을 같이 넣어* 직접 relevance score 를 회귀한다. 정확도가 훨씬 높지만 query × 후보 개수만큼 inference 가 필요하다.

운영 구성:

1. RRF 또는 단일 retriever 로 후보 100 개 추림
2. Cross-encoder(예: `bge-reranker-v2-m3`, 568M 파라미터) 로 100 개 재점수
3. 상위 K(보통 4~10) 를 LLM 컨텍스트로 투입

latency 예시(A100 GPU, batch=32):

| 후보 수 | rerank latency P50 | rerank latency P99 |
|---|---|---|
| 50 | 25ms | 60ms |
| 100 | 45ms | 110ms |
| 200 | 90ms | 220ms |

GPU 없이 CPU 만으로 운영하면 BGE-reranker-base(110M) 가 한계. P99 가 500ms 를 쉽게 넘기므로 *Embedding API 처럼 외부 vendor* 를 고려한다.

## 6. 전체 파이프라인 latency budget

총 latency 가 어디서 나오는지 분해해야 SLA 설계가 가능하다.

| 단계 | P50 | P99 | 비고 |
|---|---|---|---|
| Query embedding | 8ms | 30ms | 캐시 적중 시 1ms |
| BM25 retrieval | 15ms | 60ms | shard 수 / 인덱스 크기 |
| Dense kNN(HNSW) | 12ms | 45ms | ef_search 100 기준 |
| RRF merge | 1ms | 3ms | in-memory |
| Cross-encoder rerank(100) | 45ms | 110ms | GPU 가속 |
| **합계** | **~80ms** | **~250ms** | |

LLM generation 이 800~3000ms 이므로 retrieval 단계가 전체의 5~10% 면 양호하다.

## 7. 코드 예제 — Python 클라이언트

```python
from elasticsearch import Elasticsearch
from sentence_transformers import CrossEncoder

es = Elasticsearch("http://localhost:9200")
reranker = CrossEncoder("BAAI/bge-reranker-v2-m3", max_length=512)

def hybrid_search(query: str, top_k: int = 10) -> list[dict]:
    # 1. RRF retrieval (sparse + dense)
    body = {
        "retriever": {
            "rrf": {
                "retrievers": [
                    {"standard": {"query": {"match": {"body": query}}}},
                    {"knn": {"field": "body_embed",
                             "query_vector_builder": {
                                 "text_embedding": {
                                     "model_id": "bge-m3",
                                     "model_text": query}},
                             "k": 50, "num_candidates": 200}}
                ],
                "rank_window_size": 100,
                "rank_constant": 60
            }
        },
        "size": 50,
        "_source": ["id", "title", "body"]
    }
    hits = es.search(index="docs", body=body)["hits"]["hits"]

    # 2. Cross-encoder rerank
    pairs = [(query, h["_source"]["body"]) for h in hits]
    scores = reranker.predict(pairs, batch_size=32)
    ranked = sorted(zip(hits, scores), key=lambda x: x[1], reverse=True)
    return [h["_source"] | {"score": float(s)} for h, s in ranked[:top_k]]
```

## 8. 평가 — recall@k 와 nDCG

검색 품질 측정은 *주관적 직감* 이 아니라 라벨링된 데이터셋이 필요하다.

- **recall@k**: 정답 문서가 top-k 안에 들어온 비율. 단순하지만 강력.
- **MRR(Mean Reciprocal Rank)**: 1 / 정답 첫 등장 순위. 첫 번째 정답에 가중.
- **nDCG@k**: 등급(graded relevance)이 있는 경우. 0/1 이 아니라 0~3 점 등급.

권장 워크플로:

1. 운영 query 로그에서 200~500 query 샘플링
2. 각 query 의 정답 doc 을 사람(또는 LLM judge) 으로 라벨
3. 변경 전후의 recall@10, MRR 을 비교, 95% CI 가 겹치지 않으면 유의미
4. A/B 운영 시 click-through, follow-up question 률 등 proxy metric 으로 보완

## 9. 흔한 실수

1. **Chunk 크기 통일** — 너무 작으면(128 token) context 가 부족, 너무 크면(2048 token) embedding 의 평균화로 의미 희석. 256~512 가 보편적. 문서 구조에 맞춰 *semantic chunking*(헤더 단위) 가 더 효과적인 경우가 많다.
2. **embedding 모델과 reranker 의 도메인 불일치** — 둘 다 같은 base 패밀리(BGE) 로 맞추면 score 분포가 안정.
3. **RRF k 값을 작게 잡기** — k=10 처럼 작게 하면 상위 1~2 개가 점수를 독식. 60 이상 권장.
4. **하이브리드 비활성화 fallback 부재** — dense 모델 서버 다운 시 sparse 만으로 동작하는 graceful degradation 을 미리 만들어 둔다.

## 참고

- Cormack 외, "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods" — SIGIR 2009
- BGE: BAAI General Embedding — https://github.com/FlagOpen/FlagEmbedding
- "Elasticsearch RRF retriever" — https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html
- Nogueira & Cho, "Passage Re-ranking with BERT" — arXiv 1901.04085
- Karpukhin 외, "Dense Passage Retrieval for Open-Domain QA" — EMNLP 2020
