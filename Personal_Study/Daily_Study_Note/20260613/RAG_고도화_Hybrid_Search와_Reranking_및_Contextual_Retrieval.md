Notion 원본: https://www.notion.so/37e5a06fd6d381afbeeeee8476e16ddc

# RAG 고도화 — Hybrid Search와 Reranking 및 Contextual Retrieval

> 2026-06-13 신규 주제 · 확장 대상: AI

## 학습 목표

- 순수 벡터 검색의 한계를 이해하고 dense + sparse hybrid 검색으로 보완한다
- RRF(Reciprocal Rank Fusion)로 이질적 점수의 두 랭킹을 결합하는 방법을 설명한다
- cross-encoder 기반 reranking이 bi-encoder 검색을 정밀화하는 원리와 비용을 안다
- 청킹 전략과 Contextual Retrieval로 검색 누락(특히 대명사·맥락 손실)을 줄인다

## 1. 순수 벡터 검색의 한계

기본 RAG는 문서를 청크로 쪼개 임베딩(dense vector)으로 색인하고, 질의도 임베딩해 코사인 유사도로 top-k를 뽑는다. 이 의미 기반 검색은 동의어·패러프레이즈에 강하지만 약점이 있다. 정확한 키워드·식별자(에러 코드 `ORA-00942`, 제품 SKU, 함수명)는 임베딩 공간에서 뫌개져 놓치기 쉽다. 또 임베딩 모델의 도메인 밖 용어는 표현력이 떨어진다. 반대로 전통적 키워드 검색(BM25)은 정확 매칭엔 강하나 의미 일반화를 못 한다. 두 방식은 상호 보완적이다.

## 2. Hybrid Search — Dense + Sparse 결합

hybrid 검색은 dense(임베딩)와 sparse(BM25 또는 SPLADE 같은 학습형 sparse) 검색을 함께 돌려 결과를 합친다. 문제는 두 점수의 척도가 달라(코사인 0~1 vs BM25 0~수십) 단순 가중합이 불안정하다는 것이다. 그래서 **순위 기반 융합**인 RRF를 흔히 쓴다.

```
RRF_score(d) = Σ_r  1 / (k + rank_r(d))
```

각 랭커 `r`에서 문서 `d`의 순위 `rank_r(d)`의 역수를 합산한다. `k`(보통 60)는 상위권의 영향력을 완화하는 상수다. 점수 절댓값이 아니라 순위만 쓰므로 척도 차이에 영향받지 않고, 두 검색 모두에서 상위권인 문서가 자연히 높은 점수를 받는다.

```python
def rrf(rankings: list[list[str]], k: int = 60) -> list[str]:
    scores: dict[str, float] = {}
    for ranking in rankings:               # 각 검색기의 결과(순위 정렬된 doc id)
        for rank, doc_id in enumerate(ranking):
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (k + rank + 1)
    return sorted(scores, key=scores.get, reverse=True)
```

Elasticsearch 8.x의 `rrf` retriever, OpenSearch hybrid query, Weaviate/Qdrant의 hybrid 모드가 이를 내장 지원한다.

## 3. Bi-encoder vs Cross-encoder

검색 단계의 임베딩 모델은 **bi-encoder**다 — 질의와 문서를 각각 독립 인코딩해 벡터를 만들고 내적으로 유사도를 쟰다. 문서 벡터를 미리 계산해 두므로 수백만 건도 빠르게 검색할 수 있지만, 질의-문서 간 토큰 수준 상호작용을 보지 못해 정밀도에 한계가 있다.

**cross-encoder**는 질의와 문서를 **하나의 입력으로 합쳐** 트랜스포머에 통과시켜 직접 관련도 점수를 낸다. 토큰 간 어텐션으로 미세한 관련성을 잡아 정밀도가 훨씬 높지만, 질의마다 모든 후보를 재인코딩해야 해 비용이 크다. 그래서 전체 코퍼스에는 못 쓰고, bi-encoder가 추린 소수 후보에만 적용한다.

## 4. 2-Stage Retrieval — Retrieve then Rerank

