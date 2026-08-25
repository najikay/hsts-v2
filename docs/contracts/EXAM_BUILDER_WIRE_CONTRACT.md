# E7 exam builder wire contract — FROZEN v1 (§1-§6, §8), §7 DRAFT

**Status: FROZEN v1 as of 2026-08-25, for sections 1 to 6 and section 8. Section 7 alone stays
DRAFT.** The freeze condition the lead set was handlers existing against the text, and they do:
`server.features.exambuild.ExamHandlers` serves six of the seven verbs. **Additive-only from here**
for the frozen sections, same as [EXAM_WIRE_CONTRACT.md](EXAM_WIRE_CONTRACT.md) and
[BANK_WIRE_CONTRACT.md](BANK_WIRE_CONTRACT.md).

The partial freeze is the lead's decision of 2026-08-24, approved and then enlarged by him: the
proposal was to hold §2 and §3 open alongside §7, and his ruling settled §2 in the same message, so
only §7 remains. Freezing a section whose code does not exist is the thing a freeze is supposed to
prevent.

> **§7 STAYS DRAFT UNTIL PR B.** A cold read before the handlers were written found that §7 did not
> determine what to report for quotas whose candidate pools **cross** rather than nest, and that
> §7.4's most-constrained-first rule is a greedy order rather than a matching, so it could emit a
> shortfall the teacher can disprove. **Ruled 2026-08-24: option (a), the laminar restriction** -
> see §7.3. The lead checked the argument independently rather than taking it: topic quotas are
> pairwise disjoint, a topic's difficulty buckets nest inside it, the course-wide `any` bucket is a
> superset of all of them, so Hall's condition collapses to exactly the per-bucket checks §7.3
> already makes and deepest-first greedy is exact.
>
> It stays DRAFT anyway, because **no code exercises it yet**: `AutoComposer` is not written and
> `EXAM_AUTO_COMPOSE` is registered nowhere. `ExamHandlersTest.Registration` asserts the six-verb
> set, so restoring the seventh takes a deliberate test change rather than a quiet addition. §7
> freezes on PR B, on the same condition every other section just met.

*Types landed 2026-08-23; §12 records the five rulings applied while landing them, and the in-place
corrections they required are marked where they sit. Written for the lead to land the verbs and
DTOs from, on the same handoff as BANK: Member A drafts, the lead freezes and lands
`common/protocol/Verb.java` and the DTO package himself.*

Package: **`common/dto/authoring`** (ruling 1 below — `common/dto/exam` is taken by E10/E11's
take-exam surface and reusing it would put a student's paper and a teacher's composition in one
package). All types `Serializable` records, wire-safe, no entity types.
Verbs group under `Exam builder (E7)` in `Verb.java`.
Handlers: `server.features.exambuild.ExamHandlers` over `ExamService`, `ExamValidator` and
`AutoComposer`.

**Everything numeric in section 5 was measured against `V3__exams.sql` on 2026-08-23**, not taken
from ARCHITECTURE §5's summary. Where the two differ the migration wins and this document says so.

---

## 1. The rule this contract exists to enforce

**An exam version that exists is a releasable object, or it is a DRAFT nobody has submitted.**

There is no third state. The take-exam tier (E10), the grading tier (E12) and the release manager
(E9) all read `exam_version_questions` and all assume its points sum to 100, because a paper that
cannot total 100 cannot be scored out of 100. That assumption is currently held up by nothing but
E7's own care, since `sum(points) = 100` **cannot be a DDL constraint** — a table-level `CHECK`
cannot span rows, and `V3__exams.sql` says so in a comment.

So the rule is enforced at the only place it can be, and enforced **on the write path with no
exceptions**: no verb here writes a composition that does not total 100. Not "saves a warning",
not "saves as incomplete". The consequence, stated rather than discovered: **there is no
work-in-progress row.** A half-composed exam lives in the teacher's client and nowhere else, which
is what F3.1's "save blocked (not warned)" already required and what this contract makes
structural.

Two things follow that are worth naming, because they are the reason the surface is shaped the way
section 3 shapes it:

- **`EXAM_CREATE` carries the whole composition.** Creating an empty exam and filling it in later
  would put an unsatisfiable row in `exam_versions` for as long as the teacher is thinking, and
  T-3.5 says in plain words that a refused auto-composition creates **no exam**. If the exam row
  already existed by then, the acceptance case would be failed by an empty draft in her drawer.
- **`EXAM_AUTO_COMPOSE` writes nothing at all.** It is a pure read that answers with a proposal or
  with a shortfall report. That is what makes "no exam is created" true by construction rather than
  by a rollback that has to work.

---

## 2. Roles and scope

- **Every verb is staff-only**, and every verb requires an authenticated caller.
  `Authorization.requireRole(TEACHER, COORDINATOR)` on all seven. The principal is **absent from
  this contract entirely**: F9.3 gives her read-only access to data as entered, and E15.2's
  `DATA_EXAMS_GET` already serves her the school's exams. An authoring surface is not a read of
  entered data, and adding her here would put a role with zero mutating verbs on a wire whose
  every verb but two mutates.

- **The scope is AUTHOR-ONLY**, and that is narrower than "teaches the course":

  | Role | May CREATE an exam | May READ or EDIT an exam version |
  |---|---|---|
  | TEACHER | in courses she teaches | the ones she authored |
  | COORDINATOR | in courses she also teaches | the ones she authored |
  | PRINCIPAL | never | never (E15.2 serves her instead) |

  **Precedent, not invention.** `RESULTS_WIRE_CONTRACT` froze E14 as author-only on the lead's
  ruling, "literally F9.2's exams she wrote", deliberately narrower than the monitor's
  author-or-runner rule. F3.1 and S-12 say the same thing about authoring: the exam records an
  author, and F3.5's edit-makes-a-version is a statement about that author's document. A
  co-teacher who wants to change someone's exam has the same route a coordinator does, which is to
  say so, not to edit it.

  The coordinator's read of somebody else's exam already exists and is E8's: `EXAM_PREVIEW_GET`,
  guarded by `requireCoordinatorOf` on the subject. Nothing here widens it. **See ruling 2** — this
  is the one scope decision in the document I would want confirmed rather than assumed.

