# HSTS v2 — Reverse traceability matrix

**Task:** E22.0 · **Owner:** Member B writes, [L] reviews · **Feeds:** the submission document and
the defense.

This is the answer sheet for one question, asked about any requirement the project uses:
*"where is it, what proves it, and is it actually working today?"*

> **Ported into `main` on 2026-08-26 (batch A), with two rows corrected on arrival.** The matrix
> was written in a worktree against `origin/main` at `6bff812`, and two of its three headline gaps
> had already moved by the time it landed. **F3.1 is closed by this batch** — `exams.build` is
> declared, registered for both teaching roles and mapped to `ExamBuilderView`, so
> `ExamBuilderWiringGuardTest`'s four red-by-design cases are green and the row is now
> LIVE-unwalked. **F3.2's stated blocker is stale** — `BankQuestionRow.latestVersionId` has been on
> the wire since BANK amendment A1 shipped with the integration commit, so the contract half is
> done and only the one-method client adoption remains. Both corrections are marked in place with
> the reasoning, rather than silently rewritten: a matrix that quietly re-states itself is worth
> less than one that shows what moved.

---

## How to read a row

| Column | What it holds |
|---|---|
| **Id** | The requirement id, exactly as the PRD, the spec-derived list, or PRD §1 spells it. |
| **One-line claim** | The requirement compressed to what a defense answer needs. The PRD is the authority; this column is a handle, not a substitute. |
| **Implemented in** | The **owning** class or package — the one that would have to change if the requirement changed. Not every class that touches it. Client and server halves are both named where the requirement spans the wire. |
| **Guarded by** | The test class(es) that go **red** if the requirement breaks. Preferred, in order: a named guard/contract test, a service unit test, then a session/interaction test. Found by grepping the id, which this codebase cites in Javadoc, then verified by reading what the test actually asserts. |
| **Acceptance** | `scenario.case` numbers **from `ACCEPTANCE_TESTS.md`'s own numbering**, cross-checked against that file rather than copied from the PRD's `[T-n]` tags. A bare scenario number means the whole scenario covers the id and no single case names it. |
| **Status** | Honest, defined below. |

### The honest-status convention

| Status | Means |
|---|---|
| **LIVE** | Implemented, test-guarded, **and** its acceptance case has been walked on a running server and passed. |
| **LIVE-unwalked** | Implemented and test-guarded, but its acceptance scenario is still `⬜` in `ACCEPTANCE_TESTS.md`. The code is there; nobody has driven it end to end at a keyboard. This is the majority status and it is not a defect — it is the state of scenarios 2–7 and 10–21. |
| **PARTIAL — \<one word\>** | Something named is missing, and the word says what. |
| **GAP** | Nothing implements it, or what exists cannot be reached, or the id resolves to nothing at all. **A gap is never written up as a partial**, however small the remaining work looks. |
| **N/A** | A deliberate non-goal with a documented decision behind it (phase-2 scope). Used exactly once, for S-43, and not counted as a gap. |

Two rules this document holds itself to:

1. **An id that appears nowhere in `src/` is reported as such**, in the Notes column and again in
   the gaps section, rather than assigned a plausible owner. Sixteen ids are in that state; most
   are implemented under a neighbouring citation and are traceability defects rather than
   functional ones, and each says which.
2. **"Ticked in TODO.md" is not evidence.** Several rows below are more finished than their TODO
   boxes and several are less. Where they disagree, the code and the tests win and the row says so.

