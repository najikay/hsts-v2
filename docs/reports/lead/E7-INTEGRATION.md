# E7.10 integration — the assembly, the retirement, and the freeze

**Lead, 2026-08-25.** The assembly commit #51 (`docs/reports/member-a/PR22.md`) asks for, plus the
four documentation rulings it and PR21 were blocked on. Off `6cb5203`, tree clean and level with
`origin/main` before the first edit.

**What it does, in one line each.** Route id `exams` stops resolving to `MyApprovalsView` and
starts resolving to `ExamListView`; `MY_APPROVALS_GET` and everything that read it are deleted in
the same change; every notification in the app starts delivering its entity id; and
`EXAM_BUILDER_WIRE_CONTRACT.md` becomes FROZEN v1 whole.

**The two `ExamListWiringGuardTest` cases that were red by design on `main` are green**, and they
are green because the artifacts they name are gone, not because the assertions were edited. Neither
test file was touched.

---

## 1. Verification

| | |
|---|---|
| `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_integ ./mvnw -B clean verify`, JDK 21.0.12+8 | **BUILD SUCCESS**, 25:25 min |
| Tests | **6283 run, 0 failures, 0 errors, 0 skipped** |
| The two by-design failures | `ExamListWiringGuardTest.theRouteServesTheExamList` and `.theRetirementReachedEveryArtifact` — **both green.** The class is 7/7, and neither it nor any other file in `client/features/exambuild/**` was edited |
| Coverage gate | `All coverage checks have been met` — and reached its phase for real this time, because nothing was failing before it |
| Coverage | **98.116 %** instruction (75479/76928), **91.09 %** branch (6157/6759) |
| MySQL really ran | `FlywayCleanRunTest` 19 tests / 96.97 s; `ExamBuildRepositoryMySqlTest` 37 tests / 5.975 s; every leaf a real timing |
| `DeepSeekProviderTest` | green first run, no rerun needed |
| Sources newer than the build | none (`find src -name '*.java' -newer target/jacoco.exec` is empty) |
| Files deleted | 3 production classes, 0 test files — the retired tests were nested classes and blocks inside files that keep other work |
| Files modified | 24, plus this report |
| Member A's lane | **untouched.** No file under `client/features/exambuild/**` or `server/features/exambuild/**` is in this change. The pom exclusion is the only line in it that names his package |

**On the test count, and what I will and will not claim about it.** 6283/0/0/0 is summed from the
840 surefire XML reports of this run, which is not the same instrument PR22's 6221 came from (that
was surefire's console aggregate), so **the two numbers are not subtractable and I am not going to
pretend they are.** What is exactly known is the delta this change makes: **+4 tests written**
(three `paramsFor` cases, one deep-link interaction case) and **−14 retired** with the screen they
drove — 8 in `ApprovalSessionTest.Mine`, 3 in `ApprovalServiceTest.Mine`, 2 in
`ApprovalInteractionTest`, 1 in `ApprovalDtoTest`. Every one of the 14 is accounted for by name in
§3 with what replaced it. **Nothing green went missing without a line saying why.**

**On the coverage number, said properly.** 98.116 % is not compared against a measured `main`
baseline — `main` was not built this session — so PR22 §1's arithmetic is still the honest frame:
`ExamListView` is now on the exclusion list, which is what that PR's 0.009 pp figure assumed. The
gate is the claim that matters here, and it passed.

---

## 2. The change-set, item by item

### 2.1 Route swap and assembly *(ask 1)*

- **`client/core/SessionRoutes.java`** — `builderFor` now returns `ExamListView::new` for
  `Routes.EXAMS`; `import client.features.approval.MyApprovalsView` removed and
  `import client.features.exambuild.ExamListView` added. The import removal is load-bearing rather
  than tidiness: `theRouteServesTheExamList` reads this file with comments stripped and asserts it
  `doesNotContain("MyApprovalsView")`, so a swap that left the import would fail the guard.
