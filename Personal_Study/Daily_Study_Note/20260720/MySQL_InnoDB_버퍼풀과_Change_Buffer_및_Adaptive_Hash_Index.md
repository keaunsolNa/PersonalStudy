Notion 원본: https://www.notion.so/3a35a06fd6d38138942fe90719da1d8b

# MySQL InnoDB 버퍼풀과 Change Buffer 및 Adaptive Hash Index

> 2026-07-20 신규 주제 · 확장 대상: MySQL

## 학습 목표

- InnoDB 버퍼풀의 LRU 변형 구조(young/old 서브리스트)와 미드포인트 삽입 전략을 파악한다
- Change Buffer 가 세컸더리 인덱스 갱신을 지연시켜 랜덤 I/O 를 줄이는 원리를 추적한다
- Adaptive Hash Index 가 언제 도움이 되고 언제 경합을 유발하는지 구분한다
- 버퍼풀 인스턴스 분할과 워밍업 설정으로 성능을 튜닝한다

## 1. 버퍼풀의 역할과 페이지 캐싱

InnoDB 는 모든 데이터와 인덱스를 16KB 페이지 단위로 관리하며, 디스크의 페이지를 메모리에 캐싱하는 공간이 버퍼풀(buffer pool)이다. 쿼리가 어떤 행을 읽으려면 그 행이 속한 페이지가 버퍼풀에 있어야 한다. 없으면 디스크에서 읽어와 적재한다. 버퍼풀 히트율이 높을수록 디스크 I/O 가 줄어 성능이 좋아지므로, 버퍼풀 크기 `innodb_buffer_pool_size` 는 InnoDB 튜닝에서 가장 영향이 큰 단일 파라미터다. 전용 DB 서버라면 물리 메모리의 50~75% 를 할당한다.

```sql
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';
-- 히트율 ≈ 1 - (reads / read_requests)
```

`Innodb_buffer_pool_read_requests` 는 논리적 읽기 요청 수, `Innodb_buffer_pool_reads` 는 그중 디스크까지 내려간 횟수다. 후자가 전자에 비해 크게 작을수록 캐시가 잘 동작하는 것이다. 이 비율이 지속적으로 낮으면 버퍼풀이 작거나 워킹셋이 메모리를 초과한 것이다.

## 2. LRU 변형: young/old 서브리스트와 미드포인트 삽입

버퍼풀은 순수 LRU 가 아니라 두 개의 서브리스트로 나눈 변형 LRU 를 쓴다. 리스트의 앞쪽 약 5/8 은 자주 쓰이는 young(new) 영역, 뒷쪽 3/8 은 최근 적재된 old 영역이다(`innodb_old_blocks_pct` 기본 37%). 새 페이지는 리스트 맨 앞이 아니라 old 영역의 머리(midpoint)에 삽입된다. 이 미드포인트 삽입 전략의 목적은 풀 테이블 스캔 같은 대량 일회성 읽기가 버퍼풀 전체를 오염시키는 것을 막는 데 있다.

```sql
SHOW VARIABLES LIKE 'innodb_old_blocks_pct';   -- 기본 37
SHOW VARIABLES LIKE 'innodb_old_blocks_time';  -- 기본 1000(ms)
```

풀 스캔으로 읽힌 페이지는 old 영역에 들어갔다가, `innodb_old_blocks_time`(기본 1초) 안에 다시 접근되지 않으면 young 으로 승격되지 않은 채 밀려난다. 반면 반복 접근되는 페이지만 young 으로 올라가 오래 살아남는다. 만약 정기 배치 스캔 후 OLTP 응답이 느려진다면 `innodb_old_blocks_time` 을 늘려 스캔 페이지의 승격을 더 억제하는 것을 검토한다.

## 3. Change Buffer: 세컸더리 인덱스 지연 병합

