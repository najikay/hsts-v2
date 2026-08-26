# ACCEPT-S6-S7 — scenarios 6 and 7, pre-walked below the screen

**Scenarios:** 6 — Exam execution (T-6, 10 cases) · 7 — Extending exam duration (T-7, 4 cases)
**Walked:** 2026-08-26, worktree `hsts-acc1` (detached at `origin/main`, `6bff812`)
**Probe suite:** `src/test/java/acceptance/` — 22 probes, green under `-Dtest='acceptance.**'`
**Evidence log:** `target/acceptance/S6-S7-evidence.txt` (324 lines) — every number below is copied
out of it, none recomputed while writing this up.
**Feeds:** the `Actual` and `Status` columns of `docs/ACCEPTANCE_TESTS.md` §6 and §7. That file is
not edited here; the cells below are paste-ready.

These two scenarios are the v1-killer features. Both v1 defences failed on them — the timer stayed
open and students saw answers — so both were audited harder than the rest of the campaign, and
this report names two defects the green suites cannot see, one of them in the extension path
itself.

---

## Method, and what it does not cover

**The assembly is the production one.** `AcceptanceBase` builds the exam feature exactly as
`HSTSServer.registerExamFeature` builds it: one `JpaExamStore` shared by the attempt, monitor,
extend and close services; the monitor wired both ways into the attempt service
(`attempts.publishTo(monitors)` / `new MonitorService(store, attempts, …)`); the extend service
handed `attempts.timers()` rather than a second `TimerService`; the release manager over the same
`ExecutionCloseService` instance. Everything is registered on a real `MessageRouter` and driven
through `MessageRouter.route`, which is the method the socket layer calls. **No handler is invoked
directly anywhere in the suite** — every probe travels its verb, through the router's
authentication gate, with identity resolved from a real `SessionManager` session on a socket.

**The push gateway is real.** `RecordingPushGateway` subclasses the production `PushGateway` over a
real `SessionManager`, so "the student was told" is decided by the gateway's own delivery rule,
including the branch that silently skips a user with no socket. That is what makes case 7.1b
(offline student) mean anything. The notifications are the production `NotificationService` over
`JpaNotificationStore`, so the durable half is verified as **rows in the `notifications` table**,
not as a recorder's say-so.

**What this does not exercise:** the socket layer (E1's suite owns it) and JavaFX rendering (the
manual pass owns it). Cells below that depend on either say so.

**Two clocks, made to agree.** Since the evening batch landed, every seed window is resolved from
the loader's clock — `ExecutionsSection` sets execution `2075` to `times().fromNow(-1h)` →
`fromNow(+1h)`. So the seed is loaded on `Clock.fixed(ANCHOR)` and the services run on a
`MutableClock` starting at the same anchor. **The live-now fixture works; no workaround was
needed.** The anchor is `2026-08-20T09:00:00Z` rather than the seed suite's 15:30Z on purpose, so
that execution `5164` is genuinely *before* its open time, which is what case 6.4 asks for.

```
anchor (loader clock and service clock alike): 2026-08-20T09:00:00Z
  execution 4821 CLOSED    opens 2026-08-06 12:00:00.0  closes 2026-08-06 14:00:00.0  extra 0
  execution 7390 CLOSED    opens 2026-08-17 13:00:00.0  closes 2026-08-17 14:30:00.0  extra 0
  execution 5164 SCHEDULED opens 2026-08-20 17:00:00.0  closes 2026-08-20 19:00:00.0  extra 0
  execution 2075 LIVE      opens 2026-08-20 11:00:00.0  closes 2026-08-20 13:00:00.0  extra 0
seed loaded: LOADED {…, exam_executions=4, exam_attempts=16, attempt_answers=108, notifications=8}
```

> **Reading caveat, not a finding.** Those four rows are printed from a *native* query, so the
> JDBC driver renders `datetime(3)` in the JVM's zone (UTC+3 here): `2075` reads as 11:00→13:00
> local for the 08:00Z→10:00Z instants. Every JPQL read in the suite round-trips as a correct
> `Instant` (`ended_at 2026-08-20T10:15:00Z`), so nothing is stored wrong. It is flagged only so
> nobody quotes the local strings as UTC. Whether the same round trip holds across a *server* in
> another zone is an E2 question and is out of scope here.

**Seed dependency, noted as instructed.** All of scenario 6 and 7 rests on execution `2075` being
live at load time, which is true only because windows are load-relative. A database seeded a
fortnight before a defence presents a live exam whose window closed a fortnight ago —
`ExecutionsSection`'s own javadoc says so and names `SeedMode.RESEED` as the pre-demo step. **The
demo script must reseed on the day.**

**Cost control.** The dataset is loaded once per class (`SeedLoadedTestBase` measured a full
reseed at 290 s for seventeen tests) and execution `2075` is reset to its as-loaded state between
probes — its attempts and answers deleted, `extra_minutes` to zero, status to `LIVE`, anything the
suite released deleted, notifications above the post-load high-water id removed. The whole service
graph is rebuilt with it, so no registry entry, timer or recorded push leaks between cases.

---

## Scenario 6 — Exam execution (T-6)

**Summary: 10 of 10 pass.** One defect found (B-14, Medium) and one seed gap (B-15, Low); neither
fails a case as written, and both are recorded because a green suite cannot see either.

### 6.1 — code → id → the paper

**Status: ✅**