- The comment above `routes.add(Routes.EXAMS)` in `routesFor` said "E8.6's teacher side", which
  stopped being true with the swap *(ask 5)*. It now says what the screen is — E7.10's exam list,
  drafts included, a chip per version, the actions each state allows — records that **the route id
  is unchanged since E5.4**, and names the retirement: *"E8.6's approval-status half retired into it
  per APPROVAL ruling 1."*
- **`client/core/Routes.java`** — `Routes.EXAMS`'s own javadoc carried the same stale claim ("E8
  ships the approval-status half of this screen only… E7 replaces the screen behind this id when it
  lands"), and contract §8 cites that sentence by name as the thing the swap makes true. Updated to
  past tense, plus one new fact: the notification now arrives carrying `examVersionId` (§2.3).
- **`pom.xml`** — `<exclude>client/features/exambuild/ExamListView*</exclude>` added beside the
  other view exclusions with the one-line comment the neighbours use. `MyApprovalsView*`'s exclusion
  is deleted with the class, and the E8 block's prose no longer lists `MyApprovalsSession` among the
  measured logic classes beside the screens.

PR22 §1's coverage arithmetic depends on that exclusion: the branch measured 0.149 pp below `main`
and the whole of the gap was `ExamListView` at 86 %. With it, 0.009 pp.

### 2.2 The `MY_APPROVALS_GET` retirement *(contract §8 — the same change as the swap)*

Contract §8 requires the retirement to land in the change that lands the screen, so there is never a
window where two overlapping reads of one fact are both live. It did.

**Deleted outright**

| file | |
|---|---|
| `src/main/java/client/features/approval/MyApprovalsView.java` | the screen |
| `src/main/java/client/features/approval/MyApprovalsSession.java` | its session |
| `src/main/java/common/dto/approval/MyApprovals.java` | its payload |

**`ApprovalRow` stays.** It is the shared row type — `ApprovalQueue` carries it, `ExamPreview`
carries it, `ApprovalQueueSession` and `ApprovalCopy` read it — so it is not "MyApprovals' row
type". Only the wrapper retired.

**Modified**

| file | what |
|---|---|
| `server/features/approval/ApprovalService.java` | `MY_APPROVALS_GET` registration and the `mine` handler removed; `registerOn`'s javadoc now says four verbs and why; a dated block comment stands where `mine` was. `queue`/`preview`/`approve`/`reject` and the shared `rows(...)` helper untouched |
| `common/protocol/Verb.java` | `MY_APPROVALS_GET` removed; see §2.2.1 |
| `client/features/approval/ApprovalCopy.java` | the six teacher-side sentences (`MINE_TITLE`, `MINE_SUBTITLE`, `MINE_EMPTY_TITLE`, `MINE_EMPTY_HINT`, `MINE_LOAD_FAILED`, `REJECTED_PANEL_TITLE`) removed — copy for a screen that no longer exists is copy no reader can be shown. `ExamListCopy` already carries its own `REJECTED_PANEL_TITLE` with the identical string |
| `common/dto/authoring/ExamList.java`, `ExamListRow.java`, `ExamVersionRow.java` | javadoc tense: these described the retirement as pending ("retires", "is removed in the same PR"). Now dated and past |
| `client/core/Routes.java`, `SessionRoutes.java`, `pom.xml` | §2.1 |

**Living docs**

- `docs/contracts/APPROVAL_WIRE_CONTRACT.md` — ruling 1 gets the dated execution note the task
  specifies, naming every artifact that went and the precedent for removing the verb. The verb's
  scope bullet and its row in the verb table are struck through with the date; "Open questions for
  the freeze" item 1 is marked **ANSWERED, and executed**.
- `docs/contracts/EXAM_BUILDER_WIRE_CONTRACT.md` §8 — *Executed 2026-08-25* on the same-PR rule,
  and the type-landing note at the foot of the file corrected from "deliberately still live" to
  removed, with the date.
- `docs/TODO.md` — E7.10 and E7.15 ticked (§4); the E8 notes block's item (b) marked done, with the
  deep-link defect named so E8.6's tick is not left claiming something that was false.

