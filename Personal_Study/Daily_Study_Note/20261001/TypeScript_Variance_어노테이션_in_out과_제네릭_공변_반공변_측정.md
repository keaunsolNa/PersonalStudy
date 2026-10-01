Notion 원본: https://app.notion.com/p/3ec5a06fd6d381d48e09dece1983868e?pvs=204

# TypeScript 분산(Variance) 어노테이션 in/out과 제네릭 공변·반공변 측정 및 재귀 타입 서브타이핑 비용

> 2026-10-01 신규 주제 · 확장 대상: TypeScript 타입 시스템

## 학습 목표

- `Box<Dog>`와 `Box<Animal>`의 대입 가능 방향을 타입 파라미터 위치로부터 판정한다
- TypeScript 4.7의 `in`/`out`/`in out` 어노테이션을 선언하고 컴파일러 검증 오류를 해석한다
- 컴파일러가 마커 타입으로 분산을 측정하는 과정과 구조적 비교로 폴백하는 조건을 추적한다
- `--extendedDiagnostics`와 `--generateTrace`로 재귀 타입의 서브타이핑 비용을 계측하고 완화한다

## 1. 분산이 필요한 이유와 TypeScript의 기본 규칙

분산(variance)은 "타입 A가 타입 B의 서브타입일 때, 제네릭 `F<A>`와 `F<B>`의 관계가 어떻게 되는가"에 대한 답이다. 세 가지 결과가 있다. `F<Dog>`를 `F<Animal>`에 대입할 수 있으면 공변(covariant), 반대로 `F<Animal>`을 `F<Dog>`에 대입할 수 있으면 반공변(contravariant), 양방향 모두 되면 이변(bivariant), 어느 쪽도 안 되면 불변(invariant)이다. 타입 파라미터가 값을 "내보내는" 위치(반환 타입, 읽기 전용 프로퍼티)에만 나타나면 공변이고, 값을 "받아들이는" 위치(함수 매개변수)에만 나타나면 반공변이며, 두 위치에 모두 나타나면 불변이 된다.

TypeScript는 구조적 타입 시스템이므로 이 판정을 선언문에 적힌 키워드가 아니라 타입의 구조로부터 도출한다. 아래 예제는 세 가지 위치가 각각 어떤 분산을 만드는지 보여 준다. `strictFunctionTypes`가 켜져 있다는 가정이며, `strict: true`이면 자동으로 켜진다.

```ts
// tsconfig: { "compilerOptions": { "strict": true } }
interface Animal { name: string }
interface Dog extends Animal { bark(): void }

interface Producer<T> { get(): T }               // T는 반환 위치 -> 공변
type Consumer<T> = (value: T) => void;           // T는 매개변수 위치 -> 반공변
interface Cell<T> { value: T }                   // 읽기+쓰기 가능한 프로퍼티 -> 사실상 공변(unsound)

declare let pDog: Producer<Dog>;
declare let pAnimal: Producer<Animal>;
pAnimal = pDog;        // OK (공변)
// pDog = pAnimal;     // Error: Animal에는 bark가 없다

declare let cDog: Consumer<Dog>;
declare let cAnimal: Consumer<Animal>;
cDog = cAnimal;        // OK (반공변): Animal을 받는 함수는 Dog도 받을 수 있다
// cAnimal = cDog;     // Error (strictFunctionTypes)

declare let cellDog: Cell<Dog>;
let cellAnimal: Cell<Animal> = cellDog; // 컴파일 통과
cellAnimal.value = { name: "cat" };     // 런타임에서 cellDog.value가 Dog가 아니게 됨
```

마지막 예제가 TypeScript 분산의 현실적인 한계다. 프로퍼티는 읽기와 쓰기가 모두 가능하므로 이론적으로는 불변이어야 하지만, 타입스크립트는 프로퍼티를 공변으로 취급한다. 배열도 마찬가지로 `Dog[]`를 `Animal[]`에 대입할 수 있다. 이는 공식 핸드북이 밝히는 의도된 불완전성(unsoundness)이고, 생산성과 건전성 사이에서 생산성을 택한 설계다. 따라서 이후에 다루는 `out`/`in out` 어노테이션도 "소리 있는(sound) 타입 검사"를 보장하는 도구가 아니라, 컴파일러가 이미 내리는 판정을 명시하고 검증하는 도구라는 점을 기억해야 한다.

## 2. in/out/in out 어노테이션 문법

