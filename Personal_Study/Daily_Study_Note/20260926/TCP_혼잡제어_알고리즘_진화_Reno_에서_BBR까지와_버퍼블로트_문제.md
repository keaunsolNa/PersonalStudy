Notion 원본: https://app.notion.com/p/3e75a06fd6d3818083b4cc5b2a6666cb

# TCP 혼잡제어 알고리즘 진화(Reno→BBR)와 버퍼블로트 문제

> 2026-09-26 신규 주제 · 확장 대상: 통신 네트워크

## 학습 목표

- TCP Reno의 AIMD(Additive Increase Multiplicative Decrease) 기반 혼잡 회피가 손실을 신호로 해석하는 원리와 한계를 설명한다
- CUBIC이 고대역폭·고지연(BDP) 네트워크에서 Reno보다 빠르게 수렴하도록 설계된 지점을 구분한다
- 버퍼블로트가 발생하는 구조적 원인과 손실 기반 혼잡제어가 이를 악화시키는 메커니즘을 재현한다
- BBR이 손실이 아닌 대역폭·RTT 측정 모델로 혼잡을 추정하는 방식을 기존 알고리즘과 대비한다

## 1. 혼잡제어가 필요한 근본 이유

TCP는 종단 간 프로토콜로, 송신 측은 네트워크 중간 경로(라우터, 스위치)의 실제 혼잡 상태를 직접 알 수 없다. 만약 모든 송신자가 자신의 처리량을 무한정 늘리면 중간 라우터의 큐가 넘쳐 패킷 손실이 발생하고, 손실된 패킷의 재전송이 다시 트래픽을 늘려 혼잡을 더 악화시키는 혼잡 붕괴(congestion collapse)로 이어질 수 있다. 1980년대 실제로 이런 붕괴가 관측된 이후 Van Jacobson이 제안한 혼잡제어 알고리즘이 오늘날 모든 TCP 구현의 기반이 되었다. 핵심 아이디어는 "네트워크로부터 받는 간접적인 신호(손실, 지연)를 관산해 송신 속도를 스스로 조절한다"는 것이다.

## 2. AIMD와 Reno의 동작

Reno는 혼잡 윈도우(cwnd, congestion window)라는 변수로 한 번에 보낼 수 있는 미확인 데이터량을 제한한다. 손실이 없으면 매 RTT마다 cwnd를 조금씩(선형적으로) 늘리고, 손실이 감지되면 즉시 절반으로 줄이는 AIMD 규칙을 따른다.

```
손실 없음: cwnd = cwnd + 1 (RTT당 1 MSS씩 증가, 선형)
손실 감지(3중복 ACK): cwnd = cwnd / 2 (즉시 절반으로 감소)
손실 감지(타임아웃): cwnd = 1 (슬로우 스타트로 재시작)
```

이 선형 증가·급격한 감소 패턴은 "톱니파(sawtooth)" 형태의 처리량 그래프를 만든다. AIMD가 공정성과 안정성을 동시에 보장한다는 수학적 증명(체이유·자인의 공정성 분석)이 있어 수십년간 표준으로 자리잡았지만, 문제는 대역폭이 크고 지연(RTT)이 긴 네트워크, 이른바 고 BDP(Bandwidth-Delay Product) 네트워크에서 이 선형 증가가 지나치게 느리다는 점이다.

## 3. CUBIC: 큐빙 함수 기반 증가

CUBIC은 Reno의 선형 증가 대신 3차 함수(cubic function) 형태로 cwnd를 증가시켜, 손실 직후에는 조심스럽게 증가하다가 이전 손실 시점의 cwnd에 가까워질수록 증가 속도를 줄이고, 그 지점을 넘어서면 다시 빠르게 증가하는 곁선을 그린다. Linux 커널은 2006년부터 CUBIC을 기본 혼잡제어 알고리즘으로 채택했다.

## 4. 버퍼블로트: 큐가 클수록 나빠지는 역설

버퍼블로트는 네트워크 장비의 버퍼가 지나치게 크게 설정되어 있을 때 발생하는 현상이다. 손실 기반 혼잡제어는 "패킷이 버려져야만" 혼잡 신호로 인식하는데, 버퍼가 매우 크면 패킷이 버려지기 전에 먼저 큐에 오래 쌓이면서 지연 시간이 크게 늘어난다. 버퍼블로트를 완화하기 위한 라우터 측 대응이 AQM(Active Queue Management)이며, 대표적으로 CoDel(Controlled Delay)은 큐에 머무는 시간을 측정해 미리 패킷을 드롭해 조기 신호를 준다.

```bash
tc qdisc add dev eth0 root fq_codel
```

## 5. BBR: 손실이 아닌 모델 기반 혼잡 추정

Google이 2016년 발표한 BBR은 손실을 혼잡 신호로 쓰지 않는다. 대신 BtlBw(Bottleneck Bandwidth)와 RTprop(Round-trip propagation time) 두 값을 직접 측정해 그 곱(BDP)을 목표 전송량으로 삼아 cwnd와 페이싱 속도를 조절한다. `PROBE_BW`, `PROBE_RTT` 상태를 오가며 모델을 지속적으로 갱신한다.

## 6. 손실 기반과 모델 기반의 공존 문제

같은 병목에서 CUBIC과 BBR이 경쟁하면 공정성 문제가 생길 수 있다. BBRv2는 손실 신호를 보조적으로 반영하도록 설계가 수정되었다.

## 7. 실전 관측과 알고리즘 선택

```bash
sysctl net.ipv4.tcp_available_congestion_control
sysctl net.ipv4.tcp_congestion_control
sysctl -w net.ipv4.tcp_congestion_control=bbr
```

BBR을 커널 레벨에서 활성화하려면 페이싱을 지원하는 큐 규율(`fq`)이 함께 필요하다.

## 8. QUIC과 사용자 공간 혼잡제어

QUIC(HTTP/3의 전송 계층)은 UDP 위에서 사용자 공간에서 전송 프로토콜 전체를 재구현하면서 혼잡제어 알고리즘도 애플리케이션과 함께 배포되는 라이브러리 코드로 옥겨졌다. Google의 QUIC 구현체는 초기부터 BBR을 기본 혼잡제어로 채택했다.

## 9. 애플리케이션 레벨에서 체감하는 영향

모바일 네트워크에서는 실제 혼잡이 아닌 무선 구간의 일시적 신호 저하로도 패킷 손실이 발생하는데, BBR은 이런 환경에서 처리량 저하가 덜한 경향이 보고된다. `ss -ti` 명령으로 소켓별 혼잡제어 알고리즘과 재전송 횟수를 확인하는 것이 원인 분석의 첫 단계다.

```bash
ss -ti dst 203.0.113.10
```

## 참고

- Van Jacobson, "Congestion Avoidance and Control" (SIGCOMM 1988)
- Sangtae Ha 외, "CUBIC: A New TCP-Friendly High-Speed TCP Variant"
- Kathleen Nichols, Van Jacobson, "Controlling Queue Delay" (CoDel, ACM Queue)
- Neal Cardwell 외(Google), "BBR: Congestion-Based Congestion Control" (ACM Queue / SIGCOMM)
