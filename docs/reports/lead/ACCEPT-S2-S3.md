# Acceptance scenarios 2 and 3, walked below the screen

> *Transcribed into `main` on 2026-08-26 by the acceptance-fixes batch, so the evidence travels
> with `ACCEPTANCE_TESTS.md`. The pre-walk worktree it was written in (`hsts-acc1`) was reused for
> the scenarios 6–7 pass before this file had been committed, so this is a copy of the report as
> the fixes batch read it, not a fresh export. The `B-n` numbers below are the report's own drafts;
> the canonical register in `ACCEPTANCE_TESTS.md` renumbers them **B-7 → B-7**, **B-8 → B-8**,
> **B-9 → B-9** (this report's numbering happened to survive unchanged; the scenarios 4–5 report's
> did not).*

**Run:** 2026-08-25 · **Scope:** `docs/ACCEPTANCE_TESTS.md` §2 (T-2, question bank) and §3 (T-3,
exam building) · **Method:** Member B's "passed below the screen", the one 9.4 and 9.5 set.

This report proposes the `Actual` cell text for all seventeen cases. **It does not edit
`ACCEPTANCE_TESTS.md`** — the lead folds the cells in one pass, to keep the table out of a
cross-worktree conflict. Three `B-n` candidates are drafted at the bottom in the register's own
format, and two items are raised for a ruling rather than filed as bugs.

## How to reproduce

```bash
export JAVA_HOME=<jdk21>
export HSTS_TEST_SCHEMA=hsts_acc1          # schema isolation; parallel builds share the machine
./mvnw -o test -Dtest='acceptance.**' -Djacoco.skip=true
```

Two probe classes, new in this worktree, nothing else changed:

| File | What it walks |
|---|---|
| `src/test/java/acceptance/SeededProbeBase.java` | The rig: loads the seed, assembles the production services, puts a real router in front |
| `src/test/java/acceptance/Scenario2QuestionBankProbe.java` | Cases 2.1 – 2.8, in the table's order |
| `src/test/java/acceptance/Scenario3ExamBuildingProbe.java` | Cases 3.1 – 3.9, plus submit-for-approval |

Last run: **18 tests, 0 failures** (`Scenario2QuestionBankProbe` 8, `Scenario3ExamBuildingProbe`
10). Every number and every sentence quoted below was printed by a probe on that run; nothing in
this report is derived by hand.

## Method, and what it does not prove

Each probe sends the same `Message` the client sends, through the real `MessageRouter`, into the
real `BankHandlers` / `BankReadHandlers` / `ExamHandlers`, the real `QuestionService`,
`BankBrowseService`, `ExamService`, `AutoComposer` and `ExamValidator`, the real repositories, and
a real MySQL carrying the E2.15 seed. The seed is loaded the way `SeedMain --reseed` loads it —
`WipeOrder.wipe` then `SeedLoader` over `SeedDataset.sections()` — which is what
`SeedLoadedTestBase` does for the seed's own contract tests. Every load reported
`outcome=LOADED totalRows=375`.

**It does not go through the socket.** No OCSF connection, no serialization of the payload, no
session lookup off a `ConnectionToClient`. That is the boundary 9.4 drew, drawn here for the same
reason: every rule these cases are about lives below the socket. The hop above is covered by
`MessageRouterTest` (which exercises `handle` and the rule that identity comes from the session
bound to the socket and nothing else), `MessageRouterFuzzTest` and `ProtocolLoopbackTest`.

**Nothing here is a screen.** Every claim a case makes about rendering — a read-only id field, a
single-select radio control, a dropdown's contents, a confirmation dialog, a live points
indicator, lazy image loading — is recorded as *screen render at the manual pass*, exactly as 9.5
did. Where a screen claim has something underneath it, the something is probed and said so.

**Where a case needed state from an earlier case, it was built through the production write
path**, never by SQL: 2.5 reads the version 2.4 wrote, 2.8 deletes the question 2.1 created, and
3.x submits the draft 3.3 saved.

---

## Scenario 2 — Question bank editing (T-2)

### 2.1 — create a question · ✅ pass (below the screen)

> **Passed below the screen.** Run through the production `QUESTION_CREATE` against the reseeded
> database (375 rows). The server assigned **`11012`** — five digits, `11` + serial `012`,
> continuing the seed's eleven Algebra questions rather than restarting, which is S-8's whole
> claim — and answered `versionNo=1 latestVersionNo=1`, `author=Dana Cohen`, `hasImage=true`,
> `course=11 (Algebra)`. The Algebra question count went **11 → 12**. Re-read in a second
> transaction through `QUESTION_GET`, the stem, the four answers, `correctAnswer=2`, the topic and
> the difficulty all came back as submitted, Hebrew intact through the `utf8mb4` columns; the
> illustration came back from `QUESTION_IMAGE_GET` as `image/png, 67 bytes`. **The id being
> read-only on the form is a screen claim**: below it, the id is not a field a caller can set at
> all — `QuestionDraft` carries no id and the serial is allocated server-side under the course's
> row lock. Screen render at the manual pass.

