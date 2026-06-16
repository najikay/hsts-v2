# Product Requirements Document (PRD)

**Project:** HSTS — Java Desktop Application (Thin Client / Fat Server)
**Architecture:** Monolithic 3-Tier (Presentation / Logic / Data)
**Stack:** JavaFX (UI) · OCSF (Networking) · MySQL (Persistence) · Maven (Build)
**Author Role:** Lead Systems Architect
**Status:** Draft — Assignment Part C Prototype

---

## 1. Project Scope

HSTS is a monolithic, 3-tier desktop application built on the **Thin Client / Fat Server** model. All business logic, validation, persistence, and (in future) AI orchestration live on the server. The client is responsible only for rendering UI and relaying user intent over the network.

The three tiers:

| Tier | Responsibility | Technology |
|------|---------------|------------|
| **Presentation** | Render views, capture user input, display server responses | JavaFX |
| **Logic / Application** | Request routing, business rules, validation, future AI gatekeeping | OCSF Server (Fat Server) |
| **Data** | Persistence and retrieval | MySQL via JDBC/ORM |

### 1.1 Prototype Boundary (Assignment Part C)

This document covers a **lean prototype**. The codebase must remain minimal and demonstrable, while the *documentation* reflects the full scalable architecture for presentation purposes. Concretely, the prototype ships:

- A basic **OCSF server**.
- A **skeletal MySQL database**: a single `Questions` table seeded with **6 dummy entries**.
- A **single JavaFX UI client**.

Anything beyond this (authentication, multiple screens, AI routing, additional tables) is **documented as design intent but NOT implemented** in Part C.

---

## 2. Prototype Functional Requirements (CRUD Flow)

The prototype must demonstrate a complete client→server→database→client round trip. The minimum required user journey:

1. **View** — On launch, the client requests and displays the full list of questions retrieved from the server.
2. **Select** — The user selects a single question from the list.
3. **Edit** — The user edits the selected question's content in the UI.
4. **Update (Send)** — The client sends the update request to the server over **OCSF**.
5. **Persist** — The server applies the update to the MySQL `Questions` table.
6. **Read Back / Display** — The server returns the updated record; the client reads and displays the updated question on screen.

### 2.1 CRUD Coverage

| Operation | Prototype Status | Notes |
|-----------|------------------|-------|
| **Create** | Out of scope (data seeded) | 6 dummy rows inserted via schema script |
| **Read**   | ✅ Required | List all + display single |
| **Update** | ✅ Required | Core demonstrable flow |
| **Delete** | Out of scope | Documented for future |

### 2.2 `Questions` Table (Prototype Schema)

Minimal columns for Part C (final column set defined in §4.4):

| Column | Type | Notes |
|--------|------|-------|
| `id` | INT, PK, AUTO_INCREMENT | |
| `question_text` | VARCHAR / TEXT | Editable content |
| `answer` | VARCHAR / TEXT | Optional in prototype |

> **Future:** the table is designed to *eventually* add a `JSONB`-style metadata column for AI metadata (see §4.4). MySQL's `JSON` type is the equivalent; this is documented now, not built now.

---

## 3. Build & Packaging Requirements

### 3.1 Fat JAR Constraint

The project must **eventually** be packaged as a **double-clickable Fat JAR** using **`maven-shade-plugin`**, bundling all dependencies (JavaFX, OCSF, MySQL connector) into a single executable artifact.

### 3.2 JavaFX Module Workaround (Launcher Class)

JavaFX 11+ enforces module restrictions that prevent a shaded Fat JAR from launching when `main()` lives directly on a class that `extends Application`. To bypass this, the project **must** provide a standard **non-JavaFX `Launcher` class**:

```
public class Launcher {
    public static void main(String[] args) {
        ClientApp.main(args);   // ClientApp extends javafx.application.Application
    }
}
```

- The `Manifest`'s `Main-Class` points at `Launcher`, **not** the `Application` subclass.
- This is a hard build requirement, not optional polish.

### 3.3 Environment

- Built and run in an **Ubuntu / WSL** environment.
- Standard JDK (17+ recommended), Maven, local MySQL instance.

---

## 4. Design Patterns & Architecture

> The prototype is intentionally lean, but the architecture below is the **documented design intent** demonstrated in the presentation. Where a pattern is "documented only," the prototype may use a simplified placeholder, but the structure must leave room for the full design.

### 4.1 UI Navigation — Singleton + Template Method

- **Centralized `ScreenManager` (Singleton Pattern):** a single, globally-accessible controller responsible for routing between views, owning the JavaFX `Stage`, and swapping scenes. One instance, one source of navigation truth.
- **`AbstractScreenUI` (Template Method Pattern):** all views inherit from an abstract base that defines the skeleton of screen lifecycle (e.g., `initialize()`, `loadData()`, `render()`), deferring the variable steps to concrete subclasses. The prototype's single client screen extends this base so future screens slot in without changing the navigation contract.

### 4.2 Entities — Abstract Class + Polymorphism

- **`User` modeled as an Abstract Class:** even though the prototype has no authentication, `User` is documented as an abstract base to enable future subclasses (e.g., `Student`, `Instructor`, `Admin`) via polymorphism. Shared state/behavior lives on the abstract parent; role-specific behavior is overridden.

### 4.3 Networking — OCSF wrapped by an Adapter

- **OCSF used natively now.** The prototype talks directly to the OCSF `AbstractServer` / `AbstractClient` machinery.
- **Adapter Pattern wrapper:** client/server networking is accessed through an interface (e.g., `IClientConnection`) that today is implemented by an OCSF-backed adapter. This isolates OCSF behind a stable contract so a **future protocol migration** (REST, gRPC, WebSocket) requires swapping only the adapter, not the application.

### 4.4 Database — JDBC/ORM with future JSON metadata

- **JDBC/ORM configuration:** persistence goes through a data-access layer (DAO/ORM), not ad-hoc SQL scattered through logic.
- **Forward-looking schema:** the `Questions` table is designed to *eventually* include a **`JSONB`-style column (MySQL `JSON`)** for **AI metadata** — embeddings references, provider tags, generation provenance, etc. Not implemented in Part C, but the schema and DAO are shaped to accept it.

### 4.5 Future AI Vision — Fat Server as Secure Gatekeeper

The Fat Server is the long-term home for a **multi-provider LLM infrastructure**. The server acts as a **secure gatekeeper** that:

- Houses provider integrations for **multiple LLM vendors** (e.g., OpenAI, Claude/Anthropic, and others) behind a unified routing layer.
- **Routes requests** to the appropriate provider/model.
- **Manages rate limits** and quotas centrally.
- **Prevents client-side prompt injection** — clients never hold API keys or craft raw provider prompts; all model access is mediated, validated, and sanitized server-side.

This keeps secrets, policy, and abuse-prevention on the trusted server tier, consistent with the Thin Client / Fat Server model.

> When building the AI tier, default to the latest, most capable Claude models for the Anthropic provider integration.

---

## 5. Out of Scope (Part C)

- Authentication / authorization flows.
- Multiple client screens beyond the single questions view.
- Create / Delete operations.
- Live LLM provider integrations.
- The `JSON` AI-metadata column (designed, not added).

---

## 6. Acceptance Criteria (Prototype)

- [ ] OCSF server starts and accepts a client connection.
- [ ] MySQL `Questions` table exists with 6 dummy rows.
- [ ] Client launches via the `Launcher` class and displays the question list.
- [ ] User can select and edit a question.
- [ ] Update is sent over OCSF, persisted, and the updated value is displayed back.
- [ ] Project builds a runnable Fat JAR via `maven-shade-plugin`.
