Notion 원본: https://app.notion.com/p/3a55a06fd6d381b7a010cafae24fdc80

# Terraform 상태 관리와 원격 백엔드 락 및 드리프트 감지

> 2026-07-22 신규 주제 · 확장 대상: DevOps(IaC·상태 관리)

## 학습 목표

- Terraform state 파일이 리소스와 실제 인프라를 매핑하는 방식과 그 필요성을 파악한다.
- 원격 백엔드의 상태 잠금(locking)이 동시 실행 충돌을 막는 원리를 분석한다.
- plan·apply·refresh 사이클에서 드리프트가 감지·조정되는 흐름을 추적한다.
- state 분리·import·이동(state mv) 전략과 대규모 협업 시 상태 설계를 세운다.

## 1. state 파일 — 선언과 현실의 매핑

Terraform은 선언적이다. 개발자가 원하는 최종 상태(`aws_instance.web` 1대 등)를 HCL로 기술하면, Terraform이 현재와 비교해 필요한 변경만 수행한다. 이 "현재"를 기록한 것이 **state 파일**(`terraform.tfstate`)다. state는 HCL 리소스 주소(`aws_instance.web`)와 실제 클라우드 리소스 ID(`i-0abc123`)의 매핑, 그리고 각 리소스의 속성 스냅샷을 담는다.

state가 필요한 이유는 명확하다. HCL만으로는 "이 코드가 어느 실제 리소스에 대응하는지" 알 수 없다. AWS에 인스턴스가 있어도 그것이 이 Terraform 설정이 만든 것인지 판뱄할 표식이 없다. state가 이 대응 관계를 저장해, 다음 apply에서 "web은 이미 i-0abc123으로 존재하니 새로 만들지 말고 차이만 조정"하도록 한다. state가 없거나 유실되면 Terraform은 모든 리소스를 새로 만들려 해 인프라가 중복 생성되는 재앙이 벌어진다.

```json
// terraform.tfstate (일부)
{
  "resources": [{
    "type": "aws_instance", "name": "web",
    "instances": [{
      "attributes": { "id": "i-0abc123", "instance_type": "t3.micro", "ami": "ami-0xyz" }
    }]
  }]
}
```

## 2. 로컬 state의 한계와 원격 백엔드

기본 state는 로컬 파일이다. 혼자 쓸 때는 괜찮지만 팀 협업에서 치명적이다. state가 한 사람의 노트북에만 있으면 다른 팀원은 최신 상태를 모르고, 두 사람이 동시에 apply하면 state가 충돌·손상된다. 또 state에는 DB 비밀번호 같은 민감 값이 평문으로 저장될 수 있어 로컬·git 보관은 위험하다.

**원격 백엔드**는 state를 공유 저장소(S3, Azure Blob, GCS, Terraform Cloud 등)에 둔다. 팀원 모두 같은 state를 참조하고, 저장소 수준 암호화와 버전 관리를 얻는다. S3 백엔드 구성 예시는 다음과 같다.

```hcl
terraform {
  backend "s3" {
    bucket         = "mycompany-tfstate"
    key            = "prod/network/terraform.tfstate"
    region         = "ap-northeast-2"
    encrypt        = true
    dynamodb_table = "terraform-locks"   # 상태 잠금용 (아래 참조)
  }
}
```

`key`가 state의 저장 경로다. 프로젝트·환경별로 key를 나눠 상태를 분리한다(§7). `encrypt = true`로 S3 서버측 암호화를 켜고, 접근은 IAM으로 제한한다.

## 3. 상태 잠금 — 동시 apply 충돌 방지

여러 사람(또는 CI 파이프라인)이 동시에 apply하면 같은 state를 동시에 쓰면서 손상되거나, 실제 인프라에 모순된 변경을 가할 수 있다. **상태 잠금(state locking)**이 이를 막는다. apply·plan 시작 시 Terraform이 락을 획득하고, 끝나면 해제한다. 락을 이미 누가 쥐고 있으면 다른 실행은 대기하거나 실패한다.

S3 백엔드는 잠금을 위해 별도 저장소를 쓴다. 전통적으로 DynamoDB 테이블(위 `dynamodb_table`)에 락 항목을 조건부 쓰기(conditional put)로 생성해 원자적 잠금을 구현했다. 락 항목이 이미 있으면 쓰기가 실패해 잠금 획득이 거부된다. 최근 버전은 S3 자체의 조건부 쓰기 기능으로 DynamoDB 없이 락을 지원하는 방향도 도입됐다. 어느 쪽이든 원리는 "원자적 조건부 생성으로 상호 배제를 구현한다"이다.

```
$ terraform apply
Acquiring state lock. This may take a few moments...

# 다른 실행이 락을 쥐고 있으면:
Error: Error acquiring the state lock
  Lock Info:
    ID:        abc-123
    Who:       ci-runner@build-42
    Created:   2026-07-22 11:20:00 UTC
```

락이 비정상 종료로 남으면(프로세스 강제 종료 등) `terraform force-unlock <LOCK_ID>`로 수동 해제한다. 단, 실제로 다른 실행이 진행 중이 아님을 확인하고 써야 한다 — 진행 중인 apply의 락을 강제 해제하면 state 손상 위험이 있다.

