Notion 원본: https://www.notion.so/36f5a06fd6d381249fcbc14a11fcaacd

# LLM Mixture of Experts MoE Routing과 Auxiliary Load Balance Loss

> 2026-05-29 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- Sparse MoE 의 top-k routing 과 gating network 의 forward pass 를 정확히 기술한다
- Capacity factor, expert dropping, token overflow 의 운영적 영향을 이해한다
- Switch Transformer · GShard · Expert Choice routing 의 책임 분리를 비교한다
- Auxiliary load balance loss 와 z-loss 가 학습 안정화에 기여하는 방식을 설명한다

## 1. Dense vs Sparse — 왜 MoE 인가

표준 Transformer 의 FFN(feed-forward network) 은 모든 토큰이 동일한 가중치 행렬을 통과한다. 모델 파라미터 수가 늘어나면 매 토큰 당 FLOPs 도 비례 증가한다. Mixture of Experts 는 FFN 자리를 N 개의 "expert" FFN 으로 대체하고, 각 토큰이 그중 k 개만 통과하도록 한다. 활성 파라미터 수는 N 배 늘어도 토큰당 계산은 k/N 만 증가한다. 이 비대칭이 MoE 가 GPT-4, Mixtral, DeepSeek-V2 같은 1T 급 모델의 표준이 된 이유다.

대가는 라우팅과 load balance 의 복잡성이다. expert 가 골고루 쓰여야 GPU 가용도가 균등하고 학습이 수렴한다. 한쪽 expert 에 토큰이 쓸리면 학습 초기에 collapse 가 일어나 일부 expert 가 영원히 dead 가 된다.

## 2. Top-k Routing 의 forward pass

```
logits = W_g x + noise        # W_g ∈ R^{N×d}
p = softmax(top_k(logits, k))  # top-k 외엔 -inf 로 mask
y = Σ_{i ∈ topk} p_i · Expert_i(x)
```

`top_k` 는 logits 상위 k 개만 softmax 에 넣고 나머지는 -inf 로 마스킹한다. Switch Transformer 는 k=1 으로 단순화한다. 라우팅 정확도가 낮아져 capacity factor 와 auxiliary loss 의 튜닝 부담이 늘어난다.

```python
class TopKGate(nn.Module):
    def __init__(self, d_model, n_experts, k=2):
        super().__init__()
        self.w_gate = nn.Linear(d_model, n_experts, bias=False)
        self.k = k

    def forward(self, x):  # x: [B, T, d_model]
        logits = self.w_gate(x)                            # [B, T, N]
        topk_val, topk_idx = logits.topk(self.k, dim=-1)   # [B, T, k]
        topk_softmax = topk_val.softmax(dim=-1)            # [B, T, k]
        return topk_idx, topk_softmax, logits
```

## 3. Capacity Factor 와 Expert Dropping

분산 학습에서 각 expert 는 단일 GPU 위에 있고, all-to-all 통신으로 토큰을 expert 호스트에 보낸다.

```
capacity = capacity_factor × (total_tokens × k / N)
```

`capacity_factor` 는 보통 1.0 ~ 1.5. 1.0 이면 토큰이 정확히 균등 분포일 때만 누락 없이 처리된다. 실제로는 분포가 불균등하므로 1.0 은 매번 drop 이 발생한다. drop 된 토큰은 expert 를 통과하지 않고 residual connection 으로 그대로 전달된다. drop 비율이 5% 이상이면 학습 loss 가 진동하기 시작한다.

## 4. Switch · GShard · Expert Choice 비교

| 방식 | k | 라우팅 주체 | 부하 분산 |
| --- | --- | --- | --- |
| Switch Transformer | 1 | Token-to-expert | aux loss + capacity factor |
| GShard | 2 | Token-to-expert | aux loss + 2nd choice random |
| Expert Choice | 자율 | Expert-to-token | 보장 by design |

