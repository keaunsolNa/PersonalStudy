# Claude Code

> Notion 원본: <https://www.notion.so/3405a06fd6d3804e9656f40c1ce58699>
> 동기화일: 2026-04-21

> 이미지 다운로드 실패 알림: 본 환경의 HTTP 프록시 정책상 S3 호스트 접근이 차단되어 이미지를 로컬로 내려받지 못했습니다. 본 문서 내부의 `![](https://...)` 링크는 Notion 원본 Pre-signed URL을 유지하며 약 1시간 내 만료됩니다.

```markdown
# 단순 Vibe Coding의 문제점
  1.검증된 기존의 코드를 함부로 변경하는 문제 발생
  2.다수의 개발자가 사용하는 컨벤션 규칙을 제대로 맞추지 못하는 문제 발생
  3.요구사항을 정확히 이해하지 못해 AI가 작성한 코드를 지우고 다시 요청, 또는 수정 요청 * n 문제 발생

# 왜 claude code인가?
  1.claude code에서 제공하는 Opus 모델이 현존하는 모델 중 가장 추론 능력이 뛰어나다고 판단됨. (요구사항 이해도 ↑) (문제점 3)
  2.체감상 claude code 제공 기능들이 개발에 적합 하다 판단됨.
      > plan 모드를 통해 계획 및 검증 이후 코드 작성 (문제점 1)
      > memory 및 skill을 사용하여 컨벤션 규칙 ↑ (문제점 2)
      > context windows 관리를 통한 요구사항 명확화 및 토큰 관리
      > mcp 연동을 통한 추가 기능 확장 용이
  3.앤트로픽 및 커뮤니티에서 claude code에 대한 사용 전략들 및 많은 정보들이 공유 되고 있음
  4.지속적인 제공 스킬 업데이트가 수시로 일어나고 있음.  (2026년 2월에도 업데이트)
```

---

## 0 Claude Code 기본 요소

> Notion: <https://www.notion.so/3405a06fd6d3814781d9e5d6911ee45e>

## 에이전트 루프
클로드 코드에게 작업을 주면 수집 → 작업 수행 → 결과 검증 단계를 반복적으로 거쳐 결과를 만들어 낸다. 요청한 내용에 따라 루프 작업 절차가 조정된다.

![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/592e6ab8-fc0e-49fa-ab71-711240cac5f2/592e6ab8-fc0e-49fa-ab71-711240cac5f2.png)

에이전트 루프는 모델, 도구라는 두가지 구성 요소로 구동된다.

클로드 코드는 에이전트 하네스 역할로써, 언어 모델(claude)에게 도구, 컨텍스트 관리, 실행 환경 등을 제공해 코딩 에이전트로 변환하는 역할을 함.

### 모델
- Opus : 복잡한 아키텍처 결정을 위한 강력한 추론 제공
- Sonnet : 대부분의 코딩 작업 처리
- Haiku : 빠른 작업 결과 도출

### 도구(tools)
기본 도구
- **파일 작업** : 읽기, 편집, 생성, 변경, 재구성
- **검색** : 패턴으로 파일 찾기, 정규식 콘텐츠 검색, 코드베이스 탐색
- **실행** : 쉘 명령 실행, 서버 시작, 테스트 실행, git
- **웹** : 웹 검색, 문서 가져오기, 오류 조회
- **코드 인텔리전스** : 타입 오류 및 경고, 정의로 이동, 참조 조회

확장 도구
- **mcp**
- **skills**
- **hooks**
- **subagents**

## Context Windows
> Context Windows 는 사용자 요청 시 함께 전달되는 Context들을 최대로 담을 수 있는 공간을 뜻한다.

### Context란?
하위와 같은 내용들을 뜻한다.
- 사용자와의 대화 기록
- 참조된 파일 콘텐츠
- 명령 출력 내용
- CLAUDE.md
- 로드된 skills
- 시스템 프롬프트
- mcp

