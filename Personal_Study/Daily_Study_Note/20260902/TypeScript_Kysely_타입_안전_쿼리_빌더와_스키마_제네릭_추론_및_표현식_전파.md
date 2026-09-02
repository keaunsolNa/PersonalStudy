Notion 원본: https://www.notion.so/3cf5a06fd6d38159b450c0d30c568cf9

# TypeScript Kysely 타입 안전 쿼리 빌더와 스키마 제네릭 추론 및 표현식 전파

> 2026-09-02 신규 주제 · 확장 대상: TypeScript Template Literal Types, Higher-Kinded Types 에뮬레이션

## 학습 목표

- 스키마 인터페이스가 빌더 체인을 따라 누적 제네릭으로 전파되는 구조를 추적한다
- `Selectable` / `Insertable` / `Updateable` 세 얼굴을 `ColumnType` 로 분리한다
- 템플릿 리터럴 타입으로 참조 문자열을 파싱하는 원리를 읽는다
- 조인·서브쿼리에서 타입이 넓어지는 지점과 헬퍼 함수 작성 패턴을 설계한다

## 1. ORM 이 아니라 타입 있는 SQL

Prisma 나 TypeORM 은 엔티티 그래프를 관리하고 SQL 을 감춘다. 반면 Kysely 는 SQL 을 감추지 않는다. `selectFrom`, `innerJoin`, `where`, `groupBy` 가 SQL 절과 1:1 대응하고, 생성되는 쿼리를 예측할 수 있다. 대신 그 SQL 의 모든 식별자와 결과 타입을 컴파일 타임에 검증한다.

백엔드 엔지니어 관점에서 이 위치는 JOOQ 와 정확히 같다. 차이는 JOOQ 가 코드 생성으로 클래스를 만들어내는 반면, Kysely 는 타입 수준 프로그래밍만으로 같은 일을 한다는 점이다. 런타임 코드는 문자열 조합기에 가깝고, 무게는 전부 타입에 실린다. 결과적으로 번들 크기가 작고(코어 약 60KB) 런타임 리플렉션이 없다.

이 문서의 목표는 Kysely 를 쓰는 법이 아니라, *어떻게 그 추론이 가능한가* 를 타입 레벨에서 읽는 것이다.

## 2. 스키마 인터페이스와 세 가지 얼굴

시작점은 평범한 인터페이스다.

```typescript
import { ColumnType, Generated, Insertable, Selectable, Updateable } from 'kysely';

interface UserTable {
	id: Generated<number>;
	email: string;
	display_name: string | null;
	role: 'admin' | 'member';
	created_at: ColumnType<Date, string | undefined, never>;
	updated_at: ColumnType<Date, string | undefined, string>;
}

interface PostTable {
	id: Generated<number>;
	author_id: number;
	title: string;
	body: string;
	published_at: ColumnType<Date | null, string | null, string | null>;
}

interface Database {
	user: UserTable;
	post: PostTable;
}
```

`ColumnType<S, I, U>` 는 하나의 컬럼이 문맥마다 다른 타입을 갖는다는 사실을 인코딩한다.

- `S` — SELECT 결과 타입
- `I` — INSERT 입력 타입
- `U` — UPDATE 입력 타입

`created_at: ColumnType<Date, string | undefined, never>` 는 "읽을 때 Date, 넣을 때 생략 가능한 문자열, 갱신 불가" 를 뜻한다. `U = never` 인 컬럼은 `UpdateObject` 에서 아예 키가 제거된다.

`Generated<T>` 는 `ColumnType<T, T | undefined, T>` 의 별칭이다. 자동 증가 PK 에 쓰면 INSERT 시 생략 가능해진다.

세 얼굴을 뽑아내는 유틸리티가 `Selectable` / `Insertable` / `Updateable` 이다. 구현의 뼈대는 다음과 같다.

