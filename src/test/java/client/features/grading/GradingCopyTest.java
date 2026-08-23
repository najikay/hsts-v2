package client.features.grading;

import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link GradingCopy} — the teacher's grading vocabulary (E12).
 *
 * <p>Two of these decide something rather than describe it. {@code bulkConfirm} is the last
 * sentence a teacher reads before a class of students can see their marks, and
 * {@code adjustedMarker} is the one that would tell a whole class their papers were hand-changed
 * if it keyed on the wrong thing.
 *
 * <p>The §4.1 rules — no em dash, no shouting, sentence case — are run over the catalogue by a
 * <b>scan</b> rather than a list, so a string added later is covered the moment it is written.
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

    // ===================== The scan ======================================

    /**
     * Every public String constant that is copy, found by scanning rather than by list.
     *
     * <p>Two constants on this class are not copy and are skipped, each for a stated reason
     * rather than because it was inconvenient. {@code STYLE_CLASS} is a CSS selector — it is
     * lower case because that is what a class name is, and asking it to start with a capital
     * would be asking the stylesheet to read like a sentence. {@code COLUMN_ADJUSTED} is
     * <b>deliberately empty</b>: the adjusted column's heading is blank so the marker column
     * carries no title above a mostly-empty column.
     *
     * <p>A scan rather than a list, for the reason {@code ReleaseCopyTest} is one: a rule that
     * only checks the strings somebody remembered to enumerate is a rule a new string walks
     * past — and the two strings this amendment adds are exactly that case.
     */
    static List<String> allCopy() {
        List<String> copy = new ArrayList<>();
        for (Field field : GradingCopy.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())
                    || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class
                    || "STYLE_CLASS".equals(field.getName())) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (!value.isEmpty()) {
                    copy.add(value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not read " + field.getName(), e);
            }
        }
        return copy;
    }

    @Test
    @DisplayName("the scan really finds the copy, so a green run means something")
    void theScanHasTeeth() {
        assertThat(allCopy()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(allCopy())
                .as("the amendment's two new strings are in the scan, not beside it")
                .contains(GradingCopy.COMMENT_LABEL, GradingCopy.COMMENT_PROMPT);
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("no line contains an em dash (PRD section 4.1)")
    void noEmDashes(String line) {
        assertThat(line).doesNotContain("—").doesNotContain("–");
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("nothing shouts")
    void noShouting(String line) {
        assertThat(line).isNotBlank();
        assertThat(line).isNotEqualTo(line.toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("allCopy")
    @DisplayName("sentence case: every line starts with a capital and is not Title Case")
    void sentenceCase(String line) {
        assertThat(line.charAt(0)).isUpperCase();
    }

    // ===================== The two labels in the dialog ===================

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
    @DisplayName("the comment label says it is optional, that the student reads it, and that "
            + "leaving it empty keeps what is saved (S-22)")
    void commentLabelSaysAllThreeThings() {
        String label = GradingCopy.COMMENT_LABEL;

        // Optional, so she is not made to write to move a score. Read by the student, which is
        // the entire difference from the box above it. And empty-keeps-what-is-saved, because
        // the box opens empty on a second correction and the server's null-preserves rule is
        // otherwise invisible to her (contract A3).
        assertThat(label).contains("Optional");
        assertThat(label).contains("she will see it");
        assertThat(label).contains("keeps any comment already saved");
    }

    @Test
    @DisplayName("the two labels are not the same sentence with a word changed")
    void theTwoLabelsSayOppositeThings() {
        // The whole reason the dialog has two boxes. If these ever converge, a teacher has no
        // way to tell which piece of writing she is doing.
        assertThat(GradingCopy.JUSTIFICATION_LABEL).isNotEqualTo(GradingCopy.COMMENT_LABEL);
        assertThat(GradingCopy.JUSTIFICATION_LABEL).contains("for the record");
        assertThat(GradingCopy.COMMENT_LABEL).doesNotContain("for the record");
        assertThat(GradingCopy.JUSTIFICATION_PROMPT).isNotEqualTo(GradingCopy.COMMENT_PROMPT);
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
