package client.events;

import org.greenrobot.eventbus.EventBus;

import java.util.Objects;

/**
 * The client-side publish/subscribe hub (Presentation tier, ADR-007).
 *
 * <p>Wraps greenrobot {@link EventBus} and pairs it with an
 * {@link FxThreadPoster}: <b>every</b> event is delivered through the poster, so
 * subscribers — screens — always run on the JavaFX Application Thread no matter
 * which thread produced the event. That is the single crossing point required by
 * ARCHITECTURE §6; screens therefore never call {@code Platform.runLater}
 * themselves, and tests swap in {@link DirectFxThreadPoster} to run the whole
 * chain synchronously without a toolkit.
 *
 * <p>{@link #getInstance()} is the app-wide holder (Singleton); the public
 * constructor exists so tests — and any future second window — can own an
 * isolated bus instead of sharing global state.
 */
public final class ClientEventBus {

    private static volatile ClientEventBus instance;

    private final EventBus bus;
    private final FxThreadPoster poster;

    public ClientEventBus(EventBus bus, FxThreadPoster poster) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.poster = Objects.requireNonNull(poster, "poster");
    }

    /** @return the application-wide bus, created on first use with the real FX poster. */
    public static ClientEventBus getInstance() {
        ClientEventBus local = instance;
        if (local == null) {
            synchronized (ClientEventBus.class) {
                local = instance;
                if (local == null) {
                    local = new ClientEventBus(newBus(), new PlatformFxThreadPoster());
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * A bus tuned for this app: an event with no subscribers is normal here (a
     * push can arrive while its screen is closed), so it is neither logged as a
     * warning nor re-published as a {@code NoSubscriberEvent}.
     */
    public static EventBus newBus() {
        return EventBus.builder()
                .logNoSubscriberMessages(false)
                .sendNoSubscriberEvent(false)
                .build();
    }

    /** Subscribes an object's {@code @Subscribe} methods. */
    public void register(Object subscriber) {
        bus.register(subscriber);
    }

    /** Unsubscribes an object; safe to call when it was never registered. */
    public void unregister(Object subscriber) {
        bus.unregister(subscriber);
    }

    public boolean isRegistered(Object subscriber) {
        return bus.isRegistered(subscriber);
    }

    /** Publishes an event to subscribers, on the FX thread. */
    public void post(Object event) {
        poster.run(() -> bus.post(event));
    }

    /** @return the FX-thread seam, for the few legacy screens that await futures directly. */
    public FxThreadPoster poster() {
        return poster;
    }

    /** @return the wrapped greenrobot bus (advanced use: sticky events, priorities). */
    public EventBus bus() {
        return bus;
    }
}
