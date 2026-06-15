Notion 원본: https://www.notion.so/3805a06fd6d3816e9ec5e9744a9738cc

# TCP 혼잡 제어 — BBR과 CUBIC 내부 동작 비교

> 2026-06-15 신규 주제 · 확장 대상: 통신 네트워크 / CS

## 학습 목표

- 손실 기반(CUBIC)과 모델 기반(BBR) 혼잡 제어의 신호 차이를 구분한다
- CUBIC의 3차 함수 윈도우 증가식과 TCP-friendly 영역을 계산한다
- BBR의 BtlBw·RTprop 추정과 ProbeBW·ProbeRTT 상태 머신을 추적한다
- bufferbloat·고대역지연곱(BDP) 환경에서 두 알고리즘의 거동 차이를 설명한다

## 1. 혼잡 제어가 풀려는 문제

TCP 혼잡 제어는 "경로에 얼마나 많은 데이터를 동시에 띄울 것인가(in-flight)"를 정한다. 핵심 지표는 BDP(병목 대역폭 × 왕복 지연)로, 파이프를 가득 채우는 데 필요한 in-flight량이다. CUBIC은 패킷 손실을 신호로, BBR은 대역폭과 지연을 직접 추정한다.

## 2. CUBIC: 손실 기반의 표준

손실 직전 윈도우를 `W_max`로 기억하고, 손실 시 cwnd를 `β·W_max`(β=0.7, 30% 감소)로 줄인 뒤 3차 함수로 키운다.

```
W(t) = C·(t − K)³ + W_max
K = ∛(W_max·(1 − β) / C)
```

```python
def cubic_window(t, w_max, c=0.4, beta=0.7):
    k = ((w_max * (1 - beta)) / c) ** (1 / 3)
    return c * (t - k) ** 3 + w_max
```

손실 직후에는 곱선이 가파르게 회복하고, `W_max` 근처에서는 평평해져 조심스럽게 탐색하며, 초과 시 다시 가파로워 새 대역폭을 공격적으로 탐색한다. 윈도우 증가가 RTT에 독립적이라 공정성이 좋다.

## 3. CUBIC의 근본 한계: bufferbloat

현대 라우터는 거대한 버퍼를 가져 큐가 차도 패킷을 떨어뜨리지 않고 버퍼에 쌓는다. CUBIC은 손실이 나야 윈도우를 줄이므로 버퍼를 가득 채워 큐 대기 시간이 폭증하는 bufferbloat가 생긴다. 대역폭은 거의 다 쓰지만 지연이 BDP 대비 몇 배로 부풀고, 무선의 임의 손실을 혼잡으로 오인해 처리량이 떨어진다.

## 4. BBR: 모델 기반의 대안

BBR은 손실을 보지 않고 `BtlBw`(최근 전달률 최댓값)과 `RTprop`(최근 RTT 최솟값)을 추정한다. 둘의 곱이 BDP이며 BBR은 딱 그만큼 in-flight를 유지해 버퍼를 채우지 않고 파이프만 채운다(Kleinrock 최적점).

```python
class BBR:
    def on_ack(self, delivered, interval, rtt):
        delivery_rate = delivered / interval
        self.btlbw = max_filter(delivery_rate, window="10rtt")
        self.rtprop = min_filter(rtt, window="10s")
        self.bdp = self.btlbw * self.rtprop
        self.cwnd = self.bdp * self.cwnd_gain
        self.pacing_rate = self.btlbw * self.pacing_gain
```

BBR이 cwnd뿐 아니라 pacing_rate를 함께 제어해 버스트 없이 균등히 내보내는 점이 중요하다.

## 5. BBR의 상태 머신

BtlBw와 RTprop은 동시 측정이 어려워 시간 분할로 해결한다. `Startup`(대역폭 지수적 탐색) → `Drain`(과잉 큐 비움) → `ProbeBW`(8페이즈 순환, pacing_gain 1.25로 탐색 · 0.75로 비움) ⇄ `ProbeRTT`(약 10초마다 cwnd를 4패킷으로 줄여 파이프를 비워 최소 RTT 재측정). ProbeRTT가 공정성과 정확성을 떠받친다.

## 6. 두 알고리즘의 거동 비교

| 항목 | CUBIC | BBR |
|---|---|---|
| 혼잡 신호 | 패킷 손실 | 대역폭/지연 추정 모델 |
| 버퍼 사용 | 가득 채움 (bufferbloat) | 거의 비움 (BDP 유지) |
| 큐 지연 | 높음 | 낮음 |
| 임의 손실 대응 | 처리량 급락 | 거의 영향 없음 |
| pacing | 선택적 | 필수 |

고BDP·장거리 링크에서 BBR의 이점이 두드러지며, 구글은 긴 경로에서 BBR이 CUBIC 대비 처리량을 수 배 높이면서 RTT를 낮춘다고 보고했다.

## 7. BBR의 약점과 BBRv2/v3

BBRv1은 CUBIC과 병목을 공유할 때 손실을 무시해 과도하게 공격적이고, 얇은 버퍼에서 손실을 유발했다. BBRv2는 손실률과 ECN을 모델에 통합해 공정성을 개선했고, BBRv3는 in-flight 상한과 수렴 로직을 다듬었다.

## 8. 운영 선택과 검증

```bash
sysctl -w net.ipv4.tcp_congestion_control=bbr
sysctl -w net.core.default_qdisc=fq
```

지연 민감·고BDP·무선이면 BBR, 손실 기반 흐름이 많이 공존하는 환경은 CUBIC 또는 BBRv2 이상이 안전하다. `ss -ti`·`iperf3`로 처리량과 RTT를 함께 측정해 검증한 뒤 적용한다.

## 9. 데이터센터 환경과 DCTCP·ECN

데이터센터 내부는 RTT가 마이크로초 단위로 짧고 incast가 쟦아 DCTCP가 쓰인다. 스위치가 큐 길이가 임계를 넘으면 패킷을 떨어뜨리는 대신 ECN 비트를 마킹하고, DCTCP 송신자는 마킹 비율(α)을 추정해 `cwnd ← cwnd × (1 − α/2)`로 혼잡 정도에 비례해 부드럽게 줄인다. 큐를 매우 짧게 유지하면서도 손실 없이 높은 활용도를 얻는다. 단 DCTCP는 인터넷 경로에 쓰면 안 된다 — 알고리즘은 경로의 물리적 특성에 맞춰야 하며 만능 알고리즘은 없다.

## 참고

- Cardwell et al., "BBR: Congestion-Based Congestion Control", ACM Queue 2017: https://queue.acm.org/detail.cfm?id=3022184
- Ha, Rhee, Xu, "CUBIC", ACM SIGOPS 2008: https://www.cs.princeton.edu/courses/archive/fall16/cos561/papers/Cubic08.pdf
- RFC 9438, "CUBIC for Fast and Long-Distance Networks": https://datatracker.ietf.org/doc/rfc9438/
- google/bbr Wiki: https://github.com/google/bbr/blob/master/Documentation/bbr-faq.md
