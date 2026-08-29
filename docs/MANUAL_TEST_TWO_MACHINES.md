# HSTS — manual test, two machines (network, discovery, concurrency, deployment)

The companion of `MANUAL_TEST_ONE_MACHINE.md`. Everything here needs a second computer, a cable
to pull, or the real network. Owner: Omar. Run it after the one-machine walk is green, on the
same build (`git pull` + `clean package` on **both** machines; the jars must come from the same
commit or the wire refuses).

**Machines.** A runs the server (from a terminal, console up) and one staff client. B runs one
or two clients. Both on the same Wi-Fi/LAN first; the last section repeats the key steps on the
university network.

**Notes** as always: one line per observation, your words, into `docs/manual-round-N-notes-2m.txt`.

---

## 1. Discovery, pinning, manual entry (15 min)

1. Server on A. Client on B: the picker shows A's name, address and fingerprint within ~2 s;
   pick it → Login. Quit and relaunch B: it **auto-connects** to the pinned server. *(F13.3,
   F13.4, F1.5)*
2. Block UDP (Windows firewall rule on A for the discovery port, or a different subnet): B falls
   to the manual form after the timeout; type A's address → Login. Remove the block. *(F13.4,
   F13.2)*
3. Stop the server; delete or rename its fingerprint file; start it: B warns of a **fingerprint
   mismatch**; decline → back to the picker; accept → connects and re-pins. *(F13.4)*
4. On A's console pick a different LAN candidate; B's manual entry with that address works; with
   a wrong one → the sentence, no class name. *(F13.2, U-4)*
5. A malformed UDP packet (`echo garbage | nc -u A_ADDR PORT` from any machine) is ignored and
   logged; discovery still answers. *(F13.3)*

## 2. Sessions across machines (10 min)

6. `dana.cohen` on A; `dana.cohen` on B → refused with the sentence. Sign out on A → B succeeds.
   *(F1.3, NFR-16)*
7. `dana.cohen` on B, **pull B's cable** (or disable Wi-Fi): A can sign in as `dana.cohen`
   within seconds (the dead socket freed the session); B shows the reconnect banner. *(F1.4,
   S-40)*
8. Avi on A holds a question editor; pull A's cable: B (Tamar) sees the lock badge clear
   (released on disconnect). Reconnect A: Avi's editor says the lock is gone / stale. *(F10.1,
   F1.4)*

## 3. The exam across machines (20 min)

9. Dana releases a 3-minute exam on A (as in the one-machine walk, step 22); `maya.levi` on B
   sits it; a second student on a second B client (`omer.katz`). *(F6.1, S-40)*
10. **Time Extended across the wire**: Add time on A → both students' chips flash, "+n:00",
    the toast names Dana, within a second. *(F7.1)*
11. **Cable pull mid-attempt** on B: banner in words; answers chosen before the pull are on the
    server (A's monitor shows them saved); reconnect → the paper rebuilds with them and the
    right remaining time. *(F6.3)*
12. **Clock skew**: set B's system clock 10 minutes ahead; the countdown still ends when the
    server says (S-18); put it back. *(F6.2)*
13. Expiry with one student on B and one handed in: Time Up on B, counts on A frozen. *(F6.4,
    F7.3)*
14. **Server restart with clients open**: stop A's server for 20 s and start it; both clients
    show the banner then reconnect; the attempt resumes; the console counts the clients again.
    *(F6.3, F13.1)*

## 4. Concurrency (10 min)

15. Two teachers on two machines edit the same question: the second is read-only with the badge;
    when the first saves, the second's screen updates live. *(F10.0, F10.2)*
16. Coordinator approves on A while the author's Exams screen is open on B: the chip flips
    without a refresh. *(NFR-18)*
17. A grade approved on A appears on the student's My Grades on B without a refresh; the bell
    badge rises. *(F8.4, NFR-18)*
18. Bot sources changed on A notify the co-teacher on B live. *(F12.3)*
19. Ten clients (as many as you can open across both machines, different accounts) browsing the
    bank with illustrated questions: no stalls, images arrive. *(NFR-16, S-40)*

## 5. Deployment (15 min)

20. On a **clean Windows account** on B: copy `G12-1_Server.jar`, `G12-1_Client.jar` and the two
    `.properties` beside them; double-click each; both run; `java -jar` too; the client reads
    `client.properties` (pre-filled server), the server reads `server.properties` (DB, keys).
    *(F14.1, F14.2, NFR-15)*
21. First run against an **empty** MySQL schema: Flyway migrates automatically; the console
    offers the seed; load it; sign in. *(F14.2, NFR-17)*
22. Keys live in `server.properties` only; nothing in `client.properties` or the client jar
    contains one (`findstr /i key client.properties`). *(F12.6)*

## 6. On the real university network (15 min)

23. Repeat 1, 6, 9–11 and 14 on the network the defense runs on. Write down the addresses that
    worked and any firewall prompt Windows raised. *(S-42, NFR-15)*
24. Run `DEMO_SCRIPT.md` acts 1 and 5 end to end on the two machines as they will stand on the
    day.

## Coverage

F1.3 6 · F1.4 7–8 · F1.5 1 · F6.2 12 · F6.3 11, 14 · F6.4 13 · F7.1 10 · F7.3 13 · F8.4 17 ·
F10.0–F10.2 8, 15 · F12.3 18 · F12.6 22 · F13.1 14 · F13.2 2, 4 · F13.3 1, 5 · F13.4 1–3 ·
F14.1 20 · F14.2 20–21 · S-40 7, 9, 19 · S-42 23 · NFR-15 20, 23 · NFR-16 6, 19 · NFR-17 21 ·
NFR-18 16–17.
