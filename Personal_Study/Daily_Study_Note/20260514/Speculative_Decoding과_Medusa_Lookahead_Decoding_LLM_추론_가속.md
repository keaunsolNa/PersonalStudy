Notion 원본: https://www.notion.so/3605a06fd6d3816eb3ddf2d304ab9512

# Speculative Decoding과 Medusa · Lookahead Decoding — Draft+Verify로 LLM 추론을 2~3배 빠르게

> 2026-05-14 신규 주제 · 확장 대상: AI

## 학습 목표

- autoregressive decoding 의 *step 당 1 토큰* 한계와 GPU memory bandwidth bound 이유 분석
- Speculative Decoding 의 *Draft + Verify* 메커니즘과 *수학적 무손실* 보장 증명 스케치
- Medusa, EAGLE, Lookahead Decoding 의 draft 생성 방식 차이와 hardware 효율 비교
- 실제 서빙 (vLLM, TensorRT-LLM, SGLang) 에서 spec decoding 활성화 시 latency/throughput 영향

## 1. 왜 LLM 추론이 느린가 — memory bandwidth bound

LLM inference 의 한 토큰 생성은 *모델 전체를 GPU memory 에서 한 번 읽어야* 한다. 70B 모델, FP16 이면 ~140GB 의 weight 를 매 step 읽는다. A100 80GB HBM2e 의 memory bandwidth 는 2 TB/s. 즉 한 토큰당 *최소* 140 / 2000 = 70ms 가 든다 (실제로는 KV cache 까지 합쳐 더 길다).

핵심 통찰: *Compute 는 한참 남는데 memory 가 부족하다*. matmul FLOPS 는 A100 312 TFLOPS, 한 토큰 forward 가 약 140 GFLOPs 면 0.45ms 면 된다. 즉 *155배* 의 compute 가 놀고 있다. 한 step 에 1 토큰만 쓰는 게 비효율의 근원.

해결 아이디어: *한 step 에 여러 토큰 후보를 동시에 검증* 하면 메모리 액세스는 한 번이고 compute 만 늘어난다. Compute 가 남아도니 free.

## 2. Speculative Decoding 의 기본 — Draft + Verify

Leviathan et al. (Google, 2023) 의 *Fast Inference from Transformers via Speculative Decoding* 이 그 발판이다.

- *Draft model* M_q (작은 모델): K 개 토큰을 빠르게 생성. 후보 시퀀스 c_1...c_K
- *Target model* M_p (큰 모델): 위 후보 시퀀스를 *병렬로 한 번에* 검증 (parallel prefill 처럼)
- 검증 결과 *수락한 토큰* 까지만 출력, 거절된 위치는 target 모델의 분포에서 1 토큰 샘플링

핵심 보장: *speculative decoding 의 출력 분포는 target 모델 단독 decoding 분포와 정확히 같다.* 즉 *무손실* — quality 저하 없이 속도만 늘린다.

수락 확률 (acceptance rate) α 는 draft 와 target 의 KL divergence 와 관련된다. draft 가 target 을 잘 흉내내면 α 가 높고, 평균 토큰 처리량은 (1 + α + α² + ... + α^K) / (1 + draft cost / target cost).

전형적 수치 (A100, Llama-2-70B target + Llama-2-7B draft):
- α ≈ 0.7~0.8
- K = 4~6
- 처리량 향상 약 2~3배

## 3. 수락 규칙 — rejection sampling 으로 증명

draft 토큰 c 가 위치 t 에서 target 모델 분포 p, draft 분포 q 를 가질 때 수락 확률은:

```
P(accept c) = min(1, p(c) / q(c))
```

거절 시에는 *조정된 분포* p_residual(x) = max(0, p(x) - q(x)) / Z 에서 새 토큰을 샘플링. 이 두 단계가 합치면 *전체 출력 분포가 정확히 p* 가 된다는 게 *speculative sampling* 의 핵심 정리. 표준 rejection sampling 의 일반화다.

직관: q(c) > p(c) 이면 (draft 가 과대 예측) 일부 확률로 거절. q(c) ≤ p(c) 면 무조건 수락 (draft 가 target 보다 보수적). 한 step 에 K 개 후보 중 *연속해서 수락된 prefix* 만 출력하므로, 첫 거절 위치까지가 한 step 의 진행 길이.

## 4. greedy decoding 의 단순화

