# Seed content (E2.15 / E2.16) — the demo dataset

**Owner:** Member B (content) · **Implements:** Member A (loader, E2.15) · **Reviewer:** Naji
**Spec:** PRD §5 "well-filled, not overstuffed" (NFR-17) · Schema: `ARCHITECTURE.md` §5

This is the **content**, not the loader. Every table below maps to one schema table so the
loader is a transcription job, not a design job. Ids are explicit and stable — the
acceptance tests, `DEMO_ACCOUNTS.md` and the demo script all reference them by number.

## Rules this content obeys

| Rule | Source | Where it bites |
|---|---|---|
| `national_id` is **UNIQUE and NOT NULL** | ARCHITECTURE §5 (E2 PR1 review) | Every user below has a distinct one — teachers and the principal included |
| `users.role` is `ENUM('STUDENT','TEACHER','PRINCIPAL')` | `V1__core.sql` | **There is no COORDINATOR role.** A coordinator is a TEACHER plus a `coordinators` row |
| One coordinator per subject | `coordinators` PK is `subject_code` alone (S-1) | Exactly 2 coordinator rows, one per subject |
| Exactly one correct answer, answers pairwise distinct | C-8 / ADR-016 | Every question below |
| Points sum to 100 per exam version | §5 (service rule, not DDL) | Checked per exam below |
| Passwords BCrypt-hashed | PRD §5 | Plaintext here is **demo-only**; the loader hashes at insert |
| All `DATETIME` values are UTC | migration README | Relative times below ("T−14d") resolve at load time |

> **National ids are checksum-valid Israeli ת"ז.** S-18 has a student type this to start
> an attempt; if id validation is ever added, invalid demo data would break the demo
> rather than the code. Costing nothing now, so they are all valid.

---

## 1. Subjects (seeded, read-only — S-3)

| code2 | name |
|---|---|
| `10` | מתמטיקה |
| `20` | מדעי המחשב |

## 2. Courses (seeded, read-only — S-3)

| code2 | subject | name |
|---|---|---|
| `11` | `10` | אלגברה |
| `12` | `10` | חשבון דיפרנציאלי ואינטגרלי |
| `21` | `20` | תכנות מונחה עצמים ב-Java |
| `22` | `20` | בסיסי נתונים |

## 3. Users (18 — 1 principal, 5 teachers, 12 students)

Password convention is demo-only and uniform: `Hsts!2026`. The loader BCrypts it.

| id | username | full_name | role | national_id |
|---|---|---|---|---|
| 1 | `dana.almog` | דנה אלמוג | PRINCIPAL | `301548202` |
| 2 | `rina.barak` | רינה ברק | TEACHER | `214703951` |
| 3 | `yossi.mizrahi` | יוסי מזרחי | TEACHER | `248190639` |
| 4 | `avi.cohen` | אבי כהן | TEACHER | `273056416` |
| 5 | `michal.sharon` | מיכל שרון | TEACHER | `296481724` |
| 6 | `tamar.levi` | תמר לוי | TEACHER | `315729046` |
| 7 | `noa.friedman` | נועה פרידמן | STUDENT | `338106727` |
| 8 | `itay.regev` | איתי רגב | STUDENT | `349251082` |
| 9 | `shira.dahan` | שירה דהן | STUDENT | `352074611` |
| 10 | `omer.katz` | עומר כץ | STUDENT | `361489206` |
| 11 | `maya.ben-david` | מאיה בן-דוד | STUDENT | `374301851` |
| 12 | `adam.peretz` | אדם פרץ | STUDENT | `385612098` |
| 13 | `yael.azulay` | יעל אזולאי | STUDENT | `390745362` |
| 14 | `daniel.shapira` | דניאל שפירא | STUDENT | `402186936` |
| 15 | `lior.gabay` | ליאור גבאי | STUDENT | `413860529` |
| 16 | `tal.harari` | טל הררי | STUDENT | `425097185` |
| 17 | `roni.malka` | רוני מלכה | STUDENT | `436712400` |
| 18 | `eitan.solomon` | איתן סולומון | STUDENT | `448521062` |

## 4. Course teachers (`course_teachers`)

| course | teacher | note |
|---|---|---|
| `11` | 2 rina.barak | |
| `12` | 3 yossi.mizrahi | |
| `21` | 4 avi.cohen | |
| `21` | 6 tamar.levi | co-teacher (PRD §5) — proves a course with two teachers |
| `22` | 5 michal.sharon | |

