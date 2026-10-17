Notion 원본: https://app.notion.com/p/3fc5a06fd6d381588003de53b2aa61f6?pvs=204

# Terraform 상태 관리와 S3 원격 백엔드 락킹 및 드리프트 감지, 모듈 설계 전략

> 2026-10-17 신규 주제 · 확장 대상: AWS

## 학습 목표

- S3 원격 백엔드를 암호화, 버전닝, 락 설정까지 포함해 구성한다
- DynamoDB 락과 S3 네이티브 락(use_lockfile)의 동작 차이를 비교하고 마이그레이션 경로를 설계한다
- moved 블록, import 블록, terraform state 명령으로 리소스 주소를 안전하게 옮긴다
- plan -refresh-only로 드리프트를 감지하고 재사용 가능한 모듈 경계를 설계한다

## 1. 상태 파일이 하는 일과 로컬 상태의 한계

Terraform 상태(state)는 선언한 코드와 실제 인프라를 연결하는 매핑 테이블이다. 코드에 aws_s3_bucket.logs라는 주소가 있고 실제로 만들어진 버킷의 ID가 어떤 것인지, 각 속성이 마지막으로 확인된 값은 무엇인지를 JSON으로 저장한다. plan 단계에서 Terraform은 이 상태를 기준점으로 삼아 코드와의 차이를 계산하고, refresh로 실제 API 응답을 다시 읽어 상태를 갱신한 뒤 최종 변경 계획을 만든다. 상태가 없으면 Terraform은 이미 존재하는 리소스를 알지 못해 같은 리소스를 중복 생성하려 한다.

기본값인 로컬 백엔드는 작업 디렉터리의 terraform.tfstate 파일에 상태를 쓴다. 혼자 실험할 때는 충분하지만 팀 작업에서는 세 가지 문제가 생긴다. 첫째, 파일을 공유할 방법이 없어 사람마다 다른 상태를 갖게 된다. 둘째, 동시에 apply를 실행해도 서로를 막아 줄 장치가 없다. 셋째, 상태에는 데이터베이스 초기 비밀번호나 프라이빗 키 같은 민감 값이 평문으로 들어갈 수 있는데 이를 Git에 커밋하면 유출 경로가 된다. 원격 백엔드는 이 세 문제를 공유 저장소, 락, 저장 시 암호화로 각각 해결한다.

AWS 환경에서 사실상 표준은 S3 백엔드다. 버킷 하나에 key로 구분되는 상태 객체를 여러 개 두고, 버저닝으로 이력을 보존하며, IAM과 KMS로 접근과 암호화를 통제한다. 아래 명령으로 현재 상태에 어떤 리소스가 기록되어 있는지 확인하는 습관을 들이면 이후 모든 상태 작업의 출발점이 된다.

```bash
terraform state list
terraform state show aws_s3_bucket.logs
terraform show -json | jq '.values.root_module.resources | length'
```

상태 파일은 사람이 직접 편집하는 파일이 아니다. 형식은 버전이 올라가며 바뀔 수 있고, 한 글자만 어긋나도 serial과 lineage 검증에서 거부되거나 더 나쁘게는 리소스가 조용히 재생성된다. 수정이 필요하면 항상 terraform state 하위 명령이나 선언적 블록(moved, import, removed)을 사용한다.

## 2. S3 원격 백엔드 구성

S3 백엔드는 backend 블록 하나로 선언한다. 버킷 이름, 상태 객체의 key, 리전은 사실상 필수이며 encrypt를 true로 두면 서버 측 암호화가 적용된다. 아래는 Terraform 1.10 이상에서 S3 네이티브 락을 쓰는 구성이다.

```hcl
terraform {
  required_version = ">= 1.10.0"

  backend "s3" {
    bucket       = "acme-tfstate-prod-ap-northeast-2"
    key          = "network/vpc/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    kms_key_id   = "arn:aws:kms:ap-northeast-2:111122223333:key/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
}
```

backend 블록에는 변수나 로컬 값을 쓸 수 없다. 백엔드는 모듈 평가보다 먼저 초기화되기 때문이다. 환경마다 버킷이나 key가 달라야 한다면 부분 구성(partial configuration)을 사용한다. 블록에는 고정값만 두고 나머지는 init 시점에 파일이나 플래그로 넘긴다.

```hcl
# backend.tf
terraform {
  backend "s3" {}
}
```

