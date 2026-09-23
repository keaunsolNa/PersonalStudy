Notion 원본: https://www.notion.so/3e45a06fd6d381f999a7e5a147e6bda5

# Elasticsearch 세그먼트 병합과 샤드 라우팅 및 꼬리 레이턴시 제어

> 2026-09-23 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- Lucene 세그먼트 생성·병합 정책이 색인 처리량과 검색 지연에 미치는 영향을 분리한다
- refresh / flush / merge 세 주기를 구분하고 각각의 튜닝 레버를 적용한다
- 커스텀 라우팅으로 조회 팬아웃을 줄이되 샤드 편중을 정량적으로 감시한다
- adaptive replica selection 과 search queue 포화로 발생하는 p99 스파이크를 진단한다

## 1. 세그먼트는 왜 불변인가

Elasticsearch 의 저장 단위는 샤드이고, 샤드는 Lucene 인덱스 하나다. Lucene 인덱스는 다시 여러 세그먼트로 구성된다. 세그먼트는 한 번 쓰이면 절대 수정되지 않는다. 이 불변성 덕분에 잠금 없는 동시 읽기, OS 페이지 캐시의 안정적 활용, 증분 복제가 가능해진다.

대가는 삭제와 갱신의 처리 방식이다. 문서를 삭제하면 세그먼트에서 제거되는 것이 아니라 `.liv` 라이브 문서 비트셋에서 해당 docId 비트가 꺼진다. 갱신은 "옛 문서 삭제 표시 + 새 문서 추가" 이므로, 갱신이 짦은 인덱스는 실제 문서 수보다 훨씬 큰 물리 용량을 갖게 된다. 이 삭제 문서는 병합될 때만 물리적으로 사라진다.

```bash
# 세그먼트 수와 삭제 문서 비율 확인
GET /_cat/segments/orders?v&h=index,shard,segment,docs.count,docs.deleted,size,committed,searchable

# 인덱스 단위 요약
GET /orders/_stats/segments,docs,store?filter_path=_all.total.segments.count,_all.total.docs
```

`docs.deleted / (docs.count + docs.deleted)` 가 20~30% 를 넘어가면 검색마다 헛도는 비용이 누적된다. 역색인 포스팅 리스트를 순회하며 라이브 비트셋으로 필터링하기 때문에, 삭제 문서가 많으면 디스크에서 읽는 양 자체가 늘어난다.

## 2. refresh, flush, merge 의 역할 분리

세 주기는 자주 혼동되지만 각각 다른 문제를 해결한다.

| 동작 | 트리거 | 수행 내용 | 내구성 | 검색 가시성 |
|---|---|---|---|---|
| refresh | 기본 1s(검색 있을 때) | 인메모리 버퍼 → 새 세그먼트(페이지 캐시) | 없음 | 생김 |
| flush | translog 512MB 또는 30분 | Lucene commit + translog 비움 | 확보 | 변화 없음 |
| merge | 병합 정책 판단 | 작은 세그먼트들 → 큰 세그먼트 | 변화 없음 | 삭제 문서 정리 |
| fsync translog | 요청마다(기본 `request`) | translog 디스크 동기화 | 확보 | 변화 없음 |

색인 처리량 튜닝의 1순위는 refresh 주기다. 로그·메트릭처럼 즉시 검색할 필요가 없는 인덱스는 `refresh_interval` 을 30s 이상으로 늘린다. 세그먼트 생성 횟수가 줄고, 그만큼 이후 병합 부하도 줄어드는 복합 효과가 있다.

```json
PUT /logs-2026.09/_settings
{
  "index.refresh_interval": "30s",
  "index.translog.durability": "async",
  "index.translog.sync_interval": "5s"
}
```

`translog.durability: async` 는 노드 크래시 시 최대 `sync_interval` 만큼의 데이터 유실을 허용하는 대신 색인 처리량을 통상 15~30% 올린다. 주문·결제처럼 유실이 허용되지 않는 인덱스에는 절대 적용하지 않는다. 로그 수집 파이프라인처럼 Kafka 등에 원본이 남아 재처리 가능한 경우에만 쓴다.

대량 초기 적재 시에는 refresh 를 아예 끄는 패턴이 표준이다.