## 5. Coordinators (`coordinators`) — 2 rows, one per subject

| subject_code | teacher |
|---|---|
| `10` | 2 rina.barak |
| `20` | 4 avi.cohen |

> **rina.barak is a TEACHER row + this coordinator row** — not a COORDINATOR role.
> She teaches Algebra (11) *and* coordinates Mathematics (10), so she approves
> yossi.mizrahi's Calculus exam while authoring her own. That is the intended demo:
> the approver is a peer, not an admin.

## 6. Enrollments (`enrollments`) — each student in 2–3 courses

| student | courses |
|---|---|
| 7 noa.friedman | 11, 21 |
| 8 itay.regev | 11, 12, 21 |
| 9 shira.dahan | 11, 22 |
| 10 omer.katz | 11, 21, 22 |
| 11 maya.ben-david | 11, 12 |
| 12 adam.peretz | 11, 21 |
| 13 yael.azulay | 11, 12, 22 |
| 14 daniel.shapira | 11, 21 |
| 15 lior.gabay | 12, 21, 22 |
| 16 tal.harari | 12, 22 |
| 17 roni.malka | 21, 22 |
| 18 eitan.solomon | 12, 21, 22 |

Per-course totals: **11 → 8 students · 12 → 6 · 21 → 8 · 22 → 7.**
Algebra's 8 is deliberate: it is the fully-graded execution, and 8 grades spread across
5 deciles is what makes the F9.3 histogram look like a real class rather than a stub.

---

## 7. Question bank (40 questions)

`display_id5` = course(2) + serial(3) — S-8. Every question is 4 answers, exactly one
correct, all four pairwise distinct (C-8 / ADR-016). **Correct** column is the answer
index 1-4. **Img** = has an illustration (10 total, PRD §5).

Language is mixed on purpose: Algebra and Calculus are Hebrew (RTL must be proven),
Java and Databases are English (code and SQL read badly reversed). Both appear in every
demoed screen.

### 7.1 Algebra (course 11) — 11 questions

Topics: משוואות ליניאריות · פונקציות ריבועיות · אי-שוויונות

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 11001 | משוואות ליניאריות | EASY | פתרו: `3x + 6 = 18` | `x = 4` | `x = 6` | `x = 2` | `x = 12` | 1 | |
| 11002 | משוואות ליניאריות | EASY | פתרו: `5x - 7 = 2x + 8` | `x = 3` | `x = 5` | `x = 15` | `x = 1` | 2 | |
| 11003 | משוואות ליניאריות | MEDIUM | לאיזה ערך של `k` למערכת `2x + ky = 4`, `4x + 6y = 8` יש אינסוף פתרונות? | `k = 2` | `k = 6` | `k = 3` | `k = 12` | 3 | |
| 11004 | משוואות ליניאריות | HARD | סכום הספרות של מספר דו-ספרתי הוא 11. אם מחליפים את הספרות, המספר גדל ב-27. מהו המספר? | `47` | `38` | `56` | `29` | 1 | |
| 11005 | פונקציות ריבועיות | EASY | מהם שורשי `x² - 5x + 6 = 0`? | `1, 6` | `2, 3` | `-2, -3` | `0, 5` | 2 | yes |
| 11006 | פונקציות ריבועיות | EASY | מהו קודקוד הפרבולה `y = (x - 3)² + 4`? | `(3, 4)` | `(-3, 4)` | `(3, -4)` | `(4, 3)` | 1 | yes |
| 11007 | פונקציות ריבועיות | MEDIUM | כמה נקודות חיתוך עם ציר `x` יש לפרבולה `y = x² + 2x + 5`? | `שתיים` | `אחת` | `אף אחת` | `אינסוף` | 3 | yes |
| 11008 | פונקציות ריבועיות | HARD | הפרבולה `y = ax² + bx + c` עוברת דרך `(0,3)`, `(1,2)` ו-`(-1,6)`. מהו `a`? | `1` | `2` | `-1` | `3` | 1 | |
| 11009 | אי-שוויונות | EASY | פתרו: `2x - 4 > 6` | `x > 5` | `x > 1` | `x < 5` | `x > 10` | 1 | |
| 11010 | אי-שוויונות | MEDIUM | פתרו: `x² - 4 < 0` | `x < -2` | `x > 2` | `-2 < x < 2` | `כל x ממשי` | 3 | yes |
| 11011 | אי-שוויונות | HARD | לאילו ערכי `x` מתקיים `(x-1)/(x+2) ≥ 0`? | `x ≥ 1` | `x < -2 או x ≥ 1` | `-2 < x ≤ 1` | `x ≤ -2 או x ≥ 1` | 2 | |

