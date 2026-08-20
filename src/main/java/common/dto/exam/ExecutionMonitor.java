package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * A live execution, as the teacher watching it sees it (Common tier, E11.2 — F7.2).
 *
 * <p>The answer to {@code EXECUTION_MONITOR_GET} and the payload of every
 * {@code PUSH_MONITOR_UPDATED} that follows. It is a whole snapshot rather than a delta,
 * on purpose: a screen that rebuilds from a complete picture cannot drift out of step with
 * the server, and the alternative — patching rows from events — is precisely how a monitor
 * ends up showing a student who submitted ten minutes ago as still working.
 *
 * <p>{@link #serverNow} is carried so the client can age {@link MonitorRow#remainingMillis}
 * between pushes without inventing its own clock, exactly as the student's countdown does.
 *
 * @param executionId    the execution
 * @param examName       what is being sat
 * @param courseCode     the 2-character course code
 * @param code           the 4-character entry code, which the teacher may need to read out
 *                       again (S-17); teacher-facing only, never on a student's wire
 * @param live           whether it is still running
 * @param serverNow      the server's own instant when this snapshot was built
 * @param closesAt       when the window closes, extensions included
 * @param extraMinutes   total minutes granted so far by extensions (S-20/S-21)
 * @param durationMinutes the allotted duration per student, extensions included
 * @param counts         the three derived participation numbers (S-21)
 * @param rows           one row per student who has started, by name
 */
public record ExecutionMonitor(long executionId,
                               String examName,
                               String courseCode,
                               String code,
                               boolean live,
                               Instant serverNow,
                               Instant closesAt,
                               int extraMinutes,
                               int durationMinutes,
                               MonitorCounts counts,
                               List<MonitorRow> rows) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ExecutionMonitor {
        examName = examName == null ? "" : examName;
        courseCode = courseCode == null ? "" : courseCode;
        code = code == null ? "" : code;
        counts = counts == null ? MonitorCounts.NONE : counts;
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** @return {@code true} when nobody has started yet, so the table shows its empty state. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** @return how many students tripped the C-4 integrity net (F6.8). */
    public long flaggedCount() {
        return rows.stream().filter(MonitorRow::isFlagged).count();
    }
}