따라서 작업량이 많아질 수록 Context Windows 의 공간이 부족해지게 된다. 해당 과정에서 claude는 자동으로 Context를 압축하지만, 초기 대화 내용이 손실될 가능성이 높다.
이를 방지 하기 위해 항상 요청해야 되는 지침은 메모리(CLAUDE.md)에 넣거나, /compact {요청}을 통해 사전에 context를 압축해야 한다.

![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/c4f67045-4f25-4dac-8f3e-d842418410c3/c4f67045-4f25-4dac-8f3e-d842418410c3.png)

실제 /context 명령어를 통해 확인한 context Windows 공간이다.

> 💡 사실상 콘텍스트 윈도우에 있는 내용이 에이전트에게 질의되는 내용들로, Token 사용량과 정비례한다고 보면 된다. 즉, context 관리 또한 중요한 전략.

## 세션
claude code는 작업하며 대화들을 로컬로 저장하게 되며, 각 대화별로 독립적인 세션을 사용하게 된다.
세션은 명령어를 통해 히스토리 확인 및 세션 재개를 할 수 있으며, 각각의 컨텍스트 윈도우를 사용하게 된다.
하지만, 자동 메모리(CLAUDE.md)를 사용하면 각 세션별로 메모리를 공유할 수 있다.

![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/f3c75c9b-4b8f-48de-bb7e-c0035b4d93c4/f3c75c9b-4b8f-48de-bb7e-c0035b4d93c4.png)

/resume 명령어를 통해 재개한 세션 목록을 조회한 결과.

## ETC
- skills, subagents ..

참조: <https://code.claude.com/docs/ko/how-claude-code-works#%EC%8B%A4%ED%96%89-%ED%99%98%EA%B2%BD>

---

## 1 Tools 사용 및 기본 전략

> Notion: <https://www.notion.so/3405a06fd6d3818b9b0dea1635e06f69>

## 프로젝트 초기화(CLAUDE.md, /init)
CLAUDE.md 파일은 Claude가 모든 대화 시작 부분에서 읽는 특수한 파일이다.
때문에, 해당 파일에 기본 패키지 규칙 및 컨벤션, 워크 플로우 등을 지정해 두면 지속적으로 읽어들여 잘못된 작업 수행을 방지할 수 있다.
/init 명령어를 사용하면 claude code가 기본 패키지 구조를 스스로 분석하고 파악한 내용을 바탕으로 메모리(CLAUDE.md)를 신규로 생성하여 최소 가이드 라인을 저장하게 한다.

> 💡 CLAUDE.md 파일이 너무 길어지는 경우 의도가 흐려질 수 있기 때문에 과도한 작성은 피하는 것이 좋다. 보통 60~100줄 이내 권장

## 모델 선택 `/model`
/model 명령어를 통해 작업을 수행할 모델 및 effort(분석 가중치)를 지정할 수 있다.
- Opus : 고급 추론 모델, 기능 개발 전 분석, 설계(Plan) 단계에서 사용
- Sonnet : 전반적인 작업 수행 모델, 전반적인 코드 작성 단계에서 사용
- Haiku : 빠른 작업 수행 모델, 간단한 변수명 변경 및 소규모 리팩토링 단계에서 사용

당연히 상위 추론 모델 및 effort(노력 가중치) 값을 높게 지정할 수록 토큰 사용량이 빨리 소모된다.
때문에 개발 시 분석, 설계 단계에서는 Opus 모델로 추가 검증 및 수정을 최소화하고, 수립된 계획을 바탕으로 Sonnet으로 개발을 수행하는 전략을 주로 사용한다.

![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/c6afb81f-44d0-4871-a5ac-452eb0825fe7/c6afb81f-44d0-4871-a5ac-452eb0825fe7.png)

