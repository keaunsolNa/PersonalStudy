Notion 원본: https://www.notion.so/3505a06fd6d381fcaf78d1fcf2896180

# AWS Aurora Global Database — Cross-Region Failover RPO / RTO 설계

> 2026-04-28 신규 주제 · 확장 대상: AWS

## 학습 목표

- Aurora Global Database의 storage-level replication과 logical replica의 차이를 데이터 흐름으로 설명한다.
- managed planned failover, unplanned detach-and-promote 두 시나리오의 RPO/RTO를 수치로 산정한다.
- Route 53 Application Recovery Controller 또는 RDS Proxy를 활용한 endpoint 전환 전략을 코드로 구성한다.
- 실제 장애 훈련(GameDay) 절차와 검증 체크리스트를 작성한다.

## 1. Aurora Global Database 구조

Aurora Global Database는 하나의 primary region과 최대 5개의 secondary region으로 구성된다. 각 region에는 Aurora cluster 가 있고, 그 cluster는 storage volume(6-way replicated, 3 AZ)을 가진다. primary region의 storage layer 가 redo log를 secondary region으로 비동기로 송신하며, secondary cluster의 storage layer 가 그 redo를 적용해 read replica를 항상 따라잡게 한다. 즉 logical replication 처럼 SQL 단위가 아니라 **storage block 단위**로 복제된다. 이 때문에 binlog/WAL replication slot 같은 개념이 없고, 트랜잭션 충돌이나 row lock 경합이 secondary 측에 누적되지 않는다.

평균 lag은 1초 미만, 99 백분위수는 약 1~2초로 발표되어 있으며 실제 운영에서는 region pair (us-east-1 ↔ us-west-2 등)마다 다르다. lag은 다음 CloudWatch 지표로 모니터링한다.

| 지표 | 설명 |
| --- | --- |
| AuroraGlobalDBReplicationLag | secondary 가 primary 대비 뒤처진 시간(ms) |
| AuroraGlobalDBProgressLag | 로그 적용 진척 시간 |
| AuroraGlobalDBRPOLag | RPO 추정치(ms) |
| AuroraGlobalDBDataTransferBytes | redo 전송 바이트 |

## 2. Failover 시나리오 두 가지

**Managed Planned Failover** 는 5.7 기준 2021년에 GA된 기능으로, 무손실(0 RPO)을 약속한다. 절차는 (1) primary가 secondary 의 lag 가 0으로 수렴할 때까지 새 쓰기를 차단, (2) 두 region의 endpoint 역할을 바꿈, (3) 기존 secondary 가 primary 가 되고 기존 primary 가 secondary 로 join. 일반적으로 RTO 60~120초. region 간 의도적 전환(예: 주기적 DR 훈련)에 쓴다.

**Unplanned Detach-and-Promote** 는 primary region 전체가 사용 불가능해진 진짜 재해 상황이다. 콘솔/API에서 secondary 클러스터를 "Remove from Global Database"하면 standalone 클러스터로 전환되고, 곧바로 writer endpoint 가 활성화된다. 이때 RPO는 직전 lag에 의존하며 RTO는 5~10분이 일반적이다. detach 이후에는 기존 primary region 이 복구되어도 자동으로 다시 합류하지 않는다. 새 region pair 를 다시 셋업해야 한다.

| 시나리오 | RPO | RTO | 자동/수동 |
| --- | --- | --- | --- |
| Managed Planned Failover | 0 | 60~120s | API 호출 (수동) |
| Unplanned Detach-and-Promote | 직전 lag (보통 <2s) | 5~10분 | 수동 또는 ARC 자동 |
| 동일 region multi-AZ failover | 0 | 30~60s | 자동 |

## 3. Endpoint 전환 — Route 53 ARC

Route 53 Application Recovery Controller (ARC)의 Routing Control 기능을 쓰면, ARC API 호출 한 번으로 traffic을 region 간에 swap 할 수 있다. ARC cluster는 5 region에 분산된 control plane이라 한 region이 죽어도 동작한다.

```bash
aws route53-recovery-control-config create-routing-control \
  --cluster-arn arn:aws:route53-recovery-control::123:cluster/xxx \
  --control-panel-arn arn:aws:route53-recovery-control::123:controlpanel/yyy \
  --routing-control-name primary-us-east-1

aws route53-recovery-control-config create-safety-rule \
  --assertion-rule '{
      "Name": "exactly-one-active",
      "AssertedControls": ["arn:.../primary-us-east-1","arn:.../primary-us-west-2"],
      "WaitPeriodMs": 5000,
      "RuleConfig": {"Type":"ATLEAST","Threshold":1,"Inverted":false}
  }'
```

DNS 레코드는 두 region 별 endpoint를 가리키는 health-checked failover record로 구성한다.

```hcl
resource "aws_route53_record" "writer" {
  zone_id = var.zone
  name    = "writer.example.com"
  type    = "CNAME"
  ttl     = 5
  set_identifier = "primary"
  failover_routing_policy { type = "PRIMARY" }
  records = [aws_rds_cluster.primary.endpoint]
  health_check_id = aws_route53_health_check.primary_arc.id
}
resource "aws_route53_record" "writer_secondary" {
  zone_id = var.zone
  name    = "writer.example.com"
  type    = "CNAME"
  ttl     = 5
  set_identifier = "secondary"
  failover_routing_policy { type = "SECONDARY" }
  records = [aws_rds_cluster.secondary.endpoint]
}
```

TTL 5초는 EDNS resolver의 lower bound 정책으로 인해 실제로 5~30초 안에 전파되는 것이 일반적이다.

