Notion 원본: https://www.notion.so/3cf5a06fd6d381ac81e4c19620a91cc0

# WebAssembly 실행 모델과 선형 메모리 및 WASI 컴포넌트 모델

> 2026-09-02 신규 주제 · 확장 대상: JVM JIT 컴파일러·바이트코드, eBPF 커널 샌드박스

## 학습 목표

- Wasm 스택 머신 명령 집합과 구조적 제어 흐름이 검증을 어떻게 단순화하는지 추적한다
- 선형 메모리 모델의 격리 보장과 경계 검사 최적화 기법을 구분한다
- WASI Preview 1 의 POSIX 스타일 한계와 Component Model 의 인터페이스 타입 전환을 비교한다
- Envoy·Proxy-Wasm 필터를 예로 호스트 임베딩 경계를 설계한다

## 1. 또 하나의 바이트코드가 아닌 이유

JVM 바이트코드와 Wasm 은 곉보기에 비슷하다. 둘 다 스택 머신이고, 둘 다 JIT 로 네이티브 코드를 만들고, 둘 다 검증 단계를 거친다. 그런데 설계 목표가 다르다.

JVM 바이트코드는 *Java 언어의 의미론* 을 보존하도록 설계됐다. 객체 참조, 가상 디스패치, 예외 테이블, 가비지 컬렉션이 명령 집합 수준에 박혀 있다. 그래서 다른 언어를 얹으면(Kotlin, Scala, Clojure) 잘 맞지만, C 나 Rust 같은 수동 메모리 언어를 얹기는 어색하다.

Wasm 은 반대로 *언어 중립적인 하드웨어 추상화* 를 목표로 한다. 타입은 `i32`, `i64`, `f32`, `f64`, `v128`(SIMD), 그리고 참조 타입 `funcref`/`externref` 뿐이다. 객체도 없고 GC 도 원래 없었다(WasmGC 제안으로 나중에 추가됐다). 메모리는 그냥 바이트 배열이다. 이 최소주의 덕분에 C/C++/Rust 를 거의 오버헤드 없이 컴파일할 수 있고, 검증기가 선형 시간에 안전성을 증명할 수 있다.

eBPF 와 비교하면 또 다르다. eBPF 검증기는 프로그램이 *종료함* 을 증명해야 하므로 루프에 강한 제약(bounded loop)이 있고, 명령 수 상한과 스택 512바이트 제한이 있다. Wasm 은 종료를 증명하지 않는다 — 무한 루프를 허용하고, 대신 호스트가 연료(fuel) 카운팅이나 인터럽트로 잘라낸다. 그 대가로 임의의 계산을 표현할 수 있다.

## 2. 스택 머신과 구조적 제어 흐름

Wasm 명령은 값 스택에서 피연산자를 꺼내 결과를 밀어넣는다.

```wat
(module
  (func $sum (param $n i32) (result i32)
    (local $i i32)
    (local $acc i32)
    (loop $continue
      (local.set $acc
        (i32.add (local.get $acc) (local.get $i)))
      (local.set $i
        (i32.add (local.get $i) (i32.const 1)))
      (br_if $continue
        (i32.lt_u (local.get $i) (local.get $n))))
    (local.get $acc))
  (export "sum" (func $sum)))
```

핵심은 제어 흐름이 **구조적** 이라는 점이다. `block`, `loop`, `if` 는 중첩된 스코프를 만들고, `br`/`br_if`/`br_table` 은 *바깥쪽 레이블로만* 점프한다. 임의 주소로 뛰는 `goto` 가 없다. 결과적으로 제어 흐름 그래프가 항상 축소 가능(reducible)하고, 검증기가 단일 패스로 각 지점의 스택 타입을 추론할 수 있다. JVM 이 스택맵 프레임(StackMapTable 속성)을 별도로 실어야 했던 문제가 구조적 제어 흐름로 사라진다.

이 제약은 컴파일러에 부담을 준다. LLVM 이 만든 비축소 CFG 를 Wasm 으로 낮출 때 Relooper/Stackifier 알고리즘으로 재구조화해야 하고, 때로 상태 변수와 분기가 추가된다.

