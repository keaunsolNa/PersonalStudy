Notion 원본: https://app.notion.com/p/40e5a06fd6d381ce9875c2f4a03419c6?pvs=204

# Keyset(Seek) 페이지네이션과 OFFSET 비용 비교: 복합 정렬 인덱스 설계·커서 인코딩 및 Spring Data ScrollPosition

> 2026-11-04 신규 주제 · 확장 대상: MySQL

## 학습 목표

- OFFSET 페이지네이션이 깊은 페이지에서 읽는 행 수가 선형으로 늘어나는 이유를 Handler 카운터로 측정한다.
- 정렬 키와 유일 키를 묶은 복합 인덱스를 설계하고 EXPLAIN으로 접근 방식을 확인한다.
- 행 값 비교와 OR 전개 두 가지 keyset 조건의 실행 계획 차이를 구분한다.
- 서명된 불투명 커서를 인코딩·검증하고 Spring Data의 ScrollPosition 모델에 대응시킨다.

## 1. OFFSET은 건너뛰는 것이 아니라 읽고 버린다

`LIMIT 20 OFFSET 100000`은 100,020번째 행까지 정렬 순서대로 전부 만들어 낸 뒤 앞의 100,000개를 버리는 연산이다. 인덱스가 정렬을 대신해 주더라도 B-Tree 리프를 100,000개 따라 걸어야 하고, SELECT 목록에 인덱스에 없는 컬럼이 있으면 그 행마다 클러스터드 인덱스 조회가 추가로 붙는다. 따라서 비용은 페이지 번호에 비례한다. 첫 페이지는 빠르고 뒤로 갈수록 느려지는 이 현상은 무한 스크롤이나 배치 순회처럼 끝까지 가는 사용 패턴에서 치명적이다.

Keyset(Seek) 방식은 "직전 페이지의 마지막 행 값"을 조건으로 넘겨 인덱스의 그 위치에서 곧바로 시작한다. 건너뛸 행이 없으므로 비용이 페이지 위치와 무관하게 LIMIT에 비례한다. 대신 임의 페이지 번호로 점프할 수 없고 이전/다음 이동만 가능하다는 대가를 치른다. 이 글은 이 두 방식을 같은 데이터에서 실측해 비교한다. 이미 다룬 InnoDB 락이나 Redo/Undo와 달리, 여기서는 읽기 경로의 행 방문 수만 다룬다.

## 2. 실험 환경과 검증 범위

MySQL 서버는 apt 저장소에 없어서 MariaDB 10.11.14(InnoDB, 페이지 16KB)를 설치해 실측했다. 즉 MySQL 8이 아니라 MariaDB로 검증했으며, 옵티마이저 세부(특히 5절의 행 값 비교)는 MySQL 8과 다를 수 있다. 데이터는 100만 행이며 초당 3건이 생성된 것처럼 같은 `created_at`을 가진 행이 3개씩 존재해 동률 상황을 일부러 만들었다. 서로 다른 `created_at`은 333,334개였다.

```sql
CREATE TABLE post(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME NOT NULL,
  author_id INT NOT NULL,
  title VARCHAR(100) NOT NULL,
  body VARCHAR(200) NOT NULL
) ENGINE=InnoDB;

SET max_recursive_iterations=2000000;
INSERT INTO post(created_at,author_id,title,body)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<1000000)
SELECT '2026-01-01 00:00:00' + INTERVAL (i DIV 3) SECOND, i%5000,
       CONCAT('title-',i), REPEAT('x',150) FROM n;

CREATE INDEX idx_created_id ON post(created_at, id);
```

측정은 pymysql로 같은 쿼리를 5회 실행해 중앙값을 취했고, 방문 행 수는 실행 직전 `FLUSH STATUS` 후 `Handler_read_*` 합계로 구했다. 서버는 로컬 소켓이고 버퍼 풀에 전부 올라온 웜 상태라 디스크 I/O가 개입하지 않은 값이다. 실제 서비스에서 콜드 페이지가 섞이면 격차는 더 커진다.

## 3. 실측: 페이지 위치에 따른 비용

정렬은 `ORDER BY created_at DESC, id DESC LIMIT 20`이다. keyset 커서는 OFFSET 직전 행의 값을 사용해 결과가 동일한지도 함께 검증했다. 세 번째 열의 OR 전개 조건은 `created_at < ? OR (created_at = ? AND id < ?)`이다.

