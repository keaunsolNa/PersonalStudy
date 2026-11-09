Notion 원본: https://app.notion.com/p/4135a06fd6d381519225ee9c33ea343d

# Elasticsearch refresh·flush·translog와 Lucene 세그먼트 머지: 근실시간 검색과 색인 처리량 튜닝

> 2026-11-09 신규 주제 · 확장 대상: Elasticsearch (색인 내부 구조)

## 학습 목표

- 문서가 검색 가능해지기까지 in-memory buffer → 세그먼트 → 디스크 fsync 경로를 구분한다
- `refresh_interval` 변경으로 대량 색인 처리량을 높이고 효과를 측정한다
- translog durability 설정의 데이터 손실 범위를 판단한다
- 세그먼트 머지와 `force_merge` 의 비용과 적용 시점을 결정한다

## 1. 쓰기 경로 개요

Elasticsearch 샤드는 하나의 Lucene 인덱스다. 색인 요청이 들어오면 (1) 문서가 **in-memory indexing buffer** 에 쌓이고 동시에 (2) **translog** 에 append 된다. 이 시점엔 검색되지 않는다. **refresh** 가 일어나면 버퍼가 새 **세그먼트**로 만들어져 파일시스템 캐시에 열리고 검색 가능해진다. 아직 fsync 전이라 디스크 영속은 아니다. **flush** 는 세그먼트를 디스크에 fsync 하고 translog 를 비운다(새 generation). 이 구조 때문에 ES 는 "근실시간(NRT)" 이며 기본 1초 지연이 있다.

## 2. refresh 와 refresh_interval

기본 `index.refresh_interval` 은 1s 다. 검색 요청이 최근 30초 이상 없는 샤드는 refresh 를 건너뛰는 search idle 최적화가 적용된다. refresh 마다 작은 세그먼트가 생기므로 대량 색인 중엔 세그먼트가 폭증한다.

```json
PUT /logs-2026.11/_settings
{ "index": { "refresh_interval": "-1", "number_of_replicas": 0 } }
```

대량 적재 전에 refresh 를 끄고 replica 를 0 으로 두면 각 문서를 복제본에 중복 색인하는 비용이 사라진다. 적재 후 원복한다.

```json
PUT /logs-2026.11/_settings
{ "index": { "refresh_interval": "1s", "number_of_replicas": 1 } }
```

일반적으로 1s → 30s 변경만으로 로그성 색인 처리량이 수십 % 증가하는 사례가 흔하나, 실제 수치는 문서 크기와 매핑에 좌우되므로 `_bulk` 로 동일 데이터를 넣어 `took` 시간을 비교해 확정한다. 단건 `?refresh=wait_for` 는 다음 refresh 까지 요청을 블록하므로 처리량에 불리하고, `?refresh=true` 는 즉시 refresh 를 강제해 세그먼트 난립을 유발한다.

## 3. translog 와 durability

translog 는 마지막 flush 이후의 작업 로그로, 노드 장애 시 재생(replay)해 세그먼트에 반영되지 않은 문서를 복구한다. 기본 `index.translog.durability` 는 `request` 로 **매 요청마다 fsync** 해 확인 응답 이전 데이터를 잃지 않는다.

```json
PUT /metrics/_settings
{
  "index": {
    "translog.durability": "async",
    "translog.sync_interval": "5s"
  }
}
```

`async` 는 5초 간격 fsync 라 장애 시 최대 약 5초치 확인된 쓰기를 잃을 수 있다. 유실 허용되는 메트릭에만 쓰고 주문 같은 데이터에는 쓰지 않는다. flush 는 translog 크기가 `index.translog.flush_threshold_size`(기본 512MB)를 넘으면 자동 수행된다.

## 4. 세그먼트 머지

Lucene 세그먼트는 불변이다. 삭제는 `.liv` 파일의 비트 표시로, 수정은 삭제 + 재색인으로 처리된다. 세그먼트가 많으면 검색 때 세그먼트마다 질의를 수행해 느려지고 삭제 문서가 공간을 차지한다. 백그라운드 **TieredMergePolicy** 가 비슷한 크기의 세그먼트를 묶어 큰 세그먼트로 병합하며 이때 삭제 문서가 실제로 제거된다. 머지는 디스크 I/O 와 CPU 를 쓰므로 색인과 경합한다.

```
GET /_cat/segments/logs-2026.11?v&h=index,shard,segment,docs.count,docs.deleted,size
GET /logs-2026.11/_stats/merge,refresh,flush?human
```

`docs.deleted` 비율이 높으면 갱신이 잦은 인덱스라는 신호다. 부분 갱신이 많으면 `_update` 대신 불변 로그 모델(append-only)을 고려한다.

## 5. force_merge

더 이상 쓰기가 없는 인덱스(롤오버 된 과거 로그)는 세그먼트를 1개로 합치면 검색 속도와 디스크 효율이 좋아진다.

```json
POST /logs-2026.10/_forcemerge?max_num_segments=1
```

쓰기 중인 인덱스에는 하지 말아야 한다. 매우 큰 단일 세그먼트(기본 5GB 상한 초과)가 만들어지면 이후 삭제 문서 회수가 머지 대상에서 밀려 비효율이 생긴다. ILM(Index Lifecycle Management)의 warm 단계에서 `forcemerge` 액션으로 자동화하는 것이 표준 방식이다.

## 6. 튜닝 체크리스트와 trade-off

대량 적재: refresh 비활성, replica 0, `_bulk` 5~15MB 단위, 응답의 `errors` 필드 확인. 실시간성 요구가 있는 일반 서비스: refresh 1s 유지, 필요한 요청에만 `wait_for`. 일관성이 중요한 데이터: translog `request` 유지. 쓰기 처리량과 실시간성, 내구성은 서로 맞교환 관계이므로 요구사항을 먼저 수치로 정의한 뒤 설정을 고른다.

## 참고

- Elasticsearch Reference: Near real-time search, Translog, Tune for indexing speed
- Elasticsearch Reference: Force merge API, ILM actions
- Apache Lucene: TieredMergePolicy javadoc
