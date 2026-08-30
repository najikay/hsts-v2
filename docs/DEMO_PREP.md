# HSTS — demo prep: who is who, what to paste, what to check

Everything a team member needs to run any check without working anything out: the roster with
its relations, ready-to-paste data for every creation action, the requirements one by one with
the account and the clicks, and the multi-member scripts with assignments. Built for the seed
as of 2026-08-30 (581 rows; **Reload demo data** once after pulling that commit).

**Password for every account: `demo123`.**

---

## A. The roster, by subject (who teaches, who coordinates, who sits)

### Mathematics (subject 10) — coordinator `rina.barak` (teaches nothing)
| Course | Teacher | Students (national ID) |
|---|---|---|
| **Algebra 11** | `dana.cohen` | `noa.friedman` 338106727 · `itay.regev` 349251082 · `shira.dahan` 352074611 · `omer.katz` 361489206 · `maya.levi` 374301851 · `yael.azulay` 390745362 · `daniel.shapira` 402186936 · `lior.gabay` 413860529 |
| **Calculus 12** | `dana.cohen` | `itay.regev` 349251082 · `noam.peretz` 385612098 · `yael.azulay` 390745362 · `lior.gabay` 413860529 · `tal.harari` 425097185 · `eitan.solomon` 448521062 |

Exams: `101101` Midterm: Algebra (v1 rejected, v2 approved) · `101102` Quiz: Inequalities (draft) · `101201` Midterm: Calculus (pending, waits for Rina).
Sittings of Midterm: Algebra: `4821` closed and graded (stats frozen) · `2075` **live now** · `3318` closed, **awaiting Dana's grading** (Noa 85, Shira 75, Daniel 60, Itay 45).

### Computer Science (subject 20) — coordinator `michal.sharon` (also teaches Databases)
| Course | Teachers | Students (national ID) |
|---|---|---|
| **Java 21** | `avi.mizrahi` + `tamar.shani` (co-teachers) | `noa.friedman` 338106727 · `itay.regev` 349251082 · `omer.katz` 361489206 · `maya.levi` 374301851 · `noam.peretz` 385612098 · `daniel.shapira` 402186936 · `roni.malka` 436712400 · `eitan.solomon` 448521062 |
| **Databases 22** | `michal.sharon` | `shira.dahan` 352074611 · `omer.katz` 361489206 · `maya.levi` 374301851 · `yael.azulay` 390745362 · `tal.harari` 425097185 · `roni.malka` 436712400 · `eitan.solomon` 448521062 |

Exams: `202101` Java Fundamentals Exam (approved, Avi) · `202102` Collections Quiz (rejected, Tamar) · `202201` Databases Final (approved, Michal).
Sittings: `7390` Java, closed, **awaiting Avi's grading** · `6120` Java, closed and graded · `5164` Databases Final, **scheduled for later today**.

### Biology (subject 30) — `galit.stern` teaches **and** coordinates
| Course | Students (national ID) |
|---|---|
| **Biology 31** | `noa.friedman` 338106727 · `shira.dahan` 352074611 · `omer.katz` 361489206 · `maya.levi` 374301851 · `lior.gabay` 413860529 · `tal.harari` 425097185 |

Exam `303101` Midterm: Biology (approved) · sitting `7745` closed and graded.

### Chemistry (subject 40) — `orly.navon` teaches and coordinates
**Chemistry 41**: `itay.regev` 349251082 · `noam.peretz` 385612098 · `yael.azulay` 390745362 · `daniel.shapira` 402186936 · `roni.malka` 436712400 · `eitan.solomon` 448521062. Six questions, no exam yet (create one live: Part C, F3.1).

### Physics (subject 50) — `sivan.adler` teaches and coordinates
**Physics 51**: `noa.friedman` 338106727 · `itay.regev` 349251082 · `yael.azulay` 390745362 · `lior.gabay` 413860529 · `tal.harari` 425097185 · `roni.malka` 436712400. Six questions, no exam yet.

**Principal:** `principal.avia` — reads everything, changes nothing.

