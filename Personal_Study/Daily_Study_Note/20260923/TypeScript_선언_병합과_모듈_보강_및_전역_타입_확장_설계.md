Notion 원본: https://www.notion.so/3e45a06fd6d38194a5c2f1b76b01c588

# TypeScript 선언 병합과 모듈 보강 및 전역 타입 확장 설계

> 2026-09-23 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- 선언 공간(타입·값·네임스페이스)별 병합 규칙을 케이스로 구분한다
- `declare module` 보강과 전역 보강의 스코프 결정 조건을 파일 단위로 통제한다
- 서드파티 라이브러리 타입을 안전하게 확장하고 충돌을 진단한다
- 라이브러리 배포 시 보강 가능한 인터페이스를 설계한다

## 1. 세 개의 선언 공간

TypeScript 의 모든 선언은 세 공간 중 하나 이상에 이름을 등록한다. 병합 규칙은 이 공간 구분에서 전부 파생된다.

| 선언 | 타입 공간 | 값 공간 | 네임스페이스 공간 |
|---|---|---|---|
| `interface` | ○ | ✗ | ✗ |
| `type` | ○ | ✗ | ✗ |
| `class` | ○ | ○ | ✗ |
| `enum` | ○ | ○ | ✗ |
| `function` | ✗ | ○ | ✗ |
| `const`/`let`/`var` | ✗ | ○ | ✗ |
| `namespace` | ✗ | ○(값이 있으면) | ○ |

같은 공간에 같은 이름이 두 번 들어가면 충돌이고, 다른 공간이면 공존한다. `class Foo` 와 `interface Foo` 가 함께 쓰일 수 있는 이유는 클래스가 타입·값 양쪽에, 인터페이스가 타입에만 등록되어 **타입 공간에서 병합**되기 때문이다.

```ts
class Point {
  constructor(public x: number, public y: number) {}
}

interface Point {
  distanceTo(other: Point): number;
}

Point.prototype.distanceTo = function (other: Point): number {
  return Math.hypot(this.x - other.x, this.y - other.y);
};

const p = new Point(3, 4);
p.distanceTo(new Point(0, 0)); // 타입 OK
```

이 패턴은 런타임 프로토타입 확장을 타입에 반영할 때 쓴다. 다만 구현 책임이 타입 시스템 밖에 있어 `distanceTo` 할당을 빠뜨려도 컴파일은 통과한다. 그래서 실무에서는 애초에 클래스 메서드로 선언하고, 선언 병합은 "내가 수정할 수 없는 클래스" 에만 쓰는 것이 안전하다.

## 2. 인터페이스 병합의 정확한 규칙

같은 스코프의 동명 `interface` 는 멤버가 합쳐진다. `type` 별칭은 병합되지 않고 "Duplicate identifier" 오류가 난다. 이 차이가 라이브러리 공개 API 설계에서 `interface` 를 선호하는 핵심 근거다.

```ts
interface Config {
  host: string;
}
interface Config {
  port: number;
}
// 결과: { host: string; port: number }

type Bad = { a: string };
type Bad = { b: number }; // Error: Duplicate identifier 'Bad'
```

비함수 멤버는 이름이 겹치면 **타입이 동일해야** 한다. 다르면 오류다.

```ts
interface A { value: string }
interface A { value: number }
// Error: Subsequent property declarations must have the same type
```

함수 멤버는 오버로드로 누적된다. 순서 규칙이 중요한데, **나중에 선언된 인터페이스 블록의 오버로드가 앞에 온다**. 같은 블록 안에서는 작성 순서를 유지한다.

```ts
interface Parser {
  parse(input: string): object;
}
interface Parser {
  parse(input: Buffer): object;
}

// 실제 오버로드 해석 순서:
//   1. parse(input: Buffer): object    ← 나중 블록이 우선
//   2. parse(input: string): object
```

예외가 하나 있다. 단일 문자열 리터럴 타입을 파라미터로 받는 시그니처는 그룹 내에서 최우선으로 끌어올려진다. DOM 의 `addEventListener('click', ...)` 같은 이벤트 맵 오버로드가 정확히 이 규칙 덕분에 잘 동작한다.

제네릭 인터페이스를 병합하려면 타입 파라미터의 **이름과 개수와 제약이 모두 같아야** 한다.

