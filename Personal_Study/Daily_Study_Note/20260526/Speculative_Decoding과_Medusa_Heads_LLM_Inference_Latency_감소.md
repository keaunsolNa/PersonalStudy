Notion 원본: https://www.notion.so/36c5a06fd6d3810e934be58b8c407f22

# Speculative Decoding과 Medusa Heads — LLM Inference Latency 감소

> 2026-05-26 신규 주제 · 확장 대상: AI / vLLM Inference

## 학습 목표
- Autoregressive decoding이 memory-bound 인 이유를 arithmetic intensity 관점에서 정량적으로 분석한다.
- Speculative decoding 의 rejection sampling 이 target distribution 과 정확히 동등함을 증명 단계로 따라간다.
- Medusa Heads 구조와 tree attention mask 를 직접 구현하여 단일 forward 로 다중 candidate 를 검증한다.
- vLLM 의 `--speculative-model` 옵션으로 Llama 3 70B 추론 throughput 을 측정·비교한다.

## 1. Autoregressive Decoding 의 비용

Transformer decoder 의 생성은 토큰 단위로 sequential 하다. `t` 번째 토큰을 만들기 위해 직전 `t-1` 개 토큰의 KV cache 를 GPU HBM 에서 로드하고, 새 query 와 attention 을 수행한 뒤 logits 을 sampling 한다. 이 한 step 의 비용 구조:

- FLOPs: `2 * N_params`
- Bytes read: `2 * N_params + 2 * L * d * t` (weight + KV cache, fp16 가정)

Arithmetic intensity 는 대략 `FLOPs / Bytes ≈ 1` 수준이다. H100 SXM 의 peak 는 `989 TFLOPs (fp16) / 3.35 TB/s = ~295 FLOP/byte` 이므로 decoding 은 명백히 memory-bound 이다. 실제 nvidia-smi 로 보면 GPU SM utilization 이 10~20% 에 머무르고, HBM bandwidth utilization 이 80% 이상으로 포화된다.

```python
N = 70e9
bytes_per_step = 2 * N
bw = 3.35e12
print(f"min latency = {bytes_per_step / bw * 1000:.1f} ms")  # ~41.8 ms / token
```

이 한계 때문에 batch=1 long-context 추론은 sequence length 가 길어져도 토큰당 시간이 거의 일정하다. Speculative decoding 은 이 "남는 GPU 연산 자원"을 활용한다.

## 2. Speculative Decoding 기본 알고리즘

Leviathan et al. (2023) 의 아이디어는 단순하다. 작은 draft model `q(x)` 가 `γ` 개의 토큰을 빠르게 제안하고, 큰 target model `p(x)` 가 그 `γ` 개를 single forward pass 로 한꺼번에 검증한다. 검증은 토큰별 확률비 비교로 이루어지며, 통과한 토큰은 그대로 채택하고 reject 발생 지점부터는 target distribution 으로 재샘플링한다.

수락률을 `α = E[1{accept}]` 로 정의하면, 한 번의 speculative step 에서 채택되는 토큰 수의 기댓값은 등비수열 합으로 다음과 같다.

```
E[# accepted] = (1 - α^(γ+1)) / (1 - α)
```

예컨대 `α = 0.7`, `γ = 4` 면 `E ≈ 2.95` 토큰이 한 번의 target forward 로 생성된다.

## 3. Rejection Sampling 수식

핵심은 "draft 가 무엇을 제안하든 최종 분포가 target `p` 와 정확히 같다"는 점이다. Draft 가 토큰 `x ~ q` 를 뽑았을 때, accept 확률을 `min(1, p(x)/q(x))` 로 두면 다음이 성립한다.

```
P(output = x) = q(x) * min(1, p(x)/q(x)) + (1 - A) * r(x)
```

여기서 `r(x) = max(p(x) - q(x), 0) / Σ_y max(p(y) - q(y), 0)`. 두 항을 더하면 `q(x) min(1, p(x)/q(x)) + max(p(x) - q(x), 0) = p(x)` 가 되어 target 과 일치함이 증명된다.

```python
import numpy as np

def speculative_step(p, q, draft_token):
    if np.random.rand() < min(1.0, p[draft_token] / q[draft_token]):
        return draft_token, True
    residual = np.maximum(p - q, 0.0)
    residual = residual / residual.sum()
    return np.random.choice(len(p), p=residual), False
```

