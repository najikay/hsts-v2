# Seed content (E2.15 / E2.16) — the demo dataset

> **This document is machine-read.** `SeedDocument` (`src/test/java`) parses the tables in
> sections 3–9.6.1 and two build-failing tests consume the parsed view. Reformatting a table is a
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
| One coordinator per subject | `coordinators` PK is `subject_code` alone (S-1) | Exactly 5 coordinator rows, one per subject |
| Exactly one correct answer, answers pairwise distinct | C-8 / ADR-016 | Every question below |
| Points sum to 100 per exam version | §5 (service rule, not DDL) | Checked per exam below |
| Passwords BCrypt-hashed | PRD §5 | Plaintext here is **demo-only**; the loader hashes at insert |
| All `DATETIME` values are UTC | migration README | Relative times below ("T−14d") resolve at load time |
| No em dashes in stored text | PRD §4.1 | Every quoted value below is written as the loader stores it, not as prose would write it |

> **National ids are checksum-valid Israeli national id.** S-18 has a student type this to start
> an attempt; if id validation is ever added, invalid demo data would break the demo
> rather than the code. Costing nothing now, so they are all valid.

> **Quoted values are written as the database holds them (B-13, 2026-08-27).** PRD §4.1 forbids
> em dashes in user-visible text and permits a comma, a period or a colon in their place. That is
> three legal renderings, and the loader picks between them by context: a colon in a title
> (`Midterm: Algebra`, `Study assistant: Algebra`) and a comma inside a sentence
> (`A draft, not yet checked against the marking scheme.`). Until 2026-08-27 this document wrote
> those values with em dashes, so every acceptance case that quoted an exam name read as a
> mismatch against the loaded row and the walk reports had to quote the database instead of the
> document. Every value in §7, §8, §8.2, §9.1, §10, §10.1, §10.2 and §11 now matches
> `QuestionBankSection`, `ExamsSection`, `GradesSection`, `BotSection` and
> `NotificationsSection` character for character. §9.1 is listed last because it was the section
> the first sweep missed: its override reason is a bullet rather than a table row, so a sweep
> written to read tables did not see it, and it had drifted furthest of all of them.
>
> This also tightens the checks that exist. `SeedDocument.followsHouseRule` accepts any of the
> three replacements at each em dash, so while the dashes were here those comparisons passed on a
> comma, a period **or** a colon; with no dash left it falls through to exact equality.
>
> **Which values a test actually compares is a shorter list than this section, and the difference
> is worth knowing before you trust a value here.** Compared against the loaded database: exam
> names (added with B-13, after a plant showed nothing was reading them), the §8.2 texts,
> rejection reasons, question v1 stems and options, bot names, source titles and bodies, session
> questions and answers, notification titles, and the manual override's reason and comment (also
> added with B-13). **Not compared, and therefore only as accurate as the last person to edit
> them:** notification *bodies*, which §11 does not tabulate at all, and every second-version
> question in §7.5, which `SeedLoadedDbContract.questionsMatch` filters out with
> `qv.versionNo = 1` and no parser reads. A change to either fails nothing.
>
> **Em dashes stay in this document's prose and headings, which are stored nowhere, and as a
> sentinel in §4, §9.1.1 and §9.2, where `—` in a cell means "no row at all" rather than an
> empty value.** `SeedDocument` reads that sentinel. Replacing those would rewrite the fixture.

---

## 1. Subjects (seeded, read-only — S-3)

| code2 | name |
|---|---|
| `10` | Mathematics |
| `20` | Computer science |
| `30` | Biology |
| `40` | Chemistry |
| `50` | Physics |

> **Three subjects added 2026-08-30 (live session, U-42).** The dataset had two subjects and
> four courses, which is enough to prove every rule and not enough to look like a school: a
> picker with two entries reads as a fixture on every screen that offers one, and the reports
> screen in particular compares across a list the principal can exhaust at a glance. Biology,
> Chemistry and Physics are one course each, one teacher each and one coordinator each, so the
> breadth costs three rows per table and changes no rule anywhere.

## 2. Courses (seeded, read-only — S-3)

| code2 | subject | name |
|---|---|---|
| `11` | `10` | Algebra |
| `12` | `10` | Calculus |
| `21` | `20` | Object oriented programming in Java |
| `22` | `20` | Databases |
| `31` | `30` | Biology |
| `41` | `40` | Chemistry |
| `51` | `50` | Physics |

`code2` is the subject's first digit plus a serial within the subject (ARCHITECTURE §5, and the
four rows above it): Mathematics 10 holds 11 and 12, Computer science 20 holds 21 and 22, so
Biology 30 holds 31, Chemistry 40 holds 41 and Physics 50 holds 51. The three new subjects hold
one course each, which the convention allows and the existing four do not happen to show.

## 3. Users (21 — 1 principal, 8 teachers, 12 students)

**The five usernames marked ★ are fixed by `docs/DEMO_ACCOUNTS.md`** and are mirrored here
verbatim, per that file's own rule: the E5 fixture directory is replaced by the seeded DB in
E2 PR3, "the usernames stay (the seed mirrors them)". The other 16 are mine.

`full_name` is **English throughout** — "Dana Cohen", "Avi Mizrahi", "Maya Levi". Same people,
same usernames as `DEMO_ACCOUNTS.md`, and now the same spelling as well.

> **Corrected 2026-08-26 (batch A · B-19).** This paragraph read *"`full_name` is Hebrew
> throughout — the school is Israeli and RTL must round-trip in every screen that shows a
> name"* and had been false since **UI wave 1** translated the dataset (F-13, the ruling in
> `docs/reports/lead/MANUAL-PASS-1.md`; `WAVE1.md` §33 states it plainly — "nothing anywhere in
> the demo shows Hebrew"). `UsersSection` seeds no Hebrew codepoint at all. The document was not
> updated in lockstep with the loader, so a frozen record asserted a property of the loaded
> database that had stopped being true; the scenario-10/11 pre-walk found it by writing two
> assertions against names from this file and failing to compile.
>
> **X-I18N is unaffected, and where the evidence moved to matters more than the correction.**
> RTL is still proven — `WAVE1.md` §W1.8 records that `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip`
> was rewritten to **write its own Hebrew sample** rather than read the seed's, precisely because
> the seed no longer has one. So the round-trip guarantee is tested by a test that owns its
> fixture instead of by demo data that could be translated out from under it, which is the
> stronger arrangement. Case 21.6's Hebrew-and-English-side-by-side check is walked with a
> question typed on the day.

Seed password is one uniform demo value, BCrypted by the loader. **`DEMO_ACCOUNTS.md` uses
`demo123` for the E5 fixture** — the seed keeps that value so the demo script does not change
when the fixture is replaced.

| id | username | full_name | role (stored) | national_id | |
|---|---|---|---|---|---|
| 1 | `principal.avia` | Avia Shalev | PRINCIPAL | `301548202` | ★ |
| 2 | `dana.cohen` | Dana Cohen | TEACHER | `214703951` | ★ |
| 3 | `rina.barak` | Rina Barak | TEACHER | `248190639` | ★ |
| 4 | `avi.mizrahi` | Avi Mizrahi | TEACHER | `273056416` | |
| 5 | `tamar.shani` | Tamar Shani | TEACHER | `296481724` | |
| 6 | `michal.sharon` | Michal Sharon | TEACHER | `315729046` | |
| 7 | `noa.friedman` | Noa Friedman | STUDENT | `338106727` | |
| 8 | `itay.regev` | Itay Regev | STUDENT | `349251082` | |
| 9 | `shira.dahan` | Shira Dahan | STUDENT | `352074611` | |
| 10 | `omer.katz` | Omer Katz | STUDENT | `361489206` | |
| 11 | `maya.levi` | Maya Levi | STUDENT | `374301851` | ★ |
| 12 | `noam.peretz` | Noam Peretz | STUDENT | `385612098` | ★ |
| 13 | `yael.azulay` | Yael Azulay | STUDENT | `390745362` | |
| 14 | `daniel.shapira` | Daniel Shapira | STUDENT | `402186936` | |
| 15 | `lior.gabay` | Lior Gabay | STUDENT | `413860529` | |
| 16 | `tal.harari` | Tal Harari | STUDENT | `425097185` | |
| 17 | `roni.malka` | Roni Malka | STUDENT | `436712400` | |
| 18 | `eitan.solomon` | Eitan Solomon | STUDENT | `448521062` | |
| 19 | `galit.stern` | Galit Stern | TEACHER | `451936272` | ⚑ U-42 |
| 20 | `orly.navon` | Orly Navon | TEACHER | `460748155` | ⚑ U-42 |
| 21 | `sivan.adler` | Sivan Adler | TEACHER | `471603944` | ⚑ U-42 |

> **Three teachers added 2026-08-30 (live session, U-42).** One per new subject, and each of
> them teaches her subject's only course **and** coordinates that subject: rows 19, 20 and 21 are
> three more of `michal.sharon`'s dual-hat shape (§5). Their national ids continue the
> synthetic ascending series and are checksum-valid Israeli ids like every other one here; their
> password is the same `demo123`, hashed per user by the loader. Names are English, as §3 has
> required since B-19.

> **No stored COORDINATOR role.** `users.role` is `ENUM('STUDENT','TEACHER','PRINCIPAL')`.
> `DEMO_ACCOUNTS.md` lists `rina.barak` as COORDINATOR because that is the **wire** role:
> ARCHITECTURE §5 round-2 makes it derived at login — stored TEACHER plus a `coordinators`
> row → wire `Role.COORDINATOR`. She is seeded TEACHER, and §5 below gives her the row.

