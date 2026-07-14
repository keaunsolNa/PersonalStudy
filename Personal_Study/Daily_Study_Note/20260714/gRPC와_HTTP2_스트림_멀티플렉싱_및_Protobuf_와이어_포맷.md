Notion 원본: https://app.notion.com/p/39d5a06fd6d381a3a621f6b5368f8e50

# gRPC와 HTTP/2 스트림 멀티플렉싱 및 Protobuf 와이어 포맷

> 2026-07-14 신규 주제 · 확장 대상: REST_API

## 학습 목표

- HTTP/2의 프레임·스트림 모델과 멀티플렉싱이 HOL 블로킹을 어떻게 줄이는지 이해한다
- gRPC의 네 가지 RPC 방식이 HTTP/2 스트림에 어떻게 매핑되는지 확인한다
- Protobuf 와이어 포맷의 태그·와이어타입·varint 인코딩을 바이트 단위로 해석한다
- gRPC와 REST/JSON의 성능·호환성 트레이드오프로 선택 기준을 세운다

## 1. HTTP/2 프레임과 스트림

HTTP/1.1은 한 TCP 커넥션에서 요청·응답을 순차 처리해 앱계층 HOL 블로킹이 생긴다. HTTP/2는 커넥션을 프레임으로 쪼개고 각 프레임에 스트림 ID를 붙여 여러 요청·응답을 인터리빙한다. HEADERS 프레임이 헤더를, DATA 프레임이 본문을 나른다. 한 요청이 느려도 다른 요청이 기다리지 않는다. 헤더는 HPACK으로 압축되어 반복 헤더가 인덱싱된다.

다만 HTTP/2 멀티플렉싱은 앱계층 HOL만 없앱다. TCP는 순서 보장 바이트 스트림이라 한 패킷이 유실되면 받 모든 스트림이 재전송을 기다린다. 이 잔여 문제를 QUIC(HTTP/3)이 UDP 위에서 스트림별 독립 전송으로 없앨다.

## 2. gRPC의 네 가지 RPC와 스트림 매핑

gRPC는 HTTP/2 스트림 하나를 하나의 RPC 호출에 대응시킨다.

```protobuf
service OrderService {
  rpc Get(OrderId) returns (Order);                    // Unary
  rpc List(Query) returns (stream Order);              // Server streaming
  rpc Upload(stream Item) returns (Summary);           // Client streaming
  rpc Chat(stream Msg) returns (stream Msg);           // Bidirectional
}
```

Unary는 요청·응답 한 번씩, 서버 스트리밍은 서버가 여러 DATA 프레임을 이어 보낸다. 클라이언트 스트리밍은 반대, 양방향은 두 방향이 독립적으로 진행된다. gRPC 상태 코드는 HTTP 상태가 아니라 트레일러 헤더의 `grpc-status`로 전달되어 스트리밍 중간 실패도 정확한 종료 코드를 준다.

## 3. Protobuf 와이어 포맷: 태그와 와이어타입

Protobuf 메시지는 필드마다 `(field_number << 3) | wire_type` 태그로 시작한다. 하위 3비트가 와이어타입(0=varint, 1=64비트 고정, 2=length-delimited, 5=32비트 고정)이다. 필드 이름은 와이어에 없다.

```protobuf
message Person {
  int32 id = 1;       // field 1, wire type 0
  string name = 2;    // field 2, wire type 2
}
```

`{id: 150, name: "Al"}`을 인코딩하면 `08 96 01 12 02 41 6C`이다. `08`은 필드1·varint, `96 01`이 varint 150, `12`는 필드2·length-delimited, `02`가 길이, `41 6C`이 "Al"이다. 필드 이름이 실리지 않는 것이 크기 절감의 핵심이다.

## 4. varint와 ZigZag 인코딩

varint는 정수를 7비트 그룹으로 쪼개 리틀엔디안으로 심고 각 바이트 MSB를 continuation 플래그로 쓴다. 작은 수는 1바이트, 큰 수만 여러 바이트를 쓴다. 음수는 2의 보수로 10바이트를 잡아먹으므로 `sint32`/`sint64`는 ZigZag 인코딩으로 부호를 최하위 비트로 옮겨 -1→1, 1→2, -2→3처럼 매핑한다.

