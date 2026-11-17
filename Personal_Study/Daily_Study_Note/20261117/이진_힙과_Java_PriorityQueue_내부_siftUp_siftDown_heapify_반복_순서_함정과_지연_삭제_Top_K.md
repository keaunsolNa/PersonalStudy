Notion 원본: https://www.notion.so/41b5a06fd6d38125b591ec065b492e49

# 이진 힙과 java.util.PriorityQueue 내부: siftUp·siftDown, O(n) heapify, 반복 순서 함정, remove(Object) 비용과 지연 삭제, Top-K

> 2026-11-17 신규 주제 · 확장 대상: 자료구조&알고리즘(힙·우선순위 큐), JAVA(컬렉션 프레임워크)

## 학습 목표

- 배열 기반 이진 최소 힙을 직접 구현하고 `java.util.PriorityQueue` 와 결과를 대조한다
- 원소를 하나씩 넣는 방식과 bottom-up heapify 의 비교 횟수를 입력 분포별로 측정한다
- `PriorityQueue` 의 반복 순서, 동순위 처리, 큐 안 원소 변경이 만드는 오동작을 재현한다
- `remove(Object)` 의 선형 비용을 측정하고 지연 삭제와 크기 K 힙으로 대체한다

## 1. 배열 위의 완전 이진 트리

이진 힙은 "부모가 자식보다 작거나 같다" 는 조건만 지키는 완전 이진 트리다. 형제 사이의 순서는 정하지 않는다. 이 느슨함 덕분에 삽입과 삭제가 트리 높이만큼의 교환으로 끝나고, 완전 이진 트리라서 포인터 없이 배열 하나에 저장할 수 있다. 0 기반 인덱스 k 의 부모는 `(k - 1) >>> 1`, 왼쪽 자식은 `2k + 1`, 오른쪽 자식은 `2k + 2` 다. 원소 n 개의 높이는 `floor(log2 n)` 이므로 삽입(`offer`)과 최솟값 삭제(`poll`)는 O(log n), 최솟값 조회(`peek`)는 배열 0번을 읽는 O(1) 이다.

정렬된 구조(TreeMap, 스킵 리스트)와 비교하면 힙은 최솟값 하나만 빠르게 꺼낼 수 있고 임의 원소의 탐색은 O(n) 이다. 대신 노드 객체와 포인터가 없어 메모리 지역성이 좋고 상수 계수가 작다. "지금 가장 급한 것 하나" 만 반복해서 필요한 스케줄러, 타이머, 다익스트라, K-way 병합에 맞는 구조다.

## 2. 직접 구현: siftUp 과 siftDown

삽입은 배열 끝에 놓고 부모보다 작은 동안 위로 올린다(siftUp). 삭제는 루트를 꺼내고 마지막 원소를 루트 자리에 놓은 뒤 더 작은 자식과 바꾸며 내린다(siftDown). 아래 구현은 JDK 와 같은 방식으로, 매번 두 원소를 교환하지 않고 빈 자리를 이동시키다가 마지막에 한 번만 대입한다.

