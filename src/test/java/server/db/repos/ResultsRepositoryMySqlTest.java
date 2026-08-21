package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The E14 results reads on the real Flyway schema.
 *
 * <p>Two of them only mean anything here. The frozen statistics travel through the JSON
 * converter into a real {@code MEDIUMTEXT} column and back, which H2 stores differently; and
 * the {@code order by} on Hebrew student names runs under production collation, which is what
 * a teacher's table is actually sorted by.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ResultsRepositoryMySqlTest extends ResultsRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
