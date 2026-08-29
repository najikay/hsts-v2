package client.ui;

import client.ui.components.TextFit;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The one rule behind {@link TruncatedTextGuardTest}: is this control's text
 * actually readable in the box the layout gave it? (2026-08-29, manual rounds
 * 3-4, U-28.)
 *
 * <p>It lives on its own, package-private and unit-tested by
 * {@code TruncationProbeTest}, because the guard is only as trustworthy as this
 * measurement. A guard that walks four roles and thirty screens and then decides
 * with an inline expression nobody can read is a guard nobody will believe when
 * it goes red.
 *
 * <h2>The measurement</h2>
 *
 * <p>The renderer's own decision is invisible from the outside: JavaFX ellipsises
 * inside the skin and leaves {@code getText()} untouched, so "does it say
 * {@code Edit questi...} on screen?" cannot be asked of the control. What can be
 * asked is whether the text <i>fits</i>, and that is arithmetic:
 *
 * <ul>
 *   <li><b>one-line controls</b> ({@code wrapText} false) are truncated when the
 *       string, laid out in the control's own font, is wider than the control
 *       minus its insets, minus the graphic and the graphic-text gap when the
 *       graphic sits beside the text rather than above or below it;</li>
 *   <li><b>wrapping controls</b> are truncated when the same string, wrapped at
 *       that same available width, is taller than the control minus its insets —
 *       a wrapping label that is one line too short still ends in an ellipsis.</li>
 * </ul>
 *
 * <p>{@link #TOLERANCE_PX} of slack is allowed on both, because the skin rounds
 * and the probe does not: a sub-pixel disagreement is not a defect anyone can
 * see.
 *
 * <p>Empty text is never truncated. That is the icon-only exemption: a
 * {@code Buttons.icon} button carries its meaning in a glyph and a tooltip, and
 * measuring the width of {@code ""} would only ever produce noise.
 *
 * <p>Must be called on the FX thread with layout already settled — it reads
 * {@code getWidth()}, which is zero until a pulse has run.
 */
final class TruncationProbe {

    /**
     * Slack, in pixels, before an overflow is called a defect.
     *
     * <p>One pixel: enough to absorb the rounding the skin does and the probe
     * does not, small enough that a clipped character cannot hide under it.
     */
    static final double TOLERANCE_PX = 1.0;

    private TruncationProbe() {
    }

    /**
     * @param labeled a laid-out control
     * @return how many pixels the text overflows its box by — positive when it
     *         cannot be read in full, zero or negative when it fits
     */
    static double overflowPx(Labeled labeled) {
        String text = labeled.getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (!labeled.isWrapText()) {
            // Deliberately the app's own arithmetic, not a second copy of it:
            // DataTable decides with this whether a cell has lost its text, and a
            // guard that measured differently would report defects the app had
            // already fixed, and miss ones it had not.
            return TextFit.oneLineOverflowPx(labeled);
        }
        Insets insets = labeled.getInsets();
        Insets labelPadding = labeled.getLabelPadding();
        double availableWidth = labeled.getWidth()
                - insets.getLeft() - insets.getRight()
                - labelPadding.getLeft() - labelPadding.getRight()
                - horizontalGraphic(labeled);
        double availableHeight = labeled.getHeight()
                - insets.getTop() - insets.getBottom()
                - labelPadding.getTop() - labelPadding.getBottom()
                - verticalGraphic(labeled);
        return wrappedHeight(text, labeled.getFont(), availableWidth) - availableHeight;
    }

    /** @return {@code true} when the text overflows by more than {@link #TOLERANCE_PX}. */
    static boolean isTruncated(Labeled labeled) {
        return overflowPx(labeled) > TOLERANCE_PX;
    }

    /**
     * The one exemption, and the reason it is one.
     *
     * <p>A grid of fixed columns cannot fit every question stem anybody will ever
     * write, and pretending otherwise would leave this guard permanently red or
     * push it into deleting the check. The rule the house settled on instead is
     * that an ellipsis is allowed only where <b>the whole text is one hover
     * away</b>: {@code DataTable} puts the full value on a tooltip the moment a
     * cell stops fitting, and a control that does that has not lost anything.
     *
     * <p>It is deliberately narrow. The tooltip has to carry <i>this control's
     * own text</i>, so a button with a helpful "opens the question editor"
     * tooltip is still a defect when its label reads "Edit questi…".
     *
     * @return {@code true} when this control's full text is on its own tooltip
     */
    static boolean fullTextIsOnHover(Labeled labeled) {
        Tooltip tooltip = labeled.getTooltip();
        return tooltip != null
                && labeled.getText() != null
                && labeled.getText().equals(tooltip.getText());
    }

    /** @return the width one unwrapped line of {@code text} needs in {@code font}. */
    static double renderedWidth(String text, Font font) {
        return TextFit.renderedWidth(text, font);
    }

    /** @return the height {@code text} needs once wrapped at {@code wrappingWidth}. */
    static double wrappedHeight(String text, Font font, double wrappingWidth) {
        Text probe = new Text(text);
        probe.setFont(font);
        probe.setWrappingWidth(Math.max(0, wrappingWidth));
        return probe.getLayoutBounds().getHeight();
    }

    /**
     * Effective visibility: a node is on screen only when it and every parent
     * above it is visible.
     *
     * <p>Several screens keep one node graph and swap which card shows, so a
     * hidden card's own children still report {@code isVisible() == true}. Asking
     * the node alone is how a guard ends up measuring a screen nobody is looking
     * at.
     */
    static boolean onScreen(Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (!walk.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param root a scene root
     * @return every {@link Labeled} under {@code root} that is effectively
     *         visible and has been laid out, in scene-graph order
     */
    static List<Labeled> visibleLabeled(Parent root) {
        List<Labeled> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Node node, List<Labeled> found) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Labeled labeled && laidOut(labeled)) {
            found.add(labeled);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, found);
            }
        }
    }

    /**
     * A node with no width <i>and</i> no height was never given a box — a cell
     * factory's spare, a control built for a popup that is not open. Measuring
     * one reports a defect that nobody can see, so the guard skips it; a control
     * that got a height and no width is a real squeeze and stays in.
     */
    private static boolean laidOut(Labeled labeled) {
        return labeled.getWidth() > 0 || labeled.getHeight() > 0;
    }

    private static double horizontalGraphic(Labeled labeled) {
        Node graphic = labeled.getGraphic();
        if (graphic == null || !graphic.isVisible()) {
            return 0;
        }
        ContentDisplay display = labeled.getContentDisplay();
        if (display != ContentDisplay.LEFT && display != ContentDisplay.RIGHT) {
            return 0;
        }
        return graphic.getLayoutBounds().getWidth() + labeled.getGraphicTextGap();
    }

    private static double verticalGraphic(Labeled labeled) {
        Node graphic = labeled.getGraphic();
        if (graphic == null || !graphic.isVisible()) {
            return 0;
        }
        ContentDisplay display = labeled.getContentDisplay();
        if (display != ContentDisplay.TOP && display != ContentDisplay.BOTTOM) {
            return 0;
        }
        return graphic.getLayoutBounds().getHeight() + labeled.getGraphicTextGap();
    }
}