> **Passed.** Run through `EXAM_JOIN` and `ATTEMPT_START` on the router against the reseeded
> database. The code answered with the header and **only** the header — exam `Midterm: Algebra`,
> course `11 Algebra`, `75 min`, `7 questions`, state `NOT_STARTED`, and the general text
> *"Read each question to the end. Only a basic calculator may be used."* — while the paper itself
> stayed on the server. That is structural rather than careful: `ExamHeader`'s components are
> `[executionId, examName, courseCode, courseName, durationMinutes, generalText, questionCount,
> attemptState]`, and there is no field a question list could travel in, so the join screen
> **cannot** be given the paper early. The national id then returned the full `AttemptForm`: seven
> questions in exam order, `11001` (15 pts) through `11011` (10 pts), each with its four options
> and its points, timing `remaining 4500000 ms` of `total 4500000 ms`. **The illustrations could
> not be shown** — zero of seven questions carry image bytes, and zero rows in the whole seeded
> bank have a non-null `image`, which is `QuestionBankSection`'s documented decision rather than a
> code fault. See **B-15**.

Evidence:

```
EXAM_JOIN(2075) -> ok; header = exam 'Midterm: Algebra', course 11 'Algebra', duration 75 min,
                   7 questions, state NOT_STARTED
  general text: "Read each question to the end. Only a basic calculator may be used."
  ExamHeader's components are [executionId, examName, courseCode, courseName, durationMinutes,
  generalText, questionCount, attemptState] — no question list among them
ATTEMPT_START(national id 374301851) -> ok; attempt 27, state IN_PROGRESS, 7 questions,
                   0 saved answers
  timing: serverNow 2026-08-20T09:00:00Z, endsAt 2026-08-20T10:15:00Z,
          remaining 4500000 ms, total 4500000 ms
  Q1 11001 (15 pts) "Solve: 3x + 6 = 18" options: [x = 4 | x = 6 | x = 2 | x = 12] image absent
  … Q7 11011 (10 pts) "For which values of x does (x-1)/(x+2) ≥ 0 hold?" … image absent
  illustrations: 0 of 7 questions on the wire carry image bytes; question_versions rows with a
  non-null image across the WHOLE seeded bank: 0
```

**Case-insensitivity (C-1) is a separate probe**, because the seed's four codes are all digits and
case cannot be observed on any of them. Exam 1 v2 was released again through the production
`RELEASE_CREATE` with the teacher typing lower case:

```
RELEASE_CREATE with the teacher typing "ab7q" -> stored code is "AB7Q" (execution 5, SCHEDULED)
ReleaseScheduler.tick() opened 1 release(s); execution 5 is now LIVE
  EXAM_JOIN("AB7Q")     -> ok, exam 'Midterm: Algebra'
  EXAM_JOIN("ab7q")     -> ok, exam 'Midterm: Algebra'
  EXAM_JOIN("Ab7Q")     -> ok, exam 'Midterm: Algebra'
  EXAM_JOIN("  ab7q  ") -> ok, exam 'Midterm: Algebra'
```

**Method noted:** this exercises the services, the repositories and the database, not the socket
layer and not JavaFX. The screen's two-step entry and its route guard are covered by
`ExamEntrySessionTest` and `TakeExamInteractionTest`.

---

### 6.2 — another student's national id

**Status: ✅**

> **Passed.** `maya.levi`, correctly signed in and correctly joined, presenting `noam.peretz`'s
> national id `385612098`: refused `VALIDATION` — *"That ID number is not yours. Enter your own ID
> number and try again."* **No attempt row was created** (`attempts on execution 4 after the
> refusal: 0`), so the refusal costs her nothing and starts no clock. Her own id then worked in
> the same probe, so the gate refuses without over-refusing. The check is against the **caller's
> own** record — `AttemptService.startInTx` reads `data.user(studentId)` where `studentId` is
> `caller.userId()` — so a classmate's number identifies nobody, and no payload field exists that
> could name a different student.

```
maya.levi presenting noam.peretz's id 385612098 -> VALIDATION
  "That ID number is not yours. Enter your own ID number and try again."
  attempts on execution 4 after the refusal: 0
  her own id 374301851 -> ok, attempt 24
```

---

### 6.3 — not enrolled

**Status: ✅**

> **Passed, and twice over.** `noam.peretz` is enrolled in courses 12 and 21 and deliberately not
> in 11. `EXAM_JOIN(2075)` refused `FORBIDDEN` — *"You are not enrolled in this course, so you
> cannot sit this exam. Speak to your teacher if that is wrong."* The stronger half: sending
> `ATTEMPT_START` **straight past the join screen**, which is what a replayed request looks like,
> returns the same refusal from the same code. Every gate is re-run at start rather than trusted
> from the previous screen, because minutes can pass between the two. No attempt row was created.

---

### 6.4 — outside the window

**Status: ✅**

> **Passed, four distinct refusals on four codes.** At `09:00Z`: execution `5164` (SCHEDULED,
> opens 14:00) → `CONFLICT` *"That exam has not started yet. Wait for your teacher to tell you it
> is open, then enter the code again."* Execution `4821` (closed a fortnight ago) → `CONFLICT`
> *"That exam is no longer open. If you think this is wrong, speak to your teacher."* The two
> sentences are **different**, which is the point: a student standing in an exam hall told the
> wrong one loses minutes she cannot get back. An unknown code answers `NOT_FOUND` with a third
> sentence, and a malformed one `VALIDATION` with a fourth, before the database is touched at all.

```
EXAM_JOIN(5164, scheduled, opens 14:00) -> CONFLICT "That exam has not started yet. …"
EXAM_JOIN(4821, closed a fortnight ago) -> CONFLICT "That exam is no longer open. …"
EXAM_JOIN(ZZZZ, no such code) -> NOT_FOUND "No exam is using that code. …"
EXAM_JOIN(12, wrong length)   -> VALIDATION "An exam code is 4 letters or digits. …"
```

---

### 6.5 — the countdown starts at id entry

**Status: ✅**

> **Passed, measured to the millisecond.** The code was entered at `09:00:00Z` and the join answer
> — an `ExamHeader` — **carries no `AttemptTiming` at all**, so nothing is counting yet. Ten
> minutes were then put on the clock and the national id entered at `09:10:00Z`. The form came
> back with `endsAt 2026-08-20T10:25:00Z`, which is id-entry + 75 min exactly; code-entry + 75 min
> would have been `10:15:00Z`. `remainingMillis` was `4500000` — a **full** 75 minutes, not 65 —
> and `exam_attempts.started_at` in the database reads `2026-08-20T09:10:00Z`, so the row agrees
> with the wire. Server-authoritative was confirmed on the write path too: twenty minutes later an
> ordinary `ANSWER_SAVE` answered with `remaining 3300000 ms` and its own `serverNow`, so **every
> keystroke is a clock re-sync** and the client never computes a remaining time.

```
code entered at 2026-08-20T09:00:00Z; the join answer (ExamHeader) carries no AttemptTiming at all
national id entered at 2026-08-20T09:10:00Z (10 minutes later)
  timing.endsAt = 2026-08-20T10:25:00Z; id-entry + 75 min = 2026-08-20T10:25:00Z;
                                        code-entry + 75 min would be 2026-08-20T10:15:00Z
  timing.remainingMillis = 4500000 (a full 75 minutes, not 65)
  exam_attempts.started_at = 2026-08-20T09:10:00Z (the database agrees)
  after 20 more minutes, ANSWER_SAVE returns remaining 3300000 ms
