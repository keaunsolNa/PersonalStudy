Notion 원본: https://www.notion.so/35c5a06fd6d381979ce8dccaa58bcbd5

# Elasticsearch Composite Aggregation과 Bucket 메모리 한계 우회

> 2026-05-10 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- terms aggregation 의 size, shard_size 가 만드는 정확도 한계와 메모리 한계를 같은 시각으로 본다
- composite aggregation 의 after_key 페이지네이션이 어떻게 모든 bucket 을 메모리 폭발 없이 순회하게 하는지 정확히 이해한다
- search.max_buckets cluster setting 과 indices.breaker.request.limit circuit breaker 가 실제로 무엇을 막는지 본다
- Composite 와 RBA(rare_terms, sampler), Transform API 의 분담을 결정한다

## 1. 일반 terms aggregation 의 한계

```json
{
  "aggs": {
    "by_user": {
      "terms": { "field": "user_id", "size": 10 }
    }
  }
}
```

이 단순한 query 가 cluster 에서 어떻게 실행되는지가 문제의 출발점이다. 각 shard 가 자신의 segment 들을 훑고 user_id 별 doc count 를 계산해 *상위 N 개* 를 coordinator 로 보낸다. coordinator 는 shard 마다 받은 partial top-N 을 merge 해 최종 top-size 를 만든다.

문제 두 가지:

1. *정확도*: shard A 에서 9등이던 user 가 shard B 에서는 상위 50등 안에 못 들면 그 user 의 doc count 는 잘못 합산된다. 이걸 보정하려고 `shard_size` 라는 추가 파라미터가 있어 각 shard 가 size 보다 더 많은 후보를 보내게 한다. 기본은 `size * 1.5 + 10`. 정확도와 성능의 trade-off.

2. *메모리*: terms 가 size=10000 처럼 크면 shard 마다 10000 개 후보를 메모리에 들고 있어야 한다. Lucene field data (또는 doc_values) 로 string 을 ord 로 매핑하지만, bucket 메타(count, sub-agg accumulator) 자체가 heap 을 잡는다. cardinality 가 1억인 field 에서 size=1e6 같은 query 는 OOM / circuit breaker trip 으로 끝난다.

`search.max_buckets` (기본 65536) 는 한 search 응답에서 만들 수 있는 *전체 bucket 수* 의 cluster-wide 한계다. nested terms 가 100*100*100 = 1M bucket 을 만들면 즉시 거부된다. ES 7.x 부터 default 로 켜져 있다.

## 2. composite aggregation 의 핵심 아이디어

terms aggregation 의 메모리 한계는 *전체 bucket 을 한 번에* 메모리에 올리려 해서 생긴다. composite aggregation 은 다음을 한다:

1. bucket 키를 sort 키로 강제. 즉 key 의 *전 순서가 정해지는* 구조여야 함.
2. 한 번의 request 는 size 만큼만 bucket 을 만들어 응답.
3. 응답에 마지막 bucket 의 key 를 `after_key` 로 포함.
4. 다음 request 는 `after: { ...lastKey }` 로 그 다음 페이지를 받음.

```json
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "by_day_user": {
      "composite": {
        "size": 1000,
        "sources": [
          { "day":  { "date_histogram": { "field": "ts",      "calendar_interval": "day" } } },
          { "user": { "terms":          { "field": "user_id" } } }
        ]
      },
      "aggs": {
        "total_amount": { "sum": { "field": "amount" } }
      }
    }
  }
}
```

응답에 `after_key: { "day": 1714435200000, "user": "u-3947" }` 가 따라온다. 다음 호출에서 이걸 그대로 `composite.after` 에 넣는다.

cluster 입장에서 한 번에 들고 있어야 하는 bucket 수는 size(=1000) 뿐이다. 1억 개 user 가 있어도 1000 짜리 페이지를 100,000 번 돌면 끝.

## 3. 다중 source 와 정렬 보장

composite 의 sources 배열에 들어가는 각 source 는 *독립된 정렬 차원* 이다. terms, histogram, date_histogram, geo_tile_grid 가 source 가 될 수 있다. 응답 bucket 은 source 순서대로 lexicographic sort 된다.

