# HSTS — High School Test System

**Group 12-1 · Software Engineering 203.3140 · University of Haifa · Spring 2026**

A distributed exam-management system for a high school, built as a **3-tier client–server
application**: JavaFX desktop clients that only render and ask, one fat server that owns every
rule and every clock, and MySQL behind it. Everything runs over a local TCP/IP network from two
plain JARs.

What it does, end to end: teachers build a **versioned question bank** and compose exams
(manually, or auto-generated from topic/difficulty criteria), a **subject coordinator approves**
each exam version against the exact paper a student would see, teachers **release** approved
versions under 4-character codes, students **sit exams under a server-owned timer** (auto-save,
resume after a crash, forced hand-in at the bell), **grading is automatic** with teacher review
and audited overrides, and every role sees exactly the statistics it is allowed to see, down to
a histogram with mean/median/±σ overlays and a cross-sitting **report engine**. An **AI study
bot** answers course questions from teacher-uploaded material (PDF/Word/free text plus the
question bank), guard-railed so exam data is structurally unreachable. Every state change is
**pushed** — there is no refresh button anywhere in the application, by requirement.

**Stack:** Java 21 · JavaFX 21.0.4 + AtlantaFX 2.0.1 (design-token theme layer: light/dark/system,
five accent palettes) · vendored OCSF behind an adapter · request-correlated wire protocol with
server push · Hibernate 6.6.4 + HikariCP 5.1 + Flyway 10.20 over MySQL 8.4 · BCrypt ·
greenrobot EventBus (client) · DeepSeek + Anthropic SDK (bot providers, server-side only) ·
JUnit 5 / AssertJ / TestFX + Monocle · Maven + Shade (two fat JARs) · GitHub Actions CI running
the full suite against live MySQL on every push.

---

## Quick start (one machine)

Prerequisites: **JDK 21**, **MySQL 8** (a `root`/`root` default is assumed; change it in
`server.properties`), Maven via the included wrapper.

```bash
# 1. One-time: an empty database (the server migrates it itself on first start)
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS hsts_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

# 2. Build both fat JARs
./mvnw -DskipTests clean package        # Windows: .\mvnw -DskipTests clean package

# 3. Terminal 1: the server (opens the server console window; Flyway runs V1..V7 automatically)
java -jar target/hsts-server.jar
#    terminal-only:  java -jar target/hsts-server.jar --headless
#    other flags:    --port <n>   --no-discovery

# 4. Load the demo data: press "Load demo data if missing" once on the server console
#    (or: java -cp target/hsts-server.jar server.db.seed.SeedMain   [--reseed to wipe & reload])

# 5. Terminal 2: a client
java -jar target/hsts-client.jar
```

The client discovers the server by UDP broadcast and lands on Login. Every demo account uses
password **`demo123`** — the roster is in [`docs/DEMO_ACCOUNTS.md`](docs/DEMO_ACCOUNTS.md);
`dana.cohen` (teacher), `rina.barak` (coordinator), `maya.levi` (student) and `principal.avia`
are the four fastest doors in.

## Two machines (the deployment the course grades)

1. Build on both machines from the **same commit** (the wire protocol is serialized Java; mixed
   builds do not speak).
2. On the server machine: allow TCP 5555 and the discovery UDP port through the firewall
   ([`docs/DEMO_DAY.md`](docs/DEMO_DAY.md) §4.2 has the PowerShell), start the server, and read
   the address and server ID off the console — it prints them large.
3. On the client machine: launch the client; the discovery picker lists the server (name ·
   address · ID). Pick it, or use **change server** and type the address — manual entry is
   always one click away. First successful connect pins the server's ID; a changed ID later
   raises an explicit warning (trust-on-first-use).
4. A first connect through a firewall prompt may take a few seconds; the button says
   "Still trying…" while the dial (bounded at 15 s) works. A dead address fails with a plain
   sentence, never a spinner.

## Configuration

Both JARs read an optional properties file **beside the JAR** (falling back to bundled
defaults):