실무 패턴은 2단계다. 1단계로 hybrid 검색이 빠르게 후보 50~100개를 회수(recall 중시)하고, 2단계로 cross-encoder reranker가 그 후보만 정밀 재정렬해 상위 5~10개를 고른다(precision 중시). 비용은 후보 수에 비례하므로, 후보 크기가 품질-지연의 핵심 다이얼이다.

```python
from sentence_transformers import CrossEncoder

reranker = CrossEncoder("BAAI/bge-reranker-v2-m3")

def retrieve_rerank(query, candidates, top_n=5):
    pairs = [(query, c["text"]) for c in candidates]   # 50~100개 후보
    scores = reranker.predict(pairs)                    # cross-encoder 점수
    ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)
    return [c for c, _ in ranked[:top_n]]
```

reranker는 LLM에 넘길 컨텍스트를 줄여 토큰 비용·환각을 동시에 낮춘다. 관리형 reranker API(Cohere Rerank 등)나 오픈 모델(bge-reranker, Jina) 모두 쓸 수 있다.

## 5. 청킹 전략

검색 품질의 절반은 청킹에서 갈린다. 너무 크면 한 청크에 무관한 내용이 섞여 임베딩이 흐려지고, 너무 작으면 맥락이 잘려 답에 필요한 정보가 분산된다. 실무 출발점은 토큰 기준 고정 크기(예: 512토큰) + 겹침(overlap 50~100토큰)으로 경계에서 잘리는 문장을 보완하는 것이다. 더 나은 방식은 구조 인식 청킹 — 마크다운 헤더, 코드 블록, 표 단위로 나눠 의미 경계를 보존한다. 검색은 작은 청크로 하되 LLM에는 그 청크가 속한 더 큰 부모 블록을 넘기는 "small-to-big"(parent document) 전략도 정밀도와 맥락을 동시에 잡는다.

## 6. Contextual Retrieval — 맥락 손실 보정

청킹의 고질적 문제는 청크가 원문에서 떨어져 나오며 맥락을 잃는다는 것이다. 예컨대 "그 회사의 2025년 매출은 3% 증가했다"는 청크는 "그 회사"가 누구인지 모른 채 색인되어, "ACME 매출"로 검색하면 매칭되지 않는다. Contextual Retrieval은 색인 전에 각 청크에 LLM으로 짧은 맥락 설명을 덧붙인다.

```text
원본 청크:  "그 회사의 2025년 매출은 전년 대비 3% 증가했다."
보강 청크:  "[이 청크는 ACME Corp 2025 연차보고서의 재무 요약 섹션이다.]
            그 회사의 2025년 매출은 전년 대비 3% 증가했다."
```

이렇게 맥락이 주입된 텍스트를 임베딩(contextual embeddings)하고 BM25 색인(contextual BM25)도 함께 만들면, dense·sparse 양쪽에서 회수율이 크게 오른다. Anthropic의 실험에 따르면 contextual embedding + contextual BM25 + reranking을 결합하면 top-20 검색 실패율이 기준 대비 크게 감소한다. 비용은 색인 시점에 청크마다 LLM을 한 번 더 호출하는 것인데, 프롬프트 캐싱으로 원문을 재사용하면 청크당 비용이 낮아진다.

## 7. 평가 — 추측하지 말고 측정

RAG 튜닝은 반드시 정량 평가로 검증한다. 검색 단계는 정답 청크 포함 여부로 **Recall@k**와 순위 품질 **MRR/nDCG**를 본다. 생성 단계는 RAGAS 같은 프레임워크로 faithfulness(답이 컨텍스트에 근거하는가), answer relevance, context precision을 측정한다. 황금 질문-정답 셋을 구축해 두면, "hybrid를 켜니 Recall@10이 0.71→0.86", "reranker 추가로 nDCG가 0.62→0.78"처럼 변경의 효과를 수치로 확인하고 회귀를 막을 수 있다.

