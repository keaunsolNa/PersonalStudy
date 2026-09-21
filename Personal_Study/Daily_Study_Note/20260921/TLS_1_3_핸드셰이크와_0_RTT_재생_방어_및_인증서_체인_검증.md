Notion 원본: https://www.notion.so/3e25a06fd6d381fab6c1ddfe2476a68d

# TLS 1.3 핸드셰이크와 0-RTT 재생 방어 및 인증서 체인 검증

> 2026-09-21 신규 주제 · 확장 대상: Network, 면접을 위한 CS 전공지식 노트

## 학습 목표

- TLS 1.3 의 1-RTT 핸드셰이크 메시지 순서와 키 스케줄 단계를 대응시킨다
- 0-RTT 재생 위험이 성립하는 조건을 판정하고 애플리케이션 계층 방어를 배치한다
- 체인 검증 실패(AIA 누락·만료된 크로스 서명)의 원인을 도구로 분리 진단한다
- 세션 재개·OCSP stapling·ALPN 설정을 지연 예산 기준으로 선택한다

## 1. 1.2 에서 무엇이 사라졌나

TLS 1.3(RFC 8446)의 설계 원칙은 "선택지를 없앤다"였다. 1.2 의 취약점 대부분이 협상 가능한 옵션에서 나왔기 때문이다.

| 제거된 것 | 이유 |
| --- | --- |
| RSA 키 교환 | 전방향 비밀성(PFS) 없음, Bleichenbacher 계열 |
| 정적 DH | PFS 없음 |
| CBC 모드, RC4, 3DES | 패딩 오라클(Lucky13), 스트림 편향 |
| 압축 | CRIME |
| 재협상 | Triple Handshake |
| 커스텀 DH 그룹 | Logjam, 약한 그룹 |
| MAC-then-Encrypt | 구조적 취약 |

남은 것은 **AEAD + (EC)DHE** 조합뿐이고, 암호 스위트는 다섯 개로 줄었다(`TLS_AES_128_GCM_SHA256` 등). 또한 1.3 의 스위트는 서명 알고리즘과 키 교환을 포함하지 않는다. 그것들은 `signature_algorithms`·`supported_groups` 확장으로 따로 협상된다.

이 단순화가 1-RTT 핸드셰이크를 가능하게 했다. 선택지가 적으니 클라이언트가 **추측해서 미리 보낼** 수 있다.

## 2. 핸드셰이크 흐름과 키 스케줄

```
Client                                      Server
ClientHello
  + key_share(x25519, pub_c)
  + supported_versions, signature_algorithms
  + server_name(SNI), alpn        ──────▶
                                           ServerHello
                                             + key_share(pub_s)
                                    ◀─────  {EncryptedExtensions}
                                           {Certificate}
                                           {CertificateVerify}
                                           {Finished}
{Finished}                          ──────▶
[Application Data]                  ◀────▶ [Application Data]

{} = handshake key 로 암호화   [] = application key 로 암호화
```

핵심은 `ServerHello` **직후부터 모든 것이 암호화**된다는 점이다. 1.2 에서 평문으로 노출되던 인증서가 1.3 에서는 보이지 않는다. 다만 `ClientHello` 의 SNI 는 여전히 평문이라, 어느 사이트에 접속하는지는 관찰 가능하다. 이를 가리는 것이 ECH(Encrypted Client Hello)이며 DNS 의 HTTPS RR 로 공개키를 배포한다.

키는 HKDF 기반 스케줄로 단계적으로 파생된다.

```
        0 -> HKDF-Extract(PSK or 0)  = Early Secret
             ├─ ext_binder / res_binder
             └─ client_early_traffic_secret     (0-RTT)
      (EC)DHE -> HKDF-Extract        = Handshake Secret
             ├─ client_handshake_traffic_secret
             └─ server_handshake_traffic_secret
        0 -> HKDF-Extract            = Master Secret
             ├─ client/server_application_traffic_secret_0
             ├─ exporter_master_secret
             └─ resumption_master_secret
```

각 단계가 이전 단계의 전체 핸드셰이크 트랜스크립트 해시를 입력으로 받으므로, 메시지 하나만 변조돼도 `Finished` 검증에서 걸린다. 이것이 1.2 의 다운그레이드 공격 다수를 구조적으로 막는 장치다. 추가로 `ServerHello.random` 하위 8바이트에 다운그레이드 표식을 넣어, 1.3 을 지원하는 서버가 1.2 로 내려간 경우를 클라이언트가 탐지한다.

