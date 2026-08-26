# BATCH B — the four ruled fixes from the acceptance campaign

**Run:** 2026-08-26 · **Branch:** `main` · **Nothing committed by this batch.**
**Inputs:** the B-register of `docs/ACCEPTANCE_TESTS.md` (B-14, B-16, B-20, B-21) and the walks
behind it — `ACCEPT-S6-S7.md` (B-14's full anatomy), `ACCEPT-S10-S12.md` (B-16),
`ACCEPT-S13-S14.md` (B-20, B-21).
**Gate:** `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchb ./mvnw -B clean verify`

Four fixes. One of them — B-14 — is a rule that was missing rather than wrong, which is why no
test in the project could have failed for it; the other three are a field read and dropped, a
promise the client never remembered, and a verb the PRD asked for that nobody built.

**Contract amendments in this batch:** `EXAM_WIRE_CONTRACT` **A8** · `GRADING_WIRE_CONTRACT`
**A5** (with a dated cross-reference in `RESULTS_WIRE_CONTRACT`) · `BOT_WIRE_CONTRACT` **A1**.
All three are additive under their contracts' own rules: no verb renamed, no component removed
or reordered, no existing field's meaning changed, and every superseded constructor retained.

---

## B-14 — window and duration honesty

**The defect, in one line:** two clocks governed a sitting and nothing compared them, so a
student could be promised seventy-five minutes, given two, and told neither.

The **entry window** (`open_at` … `close_at + extra_minutes`) decided who could join. The
**attempt deadline** was `started_at + duration + extra_minutes`, derived per attempt. A student
who joined legally late got a deadline past the window's close; `ReleaseScheduler` then closed
the execution at the window's end and force-submitted her `TIMED_OUT`, with her own
server-authoritative countdown still running. **The force-submit was always right** — the S6-S7
probe proved the bell fires — so the fix is not to the ending. It is that everything the client
is told now agrees with it.

### 1. One derivation point, and the `min` is explicit in it

The exam feature derives and never stores, which is what makes E11.4 work: an extension granted
while a student is offline applies the moment she resumes, with nothing to migrate. That design
is untouched. What changed is **where the arithmetic lives and what it knows about**.

| Before | After |
|---|---|
| `AttemptRecord.deadline(int allottedMinutes)` — `startedAt + allotted`. Every caller passed `ctx.allottedMinutes()`; the signature had no way to express the window at all | `ExecutionContext.deadlineFor(Instant startedAt)` — **`min(startedAt + allotted, effectiveCloseAt())`**. `AttemptRecord.deadline(ExecutionContext)` delegates to it |
| `MonitorService.toWire` added the allotted minutes itself, in a second copy of the arithmetic that did not know about the window | the same `deadlineFor`, so the header's `closesAt` and the rows beneath it cannot part company |

Taking the whole `ExecutionContext` rather than a minute count is the fix rather than a
convenience: a caller can no longer derive a deadline without the window being in the room.
Eleven call sites moved — countdown, timer arm, late-answer check, force-submit, re-arm at boot,
extension re-arm, monitor rows — and each now inherits the reconciliation instead of deciding
for itself whether the window matters.

**The monitor inconsistency the S6-S7 probe recorded is gone with it.** During an extension its
`closesAt` read `10:15:00Z` while the rows counted down to `10:30:00Z` — the teacher's own
screen disagreeing with itself, with the wrong half winning.

### 2. Entry honesty — she is told, before the clock starts

`ExamHeader` gains two components, appended last (**A8**):

```
… AttemptState attemptState, Instant windowClosesAt, int sittingMinutes)
```

`durationMinutes` is unchanged in meaning — the paper's length with extensions, which is what a
student may well be comparing against what her teacher said. `sittingMinutes` is what *this*
sitting can actually deliver. Both, because the difference between them is the thing she has to
be told; a lone corrected number is a mystery. They are measured from the instant the clock
starts — `now` on the join answer, the attempt's own `startedAt` once it is running — so a
header read before and after a start describes the same sitting rather than drifting a minute
per request.

**No new verb**, per the ruling: it rides the existing `EXAM_JOIN` / `ATTEMPT_START` /
`ATTEMPT_RESUME` payloads. The eight-argument constructor is retained and delegates with `null`
and `sittingMinutes = durationMinutes`, which is exactly what a pre-amendment caller meant, so
every existing construction site keeps compiling and keeps meaning what it meant.

**The sentence**, in `ExamCopy.sittingShortened`, rendered on the identity card of
`ExamEntryView` and only when `ExamHeader.isSittingShortened()`:

> **This sitting closes at 13:00. You have 26 minutes.**

Sentence case, no em dash, two facts and no hedging, in her own zone. It is hidden entirely
rather than blanked in the normal case: a reassuring "you have all 75 minutes" on every entry
would make the sentence that matters just another line she has learned to skip.

### 3. `EXECUTION_EXTEND` moves the bell out of the way

The extension path was the worse half, because delivering minutes is the whole point of the
verb: `dana` granted `+15`, the toast announced them, the deadlines moved to `11:00:00Z`, and
the scheduler closed the execution at `10:15:00Z` with `actual_minutes 45 of an allotted 90`.

The verb now writes two numbers **in one transaction**: `extra_minutes`, as before, and —
only when the window is genuinely in the way — `close_at`, moved to
**`max(current close, the latest new deadline)`**.

- **A max and never a set.** An execution whose window already outlasts every new deadline keeps
  the window its teacher released it with.
- **Only ever widened.** Shortening a sitting is `RELEASE_CLOSE_EARLY`'s verb with its own rules
  about the students inside it; `ExecutionRepository.moveCloseAt` refuses a `closeAt` that is not
  later than the stored one, and the in-memory fixture refuses it too so a test cannot pass on a
  shape the real repository will not write.
- **The latest deadline, not each student's own**, because there is one window for the room: the
  last student to have started is the one it has to outlast.
- **Same transaction, same `lock_version` generation**, so two teachers extending at once still
  produce one `CONFLICT` rather than a half-applied grant — and a window that failed to move
  cannot leave minutes granted that nobody can take.
- The monitor push that follows re-reads, so the watching teacher's screen shows the moved close
  without her sending a second request.

Documented where `EXECUTION_EXTEND` is documented: the verb-semantics section of
`EXAM_WIRE_CONTRACT` carries a pointer, **A8** carries the rule, and `ExtendService`'s class
javadoc gained a section of its own beside `widenWindowFor`'s.

### 4. The seed stops reproducing it

Execution `2075` is a 75-minute paper in a window that **straddles** the anchor, so what a
student gets is whatever is left of it when she joins. Two hours looked safe and was not: thirty
minutes are already gone at load time, and a walkthrough that reaches the take-exam step forty
minutes in used to hand its student a sitting shorter than the paper.

`LIVE_WINDOW_MINUTES = 210` — **now-30m to now+3h** — so the fixture is live the instant the
load finishes (the S-2 proof needs that) and outlasts the paper comfortably for any plausible
defence slot. `SCHEDULED_OPENS` moved **3h → 4h** with it: `SeedLoadedDbContract` asserts that
execution 3 opens strictly *after* execution 4 has closed, and two windows meeting at an instant
is one clock skew away from a release list showing something nobody designed. The assertion
tightened rather than loosened.

**The code fix stands on its own** — a truncated sitting is now disclosed at entry and an
extension widens the window it needs — and the seed was still worth widening, because a fixture
that only ever exercises the sad path is one nobody can demonstrate the happy one from.

---

## B-16 — the teacher's results table says how the paper ended, and how long it took

T-10.2 asks for "score, submitted vs timed out, solving time"; only the score reached the wire.
A shape fact rather than a null — `StudentGradeRow` had twelve components and none was either —
and **the data was already being read and thrown away**: `GradeRepository.findResultRows` has
selected `a.actualMinutes` since E14, and `TeacherResultsService.toWire` mapped ten components
and dropped that one. `actualMinutes` had no reader anywhere on the E14 path.

**Additive, in four places:**

| Layer | Change |
|---|---|
| `StudentResultRow` | an eleventh component, `AttemptStatus attemptStatus` |
| `GradeRepository.findResultRows` | `a.status` joins `a.actualMinutes` in the select |
| `StudentGradeRow` | **v1.2**: `AttemptState attemptStatus`, `Integer actualMinutes`, appended last; `serialVersionUID` 2 → 3; the twelve-component constructor retained and delegating with both null; `withoutJustification()` and `withExam()` carry them through |
| `TeacherResultsService.toWire` | maps both, through an exhaustive `AttemptStatus → AttemptState` switch |

**The same wire enum and the same typing the student-side checked form already ships.**
`CheckedForm` has carried `AttemptState attemptStatus` and a boxed `Integer actualMinutes` since
the 2026-08-22 amendment; carrying them differently here would be two answers to one question.
`actualMinutes` stays boxed because "not recorded" is a different fact from "took zero minutes".

**Populated on the teacher results path only.** Null everywhere else and honest there: the
grading queue, the review header and both student containers are about grades, and
`findResultRows` is the only read that joins the attempt. `CheckedForm` keeps its own pair — the
2026-08-22 ruling was about the *student* wire and it stands; this is a different screen asking a
different question, and it is the screen T-10.2 names.

**Two columns**, *Attempt* and *Time*, content-sized (`columnWidths` re-balanced from six columns
to eight). Both render **words** — "Submitted", "Timed out", "43 min" — never a tint alone, per
the B-5 / wave rule: a colour survives neither a printout, a screenshot, nor a colour-blind
reader. The ordinary case says "Submitted" rather than staying blank, because a column whose only
content is the exception reads as data that failed to load; a missing time says "Not recorded"
for the same reason.

**Contract:** `GRADING_WIRE_CONTRACT` owns `StudentGradeRow`, so the amendment is **A5** there,
with a dated cross-reference at the `ExecutionResults.rows` bullet in `RESULTS_WIRE_CONTRACT`
naming this as the one path that populates the new components. No wire-shape guard pins the
record's component count; `WireDtoLeakGuardTest`'s licence list is unaffected, because neither
component is correctness-shaped and neither needed a licence.

---

## B-20 — the C-4 notice, once per attempt

ADR-018 and `BotMessages.integrityNotice`'s own javadoc describe a notice shown **once per
attempt**, and the server keeps that promise for the two things that matter: the integrity flag
keeps its first timestamp and the executing teacher is notified exactly once. It could not keep
it for the *prompt*, because the prompt is decided from one request field and nobody remembered
the answer — `BotChatSession.ask(String)` hardcoded `acknowledged=false` and
`BotChatModel.acknowledged()` cleared the held state without recording that she had agreed. A
student who confirmed once got the same confirmation dialog on every message for the rest of the
exam.

**The server is untouched**, per the ruling. It must keep deciding C-4 from its own live registry
and must not learn to trust a client's flag any further than it already does: the same-course
lockout still cannot be lifted from any payload field.

**Client-side, in two classes:**

- `BotChatModel` records the acknowledgement as the **`BotIntegrityNotice` she consented to**,
  not a bare boolean and not a timestamp — so what she agreed to is recoverable, and a test can
  assert she consented to *this* rather than that some flag is set. `hasAcknowledged()` is what
  the session reads.
- `BotChatSession.ask(String)` sends `model.hasAcknowledged()` instead of `false`, and passes the
  whole notice to `needsAcknowledgement` rather than only its sentence.
- **A notice is never swallowed.** `needsAcknowledgement` discards any consent it is holding
  before showing the new one: the server only asks when it has decided this ask needs asking
  about, so an arriving notice is evidence that the sitting it describes is not the one she
  already agreed to. A new attempt notices again rather than being waved through on the strength
  of an older confirmation.
- Consent is dropped by every transition that ends the situation it belonged to — `blocked`
  (which includes the same-course lockout, i.e. she has started sitting *this* course's exam),
  `load`, `startFresh` — and by `declined`, because "no" has to survive one message if it means
  anything.

**The honest limit, stated rather than buried** (and in the class javadoc, not only here): the
client has no attempt identity to key on. `BotIntegrityNotice` carries a course name and a
sentence, and nothing that distinguishes one sitting from the next. Giving it one would be a
server and wire change, which B-20's ruling excludes. So the consent is scoped to the chat
session — which is what the register's own prescribed fix says — and the "new attempt" boundary
is observed from the server re-issuing the notice. Erring towards asking again is deliberate: an
extra confirmation costs one click, and a stale one would let a report reach a teacher without
the student having been told it would.

---

## B-21 — a bot source can be edited (F12.3)

PRD **F12.3** specifies "Sources list with add/**edit**/remove for any teacher of the course;
edit-locked (F10)", and case 13.4 asks for it. Nothing implemented the middle verb anywhere in
the stack. Correcting a typo meant deleting the row and re-adding it, which loses the source id,
its author, its `updated_at` and its version — **and loses them silently**, because the remove
notifies co-teachers as a removal and the re-add as an addition, so one correction reads to a
colleague as two unrelated events. Ruled: build it.

**The verb.** `BOT_SOURCE_UPDATE`, in the `BOT` group, javadoc in the house voice, registered on
`BotAdminService.registerOn` (six teacher verbs became seven). Request
`SourceUpdateRequest(courseCode, sourceId, kind, title, content)` — the add request's shape plus
the row it addresses, deliberately its own record rather than a nullable-id variant, because an
add creates and an update replaces and one record for both is how a handler ends up doing the
wrong one. Response is a whole `BotManagerPage`, like every other mutating teacher verb.

**The write.** `BotData.updateSource` → `BotSource.setTitle` + `replaceContent`, which bumps the
domain version. The row keeps its id and its `added_by`; that is the entire difference from
remove-and-re-add, and the in-memory fixture is faithful about both so a test cannot pass on a
delete-and-insert wearing an edit's clothes. `BotSource` gained a `setType` so an edit may change
what kind of material a row holds without the row changing identity — always set together with
`replaceContent`, never alone.

**Parse before write**, exactly as the add path does. Extraction happens outside the transaction
and before it, so a replacement that cannot be read answers `VALIDATION` with the extractor's own
sentence and leaves the stored source exactly as it was rather than half-overwritten.

**The lock, in the E6.14 shape.** Scope first, then the lock consult, then the row's existence —
and the consult is **inside** the transaction. `BOT_SOURCE_REMOVE` consults before its
transaction, which is a shape that predates the ruling and is left alone; the write path
everywhere else consults after the scope check, so a `CONFLICT` cannot become a way for an
outsider to learn that a source exists and who is holding it. There is no version or staleness
check on this row to sit below it, so "after scope" is where it lands.

**The refusal names its holder.** `BotAdminService.SourceLocks` now answers
`Optional<LockHolder>` rather than a boolean — `EditLockGuard`'s own shape — with `mayEdit`
retained as a default over it, and `HSTSServer` wires `holderOf(…).filter(not me)`.
`BotMessages.sourceLockedBy(name)`: *"Avi Mizrahi is editing this source right now. Wait for them
to finish, or take over the edit from the banner."*, falling back to the existing
`SOURCE_LOCKED` when the lock service cannot say who. The caller has passed the scope check by
then, so the name gives away nothing she cannot see on the page, and it turns a wall into a
colleague she can go and ask. **This is also the first thing on this screen the
`EntityRef.BOT_SOURCE` lock has ever had an actual *editor* to protect** — case 13.6's
outstanding half, and F10.2's "read-only view while another teacher edits".

**Co-teachers are told**, through the same `BOT_SOURCE_CHANGED` notification an add or a remove
raises, and the editor is not told about her own edit. No new notification type.

**The screen: Edit on free-text rows only, and the reasoning rather than a quiet omission.**
`BotSourceRow` gains an eighth component, `String text`, carrying the pasted body of a `TEXT`
source so the dialog opens on what is actually stored — editing a typo means seeing the typo. It
is **null for `PDF` and `DOCX`, enforced in the record's own compact constructor**, so the "no
bytes" rule is intact: a typed source is something a human wrote and can sensibly re-open, while
a file row holds the *parse*, which is hundreds of kilobytes of no use to a dialog. The body
comes from a new scalar read, `BotRepository.findTextSourceBodies`, filtered to `TEXT` **in the
query** rather than afterwards, for the same reason.

So Edit is offered on typed rows and file kinds keep Add and Remove. Editing a file source could
only ever mean choosing a replacement file, which is what the existing chooser already does and
is indistinguishable from Remove-then-Add except that it keeps the id — a real difference, and a
smaller one than an "Edit" button on a PDF row would imply. **The verb already accepts any
`BotSourceKind` and the server already handles it**, so if the affordance is wanted later only
the button is missing.

The dialog is the existing add-text `WarnConfirm`, pre-filled with the title and the body, and
the advisory lock is taken on the row before it opens.

---

## Test inventory

**New class**

| Class | What it pins |
|---|---|
| `server.features.exam.WindowAndDeadlineTest` | B-14 end to end, on `TestClock` + `ManualScheduler`, **using the S6-S7 probe's own instants** (window `08:00Z → 10:00Z`, a 75-minute paper, joins at `09:58Z` and `09:30Z`, `+15` at `09:50Z`) so the report and the test can be read side by side. Four nests: the derivation itself (the min in both directions, extensions on both sides, floored minutes); the truncated join (the join answer's honest figure, the countdown agreeing with the bell, **promised == given**, and the normal case staying silent); the extension the window would have eaten (the window moves, she is no longer timed out at the old bell, the push carries the deadline she is really held to, a wide window is left alone, an empty execution keeps its window); and the monitor's own consistency (rows land exactly on the header's `closesAt`, both halves move together, the moved window is pushed unasked) |

