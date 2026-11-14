Notion 원본: https://www.notion.so/4185a06fd6d381cfb685fbce72b68508

# Java HashMap 내부 구조: 해시 스프레딩, 리사이즈 분할, 트리화 임계값과 ConcurrentHashMap 의 CAS·빈 잠금 설계

> 2026-11-14 신규 주제 · 확장 대상: 자료구조&알고리즘(해시 테이블), JAVA(컬렉션 프레임워크)

## 학습 목표

- 리플렉션으로 지연 할당, lo·hi 리사이즈 분할, 트리화 시점을 관찰한다
- 같은 `hashCode` 키에서 `Comparable` 유무가 조회 시간에 주는 영향을 측정한다
- `ConcurrentHashMap` 의 `putVal` 소스에서 CAS 경로와 빈 잠금 경로를 구분한다

## 1. 분리 연쇄 테이블과 지연 할당

`HashMap` 은 `Node<K,V>[] table` 의 각 칸(빈, bin)에 노드를 연결 리스트로 매다는 분리 연쇄 해시 테이블이다. 용량이 항상 2의 거듭제곱이라 인덱스를 나머지 연산 대신 `(n - 1) & hash` 로 구한다. 생성자는 배열을 만들지 않고, 첫 `put` 에서 `resize()` 가 기본 용량 16 으로 할당한다. `threshold` 는 용량 × 부하율 0.75 이며 `++size > threshold` 이면 용량을 두 배로 늘린다. `Peek` 은 이후 예제가 공유하는 도우미이고, 실행 시 `--add-opens java.base/java.util=ALL-UNNAMED` 가 필요하다.

```java
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Peek {
	static Object read(Class<?> type, Object target, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	static String state(Map<?, ?> map) throws Exception {
		Object[] table = (Object[])read(HashMap.class, map, "table");
		return "cap=" + (table == null ? 0 : table.length) + " thr=" + read(HashMap.class, map, "threshold");
	}

	// 형식: 인덱스=[키...]헤드클래스
	static String bins(Map<?, ?> map) throws Exception {
		Object[] table = (Object[])read(HashMap.class, map, "table");
		Class<?> nodeType = Class.forName("java.util.HashMap$Node");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < table.length; i++) {
			List<Object> keys = new ArrayList<>();
			for (Object node = table[i]; node != null; node = read(nodeType, node, "next")) {
				keys.add(((Map.Entry<?, ?>)node).getKey());
			}
			if (!keys.isEmpty()) {
				out.append(i).append('=').append(keys).append(table[i].getClass().getSimpleName()).append(' ');
			}
		}
		return out.toString();
	}

	public static void main(String[] args) throws Exception {
		Map<Integer, Integer> map = new HashMap<>();
		System.out.println("new " + state(map));
		for (int i = 1; i <= 13; i++) {
			map.put(i, i);
			if (i == 1 || i >= 12) {
				System.out.println("put#" + i + " " + state(map));
			}
		}
	}
}
```

```text
new cap=0 thr=0
put#1 cap=16 thr=12
put#12 cap=16 thr=12
put#13 cap=32 thr=24
```

부하율을 낮추면 충돌은 줄지만 빈 칸이 늘고, 높이면 그 반대다.

## 2. hash() 의 상위 16비트 XOR 스프레딩

마스크 `(n - 1)` 은 하위 비트만 남기므로, 상위 비트만 다른 해시들은 전부 같은 빈에 떨어진다. 그래서 `hash()` 는 `h ^ (h >>> 16)` 으로 상위 16비트를 하위에 섞는다. 소스 주석이 예로 든 연속 정수값 `Float` 키 48개(1.0~48.0)가 용량 64 와 2^20 에서 차지하는 빈의 수를 센다.

