package client.features.bot;

import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.bot.BotActiveRequest;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotCreateRequest;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.bot.SourceAddRequest;
import common.dto.bot.SourceRemoveRequest;
import common.protocol.ErrorCode;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bot Manager's conversation with the server (E16.12).
 */
class BotManagerSessionTest {

    private static final Instant WHEN = Instant.parse("2026-08-20T10:00:00Z");

    private static final BotManagerPage WITH_BOT = BotManagerPage.of(
            new BotProfile(9L, "22", "Databases 22", "Databases study bot", true),
            List.of(new BotSourceRow(5L, BotSourceKind.PDF, "Week 3 handout",
                    "Dana Cohen", WHEN, 1, 4200)));

    private FakeClientConnection connection;
    private BotManagerSession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new BotManagerSession(dispatcher, "22");
    }

    @Test
    @DisplayName("before the first fetch there is nothing to draw and nothing to claim")
    void startsEmpty() {
        assertThat(session.hasBot()).isFalse();
        assertThat(session.sources()).isEmpty();
        assertThat(session.isLoaded()).isFalse();
        assertThat(session.isBusy()).isFalse();
        assertThat(session.status()).isEmpty();
        assertThat(session.courseCode()).isEqualTo("22");
    }

    @Test
    @DisplayName("a fetch asks for this course and renders what comes back")
    void refresh() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);

        session.refresh().join();

        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.BOT_MANAGER_GET);
        assertThat(((BotCourseRequest) connection.lastSent().getPayload()).courseCode())
                .isEqualTo("22");
        assertThat(session.hasBot()).isTrue();
        assertThat(session.isLoaded()).isTrue();
        assertThat(session.sources()).hasSize(1);
        assertThat(session.source(5L)).isPresent();
        assertThat(session.source(999L)).isEmpty();
    }

    @Test
    @DisplayName("a course with no bot is a page to draw, not an error")
    void noBotIsAPage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, BotManagerPage.none());

        session.refresh().join();

        assertThat(session.isLoaded()).isTrue();
        assertThat(session.hasBot()).isFalse();
        assertThat(session.status()).isEmpty();
    }

    @Test
    @DisplayName("creating sends the name and re-renders from the page it gets back")
    void create() {
        connection.replyOk(Verb.BOT_CREATE, WITH_BOT);

        session.create("Databases study bot").join();

        BotCreateRequest sent = (BotCreateRequest) connection.lastSent().getPayload();
        assertThat(sent.courseCode()).isEqualTo("22");
        assertThat(sent.name()).isEqualTo("Databases study bot");
        assertThat(session.hasBot()).isTrue();
    }

    @Test
    @DisplayName("the toggle sends the state to be in, not an instruction to flip")
    void setActive() {
        connection.replyOk(Verb.BOT_ACTIVE_SET, WITH_BOT);

        session.setActive(false).join();

        BotActiveRequest sent = (BotActiveRequest) connection.lastSent().getPayload();
        assertThat(sent.active()).isFalse();
        assertThat(sent.courseCode()).isEqualTo("22");
    }

    @Test
    @DisplayName("adding a source sends its bytes and its kind")
    void addSource() {
        connection.replyOk(Verb.BOT_SOURCE_ADD, WITH_BOT);

        session.addSource(BotSourceKind.PDF, "Week 3 handout",
                "material".getBytes(StandardCharsets.UTF_8)).join();

        SourceAddRequest sent = (SourceAddRequest) connection.lastSent().getPayload();
        assertThat(sent.kind()).isEqualTo(BotSourceKind.PDF);
        assertThat(sent.title()).isEqualTo("Week 3 handout");
        assertThat(new String(sent.content(), StandardCharsets.UTF_8)).isEqualTo("material");
        assertThat(session.sources()).hasSize(1);
    }

    @Test
    @DisplayName("a parse failure keeps the server's own sentence, which is the useful one")
    void parseFailureKeepsTheServerSentence() {
        connection.replyError(Verb.BOT_SOURCE_ADD, ErrorCode.VALIDATION,
                "This PDF has no text in it. It may be a scan of printed pages.");

        session.addSource(BotSourceKind.PDF, "Scan", new byte[] {1, 2, 3}).join();

        assertThat(session.status()).startsWith("This PDF has no text in it.");
        assertThat(session.isBusy()).isFalse();
    }

    @Test
    @DisplayName("removing a source names the course as well as the row")
    void removeSource() {
        connection.replyOk(Verb.BOT_SOURCE_REMOVE, BotManagerPage.of(
                new BotProfile(9L, "22", "Databases 22", "bot", true), List.of()));

        session.removeSource(5L).join();

        SourceRemoveRequest sent = (SourceRemoveRequest) connection.lastSent().getPayload();
        assertThat(sent.courseCode()).isEqualTo("22");
        assertThat(sent.sourceId()).isEqualTo(5L);
        assertThat(session.sources()).isEmpty();
    }

    @Test
    @DisplayName("a dropped connection is a status line, and the page it had survives")
    void networkFailureKeepsThePage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        session.refresh().join();

        connection.failSendsWith(new IOException("socket closed"));
        session.refresh().join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
        assertThat(session.sources())
                .as("a failed refresh must not blank a screen that was correct a second ago")
                .hasSize(1);
    }

    @Test
    @DisplayName("an unexpected payload is reported rather than rendered")
    void unexpectedPayload() {
        connection.replyOk(Verb.BOT_MANAGER_GET, "not a page");

        session.refresh().join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
        assertThat(session.isLoaded()).isFalse();
    }

    @Test
    @DisplayName("a refusal with no sentence still says something")
    void refusalWithoutASentence() {
        connection.respondTo(Verb.BOT_SOURCE_REMOVE, request ->
                common.protocol.Message.error(request, ErrorCode.CONFLICT, "  "));

        session.removeSource(5L).join();

        assertThat(session.status()).isEqualTo(BotCopy.MANAGER_FAILED);
    }

    @Test
    @DisplayName("the screen is told when a request starts and when it ends")
    void notifiesOnEveryChange() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        AtomicInteger changes = new AtomicInteger();
        session.onChange(changes::incrementAndGet);

        session.refresh().join();

        assertThat(changes)
                .as("once for busy, once for the answer, so a spinner can appear and go")
                .hasValue(2);
    }

    @Test
    @DisplayName("a view can be handed the page rather than pulling it")
    void onPage() {
        connection.replyOk(Verb.BOT_MANAGER_GET, WITH_BOT);
        AtomicInteger pages = new AtomicInteger();
        session.onPage(page -> pages.incrementAndGet());

        session.refresh().join();

        assertThat(pages).hasValue(2);
        assertThat(session.page()).isEqualTo(WITH_BOT);
    }
}
