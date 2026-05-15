Notion 원본: https://www.notion.so/3615a06fd6d38175b39dc9acd495f02d

# DSPy Programmatic Prompting과 BootstrapFewShot Compiler 최적화

> 2026-05-16 신규 주제 · 확장 대상: AI / LLM

## 학습 목표

- 수동 prompt engineering 의 한계와 DSPy 가 채택한 *programmatic 선언 + 컴파일* 접근의 차이를 설명한다
- `Signature` / `Module` / `Predict` / `ChainOfThought` / `ReAct` 등 핵심 추상화의 책임 분리를 코드로 확인한다
- BootstrapFewShot, BootstrapFewShotWithRandomSearch, MIPRO v2 옵티마이저의 동작과 metric-driven 학습 루프를 단계별로 추적한다
- 검증된 trace 만 demonstration 으로 사용하는 *bootstrapping* 의 데이터 효율성과 LLM 호출 비용 트레이드오프를 분석한다

## 1. 수동 Prompt Engineering 의 한계

GPT-4 / Claude 등 강력한 LLM 이 등장했지만, 실무에서 *원하는 출력 품질*을 얻는 데 여전히 prompt 가 큰 비중을 차지한다. 일반적인 프로젝트 흐름은 직관으로 첫 prompt 작성 → 결과 보고 수정 → few-shot 손작성 → 미세 조정 → 모델 바꾸면 처음부터.

문제는 *재현성 / 측정 가능성 / 모듈성* 부족. prompt 안에 instruction / few-shot / output schema 가 한 문자열로 섞여서 retrieval 같은 컴포넌트와 함께 사용하면 코드가 *prompt 폭증* 한다.

DSPy 의 가설: prompt 를 *손으로 짜는 게 아니라*, 프로그램을 *선언*하고 평가 metric 으로 *컴파일* 하는 패러다임이 더 낫다. PyTorch 의 nn.Module + 최적화 루프와 유사한 멘탈 모델.

## 2. 핵심 추상화 — Signature, Module, Predict

### 2.1 Signature

```python
import dspy

class GenerateAnswer(dspy.Signature):
    """Answer questions concisely with factual support."""
    question = dspy.InputField()
    context = dspy.InputField(desc="relevant retrieved passages")
    answer = dspy.OutputField(desc="2-3 sentences, factual, no speculation")
```

이는 *프롬프트 자체가 아니다*. instruction(docstring) + input/output field 선언일 뿐. 실제 prompt 는 컴파일러가 생성한다.

### 2.2 Module

```python
class RAG(dspy.Module):
    def __init__(self, k=3):
        super().__init__()
        self.retrieve = dspy.Retrieve(k=k)
        self.generate = dspy.ChainOfThought(GenerateAnswer)

    def forward(self, question):
        context = self.retrieve(question).passages
        return self.generate(context=context, question=question)
```

### 2.3 Predict / ChainOfThought / ReAct

`Predict(Signature)` 는 가장 기본 형태. `ChainOfThought(Signature)` 는 reasoning 필드 자동 추가. `ReAct(Signature, tools=[...])` 는 thought→action→observation 루프 자동 진행.

## 3. 컴파일 — Optimizer 의 역할

```python
trainset = [
    dspy.Example(question="When did Napoleon become emperor?",
                 answer="December 2, 1804.").with_inputs('question'),
]

def em_metric(example, pred, trace=None):
    return pred.answer.lower().strip() == example.answer.lower().strip()

teleprompter = dspy.BootstrapFewShot(metric=em_metric, max_bootstrapped_demos=4)
compiled_rag = teleprompter.compile(student=RAG(), trainset=trainset)
```

`compile()` 호출 결과로 instruction 과 few-shot demos 가 *결정*된 새 RAG 모듈이 반환된다.

metric 종류:
- 단순 exact match: 빠름, 분산 적음, 정답 표현 변형에 약함
- ROUGE/BLEU: 텍스트 유사도
- LLM-as-judge: 가장 유연하지만 호출 비용 큼

## 4. BootstrapFewShot 알고리즘 상세

```
1. for example in trainset:
     trace = teacher(example.inputs)
     if metric(example, trace.output):
       keep trace
2. random sample 최대 max_bootstrapped_demos 개 trace
3. student 모듈의 각 Predict 에 sampled demonstration 주입
4. compiled_student 반환
```

핵심은 trace 가 *전체 multi-call chain* 을 기록한다는 점. RAG 처럼 retrieve → generate 두 단계가 있으면 각 단계의 input/output 이 별도 trace 로 분리되어 *각 단계 LLM 호출*에 별도 demonstration 주입.

수동으로 few-shot 을 쓰는 것보다 효율적. trainset 의 정답만 제공하면, 중간 단계 reasoning 은 LLM 이 자동 생성.

## 5. BootstrapFewShotWithRandomSearch

```python
teleprompter = dspy.BootstrapFewShotWithRandomSearch(
    metric=em_metric,
    max_bootstrapped_demos=4,
    num_candidate_programs=10,
    num_threads=4,
)
compiled = teleprompter.compile(student=RAG(), trainset=trainset, valset=valset)
```

각 candidate 는 *서로 다른 random 시드*로 demo 를 샘플링한 student. valset 으로 평균 metric 을 계산해 best candidate 를 선택. 10~20개 candidate 면 평균적으로 5~10% metric 향상.

비용: trainset 100개 × 10 candidate × cot inference 약 $0.005/call → 약 $5 (GPT-4o-mini).

## 6. MIPRO v2 — Multi-prompt Instruction Proposal

