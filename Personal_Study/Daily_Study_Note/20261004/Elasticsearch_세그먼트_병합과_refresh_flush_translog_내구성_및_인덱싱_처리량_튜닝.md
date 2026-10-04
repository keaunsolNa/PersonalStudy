Notion 원본: https://www.notion.so/3ef5a06fd6d38177acbdfb999cdfd1f4

# Elasticsearch 세그먼트 병합과 refresh·flush·translog 내구성 및 대량 인덱싱 처리량 튜닝

> 2026-10-04 신규 주제 · 확장 대상: Elasticsearch (역인덱스·BM25 학습 → 쓰기 경로 심화)

## 학습 목표

- refresh, flush, translog fsync가 각각 가시성과 내구성 중 무엇을 책임지는지 구분한다.
- `_cat/segments`와 `_stats`로 세그먼트 수·병합 비용·translog 크기를 관측한다.
- TieredMergePolicy 설정과 `_forcemerge` 사용 조건을 읽기/쓰기 워크로드에 맞게 정한다.
- 대량 인덱싱 전후로 설정을 바꾸고 복원하는 절차를 `_bulk` 기반으로 직접 실행한다.

## 1. 쓰기 경로 한눈에 보기: 왜 세 가지 동작이 따로 존재하는가

역인덱스와 BM25를 배울 때는 "문서가 색인되면 검색된다"는 한 줄로 넘어가기 쉽다. 그러나 실제 쓰기 경로는 서로 다른 목적을 가진 세 동작으로 쪼개져 있다. 하나는 검색 가시성(visibility)을 만드는 refresh, 하나는 디스크 영속성의 기준점을 만드는 flush(Lucene commit), 마지막은 장애 시 복구 근거가 되는 translog 기록이다. 이 셋을 한 덩어리로 이해하면 "refresh_interval을 늘렸더니 데이터가 유실될까?" 같은 질문에 답할 수 없다. 정답은 유실되지 않는다는 것이다. refresh는 내구성과 무관하고, 내구성은 translog가 담당하기 때문이다.

문서 하나가 샤드의 primary에 도착하면 먼저 메모리 내 인덱싱 버퍼에 들어가고, 동시에 translog에 연산이 append된다. 이 시점에는 문서가 아직 검색되지 않는다. refresh가 일어나면 인덱싱 버퍼의 내용이 새 Lucene 세그먼트로 만들어져 검색 가능해진다. 이 세그먼트는 OS 파일 시스템 캐시에 있을 수 있고 fsync되지 않았을 수 있다. 즉 "검색 가능하지만 아직 Lucene commit되지 않은" 상태다. flush는 Lucene commit을 수행해 세그먼트를 fsync하고 commit point를 기록한 뒤, 이미 커밋된 범위의 translog를 정리한다. 마지막으로 백그라운드 병합 스케줄러가 작은 세그먼트들을 큰 세그먼트로 합친다.

| 동작 | 목적 | 기본 트리거 | 비용의 성격 |
|---|---|---|---|
| refresh | 검색 가시성 | `index.refresh_interval` (기본 1s) | 작은 세그먼트 생성, 캐시 무효화 |
| translog fsync | 연산 단위 내구성 | 요청마다(`request`) 또는 주기(`async`) | 디스크 sync 지연 |
| flush | Lucene commit, translog 정리 | translog 크기 임계 등 | 대량 fsync I/O |
| merge | 세그먼트 수 억제, 삭제 문서 회수 | 병합 정책이 필요하다고 판단할 때 | CPU·디스크 I/O |


## 2. refresh: 검색 가시성과 세그먼트 생성 비용

`index.refresh_interval`의 기본값은 `1s`다. 따라서 색인 후 약 1초 내에 검색에 보인다는 "near real-time" 특성이 나온다. 다만 최근 버전에서는 일정 시간(`index.search.idle.after`, 기본 30s) 동안 검색 요청이 없는 샤드는 주기적 refresh를 건너뛰고, 다음 검색이 들어올 때 refresh하는 동작이 있다. 단, `refresh_interval`을 명시적으로 설정하면 이 유휴 최적화가 적용되지 않는다고 공식 문서에 설명되어 있으므로, 값을 직접 지정한 인덱스는 그 주기대로 refresh가 계속 일어난다는 점을 기억해야 한다.

refresh를 자주 하면 작은 세그먼트가 늘어 검색과 병합 부하가 커지고, 드물게 하면 처리량은 오르지만 가시성이 늦어진다. 특정 쓰기 직후의 가시성이 꼭 필요할 때는 인덱스 전체의 주기를 줄이는 대신 요청 단위 옵션을 쓴다.

