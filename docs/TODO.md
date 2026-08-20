# HSTS v2 — Master TODO

Owners: **[L]** Naji (lead) · **[A]** Member A (bank & exam authoring pipeline) · **[B]** Member B (results, reports, data) — see [TEAM_SPLIT.md](TEAM_SPLIT.md). Requirement IDs → [PRD.md](PRD.md).

Conventions: every task includes its tests (DoD in PLAN §5). `⚑` = defense-critical. Check items off in PRs.

---

## E0 — Repository, build & tooling [L]

- [ ] E0.1 Create fresh repo (or orphan branch `v2`) seeded from prototype `main`; archive `person5-ui` as read-only reference
- [ ] E0.2 Restructure sources to the feature-based layout (ARCHITECTURE §2); move prototype classes into `client/core`, `server/core`, `common/protocol`
- [ ] E0.3 Upgrade `pom.xml`: Java 21, JavaFX 21, encoding, reproducible builds
- [ ] E0.4 Add dependencies: atlantafx-base, ikonli (javafx + material2 pack), eventbus-java 3.3.1, hibernate-core 6.6, mysql-connector-j, HikariCP, flyway-core + flyway-mysql, bcrypt (at.favre.lib), slf4j + logback, pdfbox, poi-ooxml, anthropic-java, jackson-databind (bot JSON + transcripts)
- [ ] E0.5 Test dependencies: junit-jupiter, mockito, assertj, testfx + monocle, h2, jacoco plugin with ≥90% gate + `ocsf/**` exclusions
- [ ] E0.6 Configure shade for `G<Num>_Server.jar` / `G<Num>_Client.jar` (mains, filters, properties copy) — verify double-click AND `java -jar` on Windows
- [ ] E0.7 `.gitignore` (target, `server.properties`, local config, IDE); commit `server.properties.example`, `client.properties.example`
- [ ] E0.8 `.editorconfig` + checkstyle (or spotless) with a minimal agreed style; wire into `mvn verify`
- [ ] E0.9 GitHub Actions `ci.yml`: JDK21, MySQL 8 service, `mvn verify`, JaCoCo report artifact, JAR artifacts, status badge
- [ ] E0.10 Branch protection on `main` (PR + green CI required); PR template with DoD checklist
- [ ] E0.11 Copy docs/ into repo; add `docs/PROBLEMS.md` (running log for the submission question) and `docs/DEMO_ACCOUNTS.md`
- [ ] E0.12 README v2: quick start, architecture summary, screenshots placeholder, badge
- [ ] E0.13 Verify baseline: build on Windows, run server+client on two machines over LAN ⚑

## E1 — Protocol v2 & common model [L]

- [ ] E1.1 `Verb` enum (all operations, grouped per feature) + `Status` + `ErrorCode` enums
- [ ] E1.2 `Message` v2 (verb, requestId, payload, status, errorCode) with stable serialVersionUID
- [ ] E1.3 DTO package skeleton `common/dto/<feature>/`; rule: payloads are typed DTOs only
- [ ] E1.4 Auth DTOs: `LoginRequest`, `LoginResult` (user summary + role + courses), `ErrorPayload(message)`
- [ ] E1.5 Client `RequestDispatcher`: send → `CompletableFuture` correlated by requestId, timeout handling, error mapping
- [ ] E1.6 Server `MessageRouter`: verb→handler registry, auth check, caller resolution from connection, central try/catch → ERROR
- [ ] E1.7 Push verbs + `PushGateway` (toUser/toUsers/toCourseTeachers/toEnrolled/toRole) over SessionManager
- [ ] E1.8 Client push handling: `ServerMessageEvent` → typed events posted on FX thread; unknown verbs ignored + logged
- [ ] E1.9 `FakeClientConnection` (records sent messages, scriptable replies) for session tests
- [ ] E1.10 Unit tests: dispatcher correlation/timeout, router auth rejection, push routing, serialization round-trip of every DTO
- [ ] E1.11 Malformed-message fuzz test: random/hostile payloads never kill the connection ⚑

## E2 — Database, persistence & seed [A] (schema reviewed by L)