```bash
PUT /orders/_settings
{ "index.refresh_interval": "-1", "index.number_of_replicas": 0 }

# ... _bulk 적재 ...

PUT /orders/_settings
{ "index.refresh_interval": "1s", "index.number_of_replicas": 1 }
POST /orders/_forcemerge?max_num_segments=1
```

복제본을 0으로 두는 이유는 적재 중 복제 전송 비용을 없애기 위함이고, 적재 후 1로 복구하면 세그먼트 파일 단위 복사로 훨씬 빠르게 복제본이 만들어진다.

## 3. TieredMergePolicy 의 판단 기준

Lucene 의 기본 병합 정책인 TieredMergePolicy 는 세그먼트를 크기별 계층으로 묶고, 같은 계층에서 병합 후보를 고른다. 판단에 쓰이는 핵심 파라미터는 다음과 같다.

| 파라미터 | 기본값 | 의미 |
|---|---|---|
| `index.merge.policy.segments_per_tier` | 10 | 계층당 허용 세그먼트 수 |
| `index.merge.policy.max_merge_at_once` | 10 | 한 번에 병합할 세그먼트 수 |
| `index.merge.policy.max_merged_segment` | 5gb | 자동 병합 결과 상한 |
| `index.merge.policy.floor_segment` | 2mb | 이보다 작으면 같은 크기로 간주 |
| `index.merge.policy.deletes_pct_allowed` | 20 | 허용 삭제 비율(초과 시 병합 유발) |
| `index.merge.scheduler.max_thread_count` | min(4, CPU/2) | 동시 병합 스레드 |

병합은 I/O 를 크게 쓰므로 스로틀링된다. SSD/NVMe 라면 `max_thread_count` 를 CPU 코어 수의 절반까지 올려도 되지만, 회전 디스크에서는 1 로 낮춰 랜덤 I/O 를 피하는 것이 정답이다.

`max_merged_segment` 기본 5GB 는 중요한 설계 결정이다. 세그먼트가 이 크기에 도달하면 더 이상 자동 병합되지 않고, 그 안의 삭제 문서는 영구히 남는다. 갱신이 짦은 대용량 인덱스에서 디스크가 계속 증가하는 전형적 원인이다. 대응은 두 가지다. 값을 올려 병합을 계속 허용하거나(병합 비용 증가), 인덱스를 시간 단위로 롤오버해 오래된 인덱스를 통 forcemerge 하는 것이다. 운영에서는 후자가 압도적으로 안전하다.

```json
PUT _ilm/policy/orders-policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": { "max_primary_shard_size": "40gb", "max_age": "7d" }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "forcemerge": { "max_num_segments": 1 },
          "shrink": { "number_of_shards": 1 },
          "set_priority": { "priority": 50 }
        }
      },
      "delete": { "min_age": "90d", "actions": { "delete": {} } }
    }
  }
}
```

`forcemerge` 는 쓰기가 끝난 인덱스에만 적용한다. 쓰기 중인 인덱스에 forcemerge 를 걸면 5GB 상한을 무시한 거대 세그먼트가 만들어지고, 이후 그 세그먼트는 영원히 병합되지 않아 삭제 문서가 계속 쌓인다. ILM 의 warm 단계에서 실행되는 것이 그 이유다.

## 4. 샤드 개수의 산술

샤드는 검색 병렬성의 단위이자 오버헤드의 단위다. 검색 요청 하나는 대상 인덱스의 모든 샤드에 팬아웃되고, 각 샤드에서 `from + size` 개의 상위 문서를 수집한 뒤 코디네이팅 노드가 다시 정렬한다. 샤드가 많으면 이 팬아웃과 병합 비용이 곷바로 p99 에 얹힌다.

실무 기준은 다음과 같이 잡는다. 샤드 하나당 10~50GB 를 목표로 하고, 노드 힙 1GB 당 샤드 20개 이하를 상한으로 둔다. 32GB 힙 노드라면 노드당 640 샤드가 상한이고, 실제로는 그 절반 이하로 유지하는 것이 안전하다. 샤드 메타데이터는 클러스터 상태에 포함되어 마스터 노드 부하와 클러스터 상태 전파 지연에 직접 기여하기 때문이다.

