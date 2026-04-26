Notion 원본: https://www.notion.so/34e5a06fd6d3817b93d6d9b8a48f1b39

# Transformer FlashAttention과 KV Cache 최적화

> 2026-04-26 신규 주제 · 확장 대상: AI Multi-Agent (LLM 학습됨), 자료구조&알고리즘 (시간/공간 복잡도 학습됨)

## 학습 목표

- Self-Attention의 시간/공간 복잡도와 GPU memory hierarchy를 결합한 병목을 정량화한다
- FlashAttention의 IO-aware tiling 전략을 forward / backward pass 별로 추적한다
- KV Cache 메모리 산정식과 PagedAttention(vLLM)의 fragmentation 해소를 구현 관점에서 본다
- GQA / MQA로 KV Cache를 줄였을 때 throughput / 품질 trade-off를 평가한다

---

## 1. Self-Attention의 비용 구조

Transformer의 Self-Attention은 sequence length `N`, hidden dimension `d`인 입력에 대해 다음 연산을 수행한다.

```
Q = X · W_q     (N × d)
K = X · W_k     (N × d)
V = X · W_v     (N × d)
S = Q · K^T     (N × N)   <- 메모리 N²
P = softmax(S)            (N × N)
O = P · V       (N × d)
```

연산 복잡도는 `O(N² × d)`다. d를 상수로 두면 N에 대해 quadratic이다. 더 큰 문제는 메모리. `S`와 `P`가 `N × N` 행렬이라 N=8K에서 64M floats = 256MB(fp32) 혹은 128MB(fp16)가 필요하다. N=32K에서는 4GB가 된다. 이 메모리는 GPU의 HBM(High Bandwidth Memory)에 올라간다.

GPU memory는 두 단계 hierarchy다.

| 종류 | 용량 | 대역폭 |
|---|---|---|
| HBM (off-chip) | 80GB (A100) | 1.5 TB/s |
| SRAM (on-chip, per SM) | 192KB shared | 19 TB/s |

대부분의 deep learning 연산은 HBM 대역폭에 bound 된다. 19 TB/s SRAM을 1.5 TB/s HBM이 못 따라가서, 연산 강도(compute / memory-traffic ratio)가 낮은 연산은 GPU compute unit이 idle 상태로 HBM을 기다린다.

Standard attention은 N² 행렬 `S`를 HBM에 한 번 쓰고 다시 읽어 softmax를 적용하고, 또 한 번 쓰고 다시 읽어 V와 곱한다. 이 HBM round-trip이 attention의 진짜 병목이다. compute는 충분한데 memory traffic이 throughput을 제한한다.

## 2. FlashAttention의 IO-aware tiling

Tri Dao의 FlashAttention(2022)이 던진 통찰은 단순하다. **N² 행렬을 HBM에 절대 만들지 않는다.** Q, K, V를 작은 block으로 쪼개 SRAM에 올린 뒤, online softmax 알고리즘으로 attention output을 한 번에 누적 계산한다.

```
for each block of Q (size B_q):
    initialize O_i = 0, l_i = 0, m_i = -inf  (in SRAM)
    for each block of K, V (size B_k):
        load Q_i, K_j, V_j into SRAM
        S_ij = Q_i · K_j^T                      (B_q × B_k, in SRAM)
        m_new = max(m_i, rowmax(S_ij))
        l_new = exp(m_i - m_new) · l_i + rowsum(exp(S_ij - m_new))
        O_i = (l_i / l_new) · exp(m_i - m_new) · O_i + (1 / l_new) · exp(S_ij - m_new) · V_j
        m_i = m_new
        l_i = l_new
    write O_i to HBM
```

핵심은 두 가지. 첫째, S_ij는 SRAM 안에서만 살고 HBM에 쓰이지 않는다. N² 메모리가 사라진다. 둘째, online softmax는 streaming 방식으로 max와 sum을 누적한다. softmax의 정의 `softmax(x_i) = exp(x_i - max) / sum(exp(x_j - max))`를 분리해 max와 sum을 incremental update 한다.

수치 안정성을 위해 max를 빼는 트릭은 standard softmax와 같지만, FlashAttention은 그 max가 streaming 도중 갱신된다는 점이 다르다. 새 max가 발견되면 이전 누적값을 `exp(m_old - m_new)` 비율로 rescale 한다.

block 크기 `B_q × B_k`는 SRAM 용량에 맞춰 결정된다. A100의 SM당 SRAM 192KB에서 fp16 기준 `B_q = B_k = 128`이 일반적이다. 이 정도면 Q, K, V, O가 모두 SRAM에 한 번에 올라간다.

결과: HBM read는 `O(N · d)` 만큼만 필요하다. 메모리 사용량도 `O(N · d)`로 줄어든다. backward pass에서도 같은 tiling으로 N × N matrix를 만들지 않는다. recompute 비용이 약간 추가되지만 HBM 절약 이익이 압도적이다.

