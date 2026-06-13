Notion 원본: https://www.notion.so/37e5a06fd6d3819fa46dd18be5fe20fa

# Terraform State와 Plan · Apply 내부 및 Dependency Graph · Drift

> 2026-06-13 신규 주제 · 확장 대상: DevOps

## 학습 목표

- Terraform state가 무엇을 저장하고 왜 필요한지, 실제 인프라와의 관계를 설명한다
- refresh → plan → apply 파이프라인에서 desired/prior/actual 상태가 어떻게 비교되는지 추적한다
- 리소스 의존성 그래프(DAG)가 생성·삭제·병렬화 순서를 결정하는 방식을 이해한다
- drift, state lock, 부분 실패 같은 운영 위험을 진단하고 대응한다

## 1. State의 역할

Terraform은 선언적 IaC다. `.tf` 파일은 "원하는 최종 상태(desired state)"를 기술할 뿐, 그것을 만들려면 현재 실제 인프라가 어떤지 알아야 한다. **state 파일**(`terraform.tfstate`)은 Terraform이 관리하는 리소스와 실제 클라우드 객체(예: AWS 인스턴스 ID) 사이의 매핑을 저장한다. state가 없으면 Terraform은 "이 코드 블록이 어떤 실제 리소스에 대응하는지" 알 수 없어, 매번 모든 것을 새로 만들려 들 것이다.

state는 또한 리소스 속성·의존성·메타데이터를 캐시해 plan 속도를 높이고, 코드에 없는 정보(예: 클라우드가 할당한 ID, 출력값)를 보존한다. 민감 정보(비밀번호, 키)가 평문으로 들어갈 수 있어 암호화된 원격 백엔드 저장이 필수다.

## 2. 원격 백엔드와 State Lock

여러 사람이 동시에 apply하면 state가 손상될 수 있으므로, 원격 백엔드(S3+DynamoDB, Terraform Cloud, GCS 등)는 **상태 잠금(lock)**을 건다. apply 시작 시 락을 획득하고 끝나면 해제한다.

```hcl
terraform {
  backend "s3" {
    bucket         = "my-tfstate"
    key            = "prod/network.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "tf-locks"   # 잠금 조정용
    encrypt        = true
  }
}
```

apply가 비정상 종료해 락이 남으면 후속 실행이 "state locked"로 막힌다. 다른 실행이 정말 없음을 확인한 뒤에만 `terraform force-unlock <LOCK_ID>`로 해제해야 하며, 살아있는 실행 중에 강제 해제하면 동시 쓰기로 state가 깨진다.

## 3. Refresh — prior state와 actual state 동기화

plan의 첫 단계는 refresh다. state에 기록된 각 리소스에 대해 provider API를 호출(read)해 클라우드의 **실제 현재 값(actual state)**을 가져와 state(prior state)를 갱신한다. 누군가 콘솔에서 보안그룹 규칙을 손으로 바꾸었다면, refresh가 그 변경을 감지한다. Terraform 0.15.4+의 `-refresh-only` 모드는 코드 변경 없이 이 동기화만 수행해 drift를 state에 반영한다.

```bash
terraform plan -refresh-only   # 실제 인프라 변경 없이 drift만 확인
terraform apply -refresh-only  # state를 실제에 맞춰 갱신(코드 미적용)
```

## 4. Plan — 세 상태의 3-way diff

plan은 본질적으로 3-way 비교다: **prior state**(refresh로 갱신된 현재 기록), **configuration**(코드의 desired state), 그리고 두 차이를 메우는 **계획된 액션**. Terraform은 각 리소스 속성을 비교해 create / update in-place / destroy-and-recreate(replace) / no-op 중 하나로 분류한다.

```
+ create        코드엔 있으나 state에 없음
~ update         속성 변경, 그 자리에서 수정 가능
-/+ replace      불변 속성 변경 → 삭제 후 재생성
- destroy        state에 있으나 코드에서 제거됨
```

replace가 발생하는 이유는 일부 속성이 클라우드에서 불변(예: EC2의 일부 네트워크 속성)이라 in-place 수정이 불가능하기 때문이다. `create_before_destroy` lifecycle로 다운타임을 줄이거나, 의도치 않은 replace는 코드/리소스 설계를 재검토해야 한다.

## 5. Dependency Graph — 실행 순서의 결정

Terraform은 리소스·데이터소스·provider를 노드로 하는 **방향 비순환 그래프(DAG)**를 만든다. 엣지는 의존성으로, 명시적(`depends_on`)이거나 참조에 의한 암시적(`subnet_id = aws_subnet.x.id`) 의존이다. apply는 이 그래프를 위상정렬해, 의존 대상이 먼저 생성되도록 순서를 정하고 의존관계가 없는 노드는 병렬로 처리한다(기본 동시성 10, `-parallelism`로 조정).

```bash
terraform graph | dot -Tsvg > graph.svg   # 의존성 그래프 시각화
```

삭제 시에는 그래프를 **역순**으로 순회한다 — 의존하는 쪽을 먼저 지워야 의존 대상이 안전하게 삭제된다(예: 인스턴스를 지운 뒤 서브넷 삭제). 순환 의존이 있으면 위상정렬이 불가능해 `Cycle` 에러가 나며, 보통 모듈 간 양방향 참조가 원인이다.