```java
import java.util.*;

public class MinHeap<E> {
    private Object[] a;
    private int size;
    private final Comparator<? super E> cmp;

    public MinHeap(Comparator<? super E> cmp) { this.a = new Object[11]; this.cmp = cmp; }

    /** Floyd 의 bottom-up heapify: 마지막 내부 노드부터 siftDown */
    public MinHeap(Collection<? extends E> src, Comparator<? super E> cmp) {
        this.a = src.toArray(); this.size = a.length; this.cmp = cmp;
        for (int i = (size >>> 1) - 1; i >= 0; i--) siftDown(i, elem(i));
    }

    public int size() { return size; }
    public E peek() { return size == 0 ? null : elem(0); }

    public void offer(E e) {
        if (size == a.length) a = Arrays.copyOf(a, size < 64 ? size * 2 + 2 : size + (size >> 1));
        siftUp(size++, e);
    }

    public E poll() {
        if (size == 0) return null;
        E top = elem(0);
        E last = elem(--size);
        a[size] = null;                       // GC 가 수거하도록 참조 제거
        if (size > 0) siftDown(0, last);
        return top;
    }

    private void siftUp(int k, E x) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            E p = elem(parent);
            if (cmp.compare(x, p) >= 0) break;
            a[k] = p;                         // 부모를 내리고 빈 자리를 위로 옮긴다
            k = parent;
        }
        a[k] = x;
    }

    private void siftDown(int k, E x) {
        int half = size >>> 1;                // half 이상의 인덱스는 리프
        while (k < half) {
            int child = 2 * k + 1, right = child + 1;
            if (right < size && cmp.compare(elem(right), elem(child)) < 0) child = right;
            E c = elem(child);
            if (cmp.compare(x, c) <= 0) break;
            a[k] = c;
            k = child;
        }
        a[k] = x;
    }

    @SuppressWarnings("unchecked") private E elem(int i) { return (E) a[i]; }

    public static void main(String[] args) {
        Random rnd = new Random(42);
        for (int round = 0; round < 200; round++) {
            int n = rnd.nextInt(500);
            List<Integer> data = new ArrayList<>();
            for (int i = 0; i < n; i++) data.add(rnd.nextInt(100));
            MinHeap<Integer> mine = round % 2 == 0
                ? new MinHeap<>(data, Comparator.naturalOrder()) : new MinHeap<>(Comparator.naturalOrder());
            if (round % 2 == 1) data.forEach(mine::offer);
            PriorityQueue<Integer> ref = new PriorityQueue<>(data);
            for (int op = 0; op < 2 * n; op++) {
                if (rnd.nextInt(3) == 0) { int v = rnd.nextInt(100); mine.offer(v); ref.offer(v); }
                else if (!Objects.equals(mine.poll(), ref.poll())) throw new AssertionError("mismatch at round " + round);
                if (mine.size() != ref.size() || !Objects.equals(mine.peek(), ref.peek())) throw new AssertionError("peek/size mismatch");
            }
        }
        System.out.println("200 rounds: MinHeap == java.util.PriorityQueue");
    }
}
```

`main` 은 무작위 연산 열을 직접 구현과 `PriorityQueue` 에 똑같이 적용해 매 단계의 `poll`·`peek`·`size` 를 대조한다. 짝수 라운드는 heapify 생성자, 홀수 라운드는 반복 `offer` 로 만들어 두 경로를 모두 검사하며, 출력은 `200 rounds: MinHeap == java.util.PriorityQueue` 다. 배열 확장 정책도 JDK 를 따랐다. 용량이 64 미만이면 두 배에 2 를 더하고 그 이상이면 1.5 배로 늘린다. 기본 초기 용량은 11 이다.

## 3. heapify 는 왜 O(n) 인가

n 개를 하나씩 `offer` 하면 최악의 경우 원소마다 높이만큼 올라가 O(n log n) 이다. bottom-up heapify 는 마지막 내부 노드부터 루트 방향으로 siftDown 을 수행한다. 노드의 절반은 리프라 작업이 없고, 4분의 1 은 최대 한 칸, 8분의 1 은 최대 두 칸만 내려간다. 높이 h 인 노드 수가 대략 `n / 2^(h+1)` 이므로 총 이동은 `n × Σ h / 2^(h+1)` 이고 이 급수는 1 로 수렴해 O(n) 이 된다. 비용이 큰 긴 경로를 가진 노드가 가장 적다는 점이 핵심이다.

비교 횟수를 세는 비교자를 넣어 측정했다. 시간 대신 비교 횟수를 쓴 것은 JIT 와 GC 의 영향을 받지 않는 결정적인 값이기 때문이다.

```java
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class HeapifyCost {
    public static void main(String[] args) {
        System.out.printf("%-10s %-12s %14s %14s %8s%n", "n", "input", "offer x n", "heapify", "ratio");
        for (int n : new int[]{1_000, 100_000, 1_000_000}) {
            for (String kind : new String[]{"random", "descending", "ascending"}) {
                List<Integer> data = new ArrayList<>(n);
                Random rnd = new Random(7);
                for (int i = 0; i < n; i++) {
                    data.add(switch (kind) { case "random" -> rnd.nextInt(); case "descending" -> n - i; default -> i; });
                }
                AtomicLong c1 = new AtomicLong();
                PriorityQueue<Integer> byOffer = new PriorityQueue<>((x, y) -> { c1.incrementAndGet(); return Integer.compare(x, y); });
                for (Integer v : data) byOffer.offer(v);

                AtomicLong c2 = new AtomicLong();
                MinHeap<Integer> byHeapify = new MinHeap<>(data, (x, y) -> { c2.incrementAndGet(); return Integer.compare(x, y); });

                if (!Objects.equals(byOffer.peek(), byHeapify.peek())) throw new AssertionError();
                System.out.printf("%-10d %-12s %14d %14d %8.2f%n", n, kind, c1.get(), c2.get(), (double) c1.get() / c2.get());
            }
        }
    }
}
```

