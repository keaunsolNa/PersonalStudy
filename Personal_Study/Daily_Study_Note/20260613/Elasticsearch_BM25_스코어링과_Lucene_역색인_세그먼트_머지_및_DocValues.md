Notion 원본: https://www.notion.so/37e5a06fd6d381438cc3f5c3aadc62b6

# Elasticsearch BM25 스코어링과 Lucene 역색인 · 세그먼트 머지 및 DocValues

> 2026-06-13 신규 주제 · 확장 대상: DB

## 학습 목표

- BM25 점수 공식의 각 항(TF 포화, IDF, 문서 길이 정규화)이 관련도에 미치는 영향을 설명한다
- Lucene 역색인의 구성요소(term dictionary, postings, positions)와 검색 경로를 추적한다
- 세그먼트 불변성·refresh·flush·merge 라이프사이클이 검색 가시성과 성능에 주는 영향을 구분한다
- DocValues와 역색인의 용도 차이를 이해해 정렬·집계·검색을 올바른 자료구조로 처리한다

## 1. 역색인의 구조

Elasticsearch의 검색은 Lucene 역색인 위에서 이뤄진다. 역색인은 "term → 그 term을 포함한 문서 목록(postings)"의 매핑이다. 분석기가 텍스트를 토큰으로 쪼개고 정규화(소문자화, 어간 추출 등)한 뒤, 각 term이 어떤 문서의 어느 위치에 나타나는지를 기록한다. 구성요소는 크게 term dictionary(정렬된 term 사전, FST로 압축), postings list(문서 ID들, delta + 비트패킹 압축), 그리고 phrase·근접 질의를 위한 positions/offsets다.

질의 `"spring boot"`를 처리하면, 두 term의 postings를 각각 찾아 교집합(AND) 또는 합집합(OR)을 구하고, 매칭된 문서마다 점수를 계산해 상위 k개를 힙으로 추린다. term dictionary가 정렬·압축돼 있어 term 조회는 디스크 시크를 최소화하며 O(term 길이)에 가깝다.

## 2. BM25 점수 공식

Elasticsearch의 기본 유사도는 BM25다. 한 term `q`가 문서 `d`에 기여하는 점수는 대략 다음과 같다.

```
score(d, q) = IDF(q) * ( tf * (k1 + 1) ) / ( tf + k1 * (1 - b + b * |d| / avgdl) )

IDF(q) = ln( 1 + (N - n + 0.5) / (n + 0.5) )
```

여기서 `tf`는 문서 내 term 빈도, `N`은 전체 문서 수, `n`은 term을 포함한 문서 수, `|d|`는 문서 길이, `avgdl`은 평균 문서 길이다. 파라미터 `k1`(기본 1.2)은 TF 포화 정도를, `b`(기본 0.75)는 길이 정규화 강도를 조절한다.

## 3. 각 항의 직관

IDF는 희귀한 term일수록 높은 가중치를 준다. "the" 같은 흔한 term은 거의 모든 문서에 있어 `n`이 커지고 IDF가 0에 수렴해 변별력을 잃는다. TF 항은 **포화**가 핵심이다 — TF-IDF가 빈도에 선형 비례하던 것과 달리, BM25는 `k1`로 빈도 증가의 한계 효용을 체감시킨다. term이 5번 나온 문서와 50번 나온 문서의 점수 차가 비례하지 않고 완만해져, 키워드 스터핑에 강하다. 길이 정규화 `b`는 긴 문서가 단지 길어서 더 많은 term을 포함하는 효과를 상쇄한다. `b=0`이면 길이를 무시하고, `b=1`이면 완전 정규화한다. 로그·제품 설명처럼 길이가 들쑗날롱한 필드는 `b`를 낮춰 단문이 불리해지지 않게 조정할 수 있다.

