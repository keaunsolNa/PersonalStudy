Notion 원본: https://www.notion.so/3565a06fd6d381b2b96bd6eff5bca1ab

# RAG Re-ranking — Cross-Encoder와 ColBERT 후처리 랭킹 모델 비교

> 2026-05-04 신규 주제 · 확장 대상: HNSW, LLM RAG 아키텍처와 pgvector

## 학습 목표

- Bi-Encoder 기반 1차 검색과 Cross-Encoder 기반 re-ranking 의 *모델 구조와 비용* 차이를 정확히 식별한다
- ColBERT 의 late interaction 이 cross-encoder 의 정확도와 bi-encoder 의 속도 사이에서 어떤 절충을 만들었는지 토큰 단위 비교로 이해한다
- Reciprocal Rank Fusion(RRF) · Cohere Rerank · BGE Reranker · ColBERT v2 의 latency / 품질 / 운영 비용을 비교 매트릭스로 정리한다
- 실제 RAG 파이프라인에서 *후보 K* · *재정렬 비용 budget* · *최종 컨텍스트 길이* 의 세 변수를 어떻게 잡아 retrieval@k 와 latency 를 동시에 만족시킬지 결정 절차를 만든다

## 1. Bi-Encoder vs Cross-Encoder 의 본질

벡터 검색의 출발점인 *bi-encoder* 는 query 와 document 를 *각자 독립적으로* 임베딩한다.

```
embed(query)      → q_vec     (예: 768 차원)
embed(document)   → d_vec     (사전 색인 가능)
score = cosine(q_vec, d_vec)  (또는 dot product)
```

두 임베딩이 독립적이므로 document 임베딩을 *사전에 계산* 해 인덱스에 저장할 수 있다. HNSW / IVF / pgvector 같은 ANN 인덱스가 이 가정 위에서 동작한다. 추론 비용은 query 한 번, 인덱스 lookup 한 번이다.

대조적으로 *cross-encoder* 는 query 와 document 를 *함께* 한 시퀀스로 묶어 모델에 넣는다.

```
[CLS] query [SEP] document [SEP]   → encoder 12-layer attention
                                   → score (스칼라 0.0 ~ 1.0)
```

이 구조의 핵심은 *모든 query 토큰이 모든 document 토큰과 attention 으로 직접 상호작용* 한다는 점이다. 정확도는 매우 높지만, 한 (query, document) 쌍마다 forward pass 가 한 번씩 필요하다. 인덱스에 사전 계산해 둘 수 없다. 1억 문서 corpus 에서 cross-encoder 만으로 검색하는 건 비현실적이다.

해법은 *2단계*: bi-encoder 로 *수십 ~ 수백* 개의 후보를 빠르게 골라 내고, cross-encoder 로 그 좁은 후보 풀에서만 정확한 점수를 다시 매긴다. 이게 RAG 의 표준 파이프라인이다.

## 2. ColBERT 의 Late Interaction 절충

ColBERT (Contextualized Late Interaction over BERT) 는 *bi-encoder 의 사전 색인 가능성* 과 *cross-encoder 의 풍부한 상호작용* 을 토큰 단위로 절충한 모델이다. 핵심 아이디어:

```
query   = [q_1, q_2, ..., q_m]   ← 각 토큰의 임베딩 m 개
doc     = [d_1, d_2, ..., d_n]   ← 각 토큰의 임베딩 n 개

score(query, doc) = Σ over i [ max over j ( q_i · d_j ) ]
```

*MaxSim* 이라고 부르는 이 연산은 각 query 토큰에 대해 가장 유사한 document 토큰을 고르고 그 합으로 점수를 매긴다. document 의 토큰 임베딩은 사전 색인 가능하다(인덱스 크기는 일반 bi-encoder 의 m 배 정도). query 만 새로 인코딩하면 된다.

| 모델 | 인코딩 단위 | 인덱스 크기 (1만 문서, 평균 200 토큰 기준) | latency (single query) |
|---|---|---|---|
| Bi-Encoder | 문서 1벡터 | ≈ 30MB (768d × 1만) | 10ms (HNSW lookup) |
| ColBERT v2 | 문서 N토큰 벡터 (압축) | ≈ 600MB (10만개 × 32d 압축) | 30ms (PLAID + scoring) |
| Cross-Encoder | 사전 색인 불가 | N/A | 200~2000ms (후보 N 회 forward) |

ColBERT v2 는 v1 대비 *centroid clustering* + *residual quantization* 으로 인덱스 크기를 1/10 수준까지 압축했고, *PLAID* 라는 검색 알고리즘으로 candidate pruning 을 추가해 latency 도 크게 줄였다. 정확도는 cross-encoder 의 95~98% 수준을 유지한다.

