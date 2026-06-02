Notion 원본: https://www.notion.so/3735a06fd6d38154af1dfe8bcfbbd46a

# LLM Constitutional AI와 RLAIF Self-Critique Loop 학습 파이프라인

> 2026-06-02 신규 주제 · 확장 대상: LLM

## 학습 목표

- Constitutional AI (CAI) 의 SL-CAI / RL-CAI 단계와 self-critique 의 데이터 흐름을 분리한다
- RLHF 와 RLAIF 의 reward signal 수집 비용·품질 트레이드오프를 정리한다
- Self-critique prompt 작성 원칙(원칙 명세, revision 지시)을 코드 단위로 설계한다
- 평가(Anthropic HH-RLHF, RewardBench) 결과로 RLAIF 의 효과 범위를 검증한다

## 1. RLHF 의 한계와 RLAIF 의 동기

InstructGPT, ChatGPT 의 핵심 학습 단계는 RLHF — Reinforcement Learning from Human Feedback. *human labeler 가 모델 출력 두 개 중 더 나은 것을 선택*, 그 선호도로 reward model 을 학습, PPO 로 policy 를 업데이트. 효과적이지만 비싸다 — 한 항목당 평균 $0.50~$2.00, 10~100만 비교가 필요.

또한 *labeler 간 편차*, *문화적 편향*, *지치는 효과(fatigue)* 가 데이터 품질에 영향. Anthropic 은 2022 년 *Constitutional AI* (Bai et al.) 에서 *AI 가 AI 의 출력을 비판하고 수정* 하는 방식으로 *human 필요량을 줄이는* 접근을 발표했다.

RLAIF = Reinforcement Learning from AI Feedback. *Reward signal 의 출처가 human → AI* 로 바뀌었을 뿐, 학습 구조는 RLHF 와 거의 동일.

## 2. CAI 의 두 단계 — SL-CAI 와 RL-CAI

### SL-CAI (Supervised Learning from Constitutional AI)

1) Helpful-only model 로 *harmful prompt* 에 대한 응답 생성
2) 같은 model 에 *constitution 의 한 원칙* 을 제시하며 *비판* 요청
3) 같은 model 에 *revision* 요청 — 비판을 반영한 새 응답
4) 여러 라운드 revision 후 *최종 응답* 을 supervised fine-tuning 데이터로 사용

```
Step 1: prompt → harmful response
Step 2: response + principle → critique
Step 3: prompt + critique → revision
Step 4: (prompt, revision) 쌍을 SFT 데이터로 사용
```

### RL-CAI (Reinforcement Learning from CAI)

1) SL-CAI 로 fine-tune 된 model 이 *동일 prompt 에 응답 두 개* 생성
2) Constitution 의 원칙들이 적힌 prompt 로 AI judge 에게 *어느 응답이 더 나은가* 질문
3) Multi-principle 의 응답 선호를 *reward model* 에 fitting
4) PPO/DPO 로 policy 를 reward model 에 맞춰 학습

## 3. Constitution 의 설계

Anthropic 의 원본 constitution(2022) 은 약 16개 원칙. 예시: "가장 덜 해로운 응답을 선택", "불법/비윤리적 활동을 권장하지 않는 응답", "도덕적 인식을 보이되 설교적이지 않은 응답" 등.

각 학습 iteration 에서 *원칙 하나를 무작위 선택* 해 critique 한다. 이유는 *원칙 간의 미묘한 충돌* — 하나의 원칙으로 모두 평가하면 다른 차원이 무시된다. 무작위 샘플링으로 *모든 원칙이 평균적으로 반영* 되게 한다.

원칙 작성의 함정: 너무 추상적("Be ethical") — 모델이 일관되게 적용 못함. 너무 구체적("Don't mention guns") — over-refusal. 모호한 우선순위 — 원칙 A 와 B 충돌 시 어느 게 우선인지 알 수 없음.

## 4. Self-Critique Prompt 의 구체

### Critique prompt

