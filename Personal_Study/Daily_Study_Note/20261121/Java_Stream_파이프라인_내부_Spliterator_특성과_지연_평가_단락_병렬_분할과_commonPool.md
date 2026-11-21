Notion 원본: https://www.notion.so/41f5a06fd6d38158b0b8dada95b74afe

# Java Stream 파이프라인 내부: Spliterator 특성, 지연 평가와 단락, 상태 있는 연산의 장벽, 병렬 분할과 commonPool

> 2026-11-21 신규 주제 · 확장 대상: JAVA(Stream API·람다), 자료구조&알고리즘(분할 정복)

## 학습 목표

- 중간 연산이 종단 연산 전까지 실행되지 않고, 요소 하나가 파이프라인 전체를 먼저 통과한다는 것을 로그 순서로 확인한다
- 컬렉션별 Spliterator 특성값과 분할 크기를 출력해 병렬 처리에 유리한 소스와 불리한 소스를 구분한다
- `count()` 가 파이프라인 실행을 생략하는 조건을 재현한다
- 병렬 스트림에서 공유 가변 상태와 잘못된 `reduce` 항등값이 만드는 오류를 재현하고 올바른 형태와 비교한다

## 1. 스트림은 연산의 연결 목록이고, 종단 연산이 그것을 구동한다

`stream()` 은 데이터를 복사하지 않는다. 소스를 가리키는 `Spliterator` 하나를 쥔 파이프라인 머리(head)를 만들 뿐이다. `filter`, `map` 같은 중간 연산은 호출될 때마다 새 단계 객체를 이전 단계에 연결한다. 이 시점까지 람다는 한 번도 호출되지 않는다. 종단 연산이 호출되면 마지막 단계에서 첫 단계 방향으로 `Sink` 를 감싸 올라가며 하나의 체인을 만들고, 그 체인에 소스의 요소를 밀어 넣는다.

```java
Stream<String> lazy = Stream.of("a", "b", "c").peek(s -> System.out.println("peek " + s));
System.out.println("종단 연산 전: 출력 없음");
System.out.println("결과 " + lazy.map(String::toUpperCase).toList());
```

```text
종단 연산 전: 출력 없음
peek a
peek b
peek c
결과 [A, B, C]
```

`peek` 을 붙인 줄에서는 아무것도 출력되지 않고 `toList()` 가 호출된 뒤에야 `peek` 이 실행된다. 종단 연산이 없는 스트림 코드는 아무 일도 하지 않는다. `stream().map(this::save)` 처럼 부수 효과를 `map` 에 넣고 종단 연산을 빠뜨리면 저장이 한 건도 일어나지 않는데 컴파일도 실행도 조용히 통과한다.

## 2. 요소 단위로 끝까지 흐르고, 단락 연산은 소스 소비를 멈춘다

```java
Optional<Integer> first = Stream.of(1, 2, 3, 4, 5, 6)
	.filter(n -> { System.out.println("filter " + n); return n % 2 == 0; })
	.map(n -> { System.out.println("map " + n); return n * 10; })
	.findFirst();
System.out.println("결과 " + first.get());
```

```text
filter 1
filter 2
map 2
결과 20
```

"전체를 filter 한 뒤 전체를 map" 하는 것이 아니다. 요소 `1` 이 `filter` 에서 탈락하고, 요소 `2` 가 `filter` 와 `map` 을 차례로 지나 `findFirst` 에 도달하면 거기서 끝난다. `3` 부터는 소스에서 꺼내지도 않는다. 여섯 개 중 `filter` 는 두 번, `map` 은 한 번 호출됐다. 중간 컬렉션이 만들어지지 않고 필요한 만큼만 계산되는 것은 이 구조 덕분이다. `findFirst`, `anyMatch`, `limit` 같은 단락 연산은 체인의 뒤쪽에서 "더 받지 않겠다" 는 신호를 앞쪽으로 전달하고, 소스를 순회하는 루프가 매 요소마다 그 신호를 확인한다.

