Notion 원본: https://www.notion.so/34d5a06fd6d381e19de8ffdbd53a7131

# TCP BBR 혼잡제어와 BDP 튜닝

> 2026-04-25 신규 주제 · 확장 대상: 통신 네트워크 (TCP 학습됨), CS (혼잡제어 학습됨)

## 학습 목표

- Reno / CUBIC / BBR의 혼잡제어 모델 차이를 cwnd 변화 곡선과 신호 모델로 설명한다
- BDP(Bandwidth-Delay Product)를 측정해 socket buffer / TCP window를 튜닝한다
- BBRv1과 BBRv2의 차이(loss / ECN 반응)를 운영 시나리오에 적용한다
- Linux `sysctl`, `tc qdisc`, `ss -ti` 출력을 해석해 회선 병목을 진단한다

---

## 1. 혼잡제어의 두 가지 신호 모델

TCP 혼잡제어는 발신자가 "지금 네트워크가 얼마나 받을 수 있는가"를 추정하는 알고리즘이다. 추정 신호로 무엇을 쓰느냐가 대분류 기준이다.

| 알고리즘 | 신호 | cwnd 동작 |
|---|---|---|
| Reno | packet loss | AIMD: 1MSS씩 증가, loss 시 절반 |
| CUBIC | packet loss | 3차 함수, loss 후 빠르게 회복 |
| BBR | RTT 최소값 + bandwidth 최대값 추정 | inflight = BDP × gain |

Reno와 CUBIC은 loss-based다. 즉 패킷이 떨어져야 비로소 "병목에 도달했다"고 판단한다. 이건 두 가지 부작용을 만든다.

첫째, **bufferbloat**. 모든 라우터가 큰 큐를 가진 현대 네트워크에서 패킷 loss가 일어나려면 큐가 가득 차야 한다. 그 때까지 RTT는 계속 증가한다. CUBIC은 RTT가 평소 30 ms인 회선을 평균 200 ms로 끌어올리는 일이 흔하다. 둘째, **shallow buffer 라우터에서 과민 반응**. 데이터센터 ToR 스위치처럼 버퍼가 얕은 환경에서는 정상 트래픽도 loss를 만들어내, CUBIC은 절반으로 떨어지고 회복이 느리다.

BBR(Bottleneck Bandwidth and RTT)은 다른 모델이다. 패킷이 떨어지길 기다리지 않고, 매 RTT마다 최근 max bandwidth(BtlBw)와 min RTT(RTprop)를 측정해 inflight = BtlBw × RTprop으로 직접 계산한다.

## 2. BDP(Bandwidth-Delay Product)

회선의 정상 inflight 양은 BDP다.

```
BDP = bandwidth (bps) × RTT (s) ÷ 8 (bytes)
```

서울-도쿄 (RTT 30 ms, 1 Gbps):

```
BDP = 1,000,000,000 × 0.030 / 8 = 3,750,000 bytes ≈ 3.75 MB
```

이 값이 socket buffer 크기와 TCP window 크기의 하한이다. window가 BDP보다 작으면 회선을 못 채운다. 운영 측면에서 default는 4 MB 정도가 안전하다.

```bash
# Linux 권장 값
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216
sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"
sysctl -w net.ipv4.tcp_window_scaling=1
```

`tcp_rmem`은 [min, default, max] 형식이다. autotuning이 enabled면 default가 시작값이고 max까지 늘어난다.

## 3. BBRv1의 동작

BBRv1은 4단계 cycle을 돈다.

| Phase | gain | 의미 |
|---|---|---|
| STARTUP | 2.89× | slow-start 대용, BtlBw 추정 |
| DRAIN | 0.35× | inflight 줄여 큐 비우기 |
| PROBE_BW | 1.25× / 0.75× / 1.0× × 6 | 정상 상태 cycle |
| PROBE_RTT | 0.75× | 200ms 동안 4 packets만 보내 RTprop 갱신 |

PROBE_RTT가 매 10초에 한 번 들어와 inflight를 의도적으로 떨어뜨린다. 짧은 throughput 디프지만 RTprop 추정을 정확히 유지하려면 필요하다. 운영 환경에서 BBR을 켰을 때 throughput 그래프가 10초 주기로 살짝 dip하는 모습이 보이면 PROBE_RTT 흔적이다.

## 4. BBRv1 vs CUBIC 측정

같은 회선(1 Gbps, 50 ms RTT, packet loss 0.5%)에서 iperf3로 측정.

| 알고리즘 | 평균 throughput | RTT | 변동 |
|---|---|---|---|
| CUBIC | 280 Mbps | 165 ms (bufferbloat) | sawtooth |
| BBRv1 | 920 Mbps | 52 ms | smooth |