```java
import java.util.HashSet;
import java.util.Set;

public class Spread {
	static int usedBuckets(int capacity, boolean spread) {
		Set<Integer> used = new HashSet<>();
		for (int i = 1; i <= 48; i++) {
			int h = Float.valueOf(i).hashCode();
			used.add((capacity - 1) & (spread ? h ^ (h >>> 16) : h));
		}
		return used.size();
	}

	public static void main(String[] args) {
		System.out.println("raw=" + usedBuckets(64, false) + " spread=" + usedBuckets(64, true));
		System.out.println("raw=" + usedBuckets(1 << 20, false) + " spread=" + usedBuckets(1 << 20, true));
	}
}
```

```text
raw=1 spread=16
raw=4 spread=48
```

스프레딩이 없으면 용량 64 에서 48개가 빈 하나에 몰리고, 2^20 이어도 4개 빈만 쓴다. XOR 한 번으로 16개, 48개까지 펼쳐지지만 작은 테이블에는 충돌이 남는다. 분산 품질보다 속도를 택한 변환이고, 남는 충돌은 트리 빈이 맡는다.

## 3. 리사이즈: hash & oldCap 으로 lo·hi 분할

용량이 두 배가 되면 마스크에 비트 하나가 추가될 뿐이다. 그래서 인덱스 `j` 의 노드는 `(e.hash & oldCap) == 0` 이면 `j` 에 남고(lo 리스트), 아니면 `j + oldCap` 으로 옮겨진다(hi 리스트). `hashCode()` 를 다시 호출하지 않고 저장된 `hash` 만 본다.

```java
import java.util.HashMap;
import java.util.Map;

public class Split {
	public static void main(String[] args) throws Exception {
		Map<Integer, String> map = new HashMap<>();
		for (int key : new int[] {53, 37, 21, 5}) {
			map.put(key, "v");
		}
		System.out.println(Peek.state(map) + " | " + Peek.bins(map) + "| " + map.keySet());
		for (int key = 100; key < 148; key++) {
			String before = Peek.state(map);
			map.put(key, "filler");
			if (!before.equals(Peek.state(map))) {
				map.keySet().removeIf(k -> k >= 100);
				System.out.println(Peek.state(map) + " | " + Peek.bins(map) + "| " + map.keySet());
			}
		}
	}
}
```

```text
cap=16 thr=12 | 5=[53, 37, 21, 5]Node | [53, 37, 21, 5]
cap=32 thr=24 | 5=[37, 5]Node 21=[53, 21]Node | [37, 5, 53, 21]
cap=64 thr=48 | 5=[5]Node 21=[21]Node 37=[37]Node 53=[53]Node | [5, 21, 37, 53]
```

53 과 21 은 16 비트가 켜져 있어 `5 + 16 = 21` 로 이동했다. 마지막 열처럼 같은 내용의 맵도 용량에 따라 `keySet()` 순서가 달라진다. 재해싱은 없어도 전체 노드를 옮기는 O(n) 작업이라 그 `put` 한 번은 느려진다.

## 4. 트리화 임계값과 레드-블랙 트리 빈

`putVal` 은 노드가 이미 8개(`TREEIFY_THRESHOLD`)인 빈에 하나를 더 붙일 때 `treeifyBin` 을 호출한다. 단 테이블 길이가 64(`MIN_TREEIFY_CAPACITY`) 미만이면 트리로 바꾸지 않고 `resize()` 를 한다.

```java
import java.util.HashMap;
import java.util.Map;

public class Treeify {
	record SameHash(int id) {
		@Override
		public int hashCode() {
			return 7;
		}
	}

	public static void main(String[] args) throws Exception {
		Map<SameHash, Integer> map = new HashMap<>();
		for (int i = 1; i <= 11; i++) {
			map.put(new SameHash(i), i);
			if (i >= 8) {
				System.out.println("put#" + i + " " + Peek.state(map) + " " + Peek.bins(map).replaceAll(".*]", ""));
			}
		}
		for (int i = 11; i >= 3; i--) {
			map.remove(new SameHash(i));
			if (i <= 5) {
				System.out.println("size=" + map.size() + " " + Peek.bins(map).replaceAll(".*]", ""));
			}
		}
	}
}
```

