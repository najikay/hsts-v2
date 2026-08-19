# Brief — Member A: Epic E2 (Database, persistence & seed)

**Owner:** Member A · **Reviewer:** Naji · **Branch:** `feat/e2-database` · **Target:** first PR within a few days (split into 2–3 PRs, see below)

You own the data layer: Flyway migrations, JPA entities, repositories, and the seed dataset. This is the foundation of your whole "authoring pipeline" story (E6 bank → E7 builder → E8 approval → E9 release all sit on it).

## 0. Setup (do once, before anything)
1. Install **JDK 21** (Temurin) and **MySQL 8** locally. `java -version` must say 21.
2. Clone `https://github.com/najikay/hsts-v2.git`, run `./mvnw clean verify` → must end BUILD SUCCESS. If not, stop and report in the group before touching anything.
3. Read, in order: `docs/PLAN.md` (short), `docs/ARCHITECTURE.md` §5 (your spec — the schema), `docs/PRD.md` §5 (seed dataset) + §1 (C-2, C-7, C-8 decisions), `docs/TODO.md` E2, `docs/TEAM_SPLIT.md` §3–4 (contracts + Definition of Done).

## 1. Rules of engagement
- Work only in: `server/db/**`, `src/main/resources/db/migration/**`, and your test packages. Do **not** modify `common/protocol`, `client/**`, or the pom's plugin config (adding your dependencies' test fixtures is fine — ask first in the group).
- `docs/**` clarification (asked and answered): the *planning docs' content* (PRD/ARCHITECTURE/PLAN/DECISIONS) is the lead's; **you are expected to** tick your TODO.md boxes, add `docs/DEMO_ACCOUNTS.md`, and add your PR reports under `docs/reports/member-a/` (see `docs/reports/README.md`).
- Answered decisions from PR1 (2026-08-19): E2 code **stays in `server/db/**`** — the JaCoCo exclusion is now narrowed to the two legacy files only, so your code counts toward the gate. Legacy `QuestionDAO`/`DatabaseConfig` **co-exist until E6** — confirmed, don't touch. MySQL suite gating: your `MySqlAvailability` probe is accepted; in PR2 make it **fail (not skip) when env `HSTS_REQUIRE_MYSQL=true`** — CI now sets it, so CI can never silently skip the suite. JDK guard: enforcer rule pinning JDK 21 is in the pom (lead).
- The **schema in ARCHITECTURE §5 is the contract** — deviations require a message to Naji *before* coding, not a surprise in the PR.
- Every PR: green CI, coverage not lowered, DoD checklist from TEAM_SPLIT §4 pasted and ticked.
- Branch from latest `main`, small PRs, no direct pushes to `main` (it's protected anyway).

## 2. Work order (matches TODO E2.x — tick items there in your PRs)

**PR 1 — migrations + core entities (E2.1–E2.8):**
Flyway bootstrap (runs on server start; `db/migration/V1__core.sql` … `V7__notifications.sql` per ARCHITECTURE §5). Note the recent decisions: `question_versions.correct_answer TINYINT (1..4)` (exactly one correct — C-8), `bot_messages` table (analytics copy), **no counter columns** on `exam_executions` (participation is derived from `exam_attempts`, frozen into stats JSON at close). utf8mb4 everywhere. Test: migrations run clean on an empty DB.

**PR 2 — entities + repos (E2.9–E2.14):**
JPA entities (with `@Version` on editables), converters (JSON transcript/stats ↔ objects), `HibernateUtil` + `Transactions.inTx(fn)`, repositories per feature, ID allocators (5-digit question id = course(2)+serial(3); 6-digit exam id = subject(2)+course(2)+serial(2)) — concurrency-safe, tested. **E2.12 is defense-critical:** the take-exam projection must structurally exclude `correct_answer`, with a test proving the DTO has no correctness field. Test style: follow the existing config tests (JUnit5 + AssertJ); repo tests against H2 (MySQL mode) fast suite + a MySQL suite using the Template Method wipe/reseed base class you write (E2.13).

**PR 3 — seed (E2.15–E2.17):**
Idempotent seed per PRD §5 — including the **deliberately thin topic** for the auto-generation infeasibility demo, questions with 2 versions, BCrypt-hashed passwords (`at.favre.lib` bcrypt is already a dependency), demo credentials into `docs/DEMO_ACCOUNTS.md`. Member B is drafting the actual content (names, question texts) — coordinate; your job is the loader, theirs is the data.

## 3. Definition of Done (paste in each PR)
- [ ] Matches ARCHITECTURE §5 / PRD ids named in the task
- [ ] Unit + repo tests; coverage not lower than main
- [ ] Migrations run clean on an empty MySQL AND on top of the previous version
- [ ] No secrets, no dummy-credential changes in resources
- [ ] TODO.md boxes ticked in the PR

## 4. If you're blocked
Anything ambiguous in the schema, a Hibernate fight, an ID-allocator race question — post in the group same day, don't burn a day guessing. Naji answers contract questions within the day.
