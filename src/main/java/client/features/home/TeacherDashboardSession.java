package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.grading.GradingQueue;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.results.TeacherResults;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;

/**
 * The three numbers on the teacher's dashboard (Presentation tier, UI wave 1 — F-10).
 *
 * <h2>No new verbs</h2>
 *
 * <p>Every card here is a read the teacher's own screens already make:
 * {@code RELEASE_LIST_GET} is the Releases screen's, {@code GRADING_QUEUE_GET} is
 * the Grading screen's, {@code RESULTS_EXAMS_GET} is the Results screen's. That
 * is the constraint the wave was given and it is also the right design: a
 * dashboard is a summary of screens that exist, so a card that needed a verb of
 * its own would be a card pointing at nothing.
 *
 * <p>Three independent reads rather than one aggregate, and they settle
 * independently. A grading queue that fails does not blank the sittings card —
 * each card knows its own outcome, which is why {@link DashboardCard.State} has a
 * {@code FAILED} that is not {@code EMPTY}.
 *
 * <h2>What "today and next" counts</h2>
 *
 * <p>Sittings that are live, plus sittings still scheduled. Not closed ones and
 * not cancelled ones: the card's job is to answer "is anything of mine running or
 * about to", and a teacher who released four exams last term does not want that
 * number. The state is the server's ({@link ReleaseRow#state()}) and is never
 * recomputed here from a clock, for the reason the Releases screen states.
 */
public final class TeacherDashboardSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DashboardCard sittings = DashboardCard.loading(DashboardCopy.SITTINGS_TITLE,
            DashboardCopy.SITTINGS_HINT, Routes.RELEASES.id());
    private DashboardCard grading = DashboardCard.loading(DashboardCopy.GRADING_TITLE,
            DashboardCopy.GRADING_HINT, Routes.GRADING.id());
    private DashboardCard results = DashboardCard.loading(DashboardCopy.RECENT_RESULTS_TITLE,
            DashboardCopy.RECENT_RESULTS_HINT, Routes.RESULTS.id());

    public TeacherDashboardSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public TeacherDashboardSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** @return the cards, in the order they are laid out. */
    public List<DashboardCard> cards() {
        return List.of(sittings, grading, results);
    }

    /** Sends all three reads. Called on every visit to the dashboard. */
    public void load() {
        sittings = DashboardCard.loading(DashboardCopy.SITTINGS_TITLE,
                DashboardCopy.SITTINGS_HINT, Routes.RELEASES.id());
        grading = DashboardCard.loading(DashboardCopy.GRADING_TITLE,
                DashboardCopy.GRADING_HINT, Routes.GRADING.id());
        results = DashboardCard.loading(DashboardCopy.RECENT_RESULTS_TITLE,
                DashboardCopy.RECENT_RESULTS_HINT, Routes.RESULTS.id());
        onChange.run();

        dispatcher.send(Verb.RELEASE_LIST_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleSittings(response, failure)));
        dispatcher.send(Verb.GRADING_QUEUE_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleGrading(response, failure)));
        dispatcher.send(Verb.RESULTS_EXAMS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleResults(response, failure)));
    }

    private void settleSittings(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof ReleaseList list)) {
            sittings = DashboardCard.failed(DashboardCopy.SITTINGS_TITLE, Routes.RELEASES.id());
            onChange.run();
            return;
        }
        int upcoming = (int) list.rows().stream().filter(TeacherDashboardSession::isCurrent).count();
        sittings = DashboardCard.counted(DashboardCopy.SITTINGS_TITLE, upcoming,
                DashboardCopy.SITTINGS_HINT, DashboardCopy.SITTINGS_EMPTY, Routes.RELEASES.id());
        onChange.run();
    }

    private void settleGrading(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof GradingQueue queue)) {
            grading = DashboardCard.failed(DashboardCopy.GRADING_TITLE, Routes.GRADING.id());
            onChange.run();
            return;
        }
        grading = DashboardCard.counted(DashboardCopy.GRADING_TITLE, queue.executions().size(),
                DashboardCopy.GRADING_HINT, DashboardCopy.GRADING_EMPTY, Routes.GRADING.id());
        onChange.run();
    }

    private void settleResults(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof TeacherResults answer)) {
            results = DashboardCard.failed(DashboardCopy.RECENT_RESULTS_TITLE,
                    Routes.RESULTS.id());
            onChange.run();
            return;
        }
        results = DashboardCard.counted(DashboardCopy.RECENT_RESULTS_TITLE, answer.exams().size(),
                DashboardCopy.RECENT_RESULTS_HINT, DashboardCopy.RECENT_RESULTS_EMPTY,
                Routes.RESULTS.id());
        onChange.run();
    }

    /** @return {@code true} for a sitting that is running or still to come. */
    private static boolean isCurrent(ReleaseRow row) {
        return row.state() == ReleaseState.LIVE || row.state() == ReleaseState.SCHEDULED;
    }

    /**
     * @return the response payload, or {@code null} for anything that is not a
     *         successful answer. One place, so no card can forget one of the
     *         three ways a read fails (threw, absent, error message)
     */
    static Object payloadOf(Message response, Throwable failure) {
        if (failure != null || response == null || response.isError()) {
            return null;
        }
        return response.getPayload();
    }
}
