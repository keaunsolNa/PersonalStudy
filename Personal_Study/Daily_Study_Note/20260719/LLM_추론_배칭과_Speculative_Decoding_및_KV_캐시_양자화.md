Notion 원본: https://www.notion.so/3a25a06fd6d38133a4fdc6d2347dcdf4

# LLM 추론 배칭과 Speculative Decoding 및 KV 캐시 양자화

> 2026-07-19 신규 주제 · 확장 대상: AI

## 학습 목표

- LLM 추론이 memory-bound 인 이유와 배칭이 처리량을 올리는 원리를 설명한다.
- Speculative decoding 의 수용-검증 구조와 분포 보존을 이해한다.
- KV 캐시 구조와 양자화·GQA 로 줄이는 방법을 비교한다.
- 지연 vs 처리량 서빙에서 기법을 선택한다.

## 1. 두 단계와 memory-bound

Prefill 은 입력 전체를 병렬 처리해 compute-bound, decode 는 토큰을 하나씩 내며 가중치 전체를 읽어 memory-bound 다. 대역폭이 지배하므로 가중치를 한 번 읽어 여러 요청에 재사용(배칭)하면 처리량이 오른다.

## 2. 연속 배칭

정적 배칭은 짧은 요청이 끝나도 긴 요청 때문에 슬롯이 놀다. 연속 배칭은 매 디코드 스텝마다 끝난 요청을 빼고 새 요청을 넣어 처리량을 수 배 올린다(vLLM, TensorRT-LLM, TGI).

## 3. KV 캐시

```
KV 캐시 = 2 × 레이어 × KV헤드 × head_dim × 시퀀스길이 × 배치 × 정밀도
```

시퀀스·배치에 선형으로 커져 배치를 키워 처리량을 올리려는 시도가 KV 메모리 벽에 막힌다.

## 4. PagedAttention

KV 캐시를 고정 크기 블록으로 쫪고 논리적 연속 시퀀스를 물리적 흘어진 블록에 매핑한다. 낭비·단편화 제거 + 프리픽스 공유(CoW)로 동시 요청 수를 크게 늘린다.

## 5. Speculative Decoding

작고 빠른 draft 모델이 K개 토큰을 추측하고 target 모델이 한 번의 forward 로 병렬 검증한다. decode 가 memory-bound 라 1개든 K개든 비용이 비슷하다.

```
1. draft: [t1..t5] 추측
2. target: 5개 병렬 forward → 진짜 분포
3. 수용-거부 샘플링으로 accept/첫 reject 지점에서 재샘플
```

핵심은 수용-거부 샘플링이 출력 분포를 수학적으로 정확히 보존한다는 것이다. 품질 손실 없이 속도만 얻는다. 이득은 draft 수용률에 달려 코드·정형 텍스트는 2~3배, 창의적 텍스트는 적다.

## 6. KV 캐시 양자화

K·V 텐서를 FP8·INT4 로 저장해 같은 메모리에 더 긴 컨텍스트·큰 배치를 담는다. 가중치 양자화(GPTQ/AWQ)와 독립이다. FP8 KV 는 품질 손실이 거의 없어 널리 쓰고, outlier 채널이 오차를 키워 per-channel 스케일을 둔다.

## 7. GQA

| 방식 | 쿼리 | KV헤드 | KV캐시 | 품질 |
|---|---|---|---|---|
| MHA | N | N | 최대 | 최고 |
| MQA | N | 1 | 최소 | 저하 |
| GQA | N | G | 중간 | MHA 근접 |

GQA 는 쿼리 헤드를 G개 그룹으로 묶어 KV헤드 하나를 공유한다. 32쿼리를 8KV로 묶으면 KV캐시 1/4. GQA + FP8 KV 를 함께 쓰면 곱으로 절감된다.

## 8. 서빙 목표별 선택

| 목표 | 지표 | 우선 기법 |
|---|---|---|
| 처리량 | tokens/sec | 연속배칭 + PagedAttention + 큰 배치 |
| 지연 | TTFT/TPOT | Speculative decoding + 작은 배치 |
| 긴 컨텍스트 | 시퀀스 길이 | GQA + KV양자화 + PagedAttention |

모든 기법을 동시에 켜면 최선이라는 착각을 경계하라. Speculative 는 배치가 크면 이득이 사라지고, 공격적 KV 양자화는 품질을 깍으며, 큰 배치는 지연을 늘린다. 먼저 병목(연산/KV메모리/지연)을 프로파일링해 해당 기법만 고른다.

## 참고

- vLLM 논문 — "Efficient Memory Management for LLM Serving with PagedAttention"
- Leviathan et al. — "Fast Inference from Transformers via Speculative Decoding"
- Ainslie et al. — "GQA: Training Generalized Multi-Query Transformer Models"
- NVIDIA TensorRT-LLM / Hugging Face TGI 문서
- "Orca: A Distributed Serving System ..." — continuous batching
