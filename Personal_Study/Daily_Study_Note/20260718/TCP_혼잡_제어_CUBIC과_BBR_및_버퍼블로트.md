Notion 원본: https://www.notion.so/3a15a06fd6d38192aa3fd1e5137e22d4

# TCP 혼잡 제어 CUBIC과 BBR 및 버퍼블로트

> 2026-07-18 신규 주제 · 확장 대상: 통신_네트워크

## 학습 목표

- 흐름 제어와 혼잡 제어의 책임 분리를 BDP 관점에서 재구성한다.
- CUBIC 의 3차 함수 증가 곡선과 BBR 의 모델 기반 추정을 수식 수준에서 비교한다.
- 버퍼블로트가 손실 기반 알고리즘의 평형점을 어떻게 왜곡하는지 추적한다.
- 워크로드 특성에 맞는 혼잡 제어 알고리즘과 qdisc 조합을 선택 기준으로 정리한다.

## 1. 두 개의 윈도: rwnd 와 cwnd

TCP 송신자가 ACK 없이 네트워크에 흘려보낼 수 있는 바이트 수는 두 개의 독립적인 상한으로 결정된다. 하나는 수신자가 광고하는 수신 윈도 `rwnd` 이고, 다른 하나는 송신자가 스스로 추정해 유지하는 혼잡 윈도 `cwnd` 다. 실제 in-flight 허용량은 이 둘의 최소값이다.

```
inflight_allowed = min(cwnd, rwnd)
```

`rwnd` 는 종단 문제다. 수신 애플리케이션이 소켓 버퍼를 비우는 속도가 느리면 rwnd 가 줄고, 극단적으로 0 이 되면 zero-window 상태가 된다. 네트워크와 무관하게 "받는 쪽이 못 따라간다"는 신호다. Spring 애플리케이션에서 스레드 풀이 포화돼 `InputStream` 을 읽지 못하면 정확히 이 상황이 발생한다.

`cwnd` 는 네트워크 문제다. 송신자와 수신자 사이 어딘가가 감당하지 못하는 상황을 송신자가 간접 증거(손실, RTT 변화, ECN 마크)로 추론해 스스로 제한하는 값이다. 아무도 cwnd 를 알려주지 않는다. TCP 혼잡 제어의 역사는 결국 "이 관측 불가능한 값을 어떤 신호로 추정할 것인가"의 역사다.

추정의 목표점은 BDP(Bandwidth-Delay Product)다. 병목 대역폭 `BtlBw` 와 전파 왕복 지연 `RTprop` 의 곱이 파이프의 용적이다.

```
BDP = BtlBw × RTprop
```

10 Gbps 링크에 RTT 80 ms(서울-미국 서부 수준)라면 BDP 는 `10e9 / 8 × 0.08 = 100 MB` 다. 100 MB 를 채워야 링크가 포화된다. 여기서 두 가지 실무적 함의가 나온다. 첫째, 소켓 버퍼가 BDP 보다 작으면 알고리즘이 무엇이든 링크를 못 채운다. 둘째, cwnd 가 BDP 를 초과해 늘어난 만큼은 파이프가 아니라 병목 링크 앞의 **큐**에 쌓인다. 이 두 번째 사실이 이 문서 전체를 관통하는 축이다.

## 2. "손실 = 혼잡" 가정과 그 유효기간

Van Jacobson 이 1988년에 정립하고 RFC 5681 로 표준화된 Reno 계열의 핵심 전제는 단순하다. 패킷이 사라졌다면 어딘가 큐가 넘쳤다는 뜻이고, 큐가 넘쳤다면 보내는 양을 줄여야 한다. 1980년대 후반 유선 링크의 비트 오류율은 무시할 만했고 라우터 버퍼는 비쌌으므로 작았다. 손실은 거의 항상 혼잡이었고, 큐가 작으니 손실은 혼잡 직후 빠르게 발생했다. 신호로서 손실은 **정확했고 적시적**이었다.

