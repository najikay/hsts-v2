package server.features.locks;

import common.dto.bank.Question;
import common.dto.bank.QuestionUpdate;
import common.dto.auth.Role;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.lock.LockTiming;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Status;
import common.protocol.Verb;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.db.QuestionDAO;
import server.features.bank.LegacyQuestionHandlers;
import server.realtime.PushGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Two clients, one question (E18.6/E18.7).
 *
 * <p>The scenarios the epic is actually for, played end to end through the real
 * {@link MessageRouter}, the real {@link EditLockService} and the real
 * {@link PushGateway}: Dana opens a question, Rina opens the same one and is
 * told live who has it, Dana's client dies, Rina takes over, and a stale write
 * is refused. Only three things are doubles — the two sockets and the DAO —
 * because everything else is exactly what the server runs.
 *
 * <p>No TCP and no sleeping: the clients are {@code ConnectionToClient} mocks
 * that record what the server wrote them, and the TTL is crossed by moving a
 * {@link MutableClock}. That makes a concurrency suite deterministic, which is
 * the only kind worth having in CI.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LockConcurrencyIntegrationTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final EntityRef QUESTION = EntityRef.question(42);
    private static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

    @Mock
    private ConnectionToClient danaClient;
    @Mock
    private ConnectionToClient rinaClient;
    @Mock
    private QuestionDAO questionDAO;

    private MutableClock clock;
    private SessionManager sessions;
    private MessageRouter router;
    private EditLockService locks;
    private final List<Message> toDana = new ArrayList<>();
    private final List<Message> toRina = new ArrayList<>();

    @BeforeEach
    void bootServer() throws Exception {
        clock = new MutableClock(T0);
        sessions = new SessionManager();
        router = new MessageRouter(sessions);

        DisplayNames names = userId -> userId == DANA ? Optional.of("Dana Cohen")
                : userId == RINA ? Optional.of("Rina Barak") : Optional.empty();
        locks = new EditLockService(new PushGateway(sessions), names, clock);
        locks.registerOn(router);
        locks.attachTo(sessions);
        new LegacyQuestionHandlers(questionDAO).registerOn(router);

        // Record what each client's socket receives, the way a real client would.
        recordInto(danaClient, toDana);
        recordInto(rinaClient, toRina);

        sessions.attach(DANA, danaClient);
        sessions.attach(RINA, rinaClient);
    }

    @Test
    @DisplayName("the second client sees the lock live, and is named the holder")
    void secondClientIsRefusedAndTold() {
        LockResponse danas = acquire(DANA);
        LockResponse rinas = acquire(RINA);

        assertThat(danas.granted()).isTrue();
        assertThat(rinas.granted()).isFalse();
        assertThat(rinas.holder().displayName())
                .as("Rina's banner has to name a person, not 'somebody'")
                .isEqualTo("Dana Cohen");
        assertThat(pushesTo(toRina)).isEmpty();
    }

    @Test
    @DisplayName("when the holder releases, the waiting client hears about it without asking")
    void releaseReachesTheWaitingClientLive() {
        acquire(DANA);
        acquire(RINA);
        toRina.clear();

        send(Verb.LOCK_RELEASE, DANA, danaClient);

        List<LockChange> pushed = pushesTo(toRina);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).kind()).isEqualTo(LockChange.Kind.RELEASED);
        assertThat(pushed.get(0).entity()).isEqualTo(QUESTION);
        assertThat(pushesTo(toDana))
                .as("no client is pushed the news it just caused")
                .isEmpty();
    }

    @Test
    @DisplayName("a takeover after the TTL is granted, and the old holder is told")
    void expiryTakeover() {
        acquire(DANA);
        acquire(RINA);
        toDana.clear();
        toRina.clear();

        // Dana's laptop suspends: no heartbeat for longer than the TTL.
        clock.advance(LockTiming.TTL.plusSeconds(1));
        LockResponse takeover = acquire(RINA);

        assertThat(takeover.granted()).isTrue();
        assertThat(takeover.holder().displayName()).isEqualTo("Rina Barak");
        List<LockChange> pushed = pushesTo(toDana);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).kind()).isEqualTo(LockChange.Kind.ACQUIRED);
        assertThat(pushed.get(0).holder().userId()).isEqualTo(RINA);
    }

    @Test
    @DisplayName("the scheduled sweep tells a waiting client the moment the lock lapses")
    void sweepTellsTheWaitingClient() {
        acquire(DANA);
        acquire(RINA);
        toRina.clear();

        clock.advance(LockTiming.TTL);
        // What HSTSServer's sweeper thread runs; nobody has touched the entity.
        assertThat(locks.sweepExpired()).isEqualTo(1);

        assertThat(pushesTo(toRina))
                .extracting(LockChange::kind)
                .containsExactly(LockChange.Kind.EXPIRED);
    }

    @Test
    @DisplayName("a dropped socket releases the lock and the other client is told (E18.7)")
    void disconnectReleasesAndAnnounces() {
        acquire(DANA);
        acquire(RINA);
        toRina.clear();

        // Exactly what HSTSServer.clientDisconnected does.
        sessions.detach(danaClient);

        assertThat(locks.holderOf(QUESTION)).isEmpty();
        assertThat(pushesTo(toRina))
                .extracting(LockChange::kind)
                .containsExactly(LockChange.Kind.RELEASED);
        assertThat(acquire(RINA).granted())
                .as("and the entity is immediately editable by the other client")
                .isTrue();
    }

    @Test
    @DisplayName("a heartbeat keeps the lock across more than one TTL of editing")
    void heartbeatKeepsTheLock() {
        acquire(DANA);

        for (int beat = 0; beat < 6; beat++) {
            clock.advance(LockTiming.HEARTBEAT);
            assertThat(renew(DANA).granted()).isTrue();
        }

        assertThat(acquire(RINA).granted())
                .as("Dana has been editing for over a minute and still holds it")
                .isFalse();
    }

    @Test
    @DisplayName("a stale write is refused with CONFLICT, and the row is not touched (E18.4)")
    void staleWriteIsRejected() {
        when(questionDAO.updateGuarded(org.mockito.ArgumentMatchers.any()))
                .thenReturn(QuestionDAO.UpdateOutcome.STALE);

        // Rina saved first; Dana's client still holds the values it read.
        Message response = router.route(
                Message.request(Verb.UPDATE_QUESTION,
                        new QuestionUpdate(new Question(42, "Dana's text", "Dana's answer"),
                                "the text Dana loaded", "the answer Dana loaded")),
                CallerContext.authenticated(danaClient, DANA, Role.TEACHER));

        assertThat(response.isError()).isTrue();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(response.errorMessage())
                .as("the message has to say what the user can do next")
                .isEqualTo(LegacyQuestionHandlers.STALE_WRITE_MESSAGE)
                .contains("Reload");
        org.mockito.Mockito.verify(questionDAO, org.mockito.Mockito.never()).getAll();
    }

    @Test
    @DisplayName("a guarded write on an untouched row still saves normally")
    void freshWriteStillSaves() {
        when(questionDAO.updateGuarded(org.mockito.ArgumentMatchers.any()))
                .thenReturn(QuestionDAO.UpdateOutcome.SAVED);
        when(questionDAO.getAll()).thenReturn(List.of(new Question(42, "saved", "answer")));

        Message response = router.route(
                Message.request(Verb.UPDATE_QUESTION,
                        new QuestionUpdate(new Question(42, "saved", "answer"), "before", "before")),
                CallerContext.authenticated(danaClient, DANA, Role.TEACHER));

        assertThat(response.isOk()).isTrue();
        assertThat(response.getPayload()).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("locks and pushes stay separate per entity across two live clients")
    void twoEntitiesDoNotInterfere() {
        EntityRef other = EntityRef.question(43);
        acquire(DANA);
        router.route(Message.request(Verb.LOCK_ACQUIRE, new LockRequest(other)),
                CallerContext.authenticated(rinaClient, RINA, Role.TEACHER));
        toDana.clear();
        toRina.clear();

        send(Verb.LOCK_RELEASE, RINA, rinaClient);

        assertThat(pushesTo(toDana))
                .as("Dana is editing a different question")
                .isEmpty();
        assertThat(locks.isHeldBy(QUESTION, DANA)).isTrue();
    }

    // ===================== Helpers =======================================

    @Test
    @DisplayName("P-5 follow-up: a student is refused every lock verb outright")
    void studentsAreRefusedLockVerbs() {
        Message answer = router.route(Message.request(Verb.LOCK_ACQUIRE, new LockRequest(QUESTION)),
                CallerContext.authenticated(danaClient, 9999L, Role.STUDENT));

        // Students never edit, so they never hold a lock - and without the gate a
        // student could pin any entity read-only for its whole TTL, repeatedly.
        assertThat(answer.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(locks.lockCount()).isZero();
    }

    private LockResponse acquire(long userId) {
        return (LockResponse) send(Verb.LOCK_ACQUIRE, userId, connectionOf(userId)).getPayload();
    }

    private LockResponse renew(long userId) {
        return (LockResponse) send(Verb.LOCK_RENEW, userId, connectionOf(userId)).getPayload();
    }

    private Message send(Verb verb, long userId, ConnectionToClient connection) {
        // TEACHER: since the P-5 fix the lock verbs are role-gated, and these
        // tests are about concurrency between two people who may hold locks.
        return router.route(Message.request(verb, new LockRequest(QUESTION)),
                CallerContext.authenticated(connection, userId, Role.TEACHER));
    }

    private ConnectionToClient connectionOf(long userId) {
        return userId == DANA ? danaClient : rinaClient;
    }

    /** @return the lock changes this client's socket actually received. */
    private static List<LockChange> pushesTo(List<Message> inbox) {
        return inbox.stream()
                .filter(message -> message.getStatus() == Status.PUSH
                        && message.getVerb() == Verb.PUSH_LOCK_CHANGED)
                .map(message -> (LockChange) message.getPayload())
                .toList();
    }

    private static void recordInto(ConnectionToClient connection, List<Message> inbox) throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            Object written = invocation.getArgument(0);
            if (written instanceof Message message) {
                inbox.add(message);
            }
            return null;
        }).when(connection).sendToClient(org.mockito.ArgumentMatchers.any());
    }
}
