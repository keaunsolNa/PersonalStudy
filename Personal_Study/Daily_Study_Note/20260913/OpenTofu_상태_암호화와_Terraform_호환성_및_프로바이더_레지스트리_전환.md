Notion 원본: https://app.notion.com/p/3da5a06fd6d38163b2ecdea0d6ed38c4?pvs=204

# OpenTofu 상태 암호화와 Terraform 호환성 및 프로바이더 레지스트리 전환

> 2026-09-13 신규 주제 · 확장 대상: Terraform 모듈 설계 / State Drift 관리

## 학습 목표

- 상태 파일이 평문 비밀을 담는 구조적 이유와 기존 완화책의 한계를 규명한다
- OpenTofu 의 클라이언트 측 상태 암호화를 키 공급자별로 구성한다
- Terraform 에서의 이전 경로와 버전 호환 경계를 판단한다
- 레지스트리 차이가 CI 파이프라인에 미치는 영향을 점검한다

## 1. 상태 파일에 비밀이 남는 이유

Terraform/OpenTofu 의 상태 파일은 관리 대상 리소스의 **현재 속성 전체**를 담는다. 이것이 설계의 핵심이다. 다음 계획을 세울 때 실제 인프라 대신 상태를 참조해야 API 호출 수를 줄일 수 있고, 삭제 순서를 결정할 의존 그래프도 여기서 나온다.

문제는 속성 중에 비밀이 섞인다는 것이다.

```hcl
resource "aws_db_instance" "main" {
  identifier = "app-prod"
  engine     = "postgres"
  username   = "app"
  password   = var.db_password
}
```

`password` 는 상태 파일에 평문으로 들어간다. 프로바이더가 `Sensitive: true` 로 표시해도 그것은 **CLI 출력에서 가리는** 표시일 뿐, 저장 형식에는 영향이 없다.

```json
{
  "resources": [{
    "type": "aws_db_instance",
    "instances": [{
      "attributes": {
        "username": "app",
        "password": "P@ssw0rd-prod-2026"
      }
    }]
  }]
}
```

`random_password`, `tls_private_key`, Kubernetes Secret, 서비스 계정 키도 마찬가지다. 심지어 값을 Vault 에서 읽어 와도 읽힌 값이 상태에 기록된다.

지금까지의 완화책은 **저장소 쪽 암호화**였다.

| 방법 | 보호 범위 | 남는 구멍 |
|---|---|---|
| S3 SSE-KMS | 디스크 저장 시 | S3 읽기 권한자는 평문을 봄 |
| Terraform Cloud | 서비스 내부 | 서비스 운영자 신뢰 필요 |
| 로컬 파일 + 디스크 암호화 | 도난 시 | 실행 머신에서 평문 |

공통점은 **전송받은 클라이언트가 항상 평문을 본다**는 것이다. CI 러너, 개발자 노트북, 디버깅 중 실수로 아티팩트에 올라간 `terraform.tfstate` 모두 평문이다. `terraform state pull > /tmp/state.json` 한 줄이면 끝이다.

## 2. 클라이언트 측 상태 암호화

OpenTofu 1.7 이 도입한 상태 암호화는 이 지점을 겨냥한다. 상태가 **백엔드로 나가기 전에** 암호화되고, 읽어 들인 직후 복호화된다. 디스크에도, 원격 저장소에도, 전송 중에도 암호문만 존재한다.

```hcl
terraform {
  encryption {
    key_provider "pbkdf2" "mykey" {
      passphrase = var.state_passphrase
    }

    method "aes_gcm" "default" {
      keys = key_provider.pbkdf2.mykey
    }

    state {
      method = method.aes_gcm.default
    }

    plan {
      method = method.aes_gcm.default
    }
  }
}
```

구성 요소가 셋이다.

**key_provider** — 암호화 키를 어디서 얻는가. `pbkdf2`(패스프레이즈 유도), `aws_kms`, `gcp_kms`, `openbao`/`vault` 를 지원한다.

**method** — 어떤 알고리즘으로 암호화하는가. `aes_gcm` 이 실질적 유일 선택지이고, `unencrypted` 는 이전용 특수 메서드다.

