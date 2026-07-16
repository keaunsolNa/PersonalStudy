Notion 원본: https://www.notion.so/39f5a06fd6d381e9ab53c0d38670b126

# Elasticsearch Lucene 세그먼트 병합과 Translog 및 Refresh Flush 주기

> 2026-07-16 신규 주제 · 확장 대상: Elasticsearch

## 학습 목표

- 색인 요청이 Translog·인메모리 버퍼·세그먼트를 거치는 경로를 추적한다
- Refresh 와 Flush 와 Commit 의 책임 차이를 구분한다
- TieredMergePolicy 의 병합 후보 선정 로직을 파라미터 단위로 해석한다
- 색인 지연·검색 지연·디스크 증가 증상을 원인별로 분리한다

## 1. 색인 한 건이 검색될 때까지

Oracle 이나 MySQL 에 익숙하면 Elasticsearch 의 쓰기 경로가 낯설다. RDB 는 WAL 을 쓰고 나면 사실상 조회 가능하지만, Elasticsearch 는 그렇지 않다. 문서 하나를 색인하면 다음 경로를 지난다.

```
POST /orders/_doc/1  { "status": "PAID" }
        │
        ├─ 1. Translog 에 append (fsync 여부는 durability 설정)
        │
        ├─ 2. Lucene IndexWriter 의 인메모리 버퍼에 추가
        │      → 이 시점에는 검색되지 않는다
        │
        ├─ 3. refresh (기본 1초) — 버퍼를 세그먼트로 만들고 Searcher 재개방
        │      → 이제 검색된다. 단 아직 디스크에 fsync 되지 않았다
        │
        └─ 4. flush (기본 30분 또는 translog 512MB) — Lucene commit + translog 비움
               → 이제 재시작해도 살아남는다
```

핵심은 **3번과 4번이 다른 일**이라는 점이다. "검색 가능해지는 것" 과 "디스크에 안전해지는 것" 이 분리돼 있다. RDB 에서는 commit 이 둘을 동시에 보장하지만, Elasticsearch 는 성능을 위해 분리했다.

그렇다면 3번 이후 4번 이전에 노드가 죽으면 어떻게 되는가. **Translog 가 답이다.** 재시작 시 마지막 commit 지점 이후의 translog 를 재생(replay)해 복구한다. Translog 는 Lucene 바깥, Elasticsearch 레벨의 WAL 이다.

Lucene 자체는 append-only 다. **세그먼트는 한번 쓰이면 절대 수정되지 않는다.** 이 불변성이 모든 설계의 뿌리다. 락 없이 읽을 수 있고, OS 페이지 캐시에 안전하게 캐싱되며, 복제가 파일 복사로 끝난다. 대신 삭제와 갱신을 표현할 방법이 필요하고, 세그먼트가 계속 쌓이므로 병합이 필수가 된다.

## 2. Translog — 왜 Lucene commit 만으로 부족한가

Lucene 에는 `IndexWriter.commit()` 이 있다. 이것이 디스크 영속성을 보장한다. 그런데 commit 은 비싸다. 모든 세그먼트 파일을 fsync 하고 segments_N 파일을 갱신한다. 문서 하나마다 commit 하면 처리량이 무너진다.

Elasticsearch 는 Lucene commit 을 드물게 하고(기본 30분), 그 사이의 안전성을 Translog 로 보장한다. Translog 는 순차 append 파일이라 fsync 비용이 훨씬 낮다.

```yaml
# 인덱스 설정
index:
  translog:
    durability: request        # request(기본) | async
    sync_interval: 5s          # async 일 때만 의미
    flush_threshold_size: 512mb
```

`durability` 가 결정적이다.

| 값 | 동작 | 데이터 손실 위험 | 처리량 |
|---|---|---|---|
| `request` (기본) | 매 요청마다 translog fsync | 없음 | 낮음 |
| `async` | `sync_interval` 마다 fsync | 최대 interval 만큼 | 높음 |

`request` 는 primary 와 모든 replica 에서 fsync 가 끝나야 클라이언트에 200 을 반환한다. 확인된 쓰기는 절대 사라지지 않는다. `async` 로 바꾸면 처리량이 크게 오르지만, 노드가 죽으면 마지막 `sync_interval` 구간이 사라진다.

**판단 기준은 데이터의 성격이다.** 주문·결제 로그처럼 유실이 곳 사고인 데이터는 `request` 를 유지한다. 애플리케이션 로그나 메트릭처럼 재생성 가능하고 5초 유실이 무해한 데이터는 `async` 로 바꿔 처리량을 얻는다. 로그 수집 클러스터에서 이 설정 하나로 색인 처리량이 크게 개선되는 경우가 흔하다.

