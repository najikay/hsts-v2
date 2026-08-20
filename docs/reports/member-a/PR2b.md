# E2 PR 2b — repositories, take-exam projection, ID allocators (E2.11–E2.14)

**Branch:** `feat/e2-repos` · **Author:** Member A · **Reviewer:** Naji · **Date:** 2026-08-20

The second half of E2.9–E2.14, on top of the mapping layer you merged as `6250d3b`. This
closes E2.11–E2.14 and, with them, everything in E2 except the seed (E2.15–E2.17).

It also ticks **E2.9 and E2.10 in `docs/TODO.md`**, which PR 2a should have ticked and did
not. That was a literal DoD line missed; it is the first commit here.

## Verification

| Check | Result |
|---|---|
| `mvnw clean verify` (JDK 21) | **BUILD SUCCESS** |
| Tests | **1144** — 128 new (1016 on `main`) |
| **Skipped** | **0**, with `HSTS_REQUIRE_MYSQL=true` |
| Coverage | **98.56%** (176 missed of 12,236) — **up from 98.33%** on `main` |
| Repository suites actually ran | ✅ all 16 classes, both engines, 0 skipped |
| Allocator concurrency | ✅ on real MySQL, and proven to fail without the lock |
| Wipe order | ✅ all 20 tables populated, then wiped |

Coverage is measured against a baseline I took on this branch before writing anything, so the
comparison is to `main` exactly. It goes **up**, not down — the repositories are thin and
almost every line is exercised by a test on two engines.

## What is in it

| Task | Delivered |
|---|---|
| E2.11 | 9 repositories in `server/db/repos`, plus `RepositoryUserDirectory` |
| E2.12 | `server/db/projections/TakeExamQuestion` + `QuestionRepository.findForTakeExam` |
| E2.13 | `RepositoryTestBase` (Template Method), `TestDatabase`, `TestDatabases` |
| E2.14 | `server/db/ids`: `QuestionIdAllocator`, `ExamIdAllocator`, `CourseLock` |

## E2.12 — the part that matters most

§5 asks for the take-exam path to exclude `correct_answer` *structurally*: "a type with
nowhere to put it, not a query that happens to omit it". There are three layers here, and
they are not redundant:

1. **`TakeExamQuestion` has no correctness component.** Its ten components are asserted
   against a **whitelist**, so adding anything to the projection fails the build until
   someone edits the list — which turns "widen what a student can see" into a reviewable act
   rather than an oversight.
2. **The query never fetches the column.** `findForTakeExam` is a JPQL constructor
   expression, so `correct_answer` is not in the SELECT list at all. This is stronger than
   "the DTO drops it": the value never leaves MySQL, so it cannot surface in a heap dump, a
   slow-query log, or a debugger open during an exam.
3. **That claim is measured, not asserted.** A Hibernate `StatementInspector` records the SQL
   actually emitted and the test fails if any statement names the column. Reading the HQL
   would prove nothing about what the translator does with it.

The third test in that class is a **negative control**: it runs the authoring query, which
*does* read `correct_answer`, and requires the same assertion to trip. Without it, "no SQL
mentioned `correct_answer`" would also be true of a recorder that captured nothing, or of a
column that had been renamed — the check would pass for the wrong reason.

**The boundary is in the method names.** `findForTakeExam` returns the projection; every read
that returns a `QuestionVersion` is named `…ForAuthoring` and documented teacher-only. The
mapping layer cannot help here: it already forbids *navigating* to a question version, but a
repository that returns one directly walks straight past that. Naming it is what makes a
future reviewer notice.

### Where E2.12 landed, and why it is not where the brief implies

The brief says "a test proving the **DTO** has no correctness field". There is no take-exam
DTO to test: `common/dto` contains only `auth/` and the legacy `bank/Question`. So the
projection is a **server-side type**, and E6 or E10 maps it into a wire DTO when that DTO is
designed.

I think this is the better boundary anyway — the guarantee now sits at the query rather than
one layer above it — but it is your call, and `common/**` is outside my scope, so I could not
have done it the other way without asking. Flagged rather than assumed.

## E2.14 — the allocators, and the choice I made without asking

`MAX(serial) + 1`, never `COUNT + 1`, per §5.

`MAX + 1` is a read followed by a write, so two transactions can interleave. **I serialise
per course by locking the parent `courses` row (`SELECT … FOR UPDATE`)**, with the unique
constraint on `display_id5`/`display_id6` as the backstop. Two teachers authoring in
different courses never wait for each other.

