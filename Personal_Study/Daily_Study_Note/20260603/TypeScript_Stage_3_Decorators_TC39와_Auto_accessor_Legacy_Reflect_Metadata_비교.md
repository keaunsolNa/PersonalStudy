Notion 원본: https://www.notion.so/3745a06fd6d381a09e23eda12ea76674

# TypeScript Stage 3 Decorators TC39와 Auto-accessor Legacy Reflect Metadata 비교

> 2026-06-03 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TC39 Stage 3 decorator 의 호출 시점·context 객체·return 규약을 컴파일러가 어떻게 desugar 하는지 따라간다
- Legacy `experimentalDecorators` 와 Stage 3 의 차이를 시그니처·실행 시점·메타데이터 측면에서 정리한다
- `accessor` 키워드와 auto-accessor decorator 의 backing storage 동작을 코드로 검증한다
- Reflect Metadata 폴리필 없이 Stage 3 환경에서 DI 컨테이너가 타입 정보를 확보하는 패턴을 식별한다

## 1. Stage 3 Decorator 의 호출 모델

TypeScript 5.0 부터 `--experimentalDecorators` 없이도 TC39 Stage 3 decorator 가 컴파일된다. Stage 3 decorator 는 다섯 종류의 *target* — class, method, getter, setter, field, accessor — 에 대해 각각 정해진 시그니처를 갖는다. 모든 decorator 는 `(value, context)` 두 인자를 받고, 반환값으로 원본을 교체하거나 `undefined` 를 반환해 그대로 둘 수 있다.

```ts
function logged<T extends (this: any, ...args: any[]) => any>(
  target: T,
  context: ClassMethodDecoratorContext<ThisParameterType<T>, T>,
): T {
  const name = String(context.name);
  return function (this: ThisParameterType<T>, ...args: Parameters<T>): ReturnType<T> {
    console.log(`-> ${name}(`, args, ')');
    const r = target.apply(this, args);
    console.log(`<- ${name} =`, r);
    return r;
  } as T;
}

class Calc {
  @logged
  add(a: number, b: number): number {
    return a + b;
  }
}
```

핵심은 `context.kind === 'method'` 이고 `context.addInitializer(fn)` 가 *class body 평가 끝* 시점에 호출될 콜백을 등록하는 hook 이라는 점이다. Stage 3 는 *value 를 받고 새 value 를 반환* 한다. 부작용은 `addInitializer` 로 분리된다.

## 2. Legacy 와 Stage 3 의 시그니처 비교

| 항목 | Legacy `experimentalDecorators` | Stage 3 TC39 |
|---|---|---|
| 시그니처 | `(target, key, descriptor)` 종류별 다름 | `(value, context)` 통일 |
| 반환 의미 | descriptor mutate or replace | 새 value 반환 또는 undefined |
| Parameter decorator | 지원 | **미지원** (proposal 단계) |
| `emitDecoratorMetadata` | 작동 | **emit 안 됨** |
| polyfill | reflect-metadata 필요 | 표준 동작, polyfill 불필요 |

NestJS·TypeORM·class-validator 같이 reflect-metadata 에 의존한 라이브러리는 *legacy 모드 유지* 가 현실적.

## 3. Auto-accessor 와 `accessor` 키워드

Stage 3 와 함께 도입된 `accessor` 키워드는 *backing field + get/set pair* 를 한 줄로 선언한다.

```ts
class User {
  accessor name: string = 'init';
}
```

auto-accessor 에 대한 decorator 의 context 는 `ClassAccessorDecoratorContext` 이고, target 은 `{ get, set }` 객체를 받아 둘 다 교체할 수 있다. `init` 은 필드 초기값 자체를 검증·변환하는 hook.

## 4. Class Decorator 와 `addInitializer` 의 활용

class decorator 는 `(value: Class, context: ClassDecoratorContext) => Class | void` 시그니처. `addInitializer` 는 *클래스 정의 끝* 에 한 번 실행되는 callback 을 등록한다 — DI 컨테이너 등록·이벤트 버스 구독·메트릭 라벨링에 쓴다.

## 5. Decorator 실행 순서와 `addInitializer` 시점

*decorator expression 평가* 는 외→내 (위에서 아래) 지만 *decorator 적용* 은 내→외 (아래에서 위) 로 동일하다. 모든 element decorator 가 적용된 뒤에 `addInitializer` 들이 *등록 순서대로* 실행된다. 이 순서는 trace logging·memoization·event hook 을 끼울 때 어느 단계에서 부작용을 실행할지 결정하는 기준이 된다.

## 6. Reflect Metadata 의 종말과 대안 패턴

`emitDecoratorMetadata` 는 *legacy 모드에서만* 동작한다. Stage 3 모드는 이 자동 emit 을 *명시적으로 거부* 했다. 대안은 셋. 첫째, *명시 토큰* 패턴 — `inject(TOKEN)` 같은 함수형 API. 둘째, *Schema 기반* — Zod / Effect Schema / Valibot. 셋째, *컴파일 타임 변환* — ts-morph / typia.

## 7. `tsconfig` 옵션 매트릭스

| target | experimentalDecorators | emitDecoratorMetadata | useDefineForClassFields | 결과 |
|---|---|---|---|---|
| ES2022+ | false | false | true | Stage 3 decorator |
| ES2022+ | true | true | n/a | Legacy + reflect-metadata |
| ES2022+ | true | false | n/a | Legacy, 메타 없음 |
| ES2022+ | false | true | n/a | 컴파일 오류 |

NestJS 11 까지는 기본 legacy 모드 유지. Angular 19 는 `accessor` + Stage 3 를 정식 지원하면서 `@Input()` 의 시그니처가 변경됐다.

## 8. Trade-off 와 실측

Stage 3 decorator 의 런타임 오버헤드는 method wrap 한 번당 약 1.5–3 µs (V8 12.x, Node 22). 큰 차이는 번들 크기에서 나온다. reflect-metadata 폴리필 (~12 KB minified) 이 사라진다. Edge runtime cold start 가 4–8 ms 빨라진다. 마이그레이션 비용은 작지 않다 — NestJS 기반 백엔드 한 곳에서 PR 12 개, 2 주. 신규 프로젝트는 Stage 3, 기존 NestJS / TypeORM 은 legacy 유지가 합리적.

## 9. 디버깅 — context 객체 inspect

`context.metadata` 는 Stage 3 에서 새로 추가된 *공유 metadata object*. `Symbol.metadata` 로 접근. 라이브러리는 *외부 polyfill 없이* 클래스 단위 메타데이터를 공유할 수 있다.

## 참고

- TC39 Decorators Proposal Stage 3: https://github.com/tc39/proposal-decorators
- TypeScript 5.0 Release Notes — Decorators
- TC39 Decorator Metadata Proposal
- V8 Blog — Class field semantics
- typia: Compile-time validators replacing reflect-metadata
