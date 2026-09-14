Notion 원본: https://www.notion.so/3db5a06fd6d3810ab772d8517b98a612

# TypeScript Drizzle ORM 스키마 추론과 관계 쿼리 타입 및 마이그레이션 생성

> 2026-09-15 신규 주제 · 확장 대상: TypeScript Kysely 타입 안전 쿼리 빌더와 스키마 제네릭 추론

## 학습 목표

- 스키마 선언에서 `$inferSelect` / `$inferInsert` 가 도출되는 경로를 추적한다
- 부분 선택·조인·관계 쿼리의 반환 타입이 어떻게 좁혀지는지 확인한다
- `relations()` 기반 중첩 조회가 만들어내는 SQL 과 N+1 회피 방식을 파악한다
- `drizzle-kit` 스냅샷 diff 로 마이그레이션을 생성하고 위험한 변경을 분류한다

## 1. 설계 전제 — 코드 생성 없는 타입 추론

Prisma 는 `schema.prisma` 를 파싱해 클라이언트 코드를 생성한다. 타입은 생성물이고, 스키마를 고치면 `prisma generate` 를 다시 돌려야 한다. Drizzle 은 그 단계를 없앴다. 스키마 자체가 TypeScript 값이고, 타입은 그 값에서 `typeof` 로 추론된다.

```ts
// src/db/schema.ts
import { pgTable, serial, text, timestamp, integer, boolean, index } from "drizzle-orm/pg-core";

export const users = pgTable("users", {
  id: serial("id").primaryKey(),
  email: text("email").notNull().unique(),
  displayName: text("display_name"),
  isActive: boolean("is_active").notNull().default(true),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
});

export const posts = pgTable(
  "posts",
  {
    id: serial("id").primaryKey(),
    authorId: integer("author_id")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    title: text("title").notNull(),
    body: text("body").notNull(),
    publishedAt: timestamp("published_at", { withTimezone: true }),
  },
  (table) => ({
    authorIdx: index("posts_author_idx").on(table.authorId),
  }),
);
```

컬럼 빌더는 체이닝할 때마다 제네릭 파라미터를 갱신하는 팬텀 타입이다. `text("email")` 은 `notNull: false` 상태의 타입을 반환하고, `.notNull()` 은 그 플래그만 `true` 로 바꾼 새 타입을 반환한다. `.default(...)` 는 `hasDefault: true` 를 세운다. 이 세 플래그가 나중에 select/insert 타입을 가른다.

```ts
export type User = typeof users.$inferSelect;
// { id: number; email: string; displayName: string | null; isActive: boolean; createdAt: Date }

export type NewUser = typeof users.$inferInsert;
// { id?: number; email: string; displayName?: string | null; isActive?: boolean; createdAt?: Date }
```

규칙은 단순하다. **select 타입**은 `notNull` 이 아니면 `| null` 을 붙인다. **insert 타입**은 `notNull && !hasDefault` 인 컬럼만 필수로 남기고 나머지는 선택 프로퍼티가 된다. `serial` 은 기본값을 갖는 것으로 취급되므로 `id` 가 선택이 된다.

여기서 얻는 실질적 이점은 스키마와 타입 사이에 **동기화 지점이 존재하지 않는다**는 것이다. 생성물이 없으니 최신이 아닐 수가 없다. 대신 비용은 타입 체크 시간으로 옮겨간다. 컬럼 수가 많은 테이블 수십 개를 선언하면 인스턴스화가 상당히 늘어나므로, `.d.ts` 를 내보내는 패키지라면 `typeof` 재추론이 소비 측에서 반복되지 않도록 `User`, `NewUser` 같은 이름 있는 타입으로 고정해 export 하는 편이 낫다.

## 2. 쿼리 빌더의 반환 타입 좁히기

Drizzle 의 SQL-like API 는 SQL 절 순서를 그대로 따른다.

