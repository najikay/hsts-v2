# UI wave 1 — manual pass 1 mechanics (F-6 .. F-14)

Implements `docs/reports/lead/MANUAL-PASS-1.md`'s wave-1 table, TODO items W1.1 .. W1.8,
plus one prerequisite: the shared interaction-test harness race Member A diagnosed.

Boundaries held: `client/features/bank/**`, `common/protocol` and every server handler and
service are untouched. Dashboards call existing verbs only. No new animation dependency; all
motion is the in-house `Motion` / `Animations` system.

**Two wires were touched, and both are named here rather than buried.** `ShellBoot.enter`
gains two lines: it hands the bell to the notification panel as its anchor (F-6 cannot be
done without telling the panel what it is anchored to) and calls
`AppShell.installProfileMenu` (F-12 is a wire by definition). No session logic moved.
`ScreenManager.resetForTests` also changed — that is item 0's fix, and it is the existing
test-only seam.

---

## Item 0 — the harness race (prerequisite)

**The bug.** Every app-booting interaction test copied the same six reflective lines into
`@AfterEach` and discarded the `ScreenManager` singleton. Booting lands on `ConnectView`,
which sweeps the LAN for two seconds on a daemon thread and posts its decision back with
`Platform.runLater`. Tests that finish in under two seconds — most of them — tore the
singleton down mid-sweep. The posted runnable then ran against a freshly created,
never-initialised manager, asked it for the event bus, got `null`, and
`ConnectWiring.forEndpoint` threw. Because the throw happened inside a `runLater` with no
test in the call stack, JUnit attributed it to **whichever test ran next**.

**The fix, in three parts**, all documented in the harness javadoc
(`src/test/java/client/core/FxTestHarness.java`):

1. **Drain, then tear down.** `drainFxEvents()` posts a latch-releasing runnable and waits.
   `Platform.runLater` is FIFO, so when it executes, everything queued before it has
   finished — which covers work the sweep had *already* posted.
2. **Hide before discarding.** `ScreenManager.resetForTests()` now hides the current screen
   before dropping the singleton. Hiding clears `ConnectView`'s "still showing" flag, so a
   sweep landing *after* teardown returns without touching anything. This is the part that
   actually closes the race; the drain alone could not.
3. **A backstop in the wiring.** `ConnectWiring.forEndpoint` logs and wires to a detached
   bus instead of throwing when the bus is gone. It opens no socket, so a wiring nobody uses
   is inert.

The harness lives in package `client.core`, so `resetForTests()` is called directly and the
copied `setAccessible` reflection disappeared from twelve test classes.

**Stability result: 3 consecutive runs of the full interaction-test set, 105 tests each,
zero failures.** Command, run three times back to back with nothing else touching the tree:

```
./mvnw -B test -Dtest='*InteractionTest' -DfailIfNoSpecifiedTests=false
```

105 rather than the previous 99 because `WaveOneInteractionTest` adds six. All three runs
ended `BUILD SUCCESS`, `Tests run: 105, Failures: 0, Errors: 0, Skipped: 0`.

(For the record: an earlier attempt at this proof was invalidated by my own concurrent
`mvn compile`, which rewrote `target/classes` under a running fork and produced a cascade of
`NoClassDefFoundError`. That was a measurement error, not a regression, and the three runs
above were made with the tree quiet.)

`BankScreenInteractionTest` still carries the old inline pattern — it is inside
`client/features/bank/**`, which is Member A's PR-B. It is covered by parts 2 and 3 (both
production-side) and should be switched to `FxTestHarness` when PR-B merges.

---

## W1.1 / F-6 — notifications become an anchored popover

**What was actually wrong.** The panel was already mounted into the shell's popover layer,
but that layer is a `StackPane` filling the content area, and a StackPane centres what it is
given. So it rendered as a centred modal with no backdrop: it did not point at the bell,
clicking away did nothing, ESC did nothing. That is also why the bell felt like it needed
two clicks — the only way to dismiss was to find the bell again, so every *other* open cost
two.

**What changed.** A new house component, `client/ui/components/Popover.java`, owns the idea
that a popover belongs to the control that opened it: top-right alignment offset by a right
margin measured from the anchor, and light dismissal as a pair of **scene event filters**
(click outside, ESC) installed while open and removed on close. Filters rather than
handlers, so the popover is gone before the click reaches whatever is underneath it. The
anchor's own subtree is excluded from the outside test — without that, clicking the bell
would close it in the filter and the bell's action would reopen it, giving a control that
can never be switched off.