```ts
interface Box<T> { value: T }
interface Box<T> { map<U>(fn: (v: T) => U): Box<U> }  // OK

interface Box<U> { other: U }  // Error: 파라미터 이름 불일치
```

## 3. 네임스페이스 병합과 정적 속성

`namespace` 는 함수·클래스·enum 과 병합되어 정적 멤버를 추가한다. 함수에 속성을 붙이는 패턴에 유용하다.

```ts
function createId(prefix: string): string {
  return `${prefix}-${createId.counter++}`;
}

namespace createId {
  export let counter = 0;
  export const RESET = (): void => {
    counter = 0;
  };
}

createId('ORD');       // 'ORD-0'
createId.counter;      // 1
createId.RESET();
```

주의할 제약이 두 가지다. 첫째, **선언 순서가 강제된다** — 함수/클래스가 네임스페이스보다 먼저 와야 한다. 둘째, `export` 하지 않은 네임스페이스 멤버는 외부에서 접근할 수 없다.

enum 병합은 여러 파일에 분산된 enum 을 합칠 때 쓰이지만, 첫 블록 이후에는 반드시 초기값을 명시해야 한다.

```ts
enum Status { Draft = 1, Active = 2 }
enum Status { Archived = 3 }   // 초기값 필수
```

`const enum` 은 병합할 수 없고, 선언 병합 대상에서 제외된다.

## 4. 모듈 보강 — `declare module`

외부 모듈의 타입을 확장하려면 모듈 보강을 쓴다. 규칙이 몇 가지 있고, 어기면 "보강" 이 아니라 "덮어쓰기" 가 되어 원본 타입이 통 사라진다.

```ts
// types/express.d.ts
import 'express';                    // ① 원본 모듈을 반드시 import

declare module 'express-serve-static-core' {
  interface Request {                // ② 기존 인터페이스와 동명
    user?: AuthenticatedUser;
    requestId: string;
  }
}

export interface AuthenticatedUser {
  id: string;
  roles: readonly string[];
}
```

①이 빠지면 TypeScript 는 이 `declare module 'express-serve-static-core'` 를 **앨비언트 모듈 선언**(그 모듈의 타입 전체를 새로 정의하는 것)으로 해석한다. 그 순간 원래 Express 타입이 전부 지워지고 `Request` 에는 방금 쓴 두 필드만 남는다. `import 'express'` 또는 파일 어딘가의 `export {}` 가 이 파일을 모듈로 만들어, 안의 `declare module` 이 보강으로 해석되게 한다.

②도 중요하다. Express 5 계열에서 `Request` 인터페이스의 실제 선언 위치는 `express` 가 아니라 `express-serve-static-core` 다. 보강 대상은 **원본이 선언된 모듈 경로**여야 한다. 잘못된 경로에 쓰면 조용히 아무 효과가 없다.

보강이 적용되려면 해당 `.d.ts` 가 컴파일에 포함되어야 한다. `include` 범위 안에 두거나 `types` 배열에 명시한다.

```json
{
  "compilerOptions": {
    "strict": true,
    "typeRoots": ["./node_modules/@types", "./types"],
    "types": ["node", "express"]
  },
  "include": ["src/**/*", "types/**/*.d.ts"]
}
```

`types` 배열을 지정하는 순간 `@types/*` 자동 포함이 꺼지고 나열한 것만 로드된다. "갑자기 `describe` 가 없다" 류의 오류가 나면 이 설정을 먼저 의심한다.

## 5. 보강으로 추가할 수 있는 것과 없는 것

모듈 보강에는 명확한 한계가 있다.

| 가능 | 불가능 |
|---|---|
| 기존 `interface` 에 멤버 추가 | 기존 `type` 별칭 수정 |
| 기존 `namespace` 에 선언 추가 | 기존 멤버의 타입 변경 |
| 새 타입/인터페이스 추가 | 새 최상위 **값** export 추가 |
| 기존 인터페이스에 오버로드 추가 | 기존 export 제거 |

"새 값을 추가할 수 없다" 는 제약은 자주 문제가 된다. 런타임에 모듈에 속성을 붙이는 monkey patch 를 타입에 반영하려면, 그 모듈이 애초에 인터페이스 기반으로 export 되어 있어야 한다.

```ts
// ✗ 불가능 — 새 값 export 추가
declare module 'lodash' {
  export function myCustomHelper(x: number): number;  // Error
}

// ○ 가능 — LoDashStatic 인터페이스에 멤버 추가
declare module 'lodash' {
  interface LoDashStatic {
    myCustomHelper(x: number): number;
  }
}
```