```bash
# envs/prod.s3.tfbackend 에 bucket, key, region 을 적어 두고 사용한다
terraform init -backend-config=envs/prod.s3.tfbackend

# 값을 직접 넘기는 방식도 가능하다
terraform init \
  -backend-config="bucket=acme-tfstate-prod-ap-northeast-2" \
  -backend-config="key=network/vpc/terraform.tfstate" \
  -backend-config="region=ap-northeast-2"
```

상태 버킷 자체는 Terraform 상태를 담는 닭과 달걀 문제를 가진다. 이 버킷을 만드는 코드의 상태를 어디에 둘지 정해야 하기 때문이다. 현실적인 해법은 부트스트랩 전용의 작은 루트 모듈을 로컬 상태로 한 번 적용해 버킷을 만들고, 이후 그 모듈 자체도 새 버킷으로 마이그레이션하는 것이다. 마이그레이션은 backend 블록을 추가한 뒤 아래 명령으로 수행한다. 이때 로컬 상태가 원격으로 복사되며 사전에 확인 질문이 나온다.

```bash
terraform init -migrate-state
```

팀이 여러 AWS 계정을 쓴다면 backend 블록 안의 assume_role 설정으로 상태 전용 계정의 역할을 맡아 접근하게 하는 구성이 흔하다. 상태 버킷을 워크로드 계정과 분리해 두면 워크로드 계정이 침해되어도 상태 이력까지 지워지는 사고를 줄일 수 있다.

## 3. 락킹: DynamoDB에서 use_lockfile로

락은 같은 상태에 두 개의 쓰기 작업이 동시에 일어나는 것을 막는다. 락이 없으면 두 사람이 동시에 apply할 때 나중에 쓴 상태가 먼저 쓴 상태를 덮어써 리소스가 상태에서 사라지는(고아가 되는) 사고가 난다. 오랫동안 S3 백엔드의 락은 DynamoDB 테이블에 의존했다. 파티션 키 LockID를 가진 테이블을 만들고 backend에 dynamodb_table 이름을 적으면, Terraform은 조건부 쓰기로 락 항목을 만들어 선점한다.

```hcl
# 과거 방식. 새로 구성할 때는 권하지 않는다
terraform {
  backend "s3" {
    bucket         = "acme-tfstate-prod-ap-northeast-2"
    key            = "network/vpc/terraform.tfstate"
    region         = "ap-northeast-2"
    encrypt        = true
    dynamodb_table = "acme-tfstate-lock"
  }
}
```

Terraform 1.10에서 S3 네이티브 락킹이 실험적 기능으로 도입되었다. backend에 use_lockfile = true를 쓰면 상태 객체 옆에 key에 .tflock이 붙은 락 객체(예를 들어 network/vpc/terraform.tfstate.tflock)를 만들어 락으로 사용한다. 이 방식은 2024년에 S3에 추가된 조건부 쓰기(If-None-Match 헤더) 기능에 기반한다. 객체가 이미 존재하면 쓰기가 실패하므로 두 번째 작업자는 락을 얻지 못하고 대기하거나 실패한다. 이후 1.11 버전에서 정식 기능으로 안정화된 것으로 알려져 있으며, HashiCorp 문서는 DynamoDB 락 관련 인자를 deprecated로 표시하고 향후 마이너 버전에서 제거될 예정이라고 안내한다. 따라서 새 프로젝트는 처음부터 use_lockfile을 쓰고, 기존 프로젝트는 계획을 세워 옮기는 것이 맞다.

둘을 동시에 켜는 과도기 구성도 지원된다. 두 락을 모두 획득해야 작업이 진행되므로, 팀원 일부가 아직 예전 버전을 쓰는 동안에도 안전하게 전환할 수 있다. 권장 순서는 다음과 같다.

```bash
# 1단계: dynamodb_table 과 use_lockfile 을 함께 선언하고 재초기화
terraform init -reconfigure

# 2단계: 모든 파이프라인과 팀원이 1.10 이상으로 올라간 것을 확인한 뒤
#        dynamodb_table 줄을 지우고 다시 재초기화
terraform init -reconfigure

# 3단계: 사용하지 않는 DynamoDB 테이블 제거는 별도 변경으로 진행
```

