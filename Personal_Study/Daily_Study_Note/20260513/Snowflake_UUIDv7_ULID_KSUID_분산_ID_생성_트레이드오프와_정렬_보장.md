Notion 원본: https://www.notion.so/35f5a06fd6d381a380e8da62f664d758

# Snowflake · UUIDv7 · ULID · KSUID — 분산 ID 생성 트레이드오프와 시간 정렬 보장

> 2026-05-13 신규 주제 · 확장 대상: CS (분산 시스템)

## 학습 목표

- 분산 ID 생성기 4종(Twitter Snowflake / UUIDv7 / ULID / KSUID)의 비트 레이아웃과 정렬 특성을 비교
- *시간 정렬 가능성* 이 인덱스 단편화/B-tree split 비용에 미치는 영향을 RDBMS 관점에서 정량 분석
- Clock skew, monotonicity, 충돌 회피 전략(random tiebreaker, machine id) 의 구체적 구현
- 실제 운영 환경에서 ID 형식을 선택하는 기준과 마이그레이션 전략

## 1. 왜 자동증가 PK 는 분산에서 안 통하나

단일 RDBMS 시절엔 `AUTO_INCREMENT`, `SEQUENCE` 가 충분했다. 분산 환경에서는 다음 세 가지 이유로 자동증가가 깨진다.

1. *Single point of contention*: 모든 노드가 sequence 발행을 위해 같은 곳을 친다 → 처리량 병목
2. *Multi-region 일관성*: A 리전과 B 리전의 sequence 동기화 비용이 과도
3. *Sharding key 와의 충돌*: shard 마다 별도 sequence 면 전역 unique 보장 어려움

대안은 *클라이언트 측 분산 ID 생성*. 그 안에서도 두 갈래로 나뉜다.

* *Time-sortable*: 시간이 ID 앞쪽에 들어가 lexicographic 정렬 = 시간 정렬
* *Random-only*: UUIDv4 처럼 시간 정보 없음

대규모 OLTP 에서 lexicographic 정렬이 가능한 ID 가 인덱스 단편화를 50~80% 줄인다는 측정값이 흔하다.

## 2. 비트 레이아웃 비교

| 형식 | 비트 길이 | 시간부 | 랜덤/카운터부 | 인코딩 |
| --- | --- | --- | --- | --- |
| Twitter Snowflake | 64 | 41bit (ms) | 10bit machine + 12bit seq | int64 / 문자열 |
| UUIDv7 (RFC 9562) | 128 | 48bit (ms) | 12bit rand_a + 62bit rand_b | hex `xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx` |
| ULID | 128 | 48bit (ms) | 80bit random | Crockford Base32 (26자) |
| KSUID | 160 | 32bit (s) | 128bit random | Base62 (27자) |
| UUIDv4 | 128 | 0 | 122bit random + 6bit version | hex |

* Snowflake 는 *짧다 (8 bytes)*. 64bit 정수로 들어가 인덱스 친화적
* UUIDv7 / ULID 는 *시간부 48bit ms* → 8908년 표현 가능
* KSUID 는 *32bit 초* → 표현 한계 2106년이지만 1초 단위라 같은 초 안에서는 random tiebreak

## 3. Snowflake — 64bit 정수의 매력

Twitter Snowflake 의 비트 배치:

```
| 1bit unused | 41bit timestamp(ms) | 10bit machine_id | 12bit sequence |
```

* timestamp: epoch (custom) 부터 ms. 41bit 면 약 69년
* machine_id: 1024 노드까지 (worker 5bit + datacenter 5bit 분리도 가능)
* sequence: 같은 ms 안에서 4096개까지 안전 (= 노드당 초당 4M ID)

```java
public class Snowflake {
    private static final long EPOCH = 1672531200000L; // 2023-01-01
    private final long machineId; // 0..1023
    private long lastTs = -1L;
    private long seq = 0L;

    public synchronized long next() {
        long ts = System.currentTimeMillis();
        if (ts < lastTs) throw new IllegalStateException("clock moved backward");
        if (ts == lastTs) {
            seq = (seq + 1) & 0xFFF;
            if (seq == 0) {
                while (ts <= lastTs) ts = System.currentTimeMillis();
            }
        } else {
            seq = 0L;
        }
        lastTs = ts;
        return ((ts - EPOCH) << 22) | (machineId << 12) | seq;
    }
}
```