```typescript
type SelectType<T> = T extends ColumnType<infer S, any, any> ? S : T;
type InsertType<T> = T extends ColumnType<any, infer I, any> ? I : T;
type UpdateType<T> = T extends ColumnType<any, any, infer U> ? U : T;

type NullableInsertKeys<R> = {
	[K in keyof R]: undefined extends InsertType<R[K]> ? K : never;
}[keyof R];

type NonNullableInsertKeys<R> = Exclude<keyof R, NullableInsertKeys<R>>;

type Insertable<R> =
	{ [K in NonNullableInsertKeys<R>]: InsertType<R[K]> } &
	{ [K in NullableInsertKeys<R>]?: Exclude<InsertType<R[K]>, undefined> };
```

`NullableInsertKeys` 가 하는 일이 핵심이다. 매핑 타입으로 각 키를 순회하며 "삽입 타입에 `undefined` 가 포함되는가" 를 물어 참이면 키 자신을, 거짓이면 `never` 를 값으로 둔다. 그다음 `[keyof R]` 로 인덱싱하면 값들의 유니온이 나오고 `never` 는 유니온에서 사라진다. 이 "필터링 매핑 타입 + 인덱스 접근" 관용구는 조건부 키 추출의 표준 패턴이다.

결과적으로 다음이 성립한다.

```typescript
type NewUser = Insertable<UserTable>;
// { email: string; display_name: string | null; role: 'admin' | 'member';
//   id?: number; created_at?: string; updated_at?: string }

type UserRow = Selectable<UserTable>;
// { id: number; email: string; display_name: string | null;
//   role: 'admin' | 'member'; created_at: Date; updated_at: Date }

type UserPatch = Updateable<UserTable>;
// { email?: string; display_name?: string | null;
//   role?: 'admin' | 'member'; updated_at?: string }   // created_at 없음
```

`created_at` 이 `Updateable` 에서 사라진 것은 `U = never` 때문이다. `never` 타입 값을 요구하는 프로퍼티는 실질적으로 채울 수 없으므로 Kysely 는 이를 키에서 제거한다.

## 3. 빌더 체인의 누적 제네릭

`db.selectFrom('post')` 를 호출하면 반환 타입은 대략 `SelectQueryBuilder<DB, 'post', {}>` 다. 세 파라미터는 각각 다음을 뜻한다.

1. `DB` — 전체 데이터베이스 스키마
2. `TB` — 현재 쿼리에 참여 중인 테이블 이름의 유니온
3. `O` — 지금까지 확정된 출력 객체 타입

체인이 진행될 때마다 두 번째와 세 번째가 누적된다.

```typescript
const query = db
	.selectFrom('post')                                   // TB = 'post', O = {}
	.innerJoin('user', 'user.id', 'post.author_id')       // TB = 'post' | 'user'
	.select(['post.id', 'post.title'])                    // O = { id: number, title: string }
	.select('user.email as author_email')                 // O &= { author_email: string }
	.where('post.published_at', 'is not', null)
	.orderBy('post.published_at', 'desc');

type Row = Awaited<ReturnType<typeof query.execute>>[number];
// { id: number; title: string; author_email: string }
```

`innerJoin` 의 시그니처를 단순화하면 이렇다.

```typescript
innerJoin<
	TE extends keyof DB & string,
	K1 extends ReferenceExpression<DB, TB | TE>,
	K2 extends ReferenceExpression<DB, TB | TE>,
>(
	table: TE,
	lhs: K1,
	rhs: K2,
): SelectQueryBuilder<DB, TB | TE, O>;
```

`TE` 가 `keyof DB` 로 제약되므로 존재하지 않는 테이블 이름은 즉시 에러다. `lhs`/`rhs` 는 `TB | TE` 범위의 참조 표현식만 받으므로, 아직 조인되지 않은 테이블의 컬럼을 참조하면 에러가 난다. 반환 타입에서 `TB | TE` 로 확장되어 이후 절에서 새 테이블 컬럼을 쓸 수 있게 된다.

`where` 도 마찬가지다.

```typescript
where<RE extends ReferenceExpression<DB, TB>>(
	lhs: RE,
	op: ComparisonOperatorExpression,
	rhs: OperandValueExpressionOrList<DB, TB, RE>,
): SelectQueryBuilder<DB, TB, O>;
```

