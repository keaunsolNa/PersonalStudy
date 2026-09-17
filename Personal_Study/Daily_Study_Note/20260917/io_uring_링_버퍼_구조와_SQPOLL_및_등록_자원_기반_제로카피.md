Notion 원본: https://www.notion.so/3de5a06fd6d381dd8927c84a3e53cf13

# io_uring 링 버퍼 구조와 SQPOLL 및 등록 자원 기반 제로카피

> 2026-09-17 신규 주제 · 확장 대상: OS 시스템콜·I/O 모델 기초 → 리눅스 비동기 I/O 인터페이스 내부

## 학습 목표

- SQ/CQ 링의 메모리 배치와 헤드·테일 인덱스 동기화 규칙을 설명한다
- epoll 대비 시스템콜 횟수를 정량 비교하고 SQPOLL 이 없애는 비용을 구분한다
- 등록 파일·등록 버퍼·고정 버퍼가 커널 경로에서 줄이는 작업을 짚는다
- io_uring 의 보안 이력과 배포판 정책을 근거로 도입 여부를 판정한다

## 1. 왜 또 하나의 비동기 인터페이스인가

리눅스의 비동기 I/O 역사는 실패의 누적이다. `select`/`poll` 은 매 호출마다 fd 집합 전체를 커널에 복사하고 전체를 순회했다. `epoll` 은 관심 목록을 커널에 상주시켜 이 문제를 풀었지만 **준비 상태 통보(readiness)** 모델이라, "읽을 수 있다"는 통보를 받은 뒤 다시 `read()` 를 호출해야 한다. 즉 I/O 하나당 최소 두 번의 시스템콜이다. 게다가 정규 파일에는 동작하지 않는다 — 디스크 파일은 항상 "준비됨"이라 통보 자체가 무의미하다.

`libaio`(`io_submit`)는 **완료 통보(completion)** 모델이지만 제약이 심했다. `O_DIRECT` 가 아니면 조용히 동기 동작으로 떨어지고, 지원 연산이 읽기·쓰기 정도로 제한됐으며, 메타데이터 연산에서 블로킹이 발생했다.

io_uring 은 완료 모델을 유지하면서 두 가지를 바꿨다. 첫째, 제출과 완료를 **커널과 공유하는 링 버퍼**에 두어 시스템콜 자체를 선택 사항으로 만들었다. 둘째, 연산 종류를 시스템콜 전반으로 확장했다 — `openat`, `statx`, `accept`, `connect`, `send`, `recv`, `splice`, `mkdirat` 까지 오퍼코드로 표현된다.

## 2. 링 버퍼의 메모리 배치

`io_uring_setup(entries, params)` 는 fd 를 반환하고, 그 fd 를 `mmap` 해서 세 영역을 얻는다.

```
IORING_OFF_SQ_RING  →  SQ 헤더 + SQ 인덱스 배열
IORING_OFF_CQ_RING  →  CQ 헤더 + CQE 배열
IORING_OFF_SQES     →  SQE 배열
```

SQ(Submission Queue)가 두 겹인 이유가 io_uring 설계의 특징이다. SQ 링에는 **SQE 배열의 인덱스**만 들어 있고 실제 SQE 구조체는 별도 배열에 있다. 이 간접 참조 덕분에 애플리케이션이 SQE 를 미리 채워두고 제출 순서만 바꾸거나, 여러 SQE 를 한 번에 제출하면서 일부를 건너뛸 수 있다. CQ 는 간접 참조 없이 CQE 배열을 바로 쓴다.

각 링은 헤드와 테일 인덱스를 가진다. SQ 는 애플리케이션이 테일을 밀고 커널이 헤드를 당긴다. CQ 는 반대로 커널이 테일을 밀고 애플리케이션이 헤드를 당긴다. 인덱스는 감싸지 않고 계속 증가하며, 실제 접근은 `index & ring_mask` 로 한다. 링 크기가 2의 거듭제곱이어야 하는 이유다.