> 💡 해당 전략을 쉽게 사용할 수 있도록 별도 설정된 모델을 Claude에서 제공하고 있다. `/model opusplan` 참조: <https://code.claude.com/docs/en/model-config>

![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/aee212a7-00c9-482a-9bd9-ea88ebf0ba69/aee212a7-00c9-482a-9bd9-ea88ebf0ba69.png)

> 💡 effort: effort 매개변수를 사용해 Claude가 응답할때 사용되는 토큰 수를 제어할 수 있다. High로 갈 수록 토큰을 적극적으로 사용하는 대신 응답의 완성도가 높아진다. 참고: <https://platform.claude.com/docs/en/build-with-claude/effort>

### 플랜 비교

| 플랜 | 월 가격 | Sonnet 사용량 | Opus 사용량 |
| --- | --- | --- | --- |
| Pro | $20 | 주당 40~80 시간 | 제한적 |
| Max 5x | $100 | 주당 140~280 시간 | 주당 15~35 시간 |
| Max 20x | $200 | 주당 240~480 시간 | 주당 24~40 시간 |

## 탐색 후 계획 및 수행 모드 `/plan`
해당 명령어 또는 Shift + Tab 을 통해 mode 변경으로 plan mode를 설정 할 수 있다.
해당 모드는 계획 설계 모드로 요청한 내용에 대해 즉각적으로 변경하는 것이 아닌 무조건 분석 및 파악 이후 부족한 정보는 스스로 질의하여 이후 상세 구현 계획을 설계하며 내용 확인 요청, 요청 동의 시 Normal Mode 로 변경하여 작업을 수행한다.

> 해당 전략은 Claude Code Docs에 나와 있는 모범 사례로, 요청에 대한 잘못된 판단 및 수행을 방지하여 불필요한 리소스(토큰) 사용을 방지하고, context 사용량을 최소화 하여 좀더 명확한 결과를 이끌어 낼 수 있다.

## 응답 방식 설정 `/output-style`
만약 AI를 통해 개발한 기능에 대해, AI가 판단한 이유 및 학습이 필요한 경우 응답 방식을 수정하여 추가적인 정보를 얻을 수 있다.
- Default : 작업을 효율적으로 완료하고 간결한 답변 제공
- Explanatory : 구현 방식과 코드베이스 패턴을 설명
- Learning : 실습을 위한 간단한 부분 코드를 작성하도록 진행

Explanatory, Learning 스타일을 사용하는 경우 Claude Code에서 분석한 내용을 바탕으로 추가 Insight 정보를 제공해준다.

# SKILLS
Claude Code에서 skills를 직접 생성, 관리, 공유하여 Claude의 기능을 확장할 수 있다.
SKILL.md 파일을 만들어 설명 및 지침을 작성하면, Claude Code가 해당 skill을 도구 모음에 추가해 사용할 수 있다.
이는 직접 커맨드(/{skill-name})을 통해 실행하거나, Claude가 스킬에 대한 설명을 읽어 들여, 상황에 맞는 skill을 직접 사용할 수 있다.
이를 통해 중복되는 작업을 하나의 스킬로 만들어 놓고 바로바로 호출하여 사용해 최적화가 가능하다.

# SubAgent
특정 유형의 작업을 처리하는 특화된 AI 어시스턴트.
subagent는 자체 컨텍스트 윈도우에서 실행되며, 사용자 정의 시스템 프롬프트 및 독립적인 권한을 가진다.

### 역할
- 컨텍스트 보존 : 탐색 및 구현을 메인 에이전트 컨텍스트와 분리해 유지
- 제약 조건 적용 : 사용 도구 한정 가능
- 구성 재사용 : 각 프로젝트 간 재사용 가능
- 동작 특화 : 특정 도메인에 특화 가능
- 비용 제어 : agent에 맞는 모델을 지정해, 적절한 토큰 소모 가능

