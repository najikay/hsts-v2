package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Difficulty;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
import server.db.entities.ExamVersionStatus;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.TakeExamQuestion;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The take-exam query (E2.12) against a real database. */
abstract class TakeExamProjectionContract extends RepositoryTestBase {

    private final QuestionRepository questions = new QuestionRepository();

    protected static final Instant WHEN = Instant.parse("2026-08-20T09:00:00Z");

    @Test
    @DisplayName("the questions come back in exam order with their points")
    void questionsComeBackInOrder() {
        long examVersionId = composeExam();

        List<TakeExamQuestion> asked = inTx(session -> questions.findForTakeExam(session, examVersionId));

        assertThat(asked).hasSize(3);
        assertThat(asked).extracting(TakeExamQuestion::ordinal).containsExactly(1, 2, 3);
        assertThat(asked).extracting(TakeExamQuestion::displayId)
                .containsExactly("11001", "11002", "11003");
        assertThat(asked).extracting(TakeExamQuestion::points).containsExactly(40, 40, 20);
    }

    @Test
    @DisplayName("the wording and the four options survive, Hebrew included")
    void questionContentSurvives() {
        long examVersionId = composeExam();

        TakeExamQuestion first = inTx(session -> questions.findForTakeExam(session, examVersionId)).get(0);

        assertThat(first.text()).isEqualTo("מהם שורשי המשוואה 1?");
        assertThat(first.answer1()).isEqualTo("1, 6");
        assertThat(first.answer2()).isEqualTo("2, 3");
        assertThat(first.answer3()).isEqualTo("-2, -3");
        assertThat(first.answer4()).isEqualTo("0, 5");
    }

    @Test
    @DisplayName("a question with no illustration yields a null image, not an empty array")
    void missingIllustrationIsNull() {
        // The seed loads ten questions marked as illustrated with no bytes supplied yet, so
        // NULL is the normal case rather than an edge one.
        long examVersionId = composeExam();

        List<TakeExamQuestion> asked = inTx(session -> questions.findForTakeExam(session, examVersionId));

        assertThat(asked).extracting(TakeExamQuestion::image).containsOnlyNulls();
    }

    @Test
    @DisplayName("an exam version with no questions yields an empty list, not null")
    void emptyExamVersion() {
        long examVersionId = inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 9, "101109", danaId);
            session.persist(exam);
            session.flush();
            ExamVersion version = new ExamVersion(exam.getId(), 1, "ריק", 30,
                    null, null, ExamVersionStatus.DRAFT, WHEN);
            session.persist(version);
            session.flush();
            return version.getId();
        });

        List<TakeExamQuestion> asked = inTx(session -> questions.findForTakeExam(session, examVersionId));

        assertThat(asked).isEmpty();
    }

    /** An exam version with three questions worth 40/40/20. */
    protected final long composeExam() {
        return inTx(session -> {
            Exam exam = new Exam(COURSE_ALGEBRA, (byte) 1, "101101", danaId);
            session.persist(exam);
            session.flush();

            ExamVersion version = new ExamVersion(exam.getId(), 1, "מבחן אמצע", 60,
                    null, null, ExamVersionStatus.APPROVED, WHEN);
            session.persist(version);
            session.flush();

            int[] points = {40, 40, 20};
            for (int i = 0; i < 3; i++) {
                short serial = (short) (i + 1);
                String displayId = COURSE_ALGEBRA + String.format("%03d", serial);
                Question question = new Question(COURSE_ALGEBRA, serial, displayId);
                session.persist(question);
                session.flush();

                QuestionVersion qv = new QuestionVersion(question.getId(), 1,
                        "מהם שורשי המשוואה " + serial + "?",
                        "1, 6", "2, 3", "-2, -3", "0, 5",
                        (byte) 2, "פונקציות", Difficulty.EASY, null, danaId, WHEN);
                session.persist(qv);
                session.flush();

                session.persist(new ExamVersionQuestion(
                        version.getId(), qv.getId(), question.getId(), points[i], i + 1));
            }
            return version.getId();
        });
    }
}
