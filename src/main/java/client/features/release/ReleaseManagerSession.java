package client.features.release;

import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.release.ReleaseActionRequest;
import common.dto.release.ReleaseCodeIssue;
import common.dto.release.ReleaseCreateRequest;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseOptions;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseWindow;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Release Manager, without JavaFX (Presentation tier, E9.5/E9.6 — F5).
 *
 * <p>Every decision the screen makes is here, so every one of them is testable without a
 * toolkit: what the list holds, which actions a row offers, what the local validation says,
 * and what happens when a push arrives. The view is a renderer over this and owns no rules.
 *
 * <h2>Rows are adopted, never patched</h2>
 *
 * <p>{@code PUSH_EXECUTION_STATUS} carries one whole {@link ReleaseRow}, and
 * {@link ReleaseList#with(ReleaseRow)} replaces the row of that id or inserts it when the
 * list has never seen it. A release created on this teacher's other machine therefore
 * appears without anybody pressing anything, and a release that opened while she was reading
 * the screen changes its chip in place (NFR-18: no refresh button anywhere).
 *
 * <h2>The clock is the server's</h2>
 *
 * <p>Every list carries {@link ReleaseList#serverNow()}, and {@link #now()} ages it against
 * the local clock so countdowns tick between pushes. The client never decides a state from
 * it: {@link ReleaseRow#state()} is the server's answer and is rendered as given. Ageing is
 * for the words "opens in 20 minutes", nothing else, which is why a laptop with a wrong
 * clock shows a wrong countdown and never a wrong status chip.
 *
 * <h2>The code is remembered until she dismisses it</h2>
 *
 * <p>{@link #lastCreated()} holds the release that has just been created so the view can put
 * its code on screen big enough to read from a projector (S-17: it is delivered orally).
 * It is deliberately sticky rather than a toast: a teacher who looks away mid-sentence has to
 * be able to look back and still find it.
 */
public final class ReleaseManagerSession {

    private static final Logger log = LoggerFactory.getLogger(ReleaseManagerSession.class);

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    private final List<Consumer<ReleaseList>> listeners = new ArrayList<>();

    private boolean started;
    private ReleaseList releases = ReleaseList.empty(Instant.EPOCH);
    private ReleaseOptions options = ReleaseOptions.empty();
    private ReleaseRow lastCreated;
    private String lastError = "";
    private boolean loading;
    private Instant receivedAt;
    private Clock clock = Clock.systemUTC();

    /**
     * @param dispatcher the shared request correlator
     * @param eventBus   the app bus; pushes arrive on it already on the FX thread
     */
    public ReleaseManagerSession(RequestDispatcher dispatcher, ClientEventBus eventBus) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /**
     * Replaces the local clock the server's {@code now} is aged against.
     *
     * <p>Only the countdowns use it; nothing here decides anything from it. Injectable so
     * "opens in 20 minutes" is a two-line test rather than a twenty-minute one.
     *
     * @param newClock the clock to read "now" from
     */
    public void useClock(Clock newClock) {
        this.clock = Objects.requireNonNull(newClock, "clock");
    }

    /** Subscribes to every new list, pushed or fetched. */
    public void onUpdate(Consumer<ReleaseList> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ===================== Lifecycle =====================================

    /**
     * Opens the screen: subscribes to pushes and fetches the list and the picker.
     *
     * @return a future completing when both answers have been applied; never completes
     *         exceptionally, because a failed fetch is a sentence on screen and not a crash
     */
    public CompletableFuture<Void> start() {
        if (!started) {
            eventBus.register(this);
            started = true;
        }
        return CompletableFuture.allOf(refresh(), loadOptions());
    }

    /** Unsubscribes and forgets everything. Called when the screen is left. */
    public void stop() {
        if (started) {
            eventBus.unregister(this);
            started = false;
        }
        releases = ReleaseList.empty(Instant.EPOCH);
        options = ReleaseOptions.empty();
        lastCreated = null;
        lastError = "";
        loading = false;
    }

    /** @return {@code true} while this session is subscribed to the bus. */
    public boolean isStarted() {
        return started;
    }

    // ===================== Requests ======================================

    /**
     * Fetches the list.
     *
     * <p>Called once on open and after every action, so the numbers on screen are the
     * server's rather than arithmetic the client did. There is no refresh button that calls
     * it (NFR-18); the push channel is what keeps it current.
     *
     * @return a future completing when the model has been updated
     */
    public CompletableFuture<Void> refresh() {
        loading = true;
        publish();
        return dispatcher.send(Verb.RELEASE_LIST_GET, null)
                .handle((response, failure) -> {
                    loading = false;
                    applyList(response, failure);
                    return null;
                });
    }

    /**
     * Fetches the approved versions she may release (F5.1).
     *
     * @return a future completing when the picker has been filled
     */
    public CompletableFuture<Void> loadOptions() {
        return dispatcher.send(Verb.RELEASE_OPTIONS_GET, null)
                .handle((response, failure) -> {
                    if (failure == null && response.isOk()
                            && response.getPayload() instanceof ReleaseOptions fresh) {
                        options = fresh;
                        publish();
                    } else if (failure != null) {
                        log.warn("Could not load releasable exams: {}", failure.toString());
                    }
                    return null;
                });
    }

    /**
     * Schedules a release, letting the server pick the code (F5.1, F5.2).
     *
     * @param examVersionId the approved version, from the picker
     * @param openAt        when it opens
     * @param closeAt       when it shuts
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> create(long examVersionId, Instant openAt, Instant closeAt) {
        return create(examVersionId, openAt, closeAt, null);
    }

    /**
     * Schedules a release (F5.1, F5.2, F5.3).
     *
     * <p>Two rules are checked locally first, both with the <b>same</b> methods the server
     * refuses with ({@link ReleaseCreateRequest#windowProblem} and
     * {@link ReleaseCreateRequest#codeProblem}), so an obviously wrong window or a code of the
     * wrong shape costs no round trip and reads the same either way. The server checks both
     * again, because minutes pass between opening a dialog and pressing a button.
     *
     * <p><b>Whether the code is free is deliberately not checked here.</b> That answer belongs
     * to the transaction that inserts (§5), and a client that pre-checked it would be showing
     * a green field for a code somebody else could take a second later.
     *
     * @param examVersionId the approved version, from the picker
     * @param openAt        when it opens
     * @param closeAt       when it shuts
     * @param code          the code she typed, or {@code null} to have one generated
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> create(long examVersionId, Instant openAt, Instant closeAt,
                                          String code) {
        if (examVersionId <= 0) {
            return refuseLocally(ReleaseCopy.VERSION_REQUIRED);
        }
        ReleaseCreateRequest ask =
                new ReleaseCreateRequest(examVersionId, openAt, closeAt, code);
        ReleaseWindow problem = ask.windowProblem(now(), ReleaseCreateRequest.PAST_GRACE);
        if (problem != null) {
            return refuseLocally(problem.sentence());
        }
        ReleaseCodeIssue malformed = ask.codeProblem();
        if (malformed != null) {
            return refuseLocally(malformed.sentence());
        }
        return dispatcher.send(Verb.RELEASE_CREATE, ask)
                .handle((response, failure) -> {
                    ReleaseRow created = applyRow(response, failure);
                    if (created != null) {
                        // Sticky, not a toast: this is the code she is about to read out.
                        lastCreated = created;
                        publish();
                    }
                    return null;
                });
    }

    /**
     * Calls off a scheduled release (F5.5).
     *
     * @param executionId the release
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> cancel(long executionId) {
        return act(Verb.RELEASE_CANCEL, executionId);
    }

    /**
     * Ends a live release now (F5.5).
     *
     * <p>Behaves exactly like time expiry for anyone still working, which is the server's
     * doing and not this method's: the verb hands the release to the same close path the
     * clock uses. The client's only job is to have asked first.
     *
     * @param executionId the release
     * @return a future completing when the answer has been applied
     */
    public CompletableFuture<Void> closeEarly(long executionId) {
        return act(Verb.RELEASE_CLOSE_EARLY, executionId);
    }

    private CompletableFuture<Void> act(Verb verb, long executionId) {
        return dispatcher.send(verb, new ReleaseActionRequest(executionId))
                .handle((response, failure) -> {
                    applyRow(response, failure);
                    return null;
                });
    }

    // ===================== Pushes ========================================

    /** Adopts a pushed row (F5.4). Anything else on the bus passes through. */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_EXECUTION_STATUS) {
            return;
        }
        if (!(event.payload() instanceof ReleaseRow pushed)) {
            return;
        }
        releases = releases.with(pushed);
        if (lastCreated != null && lastCreated.executionId() == pushed.executionId()) {
            // The reveal stays up, but stops claiming a state the release has left.
            lastCreated = pushed;
        }
        publish();
    }

    // ===================== Reading it ====================================

    /** @return the releases, newest window first; never null. */
    public ReleaseList releases() {
        return releases;
    }

    /** @return the rows, for a table. */
    public List<ReleaseRow> rows() {
        return releases.rows();
    }

    /** @return the approved versions she may release. */
    public ReleaseOptions options() {
        return options;
    }

    /** @return the release just created, whose code is on screen, or empty. */
    public Optional<ReleaseRow> lastCreated() {
        return Optional.ofNullable(lastCreated);
    }

    /** Dismisses the code reveal. */
    public void clearCreated() {
        lastCreated = null;
        publish();
    }

    /** @return the last refusal's sentence, or empty when the last exchange succeeded. */
    public String lastError() {
        return lastError;
    }

    /** @return {@code true} while the first list is still in flight. */
    public boolean isLoading() {
        return loading;
    }

    /**
     * The server's clock, aged by however long ago its answer arrived.
     *
     * <p>What the countdowns are drawn from, and never what a state is decided from.
     *
     * @return the server's "now", carried forward
     */
    public Instant now() {
        Instant local = clock.instant();
        if (receivedAt == null) {
            return local;
        }
        return releases.serverNow().plus(java.time.Duration.between(receivedAt, local));
    }

    /**
     * @param executionId a release
     * @return its row, or empty when this list does not hold it
     */
    public Optional<ReleaseRow> rowOf(long executionId) {
        return releases.rows().stream()
                .filter(row -> row.executionId() == executionId)
                .findFirst();
    }

    // ===================== Internals =====================================

    private CompletableFuture<Void> refuseLocally(String sentence) {
        lastError = sentence;
        publish();
        return CompletableFuture.completedFuture(null);
    }

    private void applyList(Message response, Throwable failure) {
        if (rejected(Verb.RELEASE_LIST_GET, response, failure)) {
            return;
        }
        if (!(response.getPayload() instanceof ReleaseList fresh)) {
            lastError = ReleaseCopy.OFFLINE;
            publish();
            return;
        }
        releases = fresh;
        receivedAt = clock.instant();
        lastError = "";
        publish();
    }

    /**
     * Applies one action's answer.
     *
     * @return the row the server sent back, or {@code null} when it refused
     */
    private ReleaseRow applyRow(Message response, Throwable failure) {
        if (rejected(null, response, failure)) {
            return null;
        }
        if (!(response.getPayload() instanceof ReleaseRow row)) {
            lastError = ReleaseCopy.OFFLINE;
            publish();
            return null;
        }
        releases = releases.with(row);
        lastError = "";
        publish();
        return row;
    }

    /** @return {@code true} when this exchange failed, having already reported it. */
    private boolean rejected(Verb verb, Message response, Throwable failure) {
        if (failure != null) {
            lastError = ReleaseCopy.OFFLINE;
            log.warn("{} failed: {}", verb, failure.toString());
            publish();
            return true;
        }
        if (response.isError()) {
            lastError = ReleaseCopy.serverMessage(
                    response.getErrorCode(), response.errorMessage(), ReleaseCopy.OFFLINE);
            log.warn("{} refused: {} {}", response.getVerb(),
                    response.getErrorCode(), response.errorMessage());
            publish();
            return true;
        }
        return false;
    }

    private void publish() {
        for (Consumer<ReleaseList> listener : List.copyOf(listeners)) {
            listener.accept(releases);
        }
    }
}
