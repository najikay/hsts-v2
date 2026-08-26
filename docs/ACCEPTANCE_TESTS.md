# HSTS — Acceptance test table

**Owner:** Member B · **Reviewer:** Naji · **Feeds:** E22.1, submission document (Assignment 3 §1)

One section per scenario in the course test outline (*מתווה לבדיקת מערכת*), scenarios **1–21**.
Scenarios 1–14 are functional; 15–21 are the non-functional requirements. `T-n` is the scenario
number in the original table; `F-n.n` are the PRD features it tests; `S-n` are spec requirements
that refine it.

**Status legend:** ⬜ not run · ✅ pass · ❌ fail · ⚠ partial · ⛔ blocked (feature not built yet)

**Actual**, **Status** and **Bugs found** stay empty until the feature exists and is tested.
Everything else is filled now, from the PRD.

## How to run these

Every case is written against the **seed dataset** (`docs/seed/SEED_CONTENT.md`), so no case
says "some teacher" or "a student" — it names the actual account and the actual row. Load the
seed, sign in as the named user, follow the steps verbatim.

Accounts used most (password `demo123` for all):

| Account | Role | Why it appears |
|---|---|---|
| `dana.cohen` | Teacher | Authors the Mathematics exams; teaches Algebra 11 + Calculus 12 |
| `rina.barak` | Teacher + coordinator of subject 10 | Approves/rejects `dana.cohen`'s exams |
| `avi.mizrahi` | Teacher | Authors the Java exams; teaches Java 21 |
| `michal.sharon` | Teacher + coordinator of subject 20 | Approves the Java exams; teaches Databases 22 |
| `maya.levi` | Student | Enrolled 11, 21, 22 — the "sat the graded exam" student |
| `noam.peretz` | Student | Enrolled 12, 21 — the **not** enrolled in Algebra student, for negative cases |
| `omer.katz` | Student | The seeded TIMED_OUT attempt |
| `principal.avia` | Principal | Read-only scenarios |

Key seed rows: exam **101101** (Algebra Midterm, v1 REJECTED → v2 APPROVED) · exam **101201**
(Calculus, PENDING) · exam **202102** (Collections Quiz, REJECTED) · execution **4821** (closed,
fully graded) · **7390** (closed, awaiting grading) · **5164** (scheduled today) · **2075** (live).

---

## Summary

