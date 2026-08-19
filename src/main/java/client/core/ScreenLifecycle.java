package client.core;

import client.events.ClientEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Drives the show/hide lifecycle of screens and their EventBus membership
 * (Presentation tier, E4.3 — the Template Method's fixed steps).
 *
 * <p>The rule it enforces is the one v1 kept breaking: <b>a screen is subscribed
 * to the event bus exactly while it is visible</b>. Screens that registered in a
 * constructor and never unregistered stayed alive as bus subscribers after
 * navigation, so a push arriving later updated a detached node graph (and leaked
 * the whole screen). Here, showing registers and hiding unregisters — screens
 * never call the bus themselves, so they cannot get it wrong.
 *
 * <p>Both transitions are idempotent: showing the already-current screen, or
 * hiding when nothing is shown, is a no-op rather than a double-register or an
 * exception.
 *
 * <p>FX-free on purpose: swapping the actual node is {@code ScreenManager}'s job.
 */
public final class ScreenLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScreenLifecycle.class);

    private final ClientEventBus eventBus;

    private Screen current;

    public ScreenLifecycle(ClientEventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /**
     * Hides whatever is showing, then shows {@code screen} with {@code params}.
     *
     * <p>Re-showing the current screen instance is treated as a revisit: it is
     * <i>not</i> unregistered and re-registered, but {@link Screen#onShow} fires
     * again so the screen refreshes for the new parameters.
     */
    public void show(Screen screen, NavParams params) {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(params, "params");

        if (current == screen) {
            log.debug("re-showing current screen {}", name(screen));
            screen.onShow(params);
            return;
        }
        hideCurrent();
        current = screen;
        register(screen);
        screen.onShow(params);
    }

    /** Hides and unregisters the current screen, if any. */
    public void hideCurrent() {
        Screen leaving = current;
        if (leaving == null) {
            return;
        }
        current = null;
        unregister(leaving);
        leaving.onHide();
    }

    /** @return the screen currently shown, or {@code null}. */
    public Screen current() {
        return current;
    }

    /** @return {@code true} when {@code screen} is the one currently shown. */
    public boolean isShowing(Screen screen) {
        return current != null && current == screen;
    }

    private void register(Screen screen) {
        if (screen.listensToEvents() && !eventBus.isRegistered(screen)) {
            eventBus.register(screen);
        }
    }

    private void unregister(Screen screen) {
        if (eventBus.isRegistered(screen)) {
            eventBus.unregister(screen);
        }
    }

    private static String name(Screen screen) {
        return screen.getClass().getSimpleName();
    }
}
