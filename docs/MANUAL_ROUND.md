# HSTS — the full manual round (v3: every screen, every button, nothing waits)

**What this is.** The complete manual test of the product, on one machine, with as many
client windows as it takes, in an order where no step waits on another for more than about
two minutes. It walks every requirement in the PDF (mapped through `TRACEABILITY.md`'s ids,
see the coverage map at the end) and everything in between. Two-machine and network checks
are collected in the **last** section for Omar.

**Rules of the walk.**
- One notes file, one line per observation, your words, the screen named. No fixing, no
  re-testing, no severity: paste it over and it becomes register entries the same day.
- "Every button" is literal: if a control is on a screen and this file does not name it,
  press it anyway and write down what it did.
- Several clients on one machine is fine and used on purpose (different accounts at once).
  One session per account (F1.3), so switching accounts in a window means Sign out first.
- The clock is the only thing that blocks: the 2-minute exam in §6 is the longest wait.

## Time plan (≈ 3 h 20 m, all single machine)

| § | Block | Sign in as | Min |
|---|---|---|---|
| 0 | Pre-flight and the server console | — | 12 |
| 1 | Connect and login screens | — | 10 |
| 2 | Question bank, every control | `dana.cohen` | 20 |
| 3 | Exam builder: the 2-minute exam is born | `dana.cohen` | 15 |
| 4 | Approval round trip, rejection first | `rina.barak` ↔ `dana.cohen` | 12 |
| 5 | Releases: hand it out, cancel one, validate windows | `dana.cohen` | 10 |
| 6 | Sit the 2-minute exam and let it expire | `maya.levi` + `dana.cohen` (2 windows) | 10 |
| 7 | Grading, results, statistics, live grade push | `dana.cohen` + `maya.levi` | 15 |
| 8 | The student's grade screens | `maya.levi` | 8 |
| 9 | Self-approval by a dual-hat coordinator | `michal.sharon` | 8 |
| 10 | Edit locks, sign-out cleanup, bot manager | `avi.mizrahi` + `tamar.shani` (2 windows) | 20 |
| 11 | The study bot, live keys, C-4 | `maya.levi` (+ `dana.cohen`) | 15 |
| 12 | Live monitor: extend, attention, a submit, close early | `dana.cohen` + `maya.levi` + `omer.katz` | 14 |
| 13 | Refusals and edge accounts | `noam.peretz`, `omer.katz` | 8 |
| 14 | Principal: data browser, reports, read-only | `principal.avia` | 12 |
| 15 | Settings, shell, sessions | any | 10 |
| 16 | **Omar: two machines and the network** | — | 30 |

## Round tracker

| Round | Date | Got to | Stopped because | Notes file |
|---|---|---|---|---|
| 1 | 2026-08-28 | student flow, teacher side, bank, grading | 18 findings, 4 blockers | `docs/manual-round-1-notes.txt` |
| 2 | | | | `docs/manual-round-2-notes.txt` |

**Accounts (password `demo123` everywhere):**

| Username | Role | National ID |
|---|---|---|
| `maya.levi` | student: Algebra 11, Java 21, Databases 22 | `374301851` |
| `noam.peretz` | student: Calculus 12, Java 21 (not Algebra) | `385612098` |
| `omer.katz` | student, the seeded TIMED_OUT attempt; sits `2075` in 12.2b | `361489206` |
| `dana.cohen` | teacher: Algebra 11, Calculus 12 | — |
| `avi.mizrahi` / `tamar.shani` | Java 21 co-teachers | — |
| `michal.sharon` | teaches Databases 22 **and** coordinates CS 20 (dual hat) | — |
| `rina.barak` | coordinates Mathematics 10, teaches nothing | — |
| `principal.avia` | principal, read-only | — |

Seeded executions: **`2075`** live (Algebra Midterm, 75 min), **`5164`** scheduled (+4 h),
**`4821`** / **`7390`** closed. Seeded questions in exams (delete is blocked on these):
Algebra 11001/11002/11005/11007/11009/11010/11011; **11003, 11004, 11006, 11008 are in no
exam** and may be deleted.

---

## 0. Pre-flight and the server console (12 min)

```
git pull
.\mvnw -DskipTests clean package
java -cp target\hsts-server.jar server.db.seed.SeedMain --reseed
```
- [ ] 0.1 Reseed prints the per-table breakdown and 376 rows. *(F14.2, NFR-17)*
- [ ] 0.2 Start the server **from a terminal** (`java -jar target\hsts-server.jar`): Flyway
      lines, "listening" line, the console window opens. *(F13.1, F14.1)*
