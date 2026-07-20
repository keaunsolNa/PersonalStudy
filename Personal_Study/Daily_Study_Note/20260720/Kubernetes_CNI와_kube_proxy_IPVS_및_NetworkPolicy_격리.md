Notion 원본: https://www.notion.so/3a35a06fd6d3810a9976eb9a524c9fbb

# Kubernetes CNI와 kube-proxy IPVS 및 NetworkPolicy 격리

> 2026-07-20 신규 주제 · 확장 대상: Docker&CI / Kubernetes

## 학습 목표

- Kubernetes 네트워크 모델의 세 가지 기본 규약과 Pod-to-Pod 통신 전제를 파악한다
- CNI 플러그인이 Pod 네트워크 인터페이스를 구성하는 순서를 추적한다
- kube-proxy 의 iptables 모드와 IPVS 모드의 성능·확장성 차이를 구분한다
- NetworkPolicy 로 트래픽을 화이트리스트 방식으로 격리하는 규칙을 작성한다

## 1. Kubernetes 네트워크 모델의 규약

Kubernetes 는 네트워크 구현을 강제하지 않는 대신 세 가지 규약을 요구한다. 첫째, 모든 Pod 는 NAT 없이 서로 통신할 수 있어야 한다. 둘째, 노드의 에이전트(kubelet 등)는 그 노드의 모든 Pod 와 통신할 수 있어야 한다. 셋째, Pod 가 보는 자신의 IP 와 남이 보는 그 Pod 의 IP 가 같아야 한다. 이 "평평한(flat) 네트워크" 모델 덕분에 애플리케이션은 포트 매핑이나 NAT 를 신경 쓰지 않고 Pod IP 로 직접 통신한다. 이 규약을 실제로 구현하는 것이 CNI(Container Network Interface) 플러그인이다.

Docker 의 기본 브리지 네트워크가 포트 포워딩과 NAT 에 의존하는 것과 대조적이다. Kubernetes 는 컨테이너 오케스트레이션 규모에서 NAT 의 복잡성과 포트 충돌을 피하기 위해 평평한 IP 공간을 택했고, 이 설계 결정이 CNI 생태계 전체의 전제가 된다.

## 2. CNI 플러그인의 동작 순서

Pod 가 스케줄되면 kubelet 은 컨테이너 런타임(containerd 등)을 통해 Pod 의 네트워크 네임스페이스를 만들고, CNI 플러그인을 호출해 네트워크를 설정한다. CNI 규격은 단순하다. 런타임이 `ADD`/`DEL`/`CHECK` 명령과 JSON 설정을 플러그인에 전달하면, 플러그인이 네트워크 인터페이스를 구성하고 결과(할당된 IP 등)를 반환한다.

`ADD` 흐름은 대략 이렇다. 플러그인이 veth 페어를 만들어 한쪽은 Pod 네임스페이스 안(`eth0`), 다른 쪽은 호스트 네임스페이스에 둔다. IPAM(IP Address Management) 모듈이 Pod 에 IP 를 할당하고, Pod 네임스페이스 안에 라우팅과 기본 게이트웨이를 설정한다. 노드 간 통신은 플러그인마다 다른데, 오버레이 방식(VXLAN 캐프슐화)과 라우팅 방식(BGP 로 Pod CIDR 광고)으로 나뉩다.

```jsonc
{
  "cniVersion": "1.0.0",
  "name": "k8s-pod-network",
  "type": "calico",
  "ipam": { "type": "calico-ipam" }
}
```

Calico 는 라우팅 기반(BGP)으로 캐프슐화 오버헤드가 없고, Flannel 의 VXLAN 모드는 오버레이로 네트워크 환경 제약이 적지만 캐프슐화 비용이 있다. Cilium 은 eBPF 로 커널 데이터패스를 직접 프로그래밍해 kube-proxy 자체를 대체하기도 한다. 선택은 성능 요구, 네트워크 환경(BGP 가능 여부), 관측성 요구에 따라 달라진다.

