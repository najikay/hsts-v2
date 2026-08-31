package client.features.reports;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.StatChartData;
import common.dto.report.ReportDimension;
import common.dto.report.ReportRequest;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSubjectsRequest;
import common.dto.report.ReportSummary;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the principal's <b>Reports</b> screen (Presentation tier, E15.4 — F9.4).
 *
 * <p>Everything the screen decides lives here and nothing else does: which dimension is
 * selected, which subject inside it, which row the chart is drawing, what the summary cards say
 * and which explanation belongs where the table would be. The FX view beside it reads
 * {@link #dimension()}, {@link #subjects()}, {@link #report()} and {@link #chartData()} and
 * renders. That split is what makes the screen's behaviour testable against
 * {@code FakeClientConnection} with no JavaFX toolkit (TEAM_SPLIT section 3.2), and it is why
 * every transition below has a test rather than being discovered by clicking.
 *
 * <h2>The screen has no idea what the dimensions are</h2>
 *
 * <p>There is no {@code if (dimension == BY_COURSE)} here or in the view. The three segments are
 * {@code ReportDimension.values()}, the subject picker's prompt is
 * {@link ReportDimension#subjectNoun()}, and the table's columns are the same for all of them
 * because a report result is the same record whichever dimension produced it. A fourth
 * comparison adds a constant to the enum and a strategy on the server, and this class does not
 * change — which is the client half of S-37 and the reason the screen was built this way rather
 * than as three tabs.
 *
 * <h2>Two requests, and neither of them can settle out of order</h2>
 *
 * <p>Switching a segment loads the new dimension's subjects; picking a subject runs its report.
 * Both answers are checked against what is currently selected before they are adopted
 * ({@code ReportSubjects} carries its dimension, {@code ReportResult} carries its dimension and
 * subject), so a slow answer that arrives after the principal has moved on is discarded rather
 * than painted under the wrong heading.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>{@link #chartData()} maps the selected row's frozen figures straight into
 * {@link StatChartData}, exactly as the teacher's results screen does: the ten stored buckets,
 * the stored mean, the stored median, the stored <b>population</b> σ and the stored count. The
 * summary is the server's, aggregated there and formatted here. Nothing on this screen re-derives
 * a σ, re-applies the pass mark or re-buckets a distribution (F8.5, H14.4 ⚑).
 */
public final class ReportsSession {

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private ReportDimension dimension = ReportDimension.defaultDimension();

    private AsyncViewState subjectsState = AsyncViewState.IDLE;
    private List<ReportSubject> subjects = List.of();
    private String subjectsError;

    private ReportSubject selectedSubject;

    private AsyncViewState reportState = AsyncViewState.IDLE;
    private ReportResult report;
    private String reportError;

    private ReportRow selectedRow;
    private boolean printLayout;

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public ReportsSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public ReportsSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading the subjects ==========================

    /** Loads the current dimension's subjects. What {@code onShow} calls. */
    public void load() {
        loadSubjects(dimension);
    }

    /**
     * Switches the dimension and loads its subjects.
     *
     * <p>The selected subject and the whole report are dropped immediately rather than left on
     * screen until the new list arrives. A teacher's name under a heading that already says "by
     * course" is a screen in two states at once, and it is on screen for exactly as long as the
     * round trip takes, which is long enough for somebody to read it.
     *
     * @param next which dimension to compare across; ignored when it is already selected
     */
    public void selectDimension(ReportDimension next) {
        Objects.requireNonNull(next, "dimension");
        if (dimension == next) {
            return;
        }
        dimension = next;
        loadSubjects(next);
    }

    private void loadSubjects(ReportDimension asked) {
        subjectsState = AsyncViewState.LOADING;
        subjectsError = null;
        subjects = List.of();
        selectedSubject = null;
        report = null;
        reportError = null;
        reportState = AsyncViewState.IDLE;
        selectedRow = null;
        onChange.run();

        dispatcher.send(Verb.REPORT_SUBJECTS_GET, new ReportSubjectsRequest(asked))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleSubjects(asked, response, failure)));
    }

    private void settleSubjects(ReportDimension asked, Message response, Throwable failure) {
        if (dimension != asked) {
            // She switched segments while this was in flight. Adopting it would fill the picker
            // with one dimension's subjects under the other one's heading.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ReportSubjects payload)
                || payload.dimension() != asked) {
            // A well-formed OK carrying the wrong type or the wrong dimension is a protocol bug,
            // not a user error; she still gets a sentence rather than a stack trace.
            subjects = List.of();
            subjectsError = ReportsCopy.SUBJECTS_FAILED;
            subjectsState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        subjects = payload.subjects();
        subjectsError = null;
        subjectsState = AsyncViewState.forResult(subjects);
        ReportSubject opening = payload.defaultSubject();
        if (opening == null) {
            onChange.run();
            return;
        }
        selectSubject(opening);
    }

    // ===================== Running a report ==============================

    /**
     * Runs the report for one subject.
     *
     * @param subject one of the loaded subjects; ignored when {@code null}
     */
    public void selectSubject(ReportSubject subject) {
        if (subject == null) {
            return;
        }
        // U-61: the same subject again is a no-op unless the last attempt failed. Without
        // this, every render's re-select round-tripped the same report and the screen
        // could never settle.
        if (subject.equals(selectedSubject) && reportState != AsyncViewState.ERROR) {
            return;
        }
        selectedSubject = subject;
        reportState = AsyncViewState.LOADING;
        reportError = null;
        report = null;
        selectedRow = null;
        onChange.run();

        ReportDimension asked = dimension;
        String askedId = subject.id();
        dispatcher.send(Verb.REPORT_GET, new ReportRequest(asked, askedId))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleReport(asked, askedId, response, failure)));
    }

    private void settleReport(ReportDimension asked, String askedId, Message response,
                              Throwable failure) {
        if (dimension != asked || selectedSubject == null
                || !selectedSubject.id().equals(askedId)) {
            // She moved on. Adopting this would repaint the table with the previous subject's
            // rows under the current subject's heading.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ReportResult payload)
                || payload.dimension() != asked
                || !payload.subject().id().equals(askedId)) {
            report = null;
            selectedRow = null;
            reportError = ReportsCopy.REPORT_FAILED;
            reportState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        report = payload;
        reportError = null;
        reportState = AsyncViewState.forResult(payload.rows());
        // The chart opens on the most recent sitting: rows are oldest first because a trend
        // reads left to right, and the newest is the one a principal is looking for.
        selectedRow = payload.rows().isEmpty()
                ? null : payload.rows().get(payload.rows().size() - 1);
        onChange.run();
    }

    /**
     * Points the chart at one of the table's rows (E15.4).
     *
     * <p>A row that is not in the current report is ignored rather than adopted, so a stale
     * selection surviving a re-render cannot leave the chart drawing a sitting the table no
     * longer lists.
     *
     * @param row one of {@link #rows()}; ignored when {@code null} or not one of them
     */
    public void selectRow(ReportRow row) {
        if (row == null || !rows().contains(row)) {
            return;
        }
        if (selectedRow != null && selectedRow.executionId() == row.executionId()) {
            return;
        }
        selectedRow = row;
        onChange.run();
    }

    // ===================== Print layout (E15.4) ==========================

    /**
     * Turns the print-friendly layout on or off.
     *
     * <p>The same modest thing E14.4 built and for the same reason: a layout mode rather than a
     * printer driver. The screen flattens its cards and hides the chrome, so what a principal
     * captures is the comparison rather than the application around it.
     *
     * @param on whether to lay the screen out for print
     */
    public void setPrintLayout(boolean on) {
        if (printLayout == on) {
            return;
        }
        printLayout = on;
        onChange.run();
    }

    /** @return whether the print-friendly layout is on. */
    public boolean isPrintLayout() {
        return printLayout;
    }

    // ===================== What the screen reads =========================

    /** @return the selected dimension; never null. */
    public ReportDimension dimension() {
        return dimension;
    }

    /** @return the picker's view state: skeleton, content, empty or error. */
    public AsyncViewState subjectsState() {
        return subjectsState;
    }

    /** @return the loaded subjects; empty unless {@link #subjectsState()} is {@code READY}. */
    public List<ReportSubject> subjects() {
        return subjects;
    }

    /** @return the error sentence when the subject list failed to load. */
    public Optional<String> subjectsError() {
        return Optional.ofNullable(subjectsError);
    }

    /** @return the selected subject, or empty before anything is chosen. */
    public Optional<ReportSubject> selectedSubject() {
        return Optional.ofNullable(selectedSubject);
    }

    /** @return the report panel's view state. */
    public AsyncViewState reportState() {
        return reportState;
    }

    /** @return the loaded report, or empty while none is loaded. */
    public Optional<ReportResult> report() {
        return Optional.ofNullable(report);
    }

    /** @return the error sentence when the report failed to run. */
    public Optional<String> reportError() {
        return Optional.ofNullable(reportError);
    }

    /** @return the report's rows, oldest first; empty when there are none. */
    public List<ReportRow> rows() {
        return report == null ? List.of() : report.rows();
    }

    /** @return the cross-row summary; {@link ReportSummary#EMPTY} when no report is loaded. */
    public ReportSummary summary() {
        return report == null ? ReportSummary.EMPTY : report.summary();
    }

    /** @return the row the chart is drawing, or empty when the report has no rows. */
    public Optional<ReportRow> selectedRow() {
        return Optional.ofNullable(selectedRow);
    }

    /** @return the heading over the table, or an empty string before a subject is chosen. */
    public String heading() {
        return selectedSubject == null ? "" : ReportsCopy.heading(dimension, selectedSubject);
    }

    /** @return true while either request is in flight, for the skeleton. */
    public boolean isLoading() {
        return subjectsState == AsyncViewState.LOADING || reportState == AsyncViewState.LOADING;
    }

    /**
     * The chart's input (E15.4, reusing E14.3's component).
     *
     * <p>The whole mapping, and deliberately four lines long: the selected row's ten stored
     * buckets, its stored mean, median, population σ and count, straight across. Nothing is
     * derived. No selected row is {@link StatChartData#empty()}, never a zero-filled record —
     * the chart's "no results yet" and its "not enough results to chart" are different sentences
     * about different situations.
     *
     * @return what to hand {@code StatChart.setData}
     */
    public StatChartData chartData() {
        return selectedRow()
                .map(row -> StatChartData.of(row.statistics().deciles(), row.statistics().mean(),
                        row.statistics().median(), row.statistics().standardDeviation(),
                        row.statistics().count()))
                .orElseGet(StatChartData::empty);
    }

    /** @return the chart's heading: the selected sitting, or an empty string when none is. */
    public String chartHeading() {
        return selectedRow().map(ReportsCopy::rowLabel).orElse("");
    }

    /**
     * Which explanation belongs where the table would be, when there is no table.
     *
     * <p>Three different facts, and the screen has to say which one it is: nothing has been
     * picked yet, this dimension has no subjects at all, or this subject has had no sitting
     * close. One generic "nothing here" for all three is the dead end section 4.1 forbids, and
     * picking between them is a decision, so it lives here rather than in the view.
     *
     * @return the panel to show; meaningful only when there are no rows to draw
     */
    public ReportsCopy.EmptyPanel emptyPanel() {
        if (subjects.isEmpty()) {
            return subjectsState == AsyncViewState.IDLE || subjectsState == AsyncViewState.LOADING
                    ? ReportsCopy.NOTHING_PICKED
                    : ReportsCopy.NO_SUBJECTS;
        }
        if (selectedSubject == null) {
            return ReportsCopy.NOTHING_PICKED;
        }
        return ReportsCopy.NO_EXECUTIONS;
    }
}
