# E2 PR 2a — JPA mapping layer (E2.9, E2.10)

**Branch:** `feat/e2-entities` · **Author:** Member A · **Reviewer:** Naji · **Date:** 2026-08-19

The foundation half of E2.9–E2.14, split as you approved: entities, enums, JSON converters,
`HibernateUtil` and `Transactions`. Repositories, the E2.12 projection, the repo test base and the
ID allocators are PR 2b, which is built entirely on this.

20 entities — one per table in `V1__core.sql`…`V7__notifications.sql`, verified by diffing the mapped
table names against the migrations: exact match, none missed, none invented.

## Verification

| Check | Result |
|---|---|
| `mvnw clean verify` (JDK 21) | **BUILD SUCCESS** |
| Tests | 1000 (was 991 on `main` before this branch) — 66 new across 8 classes |
| **Skipped** | **0**, with `HSTS_REQUIRE_MYSQL=true` |
| Coverage | 98% (192 missed of 11,198) |
| Mapping matches the real Flyway schema | ✅ on MySQL 8.0, two independent checks — see below |
| Entity registry matches the package | ✅ the classpath scan production deliberately avoids |
| Optimistic locking rejects a stale write | ✅ end to end, through `lock_version` |

**Coverage is 0.5 points below `main`'s 98.8%** and I would rather say so than round it off. This PR
adds ~2,000 instructions of persistence code; the residue is the production boot path in
`HibernateUtil` and `DbBootstrap.migrate()`, which is exercised where a real `hsts_db` exists but not
otherwise.

## The check that earns the mapping its trust

You agreed H2 validates mappings and only the MySQL suite validates the real schema. There is a trap
in the first half worth naming: when H2 builds its schema **from these same entities**, the entities
are being compared against themselves. That test cannot fail. It stays green through a renamed
column, a wrong size, a missing `lock_version` — every kind of drift it appears to guard.

So the load-bearing test migrates with Flyway and *then* starts Hibernate against it. It failed on its
first three runs and caught three distinct classes of drift, none of which H2 could ever have seen:

- `@Lob byte[]` silently maps to **`tinyblob`** — a 255-byte column where the migration made 16 MB. A
  real question image would have failed to insert, in production, long after the mapping was written.
- every `TEXT`/`MEDIUMTEXT` column was mapped as `varchar(255)`.
- every fixed-width code column (`CHAR(2)`, `CHAR(4)`…) was mapped as `varchar`, which changes padding
  and comparison semantics.

There is a companion test that points the same validation at an empty database and requires it to
**fail**, so the check cannot quietly become inert.

### …and a correction to what that test claims

I told you in the PR 2 notes that `hbm2ddl validate` proves the mappings match the schema. **That was
overstated, and the audit proved it rather than arguing it:** it mapped `users.username` as
`length=4000, nullable=true` against a `varchar(50) NOT NULL` column, pointed validation at the
migrated schema, and validation *passed*.

Hibernate checks table existence, column existence, and a coarse type family — `equivalentTypes`
treats VARCHAR/LONGVARCHAR/NVARCHAR as interchangeable. It never compares length, precision,
nullability, unique constraints, foreign keys or enum members. My three catches above all crossed
type-code boundaries; drift *within* a family was invisible.

Rather than soften the claim I made it true. `SchemaColumnComparisonTest` reads
`information_schema.columns` for the freshly migrated schema and compares each against Hibernate's own
mapping model: nullability, varchar length, explicit column type, datetime precision, and any column
no entity maps. It found real drift on its first run.

It also closes a trap the audit found: **adding a constant to one of the seven Java enums without an
`ALTER TABLE … MODIFY` migration** compiles, passes validation, and then fails at the first insert with
"Data truncated for column" — with nothing in the build pointing at the cause. The test now compares
enum members on both sides, and `db/migration/README.md` states the rule.

## Conventions this establishes — the one worth arguing about

`package-info.java` states four, and the audit verified each is literally true in the code. Three are
uncontroversial. This one is not:

