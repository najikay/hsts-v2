# E12 PR 3 — auto-grading rules (E12.1, scoring half)

**Branch:** `feat/b-e12-autograde` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

`server.features.grading.AutoGrader` — F8.1's scoring rules as a pure function: pinned questions
plus the student's selections in, per-question detail and a total out. 16 tests.

Same shape as `ScoreStatistics`, and for the same reason: the one piece of arithmetic a student's
grade depends on stays free of persistence, so it can be tested exhaustively against fixtures.
The service that reads the attempt and writes the `AUTO` grade wraps it — that half is next, and
is where the repository question below lands.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| `AutoGraderTest` | 16 tests, 0 failures |
| Coverage gate | ✅ met, 200 classes analyzed |
| Fixture | Seeded exam **101101 v2** (§8.1): 7 questions, 15×6 + 10 = 100 |

The fixture is the seeded Algebra Midterm, including question **11005 pinned at version 1** — the
seed's deliberate proof that a released exam does not follow later edits.

## Rules implemented

| Rule | Source |
|---|---|
| Correct ⇔ selection equals the single correct answer | F8.1, C-8 |
| Full points or zero — **no partial credit** in the automatic pass | F8.1; partial credit is the justified override (F8.3) |
| Unanswered scores 0 **and still appears** in the detail | F6.9, H12.5 |
| An attempt with no answers at all scores 0 and is still graded | H12.4 |
| Answers must match the **pinned** question versions | PRD §6, H12.6 |

## Design decisions

| # | Decision | Why |
|---|---|---|
| a | A stray answer — one against a question version the exam did not pin — **throws** rather than being ignored | This is H12.6's failure mode. Ignoring it still produces a perfectly plausible score, which is the worst outcome for a grade: nobody notices. The exception names the offending id. |
| b | Points not totalling 100 **throws here** | Otherwise a 90-point exam quietly yields max score 90, and the first sign of trouble is `ScoreStatistics` complaining about a score — a message pointing at the wrong place. F3.1 makes 100 an invariant, so this only fires if E7 let something through. |
| c | Unanswered questions get a row with `chosen == null` | The checked form (E13.2) must render "unanswered", not omit the question. A detail list shorter than the exam is how a student ends up unable to see what they missed. |
| d | The caller supplies the pinned questions; this class does not read the exam | Only the caller can read `exam_version_questions`. What this class *can* do is refuse to paper over a mismatch — hence (a). |
| e | No partial credit, at all, automatically | F8.1 read with T-8.3: partial credit is a teacher decision that requires a justification and is audited. Silent partial credit would bypass S-23. |

## Question for you — repository ownership

The scoring half needed no repositories. **The persist half does**, and the methods it needs do
not exist yet: `AttemptRepository` has `findByExecutionAndStudent` and `countParticipation`;
`GradeRepository` has `findByAttempt` and `findAwaitingApproval`. Nothing loads an attempt's
answers with the pinned question rows, and nothing serves `GRADING_QUEUE_GET`.

E2.11 says repositories are "query-per-need", which implies they grow as features need them — but
they are Member A's files, and TEAM_SPLIT §3.4 makes cross-feature edits a review-reject.

*Assumption I will run on unless you say otherwise:* I add the methods I need to the existing
repository classes, in the established style, with tests, and flag each one in the PR so Member A
sees exactly what arrived in his area. The alternative — a round trip per query — costs days we
do not have before the 28th.

## Definition of Done

- [x] Behavior matches F8.1 / C-8 and the E12.1 task line — deviations listed above, none silent
- [x] Unit tests; coverage not lower than `main`
- [x] Edge cases handled and tested: H12.4, H12.5, H12.6
- [ ] Integration where protocol/DB touched — n/a, touches neither by design
- [ ] Design-system components / screen review — n/a, no UI
- [x] `TODO.md` annotated (E12.1 not ticked — persist half remains)
- [ ] CI green — after push

## Next

E12.1's persist half, then E12.2 (approve, idempotent, freezing `ScoreStatistics` into
`exam_executions.stats` in the same transaction) and E12.3 (override + audit). All three are
now unblocked by the frozen contract; the only open question is the repository one above.