## 4. Course teachers (`course_teachers`)

| course | teacher | note |
|---|---|---|
| `11` Algebra | 2 dana.cohen | ★ `DEMO_ACCOUNTS.md`: dana.cohen teaches Algebra 11 |
| `12` Calculus | 2 dana.cohen | ★ same teacher, second course |
| `12` Calculus | — | ★ roster change 2026-08-20: rina.barak no longer co-teaches; dana.cohen teaches Calculus alone. Rina is the pure coordinator |
| `21` Java | 4 avi.mizrahi | |
| `21` Java | 5 tamar.shani | co-teacher on Java (PRD §5) |
| `22` Databases | 6 michal.sharon | |
| `31` Biology | 19 galit.stern | ⚑ U-42, and she coordinates subject 30 as well |
| `41` Chemistry | 20 orly.navon | ⚑ U-42, and she coordinates subject 40 as well |
| `51` Physics | 21 sivan.adler | ⚑ U-42, and she coordinates subject 50 as well |

Coverage: every course has at least one teacher (S-1), **Java has two**, and `dana.cohen`
teaches two courses alone (Algebra and Calculus). See deviation 3 in the PR report — PRD §5 describes
"one per course + one co-teacher on Java", and DEMO_ACCOUNTS.md forces a second co-taught
course. The richer shape is defensible on S-1 ("one or more teachers") but it is a
divergence from PRD §5 as written, not an accident.

**The three U-42 courses are singly taught** and stay that way. Java is the only co-taught course
in the seed, and it has to stay the only one: §7's authorship rule resolves a second version to
the co-teacher, and that clause is proven by the fact that it fires on exactly one row
(`21003` v2). A second co-taught course would give the clause two rows and cost nothing, but it
would also mean two places to keep the "exactly one" assertion honest, so the new courses have
one teacher each and no second question version at all.

## 5. Coordinators (`coordinators`) — 5 rows, one per subject

| subject_code | teacher | coordinates courses |
|---|---|---|
| `10` Mathematics | 3 rina.barak | 11 Algebra, 12 Calculus |
| `20` Computer science | 6 michal.sharon | 21 Java, 22 Databases |
| `30` Biology | 19 galit.stern | 31 Biology |
| `40` Chemistry | 20 orly.navon | 41 Chemistry |
| `50` Physics | 21 sivan.adler | 51 Physics |

`rina.barak` coordinates Mathematics (10) and teaches nothing (pure coordinator, decided 2026-08-20), so she approves
`dana.cohen`'s Algebra and Calculus exams. That is the intended demo shape: **the approver is
a peer teacher, not an administrator** (S-1).

`michal.sharon` teaches Databases (22) and coordinates Computer Science (20), so she approves
the Java exams written by `avi.mizrahi` and `tamar.shani`.

**The three U-42 coordinators are the dual-hat shape, taken to its limit, and that has one
consequence worth stating rather than discovering.** Each of `galit.stern`, `orly.navon` and
`sivan.adler` is the only teacher in her subject and its coordinator, so **she is the approver of
her own exams**. The Biology exam in §8 is APPROVED and she is the only person who could have
approved it. Nothing forbids that: the `coordinators` PK is the subject alone, and S-1 asks for a
coordinator per subject, not for a second teacher to exist. It is recorded here because the
approval *story* the demo tells is deliberately the opposite one, and it must keep being told on
Mathematics and Computer Science: `rina.barak` approves what `dana.cohen` wrote and
`michal.sharon` approves what `avi.mizrahi` and `tamar.shani` wrote, which is where every
approval and rejection fixture in §8.2 lives. The three new subjects are breadth for the pickers
and the reports, not a second approval demo, and giving each of them a second teacher purely so
the approver could differ would have added three users nothing else in the dataset uses.

## 6. Enrollments (`enrollments`) — each student in 3–5 courses

| student | courses | |
|---|---|---|
| 7 noa.friedman | 11, 21, 31, 51 | |
| 8 itay.regev | 11, 12, 21, 41, 51 | |
| 9 shira.dahan | 11, 22, 31 | |
| 10 omer.katz | 11, 21, 22, 31 | |
| 11 maya.levi | 11, 21, 22, 31 | ★ the four courses `DEMO_ACCOUNTS.md` fixes, plus Biology |
| 12 noam.peretz | 12, 21, 41 | ★ the two courses `DEMO_ACCOUNTS.md` fixes, plus Chemistry |
| 13 yael.azulay | 11, 12, 22, 41, 51 | |
| 14 daniel.shapira | 11, 21, 41 | |
| 15 lior.gabay | 11, 12, 31, 51 | |
| 16 tal.harari | 12, 22, 31, 51 | |
| 17 roni.malka | 21, 22, 41, 51 | |
| 18 eitan.solomon | 12, 21, 22, 41 | |

Per-course totals: **11 → 8 students · 12 → 6 · 21 → 8 · 22 → 7 · 31 → 6 · 41 → 6 · 51 → 6.**
Algebra's 8 is deliberate: it is the fully-graded execution, and 8 grades spread across
5 deciles is what makes the F9.3 histogram look like a real class rather than a stub.

**The three U-42 rosters are six each, spread over the whole roster rather than over the
convenient half** (2026-08-30, live session). Eighteen new pairs across twelve students: six
students gain two courses and six gain one, so nobody is left in two courses while somebody else
sits in five, and no new course is a copy of another's roster. `maya.levi`'s and
`noam.peretz`'s ★ rows keep every course `DEMO_ACCOUNTS.md` fixes for them and gain one, because
that file states which courses they are in and not that those are all of them.

**Six, not eight, and the reason is §9.6.** Biology 31 is the roster of a new fully graded
sitting, and five of its six students sat it. A roster of six with five attempts is the shape
that makes "who did not sit it" answerable on a screen; a roster that exactly matches its
attempt list, as Algebra's does in §9.1, is the other shape, and the dataset now carries both.
---

## 7. Question bank (58 questions)

`display_id5` = course(2) + serial(3) — S-8. Every question is 4 answers, exactly one
correct, all four pairwise distinct (C-8 / ADR-016). **Correct** column is the answer
index 1-4. **Img** = has an illustration (10 total, PRD §5).

**Language is English throughout** — all seven courses. Topic names are English too
(`Linear equations`, `Quadratic functions`, `Inequalities`, `Recursion`), which is what case 3.4
was rewritten against (B-9).

> **Corrected 2026-08-26 (batch A · B-19).** This paragraph read *"Language is mixed on purpose:
> Algebra and Calculus are Hebrew (RTL must be proven), Java and Databases are English… Both
> appear in every demoed screen"*, and none of it has been true since **UI wave 1** (F-13).
> `QuestionBankSection` and `SubjectsSection` contain **zero Hebrew codepoints**; §8's exam names
> and §10.1's four mathematics bot sources were translated in the same pass (see the note at the
> end of §10). The mixed-language claim survived here because nothing asserts a document's prose
> against the loader — the seed tests compare rows, and the rows were translated correctly.
>
> **What replaced the RTL evidence:** see the corrected §3. The one place a Hebrew round trip is
> still guaranteed is `SeedDatasetMySqlTest.hebrewSurvivesTheRoundTrip`, which writes its own
> sample; `utf8mb4` is unchanged and Hebrew typed on the day stores and renders exactly as it
> always did — the demo simply no longer ships any.

**Authorship (`question_versions.created_by`) is a rule, not a column** — D9, stated here rather
than repeated across 61 version rows:

- **v1 of every question** is authored by the course's **first-listed teacher in §4**. So all
  Algebra and Calculus questions are `2 dana.cohen`, all Java questions `4 avi.mizrahi`, all
  Databases questions `6 michal.sharon`, and since U-42 all Biology questions
  `19 galit.stern`, all Chemistry `20 orly.navon` and all Physics `21 sivan.adler`.
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

Topics: Linear equations · Quadratic functions · Inequalities

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 11001 | Linear equations | EASY | Solve: `3x + 6 = 18` | `x = 4` | `x = 6` | `x = 2` | `x = 12` | 1 | |
| 11002 | Linear equations | EASY | Solve: `5x - 7 = 2x + 8` | `x = 3` | `x = 5` | `x = 15` | `x = 1` | 2 | |
| 11003 | Linear equations | MEDIUM | For which value of `k` does the system `2x + ky = 4`, `4x + 6y = 8` have infinitely many solutions? | `k = 2` | `k = 6` | `k = 3` | `k = 12` | 3 | |
| 11004 | Linear equations | HARD | The digits of a two-digit number add up to 11. Swapping the digits increases the number by 27. What is the number? | `29` | `38` | `56` | `47` | 4 | |
| 11005 | Quadratic functions | EASY | What are the roots of `x² - 5x + 6 = 0`? | `2, 3` | `1, 6` | `-2, -3` | `0, 5` | 1 | yes |
| 11006 | Quadratic functions | EASY | What is the vertex of the parabola `y = (x - 3)² + 4`? | `(-3, 4)` | `(3, 4)` | `(3, -4)` | `(4, 3)` | 2 | yes |
| 11007 | Quadratic functions | MEDIUM | How many x-axis intercepts does the parabola `y = x² + 2x + 5` have? | `Two` | `One` | `None` | `Infinitely many` | 3 | yes |
| 11008 | Quadratic functions | HARD | The parabola `y = ax² + bx + c` passes through `(0,3)`, `(1,2)` and `(-1,6)`. What is `a`? | `3` | `2` | `-1` | `1` | 4 | |
| 11009 | Inequalities | EASY | Solve: `2x - 4 > 6` | `x > 5` | `x > 1` | `x < 5` | `x > 10` | 1 | |
| 11010 | Inequalities | MEDIUM | Solve: `x² - 4 < 0` | `x < -2` | `-2 < x < 2` | `x > 2` | `all real x` | 2 | yes |
| 11011 | Inequalities | HARD | For which values of `x` does `(x-1)/(x+2) ≥ 0` hold? | `x ≥ 1` | `-2 < x ≤ 1` | `x < -2 or x ≥ 1` | `x ≤ -2 or x ≥ 1` | 3 | |

