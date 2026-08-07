Notion 원본: https://www.notion.so/3b55a06fd6d38100b407f5aa41d1d7d0

# Java invokedynamic과 LambdaMetafactory 및 람다 컴파일 내부

> 2026-08-07 신규 주제 · 확장 대상: JAVA (JIT 컴파일러·JVM 내부 기학습)

## 학습 목표

- JVM 4대 invoke 바이트코드와 invokedynamic 의 링키지 방식 차이를 바이트코드 수준에서 구분한다.
- MethodHandle·CallSite·bootstrap method 의 최초 링크 절차를 순서대로 설명한다.
- 람다식과 메서드 참조가 javac 에서 invokedynamic + LambdaMetafactory 로 디슈가링되는 과정을 javap 출력으로 검증한다.
- 람다 vs 익명 클래스의 콜드 스타트·정상 상태 성능 trade-off 를 JIT 인라이닝 관점에서 판단한다.

## 1. JVM 의 다섯 가지 invoke 바이트코드와 JSR 292 배경

JVM 은 클래식하게 네 가지 메서드 호출 바이트코드를 제공한다. 각 명령은 "어떤 메서드를 호출할지"를 클래스 파일의 상수 풀(constant pool) 심볼릭 레퍼런스로 고정하고, 해석(resolution) 규칙도 명령별로 정해져 있다.

| 명령 | 대상 | 디스패치 | 대표 사례 |
|---|---|---|---|
| invokestatic | static 메서드 | 정적 바인딩 | `Integer.parseInt` |
| invokespecial | 생성자, private, super 호출 | 정적 바인딩(수신자 있음) | `<init>`, `super.m()` |
| invokevirtual | 인스턴스 메서드 | vtable 기반 가상 디스패치 | `obj.toString()` |
| invokeinterface | 인터페이스 메서드 | itable 탐색 디스패치 | `list.add(x)` |

네 명령의 공통점은 링크 대상(클래스·메서드 이름·디스크립터)이 컴파일 시점에 확정된다는 것이다. 정적 타입 언어인 Java 에는 적합하지만, JRuby·Groovy 같은 동적 언어를 JVM 위에 구현할 때는 병목이었다. 수신자 타입이 런타임에야 정해져 리플렉션(`Method.invoke`)으로 우회해야 했고, 이 경로는 박싱·배열 래핑·접근 검사 때문에 JIT 인라이닝이 사실상 불가능했다.

JSR 292(JDK 7, 2011)는 다섯 번째 호출 명령 **invokedynamic**(이하 indy)을 추가했다. 핵심 발상은 "호출 대상 결정을 **언어 런타임(부트스트랩 메서드)** 에 위임하되, 결과는 JVM 이 직접 호출 가능한 MethodHandle 로 받아 JIT 최적화 대상에 포함시킨다"는 것이다. 상수 풀에는 대상 메서드 대신 `CONSTANT_InvokeDynamic` 엔트리(부트스트랩 메서드 참조 + 이름 + MethodType)만 들어간다. 아이러니하게도 최대 수혜자는 동적 언어가 아니라 Java 자신으로, JDK 8 람다·JDK 9 문자열 결합·JDK 16 레코드·JDK 21 패턴 스위치가 모두 indy 위에 구축되었다.

## 2. MethodHandle · MethodType · Lookup · CallSite 와 부트스트랩 절차

`java.lang.invoke` 패키지의 핵심 타입은 네 가지다.

**MethodType** 은 반환 타입과 파라미터 타입의 불변 조합으로, `MethodType.methodType(int.class, String.class)` 는 `(String)int` 시그니처를 뜻하며 인터닝되어 `==` 비교가 가능하다. **MethodHandle** 은 메서드·필드·생성자에 대한 직접 실행 가능 참조로, 리플렉션과 달리 접근 검사를 **생성 시점에 1회만** 수행한다. `invokeExact` 는 MethodType 완전 일치를 요구하고(불일치 시 `WrongMethodTypeException`), `invoke` 는 `asType` 변환을 허용한다. **MethodHandles.Lookup** 은 핸들 팩터리이자 능력 토큰(capability token)으로, `MethodHandles.lookup()` 을 호출한 클래스의 접근 권한(private 포함)을 캡슐화한다. 람다가 private 합성 메서드를 호출할 수 있는 근거가 이 Lookup 전달이다. **CallSite** 는 indy 호출 지점과 MethodHandle(target)을 잇는 홀더다. `ConstantCallSite` 는 target 이 불변이라 JIT 이 상수로 취급해 완전 인라이닝하고, `MutableCallSite`/`VolatileCallSite` 는 `setTarget` 교체가 가능한 대신 교체 시 탈최적화가 일어난다.

