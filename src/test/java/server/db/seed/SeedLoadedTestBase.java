package server.db.seed;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import server.db.TestDatabase;
import server.db.Transactions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A database with the seed loaded into it, once per test class (E2.15).
 *
 * <p>Shared by {@code SeedDatasetContract}, which checks properties the document leans on, and
 * {@code SeedLoadedDbContract}, which checks the loaded rows against the document itself.
 *
 * <h2>Once per class, and the reason is measured</h2>
 *
 * <p>An earlier version inherited {@code RepositoryTestBase}, which wipes and reseeds before
 * every test. That loaded the dataset once per test method and took <b>290 seconds</b> against
 * real MySQL for seventeen tests, against 31 on H2, because each run pays for eighteen BCrypt
 * hashes plus roughly 170 inserts and their idempotency lookups. Loading once per class brought
 * the same suite to under 8 seconds. Every test built on this base must therefore be a read, or
 * must leave the database as it found it.
 *
 * <p>It also cannot use that base for a second reason: the fixture there contains users named
 * {@code dana.cohen} and {@code rina.barak}, which are the real seed's usernames, so every
 * section would skip its rows and every count would be wrong.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SeedLoadedTestBase {

    /**
     * Fixed so every derived timestamp is assertable to the millisecond.
     *
     * <p><b>An afternoon on purpose, and that is now a property rather than an accident.</b> This
     * value used to sit <em>inside</em> execution 3's 14:00-16:00 window, so the canonical seed test
     * loaded a "scheduled for later today" sitting that had already opened and no assertion
     * noticed - B-10. The windows the demo needs are now resolved from this instant rather than from
     * its date (see {@code ExecutionsSection}), so the fixture is correct at any hour, and
     * {@code SeedLoadedDbContract} asserts the direction rather than only the duration. Moving this
     * anchor to a morning would have hidden the defect instead of fixing it; it stays in the
     * afternoon so the guard is exercised.
     */
    static final Instant ANCHOR = Instant.parse("2026-08-20T15:30:00Z");

    private TestDatabase database;
    private SeedSummary summary;

    /** @return the engine this leaf binds to */
    protected abstract TestDatabase openDatabase();

    @BeforeAll
    final void loadTheDatasetOnce() {
        database = openDatabase();
        WipeOrder.wipe(factory());
        summary = new SeedLoader(factory(), Clock.fixed(ANCHOR, ZoneOffset.UTC),
                SeedDataset.sections())
                .load(SeedMode.LOAD_IF_MISSING, Confirmation.refused());
    }

    @AfterAll
    final void closeDatabaseOnce() {
        if (database != null) {
            database.close();
        }
    }

    /** @return what the load reported */
    protected final SeedSummary summary() {
        return summary;
    }

    /** @return the factory for the database this test class is bound to */
    protected final SessionFactory factory() {
        return database.factory();
    }

    /** Runs work in a transaction on this test's database. */
    protected final <T> T inTx(Function<Session, T> work) {
        return Transactions.inTx(factory(), work);
    }

    /** Runs work in a transaction on this test's database, returning nothing. */
    protected final void runInTx(Consumer<Session> work) {
        Transactions.runInTx(factory(), work);
    }
}
