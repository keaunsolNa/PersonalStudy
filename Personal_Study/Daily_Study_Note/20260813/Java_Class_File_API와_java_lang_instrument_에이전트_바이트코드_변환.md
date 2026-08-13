Notion 원본: https://app.notion.com/p/3bb5a06fd6d381748397c26d9ef111d5?pvs=204

# Java Class-File API와 java.lang.instrument 에이전트 바이트코드 변환

> 2026-08-13 신규 주제 · 확장 대상: JAVA

## 학습 목표

- `ClassFile.of()` 로 클래스를 파싱하고 `CodeBuilder` 로 메서드 바이트코드를 생성한다.
- `ClassTransform`·`CodeTransform` 을 합성해 기존 메서드에 진입·종료 계측을 삽입한다.
- `premain`·`ClassFileTransformer` 로 에이전트를 빌드하고 `-javaagent` 로 기동한다.
- StackMapTable 재계산 실패와 `retransformClasses` 의 스키마 제약을 진단한다.
## 1. Class-File API 의 배경과 ASM 대비 모델 차이

JDK 는 `com.sun.tools.classfile` 과 내부 fork 된 ASM 등 클래스 파일 라이브러리를 중복 유지해 왔다. 포맷이 6개월마다 바뀔 때 내부 복사본과 외부 ASM 이 각각 따라잡아야 했고, 그 시차 동안 사용자 빌드에서 `Unsupported class file major version` 이 터졌다. Class-File API 는 JDK 가 포맷과 함께 진화하는 표준 API 를 직접 제공해 이 시차를 없앤다. 프리뷰로 도입되어 최근 JDK 에서 최종화되었고 패키지는 `java.lang.classfile` 이다. ASM 이 방문자 콜백 스트림인 반면 이쪽은 sealed 인터페이스와 레코드로 된 불변 요소 트리라, 패턴 매칭으로 분해되고 자유롭게 재사용된다.

| 관점 | ASM | Class-File API |
| --- | --- | --- |
| 모델 | 방문자 콜백 스트림 | 불변 요소 트리 + 빌더 |
| 탐색 | 1회성, 되감기 불가 | 재탐색 자유 |
| 타입 표현 | descriptor 문자열 | `ClassDesc`, `MethodTypeDesc` |
| 의존성 | 외부 jar(shade 필요) | JDK 내장, 0 |
| 생태계 | 20년 축적 | 신규, 서드파티 부족 |

ByteBuddy·CGLIB 위의 기존 코드를 갈아엎을 이유는 없다. 새 빌드 툴·정적 분석기·경량 에이전트이고 최소 JDK 가 24 이상이면 shading 비용이 사라지는 것만으로 유리하다.

## 2. 파싱: ClassModel 탐색과 패턴 매칭

`ClassFile.of()` 가 컨텍스트를 만들고 `parse(byte[])` 가 `ClassModel` 을 돌려준다. 즉시 역직렬화하지 않고 지연 처리하므로 본문을 안 건드리는 분석은 비용이 낮다.

```java
package study.classfile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

public class MethodCallScanner {

	public Set<String> scanInvokedMethods(Path classFilePath) throws Exception {
		ClassModel classModel = ClassFile.of().parse(Files.readAllBytes(classFilePath));
		Set<String> invoked = new TreeSet<>();

		for (MethodModel method : classModel.methods()) {
			method.code().ifPresent(code -> {
				for (var element : code) {
					if (element instanceof InvokeInstruction invoke) {
						invoked.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
					}
				}
			});
		}
		return invoked;
	}
}
```

ASM 은 `visitMethodInsn` 의 인자를 문자열로 조합해야 했다. 여기서는 명목 서술자를 쓰므로 오타가 런타임 검증기가 아니라 컴파일 시점에 걸린다.

## 3. 생성: ClassBuilder 와 CodeBuilder

`ClassFile.of().build(ClassDesc, Consumer<ClassBuilder>)` 가 진입점이고, `CodeBuilder` 는 저수준 opcode 와 `loadConstant`·`return_` 헬퍼를 함께 제공한다.

