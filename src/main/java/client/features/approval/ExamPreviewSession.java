package client.features.approval;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ApprovalDecision;
import common.dto.approval.ExamApproveRequest;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.approval.ExamRejectRequest;
import common.dto.exam.ExamQuestion;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The logic behind the exam preview and the two decisions on it (Presentation tier,
 * E8.4/E8.5 — F4.1, F4.2).
 *
 * <p>Everything the screen decides lives here: what is loaded, which decision is in flight,
 * what a refusal means, and what the answer key says for a given question. The view renders
 * it. That is what lets the whole approve-and-reject state machine be tested against
 * {@code FakeClientConnection} with no toolkit (TEAM_SPLIT §3.2).
 *
 * <h2>The lock version is not the screen's to invent</h2>
 *
 * <p>Both decisions echo the {@code lockVersion} the loaded preview carried. This class holds
 * exactly one preview at a time and reads that value off it, so a decision can only ever be
 * sent for the version currently on screen. When the server refuses with {@code CONFLICT} the
 * session reloads rather than retrying: the whole meaning of that refusal is that what the
 * coordinator is looking at is out of date, and a retry would send the same stale number
 * again.
 *
 * <h2>The screen is reused, so a late answer has to name its target ⚑</h2>
 *
 * <p>{@code ExamPreviewView} builds this session once and calls {@link #open(long)} from
 * {@code onShow}, which runs on every navigation. Two visits to two different versions therefore
 * share one session, and the second can begin before the first has answered. Every load carries
 * the version it was issued for and {@link #requestedVersionId} is what an answer is checked
 * against — the same rule {@code BankSession} and {@code TeacherResultsSession} apply, and the
 * one this class was missing.
 *
 * <h2>One decision at a time</h2>
 *
 * <p>{@link #isDeciding()} gates both actions. Approve and reject write the same column of
 * the same row, and letting a coordinator fire both while the first is in flight would make
 * the outcome depend on network timing.
 */
public final class ExamPreviewSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };
    private Consumer<ApprovalDecision> onDecided = decision -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private ExamPreview preview;
    private String error;
    private String decisionError;
    private boolean deciding;

    /**
     * The version the screen is currently asking about, and the guard on every late answer ⚑.
     *
     * <p>See the class javadoc's "The screen is reused" section. This is the target, not a
     * counter: {@link #reload} deliberately re-asks for the same version, so a counter would have
     * to be bumped in two places to mean the same thing, and an answer for the version on screen
     * is welcome however many requests preceded it.
     */
    private long requestedVersionId;

    /**
     * @param dispatcher the request correlator
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public ExamPreviewSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ExamPreviewSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Registers what happens once a decision lands: a toast, and back to the queue. */
    public ExamPreviewSession onDecided(Consumer<ApprovalDecision> listener) {
        this.onDecided = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Opens one exam version for review.
     *
     * @param examVersionId the version, from the queue row or from a notification's reference
     */
    public void open(long examVersionId) {
        // A fresh visit starts clean: whatever a previous decision was refused for belongs
        // to the screen that was refused, not to this one.
        open(examVersionId, true);
    }

    /**
     * Re-reads the version on screen, which is what a {@code CONFLICT} calls for.
     *
     * <p><b>Keeps the refusal message.</b> The whole reason for reloading after a refused
     * decision is that the coordinator is holding a row the server disagrees with, and the
     * sentence explaining that is the one thing she needs while the fresh row arrives.
     * Clearing it here would mean the screen quietly repainted and said nothing, which is how
     * somebody presses the same button twice.
     */
    public void reload() {
        preview().map(p -> p.summary().examVersionId()).ifPresent(id -> open(id, false, true));
    }

    private void open(long examVersionId, boolean clearDecisionError) {
        open(examVersionId, clearDecisionError, false);
    }

    /**
     * Asks for one version, unless that exact question is already outstanding ⚑.
     *
     * <p>The re-entrancy guard is scoped <b>to the target</b>, and that scoping is the fix for the
     * 4.1 defect. It used to read {@code if (state == LOADING) return}, which is right for the
     * case it was written for — a double-click on the same row must not send two requests — and
     * wrong for the one it also caught. {@code ExamPreviewView.onShow} calls {@link #open(long)}
     * on <em>every</em> navigation and the view, hence this session, is built once and reused. So
     * a coordinator who opened version A, went back to the queue and opened version B before A's
     * answer landed had her request for B <b>silently dropped</b>, and then watched A paint itself
     * onto the screen she had asked for B on — with Approve and Reject live against A's id and A's
     * {@code lockVersion}. Approving the wrong exam is a click away and nothing on screen says so.
     *
     * <p>Now a different version always travels, and {@link #settle} discards any answer that is
     * not about {@link #requestedVersionId}. {@code force} exists for {@link #reload}, whose whole
     * job is to re-ask about the version already on screen after a {@code CONFLICT}.
     */
    private void open(long examVersionId, boolean clearDecisionError, boolean force) {
        if (!force && state == AsyncViewState.LOADING && examVersionId == requestedVersionId) {
            return;
        }
        requestedVersionId = examVersionId;
        state = AsyncViewState.LOADING;
        error = null;
        if (clearDecisionError) {
            decisionError = null;
        }
        onChange.run();

        dispatcher.send(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(examVersionId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settle(examVersionId, response, failure)));
    }

    private void settle(long asked, Message response, Throwable failure) {
        if (asked != requestedVersionId) {
            // She moved on while this was in flight. Adopting it would put one exam's paper under
            // another one's heading, with the decision buttons wired to the one she cannot see.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ExamPreview loaded)) {
            preview = null;
            error = ApprovalCopy.PREVIEW_LOAD_FAILED;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        preview = loaded;
        error = null;
        // A version with no questions is loaded, not empty: the metadata and the decision
        // buttons are still the point of the screen, and the paper area says so itself.
        state = AsyncViewState.READY;
        onChange.run();
    }

    // ===================== Decisions =====================================

    /**
     * Approves the version on screen (F4.2).
     *
     * <p>Does nothing without a loaded preview, because there would be no {@code lockVersion}
     * to send and the request would be a guess.
     */
    public void approve() {
        if (preview == null || deciding) {
            return;
        }
        deciding = true;
        decisionError = null;
        onChange.run();

        dispatcher.send(Verb.EXAM_APPROVE, new ExamApproveRequest(
                        preview.summary().examVersionId(), preview.summary().lockVersion()))
                .whenComplete((response, failure) -> poster.run(() -> settleDecision(response, failure)));
    }

    /**
     * Sends the version on screen back, with a reason (F4.2).
     *
     * <p>The reason is validated locally first, by the same
     * {@link ExamRejectRequest#validate} the server runs. Not because the server needs the
     * help, but because a coordinator who typed three characters should be told before the
     * round trip, and telling her with the same rule means the two can never disagree.
     *
     * @param reason what she typed
     */
    public void reject(String reason) {
        if (preview == null || deciding) {
            return;
        }
        Optional<String> complaint = ExamRejectRequest.validate(reason);
        if (complaint.isPresent()) {
            decisionError = complaint.get();
            onChange.run();
            return;
        }
        deciding = true;
        decisionError = null;
        onChange.run();

        dispatcher.send(Verb.EXAM_REJECT, new ExamRejectRequest(
                        preview.summary().examVersionId(), reason, preview.summary().lockVersion()))
                .whenComplete((response, failure) -> poster.run(() -> settleDecision(response, failure)));
    }

    private void settleDecision(Message response, Throwable failure) {
        deciding = false;
        if (failure != null || response == null) {
            decisionError = ApprovalCopy.PREVIEW_LOAD_FAILED;
            onChange.run();
            return;
        }
        if (response.isError()) {
            // The server's sentence, not one of ours: it is the side that knows whether this
            // was a stale row, a reason that was too short, or a subject that is not hers,
            // and each of those already says what to do next.
            decisionError = response.errorMessage();
            onChange.run();
            // A refused decision leaves the screen holding a row the server disagrees with,
            // so the honest next step is a fresh read rather than the same buttons.
            reload();
            return;
        }
        if (!(response.getPayload() instanceof ApprovalDecision decision)) {
            decisionError = ApprovalCopy.PREVIEW_LOAD_FAILED;
            onChange.run();
            return;
        }
        decisionError = null;
        onChange.run();
        onDecided.accept(decision);
    }

    // ===================== What the screen reads =========================

    /** @return the current view state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded preview, when there is one. */
    public Optional<ExamPreview> preview() {
        return Optional.ofNullable(preview);
    }

    /** @return the paper, in the student's own wire type; empty before it loads. */
    public List<ExamQuestion> questions() {
        return preview == null ? List.of() : preview.questions();
    }

    /** @return the load error, when the preview could not be opened. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return the server's refusal of the last decision, when there was one. */
    public Optional<String> decisionError() {
        return Optional.ofNullable(decisionError);
    }

    /** @return true while a decision is in flight; both buttons are disabled. */
    public boolean isDeciding() {
        return deciding;
    }

    /** @return true when there is a loaded version that can still be decided on. */
    public boolean canDecide() {
        return preview != null && !deciding && preview.summary().state().isPending();
    }

    /**
     * @return whether the coordinator wrote the exam she is looking at (F4.3). Shown as a
     *         badge and as an extra line in the confirm dialog, never as an obstacle
     */
    public boolean isSelfAuthored() {
        return preview != null && preview.summary().selfAuthored();
    }

    /**
     * @param question a question on the paper
     * @return the option marked correct for it, or {@code 0} when the preview carries no key
     *         for it, which the panel renders as "not available" rather than as option 0
     */
    public int correctOptionFor(ExamQuestion question) {
        Objects.requireNonNull(question, "question");
        return preview == null ? 0 : preview.teacherOnly().correctOptionOf(question.questionVersionId());
    }
}
