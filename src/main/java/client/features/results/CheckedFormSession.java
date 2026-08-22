package client.features.results;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.CheckedForm;
import common.dto.grading.CheckedFormRequest;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the student's checked-form viewer (Presentation tier, E13.4 ⚑ — T-9.2).
 *
 * <p>A thin session over one heavily gated verb. Everything that decides whether she may see
 * this paper happens on the server ({@code CheckedFormService}'s three conditions), and that is
 * deliberate: the client's job here is to render an answer, never to work out whether it should
 * have been given.
 *
 * <p><b>There is no client-side gate and there must not be one.</b> A screen that checked "is it
 * approved" before rendering would imply the server might send an unapproved paper, and the next
 * person would trust that check instead of the server. If a form arrives, it arrived because
 * three server-side conditions held.
 *
 * <p><b>A refusal is not an error.</b> The verb answers one indistinguishable {@code NOT_FOUND}
 * for four different situations — not hers, not approved yet, sitting still open, no such grade
 * — so this session cannot say which, and does not try. It says the paper is not available and
 * offers the way back. Guessing on the student's behalf would be inventing a reason the server
 * deliberately withheld.
 */
public final class CheckedFormSession {

    /** Shown when the request fails outright; says nothing about why (F1.1's discipline). */
    public static final String LOAD_FAILED =
            "This result could not be opened. Please try again.";

    /**
     * Shown when the server refuses.
     *
     * <p>Covers all four refusals with one sentence, because the wire covers them with one
     * answer. It names the commonest innocent cause — grading still in progress — without
     * claiming that is what happened.
     */
    public static final String NOT_AVAILABLE =
            "This result is not available yet. A checked exam can be opened once your teacher "
                    + "has approved the grade and the exam session has ended.";

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };
    private AsyncViewState state = AsyncViewState.IDLE;
    private CheckedForm form;
    private String error;

    public CheckedFormSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public CheckedFormSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Opens one grade as a checked form.
     *
     * @param gradeId the grade to open; her own, or the server refuses
     */
    public void open(long gradeId) {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        form = null;
        onChange.run();

        dispatcher.send(Verb.CHECKED_FORM_GET, new CheckedFormRequest(gradeId))
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    private void settle(Message response, Throwable failure) {
        if (failure != null || response == null) {
            fail(LOAD_FAILED);
            return;
        }
        if (response.isError()) {
            // NOT_FOUND is the contract's single answer to four different refusals, so it is
            // the expected outcome rather than a fault; anything else is a real failure.
            fail(response.getErrorCode() == common.protocol.ErrorCode.NOT_FOUND
                    ? NOT_AVAILABLE
                    : LOAD_FAILED);
            return;
        }
        if (!(response.getPayload() instanceof CheckedForm payload)) {
            fail(LOAD_FAILED);
            return;
        }
        form = payload;
        error = null;
        state = AsyncViewState.READY;
        onChange.run();
    }

    private void fail(String message) {
        form = null;
        error = message;
        state = AsyncViewState.ERROR;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded form, when there is one. */
    public Optional<CheckedForm> form() {
        return Optional.ofNullable(form);
    }

    /** @return the marked questions, in exam order; empty until the form loads. */
    public List<AnswerReviewRow> answers() {
        return form == null ? List.of() : form.answers();
    }

    /** @return the sentence to show when the paper did not open. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while a request is in flight. */
    public boolean isLoading() {
        return state == AsyncViewState.LOADING;
    }
}
