Notion 원본: https://app.notion.com/p/40f5a06fd6d381bdac45e40cdd01e338

# Redis Cluster 해시 슬롯과 MOVED ASK 리다이렉션 및 리샤딩 · 클라이언트 토폴로지 갱신

> 2026-11-05 신규 주제 · 확장 대상: Redis

## 학습 목표

- 키가 16384개 해시 슬롯에 매핑되는 방식과 해시 태그의 의미를 계산으로 확인한다
- MOVED와 ASK 리다이렉션의 차이를 슬롯 마이그레이션 단계와 연결한다
- 리샤딩 중 다중 키 명령과 트랜잭션이 실패하는 조건을 식별한다
- Lettuce 클러스터 토폴로지 갱신 옵션을 장애 시나리오에 맞춰 설정한다

## 1. 슬롯 기반 샤딩

Redis Cluster는 일관된 해싱 링 대신 고정된 16384개의 해시 슬롯을 쓴다. 키가 속한 슬롯은 `CRC16(key) mod 16384`로 결정되고, 각 마스터 노드는 슬롯 구간의 부분집합을 소유한다. 슬롯 소유 정보는 노드끼리 gossip 프로토콜로 교환하며, 하트비트에 슬롯 비트맵이 실리기 때문에 16384슬롯은 비트맵이 2KB로 유지되도록 선택된 값이다. 노드가 1000개를 넘는 클러스터는 설계 목표가 아니라고 개발자가 밝힌 바 있다. 클러스터 버스는 클라이언트 포트에 10000을 더한 포트(기본 16379)를 쓰므로 방화벽에서 두 포트 모두 열어야 한다.

```bash
redis-cli -c -p 7000 CLUSTER KEYSLOT user:1001      # 슬롯 번호 계산
redis-cli -c -p 7000 CLUSTER NODES                  # 노드별 슬롯 구간과 역할
redis-cli -c -p 7000 CLUSTER SHARDS                 # 샤드 단위 정보 (7.0+)
redis-cli --cluster check 127.0.0.1:7000            # 슬롯 커버리지 점검
```

## 2. 해시 태그와 CROSSSLOT

키 안에 중괄호 `{...}`가 있으면 첫 번째 `{`와 그 뒤 첫 번째 `}` 사이 비어 있지 않은 문자열만 해시에 쓰인다. 따라서 `{user:1001}:profile`과 `{user:1001}:orders`는 같은 슬롯에 놓인다. 다중 키 명령(MGET, SUNION, 트랜잭션 MULTI/EXEC, Lua 스크립트의 KEYS)은 모든 키가 같은 슬롯에 있어야 하며 아니면 `CROSSSLOT Keys in request don't hash to the same slot` 오류가 난다. 해시 태그는 이를 해결하지만 특정 태그에 키가 몰리면 슬롯 한 곳이 핫스팟이 된다. 슬롯은 마이그레이션 단위이므로 한 슬롯의 키 총량이 매우 커지면 이동 시간도 길어진다.

## 3. MOVED 리다이렉션

클라이언트가 잘못된 노드에 명령을 보내면 노드는 `-MOVED <slot> <host:port>`를 응답한다. 이는 슬롯 소유가 영구적으로 바뀌었다는 뜻이다. 스마트 클라이언트는 응답을 받으면 해당 슬롯의 라우팅 테이블을 갱신하고 새 노드에 재시도한다. `redis-cli -c`는 리다이렉션을 자동으로 따라간다. MOVED가 지속적으로 발생한다면 클라이언트의 슬롯 캐시가 오래된 것이므로 토폴로지 갱신 설정을 점검해야 한다.

## 4. 리샤딩과 ASK 리다이렉션

슬롯 이동은 키를 하나씩 `MIGRATE`로 옮기며 진행된다. 원본 노드는 해당 슬롯을 MIGRATING, 대상 노드는 IMPORTING 상태로 둔다. MIGRATING 슬롯에 대한 요청은 키가 아직 원본에 있으면 원본이 처리하고, 이미 옮겨졌으면 `-ASK <slot> <host:port>`로 대상 노드를 알려준다. 클라이언트는 대상 노드에 먼저 `ASKING` 명령을 보낸 다음 원래 명령을 보내야 IMPORTING 노드가 이를 처리한다. MOVED와 달리 ASK는 일회성이라 클라이언트가 슬롯 테이블을 갱신하지 않는다. 이동이 완료되고 `CLUSTER SETSLOT <slot> NODE <id>`가 실행되면 이후부터는 MOVED가 나온다.

