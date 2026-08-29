package client.ui.components;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The two ways a control is stopped from cutting its own text in half
 * (Presentation tier, 2026-08-29, manual rounds 3-4, U-28).
 *
 * <p>Manual rounds 3 and 4 reported the same defect everywhere: "Edit questi…"
 * on a button, a status chip rendered as three dots, a card's explanation losing
 * its last words. The cause is always one of two things, and so is the cure.
 *
 * <ol>
 *   <li><b>A control that must stay one line and got squeezed.</b> A
 *       {@code Labeled} in an {@code HBox} shrinks below its own text when the
 *       row runs out of room, because its default minimum width is
 *       "whatever is left". {@link #oneLine(Labeled...)} pins the minimum to the
 *       preferred size, so the row runs out of room somewhere that can afford
 *       it. This is the rule {@link Buttons#styled(String, String...)} already
 *       applies to every button it makes; this is the same rule for the controls
 *       nobody makes through {@code Buttons} — chips, picker labels, links,
 *       toggles.</li>
 *   <li><b>A control that cannot be sized for what it holds.</b> A cell in a
 *       grid of fixed columns holds text nobody here chose the length of.
 *       {@link #keepOnHover(Labeled)} is the last resort for those, and only
 *       those: what will not fit goes on a tooltip, so the words are never
 *       actually gone.</li>
 * </ol>
 *
 * <p>There is a third fix and it needs no helper: prose — a title, a subtitle, a
 * card's explanation — should <b>wrap</b> rather than end in an ellipsis, which
 * is {@code setWrapText(true)} plus a container that gives it a width.
 *
 * <p>The rule is enforced by {@code client.ui.TruncatedTextGuardTest}, which
 * walks every route of every role at two window sizes and fails with the list.
 *
 * <p>None of this is a substitute for a layout with room in it. Pinning the
 * minimum width of every control in a row that is too narrow only moves the
 * overflow outside the row; the fix there is to give the container room (a
 * spacer, an {@code Hgrow}, one fewer column across) and to use these on what is
 * left.
 */
public final class TextFit {

    private TextFit() {
    }

    /**
     * Stops a control shrinking below its own text.
     *
     * @param control the control that must stay readable on one line
     * @return {@code control}, so a call can be inlined at a field initialiser
     */
    public static <T extends Labeled> T oneLine(T control) {
        control.setMinWidth(Region.USE_PREF_SIZE);
        return control;
    }

    /**
     * Stops several controls shrinking below their own text.
     *
     * @param controls the controls that must stay readable on one line
     */
    public static void oneLine(Labeled... controls) {
        for (Labeled control : controls) {
            oneLine(control);
        }
    }

    /**
     * Keeps on a tooltip whatever a control cannot show
     * (2026-08-29, manual rounds 3-4, U-28).
     *
     * <p>The remedy of last resort, for the one shape of control that genuinely
     * cannot be sized for its content: a cell in a grid of fixed columns, or a
     * picker summarising a row somebody else wrote. A question stem is as long as
     * its author made it and no column width fits every one of them, so the rule
     * the house settled on is that an ellipsis is allowed <b>only where the whole
     * text is one hover away</b>.
     *
     * <p>It is not a licence to skip a layout fix. A button, a chip or a title
     * carries copy this team wrote and a layout this team chose, and putting one
     * of those on a tooltip is hiding a defect rather than fixing it. The
     * exception is a column heading, which is fixed copy but lives in the same
     * fixed grid its cells do: eight columns sharing 449px cannot all read, and
     * {@code DataTable} gives the headings first claim on the width before
     * falling back to this.
     *
     * <p>The decision is re-taken whenever the control's width or text changes,
     * so a window drag that gives the cell room takes the tooltip away again.
     *
     * @param control the control that may have to give up on showing everything
     */
    public static void keepOnHover(Labeled control) {
        control.widthProperty().addListener((observable, was, now) -> putWhatIsCutOffOnHover(control));
        control.textProperty().addListener((observable, was, now) -> putWhatIsCutOffOnHover(control));
        putWhatIsCutOffOnHover(control);
    }

    private static void putWhatIsCutOffOnHover(Labeled control) {
        String shown = control.getText();
        // Zero slack rather than a tolerance: the tooltip must be there for every
        // control the truncation guard would flag, and one pixel either way is
        // not worth a disagreement between the two.
        if (shown == null || shown.isBlank() || control.getWidth() <= 0
                || oneLineOverflowPx(control) <= 0) {
            control.setTooltip(null);
            return;
        }
        Tooltip tooltip = control.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            control.setTooltip(tooltip);
        }
        tooltip.setText(shown);
    }

    /**
     * How far a one-line control's text overruns the box it was given.
     *
     * <p>The house's single answer to "is this cut off?", and it is here rather
     * than in the guard so that <b>the app and the test agree by construction</b>.
     * {@code DataTable} asks this to decide whether a cell needs its text on a
     * tooltip and how narrow a column heading may get;
     * {@code client.ui.TruncatedTextGuardTest} asks the same question through the
     * same arithmetic before it calls a screen broken. Two implementations of one
     * measurement is how a fix lands that the guard still reports.
     *
     * <p>A control's own skin cannot be asked: {@code prefWidth(-1)} on a
     * {@code TableCell} answers with the column's width rather than the text's,
     * which is exactly the sort of quiet disagreement this method exists to end.
     * So the text is laid out in the control's own font and measured directly.
     *
     * @param control a control that has been laid out
     * @return pixels of overrun; zero or less when the text fits, and always zero
     *         for a control with no text (an icon-only button)
     */
    public static double oneLineOverflowPx(Labeled control) {
        String text = control.getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Insets insets = control.getInsets();
        Insets labelPadding = control.getLabelPadding();
        double available = control.getWidth()
                - insets.getLeft() - insets.getRight()
                - labelPadding.getLeft() - labelPadding.getRight()
                - sideGraphicWidth(control);
        return renderedWidth(text, control.getFont()) - available;
    }

    /**
     * @param text the string as it will be drawn
     * @param font the font it will be drawn in
     * @return the width one unwrapped line of it needs
     */
    public static double renderedWidth(String text, Font font) {
        Text probe = new Text(text);
        probe.setFont(font);
        probe.setWrappingWidth(0);
        return probe.getLayoutBounds().getWidth();
    }

    /**
     * @return the width a graphic takes away from the text, which is none at all
     *         when it sits above or below it rather than beside it
     */
    private static double sideGraphicWidth(Labeled control) {
        Node graphic = control.getGraphic();
        if (graphic == null || !graphic.isVisible()) {
            return 0;
        }
        ContentDisplay display = control.getContentDisplay();
        if (display != ContentDisplay.LEFT && display != ContentDisplay.RIGHT) {
            return 0;
        }
        return graphic.getLayoutBounds().getWidth() + control.getGraphicTextGap();
    }
}
