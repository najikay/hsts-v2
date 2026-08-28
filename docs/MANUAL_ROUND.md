# HSTS — the manual round (v2, time-aware)

**What this is.** Every check that needs human eyes, in one file, in the order that costs the
fewest sign-ins and puts blockers first. v2 replaces the 2026-08-28 v1 after round 1 stopped
on blockers: it adds a **time plan**, a **round tracker**, a **re-verdict block** for the
sixteen things round 1 found, and it moves the bot out from behind the 75-minute exam.

**How to take notes.** One line per observation in your own words, naming the screen. Do not
fix, do not re-test, do not decide severity. Paste the file to the lead unedited; every line
becomes a numbered register entry with a ruling the same day. Verbatim beats careful.

**Skip what round 1 already confirmed.** Round 1 got through the student flow, the teacher
side and the bank before stopping. Tick those boxes from memory; re-check only what a fix
touched (§1 lists exactly those).

## The time plan

| § | Block | Sign in as | Minutes | Needs |
|---|---|---|---|---|
| 0 | Pre-flight: pull, rebuild, reseed, start | — | 10 | — |
| 1 | **Round-1 re-verdicts** (16 fixes, blockers first) | several | 30 | second machine for 1.9 |
| 2 | Before sign-in: throttle, connect refusals, back link | — | 5 | — |
| 3 | Bot, live keys (no exam running) | `maya.levi` | 15 | **the lead on a call** |
| 4 | Student day: bell, grades, dashboard, **then** the exam | `maya.levi` | 25 | — |
| 5 | Two machines: live monitor, extension, reconnect, C-4 | `dana.cohen` + Maya | 15 | second machine |
| 6 | Teacher authoring: exam list, builder, results, releases | `dana.cohen` | 20 | — |
| 7 | Grading trail + bot manager + edit lock | `avi.mizrahi`, `tamar.shani` | 20 | second machine for the lock |
| 8 | One screen: the timed-out result | `omer.katz` | 3 | — |
| 9 | Coordinator | `rina.barak` | 8 | — |
| 10 | Principal, bank images, theme | `principal.avia` | 12 | — |
| | **Total** | | **≈ 2 h 45 m** | |

