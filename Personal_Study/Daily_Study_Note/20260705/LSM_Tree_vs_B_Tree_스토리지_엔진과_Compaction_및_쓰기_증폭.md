Notion 원본: https://app.notion.com/p/3935a06fd6d38166b0a7df5e4ad159f5

# LSM Tree vs B Tree 스토리지 엔진과 Compaction 및 쓰기 증폭

> 2026-07-05 신규 주제 · 확장 대상: Oracle

## 학습 목표

- B+Tree 와 LSM Tree 의 쓰기 경로 차이를 구조적으로 설명한다
- MemTable/SSTable/WAL 구성과 읽기 경로의 계층 탐색을 파악한다
- 쓰기 증폭, 읽기 증폭, 공간 증폭의 삼각 트레이드오프를 이해한다
- Leveled vs Size-tiered compaction 의 특성과 선택 기준을 정리한다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
