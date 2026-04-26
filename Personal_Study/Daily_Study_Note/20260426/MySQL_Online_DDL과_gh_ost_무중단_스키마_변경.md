Notion 원본: https://www.notion.so/34e5a06fd6d3817e9dbfe23f0247cf27

# MySQL Online DDL과 gh-ost 무중단 스키마 변경

> 2026-04-26 신규 주제 · 확장 대상: Oracle (DDL/Locking 학습됨), SQLD (DML/DDL 학습됨)

## 학습 목표

- MySQL 8 Online DDL의 ALGORITHM=INSTANT/INPLACE/COPY 단계와 lock 등급을 구분한다
- pt-online-schema-change(트리거 기반)와 gh-ost(binlog 기반)의 동작 모델 차이를 설명한다
- gh-ost cut-over의 atomic rename 트릭과 throttling 정책을 운영 시나리오로 적용한다
- 1억 행 테이블에 ADD COLUMN, ADD INDEX, MODIFY COLUMN을 무중단으로 적용한다

---

## 1. 왜 Online DDL이 필요한가

전통적인 ALTER TABLE은 metadata lock(MDL)을 잡고 새 테이블을 만든 뒤 데이터를 복사한다. 1억 행 테이블에서 이 작업은 수십 분~수 시간 걸리고, 그동안 모든 DML이 차단된다. 운영 서비스에는 받아들일 수 없는 시간이다. MySQL 5.6부터 도입된 Online DDL과, 외부 도구인 pt-osc / gh-ost는 이 문제를 다른 각도로 해결한다.

핵심 지표는 두 가지다.

- **다운타임**: ALTER 동안 read/write가 차단되는 시간. 이상적 0.
- **실행 시간**: 작업 완료까지 걸리는 wall-clock. 작을수록 좋지만 다운타임이 0이라면 길어도 무방.

Online DDL은 다운타임을 줄이고, gh-ost 류는 다운타임을 0에 가깝게 가져가면서 실행 시간 trade-off를 운영자가 제어할 수 있게 한다.

## 2. MySQL 8 Online DDL의 세 가지 ALGORITHM

`ALGORITHM` 옵션은 InnoDB가 ALTER를 수행하는 방식을 결정한다.

| ALGORITHM | 데이터 복사 | DML 허용 | 메타데이터 변경 |
|---|---|---|---|
| INSTANT | 없음 | 허용 (잠깐의 MDL 제외) | 메타만 변경 |
| INPLACE | 같은 tablespace에서 변경 | 대부분 허용 | tablespace 재구성 |
| COPY | 새 테이블로 전체 복사 | 차단 | 새 tablespace |

### 2.1 INSTANT

ADD COLUMN을 row format을 건드리지 않고 메타데이터만 바꿔 처리한다. MySQL 8.0.12에서 도입되었고, 8.0.29부터는 컬럼을 임의 위치에 추가하는 것까지 INSTANT가 된다. 실행 시간은 1초 미만이고 lock은 매우 짧은 MDL 뿐이다.

```sql
ALTER TABLE orders
  ADD COLUMN promo_code VARCHAR(20) DEFAULT NULL,
  ALGORITHM=INSTANT, LOCK=NONE;
```

INSTANT 적용 가능 조건은 좁다. 컬럼 추가만 가능하며, 추가된 컬럼은 row 끝에 논리적으로 누적된다(8.0.29 이상은 위치 자유지만 내부 ordinal은 누적). 누적이 64회를 넘으면 다음 INSTANT는 거절된다. 이 제한을 풀려면 `OPTIMIZE TABLE`로 row를 재기록해야 한다. NULL DEFAULT가 없는 NOT NULL 컬럼 추가는 INSTANT가 안 된다.

### 2.2 INPLACE

테이블 데이터를 그 자리에서 재구성한다. 인덱스 추가, 컬럼 NULL/NOT NULL 변경 일부, charset 변경 등이 INPLACE로 동작한다. 동시 DML은 임시 row log에 쌓이고, ALTER 끝 단계에서 row log를 새 구조에 적용한다. 적용 단계에서 잠깐 lock이 걸린다.

```sql
ALTER TABLE orders
  ADD INDEX idx_user_status (user_id, status),
  ALGORITHM=INPLACE, LOCK=NONE;
```

`LOCK=NONE`은 DML 허용을 강제한다. 만약 InnoDB가 NONE을 보장 못하면 `ERROR 1846`을 내고 ALTER가 실패한다. `LOCK=SHARED`는 read만 허용, `LOCK=EXCLUSIVE`는 모두 차단이다. 보수적으로는 `LOCK=DEFAULT`를 두고 InnoDB가 가능한 가장 약한 lock을 고르도록 둔다.