## 4. RDS Proxy 와 connection 보전

failover 후 애플리케이션 측 JDBC pool 은 연결 자체는 끊어진다. 각 region의 RDS Proxy를 두면 클라이언트는 proxy endpoint에만 연결하고 proxy 가 백엔드 인스턴스를 추적한다. failover 시 proxy 가 새 writer 로 자동 재연결을 잡아주어 connection storm 을 줄인다.

Spring Boot HikariCP 설정은 다음 항목을 짧게 잡는다.

```yaml
spring:
  datasource:
    hikari:
      connectionTimeout: 5000
      validationTimeout: 3000
      maxLifetime: 300000
      keepaliveTime: 60000
      socketTimeout: 10000   # MySQL JDBC option
```

`maxLifetime`을 5분으로 두면 failover로 끊어진 stale connection이 풀에서 빨리 회수된다.

## 5. 애플리케이션 트랜잭션 정합성

Aurora Global Database의 secondary는 비동기 read replica이다. 따라서 애플리케이션이 "방금 쓴 글이 secondary에서 즉시 보일 것"을 기대하면 안 된다. 동시에 "primary 가 죽고 detach 한 뒤 사라진 트랜잭션"이 어떻게 처리될지를 고민해야 한다. 다음 패턴이 효과적이다.

쓰기 후 일정 시간 동안은 primary로 읽기를 강제. Spring `AbstractRoutingDataSource`로 라우팅:

```java
public class ReadAfterWriteRouter extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ReadAfterWriteContext.recentWrite() ? "primary" : "secondary";
    }
}
```

`ReadAfterWriteContext`는 쓰기 직후 N초 동안 ThreadLocal flag를 유지한다. N은 lag p99 + 안전 margin (예: 3초).

idempotency key 기반 재시도. failover 직후 잘리는 트랜잭션을 클라이언트가 같은 key 로 재시도하면 중복이 발생하지 않는다.

```java
@Transactional
public OrderResult placeOrder(String idempotencyKey, OrderRequest req) {
    return orders.findByIdempotencyKey(idempotencyKey)
        .orElseGet(() -> orders.save(new Order(idempotencyKey, req)));
}
```

## 6. 비용 모델

Global Database 비용은 (1) 각 region의 cluster 인스턴스 시간 요금, (2) 각 region의 storage 요금, (3) cross-region replication data transfer 요금. 후자는 GB당 $0.02 (us-east-1 ↔ us-west-2 기준, 2025 가격)으로 redo 트래픽 자체에는 곱이 곱해진다. write throughput이 100 MB/s 라면 일 8.6 TB → 약 $172/일 의 transfer 비용이 추가된다. 이를 줄이려면 batch insert 압축, large blob을 S3로 분리, 불필요한 audit log 컬럼 분리가 효과적이다.

## 7. Failover 훈련 절차

훈련은 분기 1회 권장. 절차는 다음과 같다. 먼저 비-운영 시간대를 잡고 SLO 영향 통보. ARC routing control 을 secondary 쪽으로 전환해 traffic 일부(10%)만 secondary로 보내며 read 정합성 확인. managed planned failover 트리거. 새 primary로 전체 traffic 라우팅 후 정상 메트릭 확인. 30분 안정화 후 원래 region으로 다시 swap. 각 단계별 RTO/RPO 측정값을 GameDay 보고서에 기록.

```bash
aws rds failover-global-cluster \
  --global-cluster-identifier my-global \
  --target-db-cluster-identifier arn:aws:rds:us-west-2:123:cluster:secondary
```

이 명령은 5.7+ 기준이다. console에서 보면 cluster 상태가 `failing-over` → `available`으로 진행된다.

## 8. 모니터링과 알람

핵심 알람:

| 알람 | 임계값 | 행동 |
| --- | --- | --- |
| AuroraGlobalDBRPOLag p95 | > 5s, 5분 지속 | secondary cluster 인스턴스 사양 점검 |
| AuroraGlobalDBReplicationLag | > 30s | primary write 부하 점검, instance class 상향 |
| StorageNetworkThroughput | rate of change > 200% | redo 폭증 SQL 식별 |
| Aurora cluster status | not available | PagerDuty Sev1 |

CloudWatch metric math로 RPO 추정치를 단일 지표로 합성:

```
MAX(m1, m2)  // m1: AuroraGlobalDBRPOLag, m2: AuroraGlobalDBReplicationLag
```

## 9. 한계와 주의 사항

Aurora Global Database는 PostgreSQL/MySQL 에서 지원되지만 일부 기능 제약이 있다. parallel query, backtrack, IAM database authentication 의 일부 옵션이 글로벌 환경에서 제한된다. major version upgrade 는 secondary 가 먼저 업그레이드된 뒤 primary가 따라가는 절차가 필요하다. cross-region traffic 은 AWS Backbone 망을 사용하지만 region pair 간 latency 자체를 줄일 수는 없다. 가까운 pair (예: us-east-1 ↔ us-east-2)는 50~80 ms, 먼 pair (us-east-1 ↔ ap-northeast-2)는 150~180 ms 정도이다.

## 참고

- AWS Aurora User Guide — Global Database (docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database.html)
- AWS re:Invent 2023 DAT309 — Building resilient apps with Aurora Global Database
- Route 53 Application Recovery Controller Developer Guide
- Werner Vogels, "Failures as a Service" — All Things Distributed 블로그
- "Designing Data-Intensive Applications", Chapter 5 — Replication Lag
