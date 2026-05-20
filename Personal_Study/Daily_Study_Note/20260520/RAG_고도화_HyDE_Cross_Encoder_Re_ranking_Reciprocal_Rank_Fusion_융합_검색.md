Notion 원본: https://www.notion.so/3665a06fd6d38182a616e5c7fdd48894

# RAG 고도화 — HyDE, Cross-Encoder Re-ranking과 Reciprocal Rank Fusion 융합 검색

> 2026-05-20 신규 주제 · 확장 대상: AI / Retrieval

## 학습 목표

- Dense retrieval 한계를 만드는 *vocabulary mismatch*와 *query-document length 불균형*을 식별한다
- HyDE가 zero-shot 환경에서 dense retrieval 품질을 끌어올리는 메커니즘을 분석한다
- Bi-encoder retrieval과 Cross-encoder re-ranking의 latency·품질 트레이드오프를 정량화한다
- BM25·Dense·HyDE 결과를 RRF로 결합하는 안전한 구현 패턴을 작성한다

## 1. 왜 단일 retrieval로 충분하지 않은가

첫째는 *vocabulary mismatch*. 사용자가 "결제 실패 시 자동 환불 정책"이라고 묻는데 정작 문서에는 "주문 취소 시 PG 환급 절차"로 적혀 있다면 dense embedding이 충분히 가까운 벡터로 매핑하지 못할 수 있다. 둘째는 *query-document length 불균형*. 사용자 쿼리는 평균 8~12토큰이고 문서 chunk는 200~800토큰이다.

## 2. HyDE — 가설 문서로 쿼리를 풀어 쓴다

HyDE(Hypothetical Document Embeddings)는 Gao et al. 2022에서 제안된 zero-shot retrieval 기법이다. *질문 그대로* 임베딩하지 않고, LLM에게 *그 질문에 답이 될 만한 가설 문서*를 생성하게 한 뒤 그 문서를 임베딩해 검색한다.

```
원래 쿼리: "Redis Stream의 consumer group이 메시지를 다시 처리하는 조건은?"

LLM 프롬프트: "위 질문에 대해 100자 정도의 기술 문서 단편을 작성하라."

가설 문서: "Redis Stream에서 consumer group이 XREADGROUP으로 가져간 메시지는 PEL에 등록된다. XACK가 호출되지 않으면 idle 임계치를 넘긴 후 XCLAIM 또는 XAUTOCLAIM으로 다른 consumer가 재처리한다."

검색 쿼리 벡터: embed(가설 문서)
```

```python
def hyde_retrieve(query: str, k: int = 20) -> list[Doc]:
    hypothesis = llm.generate(
        f"Write a short technical paragraph answering: {query}"
    )
    qvec = embed(hypothesis)
    return vector_store.search(qvec, k=k)
```

운영상 비용은 LLM 호출 한 번 추가다. GPT-4o-mini 또는 Claude Haiku 같은 저렴한 모델로 100~150ms 추가에 그친다.

## 3. Cross-Encoder Re-ranking

Bi-encoder는 dot product로 빠르게 비교되지만 *상호작용 정보가 압축된다*. Cross-encoder는 (쿼리, 문서) *쌍*을 함께 넣어 BERT-like 모델로 처리해 토큰 간 attention을 모두 활용한다.

```python
from sentence_transformers import CrossEncoder

reranker = CrossEncoder("BAAI/bge-reranker-large", max_length=512)

candidates = bi_encoder_retrieve(query, k=100)
pairs = [[query, c.text] for c in candidates]
scores = reranker.predict(pairs, batch_size=32)
top_k = [c for _, c in sorted(zip(scores, candidates), reverse=True)][:8]
```

`bge-reranker-large`는 A10G·L4 GPU에서 batch=32로 200~300ms 안에 끝난다. CPU에서는 5~10초 걸려 사실상 불가능하므로 *re-ranking은 GPU 인스턴스에 배치*가 표준이다.

## 4. Reciprocal Rank Fusion

```
RRF_score(d) = Σ over searchers s: 1 / (k + rank_s(d))
기본 k = 60
```

```python
def rrf(rank_lists: list[list[str]], k: int = 60) -> list[str]:
    scores = defaultdict(float)
    for ranks in rank_lists:
        for r, doc_id in enumerate(ranks, start=1):
            scores[doc_id] += 1.0 / (k + r)
    return [d for d, _ in sorted(scores.items(), key=lambda x: -x[1])]
```

