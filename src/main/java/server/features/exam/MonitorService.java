package server.features.exam;

import common.dto.auth.Role;
import common.dto.exam.AttemptState;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorCounts;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.entities.AttemptStatus;
import server.db.entities.ExecutionStatus;
import server.db.projections.AttemptRow;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The teacher's live view of an execution in progress (Logic tier, E11.2 — F7.2).
 *
 * <p>Answers {@code EXECUTION_MONITOR_GET} with a whole snapshot and then keeps pushing
 * whole snapshots as things change. Deltas were the obvious alternative and are the wrong
 * one: a screen that patches rows from events drifts the first time one is missed, and a
 * monitor that shows a student as still working ten minutes after she submitted is worse
 * than no monitor at all. Snapshots are small — one class of students — and correct.
 *
 * <h2>Watchers are whoever asked</h2>
 *
 * <p>Requesting an execution registers interest in it, exactly as {@code EditLockService}
 * does with entities, and for the same reason: a screen that cares about an execution is
 * precisely the screen that asked for it, so no second verb is needed. Registrations are
 * dropped on logout and on a dropped socket, through the one {@code SessionManager} hook
 * that covers both.
 *
 * <h2>Counted, never accumulated</h2>
 *
 * <p>The three participation numbers come from a {@code COUNT} over attempts every time
 * (§5 forbids counter columns for exactly this reason), and each row's remaining time is
 * computed from the same derived deadline the student's own countdown is anchored to. The
 * teacher's screen and the student's screen therefore cannot disagree, which is not true of
 * any design where the two are calculated separately.
 *
 * <h2>Authorization</h2>
 *
 * <p>{@code requireRole(TEACHER, COORDINATOR)} plus ownership resolved from the execution
 * itself: the caller must be the teacher who released it or the author of the exam being
 * sat (S-35). A teacher who owns neither gets {@code FORBIDDEN} and learns nothing about
 * the execution, including whether it exists.
 */
public final class MonitorService implements MonitorPublisher {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final ExamStore store;
    private final AttemptTracker tracker;
    private final PushGateway pushGateway;
    private final Clock clock;

    private final Map<Long, Set<Long>> watchers = new ConcurrentHashMap<>();

