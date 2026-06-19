Notion 원본: https://www.notion.so/3845a06fd6d381dcaaffedc7b4d9995c

# Java NIO Selector 다중화와 Zero-Copy transferTo 및 Direct ByteBuffer 관리

> 2026-06-19 신규 주제 · 확장 대상: JAVA

## 학습 목표

- Channel / Buffer / Selector 모델로 단일 스레드 다중 연결 처리를 구현한다
- Selector의 readiness 기반 I/O 다중화와 epoll 매핑 관계를 파악한다
- transferTo / transferFrom의 zero-copy 원리를 설명한다
- Direct ByteBuffer의 off-heap 메모리 수명과 정리(Cleaner) 문제를 관리한다

## 1. NIO의 핵심: Channel과 Buffer

전통 java.io는 스트림 기반이고 블로킹이라 한 연결당 한 스레드가 필요해 연결이 수만 개면 스레드도 수만 개가 된다. NIO는 Channel과 Buffer로 재설계됐다. Channel은 양방향 통로고 모든 데이터는 Buffer를 통해 블록 단위로 오간다. Buffer는 position, limit, capacity로 상태를 관리하며 쓰기 후 읽으려면 flip()으로 전환한다.

```java
ByteBuffer buf = ByteBuffer.allocate(1024);
int n = channel.read(buf);
buf.flip();
while (buf.hasRemaining()) System.out.print((char) buf.get());
buf.clear();
```

flip()은 쓴 만큼만 읽겠다, clear()는 비우고 처음부터, compact()는 안 읽은 데이터를 앞으로 당기고 이어 쓰겠다는 의미다.

## 2. Selector: 단일 스레드 I/O 다중화

하나의 Selector에 여러 채널을 non-blocking으로 등록하면 한 스레드가 select() 한 번으로 준비된 채널들을 한꺼번에 알아낸다. reactor 패턴의 토대다.

```java
Selector selector = Selector.open();
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);
server.register(selector, SelectionKey.OP_ACCEPT);
while (true) {
	selector.select();
	Iterator<SelectionKey> it = selector.selectedKeys().iterator();
	while (it.hasNext()) {
		SelectionKey key = it.next();
		it.remove();
		if (key.isAcceptable()) { /* accept + register OP_READ */ }
		else if (key.isReadable()) { /* handleRead */ }
	}
}
```

it.remove()를 빠뜨리면 중복 처리와 무한 루프가 된다. OP_WRITE는 송신 버퍼가 거의 항상 비어 busy-loop를 유발하므로 보낼 데이터가 있을 때만 일시 등록한다.

## 3. Selector와 OS의 epoll 매핑

Linux에서 JVM은 Selector를 epoll로 구현한다. 구식 select/poll은 매 호출마다 전체 fd 집합을 커널에 넘기고 선형 스캔해 O(n)이지만, epoll은 epoll_ctl로 관심 fd를 한 번 등록해 두고 epoll_wait가 준비된 fd만 돌려줘 활성 연결 수에 비례한다.

| 메커니즘 | fd 등록 | 조회 비용 | NIO 매핑 |
|---|---|---|---|
| select/poll | 매 호출 전체 | O(전체 fd) | 레거시 |
| epoll(Linux) | epoll_ctl 1회 | O(활성 fd) | EPollSelectorImpl |
| kqueue(macOS) | kevent | O(활성 fd) | KQueueSelectorImpl |

Netty는 JNI로 직접 epoll을 호출하는 네이티브 트랜스포트를 제공해 edge-triggered 등 세밀 제어와 GC 압력 감소를 노린다.

## 4. Zero-Copy: transferTo / transferFrom

전통적 read+write는 디스크에서 커널, 사용자, 다시 커널 소켓 버퍼로 복사가 과하다. FileChannel.transferTo()는 데이터를 사용자 공간에 올리지 않고 커널 내부에서 바로 소켓으로 보낸다(Linux sendfile).

