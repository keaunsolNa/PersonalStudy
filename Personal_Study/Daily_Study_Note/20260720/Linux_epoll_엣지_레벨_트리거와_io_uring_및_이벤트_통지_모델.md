Notion 원본: https://www.notion.so/3a35a06fd6d38104aceffd3a7eb96a99

# Linux epoll 엣지/레벨 트리거와 io_uring 및 이벤트 통지 모델

> 2026-07-20 신규 주제 · 확장 대상: OS

## 학습 목표

- `select`/`poll`/`epoll` 의 확장성 차이를 자료구조와 시스템콜 비용으로 설명한다
- 레벨 트리거(LT)와 엣지 트리거(ET) 의 동작 차이와 ET 사용 시 필수 조건을 구분한다
- `epoll` 의 기아·thundering herd 문제와 `EPOLLONESHOT`/`EPOLLEXCLUSIVE` 대응을 파악한다
- io_uring 의 제출/완료 큐 모델이 epoll 과 무엇이 근본적으로 다른지 이해한다

## 1. 이벤트 다중화의 필요성

하나의 스레드가 수천 개의 소켓을 동시에 다루려면 어떤 fd 가 지금 읽거나 쓸 준비가 됐는가를 효율적으로 알아야 한다. 각 소켓마다 블로킹 `read` 를 걸면 한 소켓이 데이터를 안 보낼 때 스레드 전체가 멈춘다. 해법이 이벤트 다중화(I/O multiplexing)로, 여러 fd 를 커널에 등록해 두고 준비된 것만 한 번에 통지받는 방식이다. `select`, `poll`, `epoll` 이 이 계보이고, io_uring 은 그 다음 세대의 비동기 I/O 인터페이스다.

## 2. select/poll 의 확장성 한계

`select` 는 감시할 fd 를 비트마스크(`fd_set`)로 커널에 넘긴다. 문제는 매 호출마다 전체 fd 집합을 유저→커널로 복사하고, 커널은 모든 fd 를 선형 순회하며, 반환 후 유저도 다시 전체를 훑어 준비된 fd 를 찾아야 한다는 것이다. 감시 fd 수 `N` 에 대해 매 호출이 `O(N)` 이고, `FD_SETSIZE`(보통 1024) 제한까지 있다. `poll` 은 `pollfd` 배열로 1024 제한을 없씸지만 매 호출마다 전체 배열 복사와 선형 순회라는 `O(N)` 본질은 그대로다.

```c
struct pollfd fds[MAX];
int n = poll(fds, MAX, timeout_ms);
for (int i = 0; i < MAX; i++)
    if (fds[i].revents & POLLIN) handle_read(fds[i].fd);
```

fd 가 만 개인데 그중 준비된 것이 몇 개뿐이어도 매번 만 개를 복사하고 순회하므로, 활성 비율이 낮을수록 낭비가 커진다. 실시간 서버처럼 대다수 연결이 유휴 상태인 워크로드에서 이 비효율이 결정적이다.

## 3. epoll 의 구조: 커널에 등록을 남긴다

`epoll` 은 감시 대상 집합을 커널 안에 영속적으로 유지한다. `epoll_create1()` 로 epoll 인스턴스를 만들고, `epoll_ctl()` 로 fd 를 한 번 등록하면 이후 재복사가 필요 없다. 커널은 등록된 fd 를 레드-블랙 트리로 관리하고, 준비된 fd 만 별도의 ready 리스트에 담는다. `epoll_wait()` 는 이 ready 리스트만 반환하므로, 준비된 fd 수 `M` 에 비례하는 `O(M)` 비용만 든다. 유휴 연결이 아무리 많아도 활성 이벤트만큼만 처리한다.

```c
int ep = epoll_create1(0);
struct epoll_event ev = { .events = EPOLLIN, .data.fd = sockfd };
epoll_ctl(ep, EPOLL_CTL_ADD, sockfd, &ev);

struct epoll_event events[MAX_EVENTS];
int n = epoll_wait(ep, events, MAX_EVENTS, -1);
for (int i = 0; i < n; i++) handle(events[i].data.fd);
```

이 "한 번 등록, 준비된 것만 통지" 구조가 epoll 이 대규모 동시 연결에서 select/poll 을 압도하는 이유다. 커널이 fd별 콜백을 등록해 두고, 소켓에 데이터가 도착하면 그 콜백이 ready 리스트에 fd 를 추가하는 방식이라, 스캔이 아니라 이벤트 구동이다.

## 4. 레벨 트리거(LT) vs 엣지 트리거(ET)

epoll 의 통지 방식은 두 가지다. 레벨 트리거는 "조건이 참인 동안 계속 통지"한다. 엣지 트리거는 "상태가 바뀜는 순간에만 한 번 통지"하고, 그 후에는 소켓 상태가 다시 바뀜기 전까지 침묵한다.

```c
struct epoll_event ev = { .events = EPOLLIN | EPOLLET, .data.fd = fd }; // ET
```

ET 의 핵심 규약은 통지를 받으면 더 이상 읽을 게 없을 때까지 논블로킹으로 전부 읽어야 한다는 것이다. 한 번의 ET 통지에서 버퍼를 다 비우지 않으면, 남은 데이터에 대한 통지가 다시 오지 않아 그 소켓이 멈춘 것처럼 보인다. 따라서 ET 는 반드시 fd 를 논블로킹(`O_NONBLOCK`)으로 설정하고 `EAGAIN` 이 나올 때까지 루프로 읽어야 한다.

```c
while (1) {
    ssize_t c = read(fd, buf, sizeof(buf));
    if (c == -1) {
        if (errno == EAGAIN) break; // 버퍼 소진 완료
        break;
    }
    if (c == 0) { break; } // 상대가 닫음
    process(buf, c);
}
```