```

**UI-only, recorded as such:** the amber-at-25 % and red-at-5-minutes thresholds are a client
rendering rule and nothing below JavaFX can observe them. They are not unverified, though: they
live as `CountdownLogic.AMBER_FRACTION = 0.25` and `CountdownLogic.RED_THRESHOLD = 5 min` in an
FX-free, clock-injected class with its own suite, `CountdownLogicTest`. **The colours themselves
are for the lead's screen review.**

---

### 6.6 — kill the client, come back

**Status: ✅**

> **Passed, and it is the autosave that makes it survivable.** Three answers were saved one minute
> apart — `[1=3, 2=1, 5=4]` in `attempt_answers` — and then the client process was killed: the
> socket detached with **no submit and no logout**. Twelve minutes later she signed in on a brand
> new socket and re-entered. `ATTEMPT_RESUME` returned **the same attempt** (id unchanged), state
> `IN_PROGRESS`, all seven questions and **all three saved answers with their exact selections**.
> The clock is the half worth checking hardest: 15 minutes had elapsed since it started, and the
> form came back with `remaining 3600000 ms` = **60 minutes of a 75-minute paper**. She did *not*
> get her 15 minutes back, which is F6.3's actual promise — the deadline is derived from
> `started_at` and survives in the database whether or not any process was running.

```
stored answers before the crash: [1=3, 2=1, 5=4]
client killed — session detached, no ATTEMPT_SUBMIT, no logout
ATTEMPT_RESUME on a new socket -> attempt 22 (same attempt: true), state IN_PROGRESS,
                                  7 questions, 3 saved answers
  restored: question version 1 selected 3 / version 2 selected 1 / version 5 selected 4
  15 minutes elapsed since the clock started; timing.remaining = 3600000 ms = 60 min, of 75
```

---

### 6.7 — inspect the wire (the v1 leak)

**Status: ✅**

> **Passed, five probes, and the strongest of them is structural.** The answer key for this paper
> is `[1, 2, 1, 3, 1, 2, 3]`, read straight from `question_versions`; none of it is reachable from
> a student's wire. **(a)** `ExamQuestion` is a record and its components are the whole of what it
> can hold: `[questionVersionId, displayId, ordinal, points, text, option1, option2, option3,
> option4, image]` — ten, and no field an answer key could occupy. **(b)** The projection it is
> mapped from, `TakeExamQuestion`, has `[…, answer1, answer2, answer3, answer4, image]` and no
> correctness field either, so neither side of `ExamPaper.toWire` could leak one *by assembly*.
> **(c)** The literal instruction of the case — capture the payload — was carried out: the
> `ATTEMPT_START` response was serialised (1903 bytes) and the bytes searched. Java serialisation
> writes every reachable class's **field names** into the stream, so this is a real wire probe, not
> a type check: `correct`, `Correct`, `answerKey`, `isCorrect` and `solution` are all **absent**.
> **(d)** Every value reachable per question was enumerated and logged; the only integers present
> are `questionVersionId`, `ordinal` and `points`, so there is no unexplained 1-4 anywhere the key
> could hide. **(e)** And the join screen answers with an `ExamHeader`, on which the paper does not
> exist at all. **This is the v1 defect fixed structurally rather than by discipline: no handler in
> this feature can leak a key, because no type on the path has anywhere to put one.**

```
the answer key for this paper, read straight from question_versions: [1, 2, 1, 3, 1, 2, 3]
(a) ExamQuestion's record components are exactly [questionVersionId, displayId, ordinal, points,
    text, option1, option2, option3, option4, image] — 10 of them, and none is an answer key
(b) TakeExamQuestion has [questionVersionId, displayId, ordinal, points, text, answer1, answer2,
    answer3, answer4, image] — no correctness field on that side either
(c) the serialised ATTEMPT_START payload is 1903 bytes; searching it for a correctness field name
    contains "correct": false / "Correct": false / "answerKey": false / "isCorrect": false
    contains "solution": false
