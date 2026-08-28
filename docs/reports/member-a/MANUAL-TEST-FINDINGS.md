# Manual test findings — Member A

Defects found by **driving the built application by hand**, as opposed to the acceptance walks in
`docs/ACCEPTANCE_TESTS.md` (which are largely exercised below the screen, through the router) and
the automated suite. This file is the running log; entries graduate into the `B-nn` register when
the lead adopts them.

**Numbering is local.** `M-n` here, deliberately not `B-nn`, because the register lives in
`docs/ACCEPTANCE_TESTS.md` and assigning numbers into it from this side would collide with whatever
the lead is numbering concurrently. The register's highest entry at the time of writing is `B-38`.

**Read the build line on every entry.** A manual finding is only about the binary that was on
screen. Re-test before assuming an entry still stands.

## Index

| | | status | lane |
|---|---|---|---|
| **M-4** | the paper does not render after the code and id are both accepted | OPEN, **lead's**, cause identified | E10, lead |
| **M-1** | the notification list is empty at sign-in, fills only on a live push | OPEN | E17, not A |
| **M-5** | the mark-as-read control does nothing | OPEN | E17, not A |
| **M-6** | the exam list promises "Open" on hover and selects instead | **FIXED by the lead**, ruling (b) | shared component, lead |
| **M-7** | the dashboard code box hands you a second code box | OPEN, works as written | E10, not A |
| **M-8** | the back control is not obvious | **FIXED by the lead** | shell, lead |
| **M-2** | no demo document carries a national id | OPEN | docs, half A's |
| **M-3** | a teacher could not start a new exam | **SOLVED** | E7, A's |

**Adopted into the register.** The lead ruled on 2026-08-28: one register, not two. `M-1`..`M-8`
are being adopted into `docs/ACCEPTANCE_TESTS.md` as `B-39`+ with these `M-` numbers kept as
cross-references, by him, immediately after PR28 merges. This file stays as the manual-round
narrative; the register stays the authority.

**One of the eight is Member A's to fix and it is fixed.** Six of the remaining seven sit in
epics owned elsewhere, which is why this file records and does not repair.

---

## Build under test

| | |
|---|---|
| Commit | `f95a6c8` — *close(#56): exam-version lock scope installed, section 7.5 seed comparison, record trail* |
| JARs | `target/hsts-server.jar`, `target/hsts-client.jar`, built 2026-08-28 13:12 |
| Database | local MySQL, seeded |
| Tester | Omar (Member A) |

---

## M-1 — the notification list is empty at sign-in and only fills when a new one arrives live

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN · **Reproduced on:** teacher and
student accounts both · **Refined:** 2026-08-28, second pass · **See also:** M-5

**What happens on screen.** Sign in. The notification bell carries a badge reading **1**. Open the
notification panel and there is **nothing in it**, no row, no item, an empty list. The badge
survives opening the panel, and there is nothing in the panel to dismiss.

Stay signed in and let a **new** notification arrive: it appears in the panel normally. The rows
that existed before she signed in never do.

**Why it matters.** This is the first thing on screen after sign-in, on every account, and it is
visibly wrong in a way that cannot be worked around by the person looking at it. A demo audience
sees a counter that lies and then refuses to be cleared. It is also self-inflicting: the badge stays
lit forever, so a *real* notification arriving later is indistinguishable from this phantom.

**Both roles.** Seen as a teacher and as a student, which argues against a per-user data problem and
for something in the shared bell/panel path.

**Refined by a second manual pass the same day, and the refinement is the diagnosis.** The panel is
not permanently empty. It is empty **at sign-in**, and it fills the moment a *new* notification
arrives while she is still logged in. So the live push path works and the **initial read does
not**: the stored rows the badge is counting never reach the list on the way in.

That narrows it hard, and it rules out what this entry first guessed at. It is not a count query
and a list query disagreeing about visibility, because the same list renders those same rows
happily once a push wakes it. The candidates now are (a) no initial fetch is issued at sign-in at
all, the panel starting empty and being filled only by pushes, or (b) the fetch is issued and its
answer lands somewhere the panel does not read. Both are checkable against the wire.

**Which makes the badge the honest half.** The count is reading persisted rows correctly. The list
is the half that never asks.

**Adjacent context, not evidence.** `NotificationsSection` seeds notifications and B-25 added
`maya.levi`'s row specifically because she had no bell at all. That is where the data comes from; it
is not a statement that the seed is at fault.

---

## M-2 — no demo document carries the students' national IDs, and the demo script needs two of them on stage

**Found:** 2026-08-28 · **Build:** documentation, not build-specific · **Status:** OPEN ·
**Affects:** demo day

**What happens.** Starting an exam attempt requires the student's **national ID** (S-18 — it is the
second prompt on Take Exam, after the join code, and it is where the countdown starts). None of the
three demo documents contains a single national ID:

```
$ grep -c 374301851 docs/DEMO_ACCOUNTS.md docs/DEMO_DAY.md docs/DEMO_SCRIPT.md
docs/DEMO_ACCOUNTS.md:0
docs/DEMO_DAY.md:0
docs/DEMO_SCRIPT.md:0
```

The numbers exist only in `docs/seed/SEED_CONTENT.md` (§ the users table) and in the lead's
`docs/reports/lead/ACCEPT-S6-S7.md`. `docs/DEMO_ACCOUNTS.md` has the account table — username,
password, name, role, courses — but no ID column.

**Why it matters — this stops the demo mid-act.** `DEMO_SCRIPT.md` act **5.2** reads *"enter
`noam.peretz`'s id first, then her own"* and supplies **neither number**. Act 5.3, the
server-authoritative clock, cannot begin until that entry succeeds. So the person driving the laptop
has to leave the script and search the seed content document while the room watches.

**The two numbers act 5.2 needs**, sourced from `server/db/seed/UsersSection.java` (the code that
actually loads them, rather than from the document describing them):

| Account | Name | National ID |
|---|---|---|
| `maya.levi` | Maya Levi | `374301851` |
| `noam.peretz` | Noam Peretz | `385612098` |

**Fix, split by who owns the file.** `docs/DEMO_ACCOUNTS.md` is Member A's to edit — an ID column
added to the existing account table closes most of this. `docs/DEMO_SCRIPT.md` is not, so putting
the two numbers inline in act 5.2 is a request to the lead.

---

## M-3 — a teacher cannot start a new exam: the create path is built end to end and reachable from no screen

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** **SOLVED**, confirmed on screen by
Omar against a rebuilt jar the same day; not yet committed · **Lane:** Member A (E7) ·
**Severity:** blocked the epic's primary user journey

**What happens on screen.** Sign in as a teacher, open **Exams**. The screen lists the exams the
seed created, each with the actions its state permits — Open/Edit, Submit for approval, Revise.
There is **no control anywhere that starts a new exam**, on this screen or any other. A teacher on
a freshly seeded database can edit, submit and revise what already exists and can never author
anything.

**This is an unreachable feature, not a missing one, and the distinction decides the fix.** Every
layer below the button exists, is registered, and is tested:

| Layer | State | Where |
|---|---|---|
| Wire contract | FROZEN | `Verb.EXAM_CREATE`; `common/dto/authoring` |
| Router registration | live | `ExamHandlers.java:120` — `router.register(Verb.EXAM_CREATE, this::create)` |
| Service | implemented | `ExamService.java:320` (E7.1, ticked) |
| Builder create-mode | implemented | `ExamBuilderSession.java:1259` — `mode == CREATE ? EXAM_CREATE : EXAM_VERSION_SAVE` |
| Builder entry point for it | implemented | `ExamBuilderView.java:172` — `session.openNew(params.getString("courseCode", null))` |
| **A control that navigates there** | **absent** | — |

`ExamBuilderView.onShow`'s own javadoc names three doors — *"`examVersionId` opens a stored version
[...] `courseCode` with no version is a new exam"* — and only the first is ever knocked on.

**The measurement.** `ExamBuildRoutes.BUILDER` has exactly **one** call site in `src/main/java`:
`ExamListView.java:216`, inside `openInBuilder`, and it passes `examVersionId` unconditionally.
`RoleNav.java:99` puts only `Routes.EXAMS` on the teacher rail; `Routes.EXAM_BUILD` appears in
`Routes.all()` for bulk registration and on no rail. No "New exam" copy string exists anywhere
under `client/`.

**Why it matters.** Authoring an exam is what E7 is for, and it is the act the demo's teacher
narrative is built around. The gap is invisible on a seeded database, because the list is never
empty — which is precisely why it survived to here.

**How it got past both the task list and the suite.** Two causes, and both are worth recording
because neither is carelessness:

1. **It fell between two rows that were each honestly complete.** `E7.10` specified the exam list
   as *"teacher's exams, status chips, versions expandable, actions per state"*, and every action
   per state operates on an exam that already exists. `E7.11`–`E7.13` specified the builder itself.
   Neither row owned *the control that starts one*, so both ticked truthfully and the path between
   them was never anybody's.
2. **The automated suite cannot reach this by construction.** `ExamBuilderSessionTest` drives the
   session object directly and enters `CREATE` by calling `openNew` itself, so create-mode is
   thoroughly covered *while being unreachable by a human*. This is `docs/PROBLEMS.md` **P-6** in
   its exact shape: the author's tests share the author's blind spot and agree with it. A green
   suite was never going to say this.

**Relationship to the open task row.** `docs/TODO.md:173` — **E7.17**, *"Acceptance pass vs T-3"* —
is still open with the note *"the on-screen half rides the next manual round"*. This is that round,
and this finding is the kind of thing that row was left open to catch.

**Fixed 2026-08-28, in the working tree.** A `New exam` menu button in the exam list header,
listing the courses the sign-in payload gives her, each item navigating to
`ExamBuildRoutes.BUILDER` with a `courseCode` and no `examVersionId`.

- The course is chosen **before** the builder opens, because the builder has no control for one:
  `openNew` takes the code as given, the bank picker is scoped to it, and `ExamCreateRequest`
  carries it. A builder entered without a course can pick no questions and save nothing.
- The menu offers **only courses she teaches**, which is exactly what `requireTeachesCourse`
  accepts, so it cannot offer a choice the server would refuse. Deliberately not
  `BankSession.courseOptions`'s wider union, which exists for the bank's read scope.
- Zero courses leaves the control **disabled with the reason on it, never hidden**. A pure
  coordinator reaches this screen and teaches nothing, and hiding the control would make M-3's
  own symptom the correct rendering for `rina.barak`.

Files: `client/features/exambuild/ExamListView.java`, `ExamListCopy.java`, and their two tests.
Nothing outside Member A's lane was touched.

**Three mutations planted and each one caught**, because the whole point of this entry is that a
green suite already agreed with the gap:

| Planted | Caught by |
|---|---|
| the door carries `examVersionId` instead of `courseCode` | `newExamNavigatesToTheBuilderWithACourse` |
| no courses hides the control instead of explaining it | `newExamIsDisabledWhenSheTeachesNothing` |
| the control is removed from the header (M-3 restored exactly) | all three new tests |

**Verified so far:** `ExamListInteractionTest` 12 tests green, and 513 green across
`client.features.exambuild`, `client.core` and `client.ui`. **A full `./mvnw clean verify` has not
been run**, so this is not PR-ready; it is ready to rebuild the jars and test by hand.

---

## M-4 — the paper does not render: code accepted, national id accepted, and then an empty screen

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN, **needs one observation to split**
· **Severity: the highest open finding**, restated as such by the tester on the second pass ·
**Blocks:** scenario 6 end to end, and the demo's central student act

**What happens on screen.** Signed in as a student, Take Exam, the four-digit code for the live
seeded sitting is **accepted**, the national id is **accepted** — and then the exam does not appear.
The screen is empty.

**Both gates passed, which narrows this considerably.** The code returning the header means
`EXAM_JOIN` answered. The id being taken means `ATTEMPT_START` was reached and did not refuse:
a wrong id refuses with a named sentence and a closed window refuses at the join. So the failure
is **after** the server answered, on the render, not on the wire or the guards.

**The fork is closed: it is branch (a), genuinely blank.** Confirmed by the tester on the second
pass, in his words, "it shows nothing no questions no timer nothing". Not the header with an empty
body, and not `ExamCopy.NO_QUESTIONS`. Nothing at all.

### The mechanism, established by reading the three classes involved

A blank screen at this exact moment is not a mystery. It is what the code does when one call
fails, and the failure is invisible by construction.

1. `ExamEntrySession.applyForm` sets `phase = EntryPhase.STARTED` and then calls `onChange.run()`.
2. `ExamEntryView.refresh` shows exactly one of `codeCard`, `identityCard`, `blockedCard`, chosen
   by phase. **`STARTED` matches none of them, so all three are hidden.** The screen is now blank
   on purpose: the form is about to take over.
3. The next line, `onStarted.accept(form)`, is what takes over. It reaches
   `TakeExamView.enterForm`, whose first statement is `attempt.start(executionId, form)` and whose
   *second* is `content.setCenter(formView)`.
