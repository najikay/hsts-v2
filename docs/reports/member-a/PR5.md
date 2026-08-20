# E2 PR 3b — seed sections 9 and 10, and the machinery's second catch

**Closes E2.15.** E2 is 17 of 17 bar E2.16, which is the walkthrough with you.

## Verification

| | |
|---|---|
| Build | `./mvnw clean verify`, JDK 21, `HSTS_REQUIRE_MYSQL=true` → **BUILD SUCCESS** |
| Tests | **2818**, 0 failures, 0 errors, **0 skipped** |
| Coverage, bundle | **98.37%** against `main`'s **98.50%**. Down **0.13**, explained in §7 |
| Coverage, `server.db.seed` | 97.57% |
| Both engines | every seed test runs on H2 and real MySQL |
| Full reseed | **375 rows**, one transaction, every constraint satisfied |

`main`'s 98.50% is measured from this run rather than quoted: no production code outside
`server/db/seed` changed, so the bundle with that package excluded is exactly what `main` scores
under the same suite.

## 1. The catch

`SeedDocument` derives each question version's expected author from §7's D9 rule and §4's teacher
order. On its first run against merged `main` it failed:

```
21003 v2: §7's rule names tamar.shani
expected: "tamar.shani"
 but was: "avi.mizrahi"
```

That is genuine drift, not a planted attack. PR 3a attributed every course-21 version to the
first-listed teacher because the document had no rule at the time; the 2026-08-20 amendment added
one, and nothing but this check could see the disagreement. The fix and the check are in the same
commit, which is the sequencing you asked for.

**The fix reads §4's order from `FacultySection` rather than keeping its own copy**, and
`COURSE_AUTHOR` — the hardcoded per-course list PR 3a used — is deleted. §7 states authorship as
a rule precisely so it re-resolves when the roster changes; a second copy of the order would
drift the next time a course gains or loses a co-teacher, and the drift would be invisible
because every version would still have *an* author.

A second test pins that the co-teacher clause resolves to **exactly one row**. If it ever finds
none, D9's second clause has stopped being demonstrable and T-2.2's "a version history shows two
names" has quietly reverted to one.

## 2. What is in it

**§9** — four executions with windows resolved from the load anchor; sixteen attempts; 108 saved
answers; sixteen grades in two states.

**§10** — four bots, eight sources, eight sessions and their dual-written messages.

**`SeedMain`** — E2.15's "one command". `--reseed` empties and reloads, `--yes` waives the
confirmation for a scripted rebuild. The E19.6 console button calls the same
`SeedLoader.standard(factory).load(RESEED, confirmation)` seam, unchanged as shipped.

**`SeedDocument` + `SeedLoadedDbTest` extended to cover all of it**, so every one of the 375
hand-transcribed rows is compared against the document rather than against a second copy of my
own belief. That includes the eight bot source bodies, roughly 3,400 characters of Hebrew and
English prose, which is where a silent slip was most likely to live.

**`DEMO_ACCOUNTS.md`** — the B-1 line, per your revert. It names the failure *symptom*, which is
the part PR 3a's rewrite left out: without the seed these logins fail with F1.1's generic
message, indistinguishable from a wrong password by design, so nothing on screen can tell you the
seed is missing.

## 3. Decisions taken

| # | Decision | Why | Reversal |
|---|---|---|---|
| D10 | **All eight bot sources load as `TEXT`** | §10's preamble rules it; five of its own labels say PDF or DOCX. Both are legal enum values, so a mechanical transcription stores five wrong types and nothing fails. See §5.3 | one constant |
| D11 | **`bot_sources.added_by` is the bot's course teacher** | NOT NULL, unstated. Follows D9's precedent rather than inventing a convention | one method |
| D12 | **Source and session timestamps are derived**, from §10.2's own `asked` column and the load anchor | Derivation, not invention. Nothing depends on the exact values, only the ordering | none |
| D13 | **Attempts are finalised through the same status-guarded update E10 issues**, not native inserts | `ExamAttempt` has no mutators because §5 makes finalisation a compare-and-set. Inserting the final state directly would have been shorter and would have meant the seed never exercised the path the product uses | none |
| D14 | **`omer.katz`'s start time is derived backwards from the window close** | §9's rule says `ended_at` equals the close for TIMED_OUT attempts. That makes his 75 minutes the full allotted duration rather than a number someone chose, and the independent audit reached the same 09:45 | none |
| D15 | **Transcripts are written in `JpaBotStore.appendExchange`'s shape** | §10 does not specify the JSON, but E16 does, and E16's queries are what read these rows. A seed whose transcripts differed structurally would make the history screen look right on live data and wrong on demo data | none |