Evidence:

```
[ACC 2.1] assigned displayId5=11012 course=11 (Algebra) versionNo=1 latestVersionNo=1 hasImage=true author=Dana Cohen createdAt=2026-08-25T09:00:00Z
[ACC 2.1] Algebra question count 11 -> 12
[ACC 2.1] QUESTION_GET re-read: text="כמה פתרונות יש למשוואה x² + 1 = 0 בממשיים?" answers=[אפס, אחד, שניים, אינסוף] correctAnswer=2 topic=Quadratic functions difficulty=MEDIUM
[ACC 2.1] QUESTION_IMAGE_GET v1: contentType=image/png bytes=67
```

### 2.2 — the three refusals · ✅ pass (below the screen)

> **Passed below the screen, four probes.** Each malformed question was refused by the production
> validator with `VALIDATION` and a specific sentence, not a generic one. Two identical answers:
> *"Answers 1 and 2 are the same. Two identical answers make the correct one ambiguous, so change
> one of them. They have to differ by more than spacing or hyphens."* No answer marked correct
> (`correctAnswer=0`): *"Mark exactly one of the four answers as the correct one."* — and the same
> sentence for `correctAnswer=5`, which is the out-of-range half of the same rule. **"Two marked
> correct" could not be probed because it cannot be expressed**: `QuestionDraft.correctAnswer` is
> a single 1-based int (C-8), so there is no payload that says two, and the single-select control
> the case asks for is therefore a screen claim over a wire that already forbids the state.
> Distinctness holds on Hebrew as well as Latin — two answers differing only in a final form
> (`מים` / `מימ`) were refused, which is the pair the `utf8mb4_unicode_ci` collation folds.
> Screen render at the manual pass.

Evidence: see the four `[ACC 2.2a–e]` lines. Note also `[ACC 2.2f]`, which is where **B-7**
starts — see the findings below.

### 2.3 — the course filter · ✅ pass (below the screen)

> **Passed below the screen, and this is the case worth not downgrading to a UI check.** The
> production scope function `BankBrowseService.reachableCourseCodes` answered `[11, 12]` for
> `dana.cohen` — Algebra and Calculus, the two she teaches, and nothing else. The dropdown's
> contents are a screen claim; what was verified underneath is stronger than the dropdown:
> replaying `QUESTION_GET` for `21001`, a Java question, was refused **server-side** with
> `NOT_FOUND` and the deliberately indistinguishable sentence *"That question is not in your bank.
> It may have been deleted, or it may belong to a course you do not teach…"*, and `BANK_LIST`
> asking explicitly for `courseCode=21` answered `totalRows=0` rather than an error — the filter
> intersects with her scope instead of trusting it, so naming somebody else's course discloses
> nothing about it. Her unfiltered browse returned `totalRows=21` drawn from courses `[11, 12]`
> only. Screen render at the manual pass.

```
[ACC 2.3] reachableCourseCodes(dana.cohen) = [11, 12]
[ACC 2.3] QUESTION_GET 21001 (Java, not hers) -> NOT_FOUND "That question is not in your bank. …"
[ACC 2.3] BANK_LIST courseCode=21 as dana.cohen -> totalRows=0 rows=0
[ACC 2.3] BANK_LIST with no filter -> totalRows=21 distinct courses in the page = [11, 12]
```

### 2.4 — edit 11005, then its version history · ❌ FAIL → **B-7**

> **Failed, and the failure is the finding.** The case's own step — open `11005`, change its text,
> save — is refused. Sending the stored question back with only the stem changed, which is what an
> editor bound to the stored row sends, answered `VALIDATION`: *"Answers 1 and 3 are the same.
> Two identical answers make the correct one ambiguous, so change one of them."* The two answers
> are `2, 3` and `-2, -3`. `QuestionValidator.sameAnswer("2, 3", "-2, -3")` returns **true**;
> asked the same question, MySQL under the column collation returns **0**, and the row is sitting
> in the bank, so `ck_question_versions_distinct` accepted exactly what the validator now refuses.
> **Five seeded questions cannot be written back at all** — `11005`, `11006`, `11008`, `12005`,
> `12007` — every one of them a pair that differs only by a minus sign or a bracket. Filed as
> **B-7**. The rest of the case was then walked on reworded answers, and the versioning half is
> sound: the edit wrote **v3**, the history came back newest-first as `[v3, v2, v1]` with each
> version's own stem and author, and the bank list row moved to `latestVersionNo=3` while v1 and
> v2 stayed readable.

