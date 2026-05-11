Notion 원본: https://www.notion.so/35d5a06fd6d381aa8ebeef743bef7583

# PostgreSQL Logical Replication Publication Subscription Failover 전략

> 2026-05-11 신규 주제 · 확장 대상: PostgreSQL

## 학습 목표

- PostgreSQL 의 *physical replication* 과 *logical replication* 이 어떤 레이어에서 데이터를 옮기는지 비교하고, logical 의 핵심 객체 `PUBLICATION` / `SUBSCRIPTION` / `REPLICATION SLOT` 의 역할을 본다
- `wal_level=logical` 이 WAL 에 추가로 기록하는 정보(`logical decoding output plugin` `pgoutput` 의 동작), DDL 이 logical 로 전송되지 않는 이유와 16/17 의 변화
- subscriber 가 *initial copy → streaming* 단계를 거치며 일관성을 만드는 흐름, replica identity, conflict 발생 시 동작, REPLICA IDENTITY FULL 의 비용
- production failover 시나리오 — primary down → logical standby 승격, slot 보존, application 의 dual-write/cutover, 17 의 *failover slot* 기능까지

## 1. physical vs logical replication

| 측면 | physical (streaming) | logical |
|---|---|---|
| 단위 | WAL 바이트 그대로 | 행 단위(INSERT/UPDATE/DELETE)로 디코딩 |
| 버전 호환 | major version 동일해야 함 | major version 다를 수 있음 |
| 일부 테이블만? | 불가 (클러스터 전체) | 가능 (publication 단위) |
| schema 변경 | 자동 적용 (WAL 에 포함) | *별도 적용 필요* (DDL 전파 X) |
| sequence | 자동 동기화 | 자동 X (16 부터 일부 가능) |
| replica 쓰기 | read-only | read-write 가능 |

logical 은 *데이터 mesh* / *cross-version 마이그레이션* / *partition routing* 같은 시나리오에 강하고, physical 은 *재해 복구 / read replica* 에 강하다.

## 2. wal_level=logical 의 의미

```
ALTER SYSTEM SET wal_level = 'logical';
-- 재시작 필요
```

`logical` 로 두면 WAL 에 추가 정보가 들어간다.

- `INSERT` / `UPDATE` / `DELETE` 의 *대상 행 이미지*. UPDATE 는 *변경 전 키* 를, DELETE 는 *삭제 행 식별 정보* 를 같이 기록.
- transaction begin / commit 메타데이터.

이 추가 기록 때문에 WAL 크기가 30~50% 늘어난다. 디스크와 WAL archive 비용 증가는 logical 도입의 첫 비용.

`max_wal_senders`, `max_replication_slots` 를 충분히(노드 수 + 여유 2) 잡아 두어야 한다. 디폴트 10 이지만 다중 subscriber 환경에선 부족.

## 3. PUBLICATION / SUBSCRIPTION / REPLICATION SLOT

```sql
-- primary
CREATE PUBLICATION pub_users FOR TABLE users, accounts;

-- subscriber
CREATE SUBSCRIPTION sub_users
  CONNECTION 'host=primary dbname=app user=replicator'
  PUBLICATION pub_users;
```

- **PUBLICATION**: 어떤 테이블의 변경을 내보낼지를 *primary* 에 정의. row filter 와 column list (PG15+) 가능.
- **SUBSCRIPTION**: 어떤 publication 을 가져올지를 *subscriber* 에 정의. 내부적으로 *logical replication slot* 을 자동 생성한다 (`create_slot=true` 기본).
- **REPLICATION SLOT**: primary 에서 *consumer 별로 어디까지 보냈는지* 의 LSN 을 보존. slot 이 살아 있으면 그 LSN 이전 WAL 은 *삭제되지 않음*.

slot 이 죽은 subscriber 를 가리킨 채 방치되면 *primary 의 WAL 이 무한 누적* 되어 디스크가 폭주한다. *모니터링 1순위*.

