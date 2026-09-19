Notion 원본: https://www.notion.so/3e05a06fd6d381738e82c02434f53c8a

# HashiCorp Vault 동적 시크릿과 DB 자격증명 리스 갱신 및 로테이션

> 2026-09-19 신규 주제 · 확장 대상: AWS, Docker&CI, Spring

## 학습 목표

- 정적 시크릿과 동적 시크릿의 차이를 리스(lease) 수명주기로 설명한다
- Database Secrets Engine이 DB 사용자를 생성·폐기하는 전 과정을 SQL 수준에서 추적한다
- Kubernetes Auth Method로 애플리케이션이 시크릿 없이 인증하는 경로를 구성한다
- Spring Cloud Vault의 리스 갱신·커넥션 풀 재시작 실패 모드를 진단하고 방어한다

## 1. 정적 시크릿의 한계와 동적 시크릿의 발상

전통적 방식은 DB 사용자 하나를 만들고 비밀번호를 환경변수나 시크릿 스토어에 넣어 모든 인스턴스가 공유한다. 문제가 셋이다. 유출 시 폭발 반경이 전 인스턴스이고, 로테이션하려면 전체 재배포가 필요하며, 감사 로그의 `db_user`가 전부 같아서 "누가 이 쿼리를 날렸나"를 추적할 수 없다.

## 2. 동적 시크릿이 바꾸는 것

Vault의 Database Secrets Engine은 요청이 올 때마다 **DB에 새 사용자를 즉석 생성**하고, TTL이 지나면 자동으로 `DROP USER` 한다. 결과적으로:

- 애플리케이션 인스턴스마다, 심지어 재시작마다 다른 자격증명
- 유출되어도 TTL(보통 1시간) 후 자연 소멸
- DB 감사 로그에 `v-kubernetes-orders-a1b2c3-1758...` 형태로 주체가 남음
- 로테이션이 "아무것도 안 하는 것"이 됨 — 만료가 곧 로테이션

대가는 복잡도다. Vault가 가용성의 단일 장애점이 되고, 커넥션 풀이 만료된 자격증명을 들고 있는 문제를 다뤄야 하며, DB에 생성되는 사용자 수가 늘어난다.

## 3. Database Secrets Engine 구성 — PostgreSQL 예제

엔진을 활성화하고 연결을 등록한다. `connection_url`의 `{{username}}`, `{{password}}`는 Vault가 채우는 템플릿이다.

```bash
vault secrets enable database

vault write database/config/orders-db \
  plugin_name="postgresql-database-plugin" \
  allowed_roles="orders-app,orders-readonly" \
  connection_url="postgresql://{{username}}:{{password}}@pg.internal:5432/orders?sslmode=require" \
  username="vault_admin" \
  password="$VAULT_ADMIN_PW" \
  password_authentication="scram-sha-256"
```

`allowed_roles`는 화이트리스트다. 여기 없는 롤은 이 연결을 쓸 수 없다. 와일드카드 `*`는 쓰지 않는 편이 좋다.

등록 직후 관리자 비밀번호 자체를 회전시킨다. 이렇게 하면 **Vault만 아는 비밀번호**가 되어, 최초 설정에 쓴 값이 유출돼도 무의미해진다.

```bash
vault write -force database/rotate-root/orders-db
```

이 작업은 되돌릴 수 없다. `vault_admin` 계정을 사람이 다시 쓸 일이 없다는 확신이 필요하고, 그래서 Vault 전용 관리 계정을 따로 만들어 등록하는 것이 관례다.

롤은 "어떤 SQL로 사용자를 만들고 어떤 권한을 줄 것인가"를 정의한다.

```bash
vault write database/roles/orders-app \
  db_name="orders-db" \
  creation_statements="
    CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app TO \"{{name}}\";
    GRANT USAGE ON ALL SEQUENCES IN SCHEMA app TO \"{{name}}\";
  " \
  revocation_statements="
    REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA app FROM \"{{name}}\";
    REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA app FROM \"{{name}}\";
    REVOKE USAGE ON SCHEMA app FROM \"{{name}}\";
    DROP ROLE \"{{name}}\";
  " \
  default_ttl="1h" \
  max_ttl="24h"
```

`VALID UNTIL '{{expiration}}'`이 중요한 안전장치다. Vault가 폐기에 실패하더라도 PostgreSQL이 스스로 로그인을 거부한다. 이중 방어다.

실전에서 가장 자주 빠뜨리는 것이 **기본 권한**이다. 위 `GRANT ... ON ALL TABLES`는 **이미 존재하는** 테이블에만 적용된다. 마이그레이션으로 새 테이블이 생기면 새 동적 사용자가 접근하지 못한다. 스키마 소유자에 대해 기본 권한을 미리 설정해야 한다.

