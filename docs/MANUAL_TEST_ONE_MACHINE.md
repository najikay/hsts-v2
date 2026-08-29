# HSTS — manual test, one machine (everything a person can do, every role, every screen)

This is the complete single-machine test. Its companion, `MANUAL_TEST_TWO_MACHINES.md`, holds
everything that needs a second computer or a real network and is Omar's. Together they replace
`MANUAL_ROUND.md`.

**Six parts.** A: how to run it. B: the **action inventory** — every screen of every role,
every control on it, what it must do (the "every button" list; walk it screen by screen).
C: the **ordered walk** — a story that creates everything from the client (questions, exams,
approvals, releases, sittings, grades, bot sources, notifications) so nothing depends on the
seed beyond the accounts, and no step waits more than the two-minute exam it creates. D: the
**situations catalogue** — every refusal, edge and failure the product has words for, each
provoked once. E: the **requirement coverage map** — every id in the course PDF (as mapped in
`TRACEABILITY.md`) named with the step that exercises it. The PDF is the floor; B and D are the
rest. F: the **interactions between roles**, every pair seen from both sides.

**No reseeding.** The seed is loaded once on a fresh database (Part A). The walk creates its own
data on top and later rounds keep building on it; reseeding is only for wiping a machine before
the demo (`DEMO_DAY.md`).

**Notes.** One line per observation, your words, the screen named, into
`docs/manual-round-N-notes.txt`. No fixing, no re-testing, no severity; paste it over.

| Round | Date | Got to | Stopped because | Notes file |
|---|---|---|---|---|
| 1 | 2026-08-28 | student flow, teacher side, bank, grading | 18 findings, 4 blockers | `manual-round-1-notes.txt` |
| 2 | 2026-08-29 | (v2 document) | 7 notes, 1 blocker (login recovery) | `manual-round-2-notes.txt` |
| 3 | | | | `manual-round-3-notes.txt` |

---

## Part A — Running it

