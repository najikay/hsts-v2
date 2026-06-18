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
Maven + `maven-shade-plugin` (single double-clickable Fat JAR).

### Tiers

| Tier | Responsibility | Key types |
|------|---------------|-----------|
| **Presentation** | Render UI, capture intent | `client.ui.*`, `client.network.*` |
| **Logic (Fat Server)** | Route requests, enforce rules, gatekeep data | `server.HSTSServer`, `server.ServerMain` |
| **Data** | Persistence | `server.db.QuestionDAO`, `DatabaseConfig`, MySQL |
| **Common** | Shared wire types | `common.entities.Question`, `common.network.Message` |

---

## 2. Quick Start

**Prerequisites:** Java 17+, MySQL, Maven.

```bash
# 1. Create + seed the database (one-time)
mysql -u root -p < src/main/resources/schema.sql
mysql -u root -p < src/main/resources/seed.sql

# 2. Build the Fat JAR
mvn clean package

# 3. Launch — one double-click boots the server and client together
java -jar target/hsts-prototype.jar
```

Default DB credentials live in `server/db/DatabaseConfig.java` (`root` / `root` @
`localhost:3306/hsts_db`) — edit the constants to match your machine.

A pre-built runnable jar is committed at `target/hsts-prototype.jar` for convenience.

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
| **Singleton** | `client.ui.ScreenManager` | One owner of the Stage + connection; central navigation |
| **Template Method** | `client.ui.AbstractScreenUI` | Fixed screen lifecycle (`render()` → `onShown()`), variable steps in subclasses |
| **Adapter** | `client.network.IClientConnection` / `HSTSClient` | Hide OCSF; enable a future protocol swap |
| **DAO** | `server.db.QuestionDAO` | Isolate SQL from logic |

---

## 8. Project Structure

```
HSTS/
├── pom.xml                       # Maven build + shade (Fat JAR, Main-Class = Launcher)
├── README.md
├── target/hsts-prototype.jar     # pre-built runnable jar
└── src/main/
    ├── java/
    │   ├── client/ui/            # Launcher, ClientApp, ScreenManager, AbstractScreenUI,
    │   │                         #   ConnectView, QuestionsView, Logo
    │   ├── client/network/       # IClientConnection (Adapter), HSTSClient
    │   ├── common/entities/      # Question (Serializable)
    │   ├── common/network/       # Message + Command enum (Serializable protocol)
    │   ├── ocsf/                 # Native OCSF: server.{AbstractServer, ConnectionToClient},
    │   │                         #   client.AbstractClient
    │   └── server/               # HSTSServer, ServerMain, db/{DatabaseConfig, QuestionDAO}
    └── resources/
        ├── schema.sql, seed.sql  # database setup
        ├── fxml/                 # ConnectView.fxml, QuestionsView.fxml
        ├── css/app.css           # shared theme
        └── branding/             # hsts-logo.svg
```

---

## 9. Build Notes

- **Single-click launch.** `client.ui.Launcher` is the manifest `Main-Class` (a plain,
  non-`Application` class — required to bypass JavaFX module restrictions in a shaded jar).
  It boots the server on a daemon thread, waits for the port to bind, then starts the
  JavaFX client.
- **Self-contained deps.** OCSF is native source, not a dependency; only JavaFX and the
  MySQL connector resolve from Maven Central.
- **Platform note.** The Fat JAR bundles the JavaFX natives for the OS it was built on, so
  build on the platform you intend to run (or add per-platform classifiers for a
  cross-platform jar).