```java
package study.classfile;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;

public class HelloClassGenerator {

	private static final ClassDesc CD_SYSTEM = ClassDesc.of("java.lang.System");
	private static final ClassDesc CD_PRINT_STREAM = ClassDesc.of("java.io.PrintStream");
	private static final ClassDesc CD_STRING = ClassDesc.of("java.lang.String");
	private static final ClassDesc CD_OBJECT = ClassDesc.of("java.lang.Object");
	private static final MethodTypeDesc MTD_VOID = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"));

	public byte[] generateHelloRunnable(String message) {
		ClassDesc target = ClassDesc.of("study.generated.HelloRunnable");

		return ClassFile.of().build(target, classBuilder -> {
			classBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SUPER);
			classBuilder.withInterfaceSymbols(ClassDesc.of("java.lang.Runnable"));

			classBuilder.withMethodBody("<init>", MTD_VOID, ClassFile.ACC_PUBLIC, code -> {
				code.aload(0);
				code.invokespecial(CD_OBJECT, "<init>", MTD_VOID);
				code.return_();
			});

			classBuilder.withMethodBody("run", MTD_VOID, ClassFile.ACC_PUBLIC, code -> {
				code.getstatic(CD_SYSTEM, "out", CD_PRINT_STREAM);
				code.loadConstant(message);
				code.invokevirtual(CD_PRINT_STREAM, "println",
						MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), CD_STRING));
				code.return_();
			});
		});
	}
}
```

빌더가 스택 맵을 자동 생성하므로 ASM 의 `COMPUTE_FRAMES` 관용구가 필요 없다. 다만 참조 타입 병합 시 공통 상위 타입 계산이 시스템 클래스로더 기준이라, 에이전트가 애플리케이션 클래스로더의 타입을 다루면 실패한다. `ClassHierarchyResolver` 를 직접 넘긴다(7절).

## 4. 변환: ClassTransform·CodeTransform 합성

핵심 추상은 요소를 받아 0개 이상을 내보내는 함수다. 조작이 없으면 `builder.with(element)` 로 흘려보내고 `andThen` 으로 잇는다. `transformingMethodBodies` 가 코드 변환을 클래스 변환으로 승격시킨다.

```java
public class TimingInstrumentor {

	private static final ClassDesc CD_PROBE = ClassDesc.of("study.agent.TimingProbe");
	private static final MethodTypeDesc MTD_HOOK = MethodTypeDesc.of(
			ClassDesc.ofDescriptor("V"), ClassDesc.of("java.lang.String"));

	public byte[] instrument(byte[] original, String displayName) {
		ClassFile classFile = ClassFile.of();
		ClassTransform classTransform = ClassTransform.transformingMethodBodies(
				method -> !"<init>".equals(method.methodName().stringValue()),
				createCodeTransform(displayName));

		return classFile.transformClass(classFile.parse(original), classTransform);
	}

	private CodeTransform createCodeTransform(String displayName) {
		return new CodeTransform() {

			@Override
			public void atStart(CodeBuilder builder) {
				builder.loadConstant(displayName);
				builder.invokestatic(CD_PROBE, "enter", MTD_HOOK);
			}

			@Override
			public void accept(CodeBuilder builder, CodeElement element) {
				if (element instanceof ReturnInstruction returnInstruction) {
					builder.loadConstant(displayName);
					builder.invokestatic(CD_PROBE, "exit", MTD_HOOK);
					builder.with(returnInstruction);
					return;
				}
				builder.with(element);
			}
		};
	}
}
```

함정이 둘이다. `<init>` 은 `super()` 호출 전 `this` 가 `uninitializedThis` 라 코드를 넣으면 검증기가 거부하므로 위처럼 제외한다. 또 `return` 지점에만 훅을 넣으면 예외 경로가 누락되므로, 본문을 `try`/`catch(Throwable)` 로 감싸 핸들러에서도 `exit` 후 `athrow` 로 재던져야 하고 이때 예외 슬롯이 늘어 `maxLocals` 가 커진다.

## 5. 에이전트 골격: premain, agentmain, ClassFileTransformer

`premain` 은 `-javaagent` 로 로드되어 `main` 보다 먼저 실행되고, `agentmain` 은 Attach API 로 붙을 때 호출된다.

```java
public class TimingAgent {

	public static void premain(String agentArgs, Instrumentation instrumentation) {
		installTransformer(agentArgs, instrumentation);
	}

	public static void agentmain(String agentArgs, Instrumentation instrumentation) {
		installTransformer(agentArgs, instrumentation);
	}

	private static void installTransformer(String agentArgs, Instrumentation instrumentation) {
		String prefix = (agentArgs == null || agentArgs.isBlank()) ? "study/" : agentArgs.replace('.', '/');
		instrumentation.addTransformer(new TimingTransformer(prefix), true);
	}

	static class TimingTransformer implements ClassFileTransformer {

		private final String packagePrefix;
		private final TimingInstrumentor instrumentor = new TimingInstrumentor();

		TimingTransformer(String packagePrefix) {
			this.packagePrefix = packagePrefix;
		}

		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			if (loader == null || className == null || !className.startsWith(packagePrefix)) {
				return null;
			}
			try {
				return instrumentor.instrument(classfileBuffer, className.replace('/', '.'));
			} catch (Throwable t) {
				System.err.println("[agent] transform failed: " + className + " - " + t);
				return null;
			}
		}
	}
}
```

