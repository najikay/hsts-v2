package client.features.data;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamPreviewRequest;
import common.dto.exam.ExamQuestion;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the principal's exam detail (Presentation tier, E15.2 — F9.3, S-7, U-44, the
 * lead's ruling of 2026-08-30).
 *
 * <p>One verb, {@code EXAM_PREVIEW_GET}, which admits her school-wide since APPROVAL amendment A1
 * of 2026-08-30. The version it is addressed by comes off the catalogue row
 * ({@code DataExamRow.latestVersionId}, REPORTS amendment A2), so opening an exam is one request
 * and no lookup.
 *
 * <h2>Not {@code ExamPreviewSession}, and that is the S-7 argument ⚑</h2>
 *
 * <p>The coordinator's session loads the same preview, and it also holds a decision's worth of
 * state: an in-flight flag, a refusal sentence, the {@code lockVersion} to echo, and two methods
 * that send {@code EXAM_APPROVE} and {@code EXAM_REJECT}. Reusing it would put both writes one call away from the principal's screen
 * and make "she has no mutating verb" a fact about which methods that screen happens to call.
 * This class has no method that writes and no field about a decision, so the claim is settled by
 * reading its public surface — which is what T-11.3 asks a reviewer to do.
 *
 * <p>What <em>is</em> shared is the part where a copy could drift: the paper is drawn by
 * {@code ExamPaperPane} over {@code ExamQuestion}, the student's own component over the student's
 * own wire type, exactly as the coordinator's screen draws it. E8's whole claim is that there is
 * no second renderer; a second renderer built for the principal would be the drift that claim
 * rules out.
 *
 * <h2>Late answers are dropped, not applied ⚑</h2>
 *
 * <p>The view builds one session and calls {@link #open} from {@code onShow}, so two visits to
 * two exams share it. Every answer is checked against {@link #requestedVersionId}, the same rule
 * {@code ExamPreviewSession} learned the hard way.
 */
public final class DataExamSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private ExamPreview preview;
    private String error;

    /** The version on screen, and the guard on every late answer ⚑. */
    private long requestedVersionId;

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public DataExamSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public DataExamSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Opens one exam version, read-only.
     *
     * @param examVersionId the latest version off the catalogue row; anything not positive is
     *                      ignored, because a row that carries no version has nothing to open
     *                      and a request for version 0 would be a guess
     */
    public void open(long examVersionId) {
        if (examVersionId <= 0) {
            error = DataDetailCopy.EXAM_NOT_OPENABLE;
            preview = null;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        if (state == AsyncViewState.LOADING && examVersionId == requestedVersionId) {
            return;
        }
        requestedVersionId = examVersionId;
        state = AsyncViewState.LOADING;
        error = null;
        preview = null;
        onChange.run();

        dispatcher.send(Verb.EXAM_PREVIEW_GET, new ExamPreviewRequest(examVersionId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settle(examVersionId, response, failure)));
    }

    private void settle(long asked, Message response, Throwable failure) {
        if (asked != requestedVersionId) {
            // She moved on while this was in flight. Adopting it would put one exam's paper
            // under another one's heading.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ExamPreview loaded)) {
            preview = null;
            error = DataDetailCopy.EXAM_FAILED_HINT;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        preview = loaded;
        error = null;
        // A version with no questions is loaded, not empty: the heading and the teacher-only
        // block are still worth reading, and the paper area says so itself.
        state = AsyncViewState.READY;
        onChange.run();
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

    /** @return the failure sentence when the exam could not be opened. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while the request is in flight. */
    public boolean isLoading() {
        return state.showsSkeleton();
    }

    /**
     * @param question a question on the paper
     * @return the option marked correct for it, or {@code 0} when the preview carries no key for
     *         it. The key comes off the teacher-only block and never off the question, because
     *         {@code ExamQuestion} has nowhere to hold one
     */
    public int correctOptionFor(ExamQuestion question) {
        Objects.requireNonNull(question, "question");
        if (preview == null || preview.teacherOnly() == null) {
            return 0;
        }
        return preview.teacherOnly().correctOptionOf(question.questionVersionId());
    }
}