## 3. Service 추상과 kube-proxy 의 역할

Pod 는 언제든 죽고 새로 뜨며 IP 가 바뀜다. 따라서 Pod IP 로 직접 의존하면 안 되고, 안정적인 가상 IP 인 Service(ClusterIP)를 통해 접근한다. Service 로 온 트래픽은 뒤에 있는 여러 Pod(엔드포인트) 중 하나로 로드밸런싱되어야 하는데, 이 매핑을 각 노드에서 구현하는 것이 kube-proxy 다. 중요한 점은 ClusterIP 가 실제 인터페이스에 바인딩된 IP 가 아니라는 것이다. 어느 네트워크 카드에도 존재하지 않는 가상 주소이며, 오직 각 노드의 패킷 처리 규칙(iptables 또는 IPVS)에 의해 실제 Pod IP 로 DNAT 될 뿐이다. 그래서 ClusterIP 는 ping 이 안 되지만 해당 포트로의 연결은 동작하는 현상이 생긴다.

## 4. iptables 모드의 확장성 한계

kube-proxy 의 전통적 모드는 iptables 다. 각 Service 마다 규칙 체인을 만들고, 엔드포인트 선택은 확률 기반 규칙으로 구현한다. 예를 들어 엔드포인트가 3개면 첫 규칙이 1/3 확률로 매칭, 실패하면 다음이 1/2, 마지막이 1.0 식으로 통계적 균등 분배를 한다.

```
-A KUBE-SVC-XXX -m statistic --mode random --probability 0.333 -j KUBE-SEP-A
-A KUBE-SVC-XXX -m statistic --mode random --probability 0.500 -j KUBE-SEP-B
-A KUBE-SVC-XXX -j KUBE-SEP-C
```

문제는 iptables 규칙이 선형 리스트로 순차 평가된다는 것이다. Service 와 엔드포인트가 수천 개로 늘면 규칙 수가 수만~수십만 줄이 되고, 패킷마다 이 리스트를 훑는 비용이 `O(N)` 으로 커진다. 규칙 갱신도 전체 테이블을 원자적으로 교체하는 방식이라, 대규모 클러스터에서 엔드포인트 변경 시 갱신 지연(수 초)이 관측된다. 수천 서비스 규모부터 iptables 모드가 병목이 되는 이유다.

## 5. IPVS 모드: 해시 테이블 기반 로드밸런싱

IPVS(IP Virtual Server)는 리눅스 커널의 L4 로드밸런서로, 원래 LVS 프로젝트의 일부다. kube-proxy 의 IPVS 모드는 Service 매핑을 iptables 선형 규칙 대신 커널 해시 테이블로 구현한다. 조회가 `O(1)` 에 가까워 Service 수가 많아도 데이터패스 성능이 일정하게 유지된다. 또한 라운드로빈(rr), 최소 연결(lc), 목적지 해시(dh) 등 여러 스케줄링 알고리즘을 지원해 iptables 의 단순 확률 분배보다 정교한 로드밸런싱이 가능하다.

```bash
ipvsadm -Ln   # 노드에서 IPVS 규칙과 가상 서버 목록 확인
```

주의할 점은 IPVS 모드도 iptables 를 완전히 버리지 않는다는 것이다. 패킷 필터링, SNAT(masquerade), NodePort 처리 일부는 여전히 iptables 나 ipset 를 병용한다. IPVS 는 로드밸런싱 데이터패스를 해시 테이블로 대체해 확장성을 얻는 것이지, iptables 를 전부 없애는 것은 아니다.

| 항목 | iptables 모드 | IPVS 모드 |
|---|---|---|
| 매칭 자료구조 | 선형 규칙 리스트 | 커널 해시 테이블 |
| 조회 복잡도 | O(N) | O(1) 근사 |
| 대규모 서비스 성능 | 저하 | 안정적 |
| LB 알고리즘 | 확률 분배만 | rr/lc/dh 등 다양 |
| 규칙 갱신 비용 | 전체 교체, 느림 | 증분 갱신, 빠름 |

