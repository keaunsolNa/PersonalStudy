Notion 원본: https://www.notion.so/3755a06fd6d381e59bf5e3802ce7fe66

# Linux io_uring SQ CQ Ring 구조와 epoll 비교 Java 생태계 적용

> 2026-06-04 신규 주제 · 확장 대상: Java NIO Selector / epoll·kqueue IO 다중화 (학습됨)

## 학습 목표

- io_uring 의 Submission Queue / Completion Queue 공유 메모리 링 구조와 syscall 수를 줄이는 메커니즘을 분석한다
- readiness 모델(epoll)과 completion 모델(io_uring)의 근본 차이를 파일 IO·소켓 IO 각각에서 비교한다
- SQPOLL, registered buffers/files, multishot 등 고급 기능의 효과와 비용을 실험 코드로 확인한다
- Netty io_uring transport 와 JDK 의 채택 현황을 파악하고 Spring 서비스에 적용 가능한 지점을 판단한다

## 1. epoll의 한계 — readiness 모델의 syscall 비용

epoll 은 "준비됨(readiness)"을 알려주는 모델이다. 소켓이 읽기 가능해지면 `epoll_wait` 가 깨어나고, 실제 데이터 복사는 **별도의 read syscall** 로 한다. 연결 N 개가 활성일 때 이벤트 루프 한 바퀴는:

```
epoll_wait()          1 syscall
read() × 활성 fd 수    N syscalls
write() × 응답 수      M syscalls
```

syscall 마다 유저↔커널 모드 전환(수백 ns~1µs, Spectre/Meltdown 완화 이후 더 비쌸)이 발생한다. 또한 epoll 은 **일반 파일에 적용 불가**다 — 디스크 파일은 항상 "ready" 로 보고되므로 파일 IO 는 결국 블로킹이거나 스레드풀 우회(libuv 방식)다. Java NIO 의 `FileChannel` 이 비동기가 아니라 `AsynchronousFileChannel` 이 내부 스레드풀인 이유가 이것이다.

## 2. io_uring 구조 — 두 개의 공유 링

io_uring(5.1+, Jens Axboe)은 유저 공간과 커널이 **mmap 으로 공유하는 두 개의 원형 큐**로 IO 를 주고받는다.

```
유저 공간                          커널
   │ SQE 작성(요청: opcode, fd, buf, offset)
   ▼
[Submission Queue Ring] ──────▶ 커널이 SQE 소비, IO 수행
                                      │
[Completion Queue Ring] ◀────── CQE 게시(res, user_data)
   │ CQE 소비(결과 처리)
   ▼
```

- **SQE**(64B): 요청 기술자. opcode(READ/WRITE/ACCEPT/RECV/SEND/FSYNC/TIMEOUT/OPENAT...), fd, 버퍼 포인터, user_data(완료 매칭용 토큰)를 담는다.
- **CQE**(16B): 완료 기술자. user_data 와 결과값(res = 전송 바이트 수 또는 -errno).
- head/tail 인덱스는 원자적 메모리 연산으로 갱신되므로 **큐 적재/소비 자체에는 syscall 이 없다**.

syscall 은 `io_uring_enter` 하나로 줄어든다. 한 번의 enter 로 SQE 여러 개 제출 + 완료 대기까지 한다(배칭). 위 epoll 시나리오의 1+N+M syscall 이 이론상 1 로 떨어진다.

```c
// liburing 최소 예제 — 파일 읽기
struct io_uring ring;
io_uring_queue_init(256, &ring, 0);

struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_read(sqe, fd, buf, 4096, 0);
sqe->user_data = 42;

io_uring_submit(&ring);                      // io_uring_enter 1회

struct io_uring_cqe *cqe;
io_uring_wait_cqe(&ring, &cqe);              // 완료 대기
printf("read %d bytes (token=%llu)\n", cqe->res, cqe->user_data);
io_uring_cqe_seen(&ring, cqe);
```

readiness 가 아니라 **completion 모델**이므로 파일 IO 와 네트워크 IO 가 동일한 인터페이스로 처리된다. 이것이 io_uring 의 가장 큰 구조적 진보다.

## 3. 고급 기능 — syscall 0회를 향한 단계들

**SQPOLL**: 커널 스레드가 SQ 를 폴링해 유저가 `io_uring_enter` 조차 부르지 않아도 제출이 처리된다. 트레이드오프: CPU 코어 하나를 폴링에 소모 — 초고처리량 스토리지 엔진에서만 정당화된다.

**Registered buffers / files** (`io_uring_register`): 버퍼와 fd 를 사전 등록하면 요청마다 하던 페이지 핀닝과 fd 참조 획득이 생략된다. NVMe 4K 랜덤 읽기에서 registered buffer 적용 전후 IOPS 차이가 20~40% 수준으로 보고된다(fio `--fixedbufs`).

**Multishot accept / recv** (5.19+): SQE 하나로 "계속 받아라"를 표현한다. accept 한 번 제출하면 새 연결마다 CQE 가 반복 게시된다.

