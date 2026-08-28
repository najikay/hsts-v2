# HSTS — the full manual round, in one sitting

One file, every manual check still owed, ordered so you sign in the fewest times.
Everything typeable is paste-ready. Expected results are stated per check; anything
that looks different from what this file says **is a note**, even if it seems minor.

**How to take notes.** Keep your own notes file open beside this one. One line per
observation, in your own words, naming the screen ("bank list", "take exam paper").
Do not fix, do not re-test, do not decide severity — paste the notes to the lead
afterwards and they get triaged into UI-REGISTER.md / ACCEPTANCE_TESTS.md with
rulings. Verbatim wording beats careful wording.

**What this round settles** (why each section exists):

| Owed item | Where it is below |
|---|---|
| Omar's re-verdicts on M-1 / M-4 / M-5 (fixed 2026-08-28, need on-screen confirmation) | §2, §3 |
| The 18 acceptance cases that passed below the screen with the screen half unseen (⚠) | woven through §2–§9, tagged `[case n.m]` |
| U-1..U-4 verification (fixed, awaiting eyes) | §2, §5, §8 |
| B-45 / B-46 rulings (dashboard double code box; back control) | §2 |
| Re-walks 8.4 and 13.4 (fixes landed after their walks) | §6, §7 |
| E2.16 seed content review on rendered screens, the ten question images included | §5, §9 |
| The E16.17 live-key bot session | §4 — **do this part with the lead on a call** |

---

## 0. Pre-flight (10 minutes, PowerShell, in `C:\dev\hsts-v2`)

Everything below assumes today's main and fresh execution windows.

```
git pull
.\mvnw -DskipTests clean package
java -cp target\hsts-server.jar server.db.seed.SeedMain --reseed
```

