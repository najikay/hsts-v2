package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/** The bank contract on the real Flyway schema. */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class BankRepositoryMySqlTest extends BankRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
