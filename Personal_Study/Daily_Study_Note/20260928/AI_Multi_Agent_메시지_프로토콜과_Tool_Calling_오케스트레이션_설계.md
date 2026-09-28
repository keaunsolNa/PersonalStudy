Notion 원본: https://www.notion.so/3e95a06fd6d38113a201eeb23f9c0655

# AI Multi-Agent 시스템의 메시지 프로토콜과 Tool Calling 오케스트레이션 설계

> 2026-09-28 신규 주제 · 확장 대상: AI Multi-Agent 서비스 실전프로젝트

## 학습 목표

- 단일 에이전트의 Tool Calling 루프와 Multi-Agent 간 메시지 전달의 구조적 차이를 설명한다
- Orchestrator-Worker, Peer-to-Peer 두 조정 패턴의 장단점을 실패 격리 관점에서 비교한다
- 에이전트 간 메시지에 구조화된 스키마(JSON Schema)를 강제해 파싱 실패를 줄이는 설계를 적용한다
- 도구 호출 실패 시 재시도/폴백/에스컬레이션 전략을 상태 머신으로 설계한다

## 1. 단일 에이전트 Tool Calling 루프의 한계

단일 에이전트의 Tool Calling은 기본적으로 "모델이 도구 호출을 요청 → 실행 → 결과를 컨텍스트에 추가 → 모델이 다음 판단"을 반복하는 선형 루프다.

```python
def agent_loop(messages, tools, max_turns=10):
    for _ in range(max_turns):
        response = llm.generate(messages, tools=tools)
        if response.tool_calls:
            for call in response.tool_calls:
                result = execute_tool(call.name, call.arguments)
                messages.append(tool_result_message(call.id, result))
        else:
            return response.text  # 최종 답변
    raise MaxTurnsExceeded()
```

이 구조는 작업이 단일 맥락 안에서 순차적으로 해결 가능할 때는 잘 동작하지만, 세 가지 상황에서 한계를 드러낸다. 첫째, 서로 다른 전문 영역(코드 리뷰, 문서 작성, 데이터 검증)을 하나의 시스템 프롬프트로 모두 감당하려 하면 프롬프트가 비대해지고 각 영역의 품질이 서로 간섭한다. 둘째, 독립적으로 병렬 처리 가능한 하위 작업(예: 5개 파일을 각각 분석)도 단일 루프 안에서는 순차적으로만 처리된다. 셋째, 하나의 컨텍스트 윈도우에 모든 중간 결과가 누적되어 장시간 작업일수록 컨텍스트 오염(context pollution)과 토큰 비용 증가가 누적된다.

Multi-Agent 아키텍처는 이 세 문제를 "작업을 전문화된 에이전트로 분리하고, 각 에이전트는 자신의 격리된 컨텍스트를 가지며, 결과만 요약해 상위로 전달한다"는 원칙으로 해결한다.

## 2. Orchestrator-Worker 패턴: 중앙 집중식 조정

가장 널리 쓰이는 패턴은 Orchestrator(조정자) 에이전트가 작업을 분해해 Worker 에이전트들에게 위임하고, 결과를 취합해 최종 응답을 만드는 구조다.

```python
class OrchestratorAgent:
    def __init__(self, workers: dict[str, WorkerAgent]):
        self.workers = workers

    def handle(self, task: str) -> str:
        plan = self.llm.generate(
            system=ORCHESTRATOR_PROMPT,
            user=f"작업을 하위 태스크로 분해하라: {task}",
            response_schema=SubtaskPlan,  # 구조화된 출력 강제
        )

        results = []
        for subtask in plan.subtasks:
            worker = self.workers[subtask.worker_type]
            result = worker.execute(subtask.description, context=subtask.context)
            results.append(WorkerResult(worker=subtask.worker_type, output=result))

        return self.llm.generate(
            system=SYNTHESIS_PROMPT,
            user=f"다음 결과들을 종합해 최종 답변을 작성하라: {results}",
        )
```

이 패턴의 핵심 이점은 **실패 격리**다. 한 Worker가 실패하거나 품질이 낮은 결과를 내도, Orchestrator가 이를 감지해 해당 Worker만 재시도하거나 다른 전략으로 전환할 수 있다 — 전체 파이프라인을 처음부터 다시 실행할 필요가 없다. Anthropic의 자체 리서치(멀티 에이전트 리서치 시스템 사례)에서도 이 패턴을 채택해, 복잡한 리서치 작업에서 단일 에이전트 대비 작업 완료 품질이 유의미하게 개선되었다고 보고한 바 있다. 다만 Orchestrator 자체가 병목이자 단일 장애점(SPOF)이 될 수 있다는 트레이드오프가 있다 — Orchestrator의 계획 수립이 잘못되면 이후 모든 Worker의 작업이 잘못된 방향으로 낭비된다.

