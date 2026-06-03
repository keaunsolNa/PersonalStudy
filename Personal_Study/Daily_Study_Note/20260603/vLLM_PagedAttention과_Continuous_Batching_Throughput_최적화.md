Notion 원본: https://www.notion.so/3745a06fd6d381b78fe2cb785bf594ac

# vLLM PagedAttention과 Continuous Batching Throughput 최적화

> 2026-06-03 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- vLLM 의 PagedAttention 이 *KV cache 단편화* 문제를 어떻게 OS 페이지 테이블 기법으로 해결하는지 따라간다
- Continuous batching 이 static batching 대비 throughput 을 몇 배 높이는지 측정값으로 확인한다
- Speculative decoding, prefix caching, chunked prefill 의 상호작용을 운영 옵션 수준에서 정리한다
- A100 80GB / H100 의 KV cache 메모리 산정과 max-model-len trade-off 를 검증한다

## 1. KV cache 의 단편화 — 전통 batching 의 함정

Transformer decoder 의 self-attention 은 매 token 생성마다 *과거 모든 token 의 Key / Value 텐서* 가 필요하다. Llama-3-70B (FP16) 은 320 KB / token, 4K context 1.28 GB / sequence. 전통 batching 은 max_seq_len 만큼 미리 할당 → 실제 평균 512 면 87% 낭비. PagedAttention 은 OS virtual memory paging 을 그대로 가져온 것. KV cache 를 block (default 16 token) 단위로 삐개고, sequence 는 block 의 list 만 들고 있다.

## 2. PagedAttention 의 자료구조

```
PhysicalBlocks   : KV tensor blocks of size [block_size, num_kv_heads, head_dim]
BlockTables[seq] : List[int]  (physical block index 의 리스트)
FreeBlockPool    : 사용 가능 block 의 free list
```

낭비 = 마지막 block 의 미사용 슬롯. 4K context 모델 메모리 효율 13% → 99%. kernel indirect lookup 오버헤드 2–5% 느린 대신 batch size 3–8 배 증가.

## 3. Continuous Batching — in-flight insert

전통 static batching 은 기다리는 동안 GPU 가 놀고 있다. continuous batching 은 매 step 마다 batch 구성이 동적. 모든 sequence 의 길이가 달라도 한 step 의 attention 연산은 모두 같은 1 token decode 이므로 GPU 가 항상 가득. *chunked prefill* 은 prefill 과 decode 를 같은 step 에 섞는다 — v0.5+ default.

## 4. throughput 실측 — A100 80GB, Llama-3-8B

| 구현 | requests/s | tokens/s (output) | p99 TTFT | p99 ITL |
|---|---|---|---|---|
| HF Transformers + greedy | 1.2 | 308 | 12.8 s | 320 ms |
| TGI v1 (continuous batch) | 5.8 | 1485 | 3.1 s | 95 ms |
| vLLM v0.4 (PagedAttention) | 11.4 | 2920 | 1.8 s | 38 ms |
| vLLM v0.6 + chunked prefill | 13.7 | 3510 | 1.2 s | 32 ms |
| vLLM v0.6 + spec decoding | 18.9 | 4840 | 0.9 s | 22 ms |

TTFT 단축은 chunked prefill, ITL 단축은 speculative decoding (1 step 평균 2.5 token).

## 5. KV cache 메모리 산정과 max-model-len

A100 80GB, Llama-3-8B FP16 weight 16 GB, swap 8 GB + activation 8 GB 제외 → KV cache ~48 GB. bytes/token = 128 KB → max_num_seqs × max_model_len = ~393,216. 챗봇 8192×256, long-context 32K×64, massive concurrency 4096×1024.

## 6. Prefix Caching

`--enable-prefix-caching`. block 단위 공유. system message 가 block boundary (16 배수) 에서 끝나도록 padding 하면 cache hit 극대화. RAG / function-calling 히트율 60–85%, throughput +30–50%. eviction LRU.

## 7. Speculative Decoding 통합

vLLM v0.6 부터 Medusa, EAGLE-2, MLPSpeculator 지원. acceptance 0.6–0.75 (채팅), 0.55–0.65 (코드). acceptance 0.7 면 throughput +1.8–2.2x. concurrent < 8 일 때 가장 큰 이득, > 64 면 평형.

## 8. Chunked Prefill 의 의미

긴 prompt (8K+) 의 prefill 이 수백 ms 를 차지해 다른 decode 대기를 유발. chunked prefill 은 chunk (default 512 token) 단위로 삐개 decode 와 interleave. `--enable-chunked-prefill --max-num-batched-tokens 4096`.

## 9. Trade-off — vLLM vs TGI vs SGLang vs TensorRT-LLM

| 항목 | vLLM | TGI | SGLang | TensorRT-LLM |
|---|---|---|---|---|
| 라이선스 | Apache 2.0 | Apache 2.0 | Apache 2.0 | NVIDIA 상용 |
| PagedAttention | O 원조 | O | O | O |
| Speculative decoding | O | 부분적 | O | O |
| 운영 친화 | 중 | 상 | 중 | 하 |

연구 / 신모델 → vLLM, HF 통합 → TGI, agentic 구조 → SGLang, NVIDIA 단독 / 최저 latency → TensorRT-LLM. 안전한 default 는 vLLM.

## 참고

- vLLM PagedAttention paper (SOSP 2023): https://arxiv.org/abs/2309.06180
- vLLM Docs — Performance Tuning
- SGLang RadixAttention (2024)
- MLPSpeculator (IBM 2024)
- NVIDIA TensorRT-LLM Best Practices
