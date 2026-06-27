Notion 원본: https://app.notion.com/p/38c5a06fd6d3813da2e0c5116c0bcfc1

# RAG 검색 증강 생성과 청킹 전략 및 Reranking 파이프라인

> 2026-06-27 신규 주제 · 확장 대상: AI(LLM 응용)

## 학습 목표

- RAG 파이프라인의 인덱싱·검색·생성 단계와 실패 지점을 안다.
- 고정·재귀·시맨틱 청킹과 overlap·메타데이터 설계를 적용한다.
- 밀집(임베딩)과 희소(BM25) 검색을 하이브리드로 결합한다.
- Cross-encoder Reranking 2단계 구조를 구현한다.

## 1. RAG가 푸는 문제

LLM은 학습 이후 지식·비공개 문서·최신 사실을 모른다. RAG는 질문 시점에 외부 지식 베이스에서 관련 문서를 검색해 프롬프트에 주입하고, 그 근거 위에서 답하게 한다. 환각을 줄이고 출처를 인용한다.

## 2. 청킹 전략

청크가 크면 주제가 섞여 유사도가 흐려지고, 작으면 맥락이 잘린다. 재귀적 청킹은 문단→문장→단어 순으로 자연 경계를 보존한다.

```python
from langchain.text_splitter import RecursiveCharacterTextSplitter
splitter = RecursiveCharacterTextSplitter(chunk_size=512, chunk_overlap=64,
    separators=["\n\n", "\n", ". ", " ", ""])
chunks = splitter.split_text(document)
```

overlap은 10~20%, 각 청크에 출처·섹션 메타데이터를 붙인다.

## 3. 임베딩과 벡터 검색

ANN(HNSW) 탐색으로 코사인 유사도 상위 K를 찾는다. 인덱싱과 질의는 반드시 같은 임베딩 모델을 써야 한다.

## 4. 하이브리드 검색

임베딩은 의미에 강하고 BM25는 정확 일치에 강하다. RRF로 순위를 합산한다: RRF_score(d)=Σ 1/(k+rank_i(d)), k는 흔히 60.

## 5. Reranking — 2단계

1차(bi-encoder)는 빠르지만 정밀도가 제한된다. Cross-encoder는 질문과 후보를 함께 넣어 관련성을 직접 산출한다. 1차로 20~50개를 추린 뒤 재순위해 상위 3~5개를 고른다.

```python
from sentence_transformers import CrossEncoder
reranker = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")
scores = reranker.predict([(question, c.text) for c in candidates])
top = [c for _, c in sorted(zip(scores, candidates), key=lambda x: x[0], reverse=True)][:5]
```

## 6. 생성·평가

"주어진 컨텍스트만 근거로 답하라" 지시와 함께 주입. lost in the middle 경향으로 관련 높은 청크를 앞·뒤에 배치.

| 단계 | 실패 | 대응 |
| --- | --- | --- |
| 청킹 | 맥락 절단 | 재귀/시맨틱+overlap |
| 임베딩 | 도메인 약함 | 도메인 모델 |
| 검색 | 키워드 누락 | 하이브리드+RRF |
| 재순위 | 정답 상위 밖 | cross-encoder |
| 생성 | 환각 | 근거 한정 |

## 7. 운영 체크리스트

청킹 파라미터 A/B 평가, 하이브리드+rerank 기본, Recall@K·faithfulness 정기 측정.

## 8. 쿼리 변환

HyDE는 가상 정답 문서를 생성·임베딩해 검색, Multi-query는 재작성 후 결과 합침. 대화형은 독립형 질문 재작성이 필수.

## 9. 인덱싱 고도화

Parent-document(small-to-big): 검색은 작은 자식 청크, 생성은 큰 부모 문서. 검색 표현과 생성 컨텍스트를 분리해 최적화.

## 10. 비용·캐싱

의미적 캐시로 생성 절감, reranker 후보 수 조절. RAG는 검색 실패 시 생성도 실패하므로 모니터링 초점을 검색 재현율에 먼저 둔다.

## 참고

- "Lost in the Middle: How Language Models Use Long Contexts"(Liu et al.)
- Reciprocal Rank Fusion(Cormack et al.)
- HNSW(Malkov & Yashunin)
- RAGAS 문서, Sentence-Transformers Cross-Encoder 가이드
