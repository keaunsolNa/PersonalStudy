Notion 원본: https://www.notion.so/3ef5a06fd6d381d38911f853e5889924

# Linux epoll Edge Level Trigger와 io_uring 완료 큐 모델 비교

> 2026-10-03 신규 주제 · 확장 대상: Linux 페이지 캐시와 mmap 및 Writeback fsync 내구성, Java Virtual Threads와 Pinning 회피

## 학습 목표

- 준비 통지(readiness) 모델인 epoll과 완료 통지(completion) 모델인 io_uring의 차이를 시스템 콜 호출 관점에서 설명한다.
- Level-Triggered와 Edge-Triggered의 깨우기 횟수 차이를 코드로 측정하고 ET의 "EAGAIN까지 읽기" 규칙을 적용한다.
- `EPOLLONESHOT`, `EPOLLEXCLUSIVE`로 멀티스레드 이벤트 루프의 중복 처리와 thundering herd를 제어한다.
- io_uring의 SQ/CQ 링 버퍼 구조와 SQPOLL, 등록 버퍼의 trade-off를 판단한다.

## 1. 이벤트 루프의 근본 문제

소켓 수천 개를 스레드 몇 개로 다루려면 "어느 소켓이 지금 읽을 수 있는가"를 효율적으로 알아야 한다. `select`와 `poll`은 호출할 때마다 전체 fd 목록을 커널에 넘기고, 커널이 전부 훑는다. 비용이 fd 수에 선형으로 비례한다(O(n)). `epoll`은 관심 fd 집합을 **커널 안에 유지**하고, 준비된 fd만 돌려준다. 등록(`epoll_ctl`)은 한 번, 대기(`epoll_wait`)는 준비된 개수에 비례해서만 일한다.

```c
int ep = epoll_create1(0);                       // epoll 인스턴스 생성
struct epoll_event ev = { .events = EPOLLIN, .data.fd = fd };
epoll_ctl(ep, EPOLL_CTL_ADD, fd, &ev);           // 관심 fd 등록 (1회)

struct epoll_event ready[64];
int n = epoll_wait(ep, ready, 64, -1);           // 준비된 fd만 반환
for (int i = 0; i < n; i++) { /* ready[i].data.fd 처리 */ }
```

커널은 내부적으로 관심 목록을 레드-블랙 트리로, 준비된 목록을 연결 리스트(ready list)로 관리한다. 드라이버나 네트워크 스택이 fd에 데이터가 도착했음을 알리면 콜백이 해당 항목을 ready list에 넣고 대기 중인 스레드를 깨운다. Java의 `Selector`(NIO)는 리눅스에서 이 epoll을 사용하며, Netty의 네이티브 epoll 전송도 같은 기반이다.

## 2. Level-Triggered 와 Edge-Triggered

**Level-Triggered(LT, 기본값)** 는 "지금 읽을 데이터가 남아있는 동안" 계속 준비 상태로 보고한다. 일부만 읽고 돌아와도 `epoll_wait`는 다시 그 fd를 돌려준다. 프로그래밍이 쉽고 안전하다. **Edge-Triggered(ET, `EPOLLET`)** 는 "상태가 바뀌는 순간"에만 한 번 보고한다. 데이터가 남아 있어도 새 데이터가 도착하지 않으면 다시 알려주지 않는다.

차이를 직접 측정했다. 소켓 쌍에 100바이트를 쓰고, 한 번에 10바이트씩만 읽으면서 `epoll_wait`를 반복 호출하는 코드다(gcc, Linux 6.18 환경에서 컴파일·실행).

```c
static int count_wakeups(int edge) {
	int sv[2];
	socketpair(AF_UNIX, SOCK_STREAM, 0, sv);
	set_nonblock(sv[0]);
	int ep = epoll_create1(0);
	struct epoll_event ev = { .events = EPOLLIN | (edge ? EPOLLET : 0), .data.fd = sv[0] };
	epoll_ctl(ep, EPOLL_CTL_ADD, sv[0], &ev);
	char big[100]; memset(big, 'x', sizeof big);
	write(sv[1], big, sizeof big);
	int wakeups = 0; char buf[10];
	for (int i = 0; i < 20; i++) {
		struct epoll_event out;
		if (epoll_wait(ep, &out, 1, 0) <= 0) break;   // timeout 0: 대기하지 않음
		wakeups++;
		read(sv[0], buf, sizeof buf);                 // 일부만 읽는다
	}
	return wakeups;
}
// 실행 결과: LT wakeups=10, ET wakeups=1
```