Translog 는 flush 시점에 비워진다. 다만 **전부 비우지는 않는다.** `index.translog.retention` 설정으로 일정 분량을 남겨 replica 복구 시 전체 세그먼트 복사 대신 translog 재생으로 따라잡게 한다. 7.4 이후로는 soft-deletes 기반 retention lease 가 이 역할을 대체했다.

## 3. Refresh — 검색 가시성의 비용

Refresh 는 인메모리 버퍼를 새 세그먼트로 만들고 `IndexSearcher` 를 재개방한다. **fsync 하지 않는다.** 새 세그먼트는 OS 페이지 캐시에만 있고, 나중에 flush 때 디스크로 내려간다.

```yaml
index:
  refresh_interval: 1s   # 기본값
```

이 1초가 "near real-time" 의 실체다. 색인 직후 검색하면 안 나오는 현상의 원인이기도 하다.

**Refresh 비용**은 두 가지다. 첫째, 새 세그먼트 파일 생성. 둘째, Searcher 재개방 시 세그먼트별 자료구조 워밍업. 후자가 더 크다. norms, doc_values, 필드 캐시 등이 새 세그먼트마다 초기화된다.

refresh 가 잦으면 작은 세그먼트가 대량 생성되고, 그만큼 병합 부하가 커진다. 색인 처리량과 검색 지연 사이의 직접적 trade-off 다.

**대량 색인 시 표준 패턴**이 있다.

```json
PUT /orders/_settings
{ "index": { "refresh_interval": "-1", "number_of_replicas": 0 } }

// ... _bulk 로 대량 색인 ...

PUT /orders/_settings
{ "index": { "refresh_interval": "1s", "number_of_replicas": 1 } }

POST /orders/_forcemerge?max_num_segments=1
```

`refresh_interval: -1` 은 자동 refresh 를 끔다. 버퍼가 `indices.memory.index_buffer_size`(기본 힙의 10%)를 넘을 때만 세그먼트가 생긴다. 초기 마이그레이션에서 색인 시간이 수 배 단축되는 경우가 많다.

`number_of_replicas: 0` 도 중요하다. replica 가 있으면 모든 색인이 두 번 일어난다. 색인 후 복제하면 세그먼트 파일 복사만으로 끝나 훨씬 빠르다.

**주의**: `refresh=wait_for` 와 `refresh=true` 를 혼동하면 안 된다.

```json
POST /orders/_doc/1?refresh=true       // 즉시 refresh 강제 — 절대 쓰지 말 것
POST /orders/_doc/1?refresh=wait_for   // 다음 refresh 까지 대기 — 허용
```

`refresh=true` 는 그 요청 하나를 위해 전체 샤드를 refresh 한다. 초당 수백 건이 들어오면 초당 수백 개의 세그먼트가 생겨 병합이 폭주한다. 테스트 코드에서 편의로 쓰다가 운영에 새어 들어가는 사고가 잦다. 통합 테스트에서는 `_refresh` API 를 명시적으로 호출하는 편이 낫다.

`wait_for` 는 다음 예정된 refresh 까지 요청을 대기시킨다. 추가 세그먼트를 만들지 않으므로 안전하다. 다만 응답이 최대 1초 지연되고, 대기 요청 수 한도(`index.max_refresh_listeners`, 기본 1000)를 넘으면 강제 refresh 로 전환된다.

**검색이 없는 인덱스는 refresh 하지 않는다.** 7.0 부터 `refresh_interval` 이 명시되지 않은 인덱스는 30초 동안 검색 요청이 없으면 자동 refresh 를 멈춘다. 검색이 들어오면 재개한다. 로그 인덱스처럼 쓰기만 많고 조회가 드문 경우 자동으로 이득을 본다.

## 4. Flush — Lucene commit 과 Translog 비움

Flush 는 Lucene commit 을 수행하고 translog 를 비운다.

```
1. IndexWriter.commit() 호출
   → 인메모리 버퍼 세그먼트화
   → 모든 세그먼트 파일 fsync
   → 새 segments_N 파일 작성 및 fsync
2. Translog 롤오버 및 이전 파일 정리
```

트리거 조건은 둘이다.

| 조건 | 기본값 |
|---|---|
| Translog 크기 | `index.translog.flush_threshold_size` = 512mb |
| 주기 | 30분 (내부 관리, 조정 비권장) |