**There are no JPA associations anywhere.** Every foreign key is a scalar field — no `@ManyToOne`, no
`@OneToMany`, no cascade. §5 specifies repositories as "query-per-need, with projections for wire
DTOs", which is a design that never walks an object graph. Mapping one would buy nothing and bring the
whole familiar tax: N+1 selects, `LazyInitializationException` the moment a DTO is built outside the
session, and cascade settings that quietly disagree with the database's own `ON DELETE` rules. It also
makes the composite FK on `exam_version_questions` expressible at all.

The audit's judgement on how it holds up for 2b is worth quoting: it **helps** E2.12, because nothing
can navigate from an `ExamVersionQuestion` to a `QuestionVersion` object, so the take-exam mapper
physically cannot reach `correct_answer` through a graph. Cross-entity HQL joins still work, so
nothing is blocked. The residual leak surface is not the mapping — it is any repository method that
returns `QuestionVersion` itself to a student-reachable path. **That is what I will point the E2.12
red-teamer at in 2b.**

Two smaller decisions worth your eye:

- **Every timestamp is `Instant`, and `jdbc.time_zone` is pinned to UTC** in `HibernateUtil`, with
  `precision = 3` on all 21 columns. Hibernate's default temporal precision is 6: left alone it writes
  microsecond values into `DATETIME(3)`, MySQL rounds them, and a round-trip assertion using a real
  clock fails on MySQL while passing on H2. This makes §5's "all DATETIME values are UTC" a property
  of the type system rather than a rule people have to remember.
- **`JsonCodec` hand-rolls `Instant` serialization** in about twenty lines instead of using
  `JavaTimeModule`. `jackson-datatype-jsr310` does resolve on this classpath — but only
  *transitively*; nothing in the pom asks for it. Building the serialization of stored data on a
  dependency that arrives by accident means a future bump elsewhere silently breaks reading rows
  already written, which is the worst kind of breakage: it appears long after the change that caused
  it, in data nobody can re-derive.

## Where I went beyond the task, and what it costs to undo

**`HibernateUtil.install()`** — package-private test seam that points the shared factory at H2. I want
to name it plainly: this is production API added partly for testability. It earns its place
independently because E6's service tests call `Transactions.inTx(...)` with no factory argument, and
being able to run those without a MySQL server on every machine is the difference between a fast suite
and a slow one. It refuses to replace an existing factory rather than silently orphaning it. *Revert
cost: delete one method and three tests.*

**Unique constraints declared on the entities.** The audit counted what H2 actually generates: 20
`CREATE TABLE`s and **zero** constraints — no unique, no foreign key, no check, no index. That is the
biggest thing that would have bitten PR 2b, because a test asserting "two concurrent ID allocations
collide" passes on H2 even with a completely broken allocator, and E2.14 is 2b's own deliverable. The
seven relevant unique constraints are now declared explicitly so H2 enforces them. MySQL validation
ignores them, so there is no drift risk in the other direction. *Revert cost: seven annotations.*

## The independent audit

Same drill as PR 1, and it was worth it again. This one generated the DDL Hibernate actually emits,
diffed it mechanically against `information_schema`, probed the validator's real teeth with a
deliberately-wrong entity, inspected bytecode-enhancement state on the persister, and re-ran the suite
under randomised method order. Findings were reproduced, not inferred.

**Field-by-field correspondence came back clean:** 0 unmapped schema columns, 0 entity-only columns, 0
nullability mismatches, `@Version` on exactly the six tables §5 names and nowhere else.

Three real defects in what I wrote, all fixed here:

1. **`@Basic(fetch = LAZY)` on the two `@Lob` fields was completely inert.** Lazy *basic* attributes
   need build-time bytecode enhancement; there is no enhancement plugin, so JPA ignores the hint — the
   persister reported the attributes as eager. My Javadoc claimed laziness that did not exist, which
   is worse than no annotation because it reads as a solved problem. Removed, and both classes now say
   plainly that the listing queries in E6.9 and F12.3 must use scalar projections that never name the
   blob column — the same structural move E2.12 makes for `correct_answer`.
2. **`EntityRoundTripTest` was order-dependent**, reproduced failing under three random seeds. Four
   tests mutated and committed to the shared graph that others asserted was pristine. I had spotted
   the hazard for one test and missed it in four. Fixed structurally — a fresh database per test, plus
   randomised ordering so it fails immediately if anyone reintroduces shared state — rather than by a
   rule the next author has to remember.
