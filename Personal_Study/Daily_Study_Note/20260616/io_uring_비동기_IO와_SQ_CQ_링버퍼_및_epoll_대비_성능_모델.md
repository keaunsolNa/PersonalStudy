Notion 원본: https://www.notion.so/3815a06fd6d381dca0bac76acd2efa68

# io_uring 비동기 I/O와 SQ/CQ 링버퍼 및 epoll 대비 성능 모델

> 2026-06-16 신규 주제 · 확장 대상: OS

## 학습 목표

- io_uring 의 SQ/CQ 공유 링버퍼 구조와 시스템콜 분할(setup/enter)을 설명한다
- epoll 의 readiness 모델과 io_uring 의 completion 모델 차이를 구분한다
- SQPOLL, fixed buffers, registered files 등 시스템콜 제거 최적화를 이해한다
- 워크로드별로 epoll vs io_uring 선택 기준을 성능 관점에서 판단한다

## 1. 왜 io_uring 인가 — 기존 비동기의 한계

리눅스의 전통적 I/O 다중화는 `select`/`poll`/`epoll` 로, 이들은 **readiness(준비됨)** 모델이다. "이 fd 가 읽을 준비가 됐는가?" 를 통지받고, 그 다음에 별도로 `read()` 를 호출해 실제 데이터를 옮긴다. 즉 이벤트 1건당 최소 2번의 시스템콜(통지 + read)이 든다. 또 `epoll` 은 소켓에는 잘 동작하지만 정규 파일(regular file) I/O 에는 진짜 비동기가 안 된다 — 파일은 항상 "준비됨" 으로 보고되어 결국 블로킹 read 가 된다. 기존 POSIX AIO(`aio_*`)는 구현이 부분적이고 버퍼드 I/O 미지원 등 한계가 컸다.

io_uring(커널 5.1 도입, 이후 급속 성숙)은 **completion(완료)** 모델로 이를 해결한다. "이 작업을 해줘" 를 큐에 넣으면 커널이 실제 I/O 까지 다 수행하고 "완료됐다 + 결과" 를 다른 큐로 돌려준다. read 든 소켓이든 정규 파일이든 동일한 인터페이스로 진짜 비동기가 된다.

## 2. SQ/CQ — 커널과 공유하는 두 링버퍼

io_uring 의 심장은 유저 공간과 커널이 **공유 메모리(mmap)** 로 같이 보는 두 개의 링버퍼다.

- **SQ(Submission Queue)**: 유저가 하고 싶은 작업을 **SQE(Submission Queue Entry)** 로 채워 넣는 큐.
- **CQ(Completion Queue)**: 커널이 완료 결과를 **CQE(Completion Queue Entry)** 로 넣어주는 큐.

두 링이 공유 메모리에 있으므로, 작업을 제출하고 결과를 수거하는 데 데이터 복사가 필요 없다. 유저는 SQE 를 채우고 tail 포인터를 전진시킨 뒤, 필요할 때만 `io_uring_enter()` 시스템콜로 커널을 깨운다. 커널은 CQE 를 채우고 head/tail 을 갱신한다.

```c
// 개념 흐름 (liburing 기반)
struct io_uring ring;
io_uring_queue_init(256, &ring, 0);          // SQ/CQ 깊이 256

struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_read(sqe, fd, buf, len, offset); // "이 read 를 해줘"
io_uring_sqe_set_data(sqe, req);               // 완료 시 식별용 user_data

io_uring_submit(&ring);                        // io_uring_enter() 1회로 제출

struct io_uring_cqe *cqe;
io_uring_wait_cqe(&ring, &cqe);                // 완료 대기
int result = cqe->res;                         // read 바이트 수(또는 -errno)
void *req = io_uring_cqe_get_data(cqe);
io_uring_cqe_seen(&ring, cqe);                 // CQE 소비 표시
```

핵심은 **배치(batching)** 다. SQE 를 여러 개 채운 뒤 `io_uring_submit()` 한 번으로 모두 제출할 수 있어, N 개의 I/O 작업이 시스템콜 1회로 처리된다. epoll 이 이벤트마다 read 시스템콜을 따로 부르는 것과 대조적이다.

## 3. 시스템콜 3개로 끝나는 인터페이스

io_uring 의 전체 API 표면은 시스템콜 3개다. `io_uring_setup()`(링 생성), `io_uring_enter()`(제출 + 완료 대기), `io_uring_register()`(버퍼/파일 사전 등록). liburing 라이브러리가 이 위에 사용하기 쉬운 래퍼를 제공한다. 작업 종류(opcode)는 read/write 를 넘어 매우 광범위하다 — `recv`/`send`, `accept`, `connect`, `openat`, `close`, `fsync`, `statx`, `timeout`, 그리고 한 작업의 완료가 다음을 트리거하는 **링크드 SQE(IOSQE_IO_LINK)** 까지. 즉 "accept → recv → send" 체인을 커널에 통째로 맡길 수 있다.

