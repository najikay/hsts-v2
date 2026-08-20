package server.db.repos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Exam;
import server.db.entities.Question;
import server.db.ids.AllocatedId;
import server.db.ids.ExamIdAllocator;
import server.db.ids.QuestionIdAllocator;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The engine-agnostic half of E2.14.
 *
 * <p>Concurrency is not tested here — it belongs in the MySQL leaf, because H2 will not
 * reproduce the row locking that makes the answer correct.
 */
abstract class AllocatorContract extends RepositoryTestBase {

    private final QuestionIdAllocator questions = new QuestionIdAllocator();
    private final ExamIdAllocator exams = new ExamIdAllocator();

    @Test
    @DisplayName("the first question in a course is serial 1, displayed as course + 001")
    void firstQuestionSerial() {
        AllocatedId allocated = inTx(session -> questions.allocate(session, COURSE_ALGEBRA));

        assertThat(allocated.serial()).isEqualTo(1);
        assertThat(allocated.displayId()).isEqualTo("11001");
    }

    @Test
    @DisplayName("a gap in the serials does not hand out a number already in use")
    void questionSerialsSkipGaps() {
        // Serials 1 and 5, nothing between them - exactly what an import or a seed produces.
        // COUNT + 1 answers 3 here, then 4, then collides with 5 on the third call. MAX + 1
        // answers 6 and stays correct.
        persistQuestion(COURSE_ALGEBRA, (short) 1);
        persistQuestion(COURSE_ALGEBRA, (short) 5);

        AllocatedId allocated = inTx(session -> questions.allocate(session, COURSE_ALGEBRA));

        assertThat(allocated.serial()).isEqualTo(6);
        assertThat(allocated.displayId()).isEqualTo("11006");
    }

    @Test
    @DisplayName("a soft-deleted question keeps its serial forever")
    void softDeletedQuestionKeepsItsSerial() {
        persistQuestion(COURSE_ALGEBRA, (short) 1);
        Question second = persistQuestion(COURSE_ALGEBRA, (short) 2);
        persistQuestion(COURSE_ALGEBRA, (short) 3);

        runInTx(session -> {
            Question managed = session.find(Question.class, second.getId());
            managed.setDeletedAt(Instant.parse("2026-08-20T10:00:00Z"));
        });

        // An implementation counting only live rows answers 3 and collides with the question
        // that already holds serial 3. F2.5 soft delete is exactly why MAX is the rule.
        AllocatedId allocated = inTx(session -> questions.allocate(session, COURSE_ALGEBRA));

        assertThat(allocated.serial()).isEqualTo(4);
    }

    @Test
    @DisplayName("serials are per course, not global")
    void serialsAreScopedToTheCourse() {
        persistQuestion(COURSE_ALGEBRA, (short) 7);

        AllocatedId other = inTx(session -> questions.allocate(session, COURSE_JAVA));

        assertThat(other.serial()).isEqualTo(1);
        assertThat(other.displayId()).isEqualTo("21001");
    }

    @Test
    @DisplayName("a question serial past 999 is refused rather than overflowing CHAR(5)")
    void questionSerialOverflowIsRefused() {
        persistQuestion(COURSE_ALGEBRA, (short) 999);

        // The entity field is a short, so 1000 assigns happily and produces a 6-character id
        // for a CHAR(5) column. The width has to be enforced here or not at all.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> inTx(session -> questions.allocate(session, COURSE_ALGEBRA)))
                .withMessageContaining("999");
    }

    @Test
    @DisplayName("the exam id carries the subject in front of the course")
    void examIdIncludesSubject() {
        AllocatedId allocated = inTx(session -> exams.allocate(session, COURSE_ALGEBRA));

        // Algebra (11) belongs to Mathematics (10); the subject is not stored on exams, so a
        // wrong join here would silently produce ids under the wrong subject.
        assertThat(allocated.displayId()).isEqualTo("101101");
        assertThat(allocated.serial()).isEqualTo(1);
    }

    @Test
    @DisplayName("exam serials also continue past a gap")
    void examSerialsSkipGaps() {
        persistExam(COURSE_ALGEBRA, (byte) 4);

        assertThat(inTx(session -> exams.allocate(session, COURSE_ALGEBRA)).serial()).isEqualTo(5);
    }

    @Test
    @DisplayName("an exam serial past 99 is refused rather than overflowing CHAR(6)")
    void examSerialOverflowIsRefused() {
        persistExam(COURSE_ALGEBRA, (byte) 99);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> inTx(session -> exams.allocate(session, COURSE_ALGEBRA)))
                .withMessageContaining("99");
    }

    @Test
    @DisplayName("allocating for a course that does not exist fails loudly")
    void unknownCourseIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> inTx(session -> questions.allocate(session, "99")))
                .withMessageContaining("99");
    }

    private Question persistQuestion(String courseCode, short serial) {
        return inTx(session -> {
            Question question = new Question(courseCode, serial, courseCode + String.format("%03d", serial));
            session.persist(question);
            return question;
        });
    }

    private void persistExam(String courseCode, byte serial) {
        runInTx(session -> session.persist(new Exam(courseCode, serial,
                SUBJECT_MATH + courseCode + String.format("%02d", serial), danaId)));
    }
}