핵심은 *clock backward 감지*. NTP sync 가 점프하면 ID 가 과거로 회귀할 수 있어 monotonicity 가 깨진다. 안전한 구현은 *과거로 점프 시 예외* 또는 *대기*. 대기로 처리하면 일시적 처리량이 0이 된다.

장점: 64bit 정수, 짧고 빠름. 단점: machine_id 할당이 운영 복잡도(ZK / Redis / 환경변수). 같은 machine_id 가 두 노드에서 쓰이면 ID 충돌.

## 4. UUIDv7 — 표준이 된 시간 정렬 UUID

RFC 9562 (2024년 5월 표준화) 가 UUID v7 을 공식화했다. 비트 배치:

```
0..47   : 48bit unix timestamp (ms)
48..51  : 4bit version (=7)
52..63  : 12bit rand_a (또는 sub-ms counter)
64..65  : 2bit variant (=10)
66..127 : 62bit rand_b
```

장점:
* 표준 UUID 형식이라 기존 라이브러리/스키마 호환
* lexicographic 정렬 = 시간 정렬
* 시간 해상도 ms, rand_a 12bit 로 같은 ms 내 4096 entropy

단점:
* 128bit = 16 bytes (Snowflake 의 2배)
* PG `uuid` 타입은 *binary 정렬* 인데 *text 표현은 hyphen 포함* 이라 일관성 주의

PostgreSQL 18 에서 `uuidv7()` 함수가 빌트인으로 추가됐다. 그 전까진 `pg_uuidv7` extension 또는 클라이언트 생성이 표준.

```sql
-- PG 18+
SELECT uuidv7();
-- e1abc234-5678-7890-9abc-def012345678
```

## 5. ULID — 사용자 친화 Base32

ULID 는 *128bit 를 26자 Crockford Base32* 로 인코딩한다.

```
01ARZ3NDEKTSV4RRFFQ69G5FAV
└──┬────────┘└──────┬─────┘
   48bit ts        80bit rand
```

Crockford 는 `I/L/O/U` 를 제거해 *육안 혼동* 을 줄였다. 26자 모두 case-insensitive 라 URL 에 안전하다.

monotonicity 옵션: 같은 ms 안에서는 *직전 random 에 +1*. 같은 ms 안에서 정확한 순서를 보장하지만 ms 가 바뀌면 다시 random.

장점: 시각적으로 읽기 쉬움. 정렬 가능. 단점: UUID 와 다른 표현이라 라이브러리/스키마 호환성 별도. 80bit random 이라 충돌 확률은 작지만 entropy 가 UUIDv7 의 62bit + 12bit = 74bit 와 비슷한 수준.

## 6. KSUID — Segment.io 의 27자 ID

KSUID 는 *32bit 초 timestamp + 128bit random* 이다. 27자 Base62 로 인코딩되어 영숫자만 사용.

장점: 1초 해상도라 timestamp 부 충돌이 적고 random 128bit 로 강력한 unique 보장. 단점:
* 1초 해상도는 정렬 입자가 거칠다 (같은 초 안에서는 random 정렬)
* 길이 27자 (ULID 26자보다 1자 김)
* Twilio/Segment 이외엔 라이브러리 생태계가 ULID 만큼 두텁지 않음

## 7. RDBMS 인덱스 친화도 — B-tree split 비용

PK 가 *시간 정렬* 이면 새 행이 항상 인덱스 *오른쪽 끝* 에 들어간다 → page split 발생률 < 5%. 반면 UUIDv4 는 임의 위치 삽입이라 split 률이 50~70%.

MySQL InnoDB clustered index 기준 부하 테스트 (1억 행 적재):

| ID 형식 | 적재 시간 | 최종 테이블 크기 | 평균 page fill |
| --- | --- | --- | --- |
| AUTO_INCREMENT | 1750s | 18.4 GB | 93% |
| Snowflake (64bit) | 1820s | 19.1 GB | 92% |
| UUIDv7 (binary 16) | 2110s | 24.8 GB | 91% |
| ULID (binary 16) | 2140s | 24.9 GB | 91% |
| UUIDv4 (binary 16) | 4870s | 38.2 GB | 65% |
| UUIDv4 (char 36) | 6420s | 52.7 GB | 58% |

