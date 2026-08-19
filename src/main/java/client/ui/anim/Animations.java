package client.ui.anim;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.List;
import java.util.Objects;

/**
 * The app's motion vocabulary (Presentation tier, E4.20, PRD §4.1).
 *
 * <p>Every animation in HSTS goes through one of these methods, which buys three
 * things no scattered {@code new FadeTransition(...)} can:
 * <ul>
 *   <li><b>the 250ms budget is structural</b> — durations pass through
 *       {@link Motion#clampMillis}, so an over-eager caller is corrected rather
 *       than shipped;</li>
 *   <li><b>everything is interruptible</b> — each node remembers its running
 *       animation in a property and the next call stops it first, so a user
 *       clicking twice in 80ms never sees two transitions fight;</li>
 *   <li><b>one house feel</b> — the same easing everywhere.</li>
 * </ul>
 *
 * <p>This class is a thin shell on purpose: all arithmetic lives in
 * {@link Motion}, which is unit-tested; here there is nothing but JavaFX
 * plumbing.
 */
public final class Animations {

    /** Property key under which a node's currently-running HSTS animation is stashed. */
    private static final Object RUNNING_KEY = new Object();

    /** House easing: quick out of the gate, settled at the end. */
    public static final Interpolator EASE = Interpolator.SPLINE(0.2, 0.0, 0.0, 1.0);

    private Animations() {
    }

    // ------------------------------------------------------------------ fades

    /** Fades a node in over the house duration. */
    public static Animation fadeIn(Node node) {
        return fadeIn(node, Motion.BASE_MS);
    }

