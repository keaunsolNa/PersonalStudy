Notion 원본: https://www.notion.so/37f5a06fd6d38119969be74bf0dfa323

# HTTP/3 QUIC와 QPACK 및 Stream Multiplexing Head-of-Line Blocking 제거

> 2026-06-14 신규 주제 · 확장 대상: 통신_네트워크 / REST_API

## 학습 목표

- HTTP/2 의 TCP 레벨 HOL blocking 원인을 패킷 손실 관점에서 설명한다
- QUIC 의 독립 스트림과 0-RTT 핸드셰이크 구조를 추적한다
- QPACK 이 HPACK 의 HOL blocking 을 어떻게 분리하는지 설명한다
- HTTP/3 도입의 실측 이점과 제약(UDP, CPU 비용)을 판단한다

## 1. HTTP/2 가 풀지 못한 HOL blocking

HTTP/2 는 한 TCP 연결 위에서 여러 요청을 stream 으로 다중화(multiplexing)해, HTTP/1.1 의 응답 순차 처리(application-level HOL blocking)를 해결했다. 그러나 그 아래 TCP 계층에는 여전히 head-of-line blocking 이 남는다. TCP 는 바이트 스트림을 순서대로 보장하므로, 중간 세그먼트 하나가 유실되면 그 뒤에 도착한 모든 세그먼트가 커널 버퍼에 갇혀 애플리케이션으로 올라가지 못한다. HTTP/2 의 stream A·B·C 가 한 TCP 연결을 공유할 때, A 의 패킷 하나가 유실되면 이미 도착한 B·C 의 데이터까지 재전송을 기다리며 멈춘다. 다중화는 했지만 손실 복구는 연결 전체가 함께 받는 것이다.

이 문제는 TCP 가 stream 개념을 모른다는 구조적 한계에서 온다. 손실률이 높은 모바일·무선 환경일수록 HTTP/2 의 단일 연결 다중화가 오히려 불리해지는 역설이 생긴다.

## 2. QUIC: UDP 위에 stream-aware 전송

HTTP/3 의 토대인 QUIC 은 UDP 위에 구현된 사용자 공간 전송 프로토콜로, stream 을 전송 계층의 1급 개념으로 끌어올렸다. 각 stream 은 독립적인 순서 보장·흐름 제어를 가지며, 한 stream 의 패킷 손실은 그 stream 만 멈춘다. stream A 의 패킷이 유실되어도 B·C 의 데이터는 즉시 애플리케이션에 전달된다. TCP 레벨 HOL blocking 이 전송 계층에서 사라지는 것이다.

QUIC 이 UDP 를 택한 이유는 TCP 가 OS 커널에 박혀 있어 진화가 느리고, 미들박스(방화벽·NAT)가 TCP 옵션을 임의로 변형해 새 기능 배포가 막히기 때문이다. UDP 위에 전송 로직을 사용자 공간에 올리면 애플리케이션·라이브러리 업데이트만으로 혼잡 제어와 손실 복구를 개선할 수 있다.

QUIC 은 또한 연결 식별을 IP·포트 4-tuple 이 아닌 Connection ID 로 한다. 그래서 모바일이 Wi-Fi 에서 LTE 로 바뀌어 IP 가 변해도 같은 Connection ID 로 연결이 유지되는 connection migration 이 가능하다. TCP 라면 4-tuple 이 깨져 연결이 끊기고 재핸드셰이크가 필요한 상황이다.

## 3. 핸드셰이크 통합과 0-RTT

TCP+TLS 1.2 는 TCP 3-way(1 RTT) 뒤에 TLS 핸드셰이크(2 RTT)가 따라와 데이터 전송까지 여러 왕복이 든다. TLS 1.3 이 이를 1-RTT 로 줄였지만 TCP 핸드셰이크가 여전히 선행한다. QUIC 은 전송 핸드셰이크와 TLS 1.3 암호 핸드셰이크를 하나로 합쳤 첫 연결도 1-RTT 에 데이터를 보내기 시작한다.

```
신규 연결:  Client → Initial(ClientHello) → Server → 데이터 시작  (1 RTT)
재방문:     Client → 0-RTT 데이터(이전 세션 티켓 사용) 즉시 전송   (0 RTT)
```