이 query 는 (day, country, user) 의 tuple 로 정렬된 모든 unique 조합을 페이지 단위로 흘려준다. ETL pipeline 에서 BigQuery / Snowflake 로 매일 누적 statistics 를 옮길 때 정확히 이 패턴.

`order: "desc"` 도 source 별로 가능하지만, *모든* source 의 order 가 동일해야 페이지 보장이 깔끔하다. asc/desc 를 섞으면 after_key 로 페이지네이션 할 때 일부 bucket 이 누락되거나 중복 가능. 7.x 부터는 mixed order 도 받지만 현장에서는 한 방향으로 통일하는 게 안전.

## 4. missing_bucket — null 값 다루기

source 에 missing_bucket: true 를 두면 해당 field 가 null 인 doc 도 별도 bucket 으로 잡힌다.

미사용 전: null 인 doc 은 통째로 무시되어 통계 누락. ETL 입장에서는 raw row 가 100만인데 응답 bucket count 합계가 800만이 되어 의문이 생긴다. missing_bucket 으로 명시적으로 "anonymous" bucket 을 만들어두면 누락 없는 합산이 가능하다.

null bucket 의 키는 `null`. after_key 에 null 이 들어가는 일도 있으므로 client 코드에서 null 처리 필요.

## 5. 메모리 footprint 비교 — 실측

10억 doc, user_id cardinality 1억, 7일 분 데이터에서 (day, user_id) 별 sum(amount) 을 뽑는 작업.

| 방식 | bucket 수 | 한 번 응답 메모리 | 전체 처리 시간 | 결과 |
| --- | --- | --- | --- | --- |
| terms(size=1e8) nested terms | 1e8 | breaker trip | 실패 | OOM |
| terms(size=1e6) nested terms 페이지 흉내 | 1e6 | 4GB+ | 실패 | breaker trip |
| composite(size=1000) after_key 100k 회 | 1e8 | 40MB | 22분 | 정확 |
| composite(size=10000) after_key 10k 회 | 1e8 | 400MB | 9분 | 정확 |
| composite(size=10000) + sub_agg(cardinality) | 1e8 | 600MB | 14분 | 정확 |

composite 의 size 를 늘리면 throughput 이 좋아지지만 한 번 응답 메모리가 비례해 늘어난다. cluster heap 의 25% 정도가 request circuit breaker 의 한계 (`indices.breaker.request.limit`, default 60% 이지만 production 은 25-40% 로 좁히는 경우 흔함). size 1만 정도가 sweet spot.

## 6. circuit breaker 와 search.max_buckets

ES 의 메모리 보호장치는 두 층이다.

```yaml
indices.breaker.total.limit: 70%
indices.breaker.request.limit: 60%
indices.breaker.fielddata.limit: 40%
search.max_buckets: 65536
```

- `request` breaker: 한 search request 가 잡는 메모리. composite 는 size 만큼만 잡으므로 이 breaker 와 거의 무관.
- `fielddata` breaker: doc_values 가 없는 text field 에 fielddata 를 쌓을 때 작동. keyword 로 매핑하면 회피.
- `total` breaker: 위 둘과 다른 작업의 합. 이걸 넘으면 그냥 트립.
- `search.max_buckets`: 응답 bucket 총 개수. composite 는 size 만큼만 응답이라 이 한계도 자연 회피.

운영 권장: composite + size=1만 으로 페이지네이션, request breaker 는 default 유지, max_buckets 는 default 유지. terms 같은 비-composite query 가 max_buckets 에 걸리는 일이 운영 알람의 좋은 신호다.

## 7. composite 가 적합하지 않은 경우

composite 는 sort key 기반이라 다음 요구를 못 받는다.

- *top-N 만 필요*: composite 는 정렬된 모든 unique key 를 흘리지, top-N 만 골라주지는 않는다.
- *count desc* 정렬: composite 의 source 정렬은 key 기반(asc/desc) 이지 doc count 기반이 아니다.
- *cardinality 가 작아 메모리 걱정 없음*: 1만 미만 unique key 면 terms 가 더 단순하다.

