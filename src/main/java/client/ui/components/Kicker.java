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
        // 2026-08-31, CI round: a kicker is one word and never wraps, so it keeps its
        // preferred width and the row's spacer absorbs the difference. Without the pin the
        // runner's fonts measured "AWAITING GRADING" two pixels wider than the dev machines'
        // and the guard caught the dots only in CI.
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * The same kicker for a table column heading: uppercase, 11.5px, faint —
     * and <b>untracked</b> (2026-08-29, manual rounds 3-4, U-28).
     *
     * <p>Tracking costs about 40% of a heading's width, and a table column is the
     * one place in the app where that width is not the designer's to spend: the
     * columns share out whatever the table was given, and eight of them on a
     * 1024px window left "D I F F I C U L T Y" rendering as "D I F F…". Every
     * other kicker in the app — the cards, the panels — keeps its tracking,
     * because nothing is competing with it for the room.
     *
     * <p>The accessible text is set here too, and it is the same string: an
     * untracked kicker is already the plain words, which is the small bonus of
     * this form.
     *
     * @param text the copy constant, in ordinary sentence case
     * @return a label for a column heading
     */
    public static Label columnLabel(String text) {
        Label label = new Label(KickerText.plain(text));
        label.getStyleClass().add(STYLE_CLASS);
        label.setAccessibleText(KickerText.plain(text));
        return label;
    }
}