### 7.2 Calculus (course 12) — 9 questions

Topics: גבולות · נגזרות · אינטגרלים

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 12001 | גבולות | EASY | חשבו: `lim(x→2) (x² - 4)/(x - 2)` | `0` | `4` | `2` | `לא קיים` | 2 | |
| 12002 | גבולות | MEDIUM | חשבו: `lim(x→∞) (3x² + x)/(x² - 5)` | `3` | `0` | `∞` | `1/3` | 1 |  |
| 12003 | גבולות | HARD | חשבו: `lim(x→0) sin(3x)/x` | `1` | `0` | `3` | `1/3` | 3 | |
| 12004 | נגזרות | EASY | מהי הנגזרת של `f(x) = x³`? | `3x²` | `x²` | `3x` | `x⁴/4` | 1 | |
| 12005 | נגזרות | EASY | מהי הנגזרת של `f(x) = sin(x)`? | `-sin(x)` | `cos(x)` | `-cos(x)` | `tan(x)` | 2 | |
| 12006 | נגזרות | MEDIUM | מהי הנגזרת של `f(x) = x·e^x`? | `e^x` | `x·e^x` | `(1 + x)·e^x` | `(x - 1)·e^x` | 3 |  |
| 12007 | נגזרות | HARD | לפונקציה `f(x) = x³ - 3x` יש מינימום מקומי בנקודה: | `x = 1` | `x = -1` | `x = 0` | `x = 3` | 1 | yes |
| 12008 | אינטגרלים | EASY | חשבו: `∫ 2x dx` | `x² + C` | `2 + C` | `x²/2 + C` | `2x² + C` | 1 | |
| 12009 | אינטגרלים | MEDIUM | חשבו את השטח מתחת ל-`y = x²` בין `x=0` ל-`x=3` | `9` | `27` | `3` | `6` | 1 | yes |

### 7.3 Java (course 21) — 11 questions

Topics: OOP Basics · Collections · Exceptions · **Recursion (the thin one)**

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 21001 | OOP Basics | EASY | Which keyword prevents a class from being subclassed? | `final` | `static` | `private` | `sealed` | 1 | |
| 21002 | OOP Basics | EASY | What is the default value of an uninitialised `int` field? | `null` | `0` | `undefined` | `-1` | 2 | |
| 21003 | OOP Basics | MEDIUM | A class implements two interfaces that both declare `default void run()`. What happens? | It compiles, the first interface wins | It compiles, the second interface wins | Compile error until the class overrides it | A runtime `AmbiguousMethodError` | 3 |  |
| 21004 | OOP Basics | HARD | Which statement about `equals` and `hashCode` is true? | Equal objects must have equal hash codes | Equal hash codes mean the objects are equal | `hashCode` must be unique for every object | Overriding `equals` alone is always safe | 1 | |
| 21005 | Collections | EASY | Which collection forbids duplicate elements? | `ArrayList` | `HashSet` | `LinkedList` | `ArrayDeque` | 2 | |
| 21006 | Collections | EASY | Which interface does `HashMap` implement? | `List` | `Set` | `Map` | `Queue` | 3 | yes |
| 21007 | Collections | MEDIUM | What is the average-case time complexity of `HashMap.get`? | `O(1)` | `O(log n)` | `O(n)` | `O(n log n)` | 1 |  |
| 21008 | Collections | HARD | Removing an element from an `ArrayList` inside a for-each loop throws: | `IndexOutOfBoundsException` | `ConcurrentModificationException` | `UnsupportedOperationException` | Nothing — it is safe | 2 | |
| 21009 | Exceptions | EASY | Which of these is a checked exception? | `NullPointerException` | `IOException` | `ArithmeticException` | `IllegalStateException` | 2 | |
| 21010 | Recursion | EASY | What does a recursive method need in order to terminate? | A base case | A `static` modifier | An enclosing loop | A `return null` statement | 1 | yes |
| 21011 | Recursion | MEDIUM | Recursion with no reachable base case fails with: | `OutOfMemoryError` | `StackOverflowError` | `IllegalStateException` | An infinite loop and no error | 2 | |

