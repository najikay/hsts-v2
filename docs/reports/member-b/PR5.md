# E12 PR 4 — GradingService (E12.1 persist half) + a decision I need from you

**Branch:** `feat/b-e12-autograde` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

`GradingService.autoGrade` — loads the pinned questions and the student's answers, calls
`AutoGrader`, persists the `AUTO` grade. 7 tests against mocked repositories.

**The reads go through a port, `GradingReads`, and its repository adapter is not in this PR.**
That is not laziness — writing it requires widening `CorrectnessLeakGuardTest`, and that guard
exists precisely so widening it is a decision rather than a side effect. See below.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 1563, 0 failures, **0 skipped** (MySQL suite ran) |
| `GradingServiceTest` | 7 tests |
| Coverage gate | ✅ met, 201 classes |

## Behaviour proven

| Rule | Source |
|---|---|
| `SUBMITTED` and `TIMED_OUT` are gradeable; `IN_PROGRESS` is refused | H12.7 |
| A timed-out attempt with nothing saved grades to **0** and is still graded | H12.4 |
| Re-grading returns the existing grade and **never overwrites** | §6 idempotence |
| An overridden grade survives a re-grade with its justification intact | F8.3, S-23 |
| The pinned exam version is read from the execution, never "latest" | PRD §6, H12.6 |

The idempotence test asserts that a second grade attempt does not even *read* the exam. That is
deliberate: re-grading must not depend on the exam still being loadable, and more importantly an
overwrite would silently discard a teacher's override reason — an audit-trail loss dressed up as
a recomputation.

## The decision — a third correctness audience exists and has no name

`CorrectnessLeakGuardTest` requires every repository read returning an answer key to end in a
sanctioned suffix. There are two: `ForAuthoring` and `ForCheckedForm`. A test named
`onlyTwoSuffixesAreSanctioned` pins that count, which I read as a deliberate tripwire — so I
stopped rather than adding a third and letting it ride in a feature PR.

**The frozen contract needs two teacher-facing reads that carry an answer key, and neither suffix
fits honestly:**

1. **Auto-grading** (E12.1) must compare each selection against the correct answer.
2. **`GRADE_REVIEW_GET`** (E12.6) returns `AnswerReviewRow`, which carries `correct` — the
   teacher's marked view of a student's paper.

Neither is authoring: nobody is composing a question. Neither is the checked form: that suffix is
documented as the *student's own* paper under three conditions. Using either name would be the
"lie or a suppression" choice the guard's own Javadoc says it exists to prevent.

**Three options, and what I would pick:**

| | Option | Cost |
|---|---|---|
| **A** *(recommended)* | Add `ForGrading` as a third sanctioned suffix, documented on the same terms — audience, what licenses it, which tests enforce it | Edit the guard's list, its Javadoc, and `onlyTwoSuffixesAreSanctioned`. Licensed by the contract's teacher role + ownership rules, which E12's handler tests will prove. |
| **B** | Reuse `ForAuthoring` | Free, and dishonest. Its Javadoc defines it as composing questions. Muddying the audience is how the next person concludes the suffixes mean nothing. |
| **C** | Compute correctness inside the query so no answer key crosses the repository boundary | Keeps the guard at two suffixes, but moves C-8's comparison into HQL and makes grading untestable without a database — undoing the property that made `AutoGrader` and `ScoreStatistics` worth writing. |

I recommend **A**. Grading is a real third audience with its own enforcement, and the guard is
designed to be widened deliberately rather than worked around. But it is your call, and the
adapter is one class either way — nothing in this PR changes.

*Assumption I am NOT running on:* I have not touched the guard. If you would rather I make the
change and you review it, say so and it is ten minutes.

## Definition of Done

- [x] Behavior matches E12.1's task line — the read adapter is the stated exception, not a silent gap
- [x] Unit tests against mocked repositories (TEAM_SPLIT §3.2); coverage not lower
- [x] Edge cases handled and tested: H12.4, H12.6, H12.7, idempotence
- [ ] Integration where DB touched — blocked on the decision above
- [ ] Design-system components / screen review — n/a, no UI
- [x] `TODO.md` annotated (E12.1 still not ticked)
- [ ] CI green — after push

## Next

Unblocked by the suffix decision: the `GradingReads` adapter, which finishes E12.1 end-to-end,
and then E12.2 (approve — idempotent, freezing `ScoreStatistics` into `exam_executions.stats` in
the same transaction) and E12.3 (override + audit). E12.6's review screen hits the same decision,
so answering it once unblocks both.