### 7.2 Calculus (course 12) — 9 questions

Topics: Limits · Derivatives · Integrals

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 12001 | Limits | EASY | Evaluate: `lim(x→2) (x² - 4)/(x - 2)` | `0` | `does not exist` | `2` | `4` | 4 | |
| 12002 | Limits | MEDIUM | Evaluate: `lim(x→∞) (3x² + x)/(x² - 5)` | `3` | `0` | `∞` | `1/3` | 1 |  |
| 12003 | Limits | HARD | Evaluate: `lim(x→0) sin(3x)/x` | `1` | `3` | `0` | `1/3` | 2 | |
| 12004 | Derivatives | EASY | What is the derivative of `f(x) = x³`? | `3x` | `x²` | `3x²` | `x⁴/4` | 3 | |
| 12005 | Derivatives | EASY | What is the derivative of `f(x) = sin(x)`? | `-sin(x)` | `tan(x)` | `-cos(x)` | `cos(x)` | 4 | |
| 12006 | Derivatives | MEDIUM | What is the derivative of `f(x) = x·e^x`? | `(1 + x)·e^x` | `x·e^x` | `e^x` | `(x - 1)·e^x` | 1 |  |
| 12007 | Derivatives | HARD | The function `f(x) = x³ - 3x` has a local minimum at: | `x = -1` | `x = 1` | `x = 0` | `x = 3` | 2 | yes |
| 12008 | Integrals | EASY | Evaluate: `∫ 2x dx` | `x²/2 + C` | `2 + C` | `x² + C` | `2x² + C` | 3 | |
| 12009 | Integrals | MEDIUM | Find the area under `y = x²` between `x=0` and `x=3` | `6` | `27` | `3` | `9` | 4 | yes |

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
| 21008 | Collections | HARD | Removing an element from an `ArrayList` inside a for-each loop throws: | `ConcurrentModificationException` | `IndexOutOfBoundsException` | `UnsupportedOperationException` | Nothing, it is safe | 1 | |
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
| 11005 | v2 rewords the stem to `Find the roots of the equation x² - 5x + 6 = 0` (answers unchanged) | The Algebra Midterm's graded execution references **v1** — proof that a released exam is pinned to a version (S-14, C-2) |
| 21003 | v2 corrects answer 4: `AmbiguousMethodError` → `IncompatibleClassChangeError` | A correction that changes an *answer*, not just the stem |
| 22004 | v2 appends "(assume no NULLs in the join key)" to the stem | A clarification on a HARD question |

**Loader note.** `exam_version_questions` for the Algebra Midterm must reference question
11005 **version 1**. Everywhere else use the latest version. This is also the row that
exercises the new `question_id` + `UNIQUE(exam_version_id, question_id)` guard: 11005 v1
and v2 must never both land in one exam version.

### 7.6 Biology (course 31) — 6 questions ⚑ (added 2026-08-30, live session, U-42)

Topics: Cells · Genetics

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 31001 | Cells | EASY | Which organelle releases most of a cell's usable energy? | Mitochondrion | Ribosome | Golgi apparatus | Lysosome | 1 | |
| 31002 | Cells | MEDIUM | A plant cell is left in pure water until its cell wall stops it taking in any more. That state is called: | Plasmolysed | Turgid | Flaccid | Lysed | 2 | |
| 31003 | Cells | HARD | Ribosomes are prevented from binding the rough endoplasmic reticulum. Which product is affected first? | ATP made in the mitochondria | Glucose made in the chloroplast | Proteins destined for secretion | Water crossing the membrane | 3 | |
| 31004 | Genetics | EASY | How many chromosomes does a normal human body cell contain? | 23 | 92 | 24 | 46 | 4 | |
| 31005 | Genetics | MEDIUM | Two parents are each carriers of the same recessive disorder. What fraction of their children is expected to be affected? | One quarter | One half | Three quarters | None | 1 | |
| 31006 | Genetics | HARD | Two individuals heterozygous for both of two independently assorting genes are crossed. What phenotype ratio is expected? | 3:1 | 9:3:3:1 | 1:1:1:1 | 1:2:1 | 2 | |

### 7.7 Chemistry (course 41) — 6 questions ⚑ (added 2026-08-30, live session, U-42)

Topics: Atomic structure · Chemical reactions

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 41001 | Atomic structure | EASY | Which particle in an atom carries a negative charge? | Electron | Proton | Neutron | Nucleus | 1 | |
| 41002 | Atomic structure | MEDIUM | An atom has 11 protons and 12 neutrons. What is its mass number? | 11 | 23 | 12 | 1 | 2 | |
| 41003 | Atomic structure | HARD | Why does the first ionisation energy fall going down a group? | The nuclear charge falls | The atoms gain more protons | The outer electron is further from the nucleus and better shielded | The atoms become more electronegative | 3 | |
| 41004 | Chemical reactions | EASY | What is the pH of a neutral aqueous solution at 25 degrees Celsius? | 0 | 14 | 1 | 7 | 4 | |
| 41005 | Chemical reactions | MEDIUM | How many molecules of water are produced when two molecules of hydrogen react completely with one molecule of oxygen? | 2 | 1 | 3 | 4 | 1 | |
| 41006 | Chemical reactions | HARD | A reaction at equilibrium is heated and the yield of product falls. What does that say about the forward reaction? | It is endothermic | It is exothermic | It is catalysed | It has stopped | 2 | |

### 7.8 Physics (course 51) — 6 questions ⚑ (added 2026-08-30, live session, U-42)

Topics: Motion · Energy

| display_id5 | topic | diff | text | a1 | a2 | a3 | a4 | correct | img |
|---|---|---|---|---|---|---|---|---|---|
| 51001 | Motion | EASY | What is the SI unit of force? | Newton | Joule | Watt | Pascal | 1 | |
| 51002 | Motion | MEDIUM | A car accelerates uniformly from rest at 3 m/s². How fast is it moving after 4 seconds? | 3 m/s | 12 m/s | 7 m/s | 0.75 m/s | 2 | |
| 51003 | Motion | HARD | A ball is thrown straight up and caught again. Ignoring air resistance, what is its acceleration at the highest point? | Zero | Upwards and increasing | 9.8 m/s² downwards | Equal to its initial speed | 3 | |
| 51004 | Energy | EASY | Which quantity is measured in joules? | Power | Momentum | Frequency | Energy | 4 | |
| 51005 | Energy | MEDIUM | A 2 kg mass is lifted 5 m at constant speed. Taking g as 10 m/s², how much gravitational potential energy does it gain? | 100 J | 10 J | 50 J | 20 J | 1 | |
| 51006 | Energy | HARD | A pendulum swings with no friction. Where is its kinetic energy greatest? | At the highest point of the swing | At the lowest point of the swing | It is the same everywhere | Halfway between the two | 2 | |

> **Why these three sit after §7.5 rather than before it** (2026-08-30, live session, U-42).
> §7.1 to §7.4 are the per-course banks and §7.5 is the second-version table, so a new course
> bank "belongs" at §7.5 and the second versions would move to §7.8. **The sections are appended
> instead, and the numbering records the order they were added rather than a taxonomy.** That is
> the precedent U-34 set when it numbered execution 5's tables §9.4 rather than inserting them
> ahead of §9.3, and it is worth keeping for the same reason: every section number in this
> document is quoted from somewhere else - a parser heading list, a loader javadoc, an acceptance
> case, a defect note - and renumbering a section silently repoints every one of those at
> different content. Appending costs a paragraph of explanation once; renumbering costs a sweep
> that has to be right everywhere or it is worse than not doing it.
>
> **Shape of the three, stated once rather than three times.** Each is six questions: two
> topics, three questions per topic, one of each difficulty, so each course holds **two EASY,
> two MEDIUM and two HARD**. Correct answers run 1, 2, 3, 4, 1, 2 down each table, which is the
> same cycle §7.1 runs, so no course has a majority answer a guesser could exploit. All four
> options in every row are pairwise distinct, none carries an illustration, and none has a
> second version - see §4's note on why the co-teacher clause stays a one-row clause.

---

## 8. Exams (7, in mixed states)

`display_id6` = subject(2) + course(2) + serial(2) — S-10. Every exam version's points
sum to **100** (service rule, §5). `status` lives on the *version*, not the exam.

