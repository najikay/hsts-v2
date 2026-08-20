# E2 PR 4 — `SeedDocument`, the one reader, and the check PR 3a could not make

**Test infrastructure only. No production code changed.** Approved as its own PR rather than
folded into 3b, because 3b is blocked on content amendments and this is not.

## Verification

| | |
|---|---|
| Build | `./mvnw clean verify` on JDK 21, `HSTS_REQUIRE_MYSQL=true` → **BUILD SUCCESS** |
| Tests | **2379**, 0 failures, 0 errors, **0 skipped** (`main` at `5158cd5`: 2337) |
| Coverage | **98.76%** bundle, **98.05%** `server.db.**` — identical to `main` |
| Production code | none. `git diff --name-only origin/main...HEAD -- src/main/java` is empty |
| Both engines | the document-versus-database contract runs on H2 and real MySQL |

Coverage is unchanged rather than merely close: adding no production code means there is
nothing new to measure, so the figure is `main`'s own.

## 1. What this is for

PR 3a shipped 40 hand-transcribed questions and said plainly in its §4 that **its own tests
could not catch a transcription error**, because the assertions were written from the same
constants as the loader. A mistake copied into both agrees with itself and passes. That was not
hypothetical: two prose errors survived exactly that way and were found by a cold read, and I
asserted `24L` for a count whose real value was 29.

`SeedDocument` removes the possibility. It reads `SEED_CONTENT.md` and returns typed rows;
`SeedLoadedDbTest` compares the loaded database against those rows. The loader is now checked
against the source rather than against a second copy of my own belief.

## 2. The demonstration

The claim above is worth more as a measurement than as an argument, so I broke the loader on
purpose. In question 11003 the document reads `k = 2 | k = 6 | k = 3 | k = 12`, correct **3**,
so the right answer is `k = 3`. I swapped options 2 and 3 in the loader and left the index
alone. The stored data still has four distinct options, still has an index in 1..4, still has
40 questions, and now marks `k = 6` correct.

| Suite | Result |
|---|---|
| PR 3a's own `SeedDatasetH2Test` | **15 tests, BUILD SUCCESS.** Blind to it. |
| This PR's `SeedLoadedDbH2Test` | **FAILS**: `11003 option 2: document has 'k = 6', database has 'k = 3'` |

That is the entire case for the PR, and it reproduces in one command. Reverted and re-verified.

## 3. Design, and the three semantics that survive it

**Silence is a failure, structurally.** Every accessor throws when its section is missing, when
the section holds no table, or when the table holds zero data rows. Your ruling, and the reason
is in the code: a parser that answers "no rows" makes every assertion built on it pass
vacuously, which is how §9's unreachable auto-scores survived review. There are five tests
feeding deliberately broken documents from `@TempDir` to prove each refusal is real.

**Normalisation is per field, because the markdown here is not always decoration.** A blanket
strip would have destroyed all three of the semantics you flagged:

| Document | Why a blanket strip loses it |
|---|---|
| `**v1**` in §8.1 | It is a **pin**, not bold. Returned as `CompositionRow.pinnedVersion()`; an unpinned slot stays `null` and is never defaulted to 1, because "pinned to v1" and "the latest" are different statements and only one exercises the composite FK. |
| `—` in §9.1.1 | It means **no row** in `attempt_answers`, not a null selection. `SelectionRow.answered()` keeps them apart. `omer.katz` has four absent and three answered, which is H12.4's fixture. |
| `*` | Sometimes literal. Question 22001's stem holds both `*before*` (emphasis) and `COUNT(*)` (SQL). Emphasis is stripped only as a matched pair around non-space text. |

**A parser that is not itself tested is a confident liar.** The throwaway script I used to verify
PR 3a's re-transcription produced **three false positives before a true negative** — a `//`
comment containing a quoted string, backticks mid-string rather than at the edges, and the space
preceding an em dash. All three are now fixtures in `SeedDocumentTest`, so the same mistakes
cannot be made again quietly.

