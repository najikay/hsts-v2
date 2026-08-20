package client.features.results;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the student's <b>My Grades</b> screen (Presentation tier, E13.3).
 *
 * <p>Everything the screen decides lives here and nothing else does: the FXML controller reads
 * {@link #state()} and {@link #grades()} and renders. That is what makes the screen's behaviour
 * — empty state, error message, live refresh — testable against {@code FakeClientConnection}
 * with no JavaFX toolkit (TEAM_SPLIT §3.2), which is also why every state transition below has
 * a test rather than being discovered by clicking.
 *
 * <p><b>Only approved grades ever arrive.</b> `MY_GRADES_GET` returns `APPROVED` rows only
 * (C-3, S-24), so this class does no filtering — a client-side filter would imply the server
 * might send something it should not, and the next person would trust the filter instead of the
 * server. If an unapproved row ever appeared here it would be a server bug, and hiding it in the
 * client is how that bug would survive to the defence.
 *
 * <p><b>No refresh button anywhere</b> (NFR-18). The list loads on open and updates itself when
 * a `PUSH_GRADE_PUBLISHED` arrives — {@link #onGradePublished()} is the hook the push bridge
 * calls, and it re-queries rather than appending, so the screen can never disagree with the
 * server about what the student has.
 */
public final class MyGradesSession {

    /** Shown when the request fails; deliberately says nothing about why (F1.1's discipline). */
    public static final String LOAD_FAILED = "Your grades could not be loaded. Please try again.";

    /** Shown when the student has sat nothing yet — H13.2 wants an explanation, not a blank panel. */
    public static final String NOTHING_YET =
            "No grades yet. Exams you have taken will appear here once your teacher approves them.";

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };
    private AsyncViewState state = AsyncViewState.IDLE;
    private List<StudentGradeRow> grades = List.of();
    private String error;

    /**
     * @param dispatcher the request correlator — the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public MyGradesSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public MyGradesSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Requests the student's grades.
     *
     * <p>Calling this while a load is already in flight is ignored rather than queued: a second
     * identical request would race the first and could settle out of order, leaving the screen
     * showing the older answer.
     */
    public void load() {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        onChange.run();

        dispatcher.send(Verb.MY_GRADES_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
    }

    /**
     * Re-queries after a grade is published (E13.6).
     *
     * <p>Deliberately a re-query and not an append of the pushed row: the push carries one
     * grade, but the list is the server's answer to "what does this student have", and rebuilding
     * from it is the only way the two cannot drift. NFR-18 — the student pressed nothing.
     */
    public void onGradePublished() {
        load();
    }

    private void settle(Message response, Throwable failure) {
        if (failure != null || response == null) {
            fail();
            return;
        }
        if (response.isError()) {
            fail();
            return;
        }
        if (!(response.getPayload() instanceof MyGrades payload)) {
            // A well-formed OK carrying the wrong type is a protocol bug, not a user error;
            // the student still gets a human sentence rather than a stack trace.
            fail();
            return;
        }
        grades = payload.grades();
        error = null;
        state = AsyncViewState.forResult(grades);
        onChange.run();
    }

    private void fail() {
        grades = List.of();
        error = LOAD_FAILED;
        state = AsyncViewState.ERROR;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state — skeleton, content, empty or error. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded grades; empty unless {@link #state()} is {@code READY}. */
    public List<StudentGradeRow> grades() {
        return grades;
    }

    /** @return the error sentence when the load failed. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return the sentence for the empty state, when there is one to show. */
    public Optional<String> emptyMessage() {
        return state == AsyncViewState.EMPTY ? Optional.of(NOTHING_YET) : Optional.empty();
    }

    /** @return true while a request is in flight, for the skeleton and to disable re-entry. */
    public boolean isLoading() {
        return state == AsyncViewState.LOADING;
    }

    /**
     * @param row a loaded row
     * @return true when the teacher changed this grade — the student sees "adjusted by your
     *         teacher" and the comment, never the justification, which the DTO already stripped
     */
    public boolean wasAdjusted(StudentGradeRow row) {
        Objects.requireNonNull(row, "row");
        return row.finalScore() != null && row.finalScore() != row.autoScore();
    }
}
