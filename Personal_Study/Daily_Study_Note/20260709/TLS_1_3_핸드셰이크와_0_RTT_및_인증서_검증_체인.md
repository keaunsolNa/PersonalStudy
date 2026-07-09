Notion 원본: https://app.notion.com/p/3985a06fd6d3817e8d58cfa0dfc76f31

# TLS 1.3 핸드셰이크와 0-RTT 및 인증서 검증 체인

> 2026-07-09 신규 주제 · 확장 대상: CS

## 학습 목표

- TLS 1.3 의 1-RTT 핸드셰이크 메시지 흐름과 키 스케줄을 단계별로 설명한다
- TLS 1.2 대비 제거된 라운드트립·취약 알고리즘의 변화를 구분한다
- 0-RTT(early data)의 이점과 replay 공격 위험 및 완화책을 판별한다
- 인증서 체인 검증과 SNI·OCSP stapling 의 역할을 분석한다

## 1. TLS 1.3 이 바꿈 것

TLS 1.3(RFC 8446)은 핸드셰이크를 1-RTT 로 단축하고 RSA 키교환·정적 DH·CBC·RC4·SHA-1·압축·재협상을 제거했다. 키교환은 전방 안전성을 항상 보장하는 (EC)DHE 만, 대칭은 AEAD(AES-GCM, ChaCha20-Poly1305)만 허용된다. 클라이언트가 첫 메시지에 key share 를 미리 보내 협상을 2-RTT 에서 1-RTT 로 줄였다.

## 2. 1-RTT 핸드셰이크 흐름

클라이언트가 ClientHello(cipher, supported_versions, key_share)를 보내면 서버가 ServerHello(cipher, key_share)로 응답하고 이 시점에 DH 공유 비밀을 계산해 이후 메시지를 암호화한다. 서버는 EncryptedExtensions, Certificate, CertificateVerify(개인키로 트랜스크립트에 서명), Finished 를 이어 보낸다. 키 스케줄은 HKDF 기반으로 handshake_traffic_secret 과 application_traffic_secret 을 계층 파생한다.

```text
ClientHello (key_share, cipher_suites)  →
         ← ServerHello (key_share), {Certificate}, {CertificateVerify}, {Finished}
{Finished} + {Application Data}  →
```

## 3. 0-RTT Early Data 와 replay 위험

이전 세션 티켓(PSK)을 이용하면 ClientHello 와 동시에 앱 데이터를 보내 첫 요청 지연을 없앨다. 그러나 early data 는 서버가 신선도를 검증하기 전에 도착해 replay 에 취약하므로 멱등한(GET) 요청만 허용해야 한다. 상태 변경(POST·결제)은 금지. 완화책으로 단일 사용 티켓·시간 창, 앱 계층 멱등성 키를 쓴다.

## 4. 인증서 체인 검증

클라이언트는 리프에서 시작해 각 인증서 서명을 상위 공개키로 검증하며 올라가 신뢰 저장소의 루트 CA 에 도달하는지 확인한다. 서명 체인 외에 유효기간, SAN 도메인 일치(CN은 무시), Key Usage, 폐기 여부를 함께 검사한다.

```bash
openssl s_client -connect example.com:443 -servername example.com </dev/null 2>/dev/null | openssl x509 -noout -dates
```

## 5. SNI 와 CertificateVerify 의 의미

SNI 는 접속 호스트명을 평문으로 알려 한 IP 다수 도메인 서버가 올바른 인증서를 고르게 한다(가림은 ECH가 진화 중). CertificateVerify 는 서버가 개인키를 실제 보유함을 증명해 중간자의 남의 인증서 재사용 공격을 차단한다.

## 6. OCSP stapling 과 폐기 확인

OCSP stapling 은 서버가 미리 받은 서명된 OCSP 응답을 핸드셰이크 중 붙여 전달해 클라이언트가 CA 직접 질의할 필요를 없앨다. Must-Staple 을 지정하면 응답이 없는 연결을 거부해 soft-fail 문제를 막는다.

```bash
openssl s_client -connect example.com:443 -servername example.com -status </dev/null 2>/dev/null | grep -A5 "OCSP Response Status"
```

## 7. 검증 예시

```bash
openssl s_client -connect example.com:443 -tls1_3 -servername example.com </dev/null 2>/dev/null | grep -q TLSv1.3 && echo PASS
# TLS 1.0 은 거부되어야 정상
```

testssl.sh / SSL Labs 로 취약 암호군·체인 누락·stapling 상태를 CI 임계값으로 점검한다.

## 참고

- RFC 8446 — TLS 1.3
- RFC 8446 §2.3 Zero-RTT, Appendix E.5 Replay Attacks on 0-RTT
- Cloudflare Learning Center — TLS 1.3 handshake / OCSP stapling
- Mozilla Server Side TLS 설정 가이드