이 가정이 깨지는 지점은 두 방향이다.

**무선 링크**에서는 페이딩·간섭으로 인한 비혼잡 손실이 발생한다. 링크가 한가한데도 프레임이 깨지고, 송신자는 이를 혼잡으로 오독해 cwnd 를 반으로 접는다. 실제로는 LTE/5G 계층이 HARQ 재전송으로 대부분 감추기 때문에 손실 대신 **지연 급증** 으로 변환돼 올라오는데, 이건 이것대로 RTT 추정을 흔든다.

**거대 버퍼**에서는 손실이 정확하긴 하나 **너무 늦게** 온다. 병목 앞에 1초치 버퍼가 있으면 cwnd 가 BDP 를 넘어선 뒤에도 1초 동안 손실이 나지 않는다. 손실 기반 알고리즘은 손실이 날 때까지 계속 늘리므로 결국 버퍼를 가득 채운 상태에서 평형에 도달한다. 이게 5절의 버퍼블로트다.

## 3. AIMD 톱니와 고BDP 링크에서의 좌절

Reno/NewReno 의 정상 상태 동작은 AIMD(Additive Increase, Multiplicative Decrease)다. Congestion avoidance 구간에서 RTT 당 1 MSS 씩 늘리고, 손실 시 절반으로 줄인다.

```
증가: cwnd += MSS/cwnd  (ACK 당) → RTT 당 약 +1 MSS
감소: cwnd = cwnd / 2
```

이 톱니의 평균 처리율은 손실률 `p` 에 대해 잘 알려진 근사식을 따른다.

```
Throughput ≈ (MSS / RTT) × (C / sqrt(p))
```

두 항 모두 문제다. 처리율이 RTT 에 반비례하므로 RTT 가 긴 흐름은 짧은 흐름에 구조적으로 밀린다(RTT 불공정). 그리고 `1/sqrt(p)` 항 때문에 고속 링크를 채우려면 비현실적으로 낮은 손실률이 필요하다.

회복 시간을 직접 계산해 보면 더 명확하다. 10 Gbps, RTT 100 ms, MSS 1460 B 링크에서 BDP 는 약 85,600 패킷이다. 손실로 cwnd 가 절반이 되면 42,800 패킷을 잃은 것이고, RTT 당 1 MSS 씩 회복하므로 42,800 RTT = **약 71분**이 걸린다. 그 71분 동안 단 한 번의 손실도 없어야 한다. 장거리 대용량 전송에서 Reno 는 사실상 링크를 채울 수 없다. 이 좌절이 BIC, CUBIC, HTCP, Illinois 같은 고속 변종을 낳았다.

## 4. CUBIC: 시간의 함수로서의 윈도

CUBIC(RFC 8312, 이후 RFC 9438 로 갱신)의 발상 전환은 cwnd 증가를 **ACK 이벤트가 아니라 마지막 혼잡 이벤트 이후 경과한 실시간 `t`** 의 함수로 정의한 것이다.

```
W(t) = C(t - K)^3 + W_max

K = cbrt(W_max × β / C)
```

`W_max` 는 직전 손실 시점의 윈도, `β` 는 감소 계수(리눅스에서 0.7), `C` 는 스케일 상수(기본 0.4)다. `K` 는 감소된 지점에서 다시 `W_max` 에 도달하기까지 걸리는 시간이다.

3차 함수의 모양이 설계의 전부다. 손실 직후 `t` 가 작을 때는 `(t-K)^3` 이 큰 음수라 곡선이 `W_max` 를 향해 **가파르게 상승**한다(concave 구간). 이전에 문제없이 통과했던 대역폭 근처까지는 빠르게 회복하겠다는 의도다. `W_max` 근처에서는 미분값이 0 에 가까워져 **평평해진다**. 이 구간에 오래 머물며 병목이 여전히 그 정도인지 조심스럽게 확인한다. 그래도 손실이 없으면 `t > K` 이후 `(t-K)^3` 이 다시 커지며 **가속 탐색**에 들어간다(convex 구간). 새 대역폭이 생겼다면 공격적으로 찾아 나선다.

