Notion 원본: https://app.notion.com/p/3ac5a06fd6d38195b613e6672d571761

# Java NIO Channel과 Selector 및 논블로킹 다중화 I/O

> 2026-07-30 신규 주제 · 확장 대상: JAVA(문법·스레드 이후의 I/O 모델 심화)

## 학습 목표

- 스트림 기반 blocking I/O 와 Channel·Buffer 기반 NIO 의 데이터 흐름 차이를 구분한다.
- `Selector` 가 하나의 스레드로 수천 연결을 다중화하는 원리를 OS `epoll` 과 연결해 정리한다.
- `ByteBuffer` 의 position·limit·capacity 와 flip/compact 규약을 정확히 파악한다.
- 논블로킹 에코 서버를 직접 구현해 이벤트 루프의 준비 상태 처리와 부분 쓰기 문제를 검증한다.

## 1. 왜 blocking I/O 는 스레드를 잡아먹는가

전통적 `java.io` 는 스트림이 데이터를 기다리는 동안 호출 스레드를 **블록** 한다. 연결 하나에 스레드 하나(thread-per-connection)를 배정하면, 1만 연결은 1만 스레드다. 각 스레드가 스택으로 수백 KB~1MB 를 잡고, 컨텍스트 스위칭 비용이 커지며, 대부분의 스레드는 실제론 데이터를 "기다리기만" 한다. CPU 는 노는데 스레드 자원이 고갈되는 구조다.

NIO(New I/O, JDK 1.4~)는 이 낭비를 겨냥한다. 소수의 스레드가 `Selector` 로 여러 채널의 준비 상태를 한꺼번에 감시하고, "읽을 게 있는" 채널만 처리한다. 기다림은 커널에 위임하고 스레드는 실제 일이 있을 때만 깨어난다.

## 2. Channel 과 Buffer — 양방향, 버퍼 경유

`java.io` 스트림은 단방향(InputStream/OutputStream)이고 바이트를 직접 흘려보낸다. Channel 은 **양방향**이며 데이터는 항상 `Buffer` 를 경유한다. 채널에서 읽으면 버퍼로 들어오고, 채널에 쓰면 버퍼에서 나간다. 이 버퍼 중심 설계가 후술할 flip/compact 규약의 근원이다.

```java
try (FileChannel ch = FileChannel.open(Path.of("data.bin"), StandardOpenOption.READ)) {
    ByteBuffer buf = ByteBuffer.allocate(1024);
    int n = ch.read(buf);        // 채널 → 버퍼 (쓰기 모드로 채워짐)
    buf.flip();                  // 읽기 모드로 전환
    while (buf.hasRemaining()) {
        System.out.print((char) buf.get());
    }
}
```

`FileChannel` 은 파일용, `SocketChannel`/`ServerSocketChannel` 은 TCP 용, `DatagramChannel` 은 UDP 용이다. 소켓 채널은 `configureBlocking(false)` 로 논블로킹 전환이 가능하지만 FileChannel 은 항상 blocking 이다(파일 I/O 는 OS 레벨에서 논블로킹 셀렉터 대상이 아니다).

## 3. ByteBuffer 의 세 지표 — position, limit, capacity

버퍼 버그의 90%는 이 세 지표의 상태를 혼동해서 생긴다. `capacity` 는 총 크기(불변), `position` 은 다음 읽기/쓰기 위치, `limit` 는 접근 가능한 경계다. 같은 버퍼가 "쓰기 모드"와 "읽기 모드"를 오가며, `flip()` 이 그 전환의 핵심이다.

```java
ByteBuffer buf = ByteBuffer.allocate(8);
// 초기: position=0, limit=8, capacity=8  (쓰기 모드)
buf.put((byte)'A').put((byte)'B').put((byte)'C');
// position=3, limit=8

buf.flip();
// flip: limit=position(3), position=0  → 읽기 모드, "방금 쓴 3바이트만" 읽음
byte b = buf.get();          // 'A', position=1

buf.compact();
// 안 읽은 나머지(B,C)를 앞으로 당기고 position=2, limit=8 → 다시 쓰기 모드
buf.put((byte)'D');          // BCD... 이어쓰기
```

핵심 규약: **쓴 뒤 읽으려면 `flip()`, 다 읽고 다시 쓰려면 `clear()`(전체 재사용) 또는 `compact()`(안 읽은 부분 보존)**. 소켓 프로그래밍에서 한 번에 완전한 메시지가 안 올 수 있으므로, 부분만 읽고 나머지를 보존하는 `compact()` 가 특히 중요하다.

