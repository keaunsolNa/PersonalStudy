Notion 원본: https://app.notion.com/p/3c15a06fd6d38120a2cdcdc98e6538b3?pvs=204

# HTTP/3와 QUIC 스트림 다중화 및 커넥션 마이그레이션

> 2026-08-19 신규 주제 · 확장 대상: 통신_네트워크

## 학습 목표

- QUIC 패킷·프레임 2계층 구조를 해석하고 TCP 세그먼트와의 차이를 설명 대신 계산으로 확인한다
- HOL 블로킹이 TCP/HTTP2 와 QUIC/HTTP3 에서 각각 어디까지 남는지 경계를 긋는다
- Connection ID 기반 마이그레이션이 NAT rebinding 을 흡수하는 절차를 추적한다
- QPACK 의 동적 테이블이 만드는 스트림 간 의존을 이해하고 blocked stream 설정을 조정한다

## 1. QUIC 이 해결하려던 문제의 범위

HTTP/2 는 하나의 TCP 연결 위에 여러 스트림을 다중화했다. 애플리케이션 계층에서는 스트림이 독립적이지만, 그 아래 TCP 는 단일 바이트 스트림이므로 세그먼트 하나가 유실되면 뒤따라 도착한 모든 데이터가 커널 수신 버퍼에 갇힌다. 스트림 A 의 패킷이 빠졌는데 스트림 B, C 의 데이터가 멀쩡히 도착해도 애플리케이션은 아무것도 읽지 못한다. 이것이 전송 계층 HOL(head-of-line) 블로킹이고, HTTP/2 가 애플리케이션 계층 HOL 만 없앴다는 한계다.

손실률이 낮으면 이 문제는 눈에 띄지 않는다. 그러나 모바일 환경의 1~3% 패킷 손실에서는 HTTP/2 가 HTTP/1.1 6-connection 보다 느려지는 역전이 실측으로 재현된다. 연결이 하나뿐이라 손실 하나가 전체를 멈추기 때문이다.

QUIC 의 답은 전송 계층을 UDP 위에서 재구현하는 것이다. 스트림 단위 재전송·순서 보장을 QUIC 자신이 하므로, 스트림 A 의 유실은 스트림 A 만 멈춘다. 동시에 TLS 1.3 을 전송 계층에 통합해 핸드셰이크 왕복을 줄였고, 4-tuple 대신 Connection ID 로 연결을 식별해 IP 가 바뀌어도 연결이 유지된다. 커널이 아닌 유저스페이스 구현이라는 점은 배포 속도라는 이득과 CPU 사용량이라는 비용을 동시에 만든다.

## 2. 패킷과 프레임의 2계층 구조

QUIC 은 UDP 데이터그램 안에 하나 이상의 QUIC 패킷을 담고, 각 패킷 페이로드에 여러 프레임을 담는다. 이 구분이 중요한 이유는 **재전송 단위가 패킷이 아니라 프레임**이기 때문이다.

```text
UDP Datagram
└── QUIC Packet (헤더 보호 + AEAD 암호화)
    ├── Packet Number (단조 증가, 재전송해도 절대 재사용 안 함)
    └── Payload
        ├── STREAM frame  (stream_id, offset, length, data)
        ├── ACK frame     (largest_acked, ack_delay, ack_ranges)
        ├── CRYPTO frame  (핸드셰이크 데이터)
        └── MAX_DATA / MAX_STREAM_DATA (흐름 제어)
```

TCP 는 유실된 세그먼트를 같은 시퀀스 번호로 다시 보낸다. 그래서 ACK 를 받았을 때 그것이 원본에 대한 것인지 재전송분에 대한 것인지 모호해지고(재전송 모호성), RTT 추정이 왜곡된다. QUIC 은 패킷 번호를 절대 재사용하지 않는다. 유실된 STREAM 프레임의 데이터는 **새 패킷 번호를 가진 새 패킷**에 실려 나간다. 따라서 모든 ACK 는 정확히 하나의 전송 시도에 대응하고, RTT 샘플이 항상 유효하다. 이 하나의 설계가 손실 감지와 혼잡 제어 정확도를 크게 끌어올린다.

