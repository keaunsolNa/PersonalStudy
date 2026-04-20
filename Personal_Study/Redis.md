# Redis

> Notion 원본: <https://www.notion.so/1dc5a06fd6d3800e9c72e2d9f4e85b4a>
> 동기화일: 2026-04-21

# 개념
- Redis는 Remote Dictionary Server의 약자로, 메모리에 데이터를 저장하는 고속 키-값 저장소이다. 단일 서버로도 동작하며, 클러스터 구성을 통해 수평 확장이 가능하다.
- 오픈 소스이며 BSD 라이선스를 사용하는 고급 키-값 저장소이며, 키에 문자열, 해시, 리스트, 세트, 정렬된 세트를 포함할 수 있으므로 데이터 구조 서버라고도 한다.
- Redis는 표준 C로 작성된 인메모리 데이터 저장소이다.
- Redis의 주요 특징은 다음과 같다.
  1. Redis는 데이터베이스를 전적으로 메모리에 보관하고 디스크는 영구 저장 용도로만 사용한다
  2. Redis는 많은 키-값 데이터 저장소와 비교했을 때 상대적으로 풍부한 데이터 유형 세트를 가지고 있다.
  3. Redis는 여러 개의 슬레이브(replica) 노드를 구성할 수 있으며, 이 슬레이브들은 마스터의 데이터를 복제하여 읽기 부하를 분산시키는 데 사용된다.
- Redis의 장점은 다음과 같다.
  1. Redis는 매우 빠르며 초당 약 110,000개의 SET, 초당 약 81,000 개의 GET을 수행할 수 있다.
  2. Redis는 목록, 집합, 정렬된 집합, 해시 등 개발자가 이미 알고 있는 대부분의 데이터 유형을 기본적으로 지원한다. 이를 통해 어떤 데이터 유형이 어떤 문제를 더 잘 처리할 수 있는지 알 수 있으므로 다양한 문제를 쉽게 해결할 수 있다.
  3. 모든 Redis 작업은 원자적이다. 두 클라이언트가 동시에 접근하는 경우 Redis 서버는 업데이트 된 값을 수신하게 된다.
  4. Redis는 다중 유틸리티 도구이며 캐싱, 메시징 대기열, 웹 애플리케이션 세션, 웹 페이지 조회수 등과 같이 애플리케이션에서 단기적으로 사용되는 데이터 등 다양한 사용 사례에 사용할 수 있다.

# CONFIG
- Redis에는 루트 디렉터리에 설정 파일(redis.conf)가 있다. Redis CONFIG 명령을 사용하여 모든 Redis 설정을 가져오고 설정할 수 있다.

  ```powershell
  redis 127.0.0.1:6379> CONFIG GET CONFIG_SETTING_NAME
  ```

  ```powershell
  redis 127.0.0.1:6379> CONFIG GET loglevel
  1) "loglevel"
  2) "notice"
  ```

- 모든 구성 설정의 경우 CONFIG_SETTING_NAME 대신 \* 을 사용한다.
- 구성 업데이트의 경우 redis.conf 파일을 직접 편집하거나 CONFIG set 명령을 통해 구성을 업데이트 할 수 있다.
- CONFIG SET으로 변경한 설정은 Redis가 재시작되면 사라진다. 영구 반영을 원할 경우 redis.conf 파일도 직접 수정해야 한다.

  ```powershell
  redis 127.0.0.1:6379> CONFIG SET CONFIG_SETTING_NAME NEW_CONFIG_VALUE
  ```

  ```powershell
  redis 127.0.0.1:6379> CONFIG SET loglevel "notice"
  OK
  redis 127.0.0.1:6379> CONFIG GET loglevel
  1) "loglevel"
  2) "notice"
  ```

# 데이터 유형

### 문자열
- Redis 문자열은 바이트 시퀀스로, 바이너리 세이프하므로 null 바이트나 특수문자가 포함된 데이터도 저장할 수 있다. 텍스트 뿐 아니라 이미지, 인코딩된 파일 등도 저장 가능하다.
  - 바이너리 세이프 : 특수 종료 문자(\0)로 결정되지 않는 알려진 길이를 가지는 구조

  ```powershell
  redis 127.0.0.1:6379> SET name "valueOfRedis"
  OK
  redis 127.0.0.1:6379> GET name
  "valueOfRedis"
  ```

  - 여기서 name은 KEY, "valueOfRedis" 는 VALUE가 된다.

### 해시
- Redis 해시는 키-값 쌍의 집합이다.
- Redis 해시는 문자열 필드와 문자열 값 사이의 맵이다.
- Redis 해시는 Java나 Python의 HashMap처럼, 필드-값 구조를 가지는 Key-Value 컬렉션으로, 간단한 사용자 정보 등을 저장할 때 유용하다.

  ```powershell
  redis 127.0.0.1:6379> HSET user:1 username knsol1992 password
  passwordOfRedis points 200
  OK
  redis 127.0.0.1:6379> HGETALL user:1
  1) "username"
  2) "knsol1992"
  3) "password"
  4) "passwordOfRedis"
  5) "points"
  6) "200"
  ```

  - 위 예에서 해시 데이터 유형은 사용자의 기본 정보가 포함된 사용자 객체를 저장하는 데 사용된다. HSET, HGETALL은 Redis 명령어이며, user-1 은 KEY가 된다.

### 리스트
- Redis 리스트는 삽입 순서대로 정렬된 문자열 리스트이다.
- Redis 리스트의 head나 tail 에 삽입 할 수 있다.

  ```powershell
  redis 127.0.0.1:6379> lpush testList redis
  (integer) 1
  redis 127.0.0.1:6379> lpush testList mongodb
  (integer) 2
  redis 127.0.0.1:6379> lpush testList rabitmq
  (integer) 3
  redis 127.0.0.1:6379> lrange testList 0 10

  1) "rabitmq"
  2) "mongodb"
  3) "redis"
  ```

### Set
- Redis 집합은 순서가 없는 문자열 컬렉션이다.
- O(1) 시간 복잡도로 멤버를 추가, 제거 및 존재 여부를 테스트 할 수 있다.

  ```powershell
  redis 127.0.0.1:6379> sadd testSet redis
  (integer) 1
  redis 127.0.0.1:6379> sadd testSet mongodb
  (integer) 1
  redis 127.0.0.1:6379> sadd testSet rabitmq
  (integer) 1
  redis 127.0.0.1:6379> sadd testSet rabitmq
  (integer) 0
  redis 127.0.0.1:6379> smembers testSet

  1) "rabitmq"
  2) "mongodb"
  3) "redis"
  ```

### Sorted Set
- Redis 정렬된 집합은 Redis 집합과 유사하며, 반복되지 않는 문자열 컬렉션이다.
- 차이점은 정렬된 집합의 각 멤버가 점수와 연결되어 있으며, 이 점수는 정렬된 집합을 가장 작은 점수부터 가장 큰 점수까지 순서대로 정렬하는 데 사용된다는 점이다.
- 멤버는 고유하지만 점수는 반복될 수 있다.

  ```powershell
  redis 127.0.0.1:6379> zadd testSortedSet 0 redis
  (integer) 1
  redis 127.0.0.1:6379> zadd testSortedSet 0 mongodb
  (integer) 1
  redis 127.0.0.1:6379> zadd testSortedSet 0 rabitmq
  (integer) 1
  redis 127.0.0.1:6379> zadd testSortedSet 0 rabitmq
  (integer) 0
  redis 127.0.0.1:6379> ZRANGEBYSCORE testSortedSet 0 1000

  1) "redis"
  2) "mongodb"
  3) "rabitmq"
  ```
