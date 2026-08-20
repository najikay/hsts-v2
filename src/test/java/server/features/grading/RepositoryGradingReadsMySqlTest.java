package server.features.grading;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * The grading-reads contract on the real Flyway schema.
 *
 * <p>This is the leaf that matters most for E12.1: the pinned-version guarantee depends on the
 * composite foreign key from {@code exam_version_questions} to {@code question_versions}, which
 * only the production schema actually has.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class RepositoryGradingReadsMySqlTest extends RepositoryGradingReadsContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