```text
n          input             offer x n        heapify    ratio
1000       random                 2244           1881     1.19
1000       descending             7987           1982     4.03
1000       ascending               999            999     1.00
100000     random               227884         188134     1.21
100000     descending          1468946         199978     7.35
100000     ascending             99999          99999     1.00
1000000    random              2280489        1882189     1.21
1000000    descending         17951445        1999974     8.98
1000000    ascending            999999         999999     1.00
```

heapify 의 비교 횟수는 어떤 입력에서도 2n 을 넘지 않았다. 반복 `offer` 는 내림차순 입력에서 원소마다 루트까지 올라가 n log n 으로 늘어나고 n 이 커질수록 격차가 벌어진다(4.03배, 7.35배, 8.98배). 무작위 입력에서 반복 `offer` 가 약 2.28n 으로 선형에 가깝게 나온 점도 확인할 가치가 있다. 새 원소가 리프 근처에 머물 확률이 높아 평균 삽입 비용은 O(1) 이다. 그래서 무작위 데이터에서는 두 방식의 차이가 1.2배 정도에 그치고, 정렬된 로그나 시간순 이벤트처럼 편향된 입력에서 차이가 커진다.

JDK 에서 heapify 경로를 타려면 컬렉션을 받는 생성자 `new PriorityQueue<>(collection)` 를 쓴다. 이 생성자는 비교자를 받지 않으므로 자연 순서가 아닌 비교자가 필요하면 원소 타입이 `Comparable` 을 구현하게 하거나 반복 `offer` 를 감수해야 한다. `addAll` 은 내부적으로 원소마다 `add` 를 호출하므로 heapify 가 아니다.

## 4. PriorityQueue 사용에서 자주 틀리는 세 가지

```java
import java.util.*;
import java.util.stream.*;

public class PqPitfalls {
    record Job(String name, int priority) {}
    record Seq<T>(T value, int priority, long seq) {}

    public static void main(String[] args) {
        // 1) 반복 순서는 배열 순서일 뿐 정렬 순서가 아니다
        PriorityQueue<Integer> pq = new PriorityQueue<>(List.of(5, 1, 4, 2, 3, 0));
        System.out.println("toString  : " + pq);
        System.out.println("stream    : " + pq.stream().map(String::valueOf).collect(Collectors.joining(",")));
        List<Integer> drained = new ArrayList<>();
        PriorityQueue<Integer> copy = new PriorityQueue<>(pq);
        while (!copy.isEmpty()) drained.add(copy.poll());
        System.out.println("poll loop : " + drained);

        // 2) 동순위 원소 사이의 FIFO 는 보장되지 않는다
        PriorityQueue<Job> jobs = new PriorityQueue<>(Comparator.comparingInt(Job::priority));
        for (String n : List.of("a", "b", "c", "d", "e")) jobs.offer(new Job(n, 1));
        StringBuilder sb = new StringBuilder(jobs.poll().name());
        jobs.offer(new Job("f", 1));
        while (!jobs.isEmpty()) sb.append(jobs.poll().name());
        System.out.println("ties      : " + sb + " (inserted as abcdef)");

        long[] counter = {0};
        PriorityQueue<Seq<String>> stable = new PriorityQueue<>(
            Comparator.<Seq<String>>comparingInt(Seq::priority).thenComparingLong(Seq::seq));
        for (String n : List.of("a", "b", "c", "d", "e")) stable.offer(new Seq<>(n, 1, counter[0]++));
        sb = new StringBuilder(stable.poll().value());
        stable.offer(new Seq<>("f", 1, counter[0]++));
        while (!stable.isEmpty()) sb.append(stable.poll().value());
        System.out.println("with seq  : " + sb);

        // 3) 큐 안에 있는 원소의 우선순위 필드를 바꾸면 힙 조건이 깨진다
        int[] key = {1};
        PriorityQueue<int[]> broken = new PriorityQueue<>(Comparator.comparingInt(x -> x[0]));
        broken.offer(key); broken.offer(new int[]{2}); broken.offer(new int[]{3});
        key[0] = 10;                                  // 루트의 키를 큐 밖에서 변경
        System.out.println("mutated   : " + Stream.generate(broken::poll).limit(3).map(x -> x[0]).toList());

        // 4) Top-K: 크기 K 의 최소 힙 하나로 O(n log K)
        int[] data = new Random(1).ints(1_000_000, 0, 1_000_000_000).toArray();
        int k = 5;
        PriorityQueue<Integer> top = new PriorityQueue<>(k);
        for (int v : data) {
            if (top.size() < k) top.offer(v);
            else if (v > top.peek()) { top.poll(); top.offer(v); }
        }
        List<Integer> viaHeap = top.stream().sorted(Comparator.reverseOrder()).toList();
        List<Integer> viaSort = Arrays.stream(data).boxed().sorted(Comparator.reverseOrder()).limit(k).toList();
        System.out.println("top-5     : " + viaHeap + " equals sort = " + viaHeap.equals(viaSort));
    }
}
```

