# HSTS v2 — Master Plan

> **Mission:** Rebuild HSTS (High School Test System) from the prototype into an enterprise-grade, defense-winning system. The previous final failed on UI/UX, a broken exams section, and a bot that didn't work at all. This time: nothing half-baked, everything accounted for, number 1 in the course.

**Course:** הנדסת תוכנה 203.3140, Spring 2026 · **Team:** 3 people · **Lead:** Naji (architecture, UI/UX, core backend)

Companion documents:
- [PRD.md](PRD.md) — every requirement, feature spec, UX spec, edge-case catalog
- [ARCHITECTURE.md](ARCHITECTURE.md) — protocol, database, packages, concurrency design
- [TODO.md](TODO.md) — full task breakdown (epics E0–E23)
- [TEAM_SPLIT.md](TEAM_SPLIT.md) — 3-way ownership, contracts, workflow rules
- [DECISIONS.md](DECISIONS.md) — architecture decision records (ADRs)

---

## 1. Where we are

### 1.1 The base (prototype, `main` branch) — KEEP
A small but solid 3-tier Thin Client / Fat Server slice:
- JavaFX 17 + FXML + CSS, `ScreenManager` (Singleton) + `AbstractScreenUI` (Template Method)
- Vendored OCSF networking behind `IClientConnection` (Adapter)
- `Message{Command, payload}` serializable envelope
- MySQL via JDBC `QuestionDAO` (DAO pattern), parameterized statements
- Maven + shade: two fat JARs (`hsts-server.jar`, `hsts-client.jar`) with external `.properties` beside the JAR

This architecture is correct. We keep the shape and rebuild everything on top of it.

### 1.2 The failed final (`person5-ui` branch) — MINE, DON'T MERGE
It actually contains useful backend material (services, DAOs, versioned schema V1–V11, 427 tests) but:
- UI/UX was ugly and clunky (the thing the defense sees first)
- The exams section had many, many failures
- The study bot did not work at all
- Screens were missing, backend incomplete

**Policy:** we never merge that branch. We *read* it as a reference for entity shapes, protocol verbs, SQL, and test fixtures — then rewrite clean in our new structure. Anything copied must pass our review + tests.

### 1.3 Biggest failures to kill (from team retro)
1. Exams: multiple-correct warning missing, edited questions not propagating, timer/extension broken, exam stayed open after timer end, students could see correct answers, coordinator couldn't view the exam properly
2. Bot: completely non-functional
3. No sessions saved, weak passwords in DB, no notification center, no server UI, no automatic network detection, no event bus, non-responsive UI, no test coverage, bad team split

Every one of these has an explicit epic/task in TODO.md.

---

## 2. Target architecture (summary — full detail in ARCHITECTURE.md)

| Layer | Choice | Why |
|---|---|---|
| Language / build | **Java 21 LTS**, Maven, shade → 2 fat JARs | Assignment requires Java + jar on 2 machines; 21 is current LTS |
| UI | **JavaFX 21 + FXML** + **AtlantaFX** theme base + **Ikonli** icons + native animations | Modern flat look for free, MIT-licensed, themeable light/dark + accent palettes |
| Client events | **EventBus (greenrobot 3.3.1)** — server pushes arrive as typed events on the FX thread | No polling (NFR-18), screens decoupled from network |
| Networking | Vendored **OCSF** behind `IClientConnection`; upgraded typed protocol v2 (request IDs + server-push verbs) | Keeps the graded Adapter, adds correlation + real-time push |
| Server | Fat server: `Router → Service → Repository`; feature-based packages | Single trusted gatekeeper, unit-testable services |
| Database | **MySQL 8** + **Hibernate 6.6** (JPA) + **HikariCP** + **Flyway** versioned migrations + seed dataset | Relational (S-41), reproducible schema, enterprise signal |
| Auth | **BCrypt** password hashes, single-session enforcement, role+course authorization guards | T-16, S-38, "they can't break it" |
| Bot | Server-side provider adapter: **DeepSeek** (OpenAI-compatible REST) primary → **Anthropic Claude** (official Java SDK) fallback; sources parsed with PDFBox/POI; sessions persisted as JSON | S-27 "use an existing bot with an API"; exam content never enters the model context |
| Concurrency | Server-authoritative timers, optimistic versioning + advisory **edit locks** with live "X is editing" indicators, single login per user | T-7, T-16, race-condition proofing |
| Testing | JUnit 5 + Mockito + AssertJ + TestFX + H2/MySQL, **JaCoCo ≥ 90%** gate | Team goal |
| CI | **GitHub Actions**: build → test (MySQL service) → coverage gate → package JARs as artifacts | Every PR proven green |

Design patterns we will name and defend: 3-tier layered, DAO/Repository, DTO, Command (protocol), Facade (services), Adapter (network, bot providers), Observer/Pub-Sub (EventBus + server push), Singleton (ScreenManager, SessionFactory), Template Method (screen lifecycle, test bases), Strategy (validators, report dimensions, bot providers), State (exam/execution/grade lifecycles), Factory (screen/dialog creation), Builder (exam auto-generation).

---

## 3. Product pillars

