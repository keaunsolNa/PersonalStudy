Notion 원본: https://www.notion.so/34f5a06fd6d381559821e05c84d1bb95

# HNSW 그래프 인덱스 알고리즘과 ef/M 파라미터 튜닝

> 2026-04-27 신규 주제 · 확장 대상: AI Multi-Agent / Elasticsearch / 자료구조&알고리즘

## 학습 목표

- HNSW(Hierarchical Navigable Small World) 의 멀티 레이어 그래프 구조와 검색 절차를 단계별로 이해한다
- 핵심 파라미터 M, efConstruction, efSearch 가 검색 품질·메모리·레이턴시에 미치는 영향을 정리한다
- pgvector / Elasticsearch / FAISS 의 HNSW 구현 차이를 비교하고 같은 데이터셋에서의 trade-off 를 본다
- 대규모 벡터 데이터(천만 ~ 억 단위)에서 인덱스 빌드와 검색을 운영하는 노하우를 정리한다

## 1. ANN 검색이 풀려는 문제

벡터 검색은 d-차원 벡터들 사이의 거리(Euclidean / cosine / inner product)로 유사도를 계산한다. 정확한 nearest neighbor 검색은 d 가 크고 N 이 클 때 O(N) 의 brute force 외에 유의미한 가속이 없다(curse of dimensionality). 1억 개의 1024-d 벡터를 brute force 로 query 하면 한 query 당 100MB 이상을 읽으면서 dot product 1억 번 — GPU 없으면 초 단위 latency.

ANN(Approximate Nearest Neighbor)은 정확도를 약간 포기하고 query 시간을 sub-linear 로 만든다. 대표 알고리즘 카테고리:

- **트리 기반**: KD-Tree, Annoy(트리 forest)
- **해시 기반**: LSH(Locality-Sensitive Hashing)
- **양자화**: PQ(Product Quantization), SQ
- **그래프 기반**: HNSW, NSG, DiskANN

그래프 기반은 현재 일반적인 워크로드에서 가장 좋은 recall/latency trade-off 를 보인다. HNSW 는 그 중 가장 널리 쓰이는 구현이다.

## 2. HNSW 의 핵심 아이디어

HNSW 는 두 개념의 결합이다.

첫째, **Navigable Small World**. 작은 세상 네트워크는 대부분의 노드가 짧은 경로(O(log N))로 연결된 그래프다. 인터넷 라우팅, 사회적 관계 네트워크 같은 실제 그래프가 이 성질을 보인다. NSW 그래프에서 임의의 시작 노드에서 greedy search(가장 가까운 이웃으로만 이동)를 수행하면 빠르게 query 의 근방으로 도달한다.

둘째, **계층 구조**. NSW 만으로는 시작 노드가 query 와 멀리 있을 때 greedy 가 느리게 수렴한다. HNSW 는 SkipList 처럼 여러 레이어를 만들어 위에서 멀리 점프, 아래에서 정밀하게 좁힌다.

레이어는 다음과 같이 구성된다:

- L0(최하단): 모든 N 개의 노드가 들어 있는 dense 그래프. 각 노드는 M 개 정도의 이웃을 가진다
- L1: 노드의 일부만 들어 있는 sparse 그래프. 각 노드는 M 개 정도의 이웃
- L2, L3, ...: 점점 적어짐
- L_max: 보통 1~3 노드만 존재

각 노드의 최대 레이어는 확률적으로 결정된다. 노드 v 의 최대 레이어 = ⌊-ln(uniform_random) × mL⌋, 여기서 mL = 1/ln(M).

검색은 다음과 같이 동작한다.

```
search(query, k, efSearch):
    enter = entry_point of L_max
    for layer = L_max down to L1:
        enter = greedy_search_layer(query, enter, ef=1, layer)  # 가장 가까운 1개만
    candidates = search_layer(query, enter, ef=efSearch, layer=L0)
    return top-k from candidates
```

상위 레이어에서는 큰 점프로 query 근방으로 이동, L0 에서는 efSearch 만큼의 후보를 유지하며 정밀 탐색한다. greedy 의 정밀도는 efSearch 가 클수록 높다.

