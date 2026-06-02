Notion 원본: https://www.notion.so/3735a06fd6d3819797cec2aed12f58c2

# QUIC/HTTP3 Loss Recovery와 Stream Multiplexing HoL Blocking 해소

> 2026-06-02 신규 주제 · 확장 대상: Network

## 학습 목표

- QUIC 의 packet number space 분리와 ACK frame 구조를 wire format 단위로 정리한다
- TCP 의 stream level HoL blocking 이 QUIC 에서 어떻게 해소되는지 stream id 흐름으로 추적한다
- HTTP/3 의 QPACK 인코딩이 HTTP/2 의 HPACK 대비 어떤 손실 복원성을 갖는지 비교한다
- RTT 측정, congestion control(BBRv2, Cubic) 의 QUIC 적용을 실측치로 분석한다

## 1. QUIC 가 왜 새 transport 인가

TCP 는 *byte stream* 추상화다. 다중 logical stream 을 표현하려면 application layer(HTTP/2 의 stream id) 가 인위적으로 multiplex 한다. 문제는 *TCP 가 한 stream 의 segment 손실 시 후속 모든 데이터를 reorder 큐에 잡아 둔다는 점*. HTTP/2 가 같은 connection 위에 stream 100 개를 다중화해도 *stream A 의 패킷 손실이 stream B 의 도착을 막는다* — TCP level Head-of-Line blocking.

QUIC 는 UDP 위에 TLS 1.3, multi-stream, loss recovery 를 한 번에 묶은 transport 다. 각 stream 이 *transport level 에서* 독립적으로 ordering 관리되어 *한 stream 의 손실이 다른 stream 을 막지 않는다*.

## 2. Packet, Frame, Stream 의 분리

QUIC wire format 의 핵심 — *packet* 과 *frame* 의 분리. 한 QUIC packet 안에 여러 frame 이 들어가고 각 frame 은 어느 stream 의 데이터인지(또는 control 메시지인지) 자기 자신이 안다.

```
QUIC Packet (UDP datagram payload)
├── Long/Short Header (packet number, conn ID, ...)
└── Frames
    ├── STREAM frame { stream_id: 4, offset: 1024, data: ... }
    ├── STREAM frame { stream_id: 8, offset: 0,    data: ... }
    ├── ACK    frame { ranges: [...] }
    └── ...
```

한 packet 에 stream 4 와 stream 8 의 데이터가 함께 들어갈 수 있다. 손실 시 *그 packet 의 모든 frame 이 함께 손실* 되지만, *재전송은 frame 단위* 로 일어난다 — 새 packet 의 다른 packet number 로 재전송. *재전송된 데이터는 원본 packet 과 다른 PN 으로 전달*.

이 차이가 TCP 와 핵심 분기점이다. TCP 는 *segment sequence number 가 byte 위치* 라 재전송이 *동일 sequence* 로 일어나야 한다. 그래서 receiver 가 *sequence 순서* 로 buffer 를 채울 때까지 다음 데이터를 application 에 넘기지 못한다. QUIC 는 *packet number 가 단조 증가* 하고 *byte 위치 는 STREAM frame 의 offset 으로 별도 관리* — receiver 가 *어느 stream 의 어느 offset 이 채워졌는가* 만 본다.

## 3. Packet Number Space — 3개 분리

QUIC 의 또 다른 핵심 — packet number 가 *세 개의 독립 space* 로 나뉜다.

- Initial: handshake 초기, ClientHello/ServerHello
- Handshake: TLS 1.3 handshake 의 나머지
- Application Data: 1-RTT 이후 모든 데이터

각 space 는 *자기만의 PN 카운터와 ACK 관리* 를 갖는다. 효과 — *Initial 패킷의 손실이 Application Data 의 ACK 처리에 영향을 주지 않는다*. 또 *handshake 가 끝나기 전에도 0-RTT 데이터로 application 패킷 전송이 가능* 하다.

## 4. ACK Frame — TCP SACK 의 강화판

QUIC ACK frame 구조:

```
ACK Frame {
  Largest Acknowledged: PN
  ACK Delay: μs (수신자가 ACK 를 보내기까지 지연 시간)
  ACK Range Count: N
  First ACK Range: count
  ACK Ranges: [ (gap, length), (gap, length), ... ]
  ECN Counts: { ECT(0), ECT(1), CE }
}
```

기본 정보 — *어디까지 받았고, 어디가 비었는지*. TCP SACK 와 비슷하지만 결정적 차이:

