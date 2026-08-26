# BATCH A — assembly #3, the traceability port, and the three-walk fold-in

**Run:** 2026-08-26 · **Branch:** `main` · **Nothing committed by this batch.**
**Inputs:** `ACCEPT-S6-S7.md` (worktree `hsts-acc1`) · `ACCEPT-S10-S12.md` (`hsts-acc2`) ·
`ACCEPT-S13-S14.md` (`hsts-e9-wt`) · `TRACEABILITY.md` (`hsts-e15-wt`)
**Gate:** `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batcha ./mvnw -B clean verify`

Four items: one assembly commit, one document ported and corrected, three acceptance walks folded
into one register, and a handful of small corrections riding along. The assembly is the only one
that changes behaviour; everything else is the project's record of itself catching up with what
the code already does.

---

## Item 1 — assembly commit #3: the exam builder is reachable

**The acceptance criterion was four named test cases going from red-by-design to green**, and they
are green.

`ExamBuilderWiringGuardTest` has been failing on `main` on purpose since E7.11 merged. Its failure
message names the exact thing missing, which is the point of writing a guard rather than a ticket:
*"No role can navigate to route id 'exams.build'. The exam list's Edit and View buttons navigate
there, and Navigator.navigate THROWS on an unregistered id."* That last clause is why this was
worth doing before anything cosmetic — the failure mode was not a dead button, it was an exception
in front of a teacher.

**What landed, in five files:**

| File | Change |
|---|---|
| `client/core/Routes.java` | `EXAM_BUILD` declared — `Route.shell("exams.build", "Exam builder")`, **non-rail**, and added to `all()` |
| `client/core/SessionRoutes.java` | registered inside the `teaches(role)` block beside `Routes.EXAMS`, with the reasoning as a comment; mapped to `ExamBuilderView::new` in `builderFor` |
| `pom.xml` | JaCoCo exclusion for `client/features/exambuild/ExamBuilderView*` |
| `client/core/AppArgsAndRoutesTest.java` | `"exams.build"` added to the exact-set id list |
| `client/core/SessionRoutesTest.java` | `Routes.EXAM_BUILD` added to **both** role lists |

**Four decisions worth stating rather than leaving to be inferred.**

**It is not on a rail.** It is a view of one exam, reached from the list's Edit, View and New
buttons carrying a nav parameter, so a rail item that needed an exam chosen first would be a dead
end — the same reasoning `EXAM_PREVIEW` and `QUESTION_EDIT` already carry, and the javadoc says so
by naming them.

**Both teaching roles, not one.** The contract's §2 gives both TEACHER and COORDINATOR the right to
author, and the exam list already offers Edit to both. Registering it for one is precisely how a
coordinator finds a button that throws — which is the second of the five mutations the guard exists
to reject, and it would have passed every other assertion in the class.

**The id came from the feature's own constant.** `Routes.EXAM_BUILD` is spelled `"exams.build"`
because that is what `ExamBuildRoutes.BUILDER` has read since #51 declared it ahead of the screen.
The guard pins the two spellings together *and* pins the mapping against a literal, so a drift
between the declaration and the buttons fails the build rather than throwing in front of a user.

**The pom already carried `ExamListView*`, and it needed `ExamBuilderView*` beside it** — verified
rather than assumed, since the ask said it might already be there. It is excluded on E7.10's exact
reasoning: `ExamBuilderSession` and `ExamBuildCopy` are where every decision it renders is made,
and both are measured. Note the file's own standing rule, which this change obeys: view classes are
listed **one by one and never as `client/features/*/…View*`**, because a single `*` in JaCoCo
crosses path separators and one wildcard would silently un-instrument whole logic subpackages.

**What this closes and what it does not.** F3.1 is closed — the builder is reachable and the guard
is green. **F3.2 is not**, and the two were always separate holes: a teacher can now open the
builder on an existing exam, and still cannot add a question to an empty one, because
`ExamBuilderSession.addFromBank()` returns `false` unconditionally. Only one of the two was ever an
assembly problem. See item 2.

