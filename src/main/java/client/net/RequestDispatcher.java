package client.net;

import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Correlates client requests with server responses (Presentation tier, E1.5).
 *
 * <p>This is the only place in the client that knows a {@code requestId} exists.
 * A screen calls {@link #send(Verb, Object)} and gets a
 * {@link CompletableFuture} that completes when the matching response arrives —
 * no screen ever touches a socket, and no screen blocks a thread waiting.
 *
 * <p>Inbound traffic all arrives at {@link #dispatchIncoming(Message)} on OCSF's
 * background read thread and is routed three ways:
 * <ul>
 *   <li>a response whose {@code requestId} matches a pending future → completes it;</li>
 *   <li>{@link Status#PUSH} → handed to the registered {@link PushListener};</li>
 *   <li>anything else (unknown id, late answer to a timed-out request, garbage)
 *       → logged and dropped. Never an exception: this method runs on the read
 *       thread, and throwing there would tear the connection down.</li>
 * </ul>
 *
 * <p>Threading: the pending map is a {@link ConcurrentHashMap} and every removal
 * is atomic, so a response and a timeout racing for the same request can only
 * ever complete it once. Futures complete on whichever thread delivered the
 * outcome — the FX-thread hop belongs to {@code client.events.FxThreadPoster}
 * (ARCHITECTURE §6: exactly one crossing point).
 */
public class RequestDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RequestDispatcher.class);

    /** Default response window; generous enough for a LAN + a cold DB page. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Receives server-initiated {@link Status#PUSH} messages. */
    @FunctionalInterface
    public interface PushListener {
        void onPush(Message push);
    }

    /** Seam for the delayed timeout trigger — swapped for a manual one in tests. */
    @FunctionalInterface
    public interface TimeoutScheduler {
        void schedule(Runnable task, Duration delay);
    }

    private final IClientConnection connection;
    private final Duration defaultTimeout;
    private final TimeoutScheduler scheduler;
    private final Map<String, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

    private volatile PushListener pushListener;

    public RequestDispatcher(IClientConnection connection) {
        this(connection, DEFAULT_TIMEOUT);
    }

    public RequestDispatcher(IClientConnection connection, Duration defaultTimeout) {
        this(connection, defaultTimeout, RequestDispatcher::delayOnCommonPool);
    }

    /** Visible for testing: lets a test fire timeouts on demand instead of waiting. */
    RequestDispatcher(IClientConnection connection, Duration defaultTimeout, TimeoutScheduler scheduler) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Registers the sink for push messages; {@code null} clears it (pushes are then dropped). */
    public void setPushListener(PushListener listener) {
        this.pushListener = listener;
    }

    // ===================== Outbound ======================================

    /** Sends {@code verb} with the default timeout. */
    public CompletableFuture<Message> send(Verb verb, Object payload) {
        return send(verb, payload, defaultTimeout);
    }

    /**
     * Builds a request, registers its future, writes it to the socket and arms
     * the timeout.
     *
     * @return a future completed with the correlated response, or completed
     *         exceptionally with a {@link RequestTimeoutException}
     *         ({@link common.protocol.ErrorCode#TIMEOUT}) or with the
     *         {@code IOException} that stopped the send
     */
    public CompletableFuture<Message> send(Verb verb, Object payload, Duration timeout) {
        Message request = Message.request(verb, payload);
        String id = request.getRequestId();
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            connection.send(request);
        } catch (Exception e) {
            // Nothing will ever answer: fail now rather than after the timeout.
            pending.remove(id);
            log.warn("Send failed for {} (requestId={}): {}", verb, id, e.toString());
            future.completeExceptionally(e);
            return future;
        }

        log.debug("→ {} (requestId={})", verb, id);
        scheduler.schedule(() -> timeOut(verb, id, timeout), timeout);
        return future;
    }

    private void timeOut(Verb verb, String requestId, Duration waited) {
        CompletableFuture<Message> future = pending.remove(requestId);
        if (future == null) {
            return; // already answered — the common case
        }
        log.warn("Timeout after {} ms waiting for {} (requestId={})", waited.toMillis(), verb, requestId);
        future.completeExceptionally(new RequestTimeoutException(verb, requestId, waited));
    }

    // ===================== Inbound =======================================

    /**
     * Routes one inbound message. Called on OCSF's read thread; never throws.
     */
    public void dispatchIncoming(Message message) {
        if (message == null) {
            log.warn("Dropping null message from server");
            return;
        }
        if (message.getStatus() == Status.PUSH) {
            deliverPush(message);
            return;
        }

        String id = message.getRequestId();
        CompletableFuture<Message> future = id == null ? null : pending.remove(id);
        if (future == null) {
            // A late answer to a timed-out request, a duplicate, or a response to
            // something we never sent. All harmless — say so and move on.
            log.warn("Dropping unmatched response {} (requestId={})", message.getVerb(), id);
            return;
        }
        log.debug("← {} {} (requestId={})", message.getStatus(), message.getVerb(), id);
        future.complete(message);
    }

    private void deliverPush(Message push) {
        PushListener listener = this.pushListener;
        if (listener == null) {
            log.warn("Dropping push {} — no listener registered", push.getVerb());
            return;
        }
        try {
            listener.onPush(push);
        } catch (RuntimeException e) {
            // A broken subscriber must not take the read thread (and the socket) down.
            log.error("Push listener threw on {}", push.getVerb(), e);
        }
    }

    // ===================== Lifecycle =====================================

    /**
     * Fails every in-flight request — called when the socket drops so screens
     * get an error instead of a future that can never complete.
     *
     * @return how many pending requests were failed
     */
    public int failAllPending(Throwable cause) {
        List<CompletableFuture<Message>> victims = new ArrayList<>();
        for (String id : List.copyOf(pending.keySet())) {
            CompletableFuture<Message> future = pending.remove(id);
            if (future != null) {
                victims.add(future);
            }
        }
        victims.forEach(future -> future.completeExceptionally(cause));
        if (!victims.isEmpty()) {
            log.warn("Failed {} in-flight request(s): {}", victims.size(), cause.toString());
        }
        return victims.size();
    }

    /** @return number of requests currently awaiting a response (diagnostics/tests). */
    public int pendingCount() {
        return pending.size();
    }

    /** Default timeout trigger: the JDK's shared delayed executor, no owned threads. */
    private static void delayOnCommonPool(Runnable task, Duration delay) {
        CompletableFuture.runAsync(task,
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    }
}
