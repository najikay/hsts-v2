package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.Routes;
import client.core.ScreenManager;
import client.features.locks.LockCopy;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.Question;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockRequest;
import common.dto.lock.LockResponse;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the lock-aware question editor (E18.5).
 *
 * <p>This is E18's working proof, driven the way a teacher drives it: click a
 * question in the list, and because Rina already has it, the editor opens
 * read-only with a banner naming her. Then Rina's client releases it, and the
 * banner turns into a takeover offer without the screen being reopened or
 * refreshed (NFR-18).
 *
 * <p>Everything the assertions look at is what a user would see: the banner's
 * sentence, whether the text areas accept typing, and whether Save is available.
 *
 * <p>Same escape hatch as the other UI tests:
 * {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class QuestionEditorLockInteractionTest extends ApplicationTest {

    private static final EntityRef QUESTION_1 = EntityRef.question(1);
    private static final LockHolder RINA = new LockHolder(1002L, "Rina Barak");
    private static final Instant EXPIRY = Instant.parse("2026-08-19T09:00:40Z");

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("11", "Algebra 11")), 0);

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
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    @Test
    @DisplayName("selecting a question somebody else is editing opens it read-only, named")
    void lockedByAnotherUserIsReadOnly() {
        ScreenManager manager = openQuestionBank(LockResponse.refused(QUESTION_1, RINA, EXPIRY));

        selectFirstQuestion(manager);

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("the banner names the person, so the user knows who to ask")
                .contains(LockCopy.readOnlyBanner("Rina Barak", "question"));
        assertThat(textArea(scene).isEditable())
                .as("read-only means the fields do not accept typing")
                .isFalse();
        assertThat(saveButton(scene).isDisabled()).isTrue();
    }

    @Test
    @DisplayName("a question nobody is editing opens editable, with no banner")
    void unlockedQuestionIsEditable() {
        ScreenManager manager = openQuestionBank(
                LockResponse.granted(QUESTION_1, new LockHolder(1001L, "Dana Cohen"), EXPIRY));

        selectFirstQuestion(manager);

        Scene scene = manager.scene();
        assertThat(textArea(scene).isEditable()).isTrue();
        assertThat(saveButton(scene).isDisabled()).isFalse();
        assertThat(labelTexts(scene))
                .doesNotContain(LockCopy.readOnlyBanner("Rina Barak", "question"));
    }

    @Test
    @DisplayName("when the other user releases it, the banner offers a takeover, live")
    void releaseOffersTakeoverWithoutAReopen() {
        ScreenManager manager = openQuestionBank(LockResponse.refused(QUESTION_1, RINA, EXPIRY));
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        selectFirstQuestion(manager);

        interact(() -> connection.pushToClient(Verb.PUSH_LOCK_CHANGED,
                LockChange.released(QUESTION_1)));
        WaitForAsyncUtils.waitForFxEvents();

        Scene scene = manager.scene();
        assertThat(labelTexts(scene))
                .as("the offer replaces the read-only sentence, without a reopen")
                .contains(LockCopy.takeoverExplanation(
                        client.features.locks.TakeoverReason.AVAILABLE, "question"));
        assertThat(labelTexts(scene))
                .doesNotContain(LockCopy.readOnlyBanner("Rina Barak", "question"));
        assertThat(textArea(scene).isEditable())
                .as("nothing is grabbed until the user says so")
                .isFalse();
        Node takeOver = scene.getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof Button button
                        && LockCopy.TAKEOVER_CONFIRM.equals(button.getText()))
                .findFirst().orElse(null);
        assertThat(takeOver).as("the banner carries a 'Take over' affordance").isNotNull();
    }

    // ===================== Fixture =======================================

    /** Boots the app into the question bank with a scripted lock answer. */
    private ScreenManager openQuestionBank(LockResponse lockAnswer) {
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
            connection.replyOk(Verb.LOGIN, DANA);
            connection.replyOk(Verb.LOGOUT, null);
            connection.replyOk(Verb.GET_ALL_QUESTIONS, new ArrayList<>(List.of(
                    new Question(1, "What is 2 + 2?", "4"),
                    new Question(2, "מהי בירת צרפת?", "פריז"))));
            // Every lock verb is answered, so the heartbeat and the release on close
            // behave exactly as they would against a real server.
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> Message.ok(request, lockAnswer));
            connection.respondTo(Verb.LOCK_RENEW, request -> Message.ok(request, lockAnswer));
            connection.respondTo(Verb.LOCK_RELEASE, request -> Message.ok(request,
                    LockResponse.free(((LockRequest) request.getPayload()).entity())));

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            dispatcher.setPushListener(new client.events.PushEventBridge(manager.eventBus()));
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);

            ShellBoot.enter(manager, DANA);
            manager.navigator().navigate(Routes.QUESTIONS.id());
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(manager.navigator().currentRouteId()).isEqualTo(Routes.QUESTIONS.id());
        return manager;
    }

    /** Clicks the first row of the question list, as a teacher would. */
    private void selectFirstQuestion(ScreenManager manager) {
        // lookupAll returns a Set, so "the first row" has to be identified by its
        // own index rather than by iteration order, and empty filler cells skipped.
        Node cell = manager.scene().getRoot().lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(node -> (ListCell<?>) node)
                .filter(listCell -> !listCell.isEmpty() && listCell.getIndex() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the question list rendered no rows"));

        clickOn(cell);
        WaitForAsyncUtils.waitForFxEvents();

        ListView<?> list = (ListView<?>) manager.scene().getRoot().lookup(".list-view");
        assertThat(list.getSelectionModel().getSelectedItem())
                .as("the click actually selected a question")
                .isNotNull();
    }

    private static TextArea textArea(Scene scene) {
        return (TextArea) scene.getRoot().lookup(".text-area");
    }

    private static Button saveButton(Scene scene) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Save Update".equalsIgnoreCase(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Save button on the question editor"));
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .collect(java.util.stream.Collectors.toSet());
    }
}
