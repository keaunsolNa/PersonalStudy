Notion 원본: https://www.notion.so/37e5a06fd6d381438cc3f5c3aadc62b6

# Elasticsearch BM25 스코어링과 Lucene 역색인 · 세그먼트 머지 및 DocValues

> 2026-06-13 신규 주제 · 확장 대상: DB

## 학습 목표

- BM25 점수 공식의 각 항(TF 포화, IDF, 문서 길이 정규화)이 관련도에 미치는 영향을 설명한다
- Lucene 역색인의 구성요소(term dictionary, postings, positions)와 검색 경로를 추적한다
- 세그먼트 불변성·refresh·flush·merge 라이프사이클이 검색 가시성과 성능에 주는 영향을 구분한다
- DocValues와 역색인의 용도 차이를 이해해 정렬·집계·검색을 올바른 자료구조로 처리한다

## 1. 역색인의 구조

Elasticsearch 검색은 Lucene 역색인 위에서 이뤄진다. 역색인은 term → 그 term을 포함한 문서 목록(postings)의 매핑이다. 분석기가 텍스트를 토큰으로 쪼개고 정규화한 뒤, 각 term이 어떤 문서의 어느 위치에 나타나는지를 기록한다. 구성요소는 term dictionary(FST로 압축), postings list(delta + 비트패킹 압축), positions/offsets다.

질의 `"spring boot"`를 처리하면 두 term의 postings를 각각 찾아 교집합/합집합을 구하고 매칭 문서마다 점수를 계산해 상위 k개를 힙으로 추린다. term dictionary가 정렬·압축돼 있어 디스크 시크를 최소화한다.

## 2. BM25 점수 공식

```
score(d, q) = IDF(q) * ( tf * (k1 + 1) ) / ( tf + k1 * (1 - b + b * |d| / avgdl) )
IDF(q) = ln( 1 + (N - n + 0.5) / (n + 0.5) )
```

`tf`는 문서 내 term 빈도, `N`은 전체 문서 수, `n`은 term을 포함한 문서 수, `|d|`는 문서 길이, `avgdl`은 평균 길이다. `k1`(기본 1.2)은 TF 포화, `b`(기본 0.75)는 길이 정규화 강도를 조절한다.

## 3. 각 항의 직관

IDF는 희귀한 term일수록 높은 가중치를 준다. "the" 같은 term은 IDF가 0에 수렴해 변별력을 잃는다. TF 항은 포화가 핵심이다 — BM25는 `k1`로 빈도 증가의 한계 효용을 체감시켜 키워드 스터핑에 강하다. 길이 정규화 `b`는 긴 문서가 단지 길어서 더 많은 term을 포함하는 효과를 상쇄한다.

```json
PUT my-index
{ "settings": { "index": { "similarity": {
  "tuned_bm25": { "type": "BM25", "k1": 1.0, "b": 0.4 } } } },
  "mappings": { "properties": { "body": { "type": "text", "similarity": "tuned_bm25" } } } }
```

## 4. 세그먼트와 불변성

Lucene 인덱스는 다수의 세그먼트로 구성되며, 각 세그먼트는 한 번 쓰이면 변경되지 않는 미니 역색인이다. 불변성 덕분에 락 없이 동시 읽기가 가능하고 OS 페이지 캐시 친화적이지만, 삭제/수정은 즉시 반영되지 않는다. 삭제는 `.liv` 비트셋에 표시만 하고 실제 제거는 머지 시점에 일어난다. 업데이트는 삭제 + 재색인이다.

## 5. refresh · flush · merge 라이프사이클

refresh는 인메모리 버퍼를 검색 가능한 세그먼트로 만든다(기본 1초, near real-time 원인). flush는 translog를 Lucene commit으로 영구화한다. merge는 작은 세그먼트를 합치며 삭제 문서를 물리적으로 제거한다.

```
PUT /my-index/_settings
{ "index.refresh_interval": "30s" }
```

