Notion 원본: https://app.notion.com/p/3bb5a06fd6d3815fb2f5f20a21d39f91?pvs=204

# TypeScript JSX 타입 시스템과 Polymorphic Component as Prop 추론

> 2026-08-13 신규 주제 · 확장 대상: TypeScript, React

## 학습 목표

- `JSX.Element`, `JSX.ElementType`, `JSX.LibraryManagedAttributes` 가 JSX 검사 파이프라인에서 맡는 역할을 구분한다.
- `@types/react` 19 의 JSX 네임스페이스 이동과 `ReactNode`·`ref` 변경이 기존 코드에 미치는 영향을 진단한다.
- `ElementType` + `ComponentPropsWithoutRef` 로 `as` prop 다형 컴포넌트를 타입 안전하게 구현한다.
- `jsx`/`jsxImportSource` 옵션별 변환 결과와 제네릭 컴포넌트의 추론 실패 지점을 우회한다.

## 1. 컴파일러가 JSX 를 검사하는 순서

TypeScript 에게 JSX 는 문법 설탕이 아니라 별도의 검사 단계를 가진 1급 구문이다. `<Foo bar={1} />` 를 만나면 컴파일러는 세 단계를 밟는다. 태그가 소문자면 내장 요소로 보고 `JSX.IntrinsicElements` 의 동명 프로퍼티를 찾고, 대문자거나 점 표기면 값 참조로 해석해 스코프에서 식별자를 찾는다. 다음으로 그 값이 요소로 쓰일 수 있는지 검사하고, 마지막으로 속성 객체 타입을 만들어 실제 전달값과 비교한다. 각 단계는 `JSX` 네임스페이스의 별도 타입에 위임된다. 즉 검사 규칙은 언어에 하드코딩된 것이 아니라 타입 정의로 주입되는 프로토콜이며, React·Preact·Solid 가 같은 문법에 다른 의미를 줄 수 있는 이유가 여기에 있다.

```tsx
const a = <div className="box" />; // JSX.IntrinsicElements['div'] 조회

function Card(props: { title: string }) {
  return <h2>{props.title}</h2>;
}
const b = <Card title="hi" />; // 값 Card 를 찾고 요소 적격성 검사

// const c = <nosuchtag />;
// Property 'nosuchtag' does not exist on type 'JSX.IntrinsicElements'.
```

웹 컴포넌트는 선언 병합으로 내장 요소 목록을 늘려 쓴다.

```tsx
// custom-elements.d.ts — @types/react 19 에서는 declare global 이 아니라 이 형태다
import type { HTMLAttributes } from 'react';

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'my-badge': HTMLAttributes<HTMLElement> & { label?: string };
    }
  }
}
```

## 2. JSX.Element 와 요소 타입의 실체

`JSX.Element` 는 JSX 표현식 하나의 결과 타입으로, React 정의에서는 `React.ReactElement` 를 확장한 빈 인터페이스다. 자주 걸려 넘어지는 지점은 이 타입이 컴포넌트 반환 타입으로는 너무 좁다는 것이다. 컴포넌트는 `null`, 문자열, 숫자, 배열, `Promise`(서버 컴포넌트)를 반환할 수 있는데 `JSX.Element` 는 요소 하나만 표현한다.

```tsx
import type { ReactNode } from 'react';

// 반환 타입이 JSX.Element 면 null 을 못 돌려줘 조건부 렌더링에서 막힌다
// function Bad(p: { count: number }): JSX.Element {
//   return p.count === 0 ? null : <span>{p.count}</span>; // 오류
// }

// 추론에 맡기거나 ReactNode 로 넓힌다
function Badge({ count }: { count: number }) {
  return count === 0 ? null : <span>{count}</span>;
}

function List({ items }: { items: string[] }): ReactNode {
  return items.map((i) => <li key={i}>{i}</li>);
}
```

반환 타입은 추론에 맡기고, 명시해야 한다면 `ReactNode` 를 쓴다. `React.FC` 는 제네릭 표현이 어색해지고 `as` prop 패턴과 충돌한다.

## 3. JSX.ElementType 과 LibraryManagedAttributes

`JSX.ElementType` 은 "무엇이 태그 자리에 올 수 있는가"를 정의한다. TypeScript 5.1 에서 이 확장점이 도입되기 전에는 컴포넌트 반환값이 반드시 `JSX.Element` 에 할당 가능해야 한다는 규칙이 컴파일러에 박혀 있었다. 이제는 라이브러리가 조건을 직접 선언하므로 `Promise` 를 반환하는 비동기 서버 컴포넌트도 오류 없이 쓸 수 있다.

```tsx
declare function fetchUser(id: string): Promise<{ name: string }>;

async function UserCard({ id }: { id: string }) {
  const user = await fetchUser(id);
  return <div>{user.name}</div>;
}
const node = <UserCard id="u1" />; // 반환이 Promise 여도 태그로 허용된다
```

