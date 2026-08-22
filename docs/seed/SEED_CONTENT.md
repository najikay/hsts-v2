# Seed content (E2.15 / E2.16) — the demo dataset

> **This document is machine-read.** `SeedDocument` (`src/test/java`) parses the tables in
> sections 3–9.1.1 and two build-failing tests consume the parsed view. Reformatting a table is a
> contract change: update the parser expectations in the same commit, or the build goes red by
> design.

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

**The five usernames marked ★ are fixed by `docs/DEMO_ACCOUNTS.md`** and are mirrored here
verbatim, per that file's own rule: the E5 fixture directory is replaced by the seeded DB in
E2 PR3, "the usernames stay (the seed mirrors them)". The other 13 are mine.

`full_name` is Hebrew throughout — the school is Israeli and RTL must round-trip in every
screen that shows a name. `DEMO_ACCOUNTS.md` writes the five in Latin transliteration; same
people, same usernames.

Seed password is one uniform demo value, BCrypted by the loader. **`DEMO_ACCOUNTS.md` uses
`demo123` for the E5 fixture** — the seed keeps that value so the demo script does not change
when the fixture is replaced.

| id | username | full_name | role (stored) | national_id | |
|---|---|---|---|---|---|
| 1 | `principal.avia` | אביה שלו | PRINCIPAL | `301548202` | ★ |
| 2 | `dana.cohen` | דנה כהן | TEACHER | `214703951` | ★ |
| 3 | `rina.barak` | רינה ברק | TEACHER | `248190639` | ★ |
| 4 | `avi.mizrahi` | אבי מזרחי | TEACHER | `273056416` | |
| 5 | `tamar.shani` | תמר שני | TEACHER | `296481724` | |
| 6 | `michal.sharon` | מיכל שרון | TEACHER | `315729046` | |
| 7 | `noa.friedman` | נועה פרידמן | STUDENT | `338106727` | |
| 8 | `itay.regev` | איתי רגב | STUDENT | `349251082` | |
| 9 | `shira.dahan` | שירה דהן | STUDENT | `352074611` | |
| 10 | `omer.katz` | עומר כץ | STUDENT | `361489206` | |
| 11 | `maya.levi` | מאיה לוי | STUDENT | `374301851` | ★ |
| 12 | `noam.peretz` | נועם פרץ | STUDENT | `385612098` | ★ |
| 13 | `yael.azulay` | יעל אזולאי | STUDENT | `390745362` | |
| 14 | `daniel.shapira` | דניאל שפירא | STUDENT | `402186936` | |
| 15 | `lior.gabay` | ליאור גבאי | STUDENT | `413860529` | |
| 16 | `tal.harari` | טל הררי | STUDENT | `425097185` | |
| 17 | `roni.malka` | רוני מלכה | STUDENT | `436712400` | |
| 18 | `eitan.solomon` | איתן סולומון | STUDENT | `448521062` | |

> **No stored COORDINATOR role.** `users.role` is `ENUM('STUDENT','TEACHER','PRINCIPAL')`.
> `DEMO_ACCOUNTS.md` lists `rina.barak` as COORDINATOR because that is the **wire** role:
> ARCHITECTURE §5 round-2 makes it derived at login — stored TEACHER plus a `coordinators`
> row → wire `Role.COORDINATOR`. She is seeded TEACHER, and §5 below gives her the row.

## 4. Course teachers (`course_teachers`)

| course | teacher | note |
|---|---|---|
| `11` אלגברה | 2 dana.cohen | ★ `DEMO_ACCOUNTS.md`: dana.cohen teaches Algebra 11 |
| `12` חדו"א | 2 dana.cohen | ★ same teacher, second course |
| `12` חדו"א | — | ★ roster change 2026-08-20: rina.barak no longer co-teaches; dana.cohen teaches Calculus alone. Rina is the pure coordinator |
| `21` Java | 4 avi.mizrahi | |
| `21` Java | 5 tamar.shani | co-teacher on Java (PRD §5) |
| `22` Databases | 6 michal.sharon | |

Coverage: every course has at least one teacher (S-1), **Java has two**, and `dana.cohen`
teaches two courses alone (Algebra and Calculus). See deviation 3 in the PR report — PRD §5 describes
"one per course + one co-teacher on Java", and DEMO_ACCOUNTS.md forces a second co-taught
course. The richer shape is defensible on S-1 ("one or more teachers") but it is a
divergence from PRD §5 as written, not an accident.

## 5. Coordinators (`coordinators`) — 2 rows, one per subject

| subject_code | teacher | coordinates courses |
|---|---|---|
| `10` מתמטיקה | 3 rina.barak | 11 אלגברה, 12 חדו"א |
| `20` מדעי המחשב | 6 michal.sharon | 21 Java, 22 Databases |

`rina.barak` coordinates Mathematics (10) and teaches nothing (pure coordinator, decided 2026-08-20), so she approves
`dana.cohen`'s Algebra and Calculus exams. That is the intended demo shape: **the approver is
a peer teacher, not an administrator** (S-1).

`michal.sharon` teaches Databases (22) and coordinates Computer Science (20), so she approves
the Java exams written by `avi.mizrahi` and `tamar.shani`.

## 6. Enrollments (`enrollments`) — each student in 2–3 courses

| student | courses | |
|---|---|---|
| 7 noa.friedman | 11, 21 | |
| 8 itay.regev | 11, 12, 21 | |
| 9 shira.dahan | 11, 22 | |
| 10 omer.katz | 11, 21, 22 | |
| 11 maya.levi | 11, 21, 22 | ★ exactly as `DEMO_ACCOUNTS.md` |
| 12 noam.peretz | 12, 21 | ★ exactly as `DEMO_ACCOUNTS.md` |
| 13 yael.azulay | 11, 12, 22 | |
| 14 daniel.shapira | 11, 21 | |
| 15 lior.gabay | 11, 12 | |
| 16 tal.harari | 12, 22 | |
| 17 roni.malka | 21, 22 | |
| 18 eitan.solomon | 12, 21, 22 | |

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

**Authorship (`question_versions.created_by`) is a rule, not a column** — D9, stated here rather
than repeated across 43 version rows:

- **v1 of every question** is authored by the course's **first-listed teacher in §4**. So all
  Algebra and Calculus questions are `2 dana.cohen`, all Java questions `4 avi.mizrahi`, all
  Databases questions `6 michal.sharon`.
- **A second version in a co-taught course** is authored by the **co-teacher**. Java (21) is the
  only co-taught course, so today this resolves to exactly one row: **`21003` v2 =
  `5 tamar.shani`**. Second versions in singly-taught courses stay with the first-listed teacher,
  which means `11005` v2 is `2 dana.cohen` and `22004` v2 is `6 michal.sharon`.