## 3. 인덱스 빌드 과정

새 노드 v 를 삽입할 때:

1. v 의 최대 레이어 L_v 를 확률적으로 선택
2. L_max 부터 L_v + 1 까지: greedy 로 query=v 에 가장 가까운 노드를 찾아 enter 갱신
3. L_v 부터 L0 까지:
   - search_layer(v, enter, ef=efConstruction, layer) 로 efConstruction 개의 후보 수집
   - 후보 중 M 개를 v 의 이웃으로 선택(이웃 선택 휴리스틱 적용)
   - v 와 선택된 이웃 사이를 양방향 edge 로 연결
   - 이웃의 이웃 수가 M_max 를 초과하면 가지치기

**이웃 선택 휴리스틱**(Malkov & Yashunin 2018)은 단순히 가까운 M개를 고르지 않는다. 이미 선택된 이웃과 후보 사이의 거리가 후보-쿼리 거리보다 가까우면 그 후보는 redundant 로 보고 제외한다. 이는 그래프의 다양성을 보장해 검색 시 dead end 를 줄인다.

```python
def select_neighbors_heuristic(query, candidates, M):
    selected = []
    for c in sorted(candidates, key=lambda x: dist(query, x)):
        if len(selected) >= M:
            break
        if all(dist(c, s) > dist(query, c) for s in selected):
            selected.append(c)
    return selected
```

efConstruction 이 클수록 빌드 시 후보 풀이 커서 더 좋은 이웃을 선택할 수 있다. 빌드 시간은 efConstruction 에 비례.

## 4. 핵심 파라미터 3개

HNSW 운영에서 다루는 파라미터는 본질적으로 3개다.

**M (max number of bidirectional links per node, L1+ 레이어)**

- 의미: 각 노드의 평균 이웃 수
- 일반 범위: 8 ~ 64
- 클수록: recall 증가, 메모리 증가, 빌드 시간 증가
- 메모리 영향: 노드당 약 (M × 8 bytes for neighbor IDs) + (vector 자체) — 즉 1024-d float32 vector 가 4KB 일 때 M=16 이면 인덱스 오버헤드는 128B 정도, M=64 이면 512B
- 권장: 일반적으로 M=16 또는 32 가 좋은 시작점. d 가 크고 cluster 구조가 복잡하면 32~48

**efConstruction (build 시 dynamic list 크기)**

- 의미: 빌드 중 search_layer 의 후보 풀 크기
- 일반 범위: 64 ~ 512
- 클수록: 인덱스 품질 증가, 빌드 시간 선형 증가, 검색 시간 영향 없음
- 권장: 100 ~ 200 이 일반적 sweet spot. 빌드를 한 번만 하고 오래 검색에 쓰는 환경이면 400 까지 올려도 좋다

**efSearch (query 시 dynamic list 크기)**

- 의미: 검색 시 L0 의 후보 풀 크기
- 일반 범위: 10 ~ 500
- 클수록: recall 증가, 검색 latency 선형 증가
- 권장: query 마다 변경 가능하므로 latency budget 에 맞춰 동적으로 조정. recall@10 = 0.95 정도면 efSearch ≈ 100 으로 시작

이 3개는 직교적이다. M 을 키우면 같은 efSearch 로 더 높은 recall 을 얻거나, 같은 recall 을 더 작은 efSearch 로 달성할 수 있다.

## 5. 구현체별 차이 — pgvector / ES / FAISS

같은 HNSW 라도 구현체마다 디테일이 다르다.

**pgvector 0.5+**

PostgreSQL 의 extension. INSERT/UPDATE/DELETE 가 transactional 로 동작한다. 인덱스가 메모리에 fit 되어야 검색 성능이 나오며, shared_buffers 가 인덱스 크기 이상이어야 한다.

```sql
CREATE INDEX ON documents
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 200);

-- query 시 efSearch 동적 조정
SET hnsw.ef_search = 100;
SELECT id, content
FROM documents
ORDER BY embedding <=> '[0.1, 0.2, ...]'::vector
LIMIT 10;
```

특징: WAL 통합으로 crash safety, 1M~10M scale 까지 단일 노드에서 잘 동작. 그 이상은 partitioning + vector index per partition 패턴이 일반적.