indy 명령이 최초 실행될 때의 링크 절차는 다음 순서다.

1. JVM 이 상수 풀에서 `CONSTANT_InvokeDynamic` 엔트리를 해석해 부트스트랩 메서드(BSM)와 정적 인자를 찾는다.
2. BSM 을 호출한다. 시그니처 관례는 `(Lookup caller, String name, MethodType type, ...)CallSite` 다.
3. BSM 이 반환한 CallSite 를 해당 호출 지점에 **영구 결합**한다. 같은 지점의 이후 실행은 BSM 을 다시 거치지 않고 `callSite.getTarget()` 의 MethodHandle 을 바로 호출한다. 실질 비용이 드는 것은 최초 1회뿐이다.

```java
public class IndyManualDemo {
	public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type) throws Exception {
		MethodHandle target = caller.findStatic(IndyManualDemo.class, "concatImpl", type);
		return new ConstantCallSite(target);
	}

	private static String concatImpl(String a, String b) {
		return a + b;
	}
}
```

BSM 이 예외를 던지면 `BootstrapMethodError` 로 래핑되며, 해당 호출 지점은 이후에도 계속 실패한다(링크 실패의 영속성).

## 3. 람다식은 어떻게 컴파일되는가

JDK 8 설계 당시 익명 내부 클래스로 번역하는 안이 유력했지만, 최종적으로 "번역 전략을 바이트코드에 굽지 않는다"는 원칙 아래 indy 가 채택되었다. javac 는 람다를 두 부분으로 쪼갠다.

첫째, 람다 본문은 소속 클래스의 **private 합성 메서드**(`lambda$메서드명$번호`, `ACC_SYNTHETIC`)로 옮긴다. 캡처 변수가 없으면 static, `this` 를 캡처하면 인스턴스 메서드가 된다. 둘째, 람다식 위치에는 indy 명령 하나만 남기고 BSM 으로 `LambdaMetafactory.metafactory` 를 지정한다.

```java
public class LambdaCompileDemo {
	public Runnable makeStateless() {
		return () -> System.out.println("hello");
	}

	public Supplier<Integer> makeCapturing(int base) {
		return () -> base + 1;
	}
}
```

`javap -c -p LambdaCompileDemo` 출력의 핵심 부분은 다음과 같다.

```text
public java.lang.Runnable makeStateless();
    0: invokedynamic #7,  0    // InvokeDynamic #0:run:()Ljava/lang/Runnable;
    5: areturn

public java.util.function.Supplier<java.lang.Integer> makeCapturing(int);
    0: iload_1
    1: invokedynamic #11, 0    // InvokeDynamic #1:get:(I)Ljava/util/function/Supplier;
    6: areturn

private static void lambda$makeStateless$0();   // 합성 메서드
private static java.lang.Integer lambda$makeCapturing$1(int);
```

`metafactory` 의 정적 인자 3개는 (1) 인터페이스 추상 메서드의 소거된 시그니처(samMethodType), (2) 합성 메서드를 가리키는 MethodHandle(implMethod), (3) 실제 특수화 시그니처(instantiatedMethodType)이고, 동적 인자는 캡처 값들이다. 여기서 성능 특성이 갈린다.

- **비캡처 람다**: indy 의 MethodType 이 `()Runnable` 처럼 인자가 없다. 메타팩터리는 구현 인스턴스를 **1개만 만들어 상수로 캐시**하므로 몇 번을 실행해도 같은 객체가 반환되고 할당이 0이다.
- **캡처 람다**: 캡처 값이 생성자 인자로 필요해 target 이 생성자 핸들이 되고, 실행마다 새 인스턴스가 생성된다. 다만 escape analysis 로 제거될 수 있는 얕은 할당이다.

익명 클래스는 두 경우 모두 `new` 마다 새 인스턴스를 만든다. 람다 캡처는 필드 저장이 아니라 생성자 인자 복사이므로, 캡처 대상이 effectively final 이어야 한다는 언어 규칙과 맞물린다.