```sql
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vault_dynamic_group;
```

또는 권한을 그룹 롤에 모으고 동적 사용자를 그 그룹에 넣는 방식이 관리가 쉽다.

```
creation_statements = "
  CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' IN ROLE app_readwrite;
"
```

자격증명을 읽어 본다.

```bash
vault read database/creds/orders-app
```

```
Key                Value
---                -----
lease_id           database/creds/orders-app/7Fz9KqW3...
lease_duration     1h
lease_renewable    true
password           A1a-xK92mPqRs7Tv
username           v-kubernetes-orders-app-8fH2kL9x-1758291600
```

`username`에 인증 방식, 롤 이름, 랜덤, 생성 시각이 인코딩되어 있다. DB 감사 로그에서 역추적이 가능한 이유다. MySQL은 사용자명 길이 제한(32자)이 있어 `username_template`을 줄여야 할 수 있다.

```bash
vault write database/roles/orders-app \
  username_template='{{ printf "v_%s_%s" (.RoleName | truncate 8) (random 12) }}' ...
```

## 4. 리스 수명주기 — 갱신, 폐기, 그리고 max_ttl의 벽

모든 동적 시크릿에는 리스가 붙는다. 상태 전이는 단순하다.

```
발급 → (renew)* → 만료 또는 명시적 revoke → DB 사용자 DROP
```

갱신은 `lease_id`로 한다.

```bash
vault lease renew -increment=1h database/creds/orders-app/7Fz9KqW3...
```

핵심 제약: **`max_ttl`을 넘길 수 없다.** `default_ttl=1h`, `max_ttl=24h`라면 1시간마다 갱신하더라도 최초 발급으로부터 24시간 뒤에는 무조건 만료된다. 애플리케이션은 갱신 실패를 예외가 아니라 **정상 경로**로 다뤄야 한다 — 갱신이 거부되면 새 자격증명을 받아 커넥션 풀을 교체한다.

이 지점이 동적 시크릿 도입에서 가장 많이 깨지는 부분이다. "무한히 갱신되겠지"라고 가정한 코드는 정확히 `max_ttl` 후에 장애를 낸다. 그리고 그 시점이 배포 후 24시간 뒤라서, 배포 직후 스모크 테스트를 통과하고 다음 날 새벽에 터진다.

리스 상태 점검:

```bash
vault list sys/leases/lookup/database/creds/orders-app
vault lease lookup database/creds/orders-app/7Fz9KqW3...
vault lease revoke -prefix database/creds/orders-app   # 롤 전체 긴급 폐기
```

마지막 명령이 사고 대응의 핵심이다. 유출이 의심되면 해당 롤의 모든 활성 자격증명을 즉시 무효화할 수 있다. 정적 비밀번호에서는 불가능한 일이다.

리스가 쌓이는 문제도 있다. 파드가 crash loop에 빠지면 매 재시작마다 새 리스를 발급받고 이전 것은 TTL까지 남는다. DB에 수백 개의 사용자가 생길 수 있다. `max_leases` 제한과 모니터링이 필요하다.

```bash
vault read sys/quotas/lease-count/orders-quota
vault write sys/quotas/lease-count/orders-quota \
  path="database/creds/orders-app" max_leases=200
```

PostgreSQL 쪽에서도 확인한다.

```sql
SELECT rolname, rolvaliduntil
  FROM pg_roles
 WHERE rolname LIKE 'v-%'
 ORDER BY rolvaliduntil;
```

## 5. Kubernetes Auth — 시크릿 없이 시크릿 얻기

동적 시크릿을 받으려면 먼저 Vault에 인증해야 한다. 여기에 정적 토큰을 쓰면 "시크릿 제로 문제"를 한 단계 옮긴 것에 불과하다. Kubernetes Auth Method는 파드의 ServiceAccount 토큰을 신원으로 쓴다.

```bash
vault auth enable kubernetes

vault write auth/kubernetes/config \
  kubernetes_host="https://kubernetes.default.svc:443" \
  kubernetes_ca_cert=@/var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
  disable_iss_validation=true

vault write auth/kubernetes/role/orders-app \
  bound_service_account_names="orders-app" \
  bound_service_account_namespaces="production" \
  policies="orders-db-read" \
  ttl="1h"
```

정책은 최소 권한으로 쓴다.

```hcl
# orders-db-read.hcl
path "database/creds/orders-app" {
  capabilities = ["read"]
}

path "sys/leases/renew" {
  capabilities = ["update"]
}
```