```sql
SELECT slot_name,
       confirmed_flush_lsn,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag_bytes,
       active
FROM pg_replication_slots;
```

## 4. pgoutput — 표준 logical decoding plugin

logical decoding 은 *plugin* 방식이다. 9.4 부터 `test_decoding`, `wal2json`, `pgoutput` 이 존재. 10 부터 *기본 plugin* 이 `pgoutput`.

`pgoutput` 의 출력은 *binary protocol* 이라 가벼우며, replication protocol 의 `START_REPLICATION SLOT ... LOGICAL` 명령으로 시작한다.

서버에서 발생한 transaction 의 처리 순서:

1. `BEGIN` 메시지
2. 각 row 변경에 대해 `INSERT`/`UPDATE`/`DELETE` 메시지
3. `COMMIT` 메시지

`pgoutput` 의 옵션:

- `proto_version`: 1, 2(streaming), 3(2PC), 4(in-progress streaming, PG14+)
- `streaming`: 대용량 트랜잭션을 commit 전에 스트리밍할지
- `publication_names`: 받을 publication 들

`streaming=true` 가 production 에서 중요하다. PG13 까지 큰 트랜잭션은 *서버 메모리에 buffering 후 commit 시점 한 번에* 보냈는데, 14 부터 *commit 전에* in-progress 트랜잭션의 변경을 스트리밍해 OOM 위험과 latency 를 동시에 줄였다.

## 5. initial copy → streaming

`CREATE SUBSCRIPTION` 이 만들어지면 subscriber 가 다음을 수행한다.

1. *snapshot 잡기* — primary 의 export 된 snapshot 으로부터 publication 테이블들을 `COPY` 받기 (initial sync)
2. snapshot 시작 시점의 LSN 이후의 변경을 *replication slot* 으로 받으며 streaming 시작

initial copy 가 끝나기 전엔 subscriber 측 테이블에 직접 쓰지 말 것. conflict 가 발생하면 subscription 이 *error 상태* 로 정지한다.

`copy_data=false` 옵션을 주면 initial copy 를 건너뛴다. 이미 dump/restore 로 데이터를 옮긴 됆 *streaming 만* 시작하고 싶을 때.

```sql
CREATE SUBSCRIPTION sub_users
  CONNECTION '...'
  PUBLICATION pub_users
  WITH (copy_data = false, create_slot = true, enabled = true);
```

## 6. REPLICA IDENTITY 의 영향

UPDATE / DELETE 가 logical 로 옮겨질 때 *어떤 컴럼으로 행을 식별할지* 를 결정하는 게 `REPLICA IDENTITY` 다.

- `DEFAULT` (기본): primary key. PK 가 있어야 함.
- `USING INDEX idx_x`: 특정 unique index
- `FULL`: 모든 컴럼을 키로 사용 — PK 없는 테이블 대안. UPDATE / DELETE 시 *모든 컴럼* 이 WAL 에 기록되어 비용이 크다.
- `NOTHING`: UPDATE/DELETE 가 logical 로 옮겨지지 않음 (INSERT 만).

PK 가 없고 FULL 로 두면 WAL 양이 폭증한다. 운영 테이블에 logical 을 도입한다면 PK 추가가 사실상 *전제조건*.

```sql
ALTER TABLE legacy_events REPLICA IDENTITY USING INDEX legacy_events_seq_uniq;
```

## 7. conflict 처리

logical 에서 충돌이 생기는 시나리오:

- subscriber 측에서 *별도 INSERT* 가 동일 PK 로 들어옴 → primary 의 INSERT 가 unique violation
- subscriber 측에서 행을 *미리 삭제* → primary 의 UPDATE 가 *no rows* (이건 logical 이 무시 — *오류 X*)

PG16 이전엔 conflict 가 발생하면 subscription 이 즉시 정지. DBA 가 직접 데이터를 정정한 됆 `ALTER SUBSCRIPTION ... SKIP (lsn = ...)` 로 다음 LSN 으로 점프.

