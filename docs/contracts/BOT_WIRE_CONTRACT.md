# E16 study bot wire contract — FROZEN v1

**Status: DRAFT, awaiting lead freeze.** Written alongside the E16 implementation; nothing outside
this feature consumes it yet, so it is still cheap to change. Once frozen, additive changes only
(new optional fields, new verbs) with any rename or retype recorded here as an amendment.

Package: `common/dto/bot` (all types are `Serializable` records, wire-safe, no entity types).
Verbs live in `common/protocol/Verb.java`, grouped under a `Study bot (E16)` section.
Requirement ids: PRD **F12**, **S-28**, **S-30**, **S-31**, **S-32**, **S-33**, **S-34**, **C-4**;
architecture **ADR-009** (provider chain) and **ADR-018** (bot lockout scope).

---

## 1. The security statement this contract encodes ⚑

Three rules bind every verb below. They are not conventions; each has a test that fails the build.

**F12.8 — the bot cannot see exam data.** The model's context contains course source chunks and
course question-bank questions (S-28) and nothing else. This is enforced at compile scope, not by
filtering: `server/features/bot` has no reference to `ExamRepository`, `ExecutionRepository`,
`AttemptRepository`, `GradeRepository`, the exam/grade entities, or `server/features/grading`.
`BotIsolationGuardTest` scans the compiled feature package's constant pools and fails on any of
them. The single sanctioned exception is `AttemptTracker` / `ActiveAttempt`, which is the C-4 seam
(below) and exposes no paper, answer, code or grade.

**The bank read carries no answer key — lead's ruling.** S-28 permits the question bank as study
material, and the specification asks for "the questions from the question bank". So
`BotBankQuestion(displayId, text, answer1..answer4)` carries the stem and its four options with
**no marking of which is correct**, and `QuestionRepository.findBankForBot` does not select
`correct_answer` at all. A study bot that hands out answer keys for material that may be on next
week's paper defeats its own purpose. Because that read carries no key it needs none of the
sanctioned `ForAuthoring` / `ForGrading` suffixes — and `CorrectnessLeakGuardTest` confirms
that rather than taking it on trust.

**S-34 — the teacher's aggregate is anonymous by construction.** `BotAnalytics`,
`BotActivityPoint` and `BotTopQuestion` have no field capable of holding a user id, a name or a
session id, and the reads behind them (`countMessages`, `findActivity`, `findRecentQuestions`)
never select `bot_messages.student_id`. `BotAnalyticsIdentityGuardTest` walks the record graph
reachable from `BotAnalytics` and fails on any identity-shaped component name.

---

## 2. Roles and scope — the rules every handler enforces

- **Student verbs** (`BOT_ASK`, `BOT_SESSIONS_GET`, `BOT_SESSION_GET`): any authenticated caller,
  scoped to **herself** in the query. **No student verb carries a user id, ever** — the caller is
  the session bound to the socket (P-5), so an id in one of these payloads could only be a
  classmate's. Another student's session id answers `NOT_FOUND`, indistinguishably from one that
  never existed.
- **Teacher verbs** (`BOT_MANAGER_GET`, `BOT_CREATE`, `BOT_ACTIVE_SET`, `BOT_SOURCE_ADD`,
  `BOT_SOURCE_REMOVE`, `BOT_ANALYTICS_GET`): `Authorization.requireRole(caller, TEACHER,
  COORDINATOR)` **plus** taught-course ownership resolved from `CourseRepository.teaches`, never
  from the payload.
- **Course codes, not bot ids.** One bot per course (S-30), so every verb is addressed by course
  code. The one exception is `BOT_SESSION_GET`, where the student names one of her own
  conversations, and `SourceRemoveRequest`, which names a row **and** its course so the server can
  check both.

---

## 3. Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `BOT_ASK` | student | `BotAskRequest` | `BotAnswer` **or** `BotIntegrityNotice` |
| `BOT_SESSIONS_GET` | student | `BotCourseRequest` | `BotSessionsPage` |
| `BOT_SESSION_GET` | student | `BotSessionRequest` | `BotConversation` |
| `BOT_MANAGER_GET` | teacher | `BotCourseRequest` | `BotManagerPage` |
| `BOT_CREATE` | teacher | `BotCreateRequest` | `BotManagerPage` |
| `BOT_ACTIVE_SET` | teacher | `BotActiveRequest` | `BotManagerPage` |
| `BOT_SOURCE_ADD` | teacher | `SourceAddRequest` | `BotManagerPage` |
| `BOT_SOURCE_REMOVE` | teacher | `SourceRemoveRequest` | `BotManagerPage` |
| `BOT_ANALYTICS_GET` | teacher | `BotCourseRequest` | `BotAnalytics` |

No push verb. Nothing in this feature happens to a user who is not looking at it: the one
server-initiated message it causes is the **C-4 integrity alert**, which travels as an ordinary
`INTEGRITY_ALERT` notification through `Notifier` (raised inside `AttemptTracker`), and the
**co-teacher source notification**, which is a `BOT_SOURCE_CHANGED` notification. Both reuse
E17's channel rather than adding one.