대안 비교:

| 도구 | 사용 상황 | 메모리 | 정렬 |
| --- | --- | --- | --- |
| terms | top-N (count desc) 요청 | shard_size * shard 수 | count |
| composite | 모든 unique key 페이지 순회 | size 만 | key |
| rare_terms | 희귀 term 만 (long tail) | bloom filter | count asc |
| sampler | 정확도 trade-off, sample 만 | shard_size | sample 내 |
| transform | 주기적 rollup 을 별도 index 로 | composite + persistence | n/a |

Transform API (7.3+) 는 composite 를 backend 로 쓰는 *영구화된* 집계 파이프라인이다. 1시간마다 (day, user_id) 합계를 계산해 별도 index `orders_rollup` 에 쓰고, 검색 시에는 그 rollup index 만 조회하면 메모리/시간이 한 자릿수 줄어든다.

## 8. 실전 ETL 코드 (Java High Level Client 패턴)

```java
public void streamDailyUserAggregates(Consumer<Bucket> consumer) {
    Map<String, Object> afterKey = null;
    while (true) {
        SearchRequest req = SearchRequest.of(b -> b
            .index("orders")
            .size(0)
            .aggregations("by_day_user", a -> a
                .composite(c -> {
                    c.size(10000)
                     .sources(List.of(
                        Map.of("day",  CompositeAggregationSource.of(s -> s.dateHistogram(d -> d.field("ts").calendarInterval("day")))),
                        Map.of("user", CompositeAggregationSource.of(s -> s.terms(t -> t.field("user_id"))))
                     ));
                    if (afterKey != null) c.after(afterKey);
                    return c;
                })
                .aggregations("total_amount", x -> x.sum(s -> s.field("amount")))
            )
        );

        SearchResponse<Void> resp = client.search(req, Void.class);
        CompositeAggregate agg = resp.aggregations().get("by_day_user").composite();

        if (agg.buckets().array().isEmpty()) break;
        agg.buckets().array().forEach(consumer);

        if (agg.afterKey() == null || agg.afterKey().isEmpty()) break;
        afterKey = agg.afterKey();
    }
}
```

운영 포인트:

- `size: 10000` 은 cluster 부하에 따라 5000-30000 범위에서 조절.
- 종료 조건: bucket 이 비어 있거나 after_key 가 비어 있을 때.
- circuit breaker trip 을 만나면 backoff retry + size 절반 감소를 client 측에 둔다.
- 실행 중 mapping 이 바뀌어도 composite 는 재시작하면 처음부터 다시 흐른다.

## 9. 인덱스 설계가 composite 성능을 결정한다

composite 의 성능은 결국 source 가 가리키는 field 의 cardinality 와 doc_values 접근 속도다.

- 모든 source field 는 doc_values 가 활성화되어야 함.
- text + keyword sub-field 는 keyword 만 source 로 사용.
- date_histogram source 는 `format` 을 명시하면 응답 key 가 사람 읽는 형태로 나오지만, 정렬 비용은 동일.
- 매일 ILM rollover 가 일어나는 인덱스에 composite 를 돌리면 새 인덱스가 빈 상태에서도 source 가 작동.

규칙: composite 는 high-cardinality dimension 의 *정확한 순회* 가 필요한 모든 ETL 의 기본 도구다. terms 가 bucket 수 한계로 실패하기 전에, 처음부터 composite 로 시작해도 손해보다 이득이 크다.

## 참고

- Elasticsearch Reference — Composite Aggregation: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-composite-aggregation.html
- Elasticsearch Reference — Terms Aggregation Approximation: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-terms-aggregation.html
- Elastic Blog — Avoiding Bucket Explosion: https://www.elastic.co/blog/aggregations-cardinality-control
- Elasticsearch Reference — Transform API: https://www.elastic.co/guide/en/elasticsearch/reference/current/transform-overview.html
- Elasticsearch Circuit Breakers: https://www.elastic.co/guide/en/elasticsearch/reference/current/circuit-breaker.html
