package client.ui;

import client.core.InMemoryPropertiesStore;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.ui.components.StatChart;
import client.ui.components.logic.StatChartData;
import client.ui.components.logic.StatChartLogic;
import client.ui.theme.AccentPalette;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeMode;
import client.ui.theme.ThemeState;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the score histogram (E14.3 — F9.2).
 *
 * <p>{@code StatChartLogicTest} owns every number; what only a booted toolkit can show is that
 * the numbers reach the screen and that the segmented toggle is wired to them. The headline
 * case is a genuine mouse click on the percent segment followed by an assertion that the axis
 * relabelled itself — the interaction F9.2 asks for, driven the way a teacher drives it rather
 * than by calling {@code setScale} from a test.
 *
 * <p>The fixture is the seeded execution 1 (mean 72.5, median 72.5, σ 17.5, 8 participants),
 * so the labels asserted here are the ones in {@code docs/seed/SEED_CONTENT.md} §9.1.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class StatChartInteractionTest extends ApplicationTest {

    private static final List<Integer> SEEDED_DECILES = List.of(0, 0, 0, 0, 1, 1, 1, 2, 1, 2);

    private StatChart chart;
    private ThemeState theme;
    private Scene scene;

    @BeforeAll
    static void headless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        theme = new ThemeState(new InMemoryPropertiesStore(), () -> false,
                new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster()));
        chart = new StatChart();
        chart.setHeading("Algebra Midterm");
        chart.setData(StatChartData.of(SEEDED_DECILES, 72.5, 72.5, 17.5, 8));
        chart.bindTheme(theme);

        StackPane root = new StackPane(chart);
        root.setPrefSize(760, 420);
        scene = new Scene(root, 760, 420);
        new ThemeManager(theme).attach(scene);

        stage.setScene(scene);
        stage.show();
    }

    @Test
    @DisplayName("clicking the percent segment relabels the axis and the tooltips ⚑")
    void percentToggleRelabelsTheChart() {
        assertThat(chart.scale()).isEqualTo(StatChartLogic.Scale.COUNT);
        assertThat(labelTexts())
                .as("the count axis is labelled in whole students")
                .contains("0", "1", "2", "3")
                .doesNotContain("30%");

        clickOn(segment("Percent"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(chart.scale())
                .as("a real mouse click on the segment must flip the scale")
                .isEqualTo(StatChartLogic.Scale.PERCENT);
        assertThat(labelTexts())
                .as("and the y axis must relabel itself in percent (F9.2)")
                .contains("0%", "10%", "20%", "30%");

        clickOn(segment("Count"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(chart.scale()).isEqualTo(StatChartLogic.Scale.COUNT);
        assertThat(labelTexts()).contains("3").doesNotContain("30%");
    }

    @Test
    @DisplayName("the chart renders ten bars, both markers and the sigma band")
    void rendersTheWholeChart() {
        assertThat(scene.getRoot().lookupAll(".stat-bar"))
                .as("one bar per decile, empty ranges included")
                .hasSize(10);
        assertThat(scene.getRoot().lookupAll(".stat-marker"))
                .as("mean and median")
                .hasSize(2);
        assertThat(scene.getRoot().lookup(".sigma-band")).isNotNull();
        assertThat(labelTexts()).contains("Mean 72.5", "Median 72.5", "0-9", "90-100");
        assertThat(labelTexts())
                .as("the caption restates the stat cards")
                .contains("8 students · mean 72.5 · median 72.5 · σ 17.5");
    }

    @Test
    @DisplayName("bars are laid out from the baseline up, tallest where the seed says")
    void barsFollowTheSeededDistribution() {
        // The entrance is a staggered grow-up bounded at 246 ms; this asserts the settled
        // heights, so it waits the animation out rather than racing it.
        settle();
        List<Rectangle> bars = scene.getRoot().lookupAll(".stat-bar").stream()
                .filter(Rectangle.class::isInstance)
                .map(Rectangle.class::cast)
                .sorted(java.util.Comparator.comparingDouble(Rectangle::getX))
                .toList();

        assertThat(bars.get(0).getHeight())
                .as("nobody scored under 40, so the first bar has no height")
                .isZero();
        assertThat(bars.get(7).getHeight())
                .as("the 70-79 bucket holds two of the eight and is a tallest bar")
                .isGreaterThan(bars.get(4).getHeight());
        assertThat(bars.get(9).getHeight()).isEqualTo(bars.get(7).getHeight());
    }

    @Test
    @DisplayName("an execution nobody has a score in shows the empty state, not zero bars")
    void emptyStateReplacesTheChart() {
        interact(() -> chart.setData(StatChartData.empty()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(scene.getRoot().lookup(".hsts-empty-state")).isNotNull();
        assertThat(labelTexts()).contains("No results yet");
        Node plot = scene.getRoot().lookup(".stat-chart-plot");
        assertThat(plot).isNotNull();
        assertThat(plot.isVisible())
                .as("the plot is withdrawn rather than drawn empty")
                .isFalse();
    }

    @Test
    @DisplayName("one result shows the insufficient-data state and says how many there are")
    void insufficientStateIsSpecific() {
        interact(() -> chart.setData(StatChartData.of(
                List.of(0, 0, 0, 0, 0, 0, 0, 1, 0, 0), 75, 75, 0, 1)));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts()).contains("Not enough results to chart");
        assertThat(labelTexts()).anySatisfy(text ->
                assertThat(text).contains("1 graded attempt"));
    }

    @Test
    @DisplayName("a palette and mode flip repaints the chart without rebuilding it")
    void paletteAndModeFlipSurvive() {
        Node firstBar = scene.getRoot().lookup(".stat-bar");

        interact(() -> {
            theme.setPalette(AccentPalette.ROSE);
            theme.setMode(ThemeMode.DARK);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(scene.getStylesheets()).anySatisfy(sheet ->
                assertThat(sheet).contains("accent-rose"));
        assertThat(scene.getRoot().lookup(".stat-bar"))
                .as("the same nodes: the chart repaints, it does not rebuild")
                .isSameAs(firstBar);
        assertThat(labelTexts())
                .as("and the numbers are untouched by the palette")
                .contains("Mean 72.5");
    }

    @Test
    @DisplayName("⚑ S3: setData with EQUAL data does not restart the entrance")
    void equalDataDoesNotRestartTheEntrance() {
        settle();
        double settled = bars().get(9).getHeight();
        assertThat(settled).as("the top bucket holds two of eight").isGreaterThan(0);

        interact(() -> chart.setData(StatChartData.of(SEEDED_DECILES, 72.5, 72.5, 17.5, 8)));
        WaitForAsyncUtils.waitForFxEvents();

        // Every screen that owns this chart calls setData on every render (the U-61
        // discipline renders often), and a push re-read usually carries the same frozen
        // record. A replayed entrance snaps the bars to zero mid-read.
        assertThat(bars().get(9).getHeight())
                .as("equal data is not a change, so the bars must not move")
                .isEqualTo(settled);
    }

    private List<Rectangle> bars() {
        return scene.getRoot().lookupAll(".stat-bar").stream()
                .filter(Rectangle.class::isInstance)
                .map(Rectangle.class::cast)
                .sorted(java.util.Comparator.comparingDouble(Rectangle::getX))
                .toList();
    }

    // ===================== Helpers =======================================

    /** Waits out the bar entrance (bounded at 246 ms) and lets layout settle. */
    private void settle() {
        sleep(400);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ToggleButton segment(String text) {
        return scene.getRoot().lookupAll(".toggle-button").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no segment labelled " + text));
    }

    private Set<String> labelTexts() {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
