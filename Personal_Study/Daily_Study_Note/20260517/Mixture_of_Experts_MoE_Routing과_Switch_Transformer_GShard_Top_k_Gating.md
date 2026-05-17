Notion 원본: https://www.notion.so/3635a06fd6d381a5ba5ce78a9def8a9c

# Mixture of Experts MoE Routing과 Switch Transformer GShard Top-k Gating

> 2026-05-17 신규 주제 · 확장 대상: AI

## 학습 목표

- Mixture of Experts(MoE) Transformer 가 Dense FFN 을 대체하는 구조적 동기와 sparse activation 이 만드는 FLOP/메모리 절감을 정량화한다
- Top-1(Switch), Top-2(GShard, Mixtral) gating 의 라우팅 알고리즘과 load balancing loss 의 수식적 의미를 추적한다
- Expert Parallelism 의 all-to-all communication 과 dropping / capacity factor 가 distributed training 처리량에 미치는 영향을 분석한다
- 추론 단계 expert offloading, EP+TP+PP 하이브리드 병렬, MoE 추론 가속 기법(MoE-Infinity, DeepSpeed-MoE)의 trade-off 를 비교한다

## 1. Dense FFN 의 한계와 MoE 의 동기

표준 Transformer 의 FFN 블록은 `d → 4d → d` linear 두 층으로, 모델 파라미터의 약 2/3 를 차지한다. 모델 크기를 키울 때 FFN 의 width 를 키우면 모든 토큰이 모든 파라미터를 사용. 7B 파라미터 모델은 토큰당 약 14 GFLOP.

MoE 의 아이디어는 FFN 을 *N 개의 expert FFN* 으로 분할하고, 각 토큰을 *그 중 k 개* 에만 라우팅. N=8, k=2 라면 *파라미터는 8배*, *연산은 2/8 = 1/4 만 사용*. 8 expert × 7B = 56B 파라미터 모델이 7B Dense 모델의 약 2배 연산만으로 동작 가능.

```
Dense FFN:    y = W2 · GeLU(W1 · x)
MoE FFN:      y = Σ_{i ∈ TopK(x)} g_i(x) · E_i(x)
```

`g_i(x)` 가 gating weight, `E_i(x)` 가 i 번째 expert FFN.

## 2. Switch Transformer — Top-1 Gating

Switch Transformer (Fedus et al. 2021) 는 *k=1*. 가장 단순한 라우팅이라 통신 비용/구현 복잡도가 낮다.

```python
logits = x @ W_router
expert_idx = logits.argmax(dim=-1)
gate_weight = softmax(logits)[expert_idx]
y = experts[expert_idx](x) * gate_weight
```

단점은 *expert 가 한 번 우세해지면 모든 토큰이 그쪽으로 쎠림* — load imbalance. 해결책: load balancing auxiliary loss.

```
L_aux = N · Σ_{i=1..N} f_i · P_i
```

`f_i` = 그 배치에서 expert i 로 라우팅된 토큰 비율, `P_i` = 그 배의 expert i 의 평균 gate probability. 이상적 분포에서 `L_aux = 1`. 총 loss 에 `α · L_aux` (α ≈ 0.01).

## 3. GShard, Mixtral — Top-2 Gating

GShard, Mixtral 8x7B 는 *k=2*. 두 expert 의 출력을 gate weight 로 가중합.

```python
logits = x @ W_router
top2_vals, top2_idx = logits.topk(2, dim=-1)
gates = softmax(top2_vals, dim=-1)
y = gates[..., 0:1] * experts[top2_idx[...,0]](x) \
  + gates[..., 1:2] * experts[top2_idx[...,1]](x)
```

Top-2 는 Gradient flow (두 번째 expert 가 적게나마 학습 신호 수신) 와 Capacity 분산 (한 expert 부하가 두 배로 분산) 효과. 비용은 통신/연산 2배.

## 4. Expert Capacity와 Token Dropping

분산 학습에서 각 expert 는 *고정 크기 buffer*. `capacity = (T · k / N) · C`. C=1.0 은 메모리 효율적이지만 dropping 10~30%. C=1.5 가 보통 좋은 trade-off. 라우팅 결과 capacity 초과 토큰은 drop (FFN 출력 = 0, residual 만 적용).

