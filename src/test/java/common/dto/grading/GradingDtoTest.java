package common.dto.grading;

import common.dto.exam.AttemptState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compact-constructor rules of the frozen grading contract (E12/E13).
 *
 * <p>{@code DtoSerializationTest} proves these records survive the wire; this one proves what
 * their constructors guarantee once they arrive. Both matter, because a record deserializes
 * through its canonical constructor — so a defensive copy and a null check are not merely
 * sender-side hygiene, they run again on the receiving side and are the only code that does.
 *
 * <p>Two things are deliberately <b>not</b> tested here, because they are deliberately not
 * implemented here (see the package javadoc): a score outside 0..100 and a blank override
 * justification are {@code VALIDATION} answers from Member B's E12 handlers, not exceptions
 * thrown inside a socket read thread.
 */
class GradingDtoTest {

    private static final Instant APPROVED_AT = Instant.parse("2026-08-20T11:30:00Z");

    @Nested
    @DisplayName("required references")
    class NullChecks {

        @Test
        @DisplayName("a grade row without a name or a state is rejected at construction")
        void studentGradeRowRequiresNameAndState() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StudentGradeRow(1L, 2L, null, 70, null, 70,
                            GradeState.AUTO, null, null, null))
                    .withMessageContaining("studentName");
            assertThatNullPointerException()
                    .isThrownBy(() -> new StudentGradeRow(1L, 2L, "Maya Levi", 70, null, 70,
                            null, null, null, null))
                    .withMessageContaining("state");
        }

        @Test
        @DisplayName("the four nullable fields of a grade row really are nullable")
        void studentGradeRowAllowsItsFourNulls() {
            StudentGradeRow row = new StudentGradeRow(1L, 2L, "Maya Levi", 70, null, 70,
                    GradeState.AUTO, null, null, null);

            assertThat(row.finalScore()).isNull();
            assertThat(row.overrideReason()).isNull();
            assertThat(row.teacherComment()).isNull();
            assertThat(row.approvedAt()).isNull();
        }

        @Test
        @DisplayName("A7 — a grade row's teacher name is never null, empty when unresolvable")
        void studentGradeRowNormalisesItsTeacherName() {
            // Null in, empty out: one absence for a screen to test rather than two, and the
            // word "null" cannot reach a card. Same treatment CheckedForm.teacherName gets.
            StudentGradeRow explicitNull = new StudentGradeRow(1L, 2L, "Maya Levi", 70, null, 70,
                    GradeState.APPROVED, null, null, APPROVED_AT, "Midterm", "11", null);
            assertThat(explicitNull.teacherName()).isEmpty();

            // The no-label constructor says "no labels", and a teacher is one of the labels.
            StudentGradeRow unlabelled = new StudentGradeRow(1L, 2L, "Maya Levi", 70, null, 70,
                    GradeState.AUTO, null, null, null);
            assertThat(unlabelled.teacherName()).isEmpty();

            StudentGradeRow named = new StudentGradeRow(1L, 2L, "Maya Levi", 70, null, 70,
                    GradeState.APPROVED, null, null, APPROVED_AT, "Midterm", "11", "Dana Cohen");
            assertThat(named.teacherName()).isEqualTo("Dana Cohen");
        }

        @Test
        @DisplayName("A7 — the copiers carry the teacher and the attempt through, not around")
        void studentGradeRowCopiersCarryEverything() {
            StudentGradeRow teacherSide = new StudentGradeRow(1L, 2L, "Maya Levi", 70, 80, 80,
                    GradeState.APPROVED, "audit text", "Well done", APPROVED_AT, "Midterm", "11",
                    "Dana Cohen", AttemptState.TIMED_OUT, 43);

            StudentGradeRow stripped = teacherSide.withoutJustification();

            // Only the justification goes. A copier that quietly dropped the rest would be the
            // shape A5 and A7 both exist to close: a field on the wire that no screen receives.
            assertThat(stripped.overrideReason()).isNull();
            assertThat(stripped.teacherName()).isEqualTo("Dana Cohen");
            assertThat(stripped.attemptStatus()).isEqualTo(AttemptState.TIMED_OUT);
            assertThat(stripped.actualMinutes()).isEqualTo(43);

            StudentGradeRow relabelled = teacherSide.withExam("Algebra", "11", "Avi Mizrahi");
            assertThat(relabelled.examName()).isEqualTo("Algebra");
            assertThat(relabelled.teacherName()).isEqualTo("Avi Mizrahi");
            assertThat(relabelled.attemptStatus()).isEqualTo(AttemptState.TIMED_OUT);
        }

        @Test
        @DisplayName("an answer row needs a display id and a question text")
        void answerReviewRowRequiresItsText() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AnswerReviewRow(1, null, "q", "a", "b", "c", "d",
                            25, (byte) 1, (byte) 1, true, 25))
                    .withMessageContaining("displayId");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AnswerReviewRow(1, "112001", null, "a", "b", "c", "d",
                            25, (byte) 1, (byte) 1, true, 25))
                    .withMessageContaining("questionText");
        }

        @Test
        @DisplayName("a queue summary needs its three names")
        void summaryRequiresItsNames() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionGradingSummary(1L, null, "11", "7391",
                            APPROVED_AT, 1, 1, 1))
                    .withMessageContaining("examName");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionGradingSummary(1L, "Midterm", null, "7391",
                            APPROVED_AT, 1, 1, 1))
                    .withMessageContaining("courseCode");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionGradingSummary(1L, "Midterm", "11", null,
                            APPROVED_AT, 1, 1, 1))
                    .withMessageContaining("code4");
        }

        @Test
        @DisplayName("the composite answers need the thing they are about")
        void compositesRequireTheirSubject() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ExecutionGrades(null, List.of()))
                    .withMessageContaining("summary");
            assertThatNullPointerException()
                    .isThrownBy(() -> new GradeReview(null, List.of()))
                    .withMessageContaining("grade");
            assertThatNullPointerException()
                    .isThrownBy(() -> new CheckedForm(null, "Midterm", "11", "Dana Cohen",
                            AttemptState.SUBMITTED, 45, List.of()))
                    .withMessageContaining("grade");
            assertThatNullPointerException()
                    .isThrownBy(() -> new CheckedForm(approvedRow(), null, "11", "Dana Cohen",
                            AttemptState.SUBMITTED, 45, List.of()))
                    .withMessageContaining("examName");
            assertThatNullPointerException()
                    .isThrownBy(() -> new CheckedForm(approvedRow(), "Midterm", null, "Dana Cohen",
                            AttemptState.SUBMITTED, 45, List.of()))
                    .withMessageContaining("courseCode");
        }

        @Test
        @DisplayName("a missing teacher name is the empty string rather than a refusal (A6)")
        void nullTeacherNameBecomesEmpty() {
            // Not required, unlike the three above: the server sends "" when it cannot resolve
            // the releasing teacher, and null is normalised to the same thing so no screen has
            // two absences to tell apart and the word "null" cannot reach a marked paper.
            assertThat(new CheckedForm(approvedRow(), "Midterm", "11", null,
                    AttemptState.SUBMITTED, 45, List.of()).teacherName()).isEmpty();
        }
    }

    @Nested
    @DisplayName("lists")
    class ListHandling {

        @Test
        @DisplayName("every list defaults to empty rather than null")
        void nullListsBecomeEmpty() {
            assertThat(new GradingQueue(null).executions()).isEmpty();
            assertThat(new ExecutionGrades(summary(), null).rows()).isEmpty();
            assertThat(new GradeReview(approvedRow(), null).answers()).isEmpty();
            assertThat(new ApproveRequest(null).gradeIds()).isEmpty();
            assertThat(new ApproveResult(0, 0, null).refused()).isEmpty();
            assertThat(new MyGrades(null).grades()).isEmpty();
            assertThat(new CheckedForm(approvedRow(), "Midterm", "11", "Dana Cohen",
                    AttemptState.SUBMITTED, 45, null).answers()).isEmpty();
        }

        @Test
        @DisplayName("the caller's list is copied, so later mutation cannot reach the DTO")
        void listsAreCopied() {
            List<Long> mutable = new ArrayList<>(List.of(9L, 10L));

            ApproveRequest request = new ApproveRequest(mutable);
            mutable.add(11L);

            assertThat(request.gradeIds()).containsExactly(9L, 10L);
        }

        @Test
        @DisplayName("the copies handed out are immutable, so a client cannot edit a page it was given")
        void listsAreImmutable() {
            assertThatThrownBy(() -> new GradingQueue(List.of(summary())).executions().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new MyGrades(List.of(approvedRow())).grades().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> new ApproveResult(1, 0, List.of(9L)).refused().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null element in a list is rejected rather than carried onto the wire")
        void nullElementsAreRejected() {
            // List.copyOf is what does this, and it is worth pinning: a null row would
            // deserialize fine on the client and then fail inside a table cell factory.
            List<ExecutionGradingSummary> withNull = Arrays.asList(summary(), null);

            assertThatNullPointerException()
                    .isThrownBy(() -> new GradingQueue(withNull));
        }

        @Test
        @DisplayName("negative counts on an approve result are clamped")
        void approveResultClampsCounts() {
            ApproveResult result = new ApproveResult(-2, -1, List.of());

            assertThat(result.approved()).isZero();
            assertThat(result.alreadyApproved()).isZero();
            assertThat(result.isComplete()).isTrue();
        }
    }

    @Nested
    @DisplayName("the student wire")
    class StudentWire {

        @Test
        @DisplayName("MyGrades strips the override justification from every row it is given")
        void myGradesStripsTheJustification() {
            // The contract's rule, made structural: the justification is teacher and audit
            // material. A handler assembling this list from a teacher-side query still
            // cannot leak it.
            StudentGradeRow teacherSide = new StudentGradeRow(9L, 2001L, "Maya Levi", 72, 80, 80,
                    GradeState.APPROVED, "Question 4 was ambiguous", "Well done", APPROVED_AT);

            MyGrades mine = new MyGrades(List.of(teacherSide));

            assertThat(mine.grades()).hasSize(1);
            assertThat(mine.grades().get(0).overrideReason()).isNull();
            assertThat(mine.grades().get(0).teacherComment())
                    .as("the comment is the student's half of the story and stays")
                    .isEqualTo("Well done");
            assertThat(mine.grades().get(0).effectiveScore())
                    .as("the score the teacher set is still the one that counts")
                    .isEqualTo(80);
            assertThat(teacherSide.overrideReason())
                    .as("the caller's own row is untouched; records are values")
                    .isEqualTo("Question 4 was ambiguous");
        }

        @Test
        @DisplayName("CheckedForm strips the justification exactly as MyGrades does")
        void checkedFormStripsTheJustificationToo() {
            StudentGradeRow teacherSide = new StudentGradeRow(9L, 2001L, "Maya Levi", 72, 80, 80,
                    GradeState.APPROVED, "Question 4 was ambiguous", "Well done", APPROVED_AT);

            CheckedForm form = new CheckedForm(teacherSide, "Algebra Midterm", "11", "Dana Cohen", AttemptState.SUBMITTED,
                    45, List.of());

            // Both student containers enforce the same structural rule; a CheckedForm
            // that kept the justification would leak through the second door.
            assertThat(form.grade().overrideReason()).isNull();
            assertThat(form.grade().teacherComment()).isEqualTo(teacherSide.teacherComment());
        }

        @Test
        @DisplayName("a row that never had a justification is passed through unchanged")
        void untouchedRowsAreNotRebuilt() {
            StudentGradeRow clean = approvedRow();

            assertThat(new MyGrades(List.of(clean)).grades().get(0)).isSameAs(clean);
        }

        @Test
        @DisplayName("the empty constants are empty, and say so")
        void emptyConstants() {
            assertThat(MyGrades.EMPTY.isEmpty()).isTrue();
            assertThat(MyGrades.EMPTY.size()).isZero();
            assertThat(GradingQueue.EMPTY.isEmpty()).isTrue();
            assertThat(GradingQueue.EMPTY.size()).isZero();
        }
    }

    @Nested
    @DisplayName("derived answers")
    class Derived {

        @Test
        @DisplayName("an unanswered question is the null chosen option, not a fifth value")
        void unansweredIsNull() {
            AnswerReviewRow blank = new AnswerReviewRow(1, "112001", "q", "a", "b", "c", "d",
                    25, null, (byte) 2, false, 0);
            AnswerReviewRow answered = new AnswerReviewRow(1, "112001", "q", "a", "b", "c", "d",
                    25, (byte) 0, (byte) 0, true, 25);

            assertThat(blank.isUnanswered()).isTrue();
            assertThat(answered.isUnanswered())
                    .as("option 0 is a real choice, not a missing one")
                    .isFalse();
        }

        @Test
        @DisplayName("an execution is fully approved only when every participant's grade is")
        void fullyApproved() {
            assertThat(new ExecutionGradingSummary(1L, "Midterm", "11", "7391", APPROVED_AT,
                    28, 28, 28).isFullyApproved()).isTrue();
            assertThat(new ExecutionGradingSummary(1L, "Midterm", "11", "7391", APPROVED_AT,
                    28, 28, 27).isFullyApproved()).isFalse();
            assertThat(new ExecutionGradingSummary(1L, "Midterm", "11", "7391", APPROVED_AT,
                    0, 0, 0).isFullyApproved())
                    .as("an execution nobody sat is not a completed one")
                    .isFalse();
        }

        @Test
        @DisplayName("one grade and many grades are the same request")
        void approveOne() {
            assertThat(ApproveRequest.one(9L).gradeIds()).containsExactly(9L);
            assertThat(ApproveRequest.one(9L).size()).isEqualTo(1);
            assertThat(new ApproveRequest(List.of()).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the composites report their sizes without a caller reaching into the list")
        void sizes() {
            AnswerReviewRow answer = new AnswerReviewRow(1, "112001", "q", "a", "b", "c", "d",
                    25, (byte) 1, (byte) 1, true, 25);

            assertThat(new ExecutionGrades(summary(), List.of(approvedRow())).size()).isEqualTo(1);
            assertThat(new ExecutionGrades(summary(), List.of()).isEmpty()).isTrue();
            assertThat(new GradeReview(approvedRow(), List.of(answer)).size()).isEqualTo(1);
            assertThat(new CheckedForm(approvedRow(), "Midterm", "11", "Dana Cohen", AttemptState.SUBMITTED, 45,
                    List.of(answer)).size())
                    .isEqualTo(1);
            assertThat(new GradingQueue(List.of(summary())).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("the override range the handler enforces is stated once, here")
        void overrideRangeIsNamed() {
            assertThat(GradeOverrideRequest.MIN_SCORE).isZero();
            assertThat(GradeOverrideRequest.MAX_SCORE).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("the override request's comment (amendment A3, S-22)")
    class OverrideComment {

        @Test
        @DisplayName("blank collapses to null, so there is one shape for 'she wrote nothing'")
        void blankBecomesNull() {
            // Null is not cosmetic here: the service acts on it. A comment of three spaces
            // reaching the server as a non-null value would overwrite a real comment with
            // whitespace, which is the null-preserves rule failing by the back door.
            assertThat(new GradeOverrideRequest(9L, 80, "why", "   ").teacherComment()).isNull();
            assertThat(new GradeOverrideRequest(9L, 80, "why", "").teacherComment()).isNull();
            assertThat(new GradeOverrideRequest(9L, 80, "why", null).teacherComment()).isNull();
            assertThat(new GradeOverrideRequest(9L, 80, "why", "\t\n ").teacherComment())
                    .as("strip, not trim: whitespace is more than the ASCII control range")
                    .isNull();
        }

        @Test
        @DisplayName("a real comment is kept, stripped of the whitespace around it")
        void realCommentIsStripped() {
            GradeOverrideRequest ask =
                    new GradeOverrideRequest(9L, 80, "why", "  שיפור ניכר. \n");

            assertThat(ask.teacherComment()).isEqualTo("שיפור ניכר.");
            assertThat(ask.hasComment()).isTrue();
        }

        @Test
        @DisplayName("the v1 three-component constructor still means what it meant")
        void oldArityStillCompilesAndMeansNoComment() {
            // Every call site written before the amendment goes through this. If it ever
            // stopped delegating with null, the null-preserves rule would silently become
            // "every legacy override clears the comment".
            GradeOverrideRequest ask = new GradeOverrideRequest(9L, 80, "Question 4 was ambiguous");

            assertThat(ask.teacherComment()).isNull();
            assertThat(ask.hasComment()).isFalse();
            assertThat(ask.justification()).isEqualTo("Question 4 was ambiguous");
            assertThat(ask).isEqualTo(new GradeOverrideRequest(9L, 80, "Question 4 was ambiguous",
                    null));
        }

        @Test
        @DisplayName("the justification is left exactly as sent; only the comment is normalised")
        void justificationIsUntouched() {
            // The frozen semantics: a blank justification is the handler's VALIDATION answer,
            // not something this record quietly turns into null. Normalising it here would
            // move a refusal a teacher can read into an NPE she cannot.
            GradeOverrideRequest ask = new GradeOverrideRequest(9L, 80, "  ", "a comment");

            assertThat(ask.justification()).isEqualTo("  ");
        }
    }

    private static StudentGradeRow approvedRow() {
        return new StudentGradeRow(9L, 2001L, "Maya Levi", 72, null, 72,
                GradeState.APPROVED, null, "Well done", APPROVED_AT);
    }

    private static ExecutionGradingSummary summary() {
        return new ExecutionGradingSummary(4821L, "Midterm", "11", "7391", APPROVED_AT, 28, 28, 3);
    }
}
