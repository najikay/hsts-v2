# HSTS v2 — Team Split (3-way)

Principle: **the lead builds the platform and the highest-risk features; members own self-contained vertical slices behind frozen contracts.** Nobody blocks anybody: every feature can be built and tested against fakes before integration.

**This is a leadership map, not an isolation map (course spec §11).** The system description
requires that all members participate in every project component and forbids splitting work so
that each member works alone on her part. "Ownership" below means *leads the implementation of* —
every component still crosses all three members, structurally:

- **Contracts are joint work.** Every frozen contract (protocol v2, the schema in ARCHITECTURE §5,
  the grading wire contract) is negotiated in review rounds where the non-owners' questions and
  amendments are recorded in the PR record and in DECISIONS.md — the schema carries A's design and
  the lead's round-1/round-2 amendments; the seed carries B's design and both reviewers' decisions.
- **Every PR is cross-reviewed** before merge (lead reviews all; members' red-team passes have
  changed lead-owned code — P-5 is Member A's finding fixed in the lead's router layer).
- **Acceptance testing crosses all boundaries**: B authors and executes the 115-case table against
  A's and the lead's features; E21 hardening explicitly has each member attacking the others' code.
- **Every member must be able to explain every component at the defense** — E22.4 (below) makes
  the cross-walkthroughs a scheduled task, not a hope.

---

## 1. Ownership map

### Naji — Lead / main production `[L]`
**Owns:** architecture + all final decisions · protocol & DTO contracts (E1) · server core (E3) · client core + design system (E4) · auth (E5) · **take-exam + timers** (E10) · extension/monitoring (E11) · **study bot** (E16) · notifications (E17) · edit locks (E18) · server console (E19) · packaging (E20) · repo/CI (E0).
**Also:** reviews every PR; polishes all screens to design-system standard; owns the demo.

Rationale: the two things that killed v1 (exam execution correctness, bot) plus everything cross-cutting stay with the lead.

### Member A — "Authoring pipeline" `[A]`
**Owns:** database schema, entities, repositories, seed dataset (E2) · question bank (E6) · exam builder + auto-generation (E7) · approval workflow (E8) · release manager (E9).
That is one coherent story: *teacher creates content → coordinator approves → teacher releases.* Client screens included (using the component library), tests included.

### Member B — "Results & insight" `[B]`
**Owns:** grading (E12) · student results (E13) · teacher results + histogram (E14) · principal views + report engine (E15) · submission document + acceptance-test table (E22 drafting).
One coherent story: *everything after the student submits.* Client screens + tests included.

### Shared
E21 hardening is everyone (each attacks the others' features); E23 stretch assigned ad hoc.

## 2. Load & sequencing

**Endgame update (2026-08-21):** the defense moved to **Aug 27** and the group name is **12-1**.
Compressed plan: features done Aug 23-24 · integration + lead's manual passes Aug 24-25 ·
hardening Aug 26 · submission zip + two-machine rehearsal + DEFENSE Aug 27. Because of the lost
day, **E14.3 (StatChart) moved to the lead** (it is shared design-system work; B keeps E14 and
wires the finished component), and the lead also runs E11.7. **E8 and E14 were taken by the lead on Aug 21** (the offers converted to decisions when the lead lane finished early); A keeps E6, E7, E9; B keeps E12, E13, E15 and the SeedArithmeticTest port. Defense note: the cross-walkthroughs (E22.4) cover both reassigned epics so every member can still speak to them.

| Milestone | L | A | B |
|---|---|---|---|
| M0–M1 | E0,E1,E3,E4,E5 | E2 schema+entities+repos | studies component library; drafts acceptance-test table skeleton; E2 seed content |
| M2–M3 | E17, E18, screen polish | E6 → E7 → E8 | E12 service groundwork (auto-grade against fixtures), E13 skeleton |
| M4–M5 | E10 → E11 | E9; support E10 integration | E12 → E13 → E14 |
| M6 | E16 (bot) | seed v2 + E21 prep | E15 |
| M7–M8 | E19 done, E20, E21 lead, demo | E21 | E21, E22 doc |

B starts lighter (grading needs attempts to exist) — that early slack is spent on seed content, the test-table skeleton, and learning the session-test pattern, so M4–M6 goes fast.

## 3. Contracts & handoffs (how we avoid stepping on each other)

1. **Protocol freeze:** before A or B starts a feature, [L] merges the feature's `Verb`s + DTOs with Javadoc (`payload → response`, error codes). Changing a frozen contract requires [L] sign-off in the PR.
2. **Fakes first:** client sessions test against `FakeClientConnection`; services against Mockito repos. The integration test that joins them is written by the feature owner, reviewed by [L].
3. **Component library only:** members never hand-roll UI controls or CSS; missing component → issue to [L], who ships it within a day. This is how the whole app stays visually consistent.
4. **Feature packages:** each person works in `*/features/<their-feature>/` + their DTO package. Cross-feature imports of internals are a review-reject.
5. **Repositories grow with their callers** (decided 2026-08-20, E12 PR 3): `server/db/repos` is shared infrastructure under E2's conventions, not a fenced feature package. Any member may add the queries her feature needs to the existing repository classes, provided each addition (a) follows the established style — Contract test on both engines, consumer named in the javadoc, correctness-suffix rules respected; (b) is flagged in a dedicated PR section so the E2 owner sees exactly what arrived in his area; (c) gets the E2 owner's post-merge review pass. New queries never weaken an existing one's contract.
5. **Design review:** every new screen gets a 10-minute screen-share review with [L] before its PR merges (layout, states, animation, empty/loading/error).
6. **Seed ownership:** A owns seed structure; whoever adds a feature adds the seed rows that make it demo well (in the same PR).
7. **Daily sync (async ok):** what merged / what's next / blockers, in the team chat; blockers on [L]'s contracts answered same day.

## 4. Definition of Done (copy into every PR)

- [ ] Behavior matches PRD ids listed in the task
- [ ] Unit tests (+ integration where protocol/DB touched); coverage not lower
- [ ] Edge cases from PRD §6 for this feature handled (server + UI)
- [ ] Uses design-system components; screen reviewed by [L]
- [ ] Errors/success/progress feedback present (NFR-21); no user-initiated refresh (NFR-18)
- [ ] Seed data updated if the screen needed it
- [ ] DECISIONS.md / PROBLEMS.md updated if applicable
- [ ] CI green

## 5. Submission credit mapping (for the doc's "responsibilities" section)

- Naji: architecture, networking/protocol, UI framework & design system, authentication & sessions, exam execution & timing, study bot, real-time subsystem, deployment.
- Member A: data layer & migrations, question bank, exam building & auto-generation, approval, release management, test dataset.
- Member B: grading & statistics, student/teacher results, principal views, report engine, acceptance testing & submission document.
