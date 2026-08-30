package client.features.data;

import client.events.FxThreadPoster;
import client.features.bank.BankSession;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionImageRequest;
import common.dto.bank.QuestionRequest;
import common.dto.bank.VersionHistory;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the principal's question detail (Presentation tier, E15.2 — F9.3, S-7, U-44,
 * the lead's ruling of 2026-08-30).
 *
 * <p>Three verbs and all three are reads that already admitted her: {@code QUESTION_GET},
 * {@code QUESTION_VERSIONS} and {@code QUESTION_IMAGE_GET} have carried PRINCIPAL on their role
 * list since E6 (BANK contract section 3), so this screen needed no amendment and no widening.
 * That is why the class is short: the whole question is which of the three answers has landed.
 *
 * <h2>Not {@code BankSession}, and that is the S-7 argument ⚑</h2>
 *
 * <p>The obvious move was to reuse the bank's session, which already loads all three. It also
 * sends {@code QUESTION_DELETE} and holds the page, the filters and the edit-lock merge. Handing
 * the principal an object whose surface includes a delete would make "she has no mutating verb" a
 * fact about which methods her screen happens to call, and that is a weaker guarantee than the
 * one T-11.3 asks for. This class has no method that writes, so a reviewer can settle the
 * question by reading its public surface. What <em>is</em> shared is the rendering
 * ({@code QuestionDetailPane}) and the version-timeline pairing
 * ({@link BankSession#timeline}), because those are the parts where a second copy could drift.
 *
 * <h2>The history is loaded with the question, not behind a toggle</h2>
 *
 * <p>The bank hides it behind {@code Version history} because that screen is a master-detail
 * browse and a teacher clicking down a list would pay for a timeline she did not ask for. This
 * screen is one question, opened deliberately, on a role whose entire purpose here is to read the
 * data as entered (F9.3). So both requests go out together and the panel renders whichever way it
 * settles: a failed timeline says so beside a question that is still on screen.
 *
 * <h2>Late answers are dropped, not applied ⚑</h2>
 *
 * <p>{@code DataQuestionView} builds one session and calls {@link #open} from {@code onShow},
 * which runs on every navigation, so two visits to two questions share this object and the second
 * can begin before the first has answered. Every answer is checked against
 * {@link #requestedId} — the same rule {@code BankSession}, {@code ExamPreviewSession} and
 * {@code CheckedFormSession} apply.
 */
public final class DataQuestionSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private QuestionDetail detail;
    private String error;

    private AsyncViewState historyState = AsyncViewState.IDLE;
    private VersionHistory history;
    private String historyError;

    private AsyncViewState imageState = AsyncViewState.IDLE;
    private byte[] image;

    /** The question on screen, and what every late answer is checked against ⚑. */
    private String requestedId;

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public DataQuestionSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public DataQuestionSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Opens one question, with its history.
     *
     * @param displayId5 the five-digit display id off the Questions row; blank does nothing,
     *                   because a screen opened with no parameter has nothing to ask about
     */
    public void open(String displayId5) {
        String asked = displayId5 == null ? "" : displayId5.strip();
        if (asked.isEmpty()) {
            return;
        }
        if (state == AsyncViewState.LOADING && asked.equals(requestedId)) {
            // Already asking this exact question. Scoped to the id rather than to the state: a
            // bare "am I loading" guard would drop a request for a DIFFERENT question, and since
            // the view calls this from onShow on every navigation with one reused session, that
            // is a principal opening question B and being shown question A.
            return;
        }
        requestedId = asked;
        state = AsyncViewState.LOADING;
        error = null;
        detail = null;
        history = null;
        historyError = null;
        historyState = AsyncViewState.LOADING;
        image = null;
        imageState = AsyncViewState.IDLE;
        onChange.run();

        dispatcher.send(Verb.QUESTION_GET, new QuestionRequest(asked))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleDetail(asked, response, failure)));
        dispatcher.send(Verb.QUESTION_VERSIONS, new QuestionRequest(asked))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleHistory(asked, response, failure)));
    }

    private void settleDetail(String asked, Message response, Throwable failure) {
        if (!asked.equals(requestedId)) {
            // She opened another question while this was in flight. Adopting it would put one
            // question's answers under another one's heading.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof QuestionDetail loaded)) {
            detail = null;
            error = DataDetailCopy.QUESTION_FAILED_HINT;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        detail = loaded;
        error = null;
        state = AsyncViewState.READY;
        if (loaded.hasImage()) {
            requestImage(asked, loaded);
        }
        onChange.run();
    }

    private void settleHistory(String asked, Message response, Throwable failure) {
        if (!asked.equals(requestedId)) {
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof VersionHistory loaded)) {
            history = null;
            historyError = DataDetailCopy.HISTORY_FAILED;
            historyState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        history = loaded;
        historyError = null;
        historyState = AsyncViewState.forResult(loaded.versions());
        onChange.run();
    }

    /**
     * The illustration, once the detail says there is one (F2.4).
     *
     * <p>Addressed by version, as the verb is, and asked for only when {@code hasImage} says so:
     * neither the list nor the detail carries bytes, so a question with no picture costs no
     * request at all.
     */
    private void requestImage(String asked, QuestionDetail loaded) {
        imageState = AsyncViewState.LOADING;
        dispatcher.send(Verb.QUESTION_IMAGE_GET,
                        new QuestionImageRequest(asked, loaded.versionNo()))
                .whenComplete((response, failure) -> poster.run(() -> {
                    if (!asked.equals(requestedId)) {
                        return;
                    }
                    if (failure != null || response == null || response.isError()
                            || !(response.getPayload() instanceof QuestionImage picture)) {
                        image = null;
                        imageState = AsyncViewState.ERROR;
                        onChange.run();
                        return;
                    }
                    image = picture.bytes();
                    imageState = AsyncViewState.READY;
                    onChange.run();
                }));
    }

    // ===================== What the screen reads =========================

    /** @return the question's load state: skeleton, content or error. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded question, when there is one. */
    public Optional<QuestionDetail> detail() {
        return Optional.ofNullable(detail);
    }

    /** @return the failure sentence when the question could not be opened. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while the question's own request is in flight. */
    public boolean isLoading() {
        return state.showsSkeleton();
    }

    /** @return the version timeline's load state. */
    public AsyncViewState historyState() {
        return historyState;
    }

    /**
     * @return the version timeline, newest first, each entry paired with what changed since the
     *         version before it. Built by {@link BankSession#timeline}, so the principal's
     *         history and the teacher's cannot describe the same two versions differently
     */
    public List<BankSession.HistoryEntry> historyEntries() {
        return BankSession.timeline(history);
    }

    /** @return the timeline's own failure sentence, which is never the screen's. */
    public Optional<String> historyError() {
        return Optional.ofNullable(historyError);
    }

    /** @return the illustration's load state; {@code IDLE} when the question has no picture. */
    public AsyncViewState imageState() {
        return imageState;
    }

    /** @return the illustration's bytes, or {@code null} until they arrive. */
    public byte[] image() {
        return image;
    }
}
