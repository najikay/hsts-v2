# E14 teacher results & statistics wire contract — FROZEN v1

**Status: frozen 2026-08-21** after lead review (independent verify at merge).

**Lead rulings at freeze:**
1. Scope is AUTHOR-ONLY — literally F9.2's "exams she wrote"; the runner's surfaces are the monitor and grading, not results. Narrower than the monitor's author-or-runner rule, on purpose.
2. `passCount` reconstitution from the stored full-precision `passRate` counts as READING, not recomputing (Member A's 1e-13 analysis, accepted). **This ruling expires** if `passRate` ever becomes a coarsely-quantised value — at that moment the count gets its own stored field.
3. Cancelled sittings are excluded from F9.2's results as well as from E15's reports — H15.2's principle, applied one screen earlier than its letter.
4. Statistics travel exactly as frozen at approval time (population σ, pass mark 55); nothing on this path recomputes them, pinned by the stored-not-recomputed test.

After the freeze the same rule applies as to the grading contract — record names, component names, their order and their types are the wire, and
a rename is a protocol break between two separately-built JARs rather than a refactor.

This is the contract the `GRADING_WIRE_CONTRACT.md` "what is deliberately absent" section
pointed at: *"No stats DTOs — teacher statistics/histogram are E14's contract, drafted when E14
starts."*

Package: `common/dto/results` (all types are `Serializable` records, wire-safe, no entity
types). Verbs live in `common/protocol/Verb.java`, grouped under a
`Teacher results & statistics (E14)` section.

## Roles and scope — the rules every handler enforces

- Both verbs: `Authorization.requireRole(caller, TEACHER, COORDINATOR)` **plus authorship** —
  the scope is every exam the caller **wrote**, resolved from `exams.author` through the
  repositories, never from the payload (P-5: a `CallerContext` is always read).
- **S-35 is a `WHERE` clause, not a check.** `ExamRepository.findAuthoredSummaries` and
  `ExecutionRepository.findContextsByExamAuthor` both filter on `exams.author`, so a sitting
  another teacher released is returned to the exam's author, and a sitting of somebody else's
  exam is never loaded in the first place.
- **This is narrower than E11's monitor rule, deliberately.** `ExecutionContext.isOwnedBy`
  admits the executing teacher *or* the author, because running the room is a live-monitoring
  concern. Reading results is F9.2's question about the exam, and the exam belongs to the
  person who wrote it. A teacher who ran a colleague's exam sees its live monitor and does not
  see its results table.
- A non-author asking for an execution gets `NOT_FOUND` with the **same sentence** an unknown
  id gets. Two answers would make the verb a membership oracle; the E14.5 negative test asserts
  the two responses are indistinguishable, not merely both refused.
- **Cancelled executions do not exist here.** They were never sat, they have no results, and
  they are excluded from the list and answered `NOT_FOUND` on the detail verb (H15.2 ⚑).

## Verbs

| Verb | Caller | Request payload | OK payload |
|---|---|---|---|
| `RESULTS_EXAMS_GET` | teacher | `null` | `TeacherResults` |
| `RESULTS_EXECUTION_GET` | teacher | `ExecutionResultsRequest` | `ExecutionResults` |

No push verbs. Results are frozen history, not a live feed: the numbers on this screen stop
changing when the execution's last grade is approved, and a screen that subscribed to them
would be subscribing to something that cannot move.

## DTOs (`common/dto/results`)

- `ExecutionState` — wire enum `SCHEDULED | LIVE | CLOSED | CANCELLED`, mirroring the stored
  `ExecutionStatus`. `CANCELLED` is declared and never sent (see above).
- `TeacherResults(List<ExamResultRow> exams)` — the whole list, no pagination (§6 scale).
  `TeacherResults.EMPTY` is the shared answer for a teacher who has written nothing.
- `ExamResultRow(long examId, String displayId, String examName, String courseCode,
  String courseName, List<ExecutionResultRow> executions)` — an exam that has **never been
  released** keeps its place with an empty list rather than being dropped. `examName` here is
  the exam's *current* name (its highest version's), because this row answers "which exams did
  I write".
- `ExecutionResultRow(long executionId, String code4, Instant openAt, Instant closeAt,
  ExecutionState state, int participants, int gradedCount, boolean hasStatistics,
  boolean releasedByAnotherTeacher)`
  - `participants` is a `COUNT` over `exam_attempts` (§5 forbids counter columns), so it is
    right for a live sitting too; `gradedCount` counts the `grades` rows behind them, which is
    what makes "6 of 8 marked" answerable before opening the sitting.
  - `hasStatistics` is carried, **not** inferred from `gradedCount == participants`: grading
    finishing and approval completing are different events, and a picker promising a histogram
    it cannot draw is worse than one that says grading is not finished.
  - `releasedByAnotherTeacher` is S-35 made visible on screen, so an author is not surprised by
    a sitting she does not remember scheduling.
