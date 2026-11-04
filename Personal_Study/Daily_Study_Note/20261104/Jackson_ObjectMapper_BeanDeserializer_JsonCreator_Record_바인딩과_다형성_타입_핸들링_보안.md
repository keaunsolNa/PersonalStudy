Notion 원본: https://app.notion.com/p/40e5a06fd6d381a9ba20ddb74c7364f2?pvs=204

# Jackson ObjectMapper 내부 구조: BeanDeserializer 생성 경로와 @JsonCreator·Record 바인딩 및 Polymorphic Type Handling 보안(활성화 기본 타이핑 위험)

> 2026-11-04 신규 주제 · 확장 대상: Spring

## 학습 목표

- ObjectMapper가 클래스별 BeanDeserializer를 만들고 캐시하는 경로를 BeanDeserializerModifier 훅으로 직접 관찰한다.
- @JsonCreator의 PROPERTIES·DELEGATING 모드와 Record 정규 생성자 바인딩이 누락·null 입력을 처리하는 방식을 실행 결과로 비교한다.
- 기본 타이핑(default typing)이 신루할 수 없는 입력에서 클래스를 인스턴스화하는 구조를 확인하고 allowlist PolymorphicTypeValidator로 차단한다.
- Spring Boot 설정에서 @JsonTypeInfo(NAME)와 sealed 계층으로 다형성을 닫힌 집합으로 제한하는 방법을 적용한다.

## 1. 왜 내부 구조를 알아야 하는가

Spring MVC의 @RequestBody, RestClient, Redis 직렬화는 결국 ObjectMapper.readValue로 수렴한다. 평소에는 "JSON이 객체로 바뀔다"만 알아도 충분하지만, 생성자가 호출되지 않는다거나 누락된 필드가 0으로 채워진다거나 특정 클래스 이름이 JSON에 있다는 이유로 서버가 그 클래스를 만든다는 문제는 모두 역직렬화기 생성 단계에서 결정된 규칙의 결과다. 이 문서는 그 규칙이 어디서 정해지는지를 코드로 확인하고, 마지막에는 가장 위험한 설정인 기본 타이핑을 방어 관점에서 다룬다.

모든 실험은 jackson-databind 2.18.2(jackson-core, annotations 동일 버전), OpenJDK 21.0.12, Maven으로 받은 jar를 classpath에 직접 올려 실행했다. 의존성은 아래 한 줄이 전부이며 출력은 이후 섹션에 실행한 그대로 옮겼다.

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.18.2</version>
</dependency>
```

Spring Boot는 Jackson2ObjectMapperBuilder로 일부 기본값을 바꾸므로(예: FAIL_ON_UNKNOWN_PROPERTIES 비활성화) 아래 결과는 모두 순수 new ObjectMapper() 기준이다.

## 2. ObjectMapper가 BeanDeserializer를 만드는 경로

readValue(json, Plain.class)가 호출되면 ObjectMapper는 먼저 루트 타입별 역직렬화기를 담은 내부 ConcurrentHashMap에서 찾고, 없으면 DefaultDeserializationContext를 통해 DeserializerCache에 위임한다. 캐시에도 없으면 BeanDeserializerFactory.buildBeanDeserializer가 호출된다. 이 팩토리는 클래스를 introspect한 BeanDescription을 받아 BeanDeserializerBuilder를 만들고, 프로퍼티(setter, 필드, creator 파라미터)를 builder에 등록하며, ValueInstantiator에 어떤 생성자나 팩토리 메서드를 쓸지 기록한다. 마지막으로 builder.build()가 BeanDeserializer를 만든다. 사용자가 끼어들 수 있는 지점이 BeanDeserializerModifier이며, updateBuilder는 build 직전, modifyDeserializer는 완성된 직후에 호출된다.

두 훅에서 로그를 찍는 모듈을 등록하고 같은 타입을 두 번 읽었다.

```java
SimpleModule sm = new SimpleModule().setDeserializerModifier(new BeanDeserializerModifier() {
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig c, BeanDescription d, BeanDeserializerBuilder b) {
        System.out.println("  updateBuilder " + d.getBeanClass().getSimpleName() + " props=" + iter(b.getProperties()));
        return b; }
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig c, BeanDescription d, JsonDeserializer<?> s) {
        System.out.println("  modifyDeserializer " + d.getBeanClass().getSimpleName() + " -> " + s.getClass().getSimpleName());
        return s; }
});
```

실행 결과는 다음과 같다.

```text
jackson=2.18.2
call 1
  updateBuilder Plain props=[name, age]
  modifyDeserializer Plain -> BeanDeserializer
  elapsed(us)=100284
