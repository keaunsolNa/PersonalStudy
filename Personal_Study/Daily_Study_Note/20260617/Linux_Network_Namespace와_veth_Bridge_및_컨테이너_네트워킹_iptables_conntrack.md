Notion 원본: https://www.notion.so/3825a06fd6d3814d8f4cc73da29fef23

# Linux Network Namespace와 veth · Bridge 및 컨테이너 네트워킹 iptables · conntrack

> 2026-06-17 신규 주제 · 확장 대상: Docker&CI, OS, 통신_네트워크

## 학습 목표

- Network Namespace가 격리하는 네트워크 자원과 컨테이너 네트워킹의 토대를 직접 만든다
- veth pair와 Linux bridge로 두 namespace를 연결하고 패킷 경로를 추적한다
- iptables MASQUERADE/DNAT가 컨테이너 외부 통신과 포트 포워딩을 어떻게 구현하는지 본다
- conntrack 테이블과 NAT 상태 추적의 동작·한계를 진단한다

## 1. Network Namespace — 컨테이너 네트워킹의 출발점

Docker 컨테이너가 자신만의 IP, 라우팅 테이블, 방화벽 규칙을 갖는 비결은 마법이 아니라 리눅스 커널의 **Network Namespace**다. namespace는 네트워크 인터페이스, 라우팅 테이블, iptables 규칙, 소켓, `/proc/net`을 독립적으로 격리한다. 컨테이너 런타임은 컨테이너마다 새 network namespace를 만들고 그 안에 가상 인터페이스를 넣는다. `ip netns`로 직접 만들어 보면 추상화 뒤의 실체가 보인다.

```bash
# 두 namespace 생성 — 각자 독립된 네트워크 스택
sudo ip netns add ns1
sudo ip netns add ns2

# ns1 안에서 명령 실행 — lo만 있고 down 상태
sudo ip netns exec ns1 ip addr
# 1: lo: <LOOPBACK> mtu 65536 state DOWN
```

새 namespace는 loopback조차 down이다. 외부와 통신하려면 인터페이스를 연결해야 한다.

## 2. veth pair — namespace를 잇는 가상 케이블

**veth(virtual ethernet) pair**는 양 끕이 연결된 가상 랜선이다. 한쪽에 들어간 패킷이 다른 쪽으로 그대로 나온다. 컨테이너 네트워킹의 기본 빌딩 블록으로, 한쪽 끕은 컨테이너 namespace에, 다른 끕은 호스트(또는 bridge)에 둔다.

```bash
# veth pair 생성: veth1 <-> veth2
sudo ip link add veth1 type veth peer name veth2

# 각 끕을 namespace로 이동
sudo ip link set veth1 netns ns1
sudo ip link set veth2 netns ns2

# 양쪽에 IP 부여 후 up
sudo ip netns exec ns1 ip addr add 10.0.0.1/24 dev veth1
sudo ip netns exec ns1 ip link set veth1 up
sudo ip netns exec ns1 ip link set lo up
sudo ip netns exec ns2 ip addr add 10.0.0.2/24 dev veth2
sudo ip netns exec ns2 ip link set veth2 up

# ns1 -> ns2 통신 확인
sudo ip netns exec ns1 ping -c2 10.0.0.2   # 성공
```

이건 두 namespace를 1:1로 직결한 것이다. 하지만 컨테이너가 수십 개면 이 방식은 확장되지 않는다 — 그래서 bridge가 필요하다.

## 3. Linux Bridge — 가상 스위치로 여러 컨테이너 묶기

Docker의 기본 `bridge` 네트워크(docker0)는 **Linux bridge**, 즉 L2 가상 스위치다. 각 컨테이너의 veth 한쪽 끕을 bridge에 꽂으면 같은 서브넷의 컨테이너들이 서로 통신한다. bridge가 MAC 학습을 하며 프레임을 포워딩한다.

```bash
# 브리지 생성
sudo ip link add br0 type bridge
sudo ip link set br0 up
sudo ip addr add 10.0.0.254/24 dev br0   # 브리지가 게이트웨이 역할

# ns1의 호스트측 veth 끕을 브리지에 연결
sudo ip link set veth1-host master br0
sudo ip link set veth1-host up

# 컨테이너 내부 기본 게이트웨이를 브리지로
sudo ip netns exec ns1 ip route add default via 10.0.0.254
```

이 구조가 정확히 docker0의 동작이다. `brctl show` 또는 `ip link show master docker0`로 어떤 veth들이 붙어 있는지 볼 수 있다. 컨테이너 간 통신은 bridge에서 L2로 끝나지만, **외부(인터넷) 통신은 L3 라우팅 + NAT**가 필요하다.

## 4. iptables MASQUERADE — 컨테이너의 외부 통신

