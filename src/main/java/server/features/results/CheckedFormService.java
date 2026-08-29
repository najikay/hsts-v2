package server.features.results;

import common.dto.exam.AttemptState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.entities.User;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.UserRepository;
import server.features.grading.GradeReviewService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A student's own marked paper, behind three gates (Logic tier, E13.4 ⚑ — F9.1, T-9.2, S-24).
 *
 * <p><b>This is the only path by which correctness data reaches a student</b>, which makes it
 * the most heavily gated read in the product and the one most worth reading twice.
 *
 * <h2>The three conditions</h2>
 *
 * <ol>
 *   <li><b>The grade is hers.</b> Not checked — <em>queried</em>. The read is
 *       {@link ResultsService#findOwnGrade}, which filters on the student id in SQL, so there is
 *       no code path that loads somebody else's paper and then remembers to drop it. This is
 *       deliberately not {@code GradeRepository.findByIdUnscoped}, which exists for the teacher
 *       verbs and whose name says why a student path must not touch it.</li>
 *   <li><b>The grade is {@code APPROVED}.</b> An auto-graded paper is not a result yet (C-3,
 *       S-24). Serving one would show a student a score before her teacher had stood behind it,
 *       and would show it complete with the answer key.</li>
 *   <li><b>The execution is closed.</b> The gate that is easy to forget and the one that
 *       matters most: while a sitting is open, handing one student the key is handing it to
 *       everyone still in the room.</li>
 * </ol>
 *
 * <h2>One answer for all three</h2>
 *
 * <p>Every refusal is the same empty {@link Optional}, and the handler turns it into one
 * {@code NOT_FOUND}. A student must not be able to tell "not yours" from "not approved yet"
 * from "still open" from "no such grade" — three distinguishable answers would let her probe
 * for which grades exist and what state they are in, which is the membership oracle E13.1
 * exists to prevent. That is why this class returns an Optional rather than an enum of reasons:
 * an enum would be a shape inviting the handler to say which gate stopped her.
 *
 * <h2>The paper itself is not assembled here</h2>
 *
 * <p>{@link GradeReviewService#answers} builds it, the same method {@code GRADE_REVIEW_GET}
 * uses. One assembler, two verbs, two different gates in front of it — so there is exactly one
 * place in the product where an answer key is turned into rows, and a change to how a paper is
 * marked cannot land for a teacher and miss a student.
 */
public class CheckedFormService {

    private static final Logger log = LoggerFactory.getLogger(CheckedFormService.class);

    private final ResultsService results;
    private final GradeReviewService reviews;
    private final AttemptRepository attempts;
    private final ExecutionRepository executions;
    private final UserRepository users;

    public CheckedFormService(ResultsService results,
                              GradeReviewService reviews,
                              AttemptRepository attempts,
                              ExecutionRepository executions,
                              UserRepository users) {
        this.results = Objects.requireNonNull(results, "results");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * The student's marked paper, if all three conditions hold.
     *
     * @param session   the current session, inside a transaction
     * @param studentId the authenticated caller — never taken from the payload
     * @param gradeId   the grade she asked to open
     * @return her checked form, or empty when any gate refuses. The caller must not report
     *         which one
     */
    public Optional<CheckedForm> checkedForm(Session session, long studentId, long gradeId) {
        Objects.requireNonNull(session, "session");

        // Gate 1: ownership, as the query.
        Optional<Grade> owned = results.findOwnGrade(session, gradeId, studentId);
        if (owned.isEmpty()) {
            log.debug("Checked form refused: grade {} is not student {}'s, or does not exist",
                    gradeId, studentId);
            return Optional.empty();
        }
        Grade grade = owned.get();

        // Gate 2: approved. Auto-grading publishes nothing.
        if (grade.getStatus() != GradeStatus.APPROVED) {
            log.debug("Checked form refused: grade {} is {} and not approved",
                    gradeId, grade.getStatus());
            return Optional.empty();
        }

        Optional<AttemptRecord> attempt = attempts.findRecordById(session, grade.getAttemptId());
        if (attempt.isEmpty()) {
            return Optional.empty();
        }
        Optional<ExecutionContext> execution =
                executions.findContext(session, attempt.get().executionId());
        if (execution.isEmpty()) {
            return Optional.empty();
        }

        // Gate 3: the sitting is over. Handing one student the key while others are still
        // sitting the same paper hands it to the room.
        if (execution.get().status() != ExecutionStatus.CLOSED) {
            log.info("Checked form refused: execution {} is {} and not closed",
                    execution.get().executionId(), execution.get().status());
            return Optional.empty();
        }

        return Optional.of(assemble(session, grade, attempt.get(), execution.get(), studentId));
    }

    /**
     * Builds the form once every gate has passed.
     *
     * <p>The rows come from {@link GradeReviewService}, and the header is built here rather than
     * reused from its {@code teacherRow} on purpose: that one carries {@code overrideReason},
     * and while {@link CheckedForm} strips it structurally, handing a student wire a row that
     * had it in the first place would leave the structural strip as the only defence. Two
     * defences, as everywhere else on this path.
     */
    private CheckedForm assemble(Session session, Grade grade, AttemptRecord attempt,
                                 ExecutionContext execution, long studentId) {
        String studentName = users.findById(session, studentId)
                .map(User::getFullName)
                .orElse("");
        // A7: the same name the form's own header line uses, resolved once and put on both, so
        // the row a student's list shows and the paper she opens cannot name two people.
        String teacherName = teacherName(session, execution);

        StudentGradeRow header = new StudentGradeRow(
                grade.getId(),
                studentId,
                studentName,
                grade.getAutoScore(),
                grade.getFinalScore(),
                grade.getEffectiveScore(),
                GradeState.APPROVED,
                // Never the justification, on any student path (S-23).
                null,
                grade.getTeacherComment(),
                grade.getApprovedAt(),
                execution.examName(),
                execution.courseCode(),
                teacherName);

        List<AnswerReviewRow> answers = reviews.answers(session,
                new GradeReviewService.ReviewContext(grade, attempt, execution));

        return new CheckedForm(header, execution.examName(), execution.courseCode(),
                teacherName, wireState(attempt.status()),
                attempt.actualMinutes(), answers);
    }

    /**
     * The name of the teacher who released this sitting (A6, 2026-08-28).
     *
     * <p>{@code executingTeacherId} is {@code exam_executions.created_by}, and that is the
     * teacher the student should see: one sitting has exactly one releasing teacher, whereas a
     * grade can be overridden by somebody else without changing whose paper it was. The same
     * lookup the student's own name goes through two lines above, because a second way to turn
     * a user id into a display name is a second way for two screens to disagree.
     *
     * <p>Empty rather than a placeholder when the row has gone. The wire says "unresolvable"
     * and the screen decides what to do about it, which here is to omit the line — a marked
     * paper is not the place to explain a missing join.
     */
    private String teacherName(Session session, ExecutionContext execution) {
        return users.findById(session, execution.executingTeacherId())
                .map(User::getFullName)
                .orElse("");
    }

    /**
     * The stored attempt status as the wire spells it (checked-form amendment, 9.5).
     *
     * <p>{@code IN_PROGRESS} cannot reach here — an unfinished attempt has no approved grade —
     * but it is mapped rather than rejected, because a service that threw on an impossible
     * value would turn a data oddity into a failed screen.
     */
    private static AttemptState wireState(AttemptStatus status) {
        return switch (status) {
            case SUBMITTED -> AttemptState.SUBMITTED;
            case TIMED_OUT -> AttemptState.TIMED_OUT;
            case IN_PROGRESS -> AttemptState.IN_PROGRESS;
        };
    }
}