### 기본 내장 subAgent
- Explore : Haiku, 읽기 전용 도구 사용
- Plan : 세션에 설정된 모델, 읽기 전용 도구, Plan mode 사용 시 활용됨
- General-purpose : 세션에 설정된 모델, 복잡한 다단계 작업 시 사용되는 유능한 에이전트
- Other : Bash, statusline-setup, Claude code Guide..

# MCP (Model Context Protocol)
MCP를 통해 다양한 외부 도구 및 데이터 소스에 연결할 수 있다.
MCP 서버는 Claude Code에 도구, 데이터베이스 및 API에 대한 액세스를 제공합니다.
- **이슈 추적기에서 기능 구현**: "JIRA 이슈 ENG-4521에 설명된 기능을 추가하고 GitHub에서 PR을 생성하세요."
- **모니터링 데이터 분석**: "Sentry와 Statsig을 확인하여 ENG-4521에 설명된 기능의 사용 현황을 확인하세요."
- **데이터베이스 쿼리**: "PostgreSQL 데이터베이스를 기반으로 기능 ENG-4521을 사용한 무작위 사용자 10명의 이메일을 찾으세요."
- **디자인 통합**: "Slack에 게시된 새로운 Figma 디자인을 기반으로 표준 이메일 템플릿을 업데이트하세요."
- **워크플로우 자동화**: "이 10명의 사용자를 새로운 기능에 대한 피드백 세션에 초대하는 Gmail 초안을 생성하세요."

참고: <https://code.claude.com/docs/ko/best-practices>

---

## 2 Skills

> Notion: <https://www.notion.so/3405a06fd6d381bc8995c7a5d4b1ba3d>

Skill 핵심은 모델에게 작업 수행 방법을 알려주는 Markdown 파일이다.
핵심 장점으로 Claude에서 제공하는 Skills는 MCP나 메모리와 달리, 초기에는 각 skills에 있는 설명만 context에 담아두었다가, 요청한 작업에 적합한 skills 설명이 있는 경우 해당 skills 세부 내용을 읽어 들여 토큰 사용량을 효율적으로 사용할 수 있음.

각 스킬이 차지하는 초기 토큰은 **약 수십개에 불과**.

참고 자료: <https://news.hada.io/topic?id=23734>

---

## 3. MCP (task manager)

> Notion: <https://www.notion.so/3405a06fd6d3812cbc8fd0d51a2a25cc>

MCP 중 개발 과정에서 유용한 기능이 있어 소개 차원에서 작성하였음.
요구 개발사항이 복잡하고, 구체적일 수록 한번에 처리하기 보다는 task를 쪼개어 순차적으로 진행하는 것이 더욱 효율적이게 된다.
한꺼번에 처리하게 되는 경우 개발자가 중간에 개입해 고칠 수 있는 부분이 적어지고, 피드백도 어려워짐. token 사용량도 방대.

이를 도와주는 mcp 도구가 있어 소개한다.
- task-master : <https://www.task-master.dev/>
- shrimp-task-manager : <https://github.com/cjo4m06/mcp-shrimp-task-manager>

두 mcp 도구 모두 복잡한 작업 단위를 작은 task 단위로 쪼개어 수행하게 도와주는 mcp이다.
다만, 두개의 차이점이 있다면 task-master의 경우는 명확한 PRD가 필요하다는 것, shrimp의 경우는 init을 통해 초기 프로젝트를 분석해 유연하게 동작한다는 것이다.

이를 사용하면 클로드에서 제시하는 탐색 및 설계, 실행 전략을 좀더 명확하게 진행할 수 있다.

---

## 4. How to use it

> Notion: <https://www.notion.so/3405a06fd6d38091bf0dfc4a678c3ef8>

### 1. 사전 준비
- Claude Code pro 계정 사용

#### MCP 설치
- shrimp task manager MCP (요구사항 분석, 설계, 개발)

#### 규칙 생성
- .claude/rules/ (하위 규칙 페이지 5종)