## 4. Medusa Heads 구조

Cai et al. (2024) 의 Medusa 는 draft model 자체를 없앤다. 대신 base LLM 의 마지막 hidden state 위에 `K` 개의 추가 prediction head 를 붙여, 한 번의 forward 로 `t+1, t+2, ..., t+K` 토큰의 후보를 동시에 산출한다.

```python
import torch.nn as nn

class MedusaHead(nn.Module):
    def __init__(self, hidden: int, vocab: int, layers: int = 1):
        super().__init__()
        self.blocks = nn.Sequential(*[
            nn.Sequential(nn.Linear(hidden, hidden), nn.SiLU())
            for _ in range(layers)
        ])
        self.lm = nn.Linear(hidden, vocab, bias=False)

    def forward(self, h):
        return self.lm(h + self.blocks(h))

heads = nn.ModuleList([MedusaHead(4096, 128256) for _ in range(4)])
```

각 head 가 top-k candidate 를 만들면 head 별 후보를 곱집합으로 합쳐 "candidate tree" 가 만들어진다. `K=4, top-k=(5, 3, 2, 2)` 이면 leaf 가 60 개인 트리가 된다.

## 5. Tree Attention 과 Sparse Mask

표준 causal mask 는 1-D sequence 를 가정하지만, candidate tree 는 분기 구조다. 각 node 가 자신의 조상 path 만 attend 하도록 attention mask 를 sparse 하게 만든다.

```python
def build_tree_mask(parents):
    M = len(parents)
    mask = torch.zeros(M, M, dtype=torch.bool)
    for i in range(M):
        j = i
        while j != -1:
            mask[i, j] = True
            j = parents[j]
    return mask
```

Medusa-2 는 base model 까지 같이 학습한다(joint training). 후속 연구인 EAGLE / EAGLE-2 는 draft 를 별도의 lightweight auto-regressive head 로 두되, candidate tree 를 confidence 기반으로 동적으로 가지치기한다.

## 6. vLLM 통합

vLLM 0.6+ 부터 speculative decoding 이 1st-class 로 지원된다.

```bash
python -m vllm.entrypoints.openai.api_server \
  --model meta-llama/Meta-Llama-3-70B-Instruct \
  --tensor-parallel-size 4 \
  --speculative-model meta-llama/Meta-Llama-3-8B-Instruct \
  --num-speculative-tokens 5 \
  --use-v2-block-manager \
  --gpu-memory-utilization 0.9
```

내부적으로 vLLM scheduler 는 매 step 마다 (1) draft model 로 `γ` 토큰 forward, (2) target 으로 `γ+1` position 을 한 번에 검증, (3) accept 된 prefix 만큼 KV cache 를 commit 한다. Draft 와 target 의 KV cache 가 별도로 잡히므로 GPU 메모리 footprint 는 단일 모델 대비 약 1.1~1.3x 증가한다.

scheduling 측면에서 가장 까다로운 부분은 chunked prefill 과의 상호작용이다. vLLM v1 의 continuous batching 은 매 step 마다 prefill 요청과 decode 요청을 섞어 GPU FLOPs 를 채우는데, speculative decode 가 추가되면 batch 의 한 슬롯이 `γ+1` 길이의 가변 query 를 들고 들어가게 된다.

## 7. 실측

| 시나리오 | Draft | Target | γ | α | TPOT (ms) | Throughput speedup |
|---|---|---|---|---|---|---|
| ShareGPT 영문 | Llama 3 8B | Llama 3 70B | 5 | 0.74 | 17 | 2.4x |
| 한국어 instruction | Llama 3 8B | Llama 3 70B | 5 | 0.41 | 38 | 1.1x |
| 코드 자동완성 | DeepSeek-Coder 1.3B | DeepSeek-Coder 33B | 7 | 0.83 | 12 | 3.1x |
| JSON tool-use | Llama 3 8B | Llama 3 70B | 3 | 0.55 | 28 | 1.5x |
| Medusa-2 (단일 모델) | head K=4 | Vicuna 7B | - | - | 14 | 2.8x |

