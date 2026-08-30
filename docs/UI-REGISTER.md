# UI register — the lead's manual-test notes, turned into work

**The workflow (agreed 2026-08-25, post-extension):** Naji tests by hand and reports notes in
his own words, in any form. Every note becomes a numbered entry here the same day: his words
verbatim, the restated issue, the owning surface, and a status. Nothing is lost and nothing
stays a complaint. When a testing session ends, the NEW entries get a ruling round (fix /
defer / won't-fix — Naji decides, the lead lane recommends); ruled fixes batch into
agent-built waves, verified like every wave before them, and Naji re-verifies each fix on
screen before it closes.

**Priority rule:** present-readiness and requirement compliance outrank polish. Acceptance
scenarios still unwalked come before any COSMETIC entry; a FUNCTIONAL entry found while
testing jumps the queue.

**Statuses:** `NEW` (logged, unruled) → `RULED: <fix|defer|wont-fix>` → `IN WORK` (in a wave)
→ `DONE` (merged, verify green) → `VERIFIED` (Naji saw it fixed on screen). Entries are
never deleted; a wont-fix keeps its reasoning.

**Numbering:** `U-n`, continuing forever. Kind: `FUNCTIONAL` (wrong/broken behavior),
`COSMETIC` (looks/feel), `COPY` (wording), `FLOW` (navigation/ordering of steps).

Related registers: `docs/reports/lead/MANUAL-PASS-1.md` (F-1..F-14, the 2026-08-23 pass —
all resolved or waved), ACCEPTANCE_TESTS.md's `B-n` (bugs with acceptance impact — a
FUNCTIONAL entry here that breaks an acceptance case gets a B-number there too).

---

## Open entries

All four are `DONE` and none is `VERIFIED`, which is what keeps them here: an entry closes when
Naji has seen it fixed on screen, not when the build is green.

### U-1 · FUNCTIONAL · two rail items named a live screen and could not be pressed

**Found by:** the **demo-script honesty pass**, 2026-08-26, not a Naji session. The script's
rule 1 is "no step routes through an open gap", so writing act 2.1 meant reading the rail
against the route table, and the two disagreed. This register's numbering starts here because
it is the first entry of any kind, whatever found it — a finding does not get a lower status
for having come out of a document rather than a keyboard.

**In the finder's words (DEMO_SCRIPT.md, act 2.1 risk line and act 4.1 note):** "two rail items
read 'Arrives with E10 / E11'. Both screens are live and both are reached from elsewhere" · "the
rail's Take Exam item is greyed. The dashboard code box is the live path".

**The issue, restated:** `RoleNav` still carried `soon(ROUTE_TAKE_EXAM, "Take Exam", …, 10)` on
the student rail and `soon(ROUTE_MONITOR, "Live Monitor", …, 11)` on the teaching rail.
`Routes.TAKE_EXAM` has existed since E10 and `Routes.MONITOR` since E11; both are registered by
`SessionRoutes` for exactly these roles and both have screens. So the one place a user is
trained to look for a feature was the one place that denied it existed, while notifications,
the dashboard code card and the Releases rows all opened it. A live feature behind a dead label
is a feature nobody can find.

**Owning surface:** `client/ui/shell/RoleNav`, and `client/features/exam/ExecutionMonitorView`
for the entry it now has to answer.

**The trap, recorded because it is the interesting half:** the placeholder ids were not the live
ids. `ROUTE_TAKE_EXAM` read `"exam.take"`; the live route reads `"attempt"`, because that is what
`NotificationCatalog.ROUTE_ATTEMPT` spells and what an "extra time added" notification navigates
to. Promoting the string beside the label instead of swapping onto the route constant would have
produced a rail item that throws on click — `Navigator.navigate` refuses an unregistered id — so
the fix is the SWAP the file itself documents for `ROUTE_REPORTS` and `ROUTE_GRADING`.