1. **UX that wins the defense.** Modern school-appropriate look; light/dark mode + user-selectable accent palettes; navbar shell with notification center; smooth micro-animations; responsive at 3 window sizes; lists, progress feedback, success/error toasts everywhere (NFR-21); illustrations for courses/empty states.
2. **An exams pipeline that cannot be broken.** Question bank → build → approve → release → take → extend → auto-grade → approve grade → results/statistics, with versioning at every step, server-authoritative time, auto-submit at expiry, and every edge case in the PRD catalog handled with both a backend answer and a UI answer.
3. **A bot that actually works.** Provider fallback, graceful "no answer" message (S-32), lockout during active exams, persisted JSON sessions with history views for student (own) and teacher (anonymized aggregate), and a hard guarantee: exam contents never reach the model.
4. **Real-time everywhere.** Server pushes (notifications, edit locks, timer extensions, approvals, grade releases) — no user-initiated refresh (NFR-18).
5. **Provable quality.** 90%+ coverage, CI gate, documented patterns/decisions/problems for the submission doc and defense Q&A.

---

## 4. Phases & milestones

Dates get anchored once the redo defense date is confirmed; sequencing is fixed. Each milestone ends with a working, demoable build (`mvn clean package` → run both JARs).

| # | Milestone | Contents | Exit criteria |
|---|---|---|---|
| **M0** | Foundation (week 1) | Repo restructure, Java 21/JavaFX 21 upgrade, deps, Flyway, CI pipeline, protocol v2 skeleton, docs in repo | CI green; client connects to server on 2 machines; coverage gate wired |
| **M1** | Platform core (week 1–2) | Design system (themes, palettes, components, nav shell), EventBus, Router/Service/Repo skeleton, auth + login + single-session, seed v1 | Login demo with full theming; 2 clients, duplicate login rejected |
| **M2** | Question bank (week 2–3) | Full T-2 + versioning + images + warnings + edit locks | All bank scenarios pass acceptance tests |
| **M3** | Exam build & approve (week 3–4) | T-3 manual+auto, T-4 approval flow, exam versioning, notifications v1 | Coordinator approves/rejects with reason; teacher notified live |
| **M4** | Release & take exam (week 4–5) | T-5, T-6: windows, codes, server timer, autosave, auto-submit, bot lockout hook | Two students take an exam concurrently; timer expiry force-submits |
| **M5** | Extend, grade, results (week 5–6) | T-7 live extension, T-8 grading flow, T-9/T-10 results + histogram, stored statistics | Full pipeline end-to-end on seeded data |
| **M6** | Principal & reports + Bot (week 6–7) | T-11, T-12 generic report engine; T-13/T-14 bot with fallback + sessions | Bot answers from uploaded PDF; reports compare across teacher/course/student |
| **M7** | Hardening (week 7–8) | Edge-case catalog sweep, race-condition tests, server console polish, seed v2 (rich dataset), coverage to 90%+ | "Try to break it" checklist passes; JaCoCo gate ≥90% |
| **M8** | Submission & defense (final week) | Acceptance-test results table, submission doc (Word→PDF), G<Num>_Client/Server JARs, demo script, dry-run defenses ×2 | Zip per spec; two full rehearsals on 2 physical machines |

**Rule:** required [T] scenarios always outrank nice-to-haves. Extras (calendar/upcoming, messaging) only enter after M7 is green.

---

## 5. Working agreements

- **Branching:** `main` protected; feature branches `feat/<epic>-<name>`; PR + 1 review (lead reviews all; lead's PRs reviewed by a member); CI must be green to merge.
- **Definition of Done (every task):** code + tests (unit; integration where it touches protocol/DB) + coverage not decreased + FXML/CSS follows design system + edge cases from PRD handled + docs updated (DECISIONS.md if a decision was made).
- **Contracts first:** protocol verbs + DTOs are frozen by the lead *before* a feature starts; client-side work develops against `FakeClientConnection`, server-side against mocked repositories — the two meet at an integration test.
- **No silent scope changes:** anything not in TODO.md gets added there first, with an owner.
- **Demo data discipline:** every feature must look good on the seed dataset — if a screen looks empty or fake, the seed is extended in the same PR.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Redo defense date arrives early | Milestones ordered so M4 already beats the old system; each milestone is demoable |
| Bot APIs flaky on defense day | Provider fallback chain + cached "sources summary" + graceful S-32 message; demo rehearsed offline with a recorded fallback path; both API keys tested the morning of |
| Teammates slower than lead | Split gives them self-contained vertical features behind frozen contracts; lead can absorb any epic without merge conflicts (feature-based packages) |
| JavaFX packaging breaks on the demo machine | M0 already produces double-clickable JARs; test on Windows target machine at every milestone, not at the end |
| 90% coverage becomes a time sink | Coverage is enforced from M0 (small codebase) so it never becomes a backfill project; JaCoCo excludes vendored `ocsf/**` and generated FXML glue |
| Concurrent-edit / timer race bugs | Dedicated concurrency integration tests (two clients, scripted interleavings) in E18/E21 — written when the feature lands, not in hardening |

---

## 7. Submission deliverables (Assignment 3)

Zip `G<GroupNum>_Assignment3` containing:
1. **Document** (Word, exported to PDF): cover page (group number, names, IDs); per-member responsibilities; **acceptance-test results table** (per the מתווה scenarios 1–21, with any bugs found + which test case exposed them); answer to "describe a design/coding problem you hit and how you solved it" (we will maintain a running `PROBLEMS.md` log so this writes itself).
2. **`G<Num>_Client.jar`** and **`G<Num>_Server.jar`** — double-clickable *and* terminal-runnable (server run in terminal for logs at the defense; client double-clicked).

Defense: server on one machine (terminal, rich structured logs), clients on another; demo script follows the acceptance-test table in order, plus a "try to break it" segment we invite them to attempt.
