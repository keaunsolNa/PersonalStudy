Notion 원본: https://app.notion.com/p/3a35a06fd6d381a09846e1a0a236b523

# Oracle 라이브러리 캐시 래치와 뮤텍스 경합 및 커서 공유 진단

> 2026-07-21 신규 주제 · 확장 대상: Oracle

## 학습 목표

- 라이브러리 캐시의 구조와 파싱 과정에서 래치·뮤텍스가 보호하는 대상을 구분한다
- Soft/Hard Parse 와 커서 공유 실패가 경합을 유발하는 메커니즘을 추적한다
- 바인드 변수와 CURSOR_SHARING 으로 Shared Pool 경합을 줄이는 방법을 적용한다
- Wait Event 로 라이브러리 캐시 병목을 진단하는 SQL 진단 경로를 세운다

## 1. 라이브러리 캐시와 Shared Pool 구조

Oracle 의 SGA 안에는 Shared Pool 이 있고, 그 핵심 구성요소가 라이브러리 캐시다. 여기에는 파싱된 SQL·PL/SQL 문, 실행 계획, 파스 트리가 해시 버킷 구조로 저장된다. 이미 파싱된 커서를 재사용하는 것이 라이브러리 캐시의 존재 이유다. 과거엔 래치로, 11g 이후 상당 부분이 뮤텍스로 대체되었다.

## 2. 래치 vs 뮤텍스

래치는 획득에 실패하면 스핑하다가 sleep 하고, 하나의 래치가 여러 버킷을 묶어 false contention 이 발생할 수 있었다. 뮤텍스는 11g 에서 도입되며 보호 대상마다 하나씩 존재해 세분화되어 있고, 참조 카운트 방식으로 여러 세션이 같은 커서를 공유 pin 할 수 있다.

| 항목 | 래치 | 뮤텍스 |
|---|---|---|
| 세분성 | 굵음 | 대상마다 1개 |
| 메모리 | 별도 구조 | 대상에 임베드 |
| 공유 획득 | 불가 | 참조 카운트로 가능 |
| 대표 Wait | latch: library cache | cursor: pin S, library cache: mutex X |

## 3. Hard Parse 가 경합을 폭발시키는 경로

동일한 커서가 있으면 계획을 재사용한다(Soft Parse). 없으면 새 계획을 만들고 커서를 삽입한다(Hard Parse). Hard Parse 는 뮤텍스를 배타적으로 잡아 `library cache: mutex X` 대기를 치솟게 한다. 가장 흔한 원인은 리터럴 SQL 이다.

```sql
-- 안티패턴: 리터럴 → 매 실행마다 Hard Parse
SELECT * FROM orders WHERE customer_id = 10021;
-- 개선: 바인드 변수 → 공유 커서 재사용
SELECT * FROM orders WHERE customer_id = :cid;
```

## 4. 바인드 변수와 커서 캐싱

JDBC 에서는 `PreparedStatement` 와 `?` 플레이스홀더로 바인드 변수를 쓰고, 드라이버 Statement Cache 로 세션 레벨에서 파싱을 건너뛴다.

```java
String sql = "SELECT * FROM orders WHERE customer_id = ? AND status = ?";
try (PreparedStatement ps = conn.prepareStatement(sql)) {
	ps.setLong(1, customerId);
	ps.setString(2, status);
	try (ResultSet rs = ps.executeQuery()) {
		// ...
	}
}
```

```properties
oracle.jdbc.implicitStatementCacheSize=50
```

## 5. CURSOR_SHARING 파라미터의 함정

`CURSOR_SHARING=FORCE` 는 리터럴을 시스템 바인드로 치환해 Hard Parse 를 Soft Parse 로 바꿔 경합을 줄인다. 하지만 데이터 분포가 치우친 컸럼에서 바인드 피킹으로 잡은 첫 계획이 고착되어 나쁘 계획이 될 위험이 있다. FORCE 는 최후 수단이고 기본값 EXACT 를 유지하며 애플리케이션에서 바인드 변수를 쓰는 것이 정석이다.

## 6. Wait Event 기반 진단 경로

```sql
SELECT event, COUNT(*) AS sessions
FROM v$session
WHERE status = 'ACTIVE' AND wait_class <> 'Idle'
GROUP BY event ORDER BY sessions DESC;
```

```sql
SELECT name, value FROM v$sysstat
WHERE name IN ('parse count (total)', 'parse count (hard)', 'execute count');
```

```sql
SELECT sql_id, version_count, executions, parse_calls, sql_text
FROM v$sqlarea WHERE version_count > 20 ORDER BY version_count DESC;
```

## 7. 커서 공유 실패 원인 추적

버전 카운트가 높다면 `v$sql_shared_cursor` 에서 공유 실패 사유를 확인한다. BIND_MISMATCH, LANGUAGE_MISMATCH, OPTIMIZER_MODE_MISMATCH 등이 대표적이다. 커넥션 풀 세션 세팅을 통일하는 것이 근본 해결이다.

```sql
SELECT * FROM v$sql_shared_cursor WHERE sql_id = '&target_sql_id';
```

## 8. 종합 튜닝 전략

첫째 바인드 변수로 Hard Parse 를 줄이고(90% 해결), 둘째 Statement Cache 로 Soft Parse 도 생략하며, 셋째 남는 레거시는 FORCE 를 국소 적용하고, 넷째 Shared Pool 크기를 점검한다. 트레이드오프의 핵심은 파싱 비용 vs 계획 품질이며, 정답은 바인드 변수 + 통계 최신화 + 히스토그램으로 좋은 계획을 안전하게 재사용하는 것이다.

## 참고

- Oracle Database Concepts: Memory Architecture, Shared Pool
- Oracle Database Performance Tuning Guide: Tuning the Shared Pool
- Oracle Database Reference: V$SQL_SHARED_CURSOR, V$SQLAREA
- MOS Note: Diagnosing 'library cache: mutex X' waits
