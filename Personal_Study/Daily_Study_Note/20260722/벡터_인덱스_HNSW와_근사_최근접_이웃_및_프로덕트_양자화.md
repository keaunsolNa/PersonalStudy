Notion 원본: https://app.notion.com/p/3a55a06fd6d3813a8d38de6592bffc57

# 벡터 인덱스 HNSW와 근사 최근접 이웃 및 프로덕트 양자화

> 2026-07-22 신규 주제 · 확장 대상: AI/Elasticsearch(벡터 검색·인덱싱)

## 학습 목표

- 고차원 벡터의 완전 탐색 비용과 근사 최근접 이웃(ANN)이 필요한 이유를 파악한다.
- HNSW의 계층적 스킵 리스트 구조와 탐색·삽입 알고리즘을 분석한다.
- ef_construction·M·ef_search 파라미터가 재현율·지연·메모리에 미치는 영향을 정량적으로 이해한다.
- 프로덕트 양자화(PQ)로 메모리를 압축하는 원리와 HNSW와의 결합 방식을 설명한다.

## 1. 문제 정의 — 고차원 최근접 이웃의 저주

임베딩 기반 검색(RAG, 추천, 이미지 유사도)은 쿼리 벡터와 가장 가까운 K개의 벡터를 찾는 문제다. 정확한 방법은 모든 벡터와의 거리를 계산하는 완전 탐색(brute-force)인데, N개 벡터·D차원에서 O(N·D)이다. 벡터가 1억 개, 차원이 768이면 쿼리 한 번에 수백억 번의 곱셈이 필요해 실시간 서비스에 부적합하다.

전통적 공간 분할 인덱스(KD-트리, R-트리)는 저차원에서는 효과적이지만 고차원에서 "차원의 저주"에 걸린다. 차원이 높아지면 모든 점이 서로 비슷한 거리에 놓여 분할의 가지치기 효과가 사라지고, 결국 완전 탐색과 비슷해진다. 그래서 고차원에서는 정확도를 약간 포기하고 속도를 얻는 **근사 최근접 이웃(ANN, Approximate Nearest Neighbor)**이 표준이다. ANN은 "진짜 최근접 K개 중 대부분을 빠르게 찾는다"를 목표로 하며, 이 "대부분"의 비율이 재현율(recall)이다.

## 2. HNSW의 핵심 아이디어 — 계층적 그래프

HNSW(Hierarchical Navigable Small World)는 현재 가장 널리 쓰이는 ANN 인덱스다. 두 아이디어를 결합한다. 첫째, **NSW(Navigable Small World)** — 각 벡터를 노드로, 가까운 벡터끼리 간선으로 연결한 그래프에서 탐욕적 탐색(greedy search)으로 최근접에 접근한다. 둘째, **계층화** — 스킵 리스트처럼 여러 층을 두어 상위 층은 성긴 그래프(먼 거리를 빠르게 건너뜀), 하위 층은 촘촘한 그래프(정밀 탐색)로 구성한다.

```
Layer 2 (성김):   A --------- F --------- K     ← 먼 거리 빠른 이동
                  |           |           |
Layer 1 (중간):   A --- C --- F --- H --- K
                  |     |     |     |     |
Layer 0 (촘촘):   A-B-C-D-E-F-G-H-I-J-K   ← 모든 노드, 정밀 탐색
```

탐색은 최상위 층의 진입점에서 시작해 탐욕적으로 쿼리에 가까운 이웃으로 이동하다가, 더 가까운 이웃이 없으면 한 층 내려가 반복한다. 최하위 층(모든 노드 포함)에서 최종 후보를 수집한다. 상위 층이 "고속도로"처럼 먼 거리를 몇 홉에 건너뛰게 해 전체 탐색을 O(log N)에 가깝게 만든다. 이 로그 스케일 탐색이 HNSW가 수억 벡터에서도 밀리초 단위 응답을 내는 비결이다.