| OFFSET | OFFSET 쿼리 (ms / Handler 합) | 행 값 비교 keyset | OR 전개 keyset | 결과 동일 |
|---|---|---|---|---|
| 0 | 0.31 / 20 | 해당 없음 | 해당 없음 | 해당 없음 |
| 1,000 | 2.40 / 1,020 | 1.49 / 1,020 | 0.39 / 21 | True |
| 10,000 | 10.09 / 10,020 | 11.14 / 10,020 | 0.34 / 21 | True |
| 100,000 | 589.02 / 1,000,001 | 107.74 / 100,020 | 0.27 / 21 | True |
| 500,000 | 864.28 / 1,000,001 | 974.27 / 500,020 | 0.33 / 21 | True |
| 900,000 | 654.12 / 1,000,001 | 1951.68 / 900,020 | 0.36 / 21 | True |

OR 전개 keyset은 어느 위치에서도 21번의 핸들러 호출(시작 위치 탐색 1회와 20행)로 끝났고 시간은 0.27~0.39ms로 평평했다. OFFSET 쿼리의 Handler 합이 1,000,001로 고정된 구간은 옵티마이저가 인덱스를 포기하고 풀 스캔과 filesort로 전환한 경우다. 이 전환은 6절 EXPLAIN에서 확인한다. 행 값 비교 keyset의 방문 수가 OFFSET 위치와 거의 같다는 점이 의외의 결과인데, 5절에서 설명한다. 시간은 웜 캐시 단일 연결의 값이라 절대치보다 증가 추세를 보는 것이 맞다.

## 4. 복합 정렬 인덱스 설계

keyset의 정확성은 정렬이 전순서(total order)여야 성립한다. `created_at`만으로 정렬하면 같은 시각의 행들 사이에 순서가 정의되지 않아, 마지막 행의 `created_at`을 `<`로 넘기는 순간 동률 행이 통째로 사라진다. 실제로 `created_at < 마지막값`만으로 20행씩 50번 읽은 결과, 상위 1,000행 중 954행만 읽혔고 46행이 누락되었다. 해법은 유일 키 `id`를 정렬 끝에 덧붙인 `(created_at, id)`를 정렬 키이자 인덱스로 쓰는 것이다. 이렇게 하면 같은 방식으로 250페이지(5,000행)를 순회한 결과가 `ORDER BY created_at DESC, id DESC LIMIT 5000`과 정확히 일치했다.

InnoDB 보조 인덱스의 리프에는 PK가 자동으로 붙으므로 `(created_at)` 인덱스도 내부적으로는 `(created_at, id)` 순이다. 그럼에도 인덱스를 `(created_at, id)`로 명시하면 옵티마이저가 정렬 키 전체를 인덱스로 인식해 확실히 filesort를 피한다. 필터가 있다면 등치 조건 컬럼을 앞에 둔다. 예를 들어 `WHERE author_id = ?` 피드라면 `(author_id, created_at, id)`가 되어야 하며, 범위 조건 컬럼 뒤의 컬럼은 정렬에 쓰지 못한다는 복합 인덱스의 기본 규칙이 그대로 적용된다. 정렬 방향이 섞이면(`created_at DESC, id ASC`) 인덱스도 같은 방향으로 만들어야 하는데, 내림차순 인덱스는 MySQL 8.0부터 실제로 지원된다는 것이 공식 문서의 설명이며 이 부분은 직접 검증하지 못했다. 가능하면 방향을 통일하는 편이 안전하다.

## 5. 행 값 비교와 OR 전개의 실행 계획

가독성만 보면 `WHERE (created_at, id) < (?, ?)`가 가장 깔끔하다. 그러나 이 조건을 MariaDB 10.11에서 EXPLAIN한 결과는 아래와 같았다.

```text
-- WHERE (created_at,id) < ('2026-01-03 00:00:00',500000) ...
type=index  key=idx_created_id  rows=20      Extra=Using where
-- WHERE created_at<'2026-01-03 00:00:00' OR (created_at=... AND id<500000) ...
type=range  key=idx_created_id  rows=493395  Extra=Using where
```

행 값 비교는 `range`가 아니라 `index` 접근이어서, 인덱스의 끝에서부터 조건을 만족할 때까지 걸어 내려간다. 3절에서 방문 수가 OFFSET과 비슷하게 나온 이유다. 반면 OR 전개는 `range`로 계획되고 실제로는 시작 위치를 바로 찾는다. 옵티마이저가 보여 주는 `rows=493395`는 range 전체의 추정치일 뿐 LIMIT 20에서 멈추므로 실제 Handler 호출은 21번이었다. 추정 rows를 실제 비용으로 읽으면 안 된다는 예시이기도 하다.

