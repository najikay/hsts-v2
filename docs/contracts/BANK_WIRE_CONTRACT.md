# E6 question bank wire contract — DRAFT

**Status: DRAFT, 2026-08-21. All five open rulings answered; freeze pending the handlers.**
The lead's condition, verbatim: "Freeze happens on the PR review once E6's handlers exist against
it." So this stays DRAFT through the service PR and is not binding until this header says FROZEN.
Same additive-only terms as [EXAM_WIRE_CONTRACT.md](EXAM_WIRE_CONTRACT.md) once it is.

Package: `common/dto/bank` (all types `Serializable` records, wire-safe, no entity types).
Verbs group under `Question bank (E6)` in `common/protocol/Verb.java`.
Handlers: `server.features.bank.BankHandlers` (the three write verbs), over
`server.features.bank.QuestionService`. The read verbs get their own handler class beside it,
deliberately not the same one: §3 makes the verb-to-guard mapping what a reviewer checks, and two
sets of verbs carrying two different guards in one class is how the wrong one eventually gets
called.

**All five rulings are answered, and §7 keeps them with their reasoning** rather than deleting the
questions, because why the contract says what it says is worth more later than a tidy document is
now. **§9 records what a pre-build red team changed**, and **§13 of the PR report records what a
post-implementation one changed after this was first reviewed** — several of those are decisions
rather than typo fixes.

---

## 1. The rule this contract exists to enforce

**The answer key travels to staff who author, and to nobody else, and the build says so.**

This is the mirror image of the take-exam contract. There, `ExamQuestion` has nowhere to put a
correct answer, because the recipient is a student. Here the recipient is a teacher editing the
question, so the key **must** travel: an editor that cannot show which answer is right cannot be
used to author. The safety property is therefore not "no key on the wire" but "no key on a path a
student can reach", which is a weaker claim and needs a stronger guard to be worth anything.

Prose cannot enforce that. Per the lead's ruling of 2026-08-21, `common/dto/bank` gets its own
member of the leak-guard family, `server.db.repos.BankWireLeakGuardTest`, which **scans the
compiled package** and fails the build on any key-bearing record not on an explicit allow-list.
The predicate is the shared `CorrectnessNames.suggestsCorrectness`, which matches any name
containing `correct`, so **the four records below all trip it** and each needs a stated licence:

| Record | Direction | Licence |
|---|---|---|
| `QuestionDraft` | **inbound** | the teacher is submitting the key. Nothing to leak: the server already knows it |
| `QuestionEdit` | **inbound** | same |
| `QuestionDetail` | outbound | the detail pane and the editor's load. Staff-only verb, scoped |
| `QuestionVersionDetail` | outbound | version history renders old versions read-only; same audience |

**The inbound two are not the interesting half and should not be argued about.** Only the two
outbound entries are a licence in the sense the take-exam guard means. The test asserts the split
so a future outbound DTO cannot be waved through by pointing at `QuestionDraft`.

Everything else in the package must be keyless, `BankQuestionRow` above all.

**`BankQuestionRow` is deliberately keyless even though its caller is a teacher.** The list is the
high-volume payload: forty rows for a bank browse, one row per lock-badge refresh, and the thing
on screen when somebody shares a screenshot. The key is fetched one question at a time, by a verb
that names a single question. That costs one extra round trip when the detail pane opens, which
the lazy image fetch was going to cost anyway (F2.4, NFR-18).

**What the guard does NOT cover, stated rather than left to be discovered.** `common/dto/bank`
already holds the legacy `Question` and `QuestionUpdate` (§7.4). `Question.answer` is a real
answer key, and it is invisible to the scan twice over: `answer` matches nothing in
`CorrectnessNames`, and `Question` is a mutable class rather than a record. So for as long as the
legacy pair lives in this package, "the build says so" is false about the only types in it that
already carry a key.

**Ruled 2026-08-21, and the ruling closes that hole rather than accepting it (§7.4):** the two
legacy types go on the allow-list with a dated `LEGACY, RETIREMENT SCHEDULED` comment naming the
follow-up's whole scope. A named, dated exception is honest; silence was the problem. When the
retirement PR lands the allow-list shrinks by two, and that diff is the proof.

