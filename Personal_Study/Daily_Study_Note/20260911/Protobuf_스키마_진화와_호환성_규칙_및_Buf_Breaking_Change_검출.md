Notion 원본: https://app.notion.com/p/3d85a06fd6d3815ba5a0c8cc629386d5?pvs=204

# Protobuf 스키마 진화와 호환성 규칙 및 Buf Breaking Change 검출

> 2026-09-11 신규 주제 · 확장 대상: gRPC / Protobuf

## 학습 목표

- tag 인코딩과 unknown field 보존에서 호환성 규칙을 유도한다.
- 스키마 변경을 와이어·JSON·소스 세 축으로 나눠 판정한다.
- `buf breaking --against` 를 CI 게이트로 구성한다.
- protobuf-java 소비자의 생성 코드·런타임 장애를 진단한다.

## 1. 와이어 포맷이 호환성을 결정하는 방식

호환성 규칙은 관례가 아니라 인코딩에서 기계적으로 따라 나온다. 직렬화된 바이트에 필드 이름은 없고, 각 필드는 tag varint 와 값으로만 이뤄진다.

```
tag = (field_number << 3) | wire_type
```

wire_type 은 0(VARINT: int·uint·sint·bool·enum), 1(I64: fixed64·double), 2(LEN: string·bytes·message·packed), 5(I32: fixed32·float) 네 가지다. 디코더가 아는 것은 번호와 읽는 방식뿐이라 이름을 바꿔도 와이어는 멀쩡하고, 번호를 바꾸면 다른 필드가 된다. wire_type 이 같으면 타입 교체도 성립하지만 `int32` → `sint32` 는 예외로, sint 계열만 ZigZag(`(n << 1) ^ (n >> 31)`) 를 써서 파서가 오류 없이 틀린 숫자를 돌려준다.

두 번째 축은 **unknown field 보존**이다. 구 버전 코드가 모르는 번호를 원본 바이트째 보관했다 재직렬화 때 그대로 내보내는 동작으로, proto3 는 3.5 에서 되돌렸다. 프록시나 재시도 큐가 메시지를 읽고 일부만 고쳐 다시 쓰기 때문에, 보존이 없으면 구 버전 노드를 지날 때마다 신규 필드가 증발한다.

```java
UserProfile parsed = UserProfile.parseFrom(incomingBytes);
byte[] relayed = parsed.toBuilder()
        .setUpdatedAtMillis(System.currentTimeMillis())
        .build().toByteArray();   // unknown field 포함해 재직렬화
```

trade-off 는 메모리다. 보존은 쓰지도 않는 페이로드를 힙에 붙들어 두고, `discardUnknownFields()` 는 그 대가로 위의 소실을 떠안는다.

## 2. 안전한 변경과 깨지는 변경

호환성은 셋으로 갈린다. **와이어**는 바이트가 오갈 수 있는가, **JSON** 은 protojson 표현이 유지되는가, **소스**는 생성 코드에 의존하는 모듈이 수정 없이 빌드되는가다. 필드 rename 은 와이어 안전이면서 나머지 둘을 파괴하므로 하나로 뭉뚱그릴 수 없다.

가장 안전한 진화는 새 번호로 필드를 추가하는 것이고, 삭제할 때는 번호와 이름을 `reserved` 로 봉인한다. 이를 빠뜨리고 같은 wire_type 으로 번호를 재사용하면 파싱 에러조차 없이 과거의 할인 금액이 배송비로 읽히며, Kafka 처럼 메시지가 오래 남으면 사고 범위가 보존 기간 전체로 번진다.

```protobuf
message Order {
  reserved 4, 7, 12 to 15;       // 번호 재사용 금지
  reserved "legacy_coupon_code"; // JSON·텍스트 이름 재사용 금지

  int64 total_amount = 2;        // int32 에서 넓힘 — 와이어 안전
  optional string memo = 3;      // explicit presence — hasMemo() 생성
  repeated LineItem items = 5;
}
```

`optional` 은 proto3 3.15 에서 synthetic oneof 로 구현돼 `hasXxx()` 를 만든다. 와이어는 그대로고 차이는 기본값의 직렬화 포함 여부뿐이라, 붙였다 떼는 것은 와이어 안전·소스 파괴다. singular → repeated 는 구 생산자의 단일 값이 원소 1개로 읽혀 무손실이지만, 반대 방향은 마지막 값만 남는다.

