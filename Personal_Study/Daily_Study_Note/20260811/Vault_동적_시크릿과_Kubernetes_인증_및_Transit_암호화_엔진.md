Notion 원본: https://www.notion.so/3b85a06fd6d381c2850af5cd7394db10

# Vault 동적 시크릿과 Kubernetes 인증 및 Transit 암호화 엔진

> 2026-08-11 신규 주제 · 확장 대상: DevOps (Kubernetes · GitHub Actions OIDC · Spring 구성 관리 기학습)

## 학습 목표

- 정적 시크릿 배포의 한계를 짚고 동적 시크릿(lease 기반 단명 자격증명) 모델로 전환한다
- Database Secrets Engine으로 MySQL 단명 계정을 발급하고 lease 갱신·회수 흐름을 추적한다
- Kubernetes 인증(ServiceAccount JWT 검증)과 Vault Agent/CSI 주입 패턴을 구성한다
- Transit 엔진으로 암호화를 서비스화(EaaS)하고 키 회전·envelope encryption을 설계한다

## 1. 정적 시크릿의 실패 모드 — 왜 "잘 숨기기"로는 부족한가

Kubernetes Secret, GitHub Actions secret, 환경변수 — 기학습한 배포 체계의 시크릿은 모두 **정적**이다. 정적 시크릿의 구조적 문제는 세 가지다. 첫째, **유출 반경**: DB 비밀번호 하나가 모든 인스턴스·모든 개발자 노트북·CI 로그에 동일하게 존재하므로 한 곳의 유출이 전체 침해가 된다. 둘째, **회전 비용**: 비밀번호를 바꾸려면 사용하는 모든 곳을 동시에 갱신해야 해서 사실상 회전하지 않게 된다(수년 묵은 DB 계정이 표준 풍경이다). 셋째, **감사 불능**: 같은 자격증명을 모두가 쓰므로 "누가 접속했나"를 DB 로그에서 구분할 수 없다.

HashiCorp Vault의 답은 시크릿을 숨기는 것이 아니라 **수명을 없애는 것**이다. 애플리케이션이 요청할 때마다 고유한 단명 자격증명을 발급(dynamic secrets)하고, TTL이 지나면 Vault가 백엔드(DB 등)에서 직접 회수한다. 유출돼도 수 분 뒤 죽는 자격증명, 인스턴스별로 다른 계정이라 감사 추적 가능, 회전은 자동 — 세 문제를 모델 수준에서 제거한다. Vault의 구성 요소는 (1) 스토리지에 암호화 저장되는 barrier, (2) unseal 메커니즘(Shamir 키 분할 또는 KMS auto-unseal), (3) 인증 메서드(K8s, OIDC, AppRole…), (4) 시크릿 엔진(KV, Database, Transit, PKI…), (5) 정책(HCL 기반 경로별 capability)이다.

```hcl
# policy: 앱은 자기 DB 역할의 자격증명만 읽을 수 있다
path "database/creds/orders-app" {
  capabilities = ["read"]
}
path "transit/encrypt/orders" {
  capabilities = ["update"]
}
```

## 2. 동적 시크릿의 핵심 — lease 수명주기

동적 시크릿 발급 응답에는 반드시 `lease_id`, `lease_duration`, `renewable`이 붙는다. lease는 Vault가 발급물을 추적하는 핸들이며, 수명주기는 발급 → (주기적) 갱신 → 만료 또는 명시적 revoke → 백엔드에서 실제 삭제로 이어진다.

```bash
$ vault read database/creds/orders-app
Key                Value
---                -----
lease_id           database/creds/orders-app/abc123
lease_duration     1h
lease_renewable    true
username           v-k8s-orders-app-x7Yz9-1754900000
password           A1b-c2D3e4F5g6H7

# 갱신 (max_ttl까지만 가능)
$ vault lease renew database/creds/orders-app/abc123
# 즉시 회수 (유출 대응): 해당 계정이 DB에서 즉시 DROP
$ vault lease revoke database/creds/orders-app/abc123
# 침해 시 프리픽스 일괄 회수
$ vault lease revoke -prefix database/creds/orders-app
```

