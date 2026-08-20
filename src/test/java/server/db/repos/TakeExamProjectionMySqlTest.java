package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/** The take-exam query against the real Flyway schema. */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class TakeExamProjectionMySqlTest extends TakeExamProjectionContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