`JSX.LibraryManagedAttributes<Component, Props>` 는 최종 요구 속성을 계산하는 훅으로, React 정의에서는 `defaultProps` 로 채워지는 속성을 선택적으로 만든다.

```tsx
import { Component } from 'react';

class LegacyButton extends Component<{ label: string; variant: 'solid' | 'ghost' }> {
  static defaultProps = { variant: 'ghost' as const };
  render() {
    return <button data-variant={this.props.variant}>{this.props.label}</button>;
  }
}
const ok = <LegacyButton label="Save" />; // variant 생략 가능
```

이 특별 대우는 함수 컴포넌트에서 사라졌다. `@types/react` 19 타입에서도 빠졌으므로 기본값은 매개변수 구조 분해로 표현해야 한다.

자식 프로퍼티의 이름조차 `JSX.ElementChildrenAttribute` 가 결정하며, React 가 이를 `children` 으로 선언해 둔 것뿐이다.

## 4. React 18에서 19로 갈 때 깨지는 것들

가장 광범위한 변경은 전역 `JSX` 네임스페이스가 `react` 모듈 안으로 이동한 것이다. 전역 네임스페이스는 여러 JSX 런타임이 공존할 때 충돌했고, 모듈 스코프에서는 `jsxImportSource` 별 규칙을 가질 수 있다. `JSX.Element` 를 import 없이 쓰던 코드는 전부 수정 대상이다.

```tsx
// 18 스타일 — 19 에서는 'Cannot find namespace JSX' 가 될 수 있다
// function A(): JSX.Element { return <div />; }

import type { JSX, ReactNode } from 'react'; // 19 스타일 1: 타입 직접 import

function A(): JSX.Element { return <div />; }
function B(): ReactNode { return <div />; } // 19 스타일 2: 아예 쓰지 않기
```

둘째로 `ReactNode` 에서 `{}` 가 빠졌다. 18 정의의 `ReactFragment` 가 `{}` 를 포함해 사실상 거의 모든 값이 할당 가능했는데, 19 에서 그 구멍이 막히며 임의 객체를 자식으로 넘기던 코드가 오류를 낸다. 오래된 버그의 수정이지만 마이그레이션 시점에는 새 오류로 체감된다. 셋째로 `ref` 가 일반 prop 이 되어 `forwardRef` 없이 받을 수 있고, `useRef` 는 인자를 요구하도록 바뀌었다.

```tsx
import { useRef } from 'react';

type InputProps = React.ComponentPropsWithoutRef<'input'> & {
  ref?: React.Ref<HTMLInputElement>;
};

function TextInput({ ref, ...rest }: InputProps) { // forwardRef 불필요
  return <input ref={ref} {...rest} />;
}

function Form() {
  const ref = useRef<HTMLInputElement | null>(null); // 19 에서 인자 필수
  return <TextInput ref={ref} placeholder="name" />;
}
```

암묵적 `children` 제거는 18 타입에서 이미 일어났다. `React.FC<P>` 가 이제 `children` 을 자동 포함하지 않는다.

| 항목 | @types/react 18 | @types/react 19 |
| --- | --- | --- |
| `JSX` 네임스페이스 | 전역(global) | `react` 모듈 내부 |
| `ReactNode` 의 `{}` | 포함(사실상 any 유사) | 제외 |
| 함수 컴포넌트 `ref` | `forwardRef` 필요 | 일반 prop 수용 |
| `useRef()` 무인자 | 허용 | 인자 필수 |
| 함수 컴포넌트 `defaultProps` | 타입상 존재 | 제거 |

## 5. as prop 다형 컴포넌트의 기본 골격

다형 컴포넌트의 타입 목표는 두 가지다. `as` 로 지정한 요소가 실제 받는 속성만 허용해야 하고(`as="a"` 면 `href` 허용, `as="button"` 이면 거부), 이름이 겹치면 자체 props 정의가 이겨야 한다. 재료는 `ElementType`, `ComponentPropsWithoutRef<T>`, 겹치는 키를 제거하는 `Omit` 이다.

```tsx
import type { ComponentPropsWithoutRef, ElementType, ReactNode } from 'react';

type AsProp<T extends ElementType> = { as?: T };
type PropsToOmit<T extends ElementType, P> = keyof (AsProp<T> & P);

type PolymorphicProps<T extends ElementType, P = object> = P &
  AsProp<T> &
  Omit<ComponentPropsWithoutRef<T>, PropsToOmit<T, P>>;

type TextOwnProps = { size?: 'sm' | 'md' | 'lg'; children?: ReactNode };

function Text<T extends ElementType = 'span'>({
  as,
  size = 'md',
  ...rest
}: PolymorphicProps<T, TextOwnProps>) {
  const Tag = (as ?? 'span') as ElementType;
  return <Tag data-size={size} {...rest} />;
}

const t1 = <Text>plain</Text>;
const t2 = <Text as="a" href="/docs" size="lg">docs</Text>;
// <Text as="button" href="/x" /> 는 오류: button 에 href 없음
```