## 5. LT/ET 선택의 트레이드오프

LT 는 프로그래밍이 쉽다. 한 번에 다 읽지 않아도 다음 `epoll_wait` 이 다시 알려주므로, 한 이벤트에 한 청크만 처리해도 안전하다. 대신 준비 상태가 유지되는 동안 반복 통지된다. ET 는 통지 횟수를 최소화해 시스템콜 오버헤드를 줄이고, 특히 대량 연결에서 CPU 를 아낌다. 대신 "완전 소진" 규약을 어기면 조용히 멈추는 버그가 생기고, 한 fd 를 다 읽는 동안 다른 fd 가 기아 상태에 빠질 수 있어 공정성 관리가 필요하다.

| 항목 | 레벨 트리거(LT) | 엣지 트리거(ET) |
|---|---|---|
| 통지 조건 | 조건 참인 동안 반복 | 상태 전환 순간 1회 |
| 읽기 규약 | 부분 읽기 허용 | EAGAIN 까지 소진 필수 |
| 논블로킹 필수 | 아님 | 필수 |
| 구현 난이도 | 낮음 | 높음 |
| 시스템콜 횟수 | 상대적 많음 | 적음 |

nginx 는 ET 를 쓰고, 많은 이벤트 루프 라이브러리는 안전한 LT 를 기본으로 한다. 어느 쪽이 절대적으로 빠르다기보다, ET 는 극한의 연결 수에서 통지 오버헤드를 줄이는 대신 코드 복잡도와 기아 관리를 감수하는 선택이다.

## 6. thundering herd 와 EPOLLEXCLUSIVE

여러 스레드/프로세스가 같은 listen 소켓을 각자의 epoll 에 등록하고 `accept` 를 기다리면, 새 연결 하나에 모두가 깨어나 경쟁하는 thundering herd 가 발생한다. Linux 4.5 부터 `EPOLLEXCLUSIVE` 플래그로 이를 완화한다. 같은 fd 에 이 플래그로 등록된 대기자 중 하나(또는 소수)만 깨우도록 커널이 보장한다.

```c
struct epoll_event ev = { .events = EPOLLIN | EPOLLEXCLUSIVE, .data.fd = listen_fd };
epoll_ctl(ep, EPOLL_CTL_ADD, listen_fd, &ev);
```

또 다른 접근은 `SO_REUSEPORT` 로, 여러 프로세스가 같은 포트에 각자의 listen 소켓을 열고 커널이 연결을 프로세스별로 분배한다. 단일 fd 를 여러 워커가 다룰 때는 `EPOLLONESHOT` 도 유용하다. 한 번 통지 후 그 fd 를 비활성화해 두 워커가 동시에 같은 fd 를 처리하는 경쟁을 막고, 처리 후 `EPOLL_CTL_MOD` 로 재무장한다.

## 7. io_uring: 통지에서 완료로

epoll 은 여전히 "준비됐다"를 알려줄 뿐, 실제 `read`/`write` 시스템콜은 유저가 별도로 호출해야 하는 readiness 모델이다. io_uring 은 이를 근본적으로 바꿔, 유저가 I/O 요청 자체를 커널에 제출하고 완료 결과를 받는 completion 모델이다. 유저와 커널이 공유하는 두 개의 링 버퍼, 제출 큐(SQ)와 완료 큐(CQ)를 통해 통신한다. 유저는 SQ 에 요청(SQE)을 채우고, 커널은 처리 후 CQ 에 결과(CQE)를 넣는다.

```c
struct io_uring ring;
io_uring_queue_init(256, &ring, 0);

struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_read(sqe, fd, buf, len, offset);
io_uring_submit(&ring);

struct io_uring_cqe *cqe;
io_uring_wait_cqe(&ring, &cqe);
int bytes = cqe->res;
io_uring_cqe_seen(&ring, cqe);
```

핵심 이점은 배칭이다. 여러 I/O 요청을 SQ 에 쌓아 한 번의 `io_uring_submit` 으로 제출하면 시스템콜 횟수가 급감한다. `SQPOLL` 모드에서는 커널 스레드가 SQ 를 폴링해 유저가 시스템콜 없이 요청을 밀어 넣을 수도 있다. 또한 epoll 이 소켓에만 효과적인 것과 달리 io_uring 은 파일 I/O, `accept`, `connect`, `fsync` 등 폭넓은 연산을 비동기로 다룬다.

## 8. io_uring 의 현실과 선택 기준

io_uring 은 강력하지만 만능은 아니다. 초기 커널 구현에서 여러 보안 취약점이 발견되어, 일부 배포판과 컨테이너 런타임은 seccomp 으로 io_uring 을 비활성화하기도 했다. 인터페이스가 복잡해 올바르게 쓰기 어렵고, 커널 버전에 따라 지원 연산이 다르다. 반면 epoll 은 성숙하고 이식성이 좋으며 대부분의 네트워크 서버에 충분하다. 실무 선택 기준은, 소켓 다중화가 주목적이고 이식성·안정성이 중요하면 epoll(LT 기본, 극한 성능이 필요하면 ET)이 여전히 표준이고, 파일 I/O 를 대량 비동기로 처리하거나 시스템콜 오버헤드가 병목으로 측정되는 고성능 스토리지/DB 계층이라면 io_uring 의 배칭과 completion 모델이 유의미한 이득을 준다. 두 모델의 근본 차이(readiness vs completion)를 이해하면 워크로드에 맞는 쪽을 고를 수 있다.

## 참고

- Linux man-pages — epoll(7), epoll_ctl(2), epoll_wait(2)
- Linux man-pages — poll(2), select(2)
- Efficient IO with io_uring — Jens Axboe (kernel.org)
- The C10K problem — Dan Kegel
