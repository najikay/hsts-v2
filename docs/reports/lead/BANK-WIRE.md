# E6 question bank wire contract · artifacts only

**Owner:** [L] Naji · **Status:** complete, `./mvnw -B clean verify` green
**Build:** 3874 tests, 0 failures, 0 errors, 0 skipped · JaCoCo BUNDLE **98.33%** instruction (gate 90%), 91.54% branch · every new `common/dto/bank` type at **100%** instruction
**MySQL:** required for this run (`HSTS_REQUIRE_MYSQL=true`), isolated schema `hsts_bankwire`

DTOs, verbs, a leak guard and their tests. **No services, no handlers, no client screens** — E6 is
Member A's epic and nothing in it is ticked here. The point of landing the contract on its own is
that both sides now compile against one shape that is already tested.

---

## 1. What shipped

### `common/dto/bank` — sixteen new wire types

Everything `docs/contracts/BANK_WIRE_CONTRACT.md` §4 names, with the component names, order and
types exactly as the draft spells them (a record deserializes through its canonical constructor, so
a rename here is a protocol break between two shipped JARs rather than a refactor):

| Type | Direction | Note |
|---|---|---|
| `BankListRequest` | in | every filter nullable; blank folds to null; `MIN/MAX/DEFAULT_PAGE_SIZE` |
| `BankPage` | out | `pageSize` (server's page) vs `rowCount()` (rows in hand) |
| `BankQuestionRow` | out | keyless, answerless, byteless; `text` is the **truncated** stem |
| `QuestionRequest` | in | display id alone |
| `QuestionDetail` | out | **carries the key**; `versionNo` + `latestVersionNo`, `isLatest()` |
| `QuestionDraft` | in | no display id, no author id; nullable image |
| `QuestionEdit` | in | `baseVersionNo` is the only concurrency token; `imageAction` |
| `ImageAction` | — | `KEEP / REPLACE / REMOVE` |
| `QuestionDeleteRequest` | in | same token as an edit |
| `DeleteOutcome` / `BlockingExam` | out | refusal is an OK, exams named by display id **and** name |
| `VersionHistory` / `QuestionVersionDetail` | out | newest first, current version included; **carries the key** |
| `QuestionImageRequest` / `QuestionImage` | in / out | addressed by version; `contentType` sniffed |
| `Difficulty` | — | wire enum, mapped to `server.db.entities.Difficulty` at the boundary |

Conventions follow `common/dto/grading` and `common/dto/exam` and the `package-info.java` records
where the draft left a shape open and which analogous choice was taken:

- **Range and blank validation is deliberately absent.** A `correctAnswer` of 7 or a 5000-character
  stem is a `VALIDATION` answer naming the field, from `QuestionValidator` in the handler, not an
  `IllegalArgumentException` thrown inside a deserialization on an OCSF read thread.
- **Null checks follow the direction of travel.** Outbound records the server builds null-check what
  they cannot be meaningful without (a null there is a server bug and should surface as one);
  inbound payloads normalise instead of throwing, because a throw on the read thread is a dropped
  connection rather than a sentence the teacher can act on. A missing `ImageAction` becomes `KEEP`,
  the instruction that changes nothing.
- Every list is `List.copyOf` after folding null to empty; every `byte[]` is cloned in and out, and
  the three records holding one override `equals`/`hashCode`/`toString` so they compare by value and
  never print a megabyte of picture into a log line.

### `common/protocol/Verb.java` — a new "Question bank (E6)" section

Seven verbs, inserted in feature order directly after the legacy prototype pair they will replace.
The section header carries the group's rules once (three mutating verbs
`requireRole(TEACHER, COORDINATOR)`, four read verbs add `PRINCIPAL`; scope per role, resolved
server-side; `NOT_FOUND` for everything out of reach and `FORBIDDEN` for the role check alone; no
caller id, no lock field, no pushes), and each verb is javadoc'd with its caller, its request
payload, its response and what it enforces.

### `server.db.repos.BankWireLeakGuardTest` — the third member of the guard family

`CorrectnessLeakGuardTest` keeps the key inside the database on student-facing reads.
`ExamWireLeakGuardTest` keeps it off the types a student receives. Both enforce an absolute.
**This package cannot make that claim** — an editor that cannot show which answer is right cannot be
used to author — so the guard is an allow-list rather than a prohibition, and it is stricter for it:

- `INBOUND_LICENCE` = `QuestionDraft`, `QuestionEdit`. Licensed for the uninteresting reason: the
  teacher is submitting a key the server already holds.
