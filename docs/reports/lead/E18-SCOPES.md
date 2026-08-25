# E18-SCOPES — the lock snapshot's existence oracle, and the subscriber that could never be called

**Branch:** `main` (working tree, uncommitted) · **Base:** `c2bcc8f` · **Date:** 2026-08-25
**Findings fixed:** Member A's PR20 §5.3 (`feat/e6-bank-lock-chip`, arriving as #49) and §6.2 / P-10

Two findings from Member A's PR #49 review, both ruled mine. Both are the same class of defect —
**a promise in a javadoc that the code does not keep** — and in both cases the promise was half
true, which is why neither showed up as a failing test.

---

## 0. What you need to know first

1. **PR20.md is not on `main`.** It lives on `feat/e6-bank-lock-chip` at `4e7181b` and arrives
   with #49. Sections 5.3 and 6.2 were read from that ref. Nothing in this work depends on that
   branch merging first.
2. **P-10 and P-11 do not exist in `docs/PROBLEMS.md` on `main`** — the log stops at P-9. So
   **PROBLEMS.md is untouched by this change**, per the instruction: the entry arrives with #49
   and this fix pre-dates it. When #49 lands, P-10's entry wants one line appended:
   *"Fixed on main 2026-08-25, before this entry landed: `ClientEventBus.register` refuses a
   subscriber the bus cannot reach — see `docs/reports/lead/E18-SCOPES.md` §2."*
