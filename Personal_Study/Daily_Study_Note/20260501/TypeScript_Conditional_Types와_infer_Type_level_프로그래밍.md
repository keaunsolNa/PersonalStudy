Notion 원본: https://app.notion.com/p/3535a06fd6d381c990f9f5d571907567

# TypeScript Conditional Types와 infer — Type-level 프로그래밍 패턴

> 2026-05-01 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- `T extends U ? X : Y` 분배 조건부 타입의 평가 규칙과 분배가 일어나는 정확한 조건 추적
- `infer` 키워드로 함수 시그니처·튜플·문자열 리터럴을 분해하는 패턴 정리
- `Awaited`, `ReturnType`, `Parameters` 등 표준 라이브러리 헬퍼의 내부 구현 재현
- Type-level 자료구조(가변 길이 튜플 reverse, path string parser)를 직접 구현해 컴파일러 한계까지 활용

## 1. 조건부 타입의 기본 평가 모델

조건부 타입은 `T extends U ? X : Y` 형태로, 컴파일 시점에 `T`가 `U`에 할당 가능하면 `X`, 아니면 `Y`로 평가된다. 핵심은 두 가지다. 첫째, `extends`는 **할당 가능성(assignability)** 검사이지 상속 관계 검사가 아니다. 둘째, 검사 대상 타입(`T`)이 **네이키드 타입 파라미터(naked type parameter)** 이고 동시에 union이라면 자동으로 분배된다.

```ts
type IsString<T> = T extends string ? true : false;

type A = IsString<'hello'>;          // true
type B = IsString<number>;           // false
type C = IsString<string | number>;  // boolean  ← true | false 로 분배
```

분배가 일어난 이유는 `T`가 `[T]`로 감싸지지 않은 네이키드 형태이기 때문이다. 분배를 막으려면 양쪽을 튜플로 감싼다.

```ts
type IsStringStrict<T> = [T] extends [string] ? true : false;
type D = IsStringStrict<string | number>; // false (분배 안 됨)
```

이 차이가 표준 라이브러리 `Exclude<T, U>`의 동작을 결정한다. `Exclude<'a' | 'b' | 'c', 'a'>`가 `'b' | 'c'`로 평가되는 이유는 `T extends U ? never : T` 안에서 `T`가 분배되어 각 멤버를 따로 검사한 뒤 `never`를 union에서 흡수하기 때문이다. `never`는 union의 항등원이라 자동으로 사라진다.

## 2. infer로 타입 분해

`infer X`는 조건부 타입의 `extends` 절 안에서만 등장 가능하며, 패턴 매칭하듯 미지의 타입을 잡아낸다. 컴파일러는 가장 잘 들어맞는 후보를 추론한다. `ReturnType`의 표준 정의가 대표 사례다.

```ts
type ReturnType<T extends (...args: any) => any> =
    T extends (...args: any) => infer R ? R : never;

type R1 = ReturnType<() => number>;          // number
type R2 = ReturnType<(x: string) => string[]>; // string[]
```

`infer`는 위치를 바꾸면 다른 정보를 뽑는다.

```ts
type FirstParam<T> = T extends (a: infer A, ...rest: any) => any ? A : never;
type LastParam<T> = T extends (...args: [...any, infer L]) => any ? L : never;

type P1 = FirstParam<(x: string, y: number) => void>;  // string
type P2 = LastParam<(x: string, y: number) => void>;   // number
```

가변 튜플 위치에서도 `infer`를 쓸 수 있는 점이 강력하다. `[...any, infer L]`은 "마지막 원소 하나만 잡고 앞은 무시"라는 의미다. 이 패턴이 가능해진 것은 TS 4.0의 가변 인자 튜플(variadic tuple types) 도입 이후다.

## 3. Awaited 헬퍼 직접 구현

Promise 체이닝을 한 번에 풀어내는 표준 `Awaited<T>`는 재귀 조건부 타입의 정석이다. 단순한 한 단계 unwrap은 `T extends Promise<infer U> ? U : T`로 충분하지만, `Promise<Promise<number>>` 같은 중첩까지 풀려면 재귀가 필요하다. TS 4.5에서 정식 도입된 정의는 then-able 객체까지 고려한다.

