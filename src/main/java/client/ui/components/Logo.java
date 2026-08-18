package client.ui.components;

import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * The HSTS brand mark (Presentation tier) — a graduation cap on the app's indigo
 * gradient tile, built as pure JavaFX vector geometry so it stays razor-sharp at
 * any size and ships no image assets.
 *
 * <p>The cap glyph is the open-source <a href="https://lucide.dev">Lucide</a>
 * {@code graduation-cap} icon (ISC licensed, free for commercial use), drawn as a
 * white stroke. The same geometry backs {@code resources/branding/hsts-logo.svg}.
 */
public final class Logo {

    // Lucide "graduation-cap" path data (24×24 view box), rendered as strokes.
    private static final String CAP_BOARD =
            "M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z";
    private static final String CAP_TASSEL = "M22 10v6";
    private static final String CAP_BASE = "M6 12.5V16a6 3 0 0 0 12 0v-3.5";

    private Logo() {
    }

    /** Builds a fixed-size logo node (gradient tile + graduation cap). */
    public static StackPane create(double size) {
        Rectangle tile = new Rectangle(size, size);
        tile.setArcWidth(size * 0.42);
        tile.setArcHeight(size * 0.42);
        tile.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#4263eb")),
                new Stop(1, Color.web("#5c7cfa"))));

        double scale = size * 0.60 / 24.0;
        double stroke = (size * 0.052) / scale;   // ≈5% of the tile, after scaling
        Group cap = new Group(
                strokePath(CAP_BOARD, stroke),
                strokePath(CAP_TASSEL, stroke),
                strokePath(CAP_BASE, stroke));
        cap.getTransforms().add(new Scale(scale, scale));

        StackPane pane = new StackPane(tile, cap);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    /** Renders the logo to an {@link Image} for use as the window/taskbar icon. */
    public static Image snapshotImage(double size) {
        StackPane node = create(size);
        node.applyCss();
        node.layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return node.snapshot(params, null);
    }

    private static SVGPath strokePath(String content, double strokeWidth) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.setFill(null);
        path.setStroke(Color.WHITE);
        path.setStrokeWidth(strokeWidth);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }
}
