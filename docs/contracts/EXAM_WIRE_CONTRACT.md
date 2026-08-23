# E10/E11 take-exam wire contract — FROZEN v1

**Status: frozen 2026-08-20** after lead review (independent verify: 2168 tests, gate met).
Additive only from here, on the same terms as [GRADING_WIRE_CONTRACT.md](GRADING_WIRE_CONTRACT.md).

**Lead rulings recorded at freeze (2026-08-20):**
1. **The attempt deadline is NOT capped by the execution's `close_at`.** The window governs
   STARTING an attempt; a student who joins inside it gets her full allotted duration (plus
   extensions). Teachers who need a hard stop have close-early (F5.5), which force-submits
   through the same path as expiry. Consequence a demo will show: an attempt can end after the
   window closes, and that is correct.
2. **A submit that loses the race to the expiry timer answers `OK` carrying `TIMED_OUT`**, never
   an error. The paper was handed in either way; "failed" would be false and frightening.
3. Solving minutes round to nearest. Monitor pushes are whole snapshots. Both confirmed.

Package: `common/dto/exam` (all types are `Serializable` records, wire-safe, no entity types).
Verbs live in `common/protocol/Verb.java`, grouped under `Take exam (E10)` and
`Extension & monitoring (E11)`.

This is the epic whose v1 version failed the first defence: the timer stayed open and students saw
answers. Both fixes are in this contract rather than in the implementation, which is the point.

---

## The two rules the whole contract exists to enforce

### 1. The server owns time

Every response and every deadline-moving push carries an `AttemptTiming`, and the client's
countdown **re-anchors** to it rather than accumulating. Between messages it interpolates and
nothing more.

- The attempt deadline is **derived, never stored**: `startedAt + (durationMinutes + extraMinutes)`.
  An extension therefore writes one number on the execution and every live deadline moves with it,
  including for a student who is offline (she gains the time the moment she resumes, E11.4).
- Every write path re-checks the attempt's status **and** re-derives the deadline **inside the
  transaction that does the write**. Status alone would let an answer land between a deadline
  passing and the timer firing; deadline alone would let one land on an already-submitted attempt.
- An answer arriving after the bell does not merely bounce: it force-submits the attempt then and
  there, so "the client was still open" cannot produce a paper edited after time (§6).

### 2. No correctness on a student's wire

`ExamQuestion` has a stem, four options and no field for an answer key. It is mapped from
`server.db.projections.TakeExamQuestion`, whose query is a JPQL constructor expression that never
selects `correct_answer` — so on this path the key is not fetched, not held, and has nowhere to
land. `server.db.repos.ExamWireLeakGuardTest` scans every record in `common/dto/exam` for a
component name that reads like an answer key and fails the build on a match, with a
"has teeth" case proving the scan can still fail.

`common/dto/grading` is deliberately **not** scanned: its `AnswerReviewRow` carries `correct` by
design, gated by the three conditions in the frozen grading contract.

---

## Roles and scope

- **Student verbs** (`EXAM_JOIN`, `ATTEMPT_START`, `ATTEMPT_RESUME`, `ANSWER_SAVE`,
  `ATTEMPT_SUBMIT`): any authenticated caller, scoped to themselves. **No payload carries a student
  id**, because one could only ever be somebody else's (P-5: the `CallerContext` identifies the
  caller). An attempt id that is not the caller's answers `NOT_FOUND`, indistinguishably from one
  that does not exist.
- **Teacher verbs** (`EXECUTION_EXTEND`, `EXECUTION_MONITOR_GET`):
  `Authorization.requireRole(TEACHER, COORDINATOR)` **plus** ownership resolved from the execution
  itself — the caller must be the teacher who released it or the author of the exam being sat
  (S-35), never whoever the payload says.

## Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `EXAM_JOIN` | student | `ExamJoinRequest` | `ExamHeader` (no questions) |
| `ATTEMPT_START` | student | `AttemptStartRequest` | `AttemptForm` |
| `ATTEMPT_RESUME` | student | `AttemptResumeRequest` | `AttemptForm` |
| `ANSWER_SAVE` | student | `SaveAnswerRequest` | `SaveAnswerResult` |
| `ATTEMPT_SUBMIT` | student | `SubmitAttemptRequest` | `AttemptOutcome` |
| `EXECUTION_EXTEND` | teacher | `ExtendTimeRequest` | `ExecutionMonitor` (refreshed) |
| `EXECUTION_MONITOR_GET` | teacher | `MonitorRequest` | `ExecutionMonitor` |

