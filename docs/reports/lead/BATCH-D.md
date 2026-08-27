# BATCH D — the acceptance campaign's closer

**Run:** 2026-08-26 · **Branch:** `main` · **Nothing committed by this batch.**
**Gate:** `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchd ./mvnw -B clean verify`
**Input:** the two final walker reports — `ACCEPT-S15-S17.md` (`hsts-acc1`) and
`ACCEPT-S18-S21.md` (`hsts-acc2`) — plus batch C's tail: the four stale demo-script passages and
the `Icons` recommendation.

This batch does three things and it is worth naming them separately, because they answer to
different standards. It **fixes six defects** the last two walks found, with tests at the layer
each one lives in. It **folds seven scenarios into `docs/ACCEPTANCE_TESTS.md`**, which completes
the table: all 21 scenarios, 115 cases, every Actual cell filled. And it **ports the demo
documents onto `main`**, reconciled against three batches of fixes that landed while they were
being written elsewhere.

One thing it deliberately does not do: close the campaign. **Below the screen, 21 of 21 is
done. On screen, nothing is.** E21.6 stays open and its annotation now says so in those words.

---

## The shape of what the last two walks found

Scenarios 1–14 asked what the server answers. Scenarios 15–21 asked what the *deliverable* does
and whether the system is the kind of system NFR-18 to NFR-21 describe — and the defects have a
different character for it. Three of the six fixed here are **a thing that was declared, agreed
to, documented, and then never wired**:

| | declared | listened for | produced |
|---|---|---|---|
| `PUSH_GRADE_PUBLISHED` (B-32) | `Verb`, with javadoc | `MyGradesSession` | **nobody** |
| the approvals queue's subscription (B-30) | NFR-18, and the pattern exists 8 times over | — | **nothing subscribed** |
| dataset drift (B-24) | `DEMO_DAY` §3.4 warns about the symptom | — | **nothing checked** |

Every existing test passed over all three, and the reason is the same each time: **there is no
assertion that can fail for something that is absent.** `GradeApprovalServiceTest` asserted the
notification, which is sent. `ApprovalQueueSessionTest` drove `load()` and `refresh()` directly,
both of which work. `SeedDatasetContract` proved idempotency on a database this build had
seeded. That is B-14's shape, and it is now the third batch in a row to meet it.

The counter-measure this batch adds where it could: **the new tests are written so that the
absence is what fails.** `nothingIsDisabledAnywhere` was batch C's version of it; here it is
`GradingHandlersTest.pushesOnlyAfterTheCommit` (which asserts *inside* the push that the
transaction has committed), `IconsTest.everyConstantResolvesInThePack` (a scan, so a constant
added next month is covered the moment it is written), and
`SeedDatasetContract.theFingerprintMatchesAFreshlySeededDatabase` (which makes the drift check's
own expectations unable to go stale silently).

---

## 1. B-32 — `PUSH_GRADE_PUBLISHED` had no producer

**Found by:** case 18.4 · **Severity:** Medium · **Also hardening item H13.5**

`MyGradesSession.onServerPush` returns early unless the verb is `Verb.PUSH_GRADE_PUBLISHED`.
`GRADES_APPROVE`'s javadoc promised *"each approval publishes to the student through
`PUSH_GRADE_PUBLISHED` **and** a durable `GRADE_PUBLISHED` notification"*. A sweep of every
source under `src/main/java/server` for that verb returned **zero files**, against exactly one
producer each for the other six push verbs.

So half of a documented sentence was true. The student's bell rang and her grades table did not
move until she navigated — and because re-asking `MY_GRADES_GET` immediately after the approval
*does* return the new row, the data was right and only the delivery was missing, which is
precisely the shape no test could see.

### The ruling: option (a), and it is recorded here because the walker offered two

The walker drafted both: **(a)** build the missing producer, keeping the frozen contract as
written; **(b)** delete the verb and let `MyGradesSession` filter `PUSH_NOTIFICATION` on
`NotificationType.GRADE_PUBLISHED`, which is `ExamListSession`'s shape and removes a push verb
from the protocol.

**(a)**, for two reasons that are not "the contract says so".

The first is that `GRADING_WIRE_CONTRACT.md` § Push does not merely name the verb, it
**specifies the payload**: *"on approval the student's live session receives it with a
`StudentGradeRow` payload"*. Option (b) would delete a frozen line rather than implement it, and
"we deleted the verb because nothing sent it" is a worse sentence at a defence than "we sent it".

The second is that the two channels are not redundant. A `GRADE_PUBLISHED` notification is a
**bell item** — durable, one row per recipient, addressed to a person. `PUSH_GRADE_PUBLISHED` is
a **screen event** — ephemeral, addressed to whatever is open. Collapsing them would make every
open My Grades re-query on every notification of every type that student receives, and would tie
the refresh of a grades table to the existence of a notification row.

### Where it goes, and the one non-obvious decision

