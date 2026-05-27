Notion 원본: https://www.notion.so/36d5a06fd6d38101819cfcd48d55ab39

# LLM Direct Preference Optimization (DPO/IPO/KTO) 와 RLHF 비교

> 2026-05-27 신규 주제 · 확장 대상: LLM Alignment / Post-training

## 학습 목표

- RLHF 의 reward model + PPO 파이프라인이 가진 안정성 / 비용 문제를 정리한다
- DPO 가 reward model 을 우회하면서 동일 최적해에 도달하는 수학적 근거를 단계별로 따라간다
- IPO, KTO, ORPO 등 DPO 변형의 차이를 *손실 함수와 데이터 요구* 관점에서 비교한다
- 실무 적용 체크리스트(데이터 수집 단가, regularizer 선택, 평가 방법)를 정리한다

## 1. Alignment 의 일반 문제 설정

사전학습 모델 π_ref(y|x) 와 인간 선호 데이터 (x, y_w, y_l) 가 있을 때, KL-제약 보상 최대화: max E[r(x,y)] - β KL(π || π_ref).

## 2. RLHF: Reward Model + PPO 의 3단계

1. **SFT**: human-written demonstration 으로 π_ref
2. **Reward Model 학습**: (x, y_w, y_l) 페어로 Bradley-Terry 모델 fit. r_φ 가 *어떤 응답이 더 좋을 확률* 추정
3. **PPO**: π_θ 를 r_φ 의 기댓값 최대화. KL penalty 가 reward 에 더해진 형태

| 문제점 | 비용/위험 |
|---|---|
| Reward Model 학습 | 별도 모델 — 계산비, 메모리 |
| PPO 학습 | actor-critic, hyperparameter 민감 |
| Reward Hacking | π_θ 가 r_φ 의 약점을 찾아 *진짜 선호와 무관한* 보상 부풀림 |
| OOD reward 오차 | 학습 분포 밖 |

## 3. DPO 의 핵심 아이디어 (Rafailov et al. 2023)

KL-제약 보상 최대화의 *해석적 해* 는

π*(y|x) = (1/Z(x)) π_ref(y|x) exp(r(x,y)/β)

r 에 대해 풀면

r(x,y) = β log[π*(y|x)/π_ref(y|x)] + β log Z(x)

Bradley-Terry 선호 확률 P(y_w ≻ y_l | x) = σ(r(x,y_w) - r(x,y_l)) 에 대입하면 *Z(x) 가 소거* 된다. 결과적으로

L_DPO(θ) = -E[log σ(β log(π_θ(y_w)/π_ref(y_w)) - β log(π_θ(y_l)/π_ref(y_l)))]

*reward model 도, RL 도 없이* 직접 LM head 만 미니배치로 학습한다. 같은 최적해에 도달한다는 게 핵심.

## 4. DPO 의 구현 형태

PyTorch + TRL 라이브러리 기준.

```python
from trl import DPOTrainer, DPOConfig

cfg = DPOConfig(
    output_dir="./dpo-out",
    per_device_train_batch_size=4,
    gradient_accumulation_steps=8,
    learning_rate=5e-7,
    num_train_epochs=1,
    beta=0.1,
    loss_type="sigmoid",
    max_prompt_length=512,
    max_length=1024,
    bf16=True,
)

trainer = DPOTrainer(
    model=policy_model,
    ref_model=ref_model,
    args=cfg,
    train_dataset=pref_ds,
    tokenizer=tokenizer,
)
trainer.train()
```

학습은 *PPO 의 1/3~1/10 비용*. hyperparameter 간단 (β, lr).

## 5. DPO 의 한계 — 두 가지 실패 모드

(a) **Length bias**: chosen 이 일관되게 길면 모델이 *길게 쓰면 좋다* 를 학습.

(b) **π_ref 의 likelihood 가 낮은 응답에서 폭주**: 학습 중 chosen 의 상대 log-prob 이 ref 보다 충분히 높아져도 *rejected* 의 log-prob 을 계속 낮추려 한다. π_θ 가 reference 에서 너무 멀어짐.

## 6. IPO (Azar et al. 2023)

DPO 의 sigmoid 손실이 *overfit* 한다는 분석. log-likelihood ratio 차이를 제곱 손실로 fit.

L_IPO = E[(log[π_θ(y_w)/π_ref(y_w)] - log[π_θ(y_l)/π_ref(y_l)] - 1/(2β))²]

