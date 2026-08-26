# E12/E13 grading wire contract — FROZEN v1

**Status: frozen 2026-08-20.** Additive changes only (new optional fields, new verbs); nothing here
is renamed, retyped or removed without a lead decision recorded in this file. This is the contract
Member B builds E12.1–E12.3 / E12.5–E12.8 and E13 against, and the lead's client screens consume.

Package: `common/dto/grading` (all types are `Serializable` records, wire-safe, no entity types).
Verbs live in `common/protocol/Verb.java`, grouped under a `Grading & results (E12/E13)` section.

## Roles and scope — the rules every handler enforces

- All teacher verbs: `Authorization.requireRole(caller, TEACHER, COORDINATOR)` **plus** ownership —
  the caller must be the execution's executing teacher or the exam's author, resolved from
  repositories, never from the payload (P-5 rule: a `CallerContext` is always read).
- All student verbs: any authenticated caller, scoped to **their own grades only** in the query
  (`WHERE student_id = :caller`), same silent-scoping pattern as notifications: someone else's
  grade id answers NOT_FOUND, revealing nothing (E13.1 ⚑ negative tests).
- A student may receive correctness data (chosen vs correct) **only** through
  `CHECKED_FORM_GET`, and only when all three hold: the grade is theirs, its status is
  `APPROVED`, and the execution is closed. Repository reads powering it are named
  `…ForCheckedForm` — the `CorrectnessLeakGuardTest` naming convention gains this second
  sanctioned suffix, and the E13.1 authorization tests are what license it.

## Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `GRADING_QUEUE_GET` | teacher | `null` | `GradingQueue` |
| `GRADING_EXECUTION_GET` | teacher | `ExecutionGradesRequest` | `ExecutionGrades` |
| `GRADE_REVIEW_GET` | teacher | `GradeReviewRequest` | `GradeReview` |
| `GRADE_OVERRIDE` | teacher | `GradeOverrideRequest` | `GradeReview` (refreshed) |
| `GRADES_APPROVE` | teacher | `ApproveRequest` | `ApproveResult` |
| `MY_GRADES_GET` | student | `null` | `MyGrades` |
| `CHECKED_FORM_GET` | student | `CheckedFormRequest` | `CheckedForm` |

Push (existing verb `PUSH_GRADE_PUBLISHED`): on approval the student's live session receives it
with a `StudentGradeRow` payload, **and** the `GRADE_PUBLISHED` notification goes through
`Notifier`/`NotificationCatalog` regardless of who is online (C-3, E13.6). The push refreshes an
open dashboard; the notification is the durable record.

## DTOs (`common/dto/grading`)

- `GradeState` — wire enum `AUTO | APPROVED`, mirroring stored `GradeStatus`. "Overridden" is not
  a state: it is `overrideReason != null`. The **effective score** is
  `finalScore != null ? finalScore : autoScore`, computed by the server and carried explicitly as
  `effectiveScore` so no client re-derives it.
- `GradingQueue(List<ExecutionGradingSummary> executions)`
- `ExecutionGradingSummary(long executionId, String examName, String courseCode, String code4,
  Instant closedAt, int participants, int gradedCount, int approvedCount)`
- `ExecutionGradesRequest(long executionId)`
- `ExecutionGrades(ExecutionGradingSummary summary, List<StudentGradeRow> rows)`
- `StudentGradeRow(long gradeId, long studentId, String studentName, int autoScore,
  Integer finalScore, int effectiveScore, GradeState state, String overrideReason,
  String teacherComment, Instant approvedAt, String examName, String courseCode)` —
  `overrideReason`/`teacherComment`/`approvedAt` nullable; `finalScore` null until overridden.
  **Amendment v1.1 (2026-08-20, additive, found in E13 PR 1 review):** `examName` and
  `courseCode` added, nullable. Populated on the STUDENT paths (`MY_GRADES_GET`,
  `CHECKED_FORM_GET`) where every row is a different exam and T-9.1 needs the label; left null
  on the teacher paths, where `ExecutionGradingSummary` already carries them once per execution.
  The previous 10-component constructor is retained for teacher-path callers.
