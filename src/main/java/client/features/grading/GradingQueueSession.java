package client.features.grading;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradesRequest;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradingQueue;
import common.dto.grading.StudentGradeRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the teacher's grading screen (Presentation tier, E12.5/E12.6/E12.7).
 *
 * <p>Four verbs and one screen: the queue, one sitting opened, an approval, an override. Every
 * decision the screen appears to make is made here, which is what lets it be proven against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT §3.2).
 *
 * <h2>After a write, re-read</h2>
 *
 * <p>Approving and overriding both re-request the open sitting rather than patching the rows in
 * place. Patching is faster and is how a screen ends up disagreeing with the server: an approval
 * can be partially refused, an override changes the effective score <em>and</em> the state, and
 * freezing an execution's statistics happens server-side where the client cannot see it. Asking
 * again costs one round trip and removes every one of those drift paths.
 *
 * <p>The exception is deliberate: {@link #lastApproval()} keeps the {@link ApproveResult} from
 * the write, because the refused ids are the one fact the re-read cannot tell the teacher. A
 * row that came back unchanged looks identical whether it was refused or was never selected.
 *
 * <h2>Selection is the screen's, not the server's</h2>
 *
 * <p>Which rows are ticked is client state and stays here, but it is <b>cleared on every
 * re-read</b>. A selection that survived a refresh would let a teacher approve a row she can no
 * longer see, or one whose state changed underneath her.
 */
public final class GradingQueueSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private List<ExecutionGradingSummary> queue = List.of();
    private ExecutionGrades open;
    private final List<Long> selected = new ArrayList<>();
    private ApproveResult lastApproval;
    private String error;
    private boolean busy;

    public GradingQueueSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public GradingQueueSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== The queue =====================================

    /** Requests the grading queue. */
    public void load() {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADING_QUEUE_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settleQueue(response, failure)));
    }

    private void settleQueue(Message response, Throwable failure) {
        if (!isOk(response, failure)) {
            queue = List.of();
            fail(GradingCopy.LOAD_FAILED);
            return;
        }
        if (!(response.getPayload() instanceof GradingQueue payload)) {
            queue = List.of();
            fail(GradingCopy.LOAD_FAILED);
            return;
        }
        queue = payload.executions();
        error = null;
        state = AsyncViewState.forResult(queue);
        onChange.run();
    }

    // ===================== One sitting ===================================

    /**
     * Opens one sitting for grading.
     *
     * @param summary the queue row she clicked
     */
    public void openExecution(ExecutionGradingSummary summary) {
        Objects.requireNonNull(summary, "summary");
        requestExecution(summary.executionId());
    }

    private void requestExecution(long executionId) {
        busy = true;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADING_EXECUTION_GET, new ExecutionGradesRequest(executionId))
                .whenComplete((response, failure) -> poster.run(() -> settleExecution(response, failure)));
    }

    private void settleExecution(Message response, Throwable failure) {
        busy = false;
        if (!isOk(response, failure)
                || !(response.getPayload() instanceof ExecutionGrades payload)) {
            error = GradingCopy.EXECUTION_FAILED;
            onChange.run();
            return;
        }
        open = payload;
        // Cleared on every re-read: a selection that survived would let her approve a row whose
        // state changed underneath her.
        selected.clear();
        error = null;
        onChange.run();
    }

    // ===================== Approving =====================================

    /**
     * Approves the selected grades (E12.2/E12.7).
     *
     * <p>Does nothing when nothing is selected, rather than sending an empty request: the server
     * would answer correctly, and a screen that sends a request for no work is a screen whose
     * buttons do not mean anything.
     */
    public void approveSelected() {
        if (selected.isEmpty() || open == null) {
            return;
        }
        approve(List.copyOf(selected));
    }

    /**
     * Approves one grade.
     *
     * @param gradeId the grade to approve
     */
    public void approveOne(long gradeId) {
        approve(List.of(gradeId));
    }

    private void approve(List<Long> gradeIds) {
        if (busy) {
            return;
        }
        busy = true;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADES_APPROVE, new ApproveRequest(gradeIds))
                .whenComplete((response, failure) -> poster.run(() -> settleApproval(response, failure)));
    }

    private void settleApproval(Message response, Throwable failure) {
        busy = false;
        if (!isOk(response, failure)
                || !(response.getPayload() instanceof ApproveResult result)) {
            error = GradingCopy.APPROVE_FAILED;
            onChange.run();
            return;
        }
        // Kept because the re-read cannot report it: a refused row and an untouched row look
        // identical once the table refreshes.
        lastApproval = result;
        reopenAfterWrite();
    }

    // ===================== Overriding ====================================

    /**
     * Changes one grade's score, with the reason that must accompany it (E12.3, S-23).
     *
     * @param gradeId       the grade to change
     * @param newScore      the score she wants, 0..100
     * @param justification why, non-blank
     * @return {@code true} when the request was sent
     */
    public boolean override(long gradeId, int newScore, String justification) {
        return override(gradeId, newScore, justification, null);
    }

    /**
     * Changes one grade's score, with the reason for the record and optionally a comment for
     * the student (E12.3, S-22/S-23).
     *
     * <p>Validated here as well as on the server, and neither check is redundant. The server's
     * is the one that matters; this one exists so a teacher who leaves the reason blank is told
     * so before her request travels, rather than after.
     *
     * <p><b>The comment is never validated.</b> It is optional, and a blank one is turned into
     * {@code null} by the request record rather than by a rule here, so the client and the
     * server agree about what "she wrote nothing" is without either of them having to check
     * (the two tiers running the same rule, ARCHITECTURE §3). Null does not clear an existing
     * comment; {@link GradingCopy#COMMENT_LABEL} says so on the dialog.
     *
     * @param gradeId        the grade to change
     * @param newScore       the score she wants, 0..100
     * @param justification  why, non-blank
     * @param teacherComment the note for the student, or {@code null}/blank for none
     * @return {@code true} when the request was sent
     */
    public boolean override(long gradeId, int newScore, String justification,
                            String teacherComment) {
        if (busy) {
            return false;
        }
        if (justification == null || justification.isBlank()) {
            error = GradingCopy.JUSTIFICATION_REQUIRED;
            onChange.run();
            return false;
        }
        if (newScore < GradeOverrideRequest.MIN_SCORE || newScore > GradeOverrideRequest.MAX_SCORE) {
            error = GradingCopy.SCORE_OUT_OF_RANGE;
            onChange.run();
            return false;
        }

        busy = true;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADE_OVERRIDE,
                        new GradeOverrideRequest(gradeId, newScore, justification, teacherComment))
                .whenComplete((response, failure) -> poster.run(() -> settleOverride(response, failure)));
        return true;
    }

    private void settleOverride(Message response, Throwable failure) {
        busy = false;
        if (failure != null || response == null) {
            error = GradingCopy.APPROVE_FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            // CONFLICT is the contract's answer to overriding an approved grade, and it is a
            // state rather than a fault: she is told what happened, not that something broke.
            error = response.getErrorCode() == ErrorCode.CONFLICT
                    ? GradingCopy.OVERRIDE_CONFLICT
                    : GradingCopy.APPROVE_FAILED;
            onChange.run();
            return;
        }
        // The response carries the refreshed review, but the table needs the whole sitting:
        // an override changes one row's score and state, and the summary's counts with it.
        reopenAfterWrite();
    }

    /** Re-reads the open sitting and the queue, because both may have changed. */
    private void reopenAfterWrite() {
        if (open == null) {
            onChange.run();
            return;
        }
        long executionId = open.summary().executionId();
        onChange.run();
        requestExecution(executionId);
        // The queue's counts moved too, and a sitting that just became fully approved should
        // leave it. Requested after, so a failure here cannot lose the table she is looking at.
        dispatcher.send(Verb.GRADING_QUEUE_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> {
                    if (isOk(response, failure)
                            && response.getPayload() instanceof GradingQueue payload) {
                        queue = payload.executions();
                        state = AsyncViewState.forResult(queue);
                        onChange.run();
                    }
                }));
    }

    // ===================== Selection =====================================

    /**
     * Ticks or unticks one row.
     *
     * @param gradeId  the row
     * @param selected whether it should be ticked
     */
    public void select(long gradeId, boolean selected) {
        if (selected) {
            if (!this.selected.contains(gradeId)) {
                this.selected.add(gradeId);
            }
        } else {
            this.selected.remove(gradeId);
        }
        onChange.run();
    }

    /** Unticks everything. */
    public void clearSelection() {
        selected.clear();
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current queue state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the sittings waiting on her. */
    public List<ExecutionGradingSummary> queue() {
        return queue;
    }

    /** @return the sitting currently open, when one is. */
    public Optional<ExecutionGrades> openExecution() {
        return Optional.ofNullable(open);
    }

    /** @return the open sitting's rows; empty when none is open. */
    public List<StudentGradeRow> rows() {
        return open == null ? List.of() : open.rows();
    }

    /** @return the ticked grade ids. */
    public List<Long> selection() {
        return List.copyOf(selected);
    }

    /** @return how many rows are ticked, which is what the confirmation counts. */
    public int selectionSize() {
        return selected.size();
    }

    /** @return the result of the last approval, for reporting refused ids. */
    public Optional<ApproveResult> lastApproval() {
        return Optional.ofNullable(lastApproval);
    }

    /** @return the sentence to show, when something went wrong. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while a request is in flight; the screen disables its buttons. */
    public boolean isBusy() {
        return busy || state == AsyncViewState.LOADING;
    }

    // ===================== Plumbing ======================================

    private static boolean isOk(Message response, Throwable failure) {
        return failure == null && response != null && !response.isError();
    }

    private void fail(String message) {
        error = message;
        state = AsyncViewState.ERROR;
        onChange.run();
    }
}
