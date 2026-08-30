package server.features.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed-login counter and lockout window, keyed by username (Logic tier, F1.1).
 *
 * <p>Five failures lock an account for thirty seconds. Two design points are
 * worth stating, because both are rules rather than plumbing:
 *
 * <ul>
 *   <li><b>keyed by username, not by connection.</b> A brute-force attempt is
 *       cheapest from many sockets; throttling the socket would stop nothing.
 *       The cost is that someone can lock a known username out on purpose — an
 *       accepted trade at thirty seconds, which annoys an attacker and barely
 *       inconveniences a student who mistyped.</li>
 *   <li><b>the {@link Clock} is injected.</b> "Unlocks after 30 seconds" is the
 *       one rule here that matters, and a test that proves it must not take 30
 *       seconds to run.</li>
 * </ul>
 *
 * <p>A failure recorded while an account is already locked pushes the window out
 * again, so hammering a locked account never shortens the wait. An expired lock
 * is cleared on the next look, handing the user a fresh set of attempts.
 *
 * <h2>What the counter does and does not look at (finding 8, 2026-08-30)</h2>
 *
 * <p>Reported as "too many attempts enforceable only when no change in
 * username/password is made". Half of that is a misreading and half of it is this
 * design, so both halves are now pinned by tests in {@code AuthServiceTest}:
 *
 * <ul>
 *   <li><b>The password is never part of the key.</b> Five wrong guesses lock the
 *       account whether they were five different guesses or the same one five
 *       times. A counter that reset when the guess changed would be no throttle at
 *       all, since varying the guess is the entire activity being throttled.</li>
 *   <li><b>The username is the whole key</b>, so each one counts separately and
 *       attempts on other usernames neither reset nor advance it. Trying five
 *       different names once each therefore locks nobody, which is correct and is
 *       what a tester varying both fields at once will see. It is not a bug: it is
 *       the direct consequence of the first bullet above, and the alternative
 *       keys are worse in both directions.</li>
 * </ul>
 *
 * <p>Thread-safe: every mutation goes through {@link ConcurrentHashMap#compute},
 * so two OCSF read threads racing on the same username cannot lose a failure.
 */
public final class LoginThrottle {

    /** Failed attempts before the lockout starts (F1.1). */
    public static final int MAX_FAILURES = 5;

    /** How long an account stays locked (F1.1). */
    public static final Duration LOCKOUT = Duration.ofSeconds(30);

    /** Map size at which stale (unlocked/expired) entries are purged. */
    static final int PURGE_THRESHOLD = 10_000;

    /** What we remember per username: consecutive failures and, maybe, a lock. */
    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    /** @param clock time source; a fixed/mutable clock in tests */
    public LoginThrottle(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return {@code true} when this username is inside its lockout window.
     *         Clears an expired entry as a side effect, so the next attempt starts
     *         from zero failures
     */
    public boolean isLocked(String username) {
        String key = key(username);
        Instant now = clock.instant();
        Attempts current = attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.lockedUntil() == null) {
                return existing;
            }
            return now.isBefore(existing.lockedUntil()) ? existing : null;
        });
        return current != null && current.lockedUntil() != null;
    }

    /**
     * Records one failed attempt, locking the account when it reaches
     * {@link #MAX_FAILURES} — and re-locking it if it was locked already.
     *
     * @return {@code true} when the account is locked after this call
     */
    public boolean recordFailure(String username) {
        String key = key(username);
        Instant now = clock.instant();
        purgeStaleIfCrowded(now);
        Attempts updated = attempts.compute(key, (ignored, existing) -> {
            int failures = existing == null ? 1 : existing.failures() + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
            return new Attempts(failures, lockedUntil);
        });
        return updated.lockedUntil() != null;
    }

    /**
     * Bounds the map (security review, E5): failures are recorded for ANY
     * submitted username — that uniformity is what prevents user enumeration —
     * so an attacker spraying random names would otherwise grow this map without
     * limit. Past the threshold, entries whose lockout has expired (or that never
     * locked and are simply stale) are dropped; live locks are always kept.
     */
    private void purgeStaleIfCrowded(Instant now) {
        if (attempts.size() < PURGE_THRESHOLD) {
            return;
        }
        attempts.entrySet().removeIf(entry -> {
            Instant lockedUntil = entry.getValue().lockedUntil();
            return lockedUntil == null || !now.isBefore(lockedUntil);
        });
    }

    /** Forgets everything about this username — called on a successful login. */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /** @return consecutive failures currently recorded (diagnostics, tests). */
    public int failureCount(String username) {
        Attempts current = attempts.get(key(username));
        return current == null ? 0 : current.failures();
    }

    /** @return how long the lock still has to run, empty when not locked. */
    public Optional<Duration> remainingLockout(String username) {
        Attempts current = attempts.get(key(username));
        if (current == null || current.lockedUntil() == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(clock.instant(), current.lockedUntil());
        return left.isNegative() || left.isZero() ? Optional.empty() : Optional.of(left);
    }

    /** @return how many usernames are currently tracked (diagnostics, tests). */
    public int trackedUsernames() {
        return attempts.size();
    }

    /** Drops all state (server console "reset lockouts", tests). */
    public void clear() {
        attempts.clear();
    }

    /** Same normalisation as {@link InMemoryUserDirectory} — see its note. */
    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