`allocateDirect()` 로 만든 다이렉트 버퍼는 JVM 힙 밖(OS 네이티브 메모리)에 잡혀 커널 복사를 줄이지만, 할당·해제 비용이 크고 GC 로 즉시 회수되지 않는다. 크고 오래 쓰는 I/O 버퍼에만 선택적으로 쓴다.

## 4. Selector — 하나의 스레드로 다중화

`Selector` 는 여러 채널을 등록해두고, 그중 I/O 준비가 된 채널 집합을 한 번의 `select()` 호출로 받아온다. 내부적으로 리눅스에서는 `epoll`, macOS/BSD 는 `kqueue`, 구형에서는 `poll` 로 매핑된다. 채널은 `SelectionKey` 로 관심 연산(OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)을 표시하고 등록된다.

```java
Selector selector = Selector.open();
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);
server.register(selector, SelectionKey.OP_ACCEPT);  // 연결 수락 준비 감시
```

`select()` 는 준비된 채널이 하나도 없으면 블록한다(스레드는 여기서만 쉰다). 준비되면 `selectedKeys()` 로 준비된 키 집합을 돌며 각각의 연산을 처리한다. 처리 후 반드시 키를 집합에서 **제거** 해야 다음 라운드에서 중복 처리되지 않는다.

## 5. 논블로킹 에코 서버 — 이벤트 루프 전체

```java
public class EchoServer {
    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(8080));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            selector.select();                       // 준비된 채널 대기
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();                         // 중복 처리 방지

                if (key.isAcceptable()) {
                    SocketChannel client = server.accept();
                    client.configureBlocking(false);
                    // 클라이언트별 상태(버퍼)를 attachment 로 보관
                    client.register(selector, SelectionKey.OP_READ,
                            ByteBuffer.allocate(256));
                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer buf = (ByteBuffer) key.attachment();
                    int n = client.read(buf);
                    if (n == -1) {                   // 상대가 연결 종료
                        client.close();
                        continue;
                    }
                    buf.flip();
                    client.write(buf);               // 에코 (부분 쓰기 주의 — §6)
                    buf.compact();                   // 못 쓴 나머지 보존
                }
            }
        }
    }
}
```

논블로킹에서 `read()` 는 즉시 반환한다 — 읽을 게 없으면 0, 연결 종료면 -1, 데이터가 있으면 읽은 바이트 수. 각 클라이언트의 버퍼를 `SelectionKey.attachment()` 에 매달아 상태를 유지하는 패턴이 핵심이다. 스레드는 하나지만 수천 연결을 이 루프 하나가 처리한다.

## 6. 부분 쓰기와 OP_WRITE 의 함정

논블로킹 `write()` 는 커널 송신 버퍼가 가득 차면 요청한 바이트를 **다 못 쓰고** 일부만 쓴 채 반환한다. 위 예제처럼 한 번 `write()` 로 끝냈다고 가정하면 데이터 유실이 생긴다. 제대로 하려면 다 못 쓴 경우 `OP_WRITE` 관심을 등록해 "쓸 수 있게 되면" 다시 나머지를 쓰도록 해야 한다.

```java
buf.flip();
client.write(buf);
if (buf.hasRemaining()) {
    // 커널 버퍼가 찼다 → 쓰기 가능해질 때 알림 요청
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
} else {
    key.interestOps(SelectionKey.OP_READ);   // 다 썼으면 읽기만 감시
}
```

주의: `OP_WRITE` 는 소켓이 쓰기 가능한 동안 계속 준비 상태로 뜬다(대부분 시간 쓰기 가능하므로). 항상 등록해두면 CPU 를 태우는 busy loop 가 된다. 그래서 "쓸 게 남았을 때만" 등록하고, 다 쓰면 즉시 해제하는 것이 규칙이다.

## 7. NIO 를 직접 쓸 것인가 — Netty·가상 스레드와의 관계

NIO 셀렉터 루프를 손으로 짜는 것은 정확하지만 오류가 나기 쉽다(부분 쓰기, 키 관리, 예외 처리, 프로토콜 프레이밍). 실무에서는 이 위에 추상화를 얹은 **Netty** 가 사실상 표준이다. Netty 는 EventLoopGroup, ChannelPipeline, ByteBuf(참조 카운팅 버퍼)로 위 함정들을 캡슐화한다.

