# HSTS v2 · defense Q&A prep sheet (E22.5)

**Owner:** lead lane (absorbed from Member B) · **Reviewer:** Naji · **Feeds:** the defense
· **Companion:** `docs/DEMO_SCRIPT.md` (E22.4b), `docs/briefs/member-b-e12-e13-walkthrough.md`

What to say when the clicking stops. Five sections: the ten questions we expect, the phase-2
answer, the design-problem story at three lengths, the honest-gaps drill, and who answers first.

**One rule above all of them.** Every answer is a claim plus its reason, and every claim can be
shown on a screen or in a file within about fifteen seconds. An answer nobody can open is worth
less than a shorter answer somebody can.

---

## 1. The ten questions, with the two sentences and the thing to open

### Q1. "How do you stop a student seeing the correct answers during an exam?"

**Answer.** The wire type a student receives has no field a correct answer could occupy: it carries
ten components, and correctness is not one of them. This was v1's leak and the fix is structural
rather than disciplinary, because no handler on that path has anywhere to put a key even by
accident.

**Show:** `common/dto/exam/ExamQuestion.java` beside `server/db/projections/TakeExamQuestion.java`,
then `ExamWireLeakGuardTest` and `TakeExamProjectionShapeTest`. **Case 6.7** searched the serialised
bytes for `correct`, `answerKey`, `isCorrect` and `solution` and found none, which is a real wire
probe rather than a type check, because Java serialisation writes field names into the stream.

### Q2. "What happens when the time runs out and the client is dead?"

**Answer.** The deadline is derived on the server from the attempt's own start, so the expiry timer
fires with no message from the student and closes the paper at the bell rather than when the task
happened to run. If the server restarts, the next resume closes it at the same instant, so the
recorded solving time is the allotted duration and not the elapsed wall clock.

**Show:** `server/features/exam/TimerService` and `AttemptService`, and the resume probe in
**case 6.8**: a 75 minute paper opened 105 minutes late closes at 75 minutes, not 105. Then say the
v1 sentence: in v1 the exam stayed open.

### Q3. "What stops one student reading another student's grade?"

**Answer.** The filter is in the SQL, not in a check afterwards, so there is no path that loads
someone else's row and then remembers to drop it. A forgotten check is a bug; a filter that was
never written cannot be forgotten.

**Show:** `findApprovedForStudent` / `findForStudent`, and `CheckedFormServiceTest`'s five ownership
probes. The strongest one is **case 9.4(a)**: a classmate who sat the same paper and holds a
legitimate grade in the same sitting is refused, and every refusal is the same empty answer, so it
is not an oracle either.

### Q4. "Which design patterns did you use, and why that one there?"

**Answer.** Patterns are named where they are used, not only in a document: Strategy for report
dimensions and validators and bot providers, Adapter at the network boundary and at each provider,
Observer for the client event bus and the server push, State for the exam, execution and grade
lifecycles, Command for the protocol verbs, DAO and Repository for the data layer. Each one is
carrying a "what if": Strategy so a new report is a class rather than a change to the engine,
Adapter so swapping the transport or the provider touches one file.

**Show:** `PLAN.md` §2's table, then `ReportStrategies.all()` and `IClientConnection`.

**Updated 2026-08-26 (batch D) — the honest caveat that used to live here is discharged.** It read
"two of the claimed patterns, Observer and DAO, are not named in Javadoc yet". Acceptance case 20.2
found it was **four**, not two: `Observer`, `Command Pattern`, `Facade` and `DAO` appeared **zero
times** in production javadoc under `src/main/java`, against five patterns that were named properly
(Adapter in `IClientConnection` and `HSTSClient`, Singleton in `ScreenManager` and `HibernateUtil`,
Template Method in `AbstractScreen`, Strategy in `QuestionValidator`, State in `EditLockState`). All
four resolve to real code — case 20.1 loads eighteen claims by class rather than by grepping for the
word — so it was a documentation gap and not a design one, which is exactly the gap NFR-20 exists to
close. **All four are now named at their boundary**, in the same house style as the five that were
already right: Observer/Pub-Sub on `ClientEventBus` **and** `PushGateway` (one mechanism, two ends
of a socket), Command on `MessageRouter`, Facade on `GradeApprovalService`, DAO/Repository on the
`server.db.repos` package.

