# HSTS v2 — Problems Log

Running log of design/coding problems we hit and how we solved them. The assignment's submission doc requires one written up ("תארו בעיית תכן או קידוד שנתקלתם בה... וכיצד פתרתם אותה") — keep this honest and specific so the best story writes itself at E22.2.

Format per entry:

```
## P-<n> — <short title>            (date, who)
**Problem.** What went wrong / what was hard, with the symptom.
**Investigation.** What we tried, what we learned.
**Solution.** What we did, and why that over alternatives.
**Evidence.** Test/commit/PR that proves it.
```

Strong candidates to watch for (from v1's failures — if we hit them again, document the fix here):
- Exam vs. execution modeling (one exam, many runs) — see ADR-011
- Timer expiry vs. client disconnect race (server-authoritative force-submit) — ADR-010
- Two teachers editing the same question (locks + optimistic versioning) — ADR-008
- Keeping correct answers off the student wire (projection design) — E2.12
- Bot provider outage mid-demo (fallback chain) — ADR-009
- JavaFX fat-jar double-click on a clean Windows machine — E20.2

---

## P-1 — Non-ASCII (Hebrew) Windows username breaks the forked test JVM    (2026-08-19, Naji + lead)
**Problem.** `mvnw clean verify` on Windows died before running a single test: `FileNotFoundException: C:\Users\????\...\jacoco.exec` and "The forked VM terminated without properly saying goodbye".
**Investigation.** The username נאגי appears in the repo path. Surefire launches the test JVM via `cmd.exe`, and the JaCoCo `-javaagent` options string gets decoded with the Windows ANSI code page — the Hebrew characters become `????`, so the agent cannot create its output file and the fork crashes at startup. WSL builds and Linux CI were unaffected (UTF-8 paths native there), which is why it only surfaced on the first full Windows test run.
**Solution.** Team rule: clones live on an ASCII-only path (`C:\dev\hsts-v2`). Moving the folder fixes it completely; no build config change needed (and no fragile encoding flags to maintain).
**Evidence.** Failing run 2026-08-19 13:25 (jvmRun1 dumpstream); green `clean verify` after the move.

*(more entries follow)*