call 2
  elapsed(us)=228
  updateBuilder Ctor props=[id, qty]
  modifyDeserializer Ctor -> BeanDeserializer
  updateBuilder Money props=[cur, amount]
  modifyDeserializer Money -> BeanDeserializer
```

두 번째 호출에서는 훅이 전혀 불리지 않았으므로 역직렬화기가 캐시에서 재사용된 것이 확인된다. 첫 호출 약 100ms와 두 번째 호출 약 228us의 차이는 introspection뿐 아니라 JVM 클래스 로딩과 워밍업이 섞인 값이어서, "첫 호출이 400배 느리다"는 일반화에는 쓰면 안 된다. 의미 있는 결론은 비용이 타입당 한 번이라는 점이며, 요청마다 new ObjectMapper()를 만드는 코드는 이 캐시를 매번 버리는 셉이다. 또 Ctor와 Money(Record)가 같은 BeanDeserializer로 나오므로 차이는 역직렬화기 종류가 아니라 ValueInstantiator와 프로퍼티 목록에서 생긴다.

## 3. @JsonCreator 모드: PROPERTIES와 DELEGATING

기본 생성자도 없고 setter도 없는 불변 클래스는 creator를 통해서만 만들 수 있다. @JsonCreator를 붙인 생성자의 파라미터가 JSON 프로퍼티와 1:1로 대응하면 PROPERTIES 모드, JSON 값 전체를 파라미터 하나로 받으면 DELEGATING 모드다. 파라미터가 하나뿐이면 모호하므로 mode를 명시하는 것이 안전하다.

```java
public static class Ctor {
    final String id; final int qty;
    @JsonCreator public Ctor(@JsonProperty("id") String id, @JsonProperty("qty") int qty) { ... }
}
public static class Deleg {
    final String v;
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING) public Deleg(String v) { this.v = v; }
}
public static class NoAnno {
    final String id;
    public NoAnno(String id) { this.id = id; }
}
```

```text
Ctor[id=A,qty=2]
Ctor[id=A,qty=0]
Deleg[hello]
NoAnno: MismatchedInputException Cannot construct instance of `Demo$NoAnno` (although at least one Creator exists): cannot deserialize from Object value (no delegate- or property-based Creator)
```

두 번째 줄이 이 섹션의 핵심 trade-off다. JSON에서 qty가 빠졌는데 예외 없이 qty=0이 들어갔다. primitive int 파라미터에는 누락 시 기본값 0이 주입되고, 객체 타입이면 null이 주입된다. 마지막 NoAnno는 파라미터 이름 정보가 없기 때문에(컴파일러에 -parameters를 주지 않았고 ParameterNamesModule도 없다) 단일 인자 생성자를 properties 생성자로 쓸 수 없어 실패한다. Boot에서는 parameter-names 모듈이 등록되어 같은 클래스가 동작할 수 있으므로, 라이브러리 코드에서는 @JsonProperty를 명시하는 편이 이식성이 좋다.

## 4. Record 바인딩과 누락·null 입력의 엄격도

Jackson 2.12부터 Record를 별도 지원한다. 정규 생성자(canonical constructor)가 암묵적 properties creator로 취급되고 컴포넌트 이름이 곧 프로퍼티 이름이므로 @JsonCreator나 ParameterNamesModule 없이도 바인딩된다. 컴팩트 생성자의 검증도 그대로 실행된다.

```java
public record Money(String cur, long amount) {
    public Money { if (amount < 0) throw new IllegalArgumentException("amount<0"); }
}
public record Tagged(@JsonProperty("n") String name, int v) {}
```

```text
Money[cur=KRW, amount=5]
Money[cur=KRW, amount=0]
compact ctor: ValueInstantiationException cause=java.lang.IllegalArgumentException: amount<0
Tagged[name=x, v=1]
{"n":"x","v":1}
```

누락된 amount는 일반 클래스와 릍같이 0이 되었다. 컴팩트 생성자의 예외는 ValueInstantiationException으로 감싸져 나온다. 컴포넌트에 붙인 @JsonProperty는 읽기와 쓰기 양쪽 이름을 모두 바꿈다. 누락과 null을 엄격하게 막으려면 두 개의 DeserializationFeature가 있다.

```java
new ObjectMapper().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
new ObjectMapper().enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
```

```text
strict: MismatchedInputException Missing creator property 'amount' (index 1); `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES` enabled
nullcreator: MismatchedInputException Null value for creator property 'cur' (index 0); `DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES` enabled
```

| 입력 | 기본 설정 | MISSING 활성 | NULL 활성 |
|---|---|---|---|
| amount 키 없음 | amount=0 | 예외 | 해당 없음 |
| cur가 명시적 null | cur=null | cur=null | 예외 |

이 옵션은 전역이라 선택 필드를 생략하던 클라이언트와 호환성이 깨질 수 있으므로, 필수 값 검증을 Bean Validation이나 컴팩트 생성자에 두는 방식과 비교해 선택한다.

## 5. 기본 타이핑이 위험한 이유

JSON에는 원래 타입 정보가 없다. 필드가 Object나 인터페이스 타입이면 Jackson은 구체 클래스를 알 수 없어서 Map이나 List로 만든다. 이 한계를 우회하려고 타입 정보를 JSON에 넣고 읽는 기능이 기본 타이핑이다. 예전 enableDefaultTyping은 deprecated 되었고 PolymorphicTypeValidator(PTV)를 요구하는 activateDefaultTyping이 대체한다. 위험의 본질은 간단하다. 타입 정보가 입력 JSON 안에 있기 때문에 클래스 이름을 요청자가 정하고, 서버는 그 이름의 클래스를 로드해 생성자와 setter를 호출한다.

여기서는 공격 코드를 쓰지 않고, 생성자 호출 횟수를 세는 무해한 클래스로 "입력이 지정한 클래스가 인스턴스화되는가"만 확인했다.

```java
public static class Marker { static int created = 0; public Marker() { created++; } public String x; }
public static class Holder { public Object payload; }