- `ExecutionResultsRequest(long executionId)` — an id and nothing else. There is no field a
  client could use to widen its own scope.
- `ExecutionResults(ExecutionResultRow execution, String examName, String courseCode,
  String courseName, List<StudentGradeRow> rows, ResultStatistics stats)`
  - `rows` **reuses `common.dto.grading.StudentGradeRow`.** This is the teacher path, so
    `overrideReason` is populated (S-23); the structural stripping that protects students lives
    in `MyGrades`/`CheckedForm` and is untouched by this.
  - ⚑ **2026-08-26 (B-16):** the row is at **v1.2** and this is the one path that populates its
    `attemptStatus` and `actualMinutes` — see `GRADING_WIRE_CONTRACT` **A5**, which owns the
    record. T-10.2 asks for "score, submitted vs timed out, solving time"; before that amendment
    only the score arrived, and a timed-out paper was indistinguishable from a submitted one.
    Additive: the twelve-component constructor is retained and every other path still gets null.
  - One row per **grade**, not per attempt: an unmarked paper has no score and is absent. The
    gap is never silent — `execution.participants()` against `rows.size()` is the
    "6 of 8 papers marked" line the screen prints.
  - `examName` here is the **released version's** name, so a sitting is always labelled with
    what the students actually saw, even after the exam is renamed.
  - `stats` is `null` while grading is unfinished; read it through `statistics()`, which
    returns an `Optional`, so the absent case cannot be forgotten at a call site.
- `ResultStatistics(int count, double mean, double median, double standardDeviation, int min,
  int max, int passCount, double passRate, List<Integer> deciles)` — a one-for-one mapping of
  `server.features.grading.ScoreStatistics`. `deciles` must be exactly ten buckets; anything
  else throws at construction rather than reaching a chart that would draw it wrong.

## The statistics rule (F8.5) — the reason this contract exists

Every figure in `ResultStatistics` is **read** from `exam_executions.stats`, frozen when the
execution's last grade was approved. Nothing on the results path recomputes any of it.

- σ is the **population** form, divisor `n`. Seeded execution 4821 has σ = 17.5; the sample
  divisor gives 18.71, which reads as a rendering bug rather than as a convention (H14.4 ⚑).
- The pass mark is **55**, with every scored attempt in the denominator (forced-submit zeros
  included). E14 renders the stored rate and never re-applies the threshold.
- The deciles are drawn as stored. The chart never re-buckets, so the bars and the stat cards
  above them cannot disagree.

**The two components the column does not store.** `ExecutionStats` keeps average, median, σ,
min, max, pass *rate* and the distribution; the wire shape also carries `count` and
`passCount`. `server.features.results.FrozenStatistics` reconstitutes those two, in one place,
with tests:

- `count` = the sum of the deciles. The distribution is one bucket per scored attempt by
  construction, so its total **is** the population the other figures were computed over.
  Counting the grade rows instead would be a second source of truth and would drift the moment
  a grade was added after the freeze.
- `passCount` = `round(passRate × count)`. This is arithmetic on two stored numbers, not a
  recomputation: re-applying the pass mark to the rows travelling beside it is the forbidden
  move. For 4821 this gives 7 of 8, which is what the acceptance table expects to read as
  "7 of 8 (87.5%)".

**A malformed column is a calm screen and a loud log.** A stored record whose distribution is
not ten buckets, whose population is zero, or whose mean is outside 0..100 is answered as
*absent* — the same "grading is not finished" state — with an ERROR line naming the execution.

## Error codes

`VALIDATION` malformed payload · `NOT_FOUND` unknown execution, an execution whose exam the
caller did not write, or a cancelled one (all three indistinguishable on purpose) ·
`FORBIDDEN` role gate · `UNAUTHORIZED` no session.

There is no `CONFLICT` and no `INTERNAL` path of its own: this feature writes nothing.

## What is deliberately absent

- **No writes.** No verb here changes a grade, a comment or a statistic. Overriding and
  approving are E12's verbs, with E12's gates.
- **No push.** See above.
- **No per-question data.** The marked paper is `GRADE_REVIEW_GET`'s job and has its own gate.
- **No cross-execution comparison.** That is F9.4 / E15's report engine, and its dimension
  strategies will consume `ResultStatistics` rather than redefine it.
- **No pagination.** School-sized lists (§6).
