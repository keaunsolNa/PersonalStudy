Notion 원본: https://app.notion.com/p/3525a06fd6d3812ca28effe92131100f

# LoRA·QLoRA Fine-tuning과 PEFT 라이브러리 실전

> 2026-04-30 신규 주제 · 확장 대상: LLM · Transformer

## 학습 목표

- 풀 파라미터 fine-tuning 대비 LoRA 가 메모리·연산을 어떻게 줄이는지 수식으로 이해
- 4bit NF4 양자화와 paged optimizer 를 결합한 QLoRA 의 실제 학습 절차
- HuggingFace `peft` + `transformers` + `bitsandbytes` 로 7B 모델을 단일 GPU 24GB 에서 학습
- Adapter 합치기(merge), 다중 어댑터(swap), serving 시 latency 절충

## 1. Full Fine-tuning 의 비용 구조

7B 파라미터 모델을 BF16 으로 풀 파인튜닝할 때 GPU 메모리는 다음을 잡는다.

| 항목 | 수식 | 7B 기준 |
|---|---|---|
| weights | `params * 2 byte` (BF16) | 14GB |
| gradients | `params * 2 byte` | 14GB |
| optimizer states (Adam: m, v) | `params * 8 byte` (FP32 m+v) | 56GB |
| activations | batch · seq · hidden · 2 byte | 6~20GB |
| 합계 | | 90GB+ |

A100 80GB 한 장으로도 빠듯하다. 13B 모델은 사실상 불가능.

## 2. LoRA — Low-Rank Adaptation

LoRA 의 통찰은 다음과 같다. fine-tuning 으로 변하는 파라미터의 변화량 `ΔW` 가 **저차원 행렬로 근사 가능** 하다. 그래서 원래 가중치 `W` 는 동결해 두고, 변화량만 두 작은 행렬 `B`, `A` 의 곱으로 표현한다.

```
W_eff = W_frozen + B · A
크기:  W: d × k, B: d × r, A: r × k    (r << min(d, k))
```

`r` 을 8 / 16 / 32 같은 작은 값으로 두면 학습 대상 파라미터가 원본의 0.1 ~ 1% 로 줄어든다. 7B 모델에서 r=16 이면 학습 파라미터 약 4M.

학습 메모리 효과:

- weights: 14GB(원본 동결) + 8MB (LoRA)
- gradients: 8MB 만 필요 (LoRA 만)
- optimizer states: 32MB
- activations: 동일 (forward 는 모두 통과)

총 ~16GB 수준. 24GB GPU 면 충분.

## 3. QLoRA — 4bit 양자화로 한 단계 더

QLoRA(2023, Dettmers et al.)는 다음을 합친다.

- **NF4 (NormalFloat4) 양자화**: 가중치를 4bit 로 줄여 저장. 7B → 약 3.5GB.
- **Double Quantization**: 양자화 상수까지 다시 양자화 → 추가 ~0.4 bit/param 절약.
- **Paged Optimizer**: optimizer 상태를 GPU↔CPU 사이로 NVIDIA Unified Memory 통해 paging.

7B 모델 학습 메모리가 ~12GB 까지 떨어진다. RTX 3090 / 4090 같은 24GB 컨슈머 GPU 한 장으로 13B 까지 올라간다.

NF4 의 핵심: 정규분포를 가정해 16개의 양자화 레벨을 비등간격으로 배치. 일반 INT4 대비 분포 적합도가 높아 정확도 손실이 적다.

## 4. 실전 코드 — 7B 모델 instruction tuning

```python
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig, TrainingArguments
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training, TaskType
from datasets import load_dataset
from trl import SFTTrainer

MODEL = "meta-llama/Meta-Llama-3-8B"

bnb = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",
    bnb_4bit_compute_dtype=torch.bfloat16,
    bnb_4bit_use_double_quant=True,
)

tokenizer = AutoTokenizer.from_pretrained(MODEL)
tokenizer.pad_token = tokenizer.eos_token

base = AutoModelForCausalLM.from_pretrained(
    MODEL,
    quantization_config=bnb,
    device_map="auto",
    torch_dtype=torch.bfloat16,
)
base = prepare_model_for_kbit_training(base, use_gradient_checkpointing=True)

lora = LoraConfig(
    r=16,
    lora_alpha=32,
    lora_dropout=0.05,
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                    "gate_proj", "up_proj", "down_proj"],
    bias="none",
    task_type=TaskType.CAUSAL_LM,
)
model = get_peft_model(base, lora)
model.print_trainable_parameters()
# trainable: 41,943,040 || all params: 8,071,143,424 || trainable%: 0.5196

ds = load_dataset("yahma/alpaca-cleaned", split="train[:5000]")

def format_example(ex):
    if ex["input"]:
        prompt = f"### Instruction:\n{ex['instruction']}\n\n### Input:\n{ex['input']}\n\n### Response:\n{ex['output']}"
    else:
        prompt = f"### Instruction:\n{ex['instruction']}\n\n### Response:\n{ex['output']}"
    return {"text": prompt}

ds = ds.map(format_example)

args = TrainingArguments(
    output_dir="out/llama3-8b-alpaca-qlora",
    per_device_train_batch_size=4,
    gradient_accumulation_steps=4,
    num_train_epochs=1,
    learning_rate=2e-4,
    bf16=True,
    optim="paged_adamw_8bit",
    warmup_ratio=0.03,
    lr_scheduler_type="cosine",
    logging_steps=20,
    save_strategy="epoch",
    report_to="none",
)

trainer = SFTTrainer(
    model=model,
    train_dataset=ds,
    args=args,
    tokenizer=tokenizer,
    dataset_text_field="text",
    max_seq_length=1024,
    packing=False,
)
trainer.train()
trainer.model.save_pretrained("out/llama3-8b-alpaca-qlora/final")
```

