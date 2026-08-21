# Rule-5 post-merge pass — E8 and E14's additions to `server/db`

**Read-only. No code changed by this pass.** Findings go to whoever owns the code, or become their
own PR.

Requested in E8's report §4 and E14's §3, both of which self-flagged their additions rather than
waiting to be found. That is the rule working.

## 0. Scope, measured rather than remembered

**~2,300 lines across 25 files, six commits, two people**, added to `server/db/**` since my last
pass at PR 2b. I had been carrying "~960 lines from five commits" since 2026-08-20; that figure
predates E8, E14 and E16 and was never re-measured. Correcting it here because a stale number in a
plan is the same defect as a stale number in a report.

| commit | who | lines |
|---|---|---|
| `0a44ff6` E10/E11 take-exam | lead | 803 |
| `ecc0a72` E8 + E14 | lead | 752 |
| `52c65cc` E16 bot | lead | 559 |
| `cb9d14c` E12.1 `ForGrading` | Member B | 78 |
| `666db63` E12.2 approve | Member B | 68 |
| `e56cc3f` E12/E13 wire freeze | lead | 56 |

**This pass covers `ecc0a72` only**, risk-ordered. Three days to the defense and handlers are the
critical path, so an even read of 2,300 lines would spend the same attention on a getter as on the
answer-key boundary. E10/E11 and E16 remain, and are named here so their absence is a decision
rather than an omission.

## 1. Defects

### 1.1 The licence admitting the new key-bearing read understates who reaches it

`CorrectnessLeakGuardTest`'s inventory comment, added by E8 to admit `findAnswerKeyForAuthoring`:

> *"It is reached by `EXAM_PREVIEW_GET` only, behind `requireRole(COORDINATOR)` plus
> `requireCoordinatorOf` on the exam's subject or the version's own author"*

`ApprovalService.preview` actually does:

```java
Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);   // :195
...
if (!version.isAuthoredBy(callerId)) {
    requireCoordinatorOf(caller, version, data);                     // :208
}
```

So a **TEACHER** who authored the version reaches the answer key with **no coordinator check at
all**. The role gate is TEACHER-or-COORDINATOR, not COORDINATOR.

**The behaviour is right** and I am not asking for it to change: a teacher previewing her own exam
should see its key, and `theAuthorMayReadItBack` exists precisely to make a rejection reason
actionable. **The licence text is what is wrong**, and it matters because the licence is the entire
mechanism by which a key-bearing read joins the sanctioned inventory. A licence whose stated
conditions are narrower than the code's is a weaker argument than the code deserves, and the next
person to widen that branch will check it against a sentence that already permits less than the
code does.

**Cost: one sentence.** Suggested: *"behind `requireRole(TEACHER, COORDINATOR)`, then either the
caller authored the version or `requireCoordinatorOf` passes on its subject."*

### 1.2 No test covers a teacher who is neither the author nor a coordinator

The `EXAM_PREVIEW_GET` block exercises `RINA` as COORDINATOR (four times), `DANA` as TEACHER **and
author**, and `MICHAL` as a **coordinator of another subject** (refused). Nothing covers the
fourth cell: a plain TEACHER who did not author the version and coordinates nothing.

**The code refuses her correctly** — `isAuthoredBy` is false, so `requireCoordinatorOf` runs, and
its first line is `requireRole(COORDINATOR)`, which throws. I traced it rather than assumed it.
But it is untested, and it is the exact cell §1.1's wording glosses over. That combination — the
licence omits the role, and the suite omits the role — is how a widening lands unnoticed.

**Cost: four lines**, mirroring `notSomebodyElsesExam` with a TEACHER caller.

## 2. Observations, no action requested

### 2.1 `supersedePendingVersions` bypasses `@Version`, and is safe because of something unwritten

The bulk `createMutationQuery` is correctly status-guarded (`status = :pending`), scoped to one
exam, and excludes the kept version. But `ExamVersion` carries `@Version lock_version`, and a bulk
HQL update does not increment it.

That is **not** a defect today: every other mutation on that row re-reads it and requires
`status == PENDING` *and* a matching `lockVersion`, so a superseded version fails on status before
the missing bump could matter. `ApprovalService:60` states the status half of that rule.

Worth writing down because the safety is a **dependency between two files**: the supersede is safe
only while every other mutation checks status and not just `lockVersion`. A future mutation guarded
on the token alone would silently lose that protection, and nothing would fail.

### 2.2 `findPendingForCoordinator` is scoped as claimed — verified, not taken on trust

E8's report says it is scoped "by a `coordinators` join, not by a filter". It is:
`exists (select 1 from Coordinator co where co.teacherId = :coordinatorId and co.subjectCode =
c.subjectCode)`, correlated on the subject and driven by the session's caller id. Nothing from the
payload reaches the predicate.

Recorded because "I checked and it is right" is information, and because this is the same shape as
the coordinator-scope defect the E6 pre-build audit found.

## 3. Answering E14's question 2, which was addressed to me