4. `attempt.start` calls `model.apply(form)`, which fires `onChange`, which is `refreshForm`,
   which is `formView.refresh()`. **The entire paper is rendered before the paper is ever put on
   screen.**
5. So anything that throws between step 3 and step 4 leaves the entry view with all its cards
   hidden and the form never swapped in. **A completely blank screen.**

**And it is silent by construction.** All of this runs inside `.handle(...)` on the
`CompletableFuture` returned by `dispatcher.send(Verb.ATTEMPT_START, ...)`. `ExamEntryView` calls
`session.start()` from a button handler and **discards the returned future** (`e -> session.start()`,
twice). A throw therefore completes that future exceptionally and nobody ever observes it: no
dialog, no toast, no log line, no stack trace in the terminal. Which is exactly the reported
experience, an empty screen and no error anywhere.

**Two defects here, and they are worth separating from whatever throws.**

- **The swap happens after the render, not before.** Move `content.setCenter(formView)` above
  `attempt.start(...)` and the same failure shows the paper's chrome with an empty body instead of
  nothing. A student would see something wrong rather than nothing at all, and so would a tester.
- **The future is discarded.** An exception on the one path that starts an exam vanishes. Whatever
  the cause turns out to be, this is why it was invisible, and it will hide the next one too.

### What has been ruled out, by measurement rather than by argument

**The illustrations are not the cause.** This was the leading theory, and it is wrong.

- The four seeded PNGs for course 11 (`q11005`, `q11006`, `q11007`, `q11010`) all carry a valid PNG
  signature and are 7-8 KB.
- A throwaway probe drove `QuestionCardView` with the real `q11005.png` bytes on a real toolkit:
  **no throw**.
- The same probe drove a real `ExamFormView` through a real `AttemptModel.apply` with an
  image-bearing question and a second without: **no throw**.

The probe was deleted after reading; it proved a negative and had no business staying in the
build. What it does confirm is the coverage gap that let this reach a human: **every question in
`TakeExamInteractionTest`'s fixture passes `null` for the image**, so the illustration path had
never been exercised on this screen by anything. That is still true and still worth closing, even
though it is not this defect.

### What was still unknown, and who closed it

**Owned by the lead as of 2026-08-28**, on his ruling: E10 is his epic. Both proposed changes are
accepted, and the future-observing diagnostic is already in his tree.

**The cause is the thread, and it is his find.** Every session in this client is constructed with
an FX-thread poster except the two on this screen:

```
BankView:109          new BankSession(dispatcher(), onFxThread(), ...)
ExamBuilderView:151   new ExamBuilderSession(dispatcher(), onFxThread())
ExamListView:75       new ExamListSession(dispatcher(), onFxThread())
TakeExamView:67       new ExamEntrySession(dispatcher())              <- no poster
TakeExamView:70       new ExamAttemptSession(dispatcher(), eventBus(), model, ...)  <- no poster
```

`ExamEntrySession`'s only constructor takes a dispatcher and nothing else. So `applyForm`, and
everything the chain above hangs off it, runs on OCSF's reader thread rather than the FX thread.
That completes the explanation:

- `onChange.run()` hides the three entry cards. Setting `visible`/`managed` off-thread is not
  reliably rejected, so this part succeeds and the screen goes blank.
- `content.setCenter(formView)` is a structural change to a node in a **showing** scene, which is
  exactly what JavaFX does reject. It throws, the form is never mounted, and the blank stays.
- The throw lands in the discarded `CompletableFuture` and is never seen.

**My own probe is evidence for this rather than against it.** It rendered the identical form,
illustrations and all, and came back green - because it ran inside `interact(...)`, which is to say
*on the FX thread*. Same data, same widgets, different thread, opposite outcome. That isolates the
variable rather than clearing the code.

The lead has the server exonerated too: a below-screen probe pulled the real `2075` form and it is
sane, seven questions and three images. His off-thread reproduction was running at the time of
writing.

---

## M-5 — the "mark as read" control does nothing

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN · **See also:** M-1

**What happens on screen.** The notification panel's mark-as-seen/read control can be pressed and
**nothing observable follows**. The badge does not change, and no row changes appearance.

**Recorded separately from M-1 on purpose, and the reason is a fix rather than a taxonomy.** The
two may well share a root cause, but they cannot share a fix: M-1 is a read that never happens,
this is a write whose effect never shows. Even if one change repairs both, each needs its own
check, because "the list now loads" and "pressing the control now clears the badge" are two
claims and a suite that only makes the first would let this one back in.

