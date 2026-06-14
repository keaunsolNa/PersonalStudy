Notion 원본: https://www.notion.so/37f5a06fd6d381019811f71e0b63e27c

# TypeScript Higher-Kinded Types 에뮬레이션과 fp-ts URI 패턴 및 Defunctionalization

> 2026-06-14 신규 주제 · 확장 대상: TypeScript

## 학습 목표

- TypeScript 가 HKT 를 직접 지원하지 못하는 타입 시스템상의 이유를 설명한다
- defunctionalization 으로 타입 생성자를 1급 값처럼 다루는 URI 패턴을 구현한다
- module augmentation 으로 HKT 레지스트리를 확장하는 fp-ts 방식을 재현한다
- 이 패턴의 표현력 한계와 대안(제네릭 인터페이스 직접 작성)을 판단한다

## 1. Higher-Kinded Types 가 무엇이고 왜 막히는가

타입에도 "종류(kind)"가 있다. `number` 처럼 그 자체로 값이 될 수 있는 타입은 kind `*` 이고, `Array` 처럼 타입 하나를 받아 타입을 만드는 타입 생성자는 kind `* -> *` 이다. Higher-Kinded Type 은 이 타입 생성자 자체를 타입 파라미터로 받는 능력을 말한다. Haskell 의 `Functor f` 처럼 `f` 자리에 `Maybe`, `List` 등 어떤 컨테이너든 추상화해 한 번에 `map` 을 정의하는 것이다.

TypeScript 의 제네릭은 타입(`*`)만 파라미터로 받을 수 있고, 타입 생성자(`* -> *`)를 파라미터로 받을 수 없다. 즉 `interface Functor<F> { map: ... }` 에서 `F` 에 `Array`(아직 인자를 안 받은 생성자)를 넘겨 `F<A>` 처럼 적용할 문법이 없다. `F<A>` 라고 쓰면 컴파일러가 `F` 를 적용 가능한 생성자로 인지하지 못한다. 이것이 HKT 부재의 본질이다.

```typescript
// 이렇게 쓰고 싶지만 불가능 — F 를 F<A> 로 적용할 수 없음
interface Functor<F> {
  map<A, B>(fa: F<A>, f: (a: A) => B): F<B>; // 컴파일 에러: F is not generic
}
```

## 2. Defunctionalization: 적용을 데이터로 바꾸기

해결책은 defunctionalization 이다. 직접 적용할 수 없는 타입 생성자를 "이름표(URI)"라는 1급 값으로 인코딩하고, 실제 적용은 별도의 type-level 디스패치 테이블로 처리한다. 함수 적용 `F<A>` 를 "F 라는 이름과 A 라는 인자"라는 데이터로 환원한 뒤, 그 데이터를 보고 결과 타입을 lookup 하는 방식이다.

핵심 장치는 인자 슬롯을 담는 인터페이스와, URI 문자열로 그 인터페이스를 인덱싱하는 `Kind` 헬퍼다.

```typescript
interface HKT<A> {
  readonly _A: A; // 적용할 인자를 보관하는 가상 슬롯(phantom)
}

interface URItoKind<A> {} // 예: { Array: Array<A>, Option: Option<A> }

type URIS = keyof URItoKind<unknown>;

type Kind<F extends URIS, A> = URItoKind<A>[F];
```

`Kind<'Array', number>` 는 `URItoKind<number>['Array']` 를 통해 `Array<number>` 로 해석된다. `F<A>` 라는 직접 적용을 `Kind<F, A>` 라는 lookup 으로 바꿜 것이 전부다. 이제 `F` 를 일반 문자열 타입 파라미터(`F extends URIS`)로 받을 수 있으니, 제네릭의 한계를 우회한다.

## 3. URItoKind 레지스트리 확장

각 컨테이너 타입은 자신을 레지스트리에 등록한다. fp-ts 가 쓰는 패턴 그대로, 모듈 보강(declaration merging)으로 `URItoKind` 에 항목을 추가한다.