## 3. 탐색 알고리즘 — greedy + ef 후보 큐

HNSW 탐색의 정밀도는 최하위 층에서 유지하는 **동적 후보 리스트 크기 ef**로 조절된다. 탐욕적 탐색은 지역 최솟값에 빠질 수 있으므로, 단일 최선 노드만 추적하는 대신 ef개의 후보를 우선순위 큐로 유지하며 탐색을 넓힌다.

```
search_layer(query, entry_points, ef):
  visited = set(entry_points)
  candidates = min-heap(entry_points)   # 쿼리와 가까운 순
  results   = max-heap(entry_points)    # 결과 후보 (ef개 유지)
  while candidates not empty:
    c = candidates.pop_nearest()
    if dist(c, query) > results.farthest(): break   # 더 나아질 여지 없음
    for neighbor in c.neighbors():
      if neighbor not in visited:
        visited.add(neighbor)
        if dist(neighbor, query) < results.farthest() or len(results) < ef:
          candidates.push(neighbor)
          results.push(neighbor)
          if len(results) > ef: results.pop_farthest()
  return results.top_k()
```

ef가 크면 더 많은 경로를 탐색해 재현율이 오르지만 지연이 늘고, 작으면 빠르지만 진짜 최근접을 놓칠 수 있다. 탐색 시 ef(`ef_search`)는 쿼리마다 조절 가능하므로, 같은 인덱스에서 "빠른 대략 검색"과 "느린 정밀 검색"을 상황에 따라 선택할 수 있다. 이것이 HNSW의 실무적 유연성이다.

## 4. 삽입과 파라미터 M·ef_construction

노드를 삽입할 때는 먼저 확률적으로 그 노드가 도달할 최상위 층을 정한다(지수 분포로, 대부분 층 0에만, 소수만 상위 층에). 그 후 각 층에서 `ef_construction` 크기로 탐색해 가장 가까운 이웃을 찾고, 그중 최대 **M개**와 양방향 간선을 맺는다. M은 노드당 이웃 수 상한으로, 그래프의 연결 밀도를 결정한다.

```
M               = 16    # 노드당 이웃 상한 (층 0 은 보통 2*M)
ef_construction = 200   # 삽입 시 탐색 후보 크기 (그래프 품질)
ef_search       = 100   # 쿼리 시 탐색 후보 크기 (재현율/지연)
```

`ef_construction`이 크면 삽입 시 더 좋은 이웃을 찾아 그래프 품질이 높아지고 나중 검색 재현율이 오르지만, 인덱싱 시간이 늘어난다. M이 크면 각 노드의 연결이 촘촘해져 재현율이 오르지만 메모리(간선 저장)와 탐색 비용이 늕다. 실무 기본값은 M=16, ef_construction=200 근처에서 시작해, 재현율이 부족하면 ef_search를 먼저 올리고(런타임 조절 가능, 재인덱싱 불필요), 그래도 부족하면 M·ef_construction을 올려 재인덱싱한다.

| 파라미터 | 크게 하면 | 조절 시점 | 비용 |
|---|---|---|---|
| M | 재현율↑ | 인덱스 생성 | 메모리↑, 탐색↑ |
| ef_construction | 그래프 품질↑ | 인덱스 생성 | 인덱싱 시간↑ |
| ef_search | 재현율↑ | 쿼리 런타임 | 쿼리 지연↑ |

## 5. HNSW의 비용 — 메모리와 갱신

HNSW의 최대 약점은 **메모리**다. 원본 벡터를 메모리에 두고(768차원 float32면 벡터당 약 3KB), 여기에 노드당 M개의 간선(정수 ID) 그래프까지 엹는다. 1억 벡터면 원본만 300GB에 이르러 단일 노드 RAM을 넘긴다. 그래서 대규모에서는 다음 절의 양자화로 벡터를 압축하거나 여러 노드에 샤딩한다.