```ts
type MyAwaited<T> =
    T extends null | undefined ? T :
    T extends object & { then(onfulfilled: infer F, ...args: any): any } ?
        F extends ((value: infer V, ...args: any) => any) ?
            MyAwaited<V> :
            never :
        T;

type A1 = MyAwaited<Promise<string>>;                    // string
type A2 = MyAwaited<Promise<Promise<number[]>>>;         // number[]
type A3 = MyAwaited<{ then(cb: (v: boolean) => any): any }>; // boolean
```

핵심은 `then` 메서드의 첫 인자가 `onfulfilled` 콜백이라는 PromiseLike 규약을 타입 수준에서 그대로 표현했다는 점이다. `null`/`undefined` 가드가 먼저 오는 이유는 `Promise.resolve(undefined)` 같은 사례에서 무한 재귀를 막기 위함이다.

## 4. 분배 조건부 타입 활용 — 깊은 readonly

조건부 타입 + 매핑 타입 + 재귀를 결합하면 임의 깊이 객체에 readonly를 적용할 수 있다. 라이브러리(`type-fest`의 `ReadonlyDeep`)가 사용하는 패턴이다.

```ts
type DeepReadonly<T> =
    T extends (...args: any) => any ? T :
    T extends Array<infer U> ? ReadonlyArray<DeepReadonly<U>> :
    T extends Map<infer K, infer V> ? ReadonlyMap<DeepReadonly<K>, DeepReadonly<V>> :
    T extends Set<infer U> ? ReadonlySet<DeepReadonly<U>> :
    T extends object ? { readonly [K in keyof T]: DeepReadonly<T[K]> } :
    T;

interface Config {
    db: { host: string; ports: number[] };
    cache: Map<string, { ttl: number }>;
}

type FrozenConfig = DeepReadonly<Config>;
// {
//   readonly db: { readonly host: string; readonly ports: ReadonlyArray<number> };
//   readonly cache: ReadonlyMap<string, { readonly ttl: number }>;
// }
```

함수 타입을 가장 먼저 걸러내는 이유는 함수도 `object`로 평가되기 때문이다. 함수에 readonly를 매핑하면 호출 시그니처가 깨진다. `Map`, `Set`을 별도 처리하는 이유도 동일 — 일반 객체로 매핑하면 내부 시그니처가 사라진다.

## 5. 템플릿 리터럴 + infer로 라우트 파서

TS 4.1의 템플릿 리터럴 타입은 문자열을 타입 수준에서 파싱하게 해준다. Express 라우트 `'/users/:id/posts/:postId'`에서 path parameter를 자동 추출하는 헬퍼는 다음과 같다.

```ts
type PathParams<T extends string> =
    T extends `${string}:${infer Param}/${infer Rest}` ?
        { [K in Param | keyof PathParams<`/${Rest}`>]: string } :
        T extends `${string}:${infer Param}` ?
            { [K in Param]: string } :
            {};

type P = PathParams<'/users/:id/posts/:postId/comments/:commentId'>;
// { id: string; postId: string; commentId: string }

function handler<R extends string>(route: R, fn: (params: PathParams<R>) => void) {}

handler('/users/:id/posts/:postId', (params) => {
    params.id;     // OK
    params.postId; // OK
    // params.foo  // 에러
});
```

이런 패턴은 tRPC, TanStack Router, Hono 등 최신 풀스택 프레임워크가 라우트 안전성을 보장하는 핵심이다. 런타임 코드는 단순 정규식 매칭이지만 IDE에서 자동완성과 오타 검출이 가능해진다.

## 6. 가변 튜플 reverse — 재귀의 한계

타입 수준 자료구조 조작도 가능하다. 튜플을 뒤집는 `Reverse<T>` 구현.

```ts
type Reverse<T extends readonly unknown[]> =
    T extends readonly [infer Head, ...infer Tail] ?
        [...Reverse<Tail>, Head] :
        [];

type R = Reverse<[1, 2, 3, 4, 5]>; // [5, 4, 3, 2, 1]
```

