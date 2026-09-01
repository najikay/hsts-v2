package server.features.reports;

import common.dto.report.ReportDimension;
import common.dto.report.ReportResult;
import common.dto.report.ReportRow;
import common.dto.report.ReportSubject;
import common.dto.report.ReportSubjects;
import common.dto.report.ReportSummary;
import common.dto.results.ResultStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.projections.ExecutionReport;
import server.features.results.FrozenStatistics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one report mechanism, parameterised by dimension (Logic tier, E15.3 ⚑ — F9.4, S-37).
 *
 * <p>Everything a report does apart from choosing its rows: resolve the subject, map each
 * sitting's frozen statistics onto the wire, attach the participant counts, and aggregate the
 * whole thing into one honest summary. It does that identically for every dimension, and it is
 * able to because it does not know what the dimensions are.
 *
 * <h2>This class names no dimension, and there is a test that says so</h2>
 *
 * <p>There is no {@code switch} here, no comparison of a dimension against a constant, and no
 * mention of a teacher, a course or a student anywhere in the file. The engine holds a map from
 * {@link ReportDimension} to {@link DimensionStrategy}, built from whatever list it was
 * constructed with, and asks the strategy the three questions in
 * {@link DimensionStrategy}. {@code ReportEngineExtensibilityTest} reads this source and fails if
 * any of the three constant names appears in it, and separately drives a real engine over a
 * fourth strategy that exists only inside the test. Between them, "a new report type is a new
 * strategy class and a menu entry" stops being a claim in a document.
 *
 * <h2>Nothing here computes a statistic</h2>
 *
 * <p>Rows arrive carrying {@code exam_executions.stats} exactly as it was written when the
 * sitting's last grade was approved (F8.5), and they go out through
 * {@link FrozenStatistics#toWire} — the same mapping the teacher's own results screen uses, so a
 * sitting cannot read one way on her histogram and another way in the principal's report.
 * Population σ, pass mark 55, ten stored buckets, none of them re-derived (H14.4 ⚑).
 *
 * <p>{@link ReportSummary#across} then <em>aggregates</em> those stored numbers across rows: a
 * participant-weighted mean, a pooled sum of squares, summed buckets, summed pass counts. That
 * is arithmetic on frozen figures and not a recomputation — the same move
 * {@code FrozenStatistics} makes when it reconstitutes a pass count from a stored rate. The
 * forbidden move is going back to the grades, and nothing on this path can reach one.
 *
 * <h2>A sitting whose stored record is unusable is dropped, loudly</h2>
 *
 * <p>{@link FrozenStatistics} answers empty for a column whose distribution is the wrong width,
 * whose population is zero or whose mean is outside 0..100, and logs an ERROR naming the
 * execution. A report cannot show that row as "grading unfinished" the way E14's detail screen
 * can, because the row's whole purpose is to be compared; so it is left out of the table and out
 * of the summary, and the fault is in the log. Including it with zeros would pull the pooled mean
 * down by a real amount for a reason nobody on the screen could see.
 */
public final class ReportEngine {

    private static final Logger log = LoggerFactory.getLogger(ReportEngine.class);

    private final ReportStore store;
    private final Map<ReportDimension, DimensionStrategy> strategies;

    /**
     * @param store      the transactional data seam
     * @param registered the dimensions this engine serves, normally
     *                   {@link ReportStrategies#all()}
     * @throws IllegalArgumentException when two strategies claim the same dimension. Failing at
     *                                  construction rather than letting one silently win is the
     *                                  point: the loser would answer nothing and there would be
     *                                  no evidence of which one it was
     */
    public ReportEngine(ReportStore store, List<DimensionStrategy> registered) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(registered, "registered");
        Map<ReportDimension, DimensionStrategy> byDimension = new EnumMap<>(ReportDimension.class);
        for (DimensionStrategy strategy : registered) {
            DimensionStrategy clash = byDimension.put(strategy.dimension(), strategy);
            if (clash != null) {
                throw new IllegalArgumentException(
                        "Two strategies claim " + strategy.dimension() + ": "
                                + clash.getClass().getSimpleName() + " and "
                                + strategy.getClass().getSimpleName()
                                + ". Register exactly one per dimension in ReportStrategies.");
            }
        }
        this.strategies = Map.copyOf(byDimension);
    }

    /** @return which dimensions this engine can answer about. */
    public java.util.Set<ReportDimension> served() {
        return strategies.keySet();
    }

    /**
     * The subjects one dimension can be reported about.
     *
     * @param dimension the dimension asked for
     * @return its subjects, or empty when no strategy is registered for it — which on a matched
     *         client cannot happen, and on a client built against a later protocol is exactly
     *         what it looks like
     */
    public Optional<ReportSubjects> subjects(ReportDimension dimension) {
        DimensionStrategy strategy = strategies.get(dimension);
        if (strategy == null) {
            log.warn("No report strategy is registered for dimension {}. Register one in "
                    + "ReportStrategies, or check the client and server are the same build.",
                    dimension);
            return Optional.empty();
        }
        return Optional.of(store.inTx(data ->
                new ReportSubjects(dimension, strategy.subjects(data))));
    }

    /**
     * One report.
     *
     * <p>Assembled in a single transaction: the subject's label, its rows and their participant
     * counts all come from one consistent moment, so a sitting cannot appear in the table with a
     * participant count read after another student joined.
     *
     * @param dimension the dimension to compare across
     * @param subjectId the subject, as its picker issued the id
     * @return the report, or empty when the dimension is unserved or the id names no subject.
     *         A subject that exists with nothing to compare is <b>not</b> empty here: it is a
     *         present result with no rows, which is a real answer rather than a refusal
     */
    public Optional<ReportResult> report(ReportDimension dimension, String subjectId) {
        DimensionStrategy strategy = strategies.get(dimension);
        if (strategy == null) {
            log.warn("No report strategy is registered for dimension {}. Register one in "
                    + "ReportStrategies, or check the client and server are the same build.",
                    dimension);
            return Optional.empty();
        }
        return store.inTx(data -> {
            Optional<ReportSubject> found = strategy.subject(data, subjectId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            List<ExecutionReport> executions = strategy.executionsOf(data, subjectId);
            List<ReportRow> rows = toRows(data, executions, strategy.subjectScores(data, subjectId));
            return Optional.of(new ReportResult(dimension, found.get(), rows,
                    ReportSummary.across(rows)));
        });
    }

    // ===================== Mapping =======================================

    /**
     * Turns selected sittings into wire rows, in one pass and with one extra read.
     *
     * <p>The participant counts are fetched for the whole list at once rather than per row: a
     * course with thirty sittings costs two queries rather than thirty-one, and every count is
     * from the same moment as every other.
     *
     * <p>Package-private rather than private since E15.2, because the data browser lists the same
     * sittings and has to map them the same way. One mapping with two callers, on the same
     * reasoning that widened {@code FrozenStatistics} rather than letting E15 copy it: a sitting
     * that read one way on the browse and another way in a report would be two answers to one
     * question, and the unusable-column rule and the participant count are decisions rather than
     * plumbing.
     *
     * @param data       this transaction's reads
     * @param executions the selected sittings, in the order they should be rendered
     * @return the rows, in the order they were selected, minus any whose stored statistics are
     *         unusable
     */
    static List<ReportRow> toRows(ReportData data, List<ExecutionReport> executions) {
        return toRows(data, executions, Map.of());
    }

    /**
     * The A2 overload: rows carrying the subject's own score where the dimension has one.
     * The browse caller stays on the two-argument form and its rows stay score-free.
     */
    static List<ReportRow> toRows(ReportData data, List<ExecutionReport> executions,
                                  Map<Long, Integer> subjectScores) {
        if (executions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(executions.size());
        for (ExecutionReport execution : executions) {
            ids.add(execution.executionId());
        }
        Map<Long, Integer> participants = data.participantsByExecution(ids);

        List<ReportRow> rows = new ArrayList<>(executions.size());
        for (ExecutionReport execution : executions) {
            long id = execution.executionId();
            Optional<ResultStatistics> statistics =
                    FrozenStatistics.toWire(id, execution.stats());
            if (statistics.isEmpty()) {
                // FrozenStatistics has already logged what is wrong with the column, naming
                // this execution. A second line here would say less and repeat more.
                continue;
            }
            rows.add(new ReportRow(id, execution.code(), execution.examName(),
                    execution.courseCode(), execution.courseName(), execution.openAt(),
                    execution.closeAt(), participants.getOrDefault(id, 0), statistics.get(),
                    subjectScores.get(id)));
        }
        return List.copyOf(rows);
    }
}
