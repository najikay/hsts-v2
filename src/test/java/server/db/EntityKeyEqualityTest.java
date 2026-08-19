package server.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import server.db.entities.AttemptAnswer;
import server.db.entities.CourseTeacher;
import server.db.entities.Enrollment;
import server.db.entities.ExamVersionQuestion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The equality contract of the composite primary keys (E2.9). No database needed.
 *
 * <p>This is not box-ticking. Hibernate uses these keys to decide whether two objects
 * are the same row — for the persistence context, for {@code find}, for dirty checking.
 * An {@code equals} that compares only one component silently merges two different rows;
 * one that omits {@code hashCode} makes lookups miss and produces a duplicate insert at
 * flush time. Both failures appear far from the mistake and are miserable to trace, so
 * each key is checked against the whole contract: reflexive, symmetric, null-safe,
 * type-safe, and sensitive to <em>every</em> component.
 */
class EntityKeyEqualityTest {

    @Nested
    @DisplayName("course_teachers (course, teacher)")
    class CourseTeacherKey {

        private final CourseTeacher.Id key = new CourseTeacher.Id("11", 7L);

        @Test
        @DisplayName("equal to itself and to an identical key, both ways round")
        void reflexiveAndSymmetric() {
            CourseTeacher.Id same = new CourseTeacher.Id("11", 7L);

            assertThat(key).isEqualTo(key).isEqualTo(same);
            assertThat(same).isEqualTo(key);
            assertThat(key).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("a difference in either component makes it a different key")
        void everyComponentCounts() {
            assertThat(key).isNotEqualTo(new CourseTeacher.Id("12", 7L));
            assertThat(key).isNotEqualTo(new CourseTeacher.Id("11", 8L));
        }

        @Test
        @DisplayName("never equal to null or to another key type")
        void nullAndTypeSafe() {
            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(new Enrollment.Id("11", 7L));
            assertThat(key).isNotEqualTo("11/7");
        }

        @Test
        @DisplayName("toString names both halves, for readable failure messages")
        void readableToString() {
            assertThat(key.toString()).contains("11").contains("7");
        }
    }

    @Nested
    @DisplayName("enrollments (course, student)")
    class EnrollmentKey {

        private final Enrollment.Id key = new Enrollment.Id("21", 42L);

        @Test
        @DisplayName("equal to an identical key, and hashes the same")
        void equality() {
            Enrollment.Id same = new Enrollment.Id("21", 42L);

            assertThat(key).isEqualTo(key).isEqualTo(same);
            assertThat(key).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("a difference in either component makes it a different key")
        void everyComponentCounts() {
            assertThat(key).isNotEqualTo(new Enrollment.Id("22", 42L));
            assertThat(key).isNotEqualTo(new Enrollment.Id("21", 43L));
        }

        @Test
        @DisplayName("never equal to null or to another key type")
        void nullAndTypeSafe() {
            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(new CourseTeacher.Id("21", 42L));
        }

        @Test
        @DisplayName("toString names both halves")
        void readableToString() {
            assertThat(key.toString()).contains("21").contains("42");
        }

        @Test
        @DisplayName("the accessors report what was built")
        void accessors() {
            assertThat(key.getCourseCode()).isEqualTo("21");
            assertThat(key.getStudentId()).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("exam_version_questions (exam_version_id, question_version_id)")
    class ExamVersionQuestionKey {

        private final ExamVersionQuestion.Id key = new ExamVersionQuestion.Id(3L, 9L);

        @Test
        @DisplayName("equal to an identical key, and hashes the same")
        void equality() {
            ExamVersionQuestion.Id same = new ExamVersionQuestion.Id(3L, 9L);

            assertThat(key).isEqualTo(key).isEqualTo(same);
            assertThat(key).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("the two halves are not interchangeable")
        void componentsAreNotSymmetric() {
            // (3,9) and (9,3) are different rows: exam version 3 question 9 is not
            // exam version 9 question 3. A key that hashed only the sum would confuse them.
            assertThat(key).isNotEqualTo(new ExamVersionQuestion.Id(9L, 3L));
            assertThat(key).isNotEqualTo(new ExamVersionQuestion.Id(3L, 10L));
            assertThat(key).isNotEqualTo(new ExamVersionQuestion.Id(4L, 9L));
        }

        @Test
        @DisplayName("never equal to null or to another key type")
        void nullAndTypeSafe() {
            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(new AttemptAnswer.Id(3L, 9L));
        }

        @Test
        @DisplayName("the accessors report what was built")
        void accessors() {
            assertThat(key.getExamVersionId()).isEqualTo(3L);
            assertThat(key.getQuestionVersionId()).isEqualTo(9L);
            assertThat(key.toString()).contains("3").contains("9");
        }
    }

    @Nested
    @DisplayName("attempt_answers (attempt_id, question_version_id)")
    class AttemptAnswerKey {

        private final AttemptAnswer.Id key = new AttemptAnswer.Id(5L, 12L);

        @Test
        @DisplayName("equal to an identical key, and hashes the same")
        void equality() {
            AttemptAnswer.Id same = new AttemptAnswer.Id(5L, 12L);

            assertThat(key).isEqualTo(key).isEqualTo(same);
            assertThat(key).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("the two halves are not interchangeable")
        void componentsAreNotSymmetric() {
            assertThat(key).isNotEqualTo(new AttemptAnswer.Id(12L, 5L));
            assertThat(key).isNotEqualTo(new AttemptAnswer.Id(5L, 13L));
            assertThat(key).isNotEqualTo(new AttemptAnswer.Id(6L, 12L));
        }

        @Test
        @DisplayName("never equal to null or to another key type")
        void nullAndTypeSafe() {
            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(new ExamVersionQuestion.Id(5L, 12L));
        }

        @Test
        @DisplayName("the accessors report what was built")
        void accessors() {
            assertThat(key.getAttemptId()).isEqualTo(5L);
            assertThat(key.getQuestionVersionId()).isEqualTo(12L);
            assertThat(key.toString()).contains("5").contains("12");
        }
    }
}
