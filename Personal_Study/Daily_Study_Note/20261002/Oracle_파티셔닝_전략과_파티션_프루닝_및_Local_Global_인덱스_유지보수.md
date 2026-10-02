Notion 원본: https://www.notion.so/3ed5a06fd6d381e9983acecd2288a971

# Oracle 파티셔닝 전략과 파티션 프루닝 및 Local/Global 인덱스 유지보수

> 2026-10-02 신규 주제 · 확장 대상: Oracle

## 학습 목표

- 데이터 수명과 조회 패턴에 맞춰 Range, List, Hash, Interval, Reference 전략을 고른다.
- 실행계획의 Pstart/Pstop 컬럼으로 파티션 프루닝 성공 여부를 판독한다.
- 프루닝을 깨뜨리는 SQL과 JDBC 바인딩 패턴을 찾아 고친다.
- Local/Global 인덱스를 비교하고 파티션 DDL 이후 인덱스 상태를 관리한다.

## 1. 파티셔닝이 해결하는 문제와 치르는 비용

파티셔닝은 하나의 논리 테이블을 키 기준으로 여러 물리 세그먼트로 나누는 기능이다. 옵티마이저는 조건절을 보고 필요한 세그먼트만 읽는다. 이득은 세 가지다. 읽는 블록이 줄고, 오래된 데이터를 DELETE 대신 파티션 DROP/TRUNCATE로 지워 undo와 redo 부담이 거의 없으며, 통계와 백업, 압축, 이관을 파티션 단위로 할 수 있다.

비용도 분명하다. Oracle Partitioning은 Enterprise Edition의 별도 라이선스 옵션이고, 파티션 키가 PK와 Unique 인덱스에 들어가야 하며, 파티션 키 조건이 없는 쿼리는 오히려 느려질 수 있다. 그래서 쿼리가 특정 키 범위로 접근하고 데이터를 기간 단위로 폐기할 때 도입한다.

## 2. 파티셔닝 전략 선택

기본 전략은 Range, List, Hash이고 여기에 Composite, 11g에서 추가된 Interval과 Reference가 있다. 12.2부터는 List 파티션을 자동 생성하는 Automatic List도 쓸 수 있다.

| 전략 | 파티션 결정 방식 | 적합한 데이터 | 주의점 |
|---|---|---|---|
| Range | 키 값의 구간 | 일자 기반 로그, 이력 | 최신 파티션에 쓰기 집중 |
| Interval | Range에 구간 자동 생성 | 월 단위로 증가하는 데이터 | 파티션 키는 단일 컬럼 |
| List | 이산 값 목록 | 지역, 테넌트 | 값 분포 편중 |
| Hash | 해시 함수 | 균등 분산이 목표인 대용량 | 범위 프루닝 불가 |
| Reference | 부모 FK 구조 상속 | 주문과 주문상세 | 부모 PK, NOT NULL FK 필요 |

가장 흔한 선택은 Interval이다. 파티션을 미리 만들지 않아도 해당 구간의 첫 행이 들어오는 순간 Oracle이 만든다. 아래는 월 단위 Interval 주문 테이블이며 PK에 파티션 키를 넣고 Local 인덱스로 만든 점을 눈여겨본다.

```sql
CREATE TABLE orders (
    order_id     NUMBER        NOT NULL,
    customer_id  NUMBER        NOT NULL,
    order_dt     DATE          NOT NULL,
    status       VARCHAR2(10)  NOT NULL,
    amount       NUMBER(12, 2) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (order_id, order_dt) USING INDEX LOCAL
)
PARTITION BY RANGE (order_dt)
INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
(
    PARTITION p_before_2026 VALUES LESS THAN (DATE '2026-01-01')
);

CREATE INDEX ix_orders_customer ON orders (customer_id, order_dt) LOCAL;
```

