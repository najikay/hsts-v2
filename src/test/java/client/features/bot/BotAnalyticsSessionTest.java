package client.features.bot;

import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.bot.BotActivityPoint;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotCourseRequest;
import common.dto.bot.BotTopQuestion;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Bot Analytics screen's conversation with the server (E16.15 — S-34 ⚑).
 */
class BotAnalyticsSessionTest {

    private static final BotAnalytics BUSY = new BotAnalytics("Databases 22", 12,
            List.of(new BotActivityPoint(LocalDate.of(2026, 8, 19), 4),
                    new BotActivityPoint(LocalDate.of(2026, 8, 20), 8)),
            List.of(new BotTopQuestion("what is a foreign key", 5),
                    new BotTopQuestion("what is normalisation", 2)));

    private FakeClientConnection connection;
    private BotAnalyticsSession session;

    @BeforeEach
    void setUp() throws IOException {
        connection = new FakeClientConnection();
        connection.connect();
        RequestDispatcher dispatcher = new RequestDispatcher(connection);
        connection.setServerMessageHandler(dispatcher::dispatchIncoming);
        session = new BotAnalyticsSession(dispatcher, "22");
    }

    @Test
    @DisplayName("before the first fetch the aggregate is empty rather than absent")
    void startsEmpty() {
        assertThat(session.analytics().isEmpty()).isTrue();
        assertThat(session.isLoaded()).isFalse();
        assertThat(session.activity()).isEmpty();
        assertThat(session.frequent()).isEmpty();
        assertThat(session.busiestDay()).isEmpty();
        assertThat(session.courseCode()).isEqualTo("22");
    }

    @Test
    @DisplayName("a fetch renders the totals, the series and the frequent questions")
    void refresh() {
        connection.replyOk(Verb.BOT_ANALYTICS_GET, BUSY);

        session.refresh().join();

        assertThat(connection.lastSent().getVerb()).isEqualTo(Verb.BOT_ANALYTICS_GET);
        assertThat(((BotCourseRequest) connection.lastSent().getPayload()).courseCode())
                .isEqualTo("22");
        assertThat(session.analytics().totalQuestions()).isEqualTo(12);
        assertThat(session.activity()).hasSize(2);
        assertThat(session.frequent()).hasSize(2);
        assertThat(session.isLoaded()).isTrue();
    }

    @Test
    @DisplayName("the busiest day is the highest bar, and is empty when there are none")
    void busiestDay() {
        connection.replyOk(Verb.BOT_ANALYTICS_GET, BUSY);
        session.refresh().join();

        assertThat(session.busiestDay()).isPresent();
        assertThat(session.busiestDay().orElseThrow().count()).isEqualTo(8);
        assertThat(session.busiestDay().orElseThrow().day())
                .isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    @DisplayName("a bot nobody has used renders its own empty state")
    void emptyAggregate() {
        connection.replyOk(Verb.BOT_ANALYTICS_GET, BotAnalytics.empty("Databases 22"));

        session.refresh().join();

        assertThat(session.isLoaded()).isTrue();
        assertThat(session.analytics().isEmpty()).isTrue();
        assertThat(session.status()).isEmpty();
    }

    @Test
    @DisplayName("a refusal keeps the server's sentence, and the other failures say something too")
    void failureModes() {
        connection.replyError(Verb.BOT_ANALYTICS_GET, ErrorCode.FORBIDDEN,
                "You do not teach this course.");
        session.refresh().join();
        assertThat(session.status()).isEqualTo("You do not teach this course.");

        connection.respondTo(Verb.BOT_ANALYTICS_GET,
                request -> Message.error(request, ErrorCode.INTERNAL, "  "));
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.ANALYTICS_FAILED);

        connection.replyOk(Verb.BOT_ANALYTICS_GET, "not an aggregate");
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.ANALYTICS_FAILED);

        connection.failSendsWith(new IOException("socket closed"));
        session.refresh().join();
        assertThat(session.status()).isEqualTo(BotCopy.ANALYTICS_FAILED);
        assertThat(session.isBusy()).isFalse();
    }

    @Test
    @DisplayName("the screen is told when the fetch starts and when it ends")
    void notifiesOnEveryChange() {
        connection.replyOk(Verb.BOT_ANALYTICS_GET, BUSY);
        AtomicInteger changes = new AtomicInteger();
        session.onChange(changes::incrementAndGet);

        session.refresh().join();

        assertThat(changes).hasValue(2);
    }

    @Test
    @DisplayName("there is no method here that could ask who asked what (S-34)")
    void thereIsNoIdentityAffordance() {
        // The screen cannot drill into a person because this class has nothing to
        // drill with: the aggregate it holds has no identity field, and no method
        // here takes or returns a user. Asserted on the API surface so that adding
        // one is a visible change rather than a quiet one.
        assertThat(java.util.Arrays.stream(BotAnalyticsSession.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("student")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("user")))
                .isEmpty();
    }
}
