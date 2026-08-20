# Seed amendments — execution 2 authored, NOT NULL rules, TEXT sources, seed ids

**Branch:** `feat/b-seed-amendments` · **Author:** Member B · **Reviewer:** Naji · **Date:** 2026-08-20

All six rulings. Table edits and prose only, no code — this is the document PR that unblocks
Omar's 3b before `SeedDocument` pins the shapes.

## Verification

| Check | Result |
|---|---|
| Execution 1 selections recompute to stated scores | ✅ 8/8, against the seed's own key |
| Execution 2 selections recompute to stated scores | ✅ 8/8 |
| Every auto score reachable by its exam | ✅ both executions |
| Execution 1 frozen stats | ✅ mean 72.5, median 72.5, σ 17.5, pass 7/8 |
| Notification title matches the frozen mean | ✅ 72.5, no stale 78 anywhere |
| Answer-key spread | ✅ `{1:11, 2:10, 3:10, 4:9}` |

## The six amendments

**1 · §9.2 authored in full.** Eight Java students, solving times, auto-scores, and §9.2.1's
per-question selections in the 9.1.1 format. Exam 4 v1 is 6×15+10 like exam 1 v2, so the same
fourteen reachable totals apply and every score is one of them.

The spread is **deliberately unlike execution 1's**: `30, 40, 55, 60, 70, 75, 85, 100` against
`45, 55, 60, 70, 75, 85, 90, 100`. Two students below the pass mark rather than one, so approving
these grades visibly moves a pass rate — and the two executions cannot be mistaken for copies in
a results list.

**Nobody times out here**, so there are no `—` entries. That is the division of labour between
the two: §9.1.1 exercises absent-versus-wrong, §9.2.1 exercises a full paper. Per-question
difficulty varies too — `21010` is missed by six of eight, `21001` by nobody — so E12.6's
breakdown has something real to render.

`21011` is the only question in the bank whose correct answer is **4**, and five students missed
it. Worth preserving when the key is next touched: it is the clearest evidence that the fourth
option is a real answer rather than filler.

**2 · §9's four NOT NULL rules**, stated once rather than repeated per row: `created_by` is the
releasing teacher (named per execution), execution 1's grades are `APPROVED` with `approved_by` =
the executing teacher and `approved_at` = close + 2 days, and `started_at` is **derived** from
window start plus solving time rather than invented — so the timestamp and the stated minutes
cannot disagree. `ended_at` follows, which is what makes `omer.katz`'s 75 minutes the full
allotted duration rather than an arbitrary number.

**3 · §10 sources are all `TEXT`** with `raw` = the UTF-8 of `extracted_text`, and the reasoning
recorded: binary fixtures would tie the seed to a checkout carrying those files and prove nothing
E16's own tests do not. `title` keeps each original document name so the set still reads as mixed.
`bot_messages.provider` is `deepseek` throughout except session 6, which is `anthropic` — the one
row proving the ADR-009 fallback fired. A constant column would demonstrate nothing.

**4 · §11 gains `seed_id`** (the D8 ruling) as the stable identifier, with the `#` column demoted
to presentation order. Notification 8's title said "ממוצע 78"; it now says 72.5.

That one is worth a second look: **it is derived data living in a text column.** Anything that
changes the seeded grades has to change this string too, and nothing enforces it. Same for
`N-GRADING-DUE-JAVA`, which says "8 attempts" and depends on §9.2's roster staying at eight. Both
are flagged in place — candidates for `SeedLoadedDbTest` to assert once the parser exists.

**5 · Two stale roster lines** at §4's coverage note and §8's approver note, both still claiming
Calculus is co-taught. Dana teaches it alone since the roster change.

**6 · The machine-read notice** is directly under the title, verbatim.

## A near-miss worth recording

My first validation run reported seven failures in §9.1.1 and two unreachable scores — in a
section that had already been merged and verified. **The document was fine; my validator was
wrong twice.** It parsed `11005 **v1**→15` as fifteen*ness* discarded and the version marker's
`1` taken as the points, giving exam 1 a total of 86; and it matched selection rows across both
sections at once, which breaks because five students sit in *both* executions.

I nearly reported a false failure against work Naji had already verified. Two things follow.

The `**v1**` pin marker is a **parsing hazard**, not just documentation — any regex reading the
composition column has to skip it explicitly. Worth `SeedDocument` handling on day one, because
getting it wrong yields a plausible wrong total rather than an error.

And section-scoped parsing is not optional. Selection tables in §9.1.1 and §9.2.1 are
structurally identical; only their position distinguishes them. A parser keyed on shape alone
will silently merge them.

Both notes are for Omar. They are the two things I would have got wrong writing `SeedDocument`
from scratch, and I only found them because the checker disagreed with a document I trusted.

## Definition of Done

- [x] All six rulings applied as stated
- [x] Both executions validated against the seed's own answer key
- [x] Frozen stats and the notification title agree
- [ ] Unit tests — n/a, no code in this PR
- [x] Parsing hazards documented for `SeedDocument`
- [ ] CI green — after push

## Next

Omar's `SeedDocument` pins these shapes; I port the recompute checks onto it as
`SeedArithmeticTest` right after 3b merges. The two hazards above should go in before the parser
is written rather than after.
