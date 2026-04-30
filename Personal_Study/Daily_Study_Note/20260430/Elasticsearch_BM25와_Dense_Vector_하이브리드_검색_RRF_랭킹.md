Notion 원본: https://app.notion.com/p/3525a06fd6d38138bab3f055dbd7b0f8

# Elasticsearch BM25와 Dense Vector 하이브리드 검색·RRF 랭킹

> 2026-04-30 신규 주제 · 확장 대상: Elasticsearch · 검색

## 학습 목표

- BM25 의 점수 공식과 length normalization 파라미터(b, k1) 이해
- HNSW 기반 dense vector 검색이 Elasticsearch 8 에 통합된 구조 분석
- 하이브리드 검색에서 두 점수를 결합하는 RRF(Reciprocal Rank Fusion) 알고리즘
- 운영 환경에서 cold start, sharding, ANN recall 트레이드오프 관리

## 1. BM25 — 텍스트 검색의 기본 공식

Lucene/Elasticsearch 의 default similarity 는 BM25(Okapi BM25)다. 점수 공식은 다음과 같다.

```
score(D, Q) = Σ_t∈Q  IDF(t) ·  ( f(t,D) · (k1+1) ) / ( f(t,D) + k1·(1 - b + b·|D|/avgdl) )

IDF(t) = log( (N - n(t) + 0.5) / (n(t) + 0.5) + 1 )
```

- `f(t,D)`: 문서 D 에 term t 가 등장한 횟수
- `|D|`: 문서 길이(토큰 수), `avgdl`: 평균 문서 길이
- `k1`(기본 1.2): term frequency saturation 강도. 클수록 빈도 증가가 점수에 더 크게 기여
- `b`(기본 0.75): 길이 정규화 강도. 1에 가까울수록 짧은 문서가 유리

```json
PUT /docs
{
  "settings": {
    "similarity": {
      "tuned_bm25": {
        "type": "BM25",
        "k1": 1.4,
        "b": 0.7
      }
    }
  },
  "mappings": {
    "properties": {
      "body": { "type": "text", "similarity": "tuned_bm25" }
    }
  }
}
```

장점은 단순함과 키워드 정확성. 한계는 어휘 일치(lexical match)에만 의존 — "노트북" 과 "랩탑" 이 유사어임을 모른다.

## 2. Dense Vector 검색 — 의미 기반 보완

문장을 임베딩 모델로 d차원 벡터로 바꿔 두면 cosine similarity 가 의미적 유사도를 잡는다. Elasticsearch 8.0+ 는 `dense_vector` + HNSW 인덱스를 내장 지원한다.

```json
PUT /docs
{
  "mappings": {
    "properties": {
      "body":   { "type": "text" },
      "embedding": {
        "type": "dense_vector",
        "dims": 768,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "hnsw",
          "m": 16,
          "ef_construction": 100
        }
      }
    }
  }
}
```

- `m`: HNSW 그래프 노드당 이웃 수. 클수록 recall ↑, 메모리 ↑.
- `ef_construction`: 인덱스 빌드 시 후보 풀. 클수록 품질 ↑, 빌드 속도 ↓.
- 검색 시 `num_candidates` 가 ef 역할 — 클수록 recall ↑, latency ↑.

쿼리:

```json
POST /docs/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.12, -0.04, ...],
    "k": 10,
    "num_candidates": 100
  }
}
```

## 3. 하이브리드 검색이 필요한 이유

벡터만 쓰면 정확한 키워드 일치(상품 모델명, 약어)를 놓친다. BM25 만 쓰면 동의어/패러프레이징을 놓친다. 두 점수를 결합한 결과가 단독보다 일관되게 좋다.

흔한 실패 시나리오:

- "samsung galaxy s24" 쿼리: vector 만 쓰면 "삼성 휴대폰 신모델" 이라는 일반 문서를 위로 올림. BM25 가 모델명 매칭으로 정확한 페이지를 잡아줌.
- "여름 캠핑에 좋은 텐트" 쿼리: BM25 만 쓰면 "여름", "캠핑", "텐트" 가 한 번씩 나오는 잡문이 위로. vector 가 의도("4계절 텐트가 아닌 여름용") 를 잡음.

## 4. 결합 방식 — Linear Combination 의 함정

가장 단순한 결합은 두 점수에 가중치를 곱해 더하기다.

```
final_score = α · bm25_score + (1-α) · vector_score
```

문제: 두 점수의 **분포가 다르다**. BM25 는 0~30+ 범위에 두꺼운 꼬리. cosine 은 [-1, 1]. 정규화 없이 더하면 한쪽이 압도하며, 정규화도 데이터셋마다 적정 α 가 달라 매번 튜닝해야 한다.

