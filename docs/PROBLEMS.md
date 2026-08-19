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

## P-2 — Invisible toast layer swallowed every mouse click and scroll    (2026-08-19, found by Naji's manual gallery test)
**Problem.** In the E4 gallery (and, via the shared AppShell plumbing, the whole future app) no mouse interaction worked — clicks dead, wheel scroll dead, only keyboard navigation alive. The user could only ever see the top of the gallery.
**Investigation.** A TestFX probe reproduced it headless; a scene-level event filter showed every MOUSE_PRESSED landing on the full-scene `ToastStack` overlay. The code correctly called `setPickOnBounds(false)`, but the stylesheet gave the layer `-fx-background-color: transparent` — and in JavaFX picking, a Region with **any** background fill (transparent included) is pickable geometry; only a *null* background is truly click-through. 729 unit tests and rendered screenshots could not see it: the bug exists only in real input dispatch.
**Solution.** Removed the background declaration from `.hsts-toast-stack` (with an explanatory comment) and added `GalleryInteractionTest` — a permanent TestFX regression test that robot-clicks a palette swatch (asserting the accent stylesheet actually changes) and delivers a wheel event through the scene graph (asserting the viewport moves). Policy takeaway adopted: UI smoke tests must include at least one *real input* assertion, not only "nodes exist" checks.
**Evidence.** Failing probe run → target-chain log naming ToastStack → green `GalleryInteractionTest` + full `verify` after the one-line CSS fix.

## P-3 — The login throttle was a password oracle (spec-level bug, caught in security review)    (2026-08-19, lead review of E5)
**Problem.** The E5 brief specified: while an account is locked out, a wrong password answers "Incorrect username or password" but a correct password answers "Too many attempts". The implementation followed the spec faithfully — and the spec was wrong: the message difference lets an attacker keep guessing during the lockout and learn exactly which guess was correct. The throttle confirmed the very password it existed to protect.
**Investigation.** Found in the line-by-line review of AuthService before commit (the implementation itself was excellent — constant-time BCrypt, dummy-hash against user-enumeration timing, generic errors; the flaw was inherited from the written spec). A second review finding: failure records are kept per submitted username — the uniformity that prevents user enumeration — which made the throttle map unboundedly growable by spraying random names.
**Solution.** While locked, every attempt is refused with the same throttle message BEFORE any lookup or verification (no oracle, no free BCrypt work, still truthful for the real owner). The throttle map self-purges stale entries past a 10,000-entry threshold, keeping live locks. Both behaviors pinned by tests, including "unknown usernames throttle identically" and the spray-bounded test.
**Evidence.** AuthServiceTest throttle nest (8 tests) + LoginIntegrationTest, all green; the lesson recorded: specs get security-reviewed like code, because implementations faithfully reproduce spec bugs.

## P-4 — Two invisible build-environment traps: stale IDE terminal env, poisoned MAVEN_OPTS    (2026-08-19, found by Member B)
**Symptom.** `mvnw clean verify` fails on a machine that has a perfectly good Temurin 21 install, with errors that point nowhere near the real cause.
**Investigation.** Two independent causes, neither guessable from the error text: (1) IntelliJ's built-in terminal inherits the environment from when the IDE process started, so JAVA_HOME/PATH changes made after launch are silently absent in every "new" terminal tab; (2) a user-level MAVEN_OPTS carrying JDK 24+ only JVM flags stops a JDK 21 JVM from booting at all, before Maven prints anything useful.
**Solution.** Team rules: after changing JAVA_HOME/PATH, restart the IDE (a new tab is not a new environment); check MAVEN_OPTS (`$env:MAVEN_OPTS` in PowerShell) when a build dies before Maven prints, and clear flags targeting a newer JDK. Recorded because both will bite again on any fresh machine, including the defense laptops.
**Evidence.** Member B's local `mvnw clean verify` green on Temurin 21 after both fixes.

*(more entries follow)*
