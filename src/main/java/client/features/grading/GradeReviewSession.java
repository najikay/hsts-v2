package client.features.grading;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.ApproveRequest;
import common.dto.grading.ApproveResult;
import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeReviewRequest;
import common.dto.grading.StudentGradeRow;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the teacher's review of one marked paper (Presentation tier, E12.6 — F8.2).
 *
 * <p>Built 2026-08-30 (live session, U-38) to close the one thing the grading queue could not
 * do: a teacher could approve a paper and change its score without ever being able to open it.
 * Three verbs and one grade — {@code GRADE_REVIEW_GET} to read it, {@code GRADES_APPROVE} to
 * publish it, {@code GRADE_OVERRIDE} to change it — and, as with {@link GradingQueueSession},
 * every decision the screen appears to make is made here so it can be proven against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT §3.2).
 *
 * <h2>The override's answer is adopted; the approval's is not</h2>
 *
 * <p>{@code GRADE_OVERRIDE} answers with the refreshed {@link GradeReview} — the server's own
 * read, not an acknowledgement — and on this screen that object <em>is</em> the whole state, so
 * it is adopted and no second round trip is spent. The queue cannot do that: there, one row's
 * new score also moves the sitting's counts, which the review knows nothing about, so it
 * re-reads instead.
 *
 * <p>{@code GRADES_APPROVE} answers with an {@link ApproveResult}, which is a tally and not a
 * paper, so approving <b>does</b> re-read. A screen that patched the state to APPROVED itself
 * would be guessing at the very moment the server may have refused it.
 *
 * <h2>A refusal is a state, not a fault</h2>
 *
 * <p>{@code CONFLICT} on an override means the grade has already been approved and published,
 * which is the contract working rather than something breaking; she is told what happened. It
 * is also why the screen re-reads after approving: the paper she is looking at has just become
 * one she may no longer change, and the buttons have to say so.
 */
