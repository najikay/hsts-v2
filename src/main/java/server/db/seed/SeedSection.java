package server.db.seed;

/**
 * One section of {@code docs/seed/SEED_CONTENT.md}, loaded (E2.15).
 *
 * <p>The dataset is split the way the document is, one implementation per numbered section,
 * so a reviewer can hold the document beside the code and check them off. Sections run in
 * dependency order: a section may rely on everything before it having been loaded, and on
 * nothing after it.
 *
 * <h2>Every section is responsible for its own idempotency</h2>
 *
 * <p>The loader does not check whether the database is empty before running. Each section
 * looks up its own rows by their <b>stable natural key</b> and inserts only what is missing,
 * which is what makes {@link SeedMode#LOAD_IF_MISSING} safe to run against a database
 * somebody is already using.
 *
 * <p><b>Natural key, never the numeric primary key.</b> The seed document numbers its users
 * 1 to 18 and cross-references them as "2 dana.cohen", but those numbers are internal to the
 * document: {@code docs/ACCEPTANCE_TESTS.md} and {@code docs/DEMO_ACCOUNTS.md} identify people
 * by username and exams by display id, never by row id. Two independent reasons the loader
 * must not try to honour them anyway: every entity here is
 * {@code @GeneratedValue(strategy = IDENTITY)}, so the id is the database's to assign; and
 * {@code DELETE} does not reset {@code AUTO_INCREMENT}, so the second reseed on a demo machine
 * would number the same eighteen people 19 to 36. Anything that had pinned "dana.cohen is id
 * 2" would then be wrong in a way that first appears at the defense. Resolve by
 * {@code username}, {@code display_id5}, {@code display_id6}, {@code code2} and execution
 * {@code code}, all of which carry unique constraints and are genuinely stable.
 */
public interface SeedSection {

    /**
     * @return the section's name for diagnostics, ideally matching the heading in
     *         {@code SEED_CONTENT.md} it implements
     */
    String name();

    /**
     * Inserts whatever this section owns that is not already present.
     *
     * <p>Runs inside the loader's single transaction: throwing rolls the entire seed back,
     * including the wipe that may have preceded it.
     *
     * @param context the session to write through, the shared time anchor, and where to
     *                record what was inserted
     */
    void load(SeedContext context);
}
