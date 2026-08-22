package client.features.results;

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
 * {@link MyGradesCopy} — every word and every formatted value a student reads (E13.3).
 *
 * <p>{@code MyGradesView} is a thin renderer excluded from the coverage gate, so this is where
 * the screen's decisions are actually measured. Two of them carry real weight.
 *
 * <p>{@code adjustedMarkerIsAboutTheDifferenceNotThePresence} is the one that would catch a
 * plausible bug with an unpleasant blast radius: approving a grade sets {@code finalScore} even
 * when nobody overrode anything, so a marker driven by "has a final score" would tell every
 * student in the class that their paper had been changed by hand.
 *
 * <p>{@code justificationIsNeverAnywhereInTheCopy} is the belt to the wire's braces. The DTO
 * already strips {@code overrideReason} structurally; this asserts that the presentation tier
 * does not reintroduce it by rendering a row that happens to carry one.
 */
class MyGradesCopyTest {

    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");

    private static StudentGradeRow row(int autoScore, Integer finalScore, String comment,
                                       Instant approvedAt, String examName, String courseCode) {
        int effective = finalScore != null ? finalScore : autoScore;
        return new StudentGradeRow(900, 11, "מאיה לוי", autoScore, finalScore, effective,
                GradeState.APPROVED, null, comment, approvedAt, examName, courseCode);
    }

