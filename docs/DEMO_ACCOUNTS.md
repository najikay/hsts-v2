# HSTS — demo accounts

> **These accounts come from the seeded database** (`server.db.seed`, E2.15), not from a
> hand-written fixture. E5 originally shipped `server.features.auth.InMemoryUserDirectory` so
> the login flow and the four role shells were demonstrable before the database existed; that
> directory has been replaced by `RepositoryUserDirectory`, which reads the same usernames out
> of MySQL. The usernames and the password did not change, so nothing in the demo script does.
> Not part of the submission document (PRD §5).

## Before a demo: reseed

The seed's execution windows are **relative to when it was loaded**. One execution is
"scheduled for today", another is "live right now", and those are what the release demo and the
take-exam demo need. A database seeded a fortnight ago presents a live exam whose window closed
two weeks earlier.

So the standard step before a demo is a **reseed**, from the server console button or the
command line flag. It empties the database and reloads, resolving every timestamp against the
current clock. It asks for confirmation first, because it deletes everything.

A plain load, the default and what first boot offers, only inserts rows that are missing. It is
safe to repeat and safe to run against a database somebody is using, but it will **not** refresh
those windows, because the rows are already there.

## Sign in

Connect screen → server address (default `localhost:5555`) → **Sign in** with the username and
password below.

**Password for every seeded user: `demo123`**

> ⚠ **These accounts do not exist until the seed has been loaded.** The server authenticates
> against the `users` table, so on a freshly migrated database every login below fails with
> F1.1's deliberately generic message — **indistinguishable from a wrong password, by design**.
> If a correct password is being refused, load the seed before suspecting the credentials:
>
> ```
> java -cp hsts-server.jar server.db.seed.SeedMain
> ```
>
> That was defect **B-1**, found in acceptance case 1.1: the message is doing exactly what F1.1
> requires by not revealing whether the account exists, which is also why nothing on screen can
> tell you the seed is missing.

The seed hashes it with BCrypt at load, once per user, so the eighteen stored hashes all differ.
Verification goes through the real `BCrypt.verifyer()` path (F1.1, S-38).

| Username | Password | Name | Role | Courses |
|---|---|---|---|---|
| `dana.cohen` | `demo123` | Dana Cohen | TEACHER | Algebra 11, Calculus 12 — teaches |
| `rina.barak` | `demo123` | Rina Barak | COORDINATOR \* | none; coordinates Mathematics 10 without teaching. The **pure-coordinator** login (roster decision, 2026-08-20); the dual-hat case is `michal.sharon` |
| `maya.levi` | `demo123` | Maya Levi | STUDENT | Algebra 11, Java Programming 21, Databases 22 — enrolled |
| `noam.peretz` | `demo123` | Noam Peretz | STUDENT | Calculus 12, Java Programming 21 — enrolled |
| `principal.avia` | `demo123` | Avia Shalev | PRINCIPAL | none (school-wide read-only, S-7) |

\* **`COORDINATOR` is a wire role, not a stored one.** `users.role` is
`ENUM('STUDENT','TEACHER','PRINCIPAL')` and has no COORDINATOR member. `rina.barak` is stored as
a **TEACHER** with a row in `coordinators` for subject `10`, and the role is derived at login:
stored TEACHER plus a coordinators row becomes wire `Role.COORDINATOR` (ARCHITECTURE §5,
round-2). Coordinator-ness is per-subject state, so it can never drift from a stored role that
disagrees with it. If you query the database directly during a demo, expect to see `TEACHER`.

**The seed carries both shapes of coordinator on purpose.** `rina.barak` is the *pure*
coordinator: she coordinates Mathematics and teaches nothing, so she has zero `course_teachers`
rows. `michal.sharon` is the *dual-hat* case: she teaches Databases 22 and coordinates Computer
Science 20. Keeping both is what makes the derived role provable, because coordinator-ness lives
only in the `coordinators` table. If every coordinator also taught a course, an implementation
that derived the role from `course_teachers` by mistake would look correct. Rina is the account
that catches it, which is worth knowing if the login role is ever questioned in review.