**If you have less time**, cut from the bottom: §10, §9, §8 are the cheapest to skip; §1 and §4
are never skipped. The exam attempt (§4's last block) runs on a 75-minute clock: start it
**last** in §4 so nothing else waits on it, and do §5 while it runs.

## Round tracker

| Round | Date | Got to | Stopped because | Notes file |
|---|---|---|---|---|
| 1 | 2026-08-28 | student flow, teacher side, bank, grading | 18 findings, 4 blockers (M-4 paper, bot lock, login status, grading skeleton) | `docs/manual-round-1-notes.txt` |
| 2 | | | | `docs/manual-round-2-notes.txt` |

---

## 0. Pre-flight (10 min, PowerShell, in `C:\dev\hsts-v2`)

```
git pull
.\mvnw -DskipTests clean package
java -cp target\hsts-server.jar server.db.seed.SeedMain --reseed
```

Reseed answers a per-table breakdown and a total (376 rows). `ClassNotFoundException:
server.db.seed.SeedMain` means the rebuild did not run: any clean build empties `target\`.
**A stale client jar re-shows every bug this round is meant to close.**

Start the server, start a client, connect to `localhost:5555`.

**Every account's password is `demo123`.**

| Username | Role | For | National ID |
|---|---|---|---|
| `maya.levi` | student | bell, grades, bot, the exam | `374301851` |
| `noam.peretz` | student | the wrong-ID refusal | `385612098` |
| `omer.katz` | student | the timed-out result screen | — |
| `dana.cohen` | teacher (Algebra 11, Calculus 12) | exam list, builder, monitor, releases, results | — |
| `avi.mizrahi` | teacher (Java 21) | grading override, bot manager | — |
| `tamar.shani` | teacher (Java 21, co-teacher) | the second-teacher lock and notification | — |
| `rina.barak` | coordinator, teaches nothing | approval queue, the disabled New exam | — |
| `principal.avia` | principal | reports, data browser, read-only | — |

Execution codes as seeded: **`2075`** live now (Algebra Midterm, closes ~3 h after reseed),
**`5164`** scheduled (+4 h), **`4821`** and **`7390`** closed.

---

## 1. Round-1 re-verdicts (30 min) — do these first

Each line is a fix from your round-1 notes. The verdict is yours: tick, or write what you
still see. Ordered by sign-in so you cross accounts once.

**Nobody signed in (2 min):**
- [ ] **U-6 login status.** With the client on the login screen, **stop the server**. The
      green Connected chip turns to Disconnected, the label reads "Not connected", the
      sign-in button greys out and a "Reconnect" link appears leading to the connect screen.
      Start the server again before continuing.
- [ ] **U-5 connect back.** Connect screen → "change server" (the manual form) → there is now a
      "Back to the server list" link beside "Look for servers again", and it returns to the
      list you came from.

**`maya.levi` (10 min):**
- [ ] **U-10 dashboard → exam is a confirmation.** Dashboard code card: type `2075`, press it.
      Take Exam opens with the title **"Confirm your exam"**, the code shown read-only, the
      subtitle "You are about to enter the exam with code 2075.", the button **"Confirm and
      continue" already enabled**, and a "Use a different code" link. Press Confirm: the
      identity screen. Press Back (top-left of the navbar), return via the card again: still
      enabled on the second visit (the bug was the second visit).
- [ ] **U-8 back on every non-rail screen.** My Grades → open an exam: the navbar shows
      **Back** (left of the breadcrumbs). It returns to My Grades. The old link above the
      title is gone (one Back per screen).
- [ ] **U-8 print exit.** On that opened exam, switch the print layout on: the chrome
      disappears **except** an "Exit print view" control; it turns print off.
- [ ] **U-9 teacher's name.** Same opened exam: under the exam name a muted line
      **"Teacher: Dana Cohen"**.
- [ ] **U-7 ring.** My Grades' term-average ring: the filled arc ends where the number says,
      no overhang, no stray dot at small values. Cosmetic; note anything still off.
- [ ] **U-11 modal scrim.** Trigger any modal (e.g. Take Exam → start an attempt → Submit):
      the **whole window dims** behind a centered dialog with a soft shadow; no dark box
      hugging the dialog. Cancel.
- [ ] **B-47 bot lock is transient.** Start the `2075` attempt (ID `374301851`), open the
      **Algebra 11** bot, ask anything: refused with the lockout sentence, **but the input
      stays usable**. Have the teacher close `2075` early (§1.9 below) or submit the attempt;
      ask again on the same screen without navigating: answered.

**`dana.cohen` (10 min):**
- [ ] **U-13 table width.** Question Bank: the columns share the whole width, the stem
      column is the widest, no dead space on the right. Check the exam list and the results
      table too (same component).
- [ ] **U-11 the "handing out" modal.** Releases → hand out an exam: the dialog appears over a
      fully dimmed window, no box. Cancel.
- [ ] **U-12 / B-48 grading rows.** Grading → pick a queued execution: the students **appear**
      (or an honest empty state if there are none) — never the loading skeleton forever.
      **Write down which execution and how many rows you saw.** If it is empty for an
      execution that should have papers, that is the second half of B-48 and I need it.
- [ ] **U-8 back on the builder.** Exams → open a version in the builder: navbar Back returns
      to Exams. Exams → New exam → a course: Back returns to Exams.
- [ ] **U-8 back on the live monitor drill-in.** Releases → a live row → the monitor. Live
      Monitor is a rail item now, so by your own rule it carries **no** Back; the rail's
      Releases item is the way back. If that feels wrong in practice, write it down — it was
      the one judgment call in the wave.
- [ ] **U-14 bots.** Study Bot: what you see is **one bot per course you teach** (Dana: two,
      Algebra and Calculus). Create the study bot is only offered for a course that has none.
      **Round 1 called this "unlimited bots" — please write the exact clicks that produced
      it**; the server enforces one per course structurally, so the screen is the question.

**`avi.mizrahi` (3 min):**
- [ ] **U-8 back on bot history / analytics.** Study Bot → history and analytics screens
      each show the navbar Back; it returns to the Study Bot screen.

**`rina.barak` (3 min):**
- [ ] **U-8 back on the approval preview.** Approvals → open `101201` from the queue: navbar
      Back returns to Approvals. The footer's own "Back to approvals" remains (it carries the
      queue's state and is not a duplicate of the navbar).

**Two machines (2 min, 1.9):**
- [ ] With Maya mid-attempt on A, Dana on B: Releases → **close `2075` early**. Maya's paper
      takes over with the Time Up screen; her Algebra bot (B-47 above) answers on the next
      ask. Reseed afterwards if you want `2075` live again for §5.

---

## 2. Before signing in (5 min)

- [ ] **[1.4] Throttle.** `maya.levi` with password `wrong`, five times: same generic refusal
      every time; the 6th attempt **with the right password** is refused with the
      too-many-attempts sentence; ~30 s later it works.
- [ ] **[21.3, U-4]** Stop the server, try to connect: a plain-English sentence naming the
      address, no Java class name, no brackets. Restart the server.

---

## 3. The bot, live keys (15 min) — **on a call with the lead (E16.17)**

Sign in as `maya.levi`. **No exam running** (this is why it moved ahead of §4).

Lock rules first, both are design: the same course's bot is refused outright while you sit
its exam; a different course's bot shows a one-time integrity notice you acknowledge.

- [ ] **[U-2 verify]** Course picker offers exactly her three courses.
- [ ] **[14.1]** Databases 22: `What does a LEFT JOIN return when there is no match?` → a
      grounded answer citing the teacher's sources, within the timeout, readable bubbles.
- [ ] **[21.2]** While it thinks, a working state shows; never a frozen pane.
- [ ] **[14.4]** `Who won the 2022 World Cup?` → the polite out-of-scope refusal.
- [ ] **[14.5]** Bot history lists both conversations; reopening shows the transcript.

---

## 4. The student's day (25 min) — `maya.levi`

**Bell (M-1/M-5 re-verdicts, if not already ticked in §1):**
- [ ] Badge count and rows **at first open**; click a row → deep-links to My Grades; mark one
      read → the row changes and the badge drops.

**My Grades:**
- [ ] Published Algebra grade with score, status, teacher comment; English copy, no
      placeholders.

**The exam — start this block last:**
- [ ] Dashboard card `2075` → Confirm and continue → header screen: exam name, 75 minutes,
      7 questions, the instructions line, **no questions yet**.
- [ ] **[5.2]** `noam.peretz`'s ID `385612098` → refused inline, no clock. Her own
      `374301851` → the paper: 7 questions, countdown, progress bar, 7 chips.
- [ ] Three illustrated questions (11005, 11007, 11010) render as real diagrams.
- [ ] Answer two; the save indicator moves. Change one; moves again. Chips reflect answers.
- [ ] **[21.6]** Resize the window: nothing overlaps; text wraps.
- [ ] **C-4 (2 min):** open the **Databases 22** bot → integrity notice once → acknowledge →
      answered. Open the **Algebra 11** bot → lockout sentence, input still usable.
- [ ] *(go do §5 now, come back)* Submit: the confirm grid and remaining time; the Submitted
      screen has one action. Re-enter `2075`: the server's "already handed in" answer.

---

## 5. Two machines (15 min) — `dana.cohen` on B, Maya mid-attempt on A

- [ ] **[F1.3]** `dana.cohen` signing in on a second client is refused with the
      already-signed-in sentence; sign out, retry, works.
- [ ] **[7.1 ⚑ Time Extended]** B: Live Monitor for `2075`, Maya's row live, add **15
      minutes**. A: chip flashes green, "+15:00" rises, toast names Dana Cohen and the new
      end. B's close time agrees with A's countdown (B-14).
- [ ] **[21.3]** Cut A's network mid-attempt: reconnect banner in product words; reconnect:
      the paper rebuilds, answers intact.

---

## 6. Teacher authoring (20 min) — `dana.cohen`

- [ ] Exams: hover a row → **no "Open →" hint**; click selects and fills the versions panel.
- [ ] **New exam** menu offers Algebra 11 and Calculus 12; pick one → empty builder scoped to it.
- [ ] Rail icons all render; the Exams tooltip reads the new wording.
- [ ] Algebra Midterm **v1** shows its rejection reason on its own card ("Only five questions
      for 60 minutes…"); v2 is APPROVED.
- [ ] Builder on a version: picker scoped to the course, points sum indicator, auto-compose
      tab, has-image markers.
- [ ] **[10.2]** Results for `4821`: student, score, attempt status, actual minutes;
      `omer.katz` TIMED_OUT with 75 minutes; frozen stats render, nothing says "unfinished".
- [ ] Releases: `5164` SCHEDULED, `2075` LIVE (or CLOSED if you closed it in §1.9).
- [ ] **[17.3, E2.16]** Nothing on any screen looks empty or fake.

---

## 7. Grading trail, bot manager, edit lock (20 min) — `avi.mizrahi` (+ `tamar.shani`)

- [ ] **Re-walk 8.4.** Grading → `7390` → `itay.regev` → override with score, justification
      and a comment; save; reopen: original, change, reason and comment all visible.
- [ ] **Re-walk 13.4.** Bot manager, Java 21: 5 sources; **edit a TEXT source** and save;
      remove another → 4. `tamar.shani` receives one notification for each.
- [ ] **[13.6 ⚠, two machines]** Avi holds a source editor open; Tamar opens the same source:
      locked banner naming Avi Mizrahi, read-only; Avi closes; she can edit.
- [ ] **[14.6]** The bot's teacher view renders sessions and analytics with seeded numbers.

---

## 8. One screen (3 min) — `omer.katz`

- [ ] **[9.5]** His Algebra result: TIMED_OUT rendered in words, four questions "Not
      answered", score from what he answered.

---

## 9. Coordinator (8 min) — `rina.barak`

- [ ] Approvals rail item; Mathematics queue shows `101201` PENDING.
- [ ] **[4.2]** Open it from the queue: full read-only paper. Approve-with-comment or send
      back: state flips live, Dana gets the notification.
- [ ] Exams: **New exam disabled with the reason beside it** (she teaches nothing).

---

## 10. Principal, bank images, theme (12 min) — `principal.avia`

- [ ] **[12.1]** Reports: the same-teacher comparison with whatever pairing the screen
      offers over `4821` (Dana) and `7390` (Avi); chart renders, honest empty state if a
      pairing has no data.
- [ ] Data browser fills; **no mutating control anywhere** (S-7).
- [ ] **[2.6 + 18.5, E2.16]** Bank browse (as `dana.cohen` if richer): filters compose,
      scroll is smooth, **each of the ten images belongs to its question**.
- [ ] **[21.5]** Light ↔ dark and an accent change: every open screen follows.

---

## 11. When you're done

Paste your notes file unedited. Then: every note → a U-n/B-n entry with a ruling; the ⚠
cases you confirmed flip to passed; §1's re-verdicts close U-5..U-13, B-47, B-48; the
tracker above gets its round-2 line.

Not in this round, by design: E20.2 (clean-Windows double-click) and E20.5 (the real
university network) need their own hardware and sessions; the dry-run defenses need the whole
team and the clock.