- **Two guards, chosen by verb, never composed.** Same shape as the bank contract's section 3, for
  the same reason: which guard applies is a property of the verb, so a handler using the wrong one
  is visibly wrong in review.
  - `requireTeachesCourse` **throws** on the two verbs where the caller *supplies* the course
    (`EXAM_CREATE`, `EXAM_AUTO_COMPOSE`). A `FORBIDDEN` naming a course she already named tells her
    nothing she did not know.
  - Authorship is checked against the **stored** row on the other five, and answers `NOT_FOUND`,
    never `FORBIDDEN`. Naming the exam would tell a caller probing ids that it exists and who owns
    it, which is the existence oracle P-5 is about and which both frozen contracts already refuse.

- **`NOT_FOUND` is the only answer for anything the caller cannot reach.** Unknown id and another
  teacher's exam are one answer, indistinguishable on purpose.

  **Two cases, not three** *(corrected 2026-08-25, the lead's ruling)*. This sentence used to end
  "and an exam whose course she has stopped teaching". `ExamService.authoredHeader` filters on the
  author id and nothing else, so that third case never existed: the document was describing a
  guard the code does not have, and §3's own table said only "author" the whole time. The ruling
  is that **the code is right and this section overreached.** An exam is authored work rather than
  course-scoped data, so a teacher who stops teaching a course keeps the exams she wrote there,
  including the right to submit a new version into that course's coordinator queue. That
  consequence is stated here so it can be found on purpose rather than discovered.

- **No payload carries a caller id.** Authorship is `CallerContext.userId()` (S-12), so an exam
  cannot be created in somebody else's name and an edit cannot be attributed to somebody else.

- **The questions a composition may pin are the caller's bank scope, not her authoring scope.**
  A question enters an exam through `EXAM_CREATE` or `EXAM_VERSION_SAVE`, and section 5 requires it
  to belong to the exam's own course. That is stricter than `reachesCourse` and stricter than
  `teachesCourse`, and it is the rule that actually matters: an Algebra exam holds Algebra
  questions. Cross-course composition is not a feature anybody asked for and is not one this
  contract offers.

---

## 3. Verbs

| Verb | Caller | **How scope applies** | Request payload | OK payload |
|---|---|---|---|---|
| `EXAM_LIST` | teacher, coordinator | **filter**: exams she authored | *(no payload)* | `ExamList` |
| `EXAM_VERSION_GET` | teacher, coordinator | author → `NOT_FOUND` | `ExamVersionRequest` | `ExamComposition` |
| `EXAM_CREATE` | teacher, coordinator | `requireTeachesCourse` (throws) | `ExamCreateRequest` | `ExamComposition` |
| `EXAM_VERSION_SAVE` | teacher, coordinator | author → `NOT_FOUND` | `ExamVersionSave` | `ExamComposition` |
| `EXAM_VERSION_REVISE` | teacher, coordinator | author → `NOT_FOUND` | `ExamVersionAction` | `ExamComposition` (the new DRAFT) |
| `EXAM_SUBMIT` | teacher, coordinator | author → `NOT_FOUND` | `ExamVersionAction` | `ExamComposition` (now PENDING) |
| `EXAM_AUTO_COMPOSE` | teacher, coordinator | `requireTeachesCourse` (throws) | `AutoComposeRequest` | `AutoComposeResult` |

**Seven verbs, and two of the obvious ones are deliberately not here.**

- **There is no exam-delete verb.** F3 never asks for one, C-2 says versions are retained, and an
  exam that has been released has attempts hanging off it that `RESTRICT` would refuse anyway. A
  teacher who regrets an exam leaves it as a DRAFT she never submits. If this turns out to be
  wanted, it is additive.
- **There is no bank-picker verb.** E7.12's picker is `BANK_LIST` from the frozen bank contract,
  used exactly as `client.features.data.DataSession` already uses it. A second browse verb would be
  a second set of scope rules over the same rows, and the first time the two disagreed the bank
  would be the thing that was wrong.

**`EXAM_VERSION_GET` serves two screens, and that is the point.** It opens the builder on a DRAFT
and it renders a past version read-only in the history panel (E7.14). One payload, and the client
decides what is editable from `state`, so a past version and a live draft can never render from two
shapes that drift.

**`EXAM_VERSION_REVISE` exists so that "edit" is never a lie.** F3.5 and C-2 say editing an exam
produces a new version and retains the old one. Folding that into `EXAM_VERSION_SAVE` would mean
one verb that sometimes mutates a row and sometimes creates one, decided by a status the client
cannot see at the moment it presses the button. Two verbs, and which one the screen calls is
decided by the state it is already showing.

**Optimistic locking travels, as it does on the approval wire.** `exam_versions.lock_version` is a
real column with `@Version` on it, and `status` is the one mutable field on the row, so an author
submitting while a coordinator approves is a genuine race. `ExamVersionSave` and `ExamVersionAction`
both carry `expectedLockVersion`, and every payload that carries a version row reports its current
one. This mirrors `ExamApproveRequest` deliberately: the same row, the same token, one convention.

**No pushes.** The author learns her exam was approved or rejected through E8's durable
notification, which already exists and already points at route id `exams`. The builder's live "being
edited by" state rides E18.8's `LOCK_WATCH` / `LOCKS_SNAPSHOT` under the existing
`EntityRef.EXAM_VERSION` constant, and **no DTO here carries a lock-holder field**, for the reason
the bank contract gives: two expressions of one fact drift, and viewing a list should never contend
for a lock.

---

## 4. DTOs (`common/dto/authoring`)

> **`ExamList`'s row order: newest exam first, and the store has to change** *(ruled 2026-08-25;
> the disagreement was found by a cold read of this document against the store)*. This section and
> `ExamList`'s own javadoc both say "newest exam first". The store did something else and did it
> deliberately: `ExamBuildRepository.findAuthoredExams` ends `order by e.displayId`, pinned on both
> engines by `ExamBuildRepositoryContract.examListIsOrderedByDisplayId`, landed in #44 and
> reviewed. Both could not be right.
>
> **The contract wins.** A teacher opens this list looking for the exam she touched yesterday, both
> wire documents already promise recency, and display id is a *filing* order rather than a recency
> one: `displayId6` is `subjectCode + courseCode + a per-course serial` (`ExamIdAllocator`), so
> ascending sorts by subject, then course, then oldest first within a course. A teacher with exams
> in two courses got neither ordering.
>
> **The fix is the query and its pinning test, not a sort in `ExamService.list`** - one rule with
> one home, so the screen cannot disagree with the store about what "newest" means. It lands in
> whichever PR next touches the store: PR B or the E7.10 screen. Until then the wire answer is the
> store's order, and this paragraph is the record of why that is a known gap rather than a
> surprise.

