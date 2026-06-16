Notion 원본: https://www.notion.so/3815a06fd6d381008731fcb9f6c6bda5

# LLM 추론 최적화 — KV Cache와 PagedAttention 및 Continuous Batching

> 2026-06-16 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- 자기회귀 디코딩에서 KV Cache 가 왜 필요하고 메모리를 얼마나 쓰는지 계산한다
- PagedAttention 이 KV Cache 단편화를 페이지 테이블 방식으로 해결하는 원리를 설명한다
- Continuous Batching 이 정적 배칭 대비 처리량을 끌어올리는 메커니즘을 구분한다
- 처리량(throughput)과 지연(TTFT/TPOT) 사이의 trade-off 를 서빙 파라미터로 판단한다

## 1. 자기회귀 디코딩과 KV Cache 의 필요성

Transformer 디코더는 토큰을 하나씩 생성한다. t번째 토큰을 만들 때 attention 은 지금까지의 모든 토큰(1..t)에 대한 **Key(K)** 와 **Value(V)** 를 참조한다. 캐시가 없다면 매 스템마다 1..t 전체의 K, V 를 다시 계산해야 하고, 이는 시퀀스 길이에 대해 O(n²) 연산을 매 토큰 반복하는 낭비다.

**KV Cache** 는 이미 계산한 각 토큰의 K, V 를 저장해두고, 새 토큰 1개분의 K, V 만 추가로 계산해 붙이는 기법이다. 덕분에 디코딩 한 스템의 attention 연산이 "새 query 1개 × 누적 K,V" 로 줄어 사실상 선형에 가까워진다. 거의 모든 프로덕션 LLM 서빙이 KV Cache 를 쓴다.

문제는 **메모리** 다. KV Cache 크기는 대략 다음과 같다.

```
KV bytes ≈ 2(K와 V) × layers × seq_len × kv_heads × head_dim × dtype_bytes × batch
```

예를 들어 레이어 32, kv_head 8, head_dim 128, fp16(2바이트) 모델에서 시퀀스 2048, 배치 1이면: `2 × 32 × 2048 × 8 × 128 × 2 ≈ 268MB`. 배치가 커지고 컨텍스트가 길어지면 KV Cache 가 모델 가중치보다 더 많은 GPU 메모리를 먹기도 한다. 즉 **LLM 서빙의 메모리 병목은 가중치가 아니라 KV Cache** 인 경우가 많고, 이게 동시 처리 가능한 요청 수(배치 크기)를 직접 제한한다.

## 2. 전통적 KV Cache 의 단편화 문제

순진한 구현은 요청마다 "최대 길이만큼" 연속(contiguous)된 KV 메모리를 예약한다. 두 가지 낭비가 생긴다. **내부 단편화** — 실제 생성 길이가 max_len 보다 짧으면 예약했지만 안 쓰는 공간이 버려진다. **외부 단편화** — 요청마다 크기가 달라 연속 블록을 잡기 어려워 메모리에 구멍이 생긴다. 실측 연구에 따르면 이런 방식은 KV 메모리의 상당 부분(때로 60~80%)을 실제 토큰이 아닌 예약/단편에 낭비했다. 메모리가 곳 동시성이므로, 이 낭비는 처리량 손실로 직결된다.

## 3. PagedAttention — OS 가상메모리에서 빌린 아이디어

PagedAttention(vLLM 의 핵심 기법)은 OS 의 **가상 메모리 페이징** 을 KV Cache 에 적용한다. KV Cache 를 고정 크기 **블록(page)** — 예: 토큰 16개분 — 으로 잘게 나누고, 논리적으로 연속인 시퀀스를 물리적으로는 트어진 블록에 저장한 뒤 **블록 테이블(page table)** 로 매핑한다.

```
시퀀스 A 논리: [tok0..15][tok16..31][tok32..47]
물리 블록:      블록7      블록2       블록9   (비연속)
블록 테이블 A: [7, 2, 9]
```

효과는 명확하다. 연속 공간이 필요 없으니 외부 단편화가 사라지고, 블록 단위로만 할당하니 내부 단편화는 최대 "한 블록 미만" 으로 제한된다. 그 결과 같은 GPU 메모리로 훨씬 많은 요청을 동시에 올릴 수 있어 처리량이 크게 오른다.

추가 이점은 **공유(copy-on-write)** 다. 여러 요청이 같은 프롬프트(시스템 프롬프트, few-shot 예시)를 공유하면, 그 프롬프트의 KV 블록을 복제하지 않고 **공유** 한다. 병렬 샘플링(한 프롬프트로 n개 후보 생성)이나 beam search 에서 prefix 가 동일한 부분의 KV 를 한 벌만 들고 가다가, 갈라지는 순간에만 블록을 복사한다. 이는 OS 의 fork() 후 COW 와 정확히 같은 발상이다.

## 4. Continuous Batching — GPU 를 놀리지 않기