INPLACE라도 약점이 있다. row log buffer 크기는 `innodb_online_alter_log_max_size`(기본 128MB)다. 대량 DML이 진행 중인 테이블에서 ALTER가 길어지면 buffer가 가득 차 ALTER가 실패한다. 운영 가이드는 trafic이 적은 시간대를 고르거나 buffer를 일시적으로 1~2GB로 늘리는 것이다.

### 2.3 COPY

가장 보수적이다. 새 테이블을 만들고 한 row씩 복사 후 swap. lock=SHARED로 read만 허용하거나 EXCLUSIVE로 모두 차단한다. 대용량 테이블에 COPY가 발동되면 운영 다운타임이 된다.

`ALGORITHM=COPY`가 강제되는 변경은 PRIMARY KEY 변경, 컬럼 type 변경 중 일부(VARCHAR(255) → TEXT 등), `ROW_FORMAT` 변경 등이다. 운영에서는 COPY가 필요하면 외부 도구로 우회한다.

## 3. pt-online-schema-change: 트리거 기반

Percona Toolkit의 `pt-osc`는 다음 절차로 동작한다.

```
1. 새 테이블 _orders_new 생성 (변경된 스키마)
2. orders → _orders_new로 INSERT/UPDATE/DELETE를 동기화하는 트리거 3개 생성
3. orders의 row를 chunk 단위(보통 1000)로 _orders_new에 복사
4. 복사 완료 후 RENAME TABLE orders TO _orders_old, _orders_new TO orders
5. _orders_old DROP, 트리거 정리
```

장점은 단순함과 호환성. MySQL 5.5부터 동작하고 binlog 형식에 무관하다. 단점은 트리거 비용. 모든 DML이 트리거 1회를 더 실행하므로 throughput이 약 30~50% 감소한다. 또한 트리거가 같은 트랜잭션 안에서 동작하므로 _orders_new의 lock이 source 테이블의 DML 트랜잭션 시간에 포함된다.

또 하나의 큰 단점은 이미 트리거가 걸린 테이블에는 사용 못 한다. 한 테이블에 statement-level 트리거는 INSERT/UPDATE/DELETE 각 1개씩 제한이라, 비즈니스 로직 트리거가 있으면 pt-osc가 충돌한다.

## 4. gh-ost: binlog 기반

GitHub가 발표한 `gh-ost`는 트리거 대신 replication binlog를 사용한다.

```
1. 새 테이블 _orders_ghc 생성 (변경된 스키마)
2. gh-ost가 자기 자신을 replica로 등록해 binlog stream을 구독
3. orders의 row를 chunk 단위로 _orders_ghc에 복사
4. 동시에 binlog로 들어오는 DML을 _orders_ghc에 재적용 (lag 추적)
5. cut-over: lag이 충분히 작아지면 atomic rename 수행
```

트리거가 없으니 source 테이블의 throughput에 영향이 거의 없다. binlog 구독은 read-only 작업이라 production master에 부담이 적다.

운영 친화적인 옵션이 풍부하다.

```bash
gh-ost \
  --max-load=Threads_running=25 \
  --critical-load=Threads_running=200 \
  --chunk-size=1000 \
  --max-lag-millis=1500 \
  --throttle-control-replicas="replica1:3306,replica2:3306" \
  --user="ghost" --password="..." \
  --host=master.db --database="prod" --table="orders" \
  --alter="ADD INDEX idx_user_status (user_id, status)" \
  --execute
```

`max-load`는 master의 부하를 감지해 일시 중단한다. `Threads_running`이 25를 넘으면 chunk 복사를 멈추고, 100 미만으로 떨어지면 재개한다. `critical-load`는 emergency abort 기준이다.

`throttle-control-replicas`는 지정한 replica의 SBM(Seconds Behind Master)이 임계치를 넘으면 throttle 한다. replica로 처리되는 read traffic이 lag으로 stale 해지지 않게 한다.

`max-lag-millis`는 cut-over 직전에 binlog apply lag이 이 값 이하로 떨어져야 cut-over를 시도한다. 이 값을 너무 낮게 잡으면 cut-over가 영영 일어나지 않을 수 있다. 1500ms 정도가 일반적이다.

## 5. gh-ost cut-over의 atomic rename

cut-over는 가장 까다로운 부분이다. `RENAME TABLE orders TO _orders_old, _orders_ghc TO orders`를 atomic하게 수행해야 새 테이블로 들어오는 모든 DML이 이미 적용된 데이터에 정확히 적용된다.

gh-ost의 cut-over는 두 단계 sentinel rename을 사용한다.

```
session A: LOCK TABLES orders WRITE, _orders_ghc WRITE;
session B: RENAME TABLE orders TO _orders_old, _orders_ghc TO orders;
            (LOCK 때문에 대기)
session A: 마지막 binlog event까지 _orders_ghc에 적용
session A: UNLOCK TABLES;
session B: RENAME 즉시 완료
```