**The push is emitted after the transaction commits, not inside it**, and that is a departure
from `NotificationService`, which pushes inline. The reason is specific to this push:
`MyGradesSession` answers it by **re-querying** `MY_GRADES_GET` on its own connection. A push
written from inside the approval transaction could be answered by a second connection reading a
database that does not yet hold the row it is announcing. Nothing else in the product has that
property, which is why nothing else needed the ordering — and `AttemptService.afterFinalized`
already documents the same reasoning from the other direction (*"deliberately outside the
transaction that closed it"*).

So the split is:

- **`GradeApprovalService.approveAndCollect`** does the work and hands back one
  `StudentGradeRow` per **newly published** grade. `approve(…)` is retained and delegates, so
  every existing caller keeps compiling and keeps meaning what it meant — and a caller using the
  narrow form visibly sends no push, which the javadoc says.
- **`GradingHandlers.approve`** pushes them on the line after `asTeacher(…)` returns, which is
  after `Transactions.inTx` has committed.

### Three properties, each with a test

**The rows are read back rather than assembled.** `StudentGradeRow` requires the student's name
and nothing on `Grade` or `AttemptRecord` carries it, so the rows come from
`GradeRepository.findResultRows` — the read the *teacher's own results table* uses. The student
is therefore pushed exactly the numbers her teacher is looking at, rather than a second assembly
of them that could drift. One query per touched execution, not one per grade, because a bulk
approve is the normal case. `overrideReason` is passed as `null` unconditionally: the
justification is teacher and audit material, `MyGrades` and `CheckedForm` strip it structurally
in their compact constructors, and a bare push has no container to strip it — so it is never put
on.

**A re-approve publishes nothing.** Idempotence already counted it in `alreadyApproved` without
re-stamping the audit fields; now it also does not make a student's screen flicker twice.

**A failed push never fails the verb.** The approval is committed by the time `publish` runs.
The gateway logs a dead socket and returns false; an unexpected throw is caught here. A student
who missed a push has a durable notification and a screen that loads on open; a teacher told her
approval failed has been lied to.

### Files and tests

| File | Change |
|---|---|
| `server/features/grading/GradeApprovalService.java` | `Approval` record; `approveAndCollect`; `publishedRows`; the Facade line (B-34); `Published` now carries the grade id and its execution |
| `server/features/grading/GradingHandlers.java` | takes a `PushGateway`; `approve` pushes after the commit; `publish(…)` |
| `server/core/HSTSServer.java` | `registerGradingFeature` takes the gateway and passes it |

| Test | What it holds down |
|---|---|
| `GradingHandlersTest.pushesEveryPublishedRow` | each student gets **her own** row, verb and recipient and payload all checked |
| `GradingHandlersTest.pushesOnlyAfterTheCommit` | asserts, **from inside the push**, that the transaction has already committed. The ordering is the fix, so the ordering is what the test drives |
| `GradingHandlersTest.reApprovingPushesNothing` | a double click is silent |
| `GradingHandlersTest.arefusedRequestPushesNothing` | a `VALIDATION` refusal publishes nothing |
| `GradingHandlersTest.aFailedPushDoesNotFailTheVerb` | a throwing gateway leaves the answer `OK` with its count intact |
| `GradeApprovalServiceTest.collectsARowPerApproval` | one row per approval, `APPROVED`, effective score, `approvedAt`, and v1.1's exam label from the execution |
| `GradeApprovalServiceTest.stripsTheJustification` | `overrideReason` null, `teacherComment` present, override honoured in `finalScore`/`effectiveScore` |
| `GradeApprovalServiceTest.reApprovingPublishesNothing` · `refusedPublishesNothing` · `emptyRequestPublishesNothing` | the three ways to publish nothing, including that an empty request reads nothing back |
| `GradeApprovalServiceTest.readsOncePerExecution` | one read per execution across an eight-grade bulk approve |
| `GradeApprovalServiceTest.unreadableGradeIsDropped` | a grade whose join does not resolve is dropped with a `WARN`, not pushed as a row of blanks — and the approval still stands |

**Twelve new methods.** `MyGradesSessionTest` already had the client half and needed nothing: it
has asserted since E13.3 that a `PUSH_GRADE_PUBLISHED` on a real bus re-queries the list. That
test was correct all along and was measuring a message nobody sent.

---

## 2. B-30 — the one inbox that subscribed to nothing

**Found by:** case 18.2 · **Severity:** Medium

`ApprovalQueueView.listensToEvents()` returned `false` with the comment *"Nothing here
subscribes; a decision refreshes the list through the session."*, and `ApprovalQueueSession`
contained no `@Subscribe` and no reference to `ServerPushEvent` at all.

**The server was doing its half correctly**, which is what makes this a client gap rather than a
feature gap: `EXAM_SUBMIT` delivered `PUSH_NOTIFICATION` carrying an `APPROVAL_REQUESTED` row to
`rina.barak`'s socket, `delivered live to 1`, and her queue re-read on the spot held the new row.
So a coordinator sitting on the queue watched **the bell badge increment and the list beneath it
stay exactly as it was** — on the one screen in the product whose entire purpose is an inbox.

The mitigation is real and does not close it: clicking the notification navigates to
`Routes.APPROVALS` and `onShow` calls `session.load()`. But clicking the bell is a user action,
and NFR-18 is about the ones you do not have to take.

### The fix, and the two decisions inside it

**Filtered on the notification's type, not on the verb.** `PUSH_NOTIFICATION` carries every kind
this app has; a published grade is not a reason to re-query an approval queue. Two types change
this list: `APPROVAL_REQUESTED` adds a row, and **`APPROVAL_SUPERSEDED` removes one** — a teacher
who revised and resubmitted has withdrawn the version her coordinator was about to open. Both are
sent to `result.coordinators()` and to nobody else, so no recipient filtering is needed on the
client. `APPROVAL_APPROVED` and `APPROVAL_REJECTED` are deliberately **not** in the set: those
are the author's news, and this is not the author's screen.

**It re-queries rather than patching the pushed row in.** Same reasoning as the existing
`refresh()`, and it matters more here: the push says one exam arrived, and a supersede in the
same second may have taken a different one away.

**`listensToEvents()` is still `false`, and that is not the same `false` it was.** The flag
governs whether `ScreenLifecycle` registers *the screen*, and the screen has no `@Subscribe`; the
subscription belongs to the session, wired in `build()`, which is `ExamListView`'s and
`MyGradesView`'s shape and puts the behaviour where a test can reach it. Turning the flag on
would register an object with nothing to receive. The comment now says which of those two facts
it is stating.

The subscriber is **public on a public class**, for the reason `ExamListSession`'s javadoc
records: the bus invokes subscribers reflectively from its own package, so a package-private one
registers without complaint and then silently never fires.

### Tests

| Test | What it holds down |
|---|---|
| `ApprovalSessionTest.Queue.anArrivingExamRefreshesTheQueue` | a real `NotificationDto` on a real `ClientEventBus`: empty queue → push → **two** `APPROVALS_QUEUE_GET` and the row is on screen |
| `…aSupersedeRefreshesTheQueue` | the removal case, which is the half a naive fix forgets |
| `…unrelatedPushesAreIgnored` | a published grade, a decision on her own exam, and a non-notification payload all leave the queue alone |

Driven through the bus rather than by calling the method, for `ExamListSessionTest`'s stated
reason: a test that calls `onQueueChanged()` directly passes with the wiring deleted.

---

## 3. B-37 — the connect screen could print a Java class name

**Found by:** case 21.3 · **Severity:** Medium (copy) · **Also `UI-REGISTER` U-4**

```java
String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
```

A `SocketTimeoutException` carries no message, so this rendered *"Could not reach
192.168.1.5:5555 (SocketTimeoutException). Check the server is running…"* — **on the first screen
anyone sees at a defence, and the only screen that depends on the room's network.** Even the
non-null branch was a JDK string rather than product copy.

**What makes it a defect rather than a house style:** the reconnect banner beside it is clean.
`ReconnectBanner.showDisconnected(String serverLabel)` takes no detail parameter at all, and
`ConnectionLostEvent`'s javadoc says the technical reason is *"never shown as the primary
message"*. The product already knew the rule; this screen was the exception. That banner is the
house reference and the fix is written to match it.

### The fix makes the leak unrepresentable rather than repaired

`ConnectFlow.afterFailedConnect` now takes the **`Throwable`** instead of a `String reason`.
There is no longer a parameter a caller could pass a class name to. `ConnectFlow.reasonFor`
walks the cause chain — the interesting exception arrives wrapped, and matching only the
outermost type works until it does not — and maps the four causes the product has words for:

| cause | sentence |
|---|---|
| `ConnectException` | *"Nothing is listening on that address."* |
| `SocketTimeoutException` | *"That address did not answer."* |
| `UnknownHostException` | *"That name could not be found on this network."* |
| `NoRouteToHostException` | *"That address cannot be reached from this network."* |

Anything else answers `""`. **Deliberately empty rather than a default**, because a default that
leaks is how this happened: "an unexpected error" is noise and the throwable's own text is the
bug.

**And the brackets are gone entirely.** The walker's suggested sentences end in full stops, which
do not belong inside a parenthesis, and chasing that discovered the better shape: a recognised
cause gets **a sentence of its own** between the address and the instruction, and an unrecognised
one leaves the message two sentences long rather than a bracket with nothing useful in it.

> Could not reach 192.168.1.42:5555. Nothing is listening on that address. Check the server is
> running, then enter the address shown on its console.

`ConnectView.onFailed` now only logs the throwable and renders what `ConnectFlow` decided.

### Tests

`ConnectFlowTest` gains six methods, and the two that matter are the negative ones:
`aMessagelessThrowableIsNotRenderedAsItsClassName` (the exact B-37 case, asserting the absence of
`SocketTimeoutException`, of `Exception`, of `(` and of `java.`) and `unknownCausesLeakNothing`,
which drives four unrecognised throwables — including one carrying a nested message — and proves
none of them can put a bracket, a class name or their own text on the screen. Plus the cause-chain
walk through a `CompletionException`, a self-referential chain that must terminate, and a copy
sweep over the four sentences. Two existing assertions were updated for the new signature; the
em-dash sweep already covered `afterFailedConnect` and still does.

**B-35 is not fixed here and stays open.** It is the same scenario's other leak —
`"Unsupported operation: PUSH_NOTIFICATION"` from `MessageRouter` — and it is behind a state one
build cannot produce, while this one is on the screen the defence opens with.

---

## 4. B-38 — `Icons` had no test, and two constants named glyphs that do not exist

**Found by:** batch C's report · **Severity:** Low · **Also `UI-REGISTER` U-3**

Batch C fixed `Icons.MONITOR`, named the two it had no business touching, and recommended the
guard as the first item of the next batch. Both were real:

| Constant | Was | Where it shows | Now |
|---|---|---|---|
| `Icons.LOGOUT` | `"mdoal-logout"` | the profile menu's sign-out item | `"mdoal-exit_to_app"` |
| `Icons.WARNING` | `"mdomz-warning_amber"` | every warning chip and toast | `"mdomz-warning"` |

Both replacements were chosen by reading the pack's own constant list: `Material2OutlinedAL` has
727 constants and `Material2OutlinedMZ` has 653; there is a `LOGIN` and an `EXIT_TO_APP` and
there has never been a `LOGOUT`, and there is a `WARNING` while `WARNING_AMBER` postdates the
pack.

### The guard, and the trap in writing it

The obvious check is `IkonResolver.getInstance().resolve(literal)`. **It passes for both broken
literals.** It answers on the `mdoal-` / `mdomz-` prefix alone, hands back the pack's handler,
and says nothing about whether the pack has that name — which is exactly why three of these have
now shipped. The claim only has teeth when the returned handler is then asked for the glyph,
which is what `FontIcon`'s constructor does and what fails at runtime.

So `IconsTest.everyConstantResolvesInThePack` resolves twice. It scans the class's public
`String` constants rather than naming them, so a constant added next month is covered the moment
it is written (`BotCopyTest`'s shape), and it needs no JavaFX toolkit because it never builds a
node. `theScanHasTeeth` pins the scan's own size; `anUnknownLiteralIsStillSurvivable` pins the
*other* half — the swallow in `Icons.of` is correct for data-driven literals and stays.

**The scan caught nothing else: 29 constants, 27 already correct.** `Icons.REFRESH` is the
opposite problem — a valid literal mounted on nothing — and stays open as **B-33**, deliberately
not folded in here so this guard's own scan size does not change for two unrelated reasons.

---

## 5. B-25 — the demo student had no bell

**Found by:** case 17.3 · **Severity:** Low (demo content)

The seed's eight notifications reached seven recipients and `maya.levi` was not one of them. She
is the student `DEMO_ACCOUNTS.md` and the acceptance table use throughout, and the account
`DEMO_DAY.md` §2.3 signs in as to prove the seed took. The one bell a grader is most likely to
open was the empty one.

Seed §11 gains **`N-GRADE-MAYA`**: one unread `GRADE_PUBLISHED` for her approved Algebra 60,
which §9.1 already gives her. Three things about it are deliberate.

**It is the catalog's words, not seed-only copy.** Title *"Your grade is ready"*, body *"Your
grade for Midterm: Algebra has been published."* — exactly what `NotificationCatalog.gradePublished`
composes. What the panel sees on the day is what a live approval produces, which matters more
here than usual because act 6.4 of the demo script makes a live one happen twenty minutes later.

**It deep-links, and it is the first seeded row that does.** The other eight carry no
`ref_type`/`ref_id` at all, so clicking one goes nowhere. Hers stores `grades` + her own attempt
on sitting `4821`, resolved at load time because the id is whatever `AUTO_INCREMENT` gave it.
A target that does not resolve yields a null ref rather than a failed load: the notification is
still true, and refusing to seed over one unjoinable click target would be a worse outcome than
a row that does nothing when clicked — which is what all eight of the others do anyway.

**Its title differs from the other two grade publications on purpose.** §11's idempotency key is
recipient + type + title, and the section's own javadoc warns that the composite collapses the
moment a repeat exists. This is that constraint met rather than tripped, and there is now a test
that says so.

**Row count 375 → 376**, updated in `SEED_CONTENT.md` (§11's table, the "nine rows" prose, and a
new paragraph explaining the row), `DEMO_ACCOUNTS.md`, `DEMO_DAY.md` §3.4 — which now also tells
the operator what her bell should read — and both count tripwires
(`SeedDocumentTest.everySectionYieldsRows`, `SeedDatasetContract`). No test pinned the 375 total.

**Two new assertions**, both in `SeedLoadedDbContract` where the loaded database is compared
against the document: `theDemoStudentsBellIsNotEmpty` (one row, right type, the catalog's title
and body, a non-null ref, unread) and `theIdempotencyKeyIsStillUnique`.

---

## 6. B-24 — `LOAD_IF_MISSING` could not see dataset drift

**Found by:** case 17.2 · **Severity:** Medium (demo data)

Idempotency here is decided **per row, by natural key**, which is what makes the mode safe to
press on a database somebody is using — and means it has no notion of *which version* of the
dataset is in there. Run against `hsts_db`, which still held the pre-translation seed (B-19), one
load reported success and inserted 4 bot sources, 4 bot sessions, 4 bot messages and 7
notifications *beside* eighteen Hebrew-named users. The next run reported `UNCHANGED`, so the
hybrid was stable and invisible. On a defence machine that is a mixed-language screen with no
warning anywhere.

### Why this is a comparison and not a stored marker

The obvious fix is a `seed_meta` row holding a dataset version. **There is nowhere to put it
without a migration** — the schema is twenty tables and none of them is a metadata table — and
the alternative, hiding a version string in a content column of a row a user can see, trades one
silent problem for a worse one. The brief's own fallback was a row-count and sentinel-content
spot-check, and following it produced something slightly better than a fallback:

**the fingerprint is computed from both sides of the comparison.** The dataset's side is ten
fixed probes — nine table counts and one string. The database's side is what those same probes
actually answer. Two digests over the same ordered list, and a difference means the rows in this
database were not put there by this dataset. That is a fingerprint whose **storage is the seeded
content itself**, which is exactly what "no new migration" leaves available.

The one string probe is `dana.cohen`'s display name. It is there because it is the precise field
that distinguished the drifted database in 17.2 from a current one: same username, same row, same
natural key, different dataset.

### What it will and will not catch, stated in the class

- **Catches** the case that actually happened — content that changed while its natural key did
  not — and any table whose row count has moved in either direction.
- **Catches** rows a person added beside the seed. A false positive in spirit and a true one in
  fact: this database is no longer the dataset, and "reload before you demo" is right either way.
- **Does not catch** a change to seeded content no probe looks at. Ten probes are not a checksum
  of 376 rows and the class says so rather than implying otherwise.
- **Never deletes anything and never fails a load.** It warns. A guard that refused to load, or
  that "fixed" the database, would be a far more dangerous thing to run minutes before a defence
  than the hybrid it is warning about.

The warning rides on `SeedSummary` as a third component (with a two-arg convenience constructor,
so every existing caller keeps compiling), which means it reaches **both** front doors — the
console result panel and `SeedMain`'s terminal output — without either of them knowing the check
exists. It runs in `LOAD_IF_MISSING` only: a reseed has just written this dataset, so the answer
is known and running the probes would be a way of getting it wrong.

### The duplication, and its tripwire

The probes' expected values restate numbers that live in `SEED_CONTENT.md`, which is a second
place for them to drift. That is guarded rather than accepted:
`SeedDatasetContract.theFingerprintMatchesAFreshlySeededDatabase` loads the real dataset into an
empty schema and asserts no drift is found, so changing the seed without changing the fingerprint
**fails the build** instead of warning every operator forever until they learn to ignore it. The
notifications probe already moved once in this batch, from 8 to 9, under B-25.

### Tests

| Test | What it holds down |
|---|---|
| `SeedDatasetContract.theFingerprintMatchesAFreshlySeededDatabase` | the tripwire above: expectations against the real dataset |
| `…aCleanDatabaseIsNotWarnedAbout` | no false positive on the honest case |
| `…driftedContentIsCaughtAndNothingIsDeleted` | **17.2 reproduced**: rename `dana.cohen`'s display name to the Hebrew it used to be, reload, and the outcome is still `UNCHANGED` (the row-level check matches on the username — that is the gap) *and* the warning names the field, says nothing was deleted, and says Reload demo data. Row counts unchanged afterwards |
| `…extraRowsAreCaught` | a row a person added moves the count and is reported by name |
| `SeedSummaryTest` ×3 | the warning is carried, printed last and on its own line, absent when there is nothing to say, and a null collapses to absent rather than printing "null" |

---

## 7. The fold-in — the acceptance table is complete

Seven scenarios, **29 cases**, folded from the two walker reports into
`docs/ACCEPTANCE_TESTS.md`. Every Actual cell is the walker's own paste-ready text, transcribed
rather than paraphrased; the only edits were mechanical (case 21.3's eight-row refusal table
became an inline enumeration so it could live in a table cell, and pipes inside code spans were
escaped).

**Counts.**

| | |
|---|---|
| Actual cells filled | **29** |
| Summary rows completed | **7** (15–21) |
| Register entries added | **13** — B-23…B-26, B-30…B-38, plus one reserved-range note |
| Dated amendments added to filled cells | **5** (17.2, 17.3, 18.2, 18.4, 21.3 — the cases this batch's fixes touch) |
| Case Steps cells corrected | **1** (18.2's actor, B-31) |

### The register, and one deliberate untidiness

The walkers numbered in parallel: S15–S17 reserved B-23…B-29 and used four, S18–S21 started at
B-30. **The gap B-27…B-29 stays reserved and unused**, with a one-line note in the register
saying so. Batch A collapsed a sparse range once and it cost a permanent mapping line that every
reader of three walk reports now has to hold; both reports here are already cross-referenced by
number in themselves, in each other and in this file, and renumbering a second time would
invalidate all three to save three integers.

### Statuses

**Marked Fixed by this batch:** B-24, B-25, B-30, B-32, B-37, and B-38 (which gets its own entry,
attributed to batch C's report and fixed here).

**Applied as documentation:**

- **B-23** — `ARCHITECTURE.md` §9 corrected to `client.core.ClientLauncher`, with the reason
  spelled out rather than the name silently swapped. `Launcher`'s own javadoc rewritten from
  *"Non-JavaFX, single-click entry point for the Fat JAR (manifest Main-Class)"* to what it is: a
  **dev-only** co-launcher that needs the server classpath, carrying the actual
  `NoClassDefFoundError` transcript so the next reader cannot repeat the mistake. The class is
  kept rather than deleted — it is the one-process convenience the console development loop uses.
- **B-26** — closed by documentation, beside B-2 as recommended. `DEMO_DAY.md` gains **§2.4, "Two
  lines on the terminal that are expected, and are not faults"**, a two-row table covering B-2's
  red log4j2 line on the server and B-26's JavaFX module warning on the client, with the sentence
  to say if either is asked about. The recommendation was the opposite of B-2's — do not chase it
  in code — because there is no fix that keeps the double-clickable-jar deliverable, which is
  F14.1.
- **B-31** — case 18.2's Steps cell corrected to `rina.barak`'s queue and `dana.cohen` submitting
  `101102` v1, with a parenthetical saying why the actor changed. B-17's precedent.
- **B-33** — recorded Open, with a note saying why it was not taken here.
- **B-34** — see below; it is the one that needed a judgement.
- **B-35** — recorded Open, with the reasoning for leaving it beside a fixed B-37.
- **B-36** — closed by documentation. `ARCHITECTURE.md` §9 now states that
  `server/console/ConsoleView` imports `client.ui.components.WarnConfirm`, that it is
  presentation-to-presentation, and that the logic tier is clean in both directions. 20.1 is
  where that question gets asked and the answer is now on record instead of improvised.

### B-34, and the claim that was checked before it was written

Four claimed patterns were named nowhere in production javadoc: **Observer, Command, Facade,
DAO** — zero occurrences under `src/main/java`. All four are now named at their boundary, in the
house style the five correctly-named ones set:

| Pattern | Where it is now named | The line's substance |
|---|---|---|
| Observer / Pub-Sub | `client.events.ClientEventBus` **and** `server.realtime.PushGateway` | one mechanism, two ends of a socket. Pub-Sub rather than plain Observer because a publisher names an *event type* and never a subscriber, which is what NFR-18 needs |
| Command | `server.core.MessageRouter` | request-as-object + one uniform `Handler` + registry dispatch |
| Facade | `server.features.grading.GradeApprovalService` | the class case 20.1 resolved; one call over three repositories, an ownership rule per row, a statistics freeze, a notification and a push |
| DAO / Repository | new `server/db/repos/package-info.java` | the boundary itself, with the guarantee stated as the absence 20.1 checks: no service opens a `Connection` |

**The Command claim was checked rather than assumed, because the brief allowed for withdrawing
it.** `PLAN.md` §2 claims "Command (protocol)". The verdict is that it is present, and the
javadoc says precisely what is and is not there: a request **is** an object (`Message` + `Verb` +
payload, which is what lets it cross a socket and be answered out of order), every operation
wears **one uniform invocation interface**, and dispatch is a **registry lookup and deliberately
not a `switch`**, so adding a verb touches no code in the router — that last property being the
one the pattern was chosen for. What is absent is `undo`, and the command object does not carry
its own receiver (`Handler` is bound to its service at registration). **Both absences are written
into the javadoc**, so the answer to "that is not textbook Command" at a defence is a concession
plus a reason rather than a defence of the label. NFR-20's claim table keeps the row.

`DEFENSE_QA.md` Q4 carried the stale version of this — *"two of the claimed patterns, Observer
and DAO, are not named in Javadoc yet"* — and it was **four**, not two. Q4 and the weak-spots
table both now say so and record the fix.

---

## 8. The demo documents, ported and reconciled

`DEMO_SCRIPT.md` and `DEFENSE_QA.md` are on `main`. The port is a reconciliation rather than a
copy: they were written on `hsts-e15-wt` while three batches of fixes were landing beside them.
Batch C listed four stale passages; **six changed**, and the two extra ones are the interesting
part.

| Passage | Was | Now |
|---|---|---|
| act 2.1 **If it goes wrong** | *"two rail items read 'Arrives with E10 / E11' … the rail label is stale, the screens are not"* | **deleted.** The rail act 2.1 reads out is literally what is on screen, and `RoleNavTest.nothingIsDisabledAnywhere` asserts it (U-1) |
| act 2.7 **the honest half** | *"The auto tab is the one screen in the authoring epic that is not built … we would rather say that than show you a button that is not there"* | **deleted, and the tab is clicked.** E7.13 landed in #53. The fallback — read cases 3.5 and 3.6 aloud — is kept as the *if it goes wrong* |
| act 5.1 **Note** | *"the rail's Take Exam item is greyed. The dashboard code box is the live path"* | rewritten as **three doors** into one screen, with F6.4's reasoning: every entry starts at the code screen and the dashboard pre-fills the code it already validated |
| act 5.5 amber-slot line | *"the flag is demonstrated below the screen in acceptance case 14.7 rather than staged here"* | **staged.** See below |
| not-done table | three rows | **three rows retired**: the C-4 one (U-2), the builder picker (F3.2, #53) and the auto-compose (F3.3, #53) |
| `DEFENSE_QA` Q4 + weak-spots | *"two of the claimed patterns … are not named in Javadoc"* | it was four; all four are named, and the NFR-20 row moved off PARTIAL (B-34) |

### The new step, and why it is worth its forty seconds

**Act 5.5a: Maya opens Databases 22's bot from the chat header while sitting Algebra, and takes
the C-4 notice live.**

This is the most demonstrable requirement in C-4 and **it had never been shown on a screen**. The
chat opened her first course unconditionally and her first course is Algebra, which is the course
the seeded live sitting belongs to — so the one bot this screen would open for her was the
*locked* one: a different rule, a different outcome, a different sentence.

> **One correction made on the way in, and it is worth recording because it was inherited rather
> than invented.** Batch C's handover wrote the step as *"sitting the 22 exam she can pick Algebra
> 11"*, and the brief for this batch repeated it. **The courses are the other way round.** Seed §8
> gives exam `101101` course **11 (Algebra)**, §9 gives the live execution `2075` to that exam,
> and act 5.1 has her join `2075` — so she is sitting **Algebra** and the cross-course bot has to
> be Databases 22 or Java 21. Written the inherited way, the step would have staged the *locked*
> path a second time and called it the cross-course one, in front of a panel, immediately before
> act 5.6 stages the locked path on purpose. Checked against the seed rather than against the
> handover, which is the only reason it was caught. Batch C's picker (U-2) made the cross-course path reachable by a
person; case 14.7 had walked it below the screen since 2026-08-26. The step is written with the
sentence to say before she confirms (*"the server does not refuse her: it tells her that
continuing informs the teacher running her exam, and it waits"*), what appears on the teacher's
machine (`AttemptTracker` raises an `INTEGRITY_ALERT` to the teacher running that execution and
flags her monitor row — checked in the source, not assumed), and two failure modes with their
recoveries, one of which is B-20 behaving correctly.

**Act 5.6 was rewritten to be the contrast rather than a repetition.** It now opens by switching
the picker back to Algebra, and says so out loud: *"this is the other half of the same rule, which
is why the picker is worth having on screen."* Two adjacent steps, one bot screen, two different
C-4 outcomes.

### Reconciled against this batch

**B-32 makes the B-18 moment stronger and act 6.4 now says so.** Until this batch, "her list just
went from one row to two" required her to have opened My Grades *after* the approval. The
producer exists now, so the row arrives under the panel's eyes with nobody touching machine B —
and the act's instruction changed accordingly: **leave her screen open from 6.1 and do not touch
B at all.** Act 6.1 gained the matching instruction and a line about her bell, which B-25 gave
her.

The script carries a dated port note at the top listing all six changes, each traceable to a
`B-n` or a `U-n`, so the next reader knows it was reconciled rather than copied.

**TODO:** E22.4b and E22.5 ticked, both with notes recording what changed on the port and stating
that the dry runs (E22.6/E22.7) are not these rows.

---

## 9. The TODO annotation pass

Five acceptance-pass rows annotated in the `E6.17 / E7.17 / E13.7` house style — *below-screen
half discharged by the campaign, on-screen half at the manual round* — each naming its walk
report and what that walk actually found, so the annotation is evidence rather than a formula:

| Row | What the annotation records |
|---|---|
| **E8.8** (T-4) | ACCEPT-S4-S5: five of six below the screen, 4.2 partial because the render *is* the case, 4.4's bell failed and is fixed (B-11) |
| **E9.8** (T-5) | ACCEPT-S4-S5: five of six, 5.4 UI-only with the server gate verified, 5.6's fixture defect fixed (B-10) |
| **E10.17** (T-6) | ACCEPT-S6-S7: all ten below the screen; the missing illustrations are B-8, which is seed content and not this epic |
| **E11.6** (T-7) | ACCEPT-S6-S7: all four, and **B-14 was found inside 7.1's own path**. Also records that 18.3 walked the same extension from the NFR-18 side and passed — a second independent sighting |
| **E16.18** (T-13/14) | ACCEPT-S13-S14: **both scenarios had a failure** (13.4/B-21, 14.7/B-20), both fixed in batch B, and 14.7's cross-course half was UI-unreachable until batch C's picker |

**E7.11–E7.16** needed no ticking: all five were already `[x]` as of #53's merge, each with a
dated note. Verified against the tree rather than against the checkboxes — `ExamBuilderView`
calls `session.addFromBank(row)`, `ExamBuilderSession` carries the `AUTO` mode and its criteria
grid, and `ExamBuilderInteractionTest` and `ExamBuilderSessionTest` are both present. **E7.17**
already carries the lead's 2026-08-26 ruling and is untouched.

**E13.6** amended rather than annotated, because its existing note was half true and the wrong
half mattered: *"the session subscribes to `PUSH_GRADE_PUBLISHED`"* was correct, and nobody had
checked whether anything **sent** it. Still unticked, and now for the originally stated reason
alone — the student dashboard card.

**H13.5** in the hardening section carries the same correction: the item had never been walked,
and 18.4 found out why — it could not have passed. It stays open as an on-screen claim, but it
now has a producer to walk.

**E21.6** annotated: *below-screen complete, 21 scenarios of 21, 115 cases, every Actual cell
filled; the on-screen ordered dry run remains.* The annotation points at the two walk reports'
own outstanding lists, so the run is a script rather than a discovery exercise.

---

## 10. UI-REGISTER

**U-3 · COSMETIC** — the `Icons` class drift. Found by batch C, fixed here. Logged separately
from U-1 rather than folded into it because it is a different surface and a different class of
defect, and because the entry's substance is the *trap in writing the guard* (the resolver
answering on the prefix), which is worth a reader's time.

**U-4 · COPY** — B-37's connect sentence. Functional copy on the first screen anyone sees, which
is this register's business as much as the acceptance table's. It carries the walker's words, the
contrast with the reconnect banner that makes it a defect rather than a policy, and — as both new
entries do — **what to look at on screen**, since neither closes until Naji has seen it.

Both `DONE`, neither `VERIFIED`. The register's opening line moved from "Both are `DONE`" to "All
four are `DONE`".

---

## 11. The campaign's final scoreboard

**21 scenarios · 115 cases · every Actual cell filled.**

| # | Scenario | Cases | ✅ | ⚠ | ❌ | ⬜ |
|---|---|---:|---:|---:|---:|---:|
| 1 | Login | 4 | 3 | 1 | | |
| 2 | Question bank editing | 8 | 7 | 1 | | |
| 3 | Exam building | 9 | 9 | | | |
| 4 | Exam approval | 6 | 5 | 1 | | |
| 5 | Out of the drawer | 6 | 5 | | | 1 |
| 6 | Exam execution | 10 | 10 | | | |
| 7 | Extending exam duration | 4 | 4 | | | |
| 8 | Exam checking | 7 | 7 | | | |
| 9 | Viewing an exam grade | 5 | 4 | 1 | | |
| 10 | Viewing exam results | 5 | 4 | 1 | | |
| 11 | Viewing data — principal | 4 | 4 | | | |
| 12 | Viewing reports | 5 | 4 | 1 | | |
| 13 | Creating a study bot | 6 | 4 | 1 | 1 | |
| 14 | Using the bot | 7 | 2 | 4 | 1 | |
| 15 | Separate machines, JARs, connect GUI | 5 | 5 | | | |
| 16 | Concurrent users; no double login | 4 | 4 | | | |
| 17 | Test data in the database | 3 | 2 | 1 | | |
| 18 | Efficient computing, no refresh | 5 | 2 | 1 | 2 | |
| 19 | Flexible, change-tolerant design | 3 | 3 | | | |
| 20 | Reuse; design patterns | 3 | 2 | 1 | | |
| 21 | UI quality and friendliness | 6 | 2 | 4 | | |
| | **Total** | **115** | **92** | **18** | **4** | **1** |

**How to read those columns, because the numbers flatter and mislead in opposite directions.**

**✅ (92) does not mean "seen working on screen".** The overwhelming majority are *passed below
the screen* by the house method (cases 9.4/9.5 are the standard): driven through the production
router, the production services and a real seeded database, with the rendering explicitly named
as a manual-pass item. That is a stronger claim than a click in every respect except the one the
panel will be looking at.

**⚠ (18) is not eighteen problems.** It is four different things:
*(a)* **pixels by nature** — 21.5 (light/dark across five palettes) and 21.6 (three window sizes)
are *entirely* a human's judgement and no probe can contradict them;
*(b)* **partial by fixture** — 18.5 and 2.6 are blocked on B-8's missing image bytes, 12.1 on the
seed having one reportable sitting;
*(c)* **the render is the case** — 4.2, 9.5;
*(d)* **genuinely incomplete** — 20.2, now fixed by B-34.

**❌ (4) is the honest number and it is the one worth quoting**: 13.4, 14.7, 18.2, 18.4. All four
are fixed — B-21 and B-20 in batch B, B-30 and B-32 here — and all four keep their walked verdict
in the Status column on 13.4's precedent, with the fix recorded in a dated amendment. **A
campaign that found four real failures and left the ❌ visible after fixing them is worth more at
a defence than one that quietly re-verdicted them.**

**⬜ (1)** is case 5.4, marked UI-only by the S4–S5 walker with the server-side gate verified
underneath.

### What the campaign cost and produced

| | |
|---|---|
| Scenarios walked | **21 of 21** |
| Cases | **115** |
| B-numbers filed | **B-1 … B-38**, minus the reserved B-27…B-29 = **35 entries** |
| Of those, Medium or above | **11** |
| Fixed in code | **B-3, B-4, B-5, B-6, B-7, B-10, B-11, B-12, B-14, B-16, B-20, B-21, B-24, B-25, B-30, B-32, B-37, B-38** — 18 |
| Closed by ruling or documentation | **B-1, B-9, B-17, B-18, B-19, B-22, B-23, B-26, B-31, B-34, B-36** — 11 |
| Still open | **B-2, B-8, B-13, B-15 (merged into B-8), B-33, B-35** — 6, all Low, none blocking |
| UI-register entries opened by the campaign | **U-1 … U-4** |

**Six defects remain open and every one of them is Low.** Two are cosmetic terminal lines (B-2,
B-26's twin), one is the seed's missing image bytes (B-8/B-15, one ticket), one is a documentation
mismatch in exam names (B-13), and two are the copy items this batch chose not to take (B-33,
B-35), each with its reason recorded beside it.

---

## 12. Test inventory

**34 new test methods across 8 classes**, one of them a new class, plus two existing assertions
updated for a changed signature and two count tripwires moved. The suite went from batch C's
**6462** to **6569** — the delta is larger than 34 because several of these are nested or run
against both the H2 and MySQL contract subclasses.

| Class | New | What they cover |
|---|---|---|
| `GradingHandlersTest` (new nested `Publishing`) | 5 | B-32's push: recipients and payloads, **after-commit ordering asserted from inside the push**, silence on re-approve, silence on refusal, survival of a throwing gateway |
| `GradeApprovalServiceTest` (new nested `Collecting`) | 7 | B-32's rows: one per approval with v1.1 labels, justification stripped, three ways to publish nothing, one read per execution, an unreadable grade dropped rather than blanked |
| `ApprovalSessionTest.Queue` | 3 | B-30 through a real bus: an arriving exam, a supersede, and three unrelated pushes ignored |
| `ConnectFlowTest.Interactions` | 6 | B-37: the messageless throwable, a four-cause leak sweep, the four mappings, the cause chain through a `CompletionException`, a self-referential chain, and a copy sweep. Two existing methods updated for the `Throwable` signature |
| **`IconsTest`** *(new class)* | 4 | B-38: the scan resolving twice, the scan's own size, the two named literals, and that the data-driven swallow still works |
| `SeedDatasetContract` | 4 | B-24: the fingerprint tripwire, no false positive, **17.2 reproduced**, and an extra row caught |
| `SeedSummaryTest` | 3 | B-24: the warning carried, printed last, absent when empty, null-safe |
| `SeedLoadedDbContract` | 2 | B-25: the demo student's bell, and the idempotency key still unique |
| `SeedDocumentTest`, `SeedDatasetContract` | *(updated)* | notifications 8 → 9 |

**Nothing was weakened and nothing was deleted.** The only changed assertions are
`ConnectFlowTest`'s two, which had to move because the method they call changed shape — and both
assert more than they did, since the old detail string is now a product sentence.

**Coverage note.** `ApprovalQueueView`, `ConnectView` and `Icons` are on the jacoco view-exclusion
list, so none of the three client fixes moves the bundle ratio in either direction; the new
session-level and flow-level tests add to it, and `SeedFingerprint`, `GradingHandlers.publish` and
`GradeApprovalService.publishedRows` are all measured.

---

## 13. Verify

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchd ./mvnw -B clean verify
```

**BUILD SUCCESS**, 2026-08-27 05:26 +03:00.

| | |
|---|---|
| Tests | **6569 run, 0 failures, 0 errors, 0 skipped** (batch C's baseline: 6462 — **+107**) |
| Jacoco `BUNDLE` INSTRUCTION | **97.85 %** covered — 79 334 of 81 077, gate `0.90` |
| Branch | 90.48 % · Line 97.75 % · Method 98.04 % · Class 654 of 657 |
| Coverage check | *All coverage checks have been met.* |
| Wall clock | 16:56 min |

**The gate was run twice, and the first run was red for a real reason of this batch's own making.**
Recorded rather than quietly rerun.

1. **Red, correctly, and by one of the new tests.**
   `SeedDatasetContract.extraRowsAreCaught` failed with a foreign-key `ConstraintViolation` on
   `fk_notifications_user`. It inserted a notification for `user_id = 1`, which is what
   `SEED_CONTENT.md` §3 numbers `principal.avia` — **and `users.id` is `AUTO_INCREMENT`, so "user
   1" is only user 1 on a schema that has been loaded exactly once.** This class reuses its
   schema, and the full gate loads it more than the targeted run did, which is why the same test
   passed on the earlier `-Dtest='Seed*'` run and failed here. Fixed by resolving the recipient
   from the database (`select u.id from User u where u.username = 'maya.levi'`), which is what
   every other assertion in that file already does. **Worth stating plainly: a test written for
   B-24 reproduced, in miniature, B-24's own lesson — an identifier that looks stable and is not.**
2. **Green**, the numbers above, from a `clean verify` on the fixed tree.

`DeepSeekProviderTest` did not flake on either run, and the NTFS partial-`test-compile`
`NoClassDefFoundError` that batch C hit did not recur.

---

## 14. What is left, and it is one thing

The campaign is finished below the screen and has not started above it.

**E21.6 — one ordered dry run of scenarios 1–21, on screen, by a person, recorded in the
submission table.** Everything this campaign produced is in service of making that run a script
rather than a discovery exercise: every case now says what it should show, and the two walk
reports carry explicit lists of what only a human can close.

The specific items, gathered from both reports so the run has one checklist:

1. **Double-click both jars on a clean Windows machine** — `DEMO_DAY.md` §2.3, and now §2.4's two
   expected terminal lines.
2. **The server console window itself** — health cards, connected-clients table, log pane, seed
   buttons. 17.1/17.2 drove the logic; the window is unobserved.
3. **Two physical machines on a LAN** — `DEMO_DAY.md` §4 (E20.5). Everything above the network
   card is proven; the cable is not.
4. **The connect screen's three states** — searching, picker, manual — plus the mismatch
   `WarnConfirm`, and now **B-37's new sentences** on a real failed connect.
5. **Light ↔ dark across all five palettes, every screen.** The largest single manual item in the
   whole campaign, and the one no probe can touch.
6. **Three window sizes, and a Hebrew question beside an English one** — typed on the day, since
   the seeded bank is English after the wave-1 translation.
7. **The spinner, the skeleton and the overlay** actually appearing, on a large report and a bot
   call.
8. **That no demoed screen looks empty or fake.** 17.3 proves each has content; the judgement is
   a human's.
9. **The four UI-register entries** — U-1's two rail items, U-2's course picker, U-3's two icons,
   U-4's connect sentence. None closes until Naji has seen it.
10. **Reseed `hsts_db` before any of the above.** It is currently a hybrid — the S15–S17 walk
    disclosed leaving it that way rather than emptying a shared database on its own authority —
    and it is now the exact database B-24's new warning was written for. Loading it will say so
    out loud.
