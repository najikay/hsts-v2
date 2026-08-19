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
 * The HSTS brand mark (Presentation tier) — an "A+" grade glyph on the app's
 * indigo gradient tile, built as pure JavaFX vector geometry so it stays
 * razor-sharp at any size and ships no image assets.
 *
 * <p>Chosen from the logo-directions review (2026-08-19): the grade everyone
 * wants, saying "tests" and "excellence" in one glyph. Own geometry, drawn as
 * white round-capped strokes in a 24×24 view box. The same geometry backs
 * {@code resources/branding/hsts-logo.svg}. The tile stays indigo across all
 * five accent palettes by design.
 */
public final class Logo {

    // "A+" path data (24×24 view box), rendered as round-capped strokes.
    private static final String GRADE_A = "M5 19 10.5 5.5 16 19";
    private static final String GRADE_A_BAR = "M6.9 14.2H14.1";
    private static final String PLUS_V = "M19.2 6.2V10.8";
    private static final String PLUS_H = "M16.9 8.5H21.5";

    // Stroke widths in glyph units; the plus is lighter so the A reads first.
    private static final double A_STROKE = 2.6;
    private static final double PLUS_STROKE = 2.2;

    private Logo() {
    }

    /** Builds a fixed-size logo node (gradient tile + A+ glyph). */
    public static StackPane create(double size) {
        Rectangle tile = new Rectangle(size, size);
        tile.setArcWidth(size * 0.42);
        tile.setArcHeight(size * 0.42);
        tile.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#4263eb")),
                new Stop(1, Color.web("#5c7cfa"))));

        double scale = size * 0.62 / 24.0;
        Group glyph = new Group(
                strokePath(GRADE_A, A_STROKE),
                strokePath(GRADE_A_BAR, A_STROKE),
                strokePath(PLUS_V, PLUS_STROKE),
                strokePath(PLUS_H, PLUS_STROKE));
        glyph.getTransforms().add(new Scale(scale, scale));

        StackPane pane = new StackPane(tile, glyph);
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
