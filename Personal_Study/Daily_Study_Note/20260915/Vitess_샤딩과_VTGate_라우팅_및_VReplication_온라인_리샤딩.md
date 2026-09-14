Notion 원본: https://www.notion.so/3db5a06fd6d381688dcfc60dffff8341

# Vitess 샤딩과 VTGate 라우팅 및 VReplication 온라인 리샤딩

> 2026-09-15 신규 주제 · 확장 대상: MySQL Group Replication과 InnoDB Cluster 및 ProxySQL Read/Write Splitting

## 학습 목표

- 키스페이스·샤드·Vindex 로 구성되는 Vitess 의 데이터 배치 모델을 정리한다
- VTGate 가 쿼리를 단일 샤드 라우팅과 스캐터-개더로 나누는 기준을 판별한다
- VReplication 워크플로로 무중단 리샤딩을 수행하고 트래픽 전환 시점을 통제한다
- 교차 샤드 트랜잭션·시퀀스·온라인 DDL 의 제약을 설계 단계에서 반영한다

## 1. Vitess 가 푸는 문제

MySQL 단일 인스턴스가 감당하지 못하는 규모에 도달하면 선택지는 셋이다. 더 큰 장비(수직 확장), 읽기 복제본 분산(쓰기는 여전히 한 곳), 샤딩(쓰기까지 분산). 앞의 둘이 한계에 닿으면 샤딩인데, 애플리케이션에서 직접 샤딩하면 라우팅·리샤딩·교차 샤드 쿼리·스키마 변경을 전부 애플리케이션이 떠안는다.

Vitess 는 이 계층을 MySQL 프로토콜 프록시로 내려버린다. 애플리케이션은 평범한 MySQL 커넥션을 열고 평범한 SQL 을 보낸다. 그 뒤에서 VTGate 가 쿼리를 파싱해 어느 샤드로 보낼지 결정하고, 여러 샤드의 결과를 합치고, 리샤딩 중에는 라우팅 규칙을 바꿔가며 트래픽을 옮긴다.

```
애플리케이션
    │  MySQL 와이어 프로토콜
    ▼
  VTGate (무상태, 여러 대)  ──── 토폴로지 서비스(etcd) ──── vtctld
    │  gRPC                            │ 키스페이스/샤드/VSchema/서빙 그래프
    ├──────────────┬──────────────┐
    ▼              ▼              ▼
VTTablet+mysqld  VTTablet+mysqld  VTTablet+mysqld
 (shard -80)      (shard 80-)      (unsharded)
```

구성 요소의 역할은 명확히 갈린다. **VTGate** 는 무상태 라우터라 수평 확장이 자유롭다. **VTTablet** 은 각 mysqld 옆에 붙는 사이드카로 커넥션 풀링, 쿼리 통합(dedup), 트랜잭션 타임아웃, 복제 지연 기반 서빙 여부를 관리한다. **토폴로지 서비스**(etcd/ZooKeeper/Consul)는 어떤 샤드가 있고 누가 프라이머리인지를 담는 메타데이터 저장소다. **vtctld** 는 관리 명령과 워크플로 오케스트레이션을 맡는다.

## 2. 키스페이스, 샤드, 키스페이스 ID

**키스페이스(keyspace)** 는 논리 데이터베이스다. 애플리케이션은 `commerce` 라는 키스페이스 하나를 보지만, 실제로는 여러 샤드로 쪼개져 있을 수 있다.

**샤드**는 키스페이스 ID 공간의 구간이다. 이름 자체가 범위 표기다.

```
샤드 이름       키스페이스 ID 범위 (빅엔디안 바이트열 비교)
0               전체 (샤딩되지 않음)
-80             [0x00.., 0x80..)
80-             [0x80.., 끝)
-40, 40-80, 80-c0, c0-    4분할
```

행마다 키스페이스 ID 가 계산되고, 그 값이 속한 구간의 샤드에 행이 저장된다. 계산 방법을 정의하는 것이 **Vindex** 다.

```json
{
  "sharded": true,
  "vindexes": {
    "xxhash": { "type": "xxhash" },
    "order_customer_lookup": {
      "type": "consistent_lookup_unique",
      "params": {
        "table": "commerce_lookup.order_customer_idx",
        "from": "order_id",
        "to": "keyspace_id"
      },
      "owner": "orders"
    }
  },
  "tables": {
    "customers": {
      "column_vindexes": [{ "column": "customer_id", "name": "xxhash" }]
    },
    "orders": {
      "column_vindexes": [
        { "column": "customer_id", "name": "xxhash" },
        { "column": "order_id", "name": "order_customer_lookup" }
      ]
    },
    "product_categories": { "type": "reference" }
  }
}
```

세 가지 구조가 한 번에 나온다.