```
[ACC 2.4] stored 11005 v2 answers=[2, 3, 1, 6, -2, -3, 0, 5] correctAnswer=1
[ACC 2.4] QUESTION_UPDATE base=2, stem changed, answers unchanged -> VALIDATION "Answers 1 and 3 are the same. …"
[ACC 2.4] QuestionValidator.sameAnswer("2, 3", "-2, -3") = true
[ACC 2.4] MySQL under the column collation says '2, 3' = '-2, -3' is 0, and the seed's own row is stored, so ck_question_versions_distinct accepted the four answers the validator now refuses
[ACC 2.4] seeded questions whose stored answers the production validator refuses (5)
[ACC 2.4]     11005 v2 field=answers[3] answers=[2, 3, 1, 6, -2, -3, 0, 5] -> "Answers 1 and 3 are the same. …"
[ACC 2.4]     11006 v1 field=answers[2] answers=[(-3, 4), (3, 4), (3, -4), (4, 3)] -> "Answers 1 and 2 are the same. …"
[ACC 2.4]     11008 v1 field=answers[4] answers=[3, 2, -1, 1] -> "Answers 3 and 4 are the same. …"
[ACC 2.4]     12005 v1 field=answers[4] answers=[-sin(x), tan(x), -cos(x), cos(x)] -> "Answers 3 and 4 are the same. …"
[ACC 2.4]     12007 v1 field=answers[2] answers=[x = -1, x = 1, x = 0, x = 3] -> "Answers 1 and 2 are the same. …"
[ACC 2.4] QUESTION_UPDATE with the answers reworded so they pass -> OK QuestionDetail
[ACC 2.4] history after the edit: 11005 [v3 by Dana Cohen, v2 by Dana Cohen, v1 by Dana Cohen]
[ACC 2.4] bank list row for 11005 shows latestVersionNo=3 text="Find the roots of x² - 5x + 6 = 0 (edited by acceptance case 2.4)"
```

### 2.5 — the released exam is pinned · ✅ pass

> **Passed, and it is the cleanest demonstration of C-2 / S-14 in the suite.** After 2.4 pushed
> `11005` to version 3, `EXAM_VERSION_GET` on exam `101101` **v2** — the APPROVED, released
> version — still answered `pinnedVersionNo=1` against `latestVersionNo=3`, at 15 points, third in
> the paper. The pinned stem read *"What are the roots of x² - 5x + 6 = 0?"*, which is v1's
> wording, not v2's and not the edit's. The released exam did not follow the bank forward; the two
> numbers in one row, 1 and 3, are the whole claim.

```
[ACC 2.5] EXAM_VERSION_GET 101101 v2: state=APPROVED name="Midterm: Algebra" questions=7 author=Dana Cohen
[ACC 2.5] 11005 in that exam: pinnedVersionNo=1 latestVersionNo=3 points=15 ord=3
[ACC 2.5] pinned text = "What are the roots of x² - 5x + 6 = 0?"
```

*(The exam's stored name is "Midterm: Algebra" rather than the seed document's "Midterm — Algebra"
— the loader replaces em dashes per PRD §4.1 and says so. Not a defect; noted so nobody re-finds
it.)*

### 2.6 — browse and filter · ⚠ partial (filters pass; the illustrated half is unwalkable) → **B-8**

> **Filters passed below the screen; the illustrated-list half could not be walked, and that is
> B-8.** Every filter combination narrows correctly and they compose: course `11` alone →
> `totalRows=12`; `+ topic='Quadratic functions'` → **5** (`11005, 11006, 11007, 11008, 11012`);
> `+ difficulty=EASY` → **2** (`11005, 11006`); free text `search='parabola'` → **3** (`11006,
> 11007, 11008`); `topic='Inequalities' + difficulty=HARD` → **1** (`11011`). Paging is
> server-side and clamped: `page=0 size=5` returned 5 rows of `totalRows=12` over `totalPages=3`.
> **Lazy image loading is structural rather than a screen behaviour and was verified as such**:
> `BankQuestionRow` carries nine components — `[displayId5, courseCode, courseName, text, topic,
> difficulty, latestVersionNo, hasImage, lastVersionAt]` — a *flag* and no bytes, so a browse
> cannot move an image however long the list is; a picture is a separate `QUESTION_IMAGE_GET`
> addressed by version. **What could not be walked is "scroll a list with illustrated
> questions":** the seeded bank holds **45 question versions and not one image**. `11005` v1,
> which the seed document marks `img=yes`, answered `NOT_FOUND` — *"There is no illustration on
> that version of the question."* The one image in the database is the one case 2.1 uploaded, and
> fetching it worked (`image/png, 67 bytes`). The list-and-detail layout is a screen claim. Screen
> render at the manual pass.

```
[ACC 2.6] course=11 -> totalRows=12
[ACC 2.6] course=11 + topic='Quadratic functions' -> totalRows=5 ids=[11005, 11006, 11007, 11008, 11012]
[ACC 2.6] + difficulty=EASY -> totalRows=2 ids=[11005, 11006]
[ACC 2.6] course=11 + search='parabola' -> totalRows=3 ids=[11006, 11007, 11008]
[ACC 2.6] course=11 + topic='Inequalities' + difficulty=HARD -> totalRows=1 ids=[11011]
[ACC 2.6] paging: page=0 size=5 -> rows=5 totalRows=12 totalPages=3
[ACC 2.6] question_versions rows in the whole seeded bank: 45, of which carry an image: 1
[ACC 2.6] QUESTION_IMAGE_GET 11005 v1, which the seed document marks img=yes -> NOT_FOUND "There is no illustration on that version of the question. …"
[ACC 2.6] QUESTION_IMAGE_GET 11012 v1 (the illustrated question 2.1 created) -> image/png, 67 bytes
```