RRF는 *학습이 필요 없다*는 점에서 매력적이다. weighted score fusion은 weight를 튜닝해야 하지만 RRF는 hyperparameter가 사실상 k 하나다.

## 5. 통합 파이프라인

```python
def hybrid_rag(query: str) -> str:
    hypothesis = small_llm.generate(f"답 단편: {query}", max_tokens=120)
    bm25_ranks = elastic_bm25(query, k=100)
    dense_ranks = vector_store.search(embed(query), k=100)
    hyde_ranks = vector_store.search(embed(hypothesis), k=100)
    fused = rrf([bm25_ranks, dense_ranks, hyde_ranks])[:50]
    pairs = [[query, doc.text] for doc in fused]
    scores = reranker.predict(pairs, batch_size=32)
    top_docs = sorted(zip(scores, fused), reverse=True)[:8]
    context = "\n\n".join(doc.text for _, doc in top_docs)
    return generation_llm.generate(prompt(query, context))
```

| 단계 | latency | 비고 |
|---|---|---|
| HyDE 가설 생성 | 110ms | Claude Haiku |
| BM25 검색 | 25ms | Elasticsearch |
| Dense 검색 | 15ms | Qdrant / pgvector HNSW |
| RRF 합산 | 2ms | CPU |
| Cross-encoder rerank | 180ms | bge-reranker-large |
| LLM 생성 | 800~1200ms | Claude Sonnet |
| 합계 | 1.2~1.6초 | end-to-end |

## 6. Chunk 설계

Retrieval 품질의 절반은 chunk 분할에서 정해진다. 권장 범위는 250~500토큰이며 문단 경계에서 자른다.

```python
def semantic_chunking(doc: str, target_tokens: int = 400) -> list[str]:
    sections = split_by_headers(doc)
    chunks = []
    for section in sections:
        if count_tokens(section) <= target_tokens * 1.4:
            chunks.append(section)
        else:
            paragraphs = section.split("\n\n")
            chunks.extend(group_until(paragraphs, target_tokens))
    return chunks
```

*Hierarchical chunking*은 검색은 작은 chunk로, LLM에는 그 chunk를 포함한 큰 chunk를 주는 전략이다.

## 7. 평가 — RAGAS·BEIR·도메인 평가셋

| 차원 | 지표 | 측정 |
|---|---|---|
| Retrieval | recall@k, nDCG@k | 정답 chunk top-k 포함 비율 |
| Faithfulness | claim → context support | 응답의 주장 지지 확인 |
| Answer quality | exact match, LLM judge | 정답 일치 또는 LLM 점수 |

## 8. 운영 함정과 회피

| 함정 | 증상 | 대응 |
|---|---|---|
| HyDE가 *너무 창작* | 무관한 chunk | 가설 길이 제한 |
| Reranker가 거꾸로 정렬 | 직관에 반하는 순서 | 도메인 mismatch, 다른 모델 |
| RRF에 검색기 너무 많이 | latency↑ | 3~4개까지 sweet spot |
| Chunk가 너무 작음 | hallucination 증가 | hierarchical chunking |
| Vector DB index 갱신 지연 | 새 문서 miss | refresh 정책 명시 |

## 9. 권장 시작 구성

```
v1: BM25 only (Elasticsearch) + LLM context
v2: + Dense retrieval
v3: + RRF fusion
v4: + Cross-encoder rerank
v5: + HyDE (도메인 갭이 큰 경우)
```

v2까지가 가장 큰 이득이고, v3·v4는 안정적인 +5~15%다. 시작부터 v5를 도입하면 어느 단계에서 효과가 났는지 추적하기 어렵다.

## 참고

- Gao et al., "Precise Zero-Shot Dense Retrieval without Relevance Labels" (HyDE, 2022)
- Cormack et al., "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods" (SIGIR 2009)
- Karpukhin et al., "Dense Passage Retrieval for Open-Domain Question Answering" (EMNLP 2020)
- BGE Reranker — BAAI/bge-reranker-large, BAAI/bge-reranker-v2-m3 (HuggingFace)
- RAGAS Documentation — Faithfulness, Answer Relevance, Context Precision/Recall metrics
