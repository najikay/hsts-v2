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
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.AuthorizationException;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * List-level lock visibility: {@code LOCK_WATCH} and {@code LOCKS_SNAPSHOT}
 * (E18.8).
 *
 * <p>Three properties are what E18.8 is for, and each has its own section below:
 * a watcher hears {@code LOCK_CHANGED} <em>without</em> having contended for the
 * entity (the whole point: a list of forty rows must not take forty locks), a
 * snapshot reports live holds only, and both verbs are gated to the two teaching
 * roles like the three that came before them.
 *
 * <p>Time-dependent cases run on a {@link MutableClock}, so an expired hold is a
 * clock move rather than a forty-five second wait. Pushes are observed through a
 * real {@link PushGateway} over a real {@link SessionManager} with the sockets
 * recorded, which is how the running server decides who hears what.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LockVisibilityTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final long MAYA = 2001L;
    private static final Instant T0 = Instant.parse("2026-08-20T09:00:00Z");

    private static final EntityRef Q41 = EntityRef.question(41);
    private static final EntityRef Q42 = EntityRef.question(42);
    private static final EntityRef Q43 = EntityRef.question(43);

    private static final DisplayNames NAMES = userId -> switch ((int) userId) {
        case (int) DANA -> Optional.of("Dana Cohen");
        case (int) RINA -> Optional.of("Rina Barak");
        case (int) MAYA -> Optional.of("Maya Levi");
        default -> Optional.empty();
    };

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient rinaSocket;

    private MutableClock clock;
    private SessionManager sessions;
    private RecordingGateway gateway;
    private EditLockService locks;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        sessions = new SessionManager();
        gateway = new RecordingGateway(sessions);
        locks = new EditLockService(gateway, NAMES, clock);
    }

    // ===================== Watch without contending ======================

    @Nested
    @DisplayName("LOCK_WATCH")
    class Watch {

        @Test
        @DisplayName("watching a free entity leaves it free and grants nothing")
        void watchingDoesNotTake() {
            LockResponse response = locks.watch(RINA, Q42);

            assertThat(response.granted())
                    .as("a watcher is never a holder")
                    .isFalse();
            assertThat(response.isFree()).isTrue();
            assertThat(response.holder()).isNull();
            assertThat(locks.lockCount())
                    .as("watching must not create a hold, not even a self-owned one")
                    .isZero();
            assertThat(locks.watchersOf(Q42)).containsExactly(RINA);
        }

        @Test
        @DisplayName("watching a held entity names the holder without disturbing the hold")
        void watchingAHeldEntityNamesTheHolder() {
            locks.acquire(DANA, Q42);

            LockResponse response = locks.watch(RINA, Q42);

            assertThat(response.granted()).isFalse();
            assertThat(response.holder()).isEqualTo(new LockHolder(DANA, "Dana Cohen"));
            assertThat(response.expiresAt()).isEqualTo(T0.plus(LockTiming.TTL));
            assertThat(locks.isHeldBy(Q42, DANA))
                    .as("Dana keeps her lock: a colleague looking at a list is not a contender")
                    .isTrue();
        }

        @Test
        @DisplayName("a watcher receives LOCK_CHANGED although it never tried to acquire")
        void watcherReceivesPushes() {
            sessions.attach(RINA, rinaSocket);
            locks.watch(RINA, Q42);

            locks.acquire(DANA, Q42);

            assertThat(gateway.kindsFor(RINA))
                    .as("the acquisition reaches the list screen live")
                    .containsExactly(LockChange.Kind.ACQUIRED);
            assertThat(gateway.changesFor(RINA).get(0).holder().displayName())
                    .as("the chip has a name to render, not an id")
                    .isEqualTo("Dana Cohen");

            locks.release(DANA, Q42);

            assertThat(gateway.kindsFor(RINA))
                    .containsExactly(LockChange.Kind.ACQUIRED, LockChange.Kind.RELEASED);
        }

        @Test
        @DisplayName("a watcher hears the sweeper expire a lock it never contended for")
        void watcherHearsExpiry() {
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, Q42);
            locks.watch(RINA, Q42);
            gateway.clear();

            clock.advance(LockTiming.TTL.plusSeconds(1));
            assertThat(locks.sweepExpired()).isEqualTo(1);

            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.EXPIRED);
        }

        @Test
        @DisplayName("watching twice registers once and says nothing on the wire")
        void watchingIsIdempotentAndQuiet() {
            sessions.attach(RINA, rinaSocket);
            sessions.attach(DANA, danaSocket);

            locks.watch(RINA, Q42);
            locks.watch(RINA, Q42);

            assertThat(locks.watchersOf(Q42)).containsExactly(RINA);
            assertThat(gateway.pushes())
                    .as("nothing changed, so there is no news to push")
                    .isEmpty();
        }

        @Test
        @DisplayName("releasing stops the watch although the watcher held nothing")
        void releaseUnwatches() {
            sessions.attach(RINA, rinaSocket);
            locks.watch(RINA, Q42);

            LockResponse response = locks.release(RINA, Q42);

            assertThat(response.isFree()).isTrue();
            assertThat(locks.watchersOf(Q42)).isEmpty();

            locks.acquire(DANA, Q42);
            assertThat(gateway.changesFor(RINA))
                    .as("a screen that stopped watching stops being pushed to")
                    .isEmpty();
        }

        @Test
        @DisplayName("a dropped socket forgets the watch")
        void disconnectForgetsTheWatch() {
            locks.attachTo(sessions);
            sessions.attach(RINA, rinaSocket);
            locks.watch(RINA, Q42);

            sessions.detach(rinaSocket);

            assertThat(locks.watchersOf(Q42)).isEmpty();
        }

        @Test
        @DisplayName("a watched entity whose hold lapsed reads as free")
        void expiredHoldReadsAsFree() {
            locks.acquire(DANA, Q42);
            clock.advance(LockTiming.TTL.plusSeconds(1));

            LockResponse response = locks.watch(RINA, Q42);

            assertThat(response.isFree())
                    .as("a lapsed hold is not a hold, sweeper or no sweeper")
                    .isTrue();
        }

        @Test
        @DisplayName("the entity is required")
        void entityIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> locks.watch(DANA, null));
        }
    }

    // ===================== Snapshot ======================================

    @Nested
    @DisplayName("LOCKS_SNAPSHOT")
    class Snapshot {

        @Test
        @DisplayName("only entities somebody is holding appear in the answer")
        void reportsOnlyHeldEntities() {
            locks.acquire(DANA, Q41);
            locks.acquire(RINA, Q43);

            LocksSnapshot snapshot = locks.snapshot(EntityRef.QUESTION, List.of(41L, 42L, 43L));

            assertThat(snapshot.entityType()).isEqualTo(EntityRef.QUESTION);
            assertThat(snapshot.holders()).containsOnlyKeys(41L, 43L);
            assertThat(snapshot.holderOf(41L)).contains(new LockHolder(DANA, "Dana Cohen"));
            assertThat(snapshot.holderOf(43L)).contains(new LockHolder(RINA, "Rina Barak"));
            assertThat(snapshot.isHeld(42L))
                    .as("a free row is absent rather than mapped to null")
                    .isFalse();
            assertThat(snapshot.heldCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("an expired hold is not reported, even before the sweeper runs")
        void expiredHoldsAreNotLive() {
            locks.acquire(DANA, Q41);
            locks.acquire(RINA, Q43);
            clock.advance(LockTiming.TTL.plusSeconds(1));
            locks.acquire(MAYA, Q43);

            LocksSnapshot snapshot = locks.snapshot(EntityRef.QUESTION, List.of(41L, 43L));

            assertThat(snapshot.holders())
                    .as("41 lapsed and was never touched again; 43 was taken over")
                    .containsOnlyKeys(43L);
            assertThat(snapshot.holderOf(43L)).contains(new LockHolder(MAYA, "Maya Levi"));
            assertThat(locks.lockCount())
                    .as("a read leaves the lapsed entry for the sweeper rather than mutating")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("ids nobody has ever locked answer as free rather than as an error")
        void unknownIdsAreFree() {
            LocksSnapshot snapshot = locks.snapshot(EntityRef.QUESTION, List.of(999L, 1000L));

            assertThat(snapshot.holders()).isEmpty();
        }

        @Test
        @DisplayName("asking does not subscribe")
        void snapshotDoesNotWatch() {
            locks.snapshot(EntityRef.QUESTION, List.of(41L, 42L));

            assertThat(locks.watchersOf(Q41)).isEmpty();
            assertThat(locks.watchersOf(Q42)).isEmpty();
        }

        @Test
        @DisplayName("the entity type is normalised the same way EntityRef normalises it")
        void typeIsNormalised() {
            locks.acquire(DANA, Q41);

            LocksSnapshot snapshot = locks.snapshot("QUESTION", List.of(41L));

            assertThat(snapshot.isHeld(41L))
                    .as("a client sending 'QUESTION' must not see a second, empty world")
                    .isTrue();
        }

        @Test
        @DisplayName("null ids in the list are skipped rather than fatal")
        void nullIdsAreSkipped() {
            locks.acquire(DANA, Q41);

            LocksSnapshot snapshot =
                    locks.snapshot(EntityRef.QUESTION, Arrays.asList(41L, null, 42L));

            assertThat(snapshot.holders()).containsOnlyKeys(41L);
        }

        @Test
        @DisplayName("an empty list is an empty answer, not a whole-table dump")
        void emptyListIsEmptyAnswer() {
            locks.acquire(DANA, Q41);

            assertThat(locks.snapshot(EntityRef.QUESTION, List.of()).holders()).isEmpty();
        }

        @Test
        @DisplayName("arguments are required")
        void argumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> locks.snapshot(null, List.of(1L)));
            assertThatNullPointerException()
                    .isThrownBy(() -> locks.snapshot(EntityRef.QUESTION, null));
        }
    }

    // ===================== Wire =========================================

    @Nested
    @DisplayName("over the router")
    class Wire {

        private MessageRouter router;

        @BeforeEach
        void register() {
            router = new MessageRouter(sessions);
            locks.registerOn(router);
        }

        @Test
        @DisplayName("both new verbs are registered and neither is open")
        void bothVerbsAreRegistered() {
            assertThat(router.registeredVerbs())
                    .contains(Verb.LOCK_WATCH, Verb.LOCKS_SNAPSHOT);
            assertThat(router.isOpen(Verb.LOCK_WATCH)).isFalse();
            assertThat(router.isOpen(Verb.LOCKS_SNAPSHOT)).isFalse();
        }

        @Test
        @DisplayName("LOCK_WATCH answers the entity's current state")
        void watchOverTheWire() {
            locks.acquire(DANA, Q42);

            Message response = router.route(
                    Message.request(Verb.LOCK_WATCH, new LockRequest(Q42)),
                    teacher(RINA));

            assertThat(response.getStatus()).isEqualTo(Status.OK);
            assertThat(response.getPayload()).isInstanceOf(LockResponse.class);
            LockResponse payload = (LockResponse) response.getPayload();
            assertThat(payload.granted()).isFalse();
            assertThat(payload.holder().displayName()).isEqualTo("Dana Cohen");
        }

        @Test
        @DisplayName("LOCKS_SNAPSHOT answers a sparse map of live holders")
        void snapshotOverTheWire() {
            locks.acquire(DANA, Q41);

            Message response = router.route(
                    Message.request(Verb.LOCKS_SNAPSHOT,
                            LocksSnapshotRequest.of(EntityRef.QUESTION, List.of(41L, 42L))),
                    teacher(RINA));

            assertThat(response.getStatus()).isEqualTo(Status.OK);
            LocksSnapshot payload = (LocksSnapshot) response.getPayload();
            assertThat(payload.holders()).containsOnlyKeys(41L);
        }

        @Test
        @DisplayName("a student is refused both verbs, like the other three")
        void studentsAreRefused() {
            CallerContext student = CallerContext.authenticated(rinaSocket, MAYA, Role.STUDENT);

            Message watch = router.route(
                    Message.request(Verb.LOCK_WATCH, new LockRequest(Q42)), student);
            Message snapshot = router.route(
                    Message.request(Verb.LOCKS_SNAPSHOT,
                            LocksSnapshotRequest.of(EntityRef.QUESTION, List.of(41L))), student);

            assertThat(watch.getStatus()).isEqualTo(Status.ERROR);
            assertThat(watch.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(snapshot.getStatus()).isEqualTo(Status.ERROR);
            assertThat(snapshot.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(locks.watchersOf(Q42))
                    .as("a refused caller is not quietly registered as a watcher")
                    .isEmpty();
        }

        @Test
        @DisplayName("the guards throw AuthorizationException, which the router is what maps")
        void guardsThrow() {
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() ->
                    server.core.Authorization.requireRole(
                            CallerContext.authenticated(rinaSocket, MAYA, Role.STUDENT),
                            Role.TEACHER, Role.COORDINATOR));
        }

        @Test
        @DisplayName("a coordinator is allowed both verbs")
        void coordinatorsAreAllowed() {
            CallerContext coordinator =
                    CallerContext.authenticated(danaSocket, DANA, Role.COORDINATOR);

            assertThat(router.route(Message.request(Verb.LOCK_WATCH, new LockRequest(Q42)),
                    coordinator).getStatus()).isEqualTo(Status.OK);
            assertThat(router.route(Message.request(Verb.LOCKS_SNAPSHOT,
                            LocksSnapshotRequest.of(EntityRef.QUESTION, List.of(41L))),
                    coordinator).getStatus()).isEqualTo(Status.OK);
        }

        @Test
        @DisplayName("a payload of the wrong shape is a readable refusal, not a crash")
        void malformedPayloads() {
            Message watch = router.route(
                    Message.request(Verb.LOCK_WATCH, "not a lock request"), teacher(DANA));
            Message snapshot = router.route(
                    Message.request(Verb.LOCKS_SNAPSHOT, new LockRequest(Q42)), teacher(DANA));

            assertThat(watch.getStatus()).isEqualTo(Status.ERROR);
            assertThat(watch.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(snapshot.getStatus()).isEqualTo(Status.ERROR);
            assertThat(snapshot.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(snapshot.getPayload().toString())
                    .as("the message says what to do next")
                    .contains("reopen the editor");
        }

        @Test
        @DisplayName("an oversized snapshot is refused rather than silently truncated")
        void oversizedSnapshotIsRefused() {
            List<Long> tooMany = LongStream.rangeClosed(1, LocksSnapshotRequest.MAX_IDS + 1)
                    .boxed().toList();

            Message response = router.route(
                    Message.request(Verb.LOCKS_SNAPSHOT,
                            LocksSnapshotRequest.of(EntityRef.QUESTION, tooMany)),
                    teacher(DANA));

            assertThat(response.getStatus()).isEqualTo(Status.ERROR);
            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.getPayload().toString())
                    .contains("Show fewer rows at a time");
        }

        @Test
        @DisplayName("a snapshot of exactly the maximum is allowed")
        void maximumSnapshotIsAllowed() {
            List<Long> exactly = LongStream.rangeClosed(1, LocksSnapshotRequest.MAX_IDS)
                    .boxed().toList();

            Message response = router.route(
                    Message.request(Verb.LOCKS_SNAPSHOT,
                            LocksSnapshotRequest.of(EntityRef.QUESTION, exactly)),
                    teacher(DANA));

            assertThat(response.getStatus()).isEqualTo(Status.OK);
        }

        private CallerContext teacher(long userId) {
            return CallerContext.authenticated(danaSocket, userId, Role.TEACHER);
        }
    }

    // ===================== DTO contracts =================================

    @Nested
    @DisplayName("payload records")
    class Payloads {

        @Test
        @DisplayName("the request de-duplicates ids and normalises the type")
        void requestNormalises() {
            LocksSnapshotRequest request =
                    LocksSnapshotRequest.of("  Question ", List.of(7L, 7L, 8L));

            assertThat(request.entityType()).isEqualTo("question");
            assertThat(request.entityIds()).containsExactly(7L, 8L);
            assertThat(request.isOversized()).isFalse();
            assertThat(request.refs())
                    .containsExactly(EntityRef.question(7), EntityRef.question(8));
        }

        @Test
        @DisplayName("the request refuses a blank type and null arguments")
        void requestValidates() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new LocksSnapshotRequest(null, List.of()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LocksSnapshotRequest("question", null));
            assertThat(catchIllegalArgument(() -> new LocksSnapshotRequest("   ", List.of())))
                    .isTrue();
        }

        @Test
        @DisplayName("the snapshot record is an unmodifiable copy of what it was given")
        void snapshotIsDefensive() {
            Map<Long, LockHolder> mutable = new java.util.LinkedHashMap<>();
            mutable.put(1L, new LockHolder(DANA, "Dana Cohen"));
            LocksSnapshot snapshot = new LocksSnapshot("question", mutable);

            mutable.put(2L, new LockHolder(RINA, "Rina Barak"));

            assertThat(snapshot.holders()).containsOnlyKeys(1L);
            assertThat(LocksSnapshot.empty("question").holders()).isEmpty();
            assertThatNullPointerException()
                    .isThrownBy(() -> new LocksSnapshot(null, Map.of()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new LocksSnapshot("question", null));
        }

        @Test
        @DisplayName("both records survive a serialization round trip")
        void wireRoundTrip() throws Exception {
            LocksSnapshotRequest request =
                    LocksSnapshotRequest.of(EntityRef.QUESTION, List.of(1L, 2L));
            LocksSnapshot snapshot =
                    new LocksSnapshot("question", Map.of(1L, new LockHolder(DANA, "Dana Cohen")));

            assertThat(roundTrip(request)).isEqualTo(request);
            assertThat(roundTrip(snapshot)).isEqualTo(snapshot);
        }

        private boolean catchIllegalArgument(Runnable work) {
            try {
                work.run();
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T roundTrip(T value) throws Exception {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                return (T) in.readObject();
            }
        }
    }

    /** A {@link PushGateway} that remembers what it delivered, per recipient. */
    private static final class RecordingGateway extends PushGateway {

        private final Map<Long, List<LockChange>> delivered = new ConcurrentHashMap<>();

        RecordingGateway(SessionManager sessions) {
            super(sessions);
        }

        @Override
        public boolean toUser(long userId, Verb verb, Object payload) {
            boolean sent = super.toUser(userId, verb, payload);
            if (sent && payload instanceof LockChange change) {
                delivered.computeIfAbsent(userId, key -> new ArrayList<>()).add(change);
            }
            return sent;
        }

        void clear() {
            delivered.clear();
        }

        List<LockChange> changesFor(long userId) {
            return List.copyOf(delivered.getOrDefault(userId, List.of()));
        }

        List<LockChange.Kind> kindsFor(long userId) {
            return changesFor(userId).stream().map(LockChange::kind).toList();
        }

        List<LockChange> pushes() {
            return delivered.values().stream().flatMap(List::stream).toList();
        }
    }
}