```java
long position = 0, size = fileChannel.size();
while (position < size) {
	position += fileChannel.transferTo(position, size - position, socketChannel);
}
```

이득은 사용자와 커널 사이 복사 제거, 컨텍스트 스위치 감소, CPU 부담 경감이다. transferTo는 한 번에 전부 보내지 않을 수 있어 반환 바이트로 루프를 돌려야 한다. 반대 방향은 transferFrom을 쓴다.

## 5. Direct ByteBuffer와 off-heap 메모리

allocate()는 JVM 힙에 배열을 만들고 I/O 시 네이티브 메모리로 잠시 복사해야 한다. allocateDirect()는 off-heap에 잡아 이 복사를 생략한다.

```java
ByteBuffer direct = ByteBuffer.allocateDirect(64 * 1024);
```

하지만 할당·해제 비용이 비싸고 힙 통계에 안 잡힌다. 정리는 GC가 직접 하지 않고 Cleaner가 처리해 힙 수거 시점에 의존한다. 따라서 풀링해 재사용하는 것이 정석이다(Netty PooledByteBufAllocator). 총량은 -XX:MaxDirectMemorySize로 제한하며 초과 시 OutOfMemoryError: Direct buffer memory가 난다.

## 6. 실전 설계: Reactor와 다중 Selector

단일 Selector는 코어 하나만 쓴다. multi-reactor에서 acceptor 스레드가 OP_ACCEPT만 처리하고 수락한 연결을 워커 스레드 풀의 여러 Selector에 분산 등록한다.

```java
SocketChannel client = server.accept();
client.configureBlocking(false);
workers[next++ % workers.length].register(client); // wakeup 필요
```

다른 스레드가 도는 Selector에 등록하려면 selector.wakeup()으로 깨워야 즉시 반영된다. interestOps 변경은 해당 Selector 스레드에서 하는 것이 안전하다.

## 7. NIO vs 가상 스레드 시대의 선택

JDK 21 가상 스레드(Loom)는 블로킹 코드를 그대로 쓰면서 높은 동시성을 낸다. 그렇다고 NIO/Selector가 무의미해진 것은 아니다. 최저 지연과 최대 처리량이 필요한 네트워크 인프라(프록시, 게이트웨이, 브로커)에서는 NIO와 다이렉트 버퍼, zero-copy가 여전히 정밀 제어의 우위가 있다. 가상 스레드도 내부적으로 블로킹 시 NIO non-blocking I/O로 언마운트된다.

## 8. MappedByteBuffer: 메모리 매핑 파일 I/O

FileChannel.map()은 파일을 가상 메모리에 매핑해 MappedByteBuffer로 돌려준다(Linux mmap). 대용량 파일을 힙에 올리지 않고도 임의 위치 접근이 빠르고 페이지 캐시를 공유한다(Kafka 로그 세그먼트).

```java
MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_WRITE, 0, ch.size());
mbb.put(0, (byte) 0x42);
mbb.force();
```

매핑 해제 시점이 명시적이지 않고 GC에 의존한다(JDK 14+ Foreign Memory API로 결정적 해제). 매핑 크기는 Integer.MAX_VALUE(약 2GB) 제한. force()는 fsync에 준하므로 배치로 모아 호출한다.

| 방식 | 복사 경로 | 적합 상황 |
|---|---|---|
| heap buffer | 커널 유저 복사 다수 | 작은 데이터 |
| Direct ByteBuffer | 유저 복사 생략 | 네트워크 I/O 반복 |
| transferTo | 커널 내부 전송 | 파일에서 소켓 |
| MappedByteBuffer | 페이지 캐시 직접 | 대용량 랜덤 접근 |

## 참고

- Java Platform SE 문서 — java.nio.channels (Selector, SocketChannel, FileChannel)
- Java Platform SE 문서 — java.nio.ByteBuffer / MappedByteBuffer
- Linux man-pages — epoll(7), sendfile(2)
- Netty 공식 문서 — Native transports
- JEP 444 — Virtual Threads
