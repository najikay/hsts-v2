# HSTS v2 — Architecture

Technical source of truth. Requirement IDs reference [PRD.md](PRD.md).

---

## 1. System overview

```
┌────────────── Client JAR (JavaFX 21) ──────────────┐      ┌────────────── Server JAR ──────────────┐
│ Screens (FXML+controller)                          │      │ HSTSServer (OCSF AbstractServer)       │
│   └─ Session classes (testable, no FX)             │ TCP  │   └─ MessageRouter (verb → handler)    │
│ ClientEventBus  ◄─ ServerMessageEvent (FX thread)  │◄────►│ Services (auth'd business logic)       │
│ IClientConnection (Adapter) → HSTSClient (OCSF)    │      │ Repositories (Hibernate/JPA)           │
│ ThemeManager · ScreenManager · Animations          │      │ SessionManager · EditLockService       │
└────────────────────────────────────────────────────┘      │ NotificationService (push)             │
                                                            │ TimerService (executions, auto-submit) │
                 common/ (shared wire model)                │ BotService → BotProvider chain         │
     Message v2 · DTOs · enums · validation constants       │ Server Console (JavaFX, optional)      │
                                                            └───────────────┬────────────────────────┘
                                                                            │ HikariCP
                                                                     MySQL 8 (Flyway-migrated)
                                                            External: DeepSeek API / Anthropic API
```

Thin client / fat server is preserved: the client holds no business rules, no DB credentials, no correctness data it shouldn't see.

---

## 2. Package layout (feature-based)

```
src/main/java/
├── common/
│   ├── protocol/            Message, RequestId, ErrorCode, Verb (enum)
│   ├── dto/<feature>/       serializable DTOs per feature (auth, bank, exam, release,
│   │                        attempt, grading, results, reports, bot, notify, lock)
│   └── util/                shared pure helpers (ids, time, validation)
├── client/
│   ├── core/                ClientApp, Launcher, ScreenManager, Navigator, ClientConfig
│   ├── net/                 IClientConnection, HSTSClient, FakeClientConnection, RequestDispatcher
│   ├── events/              ClientEventBus, typed event classes
│   ├── ui/                  design system: components/, theme/ (ThemeManager, palettes), anim/
│   └── features/<feature>/  XView.fxml + XController + XSession (logic, unit-tested)
├── server/
│   ├── core/                ServerMain, HSTSServer, MessageRouter, SessionManager, ServerConfig
│   ├── db/                  HibernateUtil, Flyway bootstrap, entities/ (JPA), repos/
│   ├── features/<feature>/  XService (+ validators, helpers)
│   ├── realtime/            NotificationService, EditLockService, TimerService, PushGateway
│   │                        (E16 note: the bot landed in features/bot rather than a top-level
│   │                        bot/ package — it is a feature like any other, and the layout rule
│   │                        below is what keeps its isolation checkable)
│   ├── console/             server console UI + NetworkDetector (E19)
│   └── discovery/           UDP discovery responder + ServerFingerprint (E19.8/E19.9)
└── ocsf/                    vendored, untouched, excluded from coverage
```

Rule: a feature's client, server, and DTO code never reaches into another feature's internals — shared things live in `core`/`ui`/`realtime` or `common`.

---

## 3. Protocol v2

`Message` stays the single serializable envelope, upgraded:

```java
class Message implements Serializable {
    Verb verb;            // enum, one per operation (LOGIN, GET_QUESTIONS, SAVE_ANSWER, ...)
    String requestId;     // UUID; responses echo it → client correlates request/response
    Object payload;       // typed DTO from common/dto (never raw maps)
    Status status;        // REQUEST | OK | ERROR | PUSH
    ErrorCode errorCode;  // machine-readable on ERROR (+ human message in payload)
}
```

- **Request/response:** client `RequestDispatcher.send(verb, dto)` returns a `CompletableFuture<Response>` matched by `requestId` (with timeout → UI error state). No screen touches sockets.
- **Push channel:** server-initiated `PUSH` messages (NOTIFICATION, LOCK_CHANGED, TIMER_EXTENDED, EXECUTION_STATUS, GRADE_PUBLISHED, FORCE_SUBMITTED, ...) delivered via `PushGateway.toUser/toUsers/toRole/toCourse` using `SessionManager`'s user↔connection map. Client turns them into EventBus events on the FX thread. Unknown verbs are ignored safely (forward compatibility).
- **Security:** router authenticates every message (except LOGIN/CONNECT), resolves the caller from the connection (never trusts a user id in the payload), then delegates to a service that runs `Authorization.require*` guards. Unknown/malformed → ERROR, logged, connection kept.

