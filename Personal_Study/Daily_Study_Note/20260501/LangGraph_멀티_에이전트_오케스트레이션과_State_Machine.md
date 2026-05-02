Notion 원본: https://app.notion.com/p/3535a06fd6d381b3836acbcbd3b1400c

# LangGraph 멀티 에이전트 오케스트레이션과 State Machine 패턴

> 2026-05-01 신규 주제 · 확장 대상: AI

## 학습 목표

- LangGraph 의 그래프(노드/엣지/조건부 엣지) 모델을 LangChain Chains 와 비교
- TypedDict 기반 상태 정의와 state reducer(Annotated, operator.add) 동작 분석
- Supervisor 패턴, Hierarchical 패턴, Plan-and-Execute 패턴의 그래프 구조 정리
- Checkpointer + Human-in-the-loop 으로 중단·재개·승인 워크플로우 구현

## 1. 왜 LangChain 만으로 부족한가

LangChain 의 Chain/Sequential 구조는 선형 파이프라인이다. "검색 → LLM → 답변" 처럼 한 방향 흐름엔 적합하지만, 실제 에이전트 시스템은 다음 특성을 갖는다.

- **사이클**: 같은 노드를 여러 번 방문(예: tool 실행 후 답변 검증 → 부족하면 다시 검색).
- **분기**: LLM 응답을 보고 여러 다음 단계 중 하나를 동적으로 선택.
- **중단/재개**: 사용자 승인이 필요한 액션 앞에서 일시정지하고 외부 입력을 받는다.
- **메모리/상태**: 노드 간 공유 상태가 단순 input/output 전달로 표현되지 않는다.

LangGraph(2024 LangChain Inc. 출시)는 이 요구사항을 그래프(state machine) 추상으로 푼다. 그래프의 각 노드는 함수, 엣지는 다음 노드를 결정하는 라우터다. 상태는 그래프 전역에 살아 있으며 노드가 부분 갱신만 발행한다.

## 2. 최소 예시 — 두 노드, 한 엣지

```python
from typing import TypedDict, Annotated
from langgraph.graph import StateGraph, END
import operator

class State(TypedDict):
    messages: Annotated[list, operator.add]   # append 누적
    answer: str

def search_node(state: State) -> dict:
    query = state["messages"][-1]
    results = ddg_search(query)
    return {"messages": [f"검색결과: {results}"]}

def answer_node(state: State) -> dict:
    prompt = "\n".join(state["messages"])
    completion = llm.invoke(prompt)
    return {"answer": completion}

graph = StateGraph(State)
graph.add_node("search", search_node)
graph.add_node("answer", answer_node)
graph.set_entry_point("search")
graph.add_edge("search", "answer")
graph.add_edge("answer", END)

app = graph.compile()
result = app.invoke({"messages": ["LangGraph 란?"]})
```

핵심은 `Annotated[list, operator.add]` 표시다. 노드가 `{"messages": [...]}` 를 반환하면 LangGraph 가 기존 messages 와 reducer(`operator.add`) 로 합친다. reducer 가 없는 필드는 단순 덮어쓴다(`answer` 처럼). 이 모델 덕분에 노드 간 동시 실행 결과를 안전하게 병합 가능하다.

## 3. 조건부 엣지 — 동적 라우팅

LangChain Agent 가 내부에서 했던 일을 LangGraph 는 명시적 엣지로 표현한다.

```python
def should_continue(state: State) -> str:
    last = state["messages"][-1]
    if last.tool_calls:
        return "tools"
    return "end"

graph.add_conditional_edges(
    "agent",
    should_continue,
    {"tools": "tools_node", "end": END}
)
graph.add_edge("tools_node", "agent")  # tool 실행 후 다시 agent 로
```

이 구조가 ReAct 패턴(tool 호출 → 관찰 → 다시 추론)을 한 그래프 안에 사이클로 표현한다. `should_continue` 가 라우터 함수라 전체 흐름 제어가 코드 한 곳에 모인다.

## 4. Supervisor 패턴 — 멀티 에이전트의 표준

전문 에이전트 여러 명을 한 supervisor 가 조율한다. supervisor 는 매번 "다음 누가 일할지" 만 결정하고, 실제 작업은 worker 에이전트가 수행한다.

