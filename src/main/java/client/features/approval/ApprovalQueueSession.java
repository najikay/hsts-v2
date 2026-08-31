package client.features.approval;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;

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
 *
 * <h2>It updates itself ⚑ (B-30)</h2>
 *
 * <p>Until 2026-08-26 this was <b>the one inbox in the app that subscribed to nothing</b>: the
 * server delivered {@code APPROVAL_REQUESTED} to the coordinator's socket correctly, her bell
 * badge incremented, and the list underneath it stayed exactly as it was until she navigated
 * (acceptance case 18.2). Clicking the bell did make it current — but clicking the bell is a
 * user action, which is the whole of what NFR-18 forbids on the screen whose only purpose is
 * an inbox.
 *
 * <p>{@link #subscribeTo(ClientEventBus)} wires it and {@link #onServerPush(ServerPushEvent)}
 * is the entry point, filtered on the notification's own <em>type</em> rather than on the verb
 * alone — {@code PUSH_NOTIFICATION} carries every kind this app has, and a grade being
 * published is not a reason to re-query an approval queue.
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
     * Retires the answers of superseded loads. {@link #refresh()} deliberately starts a new
     * read while one can still be in flight (a push landed, so the older question is stale),
     * and without this the older answer could land last and stand until the next push.
     */
    private int generation;

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
        int asked = ++generation;
        onChange.run();

        dispatcher.send(Verb.APPROVALS_QUEUE_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settle(asked, response, failure)));
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

    // ===================== Live (B-30) ===================================

    /**
     * Subscribes this session to the app bus, so an exam arriving in her queue appears without
     * her pressing anything (NFR-18, E17).
     *
     * <p>Called from the view's {@code build()}, which is where {@code ExamListSession} and
     * {@code MyGradesSession} are wired and for the stated reason: the live refresh then sits
     * somewhere a test can reach, rather than behind a {@code listensToEvents} override only
     * the shell can exercise.
     *
     * <p><b>{@link #onServerPush(ServerPushEvent)} must stay public on a public class.</b> The
     * bus invokes subscribers reflectively from its own package, so a package-private
     * subscriber registers without complaint and then throws {@code IllegalAccessException} on
     * every push, which the dispatcher catches and logs rather than rethrows — the screen
     * simply never updates and no test fails. {@code ExamListSession} records the same trap
     * because it is what happened while E6.14's lock column was being built.
     *
     * <p>Optional by design: {@link #load()} alone is a complete screen, and every existing
     * test that never calls this still describes a working session.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this, for chaining beside {@link #onChange(Runnable)}
     */
    public ApprovalQueueSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * A server push landed; re-read if it changes what this queue would say.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION) {
            return;
        }
        if (event.payload() instanceof NotificationDto item && affectsTheQueue(item.type())) {
            onQueueChanged();
        }
    }

    /**
     * @param type the notification's type
     * @return {@code true} for the two that change this list. {@code APPROVAL_REQUESTED} adds
     *         a row; {@code APPROVAL_SUPERSEDED} removes one, because a teacher who revised
     *         and resubmitted has withdrawn the version her coordinator was about to read.
     *         Both are sent to the coordinators of the subject and to nobody else
     *         ({@code ApprovalService}), so no filtering of recipients is needed here
     */
    private static boolean affectsTheQueue(NotificationType type) {
        return type == NotificationType.APPROVAL_REQUESTED
                || type == NotificationType.APPROVAL_SUPERSEDED;
    }

    /**
     * Re-reads after the queue changed under her (E17, NFR-18).
     *
     * <p>A re-query rather than patching the pushed row in — the same reasoning as
     * {@link #refresh()}, and it matters more here: the push says one exam arrived, and a
     * supersede in the same second may have taken a different one away.
     */
    public void onQueueChanged() {
        refresh();
    }

    private void settle(int asked, Message response, Throwable failure) {
        if (asked != generation) {
            // A newer read is in flight or has landed; this answer describes an older world.
            return;
        }
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
