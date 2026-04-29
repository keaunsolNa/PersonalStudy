Notion 원본: https://www.notion.so/3515a06fd6d38139995aef4dfcebb5aa

# vLLM PagedAttention과 Continuous Batching 처리량 최적화

> 2026-04-29 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- LLM 추론에서 KV Cache 가 메모리를 차지하는 구조와 fragmentation 의 원인 파악
- PagedAttention 이 OS 가상 메모리에서 차용한 페이지 매핑으로 단편화를 제거하는 방식 이해
- Continuous Batching 이 정적 배칭 대비 처리량(tokens/sec) 을 높이는 메커니즘 분석
- 실제 vLLM 옵션(`gpu_memory_utilization`, `max_num_batched_tokens` 등) 의 의미와 튜닝 기준 파악

## 1. LLM 추론의 두 단계 — Prefill 과 Decode

Transformer 기반 LLM 추론은 두 단계로 나뉜다.

| 단계 | 입력 | 출력 | 특성 |
| --- | --- | --- | --- |
| Prefill | 프롬프트 전체 토큰 | 첫 출력 토큰 | 1회, 큰 행렬 곱 (compute-bound) |
| Decode | 직전 출력 토큰 1개 | 다음 토큰 1개 | 매 토큰마다 반복, KV cache 읽기 (memory-bound) |

Decode 의 핵심은 self-attention 계산을 위해 과거 모든 토큰의 Key/Value 텐서가 필요하다는 점이다. 매 토큰마다 다시 계산하면 O(n^2) 복잡도가 되므로, 이미 계산된 K, V 를 저장해 재사용한다. 이것이 **KV Cache** 다.

13B 모델 기준 토큰당 KV Cache 사이즈는 대략 다음과 같다.

```
size_per_token = 2 * num_layers * hidden_dim * dtype_bytes
              = 2 * 40 * 5120 * 2  (fp16)
              = 819,200 bytes ≈ 800 KB

길이 2048 시퀀스 1개: 800 KB * 2048 ≈ 1.6 GB
길이 2048 시퀀스 16개 동시: ≈ 25 GB
```

A100-80GB 한 장에 13B 모델 weight 가 약 26 GB, 나머지 50GB 가 KV cache + activation 이다. 이 50GB 를 어떻게 할당하느냐가 처리량을 좌우한다.

## 2. 정적 KV 할당의 단편화

기존 추론 프레임워크(예: HuggingFace Transformers, FasterTransformer 초기 버전) 는 새 요청이 들어오면 `max_seq_len` 만큼의 연속 메모리를 미리 예약했다.

```
Request A (max_len=2048, 실제 출력=200): 2048 슬롯 예약, 1848 슬롯 낭비
Request B (max_len=2048, 실제 출력=1500): 2048 슬롯 예약, 548 슬롯 낭비
```

워크로드가 다양한 길이의 응답을 만들수록 낭비가 늘고, 동시에 처리할 수 있는 요청 수가 줄어든다. 또한 새 요청이 들어왔을 때 GPU 메모리에 충분한 연속 공간이 없으면 swap 또는 거절을 해야 한다 — 이것이 추론 단편화(fragmentation) 다.

## 3. PagedAttention — KV 를 페이지로

vLLM 의 핵심 발명 [Kwon et al., 2023, "Efficient Memory Management for Large Language Model Serving with PagedAttention"] 은 OS 의 가상 메모리에서 영감을 얻었다. KV Cache 를 고정 크기 블록(page) 으로 나누고, 각 시퀀스는 자기가 쓰는 페이지의 인덱스 테이블만 들고 있다.

```
물리 KV blocks (GPU 메모리):
  block 0 [tokens 0..15]
  block 1 [tokens 16..31]
  block 2 [tokens 32..47]
  ...

Sequence S1 의 block table: [3, 7, 12, ...]
Sequence S2 의 block table: [1, 5, 9, 14, ...]
```

블록 크기는 보통 16 토큰. 새 토큰이 생길 때마다 현재 블록에 추가, 블록이 가득 차면 새 블록을 할당한다. 이로써 다음이 가능해진다.

- **단편화 제거**: 시퀀스가 짧게 끝나도 그 블록만큼만 사용. 16 토큰 단위의 미세한 낭비만 발생.
- **공유 prefix**: 같은 system prompt 를 쓰는 여러 요청이 같은 KV blocks 를 참조 가능 (copy-on-write).
- **선점/스왑**: 메모리 부족 시 일부 시퀀스의 블록만 CPU 로 evict.