```bash
# 노드별 샤드 수와 디스크 사용 확인
GET /_cat/allocation?v&h=node,shards,disk.indices,disk.used,disk.avail,disk.percent

# 샤드 크기 분포
GET /_cat/shards/orders*?v&h=index,shard,prirep,docs,store,node&s=store:desc
```

과도한 샤드의 반대 극단도 위험하다. 샤드 하나가 100GB 를 넘으면 복구·재배치 시간이 길어지고, 노드 장애 시 샤드 재할당으로 클러스터가 수십 분간 yellow 에 머문다. `max_primary_shard_size` 기반 롤오버가 시간 기반보다 선호되는 이유가 이것이다 — 트래픽이 들쌄날쌄해도 샤드 크기가 균일하게 유지된다.

## 5. 커스텀 라우팅으로 팬아웃 줄이기

기본 라우팅은 `shard = hash(_id) % number_of_primary_shards` 다. 여기에 `routing` 값을 지정하면 `hash(routing)` 이 쓰이므로, 같은 라우팅 값을 가진 문서는 모두 같은 샤드에 들어간다. 멀티테넌트 구조에서 테넌트 ID 를 라우팅 키로 쓰면 조회가 1개 샤드만 건드린다 — 팬아웃이 N 에서 1 로 줄어드는 것은 p99 개선 폭이 가장 큰 단일 변경이다.

```json
PUT /orders/_doc/ORD-1001?routing=tenant-42&refresh=wait_for
{ "tenantId": "tenant-42", "amount": 158000, "status": "PAID" }

GET /orders/_search?routing=tenant-42
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "tenantId": "tenant-42" } },
        { "range": { "createdAt": { "gte": "now-7d" } } }
      ]
    }
  }
}
```

라우팅을 쓸 때 `tenantId` term 필터를 함께 넣는 것은 중복이 아니라 필수다. 라우팅은 "어느 샤드를 볼지" 만 정하고, 그 샤드에 다른 테넌트 문서도 섞여 있기 때문이다. 필터를 빼면 남의 데이터가 노출된다.

라우팅의 대가는 샤드 편중이다. 거대 테넌트 하나가 샤드 하나를 가득 채우면 그 샤드가 핫스팟이 된다. 매핑에 `_routing: required` 를 걸어 누락을 막고, 편중은 주기적으로 측정한다.

```json
PUT /orders
{
  "settings": { "number_of_shards": 12, "number_of_replicas": 1 },
  "mappings": {
    "_routing": { "required": true },
    "properties": {
      "tenantId": { "type": "keyword" },
      "status":   { "type": "keyword" },
      "amount":   { "type": "long" },
      "createdAt":{ "type": "date" }
    }
  }
}
```

거대 테넌트 대응으로는 `index.routing_partition_size` 가 있다. 이 값을 k 로 두면 라우팅 값 하나가 k 개 샤드로 분산되고, 조회는 k 개 샤드만 팬아웃한다. 팬아웃 1과 N 사이의 타협점으로, 예를 들어 12 샤드 인덱스에서 partition_size 를 3 으로 두면 대형 테넌트도 3개 샤드에 나뉘다. 단, 이 설정을 쓰면 `_id` 만으로 문서를 직접 조회할 수 없게 되고 join 필드도 제약을 받는다.

## 6. 검색 경로의 지연 구성 요소

검색 한 건의 지연은 대략 다음으로 분해된다. 코디네이팅 노드의 요청 파싱 → 대상 샤드로 팬아웃 → 각 샤드의 query phase(상위 docId + score 수집) → 코디네이터 병합 → fetch phase(실제 `_source` 가져오기) → 응답 조립.

p99 스파이크의 원인은 대개 query phase 자체가 아니라 큐 대기다. 각 노드의 search 스레드풀은 크기가 `int((코어수 × 3) / 2) + 1`, 큐 1000 으로 고정되어 있다. 샤드가 많은 인덱스에 동시 요청이 몰리면 한 요청이 노드당 여러 샤드 태스크를 만들고, 큐가 순식간에 찬다.