**flush 를 수동으로 호출할 일은 거의 없다.** `POST /_flush` 는 존재하지만, Elasticsearch 가 알아서 관리한다. 예외는 인덱스를 close 하거나 스냅샷 직전 정도인데, 이마저도 자동 처리된다.

**세 개념의 책임을 정리하면 이렇다.**

| 작업 | 세그먼트 생성 | fsync | Translog | 검색 가시성 | 기본 주기 |
|---|---|---|---|---|---|
| Refresh | O | X | 유지 | 생김 | 1초 |
| Flush | O | O | 비움 | 생김 | 30분/512MB |
| Translog fsync | X | O (translog만) | - | 무관 | 요청마다 |

Refresh 는 "보이게 하기", Flush 는 "안전하게 하기" 다. 이 분리를 이해하면 왜 `refresh_interval` 을 늘려도 데이터 안전성이 떨어지지 않는지가 명확해진다. 안전성은 translog 가 담당한다.

## 5. 삭제와 갱신 — .liv 파일과 문서 증식

세그먼트가 불변인데 삭제는 어떻게 하는가. **삭제하지 않는다. 표시만 한다.**

각 세그먼트에는 `.liv` 파일(live docs bitset)이 딸린다. 문서를 삭제하면 해당 비트를 0 으로 내린다. 검색 시 이 비트셋을 확인해 필터링한다.

**갱신은 삭제 + 재색인이다.**

```json
PUT /orders/_doc/1 { "status": "SHIPPED" }
// 실제 동작:
// 1. 기존 세그먼트의 doc 1 을 .liv 에서 0 으로 마킹
// 2. 새 버전을 인메모리 버퍼에 추가
// 3. 다음 refresh 에 새 세그먼트로
```

결과적으로 **같은 문서를 100번 갱신하면 100개의 죽은 문서가 세그먼트에 쌓인다.** 이 공간은 병합될 때만 회수된다.

이것이 Elasticsearch 를 자주 갱신되는 데이터에 쓸 때의 근본적 부담이다. RDB 는 UPDATE 가 제자리 갱신이거나 MVCC 로 정리되지만, Elasticsearch 는 병합될 때까지 계속 쌓인다.

삭제 비율은 다음으로 확인한다.

```json
GET /orders/_stats/docs

{
  "docs": {
    "count": 1000000,
    "deleted": 850000    // 삭제 비율 46% — 심각
  }
}
```

`deleted / (count + deleted)` 가 20% 를 넘으면 병합 정책 점검이 필요하다. 40% 를 넘으면 검색 성능과 디스크 사용에 실측 가능한 악영향이 있다.

## 6. TieredMergePolicy — 병합 후보 선정 로직

세그먼트가 무한히 쌓이면 검색이 느려진다. 검색은 모든 세그먼트를 순회하며 결과를 병합하므로 세그먼트 수에 비례해 비용이 늘는다. 병합은 이를 막는 백그라운드 작업이다.

Elasticsearch 는 Lucene 의 **TieredMergePolicy** 를 쓴다. 이름의 "tiered" 는 세그먼트를 크기 계층으로 나눠 비슷한 크기끼리 병합한다는 뜻이다. LSM Tree 의 leveled compaction 과 발상이 같다.

이전 세대인 LogByteSizeMergePolicy 는 인접한 세그먼트만 병합했다. TieredMergePolicy 는 인접성을 버리고 **크기가 비슷한 것끼리** 병합한다. 크기가 크게 다른 세그먼트를 합치면 쓰기 증폭이 커지기 때문이다.

주요 파라미터다.

| 파라미터 | 기본값 | 의미 |
|---|---|---|
| `index.merge.policy.max_merged_segment` | 5gb | 병합 결과 최대 크기 |
| `index.merge.policy.segments_per_tier` | 10 | 각 계층 허용 세그먼트 수 |
| `index.merge.policy.max_merge_at_once` | 10 | 한 병합에 넣을 최대 개수 |
| `index.merge.policy.floor_segment` | 2mb | 이보다 작으면 이 크기로 간주 |
| `index.merge.policy.deletes_pct_allowed` | 20 | 허용 삭제 비율(%) |
| `index.merge.scheduler.max_thread_count` | max(1, min(4, procs/2)) | 동시 병합 스레드 |

**후보 선정 로직**은 대략 이렇다.