Expected: reseed answers a per-table breakdown ending in a total (376 rows as of
B-25). If you get `ClassNotFoundException: server.db.seed.SeedMain`, the rebuild
step didn't run — it is required every time, because any clean build empties
`target\`.

Start the server, start a client, connect to `localhost:5555`.

**Every seeded account's password is `demo123`.** The accounts used in this round:

| Username | Role | For | National ID |
|---|---|---|---|
| `maya.levi` | student | take-exam, bell, bot, grades | `374301851` |
| `noam.peretz` | student | the wrong-ID refusal | `385612098` |
| `omer.katz` | student | the timed-out result screen | — |
| `dana.cohen` | teacher | exam list, builder, monitor, releases | — |
| `avi.mizrahi` | teacher | grading override, bot manager | — |
| `tamar.shani` | teacher | the second-teacher lock + notification | — |
| `rina.barak` | coordinator (pure) | approval queue, the disabled New exam | — |
| `principal.avia` | principal | reports, data browser, read-only | — |

Execution codes as seeded: **`2075`** live now (Algebra Midterm, closes ~3h after
reseed), **`5164`** scheduled (+4h), **`4821`** and **`7390`** closed.

---

## 1. Before signing in (5 min)

- [ ] **[case 1.4] The throttle.** Sign in as `maya.levi` with password `wrong` —
      five times. Expected: each refusal is the same generic sentence (it must not
      reveal whether the account exists). The 6th attempt **with the correct
      password** is refused with the too-many-attempts sentence; ~30 seconds later
      it works. Watch that the lockout sentence is product copy, no codes.
- [ ] **[case 21.3, U-4 verify] The connect screen refuses like a product.** Stop
      the server, try to connect. Expected: a plain-English sentence naming the
      address, **no Java class name, no brackets with jargon** (B-37's fix).
      Restart the server.

---

## 2. `maya.levi` — the student's whole day (30–40 min, the heart of the round)

Sign in as `maya.levi` / `demo123`.

**The bell, first — this is M-1/M-5's re-verdict:**
- [ ] The badge shows a count **and the panel shows the rows at first open** —
      Maya has a seeded unread "grade published" notification. Before today's fix
      the panel was empty until something arrived live. *(B-39 re-verdict)*
- [ ] Click the notification: it deep-links to My Grades.
- [ ] Back in the panel: **mark one as read** — the row visibly changes and the
      badge count drops. *(B-43 re-verdict — this control did nothing before.)*

**My Grades while you're here:**
- [ ] Her published Algebra grade renders with score, status and teacher comment
      in one screen (C-3). Names and copy in English, no placeholders.

**The dashboard — B-45's ruling happens here:**
- [ ] Use the dashboard's exam-code card: type `2075`, press its button. It lands
      on Take Exam **with the code pre-filled, asking again**. That double-ask is
      B-45. Decide with it in front of you: acceptable one-screen guard (F6.4's
      design) or worth a copy change ("Confirm the code" instead of a second
      identical prompt)? Write the verdict down either way.

**Take Exam — M-4's re-verdict, the one that blocked everything:**
- [ ] Code `2075` → accepted, header screen shows exam name, 75 minutes,
      7 questions, the instructions line — and **no questions yet** (S-18).
- [ ] **[case from 5.2]** Type `noam.peretz`'s ID first: `385612098`. Expected:
      refused inline, no attempt started, no clock running.
- [ ] Now her own: `374301851`. **The paper must render**: 7 questions, countdown
      running, progress bar, navigator strip with 7 chips. Before today's fix this
      was a blank screen. *(B-42 re-verdict)*
- [ ] Three of the seven questions carry an **illustration** (11005, 11007,
      11010). Each renders as a real diagram, sized sanely, not stretched or
      clipped. *(E2.16 half; B-8's images on their real screen)*
- [ ] Answer two questions. The save indicator moves ("Saving…" → "All changes
      saved"). Change an answer; it moves again.
- [ ] Navigator chips: answered ones look answered; clicking a chip jumps.
- [ ] **[case 21.6]** While the paper is open: resize the window smaller/larger —
      nothing overlaps or vanishes; question text wraps.

**Leave the exam open and go to §3 (two machines). Come back for:**
- [ ] Submit: the confirm dialog shows the answered/blank grid and remaining time;
      confirming lands on the Submitted screen with exactly one action.
- [ ] Re-enter code `2075` after submitting: the server's own "already handed in"
      answer, on the code field (F6.7).

**The study bot — see §4, do it with the lead. The main bot checks run BEFORE
this Take Exam block (no live attempt needed); only §4's short C-4 sub-section
belongs inside the attempt.**

**B-46 ruling, as you move between screens:** whenever you want to go "back",
notice what you reach for and whether the control you expect is where you expect
it. That vague feeling is exactly the finding — write down each moment it happens
and on which screen.

---

## 3. Two machines — the live monitor moment (15 min, needs a second laptop)

Machine A: `maya.levi` mid-attempt on `2075` (from §2). Machine B: `dana.cohen`.

- [ ] **[F1.3 quick check]** While signed in on B as `dana.cohen`, try signing in
      as `dana.cohen` on A's second client — refused with the
      already-signed-in sentence. Sign out, retry, works.
- [ ] **[case 7.1 ⚠ — the Time Extended designed moment]** On B: open the Live
      Monitor for `2075`. Maya's row is live. Add **15 minutes**. On A, watch for
      all three at once: the countdown chip **flashes green**, a floating
      **"+15:00"** rises off it, and a **toast names Dana Cohen** and the new end
      time. On B, the monitor's own close time moves consistently with what Maya
      sees (B-14's fix: the two screens must agree).
- [ ] **[case 21.3 ⚠]** Pull machine A's network cable / disable Wi-Fi
      mid-attempt: the reconnect banner appears, in product words. Reconnect: the
      paper rebuilds itself, answers intact, clock still server-anchored.
- [ ] **[U-1 verify]** The Live Monitor was reached from a real rail item, not a
      placeholder — both rail items batch C enabled (Live Monitor, Take Exam) are
      live screens now.

---

## 4. The bot, live keys — **do this section on a call with the lead (E16.17)**

This is the one part that talks to real providers; it was deliberately never
automated. Ping the lead before starting it.

**Do the main bot checks with NO exam in progress** — either as `maya.levi`
*before* starting §2's Take Exam block, or as `noam.peretz` on the Java 21 bot at
any time. Only the C-4 sub-section below wants a live attempt, and it takes two
minutes of one, not the whole sitting.

**Know the lock rules before judging them** (both are design, not bugs):
- Asking a course's bot **while sitting that same course's exam** is refused
  outright, every time, with a sentence naming the exam. There is no way through.
- Asking a **different** course's bot while sitting an exam shows an integrity
  notice once; acknowledging it lets her ask, and the teacher is alerted once per
  attempt.

**Main checks (no live attempt):**
- [ ] **[U-2 verify]** The bot screen offers a **course picker** with exactly the
      student's enrolled courses.
- [ ] **[case 14.1 ⚠]** Ask a real course question, e.g. on Databases 22:
      `What does a LEFT JOIN return when there is no match?`
      Expected: a grounded answer citing the teacher's sources, arriving within
      the timeout, rendered as chat bubbles with sane wrapping.
- [ ] **[case 21.2 ⚠]** While it thinks: the screen shows a working state
      (skeleton/typing indicator), never a frozen or blank pane.
- [ ] **[case 14.4 ⚠]** Ask something clearly outside the material, e.g.
      `Who won the 2022 World Cup?` Expected: the polite out-of-scope refusal, not
      an answer and not an error.
- [ ] **[case 14.5 ⚠]** Open bot history: both conversations above are there;
      reopening one shows the full transcript.

**C-4 checks (do during §2's attempt, ~2 minutes of it):**
- [ ] With Maya mid-attempt on Algebra `2075`, open the **Databases 22** bot and
      ask: the integrity notice appears **once**; acknowledge; the answer comes.
- [ ] Open the **Algebra 11** bot and ask: refused with the lockout sentence
      naming Midterm: Algebra. **The input box stays usable** — the refusal is a
      banner, not a dead screen. *(B-47 re-verdict: before 2026-08-28 this
      permanently disabled the composer, and the lock survived the exam closing.)*
- [ ] Have the teacher close `2075` early (or submit the attempt): **the same
      Algebra bot screen, without navigating away**, now answers when asked
      again. *(B-47's other half.)*

---

## 5. `dana.cohen` — the authoring side (25 min)

Sign out, sign in as `dana.cohen` / `demo123`.

**The exam list — today's M-6 and #57 land here:**
- [ ] Hover a row: **no "Open →" hint appears** (M-6/B-44 re-verdict). Clicking a
      row selects it and fills the versions panel on the right.
- [ ] **[B-41 / #57 verify]** The header has a **New exam** menu button offering
      exactly her courses (Algebra 11, Calculus 12). Pick one: the builder opens
      empty, scoped to that course.
- [ ] **[U-3-adjacent]** Rail icons all render (no blank squares anywhere on her
      rail — the Icons guard says they can't be missing; eyes confirm).
- [ ] The Exams rail item's tooltip reads the new wording, not "arrives with E7".
- [ ] **[case 4.2 ⚠ context]** A version with a rejection reason shows it **on its
      own card** (Dana's **Algebra Midterm v1** is seeded REJECTED, reason "Only
      five questions for 60 minutes…"; v2 is the APPROVED one that ran).
- [ ] Open a version in the builder: bank picker scoped to the course, points sum
      indicator, the auto-compose tab. Illustrated bank questions show a
      **has-image marker** in the picker.

**Releases and results:**
- [ ] **[case 10.2 ⚠]** Open closed execution `4821`'s results table. Expected
      per row: student, score, attempt status, actual minutes (B-16's columns).
      `omer.katz`'s row says TIMED_OUT with the full 75 minutes. Frozen stats
      match SEED_CONTENT §9.1's numbers — mean/median/deciles render, nothing
      says "grading unfinished".
- [ ] Release manager: `5164` sits SCHEDULED (opens ~4h after reseed); `2075`
      LIVE. Codes, windows and statuses all readable and consistent.
- [ ] **[case 17.3 ⚠, E2.16]** As you pass each screen: does anything look
      empty, fake, or lorem-ipsum-ish? The seed was built so no demoed screen is
      hollow — this is the eyes-on confirmation.

---

## 6. `avi.mizrahi` — grading trail and bot manager (20 min)

Sign out, sign in as `avi.mizrahi` / `demo123`.

**Re-walk 8.4 (the override audit trail, screen half):**
- [ ] Open the grading queue for closed Java execution `7390` (awaiting
      grading). Open `itay.regev`'s paper. Override his grade: new score, a
      justification, **and a comment to the student**. Save.
- [ ] Re-open the same record: original auto grade, the change, the reason and
      the comment are **all still visible** — the full F8.3 trail on screen, not
      just in the database.

**Re-walk 13.4 (the source editor's edit half — B-21's fix):**
- [ ] Open the bot manager for Java 21. The sources list shows 5 rows.
- [ ] **Edit a TEXT source** (the Edit control on a TEXT row is the half that did
      not exist before B-21): change its content, save. Expected: it saves and
      the list reflects it.
- [ ] Remove a different source. Expected: gone, list at 4.
- [ ] Leave this signed in and check on `tamar.shani` (any machine) that she
      received **both** notifications: one for the change, one for the removal
      (F12.3).

**[case 13.6 ⚠ — the edit lock, needs both teachers at once]:**
- [ ] With `avi.mizrahi` holding a source editor open, have `tamar.shani` open
      the same source on the other machine. Expected: she gets the **locked
      banner naming Avi Mizrahi**, read-only, no silent overwrite. Avi closes;
      she can edit.

**[case 14.6 ⚠]:**
- [ ] The bot's teacher view: sessions/analytics for his course render with the
      seeded numbers, charts drawn, no empty panels.

---

## 7. `omer.katz` — one screen (3 min)

Sign in as `omer.katz` / `demo123`.

- [ ] **[case 9.5 ⚠]** Open his result for the Algebra Midterm. Expected: the
      TIMED_OUT attempt renders honestly — score computed from what he answered,
      four questions shown as **"Not answered"**, and the timed-out status
      visible, in words, not as an enum name.

---

## 8. `rina.barak` — the coordinator (10 min)

Sign in as `rina.barak` / `demo123`.

- [ ] Her rail has Approvals; the Mathematics queue shows Calculus exam `101201`
      PENDING.
- [ ] **[case 4.2 ⚠]** Open it **from the queue**: the read-only preview renders
      the full paper — questions, points, images if any — nothing editable.
- [ ] Approve-with-comment or send back (either): the state flips live and Dana
      gets the notification.
- [ ] **[B-41's other half / #57]** On her Exams screen the **New exam control is
      disabled with the reason beside it** (she teaches nothing) — visible label,
      not a hidden button.

---

## 9. `principal.avia` — read-only world (10 min)

Sign in as `principal.avia` / `demo123`.

- [ ] **[case 12.1 ⚠]** Reports: run the same-teacher exam comparison with
      whatever pairing the screen offers over the seeded data (the graded
      executions are Dana's Algebra `4821` and Avi's Java `7390`). Expected: the
      chart renders with the frozen stats, labeled in English, series
      distinguishable, and an honest empty-state if a pairing has no data rather
      than a broken chart.
- [ ] Data browser: exams and results tables fill; **not one mutating control
      anywhere on her surface** (S-7) — no edit, no delete, no approve.
- [ ] **[case 2.6 + 18.5 ⚠, E2.16]** Wherever the bank is browsable (also check
      as `dana.cohen` if her view is richer): filter by course → topic →
      difficulty → free-text; scroll a list with many illustrated questions.
      Expected: filters compose, the scroll is smooth (images are 7-8 KB, there
      is no reason for jank), and **each of the ten images belongs to its
      question** — a diagram about the wrong question is exactly the kind of
      thing only this pass can catch.
- [ ] **[case 21.5 ⚠]** Anywhere convenient: switch light ↔ dark and change the
      accent palette. Every screen you have open follows, text stays readable,
      nothing stays stuck in the old theme.

---

## 10. When you're done

Paste your notes file to the lead, unedited. What happens next:

1. Every note becomes a U-n / B-n entry with a ruling (fix now / fix later /
   as-designed with a reason).
2. The ⚠ cases you confirmed flip from "passed below the screen" to passed
   outright in ACCEPTANCE_TESTS.md; B-39/42/43 get their re-verdict lines.
3. B-45 and B-46 get closed with whatever you ruled.

What this round deliberately does **not** cover: E20.2 (clean-Windows
double-click) and E20.5 (two-machine on the real university network) — those need
their specific hardware/network and stay their own sessions; and the dry-run
defenses, which need the whole team and the clock.
