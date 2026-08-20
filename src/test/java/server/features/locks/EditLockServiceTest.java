package server.features.locks;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.realtime.PushGateway;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for the server-authoritative edit lock (E18.1/E18.2/E18.3).
 *
 * <p>Every time-dependent case runs on a {@link MutableClock}, so expiry,
 * takeover and a late heartbeat are deterministic rather than a forty-second
 * wait. Pushes are observed through a real {@link PushGateway} over a real
 * {@link SessionManager}, with the sockets recorded, so "who was told" is
 * asserted the same way the running server decides it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EditLockServiceTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final long MAYA = 2001L;
    private static final EntityRef QUESTION = EntityRef.question(42);
    private static final EntityRef OTHER = EntityRef.question(43);
    private static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

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
    @Mock
    private ConnectionToClient mayaSocket;

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

    // ===================== Acquire =======================================

    @Nested
    @DisplayName("acquire")
    class Acquire {

        @Test
        @DisplayName("the first caller gets the lock, named and with an expiry")
        void firstCallerWins() {
            LockResponse response = locks.acquire(DANA, QUESTION);

            assertThat(response.granted()).isTrue();
            assertThat(response.holder()).isEqualTo(new LockHolder(DANA, "Dana Cohen"));
            assertThat(response.expiresAt()).isEqualTo(T0.plus(LockTiming.TTL));
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
            assertThat(locks.holderOf(QUESTION)).contains(new LockHolder(DANA, "Dana Cohen"));
        }

        @Test
        @DisplayName("the second caller is refused and told who is editing")
        void secondCallerIsRefused() {
            locks.acquire(DANA, QUESTION);

            LockResponse response = locks.acquire(RINA, QUESTION);

            assertThat(response.granted()).isFalse();
            assertThat(response.holder().displayName()).isEqualTo("Dana Cohen");
            assertThat(response.isFree()).isFalse();
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
        }

        @Test
        @DisplayName("asking twice while somebody else holds it stays refused, and stays quiet")
        void doubleAcquireByANonHolder() {
            sessions.attach(RINA, rinaSocket);
            locks.acquire(RINA, QUESTION);
            locks.acquire(DANA, QUESTION);
            gateway.clear();

            LockResponse again = locks.acquire(DANA, QUESTION);

            assertThat(again.granted()).isFalse();
            assertThat(gateway.pushes()).isEmpty();
        }

        @Test
        @DisplayName("re-acquiring your own lock extends it without telling anyone")
        void ownerRefreshesQuietly() {
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);
            gateway.clear();
            clock.advance(Duration.ofSeconds(10));

            LockResponse again = locks.acquire(DANA, QUESTION);

            assertThat(again.granted()).isTrue();
            assertThat(again.expiresAt()).isEqualTo(T0.plusSeconds(10).plus(LockTiming.TTL));
            assertThat(gateway.pushes())
                    .as("nothing changed hands, so there is nothing to announce")
                    .isEmpty();
        }

        @Test
        @DisplayName("two different entities are two independent locks")
        void locksArePerEntity() {
            locks.acquire(DANA, QUESTION);

            assertThat(locks.acquire(RINA, OTHER).granted()).isTrue();
            assertThat(locks.lockCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("an entity is required")
        void entityRequired() {
            assertThatNullPointerException().isThrownBy(() -> locks.acquire(DANA, null));
            assertThatNullPointerException().isThrownBy(() -> locks.renew(DANA, null));
            assertThatNullPointerException().isThrownBy(() -> locks.release(DANA, null));
        }

        @Test
        @DisplayName("an unresolvable holder degrades to a neutral name, it does not fail")
        void unknownHolderName() {
            EditLockService nameless = new EditLockService(gateway, DisplayNames.NONE, clock);

            assertThat(nameless.acquire(DANA, QUESTION).holder().displayName())
                    .isEqualTo(LockHolder.UNKNOWN_NAME);
        }
    }

    // ===================== Expiry & takeover =============================

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("a lapsed lock is taken over by the next caller")
        void takeoverAfterTtl() {
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.TTL);

            LockResponse response = locks.acquire(RINA, QUESTION);

            assertThat(response.granted()).isTrue();
            assertThat(locks.isHeldBy(QUESTION, RINA)).isTrue();
            assertThat(locks.isHeldBy(QUESTION, DANA)).isFalse();
        }

        @Test
        @DisplayName("one second before the TTL the lock still holds")
        void notAMomentEarly() {
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.TTL.minusSeconds(1));

            assertThat(locks.acquire(RINA, QUESTION).granted()).isFalse();
        }

        @Test
        @DisplayName("holderOf reports nobody once the TTL has passed")
        void holderOfIgnoresLapsedHolds() {
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.TTL);

            assertThat(locks.holderOf(QUESTION)).isEmpty();
            assertThat(locks.isHeldBy(QUESTION, DANA)).isFalse();
        }

        @Test
        @DisplayName("the sweeper lapses untouched locks and tells the watchers")
        void sweepExpiredNotifiesWatchers() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);
            gateway.clear();
            clock.advance(LockTiming.TTL);

            assertThat(locks.sweepExpired()).isEqualTo(1);

            assertThat(locks.lockCount()).isZero();
            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.EXPIRED);
            assertThat(gateway.kindsFor(DANA))
                    .as("the former holder is told too: if they are alive they have just lost it")
                    .containsExactly(LockChange.Kind.EXPIRED);
        }

        @Test
        @DisplayName("sweeping a live lock does nothing")
        void sweepLeavesLiveLocksAlone() {
            locks.acquire(DANA, QUESTION);

            assertThat(locks.sweepExpired()).isZero();
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
        }
    }

    // ===================== Renew =========================================

    @Nested
    @DisplayName("renew")
    class Renew {

        @Test
        @DisplayName("a heartbeat pushes the expiry out and keeps the lock alive")
        void renewExtends() {
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.HEARTBEAT);

            LockResponse response = locks.renew(DANA, QUESTION);

            assertThat(response.granted()).isTrue();
            assertThat(response.expiresAt())
                    .isEqualTo(T0.plus(LockTiming.HEARTBEAT).plus(LockTiming.TTL));
        }

        @Test
        @DisplayName("a lock survives two lost heartbeats in a row")
        void survivesLostHeartbeats() {
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.HEARTBEAT.multipliedBy(2));

            assertThat(locks.renew(DANA, QUESTION).granted())
                    .as("one dropped packet must never cost a teacher their editor")
                    .isTrue();
        }

        @Test
        @DisplayName("renewing somebody else's lock is refused, not an error")
        void renewingSomeoneElsesLock() {
            locks.acquire(DANA, QUESTION);

            LockResponse response = locks.renew(RINA, QUESTION);

            assertThat(response.granted()).isFalse();
            assertThat(response.holder().displayName()).isEqualTo("Dana Cohen");
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
        }

        @Test
        @DisplayName("a heartbeat that arrives after its own TTL does not resurrect the lock")
        void lateHeartbeatDoesNotResurrect() {
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);
            gateway.clear();
            clock.advance(LockTiming.TTL);

            LockResponse response = locks.renew(DANA, QUESTION);

            assertThat(response.isFree()).isTrue();
            assertThat(response.granted()).isFalse();
            assertThat(locks.lockCount()).isZero();
            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.EXPIRED);
        }

        @Test
        @DisplayName("renewing a lock nobody holds answers 'free', it does not take it")
        void renewOnAFreeEntity() {
            LockResponse response = locks.renew(DANA, QUESTION);

            assertThat(response.isFree()).isTrue();
            assertThat(locks.lockCount())
                    .as("a silent grab is exactly what the takeover prompt exists to prevent")
                    .isZero();
        }
    }

    // ===================== Release =======================================

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("the holder gives it back and the watchers hear about it")
        void releaseFreesAndAnnounces() {
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);
            gateway.clear();

            LockResponse response = locks.release(DANA, QUESTION);

            assertThat(response.isFree()).isTrue();
            assertThat(locks.lockCount()).isZero();
            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.RELEASED);
        }

        @Test
        @DisplayName("releasing a lock you do not hold changes nothing")
        void releasingSomeoneElsesLock() {
            locks.acquire(DANA, QUESTION);

            LockResponse response = locks.release(RINA, QUESTION);

            assertThat(response.granted()).isFalse();
            assertThat(response.holder().displayName()).isEqualTo("Dana Cohen");
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
        }

        @Test
        @DisplayName("releasing an entity nobody holds is a quiet no-op")
        void releasingAFreeEntity() {
            assertThat(locks.release(DANA, QUESTION).isFree()).isTrue();
        }

        @Test
        @DisplayName("releasing also stops watching, so no more pushes arrive")
        void releaseUnwatches() {
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);

            locks.release(RINA, QUESTION);

            assertThat(locks.watchersOf(QUESTION)).containsExactly(DANA);
        }
    }

    // ===================== Watchers ======================================

    @Nested
    @DisplayName("watchers")
    class Watchers {

        @Test
        @DisplayName("asking for a lock is what registers interest in it")
        void acquireRegistersAWatcher() {
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);

            assertThat(locks.watchersOf(QUESTION)).containsExactlyInAnyOrder(DANA, RINA);
            assertThat(locks.watchersOf(OTHER)).isEmpty();
        }

        @Test
        @DisplayName("a change never goes to somebody watching a different entity")
        void pushesAreScopedToTheEntity() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            sessions.attach(MAYA, mayaSocket);
            locks.acquire(MAYA, OTHER);
            locks.acquire(DANA, QUESTION);
            locks.acquire(RINA, QUESTION);
            gateway.clear();

            locks.release(DANA, QUESTION);

            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.RELEASED);
            assertThat(gateway.kindsFor(MAYA))
                    .as("Maya is editing a different question and must hear nothing")
                    .isEmpty();
        }

        @Test
        @DisplayName("the person who caused the change is not pushed their own news")
        void actorIsExcluded() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.TTL);
            gateway.clear();

            locks.acquire(RINA, QUESTION);

            assertThat(gateway.kindsFor(RINA))
                    .as("Rina already has the answer in her response")
                    .isEmpty();
            assertThat(gateway.kindsFor(DANA)).containsExactly(LockChange.Kind.ACQUIRED);
        }

        @Test
        @DisplayName("a refusal tells nobody: the caller has the answer and nothing changed")
        void refusalRaisesNoPush() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            gateway.clear();

            locks.acquire(RINA, QUESTION);

            assertThat(gateway.pushes()).isEmpty();
        }

        @Test
        @DisplayName("an acquisition push names the new holder")
        void acquisitionPushNamesTheHolder() {
            sessions.attach(DANA, danaSocket);
            locks.acquire(DANA, QUESTION);
            clock.advance(LockTiming.TTL);
            gateway.clear();

            locks.acquire(RINA, QUESTION);

            LockChange change = gateway.changesFor(DANA).get(0);
            assertThat(change.kind()).isEqualTo(LockChange.Kind.ACQUIRED);
            assertThat(change.holder()).isEqualTo(new LockHolder(RINA, "Rina Barak"));
        }
    }

    // ===================== Disconnect (E18.3) ============================

    @Nested
    @DisplayName("disconnect")
    class Disconnect {

        @BeforeEach
        void hook() {
            locks.attachTo(sessions);
        }

        @Test
        @DisplayName("a dropped socket releases every lock that session held")
        void droppedSocketReleases() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            locks.acquire(DANA, QUESTION);
            locks.acquire(DANA, OTHER);
            locks.acquire(RINA, QUESTION);
            gateway.clear();

            // What HSTSServer.clientDisconnected does when the TCP connection dies.
            sessions.detach(danaSocket);

            assertThat(locks.lockCount()).isZero();
            assertThat(gateway.kindsFor(RINA)).containsExactly(LockChange.Kind.RELEASED);
        }

        @Test
        @DisplayName("a polite logout releases them by the very same path")
        void logoutReleases() {
            sessions.attach(DANA, danaSocket);
            locks.acquire(DANA, QUESTION);

            // What AuthService.logout does.
            sessions.detachUser(DANA);

            assertThat(locks.lockCount()).isZero();
            assertThat(locks.holderOf(QUESTION)).isEmpty();
        }

        @Test
        @DisplayName("a disconnect also stops that user watching anything")
        void disconnectStopsWatching() {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);
            locks.acquire(RINA, QUESTION);
            locks.acquire(DANA, QUESTION);

            sessions.detach(danaSocket);

            assertThat(locks.watchersOf(QUESTION)).containsExactly(RINA);
        }

        @Test
        @DisplayName("another user's locks survive a disconnect")
        void othersAreUntouched() {
            sessions.attach(DANA, danaSocket);
            locks.acquire(RINA, OTHER);
            locks.acquire(DANA, QUESTION);

            sessions.detach(danaSocket);

            assertThat(locks.isHeldBy(OTHER, RINA)).isTrue();
            assertThat(locks.lockCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("releasing a user who holds nothing is a no-op")
        void nothingToRelease() {
            assertThat(locks.releaseAllHeldBy(MAYA)).isZero();
        }

        @Test
        @DisplayName("the hook needs a session manager")
        void hookRequiresSessions() {
            assertThatNullPointerException().isThrownBy(() -> locks.attachTo(null));
        }
    }

    // ===================== Verbs =========================================

    @Nested
    @DisplayName("verbs")
    class Verbs {

        private MessageRouter router;

        @BeforeEach
        void register() {
            router = new MessageRouter(sessions);
            locks.registerOn(router);
        }

        @Test
        @DisplayName("all three verbs are registered and none is reachable anonymously")
        void registeredAsAuthenticated() {
            for (Verb verb : List.of(Verb.LOCK_ACQUIRE, Verb.LOCK_RENEW, Verb.LOCK_RELEASE)) {
                assertThat(router.isRegistered(verb)).isTrue();
                assertThat(router.isOpen(verb)).isFalse();
                assertThat(router.route(Message.request(verb, new LockRequest(QUESTION)),
                        CallerContext.anonymous(danaSocket)).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("acquire, renew and release all answer with a LockResponse")
        void theHappyPath() {
            LockResponse acquired = (LockResponse) route(Verb.LOCK_ACQUIRE, DANA).getPayload();
            LockResponse renewed = (LockResponse) route(Verb.LOCK_RENEW, DANA).getPayload();
            LockResponse released = (LockResponse) route(Verb.LOCK_RELEASE, DANA).getPayload();

            assertThat(acquired.granted()).isTrue();
            assertThat(renewed.granted()).isTrue();
            assertThat(released.isFree()).isTrue();
        }

        @Test
        @DisplayName("the caller comes from the session, so a payload cannot impersonate")
        void identityComesFromTheSession() {
            route(Verb.LOCK_ACQUIRE, DANA);

            // Rina asks for the same entity on her own connection. Nothing she can put
            // in the payload names Dana, because the payload has no identity field.
            LockResponse refused = (LockResponse) route(Verb.LOCK_ACQUIRE, RINA).getPayload();

            assertThat(refused.granted()).isFalse();
            assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
        }

        @Test
        @DisplayName("a malformed payload is a validation error on every lock verb")
        void malformedPayload() {
            for (Verb verb : List.of(Verb.LOCK_ACQUIRE, Verb.LOCK_RENEW, Verb.LOCK_RELEASE)) {
                Message response = router.route(Message.request(verb, "not a dto"),
                        CallerContext.authenticated(danaSocket, DANA, null));

                assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
                assertThat(response.errorMessage()).isEqualTo(EditLockService.MALFORMED_REQUEST);
            }
        }

        @Test
        @DisplayName("the router must be supplied")
        void registerRequiresRouter() {
            assertThatNullPointerException().isThrownBy(() -> locks.registerOn(null));
        }

        private Message route(Verb verb, long callerId) {
            return router.route(Message.request(verb, new LockRequest(QUESTION)),
                    CallerContext.authenticated(danaSocket, callerId, null));
        }
    }

    @Test
    @DisplayName("the service refuses to be built without its collaborators")
    void requiresCollaborators() {
        assertThatNullPointerException().isThrownBy(() -> new EditLockService(null, NAMES));
        assertThatNullPointerException().isThrownBy(() -> new EditLockService(gateway, null));
        assertThatNullPointerException().isThrownBy(() -> new EditLockService(gateway, NAMES, null));
    }

    /**
     * A {@link PushGateway} that also remembers what it delivered, so a test can
     * assert who was told what without unpicking mocked sockets.
     */
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
