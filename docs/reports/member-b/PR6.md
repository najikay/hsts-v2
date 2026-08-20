# E13 PR 1 — My Grades session logic (E13.3)

**Branch:** `feat/b-e13-mygrades` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

`client.features.results.MyGradesSession` — everything the student's My Grades screen decides,
as a toolkit-free class: load, empty, error, retry, and live refresh on `PUSH_GRADE_PUBLISHED`.
14 tests against `FakeClientConnection` through a real `RequestDispatcher`.

**Deliberately separate from PR #7.** That one is blocked on the correctness-suffix decision;
this touches neither the guard nor any repository, so it should not wait behind it. No FXML here
either — the screen comes when the component library and I meet, and the logic being separate is
what let this land now.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 1577, 0 failures, **0 skipped** |
| `MyGradesSessionTest` | 14 tests |
| Coverage gate | ✅ met, 202 classes |

Fixture rows are the seeded execution 4821 results for `maya.levi` and `yael.azulay`, so the
tests and the acceptance table's scenario 9 walk the same data.

## Behaviour proven

| Rule | Source |
|---|---|
| Empty state **explains** rather than showing a blank panel | H13.2 |
| A published grade appears with no user action | H13.5, NFR-18, E13.6 |
| The error sentence never repeats the server's wording | F1.1's discipline |
| A wrong payload type fails cleanly instead of throwing | protocol robustness |
| A failed load can be retried and the retry succeeds | NFR-21 |
| An adjusted grade is distinguishable, carrying the comment but never the justification | contract, S-22/S-23 |

## Design decisions

| # | Decision | Why |
|---|---|---|
| a | **No client-side filter for `APPROVED`** | `MY_GRADES_GET` returns approved rows only (C-3, S-24). A client filter would imply the server might send something it should not, and the next person would trust the filter instead of the server. If an unapproved row ever arrived it would be a server bug — hiding it in the client is how that bug reaches the defence. |
| b | The push **re-queries** rather than appending the pushed row | The list is the server's answer to "what does this student have". Rebuilding from it is the only way the two cannot drift; appending is how a screen ends up one row ahead of reality. |
| c | A second `load()` while one is in flight is **ignored, not queued** | Two identical requests can settle out of order and leave the screen showing the older answer. Tested. |
| d | One error sentence for every failure mode | Server error, transport failure and wrong payload type are all the same thing to a student. The distinctions matter in the log, not on screen. |
| e | `wasAdjusted` is derived, not a flag on the wire | The contract already says "overridden" is not a state — it is `finalScore != null`. Deriving it here keeps that single definition. |

## Findings

**1. The justification is stripped by the container, and I have a test that proves it from the
client side.** `MyGrades`'s compact constructor strips `overrideReason`, so even a row built with
teacher-only audit text arrives null. `justificationIsStrippedByTheContainer` asserts exactly
that. Worth knowing the guarantee is now covered from both ends — the DTO's own tests and a
consumer's.

**2. Nothing here needed a `Verb` or DTO change.** The frozen contract covered E13.3 exactly as
written, including `MyGrades.EMPTY` for the empty case. Recording it as evidence the freeze was
the right shape, since the next epic's freeze will be argued from this one.

## Definition of Done

- [x] Behavior matches E13.3 and F9.1 — deviations listed, none silent
- [x] Session tests against `FakeClientConnection` (TEAM_SPLIT §3.2); coverage not lower
- [x] Edge cases handled and tested: H13.2, H13.5, retry, wrong payload
- [x] Errors/success/progress feedback present (NFR-21); **no user-initiated refresh** (NFR-18)
- [ ] Design-system components / screen review — n/a, no FXML yet
- [x] `TODO.md` annotated (E13.3 not ticked — the screen itself remains)
- [ ] CI green — after push

## Next

E13.4's checked-form viewer is the natural follow-on, but `CHECKED_FORM_GET` returns correctness
and so lands on the same suffix decision as PR #7 — its repository read is the `ForCheckedForm`
one, which *is* already sanctioned, so it may be unaffected. I will check before starting rather
than assume. E13.1's authorization tests are unblocked and are next either way.
