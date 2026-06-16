# HSTS — Demo Guide & Presentation Script

**Audience:** academic examiners / technical reviewers
**Goal of this document:** run the prototype flawlessly, then tell the story of how
this lean prototype scales into enterprise infrastructure.

**One-line thesis:** *We deliberately built a lean, fully-traceable prototype on an
architecture that is already shaped — pattern by pattern — to scale into a
containerized, multi-provider AI platform without rewrites.*

---

## Part 1 — Live Demo (the 3-minute run)

### Step 1 — Ensure local MySQL is running
```bash
sudo service mysql start          # WSL / Debian-Ubuntu
```
(One-time setup, if not already seeded:)
```bash
mysql -u root -p < src/main/resources/schema.sql
mysql -u root -p < src/main/resources/seed.sql
```

### Step 2 — Double-click the Fat JAR
Double-click **`target/hsts-prototype.jar`** (or `java -jar target/hsts-prototype.jar`).

That single action boots the **entire stack** in one process:
1. starts the **Fat Server** (`ServerMain`) on a background daemon thread, port **5555**;
2. waits ~1 second for the OCSF socket to bind;
3. launches the **JavaFX client**, which connects to `localhost:5555` and auto-loads the questions.

> **Say this out loud:** *"Although it launches with one click for convenience, this
> is a true Thin Client / Fat Server system — the server is an independent tier that
> could just as easily run on a remote host serving many clients."*

### Step 3 — Demonstrate the CRUD round trip
1. **View** — the list of 6 questions loads from the server (status: *Done.*).
2. **Select** — click a question; it fills the editor.
3. **Edit** — change the question text and/or answer.
4. **Update** — click **Save Update** → `UPDATE_QUESTION` travels over OCSF.
5. **Verify** — the server persists to MySQL and returns the refreshed list; the UI re-renders.
6. **Prove persistence** — relaunch and show the change survived.

**Point at the console** during the edit — the live `[HSTSServer]` log lines
(`GET_ALL_QUESTIONS`, `UPDATE_QUESTION id=…: updated`) are the gatekeeper in action.

---

## Part 2 — The Enterprise Scaling Roadmap

> This is the heart of the pitch. Each prototype decision below was made *because*
> of where it leads. Nothing here requires throwing away what we built.

### 2.1 — Containerize the Fat Server with Docker, orchestrate with Kubernetes

**Today:** the Fat Server is a single Java process bound to `localhost:5555`.

**The evolution:**
- **Dockerize the server tier.** The server has zero UI dependencies, so it packages
  into a slim image (`eclipse-temurin:17-jre`) exposing port 5555. The client stays a
  desktop artifact; only the server containerizes — *exactly* the boundary the Thin
  Client / Fat Server split already gave us.
- **Externalize config.** `DatabaseConfig`'s constants become environment variables
  (`DB_URL`, `DB_USER`, `DB_PASSWORD`) injected by the container — the class is already
  structured around a single config surface, so this is a swap, not a refactor.
- **Orchestrate with Kubernetes.** Run the server as a `Deployment` with N replicas
  behind a `Service` (load balancer). Because clients connect through the **`IClientConnection`
  adapter**, pointing them at a K8s ingress/LoadBalancer is a host change in one class —
  no UI code moves.
- **Scale & resilience.** K8s gives us horizontal auto-scaling (HPA on CPU/connections),
  rolling deploys, liveness/readiness probes, and self-healing — the standard story for
  a stateless request-routing tier.

```
        ┌────────────┐     OCSF / future gRPC      ┌──────────────── Kubernetes ────────────────┐
        │ JavaFX      │ ─────────────────────────▶ │  Service (LB)                              │
        │ Thin Client │                            │   ├── Fat Server pod (replica 1)           │
        └────────────┘                            │   ├── Fat Server pod (replica 2)           │
                                                   │   └── Fat Server pod (replica N)           │
                                                   │            │                               │
                                                   │            ▼                               │
                                                   │     MySQL (StatefulSet / managed DB)       │
                                                   └────────────────────────────────────────────┘
```

### 2.2 — Activate the `JSON` (JSONB-equivalent) column for AI metadata

**Today:** the `Questions` schema carries a commented-out `ai_metadata JSON` column and
the DAO is shaped to accept it.

**The evolution:** uncomment the column and start writing structured AI provenance per row:
- **Prompt provenance** — which prompt template/version generated or graded the question.
- **Token usage & cost** — prompt/completion tokens, model, estimated cost per call.
- **Provider & model** — e.g. `{"provider":"anthropic","model":"claude-opus-4-8"}`.
- **Quality signals** — difficulty score, confidence, human-review status.

```json
{
  "provider": "anthropic",
  "model": "claude-opus-4-8",
  "prompt_version": "grader-v3",
  "tokens": { "prompt": 412, "completion": 88 },
  "generated_at": "2026-06-16T10:00:00Z",
  "review_status": "auto-approved"
}
```

MySQL's native `JSON` type is the JSONB-equivalent: it lets us store flexible,
schema-light AI metadata next to relational columns and query into it
(`JSON_EXTRACT`) without a second datastore. **Why it matters:** auditability and cost
control — every AI-touched row carries its own provenance and billing trail.

### 2.3 — Evolve the Fat Server into a multi-provider LLM Gatekeeper

**Today:** the Fat Server is the single choke point for all data access.

**The evolution:** that same choke point becomes the **secure AI gatekeeper**. The client
never holds API keys or crafts raw provider prompts — it asks the server, and the server
decides everything:
- **Multi-provider routing** — a provider-abstraction layer fronts OpenAI, Anthropic
  (Claude), and others; route by capability, cost, or availability, with failover.
  *(For new AI features we default to the latest, most capable Claude models.)*
- **Rate limiting & quotas** — enforced centrally per user/tenant; no client can exhaust
  the budget.
- **Prompt-injection defense** — all model input is validated and sanitized server-side;
  system prompts and tool access live on the trusted tier, never the client.
- **Observability & cost** — every call logs tokens/cost into the `ai_metadata` JSON column
  (§2.2), closing the loop between the gatekeeper and the audit trail.

> **The clincher:** *"The architecture that makes this prototype simple — one trusted
> server mediating every request — is exactly the architecture a secure AI platform
> requires. We didn't build a toy that we'll throw away; we built the seed of the real system."*

---

## Part 3 — Quick reference: which pattern unlocks which scale step

| Prototype decision | Enterprise payoff |
|---|---|
| Thin Client / Fat Server split | Only the server containerizes; client untouched |
| `IClientConnection` **Adapter** over OCSF | Swap OCSF → gRPC/REST for K8s ingress in one class |
| **DAO** isolates all SQL | Add `ai_metadata` JSON + new queries without touching callers |
| Fat Server as single choke point | Becomes the secure LLM gatekeeper unchanged in shape |
| `DatabaseConfig` single config surface | Becomes 12-factor env-var config for containers |

---

## Troubleshooting (live-demo safety net)

| Symptom | Fix |
|---|---|
| "Connection failed" alert | MySQL down, or port 5555 already taken — close the old instance. |
| `Access denied for user` | Fix credentials in `DatabaseConfig.java`. |
| Empty list / "No questions" | Re-run `schema.sql` + `seed.sql`. |
| Slow machine, list doesn't auto-load | Increase `SERVER_BOOT_DELAY_MS` in `Launcher.java`. |
| JAR won't launch | Confirm Java 17 and that the build's JavaFX natives match your OS. |
