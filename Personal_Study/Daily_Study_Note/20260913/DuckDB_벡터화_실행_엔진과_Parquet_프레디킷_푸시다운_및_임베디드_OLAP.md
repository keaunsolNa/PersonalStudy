Notion 원본: https://app.notion.com/p/3da5a06fd6d38178804bfc658ec08be0?pvs=204

# DuckDB 벡터화 실행 엔진과 Parquet 프레디킷 푸시다운 및 임베디드 OLAP

> 2026-09-13 신규 주제 · 확장 대상: Oracle 옵티마이저 / SQLD 분석 질의

## 학습 목표

- 튜플 단위 실행과 벡터화 실행의 비용 차이를 CPU 수준에서 규명한다
- Parquet 의 파일 구조에서 어떤 메타데이터가 스킵을 가능하게 하는지 분석한다
- 푸시다운이 실제로 적용됐는지 `EXPLAIN ANALYZE` 로 검증한다
- OLTP 데이터베이스와 임베디드 OLAP 의 역할 분담 기준을 정한다

## 1. 임베디드 OLAP 이라는 범주

SQLite 는 임베디드 OLTP 다. 프로세스 안에서 돌고, 서버가 없고, 행 단위로 읽고 쓴다. DuckDB 는 같은 배치 모델(프로세스 내장, 서버 없음)에 **분석 워크로드용 엔진**을 넣었다.

차이를 만드는 세 가지 설계 결정이 있다.

**열 지향 저장.** 한 컴럼의 값들이 연속으로 놀인다. `SELECT AVG(amount) FROM sales` 는 `amount` 만 읽는다. 행 지향이면 각 행 전체를 읽어 필요 없는 바이트를 버린다. 100컴럼 테이블에서 3컴럼만 쓰는 분석 질의라면 I/O 가 30배 차이 난다.

**벡터화 실행.** 연산자가 값 하나가 아니라 1024개 묶음(벡터)을 처리한다.

**단일 프로세스.** 네트워크 왕복이 없다. Python/Java 프로세스 메모리를 직접 읽을 수 있다.

Java 백엔드 관점에서 위치를 잡자면, DuckDB 는 "Oracle 을 대체하는 것"이 아니라 **배치 집계·리포팅 전처리·로컬 데이터 검증**에서 Spark 클러스터를 띄울 정도는 아닌 작업을 담당한다. 수백 GB 까지는 노트북 한 대에서 처리된다.

## 2. 튜플 단위 실행의 비용

전통적 실행 엔진(Volcano 모델)은 연산자마다 `next()` 를 호출해 튜플 하나를 받는다.

```
Aggregate.next()
  → Filter.next()
      → Scan.next()  → 튜플 1개 반환
```

행 1억 개면 `next()` 호출이 연산자 단계마다 1억 번이다. 문제는 호출 횟수 자체가 아니라 **호출당 고정 비용**이다.

| 비용 항목 | 튜플 단위 | 벡터화 |
|---|---|---|
| 가상 함수 호출 | 튜플마다 | 벡터(1024개)마다 |
| 분기 예측 실패 | 튜플마다 조건 평가 | 분기 없는 마스크 연산 가능 |
| 명령어 캐시 | 연산자 전환 반복 | 한 연산자 코드가 루프 |
| SIMD | 불가능 | 가능 |

구체적으로, 튜플 하나 처리에 필요한 실제 계산이 5 사이클인데 가상 호출과 분기 오버헤드가 50 사이클이면 유효 효율이 10% 다. 벡터화는 이 오버헤드를 1024개로 나눠 사실상 없앵다.

DuckDB 의 벡터 크기 기본값은 2048(STANDARD_VECTOR_SIZE)이다. 이 값의 선택 기준은 **L1 캐시에 들어가는가**다. 8바이트 값 2048개는 16KB 로, 일반적인 32KB L1D 캐시에 중간 결과 몇 개까지 함께 들어간다. 벡터를 키우면 가상 호출 오버헤드는 더 줄지만 캐시를 벗어나 메모리 대역폭 병목이 생긴다. MonetDB/X100 논문이 실측으로 이 최적점을 보인 바 있다.