**Two of those lines say what the pattern is *not*, and that is deliberate — use it if pushed.**
The Command line records that there is no `undo` and that the handler is bound to its receiver at
registration rather than carried by the command object; what the product does have is the part that
earns the name — a request that is an object (`Message` + `Verb` + payload, which is what lets it
cross a socket and be answered out of order), one uniform `Handler` interface for seventy
operations, and registry dispatch rather than a `switch`, so adding a verb touches no code in the
router. If a panel member says "that is not textbook Command", the correct answer is "agreed on
`undo`, and here is the property we chose it for" rather than a defence of the label.

**And one honest crossing, since 20.1 is where it gets asked:** `server/console/ConsoleView`
imports `client.ui.components.WarnConfirm` — the server console reuses the client's confirm dialog.
It is presentation to presentation, the logic tier is clean in both directions (asserted, not
claimed), and it is now written into `ARCHITECTURE.md` §9 so the answer is on record. That is B-36.

### Q5. "Two teachers edit the same question at the same time. What happens?"

**Answer.** Two mechanisms, deliberately: an advisory edit lock so the second teacher is told before
she starts, by name, and an optimistic version column so a stale write is refused even if the lock
expired or was never taken. The lock is a courtesy that prevents the collision; the version is the
backstop that makes the courtesy unnecessary for correctness.

**Show:** `EditLockService`, `EditLockGuard`, the `@Version` columns, and
`LockConcurrencyIntegrationTest`. **Case 13.6** is the live probe: with one teacher holding the
lock, the other's write is refused `CONFLICT` and the row is unchanged, so the badge is more than a
badge. Add the design line: lock visibility starts in the list, so a teacher deciding which question
to edit gets the signal before the click rather than after it.

### Q6. "Why store the statistics instead of computing them when the screen opens?"

**Answer.** The numbers a teacher reads are the ones that existed when grading finished, frozen into
the execution in the same transaction as the last approval, so they cannot drift as data changes
later. The identity is the strong form of the claim: the record the teacher's histogram renders is
equal to the record the principal's report renders, so one sitting cannot read two ways.

**Show:** `ScoreStatistics`, `FrozenStatistics`, `JpaTeacherResultsStoreContract` (stored, not
recomputed), and **case 12.4**, which compares the two readers component for component. If the
arithmetic is challenged: the sum of squared deviations for `4821` is 2450 over 8 students, so sigma
is exactly 17.5 with the population divisor, and the sample divisor would give about 18.71.

### Q7. "How do you know the principal really cannot change anything?"

**Answer.** Not by looking for buttons: `Role.PRINCIPAL` appears in exactly four
`Authorization.requireRole` calls in the whole server, guarding eight verbs, and every one of them
is a read. A fifth hit on a verb that writes would fail that probe before anybody drew a button for
it.

**Show:** run the grep live if the room is willing. Then **case 11.4**, which replays
`QUESTION_CREATE`, `QUESTION_UPDATE` and `QUESTION_DELETE` with her session **and a null payload**:
the answer is `FORBIDDEN`, and a handler that validated before checking the role would have answered
`VALIDATION` instead, which is exactly what the malformed payload is there to catch.

### Q8. "How much work is a new report type?"

**Answer.** One enum constant, one strategy class of about forty lines, one line in
`ReportStrategies.all()`, one query and one grouping constant. The engine, the DTOs, the handlers,
the summary arithmetic, the screen, the CSS and the server assembly are all untouched.

**Show:** `ReportEngineExtensibilityTest`, which drives a real engine over a fourth strategy that
exists only inside that test file, and separately reads `ReportEngine.java` and fails if any
dimension name appears in it. **Case 12.5** is the rehearsal. If asked which one we would add: the
"sittings she ran" reading, keyed on `created_by` rather than on the exam's author, which is a
decision on record rather than an omission.

