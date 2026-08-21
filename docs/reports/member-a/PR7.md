# E6 PR 3 — `requireTeachesCourse`, and the audit that found what I could not

**One guard, mirrored from yours.** Small on purpose: it is a foundation piece with no consumer
yet, which is exactly the case for reviewing it alone rather than tangled into the service that
will sit on it.

## 1. What is in it

`Authorization.requireTeachesCourse`, built part-for-part against `requireCoordinatorOf`:
a `CourseTeachers` interface with a fail-closed `UNWIRED`, a process-wide directory, a
`useCourseTeachers` installer returning the previous value, the frozen two-argument form, and the
three-argument overload a service with an open transaction calls.

Backed by `CourseRepository.teaches`, which already existed. **53 tests in the class, 19 on this
guard.**

## 2. One line for you, because it is your file

```java
// HSTSServer, beside the useSubjectCoordinators call at line 215
Authorization.useCourseTeachers((teacherId, courseCode) ->
        Transactions.inTx(sessionFactory, session -> courses.teaches(session, teacherId, courseCode)));
```

Match whatever shape line 215 already uses; I have not seen its surroundings, only that it exists.
**Until it lands the two-argument form refuses everyone**, which is the correct behaviour for an
unwired guard and is pinned by a test. Nothing calls it yet, so nothing is broken meanwhile.

## 3. The decision you asked to be flagged

**The guard means literally "teaches this course" and is deliberately not the bank's whole rule.**
A coordinator's scope is her coordinated subject (your ruling §7.3), so `QuestionService` composes:
TEACHER through this guard, COORDINATOR through `requireCoordinatorOf` on the course's subject.

Your own words are the argument, and they are in the javadoc: *asking the wide question and
filtering afterwards is how the wrong answer eventually gets used.* A guard whose name says
"teaches" but sometimes means "coordinates the subject containing" lies where a reader cannot
afford it. **Reversal: one method, about an hour, no callers exist yet.**

**And §6 below is the counter-argument to this, which I have not acted on.**

## 4. What the post-implementation audit found

The gate from P-6, run before this PR rather than after. **Five real defects in code with a green
suite**, and I verified each in the files before acting.

### 4.1 The refusal contradicted my own contract, which I had already corrected once

`BANK_WIRE_CONTRACT` §2: *"`NOT_FOUND` is the only answer for anything the caller cannot reach…
`FORBIDDEN` is for the role check alone and never for scope"*, because the alternative is an
existence oracle, which is the P-5 shape. **My guard threw `FORBIDDEN` for scope, naming the
course.**

On `QUESTION_CREATE` that leaks nothing: the caller supplied the code. On `QUESTION_UPDATE` and
`QUESTION_DELETE` it is a real oracle, because the service resolves the course from the stored
question, so a caller probing ids learns both that a question exists and which course it is in.

**The uncomfortable part:** §9 of that same contract is my own record of catching this exact
contradiction in the *pre-build* audit and fixing it. I then implemented it the forbidden way.
Being told a rule, writing it down, and still building past it is worth recording rather than
quietly correcting.

**Fixed** with a boolean sibling, `teachesCourse(...)`, which answers rather than throws so the
service can return `NOT_FOUND` itself. The throwing form stays for the create path. Both exist so
the correct tool is available rather than the convenient one being the only one.

### 4.2 A false claim in a javadoc, on the day I wrote P-6 about false claims

`useCourseTeachers` was documented as *"called once, from `HSTSServer`'s assembly"*. It is not, and
cannot be: that file is yours. The javadoc now says so and points at §2 above.

The audit reported this as both directories being unwired. **That half is wrong and worth
correcting:** `useSubjectCoordinators` *is* wired, at `HSTSServer:215`. Only mine is not.

### 4.3 The mutation I did not plant: `PRINCIPAL`

F9.3 gives the principal *"literally zero mutating verbs"*, and this guard fronts three of them.
Adding `Role.PRINCIPAL` to the role list would have broken F9.3 and left the **whole class green**
— there was a student test and a coordinator test and no principal test. Now there is; I planted
the mutation and watched `refusesThePrincipal` fail.

### 4.4 Three tests that could not fail

- `blankCourseRefuses` passed with the entire blank branch deleted, because a blank code also
  fails the directory lookup and throws the *other* refusal. Now asserts the message.
- The anonymous test asserted the exception **type** and not `UNAUTHORIZED`. Your sibling guard
  asserts the code in five places; mine in zero. An unjustified divergence, and the file's own
  javadoc says these two codes are asserted separately everywhere because getting them backwards
  produces a client logout loop.
- Nothing pinned that the role check runs **before** the directory is consulted. Now a directory
  that throws `AssertionError` if called proves the ordering.

### 4.5 Two smaller ones, fixed

`useCourseTeachers` was a non-atomic read-then-write on a volatile field: two installers race,
both see the same previous value, one write is lost, and "put the old one back" restores something
that never was. Now `AtomicReference.getAndSet`. **This is the one place I deliberately do not
mirror your shape**, and the same fix applies to `useSubjectCoordinators` if you want it.

