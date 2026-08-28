package client.features.notify;

import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.notify.MarkReadRequest;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationsGetRequest;
import common.dto.notify.NotificationsPage;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The bell's conversation with the server (Presentation tier, E17.4/E17.5).
 *
 * <p>Everything that is not a node: fetching the list, marking rows read, and
 * turning {@code PUSH_NOTIFICATION} into model updates. It speaks only to
 * {@link RequestDispatcher} and {@link ClientEventBus}, so the whole of it is
 * unit-tested against {@code FakeClientConnection} without a JavaFX toolkit —
 * the pattern every screen in this project follows (ARCHITECTURE §6).
 *
 * <p>Three behaviours worth naming:
 *
 * <ul>
 *   <li><b>the model is updated from the server's answer, not optimistically.</b>
 *       Both notification verbs answer with a full {@link NotificationsPage}, so
 *       marking one row read refreshes the badge and the list in the same round
 *       trip. There is no window in which the badge and the rows disagree, and
 *       no refresh button anywhere (NFR-18).</li>
 *   <li><b>a push updates the list whether or not the panel is open.</b> The
 *       model is the state; the panel is a view of it. That is what makes "a
 *       push arrives while the panel is open" (E17.6) work with no special
 *       case.</li>
 *   <li><b>a failed fetch is not an exception a screen has to catch.</b> The
 *       futures returned here always complete normally; a failure leaves the
 *       model untouched and is logged, because a bell that could throw would
 *       take a dashboard down with it.</li>
 * </ul>
 */
public final class NotificationsSession {

    private static final Logger log = LoggerFactory.getLogger(NotificationsSession.class);

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    private final NotificationsModel model;
    private final List<Consumer<NotificationDto>> pushListeners = new ArrayList<>();

    private boolean started;

    /**
     * @param dispatcher the shared request correlator
     * @param eventBus   the app bus; pushes arrive on it already on the FX thread
     * @param model      the state every view of the bell reads
     */
    public NotificationsSession(RequestDispatcher dispatcher, ClientEventBus eventBus,
                                NotificationsModel model) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.model = Objects.requireNonNull(model, "model");
    }

    /** @return the state this session maintains. */
    public NotificationsModel model() {
        return model;
    }

    // ===================== Lifecycle =====================================

    /**
     * Subscribes to pushes and seeds the badge from the sign-in answer.
     *
     * <p>The count comes from {@code LoginResult} rather than from a fetch so the
     * very first frame the user sees already has the right badge; the list itself
     * is fetched lazily, when the panel is first opened.
     *
     * @param unreadFromLogin {@code LoginResult.unreadNotifications()}
     */
    public void start(int unreadFromLogin) {
        if (!started) {
            eventBus.register(this);
            started = true;
        }
        model.setUnreadCount(unreadFromLogin);
    }

    /** Unsubscribes and empties the model. Called on sign-out. */
    public void stop() {
        if (started) {
            eventBus.unregister(this);
            started = false;
        }
        model.clear();
    }

    /** @return {@code true} while this session is subscribed to the bus. */
    public boolean isStarted() {
        return started;
    }

    /** Subscribes to "a notification just arrived live" — the shell raises a toast on it. */
    public void onPushed(Consumer<NotificationDto> listener) {
        pushListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ===================== Requests ======================================

    /**
     * Fetches the newest notifications and the authoritative unread count.
     *
     * @return a future that completes when the model has been updated, or when
     *         the attempt failed (the model is then unchanged)
     */
    public CompletableFuture<Void> refresh() {
        return send(Verb.NOTIFICATIONS_GET, NotificationsGetRequest.defaults());
    }

    /**
     * Marks one notification read.
     *
     * <p>The id is the server's own; sending somebody else's changes nothing and
     * still answers a normal page, which is exactly what the server-side test in
     * E17.6 pins down.
     */
    public CompletableFuture<Void> markRead(long notificationId) {
        return send(Verb.NOTIFICATIONS_MARK_READ, MarkReadRequest.one(notificationId));
    }

    /** Marks every unread notification of this user read (the panel's one button). */
    public CompletableFuture<Void> markAllRead() {
        return send(Verb.NOTIFICATIONS_MARK_READ, MarkReadRequest.markAll());
    }

    // ===================== Pushes ========================================

    /**
     * Applies a {@code PUSH_NOTIFICATION} (F11.1).
     *
     * <p>Other push verbs pass through untouched: one generic event type carries
     * every push (E1.8), so every subscriber has to ignore what is not its own.
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (event == null || event.verb() != Verb.PUSH_NOTIFICATION) {
            return;
        }
        if (!(event.payload() instanceof NotificationDto notification)) {
            log.warn("Ignoring PUSH_NOTIFICATION carrying {}",
                    event.payload() == null ? "null" : event.payload().getClass().getName());
            return;
        }
        if (!model.receive(notification)) {
            // Already listed: the fetch that included it landed first. Nothing new
            // to show, and raising a toast would show it twice.
            log.debug("Dropping duplicate push for notification {}", notification.id());
            return;
        }
        log.debug("Notification {} arrived live", notification.id());
        for (Consumer<NotificationDto> listener : List.copyOf(pushListeners)) {
            listener.accept(notification);
        }
    }

    // ===================== Internals =====================================

    /**
     * Sends a notifications verb and folds the answer into the model.
     *
     * <p>Both verbs answer with a {@link NotificationsPage}, so there is exactly
     * one response path — which is why {@code markRead} needs no logic of its own
     * and cannot drift out of step with {@code refresh}.
     *
     * <p><b>Applied through the poster (M-1/M-5, 2026-08-28 — P-14's class).</b> The
     * answer arrives on OCSF's read thread and {@code model.apply} repaints the open
     * panel. Unposted, that repaint died off the FX thread inside a future both
     * callers discard, which is why the list stayed empty at sign-in and mark-as-read
     * looked dead while the push path — already posted by the bus — worked perfectly.
     * Holding the bus was not the same as using its poster; the response path has to
     * post explicitly, exactly as the exam sessions now do.
     */
    private CompletableFuture<Void> send(Verb verb, Object payload) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        dispatcher.send(verb, payload).whenComplete((response, failure) ->
                eventBus.poster().run(() -> {
                    apply(verb, response, failure);
                    settled.complete(null);
                }));
        return settled;
    }

    private void apply(Verb verb, Message response, Throwable failure) {
        if (failure != null) {
            // A bell that cannot reach the server keeps the count it had. Blanking it
            // would claim there is nothing waiting, which is a different lie.
            log.warn("{} failed: {}", verb, failure.toString());
            return;
        }
        if (response.isError()) {
            log.warn("{} refused: {} {}", verb, response.getErrorCode(), response.errorMessage());
            return;
        }
        if (!(response.getPayload() instanceof NotificationsPage page)) {
            log.warn("{} answered with an unexpected payload", verb);
            return;
        }
        model.apply(page);
    }
}
