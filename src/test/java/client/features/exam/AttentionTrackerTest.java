package client.features.exam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AttentionTracker} — the client half of attention events (E11.7 — F7.1b).
 *
 * <p>Every rule that decides whether a focus change becomes a message to the server is
 * asserted here, on a fake clock: the flicker threshold, reporting on refocus rather than on
 * blur, accumulation across several absences, and the hard stop at finalisation. The point of
 * the class being FX-free is that "a 400 ms flicker is ignored" is a line of arithmetic rather
 * than a 400 ms wait in a TestFX test.
 */
class AttentionTrackerTest {

    private static final Instant T0 = Instant.parse("2026-08-21T09:00:00Z");

    private Instant now;
    private AttentionTracker tracker;
    private List<Long> reported;

    @BeforeEach
    void setUp() {
        now = T0;
        reported = new ArrayList<>();
        tracker = new AttentionTracker(() -> now);
        tracker.onAbsence(reported::add);
    }

    private void advance(long millis) {
        now = now.plusMillis(millis);
    }

    // ===================== The debounce ==================================

    @Nested
    @DisplayName("the flicker debounce")
    class Flicker {

        @Test
        @DisplayName("an absence shorter than 500 ms is not an absence ⚑")
        void flickerIsIgnored() {
            tracker.start();

            tracker.focusChanged(false);
            advance(400);
            OptionalLong away = tracker.focusChanged(true);

            assertThat(away).isEmpty();
            assertThat(reported).isEmpty();
            assertThat(tracker.reportedAbsences()).isZero();
        }

        @Test
        @DisplayName("exactly 500 ms is reported: the threshold is the shortest real absence")
        void thresholdIsInclusive() {
            tracker.start();

            tracker.focusChanged(false);
            advance(AttentionTracker.FLICKER_MILLIS);
            OptionalLong away = tracker.focusChanged(true);

            assertThat(away).hasValue(AttentionTracker.FLICKER_MILLIS);
            assertThat(reported).containsExactly(AttentionTracker.FLICKER_MILLIS);
        }

        @Test
        @DisplayName("a millisecond under the threshold is still flicker")
        void justUnderIsFlicker() {
            tracker.start();

            tracker.focusChanged(false);
            advance(AttentionTracker.FLICKER_MILLIS - 1);

            assertThat(tracker.focusChanged(true)).isEmpty();
        }

        @Test
        @DisplayName("a burst of flickers reports nothing at all")
        void burstOfFlickersIsSilent() {
            tracker.start();

            for (int i = 0; i < 10; i++) {
                tracker.focusChanged(false);
                advance(80);
                tracker.focusChanged(true);
                advance(200);
            }

            assertThat(reported).isEmpty();
            assertThat(tracker.totalAwayMillis()).isZero();
        }

        @Test
        @DisplayName("the threshold constant and its Duration form agree")
        void thresholdConstantsAgree() {
            assertThat(AttentionTracker.FLICKER_THRESHOLD)
                    .isEqualTo(Duration.ofMillis(AttentionTracker.FLICKER_MILLIS));
        }
    }

    // ===================== Measuring =====================================

    @Nested
    @DisplayName("measuring an absence")
    class Measuring {

        @Test
        @DisplayName("nothing is reported on the way out, only on the way back ⚑")
        void reportedOnRefocusNotOnBlur() {
            tracker.start();

            assertThat(tracker.focusChanged(false))
                    .as("an absence has no duration until it has ended")
                    .isEmpty();
            assertThat(reported).isEmpty();
            assertThat(tracker.isAway()).isTrue();

            advance(30_000);
            assertThat(tracker.focusChanged(true)).hasValue(30_000);
            assertThat(tracker.isAway()).isFalse();
        }

        @Test
        @DisplayName("the reported duration is the time the window was actually away")
        void durationIsMeasured() {
            tracker.start();

            tracker.focusChanged(false);
            advance(12_345);
            tracker.focusChanged(true);

            assertThat(reported).containsExactly(12_345L);
            assertThat(tracker.totalAwayMillis()).isEqualTo(12_345);
        }

        @Test
        @DisplayName("a repeated blur does not restart the clock")
        void repeatedBlurKeepsTheOriginalStart() {
            tracker.start();

            tracker.focusChanged(false);
            advance(5_000);
            tracker.focusChanged(false);
            advance(5_000);
            tracker.focusChanged(true);

            assertThat(reported)
                    .as("the platform repeating the event must not halve the measured absence")
                    .containsExactly(10_000L);
        }

