package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.features.bot.BotChatView;
import client.features.bot.BotCopy;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bot.BotActivityPoint;
import common.dto.bot.BotAnalytics;
import common.dto.bot.BotAnswer;
import common.dto.bot.BotManagerPage;
import common.dto.bot.BotProfile;
import common.dto.bot.BotSessionRow;
import common.dto.bot.BotSessionsPage;
import common.dto.bot.BotSourceKind;
import common.dto.bot.BotSourceRow;
import common.dto.bot.BotTopQuestion;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.dto.notify.NotificationsPage;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction tests for the study bot's four screens (E16.16).
 *
 * <p>House policy: a smoke test that only checks nodes exist proves very little.
 * Each test here drives the actual affordance with the actual robot — typing a
 * question and clicking Send, clicking a source row, clicking Reopen — and asserts
 * the consequence.
 *
 * <p>What only a booted toolkit can prove, and these therefore do: the chat's
 * optimistic bubble and typing indicator really appear between the click and the
 * answer, the manager's lock banner really renders when a colleague holds a source
 * (E18.5, and the ordering rule that makes it stick), the history's Reopen really
 * lands on the chat with the session id, and the analytics screen really draws its
 * anonymity note next to the numbers.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class BotInteractionTest extends ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private static final LoginResult MAYA = new LoginResult(3001, "maya.levi", "Maya Levi",
            Role.STUDENT, List.of(new CourseRef("22", "Databases 22")), 0);

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("22", "Databases 22")), 0);

    private static final BotManagerPage MANAGER_PAGE = BotManagerPage.of(
            new BotProfile(9L, "22", "Databases 22", "Databases study bot", true),
            List.of(new BotSourceRow(5L, BotSourceKind.PDF, "Week 3 handout",
                    "Michal Sharon", NOW, 1, 4200)));

    @BeforeAll
    static void headless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        // Each test boots the app itself, as the other interaction tests do.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== Chat (E16.13) =================================

    @Test
    @DisplayName("typing a question and clicking Send shows it, then the bot's answer")
    void chatRoundTrip() {
        ScreenManager manager = signIn(MAYA, connection ->
                connection.replyOk(Verb.BOT_ASK, new BotAnswer(7L, "what is a foreign key",
                        "A foreign key points at another table's primary key.", NOW)));
        BotChatView chat = openChat(manager);

        clickOn(chat.input()).write("what is a foreign key");
        clickOn(chat.sendButton());
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> texts = labelTexts(manager.scene());
        assertThat(texts)
                .as("her question is on screen")
                .contains("what is a foreign key");
        assertThat(texts)
                .as("and so is the answer that came back")
                .contains("A foreign key points at another table's primary key.");
        assertThat(chat.typingIndicator().isRunning())
                .as("the indicator stops when the answer lands")
                .isFalse();
        assertThat(chat.input().isDisabled())
                .as("she can ask the next question straight away")
                .isFalse();
        assertThat(chat.input().getText())
                .as("the box is cleared by the send, not by the answer")
                .isEmpty();
    }

    @Test
    @DisplayName("while the bot is thinking the indicator runs and the composer is disabled")
    void chatShowsProgressWhileWaiting() {
        // No responder for BOT_ASK: the request goes out and nothing answers, which
        // is exactly the twenty-second window a student actually waits through.
        ScreenManager manager = signIn(MAYA, connection -> { });
        BotChatView chat = openChat(manager);

        clickOn(chat.input()).write("what is a foreign key");
        clickOn(chat.sendButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(chat.typingIndicator().isRunning())
                .as("NFR-21: every async operation shows progress")
                .isTrue();
        assertThat(chat.input().isDisabled()).isTrue();
        assertThat(chat.sendButton().isDisabled()).isTrue();
        assertThat(labelTexts(manager.scene()))
                .as("her question is already on screen, optimistically")
                .contains("what is a foreign key");
    }

    // ===================== Manager (E16.12) ==============================

    @Test
    @DisplayName("clicking a source takes its lock, and a colleague's lock renders the banner")
    void managerShowsTheLockBanner() {
        ScreenManager manager = signIn(DANA, connection -> {
            connection.replyOk(Verb.BOT_MANAGER_GET, MANAGER_PAGE);
            // Somebody else is holding this source: the acquire is refused, and the
            // banner is what tells the teacher why the row is read only (E18.5).
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> Message.ok(request,
                    LockResponse.refused(((LockRequest) request.getPayload()).entity(),
                            new LockHolder(1002L, "Michal Sharon"),
                            Instant.now().plusSeconds(45))));
            connection.replyOk(Verb.LOCK_RELEASE, LockResponse.free(
                    new common.dto.lock.EntityRef(
                            common.dto.lock.EntityRef.BOT_SOURCE, 5L)));
        });

        interact(() -> manager.navigator().navigate(Routes.BOT_MANAGER.id(),
                NavParams.of("courseCode", "22")));
        WaitForAsyncUtils.waitForFxEvents();

        client.features.bot.BotManagerView view =
                (client.features.bot.BotManagerView) manager.screens()
                        .get(Routes.BOT_MANAGER.id());
        assertThat(labelTexts(manager.scene()))
                .as("the sources table rendered from the server's page")
                .contains("Week 3 handout");

        Node row = view.sourcesBox().getChildren().get(0);
        clickOn(row);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(view.lockBanner().isShowing())
                .as("the lock state is applied last, so it cannot be undone by the re-render")
                .isTrue();
        assertThat(view.lockBanner().message()).contains("Michal Sharon");
    }

    // ===================== History (E16.14) ==============================

    @Test
    @DisplayName("Reopen on a history row lands on the chat with that conversation")
    void historyReopensAConversation() {
        ScreenManager manager = signIn(MAYA, connection -> {
            connection.replyOk(Verb.BOT_SESSIONS_GET, new BotSessionsPage("22", "Databases 22",
                    List.of(new BotSessionRow(9L, NOW, NOW, 3, "what is a foreign key"))));
            connection.replyOk(Verb.BOT_SESSION_GET, new common.dto.bot.BotConversation(
                    9L, "22", "Databases 22", NOW, NOW,
                    List.of(common.dto.bot.BotTurn.asked("what is a foreign key", NOW),
                            common.dto.bot.BotTurn.answered("It points at a primary key.", NOW))));
        });

        interact(() -> manager.navigator().navigate(Routes.BOT_HISTORY.id(),
                NavParams.of("courseCode", "22")));
        WaitForAsyncUtils.waitForFxEvents();

        client.features.bot.BotHistoryView history =
                (client.features.bot.BotHistoryView) manager.screens()
                        .get(Routes.BOT_HISTORY.id());
        assertThat(labelTexts(manager.scene()))
                .as("the row previews her first question")
                .contains("what is a foreign key");
        assertThat(history.rowsBox().getChildren()).hasSize(1);

        clickOn(BotCopy.REOPEN);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.BOT_CHAT.id());
        assertThat(labelTexts(manager.scene()))
                .as("the stored conversation is on screen, word for word (S-33)")
                .contains("It points at a primary key.");
    }

    // ===================== Analytics (E16.15) ============================

    @Test
    @DisplayName("the analytics screen draws the totals, the frequent questions and the anonymity note")
    void analyticsRendersAnonymously() {
        ScreenManager manager = signIn(DANA, connection ->
                connection.replyOk(Verb.BOT_ANALYTICS_GET, new BotAnalytics("Databases 22", 12,
                        List.of(new BotActivityPoint(LocalDate.of(2026, 8, 19), 4),
                                new BotActivityPoint(LocalDate.of(2026, 8, 20), 8)),
                        List.of(new BotTopQuestion("what is a foreign key", 5)))));

        interact(() -> manager.navigator().navigate(Routes.BOT_ANALYTICS.id(),
                NavParams.of("courseCode", "22")));
        WaitForAsyncUtils.waitForFxEvents();

        client.features.bot.BotAnalyticsView view =
                (client.features.bot.BotAnalyticsView) manager.screens()
                        .get(Routes.BOT_ANALYTICS.id());
        Set<String> texts = labelTexts(manager.scene());

        assertThat(view.totalValue().getText()).isEqualTo("12");
        assertThat(texts).contains("what is a foreign key");
        assertThat(texts).contains("5 times");
        assertThat(texts)
                .as("S-34 is stated on the screen it applies to, not only in the requirement")
                .contains(BotCopy.ANONYMOUS_NOTE);
        assertThat(view.frequentBox().getChildren()).hasSize(1);
    }

    // ===================== Fixture =======================================

    /** Boots the app, attaches a scripted server, and enters the user's shell. */
    private ScreenManager signIn(LoginResult user, java.util.function.Consumer<FakeClientConnection> script) {
        interact(() -> new ClientApp().start(new Stage()));
        WaitForAsyncUtils.waitForFxEvents();

        ScreenManager manager = ScreenManager.getInstance();
        interact(() -> {
            FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
            try {
                connection.connect();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            connection.replyOk(Verb.LOGIN, user);
            connection.replyOk(Verb.LOGOUT, null);
            connection.replyOk(Verb.NOTIFICATIONS_GET, new NotificationsPage(List.of(), 0));
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new client.events.PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, user);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    /** Navigates to the chat and returns the built screen. */
    private BotChatView openChat(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.BOT_CHAT.id(),
                NavParams.of("courseCode", "22")));
        WaitForAsyncUtils.waitForFxEvents();
        return (BotChatView) manager.screens().get(Routes.BOT_CHAT.id());
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