TypeScript 4.7 릴리스 노트가 도입한 기능으로, 인터페이스, 클래스, 객체·함수·생성자·매핑 타입을 가리키는 타입 별칭의 타입 파라미터 앞에 `in`, `out`, `in out` 수식어를 붙인다. `out T`는 T가 공변으로만 쓰인다는 선언이고, `in T`는 반공변으로만 쓰인다는 선언이며, `in out T`는 불변을 선언한다. 수식어는 생략할 수 있고, 생략하면 종전처럼 컴파일러가 구조로부터 측정한다.

```ts
interface Getter<out T> { get(): T }
interface Setter<in T> { set(value: T): void }
interface State<in out T> { get(): T; set(value: T): void }

type Mapper<in A, out B> = (a: A) => B;

class Repo<in out T extends { id: string }> {
  private items = new Map<string, T>();
  save(item: T): void { this.items.set(item.id, item); }
  find(id: string): T | undefined { return this.items.get(id); }
}

declare let s1: State<Dog>;
declare let s2: State<Animal>;
// s2 = s1;  // Error: in out은 불변이므로 양방향 모두 불가
// s1 = s2;  // Error
```

어노테이션이 실제 구조와 모순되면 컴파일러가 오류를 낸다. `out T`를 선언했지만 T가 함수 프로퍼티의 매개변수로 쓰이면, 컴파일러는 어노테이션이 암시하는 대입 관계가 구조적으로 성립하는지 확인하고 실패하면 TS2636 계열 오류("as implied by variance annotation")를 보고한다. 또 어노테이션이 허용되지 않는 위치(예: 조건부 타입의 별칭)에 쓰면 TS2637 오류가 난다. 오류 번호와 문구는 버전에 따라 다듬어질 수 있으므로 정확한 메시지는 사용하는 컴파일러로 확인해야 한다.

한 가지 주의할 점은 메서드 문법과 함수 프로퍼티 문법의 차이다. `set(value: T): void` 처럼 메서드로 선언된 매개변수는 `strictFunctionTypes`에서도 이변으로 검사되므로, 이 경우 `out T`를 붙여도 구조적 검증을 통과할 수 있다. 어노테이션이 건전성 검사기가 아니라는 것은 이 지점에서도 드러난다. 엄밀한 반공변 검사를 원하면 `set: (value: T) => void` 형태의 함수 프로퍼티로 선언해야 한다. 이 차이는 4장에서 다시 다룬다.

## 3. 컴파일러가 분산을 측정하는 방식

어노테이션이 없는 제네릭 타입에 대해 컴파일러(checker)는 타입 별칭이나 인터페이스를 처음 비교할 때 각 타입 파라미터의 분산을 한 번 계산해 캐시한다. 구현의 핵심은 마커 타입이다. 타입 파라미터 T 자리에 서로 서브타입 관계를 가지는 두 개의 가짜 타입(마커 슈퍼타입과 마커 서브타입)을 각각 대입해 `F<sub>`와 `F<super>`를 만든 뒤, 두 인스턴스를 구조적으로 비교한다. `F<sub>`가 `F<super>`에 대입 가능하면 공변 후보, `F<super>`가 `F<sub>`에 대입 가능하면 반공변 후보, 둘 다 가능하면 이변, 둘 다 불가능하면 불변으로 기록한다. 이 방식은 TypeScript 소스의 `getVariances` 계열 함수에서 확인할 수 있다.

이 측정 결과가 비용을 줄이는 이유는 이후의 비교에 있다. `Producer<Dog>`와 `Producer<Animal>`을 비교할 때, 측정된 분산을 알고 있으면 컴파일러는 멤버를 하나씩 펼치지 않고 타입 인자 Dog와 Animal만 비교한다. 즉 구조 전체를 순회하는 비용이 인자 비교 비용으로 환원된다. 인자가 한두 개인 흔한 제네릭에서는 이 차이가 작지만, 멤버가 수십 개인 인터페이스나 자기 자신을 다시 참조하는 재귀 타입에서는 차이가 커진다.

```ts
// 측정이 개입되는 전형적인 상황
interface Node<T> {
  value: T;
  children: Node<T>[];     // 자기 참조: 구조 비교를 펼치면 끝나지 않는다
  parent?: Node<T>;
}

declare let a: Node<Dog>;
declare let b: Node<Animal>;
b = a; // 분산 측정이 T를 공변으로 판정 -> Dog vs Animal 비교로 끝
```