(e) EXAM_JOIN answers with a ExamHeader … — the paper is not on that wire at all
```

---

### 6.8 — the timer runs out

**Status: ✅**

> **Passed, with the client gone, which is the case that matters.** An attempt was started with a
> derived deadline of `10:15:00Z`, three answers saved, and the timer service confirmed holding
> exactly that deadline. The clock was then moved to the deadline and the **scheduled task fired
> with no verb from the student at all**. `exam_attempts` afterwards: `status TIMED_OUT, ended_at
> 2026-08-20T10:15:00Z, actual_minutes 75` — closed **at the bell**, not when the task happened to
> run. `PUSH_FORCE_SUBMITTED` reached her exactly once, carrying the outcome the takeover renders:
> `3/7 answered, 4 unanswered, wasForced true`, with the per-question summary naming `11007`,
> `11009`, `11010` and `11011` as not answered. **No later change is accepted:** an answer sent
> after the bell was refused `CONFLICT` — *"Time is up. Your exam was handed in automatically with
> the answers you had saved."* — and the stored answers are byte-for-byte unchanged. A *submit*
> sent after the bell answers **OK** with `state TIMED_OUT` rather than an error, which is right:
> her paper was handed in either way and telling her it failed would be false and frightening.

```
attempt 17 started; derived deadline 2026-08-20T10:15:00Z
three answers saved: [1=1, 2=2, 5=3]
armed timers: 1; deadline held by the timer service: 2026-08-20T10:15:00Z
after expiry, exam_attempts says: status TIMED_OUT, ended_at 2026-08-20T10:15:00Z,
                                  actual_minutes 75
PUSH_FORCE_SUBMITTED delivered to maya.levi: 1
  outcome: state TIMED_OUT, exam 'Midterm: Algebra', 75 solving minutes, 3/7 answered,
           4 unanswered, wasForced true
    Q4 11007 NOT ANSWERED / Q5 11009 NOT ANSWERED / Q6 11010 NOT ANSWERED / Q7 11011 NOT ANSWERED
an answer sent after the bell -> CONFLICT "Time is up. Your exam was handed in automatically …"
  stored answers are unchanged: [1=1, 2=2, 5=3]
```

**And the case the scheduled task cannot cover** was probed separately: with every task discarded
(the process died), the clock advanced 105 minutes past a 75-minute start, and the *resume* closed
it — `ended_at 2026-08-20T10:15:00Z, actual_minutes 75 (not 105)`. The attempt was recorded as
ending when the bell went, not when the server came back. This is the difference between "the
server restarted" and v1's "the exam never closed".

**UI-only:** the full-screen takeover, the absence of a confirmation and the single "Back to my
dashboard" are client rendering (`ExamDoneView`, covered by `TakeExamInteractionTest`). What is
verified here is that the client is handed everything that screen needs, unasked.

---

### 6.9 — submit with time remaining

**Status: ✅**

> **Passed.** Four of seven answered, submitted 41 minutes 30 seconds in with time still on the
> clock. `ATTEMPT_SUBMIT` returned `state SUBMITTED`, the handed-in instant, `42 solving minutes`,
> `4/7 answered, 3 unanswered, wasForced false`, and the full seven-entry summary marking exactly
> `11009`, `11010` and `11011` unanswered. **The 42 is the interesting number**: 41 min 30 s
> *rounds* rather than truncates, because a paper handed in after 41 minutes 30 seconds took 42
> minutes by any reading a teacher would accept (S-19). `exam_attempts` agrees, and the expiry
> timer was disarmed — `timers armed after the submit: 0` — so nothing can force-submit an attempt
> she has already handed in.

```
four of seven answered; submitting at 2026-08-20T09:41:30Z, 41 min 30 s after the start
ATTEMPT_SUBMIT -> state SUBMITTED, handed in 2026-08-20T09:41:30Z, 42 solving minutes,
                  4/7 answered, 3 unanswered, wasForced false
  exam_attempts: status SUBMITTED, ended_at 2026-08-20T09:41:30Z, actual_minutes 42
  timers armed after the submit: 0 — the expiry task was disarmed
