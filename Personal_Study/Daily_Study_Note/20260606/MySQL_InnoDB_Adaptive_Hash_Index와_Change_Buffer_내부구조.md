Notion 원본: https://www.notion.so/3775a06fd6d381518547ef4f01d82b86

# MySQL InnoDB Adaptive Hash Index와 Change Buffer 내부구조

> 2026-06-06 신규 주제 · 확장 대상: SQLD

## 학습 목표

- Adaptive Hash Index(AHI)가 B+Tree 탐색을 어떻게 단축하고 언제 역효과를 내는지 분석한다
- Change Buffer가 secondary index 변경 I/O를 지연·병합하는 메커니즘을 설명한다
- AHI latch 경합과 change buffer merge가 운영 지표에 남기는 흔적을 해석한다
- 워크로드 특성에 따라 두 기능을 켜고 끄는 판단 기준을 세운다

## 1. 두 기능의 공통 목적 — buffer pool 효율 극대화

InnoDB는 모든 데이터/인덱스를 16KB page 단위로 buffer pool에 캐싱한다. AHI와 Change Buffer는 모두 buffer pool을 더 똑똑하게 쓰는 최적화지만 방향이 반대다. AHI는 읽기 경로를 빠르게(B+Tree 하강을 hash 한 번으로 단축) 만들고, Change Buffer는 쓰기 경로의 random I/O를 지연·병합한다. 둘 다 일반적으로 이득이지만 특정 워크로드에서는 오히려 병목이 되므로 내부 동작을 이해해야 한다.

## 2. B+Tree 탐색 비용 복습

InnoDB의 clustered/secondary index는 B+Tree다. 키 하나를 찾으려면 root → branch → leaf로 내려가며 각 레벨에서 binary search를 한다. 트리 높이가 3~4면 page를 3~4번 탐색한다. 같은 키를 반복 조회하는 패턴에서 이 하강 비용이 누적된다. AHI는 자주 접근하는 키는 leaf page로 바로 점프하자는 발상이다.

## 3. Adaptive Hash Index — B+Tree 위의 hash 캐시

InnoDB는 특정 index page 접근 패턴을 모니터링한다. 어떤 prefix로 충분히 자주, 일관되게 접근하면 자동으로 hash index를 메모리에 구축한다. 이후 같은 패턴 조회는 B+Tree를 타지 않고 hash lookup 한 번으로 leaf 위치를 얻는다. 엔진이 적응적으로 만든다는 점이 핵심이다.

```sql
SHOW ENGINE INNODB STATUS\G
-- "INSERT BUFFER AND ADAPTIVE HASH INDEX" 섹션:
-- 0.00 hash searches/s, 0.00 non-hash searches/s
SET GLOBAL innodb_adaptive_hash_index = OFF;
-- innodb_adaptive_hash_index_parts = 8 (기본)
```

hash searches/s 대 non-hash searches/s 비율이 AHI 효용의 직접 지표다. AHI는 공유 자료구조라 latch로 보호하는데, 동시성이 매우 높고 키 분포가 넓은 OLTP에서는 이 latch가 경합 지점이 된다. 넓은 키의 고동시성 point lookup, secondary index를 자주 DROP/생성하는 경우 AHI가 손해다.

## 4. Change Buffer — secondary index 변경의 지연 병합

clustered index(PK)는 보통 순차 증가해 삽입이 page 끝에 몰리지만, secondary index는 키 순서가 데이터 삽입 순서와 무관해 변경이 buffer pool 전역에 random하게 흩어진다. 변경 대상 leaf page가 buffer pool에 없으면 디스크에서 읽어와야(read-on-write) 하는 random read I/O가 쓰기를 느리게 만든다. Change Buffer는 대상 page가 buffer pool에 없으면 즉시 적용하지 않고 change buffer에 기록했다가, 나중에 그 page가 적재될 때 merge한다.

```sql
SET GLOBAL innodb_change_buffering = 'all';
-- none / inserts / deletes / changes / purges / all
SET GLOBAL innodb_change_buffer_max_size = 25;  -- buffer pool 대비 %
```

