package client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Button style-class vocabulary and factories (Presentation tier, E4.11–E4.20).
 *
 * <p>Buttons are <b>styled by CSS class, never by subclass</b> (PRD §4.1). A
 * {@code PrimaryButton extends Button} would freeze the appearance into Java
 * and, worse, would have to re-read colours on every theme change; a
 * {@code .primary} class is re-resolved by the CSS engine for free when the
 * palette swaps. These factories exist only so the class names are typed once
 * and cannot be misspelled at a call site.
 *
 * @see client.ui.theme.ThemeManager
 */
public final class Buttons {

    /** The single committing action of a view. At most one per screen region. */
    public static final String PRIMARY = "primary";

    /** Neutral filled: the common non-committing action ("Cancel", "Back"). */
    public static final String SECONDARY = "secondary";

    /** Tertiary outline: sits on a card without competing with primary. */
    public static final String OUTLINE = "outline";

    /** Destructive action, and the confirm button of a destructive WarnConfirm. */
    public static final String DANGER = "danger";

    /** Cautionary confirm — legal but unusual actions (F5.5, F6.9). */
    public static final String WARN = "warn";

    /** Transparent, icon-first: navbar bell, rail collapse toggle. */
    public static final String GHOST = "ghost";

    /** Inline textual action inside body copy. */
    public static final String LINK = "link";

    /** Size modifiers, combinable with any variant. */
    public static final String SMALL = "small";
    public static final String LARGE = "large";

    /** Stretches the button to its container's width (form submit buttons). */
    public static final String BLOCK = "block";

    private Buttons() {
    }

    /** @return a primary button. */
    public static Button primary(String text) {
        return styled(text, PRIMARY);
    }

    /** @return a secondary button. */
    public static Button secondary(String text) {
        return styled(text, SECONDARY);
    }

    /** @return an outline button. */
    public static Button outline(String text) {
        return styled(text, OUTLINE);
    }

    /** @return a destructive button. */
    public static Button danger(String text) {
        return styled(text, DANGER);
    }

    /** @return a cautionary button. */
    public static Button warn(String text) {
        return styled(text, WARN);
    }

    /** @return a text button carrying the given variant classes. */
    public static Button styled(String text, String... styleClasses) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styleClasses);
        return button;
    }

    /**
     * @return an icon-only ghost button with a tooltip — the accessible form of a
     *         bare icon, since a glyph with no label is unreadable to anyone who
     *         does not already know the app
     */
    public static Button icon(String iconLiteral, String tooltip) {
        Button button = new Button();
        button.getStyleClass().add(GHOST);
        button.setGraphic(Icons.of(iconLiteral, Icons.SIZE_DEFAULT, "nav-icon"));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    /** @return a button with a leading icon and a label. */
    public static Button withIcon(String text, String iconLiteral, String... styleClasses) {
        Button button = styled(text, styleClasses);
        button.setGraphic(Icons.of(iconLiteral, Icons.SIZE_DEFAULT, "nav-icon"));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(8);
        return button;
    }

    /** @return a horizontal spacer that pushes following nodes to the trailing edge. */
    public static Region spacer() {
        Region spacer = new Region();
        javafx.scene.layout.HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** @return a vertical spacer that pushes following nodes to the bottom. */
    public static Region verticalSpacer() {
        Region spacer = new Region();
        javafx.scene.layout.VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Right-aligns a dialog/form button row. */
    public static javafx.scene.layout.HBox row(Node... buttons) {
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(8, buttons);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.getStyleClass().add("dialog-buttons");
        return box;
    }
}