And the course code is now stripped at the boundary. `courses.code2` is `CHAR(2)` under a PAD SPACE
collation, so `"11 "` matches the row in SQL while failing Java equality against the reachable-set
list the browse filters on. Two authorization answers for one input is P-6's disease; this is the
cheap end of it.

## 5. Guards planted and watched failing

Three, before trusting any of it:

| planted | caught by |
|---|---|
| `UNWIRED` returns `true` (fail-open on an unassembled server) | `unwiredRefuses`, `installingNullFailsClosed` |
| the `requireRole` line removed | `refusesAStudentEvenWithARow` — it hands the guard a directory that lies |
| `Role.PRINCIPAL` added to the role list | `refusesThePrincipal` |

## 6. The counter-argument to §3, which is yours to rule on

The audit disagrees with the narrow-guard decision, and the argument is good enough that I am not
deciding it myself.

Its case: `CourseRepository` **already** ships the other expression of the same rule, as a set
union — `findTaughtCourseCodes` ∪ `findCoordinatedCourseCodes`, whose javadoc says "the service
unions them". So the codebase now holds a per-course guard *and* a reachable-set union that must
agree and are checked against each other nowhere. §2 of the contract names that hazard exactly:
*"routing scope through an ad-hoc service check while a declared guard sits unimplemented is how
two answers to one question get shipped."* Landing the guard does not close that. **It doubles it.**

Its proposal: put the composition in `Authorization` as the API services see, either
`requireBankScope(caller, courseCode, ...)` or a `reachableCourses(caller, ...)` returning the
union so the guard and the `BANK_LIST` filter are literally the same call. Then the narrow guard
stops being the obvious thing to reach for.

**The failure mode it names is concrete and it is a demo account.** A `QuestionService` author who
calls only `requireTeachesCourse` and forgets the coordinator branch refuses `rina.barak` on all
three mutating verbs, with "You do not teach course 11", while the contract authorizes her for all
three. Nothing in the code or the tests would catch it; the catching artifact would be an
acceptance case logging in as her, and none is wired to this guard.

**It fails closed, which is the strongest thing about the current shape** — forgetting the branch
narrows, never widens. But "fails closed onto a starred demo account" is a thin defence three days
before a defense.

**Cost now: one method and its tests, before any handler exists. Cost later: three handlers and a
wire-contract freeze.** Your call, and it changes what I build next either way.

## 7. Verification

| | |
|---|---|
| Build | `./mvnw clean verify`, JDK 21, `HSTS_REQUIRE_MYSQL=true` → **BUILD SUCCESS** |
| Tests | **3868**, 0 failures, 0 errors, **0 skipped** |
| Coverage gate | met; bundle **98.30%**, unchanged from the merged baseline |
| `Authorization` | **100%** instructions, 187 covered, 0 missed |
| Both engines | 20 MySQL leaves ran with real timings |
| Staleness | nothing under `src` newer than the log |

No database work in this PR, so no two-engine pair: the guard is pure logic over a lambda, and
`CourseRepository.teaches` behind it already has H2 and MySQL coverage from E2.

## 8. Definition of Done

- [x] **Cold auditor run after the code was written, findings acted on** — five defects in a green
      suite, §4. The gate from P-6, and this is the PR it was written for
- [x] Matches the PRD ids named (F2.1, S-5, F9.3) and BANK_WIRE_CONTRACT §2
- [x] Unit tests; three failures planted and watched failing, §5
- [x] Coverage not lower than `main`: bundle unchanged, the changed class at 100%
- [x] Migrations unchanged
- [x] No secrets; `HSTSServer` and `common/**` untouched
- [x] `docs/TODO.md` — **no box ticked.** `requireTeachesCourse` is not itself an E6 task; it is
      the guard E6.1 through E6.6 will call, and ticking anything before the handlers exist would
      be claiming something a reviewer could disprove
- [ ] CI green — filled in after the run

## 9. Next

**Your bank DTOs and verbs are the green light**, and I am not starting handlers until they land.
Noted from your message: **the guard scan over `common/dto/bank` is yours**, so I will not write
`BankWireLeakGuardTest` — I had it scoped as mine and would have duplicated it, which is the same
hour the coordinator-lookup adjudication just cost.

When it lands, the first thing I do is **read your implementation against the contract** before
writing a handler on it. A contract implemented by someone other than its author is the highest-value
place to find a divergence, and finding it before the handlers is cheaper for both of us than after.

Filling the wait: the **rule-5 post-merge pass** over E8's nine repository additions, which you
requested. One finding already banked from reconnaissance, and it may be moot depending on what your
push includes: **`common/dto/approval` carries a real answer key and no build check scans it.**
`PreviewAnswerRow.correctOption` is legitimate and you documented it carefully, including keeping it
out of `toString`. But only `common/dto/exam` has a scanning guard, and your own ruling for my bank
package was that a licence the build enforces beats a licence the reader remembers. If the scan you
are writing extends to the whole family, this closes itself.