## 5. Expert Parallelism 의 All-to-All Communication

Tensor Parallel 은 layer weight 분할. Expert Parallel(EP) 은 expert 들을 GPU 들에 분할. 라우팅 결정 후 토큰을 해당 expert 의 GPU 로 보내고(A2A 1: dispatch), expert 출력 후 원래 GPU 로 되돌린다(A2A 2: combine). all-to-all 은 NVLink/InfiniBand 에서도 무거운 collective. MoE 의 학습/추론 처리량은 사실상 *all-to-all bandwidth* 로 결정. GShard와 DeepSpeed-MoE 의 최적화는 토큰 정렬, 압축, non-blocking overlap.

## 6. Mixtral 8x7B 의 실측 구조

Mixtral (Mistral AI):

| 항목 | 값 |
|---|---|
| 총 파라미터 | 46.7B |
| Active 파라미터 (per token) | 12.9B |
| Expert 수 | 8 |
| Top-k | 2 |
| Layer 수 | 32 |
| MoE layer 빈도 | 모든 FFN 이 MoE |
| Context length | 32K |

추론 시 토큰당 활성 파라미터 12.9B 로 13B Dense 와 비슷한 latency, 46.7B 의 표현력. 단점: 전체 weight 46.7B 를 메모리에 올려야 함.

## 7. Hybrid Parallelism (EP + TP + PP)

| 차원 | 분할 대상 | 통신 패턴 |
|---|---|---|
| Data Parallel | mini-batch | all-reduce (gradient) |
| Tensor Parallel | layer weight | all-reduce |
| Pipeline Parallel | layer 순서 | point-to-point |
| Expert Parallel | expert 집합 | all-to-all |

예: 64 GPU 에서 EP=8, TP=4, DP=2. DeepSpeed-MoE 와 NVIDIA Megatron-Core MoE 가 가장 활발한 오픈소스 구현.

## 8. 추론 가속 기법

**Expert Offloading** — RAM/Disk 에 weight, 필요 시 GPU 로 load. MoE-Infinity 가 LRU + prefetch. **Speculative Routing** — draft model 이 expert 예측, 실제 모델은 검증만. **Dropless MoE** — capacity 초과 동적 확장. **Quantization** — per-expert int8/int4. **Continuous Batching + MoE** — vLLM 과 결합 시 Mixtral 추론 throughput Dense 대비 1.5~2배.

## 9. MoE 의 한계와 미해결 과제

**Memory wall** — 전체 expert weight 가 메모리에 있어야 latency 가 합리적. 46.7B fp16 = 94GB. H100 80GB 한 장에 안 들어감. **Long context 와 충돌** — 32K context × 8 expert × bf16 = 2MB per layer per token. **Distillation 난이도** — MoE→Dense 손실 큼. Dense→MoE upcycling 은 잘 동작. **Robust load balancing** — Production traffic 도메인 편향과 학습 분포 차이. **Interpretability** — 각 expert 가 무엇을 학습했는지 분간 어려움. 학습 비용 측면에서 MoE 는 동일 active param Dense 대비 2~4배 효율적이지만 *총 메모리* 와 *통신 비용* 이 늘어 작은 클러스터(8~16 GPU)에서는 Dense 가 유리. MoE 의 sweet spot 은 *수백 GPU 이상*.

## 참고

- Switch Transformer — Fedus, Zoph, Shazeer (2021): https://arxiv.org/abs/2101.03961
- GShard — Lepikhin et al. (2020): https://arxiv.org/abs/2006.16668
- Mixtral of Experts — Jiang et al. (2024): https://arxiv.org/abs/2401.04088
- DeepSpeed-MoE: https://www.microsoft.com/en-us/research/blog/deepspeed-advancing-moe-inference-and-training-to-power-next-generation-ai-scale/
- MegaBlocks: https://arxiv.org/abs/2211.15841
- Sparse Upcycling — Komatsuzaki et al. (2022): https://arxiv.org/abs/2212.05055
- MoE-Infinity: https://github.com/EfficientMoE/MoE-Infinity
