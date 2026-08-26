package client.features.bot;

import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.bot.BotAnswer;
import common.dto.bot.BotAskRequest;
import common.dto.bot.BotConversation;
import common.dto.bot.BotIntegrityNotice;
import common.dto.bot.BotTurn;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat's conversation with the server (E16.13).
 *
 * <p>Driven through the real {@link RequestDispatcher} over
 * {@code FakeClientConnection}, so the request that goes out and the response that
 * comes back are the real wire types rather than a stubbed method call.
 */
class BotChatSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private FakeClientConnection connection;
    private BotChatModel model;
    private BotChatSession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        model = new BotChatModel("22", "Databases 22");
        session = new BotChatSession(dispatcher, model, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a question goes out as BOT_ASK and the answer lands in the model")
    void asksAndAnswers() {
        connection.replyOk(Verb.BOT_ASK,
                new BotAnswer(7L, "what is a foreign key", "It points at a primary key.", NOW));

        session.ask("what is a foreign key").join();

        Message sent = connection.lastSent();
        assertThat(sent.getVerb()).isEqualTo(Verb.BOT_ASK);
        BotAskRequest payload = (BotAskRequest) sent.getPayload();
        assertThat(payload.courseCode()).isEqualTo("22");
        assertThat(payload.question()).isEqualTo("what is a foreign key");
        assertThat(payload.continuesSession()).isFalse();
        assertThat(payload.integrityAcknowledged()).isFalse();

        assertThat(model.state()).isEqualTo(ChatState.IDLE);
        assertThat(model.entries()).hasSize(2);
        assertThat(model.sessionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a follow-up carries the session id it was given")
    void followUpCarriesTheSession() {
        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "first", "a", NOW));
        session.ask("first").join();

        session.ask("second").join();

        assertThat(((BotAskRequest) connection.lastSent().getPayload()).sessionId())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("a blank question is not sent at all")
    void blankQuestionIsNotSent() {
        session.ask("   ").join();
        session.ask(null).join();

        assertThat(connection.sentMessages()).isEmpty();
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
    }

    @Test
    @DisplayName("a refusal that means the bot is unusable blocks the composer")
    void refusalsBlock() {
        for (ErrorCode code : List.of(ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND, ErrorCode.CONFLICT)) {
            connection.replyError(Verb.BOT_ASK, code, "You are not enrolled in this course.");

            session.ask("q").join();

            assertThat(model.state()).as("%s", code).isEqualTo(ChatState.UNAVAILABLE);
            assertThat(model.banner()).isEqualTo("You are not enrolled in this course.");
            model.startFresh();
        }
    }

    @Test
    @DisplayName("any other error is worth trying again, and the question comes back")
    void otherErrorsAreRetryable() {
        connection.replyError(Verb.BOT_ASK, ErrorCode.INTERNAL, "Something went wrong.");

        session.ask("what is a foreign key").join();

        assertThat(model.state()).isEqualTo(ChatState.RETRYABLE_ERROR);
        assertThat(model.banner()).isEqualTo("Something went wrong.");
        assertThat(model.entries()).isEmpty();
    }

    @Test
    @DisplayName("an error with no sentence still says what to do next")
    void errorWithoutAMessage() {
        connection.respondTo(Verb.BOT_ASK,
                request -> Message.error(request, ErrorCode.INTERNAL, "   "));

        session.ask("q").join();

        assertThat(model.banner()).isEqualTo(BotCopy.ASK_FAILED);
    }

    @Test
    @DisplayName("a dropped connection is a retryable failure, not an exception a screen must catch")
    void networkFailureIsAState() {
        connection.failSendsWith(new IOException("socket closed"));

        session.ask("q").join();

        assertThat(model.state()).isEqualTo(ChatState.RETRYABLE_ERROR);
        assertThat(model.banner()).isEqualTo(BotCopy.ASK_FAILED);
    }

    @Test
    @DisplayName("an unexpected payload does not leave the chat stuck on thinking")
    void unexpectedPayload() {
        connection.replyOk(Verb.BOT_ASK, "not a dto");

        session.ask("q").join();

        assertThat(model.state()).isEqualTo(ChatState.RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("the C-4 notice arrives as its own type and holds the question (ADR-018)")
    void integrityNotice() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22",
                "You are taking an exam right now."));

        session.ask("what is a foreign key").join();

        assertThat(model.state()).isEqualTo(ChatState.NEEDS_ACKNOWLEDGEMENT);
        assertThat(model.heldQuestion()).isEqualTo("what is a foreign key");
        assertThat(model.banner()).isEqualTo("You are taking an exam right now.");
    }

    @Test
    @DisplayName("confirming re-sends the same question, acknowledged")
    void acknowledgeAndAsk() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("what is a foreign key").join();
        connection.replyOk(Verb.BOT_ASK,
                new BotAnswer(7L, "what is a foreign key", "an answer", NOW));

        session.acknowledgeAndAsk().join();

        BotAskRequest resent = (BotAskRequest) connection.lastSent().getPayload();
        assertThat(resent.question()).isEqualTo("what is a foreign key");
        assertThat(resent.integrityAcknowledged()).isTrue();
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
    }

    @Test
    @DisplayName("a second ask after confirming does not notice again ⚑ (B-20)")
    void theNoticeIsShownOncePerAttempt() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("what is a foreign key").join();
        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "what is a foreign key", "a", NOW));
        session.acknowledgeAndAsk().join();

        // Her next question in the same sitting. Before B-20 this went out with
        // integrityAcknowledged=false, the server had no reason not to ask again, and she got
        // the confirmation dialog on every message for the rest of the exam.
        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "and a primary key", "b", NOW));
        session.ask("and a primary key").join();

        BotAskRequest second = (BotAskRequest) connection.lastSent().getPayload();
        assertThat(second.integrityAcknowledged())
                .as("the client remembers what she agreed to")
                .isTrue();
        assertThat(model.state())
                .as("answered, not asked again")
                .isEqualTo(ChatState.IDLE);
        assertThat(model.entries()).hasSize(4);
    }

    @Test
    @DisplayName("a new attempt notices again rather than being waved through ⚑ (B-20)")
    void aNewAttemptNoticesAgain() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("first").join();
        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "first", "a", NOW));
        session.acknowledgeAndAsk().join();
        assertThat(model.hasAcknowledged()).isTrue();

        // She finished that exam and started another. The server decides C-4 from its own
        // live registry, so it asks about the new sitting — and the client must put the
        // question rather than answer it from a confirmation that belonged to the old one.
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("second").join();

        assertThat(model.state()).isEqualTo(ChatState.NEEDS_ACKNOWLEDGEMENT);
        assertThat(model.heldQuestion()).isEqualTo("second");
        assertThat(model.hasAcknowledged())
                .as("the stale consent is discarded, not reused")
                .isFalse();
    }

    @Test
    @DisplayName("declining leaves the next ask unacknowledged (B-20)")
    void decliningRecordsNoConsent() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("what is a foreign key").join();
        session.decline();

        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "something else", "a", NOW));
        session.ask("something else").join();

        assertThat(((BotAskRequest) connection.lastSent().getPayload()).integrityAcknowledged())
                .as("no is an answer, and it has to survive one message")
                .isFalse();
    }

    @Test
    @DisplayName("the same-course lockout drops any consent (B-20)")
    void beingLockedOutDropsConsent() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("first").join();
        connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "first", "a", NOW));
        session.acknowledgeAndAsk().join();

        // She started sitting THIS course's exam: the server refuses with the C-4 lockout,
        // which no payload field can lift and which ends the situation she consented to.
        connection.replyError(Verb.BOT_ASK, ErrorCode.CONFLICT, "The Databases 22 bot is locked");
        session.ask("second").join();

        assertThat(model.state()).isEqualTo(ChatState.UNAVAILABLE);
        assertThat(model.hasAcknowledged()).isFalse();
    }

    @Test
    @DisplayName("confirming with nothing held sends nothing")
    void acknowledgeWithoutAHeldQuestion() {
        session.acknowledgeAndAsk().join();

        assertThat(connection.sentMessages()).isEmpty();
    }

    @Test
    @DisplayName("declining gives the question back unsent")
    void decline() {
        connection.replyOk(Verb.BOT_ASK, new BotIntegrityNotice("Databases 22", "notice"));
        session.ask("what is a foreign key").join();
        int sentSoFar = connection.sentMessages().size();

        String returned = session.decline();

        assertThat(returned).isEqualTo("what is a foreign key");
        assertThat(connection.sentMessages()).hasSize(sentSoFar);
        assertThat(model.state()).isEqualTo(ChatState.IDLE);
    }

    @Test
    @DisplayName("reopening fetches the transcript and loads it (F12.10)")
    void reopen() {
        connection.replyOk(Verb.BOT_SESSION_GET, new BotConversation(9L, "22", "Databases 22",
                NOW, NOW, List.of(BotTurn.asked("stored", NOW), BotTurn.answered("reply", NOW))));

        session.reopen(9L).join();

        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.BOT_SESSION_GET);
        assertThat(model.entries()).hasSize(2);
        assertThat(model.sessionId()).isEqualTo(9L);
    }

    /**
     * ⚑ The generation-guard sweep. {@code BotChatView.onShow} calls {@code reopen} whenever it
     * is navigated to carrying a session parameter, and it keeps one session per course, so two
     * deep links into two conversations can overlap. Nothing checked which conversation an
     * arriving transcript belonged to.
     */
    @Test
    @DisplayName("⚑ a transcript for the conversation she left loses to the one she reopened")
    void aLateTranscriptForAnotherConversationIsDropped() {
        // No responder, so both futures stay pending and the answers are delivered by hand.
        session.reopen(9L);
        session.reopen(10L);

        connection.deliver(Message.ok(connection.sentMessages().get(1),
                new BotConversation(10L, "22", "Databases 22", NOW, NOW,
                        List.of(BotTurn.asked("newer", NOW)))));
        connection.deliver(Message.ok(connection.sentMessages().get(0),
                new BotConversation(9L, "22", "Databases 22", NOW, NOW,
                        List.of(BotTurn.asked("older", NOW), BotTurn.answered("reply", NOW)))));

        assertThat(model.sessionId())
                .as("the transcript on screen must be the conversation she asked for")
                .isEqualTo(10L);
        assertThat(model.entries()).hasSize(1);
    }

    @Test
    @DisplayName("a reopen that fails says so instead of leaving a blank screen")
    void reopenFailures() {
        connection.replyError(Verb.BOT_SESSION_GET, ErrorCode.NOT_FOUND,
                "That conversation could not be found.");
        session.reopen(9L).join();
        assertThat(model.banner()).isEqualTo("That conversation could not be found.");

        connection.replyOk(Verb.BOT_SESSION_GET, "not a dto");
        session.reopen(9L).join();
        assertThat(model.banner()).isEqualTo(BotCopy.HISTORY_FAILED);
    }

    @Test
    @DisplayName("a reopen on a dropped connection is a state, not an exception")
    void reopenNetworkFailure() {
        connection.failSendsWith(new IOException("socket closed"));

        session.reopen(9L).join();

        assertThat(model.banner()).isEqualTo(BotCopy.HISTORY_FAILED);
    }

    @Test
    @DisplayName("the session hands out the model every view of the chat reads")
    void exposesItsModel() {
        assertThat(session.model()).isSameAs(model);
    }
}
