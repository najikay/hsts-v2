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