```ts
import { drizzle } from "drizzle-orm/node-postgres";
import { eq, and, isNotNull, desc, sql } from "drizzle-orm";
import * as schema from "./schema";

const db = drizzle(pool, { schema });

const rows = await db
  .select()
  .from(posts)
  .where(and(eq(posts.authorId, 42), isNotNull(posts.publishedAt)))
  .orderBy(desc(posts.publishedAt))
  .limit(20);
// rows: (typeof posts.$inferSelect)[]
```

부분 선택을 하면 반환 타입이 선택한 모양 그대로 좁혀진다.

```ts
const summaries = await db
  .select({
    id: posts.id,
    title: posts.title,
    authorEmail: users.email,
    bodyLength: sql<number>`length(${posts.body})`,
  })
  .from(posts)
  .innerJoin(users, eq(users.id, posts.authorId));
// summaries: { id: number; title: string; authorEmail: string; bodyLength: number }[]
```

`sql<number>` 의 제네릭 인자는 **개발자가 컴파일러에 하는 약속**이다. Drizzle 은 그 SQL 조각이 실제로 어떤 타입을 돌려주는지 알 수 없으므로 검증하지 않는다. `length()` 가 Postgres 에서 `integer` 를 반환한다는 사실은 사람이 보장해야 하고, 여기서 틀리면 런타임에 조용히 잘못된 타입이 흐른다. 이 지점이 Drizzle 타입 안전성의 실질적 경계다.

조인에서 주의할 점이 하나 더 있다. `leftJoin` 을 쓰고 `select()` 를 인자 없이 호출하면, 조인된 테이블 쪽 전체가 `| null` 이 된다.

```ts
const joined = await db.select().from(users).leftJoin(posts, eq(posts.authorId, users.id));
// joined: { users: User; posts: Post | null }[]
```

테이블 단위로 `null` 이 붙는다는 점이 중요하다. 컬럼별 `null` 이 아니라 "posts 객체 전체가 없거나 있다"로 모델링되므로, `row.posts?.title` 형태로 접근하게 되고 이것이 SQL 의 outer join 의미론과 정확히 맞는다.

## 3. relations() 와 관계 쿼리

`select()` 계열은 행(row) 단위 평면 결과를 준다. 중첩 구조가 필요하면 `relations()` 를 선언하고 `db.query` 를 쓴다.

```ts
// src/db/schema.ts (이어서)
import { relations } from "drizzle-orm";

export const usersRelations = relations(users, ({ many }) => ({
  posts: many(posts),
}));

export const postsRelations = relations(posts, ({ one }) => ({
  author: one(users, { fields: [posts.authorId], references: [users.id] }),
}));
```

`relations()` 는 SQL 상의 외래 키를 만들지 않는다. 순수하게 **쿼리 계층의 메타데이터**다. 실제 FK 제약은 앞에서 본 `.references()` 가 만든다. 둘을 혼동하면 "관계는 선언했는데 DB 에 제약이 없다"는 상황이 생긴다.

```ts
const result = await db.query.users.findMany({
  where: eq(users.isActive, true),
  columns: { id: true, email: true },
  with: {
    posts: {
      columns: { id: true, title: true },
      where: isNotNull(posts.publishedAt),
      orderBy: desc(posts.publishedAt),
      limit: 5,
    },
  },
});
// result: { id: number; email: string; posts: { id: number; title: string }[] }[]
```

타입은 `columns` 와 `with` 를 따라 정확히 좁혀진다. `columns` 에 지정하지 않은 필드는 결과 타입에 아예 없고, `with` 안의 중첩도 재귀적으로 같은 규칙을 따른다. `one(...)` 관계는 단일 객체 또는 `null`, `many(...)` 는 배열로 나온다.

실행되는 SQL 이 핵심이다. Drizzle 의 관계 쿼리는 관계 하나당 쿼리 하나를 날리지 않고, **단일 쿼리로 중첩 JSON 을 만든다**. Postgres 기준으로는 lateral 서브쿼리와 JSON 집계 함수를 조합한 형태가 나간다.

