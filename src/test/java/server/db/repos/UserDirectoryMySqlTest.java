package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The user directory contract on the real Flyway schema.
 *
 * <p>Case-insensitive username matching is the reason this leaf matters: production collation
 * is {@code utf8mb4_unicode_ci} and H2 does not reproduce it, so the two engines can disagree
 * about exactly the comparison the login throttle depends on.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class UserDirectoryMySqlTest extends UserDirectoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