`temperature=0` greedy 라면 위 규칙이 *훨씬 단순* 해진다. 각 위치에서 draft 의 argmax 가 target 의 argmax 와 같으면 수락, 다르면 거절 후 target argmax 로 1 토큰 채택. 즉 *exact match* 검증. 코딩 어시스턴트(GitHub Copilot 같은) 처럼 deterministic 출력에선 이 단순 룰만으로 충분.

## 5. Draft Model 의 선택

옵션 1: *작은 사전학습 모델* (예: 70B target + 7B draft). 가장 직관적. 단점: draft 도 별도 forward 가 필요하고 KV cache 도 따로 가져야 함.

옵션 2: *self-speculative*. 큰 모델의 *얕은 layer 만* 따로 추론해 draft 로 사용. 메모리 중복 없음. 정확도는 떨어지므로 α 가 낮아짐.

옵션 3: *Medusa heads*. 큰 모델 위에 *추가 헤드* 를 학습시켜 한 forward 에서 *여러 미래 토큰* 을 동시에 예측. draft 모델 자체가 필요 없다.

옵션 4: *N-gram retrieval*. context 에서 *같은 prefix* 를 검색해 그 다음 토큰들을 draft 로 제안. 코드 자동완성처럼 *반복적 패턴* 이 많은 도메인에 강함.

## 6. Medusa — Multi-Head Speculative Decoding

Cai et al. (Princeton 2024) 의 *Medusa* 는 base 모델의 마지막 hidden state 위에 *K 개의 가벼운 head* 를 얹어 *각 head 가 +i 번째 미래 토큰을 예측* 하도록 학습한다.

```
hidden ──┬── LM head ──→ next token (위치 +1)
         ├── Medusa head 1 ──→ token (위치 +2)
         ├── Medusa head 2 ──→ token (위치 +3)
         └── Medusa head 3 ──→ token (위치 +4)
```

각 head 는 base 모델의 weight 를 *공유* 한다. head 한 개는 보통 *1~2 transformer block + linear* 정도라 추가 비용이 작다.

각 head 가 top-K 토큰을 제안하면 *Medusa tree* (각 head 마다 K 개 = K^M 후보) 가 만들어진다. 그 트리를 한 번의 forward 로 verify. 수락된 가장 긴 path 가 그 step 의 출력.

장점: draft 모델 없음 (KV cache 도 추가 없음), 학습 비용도 적음 (head 만 fine-tune). 단점: head 출력은 base 모델보다 부정확 → α 낮음 (~0.5). 하지만 base 모델 forward 한 번에 *기댓값 ~2.3 토큰* 을 얻으므로 여전히 1.5~2배 가속.

## 7. EAGLE — Extrapolation Algorithm for Greater Language-model Efficiency

Li et al. (2024) 의 EAGLE 은 Medusa 를 개선해 draft 모델을 *target 의 hidden state 자체* 를 입력으로 받는 *작은 transformer* 로 만든다. lm_head 가 아니라 *hidden state stream* 을 ingest 하므로 정보 손실이 적어 α 가 ~0.8 로 올라간다. EAGLE-2 는 *동적 draft tree* (현재 context 의 confidence 에 따라 draft 길이 가변) 로 더욱 가속.

## 8. Lookahead Decoding — draft 모델 없이 *동일 모델* 로

Fu et al. (UCSD 2024) 의 Lookahead Decoding 은 draft 도 별도 head 도 필요 없다. 모델의 한 step 에 *과거 출력* 을 입력으로 넣어 *N-gram 후보* 를 만들어두고, 같은 forward 에서 *그 후보들을 verify* 하는 트릭. *Jacobi iteration* 의 LLM 적용 — 같은 모델 자체를 fixed-point iteration 으로 풀어 *parallel prefill* 처럼 활용.

장점: 학습 불필요, 외부 draft 모델 불필요. 임의의 LLM 에 *추론 시점 옵션* 으로 켤 수 있다. 단점: 효과는 Medusa/EAGLE 보다 작다 (1.5배 안팎). 학습 자원이 부족한 환경에서 매력적.

## 9. 운영 — vLLM / TensorRT-LLM 에서 켜기

vLLM 의 spec decoding 활성화 (0.7+):

```python
from vllm import LLM, SamplingParams

llm = LLM(
    model="meta-llama/Llama-3-70B-Instruct",
    speculative_model="meta-llama/Llama-3-8B-Instruct",
    num_speculative_tokens=5,                  # K
    speculative_decoding_strategy="vanilla",   # 또는 medusa, eagle, ngram
)

outputs = llm.generate(prompts, SamplingParams(temperature=0.7, top_p=0.95))
```