```sql
select "users"."id", "users"."email", "posts_sub"."data" as "posts"
from "users"
left join lateral (
  select coalesce(json_agg(json_build_array("p"."id", "p"."title")), '[]'::json) as "data"
  from (
    select "posts"."id", "posts"."title"
    from "posts"
    where "posts"."author_id" = "users"."id" and "posts"."published_at" is not null
    order by "posts"."published_at" desc
    limit 5
  ) "p"
) "posts_sub" on true
where "users"."is_active" = true;
```

이 구조의 장점은 명확하다. 라운드트립이 1회이고, 자식 쪽 `limit`/`order by` 가 **부모 행마다 독립적으로** 적용된다. 평범한 `left join` 으로는 "사용자별 최신 글 5개"를 표현할 수 없고 윈도우 함수를 직접 써야 하는데, lateral 이 그 일을 대신한다.

단점도 있다. 자식 집합이 큰 경우 JSON 직렬화 비용이 DB 쪽에 몰리고, 결과 크기가 커진다. 부모 1000행 × 자식 평균 200행 같은 조회는 관계 쿼리 대신 두 번의 평면 조회 후 애플리케이션에서 그룹핑하는 편이 빠른 경우가 많다. 판단 기준은 **자식 행 수에 상한이 있는가**다. `limit` 를 걸 수 있으면 관계 쿼리, 없으면 분리 조회를 기본값으로 삼는다.

## 4. Prepared Statement 와 플레이스홀더

쿼리 빌더는 호출할 때마다 SQL 문자열을 다시 조립한다. 핫 패스에서는 이 비용이 측정될 만큼 나온다. `.prepare()` 로 한 번만 만들고 재사용한다.

```ts
import { sql } from "drizzle-orm";

const findPostsByAuthor = db
  .select({ id: posts.id, title: posts.title })
  .from(posts)
  .where(eq(posts.authorId, sql.placeholder("authorId")))
  .limit(sql.placeholder("limit"))
  .prepare("find_posts_by_author");

const page1 = await findPostsByAuthor.execute({ authorId: 42, limit: 20 });
```

`execute()` 의 인자 타입은 사용된 플레이스홀더 이름들로부터 추론된다. 오타를 내면 컴파일 에러가 난다. Postgres 드라이버에서는 이것이 서버 측 prepared statement 로도 이어져 파싱·계획 재사용 이득이 추가된다. 단, PgBouncer 를 transaction 모드로 두면 서버 측 prepare 가 세션에 묶이지 않으므로 이 이득은 사라지고 SQL 조립 생략 효과만 남는다.

동적 조건이 필요하면 `$dynamic()` 으로 빌더를 재할당 가능한 형태로 바꾼다.

```ts
function buildPostQuery(filter: { authorId?: number; published?: boolean }) {
  let q = db.select().from(posts).$dynamic();
  if (filter.authorId !== undefined) q = q.where(eq(posts.authorId, filter.authorId));
  if (filter.published) q = q.where(isNotNull(posts.publishedAt));
  return q;
}
```

`$dynamic()` 없이는 `.where()` 호출 후 빌더 타입이 바뀌어 재할당이 막힌다. 이는 실수로 `where` 를 두 번 부르는 것을 타입으로 막으려는 설계인데, 동적 조합에서는 방해가 되므로 명시적으로 해제하는 구조다.

## 5. 트랜잭션

```ts
await db.transaction(async (tx) => {
  const [user] = await tx
    .insert(users)
    .values({ email: "a@example.com", displayName: "A" })
    .returning();

  await tx.insert(posts).values({ authorId: user.id, title: "첫 글", body: "..." });

  await tx.transaction(async (inner) => {
    // 중첩 트랜잭션은 SAVEPOINT 로 구현된다
    await inner.update(users).set({ isActive: false }).where(eq(users.id, user.id));
    inner.rollback(); // SAVEPOINT 까지만 되돌린다
  });
});
```