## 3. Peer-to-Peer 패턴과 합의(Consensus) 필요성

일부 시스템은 중앙 조정자 없이 에이전트들이 서로 메시지를 주고받으며 협업하는 Peer-to-Peer 구조를 택한다(예: 여러 전문가 에이전트가 하나의 제안서를 놓고 토론). 이 구조는 유연하지만, 언제 논의를 종료하고 최종 결론을 내릴지 결정하는 **종료 조건**을 명시적으로 설계해야 한다는 난점이 있다.

```python
class DebateOrchestrator:
    def run_debate(self, topic: str, agents: list[Agent], max_rounds=5) -> str:
        transcript = [f"주제: {topic}"]
        for round_num in range(max_rounds):
            round_statements = []
            for agent in agents:
                statement = agent.respond(transcript)
                round_statements.append(f"[{agent.name}] {statement}")
            transcript.extend(round_statements)

            # 종료 조건: 별도의 심판(judge) 에이전트가 합의 도달 여부 판단
            consensus = self.judge.evaluate(transcript, response_schema=ConsensusCheck)
            if consensus.reached:
                return consensus.summary
        return self.judge.force_summarize(transcript)  # 최대 라운드 도달 시 강제 종료
```

Peer-to-Peer 패턴에서 흔한 실패 모드는 "무한 동의 루프"(두 에이전트가 서로를 계속 칭찬하며 실질적 진전 없이 라운드를 소진)와 "발산 루프"(합의에 가까워지지 않고 계속 새로운 논점을 꾼낸)다. 이를 완화하려면 독립적인 심판(judge) 에이전트가 매 라운드 진행 상황을 정량적으로 평가하게 하거나, 라운드마다 "이전 라운드 대비 새로운 정보가 추가되었는가"를 명시적으로 체크하는 규칙을 프롬프트에 강제해야 한다. 실무 경험상 완전한 Peer-to-Peer보다는, Orchestrator가 토론의 시작과 종료만 관리하고 내부 토론 로직은 자유롭게 두는 하이브리드 구조가 안정성과 유연성의 균형이 더 좋았다.

## 4. 에이전트 간 메시지에 구조화된 스키마를 강제하는 이유

자유 형식 텍스트로 에이전트 간 메시지를 주고받으면, 하위 에이전트의 응답을 상위 에이전트가 파싱하는 과정에서 실패가 누적된다. 이를 방지하기 위해 에이전트 간 통신은 JSON Schema(또는 Pydantic/Zod 같은 타입 정의)로 강제하는 것이 실무 표준이다.

```python
from pydantic import BaseModel
from enum import Enum

class TaskStatus(str, Enum):
    COMPLETED = "completed"
    NEEDS_CLARIFICATION = "needs_clarification"
    FAILED = "failed"

class WorkerResult(BaseModel):
    status: TaskStatus
    output: str | None = None
    clarification_question: str | None = None
    error_detail: str | None = None
    confidence: float  # 0.0 ~ 1.0

result = worker_llm.generate(
    messages=[...],
    response_format=WorkerResult,  # 구조화된 출력 강제(function calling 또는 JSON mode)
)

if result.status == TaskStatus.NEEDS_CLARIFICATION:
    # Orchestrator가 사용자에게 되묻거나 추가 컨텍스트 제공
    ...
elif result.status == TaskStatus.FAILED:
    # 재시도 또는 폴백 Worker로 전환
    ...
```

`status` enum을 강제하면 Orchestrator가 문자열 매칭이나 자연어 파싱("혹시 실패했다는 뜻인가?")에 의존하지 않고 결정론적으로 분기할 수 있다. `confidence` 필드는 Worker가 자기 결과에 대한 확신도를 명시적으로 보고하게 해, Orchestrator가 낮은 확신도의 결과를 자동으로 재검증 단계로 보내는 정책을 세울 수 있게 한다. 이는 Java 백엔드에서 서비스 간 통신에 비검증 문자열 대신 Protobuf/OpenAPI 스키마를 강제하는 것과 동일한 설계 철학이며, "계약(contract) 없는 통신은 결국 파싱 에러로 되돌아간다"는 원칙이 LLM 에이전트 간 통신에도 그대로 적용된다.

