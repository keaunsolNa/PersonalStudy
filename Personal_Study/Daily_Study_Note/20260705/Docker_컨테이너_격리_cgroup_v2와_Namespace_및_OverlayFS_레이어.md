Notion 원본: https://app.notion.com/p/3935a06fd6d381b39530c3c64fcbc09d

# Docker 컨테이너 격리 cgroup v2와 Namespace 및 OverlayFS 레이어

> 2026-07-05 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- 컨테이너 격리를 구성하는 리눅스 namespace 7종의 역할을 구분한다
- cgroup v2 통합 계층과 CPU/메모리 컨트롤러의 실제 제한 방식을 파악한다
- OverlayFS 의 lowerdir/upperdir/copy-up 메커니즘으로 이미지 레이어 공유를 설명한다
- 컨테이너 OOM, CPU throttling 을 지표로 진단하고 리소스 요청을 조정한다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
