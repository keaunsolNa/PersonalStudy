Notion 원본: https://app.notion.com/p/3985a06fd6d3812d9da4d0a0701ce8bc

# Kubernetes 네트워킹 CNI와 kube-proxy iptables/IPVS 및 Service 추상화

> 2026-07-09 신규 주제 · 확장 대상: DevOps

## 학습 목표

- Kubernetes 네트워킹 모델의 요구사항과 Pod 네트워크 구성을 설명한다
- CNI 플러그인이 Pod 생성 시 네트워크 인터페이스를 붙이는 흐름을 추적한다
- Service(ClusterIP/NodePort/LoadBalancer) 추상화가 kube-proxy 로 구현되는 방식을 구분한다
- iptables 모드와 IPVS 모드의 성능·확장성 trade-off 를 판별한다

## 1. Kubernetes 네트워킹 모델

모든 Pod 는 NAT 없이 서로 통신, 노드 에이전트는 그 노드의 모든 Pod 와 통신, Pod 가 보는 자기 IP 와 남이 보는 IP 가 같아야 한다. 이 flat network 덕분에 앱은 Pod IP 로 직접 통신한다. Pod IP 는 휠발성이라 Service 추상화가 필요하며, 각 Pod 는 pause 컨테이너가 잡은 네임스페이스를 공유해 localhost 로 통신한다.

## 2. CNI 플러그인과 Pod 네트워크 연결

Pod 가 스케줄되면 kubelet 이 CNI 플러그인을 ADD 호출하고 veth pair 를 만들어 Pod 네임스페이스와 노드 라우팅을 연결하고 IPAM 으로 IP 를 할당한다. 오버레이(Flannel VXLAN, Calico IPIP)는 캐프슐화 터널로 범용이지만 오버헤드가 있고, 라우팅(Calico BGP, Cilium native)은 캐프슐화 없이 고성능이다. Cilium 은 eBPF 로 iptables 를 대체해 데이터경로를 커널에서 처리한다.

| 구분 | 예시 | 캐프슐화 | 특징 |
|---|---|---|---|
| 오버레이 | Flannel(VXLAN), Calico(IPIP) | 있음 | 범용, 오버헤드 |
| 라우팅 | Calico(BGP), Cilium(native) | 없음 | 고성능, 하부 의존 |
| eBPF | Cilium | 선택 | 커널 데이터패스 |

## 3. Service 추상화

Service 는 라벨 셀렉터로 선택된 Pod 집합에 안정적 ClusterIP 와 DNS 이름을 부여한다. EndpointSlice 가 실제 Pod IP:port 목록을 추적한다. ClusterIP는 내부 전용, NodePort는 모든 노드의 포트(30000-32767)를 열고, LoadBalancer는 클라우드 LB 를 프로비저닝한다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: order-api
spec:
  type: ClusterIP
  selector: { app: order-api }
  ports:
    - port: 80
      targetPort: 8080
```

## 4. kube-proxy 와 데이터 경로

ClusterIP 는 실제 장치에 없는 가상 IP 다. 각 노드의 kube-proxy 가 Service·EndpointSlice 변경을 감시해 커널 규칙을 프로그래밍하고, ClusterIP 패킷을 실제 Pod IP 로 DNAT 한다. 부하분산은 각 노드 커널에서 분산 수행된다. CoreDNS 는 이름을 ClusterIP 로 해석한다.

## 5. iptables 모드 vs IPVS 모드

iptables 모드는 규칙을 선형 리스트로 평가해 서비스·엔드포인트가 수천 개면 O(n) 으로 느려진다. IPVS 모드는 커널 L4 LB 를 써 해시 테이블 기반 O(1) 조회와 rr/lc/sh 스케줄링을 제공한다.

| 항목 | iptables | IPVS |
|---|---|---|
| 자료구조 | 규칙 체인(선형) | 해시 테이블 |
| 조회 | O(n) | O(1) 근사 |
| 부하분산 | 무작위 | rr/lc/sh 선택 |
| 확장성 | 제한적 | 우수 |

## 6. 트래픽 정책과 세션 어피니티

externalTrafficPolicy: Local 은 수신 노드의 로컬 Pod 로만 보내 추가 홈을 없애고 소스 IP 를 보존하지만 분산이 불균형해질 수 있다. sessionAffinity: ClientIP 는 같은 클라이언트를 같은 Pod 로 고정한다.

```yaml
spec:
  type: LoadBalancer
  externalTrafficPolicy: Local
  sessionAffinity: ClientIP
```

## 7. 검증 예시

```bash
kubectl run netcheck --image=nicolaka/netshoot -it --rm --restart=Never -- \
  sh -c 'nslookup order-api && curl -s -o /dev/null -w "%{http_code}\n" http://order-api'
kubectl get endpointslices -l kubernetes.io/service-name=order-api -o wide
```

IPVS 전환 후 ipvsadm -Ln 으로 가상서버-실제서버 매핑을 EndpointSlice 와 대조하고, NetworkPolicy 차단 경로는 음성 테스트로 회귀를 막는다.

## 참고

- Kubernetes Documentation — Cluster Networking / Service
- Kubernetes Documentation — Virtual IPs and Service Proxies (iptables/IPVS)
- CNI Specification (github.com/containernetworking/cni)
- Cilium / Calico 공식 문서
