# HSTS — defence walkthrough, mark by mark

**What this is.** The course test outline (*מתווה לבדיקת מערכת*) grades 21 scenarios. This file
walks all 21 **in the outline's order**, as one continuous story: what mark 2 creates, mark 3
builds, mark 4 approves, mark 5 releases, mark 6 sits, mark 8 grades and mark 12 reports. Every
step names the account, the exact clicks, the text to paste, what the panel must see, and one
sentence to say. Tick the box when you have rehearsed it and it behaved. **Situations with
two or more users acting at once** are collected in the appendix at the end, since the panel
asked for those specifically last time.

**How it relates to the other docs.** `DEMO_DAY.md` makes the machines ready (do it first).
`DEMO_SCRIPT.md` is the 25-minute narrated performance; this file is the complete, ordered
checklist behind it, so nothing the graders can ask for is missing. `DEMO_PREP.md` has the full
roster; the parts you need are repeated here so you never leave this page.

**Password for every account: `demo123`.**

---

## Before the panel walks in (from DEMO_DAY, ticked here)

- [ ] Built from the final commit: `.\mvnw -DskipTests clean package` on both machines.
- [ ] Machine **A** (server + staff client): `java -jar target\hsts-server.jar` from a
      Windows Terminal, console window open, font large. The terminal shows the Flyway
      lines, `Server started, listening on port 5555`, and **no red line**.
- [ ] On A's console, **Reload demo data** once this morning (the live sitting's window is
      relative to load time; a database seeded last night has no live exam today). Total
      reads 581 rows.
- [ ] Machine **B** (student / second teacher client): `java -jar target\hsts-client.jar`,
      launched once so it auto-connects next time. Close it.
- [ ] Both machines on the same network, firewall rule for 5555 TCP and the discovery UDP
      port on A (DEMO_DAY §4.2). Hotspot fallback rehearsed.
- [ ] Live bot keys in A's `server.properties` (never in git); the E16.17 checklist run today.
- [ ] This file printed, `DEMO_ACCOUNTS.md` printed, a pen.

**Machine roles for the whole demo.** A = server, its console, and every staff account
(teachers, coordinators, principal). B = students, and the second teacher for the lock moments.
One session per user, so every account change is **Sign out** first (profile menu, top right).

**Roster you will use (all `demo123`)**

| Role | Username | Extra |
|---|---|---|
| Teacher, Algebra 11 + Calculus 12 | `dana.cohen` | grading waiting on sitting `3318` |
| Java 21 co-teachers | `avi.mizrahi`, `tamar.shani` | grading waiting on `7390` |
| Coordinator, Mathematics, teaches nothing | `rina.barak` | exam `101201` waits for her |
| Coordinator, CS, also teaches Databases 22 | `michal.sharon` | self-approval mark |
| Teacher who also coordinates her subject | `orly.navon` (Chemistry 41) | course with no exam yet |
| Student, Algebra + Java + Databases + Biology | `maya.levi`, ID `374301851` | the demo student |
| Student, Algebra + Java | `omer.katz`, ID `361489206` | the second student |
| Student, NOT in Algebra | `noam.peretz`, ID `385612098` | refusal checks |
| Student, in Databases, Databases bot is off | `shira.dahan`, ID `352074611` | bot-off refusal |
| Principal | `principal.avia` | reads everything |

**Seeded things you will meet.** Exam code `2075` = live Algebra sitting (Midterm: Algebra v2).
`5164` = Databases Final, scheduled for later today. `4821`, `6120`, `7745` = closed and fully
graded (three subjects, three teachers). `3318` (Dana) and `7390` (Avi) = closed, awaiting
grading. Algebra questions `11003`, `11004`, `11006`, `11008` are in no exam (deletable); the rest
are in exams.

---

## Mark 1 — Login (כניסה למערכת) · T-1

**Covers:** F1.1, F1.2, F1.5, S-38. **Where:** B for the connect screen, then A.

- [ ] 1.1 **B:** launch the client. It finds A on its own and lands on **Login** showing
      "Connected to &lt;server name&gt; · change server". *Say: "The client discovered the
      server by broadcast and pinned its id; the address was never typed."*
- [ ] 1.2 **B:** click **change server**. Address `10.0.0.1`, port `5555`, **Connect** → one
      plain sentence ("Nothing is listening…"), no Java class name, no brackets. **Back to the
      server list** → pick the real server → Login.
- [ ] 1.3 **B:** username `maya.levi`, password `wrong`, **Sign in** → one generic sentence.
      Repeat until five failures. Now `demo123` → refused with the too-many-attempts
      sentence. *Say: "Same sentence whether the user exists or not; five failures lock the
      account for thirty seconds, even with the right password."* (Wait 30 s before 6.1.)
- [ ] 1.4 **A:** sign in as `dana.cohen`. The rail reads **Dashboard, Question Bank, Exams,
      Releases, Live Monitor, Grading, Results, Study Bot, Settings**, no Approvals. The
      dashboard greets her by name and shows **Sittings in progress** (2075), **Today and
      next** (5164), **Awaiting grading** (3318), **Class average**, **Your exams**.
- [ ] 1.5 **A:** Sign out. `rina.barak` → rail is **Dashboard, Question Bank, Approvals,
      Settings** only. *Say: "She coordinates Mathematics and teaches nothing, so the rail is
      derived from role plus course relations, not from role alone."* Sign out.
- [ ] 1.6 **A:** `michal.sharon` → the full teacher rail **plus Approvals**. Sign out.
      `principal.avia` → **Dashboard, Data, Reports, Settings**. Sign out.
- [ ] 1.7 **B:** `maya.levi` (lockout is over) → **Dashboard, Take Exam, My Grades, Study
      Bot, Settings**. Leave her signed in on B.

*If it goes wrong:* discovery finds nothing → **change server**, type A's address off the
console (DEMO_DAY §4.5). Login fails for everyone → the seed is not loaded (generic message by
design).

---

## Mark 2 — Question bank editing (עריכת מאגר שאלות) · T-2

**Covers:** F2.1–F2.6, S-5, S-8, S-9, C-8. **Where:** A as `dana.cohen`.