```python
class State(TypedDict):
    messages: Annotated[list[BaseMessage], operator.add]
    next: str          # supervisor 가 결정한 다음 worker 이름

def supervisor_node(state: State) -> dict:
    routing = supervisor_llm.invoke({
        "messages": state["messages"],
        "options": ["researcher", "coder", "writer", "FINISH"]
    })
    return {"next": routing["next"]}

def researcher_node(state: State) -> dict:
    result = research_agent.invoke(state["messages"][-1].content)
    return {"messages": [HumanMessage(content=result, name="researcher")]}

def coder_node(state: State) -> dict: ...
def writer_node(state: State) -> dict: ...

graph = StateGraph(State)
graph.add_node("supervisor", supervisor_node)
graph.add_node("researcher", researcher_node)
graph.add_node("coder", coder_node)
graph.add_node("writer", writer_node)

graph.set_entry_point("supervisor")
graph.add_conditional_edges(
    "supervisor",
    lambda s: s["next"],
    {"researcher": "researcher", "coder": "coder",
     "writer": "writer", "FINISH": END}
)
for w in ["researcher", "coder", "writer"]:
    graph.add_edge(w, "supervisor")  # 각 worker 끝나면 supervisor 로

app = graph.compile()
```

이 패턴이 multi-agent 의 사실상 표준. supervisor LLM 은 작은 모델(Claude Haiku, GPT-4o-mini)로 충분하다 — 분류만 하면 되니까. worker 들은 각자 도구·프롬프트가 다른 specialist.

## 5. Hierarchical 패턴 — 팀의 팀

대규모 워크플로우에서는 supervisor 도 계층화한다. 최상위 supervisor 가 "리서치 팀 vs 작성 팀" 을 고르고, 각 팀에는 자기 supervisor + worker 들이 있다.

```
TopSupervisor
├── ResearchTeam(Supervisor → Searcher / Reader / Summarizer)
└── WritingTeam(Supervisor → Drafter / Editor / Reviewer)
```

LangGraph 에서는 각 팀을 별도 sub-graph 로 컴파일한 뒤, 최상위 그래프의 "팀 노드" 가 sub-graph 를 invoke 하는 형태로 표현한다. 상태는 팀 경계에서 명시적으로 매핑한다(외부 state 의 일부만 sub-graph 의 입력으로 변환).

```python
research_app = research_graph.compile()

def research_team_node(state: TopState) -> dict:
    sub_state = {"messages": state["messages"]}
    result = research_app.invoke(sub_state)
    return {"messages": result["messages"]}
```

실무 권장: 팀 1개당 worker 4~5명 한도. 그 이상이면 supervisor 의 라우팅 정확도가 급격히 떨어진다(LLM 의 long-context 추론 한계).

## 6. Checkpointer — 상태 영속화

LangGraph 의 `checkpointer` 는 각 노드 실행 후 상태를 저장한다. 같은 thread_id 로 다시 invoke 하면 이전 상태에서 이어진다.

```python
from langgraph.checkpoint.postgres import PostgresSaver

checkpointer = PostgresSaver.from_conn_string("postgresql://...")
checkpointer.setup()  # 테이블 자동 생성

app = graph.compile(checkpointer=checkpointer)

config = {"configurable": {"thread_id": "user-42"}}
app.invoke({"messages": ["첫 질문"]}, config=config)
# 나중에 동일 thread_id 로 invoke 하면 이전 messages 누적된 채로 시작
app.invoke({"messages": ["두 번째 질문"]}, config=config)
```

지원 백엔드: Memory(테스트), SQLite, Postgres, Redis. 운영 환경은 Postgres 가 표준.

저장 단위는 super-step 마다 한 번. 노드가 100개여도 super-step 이 5개면 5개 체크포인트. 시간 여행(time travel)도 가능 — 과거 체크포인트에서 다른 분기로 재실행한다.

```python
states = list(app.get_state_history(config))
# 3 단계 전 상태에서 다시 시작
result = app.invoke(None, config={**config, "checkpoint_id": states[3].config["configurable"]["checkpoint_id"]})
```

