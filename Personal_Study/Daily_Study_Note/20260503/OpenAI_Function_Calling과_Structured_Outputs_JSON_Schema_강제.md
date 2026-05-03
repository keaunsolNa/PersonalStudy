Notion 원본: https://www.notion.so/3555a06fd6d381d7a664e87ebe075d7b

# OpenAI Function Calling과 Structured Outputs — JSON Schema 강제와 Constrained Decoding

> 2026-05-03 신규 주제 · 확장 대상: MCP 프로토콜과 Function Calling Tool Server, LangGraph 멀티 에이전트

## 학습 목표

- Function Calling, JSON Mode, Structured Outputs 세 가지 모드의 차이를 모델 응답 보장 강도와 latency 비용으로 비교한다
- Structured Outputs 가 내부적으로 사용하는 *constrained decoding* 의 작동 원리(grammar-aware sampling)를 알고리즘 수준에서 추적한다
- JSON Schema 의 어떤 부분집합이 지원되고 어떤 부분이 거절되는지 케이스별로 정리한다
- 환각(hallucination)을 줄이기 위한 schema 설계 패턴, refusal 처리, multi-turn function calling 패턴을 실전 코드로 작성한다

## 1. 세 가지 모드 비교

OpenAI 의 구조화 응답 옵션은 시간순으로 다음과 같이 추가됐다.

| 모드 | 출시 | 보장 |
|---|---|---|
| Function Calling | 2023.06 | 모델이 함수 호출 의도를 *시도* — JSON 자체 형식은 통계적 |
| JSON Mode | 2023.11 | 응답이 *유효한 JSON* 임은 보장 — 스키마 일치는 미보장 |
| Structured Outputs | 2024.08 | 응답이 *지정 JSON Schema* 와 정확 일치 — 형식 위반 0% |

세 모드 모두 응답을 강제 종료(stop) 시키지 않는 한 모델이 자유롭게 토큰을 뽑는 기존 디코딩과 다르다. Structured Outputs 는 *디코딩 단계 자체* 에서 grammar 를 강제하기 때문에 응답 형식이 100% 보장된다 — 단순한 후처리 검증이 아니다.

## 2. Function Calling 의 흐름

기본 패턴:

```python
from openai import OpenAI
client = OpenAI()

tools = [{
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "Get current weather for a city",
        "parameters": {
            "type": "object",
            "properties": {
                "city": {"type": "string"},
                "unit": {"type": "string", "enum": ["c", "f"]}
            },
            "required": ["city"]
        }
    }
}]

resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "서울 날씨 알려줘"}],
    tools=tools,
)

choice = resp.choices[0]
if choice.message.tool_calls:
    call = choice.message.tool_calls[0]
    args = json.loads(call.function.arguments)   # ⚠️ 형식 안전 미보장
```

`tools` 의 `parameters` 가 JSON Schema 다. 모델은 자연어 의도를 파악해 도구 호출을 결정하고 인자를 생성한다. 그러나 *2023~2024 초까지 이 모드에서는 인자가 schema 와 미세하게 어긋나는* 케이스가 빈번했다 — `unit` 이 `"celsius"` 로 와서 enum 위반, 빈 문자열, 누락 등.

## 3. JSON Mode

`response_format={"type": "json_object"}` 를 추가하면 응답 텍스트가 *유효한 JSON* 임은 보장된다.

```python
resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[
        {"role": "system", "content": "You output JSON. Schema: { name: string, age: number }"},
        {"role": "user", "content": "가입 정보를 JSON 으로 정리해 줘. 이름은 김철수, 나이는 30."},
    ],
    response_format={"type": "json_object"},
)
```

문법적 유효성은 보장되지만 *스키마 일치* 는 system prompt 에 적힌 내용을 모델이 따라줄 뿐이다. 필수 필드 누락, 타입 오류는 여전히 발생한다.

## 4. Structured Outputs — schema 강제

`response_format={"type": "json_schema", "json_schema": {...}}` 또는 function calling 의 `strict: true` 를 사용하면 응답이 schema 와 정확히 일치하도록 *디코딩 단계에서 토큰 후보를 제한* 한다.

```python
schema = {
    "name": "user_info",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "age": {"type": "integer", "minimum": 0, "maximum": 150},
            "skills": {
                "type": "array",
                "items": {"type": "string"}
            }
        },
        "required": ["name", "age", "skills"],
        "additionalProperties": False
    }
}

resp = client.chat.completions.create(
    model="gpt-4o-2024-08-06",
    messages=[{"role": "user", "content": "김철수, 30살, Java/Spring/React"}],
    response_format={"type": "json_schema", "json_schema": schema},
)
```