The second clause exists so the bank is not authored entirely by one person per course: T-2.2's
"the previous version remains" is more convincing when a version history shows two names, and
`21003` is the row that demonstrates it. If Java ever stops being co-taught, or another course
gains a co-teacher, this rule re-resolves on its own — which is why it is a rule and not 43
hand-written values.

### 7.1 Algebra (course 11) — 11 questions

Topics: משוואות ליניאריות · פונקציות ריבועיות · אי-שוויונות

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 11001 | משוואות ליניאריות | EASY | פתרו: `3x + 6 = 18` | `x = 4` | `x = 6` | `x = 2` | `x = 12` | 1 | |
| 11002 | משוואות ליניאריות | EASY | פתרו: `5x - 7 = 2x + 8` | `x = 3` | `x = 5` | `x = 15` | `x = 1` | 2 | |
| 11003 | משוואות ליניאריות | MEDIUM | לאיזה ערך של `k` למערכת `2x + ky = 4`, `4x + 6y = 8` יש אינסוף פתרונות? | `k = 2` | `k = 6` | `k = 3` | `k = 12` | 3 | |
| 11004 | משוואות ליניאריות | HARD | סכום הספרות של מספר דו-ספרתי הוא 11. אם מחליפים את הספרות, המספר גדל ב-27. מהו המספר? | `29` | `38` | `56` | `47` | 4 | |
| 11005 | פונקציות ריבועיות | EASY | מהם שורשי `x² - 5x + 6 = 0`? | `2, 3` | `1, 6` | `-2, -3` | `0, 5` | 1 | yes |
| 11006 | פונקציות ריבועיות | EASY | מהו קודקוד הפרבולה `y = (x - 3)² + 4`? | `(-3, 4)` | `(3, 4)` | `(3, -4)` | `(4, 3)` | 2 | yes |
| 11007 | פונקציות ריבועיות | MEDIUM | כמה נקודות חיתוך עם ציר `x` יש לפרבולה `y = x² + 2x + 5`? | `שתיים` | `אחת` | `אף אחת` | `אינסוף` | 3 | yes |
| 11008 | פונקציות ריבועיות | HARD | הפרבולה `y = ax² + bx + c` עוברת דרך `(0,3)`, `(1,2)` ו-`(-1,6)`. מהו `a`? | `3` | `2` | `-1` | `1` | 4 | |
| 11009 | אי-שוויונות | EASY | פתרו: `2x - 4 > 6` | `x > 5` | `x > 1` | `x < 5` | `x > 10` | 1 | |
| 11010 | אי-שוויונות | MEDIUM | פתרו: `x² - 4 < 0` | `x < -2` | `-2 < x < 2` | `x > 2` | `כל x ממשי` | 2 | yes |
| 11011 | אי-שוויונות | HARD | לאילו ערכי `x` מתקיים `(x-1)/(x+2) ≥ 0`? | `x ≥ 1` | `-2 < x ≤ 1` | `x < -2 או x ≥ 1` | `x ≤ -2 או x ≥ 1` | 3 | |

### 7.2 Calculus (course 12) — 9 questions

Topics: גבולות · נגזרות · אינטגרלים

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 12001 | גבולות | EASY | חשבו: `lim(x→2) (x² - 4)/(x - 2)` | `0` | `לא קיים` | `2` | `4` | 4 | |
| 12002 | גבולות | MEDIUM | חשבו: `lim(x→∞) (3x² + x)/(x² - 5)` | `3` | `0` | `∞` | `1/3` | 1 |  |
| 12003 | גבולות | HARD | חשבו: `lim(x→0) sin(3x)/x` | `1` | `3` | `0` | `1/3` | 2 | |
| 12004 | נגזרות | EASY | מהי הנגזרת של `f(x) = x³`? | `3x` | `x²` | `3x²` | `x⁴/4` | 3 | |
| 12005 | נגזרות | EASY | מהי הנגזרת של `f(x) = sin(x)`? | `-sin(x)` | `tan(x)` | `-cos(x)` | `cos(x)` | 4 | |
| 12006 | נגזרות | MEDIUM | מהי הנגזרת של `f(x) = x·e^x`? | `(1 + x)·e^x` | `x·e^x` | `e^x` | `(x - 1)·e^x` | 1 |  |
| 12007 | נגזרות | HARD | לפונקציה `f(x) = x³ - 3x` יש מינימום מקומי בנקודה: | `x = -1` | `x = 1` | `x = 0` | `x = 3` | 2 | yes |
| 12008 | אינטגרלים | EASY | חשבו: `∫ 2x dx` | `x²/2 + C` | `2 + C` | `x² + C` | `2x² + C` | 3 | |
| 12009 | אינטגרלים | MEDIUM | חשבו את השטח מתחת ל-`y = x²` בין `x=0` ל-`x=3` | `6` | `27` | `3` | `9` | 4 | yes |

### 7.3 Java (course 21) — 11 questions