`rhs` 의 타입이 `RE` 에 의존한다. `where('post.author_id', '=', ...)` 라면 `rhs` 는 `number` 로 좁혀지고, `where('user.role', '=', 'moderator')` 는 `'admin' | 'member'` 에 없는 값이므로 컴파일 에러다. 문자열 리터럴 하나가 다음 인자의 타입을 결정하는 이 연쇄가 Kysely 추론의 실질적 가치다.

## 4. 템플릿 리터럴 타입으로 참조 문자열 파싱

`'user.email as author_email'` 한 문자열에서 (테이블, 컬럼, 별칭) 셋을 뽑아내는 것은 전적으로 템플릿 리터럴 타입의 일이다. 원리를 축약해 재현하면 다음과 같다.

```typescript
type AnyColumn<DB, TB extends keyof DB> = {
	[T in TB]: keyof DB[T] & string;
}[TB];

type AnyColumnWithTable<DB, TB extends keyof DB> = {
	[T in TB & string]: `${T}.${keyof DB[T] & string}`;
}[TB & string];

type StringReference<DB, TB extends keyof DB> =
	| AnyColumn<DB, TB>
	| AnyColumnWithTable<DB, TB>;

type ExtractAliasFromString<S extends string> =
	S extends `${string} as ${infer A}` ? A : never;

type ExtractExprFromString<S extends string> =
	S extends `${infer E} as ${string}` ? E : S;

type Selection<DB, TB extends keyof DB, SE extends readonly string[]> = {
	[S in SE[number] as ExtractAliasFromString<S> extends never
		? ExtractExprFromString<S> extends `${string}.${infer C}` ? C : ExtractExprFromString<S>
		: ExtractAliasFromString<S>
	]: ExtractTypeFromReference<DB, TB, S>;
};
```

읽어야 할 지점이 세 곳이다.

- `AnyColumnWithTable` 은 매핑 타입 안에서 템플릿 리터럴을 만들고 `[TB & string]` 인덱싱으로 유니온화한다. `'post' | 'user'` 가 들어오면 `'post.id' | 'post.title' | ... | 'user.id' | ...` 전체가 만들어진다. 테이블이 20개, 컬럼이 각 15개면 300개짜리 유니온이다.
- `S extends` 템플릿 리터럴 패턴은 문자열을 " as " 기준으로 쪼개다. `infer` 가 템플릿 리터럴 안에서 부분 문자열을 잡아낸다.
- `Selection` 의 키 리매핑 `[S in SE[number] as ...]` 이 별칭 유무에 따라 최종 프로퍼티 이름을 결정한다. 별칭이 있으면 별칭, 없고 `table.column` 형태면 `column`, 그냥 컬럼명이면 그대로다.

이 구조가 IDE 자동완성에도 그대로 쓰인다. `select('` 까지 쳤을 때 나오는 후보 목록이 바로 `StringReference<DB, TB>` 유니온이다.

## 5. 성능 — 유니온 폭발과 대처

타입 레벨 문자열 파싱은 공짜가 아니다. 테이블 수 × 컬럼 수에 비례해 유니온 크기가 커지고, 조인이 늘면 `TB` 유니온이 커져 `AnyColumnWithTable` 이 곱셈으로 커진다. 스키마가 100 테이블 규모가 되면 `tsc` 시간이 눈에 띄게 늘고, 에디터에서 자동완성 지연이 체감된다.

측정부터 한다.

```bash
tsc --noEmit --extendedDiagnostics
tsc --noEmit --generateTrace ./trace && npx @typescript/analyze-trace ./trace
```

`analyze-trace` 는 "이 파일의 이 타입 인스턴스화가 N ms 걸렸다" 를 알려준다. Kysely 관련 병목이 잡힐면 대처는 다음과 같다.

**1) 큰 쿼리는 결과 타입을 명시적으로 고정한다.** 재사용되는 쿼리는 타입을 한 번만 계산하도록 별칭을 만든다.

```typescript
export type PostListRow = {
	id: number;
	title: string;
	author_email: string;
};

export function postListQuery(db: Kysely<Database>) {
	return db
		.selectFrom('post')
		.innerJoin('user', 'user.id', 'post.author_id')
		.select(['post.id', 'post.title', 'user.email as author_email']);
}
```