## 4. Server internals

- **SessionManager:** connection ↔ authenticated user; enforces single login (T-16); on disconnect fires cleanup hooks (release edit locks, mark attempt connection-lost but keep timer running).
- **EditLockService:** in-memory `ConcurrentHashMap<EntityKey, Lock{userId, expiresAt}>`; acquire/renew(heartbeat 15s, TTL 45s)/release; every change pushed to interested clients. Backstop: JPA `@Version` optimistic locking on entities → stale writes rejected with `ErrorCode.CONFLICT`.
- **TimerService:** single `ScheduledExecutorService`; one task per live attempt end-time + per execution close-time. Extension reschedules tasks and pushes new deadlines. Expiry: transactionally force-submit (persist `TIMED_OUT`, compute auto-grade, push FORCE_SUBMITTED). Server restart re-arms timers from DB on boot.
- **Services:** constructor-injected repositories + collaborators → unit-testable with Mockito without sockets. One service per feature; validators (Strategy) shared between add/update paths.
- **Statistics:** computed on grading approval completion, stored per execution (S-25); report engine reads stored rows.

## 5. Database schema (Flyway `V1__...` → `Vn__...`)

```
subjects(code2 PK, name)                          -- seeded, read-only (S-3)
courses(code2 PK, subject_code FK, name)          -- seeded, read-only
users(id PK, username UQ, password_hash, full_name, role ENUM, national_id UQ)
   -- national_id unique: S-18 starts an attempt by it; two students sharing one is ambiguous
course_teachers(course, teacher)  |  enrollments(course, student)
coordinators(subject_code, teacher)               -- coordinator per subject (S-1)

questions(id PK, course, serial3, display_id5 UQ) -- identity row
question_versions(id PK, question_id FK, version_no, text, a1..a4,
                  correct_answer TINYINT (1..4), topic, difficulty ENUM, image MEDIUMBLOB NULL,
                  created_by, created_at)         -- immutable versions (C-2)
   -- questions carries deleted_at DATETIME(3) NULL (F2.5 soft delete; hard delete never happens
   --  — enforced: question_versions->questions is RESTRICT, so no question is ever physically removable)

exams(id PK, course, serial2, display_id6 UQ, author FK)
exam_versions(id PK, exam_id FK, version_no, name, duration_min,
              student_text NULL, teacher_text NULL,  -- optional per T-3, deliberately nullable status ENUM(DRAFT,PENDING,APPROVED,REJECTED),
              rejected_reason NULL, created_at)
exam_version_questions(exam_version_id, question_id, question_version_id, points, ord,
                       UNIQUE(exam_version_id, question_id))
   -- question_id denormalized so the DB itself forbids the same question appearing twice
   -- via different versions (PRD §6); sum(points)=100 enforced in service + tests

exam_executions(id PK, exam_version_id FK, code CHAR4, open_at, close_at,
                extra_minutes INT DEFAULT 0, status ENUM(SCHEDULED,LIVE,CLOSED,CANCELLED),
                -- CANCELLED = F5.5 cancel-before-open; excluded from statistics/reports.
                -- code uniqueness among non-CLOSED executions is a SERVICE rule (no partial
                -- indexes in MySQL) enforced in E9 + tested,
                created_by, stats: avg, median, stddev, min, max, deciles,
                participation {started, finished, timed_out} JSON NULL)   -- S-2, S-21, S-25
   -- participation counts are DERIVED from exam_attempts (COUNT by status) while live —
   -- no mutable counters, no increment races — and frozen into stats JSON at close (S-21)

exam_attempts(id PK, execution_id FK, student_id FK, started_at, ended_at,
              actual_minutes, status ENUM(IN_PROGRESS,SUBMITTED,TIMED_OUT),
              UNIQUE(execution_id, student_id))
attempt_answers(attempt_id, question_version_id, selected TINYINT NULL, saved_at)

grades(id PK, attempt_id UQ FK, auto_score, final_score, status ENUM(AUTO,APPROVED),
       override_reason NULL, teacher_comment NULL, approved_by, approved_at)

bots(id PK, course UQ, name, active BOOL)                          -- one per course (S-30)
bot_sources(id PK, bot_id FK, type ENUM(PDF,DOCX,TEXT), title, raw MEDIUMBLOB NOT NULL,
            extracted_text MEDIUMTEXT NOT NULL, added_by, updated_at, version INT)
   -- NOT NULL by design: a source row only exists after successful extraction (F12.2) —
   -- a silently-empty source that contributes nothing to the prompt cannot exist
bot_sessions(id PK, bot_id FK, student_id FK, started_at, updated_at,
             transcript JSON)                                       -- [{role,q/a,ts}] (S-33)
bot_messages(id PK, bot_id FK, session_id FK, student_id FK, question, answer,
             provider, asked_at)   -- dual-written with the transcript in the same tx;
                                   -- analytics/aggregates query THIS, never the JSON.
                                   -- student_id is internal only — S-34 DTOs carry no identity

notifications(id PK, user_id FK, type, title, body, ref_type, ref_id,
              created_at, read_at NULL)
```

