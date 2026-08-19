# E2 PR 1 — migrations + Flyway bootstrap (E2.1–E2.8)

**Branch:** `feat/e2-database` · **Author:** Member A · **Reviewer:** Naji · **Date:** 2026-08-19

Schema per `ARCHITECTURE.md` §5: 20 tables across `V1__core.sql` … `V7__notifications.sql`, plus
`DbBootstrap` (Flyway runner) and an 18-test suite. No entities, no repositories, no seed — those are
PR 2 and PR 3.

## Verification

| Check | Result |
|---|---|
| `mvnw clean verify` | BUILD SUCCESS |
| Tests | 331 (baseline on `main`: 311) — 20 new, 0 failures |
| Coverage gate | met; unchanged at 30 analyzed classes (`server/db/**` is JaCoCo-excluded) |
| Clean run on empty MySQL | ✅ 7 migrations, no pending |
| On top of previous version | ✅ migrate to V6, then to head |
| Re-run is a no-op | ✅ |
| Skips cleanly without MySQL | ✅ verified with a bad port: 10 skipped, build still green |

## Independent schema audit before opening the PR

The migrations were audited table-by-table against §5, PRD §1 and the ADRs by a reviewer with no
knowledge of the choices made here. It found two defects, both fixed in this PR before push, and a set of
contract questions listed below.

**Fixed — no optimistic-locking column existed on any table.** §5 line 136 says "entities carry `@Version`
where editable" and PRD F10.3 says "every entity carries a `version` column"; F10.4 names them: questions,
exams, bot sources, releases, grading. None had one. Since a merged migration must never be edited, this
would have forced a `V8` purely to make E2.9 possible. Added `lock_version INT NOT NULL DEFAULT 0` to
`questions`, `exams`, `exam_versions`, `exam_executions`, `bot_sources` and `grades` — named `lock_version`
so it is never confused with the domain `version_no` / `bot_sources.version` columns. `exam_versions` is the
one addition beyond F10.4's list: its `status` is the only mutable field on a version row, so the
approve-vs-reject race between two coordinators lands there. **Confirm that inclusion.**

**Fixed — deleting one execution silently destroyed graded student history.** `exam_attempts` cascaded from
`exam_executions` and `grades` cascaded from `exam_attempts`, so a single `DELETE FROM exam_executions`
would have taken every attempt, answer and approved grade with it — contradicting this PR's own stated
convention (RESTRICT for history that must not vanish). Both foreign keys are now `ON DELETE RESTRICT`, with
a test proving a sat execution cannot be deleted and the grade survives. Cancelling a *scheduled* release
still works, since nothing has been attempted yet.

## Open questions — assumptions I ran on

None of these blocks the PR; each binds at exactly one place, so an answer costs one edit.

**1. `server/db/**` is JaCoCo-excluded (`pom.xml:250`, tagged "legacy prototype").**
All E2 code lands there, so none of it counts toward the 90% gate. I wrote the tests as if it did.
*Assumption:* code stays in `server/db/**` — the only main-code path my scope guard permits.
*Reversal cost:* `git mv` + a `sed` over package/import lines. Your call whether the exclusion goes or E2
moves to a new package.

**2. Legacy `QuestionDAO` / `DatabaseConfig`.**
*Assumption in effect:* co-exist, don't delete. `DbBootstrap` has its own JDBC constants but resolves
credentials through the same `ServerConfig.load()`, so a machine's MySQL login is still configured in one
place and E6 can delete the legacy pair without touching my config path.

**3. No Failsafe plugin, so `*IT.java` never runs.**
*Assumption:* the MySQL suite is a normal `*Test` class gated by `MySqlAvailability#isReachable`, which
probes the server with a 3s timeout. No pom change and no CI change needed — `ci.yml:16-28` already runs
MySQL 8.4 with `root/root`, which is exactly what the bundled `server.properties` falls back to.
`MySqlAvailability` is deliberately the *only* place the gating decision lives; if you'd rather add Failsafe
and rename to `*IT`, it's that one file plus a rename.