`NotificationsPanel` delegates open/close/toggle to it. **The list content and its store are
unchanged**; only the container moved. Motion is a quick fade plus an 8px drop at
`Motion.FAST_MS`.

The margin subtracts the host's own right padding — adding both double-counted the gap and
the panel stopped lining up with the bell.

## W1.2 / F-7 — the back-button convention

`client/ui/components/BackLink.java`. One control, one look, **top-left, above the title**,
on every drill-in. Breadcrumbs and view toggles are untouched: they say *where you are*, this
says *how to leave*.

The label is the single word "Back" because it is the only label that cannot lie — a drill-in
is reachable from more than one place (the approval preview from the queue *and* from a
notification), so a control reading "Back to approvals" that returns you to your
notifications has told you something untrue. The click prefers `Navigator.back()`; the parent
route is the fallback for the no-history case, and it is what the tooltip names.

**Screens swept** (complete list):

| Screen | Before | After |
|---|---|---|
| Teacher results, histogram view | **nothing** — the segmented control was a toggle, not an exit | `BackLink.action("Table", …)` above the chart. This is F-7's named case. |
| `CheckedFormView` (marked paper) | nothing; reached from a My Grades row, no rail entry | Back to My grades, hidden in print layout |
| `ExecutionMonitorView` | nothing; reached from Releases | Back to Releases |
| `BotAnalyticsView` | a "Bot manager" button on the right | Back top-left; the named button stays |
| `ExamPreviewView` | "Back to approvals" in the **footer**, below a scrolling paper | Back top-left as well; both go to the same place |
| `BotHistoryView` | a "Study bot" button on the right of the header | Back top-left; the named button stays |
| Releases code reveal | **swept, no change needed** — an inline panel on a rail screen with its own "Done" | — |
| `TakeExamView` | **swept, deliberately none** — you must not be able to walk out of a sitting | — |
| Principal reports | **swept, none needed** — a rail screen, table and chart shown together | — |

`BackLink` also has an `action(…)` form for a drill-in that is a *mode of one screen* rather
than a route, which is what the histogram is.

## W1.3 / F-8 — single click opens rows

`DataTable.openOnClick(Consumer<T>)` is the new house row factory: opens on a single primary
click **and** on Enter. Selection still happens first, so screens that highlight a selected
row keep doing so.

**Every double-click site in the client, swept** (three, found by grepping `getClickCount`):

