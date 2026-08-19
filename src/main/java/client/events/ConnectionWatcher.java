package client.events;

import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Turns a {@link ConnectionLostEvent} into "raise the reconnect banner"
 * (Presentation tier, E4.6 wired up in E5.7).
 *
 * <p>It is a subscriber and nothing else: the action is injected as a
 * {@link Consumer}, so the class carries no JavaFX and the wiring — "a dropped
 * socket while signed in must reach the shell" — is unit-testable by posting an
 * event on a bus.
 *
 * <p>Public, with a public {@code @Subscribe} method, because greenrobot's
 * subscriber index requires both. It is registered when the shell is built and
 * unregistered on logout, so exactly one banner exists at a time and no detached
 * shell keeps listening (the leak {@code ScreenLifecycle} exists to prevent).
 */
public final class ConnectionWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConnectionWatcher.class);

    private final Consumer<ConnectionLostEvent> onLost;

    /** @param onLost what to do when the connection drops (show the banner) */
    public ConnectionWatcher(Consumer<ConnectionLostEvent> onLost) {
        this.onLost = Objects.requireNonNull(onLost, "onLost");
    }

    /** Bus entry point; already on the FX thread ({@link ClientEventBus}). */
    @Subscribe
    public void onConnectionLost(ConnectionLostEvent event) {
        if (event == null) {
            return;
        }
        log.warn("Connection to {} lost: {}", event.serverLabel(), event.detail());
        onLost.accept(event);
    }
}