lodash 타입이 `declare const _: LoDashStatic` 형태로 되어 있기 때문에 인터페이스 보강이 통한다. 이것이 7절에서 다룰 "보강 가능하게 설계하기" 의 실례다.

`type` 별칭 기반 라이브러리는 확장할 수 없다. 이 경우 선택지는 모듈 전체를 감싸는 래퍼 타입을 직접 만드는 것뿐이다.

```ts
import type { OriginalOptions } from 'some-lib';

export type ExtendedOptions = OriginalOptions & {
  telemetry?: { traceId: string };
};
```

## 6. 전역 보강

`globalThis`, `Window`, `NodeJS.ProcessEnv` 같은 전역 타입 확장은 `declare global` 블록을 쓴다. 이 블록은 **모듈 파일 안에서만** 허용된다.

```ts
// types/global.d.ts
export {};  // 이 파일을 모듈로 만든다 — 없으면 declare global 이 오류

declare global {
  interface Window {
    __APP_CONFIG__: Readonly<{
      apiBaseUrl: string;
      featureFlags: Readonly<Record<string, boolean>>;
    }>;
    dataLayer: unknown[];
  }

  namespace NodeJS {
    interface ProcessEnv {
      NODE_ENV: 'development' | 'production' | 'test';
      DATABASE_URL: string;
      REDIS_URL?: string;
    }
  }

  // 전역 타입 추가 — 값이 아니라 타입만
  type Nullable<T> = T | null | undefined;
}
```

`ProcessEnv` 보강은 실용적이지만 위험도 있다. `DATABASE_URL: string` 으로 선언하면 타입 시스템은 항상 존재한다고 믿지만, 런타임에는 `undefined` 일 수 있다. 타입은 약속일 뿐 검증이 아니다. 실무에서는 부팅 시점 검증을 병행한다.

```ts
// src/config/env.ts
import { z } from 'zod';

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'production', 'test']),
  DATABASE_URL: z.string().url(),
  REDIS_URL: z.string().url().optional(),
});

export const env = envSchema.parse(process.env);
export type Env = z.infer<typeof envSchema>;
```

이 방식이면 전역 보강 없이도 `env.DATABASE_URL` 이 `string` 으로 좁혀지고, 잘못된 값이면 부팅이 즉시 실패한다. 전역 `ProcessEnv` 보강은 서드파티 코드가 `process.env` 를 직접 읽는 경우의 보조 수단으로만 쓰는 편이 낫다.

## 7. 보강 가능한 라이브러리 설계

내가 라이브러리를 만든다면, 사용자가 타입을 확장할 지점을 의도적으로 열어두어야 한다. 핵심 기법은 "빈 인터페이스를 export 하고 그것을 참조" 하는 것이다.

```ts
// my-lib/src/types.ts

/** 사용자가 모듈 보강으로 채우는 확장 지점 */
export interface CustomContext {}

/** 사용자가 등록하는 이벤트 맵 */
export interface EventMap {}

export interface RequestContext extends CustomContext {
  readonly requestId: string;
  readonly startedAt: number;
}

export interface Emitter {
  on<K extends keyof EventMap>(event: K, handler: (payload: EventMap[K]) => void): void;
  emit<K extends keyof EventMap>(event: K, payload: EventMap[K]): void;
}
```

사용자 쪽:

```ts
// app/types/my-lib.d.ts
import 'my-lib';

declare module 'my-lib' {
  interface CustomContext {
    tenantId: string;
    locale: 'ko-KR' | 'en-US';
  }

  interface EventMap {
    'order.created': { orderId: string; amount: number };
    'order.cancelled': { orderId: string; reason: string };
  }
}
```

```ts
// 사용 지점 — 완전한 타입 추론
emitter.on('order.created', (payload) => {
  payload.orderId;  // string
  payload.amount;   // number
});

emitter.emit('order.cancelled', { orderId: 'ORD-1', reason: 'user' });  // OK
emitter.emit('unknown.event', {});  // Error: keyof EventMap 에 없음
```

