Notion 원본: https://www.notion.so/34c5a06fd6d381418035f5e2cfc8b96d

# Terraform Module 설계와 Remote State 분리

> 2026-04-24 신규 주제 · 확장 대상: DevOps (AWS / Docker·CI 학습됨)

## 학습 목표

- Terraform module의 input/output/locals 계층 분리 원칙을 적용한다
- S3 + DynamoDB lock 기반 remote state 백엔드를 구성한다
- terraform_remote_state 데이터 소스로 모듈 간 참조를 만든다
- Drift 감지와 변경 재적용 워크플로우를 GitOps와 연계한다

---

## 1. Terraform Module의 기본 구조

Module은 하나의 논리적 단위를 표현하는 `.tf` 파일들의 묶음이다. 루트 모듈도 모듈이라는 점이 중요. 권장 구성:

```
modules/
  network/
    main.tf          # VPC, Subnet, NAT Gateway
    variables.tf
    outputs.tf
    versions.tf      # required_providers, required_version
    README.md
  rds/
    main.tf
    ...
environments/
  dev/
    main.tf          # module 조합
    backend.tf       # terraform { backend "s3" { ... } }
    terraform.tfvars
  prod/
    ...
```

모듈의 입력(`variables.tf`)은 다음 유형을 구분한다:

- 필수 입력(default 없음) — cidr_block, vpc_name 같이 호출자가 반드시 지정해야 하는 값
- 선택 입력(default 있음) — 합리적 기본값이 있는 값
- sensitive 입력 — `sensitive = true` 로 표시, 계획/적용 출력에서 마스킹

## 2. Input/Output 설계

```hcl
# modules/network/variables.tf
variable "name" {
  description = "Name prefix for all resources"
  type        = string
}
variable "cidr_block" {
  description = "VPC CIDR (e.g., 10.0.0.0/16)"
  type        = string
  validation {
    condition     = can(cidrhost(var.cidr_block, 0))
    error_message = "cidr_block must be a valid CIDR."
  }
}
variable "public_subnets" {
  description = "Map of AZ -> CIDR"
  type        = map(string)
  default     = {}
}

# modules/network/outputs.tf
output "vpc_id"            { value = aws_vpc.this.id }
output "public_subnet_ids" { value = [for s in aws_subnet.public : s.id] }
output "route_table_id"    { value = aws_route_table.public.id }
```

원칙: output은 다른 모듈이 실제로 쓸 값만 노출. 모든 필드를 output 하지 않는다. 과다 노출은 순환 의존 위험을 높인다.

## 3. Remote State 백엔드 (S3 + DynamoDB)

```hcl
# environments/prod/backend.tf
terraform {
  required_version = ">= 1.7"
  backend "s3" {
    bucket         = "my-org-tfstate"
    key            = "prod/infra.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "tfstate-lock"
    encrypt        = true
    kms_key_id     = "arn:aws:kms:ap-northeast-2:111:key/abc..."
  }
}
```

S3 버저닝 + KMS 암호화는 반드시. DynamoDB table은 `LockID` (string) 파티션키만 있으면 된다. 이 상태에서 `terraform apply`는 먼저 DynamoDB에 lock 시도 → 성공 시 S3에서 state 다운로드 → 변경 적용 → S3 버전 업로드 → DynamoDB lock 해제 순으로 돌아간다. 강제 종료 시 lock이 남아 `terraform force-unlock <ID>`가 필요할 수 있다.

## 4. Module Versioning

Git 저장소에서 별도 리포지토리로 모듈을 올리고, tag로 버전을 지정해 사용:

```hcl
module "network" {
  source  = "git::ssh://git@github.com/my-org/terraform-modules.git//network?ref=v1.4.0"
  name    = "prod-vpc"
  cidr_block = "10.0.0.0/16"
}
```

또는 Terraform Registry (private registry) 사용. 통신업체나 대형 조직은 Registry를 폼으로 올린다. 버전은 semver를 따르는 것이 표준: major=breaking, minor=feature, patch=fix.

## 5. terraform_remote_state

다른 state의 output을 읽어오는 방법:

```hcl
data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = "my-org-tfstate"
    key    = "prod/network.tfstate"
    region = "ap-northeast-2"
  }
}

resource "aws_db_instance" "main" {
  db_subnet_group_name = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  vpc_id = data.terraform_remote_state.network.outputs.vpc_id
}
```

주의점: remote_state 참조는 "현재 state에 존재하는" 것을 읽는다. 상대 state가 아직 apply 안 되었으면 empty. 또한 output만 보이므로 잠재 결합이 아닌 목록화된 계약이 된다.

## 6. Workspace vs 환경별 State

| 기준 | workspace | 쇼 경로/폴더 분리 |
|---|---|---|
| 예시 | dev/stage/prod workspace | environments/dev, environments/prod |
| 코드 공유 | 100% 동일 | 같은 모듈 호출, tfvars만 다름 |
| 영향 범위 인식 | 한 눈에 안 들어옴 | 명확 |
| 권한 분리 | 불가능(모두 같은 백엔드) | 가능(환경별 IAM) |

운영환경 분리에는 workspace가 부적절하다. dev 파요보다는 폴더 구조로 확실히 가르는 게 맞고, workspace는 동일 환경 안에서 실험용 분기 정도로 한정.

## 7. Drift Detection과 GitOps

Drift란 state와 실제 인프라 상태 사이의 불일치. 콘솔에서 관리자가 시큐리티 그룹 룰을 수정한 경우가 흔함. 감지 방법:

```bash
terraform plan -detailed-exitcode
# exit 0: no changes, 2: changes, 1: error
```

CI에 주기적으로 돌리고 exit 2가 나오면 Slack 알림. 문제 발견 시:
- 콘솔 변경이 필요하면 Terraform 코드에도 반영
- 즉시 변경이면 `terraform apply`로 state와 실제를 맞춤
- Import해야 할 신규 리소스면 `terraform import`

## 8. 보안 체크리스트

1. backend.tf의 KMS 암호화 + S3 bucket policy로 읽기 제한
2. sensitive = true 를 비밀번호/토큰에 반드시
3. CI의 IAM role을 least privilege로 써, `*:*`는 금지
4. `.terraform/` 와 `*.tfstate*` 를 .gitignore에 추가 (젂)
5. module 내부에 hard-coded 계정 ID, ARN 없는지 확인

## 참고

- Terraform Docs — Modules: https://developer.hashicorp.com/terraform/language/modules
- Terraform Docs — Backend Configuration: https://developer.hashicorp.com/terraform/language/backend
- HashiCorp "Terraform Best Practices" (2023)
- Yevgeniy Brikman, "Terraform: Up & Running" 3rd ed.