> ### The deliberately thin topic — do not "fix" it
> **Recursion has exactly 2 questions and no HARD one.** This is not an oversight: it is
> the fixture that lets F3.3 auto-generation be demoed *failing live*, without anyone
> touching the database mid-defense. Ask the builder for 3 Recursion questions, or for
> any HARD Recursion question, and it must report the shortfall and refuse to create the
> exam — T-3's note, "if there are not enough questions, the system reports this and does
> **not** create the exam". Every other topic has enough to succeed.

### 7.4 Databases (course 22) — 9 questions

Topics: SQL Queries · Normalization · Transactions

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 22001 | SQL Queries | EASY | Which clause filters rows *before* grouping? | `HAVING` | `WHERE` | `ORDER BY` | `LIMIT` | 2 | |
| 22002 | SQL Queries | EASY | Which join returns every row of the left table? | `INNER JOIN` | `LEFT JOIN` | `CROSS JOIN` | `SELF JOIN` | 2 | yes |
| 22003 | SQL Queries | MEDIUM | `COUNT(column)` differs from `COUNT(*)` because it: | Is always faster | Ignores NULLs | Counts distinct values only | Requires an index | 2 | |
| 22004 | SQL Queries | HARD | A join of two tables returns more rows than either table holds. The cause is: | A missing index | Duplicate values in the join key | A NULL in the ON clause | An implicit CROSS JOIN, always | 2 |  |
| 22005 | Normalization | EASY | First normal form requires every column to be: | Indexed | Atomic | Unique | Non-null | 2 | |
| 22006 | Normalization | MEDIUM | Removing a partial dependency on part of a composite key achieves: | 1NF | 2NF | 3NF | BCNF | 2 | yes |
| 22007 | Normalization | HARD | A table in 3NF but not in BCNF must contain: | A transitive dependency | A determinant that is not a candidate key | A repeating group | A surrogate key | 2 | |
| 22008 | Transactions | EASY | What does the "D" in ACID stand for? | Distributed | Durability | Deterministic | Deferred | 2 | |
| 22009 | Transactions | MEDIUM | Which isolation level still permits a phantom read? | SERIALIZABLE | REPEATABLE READ | READ COMMITTED | None of them | 3 | |

### 7.5 Second versions (T-2.2 — "the previous version remains in the bank")

Three questions ship with two versions, so "edit a question, the old version survives"
is demoable without editing anything first — and so a released exam can be seen pointing
at v1 while the bank shows v2.

| display_id5 | v1 → v2 change | why it is in the seed |
|---|---|---|
| 11005 | v2 rewords the stem to `מצאו את שורשי המשוואה x² - 5x + 6 = 0` (answers unchanged) | The Algebra Midterm's graded execution references **v1** — proof that a released exam is pinned to a version (S-14, C-2) |
| 21003 | v2 corrects answer 4: `AmbiguousMethodError` → `IncompatibleClassChangeError` | A correction that changes an *answer*, not just the stem |
| 22004 | v2 appends "(assume no NULLs in the join key)" to the stem | A clarification on a HARD question |

**Loader note.** `exam_version_questions` for the Algebra Midterm must reference question
11005 **version 1**. Everywhere else use the latest version. This is also the row that
exercises the new `question_id` + `UNIQUE(exam_version_id, question_id)` guard: 11005 v1
and v2 must never both land in one exam version.

---

## 8. Exams (6, in mixed states)

`display_id6` = subject(2) + course(2) + serial(2) — S-10. Every exam version's points
sum to **100** (service rule, §5). `status` lives on the *version*, not the exam.

| # | display_id6 | course | name | author | versions and status |
|---|---|---|---|---|---|
| 1 | `101101` | 11 | מבחן אמצע — אלגברה | 2 rina.barak | v1 **REJECTED**, v2 **APPROVED** |
| 2 | `101102` | 11 | בוחן — אי-שוויונות | 2 rina.barak | v1 **DRAFT** |
| 3 | `101201` | 12 | מבחן אמצע — חדו"א | 3 yossi.mizrahi | v1 **PENDING** (awaiting rina.barak) |
| 4 | `202101` | 21 | Java Fundamentals Exam | 4 avi.cohen | v1 **APPROVED** |
| 5 | `202102` | 21 | Collections Quiz | 6 tamar.levi | v1 **REJECTED** |
| 6 | `202201` | 22 | Databases Final | 5 michal.sharon | v1 **APPROVED** |

### 8.1 Composition