## 2. Roles and scope

- **Every verb is staff-only.** All seven require an authenticated caller.
  `Authorization.requireRole(TEACHER, COORDINATOR)` on the **three mutating verbs**
  (`QUESTION_CREATE`, `QUESTION_UPDATE`, `QUESTION_DELETE`); the **four read verbs** add
  `PRINCIPAL` (F9.3: read-only bank browse, literally zero mutating verbs authorized for the role).
  The role gate is the coarse half; the scope guards below are the half that matters.

- **There are TWO scopes, not one, because the specification splits them** (lead's ruling,
  2026-08-21). Reading the bank and writing into it are different questions with different answers,
  and collapsing them was the flaw in every earlier draft of this section:

  | Role | May READ | May WRITE |
  |---|---|---|
  | TEACHER | courses she teaches | courses she teaches |
  | COORDINATOR | every course of her coordinated subject | **only courses she also teaches** |
  | PRINCIPAL | every course (F9.3) | nothing, ever (F9.3) |

  **Reading is visibility; writing is authoring, and the sources differ.** Spec §3 and F2.1 say a
  teacher creates questions *only for courses she teaches* — that is the write rule and it admits
  no coordinator exception. §7.3's wider scope was always about reads: a coordinator previews and
  approves exams built from a bank she must therefore be able to see, which is F4.1 and F4.2's
  need, not an authoring licence.

  So a coordinator who does not teach a course **may read its bank and may not add to it.** The
  read scope is `reachesCourse`; the write scope is `requireTeachesCourse` and its boolean sibling
  `teachesCourse`. §3 names which applies to each verb and whether it throws or answers.

  **The read scope has a boolean form only, and that is not an omission.** §7.7's ruling named the
  union guard `requireBankRead`, a throwing form matching its write-side sibling. §3's table then
  gave the throwing form no verb to serve: all three scoped reads resolve their course from a
  *stored* question, where throwing a `FORBIDDEN` that names the course is the existence oracle §2
  forbids, and `BANK_LIST` is a filter rather than a guard. A throwing guard with no caller is a
  licence for a future handler to use the wrong one, so it was not written. The ruling's substance
  — two scopes, never composed — is unchanged and is what shipped.

  **Why the read half cannot be narrowed.** `rina.barak` holds a `coordinators` row for subject 10
  and **zero `course_teachers` rows**, deliberately, so that an implementation deriving
  coordinator-ness from the wrong table fails (`FacultySection` javadoc, roster decision
  2026-08-20). She is a starred account in `DEMO_ACCOUNTS.md` and the approver in acceptance
  scenario 4. Scoping reads by teaching would show her an empty bank.

  **And what the write half costs her, stated rather than discovered:** she cannot create, edit or
  delete a question in any course she does not teach, which under the seed's roster is all of them.
  That is correct — she is the approver, not an author — and nothing in the demo needs her to
  write. If a demo script ever has her adding a question, this table is why it fails.

- **Scope is enforced server-side, never by the filter.** `BANK_LIST` intersects any course filter
  with the caller's reachable set rather than trusting it. `QUESTION_GET` on a question outside
  that set answers `NOT_FOUND`.

- **`NOT_FOUND` is the only answer for anything the caller cannot reach.** Unknown, soft-deleted
  and out-of-scope are one answer, indistinguishable on purpose, matching both frozen contracts
  verbatim. `FORBIDDEN` is for the **role** check alone and never for scope: a caller who may not
  use the verb at all learns nothing about which questions exist. The alternative leaks an
  existence oracle, which is the P-5 shape.

- **No payload carries a caller id.** Authorship is `CallerContext.userId()`, so a question cannot
  be created in somebody else's name.

- **Both guards live in `Authorization`, not in the service.** `requireTeachesCourse` landed with
  PR #20; `reachesCourse` landed with PR #24. Between them they are the only place the
  table above is expressed. Routing scope through an ad-hoc service check instead is how two
  answers to one question get shipped, which is the hazard the audit correctly named even though
  its proposed fix was the wrong one (§7.7).

