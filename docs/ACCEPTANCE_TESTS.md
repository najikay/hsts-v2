# HSTS — Acceptance test table

**Owner:** Member B · **Reviewer:** Naji · **Feeds:** E22.1, submission document (Assignment 3 §1)

One section per scenario in the course test outline (*מתווה לבדיקת מערכת*), scenarios **1–21**.
Scenarios 1–14 are functional; 15–21 are the non-functional requirements. `T-n` is the scenario
number in the original table; `F-n.n` are the PRD features it tests; `S-n` are spec requirements
that refine it.

**Status legend:** ⬜ not run · ✅ pass · ❌ fail · ⚠ partial · ⛔ blocked (feature not built yet)

**Actual**, **Status** and **Bugs found** stay empty until the feature exists and is tested.
Everything else is filled now, from the PRD.

## How to run these

Every case is written against the **seed dataset** (`docs/seed/SEED_CONTENT.md`), so no case
says "some teacher" or "a student" — it names the actual account and the actual row. Load the
seed, sign in as the named user, follow the steps verbatim.

Accounts used most (password `demo123` for all):

| Account | Role | Why it appears |
|---|---|---|
| `dana.cohen` | Teacher | Authors the Mathematics exams; teaches Algebra 11 + Calculus 12 |
| `rina.barak` | Teacher + coordinator of subject 10 | Approves/rejects `dana.cohen`'s exams |
| `avi.mizrahi` | Teacher | Authors the Java exams; teaches Java 21 |
| `michal.sharon` | Teacher + coordinator of subject 20 | Approves the Java exams; teaches Databases 22 |
| `maya.levi` | Student | Enrolled 11, 21, 22 — the "sat the graded exam" student |
| `noam.peretz` | Student | Enrolled 12, 21 — the **not** enrolled in Algebra student, for negative cases |
| `omer.katz` | Student | The seeded TIMED_OUT attempt |
| `principal.avia` | Principal | Read-only scenarios |

Key seed rows: exam **101101** (Algebra Midterm, v1 REJECTED → v2 APPROVED) · exam **101201**
(Calculus, PENDING) · exam **202102** (Collections Quiz, REJECTED) · execution **4821** (closed,
fully graded) · **7390** (closed, awaiting grading) · **5164** (scheduled today) · **2075** (live).

---

## Summary

