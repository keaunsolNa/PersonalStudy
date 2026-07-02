Notion 원본: https://www.notion.so/3915a06fd6d381778adae9068937e4d9

# Oracle Redo/Undo 로그와 MVCC Consistent Read 및 SCN 기반 일관성

> 2026-07-02 신규 주제 · 확장 대상: Oracle(파티셔닝·CBO 옵티마이저 학습됨)

## 학습 목표

- Redo와 Undo가 각각 무엇을 보장하며 물리적으로 어디에 기록되는지 구분한다
- SCN이 트랜잭션 순서와 일관성 읽기의 기준점으로 쓰이는 방식을 추적한다
- Undo 세그먼트로 과거 블록 버전을 재구성하는 consistent read를 재현한다
- ORA-01555 snapshot too old의 원인과 Undo 보존 정책을 진단한다

## 1. Redo와 Undo - 방향이 반대인 두 로그

Redo는 변경을 다시 적용하기 위한 로그로 모든 변경을 물리적으로 어떻게 재현할지 기록한다. 목적은 내구성과 crash recovery다. Undo는 변경 전 이전 이미지(before image)를 기록한다. 목적은 트랜잭션 롤백과 읽기 일관성이다. 둘의 방향은 정반대다. Undo 자체가 데이터베이스 블록이므로 crash 후 복구를 위해 Undo 변경도 Redo에 기록된다.

## 2. 쓰기 경로 - 커밋이 빠른 이유

```
1. 데이터 블록의 이전 이미지를 Undo 세그먼트에 기록
2. 데이터 블록을 버퍼 캐시에서 수정 (dirty)
3. 변경을 Redo Log Buffer에 기록
4. COMMIT 시: Redo Log Buffer를 온라인 Redo 로그 파일에 flush (LGWR)
5. COMMIT 완료 — 데이터 파일은 아직 안 바뀌었을 수 있음
```

커밋이 빠른 이유는 4번에서 데이터 블록이 아니라 Redo만 디스크에 내려가면 커밋이 확정되기 때문이다(fast commit). 실제 데이터 파일 반영은 나중에 비동기로 일어난다.

## 3. SCN - 시스템 변경 번호

SCN(System Change Number)은 단조 증가하는 논리 시계다. 커밋마다 증가하고 그 커밋에 각인된다. 쿼리가 시작되면 그 시점의 SCN이 스냅샷 SCN으로 고정되고, 이후 이 쿼리는 스냅샷 SCN보다 이후에 커밋된 변경은 못 본 것으로 취급한다. 이것이 락 없이 일관된 읽기를 제공하는 원리다.

## 4. Consistent Read - 과거 버전 재구성

```
1. 블록 헤더 SCN(150)이 쿼리 스냅샷 SCN(100)보다 크다 → 너무 최신
2. Undo를 따라가 before image 적용
3. SCN<=100 시점 블록 버전을 메모리에 재구성 (CR 블록)
4. CR 블록으로 결과 생성
```

즉 원본 블록을 건드리지 않고 Undo를 역방향 적용해 과거 버전을 읽기 전용 복사본으로 재구성한다. 덕분에 읽기는 쓰기를, 쓰기도 읽기를 막지 않는다.

## 5. ORA-01555 - snapshot too old

Undo는 무한히 보존되지 않고 재사용 대상이 된다. 긴 쿼리가 과거 버전을 재구성하려는데 그 Undo가 이미 덮어쓰였다면 ORA-01555가 난다.

```sql
SHOW PARAMETER undo_retention;
ALTER SYSTEM SET undo_retention = 3600;
ALTER TABLESPACE undotbs1 RETENTION GUARANTEE;
```

원인은 긴 쿼리와 활발한 OLTP 공존, 작은 Undo 테이블스페이스, fetch-across-commit 안티패턴이다. RETENTION GUARANTEE를 켜면 보존은 보장되지만 공간 부족 시 DML이 실패할 수 있는 trade-off가 생긴다.

## 6. Redo와 성능 - 커밋 빈도의 비용

Redo는 커밋마다 LGWR가 동기 flush하므로 커밋 빈도가 성능에 직결된다. 행마다 커밋하는 루프는 log file sync 대기를 유발한다. `COMMIT WRITE BATCH NOWAIT` 같은 비동기 커밋은 대기를 줄이지만 crash 시 마지막 몇 커밋을 잃을 수 있어 로그성 데이터에서만 신중히 고려한다.

## 7. 다른 DB와의 대조

| 항목 | Oracle | PostgreSQL | MySQL InnoDB |
|---|---|---|---|
| 과거 버전 저장 | Undo 세그먼트 | 테이블 내 튜플 다중 버전 | Undo 로그 |
| 정리 방식 | Undo 재사용 | VACUUM | purge 스레드 |
| 대표 오류 | ORA-01555 | 테이블 bloat | history list length 증가 |

Oracle과 MySQL은 Undo를 별도로 두어 테이블을 깨끗하게 유지하는 반면, PostgreSQL은 과거 버전을 테이블 안에 남겨 VACUUM으로 청소하므로 bloat 관리가 별도 과제가 된다.

## 8. 지연 블록 정리(delayed block cleanout)

커밋 시점에 Oracle이 모든 변경 블록의 트랜잭션 정보를 즉시 정리하지는 않는다. 각 블록의 ITL(Interested Transaction List) 슬롯이 커밋 직후에도 active로 남을 수 있고, 이후 어떤 세션이 그 블록을 처음 읽을 때 트랜잭션 테이블을 조회해 이미 커밋됐음을 발견하고 그제서야 블록을 정리한다. 이 때문에 대량 DML 직후 첫 SELECT가 느리고 redo를 발생시키며 드문게 ORA-01555를 만나기도 한다.

## 9. 플래시백 - Undo를 기능으로 승격

```sql
SELECT * FROM orders AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE)
WHERE status = 'PENDING';

SELECT * FROM orders AS OF SCN 1234567;
```

Flashback Query는 consistent read를 사용자 지정 SCN으로 수행하는 것이다. 다만 조회 가능한 과거 범위는 undo 보존 시간에 제한되며 그 이상은 ORA-01555가 난다. 며칠 전 복구는 Flashback Data Archive 같은 별도 이력 기능이 필요하다.

## 10. 결론

Oracle의 읽기 일관성은 락이 아니라 Undo + SCN이라는 다중 버전 메커니즘 위에 서 있다. Redo가 내구성을, Undo가 롤백과 과거 버전 재구성을, SCN이 전순서와 스냅샷 기준을 담당한다. 이 셋을 이해하면 ORA-01555와 fast commit이 하나의 그림으로 이어진다.

## 참고

- Oracle Database Concepts — Multiversion Read Consistency
- Oracle Database Concepts — Transactions, Undo, Redo
- Thomas Kyte, "Expert Oracle Database Architecture"
- Oracle Support — ORA-01555 노트
