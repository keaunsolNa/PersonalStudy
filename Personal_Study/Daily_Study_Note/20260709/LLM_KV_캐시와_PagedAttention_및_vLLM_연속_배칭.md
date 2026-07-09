Notion 원본: https://app.notion.com/p/3985a06fd6d3818a8942cdffbabe3be9

# LLM KV 캐시와 PagedAttention 및 vLLM 연속 배칭

> 2026-07-09 신규 주제 · 확장 대상: AI

## 학습 목표

- 트랜스포머 추론에서 KV 캐시가 필요한 이유와 메모리 특성을 설명한다
- 기존 연속 메모리 할당 방식의 단편화 문제를 진단한다
- PagedAttention 이 가상 메모리 페이징을 차용해 KV 캐시를 관리하는 원리를 분석한다
- continuous batching 이 처리량을 높이는 메커니즘과 prefill/decode 분리를 판별한다

## 1. KV 캐시가 필요한 이유

자기회귀 LLM 은 토큰을 하나씩 생성하며 각 새 토큰의 어텐션이 이전 모든 토큰의 K·V 를 참조한다. 캐시가 없으면 매 스텝마다 전체 K·V 를 재계산해 생성 길이에 대해 제곱으로 증가한다. KV 캐시는 계산한 K·V 를 저장해 스텝당 계산을 선형으로 만든다. 대가는 메모리로, 긴 컨텍스트·큰 배치에서 KV 캐시가 서빙의 실질 병목이 된다.

## 2. 연속 할당의 단편화 문제

전통 시스템은 각 요청에 최대 생성 길이만큼 KV 캐시를 연속 텐서로 미리 할당했다. 이는 내부 단편화(예약 길이보다 짧은 생성), 예약 단편화(미래 토큰 자리 선점), 외부 단편화(크기 불균등 조각)를 낳는다. vLLM 논문 측정상 유효 활용률이 20-40% 에 그치는 경우가 흔했다.

## 3. PagedAttention 의 핵심 아이디어

KV 캐시를 고정 크기 블록으로 쪼개고, 논리적으로 연속인 시퀀스를 물리적으로 흔어진 블록에 저장하고 블록 테이블(page table)이 논리→물리 매핑을 한다. 시퀀스가 자랄 때 필요한 만큼만 블록을 할당해 예약·내부 단편화가 사라지고 블록 크기가 통일되어 외부 단편화도 없어진다.

## 4. 블록 공유와 Copy-on-Write

여러 요청이 동일 프롬프트 접두부(시스템 프롬프트)를 가지면 그 KV 블록을 물리적으로 공유해 중복 저장을 없앨다. 쓰기 시 COW 로 해당 블록만 복제한다. 이 prefix caching 은 대화 히스토리·공통 지침 재사용 시 prefill 계산과 메모리를 동시 절감한다.

## 5. Continuous Batching

정적 배칭은 배치의 모든 요청이 끝날 때까지 기다려 짧은 요청이 끝나도 GPU 가 논다. Continuous batching(iteration-level scheduling)은 매 스텝마다 배치를 재조정해 끝난 요청을 빼고 대기 요청을 넣어 GPU 를 채운다. PagedAttention 의 유연한 블록 할당이 이를 받친다. 메모리 부족 시 스왕이나 재계산(preemption)으로 대응한다.

## 6. Prefill 과 Decode 분리

Prefill 은 프롬프트 전체를 한 번에 처리해 연산 집약(compute-bound), Decode 는 토큰을 하나씩 생성해 메모리 대역폭에 묶이는 memory-bound 다. 둘을 섮으면 prefill 이 decode 지연을 튀게 해 chunked prefill 나 disaggregated serving 으로 분리한다. 지표는 TTFT(prefill)와 ITL/TPOT(decode)로 나눈다.

| 단계 | 병목 | 지배 지표 | 최적화 |
|---|---|---|---|
| Prefill | 연산 | TTFT | chunked prefill, prefix cache |
| Decode | 메모리 대역폭 | ITL/TPOT | continuous batching, paged KV |

## 7. 검증 예시

```python
import asyncio, time, httpx
async def one(client, prompt):
    t0 = time.perf_counter()
    r = await client.post("http://localhost:8000/v1/completions", json={
        "model": "meta-llama/Llama-3.1-8B-Instruct", "prompt": prompt, "max_tokens": 128}, timeout=60)
    return time.perf_counter() - t0, r.json()["usage"]["completion_tokens"]
```

동시성을 올려 처리량 포화 지점과 p50/p99 지연을 기록하고, nvidia-smi 와 vLLM 로그의 GPU KV cache usage·Running/Waiting 큰 길이로 교차 검증한다.

## 참고

- Kwon et al., PagedAttention (SOSP 2023)
- vLLM Documentation — PagedAttention, Continuous Batching, Chunked Prefill
- Anyscale — Continuous batching to increase LLM inference throughput
- vLLM — Automatic Prefix Caching