이 코드는 컴파일러 재귀 깊이 제한(기본 50, TS 4.5+ Tail-Recursion Elimination 적용 시 1000)에 부딪힐 수 있다. 100개 원소 튜플은 무난하지만, 1000개를 넘으면 `Type instantiation is excessively deep and possibly infinite` 에러가 뜬다. 타입 시스템은 만능 인터프리터가 아니라는 점을 항상 의식해야 한다.

| 작업 | 일반적 한계 | TCO 적용 시 |
|---|---|---|
| 단순 재귀 (Reverse, Length) | ~50 depth | ~1000 depth |
| 분기 재귀 (DeepClone 등) | ~25 depth | TCO 미적용 |
| 객체 키 매핑 | union 멤버 ~10000 | 동일 |

TCO가 적용되려면 재귀 호출이 조건부 타입 분기의 끝(tail position)에 와야 한다. `[...Reverse<Tail>, Head]` 처럼 spread 안에 들어가면 일부 버전에서는 TCO가 안 깨지기도 하는데, 실측이 필요하다.

## 7. 변성(variance) 어노테이션과 conditional types

TS 4.7에서 도입된 `in`/`out` 변성 어노테이션은 generic 매개변수의 공변·반공변·불변 제약을 명시한다. 조건부 타입과 결합하면 타입 안전한 옵저버 패턴 등을 명확하게 모델링할 수 있다.

```ts
interface Producer<out T> {
    produce(): T;
}

interface Consumer<in T> {
    consume(value: T): void;
}

interface Channel<in out T> {
    send(value: T): void;
    receive(): T;
}

type IsCovariant<T> = Producer<T> extends Producer<unknown> ? true : false;
type IsContravariant<T> = Consumer<T> extends Consumer<never> ? true : false;
```

이 어노테이션은 추론 정확도와 컴파일 속도 모두에 영향을 준다. 어노테이션 없이도 컴파일러가 변성을 추론하지만, 제네릭이 깊이 중첩되면 추론 비용이 폭증한다. 명시하면 캐싱이 가능해진다.

## 8. 트레이드오프와 실측

타입 수준 프로그래밍은 강력하지만 비용이 따른다.

| 항목 | 단순 타입 | Type-level 헤비 |
|---|---|---|
| `tsc --noEmit` 시간 (10만 LoC) | 8s | 25~40s |
| IDE 자동완성 응답 | <100ms | 200~800ms |
| 컴파일러 메모리 | 1GB | 3~5GB |
| 디버깅 난이도 | 낮음 | 매우 높음 |

권장 가이드라인은 다음과 같다. 라이브러리·프레임워크 경계(public API)에서는 적극적으로 활용하되, 애플리케이션 코드 내부에서는 단순한 `Pick`, `Omit`, `Partial` 수준을 넘지 않는 것이 유지보수에 유리하다. `tsc --extendedDiagnostics --traceResolution` 플래그로 어떤 타입이 비용을 잡아먹는지 측정할 수 있다.

## 9. ts-toolbelt와 type-fest 비교

서드파티 type-level 라이브러리 둘이 사실상 표준이다.

- `type-fest` (Sindre Sorhus 메인테이너): 가벼운 헬퍼 위주. `Promisable`, `RequireAtLeastOne`, `ConditionalKeys`. 컴파일 부담 적음.
- `ts-toolbelt`: type-level 함수형 라이브러리. `List.Reverse`, `Object.Paths`, `String.Split`. 표현력은 높지만 컴파일 시간 영향 큼.

대규모 프로젝트라면 `type-fest`를 기본으로 깔고, 특정 도메인(폼 빌더, 라우터)에서만 직접 작성하거나 `ts-toolbelt`를 부분 도입하는 전략이 안정적이다.

## 참고

- TypeScript Handbook: Conditional Types — https://www.typescriptlang.org/docs/handbook/2/conditional-types.html
- TypeScript 4.5 Release Notes: Awaited and Recursion Improvements — https://devblogs.microsoft.com/typescript/announcing-typescript-4-5/
- TypeScript 4.7 Release Notes: Variance Annotations — https://devblogs.microsoft.com/typescript/announcing-typescript-4-7/
- type-fest 공식 저장소 — https://github.com/sindresorhus/type-fest
- ts-toolbelt 공식 저장소 — https://github.com/millsp/ts-toolbelt
- "Type Challenges" 모음 — https://github.com/type-challenges/type-challenges