## 3. Verbs

| Verb | Caller | **How scope applies** | Request payload | OK payload |
|---|---|---|---|---|
| `BANK_LIST` | teacher, coordinator, principal | **filter**: the reachable set | `BankListRequest` | `BankPage` |
| `QUESTION_GET` | teacher, coordinator, principal | `reachesCourse` → `NOT_FOUND` | `QuestionRequest` | `QuestionDetail` |
| `QUESTION_VERSIONS` | teacher, coordinator, principal | `reachesCourse` → `NOT_FOUND` | `QuestionRequest` | `VersionHistory` |
| `QUESTION_IMAGE_GET` | teacher, coordinator, principal | `reachesCourse` → `NOT_FOUND` | `QuestionImageRequest` | `QuestionImage` |
| `QUESTION_CREATE` | teacher, coordinator | `requireTeachesCourse` (throws) | `QuestionDraft` | `QuestionDetail` |
| `QUESTION_UPDATE` | teacher, coordinator | `teachesCourse` → `NOT_FOUND` | `QuestionEdit` | `QuestionDetail` (the new version) |
| `QUESTION_DELETE` | teacher, coordinator | `teachesCourse` → `NOT_FOUND` | `QuestionDeleteRequest` | `DeleteOutcome` |

**The column is the point of the split.** Handlers compose nothing: the verb determines which
scope applies and how, and **a handler using the wrong one is visibly wrong in review** against
this table. That is what the two-guard shape buys over one composed guard deciding internally,
where the same mistake is invisible.

**Only `QUESTION_CREATE` throws, and the reason is the existence oracle again.** It is the one verb
where the caller *supplies* the course, so a `FORBIDDEN` naming it tells her nothing she did not
already know. Every other scoped verb resolves the course from a **stored** question, where naming
it would tell a caller probing ids both that the question exists and which course it belongs to.
Those use the boolean forms and answer `NOT_FOUND` themselves, per §2. This contract has already
been wrong about that once (§9), and the correction is why the boolean siblings exist at all.

**`BANK_LIST` is a filter, not a guard, and this table said otherwise until it was corrected.**
There is no single course for a guard to check: the browse intersects the caller's reachable set
with whatever she filtered by. An earlier version of this column named `requireBankRead` on that
row, which is a rule no handler could implement.

**One reachable set, computed once per request.** The union
(`findTaughtCourseCodes` ∪ `findCoordinatedCourseCodes`) serves both the `BANK_LIST` filter and the
three single-question reads, so the guard and the list can never disagree about what a caller
reaches. Two expressions of one rule checked against each other nowhere is §2's hazard; sharing the
query is how it is avoided rather than restated.

**The guard takes a lookup, never an answer.** `reachesCourse` receives a
`ReachableCourses` directory (`userId → Set<String>`), not a pre-computed set, for the reason
`CourseTeachers` does: a guard handed the answer cannot tell whether the caller computed it
correctly, and a handler passing the wrong set would pass the guard. The service memoizes per
request, so "one query" holds without the guard giving up its integrity.

No pushes. The bank list's live "being edited by" badges are **not** a bank concern: they ride
E18.8's existing `LOCK_WATCH` / `LOCKS_SNAPSHOT` / `PUSH_LOCK_CHANGED`, and the client merges lock
state onto rows it already has (F10.0). **No DTO here carries a lock field**, deliberately, so the
two cannot drift and viewing a list never contends for a lock.

**T-2.3's course filter comes from `COURSES_FOR_USER`, not from this contract.**
`CourseRepository.findForUser` already serves it and E4 already calls it. Named here because
acceptance case 2.3 reads like a bank requirement and is not one.

## 4. DTOs (`common/dto/bank`)