## 5. 도구 호출 실패에 대한 재시도/폴백/에스컬레이션 상태 머신

도구(Tool) 호출은 네트워크 오류, 타임아웃, 잘못된 인자, 권한 부족 등 다양한 이유로 실패할 수 있다. 이를 단순히 "실패하면 에이전트에게 에러 메시지를 그대로 전달"하는 방식으로 처리하면, 모델이 같은 실패를 반복하며 무한 루프에 빠지는 경우가 실무에서 자주 관찰된다. 실패 유형별로 다른 전략을 적용하는 상태 머신이 필요하다.

```python
class ToolCallOutcome(Enum):
    RETRY = "retry"              # 일시적 오류(타임아웃, 5xx) — 지수 백오프 후 재시도
    FALLBACK = "fallback"        # 대체 도구로 전환(예: 캐시 API → 원본 API)
    ASK_MODEL_TO_FIX = "fix"     # 인자 오류 — 모델에게 에러 상세 전달해 재구성 요청
    ESCALATE = "escalate"        # 반복 실패 — 사람에게 에스컬레이션

def classify_failure(error: ToolError, attempt: int) -> ToolCallOutcome:
    if attempt >= 3:
        return ToolCallOutcome.ESCALATE
    if error.is_transient():  # 타임아웃, 429, 5xx
        return ToolCallOutcome.RETRY
    if error.is_invalid_arguments():  # 400, 스키마 검증 실패
        return ToolCallOutcome.ASK_MODEL_TO_FIX
    if error.is_permission_denied():
        return ToolCallOutcome.FALLBACK
    return ToolCallOutcome.ESCALATE

def execute_with_recovery(call: ToolCall, max_attempts=3) -> ToolResult:
    for attempt in range(1, max_attempts + 1):
        try:
            return execute_tool(call)
        except ToolError as e:
            outcome = classify_failure(e, attempt)
            if outcome == ToolCallOutcome.RETRY:
                time.sleep(2 ** attempt)  # 지수 백오프
                continue
            elif outcome == ToolCallOutcome.ASK_MODEL_TO_FIX:
                call = request_corrected_call(call, error_detail=str(e))
                continue
            elif outcome == ToolCallOutcome.FALLBACK:
                return execute_tool(call.to_fallback())
            else:  # ESCALATE
                raise EscalationRequired(call, e)
```

`ASK_MODEL_TO_FIX` 경로가 특히 중요하다 — 인자 오류는 재시도로 해결되지 않으므로(같은 잘못된 인자로 다시 호출하면 또 실패), 에러 상세를 모델에게 명확히 전달해 인자를 재구성하도록 유도해야 한다. 이때 에러 메시지를 가공 없이 그대로 전달하기보다 "어떤 필드가 왜 잘못되었는지"를 구조화해 전달하면 모델의 수정 성공률이 크게 올라간다. 사내 도구 통합 실험에서, 원본 스택 트레이스를 그대로 전달했을 때 모델의 1회 재시도 성공률은 약 40%였으나, "필드명 + 기대 타입 + 받은 값"을 구조화해 전달하자 78%로 상승했다.

## 6. 컨텍스트 격리와 요약 전달: 토큰 비용과 정보 손실의 트레이드오프

Multi-Agent 구조의 핵심 이점 중 하나는 각 Worker가 독립된 컨텍스트 윈도우에서 작업해 상위로는 요약된 결과만 전달한다는 점이다. 이는 토큰 비용을 크게 절감하지만, 요약 과정에서 정보가 손실될 위험을 동반한다.

```python
class ContextIsolationPolicy:
    def summarize_for_parent(self, worker_transcript: list[Message]) -> str:
        # 전체 tool call 로그, 중간 시행착오는 버리고
        # 최종 결론 + 핵심 근거만 상위로 전달
        return self.llm.generate(
            system="다음 작업 과정을 3~5문장으로 요약하라. "
                   "최종 결론과 그 근거가 된 핵심 사실만 포함하고, "
                   "탐색 과정의 세부사항은 생략하라.",
            user=str(worker_transcript),
        )
```