## 3. HelloRetryRequest — 추측이 빗나갔을 때

클라이언트는 `key_share` 에 자신이 선호하는 그룹(보통 x25519) 하나만 넣는다. 서버가 그 그룹을 지원하지 않으면 `HelloRetryRequest` 로 원하는 그룹을 지정하고, 클라이언트가 다시 `ClientHello` 를 보낸다. **이 경우 2-RTT 가 되어 1.3 의 이점이 사라진다.**

실무 영향은 포스트 양자 하이브리드 전환에서 커졌다. `X25519MLKEM768` 같은 하이브리드 그룹은 key_share 가 1KB 를 넘어 `ClientHello` 가 여러 TCP 세그먼트로 쪼개진다. 클라이언트가 x25519 와 하이브리드를 둘 다 보내면 HRR 은 피하지만 초기 플라이트가 커지고, 하나만 보내면 서버 지원 여부에 따라 HRR 이 난다.

```bash
# 협상된 그룹 확인
openssl s_client -connect example.com:443 -tls1_3 -brief 2>&1 | grep -i 'group\|cipher'

# 특정 그룹 강제
openssl s_client -connect example.com:443 -groups X25519MLKEM768
```

서버 설정에서는 선호 그룹 목록의 순서가 곳 HRR 발생률이다. 클라이언트 분포를 모른 채 이국적인 그룹을 1순위로 두면 대부분의 접속이 2-RTT 가 된다.

## 4. 0-RTT 와 재생 공격

세션 재개 시 클라이언트는 이전에 받은 PSK 로 파생한 `client_early_traffic_secret` 으로 **첫 플라이트에 애플리케이션 데이터를 실어 보낼 수 있다**. 왕복 없이 요청이 도착하므로 지연이 크게 준다.

대가는 **재생 가능성**이다. 0-RTT 데이터는 핸드셰이크가 완료되기 전에 처리되므로, 서버의 살아 있는 nonce 와 결합되지 않는다. 공격자가 그 패킷을 그대로 복사해 여러 번 보내면 서버는 같은 요청을 여러 번 받는다.

```
공격자: [0-RTT: POST /transfer {amount:100}] 를 캐처
        → 동일 바이트열을 N회 재전송
서버  : PSK 가 유효하고 early data 를 수락하면 N번 처리
```

방어는 세 층으로 나뉘다.

**(1) 프로토콜 계층 — 단일 사용 티켓.** 서버가 티켓을 한 번만 받아들이고 기록한다. 완전하지만 분산 환경에서는 모든 노드가 사용 기록을 공유해야 해 비용이 크고, 티켓 저장소 자체가 병목이 된다.

**(2) 시간 창 제한.** `ticket_age` 와 서버 시계를 비교해 허용 창(수 초)을 벗어나면 거절한다. 창 안에서의 재생은 여전히 가능하므로 부분적 완화다.

**(3) 애플리케이션 계층 — 멱등 요청만 허용.** 가장 현실적인 방어다. 서버가 early data 로 온 요청의 메서드를 보고 비멱등이면 거절하거나 1-RTT 로 재전송을 요구한다.

```nginx
# nginx: 0-RTT 활성화 + 안전장치
ssl_early_data on;
proxy_set_header Early-Data $ssl_early_data;   # 애플리케이션에 전달
```

애플리케이션은 `Early-Data: 1` 헤더가 있으면서 메서드가 GET/HEAD 가 아니면 **425 Too Early** 를 반환해야 한다(RFC 8470). 클라이언트는 425 를 받으면 핸드셰이크 완료 후 재전송한다.

```java
@Component
public class EarlyDataFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("1".equals(req.getHeader("Early-Data")) && !SAFE.contains(req.getMethod())) {
            res.setStatus(425);
            return;
        }
        chain.doFilter(req, res);
    }
}
```

또 하나 자주 놓치는 점은 **0-RTT 데이터에 전방향 비밀성이 없다**는 것이다. PSK 가 유출되면 early data 는 복호화된다. 세션 티켓 암호화 키(STEK)를 주기적으로 교체해야 하는 이유이며, 교체 주기가 곳 노출 창이다.

