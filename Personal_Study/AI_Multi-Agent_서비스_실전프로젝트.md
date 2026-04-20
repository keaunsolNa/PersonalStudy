# AI Multi-Agent 서비스 실전프로젝트

> Notion 원본: <https://www.notion.so/2a85a06fd6d3801ab347c45b084a6673>
> 동기화일: 2026-04-21

> 이미지 다운로드 실패 알림: 본 환경의 HTTP 프록시 정책상 S3 호스트 접근이 차단되어 이미지를 로컬로 내려받지 못했습니다. 본 문서 내부의 `![](https://...)` 링크는 Notion 원본 Pre-signed URL을 유지하며 약 1시간 내 만료됩니다.

> 본 문서는 Notion 허브 페이지의 100개 이상 하위 페이지 중 핵심 8개 페이지(개요/협업/Hexagonal Architecture/RAG)를 1-depth까지 전개한 것이며, 나머지는 하단의 링크 목록으로 정리되어 있습니다.

## 메인 페이지 구성 (원본 컬럼 구조)

### 협업
- [Slack · GitHub · Notion 통합 프로젝트 운영 가이드](https://www.notion.so/2e25a06fd6d381a4b821ed2425721fed)
- [GitHub 커밋 메시지 작성 가이드](https://www.notion.so/2e25a06fd6d381668901c57b65b83717)
- [백로그 작성 가이드](https://www.notion.so/2e25a06fd6d381a2881be91189d97a2a)
- [이슈 및 질문 발행 가이드 (컨텍스트 공유 중심)](https://www.notion.so/2e25a06fd6d38104af8def3ca9735571)
- [애자일 프로세스](https://www.notion.so/2e25a06fd6d381049c87c9914e0657bb)

### 개요
- [Multi-Agent 서비스 개발: 기술 관점 vs 비즈니스 관점](https://www.notion.so/2e25a06fd6d381069432e3ca48bcbf80)
- [Multi-Agent 서비스 개발: 애자일 관점](https://www.notion.so/2e25a06fd6d3817ebba4c2c7985fb842)

### 환경 설정
- [PyCharm (python 개발 환경) 구성](https://www.notion.so/2e25a06fd6d381dc9f21f712b2778f77)
- [Git Kraken 설치](https://www.notion.so/2e25a06fd6d381469b49c3083f27f817)
- [vscode 설치](https://www.notion.so/2e25a06fd6d3812bb03ae6cb1bf5cbff)
- [코드 관리를 위해 Github Desktop 설치](https://www.notion.so/2e25a06fd6d38129b4dbd1a1e2023a9f)
- [수업 저장소 설정](https://www.notion.so/2e25a06fd6d38188b8bdfbd735563046)
- [Github 저장소와 Github Desktop 연동](https://www.notion.so/2e25a06fd6d38155848cfd88c4a8e793)
- [PyCharm과 Github 연동](https://www.notion.so/2e25a06fd6d381efb46ce39595b1f709)
- [Anaconda 설치 이후 최종 연동](https://www.notion.so/2e25a06fd6d381f196d4c135a9d53e11)
- [첫 번째 작업에 대해 커밋 넣어보기](https://www.notion.so/2e25a06fd6d3815a81b1ea5c50e07450)
- [첫 번째 프로젝트 환경 구성하기](https://www.notion.so/2e25a06fd6d38145955bcc5b9170610c)
- [git bash 설치](https://www.notion.so/2e25a06fd6d38164baa0d006cc449253)
- [MYSQL 설치하기](https://www.notion.so/2e25a06fd6d38162a284ecb718efff04)
- [사용자 계정 추가하기](https://www.notion.so/2e25a06fd6d38140acaaf7fb57ea60cd)
- [Docker Desktop 구성](https://www.notion.so/2e25a06fd6d381009d65eee3153cc95b)
- [Redis on Docker](https://www.notion.so/2e25a06fd6d381548c62cef2a6d02175)

### DB 자동화를 위한 설정
- [Windows 운영체제에 python 환경 변수 추가하기 (conda 설정)](https://www.notion.so/2e25a06fd6d3818594c4ca9ac27f53f9)
- [.env를 활용하여 보안에 민감한 정보를 차단하기 (매우 중요)](https://www.notion.so/2e25a06fd6d3814eba93efe117354d8a)
- [django 프로젝트 내에 새로운 Domain 구성하기](https://www.notion.so/2e25a06fd6d381df9229e588be58efc4)
- [django 프로젝트에 URI(URL) 맵핑](https://www.notion.so/2e25a06fd6d38113bb3ce63d75aa6a31)
- [Windows에 MYSQL 환경 변수 등록하기 (Mac은 필요 없음)](https://www.notion.so/2e25a06fd6d3815ea886e41e3c5b4426)
- [postman으로 작업 테스트 (주사위 게임 답 실행 방법)](https://www.notion.so/2e25a06fd6d381b7aabffe2690f07c80)
- [단위 프로젝트 postman 구성](https://www.notion.so/2e25a06fd6d381faa0f3d33e3cfe6c71)
- [살펴봐야하는 코드들](https://www.notion.so/2e25a06fd6d38164a867de2ef2ebb7ba)
- [데이터 서버 작업 시 아래 흐름으로 작업합니다](https://www.notion.so/2e25a06fd6d381e7b720e0922aed3b6a)

### Google OAuth 환경 구성
- [Google OAuth 구성법](https://www.notion.so/2e25a06fd6d3812f9a9be96eaa4a6d13)

### PBL 과제
- [과제 진행을 위한 밑 작업](https://www.notion.so/2e25a06fd6d381229fc3c12bed9c6e1d)
- [1주차 과제 (feat by 팀빌딩)](https://www.notion.so/2e25a06fd6d38163a754fd76b97898cd)

### Hexagonal Architecture
- [Layered Architecture](https://www.notion.so/2e25a06fd6d38113a19fe2eabc0e6d9a)
- [왜 Hexagonal Architecture가 필요한가?](https://www.notion.so/2e25a06fd6d38191b33dde90b7a05f56)
- [Hexagonal Architecture 구성](https://www.notion.so/2e25a06fd6d38196b372c739d25c8868)
- [AI Multi Agent에서 Hexagonal Architecture](https://www.notion.so/2e25a06fd6d381808c51df48fd97f5e1)
- [Hexagonal Architecture 패키지 구성 보기](https://www.notion.so/2e25a06fd6d381578b9ecb8e7b1c3a20)
- [Documents AWS S3 업로드 코드 분석](https://www.notion.so/2e25a06fd6d381799398f17f3e3368ee)

### Documents (문서)
- [Documents 작업 예제 .env 구성](https://www.notion.so/2e25a06fd6d3810f885cec2b50ca136f)
- [OpenAI Based Documents Analysis](https://www.notion.so/2e25a06fd6d38166b8e0c63dc3e6aa35)

### Naver OAuth 환경 구성
- [Naver OAuth 구성법](https://www.notion.so/2e25a06fd6d3811cb6d3ed16a1495d73)
- [Naver Shopping API 활성화](https://www.notion.so/2e25a06fd6d3812fa3d5fec9bd321dc2)

### Serp
- [Serp](https://www.notion.so/2e25a06fd6d3819982a8e5c45068707b)

### Kakao OAuth 환경 구성
- [Kakao OAuth 구성법](https://www.notion.so/2e25a06fd6d381e89b7cfb0ffd247c03)

### AWS + Docker + Github Actions
- [AWS 계정 만들기](https://www.notion.so/2e25a06fd6d38141b91ff61de8e1e5d4)
- [Root 계정에 MFA 설정](https://www.notion.so/2e25a06fd6d38110befcf2277458d6d3)
- [AWS EC2 Instance 생성](https://www.notion.so/2e25a06fd6d381c6bc7bd94812de7a33)
- [AWS EC2 Instance 접속하기](https://www.notion.so/2e25a06fd6d381d8ad34d3f708b8018b)
- [AWS EC2 Instance에 개발한 Data Server 배포하기](https://www.notion.so/2e25a06fd6d381c2a6b6cca558acb5ec)
- [AWS EC2 Instance 유형 변경하기](https://www.notion.so/2e25a06fd6d38143a936ea42c4081827)
- [AWS EC2 인스턴스에 IAM 역할 부여하기](https://www.notion.so/2e25a06fd6d381cab6afdef59d95c253)
- [AWS S3 버킷 생성하기](https://www.notion.so/2e25a06fd6d3810ea610cdf1951923d1)
- [AWS S3 전용 IAM 사용자 생성하기](https://www.notion.so/2e25a06fd6d38153bcb8d3781df0818d)
- [AWS S3를 위한 IAM 설정하기](https://www.notion.so/2e25a06fd6d381afa162e63b652f80bf)
- [AWS S3에 파일 업로드 설정하기](https://www.notion.so/2e25a06fd6d381538b09e37ec8ba582a)
- [AWS Cognito 자격 증명 풀 생성하기](https://www.notion.so/2e25a06fd6d3817a875ccea698a90957)
- [AWS Cognito 로 생성한 IAM 유저에 권한 부여하기](https://www.notion.so/2e25a06fd6d381fa8030fa17089ac411)
- [AWS S3 Access Key 및 Secret Access Key 발급하기](https://www.notion.so/2e25a06fd6d3816c8edbdfe84e3d00c7)
- [Domain 구매하기](https://www.notion.so/2e25a06fd6d38110be04d268243e15b4)
- [구매한 Domain에 AWS를 활용하여 HTTPS 붙이기](https://www.notion.so/2e25a06fd6d3817fb52cfedef179bdb5)
- [AWS EC2 Instance에 개발한 AI 서비스 배포하기 (FastAPI)](https://www.notion.so/2e25a06fd6d38180a9edc6884ea36168)
- [AWS에서 Route 53 사용하여 도메인 구매하기](https://www.notion.so/2e25a06fd6d3816cb5a0ca9012075163)
- [ACM 인증서 구성하기](https://www.notion.so/2e25a06fd6d3815b90a1cfeb95443d50)
- [CloudFront 단계 (HTTPS 핵심)](https://www.notion.so/2e25a06fd6d381e9be0cedce1c5e1e8c)

### FastAPI / Next / Toss / 기타 배포
- [FastAPI 서버 배포시 .env 설정값](https://www.notion.so/2e25a06fd6d3814e9c2ae6442b51601d)
- [FastAPI 서비스 AWS 배포하기](https://www.notion.so/2e25a06fd6d381d5aff8d234a9820e15)
- [Next 서버 배포시 .env 설정값](https://www.notion.so/2e25a06fd6d38182b301de02a6a60261)
- [Toss Payments .env 설정 키 값](https://www.notion.so/2e25a06fd6d38144a30df2768e3efe4d)
- [Google Tag Manager](https://www.notion.so/2e25a06fd6d3818194d8faaae5eaf141)
- [SEO 전략](https://www.notion.so/2e25a06fd6d38199ad7bef702461ad00)
- [AEO (Answer Engine Optimization) 전략](https://www.notion.so/2e25a06fd6d38187b0e0d3fb25c8f360)
- [grpc 기본](https://www.notion.so/2e25a06fd6d381108795e99b50499603)
- [RAG](https://www.notion.so/2e25a06fd6d3818e83fbf1b1c1335d5e)

### 개념 확인 문제
- [협업 환경 개념 확인 문제](https://www.notion.so/2e25a06fd6d3816b929ecfe7f9b58661)
- [Git 관련 개념 확인 문제](https://www.notion.so/2e25a06fd6d38112a6f5c1e213e14515)
- [백로그 작성 개념 확인 문제](https://www.notion.so/2e25a06fd6d38104a269e981ec373ff9)
- [이슈 발행과 관리 개념 확인 문제](https://www.notion.so/2e25a06fd6d381e58af7e5890bdb104c)
- [애자일 프로세스 개념 확인 문제](https://www.notion.so/2e25a06fd6d38175ae54fdd6f8a6d8bf)
- [Multi Agent 서비스 개발 관점 개념 확인 문제](https://www.notion.so/2e25a06fd6d381cea90edb7aafdd4cf1)
- [Multi Agent 서비스 애자일 관점 개념 확인 문제](https://www.notion.so/2e25a06fd6d3810fb6ddf2eeb56c65e8)
- [Hexagonal Architecture 개념 확인 문제](https://www.notion.so/2e25a06fd6d381b2865ff3a6f8883c82)

### 성과가 없다고? 축하한다. OKR을 모른다는 완벽한 증거다.
- [성과 제로인 이유는 재능이 아니라 구조다](https://www.notion.so/2e25a06fd6d381059ef0cbbefab846cc)
- [목표가 없으면 어디로 가도 실패다](https://www.notion.so/2e25a06fd6d38118af96cf0ebfe25114)
- [방향을 못 잡는 리더가 조직을 죽인다](https://www.notion.so/2e25a06fd6d381c9b630f25d9481f1db)
- [측정되지 않는 목표는 헛소리다](https://www.notion.so/2e25a06fd6d38169b11ee65af102ce09)
- [개인 영혼과 조직 목표의 연결](https://www.notion.so/2e25a06fd6d3818ebfa1ca734c2efcbd)
- [3개월이 아니면 OKR이 아니다](https://www.notion.so/2e25a06fd6d3819c939debdb290b2bc0)
- [OKR은 '적고 끝내는 문서'가 아니다](https://www.notion.so/2e25a06fd6d38165a7b9dd6adbddc602)
- [실패는 자연스러운가? 아니다, 학습의 구조다](https://www.notion.so/2e25a06fd6d381d19185d695e2d0f58b)
- [성공한 팀 vs 실패한 팀](https://www.notion.so/2e25a06fd6d3814fb293d0e59fd2d65c)
- [실제로 만들어 보자](https://www.notion.so/2e25a06fd6d3816ea891d12e0adc1374)
- [PM이 경로 이탈하지 않는 법](https://www.notion.so/2e25a06fd6d381cbb8f6f98cd9fd0feb)
- [조직이 어디까지 망가졌는지 수치로 보는 시간](https://www.notion.so/2e25a06fd6d38168b639de358c3c4fe0)
- [방향과 길이 없다면 방향과 길을 만들어라](https://www.notion.so/2e25a06fd6d381a084dcd8142d2f55fd)
- [말로만 떠드는 목표는 없다](https://www.notion.so/2e25a06fd6d381f8895dfe97966b5f1c)
- [MVP 검증](https://www.notion.so/2e25a06fd6d3816d8947c9e4eb792ecb)
- [OKR은 리뷰가 만든다.](https://www.notion.so/2e25a06fd6d38129824fc2494595aba2)

---

## Slack · GitHub · Notion 통합 프로젝트 운영 가이드

> Notion: <https://www.notion.so/2e25a06fd6d381a4b821ed2425721fed>

## 1. Slack 도메인 기반 채널 구성 전략

### 1-1. 도메인별 채널 구조 설계
- `#domain-core`
- `#domain-auth`
- `#domain-payment`
- `#domain-ai`
- `#domain-frontend`
- `#domain-infra`

### 1-2. 채널 운영 원칙
- 도메인 기준으로 관점 분리
- 개발 로그·이슈·PR 언급 자동화
- 팀 공지/운영은 별도 `#team-announcement`

- 실제 아래와 같은 케이스를 확인할 수 있음 (팀 이름을 와우 - 공대로 지었기 때문에 좀 더 느낌을 저런 형태로 가져갔었음). 전위 - Frontend, 후위 - Backend 와 같은 관점입니다.

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/f6cac53a-f569-42a9-b38c-ed2f19496861/image.png)

### 1-3. Slack 자동화 추천
- GitHub App 연동 → PR/Issue 실시간 알림
- 워크플로우 빌더로 이슈 템플릿 자동 생성

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/a7694d1b-b96e-4616-9e18-1a002a4db644/image.png)

## 2. GitHub 저장소 describe 추적 전략

### 2-1. GitHub describe 방식
- `git describe --tags --always` → 현재 커밋이 어떤 태그/버전 기준으로 있는지 보여줌
- 장점: 정확한 버전 단위 추적 가능, 배포 파이프라인에서 "이번 배포는 v1.2.0 기준"처럼 명확히 기록
- 단점: 설정/관리 복잡, 모든 커밋마다 태그 관리 필요

### 2-2. Conventional commit 방식 (feat, fix, test…)
- 커밋 메시지에 `feat: 기능 추가` / `fix: 버그 수정` 등 작성
- Slack에서 자동 알림 가능 (ex. GitHub Actions → Slack)
- 장점: 간단, 커밋 메시지만으로 변경 내역 확인 가능
- 단점: 정확한 버전 기준 모호, 배포 시점 기준 버전 관리 필요

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/bb0ce321-be40-4847-8c2b-a1398f6e67f4/image.png)

## 3. GitHub → Notion 이슈·PR 추적

### 3-1. Issue 트래킹 Notion DB 설계 — 필수 속성
- Issue Title
- GitHub Number
- 상태 (Open / In Progress / Done)
- 담당자
- 우선순위
- 생성일 / 수정일
- 관련 PR

### 3-2. 자동 연동 방법
- GitHub Webhook + 서버(예: FastAPI) → Notion API 연동
- Issue 생성 및 변경 시 Notion DB 자동 업데이트

- Notion에 구축한 위키 시스템으로 인해 질문 혹은 이슈를 발행하면 슬랙으로 알림이 날아옵니다.

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/9f8c3595-45a0-48c3-9a18-5b4a70e7db26/image.png)

  - 실제 위의 사항들은 아래 위키에서 실제 Rust 관련 사항 질문을 발행한 경우 발생합니다.

    ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/e6f216c5-cb67-405e-b1d7-06d49c5b8e6b/image.png)

## 4. 프로젝트 백로그 작성·운영 가이드

### 4-1. 백로그 DB 구조
- 도메인, 스토리 (제목), 담당자, 시작일~예상 마감일, 성공 기준, 상태, 우선순위, 리뷰, todo list, Notion 이슈 채널 연동

### 4-2. Relation / Rollup 연결
- 백로그 ↔ GitHub Issue Relation
- 백로그 ↔ 도메인 채널 문서 연결
- Rollup으로 이슈 진행률 표시

### 4-3. 실사용 흐름
1. Slack 도메인 채널에서 QoL 요소 및 아이디어 취합
2. 백로그 DB에 정식 항목으로 등록
3. GitHub Issue 생성
4. Issue–백로그 연결
5. Sprint Planning에서 우선순위 조정
6. 진행 상황은 GitHub·Slack·Notion이 자동으로 동기화

- 아래와 같은 Domain 단위 구성 보기 형태로 사용합니다.

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/977844ed-c0f6-414c-b290-711afe5fd479/image.png)

- 위의 Domain 형태와는 다르게 Status 형태로도 볼 수 있습니다.

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/963e2031-1ef6-42d4-80e2-56bed83118e3/image.png)

---

## 애자일 프로세스

> Notion: <https://www.notion.so/2e25a06fd6d381049c87c9914e0657bb>

## 애자일 프로세스란?
- **목표 중심**: 고객과 비즈니스 가치를 빠르게 전달
- **반복적, 점진적 개발**: 작은 단위로 기능을 구현하고 지속적으로 개선
- **팀 협업 강조**: 개발자, 기획자, 디자이너 등 모든 팀원이 긴밀하게 소통

## 백로그, GitHub, 이슈와의 연결
1. **백로그 (Backlog)**
   - 팀이 해야 할 모든 업무/기능/개선 사항을 **비즈니스 관점**으로 정리
   - 우선순위와 Domain 기반 그룹핑 가능
   - Success Criteria와 Todo를 포함해 **작업의 목적과 기대 성과**를 명확히 함
2. **GitHub 커밋 / PR**
   - 백로그에서 정의된 작업 단위를 코드 수준에서 구현
   - 커밋 메시지에 **Domain, Feature, 이슈 번호** 등을 명시하면 추적 용이
3. **이슈 (Issue)**
   - 문제 발생, 개선 아이디어, 질문 사항 등을 기록
   - 컨텍스트 공유와 제3자의 이해를 돕고, 백로그 카드와 맵핑 가능
   - Description, Try, Reviewer's Opinion 등으로 체계적 관리

즉, **백로그 → 이슈 → 개발 → GitHub 커밋/PR → 리뷰/완료**로 자연스럽게 연결됨

## 스크럼의 중요성
- **팀 단위 진행 상황 공유**: 매일 또는 정기적으로 진행 상황 확인
- **문제 조기 발견**: Blocker를 빠르게 공유하고 해결
- **목표 집중**: Sprint 목표를 중심으로 우선순위 조정
- **팀원 참여 강화**: 모든 구성원이 책임감을 갖고 협력

## 주간 계획의 필요성
- **Sprint Term 계획**: 이번 주에 어떤 백로그를 완료할지 명확히
- **작업량 조절**: Todo 단위로 나누어 진행 가능성 평가
- **성과 측정**: Success Criteria와 연결하여 개선 효과 확인

정리하면, 애자일은 **비즈니스 목표 → 백로그 → 이슈 → 개발 → 리뷰 → 스프린트 목표 달성**으로 이어지는 반복적 과정이며, 스크럼과 주간 계획을 통해 **진행 상황을 명확히 공유**하고 문제를 빠르게 해결하는 것이 핵심입니다.

---

## Multi-Agent 서비스 개발: 기술 관점 vs 비즈니스 관점

> Notion: <https://www.notion.so/2e25a06fd6d381069432e3ca48bcbf80>

## 목적
- 팀원들이 **같은 언어로 이야기**할 수 있도록 함
- 기술 구현이 곧 비즈니스 가치로 이어지도록 **목표 설정 기준** 제공
- Multi-Agent 개발의 **우선순위 결정 및 역할 분담** 명확화

## 기술 관점 (Technical Perspective) — 핵심 이해 포인트
1. **Agent 설계**: Agent는 자율적으로 목표 달성 수행. 환경, 상태, 행동, 보상 구조 이해 필요
2. **환경(Environment)**: Agent가 동작하는 가상/실제 공간. 상태(State)와 규칙(Rules), 입력(Input), 출력(Output) 정의
3. **상호작용**: Agent 간 메시지 전달, 협력/경쟁 전략, 이벤트 기반 통신
4. **정책과 학습**: Rule-based, Heuristic, Reinforcement Learning 등 Agent 행동 결정 방식
5. **성능/최적화**: 다중 Agent 동작 시 병목, 충돌, 안정성 관리

## 비즈니스 관점 (Service/Business Perspective) — 핵심 이해 포인트
1. **목적 중심**: 왜 Multi-Agent를 쓰는가? 고객 경험, 효율성, 경쟁력 향상. 예: 추천 시스템, 게임 AI, 로봇 협업
2. **가치 정의**: KPI와 목표 연계. 예: 체류 시간 증가, 자동화로 비용 절감
3. **사용자/시장 고려**: 실제 사용 환경에서 어떤 가치를 제공하는지, Agent 설계가 UX에 어떤 영향을 주는지
4. **비즈니스 우선순위**: 기술 가능성이 아닌 **가치 기반 기능 우선순위 결정**

## 기술 관점 ↔ 비즈니스 관점 연결

| 항목 | 기술 관점 | 비즈니스 관점 | 연결 포인트 |
| --- | --- | --- | --- |
| 목표 | 보상 최대화, 정책 학습 | 사용자 만족, 비용 절감 | Agent 목표가 KPI와 연계 |
| 동작 | 환경 상태, 행동 결정 | 서비스 흐름, UX 경험 | Agent 행동 → 사용자 경험/서비스 품질 |
| 상호작용 | 메시지, 이벤트 | 협업/경쟁/추천 서비스 | Agent 간 협력 → 비즈니스 로직 |
| 성능 | 학습 효율, 계산 자원 | 서비스 안정성, 확장성 | 최적화된 Agent → 원활한 서비스 제공 |

## 전달 방법
- **사례 기반 학습**: 실제 Multi-Agent 서비스 예시 소개
- **역할 연습**: 팀원별 Agent/서비스 역할 매핑
- **질문 유도**: "이 기능을 구현하면 사용자에게 어떤 가치가 생길까?"

### 핵심 포인트
- 기술 중심 사고만으로는 비즈니스 가치 창출이 어렵고
- 비즈니스 관점만으로는 구현 가능한 구조를 만들기 어렵다
- 따라서 **기술 ↔ 비즈니스 연계 사고**가 Multi-Agent 서비스 성공의 핵심

---

## Multi-Agent 서비스 개발: 애자일 관점

> Notion: <https://www.notion.so/2e25a06fd6d3817ebba4c2c7985fb842>

## 문제 인식
- 많은 개발자가 Multi-Agent를 설계할 때 알고리즘, 정책, 학습 구조 등 기술적 완벽성에만 집중하고, 실제 서비스에서 사용자에게 어떤 가치를 줄지에는 소홀
- 결과: 오버엔지니어링, 구현만 하고 의미 없는 기능, 또는 검증되지 않은 구조

## 핵심 개념: 서비스 중심 사고
- 기술 구현 자체가 목적이 아니라 **서비스 가치 실현**이 목적
- Multi-Agent는 `도구(tool)`이며, 서비스 문제 해결을 위한 수단

### 접근 방법
1. **실험과 검증 중심**: 즉시 완벽한 정답을 정하려 하지 말고, 작은 단위로 기능을 구현하고 서비스 환경에서 가치와 효과를 관찰
2. **가치 우선**: Agent가 어떤 문제를 해결하고, UX/서비스 흐름을 개선하는지 중심으로 설계
3. **점진적 확장**: 초기에는 단순 rule-based, 실제 사용 환경에서 필요한 부분만 점차 학습 기반으로 확장

## 기술과 서비스 연결

| 기술 중심 사고 | 서비스 중심 사고 | 문제점 / 개선 포인트 |
| --- | --- | --- |
| 정책 최적화, 학습 구조 설계 | 사용자가 얻는 가치, 문제 해결 | 기술 구현만 초점 → 서비스 가치 무시 |
| Agent 간 통신 완벽화 | Agent 행동이 서비스 흐름에 기여 | 불필요하게 복잡한 구조 → 유지보수 어려움 |
| 정답 기반 설계, 완전한 학습 모델 | 작은 실험 단위로 점진적 개선 | 초기부터 과도한 엔지니어링 → 진행 지연 |

## 서비스 중심 Multi-Agent 설계 핵심
1. 작은 실험 단위 먼저 구현 — 최소 기능만 Agent에 부여
2. 서비스 환경에서 즉시 검증 — 사용자/환경 반응 관찰
3. 점진적 확장 — 실제 가치와 효과 확인 후 필요한 기술 추가
4. 의사결정 우선순위 — 기술적 완벽성보다 서비스 가치 중심

**핵심 메시지**: Multi-Agent는 기술적 완벽성이 아닌 서비스 문제 해결의 수단. 오버엔지니어링을 피하고 실험·가치 중심 설계. 초기에는 **완벽함보다 작동**, 기술 사용보다 시장 검증이 중요.

---

## Layered Architecture

> Notion: <https://www.notion.so/2e25a06fd6d38113a19fe2eabc0e6d9a>

- **Layered Architecture**는 시스템을 **관심사의 분리(Separation of Concerns)** 원칙에 따라 서로 다른 책임을 가진 여러 `계층(Layer)`으로 나누는 고전적인 소프트웨어 구조

### 기본 구조

| 계층 | 역할 | 의존 대상 |
| --- | --- | --- |
| **Controller (Presentation)** | 외부 요청(HTTP 등)을 받아 서비스 호출 및 응답 반환 | Service |
| **Service (Application)** | 비즈니스 로직을 처리, 여러 Repository 조합 가능 | Repository, Entity |
| **Repository (Infrastructure)** | DB 접근, ORM을 통한 데이터 저장/조회 | Entity |
| **Entity (Domain)** | 비즈니스 데이터 모델 및 규칙 정의 | (없음) |

### Entity — 비즈니스 객체 정의 (ORM Model)
```python
# entity/user_entity.py
from sqlalchemy import Column, Integer, String, Boolean
from sqlalchemy.orm import declarative_base

Base = declarative_base()

class UserEntity(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    email = Column(String, unique=True, nullable=False)
    password = Column(String, nullable=False)
    is_active = Column(Boolean, default=True)
```

### Repository
- **목적**: DB/파일/외부 API 등 **데이터 저장소에 직접 접근**. ORM(SQLAlchemy), Raw SQL, Redis 등 구현을 담당. Service 계층이 데이터를 비즈니스 로직 중심으로 다루도록 추상화 제공
- **책임**: CRUD 수행, 데이터 매핑(ORM ↔ Entity 변환), 데이터 접근 로직 캡슐화

### Service
- **목적**: 도메인 규칙 적용 및 여러 Repository 조합으로 **비즈니스 프로세스 구현**. Controller 요청을 비즈니스 규칙 중심으로 실행. Transaction, Validation, Orchestration 담당
- **책임**: 도메인 로직 수행, 여러 Repository 조합, 트랜잭션 제어, 예외 처리/로깅

### Controller
- **목적**: HTTP 요청/응답 처리, 입력 데이터를 Service로 전달. 외부 세계(FE, 모바일, API 클라이언트)와의 **유일한 접점**. Validation 및 Response Serialization 담당
- **책임**: 요청 검증(Pydantic), Service 호출 및 응답 변환, 상태 코드/예외 처리

**요약**: Repository는 데이터 접근을 캡슐화, Service는 비즈니스 로직을 오케스트레이션, Controller는 외부 요청을 처리. 세 계층은 의존 방향을 유지하면서, 각자의 관심사에만 집중해야 한다.

---

## 왜 Hexagonal Architecture가 필요한가?

> Notion: <https://www.notion.so/2e25a06fd6d38191b33dde90b7a05f56>

### 기존 Layered Architecture의 한계
전통적인 3계층 구조(Controller → Service → Repository)는 간단하고 익숙하지만, **확장성과 변경 내성이 약한 구조**.

#### 대표적인 문제점
- **의존 방향이 프레임워크 중심**: Controller가 FastAPI/Flask/Django에 종속. Service가 Repository를 직접 참조 → DB 구현 변경 시 Service 수정 필요. 결국 "비즈니스 로직이 프레임워크/ORM에 오염"

  ```python
  # 나쁜 예
  def create_user(data: dict, db: Session):
      db_user = UserORM(**data)
      db.add(db_user)
      db.commit()
  ```
  - 도메인 규칙(사용자 생성)이 ORM 코드에 섞여버림. DB 없이 테스트 불가능.

- **테스트 어려움**: Repository가 실제 DB 전제. 단위 테스트가 어려워 "통합 테스트 중심"으로 흐름. Mocking 불편. 비즈니스 로직만 테스트하려 해도 DB 필요.

- **외부 I/O 교체 어려움**: DB → S3/파일/gRPC/Redis 교체 시 전체 수정 필요. DIP 미준수.

- **프레임워크 의존 구조**: 특정 프레임워크를 벗어나기 어려움. 도메인 모델이 프레임워크 import 포함 → 이식성, 독립성, 테스트성 손상.

### Hexagonal Architecture의 등장 배경
- **Alistair Cockburn** 제안. "어떤 환경에서도 비즈니스 로직이 독립적으로 작동해야 한다"
- 핵심 개념: **도메인(Core)**을 외부로부터 완전히 격리, 외부와의 상호작용은 **Port(추상화)**와 **Adapter(구현체)**로 통제

### 해결 방식

| 문제점 | Layered | Hexagonal |
| --- | --- | --- |
| 프레임워크 의존 | Controller에 종속 | Adapter로 격리 |
| DB 의존 | ORM에 직접 의존 | RepositoryPort 인터페이스로 추상화 |
| 테스트 어려움 | 실제 DB 필요 | Port에 Mock 주입 가능 |
| 외부 교체 어려움 | Service-DB 결합 | Adapter만 교체 |
| 비즈니스 독립성 | 낮음 | 높음 |

### 의존 방향의 변화

- Layered 구조
  ```
  [Controller] → [Service] → [Repository] → [DB]
  ```
  도메인(비즈니스)이 항상 "아래"에 깔려 있음. 외부 구현이 내부로 침투하기 쉬움.

- Hexagonal 구조
  ```
            +-----------------------+
            |     Application       |
            |  (UseCase Service)    |
            +----------+------------+
                       |
               +-------+--------+
               |   Domain Core   |
               +-------+--------+
                       |
            +----------+-----------+
            |   Adapters (in/out)  |
            +----------+-----------+
            | HTTP | DB | gRPC | MQ|
            +------+----+------+---+
  ```
  도메인이 `중앙(Core)`에 위치. 외부 어댑터들이 포트(인터페이스)를 통해 통신. **도메인은 외부를 전혀 모른다.** DB나 HTTP가 사라져도 도메인은 정상 작동해야 한다.

---

## Hexagonal Architecture 구성

> Notion: <https://www.notion.so/2e25a06fd6d38196b372c739d25c8868>

### 핵심 개념
- **Domain (엔티티)**: 비즈니스 규칙의 원천. 어떤 외부에도 의존하지 않음
- **Application (유스케이스)**: 도메인을 사용해 실제 업무 흐름을 실행하는 계층. 트랜잭션·오케스트레이션
- **Adapters (인터페이스 어댑터)**: 외부 표현(HTTP/DB/메시지) ↔ 내부 모델 변환기
- **Infrastructure (프레임워크 & 드라이버)**: 실제 구체 구현(ORM, HTTP 서버, 외부 API)

### Domain — 엔티티, 값 객체, 도메인 서비스
- 비즈니스 규칙(정책)을 캡슐화. 상태와 규칙을 함께 갖는 객체(엔티티)와 불변 값(Value Object)
- 외부 기술에 전혀 의존하지 않음
- 핵심 불변과 검증(예: Order 상태 전이, Money 불변성, 도메인 이벤트 발생)을 포함

```python
# domain/model/user.py
from pydantic import BaseModel, EmailStr, validator

class User(BaseModel):
    id: str
    email: EmailStr
    password_hash: str
    is_active: bool = True

    def change_email(self, new_email: str):
        self.email = new_email  # EmailStr가 검증해줌

    def verify_password(self, hash: str) -> bool:
        return self.password_hash == hash
```

**테스트**: 순수 단위 테스트만으로 검증 가능. 상태 전이, 예외 조건, 도메인 이벤트 발생 여부.
**주의**: 도메인에서 I/O/프레임워크 API 호출 금지. DTO와 도메인 모델 분리.

### Application — 유스케이스 / 오케스트레이션
- Use Case 단위로 도메인 서비스를 호출, 여러 포트 조합
- 트랜잭션 경계, 입력검증, 에러 핸들링, 로깅
- 외부에 공개되는 메서드 (예: `RegisterUser.execute(command)`)

```python
# application/usecase/register_user.py
from domain.model.user import User
from domain.ports.user_repository_port import UserRepositoryPort
from pydantic import BaseModel

class RegisterUserCommand(BaseModel):
    email: str
    password: str

class RegisterUserUseCase:
    def __init__(self, user_repo: UserRepositoryPort, password_hasher):
        self.user_repo = user_repo
        self.password_hasher = password_hasher

    async def execute(self, cmd: RegisterUserCommand):
        found = await self.user_repo.find_by_email(cmd.email)
        if found:
            raise ValueError("already exists")

        hash = self.password_hasher.hash(cmd.password)
        user = User(id="generated-id", email=cmd.email, password_hash=hash)
        await self.user_repo.save(user)
        return user
```

### Adapters — 인터페이스 어댑터 (in / out)

#### Adapter-In (입력 어댑터)
- 외부 요청을 Application 계층으로 전달. HTTP/CLI/gRPC
- 요청 → DTO → 유스케이스 호출 → 응답 DTO

```python
# adapter/in/web/user_controller.py
from fastapi import APIRouter
from application.usecase.register_user import RegisterUserUseCase, RegisterUserCommand
from adapters.out.sql_user_repository import SQLUserRepository

router = APIRouter()

@router.post("/users")
async def create_user(cmd: RegisterUserCommand):
    use_case = RegisterUserUseCase(user_repo=SQLUserRepository(), password_hasher=YourHasher())
    user = await use_case.execute(cmd)
    return {"id": user.id, "email": user.email}
```

#### Adapter-Out (출력 어댑터)
- Application/Domain이 정의한 포트(인터페이스)를 구체화

```python
# adapter/out/sql_user_repository.py
from sqlalchemy.ext.asyncio import AsyncSession
from domain.model.user import User
from domain.ports.user_repository_port import UserRepositoryPort
from infrastructure.db.models import UserModel

class SQLUserRepository(UserRepositoryPort):
    def __init__(self, session: AsyncSession):
        self.session = session

    async def save(self, user: User):
        db_user = UserModel(id=user.id, email=user.email, password_hash=user.password_hash)
        self.session.add(db_user)
        await self.session.commit()

    async def find_by_email(self, email: str):
        result = await self.session.execute(
            select(UserModel).where(UserModel.email == email)
        )
        row = result.scalar_one_or_none()
        if row:
            return User(id=row.id, email=row.email, password_hash=row.password_hash)
        return None
```

- **책임**: 변환(DTO ↔ Entity). 어댑터는 application/domain에 의존, 그 반대는 아님
- **주의**: 어댑터 레이어에 프레임워크 코드가 몰려야 유지보수 쉬움

### Infrastructure — 프레임워크 & 드라이버
- 실제 드라이버/라이브러리(ORM, HTTP 서버, 메시지 큐, 파일 시스템) 제공
- 예: TypeORM 커넥션 설정, Express/Fastify 서버, S3/Redis 클라이언트
- 보통 `infrastructure/config`에서 DI 바인딩, 런타임 시 어댑터에 주입
- **주의**: 인프라는 교체 가능해야 함(테스트용 메모리 DB ↔ 운영 DB)

### 의존성 규칙 & 구현 팁
- **의존성 방향**: 항상 외부 → 내부(엔티티)로
- **포트(인터페이스)를 먼저 정의**
- **DTO는 어댑터에 한정**
- **트랜잭션 경계**: 유스케이스 레벨에서 처리(예: UnitOfWork 패턴)
- **에러 경계**: 인프라 에러는 application에서 변환
- **로깅**: 유스케이스 단위 메타데이터 포함

### 패키지 구성 예시
```
app/
├─ domain/
│  ├─ model/
│  ├─ service/
│  └─ port/
│
├─ application/
│  ├─ usecase/
│  └─ dto/
│
├─ adapter/
│  ├─ in/
│  │  └─ web/      # FastAPI router
│  └─ out/
│     └─ persistence/
│
├─ infrastructure/
│  ├─ orm/
│  ├─ http/
│  └─ config/
│
└─ main.py         # DI/앱 부트스트랩
```

---

## RAG

> Notion: <https://www.notion.so/2e25a06fd6d3818e83fbf1b1c1335d5e>

### 한 줄 요약
- RAG란 LLM이 `기억력이 없는 바보`라는 전제를 인정하고 외부 지식을 검색해서 먹인 다음에 답하게 만드는 구조입니다.

### 프로그램 코드 구동 방법
- Multi-Agent-UI를 구동해서 로그인 진행 (로그인 시 확보되는 Cookie 값 활용)

- 단순 LLM 기반 질의

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/86ea2ce9-36f1-484c-9998-bab9500a5ff6/userask_question_cookie.png)

  ← 쿠키값 설정 이후 아래와 같이 질문

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/cfd0efef-e8a5-40ab-8e3a-4593e91daedd/userask_question.png)

- 학습 이전 rag-question을 날려봄

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/62dbf879-51a5-4cb4-8c5d-17b3ea8fedf7/userask_ragquestion.png)

  ← 동일하게 cookie 설정 이후 질의

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/aeed6dec-651c-4533-8fdc-f7c49a95a3b4/userask_ragquestion_request.png)

- 우리가 제공하는 문맥(Context)을 학습시킴

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/01222731-917b-4531-a6b5-8809443a69aa/userask_adddocs_cookie.png)

  ← 동일하게 cookie 설정 이후 학습

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/7eba536d-a2d7-45ef-ae3b-fb681a2c7ff4/userask_adddocs.png)

- 다시 RAG에 질의

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/455951ba-9bc9-4040-8120-06aaa7bab06a/%EB%8B%A4%EC%8B%9C%EC%A7%88%EC%9D%98.png)

### 왜 RAG가 필요한가
- LLM의 본질적 한계 — LLM은 다음을 할 수 없다:
  - 최신 정보 보장 X
  - 내부 문서 기억 X
  - 회사별/프로젝트별 지식 유지 X
  - 답변 근거 추적 X
- LLM은 본질적으로 `확률적으로 그럴듯한 문장 생성기`, 지식 저장소가 아님

#### 해결 전략
- 선택지 2가지
  1. 모든 지식을 모델 안에 학습시킨다 → 비용, 시간, 통제 불가 X
  2. 필요할 때 외부에서 찾아서 먹인다 → 현실적, 통제 가능

### RAG의 전체 구조

#### 인간 사고와 RAG의 대응 관계

| 인간 사고 | RAG 시스템 |
| --- | --- |
| 기억 | Vector DB |
| 검색 | Similarity Search |
| 맥락 이해 | Retrieved Context |
| 말하기 | LLM |

- 검색 → 이해 → 생성

### RAG 파이프라인
1. 문서를 쪼갠다 (Chunking)
2. 문서를 벡터로 바꾼다 (Embedding)
   - Vectorization은 과거 SIMD 연산자 같은 병렬처리 기법과 혼용 요소 있음
3. 벡터를 DB에 저장한다
4. 질문도 벡터로 바꾼다
5. 가장 비슷한 문서를 찾는다
6. 그 문서를 프롬프트에 포함
7. LLM이 답한다

- LLM은 검색을 하지 않는다. 검색은 우리가 한다.

### Vector DB와 ChromaDB의 역할
- Vector DB란 텍스트를 숫자 좌표로 바꿔서 "의미가 비슷한 것끼리 가까이 두는 창고"
- 예시: `환불 정책`, `결제 취소 규정` → 문장은 다르지만 의미가 유사 → 가까운 벡터

### 왜 ChromaDB인가 (초보 교육용)

| 이유 | 설명 |
| --- | --- |
| 설치 쉬움 | pip install |
| 로컬 실행 | 서버 필요 없음 |
| 개념 명확 | 숨겨진 마법 적음 |
| 실습 적합 | 디버깅 쉬움 |

### 실전 구조 설계
- 저장과 조회를 분리하는 것이 핵심 (ingest와 query를 분리)

### 문서 저장 (Ingest)
- 문서를 작게 쪼개고, 각 조각을 벡터화, DB에 저장
- chunk_size는 "의미 단위", overlap은 "문맥 끊김 방지"

### 질문 처리 (Query)
- 질문을 벡터로 변환
- 가장 유사한 문서 k개 검색
- 그 문서를 프롬프트에 강제 삽입
- LLM 호출

### 초보들이 반드시 이해해야 할 핵심 오해 5가지
1. **RAG면 LLM이 똑똑해진다** → 아니다. LLM은 그대로고, 우리가 맥락을 공급
2. **Vector DB가 답을 만든다** → 아니다. Vector DB는 검색만 함
3. **데이터 많으면 정확해진다** → 아니다. 좋은 Chunk + 좋은 질문이 핵심
4. **프롬프트는 대충 써도 된다** → RAG에서 프롬프트는 계약서
5. **RAG는 만능이다** → RAG는 지식 문제만 해결. 추론 능력은 모델 문제

**정리**: RAG는 AI를 똑똑하게 만드는 기술이 아니라, AI를 `쓸 수 있는 도구`로 만드는 설계.

### 코드 관점에서 RAG 활용
- 사용자 질문 → ChromaDB 의미 기반 문서 검색 → 프롬프트에 삽입 → LLM 답변 생성
- LLM이 답하기 전에 우리가 문맥(Context)을 강제로 먹이는 구조

#### 두 개의 질문 경로
- 일반 질문
  ```
  /question
  → AskQuestionUseCase
  → LangGraphAgent
  → LLM 단독 응답
  ```
  문서 검색 X, 컨텍스트 X, 순수 생성형 LLM O

- RAG 질문 (Retrieval + Generation)
  ```
  /rag-question
  → AskRAGQuestionUseCase
  → RAGAgent
     ├─ query_documents()   ← Retrieval
     └─ generate_answer()   ← Generation
  ```

#### RAG의 핵심 3요소

**Retrieval (검색)**
```
context_texts = self.agent.query_documents(question_text)
```
```
results = self.collection.query(
    query_texts=[question],
    n_results=n_results
)
```
- 질문을 임베딩 → ChromaDB 저장 벡터들과 유사도 계산 → 상위 N개 문서 반환 (Semantic Search)

**Augmentation (문맥 삽입)**
```
context = "\n".join(context_texts)
```
```
template="""
다음 문서를 참고하여 질문에 답변하세요.
문서 내용:
{context}

질문:
{question}
"""
```
- 이 단계가 RAG의 핵심: LLM이 자기 기억이 아닌 이 문서만 근거로 답하라고 강제. 프롬프트로 계약을 거는 행위.

**Generation (생성)**
```
chain = LLMChain(llm=self.llm, prompt=self.prompt_template)
return chain.run(context=context, question=question)
```
- LLM은 검색 X, 판단 X, 문서 선택 X. 오직 주어진 문맥을 문장으로 재구성.

#### 책임 분리

| 계층 | 역할 |
| --- | --- |
| Controller | HTTP |
| UseCase | 유스케이스 흐름 |
| Agent | AI 전략 |
| DB | 기억 |

### 결론
- 현재 구조가 해결: LLM이 모르는 내부 지식을 답하게 만든다
- 지식형 질문에는 매우 강력, 추론형 질문에는 한계 명확

### 추론 능력 강화 방법
1. 멀티문서 검색 (Multi-hop Retrieval)
2. 문서 유형 구분 및 요약
3. 근거 포함 답변 (Evidence-aware Generation)
4. 체인 오브 스루트(Chain-of-Thought) 적용
5. 피드백 루프 + 재질문

**요약**: 추론 강화 = 더 많은 문서 + 문서 가공 + 출처 명시 + 사고 과정 노출 + 재검증