```c
// 제출 측 기본 흐름 (liburing 없이)
unsigned tail = sq->tail;                       // 우리 소유 — 그냥 읽음
unsigned index = tail & sq->ring_mask;
struct io_uring_sqe *sqe = &sqes[index];
memset(sqe, 0, sizeof(*sqe));
sqe->opcode = IORING_OP_READ;
sqe->fd = fd;
sqe->addr = (unsigned long) buf;
sqe->len = nbytes;
sqe->off = offset;
sqe->user_data = req_id;                        // CQE 에 그대로 실려 돌아온다
sq->array[index] = index;

io_uring_smp_store_release(&sq->tail, tail + 1); // 커널이 SQE 를 보기 전에
                                                  // 내용 쓰기가 보이도록 release
io_uring_enter(ring_fd, 1, 0, 0, NULL);
```

메모리 배리어가 핵심이다. 테일 갱신이 SQE 내용 쓰기보다 먼저 보이면 커널이 쓰레기 SQE 를 읽는다. 그래서 테일은 store-release, 완료 측 헤드 읽기는 load-acquire 다. `liburing` 이 이 부분을 감춰주므로 실무에서 직접 쓸 일은 드물지만, 성능 이상을 디버깅할 때 이 구조를 알아야 한다.

`user_data` 는 커널이 해석하지 않고 CQE 에 그대로 복사한다. 요청 컨텍스트 포인터나 요청 ID 를 넣어 완료를 매칭하는 유일한 수단이다.

## 3. 시스템콜 횟수 비교

10,000 개 연결에서 각각 한 번씩 읽는 상황을 놓고 세보자.

| 모델 | 시스템콜 수 | 비고 |
|---|---|---|
| epoll + read | 1(`epoll_wait`) + 10,000(`read`) | 읽기마다 진입·복귀 |
| io_uring 기본 | 1(제출) + 1(완료 대기) | SQE 는 배치 제출 |
| io_uring + SQPOLL | 0 | 커널 스레드가 폴링 |
| io_uring + IOPOLL | 0 (완료도 폴링) | `O_DIRECT` NVMe 전용 |

시스템콜 하나의 비용은 CPU 와 완화 대책에 따라 크게 다르다. Spectre/Meltdown 완화(KPTI, retpoline)가 켜진 서버에서는 진입·복귀에 수백 나노초가 든다. 10,000 번이면 수 밀리초가 순수 오버헤드로 사라진다. io_uring 이 크게 이기는 지점이 여기다.

다만 배치가 없으면 이득도 없다. 요청 하나 제출하고 곧바로 완료를 기다리는 코드는 `io_uring_enter` 를 두 번 부르므로 `read()` 한 번보다 느리다. io_uring 은 **동시성이 충분히 높을 때만** 의미가 있다.

## 4. SQPOLL — 시스템콜을 0으로

`IORING_SETUP_SQPOLL` 플래그를 주면 커널이 전용 스레드를 띄워 SQ 테일을 폴링한다. 애플리케이션은 SQE 를 쓰고 테일만 올리면 끝이고, 시스템콜이 아예 없다.

```c
struct io_uring_params p = {0};
p.flags = IORING_SETUP_SQPOLL;
p.sq_thread_idle = 2000;          // 2초 동안 제출 없으면 잠듦
p.flags |= IORING_SETUP_SQ_AFF;
p.sq_thread_cpu = 3;              // 폴링 스레드를 CPU 3 에 고정

int ring_fd = io_uring_setup(4096, &p);
```

대가는 CPU 코어 하나를 통째로 태우는 것이다. `sq_thread_idle` 동안 제출이 없으면 잠들고, 이때는 `IORING_SQ_NEED_WAKEUP` 플래그가 서므로 애플리케이션이 `io_uring_enter(..., IORING_ENTER_SQ_WAKEUP)` 로 깨워야 한다. liburing 의 `io_uring_submit()` 이 이 검사를 해준다.

SQPOLL 은 지연에 극도로 민감하고 부하가 항상 높은 시스템(스토리지 엔진, 저지연 네트워킹)에서만 타당하다. 일반 웹 서버에서 코어 하나를 폴링에 쓰는 것은 대개 손해다. `SQ_AFF` 로 CPU 를 고정하지 않으면 스케줄러가 폴링 스레드를 옮겨 다니며 캐시 지역성을 망가뜨린다.

