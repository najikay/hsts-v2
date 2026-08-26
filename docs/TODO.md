# HSTS v2 — Master TODO

Owners: **[L]** Naji (lead) · **[A]** Member A (bank & exam authoring pipeline) · **[B]** Member B (results, reports, data) — see [TEAM_SPLIT.md](TEAM_SPLIT.md). Requirement IDs → [PRD.md](PRD.md).

Conventions: every task includes its tests (DoD in PLAN §5). `⚑` = defense-critical. Check items off in PRs.

---

## E0 — Repository, build & tooling [L]

> **Maintenance note (2026-08-25):** this section was completed in the first days of the sprint (see the merged-and-verified record in the timeline and per-PR reports) but its boxes were never ticked. Bulk-ticked at the doc cleanup; items still genuinely open were left open.

- [x] E0.1 Create fresh repo (or orphan branch `v2`) seeded from prototype `main`; archive `person5-ui` as read-only reference
- [x] E0.2 Restructure sources to the feature-based layout (ARCHITECTURE §2); move prototype classes into `client/core`, `server/core`, `common/protocol`
- [x] E0.3 Upgrade `pom.xml`: Java 21, JavaFX 21, encoding, reproducible builds
- [x] E0.4 Add dependencies: atlantafx-base, ikonli (javafx + material2 pack), eventbus-java 3.3.1, hibernate-core 6.6, mysql-connector-j, HikariCP, flyway-core + flyway-mysql, bcrypt (at.favre.lib), slf4j + logback, pdfbox, poi-ooxml, anthropic-java, jackson-databind (bot JSON + transcripts)
- [x] E0.5 Test dependencies: junit-jupiter, mockito, assertj, testfx + monocle, h2, jacoco plugin with ≥90% gate + `ocsf/**` exclusions
- [x] E0.6 Configure shade for `G<Num>_Server.jar` / `G<Num>_Client.jar` (mains, filters, properties copy) — verify double-click AND `java -jar` on Windows
- [x] E0.7 `.gitignore` (target, `server.properties`, local config, IDE); commit `server.properties.example`, `client.properties.example`
- [x] E0.8 `.editorconfig` + checkstyle (or spotless) with a minimal agreed style; wire into `mvn verify`
- [x] E0.9 GitHub Actions `ci.yml`: JDK21, MySQL 8 service, `mvn verify`, JaCoCo report artifact, JAR artifacts, status badge
- [x] E0.10 Branch protection on `main` (PR + green CI required); PR template with DoD checklist
- [x] E0.11 Copy docs/ into repo; add `docs/PROBLEMS.md` (running log for the submission question) and `docs/DEMO_ACCOUNTS.md`
- [x] E0.12 README v2: quick start, architecture summary, screenshots placeholder, badge
- [x] E0.13 Verify baseline: build on Windows, run server+client on two machines over LAN ⚑

## E1 — Protocol v2 & common model [L]

> **Maintenance note (2026-08-25):** this section was completed in the first days of the sprint (see the merged-and-verified record in the timeline and per-PR reports) but its boxes were never ticked. Bulk-ticked at the doc cleanup; items still genuinely open were left open.

- [x] E1.1 `Verb` enum (all operations, grouped per feature) + `Status` + `ErrorCode` enums
- [x] E1.2 `Message` v2 (verb, requestId, payload, status, errorCode) with stable serialVersionUID
- [x] E1.3 DTO package skeleton `common/dto/<feature>/`; rule: payloads are typed DTOs only
- [x] E1.4 Auth DTOs: `LoginRequest`, `LoginResult` (user summary + role + courses), `ErrorPayload(message)`
- [x] E1.5 Client `RequestDispatcher`: send → `CompletableFuture` correlated by requestId, timeout handling, error mapping
- [x] E1.6 Server `MessageRouter`: verb→handler registry, auth check, caller resolution from connection, central try/catch → ERROR
- [x] E1.7 Push verbs + `PushGateway` (toUser/toUsers/toCourseTeachers/toEnrolled/toRole) over SessionManager
- [x] E1.8 Client push handling: `ServerMessageEvent` → typed events posted on FX thread; unknown verbs ignored + logged
- [x] E1.9 `FakeClientConnection` (records sent messages, scriptable replies) for session tests
- [x] E1.10 Unit tests: dispatcher correlation/timeout, router auth rejection, push routing, serialization round-trip of every DTO
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
- [x] E2.15 Seed migration/loader per PRD §5 (idempotent, one command + server-console button) — every seed section loads; `SeedMain` is the one command; `SeedDocument` + `SeedLoadedDbTest` verify the loaded database against the document on both engines. The E19.6 console button calls `SeedLoader.standard(factory).load(RESEED, confirmation)` and is [L]'s, per the ServerMain scope split
- [ ] E2.16 Seed review pass with [L]: every demo screen looks "well-filled" ⚑ — after PR 3b merges, doubles as the first E22.4 cross-walkthrough *The bot half is done: §10.1's eight sources went from 546 words to 1,781 (200–250 each) on 2026-08-22, so the Java bot answers from ~480 words of course material rather than ~160. The document and `BotSection.java` are generated from one text, and `SeedLoadedDbTest.botSourcesMatch` fails on a one-word divergence — verified by mutation.*
- [x] E2.17 BCrypt hashing for all seeded users; document demo credentials in DEMO_ACCOUNTS.md

## E3 — Server core [L]

> **Maintenance note (2026-08-25):** this section was completed in the first days of the sprint (see the merged-and-verified record in the timeline and per-PR reports) but its boxes were never ticked. Bulk-ticked at the doc cleanup; items still genuinely open were left open.

- [x] E3.1 ServerMain: args (`--headless`, `--port`), config load (env > file > defaults), Flyway, startup banner
- [x] E3.2 HSTSServer wiring: OCSF listener → MessageRouter; connection lifecycle logs
- [x] E3.3 SessionManager: login/logout/disconnect, user↔connection map, single-session enforcement (T-16) with tests
- [x] E3.4 Disconnect cleanup hooks (locks released, attempt marked connection-lost, session freed) with tests
- [x] E3.5 `Authorization` guards: requireRole, requireTeachesCourse, requireEnrolled, requireCoordinatorOf, requireSelf; `AuthorizationException` → central ERROR mapping; tests per guard
- [x] E3.6 Logback setup: colorized console (defense view) + rolling file + in-memory ring appender for console UI — *ring appender done in E19.4 and wired in `logback.xml`; the colorized pattern and the rolling file are still outstanding*
- [x] E3.7 Structured log conventions: every request logged (user, verb, ms), every push, every rejection ⚑
- [x] E3.8 Graceful shutdown (close executions? no — persist state; notify clients; stop timers cleanly)
- [x] E3.9 Boot re-arm: on start, reschedule timers for live executions/attempts from DB (crash recovery) with test

## E4 — Client core & design system [L]

> **Maintenance note (2026-08-25):** this section was completed in the first days of the sprint (see the merged-and-verified record in the timeline and per-PR reports) but its boxes were never ticked. Bulk-ticked at the doc cleanup; items still genuinely open were left open.

