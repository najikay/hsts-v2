package client.ui.components;

import client.ui.anim.Animations;
import client.ui.anim.Motion;
import client.ui.components.logic.ToastQueue;
import client.ui.components.logic.ToastSpec;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Top-right overlay showing transient feedback (Presentation tier, E4.14, F11.3).
 *
 * <p>Thin: {@link ToastQueue} decides what is on screen, how many at once and
 * which repeats collapse; this class turns those decisions into cards that slide
 * in from the right, wait out their dwell and slide away.
 *
 * <p>Mounted once by the app shell via {@link #over(Node)} and reached from
 * anywhere through the screen's shell reference, so a screen shows feedback with
 * one call and never builds a popup of its own.
 */
public final class ToastStack extends VBox {

    private final ToastQueue queue;
    private final Map<ToastSpec, Node> cards = new IdentityHashMap<>();

    public ToastStack() {
        this(new ToastQueue());
    }

    public ToastStack(ToastQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
        getStyleClass().add("hsts-toast-stack");
        setAlignment(Pos.TOP_RIGHT);
        setSpacing(8);
        setPickOnBounds(false);
        setMouseTransparent(false);

        queue.onShow(this::renderToast);
        queue.onHide(this::removeToast);
    }

    /** @return the decision layer, for tests and for advanced callers. */
    public ToastQueue queue() {
        return queue;
    }

    /** Shows a success toast. */
    public void success(String title) {
        show(ToastSpec.success(title));
    }

    /** Shows a success toast with detail. */
    public void success(String title, String message) {
        show(ToastSpec.success(title, message));
    }

    /** Shows an error toast (longer dwell — a missed failure becomes a support call). */
    public void error(String title, String message) {
        show(ToastSpec.error(title, message));
    }

    /** Shows an informational toast. */
    public void info(String title, String message) {
        show(ToastSpec.info(title, message));
    }

    /** Submits a toast; consecutive duplicates collapse (see {@link ToastQueue}). */
    public void show(ToastSpec spec) {
        queue.enqueue(spec);
    }

    /** Clears everything on screen and waiting. */
    public void clear() {
        queue.clear();
    }

    /**
     * @return a {@link StackPane} with this stack floating over {@code content},
     *         anchored top-right and transparent to clicks outside its cards
     */
    public StackPane over(Node content) {
        Objects.requireNonNull(content, "content");
        StackPane stack = new StackPane(content, this);
        StackPane.setAlignment(this, Pos.TOP_RIGHT);
        setPickOnBounds(false);
        return stack;
    }

    private void renderToast(ToastSpec spec) {
        Node card = buildCard(spec);
        cards.put(spec, card);
        getChildren().add(card);
        Animations.slideInX(card, false, 24, Motion.SLOW_MS);

        PauseTransition dwell = new PauseTransition(Duration.millis(spec.dwell().toMillis()));
        dwell.setOnFinished(e -> queue.dismiss(spec));
        dwell.play();
    }

    private void removeToast(ToastSpec spec) {
        Node card = cards.remove(spec);
        if (card == null) {
            return;
        }
        Animations.slideOutX(card, false, Motion.FAST_MS)
                .setOnFinished(e -> getChildren().remove(card));
    }

    private Node buildCard(ToastSpec spec) {
        Region rail = new Region();
        rail.getStyleClass().add("toast-rail");

        Label title = new Label(spec.title());
        title.getStyleClass().add("toast-title");
        title.setWrapText(true);

        VBox text = new VBox(2, title);
        if (spec.hasMessage()) {
            Label message = new Label(spec.message());
            message.getStyleClass().add("toast-message");
            message.setWrapText(true);
            text.getChildren().add(message);
        }

        HBox card = new HBox(10, rail, Icons.of(iconFor(spec), Icons.SIZE_DEFAULT, "toast-icon"), text);
        card.getStyleClass().addAll("hsts-toast", spec.variant().styleClass());
        card.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);

        // Click anywhere on a toast to dismiss it early.
        card.setOnMouseClicked(e -> queue.dismiss(spec));
        card.setAccessibleText(spec.title() + ". " + spec.message());
        return card;
    }

    private static String iconFor(ToastSpec spec) {
        return switch (spec.variant()) {
            case SUCCESS -> Icons.CHECK;
            case ERROR -> Icons.ERROR;
            case WARN -> Icons.WARNING;
            case INFO -> Icons.INFO;
        };
    }
}