```python
CRITIQUE_TEMPLATE = """
You are evaluating an AI response according to a specific principle.

Principle: {principle}

User request:
{user_prompt}

AI response:
{response}

Identify ways in which the response could be improved according to 
the principle above. Be specific about which sentences or phrases 
violate the principle and why.
"""
```

### Revision prompt

```python
REVISION_TEMPLATE = """
Given the user's original request, the assistant's response, and a 
critique, write a revised response that addresses the critique.

The revised response should:
- Be helpful where appropriate
- Address the issues raised in the critique
- Not over-refuse — only decline if the request is genuinely harmful

User request:
{user_prompt}

Original response:
{response}

Critique:
{critique}

Revised response:
"""
```

핵심 디자인 결정: *Revision 도 critique 만큼 명확한 지시* 가 필요. 너무 단순하면 over-refusal 로 빠짐. *Helpful 의 명시적 유지* — "Be helpful where appropriate" 가 없으면 모델이 *모든 요청을 거절* 하는 방향으로 collapse. *Critique 와 revision 은 별도 turn* — 한 prompt 에 둘 다 시키면 깊이가 얕음.

## 5. RLHF vs RLAIF 비용 비교

| 항목 | RLHF | RLAIF |
|---|---|---|
| 데이터 단가 (1 비교) | $0.50~$2.00 | $0.001~$0.05 (API 호출 비용) |
| 처리량 | ~수만/일 (labeler 의존) | ~수백만/일 (병렬 호출) |
| 일관성 | labeler 간 분산 큼 | model 의 deterministic decoding 으로 향상 |
| 편향 | labeler 인구통계 의존 | base model 의 편향 의존 |
| Refresh 비용 | 다시 모집·교육 | prompt 만 변경 |

비용 우위는 RLAIF 가 압도. 하지만 *AI judge 가 옳은가* 의 평가가 핵심 질문. Anthropic 의 후속 평가에서 *AI judge 가 human 과 80~90% agree* (특정 도메인). 그 외 도메인에선 차이 큼.

## 6. Reward Model 의 training data 흐름

CAI 의 reward model 학습 데이터:

```
Dataset = {
  (prompt_i, response_a_i, response_b_i, preference_i, principles_i)
  for i in N
}
```

`preference_i` 는 AI judge 가 제공한 *0 또는 1*. 단순 선택 대신 *log-probability* 를 reward 로 직접 사용하면 더 부드러운 신호:

```python
def get_preference_logits(judge_model, prompt, resp_a, resp_b, principle):
    text = format_prompt(prompt, resp_a, resp_b, principle)
    out = judge_model.generate_with_logprobs(text + " Answer: ")
    p_a = exp(out.logprob['A'])
    p_b = exp(out.logprob['B'])
    return p_a / (p_a + p_b)  # soft preference, ∈ [0,1]
```

Soft preference 가 *완전한 0/1 보다 더 좋은 reward signal* 이라는 게 후속 연구(Lee et al., 2023) 의 발견. Reward model 의 loss 도 BCE 대신 KL-divergence 로 일반화.

## 7. PPO vs DPO — Policy Optimization 의 변형

**PPO (Proximal Policy Optimization)**: 전통적 RL 알고리즘. *reward model 의 reward* 와 *KL constraint* 로 policy 를 점진 업데이트. 단점은 *복잡, 불안정, GPU 사용량 큼*.

**DPO (Direct Preference Optimization, Rafailov et al. 2023)**: Reward model 학습 단계를 *생략*. Preference data 직접에서 policy update. 이론적으로 PPO 와 동치인 closed-form 해.

```python
def dpo_loss(policy, ref_policy, prompt, chosen, rejected, beta=0.1):
    log_p_chosen   = policy.log_prob(chosen, prompt)
    log_p_rejected = policy.log_prob(rejected, prompt)
    log_ref_chosen   = ref_policy.log_prob(chosen, prompt)
    log_ref_rejected = ref_policy.log_prob(rejected, prompt)
    
    logits = beta * ((log_p_chosen - log_ref_chosen) - 
                     (log_p_rejected - log_ref_rejected))
    return -F.logsigmoid(logits).mean()
```

