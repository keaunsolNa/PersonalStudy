Notion 원본: https://www.notion.so/36f5a06fd6d381c59588d6c5bd1974af

# Redis Cluster Resharding CLUSTER SETSLOT MIGRATE와 Hash Tag

> 2026-05-29 신규 주제 · 확장 대상: Redis

## 학습 목표

- 16384 개 슬롯의 소유권 이동을 `CLUSTER SETSLOT` IMPORTING/MIGRATING 과 `MIGRATE` 로 안전하게 수행한다
- Hash tag(`{...}`) 가 key 의 슬롯 결정에 미치는 영향과 multi-key 명령의 제약을 이해한다
- 슬롯 이동 중 `MOVED`/`ASK` redirection 의 차이와 client 측 대응을 설명한다
- 운영 환경에서 데이터 skew 와 cluster size scale-out 의 trade-off 를 판단한다

## 1. Redis Cluster 의 슬롯 모델

Redis Cluster 는 모든 key 를 `CRC16(key) mod 16384` 로 슬롯 번호에 매핑하고, 각 슬롯의 owner master node 가 그 슬롯의 모든 key 에 대한 쓰기 권한을 갖는다. 슬롯과 노드의 매핑은 gossip 으로 전파되고, 각 노드는 자기가 보유한 슬롯 셋을 `CLUSTER NODES` 응답에 broadcast 한다. 16384 라는 수는 cluster bus 패킷에 슬롯 비트맵을 2KB(16384/8)로 담기 위해 선택된 값이다. 슬롯 수가 너무 많으면 gossip 패킷이 커지고, 너무 적으면 균등 분포가 어렵다.

resharding 의 목적은 두 가지다. (a) 노드 추가/제거에 따른 슬롯 재배치, (b) hot slot 의 부하 분산. (b) 는 사실 슬롯 단위로는 해소되지 않고 key 단위 redistribution 이 필요하다. 슬롯 자체를 옮기는 것은 (a) 의 책임이다.

## 2. SETSLOT IMPORTING / MIGRATING 의 정확한 의미

슬롯 하나를 source 에서 target 으로 옮기는 절차는 다음과 같다. 절차는 atomic 이 아니며 중간 상태가 있다.

```
1) target> CLUSTER SETSLOT <slot> IMPORTING <source-node-id>
2) source> CLUSTER SETSLOT <slot> MIGRATING <target-node-id>
3) source> CLUSTER GETKEYSINSLOT <slot> <count>  // 반복
   source> MIGRATE <target-host> <target-port> "" 0 5000 KEYS <k1> <k2> ...
4) (모든 키 이동 완료 후)
   all masters> CLUSTER SETSLOT <slot> NODE <target-node-id>
```

`IMPORTING` 상태의 target 노드는 그 슬롯의 key 에 대해 client 가 ASK redirect 로 명시적으로 보낸 명령만 수행한다. `MIGRATING` 상태의 source 노드는 그 슬롯의 key 가 자기에게 있으면 정상 처리하고, 없으면 ASK redirect 로 target 으로 보낸다. 슬롯 이동 중에도 client traffic 은 양쪽 노드를 함께 사용한다. `MIGRATE` 명령 자체는 key 단위로 atomic 하다. RDB serialize → target 으로 전송 → target 에서 RESTORE → source 에서 DEL 까지가 하나의 원자적 단위다.

## 3. Hash Tag 의 슬롯 결정 규칙

`CRC16` 의 입력은 일반적으로 key 전체이지만, key 에 `{...}` 로 감싼 부분(hash tag)이 있으면 그 안쪽만 입력이 된다. 정확한 규칙은 다음과 같다.

```
key = "user:{1234}:profile"
→ CRC16 input = "1234"
→ slot = CRC16("1234") % 16384
```

여러 개의 `{` 가 있어도 첫 번째 `{` 와 그 뒤의 첫 번째 `}` 사이만 본다. 빈 hash tag (`{}`) 는 무시되고 key 전체가 입력이 된다. 이 규칙의 실용적 의미는 **같은 hash tag 를 가진 key 들은 반드시 같은 슬롯에 모인다** 는 점이다. 따라서 multi-key 명령(MGET, MSET, SUNION, EVAL with multiple KEYS) 은 모든 key 가 같은 hash tag 를 가질 때만 실행된다. 그렇지 않으면 `CROSSSLOT` 에러로 거절된다.

```redis
> MGET user:{1234}:profile user:{1234}:settings
1) "..."
2) "..."

> MGET user:{1234}:profile user:{5678}:profile
(error) CROSSSLOT Keys in request don't hash to the same slot
```