MySQL 8.0 공식 문서는 행 생성자 비교를 range 접근에 사용할 수 있다고 설명하지만, 이 문서의 실험은 MariaDB에서만 했고 MySQL 8에서 같은 쿼리가 인덱스 시작 위치를 곧바로 찾는지는 직접 검증하지 못했다. 따라서 운영 DB가 MySQL이든 MariaDB든 자기 버전에서 EXPLAIN과 `Handler_read_*`를 확인하기 전에는 이식성이 높은 OR 전개 형태를 기본으로 두는 편이 안전하다. 정렬 컬럼이 3개 이상이면 OR 항이 늘어나므로 쿼리 생성 코드를 한 곳에 모아야 한다.

## 6. OFFSET 계획이 바뀌는 지점과 정확성 비교

OFFSET 100,000의 기본 계획과 `FORCE INDEX`를 건 계획은 다음과 같이 달랐다.

```text
기본:        type=ALL   key=NULL            rows=986789  Extra=Using filesort
FORCE INDEX: type=index key=idx_created_id rows=100020
-- ANALYZE: r_rows=100020, pages_accessed=300180, r_total_time_ms=227.46
```

옵티마이저는 OFFSET이 커지자 인덱스 순회 비용(보조 인덱스 순회 후 행 조회)이 더 크다고 보고 풀 스캔과 filesort를 골랐고, 그 결과 3절의 1,000,001 방문이 나왔다. 인덱스를 강제하면 100,020행 방문으로 줄었지만 pages_accessed는 300,180로, 행을 한 번 건너뛸 때마다 약 3개의 페이지를 만졌다. 어느 쪽이든 선형 증가를 피하지 못한다.

정확성도 다르다. 1페이지를 읽은 뒤 최신 시각으로 5건이 삽입되면, OFFSET 20의 2페이지는 1페이지와 5행이 겹쳤고(밀려 내려온 행의 중복 노출), keyset 2페이지는 겹침이 0이었다. 삭제가 끼면 반대로 OFFSET은 행을 건너뛴다. 이 실험은 트랜잭션 안에서 삽입한 뒤 롤백했다. keyset도 정렬 키가 바뀌는 수정(예: `updated_at` 기준 정렬)에는 같은 문제가 남는다. 정렬 키는 불변이어야 한다.

## 7. 커서 인코딩

클라이언트에는 `(created_at, id)` 값을 그대로 노출하지 말고 하나의 불투명 토큰으로 감싼다. 형식을 바꿀 때 하위 호환을 지키고, 사용자가 임의 값을 조작해 다른 사용자의 범위를 탐색하거나 인덱스에 불리한 값을 넣지 못하게 하려는 목적이다. 아래 구현은 JSON을 base64url로 인코딩하고 앞 12바이트의 HMAC-SHA256 서명을 붙인다. 실제 실행 결과는 코드 뒤에 있다.

```python
import base64, json, hmac, hashlib
KEY = b"server-secret"
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=')
def enc(ts, id_, sort="created_at:desc"):
    body = b64(json.dumps({"v":1,"s":sort,"ts":ts,"id":id_}, separators=(',',':')).encode())
    sig = b64(hmac.new(KEY, body, hashlib.sha256).digest()[:12])
    return (body + b'.' + sig).decode()
def dec(tok):
    body, sig = tok.encode().split(b'.')
    if not hmac.compare_digest(sig, b64(hmac.new(KEY, body, hashlib.sha256).digest()[:12])):
        raise ValueError("bad signature")
    return json.loads(base64.urlsafe_b64decode(body + b'=' * (-len(body) % 4)))
```

```text
eyJ2IjoxLCJzIjoiY3JlYXRlZF9hdDpkZXNjIiwidHMiOiIyMDI2LTAxLTAzIDAwOjAwOjAwIiwiaWQiOjUwMDAwMH0.ufN4H2SqLz7XxEyu  (108자)
{'v': 1, 's': 'created_at:desc', 'ts': '2026-01-03 00:00:00', 'id': 500000}
변조: bad signature
```

토큰에 정렬 식별자 `s`와 버전 `v`를 넣는 이유는 정렬 기준이 바뀐 뒤 옛 커서가 들어왔을 때 조용히 엉뚱한 결과를 내는 대신 거절하기 위해서다. 서명은 기밀성이 아니라 무결성만 보장한다. 값을 숨겨야 한다면 암호화가 필요하고, 그 대가로 토큰 길이와 키 관리 부담이 늘어난다. 서버가 상태를 갖지 않는다는 점은 keyset 커서의 장점이며, 토큰이 약 108자로 짧아 URL 쿼리에 그대로 실을 수 있다.

## 8. Spring Data의 ScrollPosition

