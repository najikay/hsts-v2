# Week-1 PR 1 — seed content (E2.15 input)

**Branch:** `feat/b-week1` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-19

`docs/seed/SEED_CONTENT.md` — the demo dataset per PRD §5, written as content for Member A's
loader (E2.15), not as a loader. 12 sections: 2 subjects, 4 courses, 18 users, memberships and
enrollments, 40 questions, 6 exams in mixed states, 4 executions, grades + frozen statistics,
4 bots with 8 sources and 8 recorded sessions, 8 notifications.

Every table maps 1:1 to a schema table, so transcription is the only judgement left to the loader.

## Verification

No code changed, so the build is untouched. What needed checking was whether the *content*
actually satisfies the constraints it claims to — checked by script against the file, not by eye.

| Check | Result |
|---|---|
| 18 national ids — distinct | ✅ |
| 18 national ids — checksum-valid Israeli ת"ז | ✅ |
| 40 questions, `display_id5` unique | ✅ |
| 4 answers pairwise distinct on every question (C-8 / ADR-016) | ✅ |
| Correct answer ∈ 1..4 on every question | ✅ |
| Points sum to exactly 100 — all 7 exam versions | ✅ |
| Exam composition references only questions that exist in the bank | ✅ |
| 12 students, each enrolled in 2–3 courses | ✅ |
| Recursion topic = 2 questions, no HARD (F3.3 fixture) | ✅ |
| Illustrations = 10 (PRD §5) | ✅ after correction |
| Per-course question spread (11→11, 12→9, 21→11, 22→9) | ✅ |
| `./mvnw clean verify` | **not run** — see finding 3 |

The scripted pass caught two errors of mine before they reached review: the frozen `stats`
block said mean **76.75** when the eight seeded grades give **78.0**, and 15 questions were
marked as having illustrations while the section header claimed 10. Both corrected. Recording
them because they are exactly the class of error that survives proof-reading and then surfaces
as "the histogram doesn't match the stored stats" in M5.

## Deviations from the brief, with reasons

**1. This is Deliverable 2; the brief says PR after Deliverable 1.**
`docs/ACCEPTANCE_TESTS.md` (D1) is not written yet. I shipped the seed first because Member A
is blocked on it for the E2.15 loader and E2 is the epic in flight, whereas D1 blocks nobody
until M8. If you would rather hold this until D1 is ready, say so and I will hold it — but the
loader would then wait on a document that has no downstream dependency.

**2. No illustration bytes supplied.**
10 questions are marked as having an illustration; I have supplied no image data.
`image MEDIUMBLOB NULL` accepts NULL, so the loader is unblocked and can insert NULL today.
Real assets follow under `docs/seed/img/` in a later PR. Flagged rather than silently deferred
because "10 illustrations" reads as done in PRD §5 and it is not.

## Judgement calls — please confirm

| # | Call | Why |
|---|---|---|
| a | National ids are **checksum-valid** Israeli ת"ז, not sequential filler | S-18 has a student type this to start an attempt. If id validation is ever added, invalid demo data breaks the demo rather than the code. Costs nothing now. |
| b | Stored `stats.stddev` is the **population** standard deviation (divisor `n`) | The class is the whole population, not a sample of it. 13.08 for the seeded execution; the sample form would give 13.98. Recorded in the doc so E14's recomputation cannot drift ~1 point from the seed and look like a bug. **This binds my own epic — confirm before I build E14 on it.** |
| c | Password is one uniform demo value (`Hsts!2026`), BCrypted by the loader | 18 distinct demo passwords are 18 things to mistype at the defense. |
| d | Bot 4 (Databases) is seeded **inactive** | S-31 gates bot use on enrolled **and** active. With every bot active there is no way to demo the second half of that rule. |
| e | Exam 1 has v1 REJECTED → v2 APPROVED, where v2 fixes exactly what the reason named | Makes T-4.2 and C-2 demoable as one story instead of two abstract states. The rejected v1 stays queryable. |
| f | Executions 3 (SCHEDULED) and 4 (LIVE) have **no** seeded attempts | Seeding attempts would make the take-exam demo a one-shot. They exist so attempts can be created live. |
| g | Execution 4 re-runs the *same exam version* as execution 1 | The S-2 proof — one exam, two releases, separate codes, windows, participants and statistics. |
| h | Language is split by course: Algebra/Calculus Hebrew, Java/Databases English | RTL must be proven, but reversed code and SQL are unreadable. Both scripts therefore appear on every demoed screen. |