**2) 스키마를 도메인별로 쪼개다.** `Kysely<Database>` 하나 대신 `Kysely<AuthSchema>`, `Kysely<ContentSchema>` 로 나누면 각 쿼리가 보는 유니온이 작아진다.

**3) `skipLibCheck: true` 와 `incremental: true` 는 기본으로 켜다.** Kysely 의 `.d.ts` 자체가 크므로 lib 체크 생략 효과가 크다.

**4) 배딴 파일(`index.ts` 재수출)을 피한다.** 스키마 타입을 배딴로 모으면 모든 파일이 전체 스키마를 로드한다.

## 6. 헬퍼 함수 작성 — 제네릭을 뚫고 지나가기

재사용 가능한 조건 헬퍼를 쓰려면 빌더 제네릭을 그대로 받아 반환해야 한다.

```typescript
import { SelectQueryBuilder } from 'kysely';

export function onlyPublished<O>(
	qb: SelectQueryBuilder<Database, 'post', O>,
): SelectQueryBuilder<Database, 'post', O> {
	return qb.where('post.published_at', 'is not', null);
}

export function paginate<DB, TB extends keyof DB, O>(
	qb: SelectQueryBuilder<DB, TB, O>,
	page: number,
	size: number,
): SelectQueryBuilder<DB, TB, O> {
	return qb.limit(size).offset((page - 1) * size);
}
```

`paginate` 는 세 제네릭을 모두 통과시키므로 어떤 쿼리에도 붙는다. `onlyPublished` 는 `TB` 를 `'post'` 로 고정했으므로 post 를 포함한 쿼리에만 쓸 수 있다 — 조인으로 `TB` 가 `'post' | 'user'` 가 되면 할당되지 않는다. 이를 유연하게 하려면 `$if` / `$call` 메서드를 쓰는 편이 낫다.

```typescript
const rows = await db
	.selectFrom('post')
	.selectAll()
	.$if(filter.onlyPublished, (qb) => qb.where('post.published_at', 'is not', null))
	.$call((qb) => paginate(qb, filter.page, 20))
	.execute();
```

### 서브쿼리와 집계

```typescript
const withCounts = await db
	.selectFrom('user')
	.select((eb) => [
		'user.id',
		'user.email',
		eb
			.selectFrom('post')
			.select((eb2) => eb2.fn.countAll<number>().as('count'))
			.whereRef('post.author_id', '=', 'user.id')
			.as('post_count'),
	])
	.execute();
// { id: number; email: string; post_count: number }[]
```

`countAll<number>()` 에 타입 인자를 주는 이유는 드라이버마다 COUNT 반환 타입이 다르기 때문이다. PostgreSQL 은 `bigint` 를 문자열로 돌려주고, MySQL 은 숫자를 준다. Kysely 는 이를 알 수 없으므로 기본이 `string | number | bigint` 이고, 개발자가 드라이버에 맞춰 좁혀야 한다. 여기서 `<number>` 를 잘못 주면 런타임에 문자열이 들어와 타입과 실제가 어긋난다 — **타입 안전성은 드라이버 경계에서 끝난다**는 점을 잊으면 안 된다.

`whereRef` 는 값이 아니라 컬럼 참조끼리 비교할 때 쓴다. `where` 에 `'user.id'` 를 넣으면 문자열 리터럴 값으로 해석되므로 상관 서브쿼리가 성립하지 않는다.

## 7. 마이그레이션과 스키마 동기화

타입은 손으로 쓴 인터페이스일 뿐이므로 실제 DB 와 어긋날 수 있다. 이 간극을 메우는 방법은 두 가지다.

**코드 생성**: `kysely-codegen` 이 실제 DB 에 접속해 인터페이스를 뽑는다.

```bash
DATABASE_URL=postgres://... npx kysely-codegen --out-file src/db/schema.d.ts --dialect postgres
```

CI 에서 이 명령을 돌리고 `git diff --exit-code` 로 검사하면, 마이그레이션을 적용했는데 타입을 갱신하지 않은 PR 을 잡을 수 있다.

