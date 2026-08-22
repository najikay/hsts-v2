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

## What is deliberately absent

- No stats DTOs — teacher statistics/histogram are E14's contract, drafted when E14 starts.
- No pagination — school-sized lists (§6 scale).
- No per-question data in `MY_GRADES_GET` — the checked form is its own verb with its own gates.
