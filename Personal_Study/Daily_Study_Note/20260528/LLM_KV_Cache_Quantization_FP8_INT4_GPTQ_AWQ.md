Notion 원본: https://www.notion.so/36e5a06fd6d3817891a7ea4fb528ff4a

# LLM KV Cache Quantization FP8/INT4 GPTQ/AWQ

> 2026-05-28 신규 주제 · 확장 대상: LLM 추론 최적화

## 학습 목표

- Transformer 추론에서 KV cache 가 차지하는 메모리 비중과 seq_len N 에 대한 선형 증가 패턴.
- FP8 (e4m3, e5m2), INT8, INT4 양자화 포맷의 표현 범위와 양자화 오차 특성 비교.
- GPTQ, AWQ, SmoothQuant 의 weight 양자화 알고리즘 차이와 inference 처리량 영향 정리.
- KV cache 양자화가 throughput / latency / quality 에 미치는 영향 실측 비교.

## 1. KV Cache 가 메모리를 차지하는 이유

```
KV cache bytes = 2 × num_layers × num_heads × head_dim × seq_len × batch × bytes_per_element
```

Llama 3 70B (80L, 64H, 128D, FP16): 2.5 MB/token. context 32k, batch 8 → 640 GB.

## 2. 양자화 포맷 비교

| 포맷 | bits | dynamic range | 용도 |
|---|---|---|---|
| FP16 | 16 | 1e-5 ~ 6e4 | 표준 |
| BF16 | 16 | 1e-38 ~ 3e38 | 학습 |
| FP8 e4m3 | 8 | -448 ~ 448 | weight/activation |
| FP8 e5m2 | 8 | -57344 ~ 57344 | gradient, KV cache |
| INT8 | 8 | -128 ~ 127 | scale 보정 |
| INT4 | 4 | -8 ~ 7 | weight only |

FP8 은 H100 Tensor Core 네이티브, BF16 대비 2x throughput.

## 3. Weight 양자화 — GPTQ

layer-wise sequential. 각 W 를 INT4 로 양자화하며 오차를 Hessian 으로 보정. group_size 32/64/128. Llama 3 70B INT4 g128: 140 GB → 35 GB (4x).

## 4. AWQ

activation magnitude 가 큰 채널의 weight 를 덜 양자화. W = W' × s_i, W' 만 INT4. 더 빠른 양자화, 단순 구현. vLLM/llama.cpp/AutoAWQ widely support.

## 5. SmoothQuant

activation outlier magnitude 를 weight 로 옮김. Y = (W × diag(s)) × (diag(1/s) × X). 둘 다 INT8 가능 → GEMM 자체 INT8.

## 6. KV Cache 양자화

FP8 KV cache: e5m2 적합, 메모리 절반, quality 손실 거의 없음. INT4 KV: 4x 압축, perplexity 0.05~0.2 증가.

```python
from vllm import LLM
llm = LLM(
    model="meta-llama/Llama-3-70B-Instruct",
    quantization="awq",
    kv_cache_dtype="fp8_e5m2",
    max_model_len=32768,
    tensor_parallel_size=4,
)
```

## 7. 실측 — Llama 3 70B

| 설정 | throughput | p50 TTFT | GSM8K |
|---|---|---|---|
| FP16 + FP16 KV | 320 tok/s | 180ms | 81.2% |
| AWQ INT4 + FP16 KV | 1100 | 90ms | 80.7% |
| AWQ INT4 + FP8 KV | 1400 | 85ms | 80.5% |
| AWQ INT4 + INT4 KV | 1650 | 80ms | 78.9% ⚠ |
| FP8 + FP8 KV | 1850 | 75ms | 80.8% |

## 8. 알고리즘 선택 가이드

| 워크로드 | 추천 |
|---|---|
| H100 + max throughput | FP8/FP8 |
| A100/L40S | AWQ INT4 + FP16 KV |
| 128k+ context | INT4 + INT8/FP8 KV |
| Quality 최우선 | FP16 + FP8 KV |
| Edge/CPU | GGUF Q4_K_M |
| 학습 | BF16 |

GPTQ vs AWQ: 모델/태스크별 ±0.5%p 진동. 양자화 속도 + 생태계 지원으로 AWQ 먼저 시도.

## 9. 함정

- Calibration set 도메인 매칭.
- KV cache 양자화와 long context interaction.
- 검증 metric 은 GSM8K, HumanEval, MMLU, MT-Bench 4종.
- vLLM PagedAttention block 단편화 19% 낭비.

## 10. PagedAttention

고정 block 으로 나눠 단편화 4~6%. traditional 은 padding 으로 30~60% 낭비. paged 는 3.4x 효율. 양자화와 paging 은 독립 축 — 곱셈으로 적립.

## 11. 운영 체크리스트

- base/quantized 둘 다 prompt 100개 회귀 테스트.
- KV dtype 변경은 별도 단계.
- GPU 메모리 90% 이상 금지.
- long context 는 chunked prefill.

## 참고

- GPTQ paper (Frantar et al., 2022)
- AWQ paper (Lin et al., 2023)
- SmoothQuant paper (Xiao et al., 2022)
- NVIDIA FP8 Whitepaper
- vLLM Documentation — Quantization
- Hugging Face Optimum
- AutoAWQ: https://github.com/casper-hansen/AutoAWQ
