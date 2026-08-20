package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The execution contract on the real Flyway schema.
 *
 * <p>Execution codes are compared case-insensitively by production collation (C-1), which H2
 * does not reproduce, so this leaf is where that behaviour is actually established.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ExecutionRepositoryMySqlTest extends ExecutionRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
