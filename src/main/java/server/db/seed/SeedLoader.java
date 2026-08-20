package server.db.seed;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.Transactions;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Loads the demo dataset described by {@code docs/seed/SEED_CONTENT.md} (E2.15).
 *
 * <h2>Two modes, and which one to use before a demo</h2>
 *
 * <p>{@link SeedMode#LOAD_IF_MISSING} inserts only rows that are absent, identified by their
 * stable natural key, so it is safe to run repeatedly and safe to run against a database
 * somebody is using. It is the right default and the right thing for first boot.
 *
 * <p><b>{@link SeedMode#RESEED} is the standard step before a demo</b>, and it is worth
 * knowing why rather than discovering it during one. The seed's execution windows are
 * relative to load time: execution 3 opens "today at 14:00" and execution 4 is live from an
 * hour ago to an hour from now. Those are the two rows the release demo and the take-exam
 * demo need. {@code LOAD_IF_MISSING} will not refresh them, because the rows already exist
 * and are therefore skipped, so a machine seeded a fortnight ago presents a "live" execution
 * that closed two weeks earlier. Reseeding empties the database and re-resolves every
 * timestamp against the current clock, which is what keeps "today" meaning today.
 *
 * <p>Reseeding is destructive and is never reached without a {@link Confirmation} answering
 * yes. The command line and the E19.6 console button each supply their own; the prompt they
 * show comes from here so they cannot describe the same action differently.
 *
 * <h2>One transaction</h2>
 *
 * <p>The wipe and every section run in a single transaction. A section that fails rolls back
 * the whole load, including the delete that preceded it, so a failed reseed leaves the
 * operator with the data they had rather than an empty database. That matters most on the one
 * machine where it would be worst: the demo laptop, minutes before a defense.
 *
 * <h2>Construction</h2>
 *
 * <p>The clock and the section list are constructor arguments rather than statics. The clock
 * is what makes the resolved timestamps assertable to the millisecond instead of within a
 * tolerance, and the section list is what lets the loader's own behaviour, the modes, the
 * confirmation and the summary, be tested without a single row of real seed content. Both
 * seams exist partly for tests; {@link #standard(SessionFactory)} is the production wiring.
 */
public final class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    /** What the operator is asked before anything is deleted. */
    static final String RESEED_PROMPT =
            "Reseed will DELETE every row in all 20 HSTS tables and load the demo dataset "
                    + "again. Any data entered since the last seed will be lost. Continue?";

    private final SessionFactory factory;
    private final Clock clock;
    private final List<SeedSection> sections;

    /**
     * @param factory  the database to load into
     * @param clock    the clock every relative timestamp is resolved against
     * @param sections the dataset, in dependency order
     */
    public SeedLoader(SessionFactory factory, Clock clock, List<SeedSection> sections) {
        this.factory = factory;
        this.clock = clock;
        this.sections = List.copyOf(sections);
    }

    /**
     * The production loader: the real dataset, resolved against the system UTC clock.
     *
     * @param factory the database to load into
     * @return a loader wired with every section of {@code SEED_CONTENT.md}
     */
    public static SeedLoader standard(SessionFactory factory) {
        return new SeedLoader(factory, Clock.systemUTC(), SeedDataset.sections());
    }

    /**
     * Loads the dataset.
     *
     * @param mode         whether to fill gaps or to empty the database and start over
     * @param confirmation asked before anything is deleted; ignored by
     *                     {@link SeedMode#LOAD_IF_MISSING}, which destroys nothing
     * @return what happened, per table, ready to show to whoever asked
     */
    public SeedSummary load(SeedMode mode, Confirmation confirmation) {
        if (mode == SeedMode.RESEED && !confirmation.confirm(RESEED_PROMPT)) {
            log.info("Reseed cancelled at the confirmation prompt, nothing was changed.");
            return SeedSummary.nothing(SeedOutcome.CANCELLED);
        }

        Map<String, Integer> inserted = Transactions.inTx(factory, session -> {
            if (mode == SeedMode.RESEED) {
                WipeOrder.wipe(session);
            }
            SeedContext context = new SeedContext(session, new SeedTimes(clock));
            sections.forEach(section -> section.load(context));
            return context.inserted();
        });

        SeedSummary summary = new SeedSummary(outcomeOf(mode, inserted), inserted);
        log.info("Seed finished: {}, {} rows inserted.", summary.outcome(), summary.totalRows());
        return summary;
    }

    private static SeedOutcome outcomeOf(SeedMode mode, Map<String, Integer> inserted) {
        if (mode == SeedMode.RESEED) {
            return SeedOutcome.RESEEDED;
        }
        boolean anything = inserted.values().stream().anyMatch(rows -> rows > 0);
        return anything ? SeedOutcome.LOADED : SeedOutcome.UNCHANGED;
    }
}