**state / plan** — 무엇을 암호화하는가. 계획 파일(`.tfplan`)도 리소스 속성을 담으므로 함께 켜야 의미가 있다.

암호화된 상태 파일은 이렇게 보인다.

```json
{
  "encrypted_data": "yT4z...base64...",
  "encryption_version": "v0",
  "meta": {
    "key_provider.pbkdf2.mykey": "eyJzYWx0IjoiLi4uIn0="
  }
}
```

`meta` 에 키 유도용 salt 같은 비밀이 아닌 파라미터가 들어간다. 키 자체는 절대 저장되지 않는다.

`pbkdf2` 는 설정이 가장 쉽지만 **패스프레이즈 관리 문제를 다른 곳으로 옮길 뿐**이다. 프로덕션에서는 KMS 계열을 쓴다.

```hcl
terraform {
  encryption {
    key_provider "aws_kms" "prod" {
      kms_key_id = "arn:aws:kms:ap-northeast-2:123456789012:key/abcd-1234"
      region     = "ap-northeast-2"
      key_spec   = "AES_256"
    }

    method "aes_gcm" "prod" {
      keys = key_provider.aws_kms.prod
    }

    state {
      method = method.aes_gcm.prod
    }
  }
}
```

이 구성에서는 KMS 키에 대한 `Decrypt` 권한이 없으면 상태를 읽을 수 없다. S3 버킷을 통로 읽어도 암호문만 얻는다. 권한 경계가 **저장소 권한에서 KMS 권한으로** 옮겨간 것이 본질적 개선이다.

## 3. 봉투 암호화와 키 회전

KMS 키 공급자는 봉투 암호화(envelope encryption)를 쓴다. KMS 가 데이터 키를 생성해 평문 키와 암호화된 키를 함께 주면, 평문 키로 상태를 암호화하고 암호화된 키를 `meta` 에 넣는다. 복호화 시에는 `meta` 의 암호화된 키를 KMS 에 보내 평문 키를 받는다.

이 구조 덕분에 상태 크기와 무관하게 KMS 호출이 1회다. KMS 는 4KB 이상을 직접 암호화하지 못하므로 사실상 필수적인 설계다.

키 회전은 `fallback` 으로 처리한다.

```hcl
terraform {
  encryption {
    key_provider "aws_kms" "old" {
      kms_key_id = "arn:...:key/old-key"
      region     = "ap-northeast-2"
      key_spec   = "AES_256"
    }

    key_provider "aws_kms" "new" {
      kms_key_id = "arn:...:key/new-key"
      region     = "ap-northeast-2"
      key_spec   = "AES_256"
    }

    method "aes_gcm" "old" {
      keys = key_provider.aws_kms.old
    }

    method "aes_gcm" "new" {
      keys = key_provider.aws_kms.new
    }

    state {
      method = method.aes_gcm.new

      fallback {
        method = method.aes_gcm.old
      }
    }
  }
}
```

동작 규칙은 단순하다. **읽기는 주 메서드로 시도하고 실패하면 fallback 으로**, **쓰기는 항상 주 메서드로**. 따라서 이 설정으로 `tofu apply` 를 한 번 돌리면 상태가 새 키로 다시 쓰인다. 모든 워크스페이스가 넘어간 것을 확인한 뒤 `fallback` 블록과 구 키 공급자를 제거한다.

같은 메커니즘으로 암호화를 **켜고 끈다**.

```hcl
// 1단계: 암호화 도입 — 기존 평문 상태를 읽을 수 있어야 함
state {
  method = method.aes_gcm.default
  fallback {
    method = method.unencrypted.migrate
  }
}
```

```hcl
// 2단계: 안정화 후 fallback 제거
state {
  method = method.aes_gcm.default
}
```

해제는 반대다. 주 메서드를 `unencrypted` 로 두고 fallback 을 `aes_gcm` 으로 둔 뒤 apply 한다.

`enforced = true` 를 주면 fallback 이 있어도 평문 읽기를 거부한다. 이전이 끝난 환경에서 실수로 평문 상태가 섞여 들어오는 것을 막는 안전장치다.

## 4. 환경변수 설정과 CI

HCL 에 키 정보를 넣지 않고 환경변수로 전달할 수 있다. CI 에서 주로 이 방식을 쓴다.