- [x] E2.1 Flyway bootstrap on server start; migration naming convention; test that migrations run clean on empty DB — *`DbBootstrap` + clean-run test done; the one-line call in `ServerMain` is outside A's scope and awaits [L]*
- [x] E2.2 V1 core: subjects, courses, users, course_teachers, enrollments, coordinators
- [x] E2.3 V2 bank: questions, question_versions (correct_answer 1..4, topic, difficulty, image)
- [x] E2.4 V3 exams: exams, exam_versions, exam_version_questions
- [x] E2.5 V4 executions: exam_executions (+stats columns), exam_attempts, attempt_answers
- [x] E2.6 V5 grading: grades (audit fields)
- [x] E2.7 V6 bot: bots, bot_sources, bot_sessions (JSON transcript), bot_messages (normalized analytics copy, dual-written)
- [x] E2.8 V7 notifications
- [x] E2.9 JPA entities for all tables (+`@Version` on editables), enums, converters (JSON transcript ↔ objects)
- [x] E2.10 HibernateUtil (Singleton SessionFactory from HikariCP) + `Transactions` helper (`inTx(fn)`) with tests
- [x] E2.11 Repositories: UserRepo, CourseRepo, QuestionRepo, ExamRepo, ExecutionRepo, AttemptRepo, GradeRepo, BotRepo, NotificationRepo — query-per-need, projections for wire DTOs
- [x] E2.12 Take-exam projection that structurally excludes `correct_answer` (F6.6) + test proving the DTO has no correctness data ⚑
- [x] E2.13 Repo test suites: H2 fast suite + MySQL suite (test base class = Template Method wipe/reseed)
- [x] E2.14 ID allocators: 5-digit question display id (course2+serial3), 6-digit exam id (subject2+course2+serial2), concurrency-safe, with tests (S-8, S-10)
- [ ] E2.15 Seed migration/loader per PRD §5 (idempotent, one command + server-console button)
- [ ] E2.16 Seed review pass with [L]: every demo screen looks "well-filled" ⚑
- [ ] E2.17 BCrypt hashing for all seeded users; document demo credentials in DEMO_ACCOUNTS.md

## E3 — Server core [L]

- [ ] E3.1 ServerMain: args (`--headless`, `--port`), config load (env > file > defaults), Flyway, startup banner
- [ ] E3.2 HSTSServer wiring: OCSF listener → MessageRouter; connection lifecycle logs
- [ ] E3.3 SessionManager: login/logout/disconnect, user↔connection map, single-session enforcement (T-16) with tests
- [ ] E3.4 Disconnect cleanup hooks (locks released, attempt marked connection-lost, session freed) with tests
- [ ] E3.5 `Authorization` guards: requireRole, requireTeachesCourse, requireEnrolled, requireCoordinatorOf, requireSelf; `AuthorizationException` → central ERROR mapping; tests per guard
- [ ] E3.6 Logback setup: colorized console (defense view) + rolling file + in-memory ring appender for console UI
- [ ] E3.7 Structured log conventions: every request logged (user, verb, ms), every push, every rejection ⚑
- [ ] E3.8 Graceful shutdown (close executions? no — persist state; notify clients; stop timers cleanly)
- [ ] E3.9 Boot re-arm: on start, reschedule timers for live executions/attempts from DB (crash recovery) with test

## E4 — Client core & design system [L]