## 4. LambdaMetafactory 내부: 런타임 클래스 스피닝과 hidden class

`LambdaMetafactory.metafactory` 는 내부적으로 `InnerClassLambdaMetafactory` 에 위임한다. 이 클래스는 ASM(JDK 내부 포크, `jdk.internal.org.objectweb.asm`, JDK 24+ 는 java.lang.classfile API)으로 함수형 인터페이스 구현 클래스의 바이트를 **런타임에 즉석 생성(spin)** 한다. 생성되는 클래스는 캡처 값을 담는 final 필드, 그것을 채우는 생성자, 그리고 합성 메서드로 위임하는 인터페이스 메서드 구현만 가진 극도로 단순한 형태다.

JDK 8~14 에서는 `Unsafe.defineAnonymousClass` 로 로드했고, JDK 15 부터는 JEP 371 의 표준 API 인 `Lookup.defineHiddenClass(bytes, initialize, NESTMATE, STRONG)` 를 사용한다. hidden class 는 이름으로 발견 불가(`Class.forName` 실패), 다른 클래스의 상수 풀에서 참조 불가, 클래스로더 등록 명단에 없어 단독 언로드가 가능하다. NESTMATE 옵션 덕에 호스트 클래스의 nest 에 편입되어 private 합성 메서드를 접근 검사 없이 호출한다. `toString()` 시 `LambdaCompileDemo$$Lambda/0x00007f...` 처럼 `/` 뒤에 주소가 붙는 이름이 hidden class 의 표식이다.

익명 내부 클래스 번역 대비 이 구조의 장점은 세 가지다.

| 항목 | 익명 내부 클래스 | indy + 메타팩터리 |
|---|---|---|
| 클래스 파일 | 람다마다 `Outer$1.class` 정적 생성 | 없음(합성 메서드만 추가) |
| 초기화 시점 | 클래스 로딩 시 즉시 | 최초 실행 시 지연(lazy) |
| 번역 전략 | 바이트코드에 고정 | JDK 업그레이드만으로 개선 수용 |
| 비캡처 인스턴스 | 매번 생성(기본) | 싱글턴 캐시 |

세 번째가 설계상 핵심이다. 실제로 JDK 8 에서 컴파일된 바이트코드가 JDK 15+ 에서 자동으로 hidden class 혜택을 받았고, 향후 전략이 바뀌어도 재컴파일이 필요 없다. 반대급부는 최초 호출 시 링키지 비용으로, 람다 1개당 클래스 스핀 + 링크에 콜드 JVM 기준 수백 µs, `java.lang.invoke` 인프라 부팅까지 합친 첫 람다는 수 ms 가 든다. AOT/CDS(JEP 483 등)가 이 비용을 줄이는 방향으로 발전 중이다.

## 5. 메서드 참조 4형태의 디슈가링과 직렬화 람다

메서드 참조는 합성 메서드가 필요한 경우와 기존 메서드를 그대로 implMethod 로 쓰는 경우로 갈린다.

| 형태 | 예 | implMethod | 합성 메서드 |
|---|---|---|---|
| static 참조 | `Integer::parseInt` | 대상 static 메서드 직접 | 불필요 |
| 바운드 인스턴스 | `str::length` | 대상 인스턴스 메서드, 수신자를 캡처 | 불필요 |
| 언바운드 인스턴스 | `String::length` | 대상 메서드, 수신자가 첫 파라미터로 승격 | 불필요 |
| 생성자 참조 | `ArrayList::new` | `<init>` 핸들 (newInvokeSpecial) | 불필요 |

즉 순수 메서드 참조는 `lambda$` 합성 메서드가 생기지 않아 람다식(`s -> s.length()`)보다 클래스 파일이 미세하게 작다. 단 varargs 어댑테이션 등 경계 사례에서는 javac 가 브리지 성격의 합성 메서드를 만들기도 한다. 바운드 참조 `str::length` 는 수신자를 캡처하므로 캡처 람다처럼 실행마다 새 인스턴스를 만든다.

