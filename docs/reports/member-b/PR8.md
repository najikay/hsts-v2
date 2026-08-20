# Seed fix — reachable scores, the missing `attempt_answers`, and a gameable answer key

**Branch:** `feat/b-seed-scores` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

Three defects in my own seed, found by Member A's red-team pass before transcription. All three
were invisible while the seed was demo text and become wrong the moment `AutoGrader` runs on it.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 2191, 0 failures, **0 skipped** |
| Every auto score reachable by exam 1 v2 | ✅ |
| Selections recompute to the stated scores | ✅ all 8, against the seed's own key |
| Frozen stats recompute from the finals | ✅ mean 72.5, median 72.5, σ 17.5 |
| Answer-key spread across 40 questions | ✅ `{1:11, 2:10, 3:10, 4:9}` |

The validation is a script that parses §7's answer key, §8.1's composition and §9.1.1's
selections out of the document and recomputes every score — so the seed is now checked against
itself rather than against my arithmetic.

## 1. Auto scores were unreachable ⚑

Exam 1 v2 is 6×15 + 10, so auto-grading can only ever produce
`0, 10, 15, 25, 30, 40, 45, 55, 60, 70, 75, 85, 90, 100`. Of my eight auto scores —
92, 78, 85, 64, 71, 96, 51, 83 — **only 85 was reachable.** The frozen statistics derived from
all eight, so `ScoreStatisticsTest` was asserting numbers the grader could never produce.

Replaced with eight reachable scores chosen so every figure is hand-checkable:

| | old | new |
|---|---|---|
| mean | 78.0 | **72.5** |
| median | 80.5 | **72.5** |
| σ (population) | 13.08 | **17.5 exactly** — Σ(x−72.5)² = 2450, √(2450/8) = 17.5 |
| min / max | 55 / 96 | **45 / 100** |
| pass rate | 8/8 = 100% | **7/8 = 87.5%** |
| populated deciles | 5 | **6** |

Two things improve as a side effect. The **pass rate stops being a flat 100%** — I flagged in
PR #3 that the stat card demonstrated the number existed without demonstrating it discriminated;
`omer.katz` now times out and genuinely fails. And **σ is exact rather than rounded**, which is
what E12.4's "unit-tested against hand-computed fixtures" actually wants.

`yael.azulay`'s override still does its job, and does it harder: auto **45 → final 55** moves her
across the pass mark, so the T-8.3 demo changes the execution's pass rate from 6/8 to 7/8 rather
than nudging one row.

## 2. `attempt_answers` did not exist ⚑

The seed had scores but no per-question selections, so nothing could re-grade an attempt and
E12.1 had nothing to run on. New **§9.1.1** gives all eight students' selections against exam 1
v2's key, each arithmetic-checked to produce its stated score.

Three deliberate properties:

- **`omer.katz` has four absent rows, not four wrong answers.** He timed out and never reached
  questions 4–7. That is the only attempt in the seed that distinguishes "answered wrongly" from
  "never answered" — the distinction F6.9 promises and H12.4 tests, and it cannot be demonstrated
  from a dataset where everyone answered everything.
- **`yael.azulay` is wrong on 11011**, the question her stored override reason is about. Her auto
  45 plus that question's 10 points is exactly 55.
- **Wrong answers are spread, not nested.** Each student misses a different combination rather
  than a prefix of the same list, so per-question difficulty varies the way a real class does.

## 3. The answer key was gameable

Distribution across all 40 questions was `{1:14, 2:18, 3:8, 4:0}`. Two problems: Databases had
**8 of 9 on answer 2**, and **answer 4 was never correct anywhere in the bank** — a student who
never picked 4 lost nothing, and "always answer 2" scored 90/100 on exam 6.

Rebalanced to `{1:11, 2:10, 3:10, 4:9}`, every course spread. Done by **swapping the option
text**, not relabelling — the correct answer moves position and stays the same answer.

Three questions keep their fourth option in place because it is positional and would read wrongly
elsewhere: `11010` (`כל x ממשי`), `21008` (`Nothing — it is safe`), `22009` (`None of them`).
`21003` is also excluded, because §7.5's version note is specifically about its answer 4.

## Downstream updates in the same commit

**This had to land as one commit.** `ScoreStatisticsTest` is merged and green on `main` today and
asserts the old figures; changing the seed without it turns `main` red for anyone who pulls
mid-change.

- `ScoreStatisticsTest` — fixture, sum-of-squares constant, decile expectations, pass-rate and
  override assertions, and the sample-σ guard (13.98 → 18.71).
- `ACCEPTANCE_TESTS.md` — case 10.3 and hardening item H14.4 both quoted the old numbers.

## What I would change about how this happened

The seed was validated when written — 40 questions, points summing to 100, distinct answers,
enrolment cross-checks. **None of those checks asked whether a score was producible**, because
when I wrote it no grader existed. Member A caught it by red-teaming the seed against the
migrations before transcribing, which is the pass I should have run myself once `AutoGrader`
landed.

The validation script now lives in the loop: it parses the answer key, the composition and the
selections out of the document and recomputes, so this class of drift fails loudly instead of
being spotted by a careful reader.

## Definition of Done

- [x] Behavior matches PRD §5 / F8.1 / F8.5 — deviations listed, none silent
- [x] Seed validated against its own answer key by script, not by eye
- [x] Downstream test and acceptance figures updated in the same commit
- [x] Edge cases: unanswered-vs-wrong, override crossing the pass mark, six populated deciles
- [ ] CI green — after push

## Next

Seed §7's five PDF/DOCX bot sources becoming TEXT — waiting on Member A's details.
Then `StudentGradeRow` v1.1 and the `MY_GRADES_GET` handler, and wiring `GradingService` to
`AttemptFinalizedListener`, which is now on `main`.
