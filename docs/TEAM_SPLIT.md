# HSTS v2 — Team Split (3-way)

Principle: **the lead builds the platform and the highest-risk features; members own self-contained vertical slices behind frozen contracts.** Nobody blocks anybody: every feature can be built and tested against fakes before integration.

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