직렬화 가능 람다(`Runnable & Serializable` 캐스트 또는 Serializable 상속 인터페이스)는 BSM 이 `LambdaMetafactory.altMetafactory` 로 바뀌고 `FLAG_SERIALIZABLE` 플래그가 붙는다. 스핀된 클래스에 `writeReplace()` 가 추가되어 직렬화 시 실제 객체 대신 **SerializedLambda**(캡처 클래스·인터페이스·implMethod 시그니처·캡처 값의 명세 객체)를 내보내고, 역직렬화 시 캡처 클래스에 javac 가 심어 둔 `$deserializeLambda$` 합성 메서드가 명세를 검증해 람다를 재생성한다. 이 검증이 임의 클래스 주입을 차단하지만, 합성 메서드 이름·번호가 명세에 포함되므로 **재컴파일만으로 역직렬화가 깨질 수 있다**. 장기 저장·분산 캐시에 직렬화 람다를 쓰지 말라는 권고의 근거다.

## 6. indy 활용의 확대: 문자열 결합, 레코드, 패턴 스위치

**JEP 280(JDK 9, indified string concatenation)** 은 `a + b + c` 를 `StringBuilder` 체인 대신 indy 1개로 컴파일한다. BSM 은 `StringConcatFactory.makeConcatWithConstants` 이며, 상수 부분은 레시피 문자열의 정적 인자로, 동적 값만 스택 인자로 전달된다.

```text
// javap: String s = "id=" + id + ", name=" + name;
invokedynamic #9, 0
    // InvokeDynamic #0:makeConcatWithConstants:(ILjava/lang/String;)Ljava/lang/String;
    // 레시피: "id=, name="
```

기본 전략 `MH_INLINE_SIZED_EXACT` 는 최종 길이를 먼저 정확히 계산해 배열을 한 번만 할당하는 MethodHandle 트리를 조립한다. StringBuilder 방식 대비 중간 배열 재할당·복사가 사라져 OpenJDK 의 JMH 측정에서 다수 시나리오 1.5~4배 처리량 향상이 보고되었고, 이 역시 재컴파일 없이 런타임 전략 교체가 가능하다(JDK 22 부터 hidden class 기반으로 재구현).

**레코드(JDK 16)** 의 `equals`/`hashCode`/`toString` 은 javac 가 본문을 생성하지 않고 `java.lang.runtime.ObjectMethods.bootstrap` 을 BSM 으로 하는 indy 하나로 컴파일된다. 컴포넌트 접근자 MethodHandle 목록을 정적 인자로 받아 런타임이 구현을 조립하므로 컴포넌트 수와 무관하게 바이트코드 크기가 일정하다. **패턴 스위치(JDK 21)** 의 `switch (obj) { case Integer i -> ... }` 는 `SwitchBootstraps.typeSwitch` indy 로 번역되어 타입 검사 체인을 런타임이 생성하며, enum 스위치 개선(`enumSwitch`)도 같은 계열이다. 공통 패턴은 "javac 는 의도만 기록하고, 최적 구현은 런타임이 결정한다"이다.

## 7. JIT 관점: 인라이닝, 탈최적화, 실측 성능

indy 호출 지점은 링크 후 `CallSite.target` 의 MethodHandle 체인으로 고정된다. `ConstantCallSite` 라면 C2 는 target 을 **컴파일 타임 상수**로 접어 체인 전체를 관통 인라이닝하므로, 워밍업이 끝난 비캡처 람다 호출은 인터페이스 구현이 단형(monomorphic)인 한 직접 호출과 동일한 기계어로 수렴한다. 반면 같은 `accept` 지점에 서로 다른 람다 클래스가 3개 이상 흘러들면 megamorphic 이 되어 인라이닝이 깨지는데, 이는 람다 고유 문제가 아니라 가상 호출 프로파일링의 일반 한계다.

`MutableCallSite.setTarget` 은 이 상수 폴딩과 충돌한다. JVM 은 target 을 인라이닝한 nmethod 에 의존성을 등록해 두고 setTarget 시 **탈최적화(deoptimize)** 후 재컴파일하므로, target 을 자주 바꾸는 설계는 성능 급락을 부른다. 동적 언어 런타임이 inline cache 를 MutableCallSite 로 구현하되 안정화 후 교체를 멈추는 이유다.