public final class GradeReviewSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private GradeReview review;
    private String error;
    private boolean busy;

    /**
     * The grade this screen is asking about, and what every late answer is checked against ⚑
     * (the generation-guard sweep).
     *
     * <p>{@code GradeReviewView} builds one session in {@code build()} and calls {@link #open}
     * from {@code onShow}, so two visits to two different papers share it and the second can
     * begin before the first has answered. Adopting whichever arrived last would show one
     * student's answers under another student's name, and the approve button beneath them would
     * carry the id of a paper nobody was reading.
     */
    private long requestedGradeId;

    public GradeReviewSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public GradeReviewSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Reading the paper =============================

    /**
     * Opens one grade as a marked paper.
     *
     * <p>Scoped to the grade rather than to the state, for the reason {@link #requestedGradeId}
     * gives: a bare "am I already loading" guard drops the request for a <em>different</em>
     * paper, and this is called from {@code onShow} on every navigation with one reused session.
     *
     * @param gradeId the grade to open; one of hers, or the server refuses
     */
    public void open(long gradeId) {
        if (state == AsyncViewState.LOADING && gradeId == requestedGradeId) {
            return;
        }
        requestedGradeId = gradeId;
        state = AsyncViewState.LOADING;
        error = null;
        review = null;
        onChange.run();
        request(gradeId);
    }

    /** Re-reads the paper currently open, after a write has changed it. */
    private void reread() {
        if (requestedGradeId <= 0) {
            onChange.run();
            return;
        }
        request(requestedGradeId);
    }

    private void request(long gradeId) {
        dispatcher.send(Verb.GRADE_REVIEW_GET, new GradeReviewRequest(gradeId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleReview(gradeId, response, failure)));
    }

    private void settleReview(long asked, Message response, Throwable failure) {
        if (asked != requestedGradeId) {
            // She opened another paper while this was in flight; see requestedGradeId.
            return;
        }
        busy = false;
        if (!isOk(response, failure)
                || !(response.getPayload() instanceof GradeReview payload)) {
            fail(GradingCopy.REVIEW_FAILED);
            return;
        }
        adopt(payload);
    }

    // ===================== Approving =====================================

    /**
     * Approves the paper on screen (E12.2), which publishes it to the student.
     *
     * <p>One grade, sent through the same verb the queue's bulk approve uses: {@code
     * GRADES_APPROVE} takes a list and this list has one id in it. A second single-grade verb
     * would be a second place for the approval rules to live.
     */
    public void approve() {
        if (busy || review == null) {
            return;
        }
        long gradeId = review.grade().gradeId();
        busy = true;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADES_APPROVE, ApproveRequest.one(gradeId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleApproval(response, failure)));
    }

    private void settleApproval(Message response, Throwable failure) {
        if (!isOk(response, failure)
                || !(response.getPayload() instanceof ApproveResult result)) {
            busy = false;
            fail(GradingCopy.APPROVE_FAILED);
            return;
        }
        if (!result.isComplete()) {
            // The server refused this one id. It is the only fact the re-read cannot report,
            // because a refused row and an untouched row come back looking identical.
            busy = false;
            fail(GradingCopy.APPROVE_REFUSED);
            return;
        }
        // A tally is not a paper: re-read rather than patch the state to APPROVED here.
        reread();
    }

    // ===================== Overriding ====================================

    /**
     * Changes this paper's score, with the reason that must accompany it (E12.3, S-23).
     *
     * <p>Validated here as well as on the server, and neither check is redundant. The server's
     * is the one that matters; this one exists so a teacher who leaves the reason blank is told
     * so before her request travels, rather than after.
     *
     * <p><b>The comment is never validated.</b> It is optional, and a blank one is turned into
     * {@code null} by {@link GradeOverrideRequest} rather than by a rule here, so the client and
     * the server agree about "she wrote nothing" without either of them checking. Null does not
     * clear an existing comment; {@link GradingCopy#COMMENT_LABEL} says so on the dialog.
     *
     * @param newScore       the score she wants, 0..100
     * @param justification  why, non-blank
     * @param teacherComment the note for the student, or {@code null}/blank for none
     * @return {@code true} when the request was sent
     */
    public boolean override(int newScore, String justification, String teacherComment) {
        if (busy || review == null) {
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

        long gradeId = review.grade().gradeId();
        busy = true;
        error = null;
        onChange.run();

        dispatcher.send(Verb.GRADE_OVERRIDE,
                        new GradeOverrideRequest(gradeId, newScore, justification, teacherComment))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleOverride(response, failure)));
        return true;
    }

    private void settleOverride(Message response, Throwable failure) {
        busy = false;
        if (failure != null || response == null) {
            fail(GradingCopy.APPROVE_FAILED);
            return;
        }
        if (response.isError()) {
            // CONFLICT is the contract's answer to overriding an approved grade, and it is a
            // state rather than a fault: she is told what happened, not that something broke.
            fail(response.getErrorCode() == ErrorCode.CONFLICT
                    ? GradingCopy.OVERRIDE_CONFLICT
                    : GradingCopy.APPROVE_FAILED);
            return;
        }
        if (!(response.getPayload() instanceof GradeReview payload)) {
            // The contract says an override answers with the refreshed paper. If it did not,
            // asking again is better than leaving the old numbers on screen under a new score.
            reread();
            return;
        }
        adopt(payload);
    }

    // ===================== State =========================================

    private void adopt(GradeReview payload) {
        review = payload;
        error = null;
        state = AsyncViewState.READY;
        onChange.run();
    }

    /**
     * Reports a refusal without dropping the paper she is reading ⚑.
     *
     * <p>Unlike the student's checked form, which has nothing to show once its one request
     * fails, a refusal here usually arrives <em>while a paper is on screen</em> — an override
     * refused as CONFLICT, an approval refused outright. Clearing {@link #review} would answer
     * "that could not be done" by taking away the thing she was looking at.
     */
    private void fail(String message) {
        error = message;
        state = review == null ? AsyncViewState.ERROR : AsyncViewState.READY;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the paper, when one has loaded. */
    public Optional<GradeReview> review() {
        return Optional.ofNullable(review);
    }

    /** @return the header row, when the paper has loaded. */
    public Optional<StudentGradeRow> grade() {
        return review == null ? Optional.empty() : Optional.of(review.grade());
    }

    /** @return the marked questions, in exam order; empty until the paper loads. */
    public List<AnswerReviewRow> answers() {
        return review == null ? List.of() : review.answers();
    }

    /** @return the sentence to show, when something went wrong. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while a request is in flight; the screen disables its buttons. */
    public boolean isBusy() {
        return busy || state == AsyncViewState.LOADING;
    }

    /** @return whether the two actions apply, which is the contract's "still AUTO" rule. */
    public boolean canAct() {
        return review != null && !isBusy() && GradingCopy.canOverride(review.grade());
    }

    // ===================== Plumbing ======================================

    private static boolean isOk(Message response, Throwable failure) {
        return failure == null && response != null && !response.isError();
    }
}
