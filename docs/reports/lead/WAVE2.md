# UI wave 2 — the visual remodel (W2.1, W2.2)

Implements the lead's published design canvas across the client. This is a **polish wave over
wave 1's mechanics**, not a rebuild: every screen keeps its session, its verbs and its rules, and
what moved is views, CSS and the motion system.

Boundaries held: `client/features/bank/**` was not edited (one consequence is recorded below and
it is not a violation), `common/protocol` is untouched, and **no verb, no DTO and no server
handler changed**. Every read this wave makes is a read some screen already made.

**Two things were extended and both are named here rather than buried.**

1. **Sessions gained read-only accessors** where a card's summary sentence needed a number they
   had already loaded — `summary()` on all four dashboard sessions, `termAverage()`,
   `courseCount()` and `nextExam()` on `MyGradesSession`. No session changed what it fetches
   because of these.
2. **`TeacherDashboardSession` makes two conditional follow-up reads**, on verbs that already
   exist, because two of the canvas's four teacher cards need a detail the list verbs do not
   carry. `EXECUTION_MONITOR_GET` only when a sitting is live; `RESULTS_EXECUTION_GET` only when
   a closed sitting exists. Nothing live, nothing closed, nothing asked. The monitor verb's
   documented side effect — it registers the caller as a watcher — is discussed under §1.

---

## 1. Dashboards, all four roles

### The greeting header

Three lines where there were two. The middle one is the point of the remodel: a live sentence,
in muted text, composed from the numbers **the cards themselves loaded**.

`DashboardSummary` is a new FX-free class and it is a class rather than a format string because
the sentence has to be right in the cases nobody demonstrates:

| Case | What a format string produces | What this produces |
|---|---|---|
| One live sitting | "1 sittings are live" | "One sitting is live right now." |
| Nothing to grade | "…and 0 papers are waiting" | the clause is dropped |
| Reads still in flight | a confident sentence full of zeros | "Checking what is happening today." |
| Every read failed | "No exams are waiting for your approval." | "Today's summary could not be loaded…" |
| A quiet Tuesday | a list of noughts | "Nothing is live and nothing is waiting to be marked." |

The fourth row is the one that matters: it is the same lie `DashboardCard.State.FAILED` exists to
prevent, told in prose instead of in a number, and a coordinator who reads it stops checking.
Every row above has a test.

The greeting itself is still `HomeGreeting`'s, still clock-injected. The two are paired in the
header and know nothing about each other, which is why both are testable with no toolkit.

### The cards

`DashboardCard` gained three components — `kicker`, `chip`, `linkText`. They are **content, not
styling**: what a card's kicker says and whether it carries a "Live" pill are decisions made from
the data the session read, and putting them in the view would have moved a third of the
dashboard's decisions into the one class on the coverage exclusion list.

Every card now renders: an 11.5px uppercase tracked kicker in faint, an optional status chip, a
30px number, the hint, and an accent "Open X →" line at the bottom. The whole card is still the
hit target; the link line exists because a hit target with no visible affordance is a card users
do not know is clickable.

One coupling was added on purpose and should be seen: the student's latest-grade card takes its
pass chip's wording from `MyGradesCopy`, not from a copy of it in `DashboardCopy`. Same audience,
same fact, one vocabulary — and the pass mark itself comes from
`ResultStatistics.PASS_MARK` rather than a second 55 in the client, so a school that changes it
changes it once.

### The teacher's four cards

The canvas names four kickers, and wave 1 had three cards. The split is deliberate:

| Kicker | Source | What changed |
|---|---|---|
| LIVE NOW | `RELEASE_LIST_GET`, rows in `LIVE` | **new**; wave 1 counted live and scheduled together |
| AWAITING GRADING | `GRADING_QUEUE_GET` | unchanged count, restyled, gains a "To do"/"All clear" chip |
| NEXT RELEASE | `RELEASE_LIST_GET`, rows in `SCHEDULED` | **new**; the other half of wave 1's card |
| LAST CLOSED SITTING | `RESULTS_EXAMS_GET` → `RESULTS_EXECUTION_GET` | **new**, replaces "Your exams" |

Wave 1's "Today and next" answered *"is anything of mine running or about to"* in one number and
therefore answered neither: a teacher reading "2" could not tell whether to walk to a classroom.

