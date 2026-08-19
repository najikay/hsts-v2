# Week-1 PR 1 — acceptance tests, seed content, E12–E15 hardening plan

**Branch:** `feat/b-week1` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-19

All three brief deliverables.

**`docs/ACCEPTANCE_TESTS.md`** (D1, feeds E22.1) — the 21 scenarios of the course test outline,
expanded to **115 numbered cases**, columns `# · Steps · Expected result · Actual · Status ·
Bugs found`. Steps and Expected are filled from the PRD now; Actual, Status and Bugs stay empty
until each feature exists. Every case names **real seed rows and real accounts**, so a tester
loads the seed, signs in as the named user and follows the steps without inventing anything.

**`docs/seed/SEED_CONTENT.md`** (D2, feeds E2.15) — the demo dataset per PRD §5, written as
content for Member A's loader, not as a loader. 12 sections: 2 subjects, 4 courses, 18 users,
memberships and enrollments, 40 questions, 6 exams in mixed states, 4 executions, grades + frozen
statistics, 4 bots with 8 sources and 8 recorded sessions, 8 notifications. Every table maps 1:1
to a schema table, so transcription is the only judgement left to the loader.

**Hardening section** (D3, appended to ACCEPTANCE_TESTS.md) — PRD §6's edge cases for my epics
expanded into **28 given/when/then items** across E12–E15, ids `H<epic>.<n>`, kept out of the 115
so the submission table stays exactly the outline. This is my E12–E15 test plan: each item becomes
a test when its epic lands, rather than being backfilled in E21.

The documents check each other: because the test cases cite seed ids, a later change to the roster
breaks cases loudly instead of quietly making them vague.

Rebased onto `main` after E5, E4 and E2 PR1 merged; the roster below mirrors
`docs/DEMO_ACCOUNTS.md`, which landed while this was being written.

## Verification

No code changed, so the build is untouched. What needed checking was whether the *content*
satisfies the constraints it claims to — checked by script against the file, not by eye.

| Check | Result |
|---|---|
| 18 national ids — distinct, checksum-valid Israeli ת"ז | ✅ |
| The 5 usernames in `DEMO_ACCOUNTS.md` are mirrored exactly | ✅ |
| `maya.levi` / `noam.peretz` enrollments match `DEMO_ACCOUNTS.md` course-for-course | ✅ |
| 40 questions, `display_id5` unique, answers pairwise distinct, correct ∈ 1..4 | ✅ |
| Points sum to exactly 100 — all 7 exam versions | ✅ |
| Exam composition references only questions that exist | ✅ |
| Every exam author teaches the course they wrote for (S-5) | ✅ |
| 12 students, each in 2–3 courses | ✅ |
| Every graded attempt is by a student enrolled in that course | ✅ |
| Graded roster == full Algebra roster (8 of 8) | ✅ |
| Every bot session is by a student enrolled in that bot's course (S-31) | ✅ |
| Recursion = 2 questions, no HARD (F3.3 fixture) | ✅ |
| Illustrations = 10 (PRD §5) | ✅ |
| Frozen stats match the seeded grades (mean 78.0, median 80.5, σ 13.08) | ✅ |
| ACCEPTANCE_TESTS: all 21 outline scenarios present as sections | ✅ |
| ACCEPTANCE_TESTS: per-scenario case counts match the summary table | ✅ 115 total |
| ACCEPTANCE_TESTS: every row has the full column set and a status marker | ✅ |
| Hardening: 28 items, ids unique, none colliding with scenario cases | ✅ |
| Hardening: source tagged §6 vs gap on every row | ✅ 5 §6, 23 gap |
| `./mvnw clean verify` | ✅ BUILD SUCCESS — 85 classes, coverage gate met (Temurin 21 now installed) |

Four errors were caught by that pass rather than by review, and are listed because they are
the class of error that survives proof-reading and resurfaces at the defense:

1. The frozen `stats` block said mean **76.75**; the eight grades give **78.0**.
2. 15 questions were flagged as illustrated against a header claiming 10.
3. After mirroring `DEMO_ACCOUNTS.md`, `noam.peretz` was still sitting the Algebra exam — he
   is enrolled in Calculus and Java only. Replaced by `lior.gabay`, same score, so the
   distribution and every statistic are unchanged.
4. Bot session 8 had `lior.gabay` using the **Java** bot; he is enrolled in Algebra and
   Calculus. S-31 makes that impossible. Replaced by `noam.peretz`.

Errors 3 and 4 only exist because the roster changed mid-PR, and neither is visible by reading —
they are relational. The cross-checks that caught them are now part of the validation pass.

## Deviations from the contract, with reasons

**1. No illustration bytes supplied.** 10 questions are marked as illustrated; I supplied no
image data. `image MEDIUMBLOB NULL` accepts NULL, so the loader is unblocked today. Real assets
follow under `docs/seed/img/`. Flagged rather than silently deferred, because "10 illustrations"
reads as done in PRD §5 and it is not.

