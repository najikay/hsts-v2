# E7 exam builder — wire types landed

**Lead, 2026-08-23.** Types and tests only. No handler, no service, no validator, no screen: those
are Member A's E7 epic and are deliberately absent from this change, so he builds against a shape
that is already compiled, already tested and already on `main`.

**Status: the contract stays DRAFT.** The tick on E7.9 is the freeze, and the freeze happens on
Member A's handlers PR — same as BANK. Until then a handler author who finds a real problem with a
shape gets to say so, and the correction is cheaper today than it will be on the far side.

---

## 1. What landed

### `common/protocol/Verb.java` — the `Exam builder (E7)` section

Seven verbs, dated, in one group, each javadoc'd in the established voice with its caller, its
payload, its response and its error codes:

| Verb | Request | OK response | Guard |
|---|---|---|---|
| `EXAM_LIST` | *(none)* | `ExamList` | role; author-only **filter in the SQL** |
| `EXAM_VERSION_GET` | `ExamVersionRequest` | `ExamComposition` | author → `NOT_FOUND` |
| `EXAM_CREATE` | `ExamCreateRequest` | `ExamComposition` | `requireTeachesCourse` **throws** |
| `EXAM_VERSION_SAVE` | `ExamVersionSave` | `ExamComposition` | author → `NOT_FOUND` |
| `EXAM_VERSION_REVISE` | `ExamVersionAction` | `ExamComposition` (new DRAFT) | author → `NOT_FOUND` |
| `EXAM_SUBMIT` | `ExamVersionAction` | `ExamComposition` (now PENDING) | author → `NOT_FOUND` |
| `EXAM_AUTO_COMPOSE` | `AutoComposeRequest` | `AutoComposeResult` | `requireTeachesCourse` **throws** |

`MY_APPROVALS_GET` is **still live and was not removed.** Contract §8 binds its retirement to the
same PR as E7.10's screen swap. Removing it now would open the very window that rule exists to
close, from the other side: E8's `MyApprovalsView` would be calling a verb that no longer exists.
Its javadoc now points forward at `EXAM_LIST` and records what does and does not cross over, and
`VerbTest.myApprovalsGetHasNotRetiredYet` asserts it is still there so nobody tidies it away early.

### `common/dto/authoring` — fourteen records and a package-info

All `Serializable` records with `serialVersionUID`. **No enum is declared here**: `Difficulty` is
`common.dto.bank`'s and `ApprovalState` is `common.dto.approval`'s, as the contract instructs, and
`AuthoringDtoTest.noEnumIsRedeclared` pins the component types so a future copy is a failing build.

**Inbound (7)** — `ExamVersionRequest`, `ExamVersionAction`, `QuestionPin`, `ExamCreateRequest`,
`ExamVersionSave`, `AutoComposeRequest`, `TopicQuota`.
**Outbound (7)** — `ExamList`, `ExamListRow`, `ExamVersionRow`, `ExamComposition`,
`ComposedQuestion`, `AutoComposeResult`, `Shortfall`.

---

## 2. The shared-file touch list

Everything below is a file more than one epic reads. Nothing else outside `common/dto/authoring`
and its tests was touched.

| File | Change |
|---|---|
| `src/main/java/common/protocol/Verb.java` | **new** `Exam builder (E7)` section, seven verbs, placed between E12/E13 and E8. `MY_APPROVALS_GET` javadoc extended with the forward pointer; **nothing removed, nothing renamed** |
| `docs/contracts/EXAM_BUILDER_WIRE_CONTRACT.md` | status header; §5.3 `600 → 480` in the table row and the paragraph, each marked; §5.3 gained the two quota VALIDATION rules and the imported `strip()` limit; **new §12** with the five rulings |
| `docs/TODO.md` | E7.9 annotated, **not ticked** |
| `src/test/java/common/protocol/VerbTest.java` | four new methods. **No route table or verb count needed updating** — the file pins the *push* count (7, unchanged: E7 adds no push) and never pinned a total verb count |
| `src/main/java/common/dto/authoring/**` | new package, 15 files including `package-info.java` |
| `src/test/java/common/dto/authoring/**` | new, 2 files |

**No leak-guard licence was added, and none was needed.** `WireDtoLeakGuardTest`,
`BankWireLeakGuardTest`, `CorrectnessLeakGuardTest` and `ExamWireLeakGuardTest` were run first,
before the tests were written, and all four are green with their licence lists untouched. The
contract's §9 claim — "E7 adds no type to the correctness boundary and the leak guard's licensed
list does not grow" — is therefore true as asserted by the build rather than as remembered by a
reader. No component needed renaming to achieve it: `ComposedQuestion` carries a stem and nothing
a student could not see, so there was never anything for the predicate to catch.