세컸더리 인덱스는 데이터와 물리 순서가 다르므로, INSERT/UPDATE/DELETE 시 갱신해야 할 인덱스 페이지가 버퍼풀에 없으면 디스크에서 랜덤하게 읽어와야 한다. 이 랜덤 읽기 비용을 피하기 위해 InnoDB 는 Change Buffer 를 둔다. 대상 세컸더리 인덱스 페이지가 버퍼풀에 없을 때, 변경 내용을 즉시 적용하지 않고 Change Buffer(버퍼풀 내 특수 영역)에 기록해 두었다가, 나중에 그 페이지가 다른 이유로 읽혀 버퍼풀에 올라올 때 한꺼번에 병합(merge)한다.

```sql
SHOW VARIABLES LIKE 'innodb_change_buffering';     -- all / inserts / deletes / changes / none
SHOW VARIABLES LIKE 'innodb_change_buffer_max_size'; -- 버퍼풀 대비 최대 % (기본 25)
```

Change Buffer 의 전제 조건이 중요하다. **유니크하지 않은** 세컸더리 인덱스에만 적용된다. 유니크 인덱스는 삽입 시점에 중복 여부를 즉시 확인해야 하므로 페이지를 반드시 읽어야 하고, 따라서 버퍼링 대상이 아니다. 이 때문에 대량 삽입 워크로드에서 불필요하게 유니크 인덱스를 남발하면 Change Buffer 이점을 잃고 랜덤 I/O 가 폭증한다. 반대로 SSD 처럼 랜덤 읽기가 저렴한 스토리지에서는 Change Buffer 병합 오버헤드가 이득보다 클 수 있어, 워크로드에 따라 `inserts` 로 제한하거나 끄는 것을 실측으로 판단한다.

## 4. Change Buffer 의 트레이드오프와 크래시 복구

Change Buffer 는 삽입 성능을 극적으로 올리지만 대가가 있다. 첫째, 버퍼풀 메모리의 일부(기본 최대 25%)를 차지하므로 데이터 캐싱 공간이 줄어든다. 둘째, 병합이 지연되므로 버퍼링된 변경이 쌓인 상태에서 크래시가 나면 복구 시 병합 작업까지 재수행되어 복구 시간이 늘어난다. Change Buffer 자체는 시스템 테이블스페이스에 영속화되고 redo 로그로 보호되므로 데이터 유실은 없지만, 재시작이 느려질 수 있다.

셋째, 세컸더리 인덱스로 즉시 조회하는 쿼리가 오면 버퍼링된 변경을 강제로 병합해야 하므로, 지연 이득이 그 순간 반환된다. 삽입 후 곱바로 그 인덱스로 읽는 패턴에서는 Change Buffer 의 이점이 사실상 사라진다. 결론적으로 Change Buffer 는 "쓰기 무겁고 즉시 조회는 드문" 워크로드, HDD 또는 랜덤 I/O 가 비싼 환경, 세컸더리 인덱스가 많은 큰 테이블에서 가장 효과적이다.

| 조건 | Change Buffer 효과 |
|---|---|
| 유니크하지 않은 세컸더리 인덱스 다수 | 큼 (랜덤 I/O 대폭 절감) |
| 유니크 인덱스 위주 | 없음 (버퍼링 불가) |
| 삽입 직후 동일 인덱스 조회 | 사실상 무효 (즉시 병합) |
| SSD/NVMe 저지연 스토리지 | 이득 축소, 끄는 것 고려 |

## 5. Adaptive Hash Index: B-Tree 위의 해시 지름길

InnoDB 의 인덱스는 B+Tree 이므로 조회 시 루트에서 리프까지 여러 단계를 내려가야 한다. 특정 인덱스 페이지에 자주 같은 방식으로 접근하는 패턴이 감지되면, InnoDB 는 그 접근을 가속하기 위해 메모리에 해시 인덱스를 자동 구축한다. 이것이 Adaptive Hash Index(AHI)다. AHI 가 있으면 B+Tree 탐색 단계를 건너뛰고 해시 조회 한 번으로 리프에 도달해 등가 검색(=)이 빨라진다.

```sql
SHOW VARIABLES LIKE 'innodb_adaptive_hash_index';        -- ON/OFF
SHOW VARIABLES LIKE 'innodb_adaptive_hash_index_parts';  -- 파티션 수 (기본 8)
```