## 4. Verification found five things in its own tests

Worth recording because each looked like a data problem and was not:

- **`List.copyOf` rejects nulls**, and §9.2's grades legitimately have no final score. Collapsing
  that to zero would have made eight students look like failures.
- **H2 reads `CAST(x AS CHAR)` as `CHAR(1)`** and truncated every transcript to `"{"`, so the
  test passed on MySQL for entirely the wrong reason. Rewritten to read through the entity, which
  is engine-independent and asserts structure rather than serialisation. This is the failure the
  two-engine convention exists to catch, caught on my own test rather than on the schema.
- **§9 no longer leads with the executions table** — the NOT NULL rules block was added above it,
  so "the first table in §9" became the wrong one. Picked by header shape now, the same way §8.2
  and §9.1.1 already were.
- **Sessions were matched on student and provider, never on which bot they belonged to**, so
  `BotSessionRow.bot()` was parsed and then unused and a session attached to the wrong bot would
  have passed. Every session happened to be right; nothing in the suite knew that.
- **§9's four window offsets were asserted by nothing.** Deliberate at first, since the instants
  resolve from the load anchor, but their *shapes* are still the document's decisions. Now
  pinned: three durations, plus that the live window straddles the anchor rather than merely
  being two hours long.

The last two were found by a cold read **after** the transcription was finished and the
mechanical checker was already passing, which is the point of §4 in the PR 4 report made
concrete: a checker I wrote compares my loader against my parser, and an error consistent across
the two agrees with itself.

## 5. Findings for you and Amjad

Nothing here blocks the merge.

### 5.1 §11 states a loader behaviour that is impossible

§11 says "`seed_id` is the stable identifier (the D8 ruling): the loader keys idempotency on it,
so a second load updates rather than duplicating". **`notifications` has no `seed_id` column** and
there is no V8. The shipped loader keys on recipient + type + title, which is what PR 3a ruled and
what PR 3a also flagged as wrong the moment the seed grows repeats.

So the document and the merged code disagree. Either a migration adds the column, or §11 is
reworded to describe the key that exists. I have not guessed: `SeedDocument` exposes `seedId` and
uses it only to name which notification failed, which is a real improvement to the error message
and no more.

### 5.2 §8.2's "rejected by" has nowhere to be stored

`exam_versions` carries `rejected_reason` and **no** `rejected_by`, `approved_by` or
`approved_at`. So of §8.2's rejection table only the reason is transcribable; `rina.barak` and
`michal.sharon` are dropped at load. If T-4.2 needs to show who returned an exam, that is a
migration rather than a loader detail. My test asserts only what the schema can hold, so nothing
is falsely passing.

It also means exam 6's self-approval — which you have ruled intentional under F4.3 — is invisible
to every schema-level check, since there is no `approved_by` to compare against the coordinator.

### 5.3 §10.1's labels contradict §10's own ruling

The preamble rules all eight sources seed as `TEXT`; the per-source list labels five of them
**PDF** or **DOCX**. A test now pins the contradiction so it is a stated fact rather than
something the next reader rediscovers, and the loader follows the ruling.

### 5.4 §10.1 says the loader stores more than the document shows, and it does not

§10.1 opens with "Text is abridged here for readability; the loader stores the full paragraph."
**The loader stores the block quote verbatim**, because that is the only text in the repository:
nothing carries longer versions. So either the sentence is stale from an earlier draft, or eight
fuller paragraphs exist somewhere that never reached the code.

