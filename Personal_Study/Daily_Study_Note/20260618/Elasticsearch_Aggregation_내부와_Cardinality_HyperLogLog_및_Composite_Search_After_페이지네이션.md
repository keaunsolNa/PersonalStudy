Notion 원본: https://www.notion.so/3835a06fd6d381f7959ddcab2b72f37f

# Elasticsearch Aggregation 내부와 Cardinality HyperLogLog 및 Composite Search After 페이지네이션

> 2026-06-18 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- bucket·metric·pipeline aggregation 의 분산 실행(shard→coordinating) 흐름을 설명한다
- terms aggregation 의 근사 오차(`doc_count_error_upper_bound`)와 `shard_size` 보정을 다룬다
- cardinality 의 HyperLogLog++ 메모리·정확도 트레이드오프를 `precision_threshold` 로 제어한다
- composite aggregation 과 search_after 로 깊은 페이지네이션을 메모리 안전하게 구현한다

## 1. Aggregation 의 분산 실행 모델

각 shard 가 로컬 부분 집계를 수행하고 coordinating 노드가 reduce 해 최종 응답을 만든다. shard 가 전체를 못 본 채 부분 집계를 보내므로 terms 같은 상위-N 집계에서 근사 오차가 생긴다. bucket·metric·pipeline 세 부류다.

```json
GET sales/_search
{ "size": 0, "aggs": { "by_category": { "terms": { "field": "category", "size": 10 } } } }
```

## 2. terms aggregation 의 근사성과 오차 보정

각 shard 가 `shard_size` 개만 보내므로 전역 순위와 어긋난다.

```json
"by_category": { "doc_count_error_upper_bound": 42, "sum_other_doc_count": 15832 }
```

`doc_count_error_upper_bound` 가 0 이 아니면 근사다. `shard_size` 를 키우면 정확도가 오르나 비용이 증가한다.

## 3. Cardinality 와 HyperLogLog++

```json
"unique_users": { "cardinality": { "field": "user_id", "precision_threshold": 3000 } }
```

`precision_threshold`(기본 3000, 최대 40000)가 클수록 정확하나 메모리를 더 쓴다(대략 `threshold * 8 bytes`).

| 방식 | 메모리 | 정확도 | 적합 |
|---|---|---|---|
| cardinality(HLL++) | 거의 상수 | 근사 | 대시보드·UV |
| terms+count | 카디널리티 비례 | 정확(단일 shard) | 소규모 distinct |
| composite 전수 | 페이지 단위 | 정확 | 정산·배치 |

## 4. 깊은 페이지네이션과 search_after

`from`/`size` 는 깊을수록 비용 폭증, 기본 상한 10000. `search_after` 는 오프셋 비용이 없다.

```json
{ "size": 50, "sort": [ { "created_at": "desc" }, { "_id": "asc" } ],
  "search_after": [1718700000000, "order-10532"] }
```

타이브레이커(`_id`)를 넣어야 누락·중복이 없다. 일관 스냅샷이 필요하면 PIT 를 쓴다.

## 5. Composite Aggregation 으로 버킷 전수 순회

```json
"by_cat_day": { "composite": { "size": 1000,
  "sources": [ { "category": { "terms": { "field": "category" } } },
               { "day": { "date_histogram": { "field": "ts", "calendar_interval": "day" } } } ],
  "after": { "category": "books", "day": 1718668800000 } } }
```

`after_key` 를 다음 `after` 에 넣어 전체 버킷을 스트리밍한다. 상위 N 시각화는 terms, 전수 추출은 composite.

## 6. Pipeline aggregation 으로 후처리

```json
"daily": { "date_histogram": { "field": "ts", "calendar_interval": "day" },
  "aggs": { "rev": { "sum": { "field": "amount" } },
            "cum_rev": { "cumulative_sum": { "buckets_path": "rev" } } } }
```

pipeline 은 reduce 단계(coordinating 노드 부하)에서 계산된다.

## 7. nested·다단계 집계와 메모리 모델

`nested` 타입 필드는 `nested` 집계로 컨텍스트에 진입해야 배열 원소 간 상관이 유지된다. 다단계 중첩은 버킷 수가 곱셈으로 폭증해 `search.max_buckets`(기본 65536)를 넘긴다. global ordinals 는 첫 terms 집계 시 빌드되며 `eager_global_ordinals: true` 로 색인 시점에 미리 빌드해 지연을 평탄화한다.

## 8. 성능·운영 트레이드오프 정리

집계·정렬용 필드는 `keyword` 타입과 doc_values 를 쓴다. 고-cardinality terms 는 cardinality 로 대체, 깊은 페이지네이션은 search_after+PIT, 전수 추출은 composite+after 가 표준이다. 버킷 폭증 쿼리는 간격을 넓히거나 composite 페이징으로 분할해 OOM 을 예방한다.

## 참고

- Elasticsearch — Aggregations (https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations.html)
- Elasticsearch — Cardinality aggregation (https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-cardinality-aggregation.html)
- Elasticsearch — Paginate search results (https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html)
- Flajolet et al., HyperLogLog (2007)
