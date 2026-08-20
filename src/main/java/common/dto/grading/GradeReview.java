package common.dto.grading;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code GRADE_REVIEW_GET} and to {@code GRADE_OVERRIDE} (Common tier,
 * E12.3/E12.5).
 *
 * <p><b>Teacher-only.</b> It carries {@link AnswerReviewRow}s, which hold the answer key, so
 * nothing in E13 ever produces this type: a student reaching the same data does it through
 * {@link CheckedForm} and its three gates.
 *
 * <p>An override answers with this same shape, refreshed, rather than with an acknowledgement:
 * the teacher's screen then shows the new effective score and the new state from the server's
 * own read, with no client-side patching of a row it already had.
 *
 * @param grade   the grade header, teacher-side, so {@code overrideReason} is populated
 * @param answers the marked paper, in exam order; never {@code null}, defensively copied
 */
public record GradeReview(StudentGradeRow grade, List<AnswerReviewRow> answers) implements Serializable {

    private static final long serialVersionUID = 1L;

    public GradeReview {
        Objects.requireNonNull(grade, "grade");
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public int size() {
        return answers.size();
    }
}
