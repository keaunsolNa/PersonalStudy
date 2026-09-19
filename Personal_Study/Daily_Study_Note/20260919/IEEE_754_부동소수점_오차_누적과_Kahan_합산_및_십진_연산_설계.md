Notion 원본: https://www.notion.so/3e05a06fd6d38174b83bd8237ed73d9d

# IEEE 754 부동소수점 오차 누적과 Kahan 합산 및 십진 연산 설계

> 2026-09-19 신규 주제 · 확장 대상: 자료구조&알고리즘, 최적화_기본, JAVA

## 학습 목표

- binary64의 비트 레이아웃과 machine epsilon으로 표현 오차의 상한을 계산한다
- 순진한 합산의 오차가 O(n·ε)로 누적되는 과정을 Kahan·Neumaier 보상 합산과 비교한다
- 금액 계산에서 `double`을 쓰면 안 되는 이유를 `BigDecimal` / 정수 minor unit과 대조해 결정한다
- DB 컬럼 타입, JSON 직렬화, 부동소수점 비교까지 경계마다의 함정을 점검한다

## 1. binary64의 구조와 표현 오차의 상한

IEEE 754 배정밀도(binary64)는 64비트를 부호 1 + 지수 11 + 가수 52로 나눈다. 정규화 수에서는 가수 앞에 암묵적 1이 붙으므로 유효 비트는 53개다.

```
값 = (-1)^s × 1.f × 2^(e - 1023),   f는 52비트 분수부
```

여기서 두 개의 상수가 나온다.

- **machine epsilon** ε = 2^-52 ≈ 2.220446049250313e-16. 1.0 다음 표현 가능한 수와 1.0의 차이다.
- **unit roundoff** u = ε/2 = 2^-53. 반올림 최근접(round-to-nearest) 모드에서 상대 오차의 상한이다.

즉 임의의 실수 x를 배정밀도로 표현하면 `fl(x) = x(1 + δ)`, `|δ| ≤ u`가 보장된다. 십진 기준으로 약 15.95자리, 실무적으로 "17자리를 출력하면 왕복(round-trip)이 보장되고, 신뢰할 수 있는 것은 15자리"로 기억하면 된다.

가장 유명한 사례가 0.1이다. 0.1은 2의 거듭제곱 합으로 유한하게 표현되지 않으므로, 저장되는 값은 정확히 다음이다.

```
0.1 → 0.1000000000000000055511151231257827021181583404541015625
0.2 → 0.200000000000000011102230246251565404236316680908203125
합   → 0.3000000000000000444089209850062616169452667236328125
0.3 → 0.299999999999999988897769753748434595763683319091796875
```

`0.1 + 0.2 === 0.3`이 거짓인 것은 버그가 아니라 이진 표현의 필연이다. 실제 비트를 확인해 보는 습관이 도움이 된다.

```java
System.out.println(new java.math.BigDecimal(0.1));          // 정확한 값 전개
System.out.println(Long.toHexString(Double.doubleToLongBits(0.1)));  // 3fb999999999999a
```

```python
from decimal import Decimal
print(Decimal(0.1))          # 0.1000000000000000055511151231257827021181583404541015625
print((0.1).hex())           # 0x1.999999999999ap-4
```

## 2. 순진한 합산의 오차 누적

`n`개의 수를 순차로 더할 때 오차 상한은 대략 `n·u·Σ|xᵢ|`로 증가한다. 큰 값이 먼저 쌓이면 작은 값이 통째로 흡수되는 "swamping"이 발생한다.

```java
double sum = 0.0;
for (int i = 0; i < 10_000_000; i++) {
    sum += 0.1;
}
System.out.println(sum);   // 999999.9998389754  (기대: 1000000.0)
```

1천만 번 더해 상대 오차가 약 1.6e-10. 각 단계 오차가 u ≈ 1.1e-16인데 n배로 누적되면 1e-9 수준이 나온다는 계산과 맞는다. 극단적 예는 더 직관적이다.

