Notion 원본: https://www.notion.so/3b85a06fd6d38196b68af408f5e728a4

# TypeScript ArkType 타입레벨 파서와 런타임 검증 및 Zod 대비 트레이드오프

> 2026-08-11 신규 주제 · 확장 대상: TypeScript (Zod · Effect Schema 런타임 검증 기학습)

## 학습 목표

- ArkType의 문자열 정의 구문이 Template Literal Types로 타입 추론되는 원리를 분석한다
- scope / type / morph / narrow API로 도메인 스키마를 구성한다
- Zod·Effect Schema와의 성능·DX·생태계 트레이드오프를 비교해 선택 기준을 세운다
- JIT 최적화된 검증 코드 생성 방식과 set-based 타입 시스템의 동작을 이해한다

## 1. ArkType의 위치 — "TypeScript 구문 그 자체를 런타임으로"

Zod는 메서드 체이닝(`z.string().min(1)`), Effect Schema는 조합자(`Schema.Struct`)로 스키마를 만든다. ArkType의 접근은 다르다 — **TypeScript 타입 구문과 1:1인 문자열**을 그대로 정의로 쓴다:

```ts
import { type } from "arktype";

const User = type({
	name: "string",
	age: "number.integer >= 0",
	email: "string.email",
	tags: "string[]",
	role: "'admin' | 'member'",
	"nickname?": "string"          // optional은 키에 ? 표기
});

// 추론된 타입: { name: string; age: number; tags: string[]; ... }
type User = typeof User.infer;

const out = User({ name: "kean", age: 34, email: "a@b.c", tags: [], role: "member" });
if (out instanceof type.errors) {
	console.error(out.summary);    // 사람이 읽을 수 있는 에러 요약
} else {
	out.name;                       // 완전히 narrow된 User
}
```

`"number.integer >= 0"` 같은 문자열이 컴파일 타임에 파싱되어 정확한 타입으로 추론된다는 점이 핵심이다. 이것은 기학습한 Template Literal Types 재귀 파싱의 **가장 대규모 실전 적용 사례**다. ArkType은 타입 레벨에서 완전한 정의 언어 파서를 구현했고, 오타(`"strig"`)를 내면 런타임이 아니라 **에디터에서 즉시** `'strig' is unresolvable` 형태의 에러가 hover에 표시된다.

## 2. 타입레벨 파서의 내부 — 재귀 조건부 타입으로 만든 컴파일러

ArkType 저장소의 `type/parser` 디렉터리는 사실상 타입 레벨로 작성된 재귀 하강 파서다. 원리를 축소 재현하면 다음과 같다:

```ts
// 축소판: "T[]"와 "A | B"를 파싱하는 타입 레벨 파서
type Keywords = { string: string; number: number; boolean: boolean };

type ParseDef<S extends string> =
	S extends `${infer L} | ${infer R}` ? ParseDef<L> | ParseDef<R>
	: S extends `${infer Inner}[]`      ? ParseDef<Inner>[]
	: S extends `'${infer Lit}'`        ? Lit
	: S extends keyof Keywords          ? Keywords[S]
	: never;

type A = ParseDef<"string[]">;            // string[]
type B = ParseDef<"'a' | 'b' | number">;  // "a" | "b" | number
```

실물 파서는 여기에 연산자 우선순위, 괄호 그룹, 제약 비교(`>=`, `<`, `%`), 키워드 서브타입(`string.email`)까지 처리한다. 중요한 설계 포인트는 **에러를 never로 삼키지 않는다**는 것이다. 파싱 실패 시 `type.errors` 브랜드가 붙은 문자열 리터럴 타입을 반환해, 잘못된 정의가 대입 불가능한 타입이 되면서 에러 메시지 자체가 hover에 노출된다. 기학습한 "인스턴스화 폭발" 관점에서 보면 이런 파서는 위험해 보이지만, ArkType은 파싱 결과를 스코프 단위로 캐싱하고 tail-recursive 패턴으로 작성해 대형 스키마에서도 tsserver 응답성을 유지한다 — 그래도 정의 문자열이 극단적으로 길면 checker 부하가 커지므로, 거대 스키마는 `scope`로 분할하는 것이 공식 권장이다.

## 3. set-based 타입 시스템 — 교차·차집합을 아는 런타임

Zod의 스키마는 "검증 함수의 파이프라인"이고 두 스키마의 관계를 묻는 연산이 없다. ArkType의 내부 표현(`@ark/schema`)은 타입을 **집합(set)** 으로 모델링한다. 모든 타입은 정규화된 노드 트리로 컴파일되고, 노드끼리 교집합·포함 관계를 계산할 수 있다:

```ts
const Even = type("number % 2");
const Positive = type("number > 0");

const EvenPositive = Even.and(Positive);   // number % 2 & > 0 — 제약이 병합된 단일 노드
const Never = type("string").and("number"); // ParseError: intersection results in never

// 스키마 간 포함 관계 판정
EvenPositive.extends(Positive);            // true — 런타임에서 서브타입 질의
```

