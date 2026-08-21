package common.dto.exam;

import java.io.Serializable;
import java.time.Instant;

/**
 * What the teacher's monitor row says about one student's attention (Common tier, E11.7 —
 * F7.1b).
 *
 * <p>The server's running total of {@code ATTEMPT_ATTENTION} reports for one attempt: how many
 * absences, how long in total, and when the last one ended. Three facts and no interpretation,
 * for the same reason {@link IntegrityFlag} carries a course and a time and nothing else — the
 * server cannot know why a window lost focus, and pretending otherwise on the wire would push
 * a judgement onto a screen that has no business making it.
 *
 * <p><b>Signal, not verdict.</b> F7.1b forbids an auto-penalty, forbids any student-facing UI,
 * and asks for calm copy. {@link #label()} is therefore an observation in the teacher's own
 * words — "Left the exam view 3 times · 40s total" — and the monitor renders it in neutral
 * styling rather than in the danger palette. A count of one on a two-hour exam is normal, and
 * the row must not shout it.
 *
 * <p>Counted, never accumulated on the client: this record is rebuilt from the registry on
 * every monitor snapshot, exactly like the participation counts.
 *
 * @param count           how many absences have been reported for this attempt, at least 1
 * @param totalAwayMillis their durations added up, in milliseconds
 * @param lastAt          when the most recent one was reported (UTC; the client renders local)
 */
public record AttentionSummary(int count, long totalAwayMillis, Instant lastAt)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttentionSummary {
        count = Math.max(0, count);
        totalAwayMillis = Math.max(0, totalAwayMillis);
    }

    /**
     * Adds one more absence.
     *
     * @param awayMillis its duration; a negative value contributes nothing
     * @param at         when it was reported
     * @return a new summary; this record is immutable, like everything else on the wire
     */
    public AttentionSummary plus(long awayMillis, Instant at) {
        return new AttentionSummary(count + 1,
                totalAwayMillis + Math.max(0, awayMillis),
                at == null ? lastAt : at);
    }

    /**
     * @return the row's line: "Left the exam view 3 times · 40s total". Written as something
     *         observed rather than something done wrong, because the teacher is the one who
     *         decides whether it means anything (F7.1b)
     */
    public String label() {
        return "Left the exam view " + occurrences() + " · " + formatAway(totalAwayMillis) + " total";
    }

    /** @return "once" or "3 times" — never "1 times", which reads as a bug in the software. */
    public String occurrences() {
        return count == 1 ? "once" : count + " times";
    }

    /**
     * Formats a duration for the monitor row.
     *
     * <p>Seconds up to a minute, then minutes and seconds. Nothing finer: the client already
     * ignores absences under half a second, and a row reading "40.283s" would claim a
     * precision the measurement does not have.
     *
     * @param millis a duration in milliseconds
     * @return its display form, "40s" or "2m 05s"
     */
    public static String formatAway(long millis) {
        long safe = Math.max(0, millis);
        long seconds = safe / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return String.format("%dm %02ds", seconds / 60, seconds % 60);
    }
}