`tx` 는 `db` 와 같은 인터페이스를 갖지만 별도 타입이다. 트랜잭션 안에서 실수로 `db` 를 쓰면 다른 커넥션으로 나가버리는데, Drizzle 은 이것을 타입으로 막아주지 않는다. 리포지토리 함수 시그니처를 `(tx: Executor, ...)` 형태로 통일하고 ESLint 규칙으로 트랜잭션 콜백 안의 `db` 참조를 금지하는 관행이 필요하다.

```ts
import type { NodePgDatabase } from "drizzle-orm/node-postgres";
import type * as schema from "./schema";

export type Db = NodePgDatabase<typeof schema>;
export type Tx = Parameters<Parameters<Db["transaction"]>[0]>[0];
export type Executor = Db | Tx;
```

## 6. drizzle-kit 마이그레이션 — 스냅샷 diff

```ts
// drizzle.config.ts
import { defineConfig } from "drizzle-kit";

export default defineConfig({
  schema: "./src/db/schema.ts",
  out: "./drizzle",
  dialect: "postgresql",
  dbCredentials: { url: process.env.DATABASE_URL! },
  verbose: true,
  strict: true,
});
```

```bash
npx drizzle-kit generate --name add_posts_published_at
npx drizzle-kit migrate
```

`generate` 는 DB 에 접속하지 않는다. `drizzle/meta/` 아래에 있는 **직전 스냅샷 JSON** 과 현재 스키마 파일을 비교해 차이를 SQL 로 뽑고, 새 스냅샷을 기록한다. 즉 마이그레이션의 진실 원천은 실제 DB 가 아니라 리포지토리에 커밋된 스냅샷 체인이다.

```
drizzle/
├── 0000_initial.sql
├── 0001_add_posts_published_at.sql
└── meta/
    ├── _journal.json
    ├── 0000_snapshot.json
    └── 0001_snapshot.json
```

`migrate` 는 DB 의 `__drizzle_migrations` 테이블에서 적용된 해시를 읽어 미적용분만 실행한다. 이 설계의 함의가 몇 가지 있다.

- **스냅샷이 곧 상태다.** 누군가 DB 에 수동으로 컬럼을 추가하면 스냅샷은 모른다. `drizzle-kit pull`(introspect)로 실제 DB 에서 스키마를 역생성해 대조해야 한다.
- **브랜치 병합 시 충돌한다.** 두 브랜치가 각각 `0001` 을 만들면 번호와 `_journal.json` 이 충돌한다. 해결은 나중 브랜치의 마이그레이션을 재생성하는 것이지 수동 번호 변경이 아니다.
- **이름 변경은 감지되지 않는다.** 컬럼 `name` → `display_name` 변경은 diff 상 "drop + add" 로 보인다. `--strict` 모드에서는 대화형으로 rename 여부를 묻지만, CI 비대화형 실행에서는 데이터 손실 DDL 이 그대로 생성될 수 있다. 생성된 SQL 은 반드시 사람이 리뷰한다.

`push` 는 마이그레이션 파일 없이 스키마를 DB 에 직접 반영한다. 로컬·프리뷰 환경 전용으로만 쓰고 운영에는 쓰지 않는다.

| 명령 | 동작 | 적합한 환경 |
|---|---|---|
| `generate` | 스냅샷 diff → SQL 파일 생성, DB 접속 없음 | 모든 환경, PR 에 파일 커밋 |
| `migrate` | 미적용 SQL 순차 실행, 이력 테이블 갱신 | 스테이징·운영 |
| `push` | 스키마를 DB 에 직접 동기화, 이력 없음 | 로컬·일회성 프리뷰 |
| `pull` | 실제 DB → 스키마 코드 역생성 | 레거시 도입, 드리프트 점검 |
| `check` | 스냅샷 체인 정합성 검증 | CI 사전 검사 |

위험한 DDL 은 생성 후 수동으로 분할하는 것이 안전하다. 예를 들어 `NOT NULL` 컬럼 추가는 다음 3단계로 나눈다.

