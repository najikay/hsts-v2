package server.features.exam;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The production data seam on the real Flyway schema, with every constraint live.
 *
 * <p>This is the configuration the running server actually uses, so it is the one that
 * proves the take-exam data path end to end rather than only its shape.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class JpaExamStoreMySqlTest extends JpaExamStoreContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