S3 네이티브 락을 쓰려면 실행 주체에게 상태 객체뿐 아니라 락 객체에 대한 권한도 필요하다. 읽기 권한이 있는 GetObject와 목록 권한 ListBucket 외에, 락 객체를 만들고 지우는 PutObject와 DeleteObject가 .tflock 경로에 부여되어야 한다. 이 권한이 빠지면 plan 단계부터 락 획득 실패로 멈춘다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::acme-tfstate-prod-ap-northeast-2"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": [
        "arn:aws:s3:::acme-tfstate-prod-ap-northeast-2/network/vpc/terraform.tfstate",
        "arn:aws:s3:::acme-tfstate-prod-ap-northeast-2/network/vpc/terraform.tfstate.tflock"
      ]
    }
  ]
}
```

락 획득 실패 시 기본 동작은 즉시 에러다. CI에서 같은 상태를 건드리는 작업이 겹칠 가능성이 있다면 -lock-timeout으로 대기 시간을 준다. 기본값은 0초라서 옵션을 주지 않으면 한 번만 시도하고 포기한다. 비정상 종료로 락이 남았다면 락 ID를 확인한 뒤 강제 해제할 수 있지만, 다른 사람이 실제로 apply 중인지 먼저 확인해야 한다.

```bash
terraform plan -lock-timeout=60s
terraform apply -lock-timeout=5m