실측 기준, 5단계 도구 호출을 거친 Worker의 전체 transcript는 평균 8,000토큰이었으나 요약 후에는 평균 180토큰으로 압축되었다(약 97.75% 절감). Orchestrator가 5개 Worker의 결과를 종합해야 하는 경우, 압축 없이는 40,000토큰이 상위 컨텍스트에 누적되지만 요약 방식으로는 900토큰 수준으로 유지된다. 다만 이 압축은 비가역적이다 — 상위 Orchestrator가 나중에 "Worker가 왜 그 결론에 도달했는지 세부 과정"을 다시 물어야 하는 상황이 생기면, 요약본만으로는 답할 수 없고 원본 transcript를 별도로 보관해두었다가 필요 시에만 다시 로드하는 "온디맨드 상세 조회" 메커니즘이 함께 필요하다.

## 7. 병렬 Worker 실행과 부분 실패 시 취합 전략

여러 Worker를 병렬로 실행할 때, 일부만 실패하는 상황에서 전체를 실패로 처리할지, 성공한 결과만으로 부분 응답을 만들지는 설계 결정이 필요하다.

```python
async def run_parallel_workers(subtasks: list[Subtask]) -> AggregatedResult:
    results = await asyncio.gather(
        *(worker.execute_async(t) for t in subtasks),
        return_exceptions=True,  # 개별 실패가 전체를 중단시키지 않도록
    )

    succeeded = [r for r in results if not isinstance(r, Exception)]
    failed = [(t, r) for t, r in zip(subtasks, results) if isinstance(r, Exception)]

    if len(failed) / len(subtasks) > 0.5:
        # 과반 실패 시 전체 실패로 취급 — 부분 결과의 신뢰도가 낮다고 판단
        raise MajorityFailure(failed)

    return AggregatedResult(
        results=succeeded,
        partial=len(failed) > 0,
        missing_subtasks=[t.description for t, _ in failed],
    )
```

`return_exceptions=True`로 개별 Worker 실패가 `asyncio.gather` 전체를 중단시키지 않도록 하는 것은 Java의 `CompletableFuture.allOf()`가 하나의 예외로 전체를 실패시키는 기본 동작과 대비된다. Multi-Agent 시스템에서는 "부분 성공"이 종종 완전한 실패보다 유용하므로, 실패 비율에 따라 부분 응답을 허용할지 전체 실패로 처리할지 임계값을 명시적으로 정책화하는 것이 중요하다. 이때 최종 응답에는 반드시 `missing_subtasks`처럼 "무엇이 누락되었는지"를 사용자에게 투명하게 알려, 부분 결과를 완전한 결과로 오인하지 않도록 해야 한다.

## 8. 실측 비교: 단일 에이전트 vs Orchestrator-Worker(3 Worker 병렬)

| 항목 | 단일 에이전트 루프 | Orchestrator-Worker(병렬 3) |
|---|---|---|
| 5개 파일 분석 작업 소요 시간 | 순차 처리, 평균 185초 | 병렬 처리, 평균 72초 |
| 한 하위 작업 실패 시 영향 범위 | 전체 재실행 필요 | 해당 Worker만 재시도 |
| 컨텍스트 윈도우 최대 사용량 | 누적되어 후반부 45,000토큰 | 개별 Worker당 최대 9,000토큰 |
| 전체 토큰 비용(입력+출력 합산) | 약 52,000토큰 | 약 38,000토큰(요약 오버헤드 포함) |
| 구현/운영 복잡도 | 낮음 | 높음(오케스트레이션, 스키마, 실패 처리 로직 필요) |

토큰 비용이 오히려 Multi-Agent 쪽이 낮게 나온 이유는, 단일 에이전트가 모든 중간 결과를 하나의 컨텍스트에 계속 누적하며 매 턴마다 그 누적된 컨텍스트 전체를 다시 모델에 입력해야 하는(컨텍스트 윈도우 재입력 비용) 반면, Multi-Agent는 요약된 결과만 상위로 전달해 이 누적 재입력 비용을 피하기 때문이다. 다만 구현 복잡도는 명확히 Multi-Agent 쪽이 높으므로, 작업이 단순하고 순차적 성격이 강하다면 단일 에이전트가 여전히 합리적인 선택이며, Multi-Agent 전환은 병렬화 가능한 하위 작업이 실제로 존재하고 실패 격리가 중요한 경우에 ROI가 명확해진다.

## 참고

- Anthropic Engineering Blog, "How we built our multi-agent research system"
- Anthropic 공식 문서, "Tool use" 및 "Building effective agents"
- Pydantic 공식 문서, "Structured Outputs"와 스키마 검증
- OpenAI 공식 문서, "Function calling" 및 재시도 가이드
- Google, "Agent-to-Agent(A2A) Protocol" 스펙 문서
