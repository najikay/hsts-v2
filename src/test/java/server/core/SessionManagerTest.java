package server.core;

import common.dto.auth.Role;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.core.SessionManager.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SessionManager} (E3.3).
 *
 * <p>Two requirements are load-bearing here and both are defended by tests:
 * <b>T-16 / F1.3</b> — a user cannot hold two concurrent sessions, including
 * when two logins race on different threads — and the cleanup contract, since
 * every disconnect hook (edit locks, attempt state) is built on "fires exactly
 * once, even if another hook throws".
 */
@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private SessionManager sessions;

    @Mock
    private ConnectionToClient firstConnection;

    @Mock
    private ConnectionToClient secondConnection;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
    }

    @Nested
    @DisplayName("attach")
    class Attach {

        @Test
        @DisplayName("binds a user to a connection in both directions")
        void attachBindsBothDirections() {
            assertThat(sessions.attach(ALICE, Role.TEACHER, firstConnection)).isTrue();

            assertThat(sessions.connectionOf(ALICE)).contains(firstConnection);
            assertThat(sessions.userIdOf(firstConnection)).contains(ALICE);
            assertThat(sessions.sessionOf(firstConnection)).contains(new Session(ALICE, Role.TEACHER));
            assertThat(sessions.isOnline(ALICE)).isTrue();
            assertThat(sessions.onlineCount()).isEqualTo(1);
            assertThat(sessions.onlineUserIds()).containsExactly(ALICE);
        }

        @Test
        @DisplayName("the role-less overload works too (pre-E5 shape)")
        void attachWithoutARole() {
            assertThat(sessions.attach(ALICE, firstConnection)).isTrue();

            assertThat(sessions.sessionOf(firstConnection)).contains(new Session(ALICE, null));
        }

        @Test
        @DisplayName("T-16: a second connection for the same user is rejected")
        void duplicateLoginIsRejected() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThat(sessions.attach(ALICE, Role.STUDENT, secondConnection)).isFalse();

            // The original session is untouched — the newcomer is the one refused.
            assertThat(sessions.connectionOf(ALICE)).contains(firstConnection);
            assertThat(sessions.userIdOf(secondConnection)).isEmpty();
            assertThat(sessions.onlineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("re-attaching the same user on the same connection is idempotent")
        void reattachingTheSameConnectionIsIdempotent() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThat(sessions.attach(ALICE, Role.STUDENT, firstConnection)).isTrue();
            assertThat(sessions.onlineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a connection cannot carry a second user")
        void connectionCannotHostTwoUsers() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThat(sessions.attach(BOB, Role.TEACHER, firstConnection)).isFalse();
            assertThat(sessions.isOnline(BOB)).isFalse();
        }

        @Test
        @DisplayName("different users on different connections coexist")
        void differentUsersCoexist() {
            assertThat(sessions.attach(ALICE, Role.STUDENT, firstConnection)).isTrue();
            assertThat(sessions.attach(BOB, Role.TEACHER, secondConnection)).isTrue();

            assertThat(sessions.onlineUserIds()).containsExactlyInAnyOrder(ALICE, BOB);
        }

        @Test
        @DisplayName("a null connection is refused outright")
        void nullConnectionIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> sessions.attach(ALICE, null));
        }
    }

    @Nested
    @DisplayName("detach")
    class Detach {

        @Test
        @DisplayName("frees the session in both directions and returns the user")
        void detachClearsEverything() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThat(sessions.detach(firstConnection)).contains(ALICE);

            assertThat(sessions.isOnline(ALICE)).isFalse();
            assertThat(sessions.userIdOf(firstConnection)).isEmpty();
            assertThat(sessions.connectionOf(ALICE)).isEmpty();
            assertThat(sessions.onlineCount()).isZero();
        }

        @Test
        @DisplayName("a user can sign in again once the socket dropped (F1.3)")
        void detachFreesTheUserForANewLogin() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);
            sessions.detach(firstConnection);

            assertThat(sessions.attach(ALICE, Role.STUDENT, secondConnection)).isTrue();
        }

        @Test
        @DisplayName("detaching an unknown or null connection is a quiet no-op")
        void detachingUnknownConnection() {
            assertThat(sessions.detach(firstConnection)).isEmpty();
            assertThat(sessions.detach(null)).isEmpty();
        }

        @Test
        @DisplayName("looking up a null connection is empty, not an NPE (OCSF hands us one on teardown)")
        void lookupOfNullConnection() {
            assertThat(sessions.sessionOf(null)).isEmpty();
            assertThat(sessions.userIdOf(null)).isEmpty();
            assertThat(sessions.connectionOf(ALICE)).isEmpty();
        }

        @Test
        @DisplayName("detachUser() forces a logout by id")
        void detachUserById() {
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThat(sessions.detachUser(ALICE)).isTrue();
            assertThat(sessions.detachUser(ALICE)).isFalse();
            assertThat(sessions.userIdOf(firstConnection)).isEmpty();
        }
    }

    @Nested
    @DisplayName("disconnect hooks")
    class Hooks {

        @Test
        @DisplayName("fire exactly once per ended session, with user and connection")
        void hooksFireOnce() {
            List<String> fired = new ArrayList<>();
            sessions.addDisconnectHook((userId, connection) -> fired.add(userId + "@" + (connection == firstConnection)));
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            sessions.detach(firstConnection);
            sessions.detach(firstConnection); // already gone — must not fire again

            assertThat(fired).containsExactly(ALICE + "@true");
        }

        @Test
        @DisplayName("also fire on a forced logout")
        void hooksFireOnForcedLogout() {
            AtomicInteger fired = new AtomicInteger();
            sessions.addDisconnectHook((userId, connection) -> fired.incrementAndGet());
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            sessions.detachUser(ALICE);

            assertThat(fired).hasValue(1);
        }

        @Test
        @DisplayName("all hooks run even when one throws")
        void oneBadHookDoesNotStopTheOthers() {
            AtomicInteger survivor = new AtomicInteger();
            sessions.addDisconnectHook((userId, connection) -> {
                throw new IllegalStateException("cleanup exploded");
            });
            sessions.addDisconnectHook((userId, connection) -> survivor.incrementAndGet());
            sessions.attach(ALICE, Role.STUDENT, firstConnection);

            assertThatCode(() -> sessions.detach(firstConnection)).doesNotThrowAnyException();

            assertThat(survivor).hasValue(1);
        }

        @Test
        @DisplayName("hooks can be removed, and null is refused")
        void hooksAreRemovable() {
            AtomicInteger fired = new AtomicInteger();
            SessionManager.DisconnectHook hook = (userId, connection) -> fired.incrementAndGet();
            sessions.addDisconnectHook(hook);

            assertThat(sessions.removeDisconnectHook(hook)).isTrue();
            assertThat(sessions.removeDisconnectHook(hook)).isFalse();
            assertThatNullPointerException().isThrownBy(() -> sessions.addDisconnectHook(null));

            sessions.attach(ALICE, Role.STUDENT, firstConnection);
            sessions.detach(firstConnection);
            assertThat(fired).hasValue(0);
        }

        @Test
        @DisplayName("no hook fires when nothing was attached")
        void noHookForAnUnknownConnection() {
            AtomicInteger fired = new AtomicInteger();
            sessions.addDisconnectHook((userId, connection) -> fired.incrementAndGet());

            sessions.detach(firstConnection);

            assertThat(fired).hasValue(0);
        }
    }

    @Test
    @DisplayName("T-16 under load: 16 threads racing to attach the same user produce exactly one session")
    void concurrentAttachHasExactlyOneWinner() throws Exception {
        int threads = 16;
        List<ConnectionToClient> connections = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            connections.add(org.mockito.Mockito.mock(ConnectionToClient.class));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try {
            for (ConnectionToClient connection : connections) {
                pool.submit(() -> {
                    start.await();
                    if (sessions.attach(ALICE, Role.STUDENT, connection)) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners).hasValue(1);
        assertThat(sessions.onlineCount()).isEqualTo(1);
        assertThat(sessions.connectionOf(ALICE)).isPresent();
    }

    @Test
    @DisplayName("onlineUserIds() is a snapshot, not a live view")
    void onlineUserIdsIsASnapshot() {
        sessions.attach(ALICE, Role.STUDENT, firstConnection);
        var snapshot = sessions.onlineUserIds();

        sessions.detach(firstConnection);

        assertThat(snapshot).containsExactly(ALICE);
        assertThat(sessions.onlineUserIds()).isEmpty();
    }
}
