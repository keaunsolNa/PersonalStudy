Notion 원본: https://www.notion.so/3545a06fd6d38160844afefec1b0e90c

# Terraform State Drift 감지와 OPA/Sentinel Policy as Code

> 2026-05-02 신규 주제 · 확장 대상: AWS, DevOps

## 학습 목표

- terraform refresh-only / plan -refresh-only 의 동작과 적합한 시점을 안다
- driftctl, AWS Config, CloudCustodian 등 외부 drift 감지 도구의 위치를 비교한다
- OPA Rego 와 HashiCorp Sentinel 의 차이를 정책 작성 관점에서 구분한다
- pre-apply policy gate 를 GitHub Actions 파이프라인에 적용한다

## 1. State Drift 가 만드는 운영 위험

Terraform 은 `.tfstate` 를 단일 진실로 삼는다. 콘솔에서 직접 변경된 보안 그룹 규칙, 자동 스케일러가 만든 ASG 규모, AWS 가 자동 업데이트한 RDS 패치 버전 등이 state 에 반영되지 않으면, 다음 plan 이 의도와 무관한 변경 (revert, 재생성) 을 제안한다. 가장 흔한 운영 사고는 (a) 누군가 콘솔에서 inbound 0.0.0.0/0 을 임시 허용한 뒤 잊은 상태에서, (b) 다음 apply 가 그 규칙을 제거하지 못해 보안 검토에서 누락되는 패턴이다. drift 감지를 자동화하지 않으면 IaC 의 보장이 의외로 빠르게 무너진다.

## 2. terraform refresh-only / plan -refresh-only

terraform 1.0 부터 `apply -refresh-only` 가 정식이다. 실제 리소스 상태를 읽어 state 와 동기화하되, infrastructure 변경은 일으키지 않는다.

```bash
terraform plan -refresh-only -out=refresh.tfplan
terraform show refresh.tfplan       # diff 만 검토
terraform apply refresh.tfplan      # state 만 갱신
```

이 명령은 provider 의 ReadResource 를 호출해 모든 리소스를 다시 읽으므로 비용이 든다. 1,000 개 리소스 기준 4~10 분 정도 소요된다. 매 PR 마다 실행하기는 무거우니, 보통 매시간 또는 매일 cronjob 으로 실행하고 diff 가 비지 않으면 알림을 보낸다.

## 3. driftctl — out-of-band 변경 검출

driftctl 은 Cloudskiff 가 만든 OSS 로, terraform state 와 실제 cloud 인벤토리를 비교해 "state 에 없지만 클라우드에 있는" 리소스 (unmanaged) 까지 보고한다. terraform 자체 refresh 는 state 에 등록된 리소스만 검사하므로, 누군가 별도로 만든 IAM role 이나 S3 버킷은 발견하지 못한다.

```bash
driftctl scan \
  --from tfstate://s3://infra-state/prod.tfstate \
  --output json://drift.json
```

output 에서 `managed`, `unmanaged`, `missing` 카운트를 보고 임계치를 넘으면 PagerDuty 로 알림한다. driftctl 2.x 부터는 maintenance 가 줄었지만 여전히 AWS 핵심 리소스 60+ 종을 지원한다. 같은 위치의 대안으로 cloud-nuke + AWS Config rules 조합도 자주 쓰인다.

## 4. AWS Config + Conformance Pack

AWS 안에서 drift 감지를 다루면 Config 가 가장 표준적이다. 리소스 변경을 history 에 기록하고, conformance pack (managed rule 모음) 으로 위배를 점수화한다.

```yaml
# conformance-pack.yaml (발췌)
Resources:
  s3PublicReadProhibited:
    Type: AWS::Config::ConfigRule
    Properties:
      Source:
        Owner: AWS
        SourceIdentifier: S3_BUCKET_PUBLIC_READ_PROHIBITED
  rdsAutoMinorVersion:
    Type: AWS::Config::ConfigRule
    Properties:
      Source:
        Owner: AWS
        SourceIdentifier: RDS_AUTOMATIC_MINOR_VERSION_UPGRADE_ENABLED
```

Config 는 기본 6 시간 평가 주기지만, 변경 이벤트가 들어오면 rule 평가가 즉시 트리거된다. drift 와 노출 정책 위반을 동시에 추적해 EventBridge → Lambda → Slack 으로 보고하는 패턴이 표준이다.

## 5. Sentinel — Terraform Cloud / Enterprise 전용 정책 엔진

Sentinel 은 HashiCorp 의 commercial 제품과 묶인다. plan 결과를 바라볼 수 있는 imports (`tfplan/v2`) 를 통해 변경 후보를 검사한다.

```hcl
import "tfplan/v2" as tfplan

allowed_types = ["t3.micro","t3.small","m6i.large"]

mandatory "instance_type_must_be_allowed" {
  rule {
    all tfplan.resource_changes as _, c {
      c.type is "aws_instance" implies
        c.change.after.instance_type in allowed_types
    }
  }
}
```