String json = "{\"@class\":\"Demo$Holder\",\"payload\":{\"@class\":\"Demo$Marker\",\"x\":\"v\"}}";
ObjectMapper lax = new ObjectMapper();
lax.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
    ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
Holder h = lax.readValue(json, Holder.class);
```

```text
lax: Demo$Marker created=1
```

선언 타입은 Object였는데 요청 본문이 고른 Marker가 만들어졌고 생성자도 실행되었다. LaissezFaireSubTypeValidator는 아무것도 검증하지 않아 이 구성은 사실상 enableDefaultTyping과 같은 위험을 가진다. 부수효과가 위험한 클래스가 클래스패스에 하나라도 있으면 위험이 성립한다. 이는 Jackson의 버그가 아니라 설정의 결과이며, 알려진 클래스만 막는 denylist는 모르는 클래스에 무력하다.

## 6. 방어 1: allowlist PolymorphicTypeValidator

기본 타이핑을 꼭 써야 한다면 허용할 베이스 타입과 패키지를 allowlist로 좁힌 BasicPolymorphicTypeValidator를 쓴다. 같은 입력을 allowlist로 바꾸어 실행했다.

```java
PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
    .allowIfSubType("Demo$Holder")
    .allowIfSubType("java.util.")
    .allowIfSubType("java.time.")
    .build();