검증은 다음을 보장한다.

- 모든 명령의 피연산자 타입이 정적으로 일치한다
- 스택 언더플로가 없다
- 분기 대상 레이블이 유효한 스코프 안에 있다
- 함수 인덱스, 테이블 인덱스, 전역 인덱스가 범위 안이다
- 간접 호출은 런타임에 테이블 엔트리의 시그니처를 대조한다

이 검증이 선형 시간에 끝나므로 스트리밍 컴파일이 가능하다. 브라우저는 모듈을 다운로드하면서 동시에 검증·컴파일한다.

## 3. 선형 메모리 — 격리의 실체

Wasm 모듈의 메모리는 하나의 연속된 바이트 배열이다. 페이지 단위(64KiB)로 잡히고 `memory.grow` 로 늘린다.

```wat
(memory $mem 2 100)   ;; 초기 2페이지(128KiB), 최대 100페이지(6.4MiB)
```

모든 로드/스토어는 이 배열 안의 오프셋으로만 표현된다. `i32.load offset=8` 은 "스택 top 의 i32 값 + 8" 위치를 읽는다. 여기서 나올 수 있는 최대 주소는 `2^32 - 1 + offset` 이고, 그 이상은 표현 자체가 불가능하다. 모듈은 자신의 선형 메모리 밖을 *가리킬 방법이 없다*. 호스트 힙, 다른 모듈의 메모리, 스택 프레임(Wasm 의 호출 스택은 별도 관리되어 선형 메모리에 없다)에 접근할 수 없다.

이것이 Wasm 샌드박스의 본질이다. 프로세스 격리나 하드웨어 링이 아니라 *주소 표현 능력의 부재* 다. 그래서 샌드박스 진입/이탈 비용이 함수 호출 수준으로 저렴하다.

경계 검사는 두 방식으로 구현된다.

- **명시적 검사**: 매 접근마다 `if (addr + size > mem_size) trap`. 32비트 호스트나 메모리를 여러 개 쓰는 경우.
- **가드 페이지**: 64비트 호스트에서 8GiB(4GiB 주소 공간 + 오프셋 여유) 가상 주소를 `PROT_NONE` 으로 예약하고, 실제 메모리만 `mmap` 으로 커밋한다. 범위를 벗어난 접근은 SIGSEGV 를 일으키고 런타임이 시그널 핸들러에서 trap 으로 변환한다. 런타임 검사 명령이 0개다.

가드 페이지 방식 덕분에 잘 컴파일된 Wasm 은 네이티브 대비 대략 1.1~1.5배 정도의 실행 시간에 들어온다. 다만 이 이점은 64비트 주소 공간을 넘넘히 예약할 수 있을 때만 유효하며, 인스턴스를 수만 개 띄우는 서버리스 환경에서는 가상 주소 고갈이 실제 제약이 된다.

반대급부도 명확하다. 선형 메모리 *안에서는* 아무 보호도 없다. C 프로그램의 버퍼 오버플로는 Wasm 안에서 그대로 재현되어, 같은 모듈의 다른 변수를 덮어쓴다. ASLR 도 스택 카나리도 없고 힙 메타데이터 보호도 없다. "Wasm 으로 컴파일하면 메모리 안전해진다" 는 오해다. 정확히는 *호스트가 안전해진다*.

## 4. 호스트 임베딩 — 값 전달의 실제

Wasm 함수는 숫자만 주고받는다. 문자열이나 구조체를 넘기려면 선형 메모리에 쓰고 (포인터, 길이) 쌍을 넘겨야 한다. Rust + wasmtime 예다.

게스트 쪽:

```rust
#[no_mangle]
pub extern "C" fn alloc(len: usize) -> *mut u8 {
	let mut buf = Vec::with_capacity(len);
	let ptr = buf.as_mut_ptr();
	std::mem::forget(buf);
	ptr
}

#[no_mangle]
pub extern "C" fn transform(ptr: *mut u8, len: usize) -> u64 {
	let input = unsafe { Vec::from_raw_parts(ptr, len, len) };
	let s = String::from_utf8_lossy(&input).to_uppercase();
	let out = s.into_bytes();
	let out_ptr = out.as_ptr() as u32;
	let out_len = out.len() as u32;
	std::mem::forget(out);
	((out_ptr as u64) << 32) | (out_len as u64)   // 두 값을 하나로 패킹
}
```

호스트 쪽:

```rust
let engine = Engine::new(Config::new().consume_fuel(true))?;
let module = Module::from_file(&engine, "transform.wasm")?;
let mut store = Store::new(&engine, ());
store.set_fuel(10_000_000)?;

let instance = Instance::new(&mut store, &module, &[])?;
let memory = instance.get_memory(&mut store, "memory").unwrap();
let alloc = instance.get_typed_func::<u32, u32>(&mut store, "alloc")?;
let transform = instance.get_typed_func::<(u32, u32), u64>(&mut store, "transform")?;

let input = b"hello wasm";
let ptr = alloc.call(&mut store, input.len() as u32)?;
memory.write(&mut store, ptr as usize, input)?;

let packed = transform.call(&mut store, (ptr, input.len() as u32))?;
let out_ptr = (packed >> 32) as usize;
let out_len = (packed & 0xFFFF_FFFF) as usize;

let mut out = vec![0u8; out_len];
memory.read(&store, out_ptr, &mut out)?;
```

이 보일러플레이트가 Wasm 을 실무에서 쓰기 어렵게 만든 주범이다. 호스트/게스트 언어 조합마다 직렬화 규약을 손으로 맞춰야 하고, 메모리 해제 책임이 모호해 누수가 쉽다. Component Model 이 해결하려는 문제가 정확히 이것이다.

`consume_fuel` 은 명령 실행마다 연료를 차감해 무한 루프를 자른다. 연료가 떨어지면 trap 이 나고 호스트가 제어를 되찾는다. 에포크 인터럽션(`epoch_interruption`)은 별도 스레드가 카운터를 올리고 게스트가 주기적으로 확인하는 방식으로, 연료보다 오버헤드가 훨씬 낮은 대신 정밀도가 떨어진다. 멀티테넌트 환경에서는 에포크 + 벽시계 타임아웃 조합이 일반적이다.

## 5. WASI Preview 1 의 한계

WASI(WebAssembly System Interface)는 파일, 시계, 랜덤, 소켓 같은 시스템 자원 접근을 표준화한다. Preview 1 은 POSIX 를 얇게 감싼 형태다.

```wat
(import "wasi_snapshot_preview1" "fd_read"
  (func $fd_read (param i32 i32 i32 i32) (result i32)))
(import "wasi_snapshot_preview1" "path_open"
  (func $path_open (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
```

권한 모델은 **capability 기반**이다. 게스트는 임의 경로를 열 수 없고, 호스트가 미리 열어준 디렉터리 핸들(preopen)로부터 상대 경로로만 접근한다.

```bash
wasmtime run --dir=/var/data::/data app.wasm
```

게스트가 `/data/report.csv` 를 열면 실제로는 호스트의 `/var/data/report.csv` 다. `../../etc/passwd` 는 런타임이 경로 정규화 후 preopen 루트를 벗어나므로 거부한다. ambient authority 가 없다는 점에서 컨테이너보다 강한 격리다 — 컨테이너는 여전히 전역 네임스페이스 안에서 경로를 해석한다.

Preview 1 의 문제는 세 가지다.

1. **타입 표현력**: 모든 인터페이스가 (i32, i32) 포인터/길이 쌍이다. 문자열, 리스트, 옵션, 결과 타입이 없어 매번 수동 마샤링이다.
2. **비동기 부재**: `poll_oneoff` 하나로 블로킹 I/O 를 흉내 낸다. 진짜 비동기 스트림이 없다.
3. **모듈 조합 불가**: 두 Wasm 모듈이 서로를 호출하려면 선형 메모리 레이아웃을 공유해야 하는데, 각자 자기 메모리를 갖고 있어 불가능하다. 사실상 모놀리식 모듈만 가능하다.