```java
double big = 1e16;
System.out.println(big + 1.0 - big);   // 0.0  — 1.0 이 완전히 사라진다
System.out.println(big + 1.0 == big);  // true
```

1e16 근처에서 인접한 두 배정밀도 수의 간격(ULP)이 2.0이므로, 1.0을 더해도 같은 수로 반올림된다. 누적 카운터나 타임스탬프를 `double`에 담으면 어느 순간부터 증가가 멈추는 현상이 이것이다.

순서를 바꾸면 결과가 달라진다는 점도 중요하다. 부동소수점 덧셈은 **결합법칙이 성립하지 않는다**.

```java
System.out.println((0.1 + 0.2) + 0.3);   // 0.6000000000000001
System.out.println(0.1 + (0.2 + 0.3));   // 0.6
```

이 성질 때문에 병렬 리덕션(`stream().parallel().sum()`)은 스레드 분할에 따라 결과가 미세하게 달라질 수 있다. 재현 가능한 결과가 필요한 테스트라면 병렬 합산을 피하거나 보상 합산을 써야 한다.

## 3. Kahan 보상 합산 — 잃어버린 하위 비트를 되돌리기

Kahan 합산은 각 덧셈에서 반올림으로 버려진 부분을 별도 변수 `c`에 보관했다가 다음 항에 되돌린다. 오차가 `n`에 비례하지 않고 **상수 수준(2u + O(nu²))**으로 억제된다.

```java
public static double kahanSum(double[] values) {
    double sum = 0.0;
    double compensation = 0.0;          // 누적된 저차 비트
    for (double value : values) {
        double adjusted = value - compensation;
        double temporary = sum + adjusted;
        compensation = (temporary - sum) - adjusted;   // 반올림으로 잃은 양
        sum = temporary;
    }
    return sum;
}
```

핵심은 `(temporary - sum) - adjusted` 한 줄이다. `temporary - sum`은 실제로 더해진 양이고, 거기서 더하려던 양을 빼면 손실분이 남는다.

**Neumaier 변형**은 Kahan이 약한 경우 — 더하는 값이 현재 합보다 훨씬 클 때 — 를 보완한다. 실무에서는 이쪽을 기본으로 쓰는 편이 낫다.

```java
public static double neumaierSum(double[] values) {
    double sum = 0.0;
    double compensation = 0.0;
    for (double value : values) {
        double temporary = sum + value;
        if (Math.abs(sum) >= Math.abs(value)) {
            compensation += (sum - temporary) + value;   // sum 이 큰 경우
        } else {
            compensation += (value - temporary) + sum;   // value 가 큰 경우
        }
        sum = temporary;
    }
    return sum + compensation;   // 마지막에 한 번만 반영
}
```

검증해 보면 차이가 분명하다.

```java
double[] data = { 1.0, 1e100, 1.0, -1e100 };
System.out.println(Arrays.stream(data).sum());   // 0.0    — 두 개의 1.0 이 소멸
System.out.println(neumaierSum(data));           // 2.0    — 올바른 값
System.out.println(kahanSum(data));              // 0.0    — 이 경우 Kahan 도 실패
```

JDK는 이미 이 기법을 쓰고 있다. `DoubleStream.sum()`과 `Collectors.summingDouble`은 내부적으로 Kahan 보상 합산을 사용한다(javadoc에 "Kahan summation" 명시). 그래서 `Arrays.stream(...).sum()`이 단순 for 루프보다 정확한 경우가 많다. 다만 위 예시처럼 실패하는 입력도 존재하므로 "스트림을 쓰면 안전하다"고 일반화해서는 안 된다.

**정렬 후 합산**도 실용적 대안이다. 절댓값 오름차순으로 정렬해 더하면 swamping이 줄어든다. 부호가 섞이지 않은 데이터에 특히 효과적이고, 비용은 O(n log n)이다.

