package server.db.seed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Resolves the seed's relative timestamps against a single anchor (E2.15).
 *
 * <p>{@code docs/seed/SEED_CONTENT.md} §9 states every execution window relative to load
 * time, not as a fixed date: {@code T-14d 09:00}, {@code T-3d 10:00}, {@code T+0 14:00},
 * {@code T-1h} to {@code T+1h}. That is what makes the dataset demoable, because execution 4
 * has to be genuinely live and execution 3 has to be genuinely today when the demo runs, and
 * a dataset with hardcoded dates from August is neither by September.
 *
 * <h2>Why a Clock, and why one instance</h2>
 *
 * <p>The clock is injected rather than read from {@code Instant.now()} for two independent
 * reasons, and the second one is not about testing:
 *
 * <ol>
 *   <li>Tests need to assert exact instants. A loader that reads the wall clock can only be
 *       tested with tolerances, and a tolerance is what hides an off-by-one-day error.</li>
 *   <li><b>Every timestamp in one load must share one anchor.</b> Calling {@code now()} per
 *       row means a seed that starts at 23:59:59 resolves half its rows against one date and
 *       half against the next, which is a bug that reproduces roughly once a year and never
 *       on the machine where it is reported.</li>
 * </ol>
 *
 * <p>So {@link #anchor()} is captured once at construction and every method resolves against
 * it. One {@code SeedTimes} per load.
 *
 * <h2>UTC</h2>
 *
 * <p>ARCHITECTURE §5: all stored timestamps are UTC and clients render local. The wall-clock
 * offsets below are therefore UTC hours, so {@code T+0 14:00} is 14:00 UTC on the anchor's
 * date, not 14:00 in whatever zone the server happens to sit in.
 */
public final class SeedTimes {

    private final Instant anchor;

    /**
     * @param clock the clock to anchor this load on; {@code Clock.systemUTC()} in production
     */
    public SeedTimes(Clock clock) {
        this.anchor = clock.instant();
    }

    /** @return the single instant every timestamp in this load is resolved against */
    public Instant anchor() {
        return anchor;
    }

    /**
     * A wall-clock time on a day relative to the anchor's date, in UTC.
     *
     * <p>{@code dayOffsetAt(-14, 9, 0)} is the seed's {@code T-14d 09:00}: 09:00 UTC on the
     * date fourteen days before the anchor's date. The anchor's own time of day is discarded,
     * which is what makes the window reproducible regardless of when the loader was started.
     *
     * @param days   days to add to the anchor's date; negative for the past
     * @param hour   hour of day, UTC, 0 to 23
     * @param minute minute of hour, 0 to 59
     * @return the resolved instant
     */
    public Instant dayOffsetAt(int days, int hour, int minute) {
        LocalDate date = LocalDate.ofInstant(anchor, ZoneOffset.UTC).plusDays(days);
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /**
     * An offset from the anchor instant itself, keeping its time of day.
     *
     * <p>This is the form execution 4 needs: {@code T-1h} to {@code T+1h} has to straddle
     * "right now" to be LIVE, so it cannot be pinned to a wall-clock hour the way the other
     * three windows are.
     *
     * @param offset how far from the anchor; negative durations go back
     * @return the resolved instant
     */
    public Instant fromNow(Duration offset) {
        return anchor.plus(offset);
    }
}
