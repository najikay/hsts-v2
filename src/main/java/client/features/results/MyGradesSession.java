package client.features.results;

import client.events.ClientEventBus;
import client.events.FxThreadPoster;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import common.dto.grading.MyGrades;
import common.dto.grading.StudentGradeRow;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;

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

    /**
     * Subscribes this session to {@code PUSH_GRADE_PUBLISHED} (E13.6).
     *
     * <p>The subscription lives here rather than on the screen, and that is the point: the view
     * is a thin renderer excluded from the coverage gate, so a live-refresh path wired there
     * would be a behaviour nothing measures. Here it has a test.
     *
     * <p>Optional by design — {@link #load()} alone is a complete screen, and the existing tests
     * that never call this still describe a working session. A student whose bus is not wired
     * sees her grades on open and simply does not see them arrive.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this session, so the screen can chain it after {@link #onChange}
     */
    public MyGradesSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * Applies a {@code PUSH_GRADE_PUBLISHED} by re-reading the list.
     *
     * <p>One generic event type carries every push (E1.8), so this ignores everything that is
     * not its own verb. <b>The payload is deliberately not read.</b> It carries the published
     * row, and appending it would be faster — and would also be the one code path capable of
     * putting a grade on this screen that the server did not just list. Re-querying costs one
     * round trip and removes that possibility entirely.
     *
     * @param event any server push
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_GRADE_PUBLISHED) {
            return;
        }
        onGradePublished();
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
