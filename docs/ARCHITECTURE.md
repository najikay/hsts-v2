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
│   ├── bot/                 BotService, BotProvider, DeepSeekProvider, AnthropicProvider,
│   │                        SourceExtractor (pdf/docx/text), ContextBuilder, Guardrails
│   └── console/             server console UI + NetworkDetector
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
users(id PK, username UQ, password_hash, full_name, role ENUM, national_id)
course_teachers(course, teacher)  |  enrollments(course, student)
coordinators(subject_code, teacher)               -- coordinator per subject (S-1)

questions(id PK, course, serial3, display_id5 UQ) -- identity row
question_versions(id PK, question_id FK, version_no, text, a1..a4,
                  correct_answer TINYINT (1..4), topic, difficulty ENUM, image BLOB NULL,
                  created_by, created_at)         -- immutable versions (C-2)

exams(id PK, course, serial2, display_id6 UQ, author FK)
exam_versions(id PK, exam_id FK, version_no, name, duration_min,
              student_text, teacher_text, status ENUM(DRAFT,PENDING,APPROVED,REJECTED),
              rejected_reason NULL, created_at)
exam_version_questions(exam_version_id, question_version_id, points, ord)
   -- sum(points)=100 enforced in service + CHECK-style assertion in tests

exam_executions(id PK, exam_version_id FK, code CHAR4, open_at, close_at,
                extra_minutes INT DEFAULT 0, status ENUM(SCHEDULED,LIVE,CLOSED),
                created_by, stats: avg, median, stddev, min, max, deciles JSON NULL,
                started_count, finished_count, timed_out_count)    -- S-2, S-21, S-25

exam_attempts(id PK, execution_id FK, student_id FK, started_at, ended_at,
              actual_minutes, status ENUM(IN_PROGRESS,SUBMITTED,TIMED_OUT),
              UNIQUE(execution_id, student_id))
attempt_answers(attempt_id, question_version_id, selected TINYINT NULL, saved_at)

grades(id PK, attempt_id UQ FK, auto_score, final_score, status ENUM(AUTO,APPROVED),
       override_reason NULL, teacher_comment NULL, approved_by, approved_at)

bots(id PK, course UQ, name, active BOOL)                          -- one per course (S-30)
bot_sources(id PK, bot_id FK, type ENUM(PDF,DOCX,TEXT), title, raw BLOB,
            extracted_text MEDIUMTEXT, added_by, updated_at, version INT)
bot_sessions(id PK, bot_id FK, student_id FK, started_at, updated_at,
             transcript JSON)                                       -- [{role,q/a,ts}] (S-33)

notifications(id PK, user_id FK, type, title, body, ref_type, ref_id,
              created_at, read_at NULL)
```

All tables utf8mb4; entities carry `@Version` where editable. Correct answers live only in `question_versions` — the take-exam DTO mapper cannot even see `correct_answer` (separate projection), making the v1 "student sees answers" leak structurally impossible.

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
            + relevant bank questions. NO exam data exists in this module's reach.
   provider chain: DeepSeekProvider (java.net.http, OpenAI-compatible /chat/completions,
                   timeout 30s, 1 retry) → AnthropicProvider (official anthropic-java SDK,
                   model claude-opus-5, configurable) → NoAnswer (S-32 message)
   persist: append {q, a, provider, ts} to bot_sessions.transcript JSON
   respond: answer DTO (+ "degraded" flag logged, not shown)
```

Keys from env / `server.properties` (`bot.deepseek.key`, `bot.anthropic.key`); `server.properties` is gitignored with a committed `server.properties.example`.

## 8. Server console & networking

`ServerMain`: parse args (`--headless`, `--port`) → load config → Flyway migrate → detect LAN IPs (`NetworkInterface.getNetworkInterfaces()`, filter up/non-loopback/site-local IPv4; prefer the one with a default-route hint) → start OCSF listener → launch console UI unless headless. Console shows the big "connect clients to <ip>:<port>", live client table, log tail (in-memory ring buffer appender), start/stop, and health cards. Logging: SLF4J + Logback, console pattern colorized for the terminal demo, rolling file beside the JAR.

## 9. Build & packaging

- Single Maven module (simple for the course), Java 21, JavaFX 21 (`javafx-controls`, `javafx-fxml`).
- Shade: `G<Num>_Server.jar` (main `server.core.ServerMain`, includes MySQL/Hibernate/Flyway/bot deps, excludes JavaFX? — **no**: server console needs JavaFX; include it) and `G<Num>_Client.jar` (main `client.core.Launcher`, excludes DB/Hibernate/bot/server code via shade filters).
- Non-`Application` launcher classes so shaded JavaFX runs by double-click.
- Build on Windows (JavaFX natives match the demo machines).

## 10. Testing & CI

| Level | Tooling | Target |
|---|---|---|
| Unit — server services/validators/utils | JUnit 5 + Mockito + AssertJ | branch-level, every rule in PRD |
| Unit — client sessions | FakeClientConnection, no FX toolkit | every screen's logic |
| Repository | H2 (MySQL mode) fast suite + real MySQL suite | every query |
| Integration | boot HSTSServer on a random port + real MySQL, scripted clients | every protocol verb; concurrency scenarios (double login, lock races, timer expiry, parallel attempts) |
| UI smoke | TestFX (headless Monocle) | login→home per role + take-exam happy path only |
| Coverage | JaCoCo, gate ≥ 90% instruction, excludes `ocsf/**`, `**/*View.fxml` glue | enforced in `mvn verify` + CI |

GitHub Actions (`ci.yml`): on push/PR → JDK 21 → `mvn verify` with MySQL 8 service container → upload JaCoCo report + both JARs as artifacts. Badge in README.
