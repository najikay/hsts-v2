package server.db.repos;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.TestDatabase;
import server.db.TestDatabases;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The take-exam query on H2, plus the assertion that gives E2.12 its strongest form.
 *
 * <p>{@link TakeExamProjectionShapeTest} proves the projection has nowhere to put a correct
 * answer. That already satisfies §5. This class proves the stronger claim the repository
 * javadoc makes: {@code correct_answer} is not merely dropped from the result, it is never
 * <b>fetched</b> — it does not travel out of the database at all, so it cannot be caught in a
 * heap dump, a query log, or a debugger session during the exam.
 *
 * <p>A {@code StatementInspector} records the SQL Hibernate really emits, which is the only
 * way to check that: reading the HQL proves nothing about what the translator does with it.
 */
class TakeExamSqlH2Test extends TakeExamProjectionContract {

    private final List<String> emitted = new CopyOnWriteArrayList<>();

    private final QuestionRepository questions = new QuestionRepository();

    @Override
    protected TestDatabase openDatabase() {
        StatementInspector recorder = sql -> {
            emitted.add(sql);
            return sql;
        };
        return TestDatabases.h2(Map.of("hibernate.session_factory.statement_inspector", recorder));
    }

    @Test
    @DisplayName("the emitted SQL never names correct_answer")
    void correctAnswerIsNeverFetched() {
        long examVersionId = composeExam();

        emitted.clear();
        inTx(session -> questions.findForTakeExam(session, examVersionId));

        assertThat(emitted).as("the query should have run").isNotEmpty();
        assertThat(emitted)
                .as("no statement on the take-exam path may read the correct answer")
                .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("correct_answer"));
    }

    @Test
    @DisplayName("that check can fail - a query that does read the column is caught")
    void theSqlCheckHasTeeth() {
        // Without this, "no SQL mentioned correct_answer" would also be true of a recorder
        // that never captured anything, or a substring that no longer matches the column
        // name. Reading a question version deliberately must trip the same assertion.
        emitted.clear();
        inTx(session -> questions.findLatestVersionForAuthoring(session, 1L));

        assertThat(emitted)
                .as("the authoring query does read the correct answer, so the check must see it")
                .anyMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("correct_answer"));
    }

}