```
ExamList(List<ExamListRow> rows)
      newest exam first. Empty list is a real answer and the screen has a designed
      panel for it; there is no "she teaches nothing" case, because a teacher who
      teaches nothing cannot reach this screen.

ExamListRow(long examId, String displayId6, String courseCode, String courseName,
            String name, int latestVersionNo, List<ExamVersionRow> versions)
      name is the LATEST version's name, matching AuthoredExam's rule: a teacher who
      renamed her exam in v3 looks for it under that name. versions is newest first
      and holds every version, which is what makes the row expandable (E7.10).

ExamVersionRow(long examVersionId, int versionNo, ApprovalState state,
               String rejectedReason, int questionCount, int durationMinutes,
               Instant createdAt, int lockVersion)
      rejectedReason is "" unless state is REJECTED. It carries E8's superseded
      sentence too, which is why it is on the row and not only in a dialog: F4.2
      requires the reason to be visible ON the exam.

ExamVersionRequest(long examVersionId)

ExamVersionAction(long examVersionId, int expectedLockVersion)
      the payload for the two verbs that change a version's status. One record for
      both, because they take the same two facts and a screen that had to remember
      which shape went with which button is a screen that will one day send the
      wrong one.

ExamComposition(long examId, String displayId6, String courseCode, String courseName,
                long examVersionId, int versionNo, ApprovalState state,
                String name, int durationMinutes, String studentText, String teacherText,
                String authorName, Instant createdAt, String rejectedReason,
                List<ComposedQuestion> questions, int lockVersion)
      the one payload every writing verb answers with, so the client has exactly one
      path after a save, a revise and a submit. Re-read from the database after the
      write rather than patched together from the request, for the reason
      ApprovalDecision carries a re-read row: a client assembling its own new state
      is guessing at versionNo and lockVersion, and it will guess wrong exactly once.

ComposedQuestion(long questionVersionId, String questionDisplayId5, int ord, int points,
                 String text, String topic, Difficulty difficulty, boolean hasImage,
                 int pinnedVersionNo, int latestVersionNo)
      ord is 1-based, matching ck_evq_ord. text is the stem, truncated server-side
      exactly as BankQuestionRow.text is.

      NO ANSWERS AND NO KEY. The builder shows which questions are on the paper and
      what they are worth; it is not a preview of the paper. A teacher who wants to
      read the question opens it in the bank, where QUESTION_GET already serves the
      key to exactly this audience under the frozen bank contract. Keeping the key
      off this wire means E7 adds no new type to the correctness boundary and the
      leak guard's licensed list does not grow.

      pinnedVersionNo vs latestVersionNo IS E7.7. They differ when the bank has moved
      on since this version pinned the question, and the difference is the badge.

QuestionPin(long questionVersionId, int points)
      what the client sends. ord is THE LIST INDEX and is not a field: two orderings
      that can disagree is a defect waiting to happen, and uq_exam_version_questions_ord
      would catch it only after the client had already shown the teacher the wrong one.
      questionId is not sent either: it is derived server-side from the version,
      which is what the composite FK (question_version_id, question_id) polices.

ExamCreateRequest(String courseCode, String name, int durationMinutes,
                  String studentText, String teacherText, List<QuestionPin> questions)
      no display id and no author: the server allocates the first (S-10, ExamIdAllocator)
      and takes the second from the session (S-12).

ExamVersionSave(long examVersionId, int expectedLockVersion, String name,
                int durationMinutes, String studentText, String teacherText,
                List<QuestionPin> questions)
      a FULL REPLACE of metadata and composition together, matching the storage rule
      ARCHITECTURE §5 already fixed: "E7 composition updates are full-replace within one
      transaction (delete rows + reinsert), so no reorder dance is ever needed". A
      partial save would need a diff, and a diff of a list whose ord is unique-per-version
      is the reorder dance that decision exists to avoid.

AutoComposeRequest(String courseCode, List<TopicQuota> quotas, Long seed)
      seed nullable; see section 7 for what it is and why it is on the wire.

TopicQuota(String topic, int easy, int medium, int hard, int any)
      topic null or blank means "any topic in the course".
      any means "any difficulty", which is what T-3.4's "mixed difficulty" asks for.
      THERE IS NO TOTAL FIELD. The total is the sum of every bucket in every quota,
      derived in one place, so a request cannot carry a total that disagrees with its
      own breakdown. That disagreement is the single most likely defect in this payload
      and it is removed rather than validated.

AutoComposeResult(boolean feasible, List<ComposedQuestion> questions,
                  List<Shortfall> shortfalls)
      exactly one of the two lists is non-empty, and feasible says which. Both empty
      is impossible and rejected in the compact constructor: an auto-compose that
      selected nothing and explained nothing is the failure F3.3 exists to prevent,
      and it must not be representable.

Shortfall(String topic, Difficulty difficulty, int requested, int available)
      section 7. difficulty null is the `any` bucket; topic null is the course-wide one.
```

**Two enums are reused rather than redeclared**, and both are deliberate:

- **`Difficulty`** from `common/dto/bank`. The criteria grid is a statement about bank rows, so it
  speaks the bank's enum. A second copy would be two wire types for one concept and the first
  mismatch would be silent.
