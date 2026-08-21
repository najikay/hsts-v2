package client.features.results;

import client.events.FxThreadPoster;
import client.net.RequestDispatcher;
import client.ui.components.logic.AsyncViewState;
import client.ui.components.logic.StatChartData;
import common.dto.grading.StudentGradeRow;
import common.dto.results.ExamResultRow;
import common.dto.results.ExecutionResultRow;
import common.dto.results.ExecutionResults;
import common.dto.results.ExecutionResultsRequest;
import common.dto.results.ResultStatistics;
import common.dto.results.TeacherResults;
import common.protocol.Message;
import common.protocol.Verb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The logic behind the teacher's <b>Results</b> screen (Presentation tier, E14.2/E14.3b).
 *
 * <p>Everything the screen decides lives here and nothing else does: which exam is selected,
 * which sitting inside it, what the table shows, which view the toggle is on, and what the
 * histogram is fed. The FX view beside it reads {@link #state()}, {@link #exams()},
 * {@link #results()} and {@link #chartData()} and renders. That split is what makes the
 * screen's behaviour testable against {@code FakeClientConnection} with no JavaFX toolkit
 * (TEAM_SPLIT §3.2), and it is why every transition below has a test rather than being
 * discovered by clicking.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>{@link #chartData()} maps the server's frozen figures straight into
 * {@link StatChartData}: the ten stored buckets, the stored mean, the stored median, the
 * stored <b>population</b> σ and the stored count. It re-buckets nothing and re-derives
 * nothing, because F8.5 froze those numbers when the last grade was approved and a second
 * computation here would disagree with the stat cards printed directly above the chart
 * (H14.4 ⚑).
 *
 * <p>An execution with no statistics maps to {@link StatChartData#empty()} rather than to a
 * record of zeros, which is the difference between the chart saying "no results yet" and the
 * chart saying "not enough results to chart" about a class that does not exist.
 *
 * <h2>Which sitting opens first</h2>
 *
 * <p>A teacher opening Results wants results, so the default selection is the most recent
 * sitting whose statistics are ready, falling back to the most recent sitting of the first
 * exam that has any. Opening on a live execution with an empty table — which "newest first"
 * alone would do — would make the screen look broken on the one path everybody takes.
 */
public final class TeacherResultsSession {

    /** Which of the two views the T-10 toggle is showing (E14.4). */
    public enum ResultsView {

        /** The sortable per-student table. */
        TABLE,

        /** The score histogram (E14.3). */
        HISTOGRAM
    }

    private final RequestDispatcher dispatcher;
    private final FxThreadPoster poster;

    private Runnable onChange = () -> { };

    private AsyncViewState state = AsyncViewState.IDLE;
    private List<ExamResultRow> exams = List.of();
    private String error;

    private ExamResultRow selectedExam;
    private ExecutionResultRow selectedExecution;

    private AsyncViewState detailState = AsyncViewState.IDLE;
    private ExecutionResults results;
    private String detailError;

    private ResultsView view = ResultsView.TABLE;
    private boolean printLayout;

    /**
     * @param dispatcher the request correlator; the screen never touches a socket
     * @param poster     the single FX-thread hop; {@code DirectFxThreadPoster} in tests
     */
    public TeacherResultsSession(RequestDispatcher dispatcher, FxThreadPoster poster) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** Registers the "re-read me and re-render" callback. */
    public TeacherResultsSession onChange(Runnable listener) {
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ===================== Loading the list ==============================

    /**
     * Requests every exam this teacher wrote (S-35).
     *
     * <p>Calling this while a load is in flight is ignored rather than queued: two identical
     * requests could settle out of order and leave the screen showing the older answer.
     */
    public void load() {
        if (state == AsyncViewState.LOADING) {
            return;
        }
        state = AsyncViewState.LOADING;
        error = null;
        onChange.run();

        dispatcher.send(Verb.RESULTS_EXAMS_GET, null)
                .whenComplete((response, failure) -> poster.run(() -> settleList(response, failure)));
    }

    private void settleList(Message response, Throwable failure) {
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof TeacherResults payload)) {
            // A well-formed OK carrying the wrong type is a protocol bug, not a user error;
            // the teacher still gets a sentence rather than a stack trace.
            exams = List.of();
            selectedExam = null;
            selectedExecution = null;
            results = null;
            detailState = AsyncViewState.IDLE;
            error = ResultsCopy.LOAD_FAILED;
            state = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        exams = payload.exams();
        error = null;
        state = AsyncViewState.forResult(exams);
        ExamResultRow opening = defaultExam(exams);
        if (opening == null) {
            selectedExam = null;
            selectedExecution = null;
            results = null;
            detailState = AsyncViewState.IDLE;
            onChange.run();
            return;
        }
        selectExam(opening);
    }

    // ===================== Selection =====================================

    /**
     * Selects an exam and opens its default sitting.
     *
     * @param exam one of the loaded exams; ignored when {@code null}
     */
    public void selectExam(ExamResultRow exam) {
        if (exam == null) {
            return;
        }
        selectedExam = exam;
        results = null;
        detailError = null;
        ExecutionResultRow opening = defaultExecution(exam);
        if (opening == null) {
            selectedExecution = null;
            detailState = AsyncViewState.EMPTY;
            onChange.run();
            return;
        }
        openExecution(opening);
    }

    /**
     * Opens one sitting's results.
     *
     * @param execution one of the selected exam's sittings; ignored when {@code null}
     */
    public void openExecution(ExecutionResultRow execution) {
        if (execution == null) {
            return;
        }
        selectedExecution = execution;
        detailState = AsyncViewState.LOADING;
        detailError = null;
        results = null;
        onChange.run();

        long asked = execution.executionId();
        dispatcher.send(Verb.RESULTS_EXECUTION_GET, new ExecutionResultsRequest(asked))
                .whenComplete((response, failure) ->
                        poster.run(() -> settleDetail(asked, response, failure)));
    }

    private void settleDetail(long asked, Message response, Throwable failure) {
        if (selectedExecution == null || selectedExecution.executionId() != asked) {
            // The teacher moved on while this was in flight. Adopting it would repaint the
            // screen with the previous sitting's rows under the current sitting's header.
            return;
        }
        if (failure != null || response == null || response.isError()
                || !(response.getPayload() instanceof ExecutionResults payload)) {
            results = null;
            detailError = ResultsCopy.EXECUTION_FAILED;
            detailState = AsyncViewState.ERROR;
            onChange.run();
            return;
        }
        results = payload;
        detailError = null;
        detailState = AsyncViewState.forResultSize(payload.rows().size());
        onChange.run();
    }

    // ===================== The toggle (T-10, E14.4) =======================

    /**
     * Switches between the table and the histogram.
     *
     * @param next which view to show
     */
    public void setView(ResultsView next) {
        Objects.requireNonNull(next, "view");
        if (view == next) {
            return;
        }
        view = next;
        onChange.run();
    }

    /** @return which view the toggle is on. */
    public ResultsView view() {
        return view;
    }

    /**
     * Turns the print-friendly layout on or off (E14.4).
     *
     * <p>A layout mode rather than a printer driver: the screen drops to one column, flattens
     * its cards and hides the navigation, so what a teacher captures or screenshots is the
     * numbers rather than the application around them.
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

    /** @return the list's view state: skeleton, content, empty or error. */
    public AsyncViewState state() {
        return state;
    }

    /** @return the loaded exams; empty unless {@link #state()} is {@code READY}. */
    public List<ExamResultRow> exams() {
        return exams;
    }

    /** @return the error sentence when the list failed to load. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return the selected exam, or empty before anything is chosen. */
    public Optional<ExamResultRow> selectedExam() {
        return Optional.ofNullable(selectedExam);
    }

    /** @return the selected sitting, or empty when the exam has none. */
    public Optional<ExecutionResultRow> selectedExecution() {
        return Optional.ofNullable(selectedExecution);
    }

    /** @return the sittings of the selected exam, newest first; empty when none is selected. */
    public List<ExecutionResultRow> executions() {
        return selectedExam == null ? List.of() : selectedExam.executions();
    }

    /** @return the detail panel's view state. */
    public AsyncViewState detailState() {
        return detailState;
    }

    /** @return the open sitting's results, or empty while none is loaded. */
    public Optional<ExecutionResults> results() {
        return Optional.ofNullable(results);
    }

    /** @return the error sentence when the sitting failed to open. */
    public Optional<String> detailError() {
        return Optional.ofNullable(detailError);
    }

    /** @return the rows of the open sitting; empty when there are none. */
    public List<StudentGradeRow> rows() {
        return results == null ? List.of() : results.rows();
    }

    /** @return the open sitting's frozen statistics, or empty while grading is unfinished. */
    public Optional<ResultStatistics> statistics() {
        return results == null ? Optional.empty() : results.statistics();
    }

    /**
     * @return {@code true} when the open sitting has rows but no statistics — the calm state
     *         the screen explains with {@link ResultsCopy#GRADING_UNFINISHED_HINT}
     */
    public boolean isGradingUnfinished() {
        return results != null && !results.hasStatistics();
    }

    /**
     * The histogram's input (E14.3b).
     *
     * <p>The whole mapping, and deliberately four lines long: the ten stored buckets, the
     * stored mean, the stored median, the stored population σ and the stored count, straight
     * across. Nothing is derived. An execution with no statistics is
     * {@link StatChartData#empty()}, never a zero-filled record — the chart's "no results yet"
     * and its "not enough results to chart" are different sentences about different situations.
     *
     * @return what to hand {@code StatChart.setData}
     */
    public StatChartData chartData() {
        return statistics()
                .map(stats -> StatChartData.of(stats.deciles(), stats.mean(), stats.median(),
                        stats.standardDeviation(), stats.count()))
                .orElseGet(StatChartData::empty);
    }

    /** @return true while the exams list is in flight, for the skeleton. */
    public boolean isLoading() {
        return state == AsyncViewState.LOADING;
    }

    /**
     * Which explanation belongs where the table would be, when there is no table.
     *
     * <p>Four different facts, and the screen has to say which one it is: she has written no
     * exams, this exam has never been released, this sitting had no takers, or this sitting has
     * had nothing marked yet. One generic "nothing here" for all four is the dead end §4.1
     * forbids, and picking between them is a decision, so it lives here rather than in the view.
     *
     * @return the panel to show; meaningful only when there are no rows to draw
     */
    public ResultsCopy.EmptyPanel emptyPanel() {
        if (exams.isEmpty()) {
            return ResultsCopy.NO_EXAMS;
        }
        if (selectedExecution == null) {
            return ResultsCopy.NEVER_RELEASED;
        }
        if (selectedExecution.isEmpty()) {
            return ResultsCopy.NOBODY_SAT;
        }
        return ResultsCopy.NOTHING_MARKED;
    }

    /**
     * @return {@code true} while a sitting's results are on their way. False when no sitting is
     *         selected at all, which is the difference between "loading" and "nothing to load"
     *         — a screen that cannot tell them apart shows a skeleton that never resolves
     */
    public boolean isOpeningExecution() {
        return selectedExecution != null && detailState.showsSkeleton();
    }

    // ===================== Defaults ======================================

    /**
     * @param candidates the loaded exams
     * @return the exam to open on: the first with a sitting whose statistics are ready, else
     *         the first with any sitting at all, else the first exam, else {@code null}
     */
    static ExamResultRow defaultExam(List<ExamResultRow> candidates) {
        ExamResultRow withExecutions = null;
        for (ExamResultRow exam : candidates) {
            for (ExecutionResultRow execution : exam.executions()) {
                if (execution.hasStatistics()) {
                    return exam;
                }
            }
            if (withExecutions == null && !exam.neverReleased()) {
                withExecutions = exam;
            }
        }
        if (withExecutions != null) {
            return withExecutions;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * @param exam the selected exam
     * @return the sitting to open: the newest whose statistics are ready, else the newest,
     *         else {@code null} when the exam has never been released
     */
    static ExecutionResultRow defaultExecution(ExamResultRow exam) {
        for (ExecutionResultRow execution : exam.executions()) {
            if (execution.hasStatistics()) {
                return execution;
            }
        }
        return exam.executions().isEmpty() ? null : exam.executions().get(0);
    }
}