Topics: OOP Basics · Collections · Exceptions · **Recursion (the thin one)**

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 21001 | OOP Basics | EASY | Which keyword prevents a class from being subclassed? | `final` | `static` | `private` | `sealed` | 1 | |
| 21002 | OOP Basics | EASY | What is the default value of an uninitialised `int` field? | `null` | `0` | `undefined` | `-1` | 2 | |
| 21003 | OOP Basics | MEDIUM | A class implements two interfaces that both declare `default void run()`. What happens? | It compiles, the first interface wins | It compiles, the second interface wins | Compile error until the class overrides it | A runtime `AmbiguousMethodError` | 3 |  |
| 21004 | OOP Basics | HARD | Which statement about `equals` and `hashCode` is true? | Overriding `equals` alone is always safe | Equal hash codes mean the objects are equal | `hashCode` must be unique for every object | Equal objects must have equal hash codes | 4 | |
| 21005 | Collections | EASY | Which collection forbids duplicate elements? | `HashSet` | `ArrayList` | `LinkedList` | `ArrayDeque` | 1 | |
| 21006 | Collections | EASY | Which interface does `HashMap` implement? | `List` | `Map` | `Set` | `Queue` | 2 | yes |
| 21007 | Collections | MEDIUM | What is the average-case time complexity of `HashMap.get`? | `O(n)` | `O(log n)` | `O(1)` | `O(n log n)` | 3 |  |
| 21008 | Collections | HARD | Removing an element from an `ArrayList` inside a for-each loop throws: | `ConcurrentModificationException` | `IndexOutOfBoundsException` | `UnsupportedOperationException` | Nothing — it is safe | 1 | |
| 21009 | Exceptions | EASY | Which of these is a checked exception? | `NullPointerException` | `IOException` | `ArithmeticException` | `IllegalStateException` | 2 | |
| 21010 | Recursion | EASY | What does a recursive method need in order to terminate? | An enclosing loop | A `static` modifier | A base case | A `return null` statement | 3 | yes |
| 21011 | Recursion | MEDIUM | Recursion with no reachable base case fails with: | `OutOfMemoryError` | An infinite loop and no error | `IllegalStateException` | `StackOverflowError` | 4 | |

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
| 22001 | SQL Queries | EASY | Which clause filters rows *before* grouping? | `WHERE` | `HAVING` | `ORDER BY` | `LIMIT` | 1 | |
| 22002 | SQL Queries | EASY | Which join returns every row of the left table? | `INNER JOIN` | `LEFT JOIN` | `CROSS JOIN` | `SELF JOIN` | 2 | yes |
| 22003 | SQL Queries | MEDIUM | `COUNT(column)` differs from `COUNT(*)` because it: | Is always faster | Counts distinct values only | Ignores NULLs | Requires an index | 3 | |
| 22004 | SQL Queries | HARD | A join of two tables returns more rows than either table holds. The cause is: | A missing index | An implicit CROSS JOIN, always | A NULL in the ON clause | Duplicate values in the join key | 4 |  |
| 22005 | Normalization | EASY | First normal form requires every column to be: | Atomic | Indexed | Unique | Non-null | 1 | |
| 22006 | Normalization | MEDIUM | Removing a partial dependency on part of a composite key achieves: | 1NF | 2NF | 3NF | BCNF | 2 | yes |
| 22007 | Normalization | HARD | A table in 3NF but not in BCNF must contain: | A transitive dependency | A repeating group | A determinant that is not a candidate key | A surrogate key | 3 | |
| 22008 | Transactions | EASY | What does the "D" in ACID stand for? | Distributed | Deferred | Deterministic | Durability | 4 | |
| 22009 | Transactions | MEDIUM | Which isolation level still permits a phantom read? | READ COMMITTED | REPEATABLE READ | SERIALIZABLE | None of them | 1 | |

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
| 1 | `101101` | 11 | מבחן אמצע — אלגברה | 2 dana.cohen | v1 **REJECTED**, v2 **APPROVED** |
| 2 | `101102` | 11 | בוחן — אי-שוויונות | 2 dana.cohen | v1 **DRAFT** |
| 3 | `101201` | 12 | מבחן אמצע — חדו"א | 2 dana.cohen | v1 **PENDING** (awaiting 3 rina.barak) |
| 4 | `202101` | 21 | Java Fundamentals Exam | 4 avi.mizrahi | v1 **APPROVED** |
| 5 | `202102` | 21 | Collections Quiz | 5 tamar.shani | v1 **REJECTED** |
| 6 | `202201` | 22 | Databases Final | 6 michal.sharon | v1 **APPROVED** |

Every author teaches the course they wrote for (S-5). `dana.cohen` writes all three
Mathematics exams because she is the only teacher on both Algebra and Calculus —
which keeps `rina.barak` free to be the *approver* on both, never her own.

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
| 3 | יש לנמק כל שלב. תשובה ללא נימוק לא תזכה בניקוד מלא. | להזכיר לרינה: השאלות 12006 ו-12007 חדשות השנה. |
| 4 | Answer all questions. No IDE or documentation allowed. | Q21010 is the give-away question — keep it first. |
| 5 | Short quiz on the Collections framework. | Draft — needs a fourth question before resubmitting. |
| 6 | Closed book. Write SQL keywords in uppercase. | Q22007 historically has the lowest success rate — expect a low mean. |

**Rejection reasons (T-4.2 — the reason is sent to the teacher and stored):**

| exam | version | rejected by | reason |
|---|---|---|---|
| 1 | v1 | 3 rina.barak (coordinator of subject 10) | חמש שאלות בלבד ל-60 דקות, והציון לכל שאלה גבוה מדי. נדרש פיזור רחב יותר. |
| 5 | v1 | 6 michal.sharon (coordinator of subject 20) | Three questions is too few for a graded quiz, and all three are from one topic. Add a fourth from Exceptions. |

> Exam 1 is the versioning showpiece: **v1 was rejected with a reason, v2 fixed exactly
> what the reason named** (5 questions → 7, 20 points each → 15/10), and v2 is what got
> approved and released. The rejected v1 stays queryable (C-2).

---

## 9. Executions (4) — S-2 "the same exam can be taken out of the drawer many times"

Times are **relative to load time**, resolved by the loader, and stored UTC. Codes are 4
alphanumeric (C-1); the demo uses digits.

**Rules for the four NOT NULL columns the tables below do not spell out.** These are stated once
here rather than repeated per row, and the loader applies them uniformly:

| Column | Rule |
|---|---|
| `exam_executions.created_by` | the **releasing teacher** — the author of the exam version being released. Executions 1 and 4 → `2 dana.cohen`; execution 2 → `4 avi.mizrahi`; execution 3 → `6 michal.sharon`. |
| `grades.status` / `approved_by` / `approved_at` | **execution 1 only**: every grade is `APPROVED`, `approved_by` = the **executing teacher** (`2 dana.cohen`, who released it and owns the grades per T-8.2), `approved_at` = close time + 2 days. Execution 2's grades are `AUTO` with all three left null — that is what "awaiting grading" means. |
| `exam_attempts.started_at` | **derived, not invented**: window start + a small stagger, such that `started_at + solving time` lands inside the window. The per-student solving times in §9.1 and §9.2 are the input; the loader computes the timestamp so the two can never disagree. |

`ended_at` follows from `started_at` + solving time for `SUBMITTED` attempts, and equals the
window close for `TIMED_OUT` ones — which is what makes `omer.katz`'s 75 minutes in §9.1 the full
allotted duration rather than a number someone chose.

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

All 8 students enrolled in Algebra (11) sat it, so the roster and the attempt list match
exactly. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final | note |
|---|---|---|---|---|---|
| 15 lior.gabay | SUBMITTED | 45 min | 100 | 100 | all seven correct |
| 7 noa.friedman | SUBMITTED | 52 min | 90 | 90 | |
| 9 shira.dahan | SUBMITTED | 61 min | 85 | 85 | |
| 14 daniel.shapira | SUBMITTED | 58 min | 75 | 75 | |
| 8 itay.regev | SUBMITTED | 68 min | 70 | 70 | |
| 11 maya.levi | SUBMITTED | 70 min | 60 | 60 | |
| 13 yael.azulay | SUBMITTED | 73 min | 45 | **55** | **Manual override**, see below — the only fail turned into a pass |
| 10 omer.katz | **TIMED_OUT** | 75 min | 45 | 45 | Auto-submitted at expiry with four questions never reached — the S-19 "did not make it in time" row, and the one genuine fail |

