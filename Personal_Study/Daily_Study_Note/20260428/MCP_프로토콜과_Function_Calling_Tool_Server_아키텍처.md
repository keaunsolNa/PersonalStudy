Notion 원본: https://www.notion.so/3505a06fd6d3817e8271c7e22afc4a3f

# MCP 프로토콜과 Function Calling 비교 — Tool Server 아키텍처

> 2026-04-28 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트, Claude_Code

## 학습 목표

- LLM Function/Tool Calling의 wire format(OpenAI, Anthropic 둘 다)을 표준 JSON 스키마 단위로 비교한다.
- Model Context Protocol(MCP)이 client-host-server 분리로 해결하는 문제를 stdio/SSE 트랜스포트 단계로 추적한다.
- MCP Tool 정의와 OpenAI function 정의가 어떻게 매핑되는지 실제 JSON으로 비교한다.
- 자체 MCP server를 Spring Boot 또는 Node.js 로 만드는 최소 구현을 작성한다.

## 1. Function Calling 의 한계

OpenAI 와 Anthropic 모두 LLM이 외부 도구를 호출할 수 있게 하는 메커니즘을 제공한다. 형태는 거의 같다. 호출자(애플리케이션)가 사용 가능한 도구의 JSON Schema를 모델에게 같이 보내고, 모델이 "이 도구를 이 인자로 호출하라"는 응답을 만들면 호출자가 실행하고 결과를 다시 모델에 넘긴다. 이 패턴은 단일 애플리케이션이 자기 도구만 노출할 때는 잘 동작하지만, 다음 한계가 있다.

첫째, 도구 정의가 애플리케이션 코드에 하드코딩된다. 새 도구를 추가하려면 애플리케이션을 다시 빌드해야 한다. 둘째, 도구 카탈로그가 LLM 호출 본문에 포함되어 매 turn마다 토큰이 소모된다. 셋째, 도구 실행 자체는 애플리케이션이 책임지므로 권한·격리·관측이 일관되지 않다. 넷째, 도구를 다른 애플리케이션과 공유하려면 매번 재구현해야 한다.

## 2. MCP의 핵심 분리

MCP는 Anthropic이 2024년 말 공개한 오픈 표준이다. 핵심은 "도구를 누가 정의하고, 누가 실행하고, 누가 호출하는가"를 분리한 것이다. 세 역할:

| 역할 | 책임 |
| --- | --- |
| Host | 사용자 인터페이스(Claude Desktop, Cursor, Cowork). LLM과의 대화 흐름과 정책. |
| Client | Host가 만든 connection. 한 server 당 한 client. 라이프사이클 / 권한 / capability negotiation. |
| Server | 실제 도구·리소스·prompt를 노출. Host와 무관하게 독립 실행. |

Host는 0..N개의 server를 client로 연결해 한 묶음의 도구로 모델에 노출한다. Server는 stdio(local subprocess) 또는 SSE(원격 HTTP) 위에서 동작한다. 도구 카탈로그는 server가 소유하므로 새 도구를 추가하려면 server만 업데이트하면 된다. 호스트는 server를 재기동하면 새 도구를 자동으로 받는다.

## 3. Wire format — Function vs MCP

OpenAI tool call 응답:

```json
{
  "id": "call_abc",
  "type": "function",
  "function": {
    "name": "get_weather",
    "arguments": "{\"city\":\"Seoul\"}"
  }
}
```

Anthropic tool_use 응답 블록:

```json
{
  "type": "tool_use",
  "id": "toolu_01abc",
  "name": "get_weather",
  "input": {"city": "Seoul"}
}
```

MCP는 한 단계 더 나아가 JSON-RPC 2.0 위에서 동작한다. Host와 server 간 메시지는 모두 다음 형식이다.

```json
// Host → Server: 도구 목록 요청
{"jsonrpc":"2.0","id":1,"method":"tools/list"}

// Server → Host: 도구 카탈로그
{"jsonrpc":"2.0","id":1,"result":{
  "tools":[{
    "name":"get_weather",
    "description":"Returns current weather",
    "inputSchema":{
      "type":"object",
      "properties":{"city":{"type":"string"}},
      "required":["city"]
    }
  }]
}}

// Host → Server: 호출
{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"get_weather","arguments":{"city":"Seoul"}}}

// Server → Host: 응답
{"jsonrpc":"2.0","id":2,"result":{
  "content":[{"type":"text","text":"Seoul: 18°C, clear"}],
  "isError":false
}}
```

도구 자체의 schema는 거의 동일하다. 차이는 호스트와 서버 사이의 라이프사이클(initialize, capabilities, ping, shutdown) 메시지가 표준화되어 있다는 점이다.

## 4. Capability negotiation

연결 직후 host와 server는 capabilities를 교환한다.

```json
// Host → Server
{"jsonrpc":"2.0","id":0,"method":"initialize","params":{
  "protocolVersion":"2025-06-18",
  "capabilities":{"sampling":{},"roots":{"listChanged":true}},
  "clientInfo":{"name":"cowork","version":"1.0"}
}}

// Server → Host
{"jsonrpc":"2.0","id":0,"result":{
  "protocolVersion":"2025-06-18",
  "capabilities":{
    "tools":{"listChanged":true},
    "resources":{"subscribe":true,"listChanged":true},
    "prompts":{"listChanged":true},
    "logging":{}
  },
  "serverInfo":{"name":"weather-mcp","version":"0.3.1"}
}}
```