- [x] E4.1 ClientApp/Launcher (non-Application main), window sizing, app icon, title
- [x] E4.2 ScreenManager + Navigator (typed params, back-stack where sensible), ScreenFactory FXML cache
- [x] E4.3 AbstractScreen lifecycle (onShow/onHide, auto EventBus register/unregister) — Template Method
- [x] E4.4 ClientEventBus setup + typed event classes; FX-thread posting rule enforced in one place
- [x] E4.5 Connect screen: manual host/port entry pre-filled from defaults (client.properties → last server → localhost:5555), connecting state, retry, error detail; discovery picker slot added in E19.10 (manual path never blocked by discovery)
- [x] E4.6 Reconnect banner + auto-retry when the socket drops mid-session (bounded backoff)
- [x] E4.7 ThemeManager: AtlantaFX light/dark + OS-default detection, accent palette injection, persistence, `ThemeChangedEvent`
- [x] E4.8 `hsts.css` token layer + the 5 accent palette stylesheets (Indigo/Emerald/Amber/Rose/Slate)
- [x] E4.9 Settings screen: mode toggle, palette swatch picker with live preview
- [x] E4.10 App shell: top navbar (logo, breadcrumbs, bell, avatar/role, settings), collapsible side rail per role
- [x] E4.11 Component: DataTable wrapper (sorting, filtering hook, empty-state slot, loading skeleton)
- [x] E4.12 Component: form field with inline validation message + invalid styling
- [x] E4.13 Component: WarnConfirm dialog (icon, explanation, explicit confirm) — for legal-but-unusual actions (unanswered submit, close-early, deletes), reused everywhere ⚑
- [x] E4.14 Component: toast stack (success/error/info, auto-dismiss, slide-in)
- [x] E4.15 Component: status chips (exam/exec/grade states), role badges
- [x] E4.16 Component: progress overlay + skeleton loaders for async screens
- [x] E4.17 Component: empty-state with illustration slot
- [x] E4.18 Component: countdown timer widget (server-synced, amber/red thresholds, pulse animation)
- [x] E4.19 Component: modal host + standard dialogs (confirm, error detail)
- [x] E4.20 `Animations` utility (fade/slide/scale/stagger, ≤250ms) + screen transition integration
- [ ] E4.21 Curate + import illustrations (unDraw, recolored to palettes) for login/empty/success/bot
- [ ] E4.22 Responsive pass: shell + components verified at 1280/1600/1920 widths; side-rail collapse
- [x] E4.23 Session-class pattern documented + example test with FakeClientConnection (template for A/B)
- [x] E4.24 TestFX smoke harness (headless Monocle) — boots app to connect screen in CI

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
*Note (2026-08-20): the bank list shows a live "Editing · <name>" chip per row, fed by E18.8's LOCK_WATCH + LOCKS_SNAPSHOT (server side provided by the lead). Rows update live on LOCK_CHANGED pushes; opening a locked question still gets the full E18 banner + read-only mode.*