> **Every auto score above is reachable by this exam.** Exam 1 v2 is six 15-point questions plus
> one worth 10, so the only totals auto-grading can produce are
> `0, 10, 15, 25, 30, 40, 45, 55, 60, 70, 75, 85, 90, 100`. An earlier draft of this section used
> scores like 92 and 78, which no combination of these questions can yield — invisible while the
> seed was only demo data, and wrong the moment `AutoGrader` recomputes it. §9.1.1 gives the
> per-question selections that produce each score.

**Manual override (T-8.3 / S-23 — a change requires an explanation):**
- yael.azulay, 45 → 55, by **2 dana.cohen** — the teacher who wrote and released the exam.
  The coordinator approves *exams*, the teacher approves *grades* (T-8.2 / T-8.3); here they
  are deliberately different people.
- Reason: `בשאלה 11011 נכתב פתרון נכון עם טעות סימן בשורה האחרונה — ניתן ניקוד חלקי.`
- Teacher comment to the student (S-22): `שיפור ניכר באי-שוויונות. כדאי לחזור על תחום ההגדרה.`

**Frozen `participation` JSON:** `{"started": 8, "finished": 7, "timed_out": 1}`

**Frozen `stats` JSON** (computed from the final column — S-25):

| metric | value |
|---|---|
| mean | 72.5 |
| median | 72.5 |
| stddev | 17.5 |
| min | 45 |
| max | 100 |
| pass rate | 7 / 8 = 0.875 |
| deciles | 40–49: 1 · 50–59: 1 · 60–69: 1 · 70–79: 2 · 80–89: 1 · 90–100: 2 |

> **stddev is the population standard deviation** (divisor `n`, not `n-1`): the class is
> the whole population, not a sample of one. Σ(x−72.5)² = 2450, so σ = √(2450/8) = **exactly
> 17.5** — every figure in this table is hand-checkable, which is what E12.4's "unit-tested
> against hand-computed fixtures" asks for. The sample form would give ≈18.71. E14 must use the same divisor or the seeded stats and the recomputed ones will
> disagree by ~1 point and look like a bug.

Six populated deciles across eight students — the histogram (T-10 note, F9.3) reads as
a real class. A uniform spread would look fabricated; a single spike would look broken.


#### 9.1.1 `attempt_answers` — what each student actually selected

Without this, no attempt can be re-graded and E12.1's auto-grading has nothing to run on. Every
row below was checked against the exam's answer key: the totals in §9.1 are what `AutoGrader`
produces from these selections, not numbers chosen first and justified afterwards.

Exam 1 v2's key, from §7.1 (question → correct answer, points):

| # | question | correct | points |
|---|---|---|---|
| 1 | 11001 | 1 | 15 |
| 2 | 11002 | 2 | 15 |
| 3 | 11005 **v1** | 1 | 15 |
| 4 | 11007 | 3 | 15 |
| 5 | 11009 | 1 | 15 |
| 6 | 11010 | 2 | 15 |
| 7 | 11011 | 3 | 10 |

Selections — **`—` means no row in `attempt_answers`**, not a row with a null selection. A
question the student never answered is absent, and the grader scores absence as 0 (F6.9):

| student | 11001 | 11002 | 11005 | 11007 | 11009 | 11010 | 11011 | auto |
|---|---|---|---|---|---|---|---|---|
| 15 lior.gabay | 1 | 2 | 1 | 3 | 1 | 2 | 3 | **100** |
| 7 noa.friedman | 1 | 2 | 1 | 3 | 1 | 2 | 1 | **90** |
| 9 shira.dahan | 1 | 2 | 1 | 3 | 1 | 4 | 3 | **85** |
| 14 daniel.shapira | 1 | 2 | 1 | 1 | 1 | 2 | 2 | **75** |
| 8 itay.regev | 1 | 2 | 1 | 3 | 3 | 1 | 3 | **70** |
| 11 maya.levi | 1 | 2 | 1 | 2 | 1 | 3 | 1 | **60** |
| 13 yael.azulay | 1 | 4 | 1 | 1 | 1 | 4 | 2 | **45** |
| 10 omer.katz | 1 | 2 | 1 | — | — | — | — | **45** |

Three things in that table are deliberate:

**`omer.katz` has four absent rows, not four wrong answers.** He timed out (§9.1), so he never
reached questions 4–7. That is the only attempt in the seed distinguishing "answered wrongly"
from "never answered" — the distinction F6.9 promises and H12.4 tests, and it cannot be
demonstrated from a dataset where every attempt answered everything.

**`yael.azulay` is wrong on 11011**, which is the question her override is about: the stored
reason says she wrote a correct method with a sign error there. Her auto score is 45; the
10 points for 11011 awarded by hand take her to exactly 55 — the pass mark. The override demo
therefore moves a real student across a real threshold, and the execution's pass rate with it
(7/8 rather than 6/8).

**Wrong answers are spread, not nested.** Each student misses a different combination rather than
a prefix of the same list, so the per-question difficulty in the results view varies the way a
real class does. 11007 and 11010 are the two most-missed; 11001 and 11005 are missed by nobody.

**Executions 2, 3 and 4 have no `attempt_answers`.** Execution 2's eight Java attempts carry auto
scores only (§9.2) and are the fixture for approving grades, not for re-grading; 3 and 4 have no
attempts at all by design (§9.3).

---

### 9.2 Execution 2 — closed, awaiting grading

The eight students enrolled in Java (21) sat exam 4 v1, all **SUBMITTED** — nobody timed out, so
this execution is the clean contrast to §9.1. Auto-scores are computed; **no grade is approved**.
`grades.status = AUTO` for all eight, with `final_score`, `override_reason`, `teacher_comment`,
`approved_by` and `approved_at` all null.

This is the fixture for **T-8.2** (the teacher approves grades) and for **S-24**: nothing here is
visible to a student yet, and `MY_GRADES_GET` returns nothing for any of these eight until
somebody approves.

| student | attempt status | solving time (S-19) | auto | final |
|---|---|---|---|---|
| 11 maya.levi | SUBMITTED | 41 min | 100 | — |
| 18 eitan.solomon | SUBMITTED | 47 min | 85 | — |
| 7 noa.friedman | SUBMITTED | 52 min | 75 | — |
| 17 roni.malka | SUBMITTED | 44 min | 70 | — |
| 8 itay.regev | SUBMITTED | 55 min | 60 | — |
| 12 noam.peretz | SUBMITTED | 38 min | 55 | — |
| 10 omer.katz | SUBMITTED | 59 min | 40 | — |
| 14 daniel.shapira | SUBMITTED | 58 min | 30 | — |

