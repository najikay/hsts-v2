# The `ord` fix — the answer key is numbered by the paper, not by counting

**Ten lines and a fixture.** Yours by your ruling of 2026-08-21: *"you write the fix and the
gapped-ord contract case across both files, I review."*

Its own PR rather than folded into E6's handlers, because it is an E8 defect and not E6 work, and
a real fix to merged code should not wait behind a large review.

## 1. What was wrong

`JpaApprovalStore.answerKeyOf` numbered the coordinator's answer key with a 1..N counter. The paper
beside it renders the **stored** `evq.ordinal`. `V3` constrains that column as `UNIQUE` and
`>= 1` and nothing more — **not contiguous, not required to start at 1** — so the two disagree the
moment an exam has a gap. She reads "Q3 · option 2" against a paper whose Q3 is a different
question, and approves having checked the wrong answers, on the one screen whose purpose is
checking that the answers are right.

**Latent on `main` today.** Nothing produces a gap: the seed writes a contiguous counter and no
merged code removes a question from a version. The two numbers agree by accident.

**E7's builder ends the accident**, which is why this was worth doing now rather than when it
breaks.

## 2. The fix, and the design the guard forced

`QuestionRepository.findPinnedPositions` returns question-version id to stored position, and the
store pairs on it.

**The obvious implementation is illegal, and correctly so.** A projection carrying the position
beside the key trips `CorrectnessLeakGuardTest`'s rule that **no projection in that package may
hold an answer key** — absolute, no allow-list.

**Worth recording what I rejected**, because it is the more interesting half. A record holding
`(QuestionVersion version, int ordinal)` would have **passed** that guard: it inspects component
names, and neither "version" nor "ordinal" reads like a key. It would have carried the answer key
transitively through the entity — the letter of the guard with none of its point, and me walking
around a guard I wrote. Rule 14 says the honest route should be the cheaper one, and it was: the
key stays on the entity route where the `ForAuthoring` convention puts it, and the position comes
back separately and keyless.

A `Map` rather than a second list because the caller pairs by id, and ordering a second list
correctly is the same bug again wearing a different hat.

**The pairing throws rather than falling back.** If the two reads ever disagree, a silent fallback
to a counter would restore the original defect under another name.

## 3. The test, watched failing

`keyIsNumberedByStoredPositionNotByCounting`, in `JpaApprovalStoreContract` so it runs on both
engines. The fixture pins questions at **2, 5 and 9** — deliberately non-contiguous, deliberately
not starting at 1, because that is what E7's builder will emit the first time a teacher removes a
question from a draft.

**Planted the original counter back before trusting it. The new test failed and the other twelve
stayed green**, which is exactly the finding: every existing fixture was tidy enough to hide this.

**I also corrected the comment on the existing assertion.** It read *"numbered by exam position,
because the read is already in exam order"*. That sentence is the defect's justification sitting
inside a test — order is not position — and leaving it would have re-taught the mistake to the
next reader.

## 4. Scope

Two files of yours, opened in my guard for this fix only and annotated as such:
`JpaApprovalStore` and `JpaApprovalStoreContract`. **The rest of `server/features/approval/**` is
still yours and still needs asking.** A path rule cannot express "one fix", so that limit is mine
to hold rather than the hook's to enforce.

`ApprovalRepositoryContract` was the alternative home for the test and is in my own package, but
the defect was in the store's **pairing** — a repository test would not catch a store that ignored
the map.

## 5. Verification

| | |
|---|---|
| Build | `./mvnw clean verify`, JDK 21, `HSTS_REQUIRE_MYSQL=true` → **BUILD SUCCESS** |
| Tests | **3967**, 0 failures, 0 errors, **0 skipped** |
| Coverage gate | met; bundle **98.33%** |
| Both engines | 20 MySQL leaves with real timings; the new case runs on both |
| Staleness | nothing under `src` newer than the log |

## 6. Definition of Done

- [x] Cold auditor run — this fix **came from** one, the rule-5 pass on `ecc0a72`
- [x] Matches F4.1 and E8.4's purpose; `V3`'s constraint quoted rather than assumed
- [x] Tests on both engines; the original defect planted and watched failing
- [x] Coverage not lower than `main`
- [x] Migrations unchanged
- [x] No secrets; scope limited to two named files of yours, annotated
- [x] `docs/TODO.md` — no box ticked; this is a defect fix, not a task
- [x] CI green — run 32507219135, conclusion success

## 7. Note

The `findSubjectOf` dead-consumer finding from the rule-5 pass **stays open**. I had said
`requireBankRead` would become its first caller and close it. Under your ruling it will not: the
union comes from `findTaughtCourseCodes` and `findCoordinatedCourseCodes`, so `findSubjectOf`
remains uncalled with a javadoc naming a consumer that does not exist. Correcting my own earlier
claim rather than leaving it to be discovered.
