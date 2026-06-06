Notion 원본: https://www.notion.so/3775a06fd6d3812f9e7dc46775f7c33d

# FlashAttention-3 — IO-Awareness와 Online Softmax Tiling

> 2026-06-06 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- 표준 attention의 메모리 병목(O(N²) HBM 트래픽)을 정량적으로 설명한다
- online softmax와 tiling이 softmax를 재계산 없이 블록 단위로 누적하는 원리를 도출한다
- FlashAttention-3가 Hopper GPU의 WGMMA·TMA·FP8을 활용해 가속하는 지점을 정리한다
- attention 커널이 memory-bound에서 compute-bound로 옮겨가는 의미를 해석한다

## 1. 표준 attention의 진짜 병목은 메모리

Self-attention은 O = softmax(QKᵀ / √d) V다. 순진한 구현은 N×N 점수 행렬 S와 softmax 결과 P를 HBM에 통째로 쓰고 다시 읽는다. 연산량은 O(N²d)지만 진짜 문제는 메모리 트래픽이 O(N²)라는 것이다. GPU에서 HBM 대역폭은 on-chip SRAM보다 한 자릿수 이상 느려, attention처럼 산술 강도가 낮은 연산은 memory-bound가 된다. FlashAttention의 핵심 통찰은 N×N 행렬을 HBM에 절대 materialize하지 않는다이다.

## 2. Tiling — Q, K, V를 블록으로 쪼개 SRAM에서 처리

```
for i in row_blocks(Q):
    O_i = 0; l_i = 0; m_i = -inf
    for j in col_blocks(K, V):
        S_ij = Q_i @ K_j.T          # 작은 블록만 SRAM에
        m_new = max(m_i, rowmax(S_ij))
        P_ij  = exp(S_ij - m_new)
        l_i   = exp(m_i - m_new) * l_i + rowsum(P_ij)
        O_i   = exp(m_i - m_new) * O_i + P_ij @ V_j
        m_i   = m_new
    O_i = O_i / l_i
    write O_i to HBM                 # 출력만 HBM에 기록
```

HBM에 쓰는 것은 최종 출력 O뿐이다. 중간 점수 행렬은 SRAM 안에서 생성·소비되고 사라져 HBM 트래픽이 O(N²)에서 O(N·d) 수준으로 준다.

## 3. Online Softmax

softmax는 행 전체의 max와 합을 알아야 하는데 블록 단위로 나누면 모든 열을 한 번에 못 본다. running max를 m, 합을 l, 누적 출력을 O라 하면:

```
m_new = max(m, max(s))
α = exp(m - m_new)         # 항상 ≤ 1
p = exp(s - m_new)
l = α * l + sum(p)
O = α * O + p @ V_block
m = m_new
```

α = exp(m - m_new)가 핵심이다. max가 갱신될 때마다 이전까지의 합 l과 출력 O를 같은 비율로 줄여, 처음부터 새 max로 계산한 것과 수학적으로 동일한 결과를 얻는다. 근사가 아니라 정확히 동일하다.

| 구현 | N×N HBM 기록 | HBM 트래픽 | softmax |
|---|---|---|---|
| 표준 attention | 함 | O(N²) | 전체 행 일괄 |
| FlashAttention | 안 함 | O(N·d) | online(running max/sum) |

## 4. 역전파 — 재계산으로 메모리를 더 아낀다

학습 시 backward는 forward의 P가 필요한데 FlashAttention은 이를 저장하지 않고 forward에서 저장한 통계(m, l)와 Q, K, V로 backward에서 S와 P를 다시 계산한다. 재계산은 추가 연산이지만 attention이 memory-bound라 절약한 HBM I/O가 재계산 비용을 압도한다. activation 메모리가 O(N²)에서 O(N)으로 줄어 더 긴 시퀀스를 학습할 수 있다.

## 5. FlashAttention-3 — Hopper에 맞춘 가속

FlashAttention-3(2024)은 NVIDIA Hopper 기능을 적극 활용한다. (1) Warp-specialization + TMA: Tensor Memory Accelerator로 데이터 로드를 비동기로 수행하고 warp를 producer/consumer로 특화해 메모리 이동과 연산을 겹친다. (2) GEMM-softmax 파이프라이닝: 한 블록의 WGMMA 행렬곱과 다른 블록의 softmax를 인터리빙한다. (3) FP8 + incoherent processing: FP8로 Tensor Core 처리량을 두 배로 쓰되 block quantization과 Hadamard 변환으로 outlier를 분산해 정밀도 손실을 줄인다. 결과 FA-3는 H100에서 FA-2 대비 대략 1.5~2배 빠르다고 보고됐다.

```python
from flash_attn import flash_attn_func
out = flash_attn_func(q, k, v, causal=True, softmax_scale=1/math.sqrt(d))
```

## 6. 운영·적용 관점

추론의 디코딩 단계는 KV cache를 읽는 memory-bound라 PagedAttention(vLLM) 같은 KV 관리와 결합된다. FlashAttention은 prefill과 학습에서 특히 큰 이득을 준다. FA-3의 최대 이점은 Hopper 전용 기능에서 나오고 Ampere(A100)에서는 FA-2 경로가 표준이다. FP8 경로 도입 시 다운스트림 품질을 BF16 baseline과 반드시 비교한다. O(N) 메모리 덕분에 동일 GPU에서 훨씬 긴 컨텍스트를 다룰 수 있다.

## 7. 산술 강도와 roofline

FlashAttention의 tiling은 메모리 트래픽을 줄여 산술 강도를 끌어올리고 커널을 roofline의 memory-bound 영역에서 compute-bound 영역 쪽으로 이동시킨다. 일단 compute-bound에 가까워지면 다음 지렛대는 연산 유닛을 얼마나 쉬지 않고 돌리느냐다. 바로 이 지점에서 FA-3의 기여(TMA 비동기, WGMMA 파이프라이닝, FP8)가 의미를 갖는다. 먼저 병목을 옮기고 옮긴 다음 새 병목을 치는 인과 순서를 이해하는 것이 시스템 최적화의 핵심 사고법이다.

## 8. 정리

FlashAttention 계열의 본질은 attention은 연산이 아니라 메모리가 병목이라는 IO-awareness다. tiling으로 N×N 점수 행렬을 HBM에 만들지 않고, online softmax로 블록을 보며 running max/sum을 보정해 정확한 softmax를 SRAM 안에서 누적한다. FlashAttention-3는 여기에 Hopper의 비동기 메모리 이동(TMA), WGMMA 파이프라이닝, FP8을 더해 알고리즘이 만든 여유를 하드웨어 활용률로 전환한다.

## 참고

- Dao et al., "FlashAttention: Fast and Memory-Efficient Exact Attention with IO-Awareness" (NeurIPS 2022)
- Dao, "FlashAttention-2" (2023)
- Shah et al., "FlashAttention-3: Fast and Accurate Attention with Asynchrony and Low-precision" (2024)
- Milakov & Gimelshein, "Online normalizer calculation for softmax" (2018)
- NVIDIA Hopper Architecture Whitepaper — WGMMA, TMA, FP8 Tensor Core
