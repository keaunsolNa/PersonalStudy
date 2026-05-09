Notion 원본: https://www.notion.so/35b5a06fd6d38126a533f01d8ae9dc76

# Self-Consistency와 Tree of Thoughts LLM 추론 전략

> 2026-05-09 신규 주제 · 확장 대상: AI Multi-Agent / LLM Reasoning

## 학습 목표

- Chain-of-Thought(CoT) 의 한계를 짚고, Self-Consistency(SC) 가 어떤 가설로 그 한계를 보완하는지 정리한다
- Tree of Thoughts(ToT) 의 *thought / state / value function / search* 4요소를 분해하고, BFS/DFS/Best-First 적용 시 비용·정확도 trade-off 를 본다
- GSM8K, Game of 24, Creative Writing 같은 벤치마크에서 보고된 수치를 인용해 어느 작업에 어떤 전략이 강한지 가시화한다
- 실제 OpenAI / Anthropic API 로 SC, ToT 를 구현하는 의사 코드와 비용 추정을 정리한다

## 1. CoT 의 한계

CoT 는 *단일 reasoning path* 다. 한 번 잘못된 중간 단계가 들어가면 끝까지 그 흐름을 따라간다. greedy decoding 이든 sampling 이든 *그 path 안에 정답이 있다는 보장* 이 약하다. GSM8K 수학 문제의 경우, GPT-4 급 모델로도 단일 CoT 정답률이 90% 대에서 saturate 되는 이유는 *추론 분기를 한 번에 하나만 본다* 는 구조적 한계 때문이다.

## 2. Self-Consistency: "다수결" 로 보강

Self-Consistency (Wang et al., 2022) 의 핵심 아이디어는 "*같은 문제를 여러 번 다른 reasoning path 로 풀고, 최종 답을 다수결* 한다". 절차:

1. 같은 prompt 로 temperature > 0 sampling 을 N 번 수행 → N 개의 (reasoning, answer) 쌍
2. answer 만 추출해 *가장 많이 나온 답* 을 채택

가정: "올바른 reasoning 은 다양해도 같은 답에 수렴하고, 잘못된 reasoning 은 서로 다른 잘못된 답으로 흩어진다". 이 가정은 *답이 유한 후보 집합* 인 작업(수학 정수, 객관식)에 잘 맞는다. 자유 텍스트 생성에는 곧장 적용하기 어렵다.

GSM8K 에서 N=40 sampling + temperature 0.7 시 단일 CoT 대비 +10~17pp 정확도 개선이 일관되게 보고된다. 비용은 *N 배 토큰* 이라 운영 시 cost-aware 한 N 선택이 핵심.

```python
async def self_consistent(prompt: str, n: int = 20) -> str:
    samples = await asyncio.gather(*[
        client.complete(prompt, temperature=0.7, max_tokens=1024) for _ in range(n)
    ])
    answers = [extract_answer(s.text) for s in samples]
    return Counter(answers).most_common(1)[0][0]
```

`extract_answer` 가 robust 해야 한다. 정규식으로 마지막 숫자/yes-no 를 뽑는 패턴이 흔하다. JSON tool-call 응답이라면 파싱이 단순해진다.

## 3. Tree of Thoughts (ToT) 의 4요소

ToT (Yao et al., 2023) 는 reasoning 을 *트리 탐색* 으로 본다.

| 요소 | 설명 |
|---|---|
| Thought | 한 단계의 부분 reasoning (예: 24 만들기에서 "두 수를 골라 +/-/×/÷ 적용") |
| State | 지금까지 누적된 thought 들의 묶음 |
| Generator | state → 다음 thoughts (n 개) 제안 |
| Evaluator | state 가 *유망한가* 점수화 |
| Search | BFS / DFS / Best-First / Beam |

Game of 24 에서 ToT 의 4-step 트리 + BFS(branching 5, beam 5) 로 GPT-4 의 정답률이 4% (CoT) → 74% (ToT) 로 향상. 비용은 LLM 호출 수가 *수십 배*. 단순 작업엔 과한 무기.

## 4. ToT 의 평가 함수

evaluator 는 *별도의 LLM 호출* 또는 *self-evaluation prompt* 로 구현한다. 두 가지 방식이 흔히 비교된다.

- **Value**: state 마다 "이 state 에서 정답까지 갈 가능성" 을 1~10 scoring. 휴리스틱.
- **Vote**: 동일 깊이의 형제 state 들 중 어느 것이 가장 좋은지 LLM 에 *상대 평가* 시킴. 절대 점수 보정 부담 없음.

Game of 24 같은 검증 가능한 작업에서는 evaluator 가 *부분 계산 결과를 수치 검증* 하도록 시킬 수 있어 정확도가 더 안정적. 자유 텍스트 작업에서는 vote 가 일반적으로 더 안정적이라고 보고된다.

```python
def evaluate_state(state, candidates: list[str]) -> list[float]:
    msg = render_vote_prompt(state, candidates)
    resp = llm.complete(msg, temperature=0.0)
    return parse_vote(resp.text)
```

