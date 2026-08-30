# E8 approval wire contract — FROZEN v1

**Status: frozen 2026-08-21** after lead review (independent verify at merge; audit corrections applied in db50995).

**Lead rulings at freeze:**
1. The fifth verb, `MY_APPROVALS_GET`, is approved and RETIRES INTO E7's exam list when that screen absorbs route id `exams` — documented in the E8 report and binding on E7.
   > **Ruling 1 executed 2026-08-25 — `MY_APPROVALS_GET` retired into E7.10's `EXAM_LIST`; the verb, DTO and screen are deleted.** Gone with it: `common/dto/approval/MyApprovals`, `client/features/approval/MyApprovalsView`, `MyApprovalsSession`, `ApprovalService.mine` and its registration, and `ApprovalCopy`'s six teacher-side sentences. `ApprovalRow` stays — the queue uses it. Removed rather than deprecated on #47's precedent: the never-remove-a-header rule protects a client jar meeting a server jar of another version, and both tiers ship from one build here. The removal landed in the **same change** as the screen swap, so there was never a window with two live reads of one fact. `ExamList`/`ExamListRow`/`ExamVersionRow` are a strict superset; `submittedAt` becomes the version's `createdAt` and `selfAuthored` is dropped as vacuous on an author-scoped screen. See `EXAM_BUILDER_WIRE_CONTRACT.md` §8 and `docs/reports/lead/E7-INTEGRATION.md`.