- [ ] 2.1 **Question Bank.** The Course picker offers **Algebra 11 and Calculus 12 only**.
      *Say: "Only courses she teaches; asking the server for a Java question by id is refused
      with the same sentence as a deleted one, so nothing can be probed."*
- [ ] 2.2 Pick Algebra 11. Topic `Quadratic functions` → difficulty `Easy` → search `roots`:
      the list narrows at each step, the count follows. **Clear filters**. Click a column
      header twice: sort flips. The **tune icon** in the toolbar hides and shows columns.
- [ ] 2.3 Click **11005**: stem, four answers with one marked **Correct**, topic, difficulty,
      version, illustration (loading state, then the picture). **Version history** → two
      versions; **Show this version** on v1 → its own stem and answers, read-only.
      *Say: "Editing never overwrites; every version is kept and shown."*
- [ ] 2.4 **Add question.** Paste, in order:
      - Stem: `Solve: 4x - 8 = 12`
      - Answers: `x = 5` · `x = 5` · `x = 3` · `x = 20` (two the same), mark the first correct
        → **Add question** → refused: answers must differ.
      - Change the second to `x = 4`. Answer 1 is already marked correct (the default for
        a new question); the control is a radio, so "none" and "two" cannot be
        expressed. *Say: "Exactly one, by construction; the server checks it again."* Topic `Linear equations`. Difficulty **Easy**. **Choose image** → any
        PNG → "Illustration attached".
      - **Add question** → the list shows it with a five-digit id starting `11` that nobody
        typed. Write it here: `11___`. *Say: "Two-digit course code plus a server serial;
        read-only."*
- [ ] 2.5 Add two more (the exam in mark 3 needs three new ones):
      - `What is the vertex of y = (x + 2)² - 3?` → `(-2, -3)` ✓ · `(2, -3)` · `(-2, 3)` ·
        `(2, 3)` · topic `Quadratic functions` · **Medium**
      - `For which x is (x - 3)/(x + 1) < 0?` → `-1 < x < 3` ✓ · `x < -1` · `x > 3` ·
        `x < 3` · topic `Inequalities` · **Hard**
- [ ] 2.6 Select your first new question → **Edit** → add `?` to the end of the stem →
      **Save as a new version** → the pane shows **Version 2**. **Edit** again, change one
      answer, **Save as a new version** → Version 3, no "someone else saved" message.
- [ ] 2.7 Select **11005** → **Delete** → dialog "This question is in use" **names the
      exams** (Midterm: Algebra) → **Close**. Select **11004** → **Delete** → "Delete this
      question?" → **Delete** → gone. *Say: "Blocked when any exam version references it;
      otherwise a soft delete that keeps the version rows."*
- [ ] 2.8 Leave the bank open for mark 13's lock moment later; nothing else to do here.

*If it goes wrong:* the image picker is slow on a network drive; have a PNG on the desktop.

---

## Mark 3 — Exam building (בניית מבחנים) · T-3

**Covers:** F3.1–F3.6, S-10, S-11, S-12, S-13. **Where:** A as `dana.cohen`.

- [ ] 3.1 **Exams.** Rows: Midterm: Algebra (v1 REJECTED, v2 APPROVED, the reason on v1's
      card), Quiz: Inequalities (DRAFT), Midterm: Calculus (PENDING). *Say: "Four states per
      version, with chips everywhere."*
- [ ] 3.2 **New exam** → **Algebra 11**. The builder header names the course. Paste:
      - Name `Demo Quick Check`
      - **How long students get** `2` (hint says 1 to 480)
      - Student instructions tab: `Two minutes. Answer what you can.`
      - Teacher notes tab: `Demo timing check.`
- [ ] 3.3 **Choose questions** tab → **Add from the bank** → tick your three new questions
      → **Done**. Points `40` / `30` / `20`: the sum reads **90** and **Create exam is
      disabled**. *Say: "Blocked, not warned; nothing is stored until the total is 100."*
      Change the last to `30` → enabled.
- [ ] 3.3b **Add from the bank** once more → tick **11009** (it already sits in Midterm:
      Algebra) → **Done** → accepted. Adjust points back to 100. *Say: "A question may
      belong to any number of exams; what an exam cannot do is hold the same question
      twice, even through two versions."*
- [ ] 3.4 **Move up** on the last row, **Move down** on the first, **Remove** one and add it
      back. Click **Show answers** on a row → the four options with the correct one marked
      → **Hide answers**. The edited question shows "The bank has a newer version" →
      **Use the newer version**.
- [ ] 3.5 **Compose automatically** tab → **Add a topic** `Inequalities`, **Hard**, count
      `20` → **Compose the exam** → a shortfall report ("requested 20, bank has …"),
      **nothing created**. *Say: "The bank cannot satisfy it, so no exam is written and the
      report says exactly what is missing."* Change to `Linear equations`, **Easy**, `2` →
      it composes. Go back to **Choose questions** and keep the manual paper.
- [ ] 3.6 **Create exam** → "Saved." → the Exams list shows `Demo Quick Check`, DRAFT, with a
      six-digit id starting `1011`. Write it: `1011__`. *Say: "Subject, course, serial;
      author recorded from the session, not from a field."*
- [ ] 3.7 Open it again: duration `0` → refused; `481` → refused; blank name → refused. Set
      `2` back. Click **Preview** → the paper as a student sees it, with the teacher-only
      panel, **the author's name (Dana Cohen)** and the answer key, **no Approve button**
      → **Back to the exam**.
- [ ] 3.8 **Submit for approval** → chip **PENDING_APPROVAL**.
- [ ] 3.9 Select Midterm: Algebra → **Edit** on v2 → the builder says it is making **v3** →
      **Save draft** → the list shows v2 still APPROVED and v3 DRAFT. *Say: "Editing an
      approved exam never touches it; the released sittings stay pinned to v2 and its
      question versions."*

---

## Mark 4 — Exam approval (אישור מבחן) · T-4

**Covers:** F4.1–F4.3, S-14. **Where:** A: Dana out, `rina.barak` in. (Or B if you want
both on screen: sign Maya out on B first and back in after 4.6.)