- `OUTBOUND_LICENCE` = `QuestionDetail`, `QuestionVersionDetail`. The actual licence, with the gate
  that justifies it written into the field javadoc, and the same "if the E6 authorization tests go
  away, this comes off the list" clause `CorrectnessLeakGuardTest` uses for its suffixes.
- The two lists are asserted **separately**, so a future outbound DTO cannot be waved onto a flat
  list by pointing at the inbound pair.
- `theAllowListIsMinimal` fails if a licensed type stops carrying a key, so no stale permission
  survives to hide the next entry.
- `theCheckHasTeeth` is the positive control: `QuestionDetail` really does trip the predicate and
  `BankQuestionRow` really does not. Without it every other assertion would also pass if
  `CorrectnessNames` stopped recognising anything at all.
- Two shape assertions beyond the scan: the list row's components are pinned exactly, and **no bank
  DTO may carry a lock field** (E18.8 owns that; a duplicate would let a stale badge disagree with a
  real lock).

One thing the guard taught us while being written: a naive `contains("lock")` red-lines
`DeleteOutcome.blockingExams`, and the tempting fix is to weaken the check. It is camel-case aware
instead, with the reason in a comment — the same failure mode the contract's red team flagged about
widening `CorrectnessNames` to accommodate `QuestionDraft`.

---

## 2. The five rulings, and where each one landed

| # | Ruling | Where it is now enforced or recorded |
|---|---|---|
| 1 | **Verb naming is noun-first: `QUESTION_IMAGE_GET`** | The verb, its javadoc naming note, `VerbTest.bankVerbNamingIsTheRuledOne` (which also asserts `GET_QUESTION_IMAGE` is absent, so a handler author reading the old TODO cannot reintroduce it), and TODO E6.6 reworded in place with a dated parenthetical |
| 2 | **The principal sees the answer key** | One `QuestionDetail` type, staff-only, principal in the four read verbs and in none of the three mutating ones. Stated in the record javadoc, the package javadoc and the guard's outbound licence |
| 3 | **Coordinator scope is subject-based** | A server concern, so it is reflected in the `Question bank (E6)` verb-section javadoc, naming `rina.barak` and why deriving coordinator-ness from `course_teachers` shows a starred demo account an empty bank |
| 4 | **The legacy pair stays for now** | `LEGACY_NOT_COVERED` in the guard, with the entry text *"LEGACY - retirement PR scheduled after E6 merges (retires LegacyQuestionHandlers, QuestionDAO, the legacy screen, the E18.4 guarded-update flow)"*, dated 2026-08-21. `theLegacyExclusionIsNamedAndStillReal` proves the exclusion still describes something real, proves `Question.answer` is a key the shared predicate deliberately does not match, and **fails the day the pair is retired** so the entry cannot outlive its subject. The package javadoc says the same thing in prose |
| 5 | **List stems are server-truncated** | `BankQuestionRow.text` documents itself as truncated and points at `QuestionDetail.text` for the full stem, which documents itself as never truncated. The length is one shared constant, `BankQuestionRow.STEM_PREVIEW_CHARS`, so the server that cuts and the client that renders the cut agree on one number rather than two |

---

## 3. What Member A consumes, and where

| Needs | Where |
|---|---|
| The payload types for all seven handlers | `src/main/java/common/dto/bank/` — compiled, round-trip tested, Hebrew included |
| Which verb takes which payload and answers with what | `common/protocol/Verb.java`, section `Question bank (E6)` |
| The role gate and the scope rule per verb | Same section's header comment, then per-verb javadoc |
| The conventions to build against (what to validate, what not to) | `common/dto/bank/package-info.java` |
| The shape rules the build enforces | `src/test/java/server/db/repos/BankWireLeakGuardTest.java` |
| Round-trip and defensive-copy examples to copy from | `src/test/java/common/dto/bank/BankDtoTest.java` |

**Still Member A's, untouched here:** `QuestionService` and all seven handlers, `QuestionValidator`'s
remaining rules, `BankMessages`, the repository reads (including the scoped lookup that filters
`deleted_at`, which `QuestionRepository.findByDisplayId` deliberately does not), the delete-blocking
query and its two-engine test, `Authorization.requireTeachesCourse`, and every screen. **No E6 task
is ticked in `docs/TODO.md` and none was added.**

Two things worth knowing before the handlers start, both from the contract rather than from this
package. The delete block is a service query with **no database backstop** — the three `RESTRICT`
foreign keys prevent a *hard* delete and say nothing about the soft one — so a wrong blocking query
silently removes a referenced question and nothing catches it. And the validator must be at least as
strict as `ck_question_versions_distinct` in every dimension, accent folding included, because
`utf8mb4_unicode_ci` is accent-insensitive and a case-only fold would hand its own backstop a case
to reject rudely.
