package client.features.data;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.StatChartData;
import common.dto.report.DataResults;
import common.dto.report.ReportRow;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the principal's sitting detail (Presentation tier, E15.2 — F9.3, F8.5, S-7,
 * U-44, the lead's ruling of 2026-08-30).
 *
 * <p><b>No new verb, and none was needed.</b> {@code DATA_RESULTS_GET} already answers with a
 * {@link ReportRow} per closed sitting, and a {@code ReportRow} already carries everything this
 * screen draws: the code, the released version's name, the course, the window, the participant
 * count and the whole of {@code ResultStatistics} — mean, median, population σ, min, max, pass
 * count, pass rate and the ten frozen deciles. So the detail is the row, and the row was on the
 * wire before this screen existed. A {@code DATA_SITTING_GET} beside it would have been a second
 * answer to a question that already has one, which is exactly what REPORTS amendment A1 refused
 * for the Questions tab.
 *
 * <h2>It re-reads rather than being handed the row ⚑</h2>
 *
 * <p>The Data screen has the row in hand when the principal clicks it, and passing it through
 * nav parameters would have saved a request. It would also have made a cold deep link into this
 * route — a bookmark, a back-stack entry restored after a reconnect — render nothing, and made
 * the screen's content depend on which door it was entered by. Re-reading costs one unpaginated
 * school-sized list (PRD section 6) and makes the two doors identical. The same reasoning
 * {@code CheckedFormView} follows with a grade id.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>Every figure was frozen into {@code exam_executions.stats} when the sitting's last grade was
 * approved (F8.5, H14.4 ⚑) and travelled unchanged. {@link #chartData()} maps the stored deciles
 * straight into {@link StatChartData}, exactly as {@code TeacherResultsSession} does, so one
 * sitting's histogram is the same shape on the teacher's screen and on hers.
 */
public final class DataSittingSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private ReportRow sitting;
    private String error;

    /** The sitting on screen, and what every late answer is checked against ⚑. */
    private long requestedExecutionId;

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public DataSittingSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public DataSittingSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading =======================================

    /**
     * Opens one closed sitting.
     *
     * @param executionId the sitting, off a Results row; anything not positive does nothing,
     *                    because a screen opened with no parameter has nothing to ask about
     */
    public void open(long executionId) {
        if (executionId <= 0) {
            return;
        }
        if (state == AsyncViewState.LOADING && executionId == requestedExecutionId) {
            return;
        }
        requestedExecutionId = executionId;
        state = AsyncViewState.LOADING;
        error = null;
        sitting = null;
        onChange.run();

        dispatcher.send(Verb.DATA_RESULTS_GET, null)
                .whenComplete((response, failure) ->
                        poster.run(() -> settle(executionId, response, failure)));
    }

    private void settle(long asked, Message response, Throwable failure) {
        if (asked != requestedExecutionId) {
            // She opened another sitting while this was in flight. Adopting it would print one
            // sitting's figures under another one's heading.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof DataResults payload)) {
            fail();
            return;
        }
        ReportRow found = null;
        for (ReportRow row : payload.sittings()) {
            if (row.executionId() == asked) {
                found = row;
                break;
            }
        }
        if (found == null) {
            // The list answered and this sitting is not in it. That is a real fact rather than a
            // transport failure - a sitting reopened for grading leaves the closed-and-frozen
            // population (H15.2 ⚑) - and the panel says so in its own words.
            fail();
            return;
        }
        sitting = found;
        error = null;
        state = AsyncViewState.READY;
        onChange.run();
    }

    private void fail() {
        sitting = null;
        error = DataDetailCopy.SITTING_FAILED_HINT;
        state = AsyncViewState.ERROR;
        onChange.run();
    }

    // ===================== What the screen reads =========================

    /** @return the current view state. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the sitting, when it has been found. */
    public Optional<ReportRow> sitting() {
        return Optional.ofNullable(sitting);
    }

    /** @return the failure sentence when the sitting could not be opened. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return true while the read is in flight. */
    public boolean isLoading() {
        return state.showsSkeleton();
    }

    /**
     * @return the ten frozen buckets as rows, lowest band first. Empty before the sitting
     *         arrives, which is what leaves the table on its skeleton rather than on a table of
     *         zeroes
     */
    public List<DataDetailCopy.DecileRow> distribution() {
        return sitting == null ? List.of()
                : DataDetailCopy.distribution(sitting.statistics());
    }

    /**
     * @return the histogram's data: the ten stored buckets and the stored mean, median, σ and
     *         count. {@link StatChartData#empty()} before the sitting arrives, never a
     *         zero-filled record — the chart's "no results yet" state is a different picture
     */
    public StatChartData chartData() {
        if (sitting == null) {
            return StatChartData.empty();
        }
        var stats = sitting.statistics();
        return StatChartData.of(stats.deciles(), stats.mean(), stats.median(),
                stats.standardDeviation(), stats.count());
    }
}