### Q9. "Could the study bot tell a student what is on tomorrow's exam?"

**Answer.** It has no exam data to tell: the context is the course's uploaded sources plus the course
question bank, and exam definitions, execution codes and grades are not selected by any query on
that path. A bank question reaches the model as four unmarked options, so the key exists in the
database and never travels.

**Show:** `ContextBuilder`, `BotIsolationGuardTest` (compile-time isolation) and `GuardrailsTest`
(hostile fixtures, including instructions embedded in a source document). **Case 14.1** read the key
for question `21005` straight out of the database while the same probe read the prompt and found the
options unmarked. Add the deployment half: keys live in `server.properties` or the environment on the
server, never on the client and never in git.

### Q10. "How are passwords handled, and what stops somebody guessing one?"

**Answer.** BCrypt hashes, verified through the real verifier, with one generic failure message that
does not reveal whether the account exists, and a lockout after five failures. The interesting part
is that the lockout refuses **before** any lookup or verification, because the first specification we
wrote answered a locked-out correct password differently from a wrong one, which turned the throttle
into an oracle for the very password it existed to protect.

**Show:** `AuthService`, `LoginThrottle`, and the throttle nest in `AuthServiceTest` including
"unknown usernames throttle identically". The story is **P-3** in `PROBLEMS.md`, and its lesson is
the quotable one: specifications get security-reviewed like code, because implementations faithfully
reproduce specification bugs.

---

## 2. "What would you change with more time, and what does phase 2 look like?"

Lead with the shape rather than a wish list: **phase 2 is a deployment change and a set of adapters,
not a redesign**, and that is a claim the code has to survive rather than a slogan.

**The five we would name, in this order.**

1. **The transport is already behind an adapter.** Swapping OCSF over TCP for REST or WebSockets is
   one new implementation of `IClientConnection` and no UI change, because no screen knows what a
   socket is. Rehearsal case 19.2 asks exactly this.
2. **TLS, and the fingerprint claim it would upgrade.** Today's server fingerprint gives
   disambiguation and change detection, not impersonation resistance, because it is copyable, and
   we say that rather than overclaiming. The cryptographic version is the TLS certificate
   fingerprint as the identity, which is ADR-019 and a gated decision rather than an oversight.
3. **Post-commit callback machinery.** Our notification hooks run in the handler after its
   transaction commits, which is correct and leaves one honest window: a crash between the commit
   and the hook loses the bells and never loses the submission. The phase-2 shape is real
   post-commit callbacks, so a hook can join the caller's transaction without pushing bells about
   work that might roll back. `EXAM_BUILDER_WIRE_CONTRACT` §5.5 states both the ruling and the
   window.
4. **A watcher-only lock release.** `EditLockService.release` drops the hold and unwatches in one
   call, so a list screen cannot stop watching without risking its own editor's lock. The bank list
   therefore never releases anything, and the cost is a set entry per row browsed, cleared on
   disconnect. A `LOCK_UNWATCH` verb is the clean shape and it is E18's to add.
5. **The documented v2 shapes.** These are decisions on record, each with the reason it waited:
   `GRADE_COMMENT_SET`, so a teacher can comment without changing a score and can clear a comment
   (GRADING contract A3 and "what is deliberately absent"); `Violation.field` on the wire, so a
   client can attach a server refusal to the box that caused it (PR15's answer, recorded as the v2
   shape); the "sittings she ran" report dimension; and the ten seed illustrations, which are
   content rather than code.

**Then the honest one, and say it unprompted:** with another week we would spend it on the manual
pass rather than on features. Scenarios 1 to 14 are walked, 15 to 21 are not, and the ones that are
not are the non-functional half: packaging, the two-machine rehearsal, concurrency, no-refresh, the
pattern walk and the UI sweep.

---

## 3. The design-problem story, at three lengths

**The pick: P-8, "two green suites either side of one seam, and neither crosses it."** It wins over
P-11 for one reason: it is the only candidate where the failure was certain rather than probable,
the cause was structural rather than careless, and the fix is a rule a panel can check in the code
in ten seconds.