**Schema conventions (locked in E2 PR1 review):** optimistic-locking column is `lock_version INT NOT NULL DEFAULT 0` (never confused with domain `version_no`) on questions, exams, **exam_versions** (its `status` is mutable — approve/reject race lands there), exam_executions, bot_sources, grades. Deletion policy: RESTRICT everywhere history must survive — attempts/grades from executions, and **bot_sessions/bot_messages from bots** (deleting a bot must not wipe the analytics corpus; bots are toggled, not deleted). All DATETIME values are **UTC**; clients render local. `stats`/`participation` are two JSON columns. H2 tests validate *mappings* (Hibernate schema-gen); only the MySQL suite validates the real Flyway schema.

**Round-2 schema decisions (E2 PR1 final):** the denormalized `exam_version_questions.question_id` is policed by a **composite FK** `(question_version_id, question_id) → question_versions(id, question_id)` — the copy cannot disagree with its source. `raw`/`extracted_text` carry `CHECK(LENGTH > 0)` backstops; for `type='TEXT'` sources the service stores the pasted text as `raw` too (it IS the original — duplication is negligible for pasted text). `bot_messages→bot_sessions` is RESTRICT as well (nothing in the product deletes a session). `uq_exam_version_questions_ord` stays: E7 composition updates are **full-replace within one transaction** (delete rows + reinsert), so no reorder dance is ever needed. **Attempt finalization is a status-guarded atomic UPDATE** (`... SET status='SUBMITTED' WHERE id=? AND status='IN_PROGRESS'`) — the submit-vs-expiry race is resolved by compare-and-set on the state machine, not by a lock_version on attempts. **Stored role has 3 values** (`STUDENT,TEACHER,PRINCIPAL`); the wire `Role.COORDINATOR` is **derived at login**: stored TEACHER + a `coordinators` row → wire COORDINATOR (coordinator-ness is per-subject state, never stored as a role — it cannot drift). Allocators use `MAX(serial)+1`, never COUNT+1 (soft-deleted questions keep their serial); adding a soft-deleted question to a new exam version is a service-rule rejection (E7 validator). Seed/test wipes DELETE in reverse-dependency order; if `FOREIGN_KEY_CHECKS=0` is used around deletes it MUST be re-enabled before inserts (or the composite FK is inert for seeded data). Requires **MySQL ≥ 8.0.16** (older versions silently ignore CHECK constraints).

All tables utf8mb4; entities carry `@Version` (column `lock_version`) where editable. Correct answers live only in `question_versions` — the take-exam DTO mapper cannot even see `correct_answer` (separate projection), making the v1 "student sees answers" leak structurally impossible.

## 6. Client internals

- **ScreenManager** (Singleton) owns the Stage + shell; `Navigator` routes by screen id with typed params; screens load FXML via a `ScreenFactory` cache; register/unregister on the EventBus automatically (Template Method in `AbstractScreen`).
- **Session classes** hold all screen logic/state and speak only to `IClientConnection` + EventBus → fully unit-testable with `FakeClientConnection` (this is how client coverage reaches 90% without TestFX on everything).
- **ThemeManager:** applies AtlantaFX base (light/dark) + injects the selected accent palette stylesheet; persists choice to the local config file; broadcasts `ThemeChangedEvent` for custom-drawn nodes (charts).
- **Threading rule:** exactly one crossing point — network callbacks wrap into `Platform.runLater` before posting events. Long client work (image decode) on a small executor with progress UI.

