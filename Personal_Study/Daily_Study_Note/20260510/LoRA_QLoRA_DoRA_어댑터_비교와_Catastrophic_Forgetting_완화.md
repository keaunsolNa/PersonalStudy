Notion 원본: https://www.notion.so/35c5a06fd6d3813c8177f908f58bf331

# LoRA QLoRA DoRA 어댑터 비교와 Catastrophic Forgetting 완화

> 2026-05-10 신규 주제 · 확장 대상: AI

## 학습 목표

- LoRA 가 풀어주는 문제(전체 모델 fine-tune 의 메모리, 저장, 다중 task 부담)를 정확히 이해하고 rank decomposition 의 수학적 형태를 본다
- QLoRA 의 4-bit NF4 양자화 + double quantization + paged optimizer 가 메모리 footprint 를 어떻게 1/4 로 줄이는지 단계별로 따라간다
- DoRA 의 magnitude-direction 분해가 LoRA 의 표현력 한계를 어떻게 보완하는지, 그 이득과 비용을 비교한다
- catastrophic forgetting 을 완화하는 EWC, LoRA hub, replay 기법의 작동 원리와 어댑터 fine-tune 시 적용 패턴을 정리한다

## 1. 전체 fine-tune 의 비용

7B parameter 모델을 BF16 precision 으로 full fine-tune 하면:

- 가중치 14GB
- 가중치 gradient 14GB
- AdamW optimizer state 28GB (gradient mean + variance 두 묶음)
- activation memory (sequence length 4k, batch 1 기준) 약 4-8GB

총합 60-65GB. 70B 모델이면 가중치만 140GB, optimizer 까지 350-400GB.

## 2. LoRA — Low-Rank Adaptation

핵심 아이디어 (Hu et al., 2021): pretrained weight matrix W 의 *변화량* ΔW 가 low rank 라고 가정. ΔW = B A 로 분해, A 는 (r × d_out), B 는 (d_in × r), r << min(d_in, d_out).

```
W' = W + ΔW = W + B A
```

학습 시 W 는 frozen, B 와 A 만 학습. parameter 수 비교:
- full: d_in * d_out
- LoRA: r * (d_in + d_out)

7B 모델의 attention projection (d=4096) 한 layer:
- full: 4096^2 = 16.7M params
- LoRA r=8: 8 * (4096 + 4096) = 65.5K params (255배 작음)

**rank 결정**:
- r = 8: 가장 흔한 default. instruction tuning 에 적합.
- r = 16-32: 도메인 특화 fine-tune (medical, legal).
- r = 64+: full fine-tune 에 가까운 capacity.

**alpha (scaling)**: ΔW = (alpha / r) * B A. 보통 alpha=2r 또는 alpha=16 고정.

**target modules**: attention 의 q_proj, k_proj, v_proj, o_proj 가 가장 흔한 대상. 최근에는 MLP (gate_proj, up_proj, down_proj) 까지 모두 포함하는 게 성능에 더 좋다.

## 3. LoRA 의 추론 시간 비용 — 사실은 0

학습된 LoRA 어댑터는 ΔW = B A 형태로 보관되지만, 추론 시에는 W' = W + B A 를 *미리 합쳐 W' 로 baked* 할 수 있다. vLLM/TGI 같은 inference engine 의 *multi-LoRA serving* 이 그 풀이로, base W 는 하나만 들고 batch 안에서 token 마다 다른 어댑터를 적용.

## 4. QLoRA — 4-bit base + LoRA

**NF4 (4-bit NormalFloat)**: 정규분포에서 sampling 한 weight 분포에 최적인 4-bit 양자화 grid.

**double quantization**: scale 자체를 다시 8-bit 로 양자화. 추가 30% 절약.

**paged optimizer**: optimizer state 를 GPU/CPU 사이로 페이징.

7B 모델 메모리 비교:

| 방법 | base weight | optimizer state | trainable | total |
| --- | --- | --- | --- | --- |
| full FT BF16 | 14GB | 28GB | gradient 14GB → 56GB+ | no on 24GB |
| LoRA r=8 BF16 | 14GB | ~0.1GB | ~0.05GB | yes (~16GB) |
| QLoRA r=8 NF4 | 4GB | ~0.1GB | ~0.05GB | yes (~6GB) |

QLoRA 의 의미: 7B 정도면 노트북 RTX 4090, 13B 면 single A100 40GB, 70B 면 single A100 80GB 에서 fine-tune 가능.

## 5. DoRA — magnitude-direction decomposition

Liu et al., 2024. LoRA 의 ΔW = B A 가 *방향* 과 *크기* 를 함께 학습한다는 점이 표현력 한계라고 주장.

```
W = m * V / ||V||_c
W' = m' * (V + ΔV) / ||V + ΔV||_c
ΔV = B A
```

학습 대상은 m', B, A. magnitude m' 와 direction ΔV 를 *분리* 해서 학습한다.

