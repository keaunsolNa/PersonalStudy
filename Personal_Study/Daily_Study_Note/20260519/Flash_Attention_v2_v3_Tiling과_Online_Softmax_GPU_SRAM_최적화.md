Notion 원본: https://www.notion.so/3655a06fd6d38151ad20f6c95bee283e

# Flash Attention v2/v3 Tiling과 Online Softmax — GPU SRAM 메모리 최적화

> 2026-05-19 신규 주제 · 확장 대상: AI

## 학습 목표

- 표준 attention 의 `O(N²)` 메모리 병목과 *HBM ↔ SRAM* 대역폭 비대칭에서 출발한 Flash Attention 의 설계 동기를 정리한다
- Online softmax 가 어떻게 분할 처리에서도 수치적으로 정확한 softmax 결과를 누적하는지 식별한다
- Flash Attention v1·v2·v3 의 변화(워크파티션·warp-specialization·FP8)를 GPU 아키텍처와 매핑한다
- 실제 학습/추론에서 어떤 hyperparameter·메모리 layout 이 throughput 을 좌우하는지 트레이드오프를 정리한다

## 1. 표준 attention 의 메모리 병목

scaled dot-product attention 의 정의는 `S=QK^T/√d`, `P=softmax(S)`, `O=PV`. 세 행렬곱이지만 실제 GPU 구현의 병목은 *연산량* 이 아니라 *메모리 전송* 이다. `S` 와 `P` 는 `N×N` 크기의 *중간 텐서* 이며, N=8192 같은 긴 시퀀스에서는 fp16 만으로도 128MB 가 넘는다. 이 두 텐서를 HBM(global memory) 에 *반드시 한 번씩* 쓰고 읽는다.

H100 의 HBM3 대역폭은 약 3TB/s, SRAM(shared memory + register) 는 *수십 TB/s* 이상이고 latency 도 10배 이상 빠르다. 표준 attention 은 SRAM 안에서 완결되지 않고 HBM 왕복을 두 번 더 해야 하므로 *memory-bound* 가 된다. 즉 GPU 의 연산기는 idle 상태로 데이터 도착을 기다린다.

Flash Attention 의 통찰은 한 줄로 요약된다 — *S 와 P 를 절대 HBM 에 쓰지 않는다*. 출력 `O` 만 한 번 HBM 에 쓴다. 이를 위해 attention 을 *블록 단위로 분할* 하고, 각 블록의 softmax 를 *online algorithm* 으로 누적한다.

## 2. Online softmax 의 수치적 정의

softmax 는 분모에 모든 항의 exp 합이 들어가므로, 데이터를 분할 처리하면 *전체 합* 을 모르기 때문에 단순 부분합으로는 답이 안 나온다. online softmax 는 *지금까지 본 입력의 max* 와 *exp 합* 을 같이 누적해서 답을 정확히 계산한다.

input 이 `x_1, ..., x_n` 으로 들어올 때, 각 단계에서 다음을 유지한다. `m_i = max(m_{i-1}, x_i)` (running max), `ℓ_i = exp(m_{i-1}-m_i) * ℓ_{i-1} + exp(x_i-m_i)` (running denominator). `m_i` 가 바뀌면 이전 `ℓ` 를 *재스케일* 한다. 핵심은 모든 exp 가 *현재 max 기준 상대값* 이라 overflow 가 없고, 누적이 commutative 한 형태로 정의된다는 점이다.

attention 에서는 출력 누적이 한 단계 더 필요하다. 부분 출력 `O_i` 가 이전까지의 부분 softmax 와 V 의 곱이므로, max 가 바뀌 때 `O` 도 같이 재스케일한다. `O_i = exp(m_{i-1}-m_i) * O_{i-1} + p_i V_block_i`. 여기서 `p_i` 는 현재 block 의 *unnormalized* softmax 값. 최종적으로 `O / ℓ_N` 으로 한 번에 정규화한다. 이 동등성 덕에 attention 전체를 N×N 텐서 없이 *블록 단위로* 처리할 수 있다.