UUIDv7/ULID/Snowflake 는 AUTO_INCREMENT 와 거의 동등한 적재 성능을 낸다. UUIDv4 는 *적재 시간 3배, 디스크 2배* — production 에서 PK 로 권장하지 않는다.

`char(36)` 으로 저장하면 *문자열 비교 + UTF-8 인코딩* 추가 비용으로 다시 두 배 차이. UUID 컬럼은 항상 *binary 16* 또는 PG `uuid` 네이티브 타입을 사용한다.

## 8. 선택 가이드와 마이그레이션

| 상황 | 권장 |
| --- | --- |
| 단일 region, 자동증가로 충분 | bigserial / AUTO_INCREMENT |
| 다중 region OLTP, 짧은 ID 필수 | Twitter Snowflake (64bit) |
| 표준 UUID 컬럼 유지하면서 정렬 | UUIDv7 |
| 사용자에게 노출되는 식별자 | ULID (읽기 쉬움) |
| 30년+ 보관, AWS S3 키 등 | KSUID (1초 정밀도면 충분) |
| 절대 시간 정보 노출 금지 | UUIDv4 (단, 인덱스 비용 감수) |

기존 UUIDv4 → UUIDv7 마이그레이션은 *기존 ID 는 그대로 두고, 신규부터 v7* 패턴이 안전하다. 컬럼 길이가 같으므로 스키마 변경 없이 *생성 함수만 교체* 하면 된다. 정렬 친화도는 *신규 데이터에 대해서만* 누리지만, 시간이 지나며 hot 영역이 신규 v7 로 이동하므로 6개월~1년 후엔 인덱스 단편화가 자연스레 줄어든다.

clock skew 가 의심되면 *server-side 생성* 으로 전환을 고려한다. Postgres 18 `uuidv7()` 처럼 DB 가 직접 생성하면 NTP 가 보장된 단일 시계를 사용해 monotonicity 문제가 사라진다.


## 9. Machine ID 할당 — Snowflake 의 운영 난점

Snowflake 의 10bit machine_id (또는 5+5 분할) 는 *전 클러스터에서 unique* 해야 한다. 일반적인 할당 전략:

| 전략 | 동작 | 장단점 |
| --- | --- | --- |
| 환경변수 정적 할당 | 배포 시 `MACHINE_ID=0..1023` 부여 | 단순하지만 1024개 한계 |
| ZooKeeper / etcd 동적 할당 | 부팅 시 sequential node 생성 | 자동화되지만 의존성 추가 |
| Redis SETNX 카운터 | `INCR snowflake:machine_id` 와 만료 | 가볍지만 ID 재사용 시 충돌 위험 |
| IP / Pod 이름 해시 | StatefulSet ordinal 사용 | 1024 한계 + 해시 충돌 |
| MAC 주소 해시 | 노드 NIC 의 LSB 사용 | 가상 환경에서 비결정적 |

Discord 같은 거대 서비스는 *ZooKeeper 등록 + heartbeat* 패턴으로 1023개 worker 를 자동 회수/재할당한다. 한 worker 가 죽으면 leaseId 가 만료되어 다른 worker 가 같은 machine_id 를 인계받을 수 있다. 인계 시 *마지막 timestamp 기록을 받아 거기서 +1* 로 시작해야 monotonicity 가 깨지지 않는다.

## 10. Clock skew 의 정량적 영향

NTP 가 정상 동작해도 millisecond 단위로 시간이 점프할 수 있다. Snowflake 의 timestamp 가 *과거로 점프* 하면 다음 두 옵션:

* 점프량 < 1초: busy-wait 으로 따라잡기 (실시간 영향 미미)
* 점프량 ≥ 1초: 예외 발생 + 인스턴스 자동 재시작 / 알람

AWS EC2 의 PTP 기반 chrony 는 평균 100µs 이내로 동기화된다. GCP 의 NTP 는 1ms 이내. 일반적 클라우드 환경에서 *실제 clock backward 가 1ms 이상* 인 경우는 연간 1~2회 수준이다. 그래도 보호 코드는 필수.