컴파일 방식(LLVM JIT 로 질의를 기계어로 생성)과 비교하면, 벡터화는 컴파일 지연이 없고 구현·디버깅이 쉽다. 대신 극단적으로 단순한 질의에서는 컴파일 방식이 더 빠를 수 있다. DuckDB 는 짧은 대화형 질의가 많다는 판단으로 벡터화를 택했다.

## 3. Parquet 파일 구조와 스킵 가능 지점

```
File
 └ Row Group (기본 수십~수백 MB)
     └ Column Chunk (컴럼당 1개)
         └ Page (기본 1MB 미만)
```

파일 끝에 footer 가 있고, 여기에 각 Row Group 의 각 Column Chunk 에 대한 **통계**가 들어 있다. 최소값, 최대값, null 개수, 고유값 개수(선택적)다.

```sql
SELECT SUM(amount) FROM 'sales/*.parquet' WHERE order_date = DATE '2026-09-01';
```

엔진은 footer 를 읽어 각 Row Group 의 `order_date` 최소·최대를 본다. `[2026-01-01, 2026-03-31]` 범위인 Row Group 은 조건을 만족할 수 없으므로 **바이트 하나도 읽지 않고 건너뛴다**. 이것이 프레디킷 푸시다운의 1차 효과다.

여기서 중요한 실무 함의: **통계가 쓸모 있으려면 데이터가 정렬되어 있어야 한다**. 날짜가 무작위로 섞인 파일이라면 모든 Row Group 의 min/max 가 전 구간을 덮어 스킵이 0건이다. 같은 데이터, 같은 질의인데 쓰기 시 정렬 여부만으로 10배 차이가 나는 일이 흔하다.

```sql
-- 나쁨: 정렬 없음
COPY sales TO 'out/sales.parquet' (FORMAT PARQUET);

-- 좋음: 자주 필터링하는 컴럼으로 정렬
COPY (SELECT * FROM sales ORDER BY order_date, region)
	TO 'out/sales.parquet' (FORMAT PARQUET, ROW_GROUP_SIZE 1000000);
```

더 강력한 수단은 **하이브 파티셔닝**이다. 디렉터리 이름에 값을 넣으면 파일을 열기도 전에 경로만으로 제외된다.

```sql
COPY sales TO 'warehouse/sales'
	(FORMAT PARQUET, PARTITION_BY (year, month), OVERWRITE_OR_IGNORE);
-- warehouse/sales/year=2026/month=09/data_0.parquet
```

```sql
SELECT SUM(amount)
FROM read_parquet('warehouse/sales/**/*.parquet', hive_partitioning = true)
WHERE year = 2026 AND month = 9;
```

파티션 컴럼은 파일 안에 저장되지 않고 경로에서 복원된다. 저장 공간도 줄고 스킵도 완벽하다. 단점은 파티션이 지나치게 잔게 쪼개지면 작은 파일이 폭증해 메타데이터 읽기가 병목이 되는 것이다. 파티션당 수백 MB 를 목표로 잡는다.

## 4. 프로젝션 푸시다운과 늦은 구체화

컴럼 선택도 푸시다운 대상이다.

```sql
SELECT customer_id, SUM(amount)
FROM 'sales.parquet'
WHERE region = 'KR'
GROUP BY customer_id;
```

100개 컴럼 중 `customer_id`, `amount`, `region` 세 개의 Column Chunk 만 읽는다. 나머지 97개는 파일에 있어도 디스크에서 올라오지 않는다. 열 지향 포맷의 본질적 이득이며, 이것만으로도 대개 가장 큰 효과를 낸다.

`SELECT *` 는 이 이득을 통로 버린다. 탐색 단계에서조차 `SELECT * FROM t LIMIT 10` 대신 필요한 컴럼을 적는 습관이 유효하다. 다만 `LIMIT` 이 있으면 DuckDB 가 Row Group 하나만 읽고 멈추므로 실제 피해는 제한적이다.

