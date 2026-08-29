package server.features.grading;

import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import server.db.entities.ExecutionStatus;
import server.db.entities.GradeStatus;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What is waiting to be graded, and one sitting opened (Logic tier, E12.5/E12.6).
 *
 * <p>Two reads that build the teacher's side of grading: the queue she lands on, and the table
 * she opens from it. Both are assembly over reads that already exist — nothing here computes a
 * score or decides a state.
 *
 * <h2>What belongs in the queue</h2>
 *
 * <p>Executions that are <b>closed</b> and have at least one grade she has not approved.
 *
 * <p>Closed matters because grading an exam people are still sitting is not a task yet, and a
 * queue that offered it would invite a teacher to approve half a class and wonder where the rest
 * went. Unapproved matters because a finished execution is not waiting on her — it is history,
 * and history belongs on E14's results screen rather than in a to-do list. A queue that never
 * empties stops being read.
 *
 * <h2>Ownership is the same rule as everywhere else in E12</h2>
 *
 * <p>The executions come from {@code findContextsByExamAuthor}, so the queue is scoped to the
 * exams she wrote. {@link #executionGrades} additionally re-checks ownership on the execution it
 * is handed, because a queue row is not a capability: a teacher who guesses another execution's
 * id must be refused exactly as if she had asked for one that does not exist.
 *
 * <p><b>The author, not only the releasing teacher.</b> {@code ExecutionContext.isOwnedBy}
 * accepts either (S-35), which is the contract's rule for every teacher verb and the reason a
 * colleague running your exam does not lock you out of grading it.
 */
public class GradingQueueService {

    private final ExecutionRepository executions;
    private final GradeRepository grades;
    private final AttemptRepository attempts;

    public GradingQueueService(ExecutionRepository executions,
                               GradeRepository grades,
                               AttemptRepository attempts) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.grades = Objects.requireNonNull(grades, "grades");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    /**
     * The teacher's grading queue.
     *
     * @param session   the current session, inside a transaction
     * @param teacherId the authenticated caller — never taken from the payload
     * @return the executions waiting on her, oldest closing first
     */
    public GradingQueue queue(Session session, long teacherId) {
        Objects.requireNonNull(session, "session");

        List<ExecutionContext> mine = executions.findContextsByExamAuthor(session, teacherId);
        if (mine.isEmpty()) {
            return GradingQueue.EMPTY;
        }

        List<Long> closedIds = new ArrayList<>();
        for (ExecutionContext execution : mine) {
            if (execution.status() == ExecutionStatus.CLOSED) {
                closedIds.add(execution.executionId());
            }
        }
        if (closedIds.isEmpty()) {
            return GradingQueue.EMPTY;
        }

        // Three counts, three reads, whatever the size of the queue.
        Map<Long, Integer> participants = attempts.countAttemptsByExecution(session, closedIds);
        Map<Long, Integer> graded = grades.countGradesByExecution(session, closedIds);
        Map<Long, Integer> approved = grades.countApprovedByExecution(session, closedIds);

        List<ExecutionGradingSummary> waiting = new ArrayList<>();
        for (ExecutionContext execution : mine) {
            long id = execution.executionId();
            if (execution.status() != ExecutionStatus.CLOSED) {
                continue;
            }
            int gradedCount = graded.getOrDefault(id, 0);
            int approvedCount = approved.getOrDefault(id, 0);
            if (gradedCount == 0 || approvedCount >= gradedCount) {
                // Nothing marked yet, or everything signed off. Neither is waiting on her.
                continue;
            }
            waiting.add(summary(execution, participants.getOrDefault(id, 0),
                    gradedCount, approvedCount));
        }

        // Oldest closing first: the sitting that finished longest ago is the one whose students
        // have been waiting most, and a queue ordered newest-first buries it.
        waiting.sort((left, right) -> {
            if (left.closedAt() == null || right.closedAt() == null) {
                return Long.compare(left.executionId(), right.executionId());
            }
            return left.closedAt().compareTo(right.closedAt());
        });
        return new GradingQueue(List.copyOf(waiting));
    }

    /**
     * One execution opened: its summary and every marked student in it.
     *
     * <p>Every grade, not only the unapproved ones. A teacher reviewing a class needs to see
     * what she has already signed off next to what she has not — a table that hid the approved
     * rows would shrink as she worked and give her no way to check a decision she had just made.
     *
     * @param session     the current session, inside a transaction
     * @param teacherId   the authenticated caller
     * @param executionId the execution to open
     * @return its grades, or empty when it does not exist <b>or</b> is not hers — one answer for
     *         both, so a teacher cannot probe for which executions exist
     */
    public Optional<ExecutionGrades> executionGrades(Session session, long teacherId,
                                                     long executionId) {
        Objects.requireNonNull(session, "session");

        Optional<ExecutionContext> found = executions.findContext(session, executionId);
        if (found.isEmpty() || !found.get().isOwnedBy(teacherId)) {
            return Optional.empty();
        }
        ExecutionContext execution = found.get();

        List<StudentResultRow> stored = grades.findResultRows(session, executionId);
        List<StudentGradeRow> rows = new ArrayList<>(stored.size());
        int approvedCount = 0;
        for (StudentResultRow row : stored) {
            if (row.status() == GradeStatus.APPROVED) {
                approvedCount++;
            }
            rows.add(toWire(row));
        }

        // One read. A class is a class: the count is a long because the query returns one,
        // not because a sitting could overflow an int.
        int participants = (int) attempts.countParticipation(session, executionId).started();

        return Optional.of(new ExecutionGrades(
                summary(execution, participants, stored.size(), approvedCount), rows));
    }

    private static ExecutionGradingSummary summary(ExecutionContext execution, int participants,
                                                   int gradedCount, int approvedCount) {
        return new ExecutionGradingSummary(
                execution.executionId(),
                execution.examName(),
                execution.courseCode(),
                execution.code(),
                execution.closeAt(),
                participants,
                gradedCount,
                approvedCount);
    }

    /**
     * The stored row as the teacher's wire spells it.
     *
     * <p>{@code overrideReason} is carried, because this <b>is</b> the teacher wire and the
     * justification is what she wrote (S-23). {@code examName} and {@code courseCode} are left
     * null: v1.1 populates them student-side only, and here the summary above the table already
     * says which exam this is, once, for every row.
     *
     * <p>{@code teacherName} is empty for the same reason and one more: this class holds no
     * {@code UserRepository}, and A7 is not worth a new dependency on a screen where the only
     * teacher in the room is the one reading it (A7, 2026-08-29).
     */
    private static StudentGradeRow toWire(StudentResultRow row) {
        return new StudentGradeRow(
                row.gradeId(),
                row.studentId(),
                row.studentName(),
                row.autoScore(),
                row.finalScore(),
                row.effectiveScore(),
                row.status() == GradeStatus.APPROVED ? GradeState.APPROVED : GradeState.AUTO,
                row.overrideReason(),
                row.teacherComment(),
                row.approvedAt());
    }
}