한편 JDK 21 의 가상 스레드는 흥미로운 반전을 만든다. 가상 스레드에서 blocking I/O 를 호출하면 JDK 가 내부적으로 논블로킹 + 파킹으로 변환해, "thread-per-connection 의 단순한 코드"로도 셀렉터급 확장성을 얻는다. 즉 애플리케이션 로직은 blocking 스타일로 짜고 런타임이 NIO 다중화를 대신 해준다. 그럼에도 NIO 의 Channel/Buffer/Selector 모델을 이해해야 하는 이유는, 가상 스레드·Netty·리액터 라이브러리 모두 이 계층 위에 서 있고, 부분 읽기/쓰기·버퍼 지표 같은 실패 양상은 추상화 밑에서 여전히 동일하게 작동하기 때문이다.

## 8. 프로토콜 프레이밍 — TCP 는 메시지 경계를 모른다

논블로킹 서버에서 가장 흔한 논리 버그는 "한 번의 read 로 완전한 메시지가 온다"고 가정하는 것이다. TCP 는 바이트 스트림일 뿐 메시지 경계가 없다. 애플리케이션이 보낸 하나의 논리 메시지가 여러 read 에 쪼개져 오거나(단편화), 반대로 여러 메시지가 한 read 에 뭉쳐 올 수 있다(뭉침). 그래서 수신 측은 **프레이밍** 규약으로 경계를 스스로 복원해야 한다. 대표 방식은 길이 접두사(length-prefix)다.

```java
// 4바이트 길이 접두사 + 본문 프레이밍
ByteBuffer acc = (ByteBuffer) key.attachment();  // 클라이언트별 누적 버퍼
client.read(acc);
acc.flip();
while (acc.remaining() >= 4) {
    acc.mark();
    int len = acc.getInt();               // 본문 길이
    if (acc.remaining() < len) {          // 본문이 아직 다 안 옴
        acc.reset();                      // 길이 읽기 전으로 되돌림
        break;                            // 다음 read 를 기다림
    }
    byte[] body = new byte[len];
    acc.get(body);
    handleMessage(body);                  // 완전한 메시지 하나 처리
}
acc.compact();                            // 남은 부분 조각 보존
```

핵심은 `mark()`/`reset()` 으로 "완전한 프레임이 확보되지 않으면 소비하지 않고 되돌린다"는 것이다. 이 규율이 없으면 부분 프레임을 잘못 해석해 프로토콜이 어긋난다. Netty 의 `LengthFieldBasedFrameDecoder` 가 정확히 이 로직을 재사용 가능하게 캡슐화한 것이다.

## 9. 스캐터/개더와 zero-copy transferTo

NIO 는 여러 버퍼를 한 번의 시스템 콜로 처리하는 스캐터(scatter)·개더(gather)를 지원한다. 헤더 버퍼와 본문 버퍼를 따로 두고 `write(ByteBuffer[])` 로 한 번에 내보내면, 사용자 공간에서 버퍼를 합치는 복사를 줄인다. 파일 전송에서는 `FileChannel.transferTo` 가 더 극적이다.

```java
// 파일을 소켓으로 zero-copy 전송 — 커널 내부에서 직접 전달
try (FileChannel file = FileChannel.open(Path.of("big.bin"), StandardOpenOption.READ)) {
    long size = file.size(), pos = 0;
    while (pos < size) {
        pos += file.transferTo(pos, size - pos, socketChannel);
    }
}
```

`transferTo` 는 리눅스의 `sendfile` 로 매핑되어, 파일 데이터가 커널 페이지 캐시에서 소켓 버퍼로 **사용자 공간을 거치지 않고** 이동한다. read→write 로 직접 하면 커널→유저→커널 복사가 두 번 일어나는데, 이걸 제거해 대용량 정적 파일 서빙 처리량을 크게 높인다. 반환값이 요청량보다 작을 수 있으므로(소켓 버퍼 포화) 위처럼 루프로 남은 만큼 반복해야 하는 점은 부분 쓰기와 같은 주의사항이다.

## 참고

- Java Platform SE API — `java.nio.channels.Selector`, `ByteBuffer`, `SocketChannel`
- Ron Hitchens, *Java NIO* (O'Reilly)
- Netty User Guide — ByteBuf, EventLoop
- JEP 444: Virtual Threads (blocking I/O 의 논블로킹 변환 설명)