GShard 는 top-2 routing 으로 첫 번째 expert 가 overflow 일 때 두 번째 expert 로 보낸다. Switch 는 1 개만 보내고 overflow 면 drop. Expert Choice 는 라우팅을 뒤집어 각 expert 가 자기 capacity 만큼 토큰을 "선택" 한다. 모든 expert 가 동일 수의 토큰을 받게 되어 load balance 가 보장되지만, 토큰 입장에서는 어떤 expert 도 자기를 선택하지 않을 수 있다(drop). production inference 에는 token-choice (Switch/GShard) 가 표준이다.

## 5. Auxiliary Load Balance Loss

학습 손실에 추가되는 보조 loss. Switch Transformer 의 정의는 다음과 같다.

```
f_i = (1/T) Σ_t 1[argmax routing(x_t) == i]
P_i = (1/T) Σ_t p_i(x_t)
L_aux = α · N · Σ_i f_i · P_i
```

`α` 는 보통 0.01. `L_aux` 가 작아지려면 `f_i` 와 `P_i` 둘 다 1/N 에 가까워야 하다.

```python
def aux_load_balance_loss(logits, topk_idx, n_experts, alpha=0.01):
    p = logits.softmax(-1)                          # [B*T, N]
    p_mean = p.mean(0)                              # [N]
    one_hot = torch.zeros_like(p).scatter_(1, topk_idx[:, :1], 1.0)
    f = one_hot.mean(0).detach()                    # [N]
    return alpha * n_experts * (f * p_mean).sum()
```

`f.detach()` 가 중요하다. 그렇지 않으면 argmax 의 indicator 를 통한 비합법 gradient 가 흐른다.

## 6. Router Z-Loss 와 logit drift 방지

Mixture of Experts 학습의 또 다른 불안정성은 라우팅 logits 의 절대값이 폭발하는 현상이다. ST-MoE 논문이 제안한 z-loss 가 이를 막는다.

```
L_z = β · (1/T) Σ_t (log Σ_i exp(logits_i(x_t)))^2
```

`logsumexp` 값을 0 근처로 누르는 정규화다. `β` 는 보통 1e-3. 총 loss 는 `L = L_task + L_aux + L_z` 형태로 합산한다. 세 weight 비율이 학습 안정의 90% 를 결정한다고 말해도 과하지 않다.

## 7. 통신 비용 — All-to-All Dispatch

MoE 의 진짜 비용은 FFN 연산이 아니라 토큰을 expert host 로 보내고 받는 두 번의 all-to-all 이다. expert parallelism size E, sequence length T, hidden d 라면 한 번의 dispatch 가 약 `T × d × 2byte / E` 의 데이터를 모든 노드에 동시에 보낸다. H100 NVLink 가 900GB/s 라고 해도 expert parallelism 이 늘면 latency 가 layer 별로 누적된다. Mixtral 8×7B 의 토큰당 latency 의 30% 가 all-to-all 이다.

## 8. 추론 시 운영 관점

추론에서는 학습 시와 다른 두 가지 문제가 있다. 첫째, batch 크기가 작으면 expert utilization 이 극단적으로 낮다. batch=1 일 때 토큰이 N 개 expert 에 분산되면 expert 당 1 토큰 미만이다. continuous batching(vLLM, TensorRT-LLM) 이 이를 완화한다. 둘째, expert 가중치가 메모리에 모두 올라가야 하므로 64-expert 모델은 dense 7B 보다 메모리가 8× 다. capacity factor 와 batch 크기를 학습과 다르게 두면 동일 weight 의 추론 결과가 달라진다.

## 참고

- Shazeer et al. 2017 — Outrageously Large Neural Networks
- Fedus et al. 2021 — Switch Transformers: Scaling to Trillion Parameter Models
- Lepikhin et al. 2020 — GShard
- Zoph et al. 2022 — ST-MoE
- Mixtral of Experts technical report — Mistral AI