## 4. plan·refresh·apply 사이클

Terraform 실행은 세 단계 개념으로 이해한다. **refresh**는 state에 기록된 각 리소스의 실제 현재 상태를 클라우드 API로 조회해 state를 최신화한다. **plan**은 갱신된 state(현실)와 HCL(원하는 상태)을 비교해 생성·수정·삭제할 변경 목록을 만든다. **apply**는 그 계획을 실행하고 결과를 state에 다시 기록한다.

```
$ terraform plan
  # 1) refresh: AWS 에 실제 상태 조회
  # 2) diff: HCL 목표 vs 실제
  #   ~ aws_instance.web  instance_type: "t3.micro" -> "t3.small"  (수정)
  #   + aws_security_group.new                                     (생성)
Plan: 1 to add, 1 to change, 0 to destroy.
```

`plan`은 변경을 미리 보여줄 뿐 적용하지 않는다. `terraform plan -out=tfplan`으로 계획을 파일로 저장하고 `terraform apply tfplan`으로 그 계획만 실행하면, plan과 apply 사이에 인프라가 바뀌어 예상과 다른 변경이 적용되는 것을 막는다. CI에서 plan을 리뷰용으로 산출하고 승인 후 그 저장된 계획을 apply 하는 것이 안전한 파이프라인 패턴이다.

## 5. 드리프트 — 코드 밖에서 생긴 변경

**드리프트(drift)**는 Terraform이 아닌 경로로 실제 인프라가 바뀐 상태다. 누군가 콘솔에서 인스턴스 타입을 수동 변경하거나, 오토스케일링이 리소스를 조정하거나, 다른 도구가 태그를 바꾸면 state와 현실이 어긋난다. Terraform은 refresh 단계에서 이 차이를 감지한다.

```
$ terraform plan
  # refresh 중 실제 상태가 state 와 다름을 발견
  ~ aws_instance.web
      instance_type: "t3.small" -> "t3.micro"
        # 코드(state)는 micro 인데 콘솔에서 누가 small 로 바꿈
        # → plan 은 이를 다시 micro 로 되돌리려 함
```

Terraform의 철학은 "HCL이 진실의 원천(source of truth)"이다. 드리프트가 있으면 plan은 실제를 코드에 맞춰 되돌리는 변경을 제안한다. 드리프트를 정기적으로 감지하려면 CI에서 주기적으로 `terraform plan -detailed-exitcode`를 돌린다. 이 옵션은 변경이 없으면 exit 0, 오류면 1, **변경(드리프트 포함)이 있으면 2**를 반환하므로, exit 2를 잡아 알림을 보내는 드리프트 감지 잡을 구성할 수 있다.

```bash
terraform plan -detailed-exitcode -out=tfplan
case $? in
  0) echo "드리프트 없음" ;;
  2) echo "드리프트 감지 — 리뷰 필요"; notify_slack ;;
  1) echo "plan 실패"; exit 1 ;;
esac
```

## 6. 드리프트 조정 전략

드리프트를 발견하면 두 방향의 선택지가 있다. 첫째, **코드로 되돌리기** — 수동 변경이 실수라면 apply로 HCL 정의대로 복원한다. 둘째, **코드를 현실에 맞추기** — 수동 변경이 의도적이고 유지해야 한다면 HCL을 수정해 그 값을 반영한다. 잘못된 선택은 위험하다. 오토스케일러가 정당하게 조정한 인스턴스 수를 Terraform이 되돌리면 서비스 용량이 줄어든다. 그래서 오토스케일링이 관리하는 속성은 `lifecycle { ignore_changes = [desired_capacity] }`로 Terraform 관리에서 제외해 드리프트 오탐을 막는다.

```hcl
resource "aws_autoscaling_group" "app" {
  desired_capacity = 3
  lifecycle {
    ignore_changes = [desired_capacity]   # 오토스케일러의 조정을 드리프트로 보지 않음
  }
}
```

`ignore_changes`는 "이 속성은 외부에서 바뀜어도 내가 관여하지 않는다"는 선언이다. 이 경계 설정이 실무 드리프트 관리의 핵심이다. 무엇을 Terraform이 소유하고 무엇을 런타임 시스템에 위임할지 명확히 나눠야, 드리프트 감지가 진짜 문제만 알리는 신호가 된다.

## 7. state 분리와 blast radius

하나의 거대한 state에 모든 인프라를 넣으면 위험하다. 한 번의 apply가 네트워크·DB·앱을 모두 건드려 실수의 폭발 반경(blast radius)이 커지고, plan·apply가 느리며, 락 경합이 심해진다. 실무는 state를 논리적 경계로 **분리**한다 — 환경별(prod/staging), 계층별(network/database/application)로 나눠 각각 독립 state를 둔다.

| 분리 축 | 예시 key 경로 | 이점 |
|---|---|---|
| 환경 | `prod/`, `staging/` | 환경 간 격리, 실수 전파 차단 |
| 계층 | `network/`, `app/` | blast radius 축소, 병렬 작업 |
| 팀 | `team-a/`, `team-b/` | 소유권·락 분리 |