## 3. 상태 있는 연산은 흐름을 끊는 장벽이다

```java
List<Integer> top2 = Stream.of(5, 3, 1, 4, 2)
	.peek(n -> System.out.println("before " + n))
	.sorted()
	.peek(n -> System.out.println("after " + n))
	.limit(2)
	.toList();
System.out.println("결과 " + top2);
```

```text
before 5
before 3
before 1
before 4
before 2
after 1
after 2
결과 [1, 2]
```

`sorted` 는 모든 요소를 받기 전에는 첫 요소도 내보낼 수 없다. 그래서 `sorted` 앞쪽은 다섯 개가 전부 실행되고, 뒤쪽은 `limit(2)` 의 단락이 적용되어 두 개만 실행된다. `limit` 이 뒤에 있어도 `sorted` 앞의 소비를 줄여 주지 못한다. `sorted`, `distinct` 는 내부에 버퍼를 두는 상태 있는 연산이고, 요소 수에 비례하는 메모리를 쓴다. 상위 N 건만 필요한데 입력이 크다면 `sorted().limit(n)` 대신 크기 N 의 힙을 유지하는 방식이 메모리를 덜 쓴다. `Stream.iterate` 같은 무한 스트림에 `sorted` 를 걸면 끝나지 않는다.

## 4. Spliterator 특성이 최적화를 결정한다

`Spliterator` 는 `Iterator` 에 분할(`trySplit`)과 메타데이터(특성값, 예상 크기)를 더한 인터페이스다. 파이프라인은 소스의 특성값을 읽고, 중간 연산을 지날 때마다 특성을 유지하거나 지운다.

```java
static String flags(Spliterator<?> sp) {
	List<String> names = new ArrayList<>();
	if (sp.hasCharacteristics(Spliterator.ORDERED)) { names.add("ORDERED"); }
	if (sp.hasCharacteristics(Spliterator.DISTINCT)) { names.add("DISTINCT"); }
	if (sp.hasCharacteristics(Spliterator.SORTED)) { names.add("SORTED"); }
	if (sp.hasCharacteristics(Spliterator.SIZED)) { names.add("SIZED"); }
	if (sp.hasCharacteristics(Spliterator.SUBSIZED)) { names.add("SUBSIZED"); }
	if (sp.hasCharacteristics(Spliterator.IMMUTABLE)) { names.add("IMMUTABLE"); }
	return String.join("|", names) + " size=" + sp.estimateSize();
}
```

```text
ArrayList   ORDERED|SIZED|SUBSIZED size=1000
LinkedList  ORDERED|SIZED|SUBSIZED size=1000
HashSet     DISTINCT|SIZED size=1000
TreeSet     ORDERED|DISTINCT|SORTED|SIZED size=1000
iterate     ORDERED|IMMUTABLE size=9223372036854775807
filter 후   ORDERED size=1000
map 후      ORDERED|SIZED|SUBSIZED size=1000
```

`SIZED` 는 정확한 크기를 안다는 뜻이고 `SUBSIZED` 는 분할한 조각도 정확한 크기를 안다는 뜻이다. `map` 은 요소 수를 바꾸지 않으므로 둘 다 유지하고, `filter` 는 몇 개가 남을지 모르므로 지운다(`size=1000` 은 상한 추정치로 남는다). `Stream.iterate` 는 크기를 모르므로 `Long.MAX_VALUE` 를 돌려준다. 이 정보가 실제로 실행을 바꾸는 경우가 `count()` 다.

```java
long c1 = List.of(1, 2, 3).stream().peek(n -> System.out.println("peek " + n)).map(n -> n * 2).count();
System.out.println("map 뒤 count = " + c1);
long c2 = List.of(1, 2, 3).stream().peek(n -> System.out.println("peek " + n)).filter(n -> n > 0).count();
System.out.println("filter 뒤 count = " + c2);
```