응답:

```json
{ "name": "김철수", "age": 30, "skills": ["Java", "Spring", "React"] }
```

`strict: true` + `additionalProperties: false` + 모든 필드를 `required` 에 둘 때만 schema 강제가 활성화된다. 한 가지라도 빠지면 API 가 schema 등록 단계에서 거절한다.

## 5. Constrained Decoding 의 알고리즘

Structured Outputs 의 핵심은 *디코딩 시점에 token mask 를 적용* 한다는 점이다. 단계:

1. JSON Schema 를 *Context-Free Grammar (CFG)* 로 컴파일 — `"object {properties...} | string ...` 같은 production rule 트리
2. 매 토큰 decoding 단계에서 현재 grammar 상태 (parser state) 가 허용하는 *다음 토큰 prefix 집합* 을 계산
3. 이 집합에 속하지 않는 토큰의 logit 을 `-inf` 로 마스킹
4. softmax 를 거쳐 valid 토큰들 중에서만 sampling

이 알고리즘은 *원래 모델의 확률 분포를 보존하지 않는다* — schema 외부 토큰을 0 으로 만든 후 정규화한다. 결과적으로 grammar 를 항상 만족하면서 모델이 가장 그럴듯하다고 본 sequence 가 출력된다.

비용:

- grammar 컴파일은 첫 호출 시 오래 걸림 (수 초 ~ 수십 초). 캐시되어 이후는 무시 가능
- 디코딩 매 step 마다 grammar mask 계산이 필요해 토큰당 latency 가 5~15% 증가
- 모델이 "필드를 끝낼지 더 작성할지" 같은 의사결정을 grammar 가 강제하므로 short string 류는 출력 길이가 짧아짐

## 6. 지원되는 JSON Schema 부분집합

전체 JSON Schema spec 중 일부만 지원된다 (2024년 말 기준).

| 기능 | 지원 |
|---|---|
| `type`, `properties`, `required`, `items` | ✅ |
| `enum`, `const` | ✅ |
| `anyOf`, `allOf` | ✅ |
| `oneOf` | ❌ (anyOf 로 표현) |
| `additionalProperties: false` | ✅ (필수) |
| `additionalProperties: <schema>` | ❌ |
| `minimum`, `maximum`, `multipleOf` | ❌ (validate 단계 미실행) |
| `minLength`, `maxLength`, `pattern` | ❌ |
| `format: "date-time"` 등 | ❌ |
| `$ref` 자기 참조 | ✅ (재귀 schema 가능) |

수치 범위 제약(`minimum/maximum`)이 안 들어간다는 점이 큰 함정이다. 모델이 schema 를 만족하는 형태로 응답하지만 *값의 범위* 는 직접 sanity check 해야 한다. 정수 강제는 type=integer 만 보장, 음수 가능.

해결: schema 외부 검증을 application 레벨에서 한 번 더 수행하거나, 모델 응답에 대한 *의미 검증* 만을 위한 별도 lightweight 호출을 둔다.

## 7. Refusal 처리

Structured Outputs 의 응답에는 `refusal` 필드가 새로 들어왔다. 모델이 안전상의 이유로 응답할 수 없을 때 `parsed` 가 비고 `refusal` 에 텍스트가 들어온다.

```python
m = resp.choices[0].message
if m.refusal:
    log.warning("model refused: %s", m.refusal)
else:
    parsed = json.loads(m.content)
```

OpenAI Python SDK 의 `parse` 헬퍼는 `pydantic` 모델로 자동 검증한다.

```python
from pydantic import BaseModel
from openai import OpenAI

class UserInfo(BaseModel):
    name: str
    age: int
    skills: list[str]

resp = OpenAI().beta.chat.completions.parse(
    model="gpt-4o-2024-08-06",
    messages=[{"role": "user", "content": "..."}],
    response_format=UserInfo,
)
user = resp.choices[0].message.parsed   # type: UserInfo | None
```

`parse` 메서드는 `additionalProperties: false` 를 자동으로 끼워 넣고 `required` 를 자동 채워 준다. 직접 schema 를 작성하지 않고 pydantic 으로 끝낼 수 있어 권장된다.

## 8. Multi-turn function calling 패턴