이 단순한 그림에는 결함이 있다. session A가 unlock과 동시에 새 connection이 orders에 INSERT 하면 session B의 RENAME이 그 INSERT 뒤로 밀려, 일부 row가 _orders_old에 들어가 lost data가 된다. gh-ost는 magic table을 추가로 사용해 이를 막는다.

```
session A: CREATE _orders_magic; LOCK TABLES orders WRITE, _orders_ghc WRITE, _orders_magic WRITE;
session B: RENAME _orders_magic TO _orders_old, orders TO _orders_old_DROP, _orders_ghc TO orders;
            (B는 magic을 잡으려 시도하다 LOCK 때문에 대기)
session A: 마지막 binlog 이벤트 적용 후 DROP _orders_magic;
session B: magic이 사라지면 RENAME이 실패하지 않고 그 자리를 차지하며 atomic 완료
```

이 구조 덕분에 cut-over 동안 source 테이블에 들어온 DML은 RENAME 이후에 진행되어 새 테이블로 정확히 들어간다. 다운타임은 sub-second다.

## 6. 실측: 1억 행 테이블 ADD INDEX

시나리오: `orders` 1억 행, 평균 row size 400 bytes, 인덱스 4개, 평균 OLTP 부하 5,000 QPS.

| 도구 | 다운타임 | 실행 시간 | source 부하 영향 |
|---|---|---|---|
| 직접 ALTER (INPLACE) | sub-sec MDL | 약 50분 | 약 +20% CPU, OK |
| 직접 ALTER (COPY) | 50분 | 50분 | 사실상 운영 불가 |
| pt-osc | sub-sec | 약 90분 | trigger로 throughput -35% |
| gh-ost | sub-sec | 약 120분 | source에 부하 영향 거의 없음 |

INPLACE가 가능한 변경(ADD INDEX 등)은 직접 ALTER가 가장 빠르다. 다만 row log buffer 한계와 long-running open transaction에 취약하다. 운영 안정성을 우선하면 gh-ost가 표준이다.

ADD COLUMN(NULL DEFAULT)은 INSTANT라 가장 단순한 1초 작업이다. 이 경우 gh-ost를 동원하는 건 과한 결정이다.

PRIMARY KEY 변경처럼 INSTANT/INPLACE 모두 안 되는 작업은 gh-ost가 사실상 유일한 운영 옵션이다.

## 7. 운영 체크리스트

ALTER 시작 전 long-running transaction을 끊는다. `information_schema.innodb_trx`에서 1분 이상 열린 트랜잭션이 있으면 INPLACE ALTER가 row log를 비우지 못한다.

binlog format은 `ROW`여야 gh-ost가 정확하다. `STATEMENT`나 `MIXED`에서는 동작하지 않는다.

```sql
SHOW VARIABLES LIKE 'binlog_format';   -- ROW
```

gh-ost는 `--alter` 절에서 `RENAME COLUMN`을 직접 지원하지 않을 수 있다. 우회: 새 컬럼 추가 → 데이터 복사 → 옛 컬럼 drop의 multi-step 마이그레이션.

cut-over 시점은 트래픽이 가장 적은 시간대로 잡고 `--postpone-cut-over-flag-file`로 운영자 승인 후 `touch` 한 번에 cut-over가 일어나도록 제어한다.

```bash
gh-ost ... --postpone-cut-over-flag-file=/tmp/ghost.postpone.orders
# 작업 모두 완료 → 운영자 검토 후
rm /tmp/ghost.postpone.orders   # cut-over trigger
```

DROP TABLE도 큰 테이블에서는 비싸다. 1억 행 테이블 drop은 InnoDB tablespace 제거에 수 분이 걸리고 buffer pool flush로 latency를 만든다. `--ok-to-drop-table`을 끄고 swap된 _orders_old를 운영시간 외에 별도로 drop 한다.

## 8. Trade-off 요약

INSTANT는 가능하면 항상 1순위다. 컬럼 추가/메타 변경 한정이지만 실질적 다운타임 0, 비용 0이다.

INPLACE는 인덱스 변경에 가장 효율적이다. row log buffer와 open transaction을 관리할 수 있는 운영 환경이라면 외부 도구보다 빠르다.

gh-ost는 모든 ALTER를 무중단으로 만들 수 있는 일반 솔루션이지만 실행 시간이 INPLACE보다 길고 별도 인프라(binlog access 권한, 모니터링)가 필요하다. 큰 조직의 표준 운영 도구로 자리 잡았다.

pt-osc는 트리거 기반이라 throughput 부담이 있고, 비즈니스 트리거와 충돌하면 못 쓴다. 새 프로젝트에서는 gh-ost가 사실상 default다.

## 참고

- MySQL 8.0 Reference Manual - Online DDL Operations
- Percona, "pt-online-schema-change Documentation"
- GitHub, "gh-ost: GitHub's online schema migration for MySQL" (open source)
- Shlomi Noach, "Solving the cut-over problem in gh-ost"
