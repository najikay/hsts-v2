package client.ui.components;

import client.ui.components.logic.KickerText;
import javafx.scene.control.Label;

/**
 * The small uppercase label above a number or a column (Presentation tier, UI
 * wave 2).
 *
 * <p>Four lines of node building over {@link KickerText}, which is where the
 * transform and its reasoning live: JavaFX CSS has neither
 * {@code text-transform} nor {@code letter-spacing}, so both are done in Java or
 * not at all.
 *
 * <p>The accessible text is the untracked form, set here and never forgotten,
 * which is the entire reason a caller uses this instead of building a Label with
 * {@code KickerText.track(...)} in it.
 */
public final class Kicker {

    /** The style class carrying the 11.5px faint treatment. */
    public static final String STYLE_CLASS = "hsts-kicker";

    private Kicker() {
    }

    /**
     * @param text the copy constant, in ordinary sentence case
     * @return a label showing it uppercased and tracked, reading as the plain
     *         words to a screen reader
     */
    public static Label label(String text) {
        Label label = new Label(KickerText.track(text));
        label.getStyleClass().add(STYLE_CLASS);
        label.setAccessibleText(KickerText.plain(text));
        return label;
    }
}