`as as ElementType` 캐스팅이 필요한 이유가 중요하다. 제네릭 `T` 는 함수 본문 안에서 미확정 파라미터라 컴파일러가 `<Tag {...rest} />` 를 검사할 정보가 없다. 호출부에서는 `T` 가 구체화된 뒤 검사되므로, 이 패턴은 구현 내부의 안전성을 조금 내주고 호출부의 정밀한 검사를 얻는 거래다. 기본값 `= 'span'` 도 필수에 가깝다. 없으면 `as` 생략 시 `T` 가 `ElementType` 전체로 추론되어 속성 검사가 무의미해진다.

## 6. ref 까지 지원하는 다형 컴포넌트

`as` 에 따라 ref 인스턴스 타입도 달라져야 하며, 이 정보는 `ComponentPropsWithRef<T>['ref']` 로 뽑는다.

```tsx
import type { ComponentPropsWithRef, ElementType } from 'react';

type PolymorphicRef<T extends ElementType> = ComponentPropsWithRef<T>['ref'];
type PolymorphicPropsWithRef<T extends ElementType, P = object> =
  PolymorphicProps<T, P> & { ref?: PolymorphicRef<T> };

function Box<T extends ElementType = 'div'>({
  as,
  ref,
  ...rest
}: PolymorphicPropsWithRef<T, { padded?: boolean }>) {
  const Tag = (as ?? 'div') as ElementType;
  return <Tag ref={ref} {...rest} />;
}

const anchorRef = { current: null } as React.RefObject<HTMLAnchorElement>;
const ok1 = <Box as="a" ref={anchorRef} href="/a" />;
// const ng = <Box as="button" ref={anchorRef} />; // ref 타입 불일치로 오류
```

React 18 이하에서는 `forwardRef` 가 `ForwardRefExoticComponent` 라는 구체 타입을 돌려주므로 제네릭 호출 시그니처를 유지하지 못하고 `T` 를 소거한다. 표준 우회법은 반환값을 원하는 시그니처로 재선언하는 것이다.

```tsx
import { forwardRef } from 'react';

type BoxComponent = <T extends ElementType = 'div'>(
  props: PolymorphicPropsWithRef<T, { padded?: boolean }>,
) => React.ReactNode;

const Box18 = forwardRef(function BoxInner(
  { as, padded, ...rest }: PolymorphicProps<ElementType, { padded?: boolean }>,
  ref: React.Ref<Element>,
) {
  const Tag = (as ?? 'div') as ElementType;
  return <Tag ref={ref} data-padded={padded} {...rest} />;
}) as BoxComponent;
```

`as BoxComponent` 단언은 불가피하지만 라이브러리 경계 한 곳에 격리되고 호출부는 정확한 검사를 받는다. React 19 에서는 이 우회 자체가 사라진다. 한편 `key` 는 예외다. props 에 포함되지 않고 React 가 요소 생성 시점에 가로채는 예약 속성이며 타입 정의에서는 `Attributes` 인터페이스로 모든 JSX 요소에 주입되므로, props 타입에 선언할 필요가 없고 `ComponentProps<T>` 결과에도 나타나지 않는다.

## 7. jsx 컴파일러 옵션과 자동 런타임

`jsx` 옵션은 JSX 를 어떤 코드로 낮출지 결정하며, 값에 따라 필요한 import 가 달라진다.

| `jsx` 값 | 출력 | 특징 |
| --- | --- | --- |
| `preserve` | JSX 그대로 | 번들러가 이어서 변환 |
| `react` | `React.createElement(...)` | 파일마다 React import 필요 |
| `react-jsx` | `_jsx(...)` + `react/jsx-runtime` | React import 불필요 |
| `react-jsxdev` | `_jsxDEV(...)` + `react/jsx-dev-runtime` | 소스 위치 등 개발 정보 포함 |

자동 런타임에서는 컴파일러가 `import { jsx as _jsx } from 'react/jsx-runtime'` 를 넣어주므로 `import React from 'react'` 가 필요 없다. 다만 `useState` 같은 값은 여전히 명시적으로 import 해야 한다. 또 하나의 차이는 `key` 처리다. 클래식에서는 `key` 가 props 객체에 들어가지만, 자동 런타임은 `jsx(type, props, key)` 처럼 세 번째 인자로 분리하고 자식이 여럿이면 `jsxs` 를 호출한다.

