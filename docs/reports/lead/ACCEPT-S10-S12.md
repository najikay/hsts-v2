# Acceptance pre-walk — scenarios 10, 11 and 12

**Walked by:** lead pre-walk pass · **Date:** 2026-08-26 · **Worktree:** `hsts-acc2`, detached at `origin/main` (`6bff812`)
**Scenarios:** T-10 viewing exam results (5 cases) · T-11 viewing data, principal (4 cases) · T-12 viewing reports (5 cases)
**Deliverable:** the paste-ready **Actual** cell for each of the fourteen cases. `docs/ACCEPTANCE_TESTS.md` is **not** edited by this pass.

> **These three scenarios test the lead's own features** (E14, E15, E15.2, E15.3). They were
> audited on that footing: every claim a javadoc makes was checked against what the wire
> actually carries, and the four findings below are all against the lead's own code or the
> lead's own documents.

---

## 1. Method

The house "passed below the screen" method, as established by cases 9.4 and 9.5.

- **Harness:** `src/test/java/acceptance/AcceptanceHarness.java` — wipe (`WipeOrder`), load the
  real `SeedLoader` dataset over `SeedDataset.sections()` with the fixed anchor clock, assemble
  the production services **the way `HSTSServer.defaultRouter` assembles them**, drive everything
  through `MessageRouter.route`.
- **Assembled, not faked:** `TeacherResultsService` over `JpaTeacherResultsStore`;
  `ReportService` over `ReportEngine(reportStore, ReportStrategies.all())`; `DataBrowseService`
  over **the same** `JpaReportStore` instance, which is the wiring comment's whole point;
  `BankHandlers` and `BankReadHandlers` over the real `QuestionService` / `BankBrowseService`, so
  T-11's mutating-verb refusals come from production handlers.
- **Ids are looked up, never hardcoded.** `WipeOrder` issues `DELETE`, not `TRUNCATE`, so MySQL
  keeps its `AUTO_INCREMENT` counter across a reseed and "execution 4821 is row 1" is true only
  on a virgin schema. Every probe keys on the seed's own values — usernames and the 4-character
  join code.

**What this exercises:** the router, the authorization gates, the services, the repositories and
a real MySQL. **What it does not:** the OCSF socket layer above the router, and not a pixel of
JavaFX. Where a case is about rendering, that is said in its Actual cell rather than glossed.

### Verify