Pushes:

| Push verb | Payload | Recipients |
|---|---|---|
| `PUSH_TIMER_EXTENDED` | `TimerExtended` | every student sitting that execution |
| `PUSH_FORCE_SUBMITTED` | `AttemptOutcome` | the one student, if online |
| `PUSH_MONITOR_UPDATED` | `ExecutionMonitor` | the teachers watching that execution |

`PUSH_MONITOR_UPDATED` is new in E11. `PUSH_TIMER_EXTENDED` and `PUSH_FORCE_SUBMITTED` were
reserved in E1 and are given their payloads here.

---

## Verb semantics

### `EXAM_JOIN` — what is this code?

Answers the header and **never the questions**: the paper does not exist on a client until an
identity has been confirmed, because that is what starts the clock (S-18). The code is normalised
to upper case in the request record and compared case-insensitively (C-1).

`ExamHeader.attemptState` tells the client which of three things to do next: ask for an ID
(`NOT_STARTED`), resume straight into the paper (`IN_PROGRESS`), or say "already handed in"
(`SUBMITTED` / `TIMED_OUT`, F6.7).

### `ATTEMPT_START` — confirm identity, start the clock

The national id is checked against the **caller's own** user record. It is a confirmation, not a
lookup key, so typing a classmate's number identifies nobody. Comparison strips whitespace and
dashes and folds case, because people type numbers in groups.

Every gate from the join is re-checked here (live, window, enrolment): minutes can pass between the
two screens.

**Starting twice is not an error.** A double click, or a client that retried, answers the resumable
state of the first attempt. `UNIQUE(execution_id, student_id)` is the real guard: when it refuses
the second insert, that transaction rolls back and the server re-reads the winner in a clean one
(the failed flush poisons its own session, so carrying on inside it is not safe).

### `ATTEMPT_RESUME` — come back to the paper

Rebuilds the client from scratch: questions, the answers the **server** holds, and the
authoritative remaining time. Nothing is merged with what the client remembers.

**Resume is also an expiry check.** If the attempt is still marked in progress but its deadline has
passed, this closes it before answering, and the form comes back `TIMED_OUT` with its outcome
attached. That covers the one case a scheduled timer cannot: the server was not running when the
deadline arrived.

### `ANSWER_SAVE` — autosave

Keyed `(attempt_id, question_version_id)`, so eleven changes of mind leave one row. `selected` is
nullable, which is the "clear my answer" path; unanswered scores 0 (§6). The response carries the
answered count the **server** counted and fresh `AttemptTiming`, which makes every keystroke a
clock re-sync.

### `ATTEMPT_SUBMIT` — hand it in

A state transition, not an upload: the answers are already stored. Three outcomes, none of them an
error at the student:

1. in time → `SUBMITTED`, `endedAt` = now, minutes recorded (S-19);
2. a moment late and the timer has not fired → `TIMED_OUT`, `endedAt` = the deadline, because that
   is when the exam actually ended;
3. the timer already fired → the compare-and-set changes nothing and she is told the outcome that
   won. Her paper was handed in either way.

Re-submitting a closed attempt is idempotent.

### `EXECUTION_EXTEND` — add minutes (S-20)

Applies to the **execution**, never to the stored exam. Writes `extra_minutes`, re-arms every live
attempt from the recomputed deadline, pushes `PUSH_TIMER_EXTENDED` **and** raises a durable
`TIME_EXTENDED` notification through `Notifier`/`NotificationCatalog` — both, because the push is
worthless to a student whose socket dropped and the notification is worthless as a live cue. Time
added is never silent (F7.1).

`exam_executions` carries `lock_version`, so two teachers granting at the same moment produce one
`CONFLICT` rather than one silent grant.

### `EXECUTION_MONITOR_GET` — watch it live (F7.2)

Asking **subscribes**: the caller is registered as a watcher and receives `PUSH_MONITOR_UPDATED` on
every change until she disconnects or signs out — the same "whoever asked is watching" mechanism
`EditLockService` uses, dropped through the one `SessionManager` detach hook.

Every push carries a **whole snapshot**, never a delta. A screen that patched rows from events
drifts the first time one is missed, and a monitor showing a student as still working ten minutes
after she submitted is worse than no monitor: a teacher acts on it.