```

**UI-only:** the two-step confirm dialog — the answered/unanswered chip grid, the chips being
clickable to jump, the remaining time and the "unanswered score 0" note, Submit / Keep working —
is client-side. The server's contribution is the summary list above, which is present and
complete; the dialog is covered by `TakeExamInteractionTest` and confirmed at the manual pass.

---

### 6.10 — one attempt per student

**Status: ✅**

> **Passed, and the rule holds in the table rather than in a message.** After submitting, the same
> code was entered again: `EXAM_JOIN` answered **ok** with `header.attemptState = SUBMITTED`, and
> `ATTEMPT_START` answered **ok** with *the same attempt id*, state `SUBMITTED` and its outcome
> attached — not an error. That is deliberate and is F6.7 answered properly: the unique key on
> `(execution_id, student_id)` guarantees there is only ever one attempt, and the handler reports
> the one she has rather than refusing a student who did nothing wrong. **`attempt rows for this
> execution: 1`.** The closed attempt refuses writes on its own path — `CONFLICT`, *"You have
> already handed this exam in. Your teacher will publish the grade."*
>
> **Where the "Already submitted" sentence actually lives:** the client, not the server. The
> expected result names a message the join path never sends; `ExamEntrySession` reads the
> `attemptState` the server puts on the header and, when it `isFinished()`, moves to
> `EntryPhase.BLOCKED` with *"That exam is finished. Your grade appears on your dashboard once it
> is approved."* So the case passes in full, with the halves in the places they belong: the server
> owns the fact, the client owns the sentence. Confirmed by reading `ExamEntrySession` lines
> 169-173 and `ExamCopy.EXAM_CLOSED_FOR_YOU`; the rendering is for the manual pass.

---

### C-4 — the study bot during her own exam

Not one of T-6's ten cases (the bot scenarios are 13 and 14), walked here because F6.8 belongs to
this feature.

**Status: ✅ (seam-level; the full verb belongs to scenario 13/14)**

> **Passed.** Before starting, `coursesInProgressFor(maya.levi)` is empty. Mid-attempt it is
> `[11]`, and the exact seam `BotService.ask` consults —
> `AttemptTracker.activeAttemptFor(studentId, "11")` — returns her live attempt on
> `Midterm: Algebra`, so `BOT_ASK` for course 11 takes the lockout branch and **never reaches a
> provider**. The sentence it composes reads *"The Algebra study bot is locked while you are taking
> Midterm: Algebra. It unlocks as soon as you hand that exam in."* The other branch is right too:
> `activeAttemptFor("21")` is empty while `activeAttemptsFor(maya)` is non-empty, which is exactly
> the state that shows her the integrity notice and then tells the teacher — HSTS allows another
> course's bot, warns her first, and reports it. **And the lock lifts when the sentence says it
> does:** after `ATTEMPT_SUBMIT`, both queries are empty again.
>
> **Method noted deliberately:** this exercises the tracker `BotService` reads and the sentence it
> composes, not `BOT_ASK` end to end — that verb needs a provider chain, and its C-4 branches are
> covered by `BotServiceTest`. The full walk belongs to scenarios 13/14.

---

## Scenario 7 — Extending exam duration (T-7)

**Summary: 4 of 4 pass as written.** One defect found in this feature's own path (**B-14**): the
minutes a teacher grants are not guaranteed to reach the student, because the execution's window
can close first. Case 7.1 passes because the case as written does not go that far.

### 7.1 — the teacher adds 15 minutes

**Status: ✅ (with B-14 raised against the same path)**

> **Passed, on six separate observations.** Two students were mid-attempt on execution `2075`,
> thirty minutes in, both with deadlines of `10:15:00Z`. `dana.cohen` opened the monitor —
> `extraMinutes 0, durationMinutes 75, closesAt 10:00:00Z` — and added fifteen minutes. The verb
> answered with a fresh `ExecutionMonitor` reading `extraMinutes 15, durationMinutes 90`.
>
> **(a) Derived, never stored.** The only write was `exam_executions.extra_minutes: 0 -> 15` — one
> column, one row. That is what moved both students, and it is structural: `AttemptRecord`'s
> components are `[attemptId, executionId, studentId, startedAt, endedAt, actualMinutes, status]`
> and `exam_attempts`' columns, read from `information_schema`, are `[id, execution_id, student_id,
> started_at, ended_at, actual_minutes, status]`. **There is no deadline field on the wire and no
> deadline column in the schema**, so there is nothing to migrate and nothing that can be missed.
>
> **(b) Every live deadline moved.** `10:15:00Z → 10:30:00Z` for both, exactly +15, and the timers
> were re-armed to the recomputed instant rather than left pointing at the old one.
>
> **(c) The students were told, live.** `PUSH_TIMER_EXTENDED` reached each of them exactly once,
> carrying exam `Midterm: Algebra`, teacher `Dana Cohen`, `+15 min` and the new timing. The push
> was recorded through the **production** `PushGateway`, so this is delivery to a socket, not an
> intention.
>
> **(d) And durably.** Two rows in `notifications`, type `TIME_EXTENDED`, title *"Extra time
> added"*, body *"You have 15 more minutes for Midterm: Algebra."*, `read_at null`. Both halves,
> deliberately — the push is worth nothing to a dropped socket, the row is worth nothing as a live
> cue.
>
> **(e) The monitor repainted itself.** `PUSH_MONITOR_UPDATED` reached `dana.cohen` with the new
> snapshot; she sent no second request.
>
> **(f) The refusals hold.** `rina.barak`, who coordinates the subject but owns neither the release
> nor the exam, is refused `FORBIDDEN`; `+0` and `-30` minutes are refused `VALIDATION` before
> anything is read.

```
maya.levi attempt 51 deadline 2026-08-20T10:15:00Z
noa.friedman attempt 52 deadline 2026-08-20T10:15:00Z
dana.cohen opens the monitor: extraMinutes 0, durationMinutes 75, closesAt 2026-08-20T10:00:00Z
EXECUTION_EXTEND(+15) -> ok … extraMinutes 15, durationMinutes 90, closesAt 2026-08-20T10:15:00Z
(a) exam_executions.extra_minutes: 0 -> 15 — one column, one row
    exam_attempts' columns are [id, execution_id, student_id, started_at, ended_at,
    actual_minutes, status] — no deadline column exists in the schema either
(b) maya's deadline 2026-08-20T10:15:00Z -> 2026-08-20T10:30:00Z (+15 min)
    noa's deadline  2026-08-20T10:15:00Z -> 2026-08-20T10:30:00Z (+15 min)
(c) PUSH_TIMER_EXTENDED to user 29: 1 — by 'Dana Cohen', +15 min, new endsAt 10:30:00Z
(c) PUSH_TIMER_EXTENDED to user 25: 1 — by 'Dana Cohen', +15 min, new endsAt 10:30:00Z
(d) title "Extra time added", body "You have 15 more minutes for Midterm: Algebra.",
    navRef attempt/9, read_at null
(e) PUSH_MONITOR_UPDATED to dana.cohen: 1 (she sent no second request)
(f) rina.barak -> FORBIDDEN "This exam is not yours to manage. …"
    +0 minutes  -> VALIDATION "Enter how many minutes to add, between 1 and 480."