`sys/leases/renew`를 빼먹으면 발급은 되는데 갱신이 403으로 실패한다. 증상이 "1시간 뒤 커넥션 실패"라 원인 파악이 늦어지는 전형적 함정이다.

Vault Agent Injector를 쓰면 애플리케이션 코드 수정 없이 사이드카가 자격증명을 파일로 떨어뜨린다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders-app
spec:
  template:
    metadata:
      annotations:
        vault.hashicorp.com/agent-inject: "true"
        vault.hashicorp.com/role: "orders-app"
        vault.hashicorp.com/agent-inject-secret-db: "database/creds/orders-app"
        vault.hashicorp.com/agent-inject-template-db: |
          {{- with secret "database/creds/orders-app" -}}
          spring.datasource.username={{ .Data.username }}
          spring.datasource.password={{ .Data.password }}
          {{- end }}
        vault.hashicorp.com/agent-inject-file-db: "application-vault.properties"
    spec:
      serviceAccountName: orders-app
```

Agent가 리스 갱신과 재발급을 대신하고, 값이 바뀌면 파일을 다시 쓴다. 다만 **파일이 바뀐다고 애플리케이션이 알아서 반영하지는 않는다.** 이것이 다음 절의 주제다.

## 6. Spring Cloud Vault와 커넥션 풀 — 가장 흔한 실패 모드

Spring Boot 애플리케이션에서 설정은 다음과 같다.

```yaml
spring:
  cloud:
    vault:
      uri: https://vault.internal:8200
      authentication: KUBERNETES
      kubernetes:
        role: orders-app
        service-account-token-file: /var/run/secrets/kubernetes.io/serviceaccount/token
      database:
        enabled: true
        role: orders-app
        backend: database
        username-property: spring.datasource.username
        password-property: spring.datasource.password
      config:
        lifecycle:
          enabled: true
          min-renewal: 10s
          expiry-threshold: 5m
          lease-endpoints: SysLeases
  datasource:
    hikari:
      max-lifetime: 1800000        # 30분 — max_ttl 보다 충분히 짧게
      keepalive-time: 300000
      validation-timeout: 3000
```

`lifecycle.enabled=true`가 리스 자동 갱신을 켠다. `expiry-threshold`는 만료 몇 분 전부터 갱신을 시도할지다.

**문제의 핵심:** Vault가 새 자격증명을 받아 `spring.datasource.password` 프로퍼티를 갱신해도, **HikariCP가 이미 연 커넥션과 캐시된 설정은 그대로다.** 기존 커넥션은 이미 인증을 마쳤으므로 계속 동작하지만, 풀이 새 커넥션을 열려고 할 때 옛 비밀번호를 쓰면 실패한다.

방어는 세 층이다.

**(a) `maxLifetime`을 `max_ttl`보다 짧게.** 커넥션이 자격증명보다 오래 살지 못하게 한다. `max_ttl=24h`라면 `maxLifetime=30m` 정도. 이렇게 하면 풀이 주기적으로 커넥션을 교체하면서 최신 자격증명을 쓴다.

**(b) 자격증명 변경 시 풀 재시작.** Spring Cloud Vault는 자격증명이 바뀌면 `RefreshScope` 이벤트를 발행한다. DataSource를 `@RefreshScope`로 감싸거나, HikariDataSource의 비밀번호를 직접 갱신하고 유휴 커넥션을 비운다.

```java
@Component
public class DataSourceCredentialRefresher {

    private final HikariDataSource dataSource;
    private final Environment environment;

