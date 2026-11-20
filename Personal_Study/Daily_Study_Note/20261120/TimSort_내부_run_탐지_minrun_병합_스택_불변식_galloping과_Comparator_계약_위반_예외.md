Notion 원본: https://www.notion.so/41e5a06fd6d381ffa0eaf0c085f216f1

# TimSort 내부: run 탐지, minrun, 병합 스택 불변식, galloping 과 Comparator 계약 위반 예외

> 2026-11-20 신규 주제 · 확장 대상: 자료구조&알고리즘(병합 정렬·삽입 정렬), JAVA(Comparator·Collections.sort)

## 학습 목표

- 입력에서 자연 run 을 찾아 내는 규칙과 minrun 계산식을 직접 구현해 값으로 확인한다
- 입력 형태별 비교 횟수를 계측해 TimSort 가 정렬된 구간을 이용하는 정도를 수치로 비교한다
- 병합 스택 불변식과 galloping 이 각각 무엇을 보장하는지 설명 가능한 수준으로 정리한다
- `Comparison method violates its general contract!` 예외를 세 가지 비교자로 재현하고 발생 조건을 구분한다

## 1. 어디에 쓰이는가

Java 7 부터 `Arrays.sort(Object[])`, `Arrays.sort(T[], Comparator)`, `Collections.sort`, `List.sort` 는 TimSort 로 동작한다. `ArrayList.sort` 는 내부 배열에 직접 `Arrays.sort` 를 호출하고, 그 밖의 `List` 기본 구현은 배열로 복사해 정렬한 뒤 다시 써 넣는다. 원시 타입 배열(`int[]` 등)은 Dual-Pivot Quicksort 를 쓴다. 이렇게 나뉜 이유는 안정성이다. 원시 값은 같은 값끼리 구분할 수 없어 안정성이 의미가 없지만, 객체는 비교 키가 같아도 서로 다른 객체이므로 원래 순서를 유지해야 다단계 정렬이 성립한다.

```java
import java.util.*;

public class Stable {
	record Order(String customer, int amount, int seq) { }

	public static void main(String[] args) {
		List<Order> orders = new ArrayList<>();
		String[] names = {"kim", "lee", "park"};
		Random rnd = new Random(7);
		for (int i = 0; i < 9; i++) {
			orders.add(new Order(names[rnd.nextInt(3)], (rnd.nextInt(3) + 1) * 100, i));
		}
		orders.sort(Comparator.comparingInt(Order::amount).reversed());
		orders.sort(Comparator.comparing(Order::customer));
		orders.forEach(o -> System.out.println(o.customer() + " " + o.amount() + " #" + o.seq()));
	}
}
```

```text
kim 200 #1
kim 200 #6
kim 100 #4
kim 100 #5
kim 100 #8
lee 300 #0
lee 300 #3
lee 200 #2
lee 200 #7
```

금액 내림차순으로 먼저 정렬하고 고객명으로 다시 정렬했다. 두 번째 정렬이 안정적이어서 같은 고객 안에서 금액 내림차순이 유지되고, 금액까지 같은 행은 원래 삽입 순서(`#` 번호)대로 남는다. 화면의 열 머리글을 차례로 눌러 정렬하는 기능이 기대대로 동작하는 근거다.

TimSort 는 Tim Peters 가 2002 년 CPython 의 `list.sort` 용으로 설계한 알고리즘이고, 병합 정렬에 삽입 정렬을 섞은 형태다. 설계 전제는 실제 데이터에는 이미 정렬된 구간이 흔하다는 관찰이다. 최악 O(n log n) 을 유지하면서, 정렬된 구간이 많을수록 비교 횟수가 n 에 가까워진다.

## 2. run 탐지와 minrun

정렬은 배열을 왼쪽부터 훑으며 run 을 찾는 것으로 시작한다. run 은 비내림차순(`a[i] <= a[i+1]`)이거나 엄격한 내림차순(`a[i] > a[i+1]`)인 연속 구간이다. 내림차순 run 은 그 자리에서 뒤집어 오름차순으로 만든다. 내림차순 쪽만 엄격한 부등호를 쓰는 이유는 안정성이다. 같은 값이 포함된 구간을 뒤집으면 같은 값끼리의 순서가 바뀐다.

run 이 너무 짧으면 병합 횟수가 늘어나므로 최소 길이 minrun 을 정하고, 그보다 짧은 run 은 이진 삽입 정렬로 minrun 까지 늘린다. 배열 길이가 32 미만이면 병합 없이 이진 삽입 정렬만으로 끝낸다. 아래는 OpenJDK `TimSort` 의 두 규칙을 `int[]` 용으로 옮겨 쓴 코드다.

