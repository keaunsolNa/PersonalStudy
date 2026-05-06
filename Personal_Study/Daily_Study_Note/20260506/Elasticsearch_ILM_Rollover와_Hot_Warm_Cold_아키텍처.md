Notion 원본: https://www.notion.so/3585a06fd6d381359dc1f28a5344686b

# Elasticsearch ILM Rollover와 Hot Warm Cold 아키텍처 운영 전략

> 2026-05-06 신규 주제 · 확장 대상: DB

## 학습 목표

- Index Lifecycle Management(ILM) 5단계 phase(hot, warm, cold, frozen, delete) 의 트리거 조건과 액션을 정리한다
- Rollover 가 인덱스 alias / data stream 위에서 어떻게 새 backing index 를 만드는지 추적한다
- Hot/Warm/Cold tier 의 노드 attribute 와 shard allocation filtering 을 코드로 구성한다
- Searchable Snapshot 과 frozen tier 의 비용/지연 trade-off 를 분석한다

## 1. 시계열 데이터의 운영 문제

로그, 메트릭, 추적 데이터는 **시간이 지나면 가치가 떨어진다**. 어제 로그는 매시간 검색되지만 6개월 전 로그는 한 달에 한두 번 들춘다. 그러나 ES 가 default 로는 모든 데이터를 동등하게 다룬다. 단일 인덱스는 시간이 지나면서 (a) shard 사이즈가 비대해지고 (b) refresh / merge 비용이 올라가고 (c) heap 점유와 segment 메모리가 누적된다.

해결책 두 가지:

1. **Time-based index** — `logs-2026.05.06` 처럼 하루 / 주 단위로 인덱스를 분리한다. 오래된 인덱스는 닫거나 지운다.
2. **Tiered storage** — 같은 클러스터 안에서 데이터의 나이에 따라 다른 하드웨어로 옮긴다. 신선한 데이터는 NVMe SSD 에, 6개월 전 데이터는 HDD 에.

ILM 은 이 두 패턴을 자동화한다.

## 2. ILM 의 5 단계 Phase

ILM 정책은 phase 들의 시퀀스다. 각 phase 는 진입 조건(`min_age`)과 액션 집합을 가진다.

| Phase | 의미 | 대표 액션 |
|---|---|---|
| Hot | 활발한 색인 + 검색 | `rollover`, `set_priority`, `forcemerge` |
| Warm | 색인 종료, 검색만 | `allocate`, `forcemerge`, `shrink`, `readonly` |
| Cold | 드물게 검색 | `allocate` (cold tier), `searchable_snapshot` |
| Frozen | 거의 검색 안함 | `searchable_snapshot` (mounted) |
| Delete | 보관 만료 | `delete` |

```json
PUT _ilm/policy/logs-policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": { "max_age": "1d", "max_primary_shard_size": "50gb" },
          "set_priority": { "priority": 100 }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "allocate": { "include": { "data_tier": "data_warm" }, "number_of_replicas": 1 },
          "forcemerge": { "max_num_segments": 1 },
          "shrink": { "number_of_shards": 1 }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "searchable_snapshot": { "snapshot_repository": "s3-archive" }
        }
      },
      "frozen": {
        "min_age": "180d",
        "actions": {
          "searchable_snapshot": { "snapshot_repository": "s3-archive" }
        }
      },
      "delete": {
        "min_age": "365d",
        "actions": { "delete": {} }
      }
    }
  }
}
```

이 정책은 인덱스 생성 → hot phase → 1일/50GB rollover → 7일후 warm tier 이동 → 30일후 S3 snapshot mount → 180일후 frozen → 365일후 삭제 를 자동 수행한다.

## 3. Rollover 의 동작 — Alias vs Data Stream

Rollover 는 "alias 가 가리키는 write 인덱스를 새로 만들고 alias 를 그쪽으로 이동" 하는 동작이다. 옷 방식은 인덱스 + write alias 조합, 신 방식은 data stream 이다.

### 3.1 Index + Write Alias (legacy)

