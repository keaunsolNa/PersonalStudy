Notion 원본: https://www.notion.so/35f5a06fd6d381ffa648f6ad03bd0115

# Direct Preference Optimization (DPO)와 RLHF Reward Model — KL 패널티와 Reference Policy 비교

> 2026-05-13 신규 주제 · 확장 대상: AI (LLM Alignment)

## 학습 목표

- RLHF 의 3단계(SFT → Reward Model → PPO) 와 DPO 1단계 접근의 수학적 등가성 파악
- DPO 의 implicit reward 와 KL 정규화가 PPO 의 KL penalty 와 어떤 식으로 같은 목표를 푸는지 도출
- DPO / IPO / KTO / SimPO 가 같은 framework 안에서 어떤 가정(데이터, 길이 편향)을 다르게 두는지 비교
- 실제 학습 비용, GPU 메모리, 데이터 형식의 trade-off 와 운영상의 선택 기준

## 1. 정렬(alignment) 문제의 정의

언어 모델을 *사람이 선호하는 응답* 으로 끌어오는 단계는 보통 세 가지로 쪼개진다.

1. *SFT* (Supervised Fine-Tuning): demonstration 데이터로 cross-entropy 학습
2. *Preference Modeling*: 한 prompt 에 대해 두 응답 (chosen / rejected) 의 선호 라벨로 reward model r_θ 학습
3. *RL Fine-Tuning*: SFT 모델 π_θ 를 r_θ 로 업데이트, 기준 정책 π_ref 로부터 너무 멀어지지 않게 KL 패널티

RL 단계의 목적함수는:

```
maximize_π  E_{x ~ D, y ~ π(·|x)} [ r_θ(x, y) ]
        - β · D_KL ( π(·|x)  ||  π_ref(·|x) )
```

여기서 β 가 KL 패널티 강도다. 너무 낮으면 reward hacking, 너무 높으면 baseline 에서 못 벗어남.

PPO 는 이 목적함수를 *RL gradient (policy gradient + clipping)* 로 푼다. 구현이 복잡하고 sample 효율이 낮으며 보통 reward model 호출이 train step 마다 들어간다.

## 2. DPO 의 핵심 아이디어 — RL 없이 같은 해

Rafailov et al. 2023 의 통찰은 *위 목적함수의 닫힌 해* 가 존재한다는 것이다.

```
π*(y | x) = (1/Z(x)) · π_ref(y | x) · exp( r(x, y) / β )
```

이걸 r 에 대해 정리하면:

```
r(x, y) = β · log [ π*(y|x) / π_ref(y|x) ] + β · log Z(x)
```

Bradley-Terry 선호 모델에서 *chosen y_w 가 rejected y_l 보다 선호될 확률* 은:

```
P(y_w ≻ y_l | x) = σ( r(x, y_w) - r(x, y_l) )
```

여기에 위 r 식을 대입하면 *log Z(x) 항이 빼서 사라지고*, 그 결과 PPO 의 reward model + RL 단계가 *한 번의 binary classification loss* 로 환원된다:

```
L_DPO(θ) = -E_{(x, y_w, y_l) ~ D} [
    log σ( β · log(π_θ(y_w|x)/π_ref(y_w|x))
         - β · log(π_θ(y_l|x)/π_ref(y_l|x)) )
]
```

이게 DPO loss 의 전부다. *reward model 없이, RL 없이, KL penalty 도 implicit 으로 박혀 있다*.

## 3. DPO 학습 루프 — PyTorch 의사 코드

```python
def dpo_loss(model, ref_model, batch, beta=0.1):
    # batch: (prompt, chosen, rejected)
    pi_logps_c = log_prob(model, batch.prompt, batch.chosen)
    pi_logps_r = log_prob(model, batch.prompt, batch.rejected)
    with torch.no_grad():
        ref_logps_c = log_prob(ref_model, batch.prompt, batch.chosen)
        ref_logps_r = log_prob(ref_model, batch.prompt, batch.rejected)

    logits = beta * (
        (pi_logps_c - ref_logps_c) -
        (pi_logps_r - ref_logps_r)
    )
    return -F.logsigmoid(logits).mean()
```

