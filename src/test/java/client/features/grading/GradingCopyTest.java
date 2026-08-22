package client.features.grading;

import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link GradingCopy} — the teacher's grading vocabulary (E12).
 *
 * <p>Two of these decide something rather than describe it. {@code bulkConfirm} is the last
 * sentence a teacher reads before a class of students can see their marks, and
 * {@code adjustedMarker} is the one that would tell a whole class their papers were hand-changed
 * if it keyed on the wrong thing.
 */
class GradingCopyTest {

    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");

    private static ExecutionGradingSummary summary(int participants, int graded, int approved) {
        return new ExecutionGradingSummary(4822, "Java midterm", "21", "7390",
                Instant.parse("2026-06-02T10:00:00Z"), participants, graded, approved);
    }

    private static StudentGradeRow row(int auto, Integer finalScore, GradeState state) {
        return new StudentGradeRow(1, 11, "מאיה לוי", auto, finalScore,
                finalScore == null ? auto : finalScore, state, null, null, null);
    }

    // ===================== Progress =======================================

    @Nested
    @DisplayName("Progress")
    class Progress {

        @Test
        @DisplayName("states the remainder, because that is the number she acts on")
        void statesTheRemainder() {
            // "6 of 8 approved" makes a teacher subtract; "2 still to approve" does not.
            assertThat(GradingCopy.progress(summary(8, 8, 6)))
                    .isEqualTo("8 sat · 8 marked · 2 still to approve");
        }

        @Test
        @DisplayName("a sitting nobody has marked reads as nothing to approve, not as done")
        void nothingMarked() {
            assertThat(GradingCopy.progress(summary(8, 0, 0)))
                    .isEqualTo("8 sat · 0 marked · 0 still to approve");
        }

        @Test
        @DisplayName("names the exam and its course")
        void examLabel() {
            assertThat(GradingCopy.examLabel(summary(8, 8, 0)))
                    .isEqualTo("Java midterm · 21");
        }

        @Test
        @DisplayName("renders the closing time in the reader's own zone")
        void closedAt() {
            assertThat(GradingCopy.closedAt(summary(8, 8, 0), JERUSALEM))
                    .isEqualTo("2 Jun 13:00");
            assertThat(GradingCopy.closedAt(summary(8, 8, 0), ZoneId.of("UTC")))
                    .isEqualTo("2 Jun 10:00");
        }
    }

    // ===================== The confirmation ===============================

    @Nested
    @DisplayName("The bulk confirmation")
    class BulkConfirm {

        @Test
        @DisplayName("names the consequence rather than the action")
        void namesTheConsequence() {
            String text = GradingCopy.bulkConfirm(8);

            // What she needs to weigh is that eight students are about to see their marks and
            // that it cannot be undone - not that she clicked a button called Approve.
            assertThat(text).contains("8 students will be able to see");
            assertThat(text).contains("cannot be changed afterwards");
        }

        @Test
        @DisplayName("is singular for one grade, because a confirmation that says '1 grades' "
                + "reads as a bug")
        void singularForOne() {
            String text = GradingCopy.bulkConfirm(1);

            assertThat(text).startsWith("Approve this grade?");
            assertThat(text).doesNotContain("1 grades");
            assertThat(text).contains("cannot be changed afterwards");
        }
    }

    // ===================== Row vocabulary =================================

    @Nested
    @DisplayName("Row vocabulary")
    class Rows {

        @Test
        @DisplayName("states approved and awaiting in the teacher's words")
        void state() {
            assertThat(GradingCopy.state(row(100, null, GradeState.AUTO)))
                    .isEqualTo("Awaiting approval");
            assertThat(GradingCopy.state(row(100, 100, GradeState.APPROVED)))
                    .isEqualTo("Approved");
        }

        @Test
        @DisplayName("the adjusted marker keys on the scores DIFFERING, not on a final existing")
        void adjustedKeysOnDifference() {
            // Approving sets finalScore to the auto score when nobody overrode, so every
            // approved row has one. A marker driven by presence would tell a whole class their
            // papers had been hand-changed.
            assertThat(GradingCopy.wasAdjusted(row(100, 100, GradeState.APPROVED))).isFalse();
            assertThat(GradingCopy.adjustedMarker(row(100, 100, GradeState.APPROVED))).isEmpty();

            assertThat(GradingCopy.wasAdjusted(row(45, 55, GradeState.APPROVED))).isTrue();
            assertThat(GradingCopy.adjustedMarker(row(45, 55, GradeState.APPROVED)))
                    .isEqualTo("Adjusted");
        }

        @Test
        @DisplayName("override is offered only while the grade is still awaiting approval")
        void overrideOnlyWhileAuto() {
            // The contract refuses an override on an approved grade. Disabling the control
            // means she is not asked to write a justification that will then be thrown away.
            assertThat(GradingCopy.canOverride(row(100, null, GradeState.AUTO))).isTrue();
            assertThat(GradingCopy.canOverride(row(100, 100, GradeState.APPROVED))).isFalse();
        }
    }

    // ===================== The justification label =========================

    @Test
    @DisplayName("the justification label says both that it is stored and that she does not see it")
    void justificationLabelSaysBothHalves() {
        String label = GradingCopy.JUSTIFICATION_LABEL;

        // A teacher who thinks it is private writes something careless; one who thinks the
        // student reads it writes nothing useful for the audit trail (S-23).
        assertThat(label).contains("Stored with the grade");
        assertThat(label).contains("does not");
        assertThat(label).contains("comment");
    }

    @Test
    @DisplayName("the empty queue explains itself rather than looking like a failed load")
    void emptyQueueExplainsItself() {
        assertThat(GradingCopy.QUEUE_EMPTY_HINT).contains("fully approved");
        assertThat(GradingCopy.QUEUE_EMPTY_HINT).isNotEqualTo(GradingCopy.LOAD_FAILED);
    }

    @Test
    @DisplayName("rejects nulls rather than rendering the word null onto a grading table")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> GradingCopy.progress(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> GradingCopy.state(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> GradingCopy.closedAt(summary(8, 8, 0), null));
    }
}