주의할 함정: SQPOLL 모드에서는 **등록된 파일만** 쓸 수 있는 시기가 있었다(커널 5.11 이전). 최신 커널에서는 제약이 풀렸지만, 등록 파일과 조합하는 것이 여전히 성능상 유리하다.

## 5. 등록 자원 — 커널이 매번 하던 일 없애기

시스템콜마다 커널이 반복하는 작업이 두 가지 있다. fd 를 `struct file*` 로 변환하며 참조 카운트를 올렸다 내리는 것, 그리고 사용자 버퍼를 물리 페이지로 고정(pin)하는 것이다. io_uring 은 이 둘을 미리 해둘 수 있다.

```c
// 파일 등록 — fd → struct file* 변환을 1회로
int fds[] = { conn1, conn2, conn3 };
io_uring_register_files(&ring, fds, 3);

sqe->flags |= IOSQE_FIXED_FILE;
sqe->fd = 0;                        // 등록 배열의 인덱스

// 버퍼 등록 — 페이지 pin/unpin 을 1회로
struct iovec iov[] = { { .iov_base = buf, .iov_len = BUF_SIZE } };
io_uring_register_buffers(&ring, iov, 1);

io_uring_prep_read_fixed(sqe, 0, buf, BUF_SIZE, offset, 0);  // buf_index=0
```

`O_DIRECT` NVMe 읽기에서 등록 버퍼는 특히 효과가 크다. 매번 `get_user_pages()` 로 페이지를 잠그는 비용이 사라지고, DMA 매핑을 재사용할 수 있다. 고 IOPS 환경에서 측정 가능한 차이가 난다.

버퍼 등록은 `RLIMIT_MEMLOCK` 을 소비하므로 컨테이너 환경에서 한도에 걸리기 쉽다. 커널 5.12 이상에서 일부 완화됐지만 컨테이너 런타임의 `--ulimit memlock` 설정을 확인해야 한다.

**제공 버퍼(provided buffers)** 는 다른 문제를 푼다. 수만 개 연결에 각각 버퍼를 미리 할당하면 메모리가 낭비된다. 대신 버퍼 풀을 커널에 등록해두고 `IOSQE_BUFFER_SELECT` 를 쓰면, 데이터가 실제로 도착한 요청에만 커널이 버퍼를 골라 붙여준다. CQE 의 `flags` 상위 비트에 선택된 버퍼 ID 가 실린다. 커널 5.19 의 링 매핑 버퍼(`IORING_REGISTER_PBUF_RING`)는 이 등록조차 링으로 처리해 시스템콜을 없앤다.

## 6. 연산 연결과 순서 보장

io_uring 은 기본적으로 제출 순서와 완료 순서를 보장하지 않는다. 순서가 필요하면 플래그로 표현한다.

- `IOSQE_IO_LINK`: 다음 SQE 는 이 SQE 가 끝난 뒤 시작. 실패하면 뒤따르는 링크 전체가 `-ECANCELED` 로 취소된다.
- `IOSQE_IO_HARDLINK`: 앞이 실패해도 뒤를 실행.
- `IOSQE_IO_DRAIN`: 앞서 제출된 모든 요청이 완료될 때까지 이 요청을 시작하지 않음.

링크는 강력하지만 디버깅이 어렵다. 중간 실패 시 뒤의 CQE 들이 전부 `-ECANCELED` 로 오는데, `user_data` 로 어느 단계가 원인이었는지 역추적해야 한다. 체인 길이는 짧게 유지하는 편이 낫다.

**멀티샷** 연산은 SQE 하나로 여러 CQE 를 받는다. `IORING_OP_ACCEPT` 에 `IORING_ACCEPT_MULTISHOT` 을 주면 새 연결마다 CQE 가 온다. CQE 의 `IORING_CQE_F_MORE` 플래그가 서 있으면 "이 SQE 는 아직 살아 있다"는 뜻이고, 플래그가 꺼진 CQE 가 마지막이다. 재제출 비용이 사라지므로 accept 루프에서 효과가 크다.

## 7. 보안 이력과 도입 판단