## 6. Apply와 부분 실패

apply는 plan이 만든 액션 그래프를 실행한다. 클라우드 API 호출은 멱등하지 않을 수 있어, 중간에 실패하면 일부 리소스만 생성된 상태로 남는다. Terraform은 성공한 리소스를 그때그때 state에 기록하므로, 재실행하면 이미 만든 것은 건너뛰고 실패 지점부터 이어간다. 단, 생성은 됐으나 state 기록 전에 죽으면 "orphan"(실제 존재하나 state엔 없음)이 생겨 다음 plan이 중복 생성을 시도할 수 있다 — 이때 `terraform import`로 기존 객체를 state에 편입시킨다.

```bash
terraform import aws_instance.web i-0abc123   # 실존 리소스를 state에 매핑
```

## 7. Drift 감지와 대응

drift는 코드/Terraform을 거치지 않고 실제 인프라가 바뀜 상태다. 원인은 수동 콘솔 변경, 다른 자동화, 클라우드 측 자동 변경 등이다. 정기적으로 `plan -refresh-only` 또는 `plan`을 CI에서 돌려 drift를 탐지한다. 대응은 두 갈래다: (1) 코드를 정답으로 보고 `apply`해 실제를 코드에 맞추거나, (2) 실제를 정답으로 인정해 코드를 수정한다. drift를 방치하면 다음 apply가 의도치 않게 수동 변경을 되돌려 장애를 부를 수 있다.

| 상황 | 권장 대응 |
|---|---|
| 긴급 핫픽스를 콘솔에서 함 | 동일 변경을 코드에 반영(코드가 정답) |
| 외부 자동화가 태그 추가 | `ignore_changes`로 해당 속성 제외 |
| 누군가 리소스를 수동 삭제 | apply로 재생성 또는 코드에서 제거 |

## 8. 모듈·count·for_each와 그래프 주소

리소스는 `module.<name>.<type>.<name>` 같은 주소로 그래프에서 식별된다. `count`로 만든 리소스는 인덱스 주소(`aws_instance.web[0]`)를, `for_each`는 키 주소(`aws_instance.web["api"]`)를 갖는다. 이 차이가 운영에서 중요하다 — `count`는 리스트 중간 원소를 제거하면 뒤 원소들의 인덱스가 밀려, 의도치 않게 다수 리소스가 destroy/recreate된다.

```hcl
# 위험: 목록 중간 삭제 시 인덱스가 재배치되어 연쇄 재생성
resource "aws_instance" "web" {
  count = length(var.names)
}

# 안전: 키가 안정적이라 한 원소만 추가/삭제됨
resource "aws_instance" "web" {
  for_each = toset(var.names)
  tags     = { Name = each.key }
}
```

집합 멤버십이 바뀜는 리소스에는 `for_each`를 기본으로 택하는 것이 blast radius를 줄인다. 잘못 만들어진 인덱스 기반 리소스는 `terraform state mv`로 새 키 주소로 옮겨 재생성을 피할 수 있다.

## 9. 출력값·원격 state 참조와 결합도

모듈 간 데이터 전달은 `output`과 입력 변수로 한다. 다른 state의 값을 읽어야 하면 `terraform_remote_state` 데이터소스를 쓰지만, 이는 state 간 강한 결합을 만들어 한 state의 변경이 다른 쪽 plan에 영향을 준다.

```hcl
data "terraform_remote_state" "network" {
  backend = "s3"
  config  = { bucket = "my-tfstate", key = "prod/network.tfstate", region = "ap-northeast-2" }
}

resource "aws_instance" "app" {
  subnet_id = data.terraform_remote_state.network.outputs.subnet_id
}
```

더 느슨한 대안은 SSM Parameter Store/data source로 클라우드 측 값을 직접 조회하는 것이다 — state 간 직접 의존을 끊어 독립 배포가 가능해진다. 결합도와 편의의 trade-off를 환경 규모에 맞춰 선택한다.

## 10. 운영 trade-off와 모범 사례

state는 강력하지만 단일 실패점이자 보안 민감점이다. 모범 사례는 (1) 원격 암호화 백엔드 + 잠금 사용, (2) 환경·도메인별 state 분리(거대한 단일 state는 plan 지연·blast radius 확대를 부름), (3) `terraform state` 서브커맨드로의 직접 편집은 최후 수단으로만, (4) CI에서 plan 결과를 PR에 첨부해 리뷰 후 apply(GitOps), (5) `lifecycle { prevent_destroy = true }`로 핵심 리소스 보호다. 핵심 통찰은 Terraform이 "코드 ↔ state ↔ 실제"라는 삼각관계를 일관되게 유지하는 도구라는 점이며, 이 세 꼭짓점 중 하나라도 다른 경로로 벌어지면(특히 수동 변경) 위험이 시작된다는 것이다.

## 참고

- Terraform — State: https://developer.hashicorp.com/terraform/language/state
- Terraform — Resource Graph: https://developer.hashicorp.com/terraform/internals/graph
- Terraform — Refresh-only & Drift: https://developer.hashicorp.com/terraform/cli/commands/plan
- Terraform — Backend & State Locking: https://developer.hashicorp.com/terraform/language/backend