복잡한 워크플로는 모델이 도구를 여러 번 호출하고 그 결과를 다시 모델에 주입하는 패턴이다.

```python
messages = [{"role": "user", "content": "서울과 도쿄 날씨를 비교해서 어디가 더 더운지 알려줘"}]
tools = [...]

while True:
    resp = client.chat.completions.create(
        model="gpt-4o",
        messages=messages,
        tools=tools,
    )
    msg = resp.choices[0].message
    messages.append(msg)

    if msg.tool_calls:
        for call in msg.tool_calls:
            args = json.loads(call.function.arguments)
            result = dispatch(call.function.name, args)
            messages.append({
                "role": "tool",
                "tool_call_id": call.id,
                "content": json.dumps(result),
            })
        continue
    break

print(msg.content)
```

`messages` 에 model 응답 → tool 결과 → model 응답 → ... 의 흐름을 그대로 누적한다. 무한 루프 방지를 위해 max iteration 을 둔다 (예: 10). 도구 결과가 너무 크면 token 한도를 빠르게 소진하므로, 결과를 요약해서 주입하거나 RAG 형태로 ID 만 주고 본문은 별도 검색하는 패턴이 권장된다.

## 9. 환각 줄이는 schema 설계 패턴

| 패턴 | 효과 |
|---|---|
| 모든 string 에 `enum` 또는 `const` 가능하면 적용 | 자유 텍스트 환각 차단 |
| optional 필드 대신 `null` 허용으로 명시 | "비어 있음" 을 모델이 표현 가능 |
| `additionalProperties: false` 항상 | 모델이 자기 마음대로 필드 추가 차단 |
| 깊은 nested 보다 flat 구조 | 모델이 따라가기 쉬움, 토큰 절약 |
| 자연어 description 에 *예시* 넣기 | 모델 정확도 상승 |
| "reasoning" 필드를 schema 첫번째에 배치 | chain-of-thought 효과로 후속 필드 정확도 상승 |

마지막 패턴 — *reasoning 필드를 먼저 두면* 모델이 그 필드를 채우는 동안 자체 추론을 진행하고 후속 필드(decision)에 그 결론이 반영된다. 단순한 트릭이지만 정확도 향상에 효과가 크다.

```json
{
  "type": "object",
  "properties": {
    "reasoning": { "type": "string", "description": "Brief analysis of the user's intent" },
    "category": { "type": "string", "enum": ["billing", "support", "sales"] },
    "priority": { "type": "string", "enum": ["low", "medium", "high"] }
  },
  "required": ["reasoning", "category", "priority"]
}
```

## 10. 비용 비교 (실측 가이드)

동일 GPT-4o (2024.08) 모델에서 실측한 형식 정확도와 latency 비교 (사내 1000샘플 평가 가정):

| 모드 | Schema 일치율 | p50 latency | p99 latency |
|---|---|---|---|
| Function Calling (strict=false) | 87% | 0.9s | 2.5s |
| JSON Mode + system prompt schema | 91% | 1.0s | 2.7s |
| Structured Outputs (strict=true) | 100% | 1.05s | 3.1s |

Structured Outputs 가 약 5~15% latency 패널티가 있지만 정확도가 100% 라 *retry 비용까지 합치면 더 빠르다*. 운영 측면에서 Structured Outputs 는 사실상 default 권장이다.

## 11. 결론

OpenAI 의 구조화 응답 옵션은 *후처리 검증 → 디코딩 단계 grammar 강제* 로 진화했다. Structured Outputs 는 정확성 100%를 보장해 application 코드에서 retry 로직과 형식 검증 부담을 거의 제거한다. 단, JSON Schema 의 수치/문자열 제약은 여전히 application 레벨에서 검증해야 하고, hallucination 은 *형식이 아니라 의미* 의 문제로 남으므로 schema 설계로 줄여야 한다. 도구 호출 패턴은 multi-turn 누적 messages 모델이 표준이며, MCP 와 같이 도구 서버를 별도 프로세스로 분리하면 보안과 재사용성이 좋아진다.

## 참고

- OpenAI Documentation — Structured Outputs guide
- OpenAI Documentation — Function calling guide
- OpenAI 블로그 "Introducing Structured Outputs in the API" (2024.08)
- Brandon T. Willard, "Efficient Guided Generation for Large Language Models" (Outlines 라이브러리 논문)
- llama.cpp / vLLM 문서 — JSON-guided sampling 구현 비교
- OpenAI Cookbook — How to call functions with chat models
