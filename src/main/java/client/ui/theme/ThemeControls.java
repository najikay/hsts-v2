package client.ui.theme;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.Objects;

/**
 * The reusable mode toggle and palette swatch row (Presentation tier, E4.9).
 *
 * <p>Extracted from the settings screen because three places need exactly these
 * two controls: the Settings screen itself, the {@code --gallery} dev screen's
 * header (so the team can flip mode and palette while inspecting components),
 * and — later — a quick-switch popover on the navbar.
 *
 * <p>Both controls write straight to {@link ThemeState} and read back from it, so
 * they stay correct when the theme changes from somewhere else, and neither
 * holds a copy of the current selection.
 */
public final class ThemeControls {

    /** Lucide "check" path, drawn inside the selected swatch. */
    private static final String CHECK_PATH = "M20 6 9 17l-5-5";

    private ThemeControls() {
    }

    /**
     * @return a segmented Light / Dark / System control bound to {@code state}
     */
    public static HBox modeToggle(ThemeState state) {
        Objects.requireNonNull(state, "state");
        HBox group = new HBox();
        group.getStyleClass().add("hsts-segmented");
        group.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup toggles = new ToggleGroup();
        for (ThemeMode mode : ThemeMode.values()) {
            ToggleButton button = new ToggleButton(segmentText(mode, state));
            button.setToggleGroup(toggles);
            button.setUserData(mode);
            button.setSelected(state.mode() == mode);
            button.setOnAction(e -> {
                // Clicking the selected segment must not deselect it — a
                // segmented control always has exactly one active option.
                button.setSelected(true);
                state.setMode(mode);
            });
            group.getChildren().add(button);
        }

        state.addListener(event -> {
            for (Node node : group.getChildren()) {
                ToggleButton button = (ToggleButton) node;
                button.setSelected(button.getUserData() == event.mode());
                button.setText(segmentText((ThemeMode) button.getUserData(), state));
            }
        });
        return group;
    }

    /**
     * The System segment says what it currently resolves to: on a machine whose
     * OS is in light mode, "System" and "Light" look identical on screen, and
     * without the caption that reads as a broken control rather than a correct one.
     */
    private static String segmentText(ThemeMode mode, ThemeState state) {
        if (mode != ThemeMode.SYSTEM) {
            return mode.displayName();
        }
        return "System (" + (state.systemIsDark() ? "Dark" : "Light") + ")";
    }

    /**
     * @return a row of five colour swatches, the selected one ringed and ticked
     */
    public static HBox paletteSwatches(ThemeState state) {
        Objects.requireNonNull(state, "state");
        HBox row = new HBox();
        row.getStyleClass().add("hsts-swatch-row");
        row.setAlignment(Pos.CENTER_LEFT);

        for (AccentPalette palette : AccentPalette.values()) {
            row.getChildren().add(swatch(state, palette));
        }
        state.addListener(event -> {
            for (Node node : row.getChildren()) {
                AccentPalette swatchPalette = (AccentPalette) node.getUserData();
                boolean selected = swatchPalette == event.palette();
                node.getStyleClass().remove("selected");
                if (selected) {
                    node.getStyleClass().add("selected");
                }
                node.lookupAll(".swatch-check").forEach(check -> check.setVisible(selected));
                // The swatch previews the palette in the mode now in force.
                ((StackPane) node).setStyle(fillStyle(swatchPalette, event.dark()));
            }
        });
        return row;
    }

    /**
     * @return the labelled block used by the settings screen: caption + control
     */
    public static VBox labelled(String caption, String hint, Node control) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("h3");
        Label hintLabel = new Label(hint);
        hintLabel.getStyleClass().addAll("small", "muted");
        hintLabel.setWrapText(true);

        VBox box = new VBox(8, captionLabel, hintLabel, control);
        box.setFillWidth(false);
        return box;
    }

    private static StackPane swatch(ThemeState state, AccentPalette palette) {
        SVGPath check = new SVGPath();
        check.setContent(CHECK_PATH);
        check.setFill(null);
        check.setStroke(Color.WHITE);
        check.setStrokeWidth(3);
        check.getStyleClass().add("swatch-check");
        check.setScaleX(0.6);
        check.setScaleY(0.6);
        check.setVisible(state.palette() == palette);

        StackPane pane = new StackPane(check);
        pane.getStyleClass().add("hsts-swatch");
        pane.setUserData(palette);
        pane.setStyle(fillStyle(palette, state.isDark()));
        if (state.palette() == palette) {
            pane.getStyleClass().add("selected");
        }
        pane.setOnMouseClicked(e -> state.setPalette(palette));
        Tooltip.install(pane, new Tooltip(palette.displayName()));
        pane.setAccessibleText(palette.displayName() + " accent");
        return pane;
    }

    /**
     * The one place in the app that sets a colour inline. Swatches are a
     * deliberate exception: they must show <i>all five</i> palettes at once,
     * which no single set of looked-up accent colours can express.
     */
    private static String fillStyle(AccentPalette palette, boolean dark) {
        return "-fx-background-color: " + palette.accent(dark) + ";";
    }
}