**Names are stored and displayed in English** (language ruling, 2026-08-24: everything in
English — UI copy and seed content both; the Hebrew display names were translated in UI wave 1
and this file's earlier claim to the contrary was corrected the same day). Course codes are the
2-character `courses.code2` values from ARCHITECTURE §5 (Mathematics 10 → Algebra 11,
Calculus 12; Computer Science 20 → Java 21, Databases 22).

## The full roster — eighteen accounts, all `demo123`

The five above are the ones the demo script names. The complete seeded roster
(authoritative order = `UsersSection`; content story in `docs/seed/SEED_CONTENT.md` §3):

| Username | Name | Role | Note |
|---|---|---|---|
| `principal.avia` | Avia Shalev | PRINCIPAL | school-wide read-only (S-7) |
| `dana.cohen` | Dana Cohen | TEACHER | Algebra 11 + Calculus 12; Calculus solo |
| `rina.barak` | Rina Barak | TEACHER → wire COORDINATOR | the PURE coordinator: Mathematics 10, teaches nothing |
| `avi.mizrahi` | Avi Mizrahi | TEACHER | Java 21 co-teacher; the grading-demo teacher |
| `tamar.shani` | Tamar Shani | TEACHER | Java 21 co-teacher (the two-teachers-one-course case) |
| `michal.sharon` | Michal Sharon | TEACHER → wire COORDINATOR | dual-hat: teaches Databases 22, coordinates CS 20 |
| `noa.friedman` | Noa Friedman | STUDENT | |
| `itay.regev` | Itay Regev | STUDENT | |
| `shira.dahan` | Shira Dahan | STUDENT | |
| `omer.katz` | Omer Katz | STUDENT | the TIMED-OUT attempt (S-19); four questions "Not answered" |
| `maya.levi` | Maya Levi | STUDENT | the demo student: two exams sat, one published (C-3 in one screen) |
| `noam.peretz` | Noam Peretz | STUDENT | enrolled in neither Algebra nor Databases; the 9.4 outsider probe |
| `yael.azulay` | Yael Azulay | STUDENT | the manual-override grade with a written justification (T-8.3) |
| `daniel.shapira` | Daniel Shapira | STUDENT | |
| `lior.gabay` | Lior Gabay | STUDENT | |
| `tal.harari` | Tal Harari | STUDENT | |
| `roni.malka` | Roni Malka | STUDENT | |
| `eitan.solomon` | Eitan Solomon | STUDENT | |

The unannotated students exist so class rosters, approval queues and grade distributions look
like a school rather than a fixture. Notable ones, expanded:

- `michal.sharon` — teaches Databases 22 **and** coordinates Computer Science 20, so she
  approves the Java exams. The dual-hat counterpart to `rina.barak`.
- `avi.mizrahi` and `tamar.shani` — co-teachers on Java 21, now the only co-taught course, which
  is what keeps the "two teachers, one course" case demonstrable.
- `yael.azulay` — the student whose grade carries a manual override with a written
  justification (T-8.3, S-23).
- `omer.katz` — the student whose attempt timed out rather than being submitted (S-19).

## What the login path does and does not do

- **The throttle is live**: 5 failed attempts on a username → 30-second lockout, keyed by the
  normalised username. Restarting the server clears it, since the counters are in memory.
- **One session per user** (F1.3): signing `dana.cohen` in on a second client is refused with
  "This account is already signed in elsewhere." Closing or killing the first client frees the
  session immediately, as does Sign out.
- Roles drive the shell only. Server-side permission guards read the role from the session on
  **every** request; nothing trusts the client's copy.

## Demo tips

- **Two-machine duplicate-login demo:** sign in as `dana.cohen` on machine A, try the same on
  machine B → the exact F1.3 message appears inline under the password field. Sign out on A,
  retry on B → it works.
- **Throttle demo:** type a wrong password 5 times for `maya.levi`; the 6th attempt, even with
  the right password, answers "Too many attempts, try again shortly." for 30 seconds.
- **Role tour:** `maya.levi` (student rail: Dashboard / Take Exam / My Grades / Study Bot /
  Settings) → `dana.cohen` (teacher rail, no Approvals) → `rina.barak` (same plus Approvals) →
  `principal.avia` (Dashboard / Data / Reports / Settings, nothing mutating).
- **Coordinator demo:** `rina.barak` sees the Mathematics approval queue only. The Calculus exam
  `101201` is seeded PENDING and waiting for her.
