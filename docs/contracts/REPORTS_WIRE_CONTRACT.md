# E15 principal report wire contract — FROZEN v1

**Status: FROZEN v1 (lead, 2026-08-23).** Written alongside the E15 implementation; nothing outside
this feature consumes it yet, so it is still cheap to change. Once frozen, additive changes only
(new optional fields, new verbs, new dimension constants) with any rename or retype recorded here
as an amendment.

Package: `common/dto/report` (all types are `Serializable` records, wire-safe, no entity types).
Verbs live in `common/protocol/Verb.java`, grouped under a `Principal reports (E15)` section.
Requirement ids: PRD **F9.3**, **F9.4**, **S-7**, **S-37**, **C-5**; spec **7.3** / **7.3.1**;
hardening **H15.2** and **H14.4**.

This is the contract `RESULTS_WIRE_CONTRACT.md`'s "what is deliberately absent" section pointed
at: *"No cross-execution comparison. That is F9.4 / E15's report engine, and its dimension
strategies will consume `ResultStatistics` rather than redefine it."* They do.

---

## 1. What this contract encodes ⚑

**F9.4 does not ask for three reports.** It asks for *"one parameterized report mechanism
(dimension = Strategy) so a new report type is a new strategy class + menu entry, nothing else"*.
So the shape below has a dimension where another design would have three verbs, three payloads and
three screens. Not one record in this package has a teacher field, a course field or a student
field.

Three claims are structural rather than aspirational, and each has a test that fails the build:

1. **The engine names no dimension.** `ReportEngineExtensibilityTest` reads `ReportEngine.java`
   and fails if `BY_TEACHER`, `BY_COURSE`, `BY_STUDENT` or any strategy class name appears in it.
   A behavioural test alone would pass over an engine that special-cased the shipped three and
   fell through to a generic path.
2. **A fourth dimension is served without touching anything.** The same suite defines a complete
   strategy inside the test file, hands it to a real engine beside the shipped ones, and gets a
   real report out: subjects, rows, frozen statistics and a pooled summary, through code written
   before it existed.
3. **Registration is one list.** `ReportStrategies.all()`, and `HSTSServer` registers the list
   rather than the strategies (asserted).

**Statistics travel exactly as frozen.** Population sigma with divisor `n`, pass mark 55, ten
stored buckets, read from `exam_executions.stats` (F8.5, C-5). Nothing on this path recomputes any
of them, pinned by a test that serves a stored record contradicting itself and asserts the stored
figures reach the wire.

**Cancelled sittings do not exist here.** H15.2 ⚑, landing where it was written for.

---

## 2. Roles and scope — the rule both handlers enforce

- Both verbs: `Authorization.requireRole(caller, PRINCIPAL)` **and nothing else.**
- That is unusual in this codebase and it is deliberate. Every other read feature composes a role
  check with a scope — authorship in E14, enrolment in E10, the taught-course set in E6 — because
  those roles see a slice. The principal does not: spec 7.3.1 and F9.3 give her the whole school
  to read, so there is no slice to compute, and a scope check here would be a guard that could
  only ever pass.
- **A teacher, a coordinator and a student get `FORBIDDEN`,** from the role gate. Not an empty
  answer: an empty list would read as "there are no reports", which is a false statement about the
  school, and it would leave a teacher clicking a screen that will never fill. Both negatives and
  the positive are tested.
- **Zero mutating verbs, structurally (S-7).** The feature's whole data seam, `ReportData`, is
  read methods. There is no expression in `server/features/reports` that could change a row, and
  adding one would mean adding a method to that interface first, in a file whose javadoc says why
  it has none.
- The principal's client rail offers exactly one feature route (`Routes.REPORTS`), and
  `SessionRoutesTest` asserts her route set exactly.

---

## 3. Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `REPORT_SUBJECTS_GET` | principal | `ReportSubjectsRequest` | `ReportSubjects` |
| `REPORT_GET` | principal | `ReportRequest` | `ReportResult` |

No push verbs. A report compares sittings that have already closed; nothing about one can move
while it is on screen, and a screen that subscribed to it would be subscribing to something that
cannot change.