```json
PUT logs-000001
{
  "aliases": {
    "logs": { "is_write_index": true }
  },
  "settings": { "index.lifecycle.name": "logs-policy", "index.lifecycle.rollover_alias": "logs" }
}
```

### 3.2 Data Stream (recommended ≥ 7.9)

```json
PUT _index_template/logs-template
{
  "index_patterns": ["logs-*"],
  "data_stream": {},
  "template": {
    "settings": {
      "index.lifecycle.name": "logs-policy"
    },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" }
      }
    }
  }
}

PUT _data_stream/logs
```

Data stream 은 alias + backing indices 의 통합 추상이다. `@timestamp` 필드가 필수다. 색인 시 `_id` 를 명시할 수 없고 (오직 append-only) 업데이트도 `_update_by_query` 한정이다. 시계열 의도에 맞다.

Backing index 이름은 `.ds-logs-2026.05.06-000001` 형태로 자동 생성된다.

## 4. Tier 분리와 Shard Allocation

각 노드에 `node.roles: [data_hot]` 또는 `node.roles: [data_warm]` 등의 역할을 부여한다. ILM 의 `allocate` 액션이 해당 tier 의 노드로 shard 를 옮긴다.

```yaml
# elasticsearch.yml — hot 노드
node.roles: [ data_hot, data_content, ingest ]
node.attr.disk: nvme
```

```yaml
# elasticsearch.yml — warm 노드
node.roles: [ data_warm ]
node.attr.disk: hdd
```

### 4.1 노드 사양 가이드

| Tier | CPU | Memory | Storage | 데이터 비율 |
|---|---|---|---|---|
| Hot | 8~16 vCPU | 32~64 GB | NVMe SSD 1~2 TB | 5~10% |
| Warm | 4~8 vCPU | 16~32 GB | SATA SSD 또는 HDD 4~8 TB | 30~40% |
| Cold | 2~4 vCPU | 8~16 GB | HDD 8~16 TB + S3 mount | 50%+ |

JVM heap 은 노드 메모리의 절반, 최대 31GB(compressed oops 한계). cold tier 는 검색이 드물어 heap 을 작게 잡고 page cache 에 의존한다.

## 5. Force Merge 와 Shrink — Warm 단계 최적화

Hot 단계의 shard 는 수많은 segment 로 구성되어 있다. Warm 으로 옮길 때는 색인이 더 이상 일어나지 않으므로 segment 를 강제로 1개로 합친다.

```
POST logs-000001/_forcemerge?max_num_segments=1
```

효과: segment metadata 메모리 감소, 검색 latency 개선, 디스크 사용 압축. 비용: I/O 폭증, CPU 사용. 그래서 ILM 의 forcemerge 액션은 warm tier 진입 직후 한 번만 자동 수행된다.

`shrink` 는 shard 개수를 줄인다. hot 단계에 색인 분산을 위해 shard 6개로 시작했어도, warm 에서는 검색 부담이 줄었으니 shard 1개로 합쳐 메타데이터 비용을 줄인다. 조건: 원본 shard 수가 목표 shard 수의 배수여야 한다(6 → 3, 6 → 1 가능, 6 → 4 불가).

```json
PUT logs-000001/_settings
{
  "index.routing.allocation.require._name": "warm-node-1",
  "index.blocks.write": true
}
POST logs-000001/_shrink/logs-000001-shrunk
{
  "settings": {
    "index.number_of_shards": 1,
    "index.number_of_replicas": 1
  }
}
```

## 6. Searchable Snapshot — Cold/Frozen 의 핵심

Cold/Frozen tier 의 진짜 비용 절감 메커니즘은 **searchable snapshot** 이다. 인덱스 데이터를 S3/GCS/HDFS 같은 object storage 에 snapshot 으로 옮기되, 검색 가능한 상태로 mount 한다.

두 mount 모드:

- **fully mounted (cold)**: 데이터를 cold 노드의 로컬 디스크에 복사 + S3 에서 stream. 검색 latency 가 hot 과 거의 같다. local cache 가 사실상 full copy.
- **partial mounted (frozen)**: 데이터를 S3 에만 두고 검색 시 필요한 block 만 노드 page cache 로 가져온다. 메모리 한정으로 운영 가능. 단, query latency 가 segment 단위 cache miss 시 수초 단위로 튀기도 함.