PK가 `(order_id, order_dt)` 인 것은 트레이드오프다. Local Unique 인덱스는 파티션 키를 포함해야 하므로 `order_id` 단독 유일성은 DB가 보장하지 못한다. 꼭 필요하면 Global Unique 인덱스를 두되 파티션 DDL마다 유지보수 부담이 따른다. 나머지 전략 중 List는 `PARTITION BY LIST (region) AUTOMATIC (PARTITION p_kr VALUES ('KR'))`, Composite은 `SUBPARTITION BY HASH (customer_id) SUBPARTITIONS 4` 절을 Range 선언 뒤에 덧붙이는 식이고, Reference는 다음과 같이 선언한다.

```sql
-- Reference: 자식이 부모의 파티션 구조를 그대로 따른다
CREATE TABLE order_item (
    item_id NUMBER NOT NULL, order_id NUMBER NOT NULL, order_dt DATE NOT NULL, quantity NUMBER NOT NULL,
    CONSTRAINT pk_order_item PRIMARY KEY (item_id),
    CONSTRAINT fk_item_order FOREIGN KEY (order_id, order_dt) REFERENCES orders (order_id, order_dt)
)
PARTITION BY REFERENCE (fk_item_order);
```

## 3. 파티션 키 설계 원칙

파티션 키는 한 번 정하면 바꾸기 어렵다. 새 테이블로 옮겨야 하기 때문이다. 그래서 "가장 많이 쓰는 조회 조건" 과 "데이터를 폐기하는 단위" 를 동시에 만족하는 컬럼을 고른다. 주문이라면 `order_dt` 가 맞다. `customer_id` 로 Hash 파티셔닝하면 고객별 조회는 좁혀지지만 기간 폐기는 불가능하고 기간 조회는 전 파티션을 읽는다. 일 단위로 5년이면 1,800개가 넘는 파티션이 생겨 관리 비용이 늘므로 월 단위부터 검토한다. Interval 파티션의 자동 생성 파티션 이름은 `SYS_P12345` 처럼 의미가 없다. 이름 대신 `PARTITION FOR (날짜)` 문법으로 지정하면 이름에 의존하지 않는다. 처음 선언한 Range 영역(`p_before_2026`)의 마지막 파티션은 DROP할 수 없다.

## 4. 파티션 프루닝의 원리와 실행계획 판독

파티션 프루닝은 옵티마이저가 조건절에서 파티션 키 조건을 찾아 읽지 않아도 되는 파티션을 제외하는 동작이다. 조건이 리터럴이면 파싱 시점에 파티션이 확정되는 정적 프루닝이고, 바인드 변수나 조인 결과로 값이 정해지면 실행 시점에 결정되는 동적 프루닝이다. 판독의 핵심은 `DBMS_XPLAN` 출력의 Pstart/Pstop이다. 숫자가 같으면 단일 파티션, 범위면 연속 구간, `KEY` 는 실행 시점 결정, 처음부터 끝 번호까지면 사실상 전체 스캔이다.

```sql
EXPLAIN PLAN FOR
SELECT order_id, amount FROM orders
 WHERE order_dt >= DATE '2026-03-01' AND order_dt < DATE '2026-04-01' AND customer_id = 1001;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL, 'BASIC +PARTITION'));
```

```text
| Id | Operation                                   | Pstart| Pstop |
|  1 |  PARTITION RANGE SINGLE                     |     3 |     3 |
|  2 |   TABLE ACCESS BY LOCAL INDEX ROWID BATCHED |     3 |     3 |
|  3 |    INDEX RANGE SCAN                         |     3 |     3 |
```

위 출력은 예시이며 파티션 번호는 환경마다 다르다. `PARTITION RANGE SINGLE` 은 한 파티션, `ITERATOR` 는 연속된 여러 파티션, `ALL` 은 전체다. 바인드 변수를 쓰면 Pstart/Pstop이 `KEY` 로 보이는데 정상이다. 실제 접근량은 `/*+ GATHER_PLAN_STATISTICS */` 힌트와 `DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST +PARTITION')` 의 Buffers로 확인한다.

