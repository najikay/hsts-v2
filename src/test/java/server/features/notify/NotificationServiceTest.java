package server.features.notify;

import common.dto.auth.LoginResult;
import common.dto.notify.MarkReadRequest;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsGetRequest;
import common.dto.notify.NotificationsPage;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.core.CallerContext;
import server.core.MessageRouter;
import server.core.SessionManager;
import server.features.auth.AuthService;
import server.features.auth.InMemoryUserDirectory;
import server.realtime.PushGateway;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for the notification service (E17.1/E17.3/E17.6).
 *
 * <p>The suite is organised around the four rules in the service's javadoc, and
 * the two that are defence-critical get the most attention: <b>routing</b> (a
 * notification reaching anyone it was not addressed to is a data leak, so every
 * happy-path test also asserts who did <i>not</i> get it) and <b>ownership</b>
 * (a user naming somebody else's notification id must change nothing and learn
 * nothing).
 *
 * <p>No sockets: {@link PushGateway} is driven through a real
 * {@link SessionManager} with mocked {@code ConnectionToClient}s, which is
 * exactly what the running server does minus the TCP.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final long DANA = 1001L;
    private static final long RINA = 1002L;
    private static final long MAYA = 2001L;
    private static final Instant NOW = Instant.parse("2026-08-19T09:00:00Z");

    @Mock
    private ConnectionToClient danaSocket;
    @Mock
    private ConnectionToClient rinaSocket;

    private SessionManager sessions;
    private InMemoryNotificationStore store;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        store = new InMemoryNotificationStore();
        service = new NotificationService(store, new PushGateway(sessions),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ===================== Emitting ======================================

    @Nested
    @DisplayName("notify")
    class Emitting {

        @Test
        @DisplayName("persists one row per recipient and pushes to the online ones")
        void persistsThenPushes() throws IOException {
            sessions.attach(DANA, danaSocket);

            Notifier.Outcome outcome = service.notify(List.of(DANA, RINA),
                    NotificationType.APPROVAL_REQUESTED, "Exam waiting", "Dana submitted Midterm.",
                    NavRef.to("approvals", 55L));

            assertThat(outcome.persisted()).isEqualTo(2);
            assertThat(outcome.pushed()).isEqualTo(1);
            assertThat(outcome.offline()).isEqualTo(1);
            assertThat(outcome.reachedAnyoneLive()).isTrue();
            assertThat(store.size(DANA)).isEqualTo(1);
            assertThat(store.size(RINA))
                    .as("being offline must never mean being skipped")
                    .isEqualTo(1);
            verify(danaSocket).sendToClient(any(Message.class));
            verify(rinaSocket, never()).sendToClient(any());
        }

        @Test
        @DisplayName("the push carries the recipient's own row id, ready to mark read")
        void pushCarriesTheRow() throws IOException {
            sessions.attach(DANA, danaSocket);

            service.notify(List.of(DANA), NotificationType.GRADE_PUBLISHED,
                    "Your grade is ready", "Midterm.", NavRef.to("grades", 7L));

            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            verify(danaSocket).sendToClient(sent.capture());
            Message push = (Message) sent.getValue();
            assertThat(push.getStatus()).isEqualTo(Status.PUSH);
            assertThat(push.getVerb()).isEqualTo(Verb.PUSH_NOTIFICATION);
            NotificationDto row = (NotificationDto) push.getPayload();
            assertThat(row.id()).isEqualTo(store.listRecent(DANA, 1).get(0).id());
            assertThat(row.createdAt()).isEqualTo(NOW);
            assertThat(row.isUnread()).isTrue();
        }

        @Test
        @DisplayName("nobody outside the recipient list is touched (E17.6, negative)")
        void neverReachesAnyoneElse() throws IOException {
            sessions.attach(DANA, danaSocket);
            sessions.attach(RINA, rinaSocket);

            service.notify(List.of(RINA), NotificationType.APPROVAL_REJECTED,
                    "Exam sent back", "Reason: too short.", NavRef.none());

            assertThat(store.size(DANA)).isZero();
            assertThat(service.unreadCount(DANA)).isZero();
            assertThat(service.page(DANA, 10).items()).isEmpty();
            verify(danaSocket, never()).sendToClient(any());
            verify(rinaSocket).sendToClient(any(Message.class));
        }

        @Test
        @DisplayName("duplicate recipients collapse to one row each")
        void duplicatesCollapse() {
            Notifier.Outcome outcome = service.notify(List.of(DANA, DANA, DANA),
                    NotificationType.TIME_EXTENDED, "Extra time added", "", NavRef.none());

            assertThat(outcome.persisted()).isEqualTo(1);
            assertThat(store.size(DANA)).isEqualTo(1);
        }

        @Test
        @DisplayName("no recipients is a no-op, not an error")
        void emptyRecipientsAreFine() {
            assertThat(service.notify(List.of(), NotificationType.TIME_EXTENDED, "t", "", NavRef.none()))
                    .isEqualTo(Notifier.Outcome.NONE);
            assertThat(service.notify(null, NotificationType.TIME_EXTENDED, "t", "", NavRef.none()))
                    .isEqualTo(Notifier.Outcome.NONE);
        }

        @Test
        @DisplayName("a dead socket costs a delivery, never the persisted row")
        void deliveryFailureDoesNotLoseTheRow() throws IOException {
            sessions.attach(DANA, danaSocket);
            doThrow(new IOException("broken pipe")).when(danaSocket).sendToClient(any());

            Notifier.Outcome outcome = service.notify(List.of(DANA), NotificationType.GRADE_PUBLISHED,
                    "Your grade is ready", "", NavRef.none());

            assertThat(outcome.persisted()).isEqualTo(1);
            assertThat(outcome.pushed()).isZero();
            assertThat(service.unreadCount(DANA))
                    .as("the row is still waiting in the bell")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the catalog form is the same call with the sentence filled in")
        void catalogDraftShortcut() {
            service.notifyUser(RINA, NotificationCatalog.approvalRequested("Midterm", "Dana Cohen", 55L));

            NotificationDto row = service.page(RINA, 10).items().get(0);
            assertThat(row.type()).isEqualTo(NotificationType.APPROVAL_REQUESTED);
            assertThat(row.title()).isEqualTo("Exam waiting for your approval");
            assertThat(row.ref()).isEqualTo(NavRef.to(NotificationCatalog.ROUTE_APPROVALS, 55L));
        }
    }

    // ===================== Reading =======================================

    @Nested
    @DisplayName("reading and marking")
    class Reading {

        @Test
        @DisplayName("the page carries the newest rows and the full unread count")
        void pageShape() {
            for (int i = 0; i < 4; i++) {
                service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "n" + i, "", NavRef.none());
            }

            NotificationsPage page = service.page(DANA, 2);

            assertThat(page.items()).hasSize(2);
            assertThat(page.unreadCount())
                    .as("the count is the whole table, not the page")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("marking one read drops the count by one and returns the fresh page")
        void markOneRead() {
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "a", "", NavRef.none());
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "b", "", NavRef.none());
            long id = service.page(DANA, 10).items().get(0).id();

            NotificationsPage page = service.markRead(DANA, id, 10);

            assertThat(page.unreadCount()).isEqualTo(1);
            assertThat(page.items()).anySatisfy(row -> {
                assertThat(row.id()).isEqualTo(id);
                assertThat(row.readAt()).isEqualTo(NOW);
            });
        }

        @Test
        @DisplayName("mark-all clears the badge and nothing else")
        void markAllRead() {
            service.notify(List.of(DANA, RINA), NotificationType.TIME_EXTENDED, "a", "", NavRef.none());

            NotificationsPage page = service.markAllRead(DANA, 10);

            assertThat(page.unreadCount()).isZero();
            assertThat(service.unreadCount(RINA))
                    .as("mark-all is one user's gesture, not a global one")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("naming another user's notification id changes nothing and reveals nothing")
        void markReadCannotCrossUsers() {
            service.notify(List.of(RINA), NotificationType.GRADE_PUBLISHED, "hers", "", NavRef.none());
            long rinas = service.page(RINA, 10).items().get(0).id();

            NotificationsPage page = service.markRead(DANA, rinas, 10);

            assertThat(page.items())
                    .as("Dana learns nothing about a row that is not hers")
                    .isEmpty();
            assertThat(page.unreadCount()).isZero();
            assertThat(service.unreadCount(RINA))
                    .as("and Rina's row is untouched")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an unknown id is answered normally, not with an error")
        void unknownIdIsQuiet() {
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "a", "", NavRef.none());

            assertThat(service.markRead(DANA, 999_999L, 10).unreadCount()).isEqualTo(1);
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
            service.registerOn(router);
        }

        @Test
        @DisplayName("both verbs are registered and neither is reachable without a session")
        void registeredAsAuthenticated() {
            assertThat(router.isRegistered(Verb.NOTIFICATIONS_GET)).isTrue();
            assertThat(router.isRegistered(Verb.NOTIFICATIONS_MARK_READ)).isTrue();
            assertThat(router.isOpen(Verb.NOTIFICATIONS_GET)).isFalse();
            assertThat(router.isOpen(Verb.NOTIFICATIONS_MARK_READ)).isFalse();

            Message refused = router.route(Message.request(Verb.NOTIFICATIONS_GET, null),
                    CallerContext.anonymous(danaSocket));

            assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("GET answers the caller's own page, never anyone else's")
        void getIsScopedToTheCaller() {
            service.notify(List.of(RINA), NotificationType.GRADE_PUBLISHED, "hers", "", NavRef.none());
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "mine", "", NavRef.none());

            NotificationsPage page = (NotificationsPage) route(Verb.NOTIFICATIONS_GET,
                    NotificationsGetRequest.defaults(), DANA).getPayload();

            assertThat(page.items()).extracting(NotificationDto::title).containsExactly("mine");
            assertThat(page.unreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("GET without a payload still opens the bell")
        void getToleratesAMissingPayload() {
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "mine", "", NavRef.none());

            Message response = route(Verb.NOTIFICATIONS_GET, null, DANA);

            assertThat(response.isOk()).isTrue();
            assertThat(((NotificationsPage) response.getPayload()).size()).isEqualTo(1);
        }

        @Test
        @DisplayName("MARK_READ marks one row and answers with the refreshed page")
        void markReadVerb() {
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "mine", "", NavRef.none());
            long id = service.page(DANA, 10).items().get(0).id();

            NotificationsPage page = (NotificationsPage) route(Verb.NOTIFICATIONS_MARK_READ,
                    MarkReadRequest.one(id), DANA).getPayload();

            assertThat(page.unreadCount()).isZero();
        }

        @Test
        @DisplayName("MARK_READ with mark-all clears the caller's badge")
        void markAllVerb() {
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "a", "", NavRef.none());
            service.notify(List.of(DANA), NotificationType.TIME_EXTENDED, "b", "", NavRef.none());

            NotificationsPage page = (NotificationsPage) route(Verb.NOTIFICATIONS_MARK_READ,
                    MarkReadRequest.markAll(), DANA).getPayload();

            assertThat(page.unreadCount()).isZero();
            assertThat(page.items()).allSatisfy(row -> assertThat(row.isUnread()).isFalse());
        }

        @Test
        @DisplayName("MARK_READ pointed at another user's id is refused by having no effect")
        void markReadVerbCannotCrossUsers() {
            service.notify(List.of(RINA), NotificationType.GRADE_PUBLISHED, "hers", "", NavRef.none());
            long rinas = service.page(RINA, 10).items().get(0).id();

            Message response = route(Verb.NOTIFICATIONS_MARK_READ, MarkReadRequest.one(rinas), DANA);

            assertThat(response.isOk())
                    .as("an ERROR here would confirm that the id exists")
                    .isTrue();
            assertThat(service.unreadCount(RINA)).isEqualTo(1);
        }

        @Test
        @DisplayName("a malformed MARK_READ payload is a validation error, not a crash")
        void markReadRejectsGarbage() {
            Message response = route(Verb.NOTIFICATIONS_MARK_READ, "not a dto", DANA);

            assertThat(response.getErrorCode()).isEqualTo(ErrorCode.VALIDATION);
            assertThat(response.errorMessage()).isEqualTo(NotificationService.MALFORMED_REQUEST);
        }

        private Message route(Verb verb, Object payload, long callerId) {
            return router.route(Message.request(verb, payload),
                    CallerContext.authenticated(danaSocket, callerId, null));
        }
    }

    // ===================== Offline → next login (E17.6) ==================

    @Nested
    @DisplayName("an offline recipient sees it at their next login")
    class OfflineThenLogin {

        @Test
        @DisplayName("the unread count travels in LoginResult and the list is waiting")
        void unreadCountArrivesWithTheLoginAnswer() {
            InMemoryUserDirectory directory = new InMemoryUserDirectory();
            AuthService auth = new AuthService(directory, sessions,
                    Clock.fixed(NOW, ZoneOffset.UTC), service::unreadCount);

            // Rina is nowhere near a client when the notification is raised.
            service.notifyUser(RINA, NotificationCatalog.approvalRequested("Midterm", "Dana Cohen", 55L));
            service.notifyUser(RINA, NotificationCatalog.gradePublished("Midterm", 7L));
            assertThat(sessions.isOnline(RINA)).isFalse();

            AuthService.Outcome outcome = auth.login("rina.barak",
                    InMemoryUserDirectory.DEV_PASSWORD, rinaSocket);

            assertThat(outcome.isSuccess()).isTrue();
            LoginResult result = outcome.result();
            assertThat(result.userId()).isEqualTo(RINA);
            assertThat(result.unreadNotifications())
                    .as("the bell badge is right on the very first frame")
                    .isEqualTo(2);
            assertThat(service.page(RINA, 10).items())
                    .extracting(NotificationDto::title)
                    .containsExactly("Your grade is ready", "Exam waiting for your approval");
        }

        @Test
        @DisplayName("a user with nothing waiting signs in with a zero badge")
        void nothingWaiting() {
            AuthService auth = new AuthService(new InMemoryUserDirectory(), sessions,
                    Clock.fixed(NOW, ZoneOffset.UTC), service::unreadCount);

            AuthService.Outcome outcome = auth.login("maya.levi",
                    InMemoryUserDirectory.DEV_PASSWORD, danaSocket);

            assertThat(outcome.result().userId()).isEqualTo(MAYA);
            assertThat(outcome.result().unreadNotifications()).isZero();
        }

        @Test
        @DisplayName("a counter that blows up costs a badge, never a sign-in")
        void aBrokenCounterDoesNotBlockLogin() {
            AuthService auth = new AuthService(new InMemoryUserDirectory(), sessions,
                    Clock.fixed(NOW, ZoneOffset.UTC), userId -> {
                        throw new IllegalStateException("store is down");
                    });

            AuthService.Outcome outcome = auth.login("dana.cohen",
                    InMemoryUserDirectory.DEV_PASSWORD, danaSocket);

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.result().unreadNotifications()).isZero();
        }
    }

    @Test
    @DisplayName("the service refuses to be built without its collaborators")
    void requiresCollaborators() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationService(null, new PushGateway(sessions)));
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationService(store, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationService(store, new PushGateway(sessions), null));
    }
}