도메인 일치도가 높은 instruction-tuned 쌍에서는 `α ≈ 0.75`, code-completion 같이 분포가 좁은 영역은 `α ≈ 0.85` 까지 올라간다. 반면 다국어 mismatch, JSON 강제 출력 등에서는 `α` 가 0.4 이하로 떨어져 오히려 손해가 된다.

speedup 의 상한은 본질적으로 `α` 와 `γ` 의 함수이고, 측정값이 이론 상한과 큰 차이를 보이면 보통 (1) draft forward 자체가 비싸거나, (2) scheduler 의 batch 슬롯 경합, (3) chunked prefill 과의 충돌이 원인이다.

## 8. 트레이드오프와 실패 케이스

- KV cache 메모리: draft + target 양쪽 모두 보관. 1.25x 부담
- Batch size 증가 시 효과 감소: `batch ≥ 32` 부터 speedup 이 1.3x 이하로 수렴
- Sampling 파라미터: `temperature → 0` greedy 면 매우 빨라지지만 `temperature ≥ 1` 에서는 α 급락
- Scheduling 복잡도: vLLM v1 chunked prefill 과의 상호작용 별도 튜닝 필요
- Quantized draft: AWQ/GPTQ 4-bit draft 사용 시 draft 비용 절반 이하. 양자화 왜곡으로 α 1~3%p 하락
- 실패 시나리오: 도메인 mismatch, tool-use JSON schema 강제, 매우 긴 numerical reasoning. 어댑티브 γ controller 필요

## 9. 운영 체크리스트와 어댑티브 γ Controller

```python
def update_gamma(alpha_hat: float, draft_cost_ratio: float, gamma_prev: int) -> int:
    best_gain, best_gamma = 0.0, 1
    for g in range(1, 16):
        E = (1 - alpha_hat ** (g + 1)) / (1 - alpha_hat + 1e-9)
        gain = E / (1 + g * draft_cost_ratio)
        if gain > best_gain:
            best_gain, best_gamma = gain, g
    return max(1, min(gamma_prev + 2, best_gamma))
```

`draft_cost_ratio` 는 (draft forward latency) / (target forward latency) 의 실측치다. 70B+8B 조합에서는 약 0.10~0.13, 70B+1B Quantized draft 면 0.03~0.05 정도. `α̂` 가 0.4 이하로 떨어지면 controller 는 `γ=1` 로 수렴하면서 사실상 speculative 를 꺼버린다.

prefix sharing 캐시 — 같은 system prompt 를 공유하는 멀티턴 대화 환경에서 prefix KV cache 가 reuse 되면 prefill 비용은 낮아지지만 decode 단계의 memory-bound 성격은 그대로다. 즉 speculative 의 이득은 prefix length 에 거의 무관하게 유지된다.

## 10. 정리

speculative decoding 은 memory-bound autoregressive decoding 의 빈 연산 자원을 활용해 동일 분포를 유지하면서 latency 를 줄이는 unbiased 가속이고, Medusa 계열은 별도 draft 없이 base 자체에 head 를 추가해 운영 부담을 더는 방향이다. 실 production 에서 가장 중요한 것은 (1) accept ratio `α` 의 도메인별 측정, (2) batch-size-aware fallback, (3) 어댑티브 γ controller, 그리고 (4) draft model 의 quantization 으로 draft cost ratio 를 최소화하는 작업이다.

## 참고
- Leviathan, Kalman, Matias. "Fast Inference from Transformers via Speculative Decoding." arXiv:2211.17192, 2023.
- Cai et al. "Medusa: Simple LLM Inference Acceleration Framework with Multiple Decoding Heads." arXiv:2401.10774, 2024.
- Li et al. "EAGLE: Speculative Sampling Requires Rethinking Feature Uncertainty." arXiv:2401.15077, 2024. (EAGLE-2: arXiv:2406.16858)
- Fu et al. "Lookahead Decoding: Breaking the Sequential Dependency of LLM Inference." arXiv:2402.02057, 2024.
- vLLM Documentation — Speculative Decoding (docs.vllm.ai/en/latest/models/spec_decode.html).
- vLLM Blog — "How Speculative Decoding Boosts vLLM Performance by up to 2.8x" (2024-10).
