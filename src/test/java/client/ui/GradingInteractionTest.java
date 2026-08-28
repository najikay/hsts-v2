package client.ui;

import client.core.ClientApp;
import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.Routes;
import client.core.ScreenManager;
import client.events.PushEventBridge;
import client.features.login.ShellBoot;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import client.ui.components.DataTable;
import client.ui.components.logic.AsyncViewState;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.grading.ExecutionGrades;
import common.dto.grading.ExecutionGradingSummary;
import common.dto.grading.GradeState;
import common.dto.grading.GradingQueue;
import common.dto.grading.StudentGradeRow;
import common.protocol.Verb;
import javafx.scene.Node;
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

    // ===================== Fixture =======================================

    private static ExecutionGradingSummary summary() {
        return new ExecutionGradingSummary(EXECUTION, "Java midterm", "21", "7390",
                CLOSED, 8, 8, 8);
    }

    private static StudentGradeRow row(long gradeId, String name, int auto) {
        return new StudentGradeRow(gradeId, gradeId, name, auto, null, auto, GradeState.AUTO,
                null, null, null);
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