설계 함정은 hash tag 가 너무 큰 단위로 묶이는 경우다. 예를 들어 `{tenant_42}` 로 모든 키를 묶으면 tenant 42 의 모든 데이터가 하나의 슬롯, 즉 하나의 마스터 노드에 몰린다. tenant 가 크면 hot node 가 된다. 권장 원칙은 "트랜잭션이나 multi-key 명령으로 묶일 가장 작은 단위만 hash tag 로 만든다" 이다.

## 4. MOVED 와 ASK redirection 의 차이

`MOVED` 는 슬롯의 **영구 소유자가 바뀌었음** 을 client 에 알린다. client 는 슬롯-노드 매핑 cache 를 갱신해야 한다. `ASK` 는 슬롯의 **그 key 만 일시적으로 다른 노드** 에 있음을 알린다. client 는 cache 를 갱신하지 않고, 다음 명령에 `ASKING` prefix 를 붙여 한 번만 target 으로 보낸다. `ASKING` 은 다음 1 개 명령에만 유효하다.

## 5. redis-cli reshard 운영 절차

자동 도구는 redis-cli 의 `--cluster reshard` 가 사실상 표준이다.

```bash
redis-cli --cluster reshard 10.0.0.1:6379 \
  --cluster-from <A-id> \
  --cluster-to <B-id> \
  --cluster-slots 1000 \
  --cluster-yes \
  --cluster-pipeline 50 \
  --cluster-timeout 60000
```

`--cluster-pipeline` 은 `MIGRATE ... KEYS k1 k2 ...` 호출당 옮길 key 개수다. 너무 크면 target 의 socket buffer 가 압박되고, 너무 작으면 RTT 가 throughput 의 병목이 된다. 1KB 평균 value 라면 30~80 사이가 실용 범위다. `--cluster-timeout` 은 단일 MIGRATE 의 RTT 한계다. 큰 value(예: 100MB 단일 hash) 가 있으면 timeout 을 늘리거나 미리 키를 잘라야 한다.

## 6. 데이터 스큐 진단 — BIGKEY 와 슬롯별 메모리

`redis-cli --memkeys` 와 `--bigkeys` 는 노드 단위로 큰 key 를 찾는다. 슬롯 단위로는 자체 명령이 없으므로 다음처럼 집계한다.

```bash
for SLOT in $(seq 0 16383); do
  CNT=$(redis-cli CLUSTER COUNTKEYSINSLOT $SLOT)
  [ "$CNT" -gt 1000 ] && echo "$SLOT $CNT"
done | sort -k2 -n -r | head -20
```

count 가 크게 튀는 슬롯은 hash tag 로 강하게 묶인 데이터다. 슬롯을 분할해도 해소되지 않으므로 application 레벨에서 hash tag 설계를 다시 한다.

## 7. SCALE-OUT 과 replication factor 의 trade-off

3 master + 3 replica 에서 6 master + 6 replica 로 가는 scale-out 은 슬롯의 절반을 새 노드로 옮기는 작업이다.

| 슬롯 수 | 키 수 | 데이터(MB) | 예상 시간 |
| --- | --- | --- | --- |
| 1000 | 1M | 1024 | 2~5 분 |
| 8192 | 8M | 8192 | 20~40 분 |

resharding 동안 cluster 는 정상 동작하지만 throughput 은 20~30% 하락한다. 절반의 슬롯을 동시에 옮기면 안 되고, 한 번에 수십 개 슬롯씩 끊어 진행해야 한다.

## 8. 운영 체크리스트

가장 흔한 장애는 (a) 이동 중 source 노드가 죽어 슬롯이 양쪽에 있는 상태로 멈춤, (b) `SETSLOT NODE` 가 일부 마스터에만 전파되어 일부 client 가 ASK redirect 무한루프, (c) hot hash tag 로 한 노드 메모리가 maxmemory 초과. (a) 의 회복은 `CLUSTER SETSLOT <slot> STABLE` 로 양쪽 노드의 플래그를 해제하고, 어느 쪽이 진짜 owner 인지 결정해 `SETSLOT NODE` 를 모든 master 에 broadcast 한다. (b) 는 `redis-cli --cluster check` 가 잡아낸다. fix 는 `redis-cli --cluster fix` 가 자동 수정한다. (c) 는 hash tag 재설계 외에 단기 처방이 없다.

마지막으로 production 에서 `MIGRATE` 의 default timeout 5000ms 가 너무 짧은 경우가 많다. 큰 value 가 있는 환경은 `--cluster-timeout 60000` 이상으로 잡고, application 의 socket timeout 도 함께 늘려둔다.

## 참고

- Redis Cluster Specification — antirez/redis-doc
- Redis Cluster tutorial — redis.io documentation
- MIGRATE command reference — redis.io/commands/migrate
- CLUSTER SETSLOT — redis.io/commands/cluster-setslot