The three participation counts are a `COUNT` over attempts every time (§5 forbids counter columns),
and each row's remaining time is computed from the same derived deadline the student's own
countdown is anchored to, so the two screens cannot disagree.

---

## DTOs (`common/dto/exam`)

- `AttemptState` — wire enum `NOT_STARTED | IN_PROGRESS | SUBMITTED | TIMED_OUT`. The extra value
  is `NOT_STARTED`, which has no stored counterpart because "no row" is how the database says it.
  The two terminal values stay distinct because F6.4's takeover and F6.10's Submitted screen are
  the same layout with opposite tone.
- `ExamJoinRequest(String code)` — trimmed and upper-cased in the compact constructor;
  `isWellFormed()` is `[A-Za-z0-9]{4}`.
- `ExamHeader(long executionId, String examName, String courseCode, String courseName,
  int durationMinutes, String generalText, int questionCount, AttemptState attemptState)` —
  `durationMinutes` **includes** extensions granted so far; the client never adds the two.
- `AttemptStartRequest(long executionId, String nationalId)`
- `AttemptResumeRequest(long executionId)`
- `ExamQuestion(long questionVersionId, String displayId, int ordinal, int points, String text,
  String option1..option4, byte[] image)` — value equality includes the image bytes (a record's
  generated `equals` compares arrays by reference, and the constructor clones); `toString` prints
  a byte count, never the bytes.
- `SavedAnswer(long questionVersionId, int selected)` — `selected` is 1..4, enforced.
- `AttemptTiming(Instant serverNow, Instant endsAt, long remainingMillis, long totalMillis)` —
  both instants travel so a client can tell "the server says 12 minutes" from "the server said 12
  minutes, forty seconds of network ago". `remainingMillis` clamps at 0.
- `AttemptForm(long attemptId, ExamHeader header, List<ExamQuestion> questions,
  List<SavedAnswer> savedAnswers, AttemptTiming timing, AttemptState state, AttemptOutcome outcome)`
  — `outcome` is non-null **exactly** when `state` is terminal; a live form drops one in its
  compact constructor rather than letting the contradiction arrive.
- `SaveAnswerRequest(long attemptId, long questionVersionId, Integer selected)` — `null` clears.
- `SaveAnswerResult(long questionVersionId, Integer selected, int answeredCount,
  int questionCount, AttemptTiming timing)`
- `SubmitAttemptRequest(long attemptId)`
- `AttemptSummaryEntry(int ordinal, String displayId, boolean answered)` — one chip of the
  answer-summary grid; the same shape serves the submit dialog, the Submitted screen and the
  takeover.
- `AttemptOutcome(long attemptId, AttemptState state, String examName, Instant endedAt,
  int solvingMinutes, int answeredCount, int questionCount, List<AttemptSummaryEntry> summary)` —
  identical whether it arrives as a submit's answer, a force-submit push, or inside a resumed form.
- `ExtendTimeRequest(long executionId, int extraMinutes)` — `isAmountLegal()` is `1..480`.
- `TimerExtended(long executionId, String examName, String teacherName, int extraMinutes,
  AttemptTiming timing)` — a blank teacher name falls back to "Your teacher" so the sentence is
  never "null added 15 minutes".
- `MonitorRequest(long executionId)`
- `MonitorCounts(long started, long finished, long timedOut)` — derived, never accumulated;
  `inProgress()` is computed and never negative.
- `IntegrityFlag(String courseCode, String courseName, Instant at)` — C-4's cross-course flag,
  worded as an observation ("used Algebra 11 bot"), not an accusation.
- `MonitorRow(long studentId, String studentName, AttemptState state, Instant startedAt,
  Instant endedAt, long remainingMillis, int answeredCount, int questionCount,
  Integer actualMinutes, IntegrityFlag integrity)` — **no answers and no scores**: a teacher
  watching a live exam has no business seeing what anyone picked.
- `ExecutionMonitor(long executionId, String examName, String courseCode, String code,
  boolean live, Instant serverNow, Instant closesAt, int extraMinutes, int durationMinutes,
  MonitorCounts counts, List<MonitorRow> rows)` — `code` is teacher-facing only; it never appears
  on a student's wire (S-17: the code is delivered orally).

## Error codes

