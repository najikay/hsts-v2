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
**Status:** `NEW`.

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
**Status:** `IN WORK`.

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
**Status:** `IN WORK`.

---
## Closed entries

*(none yet — an entry closes when Naji has seen it fixed on screen)*
