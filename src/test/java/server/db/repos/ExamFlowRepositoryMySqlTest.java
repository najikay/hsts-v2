package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.projections.ExecutionContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The take-exam repository contract on the real Flyway schema.
 *
 * <p>Two things only exist here. Execution codes are compared case-insensitively by
 * production collation (C-1) and H2 does not reproduce that, so the entry lookup a student
 * actually uses is established in this leaf per {@code H2Support}'s rule. And the foreign
 * keys and CHECK constraints are live, so an answer row really cannot point at a question
 * that is not there.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ExamFlowRepositoryMySqlTest extends ExamFlowRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("a student may type her code in any case (C-1)")
    void codeLookupIsCaseInsensitive() {
        // H2's MySQL mode does not reproduce utf8mb4_unicode_ci, so this belongs here: it
        // is the first thing a student does and the demo types it in lower case.
        liveExecution("Ab7Q");

        List<ExecutionContext> upper = inTx(session ->
                new ExecutionRepository().findContextsByCode(session, "AB7Q"));
        List<ExecutionContext> lower = inTx(session ->
                new ExecutionRepository().findContextsByCode(session, "ab7q"));

        assertThat(upper).hasSize(1);
        assertThat(lower).hasSize(1);
        assertThat(lower.get(0).executionId()).isEqualTo(upper.get(0).executionId());
    }
}