판단 기준: **정적 자산 CDN 은 0-RTT 를 켜고, API 게이트웨이는 GET 한정으로 켜거나 끔다.** 인증 토큰이 실리는 경로에서 얻는 수십 ms 는 재생 리스크를 감수할 만큼 크지 않은 경우가 많다.

## 5. 인증서 체인 검증

검증은 리프에서 시작해 신뢰 앵커까지 경로를 만드는 과정이다(RFC 5280 path validation). 각 단계에서 확인하는 것은 다음과 같다.

```
1. 서명 검증        : child.signature == verify(parent.publicKey)
2. 유효기간         : notBefore ≤ now ≤ notAfter (전체 체인)
3. 이름 제약        : SAN 이 요청 호스트와 매칭 (CN 은 더 이상 안 봄)
4. 용도             : KeyUsage / ExtendedKeyUsage(serverAuth)
5. basicConstraints : 중간 CA 는 CA:TRUE, pathLen 준수
6. 폐기 상태         : CRL / OCSP / CRLite
7. SCT              : Certificate Transparency 로그 증명 (브라우저 요구)
```

실무 장애의 압도적 1위는 **중간 인증서 누락**이다. 서버는 리프와 중간 CA 를 모두 보내야 하는데, 중간을 빠뜨려도 일부 클라이언트(브라우저)는 AIA(Authority Information Access) 확장의 URL 로 내려받아 스스로 메운다. 그래서 "브라우저는 되는데 curl/Java 는 안 되는" 현상이 생긴다. **AIA fetching 에 의존하지 말고 체인을 완전히 보내야 한다.**

```bash
# 서버가 실제 보내는 체인 확인
openssl s_client -connect example.com:443 -showcerts </dev/null 2>/dev/null \
  | grep -E 's:|i:'

# 체인 완전성 검증 (AIA 도움 없이)
openssl s_client -connect example.com:443 -verify_return_error -CApath /etc/ssl/certs

# 만료일 일괄 확인
echo | openssl s_client -connect example.com:443 2>/dev/null \
  | openssl x509 -noout -dates -subject -ext subjectAltName
```

두 번째로 흔한 것이 **만료된 크로스 서명 루트**다. 신규 루트를 구형 루트로 크로스 서명해 하위 호환을 유지하다가, 구형 루트가 만료되는 날 구형 클라이언트가 일제히 실패한다. 2021년 DST Root CA X3 만료가 대표 사례다. 이때 서버가 보내는 체인에 만료된 크로스 서명이 포함돼 있으면, **OpenSSL 1.0.x 계열은 대체 경로를 찾지 못하고 실패**한다(1.1.1 이후 개선). 대응은 체인에서 만료 경로를 빼고 짧은 체인을 보내는 것이다.

## 6. 폐기 확인과 OCSP Stapling

CRL 은 목록 전체를 받아야 해 크고 느리다. OCSP 는 개별 조회지만 **클라이언트가 CA 에 접속하는 순간 접속 사실이 CA 에 노출**되고, CA 가 느리면 연결 지연이 된다.

OCSP Stapling 은 서버가 미리 받아 둔 서명된 응답을 핸드셰이크에 동봉한다. 프라이버시와 지연 문제를 동시에 해결한다.

```nginx
ssl_stapling on;
ssl_stapling_verify on;
ssl_trusted_certificate /etc/ssl/certs/chain.pem;
resolver 1.1.1.1 8.8.8.8 valid=300s;   # 없으면 stapling 이 조용히 비활성
```

`resolver` 누락으로 stapling 이 동작하지 않는 설정이 매우 흔하다. 확인은 이렇게 한다.

```bash
openssl s_client -connect example.com:443 -status </dev/null 2>/dev/null \
  | grep -A 10 'OCSP Response Status'
```

주의할 점은 **stapling 이 없어도 대부분의 클라이언트는 연결을 허용한다**는 것이다(soft-fail). 공격자가 OCSP 응답을 막으면 폐기된 인증서도 통과한다. 이를 막는 `OCSP Must-Staple`(TLS Feature 확장)은 서버가 stapling 에 실패하면 연결이 아예 끊기므로, 운영 리스크가 커 채택률이 낮다.