GPU 추론은 배칭으로 처리량을 올린다. 그런데 **정적 배칭(static batching)** 은 배치 안의 모든 요청이 끝날 때까지 기다린다. 요청마다 생성 길이가 천차만별(어떤 건 20토큰, 어떤 건 800토큰)이라, 짧은 요청이 끝나도 그 슬롯은 가장 긴 요청이 끝날 때까지 비어 있다 — GPU 가 논다.

**Continuous Batching**(= in-flight batching, iteration-level scheduling)은 배치를 **토큰 생성 스템(iteration) 단위** 로 재구성한다. 매 디코딩 스템마다 스케줄러가 (1) 끝난 요청을 배치에서 빼고, (2) 대기 중인 새 요청을 빈 슬롯에 즉시 끼워 넣는다. 슬롯이 비는 즉시 다음 요청으로 채워지므로 GPU 활용률이 극대화된다.

```
정적 배칭:   [req1 20tok][.................빈 슬롯................] req4가 800tok 끝날 때까지 대기
Continuous: [req1 20tok 끝→req5 투입][req2 끝→req6 투입] ... 빈 슬롯을 매 스템 재충전
```

PagedAttention 과 Continuous Batching 은 짝이다 — 페이징으로 메모리 단편화를 없애 더 많은 요청을 올릴 수 있게 하고, continuous batching 으로 그 요청들을 빈틈없이 굴린다. vLLM 이 정적 배칭 기반 서버 대비 큰 처리량 향상을 보고한 핵심 조합이 이것이다.

## 5. Prefill 과 Decode — 두 단계의 상반된 특성

LLM 추론은 두 단계로 나뉘다. **Prefill**(프롬프트 처리)은 입력 전체를 한 번에 병렬 계산 — compute-bound, GPU 연산기가 포화된다. **Decode**(토큰 생성)는 한 번에 1토큰씩 — memory-bandwidth-bound, KV Cache 를 읽어오는 메모리 대역폭이 병목이고 연산기는 한가하다.

이 비대칭이 두 지표를 만든다. **TTFT(Time To First Token)** 는 주로 prefill 시간이 좌우하고, **TPOT(Time Per Output Token)** 는 decode 단계가 좌우한다. 두 단계를 한 배치에 섞으면 긴 prefill 이 decode 들의 지연을 튀게 한다. 그래서 최신 서빙은 **chunked prefill**(긴 프롬프트를 조각내 decode 와 끼워 굽기)이나 **prefill/decode 분리(disaggregation)** 로 둘을 떼어 각각 최적화한다.

| 단계 | 병목 | 좌우 지표 | 최적화 |
|---|---|---|---|
| Prefill | 연산(compute) | TTFT | chunked prefill, prefix 캐시 |
| Decode | 메모리 대역폭 | TPOT | continuous batching, 큰 배치 |

## 6. 처리량 vs 지연 — 서빙 파라미터의 trade-off

배치를 키우면 처리량(전체 tokens/s)은 오르지만, 한 요청 입장의 지연(TPOT)은 나빠진다 — 더 많은 요청과 GPU 를 나눴 쓰기 때문이다. 운영에서 조정하는 핵심 손잡이는 다음과 같다.

`max_num_seqs`(동시 시퀀스 상한)와 `gpu_memory_utilization`(KV Cache 에 쓸 메모리 비율)은 동시성을 정한다. `max_num_batched_tokens` 는 한 스템에 처리할 토큰 예산이다. 이 값들을 키우면 처리량↑ 지연↑. SLA 가 "p99 TPOT 50ms 이하" 같은 형태라면, 그 한도 안에서 배치를 최대한 키우는 지점을 찾는 것이 튜닝의 본질이다.

```python
# vLLM 서빙 파라미터 예시 (개념)
from vllm import LLM
llm = LLM(
    model="meta-llama/Llama-3.1-8B-Instruct",
    gpu_memory_utilization=0.90,   # 90% 를 가중치+KV Cache 로
    max_num_seqs=256,              # 동시 시퀀스 상한 → 처리량/지연 균형
    max_num_batched_tokens=8192,   # 스템당 토큰 예산
    enable_prefix_caching=True,    # 공통 프롬프트 KV 재사용
)
```

추가 메모리 절감 기법으로 **양자화** 가 있다. 가중치 양자화(예: AWQ, GPTQ 의 4비트)는 가중치 메모리를, **KV Cache 양자화**(fp8/int8)는 KV Cache 메모리를 줄여 더 큰 배치를 가능케 한다. 단 양자화는 약간의 품질 저하를 동반하므로 정확도 측정과 함께 도입해야 한다.

## 7. 멀티 에이전트 서비스 관점의 시사점