**Extended**

| Class | Added |
|---|---|
| `server.db.repos.ExamFlowRepositoryContract` | `deadlineIsCappedByTheWindow` — the min, `sittingMinutesFrom` and `windowShortensSittingFrom` against real MySQL and H2. The existing `deadlineIsDerived` moved to `deadline(ctx)` |
| `server.db.seed.SeedLoadedDbContract` | execution 4's window asserted at **210** minutes with the B-14 reasoning; execution 3's `T+4h` |
| `common.dto.exam.ExamDtoTest` | `ExamHeader` A8: round-trip of both new components, `isSittingShortened` in both directions, the retained v1 constructor still meaning what it meant, and the negative clamp |
| `client.features.exam.ExamCopyTest` | `sittingShortened` — the exact sentence, singular minute, the clamp, no em dash |
| `server.features.results.TeacherResultsServiceTest` | B-16: `omer.katz` (TIMED_OUT, 90 min) beside `yael.azulay` (SUBMITTED, 55 min) on the **same** machine score; every row carrying both facts; exactly one timed-out row per seed §9.1; and the v1.1 labels still null on the teacher path |
| `client.features.results.TeacherResultsSessionTest` | B-16 at session level, through `ResultsCopy.attemptStatusLabel` / `solvingTimeLabel` / `wasTimedOut` — the words, not a tint, and "Not recorded" for an absent time |
| `client.features.bot.BotChatModelTest` | B-20: confirming records consent keyed on the notice; a notice arriving again discards it; declining, blocking, loading and starting fresh all forget it |
| `client.features.bot.BotChatSessionTest` | B-20 at session level: a second ask after confirming carries `acknowledged=true` and does not re-notice; a new attempt notices again; declining leaves the next ask unacknowledged; the same-course lockout drops consent |
| `server.features.bot.BotAdminServiceTest` | B-21: editing keeps the row, its author and bumps the version; co-teachers told and the editor not; the lock refusal naming its holder; **scope checked before the lock**; another course's source id `NOT_FOUND`; a failed parse changing nothing; and `BOT_SOURCE_UPDATE` in the registration set (seven verbs) |
| `client.features.bot.BotManagerSessionTest` | B-21: the update request's shape, the bumped version arriving on the server's own page, a refusal keeping the holder's name, and `isEditable()` / `text()` per kind |
| `server.features.bot.BotMessagesTest` | `sourceLockedBy` — names the holder, says what to do next, degrades to a sentence without a name, no em dash |
| `client.features.bot.BotCopyTest` | the edit dialog's copy; the class's existing reflective scan picks the four new constants up for the em-dash, blank and whitespace rules automatically |

