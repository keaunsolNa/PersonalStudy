Notion 원본: https://www.notion.so/3715a06fd6d3818cba98d3febb35da3b

# RAG Reranker — Cross-Encoder vs Late Interaction(ColBERT v2), BGE-Reranker, Cohere Rerank 3 비교

> 2026-05-31 신규 주제 · 확장 대상: Elasticsearch Hybrid Search (20260526) / LLM Speculative Decoding (20260526)

## 학습 목표

- RAG 파이프라인에서 reranker 가 retriever(BM25/dense) 의 한계를 어떻게 보완하는지, top-k 재정렬 단계의 역할을 식별한다
- Cross-Encoder, Bi-Encoder, Late Interaction(ColBERT) 세 아키텍처의 latency / quality trade-off 를 수치로 비교한다
- BGE-Reranker (v2-m3), Cohere Rerank 3, ColBERT v2 의 학습/추론 구조와 운영 적합 시나리오를 정리한다
- nDCG@10 / MRR / Recall 의 차이와 reranker 평가 시 어떤 지표를 봐야 하는지 결정한다

## 1. RAG 파이프라인 위치

```
query → retriever (BM25+dense, top-200) → reranker (top-10) → LLM
```

retriever 는 query/doc 을 독립 인코딩 → 의미 매칭 한계. reranker 가 query×doc 결합 표현으로 재점수 → top 5~10 으로 압축.

## 2. Bi-Encoder vs Cross-Encoder

**Bi-Encoder**: `score = cos(E(q), E(d))`. doc embedding 미리 계산 → O(1)/pair.

**Cross-Encoder**: `score = CLS_head( BERT([q ; SEP ; d]) )`. 매 (q, d) 쌍마다 forward → O(k)/query.

MS MARCO nDCG@10: BM25 0.23, dense 0.35, cross-encoder 0.45. cross-encoder 는 BERT-base GPU 200ms / CPU 2s.

## 3. ColBERT Late Interaction

```
score(q, d) = Σ_{q_i ∈ q} max_{d_j ∈ d} (q_i · d_j)
```

token-level 벡터 미리 저장 + query 만 매번 인코딩. ColBERT v2: residual compression(인덱스 1/10~1/16) + distillation from cross-encoder teacher. nDCG@10 ≈ 0.40, latency = cross-encoder의 1/10.

```python
from ragatouille import RAGPretrainedModel
rag = RAGPretrainedModel.from_pretrained("colbert-ir/colbertv2.0")
rag.index(collection=["doc1 text...", "doc2 text..."], index_name="my_index")
results = rag.search(query="how does ColBERT scoring work?", k=10)
```

## 4. BGE-Reranker v2-m3

```python
from FlagEmbedding import FlagReranker
reranker = FlagReranker('BAAI/bge-reranker-v2-m3', use_fp16=True)
scores = reranker.compute_score([
    ['질문: 캐시 무효화는?', '문서: TTL 과 LRU 의 결합으로 ...'],
    ['질문: 캐시 무효화는?', '문서: 오늘 점심은 ...'],
])
```

| 모델 | 파라미터 | 언어 | max_length |
|---|---|---|---|
| bge-reranker-base | 278M | en, zh | 512 |
| bge-reranker-large | 568M | en, zh | 512 |
| bge-reranker-v2-m3 | 568M | 100+ | 1024 |
| bge-reranker-v2-gemma | 2B | en, zh+ | 4096 |

v2-m3 + fp16 + onnxruntime 로 GPU 1장당 50~100 QPS.

## 5. Cohere Rerank 3

```python
import cohere
co = cohere.Client(API_KEY)
resp = co.rerank(
    model="rerank-3",
    query="What is ColBERT late interaction?",
    documents=[d.text for d in docs],
    top_n=10
)
```

| 항목 | self-host (BGE/ColBERT) | Cohere Rerank 3 |
|---|---|---|
| 도입 비용 | GPU infra | API key |
| latency | 50~300 ms | 100~400 ms |
| 외부 전송 | 없음 | 있음 |
| 문서 길이 | 512~4K | 100KB |

기업 내부 문서 RAG 는 self-host default. 외부 콘텐츠는 Cohere 가 단순.

## 6. Hard Negative Mining

```
1. BM25 seed → top-100 negative
2. (q, d_pos, d_neg) 트리플
3. teacher cross-encoder 점수 → false negative 제거
4. listwise loss (RankNet, ListMLE)
```

평가는 nDCG@10 + MRR@10 + Recall@k(검색 단계) 같이.

## 7. 운영 튜닝

| 결정 | 권장값 |
|---|---|
| retriever top-k | 50~200 (k=100 sweet spot) |
| reranker top-n | 5~10 |
| batch size | A10 24GB / 512 토큰 / fp16 → 64 |
| max_length | 512 (passage) / 1024 (긴 문서) |
| cache | query × doc 점수 (수 분 TTL) |

bge-v2-m3 + ONNX + GPU 1장:

| top-k | max_len | batch | p50 | p99 |
|---|---|---|---|---|
| 50 | 512 | 64 | 32 ms | 58 ms |
| 100 | 512 | 64 | 58 ms | 95 ms |
| 200 | 512 | 64 | 115 ms | 195 ms |
| 200 | 1024 | 32 | 280 ms | 520 ms |

## 8. Hybrid + Rerank

BM25 + dense → RRF → top-100 → reranker → top-10 → LLM.

MS MARCO nDCG@10: BM25 0.23, dense 0.35, RRF 0.39, +bge-v2-m3 0.49, +ColBERT v2 0.46, +cross-encoder large 0.51.

## 9. 함정과 권고

- chunk 단위 점수 집계 (mean/max) — 짧은 사실 질문은 max 가 정답인 경우 많음
- 언어 mismatch — 한국어 query 에 영어 cross-encoder 는 성능 폭락
- long doc attention quadratic — chunking 이 보통 더 효율적
- ColBERT 인덱스 저장 비용 (dense bi-encoder 의 5~20x)
- open benchmark 학습 모델은 도메인 다른 corpus 에서 성능 하락 — in-domain eval set 필수

## 참고

- Khattab & Zaharia, "ColBERT" SIGIR 2020
- Santhanam et al., "ColBERTv2" NAACL 2022
- BAAI BGE-Reranker https://huggingface.co/BAAI/bge-reranker-v2-m3
- Cohere Rerank 3 Docs https://docs.cohere.com/docs/rerank-2
- Pinecone Learn — Rerankers https://www.pinecone.io/learn/series/rag/rerankers/
