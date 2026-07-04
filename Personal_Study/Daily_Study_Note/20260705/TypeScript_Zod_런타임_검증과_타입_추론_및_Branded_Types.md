Notion 원본: https://app.notion.com/p/3935a06fd6d381f4aa99ea09e401ea85

# TypeScript Zod 런타임 검증과 타입 추론 및 Branded Types

> 2026-07-05 신규 주제 · 확장 대상: REST_API

## 학습 목표

- 컴파일 타임 타입과 런타임 검증의 간극을 Zod 스키마로 메우는 원리를 설명한다
- z.infer 로 스키마에서 정적 타입을 단일 진실원(SSOT)으로 도출한다
- parse/safeParse, transform, refine 의 동작과 에러 처리 흐름을 파악한다
- Branded Types 로 검증된 값을 타입 수준에서 구분하는 기법을 구현한다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