```
BankListRequest(String courseCode, String topic, Difficulty difficulty,
                String search, int page, int size)
      every filter nullable/blank = unfiltered; server clamps size to 1..100

BankPage(List<BankQuestionRow> rows, int page, int pageSize, long totalRows, int totalPages)
      pageSize, not size: NotificationsPage.size() already means "rows returned", and one
      name meaning two things across two contracts is a bug waiting for a client author.

BankQuestionRow(String displayId5, String courseCode, String courseName, String text,
                String topic, Difficulty difficulty, int latestVersionNo,
                boolean hasImage, Instant lastVersionAt)
      NO answer key, NO answers at all, NO image bytes. `text` is the stem, truncated
      server-side. lastVersionAt is the latest version's created_at: there is no
      updated_at column and questions rows never change, so the name says what it is.

QuestionRequest(String displayId5)

QuestionDetail(String displayId5, String courseCode, String courseName, int versionNo,
               int latestVersionNo, String text, List<String> answers, int correctAnswer,
               String topic, Difficulty difficulty, boolean hasImage,
               String authorName, Instant createdAt)
      answers is exactly 4, ordered 1..4; correctAnswer is 1..4 (C-8).
      latestVersionNo lets the detail pane say "you are looking at v2 of 3" without a
      second round trip, which F2.3's newer-version indicator needs anyway.
      NO lockVersion: see §5.

QuestionDraft(String courseCode, String text, List<String> answers, int correctAnswer,
              String topic, Difficulty difficulty, byte[] image)
      image nullable. No display id: the server allocates it (S-8, F2.2).

QuestionEdit(String displayId5, int baseVersionNo, String text, List<String> answers,
             int correctAnswer, String topic, Difficulty difficulty,
             ImageAction imageAction, byte[] image)

ImageAction = KEEP | REPLACE | REMOVE
      Three states, not two, because a null `image` is ambiguous between "unchanged"
      and "cleared", and F2.1's editor has an explicit remove button.
      KEEP copies the blob into version n+1: versions are immutable, so an illustrated
      question edited ten times stores the image ten times. That is the honest cost of
      ADR-011 and it is why QUESTION_IMAGE_GET is addressed by version.

QuestionDeleteRequest(String displayId5, int baseVersionNo)
      carries the same token as an edit, so a delete racing an edit is a CONFLICT
      rather than a coin toss.

DeleteOutcome(boolean deleted, List<BlockingExam> blockingExams)
BlockingExam(String displayId6, String name)
      deleted=false with a non-empty list is T-2.7. De-duplicated by exam, NOT by exam
      version: exam 101101 pins question 11005 in both v1 and v2, so a list of version
      names would tell the teacher "2 exams use it: Algebra Midterm, Algebra Midterm".
      displayId6 travels so the dialog can name the exam the way the teacher sees it.

VersionHistory(String displayId5, List<QuestionVersionDetail> versions)
      newest first; every version ever written, including the current one.

QuestionVersionDetail(int versionNo, String text, List<String> answers, int correctAnswer,
                      String topic, Difficulty difficulty, boolean hasImage,
                      String authorName, Instant createdAt)

QuestionImageRequest(String displayId5, int versionNo)
QuestionImage(String displayId5, int versionNo, String contentType, byte[] bytes)
      contentType is re-sniffed from the leading bytes on read. There is no
      image_content_type column and this contract does not add one: the sniff already
      exists for the E6.6 upload check, PNG and JPEG have unambiguous magic numbers, and
      a nullable column that can disagree with its own blob is worse than a derivation.

Difficulty = EASY | MEDIUM | HARD
      A WIRE enum in common/dto/bank, mapped to server.db.entities.Difficulty at the
      service boundary. Same members and same names, two types on purpose: §5's rule
      that no entity type travels is worth more than the duplicate declaration costs.
```

## 5. Rules the handlers enforce

**Scope, before anything else**, per §3's column: the reachable set filters `BANK_LIST`, the
boolean forms answer `NOT_FOUND` on the four verbs that resolve a course from a stored question,
and `requireTeachesCourse` throws on `QUESTION_CREATE` alone. Neither guard is composed inside the
other and no handler decides between them: the verb determines which applies, and the table is the
record.

