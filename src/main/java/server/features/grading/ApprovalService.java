package server.features.grading;

import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.ExecutionStats;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Approving grades, and freezing an execution's statistics when the last one lands
 * (Logic tier, E12.2 / E12.7).
 *
 * <p>One service for single and bulk because the wire contract has one verb for both: approving
 * a row is approving a list of length one, and a second code path would be a second place for
 * the rules below to drift.
 *
 * <h2>The four rules</h2>
 *
 * <p><b>Approval is idempotent.</b> Re-approving an already-approved grade counts in
 * {@code alreadyApproved} and is not an error (§6). A teacher who double-clicks, or whose client
 * retries after a timeout, must not see a failure for work that already succeeded — and must not
 * have {@code approved_at} quietly rewritten to the second click either.
 *
 * <p><b>Partial success is normal.</b> Ids the caller may not approve, and ids that do not
 * exist, both land in {@code refused} — the same answer for both, so the response cannot be used
 * to discover which grades exist. Nothing is rolled back on account of them: approving eight of
 * ten is a real outcome, not a failed transaction.
 *
 * <p><b>Ownership is checked per grade, from repositories.</b> The caller must be the
 * execution's executing teacher or the exam's author (the contract's rule for teacher verbs).
 * A bulk request can span executions, so this is resolved for each id rather than once.
 *
 * <p><b>Completing an execution freezes its statistics.</b> When the last {@code AUTO} grade of
 * an execution becomes {@code APPROVED}, {@link ScoreStatistics} is computed from the final
 * scores and written to {@code exam_executions.stats} <b>in this same transaction</b>. That is
 * E12.4's "→ stored": the numbers a teacher and the report engine read are the ones that existed
 * at the moment grading finished, not a recomputation that could drift as data changes later.
 */
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final GradeRepository grades;
    private final AttemptRepository attempts;
    private final ExecutionRepository executions;
    private final Notifier notifier;
    private final Clock clock;

    public ApprovalService(GradeRepository grades,
                           AttemptRepository attempts,
                           ExecutionRepository executions,
                           Notifier notifier,
                           Clock clock) {
        this.grades = Objects.requireNonNull(grades, "grades");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One student who has just been told their grade is ready. */
    private record Published(long studentId, long attemptId, String examName) {
    }

    /**
     * Approves the requested grades on behalf of {@code teacherId}.
     *
     * @param session   the current session, inside a transaction
     * @param teacherId the authenticated caller — never taken from the payload
     * @param request   the grade ids to approve
     * @return counts of approved and already-approved, plus the ids that were refused
     * @throws NullPointerException if any argument is null
     */
    public ApproveResult approve(Session session, long teacherId, ApproveRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");

        if (request.isEmpty()) {
            return new ApproveResult(0, 0, List.of());
        }

        Instant now = clock.instant();
        List<Grade> found = grades.findByIds(session, request.gradeIds());

        int approved = 0;
        int alreadyApproved = 0;
        List<Long> refused = new ArrayList<>();
        List<Published> published = new ArrayList<>();
        Set<Long> touchedExecutions = new LinkedHashSet<>();

        // Ids that matched no row are refused without a second query: "not yours" and
        // "does not exist" must be indistinguishable in the answer.
        Set<Long> foundIds = new LinkedHashSet<>();
        for (Grade grade : found) {
            foundIds.add(grade.getId());
        }
        for (Long requested : request.gradeIds()) {
            if (!foundIds.contains(requested)) {
                refused.add(requested);
            }
        }

        for (Grade grade : found) {
            Optional<ExecutionContext> context = contextOf(session, grade);
            if (context.isEmpty() || !mayApprove(context.get(), teacherId)) {
                refused.add(grade.getId());
                continue;
            }
            ExecutionContext execution = context.get();

            if (grade.getStatus() == GradeStatus.APPROVED) {
                // Idempotent: counted, not re-stamped. Overwriting approved_at would lose who
                // signed it off and when, which is the audit trail F8.3 exists for.
                alreadyApproved++;
                continue;
            }

            grade.approve(teacherId, now);
            approved++;
            touchedExecutions.add(execution.executionId());
            attemptOf(session, grade).ifPresent(attempt -> published.add(
                    new Published(attempt.studentId(), attempt.attemptId(), execution.examName())));
        }

        for (Long executionId : touchedExecutions) {
            freezeStatisticsIfComplete(session, executionId);
        }

        // Notify last: a student should never be told a grade is ready by a transaction that
        // then rolls back. Nothing after this point can fail the approval.
        for (Published entry : published) {
            notifier.notifyUser(entry.studentId(),
                    NotificationCatalog.gradePublished(entry.examName(), entry.attemptId()));
        }

        log.info("Approved {} grade(s) for teacher {} ({} already approved, {} refused)",
                approved, teacherId, alreadyApproved, refused.size());
        return new ApproveResult(approved, alreadyApproved, List.copyOf(refused));
    }

    /**
     * Writes the frozen statistics when nothing of this execution is left unapproved.
     *
     * <p>Silent when grading is still in progress — a bulk approve that covers half a class is
     * a normal step, not a completion.
     */
    private void freezeStatisticsIfComplete(Session session, long executionId) {
        List<Grade> all = grades.findAllForExecution(session, executionId);
        if (all.isEmpty()) {
            return;
        }
        for (Grade grade : all) {
            if (grade.getStatus() != GradeStatus.APPROVED) {
                return;
            }
        }

        List<Integer> finalScores = new ArrayList<>(all.size());
        for (Grade grade : all) {
            finalScores.add(grade.getEffectiveScore());
        }

        ScoreStatistics.of(finalScores).ifPresent(stats -> executions
                .findById(session, executionId)
                .ifPresent(execution -> {
                    execution.setStats(toEntity(stats));
                    log.info("Execution {} fully approved — froze stats: mean {}, median {}, σ {}",
                            executionId, stats.mean(), stats.median(), stats.standardDeviation());
                }));
    }

    /**
     * {@link ScoreStatistics} is the computation; {@link ExecutionStats} is the stored shape.
     * They are deliberately different types — the stored one drops {@code count} and
     * {@code passCount}, both derivable — and this is the single place they are bridged.
     */
    private static ExecutionStats toEntity(ScoreStatistics stats) {
        return new ExecutionStats(
                stats.mean(),
                stats.median(),
                stats.standardDeviation(),
                stats.min(),
                stats.max(),
                stats.passRate(),
                stats.deciles());
    }

    private Optional<AttemptRecord> attemptOf(Session session, Grade grade) {
        return attempts.findRecordById(session, grade.getAttemptId());
    }

    private Optional<ExecutionContext> contextOf(Session session, Grade grade) {
        return attemptOf(session, grade)
                .flatMap(attempt -> executions.findContext(session, attempt.executionId()));
    }

    /** The contract's rule for teacher verbs: the executing teacher, or the exam's author. */
    private static boolean mayApprove(ExecutionContext execution, long teacherId) {
        return execution.executingTeacherId() == teacherId || execution.authorId() == teacherId;
    }
}
