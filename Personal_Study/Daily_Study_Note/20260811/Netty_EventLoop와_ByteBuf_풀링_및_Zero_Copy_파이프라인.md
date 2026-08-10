Notion 원본: https://www.notion.so/3b85a06fd6d3817383c3cfa8250a8748

# Netty EventLoop와 ByteBuf 풀링 및 Zero Copy 파이프라인

> 2026-08-11 신규 주제 · 확장 대상: Backend (Java NIO Selector · epoll/io_uring 기학습)

## 학습 목표

- NIO Selector 원시 API 대비 Netty EventLoop의 스레딩 계약과 태스크 큐 구조를 분석한다
- ChannelPipeline의 인바운드/아웃바운드 이벤트 전파와 핸들러 실행 스레드를 추적한다
- PooledByteBufAllocator의 jemalloc 유래 아레나 구조와 참조 카운팅 규약을 이해한다
- 백프레셔(수위 마크)·Zero Copy·성능 튜닝 옵션을 실무 서버 구성에 적용한다

## 1. NIO에서 Netty로 — 무엇이 추상화되는가

기학습한 NIO Selector 루프를 직접 쓰면 accept/read/write 이벤트 분기, 부분 읽기 누적 버퍼 관리, wakeup 경합, epoll 스퓨리어스 웨이크업 같은 저수준 문제를 전부 코드로 감당해야 한다. Netty는 이 루프를 **EventLoop**라는 실행 모델로 고정하고, 그 위에 **ChannelPipeline**이라는 처리 체인과 **ByteBuf**라는 버퍼 계층을 얹는다. Spring WebFlux(Reactor Netty), gRPC-Java, Elasticsearch transport, Redisson — 기학습한 스택 대부분의 네트워크 바닥이 이 세 구조물 위에 있으므로, 이 층을 이해하면 "WebFlux가 왜 blocking 호출에 취약한가" 같은 상위 질문의 물리적 근거를 갖게 된다.

```java
EventLoopGroup boss = new NioEventLoopGroup(1);        // accept 전담
EventLoopGroup worker = new NioEventLoopGroup();       // 기본: 코어수 * 2

ServerBootstrap b = new ServerBootstrap();
b.group(boss, worker)
	.channel(NioServerSocketChannel.class)
	.option(ChannelOption.SO_BACKLOG, 1024)
	.childOption(ChannelOption.TCP_NODELAY, true)
	.childHandler(new ChannelInitializer<SocketChannel>() {
		@Override
		protected void initChannel(SocketChannel ch) {
			ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(65_535, 0, 4, 0, 4));
			ch.pipeline().addLast(new EchoHandler());
		}
	});
b.bind(8080).sync();
```

boss 그룹의 EventLoop가 `OP_ACCEPT`를 처리해 새 소켓을 worker 그룹의 EventLoop 하나에 **영구 등록**한다. 이 "채널은 평생 한 EventLoop에 고정"이라는 계약이 Netty 동시성 모델의 전부다.

## 2. EventLoop 스레딩 계약 — 락 없는 동시성의 대가

하나의 `NioEventLoop`는 스레드 1개 + Selector 1개 + 태스크 큐로 구성되며, 수천 개 채널이 하나의 EventLoop를 공유한다. 루프 본문은 `select()` → 준비된 채널의 I/O 처리 → 태스크 큐 소진의 반복이고, `ioRatio`(기본 50)로 I/O와 태스크의 시간 배분을 조절한다. 채널의 모든 핸들러 콜백은 반드시 그 채널의 EventLoop 스레드에서 실행되므로 **핸들러 안에서는 동기화가 필요 없다**. 외부 스레드가 `channel.write()`를 불러도 Netty가 내부적으로 `eventLoop.execute()`로 래핑해 태스크 큐에 넣는다.

대가는 명확하다 — EventLoop 스레드가 블로킹되면 그 루프에 물린 **수천 채널이 전부 멈춘다**. JDBC 호출, 동기 HTTP 클라이언트, 심지어 큰 `synchronized` 블록도 금물이다. 블로킹 작업은 별도 `ExecutorGroup`으로 내려야 한다:

