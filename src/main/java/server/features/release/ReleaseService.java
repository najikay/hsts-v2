package server.features.release;

import common.dto.auth.Role;
import common.dto.release.ReleasableVersion;
import common.dto.release.ReleaseActionRequest;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseCreateRequest;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseWindow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.db.entities.ExamVersionStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.features.exam.ExecutionCloseService;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Taking an exam out of the drawer, and putting it back (Logic tier, E9 — F5).
 *
 * <p>Five verbs and one entity, and three rules that carry the weight. Each is a rule a
 * plausible implementation gets wrong in a way nobody notices until an exam hall is
 * involved.
 *
 * <h2>1. Only an approved version leaves the drawer (F5.1, S-14)</h2>
 *
 * <p>Enforced twice, in two different ways, and both are deliberate. The picker
 * ({@code RELEASE_OPTIONS_GET}) filters on {@code APPROVED} <em>in the query</em>, so an
 * unapproved exam is not fetched and PRD §6's "impossible (not listed)" is a {@code where}
 * clause rather than a client omission. {@code RELEASE_CREATE} then checks the status of the
 * version it was actually handed, because a list is a courtesy: a dialog can sit open while
 * a coordinator sends the exam back, and the request that arrives afterwards is honest and
 * wrong. That second check answers with the F5.1 sentence, which names the person who
 * unblocks it.
 *
 * <h2>2. The code is the teacher's; the check is the server's</h2>
 *
 * <p>§4 says she defines a 4-character code and T-5.3 has her typing one, so
 * {@code ReleaseCreateRequest} carries it and the dialog has a field. Leaving it blank asks
 * {@link ExecutionCodes} for a readable one instead.
 *
 * <p>Either way the code is validated <b>here, inside the inserting transaction</b>. §5 makes
 * uniqueness a service rule because the constraint is partial and MySQL has no partial unique
 * index: a code is free again once its sitting is over, and the seed's fourth execution reuses
 * the first's shape to prove it. So a supplied code that clashes with a scheduled or live
 * sitting is refused by name ({@code ReleaseCodeIssue.TAKEN}), and a rolled one that clashes
 * is simply rolled again. The shape rule is checked earlier, outside any transaction, because
 * it is a rule about a string.
 *
 * <h2>3. Cancel and close early are different actions (F5.5)</h2>
 *
 * <p>Cancelling is legal only from {@code SCHEDULED} and is a state change and nothing else:
 * nobody sat it, so there is nobody to hand in. Closing early is legal only from
 * {@code LIVE} and is delegated whole to {@link ExecutionCloseService}, which force-submits
 * every straggler <em>through the expiry path</em> and then freezes the counts. That
 * delegation is what makes F5.5's "behaves exactly like time expiry for active students"
 * true rather than promised: there is no second force-submit implementation to drift from
 * the first, so a student caught mid-question by a close ends {@code TIMED_OUT} with the
 * answers she had saved and receives the same {@code PUSH_FORCE_SUBMITTED} her own timer
 * would have sent.
 *
 * <h2>Authorization</h2>
 *
 * <p>{@code requireRole(TEACHER, COORDINATOR)} on every verb, plus ownership resolved from
 * the release itself — the caller must be the teacher who released it or the author of the
 * exam being sat (S-35), never whoever the payload says (P-5). No request record here has a
 * teacher id on it, so there is no field to put a colleague's id into.
 *
 * <p>An id that is not hers and an id that does not exist both answer {@code NOT_FOUND} with
 * one sentence. That is a deliberate divergence from E11's {@code FORBIDDEN}, and the
 * reasoning is on {@link ReleaseMessages#RELEASE_UNKNOWN}: this screen lists exactly what
 * she may act on, so an id from anywhere else is a probe.
 */
public final class ReleaseService implements ReleaseAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ReleaseStore store;
    private final ExecutionCloseService closeService;
    private final PushGateway pushGateway;
    private final Clock clock;
    private final Random random;

    /**
     * @param store        the transactional data seam
     * @param closeService the seam E11 built for this epic: force-submit the stragglers
     *                     through the expiry path, then freeze the counts. Closing early is
     *                     entirely this call
     * @param pushGateway  the push channel, for {@code PUSH_EXECUTION_STATUS}
     * @param clock        the server's clock; the same one attempts and monitors use
     * @param random       the code generator's randomness; a seeded one in tests
     */
    public ReleaseService(ReleaseStore store, ExecutionCloseService closeService,
                          PushGateway pushGateway, Clock clock, Random random) {
        this.store = Objects.requireNonNull(store, "store");
        this.closeService = Objects.requireNonNull(closeService, "closeService");
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Registers the five release verbs. All authenticated, role-gated and owner-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.RELEASE_OPTIONS_GET, this::options);
        router.register(Verb.RELEASE_LIST_GET, this::list);
        router.register(Verb.RELEASE_CREATE, this::create);
        router.register(Verb.RELEASE_CANCEL, this::cancel);
        // The verb E11 deliberately left unregistered: closing was E9's to own, and this is
        // the one line that gives ExecutionCloseService a caller.
        router.register(Verb.RELEASE_CLOSE_EARLY, this::closeEarly);
    }

    // ===================== Reads =========================================

    /**
     * The approved versions this teacher may release (F5.1).
     *
     * <p>Takes no payload: which versions those are is the session's answer.
     */
    Message options(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        long teacherId = caller.userId();
        ReleaseOptions answer = store.inTx(data -> {
            List<ExamVersionContext> versions = data.releasableVersionsFor(teacherId);
            // U-93 (2026-09-01): this used to pass a literal 0 and the picker printed
            // "0 questions" for every exam - a hardcoded number shown as a fact. One
            // grouped query for the whole list, the approval queue's own count.
            List<Long> ids = new ArrayList<>(versions.size());
            for (ExamVersionContext version : versions) {
                ids.add(version.examVersionId());
            }
            java.util.Map<Long, Integer> counts = data.questionCountsByVersion(ids);
            List<ReleasableVersion> options = new ArrayList<>(versions.size());
            for (ExamVersionContext version : versions) {
                options.add(ReleaseRows.toOption(version,
                        counts.getOrDefault(version.examVersionId(), 0)));
            }
            return new ReleaseOptions(options, !options.isEmpty() || data.hasAnyExam(teacherId));
        });
        return Message.ok(request, answer);
    }

    /**
     * This teacher's releases, with their derived state and participation (F5.4).
     *
     * <p>Takes no payload, for the same reason. The scope is her releases <em>and</em> the
     * ones of exams she wrote, which is exactly the set the two action verbs will admit, so
     * every row on screen is actionable and every actionable release is on screen.
     */
    Message list(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        return Message.ok(request, listFor(caller.userId()));
    }

    /**
     * Builds one teacher's list.
     *
     * @param teacherId the caller
     * @return her releases, newest window first
     */
    ReleaseList listFor(long teacherId) {
        Instant now = clock.instant();
        return store.inTx(data -> {
            List<ExecutionContext> contexts = data.executionsFor(teacherId);
            List<Long> ids = new ArrayList<>(contexts.size());
            for (ExecutionContext context : contexts) {
                ids.add(context.executionId());
            }
            Map<Long, ParticipationCounts> counts = data.participationOf(ids);
            return new ReleaseList(now, ReleaseRows.toRows(contexts, counts, now));
        });
    }

    // ===================== Create ========================================

    /**
     * Schedules a release of an approved version (F5.1, F5.2, S-2).
     *
     * <p>The window is checked before anything is read, so a typo costs no database round
     * trip, and it is checked against {@link ReleaseCreateRequest#PAST_GRACE} rather than
     * against zero: a teacher who picks "now", reads the summary and presses Create has
     * spent thirty seconds doing something completely reasonable.
     */
    Message create(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ReleaseCreateRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ReleaseMessages.MALFORMED_REQUEST);
        }
        Instant now = clock.instant();
        ReleaseWindow problem = ask.windowProblem(now, ReleaseCreateRequest.PAST_GRACE);
        if (problem != null) {
            // §6: "open >= close → validation". Refused before any read, so nothing is
            // fetched on behalf of a request that was never going to be honoured.
            return Message.error(request, ErrorCode.VALIDATION, problem.sentence());
        }
        ReleaseCodeIssue malformed = ask.codeProblem();
        if (malformed != null) {
            // C-1's shape, and the two refusals acceptance case 5.3 types: "12" and "ABCDE".
            // Checked out here with the window, because it is a rule about a string and costs
            // no database round trip; whether the code is free is a different question and is
            // asked inside the transaction below.
            return Message.error(request, ErrorCode.VALIDATION, malformed.sentence());
        }

        long teacherId = caller.userId();
        Created created;
        try {
            created = store.inTx(data -> insert(data, teacherId, ask, now));
        } catch (IllegalStateException e) {
            // ExecutionCodes gave up: every roll collided, which needs a school with an
            // implausible number of open releases. A sentence, not a stack trace.
            //
            // The catch is deliberately the whole type rather than a bespoke subclass. The
            // only other IllegalStateException reachable inside that transaction is the
            // defensive re-read below, which fires when a row inserted and flushed a line
            // earlier cannot be read back in its own transaction, i.e. never. If it somehow
            // did, this sentence is still the right thing to show a teacher ("try again"),
            // and the log line here carries the real cause with its stack trace, so nothing
            // is hidden from whoever has to work out what happened.
            log.error("Could not generate a free execution code for version {}",
                    ask.examVersionId(), e);
            return Message.error(request, ErrorCode.CONFLICT, ReleaseMessages.CODE_EXHAUSTED);
        }
        if (created.error() != null) {
            return Message.error(request, created.error(), created.message());
        }

        log.info("Teacher {} released exam version {} as execution {} with code {} ({} to {})",
                teacherId, ask.examVersionId(), created.context().executionId(),
                created.context().code(), ask.openAt(), ask.closeAt());
        announce(created.context(), created.row());
        return Message.ok(request, created.row());
    }

    /** Every create rule and the insert, in one transaction. */
    private Created insert(ReleaseData data, long teacherId, ReleaseCreateRequest ask, Instant now) {
        Optional<ExamVersionContext> found = data.versionById(ask.examVersionId());
        if (found.isEmpty() || !data.teaches(teacherId, found.get().courseCode())) {
            // Unknown and "not one of yours" answer identically: a version id a teacher did
            // not get from her own picker did not come from this screen.
            return Created.refused(ErrorCode.NOT_FOUND, ReleaseMessages.VERSION_UNKNOWN);
        }
        ExamVersionContext version = found.get();
        if (version.status() != ExamVersionStatus.APPROVED) {
            // F5.1, and the one refusal this feature exists to make. ⚑
            return Created.refused(ErrorCode.VALIDATION, ReleaseMessages.VERSION_NOT_APPROVED);
        }

        // The one question only this transaction can answer, and it is asked the same way for
        // a code she chose and one we rolled: uniqueness holds among sittings a student could
        // still enter, and has no constraint behind it (§5). Asking it anywhere else — in the
        // dialog, or in an earlier transaction — would be answering from a picture that can
        // change before the insert.
        String code;
        if (ask.hasCode()) {
            if (data.isCodeInUse(ask.code())) {
                return Created.refused(ErrorCode.VALIDATION, ReleaseCodeIssue.TAKEN.sentence());
            }
            // Already trimmed and upper-cased by the request's compact constructor.
            code = ask.code();
        } else {
            code = ExecutionCodes.generate(random, data::isCodeInUse);
        }
        long executionId = data.createExecution(version.examVersionId(), code,
                ask.openAt(), ask.closeAt(), teacherId);

        // Re-read rather than assemble: the row the teacher sees is the row the database
        // holds, including anything a column default decided.
        ExecutionContext context = data.executionById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Execution " + executionId + " vanished inside its own transaction"));
        return Created.of(context, ReleaseRows.toRow(context, null, now));
    }

    // ===================== Cancel ========================================

    /**
     * Calls off a release before it ever opens (F5.5).
     *
     * <p>Legal only from {@code SCHEDULED}. A live release is ended with
     * {@link #closeEarly}, and the refusal says so rather than saying no: the teacher who
     * pressed the wrong button wants the other one.
     */
    Message cancel(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ReleaseActionRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ReleaseMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();
        Instant now = clock.instant();

        Created outcome = store.inTx(data -> {
            ExecutionContext context = ownedOrNull(data, ask.executionId(), teacherId);
            if (context == null) {
                return Created.refused(ErrorCode.NOT_FOUND, ReleaseMessages.RELEASE_UNKNOWN);
            }
            if (context.status() != ExecutionStatus.SCHEDULED) {
                return Created.refused(ErrorCode.CONFLICT,
                        ReleaseMessages.cannotCancel(context.status()));
            }
            if (data.transition(ask.executionId(), ExecutionStatus.SCHEDULED,
                    ExecutionStatus.CANCELLED) == 0) {
                // The guarded update saw a different state: the scheduled check opened it,
                // or a colleague cancelled it, between the read above and this write.
                return Created.refused(ErrorCode.CONFLICT, ReleaseMessages.RELEASE_RACED);
            }
            ExecutionContext cancelled = data.executionById(ask.executionId()).orElse(context);
            return Created.of(cancelled, ReleaseRows.toRow(cancelled, null, now));
        });

        if (outcome.error() != null) {
            return Message.error(request, outcome.error(), outcome.message());
        }
        log.info("Teacher {} cancelled execution {} before it opened", teacherId, ask.executionId());
        announce(outcome.context(), outcome.row());
        return Message.ok(request, outcome.row());
    }

    // ===================== Close early ===================================

    /**
     * Ends a live release now (F5.5).
     *
     * <p>Three steps, and the middle one is the whole feature.
     *
     * <ol>
     *   <li>One transaction checks the caller owns it and that it is live;</li>
     *   <li><b>outside</b> any transaction of ours, {@link ExecutionCloseService#close}
     *       force-submits every straggler through the expiry path and freezes the counts.
     *       Each force-submit is its own transaction and its own compare-and-set, exactly as
     *       a timer expiry is, which is what "behaves exactly like time expiry" means. Doing
     *       this inside our transaction would nest transactions and would hold a write lock
     *       on the release for the length of a whole class handing in;</li>
     *   <li>a second transaction re-reads the row, so the teacher's screen shows the counts
     *       the close actually froze rather than the ones we last saw.</li>
     * </ol>
     *
     * <p>Idempotent throughout: the close service force-submits nobody when there is nobody
     * in progress and rewrites the same three numbers, so a double click and a retry after a
     * dropped connection both do the right thing.
     */
    Message closeEarly(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof ReleaseActionRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ReleaseMessages.MALFORMED_REQUEST);
        }
        long teacherId = caller.userId();

        String refusal = store.inTx(data -> {
            ExecutionContext context = ownedOrNull(data, ask.executionId(), teacherId);
            if (context == null) {
                return ReleaseMessages.RELEASE_UNKNOWN;
            }
            return context.status() == ExecutionStatus.LIVE ? null : ReleaseMessages.CLOSE_NOT_LIVE;
        });
        if (ReleaseMessages.RELEASE_UNKNOWN.equals(refusal)) {
            return Message.error(request, ErrorCode.NOT_FOUND, refusal);
        }
        if (refusal != null) {
            return Message.error(request, ErrorCode.CONFLICT, refusal);
        }

        closeService.close(ask.executionId());
        log.info("Teacher {} closed execution {} early", teacherId, ask.executionId());

        return refreshed(ask.executionId())
                .<Message>map(row -> Message.ok(request, row))
                .orElseGet(() -> Message.error(request, ErrorCode.NOT_FOUND,
                        ReleaseMessages.RELEASE_UNKNOWN));
    }

    // ===================== Pushing =======================================

    /**
     * Rebuilds one release's row and pushes it to its owners (F5.4).
     *
     * <p>The {@link ReleaseAnnouncer} seam, so the scheduled check can announce a transition
     * without knowing how a row is built. Cheap and safe to call from anywhere: a push that
     * cannot be built is logged rather than thrown, because this is called from the timer
     * thread and from inside a verb, and neither may fail because a screen could not be
     * repainted.
     *
     * @param executionId the release that changed
     */
    @Override
    public void executionChanged(long executionId) {
        try {
            Instant now = clock.instant();
            store.runInTx(data -> data.executionById(executionId).ifPresent(context -> {
                ParticipationCounts counts =
                        data.participationOf(List.of(executionId)).get(executionId);
                announce(context, ReleaseRows.toRow(context, counts, now));
            }));
        } catch (RuntimeException e) {
            log.error("Could not push the status of release {}", executionId, e);
        }
    }

    private void announce(ExecutionContext context, ReleaseRow row) {
        int delivered = pushGateway.toUsers(ReleaseRows.ownersOf(context),
                Verb.PUSH_EXECUTION_STATUS, row);
        log.debug("Release {} is {}; pushed to {} owner(s)",
                row.executionId(), row.state(), delivered);
    }

    /** @return the release's row as it stands now, or empty when it has gone. */
    private Optional<ReleaseRow> refreshed(long executionId) {
        Instant now = clock.instant();
        return store.inTx(data -> data.executionById(executionId).map(context -> {
            ParticipationCounts counts =
                    data.participationOf(List.of(executionId)).get(executionId);
            ReleaseRow row = ReleaseRows.toRow(context, counts, now);
            announce(context, row);
            return row;
        }));
    }

    /**
     * @return the release, or {@code null} when it does not exist <b>or</b> is not hers. One
     *         answer for both, deliberately: a teacher probing ids learns nothing
     */
    private static ExecutionContext ownedOrNull(ReleaseData data, long executionId, long teacherId) {
        return data.executionById(executionId)
                .filter(context -> context.isOwnedBy(teacherId))
                .orElse(null);
    }

    /** What a write transaction produced: a release and its row, or a refusal. */
    private record Created(ExecutionContext context, ReleaseRow row,
                           ErrorCode error, String message) {

        static Created of(ExecutionContext context, ReleaseRow row) {
            return new Created(context, row, null, null);
        }

        static Created refused(ErrorCode error, String message) {
            return new Created(null, null, error, message);
        }
    }
}
