Notion 원본: https://www.notion.so/3765a06fd6d381f4b2d1f41346c98f63

# MySQL InnoDB MVCC 내부 — Undo Log, ReadView, Purge와 History List Length

> 2026-06-05 신규 주제 · 확장 대상: SQLD/MySQL (트랜잭션·격리수준 기초 학습됨)

## 학습 목표

- InnoDB가 Undo Log 체인과 ReadView로 일관된 읽기를 재구성하는 과정을 단계별로 추적한다
- REPEATABLE READ와 READ COMMITTED의 ReadView 생성 시점 차이를 실험으로 검증한다
- History List Length(HLL) 폭증의 원인(장기 트랜잭션)을 진단하고 purge 지연을 해소한다
- Long-running 트랜잭션이 디스크·성능에 미치는 비용을 정량적으로 측정한다

## 1. MVCC의 물리적 재료 — 히든 컬럼과 Undo Log

InnoDB의 모든 클러스터드 인덱스 레코드에는 사용자 컬럼 외에 세 개의 히든 필드가 붙는다.

| 필드 | 크기 | 역할 |
|---|---|---|
| `DB_TRX_ID` | 6B | 이 레코드를 마지막으로 변경한 트랜잭션 ID |
| `DB_ROLL_PTR` | 7B | 직전 버전이 저장된 undo log record 포인터 |
| `DB_ROW_ID` | 6B | PK 없을 때만 — 내부 row id |

UPDATE가 실행되면 InnoDB는 레코드를 **제자리에서(in-place)** 갱신하고, 변경 전 이미지를 undo log에 기록한 뒤 `DB_ROLL_PTR`가 그 undo record를 가리키게 한다. 같은 행이 연속으로 갱신되면 undo record들이 roll pointer로 연결되어 **버전 체인**이 만들어진다.

```
[현재 레코드 trx_id=120] → undo(trx_id=110 시점 이미지) → undo(trx_id=95 시점 이미지) → ...
```

Oracle의 undo tablespace와 개념은 같지만, InnoDB는 undo를 INSERT용과 UPDATE용으로 구분한다. INSERT undo는 트랜잭션 커밋 즉시 폐기 가능하다(이전 버전이 존재하지 않으므로 일관된 읽기에 불필요). UPDATE undo는 **그 버전을 필요로 할 수 있는 모든 ReadView가 사라질 때까지** 보존해야 하며, 이것이 purge 문제의 근원이다.

## 2. ReadView — 가시성 판단 자료구조

일관된 읽기(consistent read)가 시작될 때 InnoDB는 ReadView를 만든다. 구성 요소는 네 가지다.

```
m_ids        : ReadView 생성 순간 활성(미커밋) 트랜잭션 ID 목록
m_up_limit_id: m_ids 의 최솟값 — 이보다 작으면 무조건 커밋됨
m_low_limit_id: 다음에 발급될 트랜잭션 ID — 이상이면 미래 트랜잭션
m_creator_trx_id: 자기 자신
```

레코드(또는 undo 버전)의 `DB_TRX_ID = T`에 대한 가시성 판정:

1. `T == m_creator_trx_id` → 내가 바꾼 것, 보임
2. `T < m_up_limit_id` → ReadView 생성 전 커밋, 보임
3. `T >= m_low_limit_id` → 미래 트랜잭션, 안 보임
4. `m_up_limit_id <= T < m_low_limit_id` → `m_ids`에 있으면(당시 활성) 안 보임, 없으면(이미 커밋) 보임

안 보이면 `DB_ROLL_PTR`를 따라 undo 체인을 거슬러 올라가며 보이는 버전을 찾는다. 버전 체인이 길수록 단순 SELECT 한 건의 비용이 커진다 — 이것이 "오래된 스냅샷을 쥐는 세션이 있으면 전체 읽기가 느려진다"의 메커니즘이다.

## 3. REPEATABLE READ vs READ COMMITTED — ReadView 생성 시점

두 격리수준의 유일한 본질적 차이는 ReadView 수명이다.

