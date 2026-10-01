Notion 원본: https://app.notion.com/p/3ec5a06fd6d38101b4fddd224258632f?pvs=204

# Linux 페이지 캐시와 mmap 및 Writeback 동작 원리와 fsync 내구성 보장 경계

> 2026-10-01 신규 주제 · 확장 대상: OS 메모리·파일시스템

## 학습 목표

- read/write 와 mmap 이 같은 페이지 캐시를 공유하는 경로를 추적한다
- dirty 페이지가 flusher 스레드로 넘어가는 시점을 sysctl 로 제어한다
- fsync, fdatasync, msync, sync_file_range 가 보장하는 범위를 구분한다
- 쓰기 오류가 fsync 에서 사라지는 조건을 재현하고 방어 패턴을 적용한다

## 1. 페이지 캐시는 파일 I/O 의 기본 경로다

리눅스에서 일반적인 파일 읽기와 쓰기는 디스크를 직접 건드리지 않는다. 각 inode 는 address_space 라는 구조를 가지고, 이 안에 파일 오프셋별로 메모리 페이지(최근 커널에서는 folio 단위)가 인덱싱되어 있다. read(2) 는 해당 오프셋의 페이지가 캐시에 있으면 그 내용을 사용자 버퍼로 복사하고, 없으면 블록 계층에 읽기 요청을 보낸 뒤 채워진 페이지에서 복사한다. write(2) 는 캐시 페이지에 데이터를 복사하고 그 페이지를 dirty 로 표시한 뒤 곧바로 반환한다. 즉 write 가 성공했다는 사실은 "커널 메모리에 올라갔다"는 뜻일 뿐 "디스크에 있다"는 뜻이 아니다. 이 비대칭이 이후 모든 내구성 논의의 출발점이다.

캐시 상태는 /proc/meminfo 에서 바로 관찰된다. Cached 는 페이지 캐시 크기, Dirty 는 아직 쓰이지 않은 페이지, Writeback 은 현재 장치로 전송 중인 페이지, Mapped 는 프로세스 주소 공간에 매핑된 캐시 페이지를 나타낸다. util-linux 의 fincore 는 특정 파일이 캐시에 얼마나 올라와 있는지 보여 준다.

```bash
# 캐시/더티 상태 관찰
grep -E '^(Cached|Dirty|Writeback|Mapped):' /proc/meminfo

# 2GiB 파일 생성 후 캐시 적재량 확인 (fincore 는 util-linux 2.30 이상)
dd if=/dev/zero of=/tmp/pc.bin bs=1M count=2048 status=none
fincore /tmp/pc.bin

```

trade-off 는 명확하다. 캐시는 반복 읽기와 쓰기 합치기(write combining)로 처리량을 크게 높이지만, 전원 손실 시 dirty 페이지는 사라진다. 얼마나 빨라지는지는 환경에 따라 다름이므로 수치를 일반화하지 않고 직접 측정해야 한다.

## 2. mmap 은 같은 캐시 페이지를 주소 공간에 노출한다

mmap(2) 로 파일을 MAP_SHARED 매핑하면 별도의 복사본이 생기는 것이 아니라 페이지 캐시의 바로 그 페이지가 프로세스 페이지 테이블에 연결된다. 매핑 직후에는 페이지 테이블 항목이 비어 있고, 처음 접근할 때 페이지 폴트가 발생해 커널이 캐시에서 페이지를 찾아 연결한다. 캐시에 이미 있으면 minor fault, 디스크 읽기가 필요하면 major fault 다. 이 때문에 read(2) 와 mmap 으로 같은 파일을 접근하면 서로의 쓰기가 일관되게 보인다. 둘 다 같은 페이지를 보기 때문이다.

쓰기 추적은 하드웨어의 도움을 받는다. 매핑된 페이지는 처음에 쓰기 불가로 연결되고, 첫 쓰기에서 폴트가 나면 커널이 페이지를 dirty 로 표시하고 쓰기 가능으로 바꾼다. 이후 writeback 이 페이지를 쓴 뒤에는 다시 쓰기 보호를 걸어 다음 변경을 감지한다. MAP_PRIVATE 는 쓰기 시점에 복사(COW)하므로 파일에 반영되지 않는다.

```c
// mmap_write.c : gcc -O2 -o mmap_write mmap_write.c
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

int main(void) {
    int fd = open("/tmp/mm.dat", O_RDWR | O_CREAT, 0644);
    if (fd < 0) { perror("open"); return 1; }
    if (ftruncate(fd, 4096) < 0) { perror("ftruncate"); return 1; }

    char *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) { perror("mmap"); return 1; }

    memcpy(p, "hello", 5);            // dirty 표시만 됨, 디스크 쓰기 아님
    if (msync(p, 4096, MS_SYNC) < 0)  // 이 호출이 반환되어야 장치로 내려감
        perror("msync");

    munmap(p, 4096);
    close(fd);
    return 0;
}
```

