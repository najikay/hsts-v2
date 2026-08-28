# HSTS v2 · defense demo script (E22.4b)

**Owner:** lead lane (absorbed from Member B) · **Reviewer:** Naji · **Feeds:** the defense itself,
E22.6 and E22.7 dry runs · **Companion:** `docs/DEFENSE_QA.md` (E22.5)

The ordered walkthrough for the defense. `DEMO_DAY.md` is what makes the machines ready; this file
is what happens after the panel sits down. Read them in that order and never in the other one.

> **Ported to `main` on 2026-08-26 (batch D), and six passages changed on the way in.** This file
> was written on the `hsts-e15-wt` worktree while three batches of fixes were landing beside it, so
> the port is a reconciliation and not a copy. What moved: act 2.1's stale rail warning is **gone**
> (batch C swapped the last two placeholders onto their real routes); act 2.7's "the auto tab is
> not built" is **gone** and the tab is clicked (#53); act 5.1's "Take Exam is greyed" is now a
> note about **three doors** into one screen; **act 5.5a is new** and stages the C-4 cross-course
> flag live, which is the most demonstrable requirement in C-4 and had never been shown; acts 6.1
> and 6.4 now depend on My Grades updating **without anybody touching machine B** (B-32); and three
> rows left the not-done table because the things they described were done. Every one of those is
> traceable to an entry in `ACCEPTANCE_TESTS.md` § Bugs found or to `docs/UI-REGISTER.md`.