- **`ApprovalState`** from `common/dto/approval`. `exam_versions.status` is one column with one
  meaning, and E8 already bridges it to the store with an exhaustive switch that makes a new member
  a compile error. Declaring `ExamState` beside it would be a second bridge with no such property,
  and the two would agree only for as long as nobody added a state.

---

## 5. Rules the handlers enforce

**Every number below was read from `V3__exams.sql`.** Where the service rule is stricter than the
column, the reason is given: a rule that only the database knows arrives at the teacher as
`INTERNAL` and a stack trace, which is the outcome naming the field is meant to replace.

### 5.1 The points rule (F3.1, S-11)

- Points sum to **exactly 100**. Not at least, not approximately.
- Each question's points are **1..100** (`ck_evq_points`), integers. No fractional points anywhere
  on this wire: `points INT`, and a UI that offered halves would be offering something the column
  cannot hold.
- **At least one question.** A version with no questions cannot sum to 100.
- **The maximum number of questions is 100, and it is not a rule.** It follows from points ≥ 1 and
  a sum of 100. Writing a separate ceiling would be a second rule that could disagree with the
  first.
- The failure names the shortfall in both directions and by how much, never just "invalid": T-3.2
  watches the indicator go from wrong to right and the sentence is what tells her which way.

### 5.2 Composition rules

- **No duplicate question, even through different versions of it** (T-3.9, PRD §6). Checked in the
  service with a message naming the question, and backstopped by
  `uq_exam_version_questions_question`. The service check exists because the constraint's message is
  not a sentence a teacher can act on.
- **Every question belongs to the exam's own course.** Resolved from the pinned
  `question_versions` row, not trusted from the client.
- **No soft-deleted question.** ARCHITECTURE §5's round-2 note assigns this rule here by name:
  "adding a soft-deleted question to a new exam version is a service-rule rejection (E7 validator)".
  It has no database backstop, because soft delete is an `UPDATE` and no foreign key fires on an
  `UPDATE`. That makes it exactly the shape of rule the bank contract flagged for the delete block,
  and it gets the same treatment: a two-engine repository test standing in for the constraint that
  cannot exist.
- **Every `questionVersionId` must exist.** An unknown one answers `VALIDATION` naming the position
  in the list, not `NOT_FOUND`: the caller is describing a composition, and the thing that was not
  found is a field in her request rather than the object she addressed.

### 5.3 Metadata rules

| Field | Rule | Where the number comes from |
|---|---|---|
| `name` | non-blank, at most **150** characters | `name VARCHAR(150) NOT NULL` |
| `durationMinutes` | **1..480** *(lead ruling 3: 480 — a 600 ceiling admits the very 600-for-60 typo it was invented to catch)* | `ck_exam_versions_duration` gives `> 0` only; the ceiling is a service rule, see below |
| `studentText` | optional, at most **4000** characters | `TEXT` holds 65,535 **bytes**, and utf8mb4 spends up to 4 per character |
| `teacherText` | optional, at most **4000** characters | same |
| `courseCode` | `strip()`ped, never `trim()`ped, before any scope comparison | `courses.code2` is `CHAR(2)` under a PAD SPACE collation |

**The 480-minute ceiling is invented here** *(lead ruling 3: 480 — a 600 ceiling admits the very
600-for-60 typo it was invented to catch)*. The column only forbids zero and negatives, so a typo
of `600` for `60` is storable today, and an exam whose timer says ten hours is a live execution
nobody can end. The draft proposed 600 and the ruling cut it to 480: eight hours is already far
past any real exam, and it is the smallest ceiling that still admits every legitimate sitting
while refusing the one mistake the rule exists for.

**The 4000-character ceiling on the two texts is deliberately far below what `TEXT` holds.** The
point is not to conserve storage, it is that a paste of a whole textbook chapter into the student
text renders as an exam form nobody can read, and the refusal has to arrive as a sentence rather
than as a truncation nobody notices until a student is sitting the exam.

**The `strip()` rule is imported from the bank contract verbatim**, including its reason: `trim()`
cuts only characters at or below U+0020, so a course code carrying a Unicode space matches the row
in SQL while failing Java equality against the reachable set. It is imported with its **measured
limit** too, which `BankBrowseService` states and `BankBrowseServiceTest.nonBreakingSpacesSurviveStrip`
pins: `strip()` removes what `Character.isWhitespace()` accepts, and the non-breaking spaces
U+00A0, U+2007 and U+202F are exactly the ones that predicate rejects. A code padded with one of
those arrives at `requireTeachesCourse` unchanged and is refused, which fails **closed**. The
dangerous direction would be a value SQL matches while the guard does not, and this is its
opposite.