LT는 데이터가 모두 소진될 때까지 10번 보고했고, ET는 처음 한 번만 보고했다. ET에서 나머지 90바이트는 **새 데이터가 도착하기 전에는 영원히 통지되지 않는다.** 이것이 ET의 가장 흔한 버그(요청이 멈춘 듯 보이는 현상)의 원인이다.

### ET 사용 규칙

ET를 쓸 때는 두 가지를 반드시 지킨다. 첫째, fd를 **논블로킹**으로 설정한다. 둘째, 통지를 받으면 `read`가 `EAGAIN`(`EWOULDBLOCK`)을 반환할 때까지 **반복해서 끝까지 읽는다.** 쓰기도 마찬가지로 `EAGAIN`까지 쓰고, 남은 데이터는 버퍼링한 뒤 다시 `EPOLLOUT` 통지를 기다린다.

```c
static void drain(int fd) {
	char buf[4096];
	for (;;) {
		ssize_t n = read(fd, buf, sizeof buf);
		if (n > 0) { /* 처리 */ continue; }
		if (n == 0) { close(fd); return; }              // 상대가 연결을 닫음
		if (errno == EAGAIN || errno == EWOULDBLOCK) return;  // 다 읽음: 다음 edge를 기다림
		if (errno == EINTR) continue;
		close(fd); return;                               // 오류
	}
}
```

ET의 장점은 시스템 콜 감소다. LT는 처리를 미루는 fd를 계속 다시 보고하지만, ET는 한 번만 알려준다. 단점은 위의 규칙을 어기면 데이터가 멈춘다는 점과, 한 연결이 계속 데이터를 보내면 `drain` 루프가 이벤트 루프를 독점해 **다른 연결이 굶는** 문제다. 이를 막으려면 연결당 읽기 횟수나 바이트 상한을 두고 초과 시 다음 순서로 넘긴다. 이 경우 ET에서는 남은 데이터를 수동으로 다시 스케줄해야 한다는 점이 LT와 다르다.

## 3. 멀티스레드 이벤트 루프의 함정

여러 스레드가 하나의 epoll fd를 `epoll_wait`로 공유하면, 같은 fd의 이벤트를 두 스레드가 동시에 처리할 수 있다. 스레드 A가 읽는 중에 새 데이터가 도착해 스레드 B가 같은 fd를 깨워 처리하면 순서가 꼬이거나 소켓이 중복 close된다. `EPOLLONESHOT`은 이벤트를 한 번 전달한 뒤 해당 fd를 **비활성화**한다. 처리가 끝나면 `epoll_ctl(EPOLL_CTL_MOD)`로 다시 활성화해야 다음 이벤트를 받는다.

```c
struct epoll_event ev = { .events = EPOLLIN | EPOLLET | EPOLLONESHOT, .data.fd = fd };
epoll_ctl(ep, EPOLL_CTL_ADD, fd, &ev);
// 워커가 처리를 끝낸 뒤
ev.events = EPOLLIN | EPOLLET | EPOLLONESHOT;
epoll_ctl(ep, EPOLL_CTL_MOD, fd, &ev);   // 재장전
```

listen 소켓을 여러 프로세스·스레드의 epoll이 감시하면 새 연결 하나에 모두가 깨어나는 **thundering herd**가 발생한다. 리눅스 4.5에서 추가된 `EPOLLEXCLUSIVE`는 같은 fd를 여러 epoll 인스턴스가 감시할 때 일부(보통 하나)만 깨우도록 한다. 또는 `SO_REUSEPORT`로 스레드마다 별도 listen 소켓을 두어 커널이 연결을 분산하게 하는 방법이 있다. 어느 쪽이 나은지는 부하 분산 균일성 요구와 커널 버전에 달려 있으므로 실측해야 한다.