- [ ] E4.1 ClientApp/Launcher (non-Application main), window sizing, app icon, title
- [ ] E4.2 ScreenManager + Navigator (typed params, back-stack where sensible), ScreenFactory FXML cache
- [ ] E4.3 AbstractScreen lifecycle (onShow/onHide, auto EventBus register/unregister) — Template Method
- [ ] E4.4 ClientEventBus setup + typed event classes; FX-thread posting rule enforced in one place
- [ ] E4.5 Connect screen: manual host/port entry pre-filled from defaults (client.properties → last server → localhost:5555), connecting state, retry, error detail; discovery picker slot added in E19.10 (manual path never blocked by discovery)
- [ ] E4.6 Reconnect banner + auto-retry when the socket drops mid-session (bounded backoff)
- [ ] E4.7 ThemeManager: AtlantaFX light/dark + OS-default detection, accent palette injection, persistence, `ThemeChangedEvent`
- [ ] E4.8 `hsts.css` token layer + the 5 accent palette stylesheets (Indigo/Emerald/Amber/Rose/Slate)
- [ ] E4.9 Settings screen: mode toggle, palette swatch picker with live preview
- [ ] E4.10 App shell: top navbar (logo, breadcrumbs, bell, avatar/role, settings), collapsible side rail per role
- [ ] E4.11 Component: DataTable wrapper (sorting, filtering hook, empty-state slot, loading skeleton)
- [ ] E4.12 Component: form field with inline validation message + invalid styling
- [ ] E4.13 Component: WarnConfirm dialog (icon, explanation, explicit confirm) — for legal-but-unusual actions (unanswered submit, close-early, deletes), reused everywhere ⚑
- [ ] E4.14 Component: toast stack (success/error/info, auto-dismiss, slide-in)
- [ ] E4.15 Component: status chips (exam/exec/grade states), role badges
- [ ] E4.16 Component: progress overlay + skeleton loaders for async screens
- [ ] E4.17 Component: empty-state with illustration slot
- [ ] E4.18 Component: countdown timer widget (server-synced, amber/red thresholds, pulse animation)
- [ ] E4.19 Component: modal host + standard dialogs (confirm, error detail)
- [ ] E4.20 `Animations` utility (fade/slide/scale/stagger, ≤250ms) + screen transition integration
- [ ] E4.21 Curate + import illustrations (unDraw, recolored to palettes) for login/empty/success/bot
- [ ] E4.22 Responsive pass: shell + components verified at 1280/1600/1920 widths; side-rail collapse
- [ ] E4.23 Session-class pattern documented + example test with FakeClientConnection (template for A/B)
- [ ] E4.24 TestFX smoke harness (headless Monocle) — boots app to connect screen in CI

## E5 — Auth & login [L]

- [x] E5.1 AuthService: BCrypt verify, failed-attempt throttle (5 → 30s), generic errors; unit tests incl. timing
- [x] E5.2 LOGIN/LOGOUT verbs + router integration; LoginResult carries role, courses, display name
- [x] E5.3 Login screen: brand panel + form, inline errors, loading state, Enter submits, caps-lock hint
- [x] E5.4 Role-based shell boot: menu items and home per role (T-1) — 4 role variants
- [x] E5.5 Duplicate-login rejection UX (clear message) + integration test with two clients ⚑
- [x] E5.6 Student/Teacher/Coordinator/Principal home dashboards with live cards (wired up as features land)
- [x] E5.7 Logout flow: confirm → server logout → clean state → login screen
- [x] E5.8 Integration: LoginIntegrationTest (success, wrong pass, throttle, duplicate, disconnect frees session)

## E6 — Question bank [A]

Server:
- [ ] E6.1 QuestionService: create (validate: text, 4 non-empty answers, ≥1 correct, course taught, topic, difficulty), allocate display id
- [ ] E6.2 Answer validity enforcement (C-8): exactly one correct answer; 4 answers pairwise distinct (trim + whitespace-collapse, case-insensitive compare) — server-side validation with precise error messages ⚑
- [ ] E6.3 Edit → new immutable version; version history query; latest-version resolution
- [ ] E6.4 Delete: block when referenced by any exam version (return referencing exam names); soft-delete otherwise
- [ ] E6.5 Browse/filter query (course/topic/difficulty/text) + pagination
- [ ] E6.6 Image handling: size/type limits (≤2MB, png/jpg), stored in question_versions, `GET_QUESTION_IMAGE` lazy fetch verb
- [ ] E6.7 QuestionValidator (Strategy, shared by add/edit) unit-tested to 100%
- [ ] E6.8 Verbs + DTOs (list item, detail, editor payload, version history) — frozen with [L]
Client:
- [ ] E6.9 Bank screen: master list (DataTable, filters, search) + detail pane with image lazy-load
- [ ] E6.10 Question editor: form, answer rows with single-correct radio-select, topic/difficulty pickers, image picker+preview+remove
- [ ] E6.11 Editor validation UX: duplicate-answer inline error live while typing, exactly-one-correct guaranteed by radio group; server errors mapped to fields ⚑
- [ ] E6.12 Version history panel (timeline, view old version read-only, diff highlight of changed fields)
- [ ] E6.13 Delete flow: blocked dialog listing exams / confirm dialog otherwise
- [ ] E6.14 Edit-lock integration: acquire on editor open, "being edited by X" read-only mode, live release (E18 dependency)
- [ ] E6.15 Session tests: all flows against FakeClientConnection (incl. error paths)
- [ ] E6.16 Integration test: add/edit/version/delete/browse round-trip; Hebrew text round-trip
- [ ] E6.17 Acceptance pass vs T-2 scenarios with [L] ⚑