**Primary Vindex** 는 각 테이블의 첫 번째 `column_vindexes` 로, 행의 키스페이스 ID 를 실제로 결정한다. 위에서 `orders` 의 프라이머리 Vindex 는 `customer_id` 이므로, **한 고객의 주문은 전부 같은 샤드에 모인다**. 이것이 샤딩 설계의 핵심 결정이다. 같이 조회될 데이터를 같은 샤드에 모으는 것을 코로케이션(colocation)이라 하고, 이를 잘못 잡으면 거의 모든 쿼리가 스캐터-개더가 된다.

**Lookup Vindex** 는 프라이머리가 아닌 컬럼으로도 샤드를 찾기 위한 보조 인덱스다. `order_id` 로 주문을 찾는 쿼리는 원래 전 샤드를 뒤져야 하는데, `order_id → keyspace_id` 매핑 테이블을 별도 키스페이스에 두면 두 번의 조회로 단일 샤드에 도달한다. `consistent_lookup_unique` 는 owner 테이블의 INSERT/DELETE 와 같은 트랜잭션에서 매핑을 갱신해 정합성을 유지한다. 대가는 쓰기 경로가 두 배로 무거워지는 것이다.

**Reference 테이블**은 모든 샤드에 복제되는 작은 마스터 데이터다. 조인 시 스캐터를 유발하지 않으려는 목적이고, 갱신 빈도가 낮은 코드 테이블에만 쓴다.

Vindex 선택에서 실무적으로 중요한 점 하나. `hash` 는 초기부터 있던 3DES 기반 함수이고, 신규 구축에는 `xxhash` 가 권장된다. `hash` 는 정수 타입에만 제대로 동작하고 문자열 키에는 부적합하다. UUID 나 문자열 ID 를 샤딩 키로 쓴다면 `xxhash` 를 쓴다.

## 3. VTGate 의 쿼리 라우팅

VTGate 는 SQL 을 파싱해 실행 계획을 만든다. WHERE 절에서 프라이머리 Vindex 컬럼에 대한 등가 조건을 찾으면 키스페이스 ID 를 계산해 **한 샤드로만** 보낸다.

```sql
-- 단일 샤드 (customer_id 로 키스페이스 ID 계산 가능)
SELECT * FROM orders WHERE customer_id = 1234 ORDER BY created_at DESC LIMIT 20;

-- 단일 샤드 (IN 이지만 전부 같은 샤드로 떨어지면 1회, 아니면 해당 샤드들만)
SELECT * FROM customers WHERE customer_id IN (1234, 5678);

-- 룩업 Vindex 경유 → 매핑 조회 1회 + 대상 샤드 1회
SELECT * FROM orders WHERE order_id = 'ord_98765';

-- 스캐터-개더 (Vindex 조건 없음) — 모든 샤드에 보내고 VTGate 가 병합
SELECT COUNT(*) FROM orders WHERE status = 'PENDING';
```

스캐터-개더 자체가 금지는 아니다. 문제는 **비용이 샤드 수에 비례**한다는 점이다. 샤드 64개면 쿼리 하나가 커넥션 64개를 잡고, 그중 가장 느린 하나가 전체 지연을 결정한다. 배치·리포트 성격이면 감수할 수 있지만 사용자 요청 경로에 있으면 안 된다.

VTGate 는 스캐터를 계획 단계에서 식별할 수 있으므로, 운영 정책으로 막을 수 있다.

```
--no_scatter                  스캐터 쿼리 자체를 거부
--warn_sharded_only           계획은 하되 경고 카운터 증가
--max_memory_rows             VTGate 가 메모리에 모으는 행 수 상한
--queryserver-config-query-timeout   VTTablet 쿼리 타임아웃
```

`--warn_sharded_only` 로 먼저 경고를 수집해 어떤 쿼리가 스캐터인지 목록화한 뒤, Vindex 추가나 쿼리 재작성으로 하나씩 없애고 마지막에 `--no_scatter` 로 잠그는 순서가 안전하다.

교차 샤드 조인은 VTGate 가 일부 지원한다. 한쪽 결과를 가져와 다른 쪽 조회의 입력으로 쓰는 형태로 분해하는데, 중간 결과가 크면 `--max_memory_rows` 에 걸려 실패한다. 조인은 가능한 한 같은 샤딩 키를 공유하는 테이블끼리만 하도록 스키마를 설계한다.

## 4. 시퀀스와 AUTO_INCREMENT

샤딩된 테이블에서 `AUTO_INCREMENT` 는 쓸 수 없다. 샤드마다 독립적으로 증가해 충돌한다. Vitess 는 샤딩되지 않은 키스페이스에 시퀀스 테이블을 두고 블록 단위로 ID 를 발급하는 방식을 제공한다.