DuckDB 는 여기에 더해 **늦은 구체화(late materialization)** 를 적용한다. 필터 조건에 쓰이는 컴럼을 먼저 읽어 선택 마스크를 만들고, 통과한 행에 대해서만 나머지 컴럼을 읽는다. 선택도가 1% 인 필터라면 출력 컴럼 읽기가 99% 줄어든다.

## 5. 실행 계획 검증

푸시다운이 "되었을 것"이라 가정하면 안 된다. 측정한다.

```sql
EXPLAIN ANALYZE
SELECT region, SUM(amount)
FROM read_parquet('warehouse/sales/**/*.parquet', hive_partitioning = true)
WHERE year = 2026 AND month = 9 AND amount > 10000
GROUP BY region;
```

출력에서 봐야 할 것은 `PARQUET_SCAN` 노드다.

```
┌─────────────────────────┐
│         PARQUET_SCAN      │
│    Projections: region,   │
│                amount     │
│    Filters: amount>10000  │
│    Rows scanned: 2,140,88 │
│    Total files: 1         │
│    (files skipped: 23)    │
└─────────────────────────┘
```

확인 항목 셋:

**Filters 줄에 조건이 있는가.** 없으면 필터가 스캔 위로 올라가지 못하고 별도 FILTER 노드에서 처리된다는 뜻이다. 이 경우 모든 행이 읽힌다.

**files skipped 가 기대만큼인가.** 파티션 프루닝이 동작하는지 직접 보여 준다. 0 이면 `hive_partitioning` 설정이나 조건 형태를 의심한다.

**Rows scanned 대 결과 행 수.** 비율이 크면 Row Group 통계가 무력하다는 신호이고, 정렬 전략을 재검토할 근거가 된다.

푸시다운이 안 되는 대표적인 원인은 **컴럼에 함수를 씨운 조건**이다.

```sql
-- 푸시다운 불가: 컴럼이 함수 인자
WHERE YEAR(order_date) = 2026

-- 푸시다운 가능: 컴럼이 그대로
WHERE order_date >= DATE '2026-01-01' AND order_date < DATE '2027-01-01'
```

Oracle 의 인덱스 무효화와 정확히 같은 원리다. 함수를 씨우면 옵티마이저가 원본 컴럼의 min/max 와 조건을 비교할 수 없다. SQLD 에서 배우는 사공 조건(sargable predicate)의 개념이 그대로 적용된다.

## 6. 원격 파일과 부분 읽기

DuckDB 는 HTTP Range 요청으로 S3 나 HTTPS 상의 Parquet 을 **부분만** 읽는다.

```sql
INSTALL httpfs;
LOAD httpfs;

CREATE SECRET (
	TYPE S3,
	KEY_ID 'AKIA...',
	SECRET '...',
	REGION 'ap-northeast-2'
);

SELECT region, COUNT(*)
FROM read_parquet('s3://my-bucket/sales/year=2026/**/*.parquet')
WHERE amount > 100000
GROUP BY region;
```

읽기 순서가 중요하다. 먼저 footer(파일 끝 수 KB)만 Range 로 가져와 통계를 보고, 살아남은 Row Group 의 해당 Column Chunk 범위만 다시 Range 로 요청한다. 100GB 파일에서 실제 전송량이 수백 MB 에 그치는 일이 흔하다.

```sql
SET http_keep_alive = true;        -- 커넥션 재사용
SET threads = 8;                    -- 병렬 Range 요청 수에 영향
```

작은 파일이 수만 개면 footer 읽기 요청 자체가 병목이 된다. 이럴 때는 파일을 병합(compaction)하는 것이 유일한 해법이다. 목표 파일 크기는 128MB~512MB 가 무난하다.

## 7. Java 애플리케이션에서의 사용

```java
public class SalesAggregator {

	private static final String JDBC_URL = "jdbc:duckdb:";

	public Map<String, Long> aggregateByRegion(Path parquetDir, int year, int month) {
		String sql = """
				SELECT region, SUM(amount) AS total
				FROM read_parquet(?, hive_partitioning = true)
				WHERE year = ? AND month = ?
				GROUP BY region
				""";

		Map<String, Long> result = new LinkedHashMap<>();
		try (Connection connection = DriverManager.getConnection(JDBC_URL);
				PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, parquetDir.resolve("**/*.parquet").toString());
			statement.setInt(2, year);
			statement.setInt(3, month);

			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					result.put(rs.getString("region"), rs.getLong("total"));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("집계 실패", e);
		}
		return result;
	}
}
```