No pagination. School-sized lists (PRD section 6).

---

## 4. DTOs (`common/dto/report`)

- **`ReportDimension`** — wire enum `BY_TEACHER | BY_COURSE | BY_STUDENT`, carrying `segment()`
  (the picker's label) and `subjectNoun()` (the picker's prompt). The server holds one
  `DimensionStrategy` per constant. **A fourth report type is a fourth constant here plus a
  strategy class plus one registration line** — see section 7 for the full touch list.

- **`ReportSubject(String id, String label, String detail, int executions)`** — a teacher, a
  course or a student, in the one shape the picker renders for every dimension.
  - `id` is **opaque**: a user id in decimal for a person, a two-character course code for a
    course. Only the strategy that issued it interprets it, which is what lets a fourth dimension
    key on something else again with no wire change.
  - `executions` is the number of *reportable* sittings this subject has right now. It is on the
    subject on purpose: a principal choosing a teacher whose exams have never closed should learn
    that before she clicks, which turns E15.5's degenerate case into a label rather than a dead
    end (section 4.1). Zero is a legitimate answer and such subjects are **listed, not hidden**.

- **`ReportSubjects(ReportDimension dimension, List<ReportSubject> subjects)`** — school-wide,
  ordered for display. The dimension is echoed so an answer that arrives after the principal has
  switched segments is discarded rather than rendered under the wrong heading.

- **`ReportSubjectsRequest(ReportDimension dimension)`** — a dimension and nothing else. There is
  no scope field, because a field a client could set is a field a client could widen.

- **`ReportRequest(ReportDimension dimension, String subjectId)`** — the whole parameterisation of
  the mechanism, in two components.

- **`ReportRow(long executionId, String code4, String examName, String courseCode,
  String courseName, Instant openAt, Instant closeAt, int participants,
  ResultStatistics statistics)`**
  - A row exists **only** for a sitting that is CLOSED with its statistics frozen. A live sitting
    has no figures to compare, a scheduled one has no figures at all, and a cancelled one was
    never sat. None of them belongs in a comparison, and including them with blanks would produce
    a table whose gaps a reader has to interpret — half of them as zeros.
  - `examName` is the **released version's** name, so a sitting is labelled with what the students
    actually saw even after the exam is renamed.
  - `participants` is a `COUNT` over `exam_attempts`, deliberately **not** `statistics.count()`.
    The gap between them is the student whose paper was never marked, and it is a fact a principal
    should be able to see rather than one the report quietly closes.
  - `statistics` **reuses `common.dto.results.ResultStatistics` unchanged.** A second statistics
    record would be a second place for a divisor or a threshold to be chosen.

- **`ReportSummary(...)`** — section 5, which is the part of this contract worth reading twice.

- **`ReportResult(ReportDimension dimension, ReportSubject subject, List<ReportRow> rows,
  ReportSummary summary)`** — rows are **oldest first**: a comparison across time reads left to
  right, and a picker's "newest first" ordering would put the trend backwards.

---

## 5. The summary arithmetic ⚑ — the reason `ReportSummary` exists

**The mean of the means is a lie, and this record exists to not tell it.** The obvious summary of
a comparison table is to average its columns. That is wrong whenever the sittings differ in size,
which is always. Eight students averaging 72.5 and four averaging 65 do not average 68.75; they
average **70**, because the first sitting contributed twice as many scores. The difference is
small, plausible, and permanent once printed.

Every figure is aggregated from what the rows carry. All of it is arithmetic on stored numbers —
the same move `FrozenStatistics` makes when it reconstitutes a pass count from a stored rate — and
none of it goes back to a grade row.

