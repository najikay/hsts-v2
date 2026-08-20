# E2 PR 3a — seed loader and the sections that could be transcribed

**Closes E2.17. Advances E2.15, does not close it.** E2.16 is unchanged and remains yours.

This PR was planned as PR 3. It is PR 3a because an adversarial pass over the seed content,
run *before* transcribing anything, found that roughly a third of the dataset cannot be loaded
as written. Rather than invent replacement content or stall the whole epic, this ships the
loader plus every section that is fully specified, and PR 3b follows once the content questions
in §6 below are answered.

## Verification

| | |
|---|---|
| Build | `./mvnw clean verify` on JDK 21, `HSTS_REQUIRE_MYSQL=true` → **BUILD SUCCESS** |
| Merged with | `origin/main` at `cb9d14c`, so this includes `#8`, `#9`, `#10` and E10/E11 |
| Tests | **2337**, 0 failures, 0 errors, **0 skipped** |
| Coverage, bundle | **98.76%** against `main`'s **98.81%**. Down **0.05**, explained in §7 |
| Coverage, `server.db.**` | **98.05%** |
| Coverage, `server.db.seed` | 98.34% |

`main`'s 98.81% is measured, not quoted from an earlier run: this PR adds no production code
outside `server/db/seed` (`git diff --name-only origin/main...HEAD -- src/main/java` returns
nothing else), so the bundle with that one package excluded is exactly what `main` scores under
the same suite. That avoids comparing against a baseline three merges stale.
| Both engines | every seed test runs on H2 **and** real MySQL; MySQL leaves show real timings |
| Migrations | unchanged by this PR |
| Staleness | `find src -name '*.java' -newer <log>` empty, so the numbers describe what is committed |

## 1. What is in it

**`server/db/seed/`, the mechanism (8 types)**

| Type | Role |
|---|---|
| `WipeOrder` | **The** canonical reverse-dependency order. Both consumers named in its javadoc |
| `SeedTimes` | One injected `Clock`, one anchor, resolving `T-14d 09:00` and friends |
| `SeedLoader` | Both modes, one transaction, the confirmation gate, the summary |
| `SeedMode` / `SeedOutcome` / `SeedSummary` | Load-if-missing vs reseed, and the per-table text the E19.6 button shows |
| `Confirmation` | The seam the CLI and the console button each implement |
| `SeedSection` / `SeedContext` / `SeedDataset` | Where content plugs in, and the dependency order |
| `SeedLookup` | Natural-key resolution, the one place the no-database-ids rule is implemented |

**`server/db/seed/`, the content (7 sections)** — seed §1-§8 and §11: 2 subjects, 4 courses,
18 users with per-user BCrypt, 5 teaching assignments, 2 coordinators, 29 enrollments, 40
questions in 43 versions, 6 exams in 7 versions with 39 composition rows, 8 notifications.

**Tests** — `SeedLoaderContract` + 2 leaves (14 each), `SeedDatasetContract` + 2 leaves (15 H2 /
18 MySQL), `WipeOrderTest`, `SeedTimesTest`, `SeedSummaryTest`, `SeedContextTest`.

**`TestSchema.wipe(factory)` now delegates to `WipeOrder`**, signature unchanged exactly as you
asked, so `JpaNotificationStoreContract` is untouched and still green on both engines. I removed
the `TestSchema.WIPE_ORDER` constant rather than leaving a deprecated alias: nothing referenced
it.

**`docs/DEMO_ACCOUNTS.md`** re-pointed at the seed (E2.17). It carries your roster change and
the pure-vs-dual-hat distinction.

## 2. Why this is split, and what it costs

The seed document is written to be a transcription job. For sections 1-8 and 11 it is. For §9
and §10 it is not, and no assumption I could take would be safe:

- **§9.1's eight auto-scores cannot be produced by the exam they belong to.** Execution 1 runs
  exam 1 v2, which is six questions at 15 points and one at 10. With one correct answer per
  question and no partial credit (C-8 / ADR-016) the only reachable totals are
  `{0,10,15,25,30,40,45,55,60,70,75,85,90,100}`. The seeded values are 92, 78, 85, 64, 71, 51,
  83, 96, of which only **85** is achievable. The frozen `stats` are internally consistent and
  arithmetically correct, and they are computed from those eight numbers, so they move when the
  numbers do.
- **`attempt_answers` is never specified anywhere in the document.** So the 16 seeded attempts
  have no per-question selections, and nothing can recompute a score from them.
- **`bot_sources.raw` is `NOT NULL` with `CHECK(LENGTH(raw) > 0)`** and no bytes are supplied.
  NULL is refused and so is empty, and five of the eight sources are typed PDF or DOCX, so the
  loader cannot honestly synthesise them from the extracted text either.

Choosing eight plausible scores would silently replace Amjad's E12/E14 fixture and invalidate
the statistics that depend on it. That is the case where being wrong is worse than being absent.

**The cost, stated plainly:** E2 stays 16 of 17 rather than 17. The demo database has users,
courses, the full question bank and all six exams, but no executions, grades or bot content
until 3b. Because idempotency is per row, 3b is purely additive: nothing in this PR changes.