```text
toString  : [0, 1, 4, 2, 3, 5]
stream    : 0,1,4,2,3,5
poll loop : [0, 1, 2, 3, 4, 5]
ties      : aefdcb (inserted as abcdef)
with seq  : abcdef
mutated   : [10, 2, 3]
top-5     : [999999209, 999998869, 999998609, 999997479, 999996893] equals sort = true
```

첫째, `iterator()`·`stream()`·`toString()` 은 내부 배열을 앞에서부터 읽는다. 힙 조건은 부모와 자식 사이에만 성립하므로 결과는 정렬되어 있지 않다. 정렬된 순서가 필요하면 `poll` 을 반복하거나, 큐를 비우면 안 될 때는 복사본을 비우거나 `stream().sorted(cmp)` 를 쓴다.

둘째, 힙은 안정적이지 않다. 우선순위가 모두 같은 작업을 a 부터 e 까지 넣고 하나를 꺼낸 뒤 f 를 넣었더니 `aefdcb` 순서로 나왔다. `poll` 이 마지막 원소를 루트로 옮기기 때문이다. 같은 우선순위 안에서 접수 순서를 지켜야 하는 작업 큐라면 단조 증가하는 순번을 2차 정렬 키로 넣는다. `ThreadPoolExecutor` 에 `PriorityBlockingQueue` 를 쓸 때도 같은 처리가 필요하다.

셋째, 큐에 들어 있는 객체의 비교 기준 필드를 바꾸면 힙은 그 사실을 모른다. 예제에서 루트의 키를 10 으로 바꾼 뒤에도 그 원소가 가장 먼저 나왔다. 우선순위를 바꾸려면 `remove` 후 다시 `offer` 하거나 5절의 지연 삭제를 쓰고, 큐에 넣는 타입은 `record` 처럼 불변으로 만들어 실수 자체를 막는다.

Top-K 는 힙의 대표적인 용도다. 가장 큰 K 개를 구할 때 크기 K 의 최소 힙을 유지하면 루트가 "현재 K 등" 이 되어, 새 값이 루트보다 클 때만 교체하면 된다. 전체 정렬의 O(n log n) 과 달리 O(n log K) 이고 메모리는 K 개만 쓴다. 100만 개에서 상위 5개를 구한 결과가 정렬 결과와 일치함을 확인했다.

## 5. remove(Object) 는 선형이다: 지연 삭제

`PriorityQueue.remove(Object)` 와 `contains` 는 배열을 처음부터 훑는 `indexOf` 를 쓴다. 위치를 찾은 뒤의 재정렬은 O(log n) 이지만 탐색이 O(n) 이다. 타이머 취소처럼 임의 원소 삭제가 잦으면 이 비용이 지배적이 된다. 지연 삭제는 취소된 항목의 식별자만 집합에 기록하고, `poll` 로 꺼낸 시점에 취소 여부를 확인해 버린다.

