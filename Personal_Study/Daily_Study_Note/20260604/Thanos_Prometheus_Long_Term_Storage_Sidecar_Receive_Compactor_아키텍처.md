Notion 원본: https://www.notion.so/3755a06fd6d38183a965c37e6e2fa2c5

# Thanos Prometheus Long-Term Storage Sidecar Receive Compactor 아키텍처

> 2026-06-04 신규 주제 · 확장 대상: Observability — Logs/Metrics/Traces, OpenTelemetry 샘플링 (학습됨)

## 학습 목표

- Prometheus 단일 인스턴스의 보존·고가용 한계를 Thanos 컴포넌트(Sidecar, Store Gateway, Querier, Compactor, Receive)가 각각 어떻게 해소하는지 분해한다
- Sidecar 방식과 Receive(remote write) 방식의 수집 경로 차이와 선택 기준을 정리한다
- Compactor 의 다운샘플링(5m/1h)과 보존 정책이 쿼리 비용에 미치는 영향을 계산한다
- 글로벌 뷰 쿼리에서 중복 제거(deduplication)가 동작하는 조건과 HA 라벨 설계를 적용한다

## 1. 문제 정의 — Prometheus의 두 한계

Prometheus 는 로컬 TSDB 에 2시간 블록을 쌓는 단일 노드 설계다. 한계는 명확하다. (1) **보존**: 로컬 디스크 크기가 보존 기간의 상한이고, 1년치 메트릭을 NVMe 에 들고 있는 것은 비용 낭비다. (2) **글로벌 뷰/HA**: 클러스터마다 Prometheus 가 따로 있으면 횡단 쿼리가 안 되고, HA 쌍(같은 타깃을 두 인스턴스가 스크레이프)은 데이터가 미묘하게 어귋난 두 사본이 된다.

Thanos 는 이를 "Prometheus 를 그대로 두고 옆에 컴포넌트를 붙이는" 방식으로 푼다. 모든 컴포넌트는 gRPC 의 **StoreAPI** 라는 단일 인터페이스로 묶인다 — Querier 입장에서 2시간 전 데이터든 1년 전 객체 스토리지 데이터든 똑같은 Series() 호출이다.

## 2. Sidecar 경로 — 업로드와 프록시

```
Prometheus(TSDB) ←같은 Pod→ Thanos Sidecar
                                │ ① 완성된 2h 블록을 S3/GCS 업로드
                                │ ② StoreAPI로 최근 데이터 프록시
                                ▼
                            Object Storage (S3)
```

Sidecar 는 두 역할을 한다. Prometheus 가 2시간 블록을 완성하면 객체 스토리지에 업로드하고(`--objstore.config`), Querier 의 StoreAPI 요청을 Prometheus 의 remote read API 로 변환해 **아직 업로드되지 않은 최근 데이터**를 서빙한다.

```yaml
# objstore.yml
type: S3
config:
  bucket: thanos-metrics
  endpoint: s3.ap-northeast-2.amazonaws.com
  sse_config:
    type: SSE-S3
```

운영 요건: Prometheus 는 `--storage.tsdb.min-block-duration=2h --storage.tsdb.max-block-duration=2h` 로 로컬 컴팩션을 비활성화해야 한다(Compactor 가 전담). 로컬 보존은 6h~1d 면 충분하다. **최악의 경우 2시간 분량(미업로드 블록)은 노드 소실 시 사라질 수 있다** — 이 허용 여부가 Sidecar/Receive 선택의 첫 분기점이다.

## 3. Receive 경로 — remote write 기반 수집

Receive 는 Prometheus 의 remote write 를 받는 스테이트풀 수신기다. 자체 TSDB 에 쓰고, 블록을 객체 스토리지에 올리고, 최근 데이터를 StoreAPI 로 서빙한다.

```
Prometheus(stateless에 가깝게) ──remote_write──▶ Thanos Receive (hashring)
                                                    │ TSDB + S3 업로드 + StoreAPI
                                                    ▼
                                                   S3
```