여러 에이전트가 같은 시스템 프롬프트/툴 정의를 공유하는 멀티 에이전트 서비스에서는 **prefix caching(공유 프롬프트 KV 재사용)** 의 이득이 특히 크다. 동일한 긴 시스템 프롬프트를 매 요청 prefill 하는 대신 KV 를 캐시해두면 TTFT 가 급감한다. 또 에이전트 간 호출이 짧은 요청을 대량 발생시키는 패턴이라면 continuous batching 의 효과가 두드러진다. 반대로 한 에이전트가 매우 긴 컨텍스트(수만 토큰)를 들고 다니면 그 요청 하나의 KV Cache 가 동시성을 잡아먹으므로, 컨텍스트 길이 관리(요약/압축)가 곳 서빙 비용 관리가 된다. 즉 인프라 최적화(PagedAttention/continuous batching)와 애플리케이션 설계(프롬프트 공유, 컨텍스트 절제)가 함께 가야 비용 대비 처리량이 산다.

## 8. KV Cache 를 줄이는 모델 구조 — MQA / GQA

PagedAttention 이 메모리 *관리* 로 KV Cache 낭비를 줄인다면, 모델 구조 차원에서 KV Cache *총량* 을 줄이는 방법이 있다. §1 의 공식에서 KV 크기는 `kv_heads` 에 비례하는데, 바로 이 항을 공략한다.

**MHA(Multi-Head Attention)** 는 query head 마다 별도의 K, V head 를 둔다(kv_heads = query_heads). **MQA(Multi-Query Attention)** 는 모든 query head 가 **단 하나의 K, V** 를 공유한다 — KV Cache 가 head 수만큼 줄어 메모리가 급감하지만 품질 저하가 있다. **GQA(Grouped-Query Attention)** 는 그 절충으로, query head 들을 몇 개 그룹으로 묶어 그룹당 K, V 하나를 공유한다. 예컨대 query head 32, KV group 8 이면 KV Cache 가 MHA 대비 1/4 이다. 최근의 많은 대형 모델이 GQA 를 채택한 이유가 이것 — 품질을 거의 유지하면서 KV Cache 를 줄여 더 긴 컨텍스트와 더 큰 배치를 가능케 한다.

| 방식 | KV head 수 | KV Cache | 품질 |
|---|---|---|---|
| MHA | = query head | 최대 | 기준 |
| GQA | 그룹 수(예: 1/4) | 중간 | 거의 동등 |
| MQA | 1 | 최소 | 다소 저하 |

서빙 엔지니어 관점에서 핵심은 "이 모델이 GQA/MQA 인가" 가 동시 처리 가능 배치 크기를 직접 좌우한다는 점이다. §1 의 메모리 공식에 모델의 실제 `num_key_value_heads` 를 넣어 KV Cache 예산을 계산하면, `gpu_memory_utilization` 과 `max_num_seqs`(§6)를 근거 있게 정할 수 있다.

## 9. 서빙 지표를 SLO 로 묶기

LLM 서비스의 SLO 는 단일 지표가 아니라 여러 지연 지표의 조합이다. **TTFT**(첫 토큰까지)는 사용자 체감 반응성 — 채팅 UX 에서 가장 중요하다. **TPOT/ITL**(토큰당 시간)은 생성 속도(체감 "타이핑 속도"). **처리량(tokens/s, req/s)** 은 비용 효율. 이들은 §6 에서 봤듯 서로 충돌하므로, 운영은 "TTFT p99 ≤ X, TPOT p99 ≤ Y 를 지키는 한도 내에서 처리량 최대화" 형태의 제약 최적화가 된다.

```text
부하 증가에 따른 전형적 곡선:
  배치↑ → 처리량↑ (좋음)  그러나  TTFT/TPOT↑ (나쁜)
  → SLO(TTFT/TPOT 상한)에 닿는 지점이 그 GPU 의 실효 최대 동시성
```

실무 측정에서는 합성 부하 도구로 동시 요청 수를 단계적으로 올리며 처리량과 p50/p99 지연을 함께 기록해 "무릎점(knee)" 을 찾는다. 그 지점을 넘기면 처리량 이득은 미미한데 지연이 급격히 나빠진다. 멀티 에이전트 서비스(§7)처럼 짧은 요청이 폭발적으로 발생하는 패턴은 continuous batching 덕에 무릎점이 높지만, 긴 컨텍스트 요청이 섞이면 그 요청의 prefill 이 TTFT 를 끜어올리므로 chunked prefill(§5)이나 요청 분류(긴/짧은 요청 별도 풀)로 완화한다. 결국 KV Cache 구조 이해(§1, §8) → 메모리 예산 산정 → 배치 파라미터 튜닝 → SLO 기반 무릎점 측정으로 이어지는 한 흐름이 LLM 서빙 비용 최적화의 봈대다.

## 참고

- Kwon et al., "Efficient Memory Management for LLM Serving with PagedAttention" (vLLM, SOSP 2023)
- Yu et al., "Orca: A Distributed Serving System for Transformer-Based Generative Models" (continuous batching 원형)
- vLLM 공식 문서 — Paged Attention, Prefix Caching, Chunked Prefill
- NVIDIA 기술 블로그 — In-flight batching (TensorRT-LLM)