## 3. Decisions taken

| # | Decision | Why | Reversal |
|---|---|---|---|
| D1 | Java loader over the JPA entities, not `V8__seed.sql` | Approved by you 2026-08-20; NFR-17 being reworded to match. If the PRD still says "versioned SQL" when you read this, that is your push lagging, not a deviation | high |
| D2 | Idempotency **per row by natural key** | Your call. Also what makes TEAM_SPLIT §3 rule 6 work: a teammate adding their feature's rows and this loader can coexist because neither claims the whole database | low |
| D3 | `--reseed` confirms first, wipe and load share **one transaction** | A section failing mid-reseed must not leave an empty database minutes before a defense | none |
| D4 | Canonical `WipeOrder` in main code, `TestSchema` delegates | Forced: `src/main/java` cannot import `src/test/java` | none |
| D5 | BCrypt cost 10, hashed **per user** | Cost matches `InMemoryUserDirectory.DEV_COST` so sign-in latency is unchanged. Per user because one call reused would put one identical string in eighteen rows | one constant |
| D6 | **Database ids are never honoured from the document** | The document numbers users 1-18, but `ACCEPTANCE_TESTS.md` and `DEMO_ACCOUNTS.md` both identify people by username and exams by display id. Entities are `@GeneratedValue(IDENTITY)` and `DELETE` does not reset `AUTO_INCREMENT`, so the second reseed would renumber the same eighteen people 19-36. Anything pinned to "dana.cohen is id 2" would break at the defense and nowhere earlier | n/a, forced |
| D7 | Markdown formatting stripped; **em dashes replaced** | Backticks and one emphasis pair are formatting, not content. Em dashes violate PRD §4.1 in strings a user reads. Full list in §5 | mechanical |
| D8 | Notifications keyed on recipient + type + title | They have no natural key. **This is a choice, not a constraint** | see §6 |
| D9 | `created_by` / `created_at` inferred for questions and exam versions | Both `NOT NULL`, neither in the document. 29 of 40 questions have exactly one possible author; the 11 Java ones use the first-listed teacher | see §6 |

## 4. Two adversarial passes, and what each was worth

**Before transcription**, against the migrations and the content: found the three blockers in §2
plus four `NOT NULL` columns with no values in the document. Finding those before writing 1500
rows rather than after is the whole argument for running it at that point.

**After transcription**, comparing the Java to the document field by field: confirmed all 40
questions match on stem, all four options **in order**, correct index and illustration flag;
all 18 users on username, Hebrew name, role and national id; all 29 enrollment pairs; all 7
composition lists including points and order. It found two real errors of mine, both prose, both
fixed here, and one observation worth more than either:

> the tests were written to the code, not to the document

That is correct and it is a limit of this PR's test suite worth knowing when reviewing it. My
assertions were written from my own transcription, so an error copied into both would pass. I
had in fact written "twenty-four rows" in a javadoc and `24L` in the matching assertion when the
real number is 29; the suite agreed with me and was wrong. **The cold read is the guard here,
not the tests.**

**Five planted attacks, all caught:** removed a table from the wipe list; made `SeedTimes` read
the clock per call; committed the wipe in its own transaction; unpinned 11005 to drift to latest
(caught by three tests); restored the em dash in question 21008. Each broke exactly the intended
test and nothing else, and each was reverted and re-verified.

## 5. Content I changed, so you can object

Em dashes replaced with a comma or colon, per PRD §4.1, in strings that reach a screen. Meaning
unchanged in every case:

- question **21008** option 4, `Nothing — it is safe` → `Nothing, it is safe`
- three exam names (`מבחן אמצע — אלגברה` → `מבחן אמצע: אלגברה`, and the other two Mathematics exams)
- five teacher notes, two rejection-reason-adjacent, in §8.2
- three notification titles (§11 #1, #5, #8)

One emphasis pair stripped: question **22001**'s stem, `filters rows *before* grouping`.

## 6. Found in the seed document and on `main`, for the owners

1. **`SEED_CONTENT.md:95` and `:257` are stale after your roster commit** (`8b4a0b1`). Line 95
   still says "Calculus and Java each have two" teachers; line 257 still says dana.cohen is
   "one of two on Calculus". Both were true before 2026-08-20. The seeded data is unaffected,
   she was already approver-never-author, so this is stale rationale rather than a broken
   fixture.
2. **Exam 6 is self-approved.** `michal.sharon` authors `202201` and is the sole coordinator of
   its subject, and `pk_coordinators` allows exactly one per subject. Not a DDL violation
   (`exam_versions` has no `approved_by`), but it contradicts §5's stated intent and would make
   exam 6 unloadable if E8 ever adds a self-approval guard.
3. **8 of 9 Databases questions have `correct = 2`**, six of seven in exam 6. Answering "2" to
   everything scores 85/100, which makes the auto-grading demo look broken.
4. **`exam_version_questions.ord` is never stated.** Assigned 1..n from listing order.
5. **Notification 8's title states the mean as 78**, which comes from §9.1's frozen statistics.
   If those scores change, that string changes with them.

## 7. Coverage, and where the 0.07 went

`server.db.**` went **up**, 97.55% → 97.95%. The bundle went down 0.07 because this adds ~2,500
instructions at 98.34% to a bundle at 98.80%.

What is left uncovered in the seed package is fail-fast guards that never fire on correct data:
the points-total check in `ExamsSection`, `originalOf`'s throw, a few `SeedLookup` branches.
Reaching them means either restructuring the sections to take their data as a parameter or
reflection, and I would rather ship them uncovered than contort production code or delete a
guard for a coverage point. Say if you disagree and I will make them reachable.

I also deleted five static accessors I had added and never used, which was worth 0.18 on its
own. Speculative API is the same mistake as a speculative query.

## 8. Definition of Done

- [x] Matches ARCHITECTURE §5 and the PRD ids named in the task
- [x] Unit + repo tests on both engines; coverage stated, not rounded
- [x] Migrations unchanged by this PR
- [x] No secrets; `demo123` is a documented demo credential, hashed at load, and the working
      tree carries no `server.properties`, `.claude/` or `CLAUDE.md`
- [x] `docs/TODO.md` updated: E2.17 ticked, E2.15 annotated with what remains, E2.16 left alone
- [ ] CI green — ticked after the run

## 9. What I need from you

Nothing here blocks the merge.

| | Ask | Where |
|---|---|---|
| 🔴 | **§9's auto-scores and the missing `attempt_answers`** — Amjad's fixture, and PR 3b cannot start without it | §2 |
| 🔴 | **`bot_sources.raw`**: real files under `docs/seed/`, or types 2/4/5/6/8 become TEXT and `raw` is the UTF-8 of `extracted_text` | §2 |
| 🟡 | **D8, the notification key.** Recipient + type + title collapses two genuinely distinct notifications with the same title to the same person. Fine for a fixed eight-row fixture, wrong the moment the seed grows repeats. An explicit seed id in §11 is the alternative | §3 |
| 🟡 | **D9, the inferred question author** for the 11 Java questions | §3 |
| ⚪ | Exam 6 self-approval, and the `correct = 2` cluster | §6.2, §6.3 |
| ⚪ | The two stale prose lines in `SEED_CONTENT.md` | §6.1 |
| ⚪ | Fail-fast guards left uncovered | §7 |

## 10. §7 re-transcribed against the rebalanced answer key

PR #10 (`ce818f7`) rebalanced §7's answer key from **{1:14, 2:18, 3:8, 4:0}** to
**{1:11, 2:10, 3:10, 4:9}** by swapping option text between positions. It merged before this PR
was pushed, which is the order I asked for, so §7 here is transcribed against the rebalanced
document rather than needing a follow-up. **31 of the 40 questions changed.**

Before the rebalance I had counted the key distribution in my own transcription independently
and got {1:14, 2:18, 3:8, 4:0}, matching Amjad's stated "before" exactly. Two people deriving
the same defect separately, from different directions, is the strongest confirmation available
that option 4 was never correct anywhere in the bank.

**How the re-transcription was verified, given §4.** Section 4 says plainly that my tests cannot
catch a transcription error, because they are written from my own constants. Re-typing 40
questions is precisely the operation that weakness applies to, so this time the check was
mechanical rather than visual: a throwaway script extracted **all four options in order plus the
correct index** from both the document and the Java, normalised the two documented differences
(markdown formatting, the one sanctioned em-dash replacement), and diffed them. **Identical for
all 40.** Separately, the document's stems, topics and difficulties are byte-identical before
and after the rebalance, so the earlier field-by-field audit of those still holds.

Worth recording: that script produced **three false positives before it produced a true
negative** — it tripped on a `//` comment of mine containing a quoted string, on backticks
appearing mid-string rather than only at the edges, and on the space preceding an em dash. Each
looked exactly like a transcription error until read closely.

**The durable fix, for PR 3b.** Amjad's validation is now a script that parses §7's key, §8.1's
composition and §9.1.1's selections out of the document and recomputes every score, and you have
asked for one committed parser with two consumers. Agreed, and the throwaway above is the
argument for making it real: a parser that is not itself tested is a confident liar. PR 3b
builds the assertion that **the loaded database matches the document**, on his parser if it is
committable and as shared test infrastructure otherwise. PR 3b has to transcribe §9.1.1's
per-question selections anyway, which is where hand-copying is most dangerous.

## 11. Next

**PR 3b** closes E2.15: seed §9 (executions, attempts, grades) and §10 (bot content), once the
two red items are answered. Additive only.

**The rule-5 post-merge pass** is larger than a skim and I will take it separately: six added
reads from Amjad's two PRs, plus E10/E11's additions to my lane in `0a44ff6` — five new
projections, `AttemptRepository` +316, `ExecutionRepository` +148, `QuestionRepository` +82,
`CourseRepository` +30, and a second guard, `ExamWireLeakGuardTest`.

**E2.16** stays yours, after 3b, doubling as the first E22.4 cross-walkthrough.
