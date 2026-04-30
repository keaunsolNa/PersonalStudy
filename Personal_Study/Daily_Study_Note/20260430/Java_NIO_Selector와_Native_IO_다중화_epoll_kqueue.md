Notion 원본: https://app.notion.com/p/3525a06fd6d38180901ad959f5b5a7fa

# Java NIO Selector와 Native I/O 다중화 (epoll·kqueue)

> 2026-04-30 신규 주제 · 확장 대상: JAVA

## 학습 목표

- `Selector` 가 JDK 내부에서 `epoll_wait` / `kqueue` 호출로 매핑되는 경로 추적
- `SelectionKey` 의 `interestOps` / `readyOps` 비트마스크가 커널 이벤트와 어떻게 일치하는지 분석
- Edge-Triggered 와 Level-Triggered 모드 차이가 Java 구현에 미치는 영향 파악
- Reactor 패턴으로 단일 스레드 5만 동시 연결을 처리하는 서버 구현

## 1. NIO Selector 가 해결하는 문제

전통적 BIO 모델은 한 소켓당 한 스레드를 점유한다. 1만 동시 연결 = 1만 스레드 = 약 10GB 스택(스레드당 1MB 기본). NIO 의 `Selector` 는 단일 스레드가 N개 채널을 감시하다가 "읽기 준비된 채널 목록" 만 반환받는 모델이다. 커널이 이미 "어떤 fd가 깨어났는지" 알고 있으니, 사용자 공간이 폴링 루프를 돌릴 필요가 없다.

리눅스에서 이 책임을 지는 시스템콜이 `epoll`, FreeBSD/macOS에서는 `kqueue`, 윈도우는 `IOCP` 다. JDK는 OS별로 `sun.nio.ch.EPollSelectorImpl`, `sun.nio.ch.KQueueSelectorImpl`, `sun.nio.ch.WindowsSelectorImpl` 을 골라 끼운다. `SelectorProvider.provider()` 가 부팅 시점에 OS를 보고 결정하며, 사용자 코드는 `Selector.open()` 한 줄만 쓴다.

## 2. epoll 의 세 시스템콜 매핑

`epoll` 은 세 콜의 조합이다.

- `epoll_create1(EPOLL_CLOEXEC)` → 커널에 fd 감시 테이블 생성. JDK `EPollSelectorImpl()` 생성자에서 호출.
- `epoll_ctl(epfd, EPOLL_CTL_ADD/MOD/DEL, fd, &ev)` → 감시 fd 등록·수정·삭제. JDK `SelectionKeyImpl.interestOps(int)` 가 `Net.translateInterestOps()` 를 거쳐 `EPOLLIN | EPOLLOUT | EPOLLET` 비트로 변환된 뒤 `epoll_ctl` 호출.
- `epoll_wait(epfd, events, maxEvents, timeout)` → 준비된 fd 목록 반환. JDK `select()` / `select(timeout)` 의 본체.

JDK 내부 `EPoll.epollWait(epfd, addr, len, timeout)` 는 native 메서드로 `linux/native/libnio/ch/EPoll.c` 에 구현되어 있다. 반환된 `events[]` 의 각 항목 `epoll_event { uint32_t events; epoll_data_t data; }` 에서 `data.fd` 로 어떤 채널이 깨어났는지 식별한다.

```java
// 핵심 흐름 의사코드 (EPollSelectorImpl 내부)
int n = EPoll.wait(epfd, pollArrayAddr, NUM_EPOLLEVENTS, timeout);
for (int i = 0; i < n; i++) {
    int fd     = EPoll.getDescriptor(pollArrayAddr, i);
    int events = EPoll.getEvents(pollArrayAddr, i);
    SelectionKeyImpl ski = fdToKey.get(fd);
    if (ski != null) {
        int rOps = Net.translateReadyOps(events, ski.interestOps(), ski);
        ski.nioReadyOps(rOps);
        selectedKeys.add(ski);
    }
}
```

`Net.translateReadyOps` 는 `EPOLLIN → OP_READ | OP_ACCEPT`, `EPOLLOUT → OP_WRITE | OP_CONNECT` 로 비트를 뒤집는다.

## 3. Level-Triggered 가 기본인 이유

`epoll` 은 LT(Level-Triggered)와 ET(Edge-Triggered) 두 모드를 갖는다. JDK는 **LT 를 기본**으로 쓰며, 명시적으로 ET 를 켜는 API가 공개돼 있지 않다(`EPOLLET` 플래그를 외부에서 set 할 방법이 없다).

- LT: fd가 "여전히 읽을 게 남아 있는 상태"이면 다음 `epoll_wait` 도 같은 fd를 또 깨운다. 사용자 코드가 한 번에 다 안 읽어도 데이터 유실이 없다.
- ET: 상태가 "비었음 → 있음" 으로 바뀌는 그 순간에만 한 번 깨운다. 사용자 코드는 `EAGAIN` 까지 루프 돌며 비우는 책임을 진다.

ET가 더 빠르지만(중복 wakeup 적음), 코드가 까다롭다. JDK가 LT를 고른 이유는 호환성. 모든 사용자 코드가 "한 번에 다 비우기"를 보장한다고 가정할 수 없다. Netty의 `EpollEventLoop` 는 JNI로 직접 `epoll_ctl(EPOLLET)` 을 부르므로 ET 동작을 쓴다(약 10~15% 처리량 우위).

## 4. SelectionKey 비트 ↔ 커널 이벤트 표

| SelectionKey 상수 | 비트값 | epoll | kqueue |
|---|---|---|---|
| OP_READ | 1 | EPOLLIN | EVFILT_READ |
| OP_WRITE | 4 | EPOLLOUT | EVFILT_WRITE |
| OP_CONNECT | 8 | EPOLLOUT | EVFILT_WRITE |
| OP_ACCEPT | 16 | EPOLLIN | EVFILT_READ |