핵심은 `t` 가 벽시계 시간이라는 점이다. RTT 가 10 ms 든 200 ms 든 같은 시간이 흐르면 같은 윈도에 도달한다. cwnd 증가가 ACK 도착 빈도에 묶여 있던 Reno 의 RTT 불공정이 구조적으로 제거된다. 이게 CUBIC 의 명시적 설계 목표인 **RTT-fairness** 다. 다만 RTT 가 매우 짧은 환경에서는 CUBIC 이 Reno 보다 덜 공격적이 될 수 있어, 표준은 Reno 의 예상 윈도를 계산해 그보다 작으면 Reno 값을 쓰는 **TCP-friendly region** 을 규정한다.

CUBIC 이 리눅스 기본값인 이유는 이 조합 때문이다. 고BDP 에서 파이프를 채우고, RTT 가 섞인 환경에서 비교적 공정하고, 기존 Reno 흐름을 굶기지 않고, 커널에서 3차 함수 계산이 정수 연산으로 충분히 싸다.

**Hystart / Hystart++**: CUBIC 의 slow start 는 여전히 지수 증가라 고속 링크에서 한 RTT 만에 버퍼를 수만 패킷 오버슈트할 수 있다. Hystart 는 ACK train 간격과 RTT 증가 추세를 관찰해 손실 **전에** slow start 를 빠져나온다. Hystart++ (RFC 9406)는 초기 오탐으로 너무 일찍 나가는 문제를 개선하고, 종료 후 곧바로 congestion avoidance 로 가지 않고 **CSS(Conservative Slow Start)** 라는 중간 단계를 두어 증가율을 낮춘 채 재확인한다.

```bash
# Hystart 상태 확인
cat /sys/module/tcp_cubic/parameters/hystart          # 1
cat /sys/module/tcp_cubic/parameters/hystart_detect   # 3 (both)
cat /sys/module/tcp_cubic/parameters/beta             # 717 (/1024 ≈ 0.7)
cat /sys/module/tcp_cubic/parameters/bic_scale        # 41 → C ≈ 0.4
```

## 5. 버퍼블로트: 손실 기반 CC 가 도달하는 잘못된 평형

메모리가 싸지면서 라우터·홈 게이트웨이·모뎀·NIC 링 버퍼가 비대해졌다. "버퍼는 크면 좋다, 패킷을 안 버리니까"라는 직관이 손실 기반 혼잡 제어와 만나면 병리적으로 상호작용한다.

메커니즘은 단선적이다. CUBIC 은 손실이 없으면 cwnd 를 계속 늘린다. cwnd 가 BDP 를 넘어서면 초과분은 병목 앞 큐에 축적된다. 큐가 아무리 깊어도 손실이 안 나므로 CUBIC 은 계속 늘린다. 결국 **버퍼가 가득 찬 시점에야** 손실이 나고, 거기서 β 만큼 줄였다가 다시 채운다. 즉 손실 기반 CC 의 정상 상태 평형점은 "파이프가 참 지점"이 아니라 **"파이프 + 버퍼가 모두 찬 지점"** 이다.

이때 관측되는 RTT 는 다음과 같이 부푼다.

```
RTT_observed = RTprop + Queue_bytes / BtlBw
```

업로드 10 Mbps 회선의 모뎀에 1 MB 버퍼가 있다면 큐 지연만 `1e6 × 8 / 10e6 = 0.8초` 다. 기본 RTprop 이 20 ms 여도 관측 RTT 는 820 ms 가 된다. 대용량 업로드 한 개가 회선의 모든 인터랙티브 트래픽을 죽인다. 화상회의가 끊기고, SSH 가 씹히고, DNS 가 타임아웃되고, 게임 핑이 폭발한다. 정작 처리율은 전혀 늘지 않는다. 버퍼에 쌓인 데이터는 파이프에 있는 데이터보다 **빨리 도착하지 않기 때문**이다. 순수한 손해다.