### 2.7 — delete blocked by a referencing exam · ✅ pass (below the screen)

> **Passed below the screen.** `QUESTION_DELETE` on `11001` answered **`OK`**, not an error —
> being told which exams use a question is a successful answer to "may I delete this" — carrying
> `deleted=false` and one blocking exam, **`101101 "Midterm: Algebra"`**, named by display id and
> by name, which is what the dialog has to render. `11001` sits in *two* versions of that exam
> (v1 and v2, seed §8.1) and is reported once, by exam rather than by version, which is the right
> granularity for a sentence a teacher reads. The question was still in the bank afterwards
> (`v1 of 1`). The dialog itself is a screen claim; the data behind it is proven. Worth recording
> that **this block has no database backstop** — soft delete is an `UPDATE` and no foreign key
> fires on an update — so this refusal is the service query working, not the schema helping.
> Screen render at the manual pass.

```
[ACC 2.7] QUESTION_DELETE 11001 (base v1) -> OK DeleteOutcome
[ACC 2.7] deleted=false blockingExams=1
[ACC 2.7]   blocked by 101101 "Midterm: Algebra"
[ACC 2.7] 11001 is still in the bank afterwards: v1 of 1
```

### 2.8 — delete an unreferenced question · ✅ pass (below the screen), with one wording question

> **Passed below the screen.** Deleting `11012`, the question 2.1 created and no exam references,
> answered `deleted=true` with no blocking exams. It then left the bank: the course-11 browse went
> **12 → 11 rows** and `11012` is absent from it; `QUESTION_GET` answers the same
> indistinguishable `NOT_FOUND` an unknown id gets. **The delete is soft**: `questions.deleted_at`
> is stamped (`2026-08-25T09:00:00Z`) and the version rows are untouched — **1 before, 1 after**.
> **And the serial is not reused**: the next question created in Algebra was assigned **`11013`**,
> with `11012` left spoken for. One thing the case's wording should be tightened on: *"version
> history preserved"* is true of the **store** and not of the **API** — `QUESTION_VERSIONS` on a
> soft-deleted question answers `NOT_FOUND` too, deliberately, because the bank contract's §6
> folds unknown, deleted and out-of-scope into one answer so display ids cannot be probed. Raised
> below for a ruling rather than filed as a bug. The confirm step is a screen claim; screen render
> at the manual pass.

```
[ACC 2.8] QUESTION_DELETE 11012 -> OK DeleteOutcome
[ACC 2.8] deleted=true blockingExams=0
[ACC 2.8] after the delete, 11012 in the bank list: false (course 11 totalRows=11)
[ACC 2.8] QUESTION_GET 11012 -> NOT_FOUND "That question is not in your bank. …"
[ACC 2.8] QUESTION_VERSIONS 11012 -> NOT_FOUND "That question is not in your bank. …"
[ACC 2.8] in the database: question_versions rows 1 -> 1, questions.deleted_at = 2026-08-25T09:00:00Z
[ACC 2.8] next created question after deleting 11012 is 11013 (the deleted serial is not reused)
```

---

## Scenario 3 — Exam building (T-3)

**One structural note the table does not carry, and every case in it inherits the consequence:**
`EXAM_CREATE` takes the metadata *and* the whole composition in one message. There is no stored
"metadata only" exam between 3.1 and 3.3 — the paper lives in the client until the points reach
100 and the whole thing is written once, because the points rule has no DDL backstop. So 3.1's
four fields, 3.2's blocked save and 3.3's accepted save are three answers from one verb.

### 3.1 — create an exam, four fields, server-assigned id · ✅ pass (below the screen)

> **Passed below the screen.** All four fields are accepted and each is checked by name: with the
> four fields present and no questions, `EXAM_CREATE` answered `VALIDATION` *"An exam needs at
> least one question. Add questions from the bank, then set their points."* — the metadata got
> through and the composition rule is what stopped it. A blank name answered *"Give the exam a
> name before saving it."*; `durationMinutes=600` answered *"An exam runs between 1 and 480
> minutes. Check the duration you typed."*, naming the ceiling rather than only the fact of being
> outside it. **The author is not a field she can send** — `ExamCreateRequest` carries no author
> and the id comes from the session — and the scope guard is real: the same create aimed at course
> 21 was refused `FORBIDDEN`, *"You do not teach course 21, so its question bank is not yours to
> change."* The id and the recorded author are observed on the write 3.3 completes:
> **`101103`**, six digits `10`+`11`+`03`, `author=Dana Cohen`. Screen render at the manual pass.