| Component | How it is computed | Exact? |
|---|---|---|
| `executions` | row count | yes |
| `participants` | sum of `row.participants` | yes |
| `scored` | sum of `n_i`, and equally the sum of the pooled deciles | yes |
| `mean` | `sum(mu_i * n_i) / sum(n_i)` — participant-weighted | **exact**: each stored mean is its own score total over its own population |
| `standardDeviation` | `sigma^2 = sum n_i(sigma_i^2 + mu_i^2) / N - mu^2`, divisor `n` throughout | **exact**: `n_i(sigma_i^2 + mu_i^2)` is row `i`'s sum of squares, and sums of squares add |
| `medianBucket` | the decile holding the `ceil(scored/2)`-th lowest score, read off the pooled deciles | a band, see below |
| `min` / `max` | min of mins, max of maxes | yes |
| `passCount` | sum of stored `passCount_i` | yes; the pass mark of 55 is applied nowhere in this class |
| `passRate` | `passCount / scored` | yes |
| `deciles` | sum per bucket | yes |

**Why there is no pooled median.** A median cannot be recovered from medians: two sittings with
medians 60 and 80 can have a combined median anywhere between them, and averaging the two would
produce a number with no referent. What the rows *do* carry is the pooled distribution, and that
pins the median to a band. `medianBucket` is that band; the screen prints "70-79" and never a
point value. Saying "the middle score is in the 70s" is a claim the stored data supports.

**Hand-computed fixture** (`ReportDtoTest.SummaryArithmetic`, and again end to end in
`JpaReportStoreContract`): seeded execution 4821 (45, 55, 60, 70, 75, 85, 90, 100) pooled with a
second sitting (50, 60, 70, 80). Twelve scores totalling 840 gives mean **70** exactly.
Sum of (x-70)^2 = 2500 + 600 = 3100, so sigma = sqrt(3100/12) = **16.0728**. Ten of twelve passed.
The sixth lowest is 70, so the band is **70-79**. Pooled deciles `[0,0,0,0,1,2,2,3,2,2]`. The mean
of the means, 68.75, is asserted **not** to be the answer.

**Degenerate cases.** `ReportSummary.EMPTY` for no rows: ten zero buckets, `medianBucket = -1`,
and `isEmpty()` rather than a zero mean is what the screen branches on — a mean of 0.0 for a
teacher whose exams have never closed would be a statement about her classes, and it would be
false. Rows with nothing scored report their count and nothing else. Variance is clamped at zero,
so a report of identical scores cannot produce a NaN sigma through floating point.

---

## 6. What a report is allowed to look at

One clause, written once in `ExecutionRepository.REPORTABLE`, shared by all three population
queries:

```
ex.status = CLOSED  and  ex.stats is not null
```

- **`status = CLOSED`** rather than `<> CANCELLED`, so the exclusion survives a fifth status being
  added. This is **H15.2 ⚑**: a cancelled run was never sat and has no results.
- **`stats is not null`** — the comparison is of frozen statistics, so a sitting whose grading
  never finished has nothing to contribute and is left out rather than shown with blanks.
- Every H15.2 test builds a **cancelled sitting with statistics frozen on it** — something the
  seed does not contain and the ordinary flow would never produce. It is the only fixture that can
  tell "cancelled is excluded" apart from "cancelled sittings have no statistics anyway".

A sitting whose stored record is *unusable* (wrong bucket width, zero population, mean outside
0..100) is dropped from the rows and from the summary, with `FrozenStatistics`'s ERROR line naming
it. A report cannot render "grading unfinished" for a row whose whole purpose is to be compared,
and including it as zeros would pull the pooled mean down invisibly.

### 6.1 Open decision for the lead — what "the same teacher" means

`BY_TEACHER` keys on the exam's **author** (`exams.author_id`), not on who released the sitting
(`exam_executions.created_by`). The reasoning is E14's, in `RESULTS_WIRE_CONTRACT` section 2: the
frozen statistics describe how a paper was answered, and the paper is the work of whoever wrote
it. A teacher who lent her room to a colleague's exam did not set those questions.

The other reading is legitimate and is a legitimate **fourth strategy** rather than a change to
this one: "sittings she ran", keyed on `created_by`, one class beside `ByTeacherStrategy`. On the
seed the two answers coincide, so this is a decision to make now rather than one the data will
force. **Ruled at freeze — see below.**

---

## Lead rulings at freeze (2026-08-23)