---

## 3. Test inventory

| Suite | Tests | What it holds |
|---|---|---|
| `common.dto.authoring.AuthoringDtoTest` | **47** | six nested groups: `Constants` (3), `Inbound` (12), `Criteria` (9), `Outbound` (16), `Composition` (6), `ReusedEnums` (1) |
| `common.dto.authoring.AutoComposeResultTest` | **14** | `Quadrants` (7) and `Report` (7) — the invariant and the four shortfall shapes |
| `common.protocol.VerbTest` | 83 → **87** | four new methods, below |

**`AuthoringDtoTest` covers**, mirroring the bank DTO suites: serialization round-trips on every
record with Hebrew content throughout; tolerant copies proved with a **null list** and a **null
element** on all three inbound list carriers; blank-to-null on both optional texts; `strip()` and
not `trim()` on `courseCode`, with U+2003 EM SPACE stripped **and U+00A0 pinned as surviving**;
value semantics and defensive-copy immutability; and outbound `requireNonNull` throws asserted
field by field on all five null-checking outbound records.

**`AutoComposeResultTest` covers** all four quadrants of the invariant — feasible + questions ok,
infeasible + shortfalls ok, **both empty throws**, **mismatched `feasible` throws** — plus
both-populated, null lists, strict copies, and the four `Shortfall` shapes from contract §7.1
including the PRD's own `("Algebra", HARD, 5, 2)`.

**New in `VerbTest`**: `examBuilderVerbsExist` (seven, by `valueOf`, because the name is what
travels between two shipped JARs), `noExamBuilderVerbIsAPush`, `myApprovalsGetHasNotRetiredYet`,
`deliberatelyAbsentExamBuilderVerbs` (no `EXAM_DELETE`, no second bank-browse verb, no release verb
— so a handler author reading an older TODO cannot reintroduce one).

### Coverage

`common/dto/authoring`: **100.00% instruction (751/751), 100.00% branch, 100.00% method.** The gate
is a 90% bundle instruction ratio; a DTO suite should sit near 100 and this one is at it — every
compact constructor, every factory and every derived accessor is exercised on both sides of every
branch. The one gap the first jacoco run showed (`ExamVersionSave.hasStudentText()` /
`hasTeacherText()` each proved on only one side) was closed rather than waived.

Bundle after the change: **98.17% instruction, 91.30% branch** — green, and unmoved in the
direction that matters.

**Full gate green**: `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_e7types ./mvnw -B clean verify`
→ `BUILD SUCCESS`, **5317 tests, 0 failures, 0 errors**.

---

## 4. What Member A must know before writing handlers

### 4.1 The constants to cite — do not re-type the numbers

| Constant | Value | Where |
|---|---|---|
| `MAX_NAME_LENGTH` | 150 | `ExamCreateRequest`, aliased on `ExamVersionSave` |
| `MIN_DURATION_MINUTES` | 1 | same pair |
| `MAX_DURATION_MINUTES` | **480** | same pair — **ruling 3 changed this from the draft's 600** |
| `MAX_TEXT_LENGTH` | 4000 | same pair |
| `POINTS_TOTAL` | 100 | same pair |
| `MIN_POINTS` / `MAX_POINTS` | 1 / 100 | `QuestionPin` |

Both write requests carry all five names. On `ExamVersionSave` they are **aliases** of
`ExamCreateRequest`'s, not second literals, so `ExamValidator` can cite whichever record it is
validating and there is still exactly one number behind each rule. Cite the constant, never the
literal: `ExamBuildMessages` should interpolate it too, so the sentence a teacher reads and the
rule that refused her can never disagree.

### 4.2 The tolerance boundary — what the constructors deliberately do **not** check

This is the part that matters. **Every inbound compact constructor in this package normalises and
never throws**, because a throw runs on the socket read thread during deserialization and kills the
connection (E1.11) instead of answering a sentence. So the following all arrive at your handler
intact and are yours to refuse with a named `VALIDATION`:

1. **A `null` element inside `questions` or `quotas`.** The copies are
   `Collections.unmodifiableList(new ArrayList<>(…))` and **not** `List.copyOf`, precisely so a
   null survives. Your validator must null-check each element and name **its position in the
   list**, per contract §5.2 — `VALIDATION`, not `NOT_FOUND`, because she is describing a
   composition and the thing not found is a field of her request.
