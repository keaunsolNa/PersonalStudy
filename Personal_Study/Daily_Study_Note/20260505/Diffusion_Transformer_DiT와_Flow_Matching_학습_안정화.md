Notion 원본: https://www.notion.so/3575a06fd6d38191af4fd06eef7e179f

# Diffusion Transformer (DiT) 와 Flow Matching — 학습 안정화 메커니즘

> 2026-05-05 신규 주제 · 확장 대상: vLLM PagedAttention, LoRA QLoRA Fine-tuning

## 학습 목표

- 기존 U-Net 기반 diffusion 과 DiT 의 architecture 차이가 *scaling 곡선* 에 어떻게 작용하는지 정리한다
- DDPM / Score Matching / Flow Matching 의 학습 목적 함수 차이를 수식과 PyTorch 코드로 비교한다
- adaLN-Zero, RMSNorm, RoPE 등 DiT 가 채택한 *학습 안정화 트릭* 의 이유를 정리한다
- Flow Matching 이 1-step 또는 4-step inference 를 가능하게 하는 메커니즘과 trade-off 를 정리한다

## 1. U-Net 시대에서 DiT 시대로

Stable Diffusion 1.x ~ 2.x 의 backbone 은 U-Net 이었다. encoder-decoder + skip connection + 다중 attention 블록이 noise 를 점진적으로 제거하는 구조다. 한계는 두 가지였다. 첫째, *parameter 효율*이 낮다. encoder/decoder 의 대칭 구조 때문에 같은 capacity 를 두 번 쓴다. 둘째, *spatial inductive bias* 가 강해 high-frequency 디테일에 약점이 있다.

DiT(Peebles & Xie, 2023) 는 latent diffusion 의 backbone 을 *순수 Transformer* 로 교체한다. latent token grid 를 *patch* 로 자르고, 각 patch 를 transformer block 으로 처리한다. 결과는 다음과 같다.

| 백본 | 같은 FID 도달 시 학습 시간 | 모델 크기 한계 | scaling 법칙 |
|---|---|---|---|
| U-Net | 기준 (1.0×) | ~1B param 부근 plateau | sub-linear |
| DiT | 0.6× ~ 0.7× | 7B+ 까지 선형 scaling | near-linear |

scaling 의 *near-linear* 가 핵심이다. DiT 는 모델 크기를 키우면 FID 가 거의 단조 감소한다. SD3, FLUX, Sora 등 2024~2025 의 대형 모델이 모두 DiT 또는 그 변형(MM-DiT)을 채택한 이유다.

```python
# DiT block 의 핵심 아이디어 (의사 코드)
class DiTBlock(nn.Module):
    def __init__(self, dim, heads, mlp_ratio=4):
        super().__init__()
        self.norm1 = nn.LayerNorm(dim, elementwise_affine=False)
        self.attn  = SelfAttention(dim, heads)
        self.norm2 = nn.LayerNorm(dim, elementwise_affine=False)
        self.mlp   = MLP(dim, dim*mlp_ratio)
        # adaLN-Zero modulation
        self.adaLN = nn.Sequential(nn.SiLU(), nn.Linear(dim, 6*dim, bias=True))
        nn.init.zeros_(self.adaLN[-1].weight); nn.init.zeros_(self.adaLN[-1].bias)

    def forward(self, x, c):
        scale1, shift1, gate1, scale2, shift2, gate2 = self.adaLN(c).chunk(6, dim=-1)
        x = x + gate1 * self.attn(modulate(self.norm1(x), shift1, scale1))
        x = x + gate2 * self.mlp(modulate(self.norm2(x), shift2, scale2))
        return x
```

`modulate(x, s, sh) = x*(1+s) + sh` 가 *conditional layer norm* 이다. timestep embedding 과 class embedding 을 합쳐 conditioning vector `c` 를 만들고, 매 블록마다 6개 파라미터(scale/shift/gate × 2) 로 *세밀하게* 변조한다.

## 2. DDPM / Score / Flow Matching 의 목적 함수 차이

기존 diffusion 학습은 세 갈래로 분기됐고 2024년에 *Flow Matching* 이 새 표준으로 자리잡고 있다.

**(1) DDPM (Ho et al., 2020)** 은 noise 를 직접 예측한다.

```
L_DDPM = E_{x0, t, ε}[ ||ε - ε_θ(x_t, t)||² ]
where x_t = √(ᾱ_t) x_0 + √(1-ᾱ_t) ε,  ε ~ N(0, I)
```

**(2) Score Matching (Song et al., 2021)** 은 *score function* `∇log p_t(x)` 를 예측한다.

```
L_SM = E[ ||s_θ(x_t, t) - ∇log p_t(x_t)||² ]
```

DDPM 의 ε-prediction 과 Score Matching 의 s-prediction 은 *상수배 관계* 라 수학적으로 동치다.