**Amjad's to answer, and it is cheap either way.** If the sentence is stale, delete it. If fuller
paragraphs exist, they belong in §10.1 and the loader picks them up with no code change, because
`SeedDocument` reads whatever the block quote holds. Nothing here blocks: the seeded sources are
real, substantial text and the bot has genuine material to answer from.

It does matter for the manual pass, though. The first full run exercises the bot **with live
keys**, and its answers are built from these paragraphs. If the demo was designed around longer
source material, the bot is currently answering from less than intended, and that would show up
as thin answers on stage rather than as anything a test could report.

Worth noting the shape of it, because it is the case this PR's machinery cannot catch:
`SeedDocument` reads the same block quote, the loader stores it, and `SeedLoadedDbTest` asserts
they match. All three agree, and the document's own sentence is the only dissenting voice. It
took a cold read to notice — which is the argument for still running one even after building a
mechanical checker.

### 5.5 Two arithmetic slips in §9.2's prose — Amjad's

The data is correct: I recomputed all sixteen auto-scores from the selections against both keys
and every one reproduces. Two of the sentences describing it do not:

| §9.2 says | Actual |
|---|---|
| "21010 is the most-missed, **six** of eight got it wrong" | **5** of 8 |
| "21011 ... **five** students picked something else" | **3** of 8 |

21010 *is* the most-missed and "21001 nobody missed" is right; the counts are overstated. These
would reach the E12.6 review narrative or a demo script.

### 5.6 §10.2's aggregate example counts across all bots

"8 questions this month, most-asked topic: Collections" is the all-bots total; per bot it is
3, 1, 4 and 0. A teacher sees their own course's bot (S-30), so no teacher's screen can show 8.

## 6. What §9 and §10 now prove that nothing did before

The pin, already asserted, now sits alongside: the **absent-versus-wrong** distinction, where
`omer.katz`'s four unreached questions have **no rows** rather than null ones — a loader writing
nulls would satisfy every count and destroy H12.4's fixture; the **override keeping `auto_score`
at 45** while `final_score` reads 55, which is the single row moving execution 1's pass rate from
6/8 to the frozen 7/8; the **inactive bot having zero sessions**, which is S-31's second half; and
**every transcript agreeing with its normalised `bot_messages` row**, which is what dual-writing
means.

## 7. Coverage

Down 0.12 on the bundle. `SeedMain` is the bulk of it at 49.5%: its `main` migrates a real
database and calls `System.exit`, so it is not reachable from a unit test. What *is* tested is
everything deciding whether the database gets emptied — argument parsing, and the confirmation,
including that **end of stream is a refusal rather than a yes**. That last one is the only code
between a stray `--reseed` in the wrong terminal and an empty database.

The rest is fail-fast guards that never fire on correct data. I would rather ship those uncovered
than contort the sections to reach them, which is the ruling you already gave.

## 8. Definition of Done

- [x] Matches ARCHITECTURE §5 and the PRD ids named in the task
- [x] Unit + repo tests on both engines; coverage stated, not rounded
- [x] Migrations unchanged by this PR
- [x] No secrets; `demo123` is a documented demo credential hashed at load
- [x] `docs/TODO.md` — **E2.15 ticked**; E2.16 left alone, it is yours
- [x] CI green — run 32426720866, 3m5s

## 9. Next

**E2.16**, the walkthrough, after this merges — doubling as the first E22.4 cross-walkthrough.

**Amjad's `SeedArithmeticTest`** ports onto `SeedDocument` now that §9.1.1 and §9.2.1 both parse,
including the cross-section consistency you added to his scope. Note that notification 8's mean
and §9.1's frozen mean now agree at 72.5, so that particular check should pass on its first run.

**The rule-5 post-merge pass** remains mine and separate: roughly +960 lines across seven
repositories and nine projections, from five commits by two people, including E16's four new bot
projections. `BotBankQuestion` is the one I want to look at first, since a bot serving bank
questions is the shape that could carry an answer key.