## 3. Reciprocal Rank Fusion 의 자리

ColBERT 와 cross-encoder 가 *모델 기반* re-ranking 이라면, RRF 는 *모델 없는* fusion 이다. 여러 검색 시그널(BM25 점수, 벡터 검색 점수, 메타데이터 부스트 등)을 *순위로만* 결합한다.

```
RRF_score(d) = Σ over signal i [ 1 / (k + rank_i(d)) ]
              (보통 k = 60)
```

장점: 학습이 필요 없고, 점수 스케일이 다른 시그널들을 *순위* 로 정규화해 자연스럽게 결합한다. 가중치가 거의 hyperparameter-free 다.

한계: *순위 정보만* 사용하므로 점수 차이의 크기를 못 본다. BM25 가 1위 문서에 점수 50, 2위에 점수 1을 줬다면 둘 사이의 *압도적 차이* 가 있는데 RRF 는 그저 1/(60+1), 1/(60+2) 로만 본다.

실전 권장: BM25 + dense vector 의 1차 후보 fusion 에는 RRF 가 적합하고, 그 위에 cross-encoder 또는 ColBERT 로 한 번 더 정렬하는 2단 구조가 균형 잡힌다.

## 4. Cohere Rerank · BGE Reranker · ColBERT v2 비교

상용/오픈소스 re-ranker 들의 운영 특성 비교.

| 모델 | 형태 | 모델 크기 | 평균 latency / 50 문서 | 다국어 | 라이선스 |
|---|---|---|---|---|---|
| Cohere rerank-3 | API (managed) | 비공개 | 100~200ms (네트워크 포함) | O (100+) | Commercial |
| BGE-reranker-v2-m3 | Cross-Encoder | 568M | 80~120ms (GPU A10) | O | MIT |
| BGE-reranker-large | Cross-Encoder | 350M | 50~80ms (GPU A10) | O | MIT |
| MS MARCO MiniLM | Cross-Encoder | 22M | 15~30ms (GPU T4) | EN 위주 | Apache 2 |
| ColBERT v2 | Late Interaction | 110M | 30~50ms (GPU T4) | O (다국어 변형 존재) | Apache 2 |

선택 기준:

- *legacy 인프라에 GPU 가 없고 latency 가 매우 중요* → MS MARCO MiniLM 정도의 작은 cross-encoder 를 CPU 로
- *한국어 포함 다국어 정확도 중심* → BGE-reranker-v2-m3 (자체 호스팅) 또는 Cohere rerank-multilingual
- *runtime API key 의존을 피하고 싶음* → BGE 시리즈 자체 호스팅
- *낮은 latency 와 정확도 동시 필요, 인덱스 크기 여유 있음* → ColBERT v2

## 5. 후보 K · re-rank budget · 컨텍스트 길이 변수의 묶음

RAG 파이프라인에서 다음 세 변수가 서로 트레이드오프된다.

| 변수 | 작을 때 | 클 때 |
|---|---|---|
| 1차 후보 K | recall 손실 위험 | re-rank 비용 증가 |
| Re-rank top N (최종 LLM 입력) | LLM 토큰 비용 ↓, 일부 정답 누락 위험 | 토큰 비용 ↑, 노이즈 ↑ |
| Context window (LLM) | 짧으면 N 강제 제한 | 길어도 attention 분산으로 품질 저하 가능 |

경험적 권장값(영어/한국어 RAG 평균 기준):

- 1차 K: 50 ~ 100 (BM25 + vector 후 RRF 머지 결과)
- Re-rank 최종 N: 5 ~ 10
- LLM 컨텍스트로 들어가는 chunk 길이: 250 ~ 500 토큰

이 조합이 *retrieval@10 = 90%* 부근을 안정적으로 유지하는 sweet spot 이다. 데이터셋 특성에 따라 다르므로, 측정 가능한 evaluation set 을 갖고 변수를 grid search 하는 게 정공법.

## 6. 실전 파이프라인 (Python pseudo-code)

세 단계 — BM25 + Dense → RRF fusion → Cross-Encoder rerank — 의 최소 코드.

