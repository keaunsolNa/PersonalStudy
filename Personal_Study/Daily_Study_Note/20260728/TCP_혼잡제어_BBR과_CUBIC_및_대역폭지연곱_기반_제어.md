Notion 원본: https://app.notion.com/p/3ab5a06fd6d381738a9cda52736b601c

# TCP 혼잡제어 BBR과 CUBIC 및 대역폭지연곱 기반 제어

> 2026-07-28 신규 주제 · 확장 대상: 통신_네트워크(TCP 혼잡제어)

## 학습 목표

- 손실 기반(CUBIC)과 모델 기반(BBR) 혼잡제어가 병목을 추정하는 방식의 근본 차이를 구분한다.
- 대역폭지연곱(BDP)과 버퍼블로트가 처리량·지연에 미치는 영향을 정량적으로 정리한다.
- BBR 이 BtlBw·RTprop 추정과 pacing 으로 동작하는 상태 머신을 파악한다.
- 워크로드·네트워크 특성에 따라 CUBIC 과 BBR 중 무엇을 택할지 실측 기준으로 판단한다.

## 1. 혼잡제어가 푸는 문제

핵심 지표는 병목 대역폭(BtlBw)과 왕복 전파 지연(RTprop)이며, 이 둘의 곱이 대역폭지연곱(BDP)으로 경로를 가득 채우는 데 필요한 in-flight 바이트 수다.

```
BDP = BtlBw x RTprop
예: 1Gbps, RTT 40ms -> 125,000,000 B/s x 0.04s = 5MB
```

이상적 동작점은 in-flight = BDP 다. 이 지점에서 병목 링크는 100% 활용되고 큐는 비어 지연이 최소다.

## 2. CUBIC — 손실을 신호로 삼는다

리눅스 기본값 CUBIC 은 패킷 손실을 혼잡 신호로 본다. 손실이 없으면 3차 함수 곡선으로 cwnd 를 늘리고 손실이 나면 곱셈적으로 줄인다(0.7배). 문제는 현대 라우터의 큰 버퍼 때문에 병목을 넘겨도 패킷이 버려지지 않고 큐에 쌓인다는 것이다. CUBIC 은 손실이 없으니 버퍼가 가득 찰 때까지 밀어 넣어 버퍼블로트를 만든다. 대가는 지연이며, 무선망의 random loss 경로에서는 병목 도달 전에 윈도를 줄여 대역폭을 낭비한다.

## 3. BBR — 병목을 직접 모델링한다

구글의 BBR 은 손실을 무시하고 BtlBw 와 RTprop 을 직접 측정해 BDP 를 추정한 뒤 전송률을 조절한다. BtlBw 는 최근 전달률의 최댓값, RTprop 은 최근 RTT 의 최솟값으로 추정한다. 전달률은 정체됐는데 RTT 만 오르면 큐 축적 신호로 읽고 억제한다. 목표는 in-flight ≈ BDP 다. pacing 으로 전송 타이밍을 균등 분산해 큐 축적을 줄인다.

## 4. BBR 상태 머신

```
STARTUP  -> 지수적 전송률 증가
DRAIN    -> STARTUP 초과 큐 배출
PROBE_BW -> pacing_gain 순환으로 대역폭 탐침
PROBE_RTT-> ~10초마다 in-flight 최소로 낮춰 RTprop 재측정
```

PROBE_BW 는 1.25배로 여유 대역폭을 탐색하고 곧 0.75배로 큐를 배출한다.

## 5. 설정과 측정

```bash
sudo sysctl -w net.core.default_qdisc=fq
sudo sysctl -w net.ipv4.tcp_congestion_control=bbr
ss -tin dst SERVER   # cwnd, rtt, pacing rate 관찰
```

BBR 의 이점은 처리량뿐 아니라 부하 중 지연을 함께 봐야 드러난다.

## 6. 언제 무엇을 쓸까

| 경로 특성 | CUBIC | BBR |
|---|---|---|
| 유선·저손실·짧은 RTT | 충분히 좋음 | 큰 차이 없음 |
| 큰 버퍼 라우터 | 큐 지연 심함 | 큐 비워 지연 개선 |
| 무선·random loss | 손실에 과민 | 손실 무시, 처리량 유지 |
| 장거리·고BDP | 윈도 확장 느림 | BDP 직접 추정, 유리 |
| 다수 흐름 공정성 | 성숙·안정적 | 버전따라 공격적 |

초기 BBR(v1)은 CUBIC 과 병목 공유 시 공정성 문제가 지적됐고 BBRv2/v3 가 손실·ECN 신호를 반영하도록 개선됐다.

## 7. 정리 — 신호가 다르면 동작점이 다르다

CUBIC 은 손실을 기다려 버퍼블로트를 부르고 BBR 은 대역폭·RTT 를 모델링해 큐를 비운 채 링크를 채운다. 처리량은 비슷해도 부하 중 지연에서 갈린다.

## 8. 리눅스 커널 파라미터 — 혼잡제어 이전의 상한

고BDP 경로에서 in-flight = BDP 만큼의 수신 버퍼가 없으면 윈도가 그 크기에 묶여 알고리즘과 무관하게 처리량이 상한에 걸린다.

```bash
sudo sysctl -w net.ipv4.tcp_rmem="4096 131072 67108864"
sudo sysctl -w net.core.rmem_max=67108864
ip route change default via GW dev eth0 initcwnd 30 initrwnd 30
```

혼잡제어를 BBR 로 바꿔도 소켓 버퍼가 BDP 보다 작으면 이득이 없다. 커널 파라미터가 알고리즘의 상한을 정한다.

## 9. HTTP/3·QUIC — 혼잡제어가 유저스페이스로

QUIC 은 UDP 위에 혼잡제어를 유저스페이스로 구현해 커널 재컴파일 없이 알고리즘을 배포한다. 패킷마다 단조 증가 번호로 재전송 모호성이 사라져 RTT 추정이 정확하고, 스트림별 독립 흐름 제어로 head-of-line blocking 을 전송 계층에서 해소한다. 다만 혼잡제어 근본 원리는 QUIC 위에서도 동일하다.

## 10. AQM 과 ECN — 큐를 라우터가 관리한다

fq_codel·cake 같은 AQM qdisc 는 큐 지연을 감시해 오래 머문 패킷을 선제 드롭·마킹한다. ECN 은 패킷을 드롭하는 대신 CE 비트로 혼잡을 표시해 손실 없는 혼잡 신호를 준다.

```bash
sudo sysctl -w net.ipv4.tcp_ecn=1
sudo tc qdisc replace dev eth0 root fq_codel
```

혼잡제어는 송신 알고리즘·소켓 버퍼·라우터 큐 관리의 삼각 협력이다.

## 참고

- Cardwell et al., BBR: Congestion-Based Congestion Control, ACM Queue 2016 — https://queue.acm.org/detail.cfm?id=3022184
- RFC 8312: CUBIC — https://www.rfc-editor.org/rfc/rfc8312
- Ha, Rhee, Xu, CUBIC (2008)
- Linux kernel networking docs — https://www.kernel.org/doc/html/latest/networking/