```json
PUT my-index
{
  "settings": {
    "index": {
      "similarity": {
        "tuned_bm25": { "type": "BM25", "k1": 1.0, "b": 0.4 }
      }
    }
  },
  "mappings": {
    "properties": { "body": { "type": "text", "similarity": "tuned_bm25" } }
  }
}
```

## 4. 세그먼트와 불변성

Lucene 인덱스는 다수의 **세그먼트**로 구성되며, 각 세그먼트는 한 번 쓰이면 변경되지 않는(immutable) 미니 역색인이다. 새 문서는 인메모리 버퍼에 모였다가 새 세그먼트로 쓰인다. 불변성 덕분에 락 없이 동시 읽기가 가능하고 OS 페이지 캐시 친화적이지만, 문서 삭제/수정은 즉시 반영되지 않는다. 삭제는 `.liv`(live docs) 비트셋에 "삭제됨" 표시만 하고, 실제 제거는 머지 시점에 일어난다. 업데이트는 "삭제 + 재색인"으로 구현된다.

## 5. refresh · flush · merge 라이프사이클

세 단계를 구분해야 한다. **refresh**는 인메모리 버퍼를 검색 가능한 세그먼트로 만들어 새 IndexReader를 연다. 기본 1초 주기이며, 이 때문에 색인 직후 문서가 즉시 검색되지 않는 "near real-time" 특성이 생긴다. **flush**는 translog(내구성용 쓰기 로그)를 Lucene commit으로 디스크에 영구화하고 translog를 비운다. **merge**는 작은 세그먼트들을 큰 세그먼트로 합치며, 이때 삭제 표시된 문서가 물리적으로 제거된다.

```
PUT /my-index/_settings
{ "index.refresh_interval": "30s" }   // 대량 색인 중 refresh 부담 완화
```

벌크 색인 시 `refresh_interval`을 늘리거나 `-1`로 끄면 세그먼트 생성 빈도가 줄어 처리량이 크게 오른다. 색인 완료 후 다시 켜고 필요하면 `_forcemerge`로 세그먼트 수를 줄인다.

## 6. 세그먼트 수와 검색 성능

검색은 모든 세그먼트를 순회하며 점수를 합치므로, 세그먼트가 많을수록 오버헤드가 커진다. 세그먼트가 수백 개면 쿼리 지연이 눈에 띄게 늘고, 적을수록 빠르지만 머지 비용(쓰기 증폭, I/O)이 든다. `_forcemerge?max_num_segments=1`은 읽기 전용이 된 시계열 인덱스(예: 어제 자 로그)에 적합하다 — 한 번 강제 머지해 두면 이후 검색이 최적화된다. 단, 활발히 쓰이는 인덱스에 force merge를 돌리면 거대 세그먼트가 생겨 이후 자동 머지를 방해하므로 피해야 한다.

## 7. DocValues — 정렬·집계의 자료구조

역색인은 "term으로 문서를 찾기"에 최적이지만, "문서의 필드 값을 빠르게 읽기"(정렬·집계·스크립트)에는 부적합하다. 이를 위해 Lucene은 **DocValues**라는 컴럼형 저장소를 색인 시점에 함께 만든다. 문서 ID 순으로 필드 값을 연속 저장해, 집계 시 컴럼 스캔처럼 빠르게 읽는다.

```json
{
  "properties": {
    "status": { "type": "keyword" },                 // doc_values 기본 on
    "log_message": { "type": "text", "doc_values": false } // 정렬/집계 안 하면 off
  }
}
```

`text` 필드는 분석되어 DocValues가 없으므로 정렬/집계가 불가능하다(그래서 보통 `keyword` 서브필드를 둔다). 반대로 집계할 일이 없는 큰 `keyword` 필드는 `doc_values: false`로 디스크를 아컴 수 있다. fielddata(텍스트 필드의 인메모리 집계)는 힙을 크게 먹어 기본 비활성이며, DocValues가 그 대체재다.

## 8. 분석기와 관련도 — 토큰화가 검색을 결정한다

