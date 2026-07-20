Notion 원본: https://app.notion.com/p/3a35a06fd6d3812e819cce05793080eb

# TypeScript Template Literal Types와 문자열 파싱 및 재귀 추론

> 2026-07-21 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- Template Literal Types 의 기본 구성과 유니언 분배(cross product) 동작을 설명한다
- infer 를 문자열 패턴에 결합해 타입 레벨에서 문자열을 파싱한다
- 재귀 조건부 타입으로 split·trim·replace 유틸리티를 타입으로 구현한다
- 실전 라우트 파라미터·이벤트 키 추론에 적용하고 성능 한계를 이해한다

## 1. Template Literal Types 의 기본

백틱 안에 문자열 리터럴 타입을 `${}` 로 끼워 넣으면 새 리터럴 타입이 만들어진다. `${}` 자리에 유니언이 오면 모든 조합으로 분배된다.

```typescript
type Lang = "ko" | "en";
type Kind = "info" | "error";
type EventName = `${Lang}_${Kind}`;
// "ko_info" | "ko_error" | "en_info" | "en_error"
```

## 2. 내장 문자열 조작 유틸리티

`Uppercase`, `Lowercase`, `Capitalize`, `Uncapitalize` 네 intrinsic 타입을 키 리매핑과 결합한다.

```typescript
type Accessors<T> = {
	[K in keyof T & string as `get${Capitalize<K>}`]: () => T[K];
};
// { getName: () => string; getAge: () => number }
```

## 3. infer 로 문자열 패턴 매칭

```typescript
type ParseKV<S extends string> =
	S extends `${infer K}=${infer V}` ? { key: K; value: V } : never;
type KV = ParseKV<"lang=ko">; // { key: "lang"; value: "ko" }
```

## 4. 재귀 조건부 타입으로 Split

```typescript
type Split<S extends string, D extends string> =
	S extends `${infer Head}${D}${infer Tail}`
		? [Head, ...Split<Tail, D>]
		: [S];
type Parts = Split<"a.b.c.d", ".">; // ["a", "b", "c", "d"]
```

튜플 스프레드로 재귀 결과를 이어 붙인다. 이것이 타입 레벨 문자열 처리의 핵심 관용구다.

## 5. Trim 과 Replace

```typescript
type Whitespace = " " | "\n" | "\t";
type TrimLeft<S extends string> =
	S extends `${Whitespace}${infer Rest}` ? TrimLeft<Rest> : S;
type ReplaceAll<S extends string, From extends string, To extends string> =
	From extends ""
		? S
		: S extends `${infer L}${From}${infer R}`
			? `${L}${To}${ReplaceAll<R, From, To>}`
			: S;
```

`From extends ""` 가드가 없으면 빈 문자열 치환에서 무한 재귀에 빠진다.

## 6. 실전 — 라우트 파라미터 추론

```typescript
type PathParams<Path extends string> =
	Path extends `${string}:${infer Param}/${infer Rest}`
		? Param | PathParams<`/${Rest}`>
		: Path extends `${string}:${infer Param}`
			? Param
			: never;
type RouteParams<Path extends string> = { [K in PathParams<Path>]: string };
```

경로 문자열 하나로 핸들러 파라미터 타입을 강제하고 tRPC 등이 이를 활용한다.

## 7. 재귀 깊이 한계와 성능

재귀 깊이가 약 50단계를 넘으면 "Type instantiation is excessively deep (2589)" 오류가 난다. 누산기 패턴으로 꼬리 재귀를 유도한다.

```typescript
type SplitAcc<S extends string, D extends string, Acc extends string[] = []> =
	S extends `${infer Head}${D}${infer Tail}`
		? SplitAcc<Tail, D, [...Acc, Head]>
		: [...Acc, S];
```

타입 계산은 컴파일 시간을 늘리므로 공개 API·라우터·스키마 경계에만 국한하는 절제가 필요하다.

## 8. 디버깅과 실전 조언

중간 결과를 헬퍼 타입으로 쫪개 호버로 확인하고 `Prettify` 로 평탄화한다. infer 패턴 매칭 + 재귀 조건부 타입 + 튜플 스프레드의 조합이 문자열 처리를 타입으로 구현하는 기반이다.

## 참고

- TypeScript Handbook: Template Literal Types
- TypeScript 4.1/4.5 릴리스 노트
- type-challenges 저장소의 문자열 처리 챌린지
- TypeScript 컴파일러 instantiationDepth 상한 논의