**페어와이즈 합산**은 분할 정복으로 더한다. 오차가 O(log n · u)로 줄고 구현이 단순해 NumPy의 `np.sum`이 채택한 방식이다. 이것이 NumPy 합산이 파이썬 `sum()`보다 정확한 이유다.

```python
import numpy as np
data = np.full(10_000_000, 0.1)
print(sum(data.tolist()))   # 999999.9998389754
print(np.sum(data))         # 1000000.0000000376  (pairwise)
print(math.fsum(data))      # 1000000.0           (정확한 반올림)
```

`math.fsum`은 Shewchuk 알고리즘으로 **정확히 반올림된** 결과를 준다. 가장 정확하지만 가장 느리다.

## 4. 금액 계산 — 이진 부동소수점을 쓰면 안 되는 경계

금액은 십진 소수를 정확히 표현해야 하고, 반올림 규칙이 법·계약으로 정해져 있다. 이진 부동소수점은 둘 다 만족하지 못한다.

```java
double price = 1.10;
double qty = 3;
System.out.println(price * qty);   // 3.3000000000000003
```

부가세 계산에서 이 0.0000000000000003이 반올림 경계를 넘기면 1원 차이가 나고, 그 1원은 정산 불일치로 번진다. 선택지는 둘이다.

**(a) BigDecimal.** 십진 유효숫자와 스케일을 명시적으로 다룬다. 주의점 세 가지.

```java
// 1. 생성자에 double 을 넣지 말 것 — 오차가 그대로 들어온다
new BigDecimal(0.1);            // 0.1000000000000000055511151231257827...
new BigDecimal("0.1");          // 0.1  ← 문자열 생성자 사용
BigDecimal.valueOf(0.1);        // 0.1  ← Double.toString 경유라 안전

// 2. equals 는 스케일까지 비교한다
new BigDecimal("1.0").equals(new BigDecimal("1.00"));       // false
new BigDecimal("1.0").compareTo(new BigDecimal("1.00"));    // 0  ← 값 비교는 이쪽

// 3. 나눗셈은 스케일과 반올림 모드를 반드시 지정
BigDecimal.ONE.divide(new BigDecimal("3"));                 // ArithmeticException
BigDecimal.ONE.divide(new BigDecimal("3"), 10, RoundingMode.HALF_UP);
```

반올림 모드는 도메인이 결정한다. `HALF_UP`(사사오입)은 한국 세무 관행에 가깝고, `HALF_EVEN`(은행가 반올림)은 대량 집계에서 편향이 누적되지 않아 회계·통계에서 선호된다. IEEE 754의 기본 모드도 `HALF_EVEN`이다.

```java
BigDecimal amount = new BigDecimal("2.5");
System.out.println(amount.setScale(0, RoundingMode.HALF_UP));    // 3
System.out.println(amount.setScale(0, RoundingMode.HALF_EVEN));  // 2  (짝수로)
```

**(b) 정수 minor unit.** 금액을 최소 단위 정수로 저장한다. USD는 센트(×100), KRW는 원(×1), JPY는 엔(×1), BHD는 ×1000. Stripe를 비롯한 결제 API 대부분이 이 방식이다. 연산이 `long` 정수라 빠르고 오차가 없다. 단점은 나눗셈(할인율, 분할 결제)에서 나머지 처리를 직접 해야 한다는 것.

```java
// 100원을 3명에게 분배 — 나머지를 버리면 1원이 증발한다
long total = 100L;
int people = 3;
long each = total / people;          // 33
long remainder = total % people;     // 1
// 나머지 1원을 첫 번째 사람에게 배분: 34, 33, 33 → 합 100 보장
```

이 "합계 보존" 검증은 분배 로직의 필수 단위 테스트다.

두 방식 중 무엇을 쓰든 원칙은 같다. **소수 자릿수와 반올림 규칙을 타입에 각인**하고, 경계를 넘을 때마다 검증한다.

```java
public record Money(long minorUnits, Currency currency) {
    public Money {
        Objects.requireNonNull(currency);
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public BigDecimal toAmount() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }
}
```

