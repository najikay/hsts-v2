# HSTS v2 — Decision Log (ADRs)

One entry per significant decision. Format: context → decision → consequences. This file + `PROBLEMS.md` feed the submission doc and defense Q&A directly. Add a new numbered entry whenever a real choice is made; never rewrite history — supersede.

---

## ADR-001 — Rebuild from the prototype, mine the failed final as reference only
**Context.** Prototype (`main`) got a good grade: sound 3-tier thin-client/fat-server shape, small and clean. The failed final (`person5-ui`) has volume (services, DAOs, V1–V11 schema, 427 tests) but failed on UI/UX, exam correctness, and a dead bot; its quality is untrusted.
**Decision.** Start v2 from the prototype's architecture. `person5-ui` is read-only reference — anything reused is rewritten into the new structure and must pass our tests/review.
**Consequences.** More rewriting, but no inherited bugs and no archaeology during the defense; entity/verb naming can still borrow the good parts.

## ADR-002 — Java 21 LTS + JavaFX 21
Prototype was Java 17/JavaFX 17. Move to current LTS: virtual-thread-era JVM, newer JavaFX CSS/behavior fixes, and "we keep the stack current" as a defense point. Cost: verify shade + double-click on the demo machines at M0, not later.

## ADR-003 — Keep vendored OCSF behind the Adapter; upgrade the protocol, not the transport
Swapping to REST/WebSocket would burn weeks and lose the course-expected OCSF. Instead: `Message` v2 with `requestId` correlation (client futures), typed DTO payloads only, and a first-class server-push channel. The `IClientConnection` adapter stays — the phase-2 (internet) story is "one new adapter class".

## ADR-004 — Hibernate 6.6 (JPA) + HikariCP + Flyway
JDBC DAOs (prototype) vs ORM: we choose JPA entities + repositories for `@Version` optimistic locking for free, less hand-written SQL surface, and stronger design-pattern narrative; Flyway gives reproducible schema on any machine (NFR-17, fresh-machine demo). Raw JPQL/projections wherever ORM would be clumsy (statistics, take-exam projection). Risk (ORM opacity) mitigated by repo-level tests against real MySQL.

## ADR-005 — BCrypt for passwords + throttling + single session
`at.favre.lib:bcrypt`, cost 12, per-user salt (v1 stored weakly hashed passwords). Generic login errors, 5-failure throttle, T-16 single login enforced in SessionManager. No password CRUD in-app (S-4 external user management) — seeded only.

## ADR-006 — UI stack: AtlantaFX + Ikonli + native animations; no Lottie
AtlantaFX (MIT) supplies a modern flat theme with light/dark; we add an accent-token layer for 5 selectable palettes. Ikonli material icons. Animations via JavaFX transitions wrapped in one `Animations` utility (≤250ms rule) — there is no production-quality Lottie runtime for JavaFX, so "delight" comes from micro-animations + recolored unDraw illustrations, which cannot fail at runtime.

## ADR-007 — greenrobot EventBus on the client; server push via OCSF `sendToClient`
v1's missing event bus made screens poll and couple to networking. Client: EventBus 3.3.1 (annotation-based, proven) with all posts marshalled to the FX thread at one crossing point. Server: no bus needed — `PushGateway` over the SessionManager map is the whole "server → interested clients" story. Two mechanisms, each the simplest that works.

## ADR-008 — Concurrent editing: advisory edit locks (UX) + optimistic versioning (correctness)
Requirement: "a teacher must *see* that another teacher is editing" AND a real backend answer. Pessimistic DB locks would pin rows to socket lifetimes — fragile. So: in-memory `EditLockService` (TTL + heartbeat, pushes `LOCK_CHANGED` so the second teacher gets a live read-only banner) for the experience, and JPA `@Version` as the correctness backstop — any stale write, however it slips through, is rejected with a friendly conflict dialog. Locks die with disconnects; state is reconstructible; no DB deadlocks possible.

## ADR-009 — Bot: provider-adapter chain, server-side only, exam data structurally out of reach
S-27 forbids building a bot; we integrate APIs. `BotProvider` interface with DeepSeek (OpenAI-compatible REST via `java.net.http` — cheap primary) and Anthropic (official `anthropic-java` SDK, `claude-opus-5` default, configurable) as fallback, then the S-32 "no answer" message. Keys only on the server. Context = extracted source text + course bank questions (S-28) — the bot module has no dependency on exam repositories, so leaking exam contents is a compile-time impossibility, not a prompt hope. Guardrail prompt additionally scopes to course material and rejects instructions embedded in documents. Sessions persisted as JSON transcripts (S-33) enabling history + anonymized analytics (S-34).

## ADR-010 — Server-authoritative time
All exam timing lives on the server (`TimerService`, one scheduled executor): start at ID entry (S-18), expiry force-submits transactionally even if the client vanished (v1 bug: exam stayed open), extensions reschedule and push. The client countdown is cosmetic and periodically re-synced. Server reboot re-arms timers from DB.

## ADR-011 — Exam ≠ Execution; versions are immutable
The single biggest modeling consequence of the spec (S-2, S-19, S-21): `Exam` → versioned definitions; `ExamExecution` → each release with its own window/code/extension/participants/stats. Question and exam edits create immutable versions; approval and scheduling bind to a version (S-14). Grades reference the exact question versions answered — history can never be corrupted by later edits (v1 bug: edited questions mutated past exams).

## ADR-012 — Multiple correct answers: allow with warning + confirmation
Spec implies one correct answer; reality (and our retro) says legit questions sometimes have two. Editor permits marking several correct but interposes the WarnConfirm dialog; grading counts any marked-correct selection as correct; the same WarnConfirm component generalizes to every "legal but unusual" action in the app. Documented so the defense sees it as deliberate design, not a bug.

## ADR-013 — Feature-based packages
`client/features/x`, `server/features/x`, `common/dto/x` — three people work without merge collisions, the structure mirrors the requirement list, and NFR-19 flexibility is demonstrable ("adding a feature touches one new package + one Verb group").

## ADR-014 — Coverage gate ≥90% from day one; TestFX only for smoke
Coverage is enforced in `mvn verify` starting at M0 so it shapes design (logic in session/service classes, not controllers) instead of becoming a backfill project. UI logic lives in testable session classes; TestFX covers boot + one happy path only — chasing FXML glue coverage is explicitly out.

## ADR-015 — Cloudflare domain: not used for the assignment
Phase 1 is LAN-only by spec (S-42). The owned domain is noted as a phase-2 asset (tunnel/hosted bot proxy) in the defense's "future work" slide — introducing it now adds risk with zero graded value.

---

*Supersede pattern: "ADR-0XX superseded by ADR-0YY (date, reason)".*
