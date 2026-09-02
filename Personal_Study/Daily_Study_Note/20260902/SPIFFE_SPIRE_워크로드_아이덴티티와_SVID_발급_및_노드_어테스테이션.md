Notion 원본: https://www.notion.so/3cf5a06fd6d381fbb06cfbb1a93d078a

# SPIFFE SPIRE 워크로드 아이덴티티와 SVID 발급 및 노드 어테스테이션

> 2026-09-02 신규 주제 · 확장 대상: Istio mTLS·Service Mesh, Vault 동적 시크릿

## 학습 목표

- SPIFFE ID / SVID / Trust Bundle 세 요소의 역할을 구분한다
- 노드 어테스테이션과 워크로드 어테스테이션의 2단계 신뢰 사슬을 추적한다
- Workload API 로 mTLS 인증서를 무중단 회전하는 애플리케이션을 작성한다
- JWT-SVID 로 Vault·AWS 같은 외부 시스템에 시크릿 없이 인증한다

## 1. 시크릿 부트스트랩 문제

Vault 동적 시크릿은 "DB 비밀번호를 하드코딩하지 말고 Vault 에서 받아라" 를 해결한다. 그런데 Vault 에게 "나는 order-service 다" 를 증명하려면 무언가가 필요하다. Kubernetes 인증 방식은 ServiceAccount 토큰을 쓰고, AppRole 은 RoleID/SecretID 를 쓴다. 결국 *최초의 자격증명* 을 어떻게 워크로드에 안전하게 전달하느냐는 문제가 한 단계 뒤로 밀릴 뿐이다. 이것이 시크릿 제로(secret zero) 문제다.

SPIFFE(Secure Production Identity Framework For Everyone)의 답은 "자격증명을 배포하지 말고, 플랫폼이 관측 가능한 사실로부터 아이덴티티를 *증명* 하게 하라" 다. 워크로드가 어느 노드의 어느 컨테이너에서 어떤 UID 로 돌고 있는지는 커널과 kubelet 이 이미 알고 있다. SPIRE 는 그 사실들을 조합해 단명 인증서를 발급하고, 인증서는 유닉스 도메인 소켓으로만 전달되어 파일시스템이나 환경변수에 남지 않는다.

Istio 의 mTLS 도 내부적으로 같은 모델을 쓴다. istiod 가 SPIFFE 호환 CA 역할을 하고 `spiffe://cluster.local/ns/default/sa/order` 형태의 ID 를 발급한다. SPIRE 는 이 모델을 메시 밖 — VM, 베어메탈, 멀티 클라우드, 심지어 온프레미스 레거시 — 까지 확장한 표준 구현이다.

## 2. SPIFFE ID, SVID, Trust Bundle

**SPIFFE ID** 는 URI 다.

```
spiffe://prod.example.com/ns/payments/sa/order-service
└─ scheme ─┘└ trust domain ┘└──────── path ────────┘
```

Trust domain 은 하나의 CA 루트가 지배하는 영역이고, path 는 그 안의 워크로드 식별자다. 경로 구조는 자유롭지만 조직 전체가 하나의 규칙을 쓰는 것이 중요하다. `/ns/{namespace}/sa/{serviceaccount}` 는 Kubernetes 관례이고, VM 워크로드는 `/host/{hostname}/svc/{service}` 같은 형태를 쓴다.

**SVID**(SPIFFE Verifiable Identity Document)는 SPIFFE ID 를 담은 검증 가능한 문서로 두 가지 형식이 있다.

- **X509-SVID**: X.509 인증서. SPIFFE ID 가 SAN 의 URI 항목에 들어간다. CN 이 아니라 SAN URI 라는 점이 중요하다 — 검증 코드가 CN 을 보면 안 된다. mTLS 에 그대로 쓴다.
- **JWT-SVID**: JWT. `sub` 가 SPIFFE ID, `aud` 가 대상 서비스다. TLS 종단이 프록시나 로드밸런서에서 끕기는 환경, 또는 OIDC 를 이해하는 외부 시스템(Vault, AWS STS)용이다.

X509-SVID 는 기본 수명이 **1시간**이고 SPIRE Agent 가 절반(30분) 지점에서 자동 갱신한다. 짧은 수명은 CRL/OCSP 폐기 인프라를 불필요하게 만든다 — 유출되어도 한 시간 안에 무효화된다.

**Trust Bundle** 은 해당 trust domain 의 CA 인증서 모음이다. 피어 검증에 쓰이며, CA 회전 시 신구 CA 가 함께 담긴다. 서로 다른 trust domain 간 통신은 **Federation** 으로 상대 번들을 교환해 처리한다.

## 3. 2단계 어테스테이션 — 신뢰 사슬

