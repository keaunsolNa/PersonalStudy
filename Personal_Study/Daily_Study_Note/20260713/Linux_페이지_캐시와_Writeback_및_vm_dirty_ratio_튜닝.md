Notion 원본: https://app.notion.com/p/39c5a06fd6d381af954ddb18c2c958ad

# Linux 페이지 캐시와 Writeback 및 vm.dirty_ratio 튜닝

> 2026-07-13 신규 주제 · 확장 대상: OS

## 학습 목표

- 페이지 캐시가 파일 I/O를 어떻게 매개하고 read/write 경로가 어디서 갈라지는지 이해한다
- dirty 페이지가 라이트백 스레드(flusher)에 의해 디스크로 내려가는 조건을 분해한다
- `vm.dirty_ratio`/`vm.dirty_background_ratio`/`dirty_expire_centisecs`의 의미와 상호작용을 정리한다
- write-heavy 서버에서 지연 스파이크를 줄이는 튜닝 방향을 실측 관점으로 제시한다

## 1. 페이지 캐시: 파일 I/O의 중간 계층

리눅스는 파일을 읽고 쓸 때 디스크와 직접 주고받지 않고 페이지 캐시를 거친다. `read()`는 캐시에 있으면 디스크를 안 건드리고, 없으면 디스크에서 읽어 캐시에 채운 뒤 반환한다. 남는 메모리를 캐시가 최대한 쓰므로 메모리가 거의 찬 것은 대개 문제가 아니라 캐시가 잘 활용된다는 뜻이다.

```bash
$ free -h
              total   used   free   buff/cache   available
Mem:           31Gi   8Gi    1Gi   22Gi         22Gi
```

`available`이 진짜 여유 메모리다. `buff/cache` 상당 부분은 필요 시 회수 가능하다.

## 2. write 경로: dirty 페이지의 생성

`write()`는 기본적으로 데이터를 페이지 캐시에 쓰고 즉시 반환한다. 캐시에는 갱신됐지만 아직 디스크에 안 내려간 페이지가 dirty page다.

```bash
$ grep -E 'Dirty|Writeback' /proc/meminfo
Dirty:            10240 kB
Writeback:            0 kB
```

지연 쓰기가 성능의 핵심이다. 같은 페이지를 여러 번 쓰면 캐시에서 합쳐지고(coalescing), 순차 dirty를 모아 큰 I/O로 내리면 처리량이 오른다. 대가는 전원 손실 시 유실과 dirty 과다 축적 시 몰아쓰기 지연이다.

## 3. 라이트백: flusher가 언제 내리는가

dirty를 내리는 주체는 커널 라이트백 스레드(flusher)다. 세 계기로 발동한다. 첫째 주기적 만료: `vm.dirty_expire_centisecs`(기본 3000=30초)보다 오래된 dirty를 내린다. flusher는 `vm.dirty_writeback_centisecs`(기본 500=5초)마다 깨어난다. 둘째 백그라운드 임계치: dirty가 `vm.dirty_background_ratio`를 넘으면 백그라운드 라이트백 시작(논블로킹). 셋째 강제 임계치: dirty가 `vm.dirty_ratio`를 넘으면 `write()` 호출 프로세스가 직접 라이트백에 동원되어(throttling) 블로킹된다. 이것이 지연 스파이크의 정체다.

## 4. 네 개의 핵심 파라미터

| 파라미터 | 의미 | 기본값 |
|---|---|---|
| dirty_background_ratio | 초과 시 백그라운드 라이트백(논블로킹) | 10% |
| dirty_ratio | 초과 시 쓰기 프로세스 블로킹 | 20% |
| dirty_expire_centisecs | dirty가 이 시간 지나면 대상 | 3000(30s) |
| dirty_writeback_centisecs | flusher 깨어나는 주기 | 500(5s) |

비율은 회수 가능 메모리 기준 퍼센트다. 128GB 서버에서 `dirty_ratio=20`이면 dirty 20GB를 한꺼번에 내릴 때 수 초간 I/O가 정체된다. 그래서 대용량 메모리 서버는 절대 바이트(`_bytes`) 지정이 권장된다.

```bash
$ sysctl -w vm.dirty_background_bytes=$((256*1024*1024))  # 256MB
$ sysctl -w vm.dirty_bytes=$((1024*1024*1024))            # 1GB
```

## 5. write-heavy 서버 튜닝

DB·로그 서버는 큰 dirty 버퍼가 tail latency를 악화시킨다. 다량 dirty가 한 번에 내려갈 때 fsync 지연이 수백 ms~수 초까지 튄다. 대응은 dirty를 작게 유지해 라이트백을 상시 조금씩 흐르게 하는 것이다.

```bash
# /etc/sysctl.d/60-writeback.conf
vm.dirty_background_bytes = 268435456   # 256MB에서 flush 시작
vm.dirty_bytes = 1073741824             # 1GB에서만 블로킹
vm.dirty_expire_centisecs = 1000        # 10초
```

처리량은 약간 손해 볼 수 있지만 p99 write latency가 크게 안정된다. `/proc/vmstat`의 `nr_dirty`, `nr_writeback`을 관찰하며 부하 테스트로 확인한다.

## 6. fsync와 페이지 캐시

`write()`는 페이지 캐시까지만 보장하므로 디스크에 안전히 남았음을 보장하려면 `fsync()`/`fdatasync()`가 필요하다. DB의 WAL이 `fsync`를 호출하는 이유다. `fsync`는 해당 파일 dirty를 즉시 라이트백하고 완료를 기다리므로, dirty가 많이 쌓이면 `fsync` 자체가 오래 걸린다. `dirty_background_bytes`를 작게 유지하면 커밋 지연이 평탄해진다. PostgreSQL의 `checkpoint_completion_target`도 같은 문제를 애플리케이션 층에서 완화하는 장치다.

## 7. Direct I/O와 O_SYNC

`O_DIRECT`로 열면 read/write가 페이지 캐시를 건너뛰고 디바이스와 직접 DMA한다. 자체 버퍼 풀을 갖는 DB가 이중 캐싱을 피하려 쓴다. 정렬 요구와 캐시 히트 상실이 대가다. `O_SYNC`는 캐시는 쓰되 매 write마다 라이트백 완료를 기다린다. 대부분의 리눅스 파일 I/O는 페이지 캐시 + 명시적 `fsync` 조합이 표준이다.

## 8. 정리

페이지 캐시는 파일 I/O를 빠르게 하지만 dirty 페이지를 관리하지 않으면 tail latency 폭탄이 된다. 기본값은 처리량 위주라 dirty를 크게 허용하는데, 대용량 write-heavy 서버에서는 몰아치기 라이트백과 fsync 지연을 유발한다. 대용량 메모리 서버는 `ratio` 대신 `_bytes`로 dirty 상한을 낮게 고정하고 `dirty_background_bytes`를 작게 잡아 flusher가 상시 조금씩 내리게 만든다. 그러면 포어그라운드 throttling이 사라져 p99 지연이 평탄해진다. 튜닝 후 반드시 부하 테스트로 처리량·지연 균형을 검증한다.

## 참고

- Linux Kernel Documentation: admin-guide/sysctl/vm.rst
- Linux man 2 fsync, man 2 open (O_DIRECT, O_SYNC)
- Robert Love, Linux Kernel Development: The Page Cache and Page Writeback
- PostgreSQL Documentation: WAL Configuration, checkpoint_completion_target