Historical PR reports (`docs/reports/member-a/PR8|16|18|22.md`,
`docs/reports/lead/E7-TYPES.md|E8.md|WAVE1.md|WAVE2.md`) mention the verb and are **not** edited:
they are records of what was true when they were written, and rewriting them would destroy the only
audit trail of the decision.

#### 2.2.1 Removing the verb — the precedent, stated

`Verb` carries a never-remove-a-header rule. **It does not apply here, and the reason is worth
writing down rather than asserting.** That rule protects a *client jar meeting a server jar of a
different version*: a verb that vanishes from the enum stops being resolvable by name, so a peer
built against the old protocol gets a deserialization failure instead of a clean refusal. This
product has no such pair — both tiers ship from one build, `Launcher` starts the server in-process,
and there is no deployed client that can be older than its server.

**Precedent: #47** (`2323bbc`, the legacy bank screen retirement) deleted `GET_ALL_QUESTIONS` and
`UPDATE_QUESTION` outright on exactly this reasoning. This follows it. The reasoning is now written
into `Verb.java` itself at the group header, so the next person to ask does not have to find this
report.

`VerbTest.myApprovalsGetIsRetired` (was `myApprovalsGetHasNotRetiredYet`) asserts the far side by
**name**, not by referencing the constant — referencing it would not compile, and a build that does
not compile tells a reader nothing about what is missing. Same reasoning `ExamListWiringGuardTest`
gives for its own classpath check.

### 2.3 The notification deep link *(ask 2 — every notification, not one screen)*

`NotificationsPanel.activate` called `navigator.navigate(ref.route())`, the one-argument overload,
which passes `NavParams.empty()`. `NavRef.entityId()` is set by `NotificationCatalog` on **every**
draft it writes, and it was dropped at the last hop. Every notification in this app opened the right
screen and never said which row — for F4.2 that means a teacher with exams in two courses reads
"your exam was sent back" and lands on whichever exam her list opens by default. Member A's diagnosis
in PR22 §4.7 is exactly right and this matches it.

**The fix, and why it is not literally one line.** There is no single canonical parameter name to
pass the id under. The destination screens have named what they want since E4.2 and they disagree:
`ExamListView` and `ExamPreviewView` read `examVersionId`, `ExecutionMonitorView` reads
`executionId`. Inventing one canonical key would mean editing every destination screen to read it —
including `ExamListView`, which is Member A's file and not mine to touch. So:

- **`NotificationPresenter.paramsFor(NavRef)`** — a pure function mapping the route the server named
  to the key that route's screen asks for. It lives beside the panel's other decisions for the
  reason that class exists: *"Every decision it needs comes from `NotificationPresenter`, which is
  unit-tested."* The table is keyed off `client.core.Routes`' own ids rather than re-typed literals,
  so a route rename breaks the build rather than the deep link.
- **`NotificationsPanel.activate`** now calls the two-argument `navigate`. That is the one line.

**One route deliberately gets nothing, and it is the case that proves the design.**
`NotificationCatalog.botSourceChanged` carries a **bot** id; `BotManagerView.PARAM_COURSE` wants a
course **code**, a `String`. Passing the `Long` under that key would not deep-link — it would throw
`IllegalArgumentException` out of `NavParams.get(String, Class)` on a bell click. It navigates with
nothing, which is the behaviour every notification had before this fix. Making that one work needs
the catalog to carry a course code, which is a wire change and not this one. Written into the
javadoc and pinned by a test so it reads as a decision rather than an oversight.

### 2.4 `EXAM_BUILDER_WIRE_CONTRACT.md` — FROZEN v1, whole

**(a) §7.3, the collation-equality ruling *(PR21 §6.1 / PR22 R1)*.** Member A's annotation is
resolved **into** the text rather than left standing beside it, and he is credited in the paragraph
that replaces it. The ruled text:

> Topic matching is the **collation's** equality — `utf8mb4_unicode_ci`, as measured in #48 rather
> than assumed — reproduced service-side by `QuestionValidator.sameTopic`. **Never** Java
> `String.equals`; **never** a fold stricter or looser than the column.