SPIRE 의 핵심 설계는 신뢰를 두 단계로 나눈 것이다.

```
[Node Attestation]  SPIRE Agent  ──증명──▶  SPIRE Server
	"나는 i-0abc 인스턴스다"  (IMDS 서명 문서 / K8s PSAT / TPM)
	              ↓ Agent SVID 발급

[Workload Attestation]  Workload  ──질의──▶  SPIRE Agent
	Agent 가 소켓 peer 의 PID 를 커널에서 얻고,
	/proc/{pid} 와 kubelet API 로 셀렉터를 수집
	              ↓ Workload SVID 발급
```

**노드 어테스테이션**은 Agent 가 자신이 돌는 노드의 정체를 서버에 증명하는 단계다. 플러그인별 방식은 다음과 같다.

| 플러그인 | 증거 | 위조 난이도 |
|---|---|---|
| `aws_iid` | EC2 인스턴스 아이덴티티 문서 + AWS 서명 | 높음(AWS 공개키 검증) |
| `gcp_iit` | GCE 인스턴스 아이덴티티 토큰(JWT) | 높음 |
| `k8s_psat` | Projected ServiceAccount Token(audience 지정) | 높음(TokenReview 로 API 서버 검증) |
| `tpm_devid` | TPM 에 봉인된 디바이스 ID 키의 서명 | 매우 높음 |
| `join_token` | 사전 공유 1회용 토큰 | 낮음(부트스트랩용 임시) |

`k8s_psat` 는 구식 `k8s_sat` 을 대체한다. 구식은 마운트된 ServiceAccount 토큰을 그대로 쓰는데, 이 토큰은 만료가 없고 audience 가 없어 유출 시 무한히 재사용된다. PSAT 는 `audience: spire-server` 로 발급된 단명 토큰이라 다른 곳에 재사용할 수 없다.

**워크로드 어테스테이션**은 Agent 가 로컬 워크로드를 식별하는 단계다. 워크로드는 유닉스 도메인 소켓으로 Workload API 를 호출하고, 아무 토큰도 제시하지 않는다. Agent 는 `SO_PEERCRED`(Linux) 로 소켓 상대의 PID/UID/GID 를 커널에서 직접 얻는다. 이 값은 애플리케이션이 조작할 수 없다. 그다음 PID 로부터 셀렉터를 수집한다.

```
unix:uid:1000
unix:path:/usr/local/bin/order-service
unix:sha256:9f2a...
k8s:ns:payments
k8s:sa:order-service
k8s:pod-label:app:order
k8s:container-image:registry.example.com/order@sha256:...
docker:label:com.example.team:payments
```

Kubernetes 셀렉터는 Agent 가 노드의 kubelet read-only API 를 조회해 PID → 컨테이너 → Pod 를 역추적해서 얻는다. 이미지 다이제스트까지 셀렉터로 쓸 수 있으므로 "이 다이제스트의 이미지만 이 ID 를 받는다" 같은 정책이 가능하다.

등록 항목(registration entry)은 서버에 미리 등록한다.

```bash
# 노드용 부모 엔트리
spire-server entry create \
	-spiffeID spiffe://prod.example.com/ns/spire/sa/spire-agent \
	-selector k8s_psat:cluster:prod-cluster \
	-selector k8s_psat:agent_ns:spire \
	-selector k8s_psat:agent_sa:spire-agent \
	-node

# 워크로드 엔트리
spire-server entry create \
	-parentID spiffe://prod.example.com/ns/spire/sa/spire-agent \
	-spiffeID spiffe://prod.example.com/ns/payments/sa/order-service \
	-selector k8s:ns:payments \
	-selector k8s:sa:order-service \
	-selector k8s:container-image:registry.example.com/order@sha256:9f2a1c... \
	-ttl 3600 \
	-dnsName order.payments.svc.cluster.local
```

셀렉터는 AND 조건이다. 세 개가 모두 일치해야 발급된다. 네임스페이스만 걸면 같은 네임스페이스의 임의 Pod 가 그 ID 를 받아갈 수 있으므로, 최소한 ServiceAccount 는 함께 거는 것이 좋다.

## 4. Workload API 로 mTLS 서버 만들기

Java 애플리케이션에서 SVID 를 받아 mTLS 를 거는 예다. `java-spiffe` 라이브러리를 쓴다.

```java
public final class MutualTlsServerFactory {

	private static final String SOCKET = "unix:///run/spire/sockets/agent.sock";

	public SSLContext createContext() throws Exception {
		X509Source source = DefaultX509Source.newSource(
				DefaultX509Source.X509SourceOptions.builder()
						.spiffeSocketPath(SOCKET)
						.build());

		return SpiffeSslContextFactory.getSslContext(
				SpiffeSslContextFactory.SslContextOptions.builder()
						.x509Source(source)
						.acceptedSpiffeIdsSupplier(() -> Set.of(
								SpiffeId.parse("spiffe://prod.example.com/ns/orders/sa/api-gateway"),
								SpiffeId.parse("spiffe://prod.example.com/ns/payments/sa/ledger")))
						.build());
	}
}
```

