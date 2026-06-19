Notion 원본: https://www.notion.so/3845a06fd6d381b7bf1ad23e7b604428

# Elasticsearch Shard Allocation과 Recovery 및 ILM Rollover Hot Warm Cold

> 2026-06-19 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- 샤드 할당 결정 과정(allocation deciders)과 리밸런싱 동작을 파악한다
- 노드 장애 시 shard recovery와 동기화 단계를 이해한다
- ILM 정책으로 hot-warm-cold-delete 데이터 수명주기를 자동화한다
- rollover와 data stream으로 시계열 인덱스를 운영한다

## 1. 샤드: 분산의 기본 단위

Elasticsearch 인덱스는 하나 이상의 프라이머리 샤드로 분할되고, 각 프라이머리는 0개 이상의 레플리카 복제본을 가진다. 샤드는 사실상 독립된 Lucene 인덱스이며, 클러스터가 데이터를 분산·복제·병렬 검색하는 최소 단위다.

프라이머리 수는 생성 시 고정되고 레플리카 수는 동적이다. 쓰기는 프라이머리에서 처리 후 복제되고 읽기는 어디서나 처리된다.

```json
PUT /logs-2026
{ "settings": { "number_of_shards": 3, "number_of_replicas": 1 } }
```

샤드가 너무 작고 많으면 클러스터 상태 부담이 크고 너무 크면 recovery·리밸런싱이 느려, 샤드당 수십 GB 규모를 권장 범위로 삼는다.

## 2. Allocation Deciders: 샤드를 어디에 둘까

마스터 노드는 샤드 배치를 일련의 decider를 순서대로 통과시켜 결정한다. SameShard는 같은 샤드의 프라이머리와 레플리카를 같은 노드에 두지 않고, DiskThreshold는 디스크 워터마크를 보며 할당을 막고, Awareness는 zone 분산을, Filter는 노드 속성 기반 규칙을 적용한다(ILM hot/warm 이동).

```json
PUT _cluster/settings
{ "persistent": {
  "cluster.routing.allocation.disk.watermark.low": "85%",
  "cluster.routing.allocation.disk.watermark.high": "90%",
  "cluster.routing.allocation.disk.watermark.flood_stage": "95%"
} }
```

flood_stage를 넘으면 인덱스가 read-only로 전환된다. 디스크가 차서 쓰기가 막히는 사고의 단골 원인이다.

## 3. 클러스터 헬스와 리밸런싱

green은 모든 샤드 할당, yellow는 레플리카 일부 미할당(손실 없음), red는 프라이머리 일부 미할당(접근 불가)이다.

```json
GET _cluster/health
GET _cluster/allocation/explain
```

allocation/explain은 샤드가 왜 할당 안 됐는지 decider별로 설명한다. 노드 추가/제거 시 리밸런싱이 일어나며 대량 운영 작업 중에는 cluster.routing.allocation.enable을 none/primaries로 낮춰 불필요 이동을 막는다.

## 4. Shard Recovery: 장애에서 복구하기

recovery는 샤드가 사용 가능 상태가 되는 과정이다. 레플리카 recovery는 프라이머리와 Lucene 세그먼트를 비교해 다른 세그먼트 파일만 전송한다. 짧은 단절 후에는 translog와 retention lease로 operation 기반 복구를 한다.

```json
GET _cat/recovery?v&active_only=true
PUT _cluster/settings
{ "persistent": { "indices.recovery.max_bytes_per_sec": "100mb" } }
```

translog은 Lucene commit 전 연산을 먼저 기록해 크래시 후 재생으로 복구한다. max_bytes_per_sec로 복구 트래픽을 제한해 정상 운영 영향을 조절한다.

## 5. ILM: 데이터 수명주기 자동화

| Phase | 특성 | 전형적 동작 |
|---|---|---|
| hot | 활발한 쓰기+검색 | rollover |
| warm | 쓰기 없음 | 레플리카 축소, force merge |
| cold | 드문 검색 | searchable snapshot |
| frozen | 매우 드문 | 대부분 스냅샷 |
| delete | 보존 만료 | 인덱스 삭제 |

