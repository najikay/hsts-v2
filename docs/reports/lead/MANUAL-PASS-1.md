# Manual pass 1 — findings register (2026-08-23, lead solo)

First human pass over the whole client, run the evening E9/E15/E15.2/E7-types landed and
before #41's bank screens were merged. Findings numbered `F-n`; routed per the triage map.
Bugs with acceptance impact stay in ACCEPTANCE_TESTS.md's `B-n` register; these are the
design and UX findings.

**Two rulings recorded at the same session:**

- **Language: ENGLISH everywhere.** UI copy was already English; any Hebrew remaining in
  seed content (question stems, exam names, free text) is translated in UI wave 1.
  `DEMO_ACCOUNTS.md` stays the roster authority; `SeedArithmeticTest` guards the numbers.
- **No new animation dependencies.** No Lottie-style renderer exists for JavaFX that we
  would ship four days before a defense. The "alive" quality comes from the in-house
  `Motion` system: eased route transitions, staggered row entrances, card hover lift,
  animated empty states. The wave-2 design canvas specifies these per screen.

## Resolved during triage (no work)

| # | What was seen | Resolution |
|---|---|---|
| F-1 | Question bank empty with a loaded seed | The LEGACY screen reads the prototype's `Questions` table (`QuestionDAO`); the seed fills the Flyway schema. Expected until retirement; the #41 bank screen reads the real tables. Dies with the retirement PR. |
| F-2 | No grading data | The 8.5 walk approved the fixture; `SeedMain --reseed` restores it. Reseed before every session (DEMO_DAY §3.4 already says so). |
| F-3 | "Exams" rows not clickable | E8's status-only half by design; E7.10 replaces the screen behind the same rail id this week. |
| F-4 | Light and System modes look identical | The OS is in light mode, so System IS light. Keep all three modes; demo line: "we follow the OS". |
| F-5 | Settings accents do not repaint the legacy bank | Legacy screen carries its own stylesheet; dies with retirement. No work. |

## Wave 1 — mechanics (lead, agent-built, tonight 2026-08-23 → 24)

| # | Finding | Fix |
|---|---|---|
| F-6 | Notifications open as a centered modal; bell needs two clicks; no click-outside close | Popover anchored to the bell: one click opens, click outside closes, ESC closes. |
| F-7 | Histogram full view has no way back | Back affordance; and a back-button convention on EVERY drill-in screen (keep breadcrumbs/toggles too). |
| F-8 | Double-click needed to open rows (approvals queue and elsewhere) | Single click opens everywhere; selection vs open disambiguated per screen. |
| F-9 | Strings cut off with "…" across tables (dates, names, headers) | Global table-sizing pass: content-sized preferred widths per column, the B-5 treatment applied to every table. |
| F-10 | Dashboards empty for every role | Dashboard cards v1 per role: teacher (today's sittings, awaiting grading, recent results), coordinator (+pending approvals), student (next exam, latest grade, bot), principal (school snapshot). Each card navigates on click. |
| F-11 | Bot add-source modal has a broken-looking shadow | Fix the effect; align with house dialog style. |
| F-12 | Profile name renders as a button but does nothing | Wire it (sign-out / theme quick-switch menu) or render as plain text. Decision at implementation: menu. |
| F-13 | Hebrew remaining in seed content | Translate to English (ruling above). |
| F-14 | Bot screens unclear (what the student bot does; how a bot is added) | Copy pass: one explanatory line per screen in the established voice; empty states say the next action. |

## Wave 2 — the remodel direction (canvas first, then implementation 2026-08-24 → 25)

Taste alignment goes through a design canvas BEFORE implementation: four artboards
(teacher dashboard with cards, notification popover open, the data-table treatment, one
student screen in the livelier style), light + dark, motion spec written on the canvas.
The lead marks up, then agents implement the approved direction across screens.

Scope guard: this is a polish wave over the existing structure, not a rebuild. Screens
keep their sessions and tests; only views and CSS move. Anything that would touch a wire
or a session is out of scope for wave 2.

## Standing items fed to hardening (E21)

- TestFX tripwire for grading selection flow (from the #40/#42 regression).
- `ForCheckedForm`-class check: licences with no readers.
- Print toggles clickable both ways on all three print-capable screens (B-6 follow-up;
  Amjad confirms).
