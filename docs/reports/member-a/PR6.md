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
