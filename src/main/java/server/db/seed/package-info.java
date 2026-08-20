/**
 * The demo dataset loader (E2.15, E2.17).
 *
 * <p>Loads {@code docs/seed/SEED_CONTENT.md}: 18 users, 40 questions, 6 exams in mixed
 * states, 4 executions, bot content and notifications. The document owns the content
 * (Member B); this package owns getting it into the database, which the document is
 * deliberately written to make a transcription job rather than a design job.
 *
 * <h2>Java rather than a versioned SQL file</h2>
 *
 * <p>PRD NFR-17 originally said "via versioned SQL". This is a Java loader over the JPA
 * entities instead, approved by the lead on 2026-08-20 with NFR-17 reworded to match. Three
 * reasons, each sufficient on its own:
 *
 * <ol>
 *   <li><b>The seed is optional per boot.</b> F14.2 has the server <em>offer</em> to load it.
 *       A {@code V8__seed.sql} migration would run on every {@code migrate} and could not be
 *       declined, skipped or repeated.</li>
 *   <li><b>The execution windows are relative.</b> Seed §9 specifies {@code T-14d 09:00},
 *       {@code T+0 14:00} and a window that has to straddle "now". There is no date
 *       arithmetic that means the same thing on both H2 and MySQL, and the two-engine test
 *       symmetry is what proves the seed loads against the real schema.</li>
 *   <li><b>Passwords are hashed at insert.</b> Seed §3 keeps the plaintext {@code demo123} in
 *       the document and requires BCrypt at load, so the loader has to run code.</li>
 * </ol>
 *
 * <h2>Idempotency is per row, by natural key</h2>
 *
 * <p>{@link server.db.seed.SeedMode#LOAD_IF_MISSING} asks each section to insert only what is
 * absent, keyed on {@code username}, {@code display_id5}, {@code display_id6}, {@code code2}
 * or an execution {@code code}. It never tests whether the database as a whole is empty,
 * which is what lets a member add the seed rows their own feature needs (TEAM_SPLIT §3 rule
 * 6) without either load claiming ownership of the whole database.
 *
 * <p><b>The document's numeric ids are not database ids.</b> {@code SEED_CONTENT.md} numbers
 * its users 1 to 18 and cross-references them as "2 dana.cohen"; that numbering is internal
 * to the document, and nothing outside it depends on those values.
 * {@code docs/ACCEPTANCE_TESTS.md} and {@code docs/DEMO_ACCOUNTS.md} both identify people by
 * username and exams by display id. Honouring the numbers is also not possible and not safe:
 * the entities are {@code @GeneratedValue(IDENTITY)}, and {@code DELETE} does not reset
 * {@code AUTO_INCREMENT}, so a second reseed would renumber the same eighteen people 19 to
 * 36. See {@link server.db.seed.SeedSection} for the rule this imposes on every section.
 *
 * <h2>Reseeding is destructive, and it is the pre-demo step</h2>
 *
 * <p>{@link server.db.seed.SeedMode#RESEED} empties every table in
 * {@link server.db.seed.WipeOrder#TABLES} and reloads, which re-resolves every relative
 * timestamp against the current clock. That is how execution 3 stays "today" and execution 4
 * stays live. It always asks first, through
 * {@link server.db.seed.Confirmation}, and the wipe and the reload share one transaction so a
 * failure cannot leave the database empty.
 */
package server.db.seed;