- `GradeReviewRequest(long gradeId)`
- `GradeReview(StudentGradeRow grade, List<AnswerReviewRow> answers)` — teacher-only view.
- `AnswerReviewRow(int ordinal, String displayId, String questionText, String answer1, String
  answer2, String answer3, String answer4, int points, Byte chosen, byte correct, boolean isCorrect,
  int pointsAwarded)` — `chosen` null = unanswered (scored 0, §6).
- `GradeOverrideRequest(long gradeId, int newScore, String justification)` — justification
  **required non-blank** (S-23): blank answers `VALIDATION` before anything is read. `newScore`
  0..100. Override is allowed only while `AUTO`; overriding an `APPROVED` grade answers `CONFLICT`
  (the teacher re-approves after a new override flow is a non-goal for v1).
- `ApproveRequest(List<Long> gradeIds)` — one verb for single and bulk (E12.2/E12.7).
- `ApproveResult(int approved, int alreadyApproved, List<Long> refused)` — approve is
  **idempotent** (§6): re-approving counts in `alreadyApproved`, never errors. `refused` carries
  ids that were not the caller's to approve. When an approval completes an execution (every grade
  `APPROVED`), the server computes `ScoreStatistics` and freezes it to
  `exam_executions.stats` in the same transaction (E12.4 "→ stored").
- `MyGrades(List<StudentGradeRow> grades)` — only `APPROVED` rows. `overrideReason` is always null
  on the student wire (the justification is teacher/audit material; the student sees the comment) —
  enforced STRUCTURALLY: both student containers (`MyGrades` and `CheckedForm`) strip it in their
  compact constructors, so no handler can leak it by assembly.
