package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dataset contract against the real Flyway schema, plus what only MySQL can prove.
 *
 * <p>H2 generates no CHECK constraints, no foreign keys and not the production collation, so
 * the seed passing there says the loader is coherent, not that the database would accept it.
 * The tests below are in this leaf rather than the contract by the convention Naji confirmed:
 * anything that depends on real constraints or on {@code utf8mb4_unicode_ci} is written here
 * and never on H2, where it would pass whatever the schema said.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class SeedDatasetMySqlTest extends SeedDatasetContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("Hebrew and RTL text round-trips through the real utf8mb4 columns")
    void hebrewSurvivesTheRoundTrip() {
        // The seed is half Hebrew on purpose: Algebra and Calculus are Hebrew so RTL is
        // proven, Java and Databases are English so code does not read reversed. Every screen
        // in the demo shows both.
        String courseName = inTx(session -> session.createQuery(
                        "select c.name from Course c where c.code = '12'", String.class)
                .getSingleResult());
        String questionText = inTx(session -> session.createQuery("""
                        select qv.text from QuestionVersion qv, Question q
                        where q.id = qv.questionId and q.displayId = '11004' and qv.versionNo = 1
                        """, String.class).getSingleResult());

        assertThat(courseName).isEqualTo("חשבון דיפרנציאלי ואינטגרלי");
        assertThat(questionText).contains("סכום הספרות של מספר דו-ספרתי");
        assertThat(questionText).hasSize(questionText.length());
    }

    @Test
    @DisplayName("the composite foreign key really is what holds the pin in place")
    void theCompositeForeignKeyPolicesThePin() {
        // The denormalised question_id in exam_version_questions cannot disagree with the
        // question_version it names, because fk_evq_question_version references the pair.
        // Rewriting the copy to a different question must be refused by the database itself,
        // which is a claim only MySQL can settle: H2 creates no foreign keys at all.
        //
        // 11003 specifically, and this choice is the whole test. The obvious pick, 11001,
        // is already in both exam-1 versions, so the UPDATE trips
        // uq_exam_version_questions_question first and the composite FK is never reached:
        // the test would pass while proving nothing about the constraint it names. 11003 is
        // in the bank and in no exam version, so the unique constraint stays satisfied and
        // the foreign key is the only thing left that can refuse.
        long otherQuestionId = inTx(session -> session.createQuery(
                        "select q.id from Question q where q.displayId = '11003'", Long.class)
                .getSingleResult());

        assertThatThrownBy(() -> runInTx(session -> session.createNativeMutationQuery("""
                        UPDATE exam_version_questions evq
                        SET evq.question_id = :other
                        WHERE evq.question_version_id = (
                            SELECT qv.id FROM question_versions qv
                            JOIN questions q ON q.id = qv.question_id
                            WHERE q.display_id5 = '11005' AND qv.version_no = 1)
                        """).setParameter("other", otherQuestionId).executeUpdate()))
                .hasMessageContaining("fk_evq_question_version");
    }

    @Test
    @DisplayName("execution codes and usernames are compared case-sensitively where it matters")
    void collationBehavesAsProduction() {
        // utf8mb4_unicode_ci is case-insensitive, which is why username lookups lowercase
        // both sides rather than relying on the collation. Asserted here and never on H2,
        // whose default collation differs.
        List<String> found = inTx(session -> session.createQuery(
                        "select u.username from User u where u.username = 'DANA.COHEN'",
                        String.class).getResultList());

        assertThat(found)
                .as("the production collation is case-insensitive, so this finds the row")
                .containsExactly("dana.cohen");
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertThatThrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(callable);
    }
}