변경이 없으면 반드시 `null` 을 반환한다. 예외를 던지면 JVM 이 로그로 삼키고 원본을 쓰지만 예외 생성 비용이 모든 클래스 로딩에 붙는다. `loader == null` 은 부트스트랩이 로드하는 코어 클래스라, `java.lang.String` 을 계측하면 String 정의 → 프로브 로드 → 다시 String 의 순환으로 JVM 이 기동 중 죽는다. MANIFEST 는 콜론 뒤 공백이나 끝 개행이 빠지면 파싱이 조용히 실패한다.

```
Manifest-Version: 1.0
Premain-Class: study.agent.TimingAgent
Agent-Class: study.agent.TimingAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

```bash
javac --release 24 -d out $(find src -name '*.java')
jar --create --file timing-agent.jar --manifest src/META-INF/MANIFEST.MF -C out .

java -javaagent:timing-agent.jar=study/service -jar app.jar
java -javaagent:timing-agent.jar -Xlog:class+load=info -jar app.jar
```

## 6. retransformClasses 의 제약과 Instrumentation 부가 기능

`retransformClasses(Class<?>...)` 는 원본 바이트를 트랜스포머 체인에 다시 흘려 보내며, 이때 `classBeingRedefined` 가 `null` 이 아니다. 문제는 HotSpot 이 허용하는 변경 범위가 좁다는 점이다.

| 변경 종류 | 허용 여부 |
| --- | --- |
| 메서드 본문 교체 | 허용 |
| 상수 풀 엔트리 추가 | 허용 |
| 메서드·필드 추가·삭제 | 불가 |
| 시그니처·접근 제어자 변경 | 불가 |
| 상위 클래스·인터페이스 변경 | 불가 |

스키마는 못 바꾸고 본문만 바꾼다는 규칙이라, 위반하면 `class redefinition failed: attempted to add a method` 로 실패한다. APM 에이전트가 계측 상태를 클래스 필드에 못 심는 이유가 이것이라 실무에서는 `WeakHashMap` 사이드 테이블이나 `ThreadLocal` 을 쓴다. 나중에 attach 할 계획이면 `premain` 때 필드·메서드를 미리 삽입하고 런타임에는 본문만 켜고 끄는 편이 안전하다. 한편 `getObjectSize` 는 얕은 크기만 돌려주며, 압축 OOP 가 켜진 64비트 기본값에서 빈 `Object` 와 `Integer` 는 16바이트, `int[10]` 은 헤더 16 + 40 = 56바이트다.

```java
public class ObjectSizer {

	private static volatile Instrumentation instrumentation;

	public static void premain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	public static long measureShallowSize(Object target) {
		if (instrumentation == null) {
			throw new IllegalStateException("agent not installed: use -javaagent");
		}
		return instrumentation.getObjectSize(target);
	}

	public static void printComparison() {
		System.out.println("Object  = " + measureShallowSize(new Object()));
		System.out.println("Integer = " + measureShallowSize(Integer.valueOf(1_000_000)));
		System.out.println("int[10] = " + measureShallowSize(new int[10]));
	}
}
```

`-XX:-UseCompressedOops` 로 끄면 헤더가 커져 값이 달라진다. 깊은 크기는 리플렉션으로 필드를 훑어야 하는데 `java.base` 내부 클래스에서 `InaccessibleObjectException` 이 나므로 JOL 이 정확하다.

## 7. StackMapTable, 검증기, 클래스로더·모듈 상호작용

메이저 버전 51(Java 7) 이상은 타입 추론 폴백이 없어 `StackMapTable` 프레임이 틀리면 무조건 `VerifyError` 다. 코드를 삽입하면 분기 오프셋이 밀리고 스택 높이가 달라져 프레임을 재계산해야 하는데, 참조 타입 병합에 계층 조회가 필요하므로 대상 클래스로더를 아는 해석기를 넘긴다.

```java
public class ResolverFactory {

