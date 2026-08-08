Notion 원본: https://www.notion.so/3b65a06fd6d3819084dfce45b17b801c

# Kubernetes 네트워킹 CNI와 kube-proxy 및 eBPF 데이터플레인

> 2026-08-08 신규 주제 · 확장 대상: Kubernetes (HPA·StatefulSet·Operator·어드미션 웹훅 기학습)

## 학습 목표

- 쿠버네티스 네트워크 모델 3원칙과 CNI 플러그인 호출 흐름(ADD/DEL, veth 배선)을 추적한다
- Flannel VXLAN과 Calico BGP의 패킷 경로를 비교하고 오버레이 측슷화 비용을 계산한다
- kube-proxy iptables 모드와 IPVS 모드의 규칙 구조·성능 특성·conntrack 의존성을 분석한다
- Cilium eBPF 데이터플레인의 kube-proxy 대체 원리와 NetworkPolicy 구현 차이를 검증한다

## 1. 쿠버네티스 네트워크 모델 — 세 가지 불변 조건

쿠버네티스는 네트워크 구현을 직접 제공하지 않는 대신 만족해야 할 조건만 정의한다. (1) 모든 Pod는 클러스터 고유 IP를 갖는다. (2) 모든 Pod는 NAT 없이 서로의 IP로 통신할 수 있다. (3) 노드의 에이전트(kubelet 등)는 그 노드의 Pod와 통신할 수 있다. 이 "flat network" 모델 덕분에 애플리케이션은 포트 매핑·NAT 지옥(Docker 단일 호스트 모델의 유산) 없이 VM 시절과 같은 관념으로 통신한다. 이 조건을 어떻게 충족할지는 전적으로 CNI 플러그인의 몸이며, 같은 모델 위에서 오버레이(VXLAN)·라우팅(BGP)·클라우드 네이티브(VPC 라우팅) 구현이 갈린다.

Pod 내부는 더 단순한 사실 하나로 정리된다. **Pod = 네트워크 네임스페이스 1개.** pause 컨테이너가 네임스페이스를 붙잡고, 앱 컨테이너들은 그 네임스페이스에 조인하므로 컨테이너 간에는 localhost 통신이 된다. 사이드카 패턴(Envoy 프록시 등)이 가능한 기반이 바로 이 공유 네임스페이스다.

## 2. CNI 호출 흐름 — kubelet에서 veth까지

CNI(Container Network Interface)는 놀랄 만큼 단순한 규약이다. 실행 파일 하나가 표준 입력으로 JSON 설정을 받고, 환경 변수(`CNI_COMMAND=ADD|DEL|CHECK`, `CNI_NETNS`, `CNI_IFNAME`)로 맥락을 받아, 네임스페이스에 인터페이스를 만들고 결과 JSON을 표준 출력으로 돌려준다. Pod 생성 시 흐름은 다음과 같다.

```
kubelet → CRI(containerd) → pause 컨테이너 생성(netns 확보)
	→ containerd가 /etc/cni/net.d/의 설정으로 /opt/cni/bin/<plugin> 실행 (ADD)
		→ 플러그인: veth 쌍 생성 — 한쪽은 Pod netns에 eth0으로,
		  다른 쪽은 호스트 브리지/라우팅에 연결
		→ IPAM 플러그인 위임(host-local: 노드 podCIDR에서 IP 할당)
		→ 라우팅/브리지 FDB 갱신 → 결과(IP·게이트웨이) 반환
```

노드별 Pod CIDR은 kube-controller-manager의 `--cluster-cidr`에서 노드마다 잘라 배정된다(예: 클러스터 10.244.0.0/16 → 노드A 10.244.1.0/24). 트러블슈팅 시 이 구조를 알면 진단이 빨라진다: Pod가 `ContainerCreating`에서 멈추고 이벤트에 `failed to set up sandbox`가 보이면 CNI 설정 디렉터리·바이너리·IPAM 고갈(`no IP addresses available in range`)을 순서대로 확인하는 식이다.

## 3. 데이터플레인 비교 — Flannel VXLAN vs Calico BGP