## 4. 시스템콜을 0 으로 — SQPOLL 과 사전 등록

io_uring 의 성능 비결은 **시스템콜 자체를 제거** 하는 옵션들이다.

**SQPOLL 모드**: 커널이 전용 폴링 스레드를 띄워 SQ 를 계속 감시한다. 유저는 SQE 만 채우면 커널 스레드가 알아서 집어가 처리하므로 `io_uring_enter()` 조차 부를 필요가 없다 — **시스템콜 0회** 로 I/O 제출이 가능하다. 다만 폴링 스레드가 CPU 를 상시 소모하므로, 매우 높은 IOPS 환경에서만 이득이다(idle 시 자동 sleep, `sq_thread_idle` 로 조정).

```c
struct io_uring_params p = {0};
p.flags = IORING_SETUP_SQPOLL;
p.sq_thread_idle = 2000;   // 2초 idle 후 커널 폴링 스레드 sleep
io_uring_queue_init_params(256, &ring, &p);
```

**Registered (fixed) buffers / files**: 매 I/O 마다 커널은 유저 버퍼의 페이지를 핀(pin)하고 fd 를 검증한다. `io_uring_register()` 로 버퍼와 fd 를 미리 등록해두면 이 반복 비용이 사라진다. 등록된 버퍼에는 `read_fixed`/`write_fixed`, 등록된 파일에는 인덱스로 접근한다. 고정 IOPS 대량 처리(DB 엔진, 프록시)에서 의미 있는 차이를 만든다.

이 조합(SQPOLL + fixed buffers + registered files + 링크드 SQE)이 io_uring 을 "거의 시스템콜 없는 I/O" 로 만든다.

## 5. epoll 대비 성능 모델 — 어디서 이기나

차이는 **시스템콜 횟수와 데이터 경로** 에서 온다.

| 항목 | epoll | io_uring |
|---|---|---|
| 모델 | readiness(준비 통지) | completion(완료 통지) |
| 이벤트당 시스템콜 | 통지 + read = 2회 | 배치 제출로 N작업/1회, SQPOLL 시 0회 |
| 정규 파일 비동기 | 사실상 불가(블로킹) | 완전 지원 |
| 버퍼 복사 | read 시 커널→유저 복사 | 공유 링 + fixed buffer 로 최소화 |
| 적합 워크로드 | 일반 소켓 서버 | 고IOPS 디스크, 대규모 소켓 배치 |

성능 이득이 가장 큰 곳은 (1) **정규 파일 비동기 I/O** — epoll 로는 불가능했던 영역, (2) **초고빈도 소켓 I/O** — 시스템콜이 병목인 구간이다. 반대로 동시 연결이 수백~수천 수준이고 시스템콜이 병목이 아닌 평범한 웹 서버라면, epoll 과 io_uring 의 처리량 차이는 작고 epoll 쪽 코드가 단순하다. "io_uring 이 항상 빠르다" 는 과장이며, **시스템콜 오버헤드가 지배적인 워크로드에서만** 본격적 이득이 난다.

또 하나, 측정 시 주의할 변수가 **CPU 취약점 완화(Spectre/Meltdown mitigation)** 다. mitigation 이 켜지면 시스템콜 진입 비용이 크게 오르는데, io_uring 은 시스템콜 수를 줄이므로 mitigation 환경에서 epoll 대비 상대 이득이 더 커진다. 벤치마크 비교 시 양쪽 커널 설정을 동일하게 맞춰야 공정하다.

## 6. 보안과 운영상의 주의

io_uring 은 강력한 만큼 **공격 표면** 도 넓다. 커널이 비동기로 다양한 연산을 대신 수행하므로, 과거 여러 권한 상승/정보 노출 취약점이 보고됐다. 이 때문에 일부 환경(예: 보안에 민감한 컨테이너 플랫폼, 일부 클라우드 기본 설정)에서는 io_uring 시스템콜을 seccomp 로 차단하기도 한다. 운영 도입 전 (1) 커널 버전(가능한 한 최신 LTS — 기능과 보안 패치가 빠르게 추가됨), (2) seccomp/컨테이너 정책에서 io_uring 허용 여부, (3) `io_uring_register` 로 핀하는 메모리량이 cgroup 리밋과 충돌하지 않는지를 점검해야 한다.

또한 디버깅이 까다롭다 — 작업이 커널에서 비동기로 진행되므로 `strace` 로 read/write 가 보이지 않고 `io_uring_enter` 만 보인다. 추적에는 `perf`, eBPF, 또는 io_uring 전용 tracepoint(`io_uring:io_uring_submit_sqe` 등)를 쓴다.

## 7. 애플리케이션 레벨에서의 채택