| # | Scenario | Cases | Status |
|---|---|---|---|
| 1 | Login (כניסה למערכת) | 4 | ✅ 3 passed, 1 partial (throttle not driven) — B-1 fixed |
| 2 | Question bank editing (עריכת מאגר שאלות) | 8 | ⚠ 7 passed below the screen, 1 partial (no illustrated seed rows — B-8 open). 2.4 failed at the walk and is fixed — B-7 |
| 3 | Exam building (בניית מבחנים) | 9 | ✅ all 9 passed below the screen. 3.4 failed as written and its own text was rewritten — B-9 (the case, not the code) |
| 4 | Exam approval (אישור מבחן) | 6 | ⚠ 5 passed below the screen, 4.2 partial (the render is the case, outstanding). 4.4's bell failed at the walk and is fixed — B-11 |
| 5 | Out of the drawer (הוצאת מבחן מהמגרה) | 6 | ⚠ 5 passed below the screen, 5.4 UI-only (server gate verified). 5.6's fixture defect fixed — B-10 |
| 6 | Exam execution (ביצוע מבחן) | 10 | ⬜ |
| 7 | Extending exam duration (הארכת משך הבחינה) | 4 | ⬜ |
| 8 | Exam checking (בדיקת מבחנים) | 7 | ✅ all 7 passed |
| 9 | Viewing an exam grade (צפיה בציון הבחינה) | 5 | ✅ all 5 passed (9.5 below the screen) |
| 10 | Viewing exam results (צפיה בתוצאות בחינות) | 5 | ⬜ |
| 11 | Viewing data — principal (צפיה בנתונים) | 4 | ⬜ |
| 12 | Viewing reports (צפיה בדו"חות) | 5 | ⬜ |
| 13 | Creating a study bot (יצירת בוט לימודי) | 6 | ⬜ |
| 14 | Using the bot (שימוש בבוט) | 7 | ⬜ |
| 15 | Client-server on separate machines, JARs, connect GUI | 5 | ⬜ |
| 16 | Concurrent users; no double login | 4 | ⬜ |
| 17 | Test data prepared in the database | 3 | ⬜ |
| 18 | Efficient computing, no user-initiated refresh | 5 | ⬜ |
| 19 | Flexible, change-tolerant design | 3 | ⬜ |
| 20 | Reuse; use of design patterns | 3 | ⬜ |
| 21 | UI quality and friendliness | 6 | ⬜ |
| | **Total** | **115** | |

---

## 1 — Login (כניסה למערכת) · T-1

**PRD:** F1.1, F1.2, F1.5 · **Spec:** S-38

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 1.1 | Launch the client. On the connect screen, accept the pre-filled address (or enter host/port). Connect. Sign in as `dana.cohen` / `demo123`. | Connect screen appears **before** login, pre-filled from defaults. Login succeeds. | **Passed** on the 2026-08-22 re-run against a `--reseed` database (375 rows). Connect screen appeared before login with the address pre-filled; sign-in succeeded against the seeded `users` table. The blocking cause recorded in the first run was the empty table, not a defect. | ✅ | B-1 (now fixed) |
| 1.2 | Observe the shell after 1.1. | Teacher shell: navigation rail with Dashboard, Question Bank, Exams, Results, Study Bot, Settings. **No** Approvals item. Dashboard greets by name. | **Passed.** Teacher rail as specified, with no Approvals item — the discriminating detail, since Approvals belongs to the coordinator and not to a plain teacher. Dashboard greeted her by name. | ✅ | |
| 1.3 | Sign out. Sign in as `rina.barak`, then `maya.levi`, then `principal.avia`. | Each gets a different, role-appropriate menu: `rina.barak` = teacher rail **plus Approvals** — nothing more; the dual-hat coordinator is a teacher with one extra rail item, not a distinct shell; `maya.levi` = Dashboard / Take Exam / My Grades / Study Bot / Settings; `principal.avia` = Dashboard / Data / Reports / Settings with nothing mutating. | **Passed.** All three rails as specified. **My Grades is live for the first time** on `maya.levi`’s rail — clickable rather than the greyed "Arrives with E13" placeholder the first run saw — which is the precondition for every case in scenario 9. Take Exam remains greyed pending E10’s screen; that is the current state of the build and not a defect of this case. | ✅ | |
| 1.4 | Sign in as `maya.levi` with password `wrong`. Repeat 5 times, then try the correct password. | Each failure shows one generic message that does **not** reveal whether the username exists. After 5 failures the 6th attempt is refused for 30s even with the right password. | Partially evidenced: three failed attempts on `dana.cohen` produced one generic "incorrect username or password" each — indistinguishable from the no-such-user case, which is F1.1's requirement. The throttle itself was not driven to 5. Full run blocked with 1.1. | ⚠ | |

---

## 2 — Question bank editing (עריכת מאגר שאלות) · T-2

**PRD:** F2.1–F2.6 · **Spec:** S-5, S-8, S-9 · **Decision:** C-8 / ADR-016

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 2.1 | As `dana.cohen`, open Question Bank → Add. Fill text, 4 distinct answers, mark one correct, set topic and difficulty, attach an image. Save. | Question is created for Algebra (11). ID is assigned by the server, read-only, 5 digits = `11` + 3-digit serial (S-8). | **Passed below the screen.** Through the production `QUESTION_CREATE` against a reseeded database (375 rows). The server assigned **`11012`** — `11` + serial `012`, continuing the seed's eleven Algebra questions rather than restarting, which is S-8's whole claim — and answered `versionNo=1 latestVersionNo=1`, `author=Dana Cohen`, `hasImage=true`. The Algebra count went **11 → 12**. Re-read in a second transaction through `QUESTION_GET`, the stem, four answers, `correctAnswer=2`, topic and difficulty all came back as submitted, Hebrew intact through `utf8mb4`; the illustration came back from `QUESTION_IMAGE_GET` as `image/png, 67 bytes`. **The id being read-only is a screen claim**: below it the id is not a field a caller can set — `QuestionDraft` carries no id and the serial is allocated server-side under the course's row lock. Screen render at the manual pass. | ✅ | |
| 2.2 | In the same form, try to save with two identical answers; then with no answer marked correct; then with two marked correct. | Each is refused with a specific message. Exactly one correct answer is enforced, answers must be pairwise distinct (C-8). Correct-answer control is single-select, so "two correct" must be impossible to express. | **Passed below the screen, four probes.** Each malformed question was refused with `VALIDATION` and a specific sentence, not a generic one. Two identical answers: *"Answers 1 and 2 are the same. Two identical answers make the correct one ambiguous, so change one of them. They have to differ by more than spacing."* (the sentence read "spacing or hyphens" at the walk; the hyphen half was removed with B-7, since dashes now separate two answers as the collation does) No answer marked correct (`correctAnswer=0`): *"Mark exactly one of the four answers as the correct one."* — and the same sentence for `correctAnswer=5`, the out-of-range half of one rule. **"Two marked correct" could not be probed because it cannot be expressed**: `QuestionDraft.correctAnswer` is a single 1-based int (C-8), so no payload says two, and the single-select control is a screen claim over a wire that already forbids the state. Distinctness holds on Hebrew as well as Latin — `מים` / `מימ`, the pair `utf8mb4_unicode_ci` folds, was refused. Screen render at the manual pass. | ✅ | |
| 2.3 | As `dana.cohen`, open the Course filter. | Only Algebra (11) and Calculus (12) are offered — the courses she teaches (S-5). Java and Databases questions are not reachable. | **Passed below the screen, and worth not downgrading to a UI check.** `BankBrowseService.reachableCourseCodes` answered `[11, 12]` for `dana.cohen`. The dropdown's contents are a screen claim; what was verified underneath is stronger: `QUESTION_GET` for `21001`, a Java question, was refused **server-side** `NOT_FOUND` with the deliberately indistinguishable sentence *"That question is not in your bank. It may have been deleted, or it may belong to a course you do not teach…"*, and `BANK_LIST` asking explicitly for `courseCode=21` answered `totalRows=0` rather than an error — the filter intersects with her scope instead of trusting it, so naming somebody else's course discloses nothing. Her unfiltered browse returned `totalRows=21` drawn from `[11, 12]` only. Screen render at the manual pass. | ✅ | |
| 2.4 | Open question **11005**, change its text, save. Then open its version history. | A **new version** is created. The previous version is still in the bank and viewable (T-2.2). The bank list shows the latest version. | **Failed at the walk, on the case's own first step; fixed in the acceptance-fixes batch.** Sending the stored question back with only the stem changed — what an editor bound to the stored row sends — answered `VALIDATION`: *"Answers 1 and 3 are the same."* The two answers are `2, 3` and `-2, -3`. `QuestionValidator.sameAnswer` returned **true** while MySQL under the column collation returned **0**, so the validator was stricter than `ck_question_versions_distinct` and refused a row the database had itself stored; **five seeded questions could not be written back at all** (`11005, 11006, 11008, 12005, 12007`). Filed **B-7**, now **Fixed**: the fold substitutes a sentinel for each collator-ignorable dash, and `BankRoundTripIntegrationTest` walks create-then-stem-only-edit on all five seeded answer sets against real MySQL. **The versioning half was sound throughout** — walked on reworded answers, the edit wrote **v3**, the history came back newest-first as `[v3, v2, v1]` with each version's own stem and author, and the bank row moved to `latestVersionNo=3` while v1 and v2 stayed readable. Screen render at the manual pass. | ✅ | B-7 (fixed) |
| 2.5 | Open exam **101101** v2 and inspect the question that came from 11005. | It still references **version 1** — the released exam is pinned to the version it was built from (C-2, S-14). It did **not** silently follow the edit in 2.4. | **Passed, and it is the cleanest demonstration of C-2 / S-14 in the suite.** After 2.4 pushed `11005` to version 3, `EXAM_VERSION_GET` on exam `101101` **v2** — the APPROVED, released version — still answered `pinnedVersionNo=1` against `latestVersionNo=3`, at 15 points, third in the paper. The pinned stem read *"What are the roots of x² - 5x + 6 = 0?"*, which is v1's wording, not v2's and not the edit's. The released exam did not follow the bank forward; the two numbers in one row, 1 and 3, are the whole claim. | ✅ | |
| 2.6 | Browse the bank. Filter by course, then topic, then difficulty, then free-text search. Scroll a list with illustrated questions. | Filters combine correctly. List + detail layout. Images load lazily, not all at once (NFR-18). | **Filters passed below the screen; the illustrated half could not be walked — B-8.** Every combination narrows and they compose: course `11` → `totalRows=12`; `+ topic='Quadratic functions'` → **5**; `+ difficulty=EASY` → **2**; `search='parabola'` → **3**; `topic='Inequalities' + difficulty=HARD` → **1**. Paging is server-side and clamped: `page=0 size=5` returned 5 rows of `totalRows=12` over `totalPages=3`. **Lazy image loading is structural rather than a screen behaviour and was verified as such**: `BankQuestionRow` carries a `hasImage` **flag and no bytes**, so a browse cannot move an image however long the list; a picture is a separate `QUESTION_IMAGE_GET` addressed by version. **What could not be walked is "scroll a list with illustrated questions":** the seeded bank holds **45 question versions and not one image**. `11005` v1, which the seed marks `img=yes`, answered `NOT_FOUND`. The one image in the database is the one 2.1 uploaded, and fetching it worked. Layout is a screen claim; screen render at the manual pass. | ⚠ | B-8 (open) |
| 2.7 | Try to delete question **11001** (used by exam 101101). | Deletion is **blocked**, with a dialog naming the exams that reference it (F2.5). | **Passed below the screen.** `QUESTION_DELETE` on `11001` answered **`OK`**, not an error — being told which exams use a question is a successful answer to "may I delete this" — carrying `deleted=false` and one blocking exam, **`101101 "Midterm: Algebra"`**, named by display id and by name, which is what the dialog has to render. `11001` sits in *two* versions of that exam (v1 and v2, seed §8.1) and is reported once, by exam rather than by version, which is the right granularity for a sentence a teacher reads. The question was still in the bank afterwards (`v1 of 1`). Worth recording that **this block has no database backstop** — soft delete is an `UPDATE` and no foreign key fires on an update — so the refusal is the service query working, not the schema helping. Screen render at the manual pass. | ✅ | |
| 2.8 | Delete a question no exam references (e.g. a freshly created one). Confirm. Then look for it in the bank and in version history. | Soft-deleted after a confirm: gone from the bank list, its serial is not reused, and **the store keeps its version rows** — the delete stamps `questions.deleted_at` and touches no `question_versions` row. **`QUESTION_VERSIONS` answering `NOT_FOUND` for a soft-deleted question is contract-correct, not a gap**: bank contract §6 folds unknown, deleted and out-of-scope into one indistinguishable answer so display ids cannot be probed. "History preserved" is a claim about the store, not a promise that a teacher can reopen it. | **Passed below the screen.** Deleting `11012`, the question 2.1 created and no exam references, answered `deleted=true` with no blocking exams. It then left the bank: the course-11 browse went **12 → 11** rows and `11012` is absent; `QUESTION_GET` answers the same indistinguishable `NOT_FOUND` an unknown id gets, and so does `QUESTION_VERSIONS`. **The delete is soft**: `questions.deleted_at` is stamped and the version rows are untouched — **1 before, 1 after**. **And the serial is not reused**: the next Algebra question was assigned **`11013`**, with `11012` left spoken for. The Expected cell was reworded on 2026-08-26 to claim exactly what the system does; the original wording could be read as promising a staff-only history read of a deleted question, which would be a contract change rather than a bug. The confirm step is a screen claim; screen render at the manual pass. | ✅ | |

---

## 3 — Exam building (בניית מבחנים) · T-3

**PRD:** F3.1–F3.6 · **Spec:** S-10, S-11, S-12, S-13

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 3.1 | As `dana.cohen`, create an exam for Algebra: name, duration, general text for examinees, teacher-only text. | All four fields accepted. Author is recorded automatically as `dana.cohen` (S-12). ID assigned by server: 6 digits = subject(2)+course(2)+serial(2) → `1011nn` (S-10). | **Passed below the screen.** All four fields are accepted and each is checked by name: with the four present and no questions, `EXAM_CREATE` answered `VALIDATION` *"An exam needs at least one question. Add questions from the bank, then set their points."* — the metadata got through and the composition rule is what stopped it. A blank name answered *"Give the exam a name before saving it."*; `durationMinutes=600` answered *"An exam runs between 1 and 480 minutes. Check the duration you typed."*, naming the ceiling rather than only the fact of being outside it. **The author is not a field she can send** — `ExamCreateRequest` carries no author and the id comes from the session — and the scope guard is real: the same create aimed at course 21 was refused `FORBIDDEN`. The id and recorded author are observed on the write 3.3 completes: **`101103`**, `10`+`11`+`03`, `author=Dana Cohen`. *Structural note the table does not carry:* `EXAM_CREATE` takes metadata **and** the whole composition in one message, so there is no stored "metadata only" exam between 3.1 and 3.3 — 3.1, 3.2 and 3.3 are three answers from one verb. Screen render at the manual pass. | ✅ | |
| 3.2 | Compose **manually**: pick questions from the Algebra bank, reorder them, assign points. Watch the points indicator. | Live running total. Save is **blocked** — not merely warned — while the total ≠ 100 (F3.1). | **Passed below the screen: blocked, not warned, and the sentence says which way she is out.** Five questions totalling 96 were refused with *"The points add up to 96. Add 4 more to reach 100."*; the same five totalling 104 with *"The points add up to 104. Remove 4 to reach 100."* One question worth 0 points was refused separately — *"Question 1 is worth an impossible number of points. Each question is worth between 1 and 100."* — naming the position, which is the row she has to fix. **Blocked means nothing was stored**: Algebra exams in the database were **2 before and 2 after** all three refused saves. The live running total is a screen claim, and the sentence it has to agree with is the one quoted here. Screen render at the manual pass. | ✅ | |
| 3.3 | Set the points to total exactly 100 and save. | Saves. The exam appears in the drawer as DRAFT. | **Passed.** Four questions at 25 points each were accepted and the exam came back read out of the database rather than echoed: **`101103`**, `versionNo=1`, `state=DRAFT`, `author=Dana Cohen`, duration 60, `lockVersion=0`, both texts stored verbatim. **The order she arranged is the order stored** — the pins were sent deliberately out of bank order and came back `ord=1 11009, ord=2 11001, ord=3 11006, ord=4 11003`, so "reorder them" survives the write. `EXAM_LIST` then showed it in her drawer as `v1 DRAFT, questionCount=4, duration=60`, beside her three seeded exams — four rows, `[101103, 101101, 101201, 101102]`. | ✅ | |
| 3.4 | Create another exam, compose **automatically**: request **4 questions from topic `Linear equations`**, mixed difficulty. | Server selects and returns a draft composition totalling 100 points. It is editable before saving, and nothing is written until she saves. | **Passed, on a rewritten case — B-9 was a defect in the case, not in the code.** As written the case asked for 5 questions from topic "משוואות ליניאריות", and no such topic exists: the seed's Algebra topics are `Linear equations`, `Quadratic functions` and `Inequalities` (English, per the 2026-08-23 ruling), so the request answered a truthful shortfall `requested=5 available=0`. Asked for `Linear equations` it answered `requested=5 available=4` — that topic holds four questions and **no** Algebra topic holds five — so the count was unsatisfiable too. The step above is the report's proposed replacement, which keeps the case's "from a topic" shape. **On it the feature passes**: `Linear equations` × 4 proposed four questions at 25 points each, and the mixed-difficulty grid (2 easy + 1 medium + 1 hard) proposed exactly that mix. Asking the whole course for 5 also passes, proposing five at 20 points across three topics and drawing `11005` at **v2**, its latest. Every proposal already totals **100**, so it is savable in one click. **It is editable and it writes nothing**: exams were **7 before and 7 after** five auto-compose calls, and the last proposal was reversed, re-pointed to 40/15/15/15/15 and sent to `EXAM_CREATE`, which stored it as `101104 v1 DRAFT`. | ✅ | B-9 (fixed — the case) |
| 3.5 | Compose automatically for Java (as `avi.mizrahi`): request **3 questions from topic "Recursion"**. | **No exam is created.** The report states exactly what is missing — the bank holds 2 Recursion questions (T-3 note, F3.3). | **Passed, and this is the thin-topic fixture doing exactly what it was built for.** As `avi.mizrahi`, asking for 3 questions from topic **Recursion** answered **`OK`** — an infeasible request is a successful answer, not an error, which is what keeps F3.3's report out from behind a red banner — carrying `feasible=false`, no proposal, and one shortfall row: `topic=Recursion difficulty=null requested=3 available=2 missing=1`. **The number is the one she could disprove and it holds**: the bank really does contain 2 live latest-version Recursion questions. **No exam was created** — Java exams were **2 before and 2 after** — and that is true by construction rather than by a rollback: nothing on this path inserts. | ✅ | |
| 3.6 | Same, but request **1 HARD Recursion question**. | Again refused with a specific shortfall message: no HARD question exists in that topic. | **Passed.** The same request narrowed to one **HARD** Recursion question answered `OK`, `feasible=false`, with the shortfall scoped to the difficulty rather than to the topic: `topic=Recursion difficulty=HARD requested=1 available=0 missing=1`. `available=0` is the specific thing the case asks to see — the topic is not empty, the *difficulty* is — and the report says so without an aggregate row pairing that demand with the topic's count of 2, which would have been a sentence she could disprove. Java exams **2 before, 2 after**. | ✅ | |
| 3.7 | Edit exam **101101** and save. | A **new version** is created; the previous version is retained (T-3.5, C-2). | **Passed.** `EXAM_VERSION_REVISE` on `101101` **v2** (APPROVED) wrote **v3 as a DRAFT**, copying the metadata and all seven pinned questions forward at their points — including `11005` still pinned at **v1**, so a revision inherits the pin rather than re-resolving it. `rejectedReason` was deliberately not copied and came back empty. **The predecessors are retained**: v2 re-read as `APPROVED` with its seven questions, and v1 as `REJECTED` still carrying the coordinator's reason. Revising the new draft is refused: *"This version is still a draft, so there is nothing to revise. Edit it and save."* **Observed beyond the case, for the open ruling `ExamService.revise` already flags in its own comment:** the DRAFT guard is on the *addressed* version only, so revising v1 while v3 was a draft was accepted and produced **v4**, leaving `101101` with two open drafts. "One open draft per exam" is a new rule and E7.11's builder has to decide which draft it opens, so it stays the lead's; recorded, not filed. | ✅ | |
| 3.8 | Add question **11009** to a second Algebra exam. | Allowed — a question may belong to more than one exam (T-3 note). | **Passed.** Question `11009` was already carried by four exams — read out of the delete block rather than out of SQL, which is the same list a teacher would be shown: `101101 "Midterm: Algebra"`, `101102 "Quiz: Inequalities"`, and the two this walk created. Adding it to a fifth, `101105`, was accepted with no complaint, and the delete block then named **five**. Nothing in the composition rules is keyed on a question being unused; the only "twice" rule is 3.9's, and it is scoped to one exam version. | ✅ | |
| 3.9 | In one exam version, try to add question 11005 **v1** and 11005 **v2**. | Refused. The same question cannot appear twice in one exam version, even through different versions (PRD §6). | **Passed, three probes.** Pinning `11005` **v1** and `11005` **v2** into one exam version was refused `VALIDATION`: *"Question 11005 is in this exam twice. An exam can use a question once, even through two versions of it. Remove one of them."* — the sentence names the question by the display id she sees on the bank row, which is the one thing the database's own `UNIQUE(exam_version_id, question_id)` cannot say. Pinning the same version twice is refused by the same rule with the same sentence, so the two shapes of the mistake are one answer. A third probe on the neighbouring rule: a Calculus question pinned into an Algebra exam was refused with *"Question 12001 belongs to a different course, so it cannot go in this exam."* | ✅ | |

---

## 4 — Exam approval (אישור מבחן) · T-4

**PRD:** F4.1–F4.3 · **Spec:** S-14

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 4.1 | As `dana.cohen`, submit the Calculus exam **101201** for approval. Sign in as `rina.barak` and open Approvals. | The exam appears in the pending queue for subject 10 (Mathematics) — her subject only. | **Passed below the screen.** Against a freshly loaded seed (375 rows) through `APPROVALS_QUEUE_GET`, `rina.barak`'s queue holds **exactly one row**: `101201 v1 'Midterm: Calculus', course 12, by Dana Cohen, 7 questions, 90 min, PENDING, lockVersion 0, selfAuthored false`. The case's own step was then driven rather than assumed: `dana.cohen` submitted `101102` v1 through `EXAM_SUBMIT` (`DRAFT` → `PENDING`), and rina's queue went to two rows with the bell raised by the post-commit hook. **The "her subject only" half is evidenced positively, not by absence:** `202102` was revised and resubmitted so subject 20 had something pending too, and the two queues then read `rina.barak → [101201]`, `michal.sharon → [202102]`. Scoping is in the SQL — `findPendingForCoordinator` joins `coordinators` — so a version outside her subjects is never fetched. A plain teacher has no queue at all: `dana.cohen` calling the verb is refused `FORBIDDEN`. The empty queue is answered two ways as §4.1 requires: `michal.sharon` on the bare seed gets 0 rows with `coordinatesAnything = true`. Screen render at the manual pass. | ✅ | |
| 4.2 | Open it from the queue. | Full **read-only preview of the exam exactly as a student will see it**, plus metadata and the teacher-only notes (F4.1 — a v1 failure, check this carefully). | **Passed below the screen; the render is the half this case is about and it is outstanding.** `EXAM_PREVIEW_GET` for `101201` v1 as `rina.barak` returned the whole paper: summary, student text, **seven questions** in order each with four options, and beside them the `TeacherOnlyBlock` with the teacher-only note, the author's name and a **7-row answer key**. **The v1 failure is fixed structurally rather than by a screen:** the paper travels in the student's own wire type, `ExamQuestion`, whose components are `[questionVersionId, displayId, ordinal, points, text, option1..option4, image]` — **no field a correct answer could travel in**. Correctness exists only in `PreviewAnswerRow`, in the staff-only block. The wall between the two audiences is in the types. Two callers are admitted and no more: the subject's coordinator, and the version's **own author** (`dana.cohen` opens it OK, which is what F4.2 needs); `avi.mizrahi` is refused. **Not verified here:** that the coordinator's screen renders this with the same component the take-exam screen uses — a rendering claim, and the one F4.1 was marked a v1 failure over. Its role-refusal copy carried B-12, now fixed. | ⚠ | B-12 (fixed) |
| 4.3 | Reject it with no reason entered. | Refused — a reason is **required** (T-4.2). | **Passed.** Three shapes were tried against the seeded pending exam and each was refused server-side with its own sentence: an empty reason → `VALIDATION`, *"Type why you are sending this exam back. The teacher sees this reason."*; **whitespace only** → the same sentence, so the rule is about a real reason rather than a non-empty string; and `"no"` → *"Give the teacher something to work with: at least 10 characters explaining what to change."* After all three, `101201` v1 was still `PENDING` with `rejected_reason = null` and `dana.cohen`'s notification count was unchanged at 2 — **nothing was half-applied and nobody was told about a rejection that did not happen.** The rule is `ExamRejectRequest.validate`, the same definition the client runs on every keystroke, checked here on the side that matters and before anything is read. | ✅ | |
| 4.4 | Reject with a reason. Sign back in as `dana.cohen`. | Reason is stored, visible on the exam, and delivered to `dana.cohen` as a notification (T-4.2, F4.2). | **Stored ✅ visible ✅ delivered ✅ — the bell was the one half that failed at the walk, and it is fixed in this batch.** `rina.barak` rejected `101201` v1 with a reason; the decision came back `REJECTED` carrying it, and `exam_versions.rejected_reason` holds it **verbatim and trimmed**. *Visible on the exam:* `MY_APPROVALS_GET` as `dana.cohen` returned three rows — `101101 v2 APPROVED`, `101201 v1 REJECTED` with the new reason, and `101101 v1 REJECTED` with the seed's own — so a reason survives a dismissed notification, which is the half a bell cannot provide. *Delivered:* one durable row was written, type `APPROVAL_REJECTED`, title *"Exam sent back for changes"*, ref `exams/25`. **But at the walk the bell would not open**: `NOTIFICATIONS_GET` answered `INTERNAL` for `dana.cohen`, `rina.barak`, `tamar.shani` and `avi.mizrahi`, while `michal.sharon` — the one staff account the seed gives no notification — opened fine and empty. Cause: six of eight seeded type strings were not `NotificationType` constants and one unparseable row took the whole page down, including the well-formed row this case had just written. Filed **B-11**, now **Fixed** on both sides: the seed holds enum constants, and the read path skips-and-logs an unparseable row instead of failing the page. | ✅ | B-11 (fixed) |
| 4.5 | As `rina.barak`, approve a resubmitted version. | That **version** becomes APPROVED; the author is notified. Earlier versions keep their own status. | **Passed, both halves, and the second is the stronger.** *As written:* `101201` v1 was rejected with a reason, `dana.cohen` revised and resubmitted (the hook running after the handler's commit as §5.5 requires), rina's queue read `[101201 v2]`, and she approved it. **v2 is `APPROVED`; v1 is still `REJECTED` and still carries its own reason** — earlier versions keep their own status, which is C-2 holding on a running server. The author was notified `APPROVAL_APPROVED`, body *"…You can release it now."*, which names the next thing she does and is exactly the state 5.1 then refuses to release from anything else. Approving twice is refused rather than reapplied: `CONFLICT`. *E8.2, driven separately:* resubmitting **while v1 was still pending** superseded it in one hook — v1 flipped to `REJECTED` with the supersede reason, v2 is `PENDING`, and **her queue holds one row, not two**, so the coordinator is never asked to choose between two submissions of one exam. She was told both things, in order: `APPROVAL_SUPERSEDED` then `APPROVAL_REQUESTED`. | ✅ | |
| 4.6 | As `michal.sharon` (coordinator of subject 20 **and** the only Databases teacher), approve her own exam **202201**. Then inspect the server log. | Allowed — PRD F4.3: not required by spec, permitted, **but logged**. The logging owner is **E8's `ApprovalService`** (confirmed in the PR #2 review). Verify the log entry actually exists; "allowed but logged" with no log line is a silent failure. | **Passed, and the log line is the point.** `202201` v1 is already APPROVED on the seed, so the action needed something waiting: `michal.sharon` revised her Databases Final and submitted it wearing her teacher hat. **Her own submission appeared in her own queue**, flagged `selfAuthored = true` — information rather than a warning, exactly as `ApprovalRow`'s contract says. She then approved it: `OK`, `selfApproved = true`, version `APPROVED`. **The record exists.** Exactly one `WARN` was emitted by `server.features.approval.ApprovalService`, in full: `SELF-APPROVAL: coordinator 96 (Michal Sharon) approved her own exam 202201 'Databases Final' version 2 (F4.3)` — marker first so a grep needs no pattern, then who, which exam, which version. She was **not** sent a bell about something she did a second earlier (count 1 → 1), the deliberate exception in `announce`. The permission is also not a hole: `michal.sharon` approving a subject-10 exam is refused `FORBIDDEN` and `101201` v1 was left untouched at `PENDING`. | ✅ | |

---

## 5 — Taking an exam out of the drawer (הוצאת מבחן מהמגרה) · T-5

**PRD:** F5.1–F5.5 · **Spec:** S-2, S-14, S-15, S-17 · **Decision:** C-1

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 5.1 | As `dana.cohen`, try to release exam **101102** (DRAFT) and exam **101101 v1** (REJECTED). | Both refused. Only an **APPROVED version** can be released (T-5.1, S-14). | **Passed, and enforced twice in two different ways — which is the point.** `RELEASE_CREATE` against `101102` v1 (`DRAFT`) and against `101101` v1 (`REJECTED`) was refused each time with `VALIDATION` and the same sentence: *"Only an approved exam can be released. Ask your subject coordinator to approve this version, then release it."* — a refusal that names who unblocks it, because the way a teacher actually reaches it is by holding the dialog open while her coordinator sends the exam back. **`exam_executions` still held 4 rows afterwards**, so nothing was inserted on behalf of either refusal. And the picker never offers them in the first place: `RELEASE_OPTIONS_GET` for `dana.cohen` returned exactly `[101101 v2 (11)]` — the `APPROVED` filter is a `where` clause, so PRD §6's "impossible (not listed)" is a property of the query rather than of the client. | ✅ | |
| 5.2 | Release **101101 v2** (APPROVED). Set open and close datetimes. | Accepted. Validation rejects close ≤ open, and a close time in the past (F5.2). | **Passed, with four window shapes driven rather than two.** `101101` v2 released cleanly: execution created for *Midterm: Algebra* (course 11), 75 min, `SCHEDULED`, window stored as given. The refusals carry their own sentences because they are different mistakes: **close before open** and **close equal to open** → *"The closing time has to be after the opening time."*; **a window entirely in the past** → *"That opening time has already passed. Pick a time from now on and try again."*; **a thirty-second window** → *"The window has to be at least a minute long."* All four are refused `VALIDATION` **before any read** — `exam_executions` went from 4 rows to exactly 5, the one legitimate create. One deliberate non-refusal was checked too: an opening moment **two minutes in the past** is accepted, because `PAST_GRACE` is five minutes and a teacher who picks "now", reads the summary and presses Create is doing the commonest thing this screen is for. Screen render at the manual pass. | ✅ | |
| 5.3 | Set the execution code. Try `12`, then `ABCDE`, then `4821`. | 4 characters exactly, alphanumeric (C-1). Too short and too long are refused. **`4821` is accepted, and correctly**: it is held by a CLOSED sitting, and uniqueness is a service rule about sittings a student could still walk into. | **Passed, and the third code in the step needs a sentence of its own.** Shape: `"12"` and `"ABCDE"` are both refused `VALIDATION` — *"An exam code is 4 letters or digits. Change it, or leave it blank to generate one."* — and so is `"A-1B"`, so the rule is four **alphanumerics** rather than four characters. **`"4821"` is accepted**, and that is the rule working rather than a gap: the sitting holding `4821` is `CLOSED`, and §5's uniqueness rule covers only sittings a student could still walk into (MySQL has no partial unique index). The two codes a student *could* still type are refused by name: `"2075"` (LIVE) and `"5164"` (SCHEDULED) both answer *"That code is in use by a live or scheduled sitting."* A code typed `"ab7q"` is stored `AB7Q` (C-1), and left blank the server rolled `RNR2` — four characters from the spoken-alphabet-safe set with `O/0` and `I/1` dropped, since a code exists to survive being read out across a room. The Expected cell gained its third sentence on 2026-08-26 so a reader cannot mistake the acceptance for a miss. | ✅ | |
| 5.4 | Sign in as `maya.levi` and look everywhere a student can see this execution. | The code is **never** shown to a student anywhere in the app — it is delivered orally (S-17). | **UI-only case; the screens are the manual pass's. What is verifiable below them was verified and holds.** Every verb in the release feature is role-gated to staff **before it reads anything**, so there is no path by which a student could be handed a release row at all: `maya.levi` calling `RELEASE_LIST_GET`, `RELEASE_OPTIONS_GET` and `RELEASE_CANCEL` is refused `FORBIDDEN` on each. `ReleaseRow` — the one wire type carrying `code` — is produced only by those verbs and by `PUSH_EXECUTION_STATUS`, which is addressed to `ReleaseRows.ownersOf`, the releasing teacher and the exam's author. And the type a student *does* receive, `ExamQuestion`, has **no code field** — the same structural argument case 8.7 makes about class statistics. **Outstanding for the manual pass:** walking `maya.levi`'s screens looking for the string, which is the half this case is actually about; nothing below the screen can prove a string is absent from a view. The refusal sentence she meets carried B-12, now fixed. | ⬜ | B-12 (fixed) |
| 5.5 | Release **101101 v2** a second time with a different window and code. | Allowed. The same exam can be taken out of the drawer many times, each execution with its own schedule, code and statistics (S-2). | **Passed, and the seed proves the shape before a single request is sent.** `101101` v2 already has **two** sittings on the loaded seed — `4821` (CLOSED, 09:00→11:00 fourteen days back) and `2075` (LIVE, straddling now) — one exam version, two releases, separate codes, windows, participants and statistics. A third and a fourth were then created from the same version with their own windows and codes (`M1A1` a day out, `M2B2` three days out); each got its own execution id, all four share one `examVersionId`, and the new ones start with zero participation. `dana.cohen`'s release list then read `[M2B2 SCHEDULED (0 started), M1A1 SCHEDULED (0 started), 2075 LIVE (0 started), 4821 CLOSED (8 started)]` — newest window first, each row with its own state and counts. The eight on `4821` are counted from the attempt rows, not accumulated in a column. | ✅ | |
| 5.6 | Open the release list. Cancel a SCHEDULED release (confirm). Then close a LIVE one early. | Cancel works before open, with confirm. Closing a live one warns first and then behaves like time expiry for active students (F5.5). Status chips read Scheduled / Live / Closed and update live. | **Passed below the screen; the confirm, the warning and the live chips are outstanding.** *Cancel:* legal from `SCHEDULED` and nowhere else. `michal.sharon` cancelled `5164`; the row came back `CANCELLED` and the stored status is `CANCELLED`. Cancelling it again → `CONFLICT`; cancelling the **live** `2075` → `CONFLICT`, *"This exam has already opened, so it cannot be cancelled. Use close early to end it now."* — the refusal hands her the other button rather than saying no. Ownership is real: `dana.cohen` cancelling `michal.sharon`'s release answers `NOT_FOUND`, indistinguishable from an id that does not exist. *Close early:* refused from `SCHEDULED`. Driven on the live sitting with two sitters, the close **force-submitted the straggler through the expiry path**: her attempt ended `TIMED_OUT` (not `SUBMITTED` — she did not hand it in, and that decides which screen she gets), and **it reached the grading seam**, which is the proof it went through the expiry path rather than a bespoke UPDATE; a paper closed any other way would be left unmarked for ever. The execution ended `CLOSED` with participation **frozen at started 2 / finished 1 / timed out 1** (S-21). *States:* derived per row against the server's own clock, with `serverNow` on the payload so the client never derives a state from its own. **A fixture defect blocked the demo half — B-10, now fixed**: the seed's "scheduled today" sitting resolved to 14:00–16:00 UTC on the load date, so after lunch there was no SCHEDULED row left to cancel and one scheduler tick drove it `SCHEDULED → LIVE → CLOSED`. Its window is now load-time-relative (opens now+3h, runs 2h). | ✅ | B-10 (fixed) |

---

## 6 — Exam execution (ביצוע מבחן) · T-6

**PRD:** F6.1–F6.10 · **Spec:** S-15, S-18, S-19 · **Decision:** C-4

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 6.1 | As `maya.levi`, open Take Exam. Enter the code of the **live** execution (`2075`). Then enter her national id. | Code accepted → id prompt → exam form renders with the general text, questions, single-choice answers and any illustrations (T-6.1–3). | | ⬜ | |
| 6.2 | Repeat 6.1 but enter another student's national id. | Refused. The id is validated against the signed-in student's own identity (F6.1). | | ⬜ | |
| 6.3 | As `noam.peretz` (not enrolled in Algebra), enter code `2075`. | Refused — not enrolled in that course. | | ⬜ | |
| 6.4 | Enter the code of the **scheduled** execution (`5164`) before its open time, and the code of a **closed** one (`4821`). | Both refused with a window message. Students can start only inside the open–close window (S-15, F5.2). | | ⬜ | |
| 6.5 | Watch the timer after entering the id in 6.1. | Countdown starts **at id entry**, not at code entry (S-18). It is server-authoritative: the client displays a synced value. Amber at 25% left, red at 5 minutes. | | ⬜ | |
| 6.6 | Answer 3 questions. Kill the client process. Relaunch, sign in, re-enter the exam. | Answers were auto-saved; the attempt resumes with saved answers **and the correct remaining time** — the clock kept running server-side (F6.3). | | ⬜ | |
| 6.7 | **Inspect the wire.** Capture the take-exam payload the server sends (server log / debug view). | The DTO physically contains **no** correct-answer field. Not hidden in the UI — absent from the data (F6.6; this was the v1 leak). | | ⬜ | |
| 6.8 | Let the timer run out without submitting. | Server force-submits whatever is saved and marks the attempt TIMED_OUT. Client shows a full-screen "Time is up" takeover: **no confirmation asked**, exam unreachable behind it, summary of what was handed in, single "Back to my dashboard". No later answer change is accepted server-side (F6.4). | | ⬜ | |
| 6.9 | Start a new attempt, answer some questions, press Submit with time remaining. | Two-step: a confirm dialog with an answer-summary grid (answered vs unanswered chips, clickable to jump), remaining time, and an "unanswered score 0" note → Submit / Keep working (F6.9). Confirm → success screen with handed-in time and solving minutes (F6.10). | | ⬜ | |
| 6.10 | Re-enter the same code after submitting. | "Already submitted" — one attempt per student per execution (F6.7). | | ⬜ | |

---

## 7 — Extending exam duration (הארכת משך הבחינה) · T-7

**PRD:** F7.1–F7.3 · **Spec:** S-20, S-21

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 7.1 | With `maya.levi` mid-attempt on execution `2075`, sign in as `dana.cohen` on a second machine, open the live monitor and add 15 minutes. | The student's timer updates **immediately** without any refresh: chip flashes, "+15:00" animates, a toast names who did it and the new end time (F7.1). The student is never left guessing. | | ⬜ | |
| 7.2 | After 7.1, open exam **101101 v2** in the drawer and check its duration. | Unchanged. The extension applies to the **current execution only** (S-20). | | ⬜ | |
| 7.3 | Release **101101 v2** again and start a fresh attempt. | The new execution uses the original duration, not the extended one. | | ⬜ | |
| 7.4 | Watch the teacher monitor while two students sit the exam concurrently. | Live counts of started / submitted / timed-out and per-student status and remaining time, all pushed — no refresh button anywhere (F7.2, NFR-18). | | ⬜ | |

---

## 8 — Exam checking (בדיקת מבחנים) · T-8

**PRD:** F8.1–F8.5 · **Spec:** S-22, S-23, S-24, S-25, S-26 · **Decision:** C-3

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 8.1 | As `avi.mizrahi`, open results for execution `7390` (closed, awaiting grading). | Every attempt already carries an auto-computed score: per-question points, correct ⇔ the single correct answer (F8.1, C-8). | **Passed.** The Grading rail item opened onto the queue with the Java sitting waiting — "8 sat · 8 marked · 8 still to approve". Every attempt already carried a score, computed on submission by `GradingOnSubmit` rather than by anything a teacher did. | ✅ | |
| 8.2 | As `maya.levi` — before any approval — look for the grade of execution `7390`. | **Not visible.** Auto-checking alone does not publish anything (C-3, S-24). | **Passed — already evidenced by case 9.1.** She sat both seeded exams and My Grades showed exactly one row, the Algebra one. Her Java 100 existed, was auto-computed, and was invisible. | ✅ | |
| 8.3 | As `avi.mizrahi`, change one student’s grade without entering a justification. | Refused. A manual change **requires** an explanation (T-8.3, S-23). | **Passed.** The override dialog refused with "Please say why you are changing this score." and the score did not move. Refused client-side before the request travelled, and the server refuses it independently (`GradingHandlersTest`), so the rule holds on both sides. | ✅ | |
| 8.4 | Change it again with a justification, and add a comment to the student. Then inspect the stored record. | Original auto grade, the change, and the reason are all stored — a full audit trail (F8.3). The comment is saved (S-22). | **Passed** on the 2026-08-23 re-walk, after A3 landed the comment wire. Overrode `itay.regev`’s Java grade with a score, a justification and a comment; approved it; signed in as him. My Grades showed the new score, **Adjusted**, and **the comment in the Teacher’s note column** — while the **Auto** column kept the machine’s original (F8.3’s audit trail). **The justification did not appear anywhere on his screens**, which is the structural strip working (S-23). Both halves of the case now pass. | ✅ | B-3 (fixed) |
| 8.5 | Approve the grades (try both per-student and bulk). | Both work. Each affected student receives a "your grade is available" notification (F8.4). | **Passed, with a defect found.** One verb serves both: selecting a single row approved that student, and Select-all then Approve approved the rest, with a confirmation naming the correct count. The queue then dropped the sitting entirely — "Nothing to grade" — which is the exclusion rule working. The notification half is evidenced: `maya.levi`’s bell showed **1** unread on her next sign-in. **Defect B-4:** Select-all ticked the rows in the session but highlighted nothing in the table. | ✅ | B-4 |
| 8.6 | As `maya.levi`, open the grade now. | Visible, together with her checked form: wrong answers marked, teacher comments included (S-24). | **Passed.** My Grades went from one row to **two**: the grade that was invisible in 8.2 appeared the moment a teacher approved it — C-3 end to end in one screen. Her Java checked form opened with all seven questions marked Correct (she scored 100). Teacher comments render where present; none could be written here — see B-3. | ✅ | B-3 |
| 8.7 | As `maya.levi`, look for the class average, median or distribution. | Not available anywhere to a student (S-26). | **Passed, and structurally.** Nothing of the kind appears on her screens. Stronger than not-seeing-it: the student-facing wire types are `MyGrades` (her own rows) and `CheckedForm` (her grade, her attempt, her answers), and **neither has a field a class statistic could travel in**. The statistics verbs are teacher-gated. S-26 holds by construction, not by omission. | ✅ | |

---

## 9 — Viewing an exam grade (צפיה בציון הבחינה) · T-9

**PRD:** F9.1 · **Spec:** S-36

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 9.1 | As `maya.levi`, open My Grades. | Lists the exams she took with her grades — including the seeded Algebra Midterm (execution `4821`). | **Passed**, and it demonstrates S-24 live. Maya sat **two** seeded exams — Algebra (execution 1, 60, approved) and Java (execution 2, 100, `AUTO`, unapproved). Exactly **one** row appeared: the Algebra midterm at 60 / 100. *(Corrected 2026-08-22: first recorded as 71, which was a fixture value from a unit test rather than the seed. §9.1 and the database both say 60.)* The unapproved 100 is absent, which is C-3 / S-24 holding on a running server rather than in a unit test: auto-grading publishes nothing until a teacher approves. The row carried its own exam name and course code (contract amendment v1.1) — without those the row would have read only "60 / 100" with no way to tell which exam it was. | ✅ | |
| 9.2 | Open the Algebra Midterm result. | The checked form: her answers, wrong ones marked, the correct answers shown, points per question, teacher comments (T-9.2, S-24). | **Passed.** Opened from the My Grades row. Header carried exam, course and the effective score; the attempt line read as submitted with the recorded solving time. Seven questions, each labelled Correct or Wrong with "Your answer" and "Correct answer" tags on the options and points per question. **The "Reviewed by your teacher" marker was correctly absent** — Maya’s 60 was never overridden, and the marker keys on the two scores *differing* rather than on a final score being present, which every approved row has. Styling is deliberately plain: the marking colours are left for the lead’s screen review, and every outcome carries a word as well as a class so the form does not depend on colour alone. | ✅ | |
| 9.3 | Use the export / print action on that result. | She obtains a copy of the checked exam (S-36). | **Passed, after fixing B-6.** The toggle did nothing on the first attempt: `.results-print` was added to the root in Java and had **no CSS rule anywhere**, so it was a silent no-op — on this screen and on the teacher’s results screen (E14.4) alike. With rules written, the question cards flatten and the chrome drops, leaving the marked paper in one column. | ✅ | B-6 |
| 9.4 | Try to reach another student’s grade — via the UI, and by replaying the request with a different student id. | Refused **server-side**, not just hidden in the UI. A student can never see another’s grade (T-9 note, F9.1). | **Passed, five probes.** Run against the reseeded database through the production `CheckedFormService`, which is the gate itself — a UI with no link proves nothing about the server. **(a)** `yael.azulay` replaying `maya.levi`’s grade id: refused. This is the strongest probe — a *classmate* who sat the same Algebra paper and holds a legitimate grade in the same sitting, which is where a leak would actually be. **(b)** `noam.peretz`, enrolled in neither course: refused. **(c)** the reverse, `maya.levi` replaying `yael.azulay`’s id: refused. **(d)** each still opens her own paper (60 and 55), so the gate refuses without over-refusing. **(e)** `MY_GRADES_GET` for `yael.azulay` returns one row, hers, and never another student’s. Every refusal is the same empty answer, so none of them reveals which of the three conditions stopped her. **Method noted:** this exercises the service, the repositories and the database, not the socket layer; the handler’s use of the session id rather than a payload id is covered by `ResultsHandlersTest`. | ✅ | |
| 9.5 | As `omer.katz` (the seeded TIMED_OUT attempt), open his result. | Grade is present and the attempt is shown as timed out, with his actual solving time in minutes (S-19). | **Passed below the screen; screen render outstanding.** Verified by running the production assembler (`CheckedFormService` → `GradeReviewService` → `CheckedFormCopy`) against the reseeded database: header `45 / 100`, attempt line **"Time ran out — submitted automatically · 75 minutes"**, seven questions, of which **four render as "Not answered" and three as Correct**. All three checked-form gates pass for this grade (execution `CLOSED`, grade `APPROVED`, ownership by query), and the attempt carries 3 answer rows on a 7-question paper — the four absent rows of §9.1.1, present in the database as absences rather than zeros. **Method noted deliberately:** this exercises every layer below JavaFX and does not exercise rendering, so the pixels are confirmed at the manual pass rather than here. An earlier report of "no Not answered questions" was a false alarm — most likely a different student’s form still open, since `maya.levi` answered everything and correctly shows none. | ⚠ | |

---

## 10 — Viewing exam results (צפיה בתוצאות בחינות) · T-10

**PRD:** F9.2 · **Spec:** S-35

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 10.1 | As `dana.cohen`, open Results. | Lists the exams **she wrote**, including executions run by other teachers (S-35). | | ⬜ | |
| 10.2 | Open execution `4821`. Read the table. | Per-student rows: score, submitted vs timed out, solving time. 8 students, matching the seeded roster. | | ⬜ | |
| 10.3 | Switch to the histogram view. | Score-bucket bars themed to the active palette, with mean, median and ±1σ markers labelled. Stat cards above: average, median, std, min/max, pass rate, participants. Values match the seeded stats — **mean 72.5, median 72.5, σ 17.5, pass rate 7/8**. | | ⬜ | |
| 10.4 | Hover a bar; toggle count ↔ percentage. | Tooltip gives bucket range, count, percentage. Toggle switches the axis without a reload. | | ⬜ | |
| 10.5 | Open results for an execution with no attempts (`5164`, scheduled). | A proper empty / insufficient-data state — not a blank panel or a crash. | | ⬜ | |

---

## 11 — Viewing data — principal (צפיה בנתונים) · T-11

**PRD:** F9.3 · **Spec:** S-7

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 11.1 | Sign in as `principal.avia`. Open Data. | Can browse the question bank school-wide, across every course and subject. | | ⬜ | |
| 11.2 | Browse exams and exam results. | Both readable, school-wide. | | ⬜ | |
| 11.3 | Look for any create / edit / delete / approve control anywhere in her shell. | **None exist.** Read-only by definition (S-7). | | ⬜ | |
| 11.4 | Replay a mutating request (e.g. update question) with her session. | Refused server-side. The role has literally zero mutating verbs authorized (F9.3) — not merely a hidden button. | | ⬜ | |

---

## 12 — Viewing reports (צפיה בדו"חות) · T-12

**PRD:** F9.4 · **Spec:** S-25, S-37

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 12.1 | As `principal.avia`, open Reports. Run the report comparing **different exams of the same teacher**. | Average, median and decile distribution per execution, compared side by side (T-12). | | ⬜ | |
| 12.2 | Run the report comparing **different exams of the same course**. | Same three measures, grouped by course. | | ⬜ | |
| 12.3 | Run the report comparing **different exams of the same student**. | Same three measures, tracking one student across her executions. | | ⬜ | |
| 12.4 | Cross-check any figure against the stored statistics for execution `4821`. | The report reads the **stored** per-execution statistics (S-25) rather than recomputing differently. Mean 72.5, median 72.5 — identical to §9.1 of the seed. | | ⬜ | |
| 12.5 | **Defense question rehearsal:** ask what it takes to add a new report dimension. | Answer demonstrable in the code: one new Strategy class plus a menu entry, nothing else (F9.4, S-37, NFR-19). | | ⬜ | |

---

## 13 — Creating a study bot (יצירת בוט לימודי) · T-13

**PRD:** F12.1–F12.3 · **Spec:** S-6, S-28, S-29, S-30

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 13.1 | As `avi.mizrahi`, create a study bot for Java (21): name + information sources. | Created. Only courses he teaches are offered (S-6). | | ⬜ | |
| 13.2 | Add sources of each type: a PDF, a Word document, and free text. | All three accepted and parsed server-side into indexed text at upload time (S-28, F12.2). | | ⬜ | |
| 13.3 | Upload a corrupt or password-protected PDF. | Parse failure reported immediately and clearly; no half-created source row is left behind. | | ⬜ | |
| 13.4 | Edit an existing source; remove one. | Both work; changes notify the other teachers of the course (F12.3). | | ⬜ | |
| 13.5 | As `tamar.shani` (the Java co-teacher), open the course bot and add a source. | She edits the **existing** bot — one bot per course (S-30, T-13.3). She is not offered "create a new bot" for Java. | | ⬜ | |
| 13.6 | With `avi.mizrahi` holding the source editor open, have `tamar.shani` open the same source. | She sees a live "Being edited by Avi Mizrahi" badge and a read-only editor; it flips to editable when he closes (F10.2). | | ⬜ | |

---

## 14 — Using the bot (שימוש בבוט) · T-14

**PRD:** F12.4–F12.11 · **Spec:** S-31, S-32, S-33, S-34 · **Decision:** C-4

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 14.1 | As `maya.levi` (enrolled in Java 21), open the Java study bot and ask a course question. | Answer arrives, displayed incrementally with a typing indicator and a course context header (F12.5). | | ⬜ | |
| 14.2 | As `noam.peretz`, try to open the **Databases** bot (he is not enrolled in 22). | Refused — enrolment required (S-31). | | ⬜ | |
| 14.3 | As `shira.dahan` (enrolled in 22), open the Databases bot — seeded **inactive**. | Refused, with a clear "not currently available" message. Enrolment alone is not enough; the bot must be active too (S-31). | | ⬜ | |
| 14.4 | Ask the bot something clearly outside the course material. | Friendly fallback: "The bot couldn't answer that — try rephrasing or ask your teacher." Not a stack trace, not an empty bubble (S-32, F12.7). | | ⬜ | |
| 14.5 | As `maya.levi`, open her bot history. | Her own past sessions, each Q/A with its timestamp, reopenable and continuable (S-33, F12.10). | | ⬜ | |
| 14.6 | As `avi.mizrahi`, open the bot's teacher view. | Aggregate only: total questions, questions over time, frequent topics. **No student identities anywhere** — check the view and the DTO (S-34, F12.11). | | ⬜ | |
| 14.7 | Start an attempt on a Java execution, then open the Java bot; then open the Algebra bot. | The exam's own course bot is locked for that student. Another course's bot shows the integrity notice first; proceeding notifies the executing teacher and flags the monitor row (C-4, F6.8, F11.1). | | ⬜ | |

---

## 15 — Client-server on separate machines, JARs, connect GUI · T-15

**PRD:** F13.1–F13.4, F14.1–F14.2 · **Spec:** S-39, S-40, S-41, S-42

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 15.1 | On machine A, run `G<Num>_Server.jar` from a terminal. On machine B, **double-click** `G<Num>_Client.jar`. | Server runs terminal-only with structured logs **and** opens its console. Client launches by double-click (F14.1). | | ⬜ | |
| 15.2 | Also start the client with `java -jar` from a terminal. | Works identically (T-15, F14.1). | | ⬜ | |
| 15.3 | On the client connect screen, use the discovery picker; then enter host/port manually. | Discovery lists the server with name, address and fingerprint. Manual entry is **always** available and one click away — discovery failing never blocks connecting (F1.5, F13.4). | | ⬜ | |
| 15.4 | Connect from machine B to machine A over the LAN, then sign in and use the app. | Full round trip over TCP/IP on a LAN, GUI client (not web), separate machines (S-40, S-42). | | ⬜ | |
| 15.5 | Restart the client. | Last server remembered and auto-connected; a changed fingerprint raises a prominent warning requiring explicit confirm (F13.4). | | ⬜ | |

---

## 16 — Concurrent users; no double login · T-16

**PRD:** F1.3, F1.4 · **Spec:** S-40

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 16.1 | Sign in as `dana.cohen` on machine A. Sign in as `dana.cohen` on machine B. | Machine B is refused with a clear message ("This account is already signed in elsewhere") that reveals no further detail (F1.3). | | ⬜ | |
| 16.2 | Sign out on machine A, then retry on machine B. | Succeeds. | | ⬜ | |
| 16.3 | Sign in on A again, then **kill** the client process. Immediately retry on B. | Succeeds — the socket drop frees the session immediately, without waiting for a timeout (F1.3). | | ⬜ | |
| 16.4 | Sign in as four different users at once (`dana.cohen`, `rina.barak`, `maya.levi`, `principal.avia`) and use the app concurrently. | All four work simultaneously and independently (T-16, S-40). | | ⬜ | |

---

## 17 — Test data prepared in the database · T-17

**PRD:** NFR-17, §5 · **Content:** `docs/seed/SEED_CONTENT.md`

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 17.1 | On a fresh empty database, start the server. | Flyway migrations run automatically; the seed is loaded by one command or one console button (F14.2, E2.15). | | ⬜ | |
| 17.2 | Load the seed a second time. | Idempotent — no duplicate rows, no constraint failures. | | ⬜ | |
| 17.3 | Open every demoed screen in turn. | None looks empty or fake: bank populated, exams in mixed states, one execution fully graded with a spread histogram, bot sessions present, notification bell non-zero (PRD §5, E2.16). | | ⬜ | |

---

## 18 — Efficient computing, no user-initiated refresh · T-18

**PRD:** NFR-18, F11.1–F11.3 · **Spec:** S-44

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 18.1 | Search every screen for a "Refresh" / "Reload" control. | **None exists anywhere** (NFR-18). | | ⬜ | |
| 18.2 | With `dana.cohen`'s Approvals queue open, have another teacher submit an exam for approval. | The queue updates live, pushed — without any user action. | | ⬜ | |
| 18.3 | With a student mid-attempt, extend the time from the teacher monitor (as in 7.1). | The student's timer updates live (F7.1). | | ⬜ | |
| 18.4 | Approve a grade while the student has My Grades open. | The grade appears live, with a notification (F8.4, F11.1). | | ⬜ | |
| 18.5 | Open a long bank list with many illustrated questions. | Images load lazily and the list pages; the UI never blocks while loading (NFR-18). | | ⬜ | |

---

## 19 — Flexible, change-tolerant design · T-19

**PRD:** NFR-19 · **Spec:** S-37, S-43, S-45

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 19.1 | **Defense rehearsal:** "Add a new report type." | One new Strategy class + a menu entry (F9.4, S-37). Demonstrate in the code, do not just assert it. | | ⬜ | |
| 19.2 | **Defense rehearsal:** "Swap the network protocol for REST." | One new implementation of `IClientConnection`; no UI change. The Adapter boundary is real and nameable. | | ⬜ | |
| 19.3 | **Defense rehearsal:** "Swap the bot provider." | Provider-adapter chain: a new adapter class; keys stay server-side (F12.6). Phase-2 internet access (S-43) is a deployment change, not a redesign. | | ⬜ | |

---

## 20 — Reuse; use of design patterns · T-20

**PRD:** NFR-20 · **Reference:** `PLAN.md` §2 pattern table, `DECISIONS.md`

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 20.1 | For each pattern claimed in `PLAN.md` §2, point at the class that implements it. | Every claim resolves to real code: Adapter (`IClientConnection`), Singleton (`ScreenManager`, session factory), Template Method (screen lifecycle), DAO/Repository, Strategy (reports, validators, bot providers), Observer (EventBus + server push), State (exam/execution/grade lifecycles), Command (protocol verbs). | | ⬜ | |
| 20.2 | Check that patterns are named in Javadoc where used. | Named at the boundary classes, not only in the document (NFR-20). | | ⬜ | |
| 20.3 | Point at the reused pieces: one component library across all screens; one histogram component in results **and** reports. | Demonstrable reuse, not copy-paste (F9.2, F9.4). | | ⬜ | |

---

## 21 — UI quality and friendliness · T-21

**PRD:** NFR-21, §4 · **Spec:** S-44

| # | Steps | Expected result | Actual | Status | Bugs found |
|---|---|---|---|---|---|
| 21.1 | Walk every screen looking for lists. | Lists are used wherever a set is shown; each has an empty state that explains rather than showing a blank box. | | ⬜ | |
| 21.2 | Trigger a slow operation (large report, bot call). | Progress feedback appears — spinner, skeleton or overlay. The UI never appears frozen. | | ⬜ | |
| 21.3 | Trigger failures: disconnect the server mid-action; submit an invalid form. | Every failure shows a **human** message, never a stack trace or an error code. A reconnect banner appears on disconnect. | | ⬜ | |
| 21.4 | Complete successful actions: save a question, approve an exam, submit an exam. | Each confirms success visibly (toast or success screen). | | ⬜ | |
| 21.5 | Switch theme light ↔ dark and change the accent palette. | Everything re-themes consistently; nothing becomes unreadable. | | ⬜ | |
| 21.6 | Resize the window to three sizes; view a Hebrew question and an English question side by side. | Layout holds at all three sizes. Hebrew renders correctly RTL, English LTR, in the same screens (X-I18N). | | ⬜ | |

---

---

## Bugs found

Assignment 3 §1 asks for the bugs found **and which test case exposed them**, so every entry
names its case. Ids are `B-n` and are what the `Bugs found` column cites.

| # | Found by | Severity | Status | What |
|---|---|---|---|---|
| B-1 | case 1.1 | Low (docs) | **Fixed** | `docs/DEMO_ACCOUNTS.md` presented its accounts as working credentials with nothing saying they only exist once the E2.15 seed loader has run. On a freshly migrated database every login failed with F1.1's deliberately generic message, so there was no way to tell "seed not loaded" from "wrong password". **Fixed before the 2026-08-22 re-run:** the file now carries "⚠ These accounts do not exist until the seed has been loaded", names `RepositoryUserDirectory` as the authority, and adds the diagnostic — load the seed before suspecting the credentials. Verified by reading the file; case 1.1 then passed against a reseeded database. Never a code defect: the server was behaving as specified. |

| B-2 | server start-up, 2026-08-22 re-run | Low (cosmetic) | Open | The server prints a red `ERROR` line about thirty seconds into start-up: `Log4j2 could not find a logging implementation. Please add log4j-core to the classpath. Using SimpleLogger to log to the console...` Something on the classpath — most likely a transitive dependency of the bot SDK — uses the log4j2 API with no binding present, and log4j2 falls back to its own SimpleLogger. **Nothing is broken:** the server’s own logging is logback and every subsequent line appears normally. It matters because this is the terminal that is visible during the defence, and a red ERROR line invites a question that costs more time to answer than to prevent. Appears on the `SeedMain` path too. **Fix:** either add `log4j-to-slf4j` so the API routes into logback, or exclude the log4j2 API from the dependency that drags it in. Neither is urgent; both are small. |

| B-3 | case 8.4 | **Medium** | **Fixed** | **`teacherComment` could be read but never written.** It was on both wires, both copy classes and both screens, and the only thing that ever set it was the seed loader — no request DTO carried a comment field and no service called `Grade.setTeacherComment`. **S-22 had no path through the application:** a teacher could not leave a student the one piece of free text the student is allowed to read, and acceptance case 8.4 could not pass. The seed masked it — `yael.azulay` has a seeded comment, so every screen rendered one and looked finished; it was visible only on trying to *write* one. **Fixed by the lead, 2026-08-23, as GRADING amendment A3:** the comment now rides `GradeOverrideRequest`, which was option A of the two put forward. Found by walking case 8.4 rather than by reading code — every unit test on that path passed throughout. |
| B-4 | case 8.5 | Low | **Fixed** | **Select-all updated the session’s selection but not the table’s.** The button enabled and the confirmation counted correctly, so the right rows were approved — but a teacher could not see what she was about to approve, on the one action that cannot be undone. **The first fix was wrong and made it worse**, and is recorded here rather than quietly replaced: driving the table’s selection from the session inside `render()` broke row selection outright, because the listener clears the session before rebuilding it, that clear triggers a render, and the render wiped the table’s selection out from under the listener’s own iteration. A second cause was underneath it: `DataTable.setItems` is a clear-then-add, and JavaFX drops the selection when a backing list is cleared, so refilling on every render wiped it too. **Actual fix:** selection flows one way — table → session, never back — with Select-all setting the table’s own selection, and `render()` refilling the lists only when their contents changed. Verified by clicking, not by a build: the build was green for the broken version. | ✅ | |
| B-5 | case 8.6 / screenshot | Low (cosmetic) | **Fixed** | **My Grades truncated the Approved column to "23 Au…".** The table divided its width evenly, so a date got the same room as a two-character course code, and the column a student reads to know when her grade arrived could not show a date. Header "Teacher’s note" was clipped for the same reason. Found by looking at the rendered screen; the copy tests format the date correctly and never see a column. **Fixed:** preferred widths per column, sized to content, still adaptive. |

| B-6 | case 9.3 | Low | **Fixed** | **`.results-print` was a style class with no stylesheet rules**, so the print toggle was a silent no-op — on the student’s checked form (E13.5) **and on the teacher’s results screen (E14.4, which was ticked done)**. Both add the class in Java; nothing in `hsts.css` matched it. E15’s own print rules describe themselves as "the same modest thing E14.4 built", which is what sent me looking — the idiom was written for E15 and never back-filled. **Fixed:** rules written in E15’s style, flattening the cards and dropping the chrome, covering both screens from the one class. Found by clicking a toggle; no test can see a stylesheet that matches nothing. |
| B-7 | case 2.4 | **Medium** | **Fixed by the acceptance-fixes batch (2026-08-26)** | **A minus sign was invisible to the answer-distinctness rule, so a maths question whose distractors differ only by sign could not be saved.** `QuestionValidator.sameAnswer` compares folded forms with a `Collator` at `PRIMARY` strength, and Java's collation makes the hyphen-minus *ignorable* at that strength. So `sameAnswer("1", "-1")` returned **true**, and so did `("2, 3", "-2, -3")`, `("cos(x)", "-cos(x)")` and `("(3, 4)", "(-3, 4)")`. MySQL under `utf8mb4_unicode_ci` returns **0** for every one of them, so the validator was **stricter than `ck_question_versions_distinct`, the constraint its own javadoc says it stands in for**, in the direction that refuses questions the database would happily store — and had itself stored: **five seeded questions could not be written back through `QUESTION_UPDATE` at all** (`11005, 11006, 11008, 12005, 12007`). A teacher editing only the stem of one of them met a refusal about answers she never touched. The class argued that direction was the safe one — "the worst case is a teacher told two confusingly similar answers are too similar" — and on this evidence it does not hold: sign-differing distractors are the normal shape of a mathematics bank, not an edge case. **Why nothing caught it:** `BankRoundTripIntegrationTest` already asserted the two verdicts match in **both** directions, but no sign-differing pair was in its `@CsvSource`. **Fix:** the fold now substitutes a distinct sentinel for each collator-ignorable dash, before NFKD. The set was measured rather than guessed — every defined BMP code point was asked of `Collator` at PRIMARY and exactly eight answered "ignorable" (`U+002D`, `U+2010`–`U+2015`, `U+2212`), while MySQL called **none** of the ASCII punctuation family ignorable; brackets and commas were never the problem. All 28 pairs among the eight were then put to MySQL: 27 answered *different*, and exactly one — `U+2010` vs `U+2011` — answered *equal*, so the sentinel table has seven values for eight dashes and that repeat is a measurement. Twelve `@CsvSource` rows and a five-case create-and-stem-edit round trip now pin it against real MySQL. Recorded in `docs/PROBLEMS.md` as **P-12**. |
| B-8 | case 2.6 / case 2.1 | Low (demo content) | **Open — ticket: the ten seed images** | **No question in the seeded bank carries an illustration**, so the "scroll a list with illustrated questions" half of case 2.6 cannot be walked and the demo bank has no pictures in it. The seed document marks **10** questions `img=yes` (PRD §5); the database after a reseed holds **45 question versions and 0 images**. This is deliberate and documented — `QuestionBankSection`'s javadoc says "Illustrations load as NULL… the bytes arrive later under `docs/seed/img/`", and keeps the flag in the data so the count stays assertable — but it has no owner and no ticket, and it is the kind of gap noticed on stage rather than in a build. The plumbing on both sides is proven working: a question created with a PNG round-tripped through `QUESTION_CREATE` → `QUESTION_IMAGE_GET` as `image/png, 67 bytes`, and `BankQuestionRow` carries a `hasImage` flag and no bytes, so the lazy-loading claim holds structurally. **Fix is ten small images under `docs/seed/img/` and the loader reading them**, which the loader is already shaped for. Deliberately not attempted in the fixes batch: it is content, not code, and it belongs to the seed's owner. |
| B-9 | case 3.4 | Low (test case) | **Fixed (2026-08-26) — the case was rewritten** | **Case 3.4 named a topic the seed does not have and a count no Algebra topic can satisfy.** It asked for "5 questions from topic **משוואות ליניאריות**". The seed's Algebra topics are `Linear equations`, `Quadratic functions` and `Inequalities` (§7.1) — all English, per the 2026-08-23 English-everywhere ruling — so the request answered a shortfall of `requested=5 available=0`. Asked for `Linear equations` instead it answered `requested=5 available=4`: that topic holds four questions, and **no** Algebra topic in the seed holds five. So the case could not pass as written, and it would have read on the day as the auto-composer failing while the auto-composer was answering correctly. Every case in this table is supposed to name an actual row; this one named a row that is not there. **Fixed in the case, not in the code**: step 3.4 now reads "4 questions from topic `Linear equations`, mixed difficulty", which keeps the case's "from a topic" shape and is satisfiable. It was probed and passes, proposing a composition already totalling 100 points. |
| B-10 | case 5.6 | **Medium** | **Fixed by the acceptance-fixes batch (2026-08-26)** | **The seed's "scheduled today" execution was only genuinely scheduled if the seed was loaded before 14:00 UTC.** `SEED_CONTENT.md` §9 pinned execution 3 (`5164`) to `T+0 14:00 → 16:00`, resolved by `SeedTimes.dayOffsetAt` as **wall-clock UTC on the anchor's date**. Loaded at 18:54 UTC its window came out `14:00Z → 16:00Z` — already over — while the row is stored `SCHEDULED`. Nothing is wrong until the server runs: `ReleaseScheduler.tick()` was called once and **changed 2 releases**, taking `5164` `SCHEDULED → LIVE → CLOSED` inside a single 30-second pass. The scheduler is correct; the fixture was not. **What it cost:** every case written against a scheduled sitting evaporated within thirty seconds of the server starting for any session after 14:00 UTC — **5.6** (cancel a SCHEDULED release), **6.4** (enter the scheduled code before its open time), **10.5** (results for `5164` with no attempts), and hardening item H14.1. In local terms the fixture was correct only before **17:00 Israel time**, which is not a safe assumption for a defence slot. **Why nothing caught it:** no seed test ticked the scheduler, and `SeedLoadedTestBase` pins its anchor to `2026-08-20T15:30Z` — itself *inside* the 14:00–16:00 window — so the fixture was already not-future in the canonical test and no assertion would have noticed. **Fix:** both windows the demo needs to be *happening* are now resolved from the load **instant** rather than its date — execution 4 opens `now−30m` and closes `now+90m`, execution 3 opens `now+3h` and runs 2h. The closed and graded fixtures keep their wall-clock form, which is right for something historical. `SeedLoadedDbContract` now asserts the **direction** (a SCHEDULED sitting opens after the anchor, and after the live one closes) rather than only the duration, and the 15:30Z anchor deliberately stays in the afternoon so that guard is exercised. §9 of `SEED_CONTENT.md` rewritten to distinguish the two meanings of `T`. |
| B-11 | case 4.4 | **Medium** | **Fixed by the acceptance-fixes batch (2026-08-26)** | **`NOTIFICATIONS_GET` failed outright on a freshly seeded database — every staff account the seed gives a notification to had a bell that would not open.** Observed, not inferred: the verb answered `INTERNAL` for `dana.cohen`, `rina.barak`, `tamar.shani` and `avi.mizrahi`, while `michal.sharon` — the one staff account with no seeded notification — opened fine with `NotificationsPage[items=[], unreadCount=0]`. **One unparseable row took the whole page down**, so the well-formed `APPROVAL_REJECTED` row case 4.4 had just written was unreadable beside two seeded ones. Cause: `JpaNotificationStore.toDto` mapped the stored string with `NotificationType.valueOf`, and six of the eight rows `NotificationsSection` seeded were not constants of that enum. **Two different mistakes underneath one symptom, fixed two ways.** Four were spelling — `EXAM_REJECTED` ×2 → `APPROVAL_REJECTED`, `EXAM_PENDING` → `APPROVAL_REQUESTED`, `APPROVAL_REQUEST` → `APPROVAL_REQUESTED`. Two named events the enum had **no constant for at all**, so `GRADING_DUE` and `EXECUTION_CLOSED` were added to `NotificationType` rather than re-pointed at a type meaning something else; re-pointing the principal's "sitting finished" row at `GRADE_PUBLISHED` would have swapped a crash for a wrong icon and a wrong toast, which is worse for being invisible. The enum's own contract permits additions and forbids renames, and `NotificationPresenter.iconFor` is an exhaustive switch, so the compiler forced a rendering decision for both. The seed record now holds the **enum**, not a string, so the class of defect cannot recur. **Read path hardened too (the #49/#51 lesson):** a row whose type does not parse is skipped and logged at ERROR with its id and the offending string, so one bad row costs that row and not the page; the unread badge deliberately still counts it, because a badge larger than the list is a visible symptom and quietly adjusting it would hide the next occurrence. **Why nothing caught it:** the seed tests asserted rows against the document and both carried the same wrong strings, and the notification tests build rows through `NotificationService`, which writes `type.name()` and therefore always round-trips. Two new tripwires join them: a store test with a hostile type string proving the page survives minus that row, and a seed test asserting every seeded type string parses. |
| B-12 | cases 4.2, 5.4 | Low (copy) | **Fixed by the acceptance-fixes batch (2026-08-26)** | **A role-refusal sentence leaked a Java array literal.** `Authorization.describe` built the multi-role branch with `Arrays.toString(allowed)`, so a student who reached any staff verb was told *"This action requires one of the roles **[TEACHER, COORDINATOR]**."* — square brackets and enum constants in a sentence a user reads, which is the "never an error code" rule in PRD §4.1 and the thing case **21.3** looks for. `Arrays.toString` is a debugging aid; it renders a Java array, not English. **Fix:** the sentence now reads *"This action requires the TEACHER or COORDINATOR role."* — commas until the last join, which is "or", and the same shape as the single-role branch it used to diverge from. The role names stay upper case deliberately: they are the words `DEMO_ACCOUNTS.md` and every screen use. `AuthorizationTest` now pins the sentence in full **and** asserts no bracket appears in it, which is the half that would have failed before; the previous assertions only checked that the message *contained* the role names, which the array literal also did. **The second half of the original finding is not fixed and is not this batch's**: the single-role branch is *wrong* on `EXAM_PREVIEW_GET`, which admits `TEACHER` **and** `COORDINATOR` and refuses on *subject*, so a plain teacher who is not the author is told she needs a role — when the author, a plain teacher, may open it perfectly well. That is a change to which sentence a verb answers with, not to how a list is rendered; raised for the lead. |
| B-13 | cases 4.1, 4.2, 5.2 | Low (docs) | **Open** | **The seeded exam names and teacher notes do not match `SEED_CONTENT.md`.** §8 writes *"Midterm — Algebra"*, *"Midterm — Calculus"*, *"Quiz — Inequalities"* and *"Marking note: question 7 — accept a reasoned graphical solution too."*; `ExamsSection` loads *"Midterm: Algebra"*, *"Midterm: Calculus"*, *"Quiz: Inequalities"* and *"Marking note: question 7, accept a reasoned graphical solution too."* The substitution is deliberate — §4.1's copy rules ban em dashes and permit a comma, a period or a colon — and `SeedDocument.followsHouseRule` is written to accept exactly that, which is why the seed tests are green. But it is recorded nowhere a reader would find it, and this file's own key-rows line uses a third spelling, *"Algebra Midterm"*. **What it costs:** any case that quotes an exam name as an expected value reads as a mismatch, and the acceptance evidence in the walk reports has to quote the loaded string rather than the documented one. **Fix:** one editing pass over `SEED_CONTENT.md` §8 and §8.2 to match what the loader writes, plus a line saying why. Not a code defect, and deliberately left to the seed's content owner rather than folded into a fixes batch. |

### Not bugs, recorded so they are not re-investigated

- **Scenario 1's blocked cases are blocked, not failing.** The server is correct; the database is
  empty because E2.15 has not merged. They become runnable the moment the seed loads, and nothing
  about them needs re-designing first.

## Notes for the submission document

- The **Bugs found** column is what Assignment 3 §1 asks for — "any bugs found + which test case
  exposed them". Fill it as testing happens, not retrospectively; a bug with no test case number
  next to it is worth less in the write-up.
- Cases marked ⛔ blocked are features not yet built. Blocked is a legitimate status during
  development and an illegitimate one at submission.
- Several cases deliberately test **server-side** enforcement by replaying a request rather than
  clicking (2.3, 9.4, 11.4, 6.7). Those are the ones that answer "could a student cheat?", which
  is where v1 lost marks. Do not downgrade them to UI checks.

---

# Hardening — edge cases for E12–E15 (Member B)

**Deliverable 3.** PRD §6's Grading and Reports lines expanded into concrete test ideas, and the
gaps that pass exposed. Each item becomes a test when its epic lands, so tests are written with
the feature rather than backfilled in E21.

> **Ownership changed after this was written (PR #2 review, 2026-08-19).** E14 (StatChart) and
> E15 (report engine) moved off Member B. **H12.\* and H13.\* stay with Member B**; **H14.\* and
> H15.\* now belong to whoever owns E14/E15** and are kept here only so they are not lost in the
> handover. Two of them carry decisions the whole team is bound by — **H14.4** (σ divisor) and
> **H15.2** (CANCELLED excluded from reports) — so they need a real owner, not just a home.
>
> Member B still produces the numbers H14.4 checks: **E12.4** computes and stores the statistics;
> E14 only displays them.

Ids are `H<epic>.<n>` so they never collide with the scenario cases above. These are **not**
counted in the 115 — the outline table is what we submit; this is how we get it green.

**Source** column: `§6` = verbatim from the PRD §6 catalog · `gap` = not in the catalog, added
here. Every `gap` row is a claim that PRD §6 under-covers my epics; see the note at the end.

## E12 — Grading

| # | Source | Given / When / Then |
|---|---|---|
| H12.1 | §6 | **Given** an approved grade, **when** the teacher edits the score without entering a justification, **then** the save is refused server-side — not merely disabled in the UI (S-23). |
| H12.2 | §6 | **Given** a set of grades already approved, **when** the teacher approves them again, **then** the operation is idempotent: no duplicate audit rows, no second notification to the student. |
| H12.3 | §6 | **Given** `maya.levi` signed in, **when** she requests a grade id belonging to `omer.katz` by replaying the request, **then** the server answers with an authorization error and no grade data (F9.1). |
| H12.4 | gap | **Given** an attempt that timed out with **zero** answers saved, **when** auto-check runs, **then** the score is 0 — not null, not an error, and the attempt still appears in the results table. |
| H12.5 | gap | **Given** an attempt with some questions unanswered, **when** auto-check runs, **then** unanswered questions score 0 and the total equals the sum of the answered ones (F6.9's "unanswered score 0" promise must be true server-side, not just stated in the dialog). |
| H12.6 | gap | **Given** exam 101101 v2 pins question 11005 at **version 1**, **when** auto-check grades an attempt, **then** it compares against version 1's correct answer — never the latest version. A question edited after release must not change past grades (C-2). |
| H12.7 | gap | **Given** an attempt still `IN_PROGRESS`, **when** the teacher tries to approve its grade, **then** it is refused: nothing is gradeable before it is submitted or timed out. |
| H12.8 | gap | **Given** `avi.mizrahi` did not write exam 101101, **when** he tries to approve grades for execution 4821, **then** the server refuses — grade approval belongs to the exam's author (T-8.2 read with S-35). |
| H12.9 | gap | **Given** a manual override from 51 to 55, **when** the stored record is inspected, **then** the original auto score, the new score, the reason and the actor are all present — an override that loses the original is an audit failure (F8.3). |
| H12.10 | gap | **Given** two teachers of the same course open the same student's grade, **when** both save a change, **then** the second is rejected with a conflict rather than silently overwriting (F10.3, F10.4 names grading explicitly). |

## E13 — Student results

| # | Source | Given / When / Then |
|---|---|---|
| H13.1 | gap | **Given** execution 7390 is auto-checked but **not approved**, **when** `maya.levi` opens My Grades, **then** the exam is absent or shown explicitly as not-yet-published — never a visible score (C-3, S-24). |
| H13.2 | gap | **Given** a student who has sat no exams, **when** she opens My Grades, **then** an empty state explains rather than showing a blank panel. |
| H13.3 | gap | **Given** `omer.katz`'s TIMED_OUT attempt, **when** he opens the result, **then** the checked form renders normally and his solving time is shown — a timed-out attempt is a result, not an error state (S-19). |
| H13.4 | gap | **Given** the checked form is open, **when** its payload is inspected, **then** it contains correct answers **only for the questions in this student's own attempt** — the review DTO is not a route to the whole bank's answer key. |
| H13.5 | gap | **Given** a grade is approved while the student has My Grades open, **when** approval completes, **then** the row appears live with no refresh (NFR-18, F8.4). |

## E14 — Teacher results & statistics

| # | Source | Given / When / Then |
|---|---|---|
| H14.1 | §6 | **Given** execution 5164 with no participants, **when** the teacher opens its results, **then** statistics read N/A and the histogram shows an insufficient-data state — no divide-by-zero, no empty chart frame. |
| H14.2 | §6 | **Given** an execution with exactly one participant, **when** statistics are computed, **then** median equals the average, σ is 0, and the histogram renders one bucket without collapsing. |
| H14.3 | gap | **Given** an execution where every student scored the same, **when** the histogram renders, **then** one full-height bucket with σ = 0 and the mean/median/±1σ markers coincident — the marker overlay must not misdraw when they stack. |
| H14.4 | gap | **Given** the seeded execution 4821, **when** E14 recomputes statistics, **then** they equal the stored ones exactly: Mean 72.5, median 72.5, σ 17.5. **σ uses the population divisor `n`.** A sample divisor gives 18.71 and would read as a bug. |
| H14.5 | gap | **Given** `dana.cohen` wrote exam 101101 and another teacher ran an execution of it, **when** she opens Results, **then** that execution appears — she sees every execution of exams she wrote, not only her own (S-35). |
| H14.6 | gap | **Given** the results table for 4821, **when** it is read, **then** it holds exactly 8 rows and they match the seeded Algebra roster — a participant count that disagrees with the attempt rows means the derived counts (F7.3) drifted. |
| H14.7 | gap | **Given** the histogram in count mode, **when** toggled to percentage, **then** the buckets sum to 100% and no bar changes relative height. |

## E15 — Principal views & report engine

| # | Source | Given / When / Then |
|---|---|---|
| H15.1 | gap | **Given** `principal.avia` signed in, **when** any mutating verb is replayed with her session, **then** the server refuses — the role has zero mutating verbs authorized, not merely hidden buttons (F9.3, S-7). |
| H15.2 | gap | **Given** a CANCELLED execution, **when** any report runs, **then** it is excluded from the corpus. ARCHITECTURE §5 says CANCELLED executions are excluded from statistics; a zero-participant row would skew every average (F5.5). |
| H15.3 | gap | **Given** stored statistics exist for 4821, **when** a report displays its average, **then** the figure comes from the **stored** stats (S-25), not a fresh computation — two code paths producing 72.5 and 77.9 is the failure this prevents. |
| H15.4 | gap | **Given** the same-student comparison, **when** the student sat exams in different courses, **then** the report groups correctly and does not silently average across incomparable exams. |
| H15.5 | gap | **Given** a teacher with exactly one execution, **when** the same-teacher comparison runs, **then** it renders a single-series result rather than an error or a blank comparison. |
| H15.6 | gap | **Given** the report engine, **when** a new dimension is added, **then** it requires one new Strategy class and a menu entry and nothing else — verified by actually adding a throwaway one, not by assertion (S-37, NFR-19, and the T-19 defense question). |

## Note for the reviewer — PRD §6 under-covers E12–E15

PRD §6 gives my four epics **five** lines: three under Grading, two under Reports. There is no
Results line at all, so student results (E13) and teacher results (E14) have no catalog entries —
the only E13-adjacent item, "student polls another student's grade id", sits under Grading.

By comparison the catalog gives Bot nine items and Discovery five. That asymmetry is not
proportional to risk: E12–E15 own grade correctness, and a wrong grade that looks plausible is
harder to notice at a defense than a bot that fails visibly.

The 23 `gap` rows above are my proposed coverage. Three constrain other people's code, and the
PR #2 review **accepted all three for PRD §6**:

- **H12.6** — grading must use the question **version pinned in the exam**, never the latest.
  This constrains E6 and E7, not just E12.
- **H14.4** — the σ divisor. Binds the seed, E14 and E15 to the same choice.
- **H15.2** — CANCELLED executions excluded from the report corpus. Constrains E9's release
  handling as much as E15's engine.

**Status:** accepted in review; the PRD edit is Naji's, not mine. As of commit `14bc23f` the
wording is not yet on `main` — `PRD.md` §6's Grading and Reports lines are unchanged and F8.5
still says "standard deviation" with no divisor named. Until that lands, these three constraints
live only here and in the review thread. Flagged, not blocking: **E12.4** needs the σ divisor
decision to be findable by whoever writes it, and that is me.
