package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;

/**
 * One student in the live monitor (Common tier, E11.2 — F7.2).
 *
 * <p>Everything a teacher watching a running exam needs about one person: who, where they
 * are, how long they have left, how far through they are, and whether the C-4 integrity
 * net fired for them.
 *
 * <p>{@link #remainingMillis} is computed server-side against the same clock and the same
 * derived deadline the student's own countdown is anchored to, so the teacher's screen and
 * the student's screen cannot disagree. A row for a finished attempt carries zero rather
 * than a stale figure.
 *
 * <p>No answers here, and no scores: the monitor is about progress, and a teacher watching
 * a live exam has no business seeing what anyone picked.
 *
 * @param studentId       the student's internal id
 * @param studentName     her display name
 * @param state           where her attempt stands
 * @param startedAt       when she began
 * @param endedAt         when she finished, or {@code null} while she is working
 * @param remainingMillis time left for her right now; 0 once finished
 * @param answeredCount   how many questions carry a choice
 * @param questionCount   the paper's length
 * @param actualMinutes   recorded solving time once finished, else {@code null} (S-19)
 * @param integrity       the C-4 flag, or {@code null} when nothing fired
 * @param attention       the E11.7 attention summary, or {@code null} when her window never
 *                        lost focus. Additive to the frozen contract, and nullable precisely
 *                        so that "no reports" and "an old client that sends none" render the
 *                        same: as nothing at all, rather than as a reassuring zero
 */
public record MonitorRow(long studentId,
                         String studentName,
                         AttemptState state,
                         Instant startedAt,
                         Instant endedAt,
                         long remainingMillis,
                         int answeredCount,
                         int questionCount,
                         Integer actualMinutes,
                         IntegrityFlag integrity,
                         AttentionSummary attention) implements Serializable {

    private static final long serialVersionUID = 1L;

    public MonitorRow {
        studentName = studentName == null || studentName.isBlank() ? "Unknown student" : studentName;
        state = state == null ? AttemptState.NOT_STARTED : state;
        remainingMillis = Math.max(0, remainingMillis);
    }

    /**
     * The pre-E11.7 ten-argument shape, for a caller that has no attention data.
     *
     * <p>Kept because the attention field is an <b>additive</b> amendment to a frozen
     * contract: every existing construction site means "nothing to report here", and forcing
     * each of them to write a literal {@code null} would make the amendment look like a
     * breaking change in the diff.
     */
    public MonitorRow(long studentId, String studentName, AttemptState state, Instant startedAt,
                      Instant endedAt, long remainingMillis, int answeredCount, int questionCount,
                      Integer actualMinutes, IntegrityFlag integrity) {
        this(studentId, studentName, state, startedAt, endedAt, remainingMillis, answeredCount,
                questionCount, actualMinutes, integrity, null);
    }

    /** @return {@code true} when this student tripped the C-4 net (F6.8). */
    public boolean isFlagged() {
        return integrity != null;
    }

    /**
     * @return {@code true} when her exam window left focus at least once (F7.1b). A signal for
     *         the teacher to weigh, never a verdict the software has reached
     */
    public boolean hasAttentionEvents() {
        return attention != null && attention.count() > 0;
    }

    /** @return the progress line for the row, "7/20". */
    public String progressLabel() {
        return answeredCount + "/" + questionCount;
    }
}
