package client.features.results;

import common.dto.exam.AttemptState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link CheckedFormCopy} — how a student's own paper is marked on screen (E13.4, T-9.2).
 *
 * <p>The test that matters is {@code unansweredIsNotWrong}. Both score zero (§6), so an
 * implementation that collapsed them would look right on every ordinary paper and would make a
 * timed-out one unreadable: every question the student never reached would be presented as a
 * mistake she made. That is the seeded {@code omer.katz} row, and it is acceptance case 9.5.
 */
class CheckedFormCopyTest {

    private static AnswerReviewRow row(Byte chosen, byte correct, boolean isCorrect, int awarded) {
        return new AnswerReviewRow(1, "11001", "What is a JVM?", "one", "two", "three", "four",
                15, chosen, correct, isCorrect, awarded);
    }

    private static CheckedForm form(AttemptState state, Integer minutes, String comment) {
        return new CheckedForm(
                new StudentGradeRow(900, 11, "מאיה לוי", 71, 71, 71, GradeState.APPROVED,
                        null, comment, Instant.parse("2026-08-20T09:00:00Z"),
                        "Algebra midterm", "11"),
                "Algebra midterm", "11", state, minutes, List.of());
    }

    // ===================== Marking ========================================

    @Nested
    @DisplayName("Marking a question")
    class Marking {

        @Test
        @DisplayName("a correct answer is marked correct")
        void correct() {
            AnswerReviewRow right = row((byte) 1, (byte) 1, true, 15);

            assertThat(CheckedFormCopy.outcome(right)).isEqualTo(CheckedFormCopy.CORRECT);
            assertThat(CheckedFormCopy.outcomeStyle(right))
                    .isEqualTo(CheckedFormCopy.CORRECT_STYLE);
            assertThat(CheckedFormCopy.points(right)).isEqualTo("15 / 15");
        }

        @Test
        @DisplayName("a wrong answer is marked wrong, and scores nothing")
        void wrong() {
            AnswerReviewRow missed = row((byte) 3, (byte) 1, false, 0);

            assertThat(CheckedFormCopy.outcome(missed)).isEqualTo(CheckedFormCopy.WRONG);
            assertThat(CheckedFormCopy.outcomeStyle(missed)).isEqualTo(CheckedFormCopy.WRONG_STYLE);
            assertThat(CheckedFormCopy.points(missed)).isEqualTo("0 / 15");
        }

        @Test
        @DisplayName("an unanswered question is NOT marked wrong, though it scored the same")
        void unansweredIsNotWrong() {
            AnswerReviewRow blank = row(null, (byte) 1, false, 0);
            AnswerReviewRow missed = row((byte) 3, (byte) 1, false, 0);

            // Both scored zero. They are different facts: one is a mistake to learn from, the
            // other a question she never reached. A timed-out paper is unreadable if these
            // collapse (9.5).
            assertThat(CheckedFormCopy.outcome(blank)).isEqualTo(CheckedFormCopy.UNANSWERED);
            assertThat(CheckedFormCopy.outcome(blank))
                    .isNotEqualTo(CheckedFormCopy.outcome(missed));
            assertThat(CheckedFormCopy.outcomeStyle(blank))
                    .isEqualTo(CheckedFormCopy.UNANSWERED_STYLE)
                    .isNotEqualTo(CheckedFormCopy.outcomeStyle(missed));
        }

        @Test
        @DisplayName("an unanswered question reports no chosen option rather than option zero")
        void unansweredHasNoChosenOption() {
            assertThat(CheckedFormCopy.chosenOption(row(null, (byte) 1, false, 0))).isZero();
            assertThat(CheckedFormCopy.chosenOption(row((byte) 3, (byte) 1, false, 0)))
                    .isEqualTo(3);
        }
    }

    // ===================== The header =====================================

    @Nested
    @DisplayName("The header")
    class Header {

        @Test
        @DisplayName("names the exam, the course and the score that counts")
        void headerLine() {
            assertThat(CheckedFormCopy.header(form(AttemptState.SUBMITTED, 45, null)))
                    .isEqualTo("Algebra midterm · 11 · 71 / 100");
        }

        @Test
        @DisplayName("a submitted paper says so, with the minutes recorded")
        void submitted() {
            assertThat(CheckedFormCopy.attemptLine(form(AttemptState.SUBMITTED, 45, null)))
                    .isEqualTo("Submitted · 45 minutes");
        }

        @Test
        @DisplayName("a timed-out paper explains what happened rather than naming a state")
        void timedOut() {
            String line = CheckedFormCopy.attemptLine(form(AttemptState.TIMED_OUT, 75, null));

            // omer.katz's seeded row. "TIMED_OUT" is a database word; a student reading it
            // would reasonably wonder whether her paper counted. It did (H12.4).
            assertThat(line).isEqualTo("Time ran out — submitted automatically · 75 minutes");
            assertThat(line).doesNotContain("TIMED_OUT");
        }

        @Test
        @DisplayName("one minute is singular, because a transcript that says '1 minutes' is sloppy")
        void singularMinute() {
            assertThat(CheckedFormCopy.attemptLine(form(AttemptState.SUBMITTED, 1, null)))
                    .endsWith("· 1 minute");
        }

        @Test
        @DisplayName("an unrecorded solving time is omitted rather than shown as zero")
        void noMinutesRecorded() {
            assertThat(CheckedFormCopy.attemptLine(form(AttemptState.SUBMITTED, null, null)))
                    .isEqualTo("Submitted");
        }
    }

    // ===================== The teacher's note =============================

    @Test
    @DisplayName("the teacher's note is shown when there is one, and absent when there is not")
    void teacherNote() {
        assertThat(CheckedFormCopy.teacherNote(form(AttemptState.SUBMITTED, 45, "שיפור ניכר")))
                .isEqualTo("שיפור ניכר");
        assertThat(CheckedFormCopy.teacherNote(form(AttemptState.SUBMITTED, 45, null))).isNull();
        assertThat(CheckedFormCopy.teacherNote(form(AttemptState.SUBMITTED, 45, "  "))).isNull();
    }

    @Test
    @DisplayName("the justification is never reachable from this screen's copy")
    void noJustification() {
        // CheckedForm strips it structurally; this asserts the copy layer would not surface it
        // even if one arrived.
        CheckedForm leaky = new CheckedForm(
                new StudentGradeRow(900, 11, "מאיה לוי", 45, 55, 55, GradeState.APPROVED,
                        "teacher-only audit text", "well done",
                        Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11"),
                "Algebra midterm", "11", AttemptState.SUBMITTED, 45, List.of());

        assertThat(leaky.grade().overrideReason()).isNull();
        assertThat(CheckedFormCopy.header(leaky)).doesNotContain("audit");
        assertThat(CheckedFormCopy.teacherNote(leaky)).doesNotContain("audit");
    }

    @Test
    @DisplayName("rejects nulls rather than rendering the word null onto a marked paper")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> CheckedFormCopy.outcome(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> CheckedFormCopy.header(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> CheckedFormCopy.attemptLine(null));
    }
}