| # | Scenario | Cases | Status |
|---|---|---|---|
| 1 | Login (כניסה למערכת) | 4 | ✅ 3 passed, 1 partial (throttle not driven) — B-1 fixed |
| 2 | Question bank editing (עריכת מאגר שאלות) | 8 | ⬜ |
| 3 | Exam building (בניית מבחנים) | 9 | ⬜ |
| 4 | Exam approval (אישור מבחן) | 6 | ⬜ |
| 5 | Out of the drawer (הוצאת מבחן מהמגרה) | 6 | ⬜ |
| 6 | Exam execution (ביצוע מבחן) | 10 | ⬜ |
| 7 | Extending exam duration (הארכת משך הבחינה) | 4 | ⬜ |
| 8 | Exam checking (בדיקת מבחנים) | 7 | ⚠ 6 passed, 1 partial — B-3, B-4 |
| 9 | Viewing an exam grade (צפיה בציון הבחינה) | 5 | ⚠ 2 passed, 1 passed below the screen, 2 not walked |
| 10 | Viewing exam results (צפיה בתוצאות בחינות) | 5 | ⬜ |
| 11 | Viewing data — principal (צפיה בנתונים) | 4 | ⬜ |
| 12 | Viewing reports (צפיה בדו"חות) | 5 | ⬜ |
| 13 | Creating a study bot (יצירת בוט לימודי) | 6 | ⬜ |
| 14 | Using the bot (שימוש בבוט) | 7 | ⬜ |
| 15 | Client-server on separate machines, JARs, connect GUI | 5 | ⬜ |
| 16 | Concurrent users; no double login | 4 | ⬜ |
| 17 | Test data prepared in the database | 3 | ⬜ |
| 18 | Efficient computing, no user-initiated refresh | 5 | ⬜ |
| 19 | Flexible, change-tolerant design | 3 | ⬜ |
| 20 | Reuse; use of design patterns | 3 | ⬜ |
| 21 | UI quality and friendliness | 6 | ⬜ |
| | **Total** | **115** | |

---

## 1 — Login (כניסה למערכת) · T-1

**PRD:** F1.1, F1.2, F1.5 · **Spec:** S-38

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 1.1 | Launch the client. On the connect screen, accept the pre-filled address (or enter host/port). Connect. Sign in as `dana.cohen` / `demo123`. | Connect screen appears **before** login, pre-filled from defaults. Login succeeds. | **Passed** on the 2026-08-22 re-run against a `--reseed` database (375 rows). Connect screen appeared before login with the address pre-filled; sign-in succeeded against the seeded `users` table. The blocking cause recorded in the first run was the empty table, not a defect. | ✅ | B-1 (now fixed) |
| 1.2 | Observe the shell after 1.1. | Teacher shell: navigation rail with Dashboard, Question Bank, Exams, Results, Study Bot, Settings. **No** Approvals item. Dashboard greets by name. | **Passed.** Teacher rail as specified, with no Approvals item — the discriminating detail, since Approvals belongs to the coordinator and not to a plain teacher. Dashboard greeted her by name. | ✅ | |
| 1.3 | Sign out. Sign in as `rina.barak`, then `maya.levi`, then `principal.avia`. | Each gets a different, role-appropriate menu: `rina.barak` = teacher rail **plus Approvals** — nothing more; the dual-hat coordinator is a teacher with one extra rail item, not a distinct shell; `maya.levi` = Dashboard / Take Exam / My Grades / Study Bot / Settings; `principal.avia` = Dashboard / Data / Reports / Settings with nothing mutating. | **Passed.** All three rails as specified. **My Grades is live for the first time** on `maya.levi`’s rail — clickable rather than the greyed "Arrives with E13" placeholder the first run saw — which is the precondition for every case in scenario 9. Take Exam remains greyed pending E10’s screen; that is the current state of the build and not a defect of this case. | ✅ | |
| 1.4 | Sign in as `maya.levi` with password `wrong`. Repeat 5 times, then try the correct password. | Each failure shows one generic message that does **not** reveal whether the username exists. After 5 failures the 6th attempt is refused for 30s even with the right password. | Partially evidenced: three failed attempts on `dana.cohen` produced one generic "incorrect username or password" each — indistinguishable from the no-such-user case, which is F1.1's requirement. The throttle itself was not driven to 5. Full run blocked with 1.1. | ⚠ | |

---

## 2 — Question bank editing (עריכת מאגר שאלות) · T-2

**PRD:** F2.1–F2.6 · **Spec:** S-5, S-8, S-9 · **Decision:** C-8 / ADR-016

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 2.1 | As `dana.cohen`, open Question Bank → Add. Fill text, 4 distinct answers, mark one correct, set topic and difficulty, attach an image. Save. | Question is created for Algebra (11). ID is assigned by the server, read-only, 5 digits = `11` + 3-digit serial (S-8). | | ⬜ | |
| 2.2 | In the same form, try to save with two identical answers; then with no answer marked correct; then with two marked correct. | Each is refused with a specific message. Exactly one correct answer is enforced, answers must be pairwise distinct (C-8). Correct-answer control is single-select, so "two correct" must be impossible to express. | | ⬜ | |
| 2.3 | As `dana.cohen`, open the Course filter. | Only Algebra (11) and Calculus (12) are offered — the courses she teaches (S-5). Java and Databases questions are not reachable. | | ⬜ | |
| 2.4 | Open question **11005**, change its text, save. Then open its version history. | A **new version** is created. The previous version is still in the bank and viewable (T-2.2). The bank list shows the latest version. | | ⬜ | |
| 2.5 | Open exam **101101** v2 and inspect the question that came from 11005. | It still references **version 1** — the released exam is pinned to the version it was built from (C-2, S-14). It did **not** silently follow the edit in 2.4. | | ⬜ | |
| 2.6 | Browse the bank. Filter by course, then topic, then difficulty, then free-text search. Scroll a list with illustrated questions. | Filters combine correctly. List + detail layout. Images load lazily, not all at once (NFR-18). | | ⬜ | |
| 2.7 | Try to delete question **11001** (used by exam 101101). | Deletion is **blocked**, with a dialog naming the exams that reference it (F2.5). | | ⬜ | |
| 2.8 | Delete a question no exam references (e.g. a freshly created one). Confirm. Then look for it in the bank and in version history. | Soft-deleted after a confirm: gone from the bank list, version history preserved, its serial is not reused. | | ⬜ | |

---

## 3 — Exam building (בניית מבחנים) · T-3

**PRD:** F3.1–F3.6 · **Spec:** S-10, S-11, S-12, S-13

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 3.1 | As `dana.cohen`, create an exam for Algebra: name, duration, general text for examinees, teacher-only text. | All four fields accepted. Author is recorded automatically as `dana.cohen` (S-12). ID assigned by server: 6 digits = subject(2)+course(2)+serial(2) → `1011nn` (S-10). | | ⬜ | |
| 3.2 | Compose **manually**: pick questions from the Algebra bank, reorder them, assign points. Watch the points indicator. | Live running total. Save is **blocked** — not merely warned — while the total ≠ 100 (F3.1). | | ⬜ | |
| 3.3 | Set the points to total exactly 100 and save. | Saves. The exam appears in the drawer as DRAFT. | | ⬜ | |
| 3.4 | Create another exam, compose **automatically**: request 5 questions from topic "משוואות ליניאריות", mixed difficulty. | Server selects and returns a draft composition. It is editable before saving. | | ⬜ | |
| 3.5 | Compose automatically for Java (as `avi.mizrahi`): request **3 questions from topic "Recursion"**. | **No exam is created.** The report states exactly what is missing — the bank holds 2 Recursion questions (T-3 note, F3.3). | | ⬜ | |
| 3.6 | Same, but request **1 HARD Recursion question**. | Again refused with a specific shortfall message: no HARD question exists in that topic. | | ⬜ | |
| 3.7 | Edit exam **101101** and save. | A **new version** is created; the previous version is retained (T-3.5, C-2). | | ⬜ | |
| 3.8 | Add question **11009** to a second Algebra exam. | Allowed — a question may belong to more than one exam (T-3 note). | | ⬜ | |
| 3.9 | In one exam version, try to add question 11005 **v1** and 11005 **v2**. | Refused. The same question cannot appear twice in one exam version, even through different versions (PRD §6). | | ⬜ | |

---

## 4 — Exam approval (אישור מבחן) · T-4

**PRD:** F4.1–F4.3 · **Spec:** S-14

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 4.1 | As `dana.cohen`, submit the Calculus exam **101201** for approval. Sign in as `rina.barak` and open Approvals. | The exam appears in the pending queue for subject 10 (Mathematics) — her subject only. | | ⬜ | |
| 4.2 | Open it from the queue. | Full **read-only preview of the exam exactly as a student will see it**, plus metadata and the teacher-only notes (F4.1 — a v1 failure, check this carefully). | | ⬜ | |
| 4.3 | Reject it with no reason entered. | Refused — a reason is **required** (T-4.2). | | ⬜ | |
| 4.4 | Reject with a reason. Sign back in as `dana.cohen`. | Reason is stored, visible on the exam, and delivered to `dana.cohen` as a notification (T-4.2, F4.2). | | ⬜ | |
| 4.5 | As `rina.barak`, approve a resubmitted version. | That **version** becomes APPROVED; the author is notified. Earlier versions keep their own status. | | ⬜ | |
| 4.6 | As `michal.sharon` (coordinator of subject 20 **and** the only Databases teacher), approve her own exam **202201**. Then inspect the server log. | Allowed — PRD F4.3: not required by spec, permitted, **but logged**. The logging owner is **E8's `ApprovalService`** (confirmed in the PR #2 review). Verify the log entry actually exists; "allowed but logged" with no log line is a silent failure. | | ⬜ | |

---

## 5 — Taking an exam out of the drawer (הוצאת מבחן מהמגרה) · T-5

**PRD:** F5.1–F5.5 · **Spec:** S-2, S-14, S-15, S-17 · **Decision:** C-1

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 5.1 | As `dana.cohen`, try to release exam **101102** (DRAFT) and exam **101101 v1** (REJECTED). | Both refused. Only an **APPROVED version** can be released (T-5.1, S-14). | | ⬜ | |
| 5.2 | Release **101101 v2** (APPROVED). Set open and close datetimes. | Accepted. Validation rejects close ≤ open, and a close time in the past (F5.2). | | ⬜ | |
| 5.3 | Set the execution code. Try `12`, then `ABCDE`, then `4821`. | 4 characters exactly, alphanumeric (C-1). Too short and too long are refused. | | ⬜ | |
| 5.4 | Sign in as `maya.levi` and look everywhere a student can see this execution. | The code is **never** shown to a student anywhere in the app — it is delivered orally (S-17). | | ⬜ | |
| 5.5 | Release **101101 v2** a second time with a different window and code. | Allowed. The same exam can be taken out of the drawer many times, each execution with its own schedule, code and statistics (S-2). | | ⬜ | |
| 5.6 | Open the release list. Cancel a SCHEDULED release (confirm). Then close a LIVE one early. | Cancel works before open, with confirm. Closing a live one warns first and then behaves like time expiry for active students (F5.5). Status chips read Scheduled / Live / Closed and update live. | | ⬜ | |

---

## 6 — Exam execution (ביצוע מבחן) · T-6

**PRD:** F6.1–F6.10 · **Spec:** S-15, S-18, S-19 · **Decision:** C-4

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 6.1 | As `maya.levi`, open Take Exam. Enter the code of the **live** execution (`2075`). Then enter her national id. | Code accepted → id prompt → exam form renders with the general text, questions, single-choice answers and any illustrations (T-6.1–3). | | ⬜ | |
| 6.2 | Repeat 6.1 but enter another student's national id. | Refused. The id is validated against the signed-in student's own identity (F6.1). | | ⬜ | |
| 6.3 | As `noam.peretz` (not enrolled in Algebra), enter code `2075`. | Refused — not enrolled in that course. | | ⬜ | |
| 6.4 | Enter the code of the **scheduled** execution (`5164`) before its open time, and the code of a **closed** one (`4821`). | Both refused with a window message. Students can start only inside the open–close window (S-15, F5.2). | | ⬜ | |
| 6.5 | Watch the timer after entering the id in 6.1. | Countdown starts **at id entry**, not at code entry (S-18). It is server-authoritative: the client displays a synced value. Amber at 25% left, red at 5 minutes. | | ⬜ | |
| 6.6 | Answer 3 questions. Kill the client process. Relaunch, sign in, re-enter the exam. | Answers were auto-saved; the attempt resumes with saved answers **and the correct remaining time** — the clock kept running server-side (F6.3). | | ⬜ | |
| 6.7 | **Inspect the wire.** Capture the take-exam payload the server sends (server log / debug view). | The DTO physically contains **no** correct-answer field. Not hidden in the UI — absent from the data (F6.6; this was the v1 leak). | | ⬜ | |
| 6.8 | Let the timer run out without submitting. | Server force-submits whatever is saved and marks the attempt TIMED_OUT. Client shows a full-screen "Time is up" takeover: **no confirmation asked**, exam unreachable behind it, summary of what was handed in, single "Back to my dashboard". No later answer change is accepted server-side (F6.4). | | ⬜ | |
| 6.9 | Start a new attempt, answer some questions, press Submit with time remaining. | Two-step: a confirm dialog with an answer-summary grid (answered vs unanswered chips, clickable to jump), remaining time, and an "unanswered score 0" note → Submit / Keep working (F6.9). Confirm → success screen with handed-in time and solving minutes (F6.10). | | ⬜ | |
| 6.10 | Re-enter the same code after submitting. | "Already submitted" — one attempt per student per execution (F6.7). | | ⬜ | |

---

## 7 — Extending exam duration (הארכת משך הבחינה) · T-7

**PRD:** F7.1–F7.3 · **Spec:** S-20, S-21

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 7.1 | With `maya.levi` mid-attempt on execution `2075`, sign in as `dana.cohen` on a second machine, open the live monitor and add 15 minutes. | The student's timer updates **immediately** without any refresh: chip flashes, "+15:00" animates, a toast names who did it and the new end time (F7.1). The student is never left guessing. | | ⬜ | |
| 7.2 | After 7.1, open exam **101101 v2** in the drawer and check its duration. | Unchanged. The extension applies to the **current execution only** (S-20). | | ⬜ | |
| 7.3 | Release **101101 v2** again and start a fresh attempt. | The new execution uses the original duration, not the extended one. | | ⬜ | |
| 7.4 | Watch the teacher monitor while two students sit the exam concurrently. | Live counts of started / submitted / timed-out and per-student status and remaining time, all pushed — no refresh button anywhere (F7.2, NFR-18). | | ⬜ | |

---

## 8 — Exam checking (בדיקת מבחנים) · T-8

**PRD:** F8.1–F8.5 · **Spec:** S-22, S-23, S-24, S-25, S-26 · **Decision:** C-3

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 8.1 | As `avi.mizrahi`, open results for execution `7390` (closed, awaiting grading). | Every attempt already carries an auto-computed score: per-question points, correct ⇔ the single correct answer (F8.1, C-8). | **Passed.** The Grading rail item opened onto the queue with the Java sitting waiting — "8 sat · 8 marked · 8 still to approve". Every attempt already carried a score, computed on submission by `GradingOnSubmit` rather than by anything a teacher did. | ✅ | |
| 8.2 | As `maya.levi` — before any approval — look for the grade of execution `7390`. | **Not visible.** Auto-checking alone does not publish anything (C-3, S-24). | **Passed — already evidenced by case 9.1.** She sat both seeded exams and My Grades showed exactly one row, the Algebra one. Her Java 100 existed, was auto-computed, and was invisible. | ✅ | |
| 8.3 | As `avi.mizrahi`, change one student’s grade without entering a justification. | Refused. A manual change **requires** an explanation (T-8.3, S-23). | **Passed.** The override dialog refused with "Please say why you are changing this score." and the score did not move. Refused client-side before the request travelled, and the server refuses it independently (`GradingHandlersTest`), so the rule holds on both sides. | ✅ | |
| 8.4 | Change it again with a justification, and add a comment to the student. Then inspect the stored record. | Original auto grade, the change, and the reason are all stored — a full audit trail (F8.3). The comment is saved (S-22). | **Partial.** The justification half passes: with a reason, the score changed, the row showed **Adjusted**, and the **Auto column kept the machine’s original score** — F8.3’s audit trail, the change visible and the evidence intact. **The comment half cannot pass: `teacherComment` has no wire** (B-3). | ⚠ | B-3 |
| 8.5 | Approve the grades (try both per-student and bulk). | Both work. Each affected student receives a "your grade is available" notification (F8.4). | **Passed, with a defect found.** One verb serves both: selecting a single row approved that student, and Select-all then Approve approved the rest, with a confirmation naming the correct count. The queue then dropped the sitting entirely — "Nothing to grade" — which is the exclusion rule working. The notification half is evidenced: `maya.levi`’s bell showed **1** unread on her next sign-in. **Defect B-4:** Select-all ticked the rows in the session but highlighted nothing in the table. | ✅ | B-4 |
| 8.6 | As `maya.levi`, open the grade now. | Visible, together with her checked form: wrong answers marked, teacher comments included (S-24). | **Passed.** My Grades went from one row to **two**: the grade that was invisible in 8.2 appeared the moment a teacher approved it — C-3 end to end in one screen. Her Java checked form opened with all seven questions marked Correct (she scored 100). Teacher comments render where present; none could be written here — see B-3. | ✅ | B-3 |
| 8.7 | As `maya.levi`, look for the class average, median or distribution. | Not available anywhere to a student (S-26). | **Passed, and structurally.** Nothing of the kind appears on her screens. Stronger than not-seeing-it: the student-facing wire types are `MyGrades` (her own rows) and `CheckedForm` (her grade, her attempt, her answers), and **neither has a field a class statistic could travel in**. The statistics verbs are teacher-gated. S-26 holds by construction, not by omission. | ✅ | |

---

## 9 — Viewing an exam grade (צפיה בציון הבחינה) · T-9

**PRD:** F9.1 · **Spec:** S-36

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 9.1 | As `maya.levi`, open My Grades. | Lists the exams she took with her grades — including the seeded Algebra Midterm (execution `4821`). | **Passed**, and it demonstrates S-24 live. Maya sat **two** seeded exams — Algebra (execution 1, 71, approved) and Java (execution 2, 100, `AUTO`, unapproved). Exactly **one** row appeared: the Algebra midterm at 71 / 100. The unapproved 100 is absent, which is C-3 / S-24 holding on a running server rather than in a unit test: auto-grading publishes nothing until a teacher approves. The row carried its own exam name and course code (contract amendment v1.1) — without those the row would have read only "71 / 100" with no way to tell which exam it was. | ✅ | |
| 9.2 | Open the Algebra Midterm result. | The checked form: her answers, wrong ones marked, the correct answers shown, points per question, teacher comments (T-9.2, S-24). | **Passed.** Opened from the My Grades row. Header carried exam, course and the effective score; the attempt line read as submitted with the recorded solving time. Seven questions, each labelled Correct or Wrong with "Your answer" and "Correct answer" tags on the options and points per question. **The "Reviewed by your teacher" marker was correctly absent** — Maya’s 71 was never overridden, and the marker keys on the two scores *differing* rather than on a final score being present, which every approved row has. Styling is deliberately plain: the marking colours are left for the lead’s screen review, and every outcome carries a word as well as a class so the form does not depend on colour alone. | ✅ | |
| 9.3 | Use the export / print action on that result. | She obtains a copy of the checked exam (S-36). | | ⬜ | |
| 9.4 | Try to reach another student's grade — via the UI, and by replaying the request with a different student id. | Refused **server-side**, not just hidden in the UI. A student can never see another's grade (T-9 note, F9.1). | | ⬜ | |
| 9.5 | As `omer.katz` (the seeded TIMED_OUT attempt), open his result. | Grade is present and the attempt is shown as timed out, with his actual solving time in minutes (S-19). | **Passed below the screen; screen render outstanding.** Verified by running the production assembler (`CheckedFormService` → `GradeReviewService` → `CheckedFormCopy`) against the reseeded database: header `45 / 100`, attempt line **"Time ran out — submitted automatically · 75 minutes"**, seven questions, of which **four render as "Not answered" and three as Correct**. All three checked-form gates pass for this grade (execution `CLOSED`, grade `APPROVED`, ownership by query), and the attempt carries 3 answer rows on a 7-question paper — the four absent rows of §9.1.1, present in the database as absences rather than zeros. **Method noted deliberately:** this exercises every layer below JavaFX and does not exercise rendering, so the pixels are confirmed at the manual pass rather than here. An earlier report of "no Not answered questions" was a false alarm — most likely a different student’s form still open, since `maya.levi` answered everything and correctly shows none. | ⚠ | |

---

## 10 — Viewing exam results (צפיה בתוצאות בחינות) · T-10

**PRD:** F9.2 · **Spec:** S-35

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 10.1 | As `dana.cohen`, open Results. | Lists the exams **she wrote**, including executions run by other teachers (S-35). | | ⬜ | |
| 10.2 | Open execution `4821`. Read the table. | Per-student rows: score, submitted vs timed out, solving time. 8 students, matching the seeded roster. | | ⬜ | |
| 10.3 | Switch to the histogram view. | Score-bucket bars themed to the active palette, with mean, median and ±1σ markers labelled. Stat cards above: average, median, std, min/max, pass rate, participants. Values match the seeded stats — **mean 72.5, median 72.5, σ 17.5, pass rate 7/8**. | | ⬜ | |
| 10.4 | Hover a bar; toggle count ↔ percentage. | Tooltip gives bucket range, count, percentage. Toggle switches the axis without a reload. | | ⬜ | |
| 10.5 | Open results for an execution with no attempts (`5164`, scheduled). | A proper empty / insufficient-data state — not a blank panel or a crash. | | ⬜ | |

---

## 11 — Viewing data — principal (צפיה בנתונים) · T-11

**PRD:** F9.3 · **Spec:** S-7

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 11.1 | Sign in as `principal.avia`. Open Data. | Can browse the question bank school-wide, across every course and subject. | | ⬜ | |
| 11.2 | Browse exams and exam results. | Both readable, school-wide. | | ⬜ | |
| 11.3 | Look for any create / edit / delete / approve control anywhere in her shell. | **None exist.** Read-only by definition (S-7). | | ⬜ | |
| 11.4 | Replay a mutating request (e.g. update question) with her session. | Refused server-side. The role has literally zero mutating verbs authorized (F9.3) — not merely a hidden button. | | ⬜ | |

---

## 12 — Viewing reports (צפיה בדו"חות) · T-12

**PRD:** F9.4 · **Spec:** S-25, S-37

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 12.1 | As `principal.avia`, open Reports. Run the report comparing **different exams of the same teacher**. | Average, median and decile distribution per execution, compared side by side (T-12). | | ⬜ | |
| 12.2 | Run the report comparing **different exams of the same course**. | Same three measures, grouped by course. | | ⬜ | |
| 12.3 | Run the report comparing **different exams of the same student**. | Same three measures, tracking one student across her executions. | | ⬜ | |
| 12.4 | Cross-check any figure against the stored statistics for execution `4821`. | The report reads the **stored** per-execution statistics (S-25) rather than recomputing differently. Mean 72.5, median 72.5 — identical to §9.1 of the seed. | | ⬜ | |
| 12.5 | **Defense question rehearsal:** ask what it takes to add a new report dimension. | Answer demonstrable in the code: one new Strategy class plus a menu entry, nothing else (F9.4, S-37, NFR-19). | | ⬜ | |

---

## 13 — Creating a study bot (יצירת בוט לימודי) · T-13

**PRD:** F12.1–F12.3 · **Spec:** S-6, S-28, S-29, S-30

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 13.1 | As `avi.mizrahi`, create a study bot for Java (21): name + information sources. | Created. Only courses he teaches are offered (S-6). | | ⬜ | |
| 13.2 | Add sources of each type: a PDF, a Word document, and free text. | All three accepted and parsed server-side into indexed text at upload time (S-28, F12.2). | | ⬜ | |
| 13.3 | Upload a corrupt or password-protected PDF. | Parse failure reported immediately and clearly; no half-created source row is left behind. | | ⬜ | |
| 13.4 | Edit an existing source; remove one. | Both work; changes notify the other teachers of the course (F12.3). | | ⬜ | |
| 13.5 | As `tamar.shani` (the Java co-teacher), open the course bot and add a source. | She edits the **existing** bot — one bot per course (S-30, T-13.3). She is not offered "create a new bot" for Java. | | ⬜ | |
| 13.6 | With `avi.mizrahi` holding the source editor open, have `tamar.shani` open the same source. | She sees a live "Being edited by Avi Mizrahi" badge and a read-only editor; it flips to editable when he closes (F10.2). | | ⬜ | |

---

## 14 — Using the bot (שימוש בבוט) · T-14

**PRD:** F12.4–F12.11 · **Spec:** S-31, S-32, S-33, S-34 · **Decision:** C-4

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 14.1 | As `maya.levi` (enrolled in Java 21), open the Java study bot and ask a course question. | Answer arrives, displayed incrementally with a typing indicator and a course context header (F12.5). | | ⬜ | |
| 14.2 | As `noam.peretz`, try to open the **Databases** bot (he is not enrolled in 22). | Refused — enrolment required (S-31). | | ⬜ | |
| 14.3 | As `shira.dahan` (enrolled in 22), open the Databases bot — seeded **inactive**. | Refused, with a clear "not currently available" message. Enrolment alone is not enough; the bot must be active too (S-31). | | ⬜ | |
| 14.4 | Ask the bot something clearly outside the course material. | Friendly fallback: "The bot couldn't answer that — try rephrasing or ask your teacher." Not a stack trace, not an empty bubble (S-32, F12.7). | | ⬜ | |
| 14.5 | As `maya.levi`, open her bot history. | Her own past sessions, each Q/A with its timestamp, reopenable and continuable (S-33, F12.10). | | ⬜ | |
| 14.6 | As `avi.mizrahi`, open the bot's teacher view. | Aggregate only: total questions, questions over time, frequent topics. **No student identities anywhere** — check the view and the DTO (S-34, F12.11). | | ⬜ | |
| 14.7 | Start an attempt on a Java execution, then open the Java bot; then open the Algebra bot. | The exam's own course bot is locked for that student. Another course's bot shows the integrity notice first; proceeding notifies the executing teacher and flags the monitor row (C-4, F6.8, F11.1). | | ⬜ | |

---

## 15 — Client-server on separate machines, JARs, connect GUI · T-15

**PRD:** F13.1–F13.4, F14.1–F14.2 · **Spec:** S-39, S-40, S-41, S-42

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 15.1 | On machine A, run `G<Num>_Server.jar` from a terminal. On machine B, **double-click** `G<Num>_Client.jar`. | Server runs terminal-only with structured logs **and** opens its console. Client launches by double-click (F14.1). | | ⬜ | |
| 15.2 | Also start the client with `java -jar` from a terminal. | Works identically (T-15, F14.1). | | ⬜ | |
| 15.3 | On the client connect screen, use the discovery picker; then enter host/port manually. | Discovery lists the server with name, address and fingerprint. Manual entry is **always** available and one click away — discovery failing never blocks connecting (F1.5, F13.4). | | ⬜ | |
| 15.4 | Connect from machine B to machine A over the LAN, then sign in and use the app. | Full round trip over TCP/IP on a LAN, GUI client (not web), separate machines (S-40, S-42). | | ⬜ | |
| 15.5 | Restart the client. | Last server remembered and auto-connected; a changed fingerprint raises a prominent warning requiring explicit confirm (F13.4). | | ⬜ | |

---

## 16 — Concurrent users; no double login · T-16

**PRD:** F1.3, F1.4 · **Spec:** S-40

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 16.1 | Sign in as `dana.cohen` on machine A. Sign in as `dana.cohen` on machine B. | Machine B is refused with a clear message ("This account is already signed in elsewhere") that reveals no further detail (F1.3). | | ⬜ | |
| 16.2 | Sign out on machine A, then retry on machine B. | Succeeds. | | ⬜ | |
| 16.3 | Sign in on A again, then **kill** the client process. Immediately retry on B. | Succeeds — the socket drop frees the session immediately, without waiting for a timeout (F1.3). | | ⬜ | |
| 16.4 | Sign in as four different users at once (`dana.cohen`, `rina.barak`, `maya.levi`, `principal.avia`) and use the app concurrently. | All four work simultaneously and independently (T-16, S-40). | | ⬜ | |

---

## 17 — Test data prepared in the database · T-17

**PRD:** NFR-17, §5 · **Content:** `docs/seed/SEED_CONTENT.md`

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 17.1 | On a fresh empty database, start the server. | Flyway migrations run automatically; the seed is loaded by one command or one console button (F14.2, E2.15). | | ⬜ | |
| 17.2 | Load the seed a second time. | Idempotent — no duplicate rows, no constraint failures. | | ⬜ | |
| 17.3 | Open every demoed screen in turn. | None looks empty or fake: bank populated, exams in mixed states, one execution fully graded with a spread histogram, bot sessions present, notification bell non-zero (PRD §5, E2.16). | | ⬜ | |

---

## 18 — Efficient computing, no user-initiated refresh · T-18

**PRD:** NFR-18, F11.1–F11.3 · **Spec:** S-44

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 18.1 | Search every screen for a "Refresh" / "Reload" control. | **None exists anywhere** (NFR-18). | | ⬜ | |
| 18.2 | With `dana.cohen`'s Approvals queue open, have another teacher submit an exam for approval. | The queue updates live, pushed — without any user action. | | ⬜ | |
| 18.3 | With a student mid-attempt, extend the time from the teacher monitor (as in 7.1). | The student's timer updates live (F7.1). | | ⬜ | |
| 18.4 | Approve a grade while the student has My Grades open. | The grade appears live, with a notification (F8.4, F11.1). | | ⬜ | |
| 18.5 | Open a long bank list with many illustrated questions. | Images load lazily and the list pages; the UI never blocks while loading (NFR-18). | | ⬜ | |

---

## 19 — Flexible, change-tolerant design · T-19

**PRD:** NFR-19 · **Spec:** S-37, S-43, S-45

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 19.1 | **Defense rehearsal:** "Add a new report type." | One new Strategy class + a menu entry (F9.4, S-37). Demonstrate in the code, do not just assert it. | | ⬜ | |
| 19.2 | **Defense rehearsal:** "Swap the network protocol for REST." | One new implementation of `IClientConnection`; no UI change. The Adapter boundary is real and nameable. | | ⬜ | |
| 19.3 | **Defense rehearsal:** "Swap the bot provider." | Provider-adapter chain: a new adapter class; keys stay server-side (F12.6). Phase-2 internet access (S-43) is a deployment change, not a redesign. | | ⬜ | |

---

## 20 — Reuse; use of design patterns · T-20

**PRD:** NFR-20 · **Reference:** `PLAN.md` §2 pattern table, `DECISIONS.md`

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 20.1 | For each pattern claimed in `PLAN.md` §2, point at the class that implements it. | Every claim resolves to real code: Adapter (`IClientConnection`), Singleton (`ScreenManager`, session factory), Template Method (screen lifecycle), DAO/Repository, Strategy (reports, validators, bot providers), Observer (EventBus + server push), State (exam/execution/grade lifecycles), Command (protocol verbs). | | ⬜ | |
| 20.2 | Check that patterns are named in Javadoc where used. | Named at the boundary classes, not only in the document (NFR-20). | | ⬜ | |
| 20.3 | Point at the reused pieces: one component library across all screens; one histogram component in results **and** reports. | Demonstrable reuse, not copy-paste (F9.2, F9.4). | | ⬜ | |

---

## 21 — UI quality and friendliness · T-21

**PRD:** NFR-21, §4 · **Spec:** S-44

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 21.1 | Walk every screen looking for lists. | Lists are used wherever a set is shown; each has an empty state that explains rather than showing a blank box. | | ⬜ | |
| 21.2 | Trigger a slow operation (large report, bot call). | Progress feedback appears — spinner, skeleton or overlay. The UI never appears frozen. | | ⬜ | |
| 21.3 | Trigger failures: disconnect the server mid-action; submit an invalid form. | Every failure shows a **human** message, never a stack trace or an error code. A reconnect banner appears on disconnect. | | ⬜ | |
| 21.4 | Complete successful actions: save a question, approve an exam, submit an exam. | Each confirms success visibly (toast or success screen). | | ⬜ | |
| 21.5 | Switch theme light ↔ dark and change the accent palette. | Everything re-themes consistently; nothing becomes unreadable. | | ⬜ | |
| 21.6 | Resize the window to three sizes; view a Hebrew question and an English question side by side. | Layout holds at all three sizes. Hebrew renders correctly RTL, English LTR, in the same screens (X-I18N). | | ⬜ | |

---

---

## Bugs found

Assignment 3 §1 asks for the bugs found **and which test case exposed them**, so every entry
names its case. Ids are `B-n` and are what the `Bugs found` column cites.

| # | Found by | Severity | Status | What |
|---|---|---|---|---|
| B-1 | case 1.1 | Low (docs) | **Fixed** | `docs/DEMO_ACCOUNTS.md` presented its accounts as working credentials with nothing saying they only exist once the E2.15 seed loader has run. On a freshly migrated database every login failed with F1.1's deliberately generic message, so there was no way to tell "seed not loaded" from "wrong password". **Fixed before the 2026-08-22 re-run:** the file now carries "⚠ These accounts do not exist until the seed has been loaded", names `RepositoryUserDirectory` as the authority, and adds the diagnostic — load the seed before suspecting the credentials. Verified by reading the file; case 1.1 then passed against a reseeded database. Never a code defect: the server was behaving as specified. |

| B-2 | server start-up, 2026-08-22 re-run | Low (cosmetic) | Open | The server prints a red `ERROR` line about thirty seconds into start-up: `Log4j2 could not find a logging implementation. Please add log4j-core to the classpath. Using SimpleLogger to log to the console...` Something on the classpath — most likely a transitive dependency of the bot SDK — uses the log4j2 API with no binding present, and log4j2 falls back to its own SimpleLogger. **Nothing is broken:** the server’s own logging is logback and every subsequent line appears normally. It matters because this is the terminal that is visible during the defence, and a red ERROR line invites a question that costs more time to answer than to prevent. Appears on the `SeedMain` path too. **Fix:** either add `log4j-to-slf4j` so the API routes into logback, or exclude the log4j2 API from the dependency that drags it in. Neither is urgent; both are small. |

| B-3 | case 8.4 | **Medium** | Open | **`teacherComment` can be read but never written.** It is on the student wire and the teacher wire, both copy classes and both screens — and the only thing that ever sets it is the seed loader. No request DTO carries a comment field and no service calls `Grade.setTeacherComment`. So **S-22 has no path through the application**: a teacher cannot leave a student the one piece of free text the student is allowed to read. The seed masks it — `yael.azulay` has a seeded comment, so every screen renders one and looks finished. Distinct from `overrideReason`, which is required, works, and is deliberately never shown to the student. **Fix (lead’s call, contract change):** (A) add `teacherComment` to `GradeOverrideRequest` — additive, reuses the existing gate and dialog, but ties commenting to changing a score; (B) a separate `GRADE_COMMENT_SET` verb — cleaner, but a new verb on a frozen contract. Recommended: A for v1, B as the v2 shape. |
| B-4 | case 8.5 | Low | **Fixed** | **Select-all updated the session’s selection but not the table’s visible one.** The button enabled and the confirmation counted correctly, so the right rows were approved — but a teacher could not see what she was about to approve, on the one action that cannot be undone. Cause: the grading screen wired selection table → session and never session → table. Invisible to the session tests, where the selection genuinely is correct, and to the view, which is coverage-excluded by design — the class of defect a manual pass exists to catch. **Fixed** in the same change: `render()` now drives the table’s selection from the session inside the existing re-entrancy guard, and skips when the two already agree so it cannot fight a teacher mid-click. |
| B-5 | case 8.6 / screenshot | Low (cosmetic) | **Fixed** | **My Grades truncated the Approved column to "23 Au…".** The table divided its width evenly, so a date got the same room as a two-character course code, and the column a student reads to know when her grade arrived could not show a date. Header "Teacher’s note" was clipped for the same reason. Found by looking at the rendered screen; the copy tests format the date correctly and never see a column. **Fixed:** preferred widths per column, sized to content, still adaptive. |

### Not bugs, recorded so they are not re-investigated

- **Scenario 1's blocked cases are blocked, not failing.** The server is correct; the database is
  empty because E2.15 has not merged. They become runnable the moment the seed loads, and nothing
  about them needs re-designing first.

## Notes for the submission document

- The **Bugs found** column is what Assignment 3 §1 asks for — "any bugs found + which test case
  exposed them". Fill it as testing happens, not retrospectively; a bug with no test case number
  next to it is worth less in the write-up.
- Cases marked ⛔ blocked are features not yet built. Blocked is a legitimate status during
  development and an illegitimate one at submission.
- Several cases deliberately test **server-side** enforcement by replaying a request rather than
  clicking (2.3, 9.4, 11.4, 6.7). Those are the ones that answer "could a student cheat?", which
  is where v1 lost marks. Do not downgrade them to UI checks.

---

# Hardening — edge cases for E12–E15 (Member B)

**Deliverable 3.** PRD §6's Grading and Reports lines expanded into concrete test ideas, and the
gaps that pass exposed. Each item becomes a test when its epic lands, so tests are written with
the feature rather than backfilled in E21.

> **Ownership changed after this was written (PR #2 review, 2026-08-19).** E14 (StatChart) and
> E15 (report engine) moved off Member B. **H12.\* and H13.\* stay with Member B**; **H14.\* and
> H15.\* now belong to whoever owns E14/E15** and are kept here only so they are not lost in the
> handover. Two of them carry decisions the whole team is bound by — **H14.4** (σ divisor) and
> **H15.2** (CANCELLED excluded from reports) — so they need a real owner, not just a home.
>
> Member B still produces the numbers H14.4 checks: **E12.4** computes and stores the statistics;
> E14 only displays them.

Ids are `H<epic>.<n>` so they never collide with the scenario cases above. These are **not**
counted in the 115 — the outline table is what we submit; this is how we get it green.

**Source** column: `§6` = verbatim from the PRD §6 catalog · `gap` = not in the catalog, added
here. Every `gap` row is a claim that PRD §6 under-covers my epics; see the note at the end.

## E12 — Grading

| # | Source | Given / When / Then |
|---|---|---|
| H12.1 | §6 | **Given** an approved grade, **when** the teacher edits the score without entering a justification, **then** the save is refused server-side — not merely disabled in the UI (S-23). |
| H12.2 | §6 | **Given** a set of grades already approved, **when** the teacher approves them again, **then** the operation is idempotent: no duplicate audit rows, no second notification to the student. |
| H12.3 | §6 | **Given** `maya.levi` signed in, **when** she requests a grade id belonging to `omer.katz` by replaying the request, **then** the server answers with an authorization error and no grade data (F9.1). |
| H12.4 | gap | **Given** an attempt that timed out with **zero** answers saved, **when** auto-check runs, **then** the score is 0 — not null, not an error, and the attempt still appears in the results table. |
| H12.5 | gap | **Given** an attempt with some questions unanswered, **when** auto-check runs, **then** unanswered questions score 0 and the total equals the sum of the answered ones (F6.9's "unanswered score 0" promise must be true server-side, not just stated in the dialog). |
| H12.6 | gap | **Given** exam 101101 v2 pins question 11005 at **version 1**, **when** auto-check grades an attempt, **then** it compares against version 1's correct answer — never the latest version. A question edited after release must not change past grades (C-2). |
| H12.7 | gap | **Given** an attempt still `IN_PROGRESS`, **when** the teacher tries to approve its grade, **then** it is refused: nothing is gradeable before it is submitted or timed out. |
| H12.8 | gap | **Given** `avi.mizrahi` did not write exam 101101, **when** he tries to approve grades for execution 4821, **then** the server refuses — grade approval belongs to the exam's author (T-8.2 read with S-35). |
| H12.9 | gap | **Given** a manual override from 51 to 55, **when** the stored record is inspected, **then** the original auto score, the new score, the reason and the actor are all present — an override that loses the original is an audit failure (F8.3). |
| H12.10 | gap | **Given** two teachers of the same course open the same student's grade, **when** both save a change, **then** the second is rejected with a conflict rather than silently overwriting (F10.3, F10.4 names grading explicitly). |

## E13 — Student results

| # | Source | Given / When / Then |
|---|---|---|
| H13.1 | gap | **Given** execution 7390 is auto-checked but **not approved**, **when** `maya.levi` opens My Grades, **then** the exam is absent or shown explicitly as not-yet-published — never a visible score (C-3, S-24). |
| H13.2 | gap | **Given** a student who has sat no exams, **when** she opens My Grades, **then** an empty state explains rather than showing a blank panel. |
| H13.3 | gap | **Given** `omer.katz`'s TIMED_OUT attempt, **when** he opens the result, **then** the checked form renders normally and his solving time is shown — a timed-out attempt is a result, not an error state (S-19). |
| H13.4 | gap | **Given** the checked form is open, **when** its payload is inspected, **then** it contains correct answers **only for the questions in this student's own attempt** — the review DTO is not a route to the whole bank's answer key. |
| H13.5 | gap | **Given** a grade is approved while the student has My Grades open, **when** approval completes, **then** the row appears live with no refresh (NFR-18, F8.4). |

## E14 — Teacher results & statistics

| # | Source | Given / When / Then |
|---|---|---|
| H14.1 | §6 | **Given** execution 5164 with no participants, **when** the teacher opens its results, **then** statistics read N/A and the histogram shows an insufficient-data state — no divide-by-zero, no empty chart frame. |
| H14.2 | §6 | **Given** an execution with exactly one participant, **when** statistics are computed, **then** median equals the average, σ is 0, and the histogram renders one bucket without collapsing. |
| H14.3 | gap | **Given** an execution where every student scored the same, **when** the histogram renders, **then** one full-height bucket with σ = 0 and the mean/median/±1σ markers coincident — the marker overlay must not misdraw when they stack. |
| H14.4 | gap | **Given** the seeded execution 4821, **when** E14 recomputes statistics, **then** they equal the stored ones exactly: Mean 72.5, median 72.5, σ 17.5. **σ uses the population divisor `n`.** A sample divisor gives 18.71 and would read as a bug. |
| H14.5 | gap | **Given** `dana.cohen` wrote exam 101101 and another teacher ran an execution of it, **when** she opens Results, **then** that execution appears — she sees every execution of exams she wrote, not only her own (S-35). |
| H14.6 | gap | **Given** the results table for 4821, **when** it is read, **then** it holds exactly 8 rows and they match the seeded Algebra roster — a participant count that disagrees with the attempt rows means the derived counts (F7.3) drifted. |
| H14.7 | gap | **Given** the histogram in count mode, **when** toggled to percentage, **then** the buckets sum to 100% and no bar changes relative height. |

## E15 — Principal views & report engine

| # | Source | Given / When / Then |
|---|---|---|
| H15.1 | gap | **Given** `principal.avia` signed in, **when** any mutating verb is replayed with her session, **then** the server refuses — the role has zero mutating verbs authorized, not merely hidden buttons (F9.3, S-7). |
| H15.2 | gap | **Given** a CANCELLED execution, **when** any report runs, **then** it is excluded from the corpus. ARCHITECTURE §5 says CANCELLED executions are excluded from statistics; a zero-participant row would skew every average (F5.5). |
| H15.3 | gap | **Given** stored statistics exist for 4821, **when** a report displays its average, **then** the figure comes from the **stored** stats (S-25), not a fresh computation — two code paths producing 72.5 and 77.9 is the failure this prevents. |
| H15.4 | gap | **Given** the same-student comparison, **when** the student sat exams in different courses, **then** the report groups correctly and does not silently average across incomparable exams. |
| H15.5 | gap | **Given** a teacher with exactly one execution, **when** the same-teacher comparison runs, **then** it renders a single-series result rather than an error or a blank comparison. |
| H15.6 | gap | **Given** the report engine, **when** a new dimension is added, **then** it requires one new Strategy class and a menu entry and nothing else — verified by actually adding a throwaway one, not by assertion (S-37, NFR-19, and the T-19 defense question). |

## Note for the reviewer — PRD §6 under-covers E12–E15

PRD §6 gives my four epics **five** lines: three under Grading, two under Reports. There is no
Results line at all, so student results (E13) and teacher results (E14) have no catalog entries —
the only E13-adjacent item, "student polls another student's grade id", sits under Grading.

By comparison the catalog gives Bot nine items and Discovery five. That asymmetry is not
proportional to risk: E12–E15 own grade correctness, and a wrong grade that looks plausible is
harder to notice at a defense than a bot that fails visibly.

The 23 `gap` rows above are my proposed coverage. Three constrain other people's code, and the
PR #2 review **accepted all three for PRD §6**:

- **H12.6** — grading must use the question **version pinned in the exam**, never the latest.
  This constrains E6 and E7, not just E12.
- **H14.4** — the σ divisor. Binds the seed, E14 and E15 to the same choice.
- **H15.2** — CANCELLED executions excluded from the report corpus. Constrains E9's release
  handling as much as E15's engine.

**Status:** accepted in review; the PRD edit is Naji's, not mine. As of commit `14bc23f` the
wording is not yet on `main` — `PRD.md` §6's Grading and Reports lines are unchanged and F8.5
still says "standard deviation" with no divisor named. Until that lands, these three constraints
live only here and in the review thread. Flagged, not blocking: **E12.4** needs the σ divisor
decision to be findable by whoever writes it, and that is me.