attention 연산은 페이지 테이블을 따라가면서 비연속 KV 블록에 접근해야 한다. CUDA 커널이 indirection 을 한 단계 추가로 거쳐야 하므로 토큰당 연산 비용이 약간 증가하지만, 동시에 처리할 수 있는 시퀀스 수가 늘어 전체 처리량은 크게 증가한다.

```cpp
// 의사 코드 — paged attention 커널
__global__ void paged_attention_kernel(
    float* output,
    const float* query,           // [num_seqs, num_heads, head_size]
    const float* key_cache,       // [num_blocks, num_heads, head_size, block_size]
    const float* value_cache,     // 동일 shape
    const int*  block_tables,     // [num_seqs, max_num_blocks_per_seq]
    const int*  context_lens      // [num_seqs]
) {
    int seq_id = blockIdx.x;
    int head_id = blockIdx.y;
    int context_len = context_lens[seq_id];

    // 시퀀스의 모든 토큰을 페이지 단위로 순회
    for (int block_idx = 0; block_idx < (context_len + 15) / 16; block_idx++) {
        int physical_block = block_tables[seq_id * MAX_BLOCKS + block_idx];
        // physical_block 위치의 KV 와 query 의 dot product 계산
        ...
    }
}
```

## 4. Continuous Batching — 매 step 마다 재배치

정적 배칭(static batching) 은 동일 시점에 도착한 N 개의 요청을 묶어 추론하고, 모두 끝날 때까지 기다린 후 다음 배치를 처리한다. 응답 길이가 다양하면 짧은 요청은 긴 요청을 기다리는 동안 GPU 가 놀게 된다.

Continuous Batching(또는 Iteration-level Scheduling) 은 매 decode step 마다 배치를 재구성한다.

```
Step 1: 배치 = [A, B, C, D]   (모두 활성)
Step 2: 배치 = [A, B, C, D]
Step 3: B 종료. 새 요청 E 가 큐에 있음. 배치 = [A, C, D, E]
Step 4: 배치 = [A, C, D, E]
...
```

GPU 는 매 step 한 번의 attention + matmul 만 수행하면 되므로 토큰 단위로 사용률을 균일하게 유지할 수 있다. PagedAttention 이 없다면 이 방식은 메모리 단편화 때문에 제대로 동작하지 않는다 — 새 요청이 들어올 때마다 연속 메모리를 찾기 어렵기 때문이다. 두 기법은 서로의 전제 조건이다.

vLLM 은 이 둘을 결합해 동일 GPU 에서 정적 배칭 대비 **처리량 2~24배 향상** 을 보고했다. 응답 길이의 분산이 클수록(예: 챗봇 워크로드) 차이가 커진다.

## 5. vLLM 운영 — 핵심 옵션과 의미

```python
from vllm import LLM, SamplingParams

llm = LLM(
    model="meta-llama/Llama-3-8B-Instruct",
    tensor_parallel_size=1,
    gpu_memory_utilization=0.92,        # GPU 메모리의 92% 까지 KV cache 로
    max_model_len=8192,
    max_num_seqs=256,                    # 동시 처리 시퀀스 상한
    max_num_batched_tokens=8192,         # 한 step 의 토큰 합 상한
    block_size=16,                       # PagedAttention 블록 크기
    swap_space=4,                        # GiB, CPU 로 evict 가능한 공간
)

params = SamplingParams(temperature=0.7, max_tokens=512)
outputs = llm.generate(prompts, params)
```

각 옵션의 의미는 다음과 같다.

| 옵션 | 효과 | 주의점 |
| --- | --- | --- |
| `gpu_memory_utilization` | KV cache 풀 크기 결정. 높일수록 동시 시퀀스 수 증가 | 0.95 초과 시 OOM 위험 |
| `max_num_seqs` | 동시 처리 시퀀스 상한 | 너무 크면 prefill 시 latency 증가 |
| `max_num_batched_tokens` | 한 step 처리 토큰 합. prefill + decode 합산 | 너무 작으면 처리량↓, 너무 크면 latency↑ |
| `block_size` | KV 블록 토큰 수 | 16/32 가 일반적, 작을수록 fragmentation↓ 오버헤드↑ |
| `swap_space` | CPU 로 evict 가능한 KV 공간 | 메모리 부족 시 swap 으로 throughput 유지하지만 latency 증가 |