| # | display_id6 | course | name | author | versions and status |
|---|---|---|---|---|---|
| 1 | `101101` | 11 | Midterm: Algebra | 2 dana.cohen | v1 **REJECTED**, v2 **APPROVED** |
| 2 | `101102` | 11 | Quiz: Inequalities | 2 dana.cohen | v1 **DRAFT** |
| 3 | `101201` | 12 | Midterm: Calculus | 2 dana.cohen | v1 **PENDING** (awaiting 3 rina.barak) |
| 4 | `202101` | 21 | Java Fundamentals Exam | 4 avi.mizrahi | v1 **APPROVED** |
| 5 | `202102` | 21 | Collections Quiz | 5 tamar.shani | v1 **REJECTED** |
| 6 | `202201` | 22 | Databases Final | 6 michal.sharon | v1 **APPROVED** |
| 7 | `303101` | 31 | Midterm: Biology | 19 galit.stern | v1 **APPROVED** |

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
| 7 | v1 | 45 min | 31001→15, 31004→15, 31002→20, 31005→20, 31003→30 |

Each row sums to 100. Exam 1 v2 keeps 11005 at **version 1** deliberately (§7.5).

**Exam 7 is five questions, not seven, and its points are not a flat 15** ⚑ (added 2026-08-30,
live session, U-43). Every other paper in this section is six 15-point questions plus a 10, so
every reachable auto score in the dataset came from one arithmetic and a grading screen could
not tell a scoring bug from a coincidence. Exam 7 is 15, 15, 20, 20, 30 - the two EASY questions,
then the two MEDIUM, then the one HARD - which sums to 100 like everything else and yields a
**different** set of reachable totals: 0, 15, 20, 30, 35, 40, 45, 50, 55, 60, 65, 70, 80, 85, 100.
§9.6's five scores are five of those, and none of them is reachable from a 6x15+10 paper except
by coincidence. It also leaves 31006 out of the paper, so the Biology bank holds one question the
exam does not use, exactly as every other course's does.

### 8.2 Texts and rejection reasons

| exam | student_text (S-3 general text) | teacher_text (teacher-only) |
|---|---|---|
| 1 | Read each question to the end. Only a basic calculator may be used. | Marking note: question 7, accept a reasoned graphical solution too. |
| 2 | A short quiz. Duration: 30 minutes. | A draft, not yet checked against the marking scheme. |
| 3 | Justify every step. An answer with no justification will not receive full marks. | Remind Rina: questions 12006 and 12007 are new this year. |
| 4 | Answer all questions. No IDE or documentation allowed. | Q21010 is the give-away question, keep it first. |
| 5 | Short quiz on the Collections framework. | Draft, needs a fourth question before resubmitting. |
| 6 | Closed book. Write SQL keywords in uppercase. | Q22007 historically has the lowest success rate, expect a low mean. |
| 7 | Answer every question. Diagrams may be labelled in note form. | The last question is worth 30, so leave time for it. |

**Rejection reasons (T-4.2 — the reason is sent to the teacher and stored):**

| exam | version | rejected by | reason |
|---|---|---|---|
| 1 | v1 | 3 rina.barak (coordinator of subject 10) | Only five questions for 60 minutes, and each one is worth too much. A wider spread is needed. |
| 5 | v1 | 6 michal.sharon (coordinator of subject 20) | Three questions is too few for a graded quiz, and all three are from one topic. Add a fourth from Exceptions. |

> Exam 1 is the versioning showpiece: **v1 was rejected with a reason, v2 fixed exactly
> what the reason named** (5 questions → 7, 20 points each → 15/10), and v2 is what got
> approved and released. The rejected v1 stays queryable (C-2).

---

## 9. Executions (7) — S-2 "the same exam can be taken out of the drawer many times"

Times are **relative to load time**, resolved by the loader, and stored UTC. Codes are 4
alphanumeric (C-1); the demo uses digits.

**Rules for the four NOT NULL columns the tables below do not spell out.** These are stated once
here rather than repeated per row, and the loader applies them uniformly:

| Column | Rule |
|---|---|
| `exam_executions.created_by` | the **releasing teacher** — the author of the exam version being released. Executions 1, 4 and 5 → `2 dana.cohen`; executions 2 and 6 → `4 avi.mizrahi`; execution 3 → `6 michal.sharon`; execution 7 → `19 galit.stern`. |
| `grades.status` / `approved_by` / `approved_at` | **executions 1, 6 and 7**: every grade is `APPROVED`, `approved_by` = the **executing teacher** (the one who released it and owns the grades per T-8.2, so `2 dana.cohen`, `4 avi.mizrahi` and `19 galit.stern` respectively), `approved_at` = close time + 2 days. Executions 2 and 5 carry `AUTO` grades with all three left null — that is what "awaiting grading" means. |
| `exam_attempts.started_at` | **derived, not invented**: window start + a small stagger, such that `started_at + solving time` lands inside the window. The per-student solving times in §9.1 and §9.2 are the input; the loader computes the timestamp so the two can never disagree. |

`ended_at` follows from `started_at` + solving time for `SUBMITTED` attempts, and equals the
window close for `TIMED_OUT` ones — which is what makes `omer.katz`'s 75 minutes in §9.1 the full
allotted duration rather than a number someone chose.

| # | exam / version | code | window | status | note |
|---|---|---|---|---|---|
| 1 | 1 / v2 | `4821` | T−14d 09:00 → T−14d 11:00 | **CLOSED** | Fully graded, stats frozen |
| 2 | 4 / v1 | `7390` | T−3d 10:00 → T−3d 11:30 | **CLOSED** | Awaiting grading — nothing approved yet |
| 3 | 6 / v1 | `5164` | T+4h → T+6h | **SCHEDULED** | Opening later today, for the release demo |
| 4 | 1 / v2 | `2075` | T−30m → T+3h | **LIVE** | Second execution of exam 1 — the S-2 proof |
| 5 | 1 / v2 | `3318` | T−1d 09:00 → T−1d 10:30 | **CLOSED** | Awaiting grading, and it is `dana.cohen`'s — U-34 |
| 6 | 4 / v1 | `6120` | T−7d 10:00 → T−7d 11:00 | **CLOSED** | Fully graded, stats frozen — U-43 |
| 7 | 7 / v1 | `7745` | T−5d 09:00 → T−5d 10:00 | **CLOSED** | Fully graded, stats frozen — U-43 |

Executions 3 and 4 are the two non-CLOSED rows, and their codes differ — the E9 service
rule (unique code among non-CLOSED executions) holds on the seed as loaded. Executions 5, 6 and 7
are CLOSED, so their `3318`, `6120` and `7745` are outside that rule and stay their own forever.

**Executions 6 and 7 exist because the reports screen had one row to compare** ⚑ (added
2026-08-30, live session, U-43). E15's report reads only sittings that are CLOSED **and** carry
frozen statistics, which is one sitting on the pre-U-43 dataset: `4821`. `7390` and `3318` are
closed and unmarked, `5164` has not opened and `2075` is running, so all four are correctly
excluded and the principal's three dimensions had exactly one row between them. A report screen
whose every answer is a single row cannot demonstrate a comparison, and it also cannot show that
the exclusion rule is doing anything, because there is nothing on the other side of it.

**The two are placed to give each dimension something different**, which is why they are not two
more Algebra sittings:

- **`6120` is exam 4 released a second time** (T−7d), so course 21 and `avi.mizrahi` both acquire
  a reportable sitting, and `7390` stays what it is: the same paper, a week later, still waiting
  to be marked. One exam, two sittings, one marked and one not, is what makes "reports read only
  what is finished" visible rather than asserted.
- **`7745` is the Biology exam** (T−5d), whose author, course and subject are all new in U-42, so
  the BY_TEACHER and BY_COURSE pickers each gain a third entry that has data behind it rather
  than a "nothing to report" label.
- **BY_STUDENT is where an actual multi-row comparison lives.** `noa.friedman` and `omer.katz`
  each sat all three frozen sittings, across three different courses; `itay.regev`,
  `shira.dahan` and `lior.gabay` sat two. The remaining seven students sat one, which is the
  single-row state the reports copy now explains rather than leaving blank.

**They are historical, so they take the wall-clock form** (`SeedTimes.dayOffsetAt`), exactly as
1, 2 and 5 do: a CLOSED sitting has to have closed however late in the day the seed is loaded.

**Two different kinds of `T` in that column, and the difference is load-bearing** (corrected
2026-08-26, B-10). Executions 1, 2, 5, 6 and 7 are historical, so their `T−14d 09:00`,
`T−3d 10:00`, `T−1d 09:00`, `T−7d 10:00` and `T−5d 09:00` mean *a wall-clock hour on a date
relative to the load date* — the loader resolves them with
`SeedTimes.dayOffsetAt`, which discards the load's own time of day. Executions 3 and 4 are the two
the demo needs to be *happening*, so their offsets are from the **load instant** itself, through
`SeedTimes.fromNow`: execution 4 opened half an hour ago and closes three hours from now,
execution 3 opens four hours from now and runs for two.

**Execution 4's window is three and a half hours, not two** (widened 2026-08-26, **B-14**). It straddles
the anchor, so what a student actually gets is whatever is left of it when she joins — and a two-hour
window around exam 1 v2's **75-minute** paper hands a walkthrough that reaches the take-exam step forty
minutes in a sitting shorter than the paper, which the server then ends at the bell. That is the fixture
reproducing B-14 rather than demonstrating S-2. Execution 3 moved `T+3h → T+4h` with it, because
`SeedLoadedDbContract` asserts that a SCHEDULED sitting opens strictly *after* the live one closes and two
windows meeting at an instant is not a property worth relying on.