3. **`install()` could strand a live production factory:** it overwrote the singleton without touching
   the pool, leaving an unreachable, never-closed `SessionFactory` while `shutdown()` closed the pool
   underneath it. Now refuses to replace.

Also fixed: `H2Support` leaked a pool *and* a database per call (`DB_CLOSE_DELAY=-1`, so both survive
the JVM — harmless at four test classes, not at one per repository in 2b); `build()` caught only
`RuntimeException` where a `LinkageError` is exactly how the JDK-24/ByteBuddy failure presents;
`inTx(null)` booted a real MySQL pool before discovering its argument was null; `JsonCodec` threw
`DateTimeParseException` instead of the documented `UncheckedIOException` on a numeric or null
timestamp — reachable for any row written before `WRITE_DATES_AS_TIMESTAMPS` was disabled.

I also found and fixed a leak of my own while chasing coverage: `HibernateUtil.sessionFactory()` built
a HikariCP pool and handed it to Hibernate, which only ever closes a `DataSource` it created itself. So
`shutdown()` closed the factory and left ten connection threads alive for the life of the JVM.

## Passed to you, not acted on

| # | Finding | Why it is yours |
|---|---|---|
| 1 | **The three JSON columns are `mediumtext` in H2, native `JSON` in MySQL.** Validation passes because Connector/J reports JSON as `LONGVARCHAR`. Functionally fine today — MySQL coerces the string and Jackson round-trips — but MySQL's key reordering and whitespace normalisation are invisible to the fast suite, and any `JSON_EXTRACT` query in E15 would work on MySQL and fail on H2. | `@JdbcTypeCode(SqlTypes.JSON)` alongside the converter would close it. I did not want to change storage behaviour on the strength of a hypothetical E15 query without asking. |
| 2 | **`exam_version_questions`' primary key is now redundant.** With the composite FK in place, `question_version_id` determines `question_id`, so the PK and `UNIQUE(exam_version_id, question_id)` forbid the same thing. Not wrong, no extra index — but the table's real identity is one row per *question* per exam version. | You asked me explicitly to keep the PK in the round-1 review, so I did. |
| 3 | **H2 reproduces no foreign keys and no CHECK constraints**, because the entities map no associations and declare no `@Check`. So "correct answer is 1..4", "points 1..100", "close_at > open_at" and the composite FK are unexercisable on the fast suite. I closed the unique-constraint half; the rest would mean adding `@Check` annotations that duplicate the migrations. | Duplicating constraint logic in two places is the thing §5's deletion-policy rule warns against. My inclination is to leave it and rely on the MySQL suite; say if you disagree. |
| 4 | **Collation is not reproduced on H2.** Production is `utf8mb4_unicode_ci`, which compares case-insensitively — load-bearing twice: entering the 4-character execution code (C-1) and the pairwise-distinct answer rule (ADR-016). A case-sensitivity test could pass on one engine and fail on the other. | Recorded in `H2Support`'s javadoc so whoever writes those tests in E9/E6 puts them in the MySQL suite. |

## Definition of Done

- [x] Matches ARCHITECTURE §5 / the PRD ids named in the task — including the round-2 block in
      `93ddc11`, which I checked point for point
- [x] Unit + repo tests; coverage 98% (0.5 points under `main`, stated above rather than rounded)
- [x] Migrations unchanged by this PR; the mapping is proved against them on real MySQL
- [x] No secrets, no dummy-credential changes in resources
- [ ] TODO.md — E2.9 and E2.10 tick when this merges
- [ ] CI green — after push

## Next

**PR 2b:** repositories, `RepositoryUserDirectory` (in `server/db/repos`, with the `HSTSServer`
construction line as your patch), the defence-critical E2.12 projection, the E2.13 base class and the
E2.14 allocators. Two things from your last answer are noted for it: the `UserRecord` mapping must
surface courses both taught **and** enrolled, and the coordinator derivation lives in that adapter and
nowhere else.

**PR 3** is still waiting on `docs/seed/SEED_CONTENT.md` from Member B. He has the `national_id`
constraint and the derived-coordinator shape.
