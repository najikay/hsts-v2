# TODO — HSTS Prototype Task List

Granular, checkable tasks starting from an empty repository. Grouped by phase (see `plan.md`). Check items off as completed.

**STATUS: ✅ Prototype scope 100% complete (Phases 0–5).**

---

## Phase 0 — Architecture & Documentation
- [x] Create `docs/` directory
- [x] Write `docs/prd.md`
- [x] Write `docs/plan.md`
- [x] Write `docs/todo.md`
- [x] Review and lock architecture decisions

---

## Phase 1 — Maven / JavaFX / Git Setup
- [x] `git init` in project root
- [x] Add Java `.gitignore` (target/, *.class, IDE dirs, local DB config)
- [x] Create `pom.xml` with project coordinates (groupId, artifactId, version)
- [x] Add JavaFX dependencies (controls, fxml) to `pom.xml`
- [x] Add OCSF (now native in `src/main/java/ocsf/` — no external jar)
- [x] Add MySQL Connector/J dependency
- [x] Configure `maven-shade-plugin` (transformers, Main-Class manifest)
- [x] Create Maven source layout `src/main/java`, `src/main/resources`
- [x] Create package structure: `client.ui`, `client.network`, `common.entities`, `server`, `server.db`
- [x] Write `Launcher` class (non-Application `main`)
- [x] Write minimal `ClientApp extends Application` (window)
- [x] Point shade `Main-Class` at `Launcher`
- [x] `mvn clean package` → confirm Fat JAR is produced
- [x] Double-click JAR → confirm JavaFX window opens

---

## Phase 1.5 — Native OCSF Integration
- [x] Remove system-scoped external OCSF dependency from `pom.xml`
- [x] Create `ocsf.server.AbstractServer`
- [x] Create `ocsf.server.ConnectionToClient`
- [x] Create `ocsf.client.AbstractClient`
- [x] Project is 100% self-contained (builds from Maven Central only)

---

## Phase 2 — Database & ORM/DAO
- [x] Ensure local MySQL is running in WSL
- [x] Create database/schema
- [x] Write `schema.sql`: create `Questions` table (`id`, `question_text`, `answer`)
- [x] Write seed insert for **6 dummy questions**
- [x] Run schema + seed against MySQL; verify 6 rows
- [x] Externalize JDBC config (`DatabaseConfig` constants — not hardcoded in logic)
- [x] Create `Question` POJO entity in `common.entities` (Serializable)
- [x] Implement `QuestionDAO.getAll()`
- [x] Implement `QuestionDAO.update(question)`
- [x] Smoke test: read all + update one (`DaoTest`) — verified, then removed
- [x] Leave schema/DAO shaped to later add `JSON` AI-metadata column

---

## Phase 3 — OCSF Server
- [x] Create server class extending OCSF `AbstractServer` (`HSTSServer`)
- [x] Define request/response message objects (`Message` + `Command` enum)
- [x] Implement `GET_ALL_QUESTIONS` handler → `QuestionDAO.getAll()`
- [x] Implement `UPDATE_QUESTION` handler → `QuestionDAO.update()`
- [x] Return updated/refreshed data to the client
- [x] Add server startup + listening-port logging (`ServerMain`, port 5555)
- [x] Verify both message types (runtime-verified via client)

---

## Phase 4 — GUI & Integration
- [x] Create `ScreenManager` (Singleton) owning the Stage
- [x] Create `AbstractScreenUI` (Template Method) base class
- [x] Define `IClientConnection` interface (Adapter)
- [x] Implement OCSF-backed client adapter for `IClientConnection` (`HSTSClient`)
- [x] Build Questions list view extending `AbstractScreenUI`
- [x] On launch: request all questions → display list
- [x] Enable selecting a question from the list
- [x] Enable editing the selected question's text
- [x] Wire "send update" → OCSF `UPDATE_QUESTION`
- [x] Receive updated record → re-display on screen
- [x] End-to-end manual test: view → select → edit → update → see change

---

## Phase 5 — Packaging & Demo
- [x] Rebuild Fat JAR with full app
- [x] Confirm `Launcher` is the manifest `Main-Class`
- [x] UI polish: `questionField` → multi-line `TextArea` (wrap, 2 rows)
- [x] Single-click `Launcher`: boots server thread + client in one process
- [x] Delete throwaway `DaoTest.java`
- [x] Write demo guide (`docs/demo_guide.md`)
- [x] Clean Ubuntu/WSL smoke test of full flow
- [x] Confirm double-clickable launch boots server + UI together

---

## Documented-But-Not-Built (no code in Part C — design only, for presentation)
- [ ] (Design only) Abstract `User` entity + role subclasses
- [ ] (Design only) `JSON` AI-metadata column on `Questions`
- [ ] (Design only) Multi-provider LLM gatekeeper: routing, rate limits, prompt-injection prevention
- [ ] (Design only) Create / Delete operations & additional screens
