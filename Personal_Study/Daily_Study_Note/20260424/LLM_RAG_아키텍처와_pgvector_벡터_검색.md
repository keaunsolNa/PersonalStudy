Notion 원본: https://www.notion.so/34c5a06fd6d381cba3acffd8c8c4b001

# LLM RAG 아키텍처와 pgvector 벡터 검색

> 2026-04-24 신규 주제 · 확장 대상: AI Multi-Agent 실전 (더 아래 계층 — 리트리버 축)

## 학습 목표

- RAG 파이프라인 4단계(청크·임베딩·검색·생성)를 구현한다
- pgvector HNSW vs IVFFlat 인덱스의 latency/recall 트레이드오프를 수치로 비교한다
- Cross-encoder reranker를 적용해 recall을 유지하면서 precision을 높인다
- Spring AI 또는 LangChain으로 RAG 체인을 조립한다

---

## 1. RAG의 기본 파이프라인

LLM 단독은 학습 데이터 외의 내부 문서를 모른다. RAG(Retrieval-Augmented Generation)는 이 한계를 우회한다. 단계:

1. **Indexing (offline)**: 내부 문서를 chunk 단위로 나누고, 각 chunk을 임베딩 모델로 벡터화해 벡터 DB에 저장.
2. **Query (online)**: 사용자 질문도 동일 임베딩 모델로 벡터화.
3. **Retrieve**: 질문 벡터와 cosine 유사도가 높은 상위 K개 chunk 검색.
4. **Generate**: 검색된 chunk를 시스템 프롬프트에 넣어 LLM이 최종 답변 작성.

핵심은 "테스트 근사 검색이 가지지 못하는 의미 기반 근사도 추론"을 벡터 공간에서 하는 것.

## 2. 임베딩 모델 선택

| 모델 | 차원 | 설치 | 특징 |
|---|---|---|---|
| OpenAI text-embedding-3-large | 3072 | API | 고품질·고비용 |
| OpenAI text-embedding-3-small | 1536 | API | 기본 선택 |
| BGE-M3 (BAAI) | 1024 | 온프레미스 | 다국어, 한국어 우수 |
| KoSimCSE-roberta | 768 | 온프레미스 | 한국어 특화 |

한국어 문서에는 BGE-M3가 수치상 대체로 우세. 단 임베딩 모델은 **color급 선택이라 나중에 바꾸면 전체 재인덱싱이 필요**하니 벤치마크에 시간을 써야 한다.

## 3. pgvector 설치와 인덱스

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
  id bigserial PRIMARY KEY,
  source_id text NOT NULL,
  chunk_no  int  NOT NULL,
  content   text NOT NULL,
  embedding vector(1024)
);

-- HNSW 인덱스 (pgvector 0.5+)
CREATE INDEX idx_docs_embedding ON documents
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 200);
```

검색 예시:

```sql
SET hnsw.ef_search = 100;
SELECT id, content, 1 - (embedding <=> $1::vector) AS score
FROM documents
ORDER BY embedding <=> $1::vector
LIMIT 20;
```

`<=>`는 cosine distance 연산자. `1 - distance`가 유사도다. `ef_search`를 높이면 정확도(recall)는 올라가고 latency는 느려진다.

## 4. HNSW vs IVFFlat

| 인덱스 | 빌드 시간 | 검색 latency | recall@10 | 메모리 |
|---|---|---|---|---|
| Seq Scan (no index) | 0 | 1400ms | 100% | - |
| IVFFlat (lists=100) | 40s | 35ms | 92% | 작음 |
| HNSW (m=16, ef_c=200) | 180s | 12ms | 98% | 큼 |

(데이터셋 100만 chunk, dim=1024, AWS r6i.xlarge, PG 16 + pgvector 0.7). HNSW는 빌드 시간이 길고 메모리 소비가 크지만 검색 성능이 얼톡하게 일관되고, IVFFlat은 기본값으로 recall이 부족하지만 probes를 늘리면 보완 가능. 운영환경에서는 **HNSW가 기본 권장**.

## 5. 청크 전략

```python
from langchain.text_splitter import RecursiveCharacterTextSplitter
splitter = RecursiveCharacterTextSplitter(
    chunk_size=800,       # 한국어 상 200–300 토큰
    chunk_overlap=150,    # 문맥 위해 overlap
    separators=["\n\n", "\n", ". ", " "]
)
chunks = splitter.split_text(document)
```

청크 크기가 너무 작으면 문맥 손실, 너무 크면 양으로 잘라진 문단이 들어가 recall이 낮아진다. 경험적으로 500–1000자 + 10–20% overlap이 기본. 문서 종류별로 다르게 쯩는 게 주과영업 영향이 크다.

## 6. Query Reformulation

사용자가 "환불 언제에 되나요?" 라고 물으면 문서에는 "환불 방침"이라는 문구가 있어야 검색이 잘된다. LLM에게 **먼저 다섯 가지 버전으로 rewriting**하게 하고 OR로 연결해 검색하는 Multi-Query 전략이 평균 recall을 +8–12% 높인다.

## 7. Reranking (Cross-encoder)

벡터 검색으로 상위 50개를 돌리고, cross-encoder로 (질문, chunk) 쌍을 직접 점수내서 상위 K를 고른다. `bge-reranker-v2-m3` 같은 모델이 표준.

```python
from sentence_transformers import CrossEncoder
reranker = CrossEncoder("BAAI/bge-reranker-v2-m3")
pairs = [(query, c.content) for c in candidates]
scores = reranker.predict(pairs)
ranked = sorted(zip(candidates, scores), key=lambda x: x[1], reverse=True)[:5]
```

성능 관점에서도 cross-encoder가 GPU로 돌아가면 50개 처리에 30–50ms이므로 전체 지연에 보통 휴하지 않다.

## 8. 평가 메트릭 (RAGAS)

| 메트릭 | 설명 |
|---|---|
| Faithfulness | 생성 답변이 접관된 context에 실제로 근거하는가 |
| Answer Relevance | 답변이 질문 의도와 일치하는가 |
| Context Precision | 검색된 context 중 전달될 가치가 있는 비율 |
| Context Recall | 정답에 필요한 근거의 얼마를 가져왔는가 |

RAGAS 또는 TruLens를 CI에 넘게정해 PR 단위로 메트릭 회귀를 막는 것이 운영 관점에서 기본.

## 9. Spring AI 통합 예시

```java
@Bean
VectorStore vectorStore(PgVectorStore.Builder b) {
    return b.dimensions(1024)
            .distanceType(PgDistanceType.COSINE_DISTANCE)
            .indexType(PgIndexType.HNSW)
            .build();
}

@Service
public class RagService {
    @Autowired ChatClient chatClient;
    @Autowired VectorStore vectorStore;

    public String answer(String question) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.query(question).withTopK(5));
        String context = docs.stream().map(Document::getContent).collect(Collectors.joining("\n---\n"));
        return chatClient.prompt()
                .system("다음 컨텍스트만 활용해 답하세요:\n" + context)
                .user(question)
                .call().content();
    }
}
```

Spring Boot 3.2+ 기반 Spring AI 1.0은 다양한 벡터 스토어(pgvector, Pinecone, Weaviate, Redis)를 앞에 나열한 미간선방식만 공통화한다.

## 참고

- Lewis et al. "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks" (NeurIPS 2020)
- pgvector 저장소: https://github.com/pgvector/pgvector
- "Building RAG Applications with LangChain" 공식 가이드
- BAAI — BGE-M3 모델: https://huggingface.co/BAAI/bge-m3
- RAGAS: https://github.com/explodinggradients/ragas
