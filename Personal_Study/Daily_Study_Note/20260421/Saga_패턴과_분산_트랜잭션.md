# Saga 패턴과 분산 트랜잭션 (Outbox + CDC)

> 2026-04-21 신규 주제 · 확장 대상: Spring > Spring Cloud (MSA 데이터 일관성 심화)

## 학습 목표

- MSA 환경에서 2PC가 왜 비권장인지 설명할 수 있다
- Choreography/Orchestration Saga의 차이와 Trade-off를 설명할 수 있다
- Outbox Pattern과 Debezium 기반 CDC를 Spring Boot 예제로 구현할 수 있다

## 목차 (PDF 10장 분량)

### 1. 분산 환경에서의 데이터 일관성 문제
- CAP/PACELC
- Dual Write 문제
- 2PC(XA)의 한계: 블로킹, 가용성 저하

### 2. Saga Pattern 개요
- Long-lived Transaction → 로컬 트랜잭션의 시퀀스
- 보상 트랜잭션 (Compensating Transaction)

### 3. Choreography Saga
- 이벤트 기반, 중앙 오케스트레이터 없음
- 장점: 단순, 확장성 / 단점: 순환 의존, 관찰 어려움
- 예제: 주문 → 결제 → 재고 → 배송

### 4. Orchestration Saga
- Orchestrator가 단계별 지시
- Spring StateMachine, Axon, Camunda
- 장점: 관찰성 / 단점: 중앙 집중 리스크

### 5. Transactional Outbox Pattern
- 원자성 보장: DB 커밋 + 이벤트 발행
- Outbox Table → Polling Publisher / CDC
- Spring Data JPA + Scheduler 예제

### 6. Debezium CDC + Kafka
- PostgreSQL Logical Decoding / MySQL Binlog
- `docker-compose.yml` 구성: Zookeeper + Kafka + Debezium Connect
- Kafka Connect Config 예제

### 7. Spring Cloud Stream 구성
- `@StreamListener` 또는 Functional (JDK 8+)
- Binder 설정 (Kafka)
- Dead Letter Queue

### 8. 예제: 주문 Saga
- Order Service: 주문 생성 + Outbox 기록
- Payment/Inventory/Shipping Service 이벤트 소비
- 실패 시 보상 이벤트 발행

### 9. 관찰 & 운영
- Correlation ID 전파
- 멱등성(Idempotency Key)
- 재시도/서킷브레이커 (Resilience4j)

## 참고

- Spring Cloud (기학습) 연계
- Microservices Patterns (Chris Richardson)
- Debezium 공식 문서

Notion 원본: https://www.notion.so/3485a06fd6d3818c96b8d22ecf208e66