빈 인터페이스가 비어 있을 때의 동작을 이해해야 한다. `keyof {}` 는 `never` 이므로, 사용자가 아무것도 보강하지 않으면 `on` 의 첫 인자 타입이 `never` 가 되어 아무 이벤트도 등록할 수 없다. 이것이 의도라면 좋지만, 그렇지 않다면 기본 이벤트를 몇 개 넣어두거나 `string & {}` 로 탈출구를 만든다.

```ts
export interface Emitter {
  on<K extends keyof EventMap | (string & {})>(
    event: K,
    handler: (payload: K extends keyof EventMap ? EventMap[K] : unknown) => void
  ): void;
}
```

`string & {}` 는 리터럴 자동완성을 유지하면서 임의 문자열도 허용하는 관용구다. 순수 `string` 으로 유니온하면 리터럴이 흡수되어 자동완성이 사라진다.

`@typescript-eslint/no-empty-interface` 또는 `no-empty-object-type` 룰이 빈 인터페이스를 잡으므로, 확장 지점에는 예외 주석을 남긴다.

```ts
// eslint-disable-next-line @typescript-eslint/no-empty-object-type -- 모듈 보강 확장 지점
export interface CustomContext {}
```

## 8. 충돌 진단과 운영 규칙

보강이 여러 곳에서 이뤄지면 충돌이 생긴다. 진단 도구는 다음과 같다.

```bash
# 어떤 선언 파일들이 로드되고 있는지
npx tsc --noEmit --listFiles | grep "\.d\.ts"

# 특정 심볼의 최종 형태 확인 (선언 파일 생성)
npx tsc --declaration --emitDeclarationOnly --outDir /tmp/decl

# 왜 이 파일이 포함됐는지 추적
npx tsc --noEmit --explainFiles | grep -A 3 "express-serve-static-core"
```

모노레포에서 특히 짦은 문제는 `@types/*` 버전 중복이다. `node_modules/@types/express` 와 `packages/api/node_modules/@types/express` 가 서로 다른 버전이면 같은 이름의 인터페이스가 두 번 선언되어 병합되지 않고 충돌한다. `npm ls @types/express` 로 트리를 확인하고, 패키지 매니저의 override/resolutions 로 단일 버전으로 고정한다.

```json
{
  "pnpm": {
    "overrides": { "@types/express": "5.0.0" }
  }
}
```

팀 규칙으로 정착시킬 만한 항목들:

- 보강 파일은 `types/` 한 곳에 모으고 파일명을 대상 모듈명과 일치시킨다(`types/express.d.ts`)
- 모든 보강 파일 최상단에 대상 모듈 `import` 또는 `export {}` 를 둔다 — 린트 룰로 강제 가능
- 애플리케이션 코드에서는 `declare global` 남용을 금지하고, 진짜 전역(브라우저 주입 스크립트, 레거시 SDK)에만 허용한다
- 라이브러리 공개 타입은 `type` 이 아니라 `interface` 로 내보람다 — 사용자의 확장 여지를 남긴다
- 보강 후에는 반드시 `tsc --noEmit` 를 CI 에서 돌려 병합 결과를 검증한다

마지막 항목이 중요한 이유는, 잘못된 보강이 오류가 아니라 **무효과**로 나타나는 경우가 많기 때문이다. 모듈 경로 오타는 새로운 앨비언트 모듈 선언을 만들 뿐 오류를 내지 않는다. 보강을 추가했으면 그 타입이 실제로 적용되는지 확인하는 타입 테스트를 함께 두는 것이 안전하다.

```ts
// types/__tests__/express-augmentation.test-d.ts
import type { Request } from 'express';
import { expectTypeOf } from 'expect-type';

expectTypeOf<Request>().toHaveProperty('requestId');
expectTypeOf<Request['requestId']>().toEqualTypeOf<string>();
expectTypeOf<Request>().toHaveProperty('params');  // 원본 타입이 살아있는지
```

원본 속성(`params`) 검증을 함께 넣는 것이 핵심이다. 보강이 덮어쓰기로 변질되면 이 단언이 실패해 즉시 드러난다.

## 참고

- TypeScript Handbook, *Declaration Merging*
- TypeScript Handbook, *Modules — Ambient Modules / Module Augmentation*
- TypeScript Wiki, *Writing Declaration Files* 및 `--explainFiles` 컴파일러 옵션 문서
- DefinitelyTyped, *Contribution Guidelines* (보강 가능한 타입 설계 관행)
- microsoft/TypeScript, Issue #12607 *Module augmentation cannot add new top-level exports*