운영 감각으로 중요한 두 가지: (1) `default_ttl`(예: 1h)과 `max_ttl`(예: 24h)의 분리 — 갱신은 max_ttl 벽을 넘을 수 없으므로 애플리케이션은 max_ttl 도달 전 **새 자격증명으로 재발급 + 커넥션 풀 교체**를 해야 한다. 이 "풀 드레인 후 재구성" 로직이 동적 시크릿 도입의 실제 공수다. (2) Vault 장애 시나리오 — Vault가 죽어도 이미 발급된 자격증명은 TTL까지 유효하므로 즉시 장애로 이어지지 않지만, TTL이 짧을수록 Vault 가용성 요구가 올라간다. TTL은 "유출 허용 시간"과 "Vault 의존 강도"의 트레이드오프 다이얼이다.

## 3. Database Secrets Engine — MySQL 단명 계정 실습

```bash
vault secrets enable database

# 연결 설정: Vault가 계정 생성 권한을 가진 관리 계정 보유
vault write database/config/orders-mysql \
	plugin_name=mysql-database-plugin \
	connection_url="{{username}}:{{password}}@tcp(mysql:3306)/" \
	allowed_roles="orders-app" \
	username="vault-admin" password="initial-pw"

# 관리 계정 비밀번호를 Vault만 아는 값으로 즉시 회전 (부트스트랩 비밀 제거)
vault write -f database/rotate-root/orders-mysql

# 역할: 발급 시 실행할 DDL 템플릿
vault write database/roles/orders-app \
	db_name=orders-mysql \
	creation_statements="CREATE USER '{{name}}'@'%' IDENTIFIED BY '{{password}}'; \
	  GRANT SELECT, INSERT, UPDATE, DELETE ON orders.* TO '{{name}}'@'%';" \
	default_ttl=1h max_ttl=24h
```

`rotate-root`가 인상적인 지점이다 — 부트스트랩에 쓴 관리 비밀번호를 Vault가 스스로 회전해 버려 **사람이 아는 장기 자격증명이 시스템에서 사라진다**. creation_statements가 평범한 SQL 템플릿이므로 권한 최소화를 역할 단위로 정밀 설계할 수 있고(읽기 전용 역할, 마이그레이션용 DDL 역할 분리), 같은 엔진이 PostgreSQL·Oracle·Elasticsearch·MongoDB 플러그인을 지원해 기학습 DB 스택 전체에 일반화된다. 발급된 계정 이름에 역할·발급 시각이 인코딩되므로 DB의 processlist·감사 로그에서 "어느 파드가 이 쿼리를 던졌나"가 역추적된다 — 정적 공유 계정에서는 불가능했던 것.

## 4. Kubernetes 인증 — ServiceAccount JWT에서 Vault 토큰까지

"Vault에 인증할 시크릿은 또 어디에 두나"라는 secret-zero 문제를 Kubernetes 환경에서는 **플랫폼 신원**으로 푼다. 파드의 projected ServiceAccount 토큰(짧은 TTL, audience 지정 가능한 JWT)을 Vault가 Kubernetes TokenReview API로 검증하는 구조다.

```bash
vault auth enable kubernetes
vault write auth/kubernetes/config \
	kubernetes_host="https://kubernetes.default.svc" \
	kubernetes_ca_cert=@/var/run/secrets/kubernetes.io/serviceaccount/ca.crt

# 역할: 어느 네임스페이스의 어느 SA가 어느 정책을 받는가
vault write auth/kubernetes/role/orders \
	bound_service_account_names=orders-sa \
	bound_service_account_namespaces=prod \
	policies=orders-policy \
	ttl=1h
```

흐름: 파드가 SA JWT를 `auth/kubernetes/login`에 제출 → Vault가 TokenReview로 위조·만료 검증 → bound 조건 매칭 → 정책이 붙은 Vault 토큰 발급. 이 구조는 기학습한 GitHub Actions OIDC 페더레이션과 동형이다 — "플랫폼이 서명한 워크로드 신원 토큰을 신뢰 대상이 검증"하는 패턴의 Kubernetes 버전. 실제로 Vault는 JWT/OIDC auth method로 GitHub Actions OIDC 토큰도 직접 받을 수 있어, CI에서 장기 VAULT_TOKEN 시크릿을 없애는 데 같은 원리가 쓰인다.