주의할 점이 세 가지 있다. 첫째, 파일 끝을 포함한 마지막 페이지 너머의 매핑 영역에 접근하면 SIGBUS 가 발생하고, 다른 프로세스가 파일을 truncate 해서 매핑이 파일 범위를 벗어나게 되어도 같은 신호가 온다. 둘째, msync 의 MS_ASYNC 는 리눅스 2.6.19 이후 사실상 아무 일도 하지 않는다. 커널이 dirty 를 스스로 추적하기 때문이며 man 페이지에도 명시되어 있다. 셋째, mmap 접근은 에러를 반환값으로 알려 줄 방법이 없다. 읽기 중 I/O 오류는 SIGBUS 로, 쓰기 오류는 나중의 msync/fsync 에서야 드러난다. 그래서 데이터베이스들은 중요한 쓰기에 mmap 대신 pwrite 와 fsync 를 쓰는 경우가 많다. 이 선택은 구현마다 다르며 일괄 규칙은 아니다.

## 3. Writeback 은 flusher 스레드가 임계값 기준으로 수행한다

dirty 페이지는 백그라운드 flusher 커널 스레드(프로세스 목록에서 kworker 로 보이며 이름은 flush-장치번호 형태로 나타나기도 한다)가 장치로 내려 쓴다. 이 스레드는 backing device 별 writeback 컨텍스트(bdi_writeback)마다 동작한다. 언제 쓰는지는 세 종류의 조건이 결정한다. 주기적으로 깨어나서 일정 시간 이상 dirty 상태였던 페이지를 쓰고, dirty 총량이 백그라운드 임계값을 넘으면 비동기로 쓰기 시작하며, 총량이 하드 임계값에 접근하면 쓰기를 호출한 프로세스 자체를 balance_dirty_pages 에서 재워 속도를 늦춘다.

임계값은 sysctl 로 조절한다. 공식 문서(Documentation/admin-guide/sysctl/vm.rst)에 따르면 기본값은 vm.dirty_background_ratio=10, vm.dirty_ratio=20, vm.dirty_expire_centisecs=3000(30초), vm.dirty_writeback_centisecs=500(5초)이다. 비율의 분모는 전체 메모리가 아니라 "dirty 로 쓸 수 있는 가용 메모리"(free 와 회수 가능한 페이지 합)이며, 바이트 단위 값(dirty_background_bytes, dirty_bytes)을 지정하면 비율 설정은 무시된다.

```bash
sysctl vm.dirty_background_ratio vm.dirty_ratio \
       vm.dirty_expire_centisecs vm.dirty_writeback_centisecs

# 대용량 메모리 서버에서 비율 기반 임계값은 지나치게 커질 수 있어 바이트 단위가 낫다
sudo sysctl -w vm.dirty_background_bytes=$((256*1024*1024))
sudo sysctl -w vm.dirty_bytes=$((1024*1024*1024))
```

임계값을 높이면 쓰기 합치기가 잘 되고 버스트를 흡수하지만, 한꺼번에 밀려 나갈 때 지연 스파이크가 커지고 정전 시 잃는 데이터도 늘어난다. 낮추면 지연은 평탄해지지만 처리량과 SSD 쓰기 효율이 떨어질 수 있다. 어느 쪽이 맞는지는 장치 대역폭과 워크로드에 달려 있으므로 환경에 따라 다름이며, /proc/meminfo 의 Dirty 값을 두 설정에서 비교해 정하는 것이 안전하다.

## 4. fsync, fdatasync, msync 가 보장하는 범위

fsync(2) 는 해당 파일 디스크립터가 가리키는 파일의 수정된 데이터와 메타데이터를 장치까지 내려 쓰고, 장치가 완료를 보고한 뒤 반환한다. fdatasync(2) 는 이후 데이터를 읽는 데 필요하지 않은 메타데이터(예: mtime)의 쓰기를 생략하지만, 파일 크기처럼 읽기에 필요한 메타데이터는 포함한다. 그래서 크기가 변하지 않는 덮어쓰기 로그에서는 fdatasync 가 저널 커밋 한 번을 아낄 수 있다. 얼마나 아끼는지는 파일시스템과 장치에 따라 다름이다. msync(MS_SYNC) 는 매핑 범위에 대해 비슷한 역할을 하지만, 파일을 새로 만든 경우의 디렉터리 엔트리까지 책임지지는 않는다.