서버 쪽에도 같은 병이 있다. 클라우드 인스턴스의 NIC TX 링, 가상 스위치, 오버레이 터널이 각각 자기 버퍼를 갖는다. p99 레이턴시가 튀는데 CPU 도 GC 도 한가하다면 큐 지연을 의심할 값이 있다.

```bash
# 부하 중 큐 지연 확인 — RTT 가 idle 대비 몇 배로 뛰는가
ping -c 20 target.example.com          # idle 기준선
# (대용량 전송 시작 후 재측정 → 차이가 큐 지연)
tc -s qdisc show dev eth0              # backlog 필드가 쌓여 있는지
```

## 6. BBR: 파이프를 채우되 큐는 비운다

BBR(Bottleneck Bandwidth and Round-trip propagation time)은 손실을 신호로 쓰지 않고 네트워크 경로의 **모델**을 세운다. 이론적 근거는 Kleinrock 의 결과다. 처리율이 최대이면서 동시에 지연이 최소인 최적 동작점은 정확히 **inflight = BDP** 지점이다. 그보다 적게 보내면 대역폭을 못 쓰고, 그보다 많이 보내면 큐만 쌓이고 처리율은 그대로다.

문제는 `BtlBw` 와 `RTprop` 을 동시에 측정할 수 없다는 것이다. 큐가 차 있어야 대역폭 최대치를 관측할 수 있고, 큐가 비어 있어야 순수 전파 지연을 관측할 수 있다. BBR 은 이를 **서로 다른 시간 창의 필터**로 분리한다.

```
BtlBw = max(delivery_rate)  over 최근 약 10 RTT     # max 필터
RTprop = min(RTT)           over 최근 약 10 초       # min 필터
BDP    = BtlBw × RTprop
```

max 필터는 최근 관측된 최대 전달률을, min 필터는 최근 관측된 최소 RTT 를 취한다. 각 표본은 다른 시점에 얻지만 필터가 이를 합성해 하나의 모델을 만든다.

가장 큰 발상 전환은 제어 변수다. BBR 은 cwnd 창을 열고 닫는 대신 **pacing rate** 를 1차 제어 변수로 삼는다. cwnd 는 안전망(대략 `2 × BDP` 상한)으로만 쓴다.

```
pacing_rate = pacing_gain × BtlBw
cwnd        = cwnd_gain × BDP
```

윈도 기반 전송은 ACK 이 몰려 오면 데이터를 버스트로 뱉는다. 버스트는 순간적으로 큐를 만든다. BBR 은 패킷 간 간격을 계산해 균등하게 흘려보내므로 큐를 만들지 않는다. 다만 커널이 이 간격을 실제로 지켜 주어야 하며, 그래서 **fq qdisc** 가 필요하다(최신 커널은 TCP 계층 내부 pacing 도 지원하지만 `fq` 가 권장 구성이다).

**상태 기계**는 네 개다.

| 상태 | pacing_gain | 목적 | 전이 조건 |
|---|---|---|---|
| Startup | 2/ln2 ≈ 2.89 | 대역폭 지수 탐색 | 3 RTT 연속 BtlBw 증가율 25% 미만 |
| Drain | 1/2.89 ≈ 0.35 | Startup 이 만든 큐 배출 | inflight ≤ BDP |
| ProbeBW | [1.25, 0.75, 1, 1, 1, 1, 1, 1] 순환 | 정상 상태 + 주기적 대역폭 탐색 | 대부분의 시간 여기 체류 |
| ProbeRTT | 1 | RTprop 재측정 | min-RTT 표본이 10초간 갱신 안 되면 진입 |

ProbeBW 의 8-페이즈 사이클이 BBR 의 심장이다. 1.25 로 한 RTT 동안 초과 전송해 더 큰 대역폭이 있는지 찔러 보고, 곧바로 0.75 로 한 RTT 동안 줄여 방금 만든 큐를 **되돌려 놓는다**. 나머지 6 페이즈는 gain 1 로 순항한다. 큐를 만들되 즉시 갚는 구조라 정상 상태 큐 점유가 낮게 유지된다.