enum 은 proto2 의 **closed** 와 proto3 의 **open** 이 갈린다. closed 는 미선언 숫자를 unknown field 로 밀어내 값이 사라지고, open 은 숫자를 보존해 `UNRECOGNIZED` 로 노출한다. `oneof` 는 단일 필드를 새 oneof 로 옮길 때만 안전하다.

| 변경 유형 | 와이어 | JSON | Java 소스 |
| --- | --- | --- | --- |
| 새 번호로 필드 추가 | O | O | O |
| 필드 삭제 + `reserved` | O | O | X |
| 필드 삭제, `reserved` 누락 | 위험 | 위험 | X |
| 필드 번호 변경 | X | O | O |
| 필드 이름 변경 | O | X | X |
| `json_name` 만 변경 | O | X | O |
| `optional` 추가·제거 | O | 조건부 | X |
| singular → repeated | 조건부 | X | X |
| repeated → singular | X | X | X |
| packed ↔ unpacked | O | O | O |
| int32 ↔ int64 ↔ uint32 ↔ bool | O | 조건부 | 조건부 |
| sint32 ↔ sint64 | O | O | 조건부 |
| int32 ↔ sint32 | X | X | X |
| fixed32 ↔ sfixed32 (같은 폭) | O | 조건부 | 조건부 |
| fixed32 ↔ fixed64 | X | X | X |
| string ↔ bytes | 조건부 | X | X |
| enum 값 추가 (proto3 open) | O | O | O |
| enum 값 추가 (proto2 closed 소비자) | X | X | O |
| enum 값 삭제·번호 변경 | X | X | X |
| 단일 필드를 새 oneof 로 이동 | O | O | 조건부 |
| 기존 oneof 에 기존 필드 편입 | X | X | X |
| `java_package`·`java_outer_classname` 변경 | O | O | X |

## 3. JSON 매핑과 protojson 의 별도 규칙

canonical JSON 매핑(Java 의 `JsonFormat`)은 **필드 이름 기반**이라, 바이너리에서 무해했던 rename 이 즉시 파괴적이 된다. gRPC-Gateway 나 transcoding 을 쓴다면 와이어 호환만 봐서는 장애를 막지 못한다. snake_case 는 lowerCamelCase 로 바뀌며, 이름을 리팩터링하되 JSON key 를 고정하려면 `json_name` 을 쓴다.

```protobuf
message Payment {
  string merchant_reference = 1 [json_name = "merchantRef"];
  int64  amount_minor = 2;   // JSON 에서 "12345" 문자열로 직렬화
  bytes  raw_receipt = 3;    // base64, NaN·Infinity 도 문자열
}
```

64비트 정수는 JavaScript `Number` 의 안전 범위(2^53)를 넘으므로 canonical JSON 이 문자열로 내보낸다. 프런트엔드가 숫자를 가정했다면 `int32` → `int64` 라는 와이어 안전 변경이 JSON 계약을 깬다. 기본값 생략 규칙도 계약이라 `emit_defaults` 를 켜면 `undefined` 분기를 두던 소비자가 달라진다.

```java
// 기본값도 항상 출력 / 모르는 필드는 무시 (끄면 신규 필드 수신 시 예외)
JsonFormat.Printer p = JsonFormat.printer().includingDefaultValueFields();
JsonFormat.Parser q = JsonFormat.parser().ignoringUnknownFields();
```

엄격 파싱은 오타를 조기에 잡지만 생산자가 필드를 추가하는 순간 구 소비자가 전부 실패하므로, 내부 서비스는 관대 파싱에 CI 게이트를 붙이는 조합이 맞다.

## 4. 서비스와 RPC 수준의 진화

gRPC 의 라우팅 키는 HTTP/2 `:path` 에 실리는 full method name `/패키지.서비스/메서드` 이고, `package` 선언과 `service`·`rpc` 이름 셋으로만 결정된다.

```protobuf
package commerce.order.v1;

service OrderService {
  rpc GetOrder(GetOrderRequest) returns (GetOrderResponse);
  // 실제 경로: /commerce.order.v1.OrderService/GetOrder
  rpc StreamOrderEvents(StreamRequest) returns (stream OrderEvent);
}
```

따라서 패키지나 서비스를 rename 하면 경로가 통째로 달라진다. 서버는 새 경로만 등록하고 구 클라이언트는 옛 경로를 부르므로, 컴파일·연결·TLS 가 모두 정상인데 클라이언트만 `UNIMPLEMENTED` 를 받는다.