Execution 3 used to read `T+0 14:00 → T+0 16:00`, resolved the historical way. That is "scheduled
for later today" only when the seed is loaded before 14:00 UTC — before 17:00 Israel time. Loaded
any afternoon it stored a `SCHEDULED` row whose window had already closed, and a single
`ReleaseScheduler` tick drove it `SCHEDULED → LIVE → CLOSED` within thirty seconds of the server
starting. **Anything this document wants to be in the future when it is read must be written as an
offset from the load instant**, never as an hour on the load date.

Execution 4 being the *same exam version* as execution 1 is the point: one exam, two
releases, separate codes, windows, participants and statistics. **Execution 5 makes it three**
(added 2026-08-29, manual round 3, U-34), and the claim does not weaken with the third: `4821`
is finished and approved, `2075` is running, `3318` is closed and unmarked, and no counter on
any of them can reach any other because `exam_executions` holds no participation columns at all.

**Exam 4 is now released twice as well** (2026-08-30, live session, U-43), and that second pair
is the stronger form of the same claim: `6120` and `7390` are one paper with **different frozen
outcomes** - `6120` mean 55, `7390` not yet a mean at all - where 1, 4 and 5 differ mostly in
status. Two sittings of one paper whose statistics disagree is what a principal's report is for.

### 9.1 Execution 1 — participation (S-21) and grades

All 8 students enrolled in Algebra (11) sat it, so the roster and the attempt list match
exactly. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final | note |
|---|---|---|---|---|---|
| 15 lior.gabay | SUBMITTED | 45 min | 100 | 100 | all seven correct; teacher comment below |
| 7 noa.friedman | SUBMITTED | 52 min | 90 | 90 | |
| 9 shira.dahan | SUBMITTED | 61 min | 85 | 85 | |
| 14 daniel.shapira | SUBMITTED | 58 min | 75 | 75 | |
| 8 itay.regev | SUBMITTED | 68 min | 70 | 70 | |
| 11 maya.levi | SUBMITTED | 70 min | 60 | 60 | teacher comment below |
| 13 yael.azulay | SUBMITTED | 73 min | 45 | **55** | **Manual override**, see below — the only fail turned into a pass |
| 10 omer.katz | **TIMED_OUT** | 75 min | 45 | 45 | Auto-submitted at expiry with four questions never reached — the S-19 "did not make it in time" row, and the one genuine fail; teacher comment below |

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
- Reason: `Question 11011 has a correct solution with a sign error on the last line, so partial credit was given.`
- Teacher comment to the student (S-22): `A clear improvement on inequalities. Worth revising the domain of definition.`

**Teacher comments to the student (S-22)** ⚑ (added 2026-08-29, manual round 2):

Four of these eight approved grades carry a comment and four do not, and both halves are
deliberate. Until this round the dataset held **one** comment, `yael.azulay`'s above, and it
rides the override — so the only way to see a note was to open the one grade a teacher had
changed by hand, and `maya.levi`, the account `DEMO_DAY.md` §2.3 signs in as, opened her
Algebra midterm to a card whose note line rendered as nothing. The three below are comments
**without** an override, which is also the point: S-22 is not a consequence of S-23.

| student | teacher comment |
|---|---|
| 15 lior.gabay | `Full marks with time to spare, so the harder practice set is the natural next step.` |
| 11 maya.levi | `Solid on the basics, and the harder inequality questions are where to put the next round of practice.` |
| 10 omer.katz | `Everything reached was correct, so pacing rather than the algebra is what to work on.` |

`yael.azulay`'s is the fourth and is written once, under the override above, because that is
where it is stored from. The four without a comment are `noa.friedman`, `shira.dahan`,
`daniel.shapira` and `itay.regev`: **an empty note is a state the card has to render too**, and
a sitting where every grade carries a comment demonstrates only half of what a student can open.

**§9.2 stays comment-free.** Nothing in execution 2 is approved, so S-24 means no student can
read any of it; a comment on a grade nobody can open would contradict the fixture it lives in.

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

**Executions 3 and 4 have no `attempt_answers`, because they have no attempts at all** (§9.3).
Every other execution has both: §9.2.1 and §9.4.1 give the grids of the two awaiting-grading
sittings, and §9.5.1 and §9.6.1 give the grids of the two U-43 sittings that are finished. This
paragraph said "executions 2, 3 and 4 have no `attempt_answers`" until 2026-08-29 (manual round
3, U-34), which §9.2.1 four screens below it had already contradicted.

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

### 9.4 Execution 5 — closed, awaiting grading, and it is Dana's ⚑ (added 2026-08-29, manual round 3, U-34)

Four of the eight students enrolled in Algebra (11) sat exam 1 v2 again, all
**SUBMITTED** — nobody timed out. Auto-scores are computed; **no grade is approved**.
`grades.status = AUTO` for all four, with `final_score`, `override_reason`, `teacher_comment`,
`approved_by` and `approved_at` all null. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final |
|---|---|---|---|---|
| 7 noa.friedman | SUBMITTED | 49 min | 85 | — |
| 9 shira.dahan | SUBMITTED | 57 min | 75 | — |
| 14 daniel.shapira | SUBMITTED | 63 min | 60 | — |
| 8 itay.regev | SUBMITTED | 71 min | 45 | — |

**Why this sitting exists (U-34).** `dana.cohen` opened Grading on a freshly seeded database and
read **"Nothing to grade"**. Nothing was broken: her only closed sitting, `4821`, is fully
approved and the queue excludes what is signed off (§9.1), and the one sitting that is waiting,
`7390`, was released by `avi.mizrahi` and is scoped to him (§9.2). Two correct rules, and the
demo teacher's grading screen empty on day one. This is §9.2's shape with §9.1's exam and
§9.1's teacher, so `dana.cohen` now owns one sitting in each state: `4821` finished, `3318`
waiting for her.

**Four students, not eight, and `maya.levi` is not one of them.** Both halves are decisions.
Four keeps this sitting's counters visibly unlike `7390`'s eight, so a walkthrough that opens
both queues cannot mistake one for the other. Leaving the demo student out keeps §9.1's story
intact: her My Grades holds **exactly one row** on a freshly seeded database, which cases 8.2,
9.1 and 17.3 all read, and an unapproved second Algebra grade behind it would change that count
the moment anybody approved this sitting during a walkthrough.

**`participation` and `stats` are not frozen.** Freezing happens once at close for a sitting
whose grading is done (S-21, S-25, §9.1); this one's has not started, so both JSON columns are
null and the counts a teacher sees are derived live from `exam_attempts` — exactly as they are
for `7390`.

Exam 1 v2 is 6×15 + 10, so the reachable totals are §9.1's fourteen values and every score above
is one of them. Solving times stay inside the paper's **75 minutes** and inside the 90-minute
window, and none equals either: nobody here ran out of time.

**The spread is deliberately unlike both other sittings.** Execution 1 finals are
`45, 55, 60, 70, 75, 85, 90, 100` and execution 2's autos are `30, 40, 55, 60, 70, 75, 85, 100`;
these are `45, 60, 75, 85`. **One student sits below the pass mark**, which is `itay.regev` on 45
— the same number §9.1's override moved to 55 — so the override demo has a second candidate that
nothing in the seed has already used.

#### 9.4.1 `attempt_answers` for execution 5

The same key as §9.1.1, because it is the same paper released again (S-2):

| # | question | correct | points |
|---|---|---|---|
| 1 | 11001 | 1 | 15 |
| 2 | 11002 | 2 | 15 |
| 3 | 11005 **v1** | 1 | 15 |
| 4 | 11007 | 3 | 15 |
| 5 | 11009 | 1 | 15 |
| 6 | 11010 | 2 | 15 |
| 7 | 11011 | 3 | 10 |

Every student answered every question — there are no `—` entries here, because nobody timed out.

| student | 11001 | 11002 | 11005 | 11007 | 11009 | 11010 | 11011 | auto |
|---|---|---|---|---|---|---|---|---|
| 7 noa.friedman | 1 | 2 | 1 | 2 | 1 | 2 | 3 | **85** |
| 9 shira.dahan | 1 | 2 | 1 | 3 | 2 | 2 | 1 | **75** |
| 14 daniel.shapira | 3 | 2 | 1 | 1 | 1 | 2 | 1 | **60** |
| 8 itay.regev | 1 | 4 | 1 | 3 | 3 | 1 | 2 | **45** |

**No row here repeats a row in §9.1.1**, and that is worth stating because three of these four
students sat that paper too: a student whose second sitting reproduced her first, cell for cell,
would make a per-question breakdown across the two look like a copied fixture rather than two
classes. Each of the four misses a different combination.

**`11011` is the most-missed question here** — three of four — where §9.1.1's were `11007` and
`11010`, and **`11005` is missed by nobody** in either. Two releases of one paper with different
difficulty profiles is what makes the S-2 claim visible on the results screens rather than only
in the row counts.

---

### 9.5 Execution 6 — closed, fully graded, and it is Avi's ⚑ (added 2026-08-30, live session, U-43)