```text
put#8 cap=16 thr=12 Node
put#9 cap=32 thr=24 Node
put#10 cap=64 thr=48 Node
put#11 cap=64 thr=48 TreeNode
size=4 TreeNode
size=3 Node
size=2 Node
```

9·10번째 삽입은 리사이즈로 끝났고 용량이 64 가 된 뒤인 11번째에 `TreeNode` 가 됐다. `UNTREEIFY_THRESHOLD` 6 은 리사이즈 중 `TreeNode.split` 이 나눈 쪽의 노드 수가 6 이하일 때 적용된다. 삭제 경로(`removeTreeNode`)는 개수가 아니라 루트 주변 자식이 비었는지를 봐서, 이 삭제 순서에서는 3개가 되어서야 리스트로 돌아갔다. 소스 주석에 따르면 `TreeNode` 는 일반 노드의 약 두 배 크기이고, 해시가 무작위이면 빈의 노드 수는 평균 약 0.5 의 포아송 분포를 따라 8개일 확률이 0.00000006 이다. 트리 빈은 비정상 충돌용 방어선이다.

## 5. Comparable 키, tieBreakOrder, 해시 충돌 공격

트리 빈은 `hash` 로, 같으면 키가 `class C implements Comparable<C>` 형태일 때 `compareTo` 로 정렬한다. 그렇지 않으면 삽입 시 `tieBreakOrder` 가 클래스 이름과 `System.identityHashCode` 로 방향만 정하는데, 조회에는 쓸 수 없어 `find` 가 양쪽 서브트리를 뒤진다. 모든 키의 `hashCode` 가 같을 때 2만 건 조회 시간을 잰다.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

public class BadHash {
	record Plain(int id) {
		@Override
		public int hashCode() {
			return 42;
		}
	}

	record Ordered(int id) implements Comparable<Ordered> {
		@Override
		public int hashCode() {
			return 42;
		}

		@Override
		public int compareTo(Ordered other) {
			return Integer.compare(id, other.id);
		}
	}

	static long lookupMillis(IntFunction<Object> factory) {
		Map<Object, Integer> map = new HashMap<>();
		for (int i = 0; i < 20_000; i++) {
			map.put(factory.apply(i), i);
		}
		long start = System.nanoTime();
		for (int i = 0; i < 20_000; i++) {
			if (map.get(factory.apply(i)) != i) {
				throw new IllegalStateException();
			}
		}
		return (System.nanoTime() - start) / 1_000_000;
	}

