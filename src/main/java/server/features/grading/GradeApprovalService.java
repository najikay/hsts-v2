package server.features.grading;

import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.ExecutionStats;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.projections.AttemptRecord;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;
import server.db.repos.AttemptRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;
import server.features.notify.NotificationCatalog;
import server.features.notify.Notifier;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Approving grades, and freezing an execution's statistics when the last one lands
 * (Logic tier, E12.2 / E12.7 — the <b>Facade Pattern</b> boundary ⚑ B-34).
 *
 * <p><b>This is the class {@code PLAN.md} §2's "Facade (services)" resolves to</b>, and
 * acceptance case 20.1 named it as the representative of that claim; NFR-20 asks that a claimed
 * pattern be named where it is used, so it is named here. Every {@code *Service} under
 * {@code server/features} wears the same shape and this one is the clearest instance of it:
 * {@code GRADES_APPROVE} is a single call, and behind it are three repositories, a grade's
 * state machine, an ownership rule resolved per row, a statistics computation with its own
 * storage shape, a durable notification and a live push. <b>The handler above knows none of
 * that</b> — it checks a role, opens a transaction, calls one method and answers.
 *
 * <p>That is the load-bearing half of the pattern rather than a tidiness claim: it is what
 * lets the ordering rules below be stated once and be true for every caller, instead of being
 * a sequence each handler is trusted to get right. The one step deliberately left outside the
 * facade is the push, and the reason is in the publishing section below.
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
 *
 * <h2>Publishing: two channels, and only one of them leaves this class ⚑ (B-32)</h2>
 *
 * <p>{@code GRADES_APPROVE}'s contract promises the student <em>two</em> things — a durable
 * {@code GRADE_PUBLISHED} notification and a live {@code PUSH_GRADE_PUBLISHED} — and until
 * 2026-08-26 only the first happened. The verb was declared, its javadoc promised it,
 * {@code MyGradesSession} listened for it, and <b>no server class sent it</b>, so a student
 * with My Grades open watched her bell light up and the table beneath it stay as it was
 * (acceptance case 18.4, hardening item H13.5).
 *
 * <p>The notification is raised here, inside the transaction, because persisting it is part of
 * the same unit of work. <b>The push is not.</b> {@link #approveAndCollect} returns the rows it
 * published and {@link GradingHandlers} pushes them <em>after the commit</em> — deliberately,
 * because {@code MyGradesSession} answers the push by re-querying {@code MY_GRADES_GET} on a
 * second connection, and a push written before this transaction committed could be answered
 * from a database that does not yet hold the row it is announcing. That is
 * {@code ExtendService}'s and {@code AttemptService}'s ordering ("deliberately outside the
 * transaction that closed it") rather than {@code NotificationService}'s, and the difference is
 * exactly that this push provokes a read.
 */
public class GradeApprovalService {

    private static final Logger log = LoggerFactory.getLogger(GradeApprovalService.class);

    private final GradeRepository grades;
    private final AttemptRepository attempts;
    private final ExecutionRepository executions;
    private final Notifier notifier;
    private final Clock clock;

    public GradeApprovalService(GradeRepository grades,
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
    private record Published(long studentId, long attemptId, long gradeId,
                             ExecutionContext execution) {
    }

    /**
     * What one approve call did, and what is left for the caller to deliver (B-32).
     *
     * @param result    the wire answer — counts and refusals
     * @param published one row per grade that became visible in this call, in the student
     *                  shape {@code PUSH_GRADE_PUBLISHED} carries. Empty when nothing changed
     *                  state, which is the idempotent re-approve case: a second click must not
     *                  push a second time
     */
    public record Approval(ApproveResult result, List<StudentGradeRow> published) {

        public Approval {
            Objects.requireNonNull(result, "result");
            published = List.copyOf(published);
        }
    }

    /**
     * Approves the requested grades on behalf of {@code teacherId}.
     *
     * <p>The narrow form, kept because most callers want only the answer. It discards the
     * published rows, so a caller using it sends no {@code PUSH_GRADE_PUBLISHED} — see
     * {@link #approveAndCollect}.
     *
     * @param session   the current session, inside a transaction
     * @param teacherId the authenticated caller — never taken from the payload
     * @param request   the grade ids to approve
     * @return counts of approved and already-approved, plus the ids that were refused
     * @throws NullPointerException if any argument is null
     */
    public ApproveResult approve(Session session, long teacherId, ApproveRequest request) {
        return approveAndCollect(session, teacherId, request).result();
    }

    /**
     * Approves, and hands back the rows the caller should push once this transaction has
     * committed (B-32).
     *
     * @param session   the current session, inside a transaction
     * @param teacherId the authenticated caller — never taken from the payload
     * @param request   the grade ids to approve
     * @return the wire answer, plus one {@link StudentGradeRow} per newly published grade
     * @throws NullPointerException if any argument is null
     */
    public Approval approveAndCollect(Session session, long teacherId, ApproveRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");

        if (request.isEmpty()) {
            return new Approval(new ApproveResult(0, 0, List.of()), List.of());
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
            attemptOf(session, grade).ifPresent(attempt -> published.add(new Published(
                    attempt.studentId(), attempt.attemptId(), grade.getId(), execution)));
        }

        for (Long executionId : touchedExecutions) {
            freezeStatisticsIfComplete(session, executionId);
        }

        // Notify last: a student should never be told a grade is ready by a transaction that
        // then rolls back. Nothing after this point can fail the approval.
        for (Published entry : published) {
            notifier.notifyUser(entry.studentId(),
                    NotificationCatalog.gradePublished(entry.execution().examName(),
                            entry.attemptId()));
        }

        log.info("Approved {} grade(s) for teacher {} ({} already approved, {} refused)",
                approved, teacherId, alreadyApproved, refused.size());
        return new Approval(new ApproveResult(approved, alreadyApproved, List.copyOf(refused)),
                publishedRows(session, published));
    }

    /**
     * The rows to push once this transaction commits (B-32).
     *
     * <p>Read back rather than assembled from the entities in hand, and the reason is one
     * field: {@link StudentGradeRow} requires the student's name, which nothing on
     * {@code Grade} or {@link AttemptRecord} carries. {@code findResultRows} is the read the
     * teacher's results table already uses, so the student is pushed <b>exactly the numbers
     * her teacher is looking at</b> rather than a second assembly of them that could drift.
     * One query per touched execution, not one per grade, because a bulk approve is the
     * normal case.
     *
     * <p>{@code overrideReason} is passed as {@code null} unconditionally. The justification
     * is teacher and audit material and never reaches a student — {@code MyGrades} and
     * {@code CheckedForm} strip it structurally in their compact constructors, and a bare
     * push has no container to strip it, so it is simply never put on.
     */
    private List<StudentGradeRow> publishedRows(Session session, List<Published> published) {
        if (published.isEmpty()) {
            return List.of();
        }
        Map<Long, StudentResultRow> byGradeId = new HashMap<>();
        Set<Long> alreadyRead = new LinkedHashSet<>();
        for (Published entry : published) {
            if (alreadyRead.add(entry.execution().executionId())) {
                for (StudentResultRow row
                        : grades.findResultRows(session, entry.execution().executionId())) {
                    byGradeId.put(row.gradeId(), row);
                }
            }
        }

        List<StudentGradeRow> rows = new ArrayList<>(published.size());
        for (Published entry : published) {
            StudentResultRow row = byGradeId.get(entry.gradeId());
            if (row == null) {
                // The join did not resolve. A grade with no readable student is a data problem
                // worth a log line, not a push carrying blanks.
                log.warn("Approved grade {} could not be read back for publishing", entry.gradeId());
                continue;
            }
            rows.add(new StudentGradeRow(row.gradeId(), row.studentId(), row.studentName(),
                    row.autoScore(), row.finalScore(), row.effectiveScore(), GradeState.APPROVED,
                    null, row.teacherComment(), row.approvedAt(),
                    entry.execution().examName(), entry.execution().courseCode()));
        }
        return rows;
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