Sentinel 는 advisory / soft-mandatory / hard-mandatory 3 단계가 있어 서비스 단계별로 차등 적용이 쉽다. 단점은 OSS terraform 에서는 동작하지 않고 Terraform Cloud 또는 Enterprise 가 필요하다는 것이다.

## 6. OPA + Conftest — OSS 표준

OSS 환경에서는 OPA(Rego) 와 conftest 로 plan JSON 을 검사한다.

```rego
package terraform.aws

deny[msg] {
  rc := input.resource_changes[_]
  rc.type == "aws_security_group_rule"
  rc.change.after.cidr_blocks[_] == "0.0.0.0/0"
  rc.change.after.from_port == 22
  msg := sprintf("SSH open to world on %s", [rc.address])
}
```

```bash
terraform show -json plan.bin > plan.json
conftest test plan.json --policy ./policy
```

GitHub Actions 워크플로에서 `terraform plan -out` → `terraform show -json` → `conftest test` 의 3 단계를 PR 검증으로 두면, drift 자동 수정 PR 이 보안 정책을 위반할 때 자동으로 차단된다.

## 7. 파이프라인 예 — drift 감지 자동 PR

drift 가 발견되면 자동으로 코드와 state 를 정합시키는 PR 을 만드는 패턴이 운영 부담을 크게 줄인다.

```yaml
# .github/workflows/drift.yaml
name: drift-detect
on:
  schedule: [{ cron: '0 * * * *' }]
permissions:
  contents: write
  pull-requests: write
jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
        with: { terraform_version: 1.8.5 }
      - name: refresh-only plan
        run: |
          terraform init
          terraform plan -refresh-only -no-color -out=tf.plan
          terraform show -json tf.plan > tf.plan.json
      - name: gate via OPA
        run: conftest test tf.plan.json --policy ./policy
      - name: open PR if drift
        run: |
          if grep -q '"actions": \["update"\]' tf.plan.json; then
            gh pr create -t "drift: $(date -I)" -b "automated drift sync"
          fi
```

핵심은 자동 PR 을 사람이 한 번 검토한 뒤 merge 하는 것이다. 자동 apply 까지 가게 두면 누군가 콘솔에서 의도를 가지고 잠시 변경한 것을 시스템이 의도와 무관하게 되돌릴 위험이 있다.

## 8. 정책 카탈로그 예시 — 잘 알려진 실수 차단

| 정책 | 의도 |
| --- | --- |
| S3 bucket — `block_public_acls = true` | Config + Rego 양쪽에서 강제 |
| RDS — encryption_at_rest = true | unencrypted DB 생성 차단 |
| IAM — wildcard action `*` 금지 | least privilege |
| SecurityGroup — 22/3389 + 0.0.0.0/0 차단 | bastion 외 SSH 노출 방지 |
| EKS — `endpoint_public_access = false` | private control plane 강제 |
| Tag — owner / cost-center 필수 | FinOps 분석 |

각 정책은 평균 5~15 줄의 Rego 또는 Sentinel 로 구현되며, 실패 메시지에 리소스 address 를 포함해 PR 코멘트에 그대로 붙여 둔다.

## 9. 함정과 운영 팁

`terraform import` 후 즉시 plan 을 실행하면 schema mismatch 로 false drift 가 나타날 수 있다. provider 버전을 정확히 고정하고 lifecycle 의 `ignore_changes` 로 자동 변경 필드를 명시한다. 예: ASG 의 `desired_capacity`, RDS 의 `engine_version` (auto minor) 등.

```hcl
resource "aws_autoscaling_group" "web" {
  # ...
  lifecycle {
    ignore_changes = [desired_capacity, target_group_arns]
  }
}
```

대규모 organization 에서는 state 분할이 중요하다. 리소스 5,000 개를 한 state 에 두면 plan/refresh 가 30 분 이상 걸리고 lock 충돌이 잦다. workspace 또는 root module 단위로 50~300 리소스로 분할하면 drift 감지 latency 를 분 단위로 유지할 수 있다.

마지막으로, drift 보고가 너무 많아지면 알림 피로가 생긴다. Config remediation 이나 driftctl 의 `--filter` 로 자동 무시할 패턴 (예: AWS 가 자동으로 추가하는 default tag) 을 명시해 두고, 사람이 봐야 하는 drift 만 알림으로 남긴다.

## 참고

- Terraform Docs — refresh-only mode <https://developer.hashicorp.com/terraform/cli/commands/plan#refresh-only-mode>
- driftctl GitHub <https://github.com/snyk/driftctl>
- HashiCorp Sentinel Docs <https://developer.hashicorp.com/sentinel>
- Open Policy Agent — Terraform usage <https://www.openpolicyagent.org/docs/latest/terraform/>
- AWS Config — Conformance Packs <https://docs.aws.amazon.com/config/latest/developerguide/conformance-packs.html>