	public static void main(String[] args) {
		for (int round = 1; round <= 3; round++) {
			System.out.println("plain=" + lookupMillis(Plain::new) + "ms comparable=" + lookupMillis(Ordered::new) + "ms");
		}
	}
}
```

```text
plain=2574ms comparable=247ms
plain=2738ms comparable=9ms
plain=3137ms comparable=3ms
```

이 환경에서의 1회 측정 예이고, 워밍업과 GC 를 통제하지 않은 단순 `System.nanoTime` 측정이라 절대값이 아닌 경향만 읽는다. 세 라운드 모두 `Comparable` 이 없는 쪽이 훨씬 느렸다. 같은 해시의 키를 대량으로 보내 CPU 를 소모시키는 해시 충돌 공격(HashDoS)을 완화하는 것이 이 구조이고, JDK 8 의 JEP 180 은 높은 충돌 조건의 최악 성능을 O(n) 에서 O(log n) 으로 개선한다고 적는다. 단 키가 `Comparable` 일 때의 이야기다.

## 6. 가변 키와 초기 용량 지정

노드는 삽입 시점의 `hash` 를 저장한다. `put` 뒤에 키의 `hashCode` 가 바뀌면 조회는 다른 빈을 찾아가므로 엔트리는 남아 있는데 찾지 못한다. 한편 `new HashMap<>(n)` 의 인자는 건수가 아니라 용량이다.

```java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KeyAndSize {
	public static void main(String[] args) throws Exception {
		List<String> key = new ArrayList<>(List.of("a"));
		Map<List<String>, String> map = new HashMap<>();
		map.put(key, "value");
		key.add("b");
		System.out.println(map.get(key) + " " + map.containsKey(key) + " " + map.size());
		key.remove("b");
		System.out.println(map.get(key));

		Map<Integer, Integer> byCtor = new HashMap<>(100);
		Map<Integer, Integer> byFactory = HashMap.newHashMap(100);
		for (int i = 1; i <= 97; i++) {
			byCtor.put(i, i);
			byFactory.put(i, i);
			if (i >= 96) {
				System.out.println("#" + i + " ctor " + Peek.state(byCtor) + " | factory " + Peek.state(byFactory));
			}
		}
	}
}
```

```text
null false 1
value
#96 ctor cap=128 thr=96 | factory cap=256 thr=192
#97 ctor cap=256 thr=192 | factory cap=256 thr=192
```

키를 되돌리면 다시 조회되므로 데이터가 사라진 것은 아니다. 키는 불변 타입이 안전하다. 생성자는 100 을 `tableSizeFor` 로 128 로 올려 용량으로 쓰고, 임계값이 96 이라 97번째에서 리사이즈한다. 리사이즈 없이 담으려면 `(int) Math.ceil(n / 0.75)` 를 넘겨야 하며, JDK 19 의 `HashMap.newHashMap(int)` 가 이 계산을 대신한다. 대가는 더 큰 배열이다.

## 7. 순회와 fail-fast modCount

구조 변경마다 `modCount` 가 증가하고, 반복자는 생성 시점의 값을 `expectedModCount` 로 기억했다가 다르면 `ConcurrentModificationException` 을 던진다. 단일 스레드에서도 발생한다.

```java
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;

public class FailFast {
	public static void main(String[] args) {
		Map<String, Integer> map = new HashMap<>(Map.of("a", 1, "b", 2, "c", 3));
		try {
			for (String key : map.keySet()) {
				map.remove("c");
			}
		} catch (ConcurrentModificationException e) {
			System.out.println("for-each remove: " + e.getClass().getSimpleName());
		}
		map.values().removeIf(value -> value == 2);
		System.out.println(map);
	}
}
```

```text
for-each remove: ConcurrentModificationException
{a=1}
```

순회 중 삭제는 `Iterator.remove` 나 `removeIf` 로 한다. 예외가 났어도 `remove("c")` 는 이미 반영됐다. fail-fast 는 사후 감지일 뿐이고, Javadoc 도 best-effort 이므로 버그 탐지에만 쓰라고 명시한다.

## 8. ConcurrentHashMap: 빈 bin 은 CAS, 충돌은 빈 헤드 잠금

`ConcurrentHashMap.putVal` 은 네 갈래다. 테이블이 없으면 `initTable()` 에서 `sizeCtl` 을 CAS 로 -1 로 바꾼 스레드만 할당한다. 대상 빈이 비어 있으면 `casTabAt` 으로 잠금 없이 넣는다. 헤드의 `hash` 가 `MOVED`(-1) 이면 `ForwardingNode` 이므로 `helpTransfer` 로 리사이즈를 돕는다. 그 밖에는 `synchronized (f)` 로 빈의 헤드 노드만 잠그고 수정하므로, 다른 빈에 대한 쓰기는 서로 막지 않는다.

리사이즈(`transfer`)는 여러 스레드가 구간을 나눠 함께 수행한다. 각 빈을 잠근 뒤 `hash & n` 으로 두 리스트로 나눠 `nextTable` 에 옮기고 옛 자리에 `ForwardingNode` 를 둔다. `sizeCtl` 은 음수면 초기화·리사이즈 진행 중, 초기화 뒤에는 다음 리사이즈 기준 원소 수(`n - (n >>> 2)`)다. 원소 수는 `baseCount` 에 CAS 로 더하다가 실패하면 `CounterCell` 배열로 분산하며, `size()` 는 이를 잠금 없이 합산한 근사치다.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class Chm {
	static int fill(Map<Integer, Integer> map) {
		IntStream.range(0, 800_000).parallel().forEach(i -> map.put(i, i));
		return map.size();
	}

	public static void main(String[] args) {
		ConcurrentHashMap<String, Integer> chm = new ConcurrentHashMap<>();
		try {
			chm.computeIfAbsent("a", key -> chm.computeIfAbsent("a", inner -> 1));
		} catch (IllegalStateException e) {
			System.out.println("same key: " + e.getMessage());
		}
		System.out.println("ConcurrentHashMap size=" + fill(new ConcurrentHashMap<>()));
		System.out.println("HashMap size=" + fill(new HashMap<>()));
	}
}
```

