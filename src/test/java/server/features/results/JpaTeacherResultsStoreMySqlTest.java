package server.features.results;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The E14 production data seam on the real Flyway schema.
 *
 * <p>This is the run where the frozen statistics make a full round trip through the JSON
 * converter and a real {@code MEDIUMTEXT} column before they are served, which is the one step
 * between the number the seed wrote and the number a teacher reads that H2 does not reproduce.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaTeacherResultsStoreMySqlTest extends JpaTeacherResultsStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
