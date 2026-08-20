package common.dto.exam;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * The server's word on the clock (Common tier, E10.12 ⚑ — S-18, F6.2, ADR-010).
 *
 * <p>The v1 defence failed partly because the client decided when an exam was over. Here
 * it decides nothing. This record travels on <b>every</b> response and every push that
 * could move a deadline, and the client's countdown re-anchors to it each time; between
 * messages the countdown only interpolates. A paused laptop, a skewed clock on the second
 * demo machine, or a client that missed the whole extension is corrected by the next thing
 * the server says.
 *
 * <p>Both {@link #serverNow} and {@link #endsAt} are carried, rather than a bare
 * "remaining", so a client can tell the difference between "the server says 12 minutes"
 * and "the server said 12 minutes, forty seconds of network ago" — and
 * {@link #remainingMillis} is carried too, already computed, so no screen re-derives the
 * one number a student is looking at.
 *
 * @param serverNow      the server's own instant when it built this answer (UTC)
 * @param endsAt         when this attempt ends, extensions included
 * @param remainingMillis milliseconds left at {@link #serverNow}, never negative
 * @param totalMillis    the whole allotted duration, extensions included; drives the 25%
 *                       amber threshold (F6.2)
 */
public record AttemptTiming(Instant serverNow,
                            Instant endsAt,
                            long remainingMillis,
                            long totalMillis) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttemptTiming {
        // "-00:07 remaining" is not a thing a student should ever be shown, and clamping
        // here means no screen has to remember to clamp.
        remainingMillis = Math.max(0, remainingMillis);
        totalMillis = Math.max(0, totalMillis);
    }

    /**
     * Builds the timing for an attempt from the two instants that define it.
     *
     * @param now       the server's clock reading
     * @param startedAt when the attempt started
     * @param endsAt    the derived deadline
     * @return the timing to put on the wire
     */
    public static AttemptTiming between(Instant now, Instant startedAt, Instant endsAt) {
        return new AttemptTiming(now, endsAt,
                Duration.between(now, endsAt).toMillis(),
                Duration.between(startedAt, endsAt).toMillis());
    }

    /** The timing of an attempt that is already over: no time left, nothing to count. */
    public static AttemptTiming finished(Instant now, Instant endsAt, long totalMillis) {
        return new AttemptTiming(now, endsAt, 0, totalMillis);
    }

    /** @return time left as a {@link Duration}, for a countdown to anchor to. */
    public Duration remaining() {
        return Duration.ofMillis(remainingMillis);
    }

    /** @return the whole allotted duration, extensions included. */
    public Duration total() {
        return Duration.ofMillis(totalMillis);
    }

    /** @return {@code true} when the server considers this attempt out of time. */
    public boolean hasExpired() {
        return remainingMillis == 0;
    }
}
