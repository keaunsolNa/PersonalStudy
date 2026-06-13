Notion 원본: https://www.notion.so/37e5a06fd6d381afbeeeee8476e16ddc

# RAG 고도화 — Hybrid Search와 Reranking 및 Contextual Retrieval

> 2026-06-13 신규 주제 · 확장 대상: AI

## 학습 목표

- 순수 벡터 검색의 한계를 이해하고 dense + sparse hybrid 검색으로 보완한다
- RRF(Reciprocal Rank Fusion)로 이질적 점수의 두 랭킹을 결합하는 방법을 설명한다
- cross-encoder 기반 reranking이 bi-encoder 검색을 정밀화하는 원리와 비용을 안다
- 청킹 전략과 Contextual Retrieval로 검색 누락(대명사·맥락 손실)을 줄인다

## 1. 순수 벡터 검색의 한계

기본 RAG는 문서를 청크로 쪼개 임베딩으로 색인하고, 질의도 임베딩해 코사인 유사도로 top-k를 뽑는다. 이 의미 기반 검색은 동의어·패러프레이즈에 강하지만 약점이 있다. 정확한 키워드·식별자(에러 코드, SKU, 함수명)는 임베딩 공간에서 맙개져 놓치기 쉽다. 반대로 BM25는 정확 매칭엔 강하나 의미 일반화를 못 한다. 둘은 상호 보완적이다.

## 2. Hybrid Search — Dense + Sparse 결합

hybrid 검색은 dense(임베딩)와 sparse(BM25 또는 SPLADE)를 함께 돌려 결과를 합친다. 두 점수의 춙도가 달라 단순 가중합이 불안정해 순위 기반 융합인 RRF를 쓴다.

```
RRF_score(d) = Σ_r  1 / (k + rank_r(d))
```

각 랭커에서 문서의 순위 역수를 합산한다. `k`(보통 60)는 상위권 영향력을 완화한다. 점수 절댓값이 아닌 순위만 쓰므로 춙도 차이에 영향받지 않는다.

```python
def rrf(rankings, k=60):
    scores = {}
    for ranking in rankings:
        for rank, doc_id in enumerate(ranking):
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (k + rank + 1)
    return sorted(scores, key=scores.get, reverse=True)
```

Elasticsearch 8.x rrf retriever, OpenSearch hybrid, Weaviate/Qdrant hybrid 모드가 내장 지원한다.

## 3. Bi-encoder vs Cross-encoder

검색 단계 임베딩 모델은 bi-encoder다 — 질의와 문서를 각각 독립 인코딩해 벡터를 만들고 내적으로 유사도를 쟴다. 문서 벡터를 미리 계산해 빠르지만 토큰 수준 상호작용을 못 본다. cross-encoder는 질의와 문서를 하나의 입력으로 합쳐 트랜스포머에 통과시켜 직접 관련도를 낸다. 정밀도가 높지만 질의마다 모든 후보를 재인코딩해야 해 비용이 크다. 그래서 bi-encoder가 추린 소수 후보에만 적용한다.

## 4. 2-Stage Retrieval — Retrieve then Rerank

1단계로 hybrid 검색이 후보 50~100개를 회수(recall 중시)하고, 2단계로 cross-encoder reranker가 그 후보만 정밀 재정렬해 상위 5~10개를 고른다(precision 중시).

```python
from sentence_transformers import CrossEncoder
reranker = CrossEncoder("BAAI/bge-reranker-v2-m3")

def retrieve_rerank(query, candidates, top_n=5):
    pairs = [(query, c["text"]) for c in candidates]
    scores = reranker.predict(pairs)
    ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return [c for c, _ in ranked[:top_n]]
```

reranker는 LLM에 넘길 컨텍스트를 줄여 토큰 비용·환각을 동시에 낮춘다.

## 5. 청킹 전략

검색 품질의 절반은 청킹에서 갈린다. 너무 크면 무관 내용이 섞여 임베딩이 흐려지고, 너무 작으면 맥락이 잘린다. 출발점은 토큰 기준 고정 크기(예: 512) + 겹침(50~100)이다. 더 나은 방식은 구조 인식 청킹 — 마크다운 헤더, 코드 블록, 표 단위로 나눠 의미 경계를 보존한다. 검색은 작은 청크로 하되 LLM에는 부모 블록을 넘기는 small-to-big 전략도 효과적이다.