```text
map 뒤 count = 3
peek 1
peek 2
peek 3
filter 뒤 count = 3
```

첫 번째 파이프라인은 `peek` 이 한 번도 실행되지 않았다. 소스가 `SIZED` 이고 중간 연산이 그 특성을 유지하므로 `count()` 가 요소를 흘려보내지 않고 크기를 바로 돌려준 것이다. `Stream.count()` 의 API 문서는 Java 9 부터 구현이 파이프라인 실행을 생략할 수 있다고 명시한다. `filter` 가 끼면 `SIZED` 가 사라져 요소를 실제로 세야 하므로 `peek` 이 실행된다. `peek` 이나 `map` 안의 부수 효과에 의존하는 코드는 이 최적화로 동작이 달라진다.

## 5. 분할 품질은 소스 자료구조가 정한다

병렬 스트림은 `trySplit` 으로 소스를 반복해서 둘로 나누고 조각마다 작업을 만든다. 아래는 요소 10,000 개짜리 소스에 `trySplit` 을 네 번 연속 호출해 "떼어 낸 앞쪽 크기/남은 크기" 를 출력한 것이다.

```java
static String splitSizes(Spliterator<Integer> sp) {
	StringBuilder sb = new StringBuilder();
	for (int i = 0; i < 4; i++) {
		Spliterator<Integer> prefix = sp.trySplit();
		if (prefix == null) { sb.append("null "); break; }
		sb.append(prefix.estimateSize()).append('/').append(sp.estimateSize()).append(' ');
	}
	return sb.toString().trim();
}
```

```text
ArrayList   5000/5000 2500/2500 1250/1250 625/625
LinkedList  1024/8976 2048/6928 3072/3856 3856/0
HashSet     5000/5000 2500/2500 1250/1250 625/625
TreeSet     5000/5000 2500/2500 1250/1250 625/625
```

`ArrayList` 는 인덱스 계산만으로 정확히 반씩 나눈다. 복사가 없고 조각 크기도 정확하다. `LinkedList` 는 중간 지점을 알 수 없어 앞에서부터 1024 개, 2048 개, 3072 개로 늘려 가며 배열에 복사해 떼어 낸다. 분할 자체가 순차 순회이고 조각 크기도 균형이 맞지 않는다. `HashSet` 과 `TreeSet` 의 숫자는 `SUBSIZED` 가 없는 소스의 추정치라서 실제 조각 크기와 다를 수 있다(4절 출력에서 두 컬렉션에 `SUBSIZED` 가 없었다). 병렬화의 이득은 요소당 작업 비용과 분할 비용의 비율에 달려 있으므로, 배열 기반 소스와 `IntStream.range` 가 유리하고 연결 리스트와 `Stream.iterate` 가 불리하다.

## 6. 병렬 실행은 commonPool 과 호출 스레드가 함께 한다

```java
System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors()
	+ " commonPool parallelism=" + ForkJoinPool.commonPool().getParallelism());
Set<String> threads = ConcurrentHashMap.newKeySet();
List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
IntStream.range(0, 16).boxed().parallel().forEach(n -> {
	threads.add(Thread.currentThread().getName().replaceAll("-\\d+$", "-N"));
	seen.add(n);
});
System.out.println("스레드 종류 " + new TreeSet<>(threads));
System.out.println("forEach 순서 정렬됨? " + seen.equals(seen.stream().sorted().toList()));
List<Integer> ordered = new ArrayList<>();
IntStream.range(0, 16).boxed().parallel().forEachOrdered(ordered::add);
System.out.println("forEachOrdered " + ordered);
System.out.println("parallel toList " + IntStream.range(0, 16).boxed().parallel().map(n -> n * 2).toList());
```

