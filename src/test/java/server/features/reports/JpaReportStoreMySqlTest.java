package server.features.reports;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The E15 production data seam on the real Flyway schema.
 *
 * <p>Two steps between the seed and the principal's screen exist only on this engine. The frozen
 * statistics make a full round trip through the JSON converter and a real {@code MEDIUMTEXT}
 * column, and the course code is compared as {@code CHAR(2)} under a PAD SPACE collation, which
 * is what makes the stripping in {@code ByCourseStrategy} worth having rather than decorative.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaReportStoreMySqlTest extends JpaReportStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
