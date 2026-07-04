Notion 원본: https://app.notion.com/p/3935a06fd6d3817091dcef2dcd0606b8

# MySQL InnoDB 버퍼풀과 Change Buffer 및 Adaptive Hash Index

> 2026-07-05 신규 주제 · 확장 대상: Oracle

## 학습 목표

- InnoDB 버퍼풀의 LRU 변형(Young/Old 서브리스트) 동작과 midpoint insertion 전략을 설명한다
- Change Buffer 가 세컨더리 인덱스의 랜덤 쓰기를 어떻게 지연·병합하는지 파악한다
- Adaptive Hash Index(AHI) 가 B+Tree 탐색을 언제 단축하고 언제 역효과를 내는지 판단한다
- 버퍼풀 인스턴스 분할과 innodb_buffer_pool_size 튜닝의 실측 기준을 세운다

본 노트는 로컬 저장소와 Notion 에 동기화된 확장 학습 노트다. 전체 본문은 Notion 원본 또는 로컬 마크다운에서 확인한다.