직접 liburing 을 쓰는 대신, 런타임/프레임워크가 io_uring 을 백엔드로 채택하는 흐름이 실용적이다. Rust 의 일부 비동기 런타임, Node.js 의 libuv 가 특정 연산에 io_uring 을 활용하는 실험, 데이터베이스(예: 일부 스토리지 엔진)와 프록시가 디스크 경로에 io_uring 을 쓰는 사례가 대표적이다. 백엔드 엔지니어 입장에서 직접 SQE 를 다룰 일은 드물지만, "내 런타임이 io_uring 을 쓰는가, 그래서 어떤 커널 버전/권한이 필요한가" 를 아는 것이 운영 의사결정에 직결된다.

요약하면 io_uring 은 readiness→completion 으로 패러다임을 바꾸고, 공유 링버퍼 + 배치 + 시스템콜 제거로 I/O 오버헤드를 구조적으로 낮춘다. 단, 이득은 시스템콜이 병목인 고부하·파일 I/O 워크로드에 집중되며, 보안/커널 버전/디버깅 비용이라는 trade-off 를 함께 본 뒤 도입을 판단해야 한다.

## 8. 폴링 모드와 완료 통지 방식 — IOPOLL, 멀티샷, 버퍼 풀

io_uring 의 추가 기능들은 특정 워크로드에서 큰 차이를 낸다. **IOPOLL**(`IORING_SETUP_IOPOLL`)은 NVMe 같은 폴링 가능 장치에서 인터럽트 대신 완료를 능동 폴링해, 초고 IOPS 스토리지에서 인터럽트 오버헤드를 없앤다(O_DIRECT 등 조건 필요). **멀티샷(multishot)** 연산은 한 번 제출로 여러 완료를 받는다 — 예를 들어 `accept` 를 멀티샷으로 걸면 새 연결마다 SQE 를 다시 제출하지 않고 CQE 만 계속 받는다. 수락 경로의 시스템콜이 사실상 사라진다.

**제공 버퍼(provided buffers)** 는 수신 시점의 버퍼 관리를 커널에 위임한다. 미리 버퍼 풀을 등록해두면, recv 완료 시 커널이 풀에서 버퍼를 골라 채우고 어느 버퍼를 썬는지 CQE 로 알려준다. "데이터가 올 때까지 버퍼를 미리 잡아두지 않아도 되는" 구조라, 수많은 idle 연결에 버퍼를 묶어두는 낭비를 없앤다. 이들 기능의 공통 목표는 §4 와 같다 — **연산당 시스템콜과 부가 작업을 0 에 수렴**시키는 것이다.

```c
// 멀티샷 accept — 한 번 걸면 연결마다 CQE 가 반복 도착
struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_multishot_accept(sqe, listen_fd, NULL, NULL, 0);
io_uring_submit(&ring);
// 이후 새 연결마다 CQE 수거만 하면 됨 (재제출 불필요)
```

## 9. 마이그레이션 판단과 측정 방법

"epoll 코드를 io_uring 으로 바꿀 가치가 있는가" 는 측정으로 답해야 한다. 먼저 현재 병목이 시스템콜인지 확인한다 — `perf top` 이나 `strace -c -f` 로 `read`/`write`/`epoll_wait` 호출 빈도와 누적 시간을 보고, 시스템콜이 CPU 의 상당 비중을 차지하면 io_uring 의 이득 여지가 크다. 반대로 병목이 애플리케이션 로직이나 다운스트림 지연이면 I/O 인터페이스를 바꿔도 헛수고다.

벤치마크 시 공정성을 위해 양쪽에서 (1) 동일 커널/동일 mitigation 설정, (2) 동일 버퍼 크기, (3) 동일 동시성 수준을 맞춘다. io_uring 측정에서는 SQPOLL on/off, fixed buffer 등록 여부에 따라 결과가 크게 갈리므로 구성별로 따로 측정해야 한다. 일반적 결론은 §5 와 같다 — 동시 연결 수천 이하의 평범한 서비스라면 epoll 의 단순함이 낫고, 수만 연결 + 고빈도 메시지 또는 정규 파일 비동기 I/O 가 본질인 워크로드(스토리지 엔진, 고성능 프록시, 로그 수집기)에서 io_uring 의 구조적 이득이 측정으로 확인된다. 도입 결정 전 §6 의 보안(seccomp/커널 버전)·디버깅 비용을 반드시 함께 저울질한다.

## 참고

- "Efficient IO with io_uring" — Jens Axboe (io_uring 설계 문서)
- liburing 저장소 및 man pages (`io_uring_setup(2)`, `io_uring_enter(2)`)
- Lord of the io_uring (unixism.net) — 튜토리얼 및 SQPOLL/fixed buffer 설명
- Linux Kernel Documentation — io_uring