`VALIDATION` malformed payload, malformed code, wrong or missing ID number, option outside 1..4,
question not on this paper, extension amount out of range ·
`NOT_FOUND` unknown code, unknown execution, an attempt that is not the caller's (both cases,
indistinguishable on purpose) ·
`CONFLICT` execution not open yet / no longer open, save or submit after the deadline, save on a
closed attempt, extension of a non-live execution, an extension that lost the optimistic-lock race ·
`FORBIDDEN` not enrolled (student), not this teacher's execution (teacher) ·
`UNAUTHORIZED` no session.

The four entry refusals ride on four distinct codes so a client can branch without matching on
text; the sentences themselves live in `server.features.exam.ExamMessages` and are checked by one
test against the PRD §4.1 copy rules.

## Additive amendments

Everything below was added **after** the 2026-08-20 freeze, under its additive-only rule: no
verb renamed, no payload component removed or reordered, no semantics changed for any existing
field. A client built against v1 of this contract still works against a server that implements
the amendments, and vice versa — that is the test each amendment has to pass to be allowed in.

### A1 — `ATTEMPT_ATTENTION` (E11.7 / F7.1b, added 2026-08-21, lead)

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `ATTEMPT_ATTENTION` | student | `AttentionReport` | *(none)* |

**What it is.** While an attempt is `IN_PROGRESS`, the student's client watches the exam
window's focus (`Stage.focusedProperty`). An absence is **debounced at 500 ms** — anything
shorter is window-manager flicker, not a student leaving — and is reported **on refocus**, as
one message carrying the away duration. Nothing is sent on blur, because an absence has no
duration until it has ended.

**Payloads.**
- `AttentionReport(long awayMillis)` — clamped at 0. **No attempt id and no student id**, on
  the same scope rule as every other student verb: the server resolves the caller's own live
  attempt through `AttemptRegistry`, so no client can report an absence for anybody else.
- `AttentionSummary(int count, long totalAwayMillis, Instant lastAt)` — the server's running
  total per attempt. `label()` is the teacher-facing sentence,
  "Left the exam view 3 times · 40s total".

**Semantics.**
1. **No live attempt answers `OK` and does nothing.** She can be away when her time runs out,
   so the refocus that ends the absence arrives after the server has already force-submitted
   her. That is the normal shape of the race, not an error, and a `CONFLICT` here would make a
   correct client log failures during every exam.
2. **Accumulating, not first-wins** (the opposite of `IntegrityFlag`): one 40-second absence
   and eight five-second ones are different situations, and the teacher acts on the
   difference.
3. Summaries live in `AttemptRegistry` beside the C-4 flags, on the same terms — they survive
   a resume and outlive the attempt, and they are cleared with the registry.
4. Every report **pushes a whole `PUSH_MONITOR_UPDATED` snapshot** to the watching teachers,
   like every other monitor change. No new push verb; the count stays at seven.
5. **No notification is raised and nothing is pushed to the student.** F7.1b forbids any
   student-facing UI and any auto-penalty; this verb has exactly one visible consequence, a
   secondary line on one monitor row.

**Error codes.** `VALIDATION` malformed payload · `UNAUTHORIZED` no session. Nothing else: the
"no live attempt" case is an OK by design.

**Honest limit, stated for the defence.** Detection runs on the student's own machine, so this
is a deterrent and a visibility aid, not a control — the same framing as the discovery
fingerprints. The monitor's tooltip says so to the teacher rather than leaving it in a
document she will not read.

### A2 — `MonitorRow.attention` (E11.7 / F7.1b, added 2026-08-21, lead)

`MonitorRow` gains an eleventh component, **appended last**:

```
MonitorRow(long studentId, String studentName, AttemptState state, Instant startedAt,
           Instant endedAt, long remainingMillis, int answeredCount, int questionCount,
           Integer actualMinutes, IntegrityFlag integrity, AttentionSummary attention)
```

`attention` is **nullable**, and null is the normal value: a student whose window never left
focus carries nothing rather than a reassuring zero, and so does a row built by any code that
predates the amendment. The pre-E11.7 ten-argument constructor is kept for exactly that
reason, so every existing construction site keeps compiling and keeps meaning what it meant.

The row still carries **no answers and no scores**. Nothing here is correctness, and the E10.2
wire leak guard scans this package unchanged.

### A3 — the release manager's five verbs (E9 / F5, added 2026-08-22, lead)

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `RELEASE_OPTIONS_GET` | teacher | *(none)* | `ReleaseOptions` |
| `RELEASE_LIST_GET` | teacher | *(none)* | `ReleaseList` |
| `RELEASE_CREATE` | teacher | `ReleaseCreateRequest` | `ReleaseRow` (the new one, with its code) |
| `RELEASE_CANCEL` | teacher | `ReleaseActionRequest` | `ReleaseRow` (refreshed) |
| `RELEASE_CLOSE_EARLY` | teacher | `ReleaseActionRequest` | `ReleaseRow` (refreshed) |

