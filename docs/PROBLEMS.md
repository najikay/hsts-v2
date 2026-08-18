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

*(entries start here)*