| exam | version | duration | questions (display_id5 → points) |
|---|---|---|---|
| 1 | v1 | 60 min | 11001→20, 11002→20, 11005 **v1**→20, 11009→20, 11010→20 |
| 1 | v2 | 75 min | 11001→15, 11002→15, 11005 **v1**→15, 11007→15, 11009→15, 11010→15, 11011→10 |
| 2 | v1 | 30 min | 11009→40, 11010→30, 11011→30 |
| 3 | v1 | 90 min | 12001→15, 12002→15, 12004→15, 12005→15, 12006→15, 12008→15, 12009→10 |
| 4 | v1 | 60 min | 21001→15, 21002→15, 21005→15, 21006→15, 21009→15, 21010→15, 21011→10 |
| 5 | v1 | 30 min | 21005→35, 21006→35, 21007→30 |
| 6 | v1 | 90 min | 22001→15, 22002→15, 22003→15, 22005→15, 22006→15, 22008→15, 22009→10 |

Each row sums to 100. Exam 1 v2 keeps 11005 at **version 1** deliberately (§7.5).

### 8.2 Texts and rejection reasons

| exam | student_text (S-3 general text) | teacher_text (teacher-only) |
|---|---|---|
| 1 | קראו כל שאלה עד הסוף. מותר השימוש במחשבון פשוט בלבד. | מחוון: שאלה 7 — לקבל גם פתרון גרפי מנומק. |
| 2 | בוחן קצר. משך: 30 דקות. | טיוטה — טרם נבדק מול המחוון. |
| 3 | יש לנמק כל שלב. תשובה ללא נימוק לא תזכה בניקוד מלא. | להזכיר לרכזת: השאלות 12006 ו-12007 חדשות השנה. |
| 4 | Answer all questions. No IDE or documentation allowed. | Q21010 is the give-away question — keep it first. |
| 5 | Short quiz on the Collections framework. | Draft — needs a fourth question before resubmitting. |
| 6 | Closed book. Write SQL keywords in uppercase. | Q22007 historically has the lowest success rate — expect a low mean. |

**Rejection reasons (T-4.2 — the reason is sent to the teacher and stored):**

| exam | version | rejected by | reason |
|---|---|---|---|
| 1 | v1 | 2 rina.barak (coordinator of 10) | חמש שאלות בלבד ל-60 דקות, והציון לכל שאלה גבוה מדי. נדרש פיזור רחב יותר. |
| 5 | v1 | 4 avi.cohen (coordinator of 20) | Three questions is too few for a graded quiz, and all three are from one topic. Add a fourth from Exceptions. |

> Exam 1 is the versioning showpiece: **v1 was rejected with a reason, v2 fixed exactly
> what the reason named** (5 questions → 7, 20 points each → 15/10), and v2 is what got
> approved and released. The rejected v1 stays queryable (C-2).

---

## 9. Executions (4) — S-2 "the same exam can be taken out of the drawer many times"

Times are **relative to load time**, resolved by the loader, and stored UTC. Codes are 4
alphanumeric (C-1); the demo uses digits.

| # | exam / version | code | window | status | note |
|---|---|---|---|---|---|
| 1 | 1 / v2 | `4821` | T−14d 09:00 → T−14d 11:00 | **CLOSED** | Fully graded, stats frozen |
| 2 | 4 / v1 | `7390` | T−3d 10:00 → T−3d 11:30 | **CLOSED** | Awaiting grading — nothing approved yet |
| 3 | 6 / v1 | `5164` | T+0 14:00 → T+0 16:00 | **SCHEDULED** | "Today", for the live release demo |
| 4 | 1 / v2 | `2075` | T−1h → T+1h | **LIVE** | Second execution of exam 1 — the S-2 proof |

Executions 3 and 4 are the two non-CLOSED rows, and their codes differ — the E9 service
rule (unique code among non-CLOSED executions) holds on the seed as loaded.

Execution 4 being the *same exam version* as execution 1 is the point: one exam, two
releases, separate codes, windows, participants and statistics.

### 9.1 Execution 1 — participation (S-21) and grades

8 Algebra students sat it. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final | note |
|---|---|---|---|---|---|
| 7 noa.friedman | SUBMITTED | 52 min | 92 | 92 | |
| 8 itay.regev | SUBMITTED | 68 min | 78 | 78 | |
| 9 shira.dahan | SUBMITTED | 61 min | 85 | 85 | |
| 10 omer.katz | **TIMED_OUT** | 75 min | 64 | 64 | Auto-submitted at expiry — the S-19 "did not make it in time" row |
| 11 maya.ben-david | SUBMITTED | 70 min | 71 | 71 | |
| 12 adam.peretz | SUBMITTED | 45 min | 96 | 96 | |
| 13 yael.azulay | SUBMITTED | 73 min | 51 | **55** | **Manual override**, see below |
| 14 daniel.shapira | SUBMITTED | 58 min | 83 | 83 | |