**Provided buffer ring** (5.19+): 버퍼 풀을 커널에 맡기고, recv 완료 시 커널이 풀에서 고른 버퍼 ID 가 CQE 로 돌아온다. "연결 수 × 버퍼" 사전 할당 문제(C10K 메모리)를 "동시 활성 수 × 버퍼"로 줄인다.

**체이닝**(`IOSQE_IO_LINK`): SQE 들을 연결해 "read → write → fsync" 순서 의존을 커널 안에서 처리한다. 유저 공간 왕복 없이 파이프라인이 돈다.

## 4. 성능 — 어디서 이기고 어디서 비기는가

공정한 요약은 "파일 IO 는 압도적, 순수 네트워크는 조건부"다.

- **파일/블록 IO**: 진정한 비동기 + 배칭 + registered buffer 로 NVMe 한 장의 한계 IOPS(수백만)를 단일 코어 근처에서 뽑는다. polled IO(`IORING_SETUP_IOPOLL`)까지 켜면 인터럽트 비용도 제거된다. RocksDB, TigerBeetle, ScyllaDB 등이 채택한 이유다.
- **네트워크 echo 류 벤치마크**: 활성 연결 비율이 높고 메시지가 작을 때 epoll 대비 처리량 개선이 10~60% 범위로 보고가 갈린다. 연결이 대부분 유휴(idle-heavy)면 epoll 과 차이가 작다 — readiness 알림 자체는 epoll 도 싸기 때문. multishot recv + buffer ring 을 써야 격차가 벌어진다.
- **지연**: syscall 배칭은 평균 지연을 줄이지만, SQPOLL 없이 낮은 부하에서는 enter 왕복이 남아 p50 차이가 미미할 수 있다.

주의할 운영 이슈: 컨테이너 환경에서 io_uring 은 seccomp 기본 프로파일에 따라 차단되는 경우가 있다(Docker 기본 허용, 일부 매니지드 K8s/gVisor 차단). 또 5.x 커널의 io_uring 취약점 다수 공개 이력 때문에 멀티테넌트 환경에서 비활성화 사례가 있다 — 도입 전 보안 정책 확인이 필수다.

## 5. Java 생태계 — Netty transport와 JDK의 현재

JDK 자체 NIO 는 여전히 epoll 기반이다(Project Loom 의 가상 스레드도 블로킹 IO를 epoll 위에 올린 구조). io_uring 을 쓰는 현실적 경로는 Netty 다.

```xml
<dependency>
	<groupId>io.netty</groupId>
	<artifactId>netty-transport-native-io_uring</artifactId>
	<classifier>linux-x86_64</classifier>
</dependency>
```

```java
// Netty 4.2+ — io_uring transport (4.2에서 incubator 졸업)
IoHandlerFactory factory = IoUringIoHandler.newFactory();
EventLoopGroup group = new MultiThreadIoEventLoopGroup(factory);

ServerBootstrap b = new ServerBootstrap()
	.group(group)
	.channel(IoUringServerSocketChannel.class)
	.childHandler(new EchoServerInitializer());
```

Netty 의 io_uring transport 는 accept/recv 의 multishot, buffer ring 을 내부적으로 활용한다. Spring WebFlux(Reactor Netty)는 transport 선택을 추상화하므로 클래스패스와 설정으로 교체 가능하다 — 단 Reactor Netty 의 io_uring 지원 성숙도를 버전별로 확인해야 한다(2024 이후 1.2.x 라인에서 실험 지원). gRPC-Java, Vert.x 도 Netty 기반이므로 동일 경로로 혜택을 본다. 반면 Tomcat(MVC) 스택은 NIO 커넥터가 JDK Selector 를 직접 쓰므로 io_uring 경로가 없다 — MVC 서비스라면 이 주제의 적용 지점은 애플리케이션 서버가 아니라 같이 돌리는 프록시(Envoy, HAProxy 2.6+ io_uring 지원)나 스토리지 계층이다.

## 6. 실험 — epoll vs io_uring 마이크로벤치

직접 검증용 최소 실험 설계. 동일 머신에서 fio 로 파일 IO 경로를 비교한다.

```bash
# libaio (epoll 시대의 비동기 파일 IO)
fio --name=aio --ioengine=libaio --iodepth=64 --rw=randread \
    --bs=4k --size=4G --numjobs=1 --direct=1

# io_uring
fio --name=uring --ioengine=io_uring --iodepth=64 --rw=randread \
    --bs=4k --size=4G --numjobs=1 --direct=1 --fixedbufs --registerfiles
```

관찰 포인트: (1) IOPS 와 평균/95p 지연, (2) `perf stat -e syscalls:sys_enter_*` 로 syscall 수 비교 — io_uring 쪽이 수십 분의 1로 떨어지는 것을 확인한다, (3) iodepth 를 1→64 로 올릴 때 격차 변화(배칭 이득은 깊이에 비례).

네트워크 쪽은 Netty EchoServer 를 transport 만 바꿔(`epoll` vs `io_uring`) wrk 로 비교하되, idle 연결 9 : 활성 1 비율과 전부 활성 두 시나리오를 분리 측정해야 io_uring 의 이득 조건이 드러난다.

