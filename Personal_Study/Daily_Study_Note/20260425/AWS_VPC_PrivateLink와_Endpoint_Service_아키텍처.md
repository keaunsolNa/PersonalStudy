Notion 원본: https://www.notion.so/34d5a06fd6d381e5b6b5f4411759eca4

# AWS VPC PrivateLink와 Endpoint Service 아키텍처

> 2026-04-25 신규 주제 · 확장 대상: AWS (VPC/IAM 학습됨), DevOps (네트워크 학습됨)

## 학습 목표

- VPC Endpoint(Gateway / Interface)의 라우팅 차이를 패킷 흐름 수준에서 설명한다
- PrivateLink가 cross-account / cross-VPC 연결에서 NAT/peering보다 우월한 시나리오를 식별한다
- Endpoint Service Provider 측 NLB와 Consumer 측 Interface Endpoint의 보안 그룹/정책을 작성한다
- Cross-region PrivateLink와 VPC peering, Transit Gateway의 비용/성능 비교를 표로 결정한다

---

## 1. VPC Endpoint의 두 종류

AWS의 VPC Endpoint는 정확히 두 가지로 나뉜다.

| 종류 | 대상 서비스 | 패킷 흐름 |
|---|---|---|
| Gateway Endpoint | S3, DynamoDB | 라우팅 테이블에 prefix list 추가 → pseudo-target |
| Interface Endpoint | 그 외 거의 모든 서비스 + 사용자 정의 PrivateLink | 서브넷에 ENI 생성, AWS PrivateLink 사용 |

Gateway Endpoint는 무료지만 서비스가 제한된다. Interface Endpoint(=PrivateLink)는 ENI 단위 시간당 요금 + 데이터 처리 요금이 발생하는 대신 모든 서비스에 일관된 모델을 제공한다. 2024년 이후 AWS가 권장하는 방향은 PrivateLink다.

## 2. PrivateLink의 패킷 흐름

PrivateLink는 Provider VPC의 NLB(Network Load Balancer) → Endpoint Service → Consumer VPC의 Interface Endpoint(ENI)로 연결된다. Consumer 측에서는 ENI의 사설 IP가 DNS로 노출되고, Consumer는 그 IP로 일반 TCP 연결을 한다.

```
[Consumer App] → [Interface Endpoint ENI 10.0.1.45]
       ↘ 동일 가용영역 매핑 ↙
[Endpoint Service] → [NLB] → [Provider EC2/ECS/Lambda]
```

핵심 보안 특성은 ① 트래픽이 인터넷을 거치지 않는다, ② Source IP는 NLB에서 보면 PrivateLink의 link-local이다, ③ Provider는 Consumer의 VPC ID를 알 수 없다(only AWS account ID and endpoint ID). 이게 cross-account SaaS에서 PrivateLink가 표준이 된 이유다.

## 3. VPC peering / Transit Gateway / PrivateLink 비교

| 기준 | VPC Peering | Transit Gateway | PrivateLink |
|---|---|---|---|
| 토폴로지 | 1:1, full-mesh 어려움 | hub-and-spoke | 서비스 단위, 1:N |
| CIDR 충돌 | 불가 | 불가 | **허용** |
| Transitive 라우팅 | 불가 | 가능 | 해당 없음 |
| 시간당 요금 (한 쪽) | 무료 | $0.05 / attachment | $0.01 / ENI / AZ |
| 데이터 처리 요금 | 동일 region 무료 | $0.02 / GB | $0.01 / GB |
| 보안 격리 | 양쪽이 전체 라우팅 알 수 있음 | TGW route table | 서비스 단위 격리 |

PrivateLink가 우월한 핵심 시나리오는 CIDR 충돌. Consumer A, Consumer B가 모두 `10.0.0.0/16`을 쓰고 있어도 PrivateLink로는 같은 Provider 서비스에 동시에 붙을 수 있다. peering이나 TGW로는 불가능.

## 4. Endpoint Service 만들기 (Provider 측)

```hcl
# Terraform: Provider VPC 측
resource "aws_lb" "service_nlb" {
  name               = "my-service-nlb"
  load_balancer_type = "network"
  internal           = true
  subnets            = aws_subnet.private[*].id
  enable_cross_zone_load_balancing = true
}

resource "aws_vpc_endpoint_service" "main" {
  acceptance_required        = true
  network_load_balancer_arns = [aws_lb.service_nlb.arn]
  allowed_principals = [
    "arn:aws:iam::222233334444:root", # Consumer 계정
    "arn:aws:iam::555566667777:root",
  ]
  private_dns_name = "service.example.com" # 선택, 별도 DNS 검증 필요
}
```

`acceptance_required=true`이면 Consumer가 endpoint를 만들 때 Provider가 수동 승인해야 한다. 화이트리스트 방식으로 운영하려면 true 권장. SaaS 자동화에서는 false + `allowed_principals`로 통제하는 패턴.