```java
import java.util.*;

public class LazyDeletion {
    record Timer(long id, long fireAt) {}

    public static void main(String[] args) {
        int n = 200_000;
        Random rnd = new Random(3);
        List<Timer> timers = new ArrayList<>();
        for (int i = 0; i < n; i++) timers.add(new Timer(i, rnd.nextInt(1_000_000)));
        List<Timer> toCancel = new ArrayList<>(timers.subList(0, n / 2));
        Collections.shuffle(toCancel, rnd);
        Comparator<Timer> order = Comparator.comparingLong(Timer::fireAt).thenComparingLong(Timer::id);

        // A) remove(Object): 선형 탐색 후 재정렬
        PriorityQueue<Timer> eager = new PriorityQueue<>(order);
        eager.addAll(timers);
        long t0 = System.nanoTime();
        for (Timer t : toCancel.subList(0, 20_000)) eager.remove(t);
        long eagerMs = (System.nanoTime() - t0) / 1_000_000;

        // B) 지연 삭제: 취소 집합에 표시만 하고 poll 시점에 건너뛴다
        PriorityQueue<Timer> lazy = new PriorityQueue<>(order);
        lazy.addAll(timers);
        Set<Long> cancelled = new HashSet<>();
        long t1 = System.nanoTime();
        for (Timer t : toCancel) cancelled.add(t.id());
        long fired = 0, checksum = 0;
        while (!lazy.isEmpty()) {
            Timer t = lazy.poll();
            if (cancelled.remove(t.id())) continue;   // 취소된 항목은 버린다
            fired++; checksum += t.fireAt();
        }
        long lazyMs = (System.nanoTime() - t1) / 1_000_000;

        long expected = timers.subList(n / 2, n).stream().mapToLong(Timer::fireAt).sum();
        System.out.println("remove(Object) x 20,000 : " + eagerMs + " ms");
        System.out.println("lazy cancel x 100,000 + drain 200,000 : " + lazyMs + " ms");
        System.out.println("fired=" + fired + ", checksum ok=" + (checksum == expected));
    }
}
```

```text
remove(Object) x 20,000 : 5977 ms
lazy cancel x 100,000 + drain 200,000 : 150 ms
fired=100000, checksum ok=true
```

20만 개가 든 큐에서 2만 건을 `remove` 하는 데 약 6초가 걸렸고, 지연 삭제는 10만 건 취소와 전체 20만 건 배출을 합쳐 0.15초였다(2회 실행에서 각각 5977·6055ms, 150·208ms). 지연 삭제의 대가는 취소된 항목이 꺼내질 때까지 힙에 남는다는 점이다. 취소 비율이 높고 만료 시각이 먼 타이머가 많으면 힙이 죽은 항목으로 부풀므로, 죽은 항목 수가 전체의 절반을 넘으면 살아 있는 항목만으로 힙을 다시 만드는 압축 단계를 둔다. 재구성은 heapify 로 O(n) 이다.

위치를 알면 O(log n) 삭제가 가능하다는 점을 이용하는 방법도 있다. 원소가 자기 배열 인덱스를 필드로 갖고 힙이 이동할 때마다 갱신하는 인덱스드 힙이며, `ScheduledThreadPoolExecutor` 의 내부 지연 큐가 이 방식으로 작업 취소를 처리한다.

## 6. 선택 기준

`PriorityQueue` 는 스레드 안전하지 않다. 여러 스레드가 쓰면 `PriorityBlockingQueue`(잠금 하나로 보호, 용량 무제한)를, 만료 시각 기준으로 꺼내야 하면 `DelayQueue` 를 쓴다. 최솟값과 최댓값이 모두 필요하거나 임의 원소의 삭제·범위 조회가 필요하면 힙이 아니라 `TreeMap`(중복 키는 값을 개수나 목록으로)이 맞다. 연산은 모두 O(log n) 이 되지만 탐색과 삭제도 O(log n) 이다. 우선순위 종류가 몇 개로 고정되어 있으면 우선순위별 `ArrayDeque` 배열이 O(1) 이면서 FIFO 도 자연스럽게 지켜진다.

다익스트라 구현에서 "거리 갱신 시 기존 항목을 `remove` 하지 않고 새 항목을 넣고, 꺼냈을 때 기록된 거리보다 크면 버린다" 는 관용구는 5절의 지연 삭제와 같은 기법이다. 힙 크기가 간선 수까지 늘 수 있지만 `remove` 의 선형 탐색을 피하는 쪽이 훨씬 빠르다.

검증 환경: OpenJDK 21.0.12, Linux x86_64, 2 vCPU. 비교 횟수와 순서 출력은 고정 시드로 재현되는 결정적인 값이고, 5절의 밀리초 값은 실행 환경에 따라 달라진다.

## 참고

- Java SE 21 API, PriorityQueue: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/PriorityQueue.html
- OpenJDK 21 소스, java/util/PriorityQueue.java: https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/PriorityQueue.java
- Java SE 21 API, PriorityBlockingQueue: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/PriorityBlockingQueue.html
- Thomas H. Cormen 외, Introduction to Algorithms 4판, 6장 Heapsort, MIT Press, 2022
- Robert W. Floyd, Algorithm 245: Treesort 3, Communications of the ACM 7(12), 1964
- Robert Sedgewick, Kevin Wayne, Algorithms 4판, 2.4 Priority Queues: https://algs4.cs.princeton.edu/24pq/
