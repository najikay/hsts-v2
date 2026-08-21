# E6 PR 1 — question bank: contract draft, validator, copy

**In progress.** Written as the work happens rather than assembled at the end, so the reasoning
recorded is the reasoning actually used.

**Closes E6.7 and E6.2's server half.** E6.1/E6.3/E6.4/E6.5 land once the wire types exist;
E6.8 is yours to freeze.

## 1. What needs you first

`docs/contracts/BANK_WIRE_CONTRACT.md` is drafted and marked DRAFT per your 2026-08-21 ruling.
**Five open rulings are indexed in its §7**, answerable down the list:

| | Ruling wanted | My proposal |
|---|---|---|
| §7.1 | `QUESTION_IMAGE_GET` vs TODO E6.6's literal `GET_QUESTION_IMAGE` | the noun-first convention; reword the TODO |
| §7.2 | does PRINCIPAL see the answer key (F9.3)? | yes, one `QuestionDetail`. Reversal: one record plus one allow-list entry, about an hour |
| §7.3 | **coordinator scope** | subject coordinated, not courses taught. See §3 D8, this one is not close |
| §7.4 | **legacy retirement timing** | now a sequencing question, not tidiness. See §4.4 |
| §7.5 | truncate the list stem server-side? | yes, length as a constant |

§7.2 and §7.4 are the two worth your attention before code is built on them.

## 2. The pre-build red team, and what it cost to skip a day of it

Run against the contract draft, the schema and the acceptance table **before any handler was
written**, on the PR 3a principle. Fifteen findings; **twelve changed the contract or the code.**
I verified the four load-bearing ones myself in the files before acting on them, per the rule that
the auditor reports confidently and is not always right. All four held exactly.

**The one that would have reached your manual walkthrough:**

> **A coordinator would have seen an empty question bank.** I scoped COORDINATOR by
> `CourseRepository.teaches`. `rina.barak` holds a `coordinators` row for subject 10 and **zero
> `course_teachers` rows**, deliberately, per the roster decision of 2026-08-20 whose stated
> purpose is to catch an implementation that derives coordinator-ness from the wrong table. She is
> a starred account in `DEMO_ACCOUNTS.md` and the approver in acceptance scenario 4. Every
> `QUESTION_GET` would have answered `NOT_FOUND`, her three mutating verbs would have been
> authorized and useless, and F4.1 requires her to preview exams built from questions she could
> not read.

`FacultySection`'s javadoc says Rina "is the row that catches it". It caught it, one epic later
than intended, and only because the audit ran before the handlers rather than after.

**The one that would have quietly weakened your other two guards:**

> The allow-list I specified had two entries. `CorrectnessNames.suggestsCorrectness` matches any
> name containing `correct`, so `QuestionDraft.correctAnswer` and `QuestionEdit.correctAnswer` trip
> it too: **the guard red-lines the day the package is created.** The tempting fix is widening the
> shared predicate, and `CorrectnessNames` is shared by `ExamWireLeakGuardTest` and
> `CorrectnessLeakGuardTest`. A guard that fails for a bad reason on day one is a guard someone
> weakens on day two.

The contract now lists four entries split inbound/outbound, and says the inbound two are not a
licence in the sense the take-exam guard means, so a future outbound DTO cannot be waved through
by pointing at `QuestionDraft`.

The full before/after table is `BANK_WIRE_CONTRACT.md` §9.

## 3. Decisions taken

