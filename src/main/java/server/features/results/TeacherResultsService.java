package server.features.results;

import common.dto.auth.Role;
import common.dto.exam.AttemptState;
import common.dto.grading.GradeState;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.entities.GradeStatus;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The teacher's results and statistics (Logic tier, E14.1 — F9.2, S-35).
 *
 * <p>Two verbs. {@code RESULTS_EXAMS_GET} answers "what have I written and when was it sat";
 * {@code RESULTS_EXECUTION_GET} answers "how did one sitting go". Everything else on the
 * screen — the stat cards, the histogram, the count/percent toggle — is drawn from those two
 * answers and needs no further round trip.
 *
 * <h2>Scope is authorship, and it lives in the query</h2>
 *
 * <p>F9.2 is explicit: a teacher sees results for every exam <b>she wrote</b>, including
 * sittings released by other teachers (S-35). This service therefore scopes on the exam's
 * recorded author, resolved from the {@code exams} row, and never on the execution's
 * {@code created_by} and never on anything in the payload (P-5). The list verb's filter is a
 * {@code WHERE} clause on {@code exams.author}; the detail verb compares
 * {@link ExecutionContext#authorId()} against the session's user id and answers
 * {@code NOT_FOUND} when they differ.
 *
 * <p><b>Not-yours and not-there are the same answer.</b> Two distinct answers would turn this
 * verb into a membership oracle: a teacher — or anybody holding her session — could walk
 * execution ids and learn which ones exist. {@link ResultsMessages#NO_SUCH_EXECUTION} is the
 * single sentence both cases get, and the E14.5 negative test asserts the two responses are
 * indistinguishable, not merely both refused.
 *
 * <p>This is deliberately a <b>narrower</b> rule than {@code MonitorService}'s. Monitoring a
 * live exam belongs to whoever is running the room as well as to the author
 * ({@link ExecutionContext#isOwnedBy}); reading the results of a sitting is F9.2's question
 * about the exam, and the exam belongs to the person who wrote it. A teacher who ran somebody
 * else's exam sees its live monitor and does not see its results table here. That asymmetry is
 * intentional and is the reason this service does not call {@code isOwnedBy}.
 *
 * <h2>Statistics are read, never recomputed</h2>
 *
 * <p>Every figure the screen prints was frozen into {@code exam_executions.stats} when the
 * execution's last grade was approved (F8.5): population σ with divisor {@code n}, pass mark
 * 55, and the ten-bucket distribution. This service copies them through
 * {@link FrozenStatistics} and computes none of them. A second computation with a different
 * divisor or a different threshold produces plausible numbers that disagree with the seed by
 * about a point, which is a bug that survives review because both answers look right (H14.4
 * ⚑). The rule is enforced by a test that stores a σ inconsistent with the rows and asserts
 * the stored value is what reaches the wire.
 *
 * <h2>Unfinished grading is a state, not an error</h2>
 *
 * <p>An execution with no frozen statistics still returns its rows, with no statistics
 * attached. The screen says grading is not finished, calmly, and shows whatever has been
 * marked. Refusing to open it would be a dead end (§4.1) for the most ordinary situation there
 * is: a teacher looking at an exam she is halfway through marking.
 */
public final class TeacherResultsService {

    private static final Logger log = LoggerFactory.getLogger(TeacherResultsService.class);

    private final TeacherResultsStore store;

    /** @param store the transactional data seam */
    public TeacherResultsService(TeacherResultsStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Registers both results verbs. Authenticated and role-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.RESULTS_EXAMS_GET, this::exams);
        router.register(Verb.RESULTS_EXECUTION_GET, this::execution);
    }

    // ===================== The verbs =====================================

    /**
     * {@code RESULTS_EXAMS_GET} — every exam the caller wrote, with its sittings.
     *
     * @param caller  the authenticated teacher
     * @param request the request; its payload is ignored, and there is nothing it could say
     * @return {@code OK} with a {@link TeacherResults}
     */
    Message exams(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        TeacherResults results = examsOf(caller.userId());
        log.debug("Results list for teacher {}: {} exam(s), {} execution(s)",
                caller.userId(), results.exams().size(), results.totalExecutions());
        return Message.ok(request, results);
    }

    /**
     * {@code RESULTS_EXECUTION_GET} — one sitting's rows and frozen statistics.
     *
     * @param caller  the authenticated teacher
     * @param request the request, carrying an {@link ExecutionResultsRequest}
     * @return {@code OK} with an {@link ExecutionResults}, or {@code NOT_FOUND} when the
     *         execution does not exist <b>or</b> its exam is not the caller's
     */
    Message execution(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ExecutionResultsRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ResultsMessages.MALFORMED_REQUEST);
        }
        Optional<ExecutionResults> results = resultsOf(caller.userId(), ask.executionId());
        if (results.isEmpty()) {
            // Unknown id and somebody else's exam get the same answer, on purpose.
            log.debug("Teacher {} asked for execution {} and is not its exam's author (or it "
                    + "does not exist)", caller.userId(), ask.executionId());
            return Message.error(request, ErrorCode.NOT_FOUND, ResultsMessages.NO_SUCH_EXECUTION);
        }
        return Message.ok(request, results.get());
    }

    // ===================== The list ======================================

    /**
     * Assembles one teacher's whole results list in a single transaction.
     *
     * <p>Four reads, none of them per row: her exams, their sittings, the participant and
     * grade counts of all those sittings at once, and which of them carry frozen statistics. A
     * teacher with thirty sittings therefore costs four queries rather than ninety-one, and
     * every number on the screen comes from one consistent moment.
     *
     * @param teacherId the authenticated caller
     * @return her exams, each with its executions
     */
    TeacherResults examsOf(long teacherId) {
        return store.inTx(data -> {
            List<AuthoredExam> authored = data.examsAuthoredBy(teacherId);
            if (authored.isEmpty()) {
                return TeacherResults.EMPTY;
            }
            List<ExecutionContext> executions = data.executionsOfExamsAuthoredBy(teacherId);

            List<Long> executionIds = new ArrayList<>(executions.size());
            for (ExecutionContext execution : executions) {
                executionIds.add(execution.executionId());
            }
            Map<Long, Integer> participants = data.participantsByExecution(executionIds);
            Map<Long, Integer> graded = data.gradedByExecution(executionIds);
            Set<Long> withStatistics = data.executionsWithStatistics(executionIds);

            Map<Long, List<ExecutionResultRow>> byExam = new LinkedHashMap<>();
            for (ExecutionContext execution : executions) {
                byExam.computeIfAbsent(execution.examId(), key -> new ArrayList<>())
                        .add(toWire(execution, participants, graded, withStatistics));
            }

            List<ExamResultRow> rows = new ArrayList<>(authored.size());
            for (AuthoredExam exam : authored) {
                rows.add(new ExamResultRow(exam.examId(), exam.displayId(), exam.examName(),
                        exam.courseCode(), exam.courseName(),
                        byExam.getOrDefault(exam.examId(), List.of())));
            }
            return new TeacherResults(rows);
        });
    }

    // ===================== One execution =================================

    /**
     * Assembles one execution's results, or refuses by returning empty.
     *
     * <p>The authorship check is the first thing that happens after the read, and everything
     * else is inside the {@code if}: a caller who is not the author never causes a grade row
     * to be loaded, so the refusal costs one query and leaks nothing through timing either.
     *
     * @param teacherId   the authenticated caller
     * @param executionId the execution asked for
     * @return the results, or empty when the execution does not exist, is cancelled, or
     *         belongs to an exam this teacher did not write
     */
    Optional<ExecutionResults> resultsOf(long teacherId, long executionId) {
        return store.inTx(data -> {
            Optional<ExecutionContext> found = data.executionById(executionId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            ExecutionContext context = found.get();
            if (context.authorId() != teacherId
                    || context.status() == ExecutionStatus.CANCELLED) {
                // A cancelled sitting is answered exactly like an id that does not exist:
                // it was never sat, it has no results, and saying so differently would be
                // one more thing an id-walker could learn (H15.2).
                return Optional.empty();
            }

            List<StudentResultRow> stored = data.resultRowsOf(executionId);
            List<StudentGradeRow> rows = new ArrayList<>(stored.size());
            for (StudentResultRow row : stored) {
                rows.add(toWire(row));
            }

            ResultStatistics statistics = data.statisticsOf(executionId)
                    .flatMap(frozen -> FrozenStatistics.toWire(executionId, frozen))
                    .orElse(null);

            int participants = data.participantsByExecution(List.of(executionId))
                    .getOrDefault(executionId, 0);
            ExecutionResultRow header = new ExecutionResultRow(executionId, context.code(),
                    context.openAt(), context.closeAt(), toWire(context.status()),
                    participants, rows.size(), statistics != null,
                    context.executingTeacherId() != context.authorId());

            return Optional.of(new ExecutionResults(header, context.examName(),
                    context.courseCode(), context.courseName(), rows, statistics));
        });
    }

    // ===================== Mapping =======================================

    private static ExecutionResultRow toWire(ExecutionContext execution,
                                             Map<Long, Integer> participants,
                                             Map<Long, Integer> graded,
                                             Set<Long> withStatistics) {
        long id = execution.executionId();
        return new ExecutionResultRow(id, execution.code(), execution.openAt(),
                execution.closeAt(), toWire(execution.status()),
                participants.getOrDefault(id, 0), graded.getOrDefault(id, 0),
                withStatistics.contains(id),
                // S-35 made visible: the author is looking at a sitting somebody else ran.
                execution.executingTeacherId() != execution.authorId());
    }

    /**
     * The teacher-path row.
     *
     * <p>{@code overrideReason} is carried, unlike on either student path: the justification is
     * teacher and audit material (S-23), and this table is where a teacher checks what she
     * changed and why. The stripping that protects students is structural inside
     * {@code MyGrades} and {@code CheckedForm} and is not bypassed by anything here — those
     * containers strip whatever they are handed.
     */
    private static StudentGradeRow toWire(StudentResultRow row) {
        return new StudentGradeRow(row.gradeId(), row.studentId(), row.studentName(),
                row.autoScore(), row.finalScore(), row.effectiveScore(),
                row.status() == GradeStatus.APPROVED ? GradeState.APPROVED : GradeState.AUTO,
                row.overrideReason(), row.teacherComment(), row.approvedAt(),
                // v1.1's labels stay null here: ExecutionResults already carries the exam and
                // the course once for the whole table, and repeating them per row would be the
                // same two strings thirty times over. A7's teacher name is empty for the same
                // reason, plus one of its own: this path reads through TeacherResultsStore and
                // holds no UserRepository, and the teacher reading this table is the teacher.
                null, null, "",
                // ⚑ B-16. The projection had read actualMinutes since E14 and this mapper
                // dropped it on the floor - the one field with no reader anywhere on the path.
                // It and the attempt's status now travel, and the table has a column for each.
                toWire(row.attemptStatus()), row.actualMinutes());
    }

    /**
     * Stored attempt status to the wire enum. Exhaustive, so a new status is a compile error.
     *
     * <p>The same three values and the same mapping the student's own checked form uses
     * (E13.2): one fact, one type, whichever audience is reading it.
     */
    private static AttemptState toWire(AttemptStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case IN_PROGRESS -> AttemptState.IN_PROGRESS;
            case SUBMITTED -> AttemptState.SUBMITTED;
            case TIMED_OUT -> AttemptState.TIMED_OUT;
        };
    }

    /** Stored status to wire state. Exhaustive, so a new status is a compile error here. */
    private static ExecutionState toWire(ExecutionStatus status) {
        return switch (status) {
            case SCHEDULED -> ExecutionState.SCHEDULED;
            case LIVE -> ExecutionState.LIVE;
            case CLOSED -> ExecutionState.CLOSED;
            case CANCELLED -> ExecutionState.CANCELLED;
        };
    }
}
