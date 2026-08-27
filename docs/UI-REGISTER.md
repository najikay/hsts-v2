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

## Closed entries

*(none yet — an entry closes when Naji has seen it fixed on screen)*