| 단계 | 지표 | 의미 |
|---|---|---|
| 검색 | Recall@k | 상위 k에 정답 청크 포함 비율 |
| 검색 | nDCG / MRR | 정답을 얼마나 위에 두는가 |
| 생성 | faithfulness | 답이 컨텍스트에 근거하는가 |
| 생성 | context precision | 넘긴 컨텍스트의 적합도 |

## 8. 쿼리 변환 — 질의 측 개선

검색 품질은 문서 측만이 아니라 질의 측에서도 올린다. 사용자 질의는 짧거나 모호해 임베딩 표현이 빈약할 때가 많다. 대표 기법 셋:

- **Multi-Query**: LLM으로 원 질의를 여러 변형으로 확장해 각각 검색한 뒤 RRF로 합친다. 표현 다양성으로 회수율을 올린다.
- **HyDE(Hypothetical Document Embeddings)**: LLM이 질의에 대한 가상의 정답 문서를 먼저 생성하고, 그 가상 문서를 임베딩해 검색한다. 질의-문서 간 어휘 격차를 줄인다.
- **Query Decomposition**: 복합 질문("A와 B를 비교")을 하위 질문으로 분해해 각각 검색·종합한다.

```python
def multi_query(llm, query, retriever, k=10):
    variants = llm.generate_variants(query, n=3)        # 질의 3개로 확장
    rankings = [retriever.search(q, k) for q in [query, *variants]]
    return rrf(rankings)[:k]                             # RRF로 융합
```

비용은 LLM 추가 호출과 검색 횟수 증가다. 모호한 질의가 많은 일반 사용자 대면 RAG에서 효과가 크고, 정형 질의(코드·로그 검색)에서는 이득이 작다.

## 9. 색인·서빙 아키텍처 고려

운영 RAG는 색인 파이프라인과 서빙 경로를 분리해야 한다. 색인은 (청킹 → 맥락 주입 → 임베딩 → 벡터/역색인 적재) 배치/스트림으로 돌고, 서빙은 (검색 → rerank → 프롬프트 조립 → 생성) 저지연 경로다. 핵심 결정은 (1) 벡터 DB 선택(pgvector는 기존 Postgres와 통합·트랜잭션 일관성, 전용 DB는 ANN 성능·필터링 풍부), (2) ANN 인덱스 파라미터 — HNSW의 `ef_construction`/`M`은 회수율과 색인 비용을, `ef_search`는 질의 시 회수율과 지연을 조절, (3) 메타데이터 필터링을 ANN과 결합(pre-filter vs post-filter)해 권한·시점 제약을 적용하는 것이다. 임베딩 모델을 교체하면 전체 재색인이 필요하므로, 모델 버전을 메타데이터에 기록해 점진 마이그레이션을 설계한다.

## 10. 파이프라인 종합과 trade-off

고도화된 RAG 파이프라인은 대략: 구조 인식 청킹 → Contextual 맥락 주입 → dense + sparse 이중 색인 → hybrid 검색(RRF) → cross-encoder reranking → 상위 청크를 LLM 컨텍스트로 주입, 순이다. 각 단계는 품질을 올리지만 비용을 더한다 — contextual 주입은 색인 비용, reranking은 질의 지연, hybrid는 인프라 복잡도를 늘린다. 따라서 순수 벡터 RAG로 시작해 평가셋으로 약점을 진단하고, 키워드 누락이 보이면 hybrid를, 상위권 정밀도가 부족하면 reranker를, 맥락 손실이 보이면 contextual retrieval을 **측정된 근거에 따라** 단계적으로 추가하는 것이 합리적이다. 모든 기법을 한꺼번에 넣는 것은 비용만 키우고 어느 것이 효과적인지 분리하지 못하게 만든다.

## 참고

- Anthropic, "Introducing Contextual Retrieval": https://www.anthropic.com/news/contextual-retrieval
- "Reciprocal Rank Fusion outperforms Condorcet" (Cormack et al., 2009)
- Sentence-Transformers — Cross-Encoders: https://www.sbert.net/examples/applications/cross-encoder/README.html
- RAGAS — RAG Evaluation: https://docs.ragas.io/
