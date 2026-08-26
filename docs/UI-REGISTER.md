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

Both are `DONE` and neither is `VERIFIED`, which is what keeps them here: an entry closes when
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

---

## Closed entries

*(none yet — an entry closes when Naji has seen it fixed on screen)*