ProbeRTT 는 대가가 크다. inflight 를 4 패킷 수준까지 떨어뜨리고 최소 200 ms 유지해 큐를 확실히 비운 뒤 RTprop 표본을 얻는다. 이 순간 처리율이 급감한다. 다만 같은 병목의 BBR 흐름들이 ProbeRTT 에 동기화되는 경향이 있어, 큐가 함께 비면서 서로의 측정을 도와주는 부수 효과가 있다.

버퍼블로트 관점에서 BBR 의 가치는 명확하다. 손실을 기다리지 않으므로 **큐가 깊어도 채우지 않는다**.

## 7. BBR v1 의 함정과 v2/v3 의 방향

BBR v1 은 손실을 거의 무시한다. 이 대담함이 강점이자 결함이다.

**얕은 버퍼에서의 손실률 상승.** cwnd_gain 이 2 라 최대 `2 × BDP` 까지 in-flight 를 허용한다. 병목 버퍼가 BDP 보다 훨씬 얕으면 초과분은 큐가 아니라 **드롭**이 된다. BBR 은 손실에 반응하지 않으므로 계속 그 수준을 유지하고, 지속적인 높은 재전송률이 관측된다. 인터넷 코어의 얕은 버퍼 스위치에서 실제로 문제가 됐다.

**CUBIC 과의 공존.** 깊은 버퍼를 공유하면 CUBIC 은 손실을 볼 때마다 물러나고 BBR 은 자기 모델대로 밀어붙인다. CUBIC 이 반복적으로 밀려나며 BBR 이 대역폭을 더 가져가는 시나리오가 보고됐다. 반대로 얕은 버퍼에서는 BBR 이 만든 손실이 CUBIC 을 더 세게 때린다. 어느 쪽이든 "BBR 을 켜면 우리 트래픽만 잘 되고 옆 흐름이 손해 보는" 구도가 생길 수 있다.

BBRv2/v3 의 개선 방향은 "모델 기반을 유지하되 손실과 ECN 을 **명시적 상한 신호**로 편입"이다. 손실률 목표치를 두어 그 이상 손실이 나면 inflight 상한을 낮추고, ECN 마크(특히 DCTCP 스타일 정밀 신호)를 받아 큐 형성을 조기 감지한다. 결과적으로 얕은 버퍼 손실률이 크게 낮아지고 CUBIC 과의 공존이 개선되지만, 순수 v1 대비 일부 시나리오에서 처리율은 보수적으로 나온다. 이 계열은 지속적으로 개정되고 있으므로 도입 전 사용 중인 커널의 실제 구현 버전과 동작을 확인하는 것이 안전하다.

## 8. 중간 노드의 해법: AQM, FQ-CoDel, CAKE, ECN

종단 알고리즘을 아무리 잘 만들어도 버퍼블로트는 근본적으로 **중간 노드가 큐를 잘못 관리하는 문제**다. 병목 라우터에서 직접 푸는 편이 옳다.

**CoDel**(Controlled Delay)은 큐 **길이**가 아니라 패킷이 큐에 머문 **시간**(sojourn time)을 본다. 목표 지연(기본 5 ms)을 interval(기본 100 ms) 동안 지속적으로 넘으면 드롭을 시작하고, 상태가 지속되면 드롭 간격을 좁힌다. 순간 버스트(good queue)는 통과시키고 지속적으로 쌓인 큐(bad queue)만 때리는 것이 요점이다.

**FQ-CoDel**(RFC 8290)은 여기에 flow queueing 을 결합한다. 5-tuple 해시로 흐름을 분리해 각 흐름이 자기 큐를 갖고, 각 큐에 독립적으로 CoDel 을 적용하며, 신규·희소 흐름에 우선권을 준다. 효과가 극적인 이유는 대용량 흐름이 자기 큐만 채우고 지연을 자기가 부담하기 때문이다. 같은 회선에서 DNS 나 SSH 패킷은 거의 즉시 빠져나간다.