- **REPEATABLE READ(기본)**: 트랜잭션의 첫 일관된 읽기에서 ReadView 1개 생성, 트랜잭션 끝까지 재사용
- **READ COMMITTED**: **모든 SELECT 문마다** 새 ReadView 생성

실험으로 검증한다.

```sql
-- 세션 A
START TRANSACTION;
SELECT balance FROM account WHERE id = 1;  -- 1000, ReadView 생성

-- 세션 B
UPDATE account SET balance = 2000 WHERE id = 1; COMMIT;

-- 세션 A (REPEATABLE READ)
SELECT balance FROM account WHERE id = 1;  -- 여전히 1000 (undo 체인 재구성)
-- 세션 A 가 READ COMMITTED 였다면 2000
```

운영 관점의 trade-off: READ COMMITTED는 ReadView가 문장 단위로 짧게 살기 때문에 purge가 빨리 진행되고 HLL이 낮게 유지된다. 대신 binlog는 ROW 포맷이 강제된다(STATEMENT 포맷의 정합성 보장 불가). 갭 락도 대부분 사라져 동시성이 올라가지만 phantom이 허용된다. 대량 배치와 OLTP가 섞인 시스템에서 장기 스냅샷 문제가 반복되면 READ COMMITTED 전환이 실질적 해법인 경우가 많다.

주의 한 가지: REPEATABLE READ에서도 **UPDATE/DELETE는 현재 버전(current read)을 본다**. 스냅샷으로는 안 보이던 행이 UPDATE 대상이 될 수 있고, 갱신 후에는 그 행이 SELECT에도 보이게 된다(자기 변경 가시성). 이 "스냅샷과 현재 읽기의 혼합"이 REPEATABLE READ에서 lost update 방어를 위해 `SELECT ... FOR UPDATE`가 필요한 이유다.

## 4. Purge 서브시스템 — 죽은 버전의 수거

커밋된 UPDATE undo는 곷바로 지울 수 없다. 그 버전을 볼 수 있는 ReadView가 남아 있을 수 있기 때문이다. InnoDB는 커밋된 트랜잭션의 undo를 **history list**라는 연결 리스트에 매단다. Purge 스레드는 "현재 살아있는 가장 오래된 ReadView"보다 오래된 undo부터 차례로 다음 작업을 수행한다.

1. delete-marked 레코드의 물리 삭제 (InnoDB의 DELETE는 우선 마킹만 한다)
2. undo log 페이지 회수
3. 보조 인덱스의 구버전 엔트리 제거

관련 파라미터:

```ini
innodb_purge_threads = 4            # 기본 4 (8.0.27+)
innodb_purge_batch_size = 300       # 배치당 undo 페이지 수
innodb_max_purge_lag = 0            # 0=무제한. >0 이면 DML 에 인위적 지연 부여
innodb_max_purge_lag_delay = 0      # 위 지연의 상한(µs)
```

`innodb_max_purge_lag`는 HLL이 임계치를 넘으면 DML에 딜레이를 줘서 purge가 따라잡게 하는 장치지만, 응답시간 변동을 만들기 때문에 기본값 0(비활성)이 보통 맞다. 근본 원인(장기 트랜잭션)을 잡는 것이 우선이다.

## 5. History List Length — 가장 중요한 단일 지표

HLL은 "purge되지 못하고 쌓인 커밋된 undo 단위 수"다. 조회:

```sql
SHOW ENGINE INNODB STATUS\G
-- TRANSACTIONS 섹션
-- History list length 8743210   ← 위험 신호

-- 또는
SELECT count FROM information_schema.INNODB_METRICS
WHERE name = 'trx_rseg_history_len';
```

경험적 기준: 정상 부하에서 수천~수만이면 평온, **수백만 단위로 단조 증가**하면 어떤 ReadView가 purge를 막고 있는 것이다. 범인 추적:

```sql
SELECT trx_id, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_sec,
       trx_mysql_thread_id, trx_query
FROM information_schema.INNODB_TRX
ORDER BY trx_started ASC LIMIT 5;
```