- [ ] 4.1 `rina.barak`: the bell badge is up: **APPROVAL_REQUESTED** for Demo Quick Check.
      Dashboard "Waiting for you" counts it.
- [ ] 4.2 **Approvals.** The queue lists Demo Quick Check and Midterm: Calculus, her subject
      only. Open Demo Quick Check: the paper **exactly as a student sees it**, the
      **Teacher only** panel with Dana's note and **the author's name**, the **Answer key**
      panel. *Say: "This was the
      v1 failure. The student's wire type has no field a correct answer could travel in; the
      key is a separate staff-only block."*
- [ ] 4.3 **Send back** with an empty reason → refused. Reason `Add one more question before
      approval.` → **Send back**.
- [ ] 4.4 Sign out, `dana.cohen` in: bell **APPROVAL_REJECTED**; Exams → the card shows the
      reason and chip **REJECTED**. **Edit** → the builder opens the next version → **Add
      from the bank** → tick one more Algebra question, set points so the total is 100 →
      **Save draft** → **Submit for approval**.
- [ ] 4.5 Sign out, `rina.barak` in: the queue shows Demo Quick Check **v2** only; bell
      **APPROVAL_SUPERSEDED** for v1. Open v2 → **Approve** → "Approve this exam?" →
      **Approve**. Also open Midterm: Calculus → **Approve**. Queue: "Nothing waiting".
- [ ] 4.6 Sign out, `dana.cohen` in: bell **APPROVAL_APPROVED**; the Exams chip reads
      **APPROVED**; v1 still shows REJECTED with its reason. *Say: "Per version; earlier
      versions keep their own status."*
- [ ] 4.7 **Self-approval (F4.3):** later, in mark 13's Michal step, or now: `michal.sharon`
      → Exams → New exam → Databases 22 → name `Self check`, `5` minutes, three bank
      questions at 40/30/30 → **Create exam** → **Submit for approval** → **Approvals** →
      her queue shows it with **"You wrote this one"** → open → **Approve** → allowed. The
      server terminal prints one **WARN** line naming the self-approval. *Say: "Permitted by
      the spec, and logged."*

*Two-machine variant (better):* Rina on B, Dana on A, and the panel watches the bell, the
chip and the queue change on the other machine without anyone touching it (that is mark 18).

---

## Mark 5 — Taking an exam out of the drawer (הוצאת מבחן מהמגרה) · T-5

**Covers:** F5.1–F5.5, S-2, S-14, S-15, S-17, C-1. **Where:** A as `dana.cohen`.

- [ ] 5.1 **Releases.** Rows: `5164` Scheduled, `2075` Live with participant counters, the
      closed ones. *Say: "Same exam, many sittings; each has its own window, code and
      statistics."*
- [ ] 5.2 **Release an exam.** The "Approved exam" picker offers **only approved versions**
      (Demo Quick Check v2, Midterm: Algebra v2, Midterm: Calculus v1); the draft and the
      rejected version are not listed. *Say: "Enforced twice: the picker never offers them,
      and the server refuses them anyway."*
- [ ] 5.3 Pick Demo Quick Check v2. Exam code `730` → refused (four characters); `73O!` →
      refused; **Generate for me** → four characters appear; overwrite with `7301`.
      *(The outline says "4 digits"; the spec says digits and letters. The system accepts
      both; the demo uses digits so nobody has to argue the point.)*
      Set Closes **before** Opens → a complaint. Set **Opens now**, **Closes now + 8
      minutes** → **Release it** → "Read this code out" → **Copy code** → **Done**. The row
      is **Live**. *Say: "Four characters, digits or letters, read out by the teacher; the code is
      never shown to a student anywhere in the app."*
- [ ] 5.4 **Release an exam** again: Midterm: Algebra v2, code `7302`, Opens now + 20
      minutes, Closes + 40 → **Scheduled**. On its row **Cancel release** → "Cancel this
      release?" → **Cancel it** → gone. A Live row offers **Close early**, never Cancel.
      (Close early is shown in mark 14.)
- [ ] 5.5 Check the Create release dialog again: the clock rows are readable, nothing
      squashed.

---

## Mark 6 — Exam execution (ביצוע מבחן) · T-6

**Covers:** F6.1–F6.10, S-15, S-18, S-19, C-4. **Where:** B as `maya.levi`; a second student
client on A (or a third window on B) as `omer.katz`. Start within the 8-minute window of `7301`.

- [ ] 6.1 **B, `maya.levi`:** Dashboard → **Take an exam** card → type `7301` → **Enter** →
      "Confirm your exam" with the code read-only → **Confirm and continue**.