**One normalisation, applied at the boundary.** Course codes are `strip()`ped before any scope
comparison, never `trim()`ped. The two are different functions — `trim()` cuts only characters at
or below U+0020 — and `courses.code2` is `CHAR(2)` under a PAD SPACE collation, so a code carrying
a Unicode space matches the row in SQL while failing Java equality against the reachable set. The
handlers strip regardless of what any DTO does, because two normalisations of one value is the same
"two answers to one question" this contract keeps having to close.

**Each guard's javadoc names the other and states the split's source**, so they read as two answers
to two questions rather than two answers to one. `requireTeachesCourse` cites spec §3 and F2.1,
authoring. `reachesCourse` cites §7.3 and F4.1/F4.2, visibility. A reader who finds one and not
the other is one link from the reason both exist.

**Validation (C-8, ADR-016, F2.1).** `QuestionValidator` is shared by create and edit, so the two
cannot diverge:

- text non-blank, at most 4000 characters
- exactly 4 answers, each non-blank and at most 500 characters (`a1..a4 VARCHAR(500)`)
- `correctAnswer` in 1..4
- the 4 answers pairwise distinct under `QuestionValidator.sameAnswer`, which folds **at least
  as much as `utf8mb4_unicode_ci` does** (below)
- topic non-blank, at most 100 characters (`topic VARCHAR(100)`)
- difficulty present

**The length rules and the distinctness rule are not padding, they are the difference between a
message and a stack trace.** Without lengths, a pasted paragraph in an answer box becomes a
data-truncation `SQLException` and the teacher sees `INTERNAL`.

**And the distinctness comparison has to be stricter than ADR-016's literal words**, because
`ck_question_versions_distinct` compares under `utf8mb4_unicode_ci`. Measured against the running
database rather than assumed, MySQL calls all of these one answer:

```
resume / résumé      Strasse / Straße      oeuvre / œuvre
file / ﬁle           A / Ａ                τέλος / τέλοσ
שלום / שָׁלוֹם  (Hebrew, unpointed vs pointed)
```

Any pair the service accepts and the constraint rejects arrives as a raw constraint violation and
a generic error, which is the outcome naming the field was meant to replace. So the rule is
one-directional by design: **never accept a pair the database will reject.** Being stricter than
the collation is safe, because the worst case is a teacher told two confusingly similar answers
are too similar. Being looser is the case with a stack trace in it.

**The named worst case, so it is not a hypothetical: `sameAnswer("1 2 3", "123")` is `true`.** The
`Collator` at primary strength treats a space as completely ignorable, so spacing alone never
separates two answers, and `"red car"` against `"redcar"` goes the same way. A sequence question
whose options are `1 2 3` and `123` is refused here even though `utf8mb4_unicode_ci` gives a space
a primary weight and would have stored both.

**The hyphen goes the same way and nothing else does.** Measured against the shipped validator on
JDK 21 rather than assumed: `co-op`/`coop` and `e-mail`/`email` fold, while `cat.`/`cat`,
`it's`/`its`, `3+4`/`34` and `A(1)`/`A1` do not. So the rule is **spacing and hyphens**, not
punctuation. The ruling's own wording said punctuation; the code has never done that, and the
teacher-facing sentence says what is true instead, because telling her punctuation will not save
her would make her rewrite an answer that a full stop would in fact have got past.

**Ruled 2026-08-22: keep it.** This is the one-directional rule working rather than an exception to
it: we refuse something the database would have accepted, never the reverse. Two things follow, and
both are shipped. `QuestionValidatorTest.spacingAloneNeverSeparatesTwoAnswers` pins the behaviour as
documented rather than accidental, and `BankMessages.answersDuplicated` tells the teacher the rule
she just hit, that answers must differ by more than spacing or hyphens. Without that sentence
she retypes one of them with a different space and gets the same refusal, which is a wall rather
than a rule.

Exact equivalence with MySQL's UCA table is **not** claimed and is not achievable in Java; the two
are separate implementations. The implementation is NFKD, strip combining marks, upper-then-lower
(which folds Greek final sigma), then a `Collator` at primary strength (which folds the ß and œ
expansions that no normalisation performs).

