# Acceptance scenarios 4 and 5 — pre-walk below the screen (2026-08-25)

> *Transcribed into `main` on 2026-08-26 by the acceptance-fixes batch, so the evidence travels
> with `ACCEPTANCE_TESTS.md`. The pre-walk worktree it was written in (`hsts-acc2`) was reused for
> the scenarios 10–12 pass before this file had been committed, so this is a copy of the report as
> the fixes batch read it, not a fresh export.*
>
> **⚑ Renumbering.** This report and the scenarios 2–3 report drafted their `B-n` numbers
> independently, and both started at B-7. The canonical sequence in `ACCEPTANCE_TESTS.md`
> continues from the existing B-6 and gives the scenarios 2–3 report the first three. **This
> report's numbers therefore all shift by three:** its **B-7** (the scheduled-today window) is
> **B-10**; its **B-8** (the bell) is **B-11**; its **B-9** (the refusal copy) is **B-12**; its
> **B-10** (midterm naming drift) is **B-13**. The body below is left as it was written — read
> every `B-n` in it as the report's own draft number.

**Scope:** `docs/ACCEPTANCE_TESTS.md` §4 (exam approval, T-4) and §5 (out of the drawer, T-5),
twelve cases. **Owner of the table:** Member B · **Cells folded by:** the lead, one pass.
**This report does not edit `ACCEPTANCE_TESTS.md`.** Every case below carries a proposed
Actual-cell text ready to paste, a status, and the probe evidence the text is drawn from.

## Method, stated the way case 9.4 states it

Twenty-one probes under `src/test/java/acceptance/` walk the cases against **the production
services over the seeded database**:

- `SeededServerProbe` — the base. It wipes the schema, loads `docs/seed/SEED_CONTENT.md`
  through the same `SeedLoader` the `--reseed` console path uses, then assembles the objects
  `HSTSServer` assembles, in the order it assembles them: `NotificationService`,
  **the one `ApprovalService` the approval verbs are registered on**, `ExamHandlers` over that
  same instance, `AttemptService` → `MonitorService` → `ExecutionCloseService`, and
  `ReleaseService` + `ReleaseScheduler` over `JpaReleaseStore`.
- Requests go in through `MessageRouter.route`, which is the whole decision surface — role
  gates, handlers, services, repositories, MySQL, and the `AuthorizationException` → `ErrorCode`
  mapping. **Every sentence quoted below is a sentence the server put on the wire.**
- **What this does not exercise:** the socket layer and JavaFX. Nothing here proves a pixel.
  Claims about a rendered screen — the preview's layout, the status chips' colour, the cancel
  confirm dialog, the close-early warning — are recorded as *screen render at the manual pass*.
  The handler-to-socket hop is covered elsewhere by the existing handler tests
  (`ApprovalServiceTest`, `ReleaseServiceTest`, `ReleaseCloseIntegrationTest`,
  `ExamBuildWiringGuardTest` for the submit-hook wiring).
- **One honest simplification:** the wire role on each `CallerContext` is supplied by the probe
  as the login would derive it (§3 of the seed — `rina.barak` and `michal.sharon` are stored
  `TEACHER` plus a `coordinators` row, hence `Role.COORDINATOR`; `dana.cohen` is a plain
  `TEACHER`). The derivation itself lives in `RepositoryUserDirectory` and is not re-proved
  here; scenario 1 case 1.3 is where it was walked, and case 1.2's "no Approvals item on a plain
  teacher's rail" is its screen half.
- Where a case needs a *submitted* version, it is driven the way `ExamHandlers` drives it:
  `EXAM_VERSION_REVISE` then `EXAM_SUBMIT` through the router, so
  `ApprovalService.versionSubmitted` runs **after the handler's transaction commits** (contract
  §5.5 as amended 2026-08-24). Calling it from inside would read the row as still `DRAFT` and
  notify nobody, which is the failure mode §5.5 documents.

**Clock and windows.** The seed's four execution windows are relative to load time, so the seed
is reloaded fresh for **every** probe (both scenarios mutate the fixtures they read) and the
services are given `Clock.fixed` at that load's own anchor. The live sitting therefore straddles
"now" by construction. One observation falls straight out of this and is recorded as a finding:
see **B-7**.

**Result:** **21 probes, 21 green in one run** — `Scenario4ApprovalProbe` 12/12 (108.5 s),
`Scenario5ReleaseProbe` 9/9 (43.3 s), the two classes together in one JVM against
`hsts_acc2_repo`. Green here means *the case's steps were executed and produced
these values*, not that the case passes; two cases carry findings that hold them below ✅, and
every screen claim is still outstanding.

### Reproducing

```
export JAVA_HOME=<jdk21>
export HSTS_TEST_SCHEMA=hsts_acc2          # schema isolation; the suite drops <schema>_repo
./mvnw -o test -Dtest='Scenario4ApprovalProbe,Scenario5ReleaseProbe'
```

A **comma**, not a `+` — and recorded because this pass walked into it. With `+`, Surefire
matches nothing, runs zero tests and still prints `BUILD SUCCESS` in 3m49s of doing nothing.
Do not pass `-Dsurefire.failIfNoSpecifiedTests=false` with a `-Dtest` filter unless you want
that silence; it is the flag that turns a typo into a green build.

Evidence lines are printed with the `[EVIDENCE]` prefix; `grep EVIDENCE` over the build output
is the whole harvest.

### At a glance

