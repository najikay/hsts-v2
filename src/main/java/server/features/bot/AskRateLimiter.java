package server.features.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many questions one student may ask per minute (Logic tier, E16.8).
 *
 * <p>Every ask costs a provider call, and a provider call costs money and a second
 * of somebody's attention. A held-down send key, a stuck retry loop or a bored
 * student with a script would spend both. This is the cheapest thing that stops
 * that: a sliding one-minute window per student, in memory.
 *
 * <h2>Why in memory, and what that means</h2>
 *
 * <p>The limit protects the provider bill and the server's throughput, not the
 * integrity of anything — nothing a student can do by asking too many questions is
 * a security problem, and C-4 (the rule that <em>is</em> about integrity) is
 * enforced from the database's view of her attempts, not from here. So the state
 * can live in a map that a restart clears: the failure mode of losing it is that
 * one student briefly gets a few extra questions, which is not a failure worth a
 * table for.
 *
 * <h2>Sliding window, not a token bucket</h2>
 *
 * <p>A fixed-window counter would let a student ask twenty questions across a
 * window boundary; a token bucket would need a refill rate to tune. Keeping the
 * timestamps and dropping the ones older than a minute is exact, needs one number
 * to configure, and is small: at ten per minute the deque per student never holds
 * more than ten instants, and a student who stops asking is evicted by
 * {@link #forget}.
 *
 * <p>The {@link Clock} is injected, so {@code AskRateLimiterTest} proves both the
 * refusal and the recovery by moving time rather than by sleeping through a
 * minute.
 */
public final class AskRateLimiter {

    /** The window the limit applies over. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerWindow;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> asks = new ConcurrentHashMap<>();

    /**
     * @param maxPerWindow how many asks are allowed per {@link #WINDOW}; values
     *                     below one are treated as one, because a limiter that
     *                     allowed nothing would switch the feature off
     * @param clock        the server's clock
     */
    public AskRateLimiter(int maxPerWindow, Clock clock) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records an ask if the student is within her limit.
     *
     * <p>Test-and-record in one call, under the deque's own lock. Two asks arriving
     * on two threads at the same instant would otherwise both read "nine so far"
     * and both be allowed, which is the whole failure this class exists to prevent.
     *
     * @param studentId the student
     * @return {@code true} when the ask may proceed
     */
    public boolean tryAcquire(long studentId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> window = asks.computeIfAbsent(studentId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= maxPerWindow) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * Forgets a student's window — called when her session ends.
     *
     * <p>Not required for correctness; the entries would age out on their own the
     * next time she asked. It exists so a server that has been up for a term does
     * not hold an empty deque per student who ever used the bot.
     *
     * @param studentId the student
     */
    public void forget(long studentId) {
        asks.remove(studentId);
    }

    /** @return how many students currently have a live window; diagnostics and tests. */
    public int trackedStudents() {
        return asks.size();
    }

    /** @return the configured ceiling, for the message that explains a refusal. */
    public int maxPerWindow() {
        return maxPerWindow;
    }
}
