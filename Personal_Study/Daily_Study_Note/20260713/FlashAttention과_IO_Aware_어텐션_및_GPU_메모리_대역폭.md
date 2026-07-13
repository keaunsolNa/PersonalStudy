Notion 원본: https://app.notion.com/p/39c5a06fd6d38166ae39d754d441c189

# FlashAttention과 IO-Aware 어텐션 및 GPU 메모리 대역폭

> 2026-07-13 신규 주제 · 확장 대상: AI

## 학습 목표

- 표준 어텐션이 왜 메모리 대역폭에 병목되는지 GPU 메모리 계층으로 설명한다
- FlashAttention의 타일링·온라인 softmax가 HBM 왕복을 없애는 원리를 분해한다
- 재계산(recomputation)으로 역전파 메모리를 O(N)으로 줄이는 트레이드오프를 이해한다
- FlashAttention-2/3의 개선점과 실무 처리량 향상 폭을 실측 기준으로 정리한다

## 1. 문제: 어텐션은 compute가 아니라 memory에 묶인다

어텐션은 `S = Q K^T`(N×N), `P = softmax(S)`(N×N), `O = P V`를 계산한다. 표준 구현은 중간 행렬 S와 P를 HBM에 통째로 쓰고 다시 읽는다. N=4096이면 N×N float16 행렬 하나가 32MB를 넘고 헤드·배치까지 곱하면 수 GB를 HBM에 왕복시킨다. A100의 FP16 연산은 약 312 TFLOP/s인데 HBM 대역폭은 약 1.5~2TB/s로, 연산 대비 메모리 이동이 훨씬 비싸다. 그래서 표준 어텐션은 코어가 놀면서 메모리를 기다리는 memory-bound 상태가 된다.

## 2. GPU 메모리 계층

| 계층 | 용량(A100) | 대역폭 | 특성 |
|---|---|---|---|
| SRAM (on-chip) | 합 ~20MB | ~19TB/s | 매우 빠르지만 작음 |
| HBM (GPU DRAM) | 40~80GB | ~1.5~2TB/s | 크지만 느림 |

SRAM은 HBM보다 약 10배 빠르지만 수십 MB뿐이다. 표준 어텐션은 N×N 행렬이 SRAM에 안 들어가 HBM에 저장·재로드한다. FlashAttention의 발상은 N×N 행렬을 아예 HBM에 만들지 않는 것이다. Q·K·V를 SRAM에 들어갈 작은 블록으로 쪼개 블록 단위로 계산하고 최종 O만 HBM에 쓴다.

## 3. 타일링과 온라인 softmax

softmax는 행 전체의 최댓값과 합이 필요해 보이지만, FlashAttention은 온라인 softmax(running max/sum 갱신)로 푼다. 블록을 하나씩 처리하며 누적 최댓값·지수합을 갱신하고, 새 최댓값이 나오면 이전 누적값을 보정 계수로 재스케일한다.

```python
m = -inf; l = 0; O = zeros(Br, d)
for j in range(num_kv_blocks):
    S_ij = Q_i @ K_j.T
    m_new = max(m, rowmax(S_ij))
    P_ij = exp(S_ij - m_new)
    alpha = exp(m - m_new)
    l = alpha * l + rowsum(P_ij)
    O = alpha * O + P_ij @ V_j
    m = m_new
O = O / l
```

이 방식은 근사가 아니라 수학적으로 표준 softmax와 완전히 동일한 결과를 낸다. 블록 순회 순서와 무관하게 최종값이 같다. 중간 N×N 행렬을 HBM에 한 번도 안 만들면서 정확한 어텐션을 얻는다.

## 4. IO 복잡도: 무엇이 줄었나

FlashAttention의 기여는 FLOP 감소가 아니다. 연산량은 표준과 같은 O(N²d)다. 줄어든 것은 HBM 접근량이다. 표준은 O(N² + Nd), FlashAttention은 O(N²d²/M)(M은 SRAM 크기)로 N×N 행렬의 HBM write/read가 통째로 사라진다. memory-bound 커널에서는 I/O가 벽시계 시간을 지배하므로 FLOP이 같아도 실제 속도가 크게 오른다. 원 논문 기준 GPT-2 학습에서 표준 대비 약 2~4배 가속을 보고했고, 메모리도 O(N²)에서 O(N)으로 줄어 같은 하드웨어로 긴 컨텍스트를 다룬다.

## 5. 역전파와 재계산

보통 역전파는 순전파 중간값(P 행렬)을 저장하는데 FlashAttention은 P를 저장하지 않았다. 해결책은 재계산이다. 역전파 시 저장해 둔 통계값(각 행의 m과 l)만으로 P 블록을 필요할 때 다시 계산한다. 추가 FLOP을 쓰지만 어차피 memory-bound라 벽시계 시간에 거의 영향이 없고, 역전파 메모리가 O(N²)에서 O(N)으로 줄어드는 이득이 크다. 연산은 남고 메모리는 귀하다는 GPU 특성을 정확히 활용한 트레이드오프다.

## 6. FlashAttention-2의 개선

FA-2는 세 가지를 개선했다. 첫째 비행렬곱 연산을 줄여 Tensor Core 활용률을 높였다(재스케일 횟수 감소). 둘째 시퀀스 길이 방향으로도 병렬화해 긴 시퀀스·작은 배치에서 SM을 더 채운다. 셋째 워프 간 작업 분할을 개선해 shared memory 접근·동기화를 줄였다. 결과적으로 FA-1 대비 약 2배 처리량, A100에서 이론 FLOP의 50~73%에 도달했다. FA-3는 Hopper(H100)의 비동기 실행(TMA, WGMMA)과 FP8을 활용해 성능을 더 끌어올렸다.

## 7. 실무에서의 위치

PyTorch 2.x의 `scaled_dot_product_attention`은 조건이 맞으면 내부적으로 FlashAttention 백엔드를 자동 선택한다.

```python
import torch.nn.functional as F
out = F.scaled_dot_product_attention(q, k, v, is_causal=True)
```

| 국면 | FlashAttention 효과 | 비고 |
|---|---|---|
| 학습(긴 시퀀스) | 큼(2~4배) | 메모리 O(N)로 긴 컨텍스트 학습 |
| 추론 prefill | 큼 | 프롬프트 전체를 한 번에 처리 |
| 추론 decode | 제한적 | 쿼리 길이 1, FlashDecoding 등 필요 |

디코딩 단계는 쿼리 길이가 1이라 PagedAttention·FlashDecoding처럼 KV 캐시를 나눠 병렬화하는 별도 최적화가 결합된다. FlashAttention은 긴 컨텍스트의 prefill과 학습에서 효과가 가장 크다.

## 8. 정리

FlashAttention의 교훈은 같은 수학, 다른 메모리 접근이다. 연산량은 그대로 두고 GPU의 SRAM/HBM 대역폭 비대칭을 이해해 N×N 중간 행렬의 HBM 왕복을 제거했다. 타일링과 온라인 softmax로 정확성을 유지하고 재계산으로 역전파 메모리를 O(N)으로 낮췄다. 딥러닝 성능이 종종 FLOP이 아니라 메모리 이동에 지배되며, IO-aware 커널 설계가 모델 구조 변경 없이 큰 실질 이득을 준다는 대표 사례다.

## 참고

- Dao et al., FlashAttention: Fast and Memory-Efficient Exact Attention with IO-Awareness (2022)
- Dao, FlashAttention-2: Faster Attention with Better Parallelism and Work Partitioning (2023)
- Shah et al., FlashAttention-3 (2024)
- PyTorch Documentation: torch.nn.functional.scaled_dot_product_attention
