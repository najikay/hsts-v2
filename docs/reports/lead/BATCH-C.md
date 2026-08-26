# BATCH C — the two UI-path fixes the demo script found

**Run:** 2026-08-26 · **Branch:** `main` · **Nothing committed by this batch.**
**Input:** the demo-script honesty pass. Writing `DEMO_SCRIPT.md` under its own rule 1 ("no step
routes through an open gap") meant reading every rail item and every screen entry against the
route table, and two of them did not survive the reading.
**Gate:** `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchc ./mvnw -B clean verify`
**Register:** both are logged in `docs/UI-REGISTER.md` as its first two entries, **U-1** and
**U-2**, attributed to the pass rather than to a Naji session, status `DONE` pending his
on-screen verification.

Neither is a wire change, a contract amendment or a server change. Both are the same shape of
defect and it is a shape worth naming: **a capability that exists, is tested, is reachable by
some path, and is not reachable by the path a user would take.** No test could have failed for
either, because every test drove the path that worked — every bot fixture gave the multi-course
student one course, and every monitor test navigated with an execution in hand.

Worse than that, in one place: `UiSmokeTest` asserted `.nav-item.disabled` **is not empty**, so
the suite was actively holding the defect in place. That is the sharpest thing in this batch and
it is worth saying plainly — a test can pin a stale claim as firmly as it pins a correct one, and
the only defence is that the sentence beside the assertion has to stay true out loud. That one
said "the not-yet-built rail items are visibly muted", and had been false since E11.

---

## U-1 — the two dead rail placeholders

**The defect, in one line:** the student rail said "Take Exam · Arrives with E10" and the
teaching rail said "Live Monitor · Arrives with E11", four and three epics after those screens
shipped.

`Routes.TAKE_EXAM` has existed since E10 and `Routes.MONITOR` since E11. `SessionRoutes`
registers the first for `STUDENT` and the second for both teaching roles, and has since those
epics. Every other way in worked: the dashboard's code card, the "extra time added"
notification, a Releases row's **Monitor** button, a C-4 integrity alert. Only the rail — the one
surface a user is trained to scan for a feature — reported the feature as unbuilt.

### The swap, and the trap in it

The file documents its own pattern: enabling a feature is swapping one `disabled(...)` for one
`of(...)`, exactly as `ROUTE_REPORTS` (E15.4) and `ROUTE_GRADING` (E12) did. What made this one
not a two-line edit is that **one of the two placeholder ids was wrong**:

| Placeholder | Live route | |
|---|---|---|
| `ROUTE_MONITOR = "monitor"` | `Routes.MONITOR.id() = "monitor"` | agreed |
| `ROUTE_TAKE_EXAM = "exam.take"` | `Routes.TAKE_EXAM.id() = "attempt"` | **did not** |

`"attempt"` is what `NotificationCatalog.ROUTE_ATTEMPT` spells, and it is spelled that way so the
extra-time notification is clickable straight into the exam. Promoting the string beside the
label instead of swapping onto the route constant would have produced a rail item that **throws
on click** — `Navigator.navigate` refuses an unregistered id rather than doing nothing. Both
items now point at the `Routes` constant, and `RoleNavTest` pins `"attempt"` literally so a
future rename has to come and say so.

`soon(...)` and the two placeholder ids are deleted with them. Nothing on any rail is disabled
any more, for any role; the class javadoc says so, and `NavItem.disabled` stays exactly where it
is for the next feature that reserves a slot.

### Paramless entry — the two verdicts

The brief's condition was that neither screen may be given a rail entry it cannot stand, and
that improvising a screen is worse than stopping. Both verdicts are **stands**, and only one of
them needed work.

**`TakeExamView` — stands, unchanged.** Its `onShow` resets to the code screen on *every* entry
and treats the dashboard's pre-validated code as a pre-fill on top of it:

```java
params.get(StudentHomeSession.CODE_PARAM, String.class).ifPresent(entryView::prefillCode);
```

An `ifPresent` over an `Optional`, not a lookup that assumes. This is deliberate and documented
— "every entry starts at the code screen, the server decides what that code means" is how F6.4
is a property of one screen rather than a guard on three navigations. The dashboard path does
pass a param; the rail path does not; the screen was already built for both. The proof was
already in the tree: `TakeExamInteractionTest.openTakeExam` navigates with no params at all and
the flow test asserts the code screen is what she lands on. That method now carries a comment
saying it is U-1's evidence, so the next reader does not mistake it for an accident.

**`ExecutionMonitorView` — stood, badly, and now stands properly.** It did not throw:
`params.getLong("executionId", 0)` defaulted to zero and `session.start(0)` went to the wire.
The server refuses, and the screen renders the refusal correctly — as a red sentence under an
empty exam name, beside a "Started 0 / Handed in 0 / Timed out 0" counter row and a live **Add
time** button for a sitting that does not exist. That is not a crash; it is a mystery state,
which PRD §4.1 forbids by name.

So the paramless entry is now a designed state rather than a request for sitting zero:

- **no request is sent at all** — a screen that asks about nothing gets nothing back worth
  showing;
- the header, with its title, meta line, counters and extension control, is taken off;
- the centre is an `EmptyState`: **"Pick a sitting to watch" / "Releases lists every exam you
  have scheduled. Open one that is running and its Monitor button brings you back here with
  it." / [Open Releases]**;
- the previous sitting's rows are cleared and its session stopped, because the screen is built
  once and navigated to many times — her class still on screen under a title inviting her to
  pick one would be the same mystery state in a nicer coat.

Copy lives in `ExamCopy` (`MONITOR_NO_SITTING_TITLE` / `_HINT` / `_ACTION`), under the copy scan
that already forbids em dashes and dead ends on that screen's sentences.

`ExecutionMonitorView.PARAM_EXECUTION` replaces the `"executionId"` literal, and
`ReleaseManagerView` now spells the parameter through it.

### The third thing the swap uncovered: Live Monitor had no icon

`Icons.MONITOR` read `"mdomz-monitor"`, and **the material2 pack has no `MONITOR`**. `Icons.of`
catches the resolver's exception by design and renders an invisible spacer, so the failure was a
`WARN` line and a gap where a glyph should be — and nothing noticed, because the only two things
wearing it were a *disabled* rail item and the monitor's own "nobody has started yet" empty
state. Enabling the item would have put a rail entry with a blank icon in front of every teacher
and coordinator on demo day. It is now `"mdoal-desktop_windows"`, which exists, and the constant
carries the same note the `smart_toy` line above it already carries for the identical mistake.

**Two more of the same class are live and are *not* fixed here** — they belong to surfaces this
batch has no business touching, and they are one line each:

| Constant | Literal | Where it shows | Suggested |
|---|---|---|---|
| `Icons.LOGOUT` | `"mdoal-logout"` | the profile menu's sign-out item | `"mdoal-exit_to_app"` |
| `Icons.WARNING` | `"mdomz-warning_amber"` | wherever a warning chip or toast draws its glyph | `"mdomz-warning"` (verify against the pack) |

This is the third instance of one bug — a literal that does not exist, silently swallowed — and
it will keep happening while `Icons` has no test. **A guard is worth its ten lines:** scan the
public String constants the way `BotCopyTest` and `ExamCopyTest` scan copy, and assert each
resolves. Deliberately not written into this batch, because it is a third fix and would want its
own register entry; recommended as the first item of the next one.

### Files

| File | Change |
|---|---|
| `client/ui/shell/RoleNav.java` | two `soon(...)` → two `NavItem.of(Routes.…id(), …)`; `soon(...)` and the two placeholder ids deleted; class javadoc records that nothing is disabled any more |
| `client/features/exam/ExecutionMonitorView.java` | `PARAM_EXECUTION`; `onShow` branches on a missing execution; `showChooser()`; header and body held as fields so one can be taken off |
| `client/features/exam/ExamCopy.java` | three constants for the chooser |
| `client/features/release/ReleaseManagerView.java` | the nav parameter through the constant |
| `client/ui/components/Icons.java` | `MONITOR` onto a literal the pack actually has |

---

## U-2 — the bot course picker, and C-4's unreachable path

**The defect, in one line:** the chat opened `courses().get(0)` unconditionally, so a student in
three courses could reach one bot out of three — and the one C-4 behaviour that needs two
courses had no way to happen on screen.

`Routes.BOT_CHAT`'s javadoc has always described the intent: *"one route for one course at a
time; which course arrives as a nav parameter, so a student in three courses uses one screen
rather than three."* The nav parameter is real and the history screen and the notification
deep-link both supply it. **The rail item does not**, and the fallback for a missing one was the
first course in the sign-in result, with no way to choose another. Everything downstream
inherited that: Past conversations opened the first course's history, a new conversation started
against the first course's bot.

**What it cost, specifically.** C-4's notice fires when a student opens *another* course's bot
while sitting an exam. `maya.levi` is in courses **11, 21 and 22** in the seed, and 11 is first —
Algebra. Sitting the seeded live Algebra execution `2075`, the one bot the screen would open for
her was **Algebra 11's**, which is *locked*: the same-course lockout, a different rule with a
different outcome and a different sentence. The cross-course notice was therefore
**UI-unreachable**, which is exactly what the demo script's not-done table had written down as a
thing to say out loud: *"an Algebra student sitting Algebra can only reach the locked bot."*
Acceptance case 14.7 walked the cross-course half below the screen, against the Java bot, which
is why the behaviour is known-good and was still undemonstrable.

### The picker

A student in **more than one course** gets a course picker in the chat header. **One course and
there is no picker**: the row is hidden *and* unmanaged, so the header keeps the layout E16 gave
it for every student in the seed except Maya.

**Why a `ComboBox` and not `.hsts-segmented`.** The house rule, read off the client rather than
invented: every picker over a **data-driven list** here is a `ComboBox` — the principal's course
filter on Data, her subject picker on Reports. `.hsts-segmented` is used for **fixed
enumerations known at compile time**: `DataTab`, `ReportDimension`, a chart's scale, the theme
control. A course list is neither fixed nor bounded, and a student with seven courses would turn
segments into a second rail. The picker follows the Data screen's shape down to the `ListCell`
that renders the name.

**Switching.** The listener calls `startSessionFor(code)` — the same method the first entry
calls, so there is one path into a course's bot and not two that can drift. That method already
did the honest thing: a fresh `BotChatModel`, a fresh `BotChatSession`, an emptied conversation
view, a re-headed screen. It is `BotManagerView`'s per-course reconstruct pattern, and nothing
carries across, because nothing should:

- sessions and histories are **per course server-side** already, so the conversation she leaves
  is not lost, it is under Past conversations for that course;
- a **C-4 acknowledgement belongs to the sitting and the bot it was given for** (B-20's whole
  point), so carrying one across a course switch would be a lie the server would then be asked
  to act on.

A `selecting` flag keeps the programmatic fill in `onShow` from firing the listener — the same
guard `DataView` and `ReportsView` use. The picker is refreshed on every entry rather than built
once, because the screen is cached per session and the enrolment it reads belongs to whoever is
signed in now.

Copy is in `BotCopy` under the house rules: `COURSE_PICKER_LABEL` ("Course" — one noun, because
a bare dropdown reading "Databases 22" under a heading reading "Databases 22 study bot" looks
like a statement rather than a choice) and `COURSE_PICKER_TOOLTIP`, which says the thing that
stops a student thinking she has lost her thread: each course has its own bot and its own saved
conversations.

### Files

| File | Change |
|---|---|
| `client/features/bot/BotChatView.java` | `ComboBox<CourseRef>` + label row in the header; `refreshPicker(String)`; `courses()` helper; `CourseCell`; `coursePicker()` / `coursePickerRow()` for the TestFX flow |
| `client/features/bot/BotCopy.java` | the picker's label and tooltip |

---

## Test inventory

Ten new test methods across five classes, two existing assertions deliberately changed, one
fixture correction, and one existing test documented. Nothing was weakened: the one assertion
that was inverted was asserting something untrue.

| Class | Test | What it holds down |
|---|---|---|
| `RoleNavTest` | `questionBankIsLiveForTeachers` *(updated)* | the enabled-label lists for TEACHER, COORDINATOR and STUDENT now name Live Monitor and Take Exam, with the reason in the comment |
| `RoleNavTest` | `nothingIsDisabledAnywhere` *(new, ×4 roles)* | no rail carries a placeholder any more, asserted rather than assumed; the next one has to come here and say so |
| `RoleNavTest` | `theSwappedItemsPointAtTheRealRoutes` *(new)* | Take Exam is `"attempt"` and not `"exam.take"`; Live Monitor is `Routes.MONITOR.id()` for both teaching roles. The trap, pinned |
| `ExecutionMonitorInteractionTest` | `railEntryOffersTheChooser` *(new)* | navigate with no params → **no `EXECUTION_MONITOR_GET` goes out**, the chooser's title and hint are on screen, no rows; clicking **Open Releases** lands on `Routes.RELEASES` |
| `ExecutionMonitorInteractionTest` | `railEntryClearsThePreviousSitting` *(new)* | watch a sitting, come back from the rail: the three rows are gone and so is the header |
| `TakeExamInteractionTest` | `openTakeExam` *(documented)* | already navigated paramless; now says it is U-1's evidence and that nothing changed to make it pass |
| `UiSmokeTest` | `shellBootsAndTearsDown` *(inverted)* | it asserted `.nav-item.disabled` **is not empty** — "the not-yet-built rail items are visibly muted", a claim that had been false for four epics and was the only test in the tree that would have failed for U-1 had it been written the other way round. Now asserts the rail is non-empty and **nothing on it is disabled**, inverted rather than deleted so the next placeholder has to come here |
| `BotChatSessionTest` | `switchingCourseReachesTheIntegrityNotice` *(new)* | **the C-4 path through the picker.** Ask on 22, rebuild for 11 the way the picker does, ask again: the request names `"11"`, the notice comes back, the held question is hers, and the 22 model is untouched with no consent leaked |
| `BotChatSessionTest` | `acknowledgingAfterASwitchStaysOnTheNewCourse` *(new)* | confirming re-sends against `"11"` with `integrityAcknowledged=true`, and the Algebra conversation gets its own session id |
| `BotInteractionTest` | `oneCourseKeepsTheHeaderItHad` *(new)* | one course → the picker row is invisible and unmanaged |
| `BotInteractionTest` | `theCoursePickerSwitchesBots` *(new)* | **real robot input**: `clickOn` the picker, `clickOn("Algebra 11")`, heading follows, then the next ask goes out with `courseCode = "11"` and `sessionId = null` |
| `BotInteractionTest` | `switchingClearsTheConversation` *(new)* | the Databases thread is not sitting in the Algebra bot's window, and the fresh conversation shows the empty state |
| `BotCopyTest` | `coursePickerCopy` *(new)* | the label is one noun and the tooltip says what switching does |

**Fixture correction:** `BotInteractionTest.MAYA_ENROLLED` gives Maya the three courses the seed
gives her (`SEED_CONTENT`, student 11: 11, 21, 22). Every fixture in that file gave her one,
which is precisely why nothing there caught this — with one course, `courses().get(0)` *is*
correct. The single-course `MAYA` stays, because "one course and nothing changes" is now a claim
that needs a test of its own.

**Why the C-4 notice is proved at session level and not through the robot.** `BotChatView`
answers `NEEDS_ACKNOWLEDGEMENT` with a modal `WarnConfirm`, which blocks the FX thread by design
— the same reason `ExecutionMonitorInteractionTest` proves the extension deterministically in
its session test rather than through the dialog. The interaction tests therefore drive the
picker up to and including the ask that names the switched-to course, and the notice that ask
provokes is asserted in `BotChatSessionTest`. Between them the whole path is covered and nothing
hangs.

**Coverage note:** `BotChatView` and `ExecutionMonitorView` are both on the jacoco view-exclusion
list, so neither fix moves the bundle ratio in either direction; the new session-level tests add
to it.

---

## Documents

| File | Change |
|---|---|
| `docs/UI-REGISTER.md` | U-1 and U-2, its first two entries, attributed to the demo-script honesty pass, `DONE` pending on-screen verification |
| `docs/ACCEPTANCE_TESTS.md` case **1.3** | dated amendment: "Take Exam remains greyed pending E10's screen" was already untrue when written; both rails now match the expected column with nothing left over |
| `docs/ACCEPTANCE_TESTS.md` case **14.7** | dated amendment on half (b): it was walked below the screen because it could not be walked on one, and now can be. No `B-n` — the walked result did not change, only whether a human can reach it |
| `docs/DEMO_SCRIPT.md` | **not touched** — it lives on `hsts-e15-wt`. The four stale passages are listed at the end of this report |

`docs/TRACEABILITY.md` needed nothing: F1.2, F6.1, F7.2 and F12.5 were all already **LIVE**, which
is the whole point — the requirement rows were right and the rail was wrong.

---

## Verify

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_batchc ./mvnw -B clean verify
```

**BUILD SUCCESS**, 2026-08-26 21:35 +03:00.

| | |
|---|---|
| Tests | **6462 run, 0 failures, 0 errors, 0 skipped** |
| Jacoco `BUNDLE` INSTRUCTION | **97.81 %** covered — 77 379 of 79 109, gate `0.90` |
| Branch | 90.57 % · Line 97.77 % · Method 97.95 % · Class 99.54 % (645 of 648) |
| Coverage check | *All coverage checks have been met.* |
| Wall clock | 16:08 min |

`DeepSeekProviderTest` did not flake on either full run.

**One honest note about the run, because the brief asked for numbers and this is what happened
to produce them.** The gate was run three times.

1. **Red, correctly.** `UiSmokeTest.shellBootsAndTearsDown` failed on the assertion that
   `.nav-item.disabled` is not empty — the one test in the tree that was pinning U-1's defect
   in place. That is the failure the fix is *supposed* to cause, and it is the reason the
   inversion in the test inventory above is a change rather than an addition. Everything else
   was green.
2. **Red, spuriously.** After the inversion and the icon fix, a run failed with **3 failures and
   29 errors**, every one of them a `NoClassDefFoundError` for a *test-helper nested class* —
   `SeedLoaderContract$1`, `DiscoveryResponderTest$FakeTransport$Sent`, `InMemoryApprovalStore`,
   `RecordingNotifier` — in `SeedLoader*Test`, `Discovery*Test` and `*ApprovalStore*Test`. Those
   suites do not touch anything in this batch and all of them passed in run 1. A partial
   `test-compile` on the `/mnt/c` NTFS mount is the plausible cause and the symptom fits it
   exactly: class files that the compiler reported writing and the runtime could not find.
3. **Green**, the numbers above, from an untouched `clean verify` on the same tree.

Recorded rather than quietly rerun, because "it passed the third time" is only worth reading if
the first two are explained.

---

## For the lead, when the demo script is ported

`DEMO_SCRIPT.md` lives on the `hsts-e15-wt` worktree and **this batch did not touch it**, per the
brief. Its two-findings paragraph is resolved and four passages go stale on the port. They are
listed here so the refresh is a read rather than a hunt:

| Line | Passage | What it now says |
|---|---|---|
| ~107 | act 2.1 **If it goes wrong**: *"two rail items read 'Arrives with E10 / E11' … the rail label is stale, the screens are not"* | delete the risk line. The rail act 2.1 reads out is now literally what is on screen |
| ~247 | act 4.1 **Note**: *"the rail's Take Exam item is greyed. The dashboard code box is the live path"* | rewrite to "Take Exam on her rail and the dashboard code box are the same screen; the notification deep-links to it too" — the note is now about *three* doors, not about one being shut |
| ~287 | act 5.2, the amber-slot line: *"The seeded student's own course bot is the one she can reach from her rail, so the flag is demonstrated below the screen in acceptance case 14.7 rather than staged here"* | **stageable live.** Maya has 11, 21 and 22; sitting the 22 exam she can pick Algebra 11 in the chat header and take the notice on screen. Worth a step of its own — it is the most demonstrable requirement in C-4 and it has never been shown |
| ~525 | not-done table, row *"Raise the C-4 flag from a student's screen"* | **retire the row.** Its stated cause — "the bot chat opens the student's first course by code and offers no picker" — is what U-2 fixed |

Act 5.6 (the same-course lockout on her rail's Study Bot) is unaffected: that is the *locked*
path, it still works exactly as written, and it is now the contrast the cross-course step plays
against rather than the only bot moment in the script.

Both entries in `docs/UI-REGISTER.md` are `DONE` and neither is `VERIFIED`. Naji sees them on
screen first.