**Keep P-11 loaded as the reserve.** If the panel asks specifically for a concurrency or
distributed-systems problem, tell that one instead: subscribing to the news after reading the state
opens a window in which a change is in neither, and the fix is to subscribe first so the overlap
duplicates rather than drops.

### 30 seconds

"We had a service that wrote every exam composition with a question id of zero. The schema has a
composite foreign key onto the question versions, so every exam save would have failed, live, on the
first acceptance case in the epic. Two test suites sat either side of that seam and neither crossed
it: the repository was proven against two real databases using correct ids, and the service was
proven against a mocked repository that accepted anything. Both suites green, coverage 98.92 percent
on the class that could not write a row. We found it in a cold post-implementation audit, and the
rule we took from it is that where a method translates between two type systems, the test asserts
what crossed, never that a call happened."

### 2 minutes

Add the mechanism and the fix.

- **What it was.** `ExamService` built its composition rows with `question_id = 0`. The table carries
  a composite foreign key onto `question_versions (id, question_id)` and a unique key on
  `(exam_version_id, question_id)`. So the first save fails on the foreign key, and if one had got
  through, the second question in the same exam would have collided at zero. The comment above the
  line said the store resolved the id. The store persists the field verbatim, so the comment was
  asserting a safety property that did not exist.
- **Why nothing failed.** The seam between a repository and the service above it had a test on each
  side and neither one crossed it. `ExamBuildRepositoryContract` builds every pin with a genuine
  resolved id, because a contract test using a wrong id would be testing nothing.
  `ExamServiceTest` mocked the store and asserted `replaceComposition` was called with `any()`. So
  the store was proven correct for inputs the service never sends, and the service was proven to
  call a store that does not exist.
- **Why it is structural.** Mocking the collaborator is what makes a service unit-testable, and it
  is the same act that stops anything checking the values crossing. It is not carelessness that can
  be reviewed away.
- **The fix.** Argument captors on the collaborator plus extracting over every component, in place of
  verifying that a call happened. Deliberately narrow: it applies to methods that translate between
  type systems, not to every interaction. Three questions find the places: does this method build a
  value of a type it did not receive, does anything downstream constrain that value, and does the
  test say `any()`. All three yes is where the defect lives.
- **The proof.** Twelve planted mutations are now caught, including two enum swaps and a zero-based
  ordinal that violates a check constraint on every write. None of them moved a test before the fix.

### 5 minutes

Add the family and the method, because the interesting claim is not "we had a bug".

- **It is one of a family, and we can show the family.** P-6: the code and its test shared a
  misunderstanding of a collation, so 3,464 green tests and 100 percent coverage proved nothing.
  P-8: two suites shared an absence at a seam. P-9: the suite and the external database shared an
  understanding MySQL does not share, in Hebrew final forms. P-10: a subscriber the event bus could
  never invoke, whose failure was swallowed by a catch that exists for a different failure mode.
  P-12: a validator stricter than the constraint it stands in for, refusing five rows the system had
  itself stored. Each one is a different way for a green suite to be silent, and each fix is a rule
  rather than a patch.
- **What we changed about how we work.** A cold post-implementation audit became a gate on opening a
  pull request: an adversarial reader with no knowledge of the author's reasoning, handed the
  requirement and the schema, asked one question. Where do the code and its test agree with each
  other and both differ from the specification? It is the only check standing outside the correlated
  pair, and we adopted it because the alternatives had each already failed here: more tests by the
  same author, a second database engine, and a reviewer reading a diff.
- **What it caught afterwards.** P-12 came out of an acceptance walk and falsified a design principle
  we had written into a class javadoc, that being stricter than the database is the safe direction.
  It was not safe: five seeded questions could not be saved at all, and a teacher editing only a
  stem met a refusal about answers she had not touched. We corrected the javadoc in place rather
  than quietly amending it, because a false claim in a durable record stays a defect until the
  record is corrected.
- **The generalisation we would offer as the answer to the assignment's question.** A one-directional
  promise deserves a two-directional test, and an argument for why one direction is harmless is a
  hypothesis rather than a licence.