## 3. Flash Attention v1 의 워크플로

v1(2022, Tri Dao) 은 다음 두 중첩 루프를 GPU 커널에 구현했다. 바깥 루프는 Q rows 블록, 안쪽은 K/V cols 블록. 블록 크기는 SRAM 용량(A100 = 192KB shared, H100 = 228KB)에 맞춰 결정된다. fp16 일 때 `Br = Bc = 64`, head dim 64, fp16 으로 한 블록당 약 16KB 가 들어가서 한 SM 에 동시에 두 개의 Q 블록을 띄울 수 있다.

I/O 복잡도를 계산하면 HBM read/write 가 `O(N · d)` 로 떨어진다. 표준 attention 의 `O(N²)` 대비 N=8192, d=64 기준 약 100배 감소. 실측 wall time 은 attention 단계가 2~4배 빠르고, transformer 전체로는 1.5~2배 throughput 증가가 보고된다. backward 도 동일한 분할 구조를 따르며 *재계산* 으로 메모리를 또 한 번 줄인다.

## 4. Flash Attention v2 의 변경 — 작업 분할의 재배치

v2(2023) 는 v1 의 *내·외 루프 순서를 바꾸고* 작업을 *thread block 간에 더 균등하게* 분배한다. v1 은 *outer loop = Q rows*, *inner loop = KV cols*. 즉 한 thread block 이 한 Q row 블록을 끝까지 처리한다. 문제는 *causal mask* 같은 경우 outer 가 진행될수록 inner loop 길이가 늘어 *부하 불균형* 이 발생한다. v2 는 *outer loop = KV cols*, *inner loop = Q rows* 로 뒤집는다. 누적 변수 `m`, `ℓ`, `O` 를 register 에 두고 *exchange instruction(`__shfl_xor_sync`)* 으로 warp 간에 합친다. non-causal 모드에서 `2x` ~ `2.3x` throughput 증가.

## 5. Flash Attention v3 — Hopper SM90·warp-specialization·FP8

v3(2024) 는 H100/H200 의 *워프 스페셜화(warp specialization)* 와 *TMA(Tensor Memory Accelerator)* 를 적극 활용한다. 워프 스페셜화— 한 thread block 안의 워프들을 *프로듀서* 와 *컨슈머* 로 나눈다. 프로듀서 워프는 *TMA* 로 HBM → SRAM 비동기 전송만 담당하고, 컨슈머 워프는 *Tensor Core* 로 GEMM 만 담당한다. 두 그룹이 비동기 큐(`mbarrier`) 로 동기화되어 *데이터 이동과 연산이 완전히 오버랩* 된다. FP8 (E4M3, E5M2) 지원으로 attention 의 *Q·K* 를 FP8 로 양자화 가능. softmax 단계는 정확도 유지를 위해 BF16/FP32 로 환원한다. throughput 이 추가로 1.5~1.8배 늘고 메모리 사용량이 절반 가깝이 줄어든다. v3 의 measured throughput 은 H100 SXM 기준 FP16 에서 약 *720 TFLOPS*, FP8 에서 *1.2 PFLOPS*.

## 6. 변종 — Block-sparse·Sliding Window·Paged·MQA/GQA

Flash Attention 의 분할 구조 위에 다양한 attention 변종이 자연스레 얹힌다. *Block-sparse attention* — KV 블록 중 일부만 처리. Longformer·BigBird 의 sparse pattern 을 mask 가 아닌 *블록 인덱스 리스트* 로 표현해 cold block 을 *아예 로드하지 않는다*. *Sliding Window Attention* — Mistral·Mixtral 이 사용. 각 토큰이 *최근 W 토큰* 만 본다. *PagedAttention* — vLLM 의 핵심. KV cache 를 *논리 페이지* 단위로 관리해 시퀀스 별 메모리 fragmentation 을 없애다. *MQA(Multi-Query)·GQA(Grouped-Query)* — 헤드별로 K/V 를 따로 만들지 않고 head group 마다 공유. Llama 2 70B 부터 도입.