Six of the eight students enrolled in Java (21) sat exam 4 v1 a week before `7390` did, all
**SUBMITTED** — nobody timed out. Every grade is **APPROVED** by `4 avi.mizrahi`, the teacher who
released it (T-8.2), `approved_at` = close + 2 days, and participation and statistics are
**frozen**. No override anywhere, so `final` equals `auto` on every row. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final |
|---|---|---|---|---|
| 18 eitan.solomon | SUBMITTED | 38 min | 90 | 90 |
| 7 noa.friedman | SUBMITTED | 44 min | 70 | 70 |
| 17 roni.malka | SUBMITTED | 47 min | 55 | 55 |
| 8 itay.regev | SUBMITTED | 51 min | 45 | 45 |
| 12 noam.peretz | SUBMITTED | 42 min | 40 | 40 |
| 10 omer.katz | SUBMITTED | 52 min | 30 | 30 |

**Six students, and `maya.levi` is deliberately not one of them.** §9.4 left her out of `3318`
for a reason that binds harder here: her My Grades holds **exactly one row** on a freshly seeded
database, which cases 8.2, 9.1 and 17.3 all read, and `3318` could only have broken that count if
somebody approved it during a walkthrough. This sitting is approved *in the seed*, so putting her
on it would change that count on load, with nobody having pressed anything. `daniel.shapira` is
the second of the eight left out, which keeps the roster at six and the sitting's counters
visibly unlike `7390`'s eight.

**No comments and no override.** S-22 and S-23 are demonstrated on `4821`, where the four
commented grades sit beside four uncommented ones and the single override moves a real student
across the pass mark. Repeating either here would add rows to those sweeps without adding a
state; what this sitting is for is a **second frozen statistics record**, and it is the only
thing it adds.

**Solving times stay inside the paper's 60 minutes and inside the 60-minute window**, and none
equals either: nobody here ran out of time. The window is exactly as long as the paper, which is
a shape the dataset did not previously hold — `4821`, `7390` and `3318` all give a window longer
than the paper. It is legal (a student who joins at the bell gets no time, which is what
`ExecutionContext.deadlineFor` reconciles) and it is historical, so nothing in the demo has to
survive it.

**Frozen `participation` JSON:** `{"started": 6, "finished": 6, "timed_out": 0}`

**Frozen `stats` JSON** (computed from the final column — S-25):

| metric | value |
|---|---|
| mean | 55 |
| median | 50 |
| stddev | 20 |
| min | 30 |
| max | 90 |
| pass rate | 3 / 6 = 0.5 |
| deciles | 30–39: 1 · 40–49: 2 · 50–59: 1 · 70–79: 1 · 90–100: 1 |

> **Hand-checkable, on §9.1's rule.** Finals are 30, 40, 45, 55, 70, 90, summing to 330, so the
> mean is exactly **55** — which is also the pass mark, so three of the six are at or above it
> and the pass rate is exactly **0.5**. The median is the mean of the two middle scores,
> (45 + 55) / 2 = **50**. **The standard deviation is the population form**, divisor n, the same
> convention §9.1 fixes: Σ(x−55)² = 625 + 225 + 100 + 0 + 225 + 1225 = **2400**, so
> σ = √(2400/6) = √400 = exactly **20**. The sample form would give ≈21.9.

**This sitting is deliberately the weak one.** `4821` reads mean 72.5, σ 17.5, pass 0.875 and
`6120` reads mean 55, σ 20, pass 0.5, so a report that puts them side by side shows two sittings
that are genuinely different rather than two roundings of one class. It is also the reason the
pass rate is exactly a half: a principal reading "3 of 6 (50%)" beside "7 of 8 (87.5%)" can check
both numerators against the row counts on the same screen.

#### 9.5.1 `attempt_answers` for execution 6

Exam 4 v1's key, from §7.3 — the same paper §9.2.1 tabulates, because it is the same paper
released a second time (S-2):

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

| student | 21001 | 21002 | 21005 | 21006 | 21009 | 21010 | 21011 | auto |
|---|---|---|---|---|---|---|---|---|
| 18 eitan.solomon | 1 | 2 | 1 | 2 | 2 | 3 | 1 | **90** |
| 7 noa.friedman | 1 | 2 | 1 | 2 | 1 | 1 | 4 | **70** |
| 17 roni.malka | 1 | 1 | 1 | 1 | 2 | 1 | 4 | **55** |
| 8 itay.regev | 2 | 2 | 2 | 2 | 1 | 3 | 1 | **45** |
| 12 noam.peretz | 1 | 3 | 3 | 3 | 2 | 2 | 4 | **40** |
| 10 omer.katz | 2 | 1 | 1 | 1 | 1 | 3 | 2 | **30** |

**No row here repeats the same student's row in §9.2.1**, and all six of these students sat that
paper too. Two sittings of one paper in which a student reproduced her own answers cell for cell
would make a per-question breakdown across the two look like a copied fixture.

**Every question was missed by somebody and answered by somebody**, four correct on `21001` and
`21005` and three on the other five, which is the opposite profile to §9.2.1's — there `21010`
was missed by five of eight and `21001` by nobody. One paper, two classes, two difficulty
profiles: that is what a per-question comparison across sittings is supposed to be able to show.

---

### 9.6 Execution 7 — the Biology sitting, closed and fully graded ⚑ (added 2026-08-30, live session, U-43)

Five of the six students enrolled in Biology (31) sat exam 7 v1, all **SUBMITTED** — nobody timed
out. Every grade is **APPROVED** by `19 galit.stern`, who wrote the paper and released it,
`approved_at` = close + 2 days, and participation and statistics are **frozen**. No override, so
`final` equals `auto` on every row. `extra_minutes = 0`.

| student | attempt status | solving time (S-19) | auto | final |
|---|---|---|---|---|
| 16 tal.harari | SUBMITTED | 31 min | 100 | 100 |
| 15 lior.gabay | SUBMITTED | 36 min | 80 | 80 |
| 7 noa.friedman | SUBMITTED | 38 min | 70 | 70 |
| 9 shira.dahan | SUBMITTED | 41 min | 55 | 55 |
| 10 omer.katz | SUBMITTED | 44 min | 50 | 50 |

**Five of six, not six of six.** `maya.levi` is enrolled in Biology and did not sit it, for
§9.5's reason: her My Grades count is read by three acceptance cases and an approved grade added
on load would change it. That leaves a roster of six with five attempts, which is a shape the
dataset did not have — §9.1's eight-of-eight makes the roster and the attempt list identical, so
"one enrolled student did not sit it" had nowhere to be seen.

**Solving times stay inside the paper's 45 minutes and inside the 60-minute window**, and none
equals either.

**Frozen `participation` JSON:** `{"started": 5, "finished": 5, "timed_out": 0}`

**Frozen `stats` JSON** (computed from the final column — S-25):

| metric | value |
|---|---|
| mean | 71 |
| median | 70 |
| stddev | 18 |
| min | 50 |
| max | 100 |
| pass rate | 4 / 5 = 0.8 |
| deciles | 50–59: 2 · 70–79: 1 · 80–89: 1 · 90–100: 1 |

> **Hand-checkable, on §9.1's rule.** Finals are 50, 55, 70, 80, 100, summing to 355, so the mean
> is exactly **71**. Five scores, so the median is the third one, **70**. Population σ, divisor n:
> Σ(x−71)² = 441 + 256 + 1 + 81 + 841 = **1620**, so σ = √(1620/5) = √324 = exactly **18**. The
> sample form would give ≈20.1. Four of the five are at or above the pass mark of 55, so the pass
> rate is exactly **0.8**; `omer.katz`'s 50 is the one fail.

**Three frozen sittings, three different shapes.** `4821` is 8 students, mean 72.5, σ 17.5,
pass 0.875; `6120` is 6 students, mean 55, σ 20, pass 0.5; `7745` is 5 students, mean 71, σ 18,
pass 0.8. No two share a participant count, a mean, a σ or a pass rate, so no report row can be
mistaken for another and no aggregate over them can be reproduced by weighting them wrongly. A
participant-weighted mean over the three is (72.5×8 + 55×6 + 71×5) / 19 = 1260 / 19 ≈ **66.3**,
where the unweighted mean of the three means would be ≈66.2: close enough to look the same and
different enough to prove which one a screen is printing.

#### 9.6.1 `attempt_answers` for execution 7

Exam 7 v1's key, from §7.6 and §8.1. **The points are not flat**, which is the whole reason this
paper exists in the shape it does:

| # | question | correct | points |
|---|---|---|---|
| 1 | 31001 | 1 | 15 |
| 2 | 31004 | 4 | 15 |
| 3 | 31002 | 2 | 20 |
| 4 | 31005 | 1 | 20 |
| 5 | 31003 | 3 | 30 |

Every student answered every question — there are no `—` entries here, because nobody timed out.

| student | 31001 | 31004 | 31002 | 31005 | 31003 | auto |
|---|---|---|---|---|---|---|
| 16 tal.harari | 1 | 4 | 2 | 1 | 3 | **100** |
| 15 lior.gabay | 1 | 4 | 2 | 3 | 3 | **80** |
| 7 noa.friedman | 1 | 4 | 2 | 1 | 1 | **70** |
| 9 shira.dahan | 1 | 1 | 2 | 1 | 2 | **55** |
| 10 omer.katz | 2 | 1 | 2 | 4 | 3 | **50** |

**Every score here is one the flat papers cannot produce, and that is the point.** 80 needs a
20-point question missed, 70 needs the 30 missed, 55 needs a 15 and the 30 missed, and 50 needs
everything except the 20 and the 30. A 6×15 + 10 paper reaches 70 and 55 too, but it cannot reach
80 or 50 at all, so an auto-grader that ignored the stored points and assumed a flat paper would
produce four wrong totals out of five here and none at all anywhere else in the dataset.