# 에러 메시지에 출력된 Lock ID 를 사용한다
terraform force-unlock 3b1c7a5e-0000-0000-0000-000000000000
```

| 항목 | DynamoDB 락 | S3 네이티브 락 |
| --- | --- | --- |
| 도입 | 오래전부터 지원 | Terraform 1.10에서 실험적으로 도입 |
| 락 저장 위치 | DynamoDB 테이블 (LockID 키) | 상태 객체 옆의 .tflock 객체 |
| 추가 리소스 | 테이블, 테이블 권한 필요 | 없음, 버킷 권한만 사용 |
| 원자성 근거 | DynamoDB 조건부 쓰기 | S3 조건부 쓰기 (If-None-Match) |
| 향후 | deprecated, 이후 버전에서 제거 예정 | 권장 방식 |
| 장애 지점 | S3와 DynamoDB 두 서비스 | S3 하나 |

## 4. 상태 보호: 암호화, 버저닝, 접근 통제

락은 동시성 문제를 막을 뿐 데이터 보호는 별개 문제다. 상태 버킷은 최소한 세 가지 설정을 갖춰야 한다. 첫째, 버전닝이다. 잘못된 apply나 state push로 상태가 손상되었을 때 이전 버전을 복원할 수 있는 유일한 수단이다. 둘째, 기본 암호화로 SSE-KMS를 걸고 키 정책으로 접근 주체를 제한한다. 셋째, 퍼블릭 액세스 차단이다. 상태는 비밀 값을 포함할 수 있으므로 어떤 경로로도 공개되면 안 된다.

```hcl
resource "aws_s3_bucket" "tfstate" {
  bucket = "acme-tfstate-prod-ap-northeast-2"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.tfstate.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    id     = "expire-noncurrent"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
```

비현재 버전을 90일 뒤 만료시키는 수명 주기 규칙은 비용과 복구 가능 기간의 절충이다. 상태 파일은 보통 수십 KB에서 수 MB 수준이라 저장 비용은 미미하지만, 변경이 잦은 상태는 버전이 빠르게 쌓이고 버전이 많을수록 ListObjectVersions 응답이 길어져 복구 때 원하는 시점을 찾기 어렵다. 30일에서 90일 사이로 정하고 장기 보관이 필요하면 별도 백업 계정에 복제하는 구성이 일반적이다.

복구 절차는 단순하다. 이전 버전의 객체를 현재 버전으로 다시 복사하면 된다. 복구 직후에는 반드시 plan을 실행해 상태와 실제 인프라의 차이를 확인한다.

```bash
aws s3api list-object-versions \
  --bucket acme-tfstate-prod-ap-northeast-2 \
  --prefix network/vpc/terraform.tfstate \
  --query 'Versions[].[VersionId,LastModified,Size]' --output table

aws s3api copy-object \
  --bucket acme-tfstate-prod-ap-northeast-2 \
  --key network/vpc/terraform.tfstate \
  --copy-source "acme-tfstate-prod-ap-northeast-2/network/vpc/terraform.tfstate?versionId=VERSION_ID"
```

상태 안의 비밀은 암호화된 버킷에 있더라도 상태를 읽을 수 있는 사람에게는 평문이다. 민감 값은 sensitive 표시로 출력만 가릴 뿐 상태에는 그대로 기록된다. Terraform 1.10에서 도입된 ephemeral 값과 1.11의 쓰기 전용 인자는 이 문제를 상태 밖으로 밀어내려는 방향의 기능이므로, 지원하는 리소스에서는 비밀을 상태에 남기지 않는 쪽을 우선 검토한다.

## 5. 상태 분리 전략과 terraform_remote_state

하나의 상태에 모든 것을 넣으면 편하지만 영향 범위(blast radius)가 커진다. 네트워크 변경 한 번이 데이터베이스와 애플리케이션까지 한 plan에 올려 놓고, plan 시간은 리소스 수에 비례해 길어진다. 실무에서는 수백 개 리소스를 넘기면 refresh에만 수 분이 걸리는 일이 흔하다. 반대로 너무 잘게 쪼개면 의존 관계를 관리하는 비용이 늘어난다. 변경 빈도와 책임 주체가 같은 리소스끼리 묶는 것이 기준이다. 예를 들어 VPC와 서브넷처럼 거의 바뀌지 않는 계층, IAM 같은 보안 계층, 자주 배포되는 애플리케이션 계층을 서로 다른 상태로 둔다.

분리한 상태끼리 값을 주고받는 가장 단순한 방법은 terraform_remote_state 데이터 소스다. 상류 상태의 output만 읽을 수 있다.

```hcl
data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = "acme-tfstate-prod-ap-northeast-2"
    key    = "network/vpc/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

resource "aws_instance" "app" {
  ami           = data.aws_ami.al2023.id
  instance_type = "t3.small"
  subnet_id     = data.terraform_remote_state.network.outputs.private_subnet_ids[0]
}
```

이 방식의 단점은 상태 전체에 대한 읽기 권한이 필요하다는 점이다. output만 노출하고 싶어도 하류 팀은 상태 객체를 읽을 수 있어야 하므로 비밀 값이 함께 보일 수 있다. 대안은 SSM Parameter Store에 필요한 값만 게시하고 하류에서는 aws_ssm_parameter 데이터 소스로 읽는 것이다. 결합도가 낮아지고 권한 범위도 좁아지는 대신 값 게시용 리소스가 늘어난다. 또는 태그 기반의 data 소스(aws_vpc, aws_subnets)로 실제 인프라를 직접 조회하는 방법도 있는데, 상류 상태와 완전히 분리되지만 조회 조건이 모호하면 의도하지 않은 리소스를 잡을 위험이 있다.

워크스페이스(terraform workspace)는 같은 코드에 상태를 여러 개 두는 기능이다. S3 백엔드에서는 기본 워크스페이스가 아니면 key 앞에 env:/워크스페이스이름/ 접두사가 붙는다. 다만 환경 간 권한과 변경 승인을 분리해야 하는 dev, staging, prod 구분에는 워크스페이스보다 디렉터리나 저장소를 나누고 환경별로 backend를 분리하는 쪽이 사고 위험이 낮다. 워크스페이스는 현재 선택된 값이 눈에 잘 띄지 않아 prod에서 실수로 명령을 실행하기 쉽기 때문이다.

## 6. 상태 수술: state mv, import, moved 블록

코드를 리팩터링하면 리소스 주소가 바뀌는데, Terraform은 주소가 바뀐 것을 삭제와 생성으로 해석한다. 데이터베이스나 스토리지처럼 재생성이 치명적인 리소스에서는 이것이 사고가 된다. 해결책은 상태의 주소를 코드에 맞춰 옮겨 주는 것이다. 전통적인 방법은 명령형 terraform state mv다.

```bash
# 리소스 이름 변경
terraform state mv aws_s3_bucket.logs aws_s3_bucket.access_logs

# 루트에 있던 리소스를 모듈 안으로 이동
terraform state mv aws_s3_bucket.logs module.logging.aws_s3_bucket.this

# 상태에서만 제거하고 실제 리소스는 유지
terraform state rm aws_s3_bucket.legacy
```

이 방식은 실행한 사람의 로컬에서 수행되고 코드 리뷰에 남지 않으며, 같은 코드를 쓰는 다른 환경에는 각각 반복해야 한다. Terraform 1.1부터는 선언형 moved 블록이 지원된다. 코드에 이동 사실을 기록해 두면 모든 환경이 다음 plan 때 자동으로 같은 이동을 수행한다. 리뷰도 가능하고 되돌리기도 쉽다.

```hcl
moved {
  from = aws_s3_bucket.logs
  to   = aws_s3_bucket.access_logs
}

moved {
  from = aws_s3_bucket.access_logs
  to   = module.logging.aws_s3_bucket.this
}

# count 에서 for_each 로 전환할 때
moved {
  from = aws_subnet.private[0]
  to   = aws_subnet.private["a"]
}
```

moved 블록은 해당 모듈을 쓰는 모든 환경이 적용을 마칠 때까지 코드에 남겨 두는 것이 안전하다. 라이브러리 모듈을 외부에 배포한다면 사용자가 언제 업그레이드할지 알 수 없으므로 사실상 영구적으로 유지한다. plan 출력에 has moved to라는 문구가 나오면 의도한 이동이 인식된 것이다.

이미 존재하는 리소스를 Terraform 관리로 편입하려면 import가 필요하다. 예전에는 terraform import 명령이 유일한 방법이었지만, Terraform 1.5부터 선언형 import 블록이 추가되어 plan과 apply 흐름에 통합되었다. 같은 버전에서 -generate-config-out 옵션으로 리소스 코드 초안을 만들어 주는 기능도 들어왔다.

```hcl
import {
  to = aws_s3_bucket.legacy
  id = "acme-legacy-bucket"
}
```

```bash
# 코드 초안 생성 (파일이 이미 있으면 실패하므로 새 파일명을 쓴다)
terraform plan -generate-config-out=generated.tf

# 생성된 코드를 검토하고 정리한 뒤 적용
terraform apply

# 구형 명령형 방식
terraform import aws_s3_bucket.legacy acme-legacy-bucket
```

생성된 코드는 기본값까지 모두 펼쳐 쓰는 경향이 있으므로 그대로 쓰지 말고 필요한 인자만 남기도록 다듬어야 한다. Terraform 1.7에서는 removed 블록이 추가되어 리소스를 인프라에서는 지우지 않고 상태에서만 제거하는 일도 선언형으로 표현할 수 있다.

```hcl
removed {
  from = aws_s3_bucket.legacy

  lifecycle {
    destroy = false
  }
}
```

모든 상태 수술 전에는 먼저 상태를 백업한다. S3 버저닝이 있어도 작업 직전의 스냅샷을 로컬에 받아 두면 판단이 빨라진다.

```bash
terraform state pull > backup-$(date +%Y%m%d-%H%M%S).tfstate
terraform plan   # 변경 0건인지 확인
```

## 7. 드리프트 감지: plan -refresh-only

드리프트는 상태와 실제 인프라가 어긋나는 현상이다. 콘솔에서 누군가 보안 그룹 규칙을 직접 추가하거나, 오토스케일링이 인스턴스 수를 바꾸거나, 다른 자동화가 태그를 덮어쓰는 경우가 대표적이다. 일반 plan은 refresh와 코드 비교를 한꺼번에 수행하기 때문에, 드리프트가 있으면 코드 대로 되돌리는 변경 계획으로 보인다. 이때 어디까지가 코드 변경이고 어디부터가 외부 변경인지 구분하기 어렵다.

Terraform 0.15.4부터 지원되는 -refresh-only 모드는 코드와의 비교 없이 실제 인프라를 읽어 상태와 비교한 결과만 보여 준다. 실제 리소스는 건드리지 않고 상태를 현실에 맞출지 판단하는 용도다.

```bash
# 드리프트만 확인
terraform plan -refresh-only

# 현실을 상태에 반영 (인프라는 변경되지 않음)
terraform apply -refresh-only

# CI 에서 종료 코드로 판정
terraform plan -refresh-only -detailed-exitcode -lock=false
echo $?   # 0 변경 없음, 1 오류, 2 차이 있음
```

종료 코드 2는 차이가 있다는 뜻이므로 스케줄 파이프라인에서 알림 조건으로 쓸 수 있다. 읽기 전용 점검에서 락을 잡으면 실제 배포를 막을 수 있어 -lock=false를 쓰기도 하지만, 이 경우 같은 시점에 진행 중인 apply가 있으면 결과가 흔들릴 수 있다는 점을 감수해야 한다. 야간에 하루 한 번 모든 루트 모듈에서 실행하는 정도가 비용 대비 효과가 좋다. 다만 refresh는 모든 리소스에 읽기 API를 호출하므로 대형 상태에서는 API 스로틀링과 실행 시간이 늘어난다. 이럴 때는 -target 대신 상태를 분리하는 것이 근본 해법이다.

드리프트를 발견한 뒤의 선택지는 두 가지다. 외부 변경이 옳다면 코드를 수정해 따라가고 refresh-only apply로 상태를 맞춘다. 외부 변경이 잘못이라면 일반 apply로 코드 상태로 되돌린다. 반드시 외부 변경을 허용해야 하는 속성은 ignore_changes로 명시한다.

```hcl
resource "aws_autoscaling_group" "app" {
  name             = "app"
  min_size         = 2
  max_size         = 10
  desired_capacity = 2
  # ... 생략

  lifecycle {
    ignore_changes = [desired_capacity]
  }
}
```

ignore_changes는 편하지만 남용하면 코드가 현실을 더 이상 대변하지 못한다. 대상 속성을 최소로 좁히고 이유를 주석으로 남긴다. 예방 측면에서는 IAM 정책으로 콘솔과 CLI의 쓰기 권한을 좁히고 변경이 Terraform 파이프라인 역할을 통해서만 일어나게 하는 것이 가장 효과적이다. AWS Config 규칙이나 CloudTrail 이벤트로 Terraform 외부 경로의 변경을 감시하면 스케줄 점검 주기 사이의 공백을 메울 수 있다. 참고로 HCP Terraform에는 상태 건전성 점검(health assessment)으로 드리프트를 주기적으로 감지하는 기능이 있지만 별도 서비스이므로 도입 여부는 비용과 운영 방식에 따라 판단한다.

## 8. 모듈 설계 전략

모듈은 관련 리소스를 하나의 단위로 묶어 입력 변수와 출력 값만 노출하는 추상화다. 좋은 모듈은 하나의 명확한 책임을 가진다. VPC, 서비스용 IAM 역할 묶음, ALB와 대상 그룹 같은 식이다. 반대로 모든 설정을 변수로 열어 둔 만능 모듈은 변수 수가 리소스 인자 수와 같아져 감싸는 의미가 사라지고, 호출하는 쪽이 결국 리소스를 직접 쓰는 것과 다를 바 없어진다. 모듈은 조직의 규칙(암호화 강제, 태그 표준, 로깅 연결)을 기본값으로 굳히고 달라지는 부분만 변수로 받을 때 가치가 생긴다.

입력은 타입과 검증을 갖춰 선언하고, 출력은 하류가 실제로 필요로 하는 것만 내보낸다. 한 번 공개한 출력 이름은 호출자가 의존하므로 이름 변경은 호환성 파괴다.

```hcl
# modules/log_bucket/variables.tf
variable "name" {
  type        = string
  description = "버킷 이름"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{2,62}$", var.name))
    error_message = "S3 버킷 이름 규칙(3~63자, 소문자, 숫자, 점, 하이픈)을 따라야 한다."
  }
}