Receive 는 hashring 으로 수평 분산하고 `--receive.replication-factor=3` 으로 수신 시점 복제를 한다. 테넌트는 HTTP 헤더(`THANOS-TENANT`)로 구분한다.

| 기준 | Sidecar | Receive |
| --- | --- | --- |
| Prometheus 변경 | 없음(사이드카만 추가) | remote_write 설정 필요 |
| 데이터 유실 창 | 최대 2h(미업로드 블록) | 수 초(WAL 복제) |
| 쿼리 경로 | Querier→각 Sidecar(분산) | Querier→Receive(중앙) |
| 네트워크 토폴로지 | Querier가 모든 클러스터에 접근 필요 | 클러스터→Receive 단방향 |
| 운영 복잡도 | 낮음 | hashring/스토리지 운영 필요 |

선택 기준은 네트워크 방향성이 결정적이다. 에지/고객사 클러스터처럼 **밖에서 안으로 못 들어가는** 환경은 Receive(단방향 push)가 사실상 유일한 답이다. 같은 VPC 내 소수 클러스터라면 Sidecar 가 운영 부담이 적다.

## 4. Querier — 글로벌 뷰와 중복 제거

Querier 는 무상태 컴포넌트로, 모든 StoreAPI 엔드포인트(Sidecar, Store Gateway, Receive, 다른 Querier)에 팬아웃해 결과를 병합한다. PromQL 평가는 Querier 가 직접 한다.

HA Prometheus 쌍의 중복 제거가 핵심 기능이다. 두 인스턴스에 replica 라벨만 다르게 부여한다.

```yaml
# prometheus-0 external_labels
external_labels:
  cluster: prod-kr
  replica: "0"        # prometheus-1은 "1"
```

```bash
thanos query --query.replica-label=replica ...
```

Querier 는 `replica` 라벨만 다른 시리즈들을 같은 시리즈로 간주하고, 타임스탬프 구간별로 **더 완전한(결측이 적은) 쪽을 선택**하는 penalty 기반 알고리즘으로 한 줄의 시리즈를 합성한다. 한쪽 Prometheus 가 10분 죽어도 그래프에 구멍이 나지 않는 이유다. 주의: `cluster` 같은 식별 라벨을 replica-label 로 지정하면 서로 다른 클러스터 데이터가 합철지는 사고가 난다 — replica 라벨은 오직 "같은 데이터의 사본 번호"여야 한다.

## 5. Store Gateway — 객체 스토리지를 쿼리 가능하게

Store Gateway 는 버킷의 블록 메타(meta.json)와 인덱스 헤더를 로컬에 캠시하고, 쿼리 시 필요한 인덱스/청크 바이트 범위만 GET Range 로 가져온다. 전체 블록 다운로드가 아니라는 점이 비용 구조의 핵심이다.

성능 좌우 요소는 캠시다.

```yaml
# index cache (memcached 권장, 대규모)
--index-cache.config:
  type: MEMCACHED
  config:
    addresses: ["memcached:11211"]
```

블록 수가 수만 개가 되면 sync 와 메모리가 병목이 된다. 시간 파티셔닝으로 Store Gateway 를 샤딩하는 것이 표준 패턴이다.

```bash
# 최근 2주 전용
thanos store --min-time=-2w
# 과거 전용
thanos store --max-time=-2w
```

## 6. Compactor — 컴팩션, 다운샘플링, 보존

Compactor 는 버킷에 대해 싱글턴으로 돌며 세 가지를 한다. (1) 2h 블록들을 8h→2d→14d 로 병합(컴팩션)해 인덱스 중복을 줄이고, (2) **다운샘플링**: 원본(raw)에서 5m 해상도, 5m 에서 1h 해상도 블록을 만든다. 각 다운샘플 시리즈는 sum/count/min/max/counter 5개 집계를 보존해 rate() 류 함수가 올바르게 동작한다. (3) 해상도별 보존 적용:

```bash
thanos compact \
  --retention.resolution-raw=30d \
  --retention.resolution-5m=180d \
  --retention.resolution-1h=2y \
  --deduplication.replica-label=replica   # 버킷 수준 dedup(vertical compaction)
```

다운샘플링은 저장 절약이 아니라 **쿼리 속도** 장치다(5m 블록은 raw 의 수십분의 1 샘플로 6개월 범위 그래프를 그린다). 실제로 5m/1h 블록이 추가되므로 저장량은 오히려 약간 늘 수 있다. Querier 의 `--query.auto-downsampling` 을 켜면 쿼리 range/step 에 맞는 해상도를 자동 선택한다.

운영 주의: Compactor 는 버킷당 반드시 1개만 실행한다(동시 실행 시 블록 코럽션). 디스크는 가장 큰 컴팩션 작업분(수백 GB)이 필요하고, `halt` 상태(겹치는 블록 발견 등)에 빠지면 메트릭 `thanos_compact_halted=1` 로 알람을 건다.

## 7. 쿼리 비용 모델 — 카디널리티와 범위의 곱

장기 보존 시스템의 비용은 대략 다음에 비례한다.

```
쿼리 비용 ∝ 시리즈 수(카디널리티) × 범위 / 해상도
저장 비용 ∝ 활성 시리즈 × 샘플 빈도 × 보존 (압축 후 샘플당 1~2바이트)
```

실측 감각: 활성 시리즈 1천만, 15s 스크레이프, raw 30d 보존이면 객체 스토리지 약 2.5~5TB 수준이다(압축률 의존). S3 비용보다 **쿼리 시 GET 요청 비용과 Store Gateway 메모리**가 지배적이 되는 경우가 많아, 라벨 카디널리티 통제(`prometheus_tsdb_head_series` 모니터링, relabel 로 고카디널리티 라벨 drop)가 장기 비용의 최대 레버다.

## 8. Thanos vs Mimir/Cortex — 아키텍처 비교

| 항목 | Thanos | Grafana Mimir |
| --- | --- | --- |
| 수집 | Sidecar(pull 유지) 또는 Receive | remote write 전용 |
| 저장 단위 | Prometheus TSDB 블록 그대로 | TSDB 블록(자체 ingester가 생성) |
| 쿼리 분산 | Querier 팬아웃(+Query Frontend 샤딩) | query-frontend/scheduler/querier 분리, 샤딩 내장 |
| 멀티테넌시 | Receive 테넌트 헤더(상대적으로 단순) | 1급 개념(테넌트별 한도/격리) |
| 운영 모델 | 컴포넌트 선택적 채택(점진 도입) | 전 컴포넌트 일괄 운영(microservices/monolithic 모드) |

판단 기준: 기존 Prometheus 운영을 거의 안 바꾸고 장기 보존+글로벌 뷰만 원하면 Thanos Sidecar 가 가장 점진적이다. 수백 테넌트 SaaS 멀티테넌시와 초대형 카디널리티(억 단위 활성 시리즈)는 Mimir 의 샤딩 모델이 우세하다. 두 시스템 모두 PromQL 호환이므로 Grafana 대시보드는 그대로 이식된다.

## 9. 배포 청사진 — 3클러스터 글로벌 뷰 예시

```
[클러스터 A/B/C]
  Prometheus x2 (HA, external_labels: cluster, replica)
  + Sidecar → 중앙 S3 버킷

[중앙 관측 클러스터]
  Querier (replica-label=replica)
   ├─ A/B/C Sidecar StoreAPI (최근 데이터)
   ├─ Store Gateway x2 (시간 샤딩, S3)
  Query Frontend (쿼리 캠시 + range 분할)
  Compactor x1 (raw 30d / 5m 180d / 1h 2y)
  Ruler (글로벌 레코딩/알림 룰, 선택)
```