노드 간 Pod 통신 구현의 양대 접근을 패킷 경로로 비교한다. **Flannel(VXLAN 모드)** 은 오버레이다. 노드마다 `flannel.1`이라는 VTEP(VXLAN Tunnel Endpoint) 장치를 만들고, 다른 노드 Pod로 가는 패킷을 UDP 8472로 측슷화한다. 원본 L2 프레임 앞에 외부 IP/UDP/VXLAN 헤더 50바이트가 붙으므로 MTU가 1500 → 1450으로 줄고, 측슷화·역측슷화 CPU 비용이 노드당 추가된다. 대신 하부 네트워크에 요구하는 것이 "노드 간 UDP 통신 가능"뿐이라 어떤 환경에서든 동작한다.

**Calico(BGP 모드)** 는 측슷화가 없다. 각 노드의 Felix가 라우팅 테이블과 iptables를 프로그래밍하고, BIRD(BGP 데몬)가 "10.244.1.0/24는 노드A로"라는 경로를 피어 노드에 광고한다. Pod 패킷은 순수 IP 라우팅으로 흐르므로 MTU 손실도 측슷화 비용도 없다. 제약은 하부 네트워크가 Pod IP 라우팅을 허용해야 한다는 것 — 같은 L2 세그먼트이거나, ToR 스위치와 BGP 피어링이 가능해야 한다. 클라우드 VPC처럼 임의 IP 포워딩이 막힌 환경에서는 IPIP/VXLAN 측슷화 모드로 후퇴하거나 CrossSubnet 모드(같은 서브넷은 라우팅, 다른 서브넷만 측슷화)로 절충한다.

| 항목 | Flannel VXLAN | Calico BGP |
|---|---|---|
| 패킷 경로 | 측슷화 오버레이 (UDP 8472) | 네이티브 IP 라우팅 |
| MTU | -50B (1450) | 손실 없음 |
| 하부 네트워크 요구 | UDP 도달성만 | L2 인접 또는 BGP 피어링 |
| NetworkPolicy | 미지원(단독) | 지원 (iptables/eBPF) |
| 진단 난도 | 측슷 내부 확인 필요(tcpdump -i flannel.1) | 표준 라우팅 도구로 진단 |

처리량 벤치마크에서 BGP 모드는 호스트 네트워크 대비 근접한 수치를 내는 반면 VXLAN은 수~십수 % 손실이 관측되는 것이 일반적 결과다(NIC의 VXLAN 오프로드 지원 여부에 크게 좌우).

## 4. Service와 kube-proxy iptables 모드 — 체인 구조와 비용

Service의 ClusterIP는 어떤 인터페이스에도 붙어 있지 않은 **가상 IP**다. 실체는 각 노드에서 kube-proxy가 프로그래밍한 DNAT 규칙이다. iptables 모드의 체인 구조를 따라가면: `PREROUTING/OUTPUT → KUBE-SERVICES → KUBE-SVC-<hash>`(서비스별 체인)에서 `statistic mode random --probability` 규칙으로 엔드포인트를 확률 선택하고 → `KUBE-SEP-<hash>`(엔드포인트별 체인)에서 Pod IP:Port로 DNAT한다. 확률값은 엔드포인트 n개면 1/n, 1/(n-1), ... 순으로 걸려 균등 분배를 만든다.

이 구조의 비용은 두 가지다. 첫째, **규칙 수가 서비스×엔드포인트에 비례**해 수천 서비스 규모에서 규칙 수만~수십만 개가 되고, iptables 갱신은 iptables-restore로 전체 테이블을 다시 쓰는 방식이라 갱신 지연이 분 단위까지 늘어난 사례가 보고됐다. 둘째, 매칭이 선형 탐색이라 첫 패킷의 처리 지연이 서비스 수에 비례해 증가한다(연결 수립 후에는 conntrack이 흡수). **IPVS 모드**는 이를 해시 테이블로 바꾼 것이다: 서비스마다 IPVS 가상 서버를 만들고 엔드포인트를 리얼 서버로 등록해, 서비스 수와 무관한 O(1) 조회와 rr/lc/sh(세션 어피니티) 등 스케줄링 알고리즘 선택을 얻는다. 대규모 클러스터(서비스 수천 이상)에서는 IPVS가 사실상 필수 선택이었고, 이 문제의식이 §6 eBPF 대체로 이어진다.