#### SKILL 생성
- .claude/skills
- code-developer
- code-reviewer

#### Agent 생성
- .claude/agents

### 2. 요구사항 분석

#### 기능 요구사항
- [메일 / 보기] 첨부파일 웹폴더 저장

#### 의도
- 해당 첨부파일 웹폴더 저장 기능이 웹드라이브라는 별도 모듈과 연계되기 때문에 드라이브 UI 및 기능을 확인하고자 함.
- 하지만 드라이브 기능이 어떻게 활성화 되는지 찾기 어려움.

#### 작업
- claude code를 통해 웹드라이브 기능 활성화 방법 요청

1. 기능 설정 중 드라이브 기능 활성화 방법 요청

   ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/70b1e7f3-0b45-4d9d-952e-898b3893b217/70b1e7f3-0b45-4d9d-952e-898b3893b217.png)

   ⇒ 실제 쿼리 업데이트를 통해 planType은 FREE로 바꾸어 해결

2. 요구 사항 설계 (with shrimp manager mcp)
   - plan task {요구사항}

     ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/09b8704c-ff93-4ded-80f7-9b5812884e8c/09b8704c-ff93-4ded-80f7-9b5812884e8c.png)

   - 결과 (구현 계획 샘플):

     ```markdown
     shrimp-task-manager - list_tasks (MCP)(status: "all")
     ⎿  Error occurred: ENOENT: no such file or directory, mkdir 'X:\...\data'
         Please try correcting the error and calling the tool again

     ● Task manager MCP에 경로 오류가 있습니다. 빌트인 방식으로 계획을 정리하겠습니다.
     ★ Insight ─────────────────────────────────────
     분석 중 발견한 흥미로운 패턴: 이미 MailDriveApiController가 존재하지만 방향이
     드라이브→메일 (드라이브 파일을 메일에 첨부)입니다. 새 기능은 반대 방향인
     메일→드라이브이며, 동일한 RestTemplate + driveUrl 패턴을 역방향으로 재사용하게
     됩니다. 이처럼 양방향 통합을 같은 컨트롤러에 집약하는 패턴은 유지보수
     응집도(cohesion)를 높이는 좋은 설계입니다.
     ─────────────────────────────────────────────────

     ---
     구현 계획
     분석 결과 요약
     ┌────────────────────────┬──────────────────────────────────────────────────────────────────┐
     │          구분          │                            발견 사항                             │
     ├────────────────────────┼──────────────────────────────────────────────────────────────────┤
     │ 첨부파일 UI            │ Header.vue (전체 저장 링크) + ListItem.vue (개별 다운로드 버튼)  │
     │ 다이얼로그 시스템      │ mailDialog Vuex store + SET_DIALOG 뮤테이션 패턴                 │
     │ 폴더 트리              │ getMovableFolders() + v-treeview (MoveFileDialog.vue에서 재사용) │
     │ 기존 드라이브↔메일     │ file.api.js의 uploadFromDrive 함수 (동일 파일에 추가)            │
     │ 백엔드 패턴            │ MailDriveApiController + RestTemplate + driveUrl 설정값          │
     │ i18n 현황              │ ko/en/id 최대 키 619 → 620~624 신규 추가                         │
     │ 공유 메일 처리         │ isSharedMail computed 이미 존재 → 버튼 숨김에 재사용             │
     └────────────────────────┴──────────────────────────────────────────────────────────────────┘

     ---
     Task 1: 백엔드 API 추가
     파일: cts-mail-api/.../drive/api/MailDriveApiController.java
     -POST /api/mail/drive/save-attach 엔드포인트 신규 추가
     -파라미터: mailId, positions[], targetFolderId
     -SimpleParser로 첨부파일 스트림 추출 → RestTemplate multipart POST → {driveUrl}/api/drive/file/upload

     Task 2: 프론트엔드 API + i18n
     파일: mail/api/file.api.js + _locales/{ko,en,id}/mail.json
     -saveAttachToDrive({ mailId, positions, targetFolderId }) 함수 추가
     -i18n 키 620~624 (ko/en/id 동시 추가)

     Task 3: 폴더 선택 다이얼로그 컴포넌트 신규 작성
     파일: mail/views/components/dialog/SaveAttachToDrive.vue (신규)
     -getMovableFolders() → v-treeview 폴더 트리
     -confirm prop watch → saveAttachToDrive API 호출
     -성공/실패 snackbar + CLOSE_DIALOG

     Task 4: 다이얼로그 등록 + 첨부파일 UI 버튼 추가
     파일: dialog/index.vue, Dialog.vue, ListItem.vue, Header.vue

     ---
     의존 관계
     Task 1 (백엔드)         ← 독립 실행 가능
     Task 2 (API + i18n)    ← 독립 실행 가능
     Task 3 (다이얼로그)     ← Task 2 완료 후
     Task 4 (UI 버튼)        ← Task 3 완료 후

     이 계획대로 개발을 진행할까요? 확인해 주시면 /code-developer 로 구현을 시작하겠습니다.
     ```