> *"`passCount` reconstitution. It is `round(passRate × count)`. Confirm that counts as 'reading
> the stored figure' rather than as a recomputation — the alternative is adding `count` and
> `passCount` to the stored `ExecutionStats` JSON, which is Member A's shape."*

**It counts as reading, and I am not adding the columns. Measured rather than argued:**

`ExecutionStats.passRate` is a `double`, documented as a fraction 0..1, serialised into JSON at
full precision. It holds `passCount / count` exactly to about fifteen significant digits, so
`round(passRate × count)` recovers the integer with an error around `1e-13`. Reconstruction would
only drift if the stored rate were coarsely quantised — a percentage rounded to a whole number,
say — and it is not.

`count` as the sum of the deciles is exact by construction, not an approximation: the distribution
partitions the attempts.

**So no migration**, which three days out is also the answer I would want for a schema change with
no behavioural gain. If the stored type ever becomes a rounded percentage, this answer expires and
the columns become right.

## 4. What this pass did not cover

`0a44ff6` (E10/E11, 803 lines), `52c65cc` (E16, 559 lines), and Member B's two commits (146 lines).
Named so the gap is visible. E10/E11 is the largest single surface and the one I would do next.

## 5. Method note

Two findings, both small, both in documentation-versus-code rather than in behaviour. That is worth
saying plainly: **E8's repository additions are in-style, correctly suffixed, correctly scoped, and
I found no behavioural defect in them.** A rule-5 pass that manufactured severity to justify itself
would be worse than one that reports two precise things and says the rest is sound.

Every finding was traced in the file before being written here. That discipline came from today:
the E6 audit reported both authorization directories as unwired, and the lead's *was* wired at
`HSTSServer:215`. Relaying that unchecked would have handed him a false criticism of his own code.

---

# Cold pass over the same code

Run after my own read. My reasoning for expecting little from it was that reviewing another
author's work is the one case where I am already the outside view, so the correlated-error argument
that earns an auditor on my own code does not apply. **It found substantially more than I did**,
which is worth recording as evidence against that reasoning rather than quietly omitting.

Every finding below was traced in the file before being written here.

## 6. The one behavioural defect: the answer key is labelled with numbers that are not the paper's

`JpaApprovalStore.answerKeyOf` numbers the key with a 1..N counter. The paper beside it carries the
**stored** position. They are different numbers, and the schema permits them to differ.

`QuestionRepository.findAnswerKeyForAuthoring` **orders by `evq.ordinal` and never selects it**, so
the store has nothing to use but a counter. `findForTakeExam` does select it, and `QuestionCardView`
renders it verbatim as `"Question " + ordinal`.

`V3__exams.sql` constrains `ord` as `NOT NULL`, `UNIQUE (exam_version_id, ord)` and `CHECK (ord >= 1)`.
**Unique and at least 1. Not contiguous, not required to start at 1.** So an exam whose positions
have a gap shows the coordinator "Q3 · option 2" beside a paper whose Q3 is a different question.
She approves it having checked the wrong answers, which is the exact failure E8.4 exists to prevent.

**It is latent today, and that is the important part.** Nothing currently produces a gap: the seed
writes a contiguous counter and no merged code removes a question from an exam version. The two
numbers agree by accident.

**E7's builder is what ends the accident** — removing a question from a draft is the obvious
producer, and E7 is mine. Not my defect, but my trigger, which is why I would rather write the fix
and its test than hand it over: a test written without knowing what E7 emits is a test written
against tidy data, and tidy data is why nothing catches this now.

**Proposed split, needing one word from you rather than an assumption.** The read is in
`QuestionRepository` (mine), the mapping is in `JpaApprovalStore` (yours), and neither half works
alone. I suggest I do both and you review. About ten lines plus a contract case with a deliberately
gapped `ord`.

## 7. A coordinator can read the answer key of a DRAFT exam

`ApprovalService.preview` checks the role, then author-or-coordinator, then serves the key. **No
status check on that path** — any version in any state, including a draft another teacher is still
writing, and including rejected and superseded ones.

Arguably correct: she is staff of that subject and F4.1 does not forbid it. **I am not asserting it
should be refused.** But it is an unstated scope decision on the one verb that hands out answer
keys, and every javadoc, the `Verb` documentation and the contract all frame this verb as "the exam
she is approving". Either add the guard or write the widening down.

## 8. Two javadocs in one commit give opposite accounts of the same mechanism

`ExamRepository.supersedePendingVersions` says a coordinator looking at a superseded version is
"caught by the compare-and-set on her own decision instead". **She is not.** The bulk update leaves
`lock_version` untouched, so her token still matches and the compare-and-set passes. What refuses
her is the **status** check. `ApprovalService` states it correctly, and explicitly notes that a bulk
update does not bump `@Version`.

So the two files a maintainer would consult disagree, and the wrong one sits directly above the
method creating the hazard. **E7's `submitForApproval` is its first production caller**, will be
written by me, and I would have read that javadoc.