```bash
# 인덱스 설정: 대량 적재 중에는 refresh 비활성화
curl -s -X PUT "localhost:9200/logs-demo/_settings" \
  -H 'Content-Type: application/json' -d '{
  "index": { "refresh_interval": "-1" }
}'

# 적재 후 원복 (null 로 기본값 복원)
curl -s -X PUT "localhost:9200/logs-demo/_settings" \
  -H 'Content-Type: application/json' -d '{
  "index": { "refresh_interval": null }
}'

# 요청 단위 가시성: 다음 refresh까지 응답을 보류
curl -s -X PUT "localhost:9200/logs-demo/_doc/1?refresh=wait_for" \
  -H 'Content-Type: application/json' -d '{"msg":"hello"}'
```

`refresh=true`는 요청마다 강제 refresh해 작은 세그먼트를 양산하므로 반복 호출에 부적합하고, `refresh=wait_for`는 강제 refresh 없이 정기 refresh까지 응답을 보류한다. `refresh_interval`이 `-1`이면 오래 기다릴 수 있으니 조합에 주의한다. 설정값 `-1`은 주기적 refresh를 끄는 것일 뿐, 수동 `_refresh`나 요청 옵션은 여전히 동작한다.

## 3. translog: 연산 단위 내구성의 실체

refresh된 세그먼트는 fsync되지 않았을 수 있다. 이 상태에서 노드가 비정상 종료되면 마지막 Lucene commit 이후의 연산은 세그먼트만으로는 복구할 수 없다. 이를 메우는 것이 translog(transaction log)다. 각 샤드는 색인·삭제 연산을 translog에 기록하고, 재시작 시 마지막 commit 이후의 연산을 translog에서 재생(replay)해 상태를 복원한다.

내구성의 수준은 `index.translog.durability`가 결정한다. 기본값 `request`는 인덱싱·삭제·업데이트·bulk 요청이 성공 응답을 반환하기 전에 primary와 replica 모두에서 translog를 fsync하고 commit한다. 따라서 응답을 받은 쓰기는 하드웨어 장애가 없는 한 유실되지 않는다. `async`로 바꾸면 `index.translog.sync_interval`(기본 5s)마다 백그라운드로 fsync한다. 처리량은 오르지만 마지막 sync 이후 최대 sync_interval 동안의 확인된 쓰기가 크래시 시 유실될 수 있다.

```json
PUT /logs-demo/_settings
{
  "index.translog.durability": "async",
  "index.translog.sync_interval": "5s"
}
```

이 선택은 trade-off다. 원본을 다른 곳에서 재적재할 수 있는 파생 인덱스라면 `async`를 감수할 수 있지만, Elasticsearch가 유일한 저장소라면 `request`를 유지해야 한다. 설정은 인덱스 단위이므로 인덱스별로 정책을 분리할 수 있다.

translog 크기가 `index.translog.flush_threshold_size`(기본 512mb)에 도달하면 flush가 유발되며, translog가 클수록 재시작 시 replay가 길어진다. 현재 상태는 `GET /logs-demo/_stats/translog`의 `uncommitted_operations`, `uncommitted_size`로 본다.


## 4. flush: Lucene commit과 translog 정리

flush는 이름 때문에 "메모리를 디스크로 내리는 동작"으로 오해되지만, 정확히는 인덱싱 버퍼의 내용을 세그먼트로 만든 뒤 Lucene commit을 수행하고 translog를 정리하는 동작이다. commit이 끝나면 그 시점까지의 연산은 세그먼트만으로 복구되므로 해당 범위의 translog 세대(generation)를 버릴 수 있다. Elasticsearch는 translog 크기, 그리고 내부적인 조건에 따라 자동으로 flush하므로 대부분의 경우 직접 호출할 필요가 없다.

수동 flush는 재시작 직전 replay 시간을 줄이거나 백업 전 commit 상태를 정리할 때 정도에만 쓴다.

refresh와 flush를 혼동하면 오류가 생긴다. 가시성을 위해 `_flush`를 부르면 불필요한 fsync만 늘고, 내구성을 위해 `_refresh`를 부르면 fsync가 보장되지 않아 의미가 없다. 내구성은 translog와 flush의 조합이 책임진다.

## 5. 세그먼트 병합: TieredMergePolicy의 동작 방식

Lucene 세그먼트는 한 번 만들어지면 불변(immutable)이다. 문서를 삭제해도 세그먼트에서 지워지지 않고 삭제 표시만 남으며, 업데이트는 "삭제 후 재색인"이다. 그래서 시간이 지나면 작은 세그먼트가 쌓이고 삭제된 문서가 공간을 차지한다. 병합은 여러 세그먼트를 읽어 삭제된 문서를 제외한 새 세그먼트 하나로 합치는 작업이다. 병합이 끝나면 원본 세그먼트는 삭제된다.