**Quick picks.** Two students in the same course who are *not* Maya: Algebra → `noa.friedman` + `omer.katz`; Java → `noa.friedman` + `omer.katz` too; Databases → `shira.dahan` + `tal.harari`. A student **not** in Algebra: `noam.peretz`. A student in five courses: `itay.regev`, `yael.azulay`.

---

## B. Paste-ready data

### B1. Questions (stem · answers · correct · topic · difficulty)
| Course | Stem | Answers (correct in bold) | Topic | Difficulty |
|---|---|---|---|---|
| Algebra 11 | `Solve: 4x - 8 = 12` | **`x = 5`** · `x = 4` · `x = 3` · `x = 20` | `Linear equations` | Easy |
| Algebra 11 | `What is the vertex of y = (x + 2)² - 3?` | **`(-2, -3)`** · `(2, -3)` · `(-2, 3)` · `(2, 3)` | `Quadratic functions` | Medium |
| Algebra 11 | `For which x is (x - 3)/(x + 1) < 0?` | **`-1 < x < 3`** · `x < -1` · `x > 3` · `x < 3` | `Inequalities` | Hard |
| Calculus 12 | `What is the derivative of x³?` | **`3x²`** · `x²` · `3x` · `x³/3` | `Derivatives` | Easy |
| Java 21 | `Which keyword declares a constant in Java?` | **`final`** · `const` · `static` · `immutable` | `OOP Basics` | Easy |
| Databases 22 | `Which clause filters grouped rows?` | **`HAVING`** · `WHERE` · `GROUP BY` · `ORDER BY` | `SQL` | Medium |
| Chemistry 41 | `What is the atomic number of carbon?` | **`6`** · `12` · `8` · `14` | `Atoms` | Easy |
| Physics 51 | `What is the SI unit of force?` | **`newton`** · `joule` · `watt` · `pascal` | `Mechanics` | Easy |

Refusal checks (F2.1, C-8): two identical answers → refused; no correct marked → refused; empty stem → refused.

