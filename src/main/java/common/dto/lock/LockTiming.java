package common.dto.lock;

import java.time.Duration;

/**
 * The two numbers that make edit locks work, in one place both tiers read
 * (Common tier, E18.1/E18.3).
 *
 * <p>A lock has a TTL because the alternative is a lock that outlives the person
 * holding it: a client killed with the power button sends no release, and an
 * entity nobody can ever edit again is worse than an occasional double edit. The
 * client therefore renews while its editor is open, and the server forgets the
 * lock if the renewals stop.
 *
 * <p>The relationship between the two is the whole design, and it is why they
 * live together rather than one per tier:
 *
 * <ul>
 *   <li><b>TTL 40s</b> — long enough that a brief GC pause, a slow LAN or a
 *       laptop lid closing for a moment does not steal an editor out from under
 *       someone; short enough that a colleague waiting for a crashed client's
 *       lock waits well under a minute, which is the interval a person tolerates
 *       before assuming the app is broken.</li>
 *   <li><b>Heartbeat 12s</b> — a third of the TTL, so <b>three</b> consecutive
 *       renewals must be lost before a live editor loses its lock. One dropped
 *       packet must never cost a teacher their editing session; three in a row
 *       means the client really is gone. {@link #renewalsPerTtl()} pins that
 *       ratio down in a test.</li>
 * </ul>
 *
 * <p>Both tiers reading the same constants is what keeps the ratio true: a
 * server TTL raised without the client's heartbeat following would silently turn
 * every slow moment into a lost lock.
 */
public final class LockTiming {

    /** How long a lock survives without a renewal. */
    public static final Duration TTL = Duration.ofSeconds(40);

    /** How often a client with an open editor renews its lock. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(12);

    private LockTiming() {
    }

    /**
     * @return how many heartbeats fit inside one TTL, i.e. how many consecutive
     *         renewals may be lost before a live editor's lock expires
     */
    public static int renewalsPerTtl() {
        return (int) (TTL.toMillis() / HEARTBEAT.toMillis());
    }
}