주입 패턴은 세 가지가 경쟁한다. **Vault Agent Injector**: mutating webhook이 사이드카/init 컨테이너를 주입, 로그인·갱신·템플릿 렌더링을 대행해 파일(`/vault/secrets/db`)로 떨어뜨린다 — 애플리케이션 무수정이 장점. **CSI Provider**: 시크릿을 CSI 볼륨으로 마운트, 사이드카가 없어 리소스가 가벽다. **Vault Secrets Operator(VSO)**: Vault 시크릿을 네이티브 K8s Secret으로 동기화하는 CRD 오퍼레이터 — K8s Secret을 소비하는 기존 매니페스트·서드파티 차트와 호환이 강점이지만, 결과물이 etcd에 저장되므로 etcd 암호화가 전제된다. 신규 구축이면 VSO 또는 Agent, etcd 노출을 피하려면 Agent/CSI를 고른다.

```yaml
# Agent Injector 애노테이션 예시
metadata:
  annotations:
    vault.hashicorp.com/agent-inject: "true"
    vault.hashicorp.com/role: "orders"
    vault.hashicorp.com/agent-inject-secret-db: "database/creds/orders-app"
    vault.hashicorp.com/agent-inject-template-db: |
      {{- with secret "database/creds/orders-app" -}}
      spring.datasource.username={{ .Data.username }}
      spring.datasource.password={{ .Data.password }}
      {{- end -}}
```

Spring 쪽 소비는 Spring Cloud Vault(`spring.cloud.vault.*`)로 직접 붙거나, 위처럼 Agent가 렌더링한 프로퍼티 파일을 읽는 방식 중 선택한다. Spring Cloud Vault는 lease 갱신과 `RefreshScope` 연동(자격증명 교체 시 DataSource 재바인딩)까지 처리해 주므로 Spring 단독 스택이면 직접 통합이 매끄럽다.

## 5. Transit 엔진 — 암호화를 서비스로

Transit은 데이터를 저장하지 않는 **encryption as a service**다. 애플리케이션이 평문을 보내면 암호문을 돌려주고, 키는 Vault 밖으로 절대 나가지 않는다. DB의 개인정보 컴럼 암호화에서 "키를 애플리케이션 설정에 두는" 안티패턴을 제거한다.

```bash
vault secrets enable transit
vault write -f transit/keys/orders          # AES256-GCM96 기본

# 암호화/복호화
vault write transit/encrypt/orders plaintext=$(base64 <<< "주민번호")
# → ciphertext: vault:v1:AbC...   (v1 = 키 버전)
vault write transit/decrypt/orders ciphertext="vault:v1:AbC..."

# 키 회전: 새 버전 v2로 암호화, v1 암호문은 계속 복호화 가능
vault write -f transit/keys/orders/rotate
# 재암호화(데이터 이관): 복호화 없이 v1 암호문 → v2 암호문
vault write transit/rewrap/orders ciphertext="vault:v1:AbC..."
vault write transit/keys/orders/config min_decryption_version=2   # v1 폐기
```

암호문에 키 버전이 박히는 `vault:vN:` 포맷 덕분에 **무중단 키 회전**이 성립한다: rotate → 배치로 rewrap → min_decryption_version 상향. 대용량 데이터는 왕복 비용 때문에 직접 Transit에 보내지 않고 **envelope encryption**을 쓴다 — `transit/datakey/plaintext/orders`로 DEK(데이터 키)를 발급받아 로컬에서 AES 암호화하고, DEK의 암호문만 데이터 옆에 저장한다. 복호화 시 DEK 암호문을 Transit에 풀어 달라고 요청하는 구조로, 이는 기학습한 AWS KMS의 GenerateDataKey와 동일한 패턴이다. Transit은 서명/검증(`sign/verify`), HMAC, FPE(format-preserving, Enterprise) 연산도 제공한다.

## 6. 가용성 아키텍처 — unseal, HA, DR