Change Buffer는 non-unique secondary index에만 적용된다. unique index는 삽입 시 유일성 검사를 위해 해당 page를 반드시 읽어야 하므로 버퍼링 이점이 없다.

## 5. Change Buffer Merge가 만드는 운영 패턴

change buffer는 평소 쓰기를 빠르게 하지만, 버퍼링된 변경이 누적됐다가 한꺼번에 merge될 때 읽기 지연 스파이크를 만들 수 있다. 대량 INSERT 후 조회 시작 시, 서버 재시작 직후가 대표적이다.

```sql
SELECT name, count FROM information_schema.INNODB_METRICS
WHERE name LIKE 'ibuf%' AND status='enabled';
```

| 지표 | 의미 | 주의 신호 |
|---|---|---|
| ibuf size | 현재 버퍼링된 변경량 | max_size 근접 = 적체 |
| ibuf_merges | merge 발생 횟수 | 급증 = 읽기 지연 가능성 |
| merged ops / merges | merge당 병합된 연산 수 | 높을수록 I/O 절감 효과 큼 |

## 6. 워크로드별 튜닝 판단

| 워크로드 | AHI | Change Buffer |
|---|---|---|
| 좁은 키 반복 조회 | ON 유지 | 영향 적음 |
| 넓은 키 고동시성 lookup | OFF 실험 | inserts로 제한 |
| 대량 INSERT, index 다수 | ON 무방 | all 유지 |
| SSD/NVMe | 워크로드 따라 | none~inserts 고려 |

NVMe처럼 random I/O가 싼 스토리지에서는 change buffer 이점이 줄어 inserts나 none을 벤치마크로 비교할 가치가 있다. 반대로 HDD나 I/O가 비싼 환경, secondary index가 많은 대량 적재에서는 all이 큰 이득이다.

## 7. 실험 설계 — 끄고 켜는 검증법

sysbench로 동일 부하를 고정하고 AHI ON/OFF에서 QPS/p99를 비교한다. AHI는 hash searches/s가 non-hash보다 의미 있게 높고 latch 경합이 낮으면 켜 둔다. Change Buffer는 merged ops / merges가 1보다 충분히 크면 유지하고, merge 지연 스파이크가 SLA를 위협하면 범위를 좁힌다.

## 8. 워크된 시나리오 — 대량 적재 후 조회 스파이크

1억 건 주문 테이블에 secondary index 세 개가 걸려 있고 야간 배치로 1천만 건을 INSERT한 뒤 아침에 customer_id로 조회가 몰린다고 하자. 배치 동안 leaf page 대부분이 buffer pool에 없어 Change Buffer가 버퍼링해 INSERT가 빠르다. 문제는 아침이다. 조회 시 leaf page가 적재되며 버퍼링된 변경이 merge돼 첫 조회들의 지연이 튄다. 완화책은 조회 전 인덱스 워밍업으로 merge를 한산한 시간대에 분산하거나, 지연 SLA가 엄격하면 change buffering을 inserts로 좁히는 것이다. 핵심은 각 기능이 특정 시점의 비용을 다른 시점으로 미루므로, 그 시점이 SLA에 닿지 않게 분산시켜야 한다는 점이다.

## 9. 정리 — 적응형 최적화의 양면성

AHI와 Change Buffer는 엔진이 워크로드를 관찰해 자동 적용하는 적응형 최적화다. 대부분의 일반 OLTP에서는 기본값(둘 다 ON)이 정답이지만, 고동시성·넓은 키·고속 스토리지처럼 가정이 깨지는 환경에서는 비용이 된다. 핵심은 추측이 아니라 SHOW ENGINE INNODB STATUS와 INNODB_METRICS로 실제 hit ratio와 merge 효율을 측정한 뒤 끄고 켜는 것이다.

## 참고

- MySQL 8.0 Reference Manual — "Adaptive Hash Index" (15.5.3)
- MySQL 8.0 Reference Manual — "Change Buffer" (15.5.2)
- MySQL 8.0 Reference Manual — "Configuring InnoDB Change Buffering"
- Jeremy Cole, "InnoDB: A journey to the core"
- Percona Blog — "InnoDB Adaptive Hash Index" latch 경합 사례