loss 0.5%는 CUBIC에 치명적이다. cwnd가 계속 절반으로 잘리고 회복이 느려서 회선의 30%만 쓴다. BBR은 loss를 신호로 쓰지 않으므로 안정적인 throughput을 유지한다.

이게 Google이 YouTube CDN에서 BBR을 도입한 이유다. mobile / Wi-Fi 같은 고손실 환경에서 throughput이 2~3배 향상된다.

## 5. BBRv1의 문제와 BBRv2

BBRv1에는 잘 알려진 문제가 둘 있다.

**fairness 부족**. CUBIC와 같은 큐를 공유하면 BBR이 더 공격적으로 inflight를 유지해 CUBIC의 점유율이 떨어진다. ISP 한 회선에 BBR과 CUBIC가 섞이면 CUBIC 사용자가 손해다.

**ECN 비활용**. ECN(Explicit Congestion Notification)을 받아도 BBRv1은 무시한다. 라우터가 적극적으로 협력하려는 신호를 흘려보낸다.

BBRv2는 두 문제를 수정했다. loss와 ECN을 신호로 받되 cwnd를 절반으로 자르지 않고 PROBE_BW gain을 줄이는 방식으로 반응한다. CUBIC와 함께 있을 때도 RFC-fair에 가깝게 동작한다. Linux 6.4부터 mainline에 들어왔다.

## 6. 적용: Linux 측 설정

```bash
# BBR 활성화 (Linux 4.9+ for v1, 6.4+ for v2)
sysctl -w net.core.default_qdisc=fq
sysctl -w net.ipv4.tcp_congestion_control=bbr

# 영구화
echo "net.core.default_qdisc=fq" >> /etc/sysctl.conf
echo "net.ipv4.tcp_congestion_control=bbr" >> /etc/sysctl.conf
```

`fq`(Fair Queue) qdisc는 BBR이 정확한 시간 간격으로 packet을 송신하기 위해 필수다. 기본 `pfifo_fast`로는 BBR의 pacing이 깨진다.

연결별로 다르게 적용하려면 `setsockopt(TCP_CONGESTION, "bbr")`을 호출한다. WebRTC 서버, 게임 서버, 영상 스트리밍 서버에 부분 적용하는 패턴.

## 7. 진단 명령

```bash
# 현재 알고리즘
sysctl net.ipv4.tcp_congestion_control

# 활성 소켓의 혼잡 정보
ss -ti | grep -A1 ESTAB
# 출력: bbr wscale:7,7 rto:208 rtt:52.5/3.2 mss:1448 ... cwnd:32 ssthresh:24 bytes_acked:...
```

`rtt:52.5/3.2`은 평균/표준편차. cwnd가 BDP에 가까운지 확인. ssthresh는 slow-start가 끝난 시점.

```bash
# qdisc 확인
tc -s qdisc show dev eth0
# fq 통계: throttled, gc_flow 등이 보임
```

`gc_flow > 0`이면 fq가 너무 많은 flow를 추적하다 GC하는 중. flow_limit을 키워야 한다.

## 8. 운영 권고

| 시나리오 | 권장 |
|---|---|
| WAN 회선, packet loss 0.1% 이상 | BBRv2 |
| 데이터센터 내부 (loss 0%) | CUBIC 충분, BBR 이점 적음 |
| 모바일 클라이언트 대상 CDN | BBR 강력 권장 |
| K8s 노드 내부 트래픽 | CUBIC, ECN 활용 |
| HTTP/3 (QUIC) | QUIC 자체 cc, BBR 변형이 들어 있음 |

또 하나의 권고는 **single algorithm 강제 금지**. ICMP-blocked 환경에서 BBR이 RTprop을 잘못 추정하는 사례가 있다. application-layer health check와 함께 cc 알고리즘 변경을 모니터링해야 한다.

## 9. application-layer 영향

BBR은 socket buffer를 거의 full로 유지한다. 그래서 application은 큰 send buffer가 OS에 쌓인 상태로 동작한다. 만약 application이 send를 멈추고 socket을 close하면 in-flight 데이터가 버려질 수 있다(LINGER 설정 확인). 또 graceful shutdown 시 마지막 데이터가 RTT 단위로 늦게 도착할 수 있어, gRPC stream half-close 패턴에서는 명시적인 application-level ack를 거는 게 안전하다.

## 참고

- Neal Cardwell et al., "BBR: Congestion-Based Congestion Control", Communications of the ACM 60(2)
- RFC 9438 (CUBIC for Fast and Long-Distance Networks)
- Linux Kernel Documentation, "Documentation/networking/tcp.rst"
- Google Cloud Blog, "TCP BBR congestion control comes to GCP"
- Bufferbloat.net, "What is bufferbloat?"
