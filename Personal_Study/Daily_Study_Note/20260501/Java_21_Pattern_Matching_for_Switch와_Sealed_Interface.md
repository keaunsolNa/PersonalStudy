Notion 원본: https://app.notion.com/p/3535a06fd6d3812fbc35c2dfa35cf17a

# Java 21 Pattern Matching for Switch와 Sealed Interface 도메인 모델링

> 2026-05-01 신규 주제 · 확장 대상: JAVA

## 학습 목표

- JEP 441(Pattern Matching for switch) 의 문법·평가 규칙·exhaustiveness 검사 동작 분석
- Sealed interface + record 조합으로 ADT(Algebraic Data Type) 도메인 모델링 패턴 정립
- 기존 visitor 패턴, 다형성, instanceof chain 대비 pattern matching 의 트레이드오프
- 실제 결제 도메인을 ADT 로 표현해 컴파일러로 누락 케이스를 강제하는 코드 작성

## 1. 무엇이 바뀌었는가 — preview 에서 표준까지의 경로

Java 의 switch 는 오랫동안 정수·enum·String 만 지원하는 기본 분기문이었다. JEP 441(Java 21, 2023-09 정식)로 패턴 매칭이 들어오면서 switch 가 표현식이자 타입 분해 도구가 되었다. 진화 순서를 알면 왜 현재 모양인지 이해된다.

| JEP | 버전 | 내용 |
|---|---|---|
| 361 | 14 | switch expression (yield, ->) 정식 |
| 394 | 16 | instanceof pattern matching 정식 |
| 395 | 16 | record 정식 |
| 409 | 17 | sealed class 정식 |
| 406/420/427/433 | 17~20 | switch pattern matching preview 반복 |
| 440/441 | 21 | record pattern + switch pattern matching 정식 |

Java 21 에서 두 기능이 함께 정식화된 것은 의도적이다. record 는 데이터 분해(deconstruction), sealed 는 닫힌 계층, switch pattern 은 분해+분기를 한 번에 표현하는 문법이다. 셋이 합쳐져야 비로소 Scala 의 case class + match, Rust 의 enum + match, Haskell 의 data + case 같은 ADT 표현력이 완성된다.

## 2. 결제 도메인 ADT 설계

```java
public sealed interface PaymentResult
        permits PaymentResult.Success, PaymentResult.Declined, PaymentResult.Pending, PaymentResult.Error {

    record Success(String transactionId, BigDecimal amount, Instant approvedAt) implements PaymentResult {}
    record Declined(String reason, String issuerCode) implements PaymentResult {}
    record Pending(String transactionId, Duration estimatedWait) implements PaymentResult {}
    record Error(Throwable cause, String operation) implements PaymentResult {}
}
```

`sealed` + `permits` 가 핵심이다. 이 인터페이스를 구현할 수 있는 타입은 컴파일러가 명시적으로 안다. 다른 패키지·다른 모듈에서 `implements PaymentResult` 를 시도하면 컴파일 에러가 난다. 그 결과 switch 가 모든 경우를 다뤘는지 컴파일러가 검증할 수 있다.

```java
public String describe(PaymentResult result) {
    return switch (result) {
        case PaymentResult.Success(var id, var amt, var when) ->
            "결제 성공 #%s, 금액 %s, 시각 %s".formatted(id, amt, when);
        case PaymentResult.Declined(var reason, var code) ->
            "거절(코드=%s): %s".formatted(code, reason);
        case PaymentResult.Pending(var id, var wait) ->
            "처리 중 #%s (예상 대기 %s)".formatted(id, wait);
        case PaymentResult.Error(var cause, var op) ->
            "[ERROR] %s 중 %s".formatted(op, cause.getMessage());
    };
}
```

`default` 케이스가 없는데 컴파일이 통과한다. sealed + record pattern 의 조합으로 컴파일러가 4개 모두 처리됨을 확인했기 때문이다. 미래에 `permits` 에 새 타입을 추가하면 이 switch 는 컴파일 에러가 된다 — 누락 케이스를 감지하는 메커니즘이 도메인 변경에 대해 자동으로 작동한다.

