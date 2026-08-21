package common.dto.results;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One sitting of an exam, as the teacher's execution picker shows it (Common tier, E14.1).
 *
 * <p>Everything needed to choose between two runs of the same exam without a second round
 * trip: which code the students typed, when the window was, what state it is in, how many sat
 * it, how many of those are marked, and whether the frozen statistics exist yet.
 *
 * <p>The same row is echoed back inside {@link ExecutionResults}, so opening an execution
 * never loses the header the teacher just clicked — the {@code common.dto.grading} pattern,
 * kept deliberately.
 *
 * <h2>participants is counted, never accumulated</h2>
 *
 * <p>{@code participants} is a {@code COUNT} over {@code exam_attempts} (ARCHITECTURE §5
 * forbids counter columns), so it is correct for a live execution as well as a closed one.
 * {@code gradedCount} counts the {@code grades} rows behind those attempts, which is what
 * makes "6 of 8 marked" answerable on the picker rather than only after opening.
 *
 * <h2>hasStatistics is the state the screen branches on</h2>
 *
 * <p>True exactly when {@code exam_executions.stats} is populated, which happens once when the
 * last grade of the execution is approved (F8.5). It is carried rather than inferred from
 * {@code gradedCount == participants} because those two can agree while the statistics have
 * not been frozen — grading finished is not the same event as approval completing — and a
 * picker that promised a histogram it cannot draw is worse than one that says "grading is not
 * finished yet".
 *
 * @param executionId               the {@code exam_executions} row
 * @param code4                     the four-character entry code students used (C-1)
 * @param openAt                    when the window opened, UTC (ADR-010)
 * @param closeAt                   when it closed, extensions <b>not</b> included
 * @param state                     SCHEDULED / LIVE / CLOSED; never {@code CANCELLED}
 * @param participants              how many students sat it, counted from the attempts
 * @param gradedCount               how many of those attempts have a grade row
 * @param hasStatistics             whether the frozen statistics exist (F8.5)
 * @param releasedByAnotherTeacher  {@code true} when somebody other than the exam's author
 *                                  released this run (S-35). The author still sees it; the
 *                                  screen labels it so she is not surprised by a sitting she
 *                                  does not remember scheduling
 */
public record ExecutionResultRow(long executionId,
                                 String code4,
                                 Instant openAt,
                                 Instant closeAt,
                                 ExecutionState state,
                                 int participants,
                                 int gradedCount,
                                 boolean hasStatistics,
                                 boolean releasedByAnotherTeacher) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExecutionResultRow {
        Objects.requireNonNull(code4, "code4");
        Objects.requireNonNull(openAt, "openAt");
        Objects.requireNonNull(closeAt, "closeAt");
        Objects.requireNonNull(state, "state");
    }

    /** @return {@code true} when every attempt at this execution carries a grade row. */
    public boolean isFullyMarked() {
        return participants > 0 && gradedCount == participants;
    }

    /** @return {@code true} when nobody sat this run at all. */
    public boolean isEmpty() {
        return participants == 0;
    }
}