| Case | Proposed status | Note |
|---|---|---|
| 4.1 | ✅ pass below the screen | Queue screen outstanding |
| 4.2 | ⚠ pass below the screen | ⚑ The *render* is what F4.1 was marked a v1 failure over; data verified, pixels not. B-9 on the refusal copy |
| 4.3 | ✅ pass | Fully evidenced server-side; three shapes driven |
| 4.4 | ⚠ partial | Stored ✅ visible ✅ delivered-as-a-row ✅ **bell ❌ — B-8** |
| 4.5 | ✅ pass below the screen | Both halves, including E8.2's supersede |
| 4.6 | ✅ pass | ⚑ The log line exists and is quoted in full |
| 5.1 | ✅ pass | ⚑ Refused twice, two different ways |
| 5.2 | ✅ pass below the screen | Four window shapes; dialog outstanding |
| 5.3 | ✅ pass | ⚑ `4821` is *accepted*, and correctly — see the cell |
| 5.4 | UI-only | Server-side gate verified and holds; the screen walk is the case |
| 5.5 | ✅ pass | ⚑ S-2, two seeded sittings plus two more |
| 5.6 | ✅ pass below the screen | ⚑ Force-submit chain verified end to end. **B-7 blocks the demo half** |

Findings raised: **B-7** (Medium, seed fixture), **B-8** (Medium, seed × enum), **B-9** (Low,
copy), **B-10** (Low, docs). None fixed — report-only, per this pass's brief.

---

## Scenario 4 — exam approval (T-4)

### 4.1 — the pending queue, scoped to her subject

**Status: pass (below the screen; queue *screen* outstanding).**

> **Proposed Actual cell**
>
> **Passed below the screen.** Run against a freshly loaded seed (375 rows, outcome `LOADED`)
> through `APPROVALS_QUEUE_GET`. `rina.barak`'s queue holds **exactly one row**: `101201 v1
> 'Midterm: Calculus', course 12 (Calculus), by Dana Cohen, 7 questions, 90 min, state PENDING,
> lockVersion 0, selfAuthored false` — the seeded PENDING fixture, and nothing else. The case's
> own step was then driven rather than assumed: `dana.cohen` submitted `101102` v1 through
> `EXAM_SUBMIT` (`DRAFT` → `PENDING`), and rina's queue went to two rows,
> `[101102 v1, 101201 v1]`, with the bell raised by the post-commit hook
> (`APPROVAL_REQUESTED: "Exam waiting for your approval"`). **The "her subject only" half is
> evidenced positively, not by absence:** `202102` was revised and resubmitted so subject 20 had
> something pending too, and the two queues then read `rina.barak → [101201]`,
> `michal.sharon → [202102]`. Scoping is in the SQL — `findPendingForCoordinator` joins
> `coordinators` — so a version outside her subjects is never fetched. A plain teacher has no
> queue at all: `dana.cohen` calling the verb is refused `FORBIDDEN`. The empty queue is
> answered two different ways as §4.1 requires: `michal.sharon` on the bare seed gets 0 rows
> with `coordinatesAnything = true`. **Screen render at the manual pass.**

**Evidence**

```
4.1 | seed load = "375 rows, outcome LOADED"
4.1 | seeded 101201 v1 status = PENDING
4.1 | rina.barak queue row = "101201 v1 'Midterm: Calculus' course 12 (Calculus) by Dana Cohen,
      7 questions, 90 min, state PENDING, lockVersion 0, selfAuthored false"
