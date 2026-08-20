package server.db;

import org.hibernate.SessionFactory;
import server.db.seed.WipeOrder;

/**
 * Emptying a test database, in the one order that works (E2.13).
 *
 * <p>Two families of database tests need this: the repository suites through
 * {@link RepositoryTestBase}, and {@code server.features.notify.JpaNotificationStoreContract},
 * which runs against the same shared MySQL schema and cannot simply delete {@code
 * notifications} and hope whatever the previous test class left behind is compatible.
 *
 * <h2>The order lives in main code now</h2>
 *
 * <p>{@link WipeOrder} is the canonical list, and this class delegates to it. It moved there
 * in E2 PR 3 for a reason this class cannot solve: the seed loader's {@code --reseed} path
 * needs exactly the same order, and production code cannot import test code. Keeping a copy
 * here would have made the loader and the tests two sources of truth for one fact, drifting
 * the first time a migration adds a table.
 *
 * <p>This class stays rather than being deleted so its two callers keep a stable name, and so
 * the test suite has somewhere to put wipe behaviour that is genuinely test-only.
 */
public final class TestSchema {

    private TestSchema() {
        // static helper - no instances
    }

    /**
     * Deletes every row in every table, children first.
     *
     * @param factory the database to empty
     */
    public static void wipe(SessionFactory factory) {
        WipeOrder.wipe(factory);
    }
}
