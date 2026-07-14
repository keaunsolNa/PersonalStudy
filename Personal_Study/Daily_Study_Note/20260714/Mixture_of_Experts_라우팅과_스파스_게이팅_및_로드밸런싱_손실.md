Notion 원본: https://app.notion.com/p/39d5a06fd6d381c1a763c5d91b5a1161

# Mixture of Experts 라우팅과 스파스 게이팅 및 로드밸런싱 손실

> 2026-07-14 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- 스파스 MoE가 파라미터 수와 연산량(FLOPs)을 분리하는 원리를 이해한다
- 게이팅 네트워크의 Top-k 라우팅과 소프트맥스 가중 결합을 코드로 재현한다
- 전문가 불균형을 막는 로드 밸런싱 보조 손실과 용량 계수를 파악한다
- MoE 학습·서빙의 실무 트레이드오프(통신, 메모리, 라우팅 불안정)를 구분한다

## 1. MoE가 푸는 문제: 파라미터와 연산의 분리

밀집(dense) 트랜스포머는 모든 토큰이 모든 파라미터를 통과한다. MoE는 FFN 레이어를 여러 전문가로 복제하고 각 토큰을 그중 소수(1~2개)에게만 라우팅한다. 파라미터 총량은 전문가 수만큼 늘지만 토큰당 연산은 활성화된 전문가 몇 개분뿐이다. "총 파라미터는 수천억인데 토큰당 활성 파라미터는 수백억"인 모델이 가능해진다. Switch Transformer, GShard 등이 이 구조를 채택했다.

## 2. 게이팅 네트워크와 Top-k 라우팅

토큰 벡터에 라우터 가중치를 곱해 전문가별 점수를 내고 소프트맥스로 확률화한 뒤 상위 k개를 고른다.

```python
import torch, torch.nn.functional as F

def moe_forward(x, router_w, experts, k=2):
    logits = x @ router_w                       # (tokens, n_experts)
    probs = F.softmax(logits, dim=-1)
    topk_val, topk_idx = probs.topk(k, dim=-1)
    topk_val = topk_val / topk_val.sum(-1, keepdim=True)
    out = torch.zeros_like(x)
    for slot in range(k):
        idx = topk_idx[:, slot]
        gate = topk_val[:, slot].unsqueeze(-1)
        for e in range(len(experts)):
            mask = idx == e
            if mask.any():
                out[mask] += gate[mask] * experts[e](x[mask])
    return out
```

k=1은 Switch Transformer, k=2는 GShard 방식이다. 게이트 확률을 출력 가중치로 쓰므로 라우터가 미분 가능해진다.

## 3. 라우팅의 이산성과 그래디언트 문제

Top-k 선택은 미분 불가능하다. MoE는 선택된 전문가의 게이트 확률을 출력에 곱함으로써 라우터를 학습시킨다. 이 간접 학습 때문에 라우팅은 초반 불안정하고 특정 전문가로 토큰이 쓸리는 자기강화 현상이 생긴다.

## 4. 전문가 붕괴와 로드 밸런싱

라우터를 방치하면 소수 전문가에만 토큰이 몰리는 붕괴가 발생한다. 표준 장치가 로드 밸런싱 보조 손실이다.

```python
def load_balancing_loss(probs, topk_idx, n_experts, alpha=0.01):
    counts = torch.bincount(topk_idx.reshape(-1), minlength=n_experts).float()
    f = counts / topk_idx.numel()      # 전문가별 라우팅 비율
    P = probs.mean(dim=0)              # 전문가별 평균 라우터 확률
    return alpha * n_experts * (f * P).sum()
```

f와 P가 모두 균등할 때 최소가 된다. alpha가 너무 크면 밸런싱이 성능을 해치고, 너무 작으면 붕괴를 못 막는다. noisy top-k gating도 초반 탐색을 도와 붕괴를 완화한다.

## 5. 용량 계수와 토큰 드롭

각 전문가는 고정 크기 버퍼로 병렬 처리되므로 용량 상한을 둔다.

```
expert_capacity = capacity_factor × (tokens_per_batch / n_experts)
```

capacity_factor가 1.0이면 완벽히 균등할 때만 딱 맞고 초과분 토큰은 버려진다(token dropping). 버려진 토큰은 잔차 연결로만 통과한다. 1.25~2.0으로 키우면 드롭이 줄지만 메모리·연산이 는다.

| 파라미터 | 올리면 | 내리면 |
|---|---|---|
| Top-k | 성능·안정성↑, 연산↑ | 저렴, 라우팅 불안정 |
| capacity_factor | 드롭↓, 메모리↑ | 메모리↓, 드롭↑ |
| aux loss alpha | 밸런싱↑, 성능 저해 위험 | 붕괴 위험 |
| 전문가 수 | 용량↑, 통신·메모리↑ | 단순, 표현력↓ |

## 6. 분산 학습과 통신 비용

전문가들은 여러 GPU에 분산(expert parallelism)된다. 토큰을 전문가 장치로 보내는 dispatch와 결과를 되돌리는 combine, 두 번의 all-to-all 통신이 레이어마다 발생해 MoE 학습의 주요 병목이다. 한 장치로 토큰이 몰리면 그 장치가 핫스팟이 되어 전체가 느려진다.

## 7. 서빙 관점의 트레이드오프

MoE는 추론 FLOPs가 활성 파라미터 기준이라 저렴하지만, 메모리는 전체 파라미터를 적재해야 해 크다. "연산은 싸고 메모리는 비싼" 프로파일이다. 배치 안 토큰이 다른 전문가로 흔어지면 GPU 활용률이 떨어져 전문가별 그룹핑·장치 고정·인기 전문가 복제 등이 쓰인다.

## 7.5. 공유 전문가와 세분화 전문가

최근 설계는 전문가를 더 잔게 쪼개고(fine-grained), 모든 토큰이 통과하는 공유 전문가(shared expert)를 둔다. 공유 전문가가 공통 지식을 맡아 라우팅 전문가는 특화에 집중한다.

```python
def moe_with_shared(x, router_w, routed_experts, shared_expert, k=2):
    routed_out = moe_forward(x, router_w, routed_experts, k)
    shared_out = shared_expert(x)   # 항상 통과
    return routed_out + shared_out
```

DeepSeek-MoE 계열이 이 구조를 대표한다. 세분화가 지나치면 all-to-all 통신 대상과 전문가별 미니배치가 작아져 GPU 활용률이 떨어진다.

## 8. 도입 판단

MoE는 "같은 추론 예산으로 더 큰 모델 품질"을 원하고 대규모 분산 학습 인프라와 큰 메모리를 감당할 수 있을 때 유효하다. 메모리가 제약이면 밀집 모델이 단순하고 안정적이다. 체크리스트: 로드 밸런싱으로 붕괴를 막았는가, 용량 계수와 드롭률을 모니터링하는가, all-to-all 통신이 병목이 되지 않도록 라우팅 균형과 전문가 배치를 조율했는가.

## 참고

- Shazeer et al. — Sparsely-Gated MoE: https://arxiv.org/abs/1701.06538
- Fedus et al. — Switch Transformers: https://arxiv.org/abs/2101.03961
- Lepikhin et al. — GShard: https://arxiv.org/abs/2006.16668
- Hugging Face Blog — Mixture of Experts Explained: https://huggingface.co/blog/moe