- *ACK Delay 가 명시적* — receiver 의 처리 지연을 sender 가 RTT 계산에서 제외 가능
- *최대 263 개의 ACK range* (TCP SACK 는 옵션 공간 한계로 3개)
- *Always-on 강제* — TCP SACK 는 optional, QUIC ACK 는 mandatory

ACK Delay 의 의미 — receiver 가 *ACK 를 batch* 해서 보내는 동안의 지연을 sender 가 *RTT 측정에서 제외*. TCP 는 delayed ACK 가 RTT 측정을 부풀리는 문제가 있었다. QUIC 가 이를 명시적으로 해결.

## 5. Stream Multiplexing — Stream ID 의 의미

Stream ID 는 62-bit unsigned integer. 하위 2 bit 가 stream 의 종류를 나타낸다.

```
00: client-initiated bidirectional
01: server-initiated bidirectional
10: client-initiated unidirectional
11: server-initiated unidirectional
```

HTTP/3 에선 client 가 *request* 를 client-initiated bidirectional stream 으로 보내고, server 가 response 를 같은 stream 에 보낸다. PUSH 는 server-initiated unidirectional.

한 connection 안에 *수천 개의 stream* 이 동시 활성 가능. Stream 의 flow control 은 *connection-level + stream-level* 의 두 레벨 — receiver 가 *각 stream 별로* 그리고 *전체 connection 합산* 두 측면에서 수신량을 제한.

## 6. Loss Detection — Time-based + Packet-based

TCP 는 RTO (Retransmission Timeout) 와 dupACK threshold 로 loss 를 추정. QUIC 는 *RFC 9002* 의 loss detection 을 따른다.

**Packet threshold**: 새 packet 의 ACK 가 도착했을 때, *그보다 packet number 가 N 작은 (default 3) packet 이 아직 ACK 안 됐으면* 손실로 간주.

```python
# 단순화된 loss detection
def on_ack_received(largest_acked):
    threshold = largest_acked - kPacketThreshold  # default 3
    for pn in sent_packets:
        if pn < threshold and not acked(pn):
            mark_lost(pn)
```

**Time threshold**: ACK 가 늦으면 *시간 기반 손실 추정*. `(largest_acked - sent_time(pn)) > kTimeThreshold * RTT` 이면 loss.

이 두 메커니즘이 결합되어 TCP 대비 *더 빠른 loss detection*. TCP 의 RTO 가 보통 200ms+ 인 반면 QUIC 의 packet threshold loss detection 은 RTT 정도(보통 10~50ms)에 동작.

## 7. HoL Blocking 의 실제 해소 — 시나리오

다음 시나리오로 차이를 본다 — HTTP/2 (TCP) vs HTTP/3 (QUIC) 환경에서 두 동시 request:

- Request A: 큰 이미지 200KB
- Request B: 작은 JSON 1KB

Network: 50ms RTT, 0.5% packet loss.

**HTTP/2 + TCP**:

```
Time 0:   A 의 segment 1~50 + B 의 segment 1 (TCP byte stream 순서)
Time 50:  A의 segment 5 손실 발견
Time 50:  Receiver TCP buffer: [seg1..seg4, seg6..] — 순서 holding
          application 은 seg5 를 받기 전까지 그 뒤 데이터 못 읽음
          → B 의 segment 1 도 application 에 안 넘어감
Time 250: seg5 재전송 도착 (1 RTO 후)
Time 250: A + B 둘 다 application 에 전달
```

B 가 1KB 짜리인데 *A 의 손실 때문에 250ms 지연*. TCP HoL.

**HTTP/3 + QUIC**:

```
Time 0:   QUIC packets 안에 stream A frame + stream B frame 혼재
Time 50:  Packet 5 (stream A 데이터 일부 포함) 손실 발견
Time 50:  Receiver: stream B 의 모든 offset 은 도착 완료
          → application 에 stream B 데이터 즉시 전달
Time 50:  Stream A 만 retransmit 대기
Time 60:  Stream A 재전송 도착 → application 에 전달
```

B 는 손실 영향 없이 50ms 에 완료. *Stream level isolation* 의 효과.

물론 *완전 해소는 아니다*. 같은 packet 안에 stream A frame 과 stream B frame 이 함께 들어 있었다면 *그 packet 손실* 은 둘 다 영향. 그러나 *재전송* 시 receiver 는 *stream B 의 데이터를 stream A 와 무관하게 application 에 넘긴다*.

## 8. QPACK — HPACK 의 손실 안전 강화

HTTP/2 의 HPACK 은 *순서 의존적 dynamic table* 을 갖는다. Header 압축 시 *바로 직전 request 의 header* 를 참조해 압축률을 높이지만, *직전 request 가 손실되면 그 dependency 가 끊긴다*. 결과적으로 HTTP/2 도 *header 레벨에서 HoL* 가 발생.

