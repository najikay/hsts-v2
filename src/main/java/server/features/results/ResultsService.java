package server.features.results;

import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.entities.User;
import server.db.projections.GradeExamLabel;
import server.db.repos.GradeRepository;
import server.db.repos.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // v1.1: every row here is a different exam, so each carries its own label. One read for
        // the whole list, paired by grade id.
        List<Long> gradeIds = new ArrayList<>(approved.size());
        for (Grade grade : approved) {
            gradeIds.add(grade.getId());
        }
        Map<Long, GradeExamLabel> labels = grades.findExamLabels(session, gradeIds);

        // A7: the third label is a person. One lookup per distinct teacher, memoised, because a
        // term's grades are a handful of exams set by two or three people.
        Map<Long, String> teacherNames = new HashMap<>();

        List<StudentGradeRow> rows = new ArrayList<>(approved.size());
        for (Grade grade : approved) {
            GradeExamLabel exam = labels.get(grade.getId());
            rows.add(label(toRow(grade, studentId, studentName), exam,
                    teacherName(session, exam, teacherNames)));
        }
        return new MyGrades(rows);
    }

    /**
     * Applies the exam labels to a row, if one resolved.
     *
     * <p>An unlabelled row keeps its nulls rather than borrowing a neighbour's exam name or
     * inventing a placeholder. A grade whose joins do not resolve is a data problem worth
     * seeing as a blank, not one worth papering over with the wrong exam's name on a
     * student's transcript. The teacher's name travels with them for the same reason it is a
     * label at all: it is read off the same execution, and a name from anywhere else would be
     * a name from another exam.
     */
    private static StudentGradeRow label(StudentGradeRow row, GradeExamLabel exam,
                                         String teacherName) {
        return exam == null ? row : row.withExam(exam.examName(), exam.courseCode(), teacherName);
    }

    /**
     * The name of the teacher who released the sitting this grade belongs to (A7, 2026-08-29).
     *
     * <p>{@code GradeExamLabel.teacherId} is {@code exam_executions.created_by}, the same
     * definition A6 gave the checked form, so the card and the paper it opens name the same
     * person. The lookup is the one {@link #myGrades} uses for the student's own name —
     * {@code findById(...).map(User::getFullName)} — because one way to turn a user id into a
     * display name is what keeps two screens from spelling one teacher differently.
     *
     * <p>Empty rather than a placeholder when the row has gone, and empty for an unlabelled
     * grade: the wire says "unresolvable" and the card omits the line. A transcript is not the
     * place to explain a missing join.
     *
     * @param session the current session
     * @param exam    the row's labels, or {@code null} when its joins did not resolve
     * @param cache   names already looked up in this call, so a term of grades from one teacher
     *                is one read rather than one per grade
     * @return the teacher's full name, or {@code ""}
     */
    private String teacherName(Session session, GradeExamLabel exam, Map<Long, String> cache) {
        if (exam == null) {
            return "";
        }
        return cache.computeIfAbsent(exam.teacherId(), id -> users.findById(session, id)
                .map(User::getFullName)
                .orElse(""));
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
