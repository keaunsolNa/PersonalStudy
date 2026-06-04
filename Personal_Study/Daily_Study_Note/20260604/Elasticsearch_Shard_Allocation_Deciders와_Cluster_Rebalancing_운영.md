Notion 원본: https://www.notion.so/3755a06fd6d38103929ac961d34d07c2

# Elasticsearch Shard Allocation Deciders와 Cluster Rebalancing 운영

> 2026-06-04 신규 주제 · 확장 대상: Elasticsearch (Inverted Index / ILM / 하이브리드 검색 학습됨)

## 학습 목표

- 마스터 노드의 샤드 배치 결정 과정을 Allocation Deciders 체인 수준에서 분해한다
- Desired Balance Allocator(8.6+)가 기존 BalancedShardsAllocator 와 달리 비동기로 수렴하는 구조를 분석한다
- allocation awareness / forced awareness 로 AZ 장애 시 샤드 복제 동작을 제어한다
- Cluster Allocation Explain API 로 UNASSIGNED 샤드의 원인을 진단하고 복구하는 절차를 수행한다

## 1. 샤드 배치는 누가 결정하는가

샤드 할당(allocation)은 elected master 만 수행하는 클러스터 수준 결정이다. 인덱스 생성, 노드 이탈/합류, 워터마크 초과, 명시적 reroute 가 트리거이며, 결과는 cluster state 의 routing table 변경으로 모든 노드에 전파된다. 결정은 두 단계다.

```
1) Allocation: UNASSIGNED 샤드를 어느 노드에 둘 것인가
2) Rebalancing: 이미 배치된 샤드를 옮겨 균형을 맞출 것인가
```

각 후보 노드에 대해 **Allocation Deciders 체인**이 YES / NO / THROTTLE 을 반환하고, 하나라도 NO 면 그 노드는 제외된다. 주요 decider 와 역할:

| Decider | 거부 조건 |
| --- | --- |
| SameShardAllocationDecider | primary와 replica가 같은 노드 |
| DiskThresholdDecider | low watermark(기본 85%) 초과 노드에 신규 할당 |
| AwarenessAllocationDecider | zone 속성 불균형 |
| ShardsLimitAllocationDecider | index/cluster의 노드당 샤드 수 상한 |
| FilterAllocationDecider | include/exclude/require 필터 위반 |
| ThrottlingAllocationDecider | 동시 recovery 수 초과 → THROTTLE |
| EnableAllocationDecider | allocation.enable 설정(none/primaries 등) |

THROTTLE 은 "지금은 안 되지만 나중에 가능"이며 `cluster.routing.allocation.node_concurrent_recoveries`(기본 2)와 `indices.recovery.max_bytes_per_sec`(기본 40MB/s, 8.x에서 노드 메모리에 따라 자동 조정)로 제어된다.

## 2. Desired Balance Allocator — 8.6 이후의 비동기 수렴

8.5 까지의 BalancedShardsAllocator 는 cluster state 갱신 스레드 안에서 동기적으로 균형을 계산했다. 샤드 수만 기준이라 계산은 썌지만, 대형 클러스터에서 마스터의 state 갱신을 지연시키고, 디스크 사용량·쓰기 부하를 반영하지 못했다.

8.6+ 의 **Desired Balance Allocator** 는 구조를 바꿨다.

- 마스터는 백그라운드 스레드에서 "이상적 배치(desired balance)"를 **비동기로** 계산한다. cluster state 갱신 경로는 현재 배치와 desired balance 의 차이만큼 reconcile(이동 지시)만 한다.
- weight 함수가 다차원이 됐다: 샤드 수, 디스크 사용량 예측, **쓰기 부하(write load) 추정**(인덱싱 스레드 사용량 기반)을 함께 고려한다.

```
weight(node) = Θ_shard · shardCount
             + Θ_disk  · diskUsage
             + Θ_write · writeLoad
```

이로써 "샤드 수는 균형인데 특정 노드만 hot(쓰기 집중)" 인 고전적 불균형이 자동 완화된다. 수렴이 영원히 안 되는 경우(이동 비용 > 이득) 로그에 "Unable to converge" 류 경고가 남는다.

리밸런싱 강도는 다음으로 조절한다.

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.rebalance.enable": "all",
    "cluster.routing.allocation.cluster_concurrent_rebalance": 2,
    "cluster.routing.allocation.balance.threshold": 1.0
  }
}
```

`balance.threshold` 를 올리면 사소한 불균형을 무시해 샤드 이동(=네트워크/디스크 IO)을 줄인다. 인덱싱 피크 시간대에 리밸런싱이 검색 지연을 만든다면 threshold 상향이 첫 번째 손잡이다.

## 3. Disk Watermark — 3단계 방어선

```
low (85%)        : 이 노드로 신규 샤드 할당 중단 (이미 있는 건 유지)
high (90%)       : 이 노드의 샤드를 다른 노드로 이동 시작
flood_stage (95%): 해당 노드에 샤드가 있는 모든 인덱스를 read-only로
```

flood_stage 가 발동하면 색인이 `ClusterBlockException` 으로 실패한다. 7.4+ 부터는 디스크가 high 아래로 내려가면 블록이 **자동 해제**되지만, 그 전에 공간 확보가 우선이다. 대용량 디스크(10TB+)에서 15% 여유는 1.5TB 로 과도하므로 절대값이 합리적이다.

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.disk.watermark.low": "100gb",
    "cluster.routing.allocation.disk.watermark.high": "50gb",
    "cluster.routing.allocation.disk.watermark.flood_stage": "20gb"
  }
}
```