AHI 는 완전 자동이며 사용자가 어떤 인덱스를 해시할지 지정할 수 없다. InnoDB 가 접근 패턴을 관찰해 이득이 될 페이지만 해시한다. 등가 조회가 지배적인 워크로드(예: PK 나 유니크 키 기반 룩업이 많은 OLTP)에서 눈에 띄는 이득을 준다.

## 6. Adaptive Hash Index 의 경합 문제

AHI 는 항상 좋기만 한 기능이 아니다. 해시 인덱스를 유지·갱신하려면 내부 래치(latch)가 필요하고, 동시성이 매우 높은 환경에서는 이 AHI 래치가 병목이 된다. `innodb_adaptive_hash_index_parts` 로 해시를 여러 파티션으로 나눠 래치 경합을 분산할 수 있고(기본 8), 그래도 경합이 심하면 아예 `innodb_adaptive_hash_index=OFF` 로 끄는 것이 정답일 때가 있다. 판단 기준은 `SHOW ENGINE INNODB STATUS` 의 SEMAPHORES 섹션에서 AHI 관련 대기가 관측되는지, 그리고 켜고 끓 상태의 처리량을 실측 비교하는 것이다. "기본값이 켜져 있으니 좋은 것"이라 가정하지 말고 워크로드로 검증해야 한다.

## 7. 버퍼풀 인스턴스 분할과 워밍업

큰 버퍼풀은 여러 인스턴스로 쪼갈 수 있다(`innodb_buffer_pool_instances`). 각 인스턴스가 독립된 LRU 리스트와 뮤텍스를 가지므로, 멀티코어 환경에서 버퍼풀 뮤텍스 경합을 줄인다. 다만 버퍼풀이 1GB 미만이면 분할 이득이 없어 단일 인스턴스가 강제된다. 일반적으로 각 인스턴스가 최소 1GB 이상 되도록 개수를 정한다.

```sql
SHOW VARIABLES LIKE 'innodb_buffer_pool_dump_at_shutdown'; -- ON
SHOW VARIABLES LIKE 'innodb_buffer_pool_load_at_startup';  -- ON
```

서버 재시작 시 버퍼풀은 비어 있어(cold) 초기 응답이 느리다. 이를 완화하기 위해 InnoDB 는 종료 시 버퍼풀에 있던 페이지 목록(테이블스페이스 ID + 페이지 번호)을 덤프하고, 시작 시 이를 읽어 미리 적재(warm-up)한다. 이 워밍업이 없으면 재시작 직후 트래픽이 몰릴 때 디스크 I/O 폭주로 응답 지연이 급증한다.

## 8. 통합 진단 관점

InnoDB 성능을 볼 때 이 세 기능은 서로 얽혀 있다. 버퍼풀은 읽기 캐시의 본체이고, Change Buffer 는 쓰기 시 세컸더리 인덱스의 랜덤 I/O 를 미루는 장치이며, AHI 는 반복 등가 조회를 가속하는 지름길이다. 문제 진단의 출발점은 항상 `SHOW ENGINE INNODB STATUS` 와 `Innodb_buffer_pool_*` 상태 변수다. 히트율이 낮으면 버퍼풀 크기나 워킹셋을 의심하고, 삽입이 느리면서 세컸더리 인덱스가 많으면 Change Buffer 설정을, 고동시성인데 CPU 는 녹는데 처리량이 안 나오면 AHI 래치 경합을 점검한다. 세 기능 모두 "기본값이 최선"이 아니라 워크로드 성격에 따라 실측으로 튜닝해야 하는 대상이라는 점이 핵심이다.

## 참고

- MySQL Reference Manual — InnoDB Buffer Pool (https://dev.mysql.com/doc/refman/8.0/en/innodb-buffer-pool.html)
- MySQL Reference Manual — Change Buffer
- MySQL Reference Manual — Adaptive Hash Index
- MySQL Reference Manual — Saving and Restoring the Buffer Pool State