    public DataSourceCredentialRefresher(HikariDataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @EventListener(SecretLeaseCreatedEvent.class)
    public void onCredentialRotated(SecretLeaseCreatedEvent event) {
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
        config.setUsername(username);
        config.setPassword(password);

        // 유휴 커넥션을 폐기해 새 자격증명으로 다시 열게 한다.
        // 사용 중인 커넥션은 반납 시점에 교체된다.
        dataSource.getHikariPoolMXBean().softEvictConnections();
    }
}
```

`softEvictConnections()`가 핵심이다. `close()`와 달리 사용 중인 커넥션을 끊지 않고, 반납되는 순서대로 폐기한다. 무중단 교체가 가능한 이유다.

**(c) 실패 시 재시도.** 커넥션 획득 실패를 일시적 오류로 다뤄 재시도한다. 교체 순간의 짧은 창을 넘기기 위함이다.

리스 만료 이벤트도 구독해 로그를 남겨야 진단이 가능하다.

```java
@EventListener(SecretLeaseExpiredEvent.class)
public void onLeaseExpired(SecretLeaseExpiredEvent event) {
    log.warn("Vault lease expired: path={}, mode={}",
             event.getSource().getPath(), event.getSource().getMode());
}

@EventListener(SecretLeaseErrorEvent.class)
public void onLeaseError(SecretLeaseErrorEvent event) {
    log.error("Vault lease error: path={}", event.getSource().getPath(), event.getException());
}
```

## 7. Static Roles — 동적 생성이 불가능할 때

레거시 시스템, 라이선스가 사용자 수로 매겨지는 DB, 또는 외부 SaaS 계정처럼 **사용자를 동적으로 만들 수 없는** 경우가 있다. Vault의 Static Role은 기존 사용자 하나의 **비밀번호만 주기적으로 회전**시킨다.

```bash
vault write database/static-roles/legacy-batch \
  db_name="orders-db" \
  username="batch_user" \
  rotation_period="24h" \
  rotation_statements="ALTER USER \"{{name}}\" WITH PASSWORD '{{password}}';"

vault read database/static-creds/legacy-batch
```

```
Key                 Value
---                 -----
last_vault_rotation 2026-09-19T11:00:03Z
password            Zq7-Ln28vXcT
rotation_period     24h
ttl                 21h14m
username            batch_user
```

동적 롤과의 차이가 중요하다. Static Role에는 **리스가 없다.** 대신 `ttl`은 "다음 회전까지 남은 시간"을 뜻한다. 애플리케이션은 이 값을 보고 재조회 시점을 정해야 한다. 그리고 회전 순간에는 **모든 인스턴스가 동시에 옛 비밀번호를 들고 있게** 되므로, 동적 롤보다 오히려 교체 창의 위험이 크다. `rotation_period`를 짧게 잡을수록 안전성은 올라가지만 교체 창을 더 자주 만난다.

## 8. 운영 관점의 트레이드오프

동적 시크릿은 공짜가 아니다. 도입 전에 계산해야 할 비용이 있다.

**가용성 결합.** Vault가 내려가면 새 자격증명을 받을 수 없다. 기존 커넥션은 살아 있으므로 즉시 장애는 아니지만, 그동안 파드가 재시작되면 부팅에 실패한다. Vault는 Raft 기반 HA(최소 3노드)로 구성하고, Auto-unseal(AWS KMS, Transit)을 반드시 설정한다. 수동 unseal은 새벽 장애에서 최악이다.

```hcl
seal "awskms" {
  region     = "ap-northeast-2"
  kms_key_id = "alias/vault-unseal"
}
```

**감사 로그.** Vault 자체의 audit device를 켜지 않으면 "누가 어떤 시크릿을 언제 읽었는지"가 남지 않는다. 켜는 것이 사실상 필수이고, 하나 이상의 audit device가 모두 쓰기에 실패하면 Vault는 요청을 거부한다 — 감사 실패 시 서비스 중단을 택하는 설계다. 로그 볼륨과 디스크를 미리 계산해야 한다.

```bash
vault audit enable file file_path=/vault/logs/audit.log
```

**DB 부하.** 사용자 생성·삭제는 DDL이다. 파드가 많고 TTL이 짧으면 DDL이 빈번해지고, PostgreSQL에서는 카탈로그 경합, MySQL에서는 권한 테이블 갱신이 누적된다. TTL을 무작정 짧게 잡는 것이 항상 안전한 선택은 아니다. 시작점으로 `default_ttl=1h`, `max_ttl=24h` 정도가 균형이 맞고, 실제 파드 수와 재시작 빈도로 DDL 횟수를 추정해 조정한다.

**언제 쓰지 않을 것인가.** 인스턴스가 한두 개인 소규모 서비스, 관리형 IAM 인증이 이미 가능한 환경(AWS RDS IAM 인증, Cloud SQL IAM)에서는 Vault를 얹는 복잡도가 이득을 넘어설 수 있다. RDS IAM 인증은 15분짜리 토큰을 IAM 역할로 받으므로 동적 시크릿의 이점 상당 부분을 Vault 없이 얻는다. 여러 종류의 시크릿(DB, PKI, 클라우드 자격증명, 암복호화)을 한 곳에서 다뤄야 할 때 Vault의 값이 나온다.

## 참고

- HashiCorp Vault Documentation — Secrets Engines: Databases
- HashiCorp Vault Documentation — Auth Methods: Kubernetes
- HashiCorp Vault Documentation — Lease, Renew, and Revoke
- HashiCorp Vault Documentation — Vault Agent Injector
- Spring Cloud Vault Reference — Database Backend / Lease Lifecycle
- HikariCP Documentation — `maxLifetime`, `softEvictConnections`
- PostgreSQL Documentation — `CREATE ROLE`, `ALTER DEFAULT PRIVILEGES`