variable "retention_days" {
  type    = number
  default = 90

  validation {
    condition     = var.retention_days >= 1
    error_message = "retention_days 는 1 이상이어야 한다."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

# modules/log_bucket/main.tf
resource "aws_s3_bucket" "this" {
  bucket = var.name
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration {
      days = var.retention_days
    }
  }
}

# modules/log_bucket/outputs.tf
output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}
```

모듈 안에서 provider 구성을 선언하지 않는 것이 원칙이다. provider 블록은 루트 모듈에서만 정의하고 모듈에는 필요한 provider를 required_providers로 선언한 뒤, 여러 리전이나 계정이 필요하면 configuration_aliases로 받아서 호출 시점에 providers 인자로 넘긴다. 모듈 안에 provider를 넣으면 모듈에 count나 for_each를 쓸 수 없고 삭제도 까다로워진다.

버전 관리는 레지스트리나 Git 태그를 쓴다. 호출 측에서는 정확한 버전이나 좁은 범위를 고정해 모듈 업데이트가 예고 없이 인프라 변경으로 이어지지 않게 한다.

```hcl
module "access_logs" {
  source  = "app.terraform.io/acme/log-bucket/aws"
  version = "~> 2.3"

  name           = "acme-access-logs-prod"
  retention_days = 180
  tags           = local.common_tags
}

