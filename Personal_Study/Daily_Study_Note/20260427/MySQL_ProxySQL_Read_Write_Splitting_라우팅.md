Notion 원본: https://www.notion.so/34f5a06fd6d3817694efea09590f8797

# MySQL ProxySQL 기반 Read/Write Splitting과 쿼리 라우팅

> 2026-04-27 신규 주제 · 확장 대상: MySQL / 분산 시스템

## 학습 목표

- ProxySQL 의 query rule 기반 라우팅 모델과 mysql_query_rules 테이블 구조를 파악한다
- Read/Write splitting 에서 발생하는 replication lag 함정과 sticky session 패턴을 이해한다
- multi-source replication 환경에서 트래픽을 어떻게 분배하는지 실제 설정으로 확인한다
- ProxySQL 의 connection pool 동작과 query cache 가 성능에 미치는 영향을 정량으로 본다

## 1. ProxySQL 이 풀려는 문제

MySQL replication 으로 read replica 를 여러 대 띄우면 트래픽 분산은 가능해지지만, "어느 쿼리를 어느 노드로 보낼 것인가" 라는 라우팅 문제가 애플리케이션에 떨어진다. JDBC URL 에 multi-host 를 적거나, Spring 의 AbstractRoutingDataSource 를 써서 read-only 트랜잭션을 replica 로 보내는 방식이 일반적이다.

이 모델은 두 가지 한계가 있다. 첫째, 라우팅 정책을 변경하려면 애플리케이션 재배포가 필요하다. 둘째, replica 1대가 죽었을 때 healthcheck/failover 를 애플리케이션이 알아서 한다. 인스턴스가 수십 개 있는 환경에서는 부담이 된다.

ProxySQL 은 MySQL 프로토콜을 그대로 이해하는 L7 프록시다. 애플리케이션은 ProxySQL 만 보고, ProxySQL 이 query rule 에 따라 적절한 노드로 라우팅한다. 라우팅 정책은 SQL 명령으로 런타임에 변경 가능하고, healthcheck/failover/connection multiplexing 같은 부가 기능을 모두 ProxySQL 에서 처리한다.

## 2. hostgroup 모델

ProxySQL 의 핵심 개념은 hostgroup 이다. 하나의 hostgroup 은 같은 역할을 하는 MySQL 노드들의 묶음이다. 일반적으로 다음과 같이 구성한다.

| hostgroup_id | 역할 | 멤버 |
| --- | --- | --- |
| 10 | writer | primary 1대 |
| 20 | reader | replica 2~N대 |
| 30 | offline_writer | failover 후보 |
| 40 | offline_reader | replica from another shard |

mysql_servers 테이블에 hostgroup 별 노드를 등록한다.

```sql
-- ProxySQL admin 인터페이스(6032 포트) 에서
INSERT INTO mysql_servers (hostgroup_id, hostname, port, max_connections, weight)
VALUES
  (10, 'mysql-primary.internal', 3306, 200, 1000),
  (20, 'mysql-replica-1.internal', 3306, 400, 1000),
  (20, 'mysql-replica-2.internal', 3306, 400, 1000),
  (20, 'mysql-replica-3.internal', 3306, 400, 500);  -- 약한 노드는 weight 절반

LOAD MYSQL SERVERS TO RUNTIME;
SAVE MYSQL SERVERS TO DISK;
```

weight 는 read 라우팅 시 가중 round-robin 의 가중치다. CPU 가 약한 노드, 네트워크 RTT 가 긴 노드는 weight 를 낮춘다.

## 3. query rule 라우팅

ProxySQL 의 라우팅은 mysql_query_rules 테이블의 정규식 매칭으로 동작한다. 우선순위(rule_id 오름차순) 순으로 적용한다.

```sql
INSERT INTO mysql_query_rules (rule_id, active, match_pattern, destination_hostgroup, apply)
VALUES
  -- SELECT 는 모두 reader 로
  (100, 1, '^SELECT', 20, 1),
  -- 단, FOR UPDATE / LOCK IN SHARE MODE 는 writer 로 강제
  (90,  1, 'FOR UPDATE|LOCK IN SHARE MODE', 10, 1),
  -- BEGIN/COMMIT 같은 트랜잭션 시작은 writer 로
  (80,  1, '^BEGIN|^START TRANSACTION', 10, 1),
  -- 명시적 SELECT /*+ writer */ 힌트는 writer 로
  (70,  1, '/\\*\\+ writer \\*/', 10, 1);

LOAD MYSQL QUERY RULES TO RUNTIME;
```

apply=1 이면 매칭 후 룰 평가를 멈추고 destination 으로 보낸다. apply=0 이면 다음 룰도 검사한다(rewrite 규칙 등).