이 단계에서 host는 server가 어떤 기능(tools, resources, prompts, sampling)을 가졌는지 알게 된다. 이후 `notifications/tools/list_changed` 같은 비동기 알림을 받을 수도 있다.

## 5. Resources / Prompts — 도구 외 두 가지 노출 종류

MCP는 도구 외에도 `resources` 와 `prompts` 를 노출한다. resources는 LLM 컨텍스트에 끼워 넣을 수 있는 읽기 전용 데이터(파일, DB row, API 응답 캐시 등). prompts는 미리 작성된 prompt template으로 host가 사용자에게 슬래시 커맨드처럼 노출할 수 있다. 이 분리 덕분에 "한 도메인에 대한 모든 LLM-noticeable surface"를 한 server가 책임진다.

## 6. 최소 MCP server (Node.js / TypeScript)

```ts
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const server = new Server(
  { name: "weather-mcp", version: "0.3.1" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [{
    name: "get_weather",
    description: "Returns current weather for a city",
    inputSchema: {
      type: "object",
      properties: { city: { type: "string" } },
      required: ["city"],
    },
  }],
}));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  if (req.params.name !== "get_weather") throw new Error("unknown tool");
  const city = req.params.arguments?.city as string;
  const data = await fetch(`https://wttr.in/${encodeURIComponent(city)}?format=j1`).then(r => r.json());
  const cur = data.current_condition[0];
  return { content: [{ type: "text", text: `${city}: ${cur.temp_C}°C, ${cur.weatherDesc[0].value}` }] };
});

await server.connect(new StdioServerTransport());
```

이 server를 Cursor, Claude Desktop, Cowork 어디든 등록하면 같은 도구가 자동으로 노출된다.

## 7. 최소 MCP server (Spring Boot)

Spring 진영에서는 spring-ai-mcp-server starter 가 제공된다.

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
  <version>1.0.0-M3</version>
</dependency>
```

```java
@Configuration
class WeatherTools {
  @Bean
  ToolCallback getWeather(WeatherClient client) {
    return FunctionToolCallback.builder("get_weather", (Request req) -> client.fetch(req.city()))
        .description("Returns current weather for a city")
        .inputType(Request.class)
        .build();
  }
  record Request(String city) {}
}
```

starter 가 자동으로 `tools/list`, `tools/call` 핸들러를 등록한다. 트랜스포트는 application.yaml에서 stdio 또는 SSE를 선택할 수 있다.

```yaml
spring.ai.mcp.server:
  name: weather-mcp
  version: 0.3.1
  type: ASYNC
  transport: SSE   # 또는 STDIO
```

## 8. 보안 모델

MCP server는 host의 신뢰 경계 외부에 있을 수 있다. 따라서 host는 다음을 강제해야 한다. 첫째, server 별 권한 sandbox (파일 시스템 마운트 범위, 네트워크 화이트리스트). 둘째, tool 별 confirm flow — destructive 도구는 사용자 확인 후 실행. 셋째, 로깅과 감사 — 모든 tool call과 인자를 host 측에서 기록. 일부 host는 server 가 출력한 텍스트의 prompt injection 가능성도 검사한다.

## 9. Function Calling vs MCP — 의사결정 가이드

| 상황 | 권장 |
| --- | --- |
| 단일 앱, 도구 5개 미만, 사내 폐쇄 | function calling 직접 |
| 여러 앱이 같은 도구를 공유 | MCP server |
| 외부 SaaS 도구를 LLM에 연결 | MCP (Notion, Slack, GitHub 등 공식 server 다수) |
| 로컬 파일/DB 도구 | MCP stdio server |
| 사용자가 도구 set을 plug-in 형태로 켜고 끔 | MCP host의 server 토글 UI |

## 10. 한계와 대안

MCP는 wire format 표준에 가깝고, 인증/지속성/관측 표준은 아직 발달 중이다. SSE 트랜스포트의 OAuth 흐름은 2025년 들어 표준화 중이다. 또 다른 표준인 LangChain Tools, OpenAI Plugins(deprecated), Anthropic Computer Use 도구 정의는 각자 다른 추상 수준을 다룬다. 도구 표준을 채택할 때는 (1) 호스트 생태계 호환성, (2) 사내 정책(권한·로깅·격리)에 맞는지, (3) server 측 운영 부담을 함께 보아야 한다.

## 참고

- Model Context Protocol Specification (modelcontextprotocol.io/specification)
- Anthropic Engineering, "Introducing the Model Context Protocol" (2024-11)
- OpenAI Function Calling Guide (platform.openai.com/docs/guides/function-calling)
- Anthropic Tool Use Guide (docs.anthropic.com/en/docs/agents-and-tools/tool-use)
- Spring AI MCP Documentation (docs.spring.io/spring-ai/reference)
