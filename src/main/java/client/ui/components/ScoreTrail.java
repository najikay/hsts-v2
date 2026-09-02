package client.ui.components;

import client.ui.components.logic.ScoreTrailLogic;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;

import java.util.ArrayList;
import java.util.List;

/**
 * One student's scores as a chronological trail (Presentation tier, U-90 full form).
 *
 * <p>Built for exactly one screen - the principal's dedicated student report - and answering
 * the one question that screen exists for: <i>how do her grades move across her sittings?</i>
 * The {@link StatChart} beside it answers where one score sits inside a class; this component
 * answers where her scores are going. Two questions, two drawings, per F9.4's own wording
 * ("how grades change across the exams of the same student").
 *
 * <p>All geometry lives in {@link ScoreTrailLogic}, toolkit-free and unit-tested; this class
 * owns nodes and nothing else, the house split. Gaps are drawn as gaps: an unapproved sitting
 * breaks the line, because a trail that bridged it would invent an unpublished number.
 */
public final class ScoreTrail extends Pane {

    private static final double TOP_PAD = 22;
    private static final double BOTTOM_PAD = 26;
    private static final double SIDE_PAD = 12;
    private static final double DOT_RADIUS = 5;

    private ScoreTrailLogic logic = new ScoreTrailLogic(List.of());

    private final Line baseline = new Line();
    private final Line passLine = new Line();
    private final Label passLabel = new Label("pass " + ScoreTrailLogic.PASS_MARK);
    private final List<Polyline> lines = new ArrayList<>();
    private final List<Circle> dots = new ArrayList<>();
    private final List<Label> scoreLabels = new ArrayList<>();
    private final List<Label> stopLabels = new ArrayList<>();

    public ScoreTrail() {
        getStyleClass().add("score-trail");
        setMinHeight(120);
        setPrefHeight(150);
        baseline.getStyleClass().add("chart-baseline");
        passLine.getStyleClass().add("trail-pass-line");
        passLabel.getStyleClass().add("trail-pass-label");
        getChildren().addAll(baseline, passLine, passLabel);
        for (javafx.scene.Node node : getChildren()) {
            node.setManaged(false);
        }
    }

    /** Replaces the trail. Equal stops are a no-op, the U-61 rule. */
    public void setStops(List<ScoreTrailLogic.Stop> stops) {
        if (stops.equals(logic.stops())) {
            return;
        }
        logic = new ScoreTrailLogic(stops);
        requestLayout();
    }

    /** @return whether the trail has anything to draw; callers hide it otherwise. */
    public boolean isDrawable() {
        return logic.isDrawable();
    }

    @Override
    protected void layoutChildren() {
        double plotWidth = Math.max(0, getWidth() - SIDE_PAD * 2);
        double plotHeight = Math.max(0, getHeight() - TOP_PAD - BOTTOM_PAD);
        if (plotWidth <= 0 || plotHeight <= 0 || !logic.isDrawable()) {
            setPooled(0, 0, 0, 0);
            return;
        }

        double base = TOP_PAD + plotHeight;
        baseline.setStartX(SIDE_PAD);
        baseline.setEndX(SIDE_PAD + plotWidth);
        baseline.setStartY(base);
        baseline.setEndY(base);

        double passY = TOP_PAD + logic.passLineY(plotHeight);
        passLine.setStartX(SIDE_PAD);
        passLine.setEndX(SIDE_PAD + plotWidth);
        passLine.setStartY(passY);
        passLine.setEndY(passY);
        passLabel.autosize();
        passLabel.relocate(SIDE_PAD, passY - passLabel.getHeight() - 1);

        List<ScoreTrailLogic.Segment> segments = logic.segments(plotWidth, plotHeight);
        syncLines(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            ScoreTrailLogic.Segment segment = segments.get(i);
            Polyline line = lines.get(i);
            line.getPoints().clear();
            for (int p = 0; p < segment.xs().size(); p++) {
                line.getPoints().addAll(SIDE_PAD + segment.xs().get(p),
                        TOP_PAD + segment.ys().get(p));
            }
        }

        List<ScoreTrailLogic.Dot> model = logic.dots(plotWidth, plotHeight);
        syncDots(model.size());
        for (int i = 0; i < model.size(); i++) {
            ScoreTrailLogic.Dot dot = model.get(i);
            Circle circle = dots.get(i);
            circle.setCenterX(SIDE_PAD + dot.x());
            circle.setCenterY(TOP_PAD + dot.y());
            circle.setRadius(DOT_RADIUS);
            circle.getStyleClass().setAll("trail-dot", dot.passed() ? "passed" : "failed");
            Label score = scoreLabels.get(i);
            score.setText(String.valueOf(dot.score()));
            score.autosize();
            score.relocate(SIDE_PAD + dot.x() - score.getWidth() / 2,
                    TOP_PAD + dot.y() - DOT_RADIUS - score.getHeight() - 2);
        }

        List<String> labels = logic.labels();
        syncStopLabels(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            Label label = stopLabels.get(i);
            label.setText(labels.get(i));
            label.autosize();
            double x = SIDE_PAD + logic.xFor(i, plotWidth) - label.getWidth() / 2;
            label.relocate(Math.max(0, Math.min(getWidth() - label.getWidth(), x)), base + 6);
        }
    }

    private void setPooled(int a, int b, int c, int d) {
        // Nothing drawable: leave the reference lines where they are; callers hide the node.
    }

    private void syncLines(int wanted) {
        while (lines.size() < wanted) {
            Polyline line = new Polyline();
            line.getStyleClass().add("trail-line");
            line.setManaged(false);
            lines.add(line);
            getChildren().add(line);
        }
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).setVisible(i < wanted);
        }
    }

    private void syncDots(int wanted) {
        while (dots.size() < wanted) {
            Circle circle = new Circle();
            circle.setManaged(false);
            Label score = new Label();
            score.getStyleClass().add("trail-score");
            score.setManaged(false);
            Tooltip.install(circle, new Tooltip());
            dots.add(circle);
            scoreLabels.add(score);
            getChildren().addAll(circle, score);
        }
        for (int i = 0; i < dots.size(); i++) {
            dots.get(i).setVisible(i < wanted);
            scoreLabels.get(i).setVisible(i < wanted);
        }
    }

    private void syncStopLabels(int wanted) {
        while (stopLabels.size() < wanted) {
            Label label = new Label();
            label.getStyleClass().add("trail-stop");
            label.setManaged(false);
            stopLabels.add(label);
            getChildren().add(label);
        }
        for (int i = 0; i < stopLabels.size(); i++) {
            stopLabels.get(i).setVisible(i < wanted);
        }
    }
}
