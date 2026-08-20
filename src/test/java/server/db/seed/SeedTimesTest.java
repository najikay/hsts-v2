package server.db.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed's relative timestamps, resolved exactly (E2.15).
 *
 * <p>Every window in {@code SEED_CONTENT.md} §9 is stated relative to load time, so these
 * assertions are the difference between "execution 4 is live" and "execution 4 is live on the
 * machine where it was written". Fixed clocks throughout: an assertion with a tolerance is
 * what hides an off-by-one-day error.
 */
class SeedTimesTest {

    /** An unremarkable mid-afternoon anchor, well away from any boundary. */
    private static final Instant AFTERNOON = Instant.parse("2026-08-20T15:30:00Z");

    @Test
    @DisplayName("T-14d 09:00 is 09:00 UTC on the day fourteen days back")
    void resolvesTheGradedExecutionWindow() {
        SeedTimes times = at(AFTERNOON);

        assertThat(times.dayOffsetAt(-14, 9, 0)).isEqualTo(Instant.parse("2026-08-06T09:00:00Z"));
        assertThat(times.dayOffsetAt(-14, 11, 0)).isEqualTo(Instant.parse("2026-08-06T11:00:00Z"));
    }

    @Test
    @DisplayName("T+0 14:00 is today at 14:00 UTC, not fourteen hours from now")
    void resolvesTodayAtAWallClockTime() {
        SeedTimes times = at(AFTERNOON);

        assertThat(times.dayOffsetAt(0, 14, 0)).isEqualTo(Instant.parse("2026-08-20T14:00:00Z"));
    }

    @Test
    @DisplayName("the anchor's own time of day does not leak into a day offset")
    void dayOffsetsIgnoreTheAnchorsTimeOfDay() {
        // Two loads on the same date at very different times must produce identical windows,
        // or the same seed loaded before and after lunch describes two different exams.
        Instant early = Instant.parse("2026-08-20T00:00:01Z");
        Instant late = Instant.parse("2026-08-20T23:59:59Z");

        assertThat(at(early).dayOffsetAt(-3, 10, 0)).isEqualTo(at(late).dayOffsetAt(-3, 10, 0));
    }

    @Test
    @DisplayName("T-1h and T+1h straddle the anchor, keeping its time of day")
    void resolvesTheLiveWindowAroundNow() {
        SeedTimes times = at(AFTERNOON);

        Instant opens = times.fromNow(Duration.ofHours(-1));
        Instant closes = times.fromNow(Duration.ofHours(1));

        assertThat(opens).isEqualTo(Instant.parse("2026-08-20T14:30:00Z"));
        assertThat(closes).isEqualTo(Instant.parse("2026-08-20T16:30:00Z"));
        assertThat(opens).isBefore(times.anchor());
        assertThat(closes).isAfter(times.anchor());
    }

    @Test
    @DisplayName("one anchor for the whole load, even across midnight")
    void theAnchorIsCapturedOnce() {
        // The bug this prevents reproduces about once a year and never on the machine where
        // it is reported: a loader that reads the clock per row, started at 23:59:59, writes
        // half its rows against one date and half against the next. The ticking clock below
        // advances a second on every read, so a SeedTimes that did not capture its anchor
        // would answer differently the second time and cross the date boundary while doing it.
        SeedTimes times = new SeedTimes(tickingFrom(Instant.parse("2026-08-20T23:59:59Z")));

        Instant first = times.dayOffsetAt(0, 14, 0);
        Instant second = times.dayOffsetAt(0, 14, 0);

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(Instant.parse("2026-08-20T14:00:00Z"));
        assertThat(times.anchor()).isEqualTo(Instant.parse("2026-08-20T23:59:59Z"));
    }

    @Test
    @DisplayName("resolution is UTC regardless of the clock's zone")
    void resolvesInUtcNotTheClocksZone() {
        // ARCHITECTURE §5: stored timestamps are UTC and clients render local. A server in
        // Asia/Jerusalem must still seed 14:00 UTC, or every window shifts by three hours.
        Clock jerusalem = Clock.fixed(AFTERNOON, ZoneId.of("Asia/Jerusalem"));

        assertThat(new SeedTimes(jerusalem).dayOffsetAt(0, 14, 0))
                .isEqualTo(Instant.parse("2026-08-20T14:00:00Z"));
    }

    private static SeedTimes at(Instant instant) {
        return new SeedTimes(Clock.fixed(instant, ZoneOffset.UTC));
    }

    /** A clock that advances one second on every read, to prove the anchor is read once. */
    private static Clock tickingFrom(Instant start) {
        return new Clock() {
            private Instant next = start;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                Instant current = next;
                next = next.plusSeconds(1);
                return current;
            }
        };
    }
}