```
[ACC 3.1] EXAM_CREATE with the four fields and no questions -> VALIDATION "An exam needs at least one question. …"
[ACC 3.1] EXAM_CREATE with a blank name -> VALIDATION "Give the exam a name before saving it."
[ACC 3.1] EXAM_CREATE with duration=600 -> VALIDATION "An exam runs between 1 and 480 minutes. Check the duration you typed."
[ACC 3.1] EXAM_CREATE for course 21 as dana.cohen -> FORBIDDEN "You do not teach course 21, so its question bank is not yours to change. …"
```

### 3.2 — the points indicator, and a blocked save · ✅ pass (below the screen)

> **Passed below the screen: blocked, not warned, and the sentence says which way she is out.**
> Five questions totalling 96 were refused with *"The points add up to 96. Add 4 more to reach
> 100."*; the same five totalling 104 with *"The points add up to 104. Remove 4 to reach 100."*
> One question worth 0 points was refused separately — *"Question 1 is worth an impossible number
> of points. Each question is worth between 1 and 100."* — naming the position, which is the row
> she has to fix. **Blocked means nothing was stored**: Algebra exams in the database were **2
> before and 2 after** all three refused saves. The live running total is a screen claim, and the
> sentence it has to agree with is the one quoted here. Screen render at the manual pass.

```
[ACC 3.2] points totalling 96 -> VALIDATION "The points add up to 96. Add 4 more to reach 100."
[ACC 3.2] points totalling 104 -> VALIDATION "The points add up to 104. Remove 4 to reach 100."
[ACC 3.2] one question worth 0 points -> VALIDATION "Question 1 is worth an impossible number of points. Each question is worth between 1 and 100."
[ACC 3.2] Algebra exams in the database before/after the three refused saves: 2 / 2 - a refused save writes nothing
```

### 3.3 — exactly 100 saves; DRAFT in the drawer · ✅ pass

> **Passed.** Four questions at 25 points each were accepted and the exam came back read out of
> the database rather than echoed: **`101103`**, `versionNo=1`, `state=DRAFT`, `author=Dana
> Cohen`, duration 60, `lockVersion=0`, both texts stored verbatim. **The order she arranged is
> the order stored** — the pins were sent deliberately out of bank order and came back
> `ord=1 11009, ord=2 11001, ord=3 11006, ord=4 11003`, so "reorder them" survives the write.
> `EXAM_LIST` then showed it in her drawer as `v1 DRAFT, questionCount=4, duration=60`, beside her
> three seeded exams — four rows, `[101103, 101101, 101201, 101102]`.

```
[ACC 3.3] assigned displayId6=101103 course=11 (Algebra) versionNo=1 state=DRAFT author=Dana Cohen duration=60 lockVersion=0
[ACC 3.3] composition as stored (4)
[ACC 3.3]     ord=1 11009 v1 25pt / ord=2 11001 v1 25pt / ord=3 11006 v1 25pt / ord=4 11003 v1 25pt
[ACC 3.3] EXAM_LIST row: 101103 "Acceptance 3.3 - Algebra" latestVersionNo=1 -> v1 state=DRAFT questionCount=4 duration=60
[ACC 3.3] her drawer now holds 4 exams: [101103, 101101, 101201, 101102]
```

### 3.4 — auto-compose, 5 from one topic · ❌ FAIL as written → **B-9** (the feature is sound)

> **The case as written cannot pass on this seed, and the auto-composer is not the reason.** It
> asks for 5 questions from topic **"משוואות ליניאריות"**. No such topic exists: the Algebra
> topics in the seed are `Linear equations`, `Quadratic functions` and `Inequalities`, so the
> request answered a truthful shortfall — `requested=5 available=0`. Asked again for the topic the
> seed *does* hold, `Linear equations`, it answered `requested=5 available=4`: **that topic holds
> four questions and no Algebra topic in the seed holds five**, so the count in the case is
> unsatisfiable too. Filed as **B-9**; it is a defect in the case, not in the code. **The feature
> itself passed on three probes**: `Linear equations` × 4 proposed four questions at 25 points
> each; the mixed-difficulty grid (2 easy + 1 medium + 1 hard) proposed exactly that mix; and the
> whole course × 5 proposed five at 20 points each spanning three topics, drawing `11005` at
> **v2**, its latest. Every proposal already totals **100**, so it is savable in one click.
> **It is editable before saving and it writes nothing**: exams in the database were **7 before
> and 7 after five auto-compose calls**, and the last proposal was then reversed, re-pointed to
> 40/15/15/15/15 and sent on to `EXAM_CREATE`, which stored it as `101104 v1 DRAFT`.

