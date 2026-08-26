# The acceptance-fixes batch — B-7, B-10, B-11, B-12, the session sweep, and the fold-in

**Run:** 2026-08-26 · **Input:** the two pre-walk reports, now sitting beside this one as
`ACCEPT-S2-S3.md` (scenarios 2–3) and `ACCEPT-S4-S5.md` (scenarios 4–5) · **Scope:** four code
fixes, one audit with fixes, the `ACCEPTANCE_TESTS.md` fold-in, and `PROBLEMS.md` P-12.

Both reports drafted their `B-n` numbers independently and both started at B-7. The canonical
sequence continues from main's existing B-6 and is the one used everywhere below:

| Canonical | Was | What |
|---|---|---|
| **B-7** | S2-S3's B-7 | the minus-sign fold |
| **B-8** | S2-S3's B-8 | the seed has no question images |
| **B-9** | S2-S3's B-9 | case 3.4 names a nonexistent topic |
| **B-10** | S4-S5's B-7 | the scheduled-today window is dead in the afternoon |
| **B-11** | S4-S5's B-8 | `NOTIFICATIONS_GET` INTERNAL on a fresh seed |
| **B-12** | S4-S5's B-9 | `Authorization.describe` leaks `Arrays.toString` |
| **B-13** | S4-S5's B-10 | midterm naming drift |

---

## ⚑ Read this first: three deviations from the brief

**1. The two pre-walk reports had to be transcribed rather than copied.** The brief said to copy
them from `/mnt/c/dev/hsts-acc1` and `/mnt/c/dev/hsts-acc2`. They were read from there at the start
of this batch, and by the time the fold-in reached the copy step **both worktrees had been reused
by a parallel pass** — `hsts-acc1` now holds `ACCEPT-S6-S7.md` and `hsts-acc2` holds
`ACCEPT-S10-S12.md`, and neither original had ever been committed (`git log --all` on both paths
returns nothing; they were untracked files). The copies in `docs/reports/lead/` are therefore
faithful transcriptions of the text this batch read, each carrying a note at the top saying so.
Nothing was summarised or invented, but they are not byte-identical exports and should not be
treated as such. **Worth a process note:** a report that only ever exists as an untracked file in a
worktree somebody else will reuse is one `git clean` from gone.

**2. ⚑ FIX 1 reverses a FROZEN contract ruling, and that needs the lead's sign-off.** The brief did
not mention it and neither pre-walk report cites it, but `BANK_WIRE_CONTRACT.md` (**FROZEN v1**) §5
had already found this behaviour, measured it, and **ruled on 2026-08-22 to keep it** — naming
`co-op`/`coop` and `e-mail`/`email` as pairs that fold, and arguing the same "we refuse something
the database would have accepted, never the reverse" line the class javadoc did. So B-7 is not a
bug nobody had noticed; it is a decision made on incomplete evidence.

**The fix stands, and the reason it stands is the evidence the ruling did not have**: the ruling
priced the cost as "a teacher told two similar answers are too similar", and the walk priced it as
*five seeded questions no teacher can re-save and case 2.4 blocked on its own first step*.
`BANK_WIRE_CONTRACT.md` §5 now carries a dated **amendment A2** stating the reversal, leaving the
original ruling above it in full, and saying exactly which half is reversed. **The spacing half of
that ruling is deliberately left standing** — `sameAnswer("1 2 3", "123")` is still `true`, still
stricter than the collation, but ADR-016 *names* trimming and whitespace collapse as the rule, so
that over-strictness was chosen rather than inherited from a `Collator` artefact, and nothing has
been observed to be blocked by it. It is recorded as an open question rather than a closed one.

Two consequences that had to travel with it: `BankMessages.answersDuplicated` said answers must
differ "by more than spacing **or hyphens**", which is now false in the direction that costs a
teacher work, so it says "spacing"; and `QuestionEditorSessionTest`'s `co-op`/`coop` row **moved
from the refusal list to the acceptance list**, because it had been pinning the defect under a
docstring claiming these are "pairs MySQL calls one answer".