`paged_adamw_8bit` 가 핵심. 8bit Adam + 페이지된 상태 → optimizer 메모리도 4분의 1로.

## 5. target_modules — 어디에 LoRA 를 넣을까

LoRA 가 효과를 내는 자리는 transformer 의 attention projection 과 MLP. 일반 권장은 다음.

| 모델 family | 권장 target_modules |
|---|---|
| LLaMA / Mistral | q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj |
| GPT-2 / GPT-NeoX | c_attn, c_proj |
| BERT | query, key, value, output.dense |
| T5 | q, k, v, o, wi_0, wi_1, wo |

attention 만 vs MLP 도 포함을 두고 실험 결과는 "둘 다 넣는 게 일관되게 좋다"(QLoRA 논문 §4). r=64, alpha=128 같이 큰 값을 쓰는 경우는 거의 없으며 r=8~16 이 평탄한 sweet spot.

## 6. Adapter 합치기(merge) 와 그대로 두기(swap)

학습 후 두 가지 선택지.

```python
from peft import PeftModel

base = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=torch.bfloat16)
peft_model = PeftModel.from_pretrained(base, "out/.../final")

# (1) 합쳐서 단일 모델로
merged = peft_model.merge_and_unload()
merged.save_pretrained("merged-llama3-8b-alpaca")

# (2) 어댑터 swap (멀티 도메인 서빙)
peft_model.load_adapter("out/customer-support", adapter_name="cs")
peft_model.load_adapter("out/coding-assist", adapter_name="code")
peft_model.set_adapter("cs")
```

Trade-off:

- merge: inference 시 adapter 연산이 사라져 latency 가 빠르다. 그러나 4bit 양자화된 base 와 BF16 LoRA 를 합치면 정밀도 손실이 발생 → 보통 BF16 base 로 다시 로드해 합친다.
- swap: 한 base 에 N 개 어댑터를 올려 동적으로 라우팅. customer support / coding / 분석 등 도메인별 분리 운영에 좋다. inference 마다 LoRA 행렬 두 번 곱셈 추가 → ~5~10% latency 증가.

## 7. 학습 안정성 체크포인트

- **Loss 폭발**: learning_rate 가 너무 클 때. LoRA 는 보통 base 의 5~10배 LR(`2e-4`)이 안전. full FT 처럼 `2e-5` 쓰면 학습이 안 됨.
- **NaN/Inf**: bf16 권장. fp16 은 attention softmax 에서 overflow.
- **Validation 보다 train loss 만 떨어짐**: 데이터셋 epoch 가 너무 많거나 r 이 과함. 1 epoch + r=16 을 베이스로.
- **Generation 이상**: chat template 누락. tokenizer 의 `apply_chat_template` 와 학습 prompt 가 일치해야 함.

## 8. 평가 — 단일 지표 함정 피하기

학습이 잘 됐는지 보는 두 단계.

1. **Held-out perplexity** — 같은 분포 안에서 잘 맞추는가.
2. **외부 벤치마크**: MMLU, ARC, GSM8K, IFEval. instruction tuning 의 경우 IFEval 이 가장 직접적.

LoRA 로 특정 도메인을 학습한 모델은 도메인 외 벤치마크가 떨어지는 게 정상이다. 운영 결정 기준은 "내 도메인에서 base 보다 얼마나 좋아졌는가" 가 본질.

## 9. 운영 시 주의

- 어댑터 파일 크기는 r 에 비례. r=16 이면 7B 기준 약 80MB. 100개 도메인 운영 시 8GB 어댑터 저장.
- `load_in_4bit` 모델은 `tensor_parallel`, `pipeline_parallel` 호환성이 라이브러리 버전마다 차이가 큼. vLLM 이 4bit + LoRA 동시 지원하는 버전을 확인.
- adapter 의 hash 와 base 모델 hash 를 함께 기록. base 가 바뀌면 adapter 가 깨질 수 있다.
- 라이선스: LLaMA-3 류 base 모델의 라이선스 조건이 어댑터 배포에도 적용되는지 검토. adapter 는 법적으로는 derivative work 로 해석됨이 일반적.

## 참고

- Hu et al., "LoRA: Low-Rank Adaptation of Large Language Models" (ICLR 2022)
- Dettmers et al., "QLoRA: Efficient Finetuning of Quantized LLMs" (NeurIPS 2023)
- HuggingFace PEFT 공식 문서 (huggingface.co/docs/peft)
- bitsandbytes 라이브러리 README — 4bit 양자화 옵션
- Sebastian Raschka, "Practical Tips for Finetuning LLMs Using LoRA"
