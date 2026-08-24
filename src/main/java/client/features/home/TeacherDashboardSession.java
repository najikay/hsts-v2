package client.features.home;

import client.core.Routes;
import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.ChipCatalog;
import client.ui.components.logic.ChipSpec;
import client.ui.components.logic.ChipTone;
import common.dto.exam.ExecutionMonitor;
import common.dto.exam.MonitorRequest;
import common.dto.exam.MonitorRow;
import common.dto.grading.GradingQueue;
import common.dto.release.ReleaseList;
import common.dto.release.ReleaseRow;
import common.dto.release.ReleaseState;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ExecutionState;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.Message;
import common.protocol.Verb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The four cards on the teacher's dashboard (Presentation tier, UI wave 1 —
 * F-10; the live and last-closed cards from UI wave 2).
 *
 * <h2>No new verbs</h2>
 *
 * <p>Every read here is one the teacher's own screens already make:
 * {@code RELEASE_LIST_GET} is the Releases screen's, {@code GRADING_QUEUE_GET} is
 * the Grading screen's, {@code RESULTS_EXAMS_GET} and {@code RESULTS_EXECUTION_GET}
 * are the Results screen's, and {@code EXECUTION_MONITOR_GET} is the monitor's.
 * That is the constraint both waves were given and it is also the right design: a
 * dashboard is a summary of screens that exist, so a card that needed a verb of
 * its own would be a card pointing at nothing.
 *
 * <p>The reads settle independently. A grading queue that fails does not blank
 * the live card — each card knows its own outcome, which is why
 * {@link DashboardCard.State} has a {@code FAILED} that is not {@code EMPTY}.
 *
 * <h2>The two follow-up reads, and why they are conditional</h2>
 *
 * <p>Two of wave 2's cards need a detail the list verbs do not carry, so each
 * fires a second read <b>only when there is something to ask about</b>:
 *
 * <ul>
 *   <li><b>The live card</b> asks {@code EXECUTION_MONITOR_GET} for the sitting
 *       that is running, because per-student rows exist nowhere else. Nothing is
 *       live, nothing is asked. Note the documented side effect of that verb: it
 *       registers the caller as a watcher, so the dashboard will receive
 *       {@code PUSH_MONITOR_UPDATED} while it is open. It subscribes to no push,
 *       so those are ignored; that is a cost of one wasted message, weighed
 *       against inventing a read-only variant of a verb, which is a wire change
 *       this wave is not allowed to make.</li>
 *   <li><b>The last-closed card</b> asks {@code RESULTS_EXECUTION_GET} for the
 *       most recently closed execution, because the exam list carries
 *       {@code hasStatistics} but not the statistics. A teacher with no closed
 *       sitting asks nothing.</li>
 * </ul>
 *
 * <h2>What each card counts</h2>
 *
 * <p><b>Live now</b> is sittings whose state is {@code LIVE}. <b>Next release</b>
 * is sittings still {@code SCHEDULED}. Wave 1 had one card counting both, which
 * answered "is anything of mine running or about to" in a single number and
 * therefore answered neither well: a teacher looking at "2" could not tell
 * whether to walk to a classroom. The state is the server's
 * ({@link ReleaseRow#state()}) and is never recomputed here from a clock, for the
 * reason the Releases screen states.
 */
public final class TeacherDashboardSession {

    /** How many student rows the live card shows before deferring to the monitor. */
    public static final int LIVE_STUDENT_ROWS = 3;

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private DashboardCard live = loadingLive();
    private DashboardCard grading = loadingGrading();
    private DashboardCard next = loadingNext();
    private DashboardCard lastClosed = loadingLastClosed();

    private LiveDetail liveDetail;
    private ClosedDetail closedDetail;

    private int liveCount;
    private int gradingCount;

    /**
     * One student on the live card.
     *
     * @param name the student's display name
     * @param chip her attempt state, already turned into a pill by the catalogue
     */
    public record StudentLine(String name, ChipSpec chip) {
    }

    /**
     * Everything the rich live card draws, as a value.
     *
     * @param examName the exam being sat
     * @param code     the four-character execution code
     * @param closesAt when it shuts, extra minutes already included
     * @param serverNow the server's clock at the moment of the read, so the card
     *                  never computes time left from the workstation's clock
     * @param submitted how many have handed in
     * @param sitting   how many started the paper
     * @param students  up to {@link #LIVE_STUDENT_ROWS} of them
     * @param more      whether there are students beyond the ones listed
     */
    public record LiveDetail(String examName, String code, Instant closesAt, Instant serverNow,
                             int submitted, int sitting, List<StudentLine> students,
                             boolean more) {

        public LiveDetail {
            students = List.copyOf(students);
        }

        /**
         * @return how full the progress bar is, in {@code [0, 1]}; zero when
         *         nobody has started, rather than a division by zero
         */
        public double progress() {
            return sitting <= 0 ? 0 : Math.min(1, (double) submitted / sitting);
        }

        /** @return whole minutes left, never negative. */
        public long minutesLeft() {
            if (closesAt == null || serverNow == null) {
                return 0;
            }
            return Math.max(0, java.time.Duration.between(serverNow, closesAt).toMinutes());
        }
    }

    /**
     * Everything the last-closed card draws.
     *
     * @param examName the exam that was sat
     * @param mean     the frozen class mean
     * @param passed   how many reached the pass mark
     * @param sat      how many were marked
     * @param deciles  the ten stored buckets, for the sparkline
     */
    public record ClosedDetail(String examName, double mean, int passed, int sat,
                               List<Integer> deciles) {

        public ClosedDetail {
            deciles = List.copyOf(deciles);
        }
    }

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
        return List.of(live, grading, next, lastClosed);
    }

    /** @return the live sitting's detail, when one is running and its monitor answered. */
    public Optional<LiveDetail> liveDetail() {
        return Optional.ofNullable(liveDetail);
    }

    /** @return the last closed sitting's statistics, when they exist and answered. */
    public Optional<ClosedDetail> closedDetail() {
        return Optional.ofNullable(closedDetail);
    }

    /**
     * @return the one sentence under the greeting, composed from the numbers
     *         these cards already loaded
     */
    public String summary() {
        boolean unloaded = live.state() == DashboardCard.State.LOADING
                || grading.state() == DashboardCard.State.LOADING;
        boolean allFailed = live.state() == DashboardCard.State.FAILED
                && grading.state() == DashboardCard.State.FAILED;
        return DashboardSummary.teacher(liveCount, gradingCount, unloaded, allFailed);
    }

    /** Sends the three list reads. Called on every visit to the dashboard. */
    public void load() {
        live = loadingLive();
        grading = loadingGrading();
        next = loadingNext();
        lastClosed = loadingLastClosed();
        liveDetail = null;
        closedDetail = null;
        liveCount = 0;
        gradingCount = 0;
        onChange.run();

        dispatcher.send(Verb.RELEASE_LIST_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleReleases(response, failure)));
        dispatcher.send(Verb.GRADING_QUEUE_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleGrading(response, failure)));
        dispatcher.send(Verb.RESULTS_EXAMS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settleResults(response, failure)));
    }

    // ===================== Releases: the live and next cards =============

    private void settleReleases(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof ReleaseList list)) {
            live = DashboardCard.failed(DashboardCopy.LIVE_KICKER, DashboardCopy.LIVE_TITLE,
                    DashboardCopy.LIVE_LINK, Routes.RELEASES.id());
            next = DashboardCard.failed(DashboardCopy.NEXT_RELEASE_KICKER,
                    DashboardCopy.NEXT_RELEASE_TITLE, DashboardCopy.NEXT_RELEASE_LINK,
                    Routes.RELEASES.id());
            onChange.run();
            return;
        }
        List<ReleaseRow> liveRows = rowsIn(list, ReleaseState.LIVE);
        List<ReleaseRow> scheduled = rowsIn(list, ReleaseState.SCHEDULED);
        liveCount = liveRows.size();

        live = DashboardCard.counted(DashboardCopy.LIVE_KICKER, DashboardCopy.LIVE_TITLE,
                liveRows.size(), DashboardCopy.LIVE_HINT, DashboardCopy.LIVE_EMPTY,
                DashboardCopy.LIVE_LINK, Routes.RELEASES.id());
        if (!liveRows.isEmpty()) {
            live = live.withChip(ChipSpec.of(DashboardCopy.CHIP_LIVE, ChipTone.LIVE).withDot());
        }

        next = DashboardCard.counted(DashboardCopy.NEXT_RELEASE_KICKER,
                DashboardCopy.NEXT_RELEASE_TITLE, scheduled.size(),
                soonest(scheduled).map(ReleaseRow::examName).orElse(DashboardCopy.NEXT_RELEASE_HINT),
                DashboardCopy.NEXT_RELEASE_EMPTY, DashboardCopy.NEXT_RELEASE_LINK,
                Routes.RELEASES.id());
        onChange.run();

        // Only now, and only when there is one: the monitor read exists to fill a
        // card that is not being drawn otherwise.
        soonest(liveRows).ifPresent(this::loadMonitor);
    }

    private void loadMonitor(ReleaseRow row) {
        dispatcher.send(Verb.EXECUTION_MONITOR_GET, new MonitorRequest(row.executionId()))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleMonitor(response, failure)));
    }

    private void settleMonitor(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof ExecutionMonitor monitor)) {
            // The count card is already correct and already on screen. A failed
            // detail read leaves it as the plain count rather than replacing a
            // true number with an error.
            return;
        }
        liveDetail = detailOf(monitor);
        onChange.run();
    }

    /**
     * Turns a monitor answer into the live card's value.
     *
     * <p>Package-private and static so the mapping — which students are shown,
     * in what order, and what "N of M" counts — is tested without a dispatcher.
     *
     * <p><b>The rows shown are the ones still sitting</b>, newest start first,
     * topped up with finished ones if there is room. A card with three slots
     * spent on students who handed in twenty minutes ago has told the teacher
     * nothing she can act on.
     */
    static LiveDetail detailOf(ExecutionMonitor monitor) {
        List<MonitorRow> ordered = new ArrayList<>(monitor.rows());
        ordered.sort(Comparator
                .comparingInt((MonitorRow row) -> row.state().isLive() ? 0 : 1)
                .thenComparing(MonitorRow::studentName, Comparator.nullsLast(String::compareTo)));

        List<StudentLine> lines = new ArrayList<>();
        for (MonitorRow row : ordered) {
            if (lines.size() >= LIVE_STUDENT_ROWS) {
                break;
            }
            lines.add(new StudentLine(row.studentName(),
                    ChipCatalog.forAttemptStatus(row.state().name())));
        }
        int sitting = monitor.rows().size();
        return new LiveDetail(monitor.examName(), monitor.code(), monitor.closesAt(),
                monitor.serverNow(), (int) monitor.counts().finished(), sitting, lines,
                sitting > lines.size());
    }

    // ===================== Grading =======================================

    private void settleGrading(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof GradingQueue queue)) {
            grading = DashboardCard.failed(DashboardCopy.GRADING_KICKER,
                    DashboardCopy.GRADING_TITLE, DashboardCopy.GRADING_LINK, Routes.GRADING.id());
            onChange.run();
            return;
        }
        gradingCount = queue.executions().size();
        grading = DashboardCard.counted(DashboardCopy.GRADING_KICKER, DashboardCopy.GRADING_TITLE,
                gradingCount, DashboardCopy.GRADING_HINT, DashboardCopy.GRADING_EMPTY,
                DashboardCopy.GRADING_LINK, Routes.GRADING.id());
        grading = grading.withChip(gradingCount > 0
                ? ChipSpec.of(DashboardCopy.CHIP_TO_DO, ChipTone.WARN)
                : ChipSpec.of(DashboardCopy.CHIP_DONE, ChipTone.OK));
        onChange.run();
    }

    // ===================== Results: the last-closed card =================

    private void settleResults(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof TeacherResults answer)) {
            lastClosed = DashboardCard.failed(DashboardCopy.LAST_CLOSED_KICKER,
                    DashboardCopy.LAST_CLOSED_TITLE, DashboardCopy.LAST_CLOSED_LINK,
                    Routes.RESULTS.id());
            onChange.run();
            return;
        }
        Optional<ExecutionResultRow> newest = newestClosed(answer);
        if (newest.isEmpty()) {
            lastClosed = new DashboardCard(DashboardCopy.LAST_CLOSED_KICKER,
                    DashboardCopy.LAST_CLOSED_TITLE, "0", DashboardCopy.LAST_CLOSED_EMPTY,
                    DashboardCopy.LAST_CLOSED_LINK, null, Routes.RESULTS.id(),
                    DashboardCard.State.EMPTY);
            onChange.run();
            return;
        }
        onChange.run();
        dispatcher.send(Verb.RESULTS_EXECUTION_GET,
                        new ExecutionResultsRequest(newest.get().executionId()))
                .whenComplete((reply, error) -> poster.run(() -> settleLastClosed(reply, error)));
    }

    private void settleLastClosed(Message response, Throwable failure) {
        if (!(payloadOf(response, failure) instanceof ExecutionResults results)) {
            lastClosed = DashboardCard.failed(DashboardCopy.LAST_CLOSED_KICKER,
                    DashboardCopy.LAST_CLOSED_TITLE, DashboardCopy.LAST_CLOSED_LINK,
                    Routes.RESULTS.id());
            onChange.run();
            return;
        }
        Optional<ResultStatistics> stats = results.statistics();
        if (stats.isEmpty()) {
            // A closed sitting whose marking is unfinished is a state, not an
            // error: the server says so calmly and so does the card.
            lastClosed = new DashboardCard(DashboardCopy.LAST_CLOSED_KICKER,
                    DashboardCopy.LAST_CLOSED_TITLE, "0", DashboardCopy.LAST_CLOSED_UNMARKED,
                    DashboardCopy.LAST_CLOSED_LINK, null, Routes.RESULTS.id(),
                    DashboardCard.State.EMPTY);
            onChange.run();
            return;
        }
        ResultStatistics frozen = stats.get();
        closedDetail = new ClosedDetail(results.examName(), frozen.mean(), frozen.passCount(),
                frozen.count(), frozen.deciles());
        lastClosed = new DashboardCard(DashboardCopy.LAST_CLOSED_KICKER,
                DashboardCopy.LAST_CLOSED_TITLE, Long.toString(Math.round(frozen.mean())),
                results.examName(), DashboardCopy.LAST_CLOSED_LINK, null, Routes.RESULTS.id(),
                DashboardCard.State.READY);
        onChange.run();
    }

    /**
     * @return the execution that closed most recently across every exam this
     *         teacher wrote, or empty when none has. Cancelled sittings are not
     *         candidates: nobody sat them, so there is no average to show
     */
    static Optional<ExecutionResultRow> newestClosed(TeacherResults results) {
        return results.exams().stream()
                .map(ExamResultRow::executions)
                .flatMap(List::stream)
                .filter(row -> row.state() == ExecutionState.CLOSED)
                .filter(ExecutionResultRow::hasStatistics)
                .max(Comparator.comparing(ExecutionResultRow::closeAt));
    }

    // ===================== Shared ========================================

    private static List<ReleaseRow> rowsIn(ReleaseList list, ReleaseState state) {
        return list.rows().stream().filter(row -> row.state() == state).toList();
    }

    /** @return the row that opens or closes first, which is the one worth naming. */
    private static Optional<ReleaseRow> soonest(List<ReleaseRow> rows) {
        return rows.stream().min(Comparator.comparing(ReleaseRow::openAt));
    }

    private static DashboardCard loadingLive() {
        return DashboardCard.loading(DashboardCopy.LIVE_KICKER, DashboardCopy.LIVE_TITLE,
                DashboardCopy.LIVE_HINT, DashboardCopy.LIVE_LINK, Routes.RELEASES.id());
    }

    private static DashboardCard loadingGrading() {
        return DashboardCard.loading(DashboardCopy.GRADING_KICKER, DashboardCopy.GRADING_TITLE,
                DashboardCopy.GRADING_HINT, DashboardCopy.GRADING_LINK, Routes.GRADING.id());
    }

    private static DashboardCard loadingNext() {
        return DashboardCard.loading(DashboardCopy.NEXT_RELEASE_KICKER,
                DashboardCopy.NEXT_RELEASE_TITLE, DashboardCopy.NEXT_RELEASE_HINT,
                DashboardCopy.NEXT_RELEASE_LINK, Routes.RELEASES.id());
    }

    private static DashboardCard loadingLastClosed() {
        return DashboardCard.loading(DashboardCopy.LAST_CLOSED_KICKER,
                DashboardCopy.LAST_CLOSED_TITLE, DashboardCopy.LAST_CLOSED_HINT,
                DashboardCopy.LAST_CLOSED_LINK, Routes.RESULTS.id());
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