## 7. 설계 함의 — completion 모델이 바꾸는 코드 구조

epoll 기반 이벤트 루프는 "ready → 논블로킹 read 시도 → EAGAIN 처리" 의 상태 기계다. completion 모델에서는 "버퍼를 준 read 를 제출 → 완료 콜백" 으로 단순해지지만, **버퍼 소유권**이 어려워진다. 제출 순간부터 완료까지 버퍼는 커널 소유이므로 유저가 건드리면 안 된다 — GC 언어에서는 네이티브 메모리 고정이 필요하고, Netty 가 pooled direct buffer 와 reference counting 으로 푸는 문제가 정확히 이것이다. Rust 생태계(tokio-uring, monoio)가 버퍼 소유권을 타입으로 강제하는 설계로 주목받은 이유이기도 하다.

또 하나의 함의는 **스레드 모델**이다. io_uring 링은 기본적으로 스레드당 하나(shared-nothing)가 권장이다. 링을 여러 스레드가 공유하면 락이 필요해 이점이 줄어든다. 이는 seastar/monoio 류 thread-per-core 아키텍처와 자연스럽게 맞고, Netty 도 EventLoop 당 링 하나를 유지한다.

## 8. 도입 판단 체크리스트

- 커널 5.15+(LTS, multishot 일부는 5.19+/6.x) 인가 — 매니지드 환경이면 노드 이미지 커널 확인
- seccomp/보안 정책이 io_uring syscall(425~427)을 허용하는가
- 워크로드가 (a) 파일/블록 IO 집약이거나 (b) 활성 비율 높은 소켓 IO 인가 — idle-heavy 웹 API 라면 이득이 작다
- Java 라면 Netty 4.2+ 기반 스택(Reactor Netty, gRPC, Vert.x)인가
- 벤치마크를 프로덕션 유사 시나리오(연결 분포, 페이로드 크기)로 직접 측정했는가

이득이 분명한 영역(스토리지 엔진, 프록시, 고처리 게이트웨이)과 미미한 영역(저QPS 비즈니스 API)을 구분하는 것이 이 주제의 실무 결론이다.

## 9. 부록 — 커널 내부 동작과 async 처리 경로

io_uring 이 "항상 비동기"라는 오해가 있다. 실제 처리 경로는 3단계 폴백이다. (1) **인라인 완료**: 제출 컨텍스트에서 논블로킹으로 즉시 끝나면(페이지 캠시 히트 read, 버퍼 여유 있는 send) CQE 가 곷바로 게시된다 — 대부분의 소켓 IO 가 이 경로다. (2) **poll-armed**: 소켓이 준비되지 않았으면 커널 내부 poll 핸들러를 걸어 두고, 준비 시점에 커널이 재시도한다. epoll 의 readiness 대기가 커널 안으로 들어간 셐이라 유저 공간 왕복이 없다. (3) **io-wq 워커**: poll 로 표현 불가능한 작업(버퍼드 파일 IO 일부, openat, statx 등)은 커널 워커 스레드 풀(io-wq)로 넘어간다. io-wq 가 과도하게 생성되면(`iowq` 스레드가 ps 에 보임) 워크로드가 async-friendly 하지 않다는 신호이며 `IORING_REGISTER_IOWQ_MAX_WORKERS` 로 상한을 건다.

O_DIRECT + NVMe 조합은 (1)에서 NVMe 큐에 바로 적재되는 진성 비동기 경로를 타며, 이것이 fio 벤치마크 수치가 가장 극적인 이유다. 버퍼드 IO 는 5.7+ 부터 페이지 캠시 미스 시에도 readahead 기반 비동기 경로가 개선됐지만 여전히 io-wq 폴백 비율이 워크로드에 따라 달라진다 — `trace-cmd record -e io_uring` 으로 io-wq 적재 비율을 직접 확인하는 것이 정확하다.

보안 관점 부록: io_uring 의 공격 표면 논란은 주로 (a) 커널 워커가 유저 메모리에 접근하는 경로의 race, (b) registered buffer 수명 관리 버그에서 나왔다. 6.x 커널에서 다수 수정됐고, `sysctl kernel.io_uring_disabled`(6.6+)로 비특권 사용만 차단(=1)하거나 전면 차단(=2)하는 세분화가 가능해졌다. 멀티테넌트 노드는 =1 이 합리적 기본값이다.

```bash
# 노드의 io_uring 정책 확인
sysctl kernel.io_uring_disabled
# 컨테이너 seccomp 확인 — io_uring_setup 허용 여부
grep -A2 io_uring /usr/share/containers/seccomp.json
```

## 참고

- Jens Axboe, "Efficient IO with io_uring" 설계 문서 (kernel.dk)
- liburing man pages — io_uring_setup(2), io_uring_enter(2), io_uring_register(2)
- Netty io_uring transport 문서 및 벤치마크 (netty.io/wiki)
- LWN.net — "The rapid growth of io_uring", multishot/buffer ring 시리즈
- fio 문서 — ioengine=io_uring 옵션
- Lord of the io_uring 튜토리얼 (unixism.net/loti)