**`31002` is missed by nobody and `31003` is the most-missed**, three of five getting the HARD
30-pointer wrong — which is what makes it worth 30. `noa.friedman` and `omer.katz` sat all three
frozen sittings, in three different courses, so the BY_STUDENT report has a genuine three-row
comparison rather than a list of one.

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
| 1 | 11 | Study assistant: Algebra | yes |
| 2 | 12 | Study assistant: Calculus | yes |
| 3 | 21 | Java Study Assistant | yes |
| 4 | 22 | Databases Study Assistant | **no** — inactive, for the S-31 refusal demo |

Bot 4 is seeded **inactive** on purpose: S-31 says a student may use the bot only if
enrolled *and* the bot is active. Without an inactive bot there is no way to demo the
second half of that rule.

**Biology 31, Chemistry 41 and Physics 51 have no bot at all** (2026-08-30, live session, U-42).
S-30 is "at most one bot per course", not "every course has one", and the manager screen's
create-a-bot path (E16.12) is demonstrated by a teacher pressing the button on a course that has
none. Before U-42 every course already had one, so that half of the screen could only be shown by
deleting something first. Three bot-less courses is now the fixture for it, and the corpus stays
2171 words across eight sources: writing three more grounded corpora would have been content work
with nothing reading it, and an ungrounded bot is worse than no bot.

### 10.1 Sources (8)

**These are the complete sources, not extracts.** `BotSection.java` carries these
paragraphs verbatim and no fuller version exists anywhere else in the repository, so
this section and that file are one artefact in two places: **changing a body here
without changing it there is a contract break**, and `SeedLoadedDbTest` fails when they
disagree.

**Volume, stated plainly:** the corpus is **2171 words across eight sources**, roughly
271 words each. Per bot that is 562 words for Algebra, 655 for Calculus, 480 for Java and
474 for Databases.

The four mathematics sources were **translated from Hebrew to English in UI wave 1**
(F-13), against the ruling in `docs/reports/lead/MANUAL-PASS-1.md`. Only the language
changed: each body still teaches the same syllabus in the same order, and the counts
above were recomputed from the translated text rather than carried over. The Java and
Databases sources were written in English and are untouched.

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

**Source 1** · bot 1 · TEXT · `Linear equations: a summary`
> A linear equation is an equation in which the variable appears only to the first power, with no powers, roots or products of unknowns. Its general form is ax + b = 0 where a is not zero, and its single solution is x = -b/a. It is solved by isolating the variable: move terms across the equals sign changing their sign, collect like terms, and finally divide by the coefficient of the variable. When the equation contains fractions, multiply both sides by the common denominator to clear them before isolating. When it contains brackets, open them first using the distributive law. A system of two equations in two unknowns is solved by one of two methods. In substitution, isolate one variable in one of the equations and substitute the resulting expression into the second. In elimination, multiply the equations by suitable numbers so that the coefficients of one variable are opposite, then add the equations and that variable cancels out. Both methods give the same answer, and choosing between them is a matter of convenience: substitution is easy when one variable has a coefficient of one, and elimination is easy when the coefficients are already close. Every system has exactly three possibilities, and each of them has a geometric meaning. If the lines cross at one point there is a single solution. If both equations describe the same line there are infinitely many solutions, and the algebra ends in a true statement such as 0 = 0. If they describe parallel lines there is no solution at all, and the algebra ends in a false statement such as 0 = 5. The most common mistake is forgetting to change the sign when moving a term across.

**Source 2** · bot 1 · PDF · `Quadratic functions: chapter 3`
> A quadratic function is a function of the form y = ax² + bx + c where a is not zero. Its graph is a parabola, and the coefficient a decides which way it opens: if a is positive the parabola opens upwards and has a minimum point, and if a is negative it opens downwards and has a maximum point. The larger the absolute value of a, the narrower the parabola. The roots of the function, meaning the points where it crosses the x axis, are found with the formula x = (-b ± √(b²-4ac)) / 2a. The expression b²-4ac is called the discriminant and is usually written as delta. It decides how many roots there are: if it is positive there are two different roots, if it is zero there is a single double root and the parabola touches the x axis, and if it is negative there are no real roots and the whole parabola lies either above the axis or below it. The point where the graph crosses the y axis is always found by substituting x = 0 and therefore equals c. The axis of symmetry of the parabola is the line x = -b/2a, and the vertex of the parabola lies on that axis. Substituting this value into the function gives the extreme value. When both roots are known, the axis of symmetry is also halfway between them, which is a quicker way to compute the vertex. The function can also be written in vertex form y = a(x-p)² + k, where (p,k) is the vertex. That form is convenient for sketching and for recognising translations of the graph.

**Source 3** · bot 2 · TEXT · `Limits: definition and use`
> The limit of a function at a point describes what the values of the function approach as the variable approaches that point, without referring at all to the value of the function at the point itself. That distinction is essential: a function can be undefined at a point and still have a limit there, and conversely the value of the function at a point can differ from the limit. When the limit exists and equals the value of the function at the point, the function is said to be continuous at that point. A point can be approached from either direction, so a limit from the right and a limit from the left are defined as well. The limit exists if and only if both one-sided limits exist and are equal to each other. When they differ, the function jumps at that point and has no limit there. To compute one, try direct substitution first. If the substitution gives a number, that number is the limit. When direct substitution gives an expression of the form zero over zero, the limit is indeterminate and needs algebraic work before substituting. The three main techniques are factorising and cancelling the common factor that makes the denominator zero, multiplying by the conjugate when a root appears, and using known special limits. If the substitution gives a number other than zero over zero, the limit is infinity or minus infinity and the function has a vertical asymptote at that point. A limit at infinity describes the behaviour of the function far out. For a rational function, compare the powers of the numerator and the denominator: if the power in the denominator is larger the limit is zero, if they are equal the limit is the ratio of the leading coefficients, and if the power in the numerator is larger the limit is infinity. A finite limit at infinity indicates a horizontal asymptote.

**Source 4** · bot 2 · PDF · `Rules of differentiation`
> The derivative measures the rate of change of a function, and geometrically it is the slope of the tangent to the graph of the function at a point. It is defined as the limit of the difference quotient as the difference tends to zero, but in practice it is computed with the rules of differentiation rather than from the definition. The basic rules of differentiation: the derivative of a constant is zero; the derivative of x to the power n is n times x to the power n minus one; the derivative of a sum is the sum of the derivatives; and the derivative of a constant times a function is the constant times the derivative. The derivative of a product is given by f'g + fg', and note that it is not the product of the derivatives. The derivative of a quotient is given by (f'g - fg')/g², and the order in the numerator matters. The chain rule states that the derivative of a composition of functions is the outer derivative times the inner derivative, and it is the rule needed every time one function appears inside another. The main use of the derivative is investigating functions. Candidate extreme points are found where the derivative is zero. To decide which kind of extreme point it is, check the sign of the derivative on both sides of the point: a change from positive to negative indicates a maximum, and a change from negative to positive indicates a minimum. Alternatively use the second derivative test: if the second derivative at the point is positive it is a minimum, and if it is negative it is a maximum. The function increases on an interval where the derivative is positive and decreases on an interval where it is negative. Points where the second derivative is zero and changes sign are inflection points, where the concavity of the graph changes direction. A full investigation covers the domain, the intercepts with the axes, the intervals of increase and decrease, and the extreme points.

**Source 5** · bot 3 · DOCX · `OOP Fundamentals: Lecture Notes`
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
| 1 | 1 | 7 noa.friedman | T−12d | `deepseek` | How do you solve an equation with fractions? | Multiply both sides by the common denominator to clear the fractions, then solve as usual. |
| 2 | 1 | 11 maya.levi | T−10d | `deepseek` | What is a discriminant? | The expression b²-4ac. Its sign decides how many real roots the parabola has. |
| 3 | 1 | 7 noa.friedman | T−9d | `deepseek` | When does a parabola have no roots? | When the discriminant is negative, the whole parabola lies above the x axis or entirely below it. |
| 4 | 2 | 16 tal.harari | T−8d | `deepseek` | Why is the limit of sin(x)/x at zero equal to 1? | It is a special limit, proved geometrically with the unit circle and the squeeze theorem. |
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
the ten rows below and is what a re-load actually matches on.

The `seed_id` column stays because that composite key is useless in a sentence — an acceptance
case, a demo script or a failing assertion needs to say *which* notification, and
`N-EXEC-CLOSED-ALG` does that where "the row for user 1 with type EXECUTION_CLOSED and title
'Sitting finished…'" does not. It is documentation vocabulary, and every id here maps to exactly one
row.

**The moment that stops being true is a real column.** The key holds only because no recipient
gets two notifications of the same type with the same title. Seed a repeating notification —
two grade publications to the same student, say — and the composite collapses; that is when
`notifications` gains a `seed_id` column rather than when someone finds it tidier.

The `#` column is presentation order only and carries no meaning.

