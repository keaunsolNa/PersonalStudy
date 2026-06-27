Notion 원본: https://app.notion.com/p/38c5a06fd6d381a1b68de69283a6e971

# gRPC HTTP/2 스트리밍과 Protobuf 인코딩 및 Flow Control

> 2026-06-27 신규 주제 · 확장 대상: CS·네트워크, REST API

## 학습 목표

- gRPC가 HTTP/2 위에서 4가지 RPC 패턴을 멀티플렉싱하는 방식을 설명한다.
- Protobuf의 varint·tag 인코딩과 호환 규칙을 안다.
- HTTP/2 스트림·커넥션 WINDOW_UPDATE 흐름 제어를 이해한다.
- 데드라인·취소 전파와 keepalive로 운영 안정성을 확보한다.

## 1. gRPC 위에 HTTP/2가 있는 이유

gRPC는 하나의 TCP 연결에서 여러 RPC를 독립 스트림으로 동시 처리한다. 메서드는 :path(/package.Service/Method), 메시지는 길이 접두(5바이트) 뒤 protobuf 바이트로 DATA 프레임에 실린다. 종료는 grpc-status 트레일러로 전달된다.

## 2. Protobuf 인코딩 — varint와 tag

Protobuf는 필드 이름을 보내지 않고 (field_number << 3) | wire_type 을 varint로 인코딩한 tag를 보낸다.

```proto
syntax = "proto3";
message User {
  int32 id = 1;    // tag 0x08, wire type 0(varint)
  string name = 2; // tag 0x12, wire type 2(length-delimited)
}
```

id=300은 08 AC 02. 필드 번호 1~15는 tag가 1바이트다. 음수가 흔하면 zigzag의 sint32/sint64를 택한다.

## 3. 스키마 진화 호환

필드 번호는 절대 재사용 금지—삭제 시 reserved로 막는다. 타입 변경은 wire type이 같을 때만 안전. presence는 optional.

```proto
message User { reserved 3; int32 id = 1; optional string email = 4; }
```

## 4. HTTP/2 흐름 제어

스트림·커넥션 두 수준의 흐름 제어. 수신자가 WINDOW_UPDATE로 한도를 알린다. 초기 윈도우 65,535바이트는 BDP가 큰 환경에서 처리량을 제한하므로 gRPC는 동적 확대한다. 이 흐름 제어가 스트리밍 백프레셔를 제공한다.

```java
obs.setOnReadyHandler(() -> { while (obs.isReady() && hasNext()) obs.onNext(nextChunk()); });
```

## 5. 데드라인·keepalive

```java
UserResponse r = stub.withDeadlineAfter(2, TimeUnit.SECONDS).getUser(req);
```

마감 초과 시 양쪽 모두 DEADLINE_EXCEEDED로 정리된다. 공격적 keepalive는 too_many_pings GOAWAY를 유발하므로 permit-keepalive-time과 맞춰야 한다.

## 6. REST/JSON 대비

| 항목 | gRPC | REST(JSON) |
| --- | --- | --- |
| 페이로드 | 작음(바이너리) | 큼(텍스트) |
| 멀티플렉싱 | 단일 연결 다중 | 연결당 1요청 |
| 스트리밍 | 네이티브 양방향 | 제한적 |
| 브라우저 직접 | 불가(grpc-web) | 자유 |

## 7. 운영 체크리스트

ManagedChannel 재사용, 모든 RPC에 데드라인, 멱등 메서드에만 재시도, L7 로드밸런싱.

## 8. 상태 코드·재시도

UNAVAILABLE(14)은 재시도 안전, INVALID_ARGUMENT(3)·NOT_FOUND(5)은 즉시 실패. 비멱등 메서드 재시도는 중복 부작용 위험.

```json
{"methodConfig": [{"name": [{"service": "user.UserService", "method": "GetUser"}], "retryPolicy": {"maxAttempts": 4, "initialBackoff": "0.2s", "backoffMultiplier": 2, "retryableStatusCodes": ["UNAVAILABLE"]}}]}
```

## 9. 인터셉터·메타데이터

인증·로깅·추적은 인터셉터로 처리. W3C traceparent를 메타데이터로 전파해 호출 그래프를 이어간다.

## 10. 페이로드 한도·압축

기본 수신 한도 4MB. 큰 응답은 서버 스트리밍으로 청크 분할. 작은 메시지는 압축 역효과. Protobuf는 JSON 대비 직렬화가 빠르고 페이로드가 작다.

## 참고

- gRPC 공식 문서 — Core concepts, Deadlines, Keepalive
- Protocol Buffers Encoding — varint, wire types, field presence
- RFC 9113(HTTP/2) — Flow Control, WINDOW_UPDATE
- RFC 7541(HPACK)