```python
from rank_bm25 import BM25Okapi
from sentence_transformers import SentenceTransformer, CrossEncoder
import numpy as np

bi_encoder    = SentenceTransformer("BAAI/bge-m3")
cross_encoder = CrossEncoder("BAAI/bge-reranker-v2-m3")

def search(query: str, corpus: list[str], k: int = 50, top_n: int = 5):
    # 1차 BM25
    bm25_scores = bm25.get_scores(tokenize(query))
    bm25_top = np.argsort(-bm25_scores)[:k]

    # 1차 vector
    q_vec = bi_encoder.encode(query, normalize_embeddings=True)
    sims  = doc_matrix @ q_vec
    vec_top = np.argsort(-sims)[:k]

    # 2차 RRF fusion
    K = 60
    rrf = {}
    for rank, idx in enumerate(bm25_top):
        rrf[idx] = rrf.get(idx, 0) + 1 / (K + rank + 1)
    for rank, idx in enumerate(vec_top):
        rrf[idx] = rrf.get(idx, 0) + 1 / (K + rank + 1)
    fused = sorted(rrf.items(), key=lambda x: -x[1])[:k]
    candidates = [corpus[i] for i, _ in fused]

    # 3차 Cross-Encoder rerank
    pairs  = [(query, doc) for doc in candidates]
    scores = cross_encoder.predict(pairs)
    order  = np.argsort(-scores)[:top_n]
    return [(candidates[i], float(scores[i])) for i in order]
```

성능 튜닝 포인트:

- `cross_encoder.predict` 에 `batch_size=32~64` 를 명시해 GPU 활용률 올리기.
- 1차 단계의 BM25 와 vector 검색은 *병렬* 실행해 latency 가 max(BM25, vector) 만큼만 들도록.
- 후보 50개에 대한 cross-encoder 호출이 평균 latency 의 80% 를 차지한다. budget 이 빠듯하면 후보 K 를 30 정도로 줄이고 BGE-reranker-large(미디엄 사이즈) 로 교체.

## 7. ColBERT 인덱스 운영 패턴

ColBERT v2 의 인덱스는 다음 단계로 만들어진다.

```
1. 모든 문서를 토큰 임베딩으로 인코딩
2. 모든 토큰 임베딩에 대해 K-means clustering (centroid)
3. 각 토큰 임베딩을 (centroid_id, residual_quantized) 로 압축
4. centroid 별 inverted file 구성
5. PLAID 검색기 초기화
```

검색 시 query 토큰들을 인코딩하고 PLAID 가 *centroid level 에서 candidate set 을 좁힌 뒤* MaxSim 을 그 후보들에 대해서만 계산한다.

운영 관점에서 ColBERT 의 결점은 *인덱스 빌드 시간* 이다. 1만 문서 인덱스에 분 단위, 100만 문서면 시간 단위가 걸린다. 또한 add/delete 가 잘 지원되지 않아 *주기적 전체 재빌드* 가 일반적인 운영 패턴이다.

데이터 변경 빈도가 높은 시나리오라면 ColBERT 를 *batch refresh* 모드로 쓰면서 hot 데이터에는 BGE-reranker 를 cross-encoder 로 얹는 하이브리드 구성이 현실적이다.

## 8. 평가 지표 — recall@k, MRR, NDCG@k

re-ranker 의 효과를 정량 평가하는 표준 지표 세 가지.

| 지표 | 정의 | 무엇을 측정 |
|---|---|---|
| recall@k | top-k 안에 정답이 있을 확률 | "정답을 잡았는가" 만 본다 |
| MRR | 1 / 첫 정답의 rank 의 평균 | 정답이 *얼마나 위에* 있는가 |
| NDCG@k | rank 기반 가중 정확도 | 다중 정답 / 부분 정답 환경에 적합 |

RAG 의 출력 품질에 가장 강한 상관을 보이는 건 보통 *NDCG@5* 다. recall@10 이 같아도 NDCG@5 가 더 높으면 LLM 이 더 정확한 답을 만든다.

권장 평가 절차: 100~500 개의 (query, gold_passage) 쌍을 라벨링하고, *re-ranker 를 끄고 켰을 때* 의 NDCG@5 를 비교. 1.5~3 배 향상이 나오는 게 일반적이다. 향상 폭이 0.1 미만이면 re-ranker 가 corpus 와 잘 안 맞는 신호이므로 다른 모델을 시도한다.

## 참고

- ColBERT v2 (Santhanam et al., NAACL 2022): https://arxiv.org/abs/2112.01488
- ColBERT 원본 (Khattab and Zaharia, SIGIR 2020): https://arxiv.org/abs/2004.12832
- Reciprocal Rank Fusion (Cormack et al., SIGIR 2009): https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf
- BGE Reranker — BAAI: https://huggingface.co/BAAI/bge-reranker-v2-m3
- Sentence-Transformers — Cross-Encoder docs: https://www.sbert.net/examples/applications/cross-encoder/README.html
