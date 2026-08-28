package client.features.bot;

import client.events.DirectFxThreadPoster;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotSessionRow;
import common.dto.bot.BotSessionsPage;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bot History screen's conversation with the server (E16.14 — F12.10).
 */
class BotHistorySessionTest {

    private static final Instant WHEN = Instant.parse("2026-08-20T10:00:00Z");

    private static final BotSessionsPage PAGE = new BotSessionsPage("22", "Databases 22",
            List.of(new BotSessionRow(9L, WHEN, WHEN.plusSeconds(600), 3, "what is a foreign key"),
                    new BotSessionRow(8L, WHEN.minusSeconds(86_400), WHEN.minusSeconds(80_000),
                            1, "what is normalisation")));

    private FakeClientConnection connection;
    private BotHistorySession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new BotHistorySession(dispatcher, new DirectFxThreadPoster(), "22");
    }

    @Test
    @DisplayName("before the first fetch the page is empty and says so")
    void startsEmpty() {
        assertThat(session.rows()).isEmpty();
        assertThat(session.isLoaded()).isFalse();
        assertThat(session.isBusy()).isFalse();
        assertThat(session.courseCode()).isEqualTo("22");
        assertThat(session.page().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a fetch carries no user id, because the caller is the session on the socket")
    void fetchCarriesNoIdentity() {
        connection.replyOk(Verb.BOT_SESSIONS_GET, PAGE);

        session.refresh().join();

        Message sent = connection.lastSent();
        assertThat(sent.getVerb()).isEqualTo(Verb.BOT_SESSIONS_GET);
        assertThat(sent.getPayload()).isInstanceOf(BotCourseRequest.class);
        assertThat(((BotCourseRequest) sent.getPayload()).courseCode()).isEqualTo("22");
        assertThat(java.util.Arrays.stream(BotCourseRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .as("there is nowhere on this request to put somebody else's id")
                .containsExactly("courseCode");
    }

    @Test
    @DisplayName("the rows arrive in the order the server sent them")
    void rendersTheRows() {
        connection.replyOk(Verb.BOT_SESSIONS_GET, PAGE);

        session.refresh().join();

        assertThat(session.rows()).extracting(BotSessionRow::sessionId).containsExactly(9L, 8L);
        assertThat(session.page().courseName()).isEqualTo("Databases 22");
        assertThat(session.isLoaded()).isTrue();
        assertThat(session.status()).isEmpty();
    }

    @Test
    @DisplayName("a student with no history gets an empty list rather than an error")
    void emptyHistory() {
        connection.replyOk(Verb.BOT_SESSIONS_GET, BotSessionsPage.empty("22", "Databases 22"));

        session.refresh().join();

        assertThat(session.isLoaded()).isTrue();
        assertThat(session.rows()).isEmpty();
        assertThat(session.status()).isEmpty();
    }

    @Test
    @DisplayName("a refusal keeps the server's sentence")
    void refusal() {
        connection.replyError(Verb.BOT_SESSIONS_GET, ErrorCode.FORBIDDEN,
                "You are not enrolled in this course.");

        session.refresh().join();

        assertThat(session.status()).isEqualTo("You are not enrolled in this course.");
        assertThat(session.isLoaded()).isFalse();
    }

    @Test
    @DisplayName("a refusal with no sentence, an odd payload and a dead socket all say something")
    void failureModes() {
        connection.respondTo(Verb.BOT_SESSIONS_GET,
                request -> Message.error(request, ErrorCode.INTERNAL, "  "));
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.HISTORY_FAILED);

        connection.replyOk(Verb.BOT_SESSIONS_GET, "not a page");
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.HISTORY_FAILED);

        connection.failSendsWith(new IOException("socket closed"));
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.HISTORY_FAILED);
        assertThat(session.isBusy()).isFalse();
    }

    @Test
    @DisplayName("the screen is told when the fetch starts and when it ends")
    void notifiesOnEveryChange() {
        connection.replyOk(Verb.BOT_SESSIONS_GET, PAGE);
        AtomicInteger changes = new AtomicInteger();
        session.onChange(changes::incrementAndGet);

        session.refresh().join();

        assertThat(changes).hasValue(2);
    }
}