```text
availableProcessors=2 commonPool parallelism=1
스레드 종류 [ForkJoinPool.commonPool-worker-N, main]
forEach 순서 정렬됨? false
forEachOrdered [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]
parallel toList [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30]
```

commonPool 의 기본 병렬도는 프로세서 수에서 1 을 뺀 값이다. 종단 연산을 호출한 스레드도 작업에 참여하기 때문이다. 2 코어 환경에서 병렬도가 1 이고, 실행 스레드에 `main` 과 워커가 함께 찍혔다(같은 프로그램을 다시 실행했을 때는 `main` 만 찍혔다. 16 개 정도의 작은 작업은 호출 스레드가 워커보다 먼저 다 처리할 수 있다). 컨테이너에 CPU 를 1 개만 할당한 서비스에서는 병렬 스트림이 사실상 순차로 돈다. `forEach` 는 순서를 보장하지 않고(이번 실행에서 정렬돼 있지 않았다), `forEachOrdered` 와 `toList`, `collect` 는 병렬이어도 만남 순서를 지킨다.

commonPool 은 JVM 전체가 공유한다. `CompletableFuture.supplyAsync` 의 기본 실행기도 commonPool 의 병렬도가 2 이상이면 같은 풀이다. 병렬 스트림의 람다 안에서 JDBC 호출이나 HTTP 호출처럼 블로킹하는 작업을 하면 풀의 워커가 묶여 같은 JVM 의 다른 병렬 스트림까지 느려진다. 격리가 필요하면 전용 풀 안에서 종단 연산을 호출하는 방법이 쓰인다.

```java
ForkJoinPool pool = new ForkJoinPool(2);
Set<String> poolThreads = pool.submit(() -> IntStream.range(0, 64).parallel()
	.mapToObj(n -> Thread.currentThread().getName().replaceAll("-\\d+$", "-N"))
	.collect(Collectors.toCollection(TreeSet::new))).get();
pool.shutdown();
System.out.println("스레드 종류 " + poolThreads);
```

```text
스레드 종류 [ForkJoinPool-1-worker-N]
```

ForkJoin 워커 스레드에서 시작한 병렬 스트림은 그 워커가 속한 풀에서 실행된다. 다만 이 동작은 스트림 API 문서에 명세된 것이 아니라 구현에서 따라 나오는 결과다. 블로킹 작업을 병렬로 돌려야 한다면 `ExecutorService` 나 가상 스레드에 작업을 제출하는 쪽이 명세에 근거한 방법이다.

## 7. 병렬에서 깨지는 코드 두 가지

```java
int lost = 0;
int thrown = 0;
for (int trial = 0; trial < 200; trial++) {
	List<Integer> unsafe = new ArrayList<>();
	try {
		IntStream.range(0, 10_000).parallel().forEach(unsafe::add);
		if (unsafe.size() != 10_000) { lost++; }
	} catch (ArrayIndexOutOfBoundsException e) {
		thrown++;
	}
}
System.out.println("200회 중 유실 발생 여부=" + (lost > 0) + ", AIOOBE 발생 여부=" + (thrown > 0)
	+ ", 정상=" + (200 - lost - thrown > 0));
System.out.println("collect 사용 size=" + IntStream.range(0, 10_000).parallel().boxed().collect(Collectors.toList()).size());

List<Integer> nums = IntStream.rangeClosed(1, 100).boxed().toList();
System.out.println("순차 reduce(10, sum)=" + nums.stream().reduce(10, Integer::sum));
System.out.println("병렬 reduce(10, sum) 이 5060 인가? " + (nums.parallelStream().reduce(10, Integer::sum) == 5060));
System.out.println("병렬 reduce(0, sum)=" + nums.parallelStream().reduce(0, Integer::sum));
```

```text
200회 중 유실 발생 여부=true, AIOOBE 발생 여부=true, 정상=true
collect 사용 size=10000
순차 reduce(10, sum)=5060
병렬 reduce(10, sum) 이 5060 인가? false
병렬 reduce(0, sum)=5050
```