가장 흔한 실수는 디렉터리를 잊는 것이다. man fsync 는 새로 만든 파일의 이름이 디렉터리 엔트리로 영속화되려면 그 디렉터리에도 fsync 를 호출해야 한다고 명시한다. 파일 데이터는 안전한데 정전 후 파일이 보이지 않는 사고는 이 경로에서 난다. 기존 파일을 안전하게 교체하는 표준 패턴은 임시 파일 작성, 임시 파일 fsync, rename, 부모 디렉터리 fsync 의 네 단계다.

```python
import os

def atomic_write(path: str, data: bytes) -> None:
    d = os.path.dirname(os.path.abspath(path))
    tmp = os.path.join(d, ".tmp." + os.path.basename(path))
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    try:
        view = memoryview(data)
        while view:                      # write 는 부분 쓰기가 가능하다
            n = os.write(fd, view)
            view = view[n:]
        os.fsync(fd)                     # 1) 데이터를 장치까지
    finally:
        os.close(fd)
    os.rename(tmp, path)                 # 2) 원자적 교체 (같은 파일시스템 내)
    dfd = os.open(d, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(dfd)                    # 3) 디렉터리 엔트리 영속화
    finally:
        os.close(dfd)
```

## 5. sync_file_range 와 O_DIRECT 는 내구성 도구가 아니다

sync_file_range(2) 는 지정한 바이트 범위의 writeback 을 시작하거나 기다리게 해 주는 호출이라 fsync 의 가벼운 대안처럼 보인다. 그러나 man 페이지는 이 호출이 메타데이터를 쓰지 않고 장치 캐시 flush 도 보장하지 않아 데이터 무결성 목적으로 안전하지 않다고 경고한다. 용도는 대용량 순차 쓰기에서 dirty 페이지가 쌓이지 않도록 미리 내려 보내 지연 스파이크를 평탄화하는 힌트 정도다. 마지막에는 어차피 fsync 가 필요하다.

O_DIRECT 는 페이지 캐시를 우회해 사용자 버퍼에서 장치로 직접 전송하지만, 이것이 곧 내구성은 아니다. 파일 크기 변경이나 블록 할당 같은 메타데이터는 별도로 영속화되어야 하고, 장치의 휘발성 쓰기 캐시도 남아 있다. 따라서 O_DIRECT 로 쓴 뒤에도 파일을 확장하거나 새로 할당했다면 fdatasync 나 fsync 가 필요하다. 반대로 O_DSYNC 와 O_SYNC 로 연 파일은 write 마다 fdatasync 와 fsync 와 같은 의미를 얻는다.

```bash
# 쓰기 방식별 처리 시간 비교: 같은 장치, 같은 파일시스템에서 직접 측정할 것
dd if=/dev/zero of=/tmp/t1 bs=4k count=20000 status=none              # 캐시만
dd if=/dev/zero of=/tmp/t2 bs=4k count=20000 conv=fdatasync status=none # 끝에서 1회 flush
dd if=/dev/zero of=/tmp/t3 bs=4k count=20000 oflag=dsync               # 매 write 마다 flush
```

세 방식의 차이는 보통 큰 폭으로 벌어지지만, 구체적 배율은 장치의 쓰기 캐시와 전원 손실 보호(PLP) 유무, 파일시스템 저널 설정에 크게 좌우되므로 환경에 따라 다름이다. dd 의 conv=fdatasync 와 oflag=dsync 로 얻은 자기 값을 근거로 삼아야 한다.

## 6. 내구성은 장치 쓰기 캐시를 지나야 완성된다

fsync 가 반환되려면 커널이 데이터를 장치에 보낸 뒤 장치가 비휘발 매체에 반영했음을 보장해야 한다. 블록 계층은 이를 위해 REQ_PREFLUSH(이전 쓰기를 먼저 비휘발화)와 REQ_FUA(해당 쓰기를 비휘발 매체까지 직접) 플래그를 사용하고, ext4 와 XFS 는 기본으로 이를 활용해 저널 커밋을 수행한다. 장치가 쓰기 캐시를 가진 것으로 보고하는지는 sysfs 에서 확인할 수 있다. write back 이면 휘발성 캐시가 있어 flush 가 필요하다는 뜻이고, write through 면 flush 요청이 불필요하다고 선언한 것이다.

```bash
cat /sys/block/nvme0n1/queue/write_cache   # "write back" 또는 "write through"
```

아래 표는 호출별로 어디까지 내려가는지 정리한 것이다. 표는 장치가 flush 명령을 정직하게 처리한다는 가정이다. 일부 저가 장치나 가상화 계층이 flush 를 무시하면 어떤 호출도 이를 극복하지 못한다.