Elasticsearch가 쓰는 병합 정책은 Lucene의 TieredMergePolicy다. 크기가 비슷한 세그먼트를 "티어"로 묶어, 한 티어에 세그먼트가 일정 개수 이상 쌓이면 병합 후보로 삼는다. 주요 설정은 다음과 같다. 모두 동적 인덱스 설정으로 알려져 있으나, 변경 전에 사용 중인 버전의 공식 문서에서 해당 설정의 동적 변경 가능 여부를 확인하는 것이 안전하다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `index.merge.policy.segments_per_tier` | 10 | 티어당 허용 세그먼트 수. 작을수록 병합이 공격적 |
| `index.merge.policy.max_merge_at_once` | 10 | 한 번에 병합할 세그먼트 수 상한 |
| `index.merge.policy.max_merged_segment` | 5gb | 병합 결과 세그먼트 크기 상한 |
| `index.merge.policy.floor_segment` | 2mb | 이보다 작은 세그먼트는 이 크기로 간주 |
| `index.merge.policy.deletes_pct_allowed` | 33 | 삭제 문서 비율 허용 상한 |
| `index.merge.scheduler.max_thread_count` | max(1, min(4, 프로세서 수/2)) | 샤드당 병합 스레드 수 |

`segments_per_tier`를 낮추면 세그먼트 수가 줄어 검색에 유리하지만 병합이 잦아 쓰기 I/O가 늘어난다. 높이면 쓰기에 유리하지만 세그먼트가 많아 검색과 파일 핸들 부담이 커진다. 병합 스레드 수 기본값은 회전식 디스크가 아닌 SSD 환경을 가정한 값이므로, HDD라면 `max_thread_count`를 1로 낮추라는 공식 문서의 권고가 있다. 병합이 쓰기를 따라가지 못하면 Elasticsearch는 해당 샤드의 인덱싱 스레드를 의도적으로 늦추며, 이때 로그에 병합 지연 경고가 남는다. 

```bash
# 세그먼트 현황: 개수, 크기, 문서 수, 삭제 문서 수
curl -s "localhost:9200/_cat/segments/logs-demo?v&h=index,shard,prirep,segment,generation,docs.count,docs.deleted,size,committed,searchable"

# 병합 통계: 현재 진행 중, 누적 시간, 누적 크기
curl -s "localhost:9200/logs-demo/_stats/merge?human&pretty"
```

`_cat/segments`에서 `docs.deleted` 비율이 높거나 작은 세그먼트가 수십 개 보이면 병합이 밀리는 신호이며, `_stats/merge`의 누적치는 두 시점의 차이를 계산해야 구간별 부하를 알 수 있다.

## 6. _forcemerge: 언제 쓰고 언제 쓰지 말아야 하는가

`_forcemerge`는 병합 정책을 기다리지 않고 지정한 세그먼트 수까지 강제로 병합한다. 정책은 `max_merged_segment`(5GB) 상한을 두지만 force merge는 이 상한을 무시하고 매우 큰 세그먼트를 만들 수 있다. 큰 세그먼트는 이후 삭제 문서 비율이 TieredMergePolicy의 기준에 도달하기까지 병합 후보가 되지 못해 삭제 문서가 오래 남을 수 있다. 그래서 공식 문서는 계속 쓰기가 일어나는 인덱스에는 force merge를 하지 말고, 더 이상 쓰지 않는 읽기 전용 인덱스에만 쓰라고 안내한다.

```bash
# 쓰기가 끝난 인덱스: 세그먼트를 1개로
curl -s -X POST "localhost:9200/logs-2026.09/_forcemerge?max_num_segments=1"

# 삭제 문서가 많은 세그먼트만 정리
curl -s -X POST "localhost:9200/logs-demo/_forcemerge?only_expunge_deletes=true"
```

force merge는 CPU와 디스크 I/O를 크게 쓰는 무거운 작업이라 피크 시간대를 피해야 하며, 병합 중 임시로 디스크 공간이 추가로 필요하다는 점도 확인해야 한다.

## 7. 대량 인덱싱 처리량 튜닝 절차

대량 적재에서 처리량을 올리는 방법은 위 개념을 그대로 적용한 것이다. 핵심은 "적재 동안만" 설정을 완화하고 끝나면 원복하는 것이다. 순서는 (1) 현재 설정 확인, (2) 완화 설정 적용, (3) `_bulk`로 적재, (4) 원복과 refresh, (5) 필요 시 읽기 전용 인덱스만 force merge다.

