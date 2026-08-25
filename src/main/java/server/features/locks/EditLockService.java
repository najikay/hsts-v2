package server.features.locks;

import common.dto.auth.Role;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.lock.LockTiming;
import common.dto.lock.LocksSnapshot;
import common.dto.lock.LocksSnapshotRequest;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *       usual watch mechanism, because a screen that cares about a lock is
 *       usually a screen that tried to take it. The registration is dropped on
 *       release, on logout and on a dropped socket.
 *       <p>E18.8 added the one case that is not usual: a <em>list</em> screen
 *       cares about forty locks and must take none of them, so {@link #watch}
 *       is the same registration without the acquisition. It was always
 *       separable, since acquire has called an internal watch on its first line
 *       since E18.1; E18.8 only gave it a door.</li>
 *   <li><b>Renewing a lock you do not hold fails as a refusal, not an error.</b>
 *       A heartbeat that arrives after a takeover is an ordinary race, and the
 *       client turns the refusal into the "take over editing?" prompt.</li>
 *   <li><b>The reading and taking verbs are scoped, and the scope is somebody
 *       else's knowledge (E18.9).</b> {@link #snapshot}, {@link #watch} and
 *       {@link #acquire} put every id through the {@link EntityScopes} predicate
 *       installed for its type, so a caller is never told about — and can never
 *       hold — a lock on an entity she cannot reach. An out-of-scope acquire
 *       answers the same free shape an out-of-scope watch does and takes
 *       nothing (closed 2026-08-25; it was briefly a named gap here, and its
 *       refusal used to name the holder). The service still holds no domain
 *       knowledge of its own: it consults a lambda a feature installed at
 *       wiring and never learns what the answer means. A type with no scope
 *       installed is unfiltered, deliberately — see {@link EntityScopes} for
 *       why that default runs the opposite way to {@code Authorization}'s.
 *       {@link #renew} and {@link #release} stay unscoped: both key on the
 *       caller's own user id and can only ever touch her own hold.</li>
 * </ol>
 *
 * <p>Everything is constructor-injected — {@link PushGateway}, a
 * {@link DisplayNames} lookup and a {@link Clock} — so expiry, takeover and the
 * disconnect path are all unit-testable by moving a test clock rather than by
 * sleeping. The one exception is {@link #scopes}, installed after construction
 * for an ordering reason {@link #scopes()} states.
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
    private final EntityScopes scopes = new EntityScopes();

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

    /**
     * The per-type scope registry the two list verbs consult (E18.9).
     *
     * <p>Handed out rather than constructor-injected, which is the one place this
     * service does not follow its own "everything is constructor-injected" rule.
     * The reason is ordering: a scope for {@code question} is a lambda over the
     * bank's repositories, and in {@code HSTSServer} the lock service is
     * assembled before the bank is. Requiring the registry at construction would
     * mean building it empty and populating it later anyway, which is this method
     * with an extra argument.
     *
     * <p>Install once, at assembly. Nothing reads it during construction, so
     * there is no window in which a request could be served against a
     * half-populated registry that a constructor argument would close.
     *
     * @return the registry, to install scopes on
     * @see EntityScopes the unfiltered-when-uninstalled contract
     */
    public EntityScopes scopes() {
        return scopes;
    }

    /** Registers the five lock verbs; all authenticated, none open. */
    public void registerOn(MessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register(Verb.LOCK_ACQUIRE, (caller, request) -> handle(caller, request, this::acquire));
        router.register(Verb.LOCK_RENEW, (caller, request) -> handle(caller, request, this::renew));
        router.register(Verb.LOCK_RELEASE, (caller, request) -> handle(caller, request, this::release));
        router.register(Verb.LOCK_WATCH, (caller, request) -> handle(caller, request, this::watch));
        router.register(Verb.LOCKS_SNAPSHOT, this::handleSnapshot);
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
        if (!scopes.reaches(entity, userId)) {
            // The same non-disclosure shape watch() uses, ruled 2026-08-25 with the
            // snapshot scoping: an out-of-scope acquire must neither TAKE the lock (a
            // teacher could block a course she cannot read) nor NAME a holder (the
            // refusal was the last one-directional oracle in this group). "Free" here
            // is indistinguishable from an entity nobody is editing, and the caller's
            // own editor can never legitimately reach this branch - it navigates from
            // lists that are scoped already.
            log.debug("User {} may not acquire {}; refused as free, nothing taken", userId, entity);
            return LockResponse.free(entity);
        }
        addWatcher(entity, userId);
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
     * Registers interest in {@code entity} <b>without contending for it</b>
     * (E18.8, {@code LOCK_WATCH}).
     *
     * <p>Everything {@link #acquire} does about watching, and nothing it does
     * about holding. The caller starts receiving {@code PUSH_LOCK_CHANGED} for
     * this entity and the entity's lock is left exactly as it was, which is the
     * only behaviour a list screen can use: a bank list painting forty rows would,
     * with acquire, take forty locks and put forty colleagues into read-only mode
     * by the act of being looked at.
     *
     * <p>Watching is idempotent and raises no push. Nothing changed, so there is
     * no news, and a screen that re-watches on every refresh must not produce a
     * push storm.
     *
     * <h2>Out of scope is silently not registered (E18.9)</h2>
     *
     * <p>A watch on an entity the caller's {@link EntityScopes} scope does not
     * reach is <b>not registered</b>, and the answer is the free one: not
     * granted, no holder, no expiry. That is the same shape a watch on a genuinely
     * free entity gets, so the refusal discloses nothing — which is the point,
     * and is why this is a silent filter rather than a {@code FORBIDDEN}.
     *
     * <p><b>Registration rather than delivery, and that was a cost decision.</b>
     * {@link #snapshot} closing the oracle would be worth little if the caller
     * could watch an out-of-scope id and be told about it by the next
     * {@code PUSH_LOCK_CHANGED} instead. Two places could close that: here, or
     * {@link #publish} filtering its recipients. Filtering here is one consult in
     * the place that already has the caller, the entity and the scope in hand.
     * Filtering at publish would be one consult per recipient per lock change, on
     * the hot path, forever — the same answer bought repeatedly. A list screen
     * already spends one message per row on this verb, so one consult per row is
     * proportionate to what the client is doing anyway.
     *
     * @return the entity's current state, never a grant: a watcher is not a
     *         holder, so {@code granted} is {@code false} even when nobody holds
     *         it and {@code holder} is {@code null} in exactly that case
     */
    public LockResponse watch(long userId, EntityRef entity) {
        Objects.requireNonNull(entity, "entity");
        if (!scopes.reaches(entity, userId)) {
            // Indistinguishable from a free entity on purpose: a caller must not be
            // able to tell "you may not watch this" from "nobody is editing this".
            log.debug("User {} may not watch {}; registration dropped", userId, entity);
            return LockResponse.free(entity);
        }
        addWatcher(entity, userId);
        Held held = live(entity);
        if (held == null) {
            log.debug("User {} watches {} (free)", userId, entity);
            return LockResponse.free(entity);
        }
        log.debug("User {} watches {} (held by {})", userId, entity, held.userId());
        return LockResponse.refused(entity, holder(held.userId()), held.expiresAt());
    }

    /**
     * Who is editing each of these entities, right now (E18.8,
     * {@code LOCKS_SNAPSHOT}).
     *
     * <p>The first paint of a list screen. Pushes carry news, not state: a
     * question locked ten minutes ago raises nothing when a colleague opens the
     * bank, so a screen fed only by {@code PUSH_LOCK_CHANGED} would show it as
     * free until somebody happened to touch it. One snapshot at load plus the
     * pushes afterwards is the whole picture — <b>provided the screen calls
     * {@link #watch} first</b> (qualified 2026-08-25; the sentence used to end at
     * "picture" and that was false). {@link #publish} resolves recipients from the
     * watcher set at the instant the lock changes, so a screen that snapshots and
     * then watches has a window in which a colleague acquires, the push finds a
     * set the screen is not in, and the row shows free for that whole edit
     * session. Watch first and the overlap duplicates, which is idempotent here;
     * read first and it drops, which is silent (Member A, PR20 §3, P-11).
     *
     * <p>Only live holds are reported. An expired hold that the sweeper has not
     * reached yet is not in the answer, because it is not true any more; the
     * entry is left in the map for the sweeper rather than removed here, so a
     * read stays a read.
     *
     * <p>Reporting does not subscribe. A screen that wants the chip to stay live
     * calls {@link #watch} per row it is showing, and keeping the two apart is
     * what lets a one-off refresh stay a one-off.
     *
     * <h2>Out of scope is absent, not refused (E18.9)</h2>
     *
     * <p>Every id is put through the caller's {@link EntityScopes} scope for this
     * type before it is answered, and an id she does not reach is simply <b>not
     * in the map</b> — the same absence a free id gets, and the same absence an
     * id that has never existed gets. Absence was already ambiguous and stays
     * that way; what changes is that <em>presence</em> no longer proves anything
     * about a row out of reach.
     *
     * <p>Before this, the verb was role-gated and scoped no further, so a present
     * entry proved that a row exists, that somebody is editing it and who — for a
     * course whose every bank read answers {@code NOT_FOUND} out of scope,
     * indistinguishably from a row that does not exist. One direction of the "not
     * an existence oracle" claim held and the other did not (Member A, PR20 §5.3).
     *
     * <p><b>Only held ids are put through the scope, and the answer is identical
     * either way.</b> An id nobody holds is absent whether or not the caller
     * reaches it, so consulting the scope for it can only change how long the
     * answer takes. That matters at this verb's scale: {@code MAX_IDS} is 500 and
     * a scope consult is a database read, so filtering first would mean up to 500
     * reads to remove entries that were never going to exist. Filtering the held
     * ones costs one read per row somebody is actually editing, which on a bank
     * page is nought to a handful. The residual is a timing signal — a batch
     * containing a held id takes marginally longer than one containing none — and
     * that is accepted: it says nothing about <em>which</em> id, and the map
     * lookup it rides on predates this filter.
     *
     * @param callerId   the session's user id, whose scope the ids are filtered
     *                   through; never taken from a payload
     * @param entityType the kind of thing every id refers to
     * @param entityIds  the ids on screen; unknown, free and out-of-scope ids are
     *                   all simply absent from the answer
     * @return the sparse map of id to holder
     */
    public LocksSnapshot snapshot(long callerId, String entityType, Collection<Long> entityIds) {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityIds, "entityIds");
        Map<Long, LockHolder> held = new LinkedHashMap<>();
        int hidden = 0;
        for (Long entityId : entityIds) {
            if (entityId == null) {
                continue;
            }
            Held current = live(new EntityRef(entityType, entityId));
            if (current == null) {
                continue;
            }
            if (!scopes.reaches(entityType, callerId, entityId)) {
                hidden++;
                continue;
            }
            held.put(entityId, holder(current.userId()));
        }
        log.debug("Snapshot of {} {} id(s) for user {}: {} held, {} withheld as out of scope",
                entityIds.size(), entityType, callerId, held.size(), hidden);
        return new LocksSnapshot(entityType, held);
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
        Held held = live(entity);
        return held == null ? Optional.empty() : Optional.of(holder(held.userId()));
    }

    /**
     * The one place a read decides whether a stored hold still counts.
     *
     * @return the hold on {@code entity} when it exists and its TTL has not
     *         passed, otherwise {@code null}
     */
    private Held live(EntityRef entity) {
        Held held = locks.get(entity);
        return held == null || held.hasExpired(clock.instant()) ? null : held;
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

    /**
     * {@code LOCKS_SNAPSHOT} (E18.8): a different payload from the other four, so
     * it does not fit {@link #handle}'s shape, but the same role gate and the same
     * refuse-rather-than-fail treatment of a payload that cannot be read.
     *
     * <p>An oversized request is a {@code VALIDATION} error rather than a silent
     * truncation. Truncating would answer "nobody is editing rows 501 and up",
     * which is a wrong answer dressed as a right one, and the caller would have no
     * way to tell.
     */
    private Message handleSnapshot(CallerContext caller, Message request) {
        Authorization.requireRole(caller, Role.TEACHER, Role.COORDINATOR);
        if (!(request.getPayload() instanceof LocksSnapshotRequest query)) {
            log.warn("{} with a {} payload", request.getVerb(), describe(request.getPayload()));
            return Message.error(request, ErrorCode.VALIDATION, MALFORMED_REQUEST);
        }
        if (query.isOversized()) {
            log.warn("Refused a locks snapshot of {} ids from user {} (max {})",
                    query.entityIds().size(), caller.userId(), LocksSnapshotRequest.MAX_IDS);
            return Message.error(request, ErrorCode.VALIDATION, LocksSnapshotRequest.TOO_MANY_IDS);
        }
        return Message.ok(request,
                snapshot(caller.userId(), query.entityType(), query.entityIds()));
    }

    /** Shared shape of the four single-entity verbs: same payload, same guard, different operation. */
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

    private void addWatcher(EntityRef entity, long userId) {
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
