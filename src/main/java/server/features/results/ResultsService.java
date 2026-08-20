package server.features.results;

import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.entities.User;
import server.db.repos.GradeRepository;
import server.db.repos.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Serves a student their own results, and nothing else (Logic tier, E13.1).
 *
 * <p>E13.1 is defence-critical for one reason: a student reaching another student's grade is the
 * failure that ends a demo. The defence here is that <b>ownership is the query</b>, never a check
 * applied afterwards — {@link GradeRepository#findApprovedForStudent} and
 * {@link GradeRepository#findForStudent} both filter on the student id in SQL, so there is no
 * code path that loads someone else's row and then remembers to drop it. A forgotten check is a
 * bug; a filter that was never written cannot be forgotten.
 *
 * <p>The student id always comes from the authenticated session
 * ({@code CallerContext.userId()}), never from a payload. That is why every method here takes it
 * as a parameter rather than reading a request object: a handler physically cannot pass an id
 * the caller supplied, because the request DTOs do not carry one.
 *
 * <p><b>Someone else's grade id is {@code NOT_FOUND}, not {@code FORBIDDEN}.</b> The two answers
 * would let an attacker distinguish "this grade exists but is not yours" from "no such grade",
 * which is a membership oracle. {@link #findOwnGrade} returns an empty Optional for both, and
 * the handler turns that into one answer.
 */
public class ResultsService {

    private final GradeRepository grades;
    private final UserRepository users;

    public ResultsService(GradeRepository grades, UserRepository users) {
        this.grades = Objects.requireNonNull(grades, "grades");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * Every approved grade belonging to this student.
     *
     * <p>Unapproved grades are absent, not hidden: auto-checking publishes nothing until a
     * teacher approves (C-3, S-24), so a student polling this during marking sees the same empty
     * list they saw before it started.
     *
     * @param session   the current session
     * @param studentId the authenticated caller
     * @return the student's approved grades; {@link MyGrades#EMPTY} when there are none
     * @throws IllegalStateException if the caller does not exist
     */
    public MyGrades myGrades(Session session, long studentId) {
        Objects.requireNonNull(session, "session");

        String studentName = users.findById(session, studentId)
                .map(User::getFullName)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user " + studentId + " has no row"));

        List<Grade> approved = grades.findApprovedForStudent(session, studentId);
        if (approved.isEmpty()) {
            return MyGrades.EMPTY;
        }

        List<StudentGradeRow> rows = new ArrayList<>(approved.size());
        for (Grade grade : approved) {
            rows.add(toRow(grade, studentId, studentName));
        }
        return new MyGrades(rows);
    }

    /**
     * One grade, if it is this student's.
     *
     * <p>The gate for the checked form (E13.4) starts here: ownership. The other two conditions —
     * the grade is approved and the execution is closed — belong to that handler, because only it
     * knows what it is about to serve.
     *
     * @param session   the current session
     * @param gradeId   the requested grade
     * @param studentId the authenticated caller
     * @return the grade, or empty when it does not exist <b>or</b> is not theirs
     */
    public Optional<Grade> findOwnGrade(Session session, long gradeId, long studentId) {
        Objects.requireNonNull(session, "session");
        return grades.findForStudent(session, gradeId, studentId);
    }

    /**
     * Maps a stored grade to its wire row.
     *
     * <p>{@code overrideReason} is passed through as null rather than as the stored text: the
     * justification is teacher and audit material (S-23) and the student sees the comment
     * instead (S-22). {@link MyGrades} strips it structurally as well, so this is the first of
     * two independent defences rather than the only one.
     */
    private static StudentGradeRow toRow(Grade grade, long studentId, String studentName) {
        return new StudentGradeRow(
                grade.getId(),
                studentId,
                studentName,
                grade.getAutoScore(),
                grade.getFinalScore(),
                grade.getEffectiveScore(),
                grade.getStatus() == GradeStatus.APPROVED ? GradeState.APPROVED : GradeState.AUTO,
                null,
                grade.getTeacherComment(),
                grade.getApprovedAt());
    }
}