```python
teleprompter = dspy.MIPROv2(
    metric=em_metric,
    auto='medium',
    num_threads=8,
)
compiled = teleprompter.compile(student=RAG(), trainset=trainset, valset=valset)
```

단계:

```
1. BootstrapFewShot 으로 traces 모으기
2. instruction 후보 N개 LLM 으로 제안
3. 각 후보(instruction, demo set) 조합을 Bayesian Optimization 으로 탐색
4. valset 에서 best metric 의 조합을 채택
```

Optuna 의 TPE 알고리즘 사용. 약 50~100 시도로 수렴. BSFS+RS 의 3~5배 비용, 5~15% 추가 metric 개선.

## 7. 비용 비교 — Optimizer 별 LLM 호출 횟수

| Optimizer | 호출 횟수 | 특징 |
|---|---|---|
| LabeledFewShot | N | 단순 정답 demo |
| BootstrapFewShot | N + (k * M) | trace 1회 + eval |
| BSFS + RandomSearch (10) | N + 10 * k * M | 10배 비용 |
| MIPROv2 medium | 약 5 * (N + 50 * k * M) | instruction + BO |
| Auto heavy | 약 10배 medium | 더 깊은 탐색 |

trainset 100 / valset 50 / 4-shot / GPT-4o-mini 기준 비용:

- LabeledFewShot: $0.05
- BootstrapFewShot: $0.30
- BSFS + RandomSearch: $3
- MIPROv2 medium: $15~20

컴파일은 *한 번*만. 이후 inference 시 prompt 만 사용.

## 8. 실전 워크플로 — Routing + Retrieval + Generate

```python
class FAQRouter(dspy.Signature):
    """Decide which knowledge base to query."""
    question = dspy.InputField()
    category = dspy.OutputField(desc="one of: billing, technical, account")

class FAQAnswer(dspy.Signature):
    """Generate a customer-friendly answer."""
    question = dspy.InputField()
    passages = dspy.InputField()
    answer = dspy.OutputField()

class FAQModule(dspy.Module):
    def __init__(self):
        super().__init__()
        self.route = dspy.Predict(FAQRouter)
        self.retrieve = MyRetrieverModule()
        self.gen = dspy.ChainOfThought(FAQAnswer)

    def forward(self, question):
        cat = self.route(question=question).category
        passages = self.retrieve(question=question, category=cat).passages
        return self.gen(question=question, passages=passages)

def composite_metric(ex, pred, trace=None):
    answer_ok = ex.answer.lower() in pred.answer.lower()
    return answer_ok

mipro = dspy.MIPROv2(metric=composite_metric, auto='medium')
compiled_faq = mipro.compile(student=FAQModule(), trainset=trainset, valset=valset)
```

핵심: 한 metric 으로 *모든 sub-call* 의 정합성 검증. 컴파일러가 자동으로 *3개 Predict 모두*에 demonstration 주입.

## 9. 한계와 운영 함정

함정 1 — *metric design 의 함정*. exact match 너무 엄격해 demo 0개 → compile 효과 없음. F1 또는 LLM-as-judge 도입.

함정 2 — *trainset bias*. 한 도메인 치우치면 일반화 떨어짐. valset 다양성 필요.

함정 3 — *teacher = student* 모델일 때 self-bootstrapping 한계. teacher 가 더 큰 모델(GPT-4), student 는 cost-효율 모델.

함정 4 — *demonstration leakage*. 컴파일된 demo 가 trainset 의 PII 포함 가능. 외부 deploy 시 마스킹 필수.

함정 5 — *반복 컴파일 비용*. trainset 갱신 시마다 재컴파일. CI/CD 에 컴파일 캐시 + metric 회귀 게이트 필수.

## 10. Assertion / Suggestion — 제약 위반 자동 재시도

```python
class ConstrainedAnswer(dspy.Module):
    def __init__(self):
        super().__init__()
        self.gen = dspy.ChainOfThought(GenerateAnswer)

    def forward(self, question, context):
        result = self.gen(question=question, context=context)
        dspy.Assert(
            len(result.answer.split()) <= 50,
            "Answer must be under 50 words.",
        )
        dspy.Suggest(
            'http' not in result.answer.lower(),
            "Avoid raw URLs in answers; describe sources instead.",
        )
        return result
```

- `Assert` — 실패 시 *피드백을 prompt 에 inject* 하고 retry. 최대 backtrack 후 fail.
- `Suggest` — 권고 위반 시 retry 까지만, 최종 결과는 위반해도 통과.

JSON 출력 제약 패턴:

```python
import json
def is_valid_json(s):
    try: json.loads(s); return True
    except Exception: return False

class JSONAnswer(dspy.Signature):
    question = dspy.InputField()
    answer = dspy.OutputField(desc='Valid JSON object with keys: name, age')

class JSONModule(dspy.Module):
    def __init__(self):
        super().__init__()
        self.gen = dspy.Predict(JSONAnswer)
    def forward(self, question):
        result = self.gen(question=question)
        dspy.Assert(is_valid_json(result.answer), 'Output must be parseable JSON.')
        return result
```

OpenAI/Anthropic 의 *structured output mode* 보다 일반화돼 있다. 어떤 모델/조건에서도 동작.

## 참고

- DSPy Documentation (https://dspy.ai/) 및 GitHub (https://github.com/stanfordnlp/dspy)
- Khattab et al., "DSPy: Compiling Declarative Language Model Calls into Self-Improving Pipelines" (NeurIPS 2024)
- Khattab et al., "MIPRO: Multi-prompt Instruction Proposal Optimization"
- Omar Khattab, "Programming, not prompting, foundation models" (TalkRL Podcast / Stanford Lecture)
- Anthropic Cookbook — DSPy + Claude 통합 예제