컨테이너의 사설 IP(10.0.0.x)는 외부에서 라우팅되지 않는다. 컨테이너가 인터넷에 나가려면 패킷의 출발지 IP를 호스트의 IP로 바꾸는 **SNAT**가 필요하고, Docker는 이를 `MASQUERADE` 규칙으로 구현한다. MASQUERADE는 출력 인터페이스의 IP를 동적으로 사용하는 SNAT의 변형이다.

```bash
# IP 포워딩 활성화 (라우터처럼 동작)
sudo sysctl -w net.ipv4.ip_forward=1

# 10.0.0.0/24에서 나가는 패킷을 호스트 IP로 SNAT
sudo iptables -t nat -A POSTROUTING -s 10.0.0.0/24 ! -o br0 -j MASQUERADE
```

이제 컨테이너 → 인터넷 패킷은 POSTROUTING 체인에서 출발지가 호스트 공인 IP로 치환되어 나가고, 응답은 호스트가 받아 다시 컨테이너로 역변환(de-NAT)된다. 이 역변환이 가능한 이유가 conntrack이다(§6).

## 5. DNAT — 포트 포워딩(docker -p)의 정체

`docker run -p 8080:80`은 호스트 8080 포트로 들어온 트래픽을 컨테이너 80으로 보낸다. 이는 목적지 주소를 바꾸는 **DNAT**다. PREROUTING 체인에서 목적지 IP:포트를 컨테이너의 것으로 치환한다.

```bash
# 호스트 8080 → 컨테이너(10.0.0.1):80 으로 DNAT
sudo iptables -t nat -A PREROUTING -p tcp --dport 8080 \
     -j DNAT --to-destination 10.0.0.1:80

# 호스트 자신에서의 접근(루프백 경유)을 위한 OUTPUT 체인 규칙도 필요
sudo iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 8080 \
     -j DNAT --to-destination 10.0.0.1:80
```

Docker는 실제로 `DOCKER` 라는 사용자 정의 체인을 만들고 거기에 규칙을 모아 관리한다. `iptables -t nat -L DOCKER -n`으로 매핑을 확인할 수 있다. 이 규칙들이 컨테이너 시작/종료에 따라 동적으로 추가·삭제된다.

## 6. conntrack — NAT를 지탱하는 연결 추적

NAT가 양방향으로 동작하려면 커널이 "이 연결의 원래 주소가 무엇이었는지"를 기억해야 한다. **conntrack(connection tracking)** 이 모든 연결의 상태(NEW, ESTABLISHED, RELATED)와 원본/변환 튜플을 테이블에 저장한다. 응답 패킷이 오면 conntrack 항목을 찾아 자동으로 역변환한다.

```bash
# 현재 추적 중인 연결 — NAT 변환 내역이 보인다
sudo conntrack -L
# tcp 6 431999 ESTABLISHED src=10.0.0.1 dst=1.1.1.1 sport=54321 dport=443
#   src=1.1.1.1 dst=192.168.1.10 sport=443 dport=54321 [ASSURED]

# 테이블 사용량과 한계 확인
cat /proc/sys/net/netfilter/nf_conntrack_count
cat /proc/sys/net/netfilter/nf_conntrack_max
```

운영상 함정: 트래픽이 많은 게이트웨이/노드에서 conntrack 테이블이 가득 차면 `nf_conntrack: table full, dropping packet` 로그와 함께 신규 연결이 조용히 드롭된다. 증상은 "간헐적 연결 실패"로 나타나 디버깅이 까다롭다. 해결은 `nf_conntrack_max` 상향, 짧은 연결의 timeout 튜닝(`nf_conntrack_tcp_timeout_*`), 또는 NAT가 불필요한 경로에서 conntrack을 우회(`NOTRACK`)하는 것이다.

## 7. 패킷 경로 — 컨테이너에서 인터넷까지 종합

컨테이너에서 외부 서버로 가는 패킷의 전체 여정을 정리하면 다음과 같다.

| 단계 | 위치 | 동작 |
|---|---|---|
| 1 | 컨테이너 namespace | veth로 송출, default route → bridge |
| 2 | bridge(br0) | L2 포워딩, 호스트 라우팅 스택으로 |
| 3 | 호스트 PREROUTING | conntrack 항목 생성(NEW) |
| 4 | 호스트 라우팅 결정 | ip_forward, 외부 인터페이스 선택 |
| 5 | 호스트 POSTROUTING | MASQUERADE로 SNAT, conntrack에 변환 기록 |
| 6 | 외부 NIC | 호스트 IP로 송출 |
| 7 | 응답 수신 | conntrack 조회 → de-SNAT → bridge → 컨테이너 |

이 그림을 머리에 넣으면 "컨테이너가 외부는 되는데 컨테이너끼리 안 된다"(bridge/방화벽 문제), "포트 매핑이 안 먹는다"(DNAT/OUTPUT 체인 누락), "간헐적 연결 끊김"(conntrack full) 같은 증상을 계층별로 분리해 진단할 수 있다.

## 8. 정리 — CNI와의 연결

