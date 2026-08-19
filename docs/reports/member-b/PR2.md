# E12 PR 1 — statistics calculator (E12.4, partial)

**Branch:** `feat/b-e12-grading` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-19

`server.features.grading.ScoreStatistics` — the computation half of E12.4 / F8.5: scores in,
`mean · median · σ · min · max · deciles` out, as an immutable record built by a static factory.

**This is deliberately a small PR.** E12/E13 cannot start properly yet — the protocol is not
frozen and the E2 entities are not on `main` (see Blocked below). `ScoreStatistics` is the one
piece of E12 with **no dependency on either**: a pure function over `List<Integer>`, touching no
entity, no repository, no `Verb` and no DTO. Nothing here needs revisiting when the contract
lands; the service that loads grades and writes `exam_executions.stats` wraps it.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| Tests | 934 (baseline on `main`: 918) — 16 new, 0 failures |
| Skipped | 19 — the MySQL suite, no local server; CI runs it via `HSTS_REQUIRE_MYSQL` |
| Coverage gate | ✅ met; 86 classes analyzed (was 85) |
| Values vs the seeded fixture | ✅ mean 78.0, median 80.5, σ = √171, min 55, max 96, deciles `[0,0,0,0,0,1,1,2,2,2]` |

The fixture is not invented: it is execution **4821** from `docs/seed/SEED_CONTENT.md` §9.1, the
eight Algebra students whose statistics are frozen in the seed. E12.4's task line asks for
"values unit-tested against hand-computed fixtures" — this is that, and it means the seed
document and the code now check each other. If either drifts, a test goes red instead of a
defence panel seeing two different averages.

## Design decisions

| # | Decision | Why |
|---|---|---|
| a | **σ is the population form** (divisor `n`) | Your PR #2 answer. σ = √(1368/8) = √171 ≈ 13.08 for the fixture. |
| b | A **guard test** asserts the sample form is *not* produced | `sampleStandardDeviationWouldBeWrong()` pins ≈13.98 as the wrong answer. If someone later "corrects" the divisor to `n-1`, the failure names the reason instead of just going red. The decision currently lives only in the review thread — see Blocked #3. |
| c | `of()` returns **`Optional`**, empty when there are no scores | H14.1: an execution nobody sat has no statistics, and F9.2 wants an insufficient-data state. A zero-filled record renders as "a class where everyone scored 0" — indistinguishable from real data on a histogram. The Optional makes that case impossible to skip at the call site. |
| d | Score **100 lands in the top decile** | `score / 10` would index an eleventh bucket. Ten buckets; index 9 covers `[90, 100]`. |
| e | Scores outside 0..100 throw | `ck_grades_final_score` already constrains the column, so an out-of-range value is a caller bug, not data to be tolerated. |
| f | `deciles` is immutable | Stored statistics are frozen at close (F7.3); an in-place edit of a returned list would be a silent corruption. Tested. |
| g | Median is a `double` | 8 scores with 78 and 83 in the middle give 80.5. An `int` median would round the seeded fixture to 80 and disagree with the seed document. |

## Deviations

**1. `pass rate` is not implemented.** F8.5 and F9.2 both list it in the stored statistics.
**No passing threshold is defined anywhere in the PRD** — I grepped — and it is also unstated
whether a timed-out attempt scoring 0 belongs in the denominator. I left it out rather than
guessing 55 or 60: it is a *stored* number that both the histogram and the report engine display,
so a wrong guess propagates into two screens and is invisible until someone checks by hand.
Adding it later is one more record component and one more test; nothing above changes.
See Blocked #2.

**2. E12.4 is annotated in `TODO.md`, not ticked.** The computation is done; `→ stored` needs the
E2 entities and the frozen contract, and pass rate needs a decision. Ticking it would overstate.

## Blocked — I cannot go further without these

**1. Protocol freeze for E12/E13.** `Verb` on `main` has `LOGIN`, `LOGOUT`, the two legacy bank
verbs and six `PUSH_*`. There are no grading or results verbs and no `common/dto/grading`
package. TEAM_SPLIT §3.1: *"before A or B starts a feature, [L] merges the feature's `Verb`s +
DTOs with Javadoc"*. So I stopped at pure logic rather than inventing a contract you would have
to unpick. **Everything else in E12/E13** — handlers, the grading queue, per-student review, My
Grades — is waiting on this.

**2. The pass mark.** Deviation 1. Two sub-questions: the threshold, and whether a timed-out 0
counts in the denominator.

**3. The σ decision is not in any file.** As of `14bc23f`, `PRD.md` F8.5 still reads "standard
deviation" with no divisor named, and §6's Grading and Reports lines are unchanged. The decision
exists in the PR #2 review thread and now in this code's Javadoc. Decision (b) above is a test
that enforces a rule the PRD does not yet state — which is backwards, and worth fixing before
E14 is written by someone who never read the thread.

**4. E2 entities are on `feat/e2-entities`, not `main`.** `AttemptAnswer`, `AttemptStatus`,
`HibernateUtil`, `Transactions`. E12.1 (auto-grade over real attempts) and E12.5–E12.8 sit on
that layer.

## Findings that affect others

**1. The seed is now executable as a test fixture, not just demo data.** `ScoreStatisticsTest`
asserts against execution 4821's frozen values. Anyone changing the Algebra roster, the grade
spread, or the σ convention breaks this test — which is the intended behaviour, but worth knowing
before someone edits `SEED_CONTENT.md` expecting it to be inert documentation.

**2. Two local-setup traps, offered for `PROBLEMS.md`** (not my file to edit). IntelliJ's embedded
terminal inherits the environment from when the **IDE** launched, so opening a new tab after
installing a JDK does not pick it up — the IDE must restart. And a user-level `MAVEN_OPTS`
carrying JDK 24+ flags (`--sun-misc-unsafe-memory-access=allow`, `--enable-final-field-mutation`)
makes JDK 21 refuse to start the JVM at all, with an error that names the flag but not the cause.
Between them these cost an hour; neither is guessable from the message.

## Definition of Done

- [x] Behavior matches the PRD ids named in the task (F8.5 / S-25, E12.4) — deviations listed above, none silent
- [x] Unit tests; coverage not lower than `main` (86 classes analyzed, gate met)
- [x] Edge cases from the hardening plan handled and tested: H14.1 (no participants), H14.2 (single participant), H14.3 (identical scores)
- [ ] Integration where protocol/DB touched — n/a, this PR touches neither by design
- [ ] Design-system components / screen review — n/a, no UI in this PR
- [x] `TODO.md` annotated (E12.4 partial, with the reason it is not ticked)
- [x] Open questions recorded with the assumption each one runs on
- [ ] CI green — after push

## Next

- Unblocked by the protocol freeze: E12.1 auto-grade, E12.2 approve, E12.3 override + audit.
- Unblocked by the pass mark: complete E12.4 and tick it.
- Unblocked by `feat/e2-entities` merging: everything that reads an attempt.
- H12.\* and H13.\* items become real tests as each task lands.