```
1. 모든 세그먼트를 크기 내림차순 정렬
2. max_merged_segment 의 절반을 넘는 세그먼트는 후보에서 제외
   (이미 큰 것은 더 키워봐야 손해)
3. 허용 세그먼트 수(allowedSegCount) 계산
   → 총 크기와 floor_segment 로부터 계층 수를 도출
4. 현재 세그먼트 수가 허용치를 넘으면 병합 필요
5. 슬라이딩 윈도우로 max_merge_at_once 개씩 묶어 점수 계산
   score = (합친 크기의 skew) × (합친 크기)^0.05 / (회수될 삭제 문서 보정)
   → skew 가 작을수록(크기가 고를수록) 좋은 점수
6. 최고 점수 조합을 선택
```

`floor_segment` 가 중요한 역할을 한다. 2MB 미만 세그먼트를 전부 2MB 로 간주하므로, 아주 작은 세그먼트들이 하나의 계층으로 뭉쳐 한꺼번에 병합된다. 이 값이 없으면 KB 단위 세그먼트들이 각자 다른 계층으로 흘어져 병합되지 않는다.

**`max_merged_segment: 5gb` 의 의미**가 실무에서 오해가 잦다. 세그먼트가 5GB 에 도달하면 더 이상 병합 대상이 아니다. 즉 그 세그먼트 안의 삭제 문서는 **영구히 회수되지 않는다.** 자주 갱신되는 대형 인덱스에서 디스크가 계속 느는 원인이 대개 이것이다.

대응은 셋이다. 첫째, `max_merged_segment` 를 낮춰(예: 2gb) 세그먼트가 병합 가능 범위에 머물게 한다. 대신 세그먼트 수가 늘어 검색이 느려진다. 둘째, `deletes_pct_allowed` 를 낮춰(예: 10) 삭제 비율 기반 병합을 자주 유발한다. 셋째, 주기적으로 인덱스를 재생성(reindex)한다.

`deletes_pct_allowed` 는 7.x 후반에 추가된 파라미터로, 크기 기준과 무관하게 "삭제가 이 비율을 넘으면 병합" 을 강제한다. 다만 `max_merged_segment` 를 넘은 세그먼트에는 여전히 적용되지 않는다.

**병합 throttling** 은 별도 축이다. 7.x 부터는 `indices.store.throttle.*` 설정이 사라지고 스케줄러가 자동 조절한다. SSD 를 쓴다면 다음이 유효하다.

```yaml
index.merge.scheduler.max_thread_count: 4   # SSD 는 병렬 IO 에 강함
```

HDD 라면 1로 낮춰야 한다. 랜덤 IO 가 섞이면 병합이 검색을 방해한다.

## 7. Force Merge — 언제 쓰고 언제 쓰지 말 것인가

```json
POST /logs-2026.07.15/_forcemerge?max_num_segments=1
```

강제로 세그먼트를 지정 개수까지 병합한다. 모든 삭제 문서가 회수되고 검색이 빨라진다.

**쓸 수 있는 유일한 조건: 인덱스가 read-only 다.**

시계열 로그 인덱스는 롤오버 후 더 이상 쓰이지 않는다. 이때 force merge 하면 디스크와 검색 성능이 모두 개선된다. ILM 의 warm 페이즈에 `forcemerge` 액션을 넣는 것이 표준이다.

```json
PUT /_ilm/policy/logs-policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": { "max_primary_shard_size": "50gb", "max_age": "1d" }
        }
      },
      "warm": {
        "min_age": "2d",
        "actions": {
          "forcemerge": { "max_num_segments": 1 },
          "shrink": { "number_of_shards": 1 },
          "set_priority": { "priority": 50 }
        }
      },
      "delete": { "min_age": "30d", "actions": { "delete": {} } }
    }
  }
}
```

**쓰기가 계속되는 인덱스에 force merge 하면 재앙이다.** 이유는 `max_merged_segment` 제한을 무시하기 때문이다. `max_num_segments=1` 로 만든 50GB 세그먼트는 5GB 한도를 훨씬 넘으므로 **이후 자동 병합에서 영원히 제외**된다. 그 인덱스에 계속 쓰기가 들어오면 삭제 문서가 무한정 쌓이고 회수되지 않는다.

추가 위험도 있다. Force merge 는 동기 작업이며 되돌릴 수 없다. 대형 인덱스에서 수 시간이 걸리고 그동안 대량 IO 를 유발한다. 병합 중에는 원본과 결과가 동시에 존재하므로 **일시적으로 디스크 사용이 2배**가 된다. 디스크 여유가 없으면 워터마크에 걸려 샤드가 read-only 로 전환된다.

## 8. 증상별 진단

**세그먼트 상태 확인**