3. **Two uncommitted javadoc corrections in `Verb.java` were already in the tree** (the lock-group
   identity note and LOCK_WATCH's un-watch paragraph, both PR20 §5.1/§5.2). They are deliberate,
   they ride this change, and they are untouched.
4. **One instruction was followed to the letter and then measured to be wrong.** The P-10 guard
   was specified as "assert the class and its enclosing classes are public". The enclosing half is
   incorrect and would have broken the existing suite. §2.2 has the measurement.

---

## 1. TASK 1 — `LOCKS_SNAPSHOT` course-scoping

### 1.1 The defect, restated

`EditLockService.snapshot` and its handler applied `requireRole(TEACHER, COORDINATOR)` and
**nothing else**, then walked the raw ids. `Verb.LOCKS_SNAPSHOT`'s javadoc claimed it "is not an
existence oracle for rows the caller may not be allowed to see".

That claim held in **one direction only**. Absence was safely ambiguous — free, unknown and
non-existent were one answer. **Presence was not**: an entry proved that a row exists, that
somebody is editing it, and named the editor, for a course whose every bank read verb answers
`NOT_FOUND` out of scope and is indistinguishable from a row that does not exist *on purpose*
(BANK_WIRE_CONTRACT §2). Half a claim, and the half that was false is the half the sentence was
written for.

No correctness is reachable through it and students are gated out, so this is not a P-5 repeat —
which is exactly why it survived: nothing about it looked urgent enough to fail a build.

### 1.2 The seam: `EntityScopes`

New file: `server/features/locks/EntityScopes.java`.

A registry of `entityType -> EntityScope`, where `EntityScope` is a `@FunctionalInterface` with
one method:

```java
boolean reaches(long callerId, long entityId);
```

Ids on both sides and nothing else, which is what keeps `EditLockService` generic. The lock
service consults a lambda a feature installed at wiring and **never learns what the answer means**.

**Shape borrowed from `Authorization.useCourseTeachers`:** a functional seam a unit test satisfies
with a two-line lambda, bound once at assembly, with `install` returning the previous value so a
caller can put it back.

**One deliberate deviation: it is an instance, not a static field.** `Authorization`'s own javadoc
argues at length that services should prefer the overload that "depends on nothing global", and a
registry owned by the one `EditLockService` instance gets that for free — two tests cannot leak
scopes into each other and there is no restore step anybody can forget. It is reached through
`EditLockService.scopes()` rather than the constructor because of assembly ordering: the question
scope is a lambda over the bank's repositories, and in `HSTSServer` the lock service is built
before the bank is.

### 1.3 The contract — **an uninstalled type is unfiltered**

This is the design decision in the change, so it is stated rather than left to be discovered:

> `EntityScopes.reaches` answers `true` for any entity type nobody has installed a scope for.

That runs **the opposite way** to `Authorization.CourseTeachers.UNWIRED` and every fail-closed
guard in the tree. Three reasons, all in the class javadoc:

1. **A type nobody registered a scope for has made no scoping promise.** An `Authorization` guard
   is asked "may she?" and a missing data source means it cannot tell, so it must refuse. This is
   asked "is this one of hers?" about a type whose owning feature has not claimed the question is
   meaningful. Refusing would be inventing a policy on that feature's behalf.
2. **Fail-closed here would break four working features silently.** `exam-version` (E7),
   `bot-source` (E16), `execution` (E9) and `grade` (E12) all lock through this service and none
   installs a scope. Under a fail-closed default every snapshot would answer empty and every watch
   would register nothing — no error, just a chip that never lights. That is the P-10 failure mode
   one tier down.
3. **The risk is not symmetric.** A missing `Authorization` directory lets a caller *write*
   somebody else's data. A missing scope here lets a caller learn that a lock exists on a row of a
   type nobody has decided is sensitive — which is the state all four are in today, and which this
   class does not make worse.

Installing a scope is therefore how a feature **opts in**, and the absence of one is a reviewable
fact rather than a silent default. `EntityScopesTest$Uninstalled` pins all of it; flipping the
default to fail-closed as a planted mutation fails 15 tests.

### 1.4 What changed in `EditLockService`

**`snapshot` gained the caller.** Signature is now
`snapshot(long callerId, String entityType, Collection<Long> entityIds)`. Deliberately a changed
signature rather than an added overload: leaving the unscoped three-argument form in place would
leave the oracle reachable by anyone who called the convenient method.

Out-of-scope ids are **absent from the map** — the same absence a free id gets, and the same
absence an id that has never existed gets.

**One performance decision worth reading, because it looks like a security hole and is not.** The
scope is consulted **only for ids somebody is actually holding**:

```java
Held current = live(new EntityRef(entityType, entityId));
if (current == null) continue;                                  // absent either way
if (!scopes.reaches(entityType, callerId, entityId)) continue;  // present but out of scope
```

The answer is **identical** either way — `held.put` only ever runs for a live hold, so filtering
before or after the liveness check produces the same map. What changes is cost, and at this verb's
scale that matters: `MAX_IDS` is 500 and a scope consult is a database transaction, so filtering
first would mean up to 500 reads to remove entries that were never going to exist. Filtering the
held ones costs one read per row somebody is actually editing — on a bank page, nought to a
handful.

The residual is a timing signal: a batch containing a held id takes marginally longer than one
containing none. Accepted, and named: it says nothing about *which* id, and the map lookup it
rides on predates this filter.

**`watch` filters registration.** An out-of-scope watch is **silently not registered** and answers
`LockResponse.free(entity)` — not granted, no holder, no expiry, which is exactly what a watch on
a genuinely free entity returns. `OK` rather than `FORBIDDEN`, because a refusal would itself be
the disclosure.

### 1.5 The watch-registration decision, and why registration over delivery

Filtering the snapshot alone would have been worth very little: a caller could watch an
out-of-scope id and be told who holds it by the next `PUSH_LOCK_CHANGED` instead of by the
snapshot. `BANK_LIST` would never show her those ids, but that is the client's discretion, not the
server's defence.

Two places could close it. **Registration won on cost:**

| | Consults | When |
|---|---|---|
| `LOCK_WATCH` handling (**chosen**) | one, in the place that already holds caller, entity and scope | once per watch |
| `publish` recipient filtering | one per recipient per lock change | on the hot path, forever |

Filtering at publish buys the same answer repeatedly. And the proportion is right: a list screen
already spends **one message per row** on `LOCK_WATCH`, so one consult per row is proportionate to
what the client is doing anyway — O(1) extra per message, not multiplicative. `LOCKS_SNAPSHOT` is
the opposite shape (one message, up to 500 rows), which is exactly why the held-only optimisation
in §1.4 belongs there and not here.

### 1.6 What is **not** scoped, named rather than hidden

**`acquire` is not scoped.** Its refusal names the holder, so it is the same disclosure one verb
over. Narrowing it changes what the E6.14 editor does the moment it opens, which is a decision of
its own and not one to make silently inside a snapshot fix. It is recorded in `EditLockService`'s
rule 6 and pinned by `Scoping.acquireIsNotScoped`, so whoever closes it has to come here and say
so. **This is the one item in this report that is still open.**

### 1.7 The wiring, and the duplication it avoided

`HSTSServer.questionLockScope(sessionFactory)` builds the predicate;
`installQuestionLockScope` installs it after the bank's read handlers are registered.

Per consult, in one transaction: `QuestionLockKey.displayIdOf(entityId)` →
`questions.findActiveByDisplayId` → membership in
`BankBrowseService.reachableCourseCodes(courses, session, callerId)`.

Three things are load-bearing:

- **`QuestionLockKey.displayIdOf` is new, and is the inverse of `of`.** It zero-pads to five.
  `Long.toString` would answer `"1003"` where `of("01003")` keyed `question#1003`, matching no row
  in `uq_questions_display_id` — and the failure would be **invisible**: the scope would resolve
  nothing, read it as "you do not reach it", and silently hide every lock on every course whose
  code starts with a zero. It lives in `QuestionLockKey` because that file is the one home of the
  numbering rule and an inverse kept elsewhere is a second implementation of it.
- **`BankBrowseService.reachableCourseCodes` is extracted, not duplicated.** The union "taught,
  plus coordinated" was written out once in `BankBrowseService.Scope` and was about to be written
  a second time in the wiring. Two copies of a scope rule is precisely how a snapshot starts
  disclosing a lock on a course the browse says is empty — the disagreement this fix exists to
  close. `Scope.forCaller` now calls it and still memoizes.
  *(It was first put on `CourseRepository`. That was wrong and was reverted: `courses` is a
  Mockito mock in `BankBrowseServiceTest`, so moving the union behind one repository method
  bypassed the two stubbed calls and destroyed the real coverage of 20 existing tests. Taking the
  repository as an argument keeps the mock as the thing that answers.)*
- **No principal branch, and that is checked rather than assumed.** She holds no rows in either
  table the union is built from, so the predicate would answer her nothing — but she never reaches
  it: both verbs run `requireRole(TEACHER, COORDINATOR)` first and `PRINCIPAL` is neither. Adding
  a branch for a caller the role gate excludes would be an untested claim about an unreachable
  path, which is the P-6 shape. `noRowsReachesNothing` records what it *would* answer.

A malformed id (negative, or above 99999) is refused quietly rather than thrown: this runs inside
a snapshot serving forty other rows, and one hostile id must not take the answer down.

### 1.8 The javadoc, corrected and dated

`Verb.LOCKS_SNAPSHOT` no longer claims what it did not do. The "not an existence oracle" sentence
is now stated **in both directions**, dated 2026-08-25, crediting PR20 §5.3, naming the
unfiltered-when-uninstalled rule, and pointing at `LOCK_WATCH` for the other half.
`Verb.LOCK_WATCH` gained a matching paragraph on scoped registration.

**One small addition beyond the two assigned findings.** Both `Verb.LOCKS_SNAPSHOT` and
`EditLockService.snapshot` still carried *"one snapshot at load plus the pushes afterwards is the
complete picture"* — the claim PR20 §3 falsified and explicitly noted "was not corrected". It is
true only if `LOCK_WATCH` is sent first, because the server resolves push recipients at the
instant the lock changes. Since §5.1's sibling correction was already mine and I was rewriting
these exact paragraphs for truthfulness, leaving a known-false sentence inside them would have
been odd. Both are now qualified and dated, crediting §3 and P-11. **This overlaps #49's
territory** — Member A fixed the ordering in `BankRowLocks` and may also want the contract text —
so if their branch touches it, take theirs.

---

## 2. TASK 2 — `ClientEventBus.register` accessibility assert (P-10)

### 2.1 The defect

greenrobot invokes `@Subscribe` methods reflectively. A `public` method on a **non-public class**
registers perfectly happily and then throws `IllegalAccessException` on every delivery — which
`RequestDispatcher` catches and logs **on purpose**, so one bad subscriber cannot drop the socket.

The result is the worst shape a defect can have: a screen that paints once, never updates, throws
nothing a user sees, and leaves the suite green — because tests construct their subscribers
directly and post to them, so the reflective path is never the one under test. Member A hit it
building the bank list chip (`BankRowLocks` was package-private first), then audited all fourteen
`@Subscribe` classes and found every one already `public final`. So this guard is for the
fifteenth.

`register` now refuses, at **registration time** — where the developer is looking, with `start()`
in the stack trace — with an `IllegalArgumentException` naming the class:

> `@Subscribe classes must be public: <class> is not. The bus invokes reflectively and a
> non-public class delivers nothing, silently — P-10`

### 2.2 ⚑ The specified check was wrong, and the suite proved it

The instruction was to assert the subscriber's class **"and its enclosing classes if nested"** are
public. **That over-rejects, and it is not a theoretical concern.**

The walking version was written first. It rejected `ClientEventBusTest.RecordingSubscriber` — a
`public static` class inside a package-private test class — and **turned five passing tests red**.
Those tests post through the real greenrobot bus reflectively and had been green since E1.8. The
shape the walk called unreachable had been delivering events all along.

Measured rather than argued about, with a throwaway probe (caller in a *different* package, to
model greenrobot):

| subscriber class | `getModifiers()` public | `accessFlags()` public | cross-package reflective invoke |
|---|---|---|---|
| `public` nested in **package-private** outer | true | true | **OK** |
| `protected` nested in public outer | false | false | **OK** |
| package-private | false | false | `IllegalAccessException` |
| anonymous | false | false | `IllegalAccessException` |

A nested class compiles to its own class file and javac gives a `public` **or `protected`** member
class `ACC_PUBLIC` in that file's own access flags. The JVM's access check reads those and never
consults the outer class.

So the implemented predicate is the subscriber's own class only, and accepts **public or
protected**:

```java
Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)
```

The `protected` case is where every Java-level API lies: both `getModifiers()` and Java 21's
`Class.accessFlags()` report `PROTECTED`, because they read the `InnerClasses` attribute, which
keeps the source-level modifier — while the class file carries `ACC_PUBLIC` and the invoke
succeeds. Testing only for `public` would refuse a subscriber that works, and **a guard that
breaks a working screen at startup is worse than the silence it was written to prevent.**

Anonymous and local classes report no modifier and are genuinely unreachable, so they are
correctly refused.

All of this assumes the classpath; nothing in this build declares a `module-info`, so package
export is not a second condition.

---

## 3. Test inventory

**40 tests added**, across five files, plus 9 existing call sites updated for the `snapshot`
signature.

| File | Added | What it pins |
|---|---:|---|
| `server/features/locks/EntityScopesTest.java` *(new)* | 10 | the registry: unfiltered-when-uninstalled, per-type isolation, `install` returns previous, `null` uninstalls, type normalisation, `isInstalled` vs `reaches` |
| `server/features/locks/LockVisibilityTest.java` → `$Scoping` | 12 | in-scope entries survive; a **held** out-of-scope entry is byte-identical to one that never existed; per-caller answers; unregistered type unfiltered; watch dropped silently; dropped watch hears no push; in-scope watch unaffected; `acquire` not scoped (recorded); and `$Scoping$OverTheWire` — both verbs honour the scope **through the router**, with the caller taken from the session |
| `server/core/QuestionLockScopeH2Test.java` *(new)* | 8 | the **production predicate** against a real database: own course reaches, another's does not, coordination widens, leading-zero ids resolve, unknown/impossible ids are out of scope not exceptions, soft-deleted out of scope, no-rows reaches nothing |
| `client/events/ClientEventBusTest.java` → `$SubscriberReachability` | 7 | package-private refused by name with the reason; refused *before* the bus is touched; public unaffected; anonymous refused; **public-nested-in-package-private accepted and delivers**; **protected nested accepted**; null refused |
| `server/features/bank/QuestionLockKeyTest.java` | 3 | `displayIdOf` round-trips, zero-pads, refuses impossible ids |

**On `QuestionLockScopeH2Test` — why it exists.** Every part of the wiring predicate is covered on
its own; the *composition* is where the interesting mistake lives (a truncating inverse, an
unstripped course code, an `orElse(true)`), and all three leave every part green. That is the P-8
shape — two green suites either side of one seam. It calls
`HSTSServer.questionLockScope` **itself** rather than rebuilding the lambda, which would test a
copy and prove nothing about the server (P-6). `questionLockScope` was extracted from the
installation for exactly that, and is named as production API that exists partly for testability.

**Deviation from the brief:** the scoping tests were specified as `EditLockServiceTest` additions.
They went into `LockVisibilityTest` instead, which is where every other E18.8 snapshot and watch
test already lives; putting E18.9's beside E18.1's would have split one feature across two files.

### 3.1 Planted mutations — **8 planted, 8 caught**

| Mutation | Result |
|---|---|
| snapshot filter removed | **5 failures** |
| watch filter removed | **4 failures** |
| `EntityScopes` default flipped to fail-closed | **14 failures, 1 error** |
| `displayIdOf` stops zero-padding (`Long.toString`) | **3 failures** |
| unknown question becomes reachable (`orElse(true)`) | **2 failures** |
| handler passes `0L` instead of `caller.userId()` | **2 failures** |
| P-10 check removed from `register` | **3 failures** |
| P-10 accepts `public` only, refusing `protected` nested | **1 failure** |

---

## 4. Files changed

**New (3)**
`server/features/locks/EntityScopes.java` ·
`server/features/locks/EntityScopesTest.java` ·
`server/core/QuestionLockScopeH2Test.java`

**Modified (10)**
`common/dto/lock/EntityRef.java` (`normalizeType` extracted, one home for the rule) ·
`common/protocol/Verb.java` (LOCKS_SNAPSHOT + LOCK_WATCH javadoc; **plus the two pre-existing
uncommitted corrections, untouched**) ·
`server/features/locks/EditLockService.java` ·
`server/features/bank/QuestionLockKey.java` (`displayIdOf`) ·
`server/features/bank/BankBrowseService.java` (`reachableCourseCodes` extracted) ·
`server/core/HSTSServer.java` (wiring) ·
plus 4 test files.

**Not modified:** `docs/PROBLEMS.md` — see §0.2. `server/db/repos/CourseRepository.java` —
reverted, see §1.7.

---

## 5. Verification

- `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_e18fix ./mvnw -B clean verify` on JDK 21:
  **BUILD SUCCESS — 6151 tests, 0 failures, 0 errors, all coverage checks met.**
  That is baseline + 40, the count in §3's table. No test was removed or disabled.
- 8 mutations planted, 8 caught (§3.1).
- `DeepSeekProviderTest` did not flake on either run; no rerun was needed.
- **Nothing committed**, per instruction. The working tree carries this change plus the two
  pre-existing `Verb.java` corrections, which are intact and verified present.

---

## 6. Open, stated rather than implied

1. **`LOCK_ACQUIRE` is still an existence oracle** for the same rows, one verb over (§1.6). Fixing
   it is a decision about editor behaviour, not a filter.
2. **P-10's log entry does not exist yet.** It arrives with #49; the appendix line is drafted in
   §0.2.
3. **PR20 §5.4's truncate-vs-refuse tension** (Member A's client-side cap truncates where the
   server javadoc argues truncation is "a wrong answer dressed as a right one") is untouched here.
   It is unreachable under the current page size and logs both numbers. Still needs a ruling.
