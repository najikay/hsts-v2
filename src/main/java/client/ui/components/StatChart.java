package client.ui.components;

import client.ui.anim.Motion;
import client.ui.components.logic.StatChartData;
import client.ui.components.logic.StatChartLogic;
import client.ui.theme.ThemeState;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The score-distribution histogram (Presentation tier, E14.3 — F9.2, F8.5).
 *
 * <p>v1's statistics view was a graded weak point; this component is the answer to it, and
 * F9.2 calls it a first-class view rather than a chart. What it shows, in one frame: a bar per
 * decile, the class mean and median as labelled markers on the score axis, a soft band across
 * the ±1σ spread behind the bars, a hover sentence per bucket, and a segmented toggle that
 * flips the whole thing between students and percent.
 *
 * <p>Thin, on the {@link CountdownTimer} pattern: every number on screen — bar geometry, axis
 * scaling, the marker positions, the clamped σ band, the tick and tooltip copy, and the
 * empty / insufficient decision — comes from {@link StatChartLogic}, which is FX-free and unit
 * tested. This class owns nodes, animation and nothing else.
 *
 * <h2>Colour comes from CSS, always</h2>
 *
 * <p>Bars are filled by {@code -hsts-accent} through the {@code .stat-bar} class, so a palette
 * switch repaints them with no Java involved. The overlays deliberately do <b>not</b> use the
 * semantic tokens: on the Rose palette the accent sits close to {@code -hsts-danger}, and a
 * mean marker drawn in a red-adjacent colour reads as an alarm about the class rather than as
 * a statistic. Markers and the σ band therefore use the neutral ink tokens
 * ({@code -hsts-text}, {@code -hsts-muted}, {@code -hsts-border}) in every palette.
 *
 * <p>Geometry stays in Java and radii stay literal in CSS: JavaFX looked-up values resolve for
 * colours only, which is the rule the whole token layer is written around.
 *
 * <h2>The three states</h2>
 *
 * <ul>
 *   <li><b>Ready</b> — two or more graded attempts: the chart.</li>
 *   <li><b>Insufficient</b> — exactly one: "Not enough results to chart". A distribution, a
 *       mean and a σ over one score are all computable and all meaningless.</li>
 *   <li><b>Empty</b> — none: "No results yet", never ten bars of height zero.</li>
 * </ul>
 */
public final class StatChart extends VBox {

    /** Room on the left for the y-axis labels. */
    private static final double LEFT_GUTTER = 44;

    /** Room at the bottom for the bucket labels. */
    private static final double BOTTOM_GUTTER = 28;

    /** Room at the top for the mean and median labels, which sit above the plot. */
    private static final double TOP_PAD = 30;

    /** How long one bar takes to grow. */
    private static final int BAR_GROW_MS = 120;

    /** Gap between consecutive bars' entrances. */
    private static final int BAR_STAGGER_STEP_MS = 14;

    /**
     * Ceiling on the last bar's delay.
     *
     * <p>{@value #BAR_STAGGER_CAP_MS} + {@value #BAR_GROW_MS} = 246 ms, inside PRD §4.1's
     * {@link Motion#MAX_MS} budget for the whole entrance.
     */
    private static final int BAR_STAGGER_CAP_MS = 126;

    private final Plot plot = new Plot();
    private final StackPane body = new StackPane(plot);
    private final HBox header = new HBox(12);
    private final Label heading = new Label();
    private final Label caption = new Label();
    private final ToggleButton countSegment = new ToggleButton("Count");
    private final ToggleButton percentSegment = new ToggleButton("Percent");

    private final EmptyState emptyState = new EmptyState(Icons.RESULTS,
            StatChartLogic.emptyTitle(), StatChartLogic.emptyHint());
    private final EmptyState insufficientState = new EmptyState(Icons.RESULTS,
            StatChartLogic.insufficientTitle(), StatChartLogic.insufficientHint(1));

    private StatChartLogic logic = new StatChartLogic(StatChartData.empty());
    private StatChartLogic.Scale scale = StatChartLogic.Scale.COUNT;
    private Timeline entrance;
    private boolean animateNextLayout = true;