1. **§6.1 ACCEPTED as written: `BY_TEACHER` keys on the exam's author (`exams.author_id`).**
   The frozen statistics describe how a paper was answered, and the paper is its author's work;
   this is the same reading RESULTS froze in its section 2, and the two contracts must not
   disagree about whose number a mean is. The "sittings she ran" reading (keyed on
   `created_by`) stays what section 6.1 already calls it: a legitimate FOURTH strategy, one
   class beside `ByTeacherStrategy`, built only if someone asks for it. Not phase 1.

2. **No grouped bar chart — ACCEPTED.** The comparison screen renders the selected row's own
   distribution through the existing `.hsts-stat-chart`, and the rows themselves carry the
   comparison. A grouped chart would need a second chart component two days before the defense
   for information the table already states; declined on the same grounds V8 was declined in
   APPROVAL. If a reviewer asks, the answer is section 7: the screen is dimension-agnostic,
   and a chart is a rendering decision, not a wire decision.

---

## 7. What a fourth report type touches ⚑

The concrete form of S-37, stated so a future author does not have to measure it:

| File | Change |
|---|---|
| `common/dto/report/ReportDimension.java` | one constant, with its two labels |
| `server/features/reports/BySomethingStrategy.java` | **new**, about 40 lines |
| `server/features/reports/ReportStrategies.java` | one line in `all()` |
| `server/features/reports/ReportData.java` | three methods, *only if* it needs a population the seam cannot already produce |
| `server/features/reports/JpaReportStore.java` | their implementations, one line each |
| `server/db/repos/ExecutionRepository.java` | one query and one `ReportGrouping` constant, same condition |

**Untouched:** the engine, every other DTO, both handlers, the summary arithmetic, the whole
screen (its segments are `ReportDimension.values()` and its picker prompt is `subjectNoun()`),
the CSS, and the server assembly.

The test-file strategy in `ReportEngineExtensibilityTest` needed **only** its own class: it reused
the seam it was handed.

---

## 8. Error codes

`VALIDATION` malformed payload · `NOT_FOUND` a dimension this build does not serve, or a subject
id that names nothing (including one of the wrong shape) · `FORBIDDEN` role gate · `UNAUTHORIZED`
no session.

There is no `CONFLICT` and no `INTERNAL` path of its own: this feature writes nothing.

**A subject with nothing to compare is `OK` with no rows, never `NOT_FOUND`.** "This teacher has
had no exam close" is a true answer to what the principal asked, and refusing it would read as a
fault. `NOT_FOUND` is reserved for an id that names no subject at all.

---

## 9. What is deliberately absent

- **No writes.** Not one verb here changes anything, and the data seam has no method that could.
- **No push.** See section 3.
- **No per-student scores.** `BY_STUDENT` compares the *classes* a student sat with, not her own
  marks: every row carries the sitting's frozen class statistics, as the other two dimensions do.
  Her grades are F9.1's screen, gated on being her.
- **No second statistics record.** `ResultStatistics` is reused unchanged (section 4).
- **No export.** The print-friendly layout is a layout mode, as E14.4's is. A real export is a
  separate piece of work and is not claimed here.
- **No pagination.** School-sized lists (PRD section 6).

---

## Additive amendments

Everything below was added **after** the 2026-08-23 freeze, under the additive-only rule this
contract opened with: no verb renamed, no payload component removed or reordered, no semantics
changed for any existing field, and not one line of sections 1-9 touched. A client built against
v1 still works against a server that implements the amendments, and vice versa — that is the test
each amendment has to pass to be allowed in.

### A1 — data browser reads (E15.2 / F9.3, T-11, added 2026-08-23)

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `DATA_EXAMS_GET` | principal | *(none)* | `DataExams` |
| `DATA_RESULTS_GET` | principal | *(none)* | `DataResults` |

**Why this contract owns them.** F9.3 gives the principal a read of "question bank, exams,
results" and this contract already owns her role: section 2's rule — `requireRole(PRINCIPAL)` and
nothing else, because spec 7.3.1 gives her the whole school — is exactly the rule these two need,
and restating it in a fourth contract would be a second place for it to drift. The DTOs live
beside the report DTOs in `common/dto/report` for the same reason.

