Notion 원본: https://app.notion.com/p/39d5a06fd6d3811c9fc6cc9dce194c64

# Java NIO Selector와 이벤트 루프 및 Zero-Copy transferTo

> 2026-07-14 신규 주제 · 확장 대상: JAVA

## 학습 목표

- 블로킹 I/O와 NIO 논블로킹 채널의 스레드 모델 차이를 커널 관점에서 구분한다
- `Selector`가 epoll/kqueue 위에서 다중 채널을 감시하는 이벤트 루프를 구현한다
- `SelectionKey`의 interestOps와 readyOps 전이를 추적한다
- `transferTo`/`transferFrom`의 zero-copy 경로와 실제 절감량을 이해한다

## 1. 블로킹 I/O의 한계와 NIO의 전제

전통적 `java.io`는 스트림당 스레드 하나를 묶는다. `read()`는 데이터가 도착할 때까지 스레드를 블로킹하고, 커넥션 1만 개를 처리하려면 스레드 1만 개가 필요해 C10K 문제가 생긴다. NIO는 채널을 논블로킹으로 두고 커널의 다중화 시스템 콜(리눅스 epoll, macOS kqueue, 구형 select/poll)로 준비된 채널만 골라 처리한다. 스레드 하나가 수천 커넥션의 준비 상태를 감시하다가 준비된 것만 처리하므로 I/O 대기에 스레드가 놀지 않는다.

```java
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);   // 논블로킹 필수
```

## 2. Selector와 SelectionKey 모델

`Selector`는 여러 채널을 등록받아 한 번의 `select()` 호출로 준비된 채널 집합을 돌려주는 다중화기다. 채널을 등록하면 SelectionKey가 생성되며 두 비트마스크를 가진다. `interestOps`는 감시하고 싶은 이벤트(OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)이고, `readyOps`는 지금 준비된 이벤트다.

```java
Selector selector = Selector.open();
server.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    selector.select();
    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();                 // 반드시 제거 — 안 하면 재처리됨
        if (key.isAcceptable()) accept(key);
        else if (key.isReadable()) read(key);
    }
}
```

가장 흔한 버그는 `it.remove()` 누락이다. `selectedKeys()`는 커널이 비워주지 않으므로 처리한 키를 직접 제거하지 않으면 다음 루프에서 오작동한다.

## 3. 이벤트 루프 완성: accept와 read

OP_ACCEPT가 준비되면 새 커넥션을 수락해 OP_READ로 재등록하고, 읽기 준비 시 반환값 -1(EOF)로 커넥션 종료를 처리한다.

```java
void read(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    ByteBuffer buf = (ByteBuffer) key.attachment();
    int n = client.read(buf);
    if (n == -1) { key.cancel(); client.close(); return; }
    buf.flip();
    // ... 처리 ...
    buf.compact();
}
```

쓰기는 미묘하다. 커널 송신 버퍼가 가득 차면 `write()`가 일부만 쓰므로 남은 데이터를 보관하고 OP_WRITE를 켜뒀다가 다 보내면 꺼야 한다. OP_WRITE는 대개 항상 준비 상태라 계속 켜두면 busy-spin이 된다.

## 4. epoll 위의 select 동작

리눅스에서 Selector는 EPollSelectorImpl을 쓴다. 등록은 epoll_ctl(ADD), `select()`는 epoll_wait로 매핑된다. epoll은 관심 fd 집합을 커널에 등록해두고 준비된 것만 O(1)에 가깝게 돌려줘 select/poll의 O(n) 스캔보다 확장성이 좋다. 리눅스 epoll은 레벨 트리거라 버퍼에 데이터가 남으면 계속 준비로 보고되어 유실이 없다. Netty 등은 엣지 트리거를 선택할 수 있는데 이 경우 반드시 EAGAIN까지 반복해 읽어야 한다.

## 5. Buffer의 flip/compact 상태 기계

ByteBuffer는 position, limit, capacity 세 포인터의 상태 기계다. 쓰기 후 `flip()`은 limit을 현재 position으로, position을 0으로 옮겨 읽기 모드로 전환한다. 읽기 후 `clear()`(전체 재사용) 또는 `compact()`(미처리 보존)로 되돌린다.

```java
buf.put(data);
// buf.flip();  ← 빼면 position=limit이라 읽을 게 없음
while (buf.hasRemaining()) channel.write(buf);
```

`allocateDirect`로 만든 다이렉트 버퍼는 네이티브 메모리에 할당되어 커널 교환 시 복사를 줄이지만 할당·해제 비용이 크므로 오래 재사용하는 소켓 버퍼에 적합하다.

## 6. Zero-Copy: transferTo의 실체

파일을 소켓으로 보내는 전형적 코드는 네 번 복사와 두 번 컨텍스트 스위치를 유발한다. 유저 공간을 경유하는 두 번의 CPU 복사가 낭비다. `FileChannel.transferTo`는 리눅스 `sendfile(2)`로 매핑되어 데이터가 유저 공간을 거치지 않고 커널 페이지 캐시에서 소켓으로 직접 이동한다. scatter-gather DMA가 지원되면 CPU 복사가 0회가 된다.

```java
FileChannel file = FileChannel.open(path, StandardOpenOption.READ);
long position = 0, count = file.size();
while (position < count) {
    long transferred = file.transferTo(position, count - position, socket);
    position += transferred;
}
```

| 방식 | CPU 복사 | 컨텍스트 스위치 |
|---|---|---|
| read+write | 2회 | 4회 |
| mmap+write | 1회 | 4회 |
| transferTo(sendfile) | 1회 | 2회 |
| transferTo + SG-DMA | 0회 | 2회 |

## 7. 실측 효과와 제약

대용량 순차 전송에서 sendfile은 처리량을 크게 올리고 CPU를 절반 이하로 낮춘다. Kafka가 로그 세그먼트를 컨슈머로 보낼 때 `transferTo`를 쓰는 이유다. 다만 데이터를 변환(압축·암호화)해야 하면 유저 공간에서 손대야 하므로 zero-copy를 못 쓴다. TLS는 kTLS 오프로드가 있어야 결합되고, `transferTo`는 한 번에 옮길 바이트 제한이 있어 반드시 루프로 감싸야 한다.

## 8. 언제 무엇을 쓸 것인가

raw NIO를 직접 짜는 것은 키 미제거, OP_WRITE busy-spin, 부분 쓰기 처리 누락으로 악명 높아 실무에서는 Netty를 쓴다. 커넥션이 적고 블로킹 중심이면 Virtual Threads(Loom) + 블로킹 I/O가 단순하면서 확장성을 확보하는 대안이다. Zero-copy `transferTo`는 변환 없는 대용량 파일 전송이라는 조건에서만 켠다.

## 참고

- Java SE — java.nio.channels.Selector: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Selector.html
- Linux man page — sendfile(2): https://man7.org/linux/man-pages/man2/sendfile.2.html
- Linux man page — epoll(7): https://man7.org/linux/man-pages/man7/epoll.7.html
- IBM Developer — Efficient data transfer through zero copy: https://developer.ibm.com/articles/j-zerocopy/