2. `PreviewAnswerRow` stays a separate type from `AnswerReviewRow`: a preview key and a marked paper are different documents with different audiences.
3. The preview's audience is the deciding coordinator OR the version's own author (F4.2 actionability) — the wording corrected 2026-08-21 across guard licence, Verb header and javadoc after Member A's rule-5 pass; the plain-teacher negative test pins the third role out.
4. The `versionSubmitted` three-in-one hook (supersede + notify + request notice) is approved; E7's `submitForApproval` calls it and emits nothing of its own. A stale coordinator is refused by the STATUS guard, not her lock token — a cross-file dependency named in both javadocs.
5. Scope errors answer `NOT_FOUND` via the boolean-sibling pattern (the author's own §4.1 self-catch); `FORBIDDEN` never names a course on UPDATE/DELETE paths.
6. **Phase-2 note (V8, declined for 2026-08-27):** `exam_versions` carries no `rejected_by`/`approved_by`/`approved_at`; WHO decided is derivable (one coordinator per subject, PK-enforced) and the self-approval record is the SELF-APPROVAL log line (case 4.6). The additive nullable columns Member A proposed are the correct phase-2 fix once coordinator reassignment enters scope.
7. The ord fix (#22): answer-key rows are numbered by the STORED position, never by counting; the gapped-ord contract case is the guard.
Once frozen the same rule as the other three applies: additive changes only (new optional fields,
new verbs); nothing renamed, retyped or removed without a lead decision recorded here.

Package: `common/dto/approval` (all types are `Serializable` records, wire-safe, no entity types).
Verbs live in `common/protocol/Verb.java`, grouped under an `Exam approval (E8)` section.
Handlers: `server/features/approval/ApprovalService`.

---

## The two rules this contract exists to keep

**1. The coordinator sees the exam exactly as a student will (F4.1 ⚑ — the v1 failure).**
`ExamPreview.questions` is `List<common.dto.exam.ExamQuestion>` — *the student's own wire type*,
built by `ExamPaper.toWire` over `QuestionRepository.findForTakeExam`, which is the same mapper and
the same no-correctness projection a live attempt is served from. The client renders it with
`client.features.exam.QuestionCardView`, the same component `ExamFormView` draws a live paper with,
in read-only mode. There is no second renderer and no second mapper, so "she sees what the student
sees" is a property of the code path rather than a promise about two screens.

**2. Correctness has exactly one door on this wire, and it is labelled.**
A coordinator is staff and must see the answer key to do her job. It travels in exactly one record,
`PreviewAnswerRow`, fenced inside `TeacherOnlyBlock`, reachable by exactly one verb,
`EXAM_PREVIEW_GET`. The repository read behind it is `QuestionRepository.findAnswerKeyForAuthoring`
— the `ForAuthoring` suffix E2.12 established, so `CorrectnessLeakGuardTest` stays truthful about
which audience each key-bearing read serves. **Nothing is added to `common.dto.exam`**: that package
is scanned by `ExamWireLeakGuardTest`, and keeping the staff-only block in a package beside the
student types rather than as optional fields inside them is what keeps that guard meaningful instead
of suppressed.

---

## Roles and scope — the rules every handler enforces

- `APPROVALS_QUEUE_GET`, `EXAM_APPROVE`, `EXAM_REJECT`: `requireRole(caller, COORDINATOR)` **plus**
  `Authorization.requireCoordinatorOf(caller, subjectCode, data::coordinates)` — the caller must
  coordinate *the subject of the exam's course*, resolved from the `coordinators` table inside the
  same transaction, never from the payload (P-5).
- The queue is additionally **scoped in the SQL**: `ExamRepository.findPendingForCoordinator` joins
  `coordinators` into the query, so a version outside her subjects is not fetched, cannot be counted,
  and cannot be returned by a future code path that forgot a filter. The service's own guard on every
  mutation is the second lock on the same door, not the only one.
- `EXAM_PREVIEW_GET`: `requireRole(TEACHER, COORDINATOR)`, then **either** the caller is the
  version's own author **or** the subject-coordinator guard applies. The author is allowed because
  F4.2 requires the rejection reason to be visible on the exam, and a teacher who cannot reopen what
  she submitted cannot act on the reason she was given. A teacher of the same course who did not
  write it and does not coordinate the subject is refused.
- ~~`MY_APPROVALS_GET`: any teacher or coordinator, scoped to **her own submissions** in the query
  itself. There is no id on the wire to misuse.~~ *Retired 2026-08-25 (ruling 1). `EXAM_LIST` is
  author-scoped in its own SQL with no id on the wire either, so the property survives the move.*
- "One coordinator per subject" is the primary key of `coordinators` (ARCHITECTURE §5), so every
  scoping question here has exactly one answer and no join can multiply a row.

### Optimistic locking is on the wire, on purpose

`exam_versions.status` is the one mutable field of an otherwise immutable row, which is why the table
carries `lock_version`. Every decision request echoes the `lockVersion` its screen was rendered from,
and the handler checks it **inside the transaction that writes**, alongside the status guard:

| what happened | answer |
|---|---|
| version is no longer `PENDING` | `CONFLICT` + `ApprovalMessages.NOT_PENDING` |
| `expectedLockVersion` ≠ the row's | `CONFLICT` + `ApprovalMessages.DECISION_RACED` |
| another writer won the flush (`StaleStateException`) | `CONFLICT` + `ApprovalMessages.DECISION_RACED` |

Both checks exist because they catch different things. The explicit check catches a **stale screen**
including one that a supersede moved (a bulk update, which does not bump `@Version`); the flush
failure catches two writers who read the same value. The client's response to `CONFLICT` is to
**reload, not retry** — the whole meaning of the refusal is that what it is holding is out of date.

---

## Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `APPROVALS_QUEUE_GET` | coordinator | `null` | `ApprovalQueue` |
| `EXAM_PREVIEW_GET` | coordinator of the subject, or the version's author | `ExamPreviewRequest` | `ExamPreview` |
| `EXAM_APPROVE` | coordinator of the subject | `ExamApproveRequest` | `ApprovalDecision` |
| `EXAM_REJECT` | coordinator of the subject | `ExamRejectRequest` | `ApprovalDecision` |
| ~~`MY_APPROVALS_GET`~~ | *retired 2026-08-25 into `EXAM_LIST` (ruling 1)* | — | — |

No push verb. Both decisions raise a **durable notification** to the author through
`Notifier`/`NotificationCatalog` (`APPROVAL_APPROVED` / `APPROVAL_REJECTED`), which is what reaches a
teacher who was signed out; the client refreshes its own list on `PUSH_NOTIFICATION`. A live push of
the decided row was considered and left out: an approval is not a designed moment, and the
notification already carries everything the author needs.

---

## DTOs (`common/dto/approval`)

- `ApprovalState` — wire enum `DRAFT | PENDING | APPROVED | REJECTED`, mirroring stored
  `ExamVersionStatus`. Bridged by an **exhaustive switch** (`ApprovalService.toWire`), not by
  `valueOf(name())`, so a value added on one side is a compile error rather than a runtime failure on
  a coordinator's screen. `ApprovalServiceTest` pins the two name sets together.
- `ApprovalRow(long examVersionId, String examDisplayId, String examName, String courseCode,
  String courseName, int versionNo, String authorName, Instant submittedAt, int questionCount,
  int durationMinutes, ApprovalState state, String rejectedReason, boolean selfAuthored,
  int lockVersion)` — **one row shape for two audiences**, the coordinator's queue and the author's
  own list, because they show the same facts about the same object. In the queue `state` is always
  `PENDING` and `rejectedReason` is empty. All strings normalise `null` → `""`.
  `selfAuthored` is computed **by the server against the session**, never by the client.
- `ApprovalQueue(List<ApprovalRow> rows, boolean coordinatesAnything)` — the flag distinguishes two
  empty states that look identical and mean opposite things: a finished inbox, and a caller who
  coordinates no subject at all (PRD §4.1 forbids one blank panel for both). Factories:
  `ApprovalQueue.empty()`, `ApprovalQueue.notACoordinator()`.
- `MyApprovals(List<ApprovalRow> rows)` — the author's non-draft versions, newest first, every state.
  `rejected()` filters the ones that were sent back.
- `ExamPreviewRequest(long examVersionId)`
- `ExamPreview(ApprovalRow summary, String studentText, List<ExamQuestion> questions,
  TeacherOnlyBlock teacherOnly)` — `summary` is **re-read at open time**, so the decision buttons
  send the `lockVersion` that was actually shown rather than one a stale list row remembered.
  `studentText` sits out here with the questions because a student sees it.
- `TeacherOnlyBlock(String teacherText, String authorName, List<PreviewAnswerRow> answerKey)` —
  everything a student never sees, fenced. `correctOptionOf(questionVersionId)` returns `0` for a
  question with no key entry, which a panel renders as "not available", never as option 0.
- `PreviewAnswerRow(long questionVersionId, int ordinal, byte correctOption)` — **the one record on
  this wire that carries an answer key.** Deliberately *not* `common.dto.grading.AnswerReviewRow`:
  that row is a *marked paper* and carries `chosen`, `isCorrect` and `pointsAwarded`, three facts
  that only exist once a student has sat the exam. Nothing has been sat here, so reusing it would
  mean sending a null choice, a false correctness and a zero score for every question, and the first
  reader to take those at face value would be reading a lie. Its *convention* is followed instead of
  its shape: one place correctness is serialized per audience, named so the audience is visible, with
  a tested guard in front of it. Rejects a key outside 1..4 in its compact constructor (a corrupt
  bank row must not render as a valid preview) and hides the key from `toString`.
- `ExamApproveRequest(long examVersionId, int expectedLockVersion)`
- `ExamRejectRequest(long examVersionId, String reason, int expectedLockVersion)` — the reason is
  **required**, trimmed by the compact constructor, and **minimum `MIN_REASON_LENGTH` = 10
  characters** after trimming. `ExamRejectRequest.validate(String)` is the single definition of that
  rule and **both tiers run it**: the client on every keystroke (button disabled, live hint), the
  server before it writes anything. Failing it answers `VALIDATION` with `REASON_REQUIRED` or
  `REASON_TOO_SHORT`, and the second names the number. The floor exists because a required field
  with no floor is satisfied by "no", and a teacher who receives "no" has been refused without being
  told anything she can act on — which is the outcome F4.2 exists to prevent.
- `ApprovalDecision(ApprovalRow row, boolean selfApproved)` — one type for both decisions, so the
  screen has one path afterwards. Carries the **re-read row** rather than an acknowledgement, for
  the reason `GRADE_OVERRIDE` answers with a refreshed review: a client patching its own row would
  be guessing at the new state and the new `lockVersion`. `confirmation()` is the toast sentence.

---

## F4.3 — self-approval, allowed and recorded

A coordinator **may** approve her own exam. The seed has exactly that case (`michal.sharon`
coordinates Computer Science and is the only Databases teacher), and refusing it would leave a
single-teacher subject unable to release anything.

- The action **succeeds**, and `ApprovalDecision.selfApproved` is `true`.
- The record is one structured **`WARN`** line from `ApprovalService`, whose shape is an interface
  with acceptance case 4.6:

  ```
  SELF-APPROVAL: coordinator {id} ({name}) approved her own exam {displayId} '{examName}' version {n} (F4.3)
  ```

  The leading token is `ApprovalMessages.SELF_APPROVAL_MARKER`, so a grep for it needs no pattern.
  `ApprovalServiceTest.SelfApproval` asserts the marker, the id, the name, the display id, the exam
  name, the version and the `F4.3` tag, because "allowed but logged" with no log line is a silent
  failure.
- **No notification is sent to her.** She is looking at the confirmation; telling somebody what they
  did one second ago is the noise that makes people stop reading their bell. Rejecting her own exam
  *does* notify, because a reason is a document she will come back to.
- The client shows a neutral `You wrote this one` badge and an extra line in the confirm dialog. It
  is information, never an obstacle: the rule permits it, and the software should not disapprove of
  something the specification allows.

---

## E8.2 — supersede, and the one hook E7 calls

`ApprovalService.versionSubmitted(long examVersionId)` is **not a verb**. It is the entry point E7.6
calls after it moves a version `DRAFT → PENDING`, and it does all three things the queue needs:

1. every **other** pending version of the same exam is sent back with the fixed system reason
   `ApprovalMessages.SUPERSEDED_REASON`, whose exact first sentence is:

   > `Superseded by a newer version. You submitted a newer version of this exam, so this one was withdrawn from the approval queue. Open the newest version to see where it stands.`

   Constant and recognisable on purpose: it lands in `exam_versions.rejected_reason` of rows nobody
   typed a reason for, and the teacher reading it must be able to tell the system apart from her
   coordinator's opinion. The write is a **status-guarded bulk UPDATE**
   (`ExamRepository.supersedePendingVersions`), so a version that stopped being pending mid-statement
   is not dragged back out of `APPROVED`;
2. the subject's coordinator gets an `APPROVAL_SUPERSEDED` notification, so a row that vanishes from
   her queue mid-read is explained rather than mysterious (new `NotificationType` constant + new
   `NotificationCatalog.approvalSuperseded` factory, E8);
3. the subject's coordinator gets the ordinary `APPROVAL_REQUESTED` notification for the new version.

**Point 3 was originally E7's to emit, and it moved here on purpose.** Folding it into this one hook
means E7's submit calls one method and cannot end up emitting a request for a version whose supersede
failed, or emitting two notifications in an order that reads backwards. **E7 owns the transition; E8
owns everything the queue sees.** → *Member A: `ExamService.submitForApproval` should call
`approvalService.versionSubmitted(examVersionId)` and emit no notification of its own.*

The hook is idempotent by construction (the supersede is status-guarded) and refuses to act for a
version that is not `PENDING` or does not exist, logging a warning rather than throwing: it must
never be the reason a teacher's submit fails.

A subject with **no coordinator** notifies nobody and logs a warning: a submission nobody can approve
is an administrative gap for the principal to fix, not an error to fail the teacher with.

---

## Route ids the notifications point at

The server names client routes as string literals (`NotificationCatalog`), and a route the client
does not know renders as a non-clickable row. Three ids matter here, and all three are now registered
in `client.core.Routes` — `AppArgsAndRoutesTest.notificationRoutesLineUp` pins the spelling:

| notification | route id | screen |
|---|---|---|
| `APPROVAL_REQUESTED`, `APPROVAL_SUPERSEDED` | `approvals` | `ApprovalQueueView` |
| `APPROVAL_APPROVED`, `APPROVAL_REJECTED` | `exams` | `MyApprovalsView` (E7 replaces it) |

`approvals.preview` is registered too, but nothing links to it from the server: it is reached from
the queue and carries `examVersionId` as a nav parameter.

---

## Open questions for the freeze

1. **`MY_APPROVALS_GET` and E7. — ANSWERED, and executed 2026-08-25.** This verb was deliberately
   the narrow approval-status read, named after approvals rather than after exams so it could not
   collide with E7.9's exam-list verb. E7.10 landed, `ExamList` absorbed it, and the verb is
   deleted. See ruling 1 above for what went with it.
2. **`selfAuthored` on `ApprovalRow`.** Caller-relative, computed server-side. Harmless today; if a
   row is ever cached client-side across users it becomes wrong. Lead to confirm this stays a
   per-response field rather than becoming a client-side comparison against the session.
3. **No push on a decision.** The author learns through a durable notification. If T-4 demo pacing
   wants the author's screen to move without a bell, that is one additive `PUSH_*` verb.

---

## Additive amendments

Everything below was added **after** the 2026-08-21 freeze, under the additive-only rule this
contract opened with: no verb renamed, no payload component removed, retyped or reordered, no
semantics changed for any existing field. A client built against v1 still works against a server
that implements the amendments, and vice versa.

### A1 — `EXAM_PREVIEW_GET` admits the principal (E15.2 / F9.3, U-44, added 2026-08-30)

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `EXAM_PREVIEW_GET` | coordinator of the subject, the version's author, **or the principal** | `ExamPreviewRequest` | `ExamPreview` |

**The role list grows by one and nothing else changes.** No new verb, no new DTO, no new field, no
new error code, and not one line of sections 1-9 touched. `Authorization.requireRole` on that
handler reads `TEACHER, COORDINATOR, PRINCIPAL`; the two decisions beside it still read
`COORDINATOR` alone, which is what keeps S-7 — "the principal holds literally zero mutating verbs"
— a property of three role lists rather than of a screen that hides its buttons.

**Why.** The lead's ruling of 2026-08-30 (U-44): the principal's Data screen listed the school's
questions, exams and sittings and nothing opened. F9.3 gives her a read of the data *as entered*,
and an exam read that stops at the catalogue row is a list of names. The other two tabs needed no
amendment at all — `QUESTION_GET` and `QUESTION_VERSIONS` have carried `PRINCIPAL` since E6 (BANK
§3), and a sitting's detail is the `ReportRow` `DATA_RESULTS_GET` already sends — so this is the
single smallest change that makes all three rows openable.

**Her scope is the school, so there is none to check.** The handler branches on the role and
returns before the author-or-coordinator test: spec 7.3.1 and F9.3 give her every course, so a
scope check on this path could only ever pass, and writing one would imply a slice that does not
exist. That is the same shape `DataBrowseService` and `ReportService` already wear. She still gets
`NOT_FOUND` for a version that does not exist, on the same line as everybody else.

**She sees the answer key, and that is the existing licence rather than a new one.** `ExamPreview`
carries `TeacherOnlyBlock`, and the correctness boundary this product defends is *students* — the
same reasoning BANK's ruling 2 of 2026-08-21 gave when it sent the principal `QuestionDetail` with
its key rather than inventing a keyless projection for her. `CorrectnessLeakGuardTest`'s licence
for `findAnswerKeyForAuthoring` names one more staff audience and no new one in kind; no
key-bearing type moved package, and `common.dto.exam` is untouched.

**Client.** Route `data.exam` (`DataExamView`), registered for the principal only and aliased to
`data`. It renders through `ExamPaperPane` — the paper column and the teacher-only pane lifted out
of `ExamPreviewView` in the same change — so the two screens draw one preview with one renderer,
which is rule 1 of this contract surviving a second reader. It builds no footer: no Approve, no
Send back, nothing disabled or hidden, and the session behind it (`DataExamSession`) has no method
that could send a decision.
