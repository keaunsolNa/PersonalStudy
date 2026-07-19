Notion 원본: https://www.notion.so/3a25a06fd6d38174bb98ceb6a8ee1de2

# Docker 네트워크 네임스페이스와 브리지 veth 및 컨테이너 통신 격리

> 2026-07-19 신규 주제 · 확장 대상: Docker

## 학습 목표

- 네트워크 네임스페이스가 컨테이너 격리의 기반이 되는 원리를 설명한다.
- veth pair 와 리눅스 브리지로 컨테이너-호스트 연결이 만들어지는 경로를 추적한다.
- bridge/host/none 드라이버의 통신·격리 차이를 iptables 규칙으로 이해한다.
- 포트 포워딩과 컨테이너 간 DNS 해석이 어디서 처리되는지 안다.

## 1. 커널 기능의 조합

컨테이너 네트워킹은 리눅스 커널의 네트워크 네임스페이스를 조립한 것이다. 네트워크 스택 전체(인터페이스, 라우팅, iptables, 소켓)를 독립 사본으로 격리한다.

```bash
ip netns add ns1
ip netns exec ns1 ip addr        # lo 만 보임
```

Docker 없이 `ip netns` 로 같은 것을 만들 수 있다. Docker 는 이를 자동화하고 IP·DNS·포트 매핑을 얹은 계층이다.

## 2. veth pair

veth 는 항상 쌍으로 생기는 가상 이더넷으로, 한쪽으로 들어간 패킷이 반대쪽으로 나온다. 한 끝은 컨테이너 네임스페이스 안 `eth0`, 다른 끝은 호스트 브리지에 연결된다.

```bash
ip link add veth0 type veth peer name veth1
ip link set veth1 netns ns1
ip link set veth0 master docker0
```

## 3. docker0 브리지

`docker0` 는 소프트웨어 L2 스위치다. 기본 bridge 컨테이너들은 모두 여기 연결돼 서로 직접 통신한다. 컨테이너 간 통신은 브리지 안 L2 스위칭으로 끝나고, 외부 통신은 브리지를 통과해 호스트 라우팅·NAT 을 거친다.

## 4. SNAT(MASQUERADE)

컨테이너가 외부로 나갈 때 출발지 IP 를 호스트 IP 로 바꾸는 SNAT 을 건다. NAT 이므로 밖에서 컨테이너로 직접 들어올 수 없다.

```bash
iptables -t nat -L POSTROUTING -n
# MASQUERADE  all -- 172.17.0.0/16  !172.17.0.0/16
```

## 5. 포트 포워딩 — DNAT

`docker run -p 8080:80` 은 DNAT 으로 구현된다. 호스트 8080 도착 패킷의 목적지를 컨테이너 IP:80 으로 재작성한다. 응답은 conntrack 이 역변환하며, 대부분 트래픽은 커널 iptables 가 처리하고 docker-proxy 는 폴백이다.

## 6. 드라이버 — bridge/host/none

| 드라이버 | 격리 | 성능 | 용도 |
|---|---|---|---|
| bridge(기본) | 높음(NAT) | NAT 오버헤드 | 일반 |
| host | 없음 | 네이티브 | 고성능 |
| none | 완전 | — | 네트워크 불필요 |

host 는 호스트 스택을 공유해 veth·브리지·NAT 이 없어 오버헤드가 없지만 격리가 사라진다.

```bash
docker run --network host nginx
docker run --network none alpine
```

## 7. 사용자 정의 네트워크와 내장 DNS

기본 bridge 는 이름 해석이 안 된다. 사용자 정의 네트워크는 내장 DNS(127.0.0.11)로 컨테이너 이름을 자동 해석한다.

```bash
docker network create appnet
docker run -d --name db --network appnet postgres
docker exec api ping db
```

서로 다른 사용자 정의 네트워크는 기본 격리돼, DB 를 백엔드망에만 두면 프론트엔드 접근 경로가 없어진다.

## 8. 디버깅과 실무 정리

컨테이너 내부(ip route, nslookup) → 호스트 iptables·브리지 → 네트워크 격리 순으로 좁힌다.

```bash
docker exec api ip route
docker exec api nslookup db
iptables -t nat -L DOCKER -n
```

프로덕션은 사용자 정의 네트워크로 이름 해석·망 분리를 얻고, DB·내부 서비스는 백엔드망에 두며, host 네트워크는 성능이 진짜 병목일 때만 쓴다.

## 참고

- Docker 공식 문서 — "Networking overview", "Bridge/Host network driver"
- Linux Kernel Documentation — "Network namespaces", veth(4)
- Docker 문서 — 내장 DNS(127.0.0.11)
- iptables/netfilter — nat 테이블, conntrack
- 『Container Networking』(Michael Hausenblas)