**Every mutating teacher verb answers with a whole `BotManagerPage`,** freshly read, rather than
an acknowledgement. Same choice as the notification verbs: the screen re-renders from the server's
own read, so there is no window in which the table and the database disagree, and no refresh
button anywhere (NFR-18).

---

## 4. Error codes

| Situation | Code | Sentence source |
|---|---|---|
| Not enrolled in the course (S-31) | `FORBIDDEN` | `BotMessages.NOT_ENROLLED` |
| Course has no bot yet (student) | `NOT_FOUND` | `BotMessages.NO_BOT` |
| Bot switched off (F12.4) | `CONFLICT` | `BotMessages.BOT_INACTIVE` |
| C-4 same-course lockout | `CONFLICT` | `BotMessages.lockedOut(course, exam)` |
| Rate limit (E16.8) | `VALIDATION` | `BotMessages.TOO_FAST` |
| Empty / oversized question | `VALIDATION` | `BotMessages.QUESTION_EMPTY` / `QUESTION_TOO_LONG` |
| Session id that is not hers | `NOT_FOUND` | `BotMessages.SESSION_NOT_FOUND` |
| Teacher, wrong course (P-5) | `FORBIDDEN` | `BotMessages.NOT_YOUR_COURSE` |
| Unknown course code | `NOT_FOUND` | `BotMessages.NO_SUCH_COURSE` |
| Management verb, no bot yet | `NOT_FOUND` | `BotMessages.BOT_NOT_CREATED` |
| Source upload incomplete / too big | `VALIDATION` | `BotMessages.SOURCE_INCOMPLETE` / `SOURCE_TOO_LARGE` |
| Source will not parse (F12.2) | `VALIDATION` | the extractor's own sentence, written for the uploader |
| Source held by another teacher (E18.5) | `CONFLICT` | `BotMessages.SOURCE_LOCKED` |
| Source id not on this course's bot | `NOT_FOUND` | `BotMessages.SOURCE_NOT_FOUND` |

The client renders the server's sentence rather than substituting its own. That matters most for a
parse failure: "this PDF has no text in it, it may be a scan" is the only part of the answer a
teacher can act on.

**There is no error code for "no provider could answer".** S-32 is a successful response carrying
`BotAnswer.S32_FALLBACK`; see §6.

---

## 5. C-4, on the wire (ADR-018) ⚑

Two branches, and they are deliberately different shapes.

**Same course: refused.** A student sitting an exam of course X cannot use course X's bot.
`BOT_ASK` answers `CONFLICT` with `BotMessages.lockedOut(courseName, examName)`. Decided from
`AttemptTracker`'s view of her live attempts, so no field on the request can affect it — including
`integrityAcknowledged`, which is explicitly tested as unable to unlock this branch.

**Another course: asked, then allowed and reported.** `BOT_ASK` answers `OK` carrying a
`BotIntegrityNotice` instead of an answer. The client shows it as a calm `WarnConfirm` (once per
notice, never nagged), and re-sends the same question with `integrityAcknowledged = true`. Only
then does the ask proceed, and the service calls `AttemptTracker.reportCrossCourseBotUse`, which
raises the teacher's `INTEGRITY_ALERT` and flags her monitor row — once per attempt.

**Why a payload type rather than a third `CONFLICT`.** The client has to tell "confirm this" apart
from "you cannot do this", and matching on a sentence is not a contract. A client that does not
understand `BotIntegrityNotice` shows nothing and the ask does not proceed, which is the safe
direction.

**What `integrityAcknowledged` can and cannot buy.** It is the only field on this wire a client
could lie about, so: sending `true` without showing the notice does not unlock anything and does
not suppress anything — the alert is raised from the server's own view of her attempts either way.
All the flag decides is whether the ask proceeds now or comes back asking her to confirm. A client
that always sent `true` would have skipped its own warning and reported its user to her teacher.

---

## 6. S-32, and what the student never sees

When every provider in the chain fails, `BOT_ASK` answers **`OK`** with

> The bot could not answer that. Try rephrasing, or ask your teacher.

verbatim, as `BotAnswer.S32_FALLBACK`, and `isFallback()` is true. It is stored like any other
exchange, with `bot_messages.provider = "none"`.

**`BotAnswer` deliberately does not say which provider answered.** DeepSeek falling over and
Anthropic taking the question is not the student's problem, and a "degraded" badge would make it
hers. The provider is recorded per row and in one structured log line per ask
(`bot.answer provider=… latency_ms=…`), which is where the people who can act on it look.

---

## 7. DTOs (`common/dto/bot`)

**Asking**
- `BotSpeaker` — wire enum `STUDENT | BOT`; `wireName()` maps to the `"student"`/`"bot"` strings
  the stored transcript JSON uses.