측정 중 자기 참조를 만나면 컴파일러는 "측정 중" 표식을 두어 무한 재귀를 막고, 그 위치는 일단 낙관적으로(성립한다고) 가정한 뒤 다른 위치의 결과로 결론을 낸다. 이 때문에 재귀 타입의 분산 측정은 특히 어노테이션으로 결과를 고정해 주면 이득이 있다. 4.7 릴리스 노트도 어노테이션이 깊게 중첩되거나 재귀적인 타입에서 검사 속도에 도움이 될 수 있다고 설명한다. 다만 얼마나 빨라지는지는 타입 구조와 사용처에 따라 달라서 일반적인 배수를 제시할 수 없으며, 반드시 자기 프로젝트에서 측정해야 한다.

## 4. 메서드 vs 함수 프로퍼티와 strictFunctionTypes

`strictFunctionTypes`(TypeScript 2.6 도입)는 함수 타입 매개변수를 반공변으로 검사한다. 단, 메서드 문법으로 선언된 멤버와 생성자 선언은 예외로 이변을 유지한다. 이는 `Array<T>`의 `push` 같은 메서드가 있어도 `Dog[]`를 `Animal[]`에 대입하는 관행을 깨지 않기 위한 의도적 예외다. 결과적으로 같은 의미를 가진 두 선언이 서로 다른 분산 측정 결과를 만든다.

```ts
interface Handler1<T> { handle(value: T): void }          // 메서드: 매개변수 이변
interface Handler2<T> { handle: (value: T) => void }      // 함수 프로퍼티: 반공변

declare let h1Dog: Handler1<Dog>;
declare let h1Animal: Handler1<Animal>;
h1Animal = h1Dog;   // 통과 (이변이라 허용)
h1Dog = h1Animal;   // 통과

declare let h2Dog: Handler2<Dog>;
declare let h2Animal: Handler2<Animal>;
// h2Animal = h2Dog; // Error (반공변이므로 Dog 핸들러는 Animal 핸들러가 될 수 없다)
h2Dog = h2Animal;    // OK

// 어노테이션: Handler2에는 in이 정확히 맞고, Handler1에 in을 붙이면
// 구조상 통과는 하지만 이변 구조 위에 반공변을 덧씌우는 셈이다.
interface Handler3<in T> { handle: (value: T) => void }
```

## 5. 재귀 타입의 서브타이핑 비용

재귀 타입의 비교는 컴파일러가 구조를 펼치다 보면 같은 쌍을 반복해서 만나는 상황을 처리해야 한다. 컴파일러는 비교 중인 타입 쌍을 스택으로 유지하고, 동일한 재귀 식별자를 가진 타입이 일정 깊이 이상 중첩되면 "깊게 중첩되었다"고 보고 이후는 대입 가능으로 가정(Maybe 결과)한다. 소스에서는 이 깊이 상수가 작은 값이며, 한계를 넘기면 TS2321 "Excessive stack depth comparing types" 오류가 나기도 한다. 따라서 재귀 타입에서 분산 측정 결과가 있고 없고는 비교가 얕게 끝나느냐, 이 한계에 가까워지느냐를 가르는 요인이 된다.

타입 인스턴스화 쪽에는 별도의 한계가 있다. 인스턴스화 깊이가 100에 도달하거나 한 번의 검사 단위에서 인스턴스화 횟수가 5,000,000회에 도달하면 TS2589 "Type instantiation is excessively deep and possibly infinite"가 보고된다. TypeScript 4.5부터는 꼬리 재귀 형태로 쓴 조건부 타입을 반복문처럼 평가해 재귀 한계를 1000단계까지 늘렸다. 아래는 비용이 폭증하기 쉬운 형태와 완화 형태의 비교다.

```ts
// 비용이 큰 형태: 누산기 없이 결과를 재귀 바깥에서 조합
type Reverse<T extends unknown[]> =
  T extends [infer H, ...infer R] ? [...Reverse<R>, H] : [];

// 4.5+ 꼬리 재귀 형태: 누산기에 쌓고 마지막에 반환
type ReverseTR<T extends unknown[], Acc extends unknown[] = []> =
  T extends [infer H, ...infer R] ? ReverseTR<R, [H, ...Acc]> : Acc;

// 자기 참조 데이터 구조: 분산을 고정해 비교를 인자 비교로 환원
interface Tree<out T> {
  value: T;
  children: readonly Tree<T>[];
}
```