```bash
# (1) 현재 값 확인
curl -s "localhost:9200/logs-demo/_settings?include_defaults=true&filter_path=**.refresh_interval,**.number_of_replicas,**.translog"

# (2) 적재 전 완화: refresh 끄기, replica 0
curl -s -X PUT "localhost:9200/logs-demo/_settings" -H 'Content-Type: application/json' -d '{
  "index": { "refresh_interval": "-1", "number_of_replicas": 0 }
}'

# (3) _bulk 적재 (NDJSON, 마지막 줄 개행 필수)
curl -s -X POST "localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @batch_0001.ndjson

# (4) 원복
curl -s -X PUT "localhost:9200/logs-demo/_settings" -H 'Content-Type: application/json' -d '{
  "index": { "refresh_interval": null, "number_of_replicas": 1 }
}'
curl -s -X POST "localhost:9200/logs-demo/_refresh"
```

`number_of_replicas: 0`은 초기 적재 시 복제 비용을 없애 처리량을 높이지만, 적재 중 노드가 죽으면 복구할 사본이 없으므로 원본을 재적재할 수 있을 때만 쓴다. 적재가 끝나고 replica를 다시 늘리면 샤드 복사가 일어나는데, 이는 문서를 replica에서 다시 색인하는 것보다 세그먼트 파일을 복사하는 방식이라 일반적으로 효율적이다. 

`_bulk` 요청 크기는 문서 개수가 아니라 바이트 기준으로 잡는 것이 좋다. 공식 문서는 정답 값이 없으므로 한 배치를 약 5~15MB 범위에서 시작해 늘려 가며 측정하라고 안내한다. 너무 작으면 요청 오버헤드가 크고, 너무 크면 메모리 압박과 `429 Too Many Requests`(쓰기 스레드풀 큐 거부)가 늘어난다. 429를 받으면 지수 백오프로 재시도하고 동시 요청 수를 줄여야 한다. 또한 자동 생성 ID(`_id` 미지정)를 쓰면 기존 ID 존재 확인을 생략할 수 있어 유리하다고 알려져 있다.

## 8. 측정 방법과 실측 해석 원칙

위 설정들이 얼마나 처리량을 올리는지는 하드웨어, 문서 크기, 매핑, 샤드 수에 크게 의존하므로 일반화된 숫자를 외우면 오히려 해롭다. 이 노트에서는 수치를 제시하지 않고, 본인 환경에서 재현 가능한 측정 절차만 남긴다. 측정의 원칙은 한 번에 한 변수만 바꾸고, 동일한 데이터셋으로 최소 3회 반복해 편차를 확인하는 것이다.

측정 도구로는 공식 벤치마크 도구 Rally(esrally)가 있고, 간단히는 적재 소요 시간으로 docs/sec을 계산해도 된다.

측정 전후로 `GET /<index>/_stats/indexing,merge,refresh,flush,translog`를 파일로 저장해 차이를 계산하며, 비교할 필드는 `indexing.index_total`, `index_time_in_millis`, `merges.total_time_in_millis`, `total_throttled_time_in_millis`, `refresh.total`, `flush.total`, `translog.operations`다.

결과를 해석할 때는 처리량(docs/sec)만 보지 말고 어떤 자원이 병목이었는지 함께 본다. 예를 들어 `refresh_interval`을 `-1`로 바꿨는데 처리량이 거의 안 변했다면 병목이 refresh가 아니라 병합이나 디스크, 또는 클라이언트 쪽일 가능성이 높다. `total_throttled_time_in_millis`가 크게 증가했다면 병합이 스로틀링되고 있다는 뜻이므로 병합 스레드와 디스크 I/O를 점검한다. translog를 `async`로 바꿨을 때의 이득은 디스크 fsync 지연이 큰 환경일수록 크게 나타나는 경향이 있으나, 이득의 크기는 반드시 직접 측정해서 확인해야 하고 유실 위험과 맞바꾸는 선택임을 기록해 둔다.

## 참고

- Elasticsearch Reference, Tune for indexing speed: https://www.elastic.co/guide/en/elasticsearch/reference/current/tune-for-indexing-speed.html
- Elasticsearch Reference, Index modules (refresh_interval 등): https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules.html
- Elasticsearch Reference, Translog: https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules-translog.html
- Elasticsearch Reference, Merge: https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules-merge.html
- Elasticsearch Reference, Force merge API: https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-forcemerge.html
- Apache Lucene API, TieredMergePolicy: https://lucene.apache.org/core/9_0_0/core/org/apache/lucene/index/TieredMergePolicy.html
- Rally 공식 문서: https://esrally.readthedocs.io/