Query Frontend 는 긴 range 쿼리를 24h 단위로 분할해 Querier 에 병렬 전달하고 결과를 캠시(memcached)한다. Grafana 의 반복 대시보드 로드가 캠시 히트로 떨어져 체감 성능이 크게 좋아진다. 알림 평가 지연이 중요한 룰은 각 클러스터의 로컬 Prometheus 에 남기고, 횡단 집계 룰만 Ruler 로 올리는 이원화가 안전하다 — Ruler 는 쿼리 경로(Querier) 가용성에 의존하므로 로컬보다 실패 표면이 넓다.

## 10. Receive hashring 운영 심화 — 확장과 장애 시나리오

Receive 를 운영 경로로 선택했다면 hashring 의 동작을 이해해야 한다. hashring 은 ConfigMap(또는 파일)로 정의되고 `thanos-receive-controller` 가 StatefulSet 변화를 감지해 갱신한다.

```json
[
  {
    "hashring": "default",
    "tenants": ["team-a", "team-b"],
    "endpoints": [
      "thanos-receive-0.thanos-receive:10901",
      "thanos-receive-1.thanos-receive:10901",
      "thanos-receive-2.thanos-receive:10901"
    ]
  }
]
```

시리즈는 (테넌트, 라벨셋) 해시로 엔드포인트에 매핑되고, replication-factor=3 이면 연속한 3개 노드에 복제된다. 쓰기 성공 조건은 quorum(2/3)이다. 주의점 세 가지. (1) **링 변경 시 재셔플**: 노드 추가/제거로 해시 매핑이 바뀌면 해당 시리즈의 TSDB head 가 새 노드에 다시 만들어진다 — 순간적으로 메모리 사용량이 양쪽에 존재해 OOM 위험이 있다. ketama 해시(`--receive.hashrings-algorithm=ketama`)는 변경 시 이동량을 최소화하므로 신규 구축이라면 ketama 가 기본 선택이다. (2) **블록 업로드와 로컬 보존**: Receive 도 2h 블록을 S3 에 올리고 `--tsdb.retention=1d` 정도의 로컬 보존을 둔다. (3) **백프레셔**: quorum 실패 시 Prometheus remote write 가 429/5xx 를 받고 WAL 기반 재시도를 하므로 단기 Receive 장애는 데이터 유실 없이 흡수된다 — Prometheus 쪽 `remote_write` 큐 설정(`max_shards`, `capacity`)이 재시도 흡수량을 결정한다.

```yaml
remote_write:
  - url: https://thanos-receive.example.com/api/v1/receive
    headers:
      THANOS-TENANT: team-a
    queue_config:
      max_shards: 50
      capacity: 10000
      max_samples_per_send: 2000
```

## 11. 운영 알람 — Thanos 자신을 감시하기

장기 저장 파이프라인은 조용히 깨진다. 최소 알람 셋:

| 메트릭 | 조건 | 의미 |
| --- | --- | --- |
| thanos_shipper_uploads_failed_total | increase > 0 | Sidecar/Receive 블록 업로드 실패 |
| thanos_compact_halted | == 1 | Compactor 중단(겹침 블록 등) |
| thanos_objstore_bucket_last_successful_upload_time | 3h 이상 경과 | 버킷 쓰기 정체 |
| prometheus_remote_storage_samples_pending | 지속 증가 | remote write 백프레셔 |
| thanos_store_index_cache_hits_total 비율 | 급락 | Store Gateway 캠시 효율 저하 |

특히 `last_successful_upload_time` 은 "2시간 블록 + 여유" 기준으로 3시간 임계가 적절하며, 이것 하나로 업로드 경로 전체의 침묵 장애를 잡는다.

## 참고

- Thanos 공식 문서 — Components, Sidecar vs Receive (thanos.io)
- Thanos 문서 — Compactor: Downsampling & Retention
- Grafana Mimir Architecture 문서 (grafana.com/docs/mimir)
- PromCon 발표 "Thanos: Global, durable Prometheus monitoring" (Improbable)
- Prometheus 공식 문서 — Remote write tuning, external_labels