- `CheckedFormRequest(long gradeId)`
- `CheckedForm(StudentGradeRow grade, String examName, String courseCode,
  AttemptState attemptStatus, Integer actualMinutes, List<AnswerReviewRow> answers)` — the
  E13.2 checked form; reaches a student only under the three conditions above. Reuses
  `AnswerReviewRow` deliberately: one row shape for both audiences, gated by verb, so there is
  exactly one place correctness is serialized and two guards in front of it.
  **Checked-form amendment (2026-08-22, additive, lead's ruling):** `attemptStatus` and
  `actualMinutes` added. Acceptance case 9.5 asks that a student whose attempt was
  force-submitted sees that it timed out, with the solving time recorded (S-19), and neither
  fact was anywhere on this wire — the case could not pass as written. They land here rather
  than on `StudentGradeRow` because **the case says "open his result and *see*", and the seeing
  happens on the marked paper**: solving time belongs beside the answers it measures, and the
  list wire pays nothing for data most of its rows would never show. `StudentGradeRow` stays at
  v1.1. A "timed out" glyph on the *list* is priced separately as polish; the case as written is
  satisfied on the paper.

## Error codes

`VALIDATION` malformed payload, blank justification, score out of range · `NOT_FOUND` unknown or
unowned id (both cases, indistinguishable on purpose) · `FORBIDDEN` role gate ·
`CONFLICT` override-after-approve, stale `lock_version` on concurrent override ·
`UNAUTHORIZED` no session.

## Additive amendments

Everything below was added **after** the 2026-08-20 freeze, under its additive-only rule: no
verb renamed, no payload component removed or reordered, no semantics changed for any existing
field. A client built against v1 still works against a server that implements the amendments.

Two amendments are already recorded **inline above** and keep the names they were given: the
`StudentGradeRow` **amendment v1.1** (`examName`/`courseCode`, 2026-08-20) is **A1**, and the
**checked-form amendment** (`attemptStatus`/`actualMinutes`, 2026-08-22) is **A2**. Numbering
continues from there, in EXAM_WIRE_CONTRACT.md's style.

### A3 — `GradeOverrideRequest.teacherComment` (E12.3 / S-22, added 2026-08-23, lead-ruled)

`GradeOverrideRequest` gains a fourth component, **appended last**:

```
GradeOverrideRequest(long gradeId, int newScore, String justification, String teacherComment)
```

**Why it exists at all.** `teacherComment` could be read everywhere on this contract — it is a
component of `StudentGradeRow`, both student containers preserve it, `MY_GRADES_GET` renders it
in a column and the checked form renders it under a heading — and **nothing could write one**.
No request payload carried a comment field and no service called `setTeacherComment`; only the
seed loader did. S-22 had a read path and no wire, and acceptance case 8.4 ("change a grade with
a justification, and add a comment to the student") could not pass. Found by Member B,
2026-08-23.

**The field.**
- **Nullable, and blank is null.** The compact constructor strips the value and collapses an
  empty result to `null` (`strip`, not `trim`), so a request from an untouched box and one from
  a box holding two spaces are the same request and every later decision is a null test.
- **No maximum length and no shape rule**, mirroring `justification` exactly. The column behind
  both is MySQL `TEXT`. The comment adds **no new error code and no new refusal**: `VALIDATION`
  still means a malformed payload, a blank justification or a score out of range.
- **The three-component constructor is retained**, delegating with a null comment, so every
  call site and test written against v1 keeps compiling and keeps meaning what it meant — the
  same move `ReleaseCreateRequest` made for its optional code.
- `serialVersionUID` goes **1 → 2**, on the precedent `StudentGradeRow` and `CheckedForm` set at
  their own amendments. Stated plainly because it is the one place "additive" is not literally
  true of the bytes: a v1 *client jar* talking to an amended server would refuse this payload,
  so client and server ship together, as they always have. Source compatibility — the thing the
  retained constructor buys — is unaffected.

**Null preserves; it does not clear.** ⚑ The service writes the comment **only when one was
sent**. An override carrying no comment leaves any existing comment exactly where it is.
Correcting a score twice is ordinary, and the dialog's comment box opens empty every time; if
null meant "clear it", a teacher's second correction would silently delete the sentence she
wrote to the student on the first, and no screen would say so. **There is therefore no way to
clear a comment on this wire at all** — removing one is a v2 shape.

**Written in the same transaction as the score**, by `OverrideService`, because the comment
explains that score and a student must never be able to read one without the other. It is
refused with the override in every case the override is refused, `CONFLICT` on an already
approved grade included — that is the point of riding the same verb.

**Why A and not B.** A standalone `GRADE_COMMENT_SET` verb was considered and **declined for
v1**: commenting rides the adjustment for v1, and a standalone `GRADE_COMMENT_SET` is the v2
shape. The two acts happen at one moment in front of one paper — T-8.3 has her writing both in
the same dialog — so a second verb would have bought a second round trip, a second set of
ownership and state gates saying the same thing, and the possibility of a comment landing on a
grade whose score change had just been refused. It is the right shape for what v1 does not do:
commenting on a grade **without** changing it, and clearing a comment.

**Nothing changed on the read side.** `StudentGradeRow` stays at v1.1 and both student
containers strip `overrideReason` exactly as before. The comment already travelled every read
path; this amendment gives it a way in.

### A4 — the `ForCheckedForm` suffix is withdrawn (E13.2 / E13.4, 2026-08-23, lead-ruled)

The scope section above records that repository reads powering `CHECKED_FORM_GET` are named
`…ForCheckedForm`, sanctioned as a second suffix in `CorrectnessLeakGuardTest`. **E13.4 shipped
without one.** The checked form reuses `GradeReviewService.answers`, the same assembler
`GRADE_REVIEW_GET` uses, so its read is `findVersionsForGrading` and no method anywhere was ever
named for the checked form. Found by Member B (PR 17), who declined to remove an entry from a
security guard on his own judgement and asked.

The entry is **removed**, on the guard's own stated rule: a suffix is licensed by the feature
behind it, and a licensed name with no readers is a permission standing open that nobody is
exercising and nobody is watching. `SANCTIONED_SUFFIXES` is now `ForAuthoring, ForGrading`, and
a test asserts the withdrawal rather than only the two that remain, so restoring the entry
cannot happen silently.

**Nothing about the checked form's gates changes.** They were never the suffix: E13.1's
authorization tests prove the grade is the caller's, that it is `APPROVED` and that the
execution is closed, and that failing any of them answers `NOT_FOUND` indistinguishably. Those
tests were always the licence and they still hold. The sharing is the right design — one place
in the product turns an answer key into rows, with two different gates in front of it.

### A5 — `StudentGradeRow` v1.2: `attemptStatus` and `actualMinutes` (E14.1 / T-10.2, B-16, added 2026-08-26, lead)

**What was missing.** T-10.2 asks the teacher's results table for "score, submitted vs timed
out, solving time" and only the score reached the wire. A shape fact, not a null: `StudentGradeRow`
had twelve components and none of them was either. On the seed, `omer.katz`'s timed-out 45 read
exactly like the seven submitted papers, so **the one attempt in the whole dataset that
distinguishes "did not finish" from "did badly" was invisible on the screen built to show it.**

**And the data was already being read and thrown away.** `GradeRepository.findResultRows` has
selected `a.actualMinutes` into `StudentResultRow` since E14 — the projection's own javadoc
documents it as "recorded solving time (S-19)" — and `TeacherResultsService.toWire` mapped ten of
its eleven components and dropped that one. `actualMinutes` had **no reader anywhere on the E14
path**. Attempt status was never selected at all. This is B-3's shape turned around: a field read
but never delivered rather than written but never read, and it is why every test stayed green —
`TeacherResultsServiceTest` asserted what the record carried, and the record carried what it was
built to carry.

**The record, appended last:**

```
StudentGradeRow(long gradeId, long studentId, String studentName, int autoScore,
                Integer finalScore, int effectiveScore, GradeState state, String overrideReason,
                String teacherComment, Instant approvedAt, String examName, String courseCode,
                AttemptState attemptStatus, Integer actualMinutes)
```

- `attemptStatus` is `common.dto.exam.AttemptState` and `actualMinutes` is a boxed `Integer` —
  **the same two types the student's own `CheckedForm` has carried since the 2026-08-22
  amendment.** One fact, one type, whichever audience is reading it; two shapes would be two
  answers to one question. `actualMinutes` stays boxed because "not recorded" is a different
  fact from "took zero minutes".
- **Populated on the teacher results path only** (`RESULTS_EXECUTION_GET`), where the table
  renders them. Null everywhere else, and null is honest there: the grading queue, the review
  header and both student containers are about grades, and `findResultRows` is the only read that
  joins the attempt at all.
- The **twelve-component constructor is retained** and delegates with both null, so every
  existing caller keeps compiling and keeps meaning what it meant. `withoutJustification()` and
  `withExam()` carry the new components through unchanged.
- `serialVersionUID` goes **2 → 3**, on the precedent this record and `CheckedForm` set.

**`CheckedForm` is unaffected.** It keeps its own `attemptStatus`/`actualMinutes`, which the
2026-08-22 ruling put there rather than on this record because case 9.5's "open his result and
*see*" happens on the marked paper. That ruling was about the **student** wire and it stands;
this is the teacher's table, which is a different screen asking a different question, and it is
the screen T-10.2 names.

**Server side:** `a.status` joins `a.actualMinutes` in `findResultRows`' select, and
`StudentResultRow` gains an eleventh component to carry it. **Client side:** two columns,
*Attempt* and *Time*, sized to their content. Both render **words** — "Submitted", "Timed out",
"43 min" — never a tint alone, per the B-5 / wave rule: a colour survives neither a printout, a
screenshot, nor a colour-blind reader. The ordinary case says "Submitted" rather than staying
blank, because a column whose only content is the exception reads as data that failed to load.

## What is deliberately absent

- No stats DTOs — teacher statistics/histogram are E14's contract, drafted when E14 starts.
- No pagination — school-sized lists (§6 scale).
- No per-question data in `MY_GRADES_GET` — the checked form is its own verb with its own gates.
- No `GRADE_COMMENT_SET` — commenting rides the adjustment in v1 (A3). Commenting without
  changing a score, and clearing a comment, are the two things that verb would buy, and both
  wait for v2.