```java
// 특정 핸들러만 별도 스레드풀에서 실행
EventExecutorGroup blockingGroup = new DefaultEventExecutorGroup(16);
ch.pipeline().addLast(blockingGroup, "dbHandler", new DbLookupHandler());
```

진단 관점에서, WebFlux 기학습 때 다룬 BlockHound가 잡아내는 것이 정확히 "EventLoop 스레드 위의 블로킹 호출"이다. Netty 4.1은 전송 구현으로 NIO 외에 `epoll`(리눅스 네이티브, edge-triggered), `kqueue`(macOS), `io_uring`(incubator)을 제공하며, 리눅스 프로덕션에서는 `EpollEventLoopGroup`이 GC 부담이 적고(네이티브 fd 배열) 약간의 처리량 이득을 준다. 기학습한 io_uring의 완료 기반 모델은 Netty 4.2에서 정식 채택 방향으로 진행 중인 영역이다.

## 3. ChannelPipeline — 인바운드/아웃바운드 전파 규칙

파이프라인은 핸들러의 양방향 연결 리스트다. 인바운드 이벤트(`channelRead`, `channelActive`)는 head → tail 방향으로, 아웃바운드 연산(`write`, `flush`, `connect`)은 tail → head 방향으로 흐른다. 각 핸들러는 `ctx.fireChannelRead(msg)` / `ctx.write(msg)`를 호출해야 다음 핸들러로 전파된다 — 호출하지 않으면 이벤트는 거기서 소멸하며, 이것이 필터링의 메커니즘이자 **메모리 릭의 첫 번째 원인**이다(전파도 해제도 안 한 ByteBuf).

```
inbound:  head → [FrameDecoder] → [ProtobufDecoder] → [BizHandler] → tail
outbound: head ← [ProtobufEncoder] ←──────────────────  ctx.write()
```

중요한 세부: `ctx.write()`는 **현재 핸들러보다 앞쪽(outbound 방향)** 핸들러부터 탐색하고, `channel.write()`는 tail부터 전체를 탐색한다. 인코더가 BizHandler보다 뒤(addLast 순서상 앞)에 있으면 `ctx.write()`가 인코더를 건너뛰는 사고가 난다. 또한 `write`는 소켓에 쓰지 않고 채널의 `ChannelOutboundBuffer`에 적재만 하며, `flush`가 실제 syscall을 유발한다 — `writeAndFlush`를 매 메시지마다 부르면 syscall이 폭증하므로, 대량 전송은 write 여러 번 + flush 1회로 배칭하거나 `FlushConsolidationHandler`를 앞단에 둔다.

## 4. ByteBuf — readerIndex/writerIndex 모델과 참조 카운팅

`ByteBuffer`의 flip 지옥을 ByteBuf는 **readerIndex / writerIndex 이중 인덱스**로 해소한다. 읽기는 readerIndex를, 쓰기는 writerIndex를 독립적으로 전진시키므로 모드 전환이 없다. 더 중요한 것은 수명 관리다 — ByteBuf는 `ReferenceCounted`이며, 풀에서 나온 버퍼는 `release()`가 refCnt를 0으로 만들 때 풀로 반환된다.

규약: **마지막으로 소비하는 자가 release한다**. `SimpleChannelInboundHandler`는 `channelRead0` 후 자동 release하고, 직접 `ChannelInboundHandlerAdapter`를 쓰면 수동 release 책임이 생긴다. `write()`에 넘긴 버퍼는 Netty가 전송 후 release하므로 넘긴 쪽은 손을 떼다. 이 규약 위반이 누적되면 풀 메모리가 고갈되는데, 탐지 도구가 릭 디텍터다:

```bash
# 개발/스테이징: 모든 버퍼 추적 (프로덕션은 비용 큼)
-Dio.netty.leakDetection.level=PARANOID
# 프로덕션 기본: SIMPLE (1% 샘플링), 릭 발견 시 할당 스택트레이스 로그
```

`slice()`·`duplicate()`·`retainedSlice()`는 메모리를 공유하는 뷰를 만든다. slice는 refCnt를 공유하므로 원본보다 오래 쓸 거면 `retain()`(또는 retainedSlice)으로 카운트를 올려야 한다. `CompositeByteBuf`는 여러 버퍼를 복사 없이 논리적으로 이어 붙이는 구조로, 헤더+바디 조립을 memcpy 없이 처리한다 — 이것이 Netty가 말하는 첫 번째 종류의 zero copy다.