Exam 4 v1 is 6×15 + 10, the same shape as exam 1 v2, so the reachable totals are the same
fourteen values and every score above is one of them.

**The spread is deliberately unlike execution 1's.** Execution 1 finals are
`45, 55, 60, 70, 75, 85, 90, 100`; these are `30, 40, 55, 60, 70, 75, 85, 100`. **Two students sit
below the pass mark** rather than one, so approving these grades visibly moves a pass rate from
6/8 to something a teacher can see change — and the two executions cannot be mistaken for copies
of each other in a results list.

Solving times stay inside the exam's 60-minute duration and, unlike §9.1, none equals it: nobody
here ran out of time.

#### 9.2.1 `attempt_answers` for execution 2

Exam 4 v1's key, from §7.3 (question → correct answer, points):

| # | question | correct | points |
|---|---|---|---|
| 1 | 21001 | 1 | 15 |
| 2 | 21002 | 2 | 15 |
| 3 | 21005 | 1 | 15 |
| 4 | 21006 | 2 | 15 |
| 5 | 21009 | 2 | 15 |
| 6 | 21010 | 3 | 15 |
| 7 | 21011 | 4 | 10 |

Every student answered every question — there are no `—` entries here, because nobody timed out.
That is the point of having two executions: §9.1.1 exercises absent-versus-wrong, and this one
exercises a full paper.

| student | 21001 | 21002 | 21005 | 21006 | 21009 | 21010 | 21011 | auto |
|---|---|---|---|---|---|---|---|---|
| 11 maya.levi | 1 | 2 | 1 | 2 | 2 | 3 | 4 | **100** |
| 18 eitan.solomon | 1 | 2 | 1 | 2 | 2 | 1 | 4 | **85** |
| 7 noa.friedman | 1 | 2 | 1 | 2 | 2 | 1 | 1 | **75** |
| 17 roni.malka | 1 | 2 | 1 | 2 | 1 | 1 | 4 | **70** |
| 8 itay.regev | 1 | 2 | 1 | 1 | 2 | 1 | 1 | **60** |
| 12 noam.peretz | 1 | 2 | 2 | 2 | 1 | 1 | 4 | **55** |
| 10 omer.katz | 1 | 1 | 2 | 1 | 1 | 3 | 4 | **40** |
| 14 daniel.shapira | 1 | 1 | 2 | 1 | 1 | 3 | 1 | **30** |

**21010 is the most-missed question** — five of eight got it wrong — and **21001 nobody missed**.
That gives the per-question breakdown in the grading review (E12.6) something to actually show:
a flat difficulty profile would make that screen look like it was not reading the data.

`21011` is the only question whose correct answer is **4**, and three students picked something
else. Worth keeping when the answer key is next touched: it is the seed's clearest demonstration
that the fourth option is a real answer and not decoration.

### 9.3 Executions 3 and 4 — nothing pre-seeded

Execution 3 (SCHEDULED) and execution 4 (LIVE) have **no attempts**. They exist so the
demo can create attempts live. Seeding attempts into them would make the take-exam
demo unrepeatable.

---

## 10. Bot content

One bot per course (S-30), 4 bots, 2 sources each. **`raw` and `extracted_text` are both
NOT NULL** (E2 PR1 review) — a source row only exists after successful extraction, so
every source below carries real body text, not a placeholder.

> **All eight seeded sources are `type = TEXT`, with `raw` = the UTF-8 bytes of
> `extracted_text`.** The five that read as PDF or DOCX below are seeded as TEXT too: shipping
> binary fixtures would mean the seed could only be loaded from a checkout carrying those files,
> and it would prove nothing the parse pipeline does not already prove. **The PDF/POI extraction
> path is covered by E16's own tests and demoed live** by uploading a real document during the
> defence — a better demonstration than a pre-parsed blob nobody watches being parsed. Ruling of
> 2026-08-20; each source keeps its original document name in `title`, so the set still *reads*
> as mixed.

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

**These are the complete sources, not extracts.** `BotSection.java` carries these
paragraphs verbatim and no fuller version exists anywhere else in the repository, so
this section and that file are one artefact in two places: **changing a body here
without changing it there is a contract break**, and `SeedLoadedDbTest` fails when they
disagree.

**Volume, stated plainly:** the corpus is **1781 words across eight sources**, roughly
222 words each. Per bot that is 410 words for Algebra, 417 for Calculus, 480 for Java and
474 for Databases.

These were expanded on 2026-08-22 from an earlier set totalling 546 words. That set was
written to prove the schema rather than to answer questions, and it showed: a student
asking the Java bot a question on stage was getting an answer grounded in about 160
words of course material. Nothing about the pipeline was wrong — the sources parsed,
the prompt assembled, the fallback chain worked. It was the volume that was short, and
fixing it was content work rather than code.

**Every body is real course material for its course** and nothing else. The bot is
grounded on these plus the question bank (S-28), so anything invented here would be
invented by the bot on stage, under questioning, in front of people who teach the
subject.

**Source 1** · bot 1 · TEXT · `משוואות ליניאריות — סיכום`
> משוואה ליניארית היא משוואה שבה המשתנה מופיע בחזקה ראשונה בלבד, ללא חזקות, שורשים או מכפלות של נעלמים. הצורה הכללית היא ax + b = 0 כאשר a שונה מאפס, והפתרון היחיד הוא x = -b/a. הפתרון מתבצע על ידי בידוד המשתנה: מעבירים אגפים תוך שינוי סימן, מכנסים איברים דומים, ולבסוף מחלקים במקדם של המשתנה. כאשר יש שברים במשוואה נהוג לכפול את שני האגפים במכנה המשותף כדי להיפטר מהם לפני הבידוד. כאשר יש סוגריים פותחים אותם תחילה לפי חוק הפילוג. מערכת של שתי משוואות בשני נעלמים נפתרת באחת משתי שיטות. בשיטת ההצבה מבודדים משתנה אחד מאחת המשוואות ומציבים את הביטוי שהתקבל במשוואה השנייה. בשיטת החיבור והחיסור כופלים את המשוואות במספרים מתאימים כך שמקדמי אחד המשתנים יהיו נגדיים, ואז מחברים את המשוואות והמשתנה מצטמצם. שתי השיטות נותנות את אותה תשובה, והבחירה ביניהן היא עניין של נוחות: הצבה נוחה כשמקדם של אחד המשתנים הוא אחד, וחיבור וחיסור נוח כשהמקדמים כבר קרובים. לכל מערכת יש שלוש אפשרויות בלבד, ולכולן יש משמעות גאומטרית. אם הישרים נחתכים בנקודה אחת יש פתרון יחיד. אם שתי המשוואות מתארות את אותו ישר יש אינסוף פתרונות, ובפתרון האלגברי יתקבל פסוק אמת כמו 0 = 0. אם הן מתארות ישרים מקבילים אין פתרון כלל, ובפתרון יתקבל פסוק שקר כמו 0 = 5. הטעות הנפוצה ביותר היא שכחת שינוי הסימן במעבר אגף.