## 7. Bot pipeline

```
student msg ─► BotService.ask(course, student, sessionId, text)
   guards: enrolled? bot active? student not mid-exam? rate limit (per-student cooldown)
   context: ContextBuilder → system prompt (guardrails) + course source chunks
            (simple keyword/overlap scoring over extracted_text, top-k within token budget)
            + relevant bank questions, WITHOUT correctness data (ADR-020).
            NO exam data exists in this module's reach - BotIsolationGuardTest scans the
            compiled feature package and fails on any reference to the exam or grading
            repositories, entities or projections (F12.8).
   provider chain: DeepSeekProvider (java.net.http, OpenAI-compatible /chat/completions,
                   timeout 20s, exactly 1 retry on timeout/5xx) → AnthropicProvider (official
                   anthropic-java SDK, model claude-opus-5, configurable) → NoAnswer (S-32).
                   A provider that fails is benched for 60s (Clock-injected) and skipped;
                   one structured log line per ask records which provider answered and how long
                   it took.
   persist: append {q, a, provider, ts} to bot_sessions.transcript JSON
            + insert bot_messages row (same tx) — the analytics-facing copy
   respond: answer DTO (+ "degraded" flag logged, not shown)
```

Keys from env / `server.properties` (`bot.deepseek.key`, `bot.anthropic.key`); `server.properties` is gitignored with a committed `server.properties.example`.

## 8. Server console & networking

`ServerMain`: parse args (`ServerArgs`: `--headless`, `--port`, `--discovery-port`, `--no-discovery`;
a bare numeric argument is still the port, as the pre-E19 server accepted) → load config → Flyway
migrate → construct `HSTSServer` (**that order is a rule**, see the class javadoc: the constructor
opens the pool) → start the OCSF listener → load or generate this installation's discovery id →
start the UDP responder → launch the console UI unless `--headless`. A migration failure is one
sentence naming the next step (`StartupMessages`), then the trace.