```

**And the offline student**, which is the half a push cannot cover, was probed separately: with
`maya.levi` holding no socket at all, `PUSH_TIMER_EXTENDED` reached her **0 times** and a durable
row was waiting instead — *"You have 20 more minutes for Midterm: Algebra."* When she came back,
`ATTEMPT_RESUME` returned `10:15:00Z → 10:35:00Z`, the full twenty minutes, with **nothing
migrated**: the deadline is start plus allotted and allotted is read fresh on every answer.

**UI-only:** the chip flash, the animated "+15:00" and the toast naming who did it are client
rendering. What is verified is that the client is handed everything those need — teacher name,
minutes added and the new timing — unasked, on the push.

> ⚠ **Read alongside B-14.** In this very probe the extension left `maya`'s deadline at `10:30:00Z`
> while the monitor's own `closesAt` moved only to `10:15:00Z`. The teacher's screen and the
> student's countdown now disagree by fifteen minutes, and the window wins. See B-14.

---

### 7.2 — the exam in the drawer is unchanged

**Status: ✅**

> **Passed, and proved by a column that was *not* written.** Exam 1 v2 before the extension:
> `duration_min 75, status APPROVED, lock_version 0`. After: byte-for-byte identical, `lock_version
> 0`. **The `lock_version` is the evidence** — it is an optimistic-lock counter, so no update could
> have touched that row and left it unchanged. The fifteen minutes live on
> `exam_executions.extra_minutes = 15` instead. The stronger form of the same claim: execution
> `4821`, the **other** sitting of this very exam version (S-2's showpiece), still reads
> `extra_minutes 0`. One exam version, two releases, and the extension belongs to exactly one of
> them.

```
exam 1 v2 before: duration_minutes 75, status APPROVED, lock_version 0
exam 1 v2 after:  duration_minutes 75, status APPROVED, lock_version 0
  the 15 minutes live on exam_executions.extra_minutes = 15 instead
  and execution 4821, the OTHER sitting of this very exam version, still has extra_minutes 0
```

---

### 7.3 — release it again; the original duration returns

**Status: ✅**

> **Passed, driven through the production release path rather than asserted from the table.** With
> the live sitting already extended to an allotted 90 minutes for `maya.levi`, `dana.cohen`
> released the same exam version again through `RELEASE_CREATE`. The new row came back as a
> different execution with a different, server-generated code — `PC52` — `durationMinutes 75,
> extraMinutes 0`. After `ReleaseScheduler.tick()` opened it, `extra_minutes` in the table is still
> `0`. A different student, `omer.katz`, then joined the new sitting and started: header duration
> `75 min`, `timing.total` **75 minutes, not 90**. The extension did not follow the exam out of the
> drawer.

```
the live sitting has been extended: extra_minutes 15, so maya's allotted time is now 90 minutes
RELEASE_CREATE of the same exam version -> execution 10, code PC52, durationMinutes 75,
                                           extraMinutes 0, state SCHEDULED
  omer.katz starts the new sitting: header duration 75 min, timing.total 75 min
```

---

### 7.4 — the live monitor with two students

**Status: ✅**

> **Passed, and the counts are *counted*, never accumulated.** Before anyone joined the snapshot
> read `started=0 finished=0 timedOut=0, rows 0, isEmpty true` — a proper empty state. Two students
> then started and `dana.cohen` received two `PUSH_MONITOR_UPDATED` **having asked for none of
> them**. The live snapshot: `started=2 finished=0 timedOut=0 inProgress=2`, with a row each
> naming the student, her state, her start, `answered 2/7` and `1/7`, her remaining milliseconds,
> and the integrity and attention slots (both null here). One student then handed in — `started=2
> finished=1`, her row `SUBMITTED, remaining 0 ms, actualMinutes 25` — and the other's timer fired:
> `started=2 finished=1 timedOut=1`, her row `TIMED_OUT, actualMinutes 75`. Every transition was
> pushed; not one was requested.
>
> **Counted, not stored:** while the sitting was live `exam_executions.participation` is `NULL`
> throughout, so the three numbers are a `COUNT` over `exam_attempts` on every snapshot, exactly as
> §5 requires. They are frozen into that column only at close.
>
> **"No refresh button anywhere" is structural**, not a UI habit: the feature registers exactly one
> teacher-facing read verb, `EXECUTION_MONITOR_GET`, and asking it *is* how a screen subscribes.
> There is no second verb a refresh control could call. Ownership holds too — `rina.barak` asking
> for the same monitor is refused `FORBIDDEN` and learns nothing about the execution, including
> whether it exists.

```
before anybody joins: counts started=0 finished=0 timedOut=0, rows 0, isEmpty true
maya.levi and noa.friedman both start; PUSH_MONITOR_UPDATED to dana so far: 2 — she asked for none
the last snapshot pushed to dana, unasked: counts started=2 finished=0 timedOut=0 inProgress=2
  Maya Levi (29):    IN_PROGRESS, answered 2/7, remaining 4500000 ms, integrity null, attention null
  Noa Friedman (25): IN_PROGRESS, answered 1/7, remaining 4500000 ms, integrity null, attention null
after maya hands in: started=2 finished=1 timedOut=0
  Maya Levi: SUBMITTED, remaining 0 ms, actualMinutes 25
after noa's timer fires: started=2 finished=1 timedOut=1
  Noa Friedman: TIMED_OUT, actualMinutes 75