**Manual override (T-8.3 / S-23 — a change requires an explanation):**
- yael.azulay, 51 → 55, by rina.barak.
- Reason: `בשאלה 11011 נכתב פתרון נכון עם טעות סימן בשורה האחרונה — ניתן ניקוד חלקי.`
- Teacher comment to the student (S-22): `שיפור ניכר באי-שוויונות. כדאי לחזור על תחום ההגדרה.`

**Frozen `participation` JSON:** `{"started": 8, "finished": 7, "timed_out": 1}`

**Frozen `stats` JSON** (computed from the final column — S-25):

| metric | value |
|---|---|
| mean | 78.0 |
| median | 80.5 |
| stddev | 13.08 |
| min | 55 |
| max | 96 |
| deciles | 50–59: 1 · 60–69: 1 · 70–79: 2 · 80–89: 2 · 90–100: 2 |

> **stddev is the population standard deviation** (divisor `n`, not `n-1`): the class is
> the whole population, not a sample of one. 13.08 here; the sample form would give
> 13.98. E14 must use the same divisor or the seeded stats and the recomputed ones will
> disagree by ~1 point and look like a bug.

Five populated deciles across eight students — the histogram (T-10 note, F9.3) reads as
a real class. A uniform spread would look fabricated; a single spike would look broken.

### 9.2 Execution 2 — closed, awaiting grading

8 Java students, all SUBMITTED, solving times 38–59 min. Auto-scores computed, **no
grade approved** — `grades.status = AUTO` for all eight. This is the fixture for T-8.2
(the teacher approves grades) and for S-24: **nothing here is visible to a student yet.**

### 9.3 Executions 3 and 4 — nothing pre-seeded

Execution 3 (SCHEDULED) and execution 4 (LIVE) have **no attempts**. They exist so the
demo can create attempts live. Seeding attempts into them would make the take-exam
demo unrepeatable.

---

## 10. Bot content

One bot per course (S-30), 4 bots, 2 sources each. **`raw` and `extracted_text` are both
NOT NULL** (E2 PR1 review) — a source row only exists after successful extraction, so
every source below carries real body text, not a placeholder.

| bot | course | name | active |
|---|---|---|---|
| 1 | 11 | עוזר הלימוד — אלגברה | yes |
| 2 | 12 | עוזר הלימוד — חדו"א | yes |
| 3 | 21 | Java Study Assistant | yes |
| 4 | 22 | Databases Study Assistant | **no** — inactive, for the S-31 refusal demo |

Bot 4 is seeded **inactive** on purpose: S-31 says a student may use the bot only if
enrolled *and* the bot is active. Without an inactive bot there is no way to demo the
second half of that rule.

### 10.1 Sources (8)

Text is abridged here for readability; the loader stores the full paragraph.

**Source 1** · bot 1 · TEXT · `משוואות ליניאריות — סיכום`
> משוואה ליניארית היא משוואה שבה המשתנה מופיע בחזקה ראשונה בלבד. הפתרון מתבצע על ידי בידוד המשתנה: מעבירים אגפים תוך שינוי סימן, מכנסים איברים דומים ולבסוף מחלקים במקדם. מערכת של שתי משוואות בשני נעלמים נפתרת בשיטת ההצבה או בשיטת החיבור והחיסור. אם שתי המשוואות מתארות את אותו ישר יש אינסוף פתרונות, ואם הן מתארות ישרים מקבילים אין פתרון כלל.

**Source 2** · bot 1 · PDF · `פונקציות ריבועיות — פרק 3`
> פונקציה ריבועית היא פונקציה מהצורה y = ax² + bx + c כאשר a שונה מאפס. הגרף שלה הוא פרבולה: אם a חיובי הפרבולה פותחת כלפי מעלה ויש לה מינימום, ואם a שלילי היא פותחת כלפי מטה ויש לה מקסימום. שורשי הפונקציה נמצאים על ידי הנוסחה x = (-b ± √(b²-4ac)) / 2a. הביטוי b²-4ac נקרא דיסקרימיננטה: אם הוא חיובי יש שני שורשים, אם הוא אפס יש שורש כפול, ואם הוא שלילי אין שורשים ממשיים.

