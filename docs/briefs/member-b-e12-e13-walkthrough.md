# Walkthrough — E12 grading and E13 student results

**Author:** Member B · **Audience:** Naji and Omar, for E22.4 · **Date:** 2026-08-22

Written so either of you can answer defense questions on these two epics without having built
them. Read the first two sections and you can tell the story; read the rest and you can survive
being pushed on it.

---

## 1. The story, in one sentence

**A student hands in, the server marks it immediately, nothing is visible to her until a teacher
signs it off, and then she can open her own marked paper — and only her own.**

Everything below is that sentence with the reasons attached.

## 2. The pipeline, in five steps

Say these in order. Each is one class, and each hands off to the next.

| # | What happens | Where |
|---|---|---|
| 1 | A student submits, or the server force-submits at expiry | `AttemptService` (E10) |
| 2 | The closed attempt is marked **immediately**, against the *pinned* question versions | `GradingOnSubmit` → `GradingService` → `AutoGrader` |
| 3 | The grade is stored as `AUTO`. **Nobody can see it** | `grades.status = AUTO` |
| 4 | A teacher reviews, optionally overrides with a written reason, and approves | `GradingQueueService`, `OverrideService`, `GradeApprovalService` |
| 5 | Approval publishes: the student's list gains a row, and she may open the marked paper | `ResultsService`, `CheckedFormService` |

**The join between 1 and 2 is the interesting one.** Take-exam and grading were built by
different people at different times, so the seam is an interface — `AttemptFinalizedListener` —
that E10 declared with a documented no-op and E12 later filled. Two properties are in its
contract and both matter:

- It runs **after** the closing transaction has committed, never inside it. A slow or broken
  grader must never roll back a submission a student has already been told succeeded.
- It **never throws at its caller.** One bad row must not break handing in for a room full of
  students. An ungraded paper is recoverable; a vanished submission is not.

---

## 3. E12 — the teacher's side

**Auto-grading (E12.1).** Marks against the question versions the exam **pinned**, never the
latest — a question edited after the exam was sat must not change the marking. Unanswered scores
zero, and a timed-out attempt is graded like any other: it was sat and failed, not absent.
Re-grading an already-graded attempt returns the existing row untouched, because an overwrite
would silently discard a teacher's override.

**The queue (E12.5)** lists sittings that are **closed** and still have something unapproved.
Its value is what it *leaves out*: a sitting still running is not a task yet, one nobody has
marked has nothing to approve, and one already signed off is history and belongs on the results
screen. A queue that never empties stops being read.

**Override (E12.3)** requires a written reason, always. The machine's score is **kept** beside
the new one, so the change stays visible — that is the audit trail. Overriding is allowed only
while the grade is `AUTO`; once approved, the answer is `CONFLICT`, because the student has
already been told.

**Approve (E12.2/E12.7)** is one verb for one grade or fifty. It is **idempotent** — re-approving
counts and is not an error — and crucially it does **not re-stamp** who approved it or when.
Partial success is normal: approving eight of ten is a result, not a failed transaction. When the
last grade of a sitting is approved, the statistics are computed and **frozen** into the
execution in the same transaction.

## 4. E13 — the student's side

**My Grades (E13.3)** shows **approved rows only**. There is no client-side filter, deliberately:
a filter would imply the server might send something it should not, and the next person would
trust the filter instead of the server.

**The checked form (E13.4)** is the only path by which correctness data ever reaches a student,
and it has **three gates**: the grade is hers, it is approved, and the execution is closed. The
third is the one people forget and the one that matters most — while a sitting is open, handing
one student the answer key hands it to the room.

---

## 5. The four decisions you will be pushed on

**1. Ownership is the query, not a check.** The student reads filter on her id *in SQL*
(`findApprovedForStudent`, `findForStudent`). There is no code path that loads someone else's row
and then remembers to drop it. *A forgotten check is a bug; a filter that was never written
cannot be forgotten.*