**CAKE**는 FQ-CoDel 의 실용적 확장으로, 셰이퍼 내장, 링크 계층 오버헤드 보정(ATM/DOCSIS/PPPoE), 호스트 단위 + 흐름 단위 이중 공정성, DiffServ 인지를 통합했다. 핵심은 **모뎀보다 약간 느린 속도로 셰이핑**해 병목을 내가 통제하는 노드로 옮기는 것이다.

**ECN** 은 드롭 대신 헤더에 마크를 찍어 혼잡을 알린다. 손실 없이 신호를 전달하므로 재전송 비용이 사라지지만, 고전 ECN 은 드롭과 동일하게 반응(cwnd 절반)해 신호 해상도가 낮다. **L4S**(Low Latency, Low Loss, Scalable throughput)는 ECT(1) 코드포인트로 L4S 트래픽을 구분해 별도 큐에 넣고 **매우 자주 마크**하며, 종단은 마크 **비율에 비례**해 미세 조정한다(DCTCP 계열). 다만 Classic ECN 과의 공존을 위한 dual-queue 구조와 배포 복잡도가 확산의 관건이다.

```bash
# 기본 qdisc 를 fq_codel 로 (대부분 배포판 기본)
sysctl -w net.core.default_qdisc=fq_codel

# 특정 인터페이스에 CAKE + 셰이핑 (회선 속도의 ~95%)
tc qdisc replace dev eth0 root cake bandwidth 95mbit

# ECN 협상 활성화 (3 = 수신 요청 시 수락, 능동 요청 안 함)
sysctl -w net.ipv4.tcp_ecn=3
```

## 9. 실무 튜닝: 무엇을 언제 고를 것인가

**현재 상태 파악부터.**

```bash
sysctl net.ipv4.tcp_congestion_control            # 현재 기본값
sysctl net.ipv4.tcp_available_congestion_control  # 로드된 모듈
modprobe tcp_bbr && lsmod | grep tcp_bbr          # 없으면 로드
```

**BBR 적용 (fq 와 함께, 영구 설정).**

```bash
cat >> /etc/sysctl.d/99-tcp.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sysctl --system
```

`default_qdisc` 는 **이후 생성되는** 인터페이스에만 적용된다. 기존 인터페이스는 `tc qdisc replace dev eth0 root fq` 를 직접 실행하거나 재부팅해야 한다. `tc qdisc show dev eth0` 로 실제 적용을 확인하지 않으면 BBR 이 pacing 없이 도는 애매한 상태가 될 수 있다.

**소켓 단위 선택.** 전역 변경 없이 특정 커넥션만 다르게 갈 수 있다. Java 는 표준 API 로 `TCP_CONGESTION` 을 노출하지 않으므로, cgroup 기반 정책이나 프론트 프록시(Envoy/NGINX)에 위임하는 접근이 현실적이다.

```c
setsockopt(fd, IPPROTO_TCP, TCP_CONGESTION, "bbr", 3);
```

**흐름별 실측 읽기.** `ss -ti` 가 가장 중요한 도구다.

```bash
ss -ti state established '( dport = :443 )'
# cwnd:107  ssthresh:53  rtt:23.4/1.2  bytes_retrans:8192
# delivery_rate 87.4Mbps  pacing_rate 105Mbps  bbr:(bw:87.4Mbps,mrtt:21.9,...)
```

읽는 법: `rtt` 의 두 값은 평균/편차다. `mrtt`(BBR) 나 idle 시 ping 대비 부하 중 `rtt` 가 크게 뛰면 큐 지연이다. `cwnd` 가 BDP 환산치보다 훨씬 작은데 `bytes_retrans` 가 늘면 손실 제약, `cwnd` 는 큰데 처리율이 낮으면 rwnd 나 애플리케이션 제약이다. `bbr:` 필드가 없으면 BBR 이 실제로 안 붙은 것이다.