```jsonc
// tsconfig.json
{
  "compilerOptions": {
    "jsx": "react-jsx",
    "jsxImportSource": "react", // preact 로 바꾸면 preact/jsx-runtime 사용
    "moduleResolution": "bundler",
    "strict": true
  }
}
```

`jsxImportSource` 는 파일 단위 프라그마로 덮어쓸 수 있어 한 프로젝트에서 여러 런타임을 섞을 수 있다. Emotion 의 `css` prop 이나 Preact 혼용이 대표적이다.

```tsx
/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';

export const Highlighted = () => <span css={css({ color: 'crimson' })}>hi</span>;
```

이 프라그마가 있으면 해당 파일의 `JSX` 네임스페이스도 Emotion 쪽 정의를 따른다. 전역 네임스페이스였다면 파일마다 런타임이 달라도 검사는 하나의 정의를 공유해 `css` prop 이 React 전용 파일에서도 허용되는 부정확한 결과가 나온다.

## 8. 제네릭 컴포넌트의 추론 한계와 우회

제네릭 컴포넌트의 가장 흔한 문제는 여러 prop 에서 동시에 추론이 일어날 때 원하는 방향으로 좁혀지지 않는 것이다.

```tsx
type SelectProps<T> = {
  items: readonly T[];
  value: T;
  onChange: (next: T) => void;
};

function Select<T>({ items, value, onChange }: SelectProps<T>) {
  return (
    <ul>
      {items.map((item, i) => (
        <li key={i} onClick={() => onChange(item)} aria-selected={item === value}>
          {String(item)}
        </li>
      ))}
    </ul>
  );
}
const fruits = ['apple', 'banana'] as const;
// as const 덕분에 T 가 'apple' | 'banana' 로 좁혀진다
const s1 = <Select items={fruits} value="apple" onChange={(v) => console.log(v)} />;

type Row = { id: number; name: string };
const rows: Row[] = [{ id: 1, name: 'a' }];
// 추론이 실패하면 JSX 에서도 타입 인자를 명시할 수 있다
const s2 = <Select<Row> items={rows} value={rows[0]} onChange={(r) => r.name} />;
```

`as const` 가 없으면 `items` 가 `string[]` 으로 넓어져 `T = string` 이 되고 콜백 인자도 `string` 이 된다. 리터럴 유니온을 유지하려면 `readonly T[]` 로 받고 호출부에서 `as const` 를 쓰거나 `T extends string` 제약으로 리터럴 추론을 유도한다.

두 번째 한계는 조건부·매핑 타입이 개입할 때 추론 위치가 사라지는 것이다. `Omit<ComponentPropsWithoutRef<T>, ...>` 처럼 `T` 가 매핑 타입 안에 묻히면 그 자리에서 역추론할 수 없다. 5절에서 `as?: T` 를 단순 프로퍼티로 유지한 이유가 이것이다. `as` 만이 유일한 추론 지점이다.

세 번째는 이름 충돌 우선순위다. `PropsToOmit` 은 자체 props 를 우선해 요소 쪽 동명 속성을 잘라내므로, `size?: 'sm' | 'md' | 'lg'` 를 정의한 채 `as="input"` 을 쓰면 HTML `input` 의 `size?: number` 가 사라진다. 의도된 동작이지만 `textSize` 처럼 충돌 가능성이 낮은 이름이 안전하다.

마지막으로 검사 비용이 실재한다. 다형 타입은 유니온이 큰 `ElementType` 전체에 `Omit` 과 교차 타입을 계산하므로 컴파일 시간이 늘어난다. `type ButtonTag = 'button' | 'a' | 'div'` 처럼 허용 태그를 좁혀 `T extends ButtonTag = 'button'` 으로 제약하면 검사 비용과 API 표면을 함께 줄인다. 결국 다형 컴포넌트는 호출부 정밀도와 구현부 단순성·검사 성능 사이의 거래이므로, 애플리케이션 내부라면 `as` 대신 `Button`·`ButtonLink` 처럼 별도 컴포넌트를 두는 편이 낫다.

## 참고

- [TypeScript Handbook — JSX](https://www.typescriptlang.org/docs/handbook/jsx.html)
- [TSConfig Reference — jsx](https://www.typescriptlang.org/tsconfig/#jsx)
- [TSConfig Reference — jsxImportSource](https://www.typescriptlang.org/tsconfig/#jsxImportSource)
- [TypeScript 5.1 Release Notes](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-5-1.html)
- [React — React 19 Upgrade Guide](https://react.dev/blog/2024/04/25/react-19-upgrade-guide)
- [React — Introducing the New JSX Transform](https://legacy.reactjs.org/blog/2020/09/22/introducing-the-new-jsx-transform.html)
- [DefinitelyTyped — @types/react](https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/react)
- [Emotion — CSS Prop with TypeScript](https://emotion.sh/docs/typescript)