4.1 | michal.sharon queue size = 0
4.1 | michal.sharon coordinatesAnything = true
4.1 | dana.cohen (teacher) APPROVALS_QUEUE_GET = "FORBIDDEN / This action requires the COORDINATOR role."
4.1 | 101102 v1 status before submit = DRAFT   → after EXAM_SUBMIT = PENDING
4.1 | rina.barak queue after the submit = [101102 v1, 101201 v1]
4.1 | rina.barak notifications after the submit = [APPROVAL_REQUEST: "An exam is waiting for your
      approval in Mathematics", APPROVAL_REQUESTED: "Exam waiting for your approval"]
4.1 | 202102 v2 status = PENDING · rina.barak sees = [101201] · michal.sharon sees = [202102]
```

### 4.2 — the student-identical preview, plus the teacher notes ⚑

**Status: pass below the screen — the *render* is the half this case is about, and it is outstanding.**

> **Proposed Actual cell**
>
> **Passed below the screen; screen render outstanding.** `EXAM_PREVIEW_GET` for `101201` v1 as
> `rina.barak` returned the whole paper: summary `101201 v1 'Midterm: Calculus', 90 min, state
> PENDING, author Dana Cohen`; student text *"Justify every step. An answer with no
> justification will not receive full marks."*; **seven questions** in order — 12001, 12002,
> 12004, 12005, 12006, 12008 at 15 points and 12009 at 10 — each with four options; and beside
> them the `TeacherOnlyBlock` with the teacher-only note *"Remind Rina: questions 12006 and
> 12007 are new this year."*, the author's name, and a **7-row answer key**. **The v1 failure is
> fixed structurally, not by a screen:** the paper travels in the student's own wire type,
> `ExamQuestion`, whose record components are `[questionVersionId, displayId, ordinal, points,
> text, option1..option4, image]` — **no field a correct answer could travel in**. Correctness
> exists only in `PreviewAnswerRow(questionVersionId, ordinal, correctOption)`, in the
> staff-only block. The wall between the two audiences is in the types. Two callers are admitted
> and no more: the subject's coordinator, and the version's **own author** (`dana.cohen` opens it
> OK, which is what F4.2's "visible on the exam" needs); `avi.mizrahi`, who neither wrote it nor
> coordinates subject 10, is refused. **Not verified here:** that the coordinator's screen
> renders this with the same component the take-exam screen uses — that is a rendering claim and
> belongs to the manual pass. **Defect found on the refusal copy: B-9.**

**Evidence**

```
4.2 | summary = "101201 v1 'Midterm: Calculus', 90 min, state PENDING, author Dana Cohen"
4.2 | studentText = "Justify every step. An answer with no justification will not receive full marks."
4.2 | question count = 7 · answer key rows = 7
4.2 | teacherOnly.teacherText = "Remind Rina: questions 12006 and 12007 are new this year."
4.2 | teacherOnly.authorName = "Dana Cohen"
4.2 | question = "1. 12001 (15 pts) options: 4, image none" … "7. 12009 (10 pts) options: 4, image none"
4.2 | ExamQuestion record components = [questionVersionId, displayId, ordinal, points, text,
      option1, option2, option3, option4, image]
4.2 | PreviewAnswerRow record components = [questionVersionId, ordinal, correctOption]
4.2 | avi.mizrahi EXAM_PREVIEW_GET on 101201 v1 = "FORBIDDEN / This action requires the COORDINATOR role."
4.2 | dana.cohen (the author) EXAM_PREVIEW_GET = "OK"
```

### 4.3 — a rejection with no reason is refused

**Status: pass.**

> **Proposed Actual cell**
>
> **Passed.** Three shapes were tried against the seeded pending exam and each was refused
> server-side with its own sentence: an empty reason → `VALIDATION`, *"Type why you are sending
> this exam back. The teacher sees this reason."*; **whitespace only** → the same sentence, so
> the rule is about a real reason rather than a non-empty string; and `"no"` → `VALIDATION`,
> *"Give the teacher something to work with: at least 10 characters explaining what to change."*
> After all three, `101201` v1 was still `PENDING` with `rejected_reason = null` and
> `dana.cohen`'s notification count was unchanged at 2 — **nothing was half-applied and nobody
> was told about a rejection that did not happen.** The rule is `ExamRejectRequest.validate`,
> the same definition the client runs on every keystroke, checked here on the side that matters
> and before anything is read.

**Evidence**

```
4.3 | reject with an empty reason = "VALIDATION / Type why you are sending this exam back. The
      teacher sees this reason."
4.3 | reject with whitespace only = "VALIDATION / Type why you are sending this exam back. …"
4.3 | reject with "no" = "VALIDATION / Give the teacher something to work with: at least 10
      characters explaining what to change."
4.3 | 101201 v1 status after three refused rejections = PENDING · rejectedReason = null
4.3 | dana.cohen notification count before/after = "2 -> 2"
```

### 4.4 — the reason is stored, visible, and delivered

**Status: ⚠ partial — the three halves the case asks for are stored ✅, visible ✅, delivered
✅ as a row but ❌ unreadable in the bell on a seeded database. Finding B-8.**

> **Proposed Actual cell**
>
> **Stored and visible: passed. Delivered: the row is written and the bell cannot render it —
> B-8.** `rina.barak` rejected `101201` v1 with a reason; the decision came back `REJECTED`
> carrying the reason, and `exam_versions.rejected_reason` holds it **verbatim and trimmed**.
> *Visible on the exam:* `MY_APPROVALS_GET` as `dana.cohen` returned three rows — `101101 v2
> APPROVED`, `101201 v1 REJECTED` with the new reason, and `101101 v1 REJECTED` with the seed's
> own *"Only five questions for 60 minutes, and each one is worth too much. A wider spread is
> needed."* — so a reason survives a dismissed notification, which is the half a bell cannot
> provide. *Delivered:* one durable notification row was written to `dana.cohen`, type
> `APPROVAL_REJECTED`, title *"Exam sent back for changes"*, body *"Your subject coordinator did
> not approve Midterm: Calculus. Reason: …"*, ref `exams/25`. **But the bell she would open to
> read it does not open.** `NOTIFICATIONS_GET` for `dana.cohen` — before the rejection and after
> it — answers `INTERNAL`, *"Something went wrong on the server. Please try again."*, and so does
> it for `rina.barak`, `tamar.shani` and `avi.mizrahi`. `michal.sharon`, the one staff account
> the seed gives no notification, is the control: her bell opens fine, empty. The cause is
> **B-8** — two of `dana.cohen`'s three rows are seeded with type strings that are not
> `NotificationType` constants, and one unparseable row takes the whole page down, including the
> well-formed one this case just wrote. F4.2's delivery half is therefore evidenced **at the row
> and not at the screen**, and B-8 is what holds this cell at ⚠ rather than ✅.

**Evidence**

```
4.4 | decision row state = REJECTED
4.4 | stored exam_versions.rejected_reason = "Question 12009 is worth 10 while the rest are 15;
      even them out and add a limits question."
4.4 | dana.cohen notification type = "APPROVAL_REJECTED"
4.4 | dana.cohen notification title = "Exam sent back for changes"
4.4 | dana.cohen notification body = "Your subject coordinator did not approve Midterm: Calculus.
      Reason: Question 12009 is worth 10 while the rest are 15; even them out and add a limits question."
4.4 | dana.cohen notification ref = "exams/25"
4.4 | MY_APPROVALS_GET rows = 3
4.4 | MY_APPROVALS row = "101201 v1 REJECTED reason=…" · "101101 v1 REJECTED reason=Only five
      questions for 60 minutes…" · "101101 v2 APPROVED reason=\"\""
--- the bell (B-8) ---
4.4 | NOTIFICATIONS_GET for dana.cohen on the bare seed   = "INTERNAL / Something went wrong on the server. Please try again."
4.4 | NOTIFICATIONS_GET for rina.barak on the bare seed   = "INTERNAL / …"
4.4 | NOTIFICATIONS_GET for tamar.shani on the bare seed  = "INTERNAL / …"
4.4 | NOTIFICATIONS_GET for avi.mizrahi on the bare seed  = "INTERNAL / …"
4.4 | NOTIFICATIONS_GET for michal.sharon on the bare seed = "OK, payload NotificationsPage[items=[], unreadCount=0]"
4.4 | NOTIFICATIONS_GET for dana.cohen after the rejection = "INTERNAL / Something went wrong on the server. Please try again."
4.4 | her stored notification types = [EXAM_REJECTED, EXAM_PENDING, APPROVAL_REJECTED]
```

### 4.5 — approving a resubmitted version, and E8.2's supersede

**Status: pass (below the screen).**

> **Proposed Actual cell**
>
> **Passed, both halves, and the second one is the stronger.** *The case as written:* `101201`
> v1 was rejected with a reason, `dana.cohen` revised and resubmitted (`EXAM_VERSION_REVISE` →
> `EXAM_SUBMIT`, the hook running after the handler's commit as §5.5 requires), rina's queue
> then read `[101201 v2]`, and she approved it. **v2 is `APPROVED`; v1 is still `REJECTED` and
> still carries its own reason** — earlier versions keep their own status, which is C-2 holding
> on a running server. The author was notified: `APPROVAL_APPROVED`, *"Exam approved"*, body
> *"Your subject coordinator approved Midterm: Calculus. You can release it now."* — which names
> the next thing she will do, and is exactly the state case 5.1 then refuses to release from
> anything else. Approving the same version twice is refused rather than reapplied: `CONFLICT`,
> *"This exam is not waiting for approval any more. Open the approvals list again to see its
> current state."* *E8.2, driven separately:* resubmitting **while v1 was still pending**
> superseded it in one hook — v1 flipped to `REJECTED` with `"Superseded by a newer version. You
> submitted a newer version of this exam, so this one was withdrawn from the approval queue.
> Open the newest version to see where it stands."`, v2 is `PENDING`, and **her queue holds one
> row, not two**, so the coordinator is never asked to choose between two submissions of one
> exam. She was told both things, in order: `APPROVAL_SUPERSEDED: "A newer version replaced one
> in your queue"` and `APPROVAL_REQUESTED: "Exam waiting for your approval"`.

**Evidence**

```
4.5 | new version id/no = "36 v2" · 101201 v2 status after resubmit = PENDING
4.5 | queue after the resubmit = [101201 v2]
4.5 | approved row = "101201 v2 APPROVED, selfApproved false"
4.5 | stored v2 status = APPROVED · stored v1 status = REJECTED
4.5 | stored v1 rejectedReason = "Only seven questions and no limits question at all. Add one and resubmit."
4.5 | author notified: APPROVAL_APPROVED / "Exam approved" / "Your subject coordinator approved
      Midterm: Calculus. You can release it now."
4.5 | approving the same version again = "CONFLICT / This exam is not waiting for approval any
      more. Open the approvals list again to see its current state."
--- E8.2 ---
4.5 | after the second submission — v1 status = REJECTED
4.5 | after the second submission — v1 rejectedReason = "Superseded by a newer version. You
      submitted a newer version of this exam, so this one was withdrawn from the approval queue.
      Open the newest version to see where it stands."
4.5 | after the second submission — v2 status = PENDING
4.5 | her queue holds one row, not two = [101201 v2]
4.5 | rina.barak was told both things = [APPROVAL_REQUEST (seeded), APPROVAL_SUPERSEDED: "A newer
      version replaced one in your queue", APPROVAL_REQUESTED: "Exam waiting for your approval"]
```

### 4.6 — self-approval, allowed and logged (F4.3) ⚑

**Status: pass. The log line exists and is quoted in full.**

> **Proposed Actual cell**
>
> **Passed, and the log line is the point.** `202201` v1 is already `APPROVED` on the seed, so
> the case's action needed something waiting: `michal.sharon` revised her Databases Final and
> submitted it wearing her teacher hat. **Her own submission appeared in her own queue**, flagged
> `selfAuthored = true` — which is information rather than a warning, exactly as `ApprovalRow`'s
> contract says. She then approved it: `OK`, `ApprovalDecision.selfApproved = true`, and the
> version is `APPROVED`. **The record exists.** Exactly one `WARN` was emitted by
> `server.features.approval.ApprovalService`, and it reads in full:
>
> `SELF-APPROVAL: coordinator 96 (Michal Sharon) approved her own exam 202201 'Databases Final' version 2 (F4.3)`
>
> Marker first so a grep needs no pattern, then who, which exam, which version. "Allowed but
> logged" with no log line would have been a silent failure; it is not one. She was **not** sent
> a bell about something she did a second earlier (count 1 → 1), which is the deliberate
> exception in `announce`. The permission is also not a hole: `michal.sharon` approving a
> subject-10 exam is refused `FORBIDDEN` — *"You do not coordinate subject 10, so this exam is
> not yours to approve. Ask that subject's coordinator to look at it."* — and `101201` v1 was
> left untouched at `PENDING`.

**Evidence**

```
4.6 | 202201 v2 status before the decision = PENDING
4.6 | her own submission is in her own queue = [202201 v2 selfAuthored=true]
4.6 | ApprovalDecision.selfApproved = true · 202201 v2 status after = APPROVED
4.6 | WARN lines carrying the marker = 1
4.6 | server log line = "WARN server.features.approval.ApprovalService — SELF-APPROVAL:
      coordinator 96 (Michal Sharon) approved her own exam 202201 'Databases Final' version 2 (F4.3)"
4.6 | michal.sharon notifications before/after = "1 -> 1"
4.6 | michal.sharon approving a subject-10 exam = "FORBIDDEN / You do not coordinate subject 10,
      so this exam is not yours to approve. Ask that subject's coordinator to look at it."
4.6 | 101201 v1 status is untouched = PENDING
```

---

## Scenario 5 — taking an exam out of the drawer (T-5)

### 5.1 — only an APPROVED version leaves the drawer ⚑

**Status: pass.**

> **Proposed Actual cell**
>
> **Passed, and enforced twice in two different ways — which is the point.** `RELEASE_CREATE`
> against `101102` v1 (`DRAFT`) and against `101101` v1 (`REJECTED`) was refused each time with
> `VALIDATION` and the same sentence: *"Only an approved exam can be released. Ask your subject
> coordinator to approve this version, then release it."* — a refusal that names who unblocks
> it, because the way a teacher actually reaches it is by holding the dialog open while her
> coordinator sends the exam back. **`exam_executions` still held 4 rows afterwards**, so
> nothing was inserted on behalf of either refusal. And the picker never offers them in the
> first place: `RELEASE_OPTIONS_GET` for `dana.cohen` returned exactly `[101101 v2 (11)]` — the
> `APPROVED` filter is a `where` clause, so PRD §6's "impossible (not listed)" is a property of
> the query rather than of the client.

**Evidence**

```
5.1 | 101102 v1 = DRAFT · 101101 v1 = REJECTED · 101101 v2 = APPROVED
5.1 | releasing the DRAFT = "VALIDATION / Only an approved exam can be released. Ask your subject
      coordinator to approve this version, then release it."
5.1 | releasing the REJECTED = same sentence
5.1 | exam_executions rows after both refusals = 4
5.1 | dana.cohen's picker = [101101 v2 (11)]
```

### 5.2 — release the approved version; window validation

**Status: pass.**

> **Proposed Actual cell**
>
> **Passed, with four window shapes driven rather than two.** `101101` v2 released cleanly:
> execution created for *Midterm: Algebra* (course 11), 75 min, state `SCHEDULED`, with the
> window stored as given. The refusals each carry their own sentence, and they are different
> sentences because they are different mistakes: **close before open** and **close equal to
> open** → *"The closing time has to be after the opening time. Move one of them and try
> again."*; **a window entirely in the past** → *"That opening time has already passed. Pick a
> time from now on and try again."*; **a thirty-second window** → *"The window has to be at
> least a minute long. Move the closing time later and try again."* All four are refused
> `VALIDATION` **before any read** — `exam_executions` went from 4 rows to exactly 5, the one
> legitimate create. One deliberate non-refusal was checked too: an opening moment **two
> minutes in the past** is accepted, because `PAST_GRACE` is five minutes and a teacher who
> picks "now", reads the summary and presses Create is doing the commonest thing this screen is
> for. **Screen render (the date pickers, the summary) at the manual pass.**

**Evidence**

```
5.2 | created release = "execution 9 of Midterm: Algebra (11), code RNR2, window
      2026-08-26T18:54:02.499Z -> 2026-08-26T20:54:02.499Z, state SCHEDULED, duration 75 min"
5.2 | close before open   = "VALIDATION / The closing time has to be after the opening time. …"
5.2 | close equal to open = "VALIDATION / The closing time has to be after the opening time. …"
5.2 | a window entirely in the past = "VALIDATION / That opening time has already passed. …"
5.2 | a thirty-second window = "VALIDATION / The window has to be at least a minute long. …"
5.2 | exam_executions rows now = 5
5.2 | opening two minutes ago (inside PAST_GRACE) = "OK"
```

### 5.3 — the execution code ⚑

**Status: pass. Note for the lead: `4821` — the third code the step types — is *accepted*, and
correctly so. The Expected-result cell only covers the shape rule, so a reader could mistake the
acceptance for a miss; the Actual cell below says why it is not.**

> **Proposed Actual cell**
>
> **Passed, and the third code in the step needs a sentence of its own.** Shape:
> `"12"` and `"ABCDE"` are both refused `VALIDATION` — *"An exam code is 4 letters or digits.
> Change it, or leave it blank to generate one."* — and so is `"A-1B"`, so the rule is four
> **alphanumerics** rather than four characters. **`"4821"` is accepted**, and that is the rule
> working rather than a gap: the sitting holding `4821` is `CLOSED`, and uniqueness is a service
> rule about sittings a student could still walk into (§5 — MySQL has no partial unique index).
> The two codes a student *could* still type are refused by name: `"2075"` (LIVE) and `"5164"`
> (SCHEDULED) both answer *"That code is in use by a live or scheduled sitting. Pick another or
> leave it blank to generate one."* A code typed `"ab7q"` is stored `AB7Q` (C-1), and left
> blank the server rolled `RNR2` — four characters, all from the spoken-alphabet-safe set with
> `O/0` and `I/1` dropped, since a code exists to survive being read out across a room.

**Evidence**

```
5.3 | code "12"    = "VALIDATION / An exam code is 4 letters or digits. Change it, or leave it blank to generate one."
5.3 | code "ABCDE" = same sentence · code "A-1B" = same sentence
5.3 | status of the sitting that holds 4821 = CLOSED
5.3 | code "4821" (held by a CLOSED sitting) = "OK, accepted"
5.3 | code "2075" (LIVE) = "VALIDATION / That code is in use by a live or scheduled sitting.
      Pick another or leave it blank to generate one."
5.3 | code "5164" (SCHEDULED) = same sentence
5.3 | code typed "ab7q" is stored as = "AB7Q"
5.3 | code left blank, server generated = "RNR2"
```

### 5.4 — the code is never shown to a student

**Status: UI-only. What was verified underneath is the server-side gate, and it holds.**

> **Proposed Actual cell**
>
> **UI-only case; the screens are the manual pass's. What is verifiable below them was
> verified and holds.** Every verb in the release feature is role-gated to staff **before it
> reads anything**, so there is no path by which a student could be handed a release row at all:
> `maya.levi` calling `RELEASE_LIST_GET`, `RELEASE_OPTIONS_GET` and `RELEASE_CANCEL` is refused
> `FORBIDDEN` on each. `ReleaseRow` — the one wire type that carries `code` — is produced only
> by those verbs and by `PUSH_EXECUTION_STATUS`, which is addressed to `ReleaseRows.ownersOf`,
> i.e. the releasing teacher and the exam's author. And the type a student *does* receive, the
> take-exam paper `ExamQuestion`, has **no code field** — the same structural argument case 8.7
> makes about class statistics. **Outstanding for the manual pass:** walking `maya.levi`'s
> screens looking for the string, which is the half this case is actually written about. Copy
> defect found on the refusal sentence: **B-9**.

**Evidence**

```
5.4 | maya.levi calling RELEASE_LIST_GET    = "FORBIDDEN / This action requires one of the roles [TEACHER, COORDINATOR]."
5.4 | maya.levi calling RELEASE_OPTIONS_GET = same
5.4 | maya.levi calling RELEASE_CANCEL      = same
5.4 | ReleaseRow record components = [executionId, examVersionId, examName, courseCode,
      courseName, code, openAt, closeAt, extraMinutes, durationMinutes, state, counts]
5.4 | the student-facing take-exam paper (ExamQuestion) components = [questionVersionId,
      displayId, ordinal, points, text, option1..option4, image]   ← no code
```

### 5.5 — the same exam, out of the drawer many times (S-2) ⚑

**Status: pass.**

> **Proposed Actual cell**
>
> **Passed, and the seed proves the shape before a single request is sent.** `101101` v2 already
> has **two** sittings on the loaded seed — `4821` (CLOSED, 09:00→11:00 fourteen days back) and
> `2075` (LIVE, T−1h→T+1h) — one exam version, two releases, separate codes, windows,
> participants and statistics. A third and a fourth were then created from the same version with
> their own windows and codes (`M1A1` a day out, `M2B2` three days out); each got its own
> execution id, all four share one `examVersionId`, and the new ones start with zero
> participation. `dana.cohen`'s release list then read `[M2B2 SCHEDULED (0 started), M1A1
> SCHEDULED (0 started), 2075 LIVE (0 started), 4821 CLOSED (8 started)]` — newest window first,
> each row with its own state and its own counts. The eight on `4821` are counted from the
> attempt rows, not accumulated in a column.

**Evidence**

```
5.5 | seeded sitting of 101101 v2 = "code 4821, status CLOSED, 2026-08-11T09:00:00Z -> 11:00:00Z"
5.5 | seeded sitting of 101101 v2 = "code 2075, status LIVE, 2026-08-25T17:54Z -> 19:54Z"
5.5 | third sitting  = "execution 26, code M1A1, 2026-08-26T18:54Z -> 20:54Z"
5.5 | fourth sitting = "execution 27, code M2B2, 2026-08-28T18:54Z -> 20:54Z"
5.5 | dana.cohen's release list = [M2B2 SCHEDULED (0 started), M1A1 SCHEDULED (0 started),
      2075 LIVE (0 started), 4821 CLOSED (8 started)]
