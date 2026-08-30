package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.grading.GradingCopy;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.DataTable;
import client.ui.components.logic.AsyncViewState;
import client.features.results.CheckedFormCopy;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.grading.AnswerReviewRow;
import common.dto.grading.ApproveResult;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeReview;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.StudentGradeRow;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grading screen's table, with a booted toolkit (E12.5–E12.7 ⚑).
 *
 * <p>{@code GradingQueueSessionTest} already proves what the session decides; what only a
 * toolkit can show is what the {@link DataTable} ends up <em>displaying</em>, which is a
 * different question and is where the manual round found a defect the session tests could
 * never have seen.
 *
 * <p>2026-08-28, manual round 1, lead's ruling: a sitting that answered with zero rows left
 * the table shimmering its loading skeleton forever. The render only handed the table its
 * rows when they differed from what it already held, and empty equals empty, so a table that
 * had never been given anything was never given anything. The assertion is on
 * {@link DataTable#state()} rather than on a node lookup, because both the skeleton and the
 * empty state are always present in the graph and only their visibility differs.
 *
 * <p>Same escape hatch as the other UI tests: {@code ./mvnw verify -Dhsts.uitests=false}.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class GradingInteractionTest extends ApplicationTest {

    private static final Instant CLOSED = Instant.parse("2026-06-02T10:00:00Z");
    private static final long EXECUTION = 4822;

    private static final String QUESTION =
            "What are the roots of x squared minus 5x plus 6?";

    private static final LoginResult DANA = new LoginResult(1001, "dana.cohen", "Dana Cohen",
            Role.TEACHER, List.of(new CourseRef("21", "Java Programming")), 0);

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

    @Test
    @DisplayName("⚑ a sitting with no rows shows its empty state, not a skeleton forever")
    void zeroRowsReachTheEmptyState() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary())));
            connection.replyOk(Verb.GRADING_EXECUTION_GET,
                    new ExecutionGrades(summary(), List.of()));
        });
        openGrading(manager);

        openFirstSitting(manager);

        assertThat(table(manager.scene()).state())
                .as("the server answered with nothing, and nothing is a state the table can show")
                .isEqualTo(AsyncViewState.EMPTY);
    }

    @Test
    @DisplayName("a sitting with rows still renders them, and keeps doing so on a re-render")
    void rowsStillRender() {
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary())));
            connection.replyOk(Verb.GRADING_EXECUTION_GET, new ExecutionGrades(summary(),
                    List.of(row(1, "Maya Levi", 100), row(2, "Omer Katz", 40))));
        });
        openGrading(manager);

        openFirstSitting(manager);

        DataTable<?> table = table(manager.scene());
        assertThat(table.state()).isEqualTo(AsyncViewState.READY);
        assertThat(table.table().getItems()).hasSize(2);
    }

    /**
     * The U-38 walk, end to end: F8.2's missing half (2026-08-30, live session).
     *
     * <p>Until this change a teacher could approve a paper and change its score from the queue
     * and could not <em>open</em> it, which is what made F8.2 a documented PARTIAL. The walk is
     * therefore the feature: press Review on a row, read the student's answers against the key,
     * approve, and find the row approved when you come back.
     *
     * <p>The scripted server is stateful on purpose. A fixed reply per verb would answer the
     * post-approval re-read with the pre-approval rows, and the last assertion would be testing
     * the fixture rather than the screen.
     */
    @Test
    @DisplayName("⚑ Review opens the marked paper, Approve publishes it, and the row follows")
    void reviewOpensTheMarkedPaperAndApproves() {
        AtomicBoolean approved = new AtomicBoolean(false);
        ScreenManager manager = signIn(connection -> {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary())));
            connection.respondTo(Verb.GRADING_EXECUTION_GET, request ->
                    Message.ok(request, new ExecutionGrades(summary(), List.of(
                            approved.get() ? approvedRow() : row(1, "Maya Levi", 71),
                            row(2, "Omer Katz", 40)))));
            connection.respondTo(Verb.GRADE_REVIEW_GET, request ->
                    Message.ok(request, new GradeReview(
                            approved.get() ? approvedRow() : row(1, "Maya Levi", 71),
                            markedPaper())));
            connection.respondTo(Verb.GRADES_APPROVE, request -> {
                approved.set(true);
                return Message.ok(request, new ApproveResult(1, 0, List.of()));
            });
        });
        openGrading(manager);
        openFirstSitting(manager);

        press(manager, GradingCopy.REVIEW);

        assertThat(manager.navigator().current().orElseThrow().routeId())
                .isEqualTo(Routes.GRADE_REVIEW.id());
        assertThat(labels(manager))
                .as("the student's paper, marked, with the key beside it")
                .contains("1. " + QUESTION, GradingCopy.STUDENT_ANSWER,
                        CheckedFormCopy.CORRECT_ANSWER, CheckedFormCopy.CORRECT,
                        CheckedFormCopy.WRONG, CheckedFormCopy.UNANSWERED);

        press(manager, GradingCopy.APPROVE_ONE);

        assertThat(labels(manager))
                .as("an approved paper says so where its two actions were")
                .contains(GradingCopy.REVIEW_APPROVED);

        interact(() -> manager.navigator().navigate(Routes.GRADING.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table(manager.scene()).table().getItems())
                .as("coming back re-reads the sitting, so the row cannot still say AUTO")
                .element(0)
                .extracting(item -> ((StudentGradeRow) item).state())
                .isEqualTo(GradeState.APPROVED);
    }

    // ===================== Fixture =======================================

    private static ExecutionGradingSummary summary() {
        return new ExecutionGradingSummary(EXECUTION, "Java midterm", "21", "7390",
                CLOSED, 8, 8, 8);
    }

    private static StudentGradeRow row(long gradeId, String name, int auto) {
        return new StudentGradeRow(gradeId, gradeId, name, auto, null, auto, GradeState.AUTO,
                null, null, null);
    }

    private static StudentGradeRow approvedRow() {
        return new StudentGradeRow(1, 1, "Maya Levi", 71, 71, 71, GradeState.APPROVED,
                null, null, Instant.parse("2026-06-03T09:00:00Z"));
    }

    /** Three questions, one of each outcome: right, wrong, and never reached. */
    private static List<AnswerReviewRow> markedPaper() {
        return List.of(
                new AnswerReviewRow(1, "11001", QUESTION,
                        "1 and 6", "2 and 3", "minus 2 and minus 3", "0 and 5",
                        15, (byte) 2, (byte) 2, true, 15),
                new AnswerReviewRow(2, "11002", "Factor the quadratic expression completely",
                        "(x minus 1)(x minus 6)", "(x minus 2)(x minus 3)",
                        "(x plus 2)(x plus 3)", "(x minus 5)(x plus 6)",
                        15, (byte) 1, (byte) 2, false, 0),
                new AnswerReviewRow(3, "11003", "Complete the square for x squared plus 6x plus 5",
                        "(x plus 3) squared minus 4", "(x plus 3) squared plus 4",
                        "(x plus 6) squared minus 31", "(x minus 3) squared minus 4",
                        15, null, (byte) 1, false, 0));
    }

    /**
     * Presses the one button carrying this exact label.
     *
     * <p>Fired rather than clicked with the robot: the Review button lives in a table cell, and
     * where a virtualised cell happens to land on screen is not what this test is about.
     */
    private void press(ScreenManager manager, String label) {
        Set<Node> found = manager.scene().getRoot().lookupAll(".button");
        Button button = found.stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> label.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
        interact(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Every piece of text on the mounted screen, for asserting what a teacher can read. */
    private static List<String> labels(ScreenManager manager) {
        return manager.scene().getRoot().lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .toList();
    }

    /** Boots the app, attaches a scripted server, and enters Dana's shell. */
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
            connection.replyOk(Verb.LOGIN, DANA);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));

            ShellBoot.enter(manager, DANA);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    private void openGrading(ScreenManager manager) {
        interact(() -> manager.navigator().navigate(Routes.GRADING.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Selects the first sitting on the rail, which is the gesture that opens it.
     *
     * <p>Driven through the selection model rather than the robot: the screen wires opening to
     * the list's <em>selection</em>, so selecting is the real gesture and a click is only one
     * of the ways a teacher produces it (the arrow keys are the other).
     */
    private void openFirstSitting(ScreenManager manager) {
        ListView<?> queue = queueList(manager.scene());
        assertThat(queue.getItems()).as("the queue arrived").isNotEmpty();
        interact(() -> queue.getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ListView<?> queueList(Scene scene) {
        Node node = scene.getRoot().lookup(".list-view");
        assertThat(node).as("the grading queue rail").isInstanceOf(ListView.class);
        return (ListView<?>) node;
    }

    private static DataTable<?> table(Scene scene) {
        Node node = scene.getRoot().lookup(".hsts-table-wrapper");
        assertThat(node).as("the students table").isInstanceOf(DataTable.class);
        return (DataTable<?>) node;
    }
}