이미 통신했던 서버에는 0-RTT 로 첫 패킷에 곧바로 응답 데이터를 실어 보낼 수 있다. 다만 0-RTT 데이터는 재전송 공격(replay)에 취약하므로, 멱등하지 않은 요청(POST 결제 등)에는 쓰지 않고 GET 같은 안전한 요청에만 적용하는 것이 원칙이다.

## 4. QPACK: HPACK 의 HOL blocking 분리

HTTP/2 는 헤더 압축에 HPACK 을 쓴다. HPACK 은 이전에 보낸 헤더를 동적 테이블에 인덱싱해 반복 헤더를 작은 인덱스로 치환하는데, 이 테이블이 stream 간 공유되고 순서에 의존한다. 그런데 QUIC 처럼 stream 이 독립적으로 도착하면, 동적 테이블 갱신 순서가 보장되지 않아 압축이 깨질 수 있다. 그래서 HTTP/3 은 HPACK 대신 QPACK 을 쓴다.

QPACK 의 핵심은 헤더 데이터를 보내는 stream 과 동적 테이블을 갱신하는 명령을 별도 채널로 분리하고, 각 헤더 블록이 자신이 의존하는 테이블 상태(Required Insert Count)를 명시하는 것이다. 디코더는 필요한 테이블 항목이 아직 도착하지 않았으면 그 헤더 블록만 잠시 보류하고 다른 stream 은 계속 처리한다. 즉 압축 효율을 위해 인코더가 동적 테이블 참조를 적극 쓰면 약간의 의존성 blocking 이 생기고, 정적 참조만 쓰면 blocking 은 없지만 압축률이 떨어진다. 이 trade-off 를 `qpack-blocked-streams` 와 동적 테이블 용량으로 조절한다.

```
# HTTP/3 응답 헤더 블록(개념)
:status: 200                  → 정적 테이블 인덱스, 즉시 디코드
content-type: application/json → 동적 테이블 참조, Required Insert Count 충족 시 디코드
```

## 5. 운영 관점의 이점과 비용

QUIC/HTTP/3 의 이점은 손실·고지연 환경에서 두드러진다. 패킷 손실이 잦은 모바일에서 stream 독립성 덕에 체감 지연이 줄고, connection migration 으로 네트워크 전환 시 끊김이 사라지며, 0-RTT 로 재방문 응답이 빨라진다. 반면 비용도 분명하다.

| 항목 | TCP/HTTP2 | QUIC/HTTP3 |
|------|-----------|-----------|
| HOL blocking | TCP 레벨 잔존 | 전송 계층 제거 |
| 핸드셰이크 | 1~2 RTT | 1 RTT / 재방문 0-RTT |
| 혼잡제어 위치 | 커널(고정) | 사용자 공간(교체 가능) |
| CPU 비용 | 커널 오프로드 성숙 | 사용자 공간 처리로 높음 |
| 미들박스 호환 | 매우 성숙 | UDP 차단 환경 존재 |

가장 현실적인 제약은 두 가지다. 첫째 일부 기업·통신망이 UDP 443 을 차단하거나 제한해, HTTP/3 가 실패하면 HTTP/2 로 폴백하는 happy eyeballs 식 이중 경로가 필요하다. 둘째 QUIC 은 패킷 처리·암호화가 사용자 공간에서 일어나 CPU 사용량이 TCP 보다 높다. 커널의 UDP GSO/GRO, sendmmsg 같은 가속 경로와 하드웨어 오프로드가 성숙해지며 격차가 줄고 있으나, 대규모 정적 콘텐츠 서버에서는 여전히 고려 대상이다.

## 6. 도입 가이드와 결론

서버는 보통 Alt-Svc 헤더로 HTTP/3 가용성을 광고하고, 클라이언트가 다음 연결부터 QUIC 을 시도하게 한다. 따라서 HTTP/3 는 HTTP/2 를 대체하기보다 그 위에 얇는 선택적 업그레이드 경로로 배포된다.

```
Alt-Svc: h3=":443"; ma=86400   # 이 출처는 443/UDP 로 HTTP/3 지원, 24h 캐시
```

