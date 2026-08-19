# PR reports

Convention: every substantial PR ships a report here **and** as the PR description on GitHub (same content — the file is the durable copy, the PR body is where review happens).

- Path: `docs/reports/<member-a|member-b|lead>/PR<n>.md` (or `<epic>-<n>.md` once PRs stop being numbered per member).
- Content: what was built, verification table (build/tests/coverage/manual checks), deviations from the contract with reasons, open questions **with the assumption you ran on**, findings that affect others.
- The lead answers open questions in the PR review; accepted decisions get folded into ARCHITECTURE/PRD/DECISIONS in the same round (team rule: gaps found → docs updated same turn).

`member-a/PR1.md` is the reference example of what a great one looks like.