또 다른 약점은 **삭제·갱신**이다. HNSW 그래프에서 노드를 실제로 제거하면 그래프 연결성이 깨질 수 있어, 대부분의 구현은 삭제를 "tombstone(삭제 표시)"로 처리하고 검색 시 걸러낸다. 삭제가 누적되면 그래프에 죽은 노드가 늘어 효율이 떨어지므로 주기적 재구축이 필요하다. 이 때문에 갱신이 매우 잦은 워크로드에는 IVF 계열이나 재구축 친화적 구조가 더 나을 수 있다. Elasticsearch·OpenSearch의 kNN 필드가 HNSW를 쓰며, 세그먼트 병합 시 HNSW 그래프를 재구성하는 비용이 있는 것도 같은 맥락이다.

## 6. 프로덕트 양자화 — 벡터 압축

프로덕트 양자화(PQ, Product Quantization)는 벡터를 대폭 압축해 메모리를 줄이는 기법이다. 아이디어는 D차원 벡터를 m개의 서브벡터로 쪼개고, 각 서브공간마다 k-means로 학습한 코드북(대표 중심점 집합)의 가장 가까운 중심점 인덱스로 치환하는 것이다.

```
768차원 벡터를 m=96 개 서브벡터(각 8차원)로 분할
각 서브공간: k=256 개 중심점(코드북) 학습 (k-means)
각 서브벡터 → 가장 가까운 중심점 인덱스(1바이트, 0~255)

원본:  768 * 4바이트 = 3072 바이트
PQ:    96  * 1바이트 = 96 바이트   → 약 32배 압축
```

거리 계산은 코드북과 쿼리 서브벡터 간 거리를 미리 계산한 **거리 테이블(ADC, Asymmetric Distance Computation)**을 조회하는 방식으로 근사한다. 쿼리 벡터의 각 서브공간에 대해 256개 중심점과의 거리를 한 번 계산해 테이블에 넣어두면, 이후 각 데이터베이스 벡터의 거리는 96번의 테이블 조회 합산으로 구해진다. 곱셈 대신 조회로 바꿔 속도와 메모리를 동시에 얻는다. 대가는 정확도로, 중심점으로 근사하므로 거리에 양자화 오차가 생긴다.

## 7. HNSW + PQ 결합과 재순위

메모리와 재현율을 함께 잡기 위해 실무는 HNSW와 PQ를 결합한다. 대표적으로 그래프 탐색은 HNSW로 하되 벡터는 PQ로 압축 저장해 후보를 빠르게 좁힌 뒤, 정확도가 필요하면 원본 벡터로 **재순위(re-ranking)**한다.

```
1) PQ 압축 벡터로 HNSW 탐색 → 상위 K' (예: 200) 후보 빠르게 수집 (저메모리)
2) 상위 K' 후보만 원본(또는 고정밀) 벡터로 정확 거리 재계산
3) 재정렬 후 상위 K (예: 10) 반환                → 재현율 회복
```

이 2단계는 "많이·거칠게 좁히고, 적게·정밀하게 다듬는" 전략이다. PQ로 인덱스 전체를 RAM에 올릴 수 있어 확장성을 얻고, 재순위로 PQ 양자화 오차를 만회해 재현율을 회복한다. 재순위 후보 수 K'가 재현율과 지연의 조절 손잡이다. FAISS의 `IndexHNSWPQ`, `IVFPQ` 같은 조합 인덱스가 이 원리를 구현하며, 벡터 DB(Milvus, Weaviate, Qdrant)들도 유사한 압축+재순위 파이프라인을 제공한다.

## 8. 인덱스 선택 기준 정리

ANN 인덱스 선택은 데이터 규모·메모리 예산·갱신 빈도·재현율 요구의 함수다. 수백만 이하 벡터에 메모리 여유가 있으면 순수 HNSW(플랫 벡터)가 가장 높은 재현율과 낮은 지연을 준다. 수억 벡터로 메모리가 병목이면 HNSW+PQ나 IVF+PQ로 압축한다. 갱신이 매우 잦으면 HNSW의 tombstone 누적을 감안해 재구축 주기를 설계하거나 IVF 계열을 고려한다.