## 7. PyTorch 통합과 호출 경로

PyTorch 2.0+ 는 `torch.nn.functional.scaled_dot_product_attention` 의 backend 를 자동 선택한다.

```python
import torch
q = torch.randn(2, 16, 8192, 64, device='cuda', dtype=torch.bfloat16)
k = torch.randn_like(q); v = torch.randn_like(q)
with torch.backends.cuda.sdp_kernel(
    enable_flash=True, enable_mem_efficient=False, enable_math=False):
    out = torch.nn.functional.scaled_dot_product_attention(q, k, v, is_causal=True)
```

`enable_flash=True` 로 강제하면 Flash Attention 커널이 호출된다. 호환 조건이 있다 — dtype 이 fp16/bf16(또는 H100 에서 fp8), head dim ≤ 256, query/key seq len 이 동일 또는 padding mask 가 적절히 제공되어야 한다. 이 조건을 깨면 `RuntimeError: No available kernel` 또는 *silent fallback* 으로 mem-efficient backend 가 호출될 수 있으므로 운영에서는 *허용 backend 리스트* 를 명시 설정하는 패턴이 안전하다.

## 8. 학습/추론 측정 — 실측 trade-off

| Model | Seq Len | Std attention | Flash v2 | Flash v3 |
|---|---|---|---|---|
| Llama 7B (BF16, A100) | 2048 | 18 step/s | 32 step/s | — |
| Llama 7B (BF16, A100) | 8192 | OOM | 9 step/s | — |
| Llama 70B (BF16, H100) | 8192 | 1.1 step/s | 2.1 step/s | 3.0 step/s |
| Llama 70B (FP8, H100) | 8192 | — | — | 4.4 step/s |

추론에서는 *prefill* 단계(긴 prompt 한 번에 처리)가 큰 이득을 본다. *decode* 단계는 q 길이 1, kv 길이 점진 증가의 비대칭이라 PagedAttention + Flash v2 조합이 가장 좋다. 메모리 측면— Flash v2 는 attention 활성화 메모리가 `O(N)` 으로 떨어져 동일 GPU 에서 batch size 또는 seq len 을 2~4배 늘릴 수 있다.

## 9. 미래 방향 — Ring·Tree·Triton 구현체

Flash Attention 은 *single-GPU* 최적화의 끝에 가깝다. 더 긴 시퀀스(>1M)를 위한 방향이 세 가지로 분기한다. *Ring Attention* — 시퀀스를 여러 GPU 에 분할하고 KV 를 ring 으로 전달. Flash 의 online softmax 를 *GPU 간 누적* 으로 확장. 32 GPU 에서 시퀀스 1M+ 학습이 보고된다. *Tree Attention* — 추론에서 prefix tree 형태로 KV 를 공유. *Triton 구현체* — Triton DSL 로 작성된 Flash 변종이 *연구자가 직접 수정*하는 베이스라인이 된다. 운영 입장에서는 PyTorch 2.0+ 와 H100 이면 v3 가 자동 작동하므로 *backend 선택 로깅* 으로 fallback 을 잡고, 긴 시퀀스 모델에서는 *PagedAttention + Flash v2/v3* 조합을 vLLM 으로 운용한다.

## 참고

- Tri Dao et al., "FlashAttention: Fast and Memory-Efficient Exact Attention with IO-Awareness" (NeurIPS 2022)
- Tri Dao, "FlashAttention-2" (2023)
- Shah et al., "FlashAttention-3" (2024)
- Milakov & Gimelshein, "Online normalizer calculation for softmax" (2018)
- NVIDIA Hopper Whitepaper — TMA, warp specialization, FP8 Tensor Core