```
[ACC 3.4a] topic 'משוואות ליניאריות' (the topic the case names), any x5 -> feasible=false questions=0 shortfalls=1
[ACC 3.4a]   shortfall topic=משוואות ליניאריות difficulty=null requested=5 available=0 missing=5
[ACC 3.4b] topic 'Linear equations' (the topic the seed holds), any x5 -> feasible=false questions=0 shortfalls=1
[ACC 3.4b]   shortfall topic=Linear equations difficulty=null requested=5 available=4 missing=1
[ACC 3.4c] topic 'Linear equations', any x4 -> feasible=true questions=4 shortfalls=0 … proposed points total = 100
[ACC 3.4d] topic 'Linear equations', 2 easy + 1 medium + 1 hard -> feasible=true questions=4 shortfalls=0 … proposed points total = 100
[ACC 3.4e] the whole Algebra course, any x5 -> feasible=true questions=5 shortfalls=0 … proposed points total = 100
[ACC 3.4e]   proposed ord=4 11005 v2 EASY topic='Quadratic functions' 20pt
[ACC 3.4] exams in the database before/after five auto-compose calls: 7 / 7 - EXAM_AUTO_COMPOSE writes nothing
[ACC 3.4] the proposal, reversed and re-pointed, saved as 101104 v1 DRAFT
```

**Two replacements that do pass, for whichever the lead prefers when rewriting the case:** *"5
questions from the whole Algebra course, mixed difficulty"* (3.4e), or *"4 questions from topic
`Linear equations`, mixed difficulty"* (3.4d). The second keeps the case's "from a topic" shape;
the first keeps the number 5.

### 3.5 — three Recursion questions · ✅ pass

> **Passed, and this is the thin-topic fixture doing exactly what it was built for.** As
> `avi.mizrahi`, asking for 3 questions from topic **Recursion** answered **`OK`** — an infeasible
> request is a successful answer, not an error, which is what keeps F3.3's report out from behind
> a red banner — carrying `feasible=false`, no proposal, and one shortfall row:
> `topic=Recursion difficulty=null requested=3 available=2 missing=1`. **The number is the one she
> could disprove and it holds**: the bank really does contain 2 live latest-version Recursion
> questions. **No exam was created** — Java exams were **2 before and 2 after** — and that is true
> by construction rather than by a rollback: nothing on this path inserts.

```
[ACC 3.5] as avi.mizrahi, topic 'Recursion', any x3 -> feasible=false questions=0 shortfalls=1
[ACC 3.5]   shortfall topic=Recursion difficulty=null requested=3 available=2 missing=1
[ACC 3.5] Java exams before/after: 2 / 2
[ACC 3.5] the bank really holds 2 live latest-version Recursion questions
```

### 3.6 — one HARD Recursion question · ✅ pass

> **Passed.** The same request narrowed to one **HARD** Recursion question answered `OK`,
> `feasible=false`, with the shortfall scoped to the difficulty rather than to the topic:
> `topic=Recursion difficulty=HARD requested=1 available=0 missing=1`. `available=0` is the
> specific thing the case asks to see — the topic is not empty, the *difficulty* is — and the
> report says so without an aggregate row pairing that demand with the topic's count of 2, which
> would have been a sentence she could disprove. Java exams **2 before, 2 after**.

```
[ACC 3.6] as avi.mizrahi, topic 'Recursion', hard x1 -> feasible=false questions=0 shortfalls=1
[ACC 3.6]   shortfall topic=Recursion difficulty=HARD requested=1 available=0 missing=1
```

### 3.7 — editing exam 101101 makes a new version · ✅ pass, plus an observation for the lead

> **Passed.** `EXAM_VERSION_REVISE` on `101101` **v2** (APPROVED) wrote **v3 as a DRAFT**, copying
> the metadata and all seven pinned questions forward at their points — including `11005` still
> pinned at **v1**, so a revision inherits the pin rather than re-resolving it. `rejectedReason`
> was deliberately not copied and came back empty. **The predecessors are retained**: v2 re-read
> as `APPROVED` with its seven questions, and v1 as `REJECTED` still carrying the coordinator's
> reason, *"Only five questions for 60 minutes, and each one is worth too much. A wider spread is
> needed."* Revising the new draft is refused: *"This version is still a draft, so there is
> nothing to revise. Edit it and save."* **One thing observed beyond the case, for the open ruling
> `ExamService.revise` already flags in its own comment:** the DRAFT guard is on the *addressed*
> version only, so revising v1 while v3 was a draft was accepted and produced **v4**, leaving
> `101101` with two open drafts (v4 DRAFT, v3 DRAFT, v2 APPROVED, v1 REJECTED). Raised below.

```
[ACC 3.7] EXAM_VERSION_REVISE on v2 -> OK ExamComposition
[ACC 3.7] new version: v3 state=DRAFT name="Midterm: Algebra" questions=7 rejectedReason=
[ACC 3.7]     ord=3 11005 v1 15pt
[ACC 3.7] 101101 v2 after the revise: state=APPROVED questions=7
[ACC 3.7] 101101 v1 is still there: state=REJECTED rejectedReason="Only five questions for 60 minutes, and each one is worth too much. A wider spread is needed."
[ACC 3.7] EXAM_VERSION_REVISE on the new DRAFT -> CONFLICT "This version is still a draft, so there is nothing to revise. Edit it and save."
[ACC 3.7] EXAM_VERSION_REVISE on v1 (REJECTED) while v3 is a DRAFT -> OK ExamComposition
[ACC 3.7] 101101's versions after both revises (4): v4 DRAFT, v3 DRAFT, v2 APPROVED, v1 REJECTED
```

