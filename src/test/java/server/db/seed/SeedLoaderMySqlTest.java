package server.db.seed;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The loader contract on the real Flyway schema.
 *
 * <p>The one that counts for the reseed tests. H2 generates no foreign keys, so a wipe in the
 * wrong order succeeds there and only MySQL refuses it; and the rollback test is only
 * meaningful against an engine whose constraints were actually enforced during the delete.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class SeedLoaderMySqlTest extends SeedLoaderContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