`Tree<out T>`에서 `readonly`는 의도적이다. `readonly Tree<T>[]`는 T를 읽기 위치에만 두므로 `out`과 구조가 일치한다. 가변 배열 `Tree<T>[]`라도 TypeScript는 공변으로 취급해 검증을 통과하지만, 어노테이션이 "읽기 전용 소비"라는 의도를 코드로 남겨 둔다는 점에서 구분할 가치가 있다.

## 6. 비용 계측 방법

체감이 아닌 수치로 판단하려면 컴파일러 진단을 써야 한다. `tsc --extendedDiagnostics`는 Types, Instantiations, Symbols, Check time 같은 항목을 출력하고, `Instantiations`와 `Check time`이 재귀 타입 변경 전후로 어떻게 달라지는지가 1차 지표다. 더 정밀하게는 `--generateTrace <dir>`로 추적 파일을 만들고, TypeScript 팀의 `@typescript/analyze-trace`로 느린 타입 검사 구간을 찾는다.

```bash
# 1) 기준선 측정 (3회 이상 반복해 편차 확인)
npx tsc -p tsconfig.json --noEmit --extendedDiagnostics | \
  grep -E "Instantiations|Types:|Check time|Total time"

# 2) 추적 파일 생성 후 느린 구간 분석
npx tsc -p tsconfig.json --noEmit --generateTrace ./trace
npx @typescript/analyze-trace ./trace

# 3) 어노테이션 추가 후 같은 명령으로 재측정하고 Instantiations를 비교
```

측정 결과는 하드웨어, TypeScript 버전, 증분 빌드 여부, 프로젝트 구조에 크게 의존한다. `Check time`은 실행마다 흔들리므로 중앙값을 보고, 비교적 결정적인 `Instantiations`와 `Types` 수치를 함께 기록하는 편이 낫다. 이 문서는 구체적인 속도 향상 배수를 제시하지 않는다. 어노테이션 효과는 환경과 타입 구조에 따라 다름이 맞으며, 개선이 없거나 미미한 프로젝트도 흔하다. 오히려 병목이 분산 측정이 아니라 거대한 유니온이나 반복적인 조건부 타입 평가에 있는 경우가 많으므로, 추적 결과로 원인을 먼저 확인한 뒤 손을 대야 한다.

## 7. 적용 기준과 trade-off

어노테이션을 붙일지는 비용 대 이득으로 판단한다. 이득은 세 가지다. 재귀적이거나 멤버가 많은 제네릭의 대입 검사가 인자 비교로 단축될 수 있고, 타입 파라미터의 의도(생산자인지 소비자인지)가 선언에 드러나며, 리팩터링으로 구조가 바뀌어 분산이 의도와 달라지면 컴파일러가 즉시 알려 준다. 비용은 이렇다. 수식어는 4.7 이상 컴파일러에서만 파싱되므로 구버전 소비자가 있는 라이브러리는 선언 파일 호환에 주의해야 하고, 잘못된 어노테이션은 공개 API의 대입 가능성을 좁힐 수 있으며, 측정 가능한 이득이 없는 곳에는 노이즈가 된다.

실무 권장 순서는 다음과 같다. 먼저 `--extendedDiagnostics`로 기준선을 잡고, 느린 구간이 특정 제네릭 인터페이스의 대입 검사로 좁혀질 때만 어노테이션을 시도한다. 생산자 타입은 `out`, 소비자 콜백은 `in`, 읽기와 쓰기를 모두 하는 저장소는 `in out`으로 의도를 명시한다. 적용 후 같은 명령으로 재측정해 변화가 없으면 되돌린다. 라이브러리 작성자는 새 어노테이션이 기존 소비 코드를 깨뜨리지 않는지 타입 테스트(예: `tsd`, `expect-type`, `tsc`로 컴파일되는 예제)로 확인한다.

## 참고

- TypeScript 4.7 Release Notes, "Optional Variance Annotations for Type Parameters" (typescriptlang.org/docs/handbook/release-notes/typescript-4-7.html)
- TypeScript 4.5 Release Notes, "Tail-Recursion Elimination on Conditional Types"
- TypeScript 2.6 Release Notes, "Strict function types" (strictFunctionTypes)
- TypeScript Handbook, "Type Compatibility" (함수 매개변수 이변과 공변·반공변 설명)
- TypeScript 위키, "Performance" (github.com/microsoft/TypeScript/wiki/Performance)
- microsoft/TypeScript 소스 `src/compiler/checker.ts` (getVariances, isDeeplyNestedType, instantiationDepth 관련 구현)
- microsoft/typescript-analyze-trace 저장소 README (`--generateTrace` 결과 분석)