## 5. 프루닝을 깨뜨리는 패턴과 Spring JDBC 바인딩

운영에서 프루닝이 실패하는 원인은 대부분 파티션 키 컬럼을 가공하는 SQL이다. 일반적으로 `TO_CHAR(order_dt, 'YYYYMM') = :ym`, `TRUNC(order_dt) = :d` 처럼 컬럼에 함수를 걸면 옵티마이저가 파티션 구간과 비교하지 못해 전 파티션을 읽는다. 컬럼은 그대로 두고 값 쪽을 변환해 반개구간으로 쓴다.

```sql
-- 나쁜 예: 파티션 키에 함수 적용
SELECT COUNT(*) FROM orders WHERE TO_CHAR(order_dt, 'YYYYMM') = '202603';

-- 좋은 예: 컬럼은 그대로, 반개구간 사용
SELECT COUNT(*) FROM orders
 WHERE order_dt >= DATE '2026-03-01' AND order_dt < DATE '2026-04-01';
```

자바에서 놓치기 쉬운 부분은 바인드 타입이다. DATE 컬럼에 `LocalDateTime` 이나 `Timestamp` 를 바인딩하면 TIMESTAMP로 전달되어 Oracle이 컬럼 쪽에 암묵적 변환을 넣을 수 있고, 인덱스와 프루닝 영향은 버전과 쿼리에 따라 다르므로 실행계획으로 확인해야 한다. 가장 안전한 방법은 `java.sql.Date` 바인딩이다. 아래는 Naver Java 컨벤션을 따른 저장소다.

```java
@Repository
public class OrderQueryRepository {

	private static final String SQL_FIND_BY_PERIOD =
		"SELECT order_id, customer_id, status, amount FROM orders "
			+ "WHERE order_dt >= ? AND order_dt < ? AND customer_id = ?";

	private final JdbcTemplate jdbcTemplate;

	public OrderQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<OrderRow> findByPeriod(LocalDate fromInclusive, LocalDate toExclusive, long customerId) {
		return jdbcTemplate.query(
			SQL_FIND_BY_PERIOD,
			(rs, rowNumber) -> new OrderRow(rs.getLong("order_id"), rs.getLong("customer_id"),
				rs.getString("status"), rs.getBigDecimal("amount")),
			Date.valueOf(fromInclusive), Date.valueOf(toExclusive), customerId);
	}
}
```

상한 없이 `order_dt >= ?` 만 주면 전 파티션을 읽으므로 서비스 계층에서 조회 기간의 최대 길이를 제한하는 것이 가장 값싼 방어다.

## 6. Local 인덱스와 Global 인덱스

Local 인덱스는 테이블과 같은 방식으로 파티션되어 인덱스 파티션 하나가 테이블 파티션 하나에 대응하므로 파티션 DDL이 해당 인덱스 파티션에만 영향을 준다. Global 인덱스는 테이블 구조와 독립적이며 비파티션이거나 별도 키로 Range, Hash 파티션할 수 있다.

차이는 조회 비용에서 드러난다. 파티션 키 조건이 있으면 Local 인덱스는 프루닝된 파티션의 인덱스만 탄다. 하지만 `customer_id` 만 주는 쿼리처럼 키 조건이 없으면 모든 인덱스 파티션을 하나씩 탐색한다. 파티션이 36개이고 인덱스 높이가 3이면 단순 계산으로 약 108번의 블록 접근이 필요하고(예시 산식이며 실측값이 아니다), 단일 Global 인덱스는 3~4번이면 된다. 파티션 키 없이 단건을 찾는 OLTP는 Global이, 기간 범위를 거는 배치와 분석은 Local이 유리하다.

