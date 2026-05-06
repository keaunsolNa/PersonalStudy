Notion 원본: https://www.notion.so/3585a06fd6d381a8a49ec2c6bfcd2c82

# Speculative Decoding과 Medusa Multi-token 추론 가속 아키텍처

> 2026-05-06 신규 주제 · 확장 대상: AI

## 학습 목표

- 자기회귀 LLM 추론의 메모리 대역폭 병목과 sequence-level 병렬화의 어려움을 정리한다
- Speculative Decoding 의 draft + verify 메커니즘과 수학적 등가성(distribution preservation)을 검증한다
- Medusa 의 multi-head 구조가 별도 draft 모델을 없앤 방식을 분석한다
- vLLM, TensorRT-LLM 의 실서비스 구현 차이와 처리량 / latency 실측 수치를 비교한다

## 1. 자기회귀 추론의 비용 모델

LLM 의 inference 는 token 을 한 번에 하나씩 만든다. 매 token 생성마다 (a) 모든 layer 를 한 번 forward pass 하고 (b) KV cache 에 새 key/value 를 append 한다. 7B 모델 기준 forward 한 번에 14GB(fp16) → 1024 토큰 생성에 14TB 의 weight memory bandwidth 가 소비된다. A100 80GB 의 HBM2e 대역폭이 ~2TB/s 이므로, 단순 산수로도 7초가 weight loading 만으로 든다.

핵심 문제는 **memory bound** 다. compute 는 충분한데 weight 를 매번 GPU memory 에서 다시 읽는 것이 병목이다. batch size 를 키우면 같은 weight read 비용으로 더 많은 토큰을 처리할 수 있어 throughput 은 올라간다. 그러나 단일 요청의 latency 는 줄지 않는다.

token-level parallelism 이 어려운 이유는 self-attention 의 인과 마스크. 다음 토큰은 이전 토큰에 의존한다. n+1 토큰을 만들려면 n 토큰까지의 KV 가 필요하고 그 자체가 n 단계를 거쳐야 한다.

## 2. Speculative Decoding — Draft + Verify 의 아이디어

해결 아이디어. **작은 draft 모델로 N 개 토큰을 미리 예측**한다. 그 N 개 토큰을 큰 target 모델에 한 번의 batch forward 로 넣어 검증한다. 검증 통과한 prefix 만 채택하고 나머지는 버린다.

```
draft model (1.3B) →  t_1, t_2, t_3, t_4, t_5  (속도: 매우 빠름)
                              ↓
target model (70B) →  forward([t_0, t_1, t_2, t_3, t_4, t_5])  (1번 호출)
                              ↓
                      각 위치의 logit 을 비교해 어디까지 채택할지 결정
                              ↓
                      채택된 prefix 길이 k 만큼 progress
```

draft 가 정확할수록 한 번에 채택되는 길이 k 가 길어져 가속비가 올라간다. 핵심은 두 가지.

(1) target 모델의 forward 는 prefix N+1 길이 한 번이면 끝난다. parallel forward.
(2) draft 의 토큰을 그냥 채택하면 분포가 달라진다. 검증 단계가 분포 동등성을 보장한다.

## 3. 수학적 등가성 — Modified Rejection Sampling

draft 모델 q, target 모델 p 라 하자. draft 가 token x 를 sample 한 뒤 target 의 분포 p 와 비교해 다음과 같이 채택한다.

```
acceptance_prob(x) = min(1, p(x) / q(x))
```

x 가 채택되면 그대로 진행. 거부되면 새로운 분포 `p'(x) = max(0, p(x) - q(x)) / Z` (Z 는 정규화 상수) 에서 다시 샘플링한다. 이 절차의 결과 분포가 p 와 정확히 같다는 것이 Leviathan & Kalman(2023) 의 핵심 정리.

**즉, draft 가 잘못 추측한 부분은 자동으로 버려지고 정확한 부분만 채택된다**. 결과 시퀀스의 확률 분포는 target 모델 단독 sampling 과 통계적으로 동일하다.

```python
def speculative_decode(prompt, draft, target, k=5):
    tokens = prompt[:]
    while len(tokens) < max_len:
        # 1) draft 가 k 개 추측
        draft_tokens, draft_probs = draft.generate(tokens, n=k)
        # 2) target 이 k+1 위치 모두 한 번에 forward
        target_logits = target.forward(tokens + draft_tokens)
        target_probs = softmax(target_logits[-(k+1):])
        # 3) 검증
        accepted = []
        for i, (t, qp) in enumerate(zip(draft_tokens, draft_probs)):
            tp = target_probs[i][t]
            r = random.random()
            if r < min(1.0, tp / qp):
                accepted.append(t)
            else:
                # 거부 → 수정 분포에서 새 토큰 sampling
                modified = np.maximum(target_probs[i] - draft_probs[i], 0)
                modified /= modified.sum()
                accepted.append(np.random.choice(len(modified), p=modified))
                break
        # 4) 모두 accept 된 경우 마지막에 bonus 토큰 1개
        if len(accepted) == k:
            bonus = np.random.choice(len(target_probs[-1]), p=target_probs[-1])
            accepted.append(bonus)
        tokens.extend(accepted)
    return tokens
```