주의할 부분은 트랜잭션 안에서의 동작이다. ProxySQL 5+ 의 기본은 "transaction 시작 후에는 같은 hostgroup 을 stick 한다" 이다. 즉 BEGIN 으로 writer 에 붙으면 COMMIT 까지 모든 SELECT 도 writer 로 간다. 이를 ON 으로 두면 read-after-write 일관성이 보장되지만 writer 부하가 늘어난다.

`mysql-default_transaction_persistent` 변수가 이 동작을 제어한다.

## 4. read-after-write 일관성과 lag 처리

가장 빈번한 운영 이슈가 "방금 저장한 데이터가 화면에 안 보인다" 다. writer 에 INSERT/UPDATE 하고 직후 SELECT 가 reader 로 가면 replication lag 만큼 옛날 값이 돌아올 수 있다.

해결 방법은 4 가지다.

첫째, **세션 일관성 — sticky session**. 같은 세션의 모든 쿼리를 writer 로 보낸다. 단순하지만 read 분산 효과가 떨어진다.

둘째, **lag 기반 라우팅**. ProxySQL 의 mysql_replication_hostgroups 와 max_replication_lag 설정으로 lag 가 임계 이상인 replica 를 자동으로 빼낸다.

```sql
INSERT INTO mysql_replication_hostgroups (writer_hostgroup, reader_hostgroup, check_type)
VALUES (10, 20, 'read_only');

UPDATE mysql_servers
   SET max_replication_lag = 5
 WHERE hostgroup_id = 20;

LOAD MYSQL SERVERS TO RUNTIME;
```

`max_replication_lag = 5` 는 5초 이상 lag 인 replica 를 일시적으로 reader hostgroup 에서 제외한다. ProxySQL 은 `SHOW SLAVE STATUS` 를 주기적으로 폴링하여 Seconds_Behind_Master 를 본다.

셋째, **GTID 기반 일관성 라우팅**. 클라이언트가 직전 write 의 GTID 를 알고 있으면, ProxySQL 의 `mysql-gtid_strict_mode` 와 `WSREP_SYNC_WAIT` 같은 메커니즘을 이용해 reader 가 해당 GTID 까지 따라잡았는지 확인 후 read 한다. 정확하지만 latency 가 늘어난다.

넷째, **명시적 hint**. 애플리케이션이 read-after-write 가 필요한 곳에는 `/*+ writer */` 같은 ProxySQL 인지 hint 를 넣어 강제로 writer 로 보낸다.

```java
// Spring 에서 hint 사용
@Repository
public class OrderJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    public Order findByIdAfterWrite(long id) {
        return jdbcTemplate.queryForObject(
            "/*+ writer */ SELECT * FROM orders WHERE id = ?",
            (rs, n) -> mapOrder(rs),
            id
        );
    }
}
```

## 5. connection multiplexing

ProxySQL 의 큰 장점은 backend 연결을 여러 frontend 세션이 공유하는 multiplexing 이다. 일반 JDBC pool 은 client 가 connection 을 점유하는 동안 backend 도 점유된다. ProxySQL 은 트랜잭션 경계나 prepared statement 같은 stateful 컨텍스트가 없는 idle 시간에 backend 연결을 다른 client 와 공유한다.

이 결과로 backend MySQL 의 max_connections 를 훨씬 적게 잡고도 같은 throughput 을 낸다. 실측 예: 애플리케이션 인스턴스 50대, 각 HikariCP 20 = 1000 frontend connection. 일반적이라면 MySQL 도 1000+ connection 이 필요하지만, ProxySQL 을 끼면 backend 100~150 connection 으로 충분한 경우가 많다.

multiplexing 이 비활성화되는 조건은 다음과 같다. 이를 모르면 최적화 효과가 안 나온다.

- 트랜잭션 진행 중(BEGIN ~ COMMIT 사이)
- 현재 세션에 user variable(`SET @x = 1`) 이 살아 있음
- 명시적 LOCK TABLES
- 임시 테이블 존재
- prepared statement 가 진행 중
- 일부 SQL_MODE 변경

이를 확인하려면 stats_mysql_processlist 또는 stats_mysql_connection_pool 을 본다.

```sql
SELECT srv_host, status, ConnUsed, ConnFree, Queries, Bytes_data_sent
FROM stats_mysql_connection_pool;
```

## 6. query cache

ProxySQL 은 결과 자체를 캐시할 수 있다. cache_ttl(밀리초)을 query rule 에 지정하면 같은 쿼리를 그 시간 동안 메모리에서 응답한다.

```sql
UPDATE mysql_query_rules
   SET cache_ttl = 1000
 WHERE rule_id = 100
   AND match_pattern = '^SELECT .* FROM products WHERE category';

LOAD MYSQL QUERY RULES TO RUNTIME;
```

이 기능은 짧은 TTL 로 hot read 를 흡수할 때 강력하다. 단점은 cache invalidation 이 약하다는 것이다. write 가 들어와도 cache 는 TTL 까지 무효화되지 않는다. 그래서 일반적으로 5초 이하 TTL 만 설정하고, application 레벨 캐시(Redis 등)와 보완 관계로 본다.