| # | Decision | Why | Reversal |
|---|---|---|---|
| D1 | **`BankQuestionRow` carries no answers at all**, not merely no key | 40 rows per browse, re-rendered on every lock-badge push, and what a shared screenshot captures. The key is fetched one question at a time. Costs one round trip the lazy image load already required | one field |
| D2 | **`QuestionValidator` is a Strategy over a plain `Fields` record**, not over the wire DTOs | v1's add form checked duplicate answers and its edit form did not, so a question could be edited into a state it could not have been created in. Both payloads map into one `Fields` | none |
| D3 | **The bank does not reuse `TextNormaliser`** | its `groupingKey` javadoc says it "must destroy quite a lot" of meaning, which is right for grouping students' phrasings and wrong here: over-folding rejects legal questions | none |
| D4 | **`comparisonKey` is public** | E6.11 validates duplicates live while typing and must reach the same verdict as the server. Named as API shaped partly for a client | one modifier |
| D5 | **S-5's course check and E6.6's image rules are NOT in the validator** | both need a session or a byte array; keeping them out is what makes every rule testable without a database | none |
| D6 | **`ImageAction` is a three-state enum** | a null `image` on edit is ambiguous between "unchanged" and "cleared"; F2.1's editor has an explicit remove button | one enum |
| D7 | **Three message constants reworded rather than the copy test relaxed** | they failed §4.1's "every error says what to do next" as first written. The rule was right and the copy was lazy | none |
| **D8** | **Coordinator scope is the subject she coordinates, not the courses she teaches** | the empty-bank finding above. The alternative reading, "courses taught UNION courses in my subject", is the same set for `michal.sharon` and differs for nobody in the seed | one query; but it is an authorization semantic, hence §7.3 |
| **D9** | **`NOT_FOUND` for every unreachable question; `FORBIDDEN` only for the role check** | my §2 said one and my §6 said the other. `FORBIDDEN` on an out-of-scope question is an existence oracle, the P-5 shape. Both frozen contracts already say "indistinguishable on purpose" | one sentence |
| **D10** | **`lockVersion` removed from the wire** | `questions` is the identity row and never changes, so creating version n+1 never dirties it and `@Version` never increments. The echoed token would match forever. `baseVersionNo` plus `uq_question_versions_no` is what actually catches the race, and it was doing the job uncredited | one field |
| **D11** | **`QUESTION_DELETE` gains `baseVersionNo`** | update guarded concurrency with a token and delete guarded it with nothing, while the advisory lock is explicitly takeoverable (F10.4). A delete racing an edit was a coin toss | one field |
| **D12** | **`DeleteOutcome` carries `List<BlockingExam>`, de-duplicated by exam not exam version** | exam 101101 pins question 11005 in **both** v1 and v2, which `SeedDatasetContract` asserts. T-2.7's own demo case would have read "2 exams use it: Algebra Midterm, Algebra Midterm" | a record |
| **D13** | **`contentType` is re-sniffed on read; no `V8` migration** | it is the only field with no column behind it. The sniff already exists for the E6.6 upload check, PNG and JPEG have unambiguous magic numbers, and a nullable column that can disagree with its own blob is worse than a derivation | a migration, if you disagree |
| **D14** | **`comparisonKey` folds accents as well as case** | `utf8mb4_unicode_ci` is accent-insensitive, so `ck_question_versions_distinct` rejects `resume`/`résumé`. A case-only validator was **weaker than the constraint it claimed to backstop** and would have handed that pair to the database to refuse rudely | one method body |
| **D15** | **Length rules for text, answers and topic** | `VARCHAR(500)` and `VARCHAR(100)` truncation is an `SQLException`, not a `VALIDATION` naming the field. Without them T-2.2's "three different sentences" is false for one whole class of bad save | two constants, two rules |
| **D16** | **Violation field names are 1-based** (`answers[1]`..`answers[4]`) | a field reading `answers[0]` beside a message reading "Answer 1 is empty" is a coin toss for whoever writes E6.11, and a wrong guess highlights a box the teacher filled in correctly | one method |

## 4. Findings that are not mine to fix

### 4.1 `Authorization.requireTeachesCourse` is a frozen signature that throws

`Authorization.java` carries `requireTeachesCourse`, `requireEnrolled` and `requireCoordinatorOf`
with `TODO(E2)` bodies that fail closed, commented "bodies land with the repositories (E2.11)".
They did not: E2 shipped with the repositories and these three still throw.

That is my epic's leftover, and **E6 is the epic that must implement at least the first**. Named
here rather than fixed quietly because the contract originally routed S-5 scoping through an ad-hoc
`CourseRepository.teaches` call in the service while a declared guard sat unimplemented next to it,
which is how one question ends up with two answers. §2 now says E6 implements it.

### 4.2 The copy-rule tests scan constants only, so composed messages face no rules at all

`ExamMessagesTest` and `BotMessagesTest` scan public String **constants** and hold them to §4.1.
Both catalogues also **build** sentences in methods, and those escape entirely:
`ExamMessages.notJoinable` and `BotMessages.lockedOut` are checked only by hand-written cases.

`BankMessagesTest` closes it locally by feeding a sample output of every message-building method
through the same checks. That is how three of my own strings were caught (D7) and how the three new
length messages were kept honest. Lifting it into a shared scan is small and touches two files that
are not mine.

### 4.3 `QuestionRepository.findByDisplayId` does not filter `deleted_at` and names E6 as a consumer

Its javadoc says "Consumers: E6 bank search, and the E2.15 seed loader's idempotency check". The
method is **correct as it stands** — the seed loader needs a soft-deleted question to still count as
existing — but E6 must not reuse it, because T-2.8 checks that a soft-deleted question is gone from
the bank. E6 gets its own scoped lookup; the javadoc's invitation is the trap. One sentence to fix,
in my own file, and I will do it with the repository work rather than now.

