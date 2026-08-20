package server.features.locks;

import common.dto.auth.Role;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.lock.LockTiming;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.core.Authorization;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative advisory edit locks (Logic tier, E18.1/E18.2 — F10).
 *
 * <p>Two teachers opening the same question is normal in this product; two
 * teachers <i>saving</i> it is the bug. This service is the first of the two
 * defences: it hands out a per-entity lock so the second editor opens read-only
 * with a banner naming the first. The second defence is the optimistic
 * {@code CONFLICT} on the write itself (E18.4), because an advisory lock is
 * advisory — it prevents the situation, it does not police it.
 *
 * <p><b>In memory, deliberately.</b> A lock is worth nothing after a restart:
 * every client that held one is disconnected, so a persisted lock table would
 * come back full of locks nobody holds, which is exactly the state this design
 * spends a TTL to avoid. State lives in a {@link ConcurrentHashMap} keyed by
 * {@link EntityRef}; the map itself is the mutual exclusion.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Identity from the session, never the payload.</b> {@link LockRequest}
 *       carries only the entity. A client cannot release someone else's lock,
 *       because it cannot name someone else.</li>
 *   <li><b>Expiry is a takeover, not a queue.</b> Acquiring an entity whose lock
 *       has passed its {@link LockTiming#TTL} succeeds and the old holder is
 *       told their lock is gone. Without that, one crashed client would make a
 *       question uneditable forever.</li>
 *   <li><b>Sweep on access, plus an optional scheduled sweep.</b> Every
 *       acquire/renew/release expires the touched key first, so the answer is
 *       never based on a stale hold. {@link #sweepExpired()} does the same for
 *       every key at once and exists for the case nobody touches: a watcher
 *       waiting for a crashed colleague's lock is told it lapsed rather than
 *       sitting on a banner that has become a lie.</li>
 *   <li><b>Watchers are whoever asked.</b> Calling {@code LOCK_ACQUIRE}
 *       registers interest in that entity — granted or refused. That is the
 *       whole watch mechanism, and it needs no extra verb because a screen that
 *       cares about a lock is exactly a screen that tried to take it. The
 *       registration is dropped on release, on logout and on a dropped socket.</li>
 *   <li><b>Renewing a lock you do not hold fails as a refusal, not an error.</b>
 *       A heartbeat that arrives after a takeover is an ordinary race, and the
 *       client turns the refusal into the "take over editing?" prompt.</li>
 * </ol>
 *
 * <p>Everything is constructor-injected — {@link PushGateway}, a
 * {@link DisplayNames} lookup and a {@link Clock} — so expiry, takeover and the
 * disconnect path are all unit-testable by moving a test clock rather than by
 * sleeping.
 */
public class EditLockService {

    private static final Logger log = LoggerFactory.getLogger(EditLockService.class);

    /** Answer to a lock verb whose payload is not a {@link LockRequest}. */
    public static final String MALFORMED_REQUEST =
            "That editing request could not be read. Please reopen the editor.";

    /** One live hold. Immutable: a renewal replaces it rather than mutating it. */
    private record Held(long userId, Instant expiresAt) {

        boolean hasExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final PushGateway pushGateway;
    private final DisplayNames displayNames;
    private final Clock clock;

    private final Map<EntityRef, Held> locks = new ConcurrentHashMap<>();
    private final Map<EntityRef, Set<Long>> watchers = new ConcurrentHashMap<>();

    /** Production wiring: system clock. */
    public EditLockService(PushGateway pushGateway, DisplayNames displayNames) {
        this(pushGateway, displayNames, Clock.systemUTC());
    }

    /** @param clock time source for the TTL; a test clock in tests */
    public EditLockService(PushGateway pushGateway, DisplayNames displayNames, Clock clock) {
        this.pushGateway = Objects.requireNonNull(pushGateway, "pushGateway");
        this.displayNames = Objects.requireNonNull(displayNames, "displayNames");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the three lock verbs; all authenticated, none open. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.LOCK_ACQUIRE, (caller, request) -> handle(caller, request, this::acquire));
        router.register(Verb.LOCK_RENEW, (caller, request) -> handle(caller, request, this::renew));
        router.register(Verb.LOCK_RELEASE, (caller, request) -> handle(caller, request, this::release));
    }

    /**
     * Hooks disconnect cleanup into the session map (E18.3, E3.4).
     *
     * <p>Logout and a dropped socket both arrive here, because
     * {@link SessionManager#detach} is the single path out of a session for
     * both. That is why this is one hook and not two, and why a client killed
     * with the power button releases its locks as promptly as one that signed
     * out politely.
     */
    public void attachTo(SessionManager sessions) {
        Objects.requireNonNull(sessions, "sessions");
        sessions.addDisconnectHook(this::onSessionEnded);
    }

    // ===================== Operations ====================================

    /**
     * Takes the lock on {@code entity}, or reports who has it.
     *
     * <p>Also registers the caller as a watcher either way: a refused caller is
     * precisely the one who needs to hear the moment it is released.
     *
     * @return a grant, or a refusal naming the current holder
     */
    public LockResponse acquire(long userId, EntityRef entity) {
        Objects.requireNonNull(entity, "entity");
        watch(entity, userId);
        Instant now = clock.instant();
        Instant expiry = now.plus(LockTiming.TTL);

        // One atomic compute rather than get-then-put: two teachers pressing the
        // same button at the same instant must produce exactly one winner, and a
        // read followed by a write would let both of them see "free".
        Held[] before = new Held[1];
        Held after = locks.compute(entity, (key, current) -> {
            before[0] = current;
            boolean free = current == null || current.hasExpired(now);
            return free || current.userId() == userId ? new Held(userId, expiry) : current;
        });

        if (after.userId() != userId) {
            log.debug("Refused lock on {} for user {} - held by {}", entity, userId, after.userId());
            return LockResponse.refused(entity, holder(after.userId()), after.expiresAt());
        }
        Held previous = before[0];
        boolean changedHands = previous == null || previous.userId() != userId || previous.hasExpired(now);
        log.info("User {} holds {} until {}", userId, entity, expiry);
        if (changedHands) {
            // Everyone else watching this entity learns who took it, so their banner
            // names a person rather than "somebody". Re-acquiring a lock already
            // mine (a screen re-opened) says nothing new and raises no push.
            publish(entity, LockChange.acquired(entity, holder(userId)), userId);
        }
        return LockResponse.granted(entity, holder(userId), expiry);
    }

    /**
     * Extends the caller's own hold (the client's heartbeat).
     *
     * @return a fresh grant, or a refusal when the lock was taken over or has
     *         lapsed and been taken by somebody else in the meantime
     */
    public LockResponse renew(long userId, EntityRef entity) {
        Objects.requireNonNull(entity, "entity");
        Instant now = clock.instant();
        Instant expiry = now.plus(LockTiming.TTL);

        Held[] before = new Held[1];
        Held after = locks.compute(entity, (key, current) -> {
            before[0] = current;
            if (current == null) {
                return null;
            }
            if (current.hasExpired(now)) {
                // A heartbeat that arrives after its own TTL lapsed does not
                // resurrect the lock. Letting a late renewal win would make the TTL
                // meaningless for exactly the client it exists to catch.
                return null;
            }
            return current.userId() == userId ? new Held(userId, expiry) : current;
        });

        if (after == null) {
            if (before[0] != null) {
                log.info("Lock on {} lapsed while user {} was renewing it", entity, userId);
                publish(entity, LockChange.expired(entity), null);
            }
            // Nobody holds it. Do not silently re-take it: the client asks the user
            // whether to take over, which is the difference between a helpful app
            // and one that quietly discards a colleague's edits.
            return LockResponse.free(entity);
        }
        if (after.userId() != userId) {
            log.debug("Renew of {} refused for user {} - held by {}", entity, userId, after.userId());
            return LockResponse.refused(entity, holder(after.userId()), after.expiresAt());
        }
        return LockResponse.granted(entity, holder(userId), expiry);
    }

    /**
     * Gives the lock back and stops watching.
     *
     * <p>Releasing something you do not hold is a no-op that answers normally: a
     * screen closing after its lock was taken over must not have to reason about
     * whether its release is still legal.
     *
     * @return the state of the entity afterwards
     */
    public LockResponse release(long userId, EntityRef entity) {
        Objects.requireNonNull(entity, "entity");
        Held[] before = new Held[1];
        Held after = locks.compute(entity, (key, current) -> {
            before[0] = current;
            return current != null && current.userId() == userId ? null : current;
        });
        unwatch(entity, userId);

        if (before[0] != null && after == null) {
            log.info("User {} released {}", userId, entity);
            publish(entity, LockChange.released(entity), userId);
            return LockResponse.free(entity);
        }
        if (after == null) {
            return LockResponse.free(entity);
        }
        return LockResponse.refused(entity, holder(after.userId()), after.expiresAt());
    }

    /**
     * Drops every lock held by a user and every entity they were watching (E18.3).
     *
     * @return how many locks were released
     */
    public int releaseAllHeldBy(long userId) {
        List<EntityRef> released = new ArrayList<>();
        for (Map.Entry<EntityRef, Held> entry : locks.entrySet()) {
            if (entry.getValue().userId() == userId && locks.remove(entry.getKey(), entry.getValue())) {
                released.add(entry.getKey());
            }
        }
        forgetWatcher(userId);
        for (EntityRef entity : released) {
            publish(entity, LockChange.released(entity), userId);
        }
        if (!released.isEmpty()) {
            log.info("Released {} lock(s) held by user {}: {}", released.size(), userId, released);
        }
        return released.size();
    }

    /**
     * Expires every hold whose TTL has passed and tells the watchers.
     *
     * <p>Sweeping on access alone is not enough for the one case the TTL exists
     * for: a client that crashed while holding a lock is touched by nobody, so
     * the colleague waiting for it would keep a banner naming someone who left
     * the building. The server schedules this; tests call it directly.
     *
     * @return how many locks lapsed
     */
    public int sweepExpired() {
        Instant now = clock.instant();
        List<EntityRef> lapsed = new ArrayList<>();
        for (Map.Entry<EntityRef, Held> entry : locks.entrySet()) {
            if (entry.getValue().hasExpired(now) && locks.remove(entry.getKey(), entry.getValue())) {
                lapsed.add(entry.getKey());
            }
        }
        for (EntityRef entity : lapsed) {
            // No actor to exclude: the former holder is a recipient too, because if
            // they are alive they have just lost their lock and must be told.
            publish(entity, LockChange.expired(entity), null);
        }
        if (!lapsed.isEmpty()) {
            log.info("Swept {} expired lock(s): {}", lapsed.size(), lapsed);
        }
        return lapsed.size();
    }

    // ===================== Queries (diagnostics and tests) ===============

    /** @return who currently holds {@code entity}, if anyone and not expired. */
    public Optional<LockHolder> holderOf(EntityRef entity) {
        Held held = locks.get(entity);
        if (held == null || held.hasExpired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(holder(held.userId()));
    }

    /** @return {@code true} when this user holds a live lock on {@code entity}. */
    public boolean isHeldBy(EntityRef entity, long userId) {
        return holderOf(entity).filter(held -> held.is(userId)).isPresent();
    }

    /** @return how many live or lapsed holds the service is tracking. */
    public int lockCount() {
        return locks.size();
    }

    /** @return the users currently watching {@code entity}. */
    public Set<Long> watchersOf(EntityRef entity) {
        return Set.copyOf(watchers.getOrDefault(entity, Set.of()));
    }

    // ===================== Handlers ======================================

    /** Shared shape of all three verbs: same payload, same guard, different operation. */
    private Message handle(CallerContext caller, Message request, Operation operation) {
        // Edit locks exist for people who edit. Students never hold one, and a
        // student who could would be able to pin an entity read-only for its TTL
        // over and over - a nuisance the role gate removes outright (P-5 follow-up).
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof LockRequest lock)) {
            log.warn("{} with a {} payload", request.getVerb(), describe(request.getPayload()));
            return Message.error(request, ErrorCode.VALIDATION, MALFORMED_REQUEST);
        }
        return Message.ok(request, operation.apply(caller.userId(), lock.entity()));
    }

    @FunctionalInterface
    private interface Operation {
        LockResponse apply(long userId, EntityRef entity);
    }

    // ===================== Internals =====================================

    private void onSessionEnded(long userId, ConnectionToClient connection) {
        int released = releaseAllHeldBy(userId);
        log.debug("Session of user {} ended; {} lock(s) released", userId, released);
    }

    private void watch(EntityRef entity, long userId) {
        watchers.computeIfAbsent(entity, key -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    private void unwatch(EntityRef entity, long userId) {
        Set<Long> interested = watchers.get(entity);
        if (interested == null) {
            return;
        }
        interested.remove(userId);
        if (interested.isEmpty()) {
            // Otherwise every entity ever opened leaves an empty set behind, and the
            // map grows for the lifetime of the server.
            watchers.remove(entity, interested);
        }
    }

    private void forgetWatcher(long userId) {
        for (Map.Entry<EntityRef, Set<Long>> entry : watchers.entrySet()) {
            Set<Long> interested = entry.getValue();
            interested.remove(userId);
            if (interested.isEmpty()) {
                watchers.remove(entry.getKey(), interested);
            }
        }
    }

    /**
     * Pushes a change to everyone watching, minus the person who caused it (they
     * already have the answer in their response).
     */
    private void publish(EntityRef entity, LockChange change, Long actorUserId) {
        Set<Long> interested = watchers.get(entity);
        if (interested == null || interested.isEmpty()) {
            return;
        }
        Set<Long> recipients = new HashSet<>(interested);
        if (actorUserId != null) {
            recipients.remove(actorUserId);
        }
        if (recipients.isEmpty()) {
            return;
        }
        int delivered = pushGateway.toUsers(recipients, Verb.PUSH_LOCK_CHANGED, change);
        log.debug("Lock change {} on {} pushed to {}/{} watcher(s)",
                change.kind(), entity, delivered, recipients.size());
    }

    private LockHolder holder(long userId) {
        return new LockHolder(userId,
                displayNames.displayName(userId).orElse(LockHolder.UNKNOWN_NAME));
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }
}
