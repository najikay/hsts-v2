package client.features.approval;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the coordinator's approval queue (Presentation tier, E8.3 — F4.1).
 *
 * <p>Everything the screen decides lives here and nothing else does: the view reads
 * {@link #state()} and {@link #rows()} and renders. That is what makes the screen's behaviour
 * — empty state, error message, the two <em>different</em> empty states — testable against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT §3.2), which is also why
 * every transition below has a test rather than being discovered by clicking.
 *
 * <h2>Two empty states, not one</h2>
 *
 * <p>"Nothing is waiting for you" and "you do not coordinate a subject" produce the same
 * zero rows and mean opposite things: the first is a finished inbox, the second is a person
 * on a screen that will never have anything on it. The server distinguishes them
 * ({@link ApprovalQueue#coordinatesAnything()}) and so does {@link #emptyTitle()}, because
 * answering both with one blank panel is exactly the mystery state PRD §4.1 forbids.
 *
 * <h2>No filtering here</h2>
 *
 * <p>The queue arrives scoped to the caller's subjects by the server's own SQL. This class
 * does no filtering of its own, deliberately: a client-side filter would imply the server
 * might send something it should not, and the next person would trust the filter instead of
 * the server. If a foreign subject's exam ever appeared here it would be a server bug, and
 * hiding it in the client is how that bug would survive to the defence.
 */
public final class ApprovalQueueSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };
    private AsyncViewState state = AsyncViewState.IDLE;
    private List<ApprovalRow> rows = List.of();
    private boolean coordinatesAnything = true;
    private String error;

    /**
     * @param dispatcher the request correlator — the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public ApprovalQueueSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ApprovalQueueSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Requests the queue.
     *
     * <p>Calling this while a load is already in flight is ignored rather than queued: a
     * second identical request would race the first and could settle out of order, leaving
     * the screen showing the older answer.
     */
    public void load() {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        onChange.run();

        dispatcher.send(Verb.APPROVALS_QUEUE_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    /**
     * Re-reads the queue after a decision (E8.5).
     *
     * <p>A re-query rather than dropping the decided row locally. The list is the server's
     * answer to "what is waiting for you", and rebuilding from it is the only way the two
     * cannot drift — a supersede may have removed a different row in the same second.
     */
    public void refresh() {
        state = AsyncViewState.IDLE;
        load();
    }

    private void settle(Message response, Throwable failure) {
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ApprovalQueue page)) {
            // A well-formed OK carrying the wrong type is a protocol bug, not a user error;
            // the coordinator still gets a human sentence rather than a stack trace.
            fail();
            return;
        }
        rows = page.rows();
        coordinatesAnything = page.coordinatesAnything();
        error = null;
        state = AsyncViewState.forResult(rows);
        onChange.run();
    }

    private void fail() {
        rows = List.of();
        error = ApprovalCopy.QUEUE_LOAD_FAILED;
        state = AsyncViewState.ERROR;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state — skeleton, content, empty or error. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded rows; empty unless {@link #state()} is {@code READY}. */
    public List<ApprovalRow> rows() {
        return rows;
    }

    /** @return the error sentence when the load failed. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while a request is in flight, for the skeleton and to disable re-entry. */
    public boolean isLoading() {
        return state == AsyncViewState.LOADING;
    }

    /** @return how many exams are waiting, for the rail badge. */
    public int pendingCount() {
        return rows.size();
    }

    /** @return {@code false} when the caller coordinates no subject at all. */
    public boolean coordinatesAnything() {
        return coordinatesAnything;
    }

    /**
     * @return the empty state's title: which of the two zero-row situations this is
     */
    public String emptyTitle() {
        return coordinatesAnything
                ? ApprovalCopy.QUEUE_EMPTY_TITLE
                : ApprovalCopy.QUEUE_NOT_COORDINATOR_TITLE;
    }

    /** @return the empty state's hint, matching {@link #emptyTitle()}. */
    public String emptyHint() {
        return coordinatesAnything
                ? ApprovalCopy.QUEUE_EMPTY_HINT
                : ApprovalCopy.QUEUE_NOT_COORDINATOR_HINT;
    }

    /**
     * @param row a loaded row
     * @return whether this coordinator wrote the exam she is being asked to approve (F4.3).
     *         Drives a badge and a line in the confirm dialog, and nothing else: the rule
     *         permits it, so the screen informs rather than warns
     */
    public boolean isSelfAuthored(ApprovalRow row) {
        Objects.requireNonNull(row, "row");
        return row.selfAuthored();
    }
}
