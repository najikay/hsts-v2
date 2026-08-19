package client.ui.components;

import client.ui.anim.Animations;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Loading placeholders (Presentation tier, E4.16).
 *
 * <p>A skeleton beats a spinner for list and detail screens: it says how much is
 * coming and where, so the layout does not jump when data lands — which matters
 * on the demo machines where a cold DB page can take a moment (NFR-21: every
 * async op shows progress).
 *
 * <p>The shimmer is a Java-side opacity loop rather than CSS, because JavaFX CSS
 * has no keyframes. {@link #stopShimmer} must be called when real content
 * replaces the skeleton — {@code DataTable} does this for its own placeholders.
 */
public final class Skeletons {

    private Skeletons() {
    }

    /** @return a shimmering text-line placeholder of the given width. */
    public static Region line(double width) {
        return block(width, 12, "text");
    }

    /** @return a shimmering title-line placeholder. */
    public static Region title(double width) {
        return block(width, 18, "title");
    }

    /** @return a shimmering circular placeholder (avatars, icon discs). */
    public static Region circle(double diameter) {
        Region region = block(diameter, diameter, "circle");
        region.setMinSize(diameter, diameter);
        region.setMaxSize(diameter, diameter);
        return region;
    }

    /** @return a shimmering rectangular block (cards, images). */
    public static Region card(double width, double height) {
        return block(width, height, "block");
    }

    /**
     * @return a stack of {@code rows} placeholder lines with alternating widths,
     *         the shape a loading table or list takes
     */
    public static VBox list(int rows) {
        VBox box = new VBox(10);
        box.getStyleClass().add("hsts-skeleton-list");
        for (int i = 0; i < rows; i++) {
            box.getChildren().add(row(i));
        }
        return box;
    }

    /** Stops the shimmer on a skeleton and everything inside it. */
    public static void stopShimmer(Node node) {
        if (node == null) {
            return;
        }
        Animations.stop(node);
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                stopShimmer(child);
            }
        }
    }

    /** Alternating widths read as text rather than as a barcode. */
    private static HBox row(int index) {
        double leading = index % 2 == 0 ? 180 : 140;
        double trailing = index % 3 == 0 ? 90 : 120;
        HBox row = new HBox(12, line(leading), line(trailing));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private static Region block(double width, double height, String variant) {
        Region region = new Region();
        region.getStyleClass().addAll("hsts-skeleton", variant);
        region.setPrefSize(width, height);
        region.setMinHeight(height);
        Animations.shimmer(region);
        return region;
    }
}
