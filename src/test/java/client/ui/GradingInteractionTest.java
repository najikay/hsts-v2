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
import common.dto.grading.ApproveRequest;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

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

    /**
     * The U-46 walk: two ticks, one request (2026-08-30, live session).
     *
     * <p>The defect this replaces: the table was in {@code MULTIPLE} selection mode with a
     * listener mirroring its selected rows into the session, so a plain click on the next
     * student replaced the selection instead of adding to it and Approve selected approved one
     * paper at a time. Naji, in the session: "approving only one at a time".
     *
     * <p>So the assertions are the three halves of the fix: the checkbox column is what is
     * chosen, a click on a third row leaves the ticks alone, and the request carries both ids.
     */
    @Test
    @DisplayName("\u2691 two ticked rows go in one GRADES_APPROVE, and a click elsewhere keeps them")
    void tickedRowsAreApprovedTogether() {
        List<Long> sentIds = new ArrayList<>();
        ScreenManager manager = signIn(gradingServer(sentIds));
        openGrading(manager);
        openFirstSitting(manager);
        List<Integer> asked = confirmEverything(manager);

        assertThat(button(manager, GradingCopy.APPROVE_SELECTED).isDisabled())
                .as("nothing is ticked, so there is nothing to approve")
                .isTrue();

        tick(manager, "Maya Levi");
        tick(manager, "Omer Katz");

        assertThat(button(manager, GradingCopy.APPROVE_SELECTED).isDisabled())
                .as("two ticks are two grades to publish")
                .isFalse();
        assertThat(selectedStudent(manager))
                .as("a tick is not a row click: the box must not aim Review and Change score")
                .isNull();

        clickRow(manager, "Yael Azoulay");

        assertThat(tickOf(manager, "Maya Levi").isSelected())
                .as("a plain click on a third student is not a change of mind about the first")
                .isTrue();
        assertThat(tickOf(manager, "Omer Katz").isSelected()).isTrue();
        assertThat(tickOf(manager, "Yael Azoulay").isSelected())
                .as("and clicking her row does not tick her either")
                .isFalse();
        assertThat(selectedStudent(manager))
                .as("the row click still aims Review and Change score, which is all it is for")
                .isEqualTo("Yael Azoulay");

        press(manager, GradingCopy.APPROVE_SELECTED);

        assertThat(sentIds).as("both, in one request").containsExactly(1L, 2L);
        assertThat(asked).as("and she was asked about both, not about one").containsExactly(2);
    }

    /**
     * Select all, and the row that cannot be approved (2026-08-30, live session, U-46).
     *
     * <p>An approved paper is offered no checkbox at all: overriding one answers {@code CONFLICT}
     * by design, and a box a teacher can tick and not act on is a control that lies. It is also
     * why the confirmation counts three and not four.
     */
    @Test
    @DisplayName("\u2691 Select all ticks every row still waiting, and only those")
    void selectAllTicksEveryApprovableRow() {
        List<Long> sentIds = new ArrayList<>();
        ScreenManager manager = signIn(gradingServer(sentIds));
        openGrading(manager);
        openFirstSitting(manager);
        List<Integer> asked = confirmEverything(manager);

        assertThat(tickIfAny(manager, "Noa Bar"))
                .as("an approved paper cannot be approved again, so it is offered no box")
                .isEmpty();

        press(manager, GradingCopy.SELECT_ALL);

        assertThat(tickOf(manager, "Maya Levi").isSelected()).isTrue();
        assertThat(tickOf(manager, "Omer Katz").isSelected()).isTrue();
        assertThat(tickOf(manager, "Yael Azoulay").isSelected()).isTrue();

        press(manager, GradingCopy.APPROVE_SELECTED);

        assertThat(sentIds).containsExactly(1L, 2L, 3L);
        assertThat(asked).as("three, not the four rows on screen").containsExactly(3);
    }

    /**
     * The whole gesture sequence, in one go (2026-08-30, live session, U-46 addendum).
     *
     * <p>Naji, after the tick column was described: "the grading tab breaks completely and easily
     * after a few clicks". So this test is a session at the screen rather than one assertion:
     * select a student, select another, tick two, Select all, click a third row, approve, open a
     * paper and come back, aim Change score, tick what is left and approve again. After every
     * step it asserts the one invariant the screen has to keep, which is that <b>what the
     * controls offer is what the server last said</b>: the ticks are the session's, the count in
     * the confirmation is the ticks, and Change score is aimed at a row in the state the re-read
     * left it in.
     *
     * <p>It found one on its first run, in the first three lines: with the mirroring listener
     * gone, a row click changes nothing in the session, and the screen re-renders <b>only</b> on
     * a session change. So Change score kept whatever state it had when the session was last
     * touched - dead on a paper she had just clicked and could still change, and alive again the
     * moment she ticked something unrelated. The screen now re-renders on the table's selection
     * too; see {@code GradingQueueView#buildTable()}.
     */
    @Test
    @DisplayName("\u2691 a whole session at the screen leaves nothing disagreeing with the server")
    void theWholeSequenceStaysConsistent() {
        List<Long> sentIds = new ArrayList<>();
        ScreenManager manager = signIn(gradingServer(sentIds));
        openGrading(manager);
        openFirstSitting(manager);
        List<Integer> asked = confirmEverything(manager);

        // 1. Two plain clicks, one after the other. Neither ticks anything: a click is how she
        //    aims Review and Change score, and that is all it is.
        clickRow(manager, "Maya Levi");
        clickRow(manager, "Omer Katz");
        assertThat(ticked(manager)).isEmpty();
        assertThat(button(manager, GradingCopy.APPROVE_SELECTED).isDisabled()).isTrue();
        assertThat(button(manager, GradingCopy.OVERRIDE).isDisabled())
                .as("Omer's paper is still AUTO, so it can still be changed")
                .isFalse();

        // 2. Tick two, then Select all, then click a third row: the ticks survive the click.
        tick(manager, "Maya Levi");
        tick(manager, "Omer Katz");
        assertThat(selectedStudent(manager))
                .as("ticking two rows does not re-aim Change score at either of them")
                .isEqualTo("Omer Katz");
        press(manager, GradingCopy.SELECT_ALL);
        assertThat(ticked(manager)).containsExactly("Maya Levi", "Omer Katz", "Yael Azoulay");
        clickRow(manager, "Yael Azoulay");
        assertThat(ticked(manager))
                .as("the defect: a plain click used to replace the whole selection")
                .containsExactly("Maya Levi", "Omer Katz", "Yael Azoulay");

        // 3. Untick one, so the confirmation has a number to get wrong, and leave the selection
        //    on a row that is about to be published: that is where the screen used to end up
        //    disagreeing with itself.
        tick(manager, "Yael Azoulay");
        clickRow(manager, "Maya Levi");
        assertThat(ticked(manager)).containsExactly("Maya Levi", "Omer Katz");

        press(manager, GradingCopy.APPROVE_SELECTED);

        assertThat(asked).containsExactly(2);
        assertThat(sentIds).containsExactly(1L, 2L);

        // 4. After the write the screen re-reads, so nothing may still be ticked and the two
        //    published papers may not be offered a box at all.
        assertThat(ticked(manager)).isEmpty();
        assertThat(button(manager, GradingCopy.APPROVE_SELECTED).isDisabled()).isTrue();
        assertThat(tickIfAny(manager, "Maya Levi")).isEmpty();
        assertThat(tickIfAny(manager, "Omer Katz")).isEmpty();

        // 5. Maya's row is still the selected one and her paper has just been published, so the
        //    re-read has to take Change score away from under a selection nobody touched.
        assertThat(button(manager, GradingCopy.OVERRIDE).isDisabled())
                .as("her paper was published a moment ago; Change score cannot still offer it")
                .isTrue();
        clickRow(manager, "Yael Azoulay");
        assertThat(button(manager, GradingCopy.OVERRIDE).isDisabled())
                .as("and the row she picks next is still hers to change")
                .isFalse();

        // 6. Open a paper and come back, which re-reads both the rail and the sitting.
        press(manager, GradingCopy.REVIEW);
        assertThat(manager.navigator().current().orElseThrow().routeId())
                .isEqualTo(Routes.GRADE_REVIEW.id());
        interact(() -> manager.navigator().navigate(Routes.GRADING.id(), NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(ticked(manager)).as("coming back is a re-read, and a re-read clears").isEmpty();
        assertThat(button(manager, GradingCopy.APPROVE_SELECTED).isDisabled()).isTrue();

        // 7. What is left can still be approved, and the second approval carries only it.
        tick(manager, "Yael Azoulay");
        press(manager, GradingCopy.APPROVE_SELECTED);

        assertThat(asked).containsExactly(2, 1);
        assertThat(sentIds).containsExactly(1L, 2L, 3L);
        assertThat(ticked(manager)).isEmpty();
        assertThat(labels(manager))
                .as("and not one step of that was refused")
                .doesNotContain(GradingCopy.APPROVE_FAILED, GradingCopy.OVERRIDE_CONFLICT);
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
     * Three papers waiting and one already published, with every approval recorded.
     *
     * <p>Stateful in the same way {@code reviewOpensTheMarkedPaperAndApproves}'s server is: the
     * approval is followed by a re-read, and a fixture that answered it with the pre-approval
     * rows would be testing itself.
     */
    private Consumer<FakeClientConnection> gradingServer(List<Long> sentIds) {
        return connection -> {
            connection.replyOk(Verb.GRADING_QUEUE_GET, new GradingQueue(List.of(summary())));
            connection.respondTo(Verb.GRADING_EXECUTION_GET, request ->
                    Message.ok(request, new ExecutionGrades(summary(), sittingRows(sentIds))));
            connection.respondTo(Verb.GRADE_REVIEW_GET, request ->
                    Message.ok(request,
                            new GradeReview(sittingRows(sentIds).get(0), markedPaper())));
            connection.respondTo(Verb.GRADES_APPROVE, request -> {
                sentIds.addAll(((ApproveRequest) request.getPayload()).gradeIds());
                return Message.ok(request,
                        new ApproveResult(((ApproveRequest) request.getPayload())
                                .gradeIds().size(), 0, List.of()));
            });
        };
    }

    /** Maya, Omer and Yael still waiting; Noa's paper already published. */
    private static List<StudentGradeRow> sittingRows(List<Long> approvedIds) {
        List<StudentGradeRow> rows = new ArrayList<>();
        rows.add(gradeRow(1, "Maya Levi", 71, approvedIds.contains(1L)));
        rows.add(gradeRow(2, "Omer Katz", 40, approvedIds.contains(2L)));
        rows.add(gradeRow(3, "Yael Azoulay", 80, approvedIds.contains(3L)));
        rows.add(gradeRow(4, "Noa Bar", 65, true));
        return rows;
    }

    private static StudentGradeRow gradeRow(long gradeId, String name, int auto, boolean done) {
        return new StudentGradeRow(gradeId, gradeId, name, auto, done ? auto : null, auto,
                done ? GradeState.APPROVED : GradeState.AUTO, null, null,
                done ? Instant.parse("2026-06-03T09:00:00Z") : null);
    }

    /**
     * Answers the bulk confirmation with "yes", and records what it was asked.
     *
     * <p>The confirmation is a modal {@code showAndWait}, which blocks the FX thread this test is
     * driving: pressing Approve selected for real would hang a headless run rather than fail it,
     * which is why no test in this codebase presses one. {@code GradingQueueView} therefore keeps
     * the asking in a field, and this reaches it the way
     * {@code ReleaseManagerInteractionTest} reaches a screen's session. What the recorded counts
     * prove is the other half of U-46: the number in {@code bulkConfirm} is the number of ticks.
     *
     * @return the counts the screen asked about, in order, filled in as the test runs
     */
    private List<Integer> confirmEverything(ScreenManager manager) {
        List<Integer> asked = new ArrayList<>();
        interact(() -> {
            Object screen = manager.screens().get(Routes.GRADING.id());
            try {
                java.lang.reflect.Field field =
                        screen.getClass().getDeclaredField("bulkConfirm");
                field.setAccessible(true);
                field.set(screen, (IntPredicate) count -> {
                    asked.add(count);
                    return true;
                });
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        return asked;
    }

    /** Ticks one student's checkbox with the robot, which is the gesture under test. */
    private void tick(ScreenManager manager, String student) {
        clickOn(tickOf(manager, student));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Clicks a row where no control is, which is the gesture that used to wipe the selection.
     *
     * <p>Aimed at the row's own centre: the checkbox column is 52px on the left, so the click
     * lands on a cell and reaches the table's selection model exactly as a teacher's does.
     */
    private void clickRow(ScreenManager manager, String student) {
        clickOn(rowOf(manager, student));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Which students are ticked, in table order, which is the screen's own answer. */
    private static List<String> ticked(ScreenManager manager) {
        return table(manager.scene()).table().getItems().stream()
                .map(item -> ((StudentGradeRow) item).studentName())
                .filter(name -> tickIfAny(manager, name).map(CheckBox::isSelected).orElse(false))
                .toList();
    }

    private CheckBox tickOf(ScreenManager manager, String student) {
        return tickIfAny(manager, student)
                .orElseThrow(() -> new AssertionError("no checkbox on the row for " + student));
    }

    /** The checkbox on one student's row, when that row is offered one. */
    private static Optional<CheckBox> tickIfAny(ScreenManager manager, String student) {
        return manager.scene().getRoot().lookupAll(".check-box").stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(box -> student.equals(studentOf(box)))
                .findFirst();
    }

    private static TableRow<?> rowOf(ScreenManager manager, String student) {
        return manager.scene().getRoot().lookupAll(".table-row-cell").stream()
                .filter(TableRow.class::isInstance)
                .map(node -> (TableRow<?>) node)
                .filter(row -> row.getItem() instanceof StudentGradeRow grade
                        && student.equals(grade.studentName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + student));
    }

    /**
     * Which student a node in a table cell belongs to.
     *
     * <p>Walks up to the {@link TableRow} rather than counting cells: the row knows its item, and
     * a lookup over the scene has no order worth relying on.
     */
    private static String studentOf(Node node) {
        for (Node parent = node; parent != null; parent = parent.getParent()) {
            if (parent instanceof TableRow<?> row && row.getItem() instanceof StudentGradeRow grade) {
                return grade.studentName();
            }
        }
        return null;
    }

    private static String selectedStudent(ScreenManager manager) {
        Object selected = table(manager.scene()).table().getSelectionModel().getSelectedItem();
        return selected instanceof StudentGradeRow grade ? grade.studentName() : null;
    }

    private static Button button(ScreenManager manager, String label) {
        return manager.scene().getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> label.equals(candidate.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
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