성능 개선은 N이 클수록 크다.

| sequence length | Standard attention | FlashAttention | 가속비 |
|---|---|---|---|
| 1K | 1.8ms | 0.9ms | 2× |
| 8K | 28ms | 6ms | 4.7× |
| 32K | OOM | 30ms | N/A |

FlashAttention-2(2023)는 forward pass의 work 분배를 GPU warp 단위로 재정렬해 추가 1.5~2× 향상을 만들었다. FlashAttention-3(2024)은 H100의 TMA(Tensor Memory Accelerator)와 fp8을 활용해 또 2× 향상.

## 3. KV Cache의 정의와 크기

LLM 추론에서 generation은 token을 한 개씩 만든다(autoregressive). 매 step마다 모든 이전 token에 대한 K와 V를 다시 계산하는 건 낭비라, 첫 step에 계산한 K, V를 캐시해 매 step에 새 token의 Q만 곱한다. 이게 KV Cache다.

캐시 크기 산정:

```
KV_size = 2 (K와 V) × N (sequence length) × L (layer 수)
        × H (head 수) × d_head (head 차원) × bytes_per_element
```

Llama 3 70B 기준: L=80, H=64, d_head=128, fp16 (2 bytes).

```
KV_per_token = 2 × 80 × 64 × 128 × 2 = 2,621,440 bytes ≈ 2.5 MB / token
```

context length 8K = 20 GB. 이게 한 sequence에 필요한 KV 메모리다. A100 80GB로 동시 처리 가능한 sequence는 모델 가중치 140GB(fp16)을 빼고 남은 용량으로 결정된다. 작은 batch size로도 메모리가 빠르게 고갈된다.

이 메모리 압박이 inference cost를 결정한다. 큰 모델의 inference가 비싼 진짜 이유는 가중치가 아니라 KV Cache다.

## 4. PagedAttention과 vLLM

기존 inference 엔진은 KV Cache를 contiguous tensor로 할당했다. sequence 길이가 가변적이라 메모리 fragmentation이 심각하다. 어떤 sequence는 100 token으로 끝나고 어떤 건 4000 token까지 가는데, contiguous 할당은 max length 기준이라 평균적으로 60~80% 메모리가 낭비된다.

PagedAttention(Kwon et al., 2023, vLLM의 핵심 기법)은 OS의 paging 개념을 그대로 가져온다. KV Cache를 fixed-size block(예: 16 token)으로 쪼개 GPU 메모리에 임의 위치로 할당하고, sequence별로 block table(논리 주소 → 물리 주소 매핑)을 유지한다.

```
Sequence A (token 0~31) → blocks [B7, B12]
Sequence B (token 0~63) → blocks [B3, B5, B9, B11]
Sequence C (token 0~15) → blocks [B1]

GPU memory:
  B0 B1 B3 B5 B7 B9 B11 B12 ...
```

attention kernel은 block table을 따라 indirect 접근한다. 수정된 FlashAttention 변종이 이를 처리한다. fragmentation은 block 단위로만 발생하고, 평균 utilization이 95%+로 올라간다.

추가로 prefix sharing이 가능해진다. 같은 system prompt를 가진 여러 sequence는 prefix block을 공유한다. system prompt가 1000 token이고 batch에 32개 sequence가 있으면 32배 메모리를 절약한다.

벤치마크에서 vLLM은 HuggingFace TGI 대비 throughput 2~4배를 보인다. 같은 GPU로 더 많은 동시 사용자를 처리할 수 있다는 의미다.

```python
from vllm import LLM, SamplingParams

llm = LLM(model="meta-llama/Llama-3-70B-Instruct",
          tensor_parallel_size=4,
          max_model_len=8192,
          block_size=16)

sampling = SamplingParams(temperature=0.7, max_tokens=512)
outputs = llm.generate(["질문 1", "질문 2"], sampling)
```

`block_size=16`이 기본값이다. 작게 잡으면 fragmentation이 줄지만 indirect 접근 overhead가 커지고, 크게 잡으면 반대다. 16~32가 표준.

## 5. GQA / MQA: KV head 줄이기

Multi-Head Attention(MHA)에서 H개 head 각각이 독립 K, V를 가진다. KV Cache 크기는 head 수에 비례한다. 추론 시 KV 메모리가 가장 큰 비용이라면, head를 줄이면 직접적인 절약이 된다.

| 변형 | Q heads | KV heads | KV Cache 비율 |
|---|---|---|---|
| MHA | H | H | 1× (baseline) |
| GQA (Grouped Query Attention) | H | H/g (예: 8) | 1/g |
| MQA (Multi-Query Attention) | H | 1 | 1/H |

GQA는 Q를 g개 group으로 묶고, 같은 group의 Q들이 K, V를 공유한다. Llama 2 70B와 Llama 3 70B는 GQA-8(H=64, KV heads=8)을 쓴다. KV Cache가 1/8로 줄어들어 같은 메모리에 8배 sequence를 담을 수 있다.