**If they push on P-11 instead**, the two minute version: a live list needs the state now and the
changes after. Asking for the snapshot first and registering the watch second reads as the natural
order, paint then subscribe, and opens a window in which a lock acquired by a colleague is captured
by neither, because the server resolves push recipients at the instant the lock changes. The row
then shows the question as free for the whole of that colleague's edit session, which is the exact
case the column exists to prevent. No single-client test can reach it; it was found by an
adversarial read that started from the server's recipient resolution. The fix is to watch first and
snapshot second, pinned by a test asserting the snapshot is the last of the two verbs on the wire,
and the generalisation is that a duplicate is idempotent while a gap is silent.

---

## 4. The honest-gaps drill

**The posture:** we volunteer these. A team that hands the panel its own gap list is in a stronger
position than a team that gets walked into one, and every line below has a reason and an owner
rather than an excuse.

### Open entries in the bug register

| Entry | The one-sentence honest answer |
|---|---|
| **B-2** log4j2 error line at start-up | "A library on the classpath looks for a logging binding it does not need, our logging is logback, nothing is broken, and the fix is one dependency line we did not want to make on the day." |
| **B-8** no seeded illustrations | "Ten seeded questions are flagged as illustrated and carry no image bytes, so the picture path has nothing to show on the demo paper; the path itself round-trips, and the fix is ten files plus a loader read." |
| **B-13** seeded exam names differ from the seed document | "The loader writes a colon where the document writes a dash, because our own copy rules ban that dash on screen; the document is what is wrong and it is one editing pass." |
| **B-15** | "Folded into B-8. One fixture gap, one ticket, cross-referenced rather than filed twice." |
| **B-22** | "Closed by ruling, not by code: the lockout message carries no unlock time on purpose, and the PRD line moved to say so, because a stale unlock time is worse than none." |

### Gaps and partials in the traceability matrix

| Row | The one-sentence honest answer |
|---|---|
| **F3.2 GAP** bank picker add path | "A teacher cannot fill an empty exam from the bank yet; the wire carries the version id the pin needs, the residual is one method adopting it, and it is in flight." |
| **F3.3 PARTIAL** no auto-compose UI | "The auto composer is complete, property-tested at 28 cases, and answers the infeasibility report exactly as specified; the criteria form is the unbuilt half." |
| **E7.14 GAP** newer-version action | "The badge that says a question has a newer version renders, and nothing lets a teacher act on it yet." |
| **E7.16 GAP** builder session tests | "The builder's own session and integration tests are in flight with the picker they would exercise." |
| **F1.1 PARTIAL** throttle unwalked | "Five failures and a thirty second lockout are unit-tested, including that unknown usernames throttle identically; we stopped at three failures in the walk." |
| **F8.2 PARTIAL** no review screen | "A teacher changes a score from the table and cannot open the student's marked paper first; the assembler exists and the student's checked form uses it, so it is a screen rather than a mechanism." |
| **F10.4 PARTIAL** two of five | "Locks are wired for questions, exams, the question editor and the bot manager; release schedules and grading review are not lock-wired, and both are single-teacher screens today." |
| **F12.6 PARTIAL** keys unverified | "The provider chain is tested against mocked HTTP on both providers and has a live-key checklist that takes five minutes; if it has not been run today, we say so rather than claiming it." |
| **F14.1 PARTIAL** unverified on Windows | "The JARs must be built on Windows because JavaFX natives are baked in at build time, and that is a checklist item rather than a code risk." |
| **S-9, S-39 GAP** | "Two spec ids our own acceptance table cites in section headers and that are defined nowhere in the repository; we found them by tracing rather than by assuming, and they resolve or the citation goes." |
| **NFR-15 PARTIAL** | "The two-machine rehearsal is written in full and its execution is the checklist we run on the day." |
| **NFR-16 PARTIAL** | "Duplicate login and pairwise concurrency are covered by tests; a thirty-client load test is not written." |
| **NFR-17 PARTIAL** | "The seed loads on one command and is idempotent; ten flagged illustrations carry no bytes, which is B-8." |
| **NFR-20 — no longer partial** | "Every pattern we claim resolves to real code, checked by loading the type rather than by grepping for the word — eighteen claims, eighteen resolutions. Four of them were named nowhere in Javadoc, which our own acceptance walk found and our next batch fixed; all thirteen are now named at their boundary class." *(Was: "two of them, Observer and DAO, are not yet named". It was four. B-34.)* |
| **NFR-21 PARTIAL** | "Polish is open by design at this stage: the illustration set, the responsive pass at three widths and the RTL sweep are listed rather than assumed done." |
| **E1.11 GAP** protocol fuzz | "The discovery socket is fuzzed four thousand ways; the main protocol socket is not, and PRD §6 promises a malformed-message flood survives, so it is a real hole and it is ours." |
| **S-19 / case 9.5** | "The timed-out student's result is proven through the production assembler against the real database; the rendered screen is a manual-pass item." |
| **F9.4 stated deviation** | "The reports compare rows in a table and not in a grouped bar chart across rows; that was scoped out, not forgotten." |
| `requireEnrolled` dead stub | "Enrolment is enforced, in the attempt service and the bot service, through the data layer; that guard is dead scaffolding that looks wrong on a cold read, and a test pins its refusal deliberately." |
| Thirteen ids cited nowhere in `src/` | "Traceability defects, not functional ones: the behaviour is implemented and tested under a neighbouring citation, and each is one Javadoc line from being closed. We would rather report them than assign a plausible owner." |

