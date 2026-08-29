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
| `BOT_SOURCE_UPDATE` *(A1)* | teacher | `SourceUpdateRequest` | `BotManagerPage` |
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
  int version, int characters, String text)` — **no bytes**; `addedBy` is a display name, not an
  id. `text` is **A1** and is the pasted body of a `TEXT` source only, `null` for `PDF` and
  `DOCX`; `isEditable()` is what the screen switches on.
- `BotManagerPage(BotProfile bot, List<BotSourceRow> sources)` — `bot` is **null** for a course
  with no bot, which is an empty state the screen draws, not an error.
- `BotCourseRequest(String courseCode)`, `BotCreateRequest(String courseCode, String name)`,
  `BotActiveRequest(String courseCode, boolean active)`,
  `SourceRemoveRequest(String courseCode, long sourceId)`,
  `SourceUpdateRequest(String courseCode, long sourceId, BotSourceKind kind, String title,
  byte[] content)` **(A1)**
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

---

## 10. Additive amendments

Everything below was added **after** the 2026-08-20 freeze, under the additive-only rule: no verb
renamed, no payload component removed or reordered, no semantics changed for any existing field.

### A1 — `BOT_SOURCE_UPDATE`, and `BotSourceRow.text` (E16.9 / F12.3, B-21, added 2026-08-26, lead-ruled)

**What was missing.** PRD **F12.3** specifies "Sources list with add/**edit**/remove for any
teacher of the course; edit-locked (F10)", and acceptance case 13.4 asks for it. Nothing
implemented the middle verb: the frozen contract had `BOT_SOURCE_ADD` and `BOT_SOURCE_REMOVE`,
`BotData` had `addSource`/`removeSource`, and `BotManagerView` offered "Add a file", "Add text"
and "Remove". Correcting a typo meant deleting the row and re-adding it, which loses the source
id, its author, its `updated_at` and its version — and loses them **silently**, because the
remove notifies co-teachers as a removal and the re-add as an addition, so one correction reads
to a colleague as two unrelated events. **Ruled 2026-08-26: build it.** The current state matched
neither the PRD nor the acceptance document, and the second-order effect settled it — the
advisory lock on `EntityRef.BOT_SOURCE` is wired end to end and works (probed in 13.6), and until
now the only thing it could protect was a *remove*, so F10.2's "read-only view while another
teacher edits" had no editor to be read-only in.

**The verb.**

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `BOT_SOURCE_UPDATE` | teacher | `SourceUpdateRequest` | `BotManagerPage` |

`SourceUpdateRequest(String courseCode, long sourceId, BotSourceKind kind, String title,
byte[] content)` — the add request's shape plus the row it addresses. Deliberately its own record
and not a nullable-id variant of `SourceAddRequest`: an add creates and an update replaces, they
answer to different rules, and one record for both is how a handler ends up doing the wrong one.
Bytes copied in and out, `toString` never prints them, same `MAX_BYTES` and `MAX_TITLE`.

**Semantics.**

1. **The row survives.** Its id and its `added_by` are untouched; its domain `version` is bumped,
   so a stale extraction stays detectable. That is the whole difference from remove-and-re-add.
2. **Parse before write**, exactly as `BOT_SOURCE_ADD` does. A replacement that cannot be read
   answers `VALIDATION` with the extractor's own sentence and **leaves the stored source exactly
   as it was** rather than half-overwritten.
3. **Gate order is E6.14's: scope, then the lock, then the row.** The teaches-check runs first, so
   a teacher who does not teach the course is refused before anything about the source is read or
   reported; the advisory-lock consult runs second, inside the transaction. `BOT_SOURCE_REMOVE`
   consults its lock *before* its transaction, which is a shape that predates the ruling and is
   left alone; the new verb follows the write-path rule so that a `CONFLICT` cannot become a way
   for an outsider to learn that a source exists and who is holding it.
4. **The lock refusal names its holder.** `BotMessages.sourceLockedBy(name)` — *"Avi Mizrahi is
   editing this source right now. Wait for them to finish, or take over the edit from the
   banner."* — falling back to `BotMessages.SOURCE_LOCKED` when the lock service cannot say who.
   The caller has already passed the scope check by then, so the name gives away nothing she
   cannot see on the page, and it turns a wall into a colleague she can go and ask.
   `BotAdminService.SourceLocks` therefore answers `Optional<LockHolder>` rather than a boolean,
   in `EditLockGuard`'s own shape; `mayEdit` is retained as a default over it.
5. **Co-teachers are told**, through the same `BOT_SOURCE_CHANGED` notification an add or a remove
   raises, and the editor is not told about her own edit. No new notification type.
6. **Error codes**, all reusing §4's sentences: `VALIDATION` malformed, incomplete, oversized, or
   a replacement that will not parse · `FORBIDDEN` not your course · `NOT_FOUND` the course has no
   bot, or the source id does not belong to this course's bot · `CONFLICT` another teacher holds
   the advisory lock.

**`BotSourceRow` gains a component, appended last:** `String text`. It carries the pasted body of
a `TEXT` source so the Edit dialog opens on what is actually stored, and it is **null for `PDF`
and `DOCX`** — enforced in the record's own compact constructor, not by the caller. The "no bytes"
rule is intact and this is the reason for the asymmetry: a typed source is something a human wrote
and can sensibly re-open; a file row holds the *parse*, which is hundreds of kilobytes of no use
to a dialog. The seven-component constructor is retained and delegates with `null`.
`BotSourceRow.isEditable()` is what the screen switches on.

**So the manager screen offers Edit on free-text rows only, and that is stated rather than
quietly done.** Editing a file source could only ever mean choosing a replacement file, which is
what the existing chooser already does and is indistinguishable from Remove-then-Add except that
it keeps the id. That is a real difference and a smaller one than an "Edit" button on a PDF row
would imply, so file kinds keep Add and Remove. If the affordance is wanted later, the verb
already accepts any `BotSourceKind` and the server already handles it — only the button is
missing.

### A2 — considered and NOT taken: a bot summary verb (U-26, 2026-08-29)

**The ask.** Manual round 3 found the teacher's Bot Manager showing one course's bot behind a nav
parameter, so `dana.cohen` (Algebra 11, Calculus 12) read it as "a teacher gets one bot". The
ruling was to keep one bot per course (S-30) and make the manager a **list**: a card per taught
course carrying the bot's name, whether it is active, and how many sources it has.

**Why no verb was added.** Those three facts are already on `BotManagerPage`, which
`BOT_MANAGER_GET` answers for one course. The client therefore issues that read **once per taught
course** on show — a teacher has two or three — and the answer it gets for a course is exactly the
page the detail pane needs the moment she selects it. A `BOT_SUMMARY_GET` would have been a second
wire shape of the same fact, kept in step with the first by hand, bought for a handful of requests
against an indexed read. **This contract is unchanged by U-26**: no verb, no payload, no field.

The client-side shape is `client.features.bot.BotManagerListSession`, which holds one
`BotManagerSession` per course and owns no page of its own — the property that makes a create or a
toggle addressed to one course structurally unable to touch another's card. `BotCourseSummary` is
a client record, deliberately not in `common/dto/bot`, because nothing about it travels.

**If the shape ever changes**, the trigger to watch is a teacher with enough courses for n reads
to be felt on show. Nothing in the seed or in the demo is near it, and a summary verb would be
additive under §10's rules if it ever is.