The one unscoped read is named `findByIdUnscoped` so every call site confesses at review time —
the same mechanism as the `ForAuthoring` and `ForGrading` suffixes on reads that carry an answer
key. **If anyone greps for `ForCheckedForm`:** it was a sanctioned suffix in
`CorrectnessLeakGuardTest` with **no readers**, and it was **withdrawn on 2026-08-23** by the
lead's ruling (contract amendment A4). The contract anticipated a dedicated read; the
implementation shares the grading assembler instead, with a second gate in front of it. That is
the better design — one place an answer key becomes rows — and a sanctioned name nobody reads
through is a permission standing open, so it came out of the list with a test asserting it
stays out.

**2. Every refusal is the same answer.** Not yours, not approved yet, sitting still open, never
existed — all one `NOT_FOUND` with one sentence. Four distinguishable answers would let a student
probe for which grades exist and what state they are in. The tests assert the refusals are
**equal to each other**, not merely that each refuses — "all four refuse" is also true of an
implementation that leaks.

**3. The "adjusted" marker keys on the scores *differing*, not on a final score existing.**
Approving sets `finalScore` to the auto score when nobody overrode, so every approved row has
one. A marker driven by presence would tell an entire class their papers had been changed by
hand. This is the single best example of a bug that would have looked fine in every demo.

**4. After a write, re-read.** The grading screen re-requests the sitting rather than patching
rows it already has. An approval can be partly refused, an override moves a score *and* a state,
and freezing statistics happens server-side where the client cannot see it. One round trip
removes all three drift paths.

---

## 6. Questions you will be asked, with answers

**"What stops a student from seeing another student's grade?"**
The query, not a check — see decision 1. And if she guesses a grade id, the answer is
indistinguishable from a grade that does not exist. Acceptance case 9.4 is the live probe.

**"Can a teacher change a grade after approving it?"**
No. `CONFLICT`, by design. Re-opening an approved grade is a real workflow and a deliberate v1
non-goal — the alternative is a second notification path nobody has designed. Say that plainly;
it is a scope decision, not an oversight.

**"What if the grader crashes mid-marking?"**
The submission is already committed and safe. The listener swallows the failure, logs which
attempt failed, and the paper shows in the teacher's queue as unmarked. An ungraded paper is
recoverable by hand; a lost submission is not.

**"Why freeze the statistics instead of computing them on demand?"**
So the numbers a teacher sees are the ones that existed when grading finished, rather than a
recomputation that drifts as data changes later. Written in the *same transaction* as the last
approval.

**"Why population standard deviation and not sample?"**
The class is the whole population, not a sample of one. Divisor `n`. It is recorded in the seed
document so the stored figures and any recomputation cannot disagree by a point and look like a
bug. Σ(x−72.5)² = 2450 over 8 students gives exactly 17.5 — hand-checkable.

**"Why is the pass mark a constant in code?"**
`ScoreStatistics.PASS_MARK = 55`, one place. If it were configurable it would need a migration, a
screen and a story about what happens to already-frozen statistics — none of which the PRD asks
for.

**"A timed-out student got four questions wrong?"**
No — **four questions he never reached**, which is a different fact. Both score zero, and the
checked form says "Not answered" rather than "Wrong". Collapsing them would present a paper he
ran out of time on as a paper full of mistakes.

## 7. What to demo, in order

The seed is built so each step makes one point:

1. **`maya.levi` → My Grades.** She sat **two** exams; **one row appears.** The Java 100 is
   invisible because nobody approved it — that is the whole publishing rule, visible in one
   screen.
2. **Open the row.** Her marked paper: her answers, the correct ones, points per question.
3. **`omer.katz` → his result.** "Time ran out — submitted automatically · 75 minutes", and four
   questions marked **Not answered**.
4. **`avi.mizrahi` → Grading.** The Java sitting is waiting: 8 sat, 8 marked, 8 to approve.
   Override one with a reason, approve the rest, watch the queue empty.
5. **Back to the student.** The grade she could not see is now there.

## 8. The one gap — say it before you are asked

**E12.6's full paper review is not built.** `GRADE_REVIEW_GET` is on the router and the screen
does not use it: a teacher can change a score from the table, but cannot open the student's
marked paper to see *why* she lost marks first. The assembler exists and is shared with the
student's checked form; only the screen is missing.

Also outstanding: the checked form's marking colours are unstyled pending the design review, and
every outcome deliberately carries a **word** as well as a colour, because red/green alone is an
accessibility failure.