이 절은 Spring Data 공식 문서(3.1 이후의 Scroll API)를 기준으로 설명하며, 위 MariaDB 실험과 달리 Spring 코드는 이 환경에서 실행해 검증하지 못했다. 핵심 타입은 세 가지다. `ScrollPosition`은 현재 위치를 나타내는 인터페이스이고, 구현체로 오프셋 기반의 `OffsetScrollPosition`과 키셋 기반의 `KeysetScrollPosition`이 있다. 결과는 `Window<T>`로 돌아오며, 여기서 `hasNext()`와 `positionAt(index)`로 다음 위치를 얻는다. 리포지토리 메서드는 `ScrollPosition` 인자를 받고 `Window<T>`를 반환하는 파생 쿼리로 선언한다.

```java
interface PostRepository extends JpaRepository<Post, Long> {
  Window<Post> findFirst20ByOrderByCreatedAtDescIdDesc(ScrollPosition position);
}

Window<Post> w = repo.findFirst20ByOrderByCreatedAtDescIdDesc(ScrollPosition.keyset());
while (true) {
  w.forEach(this::handle);
  if (!w.hasNext()) break;
  w = repo.findFirst20ByOrderByCreatedAtDescIdDesc(w.positionAt(w.size() - 1));
}
```

`ScrollPosition.keyset()`은 처음부터 시작하는 위치이고, `positionAt`이 돌려주는 `KeysetScrollPosition`에는 마지막 행의 정렬 키 값들이 담긴다. 프레임워크는 이를 앞서 5절에서 본 것과 같은 `a < ? OR (a = ? AND b < ?)` 형태의 조건으로 전개한다는 것이 공식 문서의 설명이다. 따라서 쿼리 메서드의 정렬에는 반드시 유일 속성(여기서는 `id`)이 포함되어야 4절의 동률 누락 문제를 피한다. `Window`는 직렬화 가능한 커서를 제공하지 않으므로 7절의 토큰으로 `KeysetScrollPosition`의 키 맵을 감싸는 변환 계층은 직접 만들어야 한다. 또한 `OffsetScrollPosition`을 쓰면 편하지만 3절의 선형 증가 비용을 그대로 갖는다는 점은 기억해야 한다. 이 절의 API 이름과 동작은 문서 기준이며 버전에 따라 달라질 수 있으니 사용 중인 Spring Data 버전의 Javadoc으로 확인한다.

## 9. 선택 기준과 한계

정리하면 keyset은 "마지막 위치에서 이어서 읽기"가 주된 패턴일 때 압도적으로 유리하고, 임의 페이지 점프나 전체 건수 표시가 필요한 관리자 화면에서는 OFFSET이 여전히 맞다. 현실적인 절충은 앞쪽 몇십 페이지까지는 OFFSET을 허용하되 그 이상은 막거나 keyset으로 유도하는 것이다. 이번 측정에서 OFFSET 1,000은 2.40ms, 10,000은 10.09ms로 체감하기 어려웠으나 100,000부터는 589ms로 급격히 나빠졌다. 전체 건수가 필요하면 별도 COUNT 쿼리를 캐시하거나 근사치를 쓴다.

| 항목 | OFFSET | Keyset(OR 전개) |
|---|---|---|
| 깊은 페이지 비용 | 위치에 비례 (실측 최대 100만 행 방문) | LIMIT에 비례 (실측 21회) |
| 페이지 점프 | 가능 | 불가 (이전/다음만) |
| 삽입·삭제 중 안정성 | 중복·누락 발생 (실측 5건 중복) | 안정 |
| 정렬 요구사항 | 없음 | 유일 키를 포함한 전순서, 불변 키 |
| 구현 복잡도 | 낮음 | 커서 인코딩과 조건 전개 필요 |

한계로, 정렬 컬럼이 NULL을 허용하면 비교 연산이 UNKNOWN이 되어 조건이 깨지므로 NOT NULL로 두거나 COALESCE한 값을 인덱스 대상으로 삼아야 한다. 실험은 단일 웜 연결이어서 동시성과 콜드 캐시 효과를 포함하지 않았고, MySQL 8 InnoDB와 MariaDB의 옵티마이저 차이는 검증하지 못했다.

## 참고

- MySQL 8.0 Reference Manual, Row Constructor Expression Optimization
- MySQL 8.0 Reference Manual, LIMIT Query Optimization
- MySQL 8.0 Reference Manual, Descending Indexes
- MariaDB Documentation, ANALYZE Statement
- Spring Data Commons Reference, Scroll API (ScrollPosition, KeysetScrollPosition, Window)
- Markus Winand, SQL Performance Explained, "Paging Through Results"