His reading was right and the old phrase was loose rather than wrong: ruling 7.6 chose option A over
a *normalising filter*, and the filter it was ruling on runs in SQL, so "exact" always meant the
column's exactness. Both failure directions are spelled out, because §7.2 property 2 needs agreement
in both: over-folding shrinks `available` below the count she can see on the bank screen,
under-folding splits one pool the database serves from the same rows (P-9).

**(b) §8, the read path *(ask 3, option (b))*.** The retirement removes `MyApprovalsView`'s row
click into `ExamPreviewView`, so a **coordinator author** can read a rejection reason and cannot
open the exam it names. For a plain teacher nothing regressed — `Routes.EXAM_PREVIEW` was
registered for coordinators only, so that navigation already threw client-side. §8 now says the
read path moves to E7.11's version-open (PR23), dated.

Widening `EXAM_PREVIEW` to `TEACHER` is refused. **The reason I was given for refusing it does not
survive checking, so the contract carries a different one — see §5.2.** The ruling itself is
unchanged; only its stated justification is.

The gap is one role (coordinator authors), one read path, at most one PR wide, and it is written
down so it is a known gap rather than a discovered one — PR22 found it in the T-4 walkthrough with
the defense audience watching.

**(c) §5.4-A1, one open draft per exam *(ask 4)*.** A dated amendment in the EXAM A1-A7 style:
`EXAM_VERSION_REVISE` against any version, while that exam already has an open `DRAFT`, answers
`CONFLICT` **naming the existing draft**. §5.4's second bullet checked the *addressed* version only,
so revising v1 while v3 was a draft inserted a second one — reachable from this very screen, which
offers Revise on every non-draft version. Member A found it by reading `ExamService.revise`'s own
comment against its code and corrected the **comment**, which was the right call: the code matched
the contract and the contract was what was wrong.

It is marked **"amendment ruled 2026-08-25, service change in flight"** with a warning block, and
the block says PR23 removes it. E7.11 requires the rule — it is the PR whose builder has to open one
draft — so the implementation lands there, and the contract is never ahead of reality without saying
so in the same paragraph.

**(d) The header.** §7's freeze condition was the same one every other section met: code exercising
it. #50 landed `AutoComposer`, registered `EXAM_AUTO_COMPOSE` and moved
`ExamHandlersTest.Registration` to the seven-verb set. So the status line is now *"FROZEN v1, whole.
Sections 1-6 and 8 froze on #46 (2026-08-25); section 7 froze 2026-08-25 with the collation-equality
clarification."* §7 freezes with **one text change and no behaviour change**, and the §5.4-A1
exception is named in the header so nobody has to find it.

### 2.5 `TopicQuota.topic` and BANK ruling 7.6

- **`common/dto/authoring/TopicQuota.java`** — the `@param topic` javadoc said "exact equality" and
  now says the collation's, with the same never-Java-`equals` / never-stricter-nor-looser pair and a
  pointer to §7.3. It records that it said the old thing until 2026-08-25 and why that was loose
  rather than wrong.
- **`docs/contracts/BANK_WIRE_CONTRACT.md` ruling 7.6** — one dated cross-reference sentence
  appended: the "exact equality" this ruling chose is the collation's, because the filter it was
  ruling on runs in SQL; see EXAM_BUILDER §7.3 and #48's measurements.

Four places carried the loose phrase (§7.3, `TopicQuota`, ruling 7.6, `AutoCandidate`). Three are
corrected here; the fourth is in Member A's lane and PR21 already corrected it there.

### 2.6 `Verb.EXAM_AUTO_COMPOSE` — the list stops enumerating *(PR21 §6.3 / #51 R3)*

It named three `VALIDATION` causes and there were five. The fix is not to name five — a list that
enumerates will fall out of date again the next time a rule lands. It now points at contract §5.3,
§7.3 and §7.3a as the place the rules are maintained, and states the **property** a caller can rely
on: *every refusal names the rule it broke*, in a sentence `ExamBuildMessages` owns. Two causes are
given **explicitly as examples and not as a catalogue** — §7.3a's shape rule (and that its refusal
names both legal shapes) and §5.1's points ceiling — with "There are others, and there will be more."

