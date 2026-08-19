# Flyway migrations — conventions

Applied by `server.db.DbBootstrap` on server start and by
`server.db.FlywayCleanRunTest` against a throwaway schema. Flyway only reads `.sql`
files here, so this README is inert.

## Naming

`V<n>__<area>.sql` — integer version, double underscore, lower-case area name.
One migration per schema area, never per table:

| File | Area |
|---|---|
| `V1__core.sql` | subjects, courses, users, memberships, coordinators |
| `V2__bank.sql` | question bank + immutable versions |
| `V3__exams.sql` | exams, exam versions, version↔question links |
| `V4__executions.sql` | executions, attempts, answers |
| `V5__grading.sql` | grades |
| `V6__bot.sql` | bots, sources, sessions, analytics messages |
| `V7__notifications.sql` | notifications |

The next migration is `V8__<area>.sql`. **Never edit a migration that has been
merged** — Flyway records a checksum per applied file and `validateOnMigrate`
will fail on any machine that already ran it. Correct a merged migration by
adding the next version.

## Rules

- Every table: `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`.
  Hebrew/RTL content must round-trip (NFR); the collation also gives
  case-insensitive comparison, which the execution-code lookup (C-1) relies on.
- Name every constraint: `pk_<table>`, `uq_<table>_<what>`, `fk_<child>_<parent>`,
  `ck_<table>_<what>`, `ix_<table>_<cols>`. Anonymous constraints produce
  unreadable violation messages and are impossible to drop cleanly later.
- Every `FOREIGN KEY` states `ON DELETE` explicitly — `CASCADE` when the child is
  meaningless without the parent, `RESTRICT` when the row is history that must not
  vanish (users, question versions).
- Header comment on every file naming the `ARCHITECTURE.md` §5 lines and the
  PRD/ADR ids it implements. The schema is a frozen contract; the header is how a
  reviewer checks a file against it without reading the whole doc.
- DDL only. Seed data belongs to the loader (E2.15), never to a migration — the
  seed must stay idempotent and re-runnable, which a versioned migration is not.
- Cross-row invariants (points summing to 100) belong in the service layer plus a
  test, per §5. A `CHECK` cannot span rows.

## Adding a migration

1. Write `V8__<area>.sql` following the rules above.
2. Add the new tables to `FlywayCleanRunTest.EXPECTED_TABLES` — the clean-run test
   asserts the exact table set, so a missing entry fails loudly rather than silently.
3. Run `mvnw verify` with MySQL reachable. The test proves both a clean run on an
   empty schema and a stepwise run on top of the previous version.
