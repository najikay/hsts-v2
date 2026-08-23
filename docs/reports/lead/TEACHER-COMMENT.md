# S-22 — the teacher's comment gets a write path

**Built by:** implementation pass for [L]
**Date:** 2026-08-23
**Scope:** S-22 / acceptance case 8.4, contract amendment A3 (+ A4), and two riders the lead
attached to the same patch.
**Ruling implemented:** Option A — `teacherComment` becomes an optional component of the
existing `GradeOverrideRequest`. Commenting rides the override; no new verb.
**Status:** `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_tcomment ./mvnw -B clean verify`
green. Numbers in §1.

---

## 1. Verify

```
Tests run: 5231, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS  (15:58 min)
```

| | Value |
|---|---|
| Tests | **5231**, 0 failures, 0 errors, 0 skipped |
| Previous recorded total (E15.2 report) | 5131 |
| Added by this pass | **+100**, of which 66 are the parameterized copy-rule expansion |
| Instruction coverage, bundle | **98.15%** (64 748 / 65 968) |
| JaCoCo BUNDLE gate (≥90%) | **met** |

MySQL ran (`HSTS_TEST_SCHEMA=hsts_tcomment`): every `*MySqlTest` leaf including the new
`TeacherCommentFlowMySqlTest`. No coverage exclusion was added or changed.

Coverage of the packages this pass touched:

| Package | Instruction coverage |
|---|---|
| `common/dto/grading` | **100.00%** (527 / 527) |
| `server/features/grading` | 96.16% (2504 / 2604) |
| `client/features/grading` | 92.76% (653 / 704) |
| `server/features/results` | 99.21% (1250 / 1260) |
| `client/features/results` | 98.59% (1466 / 1487) |
| `server/db/repos` | 99.16% (2370 / 2390) |

`client/features/grading` is the lowest of these and the number is not this patch's: with
`GradingQueueView` excluded by name, the package is `GradingCopy` (2 instructions missed, the
em-dash branch of `closedAt`) plus `GradingQueueSession`, whose misses are the pre-existing
`busy`-guard early returns and failure branches. Nothing added here is uncovered — the new
request record is at 100%, and both the four-argument and the retained three-argument
`override` paths are exercised.

---

## 2. The gap, stated once

`teacherComment` could be **read** everywhere and **written** nowhere.

