package client.features.exam;

import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.ExtendTimeRequest;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The teacher's live monitor, without JavaFX (Presentation tier, E11.2/E11.3 — F7.2).
 *
 * <p>Fetches the execution once, then adopts whole snapshots as they are pushed. There is
 * no refresh button and no polling: NFR-18 forbids the first and the push channel makes the
 * second pointless.
 *
 * <h2>Snapshots, not patches</h2>
 *
 * <p>Every {@code PUSH_MONITOR_UPDATED} replaces the state outright. A screen that patched
 * rows from events would drift the first time one was missed, and a monitor showing a
 * student as still working ten minutes after she handed in is worse than no monitor: a
 * teacher acts on it.
 *
 * <h2>Remaining time between pushes</h2>
 *
 * <p>Each row carries the milliseconds the server computed at {@link ExecutionMonitor#serverNow()}.
 * {@link #remainingFor(MonitorRow)} ages that against the local clock so a column of
 * countdowns ticks smoothly, and every push re-anchors it. The teacher's screen and the
 * student's screen are therefore both rendering the same server-derived deadline, and
 * cannot disagree about who has how long.
 */
public final class ExecutionMonitorSession {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMonitorSession.class);

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    private final List<Consumer<ExecutionMonitor>> listeners = new ArrayList<>();

    private long executionId;
    private boolean started;
    private ExecutionMonitor snapshot;
    private String lastError = "";
    private java.time.Instant receivedAt;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /**
     * @param dispatcher the shared request correlator
     * @param eventBus   the app bus; pushes arrive on it already on the FX thread
     */
    public ExecutionMonitorSession(RequestDispatcher dispatcher, ClientEventBus eventBus) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /**
     * Replaces the local clock used to age remaining times between pushes.
     *
     * <p>Only the ageing uses it; nothing here decides anything from it. Injectable so the
     * ticking column is testable without waiting a minute for a minute to pass.
     *
     * @param newClock the clock to read "now" from
     */
    public void useClock(java.time.Clock newClock) {
        this.clock = Objects.requireNonNull(newClock, "clock");
    }

    /** Subscribes to every new snapshot, pushed or fetched. */
    public void onUpdate(Consumer<ExecutionMonitor> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ===================== Lifecycle =====================================

    /**
     * Opens the monitor on one execution and subscribes to its pushes.
     *
     * <p>The fetch is also the subscription: asking registers this teacher as a watcher
     * server-side, the same "whoever asked is watching" mechanism the edit locks use.
     *
     * @param newExecutionId which execution to watch
     * @return a future completing when the first snapshot has been applied, or the attempt
     *         has failed; never completes exceptionally
     */
    public CompletableFuture<Void> start(long newExecutionId) {
        this.executionId = newExecutionId;
        if (!started) {
            eventBus.register(this);
            started = true;
        }
        return refresh();
    }

    /** Unsubscribes and drops the snapshot. Called when the screen is left. */
    public void stop() {
        if (started) {
            eventBus.unregister(this);
            started = false;
        }
        snapshot = null;
        lastError = "";
    }

    /** @return {@code true} while this session is subscribed to the bus. */
    public boolean isStarted() {
        return started;
    }

    // ===================== Requests ======================================

    /**
     * Fetches the current snapshot.
     *
     * <p>Called once on open, and again after an extension so the numbers on screen come
     * from the server rather than from arithmetic the client did.
     *
     * @return a future completing when the model has been updated
     */
    public CompletableFuture<Void> refresh() {
        long asked = executionId;
        return dispatcher.send(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(asked))
                .handle((response, failure) -> {
                    apply(Verb.EXECUTION_MONITOR_GET, asked, response, failure);
                    return null;
                });
    }

    /**
     * Grants extra minutes to everyone sitting this execution (F7.1, S-20).
     *
     * <p>The server answers with a refreshed snapshot, so the counters and the new end time
     * on the teacher's screen are the server's own numbers. Validation is checked locally
     * first only to keep an obviously wrong amount off the wire; the server checks it again.
     *
     * @param minutes how many to add
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> extend(int minutes) {
        ExtendTimeRequest ask = new ExtendTimeRequest(executionId, minutes);
        if (!ask.isAmountLegal()) {
            lastError = "Enter a number of minutes between 1 and " + ExtendTimeRequest.MAX_MINUTES + ".";
            publish();
            return CompletableFuture.completedFuture(null);
        }
        long asked = executionId;
        return dispatcher.send(Verb.EXECUTION_EXTEND, ask)
                .handle((response, failure) -> {
                    apply(Verb.EXECUTION_EXTEND, asked, response, failure);
                    return null;
                });
    }

    // ===================== Pushes ========================================

    /** Adopts a pushed snapshot (E11.2). Anything for another execution passes through. */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_MONITOR_UPDATED) {
            return;
        }
        if (!(event.payload() instanceof ExecutionMonitor pushed)
                || pushed.executionId() != executionId) {
            return;
        }
        adopt(pushed);
    }

    // ===================== Reading it ====================================

    /** @return the current snapshot, or empty before the first answer arrives. */
    public Optional<ExecutionMonitor> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /** @return the last refusal's sentence, or empty when the last exchange succeeded. */
    public String lastError() {
        return lastError;
    }

    /**
     * How long this student has left, right now.
     *
     * <p>The server's figure aged by however long ago the snapshot arrived, so a column of
     * countdowns ticks between pushes instead of freezing. Never negative, and it is
     * replaced rather than extrapolated on the next push.
     *
     * @param row a row of the current snapshot
     * @return her remaining time
     */
    public Duration remainingFor(MonitorRow row) {
        Objects.requireNonNull(row, "row");
        if (!row.state().isLive() || receivedAt == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(receivedAt, clock.instant());
        Duration left = Duration.ofMillis(row.remainingMillis()).minus(elapsed);
        return left.isNegative() ? Duration.ZERO : left;
    }

    // ===================== Internals =====================================

    /**
     * Applies one answer, unless the screen has moved to another execution ⚑.
     *
     * <p>{@code ExecutionMonitorView.onShow} calls {@link #start(long)} on every navigation and
     * the view is built once, so {@link #executionId} can change while an answer is in flight.
     * The push path has always checked {@code pushed.executionId()} against the field; the request
     * path did not, so a snapshot for the sitting she had just left could repaint the sitting she
     * had just opened — with the wrong students, the wrong counts and the wrong countdowns, and
     * nothing on screen to say so. The two paths now apply the same rule.
     *
     * @param asked the execution this exchange was issued for
     */
    private void apply(Verb verb, long asked, Message response, Throwable failure) {
        if (asked != executionId) {
            return;
        }
        if (failure != null) {
            lastError = ExamCopy.OFFLINE;
            log.warn("{} failed: {}", verb, failure.toString());
            publish();
            return;
        }
        if (response.isError()) {
            lastError = ExamCopy.serverMessage(
                    response.getErrorCode(), response.errorMessage(), ExamCopy.OFFLINE);
            log.warn("{} refused: {} {}", verb, response.getErrorCode(), response.errorMessage());
            publish();
            return;
        }
        if (!(response.getPayload() instanceof ExecutionMonitor fresh)) {
            lastError = ExamCopy.OFFLINE;
            publish();
            return;
        }
        adopt(fresh);
    }

    private void adopt(ExecutionMonitor fresh) {
        this.snapshot = fresh;
        this.receivedAt = clock.instant();
        this.lastError = "";
        publish();
    }

    private void publish() {
        for (Consumer<ExecutionMonitor> listener : List.copyOf(listeners)) {
            listener.accept(snapshot);
        }
    }
}