벌크 색인 시 `refresh_interval`을 늘리거나 -1로 끄면 처리량이 크게 오른다. 완료 후 다시 켜고 `_forcemerge`로 세그먼트 수를 줄인다.

## 6. 세그먼트 수와 검색 성능

검색은 모든 세그먼트를 순회하므로 세그먼트가 많을수록 오버헤드가 커진다. `_forcemerge?max_num_segments=1`은 읽기 전용 시계열 인덱스에 적합하다. 단, 활발히 쓰이는 인덱스에 force merge를 돌리면 거대 세그먼트가 생겨 이후 자동 머지를 방해하므로 피해야 한다.

## 7. DocValues — 정렬·집계의 자료구조

역색인은 term으로 문서 찾기에 최적이지만 문서의 필드 값을 빠르게 읽기(정렬·집계)에는 부적합하다. Lucene은 DocValues라는 컴럼형 저장소를 색인 시점에 함께 만든다.

```json
{ "properties": {
  "status": { "type": "keyword" },
  "log_message": { "type": "text", "doc_values": false } } }
```

`text` 필드는 DocValues가 없어 정렬/집계가 불가해 보통 keyword 서브필드를 둔다. 집계할 일 없는 큰 keyword는 `doc_values: false`로 디스크를 아낀다.

## 8. 분석기와 관련도 — 토큰화가 검색을 결정한다

```json
PUT my-index
{ "settings": { "analysis": { "analyzer": {
  "ko_search": { "type": "custom", "tokenizer": "nori_tokenizer",
    "filter": ["lowercase", "nori_part_of_speech", "my_synonyms"] } },
  "filter": { "my_synonyms": { "type": "synonym", "synonyms": ["노트북, 랩탑"] } } } } }
```

동의어 필터는 회수율을 올리고, 한국어는 `nori`로 조사·어미를 분리해야 "검색을"·"검색이"가 모두 "검색"으로 매칭된다. 분석기 결과는 `_analyze` API로 즉시 확인한다.

## 9. 샤드·라우팅과 점수의 함정

BM25의 IDF는 기본적으로 샤드 로컬 통계로 계산된다. 즉 문서가 샤드마다 불균등하면 동일 문서가 샤드 위치에 따라 다른 점수를 받아 소규모 인덱스에서 관련도가 들쎄날 수 있다. `search_type=dfs_query_then_fetch`는 글로벌 통계를 먼저 모아 편차를 줄이지만 추가 라운드트립 비용이 든다. 대규모 인덱스에서는 기본값으로도 충분한 경우가 많다.

## 10. 운영 trade-off 정리

| 결정 | 처리량/지연 영향 | 권장 상황 |
|---|---|---|
| `refresh_interval` ↑ | 색인 처리량 ↑, 검색 가시성 지연 ↑ | 대량 벌크 색인 |
| force merge → 1 세그먼트 | 검색 지연 ↓, 일회성 I/O 비용 | 읽기 전용 과거 인덱스 |
| `doc_values: false` | 디스크 ↓, 정렬/집계 불가 | 검색만 하는 필드 |
| BM25 `b` ↓ | 단문에 유리 | 길이 편차 큰 필드 |

핵심 원칙은 필드의 용도(검색/정렬/집계)에 따라 자료구조를 의도적으로 선택하는 것이다. 관련도 품질은 BM25 튜닝과 분석기 설계(동의어, 어간, n-gram)의 조합으로 다듬으며, A/B로 클릭률·전환을 측정해 정량 검증한다.

## 참고

- Elasticsearch — Practical BM25: https://www.elastic.co/blog/practical-bm25-part-2-the-bm25-algorithm-and-its-variables
- Lucene Index File Formats: https://lucene.apache.org/core/documentation.html
- Near real-time search & Merge: https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html
- doc_values: https://www.elastic.co/guide/en/elasticsearch/reference/current/doc-values.html