	public static ClassFile createClassFile(ClassLoader loader) {
		ClassHierarchyResolver resolver = ClassHierarchyResolver
				.ofResourceParsing(classDesc -> openClassResource(loader, classDesc))
				.cached();
		return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
	}

	private static InputStream openClassResource(ClassLoader loader, ClassDesc classDesc) {
		ClassLoader effective = (loader != null) ? loader : ClassLoader.getPlatformClassLoader();
		String descriptor = classDesc.descriptorString();
		return effective.getResourceAsStream(descriptor.substring(1, descriptor.length() - 1) + ".class");
	}
}
```

`.cached()` 를 빼면 병합마다 리소스를 다시 읽어 수천 클래스 계측 시 수백 밀리초가 날아간다. 검증 실패는 `Expecting a stackmap frame at branch target N`(프레임 누락), `Bad type on operand stack`(push 한 값 미정리), `Inconsistent stackmap frames`(병합 타입 불일치) 세 형태다. `-Xverify:none` 은 deprecate 되어 무시되므로 `StackMapsOption.DROP_STACK_MAPS` 로 범위를 좁히거나 결과를 덤프해 `javap -v -p` 로 읽는 편이 빠르다. 프로브 jar 가 클래스패스에 없으면 `NoClassDefFoundError` 가 나므로 `appendToBootstrapClassLoaderSearch` 로 올리되, 모듈 경로에서는 읽기 관계가 없어 `IllegalAccessError` 가 난다.

```java
public void openModuleForProbe(Instrumentation instrumentation, Module target, Module agent) {
	if (!target.canRead(agent)) {
		instrumentation.redefineModule(target, java.util.Set.of(agent),
				java.util.Map.of(), java.util.Map.of(), java.util.Set.of(), java.util.Map.of());
	}
}
```

## 8. 운영 트레이드오프: 기동 시간, CDS, JIT 인라이닝

비용은 세 층위다. 첫째 로드 타임 변환으로, 모든 클래스가 `transform` 을 통과하므로 클래스 1만 개를 전부 파싱하면 기동 시간에 수백 밀리초~1~2초가 더해지지만 접두어 필터로 수백 개만 남기면 수십 밀리초로 떨어진다. 둘째 AppCDS 충돌로, 공유 아카이브는 클래스가 로드 시점에 안 바뀐다는 전제에 기대므로 변환된 클래스는 개별 로드로 폴백해 CDS 이득이 사라진다. 셋째 JIT 영향으로, C2 의 인라이닝 한계는 `MaxInlineSize` 35바이트·`FreqInlineSize` 325바이트라 프로브를 넣어 30바이트 접근자가 60바이트가 되면 인라이닝에서 탈락하고 상위 호출부의 최적화 연쇄까지 무너진다.

| 전략 | 기동 오버헤드 | 런타임 오버헤드 | 적용 |
| --- | --- | --- | --- |
| 전 클래스 계측 | 매우 큼 | 매우 큼 | 지양 |
| 접두어 필터 + 진입/종료 | 작음 | 중간 | 일반 APM |
| 프레임워크 경계만 계측 | 매우 작음 | 작음 | 권장 |
| 컴파일 타임 위빙 | 없음 | 작음 | 소스 소유 |
| JFR 샘플링 | 없음 | 매우 작음 | 프로파일링 |

프로브는 짧은 `static` 으로 두고 `if (!enabled) { return; }` 가드를 넣어 비활성 시 JIT 가 통째로 제거하게 한다. `System.nanoTime()` 은 호출당 20~30나노초가 드니 초당 수백만 회 경로에는 넣지 말고 샘플링하거나 카운터만 센다. 투입 전 계측 없는 기준선과 JMH 로 재고 `-XX:+PrintInlining` 으로 탈락을 확인한다.

## 참고

- [JEP 484: Class-File API](https://openjdk.org/jeps/484)
- [JEP 457: Class-File API (Preview)](https://openjdk.org/jeps/457)
- [java.lang.classfile javadoc](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/classfile/package-summary.html)
- [java.lang.instrument javadoc](https://docs.oracle.com/en/java/javase/24/docs/api/java.instrument/java/lang/instrument/package-summary.html)
- [JVMS Chapter 4: class File Format](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)
- [ASM User Guide (OW2)](https://asm.ow2.io/asm4-guide.pdf)
- [Byte Buddy Documentation](https://bytebuddy.net/#/tutorial)
- [JOL](https://openjdk.org/projects/code-tools/jol/)
- [JEP 341: Default CDS Archives](https://openjdk.org/jeps/341)
