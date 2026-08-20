package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the relative-time formatter (E17.4).
 *
 * <p>The interesting cases are the two boundaries and the clock-skew guard: two
 * machines on a LAN routinely disagree by a few seconds, and a notification that
 * claims to be from the future is the sort of detail that makes an app look
 * broken.
 */
class RelativeTimeTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("under a minute is 'just now'")
    void justNow() {
        assertThat(RelativeTime.of(NOW, NOW, UTC)).isEqualTo(RelativeTime.JUST_NOW_TEXT);
        assertThat(RelativeTime.of(NOW.minusSeconds(59), NOW, UTC)).isEqualTo("just now");
    }

    @Test
    @DisplayName("a timestamp from the future reads as 'just now', never as negative")
    void futureIsClamped() {
        assertThat(RelativeTime.of(NOW.plusSeconds(30), NOW, UTC)).isEqualTo("just now");
        assertThat(RelativeTime.of(NOW.plus(Duration.ofHours(3)), NOW, UTC)).isEqualTo("just now");
    }

    @Test
    @DisplayName("minutes, then hours, then days")
    void theThreeRelativeBands() {
        assertThat(RelativeTime.of(NOW.minusSeconds(60), NOW, UTC)).isEqualTo("1 min ago");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofMinutes(59)), NOW, UTC)).isEqualTo("59 min ago");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofHours(1)), NOW, UTC)).isEqualTo("1 h ago");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofHours(23)), NOW, UTC)).isEqualTo("23 h ago");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofDays(1)), NOW, UTC)).isEqualTo("1 day ago");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofDays(6)), NOW, UTC)).isEqualTo("6 days ago");
    }

    @Test
    @DisplayName("past a week it switches to a short date")
    void pastTheHorizon() {
        assertThat(RelativeTime.of(NOW.minus(RelativeTime.RELATIVE_HORIZON), NOW, UTC))
                .isEqualTo("12 Aug");
        assertThat(RelativeTime.of(NOW.minus(Duration.ofDays(40)), NOW, UTC)).isEqualTo("10 Jul");
    }

    @Test
    @DisplayName("a date in another year says which year")
    void anotherYearGainsTheYear() {
        assertThat(RelativeTime.of(NOW.minus(Duration.ofDays(400)), NOW, UTC)).isEqualTo("15 Jul 2025");
    }

    @Test
    @DisplayName("no phrase contains an em dash (PRD §4.1)")
    void copyRules() {
        for (Duration age : java.util.List.of(Duration.ZERO, Duration.ofMinutes(5),
                Duration.ofHours(5), Duration.ofDays(3), Duration.ofDays(30))) {
            assertThat(RelativeTime.of(NOW.minus(age), NOW, UTC)).doesNotContain("—");
        }
    }

    @Test
    @DisplayName("the system-zone overload is the same function with a default zone")
    void systemZoneOverload() {
        assertThat(RelativeTime.of(NOW.minus(Duration.ofMinutes(5)), NOW)).isEqualTo("5 min ago");
    }

    @Test
    @DisplayName("every argument is required")
    void argumentsAreRequired() {
        assertThatNullPointerException().isThrownBy(() -> RelativeTime.of(null, NOW, UTC));
        assertThatNullPointerException().isThrownBy(() -> RelativeTime.of(NOW, null, UTC));
        assertThatNullPointerException().isThrownBy(() -> RelativeTime.of(NOW, NOW, null));
    }
}