### 3.8 — a question in more than one exam · ✅ pass

> **Passed.** Question `11009` was already carried by four exams — read out of the delete block
> rather than out of SQL, which is the same list a teacher would be shown: `101101 "Midterm:
> Algebra"`, `101102 "Quiz: Inequalities"`, and the two this walk created. Adding it to a fifth,
> `101105`, was accepted with no complaint, and the delete block then named **five**. Nothing in
> the composition rules is keyed on a question being unused; the only "twice" rule is 3.9's, and
> it is scoped to one exam version.

```
[ACC 3.8] exams already using 11009 (read out of the delete block) (4)
[ACC 3.8] EXAM_CREATE for a second exam containing 11009 -> OK ExamComposition
[ACC 3.8] created 101105 v1 DRAFT containing [11009, 11010]
[ACC 3.8] exams using 11009 after the second exam (5)
```

### 3.9 — the same question twice in one version · ✅ pass

> **Passed, three probes.** Pinning `11005` **v1** and `11005` **v2** into one exam version was
> refused with `VALIDATION`: *"Question 11005 is in this exam twice. An exam can use a question
> once, even through two versions of it. Remove one of them."* — the sentence names the question
> by the display id she sees on the bank row, which is the one thing the database's own
> `UNIQUE(exam_version_id, question_id)` cannot say. Pinning the same version twice is refused by
> the same rule with the same sentence, so the two shapes of the mistake are one answer. A third
> probe on the neighbouring rule: a Calculus question pinned into an Algebra exam was refused with
> *"Question 12001 belongs to a different course, so it cannot go in this exam."*

```
[ACC 3.9] 11005 has versions v1 (id 51) and v2 (id 87); latest is v2
[ACC 3.9] pinning 11005 v1 and 11005 v2 in one version -> VALIDATION "Question 11005 is in this exam twice. An exam can use a question once, even through two versions of it. Remove one of them."
[ACC 3.9] pinning 11005 v1 twice -> VALIDATION "Question 11005 is in this exam twice. …"
[ACC 3.9] pinning a Calculus question into an Algebra exam -> VALIDATION "Question 12001 belongs to a different course, so it cannot go in this exam. Remove it and pick one from this course."
```

### Supplementary — submit for approval (T-3's last step, no case of its own)

Not a numbered case in the table; walked because T-3 names it and scenario 4 depends on it.
`EXAM_SUBMIT` on the DRAFT from 3.3 moved `101103` to **PENDING**; a second submit was refused
*"Only a draft can be sent for approval. This version has been sent already."*; and editing the
submitted version was refused *"This version has been submitted already, so it cannot be edited.
Make a new version from it to keep working."* **The post-commit approval hook fires** — two
notifications were then held for `rina.barak`, the coordinator of subject 10, which is the thing
`ExamService.submitForApproval`'s javadoc says is dead if the hook is called from inside the
transaction. Suggest the lead add this as case **3.10** rather than leaving it to scenario 4.

```
[ACC 3.x] EXAM_SUBMIT on 101103 -> OK ExamComposition
[ACC 3.x] state is now PENDING (was DRAFT)
[ACC 3.x] EXAM_SUBMIT a second time -> CONFLICT "Only a draft can be sent for approval. This version has been sent already."
[ACC 3.x] EXAM_VERSION_SAVE on the submitted version -> CONFLICT "This version has been submitted already, so it cannot be edited. …"
[ACC 3.x] notifications now held for rina.barak, the coordinator of subject 10: 2
```

---

## Proposed Summary-table rows

| # | Scenario | Cases | Status |
|---|---|---|---|
| 2 | Question bank editing (עריכת מאגר שאלות) | 8 | ⚠ 6 passed below the screen, 1 partial (no illustrated seed rows — B-8), 1 failed — B-7 |
| 3 | Exam building (בניית מבחנים) | 9 | ⚠ 8 passed, 1 failed as written — B-9 (the case, not the code) |

---

## B-n candidates, drafted in the register's format

Paste into the `Bugs found` table at the bottom of `ACCEPTANCE_TESTS.md`. Numbers follow B-6.

