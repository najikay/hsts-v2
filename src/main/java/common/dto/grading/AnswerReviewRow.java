package common.dto.grading;

import java.io.Serializable;
import java.util.Objects;

/**
 * One question of a marked paper, with what was chosen and what was right (Common tier,
 * E12.3/E13.2).
 *
 * <p><b>This is the one type in the contract that carries an answer key.</b> It reaches a
 * teacher through {@code GRADE_REVIEW_GET} and a student only through
 * {@code CHECKED_FORM_GET}, and only when all three of the contract's conditions hold: the
 * grade is theirs, it is {@link GradeState#APPROVED}, and the execution is closed.
 *
 * <p>The reuse is deliberate. Two audiences reading two nearly identical records would mean
 * two places correctness is serialized, and the second one would be the one nobody re-read.
 * One row shape gated by verb leaves exactly one place, with two guards in front of it. The
 * repository reads that build it are named {@code …ForCheckedForm} for the same reason the
 * authoring reads are named {@code …ForAuthoring}: so a student-facing caller cannot reach
 * for one by accident (see {@code CorrectnessLeakGuardTest}).
 *
 * <p>{@code chosen} is a boxed {@link Byte} because {@code null} means the student never
 * answered — a real outcome, scored zero (§6), and distinct from choosing option 0.
 * {@code correct} is a plain {@code byte}: every question has a right answer.
 *
 * @param ordinal       position in the exam, 1-based, so a client never re-numbers a list
 * @param displayId     the question's six-digit display id, which is what a teacher discusses
 * @param questionText  the question as it was asked
 * @param answer1       first option
 * @param answer2       second option
 * @param answer3       third option
 * @param answer4       fourth option
 * @param points        what this question was worth in this exam version
 * @param chosen        the option the student picked, or {@code null} when unanswered
 * @param correct       the option that was right
 * @param isCorrect     whether the student got it, computed by the server so no client has to
 *                      compare a boxed byte to a primitive one
 * @param pointsAwarded what this question actually contributed
 */
public record AnswerReviewRow(int ordinal,
                              String displayId,
                              String questionText,
                              String answer1,
                              String answer2,
                              String answer3,
                              String answer4,
                              int points,
                              Byte chosen,
                              byte correct,
                              boolean isCorrect,
                              int pointsAwarded) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AnswerReviewRow {
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(questionText, "questionText");
    }

    /** @return {@code true} when the student left this question blank (scored 0, §6). */
    public boolean isUnanswered() {
        return chosen == null;
    }
}