    /**
     * @param store       the transactional data seam
     * @param tracker     where integrity flags live (E10.7)
     * @param pushGateway the push channel
     * @param clock       the server's clock; the same one the attempts use
     */
    public MonitorService(ExamStore store, AttemptTracker tracker,
                          PushGateway pushGateway, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the monitor verb. Authenticated and role-gated inside. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.EXECUTION_MONITOR_GET, this::monitor);
    }

    /**
     * Hooks watcher cleanup into the session map (E18.3's pattern).
     *
     * <p>Logout and a dropped socket both arrive here, because {@code SessionManager} funnels
     * them through one detach. Without it, a teacher who closed her laptop would be pushed
     * to for the rest of the exam.
     */
    public void attachTo(SessionManager sessions) {
        Objects.requireNonNull(sessions, "sessions");
        sessions.addDisconnectHook(this::onSessionEnded);
    }

    // ===================== The verb ======================================

    Message monitor(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof MonitorRequest ask)) {
            return Message.error(request, ErrorCode.VALIDATION, ExamMessages.MALFORMED_REQUEST);
        }
        Optional<ExecutionMonitor> snapshot = snapshotFor(caller.userId(), ask.executionId());
        if (snapshot.isEmpty()) {
            return Message.error(request, ErrorCode.FORBIDDEN, ExamMessages.NOT_YOUR_EXECUTION);
        }
        watch(ask.executionId(), caller.userId());
        return Message.ok(request, snapshot.get());
    }

    // ===================== MonitorPublisher ==============================

    /**
     * Rebuilds the snapshot and pushes it to whoever is watching (E11.2).
     *
     * <p>Called from every take-exam path that changes anything, including the timer
     * thread. Cheap when nobody is watching, which is the common case: it reads nothing
     * from the database unless there is somebody to send it to.
     */
    @Override
    public void executionChanged(long executionId) {
        Set<Long> interested = watchers.get(executionId);
        if (interested == null || interested.isEmpty()) {
            return;
        }
        Set<Long> recipients = new HashSet<>(interested);
        try {
            ExecutionMonitor snapshot = store.inTx(data -> buildSnapshot(data, executionId).orElse(null));
            if (snapshot == null) {
                return;
            }
            int delivered = pushGateway.toUsers(recipients, Verb.PUSH_MONITOR_UPDATED, snapshot);
            log.debug("Monitor of execution {} pushed to {}/{} watcher(s)",
                    executionId, delivered, recipients.size());
        } catch (RuntimeException e) {
            // A monitor that cannot repaint must never be able to fail a student's submit
            // or stop a timer: this is called from inside both of those paths.
            log.error("Could not push the monitor of execution {}", executionId, e);
        }
    }

    // ===================== Snapshots =====================================

    /**
     * Builds the snapshot for a caller, checking ownership on the way.
     *
     * @param userId      the teacher asking
     * @param executionId the execution
     * @return the snapshot, or empty when the execution does not exist <b>or</b> is not
     *         hers. One answer for both, deliberately: a teacher probing ids learns nothing
     */
    Optional<ExecutionMonitor> snapshotFor(long userId, long executionId) {
        return store.inTx(data -> {
            Optional<ExecutionContext> found = data.executionById(executionId);
            if (found.isEmpty() || !found.get().isOwnedBy(userId)) {
                return Optional.empty();
            }
            return buildSnapshot(data, executionId);
        });
    }

    private Optional<ExecutionMonitor> buildSnapshot(ExamData data, long executionId) {
        Optional<ExecutionContext> found = data.executionById(executionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ExecutionContext ctx = found.get();
        Instant now = clock.instant();
        ParticipationCounts counts = data.participationOf(executionId);
        Map<Long, Integer> answered = data.answeredCountsOf(executionId);
        int questionCount = data.questionCountOf(ctx.examVersionId());

        List<MonitorRow> rows = new ArrayList<>();
        for (AttemptRow row : data.rowsOf(executionId)) {
            rows.add(toWire(row, ctx, now, answered.getOrDefault(row.attemptId(), 0), questionCount));
        }
        return Optional.of(new ExecutionMonitor(executionId, ctx.examName(), ctx.courseCode(),
                ctx.code(), ctx.status() == ExecutionStatus.LIVE, now, ctx.effectiveCloseAt(),
                ctx.extraMinutes(), ctx.allottedMinutes(),
                new MonitorCounts(counts.started(), counts.finished(), counts.timedOut()),
                rows));
    }

    private MonitorRow toWire(AttemptRow row, ExecutionContext ctx, Instant now,
                              int answered, int questionCount) {
        long remaining = 0;
        if (row.status() == AttemptStatus.IN_PROGRESS) {
            Instant deadline = row.startedAt().plusSeconds(ctx.allottedMinutes() * 60L);
            remaining = Math.max(0, Duration.between(now, deadline).toMillis());
        }
        return new MonitorRow(row.studentId(), row.studentName(), toWire(row.status()),
                row.startedAt(), row.endedAt(), remaining, answered, questionCount,
                row.actualMinutes(), tracker.flagOf(row.attemptId()).orElse(null));
    }

    private static AttemptState toWire(AttemptStatus status) {
        return switch (status) {
            case IN_PROGRESS -> AttemptState.IN_PROGRESS;
            case SUBMITTED -> AttemptState.SUBMITTED;
            case TIMED_OUT -> AttemptState.TIMED_OUT;
        };
    }

    // ===================== Watchers ======================================

    private void watch(long executionId, long userId) {
        watchers.computeIfAbsent(executionId, key -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    private void onSessionEnded(long userId, ConnectionToClient connection) {
        int dropped = forget(userId);
        if (dropped > 0) {
            log.debug("Session of user {} ended; stopped watching {} execution(s)", userId, dropped);
        }
    }

    /**
     * Drops a user from every execution she was watching.
     *
     * @param userId the user
     * @return how many executions she stopped watching
     */
    int forget(long userId) {
        int dropped = 0;
        for (Map.Entry<Long, Set<Long>> entry : watchers.entrySet()) {
            Set<Long> interested = entry.getValue();
            if (interested.remove(userId)) {
                dropped++;
            }
            if (interested.isEmpty()) {
                // Otherwise every execution ever opened leaves an empty set behind and the
                // map grows for the life of the server.
                watchers.remove(entry.getKey(), interested);
            }
        }
        return dropped;
    }

    /** @return the teachers currently watching this execution (diagnostics and tests). */
    public Set<Long> watchersOf(long executionId) {
        return Set.copyOf(watchers.getOrDefault(executionId, Set.of()));
    }
}