**Console (E19.1–E19.7, F13.1/F13.2).** A JavaFX window in the server JAR, dark by default and
styled from the same `hsts.css` token layer as the client. It shows the big `<ip>:<port>` with
copy-to-clipboard, the discovery id beside it, a manual address override (detected addresses plus
free text), a start/stop listener button, four status cards (DB pool via a `SELECT 1` probe,
connected clients, JVM heap, bot provider health read from `ProviderChain`'s bench memory), a live
connected-clients table, a log tail over an in-memory ring buffer, and the seed buttons.

The split is the point: **everything with a rule in it is FX-free and measured** — `NetworkDetector`
(address ranking), `ConsoleModel` (header, override, button labels), `ConsoleSession` (what every
button does and answers), `ConsoleHealth`/`HealthSnapshot` (cards), `ConsoleClients` (table rows),
`LogTailModel`/`LogRingBuffer` (the pane). `ConsoleView`, `ConsoleTheme` and `ServerConsoleApp` are
thin and excluded by name from the coverage gate, and are additionally driven by a real-input TestFX
test.

*Start/stop semantics:* stop closes the **listener**, not the process. Existing clients stay
connected, timers keep running and exams in progress are unaffected; what stops is new connections
being accepted. The console says so on screen.

*Log tail:* a `RingBufferAppender` declared in `logback.xml` rather than attached from Java, so it
is already capturing before the first line of `ServerMain` runs. Bounded at 2000 lines. Pause
freezes the **view**, never the capture, so the evidence the operator paused to read is not the
evidence pausing destroys.

*Seed button (E19.6):* calls `SeedLoader.standard(factory).load(mode, confirmation)` and renders the
returned `SeedSummary.toText()`. The prompt shown in the confirm dialog is the **loader's own**, so
the console and the command line cannot describe the same destructive action differently.
`LOAD_IF_MISSING` never prompts, because it destroys nothing.

**Discovery (E19.8–E19.11, F13.3/F13.4).** A UDP responder on its own port (default 5556,
configurable) answers a fixed magic request with a compact JSON `{name, ip, port, fingerprint}`.
JSON rather than a serialized `Message` deliberately: this is the one socket unauthenticated
strangers can write to, and Java deserialization of such a payload is not a risk worth taking.
Everything that is not the magic string draws no reply at all, and replies are rate limited per
source address, so the responder cannot be used as an amplifier. The fingerprint is a random UUID
generated on first boot and persisted in `server-id.properties` beside `server.properties`.

> **Honest security claim, and the defence wording:** the fingerprint provides **disambiguation and
> change detection, not impersonation resistance.** It travels in cleartext to anyone who asks, so
> anyone who has heard it can repeat it. What it genuinely buys is that two servers in one room are
> told apart, and that a client which finds a *different* id at an address it used yesterday has
> learned something real and stops to ask. Cryptographic binding means the id becoming a TLS
> certificate fingerprint, which is E19.12's gated decision (ADR-019) and changes no UX.

Client side: broadcast, collect for ~2s, then `ConnectFlow` decides. With a pinned server that
answers with its own id, or with a pin and no answers at all (broadcast filtering is normal on
school networks), the client connects silently and **the first screen is Login** carrying a subtle
"Connected to &lt;server&gt; · change server" line (E19.11). The host/port editor appears only when
nothing is pinned and nothing was found, when the pinned server could not be reached, or when the
user clicks "change server". A pinned address answering with a different id raises a prominent
confirm before any socket opens, and re-pins on accept. Pin keys in `connect.properties` are
**additive**, so an older client file still loads.

Logging: SLF4J + Logback, colorized console pattern for the terminal demo, plus the ring appender.

## 9. Build & packaging

- Single Maven module (simple for the course), Java 21, JavaFX 21 (`javafx-controls`, `javafx-fxml`).
- Shade: `G<Num>_Server.jar` (main `server.core.ServerMain`, includes MySQL/Hibernate/Flyway/bot deps, excludes JavaFX? — **no**: server console needs JavaFX; include it) and `G<Num>_Client.jar` (main `client.core.Launcher`, excludes DB/Hibernate/bot/server code via shade filters).
- Non-`Application` launcher classes so shaded JavaFX runs by double-click.
- Build on Windows (JavaFX natives match the demo machines).

## 10. Phase-2 readiness (course spec §10)

The course spec requires phase 1 (LAN, non-web UI) to be designed so the phase-2 move (access
from anywhere over the internet) is efficient and smooth. That readiness is not a promise, it is
the location of four seams that already exist:

- **Transport is one adapter.** The client talks through `IClientConnection`; OCSF is vendored
  behind it. Phase 2 swaps the implementation (WebSocket/TLS gateway) without touching a screen,
  a session class, or a handler — the protocol v2 envelope (verb + requestId + status + payload)
  is transport-agnostic by construction.
- **The server is the whole brain.** Every rule (auth, roles, timers, grading, locks, bot) runs
  server-side; clients render state they are pushed or answer. A thinner phase-2 client (or a web
  one) reuses the entire Logic tier unchanged — this is also why the fat-server architecture was
  chosen in phase 1 (ADR-002).
- **Security boundary is one place.** Identity comes from the socket-bound session
  (`SessionManager`), never the payload, and every verb carries its role gate (P-5 rule). Adding
  internet exposure means adding TLS and hardening at the transport edge, not re-auditing feature
  code.
- **Statelessness where it matters.** Client state is rebuildable from `LoginResult` + queries;
  reconnection logic (E3) already treats the link as unreliable, which is the phase-2 network
  assumption arriving early.

What phase 2 would add: TLS on the transport, an internet-facing gateway/reverse proxy, and
hardened rate limiting. None of it changes the tiers above the socket.

## 11. Testing & CI

| Level | Tooling | Target |
|---|---|---|
| Unit — server services/validators/utils | JUnit 5 + Mockito + AssertJ | branch-level, every rule in PRD |
| Unit — client sessions | FakeClientConnection, no FX toolkit | every screen's logic |
| Repository | H2 (MySQL mode) fast suite + real MySQL suite | every query |
| Integration | boot HSTSServer on a random port + real MySQL, scripted clients | every protocol verb; concurrency scenarios (double login, lock races, timer expiry, parallel attempts) |
| UI smoke | TestFX (headless Monocle) | login→home per role + take-exam happy path only |
| Coverage | JaCoCo, gate ≥ 90% instruction, excludes `ocsf/**`, `**/*View.fxml` glue | enforced in `mvn verify` + CI |

GitHub Actions (`ci.yml`): on push/PR → JDK 21 → `mvn verify` with MySQL 8 service container → upload JaCoCo report + both JARs as artifacts. Badge in README.
