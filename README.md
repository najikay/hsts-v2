# HSTS — Homework & Study Test System (Prototype)

A monolithic **3-tier desktop application** built on the **Thin Client / Fat Server**
paradigm. A JavaFX client lets a user view and edit exam questions; all logic and
persistence live on a server reached over the OCSF networking framework, backed by MySQL.

> **Status:** Assignment Part C prototype — lean by design, but architected so every
> decision scales into enterprise infrastructure (Docker/Kubernetes, JSON AI metadata,
> a multi-provider LLM gatekeeper). See [`docs/demo_guide.md`](docs/demo_guide.md) for the
> scaling roadmap and [`docs/defense_prep.md`](docs/defense_prep.md) for the defense Q&A.

---

## 1. Project Overview

HSTS is a question-management prototype demonstrating a full client → server → database
→ client round trip:

1. The client requests and displays the list of questions.
2. The user selects and edits a question.
3. The update is sent to the server over OCSF.
4. The server persists it to MySQL and returns the refreshed data.
5. The client re-displays the updated question.

**Stack:** JavaFX 17 (UI) · native OCSF (networking) · MySQL via JDBC (data) ·
Maven + `maven-shade-plugin` (single double-clickable Fat JAR). Built solo on Ubuntu/WSL, Java 17.

### Tiers

| Tier | Responsibility | Key types |
|------|---------------|-----------|
| **Presentation** | Render UI, capture intent | `client.ui.*`, `client.network.*` |
| **Logic (Fat Server)** | Route requests, enforce rules, gatekeep data | `server.HSTSServer`, `server.ServerMain` |
| **Data** | Persistence | `server.db.QuestionDAO`, `DatabaseConfig`, MySQL |
| **Common** | Shared wire types | `common.entities.Question`, `common.network.Message` |

---

## 2. Quick Start

**Prerequisites:** Java 17, MySQL, Maven.

```bash
# 1. Seed the database (one-time)
mysql -u root -p < src/main/resources/schema.sql
mysql -u root -p < src/main/resources/seed.sql

# 2. Build the Fat JAR
mvn clean package

# 3. Launch everything with one double-click (server + client boot together)
java -jar target/hsts-prototype.jar
```

Default DB credentials live in `server/db/DatabaseConfig.java` (`root`/`root` @
`localhost:3306/hsts_db`) — edit the constants to match your machine. Full presentation
walkthrough in [`docs/demo_guide.md`](docs/demo_guide.md).

---

## 3. Architectural Justifications

Every pattern here was chosen for **traceability now, scalability later**.

### 3.1 Thin Client / Fat Server
The client holds **no business logic and no database credentials**. It renders UI and
relays intent; the server owns all rules, validation, and persistence. This keeps the
client trivially replaceable and — crucially — means the server is the *only* tier that
must be trusted, secured, and scaled. When we containerize, only the server moves.

### 3.2 Native OCSF behind an Adapter (`IClientConnection`)
OCSF (Object Client/Server Framework) provides the socket + object-serialization
transport. Rather than scatter OCSF calls through the UI, we hide it behind the
**Adapter pattern**: the UI depends only on the `IClientConnection` interface, and
`HSTSClient` is the OCSF-backed implementation. **Why:** a future migration to gRPC or
REST (e.g. for a Kubernetes ingress) becomes a *single new adapter class* — zero UI
changes. The framework is vendored natively under `src/main/java/ocsf/`, so the project
is 100% self-contained (no external jar).

### 3.3 DAO / ORM pattern (`QuestionDAO`)
All SQL is isolated in the Data Access Object. Callers (the server handlers) speak in
`Question` objects, never SQL strings. **Why:** we can add the future `ai_metadata` JSON
column, change queries, or swap the persistence engine without touching the logic tier.

### 3.4 Why we deliberately did **not** use an Event Bus
An event bus (publish/subscribe) is tempting for decoupling, but for a prototype whose
chief goal is **demonstrable, auditable correctness**, it is the wrong tool:

- **Traceability.** Our flow is strictly synchronous and linear: a UI action sends one
  `Message`, the server handles it, one response comes back. You can read the entire data
  path top-to-bottom in `handleMessageFromClient`. An event bus would scatter that path
  across loosely-coupled subscribers, making the demo and the defense *harder* to reason about.
- **Determinism.** Request/response gives one obvious place where each command is handled
  and answered — no hidden fan-out, no ordering surprises.
- **Right-sizing.** An event bus solves problems (many-to-many async events, decoupled
  producers/consumers) that this prototype does not have. Adding it would be architecture
  for its own sake.