```sql
-- 0002: nullable 로 추가 (즉시, 락 짧음)
ALTER TABLE "posts" ADD COLUMN "slug" text;

-- 애플리케이션 배포: 쓰기 경로에서 slug 채우기 + 백필 배치

-- 0003: 제약 추가 (NOT VALID → VALIDATE 로 락 시간 분리)
ALTER TABLE "posts" ADD CONSTRAINT "posts_slug_not_null" CHECK ("slug" IS NOT NULL) NOT VALID;
ALTER TABLE "posts" VALIDATE CONSTRAINT "posts_slug_not_null";
```

## 7. 다른 선택지와의 비교

| 항목 | Drizzle | Kysely | Prisma |
|---|---|---|---|
| 타입 출처 | 스키마 코드에서 추론 | 수동 `Database` 인터페이스 또는 codegen | `schema.prisma` codegen |
| 코드 생성 | 없음(마이그레이션 SQL 만) | 선택(kysely-codegen) | 필수 |
| 관계 중첩 조회 | `db.query` + lateral 단일 쿼리 | 직접 작성(`jsonArrayFrom` 헬퍼) | `include` 지원, 내부적으로 분리 쿼리 |
| 런타임 크기 | 작음, 엣지 런타임 적합 | 작음 | 쿼리 엔진 바이너리 필요(엔진리스 모드 존재) |
| 마이그레이션 | 스냅샷 diff | 직접 작성(마이그레이션 러너 제공) | 선언형 diff, 성숙도 높음 |
| 타입체크 비용 | 스키마 규모에 비례해 증가 | 인터페이스가 이미 구체 타입이라 저렴 | 생성된 구체 타입이라 저렴 |

Kysely 는 데이터베이스 인터페이스를 이미 구체적인 타입으로 들고 시작하므로 체크가 싸다. Drizzle 은 그 인터페이스를 스키마 값에서 유도하는 만큼 추론 작업이 더 많다. 테이블 100개 이상, 컬럼 2000개 이상 규모에서는 `$inferSelect` 결과를 이름 있는 타입으로 고정하고 배럴 파일 재export 를 줄이는 대응이 필요하다.

Prisma 대비로는 "생성 단계 없음"과 "SQL 에 가까운 표현력"이 이득이고, "성숙한 마이그레이션 도구"와 "대규모 커뮤니티 레시피"가 손해다. 마이그레이션 안전성을 도구에 위임하고 싶다면 Prisma, 스키마 진화를 직접 통제하고 싶다면 Drizzle 이 맞는다.

## 8. 도입 시 점검 목록

- 스키마 파일은 도메인별로 분할하되, `drizzle.config.ts` 의 `schema` 에 글롭으로 모두 포함시킨다. 하나라도 빠지면 diff 가 "테이블 삭제"로 나온다.
- `relations()` 와 `.references()` 를 둘 다 선언한다. 전자는 쿼리용, 후자는 DDL 용이다.
- `sql<T>` 의 `T` 는 검증되지 않으므로, 집계·캐스팅이 들어간 원시 SQL 은 별도 단위 테스트로 실제 반환 타입을 확인한다.
- 생성된 마이그레이션 SQL 은 리뷰 필수. 특히 `DROP COLUMN`, `ALTER COLUMN TYPE`, `NOT NULL` 추가는 배포 전략과 함께 본다.
- `drizzle-kit check` 를 CI 에 넣어 스냅샷 체인이 깨진 PR 을 막는다.
- 커넥션 풀러를 transaction 모드로 쓰면 prepared statement 이득이 사라진다는 점을 성능 기대치에 반영한다.

## 참고

- Drizzle ORM 공식 문서 (orm.drizzle.team)
- Drizzle Kit 마이그레이션 문서 — generate / migrate / push / pull
- PostgreSQL 공식 문서 — LATERAL 서브쿼리, JSON 집계 함수
- PostgreSQL 공식 문서 — ALTER TABLE 락 수준과 NOT VALID 제약
- Kysely 공식 문서 — Relations 헬퍼 (`jsonArrayFrom`)