`X509Source` 는 Workload API 스트림을 열어두고 SVID 가 갱신될 때마다 내부 KeyManager 를 교체한다. 애플리케이션은 재시작 없이, 심지어 아무 코드도 실행하지 않고 인증서 회전을 받는다. 이 점이 파일 기반 인증서 배포와 결정적으로 다르다 — cert-manager 로 Secret 을 갱신해도 애플리케이션이 파일을 다시 읽지 않으면 소용없고, 그래서 보통 Pod 를 재시작한다.

권한 검증은 반드시 **SAN URI** 를 봐야 한다. 아래는 직접 검증할 때의 최소 구현이다.

```java
public static SpiffeId extractSpiffeId(X509Certificate cert) {
	try {
		Collection<List<?>> sans = cert.getSubjectAlternativeNames();
		if (sans == null) {
			throw new IllegalArgumentException("no SAN in peer certificate");
		}
		for (List<?> san : sans) {
			Integer type = (Integer) san.get(0);
			if (type == 6) {   // GeneralName.uniformResourceIdentifier
				String uri = (String) san.get(1);
				if (uri.startsWith("spiffe://")) {
					return SpiffeId.parse(uri);
				}
			}
		}
		throw new IllegalArgumentException("no SPIFFE URI SAN");
	}
	catch (CertificateParsingException e) {
		throw new IllegalStateException("cannot parse SAN", e);
	}
}
```

CN 은 SPIFFE 스펙에서 의미가 없고 SPIRE 가 채우지 않는다. 레거시 검증 코드가 CN 을 보고 있으면 전부 통과하거나 전부 실패한다.

## 5. JWT-SVID 로 Vault 인증 — 시크릿 제로 해소

Vault 는 JWT auth method 로 OIDC 호환 토큰을 받을 수 있다. SPIRE Server 는 OIDC Discovery Provider 를 함께 띄워 `/.well-known/openid-configuration` 과 JWKS 를 노출한다.

```bash
vault auth enable jwt

vault write auth/jwt/config \
	oidc_discovery_url="https://oidc.prod.example.com" \
	default_role="spiffe-workload"

vault write auth/jwt/role/order-service \
	role_type="jwt" \
	bound_audiences="vault" \
	user_claim="sub" \
	bound_subject="spiffe://prod.example.com/ns/payments/sa/order-service" \
	token_policies="db-readonly" \
	token_ttl="20m"
```

애플리케이션 쪽은 다음과 같다.

```java
JwtSource jwtSource = DefaultJwtSource.newSource(
		DefaultJwtSource.JwtSourceOptions.builder()
				.spiffeSocketPath(SOCKET)
				.build());

JwtSvid svid = jwtSource.fetchJwtSvid("vault");

Map<String, String> body = Map.of("role", "order-service", "jwt", svid.getToken());
// POST /v1/auth/jwt/login → client_token 획득
```

여기에는 저장된 시크릿이 하나도 없다. Agent 소켓 접근 권한이 곳 인증이고, 그 접근 권한은 커널이 강제한다. JWT-SVID 는 기본 5분 수명이므로 캡싱하지 말고 필요할 때마다 받는다.

주의할 점은 JWT-SVID 가 bearer 토큰이라는 것이다. 탈취되면 만료 전까지 재사용된다. X509-SVID 는 개인키가 소켓 밖으로 나가지 않으므로 보유 증명(proof-of-possession)이 성립한다. 그래서 서비스 간 통신은 가능한 한 X509-SVID mTLS 로, JWT-SVID 는 TLS 종단을 제어할 수 없는 경계에서만 쓰는 것이 원칙이다.

## 6. 배포 형태와 소켓 노출

SPIRE Agent 는 노드당 하나의 DaemonSet 으로 뜨고, 소켓을 `hostPath` 로 워크로드 Pod 에 마운트한다.

```yaml
volumes:
  - name: spire-agent-socket
    csi:
      driver: csi.spiffe.io
      readOnly: true
```

`hostPath` 대신 SPIFFE CSI Driver 를 쓰는 편이 낫다. `hostPath` 마운트는 PodSecurity `restricted` 프로필에서 금지되고, 디렉터리 전체를 노출해 다른 파일에 접근할 여지를 준다. CSI 드라이버는 소켓 하나만 읽기 전용으로 투영한다.