```java
import java.util.*;

public class Runs {
	static int minRunLength(int n) {
		int r = 0;
		while (n >= 32) {
			r |= (n & 1);
			n >>= 1;
		}
		return n + r;
	}

	static List<String> naturalRuns(int[] a) {
		List<String> runs = new ArrayList<>();
		int lo = 0;
		while (lo < a.length) {
			int hi = lo + 1;
			boolean desc = false;
			if (hi < a.length) {
				if (a[hi++] < a[lo]) {
					desc = true;
					while (hi < a.length && a[hi] < a[hi - 1]) {
						hi++;
					}
				} else {
					while (hi < a.length && a[hi] >= a[hi - 1]) {
						hi++;
					}
				}
			}
			runs.add((desc ? "desc" : "asc") + Arrays.toString(Arrays.copyOfRange(a, lo, hi)));
			lo = hi;
		}
		return runs;
	}

	public static void main(String[] args) {
		for (int n : new int[] {31, 32, 63, 64, 65, 100, 1000, 1024, 1025, 1_000_000}) {
			int m = minRunLength(n);
			System.out.printf("n=%d minRun=%d runs=%.2f%n", n, m, n < 32 ? 1.0 : (double) n / m);
		}
		System.out.println(naturalRuns(new int[] {1, 2, 2, 5, 9, 7, 4, 4, 3, 8, 6}));
	}
}
```

```text
n=31 minRun=31 runs=1.00
n=32 minRun=16 runs=2.00
n=63 minRun=32 runs=1.97
n=64 minRun=16 runs=4.00
n=65 minRun=17 runs=3.82
n=100 minRun=25 runs=4.00
n=1000 minRun=32 runs=31.25
n=1024 minRun=16 runs=64.00
n=1025 minRun=17 runs=60.29
n=1000000 minRun=31 runs=32258.06
[asc[1, 2, 2, 5, 9], desc[7, 4], desc[4, 3], desc[8, 6]]
```

`minRunLength` 는 n 의 상위 5비트를 취하고 나머지 비트 중 하나라도 1 이면 1 을 더한다. n 이 32 이상이면 결과는 16 이상 32 이하이며, `n / minrun` 이 2 의 거듭제곱이거나 그보다 조금 작게 나온다. 무작위 입력에서는 자연 run 이 대부분 minrun 보다 짧아 모든 run 이 minrun 길이로 맞춰지는데, 그 개수가 2 의 거듭제곱이면 병합이 완전 이진 트리 모양으로 균형을 이룬다. 1024 에서 minrun 이 32 가 아니라 16 인 것, 1025 에서 16 이 아니라 17 인 것이 이 규칙의 결과다. 1025 를 16 으로 나누면 run 이 65 개가 되어 마지막 병합이 길이 1024 와 1 의 극단적인 불균형이 된다.

run 분해 예시에서 `7, 4` 다음의 `4` 는 앞의 `4` 보다 작지 않으므로 엄격한 내림차순이 끊겨 새 run 이 시작된다.

## 3. 입력 형태별 비교 횟수

비교자 호출 횟수를 세면 run 을 얼마나 이용하는지 직접 볼 수 있다. 원소 10만 개에 대해 일곱 가지 입력을 정렬했다.

```java
	static long count(Integer[] a) {
		AtomicLong c = new AtomicLong();
		Arrays.sort(a, (x, y) -> {
			c.incrementAndGet();
			return Integer.compare(x, y);
		});
		for (int i = 1; i < a.length; i++) {
			if (a[i - 1] > a[i]) {
				throw new AssertionError();
			}
		}
		return c.get();
	}
```

```text
n*log2(n)        = 1,660,964
ascending        = 99,999
strictly desc    = 99,999
all equal        = 99,999
100 sorted runs  = 573,955
sorted + 100 tail= 102,475
100 random swaps = 110,604
random           = 1,534,701
```

이미 정렬된 입력, 완전히 역순인 입력, 모두 같은 값인 입력은 run 이 하나뿐이어서 탐지에 쓰인 n-1 번의 비교로 끝난다. 역순 입력은 탐지 후 한 번 뒤집을 뿐이다. 정렬된 배열 끝에 무작위 값 100 개를 붙인 경우는 102,475 번으로, 로그 테이블에 새 행을 덧붙이고 다시 정렬하는 흔한 패턴이 사실상 선형 시간에 처리된다. 정렬된 배열에서 임의의 두 원소를 100 번 맞바꾼 경우도 110,604 번이다. 길이 1000 의 오름차순 구간 100 개를 이어 붙인 입력은 573,955 번으로 무작위 입력의 37% 수준이다.