분리된 state 간에는 `terraform_remote_state` 데이터 소스나 SSM 파라미터로 출력값을 참조한다. 예를 들어 network state가 만든 VPC ID를 app state가 읽어 쓴다. 이 참조로 결합은 유지하되 apply 단위는 분리한다. 다만 지나친 분리는 상태 간 의존 관리 복잡도를 키우므로, "함께 바뀌는 것은 함께, 독립적으로 바뀌는 것은 분리"라는 응집도 원칙으로 경계를 깋는다.

## 8. import·state mv와 리팩터링

기존에 수동 생성했거나 다른 도구로 만든 리소스를 Terraform 관리로 편입하려면 `import`를 쓴다. HCL에 리소스 블록을 작성하고 실제 ID를 state에 연결한다. 최신 버전은 `import` 블록으로 선언적 임포트를 지원해, plan에서 임포트 결과를 미리 확인할 수 있다.

```hcl
import {
  to = aws_instance.web
  id = "i-0abc123"
}
# terraform plan → 임포트 후 예상 상태를 미리 보여줌
```

리소스 주소를 바꾸는 리팩터링(모듈로 이동, 이름 변경)은 `terraform state mv`나 `moved` 블록으로 처리한다. 이를 하지 않고 HCL 주소만 바꾸면 Terraform은 옛 주소 리소스를 삭제하고 새 주소로 재생성하려 해 다운타임이 발생한다. `moved` 블록은 "이 리소스는 삭제·재생성이 아니라 주소만 옮겨졌다"고 알려 state를 안전하게 갱신한다.

```hcl
moved {
  from = aws_instance.web
  to   = module.compute.aws_instance.web   # 삭제 없이 주소만 이전
}
```

핵심 교훈은 state가 Terraform의 심장이며, 협업에서는 원격 백엔드+잠금이 필수, 운영에서는 드리프트 감지와 명확한 소유권 경계, 리팩터링에서는 import·moved로 state를 안전하게 다루는 것이다. state를 직접 손으로 편집(`tfstate` 파일 수정)하는 것은 최후의 수단이며, 반드시 백업 후 `state` 하위 명령으로 다루는 것이 정석이다.

## 9. CI/CD 파이프라인과 상태 보안

Terraform을 CI에서 안전하게 돌리려면 몇 가지 원칙을 지킨다. 첫째, **plan과 apply를 분리**한다. PR 단계에서 `terraform plan`을 돌려 변경 계획을 코멘트로 노출하고 리뷰를 받는다. 병합 후에만 `terraform apply`를 실행한다. 저장된 plan 파일(`-out=tfplan`)을 apply에 넘겨 리뷰한 계획과 적용된 계획이 동일함을 보장한다.

```yaml
# GitHub Actions 예시 (개념)
plan:
  steps:
    - run: terraform plan -out=tfplan -detailed-exitcode
    - run: terraform show -no-color tfplan > plan.txt   # PR 코멘트용
apply:            # main 병합 후에만
  needs: plan
  steps:
    - run: terraform apply -auto-approve tfplan
```

둘째, **인증은 단기 자격증명**으로 한다. 장기 액세스 키를 CI 시크릿에 넣지 말고 OIDC 페더레이션으로 클라우드가 CI 러너에 임시 토큰을 발급하게 한다. 이러면 유출 시 피해가 토큰 수명으로 제한된다. 셋째, **state 자체를 보호**한다. state에는 RDS 비밀번호, 인증서 개인키 같은 민감 값이 평문으로 저장될 수 있으므로, 백엔드 저장소 암호화(S3 SSE), 접근 IAM 최소권한, state 버킷 버전 관리·MFA delete를 켜다. state 버전 관리는 apply 사고로 state가 손상됐을 때 이전 버전으로 복구하는 안전망이다.

넷째, **동시성 제어**를 파이프라인에도 적용한다. 같은 환경의 apply 잡이 병렬로 돌지 않도록 CI concurrency group을 걸어 큐잉한다. Terraform 상태 락이 저수준 방어라면, CI concurrency는 그 앞단에서 애초에 동시 실행을 막는 상위 방어다. 두 계층이 함께 있어야 "락 대기로 실패한 잡이 재시도로 몰려 경합"하는 상황도 예방된다. 마지막으로 `terraform validate`와 `fmt -check`, `tflint`, `checkov`(보안 정책 검사)를 plan 이전 단계에 넣어, 문법·스타일·보안 위반을 apply 훨씬 전에 걸러낸다. 이 전체 파이프라인이 IaC를 "코드처럼" 리뷰·검증·감사 가능하게 만드는 실무 표준이다.

## 참고

- Terraform Documentation — State, Backends, State Locking
- Terraform Documentation — Import, `moved` blocks, `terraform state mv`
- HashiCorp, "Manage Resource Drift" 가이드 (detailed-exitcode, refresh)
- "Terraform: Up & Running" (Yevgeniy Brikman) — 상태 분리·협업 장
