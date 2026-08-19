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
| 1 | Login (כניסה למערכת) | 4 | ⬜ |
| 2 | Question bank editing (עריכת מאגר שאלות) | 8 | ⬜ |
| 3 | Exam building (בניית מבחנים) | 9 | ⬜ |
| 4 | Exam approval (אישור מבחן) | 6 | ⬜ |
| 5 | Out of the drawer (הוצאת מבחן מהמגרה) | 6 | ⬜ |
| 6 | Exam execution (ביצוע מבחן) | 10 | ⬜ |
| 7 | Extending exam duration (הארכת משך הבחינה) | 4 | ⬜ |
| 8 | Exam checking (בדיקת מבחנים) | 7 | ⬜ |
| 9 | Viewing an exam grade (צפיה בציון הבחינה) | 5 | ⬜ |
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
| 1.1 | Launch the client. On the connect screen, accept the pre-filled address (or enter host/port). Connect. Sign in as `dana.cohen` / `demo123`. | Connect screen appears **before** login, pre-filled from defaults. Login succeeds. | | ⬜ | |
| 1.2 | Observe the shell after 1.1. | Teacher shell: navigation rail with Dashboard, Question Bank, Exams, Results, Study Bot, Settings. **No** Approvals item. Dashboard greets by name. | | ⬜ | |
| 1.3 | Sign out. Sign in as `rina.barak`, then `maya.levi`, then `principal.avia`. | Each gets a different, role-appropriate menu: `rina.barak` = teacher rail **plus Approvals**; `maya.levi` = Dashboard / Take Exam / My Grades / Study Bot / Settings; `principal.avia` = Dashboard / Data / Reports / Settings with nothing mutating. | | ⬜ | |
| 1.4 | Sign in as `maya.levi` with password `wrong`. Repeat 5 times, then try the correct password. | Each failure shows one generic message that does **not** reveal whether the username exists. After 5 failures the 6th attempt is refused for 30s even with the right password. | | ⬜ | |

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
| 4.6 | As `michal.sharon` (coordinator of subject 20 **and** the only Databases teacher), approve her own exam **202201**. Then inspect the server log. | Allowed — PRD F4.3 decides this explicitly: not required by spec, permitted, **but logged**. Verify the log entry actually exists; "allowed but logged" with no log line is a silent failure. | | ⬜ | |

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
| 8.1 | As `avi.mizrahi`, open results for execution `7390` (closed, awaiting grading). | Every attempt already carries an auto-computed score: per-question points, correct ⇔ the single correct answer (F8.1, C-8). | | ⬜ | |
| 8.2 | As `maya.levi` — before any approval — look for the grade of execution `7390`. | **Not visible.** Auto-checking alone does not publish anything (C-3, S-24). | | ⬜ | |
| 8.3 | As `avi.mizrahi`, change one student's grade without entering a justification. | Refused. A manual change **requires** an explanation (T-8.3, S-23). | | ⬜ | |
| 8.4 | Change it again with a justification, and add a comment to the student. Then inspect the stored record. | Original auto grade, the change, and the reason are all stored — a full audit trail (F8.3). The comment is saved (S-22). | | ⬜ | |
| 8.5 | Approve the grades (try both per-student and bulk). | Both work. Each affected student receives a "your grade is available" notification (F8.4). | | ⬜ | |
| 8.6 | As `maya.levi`, open the grade now. | Visible, together with her checked form: wrong answers marked, teacher comments included (S-24). | | ⬜ | |
| 8.7 | As `maya.levi`, look for the class average, median or distribution. | Not available anywhere to a student (S-26). | | ⬜ | |

---

## 9 — Viewing an exam grade (צפיה בציון הבחינה) · T-9

**PRD:** F9.1 · **Spec:** S-36

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 9.1 | As `maya.levi`, open My Grades. | Lists the exams she took with her grades — including the seeded Algebra Midterm (execution `4821`). | | ⬜ | |
| 9.2 | Open the Algebra Midterm result. | The checked form: her answers, wrong ones marked, the correct answers shown, points per question, teacher comments (T-9.2, S-24). | | ⬜ | |
| 9.3 | Use the export / print action on that result. | She obtains a copy of the checked exam (S-36). | | ⬜ | |
| 9.4 | Try to reach another student's grade — via the UI, and by replaying the request with a different student id. | Refused **server-side**, not just hidden in the UI. A student can never see another's grade (T-9 note, F9.1). | | ⬜ | |
| 9.5 | As `omer.katz` (the seeded TIMED_OUT attempt), open his result. | Grade is present and the attempt is shown as timed out, with his actual solving time in minutes (S-19). | | ⬜ | |

---

## 10 — Viewing exam results (צפיה בתוצאות בחינות) · T-10

**PRD:** F9.2 · **Spec:** S-35

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 10.1 | As `dana.cohen`, open Results. | Lists the exams **she wrote**, including executions run by other teachers (S-35). | | ⬜ | |
| 10.2 | Open execution `4821`. Read the table. | Per-student rows: score, submitted vs timed out, solving time. 8 students, matching the seeded roster. | | ⬜ | |
| 10.3 | Switch to the histogram view. | Score-bucket bars themed to the active palette, with mean, median and ±1σ markers labelled. Stat cards above: average, median, std, min/max, pass rate, participants. Values match the seeded stats — **mean 78.0, median 80.5, σ 13.08**. | | ⬜ | |
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
| 12.4 | Cross-check any figure against the stored statistics for execution `4821`. | The report reads the **stored** per-execution statistics (S-25) rather than recomputing differently. Mean 78.0, median 80.5 — identical to §9.1 of the seed. | | ⬜ | |
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

## Notes for the submission document

- The **Bugs found** column is what Assignment 3 §1 asks for — "any bugs found + which test case
  exposed them". Fill it as testing happens, not retrospectively; a bug with no test case number
  next to it is worth less in the write-up.
- Cases marked ⛔ blocked are features not yet built. Blocked is a legitimate status during
  development and an illegitimate one at submission.
- Several cases deliberately test **server-side** enforcement by replaying a request rather than
  clicking (2.3, 9.4, 11.4, 6.7). Those are the ones that answer "could a student cheat?", which
  is where v1 lost marks. Do not downgrade them to UI checks.