전형적 범인은 (1) 커밋을 잊은 대화형 세션(`autocommit=0` + 방치), (2) mysqldump `--single-transaction` 장시간 실행, (3) 애플리케이션의 커넥션 누수로 트랜잭션이 열린 채 풀에 반환된 경우다. (3)은 HikariCP `leakDetectionThreshold`와 MySQL `wait_timeout`으로 이중 방어한다.

HLL 폭증의 실제 비용은 세 가지로 나타난다. 첫째, 모든 일관된 읽기가 긴 undo 체인을 타며 느려진다(특히 핫 로우). 둘째, undo tablespace가 비대해진다. 셋째, purge가 밀린 delete-mark 레코드 때문에 인덱스 스캔이 "보이지 않는 행"을 건너뛰느라 느려진다 — `SELECT COUNT(*)`가 행 수에 비해 비정상적으로 느릴 때의 흔한 원인.

## 6. Undo Tablespace 운영 — 8.0의 개선

5.7까지 undo는 기본적으로 시스템 테이블스페이스(ibdata1)에 살았고, 한 번 커지면 영원히 줄지 않았다. 8.0은 undo를 독립 테이블스페이스 2개(`undo_001`, `undo_002`)로 분리하고 온라인 truncate를 지원한다.

```ini
innodb_undo_log_truncate = ON        # 기본 ON
innodb_max_undo_log_size = 1G        # 이 크기 초과 시 truncate 후보
```

truncate는 해당 undo tablespace를 비활성화하고 다른 쪽이 받는 동안 파일을 재생성하는 방식이라, undo tablespace가 최소 2개 있어야 동작한다. 수동 추가/삭제도 가능하다.

```sql
CREATE UNDO TABLESPACE undo_003 ADD DATAFILE 'undo_003.ibu';
ALTER UNDO TABLESPACE undo_003 SET INACTIVE;  -- 비우고
DROP UNDO TABLESPACE undo_003;
```

대량 배치(수억 행 UPDATE)가 예정되어 있다면 undo tablespace를 미리 늘려두고, 배치를 청크로 쪼개 커밋 주기를 짧게 가져가는 것이 undo 비대와 HLL 양쪽을 막는 정석이다.

```sql
-- 청크 커밋 패턴
REPEAT
	DELETE FROM event_log WHERE created_at < '2025-01-01' LIMIT 10000;
	-- 각 반복 커밋 → undo 가 history list 에 잠깐만 머물
UNTIL ROW_COUNT() = 0 END REPEAT;
```

## 7. 버전 체인과 보조 인덱스 — covering index 의 함정

보조 인덱스 레코드에는 `DB_TRX_ID`/`DB_ROLL_PTR`가 없다. 대신 페이지 단위 `PAGE_MAX_TRX_ID`만 있다. 그래서 보조 인덱스만으로 가시성을 판단할 수 없는 경우, InnoDB는 클러스터드 인덱스로 가서 버전을 재구성한다. 결과적으로:

- 인덱스 페이지의 `PAGE_MAX_TRX_ID`가 ReadView보다 오래됐으면 → 커버링 인덱스 읽기로 끝 (빠름)
- 페이지가 최근에 변경됐으면 → covering이어도 **클러스터드 인덱스 룩업 + undo 체인 순회** 발생

쓰기가 활발한 테이블에서 "EXPLAIN은 Using index인데 실제로는 느린" 현상의 한 원인이다. 또한 보조 인덱스 컬럼이 갱신되면 old 엔트리는 delete-mark, new 엔트리는 insert로 처리되어 purge 전까지 인덱스에 두 엔트리가 공존한다. 인덱스가 많은 테이블의 UPDATE 비용과 purge 부하는 인덱스 수에 비례해 커진다.

## 8. 모니터링 대시보드 구성

운영에서 상시 추적할 메트릭과 임계 기준:

| 메트릭 | 소스 | 경보 기준(예) |
|---|---|---|
| trx_rseg_history_len | INNODB_METRICS | 1M 초과 10분 지속 |
| 최장 트랜잭션 age | INNODB_TRX | 300s 초과 |
| undo tablespace 크기 | innodb_undo_tablespaces 파일 | 전일 대비 2배 |
| purge lag (trx_purge_*) | INNODB_METRICS | stop/resume 빈발 |

Prometheus mysqld_exporter는 `mysql_global_status_innodb_history_list_length`(percona 계열) 또는 information_schema 커스텀 쿼리로 수집한다. HLL 경보 발생 시 대응 순서: INNODB_TRX로 장기 트랜잭션 식별 → 애플리케이션 쪽이면 해당 커넥션 kill(`KILL <thread_id>`) → 재발 방지로 `max_execution_time`(SELECT 한정), 커넥션 풀 `maxLifetime`, 배치 청크화 적용.

```sql
-- 30분 넘은 트랜잭션 자동 킬 (이벤트 스케줄러 예시 — 운영 합의 후 적용)
KILL (SELECT trx_mysql_thread_id FROM information_schema.INNODB_TRX
      WHERE TIMESTAMPDIFF(MINUTE, trx_started, NOW()) > 30 LIMIT 1);
```

## 9. Oracle MVCC와의 구조 비교

같은 MVCC라도 구현 철학이 다르며, 운영 증상도 다르게 나타난다.

| 항목 | InnoDB | Oracle |
|---|---|---|
| 버전 저장 | undo log (행 단위 체인) | undo segment (블록 단위 재구성) |
| 읽기 재구성 | roll ptr 체인 순회 | 블록의 과거 이미지 생성 (CR block) |
| 보존 한계 초과 시 | 장기 트랜잭션이 purge 차단 (에러 없음, 누적) | ORA-01555 snapshot too old (읽는 쪽 에러) |
| 격리 기본값 | REPEATABLE READ | READ COMMITTED |
| 정리 주체 | purge thread | SMON + undo retention 자동 조정 |

핵심 대비는 실패 모드다. Oracle은 오래된 스냅샷을 **읽는 쪽이 에러(ORA-01555)로 즉시** 감지하지만, InnoDB는 에러 없이 시스템 전체가 서서히 무거워진다. InnoDB 운영에서 HLL 모니터링이 선택이 아니라 필수인 이유가 여기에 있다.

마지막으로 레플리케이션과의 상호작용 한 가지. 읽기 부하 분산용 레플리카에서 분석성 장기 SELECT 를 돌리는 패턴은 흔한데, 레플리카의 장기 ReadView 는 레플리카 자신의 purge 만 막는 것이 아니라 `replica_preserve_commit_order`/병렬 적용 설정에 따라 SQL 스레드의 적용 지연(lag)으로도 전이된다. 적용이 밀리는 동안 레플리카의 HLL 은 소스보다 훨씬 빠르게 자랄 수 있다. "소스는 평온한데 레플리카만 undo 가 비대해지는" 증상의 원인이 대부분 이것이며, 대응은 분석 쿼리 전용 레플리카의 분리(지연 허용)와 일반 읽기 레플리카의 쿼리 타임아웃(`max_execution_time`) 강제다. 8.0.22+ 라면 `SELECT /*+ MAX_EXECUTION_TIME(30000) */` 힌트로 쿼리 단위 적용도 가능하다.

## 참고

- MySQL 8.0 Reference Manual, "InnoDB Multi-Versioning" — https://dev.mysql.com/doc/refman/8.0/en/innodb-multi-versioning.html
- MySQL 8.0 Reference Manual, "Undo Logs" / "Purge Configuration" — https://dev.mysql.com/doc/refman/8.0/en/innodb-undo-logs.html
- Jeremy Cole, "InnoDB internals" 시리즈 — https://blog.jcole.us/innodb/
- Percona Blog, "MySQL History List Length" — https://www.percona.com/blog/
- 『Real MySQL 8.0』 1권 4장 (아키텍처), 5장 (트랜잭션과 잠금)