```bash
# 슬롯 1000개를 새 노드로 이동
redis-cli --cluster reshard 127.0.0.1:7000 \
  --cluster-from <source-node-id> --cluster-to <target-node-id> \
  --cluster-slots 1000 --cluster-yes

# 균형 재조정
redis-cli --cluster rebalance 127.0.0.1:7000 --cluster-use-empty-masters
```

리샤딩 도중 같은 슬롯의 다중 키 명령은 일부 키가 이미 이동했을 수 있어 `TRYAGAIN`을 받을 수 있다. 클라이언트는 짧은 백오프 후 재시도해야 한다. 큰 값(수십 MB 이상의 키)은 MIGRATE 동안 두 노드를 블로킹하므로 리샤딩 전에 큰 키를 분할하는 것이 안전하다.

## 5. 장애 조치

마스터가 `cluster-node-timeout` 이상 응답하지 않으면 다른 노드들이 PFAIL로 표시하고, 과반수 마스터가 동의하면 FAIL로 확정된다. 그다음 해당 마스터의 레플리카들이 선거를 열고 과반수 득표를 얻은 레플리카가 새 마스터로 승격된다. 레플리카가 없는 마스터가 죽으면 그 슬롯은 커버되지 않는다. `cluster-require-full-coverage yes`(기본값)이면 슬롯 일부가 비어도 클러스터 전체가 쓰기를 거부하며, `no`로 두면 살아 있는 슬롯은 계속 서비스한다. 가용성과 일관성 사이의 선택이다. 비동기 복제 특성상 마스터가 확인 응답을 보낸 쓰기도 승격 시 유실될 수 있다는 점은 변하지 않는다. `WAIT`로 복제 확인 수를 요구해 완화할 수 있지만 강한 일관성은 보장하지 않는다.

## 6. 클라이언트 토폴로지 갱신 (Lettuce)

Lettuce는 기본적으로 시작 시점의 토폴로지만 쓰고 자동 갱신하지 않는다. 장애 조치나 리샤딩 뒤에도 정상 동작하려면 주기 갱신과 적응형 갱신을 명시해야 한다. 적응형 갱신 트리거는 MOVED_REDIRECT, ASK_REDIRECT, PERSISTENT_RECONNECTS, UNKNOWN_NODE, UNCOVERED_SLOT 등이다.

```java
ClusterTopologyRefreshOptions topology = ClusterTopologyRefreshOptions.builder()
    .enablePeriodicRefresh(Duration.ofSeconds(30))
    .enableAllAdaptiveRefreshTriggers()
    .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(10))
    .build();

ClusterClientOptions options = ClusterClientOptions.builder()
    .topologyRefreshOptions(topology)
    .validateClusterNodeMembership(false)
    .maxRedirects(3)
    .build();

LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
    .clientOptions(options)
    .readFrom(ReadFrom.REPLICA_PREFERRED)
    .build();
```

`ReadFrom.REPLICA_PREFERRED`는 읽기를 레플리카로 분산하지만 복제 지연 때문에 오래된 값을 읽을 수 있으므로, 읽기 직후 일관성이 필요한 경로에는 마스터 읽기를 고정한다. `adaptiveRefreshTriggersTimeout`은 갱신 폭주를 막는 최소 간격이다. 값을 너무 크게 잡으면 장애 직후 오래된 라우팅이 길게 남는다.

## 7. 운영 점검 항목

`CLUSTER INFO`의 `cluster_state:ok`와 `cluster_slots_assigned:16384`, `cluster_slots_fail:0`을 알람 지표로 둔다. 노드별 `used_memory`와 슬롯당 키 수(`CLUSTER COUNTKEYSINSLOT`)의 편차를 모니터링해 해시 태그로 인한 불균형을 조기에 발견한다. 데이터베이스 번호는 클러스터 모드에서 0번만 지원되고 `SELECT`는 사용할 수 없다. 키 설계 단계에서 다중 키 연산이 필요한 단위를 해시 태그로 묶고, 그 외에는 키 분산을 우선한다.

## 참고

- Redis 공식 문서 — Scale with Redis Cluster, Redis Cluster Specification
- Lettuce Reference — Redis Cluster, Cluster-specific options
- redis-cli 문서 — --cluster 하위 명령