**Source 2** · bot 1 · PDF · `פונקציות ריבועיות — פרק 3`
> פונקציה ריבועית היא פונקציה מהצורה y = ax² + bx + c כאשר a שונה מאפס. הגרף שלה הוא פרבולה, ומקדם a קובע את כיוון הפתיחה: אם a חיובי הפרבולה פותחת כלפי מעלה ויש לה נקודת מינימום, ואם a שלילי היא פותחת כלפי מטה ויש לה נקודת מקסימום. ככל שהערך המוחלט של a גדול יותר, הפרבולה צרה יותר. שורשי הפונקציה, כלומר נקודות החיתוך עם ציר ה-x, נמצאים על ידי הנוסחה x = (-b ± √(b²-4ac)) / 2a. הביטוי b²-4ac נקרא דיסקרימיננטה ומסומן בדרך כלל באות דלתא. הוא קובע את מספר השורשים: אם הוא חיובי יש שני שורשים שונים, אם הוא אפס יש שורש כפול אחד והפרבולה משיקה לציר ה-x, ואם הוא שלילי אין שורשים ממשיים והפרבולה כולה נמצאת מעל הציר או מתחתיו. נקודת החיתוך עם ציר ה-y מתקבלת תמיד בהצבת x = 0 ולכן שווה ל-c. ציר הסימטריה של הפרבולה הוא הישר x = -b/2a, וקודקוד הפרבולה נמצא על ציר זה. הצבת ערך זה בפונקציה נותנת את ערך הקיצון. כאשר ידועים שני השורשים, ציר הסימטריה נמצא גם באמצע ביניהם, וזו דרך מהירה יותר לחשב את הקודקוד. ניתן לכתוב את הפונקציה גם בצורת קודקוד y = a(x-p)² + k, כאשר (p,k) הוא הקודקוד. צורה זו נוחה לשרטוט ולזיהוי הזזות של הגרף.

**Source 3** · bot 2 · TEXT · `גבולות — הגדרה ושימוש`
> גבול של פונקציה בנקודה מתאר לאן מתקרבים ערכי הפונקציה כאשר המשתנה מתקרב לנקודה, בלי להתייחס כלל לערך הפונקציה בנקודה עצמה. זו הבחנה מהותית: פונקציה יכולה להיות לא מוגדרת בנקודה ובכל זאת יהיה לה גבול שם, ולהפך, ערך הפונקציה בנקודה יכול להיות שונה מהגבול. כאשר הגבול קיים ושווה לערך הפונקציה בנקודה, אומרים שהפונקציה רציפה באותה נקודה. ניתן להתקרב לנקודה משני הכיוונים, ולכן מוגדרים גם גבול מימין וגבול משמאל. הגבול קיים אם ורק אם שני הגבולות החד-צדדיים קיימים ושווים זה לזה. כאשר הם שונים, הפונקציה קופצת בנקודה ואין לה גבול שם. בחישוב מנסים תחילה הצבה ישירה. אם ההצבה נותנת מספר, זהו הגבול. כאשר הצבה ישירה נותנת ביטוי מהצורה אפס חלקי אפס מדובר בגבול לא מוגדר שדורש טיפול אלגברי לפני ההצבה. שלוש השיטות המרכזיות הן פירוק לגורמים וצמצום הגורם המשותף שמאפס את המכנה, הכפלה בצמוד כאשר מופיע שורש, ושימוש בגבולות מיוחדים ידועים. אם ההצבה נותנת מספר שונה מאפס חלקי אפס, הגבול הוא אינסוף או מינוס אינסוף ולפונקציה יש אסימפטוטה אנכית באותה נקודה. גבול באינסוף מתאר את התנהגות הפונקציה לטווח רחוק. בפונקציה רציונלית משווים את חזקות המונה והמכנה: אם החזקה במכנה גדולה יותר הגבול הוא אפס, אם הן שוות הגבול הוא יחס המקדמים המובילים, ואם החזקה במונה גדולה יותר הגבול הוא אינסוף. גבול סופי באינסוף מציין אסימפטוטה אופקית.

**Source 4** · bot 2 · PDF · `כללי גזירה`
> הנגזרת מודדת את קצב השינוי של פונקציה, ומבחינה גאומטרית היא שיפוע המשיק לגרף הפונקציה בנקודה. הגדרתה היא גבול של יחס ההפרשים כאשר ההפרש שואף לאפס, אך בפועל מחשבים אותה באמצעות כללי גזירה ולא מההגדרה. כללי הגזירה הבסיסיים: נגזרת של קבוע היא אפס; נגזרת של x בחזקת n היא n כפול x בחזקת n פחות אחת; נגזרת של סכום היא סכום הנגזרות; ונגזרת של קבוע כפול פונקציה היא הקבוע כפול הנגזרת. נגזרת של מכפלה נתונה על ידי f'g + fg', ושימו לב שהיא אינה מכפלת הנגזרות. נגזרת של מנה נתונה על ידי (f'g - fg')/g², והסדר במונה חשוב. כלל השרשרת קובע שנגזרת של הרכבת פונקציות היא מכפלת הנגזרת החיצונית בנגזרת הפנימית, והוא הכלל הנחוץ בכל פעם שמופיעה פונקציה בתוך פונקציה. השימוש המרכזי של הנגזרת הוא חקירת פונקציות. נקודות קיצון חשודות נמצאות היכן שהנגזרת מתאפסת. כדי לקבוע את סוג הקיצון בודקים את סימן הנגזרת משני צדי הנקודה: מעבר מחיובי לשלילי מציין מקסימום, ומעבר משלילי לחיובי מציין מינימום. לחלופין משתמשים במבחן הנגזרת השנייה: אם הנגזרת השנייה בנקודה חיובית מדובר במינימום, ואם היא שלילית מדובר במקסימום. הפונקציה עולה בקטע שבו הנגזרת חיובית ויורדת בקטע שבו היא שלילית. נקודות שבהן הנגזרת השנייה מתאפסת ומחליפה סימן הן נקודות פיתול, שבהן משתנה כיוון הקעירות של הגרף. חקירה מלאה כוללת תחום הגדרה, נקודות חיתוך עם הצירים, תחומי עלייה וירידה ונקודות הקיצון.