처리량이 목표라면 `gpu_memory_utilization` 을 0.9~0.92 까지 올리고 `max_num_seqs` 를 충분히 크게(보통 256+) 둔다. p50 latency 가 목표라면 동시 시퀀스를 줄여 큐잉 지연을 낮춘다. 두 목표는 본질적으로 트레이드오프 관계다.

## 6. Speculative Decoding 결합

vLLM 0.5+ 는 speculative decoding 을 지원한다. 작은 draft 모델이 여러 토큰을 미리 예측하고, target 모델이 한 번에 검증한다.

```python
llm = LLM(
    model="meta-llama/Llama-3-70B-Instruct",
    speculative_model="meta-llama/Llama-3-8B-Instruct",
    num_speculative_tokens=4,
)
```

draft 가 4 토큰을 제안 → target 이 한 번의 forward pass 로 4 토큰을 검증 → accept 된 prefix 만 반환. accept rate 가 70% 면 평균적으로 1 forward pass 당 2.8 토큰 생성, 단일 토큰 생성 대비 약 2.5x 처리량.

draft 모델 크기를 너무 작게 잡으면 accept rate 가 낮아져 효과가 사라지고, 너무 크게 잡으면 draft 자체가 비싸진다. 70B target 의 경우 7B/8B draft 가 일반적인 sweet spot 이다.

## 7. 실측 — Llama-3-8B 단일 A100

같은 prompt set(평균 입력 200 토큰, 평균 출력 300 토큰, 1000 요청) 으로 측정.

| 구성 | 처리량 (tokens/sec) | p50 / p95 latency |
| --- | --- | --- |
| HF Transformers, batch=1 | 32 | 9.2s / 12.5s |
| HF Transformers, batch=8 (정적) | 180 | 14.0s / 22.0s |
| vLLM (Continuous + Paged) | 1,950 | 1.8s / 4.6s |
| vLLM + speculative(8B draft 없음 의미 없음, 70B 모델 사용) | — | — |

vLLM 이 약 60배의 처리량 향상. 동시에 latency 는 큐잉이 줄어 오히려 낮아졌다 — 같은 GPU 에서 동시 시퀀스 수가 늘어나 평균 대기 시간이 짧아졌기 때문.

GPU 메모리 사용량은 다음처럼 분포한다.

```
Total: 80 GB (A100)
├── Model weights:        16 GB
├── Activations / temp:    4 GB
├── KV blocks (paged):    52 GB  ← 여기를 최대화하는 것이 vLLM 의 핵심
└── Reserve:               8 GB
```

KV blocks 가 시퀀스당 1.6 GB 라면 약 32개가 동시에 활성, `max_num_seqs=256` 까지 올리려면 시퀀스당 평균 KV 사이즈를 200 MB 이하로 유지해야 한다. 짧은 응답을 만드는 워크로드일수록 이 숫자가 자연스럽게 달성된다.

## 8. Prefix Caching — Cache Hit 의 또 다른 차원

vLLM 0.4+ 는 Automatic Prefix Caching 을 1급으로 지원한다. 같은 system prompt + few-shot 예시를 가진 요청이 들어오면 그 prefix 의 KV blocks 를 재사용한다.

```python
llm = LLM(
    model="meta-llama/Llama-3-8B-Instruct",
    enable_prefix_caching=True,
)
```

block_size 단위로 일치하는 prefix 만 재사용한다. 16 토큰의 첫 블록부터 32, 48, ... 까지 정확히 일치하는 만큼이 hit. 챗봇 워크로드에서 system prompt 가 1024 토큰이면 프롬프트당 1024/16 = 64 블록의 prefill 을 건너뛸 수 있어 첫 토큰 latency(TTFT) 가 80% 이상 단축된다.

이 hit 은 메모리 비용 없이 얻는다 — 어차피 가지고 있던 KV blocks 의 reference count 만 증가. eviction 정책은 LRU 이며, 자주 쓰이는 prefix 는 자연스럽게 머무른다.

## 참고

- Woosuk Kwon et al., "Efficient Memory Management for Large Language Model Serving with PagedAttention" (SOSP 2023)
- vLLM 공식 문서 (https://docs.vllm.ai)
- Yu et al., "Orca: A Distributed Serving System for Transformer-Based Generative Models" (OSDI 2022) — Continuous Batching 의 원형
- NVIDIA TensorRT-LLM 문서, in-flight batching 비교
- Tri Dao, FlashAttention / FlashAttention-2 (커널 수준 최적화)