정상 상태(steady-state) 성능의 공개 실측 합의는 명확하다. Oracle 계열 JMH 벤치마크에서 비캡처 람다·익명 클래스·직접 호출은 워밍업 후 오차 범위 내 동일(수 ns/op)로 수렴하고, 캡처 람다는 할당이 escape analysis 로 제거되지 않을 때만 익명 클래스 수준의 할당 비용을 보인다. 차이가 실재하는 곳은 **콜드 스타트**다. JDK 8 시절 측정으로 익명 클래스 첫 로딩이 개당 수십~수백 µs 인 반면, 첫 람다는 인프라 초기화까지 묶여 수 ms, 이후 람다는 개당 수백 µs 수준이었다(하드웨어·JDK 버전에 따라 크게 달라지므로 자릿수 감각으로만 취한다). 수천 개 람다를 가진 애플리케이션의 기동 시간에 유의미해 Leyden/AOT·CDS 가 링크 상태를 미리 굽는 방향으로 대응 중이다. 실무 결론은 "핫 루프에서 람다를 익명 클래스로 되돌리는 최적화는 근거 없음, 기동 시간이 SLA 인 서버리스 환경에서만 람다 수·클래스 스핀을 의식하라"는 것이다.

## 8. 실습: javap 와 프록시 클래스 덤프로 내부 확인

컴파일 결과 확인 절차는 다음과 같다.

```bash
javac LambdaCompileDemo.java
javap -c -p -v LambdaCompileDemo.class
```

`-p` 가 있어야 private 합성 메서드 `lambda$...` 가 보이고, `-v` 를 붙이면 상수 풀과 함께 클래스 파일 하단의 **BootstrapMethods 속성**이 출력된다. 여기서 각 indy 가 참조하는 BSM(`LambdaMetafactory.metafactory`)과 정적 인자 3개(MethodType 2개, MethodHandle 1개)를 직접 대조할 수 있다.

```text
BootstrapMethods:
  0: #34 REF_invokeStatic java/lang/invoke/LambdaMetafactory.metafactory:(...)Ljava/lang/invoke/CallSite;
    Method arguments:
      #41 ()V                                  // samMethodType
      #42 REF_invokeStatic LambdaCompileDemo.lambda$makeStateless$0:()V
      #41 ()V                                  // instantiatedMethodType
```

런타임에 스핀되는 클래스 자체를 보려면 시스템 프로퍼티로 덤프를 켠다.

```bash
java -Djdk.internal.lambda.dumpProxyClasses=/tmp/lambdadump LambdaCompileDemo
javap -c -p /tmp/lambdadump/LambdaCompileDemo\$\$Lambda.0x*.class
```

덤프된 클래스에서 캡처 필드(`arg$1`), 생성자, 그리고 합성 메서드로 위임하는 `run()`/`get()` 구현을 확인할 수 있다(디렉터리는 미리 만들어 두어야 하며, 프로퍼티 이름은 JDK 버전에 따라 `jdk.invoke.LambdaMetafactory.dumpProxyClassFiles` 로 변경되었으니 사용 중인 JDK 릴리스 노트를 확인한다). 추가로 `-Xlog:class+load` 로 `$$Lambda` hidden class 의 로딩 시점을 관찰하면 3장의 지연 초기화와 비캡처 싱글턴 캐싱을 실측으로 검증할 수 있다. 문자열 결합의 경우 같은 소스라도 `javac --release 8` 로 컴파일하면 StringBuilder 체인이, 9 이상이면 `makeConcatWithConstants` indy 가 나오는 것을 비교해 보면 JEP 280 의 "번역 전략 분리" 철학이 손에 잡힌다.

## 참고

- JSR 292: Supporting Dynamically Typed Languages on the Java Platform — https://jcp.org/en/jsr/detail?id=292
- JEP 280: Indify String Concatenation — https://openjdk.org/jeps/280
- JEP 371: Hidden Classes — https://openjdk.org/jeps/371
- Brian Goetz, "Translation of Lambda Expressions" — https://cr.openjdk.org/~briangoetz/lambda/lambda-translation.html
- java.lang.invoke.LambdaMetafactory (Java SE API) — https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/LambdaMetafactory.html
- OpenJDK 소스: InnerClassLambdaMetafactory.java — https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/invoke/InnerClassLambdaMetafactory.java
- java.lang.runtime.SwitchBootstraps / ObjectMethods (Java SE API) — https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/runtime/package-summary.html