`Math.addExact`를 쓰면 오버플로가 조용히 랩어라운드하지 않고 `ArithmeticException`을 던진다. 금액 타입에서는 이 편이 항상 옳다.

## 5. 경계에서 새는 정밀도 — DB, JSON, 직렬화

타입을 잘 골라도 경계를 넘으면서 깨지는 경우가 흔하다.

**DB 컬럼.** Oracle의 `NUMBER(p, s)`는 십진 기반이라 안전하다. `BINARY_DOUBLE`/`BINARY_FLOAT`는 IEEE 754이므로 금액에 쓰면 안 된다. MySQL의 `DECIMAL(p, s)`도 십진이지만 `FLOAT`/`DOUBLE`은 아니다. PostgreSQL의 `numeric`은 십진, `double precision`은 이진.

```sql
-- Oracle
amount NUMBER(19, 4)        -- OK
amount BINARY_DOUBLE        -- 금액에 부적합

-- MySQL
amount DECIMAL(19, 4)       -- OK
amount DOUBLE               -- 금액에 부적합
```

JDBC 매핑도 확인이 필요하다. `ResultSet.getDouble()`로 읽으면 `DECIMAL` 컬럼이라도 그 시점에 이진으로 변환된다. `getBigDecimal()`을 써야 한다. JPA에서는 필드 타입을 `BigDecimal`로 두고 `@Column(precision = 19, scale = 4)`를 명시한다.

**JSON.** JSON 숫자에는 정밀도 규정이 없고, 대부분의 파서가 `double`로 읽는다. JavaScript의 `Number`는 binary64이므로 안전 정수 범위가 `±(2^53 - 1)` = ±9007199254740991이다. Snowflake ID나 Twitter ID 같은 64비트 정수가 프론트엔드에서 깨지는 원인이 이것이다.

```js
console.log(Number.MAX_SAFE_INTEGER);        // 9007199254740991
console.log(9007199254740993);               // 9007199254740992  ← 값이 바뀐다
```

해법은 **큰 정수와 금액을 문자열로 직렬화**하는 것이다.

```java
public class ApiResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private long orderId;              // "9007199254740993"

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;         // "1234.5600"
}
```

Jackson 전역 설정으로 막을 수도 있다.

```java
ObjectMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();
```

프론트엔드에서는 `BigInt` 또는 decimal 라이브러리(decimal.js, big.js)로 받는다. 표준 `JSON.parse`는 큰 정수를 복원할 수 없으므로 문자열로 주고받는 계약이 안전하다.

**캐시 직렬화.** Redis에 JSON으로 넣었다 빼는 과정에서도 같은 문제가 생긴다. 금액을 캐싱한다면 문자열 또는 minor unit 정수로 넣는다.

## 6. 부동소수점 비교 — `==`를 쓰면 안 되는 이유와 대안

`a == b`는 거의 항상 틀린 선택이다. 그렇다고 고정 임계값 `Math.abs(a - b) < 1e-9`도 위험하다. 값의 크기에 따라 의미가 달라지기 때문이다. 1e-9는 1.0 근처에서는 관대하고 1e12 근처에서는 아무것도 통과시키지 못한다.

상대 오차와 절대 오차를 함께 쓰는 것이 표준이다.

```java
public static boolean nearlyEquals(double a, double b, double relTol, double absTol) {
    if (a == b) { return true; }                       // 무한대 동일 처리
    double diff = Math.abs(a - b);
    double scale = Math.max(Math.abs(a), Math.abs(b));
    return diff <= Math.max(absTol, relTol * scale);
}
// 관용적 기본값: relTol = 1e-9, absTol = 1e-12
```

Python 3.5+의 `math.isclose(a, b, rel_tol=1e-09, abs_tol=0.0)`가 같은 정의다.

**ULP 기반 비교**는 더 정밀하다. 두 값 사이에 표현 가능한 부동소수점 수가 몇 개 있는지를 센다.