**2. The teacher/course shape diverges from PRD §5, forced by `DEMO_ACCOUNTS.md`.**
PRD §5 says "one per course + one co-teacher on Java". `DEMO_ACCOUNTS.md` puts `dana.cohen` on
**both** Algebra and Calculus and `rina.barak` on Calculus, so Calculus is co-taught too and one
teacher covers two courses. I mirrored `DEMO_ACCOUNTS.md`, since it states that the seed mirrors
it. The result is defensible on S-1 ("one or more teachers") and is arguably richer — it demos a
teacher with two courses *and* two co-taught courses — but it is a divergence from PRD §5 as
written. Either PRD §5 gets reworded or `DEMO_ACCOUNTS.md` does; they cannot both be literal.
>
> **Resolved in review:** `DEMO_ACCOUNTS.md` is authoritative and PRD §5 is reworded to match
> (`dana.cohen` on both courses). The seed needs no change.

## Judgement calls — please confirm

| # | Call | Why |
|---|---|---|
| a | National ids are **checksum-valid** Israeli ת"ז, not sequential filler | S-18 has a student type this to start an attempt. If id validation is ever added, invalid demo data breaks the demo rather than the code. Costs nothing now. |
| b | Stored `stats.stddev` is the **population** standard deviation (divisor `n`) | The class is the whole population, not a sample of it. 13.08 for the seeded execution; the sample form gives 13.98. Recorded in the doc so E14's recomputation cannot drift ~1 point from the seed and look like a bug. **This binds my own epic — confirm before I build E14 on it.** |
| c | Seed password stays `demo123`, matching the E5 fixture | `DEMO_ACCOUNTS.md` uses it. Keeping it means the demo script does not change when the fixture is replaced by the seed in E2 PR3. |
| d | `full_name` is Hebrew for all 18; `DEMO_ACCOUNTS.md`'s Latin names are transliterations | RTL must round-trip in every screen that renders a name. Same people, same usernames. |
| e | Bot 4 (Databases) is seeded **inactive** | S-31 gates bot use on enrolled **and** active. With every bot active there is no way to demo the second half of that rule. |
| f | Exam 1 has v1 REJECTED → v2 APPROVED, where v2 fixes exactly what the reason named | Makes T-4.2 and C-2 one demoable story instead of two abstract states. The rejected v1 stays queryable. |
| g | Executions 3 (SCHEDULED) and 4 (LIVE) have **no** seeded attempts | Seeding them would make the take-exam demo a one-shot. |
| h | Execution 4 re-runs the *same exam version* as execution 1 | The S-2 proof — one exam, two releases, separate codes, windows, participants and statistics. |
| i | Language split by course: Algebra/Calculus Hebrew, Java/Databases English | RTL must be proven, but reversed code and SQL are unreadable. Both scripts appear on every demoed screen. |

## Open questions

**1. Population or sample standard deviation** — ✅ **answered: population (divisor `n`).**
The seed's σ 13.08 stands. Rationale given: an execution's participants *are* the population,
nothing is being estimated. A sample-σ recomputation reading 13.98 is officially a bug, and
**H14.4** is its test. I own producing these numbers in **E12.4**.

**2. Illustration assets** — deviation 2. Does Member A want NULL now and a follow-up PR, or
should the loader wait for real bytes?

**3. Is coordinator self-approval actually logged?** ✅ **answered: E8's `ApprovalService` logs
it**, and acceptance case **4.6** is its test. Also confirmed: the dual-hat coordinator gets no
special treatment or demo time — the coordinator rail is simply teacher + Approvals (case 1.3
updated to say so). Original write-up kept below for the record.
PRD **F4.3** already settles the policy: *"A coordinator does not approve her own exams? — Not
required by spec; allowed, but logged."* So the seed is fine as it stands and needs no change.
The open part is only whether the **logging** half has an owner — F4.3 names no epic for it, and
"allowed but logged" with no log line is a silent failure rather than a visible one. The seed
happens to contain the exact fixture: `michal.sharon` is the only Databases teacher *and*
coordinator of subject 20, so exam **202201** is authored and approved by her. Acceptance case
**4.6** tests it. The other five exams avoid self-approval by construction, so 4.6 is the only
place this path is exercised.

### Resolved since the first draft — recorded, no action needed

- **`DEMO_ACCOUNTS.md` did not exist** when I started; it landed in `ca2caa6`. My first draft
  invented a roster and flagged the reconciliation as the top open question. That question is
  now answered by the file itself, and the seed mirrors all five accounts. No decision needed.
- **Is `rina.barak` stored as a COORDINATOR role?** No — and `main` now says so explicitly:
  ARCHITECTURE §5 round-2 derives the wire `Role.COORDINATOR` at login from stored TEACHER plus
  a `coordinators` row. She is seeded TEACHER with a `coordinators` row for subject 10. Member A
  had put this to you; it needs none of your time.