**Source 5** · bot 3 · DOCX · `OOP Fundamentals — Lecture Notes`
> Object-oriented programming in Java rests on four ideas. Encapsulation keeps fields private and exposes behaviour through methods, so an object controls its own invariants. A class that lets callers write its fields directly cannot guarantee anything about its own state, because every caller becomes responsible for rules the class was supposed to enforce. Accessors are not the point; control over change is. Inheritance lets a class extend another and reuse its behaviour, establishing an is-a relationship. It is powerful and easy to overuse. Composition, where a class holds another as a field and delegates to it, is usually the better default: it can be changed at runtime, it does not expose a superclass's internals to its subclasses, and it avoids deep hierarchies that are hard to follow. Prefer inheritance only when a subtype genuinely is a kind of its supertype. Polymorphism means a reference of a supertype can hold any subtype, and the call dispatches to the subtype's implementation at runtime rather than at compile time. This is what lets one loop over a list of shapes call area on each without knowing which shapes are in it. Abstraction hides how something works behind an interface or an abstract class, so callers depend on what a type promises rather than on how it delivers. An interface declares behaviour with no state; an abstract class may provide shared fields and partial implementation. A class implements many interfaces but extends only one class.

**Source 6** · bot 3 · PDF · `The Collections Framework`
> The Java Collections Framework is organised around three interfaces, and choosing between their implementations is mostly a question of what operation you do most often. A List is an ordered sequence that allows duplicates and addresses elements by index. ArrayList is backed by an array and gives constant-time indexed access, but inserting or removing in the middle shifts every later element. LinkedList gives constant-time insertion and removal at the ends, but reaching index n means walking n links. ArrayList is the right default; LinkedList earns its place only when you are repeatedly adding at the front. A Set forbids duplicates. HashSet offers constant-time membership tests on average but makes no promise about iteration order. LinkedHashSet preserves insertion order at a small cost. TreeSet keeps elements sorted, which costs logarithmic time per operation and requires the elements to be comparable. A Map stores key-value pairs with unique keys. HashMap is the default choice and offers average constant-time lookup, degrading when many keys collide in the same bucket. TreeMap keeps keys sorted; LinkedHashMap preserves insertion order. Every hash-based collection depends on the equals and hashCode contract. Two objects that are equal must return the same hash code, and an object used as a key must not change in a way that alters its hash while it is in the collection. Breaking either rule produces a collection that appears to lose entries, which is a bug that is hard to find later.

**Source 7** · bot 4 · TEXT · `Normalization in Practice`
> Normalization organises tables to remove redundancy and the update anomalies that come with it. When the same fact is stored in more than one row, three problems follow. An update anomaly changes one copy and leaves the others stale. An insertion anomaly makes it impossible to record one fact without inventing another. A deletion anomaly loses a fact you wanted to keep as a side effect of removing one you did not. The forms build on each other. First normal form requires atomic column values, with no repeating groups and no lists packed into a single field. Second normal form additionally forbids partial dependencies, where a non-key column depends on only part of a composite primary key; a table whose primary key is a single column satisfies it automatically. Third normal form forbids transitive dependencies, where a non-key column depends on another non-key column rather than on the key directly. Boyce-Codd normal form tightens third by requiring every determinant to be a candidate key, which matters only in tables with several overlapping candidate keys. The working rule of thumb is that every non-key column should depend on the key, the whole key, and nothing but the key. Normalization is not free. Splitting a table means joining it back together on every read, and a heavily normalized schema can be slower for reporting. Denormalizing deliberately, with the duplication documented, is a legitimate decision; duplicating by accident is not.

**Source 8** · bot 4 · PDF · `Transactions and Isolation`
> A transaction groups statements so they succeed or fail together, and the ACID properties describe the guarantees it makes. Atomicity means all-or-nothing: either every statement takes effect or none does, so a transfer cannot debit one account without crediting the other. Consistency means the database's constraints hold before the transaction and after it. Isolation means concurrent transactions do not observe each other's partial work. Durability means a committed change survives a crash, because the change was written to a durable log before the commit was acknowledged. Isolation is the property with a dial on it, because full isolation is expensive. Three phenomena are what the levels are defined against. A dirty read sees another transaction's uncommitted change, which may then be rolled back. A non-repeatable read sees a different value when reading the same row twice, because another transaction committed in between. A phantom read sees a different set of rows for the same query, because another transaction inserted or deleted matching rows. The SQL standard defines four levels against those. READ UNCOMMITTED permits dirty reads. READ COMMITTED prevents them but allows non-repeatable and phantom reads. REPEATABLE READ additionally prevents non-repeatable reads. SERIALIZABLE prevents phantom reads too, at the cost of concurrency. Isolation is usually implemented with locks, and locks make deadlock possible: two transactions each holding what the other needs. Databases detect this and abort one of them, so application code must be prepared to retry.

### 10.2 Recorded sessions (8) — S-33 stores question, answer and time

`bot_messages.provider` is **`deepseek`** on every row except session 6, which is
**`anthropic`** — the one row proving the ADR-009 fallback chain actually fired and was recorded.
Without it the provider column is a constant and demonstrates nothing.

| # | bot | student | asked | provider | question | answer sketch |
|---|---|---|---|---|---|---|
| 1 | 1 | 7 noa.friedman | T−12d | `deepseek` | איך פותרים משוואה עם שברים? | מכפילים את שני האגפים במכנה המשותף כדי להיפטר מהשברים, ואז פותרים כרגיל. |
| 2 | 1 | 11 maya.levi | T−10d | `deepseek` | מה זו דיסקרימיננטה? | הביטוי b²-4ac. הסימן שלו קובע כמה שורשים ממשיים יש לפרבולה. |
| 3 | 1 | 7 noa.friedman | T−9d | `deepseek` | מתי לפרבולה אין שורשים? | כאשר הדיסקרימיננטה שלילית — הפרבולה כולה מעל ציר x או כולה מתחתיו. |
| 4 | 2 | 16 tal.harari | T−8d | `deepseek` | למה הגבול של sin(x)/x באפס שווה 1? | זהו גבול מיוחד שמוכיחים גיאומטרית בעזרת מעגל היחידה וכלל הסנדוויץ. |
| 5 | 3 | 10 omer.katz | T−6d | `deepseek` | When should I use a LinkedList instead of an ArrayList? | Only when you insert or remove at the ends far more often than you read by index. |
| 6 | 3 | 17 roni.malka | T−5d | `anthropic` | What is the difference between an interface and an abstract class? | An interface declares a contract and a class may implement many; an abstract class can hold state and a class may extend only one. |
| 7 | 3 | 10 omer.katz | T−4d | `deepseek` | Why did my for-each loop throw ConcurrentModificationException? | The list was structurally modified during iteration. Use an Iterator and call its remove method, or use removeIf. |
| 8 | 3 | 12 noam.peretz | T−2d | `deepseek` | What does the JVM do when recursion goes too deep? | Each call takes a stack frame; when the thread stack is exhausted the JVM throws StackOverflowError. |