HTTP/3 의 QPACK 은 이 의존성을 *encoder stream + decoder stream* 으로 분리해 해결.

```
[client] encoder stream → dynamic table updates ↑
         decoder stream ← acknowledgements ↓
         request stream → headers (압축 가능)
```

핵심 — *encoder 가 dynamic table 에 새 항목을 추가할 때* 그 추가는 *encoder stream* 에 명시적 instruction 으로 보내진다. *Request 의 header* 는 *table index* 를 참조하는데, *그 index 가 아직 server 측에서 보이지 않는다면* request 처리를 *지연* 시킬 수 있다.

이 지연은 *trade-off* 다. encoder 가 *얼마나 공격적으로 dynamic table 을 채울지* 가 압축률과 지연의 균형을 결정. `qpack-blocked-streams` setting 으로 *동시 block 허용 stream 수* 를 제한.

## 9. 실측 — Page Load Time 과 throughput

Chromium 의 QUIC vs HTTP/2 비교 실측치 (Google 발표, 2023~24):

| 조건 | HTTP/2 | HTTP/3 |
|---|---|---|
| 1% packet loss, 100ms RTT, 300KB 페이지 | PLT 1.4s | PLT 1.05s (-25%) |
| 5% packet loss, 100ms RTT | PLT 3.2s | PLT 1.8s (-44%) |
| 무손실 LAN, 1ms RTT | PLT 동일 | 동일 |
| Mobile 4G (RTT 60ms, 1.5% loss) | PLT 2.1s | PLT 1.4s (-33%) |

이득의 대부분은 *손실이 있는 환경에서의 HoL 회피*. 무손실 환경에선 QUIC 의 cryptography overhead 가 미세하게 더 느림.

CPU 측면 — QUIC 가 *user-space 처리* 라 TCP 대비 CPU 사용량이 *2~3x*. Linux 6.4 의 io_uring + UDP GSO 로 일부 회복. CDN/edge 서버는 *kernel-bypass UDP* (DPDK, AF_XDP) 로 처리.

## 10. Congestion Control — Cubic, BBRv2, Prague

QUIC 는 *congestion control 알고리즘에 agnostic*. 구현체별 선택:

- Chromium: BBRv2 default, Cubic fallback
- Cloudflare quiche: Cubic default, BBR 선택
- ngtcp2 / Apple Network.framework: NewReno / Cubic

BBRv2 는 *bandwidth-delay product* 기반 — *RTT 증가가 congestion 의 sign* 이라는 가정. Bufferbloat 환경에서 Cubic 대비 *2~3x throughput*. 단 *fair sharing 이 어렵다는* 비판이 있어 Google 이 BBRv3 로 개선 중.

QUIC 에서 BBR 이 더 효과적인 이유 — *RTT 측정의 정확도*. ACK Delay 가 명시적이라 *receiver 처리 지연을 제거한 RTT* 를 알 수 있고 BBR 의 RTprop 추정이 더 안정적.

## 11. 운영 함정

- *UDP blocking* — 일부 enterprise/ISP 가 UDP 443 을 차단. Fallback to TCP+HTTP/2 가 client 책임. Chromium 은 알트 svc 헤더로 protocol 협상.
- *MTU/IP fragmentation* — QUIC 의 initial packet 은 *최소 1200 byte* 강제. 일부 path MTU 가 작으면 packet 폐기. PMTUD 동작 보장 필요.
- *NAT rebinding* — UDP 는 *connection state 가 없어* NAT 가 자주 rebind. QUIC 의 *connection ID* 가 IP/port 변경에도 connection 을 유지하게 만들지만 *NAT 가 inbound 트래픽을 끊으면* 그 동작이 무효.
- *서버 측 socket fan-out* — UDP socket 은 *kernel 이 thread/CPU 별로 분배하지 않음*. NIC 의 RSS 와 SO_REUSEPORT 조합으로 multi-thread 처리.
- *0-RTT replay 공격 위험* — 0-RTT 의 데이터는 *replay 가능*. POST 같은 *non-idempotent* request 를 0-RTT 로 받으면 안 됨. Application 이 *anti-replay token* 또는 *idempotency key* 필요.

## 참고

- RFC 9000 — QUIC: A UDP-Based Multiplexed and Secure Transport
- RFC 9001 — Using TLS to Secure QUIC
- RFC 9002 — QUIC Loss Detection and Congestion Control
- RFC 9204 — QPACK: Field Compression for HTTP/3
- "HTTP/3 explained" — Daniel Stenberg