```

### 5.6 — cancel a scheduled release; close a live one early

**Status: pass below the screen (confirm dialog, warning and live chips outstanding). One
finding on the fixture: B-7.**

> **Proposed Actual cell**
>
> **Passed below the screen; the confirm, the warning and the live chips are outstanding.**
> *Cancel:* legal from `SCHEDULED` and from nowhere else. `michal.sharon` cancelled `5164`; the
> row came back `CANCELLED` and the stored status is `CANCELLED`. Cancelling it again →
> `CONFLICT`, *"This exam is already over, so there is nothing to cancel. Open your releases to
> see its results."*; cancelling the **live** `2075` → `CONFLICT`, *"This exam has already
> opened, so it cannot be cancelled. Use close early to end it now."* — the refusal hands her
> the other button rather than saying no. Ownership is real: `dana.cohen` cancelling
> `michal.sharon`'s release answers `NOT_FOUND`, *"That release could not be found. Open your
> releases again and pick one of yours."*, indistinguishable from an id that does not exist.
> *Close early:* refused from `SCHEDULED` (*"Only a live exam can be closed early. Cancel it
> instead if you want to call it off."*). Driven on the live sitting with two sitters — one
> mid-attempt, one already handed in — the close **force-submitted the straggler through the
> expiry path**: her attempt ended `TIMED_OUT` (not `SUBMITTED` — she did not hand it in, and
> that decides which screen she gets), and **it reached the grading seam**, which is the proof it
> went through the expiry path rather than a bespoke UPDATE inside the release feature; a paper
> closed any other way would be left unmarked for ever. The student who had already handed in
> was untouched. The execution ended `CLOSED` with its participation **frozen at started 2 /
> finished 1 / timed out 1** (S-21), and the row the teacher's screen repaints from carries the
> same three numbers. Closing twice is refused and changes nothing. *States:* derived per row
> against the server's own clock — `dana.cohen`'s list read `2075 LIVE` and `4821 CLOSED`,
> `michal.sharon`'s read `5164 SCHEDULED`, with `serverNow` on the payload so the client never
> derives a state from its own clock. **Outstanding for the manual pass:** the cancel confirm,
> the close-early warning, and that the chips update live. **Fixture defect found: B-7.**

**Evidence**

```
5.6 | 5164 stored status before = SCHEDULED
5.6 | dana.cohen cancelling michal.sharon's release = "NOT_FOUND / That release could not be
      found. Open your releases again and pick one of yours."