| # | Found by | Severity | Status | What |
|---|---|---|---|---|
| B-7 | case 2.4 | **Medium** | Open | **A minus sign is invisible to the answer-distinctness rule, so a maths question whose distractors differ only by sign cannot be saved.** `QuestionValidator.sameAnswer` compares folded forms with a `Collator` at `PRIMARY` strength, and Java's default collation rules make punctuation and symbol characters — the hyphen-minus among them — *ignorable* at that strength. So `sameAnswer("1", "-1")` returns **true**, and so do `("2, 3", "-2, -3")`, `("cos(x)", "-cos(x)")` and `("(3, 4)", "(-3, 4)")`. Asked the same question, MySQL under `utf8mb4_unicode_ci` returns **0** — the values differ — so the validator is **stricter than `ck_question_versions_distinct`, the constraint its own javadoc says it stands in for**, in the direction that refuses questions the database would happily store. **The class argues that direction is the safe one** — "the worst case is a teacher told two confusingly similar answers are too similar" — and on this evidence that argument does not hold: sign-differing distractors are not a confusing edge case in a mathematics bank, they are the normal shape of one, and **five seeded questions cannot be written back through `QUESTION_UPDATE` at all** (`11005`, `11006`, `11008`, `12005`, `12007`). Case 2.4's own step, "open 11005, change its text, save", is refused; a teacher editing only the stem of one of those five meets a refusal about answers she did not touch, and the system is refusing to re-store rows it stored itself. `BankRoundTripIntegrationTest` already asserts the two verdicts match in **both** directions — the codebase's own test contract says stricter is a failure too — but no sign-differing pair is in its `@CsvSource`, which is why nothing caught it. **Suggested fix:** keep the folding that PRIMARY strength buys on Hebrew (final forms, niqqud, Yiddish digraphs — that half is right and was measured against MySQL), but stop letting sign and bracket characters be ignorable before the comparison; then add `1 / -1`, `2, 3 / -2, -3` and `cos(x) / -cos(x)` to the round-trip test's rows so the database branch pins the fix. **Not fixed here — reported only.** |
| B-8 | case 2.6 / case 2.1 | Low (demo content) | Open | **No question in the seeded bank carries an illustration**, so the "scroll a list with illustrated questions" half of case 2.6 cannot be walked and the demo bank has no pictures in it. The seed document marks **10** questions `img=yes` (PRD §5); the database after a reseed holds **45 question versions and 0 images**. This is deliberate and documented — `QuestionBankSection`'s javadoc says "Illustrations load as NULL… the bytes arrive later under `docs/seed/img/`", and keeps the flag in the data so the count stays assertable — but it has no owner and no ticket, and it is the kind of gap that is noticed on stage rather than in a build. The plumbing on both sides is proven working: a question created with a PNG round-tripped through `QUESTION_CREATE` → `QUESTION_IMAGE_GET` as `image/png, 67 bytes`, and `BankQuestionRow` carries a `hasImage` flag and no bytes, so the lazy-loading claim holds structurally. **Fix is ten small images under `docs/seed/img/` and the loader reading them**, which the loader is already shaped for. |
| B-9 | case 3.4 | Low (test case) | Open | **Case 3.4 names a topic the seed does not have and a count no Algebra topic can satisfy.** It asks for "5 questions from topic **משוואות ליניאריות**". The seed's Algebra topics are `Linear equations`, `Quadratic functions` and `Inequalities` (§7.1) — all English, per the 2026-08-23 English-everywhere ruling — so the request answers a shortfall of `requested=5 available=0`. Asked for `Linear equations` instead, it answers `requested=5 available=4`: that topic holds four questions, and **no** Algebra topic in the seed holds five. So the case cannot pass as written, and it would read on the day as the auto-composer failing when the auto-composer is answering correctly. Every case in this table is supposed to name the actual row (see "How to run these"); this one names a row that is not there. **Fix is to the case, not the code** — either "5 questions from the whole Algebra course, mixed difficulty", or "4 questions from topic `Linear equations`, mixed difficulty". Both were probed and both pass, proposing a composition already totalling 100 points. |

---

## Two items for a ruling, deliberately not filed as bugs

**1. "Version history preserved" after a soft delete — the store or the API?** (case 2.8.) The
rows are preserved: 1 version row before the delete, 1 after, with `deleted_at` stamped on the
question. But `QUESTION_VERSIONS` answers `NOT_FOUND` for a soft-deleted question, and that is not
an oversight — `BankBrowseService.readable` uses `findActiveByDisplayId` precisely so that
unknown, deleted and out-of-scope are one indistinguishable answer and display ids cannot be
probed (bank contract §6). The two readings of the case are "the history survives in the database"
(true, and what the *serial is not reused* clause is really about) and "a teacher can still open
the history of a deleted question" (false by design). **If the second is what T-2.8 means, it is a
contract change** — a staff-only history read that admits deleted questions — and it is not a bug
to be quietly fixed under an acceptance walk. Recommend tightening the case's wording to the
first reading; flagging it so the choice is made rather than inherited.

**2. Two open drafts of one exam.** (case 3.7.) `ExamService.revise`'s own comment already raises
this with the lead and says the sentence in the old javadoc was the thing that was wrong rather
than the code: contract §5.4 says only "`EXAM_VERSION_REVISE` refuses a DRAFT", the check is on
the addressed version, and nothing asks whether the exam already has a draft elsewhere. This walk
**observed the consequence** rather than reasoning about it: revising v1 (REJECTED) while v3 was
a DRAFT was accepted and produced v4, leaving `101101` with two DRAFT versions, both reachable
from `EXAM_LIST`, which renders a card per version. No case in scenarios 2–4 currently tests it.
"One open draft per exam" is a new rule and E7.11's builder has to decide which draft it opens, so
it stays the lead's. Left as behaviour; recorded here so the observation exists next to the
comment that predicted it.