exam_executions.participation is still NULL while the sitting is live
rina.barak asking for the same monitor -> FORBIDDEN "This exam is not yours to manage. …"
```

---

### The extension-versus-expiry race (E11.4)

Not a numbered case; walked because it is the property the whole extension design rests on.

**Status: ✅**

> **Passed, and it needed the *second* guard.** An attempt was armed for `10:15:00Z` with one live
> scheduled task. At **T minus ten seconds** the teacher granted fifteen minutes: the re-arm
> produced a second task and **cancelled the first** (`2 total, live 1, cancelled 1`), and the
> timer service now held `10:30:00Z`. The dangerous case is the one cancellation cannot cover — a
> task that had already begun running when the cancel arrived — so the **cancelled task was
> replayed deliberately**, one second past the original deadline. The attempt stayed
> `IN_PROGRESS`. Cancellation lost that race and the generation token inside `TimerService.fire`
> caught it. She could still answer past the old bell, and the *new* bell closed her at
> `10:30:00Z` with `actual_minutes 90` — so the granted time is in the record the grader and the
> results screens read, not just on her screen.

```
attempt 46 armed for 2026-08-20T10:15:00Z; scheduled tasks: 1 (live 1, cancelled 0)
extension granted at T minus 10 seconds (2026-08-20T10:14:50Z)
  tasks now: 2 total, live 1, cancelled 1 — the re-arm cancelled the task it replaced
  the timer service now holds deadline 2026-08-20T10:30:00Z
the stale task ran one second past the ORIGINAL deadline; the attempt is IN_PROGRESS
  and she can still answer past the old bell: accepted
at the NEW deadline 2026-08-20T10:30:00Z: status TIMED_OUT, actual_minutes 90
```

Extending a sitting that is over is refused: execution `4821` (CLOSED) → `CONFLICT`, *"Only a live
exam can be extended. This one is not running."*, with its `extra_minutes` still `0`.

---

## Bugs found — B-14 onwards

*(B-7 … B-13 are the S2-S5 findings. Numbering continues from there as instructed.)*

### B-14 — the allotted duration is never reconciled with the execution window

| | |
|---|---|
| **Found by** | cases 6.1 / 6.8 (probe `6.X`) and 7.1 (probe `7.X`) |
| **Severity** | **Medium** — high impact on the one student it happens to, and the seed's own live fixture is already in the state that triggers it |
| **Status** | Open |
| **Where** | `AttemptService.startInTx` (no check), `ExecutionContext.isOpenAt` (entry window only), `ReleaseScheduler.closeExpired` → `ExecutionCloseService.close` (force-submits stragglers), `ExtendService.apply` (moves both, does not reconcile them) |

**What.** Two independent clocks govern a sitting and nothing compares them. The **entry window**
(`open_at` … `close_at + extra_minutes`) decides whether a student may join. The **attempt
deadline** is `started_at + duration + extra_minutes`, derived per attempt. A student who joins
legally late gets a deadline *past* the window's close, and `ReleaseScheduler` then closes the
execution at the window's end and force-submits her as `TIMED_OUT` — with her own
server-authoritative countdown still showing time left, and no screen ever having told her the
window would end first.

**Observed, joining two minutes before a legal window shut:**

```
execution 2075 window: 2026-08-20 11:00:00.0 -> 2026-08-20 13:00:00.0   [local, = 08:00Z -> 10:00Z]
exam 1 v2's stored duration: 75 min
she joins two minutes before the window shuts, at 2026-08-20T09:58:00Z
  EXAM_JOIN -> ACCEPTED — nothing warns that the paper will not fit
  ATTEMPT_START -> accepted; the server tells her endsAt 2026-08-20T11:13:00Z and remaining 75 min
  but the window shuts at 2026-08-20T10:00:00Z, which is 73 minutes BEFORE the deadline promised
  ReleaseScheduler.tick() at the window's close changed 1 release(s); status is now CLOSED
  her attempt: status TIMED_OUT, ended_at 2026-08-20T10:00:00Z, actual_minutes 2
```

**She was promised 75 minutes, given 2, and told neither.**

**The extension path has the same hole, and it is worse there** — because the whole point of the
verb is to deliver minutes:

```
window shuts at 2026-08-20T10:00:00Z; maya joins 30 minutes before that, at 09:30:00Z
  her deadline is 2026-08-20T10:45:00Z — already 45 minutes past the window's close,
  before anybody extends anything
dana grants +15 at 2026-08-20T09:50:00Z
  monitor closesAt moved to 2026-08-20T10:15:00Z; maya's deadline moved to 2026-08-20T11:00:00Z
  she is told she has 70 minutes left, ending 2026-08-20T11:00:00Z
  but the execution's effective close is 2026-08-20T10:15:00Z, 45 minutes EARLIER
ReleaseScheduler.tick() at 10:15:00Z: execution CLOSED
  maya's attempt: status TIMED_OUT, ended_at 10:15:00Z, actual_minutes 45 of an allotted 90