## Open questions — the assumptions I ran on

**1. `docs/DEMO_ACCOUNTS.md` does not exist.** Not on `main`, not on any branch, not in any
commit (`git log --all`). It is E0.11, yours, unticked. Member A's note to me says the seed
"mirrors its five usernames" — so those usernames live in a local or unpushed file.
*Assumption:* §3 of SEED_CONTENT.md is the source of truth for usernames until that file lands;
whichever document is written second matches the first. *Reversal cost:* a rename pass over one
table.

**2. Which five accounts are the demo five?** *Assumption:* `dana.almog`, `rina.barak`,
`avi.cohen`, `noa.friedman`, `omer.katz` — one per role, plus a coordinator and the student who
timed out. Worth pinning before DEMO_ACCOUNTS.md is written rather than after.

**3. Population vs sample stddev** — call (b) above. This one changes code I am about to write.

**4. Illustration assets** — deviation 2 above. Does Member A want NULL now and a follow-up PR,
or should the loader wait for real bytes?

**Not a question — resolved against the schema.** Member A asked whether `rina.barak` is seeded
as a COORDINATOR role. She is not, and this needs none of your time: `V1__core.sql:32` is
`ENUM('STUDENT','TEACHER','PRINCIPAL')` with no COORDINATOR value, and `coordinators` has its PK
on `subject_code` alone (S-1). She is a TEACHER row plus one `coordinators` row. His reading was
correct and the seed is built on it.

## Findings that affect others

**1. Three parts of the seed look like defects and must not be "fixed".** Each is annotated in
place, at the point where someone would be tempted to correct it:
- **Recursion has 2 questions and no HARD one** — the fixture that lets F3.3 auto-generation be
  demoed *failing live* without touching the database mid-defense. Every other topic has enough
  to succeed.
- **Bot 4 is inactive** — see call (d).
- **Exam 1 v1 is rejected** — see call (e).

**2. The Algebra execution's grade spread is load-bearing, not arbitrary.** Eight grades across
five populated deciles is what makes the F9.3 histogram read as a real class. A uniform spread
looks fabricated; a single spike looks broken. If anyone trims the Algebra enrollment below 8,
the histogram demo degrades with it.

**3. I cannot run `./mvnw clean verify`.** This machine has JDK 12 and 26 only, and the enforcer
correctly requires `[21,22)`. Installing Temurin 21 is my brief §0 step 1 and I have not done it.
For *this* PR it is not load-bearing — the change is docs-only, nothing under `src/`, so CI is
sufficient — but it blocks me from verifying anything before E12 starts, and I am fixing it
before my next PR.

**4. `exam_version_questions` for the Algebra Midterm must reference question 11005 version 1,**
not the latest. That row is also what exercises the new `question_id` + `UNIQUE(exam_version_id,
question_id)` guard from E2 PR1: 11005 v1 and v2 must never both land in one exam version.

## Definition of Done

- [x] Behavior matches the PRD ids named in the task (PRD §5, NFR-17) — deviations listed above, none silent
- [x] Content validated against its stated constraints by script, not by eye
- [ ] Unit tests — n/a, no code in this PR
- [x] Edge cases: the three deliberate fixtures are documented where they would otherwise be "corrected"
- [ ] Design-system components / screen review — n/a, no UI in this PR
- [x] Seed data updated — this PR *is* the seed data
- [x] Open questions recorded with the assumption each one runs on
- [ ] CI green — after push

## Next

- **D1 — `docs/ACCEPTANCE_TESTS.md`**: one row per test-outline scenario 1–21, columns
  `# · Scenario · Steps · Expected · Actual · Status · Bugs found`. Steps and Expected filled
  from the PRD now; Actual and Status stay empty until testing.
- **D3 — edge-case ownership pass**: PRD §6 Grading / Results / Reports lines expanded into
  given/when/then test ideas, appended to ACCEPTANCE_TESTS.md as the E12–E15 test plan.
- Temurin 21 installed so `./mvnw clean verify` is available before E12.