---

## Item 2 — `docs/TRACEABILITY.md` ported, with two rows corrected on arrival

136 requirement ids, each with its owning class, the test that goes red if it breaks, its
acceptance case numbers and an honest status. **Two of its three headline gaps had moved between
the writing and the landing**, and both are marked in place rather than silently rewritten — a
matrix that quietly re-states itself is worth less than one that shows what changed.

### Gap 1 (F3.2) — the stated blocker was stale

It read *"blocked on `questionVersionId` missing from the frozen BANK wire — not `BankQuestionRow`,
not `QuestionDetail`, not `QuestionVersionDetail`… raised as PR23 ask #2"*. That was true when
written. **`BankQuestionRow.latestVersionId` has been on the wire since BANK amendment A1**
(2026-08-25), which shipped with the integration commit for exactly this join: the picker is
`BANK_LIST`, `QuestionPin` keys on `questionVersionId`, and the row now carries the version PK the
pin needs. **The frozen-contract ask is answered and nothing is blocked on [L].**

**The residual is one method** — `addFromBank` adopting the field it now has — and that is PR24's,
in flight beside E7.13's auto tab. **The row stays `GAP` rather than being softened to `PARTIAL`**,
per the file's own convention: a path that cannot be reached is a gap however small the remaining
work looks, and one unadopted field is exactly the size of hole that gets reported as nearly-done
for a fortnight.

### Gap 2 (F3.1) — closed by item 1

Moved **PARTIAL — unreachable → LIVE-unwalked**. The narrative is kept in place and struck through
rather than deleted: this was the most defense-relevant hole in the matrix for a fortnight, and a
reader who remembers it deserves to be told what happened to it rather than finding it quietly
absent. Counts updated with it — **16 LIVE · 105 LIVE-unwalked · 11 PARTIAL · 3 GAP · 1 N/A**.

### The B-8 / NFR-17 rows, re-checked against the register

Both now cite **B-8** by number, and B-8 itself absorbed the scenario-6 walk's independent
re-finding (see the renumbering map below). NFR-17's "guarded by" list also lost a citation to
**`SeedArithmeticTest`, a class that has never existed** — see item 4.

### One more correction the fold-in forced

The file said *"eighteen of the twenty-one scenarios have never been driven at a keyboard —
everything except 1, 8 and 9."* The pre-walk campaign has overtaken that, and the distinction is
worth more than the count: **scenarios 1–14 are now all walked**, with **1, 8 and 9 driven at a
keyboard** and **2–7 and 10–14 walked below the screen** — production services, repositories,
router and a real MySQL, and not one rendered pixel. **15–21 are the untouched ones**, and they are
the non-functional half, several of which cannot be walked below a screen even in principle.

**`docs/TODO.md`: E22.0 ticked**, with a note carrying the counts, the two corrections and their
reasons.

---

## Item 3 — the three-walk fold-in

### The renumbering map

Three walkers ran in parallel and each reserved a sparse range so it could number without waiting
for the others. The ranges are collapsed into one compact sequence continuing from **B-13**. This
map is at the top of the register too, so a reader arriving from a walk report lands somewhere:

| Report's number | Canonical | What |
|---|---|---|
| S6-S7 **B-14** | **B-14** | attempt duration never reconciled with the execution window |
| S6-S7 **B-15** | **B-15** | no loadable illustration — **merged into B-8**, one ticket |
| S10 **B-20** | **B-16** | teacher results carry no attempt status and no solving time |
| S10 **B-21** | **B-17** | case 10.5 names an execution its own actor cannot open |
| S12 **B-22** | **B-18** | one reportable sitting, so no report is a comparison |
| S12 **B-23** | **B-19** | `SEED_CONTENT.md`'s Hebrew claims are stale |
| S13 **B-26** | **B-20** | the C-4 integrity notice repeats on every message |
| S13 **B-27** | **B-21** | a bot source has no edit path at all (F12.3) |
| S13 **B-28** | **B-22** | the lockout message carries no unlock time, against the PRD |

