Notion 원본: https://app.notion.com/p/3935a06fd6d381b9a818f410c8c13433

# Spring Data JPA N+1과 Fetch Join 및 EntityGraph 배치사이즈

> 2026-07-05 신규 주제 · 확장 대상: ORM

## 학습 목표

- 지연 로딩이 N+1 쿼리를 유발하는 구조적 원인을 설명한다
- Fetch Join, @EntityGraph, batch size 각 해법의 적용 조건과 한계를 구분한다
- 컬렉션 Fetch Join 의 페이징 불가·카테시안 곱 문제를 파악한다
- DTO 프로젝션으로 조회 전용 경로를 분리하는 판단 기준을 세운다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
