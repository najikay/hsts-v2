# E13 PR 1 — My Grades: authorization (E13.1 ⚑) and screen logic (E13.3)

**Branch:** `feat/b-e13-mygrades` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

Both halves of My Grades except the FXML:

- **`server.features.results.ResultsService`** (E13.1 ⚑) — a student's own approved grades and
  nothing else, plus two scoped repository reads. 10 tests.
- **`client.features.results.MyGradesSession`** (E13.3) — load, empty, error, retry and live
  refresh on `PUSH_GRADE_PUBLISHED`. 14 tests against `FakeClientConnection`.

**Deliberately separate from PR #7**, which is blocked on the correctness-suffix decision.
Nothing here returns an answer key, so nothing here waits on it.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 1564, 0 failures, **0 skipped** |
| `ResultsServiceTest` / `MyGradesSessionTest` | 10 / 14 |
| Coverage gate | ✅ met, 198 classes |

Fixtures are the seeded execution 4821 rows for `maya.levi` (71, untouched) and `yael.azulay`
(auto 51 → 55, overridden), so these tests and acceptance scenario 9 walk the same data.

## E13.1 — how the ⚑ requirement is met

**Ownership is the query, not a check after it.** `findApprovedForStudent` and `findForStudent`
both filter on the student id in SQL, so there is no code path that loads someone else's row and
then remembers to drop it. A forgotten check is a bug; a filter that was never written cannot be
forgotten.

**The student id can only come from the session.** Every method takes it as a parameter and the
request DTOs carry no student id at all, so a handler physically cannot pass one the caller
supplied.

**Someone else's grade id and a non-existent grade id are indistinguishable.** Both answer empty
→ `NOT_FOUND`. Returning `FORBIDDEN` for the first would confirm that a grade exists, which is a
membership oracle. `missingAndForbiddenLookIdentical` asserts the two results are equal, not
merely that both fail.

**The justification never reaches the student wire** — twice over. The mapper passes null, and
`MyGrades` strips it structurally anyway. Two independent defences, both tested.

## E13.3 — design decisions

| # | Decision | Why |
|---|---|---|
| a | **No client-side filter for `APPROVED`** | The server returns approved rows only (C-3). A client filter implies it might not, and the next person trusts the filter instead of the server. An unapproved row arriving would be a server bug — hiding it client-side is how that reaches the defence. |
| b | The push **re-queries** rather than appending the pushed row | The list is the server's answer to "what does this student have"; rebuilding is the only way the two cannot drift. |
| c | A second `load()` while one is in flight is **ignored, not queued** | Two identical requests can settle out of order and leave the older answer on screen. Tested. |
| d | One error sentence for every failure mode | Server error, transport failure and wrong payload type are the same thing to a student. The distinction belongs in the log. |

## Finding — `MY_GRADES_GET` cannot say which exam a grade belongs to

`StudentGradeRow` carries `gradeId, studentId, studentName, autoScore, finalScore,
effectiveScore, state, overrideReason, teacherComment, approvedAt`. **No exam name, no course
code, no execution reference.** `MyGrades` is just a list of those rows.

So the screen E13.3 describes — *"exam list with scores, status, date"* — can render scores and
dates but cannot label which exam each belongs to. T-9.1 is "a student can view the grades of
the exams she took"; a column of numbers does not satisfy it.

The row shape is right for the **teacher** view: in `ExecutionGrades` every row is the same exam,
so the exam name sensibly lives once on `ExecutionGradingSummary`. It is the student view, where
each row is a different exam, that has nowhere to put it.

Not a blocker for this PR — the session renders whatever rows arrive, and every test above holds
regardless. But the screen cannot ship without it.

**The contract's own rule allows the fix:** "additive changes only (new optional fields, new
verbs)". Adding nullable `examName` and `courseCode` to `StudentGradeRow` is additive and stays
null on the teacher path where the summary already carries them. That is my recommendation, but
the contract is yours and I have not touched it.

## Definition of Done

- [x] Behavior matches E13.1, E13.3, F9.1 — deviations listed above, none silent
- [x] Unit tests (mocked repositories) + session tests (`FakeClientConnection`), TEAM_SPLIT §3.2
- [x] Edge cases handled and tested: H13.2, H13.5, H12.3 (own-grades-only), retry, wrong payload
- [x] Errors/success/progress feedback present (NFR-21); **no user-initiated refresh** (NFR-18)
- [ ] Design-system components / screen review — n/a, no FXML yet
- [x] `TODO.md` annotated (neither ticked — handler wiring and the screen remain)
- [ ] CI green — after push

## Repository additions — TEAM_SPLIT rule 5

Two reads added to `GradeRepository`, against each clause of the new rule:

| Rule 5 clause | How this PR meets it |
|---|---|
| Established style | Same HQL shape and Javadoc structure as `findByAttempt` / `findAwaitingApproval` |
| Contract-tested on **both engines** | 5 new cases in `ExecutionRepositoryContract`, which already covers `GradeRepository`; run by both `ExecutionRepositoryH2Test` and `ExecutionRepositoryMySqlTest` — 16 each, 32 total, both green |
| Consumer named in the Javadoc | `findApprovedForStudent` → "E13.3's `MY_GRADES_GET`"; `findForStudent` → "E13.4's `CHECKED_FORM_GET`" |
| Flagged in a dedicated PR section | This one |
| Post-merge pass by Member A | Over to you — both are additive, nothing existing changed |

Neither returns correctness data — `Grade` is scores and audit fields — so neither touches
`CorrectnessLeakGuardTest`.

Two of the five contract cases are the ⚑ ones, and they are at the engine level rather than
against mocks: another student's grade id and a never-created id return **equal** results, so the
indistinguishability is proven against real SQL on both engines rather than against a stub that
was told to return empty.

## Next

E13.4's checked-form viewer. Its repository read is the `ForCheckedForm` one, which **is**
already sanctioned, so it may be unblocked even while PR #7 waits — I will confirm that before
starting rather than assume it.
