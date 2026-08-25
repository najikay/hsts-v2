package client.features.exambuild;

import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import common.dto.approval.ApprovalState;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.authoring.ExamList;
import common.dto.authoring.ExamListRow;
import common.dto.authoring.ExamVersionRow;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the exam list (E7.10 — F3.6, F4.2).
 *
 * <p>What only a booted toolkit can show: that {@link ExamListView} builds at all, that rows
 * really arrive in the table, that <b>clicking an exam really swaps the versions panel</b>, that
 * a sent-back version really paints its reason where F4.2 wants it, and that the buttons a state
 * permits really appear on that version's card and nowhere else. Every one of those is a wiring
 * claim rather than a logic claim, and the FX-free {@link ExamListSessionTest} cannot make any
 * of them.
 *
 * <p>Without this file nothing in the build ever constructs {@link ExamListView}, and a null
 * dereference in {@code build()} would ship green.
 *
 * <p><b>It is not yet on the JaCoCo exclusion list</b>, unlike every other feature view in this
 * client. That list is in the pom's plugin config, which is outside Member A's scope, so the
 * exclusion travels with the assembly commit the same way {@code QuestionEditorView}'s did. Said
 * here rather than assumed, because "it is excluded anyway" is the kind of sentence that stops
 * being true quietly and is then relied on by the next reader.
 *
 * <h2>Three deviations from the house layout, all deliberate</h2>
 *
 * <p><b>It lives beside the feature</b> rather than in {@code client.ui}, where the shell's
 * interaction tests are: the scope guard opens {@code client/features/exambuild/**} to this epic
 * and not {@code client/ui/**}, and writing it outside the feature's package would be a scope
 * request for no gain. Same call {@code BankScreenInteractionTest} made.
 *
 * <p><b>It drives the view directly rather than navigating to it.</b> The route mapping lands in
 * the assembly commit, so navigating would make this file fail for the same reason
 * {@link ExamListWiringGuardTest} already fails. That would be one gap reported twice, and it
 * would hide a real rendering defect behind an expected failure. The guard owns the reachability
 * claim; this file owns the rendering claim; the two fail for different reasons.
 *
 * <p><b>No test presses Submit or Revise.</b> Both open a modal {@code WarnConfirm} that blocks
 * on {@code showAndWait}, which in a headless run is a hang rather than a failure. What is
 * checkable here is that the right button is on the right card, which is the half a session test
 * cannot see; what the button then sends is {@link ExamListSessionTest}'s {@code ActionTokens},
 * which drives it directly. The seam between the two is the click handler, and it is one line.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class ExamListInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-08-07T06:00:00Z");

    private static final LoginResult DANA = new LoginResult(2, "dana.cohen", "Dana Cohen",
            Role.TEACHER,
            List.of(new CourseRef("11", "Algebra"), new CourseRef("12", "Calculus")), 0);

    private static final String SENT_BACK = "Question 4 has two correct answers.";

    private static ExamVersionRow version(long id, int no, ApprovalState state, String reason,
                                          int questions, int minutes) {
        return new ExamVersionRow(id, no, state, reason, questions, minutes,
                no == 1 ? SPRING : SUMMER, 1);
    }

    private static final ExamListRow MIDTERM = new ExamListRow(900L, "110101", "11", "Algebra",
            "Algebra midterm", 3,
            List.of(version(9003L, 3, ApprovalState.DRAFT, "", 12, 90),
                    version(9002L, 2, ApprovalState.REJECTED, SENT_BACK, 12, 90),
                    version(9001L, 1, ApprovalState.APPROVED, "", 10, 60)));

    private static final ExamListRow FINAL_EXAM = new ExamListRow(901L, "120101", "12",
            "Calculus", "Calculus final", 1,
            List.of(version(9101L, 1, ApprovalState.PENDING, "", 20, 120)));

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
        // Nothing here: openList initialises ScreenManager directly rather than booting the
        // shell, so no connect screen exists to race the teardown.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The wiring claims =============================

    @Test
    @DisplayName("⚑ the exam list builds and its rows reach the table")
    void rowsRender() {
        Scene scene = openList(this::serverHasTwoExams);

        assertThat(cellTexts(scene))
                .contains("Algebra midterm", "110101", "11 · Algebra",
                        "Calculus final", "120101", "12 · Calculus");
    }

    /**
     * The first exam's versions are open when the screen arrives.
     *
     * <p>Three cards, drafts included, which is the visible half of the retirement: the screen
     * this replaces could not show a draft at all.
     */
    @Test
    @DisplayName("⚑ the selected exam's versions paint, drafts included")
    void versionsPaint() {
        Scene scene = openList(this::serverHasTwoExams);

        Set<String> labels = labelTexts(scene);
        assertThat(labels)
                .as("all three versions of the first exam, newest first")
                .anySatisfy(text -> assertThat(text).startsWith("Version 3 · 12 questions"))
                .anySatisfy(text -> assertThat(text).startsWith("Version 2 · 12 questions"))
                .anySatisfy(text -> assertThat(text).startsWith("Version 1 · 10 questions"));
        assertThat(labels)
                .as("the draft is on screen, which MY_APPROVALS_GET could never show")
                .contains("Draft");
    }

    /**
     * F4.2 on a real screen: the reason is on the exam, not only in a bell.
     *
     * <p>The one claim on this screen a teacher could otherwise lose entirely by dismissing a
     * notification, and the reason the screen it replaces existed at all.
     */
    @Test
    @DisplayName("⚑ a sent-back version paints its reason on its own card (F4.2)")
    void rejectionReasonPaints() {
        Scene scene = openList(this::serverHasTwoExams);

        assertThat(labelTexts(scene))
                .contains(SENT_BACK)
                .contains(ExamListCopy.REJECTED_PANEL_TITLE);
    }

    @Test
    @DisplayName("⚑ clicking another exam really swaps the versions panel (real input)")
    void clickingAnExamSwapsThePanel() {
        Scene scene = openList(this::serverHasTwoExams);

        clickOn(rowShowing(scene, "Calculus final"));
        WaitForAsyncUtils.waitForFxEvents();

        // The act first: a click that landed somewhere else would leave the old panel up and
        // every assertion below would then be about a screen this test never reached.
        assertThat(labelTexts(scene))
                .as("the panel is describing the exam that was clicked")
                .contains("Calculus final", "12 · Calculus · 1 version");
        assertThat(labelTexts(scene))
                .as("and has stopped describing the one before it")
                .doesNotContain(SENT_BACK);
    }

    /**
     * The buttons a state permits, on a real screen.
     *
     * <p>The session decides this and its own test pins the decision; what only a toolkit shows
     * is that the decision reaches a control. A card that rendered every button regardless would
     * pass every assertion in {@code Permissions} and hand a teacher a Submit on an approved
     * exam.
     */
    @Test
    @DisplayName("⚑ Submit is on the draft alone, Revise on the two that are not drafts")
    void actionsMatchTheState() {
        Scene scene = openList(this::serverHasTwoExams);

        assertThat(buttonsNamed(scene, ExamListCopy.SUBMIT))
                .as("v3 is the only DRAFT, and contract §5.4 lets nothing else be submitted")
                .hasSize(1);
        assertThat(buttonsNamed(scene, ExamListCopy.REVISE))
                .as("v2 REJECTED and v1 APPROVED may both be revised; the draft may not")
                .hasSize(2);
    }

    @Test
    @DisplayName("⚑ a version with only one state shows only that state's button")
    void pendingShowsReviseOnly() {
        Scene scene = openList(this::serverHasTwoExams);

        clickOn(rowShowing(scene, "Calculus final"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(scene))
                .as("the act landed before anything is claimed about the buttons")
                .contains("Calculus final");
        assertThat(buttonsNamed(scene, ExamListCopy.REVISE)).hasSize(1);
        assertThat(buttonsNamed(scene, ExamListCopy.SUBMIT))
                .as("a PENDING version is not submittable, so the button is absent rather than "
                        + "present and refused")
                .isEmpty();
    }

    @Test
    @DisplayName("⚑ a teacher with no exams gets the panel, not a blank box")
    void emptyStateDraws() {
        Scene scene = openList(connection ->
                connection.replyOk(Verb.EXAM_LIST, ExamList.empty()));

        assertThat(labelTexts(scene))
                .contains(ExamListCopy.EMPTY_TITLE, ExamListCopy.EMPTY_HINT)
                .contains(ExamListCopy.NO_SELECTION);
    }

    @Test
    @DisplayName("⚑ a notification's version opens the exam that owns it, on a real screen")
    void deepLinkOpensTheOwningExam() {
        Scene scene = openList(this::serverHasTwoExams,
                NavParams.of("examVersionId", 9101L));

        assertThat(labelTexts(scene))
                .as("she followed the notification about the calculus final and landed on it")
                .contains("Calculus final");
    }

    // ===================== Harness ========================================

    private void serverHasTwoExams(FakeClientConnection connection) {
        connection.respondTo(Verb.EXAM_LIST, request ->
                Message.ok(request, new ExamList(List.of(MIDTERM, FINAL_EXAM))));
    }

    private Scene openList(Consumer<FakeClientConnection> script) {
        return openList(script, NavParams.empty());
    }

    /**
     * Brings up just enough app for the screen to run, and deliberately not the whole shell.
     *
     * <p>Copied from {@code BankScreenInteractionTest.openBankAs} including its reason: booting
     * {@code ClientApp} also boots the connect screen, whose queued connect attempt wakes up
     * after {@code resetForTests} has nulled the event bus and fails a test that was never about
     * connecting. {@code ScreenManager.init} is the seam the shell itself uses.
     */
    private Scene openList(Consumer<FakeClientConnection> script, NavParams params) {
        ScreenManager manager = ScreenManager.getInstance();
        interact(() -> {
            ClientEventBus bus = new ClientEventBus(ClientEventBus.newBus(),
                    new DirectFxThreadPoster());
            manager.init(new Stage(), bus, new ThemeManager(ThemeState.ephemeral(bus)));
        });
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> {
            FakeClientConnection connection = new FakeClientConnection("demo-server", 5555);
            try {
                connection.connect();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            connection.replyOk(Verb.LOGIN, DANA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));
            manager.setSignedInUser(DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();

        Scene[] holder = new Scene[1];
        interact(() -> {
            ExamListView view = new ExamListView();
            Scene scene = new Scene(view.view(), 1280, 820);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.onShow(params);
            holder[0] = scene;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return holder[0];
    }

    /** Every button carrying this label, so a count can be asserted rather than a presence. */
    private static List<Button> buttonsNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .toList();
    }

    /** The table row whose cells contain {@code text}, so a click lands on a real row. */
    private static Node rowShowing(Scene scene, String text) {
        return scene.getRoot().lookupAll(".table-row-cell").stream()
                .filter(TableRow.class::isInstance)
                .filter(row -> row.lookupAll(".table-cell").stream()
                        .filter(Labeled.class::isInstance)
                        .map(cell -> ((Labeled) cell).getText())
                        .filter(Objects::nonNull)
                        .anyMatch(cellText -> cellText.contains(text)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row showing " + text));
    }

    private static Set<String> cellTexts(Scene scene) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> labelTexts(Scene scene) {
        return scene.getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