| seed_id | # | recipient | type | title | read |
|---|---|---|---|---|---|
| `N-EXAM-REJECTED-ALG` | 1 | 2 dana.cohen | APPROVAL_REJECTED | Exam sent back for revision: version 1 of "Midterm: Algebra" | read |
| `N-EXAM-PENDING-CALC` | 2 | 2 dana.cohen | APPROVAL_REQUESTED | The exam was sent to the subject coordinator for approval | unread |
| `N-APPROVAL-REQ-MATH` | 3 | 3 rina.barak | APPROVAL_REQUESTED | An exam is waiting for your approval in Mathematics | unread |
| `N-EXAM-REJECTED-JAVA` | 4 | 5 tamar.shani | APPROVAL_REJECTED | Collections Quiz was returned for revision | unread |
| `N-GRADE-NOA` | 5 | 7 noa.friedman | GRADE_PUBLISHED | Your grade for Midterm: Algebra is available | read |
| `N-GRADE-YAEL` | 6 | 13 yael.azulay | GRADE_PUBLISHED | Your grade is available, including a teacher's comment | unread |
| `N-GRADING-DUE-JAVA` | 7 | 4 avi.mizrahi | GRADING_DUE | 8 attempts awaiting your grade approval | unread |
| `N-EXEC-CLOSED-ALG` | 8 | 1 principal.avia | EXECUTION_CLOSED | Sitting finished: 8 students, average 72.5 | unread |
| `N-GRADE-MAYA` | 9 | 11 maya.levi | GRADE_PUBLISHED | Your grade is ready | unread |
| `N-GRADING-DUE-ALG` | 10 | 2 dana.cohen | GRADING_DUE | 4 attempts awaiting your grade approval | unread |

`N-EXEC-CLOSED-ALG` exists so the principal's first screen is not empty at login: S-7 makes
her read-only, so she can never generate her own activity. **Its title quotes the mean, so it is
derived data in a text column** — it reads 72.5, matching §9.1's frozen stats. It said 78 until
the score fix; anything that changes the seeded grades has to change this string too, which is
exactly the sort of copy nobody thinks to re-check.

`N-GRADING-DUE-JAVA` says eight attempts, which is §9.2's eight AUTO grades — the same coupling,
and it stays true as long as the Java roster stays at eight. **U-43 did not move it**: `6120` is
fully approved, so it contributes nothing to anybody's grading queue, and the count is grades
awaiting approval rather than grades in existence.

**`N-GRADE-MAYA` ⚑ (added 2026-08-26, B-25).** Acceptance case 17.3 found that the eight rows
above reach seven recipients and **`maya.levi` is not one of them** — 0 items, 0 unread — while
she is the student this document, `DEMO_ACCOUNTS.md` and the acceptance table use throughout,
and the account `DEMO_DAY.md` §2.3 signs in as on the clean-machine pass. The one bell a grader
is most likely to open was the empty one.

Row 9 is the notification the seed's own story already justifies: her Algebra midterm grade is
approved and visible (§9.1 gives her **60**), so a `GRADE_PUBLISHED` for it is a row the product
itself would have written on approval. Three things about it are deliberate:

- **It is the catalog's words, not seed-only copy.** Title `Your grade is ready`, body
  `Your grade for Midterm: Algebra has been published.` — exactly what
  `NotificationCatalog.gradePublished` composes, so the bell on the day shows what a live
  approval produces.
- **It deep-links, and it is still the only seeded row that does.** Every other row in this
  table carries no `ref_type`/`ref_id` at all, so clicking one goes nowhere. Hers stores
  `grades` + her own attempt id on sitting `4821`, resolved at load time because the id is
  whatever `AUTO_INCREMENT` gave it.
- **Its title differs from `N-GRADE-NOA`'s and `N-GRADE-YAEL`'s on purpose**, because the
  idempotency key is recipient + type + title and a third "Your grade for … is available" to a
  third student would be fine, but a repeat of either sentence to the same person would collapse
  the composite. This is the constraint the section warns about above, met rather than tripped.

**`N-GRADE-MAYA` keeps the catalog's words now that `maya.levi` has a comment** (2026-08-29,
manual round 2). §9.1 gives her a teacher comment this round, and `N-GRADE-YAEL`'s title reads
"Your grade is available, including a teacher's comment", so the tidy-looking move is to say the
same on hers. **It is not made.** Her title and body are `NotificationCatalog.gradePublished`
*verbatim*, and that sentence carries no comment clause whether the approval it followed had one
or not: `GradeApprovalService` composes the same words either way. Adding the clause would give
the seed a notification the product cannot produce, which is the one property this row exists to
have and the one `SeedLoadedDbContract` asserts on it in as many words. The comment is read where
the product puts it, on the grade.

`N-GRADE-YAEL`'s clause is seed-only copy written before the catalog had that sentence. It stays:
its title is half the idempotency key, and rewording it would orphan the row on the next load. It
is the string to revisit if the product ever composes a comment-aware body, and it is the reason
`noa.friedman`, whose title makes no such promise, is one of the four §9.1 grades deliberately
left without a comment.

**`N-GRADING-DUE-ALG` ⚑ (added 2026-08-29, manual round 3, U-34).** Execution 5 gives
`dana.cohen` a closed sitting with nothing approved on it (§9.4), and `avi.mizrahi` has had a
`GRADING_DUE` for exactly that situation since the section was written. Hers is the same row for
the same reason: **a queue with work in it and no bell saying so is the state that let "Nothing to
grade" go unnoticed in the first place.** It says four attempts, which is §9.4's four AUTO grades,
and it carries the same coupling `N-GRADING-DUE-JAVA` does: change the roster on that sitting and
this string changes with it. It is the second row in this table whose title quotes a count, and
both are counts a test recomputes rather than reads.

It carries no `ref_type`/`ref_id`. `N-GRADE-MAYA` is still the only seeded row that deep-links:
the catalog composes a grading-due draft without a target, and inventing one here would give the
seed a notification the product does not produce, which is precisely the property `N-GRADE-MAYA`
exists to have.

**Row count: 375 → 376** with `N-GRADE-MAYA`: one row in `notifications` (8 → 9) and nothing
else moved.

**Row count: 376 → 414** with execution 5 (2026-08-29, manual round 3, U-34). Five tables move
and no other does: `exam_executions` 4 → 5, `exam_attempts` 16 → 20, `attempt_answers` 108 → 136
(four students × seven questions, none absent), `grades` 16 → 20, and `notifications` 9 → 10 for
`N-GRADING-DUE-ALG`. That is +1 +4 +28 +4 +1 = **+38**.

**Row count: 414 → 581** with U-42 and U-43 (2026-08-30, live session). Fifteen tables move and
**`notifications` is not one of them**:

| table | was | is | why |
|---|---|---|---|
| `subjects` | 2 | 5 | U-42: Biology, Chemistry, Physics |
| `courses` | 4 | 7 | U-42: 31, 41, 51 |
| `users` | 18 | 21 | U-42: one teacher per new subject |
| `course_teachers` | 5 | 8 | U-42: one per new course |
| `coordinators` | 2 | 5 | U-42: one per new subject |
| `enrollments` | 29 | 47 | U-42: six students in each of three courses |
| `questions` | 40 | 58 | U-42: six per new course |
| `question_versions` | 43 | 61 | U-42: one version each, no second versions |
| `exams` | 6 | 7 | U-43: the Biology exam `303101` |
| `exam_versions` | 7 | 8 | U-43: its one APPROVED version |
| `exam_version_questions` | 39 | 44 | U-43: its five slots |
| `exam_executions` | 5 | 7 | U-43: `6120` and `7745` |
| `exam_attempts` | 20 | 31 | U-43: six on `6120`, five on `7745` |
| `attempt_answers` | 136 | 203 | U-43: 6×7 = 42 and 5×5 = 25, none absent |
| `grades` | 20 | 31 | U-43: eleven, all APPROVED |
| `notifications` | 10 | 10 | **unchanged, deliberately — see below** |

That is +3 +3 +3 +3 +3 +18 +18 +18 +1 +1 +5 +2 +11 +67 +11 = **+167**. The bot tables do not
move: §10 explains why the three new courses have no bot.

**⚑ Why the two new sittings add no notification, when `4821` has three.** `4821` carries
`N-GRADE-NOA`, `N-GRADE-YAEL` and `N-GRADE-MAYA`, so the obvious move is a `GRADE_PUBLISHED` per
student on `6120` and `7745` and an `EXECUTION_CLOSED` to the principal for each. **It is not
made, and the reason is the warning four paragraphs above this one.** The idempotency key is
recipient + type + title, and eight of the eleven new grades belong to students who already hold
a `GRADE_PUBLISHED` row: a second one to `noa.friedman` would need a title that differs from
"Your grade for Midterm: Algebra is available" purely to keep a composite key from collapsing,
which is copy written to satisfy a loader rather than a reader. That is exactly the moment §11
names as the moment `notifications` gains a real `seed_id` column, and adding a column to make a
demo bell fuller is not a trade this round is willing to make. The three existing
`GRADE_PUBLISHED` rows already prove the bell renders a published grade, and `N-GRADE-MAYA`
already proves one deep-links.

Every recipient is the person the event actually concerns: rejections and pending-approval
notices go to the **author** (`dana.cohen`, `tamar.shani`), the approval request goes to the
**subject coordinator** (`rina.barak`), grading-due notices go to the **teacher who released the
sitting** (`avi.mizrahi` for `7390`, `dana.cohen` for `3318`), and grade publications go to the
**students who sat the exam** (`noa.friedman`, `yael.azulay`, `maya.levi` — all three sat §9.1's Algebra midterm). A notification addressed to someone with no stake in the event is the kind of
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
| 6 | Does anything validate the national-id check digit? | Naji | Nothing in the PRD says so. Mine are all checksum-valid Israeli national id anyway, so the answer cannot break the demo either way. |
