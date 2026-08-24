package client.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * The "nothing here" panel (Presentation tier, E4.17).
 *
 * <p>PRD §4.1 forbids dead screens, and an empty list is where they appear. Every
 * empty state therefore answers three questions in one component: what would be
 * here (icon + title), why it is not (hint), and what to do about it (action).
 * A bank with no questions offers "Add the first question"; a filtered list with
 * no matches offers "Clear filters"; a principal's read-only view offers no
 * action at all — the slot is optional precisely so the read-only roles do not
 * get an action they cannot perform (F9.3).
 *
 * <p>The illustration slot takes any node, so E4.21's unDraw artwork drops in
 * later without touching call sites.
 */
public final class EmptyState extends VBox {

    private final Label titleLabel = new Label();
    private final Label hintLabel = new Label();
    private final StackPane illustration = new StackPane();

    /**
     * @param iconLiteral Ikonli literal for the default circular illustration
     * @param title       what would be here ("No questions yet")
     * @param hint        why, and what it means ("This course's bank is empty.")
     */
    public EmptyState(String iconLiteral, String title, String hint) {
        getStyleClass().add("hsts-empty-state");
        setAlignment(Pos.CENTER);
        setSpacing(10);

        illustration.getStyleClass().add("empty-illustration");
        illustration.getChildren().add(Icons.of(iconLiteral, 30, "empty-icon"));

        titleLabel.setText(title);
        titleLabel.getStyleClass().add("empty-title");

        hintLabel.setText(hint);
        hintLabel.getStyleClass().add("empty-hint");
        hintLabel.setWrapText(true);
        hintLabel.setMaxWidth(360);

        getChildren().addAll(illustration, titleLabel, hintLabel);
        setShown(hintLabel, hint != null && !hint.isBlank());

        // UI wave 2: the one ambient breathing loop in the app, and it runs only
        // while this panel is actually on screen. Every DataTable builds an empty
        // state whether or not it shows one, so a loop started in a constructor
        // and never stopped would be one indefinite timeline per table in the
        // build, all of them animating nothing.
        visibleProperty().addListener((obs, was, showing) -> breathe(showing));
        breathe(isVisible());
    }

    /** Starts or stops the illustration's breathing, following visibility. */
    private void breathe(boolean showing) {
        if (showing) {
            client.ui.anim.Animations.breathe(illustration);
        } else {
            client.ui.anim.Animations.reset(illustration);
        }
    }

    /** @return the standard "no results for these filters" state. */
    public static EmptyState noResults() {
        return new EmptyState(Icons.SEARCH, "No matches",
                "No items match the current filters. Try widening the search.");
    }

    /** @return the standard "this failed" state, with a retry action. */
    public static EmptyState error(String hint, Runnable onRetry) {
        EmptyState state = new EmptyState(Icons.CLOUD_OFF, "Could not load this", hint);
        return state.action("Try again", onRetry);
    }

    /** Adds the call-to-action button. Omit it for read-only contexts. */
    public EmptyState action(String label, Runnable onAction) {
        Objects.requireNonNull(onAction, "onAction");
        Button button = Buttons.primary(label);
        button.setOnAction(e -> onAction.run());
        getChildren().add(button);
        return this;
    }

    /** Replaces the default icon disc with custom artwork (E4.21). */
    public EmptyState illustration(Node artwork) {
        Objects.requireNonNull(artwork, "artwork");
        illustration.getChildren().setAll(artwork);
        illustration.getStyleClass().remove("empty-illustration");
        return this;
    }

    /** Updates the text in place, for a state that changes reason (empty → filtered). */
    public void set(String title, String hint) {
        titleLabel.setText(title);
        hintLabel.setText(hint);
        setShown(hintLabel, hint != null && !hint.isBlank());
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