## 7. Human-in-the-loop — 승인 게이트

위험한 액션(파일 삭제, 결제, 외부 API 쓰기) 직전에 정지하고 사용자 승인을 받는 패턴.

```python
graph.add_node("dangerous_action", dangerous_action_node)
app = graph.compile(
    checkpointer=checkpointer,
    interrupt_before=["dangerous_action"],   # 노드 실행 직전 멈춤
)

# 첫 호출 — interrupt 직전에 멈춤
app.invoke({"messages": [...]}, config=config)

# 사용자가 검토 후 승인하면 None invoke 로 재개
state = app.get_state(config)
if user_approved(state.values):
    app.invoke(None, config=config)
else:
    app.update_state(config, {"messages": [HumanMessage(content="취소")]})
    app.invoke(None, config=config)
```

`interrupt_before` 로 정한 노드 직전에 그래프가 일시정지하고, 그 시점의 상태가 체크포인트에 저장된다. 외부 시스템(Slack 봇, 이메일)이 승인 신호를 보내면 해당 thread_id 로 그래프를 재개한다. AutoGPT 같은 자율 에이전트의 위험성을 통제하는 핵심 메커니즘.

## 8. Streaming — 사용자 경험 개선

각 super-step 의 결과를 실시간 스트리밍으로 받을 수 있다.

```python
for event in app.stream({"messages": [...]}, config=config, stream_mode="updates"):
    for node, output in event.items():
        print(f"[{node}] {output}")
```

`stream_mode` 옵션:
- `"updates"`: 각 노드의 partial state update 만
- `"values"`: 매 super-step 후 전체 state
- `"debug"`: 모든 내부 이벤트(checkpoint, tasks)

LLM 토큰 단위 스트리밍은 노드 함수 내에서 `astream_events` 로 별도 처리한다.

## 9. 트레이드오프와 함정

| 항목 | 장점 | 단점/주의 |
|---|---|---|
| 사이클 표현 | 자연스러운 ReAct/평가 루프 | 무한 루프 위험 → `recursion_limit=25` |
| Reducer 합성 | 동시 실행 결과 안전 병합 | reducer 누락 시 silent overwrite |
| Checkpointer | 재시작·time travel | 상태 직렬화 비용(JSON 한계) |
| Conditional edges | 동적 분기 | 라우터 함수 디버깅 까다로움 |
| Sub-graph | 모듈화 | 상태 매핑 boilerplate |

운영에서 가장 흔한 사고: `Annotated[..., operator.add]` 빠뜨림 → 이전 messages 가 노드마다 사라지는 silent bug. State 정의를 코드 리뷰의 첫 점검 항목으로 삼는다.

## 10. LangGraph 외 대안 — 언제 쓰지 말까

- **단순 RAG**: LangChain Expression Language(LCEL) 만으로 충분. LangGraph 는 over-engineering.
- **단일 단계 분류·요약**: LLM 직접 호출이 제일 빠르다.
- **워크플로우 엔진 필요(approve, retry, scheduling)**: Temporal, Restate 등 정통 워크플로우 엔진이 더 강력. LangGraph 는 LLM 중심 그래프에 특화.
- **Production agent 가 핵심 비즈니스**: AWS Bedrock Agents, OpenAI Assistants API 등 매니지드 옵션도 평가 대상.

LangGraph 는 "복잡한 LLM 흐름을 직접 코드로 통제하고 싶을 때" 가 sweet spot. Python·TypeScript 동시 지원, OSS, LangSmith 통합 trace 가 강점.

## 참고

- LangGraph 공식 문서 — https://langchain-ai.github.io/langgraph/
- LangGraph GitHub — https://github.com/langchain-ai/langgraph
- LangGraph Tutorials (Multi-agent, Plan-and-Execute) — https://langchain-ai.github.io/langgraph/tutorials/
- "Building Effective Agents" (Anthropic blog) — https://www.anthropic.com/research/building-effective-agents
- LangSmith Trace 분석 — https://docs.smith.langchain.com
- 비교: AutoGen, CrewAI — https://microsoft.github.io/autogen/ , https://docs.crewai.com