### B2. Exams
| Name | Course | Duration | Student text | Teacher text | Questions and points |
|---|---|---|---|---|---|
| `Demo Quick Check` | Algebra 11 | **2** | `Two minutes. Answer what you can.` | `Demo timing check.` | any 3 bank questions, 40/30/30 |
| `Chemistry Basics` | Chemistry 41 | 20 | `Answer all questions.` | `First Chemistry exam.` | any 4, 25 each |
| `Sum check` | any | 10 | — | — | 3 questions 40/30/**20** → blocked at 90; fix to 30 |

Auto-compose (F3.3): topic `Inequalities` · Hard · `20` → shortfall report, nothing created; `Linear equations` · Easy · `2` → composes.

### B3. Releases
| Exam | Code | Opens | Closes | Purpose |
|---|---|---|---|---|
| Demo Quick Check v2 (after approval) | `DQ2X` | now | now + 8 min | the timed sitting |
| Demo Quick Check again | `DQ2Y` | now | now + 10 min | close-early sitting |
| Midterm: Algebra v2 | `SCHD` | now + 20 min | now + 40 min | scheduled → RELEASE_OPENING_SOON → Cancel release |

Refusals (F5.2, F5.3): closes before opens · opens > 5 min in the past · window < 1 min · code `R3Q` (3 chars) · code `R3Q!`.

### B4. Grading
Change score: score `40`, reason `Method credit for the working shown`, comment `Well tried under time pressure`. Refusals: empty reason · score `101`.

### B5. Bot
Text source to paste: `A LEFT JOIN returns every row of the left table and the matching rows of the right table; where there is no match the right side is NULL.`
Questions: in scope `What does a LEFT JOIN return when there is no match?` · out of scope `Who won the 2022 World Cup?` · too fast: three questions within seconds.

### B6. Coordinator
Send back reason: `Add one more question before approval.` Approve: no text needed.

---

## C. Requirements, one by one (account · clicks · paste · pass)

| Req | Account | Do | Pass when |
|---|---|---|---|
| F1.1 | any | wrong password ×5, then right one | generic sentence; lockout sentence; works after 30 s |
| F1.2 | `dana.cohen` / `rina.barak` / `michal.sharon` / `principal.avia` | sign in | teacher rail; Rina: Dashboard, Question Bank, Approvals, Settings; Michal: full + Approvals; principal: Dashboard, Data, Reports, Settings |
| F1.3 | `dana.cohen` ×2 clients | second sign-in | refused; sign out first → works |
| F1.4 | `avi.mizrahi` + `tamar.shani` | Avi edits 21003, signs out | Tamar's badge clears |
| F1.5 | any | connect screen | discovery picker; change server; Back to the server list |
| F2.1, F2.2, C-7, C-8, S-8 | `dana.cohen` | Question Bank → Add question → B1 row 1 | five-digit id `11xxx` allocated, read-only |
| F2.3, C-2 | `dana.cohen` | Edit 11003, Save as a new version | Version history v2 |
| F2.4 | `dana.cohen` | filters course/topic/difficulty/search, sort | list narrows; Clear filters |
| F2.5 | `dana.cohen` | Delete 11005; Delete 11004 | blocked naming exams; deleted |
| F2.6, F10.0–F10.2 | `avi.mizrahi` + `tamar.shani` | Avi edits 21003 | Tamar: badge, read-only banner |
| F3.1, S-11, S-12 | `dana.cohen` | New exam → Algebra 11 → B2 `Demo Quick Check` | Create; sum ≠ 100 blocks; course shown in header; returns to list |
| F3.2 | `dana.cohen` | Add from the bank, Move up/down, Remove | order kept |
| F3.3 | `dana.cohen` | Compose automatically → B2 | shortfall report; then composes |
| F3.4, S-10 | `dana.cohen` | Create exam | six-digit id `1011xx` |
| F3.5 | `dana.cohen` | Edit Midterm: Algebra v2 | new version, v2 kept |
| F3.6 | `dana.cohen` | Exams | chips DRAFT / PENDING / APPROVED / REJECTED, reason on card |
| F4.1 | `rina.barak` | Approvals → open | student-identical paper, Teacher only, Answer key |
| F4.2 | `rina.barak` | Send back with B6 reason; Approve | reason on Dana's card; chip flips live |
| F4.3 | `michal.sharon` | own exam → Approvals | "You wrote this one", approve allowed |
| F5.1 | `dana.cohen` | Release an exam | only approved versions offered |
| F5.2, F5.3, C-1, S-16 | `dana.cohen` | B3 refusals, then `DQ2X` | complaints; 4-char code accepted |
| F5.4, S-2 | `dana.cohen` | Releases | Scheduled / Live / Closed chips, counters |
| F5.5 | `dana.cohen` | Cancel `SCHD`; Close early `DQ2Y` | row gone; students' Time is up |
| F6.1, S-18 | `maya.levi` | code → Confirm → ID 374301851 | paper; Noam's ID refused |
| F6.2 | `maya.levi` | sit | countdown server-anchored, amber at 25 %, red at 5 min |
| F6.3 | `maya.levi` | answer, change | Saving → All changes saved |
| F6.4, F6.5, S-19 | `maya.levi` | let it expire | Time is up takeover, one button, minutes recorded |
| F6.7 | `omer.katz` | re-enter code | already handed in |
| F6.8, C-4 | `maya.levi` mid-exam | Algebra bot; Databases bot | locked sentence; notice once → answer; Dana's INTEGRITY_ALERT + monitor flag |
| F6.9, F6.10 | `omer.katz` | Hand in | grid dialog; Handed in screen |
| F7.1, S-20 | `dana.cohen` | Add time 2 | students' chips, toast, TIME_EXTENDED |
| F7.1b | `dana.cohen` | student alt-tabs | attention text on the row; nothing on hers |
| F7.2, F7.3, S-21 | `dana.cohen` | Live Monitor | counts derived, frozen at close |
| F8.1 | `dana.cohen` | Grading → `3318` | auto scores 85/75/60/45 |
| F8.2 | `dana.cohen` | Review on a row | marked paper with key; Approve / Change score on it |
| F8.3, S-22, S-23 | `dana.cohen` | Change score → B4 | student sees score + comment, never the reason |
| F8.4, S-24, C-3 | `dana.cohen` → students | Approve selected | GRADE_PUBLISHED; My Grades cards appear live |
| F8.5, S-25, C-5 | `dana.cohen` | Results | mean, median, σ, pass rate, deciles |
| F9.1, S-36 | `maya.levi` | My Grades → Open paper | own paper, print + Exit print view |
| F9.2, S-35 | `dana.cohen` | Results | every exam she wrote, incl. others' sittings |
| F9.3, S-7 | `principal.avia` | Data → open a row | read-only detail, no writing control |
| F9.4, S-37 | `principal.avia` | Reports → By teacher / course / student | three sittings compared (`4821`, `6120`, `7745`) |
| F10.4 | `avi.mizrahi` + `tamar.shani` | edit a bot source | lock banner for the other |
| F11.1, F11.2 | all | bell | every type once (see D6); mark read, Mark all read |
| F12.1, S-30 | `dana.cohen` | Study Bot | one card per course; Manage / Create / Delete |
| F12.2, S-28 | `avi.mizrahi` | Add a file (PDF, Word), Add text → B5 | listed; bad file → sentence |
| F12.3 | `avi.mizrahi` → `tamar.shani` | Edit / Remove | BOT_SOURCE_CHANGED |
| F12.4, S-31 | `avi.mizrahi` → `maya.levi` | toggle off / on | student sees the off sentence, then answers |
| F12.5, F12.7, S-32 | `maya.levi` | B5 questions | answer; refusal; too fast |
| F12.9, F12.10, S-33 | `maya.levi` | Past conversations → Reopen | transcript continues |
| F12.11, S-34 | `avi.mizrahi` | Bot activity | totals, no names |
| F13.1–F13.4, F14.1, F14.2 | server | console, discovery, jars | `MANUAL_TEST_TWO_MACHINES.md` |

---

## D. Multi-member scripts (assign before the meeting)

Roles: **T** = teacher machine (`dana.cohen`), **S1** / **S2** = two student machines, **C** = coordinator (`rina.barak`), **P** = principal. One account per client; a machine may run two clients.

**D1 Approval loop** — T: New exam `Demo Quick Check` → Submit. C: bell → Approvals → Send back `Add one more question before approval.` T: reason on card → Edit → add a question → Submit. C: v2 only, SUPERSEDED bell → Approve. T: APPROVED bell, chip live.

**D2 The timed sitting** — T: Release `DQ2X` (Opens now, Closes + 8). S1 `maya.levi` 374301851 and S2 `omer.katz` 361489206: code → Confirm → ID → answer one each. T: Live Monitor 2/0/0 → S1 alt-tabs 2 s → attention text → **Add time 2** → both chips flash, toast, bell. S2: Hand in → Handed in. S1: waits → Time is up. T: 2/1/1 frozen, GRADING_DUE.

**D3 Close early** — T: Release `DQ2Y`. S1 sits. T: Close early → S1 Time is up.

**D4 Grade → publish** — T: Grading → `DQ2X` → Review S1's row → Change score (B4) → Approve; Select all → Approve selected. S1/S2: GRADE_PUBLISHED, cards with Teacher: Dana Cohen, S1's note. P: Data → Results shows it; Reports include it.

**D5 Bot and C-4** — S1 sits `2075`. S1: Algebra bot → locked, box usable; Databases bot → notice → Continue and notify → answer. T: INTEGRITY_ALERT, monitor flag. T: Close early `2075`.

**D6 Notifications, who receives** — APPROVAL_REQUESTED → C · REJECTED / APPROVED / SUPERSEDED → T / C · RELEASE_OPENING_SOON → S1, S2 (release `SCHD`) · TIME_EXTENDED → S1, S2 · GRADING_DUE → T · GRADE_PUBLISHED → S1, S2 · INTEGRITY_ALERT → T · BOT_SOURCE_CHANGED → `tamar.shani`.

**D7 Co-teachers** — `avi.mizrahi` and `tamar.shani` on two machines: edit 21003 (badge, read-only), sign-out clears; bot source add/edit/remove (bell), toggle, Delete the study bot → refused (conversations exist).

**D8 New subjects** — `galit.stern` (Biology): Results → `7745`; Reports as principal compare Algebra / Java / Biology. `orly.navon` (Chemistry): New exam `Chemistry Basics` (B2) → Submit → her own Approvals → Approve → Release `CHM1` → `noam.peretz` 385612098 sits it.
