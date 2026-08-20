package client.features.notify;

import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.notify.MarkReadRequest;
import common.dto.notify.NavRef;
import common.dto.notify.NotificationDto;
import common.dto.notify.NotificationType;
import common.dto.notify.NotificationsGetRequest;
import common.dto.notify.NotificationsPage;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the bell's conversation with the server (E17.4/E17.6).
 *
 * <p>The whole chain runs with no JavaFX toolkit: a {@link FakeClientConnection}
 * answers the real {@link RequestDispatcher}, and the bus posts through
 * {@link DirectFxThreadPoster}, so a push becomes a model update synchronously
 * and the assertion can follow on the next line.
 */
class NotificationsSessionTest {

    private static final Instant T0 = Instant.parse("2026-08-19T09:00:00Z");

    private FakeClientConnection connection;
    private ClientEventBus eventBus;
    private NotificationsModel model;
    private NotificationsSession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        eventBus = new ClientEventBus(ClientEventBus.newBus(), new DirectFxThreadPoster());
        model = new NotificationsModel();
        session = new NotificationsSession(dispatcher, eventBus, model);
    }

    // ===================== Lifecycle =====================================

    @Test
    @DisplayName("start seeds the badge from LoginResult and subscribes to pushes")
    void startSeedsAndSubscribes() {
        session.start(4);

        assertThat(model.unreadCount()).isEqualTo(4);
        assertThat(session.isStarted()).isTrue();
        assertThat(eventBus.isRegistered(session)).isTrue();
        assertThat(connection.sentCount())
                .as("the list is fetched lazily, when the panel opens")
                .isZero();
    }

    @Test
    @DisplayName("starting twice does not subscribe twice")
    void startIsIdempotent() {
        session.start(1);
        session.start(2);

        assertThat(model.unreadCount()).isEqualTo(2);
        assertThat(session.isStarted()).isTrue();
    }

    @Test
    @DisplayName("stop unsubscribes and empties the model, so the next user starts blank")
    void stopTearsDown() {
        session.start(3);
        session.stop();

        assertThat(session.isStarted()).isFalse();
        assertThat(eventBus.isRegistered(session)).isFalse();
        assertThat(model.unreadCount()).isZero();
        assertThat(model.items()).isEmpty();
    }

    @Test
    @DisplayName("stopping a session that never started is safe")
    void stopBeforeStart() {
        session.stop();

        assertThat(session.isStarted()).isFalse();
    }

    @Test
    @DisplayName("a push after stop reaches nothing")
    void pushesStopWithTheSession() {
        session.start(0);
        session.stop();

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, row(1)));

        assertThat(model.items()).isEmpty();
    }

    // ===================== Requests ======================================

    @Test
    @DisplayName("refresh asks for the default page and applies the answer")
    void refreshAppliesThePage() {
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(row(1), row(2)), 5));

        session.refresh().join();

        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.NOTIFICATIONS_GET);
        assertThat(connection.lastSent().getPayload())
                .isEqualTo(NotificationsGetRequest.defaults());
        assertThat(model.items()).hasSize(2);
        assertThat(model.unreadCount()).isEqualTo(5);
        assertThat(model.isLoaded()).isTrue();
    }

    @Test
    @DisplayName("markRead sends the id and folds the refreshed page straight back in")
    void markReadUsesTheSameResponsePath() {
        connection.replyOk(Verb.NOTIFICATIONS_MARK_READ, new NotificationsPage(List.of(read(7)), 0));

        session.markRead(7L).join();

        assertThat(connection.lastSent().getPayload()).isEqualTo(MarkReadRequest.one(7L));
        assertThat(model.unreadCount()).isZero();
        assertThat(model.items().get(0).isUnread()).isFalse();
    }

    @Test
    @DisplayName("markAllRead sends the mark-all shape and clears the badge")
    void markAllRead() {
        connection.replyOk(Verb.NOTIFICATIONS_MARK_READ, new NotificationsPage(List.of(), 0));
        model.setUnreadCount(9);

        session.markAllRead().join();

        assertThat(connection.lastSent().getPayload()).isEqualTo(MarkReadRequest.markAll());
        assertThat(model.unreadCount()).isZero();
    }

    @Test
    @DisplayName("a refused request leaves the model exactly as it was")
    void serverErrorLeavesTheModelAlone() {
        model.setUnreadCount(3);
        connection.replyError(Verb.NOTIFICATIONS_GET, ErrorCode.INTERNAL, "boom");

        session.refresh().join();

        assertThat(model.unreadCount())
                .as("blanking the badge would claim there is nothing waiting")
                .isEqualTo(3);
        assertThat(model.isLoaded()).isFalse();
    }

    @Test
    @DisplayName("a dropped socket fails the send without throwing at the caller")
    void sendFailureIsContained() {
        model.setUnreadCount(2);
        connection.failSendsWith(new IOException("socket closed"));

        session.refresh().join();

        assertThat(model.unreadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unexpected payload is ignored rather than applied")
    void unexpectedPayloadIsIgnored() {
        model.setUnreadCount(2);
        connection.replyOk(Verb.NOTIFICATIONS_GET, "not a page");

        session.refresh().join();

        assertThat(model.unreadCount()).isEqualTo(2);
        assertThat(model.isLoaded()).isFalse();
    }

    // ===================== Pushes ========================================

    @Test
    @DisplayName("a push lands in the model and notifies the toast listener")
    void pushUpdatesTheModelAndRaisesAToast() {
        List<NotificationDto> toasted = new ArrayList<>();
        session.onPushed(toasted::add);
        session.start(0);

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, row(11)));

        assertThat(model.items()).extracting(NotificationDto::id).containsExactly(11L);
        assertThat(model.unreadCount()).isEqualTo(1);
        assertThat(toasted).extracting(NotificationDto::id).containsExactly(11L);
    }

    @Test
    @DisplayName("a push arriving while the panel is open updates the open list (E17.6)")
    void pushUpdatesAnAlreadyLoadedList() {
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(row(1)), 1));
        session.start(1);
        session.refresh().join();

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, row(2)));

        assertThat(model.items()).extracting(NotificationDto::id).containsExactly(2L, 1L);
        assertThat(model.unreadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a push that duplicates a fetched row raises no second toast")
    void duplicatePushRaisesNoToast() {
        List<NotificationDto> toasted = new ArrayList<>();
        session.onPushed(toasted::add);
        connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(row(5)), 1));
        session.start(1);
        session.refresh().join();

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, row(5)));

        assertThat(model.size()).isEqualTo(1);
        assertThat(toasted).isEmpty();
    }

    @Test
    @DisplayName("other push verbs pass through untouched")
    void otherPushVerbsAreIgnored() {
        session.start(0);

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_LOCK_CHANGED, "anything"));
        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_GRADE_PUBLISHED, row(3)));

        assertThat(model.items()).isEmpty();
    }

    @Test
    @DisplayName("a notification push carrying the wrong payload is dropped, not thrown")
    void malformedPushIsDropped() {
        session.start(0);

        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, "not a dto"));
        eventBus.post(new client.events.ServerPushEvent(Verb.PUSH_NOTIFICATION, null));
        session.onServerPush(null);

        assertThat(model.items()).isEmpty();
    }

    @Test
    @DisplayName("the session exposes the model it maintains")
    void modelIsReachable() {
        assertThat(session.model()).isSameAs(model);
    }

    @Test
    @DisplayName("every collaborator is required")
    void collaboratorsAreRequired() {
        RequestDispatcher dispatcher = new RequestDispatcher(connection);

        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationsSession(null, eventBus, model));
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationsSession(dispatcher, null, model));
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationsSession(dispatcher, eventBus, null));
        assertThatNullPointerException().isThrownBy(() -> session.onPushed(null));
    }

    private static NotificationDto row(long id) {
        return new NotificationDto(id, NotificationType.APPROVAL_REQUESTED, "Exam waiting", "body",
                NavRef.to("approvals", id), T0.plusSeconds(id), null);
    }

    private static NotificationDto read(long id) {
        return row(id).readAt(T0.plusSeconds(100));
    }
}
