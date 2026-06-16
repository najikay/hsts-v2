# Implementation Plan

**Project:** HSTS — Thin Client / Fat Server Desktop Application
**Scope:** Assignment Part C lean prototype, built on a scalable documented architecture.

This plan sequences the prototype into discrete, verifiable phases. Each phase has an objective, concrete deliverables, and an exit criterion. Build phases in order — later phases depend on earlier ones.

---

## Phase 0 — Architecture & Documentation *(this phase)*

**Objective:** Establish shared understanding and project-management artifacts before any code.

**Deliverables:**
- `docs/prd.md` — scope, CRUD requirements, build constraints, design patterns.
- `docs/plan.md` — this file.
- `docs/todo.md` — granular task checklist.

**Exit criterion:** Three docs reviewed and agreed; architecture (patterns, tiers) is locked.

---

## Phase 1 — Maven / JavaFX / Git Project Setup

**Objective:** A buildable, runnable, empty-shell project skeleton.

**Deliverables:**
- Git repository initialized with a Java-appropriate `.gitignore` (target/, IDE files, local config).
- `pom.xml` with: JavaFX dependencies, OCSF dependency/jar, MySQL Connector/J, and the **`maven-shade-plugin`** configured.
- Standard Maven source layout (`src/main/java`, `src/main/resources`).
- **`Launcher` class** (non-Application entry point) wired as the shade `Main-Class`.
- Minimal `ClientApp extends Application` that opens a blank window.
- Package structure laid out for the architecture: `client.ui`, `client.network`, `common.entities`, `server`, `server.db`.

**Exit criterion:** `mvn clean package` produces a Fat JAR; the JAR launches a blank JavaFX window by double-click (and via `java -jar`).

---

## Phase 2 — Database & ORM/DAO Layer

**Objective:** A seeded database and a clean data-access layer on the server tier.

**Deliverables:**
- SQL schema script creating the `Questions` table.
- Seed script inserting **6 dummy questions**.
- JDBC connection configuration (externalized, not hardcoded into logic).
- `Question` entity (POJO) in `common.entities`.
- `QuestionDAO` (JDBC/ORM) exposing `getAll()` and `update(question)` — shaped to later accept a JSON metadata column.

**Exit criterion:** A standalone test/main reads all 6 questions and updates one, verified against the live MySQL instance.

---

## Phase 3 — OCSF Server

**Objective:** A Fat Server that mediates all data access over OCSF.

**Deliverables:**
- Server class extending OCSF `AbstractServer`.
- Message protocol (request/response objects) for `GET_ALL_QUESTIONS` and `UPDATE_QUESTION`.
- `handleMessageFromClient` routing requests to `QuestionDAO`.
- Server returns updated/refreshed data to the originating client.
- Server-side wiring positioned as the future "secure gatekeeper" (single choke point for all client requests).

**Exit criterion:** Server starts, accepts a connection, responds to both message types with correct DB-backed data (verified with a temporary test client or logging).

---

## Phase 4 — GUI & End-to-End Integration

**Objective:** The complete demonstrable user journey.

**Deliverables:**
- **`ScreenManager` (Singleton)** owning the Stage and scene routing.
- **`AbstractScreenUI` (Template Method)** base; the questions view extends it.
- Networking accessed through the **Adapter interface** (`IClientConnection`) backed by an OCSF client adapter.
- Questions list view: load + display all questions.
- Select → edit → send update over OCSF → receive updated record → re-display.

**Exit criterion:** Full round trip works against the built Fat JAR: view list → select → edit → update → see the persisted change on screen.

---

## Phase 5 — Packaging & Demo Hardening

**Objective:** A clean, double-clickable deliverable for the presentation.

**Deliverables:**
- Verified Fat JAR via `maven-shade-plugin` with `Launcher` as `Main-Class`.
- Run instructions (start MySQL → start server → launch client JAR).
- Quick smoke test on a clean Ubuntu/WSL run.

**Exit criterion:** Double-clicking the JAR (server running) demonstrates the full CRUD-Update flow end to end.

---

## Documented-But-Not-Built (tracked for the presentation)

These are intentionally **designed, not implemented** in Part C — referenced so the architecture story is complete:

- Abstract `User` entity + role subclasses.
- `JSON` AI-metadata column on `Questions`.
- Multi-provider LLM gatekeeper (OpenAI, Claude, etc.): routing, rate limiting, prompt-injection prevention.
- Create / Delete operations and additional screens.

---

## Context-Management Protocol

Per project rule: **no `claude.md` memory file is used.** When transitioning between phases or coding tasks, the assistant will emit a **long, comprehensive text summary** in the console response capturing all decisions, file locations, schema, protocol message shapes, and open items — so context is preserved across the session without a persisted memory file.
