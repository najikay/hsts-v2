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
     * Whether a quiet re-read is in flight underneath rows still on screen (U-63's rule,
     * S3 sweep). Its own flag because a quiet re-read must not announce itself through
     * {@code LOADING}: that state is what the view draws a skeleton from, and a push she
     * never asked for must not blank the one screen that is entirely about her.
     */
    private boolean refreshing;

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
        refresh();
    }

    /**
     * Re-reads the list without blanking it (U-63's discipline, S3 sweep).
     *
     * <p>{@code load()} announces itself through {@code LOADING}, which the screen answers
     * with a skeleton - right for the first visit, wrong for a push: a student reading her
     * grades when one more was published watched the screen blink to a shimmer and every
     * card replay its entrance. The rows on screen are correct and a newer answer is
     * coming, so they stay until it lands. A list that never loaded, or whose load failed,
     * has nothing to keep and takes the ordinary path.
     */
    private void refresh() {
        if (state == AsyncViewState.LOADING || refreshing) {
            return;
        }
        if (state == AsyncViewState.IDLE || state == AsyncViewState.ERROR) {
            load();
            return;
        }
        refreshing = true;
        dispatcher.send(Verb.MY_GRADES_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settle(response, failure)));
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
        boolean quiet = refreshing;
        refreshing = false;
        if (failure != null || response == null) {
            fail(quiet);
            return;
        }
        if (response.isError()) {
            fail(quiet);
            return;
        }
        if (!(response.getPayload() instanceof MyGrades payload)) {
            // A well-formed OK carrying the wrong type is a protocol bug, not a user error;
            // the student still gets a human sentence rather than a stack trace.
            fail(quiet);
            return;
        }
        grades = payload.grades();
        error = null;
        state = AsyncViewState.forResult(grades);
        onChange.run();
    }

    private void fail(boolean quiet) {
        if (quiet) {
            // A failed quiet re-read leaves the screen exactly as it was (the DataSession
            // rule): what is on it is real and a few seconds old, and she never asked for
            // the re-read. The next push asks again.
            return;
        }
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

    // ===================== UI wave 2: the hero band ======================

    /**
     * The term average shown in the hero's ring.
     *
     * <p>A plain mean of the effective scores, unweighted. Weighting by anything
     * — credit, hours, difficulty — would be inventing a rule the school has not
     * given us, and a number a student quotes at a teacher must be a number the
     * school would recognise. It is derived from the rows already loaded and
     * costs no read.
     *
     * @return the mean, or {@code 0} when there is nothing to average
     */
    public double termAverage() {
        return termAverage(grades);
    }

    /**
     * @param rows the published grades
     * @return their unweighted mean, or {@code 0} for an empty list rather than
     *         a NaN that would render as "NaN" inside the ring
     */
    public static double termAverage(List<StudentGradeRow> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return 0;
        }
        return rows.stream().mapToInt(StudentGradeRow::effectiveScore).average().orElse(0);
    }

    /**
     * @return how many different courses the loaded grades span. Case
     *         insensitive and blank-tolerant, so a row that arrived unlabelled
     *         does not become a course of its own
     */
    public int courseCount() {
        return (int) grades.stream()
                .map(StudentGradeRow::courseCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .count();
    }

    /**
     * The hero's right-hand "next exam" slot.
     *
     * <p><b>Always empty in this build, and that is a wire fact rather than an
     * omission.</b> No verb answers "which sitting is next for this student":
     * she reaches a sitting by typing the four-character code her teacher reads
     * out ({@code EXAM_JOIN}, S-18), and there is no "list the sittings I could
     * join" read to ask. Wave 1 dropped the same card from the student dashboard
     * for the same reason and recorded it on {@code StudentDashboardSession}.
     *
     * <p>The slot is built and driven from here anyway rather than left out of
     * the design: the hero hides it gracefully when this is empty, so the day a
     * verb exists this becomes a one-line change in a measured class instead of
     * a layout change in an excluded one.
     *
     * @return the next exam's name and when it opens, or empty
     */
    public Optional<String> nextExam() {
        return Optional.empty();
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