```json
PUT _ilm/policy/logs-policy
{ "policy": { "phases": {
  "hot": { "actions": { "rollover": { "max_primary_shard_size": "50gb", "max_age": "1d" } } },
  "warm": { "min_age": "2d", "actions": { "forcemerge": { "max_num_segments": 1 }, "allocate": { "number_of_replicas": 0 } } },
  "cold": { "min_age": "7d", "actions": { "searchable_snapshot": { "snapshot_repository": "my-repo" } } },
  "delete": { "min_age": "30d", "actions": { "delete": {} } }
} } }
```

force merge는 세그먼트를 합쳐 검색 효율을 개선한다(쓰기 끝난 인덱스에만). hot/warm 노드 분리는 node.attr 속성과 allocate 액션으로 이루어진다. searchable snapshot은 데이터를 스냅샷에 두고 검색만 가능하게 해 비용을 낮춘다.

## 6. Rollover와 Data Stream

rollover는 인덱스가 조건(나이·크기·문서 수)에 도달하면 새 인덱스를 만들어 쓰기를 넘긴다. data stream은 이 패턴을 추상화해 backing index들을 숨기고 이름 하나로 색인·검색한다.

```json
PUT _index_template/logs-template
{ "index_patterns": ["logs-*"], "data_stream": {},
  "template": { "settings": { "index.lifecycle.name": "logs-policy" } } }
```

data stream은 @timestamp 필드가 필수이며 일반 update/delete를 직접 못 한다. 로그·메트릭처럼 추가만 하는 데이터에 맞고 빈번히 갱신되는 도메인 데이터엔 부적합하다.

## 7. 운영 함정과 진단

가장 흔한 사고는 디스크 워터마크 초과로 인한 read-only 전환이다. 디스크 확보 후에도 블록이 자동 해제되지 않아 수동 해제가 필요하다.

```json
PUT logs-*/_settings
{ "index.blocks.read_only_allow_delete": null }
```

red 진단은 allocation/explain이 1순위 도구다. 샤드 과다는 클러스터 상태 갱신을 느리게 하므로 _cat/shards로 정기 점검하고 rollover 조건을 샤드 크기 기준으로 설계한다. ILM 정책은 GET <index>/_ilm/explain으로 검증한다.

## 8. 스냅샷·복원과 무중단 운영 전략

스냅샷은 Lucene 세그먼트를 객체 스토리지에 저장하며 증분(incremental)이라 주기적 스냅샷 비용이 작다. SLM으로 cron 식 자동화와 보존 정책을 적용한다.

```json
PUT _snapshot/my-repo
{ "type": "s3", "settings": { "bucket": "es-backups" } }
POST _snapshot/my-repo/snap-2026-06-19/_restore
{ "indices": "logs-2026-06", "rename_pattern": "logs-(.+)", "rename_replacement": "restored-$1" }
```

롤링 업그레이드 시 enable을 primaries로 낮춰 리밸런싱을 막고, 매핑 변경은 reindex 후 alias를 원자적으로 전환해 무중단 교체한다.

```json
POST _aliases
{ "actions": [
  { "remove": { "index": "products-v1", "alias": "products" } },
  { "add": { "index": "products-v2", "alias": "products" } }
] }
```

운영 원칙은 "되돌릴 수 있게 바꿈다"다. reindex 후 검증 기간을 두고, alias 전환으로 즉시 롤백을 가능하게 하며, 모든 파괴적 작업 전에 스냅샷을 선행한다.

## 참고

- Elasticsearch Reference — Cluster-level shard allocation and routing settings
- Elasticsearch Reference — Index recovery / Cat recovery API
- Elasticsearch Reference — ILM: Manage the index lifecycle
- Elasticsearch Reference — Data streams / Rollover / Searchable snapshots