**런타임 검증**: 생성 타입을 믿되, 경계에서 한 번 더 확인한다.

```typescript
describe('user repository', () => {
	it('returns published posts with author email', async () => {
		await db.insertInto('user')
			.values({ email: 'a@example.com', display_name: null, role: 'member' })
			.execute();

		const rows = await postListQuery(db).execute();

		expect(rows).toHaveLength(0);
		expectTypeOf(rows).toEqualTypeOf<PostListRow[]>();
	});

	it('rejects unknown role at compile time', () => {
		// @ts-expect-error 'moderator' is not assignable to 'admin' | 'member'
		db.selectFrom('user').where('user.role', '=', 'moderator');
	});
});
```

`@ts-expect-error` 를 쓴 테스트가 중요하다. 타입 제약이 실제로 걸려 있는지를 검증하는 유일한 방법이고, 제약이 느슬해지면(예: 컬럼 타입을 `string` 으로 바꿔버리면) 이 줄이 "unused @ts-expect-error" 에러로 실패한다. Vitest 의 `expectTypeOf` 나 `tsd` 로 타입 단언을 정식 테스트에 포함시킬 수 있다.

## 8. 선택 기준

| 항목 | Kysely | Drizzle | Prisma | 순수 SQL + Zod |
|---|---|---|---|---|
| SQL 가시성 | 높음(절 1:1) | 높음 | 낮음(자체 DSL) | 최고 |
| 타입 추론 방식 | 인터페이스 + 문자열 파싱 | 스키마 객체 + `infer` | 코드 생성 | 수동 |
| 스키마 정의 위치 | 손작성 또는 codegen | TS 코드가 원본 | `.prisma` 파일 | 없음 |
| 마이그레이션 | 별도(내장 기본기만) | drizzle-kit | 강력 | 별도 |
| 런타임 무게 | 매우 가벼움 | 가벼움 | 무거움(엔진 바이너리) | 없음 |
| 컴파일 타임 비용 | 중~높음 | 중 | 낮음(생성 코드) | 없음 |
| 복잡 쿼리 | 강함 | 강함 | 약함(raw 폴백) | 최고 |

Kysely 가 맞는 경우는 "SQL 을 직접 통제하고 싶고, 마이그레이션은 Flyway 나 별도 도구로 이미 관리하며, 타입만 얻고 싶은" 상황이다. Spring + JOOQ 조합을 Node 로 옮긴 형태에 가깝다.

Drizzle 은 스키마를 TS 코드로 선언하고 거기서 마이그레이션 SQL 을 생성한다는 점이 다르다. TS 가 단일 진실 공급원이 되길 원하면 Drizzle, DB 스키마가 이미 있고 타입만 얹고 싶으면 Kysely 가 자연스럽다.

Prisma 는 생산성이 높지만 복잡한 조인·윈도우 함수·CTE 에서 raw 쿼리로 탈출해야 하고, 그 순간 타입 안전성이 사라진다. 리포팅 쿼리가 많은 도메인이면 Kysely 쪽이 일관성 있다.

마지막으로, 어떤 선택을 하든 **DB 드라이버가 반환하는 실제 런타임 값과 타입 선언이 일치한다는 보장은 없다**. `bigint`, `numeric`, `timestamptz`, `json` 컬럼은 드라이버 설정(`pg.types.setTypeParser` 등)에 따라 타입이 바뀜다. 스키마 타입을 작성할 때 드라이버 파서 설정을 함께 문서화하고, 통합 테스트로 대표 컬럼의 런타임 타입을 검증해두는 것이 실무에서 가장 자주 새는 구멍을 막는다.

## 참고

- Kysely Documentation — Type-safe SQL query builder (https://kysely.dev)
- Kysely Source — `ColumnType`, `Selectable`, `Insertable`, `Updateable` definitions
- TypeScript Handbook — Template Literal Types and Key Remapping in Mapped Types
- TypeScript Wiki — Performance: analyzing type instantiation with generateTrace
- node-postgres Documentation — Custom type parsers