`ref_model` 은 *SFT 결과를 그대로 동결한 사본*. 학습 중 weight 가 변하지 않으므로 KV cache 또는 *pre-computed logprob* 으로 한 번만 계산해두면 GPU 메모리/시간을 절약할 수 있다. 큰 모델(70B+)에서는 ref logprob 를 *오프라인 dump* 해두는 패턴이 표준이다.

## 4. β 의 의미와 튜닝

PPO 에서 β 는 *KL penalty coefficient*. DPO 에서도 같은 β 가 *implicit KL temperature* 역할을 한다.

* β 작음(0.01~0.05): reward signal 강조, π_ref 에서 멀어짐 허용 → 빠른 학습이지만 mode collapse, 길이 편향 위험
* β 큼(0.3~1.0): π_ref 와 가깝게 유지 → 안정적이지만 학습 효과 약함

실측 권장 범위는 7B 모델에서 0.1~0.3, 70B 에서는 0.05~0.15. SFT 단계가 짧은 경우 β 를 낮춰 더 큰 변화를 허용하고, SFT 단계가 충분히 길었으면 β 를 높여 보전한다.

## 5. DPO 의 알려진 약점과 후속 변형

### 5.1 길이 편향

DPO 는 *길이가 더 긴 답변을 선호한다*. 사람 라벨러도 길이 편향이 있고, BradleyTerry loss 가 토큰 단위 logprob 합을 비교하기 때문이다.

대응: *length-normalized DPO*. logprob 를 토큰 수로 나누거나, SimPO 처럼 *reference-free* 목적함수로 길이 항을 명시적으로 제어.

### 5.2 IPO — Identity Preference Optimization (Azar 2024)

DPO 가 *완벽한 선호 라벨* 을 가정하지만 실제 라벨은 noisy 하다. IPO 는 log-sigmoid 대신 *squared loss* 를 써서 reward overestimation 을 누른다.

```
L_IPO = E [ ( h(x, y_w, y_l) - 0.5/β )^2 ]
```

여기서 h 는 DPO 와 같은 log-ratio 차. β/2 평형점을 유한하게 잡아 무한한 reward separation 을 피한다.

### 5.3 KTO — Kahneman-Tversky Optimization

KTO 는 *pair 가 아닌 single sample* 로 학습한다. 라벨이 (응답, 좋음 / 나쁨) 형태면 충분. 데이터 수집 비용이 적고, prospect theory 의 손실 회피 형상을 손실함수에 반영.

### 5.4 SimPO — Simple Preference Optimization

ref model 자체를 제거. *length-normalized log-ratio* 와 *margin* γ 를 도입.

```
L_SimPO = -log σ( (1/|y_w|) log π(y_w|x) - (1/|y_l|) log π(y_l|x) - γ )
```

ref model 추론이 사라져 GPU 메모리 절반. 단점은 KL 정규화가 없어 π_θ 가 SFT 에서 멀리 벗어날 수 있어 학습률을 작게 잡아야 함.

## 6. 비교 표

| 방법 | ref model 필요 | RL 필요 | 라벨 형식 | 길이 편향 처리 | 권장 β/γ |
| --- | --- | --- | --- | --- | --- |
| PPO + RM | ✅ | ✅ | pairwise + scalar | 명시적 trick 필요 | KL β≈0.05 |
| DPO | ✅ | ❌ | pairwise | 없음 | β=0.1~0.3 |
| IPO | ✅ | ❌ | pairwise | 부분적 | β=0.1~0.5 |
| KTO | ✅ | ❌ | single (좋음/나쁨) | 약함 | β=0.1 |
| SimPO | ❌ | ❌ | pairwise | length-norm | γ=0.5~2.0 |
| ORPO | ❌ | ❌ | pairwise | SFT loss와 결합 | λ=0.1~0.5 |

## 7. 학습 비용 비교 (7B 모델, A100×8 기준)

| 방법 | 데이터 60K pair | wall clock | peak GPU mem | RM 학습 별도 |
| --- | --- | --- | --- | --- |
| PPO + RM | 90시간 (RM 8h + PPO 82h) | 90h | 78 GB | ✅ |
| DPO | 16~22시간 | 18h | 64 GB | ❌ |
| IPO | DPO 와 동등 | 18h | 64 GB | ❌ |
| KTO | 14시간 | 14h | 56 GB | ❌ |
| SimPO | 10~12시간 | 11h | 38 GB | ❌ |

