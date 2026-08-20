package server.db.seed;

import org.hibernate.Session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link SeedSection} is given, and where it records what it did (E2.15).
 *
 * <p>One instance per load. It carries the session every section writes through, the single
 * time anchor they all resolve against, and the running per-table tally that becomes the
 * {@link SeedSummary} the console shows.
 *
 * <p>Sections record their own inserts rather than the loader counting rows afterwards,
 * because under {@link SeedMode#LOAD_IF_MISSING} the interesting number is "how many rows did
 * this run add", which a count of the table cannot tell you.
 */
public final class SeedContext {

    private final Session session;
    private final SeedTimes times;
    private final Map<String, Integer> inserted = new LinkedHashMap<>();

    SeedContext(Session session, SeedTimes times) {
        this.session = session;
        this.times = times;
    }

    /** @return the session every section writes through, inside the loader's transaction */
    public Session session() {
        return session;
    }

    /** @return the shared time anchor; see {@link SeedTimes} for why it is shared */
    public SeedTimes times() {
        return times;
    }

    /**
     * Records one inserted row.
     *
     * @param table the table it went into
     */
    public void recordInsert(String table) {
        recordInserts(table, 1);
    }

    /**
     * Records several inserted rows.
     *
     * @param table the table they went into
     * @param rows  how many; zero is allowed and registers the table with a count of zero,
     *              which is how a section says "I ran and found everything already present"
     *              rather than saying nothing at all
     */
    public void recordInserts(String table, int rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must not be negative, got " + rows);
        }
        inserted.merge(table, rows, Integer::sum);
    }

    /** @return rows inserted per table so far, in the order tables were first written to */
    Map<String, Integer> inserted() {
        return new LinkedHashMap<>(inserted);
    }
}