전형적 acceptance rate 는 draft 가 target 의 ~1/10 크기일 때 60~80%. k=5 가속비는 약 2.5~3.0배 수준이 일반적이다.

## 4. Draft 모델 선택의 trade-off

좋은 draft 모델의 조건.

(a) **target 과 분포 유사**: KL(q || p) 가 작아야 acceptance rate 높음.
(b) **압도적 속도**: draft latency 가 target 의 1/10 이하여야 의미 있음.
(c) **같은 tokenizer**: 토큰 ID 공간 일치해야 분포 비교 가능.

흔한 선택:
- **distill 한 작은 모델**: target 의 logit 으로 KD 학습. 분포 유사도는 높지만 학습 비용 큼.
- **같은 패밀리의 작은 모델**: Llama-2-70B 의 draft 로 Llama-2-7B. Tokenizer 가 동일.
- **n-gram 통계 모델**: prefix 의 last n-gram 으로 다음 토큰을 lookup. 학습 불필요. Transformers Speculative Decoding 의 `prompt_lookup_num_tokens` 가 이 방식.

prompt-lookup 방식은 RAG 처럼 prefix 가 input prompt 와 큰 영역이 겹칠 때 매우 효과적이다. 이미 prompt 에 등장한 phrase 는 다시 등장할 가능성이 높다.

## 5. Medusa — Draft 모델 없는 Multi-head 가속

Medusa(Cai et al., 2024) 는 별도의 draft 모델을 학습/배포하는 부담을 없앤다. **target 모델의 마지막 hidden state 에 여러 개의 LM head 를 붙여**, 한 번의 forward 로 다음, 다다음, 다다다음 토큰을 동시에 예측한다.

```
hidden_state → head_0  → predict t_{n+1}
            → head_1  → predict t_{n+2}
            → head_2  → predict t_{n+3}
            → head_3  → predict t_{n+4}
```

base 모델의 weight 는 freeze. 추가 head 만 학습한다. 학습 비용이 작고(수시간 ~ 수일, single GPU 가능), 추론 시 메모리 오버헤드도 head 추가분(<1GB)에 그친다.

각 head 는 top-k(예: k=5) 후보를 낸다. 그러면 k×k×k×k = 625 가지 sequence 가 가능. 모두 검증할 수는 없으므로 **tree attention** 구조로 일부만 verify 한다. tree attention 은 attention mask 를 트리 모양으로 그려 한 번의 forward 에서 여러 분기를 동시에 평가한다.

```
                  t_n
                /  |  \
             t_a  t_b  t_c    (head 0 의 top-3)
            / |    |    | \
          ...  ...  ... ...   (head 1 의 top-2 each)
```

verify 후 가장 긴 prefix 를 채택. Medusa-1 은 draft acceptance distribution 보존이 약간 깨지지만 실측 quality drop 은 무시할 수준. Medusa-2 는 base + heads 를 함께 fine-tune 해 더 높은 acceptance rate 를 노린다.

벤치마크: Vicuna-7B + Medusa heads, A100 단일 → 2.3~2.8배 가속(MT-bench prompt 기준).

## 6. vLLM, TensorRT-LLM 에서의 구현

### 6.1 vLLM

vLLM 0.4+ 부터 speculative decoding 정식 지원. PagedAttention 의 KV cache 위에 layered. 설정:

```python
from vllm import LLM, SamplingParams

llm = LLM(
    model="meta-llama/Llama-2-70b-chat-hf",
    speculative_model="meta-llama/Llama-2-7b-chat-hf",
    num_speculative_tokens=5,
    use_v2_block_manager=True,
)

outputs = llm.generate(
    "Explain transformer attention.",
    SamplingParams(temperature=0.7, max_tokens=512),
)
```

`num_speculative_tokens` 가 k. acceptance 통계는 metric 으로 노출(`vllm:spec_decode_efficiency`). PagedAttention 의 block 단위 KV 관리가 spec decoding 과 잘 맞는다.

### 6.2 TensorRT-LLM

NVIDIA TRT-LLM 은 Medusa 와 prompt-lookup 둘 다 지원. C++ kernel 과 graph fusion 으로 vLLM 보다 단일 latency 가 짧지만 build-once-deploy-many 워크플로우 라 모델 swap 이 무겁다.