## 5. PooledByteBufAllocator — jemalloc 유래 아레나 할당기

다이렉트 버퍼 할당·해제는 비싸다(`malloc` + zeroing + `Cleaner`). Netty 기본 할당기 `PooledByteBufAllocator`는 jemalloc 설계를 이식해 이 비용을 상쇄한다. 구조는 3층이다. **Arena**: 스레드 경합을 줄이기 위해 여러 개(기본 2×코어)를 두고 EventLoop 스레드를 아레나에 분산 바인딩한다. **Chunk**: 아레나가 OS에서 받아오는 큰 블록(기본 4MB, 4.1.75+ 기준)으로, 내부를 run/page 단위로 쪼개 buddy 방식으로 관리한다. **스레드 로컬 캐시(PoolThreadCache)**: 해제된 small/normal 크기 버퍼를 스레드 로컬에 캐싱해 아레나 락 없이 재할당한다.

할당 크기 클래스는 small(<28KB 구간을 세분) / normal(chunk 이하) / huge(chunk 초과, 풀링 없이 직접 할당)로 나뉜다. 4.1.52부터 jemalloc4 스타일의 size-class 체계로 개편되어 내부 단편화가 감소했다. 운영에서 만나는 문제는 대개 두 가지다: (1) **다이렉트 메모리 한계 초과** — `io.netty.maxDirectMemory`(기본 `-XX:MaxDirectMemorySize` 추종) 초과 시 `OutOfDirectMemoryError`. 릭이 아니라면 수위 마크 없는 무제한 write 적체가 원인인 경우가 많다. (2) **아레나 밖 스레드의 할당** — EventLoop 외 스레드에서 대량 할당하면 스레드 캐시 미스와 아레나 경합이 생긴다. 메트릭은 `PooledByteBufAllocator.DEFAULT.metric()`으로 chunk 사용량·아레나별 할당 수를 노출하므로 모니터링에 연결해 둔다.

```java
// 풀링 상태 점검용 엔드포인트에서
PooledByteBufAllocatorMetric m = PooledByteBufAllocator.DEFAULT.metric();
log.info("direct arenas={}, used={}MB", m.numDirectArenas(), m.usedDirectMemory() / 1048576);
```

## 6. Zero Copy — FileRegion, CompositeByteBuf, 그리고 커널 경로

Netty의 "zero copy"는 두 층위를 묶어 부르는 말이라 구분이 필요하다. **유저 공간 zero copy**: CompositeByteBuf(§4), `Unpooled.wrappedBuffer(byte[])`(복사 없는 래핑), `slice()`(복사 없는 부분 뷰). JVM 힙 안에서 memcpy를 줄이는 기법이다. **커널 zero copy**: `DefaultFileRegion`이 기학습한 `FileChannel.transferTo()` — 즉 리눅스 `sendfile(2)` — 로 파일 → 소켓 전송을 유저 공간 경유 없이 수행한다.

```java
// 정적 파일 전송: 파일 내용이 유저 공간에 올라오지 않음
FileChannel fc = FileChannel.open(path, StandardOpenOption.READ);
ctx.write(new DefaultFileRegion(fc, 0, fc.size()));
ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
```

제약: TLS를 쓰면 데이터를 암호화해야 하므로 sendfile 경로가 불가능해진다(`SslHandler`가 있으면 FileRegion 대신 `ChunkedFile`로 폴백해야 함). kTLS(커널 TLS 오프로드)가 이 제약을 푸는 방향이지만 Netty 정식 지원은 제한적이다. HTTP 파일 서버 벤치마크에서 sendfile 경로는 대용량 파일 기준 CPU 사용률을 절반 이하로 떨어뜨리는 것이 전형적 실측 결과다.

## 7. 백프레셔 — 수위 마크와 채널 writability

느린 수신자에게 빠르게 쓰면 `ChannelOutboundBuffer`가 무한히 자라 다이렉트 메모리를 삼킨다. Netty의 1차 방어선은 **write buffer water mark**다:

```java
b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
	new WriteBufferWaterMark(32 * 1024, 64 * 1024));   // low=32K, high=64K
```

적체가 high를 넘으면 `channel.isWritable()`이 false가 되고 `channelWritabilityChanged` 이벤트가 발화한다. **강제 차단이 아니라 신호**라는 점이 함정이다 — isWritable을 무시하고 계속 write하면 여전히 OOM으로 간다. 올바른 패턴은 writability false에서 소스 읽기를 멈추는 것이다(프록시라면 반대편 채널에 `setAutoRead(false)`). 읽기 쪽 제어는 `AUTO_READ`를 끄고 `channel.read()`를 수동 호출하는 방식으로, 처리 완료 시에만 다음 읽기를 요청해 TCP 수신 윈도를 통한 원단 배압으로 이어진다. Reactor Netty의 request(n) 백프레셔가 바닥에서 하는 일이 정확히 이 autoRead 토글이다.

## 8. 성능 튜닝 파라미터 실전 정리

| 옵션 | 기본값 | 조정 지침 |
| --- | --- | --- |
| worker 스레드 수 | 코어×2 | CPU 바운드 인코딩이 없으면 코어 수로 축소 가능 |
| SO_BACKLOG | OS 의존 | accept 폭주 시 1024+ (net.core.somaxconn과 함께) |
| TCP_NODELAY | false | RPC·저지연은 true (Nagle 비활성) |
| ALLOCATOR | pooled | 유지. 디버깅 시에만 unpooled |
| RCVBUF_ALLOCATOR | AdaptiveRecvByteBufAllocator | 고정 크기 메시지는 FixedRecvByteBufAllocator로 재할당 제거 |
| ioRatio | 50 | 태스크 큐가 밀리면 상향(예: 70) |
| leakDetection.level | SIMPLE | 스테이징 PARANOID, 프로덕션 SIMPLE |

계측 없이 조정하지 않는 것이 원칙이다. 병목 판정 순서: (1) EventLoop 지연 — `SingleThreadEventExecutor#pendingTasks`와 루프 지연 히스토그램(마이크로미터 바인더 존재)으로 태스크 적체 확인. (2) 할당기 메트릭으로 다이렉트 메모리 추세 확인. (3) 기학습한 async-profiler로 EventLoop 스레드의 온-CPU 플레임그래프를 떠서 인코딩/압축/TLS가 루프를 점유하는지 확인 — 점유가 크면 해당 핸들러를 EventExecutorGroup으로 분리하는 것이 스레드 수 증가보다 효과적이다.

## 9. Spring 개발자의 관점 — Reactor Netty와의 접점

WebFlux 애플리케이션에서 이 계층이 드러나는 순간을 정리한다. `reactor.netty.ioWorkerCount` 시스템 프로퍼티가 worker EventLoop 수를, `ReactorResourceFactory`가 EventLoopGroup 수명을 관리한다. WebClient 응답을 구독하지 않고 버리면 커넥션이 풀로 반환되지 않는 문제, `DataBuffer`를 release하지 않아 leak detector 경고가 뜨는 문제는 모두 §4의 참조 카운팅 규약이 Spring 계층까지 올라온 것이다(`DataBufferUtils.release`). `Connection reset by peer` 급증 시 수위 마크·SO_BACKLOG·OS의 somaxconn을 한 세트로 점검하고, 다이렉트 메모리 OOM 시 `-Dio.netty.leakDetection.level=PARANOID`를 스테이징에 켜 릭과 적체를 구분하는 것 — 이것이 이 노트의 내용을 실무 장애 대응으로 연결하는 최단 경로다.

## 참고

- Netty 공식 사용자 가이드: https://netty.io/wiki/user-guide-for-4.x.html
- Netty ByteBuf·Reference counted objects 문서: https://netty.io/wiki/reference-counted-objects.html
- Norman Maurer, *Netty in Action* (Manning, 2015)
- jemalloc 논문: Jason Evans, "A Scalable Concurrent malloc(3) Implementation for FreeBSD" (BSDCan 2006)
- Reactor Netty Reference Guide: https://projectreactor.io/docs/netty/release/reference/