또 하나의 제약은 **일반 파일에는 epoll이 소용없다**는 점이다. 일반 파일 fd는 항상 읽기·쓰기 가능으로 간주되어 `epoll_ctl` 등록이 `EPERM`으로 거부된다. 디스크 I/O는 epoll로 비동기화할 수 없어서 별도 스레드 풀에 위임해야 했고, 이것이 io_uring이 등장한 배경이다.

## 4. io_uring: 완료 통지 모델

epoll은 "읽을 수 **있다**"를 알려줄 뿐 실제 `read`는 애플리케이션이 시스템 콜로 수행한다. 요청 한 건당 최소 두 번의 커널 진입(`epoll_wait` + `read`)이 필요하다. io_uring(Linux 5.1)은 모델이 다르다. 애플리케이션이 **"이 read를 해 달라"는 요청을 제출**하고, 커널이 완료한 뒤 **결과를 돌려준다.** 소켓과 파일 모두 같은 방식으로 다룰 수 있다.

핵심은 사용자 공간과 커널이 `mmap`으로 공유하는 두 개의 링 버퍼다. **SQ(Submission Queue)** 에는 애플리케이션이 SQE(요청 항목)를 넣고, **CQ(Completion Queue)** 에서 커널이 쓴 CQE(결과 항목)를 꺼낸다. 요청을 여러 개 SQ에 채운 뒤 `io_uring_enter` 한 번으로 제출하면 시스템 콜 횟수가 크게 줄어든다.

```c
// liburing 사용 예 (개념 예시 — 본 노트 환경에는 liburing 헤더가 없어 컴파일하지 못했다)
#include <liburing.h>

struct io_uring ring;
io_uring_queue_init(256, &ring, 0);

struct io_uring_sqe *sqe = io_uring_get_sqe(&ring);
io_uring_prep_read(sqe, fd, buf, sizeof buf, /*offset*/ 0);
io_uring_sqe_set_data(sqe, my_ctx);       // 완료 시 돌려받을 사용자 데이터

io_uring_submit(&ring);                   // io_uring_enter 호출 (여러 SQE를 한 번에 제출 가능)

struct io_uring_cqe *cqe;
io_uring_wait_cqe(&ring, &cqe);           // 완료 대기
ssize_t n = cqe->res;                     // read 결과 (음수면 -errno)
void *ctx = io_uring_cqe_get_data(cqe);
io_uring_cqe_seen(&ring, cqe);            // CQ 항목 소비 표시
```

`cqe->res`가 음수이면 `-errno`이므로 전역 `errno`를 보지 않는다는 점이 epoll 계열 코드와 다르다. 버퍼는 완료될 때까지 유효해야 하며, 스택 변수를 제출한 뒤 함수를 벗어나면 커널이 해제된 메모리에 쓰는 위험한 버그가 된다. 이 수명 관리가 io_uring 프로그래밍의 가장 큰 진입 장벽이다.

### 고급 기능과 비용

**SQPOLL**을 켤면 커널 스레드가 SQ를 폴링하므로 제출에 `io_uring_enter`조차 필요 없다. 지연은 줄지만 커널 스레드가 CPU를 점유하므로 코어를 소비한다는 비용이 있다. **등록된 버퍼·파일**(`io_uring_register_buffers`, `register_files`)은 요청마다 반복되는 페이지 고정과 fd 참조 비용을 없앤다. **멀티샷**(multishot accept는 5.19, multishot recv는 6.0 이후)은 SQE 하나로 여러 완료를 받아 accept·recv 루프의 재제출 비용을 줄인다. 이들 기능은 커널 버전에 의존하므로 `io_uring_register_probe`로 지원 여부를 확인하는 것이 안전하다.

## 5. 선택 기준과 trade-off

네트워크 서버에서 epoll과 io_uring의 성능 차이는 **워크로드에 크게 의존**한다. 연결 수가 많고 요청이 작은 서버에서는 시스템 콜 감소 효과가 크지만, 이미 epoll 기반으로 최적화된 서버라면 기대한 만큼 차이가 나지 않는 사례가 보고된다. 일반화된 배수를 믿지 말고 자신의 부하로 벤치마크해야 한다.