| File | Keys | Notes |
|---|---|---|
| `client.properties` | `server.host`, `server.port` | The client also remembers the last successful server on its own |
| `server.properties` | `db.user`, `db.password`, `bot.deepseek.key`, `bot.anthropic.key` | **Gitignored.** Bot keys may come from `HSTS_DEEPSEEK_KEY` / `HSTS_ANTHROPIC_KEY` instead (environment wins). A missing key is a supported state: that provider reports unconfigured and the chain moves on; with no provider the bot answers its fallback sentence. Keys exist only on the server, never in the client, never in git. |

## Architecture in one screen

| Tier | Owns | Where |
|---|---|---|
| **Presentation** | Rendering and intent only; zero rules | `client.core` (router, screen manager), `client.ui` (one design system: tables, chips, charts, dialogs, theme), `client.features.*` (a screen + an FX-free session per feature), `client.net` |
| **Logic (fat server)** | Every rule, every clock, every authorization | `server.core` (`HSTSServer`, `MessageRouter`, `SessionManager`), `server.features.*` (auth, bank, exambuild, approval, release, exam, grading, results, reports, bot, notify, locks) |
| **Data** | Persistence only | `server.db` — Hibernate entities, one-query-per-need repositories, Flyway `V1__core` … `V7__notifications` |
| **Common** | Wire types only | `common.protocol` (`Message`, `Verb`), `common.dto.*` — Serializable records; the student-facing shapes **structurally cannot carry an answer key** |

Highlights an examiner will ask about: server-authoritative timers (extensions push live to
every sitting student); advisory **edit locks** with live "being edited by" badges and an
optimistic-version backstop; a **Strategy**-based report engine where a new report is one class;
a provider-adapter **chain** for the bot; **immutable versioning** for questions and exams
(released sittings stay pinned to the version they were built from); notifications persisted
and pushed. The full story, including the phase-2 (internet) readiness argument, is in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); design decisions in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

## Testing

~7,100 tests run in the ordinary build: unit and service tests against live MySQL
(Flyway-migrated schemas per run), FX-free session tests, TestFX interaction tests on the real
toolkit (headless Monocle) driving **real gestures** — popup clicks, keyboard traversal — plus
guard tests that fail the build on structural drift (answer-key leak guards, thread-seam
guards, icon and copy guards, a truncation guard that walks every screen at two window sizes).
JaCoCo enforces the coverage gate.

```bash
HSTS_REQUIRE_MYSQL=true ./mvnw clean verify
```

CI (GitHub Actions) runs exactly that against MySQL 8.4 on every push.

## Repository map

| Path | What |
|---|---|
| `docs/PRD.md` | The requirements, with every course-spec id and every binding ruling |
| `docs/ARCHITECTURE.md` / `docs/DECISIONS.md` | Design and its reasons |
| `docs/TRACEABILITY.md` | Requirement → class → test → acceptance case, with honest statuses |
| `docs/ACCEPTANCE_TESTS.md` | All 21 course scenarios walked, results and bug ledger (B-1…) |
| `docs/UI-REGISTER.md` | Every finding from manual rounds and sweeps (U-1…), with root causes |
| `docs/DEMO_WALKTHROUGH.md` | The 21 defence marks as an ordered, copy-paste-ready checklist |
| `docs/DEMO_DAY.md` / `docs/DEMO_SCRIPT.md` / `docs/DEMO_PREP.md` | Machine prep · the narrated demo · who-is-who and paste data |
| `docs/contracts/` | Frozen wire contracts and their dated amendments |

## Submission build

```bash
./mvnw -Djar.prefix=G12-1 -DskipTests clean package   # names the JARs for the submission zip
```

## Team — Group 12-1

| Member | Email |
|---|---|
| Naji Kayal | najikayal4@gmail.com |
| Omar Wahbi | omar_w231@outlook.com |
| Amjad Abd Alrahim | aabdel25@campus.haifa.ac.il |