```sql
-- 언샤드 키스페이스에 생성
CREATE TABLE customer_seq (
  id INT DEFAULT 0,
  next_id BIGINT DEFAULT NULL,
  cache BIGINT DEFAULT NULL,
  PRIMARY KEY (id)
) COMMENT 'vitess_sequence';

INSERT INTO customer_seq (id, next_id, cache) VALUES (0, 1000, 1000);
```

```json
"tables": {
  "customers": {
    "column_vindexes": [{ "column": "customer_id", "name": "xxhash" }],
    "auto_increment": { "column": "customer_id", "sequence": "customer_seq" }
  }
}
```

`cache` 값만큼을 VTGate 가 한 번에 예약해 메모리에서 나눠주므로 발급 비용이 낮다. 대신 VTGate 재시작 시 예약분이 버려져 **ID 에 구멍이 생긴다**. 연속성을 요구하는 업무 규칙이 있다면 별도 채번 테이블을 두어야 한다.

## 5. VReplication — 리샤딩의 엔진

Vitess 의 거의 모든 데이터 이동 워크플로(`MoveTables`, `Reshard`, `Materialize`)는 **VReplication** 위에 얹혀 있다. 원리는 필터링된 복제다. 소스 샤드의 binlog 를 VStream 으로 읽으면서, 대상 샤드에 속하는 행만 골라 적용한다.

워크플로는 두 단계로 나뉜다. **Copy 단계**에서는 기존 행을 청크 단위로 복사하면서, 복사 지점 이후의 binlog 이벤트를 동시에 추적한다. 복사가 끝나면 **Running 단계**로 넘어가 순수 binlog 스트리밍으로 지연을 따라잡는다.

4샤드에서 8샤드로 리샤딩하는 흐름은 다음과 같다.

```bash
# 1) 새 샤드 생성 (tablet 배치는 별도)
vtctldclient Reshard --workflow=r4to8 --target-keyspace=commerce create \
    --source-shards='-40,40-80,80-c0,c0-' \
    --target-shards='-20,20-40,40-60,60-80,80-a0,a0-c0,c0-e0,e0-'

# 2) 진행 상황 확인 — 복사 진행률과 복제 지연
vtctldclient Reshard --workflow=r4to8 --target-keyspace=commerce show

# 3) 데이터 검증 — 소스와 타깃을 행 단위로 비교
vtctldclient VDiff --workflow=r4to8 --target-keyspace=commerce create
vtctldclient VDiff --workflow=r4to8 --target-keyspace=commerce show last

# 4) 읽기 트래픽부터 전환 (rdonly → replica)
vtctldclient Reshard --workflow=r4to8 --target-keyspace=commerce SwitchTraffic \
    --tablet-types=rdonly,replica

# 5) 쓰기 전환 (짧은 쓰기 정지 구간 발생)
vtctldclient Reshard --workflow=r4to8 --target-keyspace=commerce SwitchTraffic \
    --tablet-types=primary

# 6) 문제 없으면 소스 정리 / 문제 있으면 되돌리기
vtctldclient Reshard --workflow=r4to8 --target-keyspace=commerce complete
# vtctldclient Reshard ... ReverseTraffic
```

이 절차의 안전성은 두 가지 장치에서 나온다.

첫째, **역방향 워크플로**다. 쓰기를 전환할 때 Vitess 는 새 샤드에서 옛 샤드로 되돌리는 VReplication 스트림을 자동으로 만든다. 전환 후 문제가 발견되면 `ReverseTraffic` 으로 되돌아갈 수 있고, 그 사이 새 샤드에 쓰인 데이터도 옛 샤드에 반영되어 있다. 이 안전망은 `Complete` 를 실행하는 순간 사라지므로, 관찰 기간을 충분히 두고 마지막 단계를 미룬다.

둘째, **프라이머리 전환 시 저널링**이다. 쓰기 전환은 소스 프라이머리에서 쓰기를 멈추고, 타깃이 마지막 binlog 위치까지 따라잡았는지 확인하고, 라우팅 규칙을 원자적으로 바꾸는 순서로 진행된다. 이 구간에서 쓰기가 잠깐 막힌다. 보통 수백 밀리초에서 수 초이며, `--timeout` 으로 상한을 둘 수 있다. 복제 지연이 크면 이 단계가 타임아웃으로 실패하므로, 전환 전에 지연이 0 에 가까운지 반드시 확인한다.

VDiff 는 생략하고 싶은 유혹이 크지만 생략하면 안 된다. 필터링 조건 오류나 Vindex 불일치로 일부 행이 엉뚱한 샤드로 갔더라도 스트림 자체는 정상으로 보이기 때문이다. 대용량에서는 `--max-extra-rows-to-compare` 와 샘플링 옵션으로 시간을 조절한다.

## 6. 트랜잭션 모델