교차가 `never`가 되는 모순된 스키마를 **정의 시점에** 던져 준다는 것은 TypeScript 타입 시스템의 assignability 판정을 런타임에도 재현했다는 뜻이다. 이 성질 덕분에 스키마 마이그레이션에서 "새 스키마가 옛 스키마의 슈퍼셋인가"를 코드로 검사하는, Zod에서는 불가능한 호환성 테스트를 만들 수 있다.

## 4. morph와 narrow — 변환과 커스텀 검증

Zod의 `transform`/`refine`에 대응하는 것이 `pipe`(morph)와 `narrow`다.

```ts
import { type } from "arktype";

// morph: 검증 후 변환 — 입력 타입과 출력 타입이 분리 추론된다
const DateFromString = type("string.date.parse");   // 내장 morph 키워드
const Trimmed = type("string").pipe((s) => s.trim());

const Form = type({
	birth: DateFromString,        // in: string, out: Date
	name: Trimmed
});
type FormIn = typeof Form.inferIn;    // { birth: string; name: string }
type FormOut = typeof Form.infer;     // { birth: Date; name: string }

// narrow: 타입은 그대로, 술어만 추가
const Password = type("string >= 8").narrow((s, ctx) =>
	/[0-9]/.test(s) ? true : ctx.mustBe("a password containing a digit")
);
```

`inferIn`/`infer`의 분리는 Effect Schema의 `Schema<A, I>` 양방향 모델과 같은 문제의식이다. 직렬화 경계(HTTP JSON → 도메인 객체)에서 입력 표현과 도메인 표현이 다를 때, 파이프라인 전후 타입이 모두 정적으로 추적된다. 단, Effect Schema처럼 **역방향(encode) 변환을 자동 제공하지는 않는다** — morph는 단방향이며, 응답 직렬화가 필요하면 별도 스키마를 정의해야 한다. 이 지점이 Effect Schema 대비 명확한 기능 열세다.

## 5. scope — 순환 참조와 도메인 모듈화

실무 도메인 모델은 상호 참조가 흔하다. ArkType은 `scope`로 이름 공간을 만들어 순환 정의를 지원한다:

```ts
import { scope } from "arktype";

const domain = scope({
	User: {
		id: "string.uuid",
		posts: "Post[]"
	},
	Post: {
		id: "string.uuid",
		author: "User",           // 순환 참조
		"parent?": "Post"          // 자기 참조
	}
}).export();

type User = typeof domain.User.infer;   // posts: Post[] 재귀 타입으로 추론
```

스코프 내 이름은 서로를 키워드처럼 참조하고, 추론도 재귀 타입으로 정확히 떨어진다. Zod에서 같은 일을 하려면 `z.lazy()`와 수동 타입 어노테이션(`z.ZodType<User>`)이 필요해서 추론이 한 단계 끊기는 것과 대비된다. 스코프는 또한 파서 캐시의 경계라서, 앞서 언급한 tsserver 부하 관리 단위이기도 하다.

## 6. 성능 — precompiled JIT 검증과 실측 벤치마크

ArkType이 내세우는 핵심 수치는 "Zod 대비 최대 100x 빠른 검증"이다. 원리는 스키마 노드 트리를 순회 인터프리터로 검증하는 대신, **스키마별 최적화된 검증 함수 소스를 생성해 `new Function`으로 컴파일(JIT)** 하는 것이다. 객체 스키마라면 프로퍼티 접근이 하드코딩된 단일 함수가 나오므로 다형성 디스패치와 클로저 체인이 사라진다.

공정한 비교를 위해 조건을 명시하면: (1) Zod 4도 상당한 성능 개선이 있었으므로 "100x"는 Zod 3 시절 수치가 섞여 있다. (2) `new Function`은 CSP(Content-Security-Policy)가 `unsafe-eval`을 막는 환경(일부 브라우저 임베드, Cloudflare Workers 기본 설정)에서 동작하지 않아 인터프리터 폴백으로 떨어지고, 이때 성능 이점이 크게 줄어든다. (3) 검증이 전체 요청 처리에서 차지하는 비중이 작다면(대부분의 CRUD API) 라이브러리 선택을 성능만으로 정당화하기 어렵다. 직접 계측하려면:

```ts
// 간단 마이크로벤치 골격 (tinybench)
import { Bench } from "tinybench";
import { z } from "zod";
import { type } from "arktype";

const zodUser = z.object({ name: z.string(), age: z.number().int().nonnegative() });
const arkUser = type({ name: "string", age: "number.integer >= 0" });
const data = { name: "kean", age: 34 };

const bench = new Bench({ time: 1000 });
bench.add("zod", () => zodUser.safeParse(data));
bench.add("arktype", () => arkUser(data));
await bench.run();
console.table(bench.table());
```

