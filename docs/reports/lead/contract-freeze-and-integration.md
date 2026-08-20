# Grading wire contract (frozen) · E2 integration swap

**Owner:** [L] Naji · **Status:** complete, `./mvnw -B clean verify` green
**Build:** 1539 tests, 0 failures, 0 errors, 0 skipped · JaCoCo BUNDLE **98.76%** instruction (gate 90%), 94.93% branch
**MySQL:** reachable during this run, so every `@EnabledIf(MySqlAvailability)` leaf actually executed (nothing skipped)

Two packages in one working tree, deliberately: the contract artifacts (A) are what Member B builds
E12/E13 against, and the integration swap (B) is what makes the server they run on real.

---

## 1. What shipped

### Package A — the frozen grading wire contract, artifacts only

`docs/contracts/GRADING_WIRE_CONTRACT.md` implemented exactly: fifteen types in a new
`common/dto/grading` package, seven verbs in `common/protocol/Verb.java`, and nothing else. **No
services, no handlers, no client screens** — those are Member B's epics, and the point of landing the
contract on its own is that both sides now compile against one shape that is already tested.

- Every DTO is a `Serializable` record with `serialVersionUID = 1L`, null-checking the references it
  cannot be meaningful without and defensively copying every list, exactly as `common/dto/notify` and
  `common/dto/lock` do.
- **Range and blank validation is deliberately absent**, and the package javadoc says so and says why:
  a score outside 0..100 and a blank override justification are `VALIDATION` answers with a sentence
  for the teacher, not `IllegalArgumentException`s thrown inside a deserialization on an OCSF read
  thread. Those checks are Member B's E12 handlers.
- `package-info.java` states the freeze, points at the contract file, and records the one rule the
  package does enforce (see §3.1).

### Package B — the E2 integration swap

The two seams `UserDirectory` and `NotificationStore` were written for are now closed, and the
production router reads through MySQL.

- **`RepositoryUserDirectory.findById`** overrides the `default` method E17/E18 added after that class
  was written. Without it every E18 lock banner in the product reads "Another user" — a failure that is
  invisible to the feature owning the method and only shows up on a second person's screen.
- **`JpaNotificationStore`** implements `NotificationStore`, one `Transactions.inTx(...)` block per
  method over the existing `server/db/entities/Notification`. Ownership is in the queries: `markRead`
  is a single `UPDATE … WHERE id = :id AND userId = :userId AND readAt IS NULL`, so a foreign id
  updates zero rows and the `readAt IS NULL` clause *is* the idempotence.
- **`HSTSServer.defaultRouter`** now builds `new RepositoryUserDirectory(HibernateUtil.sessionFactory())`
  and `new JpaNotificationStore(sessionFactory)` — one factory, fetched once.
- **`ServerMain` reordered** so `DbBootstrap.migrate()` completes before `new HSTSServer(port)`. See
  §4.1: this is the item to double-check.
- **`InMemoryUserDirectory` and `InMemoryNotificationStore` are kept**, with their javadoc rewritten to
  say they are test fixtures and no longer production wiring.

---

## 2. File list

### New — `src/main/java/common/dto/grading/` (16 files)

| File | What it is |
|---|---|
| `package-info.java` | the freeze, the contract pointer, and what is deliberately not validated here |
| `GradeState.java` | wire enum `AUTO \| APPROVED`; "overridden" is `overrideReason != null`, not a state |
| `GradingQueue.java` | `GRADING_QUEUE_GET` answer |
| `ExecutionGradingSummary.java` | one execution in the queue, with its three progress counts |
| `ExecutionGradesRequest.java` | `GRADING_EXECUTION_GET` payload |
| `ExecutionGrades.java` | summary + every student's row |
| `StudentGradeRow.java` | the one grade shape all four screens are built from |
| `GradeReviewRequest.java` | `GRADE_REVIEW_GET` payload |
| `GradeReview.java` | teacher-only: grade header + marked paper |
| `AnswerReviewRow.java` | one question with chosen vs correct — the only type carrying an answer key |
| `GradeOverrideRequest.java` | `GRADE_OVERRIDE` payload; justification required (checked by the handler) |
| `ApproveRequest.java` | one verb for single and bulk approve |
| `ApproveResult.java` | approved / alreadyApproved / refused; idempotent by contract |
| `MyGrades.java` | student's approved rows, justification stripped |
| `CheckedFormRequest.java` | `CHECKED_FORM_GET` payload |
| `CheckedForm.java` | the E13.2 checked form, reusing `AnswerReviewRow` behind two guards |

### New — server

| File | What it is |
|---|---|
| `server/features/notify/JpaNotificationStore.java` | the durable store, one `inTx` block per method |

### Modified — main

