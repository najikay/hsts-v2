package common.dto.grading;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code MY_GRADES_GET}: a student's own published results (Common tier, E13.1).
 *
 * <p>The request carries no payload. <b>Whose</b> grades these are is the session on the
 * socket, always, scoped in the query itself ({@code WHERE student_id = :caller}) rather than
 * by a check above it — the same silent-scoping pattern as notifications, and the reason
 * naming somebody else's grade id elsewhere in this contract answers {@code NOT_FOUND} instead
 * of confirming it exists.
 *
 * <p>Only {@link GradeState#APPROVED} rows appear. A grade a teacher has not released does not
 * show up as "pending": it does not show up at all (S-24, C-3).
 *
 * <h2>This record strips the override justification</h2>
 *
 * <p>The contract states that {@code overrideReason} is always null on the student wire — the
 * justification is teacher and audit material, and what a student is meant to read is
 * {@code teacherComment}. The compact constructor below rebuilds any row that arrived with one
 * so that the guarantee is <b>structural</b> rather than a rule a future handler has to
 * remember while assembling rows from a teacher-side query. It costs one pass over a list of
 * tens of rows and removes a whole class of leak.
 *
 * <p>No per-question data here, by decision: the checked form is its own verb
 * ({@code CHECKED_FORM_GET}) with its own three gates, so correctness is never carried by the
 * list a student loads on every dashboard visit.
 *
 * @param grades the student's approved grades; never {@code null}, defensively copied, and
 *               every row's {@code overrideReason} forced to {@code null}
 */
public record MyGrades(List<StudentGradeRow> grades) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What a student with no published results gets. */
    public static final MyGrades EMPTY = new MyGrades(List.of());

    public MyGrades {
        // toList() yields an immutable, Serializable list — safe on the wire, and this pass
        // is also what enforces the "no justification on the student wire" rule.
        grades = grades == null ? List.of() : grades.stream().map(StudentGradeRow::withoutJustification).toList();
    }

    public boolean isEmpty() {
        return grades.isEmpty();
    }

    public int size() {
        return grades.size();
    }

}