**The alternative is a dedicated sequence table**, which is more machinery for the same
guarantee and adds a row that can disagree with the data it describes. You approved
build-and-flag for this one; the reversal cost is **one method body** (`CourseLock`) and no
schema change.

I did not take this on faith. Removing `FOR UPDATE` and re-running produced real
`Duplicate entry '11001' for key 'questions.uq_questions_display_id'` failures in both
concurrency tests — so the lock is load-bearing, the tests can fail, and the unique constraint
does catch what gets past it.

One of those two tests is deterministic rather than a race: it holds a transaction open,
starts a second allocation, and asserts the second sees the first's row. Without the lock the
second reads the same `MAX` — the uncommitted insert is invisible to it — and reuses the
serial. It fails every run, not on unlucky timing.

**Width turned out to matter.** `display_id5` is `CHAR(5)` = course(2) + serial(3) and
`display_id6` is `CHAR(6)` = subject(2) + course(2) + serial(2), but the entity fields are
`short` and `byte`, which accept 1000 and 100 happily and produce an id one character too long
for its column. Overflow is now an explicit rejection with a test. Also worth knowing: **the
exam allocator needs the subject, which `exams` does not store** — it comes from the course row
being locked anyway, so it costs no extra query, but a wrong join there would silently file
exams under the wrong subject.

## E2.13 — the test base, and a result worth reading

One abstract `…Contract` per area holds the tests; two three-line subclasses bind it to H2 and
to MySQL, so every test runs on both engines without being written twice. Engine-specific
tests (constraints, collation, concurrency) live in the MySQL leaf, where they can fail.

The wipe deletes in reverse dependency order and **never disables `FOREIGN_KEY_CHECKS`**. §5
allows switching them off provided they go back on before any insert, because the composite FK
is inert while they are off. Rather than guard that failure mode I have not created it.

**A deliberately broken wipe order passed all three H2 tests and failed immediately on MySQL.**
I broke it on purpose to check. That is the clearest single demonstration I have of why §5 asks
for both engines: H2 generates no foreign keys at all, so it certifies orderings that MySQL
refuses. It is also why the wipe order has its own test — twenty hand-maintained table names
that every other repository test silently depends on, where a mistake surfaces in whichever
test happened to run next.

MySQL is migrated **once per JVM** and shared, with isolation coming from the per-test wipe.
Per class it would have added minutes to every build. That assumes Surefire does not run
classes in parallel, which is the current default; if that ever changes, the shared database
has to become one schema per fork.

## E2.11 — repositories, deliberately thin

Nine repositories, but **only the queries something concrete needs today**, each naming its
consumer in its Javadoc. §5 specifies "query-per-need"; speculative finders would not match
the wire DTOs that eventually arrive, and each one would still have to carry test coverage.
They grow in E6–E13.

Two shapes are worth your eye because they encode a schema subtlety:

- **`ExecutionRepository.findByCode` returns a `List`, not an `Optional`.** Code uniqueness
  holds only among non-closed executions and §5 makes that a *service* rule, because MySQL has
  no partial unique index — a code is legitimately reused once its execution closes. An
  `Optional` here would assert a guarantee the schema does not make and would throw on
  perfectly valid data. `findJoinable` is the narrow one callers actually want.
- **`AttemptRepository.countParticipation` counts rather than reads.** §5 forbids counter
  columns; participation is derived from `exam_attempts` while live and frozen into the stats
  JSON at close.

## `RepositoryUserDirectory` — and a wording conflict in your file

The coordinator derivation lives in this adapter and nowhere else: stored `TEACHER` + a
`coordinators` row → wire `COORDINATOR`. Stored role has three values, wire role has four, and
because the stored side has no `COORDINATOR` at all the two cannot drift.

**`UserRecord`'s javadoc and your instruction disagree, and I followed the instruction.** The
javadoc reads "courses taught (teacher/coordinator) **or** enrolled in (student)", which reads
as a choice made by role. You told me the mapping must surface courses both taught **and**
enrolled. A teacher enrolled in a colleague's course has both, and the nav needs both, so
`CourseRepository.findForUser` returns the union — and the fixture has a teacher who is also a
student specifically so that case is covered rather than assumed. **The javadoc is in your
file; I did not edit it.**

`HSTSServer.java:59` is the one-line swap, ready when you want it:

```java
new AuthService(new RepositoryUserDirectory(HibernateUtil.sessionFactory()), sessions)
    .registerOn(router);
```

Out of my scope, so not in this PR.

## The independent red-team pass — and one finding that is not mine