```
zigzag(n) = (n << 1) ^ (n >> 31)   // 32비트 기준
```

음수를 자주 쓰는 필드에 `int32` 대신 `sint32`를 쓰면 인코딩 크기가 크게 준다. 이 선택은 스키마 설계 시점의 결정이다.

## 5. 스키마 진화와 호환성

Protobuf는 필드 번호로 식별해 스키마를 안전하게 진화시킨다. 새 필드를 새 번호로 추가하면 구버전 파서는 모르는 필드를 무시하고 신버전은 없는 필드를 기본값으로 읽는다.

```protobuf
message Order {
  reserved 3, 5;
  reserved "legacy_status";
  int64 id = 1;
  string customer = 2;
  Money total = 4;
}
```

`required`는 proto3에서 제거됐다. required 필드는 스키마에서 뗄 수 없어 진화를 막기 때문이며, 이는 "호환성 > 검증 강제"라는 철학을 드러낸다.

## 6. gRPC vs REST/JSON 성능 감각

Protobuf 이진 인코딩은 JSON보다 보통 30~50% 작고 파싱이 몇 배 빠르다. HTTP/2 멀티플렉싱과 HPACK까지 더하면 고빈도 통신에서 지연·CPU가 준다.

| 항목 | gRPC/Protobuf | REST/JSON |
|---|---|---|
| 페이로드 크기 | 작음 | 큰 |
| 브라우저 직접 호출 | 제한(gRPC-Web 필요) | 자유 |
| 사람이 읽기 | 어려움 | 쉽음 |
| 스트리밍 | 1급 지원 | SSE/WebSocket 별도 |
| 캐싱(프록시) | 약함 | HTTP 캐시 성숙 |

## 7. 제약과 함정

gRPC의 최대 약점은 브라우저다. 브라우저는 HTTP/2 프레임을 직접 제어할 수 없어 순수 gRPC를 호출하지 못하고 프록시(Envoy)가 gRPC-Web을 변환한다. 응답 캐싱이 어렵고 디버깅이 힘들며, Protobuf 필드 번호 규칙을 어기면 조용한 데이터 오손이 생긴다.

## 7.5. 데드라인, 메타데이터, 인터셉터

gRPC는 타임아웃을 클라이언트가 설정하는 데드라인으로 전파한다. 데드라인이 `grpc-timeout` 헤더로 서버에 전달되고 하위 호출에 남은 시간으로 전파되어 체인 전체가 하나의 예산을 공유한다.

```java
blockingStub.withDeadlineAfter(200, TimeUnit.MILLISECONDS).getOrder(request);
```

메타데이터(HTTP/2 헤더)는 인증 토큰·추적 ID를 실고, 인터셉터가 이를 가로채 인증·로깅·재시도·서킷 브레이커를 횟단 관심사로 구현한다. 재시도는 `retryPolicy`로 선언적으로 지정한다. 이 조합이 gRPC를 서비스 메시 환경에서 강하게 만든다.

## 8. 선택 기준

내부 마이크로서비스 간 고빈도·저지연 통신이고 다양한 언어 클라이언트를 코드 생성으로 묶으면 gRPC가 유리하다. 공개 API로 브라우저·서드파티가 호출하고 캐시 친화성과 가독성이 중요하면 REST/JSON이 낫다. 실무에서는 엣지 API는 REST, 내부 서비스 메시는 gRPC로 통신하는 이중 구조가 흔하다.

## 참고

- gRPC Documentation — Core Concepts: https://grpc.io/docs/what-is-grpc/core-concepts/
- Protocol Buffers — Encoding: https://protobuf.dev/programming-guides/encoding/
- RFC 9113 — HTTP/2: https://www.rfc-editor.org/rfc/rfc9113.html
- Protocol Buffers — Proto3 Language Guide: https://protobuf.dev/programming-guides/proto3/