## 6. eBPF 데이터패스의 등장

Cilium 같은 eBPF 기반 CNI 는 kube-proxy 를 아예 대체한다. Service 매핑을 iptables/IPVS 규칙이 아니라 eBPF 프로그램과 맵으로 구현해, 소켓 레벨이나 XDP(드라이버 초입)에서 DNAT 를 수행한다. 이는 커널 네트워크 스택의 상당 부분을 우회해 지연을 줄이고, 규칙 수와 무관한 상수 시간 조회를 제공한다. trade-off 는 커널 버전 요구(비교적 최신 커널 필요)와 운영 복잡도다. 그럼에도 대규모·고성능 클러스터에서 eBPF 데이터패스 채택이 늘고 있으며, "kube-proxy 없는" 클러스터가 점차 현실적 선택지가 되고 있다.

## 7. NetworkPolicy 로 트래픽 격리

기본적으로 Kubernetes 의 모든 Pod 는 서로 통신할 수 있다(allow-all). 보안 관점에서 이는 위험하므로, NetworkPolicy 로 화이트리스트 방식의 격리를 건다. NetworkPolicy 는 라벨 셀렉터로 대상 Pod 를 지정하고, 허용할 ingress/egress 트래픽을 명시한다. 핵심 규칙은 한 Pod 에 하나라도 NetworkPolicy 가 적용되면, 그 Pod 는 명시적으로 허용된 트래픽만 받는다(암묵적 deny)는 것이다.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-frontend-to-backend
  namespace: prod
spec:
  podSelector:
    matchLabels:
      app: backend
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: frontend
      ports:
        - protocol: TCP
          port: 8080
```

이 정책은 `prod` 네임스페이스의 `app: backend` Pod 에 대해, `app: frontend` Pod 에서 오는 TCP 8080 만 허용하고 나머지 모든 ingress 를 차단한다. 여러 정책이 같은 Pod 에 적용되면 각 정책의 허용 규칙이 합집합(OR)으로 결합된다. 전체를 잠그는 흔한 패턴은 빈 셀렉터로 default-deny 를 깔고, 필요한 흐름만 개별 정책으로 여는 것이다.

## 8. NetworkPolicy 의 실행 주체와 함정

NetworkPolicy 는 API 오브젝트일 뿐, 실제 시행은 CNI 플러그인이 담당한다. 중요한 함정은 **NetworkPolicy 를 지원하지 않는 CNI 를 쓰면 정책이 조용히 무시된다**는 것이다. 정책을 작성하고 `kubectl apply` 가 성공해도, CNI 가 시행하지 않으면 트래픽은 그대로 흐른다. Calico, Cilium 은 지원하지만 일부 단순 CNI 는 지원하지 않으므로, 격리를 보안 통제로 쓰려면 CNI 가 NetworkPolicy 를 실제 시행하는지 반드시 검증해야 한다. 또 egress 정책을 잠김 때 클러스터 DNS(CoreDNS, 53 포트)를 허용하지 않으면 이름 해석이 실패해 모든 외부 통신이 깨진다. 그리고 NetworkPolicy 는 L3/L4(IP/포트) 수준이라 HTTP 메서드나 경로 같은 L7 제어는 불가능하며, L7 격리가 필요하면 서비스 메시(Istio 등)나 Cilium 의 L7 정책을 병용한다. 정리하면 CNI 가 평평한 네트워크를 만들고, kube-proxy(또는 eBPF)가 Service 를 실제 Pod 로 라우팅하며, NetworkPolicy 가 그 위에서 트래픽을 화이트리스트로 격리하는 세 계층이 Kubernetes 네트워킹의 빼대다.

## 참고

- Kubernetes Documentation — Cluster Networking (https://kubernetes.io/docs/concepts/cluster-administration/networking/)
- Kubernetes Documentation — Network Policies
- Kubernetes Documentation — Virtual IPs and Service Proxies (kube-proxy iptables/IPVS)
- CNI Specification — containernetworking/cni (GitHub)