`jdbc:duckdb:` 는 인메모리 데이터베이스를 열고, 파일 기반은 `jdbc:duckdb:/path/to/db.duckdb` 를 쓴다.

동시성 모델을 반드시 알아야 한다. DuckDB 는 **한 프로세스에서 하나의 쓰기 연결**만 허용하며, 파일 DB 는 다른 프로세스가 잡고 있으면 열리지 않는다. 웹 애플리케이션에서 요청마다 `DriverManager.getConnection` 을 부르면 곧바로 문제가 된다. 하나의 `Connection` 을 만들어 두고 `connection.duplicate()` 로 같은 DB 인스턴스를 공유하는 자식 연결을 쓰는 것이 정석이다.

```java
@Configuration
public class DuckDbConfig {

	@Bean(destroyMethod = "close")
	public DuckDBConnection duckDbConnection() throws SQLException {
		return (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
	}
}
```

```java
try (Connection child = duckDbConnection.duplicate()) {
	// 스레드별로 독립 세션, 같은 DB 인스턴스
}
```

읽기 전용 분석만 한다면 `access_mode=READ_ONLY` 로 여러 프로세스가 동시에 열 수 있다.

```
jdbc:duckdb:/data/warehouse.duckdb?access_mode=READ_ONLY
```

## 8. OLTP 와의 역할 분담

DuckDB 를 운영 DB 로 쓰면 안 되는 이유는 성능이 아니라 **동시성과 내구성 모델**이다.

| 기준 | Oracle/MySQL | DuckDB |
|---|---|---|
| 동시 쓰기 | 다중 세션 | 프로세스 내 단일 writer |
| 네트워크 접근 | 기본 | 없음(임베디드) |
| 행 단위 UPDATE | 최적화됨 | 가능하지만 느림 |
| 복제·HA | 성숙 | 없음 |
| 대량 집계 | 상대적으로 느림 | 매우 빠름 |

실무 배치는 대체로 이렇게 굳는다. 운영 데이터는 Oracle/MySQL 에 있고, CDC 나 야간 배치로 Parquet 를 S3 에 떨어뜨린다. 리포팅·데이터 검증·임시 분석은 DuckDB 가 그 Parquet 를 직접 읽어 처리한다. Spark 클러스터를 상시 유지할 필요가 없어지는 구간이 생각보다 넓다.

한 가지 실용적인 조합은 **운영 DB 를 DuckDB 에서 직접 붙여 읽는 것**이다.

```sql
INSTALL postgres;
LOAD postgres;
ATTACH 'host=db.internal dbname=app user=readonly' AS pg (TYPE POSTGRES, READ_ONLY);

-- 운영 테이블과 S3 Parquet 을 한 질의에서 조인
SELECT c.name, SUM(s.amount)
FROM pg.public.customers c
JOIN read_parquet('s3://bucket/sales/**/*.parquet') s ON s.customer_id = c.id
GROUP BY c.name;
```

이때 운영 DB 쪽에 부하가 걸린다는 점을 잊으면 안 된다. 조인 대상이 크면 DuckDB 가 전체 테이블을 끌어온다. 반드시 읽기 전용 복제본을 겨냥하고, 필터를 운영 DB 쪽으로 밀어 넣을 수 있는 형태로 질의를 작성한다. MySQL, SQLite 용 확장도 동일한 패턴으로 제공된다.

## 참고

- DuckDB Documentation — Parquet (https://duckdb.org/docs/stable/data/parquet/overview)
- DuckDB — Performance Guide (https://duckdb.org/docs/stable/guides/performance/overview)
- Apache Parquet — File Format Specification (https://parquet.apache.org/docs/file-format/)
- Boncz, Zukowski, Nes — "MonetDB/X100: Hyper-Pipelining Query Execution", CIDR 2005
- Raasveldt, Mühleisen — "DuckDB: an Embeddable Analytical Database", SIGMOD 2019
