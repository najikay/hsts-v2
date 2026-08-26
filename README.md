# HSTS — High School Test System

A monolithic **3-tier desktop application** on the **Thin Client / Fat Server** paradigm:
JavaFX desktop clients that only render and ask, one fat server owning every rule, and MySQL
behind it. Teachers build a versioned question bank and compose exams (manually or
auto-generated from criteria), a subject coordinator approves them, teachers release them
under 4-character codes, students sit them under a server-owned timer, grading is automatic
with teacher review and audited overrides, and every role gets exactly the statistics it is
allowed to see. An AI study bot answers course questions from teacher-uploaded material.
Everything runs over a local network.

**Stack:** Java 21 · JavaFX 21 + AtlantaFX (design tokens, 5 accent palettes, light/dark/system)
· vendored OCSF behind an adapter (networking) · protocol v2 (request correlation + server push)
· Hibernate 6.6 + HikariCP + Flyway (data) · BCrypt (passwords) · greenrobot EventBus (client)
· DeepSeek + Anthropic SDK (bot) · JUnit 5 / Mockito / AssertJ / TestFX (tests) · Maven +
shade (two deployable Fat JARs).

### Tiers

| Tier | Responsibility | Key types |
|------|---------------|-----------|
| **Presentation** | Render UI, capture intent; no rules | `client.core.*` (Navigator, ScreenManager, Routes), `client.ui.*` (design system), `client.features.*` (screen + FX-free session per feature), `client.net.*` |
| **Logic (Fat Server)** | Route verbs to handlers, enforce every rule, own time | `server.core.{HSTSServer, MessageRouter, SessionManager}`, `server.features.*` (auth, bank, exambuild, approval, release, exam, grading, results, reports, bot, notify, locks) |
| **Data** | Persistence: entities, repositories, projections, migrations | `server.db.*` — Hibernate entities, `server.db.repos.*` (one query per need), Flyway `V1..V7` |
| **Common** | Shared wire types only | `common.protocol.{Message, Verb}`, `common.dto.*` (Serializable records; student-facing shapes structurally cannot carry answer keys) |

---

## 2. Quick Start

**Prerequisites:** Java 17+, MySQL, Maven.

```bash
# 1. Create an empty database (one-time; the server runs Flyway migrations on start)
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS hsts_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

# 2. Build both Fat JARs + copy deployment properties into target/
mvn clean package
```

Build using the wrapper (Windows):

```powershell
.\mvnw clean package
```

After the build, `target/` contains:

| Artifact | Purpose |
|----------|---------|
| `hsts-server.jar` | Fat Server — OCSF listener + MySQL access |
| `hsts-client.jar` | JavaFX Thin Client |
| `server.properties` | DB credentials for the server |
| `client.properties` | Server host/port for the client |

The two properties files are the machine's real ones when the project root has them,
and copies of `server.properties.example` / `client.properties.example` when it does
not, so a fresh clone's `target/` runs without hand-copying anything. An existing
file in `target/` is never overwritten by the examples.

**Submission build (E20.1).** The JAR names carry the group number, and the group
number is passed on the command line rather than committed:

```powershell
.\mvnw -Djar.prefix=G12-1 package     # -> target\G12-1_Server.jar + target\G12-1_Client.jar
```

Without the switch the build keeps the `hsts-server.jar` / `hsts-client.jar` names
this README and every script use. Substitute the real group number for `G12-1`, and
build the submission JARs **on Windows** (see §9, Platform note).

Edit the properties files to match your environment, then launch **server first,
client second** (separate processes — the intended deployment model):

```bash
# Terminal 1 — start the Fat Server (opens the server console window)
java -jar target/hsts-server.jar

# ...or terminal-only, exactly as it ran before E19:
java -jar target/hsts-server.jar --headless

# Terminal 2 — start the JavaFX client
java -jar target/hsts-client.jar
```

**Server switches (E19):**

| Switch | Effect |
|---|---|
| `--headless` | run terminal-only, open no console window |
| `--port 5555` | the OCSF port clients connect to (a bare number still works too) |
| `--discovery-port 5556` | the UDP port the discovery responder answers on |
| `--no-discovery` | start with the discovery responder off |

The **server console** shows the address to point clients at, big enough to read from
the back of a room, with the server's discovery id beside it. Start/stop the listener,
watch connected clients and the live log, check the database and bot-provider health,
and load or reload the demo dataset from there.