**3. B-11 required adding two enum constants *and* two catalog sentences.** The brief said to
"correct each to its real constant". Four of the six had one. **Two did not.** `GRADING_DUE` and
`EXECUTION_CLOSED` name events `NotificationType` has no constant for at all, so there was nothing
to correct them *to*. Forcing them onto `GRADE_PUBLISHED` (a student-facing "your grade is out")
would have traded a crash for a wrong icon and a wrong toast tone, which is worse for being
invisible. The constants were added, which the enum's own contract explicitly permits ("constants
may be added freely and must never be renamed").

**The full verify then found what the targeted runs had not**, and it is the better argument for
the decision than mine was: `NotificationCatalogTest.everyTypeHasASentence` asserts **every**
`NotificationType` has a `NotificationCatalog` factory. A type with no sentence is a type nothing
can send — and a seed row that bypasses the catalog to write one anyway is *exactly how B-11
happened*. So the two constants also got two factories, `gradingDue` and `executionClosed`, plus
the `ROUTE_GRADING` / `ROUTE_RESULTS` ids and their deep-link parameter entries. **The copy is not
invented**: it is seed §11's own text, already written and reviewed by the content owner,
parameterised. Three compile- or test-enforced invariants now all hold — the exhaustive icon
switch, the vocabulary pin in `NotifyDtoTest`, and the catalog coverage test.

**If the lead would rather the vocabulary not grow**, reversing this is one commit, but the two
seeded rows then need their titles rewritten so their sentences match whatever existing type they
are given — and one of them is the principal's only notification, which S-7 and NFR-21 make load-bearing.

---

## FIX 1 — B-7, the minus sign (Medium)

`QuestionValidator.sameAnswer("1", "-1")` answered **true** while MySQL under
`utf8mb4_unicode_ci` answered *different*, making the validator **stricter** than
`ck_question_versions_distinct`, the constraint its own javadoc says it stands in for, and
refusing rows the system had itself stored. Five seeded questions could not be written back at
all.

### The measurement, which is the part worth keeping

Nothing here was reasoned about. Two sweeps, both executed:

**① Which characters does Java's `Collator` ignore at PRIMARY?** Every defined BMP code point,
excluding combining marks (already stripped by the fold's NFKD step), controls, formats and
unassigned, was asked `compare("a", "a" + c) == 0`. **Twenty-two answered yes: fourteen are
spaces, and the other eight are dashes.** The spaces are out of scope for this fix and why is
argued in deviation 2 above — ADR-016 names whitespace folding as the rule, so that half is
chosen rather than inherited. The dashes are the whole of B-7:

| Code point | Name |
|---|---|
| `U+002D` | HYPHEN-MINUS |
| `U+2010` | HYPHEN |
| `U+2011` | NON-BREAKING HYPHEN |
| `U+2012` | FIGURE DASH |
| `U+2013` | EN DASH |
| `U+2014` | EM DASH |
| `U+2015` | HORIZONTAL BAR |
| `U+2212` | MINUS SIGN |

**② Which of them does MySQL ignore?** `SELECT 'a' = CONCAT('a', c) COLLATE utf8mb4_unicode_ci`
over the whole ASCII punctuation family plus the common maths and currency symbols. **Every one
answered `0` — not ignorable.** The full row, all zero:

```
! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~ ± × ÷ − – — ° · ≤ ≥ ≠ √ ∞ € §
```

So **among punctuation the divergence is exactly the dash family and nothing else.** The report's
original phrasing ("sign and bracket characters") and its own B-7 text ("a minus sign or a
bracket") over-attributed it: brackets, commas and parentheses were never ignorable on either side,
and a fix aimed at "punctuation" would have changed nothing for them while appearing to.

**The spacing divergence is real, measured, and deliberately left alone.** For completeness, since
the sweep found it: MySQL answers `0` — different — to `'1 2 3'`/`'123'`, `'a b'`/`'ab'`,
`'a  b'`/`'a b'` and `'  Two'`/`'Two'`, and `1` only to `'Two  '`/`'Two'`, because the collation is
PAD SPACE and folds **trailing** spaces alone. The validator folds all of them, so it is stricter
there too — the same class of defect as B-7, with a different provenance and no observed cost. See
deviation 2 and contract amendment A2.

**③ Do the eight differ from each other?** All 28 pairs, put to MySQL as `'a?b' = 'a?b'`:

| Pairs | MySQL says |
|---|---|
| 27 of 28 | **different** (`0`) |
| `U+2010` vs `U+2011` | **equal** (`1`) |

That single exception is the measurement that mattered. A first draft of the fix gave each dash its
own sentinel — eight for eight — and `BankRoundTripIntegrationTest` failed on that one row and
nothing else, which is the bidirectional assertion doing exactly the job P-9 built it for. The
sentinel table now has **seven values for eight dashes**, and the repeat is recorded as a
measurement rather than a coincidence. (NFKD would merge the same two, since `U+2011` decomposes to
`U+2010`; the table states the merge anyway so the fold's verdict is its own rather than a side
effect of where it sits.)

### The fix

`QuestionValidator.foldDashes` substitutes a private-use sentinel (`U+E000..U+E006`) for each of
the eight, **before NFKD**. The same technique the supplementary-plane fold already uses, aimed the
other way: there the collation is blunter than Java and the fold makes Java blunter to match; here
Java is blunter than the collation and the fold gives each character a body the collator will
weigh. The sentinels were checked on six properties rather than assumed — non-ignorable at PRIMARY
(so count and position survive, which MySQL also requires: `a` ≠ `a-`, `a-` ≠ `a--`), mutually
distinct, distinct from the astral `U+FFFD`, and unchanged by NFKD, mark-stripping and the case
round trip.

Raising the collator's strength was rejected and the reason is recorded in the javadoc: SECONDARY
or TERTIARY is not the same dial — it would stop folding case, accents and niqqud, which the
collation genuinely does fold, so it would fix one divergence by creating four.

**The class javadoc's "stricter is the safe direction" paragraph was corrected in place rather than
deleted**, because the reasoning is the kind that looks sound until somebody measures it. The
invariant is now stated as agreement in **both** directions.

### The proof

- **12 new `@CsvSource` rows** in `BankRoundTripIntegrationTest$ValidatorAgreesWithTheConstraint`:
  the six sign-differing shapes the five seeded questions actually hold, two count-and-position
  rows (`a`/`a-`, `a-`/`a--`), and two dash-versus-dash rows including the `U+2010`/`U+2011` pair
  that pins the equivalence classes. The assertion is symmetric, so looser *and* stricter fail.
- **A five-case parameterised round trip** proving the blocked questions became editable: for each
  of `11005, 11006, 11008, 12005, 12007`, their verbatim seeded answers go through
  `QUESTION_CREATE`, then `QUESTION_UPDATE` with **only the stem changed and the answers sent back
  unaltered** — case 2.4's exact step. Both are asserted, plus a re-read showing the signs survive
  `a1..a4` and that the edit wrote v2.
- `BankRoundTripIntegrationTest` + `QuestionValidatorTest`: **91 tests, 0 failures**, 30 of them in
  the agreement nest.

---

## FIX 2 — B-10, the seed windows (Medium)

`ExecutionsSection` resolved the "scheduled today" execution (`5164`) with
`SeedTimes.dayOffsetAt(0, 14, 0)` — 14:00 UTC **on the anchor's date**. Loaded any afternoon that
is a window which has already closed, stored on a row whose status is `SCHEDULED`, and one
`ReleaseScheduler` tick drives it `SCHEDULED → LIVE → CLOSED` in a single pass.

**The fix distinguishes two meanings of "relative to load", which the seed had been conflating.**
Executions 1 and 2 are historical — a wall-clock hour on a past date is the right shape for them
and they keep `dayOffsetAt`. Executions 3 and 4 are the two the demo needs to be *happening*, and
they now resolve from the anchor **instant** through `fromNow`:

| # | code | was | now | status |
|---|---|---|---|---|
| 1 | `4821` | T−14d 09:00 → 11:00 | unchanged | CLOSED |
| 2 | `7390` | T−3d 10:00 → 11:30 | unchanged | CLOSED |
| 3 | `5164` | **T+0 14:00 → 16:00** | **now+3h → now+5h** | SCHEDULED |
| 4 | `2075` | T−1h → T+1h | **now−30m → now+90m** | LIVE |

Three hours rather than one, so a demo that starts late still finds the sitting scheduled, and far
enough past execution 4's close (+90m) that the two never overlap and the release list keeps
showing one LIVE row beside one SCHEDULED row — which is what cases 5.5 and 5.6 read.

The record now carries one nullable `opensFromNow` instead of a `liveAroundNow` boolean, so
`closesAt` lost its branch entirely and is one expression for all four: *its own length after it
opened*. The live window's length used to be a consequence of two independent `fromNow` calls, so
the existing shape assertion was checking arithmetic rather than a decision.

### The tripwire that was missing

`SeedLoadedDbContract.executionWindowsHaveTheRightShape` asserted **durations** — 120, 90, 120, 120
— and every one of them was still correct while the fixture described the past. The direction is
the property, and it is now asserted:

```
a SCHEDULED sitting has to open in the future, whatever hour the seed is loaded at — B-10
execution 3 opens after execution 4 has closed
```

**`SeedLoadedTestBase`'s 15:30Z anchor deliberately stays where it is.** It used to sit *inside*
the old 14:00–16:00 window, which is why the canonical seed test loaded an already-open "scheduled"
sitting and noticed nothing. Moving it to a morning would have hidden the defect; the windows are
now anchor-relative so the fixture is correct at any hour, and the anchor stays in the afternoon so
the new guard is exercised. The javadoc says so.

`docs/seed/SEED_CONTENT.md` §9 rewritten: the table rows updated, plus a paragraph naming the two
kinds of `T` and the rule — **anything the document wants to be in the future when it is read must
be written as an offset from the load instant, never as an hour on the load date.**

**One correction to the brief.** It named `SeedArithmeticTest` as the test that must stay green.
**No such class exists** — `find src -name '*Arithmetic*'` returns nothing. The name appears only in
`SeedLoadedDbContract`'s own javadoc, which describes `SeedArithmeticTest`'s *job* (recomputing
rather than comparing) as though the class existed; that reference has been stale for some time and
is worth either writing or removing. The arithmetic assertions actually live in
`SeedDatasetContract`. **Both are green** — numbers did not move, only times.

Seed suite after the change: **169 tests, 0 failures** across H2 and MySQL leaves.

---

## FIX 3 — B-11, the bell (Medium)

`NOTIFICATIONS_GET` answered `INTERNAL` for every staff account the seed gives a notification to.
Two independent halves, both fixed.

### (a) The seed spoke a vocabulary the enum did not have

Six of eight seeded type strings were not `NotificationType` constants. **They were two different
mistakes and are fixed two different ways:**

| Seeded string | Recipient | Now | How |
|---|---|---|---|
| `EXAM_REJECTED` | dana.cohen | `APPROVAL_REJECTED` | spelling |
| `EXAM_PENDING` | dana.cohen | `APPROVAL_REQUESTED` | spelling — the type names the *event*, not its audience |
| `APPROVAL_REQUEST` | rina.barak | `APPROVAL_REQUESTED` | spelling (missing past tense) |
| `EXAM_REJECTED` | tamar.shani | `APPROVAL_REJECTED` | spelling |
| `GRADE_PUBLISHED` | noa.friedman | unchanged | was already correct |
| `GRADE_PUBLISHED` | yael.azulay | unchanged | was already correct |
| `GRADING_DUE` | avi.mizrahi | `GRADING_DUE` | **constant added** — see the deviation note |
| `EXECUTION_CLOSED` | principal.avia | `EXECUTION_CLOSED` | **constant added** |

The seed record now holds the **enum**, not a string, and calls `name()` at the last moment — the
same call `JpaNotificationStore.save` makes, so seeded rows and service-written rows are one shape.
A string type column is a place for a typo to live; the compiler now refuses one. `SEED_CONTENT.md`
§11's type column updated to match, since `SeedLoadedDbContract` compares the two.

**The two new constants also needed sentences, and that requirement is the sharpest statement of
the whole bug.** `NotificationCatalogTest.everyTypeHasASentence` asserts every `NotificationType`
has a `NotificationCatalog` factory — the invariant that a type nothing can compose is a type
nothing can send. It failed on the first full verify, which is how it surfaced. `gradingDue` and
`executionClosed` were added, parameterised from seed §11's own already-reviewed copy, along with
`ROUTE_GRADING` and `ROUTE_RESULTS` and their `NotificationPresenter` deep-link entries so both are
clickable. **The seed had been writing notifications the catalog said were impossible to send** —
which is B-11 restated in one line, and the reason the fix belongs on both sides rather than only
in the read path.

### (b) One bad row took the whole page down

`toDto` was a bare `NotificationType.valueOf`, and an `IllegalArgumentException` from inside a
`map` aborts the stream: no page at all, `INTERNAL` at the handler, and a bell that does not open.
`toDtoOrSkip` now skips the row and logs at **ERROR** with the row id and the offending string.

Two decisions recorded on the method:

- **ERROR, not WARN.** An unknown stored type means either a constant was renamed — which the enum
  forbids precisely so this cannot happen — or something wrote the column without going through
  `save`. Both are bugs elsewhere, and this line is the only place they surface.
- **The unread badge deliberately still counts the skipped row.** `unreadCount` counts in SQL and
  never parses a type. A badge of 3 over a list of 2 is a visible symptom; quietly adjusting the
  number to agree with a short page would hide the next occurrence, which is the failure this whole
  fix is about.

### The two tripwires

- `JpaNotificationStoreContract.anUnknownStoredTypeDoesNotTakeThePageDown` — a hostile type string
  written with a **raw insert**, because going through `save` is impossible by construction (it
  takes the enum), which is exactly why no existing test could see this. Asserts the page survives
  minus that row and that the badge still counts it. Runs on both H2 and MySQL.
- `SeedLoadedDbContract.everySeededNotificationTypeParses` — asks the **enum**, not the document.
  `notificationsMatch` compared the loaded type against the document's type and both said
  `EXAM_REJECTED`: they agreed with each other and with nothing else, which is this class's own
  stated failure mode reappearing in a column whose vocabulary lives in a third place.

Notify + seed + presenter suites after the change: **165 tests, 0 failures**.

---

## RIDER 4 — the generation-counter sweep

Every client session class was audited for the missing-generation-guard shape: **an entry point
that takes a target id, which can be called again with a different id while the first request is in
flight, and a settle that adopts the answer without checking which target it was for.**

### Verdicts, all 26 sessions

| Session | Entry point | Verdict |
|---|---|---|
| `ApprovalQueueSession` | `load()` | **N/A** — no target id; re-entrancy already guarded |
| `ExamPreviewSession` | `open(examVersionId)` | **DEFECT — fixed** |
| `BankSession` | `select(displayId5)`, `reload()` | **Guarded** — `listGeneration` / `detailGeneration`, the reference implementation |
| `QuestionEditorSession` | — | **N/A** — no id-addressed fetch; `save()` settles its own write |
| `BotAnalyticsSession` | `load(courseCode)` | **N/A** — returns a future, adopts nothing into shared state |
| `BotChatSession` | `reopen(sessionId)` | **DEFECT — fixed** |
| `BotHistorySession` | `load(courseCode)` | **N/A** — returns a future, no adoption |
| `BotManagerSession` | action verbs | **N/A** — no id-addressed read |
| `DataSession` | `selectTab(tab)` | **Guarded** — settle is keyed by `asked` tab; each tab loads at most once |
| `ExamAttemptSession` | `start(executionId, form)` | **N/A** — one attempt lifecycle, not re-openable to a second target |
| `ExamEntrySession` | `setCode` → join → start | **N/A** — a sequential flow, not an addressed read |
| `ExecutionMonitorSession` | `start(executionId)` | **DEFECT — fixed** |
| `ExamListSession` | `load()`, `reload()` | **Guarded** — `listGeneration`. `select(examId)` dispatches nothing |
| `GradingQueueSession` | `openExecution(summary)` | **DEFECT — fixed** |
| `CoordinatorDashboardSession` | `load()` | **N/A** — no target id |
| `PrincipalDashboardSession` | `load()` | **N/A** — two whole-list verbs, each with its own settle |
| `StudentDashboardSession` | `load()` | **N/A** — no target id |
| `StudentHomeSession` | `setCode` | **N/A** — local state only |
| `TeacherDashboardSession` | `load()` | **N/A** — fixed set of whole-list verbs, no caller-chosen id |
| `LoginSession` | `submit()` | **N/A** — no target id |
| `NotificationsSession` | `start(unread)` | **N/A** — no target id |
| `ReleaseManagerSession` | `refresh()`, action verbs | **N/A** — whole-list read; actions carry their own id and settle by refresh |
| `ReportsSession` | `selectDimension`, `selectSubject` | **Guarded** — by identity (`asked` dimension + subject id), both directions |
| `CheckedFormSession` | `open(gradeId)` | **DEFECT — fixed** |
| `MyGradesSession` | `load()` | **N/A** — no target id |
| `TeacherResultsSession` | `openExecution(row)` | **Guarded** — by identity (`asked` execution id) |

**Five confirmed defects, five fixed. Nothing sound was refactored.** `ReportsSession` and
`TeacherResultsSession` guard by *identity* rather than by a counter; that is equivalent and in
places clearer, and they were left exactly as they are.

### The two shapes of the defect, because they are not the same bug

**① The guard was scoped to the state instead of the target** — `ExamPreviewSession`,
`CheckedFormSession`. Both read `if (state == LOADING) return`. That is correct for what it was
written for: a double-click on the same row must not send two requests. It is wrong for what it
also caught. Both views are built **once** and call `open(id)` from `onShow`, which runs on
**every** navigation. So a coordinator who opened version A, went back to the queue and opened
version B before A answered had **her request for B silently dropped**, and then watched A paint
itself onto the screen she had asked for B on — with Approve and Reject live against A's id and A's
`lockVersion`. Approving the wrong exam is one click away and nothing on screen says so. This is
the 4.1 defect generalised, and it is worse than a plain race because the new request never travels
at all.

**Fix:** the re-entrancy guard is now scoped to the target (`state == LOADING && id ==
requestedId`), so a *different* id always travels and a *repeated* id still does not; the settle
discards any answer that is not about the currently requested id. The existing
`concurrentOpenIsIgnored` test — which opens the **same** id twice — still passes unchanged, which
is the evidence that the sound half of the old guard was kept.

**② There was no guard at all** — `GradingQueueSession`, `ExecutionMonitorSession`,
`BotChatSession`.

- `GradingQueueSession.openExecution` is wired to the queue list's **selection** listener, so
  holding the arrow key down the rail fires one request per row and every one is in flight at once.
  Whichever the network delivered last became the open sitting: the teacher reads one sitting's
  papers under another sitting's row, and the grade ids she then approves are internally consistent
  and belong to the wrong exam.
- `ExecutionMonitorSession` is the sharpest one, because **half of it was already right**: the
  *push* path has always checked `pushed.executionId()` against the field, and the *request* path
  did not. A snapshot for the sitting she left could repaint the sitting she opened — wrong
  students, wrong counts, wrong countdowns. The two paths now apply the same rule, threaded as an
  `asked` argument through `apply` so the error branches are covered too, not only `adopt`.
- `BotChatSession.reopen` adopts a transcript into the shared model with no check; `BotChatView`
  calls it from `onShow` on every navigation carrying a session parameter.

### Tests

One per fix, all in the house idiom — no responder, so both futures stay pending, then
`connection.deliver(Message.ok(sentMessages().get(i), …))` delivers the answers in the order the
*network* chose rather than the order the clicks did, newest first:

| Test | Asserts |
|---|---|
| `ApprovalSessionTest$Preview.aLateAnswerForAnotherVersionIsDropped` | the request for B travels (`sentCount == 2`) **and** A's late answer loses |
| `CheckedFormSessionTest.aLateAnswerForAnotherGradeIsDropped` | same two halves, on grade ids |
| `GradingQueueSessionTest$Opening.aLateAnswerForAnotherSittingIsDropped` | the open sitting is the one the rail selected |
| `ExecutionMonitorSessionTest$Opening.aLateSnapshotForAnotherExecutionIsDropped` | the request path applies the push path's rule |
| `BotChatSessionTest.aLateTranscriptForAnotherConversationIsDropped` | the model holds the conversation she asked for |

Client session suites after the change: **117 + 9 + 24 + 21 + 17** green, no regressions.

---

## FIX 5 — B-12, the refusal copy (Low)

`Authorization.describe` built the multi-role branch with `Arrays.toString(allowed)`, so a student
who reached any staff verb was told:

> This action requires one of the roles **[TEACHER, COORDINATOR]**.

Square brackets and enum constants in a sentence a user reads — PRD §4.1's "never an error code"
rule, and what case 21.3 looks for. It now reads:

> This action requires the TEACHER or COORDINATOR role.

Commas until the last join, which is "or", and the same shape as the single-role branch it used to
diverge from. Role names stay upper case deliberately: they are the words `DEMO_ACCOUNTS.md` and
every screen use, so lower-casing them here would introduce a second vocabulary for one thing. The
now-unused `java.util.Arrays` import is gone.

**No test pinned the old string**, so the brief's "update the tests pinning the old string" had
nothing to update — and that absence is itself the finding. The two existing assertions checked
that the message *contained* "TEACHER" and "PRINCIPAL", which the array literal also did, so the
defect was invisible to a green suite. `AuthorizationTest.theMultiRoleRefusalReadsAsEnglish` now
pins the sentence in full for two, three and one role **and** asserts no bracket appears in it —
that second half is what would have failed before.

**The second half of the original finding is not fixed and is not this batch's.** The single-role
branch is *wrong* on `EXAM_PREVIEW_GET`, which admits `TEACHER` **and** `COORDINATOR` and refuses
on *subject*: a plain teacher who is not the author is told she needs a role, when the author — a
plain teacher — may open it perfectly well. That is a change to which sentence a verb answers with,
not to how a list is rendered, and it belongs with whoever owns the approval contract's copy.
Recorded in the B-12 register entry.

---

## FOLD-IN 6 — `docs/ACCEPTANCE_TESTS.md`

- **All 29 `Actual` cells** for scenarios 2–5 folded in from the two reports, each with a `Status`
  and a `Bugs found` reference under the renumbered B-references. No case row left `⬜` except 5.4,
  which is UI-only by nature.
- **Summary rows 2–5** rewritten from `⬜`.
- **Seven register entries, B-7 … B-13**, in the register's own five-column format:
  B-7 / B-10 / B-11 / B-12 marked **Fixed by this batch**; **B-8 Open** with the ticket named (the
  ten seed images, deliberately not attempted — it is content, and it belongs to the seed's owner);
  **B-9 Fixed** by rewriting the case; **B-13 Open (Low)**, likewise left to the content owner.
- **Case 3.4's own text rewritten** (B-9): it now asks for *"4 questions from topic `Linear
  equations`, mixed difficulty"* — the report's proposed replacement that keeps the case's
  "from a topic" shape and is satisfiable on this seed. Its Expected cell gained the
  "nothing is written until she saves" clause the probe evidenced.
- **Case 2.8's Expected cell reworded per the ruling**: it now claims that *the store* preserves the
  version rows and states outright that `QUESTION_VERSIONS` answering `NOT_FOUND` for a
  soft-deleted question is **contract-correct** — §6 folds unknown, deleted and out-of-scope into
  one indistinguishable answer so display ids cannot be probed. The old wording could be read as
  promising a staff-only history read, which would be a contract change rather than a bug.
- **Case 5.3's Expected cell** gained a sentence saying `4821` is accepted and why, so a reader
  cannot mistake the acceptance for a miss — the report flagged this for the lead.
- **Both reports copied into `docs/reports/lead/`** so the evidence travels with the table (see the
  deviation note at the top about how).

Table integrity checked mechanically after the fold: every scenario row is exactly 6 columns, and
no cell text introduced a stray `|`.

---

## PROBLEMS.md — P-12

**Added, and the argument against is stated in the entry itself.** P-9 already covers this
validator drifting from the collation, and B-7 could be read as its fourth addendum. It is its own
entry because **P-9's solution paragraph is what failed here, not its problem**: P-9 closed by
arguing that being *stricter* than the database is the safe direction, that sentence was written
into the class javadoc as a design principle, and it is false. Recording the falsification
underneath the entry that asserted it would bury the one thing a reader needs.

P-12 carries the two measurement sweeps, the `U+2010`/`U+2011` exception that broke the first draft,
and the generalisation: **a one-directional promise deserves a two-directional test, and an
argument for why one direction is harmless is a hypothesis, not a licence.** The bidirectional
assertion existed and was green throughout — the gap was in the *data*, and it is the data an
author chooses by enumerating cases he can think of.

---

## Verify

```
export JAVA_HOME=<jdk21>
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_afix ./mvnw -B clean verify
```

**`BUILD SUCCESS` — 6312 tests, 0 failures, 0 errors, 0 skipped, 16:38 min.** Finished
2026-08-26T14:18Z+03. `DeepSeekProviderTest` did not flake; no rerun was needed.

**The first full verify was RED, with two failures, and both are worth recording because neither
targeted run could see them** — the fixes were validated class-by-class throughout, and it took the
whole suite to find what they had broken:

| Failure | What it caught |
|---|---|
| `QuestionEditorSessionTest$Duplicates.refusesWhatTheServerWould[8]` | the `co-op`/`coop` row — a **client** test pinning the server-side behaviour B-7 reverses. It is what led to `BANK_WIRE_CONTRACT.md` §5 and the discovery that the 2026-08-22 ruling existed at all. Without it the fix would have shipped silently contradicting a FROZEN contract. |
| `NotificationCatalogTest.everyTypeHasASentence` | the two new enum constants had no catalog sentence. A type nothing can compose is a type nothing can send — which is B-11 restated, and the reason the constants also needed factories. |

Both are the kind of failure worth having: each one was a test defending an invariant a targeted
run could not see, and each changed what the fix had to include rather than merely how it was
written.

## Files touched

**Production**

| File | Fix |
|---|---|
| `src/main/java/server/features/bank/QuestionValidator.java` | B-7: dash fold, sentinel tables, corrected javadoc |
| `src/main/java/server/db/seed/ExecutionsSection.java` | B-10: load-time-relative windows |
| `src/main/java/server/db/seed/NotificationsSection.java` | B-11a: enum-typed seed rows |
| `src/main/java/common/dto/notify/NotificationType.java` | B-11a: `GRADING_DUE`, `EXECUTION_CLOSED` |
| `src/main/java/server/features/notify/NotificationCatalog.java` | B-11a: `gradingDue`, `executionClosed`, two route ids |
| `src/main/java/client/features/notify/NotificationPresenter.java` | B-11a: icons for the two new constants, two deep-link entries |
| `src/main/java/server/features/bank/BankMessages.java` | B-7: the duplicate sentence no longer promises hyphens |
| `src/main/java/server/features/notify/JpaNotificationStore.java` | B-11b: skip-and-log read path |
| `src/main/java/server/core/Authorization.java` | B-12: the refusal sentence |
| `src/main/java/client/features/approval/ExamPreviewSession.java` | Rider 4 |
| `src/main/java/client/features/results/CheckedFormSession.java` | Rider 4 |
| `src/main/java/client/features/grading/GradingQueueSession.java` | Rider 4 |
| `src/main/java/client/features/exam/ExecutionMonitorSession.java` | Rider 4 |
| `src/main/java/client/features/bot/BotChatSession.java` | Rider 4 |

**Tests**

`BankRoundTripIntegrationTest`, `SeedLoadedDbContract`, `SeedLoadedTestBase`,
`JpaNotificationStoreContract`, `NotifyDtoTest`, `NotificationCatalogTest`, `AuthorizationTest`,
`ApprovalSessionTest`, `CheckedFormSessionTest`, `GradingQueueSessionTest`,
`ExecutionMonitorSessionTest`, `BotChatSessionTest`, `QuestionEditorSessionTest`.

**Docs**

`docs/ACCEPTANCE_TESTS.md`, `docs/PROBLEMS.md`, `docs/seed/SEED_CONTENT.md`,
`docs/contracts/BANK_WIRE_CONTRACT.md` (**amendment A2** — see deviation 2; it landed labelled A1 and was renumbered in batch A, 2026-08-26, because A1 was already the `latestVersionId` amendment),
`docs/reports/lead/ACCEPT-S2-S3.md`, `docs/reports/lead/ACCEPT-S4-S5.md`, this file.

**Not committed** — the tree is left staged for the lead's review, per the batch brief.