conntrack 의존성도 기억해야 한다. DNAT된 연결의 왕복 변환은 conntrack 테이블이 담당하므로, 테이블 고갈(`nf_conntrack: table full, dropping packet`)은 클러스터 전역 통신 장애로 나타난다. 고연결 워크로드 노드는 `net.netfilter.nf_conntrack_max`와 해시 크기를 상향하는 것이 표준 운영 항목이다.

## 5. 트래픽 정책과 헤어핀 — 흔한 운영 함정

`externalTrafficPolicy: Cluster`(기본)는 NodePort로 들어온 트래픽을 다른 노드의 Pod로도 보내는데, 이때 응답이 원래 노드로 돌아오도록 **SNAT가 걸려 클라이언트 소스 IP가 사라진다**. `Local`로 바꾸면 자기 노드의 Pod로만 보내 소스 IP가 보존되지만, 해당 노드에 Pod가 없으면 드롭되므로 로드밸런서 헬스체크(`healthCheckNodePort`)와 짝지어야 한다. 소스 IP 기반 제어(화이트리스트, 지역 라우팅)를 하는 서비스에서 반복적으로 등장하는 함정이다.

또 하나는 **헤어핀 NAT**: Pod가 자신이 속한 Service의 ClusterIP를 호출해 DNAT 결과 자기 자신에게 돌아오는 경우, 소스·목적지가 같은 인터페이스가 되어 기본 브리지 설정에서는 패킷이 버려진다. kubelet의 `hairpinMode` 또는 CNI의 promiscuous 설정으로 해결되는데, "자기 서비스 호출만 간헐 실패"라는 미스터리 증상의 단골 원인이다. DNS 쪽에서는 `ndots:5` 기본값이 만드는 불필요한 검색 도메인 순회(외부 도메인 조회 1건당 최대 5회의 NXDOMAIN 왕복)가 대표 지연 원인으로, FQDN 끝에 점을 붙이거나(`api.example.com.`) ndots를 낮추는 것이 처방이다.

## 6. Cilium eBPF — kube-proxy를 대체하는 데이터플레인

eBPF는 커널에 검증기를 통과한 바이트코드를 로드해 커널 이벤트 지점에서 실행하는 기술이다. Cilium은 이를 네트워킹 데이터플레인으로 밀어붙여 tc ingress/egress 훅과 소켓 훅에 프로그램을 걸고, Service 변환·정책 판정·관측을 커널 안에서 끝낸다. **kube-proxy 완전 대체(kubeProxyReplacement)** 가 대표 기능인데, 구현이 흥미롭다. ClusterIP 접속은 **소켓 레벨(connect() 훅)에서 목적지를 백엔드 Pod IP로 바꿔치기**하므로, 패킷이 네트워크 스택을 타기 전에 변환이 끝나 per-packet NAT 비용 자체가 사라진다. iptables 선형 탐색 대신 eBPF 해시 맵 조회라 서비스 수와 무관하게 상수 시간이며, conntrack 의존도 크게 줄어든다.

NetworkPolicy 구현도 차이가 있다. iptables 기반(Calico 기본)이 IP 집합 매칭이라면, Cilium은 **identity 기반** — 레이블 조합마다 숫자 identity를 부여하고 패킷에 identity를 실어 정책을 판정한다. Pod가 재생성되어 IP가 바뀌어도 identity가 같으면 정책 갱신이 불필요해, 대규모 churn 환경에서 규칙 갱신 폭풍이 사라진다. L7 정책(HTTP 경로·메서드 단위 허용, DNS 이름 기반 egress `toFQDNs`)은 Envoy 연동으로 제공되어, "결제 서비스는 api.stripe.com으로만 egress 허용" 같은 규칙이 표준 리소스로 표현된다. 관측 도구 Hubble은 eBPF가 이미 보고 있는 플로우를 그대로 노출해 "누가 누구에게 어떤 verdict(FORWARDED/DROPPED)로 통신했나"를 실시간 조회하게 해 준다 — NetworkPolicy 디버깅에서 tcpdump 추측 대신 드롭 사유를 직접 보는 경험 차이가 크다.

## 7. NetworkPolicy 설계 — 기본 거부와 단계적 허용

정책 설계의 실무 원칙을 코드로 정리한다. NetworkPolicy는 **네임스페이스 단위 additive 허용 모델**이다: 어떤 정책에도 선택되지 않은 Pod는 전부 허용이고, 하나라도 선택되면 명시 허용 외 전부 거부다. 따라서 출발점은 네임스페이스별 기본 거부다.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: payment
spec:
  podSelector: {}          # 네임스페이스 내 전체 Pod
  policyTypes: [Ingress, Egress]
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-api-to-db
  namespace: payment