**Elasticsearch 8.x**

knn_vector field 는 Lucene 의 HNSW 구현(Java)을 쓴다. segment 단위로 인덱스가 만들어지므로 segment merge 에서 인덱스 재구축 비용이 발생한다. 검색은 모든 segment 에 대해 병렬 후 merge.

```json
PUT documents
{
  "mappings": {
    "properties": {
      "embedding": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "hnsw",
          "m": 16,
          "ef_construction": 200
        }
      }
    }
  }
}
```

검색:

```json
POST documents/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.1, 0.2, ...],
    "k": 10,
    "num_candidates": 100
  }
}
```

`num_candidates` 가 efSearch 와 유사한 역할을 한다. 단 segment 별로 num_candidates 를 모아서 최종 k 를 뽑으므로 효과적으로는 num_candidates × segment 수.

**FAISS**

Facebook 의 라이브러리. C++ + Python binding. 인메모리 인덱스로 가장 빠르지만 transaction 이나 incremental update 는 약하다. 일반적으로 batch 로 인덱스를 빌드하고 read-only 로 serving.

```python
import faiss
import numpy as np

d = 1024
M = 16
efConstruction = 200

index = faiss.IndexHNSWFlat(d, M)
index.hnsw.efConstruction = efConstruction

vectors = np.random.random((1_000_000, d)).astype('float32')
index.add(vectors)

# query
index.hnsw.efSearch = 100
D, I = index.search(query_vectors, k=10)
```

FAISS 는 PQ 와 결합해 메모리를 줄이는 IndexHNSWPQ 도 제공한다. 1024-d float32 → PQ8 (각 차원 8-bit subquantizer) 로 32배 압축. 약간의 recall 손실 대신 메모리 8배 절감.

## 6. 메모리와 디스크 trade-off

HNSW 는 본질적으로 인메모리 알고리즘이다. 인덱스가 메모리에 fit 되지 않으면 random access pattern 이 디스크 I/O 를 폭발시킨다. 1억 개 1024-d float32 인덱스의 raw vector 만 400GB. M=16 이면 그래프 메타데이터 약 16GB 추가.

디스크 기반 옵션:

**DiskANN**(Microsoft): SSD 친화적인 access pattern 으로 설계. compressed in-memory + full vector on disk. 하루 동안 1억 query 처리 가능한 production-ready 시스템.

**FAISS IndexIVFPQ**: 디스크 친화적. 단 query 당 SSD seek 이 여러 번 발생.

**pgvector + 디스크**: PostgreSQL buffer cache 가 자주 접근되는 페이지를 메모리에 유지. 인덱스 크기가 buffer cache 보다 크면 latency 가 크게 늘어난다.

운영 결정의 기준:

- 100만 이하: 어떤 도구든 차이 작음
- 1000만: 단일 노드 + 메모리 fit 가능. pgvector / ES 적합
- 1억 이상: 메모리 비용이 부담. DiskANN / IVF + PQ 또는 분산 인덱스 고려
- 10억 이상: 분산 vector DB(Milvus, Vespa) 또는 sharded FAISS

## 7. recall / latency 를 측정하는 방법

운영 환경에 인덱스를 적용하기 전 ground truth 와 비교한 recall 측정이 필수다.

```python
import numpy as np

def measure_recall_and_latency(index, queries, ground_truth, k=10, ef_values=[50, 100, 200, 400]):
    results = {}
    for ef in ef_values:
        index.hnsw.efSearch = ef

        start = time.perf_counter_ns()
        _, I = index.search(queries, k)
        elapsed_ms = (time.perf_counter_ns() - start) / 1e6 / len(queries)

        recalls = []
        for pred, truth in zip(I, ground_truth):
            recalls.append(len(set(pred) & set(truth[:k])) / k)

        results[ef] = {
            'recall@k': float(np.mean(recalls)),
            'avg_latency_ms': elapsed_ms
        }
    return results
```

ground truth 는 brute force(IndexFlatL2)로 계산한 정답이다. 1만 개의 query 만 ground truth 를 만들어도 recall 측정에 충분하다.