    /** Builds an empty chart. Call {@link #setData} to fill it. */
    public StatChart() {
        getStyleClass().add("hsts-stat-chart");
        setSpacing(10);

        heading.getStyleClass().addAll("h3", "chart-heading");
        show(heading, false);
        caption.getStyleClass().addAll("small", "muted", "chart-caption");

        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(heading, Buttons.spacer(), buildToggle());

        body.getStyleClass().add("stat-chart-body");
        body.setMinHeight(160);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.getChildren().addAll(emptyState, insufficientState);

        getChildren().addAll(header, body, caption);
        setPrefSize(620, 300);
        refresh();
    }

    // ===================== Input ==========================================

    /**
     * Replaces the distribution and replays the entrance.
     *
     * @param data the execution's frozen statistics; {@link StatChartData#empty()} for an
     *             execution nobody has a score in yet
     */
    public void setData(StatChartData data) {
        this.logic = new StatChartLogic(Objects.requireNonNull(data, "data"));
        this.animateNextLayout = true;
        refresh();
    }

    /** @return the decision layer, for a caller that wants the same numbers as the chart. */
    public StatChartLogic logic() {
        return logic;
    }

    /** Sets the chart's title. Omit it when the surrounding card already carries one. */
    public void setHeading(String text) {
        heading.setText(text == null ? "" : text);
        show(heading, text != null && !text.isBlank());
    }

    /** @return whether bars are currently students or percent. */
    public StatChartLogic.Scale scale() {
        return scale;
    }

    /**
     * Flips between students and percent (F9.2's toggle).
     *
     * <p>Relabels the y axis, rescales every bar and rewrites the tooltips. The bars are not
     * re-animated: the toggle is a change of unit on a chart already on screen, and replaying
     * the entrance would read as new data arriving.
     */
    public void setScale(StatChartLogic.Scale newScale) {
        Objects.requireNonNull(newScale, "scale");
        if (newScale == scale) {
            return;
        }
        scale = newScale;
        countSegment.setSelected(scale == StatChartLogic.Scale.COUNT);
        percentSegment.setSelected(scale == StatChartLogic.Scale.PERCENT);
        animateNextLayout = false;
        refresh();
    }

    /**
     * Repaints when the palette or the mode changes (E4.7).
     *
     * <p>The colours themselves are CSS and need no help. This exists because the marker
     * labels are measured in Java, and a font or metric that moved with the theme would
     * otherwise leave them where the old measurement put them.
     *
     * @param theme the app's theme state; the listener fires once immediately
     */
    public void bindTheme(ThemeState theme) {
        Objects.requireNonNull(theme, "theme");
        theme.addListener(event -> {
            applyCss();
            plot.requestLayout();
        });
    }

    // ===================== Rendering ======================================

    /** Re-reads the logic and rebuilds the plot. Called on every input change. */
    public void refresh() {
        StatChartLogic.State state = logic.state();
        boolean chartable = state == StatChartLogic.State.READY;

        show(plot, chartable);
        show(emptyState, state == StatChartLogic.State.EMPTY);
        show(insufficientState, state == StatChartLogic.State.INSUFFICIENT);
        show(header, chartable);
        show(caption, chartable);

        if (state == StatChartLogic.State.INSUFFICIENT) {
            insufficientState.set(StatChartLogic.insufficientTitle(),
                    StatChartLogic.insufficientHint(logic.data().participantCount()));
        }
        caption.setText(logic.summaryCaption());
        plot.requestLayout();
    }