DPO 계열이 PPO 대비 *4~6배 빠르고 메모리 25% 적다*. 학계/오픈소스 SOTA 가 거의 다 DPO 변형으로 넘어간 가장 큰 이유.

## 8. 운영 권장 결정 트리

1. *labeling 예산이 풍부하고 pairwise 라벨 가능* → DPO 또는 IPO
2. *labeling 이 binary 만 가능 (좋음/나쁨)* → KTO
3. *7B+ 모델, ref model 메모리 부담* → SimPO, ORPO
4. *quality 가 매우 중요한 critical 도메인 (의료/법률)* → PPO + 정교한 RM (DPO 보다 표현력은 높음)
5. *SFT 가 부실해서 ref 가 신뢰 안 됨* → SFT 한 번 더 충분히 돌리고 DPO 로 진행

마지막으로, alignment 단계 평가는 *MT-Bench / Arena-Hard / IFEval* 같은 외부 벤치마크와 내부 도메인 지표를 같이 본다. preference accuracy 만 보면 reward hacking 을 놓치기 쉽다. KL divergence (π_θ vs π_ref) 와 응답 길이 분포를 *학습 도중 매 step 로깅* 해서 mode collapse 조기 감지에 활용한다.


## 9. 데이터셋 — preference pair 의 품질

DPO 의 결과는 *preference pair 데이터셋* 의 품질에 압도적으로 의존한다. 공개된 주요 데이터셋:

| 데이터셋 | 규모 | 출처 | 특징 |
| --- | --- | --- | --- |
| Anthropic HH-RLHF | 161K pair | 사람 라벨 | helpful + harmless 두 축 |
| OpenAssistant | 161K | 자원봉사자 라벨 | 다국어 |
| UltraFeedback | 64K | GPT-4 가 평가 | scalar 점수 → pair 변환 |
| Nectar | 183K | GPT-4 기반 | 다양한 7개 모델 응답 비교 |
| AlpacaFarm | 20K | 합성 | 학술 reproduction |

GPT-4 가 평가한 합성 데이터는 *수집 비용이 사람의 1/100* 이라 매력적이지만 *evaluator 편향* 을 그대로 학습한다. 예를 들어 GPT-4 가 *길고 형식적인 답변* 을 선호하면 학습된 모델도 같은 편향을 갖는다. 그래서 SOTA 모델은 사람 라벨 + 합성 평가 데이터를 *2:1 비율* 로 섞는 패턴을 자주 쓴다.

### 데이터 품질 점검 지표

* *agreement rate*: 같은 pair 를 여러 evaluator 에게 보냈을 때 일치율. 70% 미만이면 ambiguous pair → 학습 신호 약함
* *length skew*: chosen 평균 길이 / rejected 평균 길이. 1.3 이상이면 길이 편향 위험
* *position bias*: chosen 이 A 또는 B 위치에 치우치는지. 50/50 이 정상

## 10. 학습 디버깅 — preference accuracy 추적

DPO 학습 중 monitor 해야 할 핵심 지표:

```python
def metrics(pi_logps_c, pi_logps_r, ref_logps_c, ref_logps_r, beta):
    pi_diff = pi_logps_c - pi_logps_r
    ref_diff = ref_logps_c - ref_logps_r
    logits = beta * (pi_diff - ref_diff)
    return {
        'reward_chosen': beta * (pi_logps_c - ref_logps_c),
        'reward_rejected': beta * (pi_logps_r - ref_logps_r),
        'reward_margin': beta * (pi_diff - ref_diff),
        'accuracy': (logits > 0).float().mean(),
        'kl_chosen': (pi_logps_c - ref_logps_c).mean(),
        'kl_rejected': (pi_logps_r - ref_logps_r).mean(),
    }
```

* `accuracy` 가 0.55 → 0.85 로 증가하면 정상 학습
* `accuracy` 가 0.99+ 면 overfitting / reward hacking 의심
* `kl_chosen` 이 양수로 폭증하면 SFT 에서 멀어짐 → β 상향 고려
* `reward_margin` 이 plateau 면 데이터 한계 도달

## 11. RLHF 와 DPO 의 미묘한 비등가성

