package client.ui.components.logic;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The exam countdown's whole brain (Presentation tier, E4.18, F6.2).
 *
 * <p>S-18 makes the timer <b>server-authoritative</b>, and F6.3 requires a
 * reconnecting client to resume with the correct remaining time. That rules out
 * the naive "count down a local integer once a second" approach: a paused
 * laptop, a slow FX thread or a clock skew between the two demo machines all
 * make it drift, and drifting the wrong way means a student sees time they do
 * not have.
 *
 * <p>So this class stores an <b>end instant</b> and recomputes remaining time
 * from the clock on every tick — a missed tick is self-correcting rather than
 * cumulative. {@link #syncTo} re-anchors that instant whenever the server states
 * the truth (attempt start, reconnect, extension), and {@link #extendBy} handles
 * F7.1's live extension.
 *
 * <p>FX-free and clock-injected, so the amber/red thresholds and the drift
 * behaviour are unit-tested against a fake clock instead of by waiting.
 */
public final class CountdownLogic {

    /** Below this fraction of the total duration the timer turns amber (F6.2). */
    public static final double AMBER_FRACTION = 0.25;

    /** At or below this remaining time the timer turns red (F6.2). */
    public static final Duration RED_THRESHOLD = Duration.ofMinutes(5);

    /** How the timer should currently look. */
    public enum Threshold {
        /** Plenty of time: neutral chip. */
        NORMAL("normal"),
        /** 25% or less remaining: amber chip. */
        AMBER("amber"),
        /** 5 minutes or less remaining: red chip, pulsing. */
        RED("red"),
        /** Time is up: the server has force-submitted (F6.4). */
        EXPIRED("expired");

        private final String styleClass;

        Threshold(String styleClass) {
            this.styleClass = styleClass;
        }

        /** @return the {@code hsts.css} modifier class. */
        public String styleClass() {
            return styleClass;
        }
    }

    private final Supplier<Instant> clock;

    private Instant endsAt;
    private Duration totalDuration;

    /**
     * @param clock source of "now" — {@code Instant::now} in production, a fake in tests
     */
    public CountdownLogic(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.endsAt = null;
        this.totalDuration = Duration.ZERO;
    }

    /** @return a countdown driven by the system clock. */
    public static CountdownLogic systemClock() {
        return new CountdownLogic(Instant::now);
    }

    /**
     * Anchors the countdown to a server-stated deadline. Call on attempt start,
     * on every reconnect, and whenever the server pushes a corrected deadline —
     * this is the drift-correction hook.
     *
     * @param endsAt        when the attempt ends, in server time
     * @param totalDuration the full allotted duration, used for the 25% amber
     *                      threshold; a non-positive value disables amber
     */
    public void syncTo(Instant endsAt, Duration totalDuration) {
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt");
        this.totalDuration = totalDuration == null || totalDuration.isNegative()
                ? Duration.ZERO
                : totalDuration;
    }

    /**
     * Starts a countdown of {@code duration} from now. Convenience for the common
     * case where the server sends "you have 45 minutes" rather than an instant.
     */
    public void startFor(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        Duration safe = duration.isNegative() ? Duration.ZERO : duration;
        syncTo(clock.get().plus(safe), safe);
    }

    /**
     * Applies a teacher's live extension (F7.1): the deadline and the total both
     * move, so the amber threshold follows the new, longer exam.
     *
     * @param extra time added; a non-positive value is rejected (PRD §6: "extend
     *              by 0/negative → validation")
     * @throws IllegalStateException    when no countdown is running
     * @throws IllegalArgumentException when {@code extra} is not positive
     */
    public void extendBy(Duration extra) {
        Objects.requireNonNull(extra, "extra");
        if (extra.isZero() || extra.isNegative()) {
            throw new IllegalArgumentException("An extension must add time, got " + extra);
        }
        requireStarted();
        endsAt = endsAt.plus(extra);
        totalDuration = totalDuration.plus(extra);
    }

    /** @return {@code true} once a deadline has been set. */
    public boolean isRunning() {
        return endsAt != null;
    }

    /** @return the current deadline, or {@code null} before the first sync. */
    public Instant endsAt() {
        return endsAt;
    }

    /** @return the full allotted duration, including any extensions. */
    public Duration totalDuration() {
        return totalDuration;
    }

    /**
     * @return time left, never negative — once the deadline passes this is
     *         {@link Duration#ZERO}, because "-00:07" is not a thing a student
     *         should ever be shown
     */
    public Duration remaining() {
        requireStarted();
        Duration left = Duration.between(clock.get(), endsAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** @return {@code true} when the deadline has passed. */
    public boolean isExpired() {
        return remaining().isZero();
    }

    /**
     * @return the fraction of the allotted time still left, {@code 0.0}–{@code 1.0};
     *         {@code 0} when the total is unknown. Drives the progress ring.
     */
    public double fractionRemaining() {
        requireStarted();
        if (totalDuration.isZero()) {
            return 0;
        }
        double fraction = (double) remaining().toMillis() / totalDuration.toMillis();
        return Math.max(0, Math.min(1, fraction));
    }

    /**
     * The F6.2 threshold ladder. Red wins over amber: with a 10-minute exam,
     * 4 minutes left is both "under 25%" and "under 5 minutes", and the more
     * urgent state is the one to show.
     */
    public Threshold threshold() {
        requireStarted();
        Duration left = remaining();
        if (left.isZero()) {
            return Threshold.EXPIRED;
        }
        if (left.compareTo(RED_THRESHOLD) <= 0) {
            return Threshold.RED;
        }
        if (!totalDuration.isZero() && fractionRemaining() <= AMBER_FRACTION) {
            return Threshold.AMBER;
        }
        return Threshold.NORMAL;
    }

    /** @return the remaining time as {@code mm:ss}, or {@code h:mm:ss} past an hour. */
    public String formatted() {
        return format(remaining());
    }

    /**
     * Formats a duration for the timer chip.
     *
     * <p>Under an hour the display is {@code mm:ss} — the form the mockups show
     * and the one a student can read at a glance. An exam longer than an hour
     * gains an hours segment rather than showing {@code 125:00}.
     */
    public static String format(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long totalSeconds = Math.max(0, duration.getSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Formats an extension for the floating "+15:00" that rises off the chip
     * when a teacher grants time (F7.1).
     */
    public static String formatGain(Duration extra) {
        return "+" + format(extra);
    }

    private void requireStarted() {
        if (endsAt == null) {
            throw new IllegalStateException("Countdown has no deadline; call syncTo/startFor first");
        }
    }
}
