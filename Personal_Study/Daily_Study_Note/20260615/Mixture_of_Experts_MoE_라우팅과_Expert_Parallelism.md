Notion 원본: https://www.notion.so/3805a06fd6d381f8a263f758f1f6167e

# Mixture of Experts(MoE) 라우팅과 Expert Parallelism

> 2026-06-15 신규 주제 · 확장 대상: AI Multi-Agent 서비스 실전프로젝트

## 학습 목표

- 밀집(dense) 모델 대비 MoE의 조건부 연산과 파라미터-연산 분리를 설명한다
- Top-k 게이팅 라우터의 softmax·가중합 계산과 라우팅 붕괴(routing collapse) 문제를 추적한다
- 보조 로드 밸런싱 손실과 expert capacity·token dropping의 trade-off를 적용한다
- Expert Parallelism의 all-to-all 통신 패턴과 분산 학습 비용을 분석한다

## 1. MoE가 푸는 문제: 파라미터와 연산의 분리

밀집 모델은 모든 토큰이 모든 파라미터를 통과해 파라미터를 늘리면 연산량도 그만큼 늘는다. MoE는 여러 expert를 두고 토큰마다 소수만 활성화해 전체 파라미터(용량)는 거대하지만 토큰당 연산은 작게 유지한다. Mixtral 8x7B는 47B 파라미터를 갖지만 토큰당 약 13B만 활성화한다.

## 2. MoE 레이어의 구조

MoE는 보통 FFN 레이어를 대체한다. N개의 작은 FFN(expert)과 하나의 라우터를 두고, 토큰이 들어오면 라우터가 점수를 매겨 상위 k개를 골라 가중합한다.

```python
class MoELayer(nn.Module):
    def forward(self, x):
        logits = self.router(x)
        weights, idx = torch.topk(logits, self.top_k)
        weights = F.softmax(weights, dim=-1)  # 선택된 k개에만 softmax
        out = torch.zeros_like(x)
        for slot in range(self.top_k):
            for e in range(self.num_experts):
                mask = idx[:, slot] == e
                if mask.any():
                    out[mask] += weights[mask, slot:slot+1] * self.experts[e](x[mask])
        return out
```

선택된 k개에만 softmax를 적용하는 것이 핵심이다. 전체에 하면 선택 안 된 expert 확률도 분모에 들어가 가중치가 왜곡된다.

## 3. 라우팅 붕괴 문제

학습 초기 몇몇 expert가 더 자주 선택되면 그 expert만 그래디언트를 받아 좋아지고, 좋아지니 더 자주 선택되는 양의 피드백 루프가 생겨 소수 expert만 일하고 나머지는 죽는다. 단순히 라우터를 학습에 맡기면 거의 항상 붕괴한다.

## 4. 보조 로드 밸런싱 손실

"expert로 라우팅된 토큰 비율(f_i)"과 "라우터 평균 확률(P_i)"의 곱을 합산해 한쪽으로 쓸릴수록 페널티를 준다.

```
L_aux = α · N · Σ_i (f_i · P_i)
```

```python
def load_balancing_loss(logits, idx, num_experts, alpha=0.01):
    probs = F.softmax(logits, dim=-1)
    P = probs.mean(dim=0)
    counts = torch.bincount(idx.flatten(), minlength=num_experts).float()
    f = counts / counts.sum()
    return alpha * num_experts * torch.sum(f * P)
```

`α`가 손잡이다. 너무 작으면 붕괴를 못 막고, 크면 품질이 떨어진다. DeepSeek-V3는 expert bias 동적 조정으로 보조 손실 없이 균형을 맞추는 auxiliary-loss-free 전략을 제안했다.

## 5. Expert Capacity와 Token Dropping

```
capacity = capacity_factor × (총 토큰 수 / expert 수)
```
capacity를 초과해 몰린 토큰은 처리되지 않고 버려져(token dropping) residual로 통과한다. capacity_factor를 키우면 드롭이 줄어 품질은 좋아지나 빈 버퍼 슬롯에도 자원을 할당해 효율이 떨어진다. 추론은 더 넓게, 학습은 throughput을 위해 제한하는 식으로 다르게 운용한다.

## 6. Expert Parallelism과 all-to-all 통신

expert들을 여러 디바이스에 분산하고, 라우터가 토큰을 담당 expert가 있는 GPU로 보낸다. 이 재분배가 두 번의 all-to-all(dispatch → expert 연산 → combine)로 구현된다. all-to-all은 모든 GPU가 모든 GPU와 통신하므로 비용이 크고, 토큰 분포가 불균형하면 가장 바쁜 GPU가 전체를 느리게 한다. 로드 밸런싱은 품질뿐 아니라 통신 효율 문제이기도 하다.

## 7. 다른 병렬화와의 결합

attention은 TP, MoE는 EP로 분할하는 하이브리드가 효율적이다. attention은 모든 토큰이 같은 가중치를 쓰는 밀집 연산이라 TP, MoE는 토큰별로 다른 expert를 쓰므로 EP가 자연스럽다. EP와 DP가 섞이면 그래디언트 동기화 범위가 expert마다 달라 구현 실수가 잦다.

## 8. 추론 특성과 trade-off

활성 파라미터가 적어 토큰당 연산은 작지만, 전체 파라미터를 모두 메모리에 올려야 해 VRAM은 밀집 모델 수준으로 크다. 배치 추론 시 토큰이 서로 다른 expert로 흔어져 특정 expert에 부하가 몰리면 지연이 들쎅날씁해진다. MoE는 "메모리는 충분하지만 연산 예산은 빠듯한" 상황에 적합하다. expert 활용도를 학습 내내 모니터링해 붕괴를 조기에 잡고, capacity_factor와 aux_loss_alpha를 품질-효율 곱선 위에서 함께 튜닝하는 것이 핵심이다.

## 참고

- Shazeer et al., "Outrageously Large Neural Networks: The Sparsely-Gated MoE Layer", ICLR 2017: https://arxiv.org/abs/1701.06538
- Fedus, Zoph, Shazeer, "Switch Transformers", JMLR 2022: https://arxiv.org/abs/2101.03961
- Lepikhin et al., "GShard": https://arxiv.org/abs/2006.16668
- DeepSeek-AI, "DeepSeek-V3 Technical Report": https://arxiv.org/abs/2412.19437