```
GET /_cat/segments/orders?v&s=size:desc

index  shard prirep segment generation docs.count docs.deleted    size size.memory committed searchable
orders 0     p      _2h              737    1521030       412093   4.8gb      112233 true      true
orders 0     p      _3k             1088     234109        11290 780.2mb       23411 true      true
orders 0     p      _4a             1234       5021            0  12.1mb        1201 false     true
```

`committed: false` 는 refresh 는 됐으나 flush 전인 세그먼트다. `searchable: true` 이므로 검색은 된다. 이 두 컴럼이 §3~4 의 분리를 그대로 보여준다.

**병합 상태 확인**

```
GET /_cat/nodes?v&h=name,heap.percent,merges.current,merges.current_docs,merges.total_time

GET /_nodes/stats/indices/merges
```

`merges.current` 가 항상 0 이 아니면 병합이 밀리고 있다. 색인 속도가 병합 속도를 앞지르는 상태다.

**진행 중 작업 확인**

```
GET /_cat/thread_pool/write,search?v&h=node_name,name,active,queue,rejected

node_name  name   active queue rejected
node-1     write      8    120        3    ← queue 가 쌓이고 rejected 발생
node-1     search     4      0        0
```

`rejected` 가 증가하면 색인 요청이 버려지고 있다. 클라이언트가 재시도해야 한다.

**증상 매핑**

| 증상 | 원인 후보 | 확인 | 대응 |
|---|---|---|---|
| 색인 직후 검색 안 됨 | refresh 대기 | `refresh_interval` | `wait_for` 또는 정상 동작으로 수용 |
| 색인 처리량 저조 | translog fsync | `durability` | 로그성이면 `async` |
| 색인 처리량 저조 | 병합 밀림 | `merges.current` | 스레드 수, `max_merged_segment` |
| 디스크 계속 증가 | 삭제 미회수 | `_stats/docs` deleted | `deletes_pct_allowed` 하향, reindex |
| 검색 지연 증가 | 세그먼트 과다 | `_cat/segments` 행 수 | force merge(read-only 시) |
| 힙 압박 | 세그먼트 메타 | `size.memory` 합계 | 샤드 수 축소, 세그먼트 병합 |
| write queue rejected | 색인 폭주 | `_cat/thread_pool` | bulk 크기 조정, 백프레셔 |

**세그먼트 메모리**는 7.x 이후 크게 줄었다. 과거에는 term dictionary 가 힙에 상주해 세그먼트 수가 힙을 직접 압박했으나, 현재는 대부분 off-heap(mmap)으로 이동했다. 그래도 세그먼트당 고정 오버헤드는 남으므로 샤드당 세그먼트 수를 무한정 늘릴 수는 없다.

## 9. 정리 — 워크로드별 설정 조합

**로그·메트릭 (append-only, 유실 허용, 처리량 우선)**

```yaml
index.refresh_interval: 30s
index.translog.durability: async
index.translog.sync_interval: 5s
index.merge.scheduler.max_thread_count: 4    # SSD
# + ILM 으로 rollover → warm 에서 forcemerge
```

**주문·결제 (갱신 잦음, 유실 불가, 정확성 우선)**

```yaml
index.refresh_interval: 1s
index.translog.durability: request
index.merge.policy.deletes_pct_allowed: 10   # 삭제 회수 적극적으로
index.merge.policy.max_merged_segment: 2gb   # 병합 가능 범위 유지
```

**초기 대량 마이그레이션**

```yaml
index.refresh_interval: -1
index.number_of_replicas: 0
index.translog.durability: async
# 색인 완료 후 원복 + forcemerge
```

세 조합의 차이는 전부 §1 의 경로에서 어느 지점을 느슨하게 할 것인가의 선택이다. Refresh 를 늦추면 가시성을 포기하고 처리량을 얻는다. Translog 를 async 로 하면 안전성을 포기하고 처리량을 얻는다. 병합을 자주 하면 IO 를 쓰고 디스크와 검색 성능을 얻는다. 무엇을 포기할 수 있는지는 데이터의 성격이 결정한다.

## 참고

- Elasticsearch Reference — Near real-time search (https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html)
- Elasticsearch Reference — Translog, Merge, Index modules
- Elasticsearch Reference — Tune for indexing speed / Tune for search speed
- Lucene Javadoc — `TieredMergePolicy`, `IndexWriter`, `LiveDocs`
- Michael McCandless — Visualizing Lucene's segment merges (https://blog.mikemccandless.com/2011/02/visualizing-lucenes-segment-merges.html)
- Elasticsearch Reference — Index lifecycle management actions (forcemerge, shrink, rollover)
