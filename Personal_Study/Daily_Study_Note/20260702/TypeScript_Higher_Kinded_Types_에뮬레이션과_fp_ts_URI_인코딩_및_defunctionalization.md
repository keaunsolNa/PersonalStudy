Notion 원본: https://www.notion.so/3915a06fd6d381faa2fed7e90d4608c0

# TypeScript Higher-Kinded Types 에뮬레이션과 fp-ts URI 인코딩 및 defunctionalization

> 2026-07-02 신규 주제 · 확장 대상: TypeScript(제네릭·타입 레벨 프로그래밍 학습됨)

## 학습 목표

- 고차 종류(higher-kinded type)가 무엇이며 TypeScript가 왜 직접 지원하지 못하는지 규명한다
- defunctionalization으로 타입 생성자를 1급 값처럼 다루는 fp-ts의 URI 트릭을 재현한다
- `Kind<F, A>` 디스패치가 선언 병합으로 확장되는 구조를 읽는다
- HKT 추상화의 런타임·개발경험 trade-off를 판단한다

## 1. 문제 정의 - 종류(kind)의 계층

값에 타입이 있듯 타입에는 종류(kind)가 있다. `number`는 `*`(구체 타입), `Array`는 `* -> *`(타입을 받아 타입을 만드는 생성자)다. 모든 컨테이너 `F`에 대해 동작하는 `map`을 쓰고 싶을 때 그 `F`는 `Array`, `Option`, `Promise`처럼 `* -> *` 종류의 타입 생성자다. 타입 생성자 자체를 타입 파라미터로 추상화하는 능력이 higher-kinded polymorphism이다.

Haskell은 `class Functor f where fmap :: (a -> b) -> f a -> f b`처럼 `f`를 직접 추상화한다. TypeScript에는 이게 없다. `interface Functor<F> { map<A,B>(fa: F<A>, f:(a:A)=>B): F<B> }`라고 쓰면 `F<A>`에서 에러가 난다. `F`는 이미 타입이지 타입 생성자가 아니어서 적용할 수 없기 때문이다.

## 2. defunctionalization - 적용을 데이터로 바꾸기

해결의 열쇠는 defunctionalization이다. 함수 적용을 언어가 못 하면 적용을 1급 데이터(문자열 태그)로 인코딩하고 별도 테이블에서 결과를 조회한다.

```typescript
interface URItoKind<A> {}
type URIS = keyof URItoKind<unknown>;
type Kind<F extends URIS, A> = URItoKind<A>[F];
```

`Kind<F, A>`는 `F<A>`를 흉내 낸다. `F`는 문자열 태그이고 `URItoKind<A>[F]`가 대응하는 구체 타입을 반환한다. 언어의 타입 적용을 인덱스 접근으로 우회한 것이다.

## 3. 생성자 등록 - 선언 병합의 실전

```typescript
interface None { readonly _tag: "None"; }
interface Some<A> { readonly _tag: "Some"; readonly value: A; }
type Option<A> = None | Some<A>;

declare module "./hkt" {
  interface URItoKind<A> { readonly Option: Option<A>; }
}
declare module "./hkt" {
  interface URItoKind<A> { readonly Array: ReadonlyArray<A>; }
}
```

이제 `Kind<"Option", number>`는 `Option<number>`로 해석된다. `URItoKind`가 여러 선언으로 병합되면서 태그→타입 디스패치 테이블이 완성된다.

## 4. HKT 위에서 Functor 추상화 작성

```typescript
interface Functor<F extends URIS> {
  readonly URI: F;
  readonly map: <A, B>(fa: Kind<F, A>, f: (a: A) => B) => Kind<F, B>;
}

const optionFunctor: Functor<"Option"> = {
  URI: "Option",
  map: (fa, f) => (fa._tag === "None" ? fa : { _tag: "Some", value: f(fa.value) }),
};

function double<F extends URIS>(F: Functor<F>, fa: Kind<F, number>): Kind<F, number> {
  return F.map(fa, (n) => n * 2);
}
```

`double`은 `F`가 무엇인지 모르지만 `Functor<F>` 증거를 받아 조작한다. Haskell의 타입 클래스 제약을 사전 전달(dictionary passing)로 옮긴 것이다.

## 5. 고정 arity 문제와 Kind2, Kind3

