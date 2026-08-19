package client.ui.components.logic;

import client.ui.components.logic.CountdownLogic.Threshold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CountdownLogic} — server-synced remaining time, drift correction and
 * the F6.2 threshold ladder (E4.18).
 *
 * <p>A fake clock, so "what happens with 4 minutes left" is a test rather than a
 * 41-minute wait.
 */
class CountdownLogicTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");

    private AtomicReference<Instant> now;
    private CountdownLogic countdown;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(T0);
        countdown = new CountdownLogic(now::get);
    }

    private void advance(Duration by) {
        now.updateAndGet(current -> current.plus(by));
    }

    @Nested
    @DisplayName("starting and syncing")
    class Starting {

        @Test
        void isNotRunningUntilSynced() {
            assertThat(countdown.isRunning()).isFalse();
            assertThat(countdown.endsAt()).isNull();
        }

        @Test
        void everyReadFailsLoudlyBeforeADeadlineIsSet() {
            assertThatIllegalStateException().isThrownBy(() -> countdown.remaining());
            assertThatIllegalStateException().isThrownBy(() -> countdown.threshold());
            assertThatIllegalStateException().isThrownBy(() -> countdown.fractionRemaining());
            assertThatIllegalStateException().isThrownBy(() -> countdown.formatted());
        }

        @Test
        void startForAnchorsToNowPlusTheDuration() {
            countdown.startFor(Duration.ofMinutes(45));

            assertThat(countdown.isRunning()).isTrue();
            assertThat(countdown.endsAt()).isEqualTo(T0.plus(Duration.ofMinutes(45)));
            assertThat(countdown.totalDuration()).isEqualTo(Duration.ofMinutes(45));
            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(45));
        }

        @Test
        void aNegativeStartDurationIsTreatedAsAlreadyExpired() {
            countdown.startFor(Duration.ofMinutes(-5));

            assertThat(countdown.remaining()).isZero();
            assertThat(countdown.isExpired()).isTrue();
        }

        @Test
        void syncToAcceptsAnAbsoluteServerDeadline() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(30)), Duration.ofMinutes(60));

            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(30));
            assertThat(countdown.totalDuration()).isEqualTo(Duration.ofMinutes(60));
        }

        @Test
        void aNegativeTotalIsTreatedAsUnknown() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(30)), Duration.ofMinutes(-1));

            assertThat(countdown.totalDuration()).isZero();
            assertThat(countdown.fractionRemaining()).isZero();
        }

        @Test
        void aNullTotalIsTreatedAsUnknown() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(30)), null);

            assertThat(countdown.totalDuration()).isZero();
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> countdown.syncTo(null, Duration.ofMinutes(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> countdown.startFor(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CountdownLogic(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void theSystemClockFactoryProducesARunningCountdown() {
            CountdownLogic real = CountdownLogic.systemClock();
            real.startFor(Duration.ofMinutes(10));

            assertThat(real.remaining()).isLessThanOrEqualTo(Duration.ofMinutes(10));
            assertThat(real.remaining()).isGreaterThan(Duration.ofMinutes(9));
        }
    }

    @Nested
    @DisplayName("remaining time and drift")
    class Remaining {

        @Test
        void remainingIsRecomputedFromTheClockEachTime() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(60)), Duration.ofMinutes(60));

            advance(Duration.ofMinutes(10));
            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(50));

            advance(Duration.ofMinutes(25));
            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(25));
        }

        @Test
        void aMissedTickIsSelfCorrectingRatherThanCumulative() {
            // The laptop slept for 20 minutes: the countdown must reflect wall
            // time, not the number of ticks it managed to run.
            countdown.syncTo(T0.plus(Duration.ofMinutes(60)), Duration.ofMinutes(60));

            advance(Duration.ofMinutes(20));

            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(40));
        }

        @Test
        void remainingNeverGoesNegative() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(1)), Duration.ofMinutes(60));

            advance(Duration.ofMinutes(30));

            assertThat(countdown.remaining()).isZero();
            assertThat(countdown.isExpired()).isTrue();
        }

        @Test
        void reSyncingCorrectsClientDriftInBothDirections() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(30)), Duration.ofMinutes(60));

            // Server says there is less time than the client thought.
            countdown.syncTo(T0.plus(Duration.ofMinutes(25)), Duration.ofMinutes(60));
            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(25));

            // And more.
            countdown.syncTo(T0.plus(Duration.ofMinutes(28)), Duration.ofMinutes(60));
            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(28));
        }

        @Test
        void fractionRemainingTracksTheAllottedTotal() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(60)), Duration.ofMinutes(60));
            assertThat(countdown.fractionRemaining()).isEqualTo(1.0, within(1e-9));

            advance(Duration.ofMinutes(30));
            assertThat(countdown.fractionRemaining()).isEqualTo(0.5, within(1e-9));

            advance(Duration.ofMinutes(45));
            assertThat(countdown.fractionRemaining()).isZero();
        }

        @Test
        void fractionRemainingIsCappedWhenTheDeadlineExceedsTheStatedTotal() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(90)), Duration.ofMinutes(60));

            assertThat(countdown.fractionRemaining()).isEqualTo(1.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("F6.2 thresholds")
    class Thresholds {

        @BeforeEach
        void startAnHourExam() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(60)), Duration.ofMinutes(60));
        }

        @Test
        void plentyOfTimeIsNormal() {
            assertThat(countdown.threshold()).isEqualTo(Threshold.NORMAL);

            advance(Duration.ofMinutes(30));
            assertThat(countdown.threshold()).isEqualTo(Threshold.NORMAL);
        }

        @Test
        void justAboveAQuarterLeftIsStillNormal() {
            advance(Duration.ofMinutes(44));      // 16 of 60 = 26.7% left

            assertThat(countdown.threshold()).isEqualTo(Threshold.NORMAL);
        }

        @Test
        void exactlyAQuarterLeftIsAmber() {
            advance(Duration.ofMinutes(45));      // 15 of 60 = 25% left

            assertThat(countdown.threshold()).isEqualTo(Threshold.AMBER);
        }

        @Test
        void belowAQuarterLeftIsAmber() {
            advance(Duration.ofMinutes(48));      // 12 minutes left

            assertThat(countdown.threshold()).isEqualTo(Threshold.AMBER);
        }

        @Test
        void justAboveFiveMinutesIsStillAmber() {
            advance(Duration.ofMinutes(54).plusSeconds(59));   // 5:01 left

            assertThat(countdown.threshold()).isEqualTo(Threshold.AMBER);
        }

        @Test
        void exactlyFiveMinutesIsRed() {
            advance(Duration.ofMinutes(55));

            assertThat(countdown.threshold()).isEqualTo(Threshold.RED);
        }

        @Test
        void underFiveMinutesIsRed() {
            advance(Duration.ofMinutes(58));

            assertThat(countdown.threshold()).isEqualTo(Threshold.RED);
        }

        @Test
        void zeroIsExpired() {
            advance(Duration.ofMinutes(60));

            assertThat(countdown.threshold()).isEqualTo(Threshold.EXPIRED);
        }

        @Test
        void redBeatsAmberOnAShortExam() {
            // A 10-minute exam with 4 minutes left is both under 25% and under
            // 5 minutes; the more urgent state must win.
            CountdownLogic shortExam = new CountdownLogic(now::get);
            shortExam.syncTo(T0.plus(Duration.ofMinutes(4)), Duration.ofMinutes(10));

            assertThat(shortExam.threshold()).isEqualTo(Threshold.RED);
        }

        @Test
        void withoutAKnownTotalAmberIsSkippedButRedStillApplies() {
            CountdownLogic unknownTotal = new CountdownLogic(now::get);
            unknownTotal.syncTo(T0.plus(Duration.ofMinutes(20)), Duration.ZERO);

            assertThat(unknownTotal.threshold()).isEqualTo(Threshold.NORMAL);

            advance(Duration.ofMinutes(16));      // 4 minutes left
            assertThat(unknownTotal.threshold()).isEqualTo(Threshold.RED);
        }

        @Test
        void thresholdsCarryTheirStyleClasses() {
            assertThat(Threshold.NORMAL.styleClass()).isEqualTo("normal");
            assertThat(Threshold.AMBER.styleClass()).isEqualTo("amber");
            assertThat(Threshold.RED.styleClass()).isEqualTo("red");
            assertThat(Threshold.EXPIRED.styleClass()).isEqualTo("expired");
        }

        @Test
        void theDocumentedConstantsMatchTheSpec() {
            assertThat(CountdownLogic.AMBER_FRACTION).isEqualTo(0.25);
            assertThat(CountdownLogic.RED_THRESHOLD).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("F7.1 extensions")
    class Extensions {

        @Test
        void extendingMovesBothTheDeadlineAndTheTotal() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(10)), Duration.ofMinutes(60));

            countdown.extendBy(Duration.ofMinutes(15));

            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(25));
            assertThat(countdown.totalDuration()).isEqualTo(Duration.ofMinutes(75));
        }

        @Test
        void anExtensionCanPullTheTimerBackOutOfRed() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(3)), Duration.ofMinutes(60));
            assertThat(countdown.threshold()).isEqualTo(Threshold.RED);

            countdown.extendBy(Duration.ofMinutes(15));

            assertThat(countdown.threshold()).isEqualTo(Threshold.AMBER);
        }

        @Test
        void anExtensionArrivingWithSecondsLeftGrowsTheTimerLive() {
            countdown.syncTo(T0.plus(Duration.ofSeconds(10)), Duration.ofMinutes(60));

            countdown.extendBy(Duration.ofMinutes(15));

            assertThat(countdown.remaining()).isEqualTo(Duration.ofMinutes(15).plusSeconds(10));
            assertThat(countdown.isExpired()).isFalse();
        }

        @Test
        void zeroAndNegativeExtensionsAreRejected() {
            countdown.startFor(Duration.ofMinutes(10));

            assertThatIllegalArgumentException().isThrownBy(() -> countdown.extendBy(Duration.ZERO));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> countdown.extendBy(Duration.ofMinutes(-5)));
        }

        @Test
        void extendingBeforeAnythingStartedFailsLoudly() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> countdown.extendBy(Duration.ofMinutes(5)));
        }

        @Test
        void rejectsANullExtension() {
            countdown.startFor(Duration.ofMinutes(10));
            assertThatThrownBy(() -> countdown.extendBy(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("formatting")
    class Formatting {

        @Test
        void underAnHourIsMinutesAndSeconds() {
            assertThat(CountdownLogic.format(Duration.ofMinutes(42).plusSeconds(7)))
                    .isEqualTo("42:07");
            assertThat(CountdownLogic.format(Duration.ofSeconds(5))).isEqualTo("00:05");
            assertThat(CountdownLogic.format(Duration.ZERO)).isEqualTo("00:00");
        }

        @Test
        void anHourOrMoreGainsAnHoursSegment() {
            assertThat(CountdownLogic.format(Duration.ofMinutes(60))).isEqualTo("1:00:00");
            assertThat(CountdownLogic.format(Duration.ofMinutes(125).plusSeconds(9)))
                    .isEqualTo("2:05:09");
        }

        @Test
        void aNegativeDurationFormatsAsZeroRatherThanAsMinusSomething() {
            assertThat(CountdownLogic.format(Duration.ofSeconds(-30))).isEqualTo("00:00");
        }

        @Test
        void theWidgetFormatsItsOwnRemainingTime() {
            countdown.syncTo(T0.plus(Duration.ofMinutes(12).plusSeconds(4)), Duration.ofMinutes(60));

            assertThat(countdown.formatted()).isEqualTo("12:04");
        }

        @Test
        void anExtensionIsFormattedWithALeadingPlus() {
            assertThat(CountdownLogic.formatGain(Duration.ofMinutes(15))).isEqualTo("+15:00");
            assertThat(CountdownLogic.formatGain(Duration.ofSeconds(90))).isEqualTo("+01:30");
        }

        @Test
        void rejectsANullDuration() {
            assertThatThrownBy(() -> CountdownLogic.format(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