- [ ] 0.3 Console: the address shown is your LAN IPv4 with the other candidates listed;
      pick another candidate and back. *(F13.2)*
- [ ] 0.4 Console: the fingerprint line, DB status green, connected clients = 0. *(F13.1, F13.3)*
- [ ] 0.5 Console log tail: **Pause**, **Copy**, **Clear** each do what they say; a red log4j2
      line ~30 s in is known (B-2), nothing else red.
- [ ] 0.6 Console **Load demo data if missing** → answers UNCHANGED (the seed is there).
      **Reload demo data** → asks for confirmation; **Cancel**. *(NFR-17)*
- [ ] 0.7 Start a client (`java -jar target\hsts-client.jar`) and a second one later when
      told; connected clients on the console counts them live. *(F13.1)*

---

## 1. Connect and login (10 min)

- [ ] 1.1 The client finds the server by discovery and lands on Login with "Connected to
      &lt;server&gt; · change server". *(F13.4, F1.5)*
- [ ] 1.2 **change server** → manual form: type a wrong address → refused in plain English,
      no class name, no brackets; **Back to the server list** returns to the picker; **Look
      for servers again** re-sweeps. *(F1.5, U-4, U-5)*
- [ ] 1.3 Login with a wrong password: one generic sentence. Five wrong tries → the sixth
      **with the right password** is refused with the too-many-attempts sentence; wait 30 s;
      it works. *(F1.1, S-38)*
- [ ] 1.4 On the login screen: **stop the server**. Chip → Disconnected, "Not connected",
      Sign in disabled, **Reconnect** link. Start the server; Reconnect → connect screen →
      back on Login connected. *(U-6, F1.5)*
- [ ] 1.5 Sign in as `dana.cohen`: the teacher rail (Dashboard, Question Bank, Exams,
      Releases, Live Monitor, Grading, Results, Study Bot, Settings), no Approvals, every
      icon drawn. Breadcrumbs show Dashboard. *(F1.2)*

---

## 2. Question bank, every control (20 min) — `dana.cohen`

- [ ] 2.1 Dashboard first: cards Sittings in progress (2075 with Code / Closes / minutes
      left), Today and next (5164), Awaiting grading, Class average. Every card link opens
      the right screen; Back returns. *(NFR-21)*
- [ ] 2.2 Question Bank: course picker (Algebra 11, Calculus 12), topic, difficulty, search,
      **Clear filters**; each narrows the list; combined filters compose; count label
      updates; sort by every column header both ways. *(F2.4)*
- [ ] 2.3 Table fills its width; stem column widest. Select a row → detail pane: stem, four
      answers with the correct mark, topic, difficulty, version, image or "No image".
      *(U-13, C-7)*
- [ ] 2.4 Select 11005 (has an image): the image preview loads (loading state first); the
      **History** panel opens: v1 and v2 with the stem difference; **Close**. *(F2.3, F2.4)*
- [ ] 2.5 **Add question**: leave fields empty → Save disabled or refused with reasons; four
      answers with two identical → refused (distinct rule); no correct marked → refused;
      fill properly (stem, 4 answers, mark one, topic `Linear equations`, difficulty),
      **Choose image** → pick any PNG → "Illustration attached", **remove** it, attach again;
      **Cancel** → "Leave without saving?" → **Keep editing** → Save. New id `110xx`
      appears, read-only. *(F2.1, F2.2, C-7, C-8, S-8)*
- [ ] 2.6 Edit 11003: change the stem, Save → History shows v2; the list row shows the new
      version. *(F2.3, C-2)*
- [ ] 2.7 **Delete** 11005 → blocked, the dialog **names the exams** that reference it. Delete
      your new question from 2.5 → confirm → it disappears (soft delete). *(F2.5)*
- [ ] 2.8 Switch course to Calculus 12: the list changes; a question of a course Dana does
      not teach cannot be reached (no Java in her picker). *(S-5)*

---

## 3. Exam builder: the 2-minute exam (15 min) — `dana.cohen`

- [ ] 3.1 Exams: rows with version chips (Algebra Midterm v1 REJECTED with its reason on the
      card, v2 APPROVED; Quiz: Inequalities DRAFT; Calculus Midterm PENDING). Hover shows no
      "Open" hint; click selects. *(F3.6, F4.2)*