패킷 번호 공간은 암호화 레벨별로 분리된다(Initial / Handshake / Application). 핸드셰이크 중 재전송이 애플리케이션 데이터의 손실 판정에 섞이지 않게 하기 위해서다.

## 3. 손실 감지와 혼잡 제어

QUIC 은 TCP 의 중복 ACK 기반 빠른 재전송 대신 **패킷 번호 기반 임계값**을 쓴다. RFC 9002 의 기본 규칙은 두 가지다. 첫째, 어떤 패킷보다 번호가 kPacketThreshold(기본 3) 이상 큰 패킷이 ACK 되면 그 패킷은 유실로 간주한다. 둘째, 시간 임계값 `max(kTimeThreshold * max(smoothed_rtt, latest_rtt), kGranularity)` 를 넘겨도 ACK 가 없으면 유실로 본다. kTimeThreshold 는 9/8 이다.

재정렬이 잦은 경로에서는 번호 임계값만으로 오탐이 나므로 시간 임계값이 보완한다. 이 둘의 조합은 TCP 의 SACK+RACK 과 유사하지만, QUIC 은 처음부터 이 방식으로 설계되어 예외 경로가 없다.

PTO(Probe Timeout)는 TCP RTO 에 대응하는데 결정적 차이가 있다. RTO 는 발동 시 혼잡 윈도우를 1로 붕괴시키지만, QUIC 의 PTO 는 **탐침 패킷만 보내고 혼잡 윈도우를 건드리지 않는다**. 실제 유실이 확인된 뒤에야 윈도우를 줄인다. 무선 구간의 일시적 지연 스파이크가 처리량을 무너뜨리는 현상이 이 설계로 완화된다.

ACK 프레임은 TCP SACK 보다 표현력이 높다. TCP 옵션은 40바이트 한계 때문에 SACK 블록을 3~4개밖에 못 싣지만, QUIC ACK 프레임은 가변 길이 정수로 임의 개수의 범위를 담는다. 고손실·고대역 경로에서 이 차이는 재전송 효율로 직결된다.

## 4. HOL 블로킹이 남는 지점

QUIC 이 전송 계층 HOL 을 없앴다는 서술은 정확히는 "스트림 간 HOL 을 없앴다"는 뜻이다. 남는 곳이 있다.

| 계층 | HTTP/2 over TCP | HTTP/3 over QUIC |
|---|---|---|
| 스트림 간 데이터 | 블로킹 발생 | 없음 |
| 동일 스트림 내부 | 블로킹 발생(당연) | 블로킹 발생(당연) |
| 헤더 압축 테이블 | HPACK 순서 의존으로 발생 | QPACK 동적 테이블 참조 시 발생 |
| UDP GSO/GRO 배치 | 해당 없음 | 배치 단위 유실 시 부분 발생 |

QPACK 이 핵심이다. HPACK 은 순서가 보장된 단일 스트림을 전제로 동적 테이블을 갱신하므로 QUIC 에 그대로 못 쓴다. QPACK 은 인코더 스트림·디코더 스트림을 별도로 두고, 요청 헤더가 동적 테이블 엔트리를 참조할 때 "이 엔트리 이상이 도착해 있어야 한다"는 Required Insert Count 를 명시한다. 아직 인코더 스트림이 그 지점까지 도착하지 않았으면 그 요청은 **blocked stream** 이 되어 대기한다.

```text
SETTINGS_QPACK_MAX_TABLE_CAPACITY = 4096   # 동적 테이블 크기
SETTINGS_QPACK_BLOCKED_STREAMS    = 16     # 동시 블록 허용 스트림 수
```

`QPACK_BLOCKED_STREAMS=0` 으로 두면 동적 테이블 참조를 아예 금지해 HOL 을 완전히 제거하지만 압축률이 떨어진다. 값을 키우면 압축률은 오르나 인코더 스트림 유실 시 다수 요청이 함께 멈춘다. 실무 기본값은 16 전후이고, 손실률이 높은 모바일 대상 서비스는 이를 낮추는 쪽이 안정적이다.

## 5. Connection ID 와 마이그레이션