## E7 — Exam builder [A]

Server:
- [ ] E7.1 ExamService: create draft (name, duration, texts, author recorded), allocate 6-digit id
- [ ] E7.2 Composition update: set questions (by question_version), points, order; reject duplicates
- [ ] E7.3 Points rule: save requires Σ=100; API returns per-question breakdown for live UI sum
- [ ] E7.4 AutoExamGenerator (Builder): criteria (total, topic breakdown, difficulty mix) → selection or precise infeasibility report (which topic/difficulty short, by how much); deterministic seed option for tests ⚑
- [ ] E7.5 Edit approved/pending exam → new DRAFT version; old versions retained and listed (C-2)
- [ ] E7.6 Submit for approval: DRAFT → PENDING, notify coordinator (E17)
- [ ] E7.7 Newer-question-version indicator data (exam uses vN, bank has vN+1)
- [ ] E7.8 ExamValidator unit tests (all rules)
- [ ] E7.9 Verbs + DTOs frozen with [L]
Client:
- [ ] E7.10 Exam list screen: teacher's exams, status chips, versions expandable, actions per state
- [ ] E7.11 Builder — metadata step (name, duration, texts with student/teacher tabs)
- [ ] E7.12 Builder — manual tab: bank picker (filter/search), selected list with points editors + reorder, live Σ/100 indicator (green at 100)
- [ ] E7.13 Builder — auto tab: criteria form (topic rows, difficulty sliders), generate → editable result, infeasibility report rendering ⚑
- [ ] E7.14 Version history + "question has newer version" badges with update-question action
- [ ] E7.15 Submit-for-approval flow with confirmation summary
- [ ] E7.16 Session tests + integration test (manual + auto + infeasible + Σ≠100 + versioning)
- [ ] E7.17 Acceptance pass vs T-3 with [L] ⚑

## E8 — Approval workflow [A]

- [ ] E8.1 ApprovalService: pending queue per coordinator's subject; approve/reject(reason required); state transitions guarded; self-approval allowed but logged (F4.3, acceptance case 4.6)
- [ ] E8.2 Invalidate pending request if a newer version is submitted; coordinator notified
- [ ] E8.3 Approval queue screen (coordinator): list + badges, open preview
- [ ] E8.4 **Exam preview screen: renders the exam exactly with the student form component (reuse E10 form, read-only) + teacher-only notes side panel** — fixes v1 "coordinator couldn't see the exam" ⚑
- [ ] E8.5 Approve flow (confirm) / Reject flow (reason dialog, required, min length)
- [ ] E8.6 Teacher-side: rejection reason visible on exam + notification deep-links to it
- [ ] E8.7 Session + integration tests (approve, reject w/o reason blocked, stale version)
- [ ] E8.8 Acceptance pass vs T-4 ⚑

## E9 — Release manager [A]

- [ ] E9.1 ReleaseService: create execution (approved versions only), window validation, 4-alnum code validation (C-1)
- [ ] E9.2 Execution status lifecycle SCHEDULED→LIVE→CLOSED driven by TimerService; transitions pushed
- [ ] E9.3 Cancel scheduled / close-early (with force-submit semantics for active students)
- [ ] E9.4 Multiple executions per exam version (S-2) + history listing with per-execution stats snapshot
- [ ] E9.5 Release screen: create form (version picker, datetime pickers, code field with dice-generate), validation
- [ ] E9.6 Releases list: live status chips, participant counters (pushed), actions (cancel/close/monitor/extend)
- [ ] E9.7 Session + integration tests (window enforcement, unapproved blocked, cancel, close-early)
- [ ] E9.8 Acceptance pass vs T-5 ⚑

## E10 — Take exam [L]

