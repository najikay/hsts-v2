package server.db.seed;

/** What a seed run actually did, for the operator who asked for it (E2.15). */
public enum SeedOutcome {

    /** Rows were missing and have been inserted. */
    LOADED,

    /**
     * Everything was already there and nothing was inserted.
     *
     * <p>Distinct from {@link #LOADED} with a count of zero on purpose: "nothing to do" and
     * "did something" are different answers for the operator, and collapsing them would make
     * a loader that silently inserts nothing because of a bug look identical to a loader that
     * correctly found the data present.
     */
    UNCHANGED,

    /** The database was emptied and the whole dataset reloaded, with timestamps re-resolved. */
    RESEEDED,

    /** A reseed was declined at the confirmation prompt. Nothing was deleted or inserted. */
    CANCELLED
}