## 6. Component Model 과 인터페이스 타입

Component Model 은 Wasm 모듈 위에 타입 있는 인터페이스 계층을 얹는다. 인터페이스는 WIT(Wasm Interface Type) 로 기술한다.

```
package example:transform@1.0.0;

interface text {
  record options {
    uppercase: bool,
    max-length: option<u32>,
  }

  variant error {
    too-long(u32),
    invalid-utf8,
  }

  transform: func(input: string, opts: options) -> result<string, error>;
}

world processor {
  import wasi:logging/logging@0.1.0;
  export text;
}
```

`string`, `record`, `variant`, `option`, `result`, `list<T>` 같은 고수준 타입이 인터페이스 수준에 존재한다. 툴체인(`wit-bindgen`)이 각 언어의 관용적인 바인딩을 생성하고, 컴포넌트 간 호출 시 **Canonical ABI** 규칙에 따라 값을 한쪽 선형 메모리에서 다른 쪽으로 복사한다.

```rust
wit_bindgen::generate!({ world: "processor" });

struct Processor;

impl exports::example::transform::text::Guest for Processor {
	fn transform(input: String, opts: Options) -> Result<String, Error> {
		if let Some(max) = opts.max_length {
			if input.len() as u32 > max {
				return Err(Error::TooLong(input.len() as u32));
			}
		}
		Ok(if opts.uppercase { input.to_uppercase() } else { input })
	}
}

export!(Processor);
```

앞 절의 포인터 패킹 코드가 전부 사라진다. 더 중요한 것은 두 컴포넌트를 조합할 수 있다는 점이다. 각자 자기 선형 메모리를 가진 채로 `wasm-tools compose` 로 묶으면, 한쪽의 export 가 다른 쪽의 import 에 연결되고 값은 ABI 로 복사된다. 서로의 메모리를 볼 수 없으므로 격리가 컴포넌트 경계에서도 유지된다.

WASI Preview 2 는 이 Component Model 위에 재정의된 표준 인터페이스 집합이다. `wasi:io/streams`, `wasi:http/incoming-handler`, `wasi:clocks`, `wasi:filesystem` 등이 WIT 로 기술되고, 각각 capability 로 주입된다. HTTP 서버를 다음 한 줄짜리 world 로 표현할 수 있다.

```
world proxy {
  import wasi:http/outgoing-handler@0.2.0;
  export wasi:http/incoming-handler@0.2.0;
}
```

## 7. Proxy-Wasm — 실무 임베딩 사례

Envoy 의 Wasm 필터는 Component Model 이전 세대의 ABI(Proxy-Wasm)를 쓴다. 구조를 보면 임베딩 설계의 전형이 드러난다.

```rust
proxy_wasm::main! {{
	proxy_wasm::set_log_level(LogLevel::Info);
	proxy_wasm::set_http_context(|ctx_id, _| -> Box<dyn HttpContext> {
		Box::new(RateLimitFilter { ctx_id, tenant: String::new() })
	});
}}

struct RateLimitFilter {
	ctx_id: u32,
	tenant: String,
}

impl HttpContext for RateLimitFilter {
	fn on_http_request_headers(&mut self, _n: usize, _eos: bool) -> Action {
		self.tenant = self.get_http_request_header("x-tenant-id")
			.unwrap_or_else(|| "anonymous".to_string());

		let key = format!("rl:{}", self.tenant);
		match self.get_shared_data(&key) {
			(Some(bytes), cas) => {
				let count = u32::from_le_bytes(bytes[..4].try_into().unwrap());
				if count >= 100 {
					self.send_http_response(429, vec![("retry-after", "60")], None);
					return Action::Pause;
				}
				let _ = self.set_shared_data(&key, Some(&(count + 1).to_le_bytes()), cas);
			}
			(None, _) => {
				let _ = self.set_shared_data(&key, Some(&1u32.to_le_bytes()), None);
			}
		}
		Action::Continue
	}
}
```