It was a column on `grades`, a component of `StudentGradeRow`, preserved by both student
containers, populated by three server assemblers, rendered as a column on My Grades and under a
heading on the checked form, and written down in the frozen contract. No request payload carried
a comment field and no service called `setTeacherComment` — the only caller in the product was
`GradesSection`, the seed loader. S-22 had a read path and no wire, and case 8.4 ("change a
grade with a justification, and **add a comment to the student**") could not pass.

Found by Member B. It is written up as **PROBLEMS P-7**, because the interesting part is not the
missing field but why nothing caught it: `yael.azulay`'s seeded comment meant every screen that
reads one had something to show, so the demo looked finished. A demo cannot distinguish "renders
data" from "can produce data", and seeded data is exactly what makes the two look identical.

## 3. What changed

**`GradeOverrideRequest` gains a fourth component, appended last.**

```java
GradeOverrideRequest(long gradeId, int newScore, String justification, String teacherComment)
```

- **Nullable; blank collapses to null** in the compact constructor, `strip` not `trim` (house
  rule). One representation of "she wrote nothing", so every later decision is a null test.
- **Mirrors `justification`'s discipline exactly**: no maximum length, no shape rule, no new
  error code and no new refusal. `justification` has none of those either, and the column behind
  both is MySQL `TEXT`. A limit invented for the comment would be a rule the audit trail beside
  it does not have.
- **The three-component constructor is retained**, delegating with a null comment — the same
  move `ReleaseCreateRequest` made for its optional code. Every existing call site and test
  compiles unchanged and still means what it meant. `serialVersionUID` goes 1 → 2, on the
  precedent `StudentGradeRow` and `CheckedForm` set at their amendments.

**`OverrideService` writes it in the same transaction as the score, guarded on presence.**

```java
grade.override(request.newScore(), request.justification());
if (request.hasComment()) {
    grade.setTeacherComment(request.teacherComment());
}
```

**The null-preserves rule.** ⚑ An override carrying no comment leaves any existing comment
**unchanged**. It does not clear it.

That is the one decision in this patch that is not obvious, so it is stated in three places: the
record's javadoc, the service's javadoc, and the contract's A3. Correcting a score twice is
ordinary — a teacher fixes a mark, then fixes it again after the moderation meeting — and the
dialog's comment box opens empty every time. If null meant "clear it", her second correction
would silently delete the sentence she wrote to the student on the first, and no screen would
say so. **There is therefore no way to clear a comment on this wire at all**; removing one is a
v2 shape. It is pinned by `OverrideServiceTest$Comment.nullCommentPreservesWhatIsAlreadyThere`
and again through the database in `TeacherCommentFlowContract`, both of which override *twice* —
an implementation that assigned the comment unconditionally passes every single-override test in
the suite.

**The dialog gets a second box, separated from the first.** Same moment, same paper, two
different readers: the reason is the audit trail and never leaves the staff room, the comment is
the only free text the student ever sees. A `Separator` between them and a label on each, because
placement alone cannot say which piece of writing a box is for. The comment box opens **empty
even when the grade already has a comment**, and its label says leaving it empty keeps what is
saved — pre-filling would be friendlier right up until the first teacher cleared the box
expecting the comment to disappear, which on this wire it does not.

Copy added to `GradingCopy`: `COMMENT_LABEL`, `COMMENT_PROMPT`, `JUSTIFICATION_PROMPT` (the
prompt was a bare literal in the view; it is catalogue copy now).

**One existing string changed:** `JUSTIFICATION_LABEL` had an em dash, which PRD §4.1 forbids —
it predates the copy scan this pass added. Split into two sentences; every existing assertion on
it still passes.

## 4. Why A and not B, recorded

A standalone `GRADE_COMMENT_SET` verb was considered and **declined for v1**. The contract says
so in A3, in the lead's words: *commenting rides the adjustment for v1; a standalone
`GRADE_COMMENT_SET` is the v2 shape.*

The argument for riding, as implemented: the two acts happen at one moment in front of one paper
(T-8.3 has her writing both in the same dialog), so a second verb would have bought a second
round trip, a second copy of the ownership and state gates saying the same thing, and the
possibility of a comment landing on a grade whose score change had just been refused. Riding
means the comment inherits every gate the override has, `CONFLICT` included — asserted at the
service, at the handler and end to end.

What B would buy is written into "deliberately absent" so it is not lost: commenting **without**
changing a score, and clearing a comment. Both wait for v2.

## 5. Read side: verified, not changed

No read-side change was needed. Checked, as asked:

| Surface | Renders `teacherComment`? |
|---|---|
| `CheckedFormView` (student's marked paper) | **Yes** — `CheckedFormCopy.teacherNote` into a labelled note box |
| `MyGradesView` (student's list) | **Yes** — `MyGradesCopy.comment`, its own column, em dash when empty |
| `CheckedFormService` / `GradeReviewService` / `GradingQueueService` assemblers | **Yes**, all three populate it |

⚑ **A finding, noted rather than fixed** (per the brief): **no teacher-facing screen renders the
comment at all.** `GradingQueueService` puts it on every teacher row and `TeacherResultsService`
puts it on every E14 row, and neither table has a column for it; `GRADE_OVERRIDE` answers with a
refreshed `GradeReview` carrying it and `GradingQueueSession` discards that response in favour of
re-reading the sitting. So a teacher can write a comment and has nowhere to read it back — she
would have to re-open the dialog, which shows her an empty box by design.

This is a real gap in T-8.3 but it is E12.6's, not this patch's: the per-student review screen
(still unticked in TODO) is where a paper with its comment belongs. **Wants a lead ruling** — see
§8.

## 6. Shared-file touch list

Files outside this pass's own feature, with what was done and the risk:

| File | Change | Risk |
|---|---|---|
| `common/dto/grading/GradeOverrideRequest.java` | The amendment. Component appended last, old arity retained | Low. Every existing call site compiles and behaves identically; proved by the untouched tests in six suites |
| `common/dto/grading/CheckedForm.java` | Javadoc only — the withdrawn `ForCheckedForm` licence | None |
| `common/dto/grading/AnswerReviewRow.java` | Javadoc only — same | None |
| `server/features/grading/GradingHandlers.java` | Javadoc only; the shared gate forwards the payload untouched | None |
| `server/features/grading/GradingReads.java` | Javadoc only — the "open naming decision" it describes has been settled since E12 and now says so | None |
| `server/db/projections/BotBankQuestion.java` | Javadoc only — named the two sanctioned suffixes and one of them no longer exists (E16's file, one word) | None |
| `server/db/repos/CorrectnessLeakGuardTest.java` | **Security guard.** `ForCheckedForm` removed from `SANCTIONED_SUFFIXES`; +1 test asserting the withdrawal | Low, and argued in §7 |
| `common/dto/DtoSerializationTest.java` | +1 test, +2 assertions on the existing override case | None |
| `docs/contracts/GRADING_WIRE_CONTRACT.md` | **Appended** A3 and A4 plus one "deliberately absent" bullet. **No frozen text edited** | None |
| `docs/contracts/BOT_WIRE_CONTRACT.md` | One word: the suffix pair it names | None |
| `docs/ACCEPTANCE_TESTS.md` | Three numbers — see §8 | Low |
| `docs/briefs/member-b-e12-e13-walkthrough.md` | One paragraph — the defence brief said the suffix's fate "is with Naji", and it now is not | None, and it keeps defence material true |
| `docs/PROBLEMS.md`, `docs/TODO.md` | P-7 appended; E12.3 ticked, E12.6 annotated | None |

**Left exactly as found:** the uncommitted `docs/contracts/BANK_WIRE_CONTRACT.md` freeze header.

**Amendment numbering.** GRADING's two existing amendments are named rather than numbered, so
the new "Additive amendments" section states the mapping before using it: amendment v1.1 is A1,
the checked-form amendment is A2, and this one is **A3**. The frozen inline text is untouched;
the section only refers to it.

## 7. Rider: `ForCheckedForm` withdrawn (amendment A4)

Verified before removing, as instructed: **nothing uses the suffix.** No method in
`server/db/repos` ends with `ForCheckedForm` — `CHECKED_FORM_GET` reads through
`findVersionsForGrading`, because E13.4 shares `GradeReviewService.answers` with the teacher's
review. Member B raised it in PR 17 and declined to edit a security guard on his own judgement.

Removed, on the guard's own stated rule. The javadoc says a suffix stops being licensed when the
feature behind it goes away; a licensed name with **no readers** is the same hole from the other
direction — a permission standing open that nobody exercises and nobody watches, which the next
key-bearing student read could have taken without a single test noticing.

The removal is asserted rather than merely implied: `checkedFormSuffixIsNoLongerLicensed` checks
the entry is gone, that `findAnswersForCheckedForm` is no longer sanctioned, **and** that no
compiled repository method ends with the name — so if the dedicated read ever arrives, the test
fails and the licensing argument has to arrive with it. Nothing about the checked form's three
gates changes; E13.1's authorization tests were always the licence and still hold. The contract
records it as A4, since the frozen scope section names the suffix and frozen text is not edited.

## 8. Rider: the acceptance numbers — and a third one

The brief named **two** stale `71`s from PR #37's 71→60 correction. There are **three**, all in
the same two cells:

1. 9.1, closing sentence: `would have read only "71 / 100"` → **60 / 100** ✅
2. 9.2: `Maya's 71 was never overridden` → **60** ✅
3. 9.1, opening clause: `Algebra (execution 1, 71, approved)` → **60** ✅ *(not in the brief)*

I fixed the third as well and am flagging it rather than burying it. It is the same value from
the same correction, the existing `corrected 2026-08-22` note covers it identically, and leaving
it would have made the cell contradict itself in its own first line while the sentence below it
read 60. No new notes were added, per the instruction. **If you would rather the third stayed as
it was, it is a one-word revert.**

## 9. Tests

| Suite | Now | What this pass added |
|---|---|---|
| `GradingDtoTest` | 23 | **+4** — `OverrideComment`: blank/empty/null/`\t\n ` all collapse to null; a real comment is stripped and kept (Hebrew); the v1 three-arg constructor equals the four-arg one with null; the justification is **not** normalised |
| `DtoSerializationTest` | 29 | **+1** and 1 assertion — a comment round-trips through Java serialization (Hebrew, padded), and a blank one arrives as null on the far side. A record deserializes through its canonical constructor, so the collapse runs again on receipt and is the only code between a padded comment and the database |
| `OverrideServiceTest` | 15 | **+6** — `Comment`: persisted with the override; ⚑ **null preserves** (two overrides); blank preserves; a new comment replaces; refused with `CONFLICT` after approval; not written for an unowned grade |
| `GradingHandlersTest` | 24 | **+4** — the comment reaches the service unaltered (equality on the whole payload, so a forward that dropped the fourth component fails); it is optional; it does not rescue a blank justification; it inherits `CONFLICT` |
| `GradingQueueSessionTest` | 24 | **+5** — sends the comment; optional and never refused; blank travels as null; the reason is still mandatory alongside a comment; the three-argument call still means "no comment" |
| `GradingCopyTest` | 81 | **+69** — the §4.1 scan (66 parameterized cases over 22 constants: no em dash, no shouting, sentence case) plus `theScanHasTeeth`, the comment label's three promises, and the two labels not converging |
| `CorrectnessLeakGuardTest` | 6 | **+1** — the withdrawal asserted three ways: not in the list, not sanctioned, and no compiled repository method ends with the name |
| `TeacherCommentFlowH2Test` / `…MySqlTest` | 5 / 5 | **new** — acceptance 8.4 end to end on both engines |

**The end-to-end test is deliberately not a unit test with a mocked service.** Every unit test
involved here passed happily while the feature did not exist — that is the failure mode P-7
describes. What was missing was a *path*, and only a walk can prove a path, so
`TeacherCommentFlowContract` runs 8.4's four steps through the real services against the real
schema: override with reason and comment, inspect the stored record, approve, then open the
student's own checked form and her grade list. It asserts both halves of S-23 at the far end —
**the comment arrives and the justification does not** — and it starts from a grade with no
comment on it, so the walk creates the thing it asserts rather than reading the seed's.

The MySQL leaf earns its place: `grades.teacher_comment` is a `TEXT` column holding Hebrew under
`utf8mb4`, and "the comment survives the round trip" is a claim about that column rather than
about an entity in memory.

**`GradingCopyTest` gained the scan the other copy suites have.** It was checking four
enumerated strings; it now harvests every public `String` constant and runs the §4.1 rules over
all of them, which is what makes the two new strings covered rather than merely tested. Two
constants are skipped with the reason stated in the javadoc: `STYLE_CLASS` is a CSS selector, and
`COLUMN_ADJUSTED` is deliberately empty (the marker column has no heading).

## 10. Wants a lead ruling

1. **No teacher can read a comment back** (§5). Nothing renders `teacherComment` on any teacher
   surface, though two assemblers put it on the wire. Options as I see them: a column on the
   grading table (cheap, and a table cell is a poor place for a paragraph), a read-only "current
   comment" line inside the override dialog above the empty box (cheapest honest fix, and it
   makes the null-preserves rule visible instead of only promised), or leave it to E12.6's
   per-student review screen, which is where a paper belongs. I lean to the second **and** the
   third: the dialog line now, the screen when E12.6 lands.
2. **The third acceptance number** (§8) — kept or reverted, your call.
3. **Case 8.4 is now walkable but still unticked** in `ACCEPTANCE_TESTS.md`. I did not tick it:
   the automated walk proves the path, and the acceptance table records manual runs against a
   running server. It is ready for the next acceptance pass.