쿠버네티스의 CNI 플러그인(Flannel, Calico, Cilium)도 결국 이 원시 요소들의 조합이다. 각 Pod에 network namespace를 만들고 veth를 꽂는 것은 동일하며, 노드 간 통신을 위해 VXLAN 오버레이(Flannel), BGP 라우팅(Calico), eBPF 데이터패스(Cilium)를 더한다. Cilium은 특히 iptables/conntrack 대신 eBPF로 NAT·로드밸런싱을 처리해, 위에서 본 conntrack 테이블 포화 문제와 긴 iptables 체인 선형 탐색 비용을 줄인다. 즉 오늘 손으로 만든 `ip netns + veth + bridge + iptables`가 모든 컨테이너 오케스트레이션 네트워킹의 공통 기반이다.

```bash
# 실습 정리
sudo ip netns del ns1; sudo ip netns del ns2
sudo ip link del br0
sudo iptables -t nat -F
```

## 9. 네트워크 드라이버 모드 비교 — bridge / host / none / macvlan

Docker가 제공하는 네트워크 모드는 위에서 만든 원시 요소들의 조합 방식이다. 각 모드의 동작과 트레이드오프를 이해하면 상황에 맞는 선택이 가능하다.

| 모드 | 동작 | 격리 | 성능 | 용도 |
|---|---|---|---|---|
| bridge(기본) | veth + docker0 + NAT | 높음 | NAT 오버헤드 | 일반 컨테이너 |
| host | 호스트 namespace 공유 | 없음 | 최고(NAT 없음) | 고성능 네트워킹 |
| none | lo만, 외부 단절 | 최고 | - | 보안/배치 |
| macvlan | 물리 NIC에 가상 MAC | 높음 | 높음 | L2 직접 노출 |

`host` 모드는 컨테이너가 호스트의 network namespace를 그대로 쓴다. veth·bridge·NAT가 없어 가장 빠르지만 포트 충돌과 격리 상실이라는 대가가 있다. 지연에 민감한 네트워크 집약 워크로드(고성능 프록시 등)에서 고려한다. `macvlan`은 컨테이너에 고유 MAC을 부여해 물리 네트워크에 직접 노출, NAT 없이 L2 통신이 필요한 레거시 통합에 쓰인다. 단, 많은 클라우드/스위치가 한 포트의 다중 MAC을 promiscuous 모드 제한으로 막아 환경 의존성이 크다.

## 10. 진단 도구와 트러블슈팅 워크플로

컨테이너 네트워킹 문제는 계층별로 좁혀가며 진단한다. 핵심 도구는 `ip`, `iptables`, `conntrack`, `tcpdump`, `nsenter`다.

```bash
# 컨테이너의 network namespace 안으로 들어가 진단 (PID 기반)
PID=$(docker inspect -f '{{.State.Pid}}' my-container)
sudo nsenter -t $PID -n ip addr            # 컨테이너 인터페이스/IP
sudo nsenter -t $PID -n ip route           # 라우팅 테이블
sudo nsenter -t $PID -n ss -tlnp           # 리스닝 소켓

# 특정 namespace에서 패킷 캐프처 — DNAT 동작 확인
sudo nsenter -t $PID -n tcpdump -i eth0 -n port 80

# NAT 규칙이 의도대로인지
sudo iptables -t nat -L DOCKER -n -v --line-numbers
```

증상별 진단 순서: "컨테이너끼리 통신 안 됨" → 같은 bridge에 있는지(`ip link show master docker0`), bridge의 ICC 설정과 방화벽 FORWARD 체인 확인. "외부로 못 나감" → `ip_forward` 활성화 여부, POSTROUTING MASQUERADE 규칙 존재 확인. "포트 매핑 안 됨" → PREROUTING/OUTPUT DNAT 규칙과 컨테이너 내부 프로세스가 `0.0.0.0`(localhost가 아니라)에 바인딩됐는지 확인 — 컨테이너 안에서 `127.0.0.1`에만 리스닝하면 DNAT된 패킷이 닿지 못한다. "간헐적 연결 실패" → conntrack 테이블 포화(`dmesg | grep conntrack`)와 `nf_conntrack_count` 대 `_max` 비율 확인.

이 워크플로의 핵심은 추측하지 않고 각 계층(namespace 인터페이스 → 라우팅 → NAT 규칙 → conntrack)을 순서대로 검증하는 것이다. 컨테이너 오케스트레이션이 추가하는 오버레이(VXLAN, IPIP)도 결국 이 기반 위에 한 겹 더 캐프슐화를 얹은 것이므로, 기반 진단법이 그대로 적용된다.

## 참고

- man 8 ip-netns, man 8 ip-link, man 8 iptables
- Linux Kernel Networking documentation — netfilter / conntrack
- "Container Networking: From Docker to Kubernetes" (Michael Hausenblas, O'Reilly)
- Cilium Docs — "eBPF and the kernel datapath" (docs.cilium.io)
