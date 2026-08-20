package server.db.repos;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The fixture contract on the real Flyway schema.
 *
 * <p>This is where the wipe-order test earns its keep: H2 generates no foreign keys at all,
 * so a wrong order passes there and fails only here.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class RepositoryFixtureMySqlTest extends RepositoryFixtureContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