Each failure answers `VALIDATION` with **a message naming the offending field**, because T-2.2
tries three bad saves in a row and expects three different sentences. All copy lives in
`BankMessages`, one class, no em dashes (PRD §4.1).

**Editing creates a version, never mutates one (C-2, ADR-011, F2.3).** `QUESTION_UPDATE` inserts
version n+1 and returns it; exams pinned to version n keep version n, which is what T-2.5 checks
against exam 101101.

**The stale-editor race is caught by `baseVersionNo`, and there is deliberately no `lockVersion`
on this wire.** `questions.lock_version` carries `@Version`, but `questions` is the identity row
and never changes: creating a version row does not dirty it, so Hibernate never increments it and
an echoed token would match forever. What genuinely catches two teachers who both opened v3 is
`baseVersionNo` disagreeing with the current latest, backed by
`uq_question_versions_no UNIQUE (question_id, version_no)` underneath. Shipping an inert token
beside a working one is how the working one stops being trusted.

**Deleting is blocked or soft (F2.5).** Blocked while any exam version references any version of
the question, and the refusal names the exams. Otherwise `deleted_at` is stamped: the row leaves
every listing, the version history survives, and the serial is never reused (T-2.8).

**The block is a service query with no database backstop, and that is worth knowing.** Soft delete
is an `UPDATE`, and no foreign key fires on an `UPDATE` — the three `RESTRICT`s in the schema
prevent a *hard* delete, which is a different rule. So if the blocking query is wrong (checks only
the latest version, or only APPROVED exams) a referenced question silently leaves the bank and
nothing catches it. It gets a two-engine repository test naming the constraint it stands in for.

**Reads must filter `deleted_at`, and `QuestionRepository.findByDisplayId` does not.** That method
is correct as it stands, because the seed loader's idempotency check needs a soft-deleted question
to still count as existing. E6 gets its own scoped lookup rather than reusing it; T-2.8 is the case
that would otherwise pass in the seed and fail on screen.

**Images (E6.6).** At most 2MB, `image/png` or `image/jpeg` only, sniffed from the bytes rather
than trusted from a declared type. Never travels in the list or the detail;
`QUESTION_IMAGE_GET` fetches it per version.

## 6. Error codes

| Code | When |
|---|---|
| `UNAUTHORIZED` | no authenticated session |
| `VALIDATION` | any rule in §5, and a malformed payload; the message names the field |
| `FORBIDDEN` | **role** check failed. Never used for scope, see §2 |
| `NOT_FOUND` | unknown, soft-deleted, or out of the caller's scope (all three, indistinguishable on purpose) |
| `CONFLICT` | stale `baseVersionNo`, or the question is edit-locked by someone else |

`BAD_REQUEST` is deliberately unused here: a malformed payload answers `VALIDATION`, matching
`EXAM_WIRE_CONTRACT`.

## 7. Rulings, all five answered 2026-08-21

Answered by the lead in full. Kept here rather than deleted, because the reasoning is the record
of why the contract says what it says, and two of the answers are better arguments than the
questions were.

1. **Verb naming: `QUESTION_IMAGE_GET`.** "Convention wins. The TODO predates the convention and
   loses to it." `docs/TODO.md` E6.6 reworded in this PR.

2. **The principal SEES the answer key.** One `QuestionDetail`, staff-only, principal included, as
   drawn in §4. **And this is not the judgement call I framed it as:** spec §7.3.1 gives her
   read-only access to all data *as entered*, and the correct answer is entered data. F9.3's
   zero-mutating-verbs is her real boundary; the threat model was always students. Recorded because
   an argument from the specification outranks the cost-of-reversal argument I offered.

3. **Coordinator scope: every course in her coordinated subject**, exactly as §2 draws it. The
   red-team finding is the argument, in his words: a starred demo account opening an empty bank
   "would have been a defense-day disaster". The union alternative "adds nothing for anyone in the
   seed" and is dropped.

