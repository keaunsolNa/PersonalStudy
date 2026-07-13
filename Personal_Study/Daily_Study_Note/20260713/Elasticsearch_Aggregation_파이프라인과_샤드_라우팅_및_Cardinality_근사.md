Notion 원본: https://app.notion.com/p/39c5a06fd6d381928be7f51854fb6a42

# Elasticsearch Aggregation 파이프라인과 샤드 라우팅 및 Cardinality 근사

> 2026-07-13 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- 버킷·메트릭·파이프라인 집계의 계층과 실행 순서를 구분한다
- 집계가 샤드에서 부분 계산되고 코디네이터에서 병합되는 분산 실행 모델을 이해한다
- `terms` 집계의 근사 오차(doc_count_error)와 `shard_size` 튜닝을 실측 기준으로 다룬다
- `cardinality`의 HyperLogLog++ 기반 근사와 메모리·정확도 트레이드오프를 정리한다

## 1. 집계의 세 계층

메트릭 집계는 숫자를 계산하고(`avg`, `sum`, `cardinality`), 버킷 집계는 문서를 그룹으로 나누며(`terms`, `date_histogram`), 파이프라인 집계는 다른 집계 출력을 후처리한다(`derivative`, `cumulative_sum`). 버킷 안에 메트릭을 중첩해 그룹별 통계를 만드는 것이 기본 패턴이다.

```json
{
  "size": 0,
  "aggs": {
    "by_category": {
      "terms": { "field": "category.keyword", "size": 10 },
      "aggs": { "avg_price": { "avg": { "field": "price" } } }
    }
  }
}
```

`"size": 0`은 히트 문서 없이 집계 결과만 달라는 의미로, 불필요한 페치와 정렬을 건너뛴다.

## 2. 실행 순서

집계는 트리로 구성되고 부모 버킷을 먼저 확정한 뒤 각 버킷 내부에서 서브 집계를 돌린다. 파이프라인 집계는 형제 집계가 끝난 뒤 결과를 읽어 후처리하므로 가장 마지막이다.

```json
{
  "aggs": {
    "sales_per_day": {
      "date_histogram": { "field": "date", "calendar_interval": "day" },
      "aggs": {
        "revenue": { "sum": { "field": "amount" } },
        "revenue_growth": { "derivative": { "buckets_path": "revenue" } }
      }
    }
  }
}
```

`buckets_path`로 형제 메트릭을 참조하는 것이 파이프라인 집계의 핵심 문법이다.

## 3. 분산 실행: 샤드 부분 집계 + 코디네이터 병합

인덱스는 여러 프라이머리 샤드로 나뉘고, 집계는 각 샤드에서 부분 계산된 뒤 코디네이터에서 합쳐진다. `sum`, `avg`, `min`, `max`처럼 결합법칙이 성립하는 메트릭은 이 병합이 정확하다. 문제는 `terms`처럼 상위 N개를 뽑는 집계다. 각 샤드는 자기 로컬 상위만 보내므로, 전역 상위권이지만 특정 샤드에서 하위권인 텀은 누락될 수 있다.

## 4. terms 집계의 근사 오차와 shard_size

각 샤드는 `shard_size`개(기본 대략 `size * 1.5 + 10`)를 코디네이터로 보낸다. 전역 상위 텀이 어떤 샤드에서 shard_size 밖에 있으면 최종 카운트가 실제보다 작아진다. 응답의 `doc_count_error_upper_bound`는 카운트가 실제보다 얼마나 적을 수 있는지의 상한, `sum_other_doc_count`는 상위 버킷에 안 든 나머지 문서 수다.

| 조정 | 정확도 | 비용 |
|---|---|---|
| shard_size 증가 | 상승 | 코디네이터 메모리·병합 시간 증가 |
| 단일 프라이머리 샤드 | 정확 | 인덱싱 확장성 손실 |
| size 자체를 크게 | 상승 | 응답 크기 증가 |

## 5. cardinality: HyperLogLog++ 근사

수백만 고유값을 정확히 세려면 모두 메모리에 담아야 하므로 불가능하다. `cardinality`는 HLL++로 근사한다.

```json
{
  "aggs": {
    "unique_visitors": {
      "cardinality": { "field": "user_id", "precision_threshold": 3000 }
    }
  }
}
```

HLL++은 값의 해시 비트 패턴 통계로 고유 개수를 추정한다. 메모리 사용량이 카디널리티와 무관하게 거의 고정인 것이 핵심 성질이다. `precision_threshold` 이하는 거의 정확하고 그 위로 근사 오차가 생기되 메모리는 상한(필드당 최대 약 40KB)에서 멈춘다. 최대 유효값 40000에서 상대 오차는 약 1%다. 여러 샤드의 HLL 스케치는 병합 가능하므로 분산 환경에서도 전역 추정을 낸다.

## 6. 라우팅으로 집계 지역화

기본적으로 문서는 `_routing`(기본 `_id`) 해시로 분산되지만, 특정 필드로 라우팅을 강제하면 같은 키 문서가 한 샤드에 모인다.

```json
PUT /orders/_doc/1?routing=user_42
{ "user_id": "user_42", "amount": 100 }
```

라우팅 지정 검색(`_search?routing=user_42`)은 단일 샤드만 건드려 지연과 부하가 크게 준다. 다만 라우팅 키가 편향되면 hotspot이 생기므로, 키별 조회가 지배적이고 키 분포가 고른 경우에 유효하다.

## 7. 집계 메모리와 안전장치

`search.max_buckets`(기본 65536)는 한 응답이 만들 버킷 총수를 제한한다. 깊은 다단계 `terms` 중첩은 카티전 곱으로 버킷이 폭발하므로 `composite` 집계로 페이지네이션한다.

```json
{
  "aggs": {
    "paged": {
      "composite": {
        "size": 1000,
        "sources": [ { "cat": { "terms": { "field": "category.keyword" } } } ]
      }
    }
  }
}
```

`composite`는 `after` 키로 다음 페이지를 이어받아 전체 버킷을 스트리밍하듯 순회한다.

## 8. 정리

분산 집계에서 정확한 것과 근사인 것을 구분하는 게 핵심이다. `sum`/`avg`는 정확하고, `terms`의 상위 N은 `shard_size`가 좌우하는 근사이며 응답 오차 지표로 신뢰도를 판단한다. `cardinality`는 HLL++로 메모리를 상한에 묶는 대신 오차를 감수한다. 라우팅으로 키를 한 샤드에 모으면 집계를 지역화하되 hotspot을 감시한다. 정확도가 어디까지 필요한가를 먼저 정하고 shard_size와 precision_threshold를 맞추는 것이 실무 순서다.

## 참고

- Elasticsearch Reference: Aggregations - Bucket / Metrics / Pipeline
- Elasticsearch Reference: Terms Aggregation - Document Counts Are Approximate
- Elasticsearch Reference: Cardinality Aggregation - Counts Are Approximate
- 논문: HyperLogLog in Practice (Heule, Nunkesser, Hall, 2013)