io_uring의 확실한 강점은 **디스크 I/O의 비동기화**다. epoll로는 불가능했던 일반 파일의 비동기 읽기·쓰기를 스레드 풀 없이 처리한다. 데이터베이스와 스토리지 엔진이 먼저 도입한 이유다. 반면 고려할 비용이 있다. 첫째, 커널 공격 표면이 넓어 보안 이슈가 반복되었고, 일부 환경(컨테이너 기본 seccomp 프로파일, 일부 배포판 설정)은 io_uring을 기본 차단하거나 비활성화한다. 배포 대상에서 사용 가능한지 먼저 확인해야 한다. 둘째, 커널 버전별 기능 격차가 커서 호환성 계층이 필요하다. 셋째, 버퍼 수명과 취소(cancellation) 처리가 복잡하다.

| 비교 항목 | epoll | io_uring |
|---|---|---|
| 통지 모델 | 준비됨(readiness) | 완료됨(completion) |
| 일반 파일 | 지원 안 함(항상 ready) | 지원 |
| 시스템 콜 | 이벤트당 `epoll_wait` + `read`/`write` | 배치 제출·완료 수확, SQPOLL 시 0에 근접 |
| 프로그래밍 난이도 | 중간(ET 규칙 주의) | 높음(버퍼 수명, 취소) |
| 커널 요구 | 2.6 이후 | 5.1 이상(기능별 상이) |

## 6. 언어·프레임워크에서의 위치

Java 개발자 관점에서 연결해 보자. `java.nio.channels.Selector`는 리눅스에서 epoll을 사용하며 LT 방식으로 동작한다. JDK 구현이 ET가 아닌 LT를 쓰므로 `selectedKeys` 처리에서 일부만 읽어도 다음 `select`에서 다시 준비 상태로 나타난다. Netty의 `EpollEventLoopGroup`은 JNI로 epoll을 직접 호출하며 ET 모드를 지원한다(`EpollChannelOption.EPOLL_MODE`). 일반 NIO 대비 GC 부담이 줄고 ET 선택이 가능한 것이 장점이다. Netty에는 io_uring 전송이 별도 모듈로 제공되어 왔으나, 사용 전 해당 버전의 지원 상태와 안정성 표기를 확인해야 한다. 가상 스레드(Project Loom)는 이 위에서 블로킹 스타일 코드를 쓰게 해주는 계층이며, 내부적으로 플랫폼 스레드가 epoll 기반 I/O 완료를 기다린다.

## 7. 디버깅 도구

`strace -c -f -p <pid>`로 시스템 콜 횟수를 비교하면 epoll 서버의 `epoll_wait`/`read` 쌍과 io_uring의 `io_uring_enter` 감소를 확인할 수 있다. `ss -tin`으로 소켓 버퍼 상태를, `/proc/<pid>/fdinfo/<epoll-fd>`에서 epoll이 감시 중인 fd 목록을 볼 수 있다. ET 버그가 의심되면 한 번의 통지당 읽은 바이트 수와 `EAGAIN` 도달 여부를 로깅한다.

## 8. 점검 과제

본문 2절 코드를 직접 컴파일해 `LT wakeups`와 `ET wakeups` 값을 확인하라(실행 결과는 LT 10, ET 1이었다). 이어 `count_wakeups`에 새 데이터를 추가로 쓰는 단계를 넣어 ET가 "새 데이터 도착 시 다시 통지"하는 것을 확인한다. 소켓 버퍼를 `EAGAIN`까지 읽는 `drain` 함수를 적용하면 ET에서도 모든 100바이트가 처리되는지 검증하고, `EPOLLONESHOT`을 붙여 재장전 전까지 두 번째 통지가 오지 않음을 테스트한다. io_uring은 `liburing`을 설치한 환경에서 4절의 read 예제를 파일 대상으로 실행해, 같은 파일을 `pread`로 읽은 결과와 바이트가 일치하는지 비교한다.

## 참고

- man 7 epoll, man 2 epoll_ctl, man 2 epoll_wait
- man 7 io_uring, man 2 io_uring_setup, `liburing` 저장소 문서
- Jens Axboe, "Efficient IO with io_uring"
- Linux 커널 문서: io_uring 및 EPOLLEXCLUSIVE 도입 커밋 설명