메서드 추가는 안전한 반면 삭제, 타입 교체, streaming 종류 변경(unary ↔ server-streaming ↔ bidi)은 파괴적이며 특히 streaming 은 호출 규약 자체를 바꾼다. 그래서 요청·응답은 전용 wrapper 로 두어야 하고, `rpc GetOrder(google.protobuf.StringValue)` 로 선언했다면 필터 하나를 추가하는 데도 시그니처가 깨진다. Spring gRPC 는 구 메서드를 `@Deprecated` 로 남겨 신 구현에 위임시킨다.

## 5. Buf 도구 체인

`protoc` 는 컴파일러일 뿐 정책을 강제하지 않는다. Buf 는 모듈(`buf.yaml`), 린터, 호환성 검사기, 생성 오케스트레이터(`buf.gen.yaml`), 레지스트리(BSR) 를 제공한다. 핵심은 `buf breaking` 이 텍스트 diff 가 아니라 **FileDescriptorSet 비교**여서 주석이나 선언 순서 변경은 잡히지 않는다는 점이다.

```yaml
# buf.yaml
version: v2
modules:
  - path: proto
    name: buf.build/acme/commerce
lint:
  use: [STANDARD]
breaking:
  use: [FILE]
  ignore_unstable_packages: true   # v1beta1·v1alpha 제외

# buf.gen.yaml
managed:
  enabled: true                    # java_multiple_files 등을 중앙에서 고정
plugins:
  - remote: buf.build/grpc/java
    out: build/generated/source/proto/main/grpcjava
```

기준점은 `--against` 로 지정하며 git 참조, 로컬 경로, BSR 모듈을 모두 받는다.

```bash
buf breaking --against '.git#branch=main'               # 브랜치 기준
buf breaking --against '.git#tag=v2.14.0,subdir=proto'  # 배포된 계약 기준
buf breaking --against 'buf.build/acme/commerce'        # BSR 기준
```

카테고리는 엄격도 순으로 `FILE` > `PACKAGE` > `WIRE_JSON` > `WIRE` 다. `WIRE` 는 바이너리 파싱만 지켜 rename 을 허용하고, `WIRE_JSON` 은 JSON 이름 안정성을 더해 막는다. `FILE` 은 타입이 선언된 파일까지 고정해 다른 `.proto` 로 옮기는 것조차 파괴로 본다. stub 을 라이브러리로 배포하면 `FILE`, 사내에서 모두가 재생성하면 `WIRE_JSON` 이 균형점이다.

## 6. CI 게이트 설계

로컬 실행만으로는 계약이 지켜지지 않으므로 PR 병합 조건으로 걸어야 한다. `fetch-depth: 0` 을 빠뜨리면 shallow clone 탓에 base 객체가 없어 검사가 조용히 무력화된다.

```yaml
# .github/workflows/proto.yml
on:
  pull_request:
    paths: ['proto/**', 'buf.yaml']
jobs:
  buf:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }   # base 브랜치 비교에 필수
      - uses: bufbuild/buf-action@v1
        with: { setup_only: true }
      - run: buf lint && buf format --diff --exit-code
      - run: buf breaking --against ".git#branch=${{ github.base_ref }}"
```

monorepo 라면 `modules` 를 팀별로 나눠, 한 팀의 임시 완화가 다른 팀 스키마까지 느슨하게 만들지 않도록 격리한다.

```yaml
modules:
  - path: proto/commerce
    name: buf.build/acme/commerce
  - path: proto/identity
    name: buf.build/acme/identity
    breaking:
      use: [WIRE_JSON]                  # 이 모듈만 완화
breaking:
  ignore_only:
    FIELD_SAME_NAME:
      - proto/commerce/order/v1beta1/   # 2026-12-31 만료, @order-team
```

탈출구인 `ignore` 와 `ignore_only` 가 실질적 위험의 원천이다. `ignore` 는 경로 전체를 무검증으로 만들어 이후 모든 변경을 통과시키므로, 규칙 ID 단위로 면제하는 `ignore_only` 만 쓰고 만료 날짜와 담당자를 주석으로 강제해야 한다.

## 7. Java 소비자 관점의 실전 함정