- `BotAskRequest(String courseCode, Long sessionId, String question, boolean integrityAcknowledged)`
  — `sessionId` null (or non-positive, normalised to null) starts a new conversation; course code
  upper-cased; question trimmed; `MAX_QUESTION = 2000`.
- `BotAnswer(long sessionId, String question, String answer, Instant askedAt)` — a blank answer is
  normalised to `S32_FALLBACK` on both sides of the wire. `sessionId = 0` means "stored nowhere",
  which the client treats as not-resumable rather than as an error.
- `BotIntegrityNotice(String courseName, String message)` — see §5.

**History**
- `BotTurn(BotSpeaker speaker, String text, Instant at)`
- `BotConversation(long sessionId, String courseCode, String courseName, Instant startedAt,
  Instant updatedAt, List<BotTurn> turns)` — the transcript, word for word (S-33).
- `BotSessionRow(long sessionId, Instant startedAt, Instant updatedAt, int questionCount,
  String preview)` — `preview` is her first question, whitespace-collapsed, truncated to 90 chars.
- `BotSessionsPage(String courseCode, String courseName, List<BotSessionRow> sessions)` — newest
  first. A course with no bot answers an empty page rather than an error.
- `BotSessionRequest(long sessionId)`

**Management**
- `BotSourceKind` — wire enum `PDF | DOCX | TEXT`, deliberately separate from the entity's
  `BotSourceType`. `ofFileName` maps an extension, defaulting to `TEXT`.
- `BotProfile(long botId, String courseCode, String courseName, String name, boolean active)`
- `BotSourceRow(long sourceId, BotSourceKind kind, String title, String addedBy, Instant updatedAt,
  int version, int characters)` — **no bytes**; `addedBy` is a display name, not an id.
- `BotManagerPage(BotProfile bot, List<BotSourceRow> sources)` — `bot` is **null** for a course
  with no bot, which is an empty state the screen draws, not an error.
- `BotCourseRequest(String courseCode)`, `BotCreateRequest(String courseCode, String name)`,
  `BotActiveRequest(String courseCode, boolean active)`,
  `SourceRemoveRequest(String courseCode, long sourceId)`
- `SourceAddRequest(String courseCode, BotSourceKind kind, String title, byte[] content)` — bytes
  copied in and out; `toString` never prints them; `MAX_BYTES = 8 MiB`, `MAX_TITLE = 200`.

**Analytics (S-34 ⚑)**
- `BotAnalytics(String courseName, int totalQuestions, List<BotActivityPoint> activity,
  List<BotTopQuestion> frequent)`
- `BotActivityPoint(LocalDate day, int count)`
- `BotTopQuestion(String question, int count)` — `question` is a **normalised grouping key**
  (case-folded, whitespace-collapsed, trailing punctuation dropped), which is what makes the same
  question asked eleven ways one row and is also why it cannot be traced to whoever typed it.

---

## 8. Behaviour a client may rely on

- **`BOT_CREATE` is idempotent** (S-30). A second teacher of the same course gets the existing bot
  back and becomes a contributor; there is no `CONFLICT` for "already exists".
- **`BOT_ACTIVE_SET` is absolute, not a toggle.** Two co-teachers clicking the same switch a second
  apart agree with each other instead of undoing each other.
- **A source is parsed before any row is written** (F12.2). A failed parse writes nothing and
  notifies nobody.
- **`BOT_SOURCE_REMOVE` respects the advisory edit lock** (E18.5, `EntityRef.BOT_SOURCE`). A source
  another teacher is holding answers `CONFLICT`.
- **The transcript and the analytics row are dual-written in one transaction** (F12.9). A client
  that got a `sessionId` back can rely on both existing.
- **Rate limit**: 10 asks per student per rolling minute by default
  (`bot.rate.per.minute`), in memory, per server process.

---

## 9. Lead rulings at freeze (2026-08-20; independent verify 2582 tests, gate met)

1. **`BotIntegrityNotice` as an OK payload — APPROVED.** Three indistinguishable `CONFLICT`s
   would force clients to match on sentences, which breaks our own error-code discipline; the
   notice is a question, not a refusal, so `OK` is honest. The union payload stays the project's
   only one, documented here as deliberate.
2. **No wall-clock unlock time in the C-4 lockout sentence.** The brief assumed `ActiveAttempt`
   carries a deadline; it does not (it carries `startedAt`), and the only deadline this feature
   could reach would be the one captured at attempt start, which a teacher's extension (F7.1)
   silently invalidates. The sentence therefore says the lock lifts when she hands the exam in,
   which is true at every moment. **APPROVED as shipped**: a truth-stable sentence beats a clock
   that an extension can turn into a lie; no E10/E11 change will be made for this.
3. **`BotSourceRow.addedBy` as a display name — APPROVED.** No id travels where nothing acts on
   the person; a future filter affordance adds an id additively under this contract's rules.
4. **Per-process rate limit — APPROVED.** Phase 1 is single-server by architecture
   (ARCHITECTURE §10); a second instance is out of scope and documented as such.