무작위 입력의 1,534,701 번은 비교 기반 정렬의 정보 이론적 하한 `log2(n!)` ≈ 1,516,704 의 1.2% 위다. run 구조가 없는 입력에서는 균형 잡힌 병합 정렬과 같은 수준으로 동작한다는 뜻이다. 원시 타입용 퀵소트 계열과 비교하면 TimSort 는 비교 횟수가 적은 대신 원소 이동과 임시 배열 사용이 많다. 객체 정렬은 비교자 호출(역참조와 가상 호출)이 비싸고 원소 이동은 참조 복사라서 싸다. 비교 횟수를 줄이는 쪽이 유리한 조건이다.

## 4. 병합 스택과 불변식

찾아낸 run 은 시작 위치와 길이가 스택에 쌓인다. run 을 모두 찾은 뒤 한꺼번에 병합하지 않고, 새 run 을 쌓을 때마다 스택 상단을 검사해 필요한 병합을 즉시 수행한다. 스택 위에서부터 세 run 의 길이를 X, Y, Z(Z 가 가장 최근)라 하면 유지하려는 조건은 두 가지다.

```text
(1) X > Y + Z
(2) Y > Z
```

조건이 깨지면 Y 를 X 와 Z 중 더 짧은 쪽과 병합하고 다시 검사한다. 병합은 항상 인접한 run 끼리만 한다. 인접하지 않은 run 을 병합하면 그 사이에 있던 같은 값 원소와의 순서가 뒤바뀌어 안정성이 깨진다.

두 조건이 유지되면 스택 아래로 갈수록 run 길이가 피보나치 수열보다 빠르게 커진다. 그래서 스택 깊이는 log 수준으로 제한되고, 병합되는 두 run 의 길이가 크게 차이 나지 않아 총 병합 비용이 O(n log n) 으로 묶인다. 구현은 이 깊이 상한에 맞춰 고정 크기 배열로 스택을 잡는다.

이 부분에서 실제 결함이 나왔다. 2015 년 de Gouw 등이 KeY 정형 검증 도구로 OpenJDK 의 TimSort 를 검증하다가, 상단 세 run 만 검사하는 병합 루프로는 불변식이 스택 전체에서 유지되지 않는 입력이 존재함을 증명했다. 불변식이 깨지면 스택이 계산된 상한보다 깊어져 `ArrayIndexOutOfBoundsException` 이 날 수 있다. 재현에는 매우 큰 배열과 특수하게 구성한 run 길이가 필요해 일반 서비스에서 만날 가능성은 없지만, 10 년 넘게 쓰인 표준 라이브러리 정렬에서 테스트가 아닌 증명으로 결함이 발견된 사례로 인용된다. OpenJDK 는 JDK-8072909 로 수정했다.

병합에는 임시 배열이 필요하다. TimSort 는 두 run 중 짧은 쪽만 임시 배열에 복사하고, 병합 전에 이진 탐색으로 "이미 제자리에 있는" 앞뒤 구간을 잘라 낸다. 임시 공간은 최악의 경우 n/2 개의 참조다. 제자리 정렬인 힙 정렬이나 퀵소트와 달리 O(n) 추가 메모리를 쓴다는 점은 수천만 건 단위의 배열을 정렬할 때 고려해야 한다.

## 5. galloping

두 run 을 병합할 때 한쪽 run 의 원소가 계속 이기는 상황이 있다. 예를 들어 A 의 모든 원소가 B 의 첫 원소보다 작으면, 한 개씩 비교하는 병합은 A 의 길이만큼 비교한다. TimSort 는 한쪽이 연속으로 `MIN_GALLOP`(초기값 7)번 이기면 galloping 모드로 전환한다. 상대 run 의 현재 원소가 이쪽 run 의 어디에 들어가는지를 1, 3, 7, 15, ... 간격으로 건너뛰며 범위를 좁힌 뒤 이진 탐색으로 확정하고, 그 구간을 한 번에 블록 복사한다. 길이 k 인 구간을 넘기는 데 드는 비교가 k 에서 약 2·log2(k) 로 줄어든다.

임계값은 고정이 아니다. galloping 이 성과를 내면 임계값을 낮춰 더 쉽게 진입하게 하고, 성과 없이 빠져나오면 높인다. 무작위 데이터에서는 galloping 이 한 원소당 비교를 오히려 늘리기 때문에 이 적응이 필요하다. 3절의 "100 sorted runs" 결과가 무작위 입력의 37% 에 그친 것은 run 탐지와 galloping 이 함께 작용한 결과다.