Same drill as PR 1 and 2a: a reviewer with no knowledge of my reasoning, given one question —
*can a question's correct answer reach a student?*

**On the new code it came back clean**, and it checked rather than assumed: the projection has
nowhere to put a correct answer, the JPQL genuinely never fetches the column (verified against
the emitted SQL, not the HQL), the entity mapping cannot navigate to `QuestionVersion`, the
`ForAuthoring` convention is followed 2-of-2, no repository outside `QuestionRepository`
returns a type carrying an answer key, and nothing reflective can put a projection on the wire.

It found two real weaknesses in my guards, and both are fixed here.

**1. Both guards were scoped to one named artifact.** The shape test hardcodes
`TakeExamQuestion.class`; the SQL test inspects only statements from `findForTakeExam`. So the
cheapest leak was never to touch either — it was to add a *second* student-reachable read
beside them:

```java
public record ExamReviewQuestion(..., byte chosenAnswer, byte rightAnswer) { }
public List<ExamReviewQuestion> findForReview(Session session, long attemptId) { ... }
```

That is a genuinely scheduled feature (students reviewing a marked paper, E10/E11), and
**every test would have stayed green** — including because my `soundsLikeCorrectness` predicate
only matched `correct`, so `rightAnswer`, `solution` and `answerIndex` all sailed through.

`CorrectnessLeakGuardTest` now **scans instead of naming**: every record in
`server.db.projections` must have no answer-key component, and every public repository read
returning a correctness-bearing type must be named `…ForAuthoring`. The predicate is widened
and lives in one place, with a test proving it still ignores `answer1`…`answer4`.

I planted the reviewer's exact proposed leak and confirmed both halves fail the build, then
removed it. It is not a guard I believe in; it is one I watched reject the attack.

**2. `TakeExamQuestion.equals` compared illustrations by reference.** A record's generated
`equals` compares a `byte[]` component by identity, and the compact constructor clones — so two
projections built from identical inputs were never equal. Invisible today, because every seeded
illustration is NULL. **It would have started failing the day real assets land under
`docs/seed/img/`**, in list assertions and hash lookups, far from anything that looks like the
cause. Now value-based, with tests. Safe to hash on content precisely because the array is
cloned in and out, so it cannot change underneath a key.

### Not mine, and it needs your decision: the legacy verbs are unauthenticated by role

The reviewer went looking for leaks and found a live one **outside E2**. I verified it myself
rather than relaying it:

- `MessageRouter` has **no role dimension at all** — no `Map<Verb, Set<Role>>`. The entire
  authorization check is `MessageRouter.java:145`: `if (!openVerbs.contains(verb) &&
  !caller.isAuthenticated())`. It asks *whether* you are signed in, never *as what*.
- `LegacyQuestionHandlers` takes a `CallerContext caller` in both handlers and **never reads
  it** — no `Authorization.requireRole`, no role branch.
- `QuestionDAO` selects `id, question_text, answer` and updates `question_text, answer`.

So **any authenticated student can call `GET_ALL_QUESTIONS` and receive plaintext answers, and
`UPDATE_QUESTION` to rewrite question text and answers.** The client only offers the screen to
teachers, but that check runs on the attacker's machine. `common/dto/bank/Question` is
`Serializable`, so it goes over the wire cleanly.

Two honest qualifications. This reads the **legacy** `Questions` table, not
`question_versions.correct_answer` — and that table is currently empty on machines that
followed the PR 1 instruction to recreate `hsts_db`. That is a data-state accident, not a
control: the verb is registered, unguarded, reachable, and the demo path repopulates the table.

**It is pre-existing (E1/E5 prototype), not introduced here, and `server/core/**` and
`server/features/**` are outside my scope**, so I have not touched it. The fix looks like one
line at the top of each handler — `Authorization.requireRole(caller, Role.TEACHER,
Role.COORDINATOR);` — and the router already maps `AuthorizationException` to
`ERROR(FORBIDDEN)`. The class javadoc says a guard needs E2's course repositories, but a
blanket role gate needs no course data at all, so this is not blocked on me. Your call whose
epic it belongs to.

One smaller pre-existing note from the same pass: `MessageRouter.java:179-183` swallows any
send failure with a WARN, so a non-`Serializable` payload leaves the client's future hanging
until timeout with no error. Safe, but unpleasant to diagnose.

## Still open from PR 2a

PR 2a merged without review comments, so these four never got an answer. **None of them blocks
anything and none needs a decision today** — I am restating them with enough context to answer
in one pass, because they are all cheap now and expensive after E6 builds on this layer.