| 구분 | Local | Global |
|---|---|---|
| 구조 | 테이블 파티션과 1:1 | 테이블 파티션과 독립 |
| 파티션 DDL 영향 | 해당 인덱스 파티션만 | 전체 인덱스, 관리 필요 |
| 파티션 키 없는 단건 조회 | 모든 파티션 탐색 | 한 번의 탐색 |
| Unique 제약 | 파티션 키 포함 필수 | 임의 컬럼 가능 |
| 적합한 용도 | 기간 조회, 대량 배치, 이력 폐기 | 키 단건 조회, 비파티션키 유일성 |

실무 원칙은 기본을 Local로 두고 Global은 꼭 필요한 곳에만 추가하는 것이다.

## 7. 파티션 유지보수 작업과 인덱스 상태

파티션 DDL의 함정은 인덱스 상태다. Local 인덱스는 자동 관리되지만 MOVE, SPLIT, MERGE는 관련 인덱스 파티션을 UNUSABLE로 만들 수 있고, Global 인덱스는 DROP, TRUNCATE, EXCHANGE, SPLIT을 별도 절 없이 실행하면 UNUSABLE이 될 수 있다. `UPDATE INDEXES` 또는 `UPDATE GLOBAL INDEXES` 를 붙이면 DDL 중에 인덱스를 함께 갱신해 USABLE을 유지하지만 DDL 시간이 늘고 redo가 생긴다.

UNUSABLE의 영향은 둘로 갈린다. `SKIP_UNUSABLE_INDEXES` 기본값이 TRUE라 비유일 인덱스는 무시되어 오류 없이 풀스캔으로 바뀌고, Unique 인덱스는 DML이 ORA-01502로 실패한다. 12.1부터 DROP/TRUNCATE PARTITION에 `UPDATE GLOBAL INDEXES` 를 쓰면 Global 인덱스 정리가 비동기로 미뤄진다. 삭제된 파티션의 엔트리는 orphaned로 남지만 인덱스는 USABLE이고 결과도 정확하며, `PMO_DEFERRED_GIDX_MAINT_JOB` (기본 매일 새벽 2시)이나 `DBMS_PART.CLEANUP_GIDX` 가 나중에 정리한다.

```sql
ALTER TABLE orders DROP PARTITION FOR (DATE '2024-01-15') UPDATE GLOBAL INDEXES;

-- 적재: 스테이징 테이블과 파티션 교체 (12.2+ FOR EXCHANGE)
CREATE TABLE orders_stage FOR EXCHANGE WITH TABLE orders;
ALTER TABLE orders EXCHANGE PARTITION FOR (DATE '2026-09-01')
    WITH TABLE orders_stage INCLUDING INDEXES WITHOUT VALIDATION UPDATE GLOBAL INDEXES;

-- 상태 점검과 복구
SELECT index_name, partition_name, status FROM user_ind_partitions WHERE status = 'UNUSABLE';
SELECT index_name, status FROM user_indexes WHERE status = 'UNUSABLE';
ALTER INDEX ix_orders_customer REBUILD PARTITION sys_p12345 ONLINE;  -- 파티션 이름은 점검 쿼리 결과 사용
```

EXCHANGE는 딕셔너리만 바꿔 거의 즉시 끝난다. 다만 `WITHOUT VALIDATION` 은 범위 검사를 건너뛰므로 적재 단계에서 범위를 보증해야 하고, `INCLUDING INDEXES` 는 스테이징 테이블에 대응 인덱스가 있어야 한다. 통계는 `DBMS_STATS.SET_TABLE_PREFS` 의 `INCREMENTAL` 설정으로 변경된 파티션만 수집할 수 있다.

## 8. Spring에서 파티션 유지보수 자동화