    private static StudentGradeRow plainRow() {
        return row(71, 71, null, Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");
    }

    // ===================== The score ======================================

    @Nested
    @DisplayName("The grade")
    class Score {

        @Test
        @DisplayName("is stated out of 100, so a student never wonders out of what")
        void outOfOneHundred() {
            assertThat(MyGradesCopy.score(plainRow())).isEqualTo("71 / 100");
        }

        @Test
        @DisplayName("is the effective score, which is the teacher's once she overrode")
        void usesTheEffectiveScore() {
            StudentGradeRow overridden = row(45, 55, null,
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

            // The seeded yael.azulay row: auto 45, final 55, the fail that became a pass.
            assertThat(MyGradesCopy.score(overridden)).isEqualTo("55 / 100");
        }

        @Test
        @DisplayName("renders a zero as a zero, not as a blank")
        void zeroIsAScore() {
            StudentGradeRow nothing = row(0, 0, null,
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

            // A timed-out attempt with nothing answered scores 0 and was still sat (H12.4).
            assertThat(MyGradesCopy.score(nothing)).isEqualTo("0 / 100");
        }
    }

    // ===================== The adjusted marker ============================

    @Nested
    @DisplayName("The adjusted marker")
    class Adjusted {

        @Test
        @DisplayName("is about the difference between the scores, not the presence of a final one")
        void adjustedMarkerIsAboutTheDifferenceNotThePresence() {
            // Grade.approve() sets finalScore to the auto score when nobody overrode. Every
            // approved row therefore HAS one, and a marker keyed on presence would fire for
            // the whole class.
            StudentGradeRow approvedUntouched = row(71, 71, null,
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");
            StudentGradeRow actuallyChanged = row(45, 55, null,
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

            assertThat(MyGradesCopy.wasAdjusted(approvedUntouched)).isFalse();
            assertThat(MyGradesCopy.adjustedMarker(approvedUntouched)).isEmpty();
            assertThat(MyGradesCopy.wasAdjusted(actuallyChanged)).isTrue();
            assertThat(MyGradesCopy.adjustedMarker(actuallyChanged))
                    .isEqualTo(MyGradesCopy.ADJUSTED_MARKER);
        }

        @Test
        @DisplayName("is blank rather than a dash, so the eye goes to the exception")
        void blankForTheOrdinaryCase() {
            assertThat(MyGradesCopy.adjustedMarker(plainRow())).isEmpty();
        }

        @Test
        @DisplayName("says a teacher reviewed it, not that something was corrected")
        void wordingIsAboutAPersonNotAFault() {
            // Deliberately gentler than the teacher table's bare "Adjusted": this one is read
            // by the student whose paper it is.
            assertThat(MyGradesCopy.ADJUSTED_MARKER).isEqualTo("Reviewed by your teacher");
            assertThat(MyGradesCopy.ADJUSTED_MARKER).isNotEqualTo("Adjusted");
        }

        @Test
        @DisplayName("does not fire when a teacher set the same number the machine did")
        void sameNumberIsNotAnAdjustment() {
            StudentGradeRow agreed = row(71, 71, "well done",
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

            assertThat(MyGradesCopy.wasAdjusted(agreed)).isFalse();
        }
    }

    // ===================== The justification, which is absent =============

    @Test
    @DisplayName("the justification is never anywhere in the copy, even on a row carrying one")
    void justificationIsNeverAnywhereInTheCopy() {
        // A row that should never exist on this wire — MyGrades strips it — used here to prove
        // the presentation tier would not render it even if one arrived.
        String secret = "teacher-only audit text";
        StudentGradeRow leaky = new StudentGradeRow(900, 11, "מאיה לוי", 45, 55, 55,
                GradeState.APPROVED, secret, "שיפור ניכר", Instant.parse("2026-08-20T09:00:00Z"),
                "Algebra midterm", "11");

        assertThat(MyGradesCopy.score(leaky)).doesNotContain(secret);
        assertThat(MyGradesCopy.comment(leaky)).doesNotContain(secret);
        assertThat(MyGradesCopy.adjustedMarker(leaky)).doesNotContain(secret);
        assertThat(MyGradesCopy.examName(leaky)).doesNotContain(secret);
        assertThat(MyGradesCopy.rowDescription(leaky, JERUSALEM)).doesNotContain(secret);
    }

    // ===================== Labels and dates ===============================

    @Nested
    @DisplayName("Labels")
    class Labels {

        @Test
        @DisplayName("show the exam and course the v1.1 amendment put on the row")
        void showsExamAndCourse() {
            assertThat(MyGradesCopy.examName(plainRow())).isEqualTo("Algebra midterm");
            assertThat(MyGradesCopy.courseCode(plainRow())).isEqualTo("11");
        }

        @Test
        @DisplayName("say so honestly when a row arrived unlabelled, rather than hiding the grade")
        void unlabelledRowKeepsItsGrade() {
            StudentGradeRow unlabelled = row(71, 71, null,
                    Instant.parse("2026-08-20T09:00:00Z"), null, null);

            assertThat(MyGradesCopy.examName(unlabelled)).isEqualTo("(exam unavailable)");
            assertThat(MyGradesCopy.courseCode(unlabelled)).isEqualTo(MyGradesCopy.NO_COMMENT);
            // The point: the grade itself still renders.
            assertThat(MyGradesCopy.score(unlabelled)).isEqualTo("71 / 100");
        }

        @Test
        @DisplayName("treat a blank label as no label")
        void blankIsTreatedAsAbsent() {
            StudentGradeRow blank = row(71, 71, null,
                    Instant.parse("2026-08-20T09:00:00Z"), "   ", "");

            assertThat(MyGradesCopy.examName(blank)).isEqualTo("(exam unavailable)");
            assertThat(MyGradesCopy.courseCode(blank)).isEqualTo(MyGradesCopy.NO_COMMENT);
        }
    }

    @Nested
    @DisplayName("The approval date")
    class ApprovedOn {

        @Test
        @DisplayName("converts the UTC wire instant into the reader's own zone")
        void convertsFromUtc() {
            // 21:30 UTC is the following day in Jerusalem — the case that catches a renderer
            // that formatted the instant without a zone.
            StudentGradeRow lateEvening = row(71, 71, null,
                    Instant.parse("2026-08-20T21:30:00Z"), "Algebra midterm", "11");

            assertThat(MyGradesCopy.approvedOn(lateEvening, JERUSALEM)).isEqualTo("21 Aug 2026");
            assertThat(MyGradesCopy.approvedOn(lateEvening, ZoneId.of("UTC")))
                    .isEqualTo("20 Aug 2026");
        }

        @Test
        @DisplayName("is a date without a clock time — a transcript, not a schedule")
        void noClockTime() {
            assertThat(MyGradesCopy.approvedOn(plainRow(), JERUSALEM)).isEqualTo("20 Aug 2026");
        }

        @Test
        @DisplayName("renders a dash rather than throwing if a row somehow has no approval")
        void unapprovedRowDoesNotBlankTheList() {
            StudentGradeRow odd = row(71, 71, null, null, "Algebra midterm", "11");

            assertThat(MyGradesCopy.approvedOn(odd, JERUSALEM)).isEqualTo(MyGradesCopy.NO_COMMENT);
        }
    }

    // ===================== The comment ====================================

    @Nested
    @DisplayName("The teacher's note")
    class Comment {

        @Test
        @DisplayName("is shown when there is one")
        void showsTheComment() {
            StudentGradeRow withNote = row(51, 55, "שיפור ניכר באי-שוויונות",
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

            assertThat(MyGradesCopy.comment(withNote)).isEqualTo("שיפור ניכר באי-שוויונות");
        }

        @Test
        @DisplayName("is a dash when the teacher wrote nothing, and when she wrote only spaces")
        void absentCommentIsADash() {
            assertThat(MyGradesCopy.comment(plainRow())).isEqualTo(MyGradesCopy.NO_COMMENT);

            StudentGradeRow whitespace = row(71, 71, "   ",
                    Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");
            assertThat(MyGradesCopy.comment(whitespace)).isEqualTo(MyGradesCopy.NO_COMMENT);
        }
    }

    // ===================== Accessibility ==================================

    @Test
    @DisplayName("a row reads as one sentence for a screen reader")
    void rowDescriptionReadsAsASentence() {
        String spoken = MyGradesCopy.rowDescription(plainRow(), JERUSALEM);

        assertThat(spoken).isEqualTo("Algebra midterm, 71 / 100, approved 20 Aug 2026");
    }

    @Test
    @DisplayName("and mentions the review when a teacher set the grade")
    void rowDescriptionMentionsAnAdjustment() {
        StudentGradeRow overridden = row(45, 55, null,
                Instant.parse("2026-08-20T09:00:00Z"), "Algebra midterm", "11");

        assertThat(MyGradesCopy.rowDescription(overridden, JERUSALEM))
                .endsWith("reviewed by your teacher");
    }

    // ===================== Contract =======================================

    @Test
    @DisplayName("every row on this screen is approved, which is what the verb guarantees")
    void everyRowIsApproved() {
        assertThat(MyGradesCopy.isApproved(plainRow())).isTrue();
    }

    @Test
    @DisplayName("rejects a null row rather than rendering the word null into a transcript")
    void rejectsNulls() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> MyGradesCopy.score(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> MyGradesCopy.comment(null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> MyGradesCopy.approvedOn(plainRow(), null));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> MyGradesCopy.adjustedMarker(null));
    }
}