TCP 연결은 (src IP, src port, dst IP, dst port) 4-tuple 로 식별된다. 모바일이 Wi-Fi 에서 LTE 로 넘어가면 src IP 가 바뀌므로 연결이 끊기고, 애플리케이션은 재연결 + TLS 재협상을 겪는다. NAT 타임아웃으로 포트가 재배정돼도 마찬가지다.

QUIC 은 각 엔드포인트가 상대에게 여러 개의 Connection ID 를 미리 발급한다.

```text
NEW_CONNECTION_ID {
  sequence_number: 3
  retire_prior_to: 1
  connection_id: 0x8f3a2c...
  stateless_reset_token: 0x...
}
```

클라이언트 IP 가 바뀌면 새 경로에서 **아직 쓰지 않은 CID** 를 골라 패킷을 보낸다. 서버는 4-tuple 이 달라도 CID 로 기존 연결 상태를 찾아 그대로 이어간다. 암호화 키·스트림 상태·헤더 압축 테이블이 모두 유지되므로 왕복 비용이 0 이다.

다만 즉시 전속력으로 보내지는 않는다. 서버는 새 경로에 대해 **경로 검증**을 수행한다.

```text
서버 → 새 주소:  PATH_CHALLENGE (8바이트 난수)
클라이언트 → :  PATH_RESPONSE  (동일 난수 반향)
```

검증 완료 전까지 서버는 새 경로로 보내는 데이터를 제한(anti-amplification)한다. 검증되지 않은 주소로 대량 전송을 허용하면 QUIC 서버가 반사 증폭 DDoS 의 증폭기가 되기 때문이다. 핸드셰이크 단계에서도 같은 원리로 서버는 클라이언트로부터 받은 바이트의 **3배**를 초과해 보낼 수 없다.

경로가 바뀌면 RTT 추정과 혼잡 윈도우는 초기화된다. 새 경로의 특성이 이전과 다르기 때문이다. 그래서 마이그레이션 직후 짧은 슬로우 스타트 구간이 생긴다. 연결이 안 끊기는 것이지 성능이 즉시 복원되는 것은 아니다.

또 하나, CID 를 매 경로마다 바꾸는 이유는 프라이버시다. 같은 CID 를 계속 쓰면 관찰자가 IP 변경을 가로질러 사용자를 추적할 수 있다. 그래서 사양은 새 경로에서 반드시 새 CID 를 쓰도록 요구한다.

## 6. 0-RTT 와 그 대가

QUIC 은 TLS 1.3 핸드셰이크를 전송 핸드셰이크와 합쳤다. 첫 연결은 1-RTT 에 애플리케이션 데이터가 흐른다. 재방문이면 이전 세션의 PSK 로 0-RTT 데이터를 첫 패킷에 실을 수 있다.

0-RTT 데이터에는 재전송 공격 방어가 없다. 공격자가 0-RTT 패킷을 캡처해 그대로 재전송하면 서버는 같은 요청을 두 번 처리한다. 따라서 0-RTT 에는 **멱등 요청만** 실어야 한다. 실무 규칙은 명확하다. GET·HEAD 만 허용하고, 서버 측에서 0-RTT 로 도착한 비멱등 메서드는 425 Too Early 로 거절해 클라이언트가 1-RTT 로 재시도하게 만든다.

```nginx
# nginx: 0-RTT 허용하되 안전한 메서드만
ssl_early_data on;
location / {
    if ($ssl_early_data != "") {
        return 425;
    }
    proxy_pass http://backend;
}
```

위 설정은 보수적으로 모든 early data 를 거절하는 형태다. 세밀하게 가려면 애플리케이션에서 `Early-Data: 1` 헤더를 보고 멱등 여부로 분기한다.

## 7. 성능 비용 — UDP 는 공짜가 아니다

QUIC 은 유저스페이스에서 동작하므로 패킷마다 syscall 과 컨텍스트 스위치가 발생한다. 커널 TCP 스택이 수십 년간 최적화된 것과 비교하면 CPU 사용량이 초기 구현 기준 2~3배까지 났다. 이를 줄이는 표준 기법이 세 가지다.

첫째, GSO(Generic Segmentation Offload)/GRO 로 여러 데이터그램을 한 번의 `sendmsg`/`recvmmsg` 로 처리한다. `UDP_SEGMENT` 소켓 옵션을 쓰면 커널이 큰 버퍼를 MTU 단위로 쪼개 보낸다.