ObjectMapper safe = new ObjectMapper();
safe.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
```

```text
safe: InvalidTypeIdException Could not resolve type id 'Demo$Marker' as a subtype of `java.lang.Object`: Configured `PolymorphicTypeValidator` (of type `com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator`) denied resolution
created after safe=1
safe allowed: [1, 2]
{"@class":"Demo$Holder","payload":["java.util.ArrayList",[1,2]]}
```

created가 1에서 늘지 않았으므로 거부는 인스턴스화 이전에 일어난다. 허용한 java.util.ArrayList는 정상 처리되었다. 설계 원칙은 베이스 타입을 가능한 한 좁게(allowIfBaseType) 잡고, 패키지 접두어를 자신이 소유한 도메인 패키지로 제한하며, java.lang.Object처럼 모든 클래스가 만족하는 베이스 타입을 허용하지 않는 것이다. 위 예제의 java.util.은 접두어 규칙이라 패키지 전체를 허용하므로 실제로는 필요한 구현체 몇 개로 줄이는 편이 안전하다.

trade-off도 있다. allowlist는 새 도메인 클래스를 추가할 때마다 갱신해야 하고, 갱신을 잊으면 운영에서 InvalidTypeIdException이 난다. 그러나 이 실패는 요청이 거절되는 안전한 방향이고, 검증 없는 허용은 조용히 성공한다.

## 7. 방어 2: 기본 타이핑 대신 이름 기반 닫힌 계층

다형성이 필요한 이유가 대개 "몇 개 알려진 변형을 구분"이라면 기본 타이핑이 필요 없다. @JsonTypeInfo(use = NAME)과 @JsonSubTypes로 논리 이름을 구체 클래스에 매핑하면 JSON에 클래스 이름이 아닌 별칭만 들어가고, 선언한 서브타입 밖의 이름은 해석 자체가 되지 않는다. Java 17 이상에서는 sealed 인터페이스와 Record를 조합하면 컴파일러도 계층이 닫혀 있음을 보장한다.

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = Card.class, name = "card"),
               @JsonSubTypes.Type(value = Bank.class, name = "bank")})
public sealed interface Pay permits Card, Bank {}
public record Card(String no) implements Pay {}
public record Bank(String acct) implements Pay {}
```

```text
Card[no=1]
{"type":"bank","acct":"9"}
InvalidTypeIdException Could not resolve type id 'java.lang.Object' as a subtype of `Poly$Pay`: known type ids = [bank, card]
```

클래스 이름을 넣은 입력은 알려진 id 목록(bank, card)과 함께 거부되었다. 대가는 서브타입을 나열하는 유지보수 비용과, 수정할 수 없는 외부 클래스에는 mix-in이 필요하다는 점이다. 반면 패키지를 이동해도 JSON 포맷이 깨지지 않아 API 계약 면에서도 낫다.

| 방식 | JSON에 노출되는 값 | 허용 범위 결정 | 주된 위험 |
|---|---|---|---|
| 기본 타이핑 + LaissezFaire | 완전한 클래스 이름 | 없음(클래스패스 전체) | 입력이 클래스 선택 |
| 기본 타이핑 + allowlist PTV | 완전한 클래스 이름 | PTV 규칙 | 규칙이 넓으면 우회 여지 |
| @JsonTypeInfo(NAME) + @JsonSubTypes | 논리 이름 | 어노테이션 목록 | 서브타입 추가 시 수정 필요 |

## 8. Spring Boot에 적용하는 체크리스트

외부 입력을 받는 mapper에는 기본 타이핑을 켜지 않는다. Redis 캐시처럼 서버가 쓰고 읽는 저장소에서 타입 보존을 위해 기본 타이핑을 켜는 설정이 흔한데, 이때는 쓰기 주체가 신루 경계 안인지 확인하고 아래처럼 allowlist를 가진 별도 mapper를 해당 직렬화기에만 연결한다.

```java
@Bean
ObjectMapper cacheMapper() {
    PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType("com.example.cache.CacheValue")
        .allowIfSubType("com.example.cache.")
        .build();
    ObjectMapper m = new ObjectMapper();
    m.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    return m;
}
```

위 설정의 패키지 이름은 예시이며 이 문서에서 실행한 코드가 아니다. 실행으로 검증한 것은 섹션 6의 구성이다. 점검 순서는 enableDefaultTyping·activateDefaultTyping·LaissezFaireSubTypeValidator 사용처 검색, Object·인터페이스 필드 DTO 점검, NAME 기반 닫힌 계층으로 전환, 잘못된 type id가 거절되는 테스트의 CI 추가다.

## 참고

- Jackson 공식 저장소 FasterXML/jackson-databind (BeanDeserializerFactory, DeserializerCache, BeanDeserializerModifier 소스)
- jackson-databind Wiki: Polymorphic Deserialization
- Javadoc: com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator, DeserializationFeature
- Spring Boot Reference Documentation: Customizing the Jackson ObjectMapper
- jackson-databind Wiki: Jackson Release 2.12 (Java Record 지원)
