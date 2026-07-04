Notion 원본: https://app.notion.com/p/3935a06fd6d381349c2cf81903d118e7

# Raft 합의 알고리즘 리더 선출과 로그 복제 및 멤버십 변경

> 2026-07-05 신규 주제 · 확장 대상: 면접을_위한_CS_전공지식_노트

## 학습 목표

- Raft 의 세 상태(Follower/Candidate/Leader)와 term 기반 리더 선출을 설명한다
- 로그 복제의 매칭 속성과 커밋 규칙, commitIndex 진행 조건을 파악한다
- 이전 term 엔트리를 직접 커밋하지 않는 안전성 규칙의 이유를 이해한다
- Joint Consensus 로 무중단 멤버십 변경을 수행하는 절차를 정리한다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
