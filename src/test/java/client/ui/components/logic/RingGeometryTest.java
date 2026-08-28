package client.ui.components.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link RingGeometry} — the arithmetic behind the student's term-average ring
 * (UI wave 2).
 *
 * <p>JavaFX angles are mathematical: zero degrees is three o'clock and positive
 * is counter-clockwise. A progress ring is neither, and getting the sign wrong
 * produces a ring that fills backwards — which looks deliberate, so nobody
 * questions it. That is the test at the top.
 */
class RingGeometryTest {

    @Test
    @DisplayName("⚑ the ring fills clockwise from twelve o'clock")
    void itFillsClockwiseFromTheTop() {
        assertThat(RingGeometry.START_ANGLE)
                .as("90 degrees is twelve o'clock in JavaFX's angle system")
                .isEqualTo(90);
        assertThat(RingGeometry.sweepFor(25))
                .as("negative length is clockwise; a positive one fills backwards")
                .isNegative();
    }

    @ParameterizedTest
    @CsvSource({
            "0,   0",
            "25,  -90",
            "50,  -180",
            "100, -360"
    })
    @DisplayName("a quarter of the marks is a quarter of the circle")
    void sweepMatchesTheScore(double score, double expected) {
        assertThat(RingGeometry.sweepFor(score)).isCloseTo(expected, within(0.001));
    }

    @Test
    @DisplayName("a score outside the scale is clamped, so the ring is never overdrawn")
    void outOfScaleIsClamped() {
        assertThat(RingGeometry.sweepFor(140)).isEqualTo(-RingGeometry.FULL_SWEEP);
        assertThat(RingGeometry.sweepFor(-30)).isZero();
    }

    @Test
    @DisplayName("⚑ an average of nothing is zero, never a NaN angle JavaFX rejects")
    void notANumberIsZero() {
        // A student with no grades averages nothing. Handing JavaFX a NaN arc
        // length blanks the hero on the one screen that is entirely hero.
        assertThat(RingGeometry.fractionOf(Double.NaN)).isZero();
        assertThat(RingGeometry.sweepFor(Double.NaN)).isZero();
        assertThat(RingGeometry.centreLabel(Double.NaN)).isEqualTo("0");
    }

    // ===================== round caps (2026-08-28, manual round 1) ==========

    /** The ring the app actually draws: 84px across, 7px stroke. */
    private static final double STROKE = 7;
    private static final double RADIUS = (84 - STROKE) / 2;

    @Test
    @DisplayName("⚑ a partial fill is shortened by one round cap at each end")
    void roundCapsComeOffBothEnds() {
        // A round cap is a half-disc painted OUTSIDE the angle it terminates, so
        // an untrimmed arc reads longer than the score it stands for. This is the
        // "a little off" the manual round reported.
        double cap = RingGeometry.capAngle(STROKE, RADIUS);
        assertThat(cap).isCloseTo(Math.toDegrees(3.5 / RADIUS), within(0.001));

        assertThat(RingGeometry.sweepFor(50, STROKE, RADIUS))
                .isCloseTo(-180 + 2 * cap, within(0.001));
        assertThat(RingGeometry.sweepFor(25, STROKE, RADIUS))
                .isCloseTo(-90 + 2 * cap, within(0.001));
    }

    @Test
    @DisplayName("the two boundaries are exact: nothing at 0, the whole circle at 100")
    void theBoundariesAreNotTrimmed() {
        // Neither end has a visible cap to overhang: an empty ring paints no arc,
        // and a full one closes on itself.
        assertThat(RingGeometry.sweepFor(0, STROKE, RADIUS)).isZero();
        assertThat(RingGeometry.sweepFor(100, STROKE, RADIUS))
                .isEqualTo(-RingGeometry.FULL_SWEEP);
        assertThat(RingGeometry.sweepFor(140, STROKE, RADIUS))
                .isEqualTo(-RingGeometry.FULL_SWEEP);
        assertThat(RingGeometry.sweepFor(-30, STROKE, RADIUS)).isZero();
        assertThat(RingGeometry.sweepFor(Double.NaN, STROKE, RADIUS)).isZero();
    }

    @Test
    @DisplayName("⚑ a score too small to survive its own caps draws nothing, not a dot")
    void aTinyScoreIsNotADot() {
        // Two caps are about 10 degrees together, so anything under roughly three
        // marks used to render as a dot at twelve o'clock with no arc behind it —
        // a mark that looked like a rendering fault rather than a low average.
        assertThat(RingGeometry.sweepFor(1, STROKE, RADIUS)).isZero();
        assertThat(RingGeometry.sweepFor(0.5, STROKE, RADIUS)).isZero();
        // And the fill never runs the wrong way to make up the difference.
        assertThat(RingGeometry.sweepFor(2, STROKE, RADIUS)).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("the trimmed sweep is never longer than the untrimmed one")
    void trimmingOnlyEverShortens() {
        for (double score = 1; score < 100; score += 1) {
            assertThat(Math.abs(RingGeometry.sweepFor(score, STROKE, RADIUS)))
                    .as("score %s", score)
                    .isLessThanOrEqualTo(Math.abs(RingGeometry.sweepFor(score)));
        }
    }

    @Test
    @DisplayName("a ring with no size yet subtracts nothing rather than an angle nobody can reason about")
    void anUnsizedRingTrimsNothing() {
        assertThat(RingGeometry.capAngle(0, RADIUS)).isZero();
        assertThat(RingGeometry.capAngle(STROKE, 0)).isZero();
        assertThat(RingGeometry.capAngle(Double.NaN, RADIUS)).isZero();
        assertThat(RingGeometry.capAngle(STROKE, Double.POSITIVE_INFINITY)).isZero();
        assertThat(RingGeometry.sweepFor(50, 0, 0)).isCloseTo(-180, within(0.001));
    }

    @Test
    @DisplayName("the number inside is a whole mark, not six decimal places")
    void theLabelIsAWholeMark() {
        // 78.33333 is a number that invites a student to ask which digit she
        // can act on.
        assertThat(RingGeometry.centreLabel(78.33333)).isEqualTo("78");
        assertThat(RingGeometry.centreLabel(78.5)).isEqualTo("79");
        assertThat(RingGeometry.centreLabel(0)).isEqualTo("0");
    }

    @Test
    @DisplayName("the label is clamped with the arc, so the two never disagree")
    void theLabelAgreesWithTheArc() {
        assertThat(RingGeometry.centreLabel(140)).isEqualTo("100");
        assertThat(RingGeometry.centreLabel(-5)).isEqualTo("0");
    }
}