4. **The legacy pair is retired in its own PR, and the guard does not stay silent meanwhile.**
   Sequencing preference upheld: retirement lands right after E6 merges and before hardening on
   the 26th. But `common/dto/bank`'s `Question` and `QuestionUpdate` go on the leak guard's
   explicit allow-list with **a dated comment naming them LEGACY, RETIREMENT SCHEDULED**, and
   naming the follow-up's whole scope: `LegacyQuestionHandlers`, `QuestionDAO`, the legacy screen
   and the E18.4 guarded-update flow all retire together.

   His reasoning, which is stronger than my flag was: *"a named, dated exception is honest; silence
   was the problem. When the retirement PR lands, the allow-list shrinks by two and that diff is
   the proof."* The entry lands with `BankWireLeakGuardTest`, which needs the DTOs.

5. **Server-side stem truncation approved**, the constant documented, and the detail verb carries
   the full stem.

**Still open, and it is a freeze condition rather than a ruling:** the contract stays DRAFT until
E6's handlers exist against it. The lead's words: "Freeze happens on the PR review once E6's
handlers exist against it."

### 7.6 The seventh ruling: the topic filter becomes a lookup

**The question.** `topic` is exact equality on free text a teacher typed at create time, and no
query returned the distinct topics of a course, so E6.11 could not populate a picker and a typed
topic missed on any spacing or spelling difference. PRD F2.4 lists topic beside course and
difficulty, which are both closed sets.

**Ruled 2026-08-22: option A, the lookup.** The lead's three reasons in his own order of strength:
F2.4 reads as a closed set and A keeps that meaning; A is additive where B rewrites what the filter
means to someone who already learned it; and **A dissolves the raw-versus-lowercased defect instead
of patching it, because a picked value came out of the database**. So the comparison stays exact
and stays raw, and the asymmetry with `search` stops being reachable rather than being fixed.

There is a demo consequence the lead called out and it is worth keeping: the picker makes the
seed's deliberately thin `Recursion` topic **visible**, which sets up the F3.3 infeasibility demo
instead of hiding it behind a text field a teacher would never think to type into.

**What it costs, and where it lands.** `QuestionRepository.findDistinctTopics(courseCode)` with a
two-engine pair, a `BANK_TOPICS` verb carrying a `BankTopics` payload, and the picker in E6.11. The
wire half was missing entirely when this was ruled: neither the verb nor any carrier existed, and
`BankPage` has no field for it. The lead **opened `common/protocol` and `common/dto/bank` to Member
A for exactly that addition** rather than making it wait on his lane, and ruled that it lands
together with `findDistinctTopics` and its caller, which keeps the no-method-without-a-caller rule
intact. It is therefore **not** in the read-verbs PR; §3's row for it arrives with the code.

### 7.7 The sixth ruling, which arrived after the other five: two scopes, not one