Confirmed and worth stating: **no live mutation path guards on a lock token without also guarding on
status.** The exposure is entirely prospective.

## 9. Three more copies of the COORDINATOR-only claim

Beyond §1.1, the same false description appears in **`Verb.java`** ("Every verb here is
COORDINATOR-gated … `MY_APPROVALS_GET` is the single exception" — `EXAM_PREVIEW_GET` is a second
exception, in both halves) and in **`PreviewAnswerRow`**'s javadoc.

`APPROVAL_WIRE_CONTRACT.md` is the only one of the four that is right. **Three of four durable
records say COORDINATOR-only**, and the `Verb.java` one is the header a wire reviewer reads first.
P-6's pattern at scale rather than in isolation.

## 10. `PreviewAnswerRow` carries a key in a package no guard scans

`CorrectnessLeakGuardTest` scans `server/db/projections` and `server/db/repos`;
`ExamWireLeakGuardTest` scans `common/dto/exam`. `common/dto/approval` is in neither, so the only
thing gating that type is the handler check.

`APPROVAL_WIRE_CONTRACT.md` states it the other way round — that the `ForAuthoring` suffix keeps the
guard truthful and that placing the block in its own package "is what keeps that guard meaningful
instead of suppressed". The suffix keeps the guard truthful about the **repository read**; it says
nothing about the DTO, and the package placement moves the type outside every scan's range.

**Worth one exchange rather than either of us building it twice:** you are writing a scan over
`common/dto/bank` now. If it grows a second root for `common/dto/approval` while you are in there,
this closes at no extra cost.

## 11. Participants is counted live, contradicting "frozen at close"

`TeacherResultsService` takes it from `AttemptRepository.countAttemptsByExecution`, a live count.
The execution row already carries a frozen `Participation`, and my own `ExecutionsSection` says why:
"Frozen at close (S-21) rather than counted live, which is what the JSON column is for."

It sits on F9.2's stat cards beside five figures the class javadoc insists are "read, never
recomputed". For a CLOSED execution S-21 makes the frozen column the authority. The seed cannot
expose the drift, because it seeds exactly eight attempt rows against a stored `started = 8`.

## 12. Smaller, all confirmed

- **An orphaned javadoc in `ExecutionRepository`**: the block for `findExecutionIdsWithLiveAttempts`
  now sits above `findContextsByExamAuthor`, so the first is undocumented and the second carries the
  wrong contract. **The same commit fixed this exact defect in `ExamRepository`** and created a new
  one at the same kind of seam. My file; I will move it.
- **Two `Consumer:` lines naming callers that do not exist.** `CourseRepository.findSubjectOf` has no
  production caller — `ApprovalService` reads `subjectCode` off `ExamVersionContext`, which the
  select already joins. And `ExamRepository.findPendingVersions`' reattached javadoc names E8.2's
  supersede, which never reads the siblings. Rule 5 makes the consumer line load-bearing rather than
  decorative, so these are real. My files.
- **Self-rejection notifies the coordinator about her own action.** The `&&` at `ApprovalService:387`
  is dead, so a coordinator rejecting her own exam is notified about herself with her own name as
  approver, and `logSelfApproval` fires only on approve so it leaves no F4.3 trace.
- **`min` and `max` pass through `FrozenStatistics` unvalidated** while every other field is checked.

## 13. What the cold pass confirmed sound

The larger half, and worth recording. Scoping across all eight new reads takes nothing from a
payload. `CallerContext` cannot be forged from a DTO. `requireCoordinatorOf` is fail-closed on every
input. `GradeRepository.findResultRows` settles authorship one step earlier and returns not-yours
and not-there identically. `ExamPaper.toWire` genuinely shares the student's type, so "she sees what
the student sees" holds structurally.

**E14's statistics are sound end to end, verified rather than inspected.** `deciles()` buckets every
score into exactly one of ten, so the sum *is* the population by construction; cross-checked against
the seed's stored `[0,0,0,0,1,1,1,2,1,2]` and the eight grades producing it. Empty, single-attempt,
all-pass and all-fail all round-trip exactly.

## 14. Ownership and size

| finding | owner | size |
|---|---|---|
| §6 question numbering | **split** — read mine, mapping yours. I propose I do both, you review | ~10 lines + a test |
| §7 draft answer keys | yours | 3 lines or a paragraph |
| §8 contradictory javadoc | mine (my file, your words) | 4 lines |
| §9 three COORDINATOR-only claims | yours (`Verb`, `PreviewAnswerRow`); mine (the guard comment) | 3 sentences |
| §10 unscanned DTO package | yours if the bank guard grows a root; mine after E6 otherwise | one exchange |
| §11 participants live | yours | one read swap |
| §12 orphaned javadoc, dead consumer lines | mine | 3 lines |
| §12 self-rejection, min/max | yours | 3 lines |

**Nothing here is large.** About an hour across two people, and §6 is the only one with a
consequence rather than a correction.
