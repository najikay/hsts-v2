package server.features.exam;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the exam tests move by hand.
 *
 * <p>This whole epic is about time passing, and every rule worth testing hardest — an
 * attempt expiring, an answer arriving a second late, a submit racing the timer, an
 * extension landing at T minus ten seconds — would otherwise cost its own duration in wall
 * time. Injecting the clock turns each of them into two lines and, more importantly, makes
 * them exact rather than approximately timed.
 *
 * <p>A near-copy of {@code server.features.locks.MutableClock}, which is package-private
 * there. Duplicating twenty lines is the cheaper of the two options: the alternative is a
 * shared test utility package that every feature's tests then depend on, and a clock is
 * the sort of thing each suite wants to be able to change without asking anybody.
 */
final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    TestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private TestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** Moves time forward. */
    void advance(Duration amount) {
        now = now.plus(amount);
    }

    /** Moves time to an exact instant, for "one millisecond past the deadline" cases. */
    void moveTo(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new TestClock(now, otherZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