spec:
  podSelector:
    matchLabels: { app: payment-db }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels: { app: payment-api }
      ports:
        - protocol: TCP
          port: 5432
```

기본 거부를 깔 때 반드시 함께 열어야 하는 것이 **DNS egress(UDP/TCP 53, kube-dns 대상)** 다 — 이를 빼먹으면 "모든 통신이 이름 해석부터 실패"하는 광범위 장애처럼 보인다. 롤아웃 절차는 관측 → 정책 초안 → 감사 모드 검증 → 강제 순서가 안전하다. Cilium이라면 Hubble로 실제 플로우를 수집해 초안을 만들고, `--policy-audit-mode`로 드롭될 트래픽을 로그로만 남겨 확인한 뒤 강제로 전환한다.

## 8. 진단 워크플로우 — 계층을 내려가며 좁히기

"Pod A에서 Service B가 안 된다"는 신고의 표준 진단 순서를 정리한다. (1) **DNS**: `kubectl exec`로 `nslookup b.ns.svc.cluster.local` — 실패하면 CoreDNS·정책의 53 포트부터. (2) **Endpoints**: `kubectl get endpointslices -l kubernetes.io/service-name=b` — 비어 있으면 네트워크가 아니라 셀렉터 불일치·readiness 실패다(경험상 "네트워크 문제"의 절반이 여기서 끝난다). (3) **ClusterIP 우회**: Pod IP:Port 직접 호출 — 되면 Service 변환 계층(kube-proxy 규칙·IPVS 테이블), 안 되면 CNI/정책 계층으로 분기. (4) **정책**: Cilium이면 `hubble observe --to-pod b --verdict DROPPED`, iptables 계열이면 정책 임시 제거로 이분 탐색. (5) **패킷 캐처**: 마지막 수단으로 노드에서 `tcpdump -i any host <podIP>` — VXLAN이면 측슷 해제 전후(`-i flannel.1`) 양쪽을 본다. 이 순서의 요점은 위(이름 해석)에서 아래(패킷)로 내려가며 각 계층을 독립 검증해, 추측 없이 문제 계층을 이분 탐색하는 것이다.

## 9. 선택 기준 정리 — 어떤 CNI·모드를 고를 것인가

의사결정 규칙으로 마무리한다. **관리형 클라우드(EKS/GKE/AKS)** 는 기본 제공 CNI(VPC 네이티브)가 운영 부담이 가장 낮고 LB·보안그룹 통합이 매끈럽으므로, L7 정책·고급 관측 요구가 생기기 전까지는 기본값을 유지하는 것이 합리적이다. **자체 구축 소규모(수십 노드)** 는 Flannel의 단순함도 여전히 유효하나 NetworkPolicy 미지원이 결격이면 Calico가 무난한 표준이다. **대규모·고성능·강한 보안 요구**(수백 노드 이상, 서비스 수천, L7 정책·FQDN egress·플로우 감사)에서는 Cilium + kube-proxy 대체가 현재의 중심 선택지이며, CNCF 졸업과 관리형 서비스 채택(GKE Dataplane V2 등)으로 운영 리스크도 낮아졌다. 단 eBPF 스택은 커널 버전 의존과 진단 도구 학습(기존 iptables 지식이 통하지 않음)이라는 전환 비용이 있으므로, 도입은 신규 클러스터부터 적용해 운영 경험을 쌓는 경로가 안전하다. 어떤 선택이든 변하지 않는 기본기는 §1의 모델 이해와 §8의 계층적 진단 습관이다 — CNI가 바뀌어도 이 두 가지는 그대로 유효하다.

## 참고

- Kubernetes 공식 문서 — Cluster Networking, Services, Network Policies
- CNI Specification (github.com/containernetworking/cni/blob/main/SPEC.md)
- Cilium Documentation — kube-proxy replacement, Identity-based security (docs.cilium.io)
- Tigera — Calico Networking 문서 (BGP·IPIP·CrossSubnet 모드)
- Liz Rice, 『Learning eBPF』 (O'Reilly) — eBPF 네트워킹 장
