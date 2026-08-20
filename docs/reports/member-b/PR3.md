# E12 PR 2 — pass rate (completes E12.4's computation)

**Branch:** `feat/b-e12-passrate` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-19

Adds `passCount` and `passRate` to `ScoreStatistics`, the one component PR #4 left out because
the threshold was undecided. Now decided: **pass mark 55**, denominator is **every attempt
carrying a final score, forced-submit zeros included**.

That completes the *computation* half of E12.4. `→ stored` still waits on the frozen contract
and the service that writes `exam_executions.stats`.

## Verification

| Check | Result |
|---|---|
| `./mvnw clean verify` | ✅ BUILD SUCCESS |
| `ScoreStatisticsTest` | 22 tests (was 16) — 6 new, 0 failures |
| Seeded fixture 4821 | ✅ passCount 8, passRate 1.0 |
| Boundary | ✅ 55 passes, 54 fails |
| Zeros in denominator | ✅ `[0, 0, 80, 90]` → 2/4 = 0.5 |

## Design decisions

| # | Decision | Why |
|---|---|---|
| a | Both `passCount` **and** `passRate` are stored | A lone `0.875` invites "is that 0.875% or 87.5%?". Keeping the numerator means the stat card can render "7 of 8 (87.5%)" without anyone reconstructing it by multiplying. |
| b | `passRate` is a **fraction in [0,1]**, not a percentage | Stated in the Javadoc and pinned by a test, because this is exactly the kind of unit ambiguity that produces a screen reading "87.5%" next to one reading "0.875%". |
| c | `PASS_MARK` is a **public constant** on `ScoreStatistics` | E14/E15 render the stored value and must never redefine the threshold. A public constant means that if anyone ever does need the number, they take it from the one place that computes it. |
| d | Zeros stay in the denominator | Your rule, and worth a named test: a forced-submit 0 is an attempt that was sat and failed, not an absence. Excluding them flatters the rate — `[0,0,80,90]` would report 100% instead of 50%. |

## Findings

**1. The seeded execution's pass rate is 100%, because its lowest score is exactly the pass mark.**
Execution 4821's final scores are 55, 64, 71, 78, 83, 85, 92, 96 — every one at or above 55. So
the F9.2 stat card will read **8 of 8 (100%)** on the demo dataset.

Not wrong, and I am not proposing a seed change — you confirmed the seed as-is, and altering it
would ripple into the frozen stats, my tests, PRD §5 and acceptance case 10.3. But it is worth
knowing before the defence that the pass-rate card shows a flat 100%, which demonstrates the
number exists without demonstrating that it discriminates. Flagging, not asking.

**2. The seeded override turns out to flip a fail into a pass — which makes the T-8.3 demo much
better than I realised when I wrote it.** `yael.azulay`'s AUTO score is 51, below the mark; the
justified override to 55 is what makes her a pass. On auto scores the execution is **7 of 8**;
on final scores it is **8 of 8**.

So the override demo now visibly moves a statistic rather than just changing one row. There is a
test pinning both numbers (`theOverrideChangedTheOutcome`) so nobody "tidies" 51 to something
above the mark and quietly removes the effect. This was luck, not design — I picked 51 before a
threshold existed.

**3. The pass mark is not in the PRD.** F8.5 on `main` at `cb5a24c` reads
*"…min/max, pass rate, decile distribution 0–100"* — no threshold, no denominator rule. Grepping
the whole PRD for `55`, `pass mark`, `passing` and `threshold` returns nothing.

The σ decision **did** land this round, and F8.5 now names the population divisor explicitly —
that one is fixed. The pass mark has the same problem σ had: it is a stored, defence-critical
number whose definition lives only in a chat message. `PASS_MARK = 55` and its Javadoc are
currently the only written record, which puts a constant in my code as the source of truth for a
product decision. One line in F8.5 fixes it.

## Definition of Done

- [x] Behavior matches F8.5 and the reviewed decision — deviations listed above, none silent
- [x] Unit tests; coverage not lower than `main`
- [x] Edge cases handled and tested: boundary (54/55), all-fail, all-pass, zeros in the denominator
- [ ] Integration where protocol/DB touched — n/a, touches neither by design
- [ ] Design-system components / screen review — n/a, no UI
- [x] `TODO.md` annotation updated (E12.4 still not ticked — `→ stored` remains)
- [x] Findings recorded
- [ ] CI green — after push

## Next

Still blocked on the **E12/E13 protocol freeze** — no grading or results verbs and no
`common/dto/grading` package on `main`. That is the last thing holding E12.1–E12.3 and E12.5–E12.8,
and now that E2's entities are merged it is the *only* thing.
