package server.features.grading;

import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

/**
 * Acceptance 8.4's walk on the real Flyway schema.
 *
 * <p>The leaf that matters for this case, because the fact being proved is about a stored
 * column: {@code grades.teacher_comment} is a MySQL {@code TEXT} carrying Hebrew under
 * {@code utf8mb4}, and "the comment survives the round trip" is a claim about that column
 * rather than about an entity in memory.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class TeacherCommentFlowMySqlTest extends TeacherCommentFlowContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }
}