## 4. Allocation Awareness — AZ 분산과 forced awareness

노드에 zone 속성을 부여하면 같은 샤드의 복제본들이 zone 간 분산된다.

```yaml
# elasticsearch.yml (노드별)
node.attr.zone: ap-northeast-2a
cluster.routing.allocation.awareness.attributes: zone
```

기본 awareness 는 "가능하면 분산"이다. zone A 전체가 죽으면 남은 zone B/C 에 replica 를 **다시 만들어** 모든 샤드를 복구한다 — 순간적으로 B/C 의 디스크·IO 부하가 급증한다.

**forced awareness** 는 다르게 동작한다.

```yaml
cluster.routing.allocation.awareness.force.zone.values: ap-northeast-2a,ap-northeast-2b,ap-northeast-2c
```

이 경우 zone A 가 죽어도 A 몫의 replica 를 B/C 에 만들지 **않는다**(unassigned 로 남김). 의도는 두 가지다: (1) 남은 zone 의 용량 폭증 방지, (2) zone A 복귀 시 대량 재복제 없이 로컬 데이터로 빠른 복구. 트레이드오프는 zone 장애 동안 replica 수가 줄어 읽기 처리량과 내결함성이 감소한다는 것. 2-zone 구성에서 replica=1 이면 forced awareness 동안 단일 복제본으로 운영되므로, 3-zone + replica≥1 또는 2-zone + replica=2 가 안전한 조합이다.

## 5. UNASSIGNED 진단 — Cluster Allocation Explain

red/yellow 상태의 원인 추적은 추측하지 말고 explain API 로 한다.

```json
GET _cluster/allocation/explain
{
  "index": "logs-2026.06.04",
  "shard": 0,
  "primary": true
}
```

응답에서 봐야 할 필드:

- `unassigned_info.reason`: NODE_LEFT, INDEX_CREATED, ALLOCATION_FAILED, CLUSTER_RECOVERED 등 최초 미할당 사유
- `can_allocate`: no / yes / throttled / awaiting_info
- `node_allocation_decisions[].deciders[]`: 노드별로 **어느 decider 가 NO 를 반환했는지와 그 이유 문장**

전형적 패턴과 대응:

| explain 출력 | 원인 | 대응 |
| --- | --- | --- |
| disk usage exceeded low watermark | 디스크 부족 | 인덱스 삭제/ILM, 워터마크 절대값 조정 |
| too many shards on this node | shards limit | total_shards_per_node 상향 또는 샤드 수 재설계 |
| node does not match index filter | exclude 필터 잔존 | 마이그레이션 후 필터 제거 누락 — 필터 삭제 |
| ALLOCATION_FAILED ... 5 retries | recovery 반복 실패 | 원인(코럽션/타임아웃) 해결 후 retry_failed |

재시도 한도(기본 5회) 초과로 멈춘 샤드는 원인 제거 후 수동으로 재시도시킨다.

```json
POST _cluster/reroute?retry_failed=true
```

primary 가 영구 소실된 최후의 수단은 두 가지다 — `allocate_stale_primary`(오래된 복사본이라도 승격, 일부 데이터 손실)와 `allocate_empty_primary`(빈 샤드 생성, 해당 샤드 데이터 전체 포기). 둘 다 `accept_data_loss: true` 를 요구하며, 스냅샷 복원이 가능한지 먼저 확인한 뒤에만 쓴다.

## 6. 노드 교체 운영 — 필터로 배수(drain)하기

노드 디커미션의 표준 절차는 exclude 필터다.

```json
PUT _cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.exclude._name": "data-node-07"
  }
}
```

설정 즉시 해당 노드의 샤드가 THROTTLE 한도 내에서 빠져나간다. 진행 상황은 `GET _cat/recovery?active_only=true` 로 본다. 모든 샤드가 빠진 뒤 노드를 내리고, **필터를 반드시 제거**한다(남겨두면 위 표의 "filter 잔존" 장애가 된다). 대량 이동의 속도는 `indices.recovery.max_bytes_per_sec` 와 `node_concurrent_recoveries` 를 일시 상향해 가속할 수 있지만, 검색 레이턴시 SLO 를 보면서 단계적으로 올린다 — 40MB/s → 100MB/s 상향 시 p99 검색 지연이 눈에 띄게 출렁이는 클러스터가 흔하다.

## 7. 샤드 수 설계와 limit decider

