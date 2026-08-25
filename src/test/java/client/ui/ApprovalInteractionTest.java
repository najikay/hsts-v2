package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.approval.ApprovalCopy;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.approval.ApprovalDecision;
import common.dto.approval.ApprovalQueue;
import common.dto.approval.ApprovalRow;
import common.dto.approval.ApprovalState;
import common.dto.approval.ExamPreview;
import common.dto.approval.ExamRejectRequest;
import common.dto.approval.PreviewAnswerRow;
import common.dto.approval.TeacherOnlyBlock;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.exam.ExamQuestion;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the approval workflow (E8.3/E8.4/E8.5 ⚑ — F4).
 *
 * <p>The things only a booted toolkit can show, and the middle one is the whole point of this
 * epic: a coordinator opening her queue, opening an exam from it, and <b>seeing the paper</b>
 * rendered with the student's own question card — a stem, four options and the answer key
 * marked in the panel beside it. That is the v1 failure, checked by looking at the scene graph
 * rather than by trusting a DTO.
 *
 * <p>The rejection is driven with real input all the way through: a click on Send back, a
 * typed reason in the modal, and the request that goes out afterwards. The rule behind the
 * field is asserted deterministically in {@code ApprovalSessionTest} and
 * {@code ApprovalDtoTest}; what this adds is that the dialog exists, that its confirm button
 * is disabled until the reason is long enough, and that the exam name is on it.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ApprovalInteractionTest extends ApplicationTest {

    private static final Instant SUBMITTED = Instant.parse("2026-08-20T09:00:00Z");
    private static final long CALCULUS_V1 = 31L;

    private static final LoginResult RINA = new LoginResult(3, "rina.barak", "רינה ברק",
            Role.COORDINATOR, List.of(new CourseRef("12", "חדו\"א")), 0);

    private static final ApprovalRow PENDING = new ApprovalRow(CALCULUS_V1, "101201",
            "Calculus Midterm", "12", "Calculus", 1, "Dana Cohen", SUBMITTED, 2, 60,
            ApprovalState.PENDING, "", false, 0);

    private static final ExamQuestion QUESTION_ONE = new ExamQuestion(901, "12001", 1, 50,
            "What are the roots of x squared minus 5x plus 6?",
            "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5", null);
    private static final ExamQuestion QUESTION_TWO = new ExamQuestion(902, "12002", 2, 50,
            "What is the derivative of x squared?",
            "x", "2x", "x cubed over 3", "2", null);

    private static final ExamPreview PREVIEW = new ExamPreview(PENDING,
            "Answer every question. Calculators are not allowed.",
            List.of(QUESTION_ONE, QUESTION_TWO),
            new TeacherOnlyBlock("Mark question 2 generously; it was covered late.",
                    "Dana Cohen",
                    List.of(new PreviewAnswerRow(901, 1, (byte) 2),
                            new PreviewAnswerRow(902, 2, (byte) 2))));

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
        // Each test boots the app itself, as UiSmokeTest does.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The queue =====================================

    @Test
    @DisplayName("the queue lists what is waiting, with the facts a coordinator triages by (F4.1)")
    void queueListsWhatIsWaiting() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        openQueue(manager);

        Scene scene = manager.scene();
        assertThat(labelTexts(scene)).contains(ApprovalCopy.QUEUE_TITLE, ApprovalCopy.QUEUE_SUBTITLE);
        assertThat(scene.getRoot().lookup(".hsts-table")).isNotNull();
        assertThat(cellTexts(scene))
                .as("exam, course, teacher and length, without opening anything")
                .contains("101201 · Calculus Midterm (v1)", "12 · Calculus", "Dana Cohen",
                        "2 questions");
    }

    @Test
    @DisplayName("an empty queue explains which kind of empty it is (PRD §4.1)")
    void emptyQueueExplainsItself() {
        ScreenManager manager = signIn(connection ->
                connection.replyOk(Verb.APPROVALS_QUEUE_GET, ApprovalQueue.notACoordinator()));
        openQueue(manager);

        assertThat(manager.scene().getRoot().lookup(".hsts-empty-state")).isNotNull();
        assertThat(labelTexts(manager.scene()))
                .contains(ApprovalCopy.QUEUE_NOT_COORDINATOR_TITLE);
    }

    // ===================== The preview (E8.4 ⚑) ==========================

    @Test
    @DisplayName("the preview renders the paper with the student's own card and options ⚑")
    void previewRendersTheStudentPaper() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        openPreview(manager);

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookupAll(".question-card"))
                .as("one card per question, drawn by the same component the student is served")
                .hasSize(2);
        assertThat(scene.getRoot().lookupAll(".question-option"))
                .as("four options per question, exactly as she will see them")
                .hasSize(8);
        assertThat(labelTexts(scene))
                .contains("Calculus Midterm")
                .contains("Answer every question. Calculators are not allowed.");
        assertThat(radioTexts(scene))
                .contains("1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5");
    }

    @Test
    @DisplayName("and it is read-only: the coordinator cannot answer the exam she is reviewing")
    void previewIsReadOnly() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        openPreview(manager);

        assertThat(radios(manager.scene()))
                .allSatisfy(radio -> assertThat(radio.isDisabled()).isTrue());
    }

    @Test
    @DisplayName("the teacher-only panel carries the notes and the marked answer key")
    void teacherPanelCarriesTheKey() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        openPreview(manager);

        Scene scene = manager.scene();
        assertThat(scene.getRoot().lookup(".teacher-only-panel")).isNotNull();
        assertThat(labelTexts(scene))
                .contains(ApprovalCopy.TEACHER_PANEL_TITLE, ApprovalCopy.ANSWER_KEY_TITLE)
                .contains("Mark question 2 generously; it was covered late.")
                .contains("Written by Dana Cohen")
                .contains("Q1 · option 2", "Q2 · option 2");
        assertThat(scene.getRoot().lookupAll(".answer-key"))
                .as("and the right option is marked on the paper itself, one per question")
                .hasSize(2);
        assertThat(labelTexts(scene))
                .as("the banner says which pane is which, so nobody mistakes one for the other")
                .contains(ApprovalCopy.PREVIEW_BANNER);
    }

    // ===================== Rejecting, with real input ====================

    @Test
    @DisplayName("queue to preview to reject, with a typed reason (T-4.2) ⚑")
    void rejectWithATypedReason() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
            connection.replyOk(Verb.EXAM_REJECT, new ApprovalDecision(
                    new ApprovalRow(CALCULUS_V1, "101201", "Calculus Midterm", "12", "Calculus",
                            1, "Dana Cohen", SUBMITTED, 2, 60, ApprovalState.REJECTED,
                            "Question 2 has two correct answers.", false, 1), false));
        });
        openPreview(manager);
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        connection.clearSent();

        clickOn(buttonNamed(manager.scene(), ApprovalCopy.REJECT_CONFIRM));
        WaitForAsyncUtils.waitForFxEvents();

        Scene dialog = topWindowScene();
        assertThat(labelTexts(dialog))
                .as("the dialog names the exam, so there is no doubt which one is going back")
                .anySatisfy(text -> assertThat(text).contains("Calculus Midterm"));

        Button send = buttonNamed(dialog, ApprovalCopy.REJECT_CONFIRM);
        assertThat(send.isDisabled())
                .as("a required reason means the button starts unavailable, not that it errors later")
                .isTrue();

        TextArea reason = (TextArea) dialog.getRoot().lookup(".reject-reason");
        clickOn(reason);
        write("no");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(send.isDisabled())
                .as("two characters is not a reason a teacher can act on")
                .isTrue();
        assertThat(labelTexts(dialog))
                .anySatisfy(text -> assertThat(text).contains("more characters needed"));

        write("w, question 2 has two correct answers.");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(send.isDisabled()).isFalse();

        clickOn(send);
        WaitForAsyncUtils.waitForFxEvents();

        Message sent = connection.sentMessages().stream()
                .filter(message -> message.getVerb() == Verb.EXAM_REJECT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no EXAM_REJECT was sent"));
        ExamRejectRequest request = (ExamRejectRequest) sent.getPayload();
        assertThat(request.examVersionId()).isEqualTo(CALCULUS_V1);
        assertThat(request.reason()).isEqualTo("now, question 2 has two correct answers.");
        assertThat(request.hasUsableReason()).isTrue();
    }

    @Test
    @DisplayName("cancelling the dialog sends nothing at all")
    void cancellingSendsNothing() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.APPROVALS_QUEUE_GET, new ApprovalQueue(List.of(PENDING), true));
            connection.replyOk(Verb.EXAM_PREVIEW_GET, PREVIEW);
        });
        openPreview(manager);
        FakeClientConnection connection = (FakeClientConnection) manager.getClient();
        connection.clearSent();

        clickOn(buttonNamed(manager.scene(), ApprovalCopy.REJECT_CONFIRM));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(buttonNamed(topWindowScene(), ApprovalCopy.KEEP_LOOKING));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(connection.sentMessages())
                .extracting(Message::getVerb)
                .doesNotContain(Verb.EXAM_REJECT);
    }

    // ===================== The teacher's side (E8.6) =====================

    // Retired. The author's own list moved to E7.10's exam list with MY_APPROVALS_GET
    // (APPROVAL ruling 1); ExamListInteractionTest drives the rejection panel on the real
    // toolkit now, including the deep link that used to be exercised here.

    // ===================== Fixture =======================================

    private ScreenManager signIn(Consumer<FakeClientConnection> script) {
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
            connection.replyOk(Verb.LOGIN, RINA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, RINA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    private void openQueue(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.APPROVALS.id()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openPreview(ScreenManager manager) {
        openQueue(manager);
        interact(() -> manager.navigator().navigate(Routes.EXAM_PREVIEW.id(),
                NavParams.of("examVersionId", CALCULUS_V1)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** @return the scene of the modal that is currently on top, which is the dialog. */
    private Scene topWindowScene() {
        List<Window> windows = Window.getWindows().stream().filter(Window::isShowing).toList();
        assertThat(windows).as("a modal dialog to drive").hasSizeGreaterThan(1);
        return windows.get(windows.size() - 1).getScene();
    }

    private Button buttonNamed(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + text));
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static List<RadioButton> radios(Scene scene) {
        return scene.getRoot().lookupAll(".question-option").stream()
                .filter(RadioButton.class::isInstance)
                .map(RadioButton.class::cast)
                .toList();
    }

    private static Set<String> radioTexts(Scene scene) {
        return radios(scene).stream().map(RadioButton::getText).collect(Collectors.toSet());
    }

    /** The table's rendered cell strings, for the queue's columns. */
    private static Set<String> cellTexts(Scene scene) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.TableCell.class::isInstance)
                .map(node -> ((javafx.scene.control.TableCell<?, ?>) node).getText())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