| 동작 | 페이지 캐시 | 파일 데이터 장치 반영 | 메타데이터 | 디렉터리 엔트리 |
|---|---|---|---|---|
| write(2) | 반영 | 아님 | 메모리 | 아님 |
| fdatasync(2) | 반영 | 예 | 읽기에 필요한 것만 | 아님 |
| fsync(2) | 반영 | 예 | 예 | 아님 |
| 디렉터리 fd 에 fsync | 해당 없음 | 해당 없음 | 디렉터리 변경 | 예 |
| msync(MS_SYNC) | 반영 | 예 | 파일시스템에 따라 다름 | 아님 |
| sync_file_range | 반영 | 보장 없음 | 아님 | 아님 |

## 7. fsync 오류 처리와 fsyncgate 교훈

writeback 중 I/O 오류가 나면 문제가 생긴다. 쓰기 실패한 페이지를 어떻게 취급할지가 오래 모호했고, 2018년 PostgreSQL 커뮤니티가 fsync 실패 후 재시도가 성공을 돌려주면서도 데이터는 유실되는 현상을 보고해 "fsyncgate" 로 알려졌다. 커널 4.13 부터는 errseq_t 기반으로 오류를 기록해 파일 디스크립터마다 한 번씩 오류를 보게 하는 방향이 되었지만, 실패한 페이지가 clean 으로 표시되어 이후 fsync 가 성공을 반환할 수 있다는 근본 성질은 그대로다. 핵심 교훈은 fsync 가 한 번 실패하면 그 파일의 이전 쓰기가 영속화되었다고 가정할 수 없고, 재시도로 성공해도 믿으면 안 된다는 점이다. PostgreSQL 은 이를 반영해 12 버전부터 fsync 실패 시 기본으로 PANIC 하도록 바꾸었다(data_sync_retry 설정으로 이전 동작 선택 가능).

방어 패턴은 단순하다. 쓰기와 fsync 의 반환값을 반드시 검사하고, 실패하면 같은 페이지 캐시에 기대어 재시도하지 말고 로그 같은 별도 영속 기록에서 복구하거나 프로세스를 중단한다.

## 8. 실무 점검 절차와 설계 기준

위 내용을 종합하면 설계 판단은 세 질문으로 정리된다. 어떤 데이터가 유실되어도 되는가, 그 데이터를 얼마나 빨리 영속화해야 하는가, 오류가 났을 때 어떻게 복구하는가. 캐시와 mmap 을 쓰는 읽기 위주 서비스라면 기본 설정으로 충분한 경우가 많다. 로그나 트랜잭션처럼 확인 응답 이후 유실이 허용되지 않는다면 write 후 fdatasync 를 확인 응답 이전에 호출하고, 여러 요청을 묶는 그룹 커밋으로 flush 비용을 분산한다. 그룹 커밋은 개별 요청 지연을 약간 늘리는 대신 처리량을 크게 늘리는 전형적 trade-off 이며, 배치 크기와 대기 시간은 환경에 따라 다름이다.

운영 점검은 다음 순서로 한다. 먼저 /proc/meminfo 와 /proc/vmstat 의 nr_dirty, nr_writeback 으로 dirty 누적 추이를 본다. 지연 스파이크가 있다면 vm.dirty_bytes 계열을 낮춰 평탄화를 시도한다. 그다음 fsync 지연 분포를 fio 로 측정하고, 장치의 write_cache 값과 PLP 유무를 확인한다. 마지막으로 새 파일 생성 경로에 디렉터리 fsync 가 있는지, 쓰기와 fsync 반환값을 검사하는지 코드 리뷰한다.

## 참고

- Linux kernel documentation: Documentation/admin-guide/sysctl/vm.rst (dirty_ratio, dirty_background_ratio, dirty_expire_centisecs, dirty_writeback_centisecs)
- Linux kernel documentation: Documentation/filesystems/ext4 (data=ordered, auto_da_alloc, commit)
- man-pages: fsync(2), fdatasync(2), msync(2), mmap(2), sync_file_range(2), open(2) 의 O_DIRECT, O_SYNC 항목
- Linux kernel documentation: Documentation/block/writeback_cache_control.rst (REQ_PREFLUSH, REQ_FUA)
- LWN.net, "PostgreSQL's fsync() surprise" (2018) 와 후속 기사: errseq_t 와 fsync 오류 보고 개선
- Thomas Pillai 외, "All File Systems Are Not Created Equal: On the Complexity of Crafting Crash-Consistent Applications" (OSDI 2014)
