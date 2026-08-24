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
