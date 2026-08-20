package server.features.locks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests move by hand.
 *
 * <p>Edit locks are entirely about time passing, and the two things worth
 * testing hardest — a lock expiring, and a heartbeat that arrives a moment too
 * late — would otherwise cost forty seconds of wall time each. Injecting the
 * clock turns them into two lines.
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
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
        return new MutableClock(now, otherZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