**The LIVE NOW card, when a sitting is live**: a green pulse dot with an expanding halo, the exam
name in bold, "Code 4B7Q · closes 10:30", a 7px accent-gradient progress bar with "3 of 8
submitted" and the time left under it, then up to three per-student rows with attempt chips.

Three design points on that card, all tested:

- **The rows shown are the students still sitting**, newest-first, topped up with finished ones
  if there is room. Three slots spent on students who handed in twenty minutes ago tell a
  teacher nothing she can act on.
- **Time left is measured against `serverNow`**, which the monitor answer carries. A teacher
  whose laptop clock is ten minutes fast must not be told the sitting closes ten minutes early.
- **A failed monitor read leaves the count card alone.** The number was true before the detail
  read and still is; replacing it with an error would be worse than the missing detail.

**The LAST CLOSED SITTING card**: the mean as the big number, "12 of 18 passed", and a ten-bar
mini distribution. `Sparkbars` over the new `SparkbarSpec`, not `StatChart` — the reader is not
analysing it, she is being told the shape in the second before she decides whether to open the
sitting. `StatChart` is still the real histogram and is what the Results screen behind the card
draws.

Two rules there are worth naming: **a tie is not a mode** (an accent bar says "this is where the
class landed", and saying it about a flat distribution is the card inventing a story), and **one
student is visible where none is not** (1 out of 40 is 2.5% of the tallest bar, which rounds to
nothing, and "one" and "none" are different facts about a class).

### The monitor verb's side effect, stated

`EXECUTION_MONITOR_GET` registers the caller as a watcher — that is its documented contract and
the mechanism the monitor screen relies on. So a teacher sitting on her dashboard with a live
sitting will receive `PUSH_MONITOR_UPDATED`. The dashboard subscribes to no push, so those are
ignored: the cost is one wasted message per update. The alternative was a read-only variant of
the verb, which is a wire change this wave is not allowed to make, and inventing one to avoid a
message nobody reads is the wrong trade.

---

## 2. Table treatment, globally

All of it lives in `DataTable`, applied once. A treatment written on nine screens is a treatment
that is eight-ninths applied by the next epic.

| What | How |
|---|---|
| Column headings | 11.5px uppercase tracked faint, on a slightly tinted header band |
| Numeric columns | right-aligned, monospace stack |
| Row height | 44px (`-fx-fixed-cell-size`) |
| Row hover | full-row accent tint, plus an "Open →" pill fading in at the row end over 100ms **where the row opens something** |
| Selected row | soft accent background plus a 3px accent bar down the left edge |
| First load | 20ms linear fade stagger, **never** on a refresh or a resort |

**Every table in the client, and what it got:**

| Screen | Table | Numeric columns marked |
|---|---|---|
| `ApprovalQueueView` | approval queue | Questions |
| `MyApprovalsView` | my submissions | Questions |
| `TeacherResultsView` | students | Score, Auto, Final |
| `GradingQueueView` | grading rows | Auto, Score |
| `ReportsView` | sittings | Mean, Median, Sigma, Pass rate, Participants |
| `DataView` | questions | Version |
| `DataView` | exams | Versions |
| `DataView` | sittings | Mean, Median, Sigma, Pass rate, Participants |
| `MyGradesView` | **retired** — replaced by the card grid (§4) | — |

**`client/features/bank/**` was not edited, and one consequence should be seen rather than
discovered.** `BankView` builds a `DataTable`, so its list inherits the shared treatment through
the component. No file in that package changed, so Member A's PR-B cannot conflict with this
wave; what changed underneath it is the component both screens were always sharing. That is the
treatment working as designed, and it is the reason it was put in `DataTable` rather than
copied onto eight screens.

### Three implementation notes, because the toolkit forced them

- **JavaFX CSS has no `text-transform` and no `letter-spacing`.** Both are web properties; a
  stylesheet asked for them parses them as unknown and silently does nothing, which is how a
  design ships half-applied. The kicker's uppercase and its tracking are therefore done in Java,
  by `KickerText`, and the tracking is a hair space between characters — the only tracking a
  toolkit without tracking allows. That has an accessibility cost, and `Kicker` pays it: it sets
  the node's accessible text to the **plain** words, so a screen reader announces "APPROVED" and
  not "A P P R O V E D". Column headings are set as the column's *graphic* for exactly this
  reason.
- **JavaFX exposes no `font-feature-settings`,** so "tabular figures" is not available. The
  house's answer is the monospace stack `.mono` already uses. It is the only way a column of
  three-digit scores lines up on this toolkit.
- **The hover "Open" affordance is an overlay in the wrapper**, not an extra column and not a
  child of the `TableRow`. A column would shift every screen's `columnWidths` by one and appear
  in the sort order; a child of a `TableRow` is at the mercy of `TableRowSkin`, which sets its
  children from the cells and would drop it.

One more toolkit hazard was closed while doing this. **JavaFX does not fail on a bad stylesheet**
— it logs the bad declaration, skips it, and comes up looking almost right, with the build still
green. That is survivable while the CSS is small; this wave added roughly 200 lines of it, several
using constructs the parser is fussy about. `StylesheetParseTest` now asks `CssParser` directly:
all seven shipped stylesheets, zero parse errors, and the new selectors present in the *parsed*
rules rather than merely in the file.

---

## 3. Notification popover, visual pass

The container and its behaviour are wave 1's and are untouched — anchoring, click-outside, ESC,
the bell toggle, the deep links, the store. What changed is the row.

- 360px wide, down from 380: the row now carries a 34px badge on its left, and the same body
  text in the same width would have wrapped a line further.
- A **34px rounded icon badge** per row, tinted by kind: green for good news, red for something
  that needs a decision, accent for everything else.
- The badge tone comes from `NotificationPresenter.badgeToneFor`, and there is a test asserting
  **it agrees with the toast that same push raised**. A green toast followed by a red badge has
  told the reader two different things about one event, and the reader believes the second one.
- Title 13.5px semibold, body 12.5px muted, relative time right-aligned faint.
- Unread rows: a barely-tinted background, a 7px accent dot, and a bolder title. Three signals,
  because colour alone does not survive a colour-blind reader.

**Dropped, with the reason: the "See all" footer.** There is no all-notifications route in
`Routes` — the popover *is* the notifications surface in this build. A centred accent link that
opens nothing is the exact defect F-12 was raised for, and adding a screen behind it is a feature,
not a visual pass. Recorded here rather than quietly omitted.

---

## 4. Student My Grades

The table became a hero band and a grid of cards. This is the one screen in the app whose entire
content is a set of numbers about the person reading it, and a five-column table presents a
student's own transcript as a spreadsheet row among rows.

**The hero band**: a flat `-hsts-accent` band with a soft radial wash over it, an 84px
`ProgressRing` carrying the term average, a headline, the counting line ("4 grades across 2
courses"), and one warm sentence.

- The band is **the canvas's approved fallback, and it was needed.** A two-stop linear gradient
  from a *looked-up* colour cannot be lightened reliably across all five palettes, and a
  per-palette gradient would be five more values to keep in step with `AccentPalette`. The wash
  is one rule that works for all of them.
- Text on the band uses `-hsts-on-accent`, the token that already answers "what is legible on the
  accent" and is defined per palette and per mode. No light-only hex anywhere.
- The ring's arithmetic is `RingGeometry`, because JavaFX angles are mathematical (0° is three
  o'clock, positive is counter-clockwise) and a progress ring is neither. A sign error there
  produces a ring that fills backwards, which looks deliberate.
- **An average of nothing is 0, never NaN.** A NaN arc length blanks the hero on the screen that
  is entirely hero, and "no grades yet" is the first-run case.

**The right-hand "next exam" slot** is built, driven from `MyGradesSession.nextExam()`, and
hidden — because that method returns empty and will until a verb exists. No read answers "which
sitting is next for me": a student reaches a sitting by typing the four-character code her
teacher reads out (`EXAM_JOIN`, S-18). Wave 1 dropped the same card from the student dashboard
for the same reason. It is wired rather than omitted so the day a verb exists it is a one-line
change in a measured class, and there is a test asserting it is empty *and saying why*, so the
next person finds the answer where they find the question.

**The grid**: three columns, one card per grade — course kicker, pass chip, exam name, "71 / 100"
at 30px, the approval date, and an "Open paper →" line that appears on hover. Cards lift on
hover; a card opens the marked paper on one click, the same gesture F-8 gave the row, pointed at
the same route with the same parameter. The grid closes with a **dashed slot**: muted icon and
"Your grade appears the moment a teacher approves it".

**It is a view swap and nothing more.** Same session, same read, same push subscription, same
states, same drill-in. The checked form and its print layout are reached exactly as before.

---

## 5. Motion

Implemented through the `Motion`/`Animations` system, which gained a **reduced-motion switch**
this wave. It did not have one; the spec required it.

| Gesture | Spec | Where |
|---|---|---|
| Route change | 180ms ease-out fade + 8px rise, incoming only | `ScreenManager`, `AppShell.setContent` |
| Popover open | 140ms fade + 6px slide | `Popover.open` |
| Popover close | 100ms fade | `Popover.close` |
| Dialogs | 160ms scale 0.98→1 + fade, parallel scrim | `WarnConfirm`, `RejectDialog`, `CreateReleaseDialog` |
| Dashboard cards | 30ms stagger, fade + 6px rise, max 6 staggered | `Animations.staggerCards` |
| Table rows | 20ms linear fade stagger, **first load only** | `DataTable` |
| Card hover | 150ms lift, translateY −2px | `Animations.liftOnHover` |
| Row hover | 100ms | `DataTable` affordance fade |
| Empty state icon | 2.4s breathe, scale 1.0↔1.04 | `EmptyState`, while visible only |
| Live halo | 1.6s loop, only while genuinely live | `Animations.livePulse` |
| Changing numbers | 240ms vertical roll | `NumberRoll` (card values, live submitted count) |
| Take-exam screens | **zero entrance motion** | enforced by test, §6 |

Points worth keeping:

- **Incoming only** on the route change is the whole design. Cross-fading two screens means the
  app is briefly showing neither, and a rise on the outgoing one moves content a user may still
  be reading.
- **"First load only" is `DataTable`'s rule, not a caller's.** Only the component knows which
  `setItems` is which, and rows that re-animate every time a column header is clicked are the
  defect the rule exists to prevent.
- **The card stagger is capped by count, the row stagger by time.** Different caps for different
  reasons: past six cards nobody is reading in order, and a 400-row data browser must still
  finish arriving within a blink.
- **Two ambient loops, and only two.** Both are exempt from the 250ms budget on the same terms
  `Animations.shimmer` already is, and `MotionTest` asserts they are the only two *constants* in
  `Motion` that exceed it, so a third cannot be added by copying one. The breathing icon runs only while its empty
  state is visible — every `DataTable` builds one whether or not it shows one, and a loop started
  in a constructor would be one indefinite timeline per table in the build, all animating
  nothing.
- **The live halo is a node, not a drop shadow.** A `DropShadow` needs a `Color` in Java, and no
  colour in this app comes from Java. The halo is its own circle, painted by CSS, so it is the
  right green in both palettes and all five accents without knowing any of them.
- **The rolling numbers outlive the cards that hold them, and that is the whole trick.** The
  dashboard grid is rebuilt from scratch on every settle, because a card's shape changes with its
  state. A `NumberRoll` built inside that rebuild is a brand-new node already showing the new
  value — the roll would be in the code and never once on screen. So the rolls are kept on the
  grid, keyed by the card's kicker rather than by its position, and moved into each freshly built
  card. `WaveTwoInteractionTest` asserts the node is *the same instance* across a re-render,
  which is the assertion that tells "implemented" from "reachable".
- **The bell badge keeps its pop rather than gaining a roll**, and this is a deliberate departure
  from the spec's "submitted count, unread badge". The badge is a two-character pill inside a
  16px circle; digits rolling vertically through a clip that small read as a glitch, not as a
  count changing. The existing scale-pop already marks the moment, and it is what the wave-1
  tests pin. The roll is used where it has room to be legible: the card numbers and the live
  card's submitted count.
- **Reduced motion collapses everything to an 80ms fade** — travel to zero, staggers to zero,
  ambient loops not started at all. It works because `Animations` asks `Motion` for its durations
  and distances rather than using the ones it was handed: a switch honoured by the methods that
  remembered to check it is a switch half the app ignores. Off by default; on via
  `-Dhsts.motion.reduced=true` or `Motion.setReducedMotion(true)`.
- **JavaFX 21 CSS has no transitions.** Every duration above that decorates a *hover* is
  therefore played from Java. The row hover *tint* itself is an instant CSS state change; the
  affordance beside it fades in over the specified 100ms. That is the one place the spec is met
  in spirit rather than to the millisecond, and it is a toolkit limit rather than a shortcut.

---

## 6. Rule 5 — the take-exam screens stay still

`ExamAttemptMotionGuardTest` reads the source of six screens and asserts none of them references
any entrance-animation API — `Animations`, `javafx.animation.*`, or any `*Transition` by name.
Comments are stripped first, so commenting a line out fails exactly like deleting it.

It is a guard rather than a review note because wave 2 put motion on almost every surface in the
app, all of it house API and all of it one import away, and the screens it is wrong on look
exactly like the screens it is right on. Nobody would add it on purpose; somebody would add it by
consistency. A reviewer would not catch it either — a 180ms fade on a screen you expect to be
lively is invisible unless you are timing it.

**In scope**: `TakeExamView`, `ExamEntryView`, `ExamFormView`, `QuestionCardView`,
`AnswerGridView`, `QuestionChip`.

**Deliberately out of scope, and asserted as such**: `ExamDoneView` (shown after submission — the
clock has stopped, and the scale-in marking the moment is the one piece of motion on this journey
that is unambiguously welcome) and `ExecutionMonitorView` (the teacher's screen; its `Timeline`
is a once-a-second ticker, which is a clock and not an animation).

---

## Manual click-checklist

**Additive to wave 1's 34 points.** Reseed first (`SeedMain --reseed`), then sign in per role.
Everything below is a thing a test cannot assert: colour, weight, spacing, and whether motion
feels right.

### Both palettes, all five accents (do this once, then again in dark)

35. Settings → switch to **Dark**. Every surface below is still legible; no white card on a dark
    page and no black text on a dark card.
36. Settings → each of the five accents in turn. The dashboard link lines, the live progress bar,
    the My Grades hero band and the sparkline's modal bar all follow. Nothing stays indigo.
37. In dark mode, the My Grades hero band's text and its ring are legible against the accent.

### Teacher

38. Teacher home: **four** cards, each with a small uppercase label above its number.
39. The greeting has a sentence under it saying what today contains. Stop the server and revisit:
    it says the summary could not be loaded, **never** "nothing is live".
40. With a live sitting (release one, join it as a student): the LIVE NOW card shows a green dot
    with a halo expanding out of it, the exam name, "Code XXXX · closes HH:MM", a slim bar, "N of
    M submitted" and the time left.
41. Submit as the student. The submitted count **rolls** to its new value rather than snapping.
42. Up to three student rows on that card, each with a status chip. With more than three
    students, the card says the rest are in the monitor.
43. Close the sitting and mark it. The LAST CLOSED SITTING card shows the mean as a big number,
    "N of M passed", and ten slim bars with one in full accent.
44. Hover any dashboard card. It lifts a little and its shadow deepens — smoothly, not as a jump.
45. Click each card. It opens the screen its link line named.

### Coordinator

46. Coordinator home: two cards with kickers, chips ("To do" when the queue has something, "All
    clear" when it does not) and link lines.
47. The greeting's sentence names the queue and how many teachers it came from.

### Student

48. Student home: two cards, kickers, and the latest-grade card carries a pass chip.
49. Student → **My Grades**. A coloured band at the top with a ring showing the term average,
    a headline and one warm sentence. No "next exam" box (nothing on the wire fills it).
50. Below it, grades as **cards in three columns**, not a table.
51. Hover a card: it lifts and "Open paper →" appears. Click once — the marked paper opens.
    "Back" returns to My Grades.
52. The grid ends with a **dashed** card: muted icon, "Your grade appears the moment a teacher
    approves it".
53. A student with no grades at all: the empty state, and its icon breathes slowly.
54. Resize the window narrow. The grid scrolls in its own area; the hero stays put.

### Tables (any role)

55. Coordinator → Approvals. Column headings are small, uppercase and widely spaced, on a faintly
    tinted band. Rows are comfortable, not cramped.
56. Hover a row: the whole row tints and an accent "Open →" appears at its right end.
57. Select a row: soft accent background **and** a 3px accent bar down its left edge.
58. Principal → Reports, and Data → all three tabs: the numeric columns are right-aligned and
    line up digit under digit.
59. Open a table for the first time: rows fade in quickly, one just after another. Now click a
    column header to re-sort — **they must not animate again**.

### Notifications

60. Click the bell. Each row has a rounded coloured square on its left: green for a published
    grade, red for a rejection, accent for the rest.
61. An unread row is faintly tinted, has a small accent dot on its right, and a bolder title.
62. Watch the close: it fades out over about a tenth of a second rather than vanishing.
63. There is no "See all" link (deliberate — see §3).

### Motion

64. Navigate between rail screens. The incoming screen fades and rises slightly; the outgoing one
    does not move.
65. Open any confirmation dialog. It scales up very slightly as the scrim fades in behind it.
66. **Take an exam.** From the code entry through every question to submit: **nothing fades,
    slides or staggers.** The screen is simply there. This is the one to check twice.
67. Restart with `-Dhsts.motion.reduced=true`. Everything still works and nothing travels: no
    rise, no stagger, no lift, no breathing icon, no halo. Screens just appear.

---

## Test inventory

**New:**

| Test | Layer | What it holds |
|---|---|---|
| `client/features/home/DashboardSummaryTest` | FX-free | 21 tests: pluralisation, verb agreement, clause dropping, loading vs failed vs quiet, all four roles, the house voice, and the greeting/summary pairing |
| `client/ui/components/logic/KickerTextTest` | FX-free | 9 tests; the load-bearing one proves the tracking is **lossless and reversible**, so a heading on screen and the constant behind it cannot drift |
| `client/ui/components/logic/SparkbarSpecTest` | FX-free | 10 tests: heights, the minimum-visible rule, the mode, the tie-is-not-a-mode rule, null buckets |
| `client/ui/components/logic/RingGeometryTest` | FX-free | 9 tests: the clockwise-from-twelve direction, clamping, NaN |
| `client/features/exam/ExamAttemptMotionGuardTest` | source scan | 8 tests: rule 5 across six attempt screens, plus a teeth check and the two stated exclusions |
| `client/ui/theme/StylesheetParseTest` | CSS parser | 10 tests: **all seven shipped stylesheets parse with zero errors**, the wave-2 selectors survive parsing, and dark mode is still tokens only |
| `client/ui/WaveTwoInteractionTest` | TestFX | 12 tests: four cards with kickers and links, kickers reading as their constants once untracked, the summary line, the live card's dot and bar, popover badges and the unread dot, badge tones, the My Grades card grid, the hero ring, the hidden next-exam slot, one-click drill-in, the dashed slot's words, and that a card's number is the same node across renders so the roll can actually play |

**Extended:**

| Test | Added |
|---|---|
| `DashboardSessionTest` | two nested classes — `LiveCard` (7) and `LastClosedCard` (5) — plus three teacher tests for the chip and the link lines |
| `DashboardCopyTest` | the composed lines (`codeLine`, `submittedLine`, `timeLeftLine`, `passedLine`), kickers stored in sentence case, links naming a destination |
| `MyGradesCopyTest` | the house reflective scan (new here), the hero counting line, the pass chip, the dashed slot |
| `MyGradesSessionTest` | nested `Hero` (7): the unweighted mean, the empty-transcript zero, the effective score, the distinct course count, the empty next-exam slot |
| `NotificationPresenterTest` | badge tones, including that they **agree with the toast** for the same push |
| `MotionTest` | nested `WaveTwoSpec` (8) and `ReducedMotion` (8) |

**Updated deliberately, each with the reason in the code:**

| Test | Change | Why |
|---|---|---|
| `DashboardSessionTest.Teacher.countsOnlyCurrentSittings` → `countsLiveAndScheduledApart` | pins two numbers where it pinned their sum | wave 1's one card became two; the assertion split with the design, and now says more than it did |
| `DashboardSessionTest.Teacher` (routes, empty hints, one-failure) | updated for four cards | the card list changed; each assertion still holds the same rule |
| `WaveOneInteractionTest` — the three popover-dismissal tests | wait for the panel to leave instead of asserting it is already gone | wave 2 gives dismissal a 100ms fade, so the node now leaves when the fade ends. **Not weaker**: it still demands the panel goes away and fails just as loudly if it does not. `Popover.isOpen()` still answers `false` the instant close is called, so no behaviour waits on the fade — only the node's removal does |

**Not weakened anywhere.** Where the remodel broke an assertion, the fix went into the assertion's
*subject* or into the production code, never into the threshold.

---

## Coverage

Four new FX widgets were added to the JaCoCo exclusion list, on exactly the terms the rest of that
list uses — each is a node builder over a measured logic class one package deeper, and the logic
class is where anything can be wrong:

| Excluded (view) | Measured (logic) | What the logic decides |
|---|---|---|
| `Kicker` | `KickerText` | the uppercase and the tracking JavaFX CSS cannot do |
| `Sparkbars` | `SparkbarSpec` | which bar is the mode, how tall each is |
| `ProgressRing` | `RingGeometry` | the arc's angle, direction and clamping |
| `NumberRoll` | `Motion` | the roll's duration and its reduced-motion collapse |

`DashboardSummary` was **not** excluded, and neither were the session extensions. They are the
wave's decisions and they are measured.

**Coverage of the packages this wave touched**, from the gate's own report
(`target/site/jacoco/index.html`):

| Package | Instruction coverage |
|---|---|
| `client.features.home` | **99%** (four sessions, `DashboardSummary`, `DashboardCard`, `DashboardCopy`) |
| `client.features.results` | **99%** (`MyGradesSession`, `MyGradesCopy`) |
| `client.features.notify` | **100%** |
| `client.ui.anim` | **100%** (`Motion`, including the reduced-motion switch) |
| `client.ui.components.logic` | **99%** (now includes `KickerText`, `SparkbarSpec`, `RingGeometry`) |
| `client.ui.components` | 96% (`BackLink` and `Popover`, still deliberately not excluded) |
| Bundle total | **98%** |

---

## Verification

**Full gate, green.**

```
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_wave2 ./mvnw -B clean verify
```

```
Tests run: 5862, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (11:41)
```

5862 rather than wave 1's 5529: this wave adds 333, of which 245 are the two reflective copy
scans expanding over the new constants.

The JaCoCo bundle rule (90% instruction coverage, `jacoco-check` at `verify`) passed at 98%.

**For the record, and for the next person who sees it:** two earlier attempts at this proof were
invalidated by my own concurrent `mvn compile` rewriting `target/classes` under a running fork,
producing a cascade of `NoClassDefFoundError` across unrelated server tests. That is the same
measurement error wave 1's report records, and it is a measurement error rather than a
regression. The run above was made with the tree quiet.

---

## Dropped, with reasons

1. **The popover's "See all" footer** — no all-notifications route exists; a centred accent link
   that opens nothing is the defect F-12 was raised for. §3.
2. **The hero's "Next exam" value** — no verb answers it (§4). The slot itself is built, driven
   and hidden, with a test asserting it is empty and recording why.
3. **A true two-stop gradient on the My Grades hero** — not achievable from a looked-up colour
   across five palettes; the canvas's approved flat-plus-radial-wash fallback is what shipped
   (§4).
4. **CSS-timed hover transitions** — JavaFX 21 CSS has no `transition`. The card lift is animated
   from Java at the specified 150ms; the row hover tint is an instant state change with the
   affordance fading in over 100ms beside it (§5).
5. **Letter-spacing and tabular figures as CSS** — neither property exists on this toolkit; both
   are achieved in Java and with the monospace stack respectively, with the accessibility cost
   of the first paid by `Kicker` (§2).
6. **The unread badge's number roll** — the badge is two characters inside a 16px circle, and
   digits rolling through a clip that small read as a glitch. It keeps its scale-pop, which
   already marks the change. The roll is used where it has room to be legible: the dashboard card
   numbers and the live card's submitted count (§5).
7. **Bank tables' `columnWidths`** — still `client/features/bank/**`, still Member A's PR-B. The
   bank list does now inherit the shared table treatment, because it was always built on
   `DataTable`; no file in that package was edited (§2).