**Source 3** · bot 2 · TEXT · `גבולות — הגדרה ושימוש`
> גבול של פונקציה בנקודה מתאר לאן מתקרבים ערכי הפונקציה כאשר המשתנה מתקרב לנקודה, בלי להתייחס לערך הפונקציה בנקודה עצמה. כאשר הצבה ישירה נותנת ביטוי מהצורה 0/0 מדובר בגבול לא מוגדר שדורש טיפול: פירוק לגורמים וצמצום, הכפלה בצמוד, או שימוש בגבולות מיוחדים.

**Source 4** · bot 2 · PDF · `כללי גזירה`
> הנגזרת מודדת את קצב השינוי של פונקציה. כללי הגזירה הבסיסיים: נגזרת של x בחזקת n היא n כפול x בחזקת n פחות אחת; נגזרת של מכפלה נתונה על ידי f'g + fg'; נגזרת של מנה נתונה על ידי (f'g - fg')/g²; וכלל השרשרת קובע שנגזרת של הרכבה היא מכפלת הנגזרות. נקודות קיצון נמצאות היכן שהנגזרת מתאפסת, וסוג הקיצון נקבע לפי סימן הנגזרת השנייה.

**Source 5** · bot 3 · DOCX · `OOP Fundamentals — Lecture Notes`
> Object-oriented programming in Java rests on four ideas. Encapsulation keeps fields private and exposes behaviour through methods, so an object controls its own invariants. Inheritance lets a class extend another and reuse its behaviour, though composition is usually the better default. Polymorphism means a reference of a supertype can hold any subtype and dispatch to the subtype's implementation at runtime. Abstraction hides how something works behind an interface or an abstract class, so callers depend on what a type promises rather than on how it delivers.

**Source 6** · bot 3 · PDF · `The Collections Framework`
> The Java Collections Framework is organised around three interfaces. A List is an ordered sequence that allows duplicates; ArrayList gives constant-time indexed access while LinkedList gives constant-time insertion at the ends. A Set forbids duplicates; HashSet offers constant-time membership tests but no ordering, while TreeSet keeps elements sorted at logarithmic cost. A Map stores key-value pairs with unique keys; HashMap is the default choice and offers average constant-time lookup, degrading when many keys collide.

**Source 7** · bot 4 · TEXT · `Normalization in Practice`
> Normalization organises tables to remove redundancy and the update anomalies that come with it. First normal form requires atomic column values, with no repeating groups. Second normal form additionally forbids partial dependencies, where a non-key column depends on only part of a composite primary key. Third normal form forbids transitive dependencies, where a non-key column depends on another non-key column. Boyce-Codd normal form tightens this by requiring every determinant to be a candidate key.

**Source 8** · bot 4 · PDF · `Transactions and Isolation`
> A transaction groups statements so they succeed or fail together, and the ACID properties describe the guarantees. Atomicity means all-or-nothing. Consistency means constraints hold before and after. Isolation means concurrent transactions do not observe each other partially; the SQL standard defines four levels, from READ UNCOMMITTED, which permits dirty reads, through READ COMMITTED and REPEATABLE READ to SERIALIZABLE, which prevents phantom reads at the cost of concurrency. Durability means a committed change survives a crash.

### 10.2 Recorded sessions (8) — S-33 stores question, answer and time

| # | bot | student | asked | question | answer sketch |
|---|---|---|---|---|---|
| 1 | 1 | 7 noa.friedman | T−12d | איך פותרים משוואה עם שברים? | מכפילים את שני האגפים במכנה המשותף כדי להיפטר מהשברים, ואז פותרים כרגיל. |
| 2 | 1 | 11 maya.ben-david | T−10d | מה זו דיסקרימיננטה? | הביטוי b²-4ac. הסימן שלו קובע כמה שורשים ממשיים יש לפרבולה. |
| 3 | 1 | 7 noa.friedman | T−9d | מתי לפרבולה אין שורשים? | כאשר הדיסקרימיננטה שלילית — הפרבולה כולה מעל ציר x או כולה מתחתיו. |
| 4 | 2 | 16 tal.harari | T−8d | למה הגבול של sin(x)/x באפס שווה 1? | זהו גבול מיוחד שמוכיחים גיאומטרית בעזרת מעגל היחידה וכלל הסנדוויץ. |
| 5 | 3 | 10 omer.katz | T−6d | When should I use a LinkedList instead of an ArrayList? | Only when you insert or remove at the ends far more often than you read by index. |
| 6 | 3 | 17 roni.malka | T−5d | What is the difference between an interface and an abstract class? | An interface declares a contract and a class may implement many; an abstract class can hold state and a class may extend only one. |
| 7 | 3 | 10 omer.katz | T−4d | Why did my for-each loop throw ConcurrentModificationException? | The list was structurally modified during iteration. Use an Iterator and call its remove method, or use removeIf. |
| 8 | 3 | 15 lior.gabay | T−2d | What does the JVM do when recursion goes too deep? | Each call takes a stack frame; when the thread stack is exhausted the JVM throws StackOverflowError. |