```typescript
interface None { readonly _tag: 'None'; }
interface Some<A> { readonly _tag: 'Some'; readonly value: A; }
type Option<A> = None | Some<A>;

const OptionURI = 'Option' as const;
type OptionURI = typeof OptionURI;

declare module './hkt' {
  interface URItoKind<A> {
    readonly Option: Option<A>;
  }
}

declare module './hkt' {
  interface URItoKind<A> {
    readonly Array: ReadonlyArray<A>;
  }
}
```

이렇게 등록하면 `Kind<'Option', string>` 이 `Option<string>` 으로, `Kind<'Array', string>` 이 `ReadonlyArray<string>` 으로 풀린다. 레지스트리는 열려 있어 라이브러리 사용자가 자신의 타입도 같은 방식으로 등록할 수 있다.

## 4. HKT 위에 typeclass 정의

이제 `F` 를 추상화한 typeclass 를 정의할 수 있다. `Functor` 를 URI 로 파라미터화한다.

```typescript
interface Functor<F extends URIS> {
  readonly URI: F;
  readonly map: <A, B>(fa: Kind<F, A>, f: (a: A) => B) => Kind<F, B>;
}

const functorOption: Functor<OptionURI> = {
  URI: OptionURI,
  map: (fa, f) =>
    fa._tag === 'None' ? fa : { _tag: 'Some', value: f(fa.value) },
};

const functorArray: Functor<'Array'> = {
  URI: 'Array',
  map: (fa, f) => fa.map(f),
};
```

`Kind<F, A>` 와 `Kind<F, B>` 가 인스턴스마다 올바른 구체 타입으로 추론되므로, `functorOption.map` 의 `fa` 는 `Option<A>` 로, `functorArray.map` 의 `fa` 는 `ReadonlyArray<A>` 로 좀혀진다. 더 나아가 어떤 Functor 든 받는 제네릭 함수를 작성할 수 있다.

```typescript
function lift<F extends URIS>(
  functor: Functor<F>,
): <A, B>(f: (a: A) => B) => (fa: Kind<F, A>) => Kind<F, B> {
  return (f) => (fa) => functor.map(fa, f);
}

const double = (n: number) => n * 2;
const overOption = lift(functorOption)(double);
const overArray = lift(functorArray)(double);
```

`lift` 는 컨테이너 종류를 모른 채 `map` 만으로 동작하는 진짜 HKT 추상화다. 이것이 defunctionalization 으로 얻는 표현력이다.

## 5. 한계와 trade-off

이 패턴에는 분명한 한계가 있다. 첫째 kind arity 가 고정된다. `Kind<F, A>` 는 인자 1개(`* -> *`)만 다루므로 `Either<E, A>` 처럼 인자 2개인 생성자는 `Kind2<F, E, A>` 와 `URItoKind2` 라는 별도 계층이 필요하다. fp-ts 가 `Kind`, `Kind2`, `Kind3`, `Kind4` 를 두는 이유가 이것이다. 인자 수마다 레지스트리와 헬퍼가 복제된다.

둘째 추론 품질이 떨어질 수 있다. URI 문자열 디스패치는 컴파일러의 일반 제네릭 추론보다 에러 메시지가 난해하고, 잘못 등록하면 `unknown` 으로 조용히 무너지기도 한다. 셋째 작성·유지 비용이 크다. 많은 실무 코드는 컨테이너 종류를 추상화할 필요 없이 구체 타입 하나에 대한 제네릭 인터페이스만으로 충분하다.

| 접근 | 표현력 | 추론·에러 가독성 | 적합한 경우 |
|------|--------|----------------|------------|
| 구체 제네릭 인터페이스 | 낮음(컨테이너 고정) | 좋음 | 단일 컨테이너 추상화 |
| URI 기반 HKT(fp-ts) | 높음(컨테이너 추상) | 보통~나쁨 | 라이브러리·typeclass 생태계 |
| 비표준 패치/매크로 | 높음 | 불안정 | 권장하지 않음 |

