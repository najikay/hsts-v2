# Legacy question-bank retirement

**Date:** 2026-08-24 · **Branch:** for Member A's review · **Not committed** — everything is in the working tree.

The E0 prototype's question screen, its two verbs, its handler, its DAO, its DTOs, its FXML, its
stylesheet and every test that existed to pin its behaviour are deleted. Rail id `questions` now
serves `BankView`. The interim route id `bank` and the banner that pointed at it are gone with it.

Member A counted 28 files. **The real number is 43** — 11 deleted, 32 modified (plus this report,
new). The extra ones are listed with the reason each was missed, because "his count is a floor" only
helps if the overshoot is explained rather than just larger.

---

## 1. The ruling, as executed

The lead's ruling of 2026-08-23 was that the retirement **swaps the screen behind rail id
`questions`** — the rail id survives, the legacy screen does not. That is what happened:

| | before | after |
|---|---|---|
| rail id `questions` | `QuestionsView` (E0 prototype list, read-only since #43) | `BankView` (versioned bank) |
| route id `bank` | `BankView`, off-rail, reached from a banner | **retired** |
| route id `questions.edit` | `QuestionEditorView` | `QuestionEditorView` — unchanged, as assembled |
| `RoleNav` item | `Routes.QUESTIONS.id()`, label "Question Bank" | identical, not one character changed |

`QuestionsView.newBankBanner`'s javadoc said it would be "deleted in the same PR that deletes the
class". It was.

---

## 2. Deleted (11 files)

| File | Note |
|---|---|
| `src/main/java/client/features/bank/QuestionsView.java` | the legacy screen |
| `src/main/resources/fxml/QuestionsView.fxml` | its FXML; the `fxml/` directory is now empty and removed |
| `src/main/resources/css/app.css` | its scoped stylesheet — **verified** to have exactly one loader (`QuestionsView.LEGACY_STYLESHEET`) before deleting |
| `src/main/java/server/features/bank/LegacyQuestionHandlers.java` | the two legacy verbs' handler |
| `src/main/java/server/db/QuestionDAO.java` | **verified** no caller outside the handler and its own tests |
| `src/main/java/common/dto/bank/Question.java` | legacy DTO; the answer key the leak scan was blind to |
| `src/main/java/common/dto/bank/QuestionUpdate.java` | legacy DTO; the E18.4 guarded-update payload |
| `src/test/java/client/features/bank/LegacyScreenIsReadOnlyTest.java` | existed only to pin "the legacy screen is read-only". Dies with the screen; its own javadoc said it "holds only until the retirement PR deletes the screen and takes this file with it" |
| `src/test/java/server/features/bank/LegacyQuestionHandlersTest.java` | 358 lines, all of it the deleted handler |
| `src/test/java/common/dto/bank/QuestionUpdateTest.java` | all of it the deleted DTO |
| `src/test/java/common/dto/bank/QuestionTest.java` | **not on the count of 28.** Same-package, so it says `Question` unqualified and no `common.dto.bank.Question` grep finds it. All 133 lines are the deleted DTO |

**Verbs retired** (`common/protocol/Verb.java`): `GET_ALL_QUESTIONS`, `UPDATE_QUESTION`. Identified
by reading `LegacyQuestionHandlers.registerOn`, which registered exactly these two and nothing else.
No other handler served either.

---

## 3. The guard shrink — the proof

Both guards carried a dated LEGACY entry designed to fail the build when the pair retired, forcing
its own removal. **Both fired.** The shrink was the fix, and it is what this PR exists to show.

### 3.1 `BankWiringGuardTest` — the allow-list loses its third entry

```diff
             new Wiring("BankReadHandlers",
-                    "the bank list, the detail pane and the version history all answer nothing"),
-            // Legacy, and on the list precisely because it is. It is a bank handler in this
-            // assembly, it answers GET_ALL_QUESTIONS and UPDATE_QUESTION, and its Question.answer
-            // is a real key that the leak scan cannot see (contract section 1). When the
-            // retirement PR lands (section 7.4) this entry comes out and that diff is the proof.
-            new Wiring("LegacyQuestionHandlers",
-                    "the pre-E6 question screens stop answering, before their retirement PR"));
+                    "the bank list, the detail pane and the version history all answer nothing"));
```

Three `Wiring` entries → two. Both survivors are live code.

`everyBankHandlerInTheSourceIsListed` scans `defaultRouter` with
`new (\w*(?:Bank|Question)\w*Handlers?)\(` — a pattern that was **widened specifically because
`LegacyQuestionHandlers` was its counterexample**. The pattern is kept as-is: it is what will catch
the `BankTopicsHandler` §7.6 has already ruled is coming, and narrowing it back to `Bank\w*Handlers`
would re-open the hole the cold audit found. Comment updated to say so.

### 3.2 `BankWireLeakGuardTest` — the allow-list loses a whole list

```diff
-    private static final List<Class<?>> LEGACY_NOT_COVERED =
-            List.of(Question.class, QuestionUpdate.class);
```

and its **three skip clauses**, each of which was a hole in a different check:

```diff
-            if (licensed.contains(dto) || LEGACY_NOT_COVERED.contains(dto)) {
+            if (licensed.contains(dto)) {
```
```diff
-        known.addAll(LEGACY_NOT_COVERED);
```
```diff
-            if (LEGACY_NOT_COVERED.contains(dto)) {
-                continue;
-            }
```

The companion test `theLegacyExclusionIsNamedAndStillReal` asserted the excluded types were *still
present*, so that it would go red the day they retired. It went red. It is **replaced, not deleted**,
by `theLegacyExclusionIsRetired`, which pins the end state in the other direction:

- `Question` and `QuestionUpdate` are absent and must stay absent;
- `CorrectnessNames.suggestsCorrectness("answer")` is still `false` — the blind spot that made the
  exclusion necessary is pinned even though nothing sits in it now, because `answer1..answer4` are
  options a student is meant to see and that must never be "fixed";
- every type in `common/dto/bank` is a record or an enum, so no mutable-class DTO is hiding from
  the component scan the way `Question` did.

That last one is deliberately **not** "everything is a record": `Difficulty` and `ImageAction` are
enums and always were. My first version asserted `allMatch(Class::isRecord)`, it red-lined those
two, and the honest fix was to state the property I actually meant rather than to weaken the check.

### 3.3 The two guards that needed no shrink — checked, not assumed

- **`WireDtoLeakGuardTest`** — no legacy licences to remove. Its `LICENSED` map was already cleaned
  on 2026-08-21 (Member A's finding 3: "a licence that grants nothing is documentation wearing a
  guard's clothes"); the in-file comment records that the legacy pair's licence lived *only* in
  `BankWireLeakGuardTest`. Verified: no `Question.*` or `QuestionUpdate.*` key in the map. Untouched.
- **`CorrectnessLeakGuardTest`** — **no legacy repository method died**, so nothing to shrink. Its
  `Question` import is `server.db.entities.Question`, the JPA entity of the versioned bank, not the
  deleted DTO. Untouched.

---

## 4. Route swap (`client/core`, `client/features/bank`)

| File | Change |
|---|---|
| `client/core/Routes.java` | `QUESTIONS` keeps id `"questions"`; javadoc rewritten — it *is* the versioned bank now, on the same "rail id never moved" pattern E13 used for My Grades and E14 for Results. `BANK` constant, its javadoc and its `all()` entry **deleted**. `QUESTION_EDIT` untouched except one tense fix ("renamed nothing here") |
| `client/core/SessionRoutes.java` | `routesFor` adds `Routes.QUESTIONS` once instead of `QUESTIONS` + `BANK`; `builderFor` maps `Routes.QUESTIONS.id()` → `BankView::new`; the `Routes.BANK` branch and the `QuestionsView` import are gone |
| `client/features/bank/BankRoutes.java` | `LIST` changes `"bank"` → `"questions"`. `EDITOR` unchanged — it was spelled for the end state from the start, so the retirement renamed nothing. Class javadoc rewritten: it no longer says "both are about to move" |
| `client/ui/shell/RoleNav.java` | **rail item unchanged.** Only the comment above it, which claimed the legacy screen "still works over the DAO" |

`BankView` and `QuestionEditorView` navigate through `BankRoutes.LIST` / `BankRoutes.EDITOR`
everywhere (7 call sites), so they picked up the new spelling with no edit. That is exactly what the
constant was for.

---

## 5. Tests that changed, and why each one had to

### 5.1 Route-table expectations

| File | Change |
|---|---|
| `client/core/AppArgsAndRoutesTest.java` | `declaresEveryRouteThisBuildHas`: `"bank"` removed from the exact route-id list (26 → 25 ids). Comment explains the table *lost* a row rather than gaining one |
| `client/core/SessionRoutesTest.java` | teacher and coordinator lists drop `Routes.BANK`; note added that one bank route is now correct, not two. Principal case strengthened: her `containsExactly` already excluded `QUESTIONS`, and the `as()` now records that the reason got *stronger* — the id serves `BankView`, which carries Delete and Edit, so her bank read stays the Data screen (#41) |
| `client/features/bank/BankScreenWiringGuardTest.java` | see below |

**Student and principal are still never offered the bank.** Both negative cases were vacuous before
the assemblies landed and are live now; both pass. `unregisteredRoutesAreUnreachable` — a student's
navigator does not register `Routes.QUESTIONS.id()` — passes unchanged and means more than it did,
because the id now leads somewhere with a Delete button.

### 5.2 The new end-state pin (the one guard test this PR adds)

`BankScreenWiringGuardTest.theLegacyScreenIsStillReachable` asserted rail id `questions` was serving
the **legacy** screen — it existed to stop that screen leaving the rail before its replacement
arrived. Both halves of that ruling are discharged, so it is replaced by:

**`theRailIdServesTheVersionedBank`** — ⚑ rail id `questions` resolves to `BankView` for both
teaching roles. Three assertions, because "the id is offered" was true before this PR too:

1. both teaching roles are offered `"questions"`;
2. `SessionRoutes.builderFor` maps `Routes.QUESTIONS.id()` → `BankView::new`;
3. `"bank"` appears in no role's route list.

A future change that re-split the two ids would pass (1) and fail (3). One that pointed the rail at
a different screen would pass (1) and fail (2).

**On (2) reading source rather than building the screen** — stated plainly because it is a real
limitation: `ScreenFactory.get` would answer directly and is the honest way to ask, but it *builds*,
and `BankView` creates a `VBox`, a `DataTable` and a dozen controls in field initialisers, so it
needs a booted JavaFX toolkit. That would turn a wiring guard into an FX test for a property that is
one line of source. It uses the shape `BankWiringGuardTest` uses on the server assembly, with the
same limitation named: **it proves the mapping is written, not that it runs.**
`BankScreenInteractionTest` drives the built screen. Comments are stripped first, so commenting the
branch out reads as deleting it.

Constants `RAIL_ROUTE_ID` and `INTERIM_ROUTE_ID` are **literals, not constants read from
`BankRoutes`** — `BankRoutes.LIST` now reads `"questions"` too, and a constant that would move along
with the mistake cannot pin the mistake.

### 5.3 Tests re-expressed over live verbs and DTOs

Fifteen files used `GET_ALL_QUESTIONS` / `UPDATE_QUESTION` / `Question` as **arbitrary fixtures** —
they were the first verbs in the enum and the only DTO in the prototype, so they became the default
stand-in for "some verb", "some payload". None of them was testing the bank. Each was swapped for a
live equivalent (`BANK_LIST`, `QUESTION_UPDATE`, `QuestionRequest`), which changes nothing about
what they assert:

`ClientEventBusTest` · `ConnectWiringTest` · `FakeClientConnectionTest` · `RequestDispatcherTest` ·
`MessageTest` · `PushGatewayTest` · `MessageRouterTest` · `MessageRouterFuzzTest`

Two needed more than a swap:

- **`MessageRouterTest.runtimeExceptionIsGeneric`** planted `"NullPointerException at QuestionDAO:42"`
  in an exception message and asserted the response leaks no `"QuestionDAO"`. Pointed at
  `QuestionRepository` — a live class, so the fixture stays realistic.
- **`RequestDispatcherTest.timeoutFailsTheFuture`** asserted the timeout message names the verb; the
  expected string moved with it.

### 5.4 Tests whose subject was the legacy flow

- **`ProtocolLoopbackTest`** (5 cases) is a **transport** test that used `LegacyQuestionHandlers`
  over a mocked `QuestionDAO` as its exercise payload. Two of its cases were the prototype flow.
  Rewritten over a **stub handler defined in the test file**, which keeps all five cases and every
  transport property: a verb reaches its handler over a real socket, the answer is correlated to the
  right future, non-ASCII survives serialization, a handler error crosses as ERROR rather than a
  dropped connection, an unregistered verb answers BAD_REQUEST, a push arrives unsolicited, a
  dropped socket frees the session. The class javadoc now says why the stub is right rather than
  lazy: **what is under test is the transport, not any feature.** Wiring in the real bank would drag
  Hibernate and live MySQL into proving a property neither is involved in, and a red here would stop
  meaning "the socket is broken", which is the one thing this file exists to say. Mockito dropped
  entirely — no mocks left.
- **`LockConcurrencyIntegrationTest`** lost exactly two of its ten cases: `staleWriteIsRejected` and
  `freshWriteStillSaves`. Both drove `UPDATE_QUESTION` through the legacy handler and were testing
  the **E18.4 guarded-update flow** — the DAO's value-based `WHERE` clause — not any lock. §7.4 lists
  that flow as retiring with the pair. **No lock coverage was lost:** all eight lock cases are
  untouched, including `studentsAreRefusedLockVerbs` (the P-5 fix). The versioned bank refuses a
  stale write on `baseVersionNo` instead, proven by `QuestionServiceTest.staleBaseVersionIsRefused`.
  The `QuestionDAO` mock and the now-unused `Mockito.when` import are gone; the class javadoc
  records the removal and where the property lives now.
- **`DtoSerializationTest.questionStillRoundTrips`** was one case pinning the legacy DTO. Removed,
  with a comment in place: the bank package has `BankDtoTest`, and Hebrew survival across the wire is
  pinned by six other cases in the same file.
- **`VerbTest`** — `expectedVerbsExist` swapped to live verbs. `bankVerbNamingIsTheRuledOne`
  asserted `UPDATE_QUESTION != QUESTION_UPDATE`, which existed because the two spellings were one
  transposition apart and **both were live**. Only one is live now, so it is turned round to assert
  both retired names are *absent* — which is what stops a reader of the older TODO reintroducing
  either as a synonym for a bank verb that already exists.

### 5.5 Tests not on the count of 28

| File | Why the grep missed it |
|---|---|
| `common/dto/bank/QuestionTest.java` | same package — references `Question` unqualified |
| `client/ui/theme/StylesheetParseTest.java` | referenced `/css/app.css` as a **string**, twice (the `SHIPPED` list and a `@ValueSource`). Would have failed on a missing classpath resource. Both entries removed; the javadoc now records that app.css was the prototype's sheet with exactly one loader |
| `server/features/bank/QuestionLockKeyTest.java` | referenced `LegacyScreenIsReadOnlyTest` **in a comment** explaining where the single-key-scheme guarantee actually lived. Reworded (§6) |
| `pom.xml` | **two** stale JaCoCo exclusions, in different blocks 190 lines apart: `client/features/bank/QuestionsView*` and `server/db/QuestionDAO*`. Both are excluded paths for classes that no longer exist. Removed, each replaced with a comment saying what stood there — and the second one carries the `DatabaseConfig` finding below |

`NavigatorTest`, `ShellStateTest` and `GalleryScreen` all use the literal `"bank"` as a generic
navigator/rail fixture with no connection to `Routes.BANK`. **Deliberately left alone** — renaming
them would be a false positive dressed as thoroughness.

### 5.6 One class this retirement orphaned — left for you, not deleted

**`src/main/java/server/db/DatabaseConfig.java` is now unreachable.** Its `getConnection()` had
exactly one caller in the whole codebase — `QuestionDAO` — and the v2 stack opens connections
through `HibernateUtil`. Its three public constants (`HOST`, `PORT`, `DATABASE`, plus the assembled
`URL`) are referenced by nothing.

The pom's own comment called it and `QuestionDAO` "the legacy prototype pair", so deleting it is
arguably the completion of this PR. **I did not delete it**, for two reasons worth your ruling: it
hardcodes `hsts_db` and may still be wanted for a manual pass or a quick JDBC probe on demo day, and
it is not on the delete-list this PR was scoped to. The JaCoCo exclusion for it stays and its comment
now says plainly that it is unreachable rather than implying it is still the DAO's config. **One
line to delete it if you want it gone.**

---

## 6. Locking — verified, nothing to change

`EntityRef.QUESTION` numbers by `displayId5` with no second scheme anywhere. **Verified: no PK-keyed
lock reference survives.** `QuestionLockKey` is the one home and its only caller path resolves the
question first, then keys the lock. The legacy screen's PK-based locking died in #43 and the screen
itself is now gone, so the hazard the ruling existed to prevent — two teachers on one question
holding two different keys — is structurally impossible rather than merely avoided.

Three comments referenced the deleted screen or the deleted test as the *reason* the schemes stayed
apart, and were reworded rather than dropped, because the reasoning still matters:

- `QuestionLockKey` class javadoc — "there are two candidates" → there *were* two, and the retirement
  deleted the one that held the other.
- `QuestionLockKey.of` inline comment — it said the width check "does NOT make the key space disjoint
  from the legacy screen's primary keys… what actually keeps the two schemes apart is that the legacy
  screen takes no lock at all, which `LegacyScreenIsReadOnlyTest.takesNoLock` asserts". Both named
  things are deleted. Reworded to keep the load-bearing warning: **the width check is not a licence
  to key something else through here.**
- `QuestionLockKeyTest.wrongWidthIsRefused` — same correction, same reason.

---

## 7. The old `Questions` table — and a finding that changes the answer

**Instruction was: do not touch migrations; note that a V8 drop is a hardening-day decision for the
lead.** I did not touch them. But the note is different from the expected one, and it is the most
important thing in this report:

> **The prototype's `Questions` table was never carried into the v2 schema. There is nothing to
> drop, and no V8 decision to make.**

`QuestionDAO` ran four statements against a table `Questions` with columns `id`, `question_text`,
`answer`. Checked every migration V1–V7:

- **no migration creates such a table.** The full table list is `subjects, courses, users,
  course_teachers, enrollments, coordinators, questions, question_versions, exams, exam_versions,
  exam_version_questions, exam_executions, exam_attempts, attempt_answers, grades, bots, bot_sources,
  bot_sessions, bot_messages, notifications`.
- `questions` **is** in that list, from `V2__bank.sql` — but it is the **versioned bank's identity
  row**: `id, course, serial3, display_id5, deleted_at, lock_version`. Different shape entirely.
- `question_text` appears in no migration. The only `answer` column in the schema is
  `bot_messages.answer` (V6).

**Consequence:** against any migrated database, `QuestionDAO.getAll()` hit an unknown-column
`SQLException`, caught it, printed to stderr and returned an empty list. The legacy screen had been
showing "Questions loaded." over zero rows. It only ever *looked* alive in tests, because
`ProtocolLoopbackTest` and `LockConcurrencyIntegrationTest` both mocked the DAO — the two suites this
PR just removed the mock from.

So the append-only rule is untouched because nothing was ever appended, and hardening day has one
fewer decision. Recorded in `BANK_WIRE_CONTRACT.md` §7.4 as well, since that ruling assumed otherwise.

---

## 8. Docs

| File | Change |
|---|---|
| `docs/TODO.md` | **E6.9–E6.13 ticked**, each verified against the code before ticking (§9). E6.14 left unticked — outside this brief, though it looks complete (`LockAwareEditor`, `LockBanner`, `FxHeartbeat` are all wired in `QuestionEditorView`); flagging rather than ticking |
| `docs/contracts/BANK_WIRE_CONTRACT.md` | §1's "what the guard does NOT cover" was a **live claim that is now false** — rewritten to past tense with a `DISCHARGED 2026-08-24` block. §7.4's ruling gets a discharge note carrying the V8 finding. Both record that **this retires nothing the freeze covers**: frozen v1 is E6's seven verbs and sixteen DTOs, the legacy pair was never part of it, and §7.4 scheduled exactly this removal |
| `docs/PROBLEMS.md` | P-5's Evidence names `LegacyQuestionHandlersTest`, which no longer exists. **Addendum added, not rewritten** — P-6 of this same file establishes that a false claim in a durable record is a defect, and P-4 establishes the addendum form. Records that the exposure is now closed by removal, and that the standing rule it produced (a verb ships with its role gate; an unread `CallerContext` is a review finding) is what outlived it |

**Deliberately not edited** — dated historical records, per `docs/reports/README.md` ("the file is
the durable copy"): `docs/PLAN.md` §1.1 (describes the inherited prototype, including
`QuestionDAO`, as the starting point), `docs/briefs/member-a-e2.md`, and the ten PR reports under
`docs/reports/`. Rewriting what a PR said at the time would destroy the record rather than update it.

`docs/ARCHITECTURE.md` needed no change: it never named the legacy screen as reachable. Its one
`GET_QUESTIONS` mention (line 68) is an illustrative placeholder in a `Message` envelope comment, not
a real verb name.

---

## 9. TODO ticks, each verified before ticking

| Item | Evidence checked |
|---|---|
| **E6.9** Bank screen | `BankView` registered under rail id `questions` for both teaching roles; interim `bank` gone. Made reachable *by this PR*, which is why it was unticked |
| **E6.10** Question editor | `QuestionEditorView` with `RadioGroup.indexed`, topic/difficulty fields, `ImagePicker`; assembled in `222ddd8` |
| **E6.11** Validation UX | `QuestionEditorSession.duplicatePairs` runs the server's own `QuestionValidator.sameAnswer` live; `showError` / `apply(ValidationState)` / `clearValidation` on all field types; server errors mapped per-field via the `Field` switch |
| **E6.12** Version history | `BankSession.historyEntries` builds one row per version from `QUESTION_VERSIONS`, each carrying the full `QuestionVersionDetail` **so the panel can render it read-only**, and `BankCopy.changeSummary` names the changed fields (text, answers, which is correct, topic, difficulty, illustration, author). Diff is a **sentence, not colour** — survives a screenshot and a screen reader. Ticked with that noted, since the TODO line says "diff highlight" |
| **E6.13** Delete flow | `BankView.confirmDelete` → blocked dialog naming `session.blockingExams()`, plain confirm otherwise |

---

## 10. Verification

```
export JAVA_HOME=<temurin-21.0.12+8>
HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_retire ./mvnw -B clean verify
```

| | |
|---|---|
| **Result** | **BUILD SUCCESS** |
| **Tests** | **5,982 run · 0 failures · 0 errors · 0 skipped** |
| **MySQL** | required and used (`hsts_retire`), not skipped |
| **Coverage gate** | passed |
| **Compiler warnings** | none |
| **Wall time** | 19:53 |

**On the test count**: 5,982 is the post-retirement number. I did not capture a pre-change baseline
before starting, so **I am not quoting a delta** — four test classes and three cases were deleted,
but a great many suites here are parameterized, so subtracting file counts would be a guess dressed
as a measurement. If you want the exact delta it is one `git stash` and one run.

**One honest note on the runs.** The first full verify came back with a single failure, and it was
mine: my new `theLegacyExclusionIsRetired` asserted every type in `common/dto/bank` is a record,
which red-lines `Difficulty` and `ImageAction` — enums that were always there and are not DTOs. Fixed
by stating the property I actually meant (record **or enum**, so no mutable-class DTO hides from the
component scan) rather than by weakening the check. The second full run is the green one above.
Recorded because a guard I wrote and then had to correct is exactly the kind of thing a reviewer
should see rather than discover.

`DeepSeekProviderTest` did not flake on either run, so the known WSL clock-drift rerun was not needed.

---

## 11. For Member A

1. **The V8 finding (§7)** is the one thing here that changes a plan rather than executing it. If
   you disagree that no migration ever created the prototype's table, that is the assertion to
   re-check first — the list of all twenty tables in §7 is what I read it off.
2. **`ProtocolLoopbackTest`'s stub handler (§5.4)** is the biggest judgement call. The alternative
   was wiring the real bank, which needs Hibernate and MySQL to prove a transport property. Argued in
   the class javadoc; say the word and I will swap it.
3. **`theRailIdServesTheVersionedBank` reads source** for the builder mapping (§5.2), because
   `BankView` cannot be constructed without an FX toolkit. Same trade-off `BankWiringGuardTest`
   already makes, and stated in the test rather than buried.
4. **E6.12's "diff highlight"** is a change-summary sentence, not colour highlighting (§9). Ticked on
   that reading; unticked easily if you meant literal highlighting.
5. **`DatabaseConfig` is orphaned** by this PR (§5.6) and I left it in deliberately. Your call — one
   line to delete it.
6. Nothing is committed. Working tree only.