**Only two verbs were added, and the third tab needed none ⚑.** The screen's Questions tab calls
`BANK_LIST`, unchanged and unwidened. The principal has been on that verb's role list since E6 and
reaches every course through it (BANK_WIRE_CONTRACT section 2's read/write table, and its section
7 ruling 2). A `DATA_QUESTIONS_GET` beside it would have been a second answer to a question that
already has one, and `VerbTest` asserts that no such verb exists. What genuinely did not exist was
a school-wide **exam** listing and a school-wide **results** listing: `RESULTS_EXAMS_GET` and
`RESULTS_EXECUTION_GET` are scoped to the exams the caller *wrote* (S-35), which is a scope the
principal does not have and must not be given by loosening theirs. Two new queries, two new
handlers, nothing existing widened.

**Neither request has a payload.** Not "an empty record" — `null`. Her scope is the school, so
there is nothing for a request to narrow, and a field a client could set is a field a client could
widen (section 4's reasoning for `ReportSubjectsRequest`). The practical consequence is that
neither verb has a `VALIDATION` path: a request that carries something anyway is answered rather
than refused, because nothing in it could have changed what was read.

**The filters are the screen's, not the wire's.** The text filter and the course picker on all
three tabs run client-side over rows already in hand. School-sized lists (PRD section 6), so a
round trip per keystroke buys nothing, and the course dropdown is *derived from the loaded rows*,
which is also why it can never offer a course that would filter the list to nothing.

#### DTOs (`common/dto/report`)

- **`DataExamRow(String displayId6, String examName, String courseCode, String courseName,
  String authorName, int versions, Instant lastVersionAt)`** — one exam in the catalogue.
  - `examName` is the **latest version's**, because this row answers "which exams exist", which
    is a question about the exam. A sitting is labelled with the *released* version's name
    (`ReportRow.examName`), so the two lists deliberately disagree about a renamed exam and each
    is right about its own question.
  - `versions` is the latest version number, which is also how many versions exist. Positive by
    construction: an exam always has at least one version, so a zero is a broken query rather
    than a state to draw.
  - `authorName` and no author **id**. This row is read; an id would be the beginning of a
    request that acted on her.
  - **No questions, no answer key, no instructions and — a decision, flagged — no approval
    status.** See 6.2 below.
- **`DataExams(List<DataExamRow> exams)`** — ordered by display id, unpaginated. `EMPTY` for a
  school that has written none, which is an empty state to draw and never an error.
- **`DataResults(List<ReportRow> sittings)`** — **`ReportRow` is reused unchanged.** A sitting the
  principal browses and a sitting a report compares are the same thing seen twice, and a second
  row record would be a second place for a divisor, a pass mark or a decile width to be chosen —
  precisely what section 4 refused when it reused `ResultStatistics` rather than restating it.
  `EMPTY` for a school where nothing has closed and been marked.
  - **Newest first**, which is the opposite of `ReportResult`'s ordering and deliberate: a browse
    is a filing cabinet and the row being looked for is usually the most recent, while a report is
    a trend and reads left to right from the oldest.
  - A sitting appears only when it is **CLOSED with its statistics frozen** — the same
    `ExecutionRepository.REPORTABLE` clause the three report populations share, so the browse and
    the reports cannot disagree about which sittings exist, and **cancelled runs are absent from
    both (H15.2 ⚑)**. The screen says this once, above the table, rather than showing four kinds
    of blank row for a reader to interpret.

#### Error codes

`FORBIDDEN` role gate · `UNAUTHORIZED` no session. Nothing else: there is no payload to validate,
no id to fail to find, and neither verb writes.

#### 6.2 What a principal's exam row may carry ⚑ — RULED (lead, 2026-08-23): omission stands

`DataExamRow` **omits the exam's approval status**, and this was decided conservatively rather
than confidently. The argument for omitting it: approval is a workflow between an author and her
coordinator (F4.1), and "rejected, twice, and here is the reason" is a fact about two members of
staff rather than a fact about the school's exam catalogue. The argument against: spec 7.3.1 gives
the principal read-only access to all data *as entered*, and a version's status is entered data —
which is the same argument that decided BANK's section 7 ruling 2 in the opposite direction, for
the answer key.

The two are not obviously alike: an answer key is content, a rejection reason is a judgement about
a colleague. **If the answer is ever that she sees it, the change is one component appended to
`DataExamRow` and one column on the Exams tab; nothing else moves.**

**Lead ruling (2026-08-23): the omission stands.** The distinction the pass drew is the right
one: BANK ruling 2 admits the answer key because it is the question's own content, entered by its
author for the catalogue; an approval status drags in a rejection, and a rejection is F4.1's
conversation between two colleagues, not catalogue data. Spec 7.3.1's "all data as entered" is
about the school's *content*, and the principal already learns what matters operationally from
the Results tab: a sitting exists only for an exam that was approved and released. If the defense
asks, that is the answer: she sees every consequence of approval, and none of the judgement.

---

### A2 — `latestVersionId` on `DataExamRow` (U-44's openable catalogue row, added 2026-08-30)

`DataExamRow` gains `long latestVersionId` **appended** after `lastVersionAt`: the primary key of
the version its name and date already come from. Nothing is renamed, retyped or reordered, and no
existing component changes meaning.

**Why.** The lead's ruling of 2026-08-30 (U-44) gave the Data browser's Exams rows a detail
screen, and that screen is E8's `EXAM_PREVIEW_GET` (APPROVAL amendment A1), which is addressed by
**version**. This row carried a display id and a version *number* and no version id, so the
browser could list an exam and not open it — the same gap, for the same join reason, that BANK's
amendment A1 closed on `BankQuestionRow` when the builder's picker could show a question and not
pin it.

**Alternative rejected: a resolve verb.** A `DATA_EXAM_VERSION_GET` turning a display id into a
version id would be a round trip for a fact the catalogue row already knows — the correlated
subquery in `ExamRepository.findAllSummaries` has already bound `v` to the newest version, so the
id is a column of a row being selected rather than a second query. BANK A1 declined the same
option for the same reason.

**It is the one component of this row nobody reads.** Everything else on it is printed in a cell;
this is what a click carries. It identifies a *version*, never a person: `authorName` still
travels as a name with no id beside it, for the reason section A1's DTO note gives.

**No disclosure class is added.** Exam version PKs already travel on the authoring wire
(`ExamVersionRow`, `ComposedQuestion`) and on the approval wire (`ApprovalRow.examVersionId`), and
the verb this id is spent on refuses anyone the amendment above does not name. `SchoolExam` gains
the same field for the same reason, server-side.

**Compatibility.** `DataExamRow.isOpenable()` is `latestVersionId > 0`, and the client leaves a row
that answers false unopenable rather than sending a request for version 0 — which is what a v1
server's row would look like if the two tiers were ever built separately. They are not (both ship
from one build), so this is a guard rather than a supported configuration.

