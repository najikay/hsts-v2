package server.features.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock this feature's tests move by hand.
 *
 * <p>Two rules here are about time passing and both would otherwise cost their own
 * duration in wall time: the provider chain benches a failed provider for a minute
 * (E16.4), and the rate limiter counts asks in a sliding one (E16.8). A test suite
 * that slept through either is a test suite people start skipping, so both are
 * driven by moving this instead.
 *
 * <p>A near-copy of the exam feature's own {@code TestClock}, which is
 * package-private there. Twenty duplicated lines is the cheaper of the two
 * options: a shared test-utility package is something every feature's tests then
 * depend on, and a clock is exactly the sort of thing a suite wants to be able to
 * change without asking anybody.
 */
final class BotTestClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    BotTestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private BotTestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** Moves time forward. */
    void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new BotTestClock(now, otherZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