### 2.7 `QuestionValidator.sameTopic` — the two-consumer invariant *(PR21 §6.2 / #51 R2)*

#50's correction is merged and verified present: the javadoc no longer claims E7's auto-composer
selects with `qv.topic = :topic`, and it already carried the both-directions argument. **Added**, as
its own headed section, is the invariant stated as an invariant:

- **duplicate detection (`sameAnswer`) tolerates over-folding** — its promise is one-directional and
  the worst case is a teacher told two similar answers are too similar, recoverable in the editor
  she is already in;
- **availability (`sameTopic`, E7.4) requires agreement in BOTH directions** — over-folding inflates
  a bucket, under-folding deflates it, and either way `available` stops being the number she can
  reproduce on the bank screen, which is exactly what §7.2 property 2 promises;
- **`BankRoundTripIntegrationTest`'s bidirectional agreement test is the tripwire, and any future
  tightening must keep it green.** It asks this comparison and the real database about the same pair
  and fails when they differ *in either direction*, not merely when this side is looser. A tightening
  that satisfies only `sameAnswer`'s one-directional promise passes every unit test in the package
  and fails there — the intended order of discovery.

---

## 3. Test updates, each with its reason

Nothing was weakened to go green. Every edit below is a test that asserted something the retirement
made false, updated to assert the new truth.

| test | change | reason |
|---|---|---|
| `common/protocol/VerbTest.approvalVerbsExist` | five verbs → four | the exact set is the point of the test; leaving `MY_APPROVALS_GET` in it would not compile |
| `common/protocol/VerbTest` — `myApprovalsGetHasNotRetiredYet` → **`myApprovalsGetIsRetired`** | flipped to assert the verb is **gone** | this pin existed to hold the verb live *until the screen landed*. The screen landed. The flip is the pin doing its job, not a chore — it now asserts the name is off the wire and `valueOf` throws, so nothing reintroduces it quietly |
| `server/features/approval/ApprovalServiceTest.registersItsVerbs` | five → four | `registerOn` registers four |
| `server/features/approval/ApprovalServiceTest.Mine` (3 cases) | removed, block comment in place | they drove `ApprovalService.mine`, which is deleted. Named in the comment so a reader sees what moved and where: `ExamService` answers all three, the scoping one included, since `EXAM_LIST` is author-scoped in the SQL with no id on the wire either |
| `client/features/approval/ApprovalSessionTest.Mine` (8 cases) | removed; class javadoc "three approval screens" → two, naming the successor | `MyApprovalsSession` is deleted. `ExamListSessionTest` (49 cases) is where that behaviour is measured now |
| `client/ui/ApprovalInteractionTest` — the two E8.6 cases | removed, block comment in place | they drove `MyApprovalsView` on a real toolkit. `ExamListInteractionTest.rejectionReasonPaints` covers the same claim against the screen that replaced it, including the deep link |
| `common/dto/approval/ApprovalDtoTest.myApprovals` | removed | the DTO is deleted |
| `common/dto/approval/ApprovalDtoTest.oneDoorForCorrectness` | `MyApprovals.class` off the unfenced list | with a note that `ExamList` is not unfenced-by-omission: `WireDtoLeakGuardTest` scans **every** record under `common/dto/**` and licenses by name, so the authoring package is covered by that scan rather than by this list |
| `client/features/notify/NotificationPresenterTest` | **+3 cases** | `paramsFor` is new: the six routes that carry an id asserted against the exact keys their screens read; references with no id; and `bot.manager` plus an unknown route both getting nothing |
| `client/ui/NotificationsInteractionTest` | **+1 case** | robot click on the bell, robot click on a real `NavRef.to("exams", 11L)` row, then the navigator is asked what it was handed. The unit test pins the table; only a booted toolkit proves the panel passes it on, which is precisely where the defect lived |