소켓에 접근할 수 있는 모든 프로세스는 셀렉터가 맞으면 SVID 를 받는다. 따라서 사이드카나 init 컨테이너가 같은 Pod 에 있으면 같은 셀렉터를 만족해 같은 ID 를 받는다는 점을 인지해야 한다. 컨테이너 단위로 구분하려면 `k8s:container-name` 셀렉터를 추가한다.

## 7. CA 회전과 Nested SPIRE

SPIRE Server 의 CA 도 회전한다. 기본 설정에서 서버 CA 는 24시간마다 새 중간 CA 를 만들고, Trust Bundle 에 신구 CA 를 함께 담아 배포한다. 워크로드는 번들을 통해 신구 양쪽 서명을 검증할 수 있으므로 회전 중에도 통신이 끕기지 않는다. 루트 CA 를 외부(Vault PKI, AWS PCA, 디스크의 오프라인 루트)에 두려면 `UpstreamAuthority` 플러그인을 붙인다.

```hcl
UpstreamAuthority "vault" {
	plugin_data {
		vault_addr    = "https://vault.example.com"
		pki_mount     = "pki-spire"
		ca_cert_path  = "/run/secrets/vault-ca.pem"
		cert_auth {
			client_cert_path = "/run/secrets/vault-client.pem"
			client_key_path  = "/run/secrets/vault-client-key.pem"
		}
	}
}
```

대규모 환경에서는 **Nested SPIRE** 로 서버를 계층화한다. 루트 서버가 중간 서버들에게 SVID 를 발급하고, 각 중간 서버가 자기 클러스터의 Agent 를 담당한다. 클러스터 하나가 죽어도 나머지가 영향받지 않고, 루트는 평소 트래픽을 받지 않으므로 격리 수준이 높다. 반대로 서로 다른 조직/trust domain 을 잇는 경우는 **Federation** 이다 — 양측이 각자의 번들 엔드포인트를 노출하고 상대 번들을 주기적으로 폴링한다.

```hcl
federates_with "partner.example.org" {
	bundle_endpoint_url = "https://spire.partner.example.org/bundle"
	bundle_endpoint_profile "https_spiffe" {
		endpoint_spiffe_id = "spiffe://partner.example.org/spire/server"
	}
}
```

## 8. Istio 대비 트레이드오프

| 항목 | Istio 내장 아이덴티티 | SPIRE |
|---|---|---|
| 적용 범위 | 메시 내 Pod | Pod, VM, 베어메탈, 함수 |
| 어테스테이션 | K8s ServiceAccount 토큰 | 플러그인(IID, PSAT, TPM 등) |
| 애플리케이션 변경 | 없음(사이드카 투명) | Workload API 연동 필요(또는 Envoy SDS 연계) |
| 인증서 수명 | 24시간 기본 | 1시간 기본 |
| 외부 시스템 인증 | 별도 | JWT-SVID + OIDC |
| 운영 부담 | istiod 하나 | Server + Agent + DB + (OIDC provider) |

둘은 배타적이지 않다. Istio 를 SPIRE 와 통합해 istiod 대신 SPIRE 가 SDS 로 인증서를 공급하게 할 수 있고, 이 구성이면 메시 안밖이 같은 trust domain 을 공유한다. 메시 전환이 끝나지 않은 조직, VM 워크로드가 상당수 남은 조직에서 이 조합이 현실적이다.

반대로 전부 Kubernetes 이고 메시가 이미 있다면 SPIRE 를 따로 도입할 이득이 크지 않다. SPIRE 의 가치는 (1) 이기종 인프라를 하나의 아이덴티티 평면으로 묶을 때, (2) 시크릿 제로를 정면으로 해결해야 할 때, (3) TPM 같은 하드웨어 신뢰근거가 요구되는 규제 환경일 때 뚜렷해진다.

도입 시 가장 흔한 실패는 셀렉터를 너무 느슬하게 잡는 것이다. `k8s:ns:default` 하나만 걸어두면 그 네임스페이스에 배포 권한이 있는 누구나 그 아이덴티티를 얻는다. 아이덴티티 시스템을 도입하고도 권한 경계가 그대로인 상태가 된다. 셀렉터 설계는 RBAC 설계와 같은 수준의 검토를 받아야 한다.

## 참고

- SPIFFE Specification — SPIFFE ID and SVID (https://github.com/spiffe/spiffe/tree/main/standards)
- SPIRE Documentation — Node and Workload Attestation (https://spiffe.io/docs/latest/spire-about/spire-concepts/)
- SPIRE Documentation — Nested SPIRE and Federation (https://spiffe.io/docs/latest/planning/federation/)
- java-spiffe Library — X509Source and JwtSource usage
- Evan Gilman, Doug Barth, *Zero Trust Networks*, O'Reilly