`OP_CONNECT` 가 `EPOLLOUT` 으로 매핑되는 이유는 non-blocking connect 가 "성공/실패 상태를 쓰기 가능"으로 알리는 BSD 전통이다.

## 5. Reactor 패턴 단일 스레드 에코 서버

```java
public final class EchoReactor implements Runnable {
    private final Selector selector;
    private final ServerSocketChannel server;

    public EchoReactor(int port) throws IOException {
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        this.server.configureBlocking(false);
        this.server.bind(new InetSocketAddress(port), 1024);
        this.server.register(selector, SelectionKey.OP_ACCEPT);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                selector.select(1000L);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) {
            return;
        }
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(8192));
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer buf = (ByteBuffer) key.attachment();
        int n = ch.read(buf);
        if (n < 0) {
            ch.close();
            return;
        }
        if (n == 0) {
            return;
        }
        buf.flip();
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer buf = (ByteBuffer) key.attachment();
        ch.write(buf);
        if (!buf.hasRemaining()) {
            buf.clear();
            key.interestOps(SelectionKey.OP_READ);
        }
    }
}
```

`selectedKeys()` 가 `Set` 을 반환하지만 JDK가 자동으로 비워주지 않는다. `it.remove()` 를 깜빡하면 다음 select 에 같은 key가 또 들어 있어 중복 처리가 발생한다. 이 패턴은 NIO 사용자가 가장 자주 빠지는 함정이다.

## 6. wakeup() 과 Pipe 트릭

다른 스레드에서 selector 를 깨우려면 `selector.wakeup()` 을 부른다. 내부 구현은 OS별로 다르다.

- 리눅스: `eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC)` 를 만들어 selector 에 등록. wakeup 호출 시 `write(eventfd, &one, 8)`.
- macOS/BSD: `pipe2(fds, O_NONBLOCK | O_CLOEXEC)` 의 read end 를 등록하고 write end 에 1바이트 쓴다.
- 윈도우: 같은 호스트의 자기 자신을 가리키는 socket pair 를 만들어 더미 바이트 송신.

이 "wake fd" 는 항상 selector의 감시 대상에 묶여 있어 OS의 `select` 카운터를 1 늘린다. `Selector.keys().size()` 를 보면 등록한 채널 수보다 1 많은 이유가 이것이다.

## 7. Direct ByteBuffer 와 zero-copy

NIO 의 `read(ByteBuffer)` 가 heap buffer 를 받으면 JDK 는 **임시 direct buffer 를 잡아 native 메모리로 복사**한 뒤 `read(2)` 호출에 쓴다. heap 메모리는 GC가 옮길 수 있어 native 호출 동안 주소를 고정할 수 없기 때문이다. 그래서 핫패스에는 항상 `ByteBuffer.allocateDirect()` 를 쓴다.

`FileChannel.transferTo(target, position, count)` 는 `sendfile(2)` 시스템콜로 매핑된다. 디스크 파일 → 소켓 전송에서 사용자 공간 복사 0회로 떨어지며, 정적 파일 서버에서 처리량이 약 1.8배 차이 난다(웹 서버 nginx 가 sendfile 을 쓰는 이유).

## 8. JDK 21 이후 가상 스레드와의 관계

가상 스레드가 등장하면서 "그러면 NIO selector 는 끝났나?" 라는 질문이 흔하다. 답은 No. 가상 스레드는 내부적으로 같은 NIO selector 메커니즘을 쓴다(`Poller` + `epoll_wait`). 차이는 **선반환된 fd 를 어디서 dispatch 하느냐** 다.

- 직접 NIO: 사용자 코드가 selector loop 를 굴린다(Reactor 패턴).
- 가상 스레드: JVM Poller 가 selector loop 를 가지고 있고, 깨어난 fd 에 대응되는 가상 스레드를 ForkJoin pool 의 carrier 스레드 위에 unpark.

즉 가상 스레드는 selector loop 를 사용자에게서 숨겨서 동기 코드처럼 짤 수 있게 해 준 추상화 계층이다. 처리량은 잘 짠 Reactor 와 비슷하고, 코드는 BIO 처럼 단순해진다. 새 코드는 가상 스레드, 레거시·미세조정 영역(Netty)은 직접 NIO 라는 분업이 정착했다.

## 9. 운영 체크포인트

- `EAGAIN` 처리: non-blocking 모드에서 `read` 가 0을 리턴하면 "지금은 데이터 없음". 다시 `OP_READ` 를 켜고 selector 로 돌아가야 한다. 0을 EOF로 오인하면 멀쩡한 연결을 닫아 버린다.
- 부분 write: TCP 송신 버퍼가 가득 차면 `write` 가 일부만 쓰고 종료. `OP_WRITE` 를 등록한 후 다음 wakeup 에 이어서 보내야 한다.
- ulimit -n: 5만 connection을 받으려면 `nofile` 을 25만 이상으로 올려야 한다(연결당 fd 1개 + 임시 객체 여유).
- `TCP_NODELAY`: 짧은 메시지가 많으면 Nagle 알고리즘이 40ms 지연을 만든다. `socket.setOption(StandardSocketOptions.TCP_NODELAY, true)`.

## 참고

- OpenJDK 소스 `src/java.base/linux/classes/sun/nio/ch/EPollSelectorImpl.java`
- Linux man page `epoll(7)`, `epoll_ctl(2)`, `epoll_wait(2)`
- Doug Lea, "Scalable IO in Java" (Reactor 패턴 원전)
- Norman Maurer, Marvin Wolfthal, "Netty in Action" — JNI ET 모드 구현 비교
