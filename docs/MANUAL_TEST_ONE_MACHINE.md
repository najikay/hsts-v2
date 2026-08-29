# HSTS — manual test, one machine

**A guided walkthrough for a person.** Every step tells you who to be, what to click, what you
should see, and gives you a box to put an X in. Usernames, passwords, ID numbers and codes are
written where you need them, every time. The appendices at the end (the per-screen control
inventory, the situations list, the role-interaction matrix, the requirement map) are the
"nothing forgotten" backstop; the walkthrough is what you actually run.

Two-machine and network checks are in `MANUAL_TEST_TWO_MACHINES.md` (Omar's).

**How to use this file.** Copy it to `docs/manual-round-N-notes.md`, work through it, put `X`
in the boxes that passed, and write what you saw under any box you could not tick. Your words,
the screen named, no fixing. Paste the file back when you stop.

| Round | Date | Got to | Stopped because |
|---|---|---|---|
| 1 | 2026-08-28 | student flow, teacher side, bank, grading | 18 findings, 4 blockers |
| 2 | 2026-08-29 | (v2 file) | 7 notes, 1 blocker (login recovery) |
| 3 | | | |

**Passwords: `demo123` for every account.**

| Who | Username | ID number (for the exam identity step) |
|---|---|---|
| Student, the demo one (Algebra 11, Java 21, Databases 22) | `maya.levi` | `374301851` |
| Student, not in Algebra (Calculus 12, Java 21) | `noam.peretz` | `385612098` |
| Student, Algebra, the one who timed out in the seed | `omer.katz` | `361489206` |
| Teacher of Algebra 11 and Calculus 12 | `dana.cohen` | — |
| Java 21 co-teachers | `avi.mizrahi`, `tamar.shani` | — |
| Teacher of Databases 22 **and** coordinator of Computer Science | `michal.sharon` | — |
| Coordinator of Mathematics, teaches nothing | `rina.barak` | — |
| Principal, read-only | `principal.avia` | — |

Seeded facts you will meet: exam code **`2075`** is a live Algebra sitting; **`5164`** is
scheduled for later today; **`4821`**, **`7390`** and **`3318`** are closed (`3318` is Dana's
sitting that is still awaiting grading). Algebra questions **11003,
11004, 11006, 11008** belong to no exam (you may delete them); the others are in exams.

The walk takes about three and a half hours. The only real wait is a 2-minute exam you create
yourself in Part 3. Nothing else waits on anything.

---

## Part 0 — Start everything (10 min)

In PowerShell, in `C:\dev\hsts-v2`:

```
git pull
.\mvnw -DskipTests clean package
java -jar target\hsts-server.jar
```

- [ ] 0.1 The terminal prints the Flyway lines and a "listening" line, and the **server
      console window** opens.
- [ ] 0.2 **Only if this is a fresh database** (first ever run, or after a wipe): on the
      console press **Load demo data if missing** once. On a database that already has the
      seed it answers UNCHANGED. Never press **Reload demo data** during a walk — the walk
      creates its own data and later rounds build on it.
      *(One exception, once: right after pulling the 2026-08-29 seed changes, press Reload
      demo data one time so Maya's grade carries its teacher's note and Dana's sitting
      `3318` awaiting grading exists. The total then reads 414 rows.)*
- [ ] 0.3 Open a second PowerShell and start a client: `java -jar target\hsts-client.jar`.
      Start more clients the same way whenever a step says "second window".
- [ ] 0.4 **The client must not warn about the server "now identifying itself as …"** on a
      rebuild any more (U-22: the id now lives in the project root, which a clean does not
      touch). The first launch after pulling that fix may warn one last time; choose to
      continue and it re-pins. Any warning after that, with no rebuild, is a note.

---

## Part 1 — Before signing in (10 min)

- [ ] 1.1 The client found the server on its own and landed on **Login** showing
      "Connected to &lt;server name&gt; · change server".
- [ ] 1.2 Click **change server**. Type address `10.0.0.1` port `5555`, click **Connect**:
      a plain-English sentence ("Nothing is listening…" / "did not answer") — no Java class
      name, no brackets.
- [ ] 1.3 Click **Back to the server list** → the list is back. Click **Look for servers
      again** → it re-finds the server. Pick it → Login.
- [ ] 1.4 Username `maya.levi`, password `wrong`, **Sign in** — one generic sentence. Repeat
      four more times (five wrong in total). Now the **right** password `demo123` → refused
      with the too-many-attempts sentence. Wait 30 seconds → `demo123` works. **Sign out**
      (profile menu, top right).
- [ ] 1.5 Back on Login: **stop the server** (Ctrl+C in its terminal). Within a few seconds
      the chip reads **Disconnected**, the label "Not connected", **Sign in is greyed out**,
      and a **Reconnect** link appears.
- [ ] 1.6 Start the server again (`java -jar target\hsts-server.jar`). Click **Reconnect** →
      the connect screen finds it → Login shows Connected → sign in as `dana.cohen` /
      `demo123` **in this same window** — it works. *(U-17: this is the one that needed a
      new window before.)*

---

## Part 2 — Dana, the teacher: bank and exam (40 min) — `dana.cohen` / `demo123`

**Dashboard**
- [ ] 2.1 Cards: **Sittings in progress** (2075 with its code, closing time, minutes left),
      **Today and next** (5164), **Awaiting grading**, **Class average**, **Your exams**.
      Click every card's link; each opens the right screen; the navbar **Back** (top left)
      returns.
- [ ] 2.2 The rail reads Dashboard, Question Bank, Exams, Releases, Live Monitor, Grading,
      Results, Study Bot, Settings — no Approvals. Every icon is drawn. Click the collapse
      button at the top of the rail; icons keep tooltips; expand again.

**Question Bank** (rail)
- [ ] 2.3 Course picker shows only Algebra 11 and Calculus 12. Pick Algebra 11. Set topic
      `Quadratic functions`, difficulty `Easy`, then type `roots` in the search box: the list
      narrows at each step and the count label follows. Click **Clear filters**.
- [ ] 2.4 Click each column header twice: the sort flips both ways. The table fills the
      whole width; the question column is the widest.
- [ ] 2.5 Click question **11005**. The detail pane shows the stem, four answers with one
      marked **Correct**, topic, difficulty, version, and the illustration (a loading state,
      then the picture). Click **Version history** → v1 and v2 with different stems; **Hide
      history**.
- [ ] 2.6 Click **Add question**. Leave everything empty and try **Add question** — refused.
      Type stem `Round 3: what is 7 × 8?`, answers `56`, `56`, `54`, `64` (two the same),
      mark the first correct → refused (answers must differ). Change the second to `48`,
      unmark all → refused (one must be correct). Mark `56`. Topic: type `Round 3`
      (a brand-new topic). Difficulty Easy. **Choose image** → any PNG on your machine →
      "Illustration attached"; remove it; attach it again.
- [ ] 2.7 Click **Cancel** → "Leave without saving?" → **Keep editing**. Now **Add question**.
      The list shows the new question with a five-digit id starting `11` that you did not
      type. Write the id here: ______
- [ ] 2.8 Add three more the same way, topic `Round 3`: an Easy one (`Round 3: 9 + 6`,
      answers `15` ✓ / `14` / `16` / `13`), a Medium one (`Round 3: solve 2x = 10`, `5` ✓ /
      `10` / `2` / `20`), a Hard one (`Round 3: solve x² = 49, x > 0`, `7` ✓ / `-7` / `49`
      / `14`). Ids: ______ ______ ______
- [ ] 2.9 Select your first new question → **Edit** → change the stem to end with `?!` →
      **Save as a new version** → Version history shows v2.
- [ ] 2.10 Select **11005** → **Delete** → a dialog titled "This question is in use"
      **names the exams** it sits in → **Close**. Select **11004** → **Delete** →
      "Delete this question?" → **Delete** → it disappears.
- [ ] 2.11 Switch the course picker to Calculus 12: a different list. There is no Java in her
      picker (she does not teach it).

**Exams** (rail)
- [ ] 2.12 Rows: Midterm: Algebra (v1 REJECTED, v2 APPROVED), Quiz: Inequalities (DRAFT),
      Midterm: Calculus (PENDING). Hovering a row shows **no** "Open" hint; clicking selects
      it and fills the versions panel on the right. Midterm: Algebra v1's card shows its
      rejection reason.
- [ ] 2.13 Click **New exam** → **Algebra 11**. The builder opens (navbar Back present).
- [ ] 2.14 Exam details: name `Round 3 Quick Check`; **How long students get** `2` (the hint
      says 1 to 480); Student instructions tab: `Two minutes. Answer what you can.`; Teacher
      notes tab: `Round-3 timing check.`
- [ ] 2.15 **Choose questions** tab → **Add from the bank** → search `Round 3` → **Add** all
      four → **Done**. Points 40 / 20 / 20 / **10**: the sum reads 90 and **Create exam is
      disabled**. Change the last to 20 → enabled. Click **Move up** on the last row and
      **Move down** on the first; **Remove** one and add it back. The edited question shows
      "The bank has a newer version" → **Use the newer version**.
- [ ] 2.16 **Compose automatically** tab: **Add a topic** `Round 3`, Hard `20` → **Compose
      the exam** → a report naming the shortfall, **nothing created**. Change to Easy `2` →
      composes. Go back to Choose questions and keep your manual paper.
- [ ] 2.17 **Create exam** → "Saved." and the Exams list shows `Round 3 Quick Check` with a
      six-digit id starting `1011` and a DRAFT chip. Id: ______
- [ ] 2.18 Open it again: change duration to `0` → refused; `481` → refused; empty the name
      → refused; back to `2`. **Submit for approval** → the chip reads PENDING_APPROVAL.
- [ ] 2.19 Select Midterm: Algebra → **Edit** on v2 → the builder says it is making a new
      version (v3); **Save draft**; the list shows v2 still APPROVED and v3 DRAFT. Leave v3.

---

## Part 3 — Rina decides, Dana releases, students sit it (35 min)

**Second window: `rina.barak` / `demo123`** (keep Dana's window open)
- [ ] 3.1 The bell shows a badge; open it: **APPROVAL_REQUESTED** for Round 3 Quick Check.
      Dashboard card "Waiting for you" counts it.
- [ ] 3.2 Rail **Approvals**: the queue lists Round 3 Quick Check and Midterm: Calculus. Open
      Round 3: the paper as a student sees it, the "Teacher only" panel with Dana's note,
      the "Answer key" panel.
- [ ] 3.3 Click **Send back** with an empty reason → refused. Reason `Swap the last two
      questions.` → **Send back**.

**Dana's window**
- [ ] 3.4 Her bell: **APPROVAL_REJECTED**; Exams → Round 3's card shows the reason and the
      chip REJECTED. **Edit** → the builder opens v2 → move the last row up → **Save draft** →
      **Submit for approval**.

**Rina's window**
- [ ] 3.5 The queue shows Round 3 **v2** only; her bell has **APPROVAL_SUPERSEDED** for v1.
      Open v2 → **Approve** → "Approve this exam?" → **Approve**. Also open Midterm:
      Calculus → Approve. Queue: "Nothing waiting".
- [ ] 3.6 Dana's window, without touching it: bell **APPROVAL_APPROVED**, and the Exams chip
      is already APPROVED.

**Dana's window: Releases** (rail)
- [ ] 3.7 Rows: 5164 Scheduled, 2075 Live with participant counters, 4821 and 7390 Closed.
- [ ] 3.8 **Release an exam**. The "Approved exam" picker offers only approved versions
      (Round 3 v2, Midterm: Algebra v2, Midterm: Calculus v1).
- [ ] 3.9 Pick Round 3 v2. Exam code: type `R3Q` → refused (four characters); `R3Q!` → refused;
      click **Generate for me** → four letters/digits appear; overwrite with `R3QC`. Set
      Closes **before** Opens → a complaint appears; set Opens now and Closes now + 6 minutes.
      **Release it** → "Read this code out" → **Copy code** → **Done**. The row is **Live**.
- [ ] 3.10 **Release an exam** again: Midterm: Algebra v2, Opens now + 20 minutes, Closes
      + 40 → **Scheduled**. On the row click **Cancel release** → "Cancel this release?" →
      **Cancel it** → gone. (A Live row has **Close early**, never Cancel.)

**Second window: sign Rina out, sign in `maya.levi` / `demo123`**
- [ ] 3.11 Dashboard: "Your courses" lists three; in the **Take an exam** card type `R3QC` →
      **Enter** → the Take Exam screen opens as **"Confirm your exam"** with the code shown
      read-only and **Confirm and continue** already enabled. Click **Back to my dashboard**;
      do it again; this time **Confirm and continue**.
- [ ] 3.12 "Confirm it is you": the summary says Round 3 Quick Check, 2 minutes, 4 questions.
      Click **Back** → you are on the code step with the code still there → forward again.
      ID `385612098` (Noam's) → refused on the field, no clock. ID `374301851` → the paper.
- [ ] 3.13 The countdown is running. Click one answer on question 1 → the indicator shows
      **Saving** then **All changes saved**; the chip for question 1 changes. Use **Next** /
      **Previous** and the chips. **Do not hand in.**

**Third window: `omer.katz` / `demo123`, ID `361489206`**
- [ ] 3.14 Take Exam → `R3QC` → Continue → ID → the paper. Answer all four. **Hand in** → the
      dialog shows the answer grid, the remaining time and the note about unanswered
      questions → **Keep working** once → **Hand in** again → **Hand in** → the **Handed in**
      screen: time, minutes, summary, one button **Back to my dashboard**. Enter `R3QC`
      again → "already handed in".

**Dana's window: Live Monitor** (rail)
- [ ] 3.15 Pick Round 3 Quick Check: Maya **Started** with a countdown, Omer **Handed in**;
      counts Started 2 / Handed in 1 / Timed out 0.
- [ ] 3.16 Click on Maya's window, then on another window for two seconds, then back: the
      monitor row shows "Her exam window last lost focus at …"; Maya's screen shows nothing.
- [ ] 3.17 Set the spinner to **1** and click **Add time**: on Maya's screen the countdown
      chip flashes, "+1:00" floats up, a toast names Dana Cohen; her bell has
      **TIME_EXTENDED**.
- [ ] 3.18 Wait. The chip turns amber, then red, then at zero Maya's screen is taken over by
      **Time is up** — no confirmation, the summary, one button. The monitor row reads
      **Timed out**; counts 2 / 1 / 1. Dana's bell: **GRADING_DUE**. Within a few minutes
      the Releases row is **Closed**.
- [ ] 3.19 Maya: **Back to my dashboard**. Enter `R3QC` → "already handed in".

---

## Part 4 — Grade and publish (15 min)

**Dana's window: Grading** (rail)
- [ ] 4.1 "Waiting for you" lists Round 3 Quick Check **and the seeded sitting `3318`**
      (Midterm: Algebra, sat yesterday by four students, awaiting grading); open Round 3:
      two rows with their **Auto** scores (rows, not a loading skeleton). Open `3318`: four
      rows (Noa 85, Shira 75, Daniel 60, Itay 45). Leave `3318` unapproved for now; it is
      the demo's grading act.
- [ ] 4.2 Select Maya → **Change score…**: score `101` → refused; empty reason → refused;
      score `40`, reason `Method credit`, comment `Well tried under time pressure` → save →
      the row shows the adjusted marker.
- [ ] 4.3 **Select all** → **Approve selected**.
- [ ] 4.4 **Results** (rail) → Round 3 → the sitting: toggle histogram / table; stat cards
      (mean, median, σ, min, max, pass rate at ≥ 55, deciles); the table has attempt status
      and minutes. Print layout on → chrome disappears → **Exit print view**.
- [ ] 4.5 Results also lists Midterm: Algebra → 4821: `omer.katz` Timed out, 75 minutes.
- [ ] 4.6 Dashboard → **Class average** now shows Round 3's mean.

**Maya's window, untouched since 3.19**
- [ ] 4.7 The bell badge went up on its own: **GRADE_PUBLISHED**. **My Grades** shows a new
      card: course, Passed / Below the pass mark, `Round 3 Quick Check`, **Teacher: Dana
      Cohen**, the score 40, the date, **"Reviewed by your teacher"**, and the note "Well
      tried under time pressure". The justification "Method credit" appears **nowhere**.
- [ ] 4.8 **Open paper →**: "Teacher: Dana Cohen" under the title, attempt status Timed out
      with the minutes, "Your teacher's note", and every question with your answer / the
      correct answer / Correct · Wrong · Not answered and points. Print layout → **Exit print
      view**. Navbar **Back** → My Grades.
- [ ] 4.9 Her Midterm: Algebra card (seeded) also shows Teacher: Dana Cohen and a note.
- [ ] 4.10 The term-average ring: the filled arc sits exactly on the grey track and ends where
      the number says.
- [ ] 4.11 Bell panel: click one row → it opens My Grades; **Mark all read** → badge gone.
- [ ] 4.12 Omer's window: same push; his card and paper.

---

## Part 5 — Michal, the dual hat (8 min) — `michal.sharon` / `demo123`

- [ ] 5.1 Her rail has **Study Bot** (teacher) and **Approvals** (coordinator).
- [ ] 5.2 Exams → **New exam** → Databases 22 → name `Self check`, 5 minutes, add three
      questions from the bank at 40 / 30 / 30 → **Create exam** → **Submit for approval**.
- [ ] 5.3 Approvals: the queue shows Self check with the badge **"You wrote this one"**. Open
      → the self-approval note → **Approve** → allowed.

---

## Part 6 — Avi and Tamar: locks and the bot manager (20 min)

**Window 1: `avi.mizrahi` / `demo123` · Window 2: `tamar.shani` / `demo123`**
- [ ] 6.1 Avi: Question Bank → Java 21 → select **21003** → **Edit** (leave it open). Tamar:
      Question Bank → Java 21 → the 21003 row shows **"Editing … Avi Mizrahi"**; she opens it
      → read-only with a lock banner.
- [ ] 6.2 Avi: **Cancel** (close the editor). Tamar's badge clears by itself; she can Edit.
      She closes.
- [ ] 6.3 Avi opens 21003 again and **signs out** while it is open. Tamar's badge clears.
      Sign Avi back in.
- [ ] 6.4 Avi: **Study Bot** (rail) → the left column lists one card per course he teaches →
      the **Java 21** card → **Manage**. On the right: the bot's name, the toggle **Students
      can use this bot**, Information sources (5). **Add text** → paste a paragraph about Java
      collections → saved and listed. **Add a file** → a small PDF → "Reading that file on
      the server" → listed. Add a Word file → listed. Rename any text file to `.pdf` and add
      it → a sentence explains it could not be read.
- [ ] 6.5 Avi: **Edit** on the text source (leave it open). Tamar: Study Bot → Java 21 →
      Edit the same source → lock banner. Avi saves. Tamar's bell: **BOT_SOURCE_CHANGED** for
      each change so far. Avi: **Remove** one source → "Remove this source?" → **Remove it**.
- [ ] 6.6 Avi: switch **Students can use this bot** off. (Part 7 checks the student side.)
- [ ] 6.7 Avi: **Bot activity**: Questions asked, Busiest day, Asked most often, the 30-day
      chart. **No student name anywhere.** Navbar Back.
- [ ] 6.8 Dana's window: Study Bot → the left column lists **two cards**, Algebra 11 and
      Calculus 12, one per course she teaches (U-26). Each card shows the bot's name or **No
      study bot yet**, an **Active** / **Inactive** chip and its source count; the header says
      **One study bot per course. Co-teachers share it.** **Create the study bot** appears
      only on a card with no bot, **Manage** on the others. Click each card in turn: the right
      pane follows, sources and all. **If you can make a second bot for one course, write the
      exact clicks (U-14).**

---

## Part 7 — Maya and the bot, C-4, close early (18 min) — call the lead first (live keys)

**Maya's window**
- [ ] 7.1 **Study Bot** (rail): the Course picker offers Algebra 11, Java 21, Databases 22.
- [ ] 7.2 Pick Java 21 → ask anything → the bot-is-off sentence (Avi switched it off). Avi
      switches it on. Ask again on the same screen → answered.
- [ ] 7.3 Pick Databases 22 → `What does a LEFT JOIN return when there is no match?` → "The
      bot is thinking", then a grounded answer. `Who won the 2022 World Cup?` → the polite
      out-of-scope sentence. Send an empty question → refused. Send three questions within a
      few seconds → the "too fast" sentence.
- [ ] 7.4 **Past conversations** → both are listed with times → **Reopen** one → continue it
      with one more question. **New conversation**.
- [ ] 7.5 Take Exam → `2075` → Continue → ID `374301851` → the seeded Algebra paper. Answer
      one. Leave it open.
- [ ] 7.6 Study Bot → **Algebra 11** → ask → the locked sentence naming the exam, and the box
      is **still usable**. Pick **Databases 22** → ask → "You are taking an exam" notice →
      **Continue and notify** → answered; ask again → no second notice.
- [ ] 7.7 Dana's window: bell **INTEGRITY_ALERT** naming Maya; Live Monitor → 2075 → Maya's
      row shows "Opened another course's study bot at …".
- [ ] 7.8 Dana: **Releases** → 2075 → **Close early** → "Close it now". Maya's window: **Time
      is up**. Releases row Closed; counts frozen; Grading gets 2075 (**GRADING_DUE**).
- [ ] 7.9 Maya: Study Bot → Algebra 11 → ask on the same screen → answered (the lock ended
      with the exam).
- [ ] 7.10 Dana: Grading → 2075 → approve Maya's row. Maya's bell.

---

## Part 8 — Refusals with the other students (8 min)

**Second window: `noam.peretz` / `demo123`**
- [ ] 8.1 Take Exam: `2075` → "not enrolled" on the code field. `5164` → "not open yet".
      `ZZZZ` → "unknown code". `12` → "Codes are 4 letters or digits."
- [ ] 8.2 His dashboard lists Calculus 12 and Java 21 only.

**`omer.katz`**
- [ ] 8.3 My Grades → Midterm: Algebra (seeded) → Open paper: **Timed out**, four questions
      **Not answered**, Teacher: Dana Cohen, his note.

---

## Part 9 — The principal (12 min) — `principal.avia` / `demo123`

- [ ] 9.1 Rail: Dashboard, Data, Reports, Settings. Nothing on any screen adds, edits,
      deletes or approves.
- [ ] 9.2 **Data**: segments **Questions / Exams / Results**; type `Round 3` in the filter;
      pick a course; clear. The Questions segment with no filter shows the "too many" hint
      and pages.
- [ ] 9.3 **Reports**: pick Mathematics; **By teacher**, **By course**, **By student** in turn;
      the cards and the "Closed sittings" table include Round 3 Quick Check with the numbers
      from 4.4. Print layout → exit. Repeat for Computer Science.

---

## Part 10 — Settings, shell, sessions (10 min) — any account

- [ ] 10.1 **Settings**: Light, Dark, System (it says what it resolved to); each accent
      palette; every open window follows at once; **Reset to defaults**.
- [ ] 10.2 Profile menu (top right): the theme radios and **Sign out**.
- [ ] 10.3 Resize the window small and large on three different screens: nothing overlaps.
- [ ] 10.4 With `dana.cohen` signed in, start another client and sign in as `dana.cohen` →
      refused with the already-signed-in sentence. Sign the first out → the second succeeds.
- [ ] 10.5 Looking back over the whole walk: every refusal was a sentence, never a code;
      every wait showed a working state; no refresh button exists anywhere.

---

## After the walk

Paste this file back with the X's and your notes. Every note becomes a numbered register entry
with a ruling; the ticked steps flip the matching acceptance cases to passed on screen.

---
---

# Appendices — the "nothing forgotten" backstop

## Appendix A — Every screen and every control, per role

Walk each screen top to bottom and press everything not already pressed above.

**Server console:** address + candidate picker (LAN IPv4, other adapters listed) · fingerprint
line · DB status · connected clients (live) · log tail Pause / Copy / Clear · Load demo data if
missing (UNCHANGED when seeded) · Reload demo data (asks first) · health panel.

**Connect / Login:** discovery picker (name, address, fingerprint; pin remembered) · change
server → address, port, Connect, Back to the server list, Look for servers again · username,
password, Sign in · status row (Connected · change server / Disconnected · Reconnect).

**Shell, every role:** rail (collapse/expand, tooltips) · breadcrumbs · navbar Back on every
non-rail screen · bell (badge, rows with icon and time, click-through, mark one, Mark all read,
"Nothing yet") · toasts · profile menu (theme radios, Sign out) · reconnect banner with Retry.

**Student:** Dashboard (Your courses; Take an exam card with Enter) · Take Exam (code step with
Back to my dashboard; confirmation with Use a different code; identity step with Back; the
paper: countdown, progress, save indicator, chips, options, Previous / Next, Hand in dialog
with grid, Keep working / Hand in; Handed in and Time is up screens with one button) · My
Grades (ring, next exam, cards with Teacher / note / Reviewed marker / Open paper) · Checked
exam (print toggle, Exit print view) · Study Bot (Course picker, ask box, Send, thinking, Past
conversations, New conversation, integrity notice Continue and notify / Not now) · Bot history
(Reopen) · Settings.

**Teacher:** Dashboard (Sittings in progress, Today and next, Scheduled ahead, Awaiting
grading, Class average, Your exams, each with a link) · Question Bank (course, topic,
difficulty, search, Clear filters, sortable columns, Editing column, detail pane, Version
history / Hide history, Edit, Delete, Add question) · Question editor (stem, four
answers with the correct radio, Topic, Difficulty, Choose image / remove, Add question / Save
as a new version, Cancel with Leave without saving?, lock banner, stale and gone dialogs) ·
Exams (rows, version chips, reason cards, Edit, New exam menu) · Exam builder (details,
duration, two text tabs, Choose questions with Add from the bank / search / Add / Done,
Points, Move up / down, Remove, newer-version badge, Compose automatically with Add a topic /
counts / Compose the exam, Create exam / Save draft, Submit for approval) · Releases (chips,
counters, Monitor, Cancel release, Close early, Release an exam dialog with picker / code /
Generate for me / Opens / Closes / Release it, Read this code out with Copy code) · Live
Monitor (chooser, counts, rows, attention text, bot flag, Add time spinner) · Grading (queue,
table, Select all, Approve selected, Change score… with reason and comment) · Results (exam
rail, execution picker, histogram / table, stat cards, print) · Study Bot manager (the course
list, Manage, Create the study bot, active toggle, sources, Add a file, Add text, Edit,
Remove, Bot activity) · Bot activity.

**Coordinator:** the teacher's plus Approvals (queue, "You wrote this one", preview with Teacher
only and Answer key panels, Approve, Send back with reason, Keep looking).

**Principal:** Dashboard · Data (Questions / Exams / Results, filter, course) · Reports
(subject, By teacher / By course / By student, cards, Closed sittings, print).

## Appendix B — Situations, each provoked once

Login: wrong password; lockout; server down; recovery in place; duplicate session · Codes:
malformed, unknown, not open, closed, not enrolled, already handed in · Identity: empty, someone
else's · Paper: repeated answer changes; server restart mid-attempt (banner, resume); autosave
retry indicator · Bank: duplicate answers, none correct, empty stem, delete referenced, delete
free, edit while locked, stale save · Builder: sum ≠ 100, duration 0 / 481, empty name, no
questions, infeasible auto-compose, edit an approved version · Approvals: reject without
reason, approve own, resubmit while pending · Releases: window rules, code rules, cancel
scheduled, close live · Monitor: extend 1 and 480 · Grading: score out of range, empty reason,
approve twice · Bot: empty, too long, too fast, inactive, not enrolled, locked, cross-course
notice, provider unreachable (unplug the internet → the friendly sentence) · Notifications: all
ten types seen live · Print: results, checked exam, reports · Theme: every mode and palette ·
Sessions: sign out with an editor open; close a window mid-attempt and re-enter.

## Appendix C — Interactions between roles (tick only when the other window changed by itself)

| # | One person does | The other role sees, live | Step |
|---|---|---|---|
| 1 | Teacher submits an exam | Coordinator: bell, dashboard count, queue row | 3.1 |
| 2 | Coordinator sends it back | Author: bell, reason on the card, chip | 3.4 |
| 3 | Author resubmits | Coordinator: only the new version, SUPERSEDED bell | 3.5 |
| 4 | Coordinator approves | Author: bell, chip, Releases offers it | 3.6–3.8 |
| 5 | Teacher schedules ≤ 30 min ahead | Students: RELEASE_OPENING_SOON | 3.10 |
| 6 | Teacher reads out a code | Student enters it; it shows on no student screen | 3.9, 3.11 |
| 7 | Student enters her ID | Monitor row Started; dashboard counts her | 3.12, 3.15 |
| 8 | Teacher adds time | Student's chip, floater, toast, bell | 3.17 |
| 9 | Student loses window focus | Monitor attention text; nothing on hers | 3.16 |
| 10 | Student uses another course's bot mid-exam | Teacher's INTEGRITY_ALERT; monitor flag | 7.6–7.7 |
| 11 | Student hands in | Monitor Handed in; counts | 3.14–3.15 |
| 12 | Time runs out | Student Time is up; monitor Timed out; GRADING_DUE | 3.18 |
| 13 | Teacher closes early | Students Time is up; row Closed; grading queue | 7.8 |
| 14 | Teacher approves grades | Student's bell, card with teacher and note; nothing before | 4.3, 4.7 |
| 15 | Teacher overrides with a reason | Student sees score, marker, comment; never the reason | 4.2, 4.7 |
| 16 | Teacher A opens a question editor | Teacher B: badge, read-only; clears on close/sign-out | 6.1–6.3 |
| 17 | Teacher A edits a bot source | Co-teacher: bell per change; lock banner while open | 6.5 |
| 18 | Teacher toggles the bot | Student: off sentence, then answers again | 6.6, 7.2 |
| 19 | Students use the bot | Teacher's activity screen rises; no names | 6.7, 7.3 |
| 20 | Dual-hat coordinator submits her own exam | Her queue badges it; approval allowed and noted | 5.2–5.3 |
| 21 | Teacher marks a sitting | Principal's Data and Reports read it; cannot change it | 9.2–9.3 |
| 22 | A user signs in twice | Second refused until the first leaves | 10.4 |
| 23 | A user signs out holding a lock | The lock clears for others | 6.3 |
| 24 | Another teacher runs an exam Dana wrote | Dana's Results lists that sitting | 4.5 |

## Appendix D — Requirement coverage (course PDF ids → step)

F1.1 1.4 · F1.2 2.2, 5.1, 8.2 · F1.3 10.4 · F1.4 6.3 · F1.5 1.1–1.3, 1.5–1.6 · F2.1 2.6–2.8 ·
F2.2 2.7 · F2.3 2.5, 2.9 · F2.4 2.3–2.5 · F2.5 2.10 · F2.6 6.1 · F3.1 2.14–2.18, 5.2 · F3.2 2.15 ·
F3.3 2.16 · F3.4 2.17 · F3.5 2.19 · F3.6 2.12, 3.6 · F4.1 3.2 · F4.2 3.3–3.5 · F4.3 5.3 ·
F5.1 3.8 · F5.2 3.9 · F5.3 3.9 · F5.4 3.7, 3.18 · F5.5 3.10, 7.8 · F6.1 3.11–3.12, 8.1 ·
F6.2 3.13, 3.18 · F6.3 3.13 (+ two-machine) · F6.4 3.18, 7.8 · F6.5 3.18 · F6.6 structural ·
F6.7 3.14, 3.19 · F6.8 7.6 · F6.9 3.14 · F6.10 3.14 · F7.1 3.17 · F7.1b 3.16 · F7.2 3.15 ·
F7.3 3.18, 7.8 · F8.1 4.1 · F8.2 4.3 · F8.3 4.2 · F8.4 4.7 · F8.5 4.4 · F9.1 4.7–4.8 · F9.2 4.4–4.5 ·
F9.3 9.1–9.2 · F9.4 9.3 · F10.0 6.1 · F10.1 6.2–6.3 · F10.2 6.1 · F10.3 structural · F10.4 6.5 ·
F11.1 3.1, 3.4, 3.5, 3.10, 3.17, 3.18, 7.7 · F11.2 4.11 · F11.3 3.17 · F12.1 6.4, 6.8 · F12.2 6.4 ·
F12.3 6.5 · F12.4 6.6, 7.2 · F12.5 7.3 · F12.6 (server.properties only) · F12.7 7.3 ·
F12.8 structural · F12.9 7.4 · F12.10 7.4 · F12.11 6.7 · F13.1 0.1–0.2 · F13.2 console ·
F13.3 0.4 · F13.4 1.1–1.3 · F14.1 0.1, 0.3 · F14.2 0.2 · S-5 2.11, 8.1 · S-6 6.8 · S-7 9.1 ·
S-8 2.7 · S-10 2.17 · S-11 2.15 · S-12 2.14 · S-13 2.8 · S-14 2.18 · S-15 8.1 · S-16 3.9 ·
S-17 3.9 · S-18 3.12 · S-19 3.18, 4.5 · S-20 3.17 · S-21 3.18 · S-22 4.2 · S-23 4.2, 4.7 ·
S-24 4.7 · S-25 4.4 · S-26 9.3 · S-27..S-29 6.4 · S-30 6.8 · S-31 7.2 · S-32 7.3 · S-33 7.4 ·
S-34 6.7 · S-35 4.5 · S-36 4.8 · S-37 9.3 · S-38 1.4 · S-44 10.5 · C-1 3.9 · C-2 2.9, 2.19, 3.4 ·
C-3 4.3 · C-4 7.6 · C-5 9.3 · C-7/C-8 2.6 · NFR-16 10.4 · NFR-17 0.2 · NFR-18 3.6, 4.6, 4.7 ·
NFR-21 2.1, 10.1, 10.5. Network ids (S-40, S-42, NFR-15, F13.x across machines, F6.3 by cable)
are in the two-machine file. F6.6, F10.3 and F12.8 are structural and covered by guard tests.