        @Test
        @DisplayName("a focus gain with no matching loss is not an absence of zero")
        void unmatchedFocusGainIsIgnored() {
            tracker.start();

            assertThat(tracker.focusChanged(true)).isEmpty();
            assertThat(tracker.focusChanged(true)).isEmpty();
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("several absences accumulate, and each is reported once ⚑")
        void absencesAccumulate() {
            tracker.start();

            away(12_000);
            advance(60_000);
            away(20_000);
            advance(60_000);
            away(8_000);

            assertThat(reported).containsExactly(12_000L, 20_000L, 8_000L);
            assertThat(tracker.reportedAbsences()).isEqualTo(3);
            assertThat(tracker.totalAwayMillis()).isEqualTo(40_000);
        }

        @Test
        @DisplayName("flickers between real absences do not disturb the totals")
        void flickersDoNotPollute() {
            tracker.start();

            away(12_000);
            away(100);
            away(20_000);
            away(300);

            assertThat(reported).containsExactly(12_000L, 20_000L);
            assertThat(tracker.reportedAbsences()).isEqualTo(2);
            assertThat(tracker.totalAwayMillis()).isEqualTo(32_000);
        }
    }

    // ===================== Lifecycle =====================================

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("nothing is reported before the attempt starts")
        void silentBeforeStart() {
            assertThat(tracker.isTracking()).isFalse();

            tracker.focusChanged(false);
            advance(30_000);

            assertThat(tracker.focusChanged(true)).isEmpty();
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("nothing is reported after finalisation ⚑")
        void silentAfterStop() {
            tracker.start();
            away(12_000);
            tracker.stop();

            away(30_000);

            assertThat(reported)
                    .as("a submitted paper cannot accrue attention events")
                    .containsExactly(12_000L);
        }

        @Test
        @DisplayName("an absence in progress when the attempt ends is discarded, not reported")
        void absenceInProgressAtStopIsDropped() {
            tracker.start();
            tracker.focusChanged(false);
            advance(30_000);

            tracker.stop();
            advance(5_000);

            assertThat(tracker.focusChanged(true)).isEmpty();
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("stop clears the away state, so a restart does not inherit it")
        void stopClearsAwayState() {
            tracker.start();
            tracker.focusChanged(false);
            assertThat(tracker.isAway()).isTrue();

            tracker.stop();

            assertThat(tracker.isAway()).isFalse();
            assertThat(tracker.isTracking()).isFalse();
        }

        @Test
        @DisplayName("starting again resets the counters, because the SERVER keeps the total")
        void startResetsCounters() {
            tracker.start();
            away(12_000);
            assertThat(tracker.reportedAbsences()).isEqualTo(1);

            tracker.start();

            assertThat(tracker.reportedAbsences())
                    .as("a client that replayed its own history would double every absence")
                    .isZero();
            assertThat(tracker.totalAwayMillis()).isZero();
            assertThat(tracker.isAway()).isFalse();
        }

        @Test
        @DisplayName("stopping twice, and stopping without starting, are both safe")
        void stopIsIdempotent() {
            tracker.stop();
            tracker.start();
            tracker.stop();
            tracker.stop();

            assertThat(tracker.isTracking()).isFalse();
        }

        @Test
        @DisplayName("several listeners all hear an absence")
        void listenersAreAllNotified() {
            List<Long> second = new ArrayList<>();
            tracker.onAbsence(second::add);
            tracker.start();

            away(12_000);

            assertThat(reported).containsExactly(12_000L);
            assertThat(second).containsExactly(12_000L);
        }

        @Test
        @DisplayName("a clock and a listener are both required")
        void argumentsAreRequired() {
            assertThatThrownBy(() -> new AttentionTracker(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> tracker.onAbsence(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the system-clock factory produces a working, idle tracker")
        void systemClockFactory() {
            AttentionTracker real = AttentionTracker.systemClock();

            assertThat(real.isTracking()).isFalse();
            assertThat(real.focusChanged(false)).isEmpty();
            real.start();
            assertThat(real.isTracking()).isTrue();
        }
    }

    /** Blurs, waits {@code millis}, and refocuses. */
    private void away(long millis) {
        tracker.focusChanged(false);
        advance(millis);
        tracker.focusChanged(true);
    }
}
