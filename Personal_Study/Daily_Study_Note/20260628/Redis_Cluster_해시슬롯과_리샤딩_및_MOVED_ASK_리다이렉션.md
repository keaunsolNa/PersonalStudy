Notion 원본: https://app.notion.com/p/38d5a06fd6d3816aa157c361aa75940d

# Redis Cluster 해시슬롯과 리샤딩 및 MOVED ASK 리다이렉션

> 2026-06-28 신규 주제 · 확장 대상: Redis

## 학습 목표

- 16384개 해시 슬롯 기반 키 분배와 CRC16 매핑 규칙을 계산 수준으로 이해한다
- MOVED와 ASK 리다이렉션의 의미 차이와 클라이언트 슬롯맵 캐싱 전략을 구분한다
- 리샤딩 중 migrating/importing 상태의 키 이동·일관성 처리를 추적한다
- 해시 태그 멀티키 보장과 핫슬롯 부작용을 트레이드오프로 판단한다

## 16384 슬롯

Redis Cluster는 키를 노드에 직접 매핑하지 않고 16384개 고정 해시 슬롯을 둔다. slot = CRC16(key) mod 16384. 키가 아니라 슬롯을 노드에 배정하는 간접 계층 덕분에 노드 추가/제거 시 슬롯 단위로 소유권만 옮기면 된다. 16384는 슬롯맵 전체를 2KB 비트맵으로 표현해 가십 메시지를 작게 유지하기 위함이다.

## MOVED — 영구 이동

`-MOVED 3999 127.0.0.1:7002`는 슬롯 소유권이 영구히 바뀐 것. 스마트 클라이언트는 로컬 슬롯맵을 CLUSTER SLOTS로 리프레시한다. 캐싱 없이 매번 MOVED를 따라가면 처리량이 반토마이 난다.

## ASK — 일시적

`-ASK 3999 ...`는 슬롯이 마이그레이션 중이고 이 키만 이미 옮겨졌으니 이번만 우회하라는 뜻. 슬롯맵은 갱신하지 않는다. 대상 노드에 가기 전 반드시 ASKING을 먼저 보낸다.

## 리샤딩

소스(migrating)는 키가 아직 있으면 처리, 이미 이동했으면 ASK를 던진다. MIGRATE는 직렬화→전송→복원→삭제를 원자 단위로 처리해 키가 두 노드에 동시 존재하거나 사라지지 않는다.

## 해시 태그

멀티키 연산은 모든 키가 같은 슬롯일 때만 허용. `{...}`가 있으면 중괄호 안만 CRC16에 넣는다. 단 한 슬롯에 키가 몰리면 핫슬롯이 되고 슬롯은 쪼개질 수 없는 최소 단위라 리샤딩으로도 분산 불가다.

| 상황 | 권장 | 이유 |
|---|---|---|
| 관련 키 묶음 | 해시 태그 | 멀티키·트랜잭션 |
| 대량 시계열 | 태그 분산 | 핫슬롯 방지 |

## 일관성

Redis Cluster는 비동기 복제라 마스터가 ACK 후 복제 전 죽으면 쓰기 유실 가능. WAIT로 복제 확인을 강제하거나 cluster-require-full-coverage로 조절한다. CAP 위치는 AP에 가깝다. 스마트 클라이언트는 MOVED에서 전체 맵 갱신, ASK에서는 맵 유지+ASKING으로 처리하고 재시도 상한(기본 5회)을 둔다.

## 참고

- Redis Cluster Specification: https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/
- Scaling: https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/
