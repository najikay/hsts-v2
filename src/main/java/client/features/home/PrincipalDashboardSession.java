package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import common.dto.report.DataExams;
import common.dto.report.DataResults;
import common.protocol.Message;
import common.protocol.Verb;
import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import org.greenrobot.eventbus.Subscribe;

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

    /** How many of {@link #load}'s reads are outstanding; the {@link #refresh} guard (U-63). */
    private int pending;

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

    // ===================== Live (U-63) ===================================

    /**
     * Subscribes the dashboard to the app bus (NFR-18, U-63).
     *
     * <p><b>{@link #onServerPush(ServerPushEvent)} must stay public on a public class</b>: the
     * bus invokes reflectively, and a subscriber it cannot reach registers happily and then
     * fails silently on every delivery.
     *
     * @param eventBus the app bus; pushes arrive on it already on the FX thread
     * @return this, for chaining beside {@link #onChange(Runnable)}
     */
    public PrincipalDashboardSession subscribeTo(ClientEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus").register(this);
        return this;
    }

    /**
     * A server push landed; re-read if it changes what these cards say (U-63).
     *
     * <p><b>{@code EXECUTION_CLOSED} is the only trigger, and that is a statement about this
     * role rather than an omission.</b> S-7 makes the principal read-only, so the only
     * notification the server ever addresses to her is a sitting finishing, which is what her
     * sittings card counts. The exams card would want "an exam was authored" and no push says
     * that today; it is named here so the next person to add one knows this is where it goes,
     * rather than reaching for a timer.
     *
     * @param event the push, straight off the bus
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION) {
            return;
        }
        if (event.payload() instanceof NotificationDto item
                && item.type() == NotificationType.EXECUTION_CLOSED) {
            refresh();
        }
    }

    /**
     * Re-reads both cards (U-63).
     *
     * <p>Ignored while a read is in flight: those answers are at least as new as this push, and
     * a second pair of requests could settle out of order and put the older counts back.
     */
    public void refresh() {
        if (pending > 0) {
            return;
        }
        // Without the blanking (S3 sweep): routing through load() flashed both cards back
        // to skeletons on every push. The settled cards stay until each answer lands.
        sendReads();
    }

    /** Sends both catalogue reads. */
    public void load() {
        exams = loadingExams();
        sittings = loadingSittings();
        examCount = 0;
        sittingCount = 0;
        onChange.run();
        sendReads();
    }

    /** The two reads, shared by the blanking visit and the quiet push re-read (U-63). */
    private void sendReads() {
        pending = 2;
        dispatcher.send(Verb.DATA_EXAMS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settled(() -> settleExams(response, failure))));
        dispatcher.send(Verb.DATA_RESULTS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settled(() -> settleSittings(response, failure))));
    }

    /**
     * Runs one settle and counts it off (U-63).
     *
     * <p>The decrement is here rather than at the top of each settle, which returns early on
     * its failure path: a counter that leaks on an error is a dashboard that stops refreshing
     * after the first dropped connection.
     */
    private void settled(Runnable settle) {
        pending = Math.max(pending - 1, 0);
        settle.run();
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