```

**The teacher granted fifteen minutes, the toast announced them, and the student received none of
them.** Worse, the teacher's *own* monitor is internally inconsistent while it happens: after the
extension its `closesAt` reads `10:15:00Z` while the rows beneath it count down to `10:30:00Z`.

**Why no test sees it.** `AttemptServiceTest`, `ExtendAndMonitorTest` and
`ExamConcurrencyIntegrationTest` all build their own execution with a window generously wider than
the paper (`T0-5min` to `T0+3h` for a 45-minute exam), so the two clocks never cross. The rule is
missing rather than wrong, and there is no assertion anywhere that could fail for a missing rule.

**Not theoretical: the seed's own demo fixture is already in this state.** Execution `2075` is a
**75-minute** paper in a **two-hour** window that straddles "now" — so at load time, sixty minutes
of the window have already gone and only sixty remain. **A student who joins the live demo exam at
load time is already fifteen minutes short.** Any defence walkthrough that starts the live exam
without reseeding first will reproduce this.

**Options, for the owner rather than a decision made here.**

- **A — refuse the join.** Reject `EXAM_JOIN`/`ATTEMPT_START` when `now + allotted > effectiveClose`,
  with a sentence telling her to see her teacher. Honest and simple; costs a student who would have
  been happy with a short sitting the chance to sit at all.
- **B — cap and disclose.** Let her start, set the deadline to `min(started + allotted,
  effectiveClose)`, and tell her on the entry screen that this sitting is shorter. Requires the
  header to carry the effective figure so the countdown is truthful from the first second.
- **C — warn the teacher instead.** Refuse nothing; make `RELEASE_CREATE` refuse (or warn on) a
  window narrower than the paper's duration, and make `EXECUTION_EXTEND` extend the window by at
  least as much as it extends the attempts. Fixes it at the source and leaves take-exam untouched.
- **D — the seed.** Whatever is chosen, widen execution `2075`'s window so the demo fixture is not
  born in the failing state.

C plus D is the smallest change that makes the promise true, and C is where the rule belongs — it
is a decision about a release, and both the create dialog and the extend dialog already have a
teacher in front of them who can be told.

---

### B-15 — no illustration is loadable, so case 6.1's "and any illustrations" cannot be shown

| | |
|---|---|
| **Found by** | case 6.1 |
| **Severity** | Low (seed / demo gap; not a code defect) |
| **Status** | Open |
| **Where** | `server.db.seed.QuestionBankSection` |

Ten seed questions are marked as carrying an illustration and **none of them has any bytes**:
`question_versions` rows with a non-null `image` across the whole seeded bank: **0**. Three of the
seven questions on the demo paper — `11005`, `11007` and `11010`, per seed §7.1 — are among the
ten, so this is the demo paper's problem and not an obscure corner. The image field is present and
correct on every layer — `ExamQuestion.image` is `byte[]`, the
projection selects it, the wire carries it — so this is a fixture gap, not a leak in the path.

It is **documented as a deliberate deferral**, quoted from the loader's own javadoc:

> *"Illustrations load as NULL. Ten questions are marked `img` and no bytes are supplied. `image
> MEDIUMBLOB NULL` accepts that, and the loader stays idempotent when real assets land under
> `docs/seed/img/`. The flag is kept in the data below so the count stays assertable and so the
> follow-up knows which ten to fill."*

Recorded here because the acceptance table cannot see a javadoc: case 6.1's expected result names
illustrations, the demo will show none, and **a reviewer at the defence will read that as an
unimplemented feature rather than an unfilled fixture.** Fix is small — drop ten small images under
`docs/seed/img/` and have the section read them — and it is worth doing before the defence
precisely because the feature *is* built and currently looks as though it is not.

---

## Not bugs, recorded so they are not re-investigated

- **`ATTEMPT_START` after a submit answers OK, not an error.** It looks like a missing refusal and
  is not: F6.7 is satisfied by the unique key and by handing back the one attempt she has, and the
  "already submitted" sentence is the client's, rendered from the `attemptState` on the header
  (`ExamEntrySession` → `EntryPhase.BLOCKED` → `ExamCopy.EXAM_CLOSED_FOR_YOU`). See case 6.10.
- **`EXECUTION_EXTEND` on the scheduled execution `5164` answers `FORBIDDEN`, not `CONFLICT`.**
  Correct: `5164` was released by `michal.sharon`, so the ownership gate fires before the
  is-it-live gate. Extending a *closed* execution she owns does answer `CONFLICT`, which is the
  rule the case is about.
- **Native-query timestamps print in the JVM's zone.** A reading artefact of this suite's
  diagnostics, not a storage problem — every JPQL read round-trips as a correct `Instant`. Flagged
  under *Method* above; the cross-zone question belongs to E2.

---

## Open questions for the owner

1. **B-14 needs a decision, not a patch.** Options A-D above; C+D is the recommendation. It touches
   E9's release rules and E11's extend verb, both the lead's, which is why it is put rather than
   applied.
2. **Should `2075`'s window be widened in the seed regardless?** Even with B-14 fixed, a 75-minute
   paper in a 2-hour straddling window gives a demo student 60 minutes. Two probes here had to move
   the clock backwards inside the window to get a clean full-length sitting.
3. **Scenario 6's "illustrations" (B-15)** — worth ten small PNGs before the defence?

---

## Reproducing this

```bash
export JAVA_HOME=…/jdk-21.0.12+8
export HSTS_TEST_SCHEMA=hsts_acc1
./mvnw -o test -Dtest='acceptance.**' -DfailIfNoTests=false -Djacoco.skip=true
```

Last run: **22 tests, 0 failures, 0 errors** (`Scenario6ExecutionTest` 14, `Scenario7ExtensionTest`
8). Evidence lands in `target/acceptance/S6-S7-evidence.txt`. The suite is `@EnabledIf` on MySQL
being reachable and skips cleanly without it; it owns the schema `${HSTS_TEST_SCHEMA}_repo` and
wipes it, so it is safe to run beside other agents' builds on the same machine.

**Files added by this walk** (none of the production tree was touched, and nothing was committed):

| File | What |
|---|---|
| `src/test/java/acceptance/AcceptanceBase.java` | the harness: seed load, the two clocks, the production assembly, the evidence log |
| `src/test/java/acceptance/MutableClock.java` | the clock the probes move by hand |
| `src/test/java/acceptance/ManualScheduler.java` | the timer seam, including `runStale()` for the 7.R race |
| `src/test/java/acceptance/RecordingPushGateway.java` | the production `PushGateway`, recording what actually reached a socket |
| `src/test/java/acceptance/Scenario6ExecutionTest.java` | 14 probes for T-6 |
| `src/test/java/acceptance/Scenario7ExtensionTest.java` | 8 probes for T-7 |