```text
same key: Recursive update
ConcurrentHashMap size=800000
HashMap size=775052
```

`putVal` 첫 줄은 null 키·값에 `NullPointerException` 을 던진다. 소스에 이유는 없지만, null 을 허용하면 `get` 의 null 이 "없음"인지 "null 값"인지 가리려고 `containsKey` 를 더 불러야 하고 그 사이 다른 스레드가 맵을 바꿀 수 있다. `computeIfAbsent` 는 빈 bin 에 `ReservationNode` 를 걸고 함수를 실행하므로 같은 키를 재귀 갱신하면 `IllegalStateException` 이 난다. Javadoc 은 매핑 함수가 이 맵을 수정하지 말고 짧아야 한다고 요구한다.

## 9. 멀티스레드에서의 HashMap 과 선택 기준

`HashMap` 은 `++size`, `tab[i] = newNode(...)`, `resize()` 어디에도 동기화가 없다. 8절에서 병렬 스트림으로 서로 다른 키 80만 개를 넣었을 때 `HashMap` 의 `size()` 는 5회 실행에서 800000, 800000, 775052(위 출력), 799993, 800000 이었다. 유실이 매번 재현되지는 않으므로 테스트 통과가 안전을 뜻하지 않는다.

| 상황 | 선택 | 비용 |
| --- | --- | --- |
| 한 스레드만 접근 | `HashMap` | 순회 순서 미보장 |
| 삽입 순서 유지 | `LinkedHashMap` | 노드마다 앞뒤 링크 |
| 키 정렬·범위 조회 | `TreeMap` | 연산 O(log n) |
| 여러 스레드가 읽고 씀 | `ConcurrentHashMap` | null 금지, `size()` 근사 |

`ConcurrentHashMap` 에서도 `get` 후 `put` 은 원자적이지 않으므로 `compute`·`merge` 한 번으로 표현한다.

모든 예제는 OpenJDK 21.0.12.1(Ubuntu, 프로세서 2개)에서 `javac -Xlint:all -d out *.java` 로 경고 없이 컴파일하고 `java --add-opens java.base/java.util=ALL-UNNAMED -cp out <클래스>` 로 실행해 출력을 그대로 옮겼다. 5절의 시간과 `HashMap` 의 크기는 실행마다 달라진다. 내부 구현은 이 환경에 `src.zip` 이 없어 openjdk/jdk 의 `jdk-21-ga` 태그 소스로 확인했고, 실행한 빌드의 소스와 대조하지는 않았다.

## 참고

- OpenJDK HashMap 소스: https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/share/classes/java/util/HashMap.java
- OpenJDK ConcurrentHashMap 소스: https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java
- JEP 180: https://openjdk.org/jeps/180
- HashMap Javadoc: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html
- ConcurrentHashMap Javadoc: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