### 3. 기능 개발 및 검증
- 계획 확인 및 코드 작업 진행 요청

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/3c241301-4ee4-4f16-b132-0f3b28bbc35f/3c241301-4ee4-4f16-b132-0f3b28bbc35f.png)

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/25710415-2b21-467e-b6bf-5564ff5fa11d/25710415-2b21-467e-b6bf-5564ff5fa11d.png)

- 직접 코드 확인 과정에서 없는 메서드를 사용하고 있는 것을 발견.
- 재검토 요청 결과. 기존에는 사용하지 않는 java api 및 로직으로 작성 과정에서 잘못된 코드를 짠 것으로 보임
- ⇒ claude code에게 신규 코드를 짜게 하는 것은, 개발자가 해당 코드를 완벽하게 이해하고 있지 않는 이상 리스크가 있다고 판단, 따라서 가급적 패키지 내의 기존 코드를 탐색해 사용하도록 지시 & 규약 파일 업데이트

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/9d1fb785-19de-4b1c-b595-3d788b74c1be/9d1fb785-19de-4b1c-b595-3d788b74c1be.png)

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/c5fa0010-ba91-4029-8366-8c91aeec14ba/c5fa0010-ba91-4029-8366-8c91aeec14ba.png)

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/cc7529df-5be2-4ee3-b147-e10777a607b3/cc7529df-5be2-4ee3-b147-e10777a607b3.png)

### 4. 기능 테스트
- UI 테스트

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/b2f87af3-0dca-44c1-bf33-8f6783d525a1/b2f87af3-0dca-44c1-bf33-8f6783d525a1.png)

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/c313b675-5c18-4e78-b9f4-f214b650f577/c313b675-5c18-4e78-b9f4-f214b650f577.png)

  ![](https://prod-files-secure.s3.us-west-2.amazonaws.com/fdc67542-acbd-4407-b434-7514980e8591/b80829f7-5750-4626-80f6-8429ea0ec971/b80829f7-5750-4626-80f6-8429ea0ec971.png)

---

## 5 기능 개발 및 코드 리뷰, 테스트 까지

> Notion: <https://www.notion.so/3405a06fd6d38114bc3fc4e52eed438b>

- [code-reviewer-agent](https://www.notion.so/3405a06fd6d381bf98b3cfd9b8593d8f)
- [code-reviewer](https://www.notion.so/3405a06fd6d3819099e7fd69ec316a90)

---

## 6. 참조

> Notion: <https://www.notion.so/3405a06fd6d380da9345c6114aefc407>

- [[AI] Claude Code(클로드 코드) 사용법과 고급 사용팁 (mangkyu.tistory.com)](https://mangkyu.tistory.com/444)
