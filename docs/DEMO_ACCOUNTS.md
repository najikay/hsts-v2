# HSTS — demo accounts

> **Scope: development credentials only.** These users come from
> `server.features.auth.InMemoryUserDirectory`, the fixture directory E5 ships so the
> login flow, the four role shells and the single-session rule are demonstrable before
> the database exists. **It is replaced by the seeded DB in E2 PR3** — at that point the
> usernames stay (the seed mirrors them), the directory implementation changes, and this
> file is re-pointed at the seed migration. Not part of the submission document (PRD §5).

## Sign in

Connect screen → server address (default `localhost:5555`) → **Sign in** with the
username and password below.

**Password for every dev user: `demo123`**

| Username | Password | Name | Role | Courses |
|---|---|---|---|---|
| `dana.cohen` | `demo123` | Dana Cohen | TEACHER | Algebra 11 (11), Calculus 12 (12) — teaches |
| `rina.barak` | `demo123` | Rina Barak | COORDINATOR | Calculus 12 (12) — teaches, and coordinates |
| `maya.levi` | `demo123` | Maya Levi | STUDENT | Algebra 11 (11), Java Programming 21 (21), Databases 22 (22) — enrolled |
| `noam.peretz` | `demo123` | Noam Peretz | STUDENT | Calculus 12 (12), Java Programming 21 (21) — enrolled |
| `principal.avia` | `demo123` | Avia Shalev | PRINCIPAL | — (school-wide read-only, S-7) |

Course codes are the 2-character `courses.code2` values from ARCHITECTURE §5 and match
the seed dataset in PRD §5 (Mathematics = 10 → Algebra 11, Calculus 12; Computer
Science = 20 → Java Programming 21, Databases 22).

## What the fixture does and does not do

- Passwords are **BCrypt-hashed at construction** (cost 10) and verified through the
  real `BCrypt.verifyer()` path — the fixture shortcuts the storage, never the
  verification (F1.1, S-38).
- The **throttle is live**: 5 failed attempts on a username → 30-second lockout, keyed by
  the normalised username. Locking yourself out during a rehearsal is a 30-second problem;
  restarting the server also clears it (the counters are in memory).
- **One session per user** (F1.3): signing `dana.cohen` in on a second client is refused
  with "This account is already signed in elsewhere." Closing the first client — or
  killing it — frees the session immediately, as does Sign out.
- Roles here drive the shell only. Server-side permission guards read the role from the
  session on **every** request; nothing trusts the client's copy.

## Demo tips

- **Two-machine duplicate-login demo:** sign in as `dana.cohen` on machine A, try the same
  on machine B → the exact F1.3 message appears inline under the password field. Sign out
  on A, retry on B → it works.
- **Throttle demo:** type a wrong password 5 times for `maya.levi`; the 6th attempt — even
  with the right password — answers "Too many attempts — try again shortly." for 30 seconds.
- **Role tour:** `maya.levi` (student rail: Dashboard / Take Exam / My Grades / Study Bot /
  Settings) → `dana.cohen` (teacher rail, no Approvals) → `rina.barak` (same plus
  Approvals) → `principal.avia` (Dashboard / Data / Reports / Settings, nothing mutating).
