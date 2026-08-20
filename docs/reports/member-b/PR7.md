# E12 PR 4 — `ForGrading` sanctioned, and E12.1 finished end to end

**Branch:** `feat/b-e12-adapter` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

Both halves of your decision:

1. **`ForGrading` added** to `CorrectnessLeakGuardTest` as the third sanctioned suffix,
   documented in the same three-part form as the other two, with the exactly-two test renamed
   and rewritten.
2. **`RepositoryGradingReads`** — the adapter behind `GradingReads`, which completes E12.1: an
   attempt now goes from repository rows to a persisted `AUTO` grade with nothing stubbed.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 1629, 0 failures, **0 skipped** |
| New contract, both engines | 9 H2 + 9 MySQL |
| `ExecutionRepositoryContract` | 23 per engine (was 16) |
| `CorrectnessLeakGuardTest` | 5, green |
| Coverage gate | ✅ met, 204 classes |

## 1. The suffix

`SANCTIONED_SUFFIXES` is now `ForAuthoring, ForCheckedForm, ForGrading`, and the new entry
carries the same three claims the others do:

- **Audience** — grading services and the teacher-facing review, never anything a student calls.
- **Licensed by** — the contract's rule for teacher verbs: `requireRole(TEACHER, COORDINATOR)`
  plus ownership resolved from repositories.
- **Enforced by** — the E12 handler tests that prove those gates, and `AutoGraderTest`, which
  proves the key is *used to score and never returned*. Same terms as the other two: if those
  tests go, the suffix comes back out of the list.

**`onlyTwoSuffixesAreSanctioned` → `eachSanctionedSuffixNamesOneRealAudience`.** The old name
would have become a lie the moment it passed with three entries, which is worse than a failing
test. It now asserts the exact list, so adding a fourth is still a visible, deliberate edit —
that tripwire is preserved, not removed.

**`theNamingCheckHasTeeth` gained a case rather than a count.** It enumerates every read that
hands back an answer key — two authoring, one grading — and additionally asserts each one is
sanctioned. Spelling the inventory out means a new key-bearing read cannot appear without an
edit here.

## 2. The adapter

`RepositoryGradingReads` fetches and pairs; it holds no rules. Scoring lives in `AutoGrader`,
gradeability in `GradingService`, so the class touching the database has no decisions and the
classes with decisions never touch a database.

**The answer key is fetched and consumed, never returned.** `AutoGrader.Result` carries scores
and which questions were right — not what the right answers were.

**A torn read is refused, not scored around.** If a pinned row's question version does not load,
the adapter throws rather than grading the questions that did load. `exam_version_questions` has
a RESTRICT foreign key to `question_versions`, so that state should be impossible; if it happens
anyway, scoring the remainder would mark the missing question wrong for every student and produce
a plausible, wrong, permanent grade.

## Repository additions — TEAM_SPLIT rule 5

Four reads added across three of Member A's files:

| Read | File | Key? | Consumer named in Javadoc |
|---|---|---|---|
| `findPinnedQuestions` | `ExamRepository` | no | E12.1 auto-grading |
| `findVersionsForGrading` | `QuestionRepository` | **yes — `ForGrading`** | E12.1 via `GradingReads` |
| `findAnswers` | `AttemptRepository` | no | E12.1 via `GradingReads` |
| `findById` | `ExecutionRepository` | no | E12.1, resolving the pinned exam version |

Contract-tested on both engines: 7 new cases in `ExecutionRepositoryContract` (23 per engine now)
plus the new `RepositoryGradingReadsContract` (9 per engine). Established style throughout —
same HQL shape, same Javadoc structure, `Optional`/`List` returns matching the neighbours.
Over to Member A for the post-merge pass; all four are additive and nothing existing changed.

**Why the key is split from the points.** `findPinnedQuestions` returns points and version ids;
`findVersionsForGrading` returns the key. One read could have done both, but then the
correctness-bearing read would also be the ordinary structural one, and every future caller
wanting points would be reaching into a `ForGrading` method. Splitting keeps the key-bearing
surface as small as the feature allows.

## The test I would look at first

`gradesAgainstThePinnedVersionNotTheLatest` (⚑ H12.6) builds the trap the seed describes: a
question with **version 1 pinned into a released exam and a version 2 in the bank with a different
correct answer**. It asserts the read returns v1's key. If it ever returns v2's, every past grade
on that exam silently changes — and on the MySQL leaf this runs against the real composite foreign
key from `exam_version_questions` to `question_versions`, which is what makes the guarantee
structural rather than conventional.

## Definition of Done

- [x] Behavior matches E12.1 and both decisions — deviations listed, none silent
- [x] Unit + repository contract tests on **both engines**; coverage not lower
- [x] Edge cases handled and tested: H12.4, H12.6, empty exam version, missing execution, torn read
- [ ] Design-system components / screen review — n/a, no UI
- [x] `TODO.md` annotated
- [ ] CI green — after push

## Next

E12.2 (approve — idempotent, freezing `ScoreStatistics` into `exam_executions.stats` in the same
transaction) and E12.3 (override + audit). Both are now unblocked with nothing stubbed.

Watching for `AttemptFinalizedListener` on `main` — `GradingService.autoGrade` is the method that
registers on it, and the wiring is the few lines you described.
