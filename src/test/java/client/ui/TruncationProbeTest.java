package client.ui;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule {@link TruncatedTextGuardTest} judges by, on controls whose size this
 * test chose (2026-08-29, manual rounds 3-4, U-28).
 *
 * <p>The guard itself walks real screens, so a failure there says "this screen is
 * wrong" and never "the measurement is wrong". This is where the measurement has
 * to answer for itself: a box that is plainly too small must be called truncated,
 * a box that is plainly big enough must not, and the three things that eat width
 * — padding, a leading graphic, the gap beside it — must all be subtracted.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}. It needs a booted toolkit only
 * because text metrics do.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class TruncationProbeTest extends ApplicationTest {

    private static final String SENTENCE = "Edit question";
    private static final Font FONT = Font.font("System", 14);

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
        // Every case below builds and lays out its own control.
    }

    @Test
    @DisplayName("a label wider than its text is not truncated")
    void roomySingleLineFits() {
        Label label = laidOut(new Label(SENTENCE), 400, 30);

        assertThat(TruncationProbe.isTruncated(label)).isFalse();
        assertThat(TruncationProbe.overflowPx(label)).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("⚡ the same label in half the width is, by the pixels it is short")
    void narrowSingleLineIsTruncated() {
        double needed = onFxThread(() -> TruncationProbe.renderedWidth(SENTENCE, FONT));
        Label label = laidOut(fonted(new Label(SENTENCE)), needed / 2, 30);

        assertThat(TruncationProbe.isTruncated(label)).isTrue();
        assertThat(TruncationProbe.overflowPx(label))
                .as("it is short by about the half that did not fit")
                .isCloseTo(needed / 2, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    @DisplayName("padding counts against the text, not for it")
    void paddingIsSubtracted() {
        double needed = onFxThread(() -> TruncationProbe.renderedWidth(SENTENCE, FONT));
        Label snug = fonted(new Label(SENTENCE));
        snug.setPadding(new Insets(0, 20, 0, 20));
        laidOut(snug, needed + 10, 30);

        // Forty pixels of padding on a box ten wider than the words: the words lose.
        assertThat(TruncationProbe.isTruncated(snug)).isTrue();
    }

    @Test
    @DisplayName("a leading graphic and its gap count against the text too")
    void graphicAndGapAreSubtracted() {
        double needed = onFxThread(() -> TruncationProbe.renderedWidth(SENTENCE, FONT));
        Label withIcon = fonted(new Label(SENTENCE));
        withIcon.setGraphic(square(24));
        withIcon.setContentDisplay(ContentDisplay.LEFT);
        withIcon.setGraphicTextGap(8);
        laidOut(withIcon, needed + 16, 30);

        assertThat(TruncationProbe.isTruncated(withIcon))
                .as("24 of icon plus 8 of gap does not fit in 16 of slack")
                .isTrue();
    }

    @Test
    @DisplayName("a graphic above the text is not in its way")
    void aGraphicOnTopDoesNotEatWidth() {
        double needed = onFxThread(() -> TruncationProbe.renderedWidth(SENTENCE, FONT));
        Label stacked = fonted(new Label(SENTENCE));
        stacked.setGraphic(square(24));
        stacked.setContentDisplay(ContentDisplay.TOP);
        stacked.setGraphicTextGap(8);
        laidOut(stacked, needed + 16, 60);

        assertThat(TruncationProbe.isTruncated(stacked)).isFalse();
    }

    @Test
    @DisplayName("an icon-only button carries no text, so it can never be truncated")
    void emptyTextIsExempt() {
        Button icon = laidOut(new Button(), 8, 8);

        assertThat(TruncationProbe.isTruncated(icon)).isFalse();
    }

    @Test
    @DisplayName("⚡ a wrapping label is judged on height, and one line short is truncated")
    void wrappingLabelIsJudgedOnHeight() {
        String paragraph = "Confirm your exam before you begin. Your teacher can extend "
                + "the time, and the code is the one on the board.";

        Label roomy = fonted(new Label(paragraph));
        roomy.setWrapText(true);
        laidOut(roomy, 240, 200);
        assertThat(TruncationProbe.isTruncated(roomy))
                .as("200px of height is more than the wrapped paragraph needs")
                .isFalse();

        Label cramped = fonted(new Label(paragraph));
        cramped.setWrapText(true);
        laidOut(cramped, 240, 20);
        assertThat(TruncationProbe.isTruncated(cramped))
                .as("one line of box for a paragraph is the defect this guard is for")
                .isTrue();
    }

    @Test
    @DisplayName("a cut-off label whose full text is on its own tooltip is exempt")
    void theHoverExemptionIsTheWholeTextOrNothing() {
        Label cell = fonted(new Label(SENTENCE));
        laidOut(cell, 30, 30);
        assertThat(TruncationProbe.isTruncated(cell))
                .as("still measured as not fitting; the exemption is about the remedy")
                .isTrue();
        assertThat(TruncationProbe.fullTextIsOnHover(cell)).isFalse();

        interact(() -> cell.setTooltip(new Tooltip(SENTENCE)));
        assertThat(TruncationProbe.fullTextIsOnHover(cell))
                .as("the whole text is one hover away")
                .isTrue();

        interact(() -> cell.setTooltip(new Tooltip("Opens the question editor")));
        assertThat(TruncationProbe.fullTextIsOnHover(cell))
                .as("a helpful tooltip that is not the missing words excuses nothing")
                .isFalse();
    }

    // ===================== Fixture =======================================

    /** Puts the control in a scene at exactly this size and runs a layout pass. */
    private <T extends Labeled> T laidOut(T labeled, double width, double height) {
        interact(() -> {
            labeled.setMinSize(width, height);
            labeled.setPrefSize(width, height);
            labeled.setMaxSize(width, height);
            Pane holder = new Pane(labeled);
            new Scene(new Group(holder), Math.max(width, 1) + 100, Math.max(height, 1) + 100);
            holder.applyCss();
            holder.layout();
        });
        WaitForAsyncUtils.waitForFxEvents();
        return labeled;
    }

    /** Pins the font so the expected widths below are the ones being measured. */
    private static <T extends Labeled> T fonted(T labeled) {
        labeled.setFont(FONT);
        return labeled;
    }

    private static Region square(double side) {
        Region region = new Region();
        region.setMinSize(side, side);
        region.setPrefSize(side, side);
        region.setMaxSize(side, side);
        region.resize(side, side);
        return region;
    }

    private double onFxThread(java.util.function.Supplier<Double> work) {
        double[] result = new double[1];
        interact(() -> result[0] = work.get());
        return result[0];
    }
}