Frozen tier 는 전용 노드 그룹(`node.roles: [data_frozen]`) 에 둔다. heap 작게(8GB), shared cache 크게(NVMe 로 50GB+).

비용 효과 — AWS 기준 EBS gp3 대비 S3 Standard 는 GB당 약 1/3, S3 Glacier 는 1/10. 1년 보관 데이터를 frozen 으로 옮기면 storage 비용이 70% 이상 줄어드는 사례가 흔하다.

## 7. Index Template 과 Component Template

ILM 정책을 인덱스에 자동 적용하려면 index template 이 필요하다. 7.8 부터는 component template 으로 reusable block 을 만든다.

```json
PUT _component_template/logs-mappings
{
  "template": {
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "level": { "type": "keyword" },
        "message": { "type": "text" },
        "trace_id": { "type": "keyword" }
      }
    }
  }
}

PUT _component_template/logs-settings
{
  "template": {
    "settings": {
      "index.number_of_shards": 6,
      "index.number_of_replicas": 1,
      "index.lifecycle.name": "logs-policy",
      "index.codec": "best_compression"
    }
  }
}

PUT _index_template/logs-template
{
  "index_patterns": ["logs-*"],
  "data_stream": {},
  "composed_of": ["logs-mappings", "logs-settings"],
  "priority": 200
}
```

`composed_of` 의 순서가 중요 — 뒤에 오는 component 가 앞을 override 한다. 신규 backing index 가 생성될 때마다 이 template 이 적용된다. `index.codec=best_compression` 은 zstd 기반 압축으로 디스크 사용량 ~30% 감소. trade-off: 검색 시 decompression CPU.

## 8. ILM 운영 체크리스트와 troubleshooting

ILM 진행 정지:

```bash
GET logs-000001/_ilm/explain
```

흔한 원인: warm/cold tier 노드 부족, snapshot repository 미연결, shard relocation 동시성 한계. retry:

```bash
POST logs-000001/_ilm/retry
```

ILM polling 간격 기본 `indices.lifecycle.poll_interval=10m`. 테스트 환경에서는 1m 로 줄여 트리거 동작을 빠르게 본다.

```json
PUT _cluster/settings
{
  "transient": { "indices.lifecycle.poll_interval": "1m" }
}
```

read-only block 정리:

```json
PUT logs-000001/_settings
{ "index.blocks.write": false, "index.blocks.read_only_allow_delete": null }
```

## 9. data tiering 의 한계

ILM 으로도 풀리지 않는 케이스가 있다.

**Hot → Warm 이동 직후 trigger되는 검색** 이 latency 를 망친다. 디스크 전송 중 검색이 들어오면 두 tier 양쪽에서 shard 가 바쁘다. 해결책: `index.routing.allocation.total_shards_per_node` 와 `cluster.routing.allocation.cluster_concurrent_rebalance` 를 conservatively 잡는다.

**다중 인덱스 join 검색** 은 tier 가 다르면 latency 차이가 두드러진다. cross-cluster search 와 결합한 frozen tier 는 timeout 을 넘넘히(`search.default_search_timeout=60s`) 잡고, 클라이언트도 retry 정책 별도로 둔다.

**rollup / transform** 은 ILM 과 별개 시스템이다. 시계열 집계 데이터를 만들어 cold tier 데이터의 검색 부담을 줄인다. ILM + rollup 조합으로 365일 raw 보관 + 730일 hourly rollup 보관이 가능하다.

## 참고

- Elastic Docs — Manage the index lifecycle: https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html
- Elastic Docs — Data tiers and ILM: https://www.elastic.co/guide/en/elasticsearch/reference/current/data-tiers.html
- Elastic blog — Searchable snapshots: https://www.elastic.co/blog/searchable-snapshots-cut-costs-of-elasticsearch-deployments
- "Elasticsearch — The Definitive Guide" 시계열 데이터 관리 챕터
- KIBANA Stack Management UI — ILM/Index Templates 항목
