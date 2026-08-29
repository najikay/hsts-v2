# HSTS — manual test, two machines

**A guided walkthrough for a person, for everything that needs a second computer, a cable to
pull, or the real network.** Owner: Omar. Run it after `MANUAL_TEST_ONE_MACHINE.md` is green,
on the same commit on both machines. Put an X in every box that passed and write what you saw
under any you could not tick; paste the file back as `docs/manual-round-N-notes-2m.md`.

**Machines.** **A** runs the server (from a terminal, console up) and one staff client. **B**
runs one or two clients. Same Wi-Fi/LAN first; Part 6 repeats the key steps on the university
network.

**Passwords: `demo123`.** Accounts: `dana.cohen` (teacher), `maya.levi` (student, ID
`374301851`), `omer.katz` (student, ID `361489206`), `avi.mizrahi` / `tamar.shani` (Java
co-teachers).

**On both machines, in `C:\dev\hsts-v2`:**
```
git pull
.\mvnw -DskipTests clean package
```
On A: `java -jar target\hsts-server.jar`. Clients: `java -jar target\hsts-client.jar`.

---

## Part 1 — Discovery, pinning, manual entry (15 min)

- [ ] 1.1 Server on A. Client on B: within ~2 seconds the picker shows A's **name, address
      and fingerprint**. Pick it → Login. Quit the client; start it again → it goes straight
      to Login **without asking** (the pin).
- [ ] 1.2 On A, add a Windows Firewall rule blocking UDP for `java.exe` (or put B on another
      subnet). Start B's client: after the timeout it shows the **manual form**. Type A's
      address and `5555` → Login. Remove the rule.
- [ ] 1.3 On A: stop the server, open `server-id.properties` (in the project root on a dev
      machine, beside the jar on the deliverable), change the fingerprint value to a
      different string, start the server. On B: the client warns **"The server at … now
      identifies itself as … but this computer connected to … before"**. Choose not to
      continue → back to the picker. Connect again, choose to continue → Login, and the next
      launch does not warn.
- [ ] 1.4 On A's console pick another network candidate; on B type that address in the
      manual form → works (or the sentence if that adapter is not reachable — write which).
- [ ] 1.5 On A's terminal window, nothing red appears when B discovers; from any machine
      send junk to the discovery port (`echo junk | ncat -u A_ADDRESS PORT`) → the console
      logs an ignored packet and discovery still answers.

## Part 2 — Sessions across machines (10 min)

- [ ] 2.1 `dana.cohen` signs in on A. `dana.cohen` on B → refused with the
      already-signed-in sentence. Sign out on A → B succeeds.
- [ ] 2.2 `dana.cohen` on B. **Pull B's cable** (or turn its Wi-Fi off). Within a few
      seconds A can sign in as `dana.cohen`. B shows the reconnect banner in plain words.
      Plug B back in; press **Retry** on the banner (or sign in again) — it recovers in the
      same window.
- [ ] 2.3 `avi.mizrahi` on A opens the editor on question **21003** (Question Bank → Java 21
      → 21003 → Edit). `tamar.shani` on B sees the row badged **Editing … Avi Mizrahi**. Pull
      A's cable: within seconds Tamar's badge clears. Reconnect A: Avi's editor reports the
      lock is gone (stale) rather than saving over her.

## Part 3 — The exam across the wire (20 min)

- [ ] 3.1 Dana on A: Exams → New exam → Algebra 11 → `Two-machine check`, **3** minutes,
      three questions 40/30/30 → Create exam → Submit for approval. (Rina on either machine:
      Approvals → Approve.) Dana: Releases → Release an exam → code `TM3X`, Opens now, Closes
      + 8 minutes → Release it.
- [ ] 3.2 `maya.levi` on B: Take Exam → `TM3X` → Continue → ID `374301851` → the paper.
      `omer.katz` on a second B client: same → ID `361489206` → the paper. Answer one
      question each.
- [ ] 3.3 A: Live Monitor → the sitting: both rows Started with countdowns. **Add time 1** →
      on B **both** students' chips flash, "+1:00" floats, the toast names Dana Cohen —
      within a second of the click.
- [ ] 3.4 **Pull B's cable** with Maya mid-exam. B: the reconnect banner. A's monitor: her
      answers so far are shown as saved. Plug in → **Retry** → the paper rebuilds with her
      answers and the **remaining time the server says**, not what B's clock guessed.
- [ ] 3.5 Set B's system clock 10 minutes ahead. Maya's countdown keeps ending when the
      server says, not earlier. Put the clock back.
- [ ] 3.6 Omer hands in. Maya does nothing. Time runs out: **Time is up** on B; A's monitor
      shows Handed in 1 / Timed out 1, frozen.
- [ ] 3.7 **Stop A's server for 20 seconds and start it.** Both B clients show the banner
      and then reconnect on their own (or on Retry); Maya's ended attempt stays ended; the
      console counts the clients again.

## Part 4 — Concurrency (10 min)

- [ ] 4.1 Two teachers (Avi on A, Tamar on B) both try to edit **21005** at the same moment:
      exactly one gets the editor, the other the read-only banner; when the first saves, the
      second's screen shows the new version live.
- [ ] 4.2 Rina approves an exam on A while Dana's Exams screen is open on B: the chip flips
      **without a refresh**.
- [ ] 4.3 Dana approves grades on A while Maya's My Grades is open on B: the card appears and
      the bell badge rises **without a refresh**.
- [ ] 4.4 Avi changes a bot source on A: Tamar's bell on B rings live.
- [ ] 4.5 Open as many clients as you can across both machines (different accounts) and
      browse the Question Bank with illustrated questions on all of them at once: no stalls,
      every image arrives.

## Part 5 — Deployment (15 min)

- [ ] 5.1 On a **clean Windows account** on B (no JDK setup beyond Java itself): copy
      `G12-1_Server.jar`, `G12-1_Client.jar` and the two `.properties` files beside them.
      Double-click the server → console appears; double-click the client → Login. Also start
      each with `java -jar` from a terminal.
- [ ] 5.2 Against an **empty** MySQL schema: the server migrates on start (Flyway lines), the
      console offers the seed → **Load demo data if missing** → sign in as `dana.cohen`.
- [ ] 5.3 `findstr /i key client.properties` finds nothing; the API keys live in
      `server.properties` beside the server jar only.

## Part 6 — On the university network (15 min)

- [ ] 6.1 Repeat 1.1, 2.1, 3.2–3.4 and 3.7 on the network the defence runs on. Write down
      the addresses that worked and any Windows firewall prompt that appeared.
- [ ] 6.2 Run `DEMO_SCRIPT.md` acts 1 and 5 end to end on the two machines as they will stand
      on the day.

---

## Appendix — requirement coverage (course PDF ids → step)

F1.3 2.1 · F1.4 2.2–2.3 · F1.5 1.1 · F6.2 3.5 · F6.3 3.4, 3.7 · F6.4 3.6 · F7.1 3.3 · F7.3 3.6 ·
F8.4 4.3 · F10.0–F10.2 2.3, 4.1 · F12.3 4.4 · F12.6 5.3 · F13.1 3.7 · F13.2 1.2, 1.4 ·
F13.3 1.1, 1.5 · F13.4 1.1–1.3 · F14.1 5.1 · F14.2 5.1–5.2 · S-40 2.2, 3.2, 4.5 · S-42 6.1 ·
NFR-15 5.1, 6.1 · NFR-16 2.1, 4.5 · NFR-17 5.2 · NFR-18 4.2–4.3.