보관 기간이 지난 파티션 폐기는 스케줄 잡으로 자동화하는 편이 안전하다. DDL에는 이름을 바인드 변수로 쓸 수 없어 문자열 결합이 불가피하므로, 테이블 이름은 상수로 고정하고 파티션 이름은 정규식으로 검증한다. `HIGH_VALUE` 는 LONG 타입이라 `TO_DATE(' 2026-02-01 00:00:00', ...)` 텍스트로 나오므로 날짜만 정규식으로 뽑고, LONG은 SELECT 마지막 컬럼에 둔다. 대상은 `INTERVAL = 'YES'` 파티션으로 한정하고, HIGH_VALUE가 기준일 이하이면 구간 전체가 보관 기간을 벗어난 것이다.

```java
@Component
public class OrderPartitionRetentionJob {

	private static final String TARGET_TABLE = "ORDERS";
	private static final int RETENTION_MONTHS = 24;
	private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_$#]{0,29}$");
	private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
	private static final String SQL_LIST_PARTITIONS =
		"SELECT partition_name, high_value FROM user_tab_partitions "
			+ "WHERE table_name = ? AND interval = 'YES' ORDER BY partition_position";

	private final JdbcTemplate jdbcTemplate;

	public OrderPartitionRetentionJob(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Scheduled(cron = "0 30 3 1 * *")
	public void dropExpiredPartitions() {
		LocalDate cutoff = LocalDate.now()
			.minusMonths(RETENTION_MONTHS)
			.with(TemporalAdjusters.firstDayOfMonth());

		List<PartitionInfo> partitions = jdbcTemplate.query(
			SQL_LIST_PARTITIONS,
			(rs, rowNumber) -> new PartitionInfo(rs.getString(1), parseHighValue(rs.getString(2))),
			TARGET_TABLE);

		for (PartitionInfo partition : partitions) {
			if (partition.highValue().isAfter(cutoff)) {
				break;
			}
			dropPartition(partition.name());
		}
	}

	private void dropPartition(String partitionName) {
		if (!PARTITION_NAME_PATTERN.matcher(partitionName).matches()) {
			throw new IllegalStateException("Unexpected partition name: " + partitionName);
		}
		jdbcTemplate.execute("ALTER TABLE " + TARGET_TABLE + " DROP PARTITION " + partitionName
			+ " UPDATE GLOBAL INDEXES");
	}

	private static LocalDate parseHighValue(String highValue) {
		Matcher matcher = DATE_PATTERN.matcher(highValue);
		if (!matcher.find()) {
			throw new IllegalStateException("Cannot parse high_value: " + highValue);
		}
		return LocalDate.parse(matcher.group(1));
	}

	private record PartitionInfo(String name, LocalDate highValue) {
	}
}
```

운영 시 다중 인스턴스에서는 ShedLock 같은 분산 락이나 단일 배치 서버로 실행을 제한해야 한다. DDL은 암묵적 커밋을 일으켜 `@Transactional` 로 감싸도 되돌릴 수 없다. 점검은 파티션별 크기 편차와 `user_indexes.orphaned_entries`, UNUSABLE 상태를 주기적으로 확인한다.

트레이드오프는 분명하다. 대량 이력을 DELETE로 지우면 undo, redo와 인덱스 갱신이 따르지만 파티션 DROP은 딕셔너리 변경 중심이라 훨씬 짧게 끝난다. 반대로 파티션 키 없는 쿼리가 많으면 Local 인덱스 탐색이 파티션 수만큼 늘어난다. 도입 전에 쿼리 로그에서 파티션 키 조건 포함 비율을 측정해 결정한다.

## 참고

- Oracle Database VLDB and Partitioning Guide (Oracle 공식 문서)
- Oracle Database SQL Language Reference: CREATE TABLE의 partitioning 절, ALTER TABLE의 partition 관련 절
- Oracle Database SQL Tuning Guide: Reading Execution Plans 관련 장
- Oracle Database PL/SQL Packages and Types Reference: DBMS_PART, DBMS_STATS
- Tom Kyte, Expert Oracle Database Architecture (Apress), 파티셔닝 장