따라서 "내 코드에 HKT 가 꼭 필요한가"의 답은 대개 No 이며, 여러 컨테이너에 대해 typeclass 기반의 공통 알고리즘(Functor/Monad/Traversable 등)을 라이브러리 수준으로 추상화해야 할 때만 Yes 다. fp-ts 의 후속인 Effect 가 HKT 노출을 줄이고 더 직관적인 API 로 옷겨 간 것도 이 비용 때문이다. HKT 는 강력하지만, 그것을 쓰는 순간 팀 전체가 defunctionalization 의 정신 모델을 공유해야 한다는 점을 비용으로 계산해야 한다.

## 6. 다중 인자 생성자와 Kind2

`Either<E, A>`, `Map<K, V>` 처럼 인자가 둘인 생성자는 `Kind` 만으로 표현할 수 없다. 인자 슬롯이 하나뿐이기 때문이다. fp-ts 는 인자 수마다 평행한 계층을 둔다. `URItoKind2<E, A>` 레지스트리와 `Kind2<F, E, A>` 헬퍼다.

```typescript
interface URItoKind2<E, A> {}
type URIS2 = keyof URItoKind2<unknown, unknown>;
type Kind2<F extends URIS2, E, A> = URItoKind2<E, A>[F];

type Either<E, A> = { _tag: 'Left'; left: E } | { _tag: 'Right'; right: A };

declare module './hkt' {
  interface URItoKind2<E, A> {
    readonly Either: Either<E, A>;
  }
}

interface Functor2<F extends URIS2> {
  readonly map: <E, A, B>(fa: Kind2<F, E, A>, f: (a: A) => B) => Kind2<F, E, B>;
}

const functorEither: Functor2<'Either'> = {
  map: (fa, f) => (fa._tag === 'Left' ? fa : { _tag: 'Right', right: f(fa.right) }),
};
```

이처럼 arity 마다 `Kind`/`Kind2`/`Kind3` 를 복제해야 하는 것이 URI 패턴의 가장 큰 보일러플레이트다. 더 일반적인 typeclass(예: 모든 arity 를 받는 `Functor`)를 쓰려면 오버로드나 조건부 타입으로 `URIS | URIS2` 를 분기해야 해 복잡도가 급증한다. 이 비용이 실제 라이브러리에서 HKT 노출을 점점 숨기는 방향으로 진화한 직접적 이유다.

## 7. 실전 적용 판단 기준

이 패턴을 직접 도입할지는 세 질문으로 가른다. 첫째, 여러 컨테이너에 대해 동일한 알고리즘(traverse, sequence, fold 등)을 한 번만 작성해 재사용해야 하는가. 그렇다면 HKT 가 값을 한다. 둘째, 그 추상화를 소비하는 사람이 defunctionalization 정신 모델을 이해하는가. 그렇지 않으면 에러 메시지 해석에서 팀 생산성이 떨어진다. 셋째, 단순 제네릭으로 충분한 곳에 HKT 를 끌어들이고 있지는 않은가.

```typescript
function mapAll<A, B>(xs: readonly A[], f: (a: A) => B): readonly B[] {
  return xs.map(f); // 추상화 불필요 — 그냥 제네릭으로 명료
}
```

결론적으로 HKT 에뮬레이션은 typeclass 생태계를 만드는 라이브러리 저자의 도구이지 일반 애플리케이션 코드의 기본기가 아니다. Effect 가 `Effect<A, E, R>` 라는 구체 타입 중심 API 로 옷겨 가며 사용자 표면에서 HKT 를 거의 감춘 것은, 강력함과 학습 비용 사이에서 후자를 줄이는 실용적 타협이었다. 같은 판단을 각 팀이 자기 맥락에서 다시 해야 한다.

## 참고

- TypeScript Issue #1213 — Higher-kinded types 지원 논의(미해결 사유)
- fp-ts 문서 — HKT, Kind, URItoKind 모듈과 typeclass 정의
- Yallop & White, "Lightweight Higher-Kinded Polymorphism" (defunctionalization 원전)
- gcanti, "Functional design: combinators" 및 Effect 마이그레이션 가이드
