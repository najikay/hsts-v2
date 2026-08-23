package common.dto.grading;

import common.dto.exam.AttemptState;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * The answer to {@code CHECKED_FORM_GET}: a student's own marked paper (Common tier, E13.2).
 *
 * <p>The only way correctness reaches a student, and it reaches them only when all three of
 * the contract's conditions hold: the grade is theirs, it is {@link GradeState#APPROVED}, and
 * the execution is closed. The E13.1 authorization tests are what license this type to exist
 * at all, and they are the whole licence: the read behind it is the shared {@code …ForGrading}
 * one, because this form is assembled by {@code GradeReviewService} rather than by a student
 * path of its own. The {@code …ForCheckedForm} suffix reserved for it at the freeze was
 * withdrawn on 2026-08-23 for exactly that reason (see {@code CorrectnessLeakGuardTest}).
 *
 * <p>It reuses {@link AnswerReviewRow} rather than declaring a student-shaped copy. One row
 * shape for both audiences, gated by verb, means there is exactly one place an answer key is
 * serialized and two guards standing in front of it — two shapes would mean two places, and
 * the second one would be the one nobody re-read.
 *
 * <p>{@code examName} and {@code courseCode} are carried because a student opening a result
 * from a notification has no queue row to inherit a header from.
 *
 * <h2>Checked-form amendment (2026-08-22, additive) — {@code attemptStatus} and
 * {@code actualMinutes}</h2>
 *
 * <p>Acceptance case 9.5 asks that a student whose attempt was force-submitted sees that it was
 * timed out, with the solving time actually recorded (S-19). Neither fact was anywhere on the
 * grading wire, so the case could not pass.
 *
 * <p><b>They land here rather than on {@link StudentGradeRow}</b>, by the lead's ruling of
 * 2026-08-22: the case says "open his result and <em>see</em>", and the seeing happens on the
 * marked paper. Solving time belongs beside the answers it measures, and the list wire pays
 * nothing for data most of its rows would never show. {@code StudentGradeRow} therefore stays
 * at v1.1.
 *
 * <p>{@code actualMinutes} is boxed because it is genuinely absent for an attempt that was never
 * recorded, which is a different fact from having taken zero minutes.
 *
 * @param grade         the student's grade header; {@code overrideReason} is stripped
 *                      structurally, exactly as {@link MyGrades} does
 * @param examName      the exam this paper belongs to
 * @param courseCode    the course it was taken in
 * @param attemptStatus how the attempt ended — {@code SUBMITTED} when she handed in,
 *                      {@code TIMED_OUT} when the server did it for her (9.5)
 * @param actualMinutes recorded solving time (S-19), or {@code null} when none was recorded
 * @param answers       the marked paper, in exam order; never {@code null}, defensively copied
 */
public record CheckedForm(StudentGradeRow grade,
                          String examName,
                          String courseCode,
                          AttemptState attemptStatus,
                          Integer actualMinutes,
                          List<AnswerReviewRow> answers) implements Serializable {

    private static final long serialVersionUID = 2L;

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

    /**
     * @return {@code true} when the server submitted this paper on the student's behalf at
     *         expiry (F6.4). It was sat and scored like any other; the screen says so rather
     *         than leaving her to wonder why she never pressed anything
     */
    public boolean wasTimedOut() {
        return attemptStatus == AttemptState.TIMED_OUT;
    }

    public int size() {
        return answers.size();
    }
}