```java
public static boolean withinUlps(double a, double b, int maxUlps) {
    if (Double.isNaN(a) || Double.isNaN(b)) { return false; }
    long bitsA = Double.doubleToLongBits(a);
    long bitsB = Double.doubleToLongBits(b);
    if ((bitsA < 0) != (bitsB < 0)) { return a == b; }   // 부호 다르면 ±0 만 동일
    return Math.abs(bitsA - bitsB) <= maxUlps;
}
```

테스트 프레임워크에는 이미 들어 있다. JUnit 5의 `assertEquals(expected, actual, delta)`, AssertJ의 `isCloseTo(value, within(0.01))` 또는 `withPercentage(1.0)`.

특수값 처리도 잊지 말 것. `NaN != NaN`이므로 `Double.isNaN()`으로 검사해야 하고, `0.0 == -0.0`은 참이지만 `Double.compare(0.0, -0.0)`은 음수를 반환한다. `Double.equals`와 `==`의 동작이 NaN·±0에서 갈린다는 점이 정렬·`HashMap` 키에서 사고를 낸다.

```java
Double.valueOf(Double.NaN).equals(Double.NaN);   // true  (equals 는 비트 비교)
Double.NaN == Double.NaN;                        // false (IEEE 규칙)
```

## 7. 언제 double이 옳은 선택인가

지금까지가 경고였다면, 반대 방향도 분명히 해 둘 필요가 있다. `double`은 대부분의 수치 계산에서 여전히 최선이다.

- **물리·공학·통계 계산.** 입력 자체가 측정 오차를 가지므로 1e-16의 표현 오차는 무의미하다. `BigDecimal`을 쓰면 수십 배 느려지기만 한다.
- **좌표·거리·확률.** 십진 정확성이 요구되지 않는다.
- **머신러닝.** 오히려 float32, bfloat16으로 정밀도를 낮춰 처리량을 올린다.

성능 차이는 무시할 수준이 아니다. `BigDecimal` 연산은 객체 할당과 십진 자릿수 처리 때문에 `double`보다 대략 한 자릿수 이상 느리고, 대량 루프에서는 GC 압력까지 더해진다. JMH로 측정하면 덧셈 기준 수십 배 차이가 나는 것이 보통이다.

```java
@Benchmark
public double doubleSum() {
    double s = 0.0;
    for (int i = 0; i < SIZE; i++) { s += doubles[i]; }
    return s;
}

@Benchmark
public BigDecimal bigDecimalSum() {
    BigDecimal s = BigDecimal.ZERO;
    for (int i = 0; i < SIZE; i++) { s = s.add(decimals[i]); }
    return s;
}
```

판단 기준은 단순하다. **"이 값이 사람에게 십진수로 보이고, 두 시스템이 그 값의 일치를 검증하는가?"** 그렇다면 십진 또는 정수. 아니라면 `double`.

정리하면 세 층이다. 도메인 경계(금액·수량·비율)는 `BigDecimal`이나 minor unit 정수로 고정하고, 내부 수치 계산은 `double`로 하되 합산에는 보상 알고리즘을 쓰고, 두 층 사이의 변환 지점(DB·JSON·캐시)마다 정밀도가 유지되는지 테스트로 못 박는다.

## 참고

- IEEE Std 754-2019, IEEE Standard for Floating-Point Arithmetic
- David Goldberg, "What Every Computer Scientist Should Know About Floating-Point Arithmetic", ACM Computing Surveys 23(1), 1991
- Nicholas J. Higham, *Accuracy and Stability of Numerical Algorithms*, 2nd ed., SIAM (보상 합산 오차 분석)
- Java SE API — `java.math.BigDecimal`, `java.math.RoundingMode`, `java.util.stream.DoubleStream.sum()`
- Python Documentation — `math.fsum`, `math.isclose`, `decimal` 모듈
- NumPy Documentation — `numpy.sum` (pairwise summation)
- ECMA-262 — Number 타입 및 `Number.MAX_SAFE_INTEGER`