**Scope.** Every one is `Authorization.requireRole(TEACHER, COORDINATOR)` **plus** ownership
resolved from the release itself, on exactly the E11 rule: the caller must be the teacher who
released it or the author of the exam being sat (S-35). **No payload carries a teacher id.**
The two payload-less verbs take none because "which releases are hers" is the session's answer.

**Additive only.** No existing verb is renamed, no payload component is removed or reordered,
and no semantics change for any existing field. A client built against v1 works against a
server that implements this; a client that implements it works against a v1 server minus the
screen.

### A4 — `PUSH_EXECUTION_STATUS` gets its payload (E9 / F5.4, added 2026-08-22, lead)

| Push verb | Payload | Recipients |
|---|---|---|
| `PUSH_EXECUTION_STATUS` | `ReleaseRow` | the teachers who own that release |

The verb was **reserved in E1** and is given its payload here, as `PUSH_TIMER_EXTENDED` and
`PUSH_FORCE_SUBMITTED` were given theirs in v1. **The push verb count stays at seven**; E9 adds
no new one.

One **whole row**, never a delta, for the reason `PUSH_MONITOR_UPDATED` carries a whole
snapshot: a list that patched fields from events drifts the first time one is missed. A row for
a release the client has not seen is an **insert**, not a mistake — a release created on her
other machine, or by the exam's author, has to appear without a refresh (NFR-18).

Emitted on create, on cancel, on close (early or by the clock), and on the scheduled opening
transition. Recipients are the same pair the verbs admit, so a teacher who may act on a release
is exactly a teacher who is told when it changes.

### A5 — the release DTOs (`common/dto/release`, added 2026-08-22, lead)

A package of their own rather than `common.dto.exam`, and the reason is that package's own
defining sentence: everything in it is safe to send to a student sitting an exam. `ReleaseRow`
carries the 4-character entry code, which S-17 says students learn by ear and never in the app.
`MonitorCounts` is **reused** rather than re-declared, because a release row's participation and
the monitor's three numbers are the same derived counts (S-21, §5).

- `ReleaseState` — wire enum `SCHEDULED | LIVE | CLOSED | CANCELLED`, with `canCancel()`,
  `canCloseEarly()`, `isLive()`, `isOver()`. The F5.5 rules live here so the server's guard and
  the client's button set are one rule written once.
- `ReleasableVersion(long examVersionId, String examDisplayId, String examName, int versionNo,
  String courseCode, String courseName, int durationMinutes, int questionCount)` — a picker row.
  `label()` is "Midterm (v2) · Algebra 11 · 12 questions".
- `ReleaseOptions(List<ReleasableVersion> versions, boolean anyExams)` — `waitingOnApproval()`
  distinguishes "nothing approved yet" (go to your coordinator) from "nothing written yet" (go
  to the builder); both render zero rows and have different next steps.
- `ReleaseCreateRequest(long examVersionId, Instant openAt, Instant closeAt, String code)` —
  `code` is **nullable**: null means "generate one", and the compact constructor collapses a
  blank string to null so there is one representation of that. Trimmed and upper-cased on the
  way in. **No teacher id**, see A6. `windowProblem(now, grace)` is the F5.2 rule and
  `codeProblem()` the C-1 shape rule, both run by both tiers. `PAST_GRACE` is 5 minutes,
  `MIN_WINDOW` is 1 minute. The three-component constructor is kept and means "generate one",
  so every call site written before the code field keeps compiling and keeps meaning what it
  meant.
- `ReleaseCodeIssue` — enum `MALFORMED | TAKEN`, each carrying its sentence. `MALFORMED` is a
  rule about a string and both tiers run it; `TAKEN` is a rule about the database and is
  **server-only**, answered inside the inserting transaction.
- `ReleaseWindow` — enum of the four ways a window is wrong, each carrying its sentence. On the
  wire so the dialog's inline hint and the server's refusal are the same string.
- `ReleaseActionRequest(long executionId)` — cancel and close early. One record, two verbs:
  which action is meant is the verb, so "cancel a live exam" is not a representable request.
