# HSTS — High School Test System (Prototype)

A monolithic **3-tier desktop application** built on the **Thin Client / Fat Server**
paradigm. A JavaFX client lets a user view and edit exam questions; all logic and
persistence live on a server reached over the OCSF networking framework, backed by MySQL.

> **Scope:** This is the **working prototype (Part C)** of a larger course project — the
> full HSTS handles question banks, exam building/execution/grading, statistics, an AI
> study-bot, and multiple user roles. The prototype deliberately implements just one
> vertical slice (view → select → edit → update a question) end-to-end, on an
> architecture shaped so the rest of the system slots in cleanly.

---

## 1. What it does

A complete client → server → database → client round trip:

1. The client connects to the server and requests the list of questions.
2. The user selects a question and edits its text / answer.
3. The update is sent to the server over OCSF.
4. The server validates it and persists it to MySQL via the DAO.
5. The server returns the refreshed list; the client re-renders the saved state.

**Stack:** JavaFX 17 (FXML + CSS UI) · native OCSF (networking) · MySQL via JDBC (data) ·
Maven + `maven-shade-plugin` (two deployable Fat JARs — server and client).

### Tiers

| Tier | Responsibility | Key types |
|------|---------------|-----------|
| **Presentation** | Render UI, capture intent | `client.core.*`, `client.ui.*`, `client.features.*`, `client.net.*` |
| **Logic (Fat Server)** | Route requests, enforce rules, gatekeep data | `server.core.HSTSServer`, `server.core.ServerMain` |
| **Data** | Persistence | `server.db.QuestionDAO`, `DatabaseConfig`, MySQL |
| **Common** | Shared wire types | `common.dto.bank.Question`, `common.protocol.Message` |

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

For a two-machine demo, run the server on one machine and set `server.host` on the
client to that machine's LAN IP. The database host/port (`localhost:3306/hsts_db`) are
fixed in `server/db/DatabaseConfig.java` for the prototype.

---

## 3. User Interface

The UI is defined in **FXML** with a shared **CSS** theme, so layout and styling are
separated from the controller logic.

- **Connect screen** (`ConnectView`) — branded splash that opens the OCSF connection on a
  background thread; on success it asks the navigation controller to swap to the main
  screen, on failure it shows an inline error + Retry.
- **Main screen** (`QuestionsView`) — a master-detail layout: a scrollable, multi-line
  question list on the left; an editor on the right with unsaved-changes tracking, a
  Revert action, and a transient "Saved" confirmation after a successful write.
- **Branding** (`Logo`) — a vector graduation-cap mark on the app's indigo gradient,
  reused on the splash, in the header, and as the window icon. Source: `branding/hsts-logo.svg`.

---

## 4. Architecture & Design Choices

Every decision favours **traceable, demonstrable correctness now**, with clean seams for
later growth.

### 4.1 Thin Client / Fat Server
The client holds **no business logic and no database credentials** — it renders UI and
relays intent. The server owns all rules, validation, and persistence. The client is
trivially replaceable, and the server is the only tier that must be trusted and scaled.

### 4.2 Native OCSF behind an Adapter (`IClientConnection`)
OCSF provides the socket + object-serialization transport. The UI never touches OCSF
directly — it depends only on the `IClientConnection` interface, implemented by the
OCSF-backed `HSTSClient` (**Adapter pattern**). A future protocol swap (REST, gRPC,
WebSocket) becomes a single new adapter class, with zero UI changes. OCSF is vendored as
source under `src/main/java/ocsf/`, so the project is self-contained.

### 4.3 DAO pattern (`QuestionDAO`)
All SQL is isolated in the Data Access Object; callers speak in `Question` objects, never
SQL strings. Persistence can change (new columns, a different engine) without touching the
logic tier.

### 4.4 Why no Event Bus
The flow is intentionally **synchronous and point-to-point**: one UI action sends one
`Message`, the server handles it in one place (`handleMessageFromClient`), and one
response comes back. This keeps the whole data path readable top-to-bottom and the
behaviour deterministic. An event bus would scatter that path across subscribers and solve
problems this prototype doesn't have.

---

## 5. Concurrency Model

OCSF reads from the socket on a **background thread**; JavaFX may only be touched from the
**JavaFX Application Thread**. That boundary is crossed in exactly one place —
`HSTSClient.handleMessageFromServer` wraps the hand-off to the UI in
`Platform.runLater(...)`. The view code therefore only ever runs on the FX thread, and the
network read loop never blocks the UI.

---

## 6. Security

- **Fat Server as gatekeeper.** The server is the single choke point for data access.
  Clients cannot reach MySQL directly — they send `Message` requests, which the server
  type-checks and routes on a known `Command` (rejecting anything else with an `ERROR`)
  before touching the DAO. The same trusted boundary is where future user-auth and
  AI-provider mediation will live.
- **SQL injection neutralized.** Every query in `QuestionDAO` uses a parameterized
  `PreparedStatement`; user input is bound as typed parameters, never concatenated into SQL
  text — so input can never alter the statement's structure.

---

## 7. Design Patterns at a Glance

| Pattern | Where | Purpose |
|---------|-------|---------|
| **Singleton** | `client.core.ScreenManager` | One owner of the Stage + connection; central navigation |
| **Template Method** | `client.core.AbstractScreenUI` | Fixed screen lifecycle (`render()` → `onShown()`), variable steps in subclasses |
| **Adapter** | `client.net.IClientConnection` / `HSTSClient` | Hide OCSF; enable a future protocol swap |
| **DAO** | `server.db.QuestionDAO` | Isolate SQL from logic |

---

## 8. Project Structure

```
HSTS/
├── pom.xml                       # Maven build + shade (two Fat JARs)
├── client.properties             # deployment template → copied to target/ on build
├── server.properties             # deployment template → copied to target/ on build
├── README.md
└── src/main/
    ├── java/
    │   ├── client/
    │   │   ├── core/             # ClientLauncher, ClientApp, Launcher, ScreenManager,
    │   │   │                     #   AbstractScreenUI, ClientConfig — loads client.properties
    │   │   ├── net/              # IClientConnection (Adapter), HSTSClient
    │   │   ├── ui/components/    # Logo (design-system components)
    │   │   └── features/         # connect/ConnectView, bank/QuestionsView
    │   ├── common/dto/bank/      # Question (Serializable)
    │   ├── common/protocol/      # Message + Command enum (Serializable protocol)
    │   ├── ocsf/                 # Native OCSF: server.{AbstractServer, ConnectionToClient},
    │   │                         #   client.AbstractClient
    │   └── server/
    │       ├── core/             # HSTSServer, ServerMain,
    │       │                     #   ServerConfig — loads server.properties
    │       └── db/               # DatabaseConfig, QuestionDAO
    └── resources/
        ├── client.properties     # bundled default for the client JAR
        ├── server.properties     # bundled default for the server JAR
        ├── db/migration/        # Flyway-versioned schema (V1..V7)
        ├── fxml/                 # ConnectView.fxml, QuestionsView.fxml
        ├── css/app.css           # shared theme
        └── branding/             # hsts-logo.svg
```

---

## 9. Build Notes

- **Two Fat JARs.** `maven-shade-plugin` produces separate deployable artifacts:
  - `hsts-server.jar` — `Main-Class = server.core.ServerMain`; includes MySQL driver and JavaFX
    (the server console of E19 is a JavaFX window).
  - `hsts-client.jar` — `Main-Class = client.core.ClientLauncher`; includes JavaFX, excludes
    MySQL driver, Hibernate, Flyway, PDFBox, POI and the bot providers by allow-list.
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