## 6. Comparator 계약 위반 예외

TimSort 로 바뀐 Java 7 부터 잘못 작성된 비교자가 `IllegalArgumentException: Comparison method violates its general contract!` 를 던질 수 있게 되었다. 계약은 세 가지다. `sgn(compare(a, b)) == -sgn(compare(b, a))`, 추이성, 그리고 `compare(a, b) == 0` 이면 모든 c 에 대해 `sgn(compare(a, c)) == sgn(compare(b, c))`.

```java
import java.util.*;

public class Contract {
	public static void main(String[] args) {
		Comparator<Integer> neverEqual = (a, b) -> a > b ? 1 : -1;
		for (int n : new int[] {31, 32, 64, 1000, 100_000}) {
			Random rnd = new Random(1);
			Integer[] a = new Integer[n];
			for (int i = 0; i < n; i++) {
				a[i] = rnd.nextInt(5);
			}
			try {
				Arrays.sort(a, neverEqual);
				System.out.println("neverEqual n=" + n + " -> no exception");
			} catch (IllegalArgumentException e) {
				System.out.println("neverEqual n=" + n + " -> " + e.getMessage());
			}
		}
		for (int n : new int[] {31, 32, 64, 1000, 100_000}) {
			Random rnd = new Random(1);
			Random coin = new Random(2);
			Integer[] a = new Integer[n];
			for (int i = 0; i < n; i++) {
				a[i] = rnd.nextInt();
			}
			try {
				Arrays.sort(a, (x, y) -> coin.nextInt(3) - 1);
				System.out.println("random n=" + n + " -> no exception");
			} catch (IllegalArgumentException e) {
				System.out.println("random n=" + n + " -> " + e.getMessage());
			}
		}
		record Item(String name, Double score) { }
		Comparator<Item> nanUnsafe = (x, y) -> x.score() < y.score() ? -1 : x.score() > y.score() ? 1 : 0;
		Random rnd = new Random(3);
		int thrown = 0;
		for (int trial = 0; trial < 100; trial++) {
			List<Item> items = new ArrayList<>();
			for (int i = 0; i < 2000; i++) {
				items.add(new Item("i" + i, rnd.nextInt(10) == 0 ? Double.NaN : rnd.nextDouble()));
			}
			try {
				items.sort(nanUnsafe);
			} catch (IllegalArgumentException e) {
				thrown++;
			}
			items.sort(Comparator.comparing(Item::score));
		}
		System.out.println("NaN comparator: exceptions in 100 trials = " + thrown);
	}
}
```

```text
neverEqual n=31 -> no exception
neverEqual n=32 -> no exception
neverEqual n=64 -> Comparison method violates its general contract!
neverEqual n=1000 -> Comparison method violates its general contract!
neverEqual n=100000 -> Comparison method violates its general contract!
random n=31 -> no exception
random n=32 -> no exception
random n=64 -> no exception
random n=1000 -> no exception
random n=100000 -> Comparison method violates its general contract!
NaN comparator: exceptions in 100 trials = 100
```

`neverEqual` 은 같은 값에 대해 0 을 반환하지 않는다. `compare(a, a)` 가 -1 이므로 첫 번째 계약을 어긴다. "같으면 아무거나" 라고 생각하고 쓰는 가장 흔한 형태다. `nanUnsafe` 는 `NaN` 이 낀 비교에서 `<` 와 `>` 가 모두 `false` 여서 0 을 반환한다. `NaN` 은 0.1 과도 같고 0.9 와도 같은데 0.1 과 0.9 는 다르므로 세 번째 계약을 어긴다. 점수 열에 `NaN` 이 섞인 데이터에서 실제로 발생하며, `Double.compare` 나 `Comparator.comparing` 은 `NaN` 을 가장 큰 값으로 일관되게 취급해 문제가 없다. 위 코드의 마지막 줄 정렬이 예외 없이 끝나는 것이 그 확인이다.

출력에서 읽어야 할 것은 탐지가 보장되지 않는다는 점이다. 예외는 병합 도중 "한쪽 run 이 먼저 소진될 수 없는 상황에서 소진되었다" 는 내부 모순이 감지될 때만 던져진다. n 이 31 이면 병합 자체가 없어 예외가 나지 않고, 무작위 비교자는 1000 개까지도 통과했다. 테스트 데이터가 작으면 통과하고 운영 데이터에서만 터지는 이유다. 예외 없이 끝난 경우에도 결과가 올바르게 정렬되었다는 보장은 없다. `-Djava.util.Arrays.useLegacyMergeSort=true` 를 주면 Java 6 의 병합 정렬로 돌아가 위 프로그램의 모든 줄이 `no exception` 과 `0` 으로 바뀐다. 예외만 사라질 뿐 비교자는 여전히 틀렸으므로 해결책이 아니다.