**The three walk reports keep their original numbers and were copied in unedited.** Rewriting a
report to match a register that did not exist when it was written trades one confusion for a worse
one — a document whose numbers no longer match the evidence log it was produced from. The map is
the join.

**On B-15 specifically:** the scenario-6 walk found the missing seed images independently of the
scenario-2 walk that had already filed them as **B-8**. That is one fixture gap and it gets one
ticket. B-8 absorbed the new evidence — the sharpest of which is that **three of the seven
questions on the live demo paper (`11005`, `11007`, `11010`) are among the ten flagged `img`**, so
this is the demo's problem and not an obscure corner. B-15 remains as a one-line pointer so the
number is spoken for and cannot be reused.

### Fold-in counts per scenario

| Scenario | Cases | Folded | Outcome |
|---|---|---|---|
| 6 — Exam execution | 10 | 10 | ✅ 10 pass. B-8 (illustrations) |
| 7 — Extending duration | 4 | 4 | ✅ 4 pass **as written**; B-14 found in 7.1's own path |
| 10 — Teacher results | 5 | 5 | ✅ 4 pass · ⚠ 10.2 (B-16); 10.5's actor fixed (B-17) |
| 11 — Principal data | 4 | 4 | ✅ 4 pass |
| 12 — Reports | 5 | 5 | ✅ 4 pass · ⚠ 12.1 (B-18, closed as a demo decision) |
| 13 — Creating a bot | 6 | 6 | ✅ 4 pass · ⚠ 13.6 · ❌ **13.4 (B-21)** |
| 14 — Using the bot | 7 | 7 | ✅ 2 pass · ⚠ 4 (the provider half is E16.17's) · ❌ **14.7 (B-20)** |
| **Total** | **41** | **41** | 28 ✅ · 9 ⚠ · **2 ❌** · 9 register entries |

Summary rows for all seven scenarios rewritten; every `Actual` cell and every status came from the
walkers' own proposals rather than being re-derived here.

### The statuses, and the four that were decided rather than recorded

**Open, assigned [L], batch B — B-14, B-16, B-20, B-21.** All four are real defects with a named
fix and none is a one-liner.

- **B-14** is the one to read first. Two clocks govern a sitting — the entry window and the
  per-attempt deadline — and **nothing compares them**. A student who joins legally late is
  promised a full paper and force-submitted when the window shuts: *promised 75 minutes, given 2,
  and told neither.* The extension path is worse, because delivering minutes is the whole point of
  the verb — the teacher granted fifteen, the toast announced them, and the student got none of
  them. **The seed's own live fixture is already in this state.** Four options were put and none
  applied: it touches E9's release rules and E11's extend verb, so it is a decision, not a patch.
- **B-16** is B-3's shape turned around: a field **read and then dropped** rather than written and
  never read. `findResultRows` already selects `a.actualMinutes`; `toWire` maps ten components and
  discards it. The visible cost is that `omer.katz`'s timed-out paper is indistinguishable from
  seven submitted ones on the screen built to show exactly that.
- **B-20** is a cadence bug with a correct alert underneath it — the flag and the notification are
  once-per-attempt as designed; only the *prompt* repeats. The fix is client-side, and the server
  must keep refusing to trust the acknowledgement flag on the same-course branch.
- **B-21** is a missing feature the PRD asks for by name (F12.3, "add/**edit**/remove"). Its
  second-order effect is the interesting part: the advisory lock on `BOT_SOURCE` works and is
  probed working, and **the only thing it can protect is a remove**, because there is no editor to
  make read-only.

**Fixed here — B-17, B-19.**

- **B-17** was a defect in the case, not the code. Case 10.5 asked `dana.cohen` to open `5164`, a
  sitting of an exam `michal.sharon` wrote, so S-35 correctly refused and the empty state the case
  exists to show was never reached. **The case now runs as `michal.sharon`**, with a parenthetical
  saying why the actor moved.
- **B-19** was a frozen document asserting a property of the database that stopped being true.
  `SEED_CONTENT.md` §3 claimed Hebrew names throughout and §7 claimed mixed-language questions;
  **the loader seeds no Hebrew codepoint at all** and has not since UI wave 1. Both sections
  rewritten to English reality with dated notes. **Where the RTL evidence moved to matters more
  than the correction**: `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip` writes its own Hebrew
  sample rather than reading the seed's, so the guarantee is now held by a test that owns its
  fixture instead of by demo data that could be translated out from under it. A third instance of
  the same stale claim was found and fixed in `DEMO_DAY.md` §4.7, which told the operator the
  questions would "render in Hebrew".

**Closed as a demo-script decision — B-18.** Registered deliberately as a decision and **not as a
code bug**: the engine is correct, the arithmetic is proven on multi-row unit fixtures, and the
seed is right for the scenarios it was built for — `7390` *has* to be ungraded or case 8.2 has
nothing to prove. The problem is only that `REPORTABLE` selects one sitting on a fresh seed, so the
screen that exists to compare gets demonstrated comparing one thing. **The ruling is sequencing:**
walk grading approval (8.5) **before** reports (12.x), so approving `7390`'s eight grades freezes a
second set of statistics and the report **grows a row in front of the examiner**. `DEMO_DAY.md`
§5.6 carries it, including the fallback sentence to say if the order slips. The rejected
alternative — seeding a fifth closed execution — costs a seed change and turns a live
demonstration back into a static one.

**Closed by ruling — B-22.** The same-course bot lockout message prints no unlock time and PRD
§"Bot" said it should. **Ruled: the message is right and the PRD is what moves.** The only deadline
this feature can reach is the one captured when the attempt started, and a teacher granting extra
time (F7.1, S-20) moves the real deadline without moving that copy — **a stale unlock time is worse
than none**, because a student plans around it, leaves, and comes back still locked. B-14 sitting
one row above, showing two clocks in this system already disagreeing, is the strongest argument
available for not printing a third number. The PRD line is amended and dated; **no code changed**.

---

## Item 4 — small corrections riding along

**Case 14.4's expected sentence.** It quoted *"The bot couldn't answer that — try rephrasing or ask
your teacher."* The product says *"The bot could not answer that. Try rephrasing, or ask your
teacher."* — PRD F12.7 and `BotAnswer.S32_FALLBACK`, in agreement. The paraphrase carried an em
dash and a contraction, **both of which PRD §4.1 forbids on screen**, so the case was checking for
copy the code is forbidden to produce. Corrected to the code's exact string, with the reason.

**BANK contract amendment numbering.** Two amendments were both labelled **A1** — `latestVersionId`
(2026-08-25) and the dash-sentinel reversal (2026-08-26). Two amendments under one letter is how a
contract stops being citable. The dash one is renumbered **A2**, `latestVersionId` keeps **A1**, and
a one-line note at the renumbered heading says what happened. **Three downstream citations followed
it**, which is the reason this was worth doing rather than shrugging at: `QuestionEditorSessionTest`'s
javadoc (a live source citation) and two places in `ACCEPT-FIXES.md`.

**The `SeedArithmeticTest` citations.** `ACCEPT-FIXES.md` recorded that its brief named a test that
**does not exist and never has** — `find src -name '*Arithmetic*'` returns nothing — and that the
arithmetic assertions actually live in `SeedDatasetContract`. The name was still being cited in
four places, and a citation to a class that does not exist reads as coverage on a cold review:

| Where | Kind |
|---|---|
| `SeedDocument.java` javadoc | corrected, with a note saying the citation was never real |
| `SeedLoadedDbContract.java` javadoc | corrected, same |
| `SeedDocumentTest.java` comment | corrected |
| `README.md` | corrected (and a stray backslash in `dataset\'s` with it) |
| `docs/TRACEABILITY.md` NFR-17 row | dropped from the "guarded by" list |

**The tests were not touched** — only the citations, which is what the ask said and is also the
right call: the assertions are real and green, and it is the naming that was wrong.

---

## Verify

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batcha ./mvnw -B clean verify
```

**Result: BUILD SUCCESS.**

| | |
|---|---|
| Tests run | **6,398** across 854 test classes |
| Failures / errors / skipped | **0 / 0 / 0** |
| `ExamBuilderWiringGuardTest` | **7 run, 0 failures — the four red-by-design cases flipped, which is item 1's acceptance criterion** |
| JaCoCo `check` | passed — the 90% instruction gate held with `ExamBuilderView*` newly excluded |
| Wall clock | 15 min 46 s, MySQL mandatory (`HSTS_REQUIRE_MYSQL=true`), schema `hsts_batcha` |

**Zero skips is worth naming**, because the MySQL-gated suites skip cleanly when no server is
reachable and a green run full of skips would prove nothing about the data layer. `HSTS_REQUIRE_MYSQL=true`
turns that skip into a failure, and nothing skipped — so the repository contracts and the seed
suites really did run against a real engine.

`DeepSeekProviderTest` was flagged in the brief as a possible timing flake needing one rerun. **It
did not flake**; no rerun was needed.

The guard's four cases are the ones worth naming, because they are what this batch was measured
against: *the builder is offered to somebody* · *both authoring roles are offered it* (a
`@ParameterizedTest` over TEACHER and COORDINATOR) · *the principal is never offered it* · *route id
`exams.build` resolves to `ExamBuilderView`*. The last one is the load-bearing one: the other three
would stay green if the id were mapped to any other screen at all.

**Nothing was committed.** The tree was clean at the start (one local commit ahead of `origin/main`,
which is expected) and every change above is in the working tree for review.

---

## Files touched

**Code (item 1 + item 4's citations)**

`src/main/java/client/core/Routes.java` · `src/main/java/client/core/SessionRoutes.java` ·
`pom.xml` · `src/test/java/client/core/AppArgsAndRoutesTest.java` ·
`src/test/java/client/core/SessionRoutesTest.java` ·
`src/test/java/client/features/bank/QuestionEditorSessionTest.java` ·
`src/test/java/server/db/seed/SeedDocument.java` ·
`src/test/java/server/db/seed/SeedLoadedDbContract.java` ·
`src/test/java/server/db/seed/SeedDocumentTest.java` · `README.md`

**Documents**

`docs/ACCEPTANCE_TESTS.md` (7 scenarios, 41 cells, 7 summary rows, 9 register entries, the
renumbering note, B-8 widened) · `docs/TRACEABILITY.md` (**new**) · `docs/TODO.md` (E22.0) ·
`docs/PRD.md` (the Bot line, B-22) · `docs/DEMO_DAY.md` (§5.6 sequencing, §4.7 Hebrew) ·
`docs/seed/SEED_CONTENT.md` (§3, §7 — B-19) · `docs/contracts/BANK_WIRE_CONTRACT.md` (A1 → A2) ·
`docs/reports/lead/ACCEPT-FIXES.md` (A2 citations)

**Copied in unedited**

`docs/reports/lead/ACCEPT-S6-S7.md` · `docs/reports/lead/ACCEPT-S10-S12.md` ·
`docs/reports/lead/ACCEPT-S13-S14.md`

---

## For the lead, in priority order

1. **B-14 needs a ruling, not a patch.** It is the only finding in this batch that can hurt a real
   student in the room, the seed's own live fixture is already in the failing state, and the
   recommendation on the table (warn or refuse at release/extend time, and widen `2075`'s window)
   touches two of [L]'s epics.
2. **B-21 needs a decision on which document is wrong.** Either `BOT_SOURCE_UPDATE` is built over
   the lock that is already wired, or PRD F12.3 and case 13.4 drop "edit". **The current state
   matches neither**, which is the whole reason it is filed.
3. **B-16 and B-20 are batch-B work** with named fixes and no open questions.
4. **F3.2 is one method away** and it is [A]'s, in PR24. Worth confirming that PR24's scope really
   does include `addFromBank` adopting `latestVersionId`, because the matrix now says so.