`num_speculative_tokens` 를 키우면 한 step 당 잠재 가속은 늘지만 *거절 시 낭비도 늘어난다*. 도메인별 sweet spot 은 K=4~7.

TensorRT-LLM 은 `gptManager` 에 `--spec_dec_mode` 플래그. SGLang 은 `--speculative-num-steps` 로 제어. 모두 *batched continuous batching* 과 결합 가능.

## 10. 실측 — 어디서 이득이 작은가

| 조건 | 가속 | 이유 |
|---|---|---|
| 단일 사용자 long context | 2~3배 | bandwidth bound 최대, draft 정확 |
| 다중 사용자 batched | 1.2~1.5배 | 이미 compute bound 라 여유 없음 |
| Code generation (deterministic) | 2.5~4배 | draft α 매우 높음 |
| 창의적 글쓰기 (high temperature) | 1.3~1.8배 | draft 와 target 분포 차이 큼 |
| 입력 prompt 가 매우 길 때 | 효과 감소 | prefill 이 step 시간을 지배 |

Spec decoding 은 *single-request* (interactive chatbot) 시나리오에서 가장 빛난다. throughput-oriented batch serving 에서는 *이미 GPU 가 compute bound* 라 spec decoding 의 여유 compute 활용 트릭이 무력화된다 — 그래서 vLLM 도 batch 가 클 때는 spec decoding 을 자동 off 한다.

## 11. 함정

draft drift: long context 에서 draft 모델이 점차 target 과 다른 분포로 흐른다. α 가 시간에 따라 감소 → step 당 평균 수락 토큰 수 감소. self-speculative (Medusa) 가 *base hidden state 를 공유* 하므로 더 안정적.

memory pressure: 외부 draft 모델은 별도 KV cache 가 필요. 70B + 7B 면 KV cache 가 거의 1.1배. 메모리가 빠듯하면 batch size 를 줄여야 함.

bench mark 함정: 단일 prompt latency 만 보면 항상 빨라 보이지만 *throughput 환경* 에선 효과가 줄어든다. SLA 가 *p50/p99 latency* 인지 *tokens/sec/GPU* 인지에 따라 도입 가치 다름.

tokenizer 일치: draft 와 target 의 tokenizer 가 *완전히 동일* 해야 한다. mixed vocab 은 verify 단계에서 정렬이 깨진다. 같은 family 의 작은 모델을 draft 로 쓰는 게 표준.

학습 데이터 누출: Medusa head 학습 시 *답안의 미래 토큰* 을 함께 학습하므로 *현재 toggle 된 sampling 분포* 를 약간 왜곡할 위험. distillation 형 학습이 권장됨.

## 12. 정리 — 어떤 기법을 언제

| 상황 | 추천 |
|---|---|
| open-source 70B 서빙, 학습 자원 없음 | vLLM + small draft model (7B) |
| 학습 자원 있음, base 모델 hidden state 접근 가능 | Medusa or EAGLE |
| 코드 생성 / repetitive context | n-gram lookup + spec decode |
| 학습 불가, draft 모델 없음 | Lookahead Decoding |
| 매우 큰 batch throughput 서빙 | spec decoding 비활성, batching 으로 충분 |

근본 통찰: *LLM inference 는 memory bandwidth 가 병목이고, spec decoding 은 그 병목 한 step 에 여러 토큰의 가치를 짜내는 일관된 프레임워크다.* Medusa, EAGLE, Lookahead 는 *draft 제공 방식만 다른 같은 가족*. 무손실이라는 점이 결정적 — quantization 처럼 quality 손실 트레이드오프가 없다.

## 참고

- Leviathan, Kalman, Matias, *Fast Inference from Transformers via Speculative Decoding* (ICML 2023, arXiv:2211.17192)
- Cai et al., *Medusa: Simple LLM Inference Acceleration Framework with Multiple Decoding Heads* (2024, arXiv:2401.10774)
- Li et al., *EAGLE: Speculative Sampling Requires Rethinking Feature Uncertainty* (2024, arXiv:2401.15077)
- Fu et al., *Break the Sequential Dependency of LLM Inference Using Lookahead Decoding* (2024)
- vLLM Speculative Decoding 문서: https://docs.vllm.ai/en/latest/serving/spec_decode.html
- NVIDIA TensorRT-LLM Speculative Decoding: https://github.com/NVIDIA/TensorRT-LLM