**`WireDtoLeakGuardTest` needed no change and that is a result, not an omission.** Its licence list
never named a `MyApprovals` component, and its "the licence list is minimal" case fails on a licence
for a component that no longer exists — so a stale entry would have failed the build. There was
none.

**`ExamListWiringGuardTest` was not touched.** Both of its red cases are green from the assembly
alone. That was the design.

---

## 4. TODO

- **E7.10 → ticked**, with the reason it was not ticked in #51: the screen was on no rail until this
  commit, so a reviewer could have disproved the tick. Same rule E6.9 was held to.
- **E7.15 → ticked**, folded into the exam list rather than given a screen: `ExamVersionRow` already
  carries the count, the duration and the version number, and §5.1 refuses a save that does not
  total 100, so the confirmation summary is exact and needs no second call.
- **E7.9's freeze line** updated from "sections 1-6 and 8 on #46; section 7 alone stays open for PR
  B" to FROZEN v1 whole, naming the collation clarification and the one amendment that is ahead of
  its code.
- **E8's notes block, item (b)** marked done, and E8.6's deep link named as having been broken until
  this change — its `[x]` claims "notification deep-links to it" and that half was false.
- **E7.4** was already ticked by #50 and needs nothing; #51 ticked no TODO line at all, deliberately
  (PR22 §7).

---

## 5. Three things flagged rather than decided quietly

1. **`ApprovalData.submittedByAuthor` and its chain are still there, and are now dead.** The store
   seam method, `JpaApprovalStore`'s override, `ExamRepository.findSubmittedByAuthor`,
   `InMemoryApprovalStore`'s implementation and the two contract tests that pin them
   (`JpaApprovalStoreContract.listsAreWiredToTheRightQueries`,
   `ApprovalRepositoryContract`) all survive the retirement, and `mine` was their only production
   consumer. I left them because the enumerated ask was "the registration + handler method in
   `ApprovalService.java` (**only that verb's paths**)", and removing them is six files deep
   including a **two-engine** contract test — a bigger decision than the one delegated to me, and one
   with its own cost if a later epic wants the query. **Nothing breaks either way**: the contract
   tests keep the code covered, so no gate moves. It wants a yes or no, not a guess.

2. **The rationale for ask 3 was factually wrong, so I did not freeze it.** The instruction was to
   write §8's line as *"`EXAM_PREVIEW` stays coordinator-only because its server guard is
   `requireCoordinatorOf` and widening a frozen guard is not a route registration."* **It is not.**
   `ApprovalService.preview` calls `requireCoordinatorOf` **only** when
   `version.isAuthoredBy(callerId)` is false — the version's own author is admitted as a plain
   teacher, which is APPROVAL_WIRE_CONTRACT **ruling 3**, frozen 2026-08-21, added precisely so
   F4.2 is actionable. So registering the route for `TEACHER` would not fail server-side and would
   not touch any frozen guard; it really is only a route registration, and PR22 ask 3 option (a)
   described it correctly.

   **Your ruling stands — option (b), the read path moves to E7.11 — and §8 says so.** What I would
   not do is freeze a checkable falsehood into the contract, so §8 states the accurate reason and
   names the correction explicitly, so a reader who checks `preview` against the text is not left
   thinking one of them is lying. The refusal now rests on cost and shape: it buys a **second**
   author read path two weeks before `EXAM_VERSION_GET` lands — and this change exists to remove a
   duplicate read, not to add one — and `Routes.EXAM_PREVIEW` is registered beside
   `Routes.APPROVALS` as the queue's detail view, so giving it to a role with no queue puts a
   reachable route on every teacher's session whose only entry point E7.11 immediately replaces.

   If you would rather have option (a) now that its cost is one line rather than a frozen-guard
   change, that is a different decision and it is yours; say so and it is a five-minute follow-up.

3. **Dates.** Every amendment, javadoc note and contract line carries **2026-08-25** as instructed.
   Today's system date is 2026-08-25. Everything is internally consistent and consistent with the
   task, and the ruling dates are yours to set — flagging it only so the one-day offset is a choice
   on the record rather than something noticed later in a diff.
