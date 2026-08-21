package client.features.approval;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalRow;
import common.dto.approval.MyApprovals;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the teacher's own approval-status list (Presentation tier, E8.6 — F4.2).
 *
 * <p>F4.2 asks for a rejection reason to be delivered as a notification <b>and</b> visible on
 * the exam. This is the second half: a notification the teacher dismissed is not a record, so
 * the reason has to live on a surface she can go back to, and
 * {@link #selectedVersionId(long)} lets the notification's own reference land her on the right
 * row when she follows it.
 *
 * <p>FX-free, so the whole thing is tested against {@code FakeClientConnection} with no
 * toolkit (TEAM_SPLIT §3.2).
 *
 * <p><b>Scope.</b> This is deliberately the narrow approval-status view and not an exam list;
 * E7 owns that, and its screen replaces this one at the same route id.
 */
public final class MyApprovalsSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };
    private AsyncViewState state = AsyncViewState.IDLE;
    private List<ApprovalRow> rows = List.of();
    private String error;
    private long selectedVersionId;

    /**
     * @param dispatcher the request correlator
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public MyApprovalsSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public MyApprovalsSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Names the version a notification deep-linked to, so the screen can open on it.
     *
     * <p>Set before {@link #load()} and honoured once the rows arrive. A version that is not
     * in the answer is simply not selected: notifications outlive what they point at
     * ({@code NavRef} says so), and a dangling reference should land on the list rather than
     * on an error.
     *
     * @param examVersionId the version to select, or {@code 0} for none
     */
    public void selectedVersionId(long examVersionId) {
        this.selectedVersionId = examVersionId;
    }

    // ===================== Loading =======================================

    /** Requests the caller's own submitted versions. */
    public void load() {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        onChange.run();

        dispatcher.send(Verb.MY_APPROVALS_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    /**
     * Re-reads after an {@code APPROVAL_APPROVED} or {@code APPROVAL_REJECTED} push (E17).
     *
     * <p>A re-query rather than patching the pushed row in: the list is the server's answer
     * to "what became of my exams", and rebuilding from it is the only way the two cannot
     * drift. NFR-18 — the teacher pressed nothing.
     */
    public void onDecisionArrived() {
        state = AsyncViewState.IDLE;
        load();
    }

    private void settle(Message response, Throwable failure) {
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof MyApprovals page)) {
            rows = List.of();
            error = ApprovalCopy.MINE_LOAD_FAILED;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        rows = page.rows();
        error = null;
        state = AsyncViewState.forResult(rows);
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded rows, newest first. */
    public List<ApprovalRow> rows() {
        return rows;
    }

    /** @return only the ones that were sent back, which is what the screen leads with. */
    public List<ApprovalRow> rejected() {
        return rows.stream().filter(row -> row.state().isRejected()).toList();
    }

    /** @return the error sentence when the load failed. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while a request is in flight. */
    public boolean isLoading() {
        return state == AsyncViewState.LOADING;
    }

    /**
     * @return the row a notification pointed at, when it is in the loaded list; otherwise the
     *         first rejected row, because that is what a teacher opening this screen is most
     *         likely to be here for; otherwise the first row
     */
    public Optional<ApprovalRow> focused() {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (selectedVersionId > 0) {
            Optional<ApprovalRow> named = rows.stream()
                    .filter(row -> row.examVersionId() == selectedVersionId)
                    .findFirst();
            if (named.isPresent()) {
                return named;
            }
        }
        return rejected().stream().findFirst().or(() -> Optional.of(rows.get(0)));
    }

    /**
     * @return the rejection reason to show in the panel, when the focused row has one. The
     *         panel is absent rather than empty when it does not: a heading with nothing
     *         under it is the mystery state PRD §4.1 forbids
     */
    public Optional<String> focusedRejectionReason() {
        return focused().filter(ApprovalRow::hasRejectedReason).map(ApprovalRow::rejectedReason);
    }
}