### 4.4 The legacy pair makes the new guard prove less than it claims

`common/dto/bank` already holds `Question` and `QuestionUpdate`. **`Question.answer` is a real
answer key** and the new guard cannot see it: `answer` matches nothing in `CorrectnessNames`, and
`Question` is a mutable class rather than a record, so the record scan skips it.

So E6 would ship a guard that is green and silent about the only type in its own package that
already carries a key. This is why §7.4 changed from a tidiness preference into a ruling request:
either the legacy pair is retired **inside** E6, or §1's exclusion stands as written and we say so
out loud. I still prefer the separate PR for review-clarity reasons, but it is no longer purely my
call because it changes what the guard proves.

## 5. Verification

| | |
|---|---|
| `main` baseline, measured on `666db63` | BUILD SUCCESS, **3302 tests**, 0 failures, **0 skipped**, bundle **98.32%** |
| New tests | **132** in `server.features.bank` (112 mine, 20 pre-existing legacy), 0 failures |
| `QuestionValidator` coverage | **100%** instructions, 292 covered, 0 missed — E6.7's stated bar |
| `BankMessages` coverage | **100%** |

Baseline measured from this checkout, not quoted; PR5's 98.37% was a different tree.

## 6. Two notes worth a reviewer's minute

**The whitespace case earns the service-layer rule; the accent case nearly unearned it.**
`utf8mb4_unicode_ci` is PAD SPACE, so the CHECK already catches a trailing space and a case
difference. What SQL cannot see is a doubled space mid-string: `'New  York' <> 'New York'` is TRUE
in the database and false under ADR-016. That is the gap the rule fills. But the relationship only
holds if the service rule is stricter in **every** dimension, and it was not: the collation folds
accents and the validator did not, so the constraint was stricter than its own backstop in exactly
one direction. Both cases are now tested, and the second is tested with Hebrew as well, because
stripping combining marks must remove niqqud without taking the consonants with them.

**Rule order is load-bearing and pinned as such.** Structural rules run first so the later ones can
assume four non-null answers; without that, distinctness would need its own null handling and would
report "two answers are the same" for a question that has two answers. A test asserts that a
question with everything wrong reports the missing text rather than a consequence of it.

## 7. Definition of Done

- [x] Matches ARCHITECTURE §5 and the PRD ids named in the task (F2.1, F2.2, C-8, ADR-016)
- [x] Unit tests; **coverage not lower than `main`** — bundle 98.33% against `main`'s 98.32%, and
      `QuestionValidator` is at 100%, which is E6.7's own bar rather than the gate's
- [x] Migrations unchanged by this PR
- [x] No secrets; no dummy-credential changes in resources
- [x] `docs/TODO.md` — **E6.7 ticked**. E6.2 deliberately left unticked: the rules exist and are
      tested, but "server-side validation with precise error messages" means reachable by a
      caller, and the handler waits on §7's rulings
- [x] CI green — run 32484647276, conclusion success

---

# E6 PR 2 — the bank's two missing queries (E6.4, E6.5)

**Appended to this report rather than filed separately**, because it is the same epic and the
same reviewer pass. The PR is separate; this section is what changed after PR 1's commit.

## 8. What is in it

Nothing in the codebase could answer either question the bank screen has to ask.

**E6.5, the browse.** `QuestionRepository.findBankPage` plus `countBank`, returning a new
`BankQuestionSummary` projection: latest version per question, soft-deleted excluded, scoped
server-side, filterable by course, topic, difficulty and free text, paged and deterministically
ordered.

**E6.4, the blocked delete.** `findReferencingExams`, returning `ReferencingExam` (exam display
id and name), which is what T-2.7's dialog must list or the teacher has no next move.

**Scope resolution.** `CourseRepository.findTaughtCourseCodes` and `findCoordinatedCourseCodes`,
which is where §7.3's ruling actually lands in code.

**`findActiveByDisplayId`**, the `deleted_at`-filtered lookup E6 needs and the seed loader must
not have.

## 9. Decisions taken