## Findings that affect others

**0. I got F4.3 wrong in the first draft of this report.** I wrote that nothing in the PRD
covers coordinator self-approval and raised it as a blocking decision. F4.3 covers it explicitly
and allows it. Corrected above, and narrowed to the part that is genuinely open (the logging
owner). Recording it because the wrong version was on this branch for one commit.

**1. PRD §6 under-covers E12–E15, and I would like three items promoted into it.**
The catalog gives my four epics five lines — three Grading, two Reports, and no Results line at
all. Bot gets nine items, Discovery five. That split is not proportional to risk: a wrong grade
that looks plausible is harder to catch at a defense than a bot that fails visibly. I added 23
gap items to cover it. Three of them constrain **other people's** code and so belong in §6 rather
than only in my plan:
- **H12.6** — grading must use the question version **pinned in the exam**, never the latest.
  Constrains E6/E7, not just E12.
- **H14.4** — the σ divisor. Binds the seed, E14 and E15 to one choice.
- **H15.2** — CANCELLED executions excluded from the report corpus. Constrains E9 as much as E15.

**Accepted in review** — all three go into PRD §6, and the edit is Naji's rather than mine.
**Not yet on `main`:** as of `14bc23f`, §6's Grading and Reports lines are unchanged and F8.5
still names no divisor. Raising it because E12.4 is three days out and needs the σ decision to be
findable in a file, not only in a review thread.

**2. Three parts of the seed look like defects and must not be "fixed".** Each is annotated at
the point where someone would be tempted to correct it: **Recursion has 2 questions and no HARD
one** (the F3.3 infeasibility fixture — every other topic has enough to succeed), **bot 4 is
inactive** (call e), and **exam 1 v1 is rejected** (call f).

**3. The Algebra grade spread is load-bearing.** Eight grades across five populated deciles is
what makes the F9.3 histogram read as a real class. A uniform spread looks fabricated; a single
spike looks broken. If anyone trims Algebra's enrollment below 8, the histogram demo degrades
with it — and the frozen `stats` block stops matching the grades.

**4. ~~I cannot run `./mvnw clean verify`~~ — fixed.** Temurin 21 installed, `JAVA_HOME` set,
`./mvnw clean verify` → BUILD SUCCESS locally. Two traps worth recording for whoever hits them:
IntelliJ's embedded terminal keeps the environment from when the IDE launched, so a new *tab* is
not a new environment (the IDE must restart); and a user-level `MAVEN_OPTS` carrying JDK 24+ flags
(`--sun-misc-unsafe-memory-access=allow` and friends) makes JDK 21 refuse to start the JVM at all.
Neither is obvious from the error message. Candidate for `PROBLEMS.md` if others hit it.

**5. `exam_version_questions` for the Algebra Midterm must reference question 11005 version 1,**
not the latest. That row is also what exercises the `question_id` + `UNIQUE(exam_version_id,
question_id)` guard and the round-2 composite FK: 11005 v1 and v2 must never both land in one
exam version.

## Scope change accepted (PR #2 review)

E14 (StatChart component) and E15 (report engine) move off my plate; I keep grading, student
results, screen wiring and executing the acceptance document. Submission is **2026-08-28**, with
E12/E13 starting when take-exam lands (~Aug 22–23).

Consequence for this PR: **13 of the 28 hardening items (H14.\*, H15.\*) are no longer mine.**
They are kept in the document with an ownership banner rather than deleted, because two of them
carry team-wide decisions — H14.4 (σ divisor) and H15.2 (CANCELLED excluded from reports). They
need a real owner. I still produce the numbers H14.4 verifies: E12.4 computes and stores the
statistics, E14 only renders them.

## Definition of Done

- [x] Behavior matches the PRD ids named in the task — brief D1 (E22.1), D2 (PRD §5 / NFR-17), D3 (PRD §6); deviations listed above, none silent
- [x] Content validated by script, including relational cross-checks and table-structure checks
- [x] All 21 outline scenarios covered; every case traceable to an F-id or S-id
- [ ] Unit tests — n/a, no code in this PR; the `H*` items are the test plan for E12–E15
- [x] Edge cases: PRD §6 lines for my epics expanded, and the gaps that pass exposed are listed
- [ ] Design-system components / screen review — n/a, no UI in this PR
- [x] Seed data updated — this PR *is* the seed data
- [x] Open questions recorded with the assumption each one runs on
- [ ] CI green — after push

## Next

- Fill Actual / Status / Bugs found as each epic lands — the table is written to be filled in
  during development, not reconstructed at submission.
- Turn each `H*` item into a real test as its epic lands (E12 first, ~M4).
- Temurin 21 installed so `./mvnw clean verify` is available before E12.
