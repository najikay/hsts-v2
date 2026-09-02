package client.ui.components.logic;

import client.ui.components.logic.ScoreTrailLogic.Dot;
import client.ui.components.logic.ScoreTrailLogic.Segment;
import client.ui.components.logic.ScoreTrailLogic.Stop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the student trail's geometry with hand-computed fixtures (U-90 full form).
 */
class ScoreTrailLogicTest {

    @Test
    @DisplayName("scores map top-down: 100 at the top, 0 on the baseline, 55 where the pass line is")
    void yMapping() {
        ScoreTrailLogic logic = new ScoreTrailLogic(List.of(new Stop("4821", 90)));
        assertThat(logic.yFor(100, 200)).isEqualTo(0.0);
        assertThat(logic.yFor(0, 200)).isEqualTo(200.0);
        assertThat(logic.yFor(50, 200)).isEqualTo(100.0);
        assertThat(logic.passLineY(200)).isEqualTo(logic.yFor(55, 200));
    }

    @Test
    @DisplayName("stops share the width in centred slots, so three stops sit at 1/6, 3/6 and 5/6")
    void xSlots() {
        ScoreTrailLogic logic = new ScoreTrailLogic(List.of(
                new Stop("a", 60), new Stop("b", 70), new Stop("c", 80)));
        assertThat(logic.xFor(0, 600)).isEqualTo(100.0);
        assertThat(logic.xFor(1, 600)).isEqualTo(300.0);
        assertThat(logic.xFor(2, 600)).isEqualTo(500.0);
    }

    @Test
    @DisplayName("an unapproved sitting breaks the line rather than being bridged")
    void gapsAreHonest() {
        ScoreTrailLogic logic = new ScoreTrailLogic(List.of(
                new Stop("one", 60), new Stop("two", 70),
                new Stop("ungraded", null),
                new Stop("three", 80), new Stop("four", 90)));

        List<Segment> segments = logic.segments(500, 100);
        assertThat(segments)
                .as("two runs either side of the hole; a line drawn across it would invent "
                        + "a number the server has not published")
                .hasSize(2);
        assertThat(segments.get(0).xs()).hasSize(2);
        assertThat(segments.get(1).xs()).hasSize(2);

        assertThat(logic.dots(500, 100))
                .as("only graded stops become dots, but the ungraded one keeps its slot")
                .hasSize(4);
        assertThat(logic.labels()).containsExactly("one", "two", "ungraded", "three", "four");
        assertThat(logic.gradedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("a lone graded stop draws a dot and no line; nothing graded draws nothing")
    void degenerateShapes() {
        ScoreTrailLogic lone = new ScoreTrailLogic(List.of(new Stop("only", 75)));
        assertThat(lone.isDrawable()).isTrue();
        assertThat(lone.segments(400, 100)).as("a polyline needs two points").isEmpty();
        assertThat(lone.dots(400, 100)).hasSize(1);

        ScoreTrailLogic none = new ScoreTrailLogic(List.of(new Stop("pending", null)));
        assertThat(none.isDrawable()).isFalse();
    }

    @Test
    @DisplayName("dots know whether they cleared the pass mark, so the component can colour them")
    void passVerdictOnDots() {
        ScoreTrailLogic logic = new ScoreTrailLogic(List.of(
                new Stop("under", 54), new Stop("at", 55), new Stop("over", 90)));
        List<Dot> dots = logic.dots(300, 100);
        assertThat(dots.get(0).passed()).isFalse();
        assertThat(dots.get(1).passed()).as("the pass mark itself passes").isTrue();
        assertThat(dots.get(2).passed()).isTrue();
    }
}