**One caution before anyone reads a dead handler into it.** On a screen where the list is empty
(M-1), a control that marks the listed rows read has nothing to act on, so "does nothing" may be
the correct behaviour of a working control given an empty list. **That has not been separated
yet**, and separating it is the first step: press it once the panel has a live notification in it,
which M-1 says is reachable by staying signed in until one arrives. If it works then, this entry
folds into M-1. If it still does nothing, it is its own defect.

---

## M-6 — the exam list promises "Open" on hover and does not open

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN · **Lane:** Member A (E7)

**What happens on screen.** On the teacher's Exams list, hovering a row reveals an **"Open →"**
hint at the end of it. Clicking the row does not open the exam. It selects it, swapping the
versions panel on the right. To actually open one you press **Edit** on a version card.

**Measured, and the affordance is real rather than imagined.** `DataTable` reveals the constant
`OPEN_AFFORDANCE` (`"Open →"`) on hover for any table that has been given an `openAction`, and
`ExamListView.build` calls `table.openOnClick(row -> session.select(row.examId()))`. So the
"open" gesture of a shared component is wired to *select*, and the component advertises what its
contract says that gesture means. Both halves are behaving correctly in isolation.

**Why it matters.** It is a control that says what it will do and then does something else, on the
screen a teacher uses most. It also costs a click on every exam: select, then find Edit on the
right.

**Two fixes, and the choice was a design call rather than a repair.**

1. **Make the row click open**, most plausibly the latest version in the builder. Honours the
   affordance, but it takes the master/detail selection gesture away, and selection is how the
   versions panel is reached at all.
2. **Stop advertising it.** The screen wants click-to-select and the hint is the wrong hint. This
   needs a way to have click-to-select without the affordance, and `DataTable` tied the two
   together: the hint appeared exactly when `openAction` was set, and it exposed no separate
   selection callback. `client/ui/components`, not Member A's.

**Resolved 2026-08-28: the lead ruled (b) and implemented it.** `DataTable` grew `selectOnClick`,
the same gesture with no "Open" hint, with `openAction` and `selectAction` mutually exclusive so a
table cannot claim both; `ExamListView` was switched to it on his side.

**Note for whoever merges second.** That switch touches `ExamListView.build`, which PR28 also
changes. The two edits are in different methods and should not conflict textually, but they are
the same file: whichever lands second rebases.

---

## M-7 — the dashboard's exam-code box hands you a second exam-code box

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN, **works as written** · **Raised
as:** a suggestion by the tester

**What happens on screen.** On the student dashboard, the *Take an exam* card has a code box. Type
the code, press Enter, and you land on the Take Exam screen, which is a code box. It reads as
being asked the same question twice.

**The code is doing exactly what it says, which is why this is a design finding and not a defect.**
`StudentHomeView.submitCode` validates and then navigates carrying the code as a parameter;
`TakeExamView.onShow` calls `entryView.prefillCode`, which sets the text and **does not submit**.
Its own comment states the intent: *"a pre-fill and nothing more: the join still happens here and
the identity step still follows it."* `DEMO_SCRIPT.md`'s act 5.1 describes the same shape.

**So the second box arrives with the code already in it, and the student still has to confirm.**
Worth checking on the next pass whether the field really is pre-filled on screen: if it is, this is
a wording and flow problem, and if it is not, the pre-fill is broken and this becomes a defect.
That observation has not been made.

**Why it is still worth fixing.** Two boxes for one code is a hesitation in the demo's central
student act, immediately before M-4's screen. The dashboard has already validated the code, so the
honest options are to auto-submit the join on arrival and land her on the identity step, or to stop
offering a code box on the dashboard at all.

---

## M-8 — the back control is not obvious enough to find

**Found:** 2026-08-28 · **Build:** `f95a6c8` · **Status:** OPEN · **Raised as:** a suggestion by
the tester

**What happens on screen.** Going back is available but the control does not read as one at a
glance. The tester's words: it "isn't obvious, should be highlighted".

**Not investigated.** No measurement has been taken of what the control currently looks like or
where it sits, and no claim is made here about which screens it affects. It is recorded as
observed so it is not lost.

**Resolved 2026-08-28 by the lead**, together with a dated note in the E8 report recording
where the finding came from. The shell chrome under `client/ui/shell` is his, so this was never
a task on this side; it is recorded here because it was found here.