Sessions cluster on bots 1 and 3, and both `omer.katz` and `noa.friedman` asked twice.
That is deliberate: T-14.3 / S-34 shows the teacher an **anonymised** aggregate, and a flat
one-question-per-student spread would make that view look identical to the raw list, proving
nothing about aggregation.

**The aggregate is per bot, never school-wide.** One bot per course (S-30) and a teacher sees
only the courses she teaches, so the Java teacher's screen reads **"4 questions this month,
most-asked topic: Collections"** — not 8. The distribution is 3 for Algebra, 1 for Calculus, 4
for Java and 0 for Databases; 8 is the all-bots total and no screen in the product can show it.
An earlier draft of this note used 8 as the example, which would have had someone building the
E16 view to a number the specification forbids.

Bot 4 (Databases) has **no sessions** — it has never been active.

---

## 11. Notifications (seeded)

Enough that the notification centre is populated at login rather than empty (NFR-21).

**`seed_id` is a naming handle, not a database column** (D8, corrected). `notifications` has no
such column: the loader keys idempotency on **recipient + type + title**, which is unique across
the eight rows below and is what a re-load actually matches on.

The `seed_id` column stays because that composite key is useless in a sentence — an acceptance
case, a demo script or a failing assertion needs to say *which* notification, and
`N-EXEC-CLOSED-ALG` does that where "the row for user 1 with type EXECUTION_CLOSED and title
'בחינה הסתיימה…'" does not. It is documentation vocabulary, and every id here maps to exactly one
row.

**The moment that stops being true is a real column.** The key holds only because no recipient
gets two notifications of the same type with the same title. Seed a repeating notification —
two grade publications to the same student, say — and the composite collapses; that is when
`notifications` gains a `seed_id` column rather than when someone finds it tidier.

The `#` column is presentation order only and carries no meaning.

| seed_id | # | recipient | type | title | read |
|---|---|---|---|---|---|
| `N-EXAM-REJECTED-ALG` | 1 | 2 dana.cohen | EXAM_REJECTED | מבחן הוחזר לתיקון — גרסה 1 של "מבחן אמצע — אלגברה" | read |
| `N-EXAM-PENDING-CALC` | 2 | 2 dana.cohen | EXAM_PENDING | המבחן נשלח לאישור רכזת המקצוע | unread |
| `N-APPROVAL-REQ-MATH` | 3 | 3 rina.barak | APPROVAL_REQUEST | מבחן ממתין לאישורך במקצוע מתמטיקה | unread |
| `N-EXAM-REJECTED-JAVA` | 4 | 5 tamar.shani | EXAM_REJECTED | Collections Quiz was returned for revision | unread |
| `N-GRADE-NOA` | 5 | 7 noa.friedman | GRADE_PUBLISHED | הציון שלך במבחן אמצע — אלגברה זמין לצפייה | read |
| `N-GRADE-YAEL` | 6 | 13 yael.azulay | GRADE_PUBLISHED | הציון שלך זמין לצפייה, כולל הערת מורה | unread |
| `N-GRADING-DUE-JAVA` | 7 | 4 avi.mizrahi | GRADING_DUE | 8 attempts awaiting your grade approval | unread |
| `N-EXEC-CLOSED-ALG` | 8 | 1 principal.avia | EXECUTION_CLOSED | בחינה הסתיימה — 8 נבחנים, ממוצע 72.5 | unread |

`N-EXEC-CLOSED-ALG` exists so the principal's first screen is not empty at login: S-7 makes
her read-only, so she can never generate her own activity. **Its title quotes the mean, so it is
derived data in a text column** — it reads 72.5, matching §9.1's frozen stats. It said 78 until
the score fix; anything that changes the seeded grades has to change this string too, which is
exactly the sort of copy nobody thinks to re-check.

`N-GRADING-DUE-JAVA` says eight attempts, which is §9.2's eight AUTO grades — the same coupling,
and it stays true as long as the Java roster stays at eight.

Every recipient is the person the event actually concerns: rejections and pending-approval
notices go to the **author** (`dana.cohen`, `tamar.shani`), the approval request goes to the
**subject coordinator** (`rina.barak`), and grade publications go to the **students who sat
the exam**. A notification addressed to someone with no stake in the event is the kind of
thing that only shows up when a reviewer opens the screen at the defense.

---

## 12. Open questions

| # | question | who | what I am building on |
|---|---|---|---|
| 1 | **`docs/DEMO_ACCOUNTS.md` does not exist** — not on `main`, not on any branch, not in any commit. It is E0.11 (lead, unticked). Omar's note says the seed "mirrors its five usernames", so one of us is looking at an unpushed file. | Naji | §3 above is the source of truth until that file lands. Whichever document is written second matches the first — I do not mind which, but it needs saying. |
| 2 | Which five accounts are the demo five? | Naji | Best guess: `principal.avia`, `rina.barak`, `avi.mizrahi`, `noa.friedman`, `omer.katz` — one per role, plus a coordinator and the student who timed out. Confirm before DEMO_ACCOUNTS.md is written. |
| 3 | Is `rina.barak` seeded as TEACHER plus a `coordinators` row? | Omar → Naji | **Yes, confirmed against the schema** — `role ENUM('STUDENT','TEACHER','PRINCIPAL')` has no COORDINATOR value and `coordinators` has its PK on `subject_code` alone. Omar's reading is right; §5 is built on it. No answer needed. |
| 4 | Population or sample standard deviation in the stored stats? | me (E14) → Naji | Population, divisor `n`. Recorded in §9.1 so the seeded stats and E14's recomputation cannot drift by a point and look like a bug. |
| 5 | Illustration images — real assets or NULL to start? | Omar | 10 questions are marked `img` but I have supplied no bytes. `image MEDIUMBLOB NULL` accepts NULL, so the loader can start with NULL; I will add real assets under `docs/seed/img/` in a follow-up. Flagged so nobody blocks on it. |
| 6 | Does anything validate the national-id check digit? | Naji | Nothing in the PRD says so. Mine are all checksum-valid Israeli ת"ז anyway, so the answer cannot break the demo either way. |