```bash
export TF_ENCRYPTION=$(cat <<'EOF'
key_provider "aws_kms" "ci" {
  kms_key_id = "arn:aws:kms:ap-northeast-2:123456789012:key/abcd-1234"
  region     = "ap-northeast-2"
  key_spec   = "AES_256"
}
method "aes_gcm" "ci" {
  keys = key_provider.aws_kms.ci
}
state {
  method = method.aes_gcm.ci
}
plan {
  method = method.aes_gcm.ci
}
EOF
)

tofu init
tofu plan -out=tfplan
```

`TF_ENCRYPTION` 의 내용은 코드의 `encryption` 블록과 **병합**된다. 같은 이름이 있으면 환경변수 쪽이 이긴다.

GitHub Actions 라면 OIDC 페더레이션으로 KMS 권한을 받는 구성이 자연스럽다.

```yaml
jobs:
  plan:
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789012:role/tofu-ci
          aws-region: ap-northeast-2
      - uses: opentofu/setup-opentofu@v1
      - run: tofu init
      - run: tofu plan -out=tfplan
        env:
          TF_ENCRYPTION: ${{ vars.TF_ENCRYPTION_CONFIG }}
```

여기서 놓치기 쉬운 것: **계획 파일을 아티팩트로 올린다면 plan 암호화가 반드시 켜져 있어야 한다**. GitHub Actions 아티팩트는 저장소 읽기 권한자에게 노출된다. plan 암호화가 없으면 상태 암호화를 해 놓고 계획 파일로 비밀을 흘리는 셈이 된다.

## 5. Terraform 과의 호환 경계

OpenTofu 는 Terraform 1.5.x 에서 포크됐다. 1.6 까지는 사실상 드롭인 대체였고, 이후 양쪽이 독자 기능을 추가하며 갈라졌다.

| 기능 | OpenTofu | Terraform |
|---|---|---|
| 상태 암호화 | 1.7+ | 없음 |
| `provider` 블록의 `for_each` | 1.9+ | 없음 |
| 변수·로컬의 `.tf` 시점 평가 확장 | 1.8+ | 제한적 |
| Stacks | 없음 | 있음(클라우드 연동) |
| 라이선스 | MPL 2.0 | BUSL 1.1 |

이전 시 확인할 실무 항목은 넷이다.

**상태 파일 포맷.** Terraform 1.5 까지의 상태는 OpenTofu 가 그대로 읽는다. Terraform 1.6 이상에서 쓴 상태도 대개 호환되지만, 상태에 기록되는 `terraform_version` 이 더 높으면 OpenTofu 가 거부할 수 있다. 이 경우 상태를 내려받아 필드를 조정한다.

```bash
terraform state pull > state.json
jq '.terraform_version = "1.5.7"' state.json > state-fixed.json
tofu state push state-fixed.json
```

이 작업은 되돌리기 어려우므로 **반드시 백업 후** 진행하고, 비프로덕션에서 먼저 검증한다.

**프로바이더 출처 주소.** `hashicorp/aws` 같은 주소는 양쪽에서 각자의 레지스트리로 해석된다. OpenTofu 는 `registry.opentofu.org` 를, Terraform 은 `registry.terraform.io` 를 본다. 같은 프로바이더 바이너리를 미러링하고 있으므로 대부분 문제가 없다.

**잠금 파일.** `.terraform.lock.hcl` 은 프로바이더 해시를 담는데, 두 레지스트리가 배포하는 바이너리의 해시가 다를 수 있다. 이전 시 잠금 파일을 재생성하는 것이 안전하다.

```bash
rm .terraform.lock.hcl
tofu init
```

**BUSL 적용 시점.** Terraform 1.5.x 이하는 MPL 2.0 이므로 자유롭게 쓸 수 있다. 1.6 이상은 BUSL 이며 "경쟁 제품 제공"을 제한한다. 내부 인프라 관리 용도의 사용은 제한 대상이 아니라는 것이 HashiCorp 의 FAQ 입장이지만, 법무 검토가 필요한 사안이면 조직의 판단을 따라야 한다.

## 6. 레지스트리 구조의 차이