## 4. One thing the work found about PR 3a

**The em-dash house rule is not one transformation.** PRD §4.1 permits "a comma, a period or a
colon", and the loader uses different ones deliberately: a colon reads correctly in a title
(`מבחן אמצע: אלגברה`) and a comma in a sentence (`Nothing, it is safe`). My first version of the
check produced "the comma form" and failed every exam title — a test asserting a rule stricter
than the one it enforces. It is now a predicate, `followsHouseRule`, exact about everything
except the single character the PRD leaves to judgement, with tests that it does not drift into
"close enough".

## 5. What this proves, and what it does not

It proves the loader **matches the document**. It cannot prove the document is **internally
consistent**.

The live example: notification 8's title states the closed execution's mean as 78, while §9.1's
frozen statistics now say 72.5. **The document and the loader both say 78**, so they agree with
each other and everything here passes. Catching that needs recomputation, which is
`SeedArithmeticTest`'s job. Two checks, two failure classes, neither substituting for the other,
and worth stating explicitly so nobody assumes one reader plus one assertion covers both.

## 6. Held back deliberately, for a better reason than tidiness

Two loader-data fixes that current rulings imply are **not** in this PR:

1. **21003 v2's author.** D9's refinement puts second versions in a co-taught course on the
   co-teacher, so it should be `tamar.shani`; the loader uses `avi.mizrahi` for all of course 21.
2. **Notification 8's mean.** It depends on the number Amjad writes, so doing it now is guessing.

I proposed holding them to keep this PR purely test infrastructure. The lead's reason is better
and is the one recorded here: landing this machinery **first** turns the 21003 fix into the
test's **first catch on genuine drift** rather than on a planted attack. Amjad's batch writes the
D9 rule into the document, the check flags the mismatch against merged 3a, and the fix commit is
then evidence that the machinery works on a real disagreement nobody staged.

**One thing that will not happen automatically, so it should not be assumed.** That catch needs
two things this PR does not have. The document has no author column for questions today, so
`SeedDocument` cannot read one and `SeedLoadedDbContract.questionsMatch` does not compare
authors. When Amjad's amendment adds it, **I add the accessor and the assertion in 3b**; until
then the drift is real but invisible. Flagged so the sequencing is understood rather than
discovered.

## 7. Scope

`SeedDocument` parses §1 to §8, §9.1, §9.1.1 and §11 — every section with a stable table. §10 is
excluded because its sources use a non-table format *and* its content is mid-amendment, and §9.2
because it has no rows yet. Both arrive in 3b with the content. Including §9.1.1 now is
deliberate: it is what lets Amjad port `SeedArithmeticTest` immediately rather than waiting on
content that is not his to unblock.

`SeedLoadedTestBase` is extracted from `SeedDatasetContract` so both contracts share one
load-once lifecycle. That lifecycle carries the measurement behind it: loading per test method
took 290 seconds against MySQL, once per class takes under 8.

## 8. Definition of Done

- [x] Matches the ruling: one reader, no assertions in it, fails on empty matches
- [x] Its own tests, including the three failure classes that fooled the throwaway
- [x] Both engines; coverage unchanged because no production code changed
- [x] No secrets; nothing outside `src/test/java/server/db/**` and this report
- [x] `docs/TODO.md` unchanged — this closes no task on its own, it unblocks E2.15's verification
- [ ] CI green — ticked after the run

## 9. Next

**PR 3b** closes E2.15 when Amjad's amendment batch lands: §10's two rules, §9.2's execution-2
content, §9's four NOT NULL amendments, §11's seed id column, and the stale-prose fixes. The
transcription will be machine-verified against the document from its first commit rather than
its last.

**Amjad's `SeedArithmeticTest`** ports onto `SeedDocument` right after, so there is never a
moment with two readers.
