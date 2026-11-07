Notion 원본: https://www.notion.so/4115a06fd6d38164a5c9e9f495c6adee

# MySQL Online DDL: INPLACE·INSTANT 알고리즘과 gh-ost·pt-osc 무중단 스키마 변경

> 2026-11-07 신규 주제 · 확장 대상: Oracle / MySQL / ORM

## 학습 목표

- ALGORITHM(COPY / INPLACE / INSTANT)과 LOCK(NONE / SHARED / EXCLUSIVE)의 조합 규칙을 구분한다
- Metadata Lock 대기가 서비스 전체를 막는 시나리오를 재현하고 방어한다
- Online DDL 의 row log 와 `innodb_online_alter_log_max_size` 한계를 이해한다
- gh-ost / pt-online-schema-change 의 동작 원리와 트레이드오프를 비교한다
- Flyway/Liquibase 마이그레이션에 안전한 DDL 절차를 적용한다

## 1. 왜 ALTER TABLE 이 위험한가

MySQL 5.5 까지 `ALTER TABLE` 은 새 구조의 테이블을 만들어 전체 행을 복사하는 COPY 방식이었고, 그동안 쓰기가 차단되었다. 5.6 부터 InnoDB Online DDL 이 도입되어 많은 변경을 동시 DML 허용 하에 수행할 수 있고, 8.0.12 부터 INSTANT 알고리즘이 메타데이터만 바꿔 테이블 크기와 무관하게 즉시 끝난다. 그러나 "온라인"이라는 이름과 달리 **시작과 끝에 Metadata Lock(MDL) 배타 락이 필요**하며, 이 락 대기가 운영 장애의 가장 흔한 원인이다.

## 2. ALGORITHM 과 LOCK 명시

```sql
-- 요구 조건을 명시하면 불가능할 때 즉시 에러로 실패 (조용히 COPY 로 후퇴하지 않음)
ALTER TABLE orders ADD COLUMN memo VARCHAR(200) NULL, ALGORITHM=INSTANT;
ALTER TABLE orders ADD INDEX idx_user_created (user_id, created_at), ALGORITHM=INPLACE, LOCK=NONE;
```

두 절을 쓰지 않으면 MySQL 이 가능한 최선을 고르지만, 명시하면 제약을 만족하지 못할 때 `ERROR 1845/1846` 으로 거부되어 의도치 않은 테이블 전체 락을 예방한다. 운영 DDL 은 항상 명시하는 것을 규칙으로 삼는다.

| 변경 | 알고리즘(8.0) | 동시 DML | 테이블 재구성 |
|---|---|---|---|
| 컬럼 마지막에 추가(NULL/기본값) | INSTANT | 허용 | 없음 |
| 8.0.29+ 임의 위치 컬럼 추가 / DROP COLUMN | INSTANT | 허용 | 없음 |
| 보조 인덱스 추가/삭제 | INPLACE | 허용 | 인덱스만 빌드 |
| 컬럼 기본값 변경, 컬럼 이름 변경 | INSTANT / INPLACE | 허용 | 없음 |
| 컬럼 타입 변경(VARCHAR 확장은 길이 바이트 경계 내만) | INPLACE(제한) / COPY | 제한 | 있음 |
| PRIMARY KEY 변경, 문자셋 변경 | COPY / INPLACE(rebuild) | 제한/차단 | 전체 재구성 |
| NOT NULL 로 변경 | INPLACE(rebuild) | 허용 | 전체 재구성 |

INSTANT 는 데이터 파일에 "행 버전" 메타를 두어 오래된 행을 읽을 때 기본값으로 보완한다. 8.0.29 부터 `INSTANT` 컬럼 변경 횟수는 테이블당 최대 64회로 제한되며(행 버전 상한), 한도에 가까워지면 `ALTER TABLE ... ENGINE=InnoDB` 같은 재구성으로 초기화한다. `information_schema.INNODB_TABLES.TOTAL_ROW_VERSIONS` 로 현재 버전 수를 확인한다.