```
# TRT-LLM Medusa build
trtllm-build --model_dir ./llama2-7b-medusa \
             --max_input_len 2048 \
             --max_output_len 512 \
             --medusa_choices "[[0],[1],[0,0],[0,1],[1,0]]" \
             --output_dir ./engines/llama2-medusa
```

`medusa_choices` 는 tree attention 의 가지 패턴을 명시한다. 어느 head 의 어떤 top-k 까지 verify 할지 미리 결정한다.

## 7. 처리량 vs latency — batch size 의 영향

speculative decoding 은 단일 sequence latency 를 줄이는 기법이다. 그러나 batch size 가 커지면 효용이 줄어든다.

벤치 환경: Llama-2-70B target, Llama-2-7B draft, A100 x 4, fp16

| batch size | naive token/s | spec decode token/s | 가속비 |
|---|---|---|---|
| 1 | 23 | 67 | 2.91× |
| 4 | 78 | 165 | 2.12× |
| 16 | 240 | 380 | 1.58× |
| 64 | 720 | 880 | 1.22× |
| 256 | 1900 | 2050 | 1.08× |

batch 가 커지면 target forward 가 이미 batch parallelism 으로 weight 를 잘 활용한다. 추가 draft compute 가 오히려 cost 가 된다. 따라서 spec decoding 은:
- 단일 사용자 chatbot, IDE assistant: 효과 큼
- 대량 batch 추론(평가, 데이터 합성): 효과 적음
- 멀티 사용자 inference server with continuous batching: 중간

vLLM 의 continuous batching 은 새 요청이 들어올 때 idle GPU 슬롯에 합류시킨다. spec decoding 은 batch 내 sequence 별로 accept 길이가 달라 padding/uneven progress 처리가 까다롭다. 이 부분이 v0.4 이후 꾸준히 개선되는 영역이다.

## 8. Lookahead Decoding — 새로운 흐름

LADE(Lookahead Decoding, He et al. 2023+) 는 또 다른 draft 없는 가속 기법. Jacobi iteration 을 사용해 future token 들의 fixed-point 를 동시 추정한다.

핵심 아이디어:

(1) prefix `t_1..t_n` 이 주어졌을 때, 미래 토큰들 `t_{n+1}, t_{n+2}, ...` 의 초기 추측을 random 또는 prompt 에서 lookup
(2) target 모델로 한 번의 forward 에 모든 위치를 평가, 각 위치에서 출력이 입력과 일치하는 부분이 fixed-point
(3) fixed-point 토큰들이 채택. 나머지는 다음 iteration 에서 갱신

복잡도는 spec decoding 과 비슷하지만, draft 모델이 필요 없고 prompt 일치 영역에서 강하다. vLLM 0.5+ 에서 실험 지원.

## 9. 운영 체크리스트

배포 전 검증 항목.

**acceptance rate 모니터링**. 60% 이하면 draft 모델 quality 가 부족하거나 prompt 도메인이 학습 분포와 너무 다르다는 신호. domain-specific draft fine-tune 이 필요.

**latency p50 / p99 동시 측정**. 평균 가속비가 좋아도 p99 가 악화되는 경우가 있다. accept rate 가 낮은 prompt 에서 draft 비용만 추가된다.

**메모리 footprint**. draft 모델 + target 모델 + KV cache(둘 다) 가 GPU 메모리에 동시에 올라가야 한다. 70B + 7B + KV cache 는 A100 80GB 4장 기준 빠듯. tensor parallel 분할 시 둘의 partition 전략을 일치시킴.

**deterministic mode**. spec decoding 은 random number 사용으로 같은 prompt + 같은 seed 라도 비결정적일 수 있다. 평가 reproducibility 가 필요한 환경에서는 acceptance check 의 RNG 를 명시 seed 로 고정.

**draft / target 분기**. continuous deployment 환경에서 target 만 업그레이드하고 draft 가 stale 하면 acceptance rate 가 급락한다. 두 모델의 deploy lifecycle 을 묶어 관리한다.

## 참고

- Leviathan, Kalman, Matias — "Fast Inference from Transformers via Speculative Decoding" (2023)
- Cai, Chen, Liu et al. — "Medusa: Simple LLM Inference Acceleration Framework" (2024)
- vLLM Docs — Speculative Decoding: https://docs.vllm.ai/en/latest/features/spec_decode.html
- NVIDIA TRT-LLM Docs — Speculative & Medusa: https://nvidia.github.io/TensorRT-LLM/
- He et al. — Lookahead Decoding: https://lmsys.org/blog/2023-11-21-lookahead-decoding/