`get_shared_data` / `set_shared_data` 는 워커 스레드 간 공유 KV 로, CAS 토큰으로 낙관적 동시성을 처리한다. 필터 인스턴스는 요청마다 만들어지고 선형 메모리는 VM 단위로 공유되므로, 상태를 인스턴스 필드에 두면 요청 격리가 되고 공유 데이터에 두면 워커 전역이 된다. 이 구분을 잘못하면 테넌트 간 데이터가 새어 나간다.

성능 관점에서 Wasm 필터는 네이티브 C++ 필터 대비 요청당 수 마이크로초의 오버헤드가 붙는다(VM 컨텍스트 전환 + 헤더 복사). Envoy 는 헤더를 게스트 메모리로 복사해야 하므로 헤더가 많으면 비용이 든다. 핫 패스에 넣을 때는 필요한 헤더만 읽고, 가능하면 `on_http_request_headers` 에서 조기 결정을 내려 바디 콜백을 피한다.

## 8. 서버사이드 도입 판단

| 비교축 | 컨테이너 | Wasm(WASI) | 언어 내장 플러그인 |
|---|---|---|---|
| 콜드 스타트 | 수백 ms~수 s | 수십 µs~수 ms | 즉시 |
| 이미지/모듈 크기 | 수십~수백 MB | 수백 KB~수 MB | — |
| 격리 강도 | 네임스페이스+cgroup(커널 공유) | 주소 공간 부재(런타임 신뢰) | 없음 |
| 시스템 접근 | 전체 syscall(seccomp 로 축소) | capability 로 명시 주입 | 전체 |
| 언어 선택 | 자유 | LLVM 계열 위주(+ JS/Python 인터프리터 내장) | 호스트 언어 |
| 멀티스레드 | 자유 | threads 제안 필요, 성숙도 낮음 | 자유 |
| GC 언어 지원 | 자유 | WasmGC 필요, 도구 성숙 중 | 자유 |

Wasm 이 명확히 유리한 곳은 **신뢰할 수 없는 코드를 낮은 지연으로 자주 실행** 해야 하는 경우다. CDN 엣지 함수, API 게이트웨이 필터, 데이터베이스 UDF, SaaS 의 고객 작성 확장 로직이 그렇다. 콜드 스타트가 마이크로초 단위라 요청당 인스턴스를 새로 만들어도 되고, 그러면 요청 간 상태 오염이 원천적으로 없다.

반대로 일반 마이크로서비스를 Wasm 으로 옮기는 것은 대체로 손해다. JVM 이나 Go 런타임이 주는 스레드, GC, 성숙한 라이브러리 생태계를 포기하는 대가가 크고, 격리 이득은 이미 컨테이너로 충분하다. 특히 Java 워크로드는 TeaVM 이나 GraalVM Wasm 백엔드가 있지만 프로덕션 성숙도가 JVM 에 한참 못 미친다.

현실적인 도입 지점은 "기존 서비스 안의 확장 포인트를 Wasm 으로 여는 것" 이다. 고객이 작성한 변환 로직, 정책 규칙, 커스텀 밸리데이터를 Wasm 모듈로 받아 연료 제한과 capability 제한 아래 실행하면, 그 코드가 무한 루프를 돌거나 파일을 뒤져도 호스트는 영향받지 않는다. 이것이 컨테이너로는 지연·비용 면에서 감당하기 어려운 영역이다.

## 참고

- WebAssembly Core Specification 2.0 (https://webassembly.github.io/spec/core/)
- WebAssembly Component Model design documents (https://github.com/WebAssembly/component-model)
- WASI Preview 2 — wasi:http, wasi:io interface definitions (https://github.com/WebAssembly/WASI)
- Bytecode Alliance — Wasmtime security and sandboxing documentation
- Proxy-Wasm ABI Specification (https://github.com/proxy-wasm/spec)