업계 방향은 **인증서 수명 단축**으로 기울었다. 90일에서 더 짧아지는 추세이며, 수명이 짧으면 폐기 메커니즘의 중요도 자체가 내려간다. ACME 자동 갱신이 전제 조건이고, 갱신 실패 알람이 폐기 확인보다 실질적으로 더 중요한 운영 항목이 된다.

## 7. 세션 재개와 티켓 관리

| 방식 | 서버 상태 | 확장성 | PFS |
| --- | --- | --- | --- |
| 세션 ID(1.2) | 필요 | 낮음 | 유지 |
| 세션 티켓 | 불필요(STEK) | 높음 | STEK 유출 시 손상 |
| PSK(1.3) | 티켓 기반 | 높음 | `psk_dhe_ke` 면 유지 |

TLS 1.3 에서는 세션 ID 가 사라지고 PSK 로 통합됐다. 재개 시 `psk_key_exchange_modes` 를 `psk_dhe_ke` 로 두면 재개에도 새 DH 교환이 포함되어 전방향 비밀성이 유지된다. `psk_ke`(DH 없음)는 빠르지만 PFS 를 잃으므로 기본으로 쓰지 않는다.

다중 서버 환경에서는 **STEK 를 전 노드가 공유해야** 재개가 동작한다. 공유하지 않으면 로드밸런서가 다른 노드로 보낼 때마다 전체 핸드셰이크가 다시 일어난다.

```nginx
ssl_session_tickets on;
ssl_session_ticket_key /etc/nginx/ticket/current.key;   # 순서가 중요
ssl_session_ticket_key /etc/nginx/ticket/previous.key;  # 이전 키로 복호화만
ssl_session_timeout 1h;
```

첫 번째 키로 암호화하고 나머지 키로는 복호화만 하므로, 키 교체 시 무중단 전환이 된다. 교체 주기는 티켓 수명보다 짧게(예: 수명 1시간, 교체 45분) 잡는다. 키 파일 관리는 시크릿 관리 도구로 배포하고, **키를 영구 보관하면 PFS 가 무의미해진다**는 점을 기억한다.

## 8. 설정 점검과 진단 순서

문제가 났을 때의 분리 진단 순서는 다음과 같다.

```bash
# 1) 프로토콜·암호 협상 자체
openssl s_client -connect host:443 -tls1_3 -brief

# 2) 체인 완전성 (클라이언트 신뢰 저장소 없이)
openssl s_client -connect host:443 -showcerts -verify_return_error

# 3) 이름 매칭
openssl s_client -connect host:443 -verify_hostname host

# 4) ALPN 협상 (HTTP/2, HTTP/3 전환 확인)
openssl s_client -connect host:443 -alpn h2,http/1.1 -brief | grep ALPN

# 5) 0-RTT 수락 여부
openssl s_client -connect host:443 -sess_out s.pem </dev/null
openssl s_client -connect host:443 -sess_in s.pem -early_data req.txt
```

| 증상 | 1순위 원인 | 확인 |
| --- | --- | --- |
| 브라우저 OK, Java/curl 실패 | 중간 CA 누락 | `-showcerts` 체인 길이 |
| 특정 구형 기기만 실패 | 만료 크로스 서명 | 체인의 issuer 만료일 |
| 핸드셰이크가 2-RTT | HelloRetryRequest | 협상 그룹 확인 |
| 재개가 안 됨 | STEK 미공유 | 노드별 반복 접속 테스트 |
| stapling 미동작 | resolver 누락 | `-status` 응답 |
| 간헐적 중복 처리 | 0-RTT 재생 | `Early-Data` 헤더 로그 |

마지막으로 설정의 기준선은 직접 만들지 말고 **Mozilla SSL Configuration Generator 의 intermediate 프로파일**을 출발점으로 삼는 편이 안전하다. 그 위에 조직 요구(HSTS preload, Must-Staple, 특정 그룹 선호)를 얹고, 변경할 때마다 위 진단 명령으로 회귀를 확인하는 것이 실무적인 운영 방식이다.

## 참고

- RFC 8446 — The Transport Layer Security (TLS) Protocol Version 1.3
- RFC 8470 — Using Early Data in HTTP (425 Too Early)
- RFC 5280 — X.509 Certificate and CRL Profile (Path Validation)
- RFC 6962 — Certificate Transparency
- Mozilla SSL Configuration Generator / Server Side TLS Guidelines
- OpenSSL `s_client` 매뉴얼 페이지