    /** Fades a node in from fully transparent. */
    public static Animation fadeIn(Node node, int millis) {
        Objects.requireNonNull(node, "node");
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(duration(millis), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        return play(node, fade);
    }

    /** Fades a node out to fully transparent (it stays in the layout). */
    public static Animation fadeOut(Node node, int millis) {
        Objects.requireNonNull(node, "node");
        FadeTransition fade = new FadeTransition(duration(millis), node);
        fade.setFromValue(node.getOpacity());
        fade.setToValue(0);
        fade.setInterpolator(EASE);
        return play(node, fade);
    }

    // ----------------------------------------------------------------- slides

    /** Slides a node in horizontally while fading it in. Used by the toast stack. */
    public static Animation slideInX(Node node, boolean fromLeadingEdge) {
        return slideInX(node, fromLeadingEdge, Motion.SLIDE_DISTANCE, Motion.BASE_MS);
    }

    /** @see #slideInX(Node, boolean) */
    public static Animation slideInX(Node node, boolean fromLeadingEdge, double distance, int millis) {
        Objects.requireNonNull(node, "node");
        double offset = Motion.slideOffset(distance, fromLeadingEdge);
        node.setTranslateX(offset);
        node.setOpacity(0);

        TranslateTransition slide = new TranslateTransition(duration(millis), node);
        slide.setFromX(offset);
        slide.setToX(0);
        slide.setInterpolator(EASE);
        return play(node, new ParallelTransition(node, slide, fadeComponent(node, millis)));
    }

    /** Slides a node in vertically while fading it in. Used by screen transitions. */
    public static Animation slideInY(Node node, boolean fromTop) {
        return slideInY(node, fromTop, Motion.SLIDE_DISTANCE, Motion.BASE_MS);
    }

    /** @see #slideInY(Node, boolean) */
    public static Animation slideInY(Node node, boolean fromTop, double distance, int millis) {
        Objects.requireNonNull(node, "node");
        double offset = Motion.slideOffset(distance, fromTop);
        node.setTranslateY(offset);
        node.setOpacity(0);

        TranslateTransition slide = new TranslateTransition(duration(millis), node);
        slide.setFromY(offset);
        slide.setToY(0);
        slide.setInterpolator(EASE);
        return play(node, new ParallelTransition(node, slide, fadeComponent(node, millis)));
    }

    /** Slides a node out horizontally and fades it away (toast dismissal). */
    public static Animation slideOutX(Node node, boolean towardsLeadingEdge, int millis) {
        Objects.requireNonNull(node, "node");
        TranslateTransition slide = new TranslateTransition(duration(millis), node);
        slide.setToX(Motion.slideOffset(Motion.SLIDE_DISTANCE, towardsLeadingEdge));
        slide.setInterpolator(EASE);

        FadeTransition fade = new FadeTransition(duration(millis), node);
        fade.setToValue(0);
        return play(node, new ParallelTransition(node, slide, fade));
    }

    // ------------------------------------------------------------ scale & pop

    /**
     * A brief overshoot-and-settle. Used for the notification bell badge when a
     * push arrives, and for the submit button on success.
     */
    public static Animation scalePop(Node node) {
        return scalePop(node, Motion.POP_SCALE, Motion.FAST_MS);
    }

    /** @see #scalePop(Node) */
    public static Animation scalePop(Node node, double peak, int millis) {
        Objects.requireNonNull(node, "node");
        double target = Motion.clampScale(peak);
        int half = Math.max(1, duration(millis).toMillis() > 0 ? (int) duration(millis).toMillis() / 2 : 1);

        ScaleTransition up = new ScaleTransition(Duration.millis(half), node);
        up.setToX(target);
        up.setToY(target);
        up.setInterpolator(EASE);

        ScaleTransition down = new ScaleTransition(Duration.millis(half), node);
        down.setToX(1);
        down.setToY(1);
        down.setInterpolator(EASE);

        javafx.animation.SequentialTransition seq = new javafx.animation.SequentialTransition(node, up, down);
        return play(node, seq);
    }

    /** Scales a node in from slightly small while fading — dialog and card entrances. */
    public static Animation scaleIn(Node node, int millis) {
        Objects.requireNonNull(node, "node");
        node.setScaleX(0.96);
        node.setScaleY(0.96);
        node.setOpacity(0);

        ScaleTransition scale = new ScaleTransition(duration(millis), node);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(EASE);
        return play(node, new ParallelTransition(node, scale, fadeComponent(node, millis)));
    }

    // ----------------------------------------------------------- pulse & glow

    /**
     * A repeating breathe. Drives the LIVE chip dot and the countdown timer once
     * it turns red (F6.2) — attention without a distracting flash.
     *
     * @param cycles number of breaths; {@code 0} or less repeats indefinitely
     *               until {@link #stop(Node)}
     */
    public static Animation pulse(Node node, int cycles) {
        Objects.requireNonNull(node, "node");
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.opacityProperty(), 1, EASE),
                        new KeyValue(node.scaleXProperty(), 1, EASE),
                        new KeyValue(node.scaleYProperty(), 1, EASE)),
                new KeyFrame(duration(Motion.SLOW_MS),
                        new KeyValue(node.opacityProperty(), Motion.clampOpacity(0.55), EASE),
                        new KeyValue(node.scaleXProperty(), Motion.clampScale(1.08), EASE),
                        new KeyValue(node.scaleYProperty(), Motion.clampScale(1.08), EASE)));
        timeline.setAutoReverse(true);
        timeline.setCycleCount(cycles > 0 ? cycles * 2 : Animation.INDEFINITE);
        return play(node, timeline);
    }

    /**
     * A one-shot coloured halo. Marks the moment a teacher grants an extension
     * (F7.1) — the timer chip glows green so the change is impossible to miss.
     */
    public static Animation glow(Node node, Color color) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(color, "color");
        DropShadow halo = new DropShadow(0, color);
        halo.setSpread(0.35);
        node.setEffect(halo);

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(halo.radiusProperty(), 0, EASE)),
                new KeyFrame(duration(Motion.SLOW_MS), new KeyValue(halo.radiusProperty(), 22, EASE)),
                new KeyFrame(duration(Motion.SLOW_MS).multiply(2), new KeyValue(halo.radiusProperty(), 0, EASE)));
        timeline.setOnFinished(e -> node.setEffect(null));
        return play(node, timeline);
    }

    // --------------------------------------------------------------- stagger

    /**
     * Plays a fade+rise entrance across a list, each item slightly after the one
     * before. Total wall time is bounded by {@link Motion#STAGGER_CAP_MS}
     * regardless of list length.
     */
    public static void staggerIn(List<? extends Node> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            int delay = Motion.staggerDelay(i);
            node.setOpacity(0);
            node.setTranslateY(Motion.slideOffset(8, false));

            TranslateTransition rise = new TranslateTransition(duration(Motion.BASE_MS), node);
            rise.setToY(0);
            rise.setInterpolator(EASE);

            ParallelTransition entrance =
                    new ParallelTransition(node, rise, fadeComponent(node, Motion.BASE_MS));
            entrance.setDelay(Duration.millis(delay));
            play(node, entrance);
        }
    }

    /**
     * A slow left-to-right sweep for skeleton placeholders (E4.16). JavaFX CSS
     * has no keyframes, so the shimmer is a Java-side opacity loop; it is the one
     * animation exempt from the 250ms rule because it is ambient, not a
     * transition, and it stops the moment real content replaces the skeleton.
     */
    public static Animation shimmer(Node node) {
        Objects.requireNonNull(node, "node");
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 1, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(700), new KeyValue(node.opacityProperty(), 0.45, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1400), new KeyValue(node.opacityProperty(), 1, Interpolator.EASE_BOTH)));
        timeline.setCycleCount(Animation.INDEFINITE);
        return play(node, timeline);
    }

    // ---------------------------------------------------------------- control

    /**
     * Stops whatever HSTS animation is running on a node and clears the transient
     * transforms it may have left behind. Screens call this in {@code onHide} so
     * a cached screen never comes back mid-fade.
     */
    public static void stop(Node node) {
        if (node == null) {
            return;
        }
        Object running = node.getProperties().remove(RUNNING_KEY);
        if (running instanceof Animation animation) {
            animation.stop();
        }
    }

    /** Stops the animation and restores opacity, scale and translation to neutral. */
    public static void reset(Node node) {
        stop(node);
        if (node == null) {
            return;
        }
        node.setOpacity(1);
        node.setScaleX(1);
        node.setScaleY(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setEffect(null);
    }

    /** @return {@code true} when an HSTS animation is currently running on the node. */
    public static boolean isAnimating(Node node) {
        return node != null
                && node.getProperties().get(RUNNING_KEY) instanceof Animation animation
                && animation.getStatus() == Animation.Status.RUNNING;
    }

    // ---------------------------------------------------------------- helpers

    private static Duration duration(int millis) {
        return Duration.millis(Motion.clampMillis(millis));
    }

    private static FadeTransition fadeComponent(Node node, int millis) {
        FadeTransition fade = new FadeTransition(duration(millis), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        return fade;
    }

    /**
     * The interruptibility guarantee: stop the previous animation on this node,
     * remember the new one, and forget it again when it ends.
     */
    private static Animation play(Node node, Animation animation) {
        stop(node);
        node.getProperties().put(RUNNING_KEY, animation);
        animation.statusProperty().addListener((obs, was, is) -> {
            if (is != Animation.Status.RUNNING && node.getProperties().get(RUNNING_KEY) == animation) {
                node.getProperties().remove(RUNNING_KEY);
            }
        });
        animation.play();
        return animation;
    }
}