**Paramless entry, checked before the swap and not after:** Take Exam from the rail carries no
code and `TakeExamView.onShow` has always built the code screen either way, treating the
dashboard's pre-validated code as an `ifPresent` pre-fill; nothing there changed. Live Monitor
from the rail carries no execution and asked for execution zero, which the server refuses — a
red sentence under an empty title. It now renders a designed chooser instead ("Pick a sitting to
watch", with a button to Releases) and sends nothing.

**Found while fixing it, and fixed with it:** `Icons.MONITOR` read `"mdomz-monitor"`, a literal
the material2 pack does not have. `Icons.of` swallows an unresolvable literal into a blank
spacer by design, so nothing failed — a disabled rail item with no glyph looks like a disabled
rail item. Enabling it would have shipped a rail entry with a hole in it. Now
`"mdoal-desktop_windows"`. Two more constants in that file are broken the same way and are
**not** fixed here; see `BATCH-C.md`, which also recommends the ten-line scan that would end the
class.

**Status:** `DONE` — batch C, verify green — pending Naji's on-screen verification.

### U-2 · FUNCTIONAL · the study bot chat could only ever open one course's bot

**Found by:** the same **demo-script honesty pass**, 2026-08-26. It is the last row of the
script's "what this script deliberately does not do" table, which is where a UI limit gets
written down as something to say out loud rather than as something to fix.

**In the finder's words (DEMO_SCRIPT.md, act 5.2 and the not-done table):** "The seeded student's
own course bot is the one she can reach from her rail" · "The bot chat opens the student's first
course by code and offers no picker, so an Algebra student sitting Algebra can only reach the
locked bot".

**The issue, restated:** `BotChatView.onShow` resolved a missing `courseCode` to
`signedInUser().courses().get(0)` and offered no way to choose another. `Routes.BOT_CHAT`'s own
javadoc says "one route for one course at a time; which course arrives as a nav parameter" — the
rail item has no parameter to give it, so for a multi-course student the route delivered one bot
out of three. **This is what made C-4's cross-course path UI-unreachable:** the integrity notice
fires when a student opens *another* course's bot while sitting an exam, and there was no other
course's bot to open. The seed's `maya.levi` is in three courses.

**Owning surface:** `client/features/bot/BotChatView`, with copy in `BotCopy`.

**Fix:** a course picker in the chat header for a student in more than one course; one course and
there is no picker and nothing changes. Switching rebuilds the session and model for the chosen
course, which is `BotManagerView`'s pattern and is honest — sessions and histories are per course
server-side, and a C-4 acknowledgement belongs to the sitting and the bot it was given for. A
`ComboBox`, not `.hsts-segmented`: every picker over a data-driven list in this client is a
`ComboBox` (the principal's course filter on Data, her subject picker on Reports) and segments
are reserved for fixed enumerations known at compile time.

**Acceptance impact:** the script's not-done row is retired and case 14.7's below-the-screen
proof now has a screen. No `B-n` number: nothing about the acceptance case's *result* changed,
only whether a human can reach it.

**Status:** `DONE` — batch C, verify green — pending Naji's on-screen verification.

### U-3 · COSMETIC · two icon constants named glyphs that do not exist, and nothing could tell

**Found by:** **batch C**, 2026-08-26, while fixing U-1 — and then **not fixed by it**, on
purpose. Batch C's brief was two UI paths; a third fix on a third surface would have arrived with
no register entry of its own, so it was written up as a recommendation with the two literals
named and left for the next batch. Fixed by **batch D** the same day. Logged here rather than
folded into U-1 because it is a different surface and a different class of defect.

**In the finder's words (BATCH-C.md):** "This is the third instance of one bug — a literal that
does not exist, silently swallowed — and it will keep happening while `Icons` has no test. **A
guard is worth its ten lines.**"

**The issue, restated:** `Icons.of` catches the icon resolver's exception and renders an
invisible spacer of the right size. That is correct for a *data-driven* literal — a typo in a
`NavItem` config must not throw in the middle of building the shell — and quietly wrong for the
class's own constants, because **a constant is a claim that a glyph exists** and the swallow
turns a false claim into a `WARN` line nobody reads and a hole in a layout nobody notices. Three
had already happened: `BOT`/`smart_toy` caught by hand in E16, `MONITOR`/`mdomz-monitor` caught
by U-1 (where enabling the rail item would have put a blank icon in front of every teacher on
demo day), and two more found live in the same read:

| Constant | Was | Where it shows | Now |
|---|---|---|---|
| `Icons.LOGOUT` | `"mdoal-logout"` | the profile menu's sign-out item | `"mdoal-exit_to_app"` — the pack has `LOGIN` and `EXIT_TO_APP` and has never had `LOGOUT` |
| `Icons.WARNING` | `"mdomz-warning_amber"` | every warning chip and toast | `"mdomz-warning"` — the pack has `WARNING`; `WARNING_AMBER` postdates it |

**Owning surface:** `client/ui/components/Icons`, and `IconsTest`, which is the actual fix.

**Why the guard needed care, which is the part worth reading:** the obvious check —
`IkonResolver.getInstance().resolve(literal)` — **passes for both broken literals**. It answers
on the `mdoal-` / `mdomz-` prefix alone, hands back the pack's handler, and says nothing about
whether the pack has that name. The claim only has teeth when the returned handler is then asked
for the glyph, which is what `FontIcon`'s constructor does and what fails at runtime. So the test
resolves twice. It scans rather than names, so a constant added next month is covered the moment
it is written, and it needs no JavaFX toolkit because it never builds a node.

**The scan caught nothing else:** 29 constants, 27 already correct.

**Acceptance impact:** none directly; it gets a **B-38** number in `ACCEPTANCE_TESTS.md`
attributed to batch C's report, because it is a defect a grader could see on stage rather than a
polish note. `Icons.REFRESH` is a separate, opposite problem — a *valid* literal mounted on
nothing — and stays open as **B-33**.

**Status:** `DONE` — batch D, verify green — pending Naji's on-screen verification. What to look
at: the profile menu's sign-out item, and any warning chip or toast.

### U-4 · COPY · the connect screen could print a Java exception class name

**Found by:** the **S18–S21 acceptance walk**, case 21.3, 2026-08-26. Filed there as **B-37**,
Medium. It is here as well, and deliberately: it is functional copy on the first screen anyone
sees, which is this register's business as much as the acceptance table's.

**In the finder's words (ACCEPT-S18-S21.md, case 21.3):** "a throwable with no message —
`SocketTimeoutException`, for one — produces *'Could not reach 192.168.1.5:5555
(SocketTimeoutException). Check the server is running…'* on the first screen an examiner sees."

**The issue, restated:** `ConnectView.onFailed` computed
`cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()` and handed
it to `ConnectFlow.afterFailedConnect`, which folded it into the sentence in brackets. Even the
non-null branch was a JDK string rather than product copy (*"Connection refused"*, or a bare
hostname from `UnknownHostException`). PRD §4.1 says a user never meets an error code or a stack
trace, and a Java class name is one.

**What makes it a defect and not a house style:** the product already knows the rule and this
screen was the exception. The reconnect banner beside it is clean —
`ReconnectBanner.showDisconnected(String serverLabel)` takes no detail parameter at all, and
`ConnectionLostEvent`'s own javadoc says the technical reason is *"never shown as the primary
message"*. That banner is the house reference and this screen was not following it.

**Owning surface:** `client/features/connect/ConnectFlow` (where the copy and the decision live)
and `ConnectView` (which now only logs).

**Fix:** `afterFailedConnect` takes the `Throwable` itself, so **there is no longer a parameter a
caller could pass a JDK string to** — the leak is unrepresentable rather than merely repaired.
`ConnectFlow.reasonFor` walks the cause chain and maps the four causes the product has words for
to product sentences (refused → *"Nothing is listening on that address."*, timeout → *"That
address did not answer."*, unknown host → *"That name could not be found on this network."*, no
route → *"That address cannot be reached from this network."*), and answers `""` for everything
else. **The brackets are gone entirely:** a recognised cause gets a sentence of its own between
the address and the instruction, an unrecognised one leaves the message two sentences long. The
throwable is logged.

**Status:** `DONE` — batch D, verify green — pending Naji's on-screen verification. What to look
at: start the client with no server running and read the sentence; then point it at an
unreachable address and read that one.

---

---

## Manual round 1 (2026-08-28) — Naji's notes, `docs/manual-round-1-notes.txt`

The first full walk with `MANUAL_TEST_ONE_MACHINE.md`. It stopped short on blockers, which is what a
first walk is for. Every line of the notes file is an entry below; the two that were
already registered elsewhere are cross-referenced rather than duplicated (bot locked after
early close = **B-47**, fixed; national IDs = M-2/B-40, extended here as U-15). Waves: **A**
navigation, **B** visuals, **C** flows, **D** wire; each agent-built in its own worktree,
lead-verified, one batch commit.

### U-5 · FLOW · the manual connect form has no way back
**In Naji's words:** "the manual connect window to the server from the client side (UI) doesn't have a go back button, which it should"
**Restated:** `ConnectView`'s manual card can be reached from the picker and cannot return to it; the only link offered is "Look for servers again".
**Surface:** `client/features/connect/ConnectView`. **Ruling:** fix, wave A — a "Back to the server list" link that returns to the picker with the decision the view already keeps.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-6 · FUNCTIONAL · the login screen still says Connected after the server dies
**In Naji's words:** "when the server is down, the login window (UI) still doesn't register it and still says it's connected with the green button"
**Restated:** `LoginView` computes its status row once at build from `client.isConnectionOpen()` and subscribes to nothing; `ConnectionLostEvent` is posted on the bus and the shell's reconnect banner reacts, the login screen does not.
**Surface:** `client/features/login/LoginView`. **Ruling:** fix, wave C — subscribe; on loss the chip flips, the sign-in button disables, a Reconnect link leads to the connect screen; status re-read on every show.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-7 · COSMETIC · the My Grades ring is a little off
**In Naji's words:** "the my grades page looks nice but the semi-filled circle thing is a little off (not high priority)"
**Restated:** `ProgressRing` draws the fill arc with round line caps, so each end overhangs its angle by half a stroke; a partial value reads slightly longer than the score and a tiny value shows a dot.
**Surface:** `client/ui/components/ProgressRing` + `RingGeometry`. **Ruling:** fix, wave B (Low) — subtract the cap angle at each end for 0 < value < 100.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-8 · FLOW · a Back control on every screen that is not on the rail
**In Naji's words:** "grades -> open exam, no go back button" · "grades -> open exam -> print mode, no go back button" · "all print layouts have no go back button" · "IN GENERAL, KEEP A GO BACK BUTTON IN ALL SCREENS THAT AREN'T ON THE SIDE-BAR" · "AGAIN ADD GO BACK BUTTONS TO EVERY SCREEN"
**Restated:** six screens hand-roll a `BackLink`, the rest of the non-rail screens have nothing, and print mode is worse: `.results-print .toggle-button { visibility: hidden }` hides the very toggle that turns print mode off, so print view has no exit at all.
**Surface:** `client/ui/shell/AppShell` (the navbar), `hsts.css`, the results and checked-form views. **Ruling:** fix, wave A, **systemically**: the shell renders one Back control for any route that is not a rail item (back stack, else the role's home), the six hand-rolled links go so no screen carries two, and print mode gets an always-visible "Exit print view".
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-9 · FUNCTIONAL · the opened exam shows no teacher name
**In Naji's words:** "missing teacher's name"
**Restated:** My Grades → open exam is `CheckedFormView` over `common.dto.grading.CheckedForm`, which has no teacher field at all; nothing on that wire could carry the name.
**Surface:** GRADING wire contract (frozen) → amendment **A6**: `CheckedForm.teacherName`, the execution's releasing teacher; `CheckedFormService`, `CheckedFormView`. **Ruling:** fix, wave D.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-10 · FLOW · from the dashboard, Take Exam asks for the code again, and its Continue is dead
**In Naji's words:** "from dashboard -> take exams, it opens the take exams page and asks for the exam code once again, it should skip it (it's a requirement but we want to change it, both in the docs and the code), also in it's current state, we have to modify the code for it to light up the continue button and allow us to move forward - verdict: fix the continue thing and modify it to be a confirmation instead"
**Restated:** two things. (1) BUG: `ExamEntryView.prefillCode` only sets the field's text; a field that already holds the code from an earlier visit fires no listener after `reset()` emptied the session, so `canContinue()` stays false — the exact "have to modify the code to light it up". (2) DESIGN: the second prompt was F6.4's one-screen guard; Naji's ruling makes it a **confirmation**: read-only code, "Confirm and continue", a "Use a different code" link.
**Surface:** `ExamEntrySession`, `ExamEntryView`, `ExamCopy`, `TakeExamView.onShow`. Docs already changed: PRD F6.1 amendment, DEMO_SCRIPT 5.1, and this closes **B-45**. **Ruling:** fix, wave C.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-11 · COSMETIC · every modal has a "weird shadow"
**In Naji's words:** "when opening up the handing out exam modal, the same weird shadow thing appears, get rid of it or fix it like you did previously" · "dashboard modals has the same weird shadow thing" · "almost all modals if not all have weird shadows, fix it or get rid of it"
**Restated:** every modal is `WarnConfirm`: a transparent stage sized to the dialog plus a 40px scrim margin, and the scrim carries `-hsts-scrim` (42% dark). The dim therefore paints as a dark box hugging the dialog instead of dimming the window. The "previous" fix (c2b9c0f, 2026-08-19) softened the drop-shadow recipes; this is the scrim, not the shadow, which is why softening did not reach it.
**Surface:** `client/ui/components/WarnConfirm`. **Ruling:** fix, wave B — the modal stage takes the owner window's bounds so the scrim dims the whole window with the dialog centered; the soft dialog shadow stays.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-12 · FUNCTIONAL · grading: students look like they are loading and never show
**In Naji's words:** "grading for teachers: students look like they're loading but never show"
**Restated:** `GradingQueueView.render` only calls `table.setItems` when the rows differ from what the table holds; when an execution answers with zero rows, empty equals empty, `setItems` is never called and the `DataTable` never leaves its initial skeleton. Whether the execution should have had rows is a second question that needs the account used (asked).
**Surface:** `client/features/grading/GradingQueueView`. **Ruling:** fix, wave C; filed as **B-48** too because it breaks scenario 8 on screen.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-13 · COSMETIC · the question bank table leaves its width unused
**In Naji's words:** "question bank: the table doesn't look good, the cells (columns) ares too packed leaving a lot of empty space unused, we need better spacing for the table"
**Restated:** `DataTable` sets no column resize policy, so every table's columns sit at pref width and the remainder of the row is dead space; the bank is where it shows most.
**Surface:** `client/ui/components/DataTable`. **Ruling:** fix, wave B — a constrained proportional resize policy on the component, so every table fills its width and `columnWidths` become the proportions.
**Status:** `DONE` — wave of 2026-08-28, verify green (6660) — pending Naji's on-screen verification (MANUAL_ROUND v2 §1).

### U-14 · FUNCTIONAL · "the number of study bots isn't limited for teachers"
**In Naji's words:** "BIG BUG: the number of study bots isn't limited for teachers, the teacher can create and manage bots, not just one bot"
**Restated:** PRD F12.1 is one bot per course (S-30). The server holds that structurally: `bots.course` is `UNIQUE` (V6) and `BOT_CREATE` is idempotent — a second create hands back the existing bot. So the database cannot hold two bots for a course. What the screen showed is not yet known: a teacher of two courses (`dana.cohen`) legitimately sees two bots, one per course; or the manager kept offering "Create the study bot" after a create.
**Surface:** `client/features/bot/BotManagerView` (suspected). **Ruling:** `NEW` — the exact steps and what was on screen are needed before this is fixed or closed as design.
**Status:** `ANSWERED BY U-26` — manual round 3 (2026-08-29) supplied the steps: a two-course teacher was seeing one card because the screen managed one course at a time. The limit was never missing; the list was.

### U-15 · COPY · national IDs in the demo files
**In Naji's words:** "The IDs for the students aren't in the demo file (need to be added for easier testing) - or redo the demo"
**Restated:** M-2 put them in DEMO_ACCOUNTS.md and DEMO_SCRIPT 5.2; DEMO_DAY.md and the manual round's own §0 are where the tester looked.
**Surface:** docs. **Fix:** DEMO_DAY.md §sign-in and the accounts-sheet line now carry both IDs; MANUAL_ROUND §0's account table carries them.
**Status:** `DONE`.

### U-16 · FLOW · a better, time-aware manual testing document
**In Naji's words:** "NEED A BETTER MORE ORGANIZED AND TIME AWARE MANUAL TESTING DOCUMENT"
**Restated:** the first walk stalled on blockers with no way to see what was still worth doing; the doc needs minute budgets per section, blockers first, and a round tracker.
**Surface:** `docs/MANUAL_TEST_ONE_MACHINE.md`. **Ruling:** rewrite as v2 after the wave lands, so its expectations describe the fixed build.
**Status:** `DONE` — MANUAL_TEST_ONE_MACHINE.md v2 (time plan, round tracker, re-verdict block first, bot before the exam).

---

## Manual round 2 (2026-08-29) — Naji's notes, `docs/manual-round-2-notes.txt`

Run against 19a2ab1 + d77bf3c. "Overall seeing a big improvement" and "gj on the go back";
five new entries, one reopening.

### U-7 · reopened · the ring's fill is off-centre, not just long
**In Naji's words:** "the average card has a semi filled circle that represents the grade average, the filling of the circle's borders is off center, needs urgent way to fix or a replacement"
**Restated:** round 1's fix trimmed the cap overhang; the visible defect was a different one. `ProgressRing` was a `StackPane`, which centres each child by its own bounds: a full-circle track and a partial arc have different bounds, so the fill was centred on itself and drifted off the track as the score changed.
**Fix:** `ProgressRing` is a `Pane`; both arcs sit on one explicit centre (`DIAMETER / 2`), only the label is centred by hand.
**Status:** `DONE` — pending eyes.

### U-17 · FUNCTIONAL · login stays stuck on "could not reach the server" after the server comes back
**In Naji's words:** "after disconnecting the server, and reconnecting the sign in still show that could not reach the server error and doesn't allow a sign-in, had to open a new client window for it to work"
**Restated:** the loss is now detected (U-6). The recovery path is Reconnect → connect screen → back to Login; something in that path left the screen on the connect refusal with sign-in disabled until a fresh client was started. The exact sequence decides the fix (was Reconnect pressed while the server was still starting? did the connect screen's "Look for servers again" get used?).
**Surface:** `LoginView` / `ConnectView` / `ConnectWiring` / `RequestDispatcher`. **The clicks (Naji, round 2 follow-up):** server down → manual reconnect → the status said Connected (true, the server was up) → Sign in still refused; same with auto-reconnect; all before login.
**Diagnosis:** screens are cached and built once (`ScreenCache`), and `LoginView` builds its `LoginSession` over the `RequestDispatcher` it holds at build time, which wraps one fixed socket. A reconnect through the connect screen creates a **new** client and a **new** dispatcher and gives them to the manager; the cached login screen keeps the dead dispatcher. The status row reads the client fresh from the manager (Connected, true) and Sign in sends LOGIN down the dead socket (refused, also true). Every cached screen carries the same trap after any reconnect on that path.
**Ruling:** fix at the seam — `RequestDispatcher` becomes rebindable (`rebind(IClientConnection)`: pending requests failed, push listener kept), `ConnectWiring` rebinds the manager's existing dispatcher on reconnect instead of replacing it, and the login screen re-reads its state on show. Wave 2 agent E.
**Fix:** `RequestDispatcher.rebind(IClientConnection)` (pending futures failed with a replacement cause, push listener and request ids kept); `ConnectWiring.forEndpoint(endpoint, bus, existingDispatcher)` rebinds the manager's dispatcher on every reconnect, creating one only for the first connection of the process; exactly one `new RequestDispatcher(` site remains in production code and it is behind that guard. Pinned by `UiSmokeTest.loginUsesTheConnectionItReconnectedOn`, which failed on the old code precisely on "the credentials go to the server she is actually connected to" while the two assertions before it (screen not rebuilt, status reads Connected) passed: the defect exactly as reported.
**Status:** `DONE` — pending eyes: stop the server on the login screen, start it, Reconnect, sign in in the same window.

### U-18 · FUNCTIONAL · the grade cards show neither the teacher's name nor the teacher's note
**In Naji's words:** "the cards show: a grade, name of the exam, a number on top that, a date below the grade, and a passed status in the corner, clean UI, however, no teacher name and no teacher comment"
**Restated:** `StudentGradeRow.teacherComment` is on the wire (A3) and the card never rendered it; the teacher's name is not on that wire at all.
**Surface:** `MyGradesView` card; GRADING wire amendment **A7** (`StudentGradeRow.teacherName`, releasing teacher, as A6). **Ruling:** fix — wave 2 agent D2.
**Status:** `DONE` — verify green (6688) — pending eyes.

### U-19 · FLOW · Back on the take-exam sub-steps
**In Naji's words:** "on the confirm it is you, on the take exam child screens I want a go back button also, not just the confirm it is you, I thing all child screens should have it probably"
**Restated:** Take Exam is a rail route, so the shell's navbar Back (U-8) does not appear there; its sub-steps (code / confirmation, identity, handed-in) have no way back of their own.
**Surface:** `ExamEntrySession` (`backToCode`), `ExamEntryView` (Back on the identity step, Back to my dashboard on the code step and the dead end). The Time Up / Submitted takeovers keep their single button by design (F6.4). **Ruling:** fix — wave 2 agent C2.
**Status:** `DONE` — verify green (6688) — pending eyes.

### U-20 · COPY · the login screen's behaviour with the server down is the wanted one; docs must say so
**In Naji's words:** "when the server is down it shows disconnected and doesn't enable sign in, which I like better than the old 1.2 just make sure to update the docs to match it (manual round and other requirements docs)"
**Fix:** PRD F1.5 carries a dated note; the manual documents describe the disabled sign-in and the Reconnect link as the expected state.
**Status:** `DONE`.

### U-21 · FLOW · the manual round document, redone as two
**In Naji's words:** "the manual round md for checking is horrible short, and to me doesn't look like it changed ... gimme two documents now and delete the old manual round file ... one for one machine testing, the other is for two machines, the testing MUST visit every screen and sample every situation possible ... every action we can take of every type of user ... all of the requirements from the pdf ... we wanna test adding new stuff from the client side"
**Restated:** the file on disk at the time was v3 (d77bf3c, sixteen sections, the 2-minute exam, the coverage map) and the notes quote v2's headings, so a stale copy was read; the ask is taken as written anyway: two documents, per-role action inventories, creation from the client instead of reseeding, the PDF's requirements as the floor.
**Fix:** `docs/MANUAL_TEST_ONE_MACHINE.md` + `docs/MANUAL_TEST_TWO_MACHINES.md`; `MANUAL_TEST_ONE_MACHINE.md` deleted.
**Status:** `DONE` — the two files landed in 3ad08db and were rewritten walkthrough-first under U-23 (6da1a49).

---
## Closed entries

*(none yet — an entry closes when Naji has seen it fixed on screen)*
### U-22 · FUNCTIONAL · the client warns of an "unusual server" after every rebuild
**In Naji's words:** "for some reason when I open the client it's showing me that I'm connecting to an unusual server, which I think isn't true but not sure"
**Restated:** the server's identity (`server-id.properties`) is kept beside the jar, which on a dev machine is `target\`; `clean package` empties `target\`, so every rebuild gave the server a new fingerprint and every client that had pinned the old one showed the mismatch warning ("now identifies itself as … but this computer connected to … before"). The warning was honest; the cause was the build.
**Surface:** `ServerMain.configDirectory`. **Fix:** under a directory named `target` the id lives one level up (the project root), which a clean does not touch; the deliverable's beside-the-jar rule is unchanged. `ServerConfigDirectoryTest`.
**Status:** `DONE` — pending eyes: rebuild twice, no warning the second time.

### U-23 · FLOW · the manual files must be a human walkthrough, not an inventory
**In Naji's words:** "we want these files to ALSO contain a walk-through with a guide, passwords, usernames, ids, what to do exactly to check everything, with [ ] to fill with X for things we checked, something human friendly not just machine friendly"
**Fix:** both files rewritten walkthrough-first: who to be, what to click, what to see, a box per step, credentials and ids inline every time; the inventories, situations, interaction matrix and requirement map moved to appendices as the backstop.
**Status:** `DONE`.

## Manual round 3 (2026-08-29) — the teacher's basics, `docs/manual-round-3-notes.txt`

### U-24 · COSMETIC · every modal card is "unreasonably long, not centered"
**In Naji's words:** "the modal card doesn't look nice it's unreasonably long not centered" · "same issue with the logout modal being way too long for no reason, looks like you did it for all modals"
**Restated:** U-11's fix sized the modal stage to the owner window; the scrim is a `StackPane`, which hands its child the whole area, so the card stretched to the window's full height.
**Fix:** `ModalHost` centres the card and pins its height to its preferred height; width still follows the stylesheet's min/max so long text wraps.
**Status:** `DONE` — pending eyes.

### U-25 · FUNCTIONAL · "the login server thing didn't get fixed" (the unusual-server warning on a new build)
**In Naji's words:** "my theory is that it's not terminal related but simply the first time we open it just has this modal opening since it considers it new since it's a new build"
**Restated:** the theory is right and is U-22, committed in 6da1a49 after that round was run: the server id lived in `target\` and was reborn on every clean build. After pulling 6da1a49 it warns at most once more (the id moves to the project root), then never on a rebuild.
**Status:** `DONE` in 6da1a49 — pending eyes on the build after the pull.

### U-26 · FUNCTIONAL · the Study Bot screen looks like a teacher gets one bot in total
**In Naji's words:** "the teacher's study bot screen shows one bot, I teach two courses" · "I want a list of bots to manage"
**Restated:** U-14 asked which of two things was on screen; manual round 3 answered it. `BotManagerView` managed **one** course's bot and took the course from a nav parameter, falling back to `courses().get(0)`. So `dana.cohen` (Algebra 11, Calculus 12) opened the screen, saw a single card, and read the product as one bot per teacher. The rule she was reading against is real and unchanged: **one study bot per course** (PRD F12.1, S-30), held structurally by `UNIQUE(course)` in V6 and by an idempotent `BOT_CREATE`. Nothing was wrong with the data; the screen never showed her the second bot.
**Surface:** `client/features/bot/BotManagerView`, with `BotManagerListSession` + `BotCourseSummary` new beside it and copy in `BotCopy`.
**Ruling (lead, 2026-08-29):** keep one bot per course; make the manager a **LIST**. Master and detail in `ExamListView`'s shape: a card per taught course on the left carrying the course code and name, the bot's name or "No study bot yet", an Active / Inactive chip, its source count and a **Manage** button (or **Create the study bot** for a course without one); the selected course's existing single-bot page on the right. `BotCopy.LIST_SUBTITLE` — *"One study bot per course. Co-teachers share it."* — states the rule in words on the screen that now shows more than one bot.
**Wire:** none. The list reads `BOT_MANAGER_GET` **per taught course** on show; every fact a card needs is already on `BotManagerPage`, and a teacher has two or three courses. Recorded as **A2 (considered and not taken)** in `docs/contracts/BOT_WIRE_CONTRACT.md` so the next reader sees the decision rather than re-opening it. The `PARAM_COURSE` deep link (co-teacher notification, the analytics Back) now selects that card.
**Isolation:** one `BotManagerSession` per course and no page shared between them, so a create or a toggle addressed to one course has nothing through which to reach another. Asserted both ways: `BotManagerSessionTest$TheListOfBots.creatingOnOneCourseLeavesTheOtherAlone` and `BotInteractionTest.aWriteOnOneCourseNeverMovesAnother`.
**Status:** `DONE` — pending Naji's on-screen verification: sign in as `dana.cohen`, open Study Bot, expect two cards.

### U-27 · COPY · bank card buttons cut off
**In Naji's words:** "the buttons on that card aren't showing the full text ... version history stays as is, edit question becomes edit, delete question becomes delete"
**Fix:** exactly that; and the general rule below.
**Status:** `DONE` — pending eyes.

### U-28 · GENERAL · no button may show cut-off text
**In Naji's words:** "avoid cutoff text in buttons, we need a solution for it"
**Fix, in two layers.** (1) Every button made through `Buttons` has its minimum width pinned to its text; `TextFit` does the same for the controls made elsewhere (hyperlinks, toggles, pickers) and puts a full-text tooltip on the one shape that cannot be sized for its content, a table cell. (2) **`TruncatedTextGuardTest`** boots the app once per role, walks every route that role registers (with sensible params, the take-exam confirmation included), at 1200x760 and 1024x700, and fails on any visible control whose text does not fit; its first run listed **115** truncations across the bank, results, exams, dashboards, approvals, the take-exam links, the bot chat, the data browser and reports, all fixed at the component or layout layer (`DataTable` headings get first claim on width and cells carry their text on a tooltip; stat-card rows wrap; the dashboard grid re-flows to the window; the take-exam card widened). A role gaining a route the guard does not visit fails the guard.
**Two design calls made under it, open to reversal:** table column headings lost the faked letter-tracking (uppercase kept) because tracking cost ~40% of a heading's width in a place the width is not ours to spend; and a table cell whose full text sits on its own tooltip counts as readable, because eight bank columns beside a 420px detail pane cannot show a 47-character stem at any width (that layout itself is U-36).
**Status:** `DONE` — the guard is part of the ordinary build — pending eyes across screens.

### U-29 · COSMETIC · the New exam menu is unreadable (dark on dark, white on white)
**Fix:** menu and context-menu items styled from the theme tokens in both modes. Wave 3 agent H.
**Status:** `DONE` — built, suites green, pending eyes.

### U-30 · FLOW · the builder should show which course the exam is for
**Fix:** the course (code and name) in the builder's header. Agent H.
**Status:** `DONE` — built, suites green, pending eyes.

### U-31 · FLOW · Create exam and Save draft should return to the Exams list
**Fix:** both navigate back to Exams on success, with the saved exam selected. Agent H.
**Status:** `DONE` — built, suites green, pending eyes.

### U-32 · COSMETIC · version chips on the versions panel are cut to three dots
**In Naji's words:** "a check mark for approved, X for rejected and another suitable symbol for pending is better than full words that get cut off, especially since the table has the full words with colors"
**Fix:** compact icon chips on the version cards (✓ approved, ✗ rejected, ● pending, ○ draft) with the word in the tooltip; the table keeps the words. Agent H.
**Status:** `DONE` — built, suites green, pending eyes.

### U-33 · COSMETIC · bot source rows look pressable and only jitter
**Fix:** the hover/press treatment removed from rows that carry no action; Edit and Remove untouched. Agent H.
**Status:** `DONE` — 2026-08-30: the row has no click handler; Edit and Remove are the only actions; the lock-banner test drives the lock through Edit on a text source.

### U-34 · FUNCTIONAL/SEED · the grading screen is empty for the demo teacher
**In Naji's words:** "grading screen still empty, looks like a real bug or unseeded, either way I want it fixed"
**Restated:** correct for the data: Dana's only closed sitting (4821) is fully approved and 7390 is Avi's, so her queue is legitimately "Nothing to grade". That makes the demo teacher's grading screen empty on day one, which is the problem.
**Ruling:** seed a fifth execution, Algebra Midterm v2 sat yesterday by four students, closed, grades AUTO and unapproved, so Dana's queue and "Awaiting grading" card are populated on load. Wave 3 agent G.
**Status:** `DONE` — built, suites green, pending eyes.

### U-35 · COSMETIC · the New exam button is too big
**Fix:** secondary size and style in the header. Agent H.
**Status:** `DONE` — built, suites green, pending eyes.

### U-36 · COSMETIC · the question bank's eight columns beside a fixed detail pane
**Found by:** the truncation guard's first run (2026-08-29): at 1024x700 the bank's eight columns share 449px next to the 420px detail pane, so long stems can only be read from the cell tooltip.
**Ruling:** logged, not taken now: candidates are a collapsible detail pane, fewer default columns (hide Written / Version behind a column chooser), or a wider minimum window. Naji's call on the next round.
**Status:** `NEW`.

## Live session (2026-08-29, after 0df6edd) — Naji browsing with the lead, `docs/manual-round-4-notes.txt`

### U-37 · COSMETIC · the bank card's three buttons: centre Edit
**In Naji's words:** "the buttons versions history is next to edit and delete is way to the right, maybe center edit in the middle it'd look nicer"
**Ruling:** fix (Low): Version history left, Edit centred, Delete right, evenly spaced.
**Status:** `DONE` — Version history left, Edit centred, Delete right (two spacers), verify green.

### U-38 · FUNCTIONAL · grading needs a per-student paper review before approving
**In Naji's words:** "we have a list of approvals to give but we need to open their exam and see it to review it too so this is missing"
**Restated:** the Grading screen lists rows with auto score, state and the override dialog; there is no way to open one student's marked paper (answers, correct answers, points) before approving. `TRACEABILITY.md` already carried F8.2 as PARTIAL "no-review-screen" and E12.6's "paper review" was accepted as a v1 gap on 2026-08-23; the manual round now asks for it. The server side exists: `GradeReviewService` answers `GradeReview(StudentGradeRow grade, List<AnswerReviewRow> answers)` for a teacher (the same shape the student's checked form renders).
**Ruling:** build it: a **Review** action on a grading row opening the teacher's view of that paper (read-only checked form with the answer key, the auto score, and Approve / Change score on the same screen), navbar Back to Grading. Closes the F8.2 gap.
**Status:** `DONE` — route `grading.review` (Marked paper): Review on every grading row opens the student's paper with the answer key, auto score, current score, note, and Approve / Change score on the paper; `GRADE_REVIEW_GET` reused, no wire change; F8.2 PARTIAL closed in TRACEABILITY. Pending eyes.

### U-39 · FUNCTIONAL · "still missing the option to delete the bot"
**In Naji's words:** as quoted.
**Restated:** there is no `BOT_DELETE` on the wire; the product retires a bot with the active toggle (F12.4), because a bot owns persisted student conversations (S-33) that a delete would destroy or that the database would refuse to cascade. The PDF asks for create, sources add/edit/remove, active/inactive; not delete.
**Ruling (Naji, 2026-08-30):** build (b): a **Delete the study bot** button on every bot card beside Manage, confirmed, removing the bot and its sources; refused with a sentence when the bot has student conversations (their records, S-33), pointing at the active toggle. BOT contract amendment A3, verb `BOT_DELETE`. Wave 4 agent M.
**Status:** `DONE` — `BOT_DELETE` (BOT contract A3): Delete the study bot under Manage on every bot card, confirmed; refused with "This bot has N student conversations, which are those students' own records. Switch it off instead of deleting it." when conversations exist; sources go with the bot; co-teachers notified. On the seed every bot has conversations, so the refusal is what you will see; the clean path needs a bot with no chats. Pending eyes.

### U-40 · COSMETIC · the Approvals screen repeats its title and its toolbar hugs the edges
**In Naji's words:** "a big Approvals on top with a small description below it but another Approvals below that which doesn't belong there and is off to the left too much ... the search bar is also too much to the right both have 0 padding or margins"
**Restated:** the page header says Approvals, and the `DataTable` below it carries its own title "Approvals" in its toolbar with the search box on the far right, and the toolbar has no horizontal padding. The same page-title-plus-table-title doubling may exist on other list screens (Exams, Releases, Grading, Results) and is worth one sweep.
**Ruling:** fix (Low): no table title where the page already has one; toolbar padding aligned with the page's 28px gutter; sweep the other list screens for the same doubling.
**Status:** `DONE` — no duplicated table title on Approvals or Exams; the Approvals table sits in the page gutter; the count survives as its own control. Pending eyes.

### U-41 · FLOW · a pure coordinator sees the whole teacher rail, empty
**In Naji's words:** "looks like we're treating coordinators as both teachers and coords, most data here is empty may need seeding"
**Restated:** `rina.barak` is deliberately the pure coordinator (roster decision 2026-08-20: she coordinates Mathematics and teaches nothing, which is what proves the role is derived from the coordinators table and not from course_teachers). Her rail is the teacher's plus Approvals, so Question Bank, Exams, Releases, Live Monitor, Grading, Results and Study Bot are all empty for her. PRD F1.2 says the shell derives from role **and course relations**.
**Ruling (Naji, 2026-08-30): (b).** The rail derives from role and course relations: a coordinator with no taught course gets Dashboard, Question Bank (her subject, read-only), Approvals, Settings; the teaching items appear the day she teaches. `michal.sharon` keeps the full rail. Coordinators stay per subject (S-1); the school-wide view is the principal's. Wave 4 agent N.
**Status:** `DONE` — rail derived from role + taught courses: `rina.barak` gets Dashboard, Question Bank, Approvals, Settings; `michal.sharon` keeps the full rail; coordinator dashboard drops the empty courses card. Pending eyes.

### U-42 · SEED · two subjects is thin
**In Naji's words:** "we might need to do things other than CS and math, like bio, chem, physics maybe the seeding is a little lacking"
**Ruling:** a later seed wave: three more subjects with a course each, a teacher and a coordinator per subject, enrollments, and a handful of questions per course so pickers, reports and the data browser show breadth; no new exams or sittings unless the demo needs them.
**Status:** `DONE` — 2026-08-30: Biology 30/31 (`galit.stern`), Chemistry 40/41 (`orly.navon`), Physics 50/51 (`sivan.adler`), each teacher coordinating her subject; six students per course; six questions per course; Midterm: Biology approved with a graded sitting `7745`; seed total 581. Pending eyes after Reload demo data.

### U-43 · SEED/FUNCTIONAL · reports show the same figures whatever the dimension or subject
**In Naji's words:** "by teacher by course by students, all of them give the exact same data, same within each category, changing course or teacher or student give one out of two situations, the exact same data or nothing at all ... big bug"
**Restated:** by design a report reads only closed sittings whose statistics were **frozen** at the last grade approval (F8.5, C-5), and the seed has exactly **one** such sitting (`4821`, Algebra, Dana). So every dimension and every subject resolves to that one row or to nothing: By teacher → Dana → 4821; By course → Algebra → 4821; By student → any Algebra student → 4821's class statistics (the by-student dimension compares the classes a student sat in, never her own marks, by the strategy's own javadoc and F9.4). `7390` and `3318` are unfrozen on purpose (they are the grading demos). The screen is working; the data cannot show it.
**Ruling:** (1) seed at least three frozen sittings across two subjects (a second Algebra sitting, a Java one, and one for a new subject from U-42) so each dimension has something to compare; (2) the report's empty and single-row states say why ("one closed and graded sitting so far"); (3) a note in DEMO_SCRIPT: approve 3318 and 7390 before act 6, which is B-18's rule already. Not a code defect in the strategies.
**Status:** `DONE` — three frozen sittings (`4821` Algebra/Dana, `6120` Java/Avi, `7745` Biology/Galit) so By teacher, By course and By student compare real rows; the single-row hint "One closed and graded sitting so far; approve more sittings to compare" when there is only one; B-18 amended. Pending eyes.

### U-44 · FUNCTIONAL · the principal's Data tab opens nothing
**In Naji's words:** "in the data tab, nothing opens, so if a principal wants to read an exam or question or results should there be a modal or a screen to display those things?"
**Restated:** F9.3 gives the principal a read-only browse of bank, exams and results; the browser lists rows and stops there. A row should open a read-only detail: a question (the bank's detail card, no actions), an exam version (the coordinator's student-identical preview, no Approve/Send back), a sitting's results (the teacher's results table and statistics, read-only, no print restriction).
**Ruling:** build it: `data.detail` routes off the rail, reusing the existing read-only renderers; every mutating control absent (S-7 stays structurally true: the principal has no mutating verbs either way).
**Status:** `DONE` — routes `data.question` / `data.exam` / `data.results` (principal only, off the rail, Back to Data): the bank's detail pane and the coordinator's paper renderer lifted into shared components; APPROVAL A1 admits the principal to the exam preview, REPORTS A2 puts the latest version id on the catalogue row; results detail shows the frozen statistics, deciles and histogram. No screen carries a writing control. Pending eyes.

### U-45 · TEST HYGIENE · the bot interaction suite trips a teardown race under the full build
**Found by:** three full verifies on 2026-08-29/30, each with exactly one error in `BotInteractionTest` (`aWriteOnOneCourseNeverMovesAnother`, then `selectingACardLoadsThatCoursesBot`): `NullPointerException ... AbstractScreen.eventBus() is null` while a screen builds; the class passes alone every time. `FxTestHarness`'s own javadoc documents the race: `resetGlobalState` after one test can land while a queued FX runnable from it still builds a screen against the emptied `ScreenManager`.
**Ruling:** fix the harness, not the tests: `resetGlobalState` drains the FX queue (`WaitForAsyncUtils.waitForFxEvents()` twice, then reset), and screens built during teardown must not throw on a null bus. Until then a sole red of this shape is rerun once.
**Status:** `NEW`.

