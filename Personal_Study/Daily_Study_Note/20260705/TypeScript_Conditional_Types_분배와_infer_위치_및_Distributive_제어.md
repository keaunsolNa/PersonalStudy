Notion 원본: https://app.notion.com/p/3935a06fd6d3817c97f3f8185f7bd4ed

# TypeScript Conditional Types 분배와 infer 위치 및 Distributive 제어

> 2026-07-05 신규 주제 · 확장 대상: Javascript

## 학습 목표

- 조건부 타입의 분배(distributive) 동작이 언제 발동하는지 구분한다
- `[T] extends [U]` 튜플 래핑으로 분배를 끄는 원리를 파악한다
- infer 를 공변·반공변 위치에 두었을 때 union/intersection 추론 차이를 이해한다
- 실전 유틸리티 타입을 분배 제어와 infer 로 직접 구현한다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