Raised as an objection by the post-implementation audit of `requireTeachesCourse` (PR #20 §6),
which argued the narrow guard *doubled* the "two answers to one question" hazard rather than
closing it, and proposed one composed `requireBankScope`.

**Ruled 2026-08-21, and the ruling is better than either the objection or my original shape.** The
audit was right that the hazard was real and wrong about the fix. Replacing the narrow guard would
have merged two rules the specification keeps apart: spec §3 and F2.1 make authoring
teaching-scoped with no coordinator exception, while §7.3's wider scope was always about reads.

So: `requireBankRead` is the union guard implementing §7.3, `requireTeachesCourse` survives
unaltered as the write gate implementing F2.1, and **handlers compose nothing**. §2's table and
§3's guard column are the two places this landed.

**Implemented as `reachesCourse`, the boolean form, in PR #24**, and the ruling above is left as it
was made rather than rewritten around what shipped. The substance is untouched: two scopes, one per
verb, composed nowhere. Only the throwing form went unwritten, because §3's table left it with no
verb to serve once `BANK_LIST` was corrected from a guard to a filter and the three stored-question
reads were corrected to answer `NOT_FOUND` themselves. Recorded here so a reader who finds the name
in this ruling and not in `Authorization` learns why in the same paragraph rather than assuming the
guard was forgotten.

**What I had wrong**, recorded because the shape of the error is worth more than the correction:
my §2 gave a coordinator one undifferentiated scope, which silently granted her authoring rights
in every course of her subject. Nobody would have noticed until a coordinator wrote a question
into a colleague's bank and it was nobody's job to explain how.

## 8. What is deliberately absent

- **No student verb in this contract.** The one student-reachable read of the bank already exists
  and is not here: the study bot serves bank questions to enrolled students through
  `QuestionRepository.findBankForBot` and `BotBankQuestion` (S-28, F12.8), which is keyless by your
  ruling. Saying "no student-reachable path" without that qualification would be false, and it is
  the kind of absolute a reviewer tests at a defense.
- **No lock fields.** E18.8 already carries that, and duplicating it would let a stale badge
  disagree with a real lock.
- **No `courseCode` filter trusted from the client.** It is intersected with the caller's reachable
  set server-side; the client's filter list is a convenience, not a boundary.
- **No hard delete.** Three `RESTRICT` foreign keys make it impossible
  (`fk_question_versions_question`, `fk_evq_question_version`, `fk_attempt_answers_question_version`)
  and F2.5 makes it wrong. The verb is named DELETE because that is what the teacher is doing, not
  what the row does. Note the three prevent a hard delete and say nothing about the soft one; §5
  covers that.
- **No `latest_version_id` denormalisation on `questions`.** Every bank-list row therefore resolves
  its latest version by a correlated `MAX(version_no)`. Stated because it is a real query-shape
  constraint on E6.5, and because adding the column later is a migration.

## 9. What the pre-build red team changed

Run against this document, the schema and the acceptance table **before** any handler was written,
on the PR 3a principle that a defect in a contract is cheapest while nothing is built on it. It
returned fifteen findings; these are the ones that changed the contract rather than a sentence.

| Was | Now | Why it mattered |
|---|---|---|
| coordinator scoped by `course_teachers` | scoped by coordinated subject | `rina.barak` has zero teaching rows by design. A starred demo account would have opened an empty bank at the defense |
| allow-list of 2 | 4, split inbound/outbound | `CorrectnessNames` matches any name containing `correct`, so `QuestionDraft` and `QuestionEdit` trip it. The guard would have red-lined the day the package was created, and the tempting fix is widening the shared predicate, which silently weakens the other two guards |
| §2 said `NOT_FOUND`, §6 said `FORBIDDEN` | `NOT_FOUND` everywhere for scope | the same condition was mapped to two codes, one of which is an existence oracle. Both frozen contracts already settle this |
| `lockVersion` on the wire | removed | `questions` never changes on an edit, so `@Version` never increments and the token is inert. `baseVersionNo` was doing the work uncredited |
| `blockingExamNames: List<String>` | `List<BlockingExam>`, de-duped by exam | exam 101101 pins 11005 in two versions, so T-2.7's own demo case would have said "2 exams use it: Algebra Midterm, Algebra Midterm" |
| `contentType` unexplained | re-sniffed on read, stated | it is the only field in §4 with no column behind it |
| `updatedAt` | `lastVersionAt` | no such column; the name promised something the schema does not have |
| no length rules | text/answer/topic maxima | `VARCHAR(500)` truncation is an `SQLException`, not a `VALIDATION` naming the field |
| case-folding comparison | plus accent folding | `utf8mb4_unicode_ci` is accent-insensitive, so the CHECK was **stricter** than the service rule that claimed to backstop it. `résumé`/`resume` would have died in the database |
| "six authoring verbs", "three read verbs" | three mutating, four read | whoever wrote the negative authorization tests from §2 would have been one verb short, and the missing one returns bytes |
| "no student-reachable path" | qualified | the study bot reads the bank. The claim was false as an absolute |
| `size` on `BankPage` | `pageSize` | `NotificationsPage.size()` already means "rows returned" |

Two findings were **not** fixed in that pass because they were decisions rather than corrections:
the legacy-pair sequencing and the `requireTeachesCourse` note in §2. Both are now settled, the
first by ruling §7.4 and the second by the lead opening `Authorization.java` to E6.