## 3. INPLACE 의 단계와 row log

INPLACE DDL 은 세 단계다. 준비(prepare): MDL 업그레이드 가능 락으로 구조 확인. 실행(execute): MDL 을 공유로 낮춰 DML 을 허용하면서 인덱스를 빌드하고, 그 사이 발생한 변경은 **online alter log(row log)** 에 임시 기록한다. 커밋(commit): 배타 MDL 을 얻어 로그를 최종 적용하고 딕셔너리를 갱신한다.

row log 크기는 `innodb_online_alter_log_max_size`(기본 128MB)로 제한되며, 초과하면 `DB_ONLINE_LOG_TOO_BIG` 에러로 DDL 이 롤백된다. 쓰기가 많은 테이블에서 큰 인덱스를 빌드하면 수 시간 걸리는 동안 로그가 넘칠 수 있다. 이 경우 변수를 늘리거나(`SET GLOBAL innodb_online_alter_log_max_size = 1073741824`) 트래픽이 적은 시간대를 택한다. 또한 INPLACE 빌드 중 I/O·CPU 부하와 복제 지연이 올라간다. 복제는 소스에서 DDL 이 끝난 뒤에야 binlog 에 기록되므로 **레플리카는 DDL 을 처음부터 다시 실행**하며 그 시간만큼 지연이 발생한다(복제 스레드가 DDL 을 직렬 실행).

## 4. Metadata Lock: 진짜 병목

```sql
-- 세션 A: 오래 열린 트랜잭션 (SELECT 후 COMMIT 안 함)
START TRANSACTION; SELECT * FROM orders WHERE id = 1;

-- 세션 B: DDL → 세션 A 의 MDL(shared)이 풀릴 때까지 대기
ALTER TABLE orders ADD COLUMN memo VARCHAR(200), ALGORITHM=INSTANT;

-- 세션 C~Z: 이후 모든 쿼리가 B 뒤에서 줄을 선다 (MDL 대기열은 FIFO, 배타 요청이 앞서면 뒤 SELECT 도 대기)
SELECT * FROM orders WHERE id = 2;   -- "Waiting for table metadata lock"
```

DDL 이 MDL 배타 락을 기다리는 동안 이후 도착한 일반 SELECT 까지 막힌다. 그래서 INSTANT 라도 장수 트랜잭션이 있으면 서비스가 정지한다. 진단은 `performance_schema.metadata_locks` 와 `SHOW PROCESSLIST` 의 `Waiting for table metadata lock` 상태로 하고, 원인 트랜잭션은 `information_schema.INNODB_TRX` 의 `trx_started` 로 찾는다.

```sql
SELECT ml.OBJECT_NAME, ml.LOCK_TYPE, ml.LOCK_STATUS, t.PROCESSLIST_ID, t.PROCESSLIST_INFO
FROM performance_schema.metadata_locks ml
JOIN performance_schema.threads t ON t.THREAD_ID = ml.OWNER_THREAD_ID
WHERE ml.OBJECT_NAME = 'orders';
```

방어책은 `SET SESSION lock_wait_timeout = 5;` 를 DDL 세션에 설정해 짧게 실패시키고 재시도하는 것이다(기본 1년이라 사실상 무한 대기). 스크립트는 "5초 타임아웃 → 1~2초 대기 → 재시도" 루프로 감싼다. 애플리케이션 쪽에서는 트랜잭션 최대 길이를 제한(`max_execution_time`, 커넥션 풀 `maxLifetime` 및 쿼리 타임아웃)한다.

## 5. gh-ost 와 pt-online-schema-change

내장 Online DDL 이 부적합한 경우(PK 변경, 타입 변경, 복제 지연 통제 필요)에는 외부 도구를 쓴다. 두 도구 모두 "그림자 테이블에 새 구조를 만들고 데이터를 청크로 복사하며 변경분을 동기화한 뒤 `RENAME` 으로 교체"한다는 틀은 같다. 차이는 변경분 포착 방법이다.