The **client normally never asks for an address.** It broadcasts, finds the server and
goes straight to Login with a "Connected to &lt;server&gt; · change server" line. The
host/port form appears only when nothing is found, when the remembered server cannot be
reached, or when you click "change server".

### Configuration

Both JARs look for their properties file in this order (E20.4):

1. **beside the JAR** — the deployment layout, and the one that wins when both exist;
2. **the working directory** — the same file name where the process was started, which
   is what a shortcut or a `java -jar C:\hsts\G12-1_Server.jar` typed from elsewhere hits,
   and the only external candidate when running from the IDE;
3. **bundled defaults** inside the JAR;
4. **hard-coded fallbacks** (`root`/`root`, `localhost:5555`).

**`server.properties`** (beside `hsts-server.jar`):

```properties
db.user=root
db.password=root
```

**`client.properties`** (beside `hsts-client.jar`):

```properties
server.host=localhost
server.port=5555
```


---

## 3. User Interface

Every screen splits into a thin **view** (nodes only) and a toolkit-free **session** class
holding all decisions, which is what makes UI behaviour unit-testable without booting JavaFX.
The design system (`client.ui`) carries the tokens: 5 accent palettes, light/dark/system modes
switchable live, motion through one budgeted `Motion` system (reduced-motion collapses every
gesture to a plain fade; the take-exam screen gets no entrance motion at all, by rule).

Per role, after sign-in: **teachers** get a dashboard with live cards, the versioned question
bank and its editor, the exam builder, releases, the live execution monitor, grading, results
with histograms, and the bot manager; **coordinators** get all of that plus the approval queue
with a student-identical exam preview; **students** get take-exam, their own grades with the
marked paper behind each row, and the study bot; the **principal** gets read-only school-wide
reports and a data browser, with zero write controls anywhere (asserted by test).

---

## 4. Design Patterns at a Glance

| Pattern | Where | Purpose |
|---------|-------|---------|
| **Adapter** | `client.net.IClientConnection` over OCSF; `BotProvider` over each AI vendor | Phase-2 transport swap = one class; AI vendors interchangeable |
| **Strategy** | `server.features.reports.ReportEngine` over `DimensionStrategy` | A new principal report = one class + one registration line (proven by a fourth strategy defined inside a test) |
| **Observer** | Client EventBus; server `PushGateway`; attempt/lock listeners | Screens react to pushes without knowing each other |
| **Singleton** | `HibernateUtil`'s one `SessionFactory` | Expensive to build, safe to share, closed once |
| **Template Method** | `RepositoryTestBase` + the `*Contract` test suites | Every data-layer test runs on H2 AND real MySQL |
| **Repository + Projection** | `server.db.repos.*` | One query per need; student-facing projections have no field for a correct answer |
| **DI + injected Clock** | Every service constructor | Deterministic timer/lockout/TTL tests, no sleeping |
| **State machine + CAS** | Exam attempts (`IN_PROGRESS -> SUBMITTED / TIMED_OUT`) | One atomic guarded UPDATE decides submit-vs-expiry races |

---

## 5. Concurrency Model

The server computes every deadline itself (start + duration + extensions, derived, never
stored) and re-checks status and deadline inside each transaction; a crashed client still gets
force-submitted by `TimerService`. On the client, OCSF reads on a background thread and every
crossing to the FX thread goes through one `FxThreadPoster` seam - the event bus and the shared
`LockAwareEditor` deliver on the FX thread by construction, so screens never call
`Platform.runLater` themselves and tests run the whole path synchronously.

---

## 6. Security

- **Identity is the session, never the payload.** No student verb carries a user id; the server
  resolves the caller from the authenticated socket on every request.
- **Structural over careful.** A family of guard tests scans source and bytecode and fails the
  build on any path that could carry an answer key to a student - the student-facing projections
  and DTOs have nowhere to put one.
- **BCrypt** with per-password salts; one generic login failure message; a dummy-hash timing
  defense; a lockout after 5 failures that refuses even correct passwords (so the throttle
  cannot become a password oracle).
- **Locks and versions.** Server-side edit locks (advisory, TTL + heartbeat) refuse a write on
  a row someone else is editing; optimistic version checks remain the final arbiter, so a stale
  save is a calm CONFLICT with a reload, never a lost update.
- **The bot cannot leak exams.** A bytecode-level isolation test proves the bot package cannot
  even reference exam or grade code; bot context is built from sources and bank questions
  without correct answers.

---

## 7. Testing