5.6 | cancelled row state = CANCELLED · 5164 stored status after = CANCELLED
5.6 | cancelling it a second time = "CONFLICT / This exam is already over, so there is nothing to
      cancel. Open your releases to see its results."
5.6 | cancelling the LIVE sitting = "CONFLICT / This exam has already opened, so it cannot be
      cancelled. Use close early to end it now."
5.6 | its owner closing a SCHEDULED sitting early = "CONFLICT / Only a live exam can be closed
      early. Cancel it instead if you want to call it off."
5.6 | before the close = "attempt 113 IN_PROGRESS, attempt 114 SUBMITTED"
5.6 | row after the close = "state CLOSED, counts started 2 / finished 1 / timed out 1"
5.6 | the straggler's attempt status = TIMED_OUT · the one already handed in = SUBMITTED
5.6 | papers handed to the grading seam = [113]
5.6 | frozen participation = "started 2, finished 1, timed out 1" · 2075 stored status = CLOSED
5.6 | closing it a second time = "CONFLICT / Only a live exam can be closed early. …"
5.6 | serverNow on the list = 2026-08-25T18:54:32.213655243Z
5.6 | dana.cohen's list rows = 2075 LIVE (0/0/0) · 4821 CLOSED (8/7/1)
5.6 | michal.sharon's list row = 5164 SCHEDULED
```

---

## Drafted `B-n` candidates

Numbered from **B-7**, continuing the register in `ACCEPTANCE_TESTS.md`. Report-only: no
production code was changed by this pass. Format follows the table at the bottom of that file.

*(Renumbered in the canonical register: B-7 → **B-10**, B-8 → **B-11**, B-9 → **B-12**,
B-10 → **B-13**.)*

| # | Found by | Severity | Status | What |
|---|---|---|---|---|
| B-7 | case 5.6 | **Medium** | Open | **The seed's "scheduled today" execution is only genuinely scheduled if the seed is loaded before 14:00 UTC.** `SEED_CONTENT.md` §9 pins execution 3 (`5164`) to `T+0 14:00 → 16:00`, resolved by `SeedTimes.dayOffsetAt` as **wall-clock UTC on the anchor's date**. Loaded at 18:54 UTC, its window came out `2026-08-25T14:00Z → 16:00Z` — already over — while the row is stored `SCHEDULED`. Nothing is wrong until the server runs: `ReleaseScheduler.tick()` was called once and **changed 2 releases**, taking `5164` `SCHEDULED → LIVE → CLOSED` inside a single 30-second pass. The scheduler is correct; the fixture is not. **What it costs:** every case written against a scheduled sitting evaporates within thirty seconds of the server starting for any demo or test session after 14:00 UTC — **5.6** (cancel a SCHEDULED release), **6.4** (enter the scheduled code before its open time), **10.5** (results for `5164` with no attempts), and hardening item H14.1. In local terms (UTC+3) the fixture is only correct before **17:00 Israel time**, which is not a safe assumption for a defence slot. **Why nothing caught it:** no seed test ticks the scheduler, and `SeedLoadedTestBase` pins its anchor to `2026-08-20T15:30Z` — which is itself *inside* the 14:00–16:00 window, so the fixture is already not-future in the canonical test and no assertion is written that would notice. **Fix (seed, one line):** resolve execution 3 relative to the anchor instant the way execution 4 already is — `SeedTimes.fromNow(+2h) → fromNow(+4h)` — so "scheduled, opening later today" is true whenever it is loaded, instead of true only in the morning. That is the same reasoning §9 already gives for execution 4. Found by ticking the production scheduler against a freshly loaded seed; every existing test passes throughout. |
| B-8 | case 4.4 | **Medium** | Open | **`NOTIFICATIONS_GET` fails outright on a freshly seeded database — every staff account the seed gives a notification to has a bell that will not open.** Observed, not inferred: the verb answers `INTERNAL`, *"Something went wrong on the server. Please try again."*, for `dana.cohen`, `rina.barak`, `tamar.shani` and `avi.mizrahi`, while `michal.sharon` — the one staff account the seed gives no notification — opens fine with `NotificationsPage[items=[], unreadCount=0]`. **One unparseable row takes the whole page down**, so the perfectly well-formed `APPROVAL_REJECTED` row case 4.4 had just written was unreadable beside two seeded ones. The cause: `JpaNotificationStore.toDto` maps the stored string with `NotificationType.valueOf(row.getType())`, and six of the eight rows `NotificationsSection` seeds carry values that are **not** constants of that enum — `EXAM_REJECTED`, `EXAM_PENDING`, `APPROVAL_REQUEST`, `GRADING_DUE`, `EXECUTION_CLOSED` (only `GRADE_PUBLISHED` matches). The enum holds `APPROVAL_REQUESTED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`, `APPROVAL_SUPERSEDED`, `GRADE_PUBLISHED`, `TIME_EXTENDED`, `BOT_SOURCE_CHANGED`, `RELEASE_OPENING_SOON`. `NotificationType`'s own javadoc says these names are persisted and must never be renamed — the seed simply never used them. **What it costs:** the four refusals above are **observed**; `principal.avia` (`EXECUTION_CLOSED`) is not driven by these probes and follows from the same mapping, so treat her as very likely and confirm her at the manual pass. `noa.friedman` and `yael.azulay` hold the only two well-formed seeded rows (`GRADE_PUBLISHED`), which is why case 8.5's "bell showed 1 unread" passed for a student and nobody noticed the staff side. The bell is the delivery half of F4.2 that case 4.4 has to evidence, PRD §5's "notification bell non-zero" that case **17.3** checks, and NFR-21's populated-at-login requirement. **Why nothing caught it:** the seed tests assert on rows and never read them back through the DTO, and the notification tests build their rows through `NotificationService`, which writes `type.name()` and therefore always round-trips. Nothing joins the two, and this pass found it only because it drove the verb on a seeded database. **Fix, seed side (preferred):** change the six strings in `NotificationsSection` to the enum's own constants and make the mapping compile-checked by seeding `NotificationType` values rather than strings — the loader's idempotency key is recipient+type+title, so the change is a straight substitution. **Also worth doing, server side:** `toDto` should degrade rather than throw on an unknown stored type, so one bad row cannot take a user's whole bell down. Found by driving `NOTIFICATIONS_GET` while walking case 4.4; no unit test on either side can see it. |
| B-9 | cases 4.2, 5.4 | Low (copy) | Open | **Two role-refusal sentences break PRD §4.1's copy rules — one leaks a Java array literal, the other misnames the rule.** `Authorization.describe` builds the sentence with `Arrays.toString(allowed)`, so a student who reaches any staff verb is told *"This action requires one of the roles **[TEACHER, COORDINATOR]**."* — square brackets and enum constants in a sentence a user reads, which is the "never an error code" rule in §4.1 and the thing case **21.3** looks for. The single-role branch reads better (*"This action requires the COORDINATOR role."*) but is **wrong on `EXAM_PREVIEW_GET`**: that verb admits `TEACHER` **and** `COORDINATOR` and refuses on *subject*, so a plain teacher who is not the author is told she needs a role — when the author, a plain teacher, may open it perfectly well. `ApprovalService.preview`'s own javadoc promises the refusal "names what to do next rather than pretending the exam does not exist", and the sibling sentence on the same path does exactly that: *"You do not coordinate subject 10, so this exam is not yours to approve. Ask that subject's coordinator to look at it."* **Fix:** give `describe` a human list ("a teacher or a coordinator"), and let `EXAM_PREVIEW_GET` answer a non-author, non-coordinator with a sentence about the exam rather than about roles. Neither is urgent; both are small, and both are on screens a reviewer will drive. |
| B-10 | cases 4.1, 4.2, 5.2 | Low (docs) | Open | **The seeded exam names and teacher notes do not match `SEED_CONTENT.md`.** §8 writes *"Midterm — Algebra"*, *"Midterm — Calculus"*, *"Quiz — Inequalities"* and *"Marking note: question 7 — accept a reasoned graphical solution too."*; `ExamsSection` loads *"Midterm: Algebra"*, *"Midterm: Calculus"*, *"Quiz: Inequalities"* and *"Marking note: question 7, accept a reasoned graphical solution too."* The substitution looks deliberate (§4.1's copy rules ban em dashes) but is recorded nowhere, and `ACCEPTANCE_TESTS.md`'s own key-rows line uses a third spelling, *"Algebra Midterm"*. **What it costs:** any case that quotes an exam name as an expected value reads as a mismatch, and the acceptance evidence in this report has to quote the loaded string rather than the documented one. **Fix:** one editing pass over `SEED_CONTENT.md` §8 and §8.2 to match what the loader writes, plus a line saying why. Not a code defect. |

---

## Outstanding for the manual pass

Everything below is a screen claim these probes cannot make. Each is written as it would be
driven at the manual pass.

| Case | What is still to be seen |
|---|---|
| 4.1 | The Approvals rail item and the queue screen: one row, its metadata, and the empty state a coordinator with nothing waiting gets. |
| 4.2 | ⚑ **The heart of the case.** That the preview renders the paper with the *same component* the take-exam screen renders, and that the teacher-only block is visually separate from it. The data is right; the rendering claim is the one F4.1 was marked a v1 failure over. |
| 4.3 | The reason box refusing before the request travels, and its character counter. |
| 4.4 | The reason on the author's exam screen, and the bell — **blocked by B-8 until the seeded types are fixed.** |
| 4.5 | The queue losing the superseded row live, and the notification arriving without a refresh (case 18.2 covers the push half). |
| 4.6 | Nothing further below the screen; the log line is the case's evidence and it exists. |
| 5.2 | The create dialog's date pickers, the live validation as she types, and the summary before Create. |
| 5.3 | The code field, and that a generated code is shown to the teacher clearly enough to read out. |
| 5.4 | ⚑ **The whole case.** Walking `maya.levi`'s screens — dashboard, Take Exam, My Grades — looking for the code. Nothing below the screen can prove a string is absent from a view. |
| 5.6 | The cancel confirm, the close-early warning, and that the Scheduled / Live / Closed chips update **live** without a refresh. **B-7 must be fixed first** or there is no scheduled row to cancel after 14:00 UTC. |

## Files

- `src/test/java/acceptance/SeededServerProbe.java` — the seeded database and the production wiring.
- `src/test/java/acceptance/Evidence.java` — the observation recorder.
- `src/test/java/acceptance/Scenario4ApprovalProbe.java` — 12 probes, §4.
- `src/test/java/acceptance/Scenario5ReleaseProbe.java` — 9 probes, §5.
