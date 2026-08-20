package server.db.seed;

/**
 * What the loader should do about data that is already there (E2.15).
 *
 * <p>E2.15 requires the loader to be idempotent. There are two useful readings of that and
 * the seed needs both, so the choice is explicit at the call site rather than inferred.
 */
public enum SeedMode {

    /**
     * Insert only what is missing, identified by each row's stable natural key.
     *
     * <p>The default, and the safe one: running it twice inserts nothing the second time,
     * running it against a partially seeded database completes it, and running it against a
     * database somebody has been using does not touch their work. This is also what makes
     * TEAM_SPLIT §3 rule 6 workable, where each member adds the seed rows their own feature
     * needs: their rows and mine coexist because neither load claims the whole database.
     */
    LOAD_IF_MISSING,

    /**
     * Delete every row in {@link WipeOrder#TABLES}, then load the whole dataset fresh.
     *
     * <p>Destructive, and therefore always confirmed through a {@link Confirmation} first.
     *
     * <p><b>This is the standard step before a demo.</b> The seed's execution windows are
     * relative to load time: execution 3 is "today at 14:00" and execution 4 is live right
     * now. {@link #LOAD_IF_MISSING} will not refresh them, because the rows already exist and
     * are therefore skipped, so a database seeded a fortnight ago shows a release demo whose
     * window closed two weeks back. Reseeding re-resolves every timestamp against the current
     * clock, which is what keeps "today" meaning today at the defense.
     */
    RESEED
}