One command runs everything: `mvnw clean verify` compiles, runs ~6,000 tests (zero skipped),
enforces a **90% instruction-coverage gate** (thin view classes excluded one-by-one, never by
wildcard), and packages both JARs. Data-layer suites run on H2 and real MySQL both
(`HSTS_REQUIRE_MYSQL=true` makes MySQL mandatory, as CI does); TestFX + Monocle drive real
clicks through the screens headlessly; wiring guards fail the build if a screen or handler
ships unreachable; and `SeedDatasetContract` re-derives the demo dataset's statistics so a
hand-edited number cannot drift from its source document.

---

## 8. Project Structure

```
hsts-v2/
├── docs/                       # PRD, ARCHITECTURE, TODO (epics E0-E23), contracts/ (frozen
│                               #   wire contracts), reports/ (per-PR records), DEMO_DAY.md
├── src/main/java/
│   ├── client/
│   │   ├── core/               # Navigator, Routes, ScreenManager, session routing
│   │   ├── ui/                 # design system: components, shell, theme, Motion
│   │   ├── features/           # one package per feature: bank, exam, grading, results,
│   │   │                       #   release, reports, data, bot, home, locks, settings...
│   │   └── net/                # IClientConnection adapter, RequestDispatcher
│   ├── common/
│   │   ├── protocol/           # Message envelope + Verb vocabulary
│   │   └── dto/                # Serializable wire records, one package per feature
│   ├── server/
│   │   ├── core/               # HSTSServer, MessageRouter, SessionManager, Authorization
│   │   ├── features/           # auth, bank, exambuild, approval, release, exam, grading,
│   │   │                       #   results, reports, bot, notify, locks
│   │   ├── db/                 # entities, repos/ (repositories + contracts), projections/,
│   │   │                       #   seed/ (demo dataset + SeedMain), Flyway migrations
│   │   ├── console/            # the server's own JavaFX console window (E19)
│   │   └── realtime/           # PushGateway
│   └── ocsf/                   # vendored OCSF source (self-contained)
└── src/main/resources/
    ├── css/hsts.css            # design tokens + every screen's styles
    ├── css/accent-*.css        # the five accent palettes
    └── db/migration/           # V1__core.sql ... V7__notifications.sql
```

---

## 9. Build Notes

- **Two Fat JARs.** `maven-shade-plugin` produces separate deployable artifacts:
  - `hsts-server.jar` — `Main-Class = server.core.ServerMain`; includes MySQL driver and JavaFX
    (the server console of E19 is a JavaFX window).
  - `hsts-client.jar` — `Main-Class = client.core.ClientLauncher`; includes JavaFX, excludes
    the MySQL driver, Hibernate, Flyway, PDFBox, POI and bot-provider **libraries** by
    allow-list. Project **classes** are never filtered: the client jar ships the full project
    artifact on purpose, and E6.11's editor depends on it (ARCHITECTURE, packaging invariant).
  Both are plain (non-`Application`) entry points to satisfy JavaFX module restrictions in a shaded jar.
- **JAR names (E20.1).** `-Djar.prefix=G12-1 package` switches both to `G12-1_Server.jar` /
  `G12-1_Client.jar`; without it they keep the `hsts-server` / `hsts-client` names. The switch
  activates a profile rather than editing a property, so the group number never lands in a commit.
- **External configuration (E20.4).** Root-level `client.properties` and `server.properties` are
  copied into `target/` at package time so they sit beside the JARs out of the box; whichever of
  the two is still missing is seeded from its `*.properties.example`, and an existing file in
  `target/` is left alone. Edit those copies (or place your own next to the JARs at deploy time)
  without rebuilding.
- **Terminal logs (E20.3).** The server's stdout stream is colour-coded by severity through
  logback's own ANSI converters (no extra library). Windows Terminal and PowerShell 5+ render it;
  a terminal with no ANSI support prints the escape codes literally, and the fix is the terminal,
  not the build. The console window's log pane formats events itself and is unaffected.
- **Self-contained deps.** OCSF is native source, not a dependency; only JavaFX and the
  MySQL connector resolve from Maven Central.
- **Platform note (E20.4b).** JavaFX ships its natives per platform and shade bakes in whichever
  the build machine resolved. **Both** JARs contain JavaFX since E19, so **both** are
  platform-specific: the submission JARs must be built on Windows, on the machine family that
  will run them. A JAR built on Linux or macOS fails at launch on Windows with an
  `UnsatisfiedLinkError`, and no amount of re-zipping fixes it.