| 항목 | pt-online-schema-change | gh-ost |
|---|---|---|
| 변경 포착 | 원본 테이블의 **트리거**(INSERT/UPDATE/DELETE) | **binlog 스트림** 읽기(레플리카 또는 소스) |
| 소스 부하 | 트리거로 쓰기 증폭 | 낮음(트리거 없음) |
| 일시정지/제어 | 제한적 | 소켓/파일로 throttle, pause, cut-over 지연 가능 |
| 요구 사항 | 트리거 허용 | `binlog_format=ROW`, 레플리카 사용 시 `log_replica_updates` |
| FK 지원 | 일부 지원 | 제한적 |

```bash
gh-ost \
  --host=replica01 --allow-on-master \
  --database=shop --table=orders \
  --alter="ADD COLUMN memo VARCHAR(200) NULL, ADD INDEX idx_memo (memo)" \
  --chunk-size=1000 --max-lag-millis=1500 --max-load=Threads_running=40 \
  --critical-load=Threads_running=200 \
  --postpone-cut-over-flag-file=/tmp/ghost.postpone \
  --execute
```

`--max-lag-millis` 는 레플리카 지연이 넘으면 복사를 일시정지하고, `--postpone-cut-over-flag-file` 은 파일을 지울 때까지 마지막 교체를 미룬다(새벽 점검 시간에 맞춰 교체). cut-over 는 원본에 짧은 락을 걸고 `RENAME` 하는 원자적 단계이며 여기서도 MDL 대기를 겪을 수 있다. 완료 후 `_orders_del` 테이블을 남겨두므로 롤백이 필요하면 되돌리고 확인 후 삭제한다.

## 6. 마이그레이션 도구(Flyway/Liquibase) 적용 지침

앱 기동 시 자동 마이그레이션은 DDL 을 트래픽이 있는 상태로 실행시키므로 위험하다. 권장 절차는 다음과 같다. 대형 테이블 DDL 은 배포 파이프라인과 분리된 별도 잡으로 실행하고, Flyway 에서는 `V__` 스크립트를 비워 두거나 이미 적용된 상태를 검증하는 `validate` 만 남긴다. 컬럼 삭제는 expand/contract 패턴으로 진행한다 — ① 새 컬럼 추가(INSTANT) ② 애플리케이션이 양쪽에 쓰기(이중 기록) ③ 백필 ④ 읽기 전환 ⑤ 구 컬럼 쓰기 중단 ⑥ 일정 기간 후 DROP. 이름 변경 같은 파괴적 변경은 한 번에 하지 않는다. 롤링 배포 중에는 신/구 버전 애플리케이션이 동시에 존재하므로, 모든 스키마 변경은 구 버전 코드와도 호환되어야 한다.

## 7. 점검 절차

DDL 직전에 ① `SHOW PROCESSLIST` 와 `INNODB_TRX` 로 장수 트랜잭션 확인 ② 테이블 크기와 예상 소요 시간(`rows * 인덱스 수`) 산정 ③ 레플리카 지연 임계 설정 ④ 롤백 계획(INSTANT 는 `DROP COLUMN` 으로, gh-ost 는 `_del` 테이블로) 마련 ⑤ 스테이징에서 동일 크기 데이터로 리허설을 수행한다. 실행 중에는 `performance_schema.events_stages_current` 로 진행률을 볼 수 있다(`stage/innodb/alter table (read PK and internal sort)` 등). 실패 시 남은 `#sql-*` 임시 파일과 고아 그림자 테이블이 없는지 확인해 정리한다.

## 참고

- MySQL 8.0 Reference Manual — InnoDB Online DDL Operations, Online DDL Space Requirements
- MySQL 8.0 Reference Manual — Metadata Locking
- gh-ost GitHub (github/gh-ost) docs/ — cut-over, throttle
- Percona Toolkit pt-online-schema-change 문서