**Total: 25 minutes 15 seconds of scripted material, in nine acts.** *(Was 24:30; act 5.5a's
C-4 step added forty-five seconds on the 2026-08-26 port. It is on the cut list at position 2b if
the clock is tight, but cutting it costs the only live demonstration of C-4's cross-course half.)*
The
[cut list](#the-cut-list-25-minutes-down-to-15) takes it to 15 minutes without losing a single
claim that carries a requirement id.

## How to read a step

Every step names four things, because a demo fails on the ones nobody wrote down:

| Field | What it holds |
|---|---|
| **Who / where** | the account, and which machine it is signed in on |
| **Clicks** | the literal path, in the labels the screens actually carry |
| **Say** | one sentence, the claim the step proves, with its spec ids in it |
| **If it goes wrong** | the recovery line, said out loud rather than debugged in silence |

**Markers.** ⚑**E16.17** means the step is worth nothing unless the live-key checklist
(`docs/reports/lead/E16.md` §6, gated in `DEMO_DAY.md` §5.4) was run **today**; there are exactly
two of those and they are both in act 8. **[2M]** means the step needs the second machine actually
rehearsed per `DEMO_DAY.md` §4, not just present in the room.

## The honesty rules this script was written under

1. **No step routes through an open gap.** Every gap and partial in `TRACEABILITY.md` and every
   open entry in the `ACCEPTANCE_TESTS.md` register was checked against the path below. What that
   removed is listed in [what this script deliberately does not do](#what-this-script-deliberately-does-not-do),
   with the honest sentence for each, because a panel that finds one of them should hear the same
   answer from the script and from the person talking.
2. **Grade before you report.** Scenario 8 runs before scenario 12 (B-18, `DEMO_DAY.md` §5.6). The
   reports screen exists to compare, and a fresh seed gives it one row to compare until execution
   `7390` is approved. Approving it live is a better demo than a second seeded sitting would be.
3. **Nothing is claimed off a screen nobody has seen.** Where a step rests on rendering that the
   acceptance pass proved below the screen only, the risk line says so.

## Machine roles, fixed for the whole demo

- **A** runs the server (from a terminal, with the console window up, `DEMO_DAY.md` §5.3) **and**
  the staff client. Every teacher, coordinator and the principal sign in here.
- **B** runs one client. It is the student for acts 4, 5 and 6, and the second teacher for the
  edit-lock moment in act 8.
- One session per user (F1.3), so every account change is an explicit **Sign out** first.

---

## 0. Before the panel walks in

Not part of the 24 minutes. Ticked from `DEMO_DAY.md`, on the morning:

- Reseeded **this morning** with **Reload demo data**, not loaded (§3.4). The live sitting's window
  is relative to load time, so a database seeded last night has no live exam today.
- Server started **from a terminal** so the coloured log stream is on screen (§5.3), console window
  up beside it, font readable from the back of the room.
- Client on B launched once already, so it **auto-connects** at the next launch (§4.4).
- Both JARs came out of `target\` of the final commit (§5.1). A stale JAR demonstrates yesterday.
- `docs/DEMO_ACCOUNTS.md` on the table, password `demo123` for all eighteen.

---

## 1. Cold open (90 seconds)

**What the panel sees before anybody logs in: two machines that found each other.**

### 1.1 The server console · machine A
**Clicks:** nothing. It is already open, beside the terminal.
**Say:** "The server prints its own address and its own fingerprint. That is F13.1 and F13.2, and
the number under the address is the discovery identity from F13.3."
**If it goes wrong:** if the console picked a virtual adapter, use the address picker in the console
and say "the console offers every candidate interface because a demo laptop has four of them."

### 1.2 The client finds it · machine B
**Clicks:** launch the client. It broadcasts, finds A and lands on **Login**, showing
"Connected to &lt;server name&gt; · change server".
**Say:** "No address was typed. The client broadcast on UDP, the server answered, and the pin from
the first connection is what made this launch silent. F13.4, and manual entry is one click away
because discovery is a convenience and never a dependency."
**If it goes wrong:** "That is the case F13.4 was designed around," then click **change server**,
type A's address, connect. `DEMO_DAY.md` §4.5 is the rehearsed path, not an improvisation.

### 1.3 The log stream · machine A
**Clicks:** point at the terminal while B connects.
**Say:** "Every request the panel is about to see arrives on this stream, so nothing in the next
twenty minutes happens off camera."
**Risk, and say it before somebody asks:** a red `ERROR` line about log4j2 appears around thirty
seconds into start-up. **B-2, open, cosmetic.** The line to use: "that is a third-party library
looking for a logging binding it does not need. Our logging is logback, and every line after it is
ours."

---

## 2. Act 1 · Authoring (3 minutes 30 seconds)

**The claim: a teacher works inside the courses she teaches, and every edit keeps its history.**

### 2.1 The teacher shell · `dana.cohen` on A
**Clicks:** sign in. The rail reads Dashboard, Question Bank, Exams, Releases, Live Monitor,
Grading, Results, Study Bot, Settings.
**Say:** "The rail comes from the role plus her course relations, F1.2, and it has no Approvals item
because a plain teacher is not a coordinator. The rail decides what is offered and never what is
permitted: the server re-checks the role on every request."
**If it goes wrong:** nothing to warn about here any more. The rail act 2.1 reads out is
literally what is on screen: batch C swapped the last two placeholders (Take Exam, Live Monitor)
onto their real routes, and `RoleNavTest.nothingIsDisabledAnywhere` now asserts that no rail
carries a placeholder for any role.

### 2.2 Add a question · `dana.cohen`
**Clicks:** Question Bank → **Add** → type a stem, four distinct answers, mark one correct, pick
topic `Linear equations` and difficulty, **Save**.
**Say:** "The id is five digits, the course code and a server-allocated serial, S-8, and she cannot
send it: it is allocated under the course's row lock, not chosen by the client."
**If it goes wrong:** if the save refuses, read the sentence aloud. Every refusal on this screen
names the box and what to do, which is the point of C-8 and PRD §4.1.

### 2.3 Two identical answers · `dana.cohen`
**Clicks:** edit answer 2 so it repeats answer 1 → **Save**.
**Say:** "Refused with a sentence naming both boxes, C-8, and the same rule runs server-side, so a
client that skipped it changes nothing."
**Extra, only if the panel looks interested:** "That comparison is a story of its own. It is P-12 in
our problems log, and the fix is measured against MySQL's own collation rather than argued."

### 2.4 Version history · `dana.cohen`
**Clicks:** open question **11005**, change the stem only, **Save**, then open **Version history**.
**Say:** "Editing creates version three. Versions one and two are still in the bank and still
readable, C-2, and the exam that was released against version one is still pinned to version one,
S-14."
**If it goes wrong:** the refusal about answers she did not touch was **B-7**, fixed on 2026-08-26.
If it reappears, the JAR is stale, which is `DEMO_DAY.md` §5.1, and say so plainly.

### 2.5 Delete is blocked, and it says by what · `dana.cohen`
**Clicks:** select question **11001** → **Delete**.
**Say:** "Blocked, and the dialog names the exam that uses it by its display id, F2.5. A question
in use is not deletable and a question in use is not a mystery."

### 2.6 The builder, and the rule that blocks rather than warns · `dana.cohen`
**Clicks:** Exams → the list shows her exams with a chip per version, including `101101` **v1
Rejected** and **v2 Approved** with the coordinator's reason on the row → open `101102` (Draft)
with **Edit** → change one question's points so the total reads 96 → try **Save**.
**Say:** "The save is blocked, not warned, and the sentence says which way she is out: the points
add up to 96, add 4 more to reach 100. That is F3.1, and the same rule is enforced on the server,
so the client is a convenience."
**Then:** put the points back and leave the screen without saving.
**Do not click New.** See [what this script deliberately does not do](#what-this-script-deliberately-does-not-do):
the bank picker's add path is an open gap (F3.2), so an empty exam has no way to be filled and the
Add question button is correctly disabled.

### 2.7 Auto composition and the thin topic, told rather than clicked
**Clicks:** none. Have `ACCEPTANCE_TESTS.md` cases 3.5 and 3.6 open on the second monitor or on
paper.
**Say, verbatim from the two cases:** "Asked for three Recursion questions in Java, the server
answers `OK` with `feasible=false` and one shortfall row: topic Recursion, requested 3, available 2,
missing 1. Narrowed to one HARD Recursion question it answers `available=0`, scoped to the
difficulty rather than to the topic. No exam is created in either case, and that is true by
construction rather than by a rollback, because nothing on that path inserts. The thin topic is a
seed fixture built for exactly this, `SEED_CONTENT.md` §7.3, so F3.3 can fail live without anybody
touching the database."
**Then click it, because it is built now.** The auto tab is a segmented switch beside the manual
one, its criteria grid opens with the course-wide quota row, and generating an infeasible request
renders the shortfall table above instead of a number. This paragraph used to end "we would rather
say that than show you a button that is not there"; the button is there as of #53, and the two
cases above are what it does when it refuses.
**If it goes wrong:** fall back to reading cases 3.5 and 3.6 aloud, which is what this act was
before the tab landed and is still a complete answer.

---

## 3. Act 2 · Approval (2 minutes 30 seconds) [2M]

**The claim: the coordinator sees the student's paper, and a rejection is a sentence that arrives.**

### 3.1 The queue is her subject only · `rina.barak` on B
**Clicks:** sign in on B → **Approvals**. One row: `101201` Midterm: Calculus, by Dana Cohen.
**Say:** "Rina coordinates Mathematics and teaches nothing, so her queue is subject 10 and the
scoping is a join in the SQL, S-1 and F4.1. A version outside her subjects is never fetched, so
there is nothing to filter on the client."

### 3.2 The student-identical preview · `rina.barak`
**Clicks:** open the row.
**Say:** "This is the paper as the student sees it, rendered by the take-exam screen's own
component, plus a staff-only block with the teacher note and the answer key. F4.1 was a v1 failure
and the fix is structural: the paper travels in `ExamQuestion`, which has no field a correct answer
could occupy, and correctness exists only in the staff block."
**Risk:** the render of the preview through the shared component is the half case 4.2 leaves
outstanding. If it looks different from act 4's form, say "the wire is identical and the styling is
the open half," rather than claiming they are the same pixels.

### 3.3 Reject with no reason · `rina.barak`
**Clicks:** **Reject** → leave the box empty → confirm.
**Say:** "Refused, and refused server-side too. Whitespace is refused with the same sentence and
`no` is refused for being too short, because the rule is about a usable reason and not a non-empty
string, T-4.2."

### 3.4 Reject with a reason, and watch it land · `rina.barak` on B, `dana.cohen` on A
**Clicks:** type a real reason → **Reject**. Then look at machine A without touching it.
**Say:** "Dana's bell moved while nobody refreshed anything. The notification is a durable row and
the push is the live cue, and we keep both because a push is worth nothing to a client that was
closed and a row is worth nothing as a live signal. F4.2 and F11.1."
**Clicks on A:** open the bell, click the notification.
**Say:** "The notification deep-links to the exam, and the reason is on the exam itself, so it
survives the bell being dismissed."
**If it goes wrong:** the bell failing to open was **B-11**, fixed on 2026-08-26 with the seed's
types and a read path that skips one bad row instead of losing the page. If it fails, open Exams
instead: the reason is on the version row, which is the durable half.

---

## 4. Act 3 · Release (1 minute 30 seconds)

**The claim: only an approved version leaves the drawer, and the code is spoken, never shown.**

### 4.1 The picker offers approved versions only · `dana.cohen` on A
**Clicks:** Releases → **Release an exam**. The picker lists `101101 v2` and nothing else.
**Say:** "Only an approved version is releasable, T-5.1 and S-14, and it is a where clause rather
than a disabled button: the draft and the rejected version are not in the answer."

### 4.2 The window rules · `dana.cohen`
**Clicks:** set a close time before the open time → the sentence appears; fix it; try a code `12`,
then `ABCDE`.
**Say:** "Four alphanumerics, C-1, and the two window mistakes get different sentences because they
are different mistakes. A code left blank is generated from an alphabet with O, 0, I and 1 dropped,
because a code exists to survive being read out across a room."

### 4.3 The code reveal · `dana.cohen`
**Clicks:** **Create**. The panel headed **Read this code out** appears.
**Say, and then actually read it out:** "The code is delivered orally, S-17. It appears on this
screen and on no screen a student can reach, and `ReleaseRow`, the only wire type that carries it,
is produced by staff-only verbs."
**Then:** point at the row for the live sitting and read out **2075**, which is the sitting act 4
uses.
**If it goes wrong:** a newly created release opens on the next scheduler tick, up to thirty
seconds. Do not wait for it on stage. The demo sits the already-live `2075`.

---

## 5. Act 4 · Execution and the timer moments (5 minutes 45 seconds) [2M]

**The claim: the clock belongs to the server, and everything the student is told, she is told
immediately.**

### 5.1 Join by code · `maya.levi` on B
**Clicks:** sign out of `rina.barak`, sign in as `maya.levi` → Dashboard → the **Take an exam** card
→ type `2075` → **Enter**.
**Say:** "The code answers with a header and only a header: exam name, course, duration, question
count and the general text. The paper stays on the server, because `ExamHeader` has no field a
question list could travel in."
**Note — three doors, not one shut one:** Take Exam on her rail, the dashboard code box and the
notification for an opening sitting are all the same screen. Every entry starts at the code screen
and the dashboard hands its validated code over as a **confirmation step** (read-only code, one
"Confirm and continue" button; ruling 2026-08-28, manual round 1), which is why F6.4 is a property
of one screen rather than a guard on three navigations. Use the dashboard card on the day because
it is the shortest sentence; say the other two exist if anyone asks how she would find it cold.

### 5.2 Her own id, and only hers · `maya.levi`
**Clicks:** enter `noam.peretz`'s id first: `385612098` → refused. Then her own: `374301851`
(both also in DEMO_ACCOUNTS.md's table; M-2 put them on stage so nobody leaves the script to
search the seed document mid-act).
**Say:** "Refused, and no attempt row was created, so a mistake costs her nothing and starts no
clock. The check is against the caller's own record, F6.1, and there is no payload field that could
name a different student."

### 5.3 The clock starts here · `maya.levi`
**Clicks:** the form renders with seven questions. Answer two.
**Say:** "The countdown started at id entry and not at code entry, S-18, and it is server
authoritative: every answer save carries the server's own remaining time, so the client displays a
value it never computes."
**Say next, because B-14 is worth owning:** "It also tells her the truth about the window. If the
sitting closes before her paper would, the entry card says so before she starts. That was a defect
we found in our own acceptance walk and fixed on 2026-08-26."

### 5.4 The extension, with the panel watching both screens · `dana.cohen` on A
**Clicks on A:** Releases → the `2075` row → **Monitor** → set 15 in the minutes box → **Add time**
→ confirm the dialog, which names how many students are sitting.
**Say:** "Watch her timer, not mine."
**On B:** the chip pulses, +15:00 rises off it, and a toast names the teacher and the new end time.
**Say:** "Time added is never silent, F7.1. She knows it happened, who did it, and when the exam now
ends, without asking for anything. And the extension moved the window with it, so the minutes she
was promised are minutes she can actually use."
**If it goes wrong:** if the toast does not appear on B, the durable notification still did. Open
her bell and say "the push is the cue and the row is the record, and we ship both for exactly this
reason."

### 5.5 The monitor, counted rather than accumulated · `dana.cohen` on A
**Clicks:** stay on the monitor. On B, alt-tab away from the exam window and back.
**Say:** "Started, submitted and timed out are a count over the attempt rows on every snapshot, not
counters somebody increments, F7.3, so there is no increment race to lose. The neutral chip that
just appeared on her row is the attention signal, F7.1b: it is a count and a timestamp, never a
verdict, there is no automatic penalty, and the student sees nothing. It runs on her machine, so it
is a deterrent and a visibility aid rather than a control, and we say that rather than overclaiming."
**Say about the amber slot beside it:** "The amber chip on this row is C-4. If a student opens
another course's study bot while sitting this exam, she is told first that continuing informs this
teacher, and if she proceeds the row flags and this teacher is notified once." Then stage it — the
next step is exactly that, and it is worth the forty seconds.

### 5.5a The C-4 flag, taken live · `maya.levi` on B, `dana.cohen` watching on A
**Clicks on B:** Study Bot on her rail. It opens **Algebra 11's bot, locked** — she is sitting
Algebra, and that is act 5.6's story, so do not tell it yet. Instead: **the Course picker in the
chat header** → pick **Databases 22** → type a question → **Ask**.
**Say before she confirms:** "She is sitting Algebra and she has just opened a *different*
course's bot. The server does not refuse her: it tells her that continuing informs the teacher
running her exam, and it waits. That is C-4's whole design, F6.8 — a deterrent she can read, not a
trap."
**Clicks:** confirm.
**On A:** the amber chip appears on her row and the teacher's bell rings, once — an
`INTEGRITY_ALERT` to the teacher running that execution.
**Say:** "Once per attempt, not once per message. She acknowledged, so the answer comes back
normally, and the flag is a count and a timestamp on the monitor rather than a verdict. Asking that
bot forty more questions produces one flag, with the moment it started."
**Why this step exists, if anyone asks how new it is:** this was the most demonstrable requirement
in C-4 and it had never been shown on a screen. The chat used to open her first course
unconditionally, and her first course is Algebra — **which is the course she is sitting** — so the
one bot this screen would open for her was the *locked* one, a different rule with a different
outcome and a different sentence. Batch C's picker (`U-2`) is what makes the cross-course path
reachable by a person; acceptance case 14.7 had walked it below the screen since 2026-08-26.
**If it goes wrong:** if there is no picker in the header, she is in one course — check you are
signed in as `maya.levi`, who is in 11, 21 and 22. If no confirm dialog appears, she has already
acknowledged for this attempt; that is B-20 behaving correctly, and 5.6 is the contrast to play
instead.

### 5.6 The bot is locked, and it says why · `maya.levi` on B
**Clicks:** the Course picker back to **Algebra 11** (or leave the screen and re-enter from the
rail, which lands on the same course).
**Say:** "And this is the other half of the same rule, which is why the picker is worth having on
screen. Her own course's bot is locked while she is sitting that course's exam, C-4, and the
message names the exam and says it unlocks when she hands it in. It carries no unlock time on
purpose: a teacher can add minutes, and a stale unlock time is worse than none because she would
plan around it."
**Clicks:** back to the exam.

### 5.7 The time-up takeover · `dana.cohen` on A, `maya.levi` on B
**Clicks on A:** Releases → the `2075` row → **Close early** → read the warning → confirm.
**Say:** "Closing early behaves exactly like time expiry for whoever is still sitting, F5.5, and it
goes through the expiry path rather than a bespoke update, which is what makes her paper reach the
grading seam instead of staying unmarked for ever."
**On B:** the full-screen takeover appears with what was handed in.
**Say:** "No confirmation is asked, because it already happened. The exam is unreachable behind it,
the summary is what the server handed in, and there is one way out. Her attempt is TIMED_OUT and
not SUBMITTED, which decides which screen she gets and which row the teacher reads. In v1 the exam
simply stayed open."
**If it goes wrong:** if the takeover does not paint, read her state off the monitor on A, where the
row has already flipped, and say "the server closed her at the bell; the takeover is the client's
half."

---

## 6. Act 5 · Grading (3 minutes)

**The claim: marking is immediate, publishing is a decision, and the decision is auditable.**

### 6.1 Set the student side up first · `maya.levi` on B
**Clicks:** she is at the takeover. Back to dashboard → **My Grades**. **One row**: the Algebra
midterm at 60.
**Say:** "She has sat three sittings and one row is visible. Her Java paper scored 100 the moment
she handed it in, and nobody can see it, because auto-checking publishes nothing, C-3 and S-24.
Leave this screen open."
**Leave it open literally.** 6.4 depends on it: the second row arrives on this screen by itself,
with nobody touching machine B. Her bell also has one unread item on it from the seed — her Algebra
grade, which deep-links here — so the badge is already visible if the panel asks what the bell does.

### 6.2 The queue leaves things out · `avi.mizrahi` on A
**Clicks:** sign out of `dana.cohen`, sign in as `avi.mizrahi` → **Grading**. The Java sitting is
waiting: 8 sat, 8 marked, 8 to approve.
**Say:** "Every attempt already carries a score computed on submission, F8.1, against the question
versions the exam pinned rather than the latest ones. The queue lists closed sittings with something
unapproved, and what it leaves out is its value: a queue that never empties stops being read."

### 6.3 An override needs a reason · `avi.mizrahi`
**Clicks:** select a row → **Override** → change the score, leave the justification empty → try to
save. Then write a justification and a comment to the student, and save.
**Say:** "A manual change requires an explanation, S-23, refused on both sides. The machine's score
is kept beside the new one, F8.3, so the change stays visible. The justification is for the record
and never reaches the student; the comment is the one piece of free text she does read, S-22."
**Worth one extra sentence if there is time:** "That comment had a read path everywhere and a write
path nowhere until our own acceptance walk found it. It is P-7 in the problems log."

### 6.4 Approve, singly and in bulk, in front of the panel · `avi.mizrahi`
**Clicks:** approve the single row you overrode → then **Select all** → **Approve**, and read the
confirmation count aloud.
**Say:** "One verb serves one grade or fifty and it is idempotent: approving twice counts and is not
an error, and it does not re-stamp who approved it. When the last grade of a sitting is approved,
the statistics are computed and frozen into the execution in the same transaction."
**Now point at machine B without touching it:** "Her list just went from one row to two. That is the
whole publishing rule in one screen, and nobody refreshed anything, F8.4 and NFR-18."
**This step got stronger on 2026-08-26 and it is worth knowing why, because it changes what you can
promise.** Until batch D, *she had to have opened My Grades after the approval* for the second row
to be there: `PUSH_GRADE_PUBLISHED` was declared, documented and listened for, and no server class
sent it, so a student watching the screen saw her bell light up and the table underneath it stay as
it was (acceptance case 18.4, hardening item H13.5). The producer exists now, and it fires after the
approval commits. **So leave her screen open from 6.1 and do not touch machine B at all** — the row
arriving under the panel's eyes is the moment, and it is now a property of the product rather than
of the order you clicked in.
**This is the B-18 moment.** Say it out loud: "That approval also froze a second set of statistics,
and the principal's reports are about to have something to compare."

---

## 7. Act 6 · Results (2 minutes 30 seconds)

**The claim: the numbers a teacher reads are the stored ones, and a student reads only her own.**

### 7.1 The theme, placed where it earns its keep · `dana.cohen` on A
**Clicks:** sign out of `avi.mizrahi`, sign in as `dana.cohen` → Settings → switch to **Dark** and
pick a different accent swatch → back to **Results**.
**Say:** "Persisted per user, applied instantly, no restart. The histogram you are about to see is
themed to that accent, which is why the switch happens here rather than at the start."
**If it goes wrong:** if anything reads badly in dark, switch back and say "the polish sweep is the
open half of NFR-21 and it is on our list rather than off it."

### 7.2 The teacher's table · `dana.cohen`
**Clicks:** Results → exam `101101` → sitting `4821`.
**Say:** "Eight rows for eight students, with the effective score, the state, and now the attempt
and the solving time. `omer.katz` reads Timed out and 75 minutes, so the one paper in this dataset
that ran out of time is distinguishable from a paper that just went badly. That column was missing
until our own walk found it as B-16, and it is fixed."
**Say next:** "The scope is a where clause on the exam's author, S-35, so this list is the exams she
wrote, including sittings other teachers ran."

### 7.3 The histogram · `dana.cohen`
**Clicks:** the **Histogram** segment. Hover a bar. Toggle count and percentage.
**Say:** "Mean 72.5, median 72.5, sigma 17.5, pass rate 7 of 8. These are the stored figures, not a
recomputation, S-25 and C-5, and sigma is the population form because the class is the population
rather than a sample of one. The percentage toggle asks the server nothing: both scales are
functions of the ten stored buckets."
**If a panel member does the arithmetic:** the sum of squared deviations is 2450 over 8 students,
which is exactly 17.5. The sample divisor would give about 18.71 and would read as a bug.

### 7.4 Her own paper, and only hers · `maya.levi` on B
**Clicks:** the new row → the checked form.
**Say:** "Her answers, the correct ones, points per question, and the teacher's note where there is
one. Three gates stand in front of this screen: the grade is hers, it is approved, and the sitting
is closed. The third is the one people forget, because handing one student the answer key while the
sitting is open hands it to the room."
**Optional, and the first thing to cut:** the **Print layout** toggle, which flattens the cards and
drops the chrome, S-36.

---

## 8. Act 7 · Principal (2 minutes)

**The claim: a role with no mutating verbs at all, and a report that gained a row while you watched.**

### 8.1 School-wide, and read-only by construction · `principal.avia` on A
**Clicks:** sign out of `dana.cohen`, sign in as `principal.avia`. The rail is Dashboard, Data,
Reports, Settings → **Data** → the Questions tab, then Exams.
**Say:** "All forty questions across all four courses, on the same verb Dana calls, which answers
her with two courses. Scope is resolved server-side per role, S-5 and S-7, so the difference is in
the answer rather than in the screen."
**Say, because it is the strong form:** "There is no create, edit or approve control here, and the
reason is not that nobody drew one. `Role.PRINCIPAL` appears in exactly four authorization gates in
the whole server, guarding eight verbs, and every one of them is a read."

### 8.2 Reports, with two rows · `principal.avia`
**Clicks:** **Reports** → dimension **By teacher** → subject `Dana Cohen`, then switch to
`Avi Mizrahi`.
**Say:** "Average, median and the decile spread per sitting, F9.4. Avi's report has a row now that
it did not have twenty minutes ago, because approving those grades froze the statistics. On a fresh
seed this screen compares one sitting, which is honest and dull; we approved a second one in front
of you instead of seeding it."
**Clicks:** switch to **By course**, then **By student**.
**Say:** "One mechanism, three dimensions, one strategy class each. A student's report carries the
class figures and not her own score, because `ReportRow` has no component a personal score could
travel in."

---

## 9. Act 8 · The study bot (2 minutes)

**The claim: a real provider, server-side only, with the exam data structurally out of reach.**

### 9.1 The manager · `avi.mizrahi` on A
**Clicks:** sign out, sign in as `avi.mizrahi` → **Study Bot**. The Java bot with its sources, each
row carrying its kind, its extracted character count and its author.
**Say:** "Sources are parsed to text at upload time and not at ask time, S-28, and a file that will
not parse is refused on the spot with no half-created row left behind. One bot per course, S-30: a
second teacher of the same course extends this bot rather than making another."

### 9.2 The edit lock, live · `avi.mizrahi` on A, `tamar.shani` on B [2M]
**Clicks on A:** open a free-text source with **Edit**.
**Clicks on B:** sign out of `maya.levi`, sign in as `tamar.shani`, open the same Study Bot.
**Say:** "She sees who is editing, by name, and her editor is read-only. The badge is not decoration:
the server refuses behind it, and the refusal names the holder. F10.1 and F10.2, and the same
mechanism is what the question bank list uses to badge a row before the click rather than after it."
**Then on A:** save the edit.
**Say:** "The row keeps its id, its author and its version, and her co-teachers are told the sources
changed. Editing a source at all is something our acceptance walk found missing, B-21, and it was
built on 2026-08-26."
**If it goes wrong:** if the badge does not paint, do the refusal instead: have B try **Remove** on
the locked source and read the `CONFLICT` sentence out. That is the half that is proven.

### 9.3 The student asks something ⚑E16.17 · `maya.levi` or `tamar.shani`'s class on B
**Clicks:** on B, sign in as a student enrolled in Java, open **Study Bot**, ask a course question.
**Say:** "The answer comes from the course's own uploaded material and the course question bank. The
model is never given exam definitions, execution codes or grades, F12.8, and the system prompt says
in as many words that it has no information about exams."
**Say, because it is the sharper claim:** "A bank question reaches the model as four unmarked
options. The correct answer exists in the database and is not selected by the query that builds the
context, so there is nothing to strip."

### 9.4 The fallback, deliberately ⚑E16.17 · machine A
**Clicks:** if the checklist was run and a provider is out of credit, let it fall over. Otherwise
describe it against the log.
**Say:** "DeepSeek first, Anthropic on failure or timeout, and if both fail the student gets one
friendly sentence rather than a stack trace or an empty bubble, S-32. The exchange is still stored,
with its provider column reading none, so a failed ask is visible in her history rather than
silently dropped."
**If E16.17 was not run:** do not fake it. Say "the chain is unit-tested against mocked HTTP on both
providers, and the live key session is a checklist we run on the day. If it has not been run when
you see this, we say so."

---

## 10. The break-it invitation (1 minute)

Close on it. Say it in this shape:

> "Everything you have seen ran through the server. If you want to try to break it, here are four
> things we would try first, and we have machines ready for all four."

1. **"Show me a student reading another student's grade."** Sign in as any student on B and let a
   panel member replay a grade id. Every refusal is the same empty answer, so it is not an oracle
   either. Acceptance case 9.4 has five probes, including a classmate who sat the same paper.
2. **"Make the exam form leak an answer."** Offer the wire. `ExamQuestion` has ten components and
   none of them could hold a key, and case 6.7 searched the serialised bytes for the field names.
3. **"Sign the same teacher in twice."** Two machines, one account, F1.3, and the second is refused
   with a sentence that names no detail. Killing the first client frees the session immediately.
4. **"Send a teacher verb from a student session."** Any of them. The role gate runs before the
   payload is read, which is why a malformed payload answers FORBIDDEN and not VALIDATION.

Then hand them `docs/DEFENSE_QA.md` §4's list if they want to know what we already know is missing.
Volunteering the gap list is a stronger position than being walked into it.

---

## The cut list: 25 minutes down to 15

Cut in this order and stop when the clock is met. Nothing below the line removes a requirement id
that no other step carries.

| # | Cut | Saves | Why it is safe to lose |
|---|---|---|---|
| 1 | Act 8's manager and lock moment (9.1, 9.2), keep 9.3 | 1:00 | The lock story has a second home in the question bank, and the Q&A sheet can carry it |
| 2 | Act 1's version history and delete block (2.4, 2.5) | 1:30 | C-2 is proved again by the preview pin and by the grading claim about pinned versions |
| 2b | Act 4's C-4 step (5.5a) | 0:45 | **Cut last of the early ones, and know what it costs.** It is the only live demonstration of C-4's cross-course half, which was UI-unreachable until 2026-08-26 and had never been shown; acceptance case 14.7 is the fallback, and act 5.6's locked bot still carries C-4's other half |
| 3 | Act 7's Data tab (8.1), keep Reports | 1:00 | S-7's strong form is a sentence, not a screen, and the Q&A sheet has it |
| 4 | Act 3's window and code refusals (4.2) | 1:00 | The code reveal carries S-17, which is the part that matters |
| 5 | Act 6's print layout and the timed-out student's form | 1:00 | The Attempt column in 7.2 already shows the timed-out row |
| 6 | Act 2's reject-without-reason probe (3.3) | 0:30 | The reason arriving in 3.4 is the claim; the refusal is the guard |
| 7 | Act 4's negative id probe (5.2) | 1:00 | Kept for the break-it invitation instead |
| 8 | The theme switch (7.1) | 0:30 | Say "themed to the active palette" and move |
| 9 | Act 5's override dialog (6.3), keep the approve | 1:00 | Never cut 6.4: it is the B-18 moment and the live publish |

**Never cut, in any circumstance:** the code reveal (4.3), the extension (5.4), the takeover (5.7),
the bulk approve (6.4), My Grades going from one row to two (6.4), and the second report row (8.2).
Those six are the demo. Everything else is evidence around them.

---

## What this script deliberately does not do

Each line is a path the script avoids, and the sentence to use if the panel walks into it anyway.
The full drill is `docs/DEFENSE_QA.md` §4.

| Not done | Why | The sentence |
|---|---|---|
| Show an illustrated question | **B-8 is open.** Ten seeded questions are flagged as illustrated and carry no bytes, three of them on the demo paper | "The image path works end to end and the seed ships no image bytes. It is a fixture gap with a ticket, not a feature gap." |
| Open a student's marked paper from the grading screen | **F8.2 is PARTIAL.** E12.6's review screen is not built; the assembler exists and the student's checked form uses it | "A teacher changes a score from the table today and cannot read the paper first. The assembler is shared with the student's form; only the screen is missing." |
| Drive the login throttle to five | Case 1.4 is partial: the throttle is unit-tested and never walked | "Five wrong passwords, thirty seconds, and the lockout answers before any lookup so it cannot confirm a password. It is unit-tested and not yet walked at a keyboard." |
| Promise the timed-out student's screen as rehearsed | Case 9.5 passed below the screen; the render is a manual-pass item | Show it if the dry run rehearsed it, and say "proved through the assembler and the database; the render is on our polish list" if not |

---

## Two dry-run notes, for E22.6

- **The two E16.17 steps are the only ones with an external dependency.** Run the live-key checklist
  the morning of, and if it is not run, cut act 8 to 9.1 and 9.2 and say why.
- **Rehearse acts 4 and 5 back to back at least twice.** They carry six of the nine unmissable
  moments, they span both machines, and they are the only place the demo depends on timing rather
  than on clicking.