module "audit_logs" {
  source = "git::https://github.com/acme/terraform-aws-log-bucket.git?ref=v2.3.1"
  name   = "acme-audit-logs-prod"
}
```

모듈을 중첩할수록 주소가 길어지고 디버깅이 어려워진다. 깊이는 두세 단계 이내로 제한하고, 모듈 안에서 다른 모듈을 호출하기보다 루트에서 조합하는 평평한 구조를 선호한다. 또한 모듈 경계는 상태 이동 비용과 직결된다. 모듈 내부 구조를 바꾸면 주소가 달라지므로 앞서 본 moved 블록을 모듈 안에 함께 배포해야 한다. 테스트로는 Terraform 1.6에서 도입된 terraform test 명령과 tftest.hcl 파일로 plan 또는 apply 단위의 검증을 작성할 수 있다.

```hcl
# tests/log_bucket.tftest.hcl
run "defaults" {
  command = plan

  variables {
    name = "acme-test-logs"
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.this.rule[0].expiration[0].days == 90
    error_message = "기본 보관 기간은 90일이어야 한다."
  }
}
```

## 9. 운영 체크리스트와 trade-off

지금까지의 선택은 모두 안전성과 단순성 사이의 교환이다. 락을 DynamoDB에서 S3 네이티브로 옮기면 의존 서비스가 줄고 권한 설계가 단순해지지만, 팀 전원의 Terraform 버전을 1.10 이상으로 맞춰야 한다는 선행 조건이 생긴다. 상태를 잘게 나누면 영향 범위와 plan 시간이 줄지만 출력 전달과 적용 순서를 관리해야 한다. 드리프트 점검을 자주 돌리면 감지가 빨라지지만 API 호출과 실행 비용이 늘고, 모듈을 추상화할수록 중복이 줄지만 변경 시 영향 범위와 호환성 부담이 커진다.

| 결정 | 얻는 것 | 치르는 비용 |
| --- | --- | --- |
| use_lockfile 전환 | DynamoDB 제거, 권한 단순화 | 모든 실행 환경의 버전 상향 |
| 상태 분리 | 영향 범위 축소, plan 단축 | 출력 전달, 적용 순서 관리 |
| moved 블록 사용 | 리뷰 가능한 리팩터링 | 블록을 장기간 코드에 유지 |
| refresh-only 정기 실행 | 드리프트 조기 발견 | API 호출 증가, 알림 대응 부담 |
| 모듈 버전 고정 | 예기치 않은 변경 차단 | 업그레이드를 의식적으로 수행 |
| 버저닝 + 90일 만료 | 복구 가능, 비용 통제 | 장기 이력은 별도 백업 필요 |

도입 순서는 다음처럼 잡으면 위험이 작다. 먼저 상태 버킷을 버저닝, KMS 암호화, 퍼블릭 차단으로 보호한다. 다음으로 모든 루트 모듈의 backend를 S3로 옮기고 락을 켠다. 그다음 CI에서 plan 결과를 PR에 남기고 apply는 보호된 브랜치에서만 실행하게 한다. 마지막으로 야간 refresh-only 점검과 알림을 붙인다. 이 순서를 따르면 각 단계가 독립적으로 검증되고 문제가 생겨도 직전 단계로 쉽게 돌아갈 수 있다.

운영 중에 자주 만나는 증상은 몇 가지로 정리된다. Error acquiring the state lock이 나오면 먼저 다른 실행이 진행 중인지 확인하고, 아니라면 force-unlock으로 푼다. 상태 객체 접근 거부는 대개 KMS 키 정책이나 .tflock 경로 권한 누락이다. plan에서 리소스가 갑자기 삭제와 생성으로 보이면 리팩터링으로 주소가 바뀐 것이므로 apply 전에 moved 블록을 추가한다. 이 세 가지만 익혀도 상태 관련 사고의 대부분은 데이터 손실 없이 해결할 수 있다.

## 참고

- Terraform S3 백엔드 문서: https://developer.hashicorp.com/terraform/language/backend/s3
- Terraform 상태 개요: https://developer.hashicorp.com/terraform/language/state
- 상태 락킹 설명: https://developer.hashicorp.com/terraform/language/state/locking
- moved 블록으로 리팩터링하기: https://developer.hashicorp.com/terraform/language/modules/develop/refactoring
- import 블록: https://developer.hashicorp.com/terraform/language/import
- terraform plan 명령 (refresh-only 모드): https://developer.hashicorp.com/terraform/cli/commands/plan
- 모듈 개발 가이드: https://developer.hashicorp.com/terraform/language/modules/develop
- Terraform 변경 이력: https://github.com/hashicorp/terraform/blob/main/CHANGELOG.md
