package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.Objects;

/**
 * A light dismissible panel anchored under the control that opened it
 * (Presentation tier, UI wave 1 — F-6).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The notifications list was already mounted into the shell's popover layer,
 * but that layer is a {@link StackPane} that fills the content area, and a
 * StackPane centres what it is given. The result read as a centred modal that
 * happened to have no backdrop: it did not point at the bell, clicking away did
 * not dismiss it, and ESC did nothing — so the only way out was to find the bell
 * again, which is why it felt like the bell needed two clicks.
 *
 * <p>All three of those are one missing idea rather than three bugs: <b>a
 * popover belongs to the control that opened it</b>. It appears next to that
 * control, and any gesture that means "I am done here" — clicking elsewhere,
 * pressing ESC, clicking the control again — closes it. That idea is this class,
 * so the next navbar menu gets it for free instead of re-deriving it.
 *
 * <h2>Light dismissal</h2>
 *
 * <p>Dismissal is a pair of <b>event filters on the scene</b>, installed while
 * open and removed on close. Filters, not handlers: they see the press on its
 * way <i>down</i> to whatever was clicked, so the popover is gone before that
 * click does anything, which is what stops a click-away from both dismissing the
 * popover and activating a button underneath by surprise.
 *
 * <p>The anchor's own subtree is excluded from the outside test. Without that
 * exclusion, clicking the bell would close the popover in the filter and then
 * the bell's own action would toggle it straight back open — a control that can
 * never be switched off.
 *
 * <h2>Anchoring</h2>
 *
 * <p>Alignment is top-right within the host, offset by a right margin measured
 * from the anchor at open time, so the panel's right edge lines up with the
 * anchor's. A layout margin rather than {@code translateX}, because the entrance
 * animation owns the translate properties and the two would fight.
 */
public final class Popover {

    /** Entrance travel: shorter than a screen transition, this is a small thing. */
    private static final double ENTRANCE_TRAVEL = 8;

    private final StackPane host;
    private final Region content;
    private final Node anchor;

    private final EventHandler<MouseEvent> outsidePress = this::onScenePress;
    private final EventHandler<KeyEvent> escape = this::onSceneKey;

    private Scene watched;

    /**
     * @param host    the layer the panel is mounted into ({@code AppShell.popovers()})
     * @param content the panel itself
     * @param anchor  the control that owns it; may be {@code null}, in which case
     *                the panel sits in the host's top-right corner and every click
     *                outside it dismisses
     */
    public Popover(StackPane host, Region content, Node anchor) {
        this.host = Objects.requireNonNull(host, "host");
        this.content = Objects.requireNonNull(content, "content");
        this.anchor = anchor;
    }

    /** @return {@code true} while the panel is on screen. */
    public boolean isOpen() {
        return host.getChildren().contains(content);
    }

    /** Opens if closed, closes if open. This is what an anchor button is wired to. */
    public void toggle() {
        if (isOpen()) {
            close();
        } else {
            open();
        }
    }

    /** Mounts the panel, anchors it, plays the entrance and arms light dismissal. */
    public void open() {
        if (!isOpen()) {
            host.getChildren().add(content);
        }
        StackPane.setAlignment(content, Pos.TOP_RIGHT);
        StackPane.setMargin(content, new Insets(0, rightMargin(), 0, 0));
        armDismissal();
        // Quick fade plus a short drop from above: the panel reads as coming out
        // of the control rather than appearing over the page.
        Animations.slideInY(content, true, ENTRANCE_TRAVEL, Motion.FAST_MS);
    }

    /** Unmounts the panel and disarms light dismissal. Safe when already closed. */
    public void close() {
        disarmDismissal();
        Animations.stop(content);
        host.getChildren().remove(content);
    }

    // ===================== Anchoring =====================================

    /**
     * How far the panel's right edge sits from the host's right edge, so the two
     * right edges — panel and anchor — line up.
     *
     * <p>Falls back to the host's own padding when the anchor is not laid out
     * yet (the very first open of a freshly built shell): a panel in the corner
     * is a small imperfection, an exception during layout is not.
     */
    private double rightMargin() {
        if (anchor == null || anchor.getScene() == null) {
            return 0;
        }
        Bounds anchorInScene = anchor.localToScene(anchor.getBoundsInLocal());
        Bounds hostInScene = host.localToScene(host.getBoundsInLocal());
        if (anchorInScene == null || hostInScene == null) {
            return 0;
        }
        // The host's own right padding already pushes the panel inward, so it is
        // subtracted rather than added to: the two together would double the gap
        // and the panel would stop lining up with the bell.
        double margin = hostInScene.getMaxX() - anchorInScene.getMaxX()
                - host.getInsets().getRight();
        // Never negative: an anchor further right than the host would push the
        // panel off screen, and the corner is the honest fallback.
        return Math.max(margin, 0);
    }

    // ===================== Light dismissal ===============================

    private void armDismissal() {
        Scene scene = content.getScene();
        if (scene == null || scene == watched) {
            return;
        }
        disarmDismissal();
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsidePress);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, escape);
        watched = scene;
    }

    private void disarmDismissal() {
        if (watched == null) {
            return;
        }
        watched.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsidePress);
        watched.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
        watched = null;
    }

    private void onScenePress(MouseEvent event) {
        if (!(event.getTarget() instanceof Node target)) {
            close();
            return;
        }
        if (isWithin(target, content) || isWithin(target, anchor)) {
            return;
        }
        close();
    }

    private void onSceneKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            close();
            // Consumed so ESC does not also cancel whatever is behind the popover;
            // the user meant "close this", singular.
            event.consume();
        }
    }

    /** @return {@code true} when {@code node} is {@code ancestor} or sits inside it. */
    private static boolean isWithin(Node node, Node ancestor) {
        if (ancestor == null) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }
}