실측: 카탈로그 페이지에서 카테고리별 상품 목록 SELECT 를 cache_ttl=2000 으로 두자 backend QPS 가 80% 떨어졌다. 데이터 조금 stale 한 것이 허용되는 read-heavy 페이지에 적합하다.

## 7. ProxySQL Cluster 와 HA

운영에서 ProxySQL 자체가 SPOF 가 되면 안 된다. 일반적으로 ProxySQL 을 2~3대 띄우고 그 앞에 keepalived/VIP 또는 EKS 의 NLB 같은 L4 분산을 둔다.

ProxySQL Cluster 는 여러 ProxySQL 인스턴스가 설정을 자동 동기화하는 기능이다. 한 노드에서 LOAD MYSQL QUERY RULES TO RUNTIME 을 실행하면 epoch 가 올라가고, 다른 노드들이 이를 감지해서 fetch 한다.

```cnf
# /etc/proxysql.cnf 의 cluster 설정
admin_variables=
{
    cluster_username="cluster_admin"
    cluster_password="cluster_pass"
    cluster_check_interval_ms=1000
    cluster_mysql_query_rules_save_to_disk=true
    cluster_mysql_servers_save_to_disk=true
    cluster_mysql_users_save_to_disk=true
    cluster_proxysql_servers_save_to_disk=true
}

proxysql_servers=
(
    {hostname="proxysql-1.internal" port=6032 weight=1 comment="node1"},
    {hostname="proxysql-2.internal" port=6032 weight=1 comment="node2"},
    {hostname="proxysql-3.internal" port=6032 weight=1 comment="node3"}
)
```

각 노드에서 cluster_admin 으로 admin 포트(6032) 접속이 가능해야 한다.

## 8. 운영에서 자주 만나는 함정

ProxySQL 운영 중 나오는 문제 패턴을 정리한다.

첫째, **prepared statement 의 multiplexing 비활성화로 인한 backend 폭주**. ORM 이 모든 쿼리를 PREPARE/EXECUTE 로 보내면 ProxySQL 의 multiplexing 효과가 거의 사라진다. ProxySQL 2.x 부터 prepared statement 의 connection re-use 가 가능해졌지만 호환성 이슈가 있어 일부 환경에서는 disable 한다. JDBC URL 에 `useServerPrepStmts=false` 를 두는 것이 안전한 기본값이다.

둘째, **transaction_persistent 를 OFF 로 설정해서 읽기/쓰기 일관성 깨짐**. 이 옵션을 OFF 로 두고 BEGIN 후 SELECT 가 reader 로 가버리면 자기가 방금 쓴 데이터를 못 본다. 명시적 hint 없으면 ON 이 안전한 기본값이다.

셋째, **healthcheck 쿼리(default `SELECT 1`)가 반영하지 않는 lag**. healthcheck 가 통과해도 lag 는 클 수 있다. max_replication_lag 와 monitor 모듈을 함께 활성화해야 한다.

넷째, **stats_history 테이블의 disk 사용량**. ProxySQL 이 자체 메트릭을 기록하는데 장기간 운영하면 stats DB 가 커진다. cron 으로 prune 하거나 외부 모니터링(Prometheus exporter)으로 옮긴다.

## 9. JDBC pool 과 ProxySQL pool 의 trade-off

이중 pool(애플리케이션 HikariCP + ProxySQL pool) 환경에서 어느 쪽 사이즈를 어떻게 잡을지가 흔한 질문이다.

HikariCP 는 latency 의 lower bound 를 결정한다. 너무 작으면 connection 획득 대기가 발생한다. 너무 크면 ProxySQL frontend 의 메모리만 차지하고 multiplexing 이익을 누리지 못한다. 권장은 P99 동시 요청 수의 1.2 배 정도.

ProxySQL backend pool(mysql_servers.max_connections)은 MySQL max_connections 의 80% 정도를 hostgroup 별로 분배. 한 ProxySQL 인스턴스가 모든 connection 을 점유하지 않도록 cluster 안의 모든 인스턴스가 합쳐서 80% 가 되도록 계산한다.

```text
예시:
- MySQL primary max_connections = 800
- ProxySQL 인스턴스 3개
- ProxySQL 인스턴스 1개당 max_connections (hostgroup_id=10) = (800 * 0.8) / 3 ≈ 213
```

## 참고

- ProxySQL 공식 문서: https://proxysql.com/documentation/
- ProxySQL Wiki — Query Rules: https://github.com/sysown/proxysql/wiki/Query-Rules
- Percona Blog, "Read-Write Splitting with ProxySQL" 시리즈
- Render Engineering, "We replaced HAProxy with ProxySQL"
- High Performance MySQL 4판 — Replication 챕터