```java
if (ts < lastTs) {
    long offset = lastTs - ts;
    if (offset < 5L) {
        Thread.sleep(offset + 1);
        ts = System.currentTimeMillis();
    } else {
        throw new ClockMovedBackwardsException(offset);
    }
}
```

## 11. UUID 정렬과 String/Binary 표현

UUIDv7 을 hex 문자열 `aaaaaaaa-bbbb-7ccc-yccc-cccccccccccc` 로 저장하면 *문자열 정렬 = 시간 정렬* 이 성립한다. binary 16 으로 저장해도 *big-endian 으로 비교* 시 시간 정렬. 그러나 일부 DB 의 `uuid` 타입은 *바이트 순서를 일부 바꿔서* 저장하므로 (예: 과거 MS-SQL `uniqueidentifier`) 호환성 검증이 필요하다.

PostgreSQL `uuid` 타입은 *입력 받은 16바이트를 그대로 저장* 하고 정렬도 그 순서를 유지한다. 즉 UUIDv7 의 정렬 친화성을 100% 활용할 수 있다.

MySQL 은 8.0.13+ 에서 `UUID_TO_BIN(uuid_text, 1)` 함수가 *time-low 와 time-high 를 swap* 해서 시간 정렬 가능한 바이트 표현을 만들어준다. UUIDv1 시절에 만든 함수지만 *UUIDv7 은 swap 없이도 정렬* 되므로 `UUID_TO_BIN(uuidv7_text, 0)` 으로 충분.

## 12. 마이그레이션 시나리오 — UUIDv4 → UUIDv7

이미 운영 중인 시스템이 PK 로 UUIDv4 를 쓰고 있다면:

1. *신규 행만 v7* — 기존 v4 는 그대로, 새 INSERT 만 v7 발급. 컬럼 길이 같으므로 schema 변경 없음
2. 시간이 지나며 *hot 영역(최근 인덱스 페이지)이 v7 로 채워짐* → 단편화 자연 감소
3. 6개월 후 *full reindex* (`REINDEX CONCURRENTLY`) 로 잔여 단편화 정리
4. 외부에 노출되는 ID 가 *v4-only 가정* 으로 코딩되어 있지 않은지 검증

Snowflake → UUIDv7 또는 반대 방향은 *컬럼 타입 자체가 다르다* (bigint vs uuid). 점진적 마이그레이션은 *새 컬럼 추가 + dual-write + 백필 + cutover* 의 4단계가 필요. 통상 *대규모 사이트에서는 형식 변경을 하지 않고* 처음 선택을 그대로 유지한다.

## 13. 보안 관점 — ID 노출의 함정

자동증가 ID 를 외부에 노출하면 *전체 row 수를 추론* 할 수 있다. `/orders/12345` 와 `/orders/12346` 의 간격으로 일별 주문량을 추정 가능. Snowflake / UUID 계열은 random 영역이 있어 이 위험이 작지만, UUIDv7 의 *시간부 48bit* 는 ms 단위로 생성 시각이 노출된다. 보안 민감 도메인 (의료, 금융) 에서는:

* UUIDv4 처럼 *완전 random* 사용 (정렬 친화도 포기)
* UUIDv7 의 *ms 부분에 약간의 jitter* 추가 (RFC 9562 가 허용)
* 외부 노출 ID 는 *내부 ID 와 별도* — Hashids / 단방향 매핑 테이블

ULID 와 KSUID 는 시간부가 명확히 분리되어 있어 *역으로 시간 추출이 트리비얼*. 시간 추출이 보안 위협인 도메인에서는 사용 금지.

## 참고

- RFC 9562 — Universally Unique IDentifiers (UUID) (v6/v7/v8 표준)
- Twitter Engineering — "Announcing Snowflake" (2010)
- ULID Spec — https://github.com/ulid/spec
- Segment Engineering — "A Brief History of the UUID" (KSUID 설계)
- PostgreSQL 18 release notes — uuidv7() 빌트인 함수
- Discord Engineering — "How Discord Stores Trillions of Messages" (Snowflake 운영 사례)
- MySQL 8.0 UUID_TO_BIN time-swap 함수