수만 rps를 처리하는 게이트웨이의 요청 검증, 대량 배치의 레코드 검증처럼 검증 자체가 hot path인 경우에 ArkType의 JIT가 실질 이득을 준다.

## 7. Zod · Effect Schema · ArkType 선택 매트릭스

| 축 | Zod 4 | Effect Schema | ArkType 2 |
| --- | --- | --- | --- |
| 정의 스타일 | 메서드 체이닝 | 조합자 (함수형) | TS 구문 문자열 |
| 추론 정밀도 | 높음 | 높음 (A/I 양방향) | 높음 (in/out 분리) |
| 순환 참조 | z.lazy + 수동 타입 | Schema.suspend | scope로 자연 지원 |
| encode(역변환) | 없음 | 있음 | 없음 |
| 검증 성능 | 보통~양호 | 보통 | JIT로 최상위 |
| CSP 제약 환경 | 무관 | 무관 | eval 불가 시 폴백 |
| 생태계(어댑터) | 압도적 (tRPC·RHF 등) | Effect 생태계 중심 | 성장 중 (standard-schema) |
| 스키마 간 연산 | 없음 | 제한적 | extends/and 등 집합 연산 |

생태계 격차는 **Standard Schema** 명세(Zod·Valibot·ArkType 등이 공동 채택한 공통 인터페이스)로 상당 부분 해소됐다. tRPC, TanStack Form, Hono 등 standard-schema를 받는 프레임워크에서는 세 라이브러리를 같은 자리에 꾂을 수 있으므로, "tRPC가 Zod만 지원해서"라는 이유는 더 이상 성립하지 않는다. 판단 기준은 (1) 팀이 문자열 DSL의 학습 비용을 수용하는가, (2) 검증이 성능 병목인가, (3) encode가 필요한가(필요하면 Effect Schema), (4) 스키마 호환성 질의 같은 고급 기능이 필요한가로 정리된다.

## 8. 실무 통합 — Express/NestJS 경계 검증과 에러 응답 설계

Spring의 Bean Validation(`@Valid`)에 대응하는 계층을 ArkType으로 구성하면 다음과 같다:

```ts
import { type, type Type } from "arktype";
import type { RequestHandler } from "express";

const validate = <T extends Type>(schema: T): RequestHandler =>
	(req, res, next) => {
		const out = schema(req.body);
		if (out instanceof type.errors) {
			// out은 배열형 컴렉션 — path·expected·actual 구조화 제공
			res.status(400).json({
				code: "VALIDATION_FAILED",
				errors: out.map((e) => ({
					path: e.path.join("."),
					message: e.message      // "age must be at least 0 (was -1)"
				}))
			});
			return;
		}
		req.body = out;               // morph 적용된 out 타입으로 교체
		next();
	};

const CreateUser = type({ name: "string >= 1", age: "number.integer >= 0" });
app.post("/users", validate(CreateUser), handler);
```

에러 객체가 `path`, `expected`, `actual`, `message`로 구조화되어 있고 기본 메시지 품질이 좋다는 점("must be an integer (was 3.5)")이 실무 체감 강점이다. 다국어 에러가 필요하면 `.configure({ message })`로 노드 단위 메시지를 오버라이드한다. 마이그레이션 전략으로는 standard-schema 인터페이스에 의존하도록 미들웨어를 먼저 추상화한 뒤, hot path 스키마부터 Zod → ArkType으로 교체하는 점진 경로가 안전하다.

## 9. 한계와 리스크 평가

도입 전 인지해야 할 리스크를 정리한다. 첫째, 문자열 DSL은 grep·코드모드 도구와의 궁합이 나쁘다 — `"number > 0"`을 리팩터링할 때 TS 리네임이 돕지 못한다. 둘째, 커뮤니티 규모가 Zod 대비 작아 엣지 케이스 문서·스택오버플로 축적이 얇고, 메이저 버전(1→2)에서 API가 크게 바뀜 전력이 있다. 셋째, 타입레벨 파서의 특성상 TypeScript 버전 업그레이드에 대한 결합도가 높다 — 신규 TS 버전에서 추론 회귀가 발생하면 라이브러리 패치를 기다려야 한다. 넷째, 앞서 언급한 CSP 환경 폴백. 이 리스크들을 수용할 수 있고 검증 성능·추론 DX가 중요한 프로젝트라면 유력한 선택지이고, 보수적 팀이라면 Zod 4 + standard-schema 추상화로 이행 옵션만 열어 두는 것이 합리적이다.

## 참고

- ArkType 공식 문서: https://arktype.io/
- arktypeio/arktype (GitHub): https://github.com/arktypeio/arktype
- Standard Schema 명세: https://standardschema.dev/
- Zod 4 문서: https://zod.dev/
- Effect Schema 문서: https://effect.website/docs/schema/introduction/