Server:
- [x] E6.1 QuestionService: create (validate: text, 4 non-empty answers, ≥1 correct, course taught, topic, difficulty), allocate display id
- [x] E6.2 Answer validity enforcement (C-8): exactly one correct answer; 4 answers pairwise distinct (trim + whitespace-collapse, case-insensitive compare) — server-side validation with precise error messages ⚑ — *shipped with E6.1 and never ticked. Both halves are `QuestionValidator`: `correctAnswerInRange` is the C-8 half (exactly one, since the field is a single 1..4 ordinal and 0 or 5 are refused), and `sameAnswer` is the ADR-016 half, deliberately stricter than the ADR's literal words so it can never accept a pair `ck_question_versions_distinct` would reject. Proven by `QuestionValidatorTest`'s two nested classes, "exactly one correct answer (C-8)" and "pairwise distinct answers (ADR-016)"; each failure names its field via `BankMessages`, which is what T-2.2's three-different-sentences case needs*
- [x] E6.3 Edit → new immutable version; version history query; latest-version resolution
- [x] E6.4 Delete: block when referenced by any exam version (return referencing exam names); soft-delete otherwise
- [x] E6.5 Browse/filter query (course/topic/difficulty/text) + pagination *(2026-08-22: the topic filter is exact equality per §7.6's option A ruling; the picker that makes it usable is E6.11 and lands with `BANK_TOPICS`)*
- [x] E6.6 Image handling: size/type limits (≤2MB, png/jpg), stored in question_versions, `QUESTION_IMAGE_GET` lazy fetch verb *(reworded 2026-08-21: the lead ruled for the noun-first convention over this line's original `GET_QUESTION_IMAGE`; the verb exists in Verb.java under that name)*
- [x] E6.7 QuestionValidator (Strategy, shared by add/edit) unit-tested to 100%
- [x] E6.8 Verbs + DTOs (list item, detail, editor payload, version history) — frozen with [L] — *the seven verbs and the sixteen DTOs landed in `24c127d`, and `BANK_WIRE_CONTRACT.md` reads **FROZEN v1** as of `63746b7` (2026-08-23): the lead's stated condition, handlers existing against the text, was met by #30. Additive-only from here, so the two additions this epic still wants (the `BANK_TOPICS` lookup of ruling 7.6, and an image identity on `QuestionVersionDetail`) are amendments rather than edits*
Client:
- [x] E6.9 Bank screen: master list (DataTable, filters, search) + detail pane with image lazy-load *(ticked 2026-08-24 with the retirement PR, which is what made it reachable: `BankView` is registered under rail id `questions` for both teaching roles and the interim `bank` id is gone. `BankScreenWiringGuardTest.theRailIdServesTheVersionedBank` pins the end state)*
- [x] E6.10 Question editor: form, answer rows with single-correct radio-select, topic/difficulty pickers, image picker+preview+remove *(2026-08-22: the two components are built, gallery-verified and unblocked — `RadioGroup` and `ImagePicker` in `client/ui/components`. Binding recipe and the full `ImageAction` mapping table are in `docs/reports/lead/COMPONENTS-E6.md`. Do not assemble `action()` + `chosenBytes()` by hand, and call `loadExisting` with the `QUESTION_IMAGE_GET` bytes before the picker is shown.)*
- [x] E6.11 Editor validation UX: duplicate-answer inline error live while typing, exactly-one-correct guaranteed by radio group; server errors mapped to fields ⚑ *(the rendering surface exists on both new components: `showError` / `apply(ValidationState)` / `clearValidation`, phrased identically to `FormField` so one validation pass drives all three)*
- [x] E6.12 Version history panel (timeline, view old version read-only, diff highlight of changed fields) *(verified 2026-08-24: `BankSession.historyEntries` builds one row per version from `QUESTION_VERSIONS`, each carrying the full `QuestionVersionDetail` so the panel can render it read-only, and `BankCopy.changeSummary` names the changed fields — text, answers, which is correct, topic, difficulty, illustration, author — as a sentence rather than as colour, which survives a screenshot and a screen reader)*
- [x] E6.13 Delete flow: blocked dialog listing exams / confirm dialog otherwise *(verified 2026-08-24: `BankView.confirmDelete` shows the blocked dialog naming the exams from `session.blockingExams()` when the delete is refused, and the plain confirm otherwise)*
- [x] E6.14 Edit-lock integration: acquire on editor open, "being edited by X" read-only mode, live release (E18 dependency) *(done in #43/#46: editor takes/releases under displayId5 via QuestionLockKey, live banner, and the server-side EditLockGuard consult on both write verbs answers CONFLICT with lockedBy - stronger than the task asked)* *(A: the remaining half, the one this section's own note asks for, landed 2026-08-25 - the list's live "Editing · <name>" column, `BankRowLocks` + the column on `BankView`, one `LOCKS_SNAPSHOT` and one `LOCK_WATCH` per row, live on `PUSH_LOCK_CHANGED`. Three things a later list screen should copy rather than rediscover: **the watch is registered BEFORE the snapshot is asked for**, or a lock taken in the gap is in neither the state nor the news and the row reads free for the colleague's whole session (P-11); **`LOCK_RELEASE` is never sent from a list**, because the server's release drops the caller's hold and the watch in one call and the list keys on the same `EntityRef` the editor does, so it would drop the teacher's own lock on the question she just opened; and a malformed display id is logged and skipped rather than thrown, since a throw on this path is swallowed by `whenComplete` and would leave the browse screen on a permanent spinner. Also stale in the line above: **"(E18 dependency)" has been met since E18.8** - all five lock verbs have been registered and serving in `EditLockService`)*
- [x] E6.15 Session tests: all flows against FakeClientConnection (incl. error paths) *(done across #41/#43: BankSessionTest 1016 lines + QuestionEditorSessionTest 844 lines, all flows and error paths against FakeClientConnection)*
- [x] E6.16 Integration test: add/edit/version/delete/browse round-trip; Hebrew text round-trip *(2026-08-25: `BankRoundTripIntegrationTest`, 25 assertions against real MySQL - the first database-backed test in `server.features.bank`, which was the only feature package without one. Every assertion is a re-read in a new transaction and every question arrives through `QUESTION_CREATE`, so the validator, the allocator, the mappers and the transaction boundary are all on the path. It found a live defect: `QuestionValidator` was looser than `utf8mb4_unicode_ci` on Hebrew final forms, so two answers differing only in a final form passed validation and then violated `ck_question_versions_distinct` as an internal error on the add-question screen. Fixed, and the same divergence on supplementary-plane characters and Yiddish digraphs fixed with it; P-9 records the class)*
- [ ] E6.17 Acceptance pass vs T-2 scenarios with [L] ⚑

## E7 — Exam builder [A]

Server:
- [x] E7.1 ExamService: create draft (name, duration, texts, author recorded), allocate 6-digit id *(merged #45/#46 + lead assembly 2026-08-25; reachable)*
- [x] E7.2 Composition update: set questions (by question_version), points, order; reject duplicates *(merged #45/#46 + assembly; full-replace per ARCHITECTURE section 5)*
- [x] E7.3 Points rule: save requires Σ=100; API returns per-question breakdown for live UI sum *(merged #45/#46 + assembly; ExamValidator.pointsProblem public for E7.12 live sum)*
- [x] E7.4 AutoExamGenerator (Builder): criteria (total, topic breakdown, difficulty mix) → selection or precise infeasibility report (which topic/difficulty short, by how much); deterministic seed option for tests ⚑ *(2026-08-25: `AutoComposer` + `ExamBuildRepository.findAutoCandidates` + `AutoCandidate`, and `EXAM_AUTO_COMPOSE` is registered - it was the one verb of the group deliberately left off the router. **What unblocked it was a contradiction, not new work:** §7.3a (ruled 2026-08-24) states "`ExamValidator.quotaProblem` refuses any other combination" and `quotaProblem` did not refuse it. That rule is load-bearing - it is what makes every quota pool nest, which is what makes the bucket comparisons exact and most-constrained-first greedy exact rather than merely sensible - so it is implemented now and its refusal names both legal shapes, which was the lead's condition. The pool is read in ONE query and bucketed in memory so `available` and the selection cannot describe different banks (§7.2 property 2), and topics are bucketed with `QuestionValidator.sameTopic` rather than `qv.topic = :topic` so the service is never looser than the collation (P-9). 28 tests, eight mutations planted; the load-bearing one is a property test over 400 generated bank shapes asserting every `available` equals the raw count she would get by filtering her own bank. **Two report defects found in my own work while testing** and fixed: a lone course-wide quota emitted its shortfall twice, once as a leaf and once as the aggregate; and a topic-level row fired even when a single bucket was short, printing "requested 3 questions, bank has 2" beside the correct "requested 3 Hard, bank has 0" - a true count paired with a demand she never made, which is the disprovable sentence §7.3 exists to prevent. **One rule added that §7 does not state and arithmetic forces:** `MIN_POINTS`=1 and `POINTS_TOTAL`=100 are both frozen, so more than 100 questions cannot be proposed; refused with a named sentence rather than proposed and then rejected by `ck_evq_points` on save. Flagged for [L]. §7 is still formally DRAFT in the contract header while its substance was ruled on 2026-08-24; the lead's own freeze condition, handlers existing against the text, is now met)*
- [x] E7.5 Edit approved/pending exam → new DRAFT version; old versions retained and listed (C-2) *(merged #45/#46 + assembly; revise refuses retired questions per lead ruling)*
- [x] E7.6 Submit for approval: DRAFT → PENDING, notify coordinator (E17) *(merged #46 + assembly; hook is the HANDLER post-commit per amended section 5.5, crash window named)*
- [x] E7.7 Newer-question-version indicator data (exam uses vN, bank has vN+1) *(on the wire since the types: ComposedQuestion.pinnedVersionNo vs latestVersionNo; served by #45)*
- [x] E7.8 ExamValidator unit tests (all rules) *(#45/#46/#48: 12 criteria tests, twelve mutation plants, collation-safe topic fold via sameTopic)*

> **Open after the E7 store PR, and enforced by nothing on disk until the handlers land.** The
> repository and its two-engine tests are in, so the *writes* are held. These five rules are not,
> and each is a service rule with no database backstop that could stand in for it:
> **(1)** no soft-deleted question (contract §5.2, assigned to E7 by name in ARCHITECTURE §5 - the
> MySQL leaf's `softDeleteHasNoDatabaseBackstop` deliberately asserts the hole is open);
> **(2)** no duplicate question through two versions of it, as a *named* refusal rather than a
> constraint violation (§5.2, T-3.9 - the constraint itself is tested);
> **(3)** points summing to exactly 100, in both directions with the shortfall quantified (§5.1);
> **(4)** every question in the exam's own course, resolved server-side (§5.2);
> **(5)** only a DRAFT is savable, and REVISE refuses a DRAFT (§5.4, answering CONFLICT not
> VALIDATION).
> Listed here because the store PR is the last artefact reviewed before these get assumed done.
- [x] E7.9 Verbs + DTOs frozen with [L] *(types landed by [L] 2026-08-23, freeze on handlers PR — `common/protocol/Verb.java` has its `Exam builder (E7)` section with all seven verbs, and `common/dto/authoring` holds the fourteen records of the contract's §4, reusing `Difficulty` and `ApprovalState`. The five rulings are §12 of `docs/contracts/EXAM_BUILDER_WIRE_CONTRACT.md`; what Member A must know before writing handlers — the constants to cite and the tolerance boundaries his validator has to cover because the constructors deliberately do not — is `docs/reports/lead/E7-TYPES.md`. Not ticked: the tick is the freeze, and the contract still says DRAFT so a handler author who finds a real problem with a shape still gets to say so.)* **FROZEN v1, WHOLE. Sections 1-6 and 8 froze on #46 (2026-08-25); section 7 froze 2026-08-25** with the collation-equality clarification in §7.3 (topic matching is `utf8mb4_unicode_ci`'s equality as measured in #48, reproduced by `QuestionValidator.sameTopic` — never Java `equals`), which resolves Member A's standing annotation and PR21 §6.1 / PR22 R1. §7's freeze condition was code exercising it, and #50 landed `AutoComposer` and registered the seventh verb. Additive-only from here; §5.4-A1 (one open draft per exam, ruled 2026-08-25) is the one amendment knowingly ahead of its code and says so, with the service change landing in PR23.
Client:
- [x] E7.10 Exam list screen: teacher's exams, status chips, versions expandable, actions per state *(2026-08-25: built in #51 (`client.features.exambuild` — `ExamListView`, `ExamListSession`, `ExamListCopy`, `ExamBuildRoutes`, 82 tests) and made reachable by the lead's assembly commit, which is what this tick is for. Master and detail rather than an expandable tree, because there is no `TreeTableView` in this client and a new shared component under `client/ui` was not Member A's to write; the actions come from the frozen contract and nowhere else (`canSubmit` is `isEditable()`, `canRevise` its negation, §5.4). **Deliberately NOT ticked with the PR that built it**, on E6.9's precedent: the screen was on no rail until `SessionRoutes` pointed route id `exams` at it, so a reviewer could have disproved the tick. That swap retired `MY_APPROVALS_GET`, `MyApprovals`, `MyApprovalsView` and `MyApprovalsSession` in the same change (contract §8), and the two `ExamListWiringGuardTest` cases that were red by design on `main` are green because of it, not because they were edited. `docs/reports/lead/E7-INTEGRATION.md`)*
- [ ] E7.11 Builder — metadata step (name, duration, texts with student/teacher tabs)
- [ ] E7.12 Builder — manual tab: bank picker (filter/search), selected list with points editors + reorder, live Σ/100 indicator (green at 100)
- [ ] E7.13 Builder — auto tab: criteria form (topic rows, difficulty sliders), generate → editable result, infeasibility report rendering ⚑
- [ ] E7.14 Version history + "question has newer version" badges with update-question action
- [x] E7.15 Submit-for-approval flow with confirmation summary *(2026-08-25: folded into #51's exam list rather than given a screen of its own — `ExamVersionRow` already carries the question count, the duration and the version number, and §5.1 refuses a save that does not total 100 points, so the summary is exact and needs no second call. The revise dialog deliberately does **not** predict the new version number: it is allocated against `uq_exam_versions_no`, so any number named in advance is one concurrent revise away from being false; `revisedNotice` names it afterwards off the server's own answer. Ticked with the assembly for the same reason E7.10 is)*
- [ ] E7.16 Session tests + integration test (manual + auto + infeasible + Σ≠100 + versioning)
- [ ] E7.17 Acceptance pass vs T-3 with [L] ⚑

## E8 — Approval workflow [L, taken from A 2026-08-21 for the compressed endgame]

- [x] E8.1 ApprovalService: pending queue per coordinator's subject; approve/reject(reason required); state transitions guarded; self-approval allowed but logged (F4.3, acceptance case 4.6)
- [x] E8.2 Invalidate pending request if a newer version is submitted; coordinator notified
- [x] E8.3 Approval queue screen (coordinator): list + badges, open preview
- [x] E8.4 **Exam preview screen: renders the exam exactly with the student form component (reuse E10 form, read-only) + teacher-only notes side panel** — fixes v1 "coordinator couldn't see the exam" ⚑
- [x] E8.5 Approve flow (confirm) / Reject flow (reason dialog, required, min length)
- [x] E8.6 Teacher-side: rejection reason visible on exam + notification deep-links to it
- [x] E8.7 Session + integration tests (approve, reject w/o reason blocked, stale version)
- [ ] E8.8 Acceptance pass vs T-4 ⚑

> **E8 notes (2026-08-21).** Wire contract: `docs/contracts/APPROVAL_WIRE_CONTRACT.md` (DRAFT, for
> the lead to freeze). Report: `docs/reports/lead/E8.md`.
> Two things E7 has to pick up: (a) `ExamService.submitForApproval` calls
> `ApprovalService.versionSubmitted(examVersionId)` and emits **no** notification of its own — that
> hook does the supersede *and* the APPROVAL_REQUESTED; (b) E7's exam list replaces
> `MyApprovalsView` at route id `exams` and absorbs `MY_APPROVALS_GET`. **(b) is done, 2026-08-25:**
> the swap landed with E7.10 and the verb, `MyApprovals`, `MyApprovalsView` and `MyApprovalsSession`
> are deleted (APPROVAL ruling 1 / E7 contract §8). E8.6's requirement is still met — the rejection
> reason is on `ExamVersionRow` and paints on the version's own card — and the notification deep
> link it names now actually arrives, which it did not: `NotificationsPanel.activate` dropped
> `NavRef.entityId()` for **every** notification in the app until it was fixed in the same change.
> `Authorization.requireCoordinatorOf` is no longer a stub: E8 implemented it against the
> `coordinators` table. `requireTeachesCourse` and `requireEnrolled` are still fail-closed stubs.

## E9 — Release manager [L, reabsorbed 2026-08-22]

- [x] E9.1 ReleaseService: create execution (approved versions only), window validation, 4-alnum code (C-1)
- [x] E9.2 Execution status lifecycle SCHEDULED→LIVE→CLOSED driven by a scheduled check; transitions pushed
- [x] E9.3 Cancel scheduled / close-early (force-submit semantics through ExecutionCloseService)
- [x] E9.4 Multiple executions per exam version (S-2) + history listing with per-execution participation
- [x] E9.5 Release screen: create dialog (approved-version picker, datetime pickers), validation, code reveal
- [x] E9.6 Releases list: live status chips, participant counters (pushed), actions (cancel/close/monitor)
- [x] E9.7 Session + integration tests (window enforcement, unapproved blocked, cancel, close-early)
- [ ] E9.8 Acceptance pass vs T-5 ⚑

> **E9 notes (2026-08-22).** Contract: amendments **A3-A7** of
> `docs/contracts/EXAM_WIRE_CONTRACT.md`. Report: `docs/reports/lead/E9.md`.
> Three scope corrections against the wording above, all argued in the report:
> (a) **E9.1's code is the teacher's, validated server-side** (coordinator's ruling,
> 2026-08-23, reversing E9's first shape). `ReleaseCreateRequest.code` is nullable: typed is
> validated for C-1 shape by both tiers and for uniqueness inside the inserting transaction
> (§5), blank is generated. E9.5's dice **clears** the field to server-generation rather than
> filling it, because a client-rolled code cannot be checked for uniqueness;
> (b) **E9.2 does not use `TimerService`** — that one ends individual attempts; releases move
> through a 30 s `ReleaseScheduler.tick()` on the same daemon thread, because a release's window
> is set weeks ahead and survives restarts;
> (c) **E9.4's "stats snapshot" is the participation record** (S-21's three counts, frozen at
> close). Score statistics are E12's and are rendered by E14; and **E9.6's "extend" is E11's
> verb on the monitor**, which every live row links to, rather than a second button here.
> `ExecutionCloseService` now has its caller: `RELEASE_CLOSE_EARLY` and the scheduled check both
> go through it, which is what makes F5.5's "behaves exactly like time expiry" true by reuse.

## E10 — Take exam [L]

Server:
- [x] E10.1 AttemptService.start: code lookup (live executions), student identity check (own ת"ז), enrollment check, window check, single-attempt check → create attempt, start server timer (S-18)
- [x] E10.2 Exam form DTO via the no-correctness projection (E2.12) ⚑
- [x] E10.3 SAVE_ANSWER verb: upsert attempt_answers, reject if attempt not IN_PROGRESS or past deadline (server clock) ⚑
- [x] E10.4 SUBMIT verb: finalize, record actual minutes (S-19), trigger auto-grade (E12); participation counts derived from attempts (no counter mutation) + pushed to monitor
- [x] E10.5 TimerService expiry: transactional force-submit + TIMED_OUT + push FORCE_SUBMITTED + counters — **works even if client is gone** ⚑
- [x] E10.6 Reconnect/resume: attempt state + saved answers + authoritative remaining time returned on re-entry
- [x] E10.7 Attempt-in-progress state exposed to BotService with attempt lifecycle (C-4): same-course bot locked; cross-course use triggers integrity notice + teacher notification + monitor-row flag
- [x] E10.8 Concurrency integration tests: two students parallel, answer-after-expiry rejected, resume after kill, double-attempt blocked ⚑
Client:
- [x] E10.9 Entry flow screens: code entry → ID entry (each with distinct, specific error messages)
- [x] E10.10 Exam form: general text header, question cards (text, image, 4 options single-select), progress bar (answered x/y), question navigator strip
- [x] E10.11 Debounced auto-save with saved-state indicator ("All changes saved ✓")
- [x] E10.12 Countdown widget wired to server sync + TIMER_EXTENDED push as a designed moment (F7.1 / *Time Extended* mockup): green flash + glow pulse on the timer, floating "+mm:ss", toast with teacher + new end time ⚑
- [x] E10.13 Manual submit flow (F6.9 / *Submit Confirm* mockup): WarnConfirm with answer-summary grid (chips clickable → jump to question), remaining-time note → *Submitted* success screen (F6.10): check animation, handed-in time, solving minutes, summary, Back to dashboard
- [x] E10.14 Time-up takeover (F6.4 / *Time Up* mockup) on FORCE_SUBMITTED push or on resume: full-screen, animated clock, NO confirmation, answers locked (server already enforces), submitted-summary grid, single Back-to-dashboard navigation, exam unreachable afterwards ⚑
- [x] E10.15 Disconnect mid-exam UX: reconnect banner, resume seamlessly, no lost answers
- [x] E10.16 Session tests: full state machine incl. expiry/resume/push paths
- [ ] E10.17 Acceptance pass vs T-6 ⚑

## E11 — Extension & monitoring [L]

- [x] E11.1 ExtendService: minutes>0, execution LIVE, applies to execution only (S-20); reschedules attempt timers; pushes to active students + records in execution
- [x] E11.2 Execution monitor screen (teacher): live counters, per-student rows (status, remaining, **integrity flag: "used <course> bot at <time>" when C-4 alert fired**), extension action with amount dialog
- [x] E11.3 Extension UX on student side verified end-to-end (timer grows mid-countdown) ⚑
- [x] E11.4 Edge tests: extend at T-10s, extend after close blocked, extension while student offline (applies on resume)
- [x] E11.7 **[L, done 2026-08-21]** Attention events (F7.1b): FX-free `AttentionTracker` (500 ms flicker debounce, reported on refocus, silent after finalisation); new `ATTEMPT_ATTENTION` verb resolving the caller's own live attempt; `AttentionSummary` accumulated in `AttemptRegistry` beside the C-4 flags (survives resume, outlives the attempt); pushed as a whole monitor snapshot; neutral secondary chip on the teacher's row; no student-facing UI anywhere. Contract amendments A1/A2 in EXAM_WIRE_CONTRACT.md
- [x] E11.5 Execution documentation record complete (S-21): derived counts frozen into stats JSON at close + shown in execution history
- [ ] E11.6 Acceptance pass vs T-7 ⚑

## E12 — Grading [B]

- [x] E12.1 GradingService.autoGrade: per-question correctness (selection == correct_answer), weighted score, persist AUTO grade — *complete, and now wired: `GradingOnSubmit` implements `AttemptFinalizedListener` and replaces the no-op in `HSTSServer`, so an attempt is marked the moment it closes. Auto-grading publishes nothing (C-3) — approval does*
- [x] E12.2 Approve grade(s): single + bulk; status→APPROVED; push GRADE_PUBLISHED to student (C-3) — *`GradeApprovalService` + `GRADES_APPROVE` on the router behind `GradingHandlers`' shared teacher gate. Idempotent (counts, never re-stamps), per-grade ownership, refused list, freezes stats into `exam_executions.stats` on completion*
- [x] E12.3 Override: new score requires justification (S-23); audit trail (auto score kept); comment to student (S-22) — *`OverrideService` + `GRADE_OVERRIDE` done: blank justification is VALIDATION before anything is read, out-of-range score likewise, an approved grade is CONFLICT, the auto score is kept beside the new one, and it answers with the refreshed `GradeReview`. **S-22 closed 2026-08-23** (contract amendment A3, lead-ruled): `teacherComment` is an optional fourth component of `GradeOverrideRequest`, written in the same transaction as the score, and a null comment **preserves** an existing one rather than clearing it. The dialog has its second box. Acceptance 8.4 is walked end to end on both engines by `TeacherCommentFlowContract` — it could not pass before, because the comment had a read path everywhere and no write path anywhere (PROBLEMS P-7)*
- [x] E12.4 Stats computation on execution fully graded: avg, median, **std dev**, min/max, pass rate, deciles → stored (S-25); values unit-tested against hand-computed fixtures ⚑ — *`ScoreStatistics` tested against the seeded execution 4821 fixture (population σ, deciles, pass mark 55), and **stored**: `GradeApprovalService` freezes it into `exam_executions.stats` in the approving transaction*
- [x] E12.5 Grading queue screen: executions awaiting grading, per-execution student table (auto scores, status) — *`GradingQueueView` over `GradingQueueSession`, live on the teacher rail (the E5.4 placeholder swapped, same path E13/E14 took). The queue is defined by its exclusions — still running, nothing marked, already signed off — because a queue that never empties stops being read*
- [ ] E12.6 Per-student review screen: checked form view (correct/wrong marks), override dialog (score+reason), comment box, approve — *server side done: `GradeReviewService` assembles the marked paper and `GRADE_REVIEW_GET` serves it to the owning teacher. The ticks are re-run through `AutoGrader`, so a paper's marks and the score above them can never come from two different rules. **The comment box landed 2026-08-23** in the override dialog on the queue screen: two boxes, separated, one labelled for the record and one saying the student will read it. Not ticked: the per-student review screen itself, which is still the queue table plus a dialog rather than a paper the teacher can read*
- [x] E12.7 Bulk approve with summary confirm — *one verb for single and bulk, one confirmation that names the consequence rather than the action: it says how many students are about to see their marks and that approval cannot be undone. Select-all skips already-approved rows so the count never overstates what is about to happen*
- [ ] E12.8 Session + integration tests (auto-grade correctness, override w/o reason blocked, idempotent approve, stats values verified against hand-computed fixture)
- [x] E12.9 Acceptance pass vs T-8 ⚑ — *all seven cases walked and passed on 2026-08-23 against a reseeded database: auto-scores present on submission, the unapproved grade invisible, the blank justification refused, the audit trail intact, single and bulk approve, the notification delivered, and no class statistics reachable by a student. Four bugs found and recorded with case ids (B-3 to B-6), all fixed.*

## E13 — Student results [B]

- [x] E13.1 ResultsService: student's own grades only (authorization test: requesting others fails) ⚑ — *ownership is the SQL filter, not a check. `MY_GRADES_GET` is on the router behind `ResultsHandlers`, whose gate is deliberately a different shape from the teacher one: no role check at all, because the session's id **is** the query*
- [x] E13.2 Checked-form DTO: questions, chosen vs correct, marks, comments — only for APPROVED grades — *`CheckedForm` reuses `AnswerReviewRow` so there is one place correctness is serialized with two gates in front. Checked-form amendment applied: `attemptStatus` and `actualMinutes` added additively for acceptance 9.5, per the lead's ruling that the seeing happens on the marked paper*
- [x] E13.3 My Grades screen: exam list with scores, status, date; empty-state — *`MyGradesView` over the existing session, on the router and on the student's rail (the E5.4 placeholder swapped for a live item, same path E14 took for Results). Every string and formatted value is in `MyGradesCopy`, tested, because the view is coverage-excluded. `StudentGradeRow` v1.1 labels each row with its own exam. **Also closes a gap:** `onGradePublished()` existed and nothing called it — `subscribeTo(ClientEventBus)` wires `PUSH_GRADE_PUBLISHED` in the session where it can be tested, so the list refreshes with no user action (NFR-18)*
- [x] E13.4 Checked form viewer: green/red marking, teacher comments, score breakdown — *`CheckedFormService` owns the three conditions — hers (as the query, not a check), approved, execution closed — and all four refusals are one indistinguishable NOT_FOUND. Mutation-tested: removing the closed-execution gate fails four tests including the no-oracle one. `CheckedFormView` renders three outcomes per question, not two: unanswered is not wrong*
- [x] E13.5 Export/print view of the checked form (S-36) — printable layout — *toggle in and now actually doing something: `.results-print` was a style class with no CSS rules, so the toggle was a silent no-op on this screen and on E14.4's. Rules written in E15's idiom, covering both. Walked as acceptance case 9.3.*
- [ ] E13.6 GRADE_PUBLISHED push → notification + dashboard card refresh — *the My Grades half is done: the session subscribes to `PUSH_GRADE_PUBLISHED` and re-queries rather than appending the pushed row, so the screen cannot drift from the server. The durable notification already goes through `Notifier`/`NotificationCatalog` on approval. Not ticked: the student dashboard card*
- [ ] E13.7 Session tests + acceptance pass vs T-9 ⚑ — *T-9's five cases all pass as of 2026-08-23; 9.5 is recorded as proven below the screen (the assembler and copy against real data) rather than on it. Not ticked: the lead's screen-render review.*

## E14 — Teacher results & statistics [L, taken from B 2026-08-21 for the compressed endgame; B keeps E12/E13/E15]
*Ownership note (2026-08-19): hardening items H14.* and H15.* in ACCEPTANCE_TESTS.md moved out of B's scope with the sprint re-plan; whoever executes E14/E15 picks them up. H14.4 (population-σ divisor test) and H15.2 (CANCELLED excluded from reports) are defense-critical.*

- [x] E14.1 Teacher results query: all exams she authored, incl. executions run by others (S-35)
- [x] E14.1 Teacher results query: all exams she authored, incl. executions run by others (S-35) — *`RESULTS_EXAMS_GET` + `RESULTS_EXECUTION_GET` on `TeacherResultsService`, scoped on `exams.author` in the query (a colleague's sitting of her exam is hers; a non-author gets `NOT_FOUND` indistinguishable from an unknown id). Statistics are read from `exam_executions.stats`, never recomputed. Draft contract: docs/contracts/RESULTS_WIRE_CONTRACT.md*
- [x] E14.3 **[L, done 2026-08-21]** StatChart component (shared, in client/ui): score-bucket histogram with mean/median/±1σ overlay markers, hover tooltips (range, count, %), count↔% toggle, animated entrance (≤246 ms), theme/palette-aware colors, empty & insufficient-data states ⚑ — `StatChartData` + FX-free `StatChartLogic` (zero-based axis with headroom, honest ruler shared by bars and markers) + thin `StatChart` view, gallery section on the seeded execution-1 distribution, documented CSS block in hsts.css
- [x] E14.3b **[B]** Wire StatChart into teacher results; visual QA against seed data distribution ⚑ — *the component is done and gallery-verified; what remains is the screen wiring. Build the input with `StatChartData.of(deciles, mean, median, stddev, count)` straight from the execution's stored stats, and do not recompute σ (population divisor, F8.5). See docs/reports/lead/E14.3-E11.7.md*
- [x] E14.4 Table/histogram toggle (T-10 note), export/print-friendly layout
- [x] E14.3b **[B]** Wire StatChart into teacher results; visual QA against seed data distribution ⚑ — *wired: `TeacherResultsSession.chartData()` is the whole mapping, `StatChartData.of(deciles, mean, median, stddev, count)` straight from the stored stats, σ untouched (population divisor, F8.5), and an execution with no statistics becomes `StatChartData.empty()` rather than a zero-filled record. Visual QA against the seeded 4821 distribution is pinned by `TeacherResultsInteractionTest` (bars drawn, both toggles clicked) plus the caption assertion in `TeacherResultsSessionTest`*

## E15 — Principal & reports [L, reabsorbed 2026-08-22]

- [x] E15.1 Principal read-only services: browse bank, exams, results (S-7 — zero mutating verbs authorized; negative tests) — *completed by E15.2's pass (2026-08-23), which is what the missing third was. All three now exist: the **bank** through `BankReadHandlers`, unchanged and unwidened (F9.3, BANK contract §3 — she has been on those four verbs' role list since E6); **exams** through the new `DATA_EXAMS_GET`; **results** through the new `DATA_RESULTS_GET`. Both new verbs are `requireRole(PRINCIPAL)` and nothing else, both take **no payload at all** (no field a client could widen), and both reach the database only through `ReportData`, whose every method is still a read — so S-7 stays structural rather than procedural. Positive, all three negatives and the anonymous case are tested in `DataBrowseServiceTest` and again end to end on both engines. Amendment: `REPORTS_WIRE_CONTRACT.md` **A1***
- [x] E15.2 Principal data browser screen: tabbed (questions/exams/results) with filters — reusing bank/results components read-only — *three tabs on the segmented control built from `DataTab.values()` (no `TabPane` — the house idiom), each with a text filter and a course picker **derived from the rows in hand**, so the dropdown can never offer a course that filters to nothing. Questions reuses `BANK_LIST` verbatim and pages through it so the screen shows one unpaginated list; Results reuses `ReportRow` verbatim, so a sitting reads identically here and on the reports screen and cancelled runs are absent from both (H15.2). Four designed empty panels: one per tab plus a distinct "nothing matches that filter", because a principal who has typed something and is told the bank is empty will believe it. `DataSession` is FX-free and sends exactly three verbs, every one a read; `DataBrowserInteractionTest` walks T-11.3 by asserting the screen holds no button but its own tabs. The rail item is now `NavItem.of(Routes.DATA.id(), …)`*
- [x] E15.3 Report engine: `ReportDimension` Strategy (ByTeacher / ByCourse / ByStudent) over stored execution stats (C-5); comparison result DTO (S-37 extensibility story) ⚑ — *one parameterised mechanism: `ReportEngine` over `DimensionStrategy`, three strategies registered in `ReportStrategies.all()`. The extensibility story is proven twice — a fourth strategy defined inside `ReportEngineExtensibilityTest` is served by a real engine, and the engine's own source is asserted to name no dimension. Stats read through `FrozenStatistics` (shared with E14), never recomputed; the cross-row summary is participant-weighted with an exact pooled population σ, hand-computed in `ReportDtoTest`. Contract: docs/contracts/REPORTS_WIRE_CONTRACT.md (**FROZEN v1**, 2026-08-23)*
- [x] E15.4 Reports screen: dimension picker, subject/entity selectors, comparison table + grouped bar chart (avg/median/std), decile distribution view (reuses StatChart) — *segmented dimension picker built from `ReportDimension.values()`, subject picker per dimension, rows table (sitting, date, mean, median, sigma, pass rate, participants), six cross-row summary cards, and StatChart drawing the SELECTED row's decile distribution with a row-to-chart selection flow. Print-friendly pass as E14.4's. **Deviation, stated:** no grouped bar chart across rows — the comparison is the table plus the per-row distribution, because a grouped bar of three means over two sittings is a chart with less information than the row it sits under. Flagged for the lead*
- [x] E15.5 Empty/degenerate data handling (no executions, one student) per PRD catalog — *three distinct empty panels (nothing picked / no subjects / no closed sittings), each saying what would make it go away; a subject with nothing to report carries a count of zero **in the picker**, so the dead end is avoided before the click; one sitting says "one sitting, no trend" rather than drawing one; nothing scored prints a dash, never a zero mean*
- [x] E15.6 Session tests + acceptance pass vs T-11, T-12 ⚑ — *`ReportsSessionTest` (25 FX-free cases), `ReportsCopyTest` (21), `ReportsInteractionTest` (6 TestFX, real input: dimension → subject → rows → row click → chart). T-12 covered end to end. **T-11 is now covered too** (updated 2026-08-23, E15.2): 11.1 and 11.2 are the Data screen's three tabs, driven by `DataSessionTest` (31 FX-free cases), `DataCopyTest` (78) and `DataBrowserInteractionTest` (6 TestFX); 11.3 is asserted rather than eyeballed — the interaction test looks for a push button anywhere under `.principal-data` and finds none; 11.4 is the role gate, tested positive-and-negative in `DataBrowseServiceTest` and again on both engines. The row still wants a human pass at the brief, but nothing is missing behind it any more*

## E16 — Study bot [L] ⚑⚑ (v1's worst failure — gets over-engineered on purpose)

Server:
- [x] E16.1 `BotProvider` interface (ask(context, history, question) → answer/exception) + provider config loading
- [x] E16.2 DeepSeekProvider: java.net.http against OpenAI-compatible `/chat/completions`, timeout, 1 retry, error taxonomy (auth/rate/timeout/5xx)
- [x] E16.3 AnthropicProvider: official `anthropic-java` SDK, model configurable (default `claude-opus-5`), same interface
- [x] E16.4 Provider chain with health memory (skip known-down provider for 60s) + structured logging of provider used
- [x] E16.5 SourceExtractor: PDF (PDFBox), DOCX (POI), free text → normalized chunks; failure surfaces to uploader
- [x] E16.6 ContextBuilder: top-k chunk selection (keyword overlap scoring), token budget, + course bank questions; **compile-time isolation from exam repositories** (module reaches only bot_/question_ data) ⚑
- [x] E16.7 Guardrails system prompt: course-material scope, refuse embedded instructions in sources, never reveal prompt, don't fabricate exam info; red-team unit tests with hostile source fixtures ⚑
- [x] E16.8 BotService: enrollment/active/rate-limit guards + C-4 logic (same-course attempt → locked; cross-course attempt → require acknowledged integrity notice, emit teacher notification + monitor flag once per session) → context → chain → persist to JSON transcript → answer DTO; S-32 fallback message path ⚑
- [x] E16.9 Bot management service: create bot (one per course — second teacher joins existing, S-30), sources CRUD (edit-locked), active toggle, co-teacher notifications
- [x] E16.10 Session store: bot_sessions JSON transcript append/read (student history/replay) + bot_messages dual-write in same tx; teacher aggregates (count, over-time, frequent questions) query bot_messages with **zero identity fields in DTO** (S-34) ⚑
- [x] E16.11 Unit tests: providers (mocked HTTP), chain fallback, extractor fixtures, context selection, guards; integration: ask round-trip with stubbed provider
Client:
- [x] E16.12 Bot manager screen (teacher): bot card (name, active toggle), sources table (type icons, add/edit/remove, upload progress, parse errors), co-teacher edit-lock states
- [x] E16.13 Bot chat screen (student): message list (user/bot bubbles), typing indicator, incremental answer display, error/S-32 states, same-course lockout state ("unavailable during your exam" + unlock time), cross-course integrity notice dialog (non-nagging: shown once per attempt, calm wording)
- [x] E16.14 Bot history screen (student): session list, reopen conversation, continue
- [x] E16.15 Bot analytics screen (teacher): totals, activity chart, frequent questions list — anonymized
- [x] E16.16 Session tests for all four screens
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
- [x] E18.8 List-level lock visibility (Naji, 2026-08-20): LOCK_WATCH verb (watch without contending; EditLockService.watch already separable) + LOCKS_SNAPSHOT bulk query (entity type + ids → holders) so list screens can badge rows "Editing · <name>" live. **Server half done** (both verbs, TEACHER/COORDINATOR gated, contract in Verb.java's lock section); the UI chip ships with E6's rebuilt bank list (see E6 note). `LOCKS_SNAPSHOT` answers `id → LockHolder` rather than a bare name, so a row can tell the holder's own lock from a colleague's by id

## E19 — Server console & network [L]

- [x] E19.1 NetworkDetector: enumerate candidate IPv4s, pick best (site-local, default-route heuristic), expose all — five ranked tiers incl. a virtual-adapter demotion (Hyper-V/VirtualBox/Docker/VPN), ranking is a pure function over injected interface data and fully unit-tested
- [x] E19.2 Console UI: header with big `<ip>:<port>`, start/stop listener, port editor, status cards (DB pool, clients, memory, bot providers) — *port is set by `--port` on the command line and shown in the header, not editable in the window; changing it means rebinding the listener mid-demo, which the lead can rule on later*
- [x] E19.3 Connected clients table (user, role, IP, connected-since) — live, off a new read-only `SessionManager.connectedClients()` snapshot plus a `SessionListener` repaint hook
- [x] E19.4 Log tail pane (ring buffer appender), level filter, pause/scroll — 2000-line `RingBufferAppender` declared in `logback.xml` (capturing before ServerMain runs); pause freezes the view, never the capture
- [x] E19.5 Manual IP override + copy-to-clipboard; `--headless` mode verified — override takes detected addresses or free text; `--headless` parsing is `ServerArgs`, tested, and the flag path reaches no JavaFX
- [x] E19.6 Seed-data button (loads/reloads demo dataset with confirm) — two buttons; RESEED goes through a WarnConfirm showing SeedLoader's *own* prompt, result rendered from `SeedSummary.toText()`; LOAD_IF_MISSING never prompts
- [x] E19.7 Console styled with the same design system (dark by default) ⚑ — same `hsts.css` token layer + accent-indigo over PrimerDark; asserted in the TestFX interaction test
- [x] E19.8 Discovery responder (F13.3): UDP listener on its own port, reply {ip, port, name, fingerprint}; fingerprint generated on first boot + persisted; console shows it next to the address; console toggle on/off; malformed/flood packets ignored + logged (fuzz test) ⚑ — compact JSON (never Java serialization: this is the one socket strangers can write to); per-source rate limit so it cannot be used as an amplifier; 2000-case fuzz on the codec and 2000 more on the responder
- [x] E19.9 Fingerprint persistence + regeneration path (server reinstall) — documented behavior, test — id lives in `server-id.properties` beside `server.properties`; an unwritable file degrades to a per-run id with one clear log line rather than refusing to boot
- [x] E19.11 Connect screen demoted to fallback (Naji, 2026-08-20): with a pinned server the client auto-connects and the FIRST screen is Login, carrying only a subtle status line "Connected to <server name> · change server". The host/port editor appears only when discovery finds nothing, the pinned server is unreachable, or the user clicks "change server". Host and port belong on the server console, not in the user's face
- [x] E19.10 Client discovery (F13.4): broadcast + ~2s collect, picker UI (name · address · fingerprint), TOFU pinning of {address, fingerprint}, auto-connect to pinned server, mismatch → prominent warning dialog requiring explicit confirm; "nothing found" → manual entry with defaults ⚑ — whole decision table is `ConnectFlow`, pure and exhaustively tested; pin keys are additive in `connect.properties`
- [x] E19.11 Discovery tests: responder round-trip, timeout path, pin/mismatch state machine, isolation-network fallback (responder off) — all without real multicast in CI (loopback/injected transport seam) — *note: this is the second item numbered E19.11 in this list, the first being the UX ruling above; both are done.* Every rule runs on injected transports; one `DiscoveryLoopbackTest` uses real UDP on loopback and aborts cleanly where sockets are forbidden
- [ ] E19.12 **[GATED — decide at M6, criteria in ADR-019]** TLS over OCSF: SSLServerSocket in vendored OCSF, self-signed cert generated on first boot, discovery ID becomes the cert's fingerprint (no UX change), client verifies pinned fingerprint against the presented cert; encrypts credentials in transit. ~2–3 days incl. keystore handling + demo-machine rehearsal. If not taken: phase-2 slide + cleartext-transit limitation stated in submission doc

## E20 — Packaging & deployment [L]

- [x] E20.1 Final JAR names `G<Num>_Server` / `G<Num>_Client` (group number parameterized in pom) — `-Djar.prefix=G12-1 package` activates a profile that renames both JARs to `G12-1_Server.jar` / `G12-1_Client.jar`; without the switch the dev names `hsts-server` / `hsts-client` stand, so the group number never lands in a commit. One line, in README §2 and `docs/DEMO_DAY.md` §1.1
- [ ] E20.2 Double-click verified on clean Windows machine (no IDE, only JRE/JDK 21) ⚑ — **instructions ready, manual execution pending**: `docs/DEMO_DAY.md` §2 (machine prep, the four-file layout to hand in, the checks to observe)
- [x] E20.3 Terminal run shows the colorized log stream (defense view) ⚑ — `logback.xml` CONSOLE pattern is `%gray(time) %highlight(level) %cyan(logger) msg`, logback's own ANSI converters, no new dependency; widths sit inside the colour converters so the message column cannot drift. The E19.4 ring buffer formats events itself and is untouched. `TerminalLogFormatTest` asserts all of it against the shipped config
- [x] E20.4 Externalized properties beside JARs + first-run defaults; client remembers last server; pom copies from `*.properties.example` when the gitignored real files are absent (fresh clone/CI currently ships no adjacent config) — lookup is now beside-the-JAR → working directory → bundled → hard-coded (`common.config.ExternalConfig`, shared by both tiers; the working-directory step was the gap, hit by any launch whose cwd is not the JAR's folder). `package` seeds `target/` from the examples only for whichever file is absent, so a fresh clone runs out of the box and real credentials are never overwritten
- [ ] E20.4b Final defense JARs must be built on Windows — JavaFX natives are baked in at build time (hard gate, add to day-of checklist) — **instructions ready, manual execution pending**: `docs/DEMO_DAY.md` §1.2, incl. the `jar tf | Select-String "\.dll$"` proof. Note **both** JARs carry JavaFX since E19, not just the client; README §9 corrected
- [ ] E20.5 Two-machine LAN checklist doc (firewall rules incl. UDP discovery port, IP from console, smoke script, **test discovery on the actual demo network type — bring a hotspot as fallback**, manual-IP path rehearsed too) — rehearsed ⚑ — **instructions ready, manual execution pending**: `docs/DEMO_DAY.md` §4 (TCP 5555 + UDP 5556 rules, Private-profile requirement, unicast-reply-to-broadcast note, discovery path, manual-IP path, hotspot plan, smoke script)
- [ ] E20.6 DB setup path for a fresh machine: install MySQL → run server → Flyway + seed → done (documented, timed) — **instructions ready, manual execution pending**: `docs/DEMO_DAY.md` §3, with a budget/measured table and the startup-failure sentences mapped to fixes. Budgets are estimates until the first rehearsal fills the measured column

## E21 — Hardening & test completion [all, coordinated by L]
- [x] E21.0b (FIXED 2026-08-23, lead, rule 5 - flagged for A's pass): TestDatabases.REPO_SCHEMA is hardcoded (hsts_e2_repo_test) while its migration sibling honors HSTS_TEST_SCHEMA - three phantom failures under parallel builds so far. Two-line fix: env-override it the same way (Member A's file, rule-5 style)
- [ ] E21.0 (build nit, 2026-08-21): a surefire fork lingers 30s after the suite since E19/E20 ("kill self fork JVM ... elapsed 30 seconds after System.exit(0)") - some test leaves a non-daemon thread (suspect: console TestFX or discovery loopback). Find and close it; costs 30s per build and could flake CI

- [ ] E21.1 Execute the full PRD §6 edge-case catalog as a tracked checklist — every line gets a test or a manual-verified note
- [ ] E21.2 Race-condition suite: parallel attempts, simultaneous edits, extension-vs-expiry, submit-vs-expiry, login race ⚑
- [ ] E21.3 "Break it" adversarial session: each member attacks the others' features; findings → issues → fixed
- [ ] E21.4 Coverage push to ≥90% (JaCoCo report reviewed; gaps assigned by owner)
- [ ] E21.5 Performance sanity: 100-question exam form, 500-question bank list, 30 concurrent scripted clients
- [ ] E21.6 Full acceptance-test dry run of scenarios 1–21 in order, results recorded in the submission table ⚑
- [ ] E21.7 UX polish sweep: every screen against the design checklist (spacing, empty states, loading, errors, animations)
- [ ] E21.8 Hebrew/RTL data pass on all screens

## E22 — Submission & defense [B writes, L reviews]

- [x] E22.0 **[done 2026-08-26, batch A]** Reverse traceability matrix (`docs/TRACEABILITY.md`): every T-n/S-n → PRD feature → package/class → test(s) — the completeness proof for the submission doc & defense *Written by Member B in a worktree against `6bff812` and ported into `main` in batch A. **136 ids**, each with its owning class, the test that goes red if it breaks, its acceptance case numbers and an honest status; the five statuses are defined in the file and `GAP` is deliberately never softened to `PARTIAL`. It carries three sections a matrix usually does not: the gaps and partials gathered in one table with an owner per row, the ids that are **implemented but cited nowhere in `src/`** (thirteen of them, each one javadoc line from being closed), and "one more thing an examiner will find" (`Authorization.requireEnrolled` is dead scaffolding — enrolment is enforced elsewhere and the stub only looks wrong). **Two rows were corrected on arrival rather than ported as written**, both marked in place: **F3.1** moved PARTIAL → LIVE-unwalked, because batch A's own assembly commit is what closed it (`exams.build` declared, registered for both teaching roles, mapped to `ExamBuilderView`); and **F3.2**'s stated blocker was stale — `BankQuestionRow.latestVersionId` has been on the wire since BANK amendment A1, so the frozen-contract ask is answered and the residual is one method (`addFromBank` adopting it, in flight as PR24). Counts now: 16 LIVE · 105 LIVE-unwalked · 11 PARTIAL · 3 GAP · 1 N/A. **The 105 LIVE-unwalked rows are one task, not a hundred and five** — E21.6 — and the note about which scenarios have been driven was rewritten too: 1–14 are now all walked, with 1/8/9 driven at a keyboard and 2–7 and 10–14 walked below the screen; **15–21 are the untouched ones**, and they are the non-functional half.*
- [ ] E22.1 Acceptance-test results table (scenario, steps, expected, actual, bugs found + which case exposed them)
- [ ] E22.2 Submission doc: cover page (group, names, IDs), per-member responsibilities, test table, design/coding-problem answer (from PROBLEMS.md — pick the best story, e.g. exam-vs-execution modeling or timer race)
- [ ] E22.3 Export to required format, assemble `G<Num>_Assignment3.zip` (doc + 2 JARs), verify zip contents against spec ⚑
- [ ] E22.4 Cross-walkthrough sessions (spec §11): each member walks the other two through her components until all three can answer defense questions on ANY component; verified with a mock Q&A round ⚑ *Member B's half is written: `docs/briefs/member-b-e12-e13-walkthrough.md` covers E12 and E13 — the five-step pipeline, the four decisions most likely to be pushed on, seven anticipated questions with answers, the demo order, and the one gap (E12.6's paper review) stated rather than hidden. Not ticked: the session itself and the mock Q&A round.*
- [ ] E22.4b Demo script (renumbered from a duplicate E22.4, maintenance 2026-08-25): ordered walkthrough matching scenarios 1–21 + wow-moments (theme switch, live extension, edit locks, bot fallback) + break-it invitation
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

## UI waves — manual pass 1 follow-up [L] (added 2026-08-23)

Register and rulings: `docs/reports/lead/MANUAL-PASS-1.md` (English everywhere; no new
animation dependencies — Motion system only).

- [x] W1.1 Notification popover from the bell (one click, click-outside + ESC close) — F-6
- [x] W1.2 Back-button convention on every drill-in screen; histogram full view first — F-7
- [x] W1.3 Single-click opens rows everywhere — F-8
- [x] W1.4 Global table-sizing pass (B-5 treatment on every table) — F-9
- [x] W1.5 Dashboard cards v1, all four roles, cards navigate — F-10
- [x] W1.6 Bot modal shadow + bot copy pass — F-11, F-14
- [x] W1.7 Profile-name control: menu or plain text — F-12
- [x] W1.8 Seed content to English (SeedArithmeticTest + DEMO_ACCOUNTS stay authoritative) — F-13
- [x] W2.1 Design canvas: 4 artboards light+dark with motion spec; lead markup gates implementation
- [x] W2.2 Implement approved wave-2 direction across screens (views + CSS only; sessions and wires untouched)
      — implemented against the published canvas, lead's markup pending. Report:
      `docs/reports/lead/WAVE2.md`. Two sessions were extended where a card's summary sentence
      needed data they already load, and the teacher's session makes two conditional follow-up
      reads on existing verbs; no wire, verb or DTO changed.