## 3. record pattern — 중첩 분해

record pattern 은 임의 깊이로 중첩된다. 원시 타입과 다른 record 가 섞여도 한 번에 분해 가능하다.

```java
public sealed interface Shape permits Circle, Rectangle, Triangle {}
public record Point(double x, double y) {}
public record Circle(Point center, double radius) implements Shape {}
public record Rectangle(Point topLeft, Point bottomRight) implements Shape {}
public record Triangle(Point a, Point b, Point c) implements Shape {}

public double area(Shape s) {
    return switch (s) {
        case Circle(Point(var cx, var cy), var r) -> Math.PI * r * r;
        case Rectangle(Point(var x1, var y1), Point(var x2, var y2)) ->
            Math.abs((x2 - x1) * (y2 - y1));
        case Triangle(Point(var ax, var ay), Point(var bx, var by), Point(var cx, var cy)) ->
            0.5 * Math.abs(ax * (by - cy) + bx * (cy - ay) + cx * (ay - by));
    };
}
```

분해 깊이에 제한이 없다. 이는 도메인 객체가 깊을수록 효과가 크다 — 중첩된 `Order > LineItem > Product` 구조도 한 케이스에서 모두 꺼낼 수 있다.

## 4. when guard — 같은 타입 안에서 다시 분기

타입은 같지만 값에 따라 분기를 갈라야 할 때 `when` 절을 쓴다.

```java
public String classifyTemperature(int celsius) {
    return switch (celsius) {
        case Integer i when i < -10  -> "혹한";
        case Integer i when i < 0    -> "영하";
        case Integer i when i < 15   -> "쌀쌀함";
        case Integer i when i < 25   -> "온화";
        case Integer i when i < 35   -> "더움";
        case Integer i              -> "폭염";
    };
}
```

`when` 은 boolean 표현식이다. 위에서부터 매칭을 시도하므로 순서가 의미를 가진다. exhaustiveness 검사는 guard 의 결과를 컴파일러가 평가하지 않으므로, 마지막 unguarded case 가 반드시 필요하다(혹은 default).

## 5. null 처리 — 명시적 case null

Java 21 의 switch pattern 은 null 을 명시적으로 다루도록 강제한다. case null 이 없으면 NPE 가 던져진다.

```java
public String length(String s) {
    return switch (s) {
        case null -> "<null>";
        case String str when str.isEmpty() -> "<empty>";
        case String str -> "%d 글자".formatted(str.length());
    };
}
```

`case null, default ->` 처럼 묶을 수도 있다. 기존 switch 가 null 을 던져 버려서 호출 측이 매번 가드해야 했던 문제를 표현 안에서 직접 푼다.

## 6. 기존 패턴 대비 비교

| 패턴 | 코드량 | 새 케이스 추가 시 | exhaustiveness 보장 |
|---|---|---|---|
| 다형성 (각 클래스 메서드) | 많음 | 모든 클래스 수정 | 컴파일 검사 가능 |
| Visitor 패턴 | 많음 (visitor + accept) | visitor 인터페이스 + 모든 구현체 | 컴파일 검사 가능 |
| instanceof chain (Java 8) | 적음 | 분기 추가 | ❌ 누락 검사 불가 |
| switch pattern (Java 21) | 매우 적음 | 분기 추가 | ✅ sealed 조합 시 자동 검사 |

다형성은 OOP 정통이지만 데이터 변환·표현 분리에서는 데이터 클래스에 표현 책임을 강제한다(예: `toJson()` 을 도메인 객체 안에 넣는 식). switch pattern 은 데이터와 동작을 분리하면서 안전성을 유지한다 — Expression Problem 을 부분 해결한다.

## 7. 바이트코드 — invokedynamic 기반 컴파일

switch pattern 은 컴파일 결과로 `invokedynamic` 호출이 들어간다. JVM 의 `SwitchBootstraps.typeSwitch` 가 부트스트랩 메서드로 호출되어 각 case 의 타입을 순서대로 검사하는 디스패처를 동적으로 만든다.