| 모드 | 동작 | 보장 |
|---|---|---|
| `SINGLE` | 단일 샤드 트랜잭션만 허용, 교차 시 에러 | 완전한 ACID |
| `MULTI` (기본) | 샤드별로 각각 커밋, 2PC 없음 | 부분 커밋 가능 |
| `TWOPC` | 메타데이터 관리자 기반 2단계 커밋 | 원자성 보장, 지연·복잡도 증가 |

기본값 `MULTI` 는 "여러 샤드에 커밋을 순차 전송"이다. 세 번째 샤드 커밋이 실패하면 앞의 두 샤드는 이미 커밋된 상태로 남는다. 즉 **교차 샤드 원자성이 없다**. 이를 모르고 설계하면 정합성 사고가 난다.

현실적인 대응은 두 가지다. 코로케이션을 강화해 트랜잭션이 단일 샤드에 닫히게 스키마를 설계하거나, 교차 샤드 작업을 아웃박스 + 비동기 보상(사가)으로 바꾸는 것이다. `TWOPC` 는 존재하지만 지연 증가와 운영 복잡도가 커서, 정말로 원자성이 필요한 소수 경로에만 선택적으로 쓰는 편이 낫다.

## 7. 온라인 DDL 과 장애 대응

Vitess 는 스키마 변경을 워크플로로 관리한다.

```sql
SET @@ddl_strategy = 'vitess --postpone-completion';
ALTER TABLE orders ADD COLUMN coupon_code VARCHAR(32) NULL;
-- 반환값으로 UUID 가 나온다
SHOW VITESS_MIGRATIONS LIKE '<uuid>';
ALTER VITESS_MIGRATION '<uuid>' COMPLETE;
```

`vitess` 전략은 VReplication 기반 그림자 테이블 방식이고, `gh-ost`·`pt-osc` 전략도 선택할 수 있다. `--postpone-completion` 은 데이터 복사를 미리 다 해두고 최종 컷오버(테이블 스왑)만 사람이 원하는 시각에 실행하게 한다. 트래픽이 적은 시간대에 컷오버를 몰아넣는 실무 패턴이다. 모든 샤드에 대해 병렬로 진행되므로, 샤드가 많을수록 수동 DDL 대비 이득이 크다.

장애 시 프라이머리 교체는 두 명령으로 나뉜다.

```bash
# 계획된 교체 — 현 프라이머리가 살아 있고 데이터 손실 없음
vtctldclient PlannedReparentShard --keyspace-shard commerce/-80 --new-primary zone1-0000000102

# 비상 교체 — 현 프라이머리가 죽음, 가장 앞선 복제본을 승격
vtctldclient EmergencyReparentShard --keyspace-shard commerce/-80
```

데이터 손실 여부는 내구성 정책(durability policy)이 결정한다. `semi_sync` 정책은 최소 한 복제본이 ack 해야 커밋을 확정하므로 ERS 시 손실 없이 승격할 수 있고, `none` 은 비동기라 손실 가능성이 있다. 프로덕션 키스페이스는 semi-sync 계열을 기본으로 둔다.

## 8. 도입 판단

Vitess 는 공짜가 아니다. etcd, vtctld, VTGate, VTTablet 이 추가되고, 스키마 변경·백업·모니터링이 전부 Vitess 방식으로 바뀐다. 운영 인력이 MySQL 하나 보던 수준으로는 감당하기 어렵다.

도입이 정당화되는 지점은 대체로 이렇다. 쓰기 처리량이 단일 프라이머리 한계에 닿았고, 데이터가 자연스러운 샤딩 키(테넌트 ID, 사용자 ID)를 갖고 있으며, 쿼리의 대부분이 그 키를 조건으로 갖는 경우다. 반대로 샤딩 키가 불명확하거나 분석성 전역 조회가 많은 워크로드라면, 샤딩보다 OLAP 저장소 분리나 읽기 복제본 확장이 먼저다.

시작하는 방법도 정해져 있다. 처음부터 샤딩하지 말고 **언샤드 키스페이스로 Vitess 를 먼저 도입**해 VTGate 경유 운영에 익숙해진 뒤, `MoveTables` 로 테이블을 분리하고, 마지막에 `Reshard` 로 쪼갠다. 각 단계가 되돌릴 수 있는 워크플로이므로 점진적 이행이 가능하다.

## 참고

- Vitess 공식 문서 — Concepts(Keyspace, Shard, Vindex), VSchema
- Vitess 공식 문서 — VReplication, Reshard, MoveTables, VDiff
- Vitess 공식 문서 — Transaction Model, Online DDL, Durability Policy
- Vitess 공식 문서 — Reparenting (PlannedReparentShard / EmergencyReparentShard)
- MySQL 공식 문서 — Semisynchronous Replication, Binary Log Formats
