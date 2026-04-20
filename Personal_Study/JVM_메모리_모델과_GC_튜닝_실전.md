# JVM 메모리 모델과 GC 튜닝 실전

> 2026-04-21 신규 주제 · 확장 대상: JAVA (객체지향 중심 → 런타임 심화)

## 학습 목표

- JVM 메모리 영역과 객체 할당/해제 흐름을 설명할 수 있다
- Generational GC의 가정과 각 콜렉터(Serial/Parallel/G1/ZGC/Shenandoah)의 특성을 비교할 수 있다
- GC 로그를 해석하고 `-Xmx`, `-XX:MaxGCPauseMillis` 등 실전 튜닝 파라미터를 적용할 수 있다
- JFR, Async-profiler로 GC 원인을 분석할 수 있다

## 목차 (PDF 10장 분량)

### 1. JVM Runtime Data Area
- Heap (Young/Old) · Metaspace · Stack · PC Register · Native Method Stack
- 객체 할당 경로: TLAB → Eden → Survivor → Old

### 2. Generational Hypothesis & GC 기본
- 약한 세대 가설 (Weak Generational Hypothesis)
- Minor GC vs Major GC vs Full GC
- Stop-The-World 의미와 영향

### 3. GC 콜렉터 비교

| 콜렉터 | 목적 | 특징 |
| --- | --- | --- |
| Serial | 단일 스레드 | 소규모 앱 |
| Parallel | 처리량 | 배치 |
| G1 | 저지연 + 처리량 | JDK 9+ 기본 |
| ZGC | 초저지연 | 대용량 힙, <10ms |
| Shenandoah | 초저지연 | Red Hat |

### 4. G1 GC 심화
- Region 기반 구조, Humongous Object
- Young Collection · Mixed Collection · Concurrent Marking
- `-XX:MaxGCPauseMillis`, `-XX:G1HeapRegionSize`

### 5. GC 로그 읽기
- `-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10M`
- GC Viewer, GCEasy로 시각화

### 6. 실전 튜닝 시나리오
- 메모리 누수 진단 (`jmap`, `jhat`, MAT)
- Old Gen이 계속 차오르는 경우
- Humongous Object 로 인한 G1 Full GC

### 7. JFR / Async-profiler
- `jcmd <pid> JFR.start`
- Flame Graph 해석

### 8. Spring Boot 애플리케이션 기준 권장 기본값
- 컨테이너 환경 `-XX:+UseContainerSupport` (JDK 10+ 기본)
- `-XX:MaxRAMPercentage=75.0`

## 참고

- 토비의 스프링 VOL1 (기학습) 연계
- Oracle JVM Tuning Guide
- "자바 성능 튜닝 이야기" (이상민)
- JEP 248 (G1 Default), JEP 377 (ZGC Production)

Notion 원본: https://www.notion.so/3485a06fd6d381939498e5b064a7fb61