```
0: aload_1
1: invokedynamic #21,  0  // typeSwitch with bootstrap method
6: tableswitch  { 0: ..., 1: ..., 2: ..., 3: ... }
```

컴파일러는 처음에는 단순한 if-else chain 처럼 보이지만, JIT 가 hot 해지면 hash-based dispatch 로 최적화한다. 4~5 케이스까지는 linear, 그 이상은 hash table 이 깔린다. record deconstruction 은 각 component accessor 메서드 호출로 펼쳐진다.

## 8. 실전 — Result 모나드 흉내

Rust `Result<T, E>` 또는 함수형 Either 를 sealed 로 표현하면 Java 도 명시적 에러 처리 스타일이 가능하다.

```java
public sealed interface Result<T, E> permits Result.Ok, Result.Err {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}

    default <U> Result<U, E> map(Function<T, U> fn) {
        return switch (this) {
            case Ok<T, E>(var value) -> new Ok<>(fn.apply(value));
            case Err<T, E>(var error) -> new Err<>(error);
        };
    }

    default <U> Result<U, E> flatMap(Function<T, Result<U, E>> fn) {
        return switch (this) {
            case Ok<T, E>(var value) -> fn.apply(value);
            case Err<T, E>(var error) -> new Err<>(error);
        };
    }
}

Result<User, ApiError> result = fetchUser(id)
    .flatMap(user -> validatePermission(user))
    .map(user -> toDto(user));
```

`Optional` 의 풍부판이라 보면 된다. throw 없이 컴파일러가 에러 분기를 강제한다. Spring 의 `ResponseEntity` 도 이 패턴으로 wrap 하면 컨트롤러 레벨에서 try/catch 가 거의 사라진다.

## 9. 한계와 트레이드오프

- **Generic record pattern** 의 추론은 아직 부분적이다. `case Ok(var value)` 처럼 다이아몬드 추론이 모든 경우에 동작하지는 않는다(Java 21 시점 - JEP 488 에서 개선 진행 중).
- **performance**: hot path 에서 단순 enum switch 보다 약 1.5~2배 느리다. 마이크로벤치 기준으로 한 분기당 ~5ns. 비즈니스 로직에서는 무의미하지만 인터프리터 코어에서는 의식해야 한다.
- **gradual migration**: 거대한 도메인을 한꺼번에 sealed 로 묶으려면 모든 구현체를 같은 모듈/패키지에 두어야 한다. 분리된 모듈이라면 `permits` 명시 확인 필수.
- **library compatibility**: Lombok 의 `@Value`/`@Data` 와 record 는 함께 쓰지 않는다. record 가 더 적은 코드로 같은 효과를 낸다.

## 10. 마이그레이션 로드맵

기존 visitor 패턴 또는 instanceof chain 코드를 옮기는 권장 순서.

1. 데이터 클래스를 `record` 로 치환. equals/hashCode/toString 무료.
2. 공통 부모를 `sealed interface` 로 선언, `permits` 에 모든 구현체 명시.
3. 분기 로직을 switch expression 으로 모음. `default` 제거 시도 — 컴파일러가 누락을 알려준다.
4. 각 case 안에서 record pattern 으로 필드 분해.
5. 기존 instanceof chain 은 IDE refactoring(IntelliJ "Replace with switch") 로 대부분 자동 변환된다.

이 작업은 코드 라인 수를 30~50% 줄이는 동시에 리팩토링 시 누락된 케이스를 컴파일러가 잡아주는 안전망을 제공한다.

## 참고

- JEP 441: Pattern Matching for switch — https://openjdk.org/jeps/441
- JEP 440: Record Patterns — https://openjdk.org/jeps/440
- JEP 409: Sealed Classes — https://openjdk.org/jeps/409
- "Algebraic Data Types in Java" by Brian Goetz — https://inside.java/2024/02/06/data-oriented-programming/
- Java Language Specification 14.30 (Patterns) — https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.30
- "Java 21 New Features" Inside.java — https://inside.java/tag/java21