We kept the data flow **synchronous and point-to-point** on purpose. The one place we
*do* cross threads — receiving server responses — is handled explicitly and visibly via
`Platform.runLater` (see §4), not buried in a bus.

---

## 4. Concurrency Model

OCSF reads from the socket on a **background thread**. JavaFX forbids touching the scene
graph from any thread but the **JavaFX Application Thread**. The boundary is crossed in
exactly one place: `HSTSClient.handleMessageFromServer` wraps the hand-off to the UI in
`Platform.runLater(...)`. This keeps the entire view (`QuestionsView`) free of threading
concerns — it only ever runs on the FX thread — while the network read loop never blocks
the UI.

---

## 5. Security

### 5.1 The Fat Server as Gatekeeper
The server is the **single choke point** for all data access. Clients cannot reach MySQL
directly; they can only send `Message` requests, which the server validates (type-checks
the envelope, switches on a known `Command`, rejects anything else with an `ERROR`) before
touching the DAO. This is the foundation for the future **LLM gatekeeper**: the same
trusted boundary that mediates DB access will mediate AI-provider access — holding API
keys, enforcing rate limits, and sanitizing prompts server-side so a client can never
exfiltrate credentials or inject a malicious prompt.

### 5.2 SQL injection neutralized by `PreparedStatement`
Every query in `QuestionDAO` uses a parameterized `PreparedStatement`. User input is
bound as **typed parameters** (`ps.setString`, `ps.setInt`), never concatenated into SQL
text. The database driver sends the query template and the values **separately**, so input
can never change the structure of the statement — classic injection (`'; DROP TABLE …`)
is treated as a literal string value, not executable SQL.

---

## 6. Design Patterns at a Glance

| Pattern | Where | Purpose |
|---------|-------|---------|
| **Singleton** | `client.ui.ScreenManager` | One owner of the Stage + connection; central navigation |
| **Template Method** | `client.ui.AbstractScreenUI` | Fixed screen lifecycle (`render()` → `onShown()`), variable steps in subclasses |
| **Adapter** | `client.network.IClientConnection` / `HSTSClient` | Hide OCSF; enable future protocol swap |
| **DAO** | `server.db.QuestionDAO` | Isolate SQL from logic |
| **Abstract entity (planned)** | `common.entities.User` (future) | Polymorphic role subclasses |

---

## 7. Project Structure

```
HSTS/
├── pom.xml                       # Maven build + shade (Fat JAR, Main-Class = Launcher)
├── README.md
├── docs/
│   ├── prd.md                    # Requirements & design patterns
│   ├── plan.md                   # Phased implementation plan
│   ├── todo.md                   # Granular task checklist (100% complete)
│   ├── demo_guide.md             # Presentation script + enterprise roadmap
│   └── defense_prep.md           # Examiner Q&A
└── src/main/
    ├── java/
    │   ├── client/ui/            # Launcher, ClientApp, ScreenManager, AbstractScreenUI, QuestionsView
    │   ├── client/network/       # IClientConnection (Adapter), HSTSClient
    │   ├── common/entities/      # Question (Serializable)
    │   ├── common/network/       # Message + Command enum (Serializable protocol)
    │   ├── ocsf/                 # Native OCSF: server.AbstractServer, server.ConnectionToClient, client.AbstractClient
    │   └── server/               # HSTSServer, ServerMain, db/{DatabaseConfig, QuestionDAO}
    └── resources/                # schema.sql, seed.sql
```

---

## 8. Build Notes

- **Single-click launch.** `client.ui.Launcher` is the manifest `Main-Class` (a plain,
  non-`Application` class — required to bypass JavaFX module restrictions in a shaded jar).
  It boots the server on a daemon thread, waits for the port to bind, then starts the
  JavaFX client.
- **Self-contained.** OCSF is native source, not a dependency; only JavaFX and MySQL
  Connector/J resolve from Maven Central.
- **Platform note.** The classpath Fat JAR bundles OS-specific JavaFX natives — a Linux
  build runs on Linux. Per-platform classifiers would produce a Windows-runnable jar.

---

## 9. Roadmap

See [`docs/demo_guide.md`](docs/demo_guide.md) Part 2 for the full enterprise scaling story:
Dockerized server + Kubernetes load balancing, the `ai_metadata` JSON column for prompt
provenance & token accounting, and the Fat Server's evolution into a multi-provider
(OpenAI, Anthropic/Claude, …) LLM gatekeeper.