| # | Decision | Why | Reversal |
|---|---|---|---|
| D17 | **`hasImage` is computed with `case when image is null`, never by selecting the blob** | `question_versions.image` is a `MEDIUMBLOB` holding up to 2MB. A projection that selected it would move up to 80MB to render forty rows of text. This is the difference between a list and an outage | none |
| D18 | **The browse returns the full stem; truncation is a service concern** | §7.5 is still an open ruling. Truncating in SQL means a query change if you prefer the wire to carry everything; truncating above means a constant | none |
| D19 | **`BankQuery` is a record with `allCourses` and `reachableCourses` as separate fields** | six parameters of which four are adjacent nullable strings is the shape METHOD flagged in PR 2b. More importantly, **empty scope and unrestricted scope must never be one state**, and a nullable list would make them one | one record |
| D20 | **Scope and filter are separate concepts in that record** | scope is authorization and comes from the role; `courseCode` is a filter and comes from the client. A filter naming an unreachable course is not a refusal, it simply intersects to nothing, which keeps the client's filter list a convenience rather than a boundary | none |
| D21 | **The WHERE is assembled, not written as one block of `:topic is null or ...`** | an unfiltered browse is the common case, and those disjunctions defeat the index on every filter at once | one method |
| D22 | **Search lowercases both sides in the query rather than leaning on the collation** | H2 does not reproduce `utf8mb4_unicode_ci`, so a collation-dependent search would behave differently on the two engines and the H2 leaf would certify something untrue. The MySQL leaf asserts the result on the engine that ships | none |
| D23 | **A new contract pair (`BankBrowseContract`) rather than more tests on `BankRepositoryContract`** | that class holds E2.11's authoring reads; this needs a different fixture entirely. Mixing them makes both harder to read | file move |
| D24 | **The blocking query collapses to the exam and takes its name from the latest version** | `exam_version_questions` is keyed on the version, and seed exam `101101` pins `11005` in both of its versions | none |

## 10. Three guards, planted and watched failing

Per the rule that a test not seen to fail is decoration.

**The de-duplication.** Replaced the collapse with the obvious join. Two tests fired:
`oneRowPerExamNotPerVersion` with **"Expected size: 1 but was: 2"**, which is literally T-2.7's
"Algebra Midterm, Algebra Midterm", and `nameComesFromTheLatestVersion` with "expected Current
Name but was Old Name".

**The scope predicate.** Made the WHERE skip the course clause when the reachable list is empty,
which is the natural-looking way to "handle" that case. `emptyScopeMatchesNothing` caught it:
**"Expecting empty but was: [BankQuestionSummary[displayId=11001, ...]]"** — every question in
the school handed to a caller entitled to none.

**And one claim of mine that the planting proved wrong.** I had written, in a javadoc and a test
comment, that the empty-scope short-circuit prevented a crash because `in ()` is invalid HQL.
It does not: Hibernate 6 expands an empty list into a false predicate on **both** engines, and
removing the short-circuit leaves every test green. The guard is an optimisation and is now
documented as one. The load-bearing property was somewhere else entirely, in the WHERE clause,
which is where the real attack landed. Recorded because a comment asserting a safety property
that is not there is worse than no comment: the next person trusts it.

## 11. One divergence worth your ruling, not mine to fix

**`RepositoryTestBase`'s roster predates the pure-coordinator decision.** The shared fixture has
`rina` teaching Calculus and coordinating Maths; the seed made her a coordinator who teaches
**nothing** on 2026-08-20, which is exactly the shape that caused PR 1's empty-bank finding.

So the fixture every repository contract inherits cannot express the case that just bit us. I did
**not** change it: it is shared by nine contracts and a roster change there ripples into other
people's tests. `pureCoordinatorReachesHerSubject` builds its own coordinator instead, and says
why in a comment.

Worth deciding whether the shared fixture should track the seed's roster. If yes it is a small
change and a wide blast radius, so it wants to be its own PR and probably not this week.

## 12. Verification

| | |
|---|---|
| New tests | **50** — 21 H2, 24 MySQL (21 shared + 3 collation-only), 5 pure unit |
| Both leaves | ran with real timings, neither skipped |
| `BankQuery`, `BankQuestionSummary`, `ReferencingExam` | **100%** instructions |

## 13. The post-implementation audit, and what it cost me to have skipped it once

Run against the whole E6 body of code after everything was green, with one instruction: find where
the query and its test agree with each other and both differ from the requirement. That is the
failure class no amount of my own testing reaches, because I wrote both halves.

**Fifteen findings. Five were real defects in shipped-quality code that passed 3464 tests.**

### 13.1 The distinctness rule was looser than the constraint it claimed to backstop

The one worth the whole exercise. `comparisonKey` folded canonically (NFD) and its javadoc claimed
equivalence with `utf8mb4_unicode_ci`. **It matched on two cases out of seven.**

Measured against the running database, not argued:

| pair | MySQL `utf8mb4_unicode_ci` | old rule |
|---|---|---|
| `resume` / `résumé` | equal | equal |
| `Strasse` / `Straße` | **equal** | different |
| `oeuvre` / `œuvre` | **equal** | different |
| `file` / `ﬁle` | **equal** | different |
| `A` / `Ａ` | **equal** | different |
| `τέλος` / `τέλοσ` | **equal** | different |
| `שלום` / `שָׁלוֹם` | equal | equal |

Every mismatch runs in the dangerous direction: **service accepts, database rejects.** The teacher
gets a raw constraint violation and a generic `INTERNAL`, which is precisely the outcome the
named-field message exists to replace. My test covered only the diacritic subset, which NFD does
handle, so the code and the test agreed and both differed from the requirement.

`sameAnswer` now does NFKD, strips combining marks, folds case with an upper-then-lower round trip
(which is what folds Greek final sigma, since `toLowerCase` leaves ς alone), then collates at
primary strength (which is what folds the ß and œ **expansions** that no normalisation performs).
All seven pass, and six deliberately-distinct pairs stay distinct.

**And the claim is now honest.** Exact equivalence with MySQL's UCA table is not achievable in Java
and is no longer asserted. The rule is one-directional by design: never accept what the database
will reject, and over-folding is the safe error.

### 13.2 A test that could not fail on the defect it claimed to pin

`emptyScopeMatchesNothing` — including the plant I reported in §10. `findBankPage` returns early
when the scope is empty, so `bankWhere` is **never reached** with an empty list. My planted attack
only failed because I had removed the short-circuit at the same time; with it in place, the WHERE
clause could be gutted and the test would stay green.

`scopeSurvivesWithoutTheShortCircuit` now covers it: a non-empty scope matching no questions does
reach the WHERE, so it fails if the course predicate is skipped or widened. §10 above overstated
what the original test proved, and is left standing with this correction rather than quietly
rewritten.

### 13.3 Free-text search treated LIKE wildcards as wildcards

Searching `100%` matched every stem containing `100`; searching `_` matched everything. Not an
injection risk, since the value is a bound parameter, but the one filter a teacher composes herself
is the one that can contain those characters. Now escaped with `escape '!'`, with a test covering
`%`, `_` and the escape character itself.

### 13.4 `deleteBlocked` threw away the display id the projection exists to carry

It took `List<String>` of names, so two exams called "Algebra Midterm" in different terms produce
**"2 exams use it: Algebra Midterm, Algebra Midterm"** — reintroducing one layer up the exact defect
D24's per-exam de-duplication was built to remove. My test passed two differently-named exams, so
the case was unreachable. Now takes `List<ReferencingExam>` and renders "101101 Algebra Midterm".

### 13.5 A Hebrew test that asserted nothing

`hebrewIsNotDestroyed` used unpointed strings on both sides, so deleting the mark-stripping
entirely would not have failed it. Replaced with a pointed-versus-unpointed pair, plus its
converse so the consonants still have to matter.

Also fixed: `deleteBlocked(List.of())` produced "0 exams use it: ." — unreachable today, one `if`.

### 13.6 What it confirmed rather than found

Worth recording because these were the parts I was least sure of. `findReferencingExams` is
correct on every edge it was asked about, and structurally so: `uq_exam_versions_no` makes the
latest-version join yield exactly one row per exam, and `fk_evq_question_version`'s composite key
makes a mismatched denormalised `questionId` unwritable. `countBank` has exact row parity with
`findBankPage`. `order by q.displayId` is a total order because of `uq_questions_display_id`.
`bankWhere` and `bindBank` do not disagree on any input. The length rules err in the safe
direction, because `String.length()` in UTF-16 units is always at least MySQL's character count.

### 13.7 Three findings deferred, with reasons

**The topic filter has no way to be driven.** It is exact equality on free text a teacher typed,
and there is no `findDistinctTopics`, so E6.11 cannot populate a dropdown and a typed topic misses
on any spacing difference. PRD F2.4 lists topic beside course and difficulty, which are closed
sets. That is a **feature gap, not a code defect**, and it wants your ruling: either topic becomes
a lookup fed by a new query, or F2.4's topic filter is a text match rather than a picker.

**`topic` is compared raw while `search` is lowercased**, so on MySQL `topic = 'Equations'` matches
`'equations'` and on H2 it does not. Invisible today because both sides of the test are
byte-identical and the client will send back a topic it read from the server. It becomes real the
moment a topic is typed rather than picked, which is the same ruling as above.

**The size clamp and `requireTeachesCourse` live nowhere yet.** Both are the service layer's, which
this PR does not contain. Named here so §2 of the contract is not read as satisfied.