DPO 의 장점 — *단일 forward + backward*, reward model 없음, 안정적. CAI 가 *원래 PPO 기반* 으로 발표됐지만 후속 작업은 *DPO 또는 IPO/KTO 등 변형* 으로 옮겨가는 추세.

## 8. 평가 — RewardBench, HH-RLHF, Toxicity Benchmarks

### Anthropic HH-RLHF (Helpful + Harmless)

- 인간 선호도로 평가: SL-CAI 모델 > base helpful 모델 (harmless 측)
- 단 helpful 점수는 약간 하락 (5~10%) — alignment tax

### RewardBench (Allen AI, 2024)

- Open-source RM 평가 벤치마크
- CAI 기반 reward model 이 *Chat* 카테고리에서 human-label RM 과 동등 수준
- *Reasoning* 카테고리는 human RM 이 여전히 우위

### Toxicity (RealToxicityPrompts)

- CAI 적용 후 *toxic continuation rate* 40~60% 감소
- 단 *helpful prompt 의 응답 길이* 도 평균 15% 감소 — 보수적 응답 경향

| 평가 | base | RLHF | RLAIF/CAI |
|---|---|---|---|
| HH-RLHF Helpful Elo | 1000 | 1180 | 1120 |
| HH-RLHF Harmless Elo | 1000 | 1090 | 1180 |
| RealToxicityPrompts (lower better) | 0.32 | 0.18 | 0.12 |
| MT-Bench | 7.1 | 8.4 | 8.2 |

## 9. 운영 함정과 fine-tuning 안정성

- *Mode collapse* — Self-critique loop 가 *동일한 응답 패턴* 으로 수렴. 다양한 prompt 분포로 critique data 를 만들어야 회피.
- *Sycophancy* — AI judge 가 *user 의 호감* 을 기준으로 평가하면 모델이 *동의 편향* 으로 학습. Constitution 에 명시적 anti-sycophancy 원칙 필요.
- *Reward hacking* — Reward model 의 exploit. Policy 가 *내용은 빈약하지만 reward model 이 좋아하는 형식* 으로 collapse. KL constraint 의 β 를 적절히 (보통 0.1~0.5) 유지.
- *Inversion / red-team* — Constitution 의 *원칙 자체를 회피* 하는 prompt 가 발견됨. 지속적인 red-teaming 으로 원칙 보강.
- *Over-refusal* — CAI 가 *helpful 요청도 거절* 하는 경향. *Helpful-only 모델로 시작* 하고 *Harmless 만 incremental 적용* 하는 게 안전.

## 10. 실전 적용 — 사내 LLM 의 CAI 도입 절차

조직 내 LLM 에 CAI 를 적용한다면:

1) **원칙 수집** — 회사의 가치, 법적 제약, 도메인 규제를 *명문화*. 10~30 개 원칙이 적절.
2) **Helpful-only base** — 기존 fine-tuned 모델로 출발. CAI 는 *추가 alignment*.
3) **Critique data 생성** — 1만~10만 prompt 에 대해 self-critique + revision. 며칠~수 주.
4) **SL-CAI** — Revision data 로 supervised fine-tune. 보통 LoRA 로 충분.
5) **평가** — 내부 prompt set 으로 helpful + harmless 측정. helpful 하락이 임계 이하면 다음 단계.
6) **RL-CAI** — DPO 또는 PPO. KL constraint 조심.
7) **Red-team + 원칙 보강** — 실패 케이스 발견 시 원칙에 반영, iterate.

비용 — 7B 모델 기준 SL-CAI 는 단일 A100 노드에서 수 시간, DPO 는 수 일. 70B 는 multi-node 학습 필요.

## 참고

- Bai et al. (2022) — Constitutional AI: Harmlessness from AI Feedback
- Lee et al. (2023) — RLAIF: Scaling Reinforcement Learning from Human Feedback with AI Feedback
- Rafailov et al. (2023) — Direct Preference Optimization
- Anthropic — Claude's Constitution (공개된 원칙 목록)
- Allen AI — RewardBench: Evaluating Reward Models