    private HBox buildToggle() {
        HBox group = new HBox();
        group.getStyleClass().addAll("hsts-segmented", "stat-chart-scale");
        group.setAlignment(Pos.CENTER_RIGHT);

        ToggleGroup toggles = new ToggleGroup();
        countSegment.setToggleGroup(toggles);
        percentSegment.setToggleGroup(toggles);
        countSegment.setSelected(true);
        countSegment.getStyleClass().add("scale-count");
        percentSegment.getStyleClass().add("scale-percent");

        // Clicking the selected segment must not deselect it: a segmented control always has
        // exactly one active option (the ThemeControls rule, applied here too).
        countSegment.setOnAction(e -> {
            countSegment.setSelected(true);
            setScale(StatChartLogic.Scale.COUNT);
        });
        percentSegment.setOnAction(e -> {
            percentSegment.setSelected(true);
            setScale(StatChartLogic.Scale.PERCENT);
        });

        group.getChildren().addAll(countSegment, percentSegment);
        return group;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * The painted surface.
     *
     * <p>One {@link Pane} whose children are all unmanaged and placed by hand, because the
     * layout is a coordinate system rather than a box model: a bar's height <i>is</i> its
     * value, and no standard pane can express that.
     */
    private final class Plot extends Pane {

        private final Rectangle band = new Rectangle();
        private final Line meanMarker = new Line();
        private final Line medianMarker = new Line();
        private final Label meanLabel = new Label();
        private final Label medianLabel = new Label();
        private final Line baseline = new Line();

        private final List<Rectangle> bars = new ArrayList<>();
        private final List<Tooltip> barTips = new ArrayList<>();
        private final List<Label> bucketLabels = new ArrayList<>();
        private final List<Line> gridlines = new ArrayList<>();
        private final List<Label> tickLabels = new ArrayList<>();
        private final Tooltip bandTip = new Tooltip();

        Plot() {
            getStyleClass().add("stat-chart-plot");
            setMinSize(0, 0);

            band.getStyleClass().add("sigma-band");
            band.setMouseTransparent(true);
            baseline.getStyleClass().add("chart-baseline");
            meanMarker.getStyleClass().addAll("stat-marker", "mean");
            medianMarker.getStyleClass().addAll("stat-marker", "median");
            meanLabel.getStyleClass().addAll("marker-label", "mean");
            medianLabel.getStyleClass().addAll("marker-label", "median");

            Tooltip.install(band, bandTip);

            for (int i = 0; i < StatChartData.BUCKET_COUNT; i++) {
                Rectangle bar = new Rectangle();
                bar.getStyleClass().add("stat-bar");
                // Installed once, per bar. Re-installing on every layout pass would attach a
                // fresh Tooltip to the same node on every resize tick.
                Tooltip tip = new Tooltip();
                Tooltip.install(bar, tip);
                barTips.add(tip);
                bars.add(bar);

                Label label = new Label(StatChartLogic.bucketLabel(i));
                label.getStyleClass().add("bucket-label");
                bucketLabels.add(label);
            }

            getChildren().add(band);
            getChildren().addAll(bars);
            getChildren().addAll(baseline, meanMarker, medianMarker, meanLabel, medianLabel);
            getChildren().addAll(bucketLabels);
            for (javafx.scene.Node node : getChildren()) {
                node.setManaged(false);
            }
        }

        @Override
        protected void layoutChildren() {
            double plotWidth = Math.max(0, getWidth() - LEFT_GUTTER);
            double plotHeight = Math.max(0, getHeight() - BOTTOM_GUTTER - TOP_PAD);
            if (plotWidth <= 0 || plotHeight <= 0 || !logic.isChartable()) {
                return;
            }

            layoutGrid(plotWidth, plotHeight);
            layoutBand(plotWidth, plotHeight);
            List<StatChartLogic.Bar> model = logic.bars(plotWidth, plotHeight, scale);
            layoutBars(model, plotHeight);
            layoutMarkers(plotWidth, plotHeight);

            if (animateNextLayout) {
                animateNextLayout = false;
                playEntrance(model, plotHeight);
            }
        }

        private void layoutGrid(double plotWidth, double plotHeight) {
            List<StatChartLogic.Tick> ticks = logic.yTicks(scale, plotHeight);
            syncPool(gridlines, ticks.size(), () -> {
                Line line = new Line();
                line.getStyleClass().add("chart-gridline");
                line.setMouseTransparent(true);
                return line;
            });
            syncPool(tickLabels, ticks.size(), () -> {
                Label label = new Label();
                label.getStyleClass().add("tick-label");
                return label;
            });

            for (int i = 0; i < ticks.size(); i++) {
                StatChartLogic.Tick tick = ticks.get(i);
                double y = TOP_PAD + tick.y();
                Line line = gridlines.get(i);
                line.setStartX(LEFT_GUTTER);
                line.setEndX(LEFT_GUTTER + plotWidth);
                line.setStartY(y);
                line.setEndY(y);

                Label label = tickLabels.get(i);
                label.setText(tick.label());
                label.autosize();
                label.relocate(LEFT_GUTTER - 8 - label.getWidth(), y - label.getHeight() / 2);
            }

            double base = TOP_PAD + plotHeight;
            baseline.setStartX(LEFT_GUTTER);
            baseline.setEndX(LEFT_GUTTER + plotWidth);
            baseline.setStartY(base);
            baseline.setEndY(base);
        }

        private void layoutBand(double plotWidth, double plotHeight) {
            StatChartLogic.Interval pixels = logic.sigmaBandPixels(plotWidth);
            band.setX(LEFT_GUTTER + pixels.low());
            band.setWidth(Math.max(0, pixels.high()));
            band.setY(TOP_PAD);
            band.setHeight(plotHeight);
            bandTip.setText(logic.sigmaLabel());
        }

        private void layoutBars(List<StatChartLogic.Bar> model, double plotHeight) {
            double base = TOP_PAD + plotHeight;
            for (StatChartLogic.Bar spec : model) {
                Rectangle bar = bars.get(spec.index());
                bar.setX(LEFT_GUTTER + spec.x());
                bar.setWidth(spec.width());
                if (!animateNextLayout) {
                    bar.setY(TOP_PAD + spec.y());
                    bar.setHeight(spec.height());
                }
                bar.getStyleClass().remove("empty-bucket");
                if (spec.isEmpty()) {
                    bar.getStyleClass().add("empty-bucket");
                }
                barTips.get(spec.index()).setText(spec.tooltip());

                Label label = bucketLabels.get(spec.index());
                label.autosize();
                label.relocate(LEFT_GUTTER + spec.x() + (spec.width() - label.getWidth()) / 2,
                        base + 8);
            }
        }

        private void layoutMarkers(double plotWidth, double plotHeight) {
            double top = TOP_PAD;
            double base = TOP_PAD + plotHeight;

            double meanX = LEFT_GUTTER + logic.meanX(plotWidth);
            meanMarker.setStartX(meanX);
            meanMarker.setEndX(meanX);
            meanMarker.setStartY(top);
            meanMarker.setEndY(base);
            meanLabel.setText(logic.meanLabel());
            meanLabel.autosize();
            meanLabel.relocate(clampLabelX(meanX, meanLabel.getWidth()), 2);

            double medianX = LEFT_GUTTER + logic.medianX(plotWidth);
            medianMarker.setStartX(medianX);
            medianMarker.setEndX(medianX);
            medianMarker.setStartY(top);
            medianMarker.setEndY(base);
            medianLabel.setText(logic.medianLabel());
            medianLabel.autosize();
            medianLabel.relocate(clampLabelX(medianX, medianLabel.getWidth()), 15);
        }

        /** Keeps a marker's label inside the frame when its marker sits near an edge. */
        private double clampLabelX(double markerX, double labelWidth) {
            double wanted = markerX - labelWidth / 2;
            return Math.max(0, Math.min(getWidth() - labelWidth, wanted));
        }

        /**
         * The staggered grow-up (F9.2).
         *
         * <p>Interruptible: a chart whose data changes mid-entrance stops the running timeline
         * rather than layering a second one over it, which is what would otherwise leave a bar
         * animating towards a height the previous distribution asked for.
         */
        private void playEntrance(List<StatChartLogic.Bar> model, double plotHeight) {
            if (entrance != null) {
                entrance.stop();
            }
            double base = TOP_PAD + plotHeight;
            Timeline timeline = new Timeline();
            for (StatChartLogic.Bar spec : model) {
                Rectangle bar = bars.get(spec.index());
                bar.setY(base);
                bar.setHeight(0);
                int delay = Motion.staggerDelay(spec.index(), BAR_STAGGER_STEP_MS, BAR_STAGGER_CAP_MS);
                timeline.getKeyFrames().addAll(
                        new KeyFrame(Duration.millis(delay),
                                new KeyValue(bar.yProperty(), base),
                                new KeyValue(bar.heightProperty(), 0)),
                        new KeyFrame(Duration.millis(delay + BAR_GROW_MS),
                                new KeyValue(bar.yProperty(), TOP_PAD + spec.y(), Interpolator.EASE_OUT),
                                new KeyValue(bar.heightProperty(), spec.height(), Interpolator.EASE_OUT)));
            }
            entrance = timeline;
            timeline.play();
        }

        private <T extends javafx.scene.Node> void syncPool(List<T> pool, int wanted,
                                                            java.util.function.Supplier<T> factory) {
            while (pool.size() < wanted) {
                T node = factory.get();
                node.setManaged(false);
                pool.add(node);
                // Gridlines belong behind the bars, so they go in at the front.
                getChildren().add(0, node);
            }
            while (pool.size() > wanted) {
                T node = pool.remove(pool.size() - 1);
                getChildren().remove(node);
            }
        }
    }
}