Vault는 시크릿 경로의 단일 장애점이 되기 쉽으므로 가용성 설계가 도입 성패를 가른다. **Unseal**: 재시작 때마다 barrier 키를 복원해야 하는데, Shamir 수동 unseal(운영자 N명 중 K명이 키 조각 입력)은 자동 재기동을 막으므로 프로덕션은 **auto-unseal**(AWS KMS/GCP KMS가 루트 키를 감쌈)이 표준이다. **HA**: 통합 스토리지(Integrated Storage, Raft)가 현재 권장 — 기학습한 Raft 합의가 그대로 등장한다. 리더 1 + 팔로워 N, 쓰기는 리더로 포워딩되고 장애 시 Raft 선출로 승계된다. **성능 확장**: 표준 팔로워는 읽기를 서빙하지 않으므로(리다이렉트), 읽기 확장은 Enterprise performance replica 또는 Agent 캐싱으로 푼다. **감사**: audit device(파일/syslog)를 반드시 켜고, 모든 요청·응답이 HMAC 처리되어 기록됨을 확인한다 — 감사 로그 쓰기 실패 시 Vault는 요청을 거부(fail-closed)하므로 디스크 용량이 곷 가용성이다.

| 결정 | 기본 권장 | 비고 |
| --- | --- | --- |
| 스토리지 | Integrated Storage (Raft) | Consul 백엔드는 레거시 경로 |
| unseal | 클라우드 KMS auto-unseal | recovery key는 Shamir 유지 |
| 노드 수 | 3 또는 5 | Raft 쿼럼 |
| 토큰 TTL | 짧게 + 갱신 | 장기 토큰 발급 금지 정책 |
| 감사 | 파일 + 수집기 이중화 | fail-closed 특성 주의 |

## 7. 대안과의 비교 — 어디까지 Vault가 필요한가

모든 팀에 Vault가 정답은 아니다. **클라우드 네이티브 대안**: AWS Secrets Manager + IAM, GCP Secret Manager는 관리 부담이 0에 가깝고 IAM 통합이 매끄럽다 — 단일 클라우드에 갇혀 있고 동적 시크릿이 RDS 등 일부에 제한되는 것이 차이. **External Secrets Operator(ESO)**: 외부 스토어(ASM/GSM/Vault)를 K8s Secret으로 동기화하는 중립 계층으로, "지금은 ASM, 나중에 Vault" 같은 이행 유연성을 준다. **SOPS + KMS**: GitOps 친화적 정적 암호화로, 소규모·저변경 시크릿에 충분하다. **OpenBao**: Vault의 BUSL 라이선스 전환(2023) 이후 갈라진 오픈소스 포크로, 라이선스 리스크를 피하려는 조직의 선택지다. 판단 축은 (1) 동적 시크릿·Transit 같은 고급 기능의 실수요, (2) 멀티클라우드/온프레 요구, (3) Vault 클러스터를 운영할 팀 역량이다. 동적 DB 자격증명과 EaaS가 필요 없다면 관리형 시크릿 스토어 + ESO가 총소유비용에서 대체로 이긴다.

## 8. 실무 도입 로드맵 — 4년차 백엔드 관점

현실적 단계화: **1단계** — KV v2 엔진으로 정적 시크릿부터 이관하고 K8s 인증·정책·감사 로그 체계를 세운다(애플리케이션 변경 최소, Agent 주입). 이 단계의 가치는 중앙 감사와 접근 제어 통일이다. **2단계** — 신규 서비스 하나로 Database 동적 시크릿을 파일럿한다. 커넥션 풀 재구성(HikariCP `softEvictConnections` + 재바인딩) 검증, max_ttl 도달 시나리오의 카오스 테스트가 핵심 과제다. **3단계** — 개인정보 컴럼에 Transit envelope encryption 적용, 키 회전 runbook 작성. **4단계** — CI(GitHub Actions OIDC → Vault JWT auth)에서 장기 시크릿 제거. 각 단계에서 측정할 것: 시크릿 평균 수명(목표: 시간 단위), 사람이 아는 프로덕션 자격증명 수(목표: 0), 유출 대응 시간(revoke -prefix로 분 단위).

## 참고

- Vault 공식 문서 — Dynamic Secrets / Database Engine: https://developer.hashicorp.com/vault/docs/secrets/databases
- Vault 공식 문서 — Kubernetes Auth Method: https://developer.hashicorp.com/vault/docs/auth/kubernetes
- Vault 공식 문서 — Transit Engine: https://developer.hashicorp.com/vault/docs/secrets/transit
- Vault Reference Architecture (Integrated Storage): https://developer.hashicorp.com/vault/tutorials/raft/raft-reference-architecture
- Spring Cloud Vault Reference: https://docs.spring.io/spring-cloud-vault/reference/
- OpenBao 프로젝트: https://openbao.org/