**이득**:
- 같은 r 에서 LoRA 보다 1-2 점 높은 정확도.
- r 을 작게 (r=4) 두어도 LoRA r=8 과 비슷한 성능.

**비용**:
- forward pass 마다 column-wise norm 계산 추가. 학습 시 5-10% 느림. 추론은 baked merge 로 0.

## 6. 어댑터 비교 매트릭스

| 측면 | LoRA | QLoRA | DoRA |
| --- | --- | --- | --- |
| trainable param | r * (d_in + d_out) | 동일 | + d_out |
| base weight precision | BF16 | NF4 4-bit | BF16 (or NF4) |
| 학습 메모리 (7B) | ~16GB | ~6GB | ~17GB |
| 학습 속도 | 1.0x baseline | 0.7x | 0.9x |
| 정확도 vs full FT | -1 to -3 pt | -1 to -3 pt | -0.5 to -1 pt |
| 추론 latency | base 와 동일 | base 와 동일 | base 와 동일 |

실용 가이드:
- single GPU (24GB 미만), 13B+ → QLoRA 무조건
- 정확도 critical, GPU 충분 → DoRA
- 표준 instruction tuning, 환경 정착됨 → LoRA r=8

## 7. catastrophic forgetting

continual learning 환경에서 task A 에 fine-tune 한 모델을 task B 에 다시 fine-tune 하면 task A 성능이 급락.

해결 패턴 다섯:

### 7.1 task 별 별도 어댑터
가장 단순. task A 어댑터, task B 어댑터를 따로 학습/저장. multi-LoRA serving 으로 한 GPU 에서 모두.

### 7.2 LoRA Hub / Composition
task A, B, C 어댑터를 *합성* 해 새 task 에 zero-shot 으로 적용. 단순 weighted sum 또는 SVD 기반 merge.

### 7.3 EWC (Elastic Weight Consolidation)
task A 에서 중요한 parameter 를 식별해 task B 학습 시 그 parameter 의 변경에 penalty 추가.

```
L_total = L_taskB + (lambda / 2) * sum_i F_i * (theta_i - theta_i_A)^2
```

### 7.4 Replay (rehearsal)
task A 의 일부 example 을 task B 학습 데이터에 섞는다. 비율 5-10%.

### 7.5 LoRA dropout / weight decay
LoRA 어댑터 자체에 dropout (0.05-0.1), weight decay 를 두면 overfit 이 줄고 forgetting 도 완화.

## 8. 실전 — QLoRA + PEFT (HuggingFace) 코드 골격

```python
from transformers import AutoModelForCausalLM, BitsAndBytesConfig
from peft import LoraConfig, get_peft_model

bnb_config = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",
    bnb_4bit_use_double_quant=True,
    bnb_4bit_compute_dtype=torch.bfloat16,
)

base = AutoModelForCausalLM.from_pretrained(
    "meta-llama/Llama-3-8B",
    quantization_config=bnb_config,
    device_map="auto",
)

lora_config = LoraConfig(
    r=8,
    lora_alpha=16,
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                    "gate_proj", "up_proj", "down_proj"],
    lora_dropout=0.05,
    bias="none",
    task_type="CAUSAL_LM",
)

model = get_peft_model(base, lora_config)
model.print_trainable_parameters()
```

DoRA 사용 시 LoraConfig 에 `use_dora=True` 한 줄 추가 (PEFT 0.10+).

## 9. 운영에서의 함정과 대처

| 함정 | 증상 | 대처 |
| --- | --- | --- |
| target_modules 누락 (MLP 빠짐) | val loss 안 줄어듦 | 모든 linear (attention + MLP) 포함 |
| r 너무 작음 (r=4) | 도메인 task 에서 underfit | r=16+ 로 시도 |
| QLoRA 에서 LR 너무 큼 | divergence (loss NaN) | 보통 1e-4 ~ 5e-5 |
| baked merge 후 양자화 | 정확도 급락 | merge 는 BF16 base 에만 |
| 어댑터 호환성 | base model 변경 시 어댑터 재학습 필요 | base 버전을 model card 에 명시 |
| context length mismatch | 학습 4k, 추론 8k | RoPE scaling 함께 학습 |

규칙: PEFT 어댑터의 가장 큰 가치는 *비용 곡선이 평평하다는 것* 이다. task 100개에 대해 어댑터 80MB 씩 → 8GB 보관, base 14GB 1개 + multi-LoRA serving 으로 한 GPU 에서 모두 서빙.

## 참고

- LoRA: Low-Rank Adaptation of Large Language Models: https://arxiv.org/abs/2106.09685
- QLoRA: Efficient Finetuning of Quantized LLMs: https://arxiv.org/abs/2305.14314
- DoRA: Weight-Decomposed Low-Rank Adaptation: https://arxiv.org/abs/2402.09353
- HuggingFace PEFT 라이브러리: https://huggingface.co/docs/peft/index
- bitsandbytes 라이브러리: https://github.com/bitsandbytes-foundation/bitsandbytes
- EWC: https://www.pnas.org/doi/10.1073/pnas.1611835114