```c
int gso_size = 1200;
setsockopt(fd, SOL_UDP, UDP_SEGMENT, &gso_size, sizeof(gso_size));
```

둘째, 패킷 크기를 MTU 에 맞춘다. QUIC 은 경로 MTU 탐색(DPLPMTUD)을 자체 수행하는데, 초기값은 1200바이트로 보수적이다. 탐색이 성공해 1400~1450 으로 오르면 패킷 수가 15% 줄어든다. 방화벽이 ICMP 를 막아도 QUIC 은 프로브 패킷으로 탐색하므로 TCP PMTUD 블랙홀 문제를 피한다.

셋째, ACK 빈도를 줄인다. 기본은 2패킷마다 ACK 인데, 대역폭이 큰 경로에서는 ACK 처리 자체가 CPU 를 먹는다. ACK_FREQUENCY 확장으로 이를 조절한다.

배포 관점에서 UDP 차단도 현실 문제다. 기업 방화벽 중 상당수가 443/UDP 를 막는다. 그래서 서버는 항상 TCP 폴백을 유지해야 하고, 클라이언트는 `Alt-Svc` 헤더로 HTTP/3 가능성을 광고받은 뒤 병렬 시도(Happy Eyeballs 유사)로 빠른 쪽을 택한다.

```http
HTTP/1.1 200 OK
Alt-Svc: h3=":443"; ma=86400
```

`ma`(max-age)는 이 광고의 캐시 수명이다. HTTP/3 를 끄고 싶으면 `Alt-Svc: clear` 를 보내되, 이미 캐시된 클라이언트는 ma 만료까지 계속 시도한다는 점을 감안해 롤백 계획을 세워야 한다.

## 8. 운영에서 실제로 측정할 것

QUIC 도입 효과는 평균 응답 시간이 아니라 **꼬리 지연과 손실 구간**에서 나타난다. 측정 지표를 이렇게 나눈다. 손실률 0.1% 미만의 유선 환경에서는 HTTP/2 대비 차이가 거의 없거나 CPU 때문에 오히려 나빠질 수 있다. 손실률 1% 이상, RTT 100ms 이상의 모바일·해외 구간에서 p95/p99 가 20~40% 개선되는 것이 일반적으로 보고되는 범위다.

디버깅은 `qlog` 표준 포맷을 쓴다. QUIC 은 전 구간이 암호화되어 tcpdump 로 프레임을 볼 수 없으므로, 엔드포인트가 직접 이벤트 로그를 뽑아야 한다. qvis 같은 시각화 도구에 qlog 를 넣으면 스트림별 전송·손실·혼잡 윈도우 변화를 시간축으로 볼 수 있다. TLS 키를 `SSLKEYLOGFILE` 로 덤프해 Wireshark 에 물리는 방법도 있지만, 프로덕션에서는 키 유출 위험 때문에 스테이징에 한정한다.

부하 테스트 도구 선택도 달라진다. 기존 HTTP 벤치 도구 상당수가 h3 를 지원하지 않으므로 `h2load`(nghttp3 빌드), `quiche` 의 클라이언트, 또는 `k6` 의 실험적 h3 지원을 쓴다. 무엇보다 손실을 인위적으로 주입해야 의미가 있다. `tc netem` 으로 손실·지연·재정렬을 넣고 비교하는 것이 QUIC 평가의 최소 조건이다.

```bash
tc qdisc add dev eth0 root netem loss 2% delay 80ms 20ms distribution normal
```

이 조건 없이 로컬 무손실 환경에서 벤치를 돌리면 QUIC 은 거의 항상 진다. 그 결과로 도입을 판단하면 잘못된 결론에 도달한다.

## 참고

- RFC 9000 — QUIC: A UDP-Based Multiplexed and Secure Transport
- RFC 9002 — QUIC Loss Detection and Congestion Control
- RFC 9114 — HTTP/3
- RFC 9204 — QPACK: Field Compression for HTTP/3
- RFC 8899 — Datagram Packetization Layer Path MTU Discovery
- qlog / qvis 프로젝트 문서 (draft-ietf-quic-qlog-main-schema)