MQA는 극단적인 형태로, K, V head가 1개다. PaLM, Falcon이 MQA를 채택했다. KV cache가 H분의 1로 줄어 inference에 매우 유리하지만, 학습 시 quality 저하가 보고되어 GQA가 더 자주 쓰인다.

품질 측면에서 GQA-8은 MHA 대비 perplexity 차이가 0.1 미만으로 사실상 동일한 반면, MQA는 0.5~1.0 차이를 보이는 경우가 있다. 학습 데이터 / 모델 크기에 따라 결과가 달라지지만 GQA가 안전한 선택이다.

기존 MHA 모델을 GQA로 변환하는 uptraining 기법(Ainslie et al., 2023)이 있다. K, V를 head group의 평균으로 초기화하고 5~10% 추가 학습으로 재교정한다. 이미 학습된 모델을 GQA로 옮겨도 메모리 이득을 얻을 수 있다는 의미다.

## 6. Quantization과의 결합

KV Cache fp16 → int8로 quantize 하면 메모리가 절반이 된다. accuracy 손실은 일반적으로 1% 미만이다.

```python
# vLLM에서 KV cache fp8 quantization
llm = LLM(model="meta-llama/Llama-3-70B-Instruct",
          kv_cache_dtype="fp8",
          quantization_param_path="kv_cache_scales.json")
```

fp8은 H100 이상에서 native 지원이고, 기존 GPU에서는 int8로 emulate 한다. fp8/int8 quantization은 model weight quantization과 별개로 적용 가능해 결합 시 메모리 사용이 크게 줄어든다.

극단적으로 4-bit quantize도 연구되고 있다. KIVI(Liu et al., 2024)는 K는 channel-wise, V는 token-wise로 다르게 quantize 해 4-bit에서 fp16 수준의 품질을 달성한다. 운영 적용은 아직 라이브러리 지원이 제한적이다.

## 7. 운영 시 throughput 측정

inference latency는 두 단계로 나뉜다. **prefill**(prompt를 한 번에 처리해 KV Cache 채움)과 **decode**(한 token씩 생성). prefill은 N개 token에 대해 attention을 한 번 계산하므로 compute-bound, decode는 1개 token에 대해서만 계산하므로 memory-bound다.

```
prefill latency = O(N²) compute / GPU TFLOPS
decode latency  = O(N · KV size) memory / HBM bandwidth
```

같은 GPU에서 prefill은 빠르고 decode는 sequence length에 비례해 느려진다. KV Cache 최적화는 decode latency에 직접적으로 영향을 준다.

batch에 다양한 sequence 길이가 섞이면 decode가 가장 긴 sequence의 token 1개를 만드는 데 batch 전체가 기다린다. continuous batching(vLLM의 또 다른 기법)은 sequence가 끝나는 즉시 새 sequence를 같은 batch에 끼워 넣어 GPU utilization을 유지한다.

| 최적화 단계 | throughput (token/s, Llama 3 70B) |
|---|---|
| naive PyTorch | 50 |
| + FlashAttention-2 | 250 |
| + KV Cache | 1500 |
| + PagedAttention + continuous batching (vLLM) | 6000+ |

각 단계가 곱셈적으로 효과를 더한다. FlashAttention 없이 32K context는 OOM, KV Cache 없이 LLM inference는 비현실적, PagedAttention 없이 multi-tenant 서빙은 GPU utilization이 낮다.

## 8. Trade-off 요약

FlashAttention은 정확도 손실 없는 순수 IO 최적화라 모든 워크로드에 도입할 가치가 있다. 라이브러리 수준에서 default로 켜져 있다.

PagedAttention은 multi-tenant 환경에서 가치가 크다. 단일 모델 / 단일 사용자 환경에서는 fragmentation이 심각하지 않아 이득이 작다. vLLM이나 TensorRT-LLM 같은 inference 엔진을 쓸 때 자연스럽게 따라온다.

GQA는 새 모델 학습 시 default 선택이 되어 가고 있다. quality 손실이 거의 없으면서 inference cost는 크게 줄어들기 때문이다. 기존 MHA 모델을 운영 중이라면 uptraining을 검토할 가치가 있다.

KV Cache quantization은 메모리 vs 정확도 trade-off다. 운영 모델로 평가 데이터셋에서 quality 회귀를 측정한 뒤 도입한다.

## 참고

- Tri Dao et al., "FlashAttention: Fast and Memory-Efficient Exact Attention with IO-Awareness" (NeurIPS 2022)
- Tri Dao, "FlashAttention-2: Faster Attention with Better Parallelism and Work Partitioning" (2023)
- Woosuk Kwon et al., "Efficient Memory Management for Large Language Model Serving with PagedAttention" (SOSP 2023)
- Joshua Ainslie et al., "GQA: Training Generalized Multi-Query Transformer Models from Multi-Head Checkpoints" (EMNLP 2023)
- vLLM Documentation - https://docs.vllm.ai
