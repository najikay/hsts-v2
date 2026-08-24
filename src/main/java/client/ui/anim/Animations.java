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
 *
 * <h2>Reduced motion (UI wave 2)</h2>
 *
 * <p>Every method below asks {@link Motion} for its duration and its travel
 * rather than using the numbers it was handed, so
 * {@link Motion#setReducedMotion(boolean)} collapses the whole app to an 80ms
 * fade in one place. That is the only way it could work: a switch honoured by
 * the methods that remembered to check it is a switch that half the app ignores.
 * Travel becomes zero, staggers become zero, and the two ambient loops
 * ({@link #breathe}, {@link #livePulse}) do not start at all.
 */
public final class Animations {

    /** Property key under which a node's currently-running HSTS animation is stashed. */
    private static final Object RUNNING_KEY = new Object();

    /**
     * A second slot, for the hover lift only.
     *
     * <p>Separate from {@link #RUNNING_KEY} because a hover and an entrance are
     * not competing for the same job: an entrance owns opacity and must not be
     * cancelled by a pointer crossing the card while it plays.
     */
    private static final Object HOVER_KEY = new Object();

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
        double offset = Motion.slideOffset(Motion.effectiveDistance(distance), fromLeadingEdge);
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
        double offset = Motion.slideOffset(Motion.effectiveDistance(distance), fromTop);
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
        slide.setToX(Motion.slideOffset(Motion.effectiveDistance(Motion.SLIDE_DISTANCE),
                towardsLeadingEdge));
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
        return scaleIn(node, 0.96, millis);
    }

    /**
     * Scales a node in from a given starting size while fading it in.
     *
     * <p>Wave 2's dialog entrance starts at {@link Motion#DIALOG_FROM_SCALE}
     * rather than the older 0.96: a dialog is already the thing you asked for,
     * so it should settle into place rather than arrive from somewhere.
     *
     * @param from the starting scale; under reduced motion this is ignored and
     *             the node simply fades at full size
     */
    public static Animation scaleIn(Node node, double from, int millis) {
        Objects.requireNonNull(node, "node");
        double start = Motion.isReducedMotion() ? 1 : Motion.clampScale(from);
        node.setScaleX(start);
        node.setScaleY(start);
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
        if (!Motion.ambientLoopsAllowed()) {
            // A loop that never ends is the animation a reader who asked for less
            // motion would notice most. Neutralise the node and hand back a
            // timeline that is never played, so callers need no null check.
            reset(node);
            return new Timeline();
        }
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
            entrance(nodes.get(i), 8, Motion.BASE_MS, Motion.effectiveDelay(Motion.staggerDelay(i)),
                    EASE);
        }
    }

    /**
     * One item's delayed fade-and-rise. Shared by {@link #staggerIn} and
     * {@link #staggerCards} so the two differ only in their arithmetic.
     */
    private static void entrance(Node node, double distance, int millis, int delayMs,
                                 Interpolator easing) {
        double offset = Motion.slideOffset(Motion.effectiveDistance(distance), false);
        node.setOpacity(0);
        node.setTranslateY(offset);

        TranslateTransition rise = new TranslateTransition(duration(millis), node);
        rise.setFromY(offset);
        rise.setToY(0);
        rise.setInterpolator(easing);

        ParallelTransition together =
                new ParallelTransition(node, rise, fadeComponent(node, millis));
        together.setDelay(Duration.millis(delayMs));
        play(node, together);
    }

    /**
     * The wave-2 entrance: a fade with a short rise, incoming node only
     * (UI wave 2, motion spec item 1).
     *
     * <p>Incoming <i>only</i> is the whole design. Cross-fading two screens
     * means the app is briefly showing neither, and a rise on the outgoing one
     * would move content a user may still be reading. The screen being left
     * simply stops existing; the screen arriving is the one that animates.
     *
     * @param distance how far it rises, in px
     */
    public static Animation riseIn(Node node, double distance, int millis) {
        return slideInY(node, false, distance, millis);
    }

    /** The house route transition: {@link Motion#RISE_DISTANCE} over {@link Motion#ROUTE_MS}. */
    public static Animation riseIn(Node node) {
        return riseIn(node, Motion.RISE_DISTANCE, Motion.ROUTE_MS);
    }

    /**
     * The card hover lift (UI wave 2): the node rises 2px over 150ms and
     * settles back when the pointer leaves.
     *
     * <p>In Java rather than in the stylesheet because <b>JavaFX 21 CSS has no
     * transitions</b>. A {@code :hover} rule changes instantly, so a lift
     * written there would be a jump — and a jump is exactly the cheap-feeling
     * motion this wave exists to replace. Routing it through here also means it
     * collapses with everything else under reduced motion, where
     * {@link Motion#effectiveDistance} takes the travel to zero.
     *
     * <p>Installs listeners; call it once per node, at build time.
     */
    public static void liftOnHover(Node node) {
        Objects.requireNonNull(node, "node");
        node.hoverProperty().addListener((obs, was, hovered) -> {
            // Deliberately NOT play(): that slot holds the node's entrance, and
            // a pointer crossing a card 100ms after the dashboard opened would
            // otherwise cancel a fade-in half way and leave the card invisible.
            // The hover lift gets a slot of its own.
            Object previous = node.getProperties().get(HOVER_KEY);
            if (previous instanceof Animation running) {
                running.stop();
            }
            TranslateTransition lift =
                    new TranslateTransition(duration(Motion.CARD_HOVER_MS), node);
            lift.setToY(hovered ? -Motion.effectiveDistance(2) : 0);
            lift.setInterpolator(EASE);
            node.getProperties().put(HOVER_KEY, lift);
            lift.play();
        });
    }

    /**
     * The dashboard cards' entrance (UI wave 2): a 30ms stagger of fade plus a
     * 6px rise, at most {@link Motion#CARD_STAGGER_MAX} of them staggered.
     *
     * <p>Separate from {@link #staggerIn} rather than a parameter on it,
     * because they answer to different rules: a list stagger is capped in
     * <i>time</i> so a long list still arrives at once, and a card stagger is
     * capped in <i>count</i> because a dashboard row that needed a time cap
     * would already have too many cards on it.
     */
    public static void staggerCards(List<? extends Node> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        for (int i = 0; i < nodes.size(); i++) {
            entrance(nodes.get(i), 6, Motion.ROUTE_MS, Motion.cardStaggerDelay(i), EASE);
        }
    }

    /**
     * A table's first-load row entrance (UI wave 2): a 20ms linear fade stagger,
     * no travel.
     *
     * <p>Linear rather than eased, and no rise: rows are a texture, not a set of
     * objects arriving, and easing forty of them makes the table look like it is
     * struggling. <b>First load only</b> — the caller owns that rule, because
     * only the caller knows whether this is a refresh or a resort, and rows that
     * re-animate every time a column header is clicked are the exact defect this
     * treatment is worth avoiding.
     */
    public static void staggerRows(List<? extends Node> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.setOpacity(0);
            FadeTransition fade = new FadeTransition(duration(Motion.ROUTE_MS), node);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.setInterpolator(Interpolator.LINEAR);
            fade.setDelay(Duration.millis(Motion.rowStaggerDelay(i)));
            play(node, fade);
        }
    }

    /**
     * The one ambient breathing loop: an empty state's icon, scaling 1.0 to 1.04
     * and back over 2.4s (UI wave 2).
     *
     * <p>Exempt from the 250ms budget on the same terms {@link #shimmer} is: it
     * is ambience rather than a transition, nothing is waiting on it, and it
     * stops the moment the empty state is replaced by content. It does not run
     * at all under reduced motion.
     */
    public static Animation breathe(Node node) {
        Objects.requireNonNull(node, "node");
        if (!Motion.ambientLoopsAllowed()) {
            reset(node);
            return new Timeline();
        }
        double peak = Motion.clampScale(Motion.BREATHE_SCALE);
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.scaleXProperty(), 1, Interpolator.EASE_BOTH),
                        new KeyValue(node.scaleYProperty(), 1, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(Motion.BREATHE_MS / 2.0),
                        new KeyValue(node.scaleXProperty(), peak, Interpolator.EASE_BOTH),
                        new KeyValue(node.scaleYProperty(), peak, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(Motion.BREATHE_MS),
                        new KeyValue(node.scaleXProperty(), 1, Interpolator.EASE_BOTH),
                        new KeyValue(node.scaleYProperty(), 1, Interpolator.EASE_BOTH)));
        timeline.setCycleCount(Animation.INDEFINITE);
        return play(node, timeline);
    }

    /**
     * The live-sitting halo: a ring that expands out of the dot and fades,
     * looping every 1.6s (UI wave 2).
     *
     * <p>The second and last ambient loop, and it is allowed <b>only while
     * something is genuinely live</b>. Callers stop it with {@link #reset} the
     * moment the sitting closes; a halo around a card describing a sitting that
     * finished an hour ago is a lie told in motion.
     *
     * <p>A scaling node rather than a {@link DropShadow}, which is what
     * {@link #glow} uses, and the difference is a house rule rather than a
     * preference: a drop shadow needs a {@link Color} in Java, and every colour
     * in this app comes from a token in the stylesheet. The halo is therefore
     * its own node, painted by CSS, and this method only moves it — so the halo
     * is the right green in both palettes and all five accents without knowing
     * any of them.
     *
     * @param halo the ring node, sized and coloured by the stylesheet
     */
    public static Animation livePulse(Node halo) {
        Objects.requireNonNull(halo, "halo");
        if (!Motion.ambientLoopsAllowed()) {
            reset(halo);
            halo.setOpacity(0);
            return new Timeline();
        }
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(halo.scaleXProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(halo.scaleYProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(halo.opacityProperty(), Motion.clampOpacity(0.35),
                                Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(Motion.LIVE_PULSE_MS),
                        new KeyValue(halo.scaleXProperty(), 2.4, Interpolator.EASE_OUT),
                        new KeyValue(halo.scaleYProperty(), 2.4, Interpolator.EASE_OUT),
                        new KeyValue(halo.opacityProperty(), 0, Interpolator.EASE_OUT)));
        timeline.setCycleCount(Animation.INDEFINITE);
        return play(halo, timeline);
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
        return Duration.millis(Motion.effectiveMillis(millis));
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
