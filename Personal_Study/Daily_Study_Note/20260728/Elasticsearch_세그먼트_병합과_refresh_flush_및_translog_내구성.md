Notion 원본: https://app.notion.com/p/3ab5a06fd6d38170b2e3e2ea8770c783

# Elasticsearch 세그먼트 병합과 refresh flush 및 translog 내구성

> 2026-07-28 신규 주제 · 확장 대상: Elasticsearch(색인 구조·Lucene 내부)

## 학습 목표

- 문서 색인이 in-memory 버퍼 → 세그먼트 → 커밋 포인트로 흐르는 단계를 refresh·flush 경계로 구분한다.
- translog 가 refresh 와 독립적으로 내구성을 보장하는 메커니즘과 fsync 정책의 트레이드오프를 정리한다.
- 세그먼트가 불변이기에 삭제·갱신이 tombstone 으로 처리되고 병합으로 회수되는 과정을 파악한다.
- 색인 처리량과 검색 지연 사이의 튜닝 노브를 실측 관점에서 선택한다.

## 1. 색인 데이터의 3단계 흐름

색인 단위는 Lucene 세그먼트다. 문서는 먼저 in-memory 버퍼에 쌓이며 이때는 검색되지 않는다. refresh 가 일어나면 버퍼 내용이 새 세그먼트로 만들어져 파일 시스템 캐시에 올라가고 이때부터 검색 가능해진다(기본 refresh_interval 1초, near real-time). flush 가 일어나면 세그먼트가 디스크에 fsync 되고 커밋 포인트가 기록되며 translog 가 비워진다. refresh 는 검색 가능하게 만들 뿐 내구성은 translog 와 flush 가 담당한다.

## 2. translog — refresh 와 무관한 내구성

색인 요청은 in-memory 버퍼에 쓰이는 동시에 translog 에 append 된다. 기본 durability: request 는 매 색인 요청마다 translog 를 fsync 한 뒤 응답한다.

```json
PUT /orders/_settings
{ "index.translog.durability": "async", "index.translog.sync_interval": "5s" }
```

| durability | fsync 시점 | 처리량 | 유실 위험 |
|---|---|---|---|
| request(기본) | 매 요청 | 낮음 | 없음(응답=내구) |
| async | sync_interval마다 | 높음 | 최대 interval 구간 |

## 3. 세그먼트 불변성과 tombstone

Lucene 세그먼트는 불변이다. 삭제는 live docs 비트맵에 tombstone 표시만 남기고, 갱신은 delete-then-insert 로 처리된다. 죽은 문서의 실제 회수는 세그먼트 병합 시점에 일어난다. 불변성 덕분에 락 없이 동시 읽기가 가능해 near real-time 검색과 높은 읽기 동시성이 나온다.

## 4. 세그먼트 병합 — 백그라운드 정리

refresh 마다 세그먼트가 생겨 수가 늘어난다. Lucene 은 백그라운드에서 작은 세그먼트를 큰 세그먼트로 병합하며 tombstone 문서를 제거한다. TieredMergePolicy 가 지배하며 병합은 I/O 집약적이라 색인 처리량과 경쟁한다. 읽기 전용 인덱스는 _forcemerge 로 1개 세그먼트로 만들면 지연·힙이 줄지만, 쓰기 중 인덱스에는 절대 실행하지 않는다.

```json
POST /logs-2026.07.27/_forcemerge?max_num_segments=1
```

## 5. 색인 처리량 vs 검색 신선도 튜닝

```json
PUT /orders/_settings
{ "index.refresh_interval": "-1", "index.number_of_replicas": 0 }
```

refresh_interval: -1 은 자동 refresh 를 끄고 백필 동안 세그먼트 폭증과 병합 경쟁을 막아 처리량을 크게 올린다. 검색이 방금 색인한 문서를 즉시 봐야 하면 refresh=wait_for 를 붙인다(refresh=true 는 세그먼트 남발이라 지양).

## 6. 세그먼트 상태 관찰

```bash
GET /orders/_cat/segments?v&h=index,shard,segment,docs.count,docs.deleted,size
GET /orders/_stats/merge,refresh,flush
```

docs.deleted 비율이 높으면 병합이 밀린 것이고, 세그먼트 수가 수백을 넘으면 검색 지연이 커진다.

## 7. 정리 — 경계 세 개만 기억

refresh 는 검색 가능 경계, flush 는 디스크 커밋 경계, merge 는 공간 회수 경계다. translog 는 이 셋과 독립적으로 매 요청 내구성을 책임진다. 검색이 느리면 세그먼트가 너무 많은 것, 색인이 느리면 refresh·병합이 과한 것, 데이터가 날아가면 translog async 다.

## 8. 대량 색인 실측 시나리오

```json
PUT /orders/_settings
{ "index.refresh_interval": "-1", "index.number_of_replicas": 0, "index.translog.durability": "async", "index.translog.sync_interval": "30s" }
```

_bulk API 로 한 요청에 수천 건씩 묶어 보내며 배치 크기는 5~15MB 구간에서 최적점을 찾는다. 백필이 끝나면 replica 를 나중에 붙여 segment-based recovery 로 빠르게 복제한다.

## 9. 세그먼트와 힙 — doc values, fielddata

세그먼트는 정렬·집계용 열 지향 자료구조인 doc values 도 담는다. doc values 는 디스크에 저장되고 OS 파일 캐시로 매핑되어 힙을 거의 쓰지 않는다. 반대로 text 필드 집계는 fielddata 를 힙에 올려 서킷 브레이커를 유발하므로 집계·정렬 대상은 keyword 타입으로 매핑한다.

```json
PUT /orders/_mapping
{ "properties": { "category": { "type": "text", "fields": { "raw": { "type": "keyword" } } } } }
```

## 참고

- Elasticsearch Guide — Near real-time search — https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html
- Elasticsearch Guide — Translog — https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules-translog.html
- Elasticsearch Guide — force merge — https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-forcemerge.html
- Elasticsearch: The Definitive Guide — Making Text Searchable