전형적인 결과 패턴(M=16, 1M vector, 1024-d):

| efSearch | recall@10 | avg latency (single thread) |
| --- | --- | --- |
| 20 | 0.78 | 0.25 ms |
| 50 | 0.91 | 0.45 ms |
| 100 | 0.96 | 0.85 ms |
| 200 | 0.985 | 1.6 ms |
| 400 | 0.994 | 3.0 ms |

recall 이 0.95~0.98 부근에서 latency 곡선이 가팔라진다. 비즈니스 요구의 recall 임계값에 맞춰 efSearch 를 결정한다.

## 8. 운영 시 자주 만나는 이슈

**delete 가 복잡하다**. HNSW 는 그래프이므로 노드 삭제 시 이웃의 link 를 정리하지 않으면 dead end 가 생긴다. 대부분의 구현은 "soft delete" 로 노드를 표시만 하고 query 시 결과에서 제외한다. 일정량 이상 쌓이면 인덱스 재빌드가 필요하다.

ES 의 segment merge 가 이 역할을 자동 수행한다. pgvector 는 VACUUM 으로 dead tuple 을 정리하지만 그래프 자체 재구축은 ANALYZE / REINDEX 가 필요하다.

**update 시 graph quality 저하**. 벡터를 자주 update 하는 워크로드(예: 사용자 임베딩이 행동에 따라 매일 갱신)에서는 시간이 지날수록 그래프 품질이 떨어진다. 일정 주기로 재빌드를 스케줄.

**warm-up 이 필수**. 인덱스가 디스크에 있으면 첫 query 가 매우 느리다(I/O cold). 운영 시작 전 sample query 1000~10000 개를 미리 실행해서 page cache 에 올린다.

**multi-tenant 의 분리**. 여러 사용자/테넌트의 벡터를 한 인덱스에 넣고 query 시 filter 하는 방식은 ANN 의 효율을 떨어뜨린다. ANN 은 거리 기반이므로 filter 가 sparse 하면 충분한 후보를 못 찾고 efSearch 만 키워야 한다. 테넌트별로 인덱스를 나누거나, ES 의 filtered knn 같은 native filter 지원을 쓴다.

## 9. RAG 와 결합한 운영 패턴

RAG(Retrieval-Augmented Generation)에서 HNSW 의 역할은 LLM 컨텍스트 윈도우에 넣을 가장 관련성 높은 문서를 빠르게 찾는 것이다. 일반적인 패턴은 다음과 같다.

```
1. user query → embedding model → query vector
2. vector DB(HNSW) → top-K (K=20~50) candidates
3. cross-encoder reranker → top-N (N=3~10) reranked
4. LLM 에 N 개 문서 + query 를 넣어 답변 생성
```

HNSW 의 recall@K 가 핵심이다. K=50 일 때 recall@50 = 0.99 면 진짜 정답이 거의 항상 후보에 포함되어 있다. cross-encoder 가 reranking 만 잘 하면 최종 답변 품질이 보장된다.

이 패턴에서 HNSW efSearch 는 K(=50)보다 약간 큰 값(예: 80~100)이 좋다. 같은 K 라도 efSearch 가 작으면 후보 다양성이 떨어진다.

운영 메트릭으로 다음을 본다:

- vector_search_latency_p99 (target: < 50ms)
- vector_search_recall_at_k (target: > 0.97)
- index_build_time (data update 주기에 fit)
- index_memory_usage (host memory 의 60% 이내 권장 — page cache 여유 위해)

## 참고

- Y. Malkov, D. Yashunin, "Efficient and robust approximate nearest neighbor search using Hierarchical Navigable Small World graphs": https://arxiv.org/abs/1603.09320
- pgvector 공식 문서: https://github.com/pgvector/pgvector
- Elasticsearch dense_vector reference: https://www.elastic.co/guide/en/elasticsearch/reference/current/dense-vector.html
- FAISS Wiki — HNSW: https://github.com/facebookresearch/faiss/wiki/Indexing-1G-vectors
- Microsoft DiskANN paper: https://harsha-simhadri.org/pubs/DiskANN-NeurIPS19.pdf
