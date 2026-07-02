Notion 원본: https://www.notion.so/3915a06fd6d38115abacf20f8e47d8d4

# TCP 혼잡 제어 CUBIC과 BBR 및 재전송 타임아웃 RTO

> 2026-07-02 신규 주제 · 확장 대상: 통신/네트워크(TCP 3-way handshake·흐름제어 학습됨)

## 학습 목표

- 흐름 제어(flow control)와 혼잡 제어(congestion control)의 목적 차이를 명확히 한다
- 손실 기반 CUBIC과 대역폭·RTT 기반 BBR의 모델 차이를 대조한다
- RTT 측정과 RTO 계산(Jacobson/Karels)의 수식을 재현한다
- bufferbloat 환경에서 알고리즘 선택이 지연에 미치는 영향을 판단한다

## 1. 흐름 제어 vs 혼잡 제어

TCP에는 수신 창(rwnd)과 혼잡 창(cwnd) 두 개의 독립적 창이 있다. rwnd는 수신자 버퍼 보호(흐름 제어), cwnd는 네트워크 경로 보호(혼잡 제어)다. 실제 전송량은 min(rwnd, cwnd)이다. 혼잡 제어의 어려움은 네트워크 내부를 직접 볼 수 없어 ACK 패턴과 손실로만 혼잡도를 추정해야 한다는 점이다.

## 2. 고전 모델 - AIMD와 슬로우 스타트

```
슬로우 스타트: cwnd를 ACK마다 지수적 증가
혼잡 회피(AIMD): 손실 없으면 RTT마다 +1 MSS, 손실 시 *0.5
```

AIMD의 톱니 패턴은 고대역폭·고지연(LFN) 경로에서 치명적으로 느리다. 10Gbps/100ms에서 손실 후 절반이 된 cwnd 회복에 수천 RTT가 걸린다.

## 3. CUBIC - 손실 기반의 개선

Linux 기본인 CUBIC은 선형 증가를 3차 함수 곱선으로 대체한다.

```
W(t) = C * (t - K)^3 + W_max
K = (W_max * beta / C)^(1/3)
```

손실 직후에는 W_max 근처까지 빠르게 회복하고 W_max 부근에서는 완만하게 접근해 조심스럽게 탐색한다. RTT에 무관하게 실제 경과 시간 기준이라 공정성도 낛다. 근본 한계는 여전히 손실이 나야 후퇴한다는 것이다.

## 4. Bufferbloat - 큰를 채우는 대가

라우터 버퍼가 과도하게 크면 손실 기반 CC는 그 버퍼를 끝까지 채운 뒤에야 손실을 만난다. 버퍼가 차 있는 동안 모든 패킷이 큰 대기 시간만큼 지연되므로 처리량은 유지되지만 지연이 급증한다. 화상 통화 중 대용량 업로드 시 통화가 끊기는 것이 전형이다.

## 5. BBR - 대역폭과 RTT를 직접 모델링

```
BtlBw = 최근 최대 전달률
RTprop = 최근 최소 RTT
목표 전송량(BDP) = BtlBw * RTprop
```

최적 동작점은 큰가 비어 있으면서 병목을 꽉 채우는 지점이다. BBR은 큰를 채우지 않으므로 지연을 낮게 유지한다. ProbeBW로 대역폭 증가를 탐색하고 ProbeRTT로 최소 RTT를 재측정한다.

| 관점 | CUBIC | BBR |
|---|---|---|
| 혼잡 신호 | 패킷 손실 | 대역폭·RTT 추정 |
| 큰 점유 | 버퍼를 채움 | 큰를 비우려 함 |
| bufferbloat 지연 | 높음 | 낮음 |
| 랜덤 손실(무선) | 혼잡으로 오판 | 덜 민감 |

BBR은 무선·위성처럼 손실이 혼잡과 무관한 링크에서 특히 우월하다.

## 6. RTO - 재전송 타임아웃 계산

```
SRTT   = (1 - 1/8) * SRTT + 1/8 * RTT_sample
RTTVAR = (1 - 1/4) * RTTVAR + 1/4 * |SRTT - RTT_sample|
RTO    = SRTT + max(G, 4 * RTTVAR)
```

변동(RTTVAR)을 4배로 반영해 RTT가 흔들리는 경로에서 성급한 재전송을 막는다. Karn의 알고리즘에 따라 재전송된 세그먼트의 ACK는 RTT 샘플로 쓰지 않는다. 실무 손실 복구는 3 중복 ACK fast retransmit과 SACK가 RTO보다 빨리 처리하고, RTO는 최후 방어선이다.

## 7. 알고리즘 선택 실무

```bash
sysctl net.ipv4.tcp_available_congestion_control
sysctl -w net.ipv4.tcp_congestion_control=bbr
```

데이터센터 내부처럼 손실이 드물면 CUBIC으로 충분하고, 대륙간 CDN·모바일·bufferbloat 경로는 BBR이 유리한 경우가 많다. 다만 공정성은 논란이 있으므로 실측 검증이 필요하다.

## 8. ECN과 손실 없는 혼잡 신호

ECN(Explicit Congestion Notification)은 라우터가 큰가 차오르면 패킷을 버리는 대신 IP 헤더의 ECN 비트를 세워 혼잡을 미리 알린다. 데이터센터의 DCTCP는 ECN 표시 비율을 세밀히 측정해 창을 비례적으로 줄여 큰를 매우 낮게 유지한다. 다만 ECN·DCTCP는 경로상 라우터와 종단이 모두 지원해야 하므로 통제된 데이터센터에서 주로 쓰이고 공용 인터넷에서는 배치가 제한적이다.

## 9. 진단 - 무엇을 측정할 것인가

```bash
ss -ti dst 203.0.113.10
# cubic rtt:42.3/5.1 cwnd:64 ssthresh:48 retrans:0/12 ...
```

retrans가 높으면 손실 다발 경로, rtt 변동이 크면 bufferbloat를 의심한다. cwnd가 rwnd에 못 미치는데 느리면 혼잡 제어 병목이고, cwnd는 충분한데 느리면 애플리케이션이나 rwnd를 봐야 한다. 지표로 원인을 먼저 국소화하는 것이 순서다.

## 10. 결론

혼잡 제어의 역사는 무엇을 혼잡의 증거로 삼을 것인가의 진화다. 손실 기반 CUBIC은 견고하지만 큰를 채워 지연을 만들고, 대역폭·RTT 기반 BBR은 지연을 낮추지만 공정성 과제를 안는다. RTO는 직교하는 손실 판정 타이머다. 성능 문제는 처리량뿐 아니라 지연·재전송률·경로 특성을 함께 봐야 한다.

## 참고

- RFC 5681 — TCP Congestion Control
- RFC 6298 — Computing TCP's Retransmission Timer
- Ha, Rhee, Xu, "CUBIC" (2008)
- Cardwell et al., "BBR: Congestion-Based Congestion Control" (2016)