"HTTP/3 로 바꾸면 항상 빨라지는가"의 답은 No 다. 저손실·저지연 유선 환경에서는 HTTP/2 와 차이가 작거나 CPU 비용 탓에 불리할 수도 있다. 반면 모바일·고손실·전 세계 분산 사용자처럼 손실과 RTT 가 큰 환경에서는 명확히 유리하다. 대상 트래픽의 손실률과 RTT 분포를 측정해 적용 여부를 정하는 것이 핵심이다.

## 7. 손실 복구와 혼잡 제어의 재설계

QUIC 은 손실 복구도 TCP 보다 정교하게 다시 짰다. TCP 는 재전송 패킷에 원본과 같은 sequence number 를 재사용해, ACK 가 원본에 대한 것인지 재전송에 대한 것인지 구분하지 못하는 retransmission ambiguity 문제가 있었다. RTT 추정이 흐려지는 원인이다. QUIC 은 모든 패킷에 단조 증가하는 packet number 를 부여하고 재전송 시에도 새 번호를 쓴다. 데이터의 재전송은 패킷 번호가 아니라 stream offset 으로 식별되므로, ACK 가 항상 어떤 전송에 대한 것인지 명확하고 RTT 표본이 정확해진다.

```
TCP:  seq=1000 전송 → 손실 추정 → seq=1000 재전송 → ACK 도착(어느 것?) → RTT 모호
QUIC: pkt=5(offset 1000) → 손실 → pkt=9(같은 offset 1000) → ACK 5/9 구분 가능
```

또한 QUIC 의 ACK 프레임은 TCP SACK 보다 많은 ack range 를 표현할 수 있어, 다수의 산발적 손실이 있는 환경에서 어떤 패킷이 빠졌는지 더 풍부하게 알린다. 혼잡 제어 알고리즘(기본 CUBIC, 선택적 BBR)은 사용자 공간에 있어 애플리케이션 배포만으로 교체·튜닝이 가능하다. TCP 처럼 커널·OS 업그레이드를 기다릴 필요가 없으므로, 서비스 특성에 맞춰 혼잡 제어를 실험하고 점진 배포하기 쉽다.

## 8. 스트림 우선순위와 흐름 제어

HTTP/3 는 RFC 9218 의 Extensible Priorities 로 우선순위를 다룬다. HTTP/2 의 복잡한 의존성 트리 기반 우선순위는 구현 편차가 커 사실상 폐기되었고, HTTP/3 는 `urgency`(0~7)와 `incremental` 두 파라미터만 쓰는 단순한 모델로 대체했다. 클라이언트가 `Priority` 헤더나 PRIORITY_UPDATE 프레임으로 "이 응답을 먼저 달라"고 표현하면 서버가 전송 순서를 조정한다.

```
priority: u=1, i           # urgency=1(높음), incremental(점진 렌더 가능)
priority: u=5              # urgency=5(낮음), 비점진 — 예: 푸터 이미지
```

흐름 제어는 두 층위로 작동한다. stream 단위 흐름 제어는 한 stream 이 수신자 버퍼를 독점하지 못하게 막고, connection 단위 흐름 제어는 전체 연결의 미처리 데이터 총량을 제한한다. 송신자는 `MAX_STREAM_DATA`·`MAX_DATA` 프레임으로 허용된 윈도우 안에서만 보내고, 수신자가 데이터를 소비하면 윈도우를 늘려 통지한다. 이 이중 흐름 제어가 stream 독립성을 유지하면서도 메모리 폭주를 막는 장치다.

## 참고

- RFC 9000 (QUIC Transport), RFC 9001 (QUIC-TLS), RFC 9114 (HTTP/3), RFC 9204 (QPACK)
- RFC 9218 (Extensible Prioritization Scheme for HTTP)
- Daniel Stenberg, "HTTP/3 Explained" (http3-explained.haxx.se)
- Robin Marx, "HTTP/3: From root to tip" 및 QUIC HOL blocking 분석 시리즈
- Cloudflare/Fastly 엔지니어링 블로그 — QUIC CPU 비용 및 UDP 오프로드 최적화
