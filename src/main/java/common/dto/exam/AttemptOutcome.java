package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * How a sitting ended, and what was handed in (Common tier, E10.4/E10.5 — F6.4, F6.10).
 *
 * <p>One record for both endings, because the two screens that render it are the same
 * layout family: F6.10's celebratory <i>Submitted</i> screen and F6.4's locked <i>Time
 * Up</i> takeover. {@link #state} is the only thing that decides which, and a client that
 * receives this can render the right one without asking anything else.
 *
 * <p>It arrives three ways, and it is identical in all three: as the answer to a manual
 * submit, as the {@code PUSH_FORCE_SUBMITTED} payload when the server closed the attempt
 * on expiry, and inside {@link AttemptForm} when a student comes back to an attempt that
 * ended while she was away. That last route is the one that makes E10.14 work with the
 * client gone: the takeover is not a message the student has to have been present for.
 *
 * <p>{@link #solvingMinutes} is S-19's recorded figure, whichever way the attempt ended.
 *
 * @param attemptId      the attempt this closes
 * @param state          {@link AttemptState#SUBMITTED} or {@link AttemptState#TIMED_OUT}
 * @param examName       the exam's name, so a takeover screen can name what just ended
 * @param endedAt        the handed-in time shown on both screens (UTC; clients render local)
 * @param solvingMinutes actual minutes spent, recorded per S-19
 * @param answeredCount  how many questions carried a choice at the end
 * @param questionCount  how many the paper had
 * @param summary        the per-question grid, in paper order
 */
public record AttemptOutcome(long attemptId,
                             AttemptState state,
                             String examName,
                             Instant endedAt,
                             int solvingMinutes,
                             int answeredCount,
                             int questionCount,
                             List<AttemptSummaryEntry> summary) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttemptOutcome {
        examName = examName == null ? "" : examName;
        summary = summary == null ? List.of() : List.copyOf(summary);
    }

    /** @return {@code true} when the server closed this attempt, not the student (F6.4). */
    public boolean wasForced() {
        return state == AttemptState.TIMED_OUT;
    }

    /** @return how many questions were left blank; they score zero (§6). */
    public int unansweredCount() {
        return Math.max(0, questionCount - answeredCount);
    }
}