Each is a yes/no. My recommendation is stated so silence is a workable answer.

| # | Question | Why it matters | My recommendation | Cost to change later |
|---|---|---|---|---|
| 1 | Add `@JdbcTypeCode(SqlTypes.JSON)` to the three JSON columns? | They are native `JSON` in MySQL but `mediumtext` in H2, and Connector/J reports JSON as `LONGVARCHAR`, so validation passes either way. Fine today; MySQL's key reordering and whitespace normalisation are invisible to the fast suite, and any `JSON_EXTRACT` query in E15 would work on MySQL and fail on H2. | **Leave it** until E15 actually needs a JSON query. I did not want to change storage behaviour for a hypothetical. | 3 annotations, no migration — but rewriting E15 queries if it is left too long |
| 2 | Drop the now-redundant `exam_version_questions` primary key? | With the composite FK in place, `question_version_id` determines `question_id`, so the PK and `UNIQUE(exam_version_id, question_id)` forbid the same thing. Not wrong, no extra index. | **Keep it.** You asked for it explicitly in the round-1 review; I am only flagging that the reason it existed has since been covered by something else. | A migration — so this is the one worth deciding before more data exists |
| 3 | Duplicate the CHECK constraints as `@Check` so H2 enforces them? | H2 reproduces no CHECK constraints and no foreign keys, so "correct answer is 1..4", "points 1..100", "close_at > open_at" and the composite FK are unexercisable on the fast suite. | **Don't.** Constraint logic in two places is what §5's deletion-policy rule warns against. The MySQL suite is the right home. | Annotations only, no migration |
| 4 | Anything to do about the H2 collation blind spot? | Production is `utf8mb4_unicode_ci` and compares case-insensitively; H2 does not reproduce it. Load-bearing for the 4-character execution code (C-1) and the pairwise-distinct answer rule (ADR-016). | **Already handled by convention** — case-sensitivity tests go in the MySQL leaf. Recorded in `H2Support`'s javadoc and now in the `/repo-pair` checklist. | None, unless someone writes such a test on H2 by mistake |

**Question 3 became concrete in this PR.** It is exactly why the wipe-order test and both
allocator concurrency tests live in the MySQL leaf: I broke the wipe order deliberately and H2
passed all three tests while MySQL refused instantly. That is the cost of H2's missing
constraints, measured rather than argued — and it is the strongest evidence I have that
duplicating them into `@Check` would buy less than it looks like it would.

## What I need from you — index

Nothing here blocks the merge. In rough order of how much it costs to leave:

| | Ask | Where |
|---|---|---|
| 🔴 | **The legacy verbs have no role check** — any signed-in student can read and rewrite question answers. Pre-existing, outside my scope, needs an owner. | *The independent red-team pass* |
| 🟡 | `UserRecord`'s javadoc says courses are "taught **or** enrolled"; your instruction was both. I followed the instruction; the wording is in your file. | *RepositoryUserDirectory* |
| 🟡 | `exam_version_questions` PK — the only open item that would need a migration later. | *Still open from PR 2a*, #2 |
| ⚪ | Allocator serialised by parent-row lock rather than a sequence table. Reversal: one method body. | *E2.14* |
| ⚪ | E2.12 landed as a server-side projection because no wire DTO exists and `common/**` is outside my scope. | *Where E2.12 landed* |
| ⚪ | `HSTSServer.java:59` one-line swap to the repository-backed directory, ready when you want it. | *RepositoryUserDirectory* |

## Definition of Done

- [x] Matches ARCHITECTURE §5 / the PRD ids named in the task
- [x] Unit + repo tests; coverage 98.56%, up from 98.33% on `main`
- [x] Migrations unchanged by this PR
- [x] No secrets, no dummy-credential changes in resources
- [x] TODO.md — E2.9–E2.14 ticked here
- [x] CI green — run 32317040495, 1m50s

## Next

**PR 3 (E2.15–E2.17)** is unblocked now that `docs/seed/SEED_CONTENT.md` is on `main`. Noted
for it: illustrations load as NULL and stay idempotent when the real assets arrive; the Algebra
Midterm pins question 11005 **version 1** in *both* exam-1 versions, not only the released one
(seed §8.1); seed password stays `demo123`; wipes delete in reverse-dependency order.

`docs/DEMO_ACCOUNTS.md` already exists, so E2.17 is a re-point rather than a fresh write. One
detail for it: that file lists `rina.barak` as `COORDINATOR`, which is the *wire* role — stored
is `TEACHER` plus a `coordinators` row, and the re-pointed file should say so.