- [ ] 3.2 **New exam** → Algebra 11 → the builder: name **`Round 2 Quick Check`**, duration
      **`2`** (hint says 1..480), student text `Two minutes. Answer what you can.`, teacher
      text `Round-2 timing check.`. *(F3.1, S-11, S-12)*
- [ ] 3.3 Manual composition: pick **11003, 11009, 11010**; points 40/30/**20** → the sum
      indicator says 90 and **Save is blocked**; set 40/30/30 → allowed. Reorder the three;
      order persists after save. *(F3.1, F3.2)*
- [ ] 3.4 Auto-compose tab: ask for 20 questions of topic `Inequalities` HARD → **no exam is
      created** and the report names the exact shortfall; ask for 2 EASY `Linear equations`
      → composes. Discard that and keep the manual paper. *(F3.3)*
- [ ] 3.5 Save draft: exam id `1011xx` (6 digits, server-allocated); Exams shows it DRAFT.
      *(F3.4, S-10)*
- [ ] 3.6 **Submit for approval** → PENDING chip. Edit an APPROVED exam (Algebra Midterm v2):
      the builder opens a **new version**, v2 stays intact. Do not submit that one. *(F3.5, C-2, S-14)*

---

## 4. Approval round trip, rejection first (12 min)

**Window 2, `rina.barak`** (keep Dana's window open):
- [ ] 4.1 Bell: APPROVAL_REQUESTED arrived live for Quick Check. Approvals queue lists Quick
      Check and Calculus `101201`. *(F11.1, F4.1)*
- [ ] 4.2 Open Quick Check: the paper **as a student sees it** plus metadata, the "Teacher
      only" panel, the answer key panel. *(F4.1)*
- [ ] 4.3 **Send back** with an empty reason → refused; reason `Add a fourth question.` →
      sent back. *(F4.2)*

**Dana's window:**
- [ ] 4.4 APPROVAL_REJECTED push arrives; the version card shows the reason. Open the
      builder → it makes **v2**; add 11011, points 25/25/25/25; save; submit. *(F4.2, C-2)*

**Rina's window:**
- [ ] 4.5 Queue shows Quick Check v2 (v1 gone: superseded); **Approve** → APPROVED. Approve
      `101201` too. Queue: "Nothing waiting". *(F4.2, F3.6)*
- [ ] 4.6 Dana's bell: APPROVAL_APPROVED, and the Exams chip flipped live without a
      refresh. *(NFR-18)*

---

## 5. Releases (10 min) — `dana.cohen`

- [ ] 5.1 Releases: seeded rows with chips Scheduled (`5164`) / Live (`2075`) / Closed
      (`4821`), participant counters on the live row. *(F5.4, S-2)*
- [ ] 5.2 **Release an exam**: the Approved exam picker offers Quick Check v2 and Algebra
      Midterm v2 (only approved versions). *(F5.1)*
- [ ] 5.3 Window validation: closes before opens → complaint; opens more than 5 min in the
      past → complaint; window shorter than a minute → complaint. *(F5.2)*
- [ ] 5.4 Code: **Generate for me** → 4 characters; type your own `QC2A`; a 3-character code
      is refused. *(F5.3, C-1, S-16)*
- [ ] 5.5 Set **Opens = now, Closes = now + 5 minutes**, **Release it** → "Read this code
      out" dialog, **Copy code**, **Done**; the row is **Live** with code shown to the
      teacher only. *(F5.3, S-17)*
- [ ] 5.6 Release Algebra Midterm v2 again with Opens = now + 20 min → **Scheduled**;
      `maya.levi` (window 2, sign Rina out first) gets RELEASE_OPENING_SOON within a couple
      of minutes (it is inside the 30-minute horizon). Then **Cancel release** → confirm →
      the row goes. *(F5.5, F11.1, S-2)*

---

## 6. Sit the 2-minute exam and let it expire (10 min)

**Window 2, `maya.levi`; window 1 stays `dana.cohen`.**
- [ ] 6.1 Dashboard: "Your courses" lists three; the code card: `QC2A` → **Confirm your
      exam** (read-only code, Confirm and continue enabled) → header screen: name, 2 minutes,
      4 questions, the student text, **no questions**. *(F6.1, U-10)*
- [ ] 6.2 ID `385612098` (Noam's) → refused inline, no clock started. Own ID `374301851` →
      the paper; countdown started **now**. *(F6.1, F6.2, S-18)*
- [ ] 6.3 Answer **one** question only. Indicator moves to saved. Chips reflect it. Do **not**
      submit. *(F6.3)*
- [ ] 6.4 Window 1 (Dana): Live Monitor → Quick Check: Maya's row Started, remaining time
      counting down, counts 1/0/0. *(F7.2)*
- [ ] 6.5 Dana: **Add time** = **1** minute → Maya's chip flashes, "+1:00" rises, toast names
      Dana Cohen; TIME_EXTENDED in her bell. *(F7.1, S-20)*
- [ ] 6.6 Wait for the clock: amber then red as it runs out; at zero the **Time is up**
      takeover covers the paper, cannot be dismissed, shows answered/unanswered and solving
      minutes, one button. *(F6.4, F6.5, S-19)*
- [ ] 6.7 Monitor row → Timed out; counts 1 started / 0 handed in / 1 timed out; the row is
      frozen. *(F7.2, F7.3, S-21)*
- [ ] 6.8 Maya: **Back to my dashboard**; re-enter `QC2A` → "already handed in". *(F6.7)*
- [ ] 6.9 Releases: the Quick Check row is **Closed** when its window ends (≤ 3 min more);
      the record shows the frozen three counts. *(F5.4, F7.3)*

---

## 7. Grading, results, statistics, live grade push (15 min)

**Window 1, `dana.cohen`:**
- [ ] 7.1 Bell: GRADING_DUE for Quick Check; dashboard "Awaiting grading" card counts it.
      *(F11.1)*
- [ ] 7.2 Grading → Quick Check: Maya's row with the **auto score** (one answer, up to 25). The
      table renders rows, not a skeleton. *(F8.1, B-48)*
- [ ] 7.3 **Change score…** with an empty reason → refused; reason `Partial credit for
      method` and a comment `Good attempt under time pressure`, score 40 → saved; the row
      shows the adjusted marker. *(F8.3, S-22, S-23)*
- [ ] 7.4 **Approve selected** (tick the row; also try Select all). *(F8.2, C-3)*
- [ ] 7.5 Results → Quick Check: the sitting; histogram/table toggle; stat cards mean, median,
      σ, min/max, pass rate at ≥ 55, deciles — computed and stored. Print layout on → chrome
      gone, **Exit print view** works. *(F8.5, F9.2, S-25, C-5)*
- [ ] 7.6 Results also lists Algebra Midterm `4821`: `omer.katz` TIMED_OUT with 75 minutes,
      attempt status and actual minutes columns. *(S-35, S-19)*
- [ ] 7.7 Dashboard "Class average" now shows Quick Check's mean. *(NFR-18)*

**Window 2, `maya.levi`, untouched since 6.8:**
- [ ] 7.8 The bell badge rose **by itself** (GRADE_PUBLISHED) and My Grades gained the row
      without any refresh. *(F8.4, NFR-18, S-24)*

---

## 8. The student's grade screens (8 min) — `maya.levi`

- [ ] 8.1 My Grades: hero term average (ring ends where the number says), rows with Exam /
      Course / Grade / Approved / Teacher's note; Quick Check shows 40 with "Reviewed by your
      teacher" and the comment; **the justification is nowhere**. *(F9.1, S-23, U-7)*
- [ ] 8.2 Open Quick Check: "Teacher: Dana Cohen" under the title; per-question your
      answer / correct answer / points; attempt status Timed out; solving minutes. *(F9.1,
      S-36, U-9)*
- [ ] 8.3 Print layout → **Exit print view**; navbar **Back** → My Grades. *(U-8)*
- [ ] 8.4 Bell panel: mark one read → badge drops; **Mark all** → zero; relative times and
      icons; click-through on the grade row lands on My Grades. *(F11.2)*
- [ ] 8.5 Nothing anywhere on her screens shows statistics or another student. *(S-26)*

---

## 9. Self-approval by a dual-hat coordinator (8 min) — `michal.sharon`

- [ ] 9.1 Her rail has both Study Bot (teacher) and Approvals (coordinator of CS 20). *(F1.2)*
- [ ] 9.2 New exam → Databases 22: `Self-approval check`, 5 minutes, three questions 40/30/30,
      save, submit. *(F3.1)*
- [ ] 9.3 Approvals: the queue shows it with **"You wrote this one"**; open → the
      self-approval note; **Approve** → allowed; the approval is recorded as self-approval
      (the server log line says so). *(F4.3)*

---

## 10. Edit locks, sign-out cleanup, bot manager (20 min)

**Window 1 `avi.mizrahi`, window 2 `tamar.shani`** (both Java 21).
- [ ] 10.1 Avi: Question Bank → edit 21003. Tamar: her bank list shows the row badged
      **editing by Avi Mizrahi**, live; opening it → read-only with the banner. *(F10.0, F10.2, F2.6)*
- [ ] 10.2 Avi closes the editor → Tamar's badge clears and she can edit. *(F10.2, F10.1)*
- [ ] 10.3 Avi reopens 21003 and **signs out** while holding it → Tamar's badge clears
      (locks released on logout). Sign Avi back in. *(F1.4, F10.1)*
- [ ] 10.4 Avi: Study Bot (manager) for Java 21: bot name, **Active** toggle, sources list
      (5). **Add text** → paste a paragraph → saved; **Add file** → a small PDF → parsed,
      appears; a Word file → appears; a garbage file renamed `.pdf` → the parse failure is
      reported in words. *(F12.1, F12.2, S-28, S-29)*
- [ ] 10.5 Edit the text source; Tamar's window: BOT_SOURCE_CHANGED notification for each
      change; while Avi's source editor is open, Tamar opening the same source gets the lock
      banner. Remove one source. *(F12.3, F10.4)*
- [ ] 10.6 Toggle the bot **inactive** → (§11.2 checks the student side) → back to active.
      *(F12.4, S-31)*
- [ ] 10.7 Analytics screen: totals, over time, frequent questions — **no student name
      anywhere**. Bot history is not offered to a teacher. *(F12.11, S-34)*
- [ ] 10.8 Dana in another window: Study Bot shows **one bot per course she teaches** (two),
      Create only where none exists; pressing nothing creates a second. **If you can make a
      second bot for one course, write the exact clicks (U-14).** *(F12.1, S-30, S-6)*

---

## 11. The study bot, live keys, C-4 (15 min) — `maya.levi` (+ `dana.cohen`)

Ping the lead before this section (E16.17).
- [ ] 11.1 Picker offers her three courses; header names the course. *(F12.5, U-2)*
- [ ] 11.2 With Java's bot **inactive** (10.6): asking → the inactive sentence, no crash.
      Flip it active (Avi) → asking works on the same screen. *(F12.4, S-31)*
- [ ] 11.3 Databases 22: `What does a LEFT JOIN return when there is no match?` → typing
      indicator, then a grounded answer; `Who won the 2022 World Cup?` → the friendly
      out-of-scope sentence; an empty question → refused; three questions within seconds →
      the "too fast" sentence. *(F12.5, F12.7, S-32)*
- [ ] 11.4 History: both conversations, timestamps; **reopen** one and continue it. *(F12.9, F12.10, S-33)*
- [ ] 11.5 Start the seeded `2075` attempt (code, own ID). Open the **Algebra** bot → locked
      sentence, **input still usable**. Open **Databases** → the integrity notice **once** →
      acknowledge → answered. Dana's bell: INTEGRITY_ALERT naming Maya; the monitor row for
      `2075` shows "Opened another course's study bot at …". *(F6.8, C-4, F11.1)*
- [ ] 11.6 Keys never on the client: `client.properties` beside the client jar has no key;
      `server.properties` is gitignored. *(F12.6)*

---

## 12. Live monitor extras on `2075` (10 min) — `dana.cohen` with Maya mid-attempt

- [ ] 12.1 Alt-tab away from Maya's exam window for ~2 s and back: the monitor row shows
      "Her exam window last lost focus at …" and an attention count; **nothing** appears on
      Maya's side. *(F7.1b)*
- [ ] 12.2 Add time 15 → the full Time Extended moment on Maya's screen; the monitor's
      close time agrees with her countdown. *(F7.1)*
- [ ] 12.2b **Window 3, `omer.katz`** (ID `361489206`; he sat `4821`, `2075` is a different
      execution of the same exam, so one attempt per execution lets him in): code `2075`, own
      ID, answer two questions, **Submit** → the two-step confirm: the answer grid, remaining
      time, the unanswered-score-zero note; **Cancel** once, then confirm → the **Submitted**
      screen: handed-in time, solving minutes, summary, **Back to my dashboard**. Monitor:
      his row Handed in. *(F6.9, F6.10, F6.7, F7.2)*
- [ ] 12.3 Releases → `2075` → **Close early** → confirm: Maya gets the Time Up takeover; the
      row Closed; counts frozen (2 started / 1 handed in / 1 timed out); grading queue gains
      it (GRADING_DUE). *(F5.5, F6.4, F7.3, S-21)*
- [ ] 12.4 Maya's Algebra bot, same screen as 11.5, answers on the next ask (B-47).
- [ ] 12.5 Dana: Grading → `2075`: Omer's auto score and Maya's timed-out paper; approve
      both; Omer's window: GRADE_PUBLISHED and My Grades gains the row live. *(F8.1, F8.4)*

---

## 13. Refusals and edge accounts (8 min)

`noam.peretz` (window 2, sign Maya out):
- [ ] 13.1 Code `2075` → not enrolled (Algebra). Code `5164` → not open yet. Code `ZZZZ` →
      unknown. Code `12` → malformed. *(S-15, F6.1, S-5)*
- [ ] 13.2 His rail and dashboard show only Calculus and Java.

`omer.katz`:
- [ ] 13.3 My Grades → Algebra Midterm: TIMED_OUT in words, four questions Not answered,
      score from what he answered. *(F6.4, S-19)*

---

## 14. Principal (12 min) — `principal.avia`

- [ ] 14.1 Rail: Dashboard, Data, Reports, Settings. Dashboard cards read-only. *(S-7)*
- [ ] 14.2 Data: segments **Questions / Exams / Results**, text filter, course filter; a
      large question set shows the "too many" hint and paginates; **no** Add / Edit /
      Delete / Approve anywhere. *(F9.3, S-7)*
- [ ] 14.3 Reports: pick a subject; **By teacher / By course / By student**; stat cards +
      the closed-sittings table read the **stored** statistics (Quick Check's numbers match
      7.5); print layout + exit. *(F9.4, S-37, C-5, S-25)*
- [ ] 14.4 The report includes `4821`, `7390` and today's Quick Check; excludes cancelled
      releases. *(C-5)*

---

## 15. Settings, shell, sessions (10 min) — any account

- [ ] 15.1 Settings: Light / Dark / System (System shows what it resolves to), each accent
      palette, every open window follows instantly; **Reset to defaults**. *(NFR-21)*
- [ ] 15.2 Profile menu: theme radios, **Sign out**. Rail collapse/expand keeps tooltips.
- [ ] 15.3 Breadcrumbs on a drill-in (Question Bank → editor) show the parent crumb; navbar
      Back on every non-rail screen; none on rail screens. *(U-8)*
- [ ] 15.4 Resize the window small and large on three screens: nothing overlaps. *(NFR-21)*
- [ ] 15.5 F1.3: with `dana.cohen` signed in, a second client signing in as `dana.cohen` is
      refused with the sentence; sign the first out → the second succeeds. *(F1.3, NFR-16)*
- [ ] 15.6 Every failure met today was a sentence, never a code; every async screen showed
      progress; no refresh button exists anywhere. *(NFR-18, NFR-21, S-44)*

---

## 16. Omar: two machines and the network (30 min)

- [ ] 16.1 Server on A, client on B: discovery finds A; the picker shows name, address and
      fingerprint; first connect pins it; next launch auto-connects. *(F13.3, F13.4, NFR-15)*
- [ ] 16.2 Stop discovery (or a firewall): the client falls to manual entry after ~2 s;
      typing A's address works. *(F13.4, F13.2)*
- [ ] 16.3 Restart the server with a new fingerprint file: the client warns of the mismatch;
      decline → back to the picker; accept → connects. *(F13.4)*
- [ ] 16.4 Same account on A and B: the second refused; a socket drop on A (pull the cable)
      frees the session within seconds; B can sign in. *(F1.3, F1.4, S-40)*
- [ ] 16.5 Student mid-attempt on B, cable pulled: reconnect banner in words; cable back →
      the paper rebuilds with saved answers and the right remaining time. *(F6.3)*
- [ ] 16.6 The Time Extended moment and the monitor across two real machines. *(F7.1, F7.2)*
- [ ] 16.7 On a clean Windows account: double-click `G12-1_Server.jar` then
      `G12-1_Client.jar`, both run; `java -jar` too; properties files beside the jars are
      read. *(F14.1, F14.2)*
- [ ] 16.8 Once on the real university network. *(S-42, NFR-15)*

---

## Coverage map (requirement → step)

F1.1 1.3 · F1.2 1.5, 9.1, 13.2 · F1.3 15.5, 16.4 · F1.4 10.3, 16.4 · F1.5 1.1–1.4 ·
F2.1 2.5 · F2.2 2.5 · F2.3 2.4, 2.6 · F2.4 2.2–2.4 · F2.5 2.7 · F2.6 10.1 ·
F3.1 3.2–3.3, 9.2 · F3.2 3.3 · F3.3 3.4 · F3.4 3.5 · F3.5 3.6 · F3.6 3.1, 4.5 ·
F4.1 4.1–4.2 · F4.2 4.3–4.5 · F4.3 9.3 · F5.1 5.2 · F5.2 5.3 · F5.3 5.4–5.5 · F5.4 5.1, 6.9 ·
F5.5 5.6, 12.3 · F6.1 6.1–6.2, 13.1 · F6.2 6.2, 6.6 · F6.3 6.3, 16.5 · F6.4 6.6, 12.3, 13.3 ·
F6.5 6.6 · F6.6 (structural, `CorrectnessLeakGuardTest`) · F6.7 6.8 · F6.8 11.5 · F6.9 12.2b · F6.10 12.2b ·
F7.1 6.5, 12.2, 16.6 · F7.1b 12.1 · F7.2 6.4, 6.7 · F7.3 6.7, 6.9, 12.3 · F8.1 7.2 · F8.2 7.4 ·
F8.3 7.3 · F8.4 7.8 · F8.5 7.5 · F9.1 8.1–8.2 · F9.2 7.5–7.6 · F9.3 14.2 · F9.4 14.3 ·
F10.0 10.1 · F10.1 10.2–10.3 · F10.2 10.1–10.2 · F10.3 (server-tested; no manual path) ·
F10.4 10.5 · F11.1 4.1, 4.4, 5.6, 7.1, 11.5 · F11.2 8.4 · F11.3 6.5 · F12.1 10.4, 10.8 ·
F12.2 10.4 · F12.3 10.5 · F12.4 10.6, 11.2 · F12.5 11.1, 11.3 · F12.6 11.6 · F12.7 11.3 ·
F12.8 (structural, `BotIsolationGuardTest`) · F12.9 11.4 · F12.10 11.4 · F12.11 10.7 ·
F13.1 0.2–0.7 · F13.2 0.3, 16.2 · F13.3 0.4, 16.1 · F13.4 1.1, 16.1–16.3 · F14.1 0.2, 16.7 ·
F14.2 0.1, 16.7 · S-1..S-4 (seed, 0.1) · S-5 2.8, 13.1 · S-6 10.8 · S-7 14.1–14.2 · S-8 2.5 ·
S-10 3.5 · S-11 3.2 · S-12 3.2 · S-13 2.5 · S-14 3.6 · S-15 13.1 · S-16 5.4 · S-17 5.5 ·
S-18 6.2 · S-19 6.6, 7.6 · S-20 6.5 · S-21 6.7 · S-22 7.3 · S-23 7.3, 8.1 · S-24 7.8 ·
S-25 7.5 · S-26 8.5 · S-27..S-29 10.4 · S-30 10.8 · S-31 11.2 · S-32 11.3 · S-33 11.4 ·
S-34 10.7 · S-35 7.6 · S-36 8.2 · S-37 14.3 · S-38 1.3 · S-40 16.4 · S-42 16.8 · S-44 15.6 ·
C-1 5.4 · C-2 2.6, 3.6, 4.4 · C-3 7.4 · C-4 11.5 · C-5 14.3 · C-7/C-8 2.5 ·
NFR-15 16.1, 16.8 · NFR-16 15.5 · NFR-17 0.1, 0.6 · NFR-18 4.6, 7.7, 7.8 · NFR-21 2.1, 15.1, 15.6.

Unreachable by hand and covered by tests instead: F6.6, F10.3, F12.8. Unresolved ids S-9 /
S-39 are documented gaps in `TRACEABILITY.md`.

## After the round

Paste the notes. Every line becomes a register entry with a ruling; the coverage map's
steps you ticked flip the matching acceptance cases to passed on screen; §16's results come
from Omar as his own notes.