### A2 - the subject's own score on by-student rows (2026-09-01, lead's ruling reversal)

`ReportRow` gains a tenth component, `Integer subjectScore`: the subject's approved effective
score on that sitting, filled ONLY when the report's dimension is BY_STUDENT and null everywhere
else - including a by-student row whose grade is not yet approved, so an unpublished grade stays
unpublished on the principal's screen too. Additive: a nine-argument constructor remains and
means "no subject score", so every pre-A2 call site and every stored expectation still holds.

**The ruling this reverses, on the record.** Acceptance 12.3 ruled that a personal score has no
field to travel in, reading S-26's "not available to students" as a general privacy stance. That
was an over-application: S-26 restricts STUDENTS, while spec 7.3.1 grants the principal read
access to all results as entered, and 7.3.2.3 asks how grades change "בין הבחינות השונות של אותה
תלמידה" - her trajectory, which class means cannot answer. The rows still carry the class
statistics; what changed is that the one person the report is about is now visible inside them.
Student-facing wire types are untouched and the leak guards still hold.

**A2 consumption note (2026-09-02, U-90 full form).** The by-student view was remodelled into a
dedicated student report (hero, her cards, a score trail, "Her sittings"). This is client-side
COMPOSITION over A2's one field: no further wire surface was added, and the other dimensions'
payloads and rendering are untouched. The trail draws only approved scores and breaks across an
unapproved sitting - the same rule A2 states for the column.