**(3) Flow Matching (Lipman et al., 2023)** 은 *vector field* `v_t` 를 직접 예측해 source distribution 에서 target distribution 으로 흘러가는 ODE 를 학습한다. linear interpolation 을 채택하면 (Rectified Flow):

```
x_t = (1-t) * x_0 + t * x_1,   x_0 ~ N(0,I), x_1 ~ data
v_target = x_1 - x_0
L_FM = E_{x0, x1, t}[ ||v_θ(x_t, t) - (x_1 - x_0)||² ]
```

```python
def flow_matching_loss(model, x_data, c):
    B = x_data.size(0)
    x0 = torch.randn_like(x_data)              # 노이즈
    t  = torch.rand(B, device=x_data.device)   # uniform [0,1]
    t_ = t.view(-1, 1, 1, 1)
    xt = (1 - t_) * x0 + t_ * x_data            # 직선 보간
    v_target = x_data - x0
    v_pred = model(xt, t, c)
    return F.mse_loss(v_pred, v_target)
```

차이의 핵심: DDPM 의 SDE 는 *경로가 곡선*. Flow Matching 의 ODE 는 *경로를 직선으로 강제 가능*. 직선 경로는 *적은 샘플링 스텝* 으로 도달 가능. SD 1.5 가 25~50 스텝이 필요했던 것과 달리, Rectified Flow + reflow 학습을 결합한 모델은 *4 스텝* 으로 같은 품질을 낸다.

## 3. adaLN-Zero — 학습 초반 안정화의 핵심

DiT 의 *adaLN-Zero* 는 learnable scale/shift/gate 의 *초기값을 0* 으로 둔다. 결과적으로 학습 시작 시 모든 transformer block 이 *항등 함수* (identity) 가 된다. 이는 Pre-LN transformer 의 deep network 학습 안정성과 같은 원리이지만, *conditioning gating* 을 추가로 0 초기화해서 *timestep / class 의 영향이 점진적으로 학습에 들어오게* 만든다.

대안 변종은 다음과 같다.

| 변종 | 특징 | 한계 |
|---|---|---|
| 표준 LN + cross-attention conditioning | 구현 단순 | conditioning 채널 capacity 낭비 |
| adaIN (style transfer 용) | scale/shift 만 | gate 부재로 깊은 모델에서 불안정 |
| adaLN | scale/shift/gate 각 1세트 | 충분히 안정 |
| **adaLN-Zero** | adaLN + 0 init | 가장 빠른 수렴, 가장 안정 |

DiT 논문 Table 4 에서 adaLN-Zero 가 같은 학습 step 에서 FID 를 7~12 절대값 끌어내린다. 모든 후속 모델이 이를 채택한 이유다. PixArt-α, SD3, FLUX, Stable Cascade 의 코드에서 `adaLN_zero_init` 이 그대로 보인다.

## 4. RMSNorm, RoPE, MM-DiT — 추가 안정화 트릭

*RMSNorm* 은 LayerNorm 의 mean centering 을 제거한다. activations 의 norm 만 정규화하므로 *연산량이 50% 절감* 되고, large-scale 학습에서 LayerNorm 보다 약간 더 안정적이다. Llama, Mistral 계열도 모두 RMSNorm 을 채택한다.

```python
class RMSNorm(nn.Module):
    def __init__(self, dim, eps=1e-6):
        super().__init__(); self.eps=eps; self.weight=nn.Parameter(torch.ones(dim))
    def forward(self, x):
        return self.weight * x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps)
```

*RoPE (Rotary Position Embedding)* 는 attention 의 query/key 에 *복소수 회전* 을 곱해 상대 위치 정보를 표현한다. 2D 이미지에서는 *2D-RoPE* (axial 또는 mixed) 로 확장된다. learnable position embedding 보다 *학습 분포 외 해상도* 에 더 잘 일반화된다는 보고가 있어 SD3, FLUX 가 채택한다.

*MM-DiT (Multi-Modal DiT)* 는 SD3 가 도입한 변형으로, *image token* 과 *text token* 을 *대등한 두 stream* 으로 처리한다. text 와 image 가 self-attention 에서 *교차 attention* 을 자연스럽게 수행한다. cross-attention block 을 별도로 두는 기존 방식보다 *parameter efficiency* 와 *text adherence* 가 모두 개선된다.

## 5. Flow Matching 이 가능하게 하는 1-step / 4-step 추론

Diffusion 의 추론은 ODE/SDE solver 가 N 스텝의 *적분* 을 수행하는 것이다. Flow Matching 의 직선 경로 + *Reflow / Rectification* 학습으로 N 을 줄인다.

**Reflow procedure**:
1. 초기 모델 `v_θ` 학습 (50 step ODE 로 sampling 가능).
2. 학습된 모델로 `x_0 ~ N(0,I)` → `x_1` 페어를 합성.
3. 합성 페어로 *모델을 다시 학습*. linear interpolation 이 더 정확해진다.
4. 반복.