sigmoid 의 *무한히 벌리는 동기* 가 없어 length bias / overfit 완화.

## 7. KTO (Ethayarajh et al. 2024)

*unpaired* (단일 응답 + good/bad 라벨)로 학습. prospect theory 에서 영감을 받은 비대칭 sigmoid. pair 데이터가 충분하지 않을 때 안정적.

## 8. ORPO (Hong et al. 2024)

SFT 와 preference 학습을 *한 단계로 합치는* 변형. log-odds ratio penalty 를 SFT loss 에 더한다. π_ref 필요 없음 (메모리 절감) 하지만 KL 제약 약해 모델/데이터에 민감.

## 9. 변형 비교 표

| 알고리즘 | 데이터 형식 | ref model | 손실 형태 | 강점 | 약점 |
|---|---|---|---|---|---|
| RLHF(PPO) | preference pair | 필요(KL penalty) | actor-critic + KL | 표현력 | 비용/안정성 |
| DPO | preference pair | 필요 | sigmoid CE | 단순/저비용 | length bias, ref drift |
| IPO | preference pair | 필요 | MSE on log-ratio | overfit 감소 | hyperparameter 추가 |
| KTO | binary good/bad | 필요 | prospect-theory weighted | unpaired 데이터 활용 | 라벨 정확도 영향 큼 |
| ORPO | preference pair | 불필요 | SFT + log-odds | 메모리 절감 | KL 제약 약함 |
| SimPO | preference pair | 불필요 | margin-based | ref 불필요 | 일부 benchmark 불안 |

## 10. 실무 적용 체크리스트

1. 데이터 수집: pair 라벨 일관성 Cohen kappa > 0.7 확인
2. 모델 크기: 7B–13B 가 sweet spot
3. β 선택: 0.05~0.5 범위, 기본 0.1
4. Regularizer: SFT-DPO 혼합 (TRL 의 `rpo_alpha`)
5. 평가: Arena-Hard, MT-Bench, AlpacaEval 2.0 length-controlled
6. 추론 시 분포 점검

## 11. 실측 결과 예

| 모델/설정 | benchmark | RLHF | DPO | IPO | KTO |
|---|---|---|---|---|---|
| Llama-2-7B + UF | AlpacaEval-2 LC | 13.0 | 14.2 | 14.8 | 14.5 |
| Mistral-7B + UF | MT-Bench | 6.95 | 7.15 | 7.20 | 7.10 |
| Llama-3-8B + UF + Mix | Arena-Hard | 22.3 | 23.7 | 24.5 | 24.0 |

## 12. 한국어 도메인 적용 시 추가 주의

- 토크나이저 vocab 의 한국어 coverage 낮으면 length 비대칭
- polite/non-polite 어미 차이가 style preference 로 잡혀 톤 변화
- preference 라벨러 가이드라인 명문화 (사실성/안전성/도움됨 우선순위)

## 13. 학습 안정화 트릭

(1) Reference-free margin clipping [-3, 3]
(2) Identity Anchor regularizer (rpo_alpha=0.1)
(3) Mini-batch 다양성 (prompt id hash)
(4) Warmup + Cosine schedule (warmup 5%)
(5) Gradient checkpointing + bf16 + flash-attention-2

## 14. 평가 함정 — Reward Hacking 의 새로운 형태

| 함정 | 발견 신호 | 대응 |
|---|---|---|
| Length bias | 평균 응답 길이 30%+ 증가 | length-controlled win-rate 사용 |
| Style 일치만 학습 | 사실성 저하 | TruthfulQA 병행 |
| Hallucination 증가 | 자신 있는 잘못된 답 ↑ | uncertainty calibration / RAG 결합 |
| 안전성 약화 | jailbreak 성공률 ↑ | safety preference 데이터 보강 |
| 한정 도메인 overfit | OOD 성능 하락 | held-out domain 평가 |

## 참고

- Rafailov et al., "Direct Preference Optimization" NeurIPS 2023
- Azar et al., "A General Theoretical Paradigm to Understand Learning from Human Preferences" 2023 (IPO)
- Ethayarajh et al., "KTO: Model Alignment as Prospect Theoretic Optimization" 2024
- Hong et al., "ORPO" 2024
- Hugging Face TRL 라이브러리 문서 (https://huggingface.co/docs/trl)