**4. New — `main` does not build on JDK 24 or 25.**
All 162 Mockito tests error with `Could not initialize plugin: org.mockito.plugins.MockMaker` (ByteBuddy
doesn't support those JDKs; confirmed — `-Dnet.bytebuddy.experimental=true` makes them pass). CI is green
because it pins Temurin 21, so this only bites locally, and nothing in the build declares the requirement:
no `maven-enforcer-plugin` rule, no toolchain. Anyone installing a current JDK gets 162 red tests and no
hint why. Worth an enforcer rule pinning 21 — pom change, so it's your call.

## Deferred to you: one line

E2.1 says "Flyway bootstrap **on server start**". The bootstrap is built and tested, but the call site is in
`server/core`, outside my allowed paths — the scope guard denies the write. The patch, in
`ServerMain.main` immediately before `server.listen()` (`ServerMain.java:26`):

```java
HSTSServer server = new HSTSServer(port);
try {
    DbBootstrap.migrate();      // <-- add this line (+ import server.db.DbBootstrap;)
    server.listen();
```

Apply it, or tell me I'm cleared to. `docs/TODO.md` E2.1 is ticked with this exception noted inline.

## Schema judgement calls — please confirm

§5 is a frozen contract, so everywhere it was literal I followed it literally. These are the places it left
room, plus one deliberate widening. None changes a table or column name.

| # | §5 says | What I did | Why |
|---|---|---|---|
| a | `image BLOB NULL` | `MEDIUMBLOB` | MySQL `BLOB` caps at 64 KB — too small for a real illustration (PRD §5 wants ~10 seeded). The only widening in the PR. |
| b | `stats: avg, median, … participation {…} JSON NULL`; TODO E2.5 says "stats columns" | two JSON columns, `stats` and `participation` | Matches "frozen into the stats JSON at close". If you meant discrete numeric columns, say so — it's a V8. |
| c | `coordinators(subject_code, teacher)` | PK on `subject_code` alone | S-1 is "coordinator per subject", so the PK enforces one. A teacher can still coordinate several subjects. |
| d | `users(… national_id)` | **not** unique | Followed §5 literally. But S-18 identifies a student by national id to start an attempt — two students sharing one would be ambiguous. **Propose `UNIQUE(national_id)`.** |
| e | `exam_executions(… code CHAR4 …)` | **not** unique | Followed §5 literally. Two concurrent live executions sharing a code makes S-16 entry ambiguous. **Propose unique among non-`CLOSED` executions** (needs a service rule; MySQL has no partial index). |
| f | `grades(… final_score …)` | nullable | Left open rather than forcing `final_score = auto_score` at insert — that's E12's semantics (Member B), not mine to fix in DDL. |
| g | C-8: answers pairwise distinct | added `CHECK (a1<>a2 AND …)` | Storage-level backstop only; case-insensitive for free under `utf8mb4_unicode_ci`. The real rule (trim + collapse whitespace) stays in the service per ADR-016. |
| h | — | added indexes | `exam_attempts(execution_id, status)` for the derived participation counts, plus code lookup, topic/difficulty filtering, unread notifications, bot analytics. No column changes. |

Also as specified: `sum(points)=100` is **not** a DDL constraint (§5 says service + test), and
`exam_executions` has **no counter columns**.

## Contract-vs-PRD conflicts the audit surfaced — these need your decision, not my code

In each of these the migration is **faithful to §5**; it is the PRD that asks for something §5 does not
provide. They are `ARCHITECTURE.md` edits, so they are yours.

1. **No `CANCELLED` execution status.** §5 fixes the ENUM at `SCHEDULED, LIVE, CLOSED`, but F5.5 requires
   "cancel a scheduled release". With no such value, cancel becomes either `CLOSED` (which pollutes the
   report corpus in F9.4 with a zero-participant execution) or a row delete — and a delete is exactly what
   the RESTRICT fix above now blocks once anyone has sat it. Adding the value later is an `ALTER TABLE`.
2. **No soft-delete column on `questions`.** §5 gives it four columns; F2.5 says delete is "blocked if any
   exam version references it… otherwise **soft-delete**". The blocked half works structurally (RESTRICT
   from `exam_version_questions` and `attempt_answers`), but there is nowhere to record "deleted", so the
   fallback becomes a hard delete — contradicting F2.5 and losing version history. Either §5 gains
   `deleted_at NULL`, or F2.5 is re-worded.
3. **Duplicate questions in one exam.** PRD §6 lists "duplicate question in exam → prevented", but the link
   table keys on `question_version_id`, so question 17 version 1 *and* version 2 can both be added — the
   student sees it twice, worth double points. The database cannot express this without denormalising
   `question_id` into the link table; it needs a named service rule plus a test in E7.
4. **Nullability drift.** §5 marks `NULL` deliberately and sparingly. `student_text`, `teacher_text`,
   `raw` and `extracted_text` carry no marker but are nullable here. `extracted_text` is the one with teeth:
   a NULL row is a bot source that appears in the UI but contributes nothing to the prompt, silently.

Lower-priority notes from the same audit, recorded so they are not rediscovered later: the pairwise-distinct
CHECK inherits `utf8mb4_unicode_ci`, which also treats accented and unaccented letters as equal (harmless for
Hebrew, but it means `résumé`/`resume` count as duplicates); `bots` still cascades to `bot_sessions` and
`bot_messages`, so deleting a bot wipes the analytics corpus that F12.4's active-toggle exists to preserve;
`display_id5`/`display_id6` are denormalised with nothing tying them to their source columns; and
`DATETIME(3)` carries no timezone, so a demo machine on a different offset shifts `close_at` silently.

## Findings

**The legacy `Questions` table collides with V2 on Windows.** `lower_case_table_names=1` on Windows MySQL,
so `Questions` (from the unreferenced `src/main/resources/schema.sql`) and V2's `questions` are the same
name. My `hsts_db` already has it, so `DbBootstrap.migrate()` against a used `hsts_db` will fail —
correctly, since `baselineOnMigrate` is off and silently skipping V1 would be worse. Tests are unaffected
(they use a throwaway schema). Before the wiring line lands, `hsts_db` needs to be dropped and recreated
empty. Also: `schema.sql` and `seed.sql` are now superseded and referenced by nothing — deleting them is
outside my paths.

**H2 cannot run these migrations.** They're MySQL-dialect (`ENGINE=InnoDB`, `MEDIUMBLOB`, `JSON`, native
`ENUM`). E2.13 wants an H2 fast suite, so PR 2's H2 tests will have to build their schema with Hibernate
schema-generation rather than Flyway — meaning H2 tests validate *mappings*, and only the MySQL suite
validates the real schema. Worth agreeing before PR 2.

## Definition of Done

- [x] Matches ARCHITECTURE §5 / the PRD ids named in the task — deviations listed above, none silent
- [x] Unit + repo tests; coverage not lower than `main`
- [x] Migrations run clean on empty MySQL AND on top of the previous version
- [x] No secrets, no dummy-credential changes in resources — `src/main/resources/server.properties`
      untouched; local credentials live in the gitignored root `server.properties`
- [x] TODO.md boxes ticked (E2.1–E2.8; E2.1 annotated with the deferred line)
- [ ] CI green — after push

## Next

PR 2 (E2.9–E2.14): entities, `HibernateUtil`, `Transactions.inTx`, repositories, ID allocators, and the
defense-critical E2.12 projection. Blocked on nothing, but answers to questions 1 and the H2 finding would
shape it.
