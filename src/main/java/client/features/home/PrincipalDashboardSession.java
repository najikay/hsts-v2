package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;

/**
 * The principal's school snapshot (Presentation tier, UI wave 1 — F-10).
 *
 * <h2>Two counts, both already on the wire</h2>
 *
 * <p>{@code DATA_EXAMS_GET} and {@code DATA_RESULTS_GET} are the Data screen's own
 * reads, and both are unpaginated catalogues (PRD §6), so the row count <i>is</i>
 * the school total. No aggregate verb is invented for this: the numbers a
 * principal wants on a home screen are the sizes of two lists she can already
 * open, and each card opens the list it counted.
 *
 * <p>They settle independently, so a failing catalogue leaves the other card
 * intact rather than blanking the page.
 *
 * <p>What is deliberately <b>not</b> here: an average, a pass rate or a trend.
 * Those are {@code REPORT_GET}'s answers and they belong to the Reports screen,
 * which frames them with the dimension and subject they are about. A single
 * school-wide mean on a dashboard is a number with no question attached, and it
 * is the kind of number that ends up quoted.
 */
public final class PrincipalDashboardSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DashboardCard exams = loadingExams();
    private DashboardCard sittings = loadingSittings();

    private int examCount;
    private int sittingCount;

    public PrincipalDashboardSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public PrincipalDashboardSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** @return the cards, in the order they are laid out. */
    public List<DashboardCard> cards() {
        return List.of(exams, sittings);
    }

    /**
     * @return the one sentence under the greeting, composed from the two counts
     *         these cards already loaded
     */
    public String summary() {
        boolean unloaded = exams.state() == DashboardCard.State.LOADING
                || sittings.state() == DashboardCard.State.LOADING;
        boolean allFailed = exams.state() == DashboardCard.State.FAILED
                && sittings.state() == DashboardCard.State.FAILED;
        return DashboardSummary.principal(examCount, sittingCount, unloaded, allFailed);
    }

    /** Sends both catalogue reads. */
    public void load() {
        exams = loadingExams();
        sittings = loadingSittings();
        examCount = 0;
        sittingCount = 0;
        onChange.run();

        dispatcher.send(Verb.DATA_EXAMS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleExams(response, failure)));
        dispatcher.send(Verb.DATA_RESULTS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleSittings(response, failure)));
    }

    private void settleExams(Message response, Throwable failure) {
        if (!(TeacherDashboardSession.payloadOf(response, failure) instanceof DataExams answer)) {
            exams = DashboardCard.failed(DashboardCopy.SCHOOL_EXAMS_KICKER,
                    DashboardCopy.SCHOOL_EXAMS_TITLE, DashboardCopy.SCHOOL_EXAMS_LINK,
                    Routes.DATA.id());
            onChange.run();
            return;
        }
        examCount = answer.exams().size();
        exams = DashboardCard.counted(DashboardCopy.SCHOOL_EXAMS_KICKER,
                DashboardCopy.SCHOOL_EXAMS_TITLE, examCount, DashboardCopy.SCHOOL_EXAMS_HINT,
                DashboardCopy.SCHOOL_EXAMS_EMPTY, DashboardCopy.SCHOOL_EXAMS_LINK,
                Routes.DATA.id());
        onChange.run();
    }

    private void settleSittings(Message response, Throwable failure) {
        if (!(TeacherDashboardSession.payloadOf(response, failure) instanceof DataResults answer)) {
            sittings = DashboardCard.failed(DashboardCopy.SCHOOL_SITTINGS_KICKER,
                    DashboardCopy.SCHOOL_SITTINGS_TITLE, DashboardCopy.SCHOOL_SITTINGS_LINK,
                    Routes.REPORTS.id());
            onChange.run();
            return;
        }
        sittingCount = answer.sittings().size();
        sittings = DashboardCard.counted(DashboardCopy.SCHOOL_SITTINGS_KICKER,
                DashboardCopy.SCHOOL_SITTINGS_TITLE, sittingCount,
                DashboardCopy.SCHOOL_SITTINGS_HINT, DashboardCopy.SCHOOL_SITTINGS_EMPTY,
                DashboardCopy.SCHOOL_SITTINGS_LINK, Routes.REPORTS.id());
        onChange.run();
    }

    private static DashboardCard loadingExams() {
        return DashboardCard.loading(DashboardCopy.SCHOOL_EXAMS_KICKER,
                DashboardCopy.SCHOOL_EXAMS_TITLE, DashboardCopy.SCHOOL_EXAMS_HINT,
                DashboardCopy.SCHOOL_EXAMS_LINK, Routes.DATA.id());
    }

    private static DashboardCard loadingSittings() {
        return DashboardCard.loading(DashboardCopy.SCHOOL_SITTINGS_KICKER,
                DashboardCopy.SCHOOL_SITTINGS_TITLE, DashboardCopy.SCHOOL_SITTINGS_HINT,
                DashboardCopy.SCHOOL_SITTINGS_LINK, Routes.REPORTS.id());
    }
}