**Totals:** 136 ids mapped — 73 `F`, 45 `S`, 8 `C`, 10 `NFR`/`X`.
Counts per status are in [the gaps section](#gaps-and-partials-gathered).

**Baseline:** worktree at `origin/main` = `6bff812`, 2026-08-26. CI on that commit is **red by
design** — four assertions in `ExamBuilderWiringGuardTest`, all naming the same missing route
registration (see F3.1).

---

## F1 — Authentication & sessions

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F1.1 | Username + BCrypt password login; one generic failure message; 5 failures → 30s lockout | `server.features.auth.AuthService`, `LoginThrottle`, `UserDirectory` | `AuthServiceTest`, `LoginIntegrationTest`, `UserDirectoryContract` | 1.1 ✅, 1.4 ⚠ | **PARTIAL — throttle-unwalked** |
| F1.2 | Post-login shell (rail, home, permissions) derives from role + course relations | `client.ui.shell.RoleNav` / `AppShell`, `client.features.login.ShellBoot` | `RoleNavTest`, `BankScreenWiringGuardTest`, `ExamListWiringGuardTest` | 1.2 ✅, 1.3 ✅ | **LIVE** |
| F1.3 | No concurrent double login; socket drop frees the session immediately | `server.core.SessionManager`, `AuthService` | `SessionManagerTest`, `LoginIntegrationTest`, `ProtocolLoopbackTest` | 16.1, 16.2, 16.3 | **LIVE-unwalked** |
| F1.4 | Logout; locks and in-progress state cleaned on logout/disconnect | `AuthService`, `SessionManager` disconnect hooks, `client.features.login.ShellBoot` | `AuthServiceTest`, `SessionManagerTest`, `LockConcurrencyIntegrationTest` | 16.3 | **LIVE-unwalked** |
| F1.5 | Connect screen before login, pre-filled, last server remembered, discovery never blocking | `client.core.ConnectPrefs`, `client.features.connect.ConnectView` / `ConnectFlow` | `ConnectPrefsTest`, `ConnectFlowTest`, `DiscoveryClientTest` | 1.1 ✅, 15.3 | **LIVE** (15.3 unwalked) |

## F2 — Question bank

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F2.1 | Teacher adds a question for a taught course: 4 distinct answers, exactly one correct, topic, difficulty, optional image | `server.features.bank.QuestionService` + `QuestionValidator`; `client.features.bank.QuestionEditorSession`, `ui.components.RadioGroup` / `ImagePicker` | `QuestionValidatorTest`, `QuestionServiceTest`, `BankRoundTripIntegrationTest`, `QuestionEditorSessionTest` | 2.1, 2.2 | **LIVE-unwalked** |
| F2.2 | Question id = 2-digit course + 3-digit serial, server-allocated, read-only | `server.db.ids.QuestionIdAllocator` | `AllocatorContract`, `BankRoundTripIntegrationTest` | 2.1 | **LIVE-unwalked** |
| F2.3 | Edit creates version n+1; old versions stay queryable and viewable | `QuestionService`, `server.db.repos.QuestionRepository`; `client.features.bank.BankSession` history panel | `BankRepositoryContract`, `BankBrowseContract`, `BankCopyTest`, `BankSessionTest` | 2.4, 2.5 | **LIVE-unwalked** |
| F2.4 | Browse/filter by course/topic/difficulty/text; list+detail; lazy image preview | `server.features.bank.BankBrowseService`, `QuestionImages`; `client.features.bank.BankView` | `BankBrowseContract`, `QuestionImagesTest`, `BankSessionTest`, `BankScreenInteractionTest` | 2.6 | **LIVE-unwalked** (see NFR-17 — the seed ships no image bytes to preview; registered as **B-8** in `ACCEPTANCE_TESTS.md`, which the scenario-6 walk widened) |
| F2.5 | Delete blocked while any exam version references it, naming those exams; otherwise soft-delete | `QuestionService`, `server.db.projections.ReferencingExam` | `QuestionServiceTest`, `BankRoundTripIntegrationTest` | 2.7, 2.8 | **LIVE-unwalked** |
| F2.6 | Opening an editor takes an advisory lock; others get a live badge + read-only view | `server.features.locks.EditLockService` / `EditLockGuard`; `client.features.locks.LockAwareEditor` | `EditLockServiceTest`, `EditLockGuardTest`, `LockAwareEditorTest`, `LockVisibilityTest` | 2 (no case names it), 13.6 | **LIVE-unwalked** |

## F3 — Exam building

The epic with the live wounds. The **server** half is complete and heavily tested; the **client**
half landed in `#52` and is not on any rail.

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F3.1 | Create an exam for a taught course: name, duration, both texts, per-question points, author recorded; save blocked while Σ ≠ 100 | `server.features.exambuild.ExamService` + `ExamValidator` + `ExamHandlers`; `client.features.exambuild.ExamBuilderSession` / `ExamBuilderView` | `ExamValidatorTest`, `ExamServiceTest`, `ExamBuildRepositoryContract`, `ExamBuilderSessionTest`, `ExamBuilderInteractionTest`, **`ExamBuilderWiringGuardTest`** | 3.1, 3.2, 3.3 | **LIVE-unwalked** *(was PARTIAL — unreachable; the route landed 2026-08-26)* |
| F3.2 | Manual composition: pick from the course bank, reorder, assign points | `ExamService.saveComposition`; `ExamBuilderSession` (reorder / repoint / remove) | `ExamServiceTest`, `ExamBuilderSessionTest` | 3.2, 3.8, 3.9 | **GAP** (the picker's add path — **the contract half is done; see the corrected note below**) |
| F3.3 | Auto composition from count + topic + difficulty quotas; on infeasibility **no exam is created** and the report names the exact shortfall | `server.features.exambuild.AutoComposer`, `ExamValidator.quotaProblem`, `ExamBuildMessages` | `AutoComposerTest` (28 cases, incl. a 400-shape property test), `ExamValidatorTest`, `ExamServiceTest`, `AutoComposeResultTest`, `SeedDatasetContract` (the thin Recursion topic) | 3.4, 3.5, 3.6 | **PARTIAL — no-UI** |
| F3.4 | Exam id = subject(2)+course(2)+serial(2), server-allocated | `server.db.ids.ExamIdAllocator` | `AllocatorContract`, `ExamBuildRepositoryContract` | 3.1 | **LIVE-unwalked** |
| F3.5 | Edit ⇒ new version, old retained; a question may live in many exams | `ExamService.revise`, `server.db.repos.ExamBuildRepository`; `client.features.exambuild.ExamListSession` | `ExamBuildRepositoryContract`, `ExamListSessionTest`, `AuthoringDtoTest` | 3.7, 3.8 | **LIVE-unwalked** |
| F3.6 | Per-version state `DRAFT → PENDING_APPROVAL → APPROVED / REJECTED` with chips everywhere | `common.dto.approval.ApprovalState`, `ExamService`; `client.ui.components.logic.ChipCatalog` | `ChipCatalogTest`, `ExamListInteractionTest`, `ExamListSessionTest` | 3.3, 4.5 | **LIVE-unwalked** |

## F4 — Exam approval

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F4.1 | Coordinator's pending queue for her subject; opens the exam exactly as a student sees it, plus metadata and teacher-only notes | `server.features.approval.ApprovalService` / `ApprovalData`; `client.features.approval.ExamPreviewView` reusing `client.features.exam.ExamFormView` | `ApprovalServiceTest`, `ApprovalSessionTest`, `ApprovalInteractionTest`, `ApprovalDtoTest` | 4.1, 4.2 | **LIVE-unwalked** |
| F4.2 | Reject requires a reason; reason stored, shown on the exam, pushed to the author. Approve → APPROVED for that version | `ApprovalService`, `client.features.approval.RejectDialog`, `server.features.notify.NotificationCatalog` | `ApprovalServiceTest`, `ApprovalSessionTest`, `ExamListSessionTest`, `NotificationPresenterTest` | 4.3, 4.4, 4.5 | **LIVE-unwalked** |
| F4.3 | A coordinator may approve her own exam — allowed, but the approval log records self-approval | `ApprovalService` (the SELF-APPROVAL log line), `ApprovalMessages` | `ApprovalServiceTest`, `ApprovalCopyTest` | 4.6 | **LIVE-unwalked** ⚑ |

> Case 4.6 is written to check the **log line itself** ("allowed but logged with no log line is a
> silent failure"). Nothing but a walk can close it; the unit test asserts the call, not the output.

## F5 — Release ("out of the drawer")

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F5.1 | Only an APPROVED **version** is releasable; the same exam releases many times | `server.features.release.ReleaseService` / `ReleaseData` | `ReleaseServiceTest`, `JpaReleaseStoreContract`, `ExecutionRepositoryContract` | 5.1, 5.5 | **LIVE-unwalked** |
| F5.2 | Open/close datetimes validated (open < close, close in future); the window is enforced at start | `ReleaseService`, `ReleaseScheduler` | `ReleaseServiceTest`, `ReleaseSchedulerTest`, `ReleaseWireTest` | 5.2, 6.4 | **LIVE-unwalked** |
| F5.3 | A 4-character alphanumeric execution code, delivered orally, never shown to a student in-app | `server.features.release.ExecutionCodes`; `common.dto.exam.ExamJoinRequest` | `ExecutionCodesTest`, `ReleaseCopyTest`, `ReleaseManagerInteractionTest` | 5.3, 5.4 | **LIVE-unwalked** |
| F5.4 | Release list with live `Scheduled / Live / Closed` chips and pushed participant counters | `ReleaseRows`, `ReleaseScheduler`, `ReleaseAnnouncer`; `client.features.release.ReleaseManagerSession` | `ReleaseSchedulerTest`, `ReleaseManagerSessionTest`, `ChipCatalogTest` | 5.6, 7.4 | **LIVE-unwalked** |
| F5.5 | Cancel a scheduled release; close a live one early, behaving exactly like time expiry | `ReleaseService`, `server.features.exam.ExecutionCloseService` | `ReleaseCloseIntegrationTest`, `ReleaseServiceTest`, `ReleaseCopyTest` | 5.6 | **LIVE-unwalked** |

## F6 — Taking an exam

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F6.1 | Code → own national id → the exam form renders | `server.features.exam.AttemptService`, `StudentIdentity`; `client.features.exam.ExamEntrySession` | `AttemptServiceTest`, `StudentIdentityTest`, `ExamEntrySessionTest` | 6.1, 6.2, 6.3 | **LIVE-unwalked** |
| F6.2 | Timer starts at id entry, server-authoritative, drift-corrected, amber at 25% / red at 5 min | `AttemptService`, `TimerService`; `client.ui.components.logic.CountdownLogic` | `CountdownLogicTest`, `AttemptServiceTest`, `JpaExamStoreContract` | 6.5 | **LIVE-unwalked** |
| F6.3 | Debounced auto-save; reconnect resumes with saved answers and correct remaining time | `AttemptService` (`SAVE_ANSWER`, resume); `client.features.exam.ExamAttemptSession`, `SaveState` | `AttemptServiceTest`, `ExamAttemptSessionTest`, `ExamConcurrencyIntegrationTest` | 6.6 | **LIVE-unwalked** |
| F6.4 | On expiry the server force-submits and marks `TIMED_OUT`; the client shows an unconfirmable full-screen takeover | `TimerService`, `AttemptService`; `client.features.exam.ExamDoneView` | `AttemptServiceTest`, `ReleaseCloseIntegrationTest`, `TakeExamInteractionTest`, `ExamCopyTest` | 6.8 | **LIVE-unwalked** |
| F6.5 | Actual solving time in minutes recorded per student, manual submit or timeout alike | `AttemptService`, `server.db.entities.ExamAttempt.actualMinutes` | `AttemptServiceTest`, `CheckedFormServiceTest`, `ReleaseCloseIntegrationTest` | 9.5 ⚠ | **LIVE** *(id uncited — carried under S-19)* |
| F6.6 | The take-exam DTO physically has no correctness field | `server.features.exam.ExamPaper`, `server.db.projections.TakeExamQuestion` | `TakeExamProjectionShapeTest`, `ExamWireLeakGuardTest`, `WireDtoLeakGuardTest` | 6.7 | **LIVE-unwalked** ⚑ |
| F6.7 | One attempt per student per execution; re-entry says "already submitted" | `AttemptService`, `DuplicateAttemptException` | `AttemptServiceTest`, `ExamConcurrencyIntegrationTest`, `ExamFlowRepositoryContract` | 6.10 | **LIVE-unwalked** |
| F6.8 | Mid-attempt: own-course bot locked; another course's bot warns, then notifies the teacher and flags the monitor row | `server.features.exam.AttemptTracker` / `AttemptRegistry`, `server.features.bot.BotService` | `AttemptRegistryTest`, `BotServiceTest`, `ExtendAndMonitorTest`, `AttentionEventsTest` | 14.7 | **LIVE-unwalked** |
| F6.9 | Manual submit is two-step: answer-summary grid, remaining time, unanswered-score-0 note | `client.features.exam.AttemptModel`, `AnswerGridView`, `ui.components.WarnConfirm` | `AttemptModelTest`, `ExamAttemptSessionTest`, `ExamCopyTest` | 6.9 | **LIVE-unwalked** |
| F6.10 | Post-submit success screen: handed-in time, solving minutes, summary, back to dashboard | `client.features.exam.ExamDoneView`, `ExamAttemptSession` | `AttemptServiceTest`, `TakeExamInteractionTest`, `ExamCopyTest` | 6.9 | **LIVE-unwalked** |

## F7 — Extension & live monitoring

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F7.1 | Extend a live execution; every active student's timer grows immediately, named and explained | `server.features.exam.ExtendService`; `client.ui.components.CountdownTimer`, `client.features.exam.ExamAttemptSession` | `ExtendAndMonitorTest`, `ExamAttemptSessionTest`, `CountdownLogicTest`, `ExamDtoTest` | 7.1, 18.3 | **LIVE-unwalked** |
| F7.1b | Focus-loss attention events reported and shown as a neutral count on the teacher's row; no student-facing UI, no auto-penalty | `client.features.exam.AttentionTracker`; `server.features.exam.AttemptRegistry` | `AttentionTrackerTest`, `AttentionEventsTest`, `ExecutionMonitorInteractionTest` | 7.4 (no case names it) | **LIVE-unwalked** |
| F7.2 | Live monitor: started / submitted / timed-out counts, per-student status and remaining time, all pushed | `server.features.exam.MonitorService` / `MonitorPublisher`; `client.features.exam.ExecutionMonitorSession` | `ExtendAndMonitorTest`, `ExecutionMonitorSessionTest`, `ExecutionMonitorInteractionTest` | 7.4 | **LIVE-unwalked** |
| F7.3 | Execution record's three counts are **derived** from attempts, then frozen at close — no mutable counters | `ExecutionCloseService`, `server.db.projections.ParticipationCounts` | `ExtendAndMonitorTest`, `ExamConcurrencyIntegrationTest`, `ReleaseCloseIntegrationTest` | 7.4 | **LIVE-unwalked** |

## F8 — Grading

Scenario 8 is walked and green — all seven cases, 2026-08-23, four bugs found and fixed.

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F8.1 | Auto-check on submission: per-question points, correct ⇔ the single correct answer | `server.features.grading.AutoGrader`, `GradingOnSubmit` | `AutoGraderTest`, `GradingOnSubmitTest` | 8.1 ✅ | **LIVE** |
| F8.2 | Teacher reviews per-student results, approves singly and in bulk, may comment to the student | `GradeApprovalService`, `GradingQueueService`, `OverrideService`; `client.features.grading.GradingQueueView` | `GradeApprovalServiceTest`, `GradingQueueServiceTest`, `GradingHandlersTest`, `GradingQueueSessionTest`, `TeacherCommentFlowContract` | 8.5 ✅ | **PARTIAL — no-review-screen** *(id uncited)* |
| F8.3 | A manual grade change requires a justification; auto score, new score, reason and actor all stored | `OverrideService` | `OverrideServiceTest`, `TeacherCommentFlowContract`, `GradingHandlersTest` | 8.3 ✅, 8.4 ✅ | **LIVE** |
| F8.4 | Only after approval does the student see grade + checked form + comments, with a push notification | `server.features.results.CheckedFormService`, `ResultsService`, `GradeApprovalService` | `CheckedFormServiceTest` (mutation-tested), `GradeApprovalServiceTest`, `ExecutionRepositoryContract` | 8.2 ✅, 8.6 ✅, 9.1 ✅, 18.4 | **LIVE** |
| F8.5 | On completion, stats stored per execution: avg, median, **population σ (divisor n)**, min/max, **pass rate at ≥ 55**, deciles. Never visible to students | `server.features.grading.ScoreStatistics`, frozen by `GradeApprovalService`; read via `server.features.results.FrozenStatistics` | `ScoreStatisticsTest` (hand-computed against seeded 4821), `JpaTeacherResultsStoreContract` (stored-not-recomputed), `ReportEngineTest` | 8.7 ✅, 10.3, 12.4 | **LIVE-unwalked** (computed and stored; the σ / pass-rate figures have not been read off a screen) |

## F9 — Results, data & reports

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F9.1 | A student sees her own grades and checked forms only, server-enforced, and can obtain a copy | `server.features.results.ResultsService`, `CheckedFormService`; `client.features.results.MyGradesView`, `CheckedFormView` | `CheckedFormServiceTest` (five ownership probes), `ResultsHandlersTest`, `MyGradesSessionTest`, `ResultsCopyTest` | 9.1 ✅, 9.2 ✅, 9.3 ✅, 9.4 ✅, 9.5 ⚠ | **LIVE** (9.5 proven below the screen only) |
| F9.2 | Teacher sees results for every exam **she wrote**, incl. others' executions; first-class histogram with mean/median/±1σ and stat cards | `server.features.results.TeacherResultsService`; `client.ui.components.StatChart` + `logic.StatChartLogic` / `StatChartData` | `TeacherResultsServiceTest`, `JpaTeacherResultsStoreContract`, `StatChartLogicTest`, `TeacherResultsInteractionTest`, `StatChartInteractionTest` | 10.1–10.5, 20.3 | **LIVE-unwalked** |
| F9.3 | Principal browses bank, exams and results with **zero** mutating verbs authorized | `server.features.reports.DataBrowseService`, `server.features.bank.BankReadHandlers`, `server.core.Authorization`; `client.features.data.DataView` | `DataBrowseServiceTest`, `AuthorizationTest`, `BankReadHandlersTest`, `DataBrowserInteractionTest` (asserts no push button exists), `RoleNavTest` | 11.1–11.4 | **LIVE-unwalked** |
| F9.4 | One parameterized report mechanism (dimension = Strategy) comparing avg/median/deciles by teacher, course, student | `server.features.reports.ReportEngine` over `DimensionStrategy`, `ReportStrategies`; `client.features.reports.ReportsView` | `ReportEngineExtensibilityTest` (a fourth strategy served by the real engine), `ReportEngineTest`, `ReportDtoTest`, `ReportsInteractionTest` | 12.1–12.5, 19.1, 20.3 | **LIVE-unwalked** (stated deviation: no grouped bar chart across rows — E15.4) |

## F10 — Concurrency & edit locks

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F10.0 | Lock visibility starts in the **list**: rows badge their editor's name, live; viewing never contends | `server.features.locks.EditLockService` (`LOCK_WATCH`, `LOCKS_SNAPSHOT`); `client.features.bank.BankRowLocks` | `LockVisibilityTest`, `BankSessionTest`, `BankMessagesTest` | 2 | **LIVE-unwalked** (bank list only — the only list with lockable rows today) |
| F10.1 | Generalized advisory EditLock service: entity type + id + holder, TTL heartbeat, released on close/logout/disconnect/timeout | `server.features.locks.EditLockService`, `EntityScopes` | `EditLockServiceTest` (incl. expiry races), `EntityScopesTest`, `LockConcurrencyIntegrationTest` | 13.6 | **LIVE-unwalked** *(id uncited)* |
| F10.2 | Everyone viewing a locked entity gets "Being edited by \<name\>" and read-only; release flips the UI live | `client.features.locks.LockAwareEditor`, `LockBanner`, `EditLockState` | `LockAwareEditorTest`, `EditLockStateTest`, `LockCopyTest`, `LockVisibilityTest` | 13.6 | **LIVE-unwalked** *(id uncited)* |
| F10.3 | Every editable entity carries `@Version`; a stale write is refused as `CONFLICT` with a reload offer | `server.db.entities.*` `@Version`, `server.features.locks.EditLockGuard`, `BankMessages` | `EntityRoundTripTest`, `EditLockGuardTest`, `BankSessionTest` | 2 | **LIVE-unwalked** |
| F10.4 | Applies to questions, exams, bot sources, release schedules, grading a submission | `QuestionService` + `ExamService` (server guard); `QuestionEditorView` + `BotManagerView` (client) | `EditLockGuardTest`, `LockConcurrencyIntegrationTest` | 13.6 | **PARTIAL — two-of-five** |

## F11 — Notifications

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F11.1 | Persistent notifications + live push for all eight types, including the C-4 possible-cheating alert | `server.features.notify.NotificationService`, `Notifier`, `NotificationCatalog` (nine drafts, every one with a caller) | `NotificationStoreContract`, `ApprovalServiceTest`, `GradeApprovalServiceTest`, `ExtendAndMonitorTest`, `ReleaseSchedulerTest`, `BotAdminServiceTest` | 4.4, 8.5 ✅, 14.7, 18.4 | **LIVE-unwalked** |
| F11.2 | Navbar bell with unread badge; panel with relative time, icons, click-through, mark-read | `client.features.notify.NotificationsPanel`, `NotificationsModel`, `ui.shell.ShellState` | `NotificationsInteractionTest`, `NotificationPresenterTest` | 8.5 ✅ (bell showed 1 unread), 18.4 | **LIVE-unwalked** |
| F11.3 | Toasts for transient feedback, separate from persistent notifications | `client.features.notify.NotificationPresenter`, `ui.components.ToastStack`, `logic.ToastSpec` | `NotificationPresenterTest`, `ToastQueueTest`, `NotificationsInteractionTest` | 21.4 | **LIVE-unwalked** |

## F12 — Study bot

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F12.1 | Teacher creates the bot for a taught course; one bot per course, a second teacher extends it | `server.features.bot.BotAdminService` | `BotAdminServiceTest`, `BotFeatureRepositoryContract` | 13.1, 13.5 | **LIVE-unwalked** |
| F12.2 | Sources: PDF, Word, free text — parsed server-side into indexed chunks at upload, failures surfaced | `server.features.bot.SourceExtractor`, `Chunker` | `SourceExtractorTest`, `BotAdminServiceTest` | 13.2, 13.3 | **LIVE-unwalked** |
| F12.3 | Sources add/edit/remove for any teacher of the course, edit-locked, co-teachers notified | `BotAdminService`; `client.features.bot.BotManagerView` | `BotAdminServiceTest`, `RecordingNotifier` | 13.4, 13.6 | **LIVE-unwalked** |
| F12.4 | Active/inactive toggle; a student needs enrolled **and** active **and** not locked out | `BotService`, `BotAdminService`, `BotData.isEnrolled` | `BotServiceTest`, `BotAdminServiceTest` | 14.2, 14.3 | **LIVE-unwalked** |
| F12.5 | Student chat: incremental display, typing indicator, course context header | `client.features.bot.BotChatView` / `BotChatModel` / `BotChatSession` | `BotChatModelTest`, `BotChatSessionTest`, `BotInteractionTest` | 14.1 | **LIVE-unwalked** |
| F12.6 | Provider chain DeepSeek → Anthropic, server-side only, keys never on the client or in git | `server.features.bot.ProviderChain`, `DeepSeekProvider`, `AnthropicProvider`, `BotConfig` | `ProviderChainTest`, `DeepSeekProviderTest`, `AnthropicProviderTest`, `BotConfigTest` — **all against mocked HTTP** | 19.3 | **PARTIAL — keys-unverified** ⚑ |
| F12.7 | No usable answer → one friendly sentence, never a stack trace or an empty bubble | `BotService`, `common.dto.bot.BotAnswer` | `BotServiceTest`, `BotMessagesTest`, `BotChatModelTest`, `ProviderChainTest` | 14.4 | **LIVE-unwalked** |
| F12.8 | Model context holds course sources + course bank questions and **cannot** reach exam data; guardrail prompt refuses embedded instructions | `server.features.bot.ContextBuilder`, `Guardrails`, `BotData` | `BotIsolationGuardTest` (compile-time isolation), `GuardrailsTest` (hostile fixtures), `ContextBuilderTest`, `BotFeatureRepositoryContract` | 14.4, and PRD §6's bot lines | **LIVE-unwalked** ⚑ |
| F12.9 | Every Q/A pair persisted with a timestamp inside a bot session, as a JSON transcript | `server.features.bot.JpaBotStore`, `server.db.converters.BotTranscriptConverter` | `BotFeatureRepositoryContract`, `BotServiceTest` | 14.5 | **LIVE-unwalked** |
| F12.10 | Student sees her own history and can reopen and continue a session | `client.features.bot.BotHistorySession` / `BotHistoryView` | `BotHistorySessionTest`, `BotChatSessionTest`, `BotServiceTest` | 14.5 | **LIVE-unwalked** |
| F12.11 | Teacher sees anonymized aggregates — no student identity in the view **or its DTOs** | `BotAdminService`, `common.dto.bot.BotAnalytics`; `client.features.bot.BotAnalyticsView` | `BotAnalyticsIdentityGuardTest`, `BotAdminServiceTest`, `BotAnalyticsSessionTest` | 14.6 | **LIVE-unwalked** ⚑ |

## F13 — Server console

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F13.1 | Terminal logs **and** a JavaFX console: listener control, DB status, connected clients, live log tail, health | `server.console.ServerConsoleApp`, `ConsoleView`, `ConsoleHealth`, `ConsoleClients` | `ConsoleHealthTest`, `ConsoleHealthProbeH2Test`, `ConsoleClientsTest`, `TerminalLogFormatTest` | 15.1 | **LIVE-unwalked** |
| F13.2 | Automatic LAN IPv4 detection with all candidates shown, manual override, `--headless` | `server.console.NetworkDetector`, `server.core.ServerArgs` | `NetworkDetectorTest`, `ServerArgsTest`, `ConsoleModelTest` | 15.1 | **LIVE-unwalked** |
| F13.3 | UDP discovery responder answering {ip, port, name, fingerprint}; fingerprint persisted; malformed/flood packets ignored and logged | `server.discovery.DiscoveryResponder`, `ServerFingerprint`, `common.dto.discovery.DiscoveryProtocol` | `DiscoveryResponderTest` + `DiscoveryProtocolTest` (2000-case fuzz each), `ServerFingerprintTest` | 15.3 | **LIVE-unwalked** |
| F13.4 | Client discovery picker, ~2s timeout to manual entry, TOFU pinning, fingerprint-mismatch warning | `client.features.connect.ConnectFlow`, `DiscoveryClient`, `client.core.ServerPin` | `ConnectFlowTest` (exhaustive decision table), `DiscoveryClientTest`, `ConnectPrefsTest`, `DiscoveryLoopbackTest` | 15.3, 15.5 | **LIVE-unwalked** |

## F14 — Packaging & deployment

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| F14.1 | `G<Num>_Server.jar` / `G<Num>_Client.jar` launch by double-click and by `java -jar`; external properties beside the JARs | `pom.xml` shade + `jar.prefix` profile, `common.config.ExternalConfig`, `client.core.AppArgs` | `ExternalConfigTest`, `AppArgsAndRoutesTest`, `TerminalLogFormatTest` | 15.1, 15.2 | **PARTIAL — unverified-on-Windows** |
| F14.2 | First run: Flyway migrations automatic, seed offered; connect screen pre-filled | `server.db.DbBootstrap`, `server.db.seed.SeedLoader` / `SeedMain`, console button (E19.6) | `FlywayCleanRunTest`, `SeedLoadedDbContract`, `ConsoleModelTest` | 17.1, 17.2 | **LIVE-unwalked** |

---

## S — spec requirements

`S-n` ids come from the system description. They are cited in Javadoc across the tree; the sixteen
that are not are marked, and each says where its substance actually lives.

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| S-1 | One coordinator per subject | `server.db.entities.Coordinator`, `Authorization.requireCoordinatorOf`, `server.db.seed.FacultySection` | `ApprovalServiceTest`, `AuthorizationTest`, `SeedDatasetContract` | 4.1 | **LIVE-unwalked** |
| S-2 | One exam, many executions, each with its own window, code and statistics | `server.features.release.ReleaseService`, `server.db.repos.ExecutionRepository` | `JpaReleaseStoreContract`, `SeedLoadedDbContract`, `ReportEngineTest` | 5.5 | **LIVE-unwalked** |
| S-3 | Subjects and courses come from an external system: read-only, seeded | `server.db.entities.Subject` / `Course`, `server.db.seed.SubjectsSection` | `SeedDatasetContract`, `FlywayCleanRunTest`; structurally, `Verb.java` declares no course or subject write verb | 17.1 | **LIVE-unwalked** |
| S-4 | Users and permissions come from an external system: we never build user CRUD | `server.db.entities.User`, `common.dto.auth.Role`, `server.db.seed.FacultySection` | Structural — none of the 68 verbs mutates a user | 17.1 | **LIVE-unwalked** |
| S-5 | A teacher works only within the courses she teaches | `Authorization.requireTeachesCourse` (implemented against `course_teachers` in E6), `BankBrowseService`, `QuestionValidator` | `AuthorizationTest`, `BankBrowseContract`, `BankBrowseServiceTest` | 2.3 | **LIVE-unwalked** |
| S-6 | A teacher creates a bot only for a course she teaches | `server.features.bot.BotAdminService`, `server.db.repos.UserRepository` | `BotAdminServiceTest` | 13.1 | **LIVE-unwalked** |
| S-7 | Principal is read-only, school-wide | `server.features.reports.DataBrowseService`, `BankReadHandlers` | `DataBrowseServiceTest`, `DataSessionTest`, `DataBrowserInteractionTest`, `ReportServiceTest` | 11.3, 11.4 | **LIVE-unwalked** |
| S-8 | Question id = 5 digits, course(2) + serial(3) | `server.db.ids.QuestionIdAllocator` | `AllocatorContract`, `QuestionLockKeyTest`, `BankSessionTest` | 2.1 | **LIVE-unwalked** |
| S-9 | **Unresolved.** Cited once, in `ACCEPTANCE_TESTS.md`'s scenario-2 header, and defined nowhere — not in the PRD, not in any contract, not in `src/` | — | — | 2 (header only) | **GAP** |
| S-10 | Exam id = 6 digits, subject(2) + course(2) + exam(2) | `server.db.ids.ExamIdAllocator` | `AllocatorContract`, `ExamBuildRepositoryContract` | 3.1 | **LIVE-unwalked** |
| S-11 | Per-question points set by the author | `server.features.exambuild.ExamService`, `ExamValidator`, `common.dto.authoring.QuestionPin` | `ExamValidatorTest`, `ExamBuilderSessionTest` | 3.2, 3.3 | **LIVE-unwalked** |
| S-12 | The authoring teacher is recorded on the exam | `common.dto.authoring.ExamCreateRequest`, `ExamService` (author from the caller, never the payload) | `ExamBuildRepositoryContract`, `AuthoringDtoTest` | 3.1 | **LIVE-unwalked** |
| S-13 | Questions carry difficulty, because auto-generation needs it | `server.db.entities.Difficulty`, `AutoComposer` | `AutoComposerTest`, `ExamValidatorTest` | 3.4–3.6 | **LIVE-unwalked** |
| S-14 | Approval and scheduling attach to a specific exam **version** | `server.db.entities.ExamVersion` / `ExamVersionStatus`, `ApprovalService`, `ReleaseService` | `ApprovalServiceTest`, `ReleaseServiceTest`, `SeedDatasetContract` | 2.5, 4.5, 5.1 | **LIVE-unwalked** |
| S-15 | Students may start only inside the open–close window | `AttemptService`, `server.db.projections.ExecutionContext` | `AttemptServiceTest`, `ExecutionRepositoryContract` | 6.4 | **LIVE-unwalked** |
| S-16 | Execution code may contain digits and letters (resolved by C-1 to 4 alphanumerics) | `server.features.release.ExecutionCodes` | `ExecutionCodesTest` | 5.3 | **LIVE-unwalked** |
| S-17 | The code is delivered orally and never displayed to a student | `ExecutionCodes`, `common.dto.release.ReleaseRow`, `common.dto.exam.ExamJoinRequest` | `ReleaseCopyTest`, `ReleaseManagerInteractionTest`, `ExecutionCodesTest` | 5.4 | **LIVE-unwalked** |
| S-18 | The timer starts at id entry and is server-authoritative | `AttemptService`, `TimerService`, `StudentIdentity` | `AttemptServiceTest`, `StudentIdentityTest`, `JpaExamStoreContract`, `CountdownLogicTest` | 6.5 | **LIVE-unwalked** |
| S-19 | Actual solving time in minutes recorded per student | `AttemptService`, `server.db.projections.AttemptRecord` | `AttemptServiceTest`, `CheckedFormServiceTest`, `ReleaseCloseIntegrationTest` | 9.5 ⚠ | **LIVE** (below the screen only) |
| S-20 | An extension applies to the current execution only; the stored exam is untouched | `server.features.exam.ExtendService` | `ExtendAndMonitorTest`, `JpaExamStoreContract`, `ReleaseServiceTest` | 7.2, 7.3 | **LIVE-unwalked** |
| S-21 | Execution record: date/time, actual allotted duration, #started, #finished, #ran out | `ExecutionCloseService`, `server.db.entities.Participation`, `ParticipationCounts` | `ExtendAndMonitorTest`, `ExamConcurrencyIntegrationTest`, `ReleaseCloseIntegrationTest`, `ExamFlowRepositoryContract` | 7.4 | **LIVE-unwalked** |
| S-22 | The teacher may add a comment the student reads | `server.features.grading.OverrideService` (comment rides `GradeOverrideRequest`, amendment A3) | `TeacherCommentFlowContract` (walks 8.4 on both engines), `OverrideServiceTest`, `GradingHandlersTest` | 8.4 ✅ | **LIVE** |
| S-23 | A manual grade change requires a justification, and the justification never reaches the student | `OverrideService`, `server.db.entities.Grade` | `OverrideServiceTest`, `TeacherCommentFlowContract`, `GradingHandlersTest`, `SeedLoadedDbContract` | 8.3 ✅, 8.4 ✅ | **LIVE** |
| S-24 | Grade + checked form visible only after teacher approval | `CheckedFormService`, `ResultsService`, `server.db.entities.GradeStatus` | `CheckedFormServiceTest`, `ExecutionRepositoryContract` | 8.2 ✅, 8.6 ✅, 9.1 ✅, 9.2 ✅ | **LIVE** |
| S-25 | Statistics computed and **stored** per execution | `server.features.grading.ScoreStatistics`, `server.db.entities.ExecutionStats` | `ScoreStatisticsTest`, `JpaTeacherResultsStoreContract`, `JpaReportStoreContract` | 12.4 | **LIVE-unwalked** |
| S-26 | Statistics are never visible to a student | Structural: `common.dto.grading.MyGrades` and `CheckedForm` have no field a class statistic could travel in; every statistics verb is teacher-gated | `WireDtoLeakGuardTest`, `ResultsHandlersTest`, `TeacherResultsServiceTest` | 8.7 ✅ | **LIVE** *(id uncited)* |
| S-27 | Use an existing bot through its API; do not build one | `server.features.bot.BotProvider` + the two adapters (ADR-009) | `ProviderChainTest`, `DeepSeekProviderTest`, `AnthropicProviderTest` | 19.3 | **LIVE-unwalked** |
| S-28 | Source types: PDF, Word, free text, plus the course question bank | `SourceExtractor`, `ContextBuilder`, `server.db.projections.BotBankQuestion` | `SourceExtractorTest`, `ContextBuilderTest`, `BotServiceTest` | 13.2 | **LIVE-unwalked** |
| S-29 | A bot carries a name and information sources | `server.db.entities.Bot`, `BotSource`, `BotAdminService` | `BotAdminServiceTest`, `BotFeatureRepositoryContract` | 13.1, 13.2 | **LIVE-unwalked** *(id uncited — substance sits under F12.1 / S-28)* |
| S-30 | One bot per course; a second teacher extends the existing one | `BotAdminService`, `server.db.repos.BotRepository` | `BotAdminServiceTest`, `BotFeatureRepositoryContract` | 13.5 | **LIVE-unwalked** |
| S-31 | A student uses the bot only if enrolled and the bot is active | `BotService`, `BotData.isEnrolled` | `BotServiceTest`, `SeedLoadedDbContract` (Databases bot seeded inactive) | 14.2, 14.3 | **LIVE-unwalked** |
| S-32 | No usable answer → a friendly, actionable message | `ProviderChain`, `BotService`, `server.features.bot.BotMessages` | `ProviderChainTest`, `BotServiceTest`, `BotMessagesTest`, `BotChatModelTest` | 14.4 | **LIVE-unwalked** |
| S-33 | Each Q/A pair persisted with a timestamp, inside a conversation | `server.db.entities.BotTranscript`, `BotTranscriptConverter`, `BotService` | `BotFeatureRepositoryContract`, `BotServiceTest`, `BotInteractionTest` | 14.5 | **LIVE-unwalked** |
| S-34 | Teacher aggregate view is anonymized: totals, over time, frequent questions | `BotAdminService`, `server.db.entities.BotMessage`, `TextNormaliser` | `BotAnalyticsIdentityGuardTest`, `BotAdminServiceTest`, `TextNormaliserTest` | 14.6 | **LIVE-unwalked** |
| S-35 | A teacher sees results for exams she wrote, including executions run by others | `TeacherResultsService` (scoped on `exams.author` in the query) | `TeacherResultsServiceTest`, `JpaTeacherResultsStoreContract`, `ResultsRepositoryContract` | 10.1 | **LIVE-unwalked** |
| S-36 | The student can obtain a copy of the checked exam | `client.features.results.CheckedFormView` print layout + `.results-print` CSS | Walked as case 9.3; no automated guard (a stylesheet that matches nothing is invisible to tests — that was bug B-6) | 9.3 ✅ | **LIVE** |
| S-37 | A new report type must cost near-nothing to add | `ReportEngine`, `DimensionStrategy`, `ReportStrategies` | `ReportEngineExtensibilityTest` (asserts the engine's own source names no dimension) | 12.5, 19.1 | **LIVE-unwalked** |
| S-38 | Username + password authentication | `server.features.auth.AuthService`, `UserRecord` | `AuthServiceTest`, `UserDirectoryTest`, `LoginIntegrationTest` | 1.1 ✅ | **LIVE** |
| S-39 | **Unresolved.** Cited once, in `ACCEPTANCE_TESTS.md`'s scenario-15 header, and defined nowhere | — | — | 15 (header only) | **GAP** |
| S-40 | Many users work concurrently on separate machines | `server.core.SessionManager`, OCSF listener, `HSTSServer` | `SessionManagerTest`, `ProtocolLoopbackTest`, `ExamConcurrencyIntegrationTest`, `LockConcurrencyIntegrationTest` | 15.4, 16.4 | **LIVE-unwalked** *(id uncited)* |
| S-41 | A relational database holds the data | Flyway `src/main/resources/db/migration` + Hibernate 6.6 + MySQL 8 | `FlywayCleanRunTest`, every `*MySqlTest` leaf | 17.1 | **LIVE-unwalked** *(id uncited)* |
| S-42 | Phase 1 is a LAN, GUI client over TCP/IP — not a web app | `common.dto.auth.LoginRequest`, OCSF transport, JavaFX client | `ProtocolLoopbackTest`, `DiscoveryLoopbackTest` | 15.4 | **LIVE-unwalked** |
| S-43 | Phase 2 adds internet access | Deliberately not built — DECISIONS §"phase-2 asset"; a deployment change, not a redesign | — | 19.3 (rehearsal answer) | **N/A** (documented non-goal) |
| S-44 | No user-initiated refresh; the UI reports progress and failure in human words | See NFR-18 and NFR-21 | `BankSessionTest`, `MyGradesSessionTest`, `ExecutionMonitorInteractionTest`, `NotificationsInteractionTest` | 18.1–18.5, 21.1–21.4 | **LIVE-unwalked** *(id uncited)* |
| S-45 | The design tolerates change | See NFR-19 | `ReportEngineExtensibilityTest` | 19.1–19.3 | **LIVE-unwalked** *(id uncited)* |

---

## C — resolved ambiguities (PRD §1, binding)

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| C-1 | Execution code is 4 alphanumeric characters, case-insensitive on entry | `server.features.release.ExecutionCodes`, `AttemptService` | `ExecutionCodesTest`, `ReleaseServiceTest`, `AttemptServiceTest`, `ReleaseWireTest`, `ExamEntrySessionTest` | 5.3 | **LIVE-unwalked** |
| C-2 | Immutable versions: editing creates n+1; past exams keep the version they pinned | `server.db.entities.QuestionVersion` / `ExamVersion`, `QuestionService`, `ExamService` | `BankRepositoryContract`, `AutoGraderTest` (grades against the **pinned** version), `SeedDatasetContract` | 2.5, 3.7 | **LIVE-unwalked** |
| C-3 | Auto-check → teacher approval → **then** visible to the student | `GradingOnSubmit`, `GradeApprovalService`, `CheckedFormService` | `GradingOnSubmitTest`, `GradeApprovalServiceTest`, `CheckedFormServiceTest`, `TeacherCommentFlowContract` | 8.2 ✅, 8.6 ✅, 9.1 ✅ | **LIVE** |
| C-4 | Bot lockout is per-course; a cross-course bot use mid-attempt warns, then notifies the teacher and flags the monitor row | `server.features.exam.AttemptTracker` / `AttemptRegistry`, `BotService`, `NotificationCatalog.integrityAlert` | `AttemptRegistryTest`, `BotServiceTest`, `BotIsolationGuardTest`, `ExtendAndMonitorTest`, `BotChatSessionTest` | 14.7 | **LIVE-unwalked** |
| C-5 | Statistics are computed and stored per execution; reports **read** the stored values | `server.db.entities.ExecutionStats` + `ExecutionStatsConverter`; `server.features.results.FrozenStatistics` | `JpaTeacherResultsStoreContract` (stored-not-recomputed), `JpaReportStoreContract`, `ReportEngineTest` | 12.4 | **LIVE-unwalked** |
| C-6 | `Exam` and `ExamExecution` are distinct entities; one exam, many executions | `server.db.entities.Exam`, `ExamExecution`, `ExecutionStatus` | `EntityRoundTripTest`, `JpaReleaseStoreContract`, `ExamFlowRepositoryContract` | 5.5, 7.2 | **LIVE-unwalked** |
| C-7 | Question shape: text + 4 answers + exactly one correct + optional image + course + topic + difficulty | `server.features.bank.QuestionValidator`, `server.db.entities.QuestionVersion` | `QuestionValidatorTest`, `ExamValidatorTest`, `BankRoundTripIntegrationTest` | 2.1, 2.2 | **LIVE-unwalked** |
| C-8 | Exactly one correct answer, enforced; the four answers pairwise distinct after trim + collapse, case-insensitive | `QuestionValidator` (`correctAnswerInRange`, `sameAnswer`), DB constraint `ck_question_versions_distinct`, `AutoGrader` | `QuestionValidatorTest` (two nested classes), `BankRoundTripIntegrationTest` (found the Hebrew final-form divergence, P-9), `AutoGraderTest`, `SeedDatasetContract` | 2.2, 8.1 ✅ | **LIVE-unwalked** |

---

## NFR and X — non-functional requirements

| Id | Claim | Implemented in | Guarded by | Acceptance | Status |
|---|---|---|---|---|---|
| NFR-15 | Client and server on separate machines, as JARs, with a connect GUI | `pom.xml` shade profile, `client.features.connect.ConnectView`, `server.console` | `ExternalConfigTest`, `ProtocolLoopbackTest`; the two-machine run itself is manual | 15.1–15.5 | **PARTIAL — rehearsal-pending** |
| NFR-16 | Many concurrent users; no double login | `SessionManager`, OCSF listener | `SessionManagerTest`, `LoginIntegrationTest`, `ExamConcurrencyIntegrationTest`, `LockConcurrencyIntegrationTest` | 16.1–16.4 | **PARTIAL — loadtest-missing** *(id uncited)* |
| NFR-17 | Test data prepared in the DB by a versioned Java loader, idempotent, one command | `server.db.seed.SeedLoader` / `SeedMain` / `SeedDocument`, console button | `SeedLoadedDbContract`, `SeedDocumentTest`, `SeedDatasetContract` | 17.1, 17.2, 17.3 | **PARTIAL — images-missing** (**B-8**) |
| NFR-18 | Everything pushes; lists paginate and lazy-load; zero refresh buttons anywhere | Every `*Session` class subscribing to `ClientEventBus`; `server.realtime` `PushGateway` | `BankSessionTest`, `MyGradesSessionTest`, `ExamListSessionTest`, `ReleaseManagerSessionTest`, `DataSessionTest`, `ExecutionMonitorInteractionTest`, `QuestionImagesTest` | 2.6, 7.4, 18.1–18.5 | **LIVE-unwalked** |
| NFR-19 | Feature packages, Strategy-based reports and validators, provider adapters — each with a "what if" story | `ReportStrategies`, `QuestionValidator` / `ExamValidator`, `BotProvider` chain, `client.core.Route`, `ui.theme.AccentPalette` | `ReportEngineExtensibilityTest`, `ProviderChainTest`, `ExamValidatorTest` | 19.1–19.3 | **LIVE-unwalked** |
| NFR-20 | Reuse and named design patterns: `DECISIONS.md` + the PLAN §2 table, **named in Javadoc where used** | Javadoc names Strategy, Builder, Singleton, Adapter, Template Method, Command, State — **and, since 2026-08-26 (batch D, B-34), Observer/Pub-Sub on `ClientEventBus` and `PushGateway`, Command at the `MessageRouter` boundary, Facade on `GradeApprovalService`, and DAO/Repository in the new `server.db.repos` package-info** | Case 20.1 asserts it by **loading the type** for each of PLAN §2's claims — 18 resolutions, four checked for shape rather than existence — and case 20.2 sweeps `src/main/java` for the pattern names | 20.1, 20.2, 20.3 | **LIVE** *(id uncited)* |
| NFR-21 | Lists everywhere, progress on every async op, a human message on every failure | `client.ui.components.*` (DataTable, EmptyState, Skeletons, ToastStack, WarnConfirm) + the `*Copy` classes | `ChipCatalogTest`, `ToastQueueTest`, `BotInteractionTest`, and one `*CopyTest` per feature | 21.1–21.6 | **PARTIAL — polish-open** |
| X-COV | JaCoCo ≥ 90% instruction coverage on our code, `ocsf/**` excluded | `pom.xml` `jacoco.min.instruction.coverage=0.90`, enforced in `verify` | The build gate itself; last measured **97.911 %** (PR23, on `6cb5203`) | — | **LIVE** *(id uncited)* |
| X-SEC | BCrypt; JPA/parameterized SQL only; server-side authorization on **every** verb; no secrets in git; exam answers never on the student wire | `AuthService`, `server.core.Authorization`, `MessageRouter`, `common.config.ExternalConfig` | `AuthorizationTest`, `WireDtoLeakGuardTest`, `ExamWireLeakGuardTest`, `BankWireLeakGuardTest`, `TakeExamProjectionShapeTest`, `BotIsolationGuardTest` | 6.7, 9.4 ✅, 11.4 | **LIVE-unwalked** |
| X-I18N | UI in English; data fully supports Hebrew/RTL (utf8mb4) | `client.features.home.HomeGreeting`, `ui.shell.Initials`, `server.features.bot.TextNormaliser`, utf8mb4 schema | `BankRoundTripIntegrationTest` (Hebrew round trip; found P-9), `HomeLogicTest`, `ExamDtoTest`, `NotifyDtoTest`, `SourceExtractorTest` | 21.6 | **LIVE-unwalked** |

---

## GAPS AND PARTIALS, gathered

This is the section the team reads. Everything below is a row that is **not** LIVE or
LIVE-unwalked, in one place, with an owner.

### Status counts (136 ids)

| Status | Count |
|---|---|
| LIVE (walked and passed) | 16 |
| LIVE-unwalked | 105 |
| PARTIAL | 11 |
| GAP | 3 |
| N/A (documented non-goal) | 1 |

*Counts corrected 2026-08-26 (batch A): **F3.1** moved PARTIAL → LIVE-unwalked when the
`exams.build` route landed. Nothing else moved column.*

Three further gaps carry epic ids rather than requirement ids (**E7.14**, **E7.16**, **E1.11**) and
are listed in the table below with the rest, because a defense does not care which numbering
system a hole was filed under.

The 105 LIVE-unwalked rows are one task, not a hundred and five: **E21.6**, the full dry run of
scenarios 1–21.

**Updated 2026-08-26 (batch A).** That sentence read *"eighteen of the twenty-one scenarios have
never been driven at a keyboard — everything except 1, 8 and 9"*, and the pre-walk campaign has
overtaken it. **Scenarios 1–14 have now all been walked**, and the distinction matters more than
the count: **1, 8 and 9 were driven at a keyboard; 2–7 and 10–14 were walked *below the screen***
— through the production services, repositories, router and a real MySQL, but not through a single
rendered pixel. Every cell that rests on rendering says so in its own Actual text, and those are
the manual pass's (E21.7) rather than E21.6's.

**Scenarios 15–21 are the ones still untouched**, and they are the non-functional half: packaging
and two-machine LAN (15), concurrency (16), seed review (17), no-refresh (18), the three defense
rehearsals (19), the pattern walk (20) and the UI-quality sweep (21). Several of them cannot be
walked below a screen even in principle, which is why they are last rather than merely late.

### The three most defense-relevant gaps

1. **No exam can be authored from scratch in the UI (F3.2 · GAP · owner [A]).**
   `ExamBuilderSession.addFromBank()` returns `false` unconditionally, and `canAddFromBank()` with
   it. A new exam starts empty, so the picker is the only way to fill it, and until this lands
   every exam in the demo must come from the seed.

   > **⚑ Status text corrected 2026-08-26 (batch A) — the blocker named here is gone.** This read
   > *"blocked on `questionVersionId` missing from the frozen BANK wire — not `BankQuestionRow`,
   > not `QuestionDetail`, not `QuestionVersionDetail`… raised as PR23 ask #2"*, and that was true
   > when it was written and is not true now. **`BankQuestionRow.latestVersionId` has been on the
   > wire since BANK amendment A1** (2026-08-25), which shipped with the integration commit
   > precisely to close this join: the picker is `BANK_LIST` (EXAM_BUILDER §3), `QuestionPin` keys
   > on `questionVersionId` (EXAM_BUILDER §4), and the row now carries the version PK that the pin
   > needs. **So the contract half is done, the ask is answered, and nothing is blocked on [L].**
   >
   > **What is left is the residual, and it is one method:** `addFromBank` has to *adopt*
   > `latestVersionId` — read it off the selected row, build the `QuestionPin`, and stop returning
   > `false`. That is **PR24's**, in flight with E7.13's auto tab and E7.14's newer-version action.
   > The row stays **GAP** rather than PARTIAL, deliberately and per this file's own convention: a
   > path that cannot be reached is a gap however small the remaining work looks, and one
   > unadopted field is exactly the size of hole that gets reported as nearly-done for a fortnight.
2. **~~The exam builder is on no rail, and CI is red because of it~~ — CLOSED 2026-08-26
   (F3.1 · now LIVE-unwalked · was owner [L]).** Kept in place rather than deleted, because "the
   third assembly commit" was the single most defense-relevant hole in this matrix for a fortnight
   and a reader who remembers it deserves to be told what happened to it.

   **What it was:** `ExamBuilderView` and `ExamBuilderSession` were merged, tested and complete for
   the metadata step and the paper editor, but `exams.build` was not declared in
   `client.core.Routes` and was offered to no role — so `ExamBuilderWiringGuardTest` failed four
   named assertions on `main` by design. Worse than unreachable: `Navigator.navigate` **throws** on
   an unregistered id, so the exam list's Edit and View buttons threw rather than dead-ended.

   **What landed (batch A, assembly commit #3):** `Routes.EXAM_BUILD` declares `exams.build` as a
   non-rail shell route, spelled by `ExamBuildRoutes.BUILDER`; `SessionRoutes` registers it for
   **both** teaching roles beside the exam list, and maps it to `ExamBuilderView` in `builderFor`.
   The guard's four red-by-design cases are green, and the exact-set route tests
   (`AppArgsAndRoutesTest`, `SessionRoutesTest`) carry the new id in both role lists — so the route
   cannot be quietly dropped again without a test naming it.

   **What this does not close:** F3.2. The screen is now reachable and a teacher can open the
   builder on an existing exam, but she still cannot add a question to an empty one — see gap 1.
   The two were always separate holes and only one of them was assembly.
3. **The study bot has never spoken to a real provider (F12.6 · PARTIAL — keys-unverified ·
   owner [L]).** `ProviderChainTest`, `DeepSeekProviderTest` and `AnthropicProviderTest` all run
   against mocked HTTP; `server.properties.example` ships both keys blank; the E16.17 live-key
   checklist exists in `DEMO_DAY.md` §5.4 and has never been run. The bot is v1's worst failure and
   the epic that "gets over-engineered on purpose" — a fallback chain nobody has watched fall back
   is the single highest-value five minutes on the list.

### Every non-LIVE row

| Id | Status | What is missing | Owner |
|---|---|---|---|
| F1.1 | PARTIAL — throttle-unwalked | Case 1.4 stopped at three failed attempts; the 5→30s lockout is unit-tested but never driven on a running server | B (walk) |
| F3.2 | **GAP** | The bank picker's add path. **No longer blocked on the wire** — `BankQuestionRow.latestVersionId` landed as BANK amendment A1 (2026-08-25). The residual is one method: `addFromBank` adopting it. In flight as **PR24** | A |
| F3.3 | PARTIAL — no-UI | Server auto-composer is complete and property-tested; E7.13's auto tab (criteria form, infeasibility report rendering) is unbuilt. In flight as **PR24** | A |
| — | **GAP** (E7.14) | The "question has a newer version" **update action**. The badge renders (`Line.hasNewerVersion()`); nothing lets a teacher act on it. In flight as PR24 | A |
| — | **GAP** (E7.16) | E7's session + integration tests — manual, auto, infeasible, Σ≠100, versioning. In flight as PR24 | A |
| F8.2 | PARTIAL — no-review-screen | E12.6: grading is still a queue table plus an override dialog, not a per-student paper the teacher can read. The comment box and the marked-paper assembler both exist server-side | B |
| F10.4 | PARTIAL — two-of-five | E18.5: locks are wired for questions and exams (server) and the question editor and bot manager (client). **Release-schedule editing and grading review are not lock-wired at all** | L |
| F12.6 | PARTIAL — keys-unverified | E16.17 live E2E against real DeepSeek and real Anthropic keys. Never run | L |
| F14.1 | PARTIAL — unverified-on-Windows | E20.2 (double-click on a clean Windows box) and E20.4b (JARs **must** be built on Windows for the JavaFX natives). Instructions written in `DEMO_DAY.md` §1.2/§2; execution pending | L |
| S-9 | **GAP** | An id `ACCEPTANCE_TESTS.md` cites in its scenario-2 header that is defined nowhere in the repository. Either resolve it to a spec line or delete the citation before submission | B |
| S-39 | **GAP** | Same, in the scenario-15 header | B |
| S-43 | N/A | Phase-2 internet access. A deliberate non-goal with a DECISIONS entry and a defense slide | — |
| S-19 / F6.5 | LIVE, below the screen | Case 9.5 passed against the production assembler and the real database, **not** against rendered pixels. The screen render is outstanding at the manual pass (E13.7) | B / L |
| NFR-15 | PARTIAL — rehearsal-pending | E20.5's two-machine LAN checklist, including UDP discovery on the actual demo network. Written, never rehearsed | L |
| NFR-16 | PARTIAL — loadtest-missing | E21.5: 30 concurrent scripted clients, 500-question bank list, 100-question form. Duplicate login and pairwise concurrency **are** covered | L |
| NFR-17 | PARTIAL — images-missing (**B-8**) | **Ten seeded questions are flagged `img` and carry no bytes.** `QuestionBankSection` loads `image` as NULL by design, waiting for assets under `docs/seed/img/`. Consequences: F2.1's illustration demo, F2.4's lazy preview and case 2.6 have nothing to show, and case 17.3's "no screen looks empty" is not yet true. **Widened 2026-08-26 by the scenario-6 walk:** three of the ten — `11005`, `11007`, `11010` — are on the **live demo paper**, so case 6.1's "and any illustrations" cannot be shown either. Registered as **B-8** (the scenario-6 walk's own B-15 is folded into it, one ticket). E2.16's seed review pass is also open | A (assets), L (review) |
| NFR-20 | **CLOSED 2026-08-26 (batch D)** | Case 20.2 walked it and the count was worse than this row said: **four** patterns were named nowhere under `src/main/java`, not two — Observer, **Command**, **Facade** and DAO. All four resolve to real code (20.1 loads every claim by class), so it was a documentation gap and not a design one, and all four are now named at their boundary in the house style of the five that were already right. **B-34.** The Command and DAO lines record what the pattern is *not*, so the defence answer is a concession plus a reason rather than a defence of the label | L |
| NFR-21 | PARTIAL — polish-open | E4.21 (unDraw illustrations for login/empty/success/bot), E4.22 (responsive pass at 1280/1600/1920), E21.7 (the polish sweep), E21.8 (Hebrew/RTL pass on all screens) | L |
| E1.11 | **GAP** (not a PRD id) | The malformed-message fuzz test on the **main protocol** socket. Discovery's socket is fuzzed 4000 ways; the OCSF socket is not, and PRD §6 promises "client flood of malformed messages → rejected + logged, connection survives" | L |

### Traceability defects — implemented, but the id is cited nowhere in `src/`

Not functional gaps. Each is one Javadoc line away from being closed, and each is a question an
examiner can ask that the grep answer alone does not survive.

`F6.5` · `F8.2` · `F10.1` · `F10.2` · `S-26` · `S-29` · `S-40` · `S-41` · `S-44` · `S-45` ·
`NFR-16` · ~~`NFR-20`~~ *(closed 2026-08-26, batch D: the id is now cited in `MessageRouter`'s Command section and in the `server.db.repos` package javadoc, and B-34's four missing pattern names are written at their boundaries)* · `X-COV`

Two more are structural rather than cited and are fine as they stand, but should be *said* that way
at the defense rather than searched for: **S-3/S-4** hold because none of the 68 verbs mutates a
user, a course or a subject.

### One more thing an examiner will find

`Authorization.requireEnrolled` still throws `notImplemented` — it is E10-era scaffolding that
nothing calls. Enrollment **is** enforced, in `AttemptService` and `BotService`, through
`data.isEnrolled`, and `AuthorizationTest` pins the stub's refusal deliberately. The requirement
holds; the dead guard is what looks wrong on a cold read. Either delete it or route the two
services through it.
