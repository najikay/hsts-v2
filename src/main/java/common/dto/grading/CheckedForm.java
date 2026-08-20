package common.dto.grading;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code CHECKED_FORM_GET}: a student's own marked paper (Common tier, E13.2).
 *
 * <p>The only way correctness reaches a student, and it reaches them only when all three of
 * the contract's conditions hold: the grade is theirs, it is {@link GradeState#APPROVED}, and
 * the execution is closed. The E13.1 authorization tests are what license this type to exist
 * at all, and they are also what licenses the {@code …ForCheckedForm} repository reads behind
 * it (see {@code CorrectnessLeakGuardTest}).
 *
 * <p>It reuses {@link AnswerReviewRow} rather than declaring a student-shaped copy. One row
 * shape for both audiences, gated by verb, means there is exactly one place an answer key is
 * serialized and two guards standing in front of it — two shapes would mean two places, and
 * the second one would be the one nobody re-read.
 *
 * <p>{@code examName} and {@code courseCode} are carried because a student opening a result
 * from a notification has no queue row to inherit a header from.
 *
 * @param grade      the student's grade header; {@code overrideReason} is stripped
 *                   structurally, exactly as {@link MyGrades} does
 * @param examName   the exam this paper belongs to
 * @param courseCode the course it was taken in
 * @param answers    the marked paper, in exam order; never {@code null}, defensively copied
 */
public record CheckedForm(StudentGradeRow grade,
                          String examName,
                          String courseCode,
                          List<AnswerReviewRow> answers) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CheckedForm {
        Objects.requireNonNull(grade, "grade");
        // Same structural rule as MyGrades: this is a student wire, and the override
        // justification is teacher and audit material.
        grade = grade.withoutJustification();
        Objects.requireNonNull(examName, "examName");
        Objects.requireNonNull(courseCode, "courseCode");
        // List.copyOf yields an immutable, Serializable list — safe on the wire.
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public int size() {
        return answers.size();
    }
}