```
HSTS_TEST_SCHEMA=hsts_acc2 ./mvnw -o test -Dtest='acceptance.**'

Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Probe class | Tests |
|---|---|
| `acceptance/Scenario10TeacherResultsTest.java` | 20 |
| `acceptance/Scenario11PrincipalDataTest.java` | 15 |
| `acceptance/Scenario12ReportsTest.java` | 21 |
| **Total** | **56** |

### Summary of outcomes

| Scenario | Cases | Outcome |
|---|---|---|
| 10 — viewing exam results | 5 | 4 pass · **1 partial (10.2 — B-20)** |
| 11 — viewing data, principal | 4 | 4 pass |
| 12 — viewing reports | 5 | 4 pass · **1 partial (12.1 — B-22, seed not code)** |

Findings raised: **B-20** (medium, code) · **B-21** (low, docs) · **B-22** (medium, demo data)
· **B-23** (low, docs). Numbered from B-20; B-14..B-19 are reserved for the S6–S7 walker.

---

## 2. Scenario 10 — viewing exam results (T-10 · F9.2 · S-35)

### 10.1 — As `dana.cohen`, open Results

**Status:** ✅ **Bugs:** —

> **Passed, and S-35 is proven in both directions rather than one.** Driven through the
> production `TeacherResultsService` on `RESULTS_EXAMS_GET` against the reseeded database. Dana's
> answer is **three exams — `101101`, `101102`, `101201` — and two sittings**, both hanging off
> `101101`: the closed `4821` and the live `2075`, which is S-2's "the same exam taken out of the
> drawer twice" visible as two rows under one exam. `101102` and `101201` come back with an empty
> execution list rather than being dropped, so an exam that was never released keeps its place.
> **The other direction is the half that matters:** `avi.mizrahi`'s list is `202101` and its one
> sitting `7390`, and contains nothing of hers — the scope is a `WHERE` on `exams.author`, not a
> filter over a list she was already handed. `rina.barak`, a pure coordinator who has written
> nothing, gets an **empty answer, not an error**; `maya.levi` and `principal.avia` are both
> refused by the role gate with *"This action requires one of the roles [TEACHER, COORDINATOR]."*
> **One claim in the case text is not demonstrable on this dataset and is recorded rather than
> passed:** "including executions run by other teachers". SEED_CONTENT §9's `created_by` rule
> makes the releaser the author on all four executions, so `releasedByAnotherTeacher` is `false`
> on every row and the flag has nothing to be true about here. The behaviour itself is covered by
> `TeacherResultsServiceTest`, "⚑ a sitting run by another teacher still belongs to the exam's
> author (S-35)", on a fixture the seed does not contain. **Method:** service, repositories and
> MySQL; not the socket layer, not JavaFX.

### 10.2 — Open execution `4821`. Read the table.

**Status:** ⚠ **Bugs:** B-20

> **Partial — the rows are right and two of the three columns the case asks for do not exist.**
> `RESULTS_EXECUTION_GET` returns **eight rows for eight seeded students**, `participants` 8,
> `gradedCount` 8, `isFullyMarked` true, state `CLOSED`, exam "Midterm: Algebra" / course
> "Algebra". The effective scores are exactly §9.1's final column — **100, 90, 85, 75, 70, 60,
> 55, 45** — and `yael.azulay`'s override travels whole on the teacher path: auto **45**, final
> **55**, state `APPROVED`, with the stored reason *"Question 11011 has a correct solution with a
> sign error on the last line, so partial credit was given."* and the student comment *"A clear
> improvement on inequalities. Worth revising the domain of definition."* `ResultsCopy` renders
> her row's marker as **"Adjusted"**. **What is missing is submitted-versus-timed-out and solving
> time.** Not null — absent by shape: `StudentGradeRow`'s twelve components are `gradeId`,
> `studentId`, `studentName`, `autoScore`, `finalScore`, `effectiveScore`, `state`,
> `overrideReason`, `teacherComment`, `approvedAt`, `examName`, `courseCode`, and there is no
> component either fact could travel in. `omer.katz`, the seeded TIMED_OUT attempt, reads exactly
> like the seven submitted papers. **`GradeRepository.findResultRows` already selects
> `a.actualMinutes` into `StudentResultRow`, and `TeacherResultsService.toWire` drops it** — see
> B-20. Scope holds in both directions and does not leak: `avi.mizrahi` asking for `4821` and
> `avi.mizrahi` asking for id `999999` get **the same code and the same sentence**, so the verb
> is not a membership oracle; `dana.cohen` is likewise refused on `7390`; `maya.levi` is stopped
> by the role gate before any id is read.

### 10.3 — Switch to the histogram view

**Status:** ✅ **Bugs:** —

> **Passed, and the frozen-statistics contract is pinned rather than asserted.** Every figure on
> the wire is §9.1's frozen record: **count 8, mean 72.5, median 72.5, σ 17.5, min 45, max 100,
> passCount 7, passRate 0.875**, deciles `[0,0,0,0,1,1,1,2,1,2]` — six populated buckets summing
> to the population. **The wire figures ARE the stored column, component for component:** the
> probe reads `exam_executions.stats` straight out of the database in its own transaction and
> compares each component against what `RESULTS_EXECUTION_GET` served — mean, median, stddev,
> min, max, deciles and pass rate all identical, so nothing between the column and the screen
> re-derives anything. **σ is the population form and this is hand-checked against the rows the
> same answer carries:** the eight effective scores give a mean of exactly 72.5 and
> Σ(x−72.5)² = **2450**, so √(2450/8) = **17.5** exactly; the sample divisor would give ≈18.708
> and is not what the wire carries. The six stat cards read **Average 72.5 · Median 72.5 · Std
> deviation 17.5 · Min / max "45 to 100" · Pass rate "7 of 8 (87.5%)" · Participants 8**, with
> the σ card captioned "population sigma" and the pass card "pass mark 55" — the numerator is the
> stored `passCount`, not a threshold re-applied. The histogram has real data to draw: chartable,
> ten buckets consistent with the population, tallest bucket 2, ±1σ band **55.0 to 90.0**, and
> the caption `8 students · mean 72.5 · median 72.5 · σ 17.5`. **Method noted:** the chart is
> driven through `StatChartLogic` over the wire record, which is the headless logic the FX
> component delegates to; the drawn bars are a manual-pass item. The inconsistent-σ pinning case
> is `TeacherResultsServiceTest`, "⚑ the stored figures are served even when the rows say
> otherwise (H14.4)" — cited rather than re-run here, because proving it needs a deliberately
> corrupt stored record and this pass writes nothing.

### 10.4 — Hover a bar; toggle count ↔ percentage

**Status:** ✅ **Bugs:** —

> **Passed below the screen; the hover gesture itself is a manual-pass item.** The tooltip
> sentences are the production copy over the seeded record and carry all three things the case
> asks for: **`"90-100 · 2 students · 25%"`**, `"70-79 · 2 students · 25%"`,
> `"40-49 · 1 student · 12.5%"`, and an empty bucket reads `"0-9 · nobody"` rather than
> "0 students". The top bucket is labelled **90-100**, not 90-99, because it holds the perfect
> score. **The toggle needs no round trip and this is structural:** both scales are functions of
> the ten stored buckets already in hand — bucket 9 is `2.0` on the count axis and `25.0` on the
> percent axis, computed from the same record, and the percent tick labels carry `%`. Nothing on
> the percent path asks the server anything, which is what "switches the axis without a reload"
> means. **Recorded as UI-only:** that the pointer produces the tooltip and that the segmented
> control repaints without re-animating are rendering facts this pass cannot see;
> `TeacherResultsInteractionTest` and `StatChartLogicTest` cover the wiring, the pixels are for
> the manual pass.

### 10.5 — Open results for an execution with no attempts

**Status:** ✅ **Bugs:** B-21

> **Passed, after correcting the case's own actor — see B-21.** `5164` is exam `202201`
> (Databases Final), authored by `michal.sharon`, so `dana.cohen` asking for it is answered
> `NOT_FOUND` with *"That exam sitting is not available. You can see results for exams you wrote,
> including sittings run by other teachers."* — correct S-35 behaviour, and **not** the empty
> state the case is trying to show. Walked three ways instead. **(a)** Its own author opens
> `5164` and gets a proper empty state: no rows, participants 0, `hasStatistics` false,
> `statistics()` empty, state `SCHEDULED`, and the screen's own sentence **"Nobody sat this
> sitting"** — a state, not a blank panel and not a crash. **(b)** Dana's own attempt-free
> sitting `2075` (LIVE) behaves identically, which is the case as it was probably meant. **(c)**
> The neighbouring state is right too: `7390` is CLOSED with grading unfinished, and it opens
> with **eight rows and no statistics**, every row `AUTO` — grading-in-progress is a state rather
> than a refusal, which is the §4.1 dead-end rule holding for the most ordinary situation there
> is.

---

## 3. Scenario 11 — viewing data, principal (T-11 · F9.3 · S-7)

### 11.1 — Sign in as `principal.avia`. Open Data.

**Status:** ✅ **Bugs:** —

> **Passed, and school-wide is proven by contrast rather than by a row count.** Her Questions tab
> calls **`BANK_LIST`, unchanged and unwidened** — the verb she has held since E6 — and returns
> **all 40 seeded questions across all four courses (11, 12, 21, 22)**, every subject in the
> school. The contrast is what makes that mean something: `dana.cohen` on the **same verb with
> the same request** gets only courses 11 and 12 and a smaller total, because her scope is
> taught-plus-coordinated and the principal's is `reachesEveryCourse`. **No `DATA_QUESTIONS_GET`
> exists in the protocol** — asserted over `Verb.values()` — so the third tab really did need no
> verb of its own, and there is no second answer to a question that already had one.

### 11.2 — Browse exams and exam results

**Status:** ✅ **Bugs:** —

> **Passed, both tabs, and the contract's 6.2 omission is confirmed by shape.** `DATA_EXAMS_GET`
> returns **all six seeded exams ordered by display id** — `101101, 101102, 101201, 202101,
> 202102, 202201` — each with its course code and name, its author by **display name and no id**,
> its version count and its last-version timestamp. `101101` reads "Midterm: Algebra" / Algebra /
> Dana Cohen / **2 versions** / revised, and the catalogue names four different authors, which is
> what school-wide means for this tab. **Contract 6.2 holds structurally:** `DataExamRow`'s seven
> components are `displayId6`, `examName`, `courseCode`, `courseName`, `authorName`, `versions`,
> `lastVersionAt` — there is **no component an approval status could travel in**, so this is a
> shape fact rather than a value that happened to be null. `202102` is REJECTED in the seed and
> appears in her catalogue as "Collections Quiz" by Tamar Shani with nothing saying so, which is
> the ruling working: she sees every consequence of approval and none of the judgement.
> `DATA_RESULTS_GET` returns **`4821` and only `4821`** — mean 72.5, median 72.5, σ 17.5, 8
> participants. `7390`, `5164` and `2075` are absent, and that is the shared `REPORTABLE` clause
> rather than an oversight: `7390` exists and its own author can open it (case 10.5c), it is
> merely CLOSED-and-unmarked. **The browse and the reports cannot disagree:** the sitting the
> browse lists and the sitting the `BY_COURSE` report compares carry the **byte-identical**
> `ResultStatistics` record, which is what sharing one `JpaReportStore` and one
> `ReportEngine.toRows` buys.

### 11.3 — Look for any create / edit / delete / approve control anywhere in her shell

**Status:** ✅ **Bugs:** —

> **Passed, and structurally rather than by looking.** "No button exists" is the weak form; the
> strong form is that the role is admitted nowhere that writes. A regex over every production
> source under `src/main/java/server` finds `Role.PRINCIPAL` inside **exactly four
> `Authorization.requireRole` calls, and no others**: `BankReadHandlers` (`requireRole(caller,
> Role.TEACHER, Role.COORDINATOR, Role.PRINCIPAL)`, the one gate its four read verbs share),
> `DataBrowseService` twice, and `ReportService` twice. Those gates guard **eight verbs, all
> reads** — `BANK_LIST`, `QUESTION_GET`, `QUESTION_VERSIONS`, `QUESTION_IMAGE_GET`,
> `DATA_EXAMS_GET`, `DATA_RESULTS_GET`, `REPORT_SUBJECTS_GET`, `REPORT_GET` — every one of them
> registered on the assembled router. A fifth hit on a verb that writes would fail this probe
> before anybody drew a button for it. Both report features additionally reach the database only
> through `ReportData`, whose every method is a read, so a write would mean adding a method to an
> interface whose javadoc says why it has none.

### 11.4 — Replay a mutating request with her session

**Status:** ✅ **Bugs:** —

> **Passed, and refused by the gate rather than by validation, which is the distinction that
> matters.** `QUESTION_CREATE`, `QUESTION_UPDATE` and `QUESTION_DELETE` were each replayed with
> her session through the production `BankHandlers` and each answered **`FORBIDDEN`** with *"This
> action requires one of the roles [TEACHER, COORDINATOR]."* **The payload was deliberately
> `null`.** A handler that validated its payload before checking the role would have answered
> `VALIDATION`, and that answer would have proved the gate had not run — which is the failure
> mode this case is written to catch, and the reason a malformed payload is the right probe
> rather than a well-formed one. **The reverse holds too:** `dana.cohen` (teacher),
> `michal.sharon` (coordinator) and `maya.levi` (student) are each refused on all four of her
> verbs with *"This action requires the PRINCIPAL role."* — a refusal, not an empty list, because
> an empty list would read as "the school has no exams". Re-reading the bank and the catalogue
> afterwards returns the same 40 questions and the same 6 exams: nothing this scenario did
> changed a row.

---

## 4. Scenario 12 — viewing reports (T-12 · F9.4 · S-25, S-37)

### 12.1 — Report comparing different exams of the same teacher

**Status:** ⚠ **Bugs:** B-22

> **Passed as a mechanism; the comparison itself has nothing to compare — see B-22.**
> `REPORT_SUBJECTS_GET` on `BY_TEACHER` lists **all five teachers**, each with its reportable
> count, and a teacher with nothing to report is **listed rather than hidden**. Exactly one of
> them has a count above zero: `dana.cohen`, with **1**. Her report comes back keyed
> `BY_TEACHER`, subject "Dana Cohen", with sitting **`4821`** carrying mean **72.5**, median
> **72.5** and the decile spread `[0,0,0,0,1,1,1,2,1,2]` — the three measures the case asks for,
> per execution. `avi.mizrahi` returns a **present answer with no rows** and
> `ReportSummary.EMPTY` — no invented mean of 0.0 for a teacher whose exams have never closed —
> rather than `NOT_FOUND`. **What cannot be shown is the comparison.** `REPORTABLE` is
> `CLOSED and stats is not null`, and on this dataset that selects exactly one sitting, so every
> dimension is a table of one row and "side by side" has no second column. B-22 records the fix.
> **On author-versus-releaser, method honesty:** `ByTeacherStrategy` asks
> `ReportGrouping.AUTHOR` and `executionsByAuthor`, both joining `exams.author_id`; the seed's
> §9 `created_by` rule makes `exams.author_id` and `exam_executions.created_by` **the same user
> on `4821`** (verified by direct query: both are `dana.cohen`), so the two readings coincide and
> **this pass cannot separate them**. The distinction is proven by `ReportEngineTest`, "the
> teacher is the exam's author, not whoever released the room (S-35)", on a fixture the seed does
> not have. The freeze ruling stands as written.

### 12.2 — Report comparing different exams of the same course

**Status:** ✅ **Bugs:** (B-22 applies)

> **Passed.** The `BY_COURSE` picker lists **all four courses by code — 11, 12, 21, 22** — with
> Algebra the only one carrying a sitting. The Algebra report is subject "Algebra", row `4821`,
> and the **same three measures**: mean 72.5, median 72.5, σ 17.5. The negative is right too:
> course 21 ("Object oriented programming in Java") returns a present, empty answer — its only
> sitting, `7390`, is closed but unmarked, so there is genuinely nothing to compare and the
> screen says so rather than erroring.

### 12.3 — Report comparing different exams of the same student

**Status:** ✅ **Bugs:** (B-22 applies)

> **Passed, and the "no per-student scores" rule holds by shape.** The `BY_STUDENT` picker lists
> **all twelve students**, and exactly the **eight who sat `4821`** carry a non-zero count —
> `lior.gabay`, `noa.friedman`, `shira.dahan`, `daniel.shapira`, `itay.regev`, `maya.levi`,
> `yael.azulay`, `omer.katz` — which is membership by *attempt*, so a student whose paper was
> never marked would still appear in her own history. `maya.levi`'s report tracks her across her
> executions and carries the **class** figures: her row is `4821` with mean **72.5** over a
> population of **8**. Maya scored 60; that number is nowhere in the answer, and there is no
> component in `ReportRow` for a personal score to travel in — her grades are F9.1's screen,
> gated on being her. `noam.peretz`, who has sat nothing reportable, gets a real empty answer
> under his own name.

### 12.4 — Cross-check any figure against the stored statistics for `4821`

**Status:** ✅ **Bugs:** —

> **Passed, and the cross-check is the strong form: identity, not agreement.** The
> `ResultStatistics` record in the principal's `BY_TEACHER` report is **equal to** the record
> `RESULTS_EXECUTION_GET` serves `dana.cohen` on her own results screen — one record, two
> readers, so a sitting cannot read one way on her histogram and another way in the principal's
> report. All three dimensions agree byte for byte on the same sitting. Mean **72.5**, median
> **72.5**, σ **17.5**, identical to §9.1 of the seed. **The pooled summary is hand-checked
> against the report's own numbers:** executions 1, participants 8, scored 8; the
> participant-weighted mean is Σ(72.5 × 8) / 8 = **72.5**; the pooled population σ is
> √(8(17.5² + 72.5²)/8 − 72.5²) = √306.25 = **17.5**, recomputed independently in the probe and
> equal to what the wire carries; min 45, max 100; `passCount` **7** and `passRate` **0.875**,
> both summed from the stored numerator with the mark of 55 applied nowhere; the pooled deciles
> equal the row's and sum to the population by construction. **The median is a band, not a
> number**, and the band is derivable by hand: the ⌈8/2⌉ = 4th lowest score, walked over
> `[0,0,0,0,1,1,1,2,1,2]` — cumulative 1, 2, 3, 5 — lands in **bucket 7, "70-79"**, which
> contains 72.5. **Method honesty on the flagship claim:** with one row the participant-weighted
> mean and the mean of the means coincide, so this pass **cannot** distinguish them; the identity
> is proven on multi-row fixtures by `ReportDtoTest`, "⚑ the mean is participant-weighted, and is
> not the mean of the means" and "⚑ sigma is the exact pooled population form, recovered from the
> stored ones". **On H15.2, likewise cited rather than re-proven:** the seed contains **zero**
> cancelled executions (verified by query), so the exclusion has nothing to exclude here; the
> three `ReportEngineTest` cases build a cancelled sitting *with statistics frozen on it*, which
> is the only fixture that can tell "cancelled is excluded" apart from "cancelled sittings have
> no statistics anyway".

### 12.5 — Defense question rehearsal: adding a new report dimension

**Status:** ✅ **Bugs:** —

> **Passed, and the answer is demonstrable in the code rather than promised in a document.** The
> engine serves exactly the list `ReportStrategies.all()` hands it — **three strategies** — and
> every `ReportDimension` answers through **the same two verbs with no branch above it**: each
> dimension's `REPORT_SUBJECTS_GET` returns its own dimension with a non-empty subject list and a
> `defaultSubject()` to open on, and an id naming no subject is `NOT_FOUND` on all three alike.
> **The extensibility claim is structural and is cited rather than re-proven, deliberately:**
> `ReportEngineExtensibilityTest` drives a real `ReportEngine` over a **fourth strategy that
> exists only inside that test file** — subjects, rows, frozen figures and a pooled summary, with
> the three shipped dimensions still working beside it — and separately reads `ReportEngine.java`
> and fails if any of the three dimension constants or any of the three strategy class names
> appears in it. Between them, "a new report type is a new Strategy class and a menu entry" stops
> being a claim. The concrete cost is REPORTS contract §7: one enum constant, one ~40-line
> strategy, one line in `ReportStrategies.all()`, one query and one `ReportGrouping` constant —
> engine, DTOs, handlers, summary arithmetic, screen, CSS and server assembly all untouched. The
> "sittings she ran" reading is exactly that fourth strategy, keyed on `created_by`, and the
> freeze already rules it out of phase 1 — which makes it the best answer to give if the question
> is asked, because it is a decision on record rather than an omission.

---

## 5. Bug candidates — drafted, for the lead to accept, renumber or reject

Numbered from **B-20**; B-14..B-19 are reserved for the S6–S7 walker running in parallel.

| # | Found by | Severity | Status | What |
|---|---|---|---|---|
| **B-20** | case 10.2 | **Medium** | Open | **The teacher's results table cannot say whether a student ran out of time, or how long she took.** T-10.2 asks for "score, submitted vs timed out, solving time"; only the score reaches the wire. This is a shape fact, not a null: `StudentGradeRow` has twelve components and none of them is a solving time or an attempt status, and the table's columns are Student / Score / Auto / Final / State / Adjusted. **The data is already read and then thrown away:** `GradeRepository.findResultRows` selects `a.actualMinutes` into `StudentResultRow` — the projection's javadoc documents it as "recorded solving time (S-19)" — and `TeacherResultsService.toWire(StudentResultRow)` maps ten of its components onto the wire and drops that one. `actualMinutes` has **no reader anywhere on the E14 path**; the only consumers of the name are the monitor and the checked form. Attempt status (`SUBMITTED` / `TIMED_OUT`) is never selected at all. The visible consequence on the seed: `omer.katz`'s timed-out 45 reads exactly like the seven submitted papers, and the one attempt in the whole dataset that distinguishes "did not finish" from "did badly" is indistinguishable on the screen built to show it. **Why every test stayed green:** `TeacherResultsServiceTest` asserts what the record carries, and the record carries what it was built to carry — this is B-3's shape (a field readable but never written) turned around, a field read but never delivered. **Fix:** two components appended to `StudentGradeRow` and `a.status` added to the query — additive under the RESULTS contract, one mapping line, two table columns. Contract note: `ExamAttempt.status` would need a wire enum, or the boolean "timed out" alone, which is all the case asks for. |
| **B-21** | case 10.5 | Low (docs) | Open | **Case 10.5 names an execution its actor cannot open.** The case reads "Open results for an execution with no attempts (`5164`, scheduled)" inside scenario 10, whose actor is `dana.cohen`. `5164` is a sitting of exam `202201`, **authored by `michal.sharon`**, so S-35 correctly answers Dana `NOT_FOUND` and the empty state the case exists to exercise is never reached. Two correct rewrites, either is fine: *"As `michal.sharon`, open results for `5164`"*, or *"As `dana.cohen`, open `2075` — her own sitting with no attempts"*. Both were walked and both produce the intended empty state. Not a code defect: the server is behaving as specified, and it is worth fixing before the demo only because a walker following the script verbatim will see a refusal and think something is broken. |
| **B-22** | cases 12.1, 12.2, 12.3 | **Medium (demo data)** | Open | **The seed offers exactly one reportable sitting, so no report can be a comparison.** `REPORTABLE` is `status = CLOSED and stats is not null`; of the four executions, `4821` qualifies, `7390` is CLOSED but every grade is `AUTO` by design (it is T-8.2's fixture), `5164` is SCHEDULED and `2075` is LIVE. Every dimension therefore returns a **one-row table**, and T-12's "compared side by side", the per-execution decile comparison, and the entire participant-weighted / pooled-σ summary arithmetic are **unobservable on the demo dataset** — the summary of one row is that row. This is not a code defect and the arithmetic is proven by unit fixtures, but it is a defense-day exposure: the screen that exists to compare will be demonstrated comparing one thing. **Two fixes, and the first is free.** (a) **Sequence the demo so T-8.2 runs before T-12**: approving `7390`'s eight grades freezes its statistics live, and the reports gain a second row *in front of the examiner*. SEED_CONTENT §9.2 already designed that spread to be "deliberately unlike execution 1's" — 30, 40, 55, 60, 70, 75, 85, 100 against 45, 55, 60, 70, 75, 85, 90, 100, two students below the pass mark instead of one — so the two rows cannot be mistaken for copies and the pooled mean genuinely differs from the mean of the means. (b) Seed a fifth closed-and-frozen execution, which costs a seed change and a document change. **(a) is recommended:** it costs nothing, and it turns two scenarios into one story. |
| **B-23** | cases 10.1, 11.2 | Low (docs) | Open | **`SEED_CONTENT.md` still describes a Hebrew dataset that the wave-1 translation removed.** §3 states "`full_name` is Hebrew throughout — the school is Israeli and RTL must round-trip in every screen that shows a name", and §7 says "Algebra and Calculus are Hebrew (RTL must be proven)". The loader seeds **no Hebrew at all**: `UsersSection` has "Dana Cohen", `ExamsSection` has "Midterm: Algebra", and `QuestionBankSection` and `SubjectsSection` contain zero Hebrew codepoints. The translation was a deliberate lead decision — `docs/reports/lead/WAVE1.md` §33 says so plainly ("Nothing anywhere in the demo shows Hebrew"), and §W1.8 records that `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip` was rewritten to write its own Hebrew sample rather than read the seed's, precisely because the seed no longer has one. **The defect is that SEED_CONTENT §3 and §7 were not updated in that lockstep**, so the document asserts a property of the loaded database that is no longer true. Cost of the mistake in this pass: two assertions were written against Hebrew names from the document and failed on the first compile. Fix: correct §3 and §7 to record the wave-1 translation and point at `WAVE1.md` for the RTL evidence that replaced it. |

### Not bugs, recorded so they are not re-investigated

- **`releasedByAnotherTeacher` is `false` on every seeded row, and that is the seed, not the
  flag.** SEED_CONTENT §9's `created_by` rule makes the releasing teacher the exam's author on
  all four executions, so S-35's "including sittings run by other teachers" has nothing to be
  true about on this dataset. `TeacherResultsServiceTest` covers it on a fixture that does.
- **The seed contains no cancelled execution**, so H15.2's exclusion is inert here. The three
  `ReportEngineTest` cases build a cancelled sitting *with frozen statistics on it*, which is the
  only fixture that separates "excluded" from "had nothing anyway".
- **`ReportEngineExtensibilityTest` was cited, not re-run in a new form**, per the brief. It
  already does the strongest available thing: a fourth strategy living only inside the test file,
  plus a source read that fails if `ReportEngine.java` names any dimension.
- **`5164` answering `NOT_FOUND` to `dana.cohen` is correct**, not a scope bug. See B-21 — the
  case text is what needs the edit.

---

## 6. Files this pass added

| File | What |
|---|---|
| `src/test/java/acceptance/AcceptanceHarness.java` | wipe + `SeedLoader` + the `defaultRouter` assembly + `MessageRouter.route`, once per class |
| `src/test/java/acceptance/Scenario10TeacherResultsTest.java` | 20 probes, cases 10.1–10.5 |
| `src/test/java/acceptance/Scenario11PrincipalDataTest.java` | 15 probes, cases 11.1–11.4 |
| `src/test/java/acceptance/Scenario12ReportsTest.java` | 21 probes, cases 12.1–12.5 |

`docs/ACCEPTANCE_TESTS.md` is untouched, per the brief. Nothing in this worktree is committed.