```bash
# 스레드풀 포화 확인 — rejected 가 증가하면 확정
GET /_cat/thread_pool/search,write,get?v&h=node_name,name,active,queue,rejected,completed

# 느린 쿼리 로깅 활성화
PUT /orders/_settings
{
  "index.search.slowlog.threshold.query.warn": "1s",
  "index.search.slowlog.threshold.query.info": "500ms",
  "index.search.slowlog.threshold.fetch.warn": "500ms"
}
```

`rejected` 가 0이 아닌데 CPU 사용률이 낮다면 I/O 대기다. 이 경우 페이지 캐시 미스를 의심한다. Elasticsearch 는 힙보다 페이지 캐시에 의존하므로, 힙을 물리 메모리의 절반 이하(그리고 31GB 미만, compressed oops 경계)로 두는 원칙이 여기서 나온다. 힙을 키우는 튜닝이 오히려 검색을 느리게 만드는 흔한 역설이다.

## 7. Adaptive Replica Selection 과 노드 편차

기본적으로 활성인 ARS(`cluster.routing.use_adaptive_replica_selection`)는 복제본 중 하나를 라운드로빈이 아니라 응답 시간·큐 길이·서비스 시간 기반 점수로 고른다. 느린 노드로 가는 요청을 자동으로 줄여 p99 를 낮춘다.

ARS 가 있어도 특정 노드가 계속 느리다면 하드웨어·이웃 프로세스 문제이거나 샤드 배치 불균형이다. 배치는 다음으로 확인하고 교정한다.

```bash
# 왜 이 샤드가 저 노드에 있는지 설명
GET /_cluster/allocation/explain
{ "index": "orders-000042", "shard": 0, "primary": false }

# 노드별 디스크 기반 재균형 임계
PUT /_cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.disk.watermark.low": "80%",
    "cluster.routing.allocation.disk.watermark.high": "85%"
  }
}
```

hot/warm 아키텍처에서는 노드에 속성을 부여하고 인덱스 설정으로 배치를 고정한다. 이 방식은 SSD 노드에 최근 데이터를, HDD 노드에 오래된 데이터를 두어 비용 대비 지연을 최적화한다.

```bash
# elasticsearch.yml (hot 노드)
node.attr.data_tier: hot

# 인덱스 설정
PUT /orders-000042/_settings
{ "index.routing.allocation.require.data_tier": "hot" }
```

## 8. 측정 없는 튜닝을 피하는 절차

실무 순서를 고정해두면 시행착오가 줄어든다.

1. `_cat/thread_pool` 로 rejection 유무 확인 — 있으면 용량/샤드 수 문제
2. slowlog 에서 느린 쿼리 유형 추출 — wildcard, script, 깊은 페이징(`from` 큰 값) 이 대부분
3. `_search?profile=true` 로 쿼리 내부 비용 분해 — `build_scorer` 가 크면 필터 캐시 미스
4. `_cat/segments` 로 세그먼트 수·삭제 비율 확인 — 병합 정책 조정 대상
5. `_nodes/stats` 의 `os.mem.free`, `fs.io_stats` 확인 — 페이지 캐시 부족 판별

깊은 페이징은 구조적으로 해결해야 한다. `from: 10000, size: 10` 은 각 샤드에서 10010개를 수집해 코디네이터로 보낸다. 샤드 12개면 120,120개 문서의 정렬 작업이다. 커서 방식인 `search_after` 로 바꾸면 이 비용이 사라진다.

```json
GET /orders/_search
{
  "size": 20,
  "sort": [ { "createdAt": "desc" }, { "_shard_doc": "asc" } ],
  "search_after": [ 1758556800000, 8421 ],
  "track_total_hits": false
}
```

`track_total_hits: false` 는 총 건수 계산을 생략해 상위 N 개만 필요한 화면에서 지연을 눈에 띄게 줄인다. 정확한 총 건수가 필요하면 `track_total_hits: 10000` 처럼 상한을 두어 "10,000+ 건" 표기로 타협하는 것이 일반적이다.

## 참고

- Elastic, *Elasticsearch Guide — Tune for indexing speed / Tune for search speed*
- Apache Lucene, *TieredMergePolicy* API 문서 및 소스 주석
- Elastic, *Size your shards* (샤드 사이징 공식 가이드)
- Elastic Blog, *Adaptive replica selection in Elasticsearch*
- Elastic, *Index lifecycle management (ILM) actions reference*