## 5. 탐색 전략별 trade-off

| 전략 | 호출 수 | 적용 |
|---|---|---|
| BFS(beam=k) | depth × branching × k | 작업 단계가 일정하고 짧을 때 |
| DFS(+ pruning) | 가변 (early-stop) | 깊이 큰 답이 빨리 발견되면 빠름 |
| Best-First (priority queue) | 가변 | evaluator 신뢰 시 가장 효율 |
| Monte Carlo Tree Search (MCTS) | 매우 많음 | reward 가 명확한 게임/검증 가능 작업 |

BFS 는 구현이 단순해 *작업 길이가 4~6 step 이내* 인 경우 합리적. 길어지면 DFS 또는 Best-First 가 토큰을 적게 쓴다.

## 6. ReAct, Reflexion 과의 관계

CoT → SC → ToT 는 *reasoning trace 의 다양성/탐색* 에 초점. ReAct(Reason + Act, Yao et al., 2022) 는 *외부 도구/관찰* 을 reasoning 사이에 끼우는 패턴. Reflexion(Shinn et al., 2023) 은 *실패 후 self-critique 를 메모리에 축적해 다음 시도에 반영*. 셋은 직교 가능.

```text
ReAct  : reasoning + tool_call 의 시퀀스
Reflex : 실패 시 자기 비판을 메모리화해 재시도
ToT    : reasoning path 의 트리 탐색
SC     : path 결과의 다수결
```

운영 코드에서는 ToT 의 각 노드가 *ReAct 단계* 일 수 있고, 각 leaf 답에 대해 *SC 다수결* 을 수행할 수 있다. 즉 셋은 *결합 가능*.

## 7. 비용 추정과 운영 패턴

문제 1건당 비용 모델:

```
cost = N_calls * (avg_input_tokens + avg_output_tokens) * unit_price
```

ToT(BFS, depth=4, branching=3, beam=2):
- 한 노드당 generator 1 호출 + evaluator 1 호출
- 노드 수 = ~30
- 호출 수 = ~60

SC(N=20):
- 호출 수 = 20

CoT 단발 호출:
- 호출 수 = 1

따라서 *대규모 batch* 추론에서 ToT 는 SC 의 3배, CoT 의 60배 비용. 정확도 향상 폭과 비교해 선택해야 한다. 일반적인 권장:

- 빠른 답이 필요한 응답형: CoT
- 답 후보가 유한하고 정확도가 중요한 분류/수학: SC
- 다단계 의사결정/창의적 탐색이 필요한 워크플로우: ToT
- 외부 데이터 조회가 필요한 정보 검색형: ReAct

## 8. 실제 구현: ToT BFS 의사 코드

```python
def tot_bfs(problem, depth: int, branching: int, beam: int):
    states = [State(initial(problem))]
    for d in range(depth):
        next_states = []
        for s in states:
            cands = generator(s, n=branching)
            for c in cands:
                next_states.append(s.extend(c))
        # evaluate
        scores = [evaluator(s) for s in next_states]
        ranked = sorted(zip(scores, next_states), reverse=True)
        states = [s for _, s in ranked[:beam]]
    # 마지막 leaf 에서 답 추출
    final_scores = [(evaluator(s), s) for s in states]
    return max(final_scores, key=lambda x: x[0])[1].answer
```

state 객체는 *thought 의 누적* 이므로 prompt 길이가 깊이에 비례해 증가한다. context window 와 토큰 비용을 함께 고려해야 한다. 대형 모델로 깊이 6 이상 가면 input 토큰만으로도 비용이 폭증한다.

## 9. 한계와 비판

- **검증 불가능 작업의 evaluator 신뢰도 부족**: 자유 텍스트 평가에서 LLM evaluator 가 *자기 자신을 좋게 평가* 하는 편향이 보고된다. cross-evaluation (다른 모델로 평가) 으로 보강하기도 한다.
- **분기 폭과 깊이의 hyper-parameter 튜닝 비용**: 작업마다 적절 값이 달라 일반화 어려움
- **Latency**: ToT 는 step 단위 직렬화 비중이 높아 *체감 응답속도* 가 매우 느려진다. 사용자 대면 실시간 서비스에는 부적합
- **모델 사이즈 의존성**: 작은 모델은 evaluator 역할이 약해 ToT 효과가 작다. *30B+ 모델* 또는 *프론티어 급* 에서 효과가 두드러진다는 후속 보고

## 참고

- Wei et al., "Chain-of-Thought Prompting Elicits Reasoning in Large Language Models"
- Wang et al., "Self-Consistency Improves Chain of Thought Reasoning in Language Models"
- Yao et al., "Tree of Thoughts: Deliberate Problem Solving with Large Language Models"
- Yao et al., "ReAct: Synergizing Reasoning and Acting in Language Models"
- Shinn et al., "Reflexion: Language Agents with Verbal Reinforcement Learning"
- Anthropic / OpenAI 공식 문서 — temperature, top_p, n-sampling 가이드
