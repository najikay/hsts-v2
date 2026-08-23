package server.features.reports;

import common.dto.auth.Role;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;

import java.util.Objects;
import java.util.Optional;

/**
 * The principal's two report verbs (Logic tier, E15.3 — F9.3, F9.4, S-7, S-37).
 *
 * <p>{@code REPORT_SUBJECTS_GET} answers "what can I run this kind of report about";
 * {@code REPORT_GET} answers "run it". Everything on the screen — the rows table, the chart, the
 * summary cards — is drawn from those two answers and needs no further round trip.
 *
 * <h2>The role gate is the whole of the authorization</h2>
 *
 * <p>{@code requireRole(caller, PRINCIPAL)} and nothing else, which is unusual enough in this
 * codebase to be worth saying out loud rather than leaving a reviewer to notice the absence.
 * Every other read feature composes a role check with a scope — authorship in E14, enrolment in
 * E10, the taught-course set in E6 — because those roles see a slice. The principal does not:
 * spec 7.3.1 and F9.3 give her the whole school to read, so there is no slice to compute and a
 * scope check here would be a guard that could only ever pass.
 *
 * <p>The consequence is that a teacher, a coordinator and a student are refused by the gate with
 * {@code FORBIDDEN}, and not by an empty answer. That distinction matters: an empty list would
 * read as "there are no reports", which is a false statement about the school, and it would leave
 * a teacher clicking a screen that will never fill.
 *
 * <h2>Zero mutating verbs, structurally</h2>
 *
 * <p>S-7 says the principal is authorized for literally nothing that writes. This service is the
 * proof rather than the promise: it reaches the database only through {@link ReportData}, whose
 * every method is a read, so there is no expression in this feature that could change a row.
 * Adding a write would mean adding a method to that interface first, in a file whose javadoc
 * says why it has none.
 *
 * <h2>Statistics are read, never recomputed</h2>
 *
 * <p>Every figure a report prints was frozen into {@code exam_executions.stats} when a sitting's
 * last grade was approved (F8.5). {@link ReportEngine} maps them through the same
 * {@code FrozenStatistics} the teacher's results screen uses and aggregates them with arithmetic
 * documented on {@code ReportSummary}; nothing on this path re-derives a σ, re-applies the pass
 * mark of 55, or re-buckets a distribution. The rule is pinned by a test that stores a σ
 * inconsistent with the scores beside it and asserts the stored value is what reaches the wire.
 *
 * <h2>Cancelled sittings do not exist here</h2>
 *
 * <p>H15.2 ⚑, landing where it was written for. A cancelled run was never sat, has no results and
 * has nothing to contribute to a comparison; the exclusion is a {@code WHERE} clause in the three
 * report queries rather than a filter a fourth strategy could forget.
 */
public final class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportEngine engine;

    /** @param engine the parameterised report mechanism */
    public ReportService(ReportEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Registers both report verbs. Authenticated and role-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.REPORT_SUBJECTS_GET, this::subjects);
        router.register(Verb.REPORT_GET, this::report);
    }

    // ===================== The verbs =====================================

    /**
     * {@code REPORT_SUBJECTS_GET} — what one dimension can be reported about.
     *
     * @param caller  the authenticated principal
     * @param request the request, carrying a {@link ReportSubjectsRequest}
     * @return {@code OK} with a {@link ReportSubjects}, {@code VALIDATION} for a malformed
     *         payload, or {@code NOT_FOUND} for a dimension this build does not serve
     */
    Message subjects(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.PRINCIPAL);
        if (!(request.getPayload() instanceof ReportSubjectsRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ReportMessages.MALFORMED_REQUEST);
        }
        Optional<ReportSubjects> subjects = engine.subjects(ask.dimension());
        if (subjects.isEmpty()) {
            return Message.error(request, ErrorCode.NOT_FOUND, ReportMessages.UNKNOWN_DIMENSION);
        }
        log.debug("Report subjects for {}: {}", ask.dimension(),
                subjects.get().subjects().size());
        return Message.ok(request, subjects.get());
    }

    /**
     * {@code REPORT_GET} — one report.
     *
     * <p>A subject with nothing to compare is answered {@code OK} with no rows, not
     * {@code NOT_FOUND}. "This teacher has had no exam close" is a fact about the school and is
     * what the principal asked; refusing it would turn a true answer into an error she would
     * reasonably read as a fault.
     *
     * @param caller  the authenticated principal
     * @param request the request, carrying a {@link ReportRequest}
     * @return {@code OK} with a {@link ReportResult}, {@code VALIDATION} for a malformed
     *         payload, or {@code NOT_FOUND} when the dimension is unserved or the subject id
     *         names nothing
     */
    Message report(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.PRINCIPAL);
        if (!(request.getPayload() instanceof ReportRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ReportMessages.MALFORMED_REQUEST);
        }
        Optional<ReportResult> result = engine.report(ask.dimension(), ask.subjectId());
        if (result.isEmpty()) {
            log.debug("No {} report for subject {}", ask.dimension(), ask.subjectId());
            return Message.error(request, ErrorCode.NOT_FOUND, ReportMessages.NO_SUCH_SUBJECT);
        }
        log.debug("{} report for subject {}: {} row(s)", ask.dimension(), ask.subjectId(),
                result.get().rows().size());
        return Message.ok(request, result.get());
    }
}
