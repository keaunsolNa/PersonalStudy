Notion 원본: https://www.notion.so/35a5a06fd6d381b9ae59c5ed8e1eb0ff

# Mixture of Experts MoE Router Gating과 Switch Transformer Expert Parallelism

> 2026-05-08 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- Mixture of Experts(MoE) 의 sparse activation 원리와 dense Transformer 대비 연산량 절감 방식을 이해한다
- Top-k gating, noisy routing, load balancing loss 같은 router 설계 요소를 식별한다
- Switch Transformer 의 single-expert routing 단순화가 만든 trade-off 와 expert parallelism 통신 패턴을 안다
- 추론 시 batch 내 token 분포 불균형(stragglers) 와 expert capacity 의 영향, 운영 튜닝 포인트를 정리한다

## 1. MoE 의 등장 배경

dense Transformer 는 모든 token 이 모든 parameter 를 거친다. 모델이 커질수록 연산량과 메모리는 정비례로 증가한다. MoE 는 token 마다 일부 expert 만 활성화한다.

실제 학습된 LLM(예: Mixtral 8x7B, Switch-C, GShard) 모두 dense 동급 대비 학습 비용 30~70% 절감을 보고했다.

## 2. MoE Layer 구조

Transformer 의 FeedForward(FFN) 블록을 다수의 expert(작은 FFN) 와 router 로 대체한다.

```python
class MoELayer(torch.nn.Module):
    def __init__(self, d_model, d_ff, num_experts):
        super().__init__()
        self.router = torch.nn.Linear(d_model, num_experts, bias=False)
        self.experts = torch.nn.ModuleList(
            [torch.nn.Sequential(
                torch.nn.Linear(d_model, d_ff),
                torch.nn.GELU(),
                torch.nn.Linear(d_ff, d_model),
            ) for _ in range(num_experts)]
        )

    def forward(self, x):
        logits = self.router(x)
        probs = F.softmax(logits, dim=-1)
        top1 = probs.argmax(dim=-1)
        weight = probs.gather(-1, top1.unsqueeze(-1)).squeeze(-1)
        out = torch.zeros_like(x)
        for e, expert in enumerate(self.experts):
            mask = (top1 == e)
            if mask.any():
                out[mask] = expert(x[mask]) * weight[mask].unsqueeze(-1)
        return out
```

## 3. Top-k Gating 과 Noisy Top-k

GShard, Mixtral 등은 top-1 이 아니라 top-2 routing 을 쓴다. token 마다 두 expert 를 활성화하고 각 weight 로 합산한다.

```
noisy_logits = W_g(x) + noise * softplus(W_noise(x))
gate = top_k(noisy_logits, k)
```

## 4. Load Balancing Loss

```
L_aux = α * E * Σ_i (f_i * P_i)
```

- `f_i` = `(expert i 로 라우팅된 token 수) / (전체 token 수)`
- `P_i` = `(전체 token 의 router 가 expert i 에 부여한 평균 prob)`
- `α` = scaling(보통 0.01)

`f_i * P_i` 가 모든 expert 에 균등하게 분배될 때 합이 최소화된다.

## 5. Switch Transformer — Top-1 routing 의 단순화

Google 의 Switch Transformer(2021) 는 top-1 only 로 단순화하면서 expert capacity factor 를 도입했다.

```
expert_capacity = capacity_factor * (B * T) / E
```

`capacity_factor = 1.25` 는 학습에서 흔히 쓰이는 값이다.

## 6. Expert Parallelism

E 개 expert 를 N 개 GPU 에 나눠 두면 token 라우팅 시 GPU 간 데이터 이동이 필요하다. 이 통신 패턴이 **all-to-all** 이다.

| 단계 | 통신 |
|---|---|
| dispatch (token → expert GPU) | all-to-all |
| local expert compute | GPU 내 |
| combine (expert 결과 → 원래 GPU) | all-to-all (역방향) |

NVLink·Infiniband 같은 고대역 네트워크 없이는 efficient 학습이 어렵다.

## 7. 추론 시 운영 이슈

- **Stragglers**: 한 expert 에 token 이 몰리면 그 GPU 만 늦어 전체 latency 가 그 GPU 시간으로 결정된다
- **Capacity 결정**: inference 시 cf = 2.0~4.0 로 늘려 dropping 을 0으로
- **KV cache 와 expert mismatch**: dispatch/combine 과 attention 의 partitioning 을 일관되게 설계

vLLM, DeepSpeed-MII, TensorRT-LLM 모두 MoE 추론 최적화를 지원한다.

## 8. 모델별 비교

| 모델 | 활성 / 전체 parameter | top-k | expert 개수 |
|---|---|---|---|
| Switch Transformer-C | 380M / 1.6T | 1 | 2048 |
| GShard | 5B / 600B | 2 | 2048 |
| GLaM | 8B / 1.2T | 2 | 64 |
| Mixtral 8x7B | 13B / 47B | 2 | 8 (per layer) |
| DeepSeek-MoE-V2 | 21B / 236B | 2 (+ shared) | 160 |

Mixtral 처럼 expert 가 적은 모델은 single-node 추론에 유리하다. expert 가 많은(2048개) Switch 류는 운영 인프라가 필수다.

## 참고

- Shazeer et al., 2017, "Outrageously Large Neural Networks: The Sparsely-Gated Mixture-of-Experts Layer"
- Lepikhin et al., 2020, "GShard"
- Fedus et al., 2021, "Switch Transformer"
- Mixtral of Experts (Mistral AI, 2023) 기술 보고서
- DeepSpeed MoE 문서 — Microsoft Research
