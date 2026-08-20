package server.db.seed;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * Document-versus-database against the real Flyway schema.
 *
 * <p>Worth running on both engines even though the comparison is engine-independent: this is
 * the leaf where the loaded text has been through utf8mb4 columns and the production collation,
 * so it proves the Hebrew stems and options survive the round trip byte for byte rather than
 * merely surviving H2.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class SeedLoadedDbMySqlTest extends SeedLoadedDbContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