재현율 목표를 먼저 정하고(예: recall@10 ≥ 0.95), 그 목표에서 지연·메모리를 최소화하는 파라미터를 실측 벤치마크로 찾는 것이 정석이다. 파라미터를 감으로 잡지 말고, 대표 쿼리 셋으로 완전 탐색 정답과 비교해 recall을 측정하며 ef_search·M·PQ 서브벡터 수를 조율한다. "정확도-속도-메모리"는 항상 삼각 절충이며, HNSW·PQ의 여러 손잡이는 이 삼각형 위에서 원하는 지점을 고르는 도구다. 하나를 공짜로 개선하는 마법은 없고, 워크로드가 어느 꼭짓점을 더 중시하는지가 인덱스 설계의 출발점이다.

## 9. Elasticsearch kNN 실무와 필터링된 검색

Elasticsearch·OpenSearch는 `dense_vector` 필드에 HNSW를 내장한다. 매핑에서 차원·유사도·그래프 파라미터를 지정하고, 쿼리에서 `knn` 절로 근사 검색한다.

```json
PUT /products
{ "mappings": { "properties": { "embedding": {
    "type": "dense_vector", "dims": 768, "index": true,
    "similarity": "cosine",
    "index_options": { "type": "hnsw", "m": 16, "ef_construction": 200 }
}}}}

POST /products/_search
{ "knn": {
    "field": "embedding", "query_vector": [ ... ],
    "k": 10, "num_candidates": 100,          // num_candidates ≈ ef_search
    "filter": { "term": { "category": "book" } }
}}
```

`num_candidates`가 `ef_search`에 대응하며 k보다 크게 잡아야 재현율이 확보된다. 실무에서 가장 까다로운 것은 **필터링된 kNN**이다. "카테고리=book인 것 중 최근접 10개"처럼 메타데이터 필터와 벡터 검색을 결합할 때, 순진하게 HNSW로 후보를 뽑은 뒤 필터를 적용하면(post-filtering) 후보 대부분이 필터에서 탈락해 결과가 k개에 못 미칠 수 있다. 반대로 필터를 먼저 적용하고 그 부분집합에서 완전 탐색하면(pre-filtering) 필터 선택도가 낮을 때 느리다.

Elasticsearch는 이를 위해 필터를 그래프 탐색에 통합한 방식을 쓴다 — 그래프를 순회하되 필터를 통과하는 노드만 결과 후보로 수집하고, 필터 통과 노드가 부족하면 탐색 범위를 넓히거나 완전 탐색으로 폴백한다. 필터 선택도(전체 대비 통과 비율)가 높으면 그래프 탐색이 효율적이고, 매우 낮으면(예: 0.1%) 필터 먼저 적용 후 완전 탐색이 낫다. 이 임계 판단을 엔진이 비용 기반으로 자동 선택한다. 세그먼트가 여러 개면 각 세그먼트의 HNSW 그래프를 독립 탐색한 뒤 병합하므로, 세그먼트 병합(force merge)으로 그래프 수를 줄이면 검색이 빨라지지만 인덱싱·병합 비용이 늘는다. 이 병합 정책과 재현율·지연의 균형이 프로덕션 튜닝의 마지막 관문이다.

## 참고

- Malkov & Yashunin, "Efficient and robust approximate nearest neighbor search using HNSW graphs" (2016)
- Jégou et al., "Product Quantization for Nearest Neighbor Search" (IEEE TPAMI, 2011)
- FAISS 공식 문서 및 위키 — Index types, HNSW, IVFPQ
- Elasticsearch/OpenSearch kNN 검색 문서 — HNSW 파라미터 튜닝