```
git pull
.\mvnw -DskipTests clean package
java -jar target\hsts-server.jar
```
On a **fresh** database only (first run ever, or after `DEMO_DAY.md`'s wipe): press **Load demo
data if missing** on the console once. Never **Reload demo data** during a walk.

Start one client per account you want open at the same time: `java -jar target\hsts-client.jar`
in another terminal (or double-click the jar). One session per account (F1.3); switching
accounts inside a window means Sign out first.

**Accounts (password `demo123`):**

| Username | Role | National ID |
|---|---|---|
| `maya.levi` | student: Algebra 11, Java 21, Databases 22 | `374301851` |
| `noam.peretz` | student: Calculus 12, Java 21 (not Algebra) | `385612098` |
| `omer.katz` | student (Algebra), the seeded TIMED_OUT attempt | `361489206` |
| `noa.friedman`, `itay.regev`, `shira.dahan`, `yael.azulay`, `daniel.shapira`, `lior.gabay`, `tal.harari`, `roni.malka`, `eitan.solomon` | more students, for multi-student sittings | in `UsersSection.java` |
| `dana.cohen` | teacher: Algebra 11, Calculus 12 | — |
| `avi.mizrahi`, `tamar.shani` | Java 21 co-teachers | — |
| `michal.sharon` | teaches Databases 22 **and** coordinates Computer Science 20 | — |
| `rina.barak` | coordinates Mathematics 10, teaches nothing | — |
| `principal.avia` | principal, read-only | — |

Seeded facts the walk leans on: executions `2075` (live, Algebra Midterm), `5164` (scheduled),
`4821`/`7390` (closed); Algebra questions **11003, 11004, 11006, 11008 are in no exam** (deletable),
the rest are referenced; Calculus exam `101201` is PENDING for Rina.

---

## Part B — The action inventory (every screen, every control)

Walk each screen top to bottom and press everything. "Expected" is what the product promises;
anything else is a note. Requirement ids in brackets.

### B0. Server console (window on the server)
| Control | Expected |
|---|---|
| Address line + candidate picker | your LAN IPv4; other adapters listed; picking one changes the address shown [F13.2] |
| Fingerprint line | present, stable across restarts [F13.3] |
| DB status | green; red with a sentence if MySQL is down [F13.1] |
| Connected clients | counts live as clients connect and quit [F13.1] |
| Log tail: Pause / Copy / Clear | pause stops scrolling (buffer keeps filling), copy puts the tail on the clipboard, clear empties the pane [F13.1] |
| Load demo data if missing | UNCHANGED on a seeded DB; inserts on an empty one [NFR-17, F14.2] |
| Reload demo data | asks first; Cancel does nothing [NFR-17] |
| Health panel | listener up, DB up, uptime [F13.1] |

### B1. Before sign-in (any client)
| Screen / control | Expected |
|---|---|
| Connect: discovery | finds the server in ~2 s; picker shows name, address, fingerprint; pinned server auto-connects next time [F13.4, F1.5] |
| Connect: change server / manual form | address + port fields, Connect enabled only when valid; wrong address → a sentence, no class name; **Back to the server list**; **Look for servers again** [F1.5, U-4, U-5] |
| Login: username + password + Sign in | wrong password → one generic sentence; 5 failures → 30 s lockout sentence even with the right password [F1.1, S-38] |
| Login: status row | Connected to &lt;server&gt; · change server; when the socket drops: Disconnected, "Not connected", Sign in disabled, **Reconnect** [U-6, U-20] |
| Login: Reconnect | leads to the connect screen; a successful reconnect returns to Login enabled [U-17: write the exact clicks if it does not] |
| Login: theme | the profile/theme controls, if shown pre-login, follow the saved preference |

### B2. Shell (every signed-in role)
| Control | Expected |
|---|---|
| Rail items | exactly the role's set (below); collapse/expand with tooltips when collapsed [F1.2] |
| Breadcrumbs | parent · current on drill-ins [U-8] |
| Navbar Back | on every non-rail screen; goes back in history, else to the parent, else home; absent on rail screens [U-8] |
| Bell + badge | badge = unread count at sign-in; panel lists rows with icon and relative time; click-through; mark one read; **Mark all read**; "Nothing yet" when empty [F11.2] |
| Toasts | transient, dismiss on their own, never block [F11.3] |
| Profile menu | Light / Dark / System radios (System says what it resolved to); **Sign out** [F1.4] |
| Reconnect banner | on a socket drop mid-session: banner in words with Retry; disappears on reconnect |
| Window resize | three sizes on every screen; nothing overlaps or clips [NFR-21] |

Rails: **student** Dashboard, Take Exam, My Grades, Study Bot, Settings · **teacher** Dashboard,
Question Bank, Exams, Releases, Live Monitor, Grading, Results, Study Bot, Settings ·
**coordinator** teacher's + Approvals · **principal** Dashboard, Data, Reports, Settings.

### B3. Student screens
**Dashboard** — "Your courses" cards (each names its bot) · "Take an exam" code card: Enter with an empty/short/long code → "Codes are 4 letters or digits."; a valid code → Take Exam in confirmation mode · next-exam hint when a release is scheduled [F6.1, U-10].
**Take Exam** — code step: field, hint, Continue (enabled only on 4 alphanumerics), **Back to my dashboard** [U-19] · confirmation mood: read-only code, "Confirm your exam", "Confirm and continue", **Use a different code** [U-10] · identity step: exam summary (name, minutes, questions, window note when the window cuts the sitting short, B-14), ID field, Start exam, **Back** to the code step [U-19] · handed-in dead end: sentence + Back to my dashboard [F6.7] · paper: header (name, course, instructions), countdown chip (amber at 25 %, red at 5 min), progress bar + "Answered n of m", save indicator (All changes saved / Saving / Not saved yet, retrying), question chips (answered state, jump), question card (stem, image, four options, one selectable), Previous / Next, Hand in → dialog with the answer grid (chips jump and close the dialog), remaining time, unanswered note, **Keep working** / **Hand in** [F6.2, F6.3, F6.9] · Submitted screen: handed-in time, minutes, summary, single **Back to my dashboard** [F6.10] · Time is up takeover: no confirmation, same summary, single button [F6.4].
**My Grades** — hero: term average ring (fill centred on the track, ends where the number says), count line, next exam · cards: course, Passed / Below the pass mark chip, exam name, **Teacher: name** [U-18], score, approved date, teacher's note [U-18], "Reviewed by your teacher" when adjusted, **Open paper →** · empty slot card when nothing is graded [F9.1, F8.4].
**Checked exam** (drill-in) — title, Teacher: name, attempt status + minutes, "Your teacher's note", per question: your answer / correct answer / Correct · Wrong · Not answered, points; print layout toggle → **Exit print view**; navbar Back [F9.1, S-36, U-8].
**Study Bot** — Course picker (enrolled courses only), header names the course, ask box + Send (empty refused), "The bot is thinking" indicator, bubbles, out-of-scope sentence, too-fast sentence, inactive sentence, not-enrolled sentence, locked sentence with the box still usable (B-47), integrity notice **Continue and notify** / **Not now** once per attempt, **Past conversations**, **New conversation** [F12.4, F12.5, F12.7, C-4].
**Bot history** — list with times, **Reopen** → the transcript continues in the chat; navbar Back [F12.10].
**Settings** — Light / Dark / System, accent palettes, **Reset to defaults**; every open window follows instantly.

### B4. Teacher screens
**Dashboard** — Sittings in progress (code, closes, minutes left, submitted count, "More students are in the monitor") · Today and next · Scheduled ahead · Awaiting grading · Class average (Passed / "Marking is not finished yet") · Your exams; every card's link opens the right screen.
**Question Bank** — course picker (taught courses only), topic, difficulty, search, **Clear filters**, count label, every column sortable, **Editing** column badges another teacher's editor live · detail pane: stem, answers with Correct, topic, difficulty, version, illustration (Loading → image / "No illustration"), **Version history** / **Hide history**, **Edit**, **Delete question** (blocked dialog names the exams; otherwise confirm Delete / Keep it) · **Add question** [F2.1–F2.6, F10.0].
**Question editor** (drill-in) — Question text, four answers with the correct radio, Topic (free text, suggestions), Difficulty, Illustration: **Choose image** / remove, **Add question** / **Save as a new version**, **Cancel** → "Leave without saving?" Discard them / Keep editing, "Unsaved changes" marker, lock banner when someone else holds it, "Somebody else edited this question" → Close the editor (stale), "That question is no longer there"; navbar Back [F2.1, F2.3, F10.2, F10.3].
**Exams** — rows with version chips DRAFT / PENDING_APPROVAL / APPROVED / REJECTED, click selects (no "Open" hint), versions panel with the rejection reason on the card, **Edit** on a version, **New exam** menu of taught courses (disabled with the reason for a coordinator who teaches nothing) [F3.5, F3.6, F4.2].
**Exam builder** (drill-in) — Exam details: name, "How long students get" (1..480), Student instructions / Teacher notes tabs · Questions on this paper: per-row Points, Move up / Move down / Remove, "The bank has a newer version" badge with **Use the newer version** · **Choose questions** tab: **Add from the bank** picker (search by id/text/topic, Add, "Already on this exam", Done) · **Compose automatically** tab: rows "Anywhere in this course" / **Add a topic**, counts by Easy/Medium/Hard/Any, **Compose the exam** (infeasible → the shortfall report, nothing created) · sum-to-100 indicator, **Create exam** / **Save draft**, "Saved.", Submit for approval, lock banner when another author holds it; navbar Back [F3.1–F3.4, C-2].
**Releases** — rows with Scheduled / Live / Closed chips and live counters, per-row **Monitor**, **Cancel release** (scheduled only) → Cancel it / Keep it, **Close early** (live only) → Close it now / Keep it running · **Release an exam** dialog: Approved exam picker, Exam code (Generate for me / typed, 4 alphanumerics), Opens / Closes, complaint line, Release it / Cancel → "Read this code out" with **Copy code** / Done [F5.1–F5.5].
**Live Monitor** — sitting chooser (or "Pick a sitting to watch" → Open Releases), counts Started / Handed in / Timed out, per-student rows with status and remaining time, "Her exam window last lost focus at …" attention count, "Opened another course's study bot at …" flag, **Add time** spinner (1..480, default 15) + button [F7.1, F7.1b, F7.2].
**Grading** — "Waiting for you" list, execution header with progress and closed time, table Student / Auto / Score / State / adjusted marker, Select all, **Approve selected**, **Change score…** dialog (score 0..100, "Reason for the record" required, "Comment the student will read") [F8.1–F8.3].
**Results** — exam rail (exams she wrote, including others' executions), execution picker, histogram / table toggle, stat cards (mean, median, σ, min, max, pass rate ≥ 55, deciles), table with attempt status and minutes, print layout → Exit print view; empty states "Not run yet" / "Nobody sat this one" / "Nothing marked yet" / "Grading is not finished" [F8.5, F9.2].
**Study Bot (manager)** — course picker (taught courses), "This course has no study bot" + **Create the study bot** (name dialog) when none, otherwise: bot name, **Students can use this bot** toggle, Information sources list with kind and holder badge, **Add a file** (PDF / Word; "Reading that file on the server"; a bad file → a sentence), **Add text**, per source **Edit** (text only; lock banner if held) / **Remove** → Remove it / Keep it, **Bot activity** link [F12.1–F12.4, F10.4].
**Bot activity** (drill-in) — Questions asked, Busiest day, Asked most often, Questions over the last 30 days, "Nobody has used this bot yet"; no student named anywhere; navbar Back [F12.11].

### B5. Coordinator screens (in addition to the teacher's)
**Dashboard** — "Waiting for you" approvals card, "Teachers submitting".
**Approvals** — queue for her subject (or "You do not coordinate a subject"), "You wrote this one" badge, open → **Exam preview**: banner, the paper as a student sees it, "Teacher only" panel, "Answer key" panel, metadata, **Approve** → "Approve this exam?" Approve / Keep looking, **Send back** → reason required → Send back / Keep looking, self-approval note when it is hers; footer Back to approvals; navbar Back [F4.1–F4.3].

### B6. Principal screens
**Dashboard** — school-wide read-only cards.
**Data** — segments Questions / Exams / Results, filter "Filter by name, code or course", course picker "All courses", "too many questions" hint on wide filters, tables paginate; **no** control that writes [F9.3, S-7].
**Reports** — subject picker, By teacher / By course / By student, stat cards, "Closed sittings" table, the median-band hint, print layout + exit [F9.4, S-37].

---

## Part C — The ordered walk (creates everything from the client)

Time ≈ 3 h 30 m. The only wait is the 2-minute exam. Steps are numbered for the coverage map.

### C1. Console and connection (10 min)
1. Server from a terminal; console items per B0. *(F13.1, F13.2, F13.3, F14.1)*
2. Client: discovery → Login; change server → manual form → wrong address → sentence; Back to the server list; Look for servers again; back on Login. *(F13.4, F1.5)*
3. Wrong password ×5, right password refused, 30 s, works. *(F1.1)*
4. Stop the server → Login shows Disconnected, Sign in disabled, Reconnect. Start the server. **Reconnect** → connect screen → Login enabled → sign in works in the **same window**. If not, write every click in order (U-17). *(U-6, U-17)*

### C2. Dana authors (35 min) — `dana.cohen`, window 1
5. Dashboard cards per B4; every link and Back. *(F1.2, NFR-21)*
6. Question Bank: every filter, sort, Clear filters; detail of 11005 with image and history (v1/v2). *(F2.4, F2.3)*
7. **Create four questions** in Algebra 11, topic `Round 3` (a new topic typed in): two EASY, one MEDIUM with an image, one HARD; refusals on the way: empty stem, duplicate answers, no correct marked. Ids allocated `110xx`, read-only. *(F2.1, F2.2, C-7, C-8, S-8, S-13)*
8. Edit one of them (stem + swap two answers) → Save as a new version → history v2. *(F2.3, C-2)*
9. Delete 11005 → blocked naming the exams; delete 11004 → gone. *(F2.5)*
10. Exams: chips per B4; Algebra Midterm v1's rejection reason on its card. *(F3.6, F4.2)*
11. **New exam** → Algebra 11 → `Round 3 Quick Check`: duration `2`, both texts; manual: add your four questions, points 40/20/20/**10** → sum 90 blocks; 40/20/20/20 → allowed; Move up/down; Remove one and re-add it; the "newer version" badge on the edited question → Use the newer version. *(F3.1, F3.2, S-11, S-12, C-2)*
12. Compose automatically: 20 HARD `Round 3` → shortfall report, nothing created; 2 EASY `Round 3` → composes; discard, keep the manual paper. *(F3.3)*
13. Save draft → id `1011xx`; Submit for approval → PENDING. *(F3.4, S-10, S-14)*
14. Duration refusals: `0` and `481` refused; empty name refused; a paper with no questions cannot be submitted. *(F3.1)*
15. Edit Algebra Midterm v2 → the builder makes v3; save draft; leave it. *(F3.5)*

### C3. Rina decides (12 min) — `rina.barak`, window 2
16. Bell: APPROVAL_REQUESTED for Round 3 Quick Check; dashboard "Waiting for you". *(F11.1)*
17. Approvals: queue lists it and `101201`; open Round 3: student view, Teacher only, Answer key. *(F4.1)*
18. Send back with an empty reason → refused; `Swap Q3 and Q4.` → sent back. Dana's window: APPROVAL_REJECTED push; reason on the card. *(F4.2)*
19. Dana: edit → v2 (Move Q4 above Q3), Submit. Rina: the queue shows v2 only; her bell has APPROVAL_SUPERSEDED for v1. Approve v2; approve `101201`. Dana's bell: APPROVAL_APPROVED; the chip flipped live. *(F4.2, F3.6, NFR-18)*

### C4. Dana releases (10 min) — window 1
20. Releases per B4; **Release an exam**: picker offers only approved versions. *(F5.1)*
21. Window refusals: closes before opens; opens > 5 min in the past; window under a minute. Code refusals: 3 chars, 5 chars, symbols. Generate for me → 4 chars. *(F5.2, F5.3, C-1)*
22. Release Round 3 v2, code `R3QC`, Opens now, Closes now + 6 min → Read this code out → Copy code → Done → row Live. *(F5.3, S-17)*
23. Release Algebra Midterm v2 again, Opens now + 20 min → Scheduled; students' bells: RELEASE_OPENING_SOON (within the 30-min horizon); **Cancel release** → gone; a live row has no Cancel, only Close early. *(F5.5, S-2, F11.1)*

### C5. Three students sit it (12 min) — windows 2, 3, 4
24. `maya.levi`: dashboard card `R3QC` → confirmation → Back to my dashboard → card again → Confirm and continue → identity: **Back** returns to the code step, then forward again; Noam's ID refused; own ID → the paper; **answer one**; do not submit. *(F6.1, F6.2, U-19, S-18)*
25. `omer.katz` (`361489206`): same code; answer all; Hand in → dialog: Keep working once, then Hand in → Submitted screen. Re-enter `R3QC` → "already handed in". *(F6.9, F6.10, F6.7)*
26. `noam.peretz`: `R3QC` → not enrolled. *(S-5, S-15)*
27. Dana: Live Monitor → Round 3: rows Maya Started / Omer Handed in, counts 2/1/0; alt-tab away from Maya's window 2 s → attention text on her row, nothing on hers; **Add time 1** → Maya's Time Extended moment + TIME_EXTENDED in her bell. *(F7.2, F7.1b, F7.1, S-20)*
28. Wait: amber, red, **Time is up** on Maya's window; monitor row Timed out; counts frozen 2/1/1; Releases row Closed when the window ends; Dana's bell GRADING_DUE. *(F6.4, F6.5, F7.3, S-21, F11.1)*

### C6. Grade and publish (15 min)
29. Dana: Grading → Round 3: two rows with auto scores (rows render, never a skeleton). Change score on Maya: empty reason refused, score 101 refused; `Method credit` + comment `Well tried`, 40 → adjusted marker. Select all → Approve selected. *(F8.1, F8.2, F8.3, S-22, S-23, C-3)*
30. Maya's and Omer's windows: GRADE_PUBLISHED without a refresh; My Grades cards show **Teacher: Dana Cohen** and the note; Open paper → the checked exam with per-question marks; print → Exit print view; the justification appears nowhere. *(F8.4, F9.1, S-24, S-36, U-18)*
31. Dana: Results → Round 3: histogram/table, stat cards; dashboard Class average updated; Results also lists `4821`'s omer TIMED_OUT 75 min. *(F8.5, F9.2, S-25, S-35, C-5)*
32. Dana: Grading → `2075` too (later, after C8 closes it).

### C7. Second teacher, locks, bot manager (20 min) — `avi.mizrahi` w1, `tamar.shani` w2
33. Avi edits 21003 → Tamar's bank badges "Editing … Avi Mizrahi" live; her open is read-only; Avi closes → clears; Avi reopens and **signs out** → clears. *(F2.6, F10.0–F10.2, F1.4)*
34. Avi: Study Bot manager, Java 21: **Add text** (a paragraph), **Add a file** PDF, Word, and a renamed garbage `.pdf` → sentence; Edit the text source (Tamar opening it meanwhile → lock banner); Remove one; Tamar's bell: BOT_SOURCE_CHANGED per change. Toggle **Students can use this bot** off. *(F12.1–F12.4, F10.4, S-28, S-29)*
35. Bot activity: no student name anywhere. Dana's manager shows two bots (Algebra, Calculus), Create only where none exists. **If a second bot for one course can be made, write the clicks (U-14).** *(F12.11, S-30, S-6)*

### C8. Maya and the bot, C-4, close early (18 min) — lead on the call for the live keys
36. Maya: Java bot → inactive sentence; Avi flips it on → same screen answers. *(F12.4, S-31)*
37. Databases bot: a course question → thinking indicator → answer; off-topic → refusal; empty → refused; three fast → too fast; history → Reopen → continue. *(F12.5, F12.7, F12.9, F12.10, S-32, S-33)*
38. Maya sits `2075`; Algebra bot → locked, box usable; Databases bot → integrity notice once → Continue and notify → answered; Dana's bell INTEGRITY_ALERT; monitor row flagged. *(F6.8, C-4)*
39. Dana: Releases → `2075` → **Close early** → Maya's Time Up; counts frozen; grading queue gains it; approve its grades; Maya's bell. Maya's Algebra bot answers again (B-47). *(F5.5, F6.4, F8.4)*

### C9. Dual hat and the principal (18 min)
40. `michal.sharon`: rail has Study Bot and Approvals; New exam → Databases 22 `Self check` (5 min, three questions), submit; Approvals shows it with "You wrote this one"; Approve → allowed, note shown. *(F4.3, F1.2)*
41. `principal.avia`: Data segments + filters, no writing control; Reports: each dimension for each subject; Round 3's numbers match step 31; print + exit. *(F9.3, F9.4, S-7, S-26, C-5)*

### C10. Shell, settings, sessions (10 min)
42. Settings: modes, palettes, Reset; profile menu; rail collapse; breadcrumbs; resize on three screens. *(NFR-21)*
43. F1.3: second client as `dana.cohen` refused; sign the first out → succeeds. *(F1.3, NFR-16)*
44. Every failure today was a sentence; every wait showed progress; no refresh button anywhere. *(NFR-18, NFR-21, S-44)*

---

## Part D — Situations catalogue (each provoked once)

| Area | Situation | Expected |
|---|---|---|
| Login | wrong password / locked out / server down / dead-socket recovery / duplicate session | generic sentence · lockout sentence · disabled with Reconnect · recovers in place · refused |
| Codes | malformed, unknown, not open yet (`5164`), closed (`4821`), not enrolled, already handed in | the six sentences, each on the code field or the identity field as appropriate |
| Identity | empty ID, another student's ID | required sentence · mismatch sentence, no attempt started |
| Paper | change an answer repeatedly; reconnect banner (server restart mid-attempt); autosave failure indicator | one write per question after the pause; banner then resume with answers; "Not saved yet, retrying" |
| Bank | duplicate answers, no correct, empty stem, delete referenced, delete free, edit while locked, stale save | refusal sentences · blocked dialog naming exams · soft delete · read-only banner · "Somebody else edited" |
| Builder | Σ ≠ 100, duration 0 / 481, empty name, no questions, infeasible auto-compose, edit an approved version | blocked · refused · refused · cannot submit · shortfall report, nothing created · new version |
| Approvals | reject without reason; approve own (dual hat); resubmit while pending | refused · allowed with note · superseded notice |
| Releases | window rules, code rules, cancel scheduled, close live, cancel live (absent), monitor a closed one | complaints · 4 alphanumerics · gone · Time Up for students · no such control · frozen counts |
| Monitor | extend by 1 and by 480, attention, C-4 flag | students' clocks grow; counts stay derived |
| Grading | score 101 / -1, empty reason, approve twice | refused · refused · idempotent |
| Bot | empty, too long, too fast, inactive, not enrolled, locked, cross-course notice, provider down (unplug internet) | sentences; provider down → the friendly S-32 sentence, never a stack trace |
| Notifications | all ten types arrive live: APPROVAL_REQUESTED / APPROVED / REJECTED / SUPERSEDED, GRADE_PUBLISHED, TIME_EXTENDED, BOT_SOURCE_CHANGED, RELEASE_OPENING_SOON, INTEGRITY_ALERT, GRADING_DUE | each seen once in Part C |
| Print | results, checked exam, reports | chrome gone, Exit print view present |
| Theme | light / dark / system, each palette | every window follows |
| Sessions | sign out with an editor open; close the window with an attempt open | locks released; the attempt continues on the server and resumes on re-entry |

---

## Part F — Interactions between roles (every pair, seen from both sides)

Every row is one person doing something and **another role seeing the effect without a
refresh**, in two windows open at the same time. Part C exercises each once; this table is the
checklist that says who watches what, so none is assumed. Tick the row only when the consumer's
window changed **by itself**.

| # | Producer (window 1) does | Consumer (window 2) must see, live | Where | Step |
|---|---|---|---|---|
| F1 | Teacher submits an exam for approval | Coordinator: bell APPROVAL_REQUESTED, dashboard "Waiting for you" +1, queue row | Approvals | 16–17 |
| F2 | Coordinator sends it back with a reason | Author: bell APPROVAL_REJECTED, the reason on the version card, chip REJECTED | Exams | 18 |
| F3 | Author resubmits a new version | Coordinator: queue shows only the new version, bell APPROVAL_SUPERSEDED | Approvals | 19 |
| F4 | Coordinator approves | Author: bell APPROVAL_APPROVED, chip APPROVED, Releases now offers it | Exams, Releases | 19–20 |
| F5 | Teacher schedules a release ≤ 30 min ahead | Every enrolled student: bell RELEASE_OPENING_SOON, dashboard next-exam hint | Student dashboard | 23 |
| F6 | Teacher hands out a code (orally) | Student enters it; the code is nowhere on any student screen | Take Exam | 22, 24 |
| F7 | Student enters her ID | Teacher's monitor: the row appears Started with a countdown; dashboard "Sittings in progress" counts her | Live Monitor, dashboard | 24, 27 |
| F8 | Student saves answers | Monitor: her row stays live; on a reconnect the answers are there | Live Monitor | 24 |
| F9 | Teacher adds time | Student: chip flash, "+n:00", toast naming the teacher, bell TIME_EXTENDED; the monitor's close time agrees | both | 27 |
| F10 | Student alt-tabs away 2 s | Monitor: attention text and count on her row; **nothing** on hers | Live Monitor | 27 |
| F11 | Student opens another course's bot mid-exam and continues | Teacher: bell INTEGRITY_ALERT naming her; monitor row flag "Opened another course's study bot at …" | both | 38 |
| F12 | Student hands in | Monitor: row Handed in, counts move; dashboard "Submitted" count | Live Monitor | 25 |
| F13 | Time runs out for a student | Student: Time Up takeover; monitor row Timed out; counts frozen; teacher's bell GRADING_DUE | both | 28 |
| F14 | Teacher closes a sitting early | Every live student: Time Up; Releases row Closed; grading queue gains it | both | 39 |
| F15 | Teacher approves grades | Student: bell GRADE_PUBLISHED, My Grades gains the card (teacher's name + note), checked exam openable; **before** approval the student sees nothing | My Grades | 29–30 |
| F16 | Teacher overrides a score with a justification | Student sees the new score, the "Reviewed by your teacher" marker and the comment; **never** the justification | My Grades | 29–30 |
| F17 | Teacher A opens a question editor | Teacher B (same course): "Editing … A" badge on the row, read-only on open; clears when A closes, saves, signs out or drops | Question Bank | 33 |
| F18 | Teacher A edits an exam version | Teacher B / coordinator opening it: lock banner, read-only | Exam builder / preview | 15 (+ two windows) |
| F19 | Teacher A changes a bot source | Co-teacher B: bell BOT_SOURCE_CHANGED per change; the list updates | Bot manager | 34 |
| F20 | Teacher A holds a source editor | Co-teacher B opening it: lock banner | Bot manager | 34 |
| F21 | Teacher toggles the bot inactive / active | Student: the inactive sentence, then answers again on the same screen | Study Bot | 36 |
| F22 | Teacher creates a bot for a course that has none | A second teacher of that course sees the same bot, not a Create button | Bot manager | 35 |
| F23 | Students use the bot | Teacher's Bot activity: totals and frequent questions rise; no names | Bot activity | 35, 37 |
| F24 | Coordinator who also teaches submits her own exam | Her own queue shows it badged "You wrote this one"; approval allowed and noted | Approvals | 40 |
| F25 | Teacher marks a sitting | Principal's Data → Results and Reports read the stored statistics; nothing on the principal's screens can change them | Data, Reports | 41 |
| F26 | Any user signs in twice | The second sign-in is refused until the first signs out or drops | Login | 43 |
| F27 | Any user signs out holding a lock or mid-attempt | The lock clears for others; the attempt continues on the server and resumes on re-entry | bank / Take Exam | 33, D |
| F28 | Another teacher runs an execution of an exam Dana wrote | Dana's Results lists that sitting too (she wrote it); the executing teacher grades it | Results, Grading | 31 (Algebra Midterm `4821` / `2075`) |

Run F17–F20 with the two windows side by side and watch the badge appear and clear; run F7–F14
with the student's window and the monitor side by side. A row that needed a refresh, a
re-navigation or a sign-out/in to show its effect is a note (NFR-18).

---

## Part E — Requirement coverage map (id → step)

F1.1 3 · F1.2 5, 40 · F1.3 43 · F1.4 33 · F1.5 2, 4 · F2.1 7 · F2.2 7 · F2.3 6, 8 · F2.4 6 ·
F2.5 9 · F2.6 33 · F3.1 11, 14, 40 · F3.2 11 · F3.3 12 · F3.4 13 · F3.5 15 · F3.6 10, 19 ·
F4.1 17 · F4.2 18–19 · F4.3 40 · F5.1 20 · F5.2 21 · F5.3 21–22 · F5.4 20, 28 · F5.5 23, 39 ·
F6.1 24, 26 · F6.2 24, 28 · F6.3 24 (+ two-machine 6) · F6.4 28, 39 · F6.5 28 · F6.6 structural
(`CorrectnessLeakGuardTest`) · F6.7 25 · F6.8 38 · F6.9 25 · F6.10 25 · F7.1 27 · F7.1b 27 ·
F7.2 27 · F7.3 28, 39 · F8.1 29 · F8.2 29 · F8.3 29 · F8.4 30 · F8.5 31 · F9.1 30 · F9.2 31 ·
F9.3 41 · F9.4 41 · F10.0 33 · F10.1 33 · F10.2 33 · F10.3 structural (server-tested) · F10.4 34 ·
F11.1 16, 18, 19, 23, 27, 28, 38 · F11.2 B2 · F11.3 27 · F12.1 34–35 · F12.2 34 · F12.3 34 ·
F12.4 34, 36 · F12.5 37 · F12.6 (keys: server.properties only) · F12.7 37 · F12.8 structural
(`BotIsolationGuardTest`) · F12.9 37 · F12.10 37 · F12.11 35 · F13.1 1 · F13.2 1 · F13.3 1 ·
F13.4 2 · F14.1 1 · F14.2 Part A · S-1..S-4 seed · S-5 26 · S-6 35 · S-7 41 · S-8 7 · S-10 13 ·
S-11 11 · S-12 11 · S-13 7 · S-14 13 · S-15 26, D · S-16 21 · S-17 22 · S-18 24 · S-19 28 ·
S-20 27 · S-21 28 · S-22 29 · S-23 29–30 · S-24 30 · S-25 31 · S-26 41 · S-27..S-29 34 · S-30 35 ·
S-31 36 · S-32 37 · S-33 37 · S-34 35 · S-35 31 · S-36 30 · S-37 41 · S-38 3 · S-44 44 ·
C-1 21 · C-2 8, 11, 15 · C-3 29 · C-4 38 · C-5 31, 41 · C-7/C-8 7 · NFR-16 43 · NFR-17 Part A ·
NFR-18 19, 30 · NFR-21 5, 42, 44. The network ids (S-40, S-42, NFR-15, F13.x across machines,
F6.3 by cable) live in the two-machine document.