뺄셈 비교자도 계약을 어긴다.

```java
		Integer[] extremes = {Integer.MIN_VALUE, 1, Integer.MAX_VALUE, -1, 0};
		Arrays.sort(extremes, (a, b) -> a - b);
		System.out.println("a - b       : " + Arrays.toString(extremes));
		Arrays.sort(extremes, Integer::compare);
		System.out.println("compare     : " + Arrays.toString(extremes));
```

```text
a - b       : [-1, 0, 1, 2147483647, -2147483648]
compare     : [-2147483648, -1, 0, 1, 2147483647]
```

`1 - Integer.MIN_VALUE` 는 오버플로로 음수가 되어 `MIN_VALUE` 가 1 보다 크다고 판정된다. 원소가 5 개라 예외 없이 잘못된 순서가 그대로 반환되었다. ID 나 타임스탬프 차이처럼 값의 범위가 `int` 절반을 넘을 수 있는 필드에는 `Integer.compare`, `Long.compare`, `Comparator.comparingInt` 를 쓴다.

## 7. 실무 판단

정렬 대상이 변하는 동안 정렬하면 같은 예외가 난다. 비교 키가 가변 필드이고 다른 스레드가 정렬 중에 그 필드를 바꾸면, 비교자 자체는 올바르더라도 정렬이 관측하는 순서 관계가 일관되지 않게 된다. 정렬 전에 키를 스냅샷으로 복사하거나 불변 객체를 정렬한다.

여러 필드를 비교할 때는 `Comparator.comparing(...).thenComparing(...)` 체인으로 조립한다. 각 단계가 계약을 지키면 합성 결과도 지킨다. `null` 은 `Comparator.nullsFirst`·`nullsLast` 로 감싸 위치를 명시한다. 직접 `if` 로 비교자를 쓰면 `null`·`NaN`·동등 처리 중 하나를 빠뜨리기 쉽다.

거의 정렬된 데이터를 다시 정렬하는 비용이 낮다는 성질은 설계에 활용할 수 있다. 정렬된 목록에 소량을 추가한 뒤 전체를 다시 `sort` 하는 코드는 3절의 결과대로 선형에 가깝다. 삽입 위치를 이진 탐색으로 찾아 끼워 넣는 코드를 따로 작성할 이유가 크지 않다. 반대로 `parallelSort` 는 배열을 나눠 각각 정렬한 뒤 병합하므로 기존 run 구조의 이점이 줄어든다. 수십만 건 이상의 무작위 데이터에서만 이득이 있고, 공용 `ForkJoinPool` 을 쓰므로 요청 처리 경로에서는 다른 병렬 스트림 작업과 경합한다.

검증 환경: OpenJDK 21.0.13 에서 `java <파일>.java` 로 실행한 출력이다. 3절의 `count` 는 `Count.java` 의 발췌로, 입력 배열 일곱 개를 만드는 코드(시드 42 의 `java.util.Random`)와 출력문은 생략했다. 6절의 뺄셈 비교자 코드는 1절 `Stable.java` 의 `main` 뒷부분이다. 4·5절의 불변식과 galloping 설명은 OpenJDK 소스와 CPython 의 `listsort.txt` 에 근거하며 별도 실행 결과는 없다.

## 참고

- OpenJDK 소스, TimSort.java: https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/TimSort.java
- CPython, listsort.txt (Tim Peters 의 설계 설명): https://github.com/python/cpython/blob/main/Objects/listsort.txt
- Java SE 21 API, Arrays.sort(T[], Comparator): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Arrays.html
- Java SE 21 API, Comparator (compare 계약): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Comparator.html
- Java SE 7 호환성 문서 (정렬 구현 변경과 java.util.Arrays.useLegacyMergeSort): https://www.oracle.com/java/technologies/compatibility.html
- de Gouw 외, OpenJDK's java.utils.Collection.sort() is broken: The good, the bad and the worst case (CAV 2015): https://link.springer.com/chapter/10.1007/978-3-319-21690-4_16
- JDK-8072909, TimSort fails with ArrayIndexOutOfBounds on worst case long arrays: https://bugs.openjdk.org/browse/JDK-8072909