**버퍼 자동 튜닝.** 리눅스는 기본적으로 수신 버퍼를 자동 조절한다.

```bash
sysctl net.ipv4.tcp_moderate_rcvbuf   # 1 이어야 함
sysctl net.ipv4.tcp_rmem              # min default max
sysctl net.ipv4.tcp_wmem
```

고BDP 경로에서 `max` 가 BDP 보다 작으면 알고리즘과 무관하게 상한에 걸린다. 앞의 100 MB 예시라면 기본값(보통 수 MB)으로는 근처도 못 간다. 다만 무조건 키우면 메모리 압박과 자체 버퍼블로트를 부르므로 **실제 경로의 BDP 를 계산해 그 언저리로** 잡는다. 애플리케이션이 `setsockopt(SO_RCVBUF)` 로 명시 지정하면 **자동 튜닝이 꺼진다**. Java 의 `StandardSocketOptions.SO_RCVBUF` 를 무심코 박아 두면 자동 튜닝을 무력화하는 함정이 여기 있다.

**송신 큐 지연 억제.** `tcp_notsent_lowat` 은 아직 전송되지 않은 데이터가 이 임계 미만일 때만 소켓을 writable 로 보고한다. 애플리케이션이 소켓 송신 버퍼에 수 MB 를 미리 쌓아 두는 것을 막아, HTTP/2 처럼 한 커넥션에서 스트림 우선순위를 재조정해야 하는 경우 응답성을 크게 개선한다.

```bash
sysctl -w net.ipv4.tcp_notsent_lowat=131072   # 128KB
```

**선택 기준.**

| 워크로드 | 권장 | 근거 |
|---|---|---|
| 장거리 대용량 전송 (CDN origin, 리전 간 복제, 백업) | BBR + fq | 고BDP·비혼잡 손실 환경에서 손실 기반보다 파이프를 잘 채움 |
| 사내/AZ 내 저지연 RPC | CUBIC 기본 유지 | BDP 가 작아 이득 미미, 검증된 기본값이 안전 |
| 데이터센터 내부 (ECN 지원 스위치 전제) | DCTCP | 정밀 ECN 마크로 큐를 얕게 유지 |
| 공용 인터넷 대상 웹 서비스 | CUBIC 유지 또는 신중한 BBR | 공정성 논란이 있는 영역, 재전송률 회귀 모니터링 필수 |
| 상향 회선의 버퍼블로트 | CAKE/FQ-CoDel 셰이핑 | 종단이 아닌 병목에서 해결하는 것이 정공법 |

원칙은 **바꾸기 전에 측정하고 바꾸기 전에**이 아니라 **바꾸기 전에 측정하고 바꾼 뒤 회귀를 본다**는 것이다. 최소한 부하 중 p99 RTT, `bytes_retrans` 기반 재전송률, 실효 처리율 세 가지는 전후로 비교해야 한다. 그리고 대부분의 백엔드 성능 문제는 혼잡 제어가 아니라 GC·스레드 풀·DB 커넥션 풀·N+1 쿼리다. 혼잡 제어 튜닝은 **네트워크가 실제 병목임을 증명한 뒤**에 손댈 카드다.

## 참고

- RFC 5681 — TCP Congestion Control (Reno/NewReno 표준)
- RFC 9438 — CUBIC for Fast and Long-Distance Networks (RFC 8312 갱신)
- RFC 9406 — HyStart++: Modified Slow Start for TCP
- RFC 8290 — The FlowQueue-CoDel Packet Scheduler and Active Queue Management Algorithm
- Cardwell et al., "BBR: Congestion-Based Congestion Control", ACM Queue / CACM
- Linux kernel: `Documentation/networking/ip-sysctl.rst`, `net/ipv4/tcp_cubic.c`, `net/ipv4/tcp_bbr.c`
