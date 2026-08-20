package client.features.bot;

import client.ui.anim.Animations;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One message bubble (Presentation tier, E16.13 — F12.5).
 *
 * <p>A thin FX node: it takes a {@link ChatEntry} and draws it. Everything that
 * decides <em>what</em> is drawn lives in {@link BotChatModel}, which is why this
 * class is on the coverage exclusion list and that one is not.
 *
 * <h2>The entrance, and what it stands in for</h2>
 *
 * <p>The bot's answer fades and slides in over 180ms rather than appearing at
 * once. That is our answer to F12.5's "streaming-style incremental display": our
 * own wire has no token channel — the provider call is not streamed, because
 * reassembling a token stream across the OCSF envelope would be real complexity
 * for a cosmetic gain — so instead of pretending to type, the answer arrives as a
 * whole with a movement that reads as arrival. The perceived difference is a
 * fraction of a second; the difference in moving parts is a protocol.
 *
 * <p>A pending bubble (her question, before the server has confirmed it) is drawn
 * muted, so the optimistic echo is visibly provisional rather than a claim.
 */
public final class ChatBubble extends HBox {

    /** Style class on the row; the CSS uses it to align the bubble. */
    public static final String STYLE_ROW = "chat-row";

    /** Style class on the bubble itself. */
    public static final String STYLE_BUBBLE = "chat-bubble";

    /** Added to a student's bubble. */
    public static final String STYLE_STUDENT = "from-student";

    /** Added to the bot's bubble. */
    public static final String STYLE_BOT = "from-bot";

    /** Added while a message is on screen but not yet acknowledged. */
    public static final String STYLE_PENDING = "pending";

    private final Label text = new Label();

    /**
     * @param entry  what to draw
     * @param animate {@code true} to play the entrance; {@code false} when
     *                re-rendering a conversation that was already on screen
     */
    public ChatBubble(ChatEntry entry, boolean animate) {
        getStyleClass().add(STYLE_ROW);
        setSpacing(8);
        setFillHeight(true);

        text.setText(entry.text());
        text.setWrapText(true);
        text.getStyleClass().addAll(STYLE_BUBBLE,
                entry.isFromStudent() ? STYLE_STUDENT : STYLE_BOT);
        if (entry.pending()) {
            text.getStyleClass().add(STYLE_PENDING);
        }
        // Bubbles stop well short of the full width: a line of text that runs the
        // whole way across is hard to read and stops looking like a conversation.
        text.maxWidthProperty().bind(widthProperty().multiply(0.72));

        VBox column = new VBox(text);
        column.setFillWidth(false);
        column.setAlignment(entry.isFromStudent() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        HBox.setHgrow(column, Priority.ALWAYS);
        setAlignment(entry.isFromStudent() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        getChildren().add(column);

        if (animate) {
            // Fade plus a short rise: 180ms, inside the project's 250ms ceiling,
            // and interruptible like every other animation here.
            Animations.fadeIn(this, 180).play();
            Animations.slideInY(this, false, 10, 180).play();
        }
    }

    /** @return the label carrying the message text; the TestFX assertions look it up. */
    public Label textNode() {
        return text;
    }

    /** @return a spacer that pushes a bubble to one side when a row needs one. */
    static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