BM25 점수 이전에, 무엇을 term으로 만드는지가 검색 결과를 좌우한다. 분석기는 character filter → tokenizer → token filter 순으로 동작한다. 색인 시 분석기와 검색 시 분석기가 일치해야 매칭이 정상화된다.

```json
PUT my-index
{
  "settings": {
    "analysis": {
      "analyzer": {
        "ko_search": {
          "type": "custom",
          "tokenizer": "nori_tokenizer",
          "filter": ["lowercase", "nori_part_of_speech", "my_synonyms"]
        }
      },
      "filter": {
        "my_synonyms": { "type": "synonym", "synonyms": ["노트북, 랩탑"] }
      }
    }
  }
}
```

동의어 필터는 "노트북"과 "랩탑"을 같은 term으로 묶어 회수율을 올린다. 한국어는 `nori` 형태소 분석기로 조사·어미를 분리해야 "검색을"·"검색이"가 모두 "검색"으로 매칭된다. n-gram/edge-ngram은 부분 일치·자동완성에 쓰지만 색인 크기를 키운다. 분석기 결과는 `_analyze` API로 즉시 확인할 수 있어, "왜 이 문서가 안 잡히는가"의 1차 진단 도구다.

```bash
POST my-index/_analyze
{ "analyzer": "ko_search", "text": "검색엔진을 학습한다" }
```

## 9. 샤드·라우팅과 점수의 함정

인덱스는 여러 프라이머리 샤드로 분산되고, BM25의 IDF는 기본적으로 **샤드 로컬** 통계로 계산된다. 즉 같은 term이라도 문서가 샤드마다 불균등하게 분포하면 동일 문서가 샤드 위치에 따라 다른 점수를 받아, 소규모 인덱스에서 관련도가 들쑗날롱해질 수 있다. `search_type=dfs_query_then_fetch`를 쓰면 샤드 간 글로벌 통계를 먼저 모아 계산해 이 편차를 줄이지만, 추가 라운드트립 비용이 든다. 대규모 인덱스에서는 문서 수가 많아 샤드별 통계가 자연히 비슷해져 기본값으로도 충분한 경우가 많다. 라우팅(`routing` 파라미터)으로 특정 문서를 같은 샤드에 모으면 검색 범위를 줄여 지연을 낮출 수 있으나, 샤드 hotspot과 점수 편향을 유발할 수 있어 트레이드오프를 따져야 한다.

## 10. 운영 trade-off 정리

| 결정 | 처리량/지연 영향 | 권장 상황 |
|---|---|---|
| `refresh_interval` ↑ | 색인 처리량 ↑, 검색 가시성 지연 ↑ | 대량 벌크 색인 |
| force merge → 1 세그먼트 | 검색 지연 ↓, 일회성 I/O 비용 | 읽기 전용 과거 인덱스 |
| `doc_values: false` | 디스크 ↓, 정렬/집계 불가 | 검색만 하는 필드 |
| BM25 `b` ↓ | 단문에 유리 | 길이 편차 큰 필드 |

핵심 원칙은 "필드의 용도(검색/정렬/집계)에 따라 자료구조를 의도적으로 선택"하는 것이다. 모든 필드에 기본값을 쓰면 디스크와 힙을 낭비하고, 반대로 무분별하게 끄면 필요한 집계가 막힌다. 관련도 품질은 BM25 파라미터 튜닝과 분석기 설계(동의어, 어간, n-gram)의 조합으로 다듬으며, A/B로 클릭률·전환을 측정해 정량 검증하는 것이 권장된다.

## 참고

- Elasticsearch — Practical BM25: https://www.elastic.co/blog/practical-bm25-part-2-the-bm25-algorithm-and-its-variables
- Lucene Index File Formats: https://lucene.apache.org/core/documentation.html
- Elasticsearch — Near real-time search & Merge: https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html
- Elasticsearch — doc_values: https://www.elastic.co/guide/en/elasticsearch/reference/current/doc-values.html