| Screen | Was | Now |
|---|---|---|
| `ApprovalQueueView` (F-8's confirmed case) | double click + Enter | single click + Enter |
| `MyApprovalsView` | double click only | single click + Enter (Enter is new) |
| `MyGradesView` | double click on the table | single click + Enter (Enter is new) |

Screens that distinguish select-from-open already used single-click selection listeners and
were left alone: `GradingQueueView` rail, `TeacherResultsView` exam rail, `ReportsView` row
selection, bot history rows.

## W1.4 / F-9 — global table sizing (the B-5 treatment)

`DataTable.columnWidths(double...)` generalises what B-5 fixed by hand on My Grades.
Preferred widths, not fixed ones, so tables still stretch and dividers still drag; the point
is only that a date is not allotted the same room as a two-character course code.

**Every table in the client, and what drove each choice:**

| Screen | Table | Columns sized |
|---|---|---|
| `ApprovalQueueView` | approval queue | 6 — teacher name and submitted date were clipping |
| `MyApprovalsView` | my submissions | 5 — submitted date |
| `MyGradesView` | my grades | 6 — the original B-5 case, now via the shared API |
| `TeacherResultsView` | students | 6 — student name vs four numeric columns |
| `GradingQueueView` | grading rows | 5 — student name vs two- and three-digit scores |
| `ReportsView` | sittings | 7 — sitting label and date vs five statistics |
| `DataView` | questions | 7 — question stem, and the "Written" date |
| `DataView` | exams | 6 — exam name, author, "Last written" date |
| `DataView` | sittings | 8 — sitting label and "Closed" date vs four statistics |

**Not touched:** `BankView` / `QuestionsView` tables — inside `client/features/bank/**`,
Member A's PR-B. They should get one `columnWidths(…)` call each after that merges; the API
is already there.

## W1.5 / F-10 — dashboard cards v1

Four FX-free sessions, one per role, each tested with no toolkit booted. Cards are values
(`DashboardCard`), so a test asserts on the card a session produced and the view is a loop
with nothing left to get wrong. All copy is in `DashboardCopy` and covered by the house scan.

| Role | Cards | Verbs (all pre-existing) |
|---|---|---|
| Teacher | Today and next; Awaiting grading; Your exams | `RELEASE_LIST_GET`, `GRADING_QUEUE_GET`, `RESULTS_EXAMS_GET` |
| Coordinator | Waiting for you; Teachers submitting | `APPROVALS_QUEUE_GET` (one read, both cards) |
| Student | Latest grade; Study bot | `MY_GRADES_GET`; the bot card asks nothing |
| Principal | Exams in the school; Sittings marked | `DATA_EXAMS_GET`, `DATA_RESULTS_GET` |

Design points worth keeping:

- **A failed read is never a zero.** `DashboardCard.State` keeps `EMPTY` and `FAILED` apart.
  A card that could not reach the server says "not available", because zero is a fact about
  the school and a failed read is a fact about the network. Every session has a test for it.
- **Cards settle independently.** The teacher's three reads are three verbs; one failing
  leaves the other two carrying their numbers. Tested.
- **Each card opens the screen it counted.** Tested by route id.
- **Motion**: `Animations.staggerIn` on the card row, which is what makes three reads
  landing at three different moments read as arriving rather than flickering.
- **Designed empty state per card**, and the copy test enforces that each names *what fills
  it* ("release an exam and its sitting appears here") rather than restating the absence.

**Dropped, with the reason:** the student's **"next or live exam"** card. No verb answers it.
A student reaches a sitting by typing the four-character code a teacher reads out
(`EXAM_JOIN`, S-18); there is no "list the sittings I could join" read on the wire, and
adding one means a protocol change, a handler and a service — all outside this wave, and not
a decision a dashboard card should force. The code-entry card already on the student home is
the real answer and stays where it is. Recorded in `StudentDashboardSession`'s javadoc too.

**Also removed:** the placeholder API that produced the empty dashboards —
`DashboardPage.statCard`, `statGrid`, `pendingAction`, `NO_VALUE`. Every epic they named
("Arrives with E8", "Arrives with E12") has landed, so they were dead.

**Coordinator note:** "Teachers submitting" is derived from the distinct `authorName` values
in the approval queue, not fetched. It is honest about what it means (teachers with something
in the queue *right now*), and it keeps the no-new-verbs promise. Case-insensitive, so two
spellings of one name cannot inflate it.

## W1.6 / F-11 + F-14 — bot modal shadow, and the bot copy pass

**F-11.** The "create the study bot" modal was a raw `TextInputDialog` — the only modal in
the app that was. It opened as an OS-decorated window with the platform's own drop shadow,
inherited neither the stylesheet nor the dark-mode root class, and had no scrim. That is the
"broken-looking shadow". Fixed by using `WarnConfirm`, the house dialog, which already solves
all three (transparent stage, copied stylesheets, scrim) — so the fix is to stop having a
second kind of dialog rather than to restyle this one.

**F-14.** Three of the four bot screens put the *course name* where a sentence should have
been. Each now carries one explanatory line, in `BotCopy` and therefore covered by the scan:

- **Chat** — says what the bot knows and, deliberately, that the teacher does not read the
  questions. A student who suspects otherwise asks nothing.
- **Manager** — says what adding material is *for*, so "add a file" is not an upload with no
  visible consequence.
- **History** — says what the list is and that a conversation can be reopened.
- **Analytics** — says what the numbers are about, *before* the anonymity note says what they
  are not.

Two empty states were sharpened to name the next action: the student chat now says "type a
question below and press send", and the teacher's no-bot state names the button by its label.

## W1.7 / F-12 — the profile-name control is a menu

The avatar chip has always been styled as a control — rounded, bordered, hoverable, hand
cursor — and did nothing. `AppShell.installProfileMenu(ThemeState)` makes it a `ContextMenu`
with the **theme quick switch** (Light / Dark / System, radio items reading and writing
`ThemeState`) and **Sign out**.

Sign-out plumbing does exist cleanly, so both are present: the menu item fires the existing
`logoutButton`, which already owns the confirmation, so neither the dialog nor the logout
sequence is duplicated. The item is only added when `setOnLogout` has been wired — a menu
item that is present and inert is the exact defect being fixed.

A `ContextMenu` rather than a hand-built popover, because it already closes on ESC, on a
click outside and on a second click of its owner, and re-implementing that would be a second
copy of what `Popover` does for the bell.

## W1.8 / F-13 — seed content to English

`docs/seed/SEED_CONTENT.md` is the authority and `SeedLoadedDbContract` compares the loaded
database against it, so the document and `server/db/seed/**` were translated **in lockstep**
in one pass from a single mapping.

Translated: 18 user display names, 2 subjects, 4 course names, 20 mathematics question stems
and their non-numeric options, 3 exam names with their instructions, teacher notes and
rejection reasons, 1 teacher comment and 1 override reason, 6 notification texts, 2 bot
names, 4 bot source titles, the 4 long bot source bodies (1217 words) and 4 recorded bot
Q&A pairs. The Java and Databases content was already English and is untouched.

**What was deliberately preserved:**

- **Every number, id, login, national id and password.** `SeedArithmeticTest`'s inputs are
  untouched; the seed test suite is green (174 tests, run explicitly — see below).
- **The em-dash house rule.** `SeedDocument.followsHouseRule` splits document text on an em
  dash and expects `[,.:]` in the stored text. Translation therefore replaced the *Hebrew
  halves only* and left the separators alone, so the document still reads
  `Marking note: question 7 — accept …` where the loader stores `…question 7, accept …`.
  One string needed a wording tweak to keep both halves identical.
- **`DEMO_ACCOUNTS.md` as roster authority.** Its names were already
  `Dana Cohen (דנה כהן)`; the parenthetical became a duplicate under the ruling and was
  dropped, leaving the English names it already carried. No login, password or role changed.

**One test updated deliberately, not weakened.** `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip`
read Hebrew *out of the seed* to prove the utf8mb4 columns and connection charset do not
mangle it. With the seed in English there is nothing to read, and deleting the test would
have thrown away the guarantee along with the coupling — the columns are utf8mb4 so a
*teacher* can type a Hebrew comment on a paper. It now writes its own Hebrew sample and reads
it back, asserting byte-for-byte equality and unchanged length. Strictly stronger: it
exercises a write as well as a read.

Also updated: the document's stated word counts, recomputed from the translated bodies
(2171 words across eight sources) rather than left stale, with a note recording the
translation.

---

## Manual click-checklist (view-only changes, for the lead)

Reseed first (`SeedMain --reseed`), then sign in per role.

**Notifications (F-6)** — sign in as anyone.
1. Click the bell **once**. The panel opens **under the bell**, right edges aligned, not
   centred on the page.
2. Click anywhere on the rail or the page. It closes.
3. Open it again, press **ESC**. It closes.
4. Open it, click the **bell** again. It closes. Click once more — it opens. (It must not
   flicker closed-then-open.)
5. Watch the entrance: a short fade and drop, not a pop.

**Back convention (F-7)** — one screen each.
6. Teacher → Results → pick an exam → **Histogram**. There is a "Back" above the chart; it
   returns to the table.
7. Student → My Grades → open a paper. "Back" top-left returns to My Grades. Turn on **print
   layout**: the Back control disappears from the page.
8. Teacher → Releases → open the monitor of a live sitting. "Back" returns to Releases.
9. Coordinator → Approvals → open one. "Back" top-left *and* "Back to approvals" in the
   footer both work.
10. Teacher → Study Bot → Bot activity. "Back" top-left returns to the manager.
11. Student → Study bot → Past conversations. "Back" top-left returns to the chat.

**Single click (F-8)**
12. Coordinator → Approvals. **One** click on a row opens the preview.
13. Same row with the keyboard: arrow to it, press **Enter**. It opens.
14. Teacher → My submissions: one click opens. Student → My Grades: one click opens a paper.

**Table sizing (F-9)** — at the **default** window size, no maximising.
15. Student → My Grades: the "Approved" column shows a full date, and "Teacher's note" is not
    clipped.
16. Coordinator → Approvals: teacher names and "Submitted" dates are complete.
17. Principal → Data, all three tabs: no header or date ends in an ellipsis.
18. Teacher → Grading, and Results → Students: student names are complete.

**Dashboards (F-10)** — all four roles.
19. Teacher home: three cards with real counts. Click each — it opens Releases, Grading and
    Results respectively.
20. Coordinator home: two cards; clicking either opens Approvals.
21. Student home: latest grade (the *most recent*, not the highest) and the study bot card.
22. Principal home: two counts; they open Data and Reports.
23. Watch the entrance on any of them: cards arrive staggered, left to right.
24. **Failure state:** stop the server, then revisit a dashboard. Cards must read "Not
    available" with the connection sentence — **never "0"**.
25. On a fresh/empty account, each empty card says what will fill it.

**Bot (F-11, F-14)**
26. Teacher → Study Bot on a course with no bot → "Create the study bot". The dialog is the
    house dialog: scrim behind it, no OS window frame, correct in dark mode.
27. Each of the four bot screens has one explanatory line under the heading.
28. Student chat with no messages: the empty state names the gesture ("type a question below
    and press send").

**Profile menu (F-12)**
29. Click the name chip in the navbar. A menu opens with Light / Dark / System and Sign out.
30. Pick Dark. The app repaints immediately and the radio shows the new mode next time.
31. Open it, press ESC — closes. Open it, click elsewhere — closes.
32. Sign out from the menu: the same confirmation dialog as the icon button.

**Seed English (F-13)**
33. Nothing anywhere in the demo shows Hebrew: question stems, exam names, student names,
    notifications, bot sources and bot history.
34. `DEMO_ACCOUNTS.md` names match what the app shows.

---

## Test inventory

**New:**

| Test | Layer | What it holds |
|---|---|---|
| `client/core/FxTestHarness` | shared harness | the drain + hide + backstop mechanism, documented in javadoc |
| `client/features/home/DashboardSessionTest` | FX-free session | 16 tests across four nested classes: counts, routes, independent settling, failure-is-not-zero, empty states, loading |
| `client/features/home/DashboardCopyTest` | copy scan | the house reflective scan (em dash, shouting, sentence case) plus two meaning tests: empty lines name what fills them, the failure line blames the connection |
| `client/ui/WaveOneInteractionTest` | TestFX | 6 tests: popover click-outside, popover ESC, bell toggle stays a toggle, popover anchored to the bell, one-click row open, drill-in back control |

**Updated deliberately (each with the reason in the code):**

| Test | Change | Why |
|---|---|---|
| `ConnectWiringTest` | the null-bus case now asserts graceful degradation instead of an NPE | the backstop in item 0; the endpoint is still a hard requirement, and the new test additionally proves the substituted bus is genuinely detached and still opens no socket |
| `BotCopyTest.emptyStatesOfferSomething` | two pinned phrases moved with the F-14 rewrite | the rule is unchanged and no weaker — each assertion still demands the words that make the sentence an instruction, and now demands *two* phrases where it demanded one |
| `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip` | writes its own Hebrew sample instead of reading the seed | see W1.8; strictly stronger, since it now exercises a write as well as a read |
| 12 interaction test classes | reflective teardown replaced by `FxTestHarness.resetGlobalState()` | item 0; behaviour preserved and the race closed |
| `BotCopyTest` | **added** `everyScreenHasAnExplainer` | F-14 needs each explainer to say what the screen *does*, not merely to exist |

**Notably not weakened:** `TeacherResultsInteractionTest.toggleSwapsTheViews` failed after the
histogram back link was added, because the chart went into a wrapper and JavaFX does not push
visibility into children. The fix went into the **production** code (hide the pane *and* the
chart), not the assertion.

## Verification

**Full gate, green.**

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_wave1 ./mvnw -B clean verify
```

```
Tests run: 5529, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (15:10)
```

The JaCoCo bundle rule (90% instruction coverage, `jacoco-check` at `verify`) passed.

**Coverage of the packages this wave touched**, from that run's report
(`target/site/jacoco/index.html`):

| Package | Instruction coverage |
|---|---|
| `client.features.home` | **100%** (the four dashboard sessions, `DashboardCard`, `DashboardCopy`) |
| `client.features.notify` | **100%** |
| `client.ui.components` | 95% (now includes `BackLink` and `Popover`) |
| `client.features.connect` | 95% (the `ConnectWiring` backstop) |
| Bundle total | **98%** |

`BackLink` and `Popover` were deliberately **not** added to the JaCoCo exclusion list, even
though every other view component in `client/ui/components` is on it. They carry real
behaviour — light dismissal, anchoring arithmetic, the history-then-parent fallback — and
`WaveOneInteractionTest` exercises it, so excluding them would have hidden the one part of
this wave's view code that can actually be wrong. The package still clears the gate at 95%.

## TODO

`docs/TODO.md`: W1.1 .. W1.8 ticked. W2.1 and W2.2 untouched — the wave-2 canvas gates them.

## Dropped, with reasons

1. **Student "next or live exam" card** — no verb exists (W1.5 above).
2. **Bank tables' column widths** — `client/features/bank/**` is Member A's in-flight PR-B.
   One `columnWidths(…)` call each once it merges.
3. **`BankScreenInteractionTest` harness migration** — same boundary (item 0 above).
