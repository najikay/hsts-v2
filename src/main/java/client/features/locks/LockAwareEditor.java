package client.features.locks;

import client.events.ClientEventBus;
import client.events.ServerPushEvent;
import client.net.RequestDispatcher;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.lock.LockTiming;
import common.protocol.Message;
import common.protocol.Verb;
import org.greenrobot.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Makes any editor screen lock-aware (Presentation tier, E18.3 — F10).
 *
 * <p>A screen <b>composes</b> one of these rather than inheriting from it: an
 * editor already extends {@code AbstractScreen}, and lock behaviour is a
 * collaborator, not an identity. Everything concurrency-related then lives here
 * instead of being copied into five editors with five sets of subtle bugs.
 *
 * <h2>Integration recipe</h2>
 * The whole contract is four calls and one callback. For an editor of entity
 * type {@code "exam-version"}:
 *
 * <pre>{@code
 * // 1. build it once, in the screen's build()
 * this.locks = new LockAwareEditor(dispatcher(), eventBus(),
 *         ScreenManager.getInstance().signedInUser().userId(), new FxHeartbeat(),
 *         "exam version");
 * this.locks.onStateChanged(this::renderLockState);
 *
 * // 2. open when the user starts editing something
 * locks.open(new EntityRef(EntityRef.EXAM_VERSION, versionId));
 *
 * // 3. close on screen hide, on navigation, and when the editor is dismissed
 * locks.close();
 *
 * // 4. render: the snapshot says everything
 * private void renderLockState(EditLockState.Snapshot state) {
 *     form.setDisable(!state.isEditable());
 *     banner.setText(state.bannerText("exam version").orElse(""));
 *     if (state.offersTakeover() && askUserToTakeOver()) {
 *         locks.takeOver();
 *     }
 * }
 * }</pre>
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>Heartbeat while open.</b> A renewal every
 *       {@link LockTiming#HEARTBEAT}, so three have to be lost before the lock
 *       lapses. It starts on a grant and stops the moment the lock is not this
 *       user's, so a read-only screen sends no traffic at all.</li>
 *   <li><b>Release on close, best effort.</b> {@link #close()} sends
 *       {@code LOCK_RELEASE} and does not wait for the answer: a user leaving a
 *       screen must never be held up by the network. If the message never
 *       arrives, the TTL cleans up, and if the client died outright the
 *       server's disconnect hook does (E18.3).</li>
 *   <li><b>Never a silent grab.</b> A lock becoming free raises
 *       {@code TAKEOVER_OFFERED} and stops there. The screen asks the user; only
 *       {@link #takeOver()} acquires. Quietly taking a lock would let a user
 *       start typing over work someone else believes is theirs.</li>
 *   <li><b>Only this entity's pushes.</b> One generic event carries every push
 *       (E1.8), so everything for another entity, or for another verb, is
 *       ignored here rather than by each screen.</li>
 * </ul>
 *
 * <p>FX-free on purpose — the toolkit enters only through the injected
 * {@link Heartbeat} — so every state above is a unit test against
 * {@code FakeClientConnection}.
 */
public final class LockAwareEditor {

    private static final Logger log = LoggerFactory.getLogger(LockAwareEditor.class);

    private final RequestDispatcher dispatcher;
    private final ClientEventBus eventBus;
    private final EditLockState state;
    private final Heartbeat heartbeat;
    private final String entityNoun;
    private final List<Consumer<EditLockState.Snapshot>> listeners = new ArrayList<>();

    private EntityRef entity;
    private boolean subscribed;

    /**
     * @param dispatcher the shared request correlator
     * @param eventBus   the app bus; {@code PUSH_LOCK_CHANGED} arrives on it
     * @param selfUserId this client's user id, from {@code LoginResult}
     * @param heartbeat  the renewal ticker ({@link FxHeartbeat} in the app)
     * @param entityNoun what is being edited, lower case singular ("question"),
     *                   used in every sentence the banner and prompts show
     */
    public LockAwareEditor(RequestDispatcher dispatcher, ClientEventBus eventBus, long selfUserId,
                           Heartbeat heartbeat, String entityNoun) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.entityNoun = Objects.requireNonNull(entityNoun, "entityNoun");
        this.state = new EditLockState(selfUserId);
    }

    // ===================== Screen API ====================================

    /** Subscribes to state changes; the screen re-renders from the snapshot. */
    public void onStateChanged(Consumer<EditLockState.Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Opens {@code target} for editing: subscribes to its pushes and asks for the
     * lock. Opening a different entity releases the previous one first.
     */
    public void open(EntityRef target) {
        Objects.requireNonNull(target, "target");
        if (entity != null && !entity.equals(target)) {
            close();
        }
        entity = target;
        if (!subscribed) {
            eventBus.register(this);
            subscribed = true;
        }
        publish(state.opening());
        acquire();
    }

    /**
     * Closes the editor: stops the heartbeat, releases the lock (best effort) and
     * unsubscribes. Safe to call when nothing is open, so a screen can call it
     * unconditionally from {@code onHide}.
     */
    public void close() {
        heartbeat.stop();
        EntityRef closing = entity;
        entity = null;
        if (subscribed) {
            eventBus.unregister(this);
            subscribed = false;
        }
        if (closing != null) {
            // Fire and forget: leaving a screen must not wait for the network. The
            // TTL and the disconnect hook are the backstops if this never lands.
            dispatcher.send(Verb.LOCK_RELEASE, new LockRequest(closing))
                    .whenComplete((response, failure) -> {
                        if (failure != null) {
                            log.debug("Release of {} was not acknowledged: {}", closing, failure.toString());
                        }
                    });
        }
        publish(state.closed());
    }

    /**
     * Takes the lock after the user said yes to the takeover prompt (E18.3, state
     * c). A no-op unless a takeover is actually on offer, so a stale dialog
     * cannot steal a lock that has since been taken by someone else.
     */
    public void takeOver() {
        if (entity == null || !state.snapshot().offersTakeover()) {
            return;
        }
        publish(state.opening());
        acquire();
    }

    /** The user declined the takeover; the screen stays read-only. */
    public void declineTakeover() {
        publish(state.declineTakeover());
    }

    /** @return the current lock state. */
    public EditLockState.Snapshot state() {
        return state.snapshot();
    }

    /** @return the entity currently open, or {@code null}. */
    public EntityRef entity() {
        return entity;
    }

    /** @return what to call the thing being edited, for the screen's own copy. */
    public String entityNoun() {
        return entityNoun;
    }

    /** @return {@code true} when the user may edit right now. */
    public boolean isEditable() {
        return state.snapshot().isEditable();
    }

    // ===================== Server conversation ===========================

    private void acquire() {
        send(Verb.LOCK_ACQUIRE);
    }

    private void renew() {
        if (entity != null && state.snapshot().isEditable()) {
            send(Verb.LOCK_RENEW);
        }
    }

    private void send(Verb verb) {
        EntityRef target = entity;
        dispatcher.send(verb, new LockRequest(target))
                .whenComplete((response, failure) -> applyAnswer(target, verb, response, failure));
    }

    private void applyAnswer(EntityRef target, Verb verb, Message response, Throwable failure) {
        if (!Objects.equals(target, entity)) {
            // The editor moved on (or closed) while this was in flight.
            return;
        }
        if (failure != null) {
            log.warn("{} for {} failed: {}", verb, target, failure.toString());
            // Cannot prove the lock is ours, so stop claiming it is: the heartbeat
            // stops and the screen offers a takeover instead of silently editing on.
            heartbeat.stop();
            publish(state.applyFailure());
            return;
        }
        if (response.isError() || !(response.getPayload() instanceof LockResponse lock)) {
            log.warn("{} for {} answered {} {}", verb, target,
                    response.getErrorCode(), response.errorMessage());
            heartbeat.stop();
            publish(state.applyFailure());
            return;
        }
        EditLockState.Snapshot snapshot = state.applyResponse(lock);
        if (snapshot.isEditable()) {
            startHeartbeat();
        } else {
            heartbeat.stop();
        }
        publish(snapshot);
    }

    /**
     * Applies {@code PUSH_LOCK_CHANGED} for the open entity (E18.2). Everything
     * else on the bus is somebody else's business.
     */
    @Subscribe
    public void onServerPush(ServerPushEvent event) {
        if (entity == null || event == null || event.verb() != Verb.PUSH_LOCK_CHANGED) {
            return;
        }
        if (!(event.payload() instanceof LockChange change) || !entity.equals(change.entity())) {
            return;
        }
        EditLockState.Snapshot snapshot = state.applyChange(change);
        if (snapshot.isEditable()) {
            startHeartbeat();
        } else {
            // Lost it, or never had it: stop renewing something that is not ours.
            heartbeat.stop();
        }
        publish(snapshot);
    }

    private void startHeartbeat() {
        if (!heartbeat.isRunning()) {
            heartbeat.start(LockTiming.HEARTBEAT, this::renew);
        }
    }

    private void publish(EditLockState.Snapshot snapshot) {
        for (Consumer<EditLockState.Snapshot> listener : List.copyOf(listeners)) {
            listener.accept(snapshot);
        }
    }
}