가장 자주 사고를 내는 건 필드가 아니라 **파일 옵션**이다. `java_multiple_files` 를 true 로 바꾸면 `OrderProto.Order` 가 `Order` 로 바뀌고, `java_outer_classname` 변경은 outer 클래스를 갈아치운다. 와이어는 멀쩡해 `WIRE` 는 통과시키지만 stub 을 쓰는 모든 모듈의 import 가 깨지며, 이것이 `FILE` 을 골라야 하는 근거다.

둘째는 field presence 로, proto3 일반 스칼라에는 `hasXxx()` 가 없고 `optional`·message·oneof 멤버일 때만 생긴다. 셋째는 **UNRECOGNIZED** 다. 이 상수에 `getNumber()` 를 부르면 `IllegalArgumentException` 이 터지므로, 신 서버가 새 값을 보내는 순간 구 클라이언트가 죽는다.

```java
if (order.hasMemo()) { applyMemo(order.getMemo()); }   // optional 의 presence
switch (order.getStatus()) {
    case ORDER_STATUS_PAID -> handlePaid(order);
    case UNRECOGNIZED, ORDER_STATUS_UNSPECIFIED ->
            log.warn("미지 상태: {}", order.getStatusValue());  // getNumber() 금지
    default -> log.warn("미처리 상태: {}", order.getStatus());
}
```

넷째는 런타임과 생성 코드의 버전 불일치다. 생성 코드가 더 새로우면 클래스 로딩 시점에 `NoSuchMethodError` 가 터지고, 3.25 이후에는 `RuntimeVersion.validateProtobufGencodeVersion` 이 명시적 메시지를 던진다. gRPC-Java 가 끌어오는 버전과 어긋나는 경우가 대부분이라 BOM 으로 고정해야 한다.

```bash
./gradlew :order-service:dependencyInsight \
  --dependency protobuf-java --configuration runtimeClasspath
```

## 8. 실무 운영 전략

배포 순서는 변경 방향이 정한다. **필드 추가는 소비자 먼저**여야 읽을 코드가 깔린 뒤 생산자가 채워 보낼 수 있고, **필드 제거는 생산자 먼저** 쓰기를 멈춘 뒤 지운다. deprecate 사이클은 경고, 이중 쓰기, 메트릭 확인, 삭제와 `reserved` 봉인의 네 단계다.

```protobuf
message Order {
  reserved 9;
  reserved "shipping_fee_legacy";
  int64 shipping_fee_minor = 2 [deprecated = true];  // 1단계: 경고
  Money shipping_fee = 10;                           // 2단계: 이중 쓰기
}
```

가장 까다로운 건 **저장된 메시지**다. Kafka 토픽의 메시지는 수개월 이상 살아 있으므로 직전 릴리스가 아니라 보존 기간 안에서 가장 오래된 스키마가 기준점이어야 한다. Schema Registry 라면 `BACKWARD`·`FULL` 에 더해 전 이력을 검사하는 `*_TRANSITIVE` 를 걸고, Buf 만 쓴다면 `--against` 를 보존 시작점 태그로 잡아 근사한다.

```bash
OLDEST=$(git tag --merged main --sort=-creatordate | while read t; do
  [ "$(git log -1 --format=%ct "$t")" -lt "$(date -d '90 days ago' +%s)" ] \
    && echo "$t" && break
done)
buf breaking --against ".git#tag=${OLDEST},subdir=proto"
```

버전 디렉터리는 마지막 안전망이다. `commerce/order/v1/` 처럼 패키지에 버전을 넣고 정말 깨야 할 때 `v2` 를 만들면 소비자가 자기 속도로 이전할 수 있지만 서버가 두 벌의 구현과 변환 계층을 떠안으므로, v2 를 만드는 조건에 v1 의 종료 날짜를 함께 박아야 한다. `v1beta1` 은 완충재지만 깨질 수 있음을 감수한다는 합의가 없으면 검사만 꺼진 채 v1 과 같은 기대를 받는다.

## 참고

- Encoding (tag, varint, ZigZag) — https://protobuf.dev/programming-guides/encoding/
- Proto Best Practices — https://protobuf.dev/best-practices/dos-donts/
- ProtoJSON Format — https://protobuf.dev/programming-guides/json/
- Field Presence — https://protobuf.dev/programming-guides/field_presence/
- Buf: Breaking change detection — https://buf.build/docs/breaking/overview/
- gRPC over HTTP/2 — https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