각 reflow 단계마다 *경로가 더 직선화* 되어 추론 step 수를 줄여도 품질이 유지된다. SD3 / FLUX schnell / Stable Diffusion XL Turbo 모두 이 패턴을 따른다.

| 모델 | 학습 baseline | inference steps | FID (COCO) |
|---|---|---|---|
| SD 1.5 (DDPM) | DDPM | 50 | 9.6 |
| SDXL (DDIM) | DDPM | 25 | 7.4 |
| SD3 (Flow Matching) | FM + reflow | 28 | 7.1 |
| FLUX Schnell | FM + reflow + distillation | 4 | 8.3 |
| SDXL Turbo (ADD) | adversarial distillation | 1 | 11.0 |

distillation 까지 결합한 1-step 모델은 약간의 품질 손실을 감수한다. *interactive 응용* 은 그 trade-off 가 정당화된다.

## 6. 실전 학습 안정화 — gradient norm, EMA, classifier-free guidance

*Gradient norm clipping* 은 diffusion 학습에서 거의 필수다. 단일 outlier 가 latent space 의 분포를 흔들면 학습이 발산한다. `clip_grad_norm_(model.parameters(), 1.0)` 가 표준값.

*EMA (Exponential Moving Average) weight* 는 추론 시 더 안정적인 결과를 준다. `ema_decay = 0.9999` 가 기본. 학습 step 마다 `ema_w = decay * ema_w + (1-decay) * w` 로 업데이트한다. EMA weight 가 학습 weight 보다 *FID 를 1~2 더 낮춘다*. 메모리 비용은 weight 1 set 추가.

*Classifier-Free Guidance (CFG)* 는 conditioning 을 *조건부 + 무조건부* 두 모델로 학습해, 추론 시 두 score 의 *외삽* 을 사용한다.

```python
def cfg_step(model, x_t, t, c, w=7.5):
    e_uncond = model(x_t, t, None)        # unconditional
    e_cond   = model(x_t, t, c)           # conditional
    return e_uncond + w * (e_cond - e_uncond)
```

`w` 가 클수록 *prompt adherence* 가 높아지지만 *다양성* 이 감소하고 *artifact* 가 발생한다. SDXL/SD3 권장값은 5.0 ~ 7.5. CFG-zero/CFG++ 같은 후속 연구는 *w 의 동적 스케줄링* 으로 그 trade-off 를 완화한다.

## 7. 운영 / 비용 관점

DiT 모델의 학습 비용은 모델 크기 × 데이터셋 × 스텝 수에 비례한다. 7B 파라미터 DiT 를 1024² 해상도로 학습하려면 H100 GPU 클러스터 64 ~ 256 장 × 수 주가 필요하다. 비용은 모델 한 번 학습에 *수억 원* 이다.

대안은 다음과 같다. 첫째, *base 모델 + LoRA fine-tuning*. 도메인 특화 데이터(예: 회사 로고, 제품 이미지) 는 LoRA rank 16~32 로 1~10 GPU 시간이면 학습된다. 둘째, *DreamBooth* 류 *subject-driven* 학습. 한 개 subject 에 5~20 장으로 가능하다. 셋째, *ControlNet* 추가. canny/edge/pose 같은 spatial conditioning 을 추가 학습한다.

추론 비용 관점에서는 *4-step Flow Matching 모델 + INT8 양자화 + ONNX Runtime / TensorRT* 조합이 *T4 GPU 한 장으로 1024² 이미지 5초 이내* 가 가능하다. 인터랙티브 응용에 충분한 수준이다.

## 8. 운영에서 본 도입 결정 기준

| 상황 | 권장 |
|---|---|
| 일반적 텍스트→이미지 생성 | SDXL / SD3 / FLUX 직접 사용, fine-tuning 없음 |
| 도메인 데이터 셋 1~5만장 | base + LoRA, full fine-tuning 불필요 |
| 50만장+ 도메인 데이터 + 차별화된 모델 | DiT scratch 학습 검토 |
| 1-step 모바일 추론 | distilled 모델(FLUX schnell, SDXL Turbo) |
| 영상 생성 | Sora-style spatio-temporal DiT, 클러스터 자원 필수 |

DiT + Flow Matching 은 2024~2025 의 새 표준이지만, *대부분의 application* 에는 직접 학습이 필요 없다. 이미 공개된 base 모델을 *조합* 하고 *경량 adapter* 로 도메인을 입히는 것이 가장 비용 효율적이다. 학습 안정화 트릭(adaLN-Zero, RMSNorm, RoPE) 의 원리를 알면 fine-tuning 시 hyperparameter 결정과 디버깅이 훨씬 정확해진다.

## 참고

- "Scalable Diffusion Models with Transformers" Peebles & Xie, ICCV 2023
- "Flow Matching for Generative Modeling" Lipman et al., ICLR 2023
- "Stable Diffusion 3 Technical Report" Esser et al., 2024
- "Rectified Flow" Liu et al., ICLR 2023