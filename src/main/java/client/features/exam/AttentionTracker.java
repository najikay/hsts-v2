package client.features.exam;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Watches whether the exam window still has focus (Presentation tier, E11.7 — F7.1b).
 *
 * <p>FX-free and clock-injected, on the {@link client.ui.components.logic.CountdownLogic}
 * pattern: {@code TakeExamView} does nothing but forward {@code Stage.focusedProperty}
 * changes into {@link #focusChanged}, and every rule about what counts as an absence is
 * decided and unit tested here, against a fake clock rather than by waiting half a second in
 * a test.
 *
 * <h2>Debounced, and reported on the way back</h2>
 *
 * <p>Two rules, and both exist because the raw signal is noisy:
 *
 * <ol>
 *   <li><b>An absence shorter than {@value #FLICKER_MILLIS} ms is not an absence.</b> Window
 *       managers hand focus around for a fraction of a second when a notification appears, a
 *       tooltip opens, or the OS repaints; reporting those would bury one real thirty-second
 *       absence under forty meaningless ones and make the whole feature noise.</li>
 *   <li><b>Nothing is reported on blur, only on refocus.</b> The duration is the fact the
 *       teacher weighs, and a duration does not exist until the absence has ended. It also
 *       means a student who never comes back produces no report at all, which is correct: her
 *       attempt ends by expiry, and the monitor already says so.</li>
 * </ol>
 *
 * <h2>It stops when the attempt does</h2>
 *
 * <p>{@link #stop()} is called on finalisation and on navigation away, and after it nothing is
 * reported — including the refocus that ends an absence which was already running. A submitted
 * paper cannot accrue attention events, and a student who alt-tabs away from the Submitted
 * screen is not doing anything an exam monitor has an opinion about.
 *
 * <p><b>No student-facing anything.</b> This class has no UI, raises no warning and changes
 * nothing the student can see. F7.1b makes that a rule rather than an omission.
 */
public final class AttentionTracker {

    /** Absences shorter than this are focus flicker, not a student leaving (F7.1b). */
    public static final long FLICKER_MILLIS = 500;

    /** The flicker threshold as a {@link Duration}, for callers that prefer one. */
    public static final Duration FLICKER_THRESHOLD = Duration.ofMillis(FLICKER_MILLIS);

    private final Supplier<Instant> clock;
    private final List<LongConsumer> listeners = new ArrayList<>();

    private boolean tracking;
    private Instant awaySince;
    private int reportedAbsences;
    private long totalAwayMillis;

    /**
     * @param clock source of "now" — {@code Instant::now} in production, a fake in tests
     */
    public AttentionTracker(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return a tracker driven by the system clock. */
    public static AttentionTracker systemClock() {
        return new AttentionTracker(Instant::now);
    }

    // ===================== Lifecycle =====================================

    /**
     * Begins watching, from a clean slate.
     *
     * <p>Called when the paper takes over the screen, on a fresh start and on a resume alike.
     * The counters reset because the <b>server</b> accumulates: it holds the running total for
     * the attempt across reconnects, so a client that re-sent its own history would double
     * every absence a student had before her socket dropped.
     */
    public void start() {
        tracking = true;
        awaySince = null;
        reportedAbsences = 0;
        totalAwayMillis = 0;
    }

    /**
     * Stops watching. Called on finalisation and when the screen is left.
     *
     * <p>An absence in progress is discarded rather than reported: the attempt is over, and an
     * event landing after it would be recorded against a paper that has already been handed
     * in.
     */
    public void stop() {
        tracking = false;
        awaySince = null;
    }

    /** @return {@code true} while a live attempt is being watched. */
    public boolean isTracking() {
        return tracking;
    }

    /** @return {@code true} when the window is currently away and the clock is running. */
    public boolean isAway() {
        return tracking && awaySince != null;
    }

    // ===================== The signal ====================================

    /**
     * Feeds one focus change in.
     *
     * @param focused what {@code Stage.focusedProperty} now says
     * @return the reportable absence in milliseconds, or empty — which is the answer for a
     *         blur, for a flicker, and for anything at all once tracking has stopped
     */
    public OptionalLong focusChanged(boolean focused) {
        if (!tracking) {
            return OptionalLong.empty();
        }
        if (!focused) {
            if (awaySince == null) {
                awaySince = clock.get();
            }
            // Nothing is reported here on purpose: an absence has no duration until it ends.
            return OptionalLong.empty();
        }
        if (awaySince == null) {
            // A focus gain with no matching loss: the window was focused when the attempt
            // started, or the platform repeated the event. Neither is an absence.
            return OptionalLong.empty();
        }
        long away = Duration.between(awaySince, clock.get()).toMillis();
        awaySince = null;
        if (away < FLICKER_MILLIS) {
            return OptionalLong.empty();
        }
        reportedAbsences++;
        totalAwayMillis += away;
        for (LongConsumer listener : List.copyOf(listeners)) {
            listener.accept(away);
        }
        return OptionalLong.of(away);
    }

    /**
     * Subscribes to reportable absences.
     *
     * @param listener receives the away duration in milliseconds, once per absence that
     *                 survived the flicker threshold
     */
    public void onAbsence(LongConsumer listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ===================== What has happened so far ======================

    /**
     * @return how many absences this tracker has reported since {@link #start()}. Diagnostics
     *         and tests only: the number the teacher sees is the server's, which survives a
     *         reconnect and this one does not
     */
    public int reportedAbsences() {
        return reportedAbsences;
    }

    /** @return those absences' durations added up, in milliseconds. */
    public long totalAwayMillis() {
        return totalAwayMillis;
    }
}