이론적으로 DPO 와 PPO+RM 은 *같은 최적해*를 갖는다. 그러나 실제 학습에서 두 가지 비등가성이 관찰된다.

1. *Off-policy vs On-policy*: DPO 는 *고정 데이터셋* 으로 학습 → off-policy. PPO 는 *현재 정책으로 샘플링* → on-policy. 새 분포의 응답을 학습하려면 PPO 가 유리.
2. *Reward extrapolation*: PPO 의 RM 은 *training 분포 밖* 의 응답도 점수를 매길 수 있어 explore 가능. DPO 는 *데이터에 있는 pair* 만 알기 때문에 외삽 불가.

따라서 RLHF 가 더 적합한 시나리오:
* SFT 가 매우 부실해서 *대폭 분포 이동* 이 필요한 경우
* 도메인 특화 reward (예: code correctness, math solvability) 가 *명시적*으로 정의된 경우
* online learning 으로 *지속 업데이트* 가 필요한 경우 (예: 사용자 피드백 반영)

DPO 가 더 적합한 시나리오:
* 정적 preference 데이터셋이 있고 *재현성*이 중요
* 학습 비용을 줄여야 하는 자원 제약 환경
* alignment 가 *주로 스타일 / 톤* 조정 위주

## 12. 실전 운영 팁

* *LoRA + DPO* 결합이 사실상 표준 — full FT 의 1/10 GPU 메모리로 거의 동등한 효과
* *Adam β1 0.9, β2 0.999, weight_decay 0.0* 권장 (LR 1e-6 ~ 5e-6)
* *Cosine schedule + warmup 0.1* 가 무난
* *gradient checkpointing* 필수 (ref + policy 두 모델 메모리)
* *flash-attention 2 또는 3* 으로 attention 메모리 절약
* *batch size 32~64* (token 기준 64K~128K) — 너무 작으면 학습 불안정
* *evaluator 에 GPT-4o + Claude Sonnet 둘 다* 써서 평가 편향 평균화

마지막으로 *alignment 의 한계*. DPO/RLHF 모두 *base capability 를 늘리진 않는다*. SFT 단계가 *원하는 행동을 모두 포함* 해야 한다. preference 학습은 *이미 있는 모드 중에서 어떤 모드를 강조할지* 결정할 뿐, 새로운 모드를 만들지 못한다는 점이 운영의 핵심 가정.

## 13. 평가 — preference accuracy 만으론 부족하다

DPO 학습이 잘 됐는지 평가하는 표준 벤치마크:

| 벤치마크 | 평가 방식 | 강점 |
| --- | --- | --- |
| MT-Bench | GPT-4 judge, 80개 multi-turn 질문 | 일반 대화 품질 |
| Arena-Hard | crowdsourced + GPT-4 judge | challenging prompt |
| AlpacaEval 2 | GPT-4 vs GPT-4-Turbo | length-controlled win rate |
| IFEval | instruction following 정확도 | 형식 준수 능력 |
| MMLU | 객관식 학술 지식 | 정렬 단계 후 성능 저하 모니터링 |

정렬 단계 후 *MMLU 점수가 5% 이상 떨어지면 alignment tax* 가 과도하다는 신호. 이 경우 β 를 더 높이거나 SFT 데이터에 학술 지식을 더 보충해야 한다. Anthropic 의 Constitutional AI 논문에서는 alignment tax 가 0.5~2% 수준이라 보고됐다.

## 참고

- Rafailov et al. 2023 — "Direct Preference Optimization: Your Language Model is Secretly a Reward Model" (NeurIPS)
- Azar et al. 2024 — "A General Theoretical Paradigm to Understand Learning from Human Preferences" (IPO)
- Ethayarajh et al. 2024 — "KTO: Model Alignment as Prospect Theoretic Optimization"
- Meng et al. 2024 — "SimPO: Simple Preference Optimization with a Reference-Free Reward"
- Hong et al. 2024 — "ORPO: Monolithic Preference Optimization without Reference Model"
- Hugging Face TRL 라이브러리 DPO Trainer 소스
- Anthropic — "Training a Helpful and Harmless Assistant" (HH-RLHF 데이터 공개)
- DeepSeek-R1 technical report — DPO + RL 결합 학습 사례