PG16 부터 `disable_on_error = true` 옵션이 추가되어 *자동으로 SUBSCRIPTION 을 disable*.

## 8. failover — primary 가 죽으면 subscriber 는?

핵심 문제: logical replication slot 은 *primary 의 메모리/디스크* 에 존재한다. primary 가 죽고 physical standby 가 promote 되면 *그 slot 이 함께 따라가지 않는다* (PG16 이전).

**PG17 의 *failover slot***:

- `CREATE SUBSCRIPTION ... WITH (failover = true)` 로 만들면 primary 가 slot 정보를 *physical standby 에도 동기화*.
- failover 후 새 primary 에서 그 slot 이 살아 있어 subscriber 는 *그 자리에서* 이어서 받을 수 있음.
- 동기화 메커니즘: walsender 가 slot 의 LSN 을 정기적으로 standby 에 broadcast. `sync_replication_slots = on` 필요.

운영 절차 (PG17 기준):

1. primary 의 모든 logical slot 을 `failover=true` 로 생성
2. `synchronized_standby_slots` 에 동기화할 standby 의 application name 명시
3. primary 의 `standby_slot_names` 에 standby 이름 명시
4. failover 시 standby promote → slot 들이 이미 거기 있음 → subscriber 가 *동일 connection string* 로 재접속

## 9. application 측 cutover 패턴

PG17 미만 환경에서 logical 으로 마이그레이션 / 새 primary 로 cutover 할 때:

```
[old primary] ─pub→ [new primary 1]    (logical 으로 데이터 sync)
   ↑ 앱이 dual-write
[old primary] ←app ; [new primary] ←app
```

1. new primary 가 충분히 catch up 했는지 `pg_wal_lsn_diff` 로 확인 (< 1 MB)
2. 짧은 down-time 또는 read-only 모드로 old primary 진입
3. 잔여 WAL 이 모두 옮겨졌는지 확인
4. 앱의 connection string 을 new primary 로 교체 (atomic switch — feature flag / DNS / pgbouncer 풀 재구성)
5. dual-write 종료, old primary 폐기 또는 demote

production 의 가장 까다로운 부분은 *sequence* 다. logical 은 sequence value 를 자동 동기화하지 않는다(PG10~15). 16 부터 일부 지원되었으나 17 에서 본격적.

## 10. 흔한 함정 모음

- *DDL 자동 전파 없음* — schema 변경은 양쪽에 별도 적용해야 하며, `--check-only` 같은 dry-run 으로 호환성 미리 검증. flyway / liquibase 같은 도구로 양쪽 동시 적용 자동화.
- *TOAST 컴럼 의 unchanged TOAST*: UPDATE 시 TOAST 컴럼이 변하지 않으면 WAL 에 *value 없이 reference 만* 들어가, subscriber 측이 이전 값을 그대로 유지하기로 한다. 이는 정상 동작.
- *prepared transaction (2PC)*: PG14 + proto_version=3 부터 지원.
- *replication slot 의 catalog_xmin 누적*: 오래 멈춰 있는 slot 이 있으면 VACUUM 이 *그 LSN 까지 dead row* 를 청소 못 한다. table bloat 의 흔한 원인.
- *publication row filter* — PG15+. row filter 는 *publisher 측에서* 평가. subscriber 와 PK 가 다른 dataset 을 받게 되면 conflict 가능.

## 참고

- PostgreSQL Documentation — Logical Replication: https://www.postgresql.org/docs/current/logical-replication.html
- PG17 Release Notes — Failover Slots
- *PostgreSQL Replication* 2nd Ed. (Hans-Jürgen Schönig)
- 2ndQuadrant / EDB Tech Blog — *Logical Replication Internals*
- PG Conf EU 2024 — *Logical Replication, the Hard Parts* (Andres Freund)
- pgoutput protocol — src/backend/replication/pgoutput/pgoutput.c
