package client.features.exambuild;

import client.core.FxTestHarness;
import client.core.NavEntry;
import client.core.NavParams;
import client.core.NavigationEvent;
import client.core.Routes;
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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableRow;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
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

    /**
     * The pure coordinator, who reaches this screen and teaches nothing.
     *
     * <p>Modelled on the seeded {@code rina.barak}: a {@code coordinators} row, zero
     * {@code course_teachers} rows, and therefore an empty {@code courses} list in the sign-in
     * payload. Wire role COORDINATOR, stored role TEACHER, which is the derivation
     * {@code docs/DEMO_ACCOUNTS.md} describes and the reason she is a starred demo account.
     */
    private static final LoginResult RINA = new LoginResult(3, "rina.barak", "Rina Barak",
            Role.COORDINATOR, List.of(), 0);

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

    /**
     * An exam with no open draft, which is the only kind §5.4's amendment lets her revise.
     *
     * <p>The midterm has a DRAFT at v3 and therefore offers Revise nowhere. Without this exam
     * the suite could not tell the amended rule apart from a screen that renders no Revise
     * button at all.
     */
    private static final ExamListRow GEOMETRY = new ExamListRow(902L, "110201", "11", "Algebra",
            "Geometry quiz", 3,
            List.of(version(9203L, 3, ApprovalState.REJECTED, SENT_BACK, 8, 45),
                    version(9202L, 2, ApprovalState.APPROVED, "", 8, 45),
                    version(9201L, 1, ApprovalState.APPROVED, "", 6, 30)));

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
        Scene scene = openList(this::serverHasThreeExams);

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
        Scene scene = openList(this::serverHasThreeExams);

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
        Scene scene = openList(this::serverHasThreeExams);

        assertThat(labelTexts(scene))
                .contains(SENT_BACK)
                .contains(ExamListCopy.REJECTED_PANEL_TITLE);
    }

    @Test
    @DisplayName("⚑ clicking another exam really swaps the versions panel (real input)")
    void clickingAnExamSwapsThePanel() {
        Scene scene = openList(this::serverHasThreeExams);

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
        Scene scene = openList(this::serverHasThreeExams);

        assertThat(buttonsNamed(scene, ExamListCopy.SUBMIT))
                .as("v3 is the only DRAFT, and contract §5.4 lets nothing else be submitted")
                .hasSize(1);
        assertThat(buttonsNamed(scene, ExamListCopy.REVISE))
                .as("⚑ NONE, and that is §5.4's amendment on a real screen: this exam has an "
                        + "open draft at v3, so no version of it may be revised. Before the "
                        + "amendment the approved v1 and the sent-back v2 both carried a button "
                        + "whose only possible outcome is now a refusal")
                .isEmpty();
    }

    /**
     * The other side of the one-draft rule, on a real screen.
     *
     * <p>{@link #actionsMatchTheState} alone cannot distinguish "the rule works" from "Revise is
     * never rendered at all", which is a mutation that would pass it. This exam has no draft and
     * every one of its versions carries the button.
     */
    @Test
    @DisplayName("⚑ an exam with no open draft carries Revise on every version")
    void reviseAppearsWhenNoDraftIsOpen() {
        Scene scene = openList(this::serverHasThreeExams);

        clickOn(rowShowing(scene, "Geometry quiz"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(scene))
                .as("the act landed before anything is claimed about the buttons")
                .contains("Geometry quiz");
        assertThat(buttonsNamed(scene, ExamListCopy.REVISE))
                .as("three versions, none of them a draft, so all three are revisable")
                .hasSize(3);
        assertThat(buttonsNamed(scene, ExamListCopy.SUBMIT))
                .as("and nothing here is submittable, because nothing here is a draft")
                .isEmpty();
    }

    @Test
    @DisplayName("⚑ a version with only one state shows only that state's button")
    void pendingShowsReviseOnly() {
        Scene scene = openList(this::serverHasThreeExams);

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

    // ===================== The new-exam door (M-3) ========================

    /**
     * The control M-3 was about, on a real screen.
     *
     * <p>Manual testing found that a teacher could edit, submit and revise what the seed had
     * written and could never start an exam: {@code EXAM_CREATE} was registered, the service
     * implemented it, {@code ExamBuilderSession.Mode.CREATE} sent it, and the only navigation
     * into the builder in the whole client carried an {@code examVersionId}. Every layer worked
     * and no control reached the one that mattered.
     *
     * <p><b>This file could not have caught that and neither could any other.</b>
     * {@code ExamBuilderSessionTest} enters {@code CREATE} by calling {@code openNew} itself, so
     * create-mode was thoroughly covered while being unreachable by a human. That is
     * {@code docs/PROBLEMS.md} P-6 exactly. The three tests below are written against
     * <em>reachability</em> rather than against the mode, because reachability is the property
     * that was missing.
     */
    @Test
    @DisplayName("⚑ M-3: the new-exam control is on the screen and offers her own courses")
    void newExamOffersHerOwnCourses() {
        Scene scene = openList(this::serverHasThreeExams);

        MenuButton newExam = newExamControl(scene);
        assertThat(newExam.isDisabled())
                .as("Dana teaches two courses, so the door is open")
                .isFalse();
        assertThat(itemTexts(newExam))
                .as("her two courses, and the prompt above them, and nothing else. A menu "
                        + "offering a course she does not teach would be a FORBIDDEN dressed "
                        + "up as a choice")
                .containsExactly(ExamListCopy.NEW_EXAM_PROMPT, "11 · Algebra", "12 · Calculus");
    }

    /**
     * The navigation itself, through the real {@code Navigator}.
     *
     * <p>Asserts the two halves that distinguish a new exam from an open one: the route id, and
     * that the parameters carry a {@code courseCode} and <b>no</b> {@code examVersionId}. A
     * control wired to {@code openInBuilder} instead would satisfy the route half and fail the
     * second, which is the mutation worth catching, because it is the one that produces a screen
     * that looks right and edits the wrong thing.
     *
     * <p><b>The item's action is fired rather than clicked, and the limit is stated rather than
     * hidden.</b> A {@code MenuButton}'s popup is a separate window, and this file already
     * refuses to press modal popups because in a headless run they hang instead of failing. What
     * is checked here is a real control, looked up from a real scene, whose real action performs
     * a real navigation. What is not checked is that the popup opens on a mouse press, which is
     * JavaFX's own behaviour and not this screen's wiring.
     */
    @Test
    @DisplayName("⚑ M-3: picking a course really navigates to the builder, with no version")
    void newExamNavigatesToTheBuilderWithACourse() {
        Scene scene = openList(this::serverHasThreeExams);

        ScreenManager manager = ScreenManager.getInstance();
        manager.navigator().register(Routes.EXAM_BUILD);
        manager.screens().register(ExamBuildRoutes.BUILDER, StubScreen::new);

        List<NavigationEvent> navigations = new java.util.ArrayList<>();
        manager.navigator().addListener(navigations::add);

        MenuItem algebra = itemNamed(newExamControl(scene), "11 · Algebra");
        interact(() -> algebra.getOnAction().handle(null));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigations)
                .as("the act landed: something navigated")
                .hasSize(1);
        NavEntry target = navigations.get(0).to();
        assertThat(target.routeId())
                .as("the builder, spelled by the feature's own constant")
                .isEqualTo(ExamBuildRoutes.BUILDER);
        assertThat(target.params().getString("courseCode", null))
                .as("the course she picked, which is what openNew and EXAM_CREATE both need")
                .isEqualTo("11");
        assertThat(target.params().getLong("examVersionId", 0))
                .as("⚑ and NO version: this is the one door into the builder that opens on "
                        + "nothing. A version here would silently reopen a stored exam")
                .isZero();
    }

    /**
     * A pure coordinator reaches this screen and may write for no course.
     *
     * <p>{@code rina.barak} holds a {@code coordinators} row and zero {@code course_teachers}
     * rows deliberately, and the rail adds Exams for her without asking what she teaches. The
     * server would refuse her with {@code FORBIDDEN} out of {@code requireTeachesCourse}, so the
     * control says so first, in a sentence, and stays visible while saying it.
     *
     * <p><b>Disabled and not hidden</b>, because a hidden control is indistinguishable from the
     * absent one this whole group of tests exists to prevent. That is not a style preference:
     * hiding it would make M-3's exact symptom the correct rendering for one real account.
     */
    @Test
    @DisplayName("⚑ M-3: someone who teaches nothing gets the reason, not a missing control")
    void newExamIsDisabledWhenSheTeachesNothing() {
        Scene scene = openList(connection ->
                connection.replyOk(Verb.EXAM_LIST, ExamList.empty()), NavParams.empty(), RINA);

        MenuButton newExam = newExamControl(scene);
        assertThat(newExam.isDisabled())
                .as("no course_teachers row, so nothing here is creatable")
                .isTrue();
        assertThat(newExam.getItems())
                .as("and nothing is offered, rather than a course she would be refused")
                .isEmpty();
        assertThat(labelTexts(scene))
                .as("⚑ the reason is READABLE, on screen, beside the control. Asserting only "
                        + "that a Tooltip object exists would pass with the sentence invisible: "
                        + "JavaFX delivers no hover events to a disabled node, so a tooltip on "
                        + "one is a sentence nobody can reach")
                .contains(ExamListCopy.NEW_EXAM_NO_COURSES);
        assertThat(newExam.getTooltip())
                .as("the tooltip is kept too, for the pointer that does find it")
                .isNotNull()
                .extracting(Tooltip::getText)
                .isEqualTo(ExamListCopy.NEW_EXAM_NO_COURSES);
    }

    @Test
    @DisplayName("⚑ a notification's version opens the exam that owns it, on a real screen")
    void deepLinkOpensTheOwningExam() {
        Scene scene = openList(this::serverHasThreeExams,
                NavParams.of("examVersionId", 9101L));

        assertThat(labelTexts(scene))
                .as("she followed the notification about the calculus final and landed on it")
                .contains("Calculus final");
    }

    // ===================== Harness ========================================

    private void serverHasThreeExams(FakeClientConnection connection) {
        connection.respondTo(Verb.EXAM_LIST, request ->
                Message.ok(request, new ExamList(List.of(MIDTERM, FINAL_EXAM, GEOMETRY))));
    }

    /** Every item of a menu button, in order, so the offer can be pinned and not merely sampled. */
    private static List<String> itemTexts(MenuButton button) {
        return button.getItems().stream().map(MenuItem::getText).toList();
    }

    private static MenuItem itemNamed(MenuButton button, String text) {
        return button.getItems().stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item named " + text));
    }

    /** The new-exam control, looked up from the real scene rather than held from construction. */
    private static MenuButton newExamControl(Scene scene) {
        return scene.getRoot().lookupAll(".menu-button").stream()
                .filter(MenuButton.class::isInstance)
                .map(MenuButton.class::cast)
                .filter(button -> ExamListCopy.NEW_EXAM.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no control named " + ExamListCopy.NEW_EXAM + " on the screen (M-3)"));
    }

    /** Stands in for the builder so a navigation can complete without booting it. */
    private static final class StubScreen extends client.ui.screen.AbstractScreen {
        @Override
        protected javafx.scene.Parent build() {
            return new VBox();
        }
    }

    private Scene openList(Consumer<FakeClientConnection> script) {
        return openList(script, NavParams.empty());
    }

    private Scene openList(Consumer<FakeClientConnection> script, NavParams params) {
        return openList(script, params, DANA);
    }

    /**
     * Brings up just enough app for the screen to run, and deliberately not the whole shell.
     *
     * <p>Copied from {@code BankScreenInteractionTest.openBankAs} including its reason: booting
     * {@code ClientApp} also boots the connect screen, whose queued connect attempt wakes up
     * after {@code resetForTests} has nulled the event bus and fails a test that was never about
     * connecting. {@code ScreenManager.init} is the seam the shell itself uses.
     */
    private Scene openList(Consumer<FakeClientConnection> script, NavParams params,
                           LoginResult user) {
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
            connection.replyOk(Verb.LOGIN, user);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));
            manager.setSignedInUser(user);
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
