Notion 원본: https://app.notion.com/p/3985a06fd6d381908a63f9659779e934

# Redis 영속성 RDB vs AOF와 fork Copy-on-Write 및 AOF rewrite

> 2026-07-09 신규 주제 · 확장 대상: DB

## 학습 목표

- RDB 스냅샷과 AOF 로그의 저장 구조·복구 특성·데이터 손실 범위를 대비한다
- fork() 와 Copy-on-Write 가 백그라운드 저장의 메모리·지연에 미치는 영향을 설명한다
- appendfsync 정책 세 가지의 내구성·성능 trade-off 를 판별한다
- AOF rewrite 와 하이브리드 영속성(RDB preamble)으로 파일 크기와 복구 속도를 최적화한다

## 1. 두 가지 영속성 모델

RDB 는 특정 시점 데이터셋 전체를 바이너리 스냅샷으로 덤프한다. AOF 는 데이터를 변경하는 모든 쓰기 명령을 로그로 순차 기록해 재시작 시 replay 로 복원한다. 둘은 배타적이지 않으며 동시에 켜는 것이 프로덕션 권장이다.

| 항목 | RDB | AOF |
|---|---|---|
| 저장 형태 | 시점 스냅샷 | 쓰기 명령 로그 |
| 손실 범위 | 마지막 스냅샷 이후 전체 | 최대 1초(everysec) |
| 복구 속도 | 빠름 | 느림(명령 재생) |
| 파일 크기 | 작음 | 큼(rewrite로 완화) |

## 2. RDB 스냅샷 트리거와 저장 흐름

`save <seconds> <changes>` 규칙, 수동 SAVE/BGSAVE, 복제 초기 동기화에서 트리거된다. SAVE 는 메인 스레드를 블록해 금지이고, BGSAVE 는 fork() 로 자식이 덤프하는 동안 부모는 요청을 계속 처리한다.

## 3. fork()와 Copy-on-Write

fork() 는 부모·자식이 동일 물리 페이지를 읽기 전용으로 공유하게 하고, 쓰기 시 해당 페이지만 복사한다(COW). 스냅샷 중 쓰기가 많으면 페이지 복제로 메모리가 최대 2배까지 늘 수 있어 OOM 위험이 있다. 대용량에서 fork 자체가 수십~수백 ms 지연(latest_fork_usec)을 유발하며, THP 를 켜면 COW 단위가 커져 악화된다.

```bash
echo never > /sys/kernel/mm/transparent_hugepage/enabled
redis-cli INFO stats | grep latest_fork_usec
```

## 4. AOF 와 appendfsync 정책

`always` 는 매 쓰기마다 fsync(최대 내구성·처리량 급감), `everysec` 는 초당 fsync(최대 1초 손실, 대부분 프로덕션 선택), `no` 는 OS 에 위임(최고 성능, 손실 예측 불가)이다.

```conf
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

everysec 라도 fsync 스레드가 밀리면 append 가 멈춰 지연이 전파될 수 있다.

## 5. AOF rewrite 와 파일 축소

AOF rewrite 는 현재 데이터셋을 재현하는 최소 명령 집합으로 다시 쓴다(100번 INCR → 단일 셀팅). 역시 fork() 로 자식이 새 파일을 만들며, Redis 7 부터 base+incremental 멀티파트 구조로 원자적 교체가 견고해졌다.

## 6. 하이브리드 영속성과 복구 전략

aof-use-rdb-preamble yes(Redis 4+ 기본)는 rewrite 시 base 를 RDB 형식으로 쓰고 tail 을 명령으로 덧붙여 빠른 복구+작은 손실을 동시에 얻는다. 재시작 시 AOF 가 켜져 있으면 AOF 를 먼저 로드한다. 마스터가 영속성을 완전히 끄면 빈 데이터셋이 레플리카로 전파되는 위험이 있다.

## 7. 검증 예시

```bash
#!/usr/bin/env bash
set -euo pipefail
redis-cli CONFIG SET appendonly yes
redis-cli SET durable:key "before-restart"
redis-cli BGREWRITEAOF; sleep 1
redis-cli DEBUG LOADAOF
test "$(redis-cli GET durable:key)" = "before-restart" && echo PASS || exit 1
```

redis-check-rdb / redis-check-aof --fix 로 무결성을, redis-benchmark 로 정책별 처리량을 비교한다.

## 참고

- Redis Documentation — Persistence (RDB, AOF)
- Redis Documentation — fork/COW, THP 권고
- Redis 7 Release Notes — Multi-part AOF
- redis.conf 주석 (appendfsync, auto-aof-rewrite-*, aof-use-rdb-preamble)
