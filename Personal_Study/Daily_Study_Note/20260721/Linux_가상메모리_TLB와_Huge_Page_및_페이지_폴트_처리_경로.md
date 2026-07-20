Notion 원본: https://app.notion.com/p/3a35a06fd6d381c69314d46b04210ae5

# Linux 가상메모리 TLB와 Huge Page 및 페이지 폴트 처리 경로

> 2026-07-21 신규 주제 · 확장 대상: OS

## 학습 목표

- 다단계 페이지 테이블 워크와 TLB 가 주소 변환 비용을 결정하는 구조를 설명한다
- Huge Page(2MB/1GB)와 THP 가 TLB 미스와 워크 비용을 줄이는 원리를 구분한다
- Minor/Major 페이지 폴트의 처리 경로와 성능 영향을 추적한다
- 실측 도구로 TLB 미스와 폴트를 진단하고 튜닝 판단 기준을 세운다

## 1. 가상 주소가 물리 주소로 바뀌는 비용

x86-64 는 4단계(PML4 → PDPT → PD → PT) 페이지 테이블을 쓴다. 캐시 미스가 나면 한 번의 주소 변환에 최대 4번의 메모리 접근이 든다. 이 비용을 감추는 것이 TLB(Translation Lookaside Buffer)다. TLB 히트면 페이지 테이블 워크를 건너뛰고 즉시 물리 주소를 얻는다.

## 2. TLB 의 한계와 워킹셋 크기

L2 TLB 2048개면 4KB 페이지 기준 커버 범위는 8MB 에 불과하다. 워킹셋이 8MB 를 넘으면 TLB 미스가 급증한다. DB 버퍼 풀, JVM 힙처럼 수 GB 를 무작위 접근하는 워크로드는 TLB thrashing 에 빠진다.

## 3. Huge Page

2MB 페이지를 쓰면 TLB 엔트리 하나가 2MB 를 커버해 커버 메모리가 512배 늘고, 워크 단계도 준다. Linux 는 명시적 HugeTLB 와 자동 THP 두 방식을 제공한다.

```bash
echo 512 > /proc/sys/vm/nr_hugepages
cat /proc/meminfo | grep -i huge
cat /sys/kernel/mm/transparent_hugepage/enabled
```

## 4. THP 의 이점과 함정

`always` 모드는 direct compaction 을 유발해 할당 스레드를 멈추고, Redis 는 fork 저장에서 CoW 페이지가 2MB 단위로 복사되어 지연이 튀다고 경고한다.

```c
void *p = mmap(NULL, 512UL*1024*1024, PROT_READ|PROT_WRITE,
		MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
madvise(p, 512UL*1024*1024, MADV_HUGEPAGE);
```

처리량이 왕인 배치는 always, 지연이 왕인 온라인 서비스는 madvise 또는 never 가 일반적 선택이다.

## 5. 페이지 폴트 — Minor 와 Major

Minor fault 는 페이지가 메모리에 있지만 매핑만 안 된 경우로 디스크 I/O 가 없어 마이크로초 단위다. Major fault 는 디스크에서 읽어와 밀리초 단위로 수천 배 비싸고, 급증은 메모리 부족·스와핑 신호다.

```bash
ps -o min_flt,maj_flt,cmd -p <PID>
```

## 6. Demand Paging 과 Copy-on-Write

mmap/malloc 으로 영역을 잡아도 첫 접근 순간 마이너 폴트로 붙인다. fork 는 읽기 전용 공유 후 쓰기에서만 복사한다(CoW). CoW 단위가 2MB 로 커지면 한 바이트만 바꿔도 2MB 를 복사한다.

## 7. 실측 진단 — perf

```bash
perf stat -e dTLB-load-misses,dTLB-loads,\
dtlb_load_misses.walk_duration ./myapp
```

`dTLB-load-misses / dTLB-loads` 비율과 walk_duration 이 크면 huge page 도입을 검토한다. 적용 전후로 이 지표를 비교하는 것이 정공법이다.

## 8. JVM·DB 적용

JVM 은 `-XX:+UseLargePages` 로 힙을 HugeTLB 에 엹고, Oracle·PostgreSQL 도 SGA/shared_buffers 를 huge page 에 올리는 것을 권장한다. huge page 는 큰 메모리를 무작위로 접근하는 장수 프로세스에 가장 큰 이득을 주고, 짧게 살고 메모리를 조금 쓰는 프로세스에는 손해가 될 수 있다.

## 참고

- Linux Kernel Documentation: admin-guide/mm/transhuge, hugetlbpage
- Intel SDM Vol.3: Paging, TLBs
- Ulrich Drepper, What Every Programmer Should Know About Memory
- Redis 공식 문서: Latency troubleshooting — Transparent Huge Pages