- [ ] 6.2 "Confirm it is you": summary reads Demo Quick Check, 2 minutes, N questions.
      ID `385612098` (Noam's) → refused on the field, **no clock started**. ID `374301851`
      → the paper. *Say: "The id is checked against the signed-in student's own record; the
      timer starts here, at id entry, on the server."*
- [ ] 6.3 The countdown runs. Answer question 1 → **Saving** → **All changes saved**; its
      chip changes. **Next** / **Previous**. **Do not hand in.**
- [ ] 6.4 **Second student, `omer.katz`, ID `361489206`:** Take Exam → `7301` → confirm →
      ID → paper. Answer everything. **Hand in** → the dialog shows the answer grid, the
      remaining time, the note that unanswered questions score 0 → **Keep working** once →
      **Hand in** → **Hand in** → the **Handed in** screen: time, minutes, summary, one
      button **Back to my dashboard**. Enter `7301` again → "already handed in".
- [ ] 6.5 **Refusals (any student window, quick):** as `noam.peretz`: `2075` → "not enrolled";
      `5164` → "not open yet"; `ZZZZ` → "unknown code"; `12` → "Codes are 4 letters or
      digits." *Say: "Four different sentences for four different mistakes."*
- [ ] 6.6 **Wire honesty (say, do not click):** *"The take-exam DTO has ten fields and none
      of them is a correct answer. It is absent from the data, not hidden by the UI."*
- [ ] 6.7 *(Outline note: "the bot is unavailable during the exam")* → shown live in
      mark 14.5 on the seeded sitting `2075`. *(Outline note: "time is measured and the
      exam closes when it runs out")* → mark 7.4.
- [ ] 6.8 Continue directly to mark 7 while Maya's clock is still running.

*If it goes wrong:* the window closed before Maya joined → release again with code `7303`,
Opens now, Closes + 10.

---

## Mark 7 — Extending exam duration (הארכת משך הבחינה) · T-7

**Covers:** F7.1–F7.3, S-20, S-21. **Where:** A as `dana.cohen`, Maya still sitting on B.

- [ ] 7.1 **A: Live Monitor** → Demo Quick Check: Maya **Started** with a countdown, Omer
      **Handed in**; counts **Started 2 / Handed in 1 / Timed out 0**. *Say: "Counts are
      derived from the attempts on every push, never incremented, so there is no race."*
- [ ] 7.2 On B click another window for two seconds, then back: Maya's monitor row reads
      "Her exam window last lost focus at …"; **her own screen shows nothing**. *Say: "A
      signal for the teacher, not a verdict; detection runs on her machine, so it is a
      deterrent and a visibility aid."*
- [ ] 7.3 Spinner **1** → **Add time**. On B, **at once**: the chip flashes, "+1:00" floats
      up, a toast names Dana Cohen and the new end; her bell has **TIME_EXTENDED**.
      *Say: "Pushed to every active student the moment it is granted; the stored exam's
      duration is untouched, the minutes live on this sitting only."*
- [ ] 7.4 Wait. The chip turns amber, then red, then at zero Maya's screen is taken over by
      **Time is up**: no confirmation, the summary, one button. The monitor row reads
      **Timed out**; counts **2 / 1 / 1**. Dana's bell: **GRADING_DUE**. Within a few
      minutes the Releases row is **Closed** and the counts are frozen. *Say: "The server
      force-submitted what was saved at the bell; the client only found out."*
      The sitting's record (Releases row and the Results header): **date and time, the
      duration actually allotted (2 + 1 minutes), started 2, finished on their own 1, did
      not make it 1**, exactly the five things §4 of the spec asks to be recorded.
- [ ] 7.5 Maya: **Back to my dashboard**. Enter `7301` → "already handed in".
- [ ] 7.6 *(Optional, S-20 proof)* Exams → Midterm: Algebra v2 still says its original
      duration; a new release of the same version would start from it.

---

## Mark 8 — Exam checking (בדיקת מבחנים) · T-8

**Covers:** F8.1–F8.5, S-22–S-26, C-3. **Where:** A as `dana.cohen`; Maya watching on B.
**Do this before mark 12** (the reports compare closed and graded sittings).

- [ ] 8.1 **Grading.** "Waiting for you" lists Demo Quick Check and the seeded `3318`
      (Midterm: Algebra, four students). Open Demo Quick Check: two rows, each with its
      **Auto** score already computed. *Say: "Computed on submission against the pinned
      question version; nothing published yet."*
- [ ] 8.2 **B, Maya:** My Grades shows **no** Demo Quick Check row. *Say: "Auto-checking alone
      publishes nothing (C-3)."*
- [ ] 8.3 **A:** click Maya's row → **Review** → her marked paper with the key beside it,
      Approve and Change score on it. **Change score…**: score `101` → refused; empty reason
      → refused. Score `40`, reason `Method credit for the working shown`, comment
      `Well tried under time pressure` → save → the row shows the adjusted marker; the Auto
      column keeps the original. *Say: "Original, change and reason are all stored; the
      reason never reaches the student."*
- [ ] 8.4 Approve Maya's row on its own. Then **Select all** → **Approve selected** → the
      confirmation names the count → the sitting leaves the queue.
- [ ] 8.5 Open `3318` → four rows (Noa 85, Shira 75, Daniel 60, Itay 45) → **Select all** →
      **Approve selected**. *(This gives the reports a fourth frozen sitting.)*
- [ ] 8.6 **B, Maya, untouched:** the bell badge went up on its own: **GRADE_PUBLISHED**.
      **My Grades** now shows the Demo Quick Check card: **Teacher: Dana Cohen**, score 40,
      **Reviewed by your teacher**, the note "Well tried under time pressure". The reason
      "Method credit…" appears **nowhere**.
- [ ] 8.7 **B:** look for a class average, median or histogram anywhere on Maya's screens:
      none. *Say: "The student wire types have no field a class statistic could travel in."*

---

## Mark 9 — Viewing an exam grade (צפיה בציון הבחינה) · T-9

**Covers:** F9.1, S-36. **Where:** B as `maya.levi`.

- [ ] 9.1 **My Grades**: the seeded Midterm: Algebra card (60, Teacher: Dana Cohen, her note)
      and the new Demo Quick Check card. The term-average ring's arc sits on its track.
- [ ] 9.2 Demo Quick Check → **Open paper →**: header with exam, course, score; attempt line
      **Timed out** with the minutes; "Your teacher's note"; every question with your
      answer, the correct answer, Correct · Wrong · Not answered, and points.
- [ ] 9.3 **Print layout** → the chrome disappears, one column → **Exit print view**.
      *Say: "Her copy of the checked exam."*
- [ ] 9.4 *(Say, do not click)* *"Replaying another student's grade id is refused server-side;
      the gate is ownership by query, not a missing link."*
- [ ] 9.5 On the second student's window (`omer.katz`): his card and paper arrived by push
      too, and his **My Grades lists only his own** sittings; Maya's 40 is nowhere on his
      screens. *(Outline note: "a student cannot see other students' grades".)*

---

## Mark 10 — Viewing exam results (צפיה בתוצאות בחינות) · T-10

**Covers:** F9.2, S-35. **Where:** A as `dana.cohen`.

- [ ] 10.1 **Results.** Every exam **she wrote**, including sittings run by others; Midterm:
      Algebra lists `4821`, `2075`, `3318`. Never Avi's Java exam.
- [ ] 10.2 Demo Quick Check → its sitting: the table has score, status (Handed in / Timed
      out), minutes, Adjusted marker on Maya.
- [ ] 10.3 Toggle to the **histogram**: bars in the accent colour, **mean, median and ±1σ**
      markers labelled; stat cards **average · median · σ · min/max · pass rate (≥ 55) ·
      participants**. Hover a bar → "range · n students · %". Toggle **count ↔ %** with no
      reload. *Say: "Statistics are computed once when grading completes and stored; the
      report engine reads the same record."*
- [ ] 10.4 Midterm: Algebra → `4821`: **mean 72.5, median 72.5, σ 17.5, pass 7/8**;
      `omer.katz` Timed out, 75 minutes.
- [ ] 10.5 **Print layout** → **Exit print view**.
- [ ] 10.6 *(Empty state)* `michal.sharon` → Results → `5164` (scheduled, nobody sat it): a
      proper empty state, not a blank panel.

---

## Mark 11 — Viewing data, principal (צפיה בנתונים) · T-11

**Covers:** F9.3, S-7. **Where:** A as `principal.avia`.

- [ ] 11.1 Rail: **Dashboard, Data, Reports, Settings**. Nothing on any screen adds, edits,
      deletes or approves. *Say: "The role is admitted to eight read verbs and nothing that
      writes; a replayed update is refused by the role gate before validation."*
- [ ] 11.2 **Data** → **Questions**: school-wide, every course including Biology, Chemistry
      and Physics; type `Solve` in the filter, pick a course, clear. Click a row → the
      read-only detail with version history (and **Show this version**).
- [ ] 11.3 **Exams** segment → every exam by every author, no approval judgement shown →
      click a row → the paper, read-only. **Results** segment → the closed sittings → click
      one → the frozen statistics.

---

## Mark 12 — Viewing reports (צפיה בדו"חות) · T-12

**Covers:** F9.4, S-25, S-37. **Where:** A as `principal.avia`. **After mark 8.**

- [ ] 12.1 **Reports** → **By teacher** → Dana Cohen: `4821`, `3318` and Demo Quick Check
      side by side: average, median, decile distribution per sitting, pooled summary above.
      *Say: "3318 and the demo sitting appeared during this demo; the frozen 4821 was there
      all along, and the pooled mean differs from the mean of the means."*
- [ ] 12.2 **By course** → Algebra 11: the same three; Java 21 → `6120` (and `7390` once Avi
      approves it in mark 13); Biology 31 → `7745`.
- [ ] 12.3 **By student** → `noa.friedman`: three sittings in three subjects, class figures
      only, **her own score nowhere**. *Say: "A student's personal score has no field in a
      report row."*
- [ ] 12.4 Cross-check: `4821` reads **72.5 / 72.5 / 17.5**, identical to the teacher's
      histogram. **Print layout** → exit.
- [ ] 12.5 *(Q&A, mark 19)* *"A new report dimension is one Strategy class and a menu entry;
      `ReportEngine` names none of the three that exist."*

---

## Mark 13 — Creating a study bot (יצירת בוט לימודי) · T-13

**Covers:** F12.1–F12.3, S-6, S-28, S-29, S-30, F10.x locks. **Where:** A as `avi.mizrahi`,
B as `tamar.shani` (sign Maya out on B first).

- [ ] 13.1 **A, Avi: Study Bot.** One card per course he teaches; **Java 21** → **Manage**:
      name, the toggle **Students can use this bot**, Information sources. *Say: "One bot per
      course; a co-teacher extends the same bot. The course's own question bank is always
      part of what the bot may answer from; files and free text are added here."*
- [ ] 13.2 **Add text** → paste:
      `A LEFT JOIN returns every row of the left table and the matching rows of the right table; where there is no match the right side is NULL.`
      → listed with its character count and author. **Add a file** → a small PDF → "Reading
      that file on the server" → listed. A Word file → listed. A text file renamed to `.pdf`
      → a sentence says it could not be read, **no row left behind**.
- [ ] 13.3 **B, Tamar: Study Bot → Java 21**: she sees **the same bot** and Avi's sources,
      with **Manage**, not "Create". Her bell: **BOT_SOURCE_CHANGED** for each of Avi's
      changes.
- [ ] 13.4 **Lock, live:** A: **Edit** on the text source, leave it open. B: Edit the same
      source → **"Being edited by Avi Mizrahi"** banner, read-only. A: save → B's banner
      clears by itself. *Say: "Advisory lock with a heartbeat, released on close, sign-out or
      socket drop; the server refuses writes behind it, so it is more than a banner."*
- [ ] 13.5 **Same lock on the bank:** A: Question Bank → Java 21 → **21003** → **Edit**. B:
      the 21003 row shows **Editing … Avi Mizrahi**; opening it is read-only. A: **Sign
      out** with the editor open → B's badge clears.
- [ ] 13.6 A: sign Avi back in. **Remove** one source → "Remove this source?" → **Remove
      it**. **Delete the study bot** → refused while conversations exist (the sentence says
      so). Study Bot → a course card with **No study bot yet** (for Dana: Calculus 12) →
      **Create the study bot** → name `Calculus Helper` → created → **Add text** → paste
      `The derivative of a function at a point is the slope of the tangent line there.`
      → listed. *(Outline 13.1: create a bot for a course and define its sources.)*
- [ ] 13.7 **Grade Avi's sitting now (feeds mark 12 and mark 18):** Grading → `7390` →
      **Select all** → **Approve selected**.
- [ ] 13.8 Switch **Students can use this bot** off on Java 21 (mark 14 uses it).

---

## Mark 14 — Using the bot (שימוש בבוט) · T-14  ⚑ live keys

**Covers:** F12.4–F12.11, S-31–S-34, C-4, F6.8. **Where:** B as `maya.levi`; A as
`dana.cohen` for the alert, `avi.mizrahi` for activity.

- [ ] 14.1 **B, Maya: Study Bot** → Course picker offers Algebra 11, Java 21, Databases 22,
      Biology 31. Pick **Java 21** → ask anything → the bot-is-off sentence. **A, Avi:** switch it on.
      **B:** ask again on the same screen → answered. *Say: "Enrolled and active and not
      locked out; three different refusals."*
- [ ] 14.2 **B:** stay on **Java 21** (the Databases bot is seeded switched off; that is 14.4's
      refusal) → `What does a LEFT JOIN return when there is no match?` (the source Avi pasted
      in 13.2)
      → "The bot is thinking", typing indicator, then a grounded answer citing the source.
      `Who won the 2022 World Cup?` → `The bot could not answer that. Try rephrasing, or ask your teacher.`
      Three questions within seconds → the "too fast" sentence. *Say: "Course sources and
      the course question bank only; no exam data exists in its context by construction."*
- [ ] 14.3 **Past conversations** → listed with times → **Reopen** one → one more question →
      it continues on the same conversation. **New conversation**.
- [ ] 14.4 `shira.dahan` (any window): Databases bot is **off** in the seed → "switched off
      right now" sentence. `noam.peretz` → Databases bot → "not enrolled" sentence.
- [ ] 14.5 **C-4, live. B, Maya:** Take Exam → `2075` → confirm → ID `374301851` → the seeded
      Algebra paper; answer one; leave it open. **Study Bot → Algebra 11** → ask → the locked
      sentence naming Midterm: Algebra, box still usable. **Java 21** → ask → "You are
      taking an exam" notice → **Continue and notify** → answered; no second notice.
- [ ] 14.6 **A, `dana.cohen`:** bell **INTEGRITY_ALERT** naming Maya; **Live Monitor** →
      `2075` → her row: "Opened another course's study bot at …". *Say: "The same course's
      bot is locked; another course's bot is allowed but surfaced to the teacher, instead
      of silently permitted."*
- [ ] 14.7 **A, Dana:** Releases → `2075` → **Close early** → "Close it now" → **B:** Maya's
      screen goes to **Time is up**. Releases row **Closed**; Grading gets `2075`.
- [ ] 14.8 **B:** Study Bot → Algebra 11 → ask → answered (the lock ended with the exam).
- [ ] 14.9 **A, `avi.mizrahi`:** Study Bot → Java 21 → **Bot activity**: Questions asked,
      Busiest day, Asked most often, the 30-day chart. **No student name anywhere.** *Say:
      "The analytics DTO has no identity field; asserted reflectively in the tests."*

*If the model is unreachable:* the S-32 sentence appears instead of an answer. Say so: *"Both
providers are down; this is the designed fallback."*

---

## Mark 15 — Client-server on separate machines, JARs, connect GUI · T-15

**Covers:** F13.1–F13.4, F14.1–F14.2, S-39–S-42. Mostly already shown in mark 1; tick the
explicit checks.

- [ ] 15.1 **A:** the server was started from a terminal (coloured structured log) **and** its
      console window is open: port, DB status, connected clients (user, role, IP, uptime),
      live log tail, health cards. The LAN address and the server id are printed large.
- [ ] 15.2 **B:** the client was launched by **double-click** on the jar (say it); `java -jar`
      works the same from a terminal. `client.properties` sits beside the jar.
- [ ] 15.3 **B:** connect screen → **Look for servers again** → the picker lists A with name,
      address and id. **change server** → manual entry is one click away. *Say: "Discovery
      failing never blocks connecting; trust on first use pins the id, and a changed id
      raises a warning that needs an explicit confirm."*
- [ ] 15.4 **Console toggle:** on A's console switch discovery off → B's **Look for servers
      again** finds nothing quickly and offers manual entry → switch it back on.
- [ ] 15.5 **Reconnect after a drop (new):** with Maya signed in on B, pull B's network cable
      (or Wi-Fi off) for ten seconds → the banner reads Disconnected → network back →
      **Retry** → Login with her username pre-filled and "Reconnected. Sign in again." →
      sign in → works. Then restart the **server** on A while Dana is signed in: her banner
      → Retry → Login → sign in on the same window works.

---

## Mark 16 — Concurrent users; no double login · T-16

**Covers:** F1.3, F1.4, S-40.

- [ ] 16.1 `dana.cohen` signed in on A. On B sign in as `dana.cohen` → refused:
      **"This account is already signed in elsewhere."**, nothing more. *Say: "No host, no
      time, no detail."*
- [ ] 16.2 Sign out on A → retry on B → succeeds. Sign out on B, back in on A.
- [ ] 16.3 **Kill** the client process on A (close the window with the X) → retry on B at
      once → succeeds. *Say: "The socket drop frees the session; there is no timeout to
      wait for, and any edit locks she held are released with it."*
- [ ] 16.4 Four accounts at once across the two machines (`dana.cohen`, `rina.barak`,
      `maya.levi`, `principal.avia`), each on its own screen; the console lists four
      connected clients.

---

## Mark 17 — Test data prepared in the database · T-17

**Covers:** NFR-17, PRD §5, `docs/seed/SEED_CONTENT.md`.

- [ ] 17.1 **A's console:** point at **Load demo data if missing** and **Reload demo data**.
      Press **Load demo data if missing** → "Seed already present, nothing inserted" (581
      rows, unchanged). *Say: "Flyway creates the schema on first start; the seed is a
      versioned Java loader over the entities, idempotent per row, with the exam windows
      relative to load time so the live sitting is live today."*
- [ ] 17.2 Say what is in it: **5 subjects, 7 courses, 21 users** (1 principal, 8 teachers of whom
      five also coordinate, 12 students in 3 to 5 courses each), **about 60 questions** with illustrations and
      versions, **one deliberately thin topic** (Recursion in Java: 2 questions, no Hard) for
      the auto-compose refusal, **exams in every state**, **sittings live / scheduled /
      closed / graded**, bot sources and conversations, notifications, teacher comments.
- [ ] 17.3 Every screen the panel has seen so far was populated; nothing looked fake.
- [ ] 17.4 *Say:* "Users, roles, permissions, subjects and courses come from external
      systems (spec §3.1, §8), so they are seeded and read-only here: there is no user or
      course editor anywhere, by design."

---

## Mark 18 — Efficient computing, no user-initiated refresh · T-18

**Covers:** NFR-18, F11.1–F11.3, S-44. Evidence is what the panel already watched:

- [ ] 18.1 *Say:* "There is no Refresh or Reload control anywhere; a census of every string
      literal in the client finds none."
- [ ] 18.2 Point back at what changed **without anyone touching the other machine**: the
      Approvals queue and bell (mark 4), the timer extension (mark 7), the grade card and
      bell (mark 8), the lock badge clearing (mark 13), the integrity alert (mark 14), the
      Releases chips and monitor counts (marks 5–7).
- [ ] 18.3 Bank lists page server-side and images load per question, lazily; the list row
      carries a flag and never bytes.
- [ ] 18.4 Bell panel: click a row → it navigates; **Mark all read** → badge gone.

---

## Mark 19 — Flexible, change-tolerant design · T-19

**Covers:** NFR-19, S-37, S-43, S-45. Code open in the IDE on A; two sentences each.

- [ ] 19.1 **New report type:** open `server/features/reports/ReportStrategies.java` and
      `ReportEngine.java`. *"One new Strategy class plus a menu entry; the engine names none
      of the existing three. A fourth strategy that exists only inside a test drives the real
      engine end to end."*
- [ ] 19.2 **Swap the protocol for REST:** open `client/net/IClientConnection.java` (seven
      methods). *"Two implementations exist today: the OCSF adapter and the fake the screens
      develop against; the UI never sees a socket."*
- [ ] 19.3 **Swap the bot provider:** open `server/features/bot/ProviderChain.java`.
      *"A `BotProvider` interface, two adapters, a chain with no vendor type in it; keys stay
      server-side in a file that is not in git."*
- [ ] 19.4 **Phase 2, internet access (spec §10):** *"The client talks to one interface
      and the server authorises every verb by session, not by network; moving from LAN to
      internet is a transport and deployment change (TLS, a public address), not a
      redesign. The server id becomes a certificate fingerprint (ADR-019)."*

---

## Mark 20 — Reuse; use of design patterns · T-20

**Covers:** NFR-20, `PLAN.md` §2, `DECISIONS.md`.

- [ ] 20.1 Name the class for each: Adapter `IClientConnection` / `HSTSClient` · Singleton
      `ScreenManager`, `HibernateUtil` · Template Method `AbstractScreen` · Strategy
      `QuestionValidator`, `ReportStrategies`, `BotProvider` · Observer `ClientEventBus` +
      server push · State `EditLockState`, exam / execution / grade lifecycles · Command
      the protocol verbs and `MessageRouter` · DAO/Repository `server.db.repos.*` · DTO
      `common.dto.*`.
- [ ] 20.2 Patterns are named in the Javadoc of the boundary classes; open one.
- [ ] 20.3 **Reuse:** `client/ui/components` (23 components, imported by all 16 feature
      packages); **one `StatChart`** in teacher Results and in principal Reports; one
      `DataTable`, one `ModalHost`, one toast stack in the shell.

---

## Mark 21 — UI quality and friendliness · T-21

**Covers:** NFR-21, PRD §4, S-44.

- [ ] 21.1 Lists everywhere with an explaining **empty state** (Approvals "Nothing waiting",
      Grading "Nothing to grade", a scheduled sitting's results).
- [ ] 21.2 Progress: the skeleton on a list, the ring on the dashboard, "The bot is
      thinking", "Reading that file on the server".
- [ ] 21.3 Failures: every refusal today was a sentence with a next step, never a code or a
      class name; the disconnect banner and Retry (mark 15.5).
- [ ] 21.4 Success: "Saved." toasts, "Decision saved", the **Handed in** screen.
- [ ] 21.5 **Settings** → Light, Dark, System; each accent palette (Indigo, Emerald, Amber,
      Rose, Slate) → every open window follows at once → **Reset to defaults**.
- [ ] 21.6 Resize a window small and large: nothing overlaps, no text is cut off; the rail
      collapses to icons. Show a Hebrew question and an English one in the bank side by side.

---

## Appendix — two or more at a time

The panel asked last time for situations with **two users acting at once**. Every one below is
runnable on the two machines (A staff, B students; open a second client window on either
machine when a situation needs three). Each says who, where, what to do, and what must happen
**on the other person's screen without anyone touching it**. The ones marked ★ are already
inside the walkthrough at the step named; run the rest when asked, or as a block after mark 21.

**Two students**

| # | Situation | Who / where | Do | Must happen |
|---|---|---|---|---|
| ★ S1 | Same exam, one hands in, one does not | `maya.levi` on B, `omer.katz` on A (second window) | Both join `7301`. Omer answers all and **Hands in**; Maya keeps working until the clock ends (mark 6–7) | Monitor: Started 2 / Handed in 1 / Timed out 0, then 2 / 1 / 1. Omer's Handed in screen; Maya's **Time is up** takeover. Each later gets her own grade card only |
| ★ S2 | Extension reaches both at once | same two, both mid-exam | Dana: **Add time 1** (mark 7.3) | Both chips flash and both bells get TIME_EXTENDED in the same second; Omer, if already handed in, gets nothing |
| S3 | Answers are isolated | same two, both mid-exam | Maya picks option 1 on question 1; Omer picks option 3 on question 1 | Each sees only her own chips; kill Maya's client, sign in again, re-enter the code: her three answers, not his |
| S4 | One sits, one studies | Maya mid-exam on `2075`; `omer.katz` on the Algebra study bot | Omer asks the Algebra bot while Maya is sitting the Algebra exam | Omer is answered; only the sitting student is locked (the lock is per student, not per course) |
| S5 | Two sittings of one exam | `7301` (demo) and `2075` (seeded) both live | Maya on `2075`, Omer on `7301` | Two rows in Releases, two separate monitors, separate counts and codes; S-2 |
| S6 | Same student twice | `maya.levi` on B, then `maya.levi` on A | Second sign-in | Refused "This account is already signed in elsewhere."; her attempt on B is untouched |
| S7 | Two students, one bot, same second | Maya and Omer both on the Databases bot | Both send a question at the same time | Two separate conversations, two answers, two histories; the teacher's Bot activity counts 2 and names nobody |
| S8 | Re-entering after the other handed in | Omer handed in, Maya still sitting | Maya enters `7301` again from the dashboard | She resumes her own attempt with her own clock; Omer's hand-in changed nothing for her |

**Two teachers**

| # | Situation | Who / where | Do | Must happen |
|---|---|---|---|---|
| ★ T1 | Same question, same time | `avi.mizrahi` on A, `tamar.shani` on B | Avi: Question Bank → Java 21 → 21003 → **Edit**. Tamar: same row (mark 13.5) | Tamar's row shows **Editing … Avi Mizrahi**; her editor is read-only with the banner. Avi **Cancel** → her badge clears by itself; Avi signs out with it open → clears; Avi's window killed → clears within seconds |
| T2 | Take over and the stale save | same two | Tamar uses **take over** on the banner, changes the stem, **Save as a new version**. Avi, still on his old editor, tries to save | Avi gets the conflict dialog "This was changed by someone else while you were editing. Reload the latest version?" → **Reload** shows Tamar's version; nothing of his overwrote hers |
| ★ T3 | Same bot source | same two | Avi **Edit** on a text source, Tamar opens the same source (mark 13.4) | Banner for Tamar; Avi saves → Tamar's bell **BOT_SOURCE_CHANGED**, her list updates, her banner clears |
| T4 | Co-teachers, one bot | same two | Tamar opens Study Bot → Java 21 | She gets **Manage** on Avi's bot, never Create; adds a source; Avi's bell rings, his list grows without a click |
| T5 | Two teachers, one sitting to grade | same two on `7390` | Avi approves one row; Tamar has the same Grading screen open | Tamar's row shows approved when she next acts on it; approving it again publishes nothing a second time: the confirmation counts it as already approved, and the student gets no second bell (idempotent) |
| T6 | Same exam released twice by two teachers | Avi and Tamar, Java Fundamentals Exam v1 | Each does **Release an exam** with a different code | Two Live rows, two codes, separate participants and statistics; the exam in the drawer is untouched |
| T7 | Scope is per teacher | `dana.cohen` on A, `avi.mizrahi` on B | Both open Question Bank at once | Dana sees Algebra/Calculus, Avi sees Java; neither picker offers the other's course; typing the other's question id in the URL-less client is impossible, and the server refuses it anyway |
| T8 | Same teacher twice | `dana.cohen` on A, `dana.cohen` on B | Second sign-in; then close A's window without signing out; retry on B | Refused, then succeeds within a second of the drop (mark 16); locks she held are released with the session |

**Teacher and coordinator**

| # | Situation | Who / where | Do | Must happen |
|---|---|---|---|---|
| ★ C1 | Submit while the queue is open | `rina.barak` on B with Approvals open, `dana.cohen` on A | Dana **Submit for approval** (mark 4) | Rina's queue gains the row and her bell rings, no click |
| ★ C2 | Decision lands on the author | same | Rina **Send back** / **Approve** | Dana's card shows the reason / the chip flips to APPROVED, no click |
| C3 | Resubmit while the coordinator has v1 open | same | Rina opens v1's paper; Dana edits and resubmits (v2) | Rina's bell **APPROVAL_SUPERSEDED**; her queue shows v2 only; approving the stale v1 is refused |
| C4 | Dual hat, both sides at once | `michal.sharon` on A, `dana.cohen` on B | Michal submits her own Databases exam and, on the same screen set, approves it | Allowed, badge "You wrote this one", one WARN line on the server terminal; Dana's Mathematics exams never appear in Michal's queue |

**Teacher and student**

| # | Situation | Who / where | Do | Must happen |
|---|---|---|---|---|
| ★ P1 | Extend mid-exam | Dana on A, Maya on B | mark 7.3 | Chip, "+1:00", toast, bell on B at once |
| ★ P2 | Close early mid-exam | Dana on A, Maya on B sitting `2075` | mark 14.7 | Maya's screen goes to Time is up; monitor freezes |
| ★ P3 | Publish while My Grades is open | Dana on A, Maya on B on My Grades | mark 8.4 | Maya's card appears and her bell rings, no click |
| ★ P4 | Cross-course bot mid-exam | Maya on B sitting Algebra, Dana on A | mark 14.5–14.6 | Dana's bell INTEGRITY_ALERT and Maya's monitor row flagged the moment Maya clicks Continue |
| P5 | Focus loss mid-exam | same | Maya clicks another window (mark 7.2) | Dana's row shows the attention text; Maya sees nothing |
| P6 | Teacher toggles the bot while a student is on it | Avi on A, Maya on B on the Java bot | Avi switches **Students can use this bot** off, then on | Maya's next question gets the switched-off sentence, then is answered again, on the same screen |
| P7 | Grades are private across students | Dana approves; Maya and Omer both on My Grades | mark 8.4 | Each sees only her own card; replaying the other's grade id is refused server-side |

**Whole room**

| # | Situation | Who / where | Do | Must happen |
|---|---|---|---|---|
| ★ R1 | Four roles at once | `dana.cohen`, `rina.barak` on A; `maya.levi`, `principal.avia` on B | mark 16.4 | A's console lists four connected clients with user, role, IP and uptime; each screen works independently |
| R2 | Server restart under everyone | all of the above signed in | Ctrl+C the server, start it again | Every client shows the banner; **Retry** lands each on Login with the username filled; sign in works in the same window; a student's attempt resumes with the correct remaining time |

## The order, on one line

1 login → 2 bank → 3 build → 4 approve → 5 release `7301` → 6 sit (Maya + Omer) → 7 extend,
time up → 8 grade (+ `3318`) → 9 Maya's card and paper → 10 results → 11 principal data →
12 reports → 13 bots and locks (+ approve `7390`) → 14 use the bot, C-4, close `2075` early →
15 machines and reconnect → 16 double login → 17 seed → 18 no refresh → 19–20 code → 21 UI.

**Fixed points:** grade (8) before reports (12). Mark 14's C-4 needs the seeded live sitting
`2075`, so do not close it early before mark 14. The 8-minute window of `7301` starts at 5.3;
marks 6 and 7 must run inside it.

## If the demo has to be short

Keep: 1.1, 1.4–1.5, 2.4, 3.2–3.3, 3.5, 4.2–4.6, 5.2–5.3, 6.1–6.4, 7.3–7.4, 8.3–8.6, 10.3,
11.1, 12.1, 13.4, 14.2, 14.5–14.6, 16.1. Everything else answers a question when asked.