리밸런싱의 품질은 샤드 크기 분포에 좌우된다. 권장 운영 기준(Elastic 공식 가이드)은 샤드당 10~50GB, 노드당 샤드 수는 힙 1GB 당 20개 이하다. 수백 MB 짜리 샤드 수천 개는 cluster state 를 비대하게 만들고 allocator 계산을 느리게 한다. ILM rollover 의 `max_primary_shard_size: 50gb` 로 크기를 표준화하면 desired balance 의 디스크 차원 균형도 좋아진다.

핫스팟 방지로 자주 쓰는 인덱스 수준 설정:

```json
PUT logs-*/_settings
{
  "index.routing.allocation.total_shards_per_node": 2
}
```

단일 인덱스의 샤드가 한 노드에 몰리는 것을 막는다. 단, 노드 장애 시 이 제약 때문에 replica 를 배치할 노드가 없어 yellow 가 지속될 수 있으므로 `노드 수 ≥ (primary+replica)/total_shards_per_node` 를 만족하는지 검산한다.

## 8. 운영 체크 시나리오 — 롤링 재시작과 allocation enable

롤링 재시작 시 NODE_LEFT 직후 replica 재생성이 시작되면 불필요한 대량 IO 가 발생한다. 두 가지 장치로 막는다.

```json
// 1) 재시작 전: replica 신규 할당 중단 (primary는 허용)
PUT _cluster/settings
{ "persistent": { "cluster.routing.allocation.enable": "primaries" } }

// 2) 노드 내리기 → 패치 → 기동 → 합류 확인 후 복원
PUT _cluster/settings
{ "persistent": { "cluster.routing.allocation.enable": null } }
```

추가로 `index.unassigned.node_left.delayed_timeout`(기본 1m)이 노드 이탈 후 재할당을 지연시킨다. 재시작이 1분을 넘는다면 인덱스별로 5~10m 으로 늘려 두는 것이 재복제 폭주를 막는다. 노드가 제시간에 복귀하면 로컬 데이터의 sync/seq_no 기반 **operation-based recovery** 로 변경분만 따라잡아 수 초 내 green 으로 돌아온다.

## 9. 정리 — 의사결정 흐름

운영 중 샤드 배치 문제의 진단 순서를 고정한다. (1) `_cluster/health` 로 unassigned 수 확인 → (2) `_cluster/allocation/explain` 으로 decider 거부 사유 식별 → (3) 사유별 표준 대응(디스크/필터/limit/recovery 실패) → (4) `_cat/recovery` 로 복구 진행 관측 → (5) 반복 발생이면 샤드 크기·수 설계와 awareness 구성을 재검토. 임기응변식 reroute 남발은 desired balance 와 싸우는 일이 되므로, allocator 가 왜 그 결정을 했는지(explain)를 항상 먼저 본다.

## 10. 부록 — recovery 내부와 모니터링 지표

샤드 이동·복구의 실체는 **peer recovery** 다. 대상 노드가 소스(primary)에서 데이터를 받아오는 과정은 두 방식이 있다. (1) **file-based**: 세그먼트 파일 자체를 복사. 신규 replica 나 오래 떨어져 있던 노드. (2) **operation-based**: 소스의 translog/Lucene 소프트 삭제에 남은 연산을 seq_no 기준으로 재생. 7.4+ 의 `peer recovery retention lease` 가 각 복사본의 따라잡기 지점을 primary 에 기록해 둔다. 리스 보존 기간(기본 12h) 내 복귀라면 file copy 없이 수 초에 끝난다 — 롤링 재시작에서 delayed_timeout 과 함께 빠른 green 복귀를 만드는 두 축이다.

복구 관측의 핵심 지표:

```
GET _cat/recovery?active_only=true&h=index,shard,type,stage,source_node,target_node,bytes_percent,translog_ops_percent
```

| stage | 의미 |
| --- | --- |
| index | 세그먼트 파일 복사 중 (bytes_percent 진행률) |
| translog | 연산 재생 중 (translog_ops_percent) |
| finalize | retention lease 정리, 마무리 |

`bytes_percent` 가 0 에서 멈춰 있으면 THROTTLE(동시 recovery 한도) 또는 소스 노드 디스크 IO 포화를 의심한다. Prometheus exporter 기준으로는 `elasticsearch_cluster_health_unassigned_shards`, `elasticsearch_cluster_health_relocating_shards` 의 추세, 그리고 `elasticsearch_indices_segments_count` 의 노드별 편차가 리밸런싱 품질의 장기 신호다. unassigned 가 0 이 아닌 상태로 30분 이상 유지되면 자동 복구가 막힌 것이므로 explain API 진단 런북을 트리거하는 알람을 거는 것이 표준 운영이다.

## 참고

- Elasticsearch Reference — Cluster-level shard allocation and routing settings (elastic.co/guide)
- Elasticsearch Reference — Cluster allocation explain API
- Elastic Blog — "Introducing the desired balance allocator" (8.6)
- Elasticsearch Reference — Size your shards 가이드
- GitHub elastic/elasticsearch — DesiredBalanceShardsAllocator 소스