## 5. Consumer 측 Interface Endpoint

```hcl
# Consumer VPC 측
resource "aws_vpc_endpoint" "to_provider" {
  vpc_id            = aws_vpc.consumer.id
  service_name      = "com.amazonaws.vpce.ap-northeast-2.vpce-svc-0123abcd"
  vpc_endpoint_type = "Interface"
  subnet_ids        = aws_subnet.consumer_private[*].id
  security_group_ids = [aws_security_group.endpoint.id]
  private_dns_enabled = false # custom 도메인이면 false
}

resource "aws_security_group" "endpoint" {
  vpc_id = aws_vpc.consumer.id
  ingress {
    from_port = 443
    to_port   = 443
    protocol  = "tcp"
    cidr_blocks = ["10.0.0.0/16"] # consumer 내부에서만 호출
  }
}
```

Endpoint의 보안 그룹은 "ENI에 접근 가능한 IP"를 통제한다. NLB 측 보안 그룹과는 별개. Provider 측 EC2 인스턴스의 보안 그룹은 NLB 자체가 SG를 가지지 않으므로 PrivateLink CIDR(`170.x.x.x` 영역의 link-local)이 아니라 **Consumer가 NLB에 던진 source IP**(=Endpoint ENI의 사설 IP)를 통과시키는 규칙이 필요하다.

## 6. Cross-Region PrivateLink

2022년부터 AWS는 **Cross-Region PrivateLink**를 지원한다. Consumer와 Provider가 다른 region에 있어도 PrivateLink connection을 만들 수 있다. 내부적으로는 AWS backbone을 따라 패킷이 이동하지만 사용자 입장에서는 동일 인터페이스다.

장점: VPC peering이 불가능하던 region 간 격리된 서비스 호출이 가능해졌다. 단점: latency가 region 간 RTT만큼 추가되며(예: 서울-도쿄 ~30ms), 비용이 region 데이터 전송 요금과 합산된다. ap-northeast-2 → us-east-1 트래픽은 데이터 전송 + PrivateLink 처리 요금이 둘 다 부과되어, 단순 peering(무료) 또는 TGW inter-region peering($0.02/GB)보다 비싸질 수 있다.

선택 가이드: 동일 region이면 PrivateLink가 거의 항상 우월. cross-region이면 TGW inter-region peering(전 라우팅) 또는 PrivateLink(서비스 단위) 중 보안 모델로 선택.

## 7. Private DNS와 Split-Horizon

Interface Endpoint를 만들 때 `private_dns_enabled=true`로 설정하면 AWS가 자동으로 split-horizon DNS를 구성한다. Consumer VPC 안에서 `s3.ap-northeast-2.amazonaws.com`을 resolve하면 PrivateLink ENI IP가 반환되고, VPC 밖에서는 public IP가 반환된다.

이 동작은 "기존 코드를 한 줄도 바꾸지 않고" 트래픽을 PrivateLink로 옮길 수 있게 해주는 핵심 메커니즘이다. 단, VPC의 `enableDnsHostnames`와 `enableDnsSupport`가 모두 true여야 한다.

사용자 정의 Endpoint Service에 private DNS를 쓰려면 별도로 도메인 소유 검증(`aws ec2 modify-vpc-endpoint-service-configuration --private-dns-name`)이 필요하다. ACM 인증서 검증과 비슷한 TXT 레코드 발급 절차다.

## 8. 운영 함정과 비용 사고

**ENI당 요금**. PrivateLink는 가용영역 × Endpoint 수만큼 ENI가 만들어지고 ENI 시간당 $0.01이 부과된다. AZ 3개에 Endpoint 100개를 만들면 한 달에 $0.01 × 24 × 30 × 300 = $2,160. 사용 빈도와 무관하다. 정리되지 않은 dangling endpoint가 비용 누수를 만든다.

**hairpin NAT 함정**. Provider EC2가 NLB로 들어온 트래픽에 응답할 때, source NAT가 적용되지 않으면 응답 패킷이 NLB를 우회해서 인터넷으로 나가려고 한다. NLB의 client IP preservation을 켜야 한다.

**NLB target group의 dual-stack 이슈**. Provider EC2 그룹에 IPv6 ENI가 있으면 일부 OS에서 IPv4로 들어온 PrivateLink 트래픽 응답을 IPv6로 보내려는 케이스가 있다. target group을 IPv4-only로 강제하는 것이 안전하다.

## 참고

- AWS Documentation, "AWS PrivateLink for VPCs"
- AWS Whitepaper, "Building a Scalable and Secure Multi-VPC AWS Network Infrastructure"
- AWS re:Invent 2023 NET402, "Deep dive into PrivateLink"
- "Comparing AWS PrivateLink and VPC peering", AWS Blog
- VPC Endpoint pricing page, https://aws.amazon.com/privatelink/pricing/
