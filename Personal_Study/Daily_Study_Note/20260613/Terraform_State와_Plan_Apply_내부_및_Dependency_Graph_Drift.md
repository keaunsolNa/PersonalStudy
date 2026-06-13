Notion 원본: https://www.notion.so/37e5a06fd6d3819fa46dd18be5fe20fa

# Terraform State와 Plan · Apply 내부 및 Dependency Graph · Drift

> 2026-06-13 신규 주제 · 확장 대상: DevOps

## 학습 목표

- Terraform state가 무엇을 저장하고 왜 필요한지, 실제 인프라와의 관계를 설명한다
- refresh → plan → apply 파이프라인에서 desired/prior/actual 상태가 어떻게 비교되는지 추적한다
- 리소스 의존성 그래프(DAG)가 생성·삭제·병렬화 순서를 결정하는 방식을 이해한다
- drift, state lock, 부분 실패 같은 운영 위험을 진단·대응한다

## 1. State의 역할

Terraform은 선언적 IaC다. .tf 파일은 원하는 최종 상태(desired)를 기술할 뿐이고, 그것을 만들려면 현재 실제 인프라를 알아야 한다. state 파일은 Terraform이 관리하는 리소스와 실제 클라우드 객체(예: AWS 인스턴스 ID) 사이의 매핑을 저장한다. state가 없으면 이 코드 블록이 어떤 실제 리소스에 대응하는지 알 수 없어 매번 새로 만들려 든다. 민감 정보가 평문으로 들어갈 수 있어 암호화된 원격 백엔드 저장이 필수다.

## 2. 원격 백엔드와 State Lock

```hcl
terraform {
  backend "s3" {
    bucket         = "my-tfstate"
    key            = "prod/network.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "tf-locks"
    encrypt        = true
  }
}
```

apply 시작 시 락을 획득하고 끝나면 해제한다. 비정상 종료로 락이 남으면 `terraform force-unlock <LOCK_ID>`로 해제하되, 살아있는 실행 중 강제 해제하면 state가 깨진다.

## 3. Refresh — prior와 actual 동기화

plan의 첫 단계는 refresh다. state에 기록된 각 리소스에 대해 provider API를 호출해 실제 현재 값을 가져와 state를 갱신한다. 누군가 콘솔에서 손으로 바꿨다면 refresh가 그 변경을 감지한다.

```bash
terraform plan -refresh-only   # 실제 변경 없이 drift만 확인
terraform apply -refresh-only  # state를 실제에 맞춤 갱신
```

## 4. Plan — 세 상태의 3-way diff

plan은 prior state, configuration, 그 차이를 메우는 계획된 액션의 3-way 비교다.

```
+ create     코드엔 있으나 state에 없음
~ update     속성 변경, 그 자리에서 수정
-/+ replace  불변 속성 변경 → 삭제 후 재생성
- destroy    state에 있으나 코드에서 제거됨
```

replace는 일부 속성이 클라우드에서 불변이라 in-place 수정이 불가하기 때문이다. `create_before_destroy`로 다운타임을 줄인다.

## 5. Dependency Graph — 실행 순서의 결정

Terraform은 리소스·데이터소스·provider를 노드로 하는 DAG를 만든다. 엣지는 명시적(`depends_on`)이거나 참조에 의한 암시적(`subnet_id = aws_subnet.x.id`) 의존이다. apply는 위상정렬해 의존 대상이 먼저 생성되게 하고 의존 없는 노드는 병렬 처리한다(기본 10, `-parallelism`).

```bash
terraform graph | dot -Tsvg > graph.svg
```

삭제 시엔 그래프를 역순 순회한다. 순환 의존이 있으면 Cycle 에러가 나며 보통 모듈 간 양방향 참조가 원인이다.

## 6. Apply와 부분 실패

apply는 성공한 리소스를 그때그때 state에 기록하므로, 재실행하면 이미 만든 것은 건너뛰고 실패 지점부터 이어간다. 생성은 됐으나 state 기록 전에 죽으면 orphan이 생겨 중복 생성을 시도할 수 있다.

```bash
terraform import aws_instance.web i-0abc123
```

## 7. Drift 감지와 대응

| 상황 | 권장 대응 |
|---|---|
| 긴급 핫픽스를 콘솔에서 함 | 동일 변경을 코드에 반영(코드가 정답) |
| 외부 자동화가 태그 추가 | ignore_changes로 해당 속성 제외 |
| 누군가 리소스를 수동 삭제 | apply로 재생성 또는 코드에서 제거 |

drift를 방치하면 다음 apply가 의도치 않게 수동 변경을 되돌려 장애를 부를 수 있으므로 CI에서 정기 탐지한다.

## 8. 모듈·count·for_each와 그래프 주소

```hcl
# 위험: 목록 중간 삭제 시 인덱스 재배치로 연쇄 재생성
resource "aws_instance" "web" { count = length(var.names) }

# 안전: 키가 안정적이라 한 원소만 추가/삭제
resource "aws_instance" "web" {
  for_each = toset(var.names)
  tags     = { Name = each.key }
}
```

집합 멤버십이 바뀌는 리소스엔 `for_each`를 기본으로 택해 blast radius를 줄인다. 잘못 만들어진 인덱스 리소스는 `terraform state mv`로 키 주소로 옮겨 재생성을 피한다.

## 9. 출력값·원격 state 참조와 결합도

```hcl
data "terraform_remote_state" "network" {
  backend = "s3"
  config  = { bucket = "my-tfstate", key = "prod/network.tfstate", region = "ap-northeast-2" }
}
resource "aws_instance" "app" {
  subnet_id = data.terraform_remote_state.network.outputs.subnet_id
}
```

`terraform_remote_state`는 state 간 강한 결합을 만든다. 더 느슨한 대안은 SSM Parameter Store/data source로 클라우드 측 값을 직접 조회해 state 간 의존을 끊는 것이다.

## 10. 운영 trade-off와 모범 사례

state는 강력하지만 단일 실패점이자 보안 민감점이다. 모범 사례는 원격 암호화 백엔드 + 잠금, 환경·도메인별 state 분리(거대 단일 state는 plan 지연·blast radius 확대), state 직접 편집은 최후 수단, CI에서 plan 결과를 PR에 첨부해 리뷰 후 apply(GitOps), `prevent_destroy`로 핵심 리소스 보호다. 핵심은 Terraform이 코드 ↔ state ↔ 실제라는 삼각관계를 일관되게 유지하는 도구이며, 한 궤점이라도 다른 경로로 벌어지면(특히 수동 변경) 위험이 시작된다는 것이다.

## 참고

- Terraform — State: https://developer.hashicorp.com/terraform/language/state
- Terraform — Resource Graph: https://developer.hashicorp.com/terraform/internals/graph
- Terraform — Plan & Drift: https://developer.hashicorp.com/terraform/cli/commands/plan
- Terraform — Backend & State Locking: https://developer.hashicorp.com/terraform/language/backend