- `ReleaseRow(long executionId, long examVersionId, String examName, String courseCode,
  String courseName, String code, Instant openAt, Instant closeAt, int extraMinutes,
  int durationMinutes, ReleaseState state, MonitorCounts counts)` — `code` is teacher-facing
  only. `effectiveCloseAt()` and `allottedMinutes()` include extensions (S-20).
- `ReleaseList(Instant serverNow, List<ReleaseRow> rows)` — `serverNow` travels so countdowns
  are anchored to the server's clock (ADR-010). `with(row)` is the push merge.

### A6 — the execution code, and the one thing the client never supplies (E9, added 2026-08-22, revised 2026-08-23, lead)

**The code is the teacher's; the check is the server's.** §4 says she defines a 4-character
execution code and T-5.3 has her typing one, so `ReleaseCreateRequest.code` carries it and the
create dialog has a field. It is nullable and blank is legitimate: null asks the server to pick
one.

Both paths are validated **inside the transaction that inserts**. §5 makes uniqueness a
*service* rule because the constraint is partial and MySQL has no partial unique index: a code
is unique among **scheduled and live** sittings and free again once one is closed or cancelled
(the seed's fourth execution reuses the first's shape). That is the only place the question can
be answered honestly, so:

- a supplied code that clashes → `VALIDATION`, `ReleaseCodeIssue.TAKEN`, which names the way
  out ("Pick another or leave it blank to generate one.");
- a supplied code of the wrong shape → `VALIDATION`, `ReleaseCodeIssue.MALFORMED`, checked
  before any read because it is a rule about a string, and checked by the dialog as she types;
- a generated code that collides → re-rolled, bounded at 20 attempts.

Codes are stored upper case and compared case-insensitively (C-1), so `ab7q` and `AB7Q` are the
same code both for a student joining and for the uniqueness check. **The accepted shape is
C-1's wide `[A-Za-z0-9]{4}`** — the seed, the demo and T-5.3 all use all-digit codes.

The **generator's** alphabet is narrower, `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`: 32 symbols
without `O`/`0` and `I`/`1`, because a code we invent will be read out loud to a room. That
narrowing constrains generation only and is never imposed on a code a teacher typed.

**The one thing still absent is the teacher id.** Who is releasing is the session's answer
(P-5), so no payload has a field a client could put a colleague's id into.

**The state is the server's too.** `ReleaseRow.state` is derived from the stored status *and* the
window against the server clock, and the derivation is deliberately asymmetric: a `SCHEDULED`
release whose opening moment has passed still reads **Scheduled** (students cannot enter until
the column says otherwise, and a teacher must not read the code out to a room that would be
refused), while a `LIVE` one past its effective close reads **Closed** (joins are already
refused and every attempt's timer has fired). Extensions move that second answer, so fifteen
minutes granted keeps it Live for fifteen minutes more.

### A7 — error codes for the release verbs (E9, added 2026-08-22, lead)

`VALIDATION` malformed payload; an **unapproved** exam version (the F5.1 sentence); a window
whose close is not after its open, is shorter than a minute, or opens well before now; a code
that is not four letters or digits; a code already held by a scheduled or live sitting ·
`NOT_FOUND` an exam version that does not exist **or** belongs to a course she does not teach;
a release that does not exist **or** is not hers — both pairs indistinguishable on purpose ·
`CONFLICT` cancelling something that is not scheduled; closing early something that is not live;
losing the guarded-transition race; no free code after twenty rolls ·
`FORBIDDEN` never used by these verbs, see below · `UNAUTHORIZED` no session.

**One deliberate divergence from E10/E11.** Those answer `FORBIDDEN` for "not your execution",
on the reasoning that a teacher who reached the monitor already knows it exists and telling her
whose it is lets her ask the right colleague. The release manager lists exactly the releases she
may act on, so an id from anywhere else did not come from this screen; its two action verbs
answer `NOT_FOUND` with one sentence for both cases. The sentences live in
`server.features.release.ReleaseMessages` and are checked by one test against the §4.1 copy
rules, exactly as `ExamMessages` is.

## What is deliberately absent

- **No `student_id` anywhere in a request.** See the scope rule above.
- **No correctness anywhere.** The student's marked paper is `CHECKED_FORM_GET` in the grading
  contract, with its own three gates.
- **No score, grade or statistic.** Those are E12/E14; this contract stops at "handed in".
- **No stored deadline field.** It is derived, and that is what makes E11.4 work.
- **No pagination.** School-sized lists (§6 scale).