| File | Change |
|---|---|
| `common/protocol/Verb.java` | +7 constants in a new "Grading & results (E12/E13)" section, between the lock section and the push section. No existing constant touched. |
| `server/db/repos/RepositoryUserDirectory.java` | `findById(long)` override; the entity → `UserRecord` mapping extracted to one private `toRecord` so both entry points map identically |
| `server/db/repos/UserRepository.java` | `findById(Session, long)` — `session.get` on the primary key |
| `server/core/HSTSServer.java` | production wiring swapped to the repository directory + JPA store; ordering rule documented on `defaultRouter` and on the production constructor |
| `server/core/ServerMain.java` | `DbBootstrap.migrate()` hoisted above `new HSTSServer(port)`; ordering rule documented in the class javadoc |
| `server/features/notify/NotificationStore.java` | seam javadoc updated: JPA is production, in-memory is a fixture, both held to one contract test |
| `server/features/notify/InMemoryNotificationStore.java` | javadoc: test fixture, no longer production wiring |
| `server/features/auth/InMemoryUserDirectory.java` | javadoc: test fixture, no longer production wiring |

### New — test

| File | What it is |
|---|---|
| `common/dto/grading/GradingDtoTest.java` | null-check, defensive-copy, immutability and student-wire behaviour (18 tests, 4 nested groups) |
| `server/features/notify/NotificationStoreContract.java` | the shared store suite, 16 tests, run by every implementation |
| `server/features/notify/JpaNotificationStoreContract.java` | the JPA branch: database lifecycle, two seeded users, plus 3 JPA-only tests |
| `server/features/notify/JpaNotificationStoreH2Test.java` | leaf: `TestDatabases.h2()` |
| `server/features/notify/JpaNotificationStoreMySqlTest.java` | leaf: `TestDatabases.mySql()`, gated by `MySqlAvailability` |
| `server/db/TestSchema.java` | `WIPE_ORDER` + `wipe(SessionFactory)`, lifted out of `RepositoryTestBase` (see §3.4) |

### Modified — test

| File | Change |
|---|---|
| `common/dto/DtoSerializationTest.java` | +10 tests: every new DTO round-tripped, including a grading DTO inside a `Message` envelope |
| `common/protocol/VerbTest.java` | +1 test pinning the seven verb names by `valueOf` (a rename would compile if it referred to the constant) |
| `server/db/repos/CorrectnessLeakGuardTest.java` | sanctioned suffix rule widened to `ForAuthoring` **or** `ForCheckedForm`, with the licensing argument in the javadoc; +1 test that only those two are sanctioned |
| `server/db/repos/UserDirectoryContract.java` | +3 tests for `findById` (runs on both engines) |
| `server/features/notify/InMemoryNotificationStoreTest.java` | now a leaf of `NotificationStoreContract`; keeps only what the map alone has (`size`, `clear`, the concurrency probe) |
| `server/db/RepositoryTestBase.java` | `wipe()` delegates to `TestSchema.wipe` |

---

## 3. Decisions

### 3.1 `MyGrades` strips `overrideReason` structurally

The contract says the justification is teacher and audit material and is *always* null on the student
wire. I made that a property of the type rather than a rule a handler has to remember: `MyGrades`'
compact constructor rebuilds any row that arrived carrying one. A future handler assembling the
student list from a teacher-side query therefore cannot leak it.

**This is the one place I went beyond a literal transcription of the contract**, and it is a
one-directional safety property (it can only remove data from the student wire, never add). It costs
one pass over a list of tens of rows. Pinned by two tests, one structural and one after a real
serialization round trip. Say the word and it comes out in one method.

### 3.2 Small derived accessors, no new fields or types

