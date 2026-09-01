# Zoom defence runbook — Monday 2026-09-07, 19:00

The defence is remote. This file adapts `DEMO_DAY.md` (machine prep) and `DEMO_WALKTHROUGH.md`
(the 21 marks) to Zoom, and records the network decision. Read those two first; this file only
says what CHANGES.

**The decision (lead, 2026-09-01): Tailscale mesh, primary.** Server at Naji's, clients at
Omar's and Amjad's homes, one private encrypted network, zero code change. Plan B is a
cloudflared TCP tunnel through Naji's domain (same properties, more setup). Fallback, always
armed: everything on Naji's home LAN with two laptops. **Never** expose port 5555 to the public
internet: a Java-serialization endpoint on a public address is an RCE-class exposure, and it is
the same structural-security argument we make about the bot, applied to ourselves.

---

## 1. Tailscale setup (each member, once, BEFORE the rehearsal)

1. Install Tailscale (tailscale.com/download), sign in — Naji creates the tailnet and invites
   `omar_w231@outlook.com` and `aabdel25@campus.haifa.ac.il` (three users are free).
2. Naji enables **MagicDNS** in the admin console. Each machine then has a stable name.
3. Verify: `tailscale status` on each machine shows the other two; `ping <naji-machine>` works.
4. Connect the client: launch it → discovery will find nothing (broadcasts do not cross the
   mesh — expected, and it demonstrates that discovery failing never blocks connecting) →
   **change server** → host = Naji's Tailscale name (or 100.x.y.z IP), port 5555 → Connect.
   The endpoint is remembered and pinned; from then on the client auto-connects.
5. First connect may take a few seconds ("Still trying…" is normal). If a member's network
   relays instead of going peer-to-peer, it still works; expect slightly slower pushes.

## 2. Machine roles (replaces DEMO_WALKTHROUGH's A/B for Zoom day)

| Machine | Runs | Accounts |
|---|---|---|
| **Naji** | server (from Windows Terminal, console window up) + staff client | teachers, coordinators, principal |
| **Omar** | client over the mesh | students (`maya.levi`, `omer.katz`, refusal students) |
| **Amjad** | client over the mesh | second teacher for lock moments (`tamar.shani`), spare student |

One session per user still holds; every account change is a Sign out first.

## 3. Zoom choreography

- **Host setting** (ask at the start if the course staff hosts): Share Screen arrow →
  Advanced Sharing Options → **Multiple participants can share simultaneously**. Then announce:
  "we are both sharing — teacher's machine left, student's right — pick side-by-side view."
- **Default**: Naji shares (server console + staff client). **Omar adds his share** for the
  live-push moments; **if simultaneous share is refused**, Omar shares INSTEAD at those
  moments and hands back — rehearse both.
- The double-share moments, in walkthrough order:
  - **Mark 4**: Dana submits (Naji) → the queue row and bell appear on Rina's screen — run
    Rina on Amjad's machine for this so the push crosses the internet on camera.
  - **Marks 6–7**: Omar shares while sitting the exam; Naji adds time → "+1:00" lands on
    Omar's share in the same second. **This is the wow moment; protect it.**
  - **Mark 8**: grades approved on Naji's share → the card and bell appear on Omar's.
  - **Mark 14**: Omar triggers the cross-course bot notice → INTEGRITY_ALERT rings on Naji's.
- Camera/audio discipline: whoever is NOT sharing mutes; the speaker narrates their own share.

## 4. What the mesh changes in the walkthrough (nothing else changes)

- **Mark 1 / 15 (discovery)**: show discovery working on Naji's LAN if a second local device
  is available; across the mesh, demonstrate the manual-entry + pinning path and SAY why:
  "UDP broadcast is link-local by design; across networks the manual path takes over, and the
  pinned identity carries the trust." That is an honest, spec-aligned answer.
- **Mark 15's "two machines"**: stronger than the room version — three cities, one system.
  The phase-2 line writes itself: *"the internet version is a deployment change; the defence
  you are watching is running across the internet right now with zero code change, over an
  encrypted mesh."*
- **Latency**: all timers are server-side; pushes arrive when they arrive (typically <100 ms
  on a direct mesh path). Nothing to configure.

## 5. Rehearsal (BEFORE the weekend — do not leave this to Sunday)

- [ ] All three on the mesh; both remote clients connect, sign in, stay stable 15 minutes.
- [ ] Run marks 6–7 end to end across the mesh (join, sit, extend, time-up).
- [ ] Zoom dry run: multiple-share on, side-by-side confirmed on a fourth device or phone.
- [ ] Time the cut-list version of the demo (15 min) once.
- [ ] Test the fallback switch: kill the mesh, move both clients to Naji's LAN laptops, be
      demo-ready again inside two minutes.

## 6. Fallback trigger

If, on the day, a remote client cannot connect or drops twice: say one sentence ("we will run
both clients locally and keep going"), switch to the two laptops on Naji's LAN, and continue
from the same mark. The walkthrough is identical from that point. Losing the cross-town wow
loses zero marks; stalling the demo loses real ones.

## 7. Day-of checklist deltas (on top of DEMO_DAY's)

- [ ] **Reload demo data** Monday afternoon, not the night before (relative windows).
- [ ] All three machines: `git pull` + rebuild from the FINAL commit that morning; verify
      `git log --oneline -1` matches on all three (mixed jars do not speak).
- [ ] Tailscale up on all three (`tailscale status`) BEFORE the meeting; clients pre-connected
      and signed out at the login screen.
- [ ] Live bot keys on the server + the five-minute live-key checklist.
- [ ] Zoom: join 15 minutes early; test both shares; phone on the table as a fourth viewer.
- [ ] The submission zip must already be rebuilt from the final commit (it carries the jars).