첫째는 공유 가변 상태다. `ArrayList.add` 는 스레드 안전하지 않아서 200 회 시도 안에 요소 유실, `ArrayIndexOutOfBoundsException`, 정상 완료가 모두 나왔다. 프로그램을 다시 실행했을 때는 유실과 정상 완료만 나오고 예외는 나오지 않았다. 매번 실패하지 않는다는 점이 문제를 키운다. 테스트에서는 통과하고 운영에서 가끔 건수가 맞지 않는다. `collect` 는 조각마다 별도 컨테이너에 모은 뒤 합치므로 동기화 없이도 항상 10,000 건이다.

둘째는 `reduce` 의 항등값이다. 첫 인자는 초깃값이 아니라 항등원이어야 한다. 병렬에서는 조각마다 그 값으로 시작하므로 `10` 을 넣으면 조각 수만큼 더해진다. 순차에서는 5060 으로 "의도대로" 보이기 때문에 `parallel()` 을 붙이는 순간에야 드러난다. 초깃값을 더하려면 `reduce(0, Integer::sum) + 10` 으로 쓴다.

## 8. 실무 판단

| 상황 | 판단 |
|---|---|
| 요소 수가 적거나 요소당 작업이 가벼움 | 순차. 분할과 작업 생성 비용이 이득보다 크다 |
| 소스가 `LinkedList`, `Stream.iterate`, I/O 기반 | 순차. 분할이 순차 순회라 균형이 맞지 않는다 |
| 람다 안에 DB·HTTP 호출 | 병렬 스트림 대신 전용 실행기에 제출 |
| CPU 위주 계산, 배열 기반 소스, 요소 수가 큼 | 병렬 후보. 측정으로 확인한 뒤 적용 |
| 결과를 외부 컬렉션에 `add` | `collect` 로 교체 |

- 중간 연산의 람다는 상태가 없고 소스를 건드리지 않아야 한다. `java.util.stream` 패키지 문서의 non-interference, stateless 요건이다.
- `peek` 은 디버깅용이다. 4절처럼 실행이 생략될 수 있다.
- `sorted().limit(n)` 은 전체를 버퍼링한다.

검증 환경: OpenJDK 21.0.13, 프로세서 2 개 환경에서 `java -Dstdout.encoding=UTF-8 <파일>.java` 로 실행한 출력이다. 본문의 코드는 `StreamLab.java` 와 `Split.java` 의 `main` 에서 발췌했고, 4절의 소스 컬렉션은 0 부터 999 까지의 정수 1,000 개다. 프로그램을 두 번 실행했고 본문 출력은 첫 실행의 것이다. 6절의 스레드 종류와 7절의 예외 발생 여부는 두 실행에서 달랐고(본문에 적음), 나머지 출력은 같았다. 7절은 실행마다 횟수가 달라 발생 여부만 출력했다. 전용 풀에서의 실행 위치는 구현 동작을 관찰한 것이며 명세 근거는 없다.

## 참고

- Java SE 21 API, `java.util.stream` 패키지 설명 (Stream operations and pipelines, Parallelism, Non-interference, Reduction): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/package-summary.html
- Java SE 21 API, Spliterator (특성값 정의와 trySplit 계약): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Spliterator.html
- Java SE 21 API, Stream.count (파이프라인 실행 생략에 관한 API Note): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html#count()
- Java SE 21 API, ForkJoinPool (commonPool 과 `java.util.concurrent.ForkJoinPool.common.parallelism`): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ForkJoinPool.html
- OpenJDK 소스, AbstractPipeline.java: https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/stream/AbstractPipeline.java
- OpenJDK 소스, LinkedList.java (LLSpliterator 의 BATCH_UNIT): https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/LinkedList.java
- Brian Goetz, Java Streams, Part 3: Streams under the hood (IBM developerWorks)