io_uring 은 강력한 만큼 커널 공격 표면을 크게 넓혔다. 2023년 구글 보안팀은 자사 제품에서 io_uring 을 기본 비활성화했다고 밝혔고, 그 근거로 다수의 권한 상승 취약점을 들었다. ChromeOS 와 Android 도 제한적으로 다뤘다. 문제의 성격은 대체로 (1) 비동기 실행 컨텍스트에서 자격 증명 처리가 꼬이는 경우, (2) 참조 카운트 경합으로 인한 use-after-free, (3) 워커 스레드의 권한 상속 문제였다.

대응 수단은 다음과 같다.

```bash
# 완전 차단 (커널 6.6+)
sysctl -w kernel.io_uring_disabled=2

# 특권 그룹만 허용
sysctl -w kernel.io_uring_disabled=1
sysctl -w kernel.io_uring_group=<gid>
```

seccomp 로 `io_uring_setup`/`io_uring_enter`/`io_uring_register` 를 막는 방법도 있지만, io_uring 의 특징상 **한 번 링을 만들면 그 안의 오퍼코드는 seccomp 가 보지 못한다.** 즉 seccomp 로 `openat` 을 막아도 io_uring 의 `IORING_OP_OPENAT` 은 통과한다. 컨테이너 보안 프로파일을 설계할 때 반드시 고려해야 하는 지점이며, 이것이 많은 보안 팀이 io_uring 을 꺼리는 실질적 이유다. Docker 의 기본 seccomp 프로파일은 io_uring 계열 시스템콜을 차단한다.

도입 판단 기준을 정리하면 이렇다. 신뢰 경계 안에서 도는 자체 서비스이고, I/O 동시성이 수천 이상이며, 커널 버전을 통제할 수 있다면 io_uring 이 준다. 멀티테넌트 환경이거나 신뢰할 수 없는 코드를 실행하는 샌드박스라면 비활성화가 합리적이다.

## 8. 실측 설계와 해석

성능 비교를 할 때 흔한 실수는 동시성이 낮은 벤치마크를 돌리는 것이다. 다음 조건을 갖춰야 의미 있는 숫자가 나온다.

- 큐 깊이를 32 이상으로 두고 배치 제출 — 깊이 1 이면 io_uring 이 진다
- `O_DIRECT` 와 버퍼드 I/O 를 분리 측정 — 페이지 캐시 적중 시 io_uring 이점이 사라진다
- CPU 사용률을 함께 기록 — SQPOLL 은 처리량이 올라도 CPU 효율은 나빠질 수 있다
- 완화 대책 상태 확인 — `cat /sys/devices/system/cpu/vulnerabilities/*` 결과에 따라 시스템콜 비용이 몇 배 차이 난다

`fio` 는 `ioengine=io_uring` 을 지원하므로 `ioengine=libaio` 및 `ioengine=psync` 와 나란히 비교할 수 있다.

```bash
fio --name=t --filename=/dev/nvme0n1 --rw=randread --bs=4k \
    --ioengine=io_uring --iodepth=64 --sqthread_poll=1 --fixedbufs=1 \
    --direct=1 --runtime=30 --time_based --group_reporting
```

해석할 때는 IOPS 단독이 아니라 `IOPS / CPU 사용률` 을 본다. io_uring 의 본질적 기여는 같은 CPU 로 더 많은 I/O 를 처리하는 것이지, 장치 한계를 넘는 것이 아니다. 디바이스가 이미 포화라면 인터페이스를 바꿔도 IOPS 는 그대로고 CPU 만 남는다 — 그 남은 CPU 를 애플리케이션 로직에 쓸 수 있다는 것이 실제 이득이다.

## 참고

- Jens Axboe, "Efficient IO with io_uring" — https://kernel.dk/io_uring.pdf
- liburing 저장소 및 man 페이지 — https://github.com/axboe/liburing
- Linux man-pages: io_uring(7), io_uring_setup(2), io_uring_enter(2) — https://man7.org/linux/man-pages/man7/io_uring.7.html
- Google Security Blog, "Learnings from kCTF VRP's 42 Linux kernel exploits" — https://security.googleblog.com/2023/06/learnings-from-kctf-vrps-42-linux.html
- LWN.net, io_uring 관련 기사 모음 — https://lwn.net/Kernel/Index/#io_uring
- fio 문서, I/O engine 섹션 — https://fio.readthedocs.io/