### Two things a panel might notice on the screen

| What they see | The one-sentence honest answer |
|---|---|
| Two rail items read "Arrives with E10 / E11" | "Both screens are live and both are reached from where they belong, the dashboard code box and the release row's Monitor button; the rail labels are stale and they are cosmetic." |
| The student's bot always opens one course | "The chat opens the course a navigation parameter names and otherwise her first course, and there is no picker yet, which is why the cross-course integrity net is demonstrated from the acceptance evidence rather than staged live." |

---

## 5. Who answers first

The cross-walkthrough rule in `TEAM_SPLIT.md` is the reason this section is short:
**everyone can field everything, second.** First answer goes to the lane that built it, because the
reasons are freshest there, and the second answer is available from anybody, which is the property
the course specification asks for and the property E22.4 exists to produce.

| Topic | First | Everyone else, second |
|---|---|---|
| Architecture, tiers, the protocol, patterns overall | **Naji [L]** | A and B both hold `PLAN.md` §2 and `ARCHITECTURE.md` §5 |
| Authentication, sessions, the throttle story (P-3) | **Naji [L]** | |
| Taking an exam, timers, force-submit, extension, monitor | **Naji [L]** | B can tell the grading seam's half of it |
| Study bot, provider chain, prompt isolation | **Naji [L]** | |
| Notifications, edit locks, server console, packaging, discovery | **Naji [L]** | |
| Release manager and the report engine (reabsorbed to the lead) | **Naji [L]** | B wrote the report engine's acceptance cases and can drive them |
| Schema, entities, repositories, the seed dataset | **Omar [A]** | B knows the seed's numbers cold, since every acceptance case names a seeded row |
| Question bank, versioning, the validator stories (P-6, P-9, P-12) | **Omar [A]** | L ruled the folds and can defend the direction |
| Exam builder, auto composition, the infeasibility report | **Omar [A]** | L for the gap's status and the plan |
| Approval workflow and self-approval logging | **Omar [A]** | |
| Grading, override and audit trail, statistics | **Amjad [B]** | L for the frozen-statistics identity |
| Student results, checked form, the three gates | **Amjad [B]** | |
| Teacher results, histogram, the sigma divisor | **Amjad [B]** | L built the chart component |
| Acceptance testing, the bug register, traceability | **Amjad [B]** | everyone: it is the one document all three lanes wrote into |
| The design-problem story (P-8) | **Omar [A]** found it | L ruled the rule; B can tell it at any of the three lengths |

**If a question lands on the wrong person**, the recovery is one sentence and not a handover
apology: "that one is Omar's lane, and here is the short version while he opens the file." Then give
the thirty second answer from this sheet. Every row above has one.