## 6. Contextual Retrieval — 맥락 손실 보정

청크가 원문에서 떨어져 나오며 맥락을 잃는 것이 고질적 문제다. 색인 전에 각 청크에 LLM으로 짧은 맥락 설명을 덧붙인다.

```
원본: "그 회사의 2025년 매출은 전년 대비 3% 증가했다."
보강: "[이 청크는 ACME Corp 2025 연차보고서의 재무 요약 섹션이다.]
      그 회사의 2025년 매출은 전년 대비 3% 증가했다."
```

맥락이 주입된 텍스트를 임베딩(contextual embeddings)하고 BM25 색인도 함께 만들면 dense·sparse 양쪽 회수율이 크게 오른다. Anthropic 실험에서 contextual embedding + contextual BM25 + reranking 조합은 top-20 검색 실패율을 크게 줄였다. 비용은 색인 시점 LLM 호출이며 프롬프트 캐싱으로 완화한다.

## 7. 평가 — 추측하지 말고 측정

| 단계 | 지표 | 의미 |
|---|---|---|
| 검색 | Recall@k | 상위 k에 정답 청크 포함 비율 |
| 검색 | nDCG / MRR | 정답을 얼마나 위에 두는가 |
| 생성 | faithfulness | 답이 컨텍스트에 근거하는가 |
| 생성 | context precision | 넘긴 컨텍스트의 적합도 |

황금 질문-정답 셋을 구축해 두면 "hybrid를 켜니 Recall@10이 0.71→0.86" 처럼 변경 효과를 수치로 확인하고 회귀를 막는다.

## 8. 쿼리 변환 — 질의 측 개선

- Multi-Query: 원 질의를 여러 변형으로 확장해 각각 검색 후 RRF로 합친다.
- HyDE: LLM이 가상 정답 문서를 생성해 그것을 임베딩해 검색한다. 어휘 격차를 줄인다.
- Query Decomposition: 복합 질문을 하위 질문으로 분해해 종합한다.

```python
def multi_query(llm, query, retriever, k=10):
    variants = llm.generate_variants(query, n=3)
    rankings = [retriever.search(q, k) for q in [query, *variants]]
    return rrf(rankings)[:k]
```

비용은 LLM 추가 호출과 검색 횟수 증가다. 모호한 질의가 많은 일반 사용자 대면 RAG에서 효과가 크다.

## 9. 색인·서빙 아키텍처 고려

색인은 (청킹→맥락 주입→임베딩→적재) 배치/스트림, 서빙은 (검색→rerank→프롬프트 조립→생성) 저지연 경로로 분리한다. 핵심 결정은 벡터 DB 선택(pgvector는 Postgres 통합·트랜잭션 일관성, 전용 DB는 ANN 성능·필터링), HNSW 파라미터(`ef_construction`/`M`은 색인 비용, `ef_search`는 질의 회수율·지연), 메타데이터 필터링(pre vs post)이다. 임베딩 모델 교체는 전체 재색인이 필요하므로 버전을 메타데이터에 기록해 점진 마이그레이션한다.

## 10. 파이프라인 종합과 trade-off

고도화된 RAG는 대략: 구조 인식 청킹 → Contextual 맥락 주입 → dense + sparse 이중 색인 → hybrid 검색(RRF) → cross-encoder reranking → 상위 청크를 LLM 컨텍스트로 주입, 순이다. 각 단계는 품질을 올리지만 비용을 더한다. 순수 벡터 RAG로 시작해 평가셋으로 약점을 진단하고, 키워드 누락이 보이면 hybrid를, 상위권 정밀도가 부족하면 reranker를, 맥락 손실이 보이면 contextual retrieval을 측정된 근거에 따라 단계적으로 추가하는 것이 합리적이다. 모든 기법을 한꺼번에 넣는 것은 비용만 키우고 어떤 것이 효과적인지 분리하지 못하게 만든다.

## 참고

- Anthropic, "Introducing Contextual Retrieval": https://www.anthropic.com/news/contextual-retrieval
- "Reciprocal Rank Fusion outperforms Condorcet" (Cormack et al., 2009)
- Sentence-Transformers — Cross-Encoders: https://www.sbert.net/examples/applications/cross-encoder/README.html
- RAGAS — RAG Evaluation: https://docs.ragas.io/