**The criteria a generation asks for** *(added at type-landing, 2026-08-23)*. `AutoComposeRequest`
carries no rules of its own in the DTO — its compact constructor normalises and does not throw
(§4's inbound rule) — so both of these are `ExamValidator`'s, and both answer `VALIDATION` naming
the field:

- **`TopicQuota` topics must be distinct within one request.** Two quotas naming one topic are two
  buckets drawing on one pool with no rule saying which of them is short, so the report could name
  a shortfall the teacher can disprove by filtering her own bank to the same topic. §7.2 property
  2 calls that the worst possible failure here. Comparison is on the **normalised** topic, since
  the record folds blank to `null`, so `""` and `null` are one bucket and not two.

  **This rule does not buy disjointness, and an earlier draft of it said so wrongly** *(corrected
  2026-08-24, found by a cold read of this document against the code)*. Distinct topics still
  overlap: `quotaProblem` deliberately permits one course-wide quota alongside every topic quota,
  and within a single `TopicQuota` the `any` bucket overlaps `easy`/`medium`/`hard`. §7.3 says as
  much outright - "quotas draw from overlapping supply" - which is the whole reason its aggregate
  row exists. The rule is kept because two buckets over one pool have no defined answer; what it
  must not be read as is a licence for the generator to assume disjoint pools. It is not one, and
  §7.4's selection rule is the open question that follows from that.
- **Every quota bucket is `>= 0`, and the total across all quotas is `>= 1`.** A negative bucket
  would subtract from a sibling quota's demand and make the derived total a lie; a request for
  nothing at all is not a composition, and answering it with an empty proposal would violate
  §4's `AutoComposeResult` invariant rather than produce a paper. The total is
  `AutoComposeRequest.totalRequested()`, derived from the buckets in one place — see §4 on why
  there is no total field.

### 5.4 State rules (F3.6)

- **Only a `DRAFT` version is savable.** `EXAM_VERSION_SAVE` against `PENDING`, `APPROVED` or
  `REJECTED` answers `CONFLICT`, not `VALIDATION`: the request was well formed and the world moved.
- **`EXAM_VERSION_REVISE` refuses a `DRAFT`.** Revising a draft would produce two drafts of one
  exam, and the second is a version number nobody asked for. A teacher editing her draft saves it.
- **`EXAM_SUBMIT` requires `DRAFT`**, and answers `CONFLICT` otherwise. It does not need to re-check
  the points rule, because section 1's invariant means no stored version can fail it. It re-checks
  anyway, cheaply, and that check is a genuine test of the invariant rather than a restatement of
  it: if it ever fires, section 1 is false and the log line says so.
- **A new version starts at `latestVersionNo + 1`**, backed by `uq_exam_versions_no`, and it copies
  the metadata and composition of the version it was revised from. `rejected_reason` is **not**
  copied: it belongs to the version that was rejected.

### 5.5 Submit hands off to E8, and emits nothing itself

**Amended 2026-08-24 (lead ruling, freeze text): the hook is the handler's, after commit.**
`EXAM_SUBMIT`'s handler calls **`ApprovalService.versionSubmitted(examVersionId)`** once its
transaction has committed and `ExamService.submitForApproval` returned `OK`.
`ExamService.submitForApproval` owns the transition and sends no notification of its own.

**"After the service returned `OK`" is not sufficient on its own, and the difference is the whole
bug** *(added while writing the handler, 2026-08-24)*. `ExamService.submitForApproval` does not
own a transaction: it takes a `Session` and the boundary is the handler's
`Transactions.inTx(...)`. So a call placed *inside* that lambda satisfies both halves of the
sentence above - the service has returned `OK`, and nothing has committed - while reproducing
exactly the failure this amendment exists to kill. **The call goes outside `Transactions.inTx`, on
the returned outcome.** `ExamHandlersTest.theHookRunsAfterTheCommit` is what holds it there: it
asserts what had happened to the transaction *at the moment of the call*, not that the call
happened, because a call that happens and notifies nobody is the thing being prevented.

**A hook that throws does not turn a committed submission into an error.** By the time it runs the
transaction is committed, so propagating would tell her the submit failed when it did not, and she
would submit again over a version that is already `PENDING`. It is logged at error and the answer
stays `OK`. That is the window the next paragraph but two states, entered deliberately rather than
by accident.

The original text below said the *service* makes that call from inside the transaction. **That
notifies nobody**, and it was found by a cold read rather than by a test.
`JpaApprovalStore.inTx` goes through `Transactions.inTx(factory, ...)`, which opens a fresh
session, so the hook runs on another connection, cannot see the uncommitted status flip, takes its
own `if (!version.isPending())` guard, reads the row as still `DRAFT` and returns
`Superseded.none()` before either notification. E7 was the hook's first production caller, which is
why nothing had exercised it.

**The session-joining alternative is worse, and that is the lead's reason rather than mine.**
`versionSubmitted` already notifies *outside* its own transaction, so a variant that joined the
caller's session would push bells about a submit that has not committed, and rolling the outer
transaction back would leave phantom notifications. Doing that correctly needs post-commit callback
machinery, which is a phase-2 shape.

**The one window, stated honestly because somebody will probe it.** A crash between the commit and
the hook loses the supersede and the notifications. **It never loses the submission**: the version
is `PENDING` in a committed transaction, and the coordinator's queue reads *status*, not bells, so
the row still appears. A re-submit re-fires the hook.

---

*Original text, kept because the division of ownership it states is unchanged:*

`ExamService.submitForApproval` calls **`ApprovalService.versionSubmitted(examVersionId)`** and
sends no notification of its own. This is not a suggestion; the approval contract's E8.2 section
names it as an instruction to Member A, and gives the reason: that one hook supersedes the other
pending versions, notifies the coordinator about the supersede, and emits the ordinary
`APPROVAL_REQUESTED`. Splitting it would let E7 emit a request for a version whose supersede failed,
or emit two notifications in an order that reads backwards.

**E7 owns the transition. E8 owns everything the queue sees.**

### 5.6 One transaction per write

Create, save, revise and submit are each **one transaction**. The full-replace save deletes and
reinserts `exam_version_questions` inside it, per ARCHITECTURE §5. A half-written composition would
be a version that violates section 1's invariant while looking valid, which is the one failure mode
this whole contract is arranged around.

---

## 6. Error codes

| Code | When |
|---|---|
| `UNAUTHORIZED` | no authenticated session |
| `VALIDATION` | any rule in section 5.1 to 5.3, and a malformed payload; the message names the field |
| `FORBIDDEN` | **role** check failed, or `requireTeachesCourse` on the two verbs that carry a course. Never used for authorship |
| `NOT_FOUND` | unknown exam or version, or one the caller did not author. All indistinguishable on purpose |
| `CONFLICT` | stale `expectedLockVersion`, a version in the wrong state for the verb (5.4), or the version is edit-locked by someone else |

`BAD_REQUEST` is unused, matching both frozen contracts: a malformed payload answers `VALIDATION`.

All copy lives in one `ExamBuildMessages` class, **no em dashes** (PRD §4.1).

---

## 7. The infeasibility report (E7.4, F3.3) ⚑

**This is the section the lead asked for, and it is the defense moment.** F3.3's requirement is not
that generation fails politely. It is that the report "states exactly what's missing", and the PRD
writes the example out in full:

> Topic 'Algebra': requested 5 Hard, bank has 2

The seed exists to make this demonstrable live: PRD §5 puts **one deliberately thin topic**
("Recursion" in Java, 2 questions, none Hard) in the bank precisely so F3.3 can be shown without
anybody touching the database. T-3.5 and T-3.6 are the two shots.

### 7.1 The shape

```
Shortfall(String topic, Difficulty difficulty, int requested, int available)
```

Four fields, and every one of them is in the sentence. `topic` is null for a course-wide quota;
`difficulty` is null for the `any` bucket. The client renders:

| Shortfall | Sentence |
|---|---|
| `("Recursion", HARD, 1, 0)` | Topic "Recursion": requested 1 Hard, bank has 0. |
| `("Recursion", null, 3, 2)` | Topic "Recursion": requested 3 questions, bank has 2. |
| `(null, HARD, 10, 4)` | Requested 10 Hard, bank has 4. |
| `(null, null, 40, 31)` | Requested 40 questions, bank has 31. |

### 7.2 Four properties, each of which is a way this could have been useless

1. **Every shortfall is reported, not the first one.** A teacher who asks for five Algebra Hard and
   three Recursion Hard and is short on both gets two lines. First-failure reporting turns a report
   into an error and makes her discover the second problem by fixing the first, which on a demo
   stage is a very long silence.
2. **`available` is the real count in her bank, under her own scope.** Not the count in the course,
   not the count before soft-deleted rows were excluded. If the number in the sentence is not the
   number she would see by filtering the bank screen to the same topic and difficulty, the report
   is worse than nothing, because she will go and look.
3. **The report is data, and the sentence is composed once on the client.** No sentence travels;
   see ruling 4, which is the one place I would accept being overruled without argument.
4. **Nothing is written.** No exam, no version, no allocated serial. The verb is a read and
   `AutoComposeResult.feasible` is false. T-3.5's "**No exam is created**" is then true because
   there was never a write to undo.

### 7.3 What "available" counts

A question is available to a quota when it is in the exam's course, not soft-deleted, and its
**latest** version matches the quota's topic and difficulty. Latest, not any: pinning an old
version because it used to be Hard would put a question on the paper that the bank no longer
describes the way she asked for.

Topic matching is **exact equality**, inherited from the bank contract's ruling 7.6 (option A) so
that the auto-composer and the bank's own filter can never disagree about what a topic is.

> **Clarification, needs [L] to confirm before §7 freezes** *(Member A, 2026-08-25, from a cold
> read of §7 against the code)*. "Exact" here means **the column's own exactness**, which is
> `utf8mb4_unicode_ci` and therefore folds case and accents. It does **not** mean Java
> `String.equals`. Ruling 7.6 chose option A over a normalising filter; it did not choose Java
> equality over the collation, and it could not have, because the filter it was ruling on runs in
> SQL. The implementation buckets with `QuestionValidator.sameTopic`, which is at least as strict
> as the collation in every dimension (C-7 / ADR-016).
>
> Stated because the sentence above, read literally, is the one line in §7 a client author or a
> second query would rely on, and reading it as Java equality would split one candidate pool into
> two buckets that the database serves from the same rows - the exact hazard `docs/PROBLEMS.md`
> P-9 records. The same phrase appears on `TopicQuota.topic`'s javadoc, which is lead-owned
> (`common/dto/**`) and is not edited here.

**`available` never changes meaning, and which row is emitted does** *(lead ruling, 2026-08-24,
freeze text)*. It is always the **raw** count above: what she gets by filtering the bank screen to
that same topic and difficulty. Section 7.2's property 2 is non-negotiable, and a count net of what
another quota consumed would break it, because the number in the sentence would no longer be a
number she can check.

That leaves the case this rule exists for. **Every quota can be satisfiable on its own while the
request as a whole is not**, because quotas draw from overlapping supply. Three Recursion and eight
course-wide against a bank of ten: neither row is short, eleven questions are asked for, ten exist.
Reporting either quota alone produces a true count paired with a demand it does not belong to, and
"Requested 8 questions, bank has 10" is a sentence she can disprove.

So when every individual quota is satisfiable against raw supply but the union is not, the
shortfall is emitted at the **smallest enclosing bucket whose summed demand exceeds its raw
supply**:

- `requested` is the total demanded across every quota inside that bucket;
- `available` is that bucket's own raw supply, unchanged in meaning.

The example above emits `(null, null, 11, 10)` - **"Requested 11 questions, bank has 10."** Both
numbers are verifiable against her own bank and the pairing is coherent.

The same rule applies one level down. Topic-internal overlap, where a topic's difficulty buckets
and its `any` bucket compete for one supply, reports `(topic, null, topicDemand, topicSupply)`.

**No wire change.** The four `Shortfall` shapes in section 7.1 already express exactly these
levels: a `null` difficulty is the topic-wide bucket and a `null` topic is the course-wide one.
This is a rule about which row to emit, not a new field.

**Raw-short quotas keep their own rows beside the aggregate one.** Section 7.2's property 1 -
every shortfall, not the first - extends to the aggregate row rather than being replaced by it: a
teacher short on Recursion Hard *and* over her course's total supply is told both, because fixing
one does not fix the other.

### 7.3a The shape rule that makes all of the above true *(ruled 2026-08-24)*

**If any topic quota is present, the course-wide quota may use `any` only. A course-wide quota with
graded buckets stands alone.** `ExamValidator.quotaProblem` refuses any other combination.

Everything §7.3 says about which row to emit, and everything §7.4 says about selection order, is
true **because of this rule and not without it.** The pools then form a nesting hierarchy: topic
quotas are pairwise disjoint, a topic's difficulty buckets nest inside that topic, and the
course-wide `any` bucket is a superset of all of them. On a family that nests, Hall's condition
collapses to exactly the per-bucket comparisons §7.3 already makes, so bucket checking is complete
rather than approximate, and deepest-first (most-constrained-first) greedy is exact rather than
merely reasonable.

**Without the rule, both properties fail, and the failures were reproduced rather than imagined.**
A topic quota drawing on `any` crosses a course-wide quota drawing on `hard`: neither nests inside
the other, no bucket is short, and yet the request is infeasible - so §7.3 names no row to emit,
`AutoComposeResult`'s compact constructor refuses the empty report, and the teacher gets
`INTERNAL` on the one verb F3.3 exists for. Separately, greedy loses on crossing pools and emits a
shortfall whose `missing()` is zero, which renders as a shortfall claiming nothing is missing.

**The refusal must name the two legal shapes** *(the lead's condition on accepting this rule)*. A
sentence saying only "that combination is not allowed" leaves her guessing which half to delete.
It names both: quotas per topic with a course-wide **total**, or one course-wide quota split by
difficulty on its own. `ExamBuildMessages` owns the wording; the client composes nothing (ruling 4).

### 7.4 Selection, when it is feasible

- Quotas are satisfied **most-constrained first** (fewest available candidates), so a narrow quota
  does not lose its only candidates to a wide one that had alternatives. Without this, asking for
  "3 Recursion" and "10 any topic" can fail even when 13 suitable questions exist, and the report
  would then name a shortfall that is not real. That is the worst possible failure here: a report
  the teacher can disprove.
- Within a quota the choice is random, over the candidates, using `seed`.
- No question appears twice, across quotas as well as within one (section 5.2's rule, applied
  during selection rather than discovered at save).
- **Points are proposed, already totalling 100**, distributed as evenly as the count allows with
  the remainder on the earliest questions: 3 questions become 34, 33, 33. So the auto path is
  savable in one click, which is what T-3.4 walks, and every proposal already satisfies section 1.
- `ord` is the selection order, 1-based.

### 7.5 The seed, disclosed rather than found

`AutoComposeRequest.seed` is nullable and null means "random". It is on the wire partly so tests can
pin a selection, and that is disclosed here rather than left for a reviewer to notice.

It has an independent justification and would be here without the tests: a teacher who says "it gave
me a strange set" cannot be helped if nobody can reproduce it, and a seed echoed back in the log line
is the difference between reproducing her result and asking her to try again. The real client sends
null.

---

## 8. What E7 absorbs, and what retires

**`MY_APPROVALS_GET` retires into `EXAM_LIST`.** This is the lead's ruling at the E8 freeze, quoted
from `APPROVAL_WIRE_CONTRACT.md`: the verb "RETIRES INTO E7's exam list when that screen absorbs
route id `exams`", and it is "binding on E7". Confirmed again on 2026-08-23. Recorded here so the
retirement is traceable from either document.

What that means concretely, so nothing is left to interpretation:

| | Before | After |
|---|---|---|
| Verb | `MY_APPROVALS_GET` → `MyApprovals` | `EXAM_LIST` → `ExamList` |
| Screen behind route id `exams` | `MyApprovalsView` (E8's approval-status half) | E7's exam list |
| Rows | non-draft versions only, newest first | every exam, every version, drafts included |

`ExamListRow` is a strict superset of what `MyApprovals` showed: `ApprovalRow` carried state,
`rejectedReason`, `questionCount`, `durationMinutes` and `versionNo`, and every one of them is on
`ExamVersionRow`. The two facts on `ApprovalRow` that do **not** cross over are `submittedAt` and
`selfAuthored`, and neither is missed: on a screen that only ever shows the caller's own exams,
`selfAuthored` is true on every row and therefore says nothing, and `submittedAt` is replaced by the
version's `createdAt`.

`Routes.EXAMS` already anticipates this. Its javadoc says E8 "ships the approval-status half of this
screen only" and that E7 "replaces the screen behind this id when it lands", and the notification
route table points `APPROVAL_APPROVED` and `APPROVAL_REJECTED` at `exams`. So the swap is a screen
swap behind an id that already has the right spelling, and F4.2's "reason visible on the exam" keeps
working across it because `rejectedReason` is on `ExamVersionRow`.

**The retirement lands in the same PR as the replacement**, on the pattern the lead ruled for the
legacy bank screen on 2026-08-23: the screen swap and the removal in one change, so there is never a
window where two overlapping reads of one fact are both live.

---

## 9. What is deliberately absent

- **No exam delete.** Section 3.
- **No bank browse verb.** Section 3; `BANK_LIST` serves the picker.
- **No answer key.** Section 4; `ComposedQuestion` has nowhere to put one, so E7 adds no type to the
  correctness boundary and the leak guard's licensed list does not grow.
- **No release verb.** E9 is merged and owns that; an APPROVED version is handed off, not released
  from here.
- **No `points` on `ComposedQuestion` sent back as a percentage.** Points are the stored integer.
  E7.13's difficulty sliders may render percentages, and they send counts.
- **No draft autosave.** Section 1: there is no work-in-progress row to autosave into. This is the
  most likely thing to be asked for later, and it is additive if it is: it would need its own
  storage, not a relaxation of the points rule.

---

## 10. Rulings needed before the freeze

Indexed so they can be answered straight down the list.

1. **Package name: `common/dto/authoring`.** `common/dto/exam` is E10/E11's. The alternative is
   `common/dto/exambuild`, matching the server package. I prefer `authoring` because the pipeline is
   named that everywhere else; I have no real stake in it and would rather be told than guess, since
   every import in E7 depends on it.

2. **Author-only scope, confirmed?** Section 2. A co-teacher of the same course cannot open, edit or
   submit another teacher's exam. This follows E14's frozen author-only ruling and S-12, and it is
   the assumption with the widest blast radius in the document: widening it later is additive
   (a guard change), narrowing it later is not.

3. **The 600-minute duration ceiling.** Invented here. The column forbids only zero and negatives.
   Yes, no, or a different number.

4. **Where the infeasibility sentence is composed.** Section 7.1 keeps the wire structural and
   composes the sentence in `ExamCopy`, because PRD §4.1's copy rules and Hebrew rendering are client
   concerns. But `BankMessages` and `ReleaseMessages` both put their sentences server-side, so this
   deviates from the house pattern in the one place the lead has called defense-critical. Happy to
   move it server-side and carry a formatted `summary` string alongside the structured fields if
   that is the call; what I want to avoid is carrying both and letting them disagree.

5. **Does `EXAM_VERSION_GET` need to serve a coordinator?** It does not today: she previews through
   E8's `EXAM_PREVIEW_GET`, which renders the paper as a student sees it plus the teacher-only
   block. I believe that is complete and E7 needs no coordinator path at all. Flagged because it is
   the sort of thing that is discovered during the T-4 walkthrough rather than during a review.

---

## 11. Traceability

| Task | Where it lands |
|---|---|
| E7.1 create draft, 6-digit id | `EXAM_CREATE`, `ExamIdAllocator` (S-10, F3.4) |
| E7.2 composition update | `EXAM_VERSION_SAVE`, full replace (§5.6) |
| E7.3 points rule, live sum | §5.1; the breakdown the UI sums is `ComposedQuestion.points` |
| E7.4 auto-generation ⚑ | `EXAM_AUTO_COMPOSE`, §7 |
| E7.5 edit approved or pending → new DRAFT | `EXAM_VERSION_REVISE` (C-2, F3.5) |
| E7.6 submit for approval | `EXAM_SUBMIT` → `ApprovalService.versionSubmitted` (§5.5) |
| E7.7 newer-question-version indicator | `ComposedQuestion.pinnedVersionNo` vs `latestVersionNo` |
| E7.8 validator unit tests | `ExamValidator`, shared by create and save so the two cannot diverge |
| E7.9 verbs and DTOs frozen with [L] | this document |
| E7.10 exam list screen | `EXAM_LIST`, §8 |
| E7.14 version history, update-question action | `EXAM_VERSION_GET` + re-pin and save |

Acceptance: T-3.1 through T-3.9. T-3.5 and T-3.6 are §7's two shots; T-3.9 is §5.2's duplicate rule.

---

## 12. Lead rulings at type-landing (2026-08-23)

§10 asked five questions. All five are answered here, straight down the list, and the answers are
applied to the document above rather than only recorded here. Where a ruling changed the draft, the
change is marked in place.

**1. Package: `common/dto/authoring`. Confirmed as drafted.** The alternative was
`common/dto/exambuild`, matching the server package. `authoring` wins because the pipeline is
called that everywhere else in the repository, and because the reason to avoid `common/dto/exam` is
the one that actually matters: it is E10/E11's take-exam surface, and putting a student's paper and
a teacher's composition in one package would sit two audiences together whose whole relationship is
that one of them must never see what the other holds. Every import in E7 depends on this, so it is
settled before a handler is written and not after.

**2. Author-only scope: CONFIRMED.** A co-teacher of the same course cannot open, edit or submit
another teacher's exam. This is not an invention of this document, it is E14's frozen ruling
applied to the surface it was always about: `RESULTS_WIRE_CONTRACT` froze E14 as author-only on
"literally F9.2's exams she wrote", deliberately narrower than the monitor's author-or-runner rule,
and S-12 records an author on the exam precisely so that F3.5's edit-makes-a-version is a statement
about that author's document. The coordinator's read of somebody else's exam already exists and is
E8's `EXAM_PREVIEW_GET`, guarded by `requireCoordinatorOf`; nothing in E7 widens it. The asymmetry
noted in §2 is the reason to be comfortable ruling now: widening later is a guard change and is
additive, narrowing later is not.

**3. Duration ceiling: 480, not 600.** The draft invented 600 and asked for a yes, a no or a
different number. The answer is a different number, and the reasoning is the draft's own: the
ceiling exists to catch a typo of `600` for `60`. **A ceiling of 600 admits the very 600-for-60
typo it was invented to catch** — it is the one value in the whole range that the rule must refuse
and the only one it would have let through. Eight hours is already far past any real exam and
refuses the mistake the rule is for. Applied to §5.3 in both places, and carried on the wire as
`ExamCreateRequest.MAX_DURATION_MINUTES` and its alias on `ExamVersionSave`, so the validator and
the client's spinner count to one number.

**4. The infeasibility sentence stays on the client. `Shortfall` stays structural.** The wire
carries four fields and **no `summary` string**. The draft offered to move it server-side beside
`BankMessages` and `ReleaseMessages` and named the thing it wanted to avoid — carrying both and
letting them disagree — which is exactly right and is the reason the answer is no. The sentence is
composed **once**, in `ExamCopy`, because PRD §4.1's copy rules and the Hebrew rendering around
them are client concerns, and because a formatted string on the wire is a second expression of a
fact the four fields already carry. **The PRD's example sentence is pinned by a copy test**:
`Topic 'Algebra': requested 5 Hard, bank has 2` is the acceptance artefact of F3.3, so it is
asserted somewhere rather than typed correctly by luck on the day. That test belongs with
`ExamCopy` and is Member A's to write with the screen; the four shapes it must cover are in §7.1
and are already pinned as data by `AutoComposeResultTest`.

**5. `EXAM_VERSION_GET` needs no coordinator path.** Confirmed as the draft believed. She previews
through E8's `EXAM_PREVIEW_GET`, which renders the paper as a student sees it plus the teacher-only
block, and that is her **complete** read: approving an exam is deciding about a paper, not editing
one, and the preview already carries everything the decision needs including the answer key. Adding
a coordinator path here would give her a second, differently-shaped read of the same version, and
the first time the two disagreed the one she had not been trained to distrust would be the one she
believed. If the T-4 walkthrough turns up a real gap, widening the preview is additive; widening
this verb is not the fix.

---

**Types landed by the lead on 2026-08-23.** `common/protocol/Verb.java` gained its
`Exam builder (E7)` section with all seven verbs; `common/dto/authoring` holds the fourteen records
of §4, reusing `Difficulty` and `ApprovalState` rather than redeclaring either. `MY_APPROVALS_GET`
is deliberately **still live** — §8 binds its removal to the same PR as E7.10's screen swap, and
removing it at type-landing time would open the window from the other side by leaving E8's
`MyApprovalsView` calling a verb that no longer exists.

**Freeze happens on Member A's handlers PR, same as BANK.** Until then this header says DRAFT and
means it: the shapes are compiled and tested, but a handler author who finds a genuine problem with
one still gets to say so, and the correction is cheaper today than it will be on the far side of
the freeze. From FROZEN, terms are additive only.