Sessions cluster on bots 1 and 3, and both `omer.katz` and `noa.friedman` asked twice.
That is deliberate: T-14.3 / S-34 shows the teacher an **anonymised** aggregate ("8
questions this month, most-asked topic: Collections"), and a flat one-question-per-student
spread would make that view look identical to the raw list, proving nothing about
aggregation.

Bot 4 (Databases) has **no sessions** — it has never been active.

---

## 11. Notifications (seeded)

Enough that the notification centre is populated at login rather than empty (NFR-21).

| # | recipient | type | title | read |
|---|---|---|---|---|
| 1 | 2 rina.barak | EXAM_REJECTED | מבחן הוחזר לתיקון — גרסה 1 של "מבחן אמצע — אלגברה" | read |
| 2 | 3 yossi.mizrahi | EXAM_PENDING | המבחן נשלח לאישור רכזת המקצוע | unread |
| 3 | 2 rina.barak | APPROVAL_REQUEST | מבחן ממתין לאישורך במקצוע מתמטיקה | unread |
| 4 | 6 tamar.levi | EXAM_REJECTED | Collections Quiz was returned for revision | unread |
| 5 | 7 noa.friedman | GRADE_PUBLISHED | הציון שלך במבחן אמצע — אלגברה זמין לצפייה | read |
| 6 | 13 yael.azulay | GRADE_PUBLISHED | הציון שלך זמין לצפייה, כולל הערת מורה | unread |
| 7 | 4 avi.cohen | GRADING_DUE | 8 attempts awaiting your grade approval | unread |
| 8 | 1 dana.almog | EXECUTION_CLOSED | בחינה הסתיימה — 8 נבחנים, ממוצע 78 | unread |

Notification 8 exists so the principal's first screen is not empty at login: S-7 makes
her read-only, so she can never generate her own activity.

---

## 12. Open questions

| # | question | who | what I am building on |
|---|---|---|---|
| 1 | **`docs/DEMO_ACCOUNTS.md` does not exist** — not on `main`, not on any branch, not in any commit. It is E0.11 (lead, unticked). Omar's note says the seed "mirrors its five usernames", so one of us is looking at an unpushed file. | Naji | §3 above is the source of truth until that file lands. Whichever document is written second matches the first — I do not mind which, but it needs saying. |
| 2 | Which five accounts are the demo five? | Naji | Best guess: `dana.almog`, `rina.barak`, `avi.cohen`, `noa.friedman`, `omer.katz` — one per role, plus a coordinator and the student who timed out. Confirm before DEMO_ACCOUNTS.md is written. |
| 3 | Is `rina.barak` seeded as TEACHER plus a `coordinators` row? | Omar → Naji | **Yes, confirmed against the schema** — `role ENUM('STUDENT','TEACHER','PRINCIPAL')` has no COORDINATOR value and `coordinators` has its PK on `subject_code` alone. Omar's reading is right; §5 is built on it. No answer needed. |
| 4 | Population or sample standard deviation in the stored stats? | me (E14) → Naji | Population, divisor `n`. Recorded in §9.1 so the seeded stats and E14's recomputation cannot drift by a point and look like a bug. |
| 5 | Illustration images — real assets or NULL to start? | Omar | 10 questions are marked `img` but I have supplied no bytes. `image MEDIUMBLOB NULL` accepts NULL, so the loader can start with NULL; I will add real assets under `docs/seed/img/` in a follow-up. Flagged so nobody blocks on it. |
| 6 | Does anything validate the national-id check digit? | Naji | Nothing in the PRD says so. Mine are all checksum-valid Israeli ת"ז anyway, so the answer cannot break the demo either way. |
