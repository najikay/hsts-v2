# Brief — Member B: Week 1 (grounding + seed content + acceptance skeleton)

**Owner:** Member B · **Reviewer:** Naji · **Branch:** `feat/b-week1` · Your coding epics (E12–E15 grading/results/reports) start once exam attempts exist (~M4); this week's work makes that start fast and is genuinely on the critical path.

## 0. Setup (same as everyone)
1. Install **JDK 21** (Temurin) + **MySQL 8**. `java -version` → 21.
2. Clone `https://github.com/najikay/hsts-v2.git`, `./mvnw clean verify` → BUILD SUCCESS (report in group if not).
3. Read: `docs/PLAN.md`, `docs/PRD.md` (all of it — you are the acceptance-test owner, you need the full picture), `docs/TODO.md` E12–E15 + E22, `docs/TEAM_SPLIT.md`.

## 1. Deliverable 1 — Acceptance-test table skeleton (feeds E22.1, submission doc)
Create `docs/ACCEPTANCE_TESTS.md`: one row per scenario from the course test outline (scenarios 1–21), columns: `# · Scenario · Steps · Expected result · Actual · Status · Bugs found`. Fill #, Scenario, Steps, Expected now from PRD (each scenario maps to PRD features — e.g. scenario 2 = F2.x). Actual/Status stay empty until testing. This document is what we literally submit and demo from, so precision > speed.

## 2. Deliverable 2 — Seed content (feeds E2.15, Member A implements the loader)
Create `docs/seed/SEED_CONTENT.md` with the actual data per PRD §5: subjects/courses with their 2-digit codes, 5 teachers + 12 students (realistic names, roles, coordinator assignments, enrollments — each student 2–3 courses), **~40 questions** (per question: course, topic, difficulty, text, 4 distinct answers, which one is correct, illustration yes/no) including the **deliberately thin topic** (2 questions, no Hard) for the infeasibility demo, 6 exams in mixed states, the 4 executions, a realistic grade distribution for the closed one (so the histogram looks good — vary it, don't make it uniform), 2 bot sources per course (real paragraphs of course-like content), ~8 bot Q&A session sketches. Questions may be Hebrew or English — mix both (we must prove RTL works).

## 3. Deliverable 3 — Edge-case ownership pass
Read PRD §6 (edge-case catalog). For the **Grading / Reports / Results** lines (your epics): expand each into a concrete test idea (given/when/then, 1–2 lines each) appended to ACCEPTANCE_TESTS.md under a "Hardening" section. This becomes your E12–E15 test plan.

## 4. Rules
- Your files this week: `docs/ACCEPTANCE_TESTS.md`, `docs/seed/**`. Nothing in `src/` yet.
- PR when deliverable 1 is done (don't wait for all three); DoD checklist from TEAM_SPLIT §4 in the PR.
- Questions about what a scenario should do → group chat same day; PRD is the source of truth, flag anything where PRD seems wrong or incomplete (finding spec holes now is exactly your job).
