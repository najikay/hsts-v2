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

## ADR-012 — ~~Multiple correct answers: allow with warning + confirmation~~ *(superseded by ADR-016, 2026-08-18 — team lead decision: spec says exactly one)*

## ADR-016 — Exactly one correct answer, enforced; duplicate answers rejected
**Context.** The spec is explicit: a question has 4 answers and **a** correct answer (singular). An earlier idea to allow multiple correct answers behind a warning (ADR-012) contradicted it.
**Decision.** The editor uses a radio group (a second correct selection is impossible in the UI) and the server independently validates exactly-one-correct. Additionally, the 4 answers must be pairwise distinct — two answers identical word-for-word (compared after trimming and whitespace collapse, case-insensitive) are rejected, because a duplicated answer makes the "one correct answer" ambiguous for the student. Grading: correct ⇔ selection equals the correct answer.
**Consequences.** Simpler grading and no defense-time debate about spec deviation; the WarnConfirm component remains as a general pattern for other legal-but-unusual actions (unanswered submit, close-early, deletes).

## ADR-017 — Client fat-JAR uses a shade allow-list, not a deny-list
**Context.** Excluding only the named server-side artifacts (mysql, hibernate, flyway, poi, pdfbox, anthropic-java) still shipped ~30MB of their *transitive* dependencies (kotlin/okhttp/protobuf behind the Anthropic SDK, byte-buddy/antlr/jakarta behind Hibernate, commons/xmlbeans behind POI) into the client JAR.
**Decision.** The client shade config includes an explicit allow-list (project classes, JavaFX, AtlantaFX, Ikonli, EventBus, bcrypt, slf4j/logback, jackson); everything else is excluded by default. New client-side dependencies must be added to the list consciously.
**Consequences.** Client JAR 39MB → 13.3MB; a server-only dependency can never leak into the client by transitivity. Cost: adding a client dependency is a two-line change (dependency + allow-list entry) — documented here so nobody debugs a "class not found in client jar" blindly.

## ADR-013 — Feature-based packages
`client/features/x`, `server/features/x`, `common/dto/x` — three people work without merge collisions, the structure mirrors the requirement list, and NFR-19 flexibility is demonstrable ("adding a feature touches one new package + one Verb group").

## ADR-014 — Coverage gate ≥90% from day one; TestFX only for smoke
Coverage is enforced in `mvn verify` starting at M0 so it shapes design (logic in session/service classes, not controllers) instead of becoming a backfill project. UI logic lives in testable session classes; TestFX covers boot + one happy path only — chasing FXML glue coverage is explicitly out.

## ADR-015 — Cloudflare domain: not used for the assignment
Phase 1 is LAN-only by spec (S-42). The owned domain is noted as a phase-2 asset (tunnel/hosted bot proxy) in the defense's "future work" slide — introducing it now adds risk with zero graded value.

## ADR-018 — Bot lockout is per-course (spec-exact) with a cross-course integrity net
**Context.** The spec (§6.2) scopes bot unavailability to *the course whose exam is being taken*. A global lockout would be simpler but deviates from the requirement; a literal per-course lock leaves a hole — a student mid-exam can freely consult *another* course's bot.
**Decision.** Implement the lock exactly as specified (same-course bot blocked during the student's in-progress attempt, with a clear unlock-time message). For any other course's bot during an attempt: allow it, but show a one-time notice ("continuing will inform the exam's teacher"); on proceed, push a real-time possible-cheating notification to the teacher running the execution and flag the student's row in the live monitor with course + timestamp. Fired at most once per attempt per bot so the student experience isn't degraded.
**Consequences.** Requirement satisfied word-for-word; the unmentioned scenario is surfaced to the person who can act on it instead of being silently permitted or over-blocked; a strong defense talking point (spec fidelity + threat modeling). Cost: BotService needs live attempt-state lookup and one extra notification type.

## ADR-019 — LAN server discovery with TOFU fingerprint pinning (teammate proposal, accepted with corrections)
**Context.** Typing the server IP is the most error-prone step of a two-machine demo. A teammate proposed UDP broadcast discovery where servers reply {ip, port, fingerprint} and the console displays the fingerprint for the user to compare. Security review found the compare-on-screen step alone is spoofable: the responder answers anyone, so an attacker can learn the real fingerprint and replay it from their own IP.
**Decision.** Implement discovery as UX sugar with trust-on-first-use pinning: first successful connect pins {address, fingerprint}; auto-connect thereafter; any later fingerprint mismatch triggers a prominent warning requiring explicit confirmation. **Scope of the mechanism, stated precisely (2nd review round): pinning gives disambiguation and change detection across DHCP — it does NOT bind identity. The ID is copyable: an attacker who queries the server can replay it and the pin still matches. Anti-impersonation requires the pinned value to be a TLS certificate fingerprint (E19.12), costed at ~2–3 days on top of planned work, which would also encrypt credentials in transit (today they cross the LAN in cleartext — a known, documented phase-1 limitation under the assignment's LAN trust model). Decision: deferred behind a go/no-go gate at M6 — proceed only if (a) all [T] scenarios are green with ≥3 days slack + rehearsal time on the demo machines, AND (b) it passes the team's explainability rule (below); otherwise it is the phase-2 answer.**
**Team rule (lead decision): we only ship security we can explain — any security mechanism in the build must be explainable by each presenting member in under a minute; if it can't clear that bar, it stays out and is named honestly as a phase-2 item instead. Simple, understood security beats impressive, opaque security at a defense.** Manual IP entry with sensible defaults (client.properties → last server → localhost:5555) is always one click away and pre-filled — discovery failing (client-isolation networks block broadcast) never blocks connecting. Responder: own UDP port, nothing sensitive in replies, console toggle, malformed/flood packets ignored + logged.
**Consequences.** No IP typing in the demo (wow-moment), multiple dev servers coexist, honest defense claim: "discovery proposes candidates; the pinned fingerprint makes impersonation visible, not impossible — cryptographic binding via TLS cert fingerprints is the phase-2 upgrade." Cost: one new unauthenticated UDP surface, kept minimal and fuzz-tested; demo checklist must verify broadcast works on the venue network (hotspot fallback).
**Canned defense explanation (use verbatim, don't volunteer unless asked — goes into the E22.5 prep sheet):** *"The server ID lets the user tell our server apart from anything else that answers the broadcast, and the client alerts if a remembered server's ID ever changes. It's a label plus a change alarm — not a lock: a determined impersonator could copy the ID. Blocking that requires the ID to be a TLS certificate fingerprint, which we've designed and costed (~2–3 days, also encrypts credentials in transit) as the phase-2 upgrade; phase 1 stays within the assignment's LAN trust model."*

---

*Supersede pattern: "ADR-0XX superseded by ADR-0YY (date, reason)".*
