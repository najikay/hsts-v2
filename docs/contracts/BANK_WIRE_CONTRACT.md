# E6 question bank wire contract — DRAFT

**Status: DRAFT, 2026-08-21.** Member A drafts, the lead freezes with rulings on review
(his ruling of 2026-08-21). Nothing here is binding until this header says FROZEN.
Same additive-only terms as [EXAM_WIRE_CONTRACT.md](EXAM_WIRE_CONTRACT.md) once it is.

Package: `common/dto/bank` (all types `Serializable` records, wire-safe, no entity types).
Verbs group under `Question bank (E6)` in `common/protocol/Verb.java`.
Handlers: `server.features.bank.QuestionService`.

**Open rulings are collected in §7.** They are the reason this is a draft rather than a
proposal I built against silently. **§9 records what a red-team pass changed before you read
this**, because several of the corrections are decisions rather than typo fixes.

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
already carry a key. See §7.4, which is now a sequencing question rather than a tidiness one.

## 2. Roles and scope

- **Every verb is staff-only.** All seven require an authenticated caller.
  `Authorization.requireRole(TEACHER, COORDINATOR)` on the **three mutating verbs**
  (`QUESTION_CREATE`, `QUESTION_UPDATE`, `QUESTION_DELETE`); the **four read verbs** add
  `PRINCIPAL` (F9.3: read-only bank browse, literally zero mutating verbs authorized for the role).

- **Scope is per role, and a coordinator's is NOT "courses I teach".** This is the correction that
  matters most in §9:

  | Role | Reaches |
  |---|---|
  | TEACHER | questions in courses she teaches (`course_teachers`), S-5 |
  | COORDINATOR | questions in every course of the subject she coordinates |
  | PRINCIPAL | every course, read-only (F9.3) |

  A coordinator scoped by `course_teachers` would show `rina.barak` an **empty bank**. She holds a
  `coordinators` row for subject 10 and zero `course_teachers` rows, deliberately, so that an
  implementation deriving coordinator-ness from the wrong table fails
  (`FacultySection` javadoc, roster decision 2026-08-20). She is also a starred account in
  `DEMO_ACCOUNTS.md` and the approver in acceptance scenario 4, and F4.1 requires her to preview
  exams built from questions she must therefore be able to read.

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

- **`Authorization.requireTeachesCourse` exists and throws.** It is a frozen signature with a
  `TODO(E2)` body that fails closed (`Authorization.java`). **E6 implements it**, backed by
  `CourseRepository`, and it becomes the one place the table above is expressed. Routing scope
  through an ad-hoc service check while a declared guard sits unimplemented is how two answers to
  one question get shipped.

## 3. Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `BANK_LIST` | teacher, coordinator, principal | `BankListRequest` | `BankPage` |
| `QUESTION_GET` | teacher, coordinator, principal | `QuestionRequest` | `QuestionDetail` |
| `QUESTION_VERSIONS` | teacher, coordinator, principal | `QuestionRequest` | `VersionHistory` |
| `QUESTION_IMAGE_GET` | teacher, coordinator, principal | `QuestionImageRequest` | `QuestionImage` |
| `QUESTION_CREATE` | teacher, coordinator | `QuestionDraft` | `QuestionDetail` |
| `QUESTION_UPDATE` | teacher, coordinator | `QuestionEdit` | `QuestionDetail` (the new version) |
| `QUESTION_DELETE` | teacher, coordinator | `QuestionDeleteRequest` | `DeleteOutcome` |

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

**Validation (C-8, ADR-016, F2.1).** `QuestionValidator` is shared by create and edit, so the two
cannot diverge:

- text non-blank, at most 4000 characters
- exactly 4 answers, each non-blank and at most 500 characters (`a1..a4 VARCHAR(500)`)
- `correctAnswer` in 1..4
- the 4 answers pairwise distinct, compared after **trim, whitespace-collapse, case folding and
  accent folding**
- topic non-blank, at most 100 characters (`topic VARCHAR(100)`)
- difficulty present

**The length rules and the accent rule are not padding, they are the difference between a message
and a stack trace.** Without lengths, a pasted paragraph in an answer box becomes a data-truncation
`SQLException` and the teacher sees `INTERNAL`. And the accent rule exists because
`utf8mb4_unicode_ci` is accent-insensitive: `ck_question_versions_distinct` rejects
`resume`/`résumé` as duplicates, so a validator that folds only case is **weaker than the
constraint it claims to backstop** and hands the same case to the database to reject rudely. The
service rule must be at least as strict as the CHECK in every dimension, or the CHECK is not a
backstop but a second, worse error path.

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

## 7. Open rulings for the lead

1. **Verb naming.** I propose `QUESTION_IMAGE_GET`, matching the noun-first convention of
   `NOTIFICATIONS_GET`, `LOCKS_SNAPSHOT` and `BOT_MANAGER_GET`. **TODO E6.6 literally names it
   `GET_QUESTION_IMAGE`**, which matches the two legacy verbs instead. Your call; I would rather
   the convention won and the TODO was reworded.

2. **Does the principal see the answer key?** I have drawn it so she does: one `QuestionDetail`
   type, staff-only, principal included. The argument for keeping it from her is that the key's
   only purpose here is authoring and she cannot author. The argument against is that a second
   keyless projection is real code for a distinction whose threat model is students, not staff.
   **Reversal cost if you want her excluded: one extra record plus one allow-list entry, about an
   hour**, and it is cheaper now than after the screens are built.

3. **Coordinator scope, §2.** I have ruled it "every course in the subject she coordinates",
   because the alternative shows a starred demo account an empty bank. The reachable alternative
   is "courses she teaches, union courses in her subject", which is the same set for
   `michal.sharon` and differs for nobody in the seed. Flagging because it is an authorization
   semantic and yours to overrule, not because I think it is close.

4. **The legacy retirement is now a sequencing question, not a tidiness one.** `common/dto/bank`
   already holds `Question` and `QuestionUpdate`, and `Question.answer` is an answer key the new
   guard cannot see (§1). So E6 ships a guard that is green and silent about the one type in its
   own package that already carries a key. Either the legacy pair is retired **inside** E6, or §1's
   exclusion stands as written and we say so out loud. I still prefer a separate PR after E6 for
   the review reasons, but this is no longer purely my call: it changes what the guard proves.

5. **Truncating the list stem server-side.** I propose it, because forty full stems is the payload
   that makes a bank browse feel slow. The length is a constant.

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

Two findings are **not** fixed here and are §7.4 and the `requireTeachesCourse` note in §2: both
are decisions rather than corrections.