OpenTofu 레지스트리는 **GitHub 저장소 기반의 정적 인덱스**다. 프로바이더 목록이 공개 Git 저장소에 JSON 으로 있고, 바이너리는 원 저장소의 릴리스 아티팩트를 가리킨다. Terraform 레지스트리는 HashiCorp 가 운영하는 서비스다.

실무에 영향을 주는 차이 셋:

**서명 검증.** 두 레지스트리 모두 GPG 서명을 검증한다. OpenTofu 는 서명 키를 레지스트리 저장소에 커밋된 형태로 관리하므로 변경 이력이 공개된다.

**신규 프로바이더 등록 속도.** OpenTofu 쪽은 PR 기반이라 상황에 따라 지연이 있을 수 있다. 사내 프로바이더나 마이너한 프로바이더를 쓴다면 미리 확인한다.

**네트워크 격리 환경.** 폐쇄망에서는 어느 쪽이든 미러가 필요하다. 설정 방식은 동일하다.

```hcl
# .terraformrc 또는 tofurc
provider_installation {
  filesystem_mirror {
    path    = "/opt/tofu/providers"
    include = ["registry.opentofu.org/*/*"]
  }
  direct {
    exclude = ["registry.opentofu.org/*/*"]
  }
}
```

```bash
tofu providers mirror /opt/tofu/providers
```

## 7. 모듈 레지스트리와 소스 주소

모듈 소스는 레지스트리 의존도가 낮다. Git URL 을 직접 쓰면 양쪽 모두 동일하게 동작한다.

```hcl
module "vpc" {
  source = "git::https://github.com/example/tf-modules.git//vpc?ref=v2.3.0"

  cidr_block = "10.0.0.0/16"
}
```

`ref` 에 태그를 고정하는 것이 중요하다. 브랜치를 가리키면 재현성이 사라지고, `tofu init -upgrade` 때마다 다른 코드가 내려온다.

레지스트리 주소를 쓴다면 OpenTofu 는 `registry.opentofu.org` 를 기본으로 삼는다.

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"
}
```

버전 목록이 완전히 일치한다는 보장은 없으므로, 버전 제약을 느슨하게(`~>`) 두었다면 이전 직후 `tofu init` 결과의 버전을 확인한다.

## 8. 도입 판단

상태 암호화 하나만으로 전환을 정당화할 수 있는가? 조건에 따라 다르다.

**전환 이득이 큰 경우** — 상태에 데이터베이스 비밀번호나 개인 키가 들어가고, 상태 저장소 접근 권한이 넓거나 감사 대상인 조직. 금융·의료처럼 "저장 데이터에 평문 자격증명이 없어야 한다"는 요구가 명문화된 환경에서는 대안이 사실상 없다.

**전환이 급하지 않은 경우** — 모든 비밀을 Secrets Manager 참조로만 다루고 상태에 값이 들어가지 않도록 이미 설계했으며, 상태 저장소 접근이 소수로 통제된 환경.

두 번째 상태를 만드는 것이 이상적이지만, 현실에서는 프로바이더가 강제로 값을 상태에 넣는 리소스가 남는다. `aws_db_instance.password` 는 `manage_master_user_password = true` 로 피할 수 있지만, 모든 리소스에 그런 우회가 있지는 않다.

병행 전략도 가능하다. 상태 암호화가 필요한 워크스페이스만 OpenTofu 로 옮기고 나머지는 유지하는 것이다. 상태 파일이 워크스페이스 단위로 독립적이므로 기술적 장벽은 낮다. 대신 팀이 두 개의 CLI 를 관리해야 하고, `tfenv` 같은 버전 관리 도구가 양쪽을 다루도록 설정이 필요하다. 장기적으로는 한쪽으로 수렴시키는 편이 운영 부담이 적다.

## 참고

- OpenTofu Documentation — State Encryption (https://opentofu.org/docs/language/state/encryption/)
- OpenTofu — Migrating from Terraform (https://opentofu.org/docs/intro/migration/)
- OpenTofu Registry (https://github.com/opentofu/registry)
- HashiCorp — Terraform State Sensitive Data (https://developer.hashicorp.com/terraform/language/state/sensitive-data)
- NIST SP 800-57 Part 1 Rev. 5 — Key Management Recommendations