Beyond the contract's records I added only derived helpers in the house style of `isUnread()` /
`isNavigable()` / `isFree()`: `AnswerReviewRow.isUnanswered()`, `ExecutionGradingSummary.isFullyApproved()`,
`ApproveResult.isComplete()`, `ApproveRequest.one(long)`, `isEmpty()`/`size()` on the list-bearing
records, `GradingQueue.EMPTY` / `MyGrades.EMPTY`, and `GradeOverrideRequest.MIN_SCORE`/`MAX_SCORE`
(so Member B's handler and the client spinner cannot each invent their own range). **No component was
renamed, retyped, reordered, added or removed, and no type was added.**

### 3.3 `ForCheckedForm` is sanctioned, not exempted

`CorrectnessLeakGuardTest`'s naming rule now accepts two suffixes. The javadoc records *why* the
second one is licensed and, importantly, what would un-license it: E13.1's authorization tests, which
prove the three conditions on the handler and prove that failing any of them answers `NOT_FOUND`
indistinguishably. A suffix is a naming convention, not a guard; the guard is those tests. Both
companion tests still have teeth — `theNamingCheckHasTeeth` is unchanged, and a new
`onlyTwoSuffixesAreSanctioned` proves a name that merely *mentions* the words is not sanctioned
(`findForCheckedFormAndAlsoTheDashboard` fails).

### 3.4 The notification store contract needed the repository wipe

`notifications.user_id` is a foreign key to `users` (V7), so the JPA leaves have to seed real user
rows — and the shared MySQL schema is left seeded by whichever repository test class ran before them,
with rows pointing at users this class is about to replace. That needs the full reverse-dependency
wipe, not `DELETE FROM notifications`.

Java's single inheritance meant the JPA leaves could not extend both `NotificationStoreContract` and
`RepositoryTestBase`, so I lifted `WIPE_ORDER` + the wipe into `server/db/TestSchema.java` and had
`RepositoryTestBase.wipe()` delegate to it. **One list, two callers**, rather than a second copy that
would drift the first time a migration adds a table. `RepositoryTestBase`'s public behaviour is
unchanged and its wipe-order test still exercises the same code.

### 3.5 The store contract asks its leaves for user ids

`NotificationStoreContract` declares `userA()` / `userB()` hooks instead of `DANA = 1001L` constants,
because the map accepts any number and the real schema does not. That difference is exactly the sort
of thing a contract test run against one implementation only would never surface.

---

## 4. For the lead to double-check

### 4.1 The `ServerMain` ordering — the one item worth a real look

`ServerMain` now reads:

```java
DbBootstrap.migrate();          // Flyway first
HSTSServer server = new HSTSServer(port);
try { server.listen(); ... }
```

**Why it had to move.** `new HSTSServer(port)` builds `defaultRouter`, which now calls
`HibernateUtil.sessionFactory()`, which boots a HikariCP pool against `hsts_db` on the spot. Under the
old order the pool opened before Flyway had created or migrated the database: on a clean machine that
is a startup failure, and on a half-migrated one it is worse, because the pool comes up and the first
query fails somewhere that looks like a repository bug. It was harmless before only because the
pre-E2 wiring was entirely in memory.

**Second-order effect, please confirm you are happy with it:** `DbBootstrap.migrate()` used to sit
inside the `try` that catches `IOException` (it never threw one — a Flyway failure propagated out of
`main` either way), and it is now outside. A migration failure is still a loud stack trace out of
`main`, but it no longer passes through that block. Behaviour is the same; the shape changed.

The ordering rule is now stated in three javadocs so it cannot be un-learned by a later refactor:
`ServerMain` (class), `HSTSServer(int)` (production constructor), and `HSTSServer.defaultRouter`.

**Not verified end to end.** I did not boot the real server against `hsts_db` — that is a manual
smoke test (`ServerMain` is coverage-excluded and has no test). Worth one run before the demo: start
`ServerMain` against a *dropped* `hsts_db` and confirm it migrates and comes up clean.

### 4.2 Tests that construct `HSTSServer` — checked, none needed gating

Two, both already on the bring-your-own-router constructor, so no MySQL dependency crept into the unit
suite and **nothing needed an `MySqlAvailability` gate**:

| Test | Constructor | Verdict |
|---|---|---|
| `server/core/ProtocolLoopbackTest` | `new HSTSServer(port, sessions, router)` | fine, untouched |
| `server/features/auth/LoginIntegrationTest` | `new HSTSServer(freePort(), sessions, router)` | fine, untouched |

`ServerMain` is the only caller of the production constructor. I documented on the test constructor
that it builds no directory, no store and no session factory, so the next person adding a transport
test does not reach for the other one.

### 4.3 The `MyGrades` stripping (§3.1)

The one deliberate step past a literal transcription. Your call.

### 4.4 `GradeState` vs `GradeStatus`

Two enums with the same two constants — `common.dto.grading.GradeState` (wire) and
`server.db.entities.GradeStatus` (stored). That is the contract's shape and the right one (the Common
JAR must not carry an entity enum), but it means Member B's E12 service owns a two-line mapping in
both directions, and nothing currently fails if a third constant is added to one and not the other.
Flagging it rather than adding a test that would live in neither epic.

### 4.5 Coverage of the new code

`common/dto/grading` is at **100%** instruction coverage on all fifteen types;
`JpaNotificationStore` at 99.03% (2 instructions: the `ref == null` defensive branch and the count
clamp, neither reachable from a caller that obeys the interface); `RepositoryUserDirectory` and
`UserRepository` at 100%. No new JaCoCo excludes were added — the gate is met on merit, not on a list.

---

## 5. Verification

| Check | Result |
|---|---|
| `./mvnw -B clean verify` | **BUILD SUCCESS** |
| Tests | **1539** run, 0 failures, 0 errors, **0 skipped** |
| JaCoCo BUNDLE instruction | **98.76%** (16513/16720), gate 90% |
| JaCoCo BUNDLE branch | 94.93% |
| New JaCoCo excludes | none |
| MySQL leaves | executed (server reachable) — `JpaNotificationStoreMySqlTest` and `UserDirectoryMySqlTest` included |
| Frozen contract | implemented verbatim; no rename, retype, reorder, addition or removal of any component |
| House rules | no em dashes in user-visible strings (none added); javadoc-heavy style matched against `AuthService` and `common/dto/notify` |