---

## Verify

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchb ./mvnw -B clean verify

Tests run: 6443, Failures: 0, Errors: 0, Skipped: 0
jacoco-check: All coverage checks have been met.   (648 classes, ≥ 0.90 instruction)
BUILD SUCCESS — 16:29 min, 2026-08-26T17:43+03:00
```

**Nothing skipped**, which is the number worth reading: `HSTS_REQUIRE_MYSQL=true` turns every
`@EnabledIf("MySqlAvailability#isReachable")` class from a silent skip into a failure, so the
MySQL contracts — `ExamFlowRepositoryMySqlTest` (29), `SeedLoadedDbMySqlTest` (26),
`SeedDatasetMySqlTest` (18), `SeedLoaderMySqlTest` (14) — really ran against a server.
`DeepSeekProviderTest` did not flake; no rerun was needed.

**The batch's own classes, from that run:**

| Class | Tests |
|---|---|
| `server.features.exam.WindowAndDeadlineTest` (new) | 15 — Derivation 3, TruncatedJoin 4, ExtensionEatenByTheWindow 5, MonitorConsistency 3 |
| `server.db.repos.ExamFlowRepositoryMySqlTest` | 29 (was 28) |
| `server.db.seed.SeedLoadedDbMySqlTest` / `…H2Test` | 26 each |
| `common.dto.exam.ExamDtoTest$Header` | 5 (was 3) |
| `client.features.exam.ExamCopyTest$Composed` | 9 (was 8) |
| `server.features.results.TeacherResultsServiceTest$Detail` | 11 (was 8) |
| `client.features.results.TeacherResultsSessionTest` | 31 across its nests + the flat tests |
| `client.features.bot.BotChatModelTest` | 22 (was 19) |
| `client.features.bot.BotChatSessionTest` | 21 (was 17) |
| `server.features.bot.BotAdminServiceTest$Sources` | 17 (was 11) |
| `client.features.bot.BotManagerSessionTest` | 16 (was 13) |
| `server.features.bot.BotMessagesTest` | 11 (was 10) |
| `client.features.bot.BotCopyTest` | 10 (was 9) |

---

## Register

`docs/ACCEPTANCE_TESTS.md`: **B-14, B-16, B-20 and B-21 → Fixed by batch B (2026-08-26)**, each
row's description extended with what was actually done rather than replaced, so the original
finding stays readable. The scenario-summary rows for §7, §10, §13 and §14 and the Actual cells
of cases 10.2, 13.4, 13.6 and 14.7 are annotated with the fix and its date. **Case 13.4's ❌
stands** and is marked for a re-walk: the verb is built and unit-proved, and only a walk below
the screen can turn that cell green.

### TODO lines that became true

Four were **ticked while not being true**, which is the kind of thing an acceptance walk exists
to find, and they are annotated rather than silently left:

- **E16.9** "sources **CRUD** (edit-locked)" — the *U* did not exist anywhere in the stack until
  `BOT_SOURCE_UPDATE` landed. Now true as written.
- **E16.12** "sources table (type icons, **add/edit/remove** …)" — the edit half arrived with the
  free-text Edit button; the file-kind reasoning is recorded rather than the affordance quietly
  omitted.
- **E11.1** annotated: the verb now also moves the execution's `close_at` when the window would
  eat the grant, so minutes announced are minutes delivered.
- **E10.9** annotated: the entry flow now says the one sentence it owed a student whose sitting
  the window cuts short.
- **E14.1** annotated: the rows carry attempt status and solving time, so T-10.2's second and
  third columns exist.

`docs/seed/SEED_CONTENT.md` §9's window table and its two-kinds-of-`T` note are corrected for the
widened `2075` and the moved `5164`, with the B-14 reasoning stated in the document rather than
only in the loader's javadoc.

### Still outstanding, and not claimed

- **Case 13.4's walk and 13.6's badge.** The `BOT_SOURCE_UPDATE` path is proved at service,
  handler, session and copy level and against the in-memory store; the *live badge* and the
  read-only editor remain pixels for the manual pass, exactly as 13.6 recorded them.
- **The B-14 entry sentence on screen.** `ExamCopy.sittingShortened` and
  `ExamHeader.isSittingShortened()` are tested; that the label renders where the mock-up wants it
  belongs to the manual pass, like every other pixel in this project's method.
- **B-20's residual, stated in the class javadoc as well as here.** Consent is scoped to the chat
  session because the client has no attempt identity to key on, and giving it one would be the
  server and wire change the ruling excludes.