2. **Points that do not sum to 100**, in either direction. No constructor sums anything. The
   sentence must name the shortfall in both directions and by how much (T-3.2 watches the indicator
   go from wrong to right).
3. **A points value outside 1..100**, and **an empty question list**.
4. **A duplicate question, including through two different versions of it** (T-3.9). Not checked
   anywhere in this package. Resolve to the owning `question_id` server-side and refuse naming the
   question.
5. **Two `TopicQuota`s naming one topic** (§5.3, added at type-landing). Compare on the
   **normalised** topic — the record folds blank to `null`, so `""` and `null` are one bucket.
6. **A negative quota bucket, or a request whose total is zero.** `totalRequested()` is derived and
   skips nulls; it will happily return 0 and it is not a check.
7. **A blank or over-long `name`.** `name` is stripped but **not** folded to null, so a client that
   sent `"   "` arrives as `""` and one that sent nothing arrives as `null` — check both.
8. **Anything about `courseCode`.** It is stripped and nothing else. A `null` course code arrives
   as `null`.

Conversely, three things you can rely on having already happened: `studentText` and `teacherText`
are stripped and blank-folded to `null` (so `hasStudentText()` is the whole test); `TopicQuota.topic`
is blank-folded to `null` (so `isCourseWide()` is the whole test); and every list component is
non-null and immutable even when the client sent `null`.

### 4.3 `strip()` reaches less far than it looks, and that is pinned

Imported verbatim from the bank contract **including its measured limit**: `String.strip()` removes
what `Character.isWhitespace()` accepts, and the non-breaking spaces U+00A0, U+2007 and U+202F are
exactly the ones that predicate rejects. A course code padded with U+00A0 arrives at your guard
unchanged and is refused, because it equals no member of the reachable set. **That fails closed**,
which is the safe direction, and it is pinned by `AuthoringDtoTest.nonBreakingSpacesSurviveStrip`
on the same footing `BankBrowseServiceTest` pins it on the read side. Do not "fix" it locally:
widening to a full Unicode-space fold changes what a course code is allowed to be and is a lead
decision.

### 4.4 The one outbound record that throws a business rule

`AutoComposeResult`'s compact constructor enforces the contract's invariant: **exactly one of
`questions` / `shortfalls` is non-empty, and `feasible` says which.** Both empty, both populated,
or a `feasible` flag that disagrees, all throw `IllegalArgumentException` — on construction *and*
on deserialization, since a record is read back through its canonical constructor.

Use the two factories, `AutoComposeResult.composed(…)` and `AutoComposeResult.infeasible(…)`; they
cannot be got wrong. `AutoComposer` must therefore never answer "nothing to propose and nothing to
explain": if a request is unsatisfiable, produce a `Shortfall` for it. That is F3.3 and it is the
defense moment.

### 4.5 Where the infeasibility sentence lives

**Nowhere on the wire** (ruling 4). `Shortfall` is four structured fields; `ExamCopy` composes the
sentence once, client-side. The PRD's example —
`Topic 'Algebra': requested 5 Hard, bank has 2` — is an acceptance artefact of F3.3 and needs a
**copy test** pinning it; that test lands with your screen. The four shapes it has to cover are
contract §7.1 and are already pinned as *data* by `AutoComposeResultTest.Report`, so you are
pinning the wording and nothing else.

### 4.6 Other rulings that bind your handlers

- **Ruling 2 — author-only, confirmed.** A co-teacher cannot open, edit or submit another teacher's
  exam. Five verbs check authorship against the **stored** row and answer `NOT_FOUND`, never
  `FORBIDDEN`; two check `requireTeachesCourse` and **throw**. Never compose the two guards.
- **Ruling 5 — no coordinator path on `EXAM_VERSION_GET`.** `EXAM_PREVIEW_GET` is her complete read.
- `ExamComposition` and `ExamVersionRow` require `rejectedReason` to be **`""` and never `null`**
  when the version is not REJECTED. `requireNonNull` will catch you at build time if it is null.
- `EXAM_SUBMIT` calls `ApprovalService.versionSubmitted(examVersionId)` and emits **nothing** of
  its own (§5.5). E7 owns the transition; E8 owns everything the queue sees.
- Every writing verb answers `ExamComposition` **re-read from the database**, never assembled from
  the request.

---

## 5. Open, and deliberately

- **The freeze.** On your handlers PR. If a shape is wrong, say so now.
- **`MY_APPROVALS_GET`'s removal.** Yours, in the same PR as E7.10's screen.
- **The `ExamCopy` copy test** pinning F3.3's sentence (ruling 4).