위 인코딩은 파라미터 1개 생성자만 다룬다. `Either<E, A>` 같은 2·3항 생성자를 위해 fp-ts는 `URItoKind2<E, A>`, `Kind2<F, E, A>`처럼 arity별로 테이블과 조회 타입을 따로 둔다.

```typescript
interface URItoKind2<E, A> {}
type URIS2 = keyof URItoKind2<never, never>;
type Kind2<F extends URIS2, E, A> = URItoKind2<E, A>[F];
```

TypeScript는 가변 arity 종류를 하나로 표현하지 못하므로 arity마다 인코딩을 복제한다.

## 6. 한계와 오류 메시지 비용

| 항목 | 내용 |
|---|---|
| 부분 적용 불가 | Either의 왼쪽을 고정한 타입을 태그 하나로 못 만든다 |
| arity 폭발 | 생성자 파라미터 개수마다 KindN 복제 필요 |
| 오류 가독성 | 실패 시 URItoKind[...] 인덱스 형태로 에러가 나와 추적이 어렵다 |
| 전역 오염 | URItoKind가 전역 병합 지점이라 태그 충돌 위험 |

특히 오류 메시지 비용이 실무의 걸림돌이다. 인스턴스를 하나 빠뜨리면 난해한 constraint 에러가 나온다.

## 7. 대안 - Effect의 Variance 인코딩

Effect는 순수 URI 디스패치 대신 타입에 심볼로 variance 위치를 심어 생성자를 식별하는 방식을 섞어 쓴다. 커뮤니티에서는 HKT를 전면에 내세우기보다 구체 타입 + 잘 설계된 콤비네이터로 실용 코드를 커버하는 흐름이 강하다.

## 8. 언제 HKT를 쓰는가

Higher-kinded 에뮬레이션은 여러 컨테이너에 걸쳐 동일한 추상 알고리즘(traverse, sequence)을 한 번만 구현해야 할 때 값어치를 한다. 판단 기준은 동일한 다형 알고리즘을 세 종류 이상의 생성자에 재사용하는가이며, 그렇지 않다면 구체 타입별 콤비네이터가 더 건강하다.

## 9. 실전 - traverse 를 HKT 위에서 단 한 번만 구현하기

```typescript
interface Applicative<F extends URIS> extends Functor<F> {
  readonly of: <A>(a: A) => Kind<F, A>;
  readonly ap: <A, B>(fab: Kind<F, (a: A) => B>, fa: Kind<F, A>) => Kind<F, B>;
}

function traverseArray<F extends URIS>(F: Applicative<F>) {
  return <A, B>(as: ReadonlyArray<A>, f: (a: A) => Kind<F, B>): Kind<F, ReadonlyArray<B>> =>
    as.reduce(
      (acc, a) => F.ap(F.map(acc, (bs: ReadonlyArray<B>) => (b: B) => [...bs, b]), f(a)),
      F.of<ReadonlyArray<B>>([])
    );
}
```

`traverseArray(optionApplicative)`는 하나라도 None이면 전체를 None으로 단락시키고, either 버전은 첫 Left에서 멈춘다. 동일한 순회 골격을 효과별로 다시 짜지 않아도 되는 것이 요점이다.

## 10. 성능·번들 관점의 냉정한 평가

HKT 에뮬레이션은 전적으로 타입 레벨 장치이므로 런타임에는 흔적이 없다. 실제 비용은 두 가지다. 첫째 컴파일 타임 부담으로, 인덱스 접근과 조건부 타입을 깊게 전개하면 `tsc --extendedDiagnostics`의 Instantiations가 튀어 에디터 반응성이 떨어진다. 둘째 팀 전파 비용으로, `Kind<F, A>`가 왜 `F<A>`가 아닌지 모르는 팀원에게는 블랙박스가 된다. 현실적 결론은 계층 분리다. 라이브러리 경계 안쪽은 HKT로 중복을 제거하되 공개 API는 `Option.traverse`처럼 구체 타입으로 특수화해 노출한다.

## 참고

- fp-ts — HKT 모듈 소스 (https://github.com/gcanti/fp-ts/blob/master/src/HKT.ts)
- Yallop & White, "Lightweight Higher-Kinded Polymorphism" (FLOPS 2014)
- Reynolds, "Definitional Interpreters" (1972)
- Effect 공식 문서 — Data types & variance (https://effect.website)