Server:
- [ ] E10.1 AttemptService.start: code lookup (live executions), student identity check (own ת"ז), enrollment check, window check, single-attempt check → create attempt, start server timer (S-18)
- [ ] E10.2 Exam form DTO via the no-correctness projection (E2.12) ⚑
- [ ] E10.3 SAVE_ANSWER verb: upsert attempt_answers, reject if attempt not IN_PROGRESS or past deadline (server clock) ⚑
- [ ] E10.4 SUBMIT verb: finalize, record actual minutes (S-19), trigger auto-grade (E12); participation counts derived from attempts (no counter mutation) + pushed to monitor
- [ ] E10.5 TimerService expiry: transactional force-submit + TIMED_OUT + push FORCE_SUBMITTED + counters — **works even if client is gone** ⚑
- [ ] E10.6 Reconnect/resume: attempt state + saved answers + authoritative remaining time returned on re-entry
- [ ] E10.7 Attempt-in-progress state exposed to BotService with attempt lifecycle (C-4): same-course bot locked; cross-course use triggers integrity notice + teacher notification + monitor-row flag
- [ ] E10.8 Concurrency integration tests: two students parallel, answer-after-expiry rejected, resume after kill, double-attempt blocked ⚑
Client:
- [ ] E10.9 Entry flow screens: code entry → ID entry (each with distinct, specific error messages)
- [ ] E10.10 Exam form: general text header, question cards (text, image, 4 options single-select), progress bar (answered x/y), question navigator strip
- [ ] E10.11 Debounced auto-save with saved-state indicator ("All changes saved ✓")
- [ ] E10.12 Countdown widget wired to server sync + TIMER_EXTENDED push as a designed moment (F7.1 / *Time Extended* mockup): green flash + glow pulse on the timer, floating "+mm:ss", toast with teacher + new end time ⚑
- [ ] E10.13 Manual submit flow (F6.9 / *Submit Confirm* mockup): WarnConfirm with answer-summary grid (chips clickable → jump to question), remaining-time note → *Submitted* success screen (F6.10): check animation, handed-in time, solving minutes, summary, Back to dashboard
- [ ] E10.14 Time-up takeover (F6.4 / *Time Up* mockup) on FORCE_SUBMITTED push or on resume: full-screen, animated clock, NO confirmation, answers locked (server already enforces), submitted-summary grid, single Back-to-dashboard navigation, exam unreachable afterwards ⚑
- [ ] E10.15 Disconnect mid-exam UX: reconnect banner, resume seamlessly, no lost answers
- [ ] E10.16 Session tests: full state machine incl. expiry/resume/push paths
- [ ] E10.17 Acceptance pass vs T-6 ⚑

## E11 — Extension & monitoring [L]

- [ ] E11.1 ExtendService: minutes>0, execution LIVE, applies to execution only (S-20); reschedules attempt timers; pushes to active students + records in execution
- [ ] E11.2 Execution monitor screen (teacher): live counters, per-student rows (status, remaining, **integrity flag: "used <course> bot at <time>" when C-4 alert fired**), extension action with amount dialog
- [ ] E11.3 Extension UX on student side verified end-to-end (timer grows mid-countdown) ⚑
- [ ] E11.4 Edge tests: extend at T-10s, extend after close blocked, extension while student offline (applies on resume)
- [ ] E11.5 Execution documentation record complete (S-21): derived counts frozen into stats JSON at close + shown in execution history
- [ ] E11.6 Acceptance pass vs T-7 ⚑

## E12 — Grading [B]

- [ ] E12.1 GradingService.autoGrade: per-question correctness (selection == correct_answer), weighted score, persist AUTO grade
- [ ] E12.2 Approve grade(s): single + bulk; status→APPROVED; push GRADE_PUBLISHED to student (C-3)
- [ ] E12.3 Override: new score requires justification (S-23); audit trail (auto score kept); comment to student (S-22)
- [ ] E12.4 Stats computation on execution fully graded: avg, median, **std dev**, min/max, pass rate, deciles → stored (S-25); values unit-tested against hand-computed fixtures ⚑ — *`ScoreStatistics` done and tested against the seeded execution 4821 fixture (population σ). Not ticked: **pass rate** has no threshold defined in the PRD, and **→ stored** needs E2 entities + the frozen contract*
- [ ] E12.5 Grading queue screen: executions awaiting grading, per-execution student table (auto scores, status)
- [ ] E12.6 Per-student review screen: checked form view (correct/wrong marks), override dialog (score+reason), comment box, approve
- [ ] E12.7 Bulk approve with summary confirm
- [ ] E12.8 Session + integration tests (auto-grade correctness, override w/o reason blocked, idempotent approve, stats values verified against hand-computed fixture)
- [ ] E12.9 Acceptance pass vs T-8 ⚑

## E13 — Student results [B]

- [ ] E13.1 ResultsService: student's own grades only (authorization test: requesting others fails) ⚑
- [ ] E13.2 Checked-form DTO: questions, chosen vs correct, marks, comments — only for APPROVED grades
- [ ] E13.3 My Grades screen: exam list with scores, status, date; empty-state
- [ ] E13.4 Checked form viewer: green/red marking, teacher comments, score breakdown
- [ ] E13.5 Export/print view of the checked form (S-36) — printable layout
- [ ] E13.6 GRADE_PUBLISHED push → notification + dashboard card refresh
- [ ] E13.7 Session tests + acceptance pass vs T-9 ⚑

## E14 — Teacher results & statistics [B]
*Ownership note (2026-08-19): hardening items H14.* and H15.* in ACCEPTANCE_TESTS.md moved out of B's scope with the sprint re-plan; whoever executes E14/E15 picks them up. H14.4 (population-σ divisor test) and H15.2 (CANCELLED excluded from reports) are defense-critical.*

- [ ] E14.1 Teacher results query: all exams she authored, incl. executions run by others (S-35)
- [ ] E14.2 Results screen: exam → execution picker → student table (sortable) + stat cards (avg · median · std · min/max · pass rate · participants)
- [ ] E14.3 **StatChart component** (shared, in client/ui): score-bucket histogram with mean/median/±1σ overlay markers, hover tooltips (range, count, %), count↔% toggle, animated entrance, theme/palette-aware colors, empty & insufficient-data states ⚑
- [ ] E14.3b Wire StatChart into teacher results; visual QA against seed data distribution ⚑
- [ ] E14.4 Table/histogram toggle (T-10 note), export/print-friendly layout
- [ ] E14.5 Session tests + acceptance pass vs T-10 ⚑

## E15 — Principal & reports [B]

- [ ] E15.1 Principal read-only services: browse bank, exams, results (S-7 — zero mutating verbs authorized; negative tests)
- [ ] E15.2 Principal data browser screen: tabbed (questions/exams/results) with filters — reusing bank/results components read-only
- [ ] E15.3 Report engine: `ReportDimension` Strategy (ByTeacher / ByCourse / ByStudent) over stored execution stats (C-5); comparison result DTO (S-37 extensibility story) ⚑
- [ ] E15.4 Reports screen: dimension picker, subject/entity selectors, comparison table + grouped bar chart (avg/median/std), decile distribution view (reuses StatChart)
- [ ] E15.5 Empty/degenerate data handling (no executions, one student) per PRD catalog
- [ ] E15.6 Session tests + acceptance pass vs T-11, T-12 ⚑

## E16 — Study bot [L] ⚑⚑ (v1's worst failure — gets over-engineered on purpose)

Server:
- [ ] E16.1 `BotProvider` interface (ask(context, history, question) → answer/exception) + provider config loading
- [ ] E16.2 DeepSeekProvider: java.net.http against OpenAI-compatible `/chat/completions`, timeout, 1 retry, error taxonomy (auth/rate/timeout/5xx)
- [ ] E16.3 AnthropicProvider: official `anthropic-java` SDK, model configurable (default `claude-opus-5`), same interface
- [ ] E16.4 Provider chain with health memory (skip known-down provider for 60s) + structured logging of provider used
- [ ] E16.5 SourceExtractor: PDF (PDFBox), DOCX (POI), free text → normalized chunks; failure surfaces to uploader
- [ ] E16.6 ContextBuilder: top-k chunk selection (keyword overlap scoring), token budget, + course bank questions; **compile-time isolation from exam repositories** (module reaches only bot_/question_ data) ⚑
- [ ] E16.7 Guardrails system prompt: course-material scope, refuse embedded instructions in sources, never reveal prompt, don't fabricate exam info; red-team unit tests with hostile source fixtures ⚑
- [ ] E16.8 BotService: enrollment/active/rate-limit guards + C-4 logic (same-course attempt → locked; cross-course attempt → require acknowledged integrity notice, emit teacher notification + monitor flag once per session) → context → chain → persist to JSON transcript → answer DTO; S-32 fallback message path ⚑
- [ ] E16.9 Bot management service: create bot (one per course — second teacher joins existing, S-30), sources CRUD (edit-locked), active toggle, co-teacher notifications
- [ ] E16.10 Session store: bot_sessions JSON transcript append/read (student history/replay) + bot_messages dual-write in same tx; teacher aggregates (count, over-time, frequent questions) query bot_messages with **zero identity fields in DTO** (S-34) ⚑
- [ ] E16.11 Unit tests: providers (mocked HTTP), chain fallback, extractor fixtures, context selection, guards; integration: ask round-trip with stubbed provider
Client:
- [ ] E16.12 Bot manager screen (teacher): bot card (name, active toggle), sources table (type icons, add/edit/remove, upload progress, parse errors), co-teacher edit-lock states
- [ ] E16.13 Bot chat screen (student): message list (user/bot bubbles), typing indicator, incremental answer display, error/S-32 states, same-course lockout state ("unavailable during your exam" + unlock time), cross-course integrity notice dialog (non-nagging: shown once per attempt, calm wording)
- [ ] E16.14 Bot history screen (student): session list, reopen conversation, continue
- [ ] E16.15 Bot analytics screen (teacher): totals, activity chart, frequent questions list — anonymized
- [ ] E16.16 Session tests for all four screens
- [ ] E16.17 Live E2E vs real DeepSeek + real Anthropic keys (manual checklist, run before every demo) ⚑
- [ ] E16.18 Acceptance pass vs T-13, T-14 ⚑

## E17 — Notifications & realtime [L]

- [x] E17.1 NotificationService: persist + push-if-online; helper `notify(users, type, title, body, ref)`
- [ ] E17.2 (infrastructure ready, emit calls land with their epics; see docs/reports/lead/E17-E18.md §4) Emit points wired: approval requested/approved/rejected, grade published, extension, bot source changed, release opening soon (scheduled), possible-cheating alert (cross-course bot use mid-attempt → executing teacher, C-4)
- [x] E17.3 GET/MARK_READ verbs, unread count in LoginResult
- [x] E17.4 Bell + badge in navbar (live), notification panel (list, icons, relative time, click-through navigation, mark-all)
- [x] E17.5 Toast integration for foreground pushes
- [x] E17.6 Tests: persistence, routing (only intended recipients — negative tests), offline user gets it on next login ⚑

## E18 — Edit locks & concurrency [L]

- [x] E18.1 EditLockService: acquire/renew/release, TTL expiry sweep, per-entity keys; unit tests incl. expiry races
- [x] E18.2 Verbs: ACQUIRE/RENEW/RELEASE + LOCK_CHANGED push to viewers of that entity
- [x] E18.3 Client `LockAwareEditor` mixin: heartbeat while open, release on close/navigate/crash (best effort), UI states (owner / locked-by-other read-only banner with name / lock released → "Take over editing?" prompt)
- [x] E18.4 Optimistic `@Version` conflict → `CONFLICT` error → client dialog "Changed by someone else — reload?" ⚑
- [ ] E18.5 (done for the question editor; the other four compose LockAwareEditor when their screens land) Wire into: question editor, exam builder, bot sources, release schedule editor, grading review
- [x] E18.6 Concurrency integration tests: two clients editing same entity (lock visible live), lock-expiry takeover, stale-write rejected ⚑
- [x] E18.7 Disconnect releases locks (ties to E3.4) — test

## E19 — Server console & network [L]

- [ ] E19.1 NetworkDetector: enumerate candidate IPv4s, pick best (site-local, default-route heuristic), expose all
- [ ] E19.2 Console UI: header with big `<ip>:<port>`, start/stop listener, port editor, status cards (DB pool, clients, memory, bot providers)
- [ ] E19.3 Connected clients table (user, role, IP, connected-since) — live
- [ ] E19.4 Log tail pane (ring buffer appender), level filter, pause/scroll
- [ ] E19.5 Manual IP override + copy-to-clipboard; `--headless` mode verified
- [ ] E19.6 Seed-data button (loads/reloads demo dataset with confirm)
- [ ] E19.7 Console styled with the same design system (dark by default) ⚑
- [ ] E19.8 Discovery responder (F13.3): UDP listener on its own port, reply {ip, port, name, fingerprint}; fingerprint generated on first boot + persisted; console shows it next to the address; console toggle on/off; malformed/flood packets ignored + logged (fuzz test) ⚑
- [ ] E19.9 Fingerprint persistence + regeneration path (server reinstall) — documented behavior, test
- [ ] E19.10 Client discovery (F13.4): broadcast + ~2s collect, picker UI (name · address · fingerprint), TOFU pinning of {address, fingerprint}, auto-connect to pinned server, mismatch → prominent warning dialog requiring explicit confirm; "nothing found" → manual entry with defaults ⚑
- [ ] E19.11 Discovery tests: responder round-trip, timeout path, pin/mismatch state machine, isolation-network fallback (responder off) — all without real multicast in CI (loopback/injected transport seam)
- [ ] E19.12 **[GATED — decide at M6, criteria in ADR-019]** TLS over OCSF: SSLServerSocket in vendored OCSF, self-signed cert generated on first boot, discovery ID becomes the cert's fingerprint (no UX change), client verifies pinned fingerprint against the presented cert; encrypts credentials in transit. ~2–3 days incl. keystore handling + demo-machine rehearsal. If not taken: phase-2 slide + cleartext-transit limitation stated in submission doc

## E20 — Packaging & deployment [L]

- [ ] E20.1 Final JAR names `G<Num>_Server` / `G<Num>_Client` (group number parameterized in pom)
- [ ] E20.2 Double-click verified on clean Windows machine (no IDE, only JRE/JDK 21) ⚑
- [ ] E20.3 Terminal run shows the colorized log stream (defense view) ⚑
- [ ] E20.4 Externalized properties beside JARs + first-run defaults; client remembers last server; pom copies from `*.properties.example` when the gitignored real files are absent (fresh clone/CI currently ships no adjacent config)
- [ ] E20.4b Final defense JARs must be built on Windows — JavaFX natives are baked in at build time (hard gate, add to day-of checklist)
- [ ] E20.5 Two-machine LAN checklist doc (firewall rules incl. UDP discovery port, IP from console, smoke script, **test discovery on the actual demo network type — bring a hotspot as fallback**, manual-IP path rehearsed too) — rehearsed ⚑
- [ ] E20.6 DB setup path for a fresh machine: install MySQL → run server → Flyway + seed → done (documented, timed)

## E21 — Hardening & test completion [all, coordinated by L]

- [ ] E21.1 Execute the full PRD §6 edge-case catalog as a tracked checklist — every line gets a test or a manual-verified note
- [ ] E21.2 Race-condition suite: parallel attempts, simultaneous edits, extension-vs-expiry, submit-vs-expiry, login race ⚑
- [ ] E21.3 "Break it" adversarial session: each member attacks the others' features; findings → issues → fixed
- [ ] E21.4 Coverage push to ≥90% (JaCoCo report reviewed; gaps assigned by owner)
- [ ] E21.5 Performance sanity: 100-question exam form, 500-question bank list, 30 concurrent scripted clients
- [ ] E21.6 Full acceptance-test dry run of scenarios 1–21 in order, results recorded in the submission table ⚑
- [ ] E21.7 UX polish sweep: every screen against the design checklist (spacing, empty states, loading, errors, animations)
- [ ] E21.8 Hebrew/RTL data pass on all screens

## E22 — Submission & defense [B writes, L reviews]

- [ ] E22.0 Reverse traceability matrix (`docs/TRACEABILITY.md`): every T-n/S-n → PRD feature → package/class → test(s) — the completeness proof for the submission doc & defense
- [ ] E22.1 Acceptance-test results table (scenario, steps, expected, actual, bugs found + which case exposed them)
- [ ] E22.2 Submission doc: cover page (group, names, IDs), per-member responsibilities, test table, design/coding-problem answer (from PROBLEMS.md — pick the best story, e.g. exam-vs-execution modeling or timer race)
- [ ] E22.3 Export to required format, assemble `G<Num>_Assignment3.zip` (doc + 2 JARs), verify zip contents against spec ⚑
- [ ] E22.4 Demo script: ordered walkthrough matching scenarios 1–21 + wow-moments (theme switch, live extension, edit locks, bot fallback) + break-it invitation
- [ ] E22.5 Defense Q&A prep sheet: patterns, decisions, "what would you change for phase 2 (internet)", concurrency story
- [ ] E22.6 Dry-run defense #1 (full, timed, two machines) → fix list
- [ ] E22.7 Dry-run defense #2 (clean run) ⚑
- [ ] E22.8 Day-of checklist: keys tested, DB seeded fresh, firewall, chargers, backup laptop with everything installed

## E23 — Stretch (only after E21 green)

- [ ] E23.1 Upcoming strip on home (next executions per role) — from FUTURE_IDEAS #3
- [ ] E23.2 Full exam calendar month view
- [ ] E23.3 School messaging (principal/teacher broadcasts) — FUTURE_IDEAS #2
- [ ] E23.4 Report export to CSV
- [ ] E23.5 Bot answer citations ("based on: <source name>")