## 5. RRF — Reciprocal Rank Fusion

RRF 는 점수 대신 **순위(rank)** 만 본다.

```
RRF_score(d) = Σ_r∈ranks  1 / (k + rank_r(d))
```

각 검색 결과 리스트(BM25, vector, 또 다른 검색기) 에서 문서 d 가 몇 등인지를 보고 `1/(k+rank)` 를 더한다. `k` 는 보통 60. 점수 분포에 무관하므로 정규화 부담이 없고, 가중치 튜닝도 거의 필요 없다.

Elasticsearch 8.8+ 는 RRF 를 native 지원한다.

```json
POST /docs/_search
{
  "query": {
    "match": { "body": "여름 캠핑 텐트" }
  },
  "knn": {
    "field": "embedding",
    "query_vector": [...],
    "k": 50,
    "num_candidates": 200
  },
  "rank": {
    "rrf": {
      "rank_window_size": 100,
      "rank_constant": 60
    }
  },
  "size": 10
}
```

`rank_window_size` 는 각 검색기가 최대 몇 개를 본 뒤 RRF 결합할지를 정한다. 너무 작으면 좋은 후보를 놓치고, 너무 크면 응답이 느려진다. 100 이 균형점.

## 6. RRF vs Linear — 실험적 비교

같은 데이터셋(약 100만 문서, FAQ 검색)에서 평가했다고 가정.

| 방식 | NDCG@10 | 가중치 튜닝 횟수 |
|---|---|---|
| BM25 only | 0.62 | 0 |
| Vector only | 0.58 | 0 |
| Linear (정규화 후) | 0.71 (α=0.6) | α 그리드 서치 7회 |
| RRF | 0.73 | 0 |

RRF 가 가중치 튜닝 없이도 linear 와 비슷하거나 더 나은 결과를 낸다. 운영 단순성에서 차이가 크다.

## 7. 임베딩 모델 선택과 multilingual

한국어 검색에는 `BAAI/bge-m3`, `intfloat/multilingual-e5-large`, `jhgan/ko-sroberta-multitask` 등이 적합. 768~1024 차원이 표준. 차원이 클수록 표현력은 늘지만 인덱스 메모리가 비례해서 증가한다.

빠른 사이징 가이드:

```
HNSW 메모리 ≈ docs · (dims · 4 byte + m · 4 byte · 2 + 약간의 메타)
```

100만 문서 · 768 dims · m=16 → 약 4GB. 1억 문서면 400GB → 단일 노드로 무리. shard 분산 + replica 가 필요.

## 8. 운영 체크리스트

- **Embedding 일관성**: 인덱싱 시 모델 버전과 검색 시 모델 버전이 같아야 함. mapping 에 `meta.embedding_model: "bge-m3-v1"` 박아 둔다.
- **Cold start**: 새 임베딩 모델로 재인덱싱 시 수 시간이 든다. 새 인덱스 빌드 → alias swap 패턴.
- **HNSW recall vs latency**: `num_candidates` 를 100 → 500 으로 올리면 recall +5%, latency +200%. SLA 와 합의 필요.
- **데이터 갱신**: HNSW 는 incremental delete 시 그래프 단편화가 누적. 일정 주기 reindex 또는 force_merge.
- **Filter + KNN**: pre-filter (`filter` 안 knn) 가 strict 하면 그래프 탐색이 비효율적. post-filter 와 비교 후 결정.

## 9. 다음 단계 — Reranker

검색 후 Top-K(예: 50~100) 를 cross-encoder 에 넣어 재정렬하면 정확도가 한 단계 더 오른다. `cross-encoder/ms-marco-MiniLM-L-6-v2` 같은 모델은 한 쌍의 (query, doc) 을 통째로 보고 0~1 점수를 매긴다.

| 단계 | 후보 수 | latency | 정확도 |
|---|---|---|---|
| BM25 + KNN + RRF | 100 | 30ms | NDCG 0.73 |
| + Reranker (top 50) | 10 | +80ms | NDCG 0.81 |

이 3단 파이프라인(retrieval → fusion → rerank)이 RAG 와 검색 시스템의 표준이 됐다.

## 참고

- Robertson, Zaragoza, "The Probabilistic Relevance Framework: BM25 and Beyond"
- Cormack, Clarke, Buettcher, "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods" (SIGIR 2009)
- Elasticsearch 공식 문서, "kNN search", "RRF"
- Malkov, Yashunin, "Efficient and robust approximate nearest neighbor search using HNSW graphs"
- HuggingFace MTEB Leaderboard — 임베딩 모델 비교
