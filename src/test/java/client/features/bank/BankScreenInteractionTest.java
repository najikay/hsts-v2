package client.features.bank;

import client.core.FxTestHarness;
import client.core.NavParams;
import client.core.ScreenManager;
import client.events.ClientEventBus;
import client.events.DirectFxThreadPoster;
import client.events.PushEventBridge;
import client.ui.theme.ThemeManager;
import client.ui.theme.ThemeState;
import client.net.FakeClientConnection;
import client.net.RequestDispatcher;
import common.dto.auth.CourseRef;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import common.dto.bank.BankListRequest;
import common.dto.bank.BankPage;
import common.dto.bank.BankQuestionRow;
import common.dto.bank.Difficulty;
import common.dto.bank.QuestionDetail;
import common.dto.bank.QuestionEdit;
import common.dto.bank.QuestionImage;
import common.dto.bank.QuestionVersionDetail;
import common.dto.bank.VersionHistory;
import common.dto.lock.EntityRef;
import common.dto.lock.LockChange;
import common.dto.lock.LockHolder;
import common.dto.lock.LockResponse;
import common.dto.lock.LocksSnapshot;
import common.protocol.ErrorCode;
import common.protocol.Message;
import common.protocol.Verb;
import client.core.Routes;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import server.features.bank.QuestionLockKey;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-input interaction test for the question bank screen (E6.9 / E6.12 / E6.13 — T-2).
 *
 * <p>What only a booted toolkit can show: that {@link BankView} builds at all, that rows really
 * arrive in the table, that <b>clicking a row really opens the detail pane</b> with its four
 * answers and the key marked, and that an empty bank really draws its own explanation. Every one
 * of those is a wiring claim rather than a logic claim, and the FX-free {@link BankSessionTest}
 * cannot make any of them.
 *
 * <p>It matters more here than on most screens, because {@link BankView} is on the JaCoCo
 * exclusion list by name. Without this file nothing in the build ever constructs it, and a null
 * dereference in {@code build()} would ship green.
 *
 * <h2>Two deviations from the house layout, both deliberate</h2>
 *
 * <p><b>It lives beside the feature rather than in {@code client.ui}</b>, where the other
 * screens' interaction tests are. The scope guard opens {@code client/features/bank/**} to this
 * epic and not {@code client/ui/**}, and a test that had to be written outside the feature's own
 * package would be a scope request for no gain: nothing here needs anything the shell's tests
 * need.
 *
 * <p><b>It drives the view directly rather than navigating to it.</b> The route is registered by
 * {@code SessionRoutes}, which lands in the assembly PR, so navigating would make this file fail
 * for the same reason {@link BankScreenWiringGuardTest} already fails. That would be one gap
 * reported twice, and it would hide a real rendering defect behind an expected failure. The guard
 * owns the reachability claim; this file owns the rendering claim; the two fail for different
 * reasons.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class BankScreenInteractionTest extends ApplicationTest {

    private static final Instant SPRING = Instant.parse("2026-03-10T07:00:00Z");

    private static final LoginResult DANA = new LoginResult(2, "dana.cohen", "Dana Cohen",
            Role.TEACHER,
            List.of(new CourseRef("11", "Algebra"), new CourseRef("12", "Calculus")), 0);

    /** rina.barak: coordinates subject 10, teaches nothing, by design. */
    private static final LoginResult READ_ONLY_RINA = new LoginResult(4, "rina.barak",
            "Rina Barak", Role.COORDINATOR, List.of(), 0);

    private static final BankQuestionRow LINEAR = new BankQuestionRow("11001", "11", "Algebra",
            "Solve the linear equation", "Equations", Difficulty.EASY, 701L, 1, false, SPRING);
    private static final BankQuestionRow GEOMETRY = new BankQuestionRow("11005", "11", "Algebra",
            "Read the diagram", "Geometry", Difficulty.HARD, 702L, 2, true, SPRING);

    private static final QuestionDetail GEOMETRY_V2 = new QuestionDetail("11005", "11", "Algebra",
            2, 2, "Read the diagram and answer",
            List.of("Twelve", "Fourteen", "Sixteen", "Eighteen"), 3, "Geometry", Difficulty.HARD,
            false, "Dana Cohen", SPRING);

    /** Version 1 of 11001, the version the U-49 sequence starts from. */
    private static final QuestionDetail LINEAR_V1 = new QuestionDetail("11001", "11", "Algebra",
            1, 1, "Solve the linear equation", List.of("x = 1", "x = 2", "x = 3", "x = 4"), 2,
            "Equations", Difficulty.EASY, false, "Dana Cohen", SPRING);

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

    /** The instance {@code openBank} put on screen, so a test can re-show it as a return does. */
    private BankView viewUnderTest;

    @Override
    public void start(Stage stage) {
        // Nothing here: openBankAs initialises ScreenManager directly rather than booting the
        // shell, so no connect screen exists to race the teardown. See its javadoc.
    }

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    // ===================== The wiring claims =============================

    @Test
    @DisplayName("⚑ the bank builds and its rows reach the table")
    void rowsRender() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        assertThat(cellTexts(scene)).contains("#11001", "Solve the linear equation", "Equations",
                "Easy", "#11005", "Read the diagram", "Geometry", "Hard");
        assertThat(labelTexts(scene)).contains("2 questions");
    }

    /**
     * The Editing column, on a real table, through the real event bus (E6.14 ⚑).
     *
     * <p>Two wiring claims the FX-free session test cannot make. That the column exists on
     * {@code BankView}'s table at all and renders its sentence into a cell — and that a push
     * travelling the app's <em>real</em> {@code ClientEventBus} reaches
     * {@code BankRowLocks.onServerPush}.
     *
     * <p>The second one is not hypothetical. The subscriber is invoked reflectively from the bus
     * package, so a package-private subscriber class registers without complaint and then throws
     * {@code IllegalAccessException} on every push, which {@code RequestDispatcher} catches and
     * logs rather than rethrows. The screen simply never updates and no test fails. That is
     * exactly what happened while this was being built.
     */
    @Test
    @DisplayName("⚑ the Editing column paints, and a live push repaints it (real bus)")
    void editingColumnPaintsAndFollowsPushes() {
        FakeClientConnection[] wire = new FakeClientConnection[1];
        Scene scene = openBank(connection -> {
            wire[0] = connection;
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.LOCKS_SNAPSHOT, new LocksSnapshot(EntityRef.QUESTION,
                    java.util.Map.of(QuestionLockKey.of("11005").entityId(),
                            new LockHolder(7L, "Ron Levi"))));
        });

        assertThat(cellTexts(scene))
                .as("the snapshot at load is what a teacher sees before she clicks anything")
                .contains("Editing · Ron Levi");
        assertThat(cellTexts(scene))
                .as("and a row nobody holds carries no chip at all")
                .doesNotContain("Editing · Dana Cohen", "Editing · you");

        interact(() -> wire[0].pushToClient(Verb.PUSH_LOCK_CHANGED,
                LockChange.released(QuestionLockKey.of("11005"))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(cellTexts(scene))
                .as("he closed the editor, so the row is free on screen with no refresh: a push "
                        + "that never reaches the subscriber leaves this chip up forever")
                .doesNotContain("Editing · Ron Levi");
    }

    @Test
    @DisplayName("⚑ clicking a row opens it, four answers and the key marked (real input)")
    void clickingARowOpensIt() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        Set<String> labels = labelTexts(scene);
        assertThat(labels)
                .as("the pane describes the row that was clicked")
                .contains("#11005", "Read the diagram and answer");
        assertThat(labels)
                .as("all four options, one-based, as C-8 numbers them")
                .contains("Answer 1", "Answer 2", "Answer 3", "Answer 4",
                        "Twelve", "Fourteen", "Sixteen", "Eighteen");
        assertThat(labels)
                .as("and the key is visible to an author, which is what this wire exists for")
                .contains(BankCopy.CORRECT_MARK);
    }

    @Test
    @DisplayName("the illustration is fetched and drawn for a question that has one")
    void illustrationIsDrawn() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", onePixelPng()));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(scene.getRoot().lookup(".bank-image"))
                .as("the picture is on screen rather than the 'no illustration' sentence")
                .isNotNull();
        assertThat(labelTexts(scene)).doesNotContain(BankCopy.NO_IMAGE);
    }

    @Test
    @DisplayName("an empty bank draws its own explanation, not a blank rectangle")
    void emptyBankIsExplained() {
        Scene scene = openBank(connection -> connection.replyOk(Verb.BANK_LIST,
                new BankPage(List.of(), 0, BankListRequest.DEFAULT_PAGE_SIZE, 0, 0)));

        assertThat(labelTexts(scene))
                .contains(BankCopy.NO_QUESTIONS.title(), BankCopy.NO_QUESTIONS.hint())
                .doesNotContain(BankCopy.NO_MATCHES.title());
    }

    @Test
    @DisplayName("nothing selected is a described state, not an empty half of the screen")
    void nothingSelectedIsDescribed() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        assertThat(labelTexts(scene))
                .contains(BankCopy.NOTHING_SELECTED.title(), BankCopy.NOTHING_SELECTED.hint());
    }

    @Test
    @DisplayName("the course picker is built from the sign-in payload, not from a round trip")
    void coursePickerComesFromTheSession() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        ComboBox<?> picker = (ComboBox<?>) scene.getRoot().lookup(".bank-course-picker");
        assertThat(picker).isNotNull();
        assertThat(picker.getItems())
                .as("two courses plus the 'all courses' entry, straight from LoginResult")
                .hasSize(3);
    }

    @Test
    @DisplayName("the topic picker is absent while its lookup verb does not exist")
    void topicPickerIsHiddenUntilItCanBeFilled() {
        Scene scene = openBank(this::bankHasTwoQuestions);

        Node picker = scene.getRoot().lookup(".bank-topic-picker");
        assertThat(picker).isNotNull();
        assertThat(picker.isManaged())
                .as("an empty picker is worse than no picker: it offers a filter that can only "
                        + "match nothing (contract ruling 7.6)")
                .isFalse();
    }

    // ===================== The editor's door ⚑ ============================

    @Test
    @DisplayName("⚑ Edit stays disabled while an illustrated question's picture is still coming")
    void editIsClosedUntilTheImageArrives() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            // No responder for QUESTION_IMAGE_GET: the picture never arrives, which is the
            // state the components report warns about.
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("QuestionEditorSession.forEdit takes the bytes as a required argument, and "
                        + "this button is the only route into it from a question. If it fired "
                        + "now, the editor would open showing 'No illustration' about a question "
                        + "that has one, and a Remove she pressed would be silently ignored.")
                .isTrue();
    }

    @Test
    @DisplayName("Edit opens as soon as the picture is in hand")
    void editOpensOnceTheImageIsThere() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", onePixelPng()));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("otherwise the gate would be a button nobody can ever press")
                .isFalse();
    }

    @Test
    @DisplayName("the detail card's three actions are spread evenly across it (U-37)")
    void detailActionsAreEvenlySpaced() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11001", "11", "Algebra",
                    1, 1, "Solve the linear equation", List.of("A", "B", "C", "D"), 0,
                    "Equations", Difficulty.EASY, false, "Dana Cohen", SPRING));
        });

        clickOn(rowShowing(scene, "Solve the linear equation"));
        WaitForAsyncUtils.waitForFxEvents();

        Node actions = scene.getRoot().lookup(".bank-actions");
        assertThat(actions).isNotNull();
        List<javafx.scene.Node> children = ((javafx.scene.layout.HBox) actions).getChildren();
        List<String> shape = children.stream()
                .map(node -> node instanceof javafx.scene.control.Button button
                        ? button.getText() : "spacer")
                .toList();
        assertThat(shape)
                .as("history left, edit in the middle, delete on the right edge - a spacer "
                        + "either side of Edit rather than all three bunched to the left")
                .containsExactly(BankCopy.HISTORY_OPEN, "spacer", BankCopy.EDIT,
                        "spacer", BankCopy.DELETE);
        children.stream()
                .filter(node -> !(node instanceof javafx.scene.control.Button))
                .forEach(spacer -> assertThat(javafx.scene.layout.HBox.getHgrow(spacer))
                        .as("both gaps must grow at the same rate or the spacing is not even")
                        .isEqualTo(javafx.scene.layout.Priority.ALWAYS));
    }

    @Test
    @DisplayName("⚑ an empty blob is not a picture, so Edit stays shut rather than crashing")
    void editIsClosedOnAnEmptyBlob() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, new QuestionDetail("11005", "11", "Algebra",
                    2, 2, "Read the diagram", List.of("A", "B", "C", "D"), 1, "Geometry",
                    Difficulty.HARD, true, "Dana Cohen", SPRING));
            // QuestionImage normalises a null blob to an empty array, so this is a well-formed
            // OK carrying no picture. The response arrives, the state goes READY, and a check
            // that asked the state enum or merely non-null would open the editor here.
            connection.replyOk(Verb.QUESTION_IMAGE_GET,
                    new QuestionImage("11005", 2, "image/png", new byte[0]));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled())
                .as("forEdit refuses an illustrated question with no bytes, so enabling the "
                        + "button here would make pressing it throw")
                .isTrue();
    }

    @Test
    @DisplayName("⚑ a coordinator sees a course she cannot author in, with both controls shut")
    void writeControlsAreShutOutsideTheTaughtSet() {
        Scene scene = openBankAs(READ_ONLY_RINA, connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.DELETE).isDisabled())
                .as("her read scope shows her the row; her write scope does not cover it, and "
                        + "the server would refuse a delete")
                .isTrue();
        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled()).isTrue();
        assertThat(buttonNamed(scene, BankCopy.DELETE).getTooltip())
                .as("a greyed control with no reason is a defect of its own here: she reached "
                        + "this state by doing nothing wrong")
                .isNotNull();
    }

    @Test
    @DisplayName("a question with no illustration is editable immediately")
    void editOpensWithNoImageAtAll() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(buttonNamed(scene, BankCopy.EDIT).isDisabled()).isFalse();
    }

    // ===================== U-49: what the pane comes back with ============

    /**
     * The whole of U-49, driven through the real router (2026-08-30, Findings.txt, U-49 ⚑).
     *
     * <p><b>The defect.</b> A teacher opened a question, pressed Edit, saved a new version, and
     * landed back on the bank with the row highlighted and the pane still drawing the version
     * she had just replaced. Pressing Edit again opened the editor on that stale
     * {@code QuestionDetail}, whose {@code versionNo} is the staleness token
     * {@code QUESTION_UPDATE} carries, so the server answered {@code CONFLICT} and the screen
     * told her that <em>somebody else</em> had saved a new version of this question. Nobody
     * had. It was her own save, one screen ago.
     *
     * <p><b>Why nothing caught it.</b> Neither half is wrong on its own.
     * {@code BankSession.load} re-asked for the list, which is what a returning screen owes the
     * rows; {@code select} declines to re-ask for a question that is already showing, which is
     * what stops the table's selection listener fetching on every render. Between them nothing
     * ever re-issued {@code QUESTION_GET}, and the object that went stale is one the FX-free
     * session test cannot see going anywhere: it is handed to the editor through
     * {@code NavParams} by the view.
     *
     * <p>So it takes this file and it takes the router: the sequence is bank to editor to bank
     * to editor, and it is the <em>second</em> editor that is the assertion. The scripted server
     * is stateful and refuses a stale base exactly as {@code QuestionService} does, so a
     * conflict here is the product's own conflict rather than a test's invention.
     */
    @Test
    @DisplayName("⚑ a new version is what the bank shows and what the next Edit sends (U-49)")
    void aSavedVersionIsWhatTheBankAndTheNextEditorSee() {
        QuestionDetail[] onServer = {LINEAR_V1};
        List<Integer> basesSent = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        Scene scene = openRoutedBank(connection -> {
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                    new BankPage(List.of(rowOf(onServer[0])), 0,
                            BankListRequest.DEFAULT_PAGE_SIZE, 1, 1)));
            connection.respondTo(Verb.QUESTION_GET, request -> Message.ok(request, onServer[0]));
            connection.respondTo(Verb.QUESTION_UPDATE, request -> {
                QuestionEdit edit = (QuestionEdit) request.getPayload();
                basesSent.add(edit.baseVersionNo());
                if (edit.baseVersionNo() != onServer[0].versionNo()) {
                    // What QuestionService does with a stale token, and the sentence the editor
                    // turns into "somebody else saved a new version of this question".
                    refused.add("v" + edit.baseVersionNo());
                    return Message.error(request, ErrorCode.CONFLICT, "stale base version");
                }
                onServer[0] = nextVersionOf(onServer[0], edit.text());
                return Message.ok(request, onServer[0]);
            });
            connection.respondTo(Verb.LOCK_ACQUIRE, request -> Message.ok(request,
                    LockResponse.granted(QuestionLockKey.of("11001"),
                            new LockHolder(DANA.userId(), DANA.displayName()),
                            SPRING.plusSeconds(120))));
            connection.replyOk(Verb.LOCK_RELEASE, null);
            connection.replyOk(Verb.LOCK_RENEW, null);
            connection.replyOk(Verb.LOCKS_SNAPSHOT,
                    new LocksSnapshot(EntityRef.QUESTION, java.util.Map.of()));
        });

        clickOn(rowShowing(scene, "Solve the linear equation"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(labelTexts(scene)).contains(BankCopy.versionLine(LINEAR_V1));

        clickOn(buttonNamed(scene, BankCopy.EDIT));
        settle();
        writeStem(scene, "Solve the linear equation, showing your working");
        clickOn(buttonNamed(scene, QuestionEditorCopy.SAVE));
        settle();

        Set<String> afterFirstSave = labelTexts(scene);
        assertThat(afterFirstSave)
                .as("the pane is the screen's answer to 'what does this question say now', and "
                        + "it came back saying what the question said before she saved")
                .contains("Solve the linear equation, showing your working")
                .doesNotContain("Solve the linear equation");
        assertThat(afterFirstSave)
                .as("and the version line is the F2.3 indicator, so a stale one is the screen "
                        + "telling her which version she is about to branch from, wrongly")
                .contains("Version 2, the newest")
                .doesNotContain("Version 1, the newest");

        clickOn(buttonNamed(scene, BankCopy.EDIT));
        settle();
        assertThat(labelTexts(scene))
                .as("the editor says what saving will do, and it opens on the version that "
                        + "exists rather than the one the pane was holding")
                .contains(QuestionEditorCopy.editSubtitle(2));

        writeStem(scene, "Solve the linear equation and check your answer");
        clickOn(buttonNamed(scene, QuestionEditorCopy.SAVE));
        settle();

        assertThat(basesSent)
                .as("each save branches from the version before it: v1 then v2, never v1 twice")
                .containsExactly(1, 2);
        assertThat(refused)
                .as("a CONFLICT here would be the fake one U-49 records - her own save reported "
                        + "to her as a colleague's")
                .isEmpty();
        assertThat(labelTexts(scene))
                .as("and the third version is on screen, with no refresh asked of her (NFR-18)")
                .contains("Version 3, the newest",
                        "Solve the linear equation and check your answer");
    }

    /**
     * The same staleness, checked on the other two writes because the finding asks (U-49).
     *
     * <p>Add and delete are safe for reasons that are not the same reason, and neither of them
     * is "nothing goes stale". A create leaves the pane describing whatever was open before it,
     * which is a different question and is re-read like any other on the way back; a delete
     * clears the selection when it lands on the question that was open. What this pins is the
     * first half: a question open while a new one is added still reads correctly afterwards,
     * because the return re-reads it rather than trusting the copy in hand.
     */
    @Test
    @DisplayName("adding a question re-reads the one still open, not just the list (U-49)")
    void addingAQuestionAlsoRefreshesTheOpenOne() {
        QuestionDetail[] onServer = {LINEAR_V1};
        int[] reads = {0};

        Scene scene = openBank(connection -> {
            connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                    new BankPage(List.of(rowOf(onServer[0])), 0,
                            BankListRequest.DEFAULT_PAGE_SIZE, 1, 1)));
            connection.respondTo(Verb.QUESTION_GET, request -> {
                reads[0]++;
                return Message.ok(request, onServer[0]);
            });
        });

        clickOn(rowShowing(scene, "Solve the linear equation"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(reads[0]).isEqualTo(1);

        // Somebody wrote a version while she was away, which is what coming back from any
        // write looks like from this screen's side of the wire.
        onServer[0] = nextVersionOf(onServer[0], "Solve the linear equation over the reals");
        interact(() -> viewUnderTest.onShow(NavParams.empty()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(reads[0])
                .as("returning to the bank re-reads the open question, it does not trust the "
                        + "copy the pane is drawing")
                .isEqualTo(2);
        assertThat(labelTexts(scene)).contains("Solve the linear equation over the reals",
                "Version 2, the newest");
    }

    // ===================== U-50: history shows the version ================

    /**
     * F2.3's "viewable in a version history panel", made true (2026-08-30, Findings.txt, U-50 ⚑).
     *
     * <p>The panel listed every version with its date, its author and a sentence naming which
     * fields moved, and showed the content of none of them. So a teacher could be told that v1
     * said something different without ever being told what it said, which is the whole of what
     * F2.3 asks the panel for and the one thing it did not do.
     *
     * <p>Nothing on the wire changed for this: {@code QuestionVersionDetail} has carried the
     * stem, the four options and the key since E6.3, and {@code BankWireLeakGuardTest} licenses
     * it in writing for exactly this staff-only read. The defect was that the renderer dropped
     * them on the floor.
     */
    @Test
    @DisplayName("⚑ a history entry opens to show the version it names, key marked (U-50)")
    void aHistoryEntryOpensToShowThatVersion() {
        Scene scene = openBank(connection -> {
            bankHasTwoQuestions(connection);
            connection.replyOk(Verb.QUESTION_GET, GEOMETRY_V2);
            connection.replyOk(Verb.QUESTION_VERSIONS, new VersionHistory("11005", List.of(
                    version(2, "Read the diagram and answer",
                            List.of("Twelve", "Fourteen", "Sixteen", "Eighteen"), 3),
                    version(1, "Read the diagram",
                            List.of("Ten", "Eleven", "Thirteen", "Fifteen"), 2))));
        });

        clickOn(rowShowing(scene, "Read the diagram"));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(buttonNamed(scene, BankCopy.HISTORY_OPEN));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(labelTexts(scene))
                .as("the timeline still leads with when and by whom, which is what it is read "
                        + "top-down for")
                .anySatisfy(text -> assertThat(text).startsWith("Version 1"));
        assertThat(scene.getRoot().lookupAll(".bank-history-version").stream()
                .filter(Node::isVisible).toList())
                .as("and every version is shut to begin with: ten open versions is a panel "
                        + "nobody can scan")
                .isEmpty();
        assertThat(labelTexts(scene))
                .as("so v1's own words are not on screen yet")
                .doesNotContain("Ten", "Eleven", "Thirteen", "Fifteen");

        List<Button> toggles = buttonsNamed(scene, BankCopy.HISTORY_SHOW_VERSION);
        assertThat(toggles).as("one toggle per version, current one included").hasSize(2);
        clickOn(toggles.get(1));
        WaitForAsyncUtils.waitForFxEvents();

        Node opened = scene.getRoot().lookupAll(".bank-history-version").stream()
                .filter(Node::isVisible)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no version opened"));
        assertThat(labelTexts(opened))
                .as("v1 exactly as it read: the stem, all four options, and the key marked on "
                        + "the one it belonged to - a history that hid which answer was right "
                        + "would be a diff a teacher cannot read")
                .contains("Read the diagram", "Ten", "Eleven", "Thirteen", "Fifteen",
                        BankCopy.CORRECT_MARK);
        assertThat(buttonsNamed(scene, BankCopy.HISTORY_HIDE_VERSION))
                .as("and the toggle says which way it goes next")
                .hasSize(1);
    }

    // ===================== Fixture and harness ============================

    private void bankHasTwoQuestions(FakeClientConnection connection) {
        connection.respondTo(Verb.BANK_LIST, request -> Message.ok(request,
                new BankPage(List.of(LINEAR, GEOMETRY), 0, BankListRequest.DEFAULT_PAGE_SIZE,
                        2, 1)));
    }

    /**
     * Boots the shell, signs Dana in, then puts a {@link BankView} on its own stage.
     *
     * <p>The shell boot is what gives {@code AbstractScreen} its dispatcher and its signed-in
     * user; the separate stage is what lets the screen be driven without a route.
     */
    private Scene openBank(Consumer<FakeClientConnection> script) {
        return openBankAs(DANA, script);
    }

    /**
     * Brings up just enough app for a screen to run, and deliberately not the whole shell.
     *
     * <p><b>It does not call {@code ClientApp.start}, and that is the point.</b> Booting the app
     * also boots the connect screen, which schedules its connect attempt with
     * {@code applyLater}. When a test ends, {@code resetForTests} nulls the manager's event bus,
     * and a connect attempt still sitting in the FX queue then wakes up and dereferences it:
     * {@code NullPointerException: eventBus} from {@code ConnectWiring.forEndpoint}, in a test
     * that was never about connecting. It is a teardown race, it is timing-dependent, and it
     * surfaced here only because this file grew a twelfth test.
     *
     * <p>{@code ScreenManager.init} is the seam the shell itself uses, so initialising it
     * directly gives these tests a real manager, a real event bus and a real navigator with no
     * connect screen anywhere near them. {@code ThemeState.ephemeral} keeps it off the user's
     * home directory too.
     *
     * <p><b>The race is not fixed by this, only avoided here.</b> Fourteen test classes share the
     * boot-and-reset shape; this file and the editor's stop being exposed. Raised with the lead
     * as a suite-wide item.
     */
    private Scene openBankAs(LoginResult who, Consumer<FakeClientConnection> script) {
        ScreenManager manager = boot(who, script);

        Scene[] holder = new Scene[1];
        interact(() -> {
            BankView view = new BankView();
            Scene scene = new Scene(view.view(), 1280, 820);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            view.onShow(NavParams.empty());
            viewUnderTest = view;
            holder[0] = scene;
        });
        WaitForAsyncUtils.waitForFxEvents();
        return holder[0];
    }

    /** The half of {@code openBankAs} that is app rather than screen. */
    private ScreenManager boot(LoginResult who, Consumer<FakeClientConnection> script) {
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
            connection.replyOk(Verb.LOGIN, who);
            connection.replyOk(Verb.LOGOUT, null);
            script.accept(connection);

            RequestDispatcher dispatcher = new RequestDispatcher(connection);
            connection.setServerMessageHandler(dispatcher::dispatchIncoming);
            manager.setClient(connection);
            manager.setDispatcher(dispatcher);
            dispatcher.setPushListener(new PushEventBridge(manager.eventBus()));
            manager.setSignedInUser(who);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return manager;
    }

    /**
     * The bank reached the way a teacher reaches it: through the router.
     *
     * <p>The rest of this file drives {@link BankView} on its own stage, and says why in
     * {@code openBankAs}'s javadoc. The U-49 sequence cannot: the defect lives in what one
     * screen hands the next through {@code NavParams} and in what the first screen does when it
     * is shown again, and neither exists without a navigator, a screen cache and the real
     * lifecycle. So this registers exactly the two routes the sequence walks and lets
     * {@code ScreenManager} do the rest; there is still no shell and still no connect screen,
     * which is what keeps the teardown race out of it.
     *
     * @return the manager's own scene, whose root the router swaps
     */
    private Scene openRoutedBank(Consumer<FakeClientConnection> script) {
        ScreenManager manager = boot(DANA, script);
        interact(() -> {
            manager.navigator().registerAll(Routes.QUESTIONS, Routes.QUESTION_EDIT);
            manager.screens().register(Routes.QUESTIONS.id(), BankView::new);
            manager.screens().register(Routes.QUESTION_EDIT.id(), QuestionEditorView::new);
            manager.navigator().navigate(BankRoutes.LIST);
        });
        settle();
        return manager.scene();
    }

    /**
     * Waits out a route transition as well as the event queue.
     *
     * <p>{@code ScreenManager} plays {@code Animations.riseIn} over
     * {@link client.ui.anim.Motion#ROUTE_MS} on every screen it swaps in, and
     * {@code waitForFxEvents} pumps pulses rather than advancing the clock. Clicking into a
     * screen that is still 8px off its resting place is how a routed test flakes.
     */
    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        sleep(client.ui.anim.Motion.ROUTE_MS * 3L);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Types a new stem into whichever editor is on screen. */
    private void writeStem(Scene scene, String text) {
        TextArea box = (TextArea) scene.getRoot().lookupAll(".text-area").stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("no editor on screen"));
        interact(() -> box.setText(text));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The version the server writes when it accepts an edit: n+1, with the new stem. */
    private static QuestionDetail nextVersionOf(QuestionDetail current, String text) {
        return new QuestionDetail(current.displayId5(), current.courseCode(),
                current.courseName(), current.versionNo() + 1, current.latestVersionNo() + 1,
                text, current.answers(), current.correctAnswer(), current.topic(),
                current.difficulty(), current.hasImage(), current.authorName(),
                current.createdAt());
    }

    /** The list row the bank would show for a question, so the two answers cannot disagree. */
    private static BankQuestionRow rowOf(QuestionDetail detail) {
        return new BankQuestionRow(detail.displayId5(), detail.courseCode(), detail.courseName(),
                detail.text(), detail.topic(), detail.difficulty(), 701L,
                detail.latestVersionNo(), detail.hasImage(), detail.createdAt());
    }

    /** One row of a version history. */
    private static QuestionVersionDetail version(int versionNo, String text, List<String> answers,
                                                 int correct) {
        return new QuestionVersionDetail(versionNo, text, answers, correct, "Geometry",
                Difficulty.HARD, false, "Dana Cohen", SPRING);
    }

    /** Every button carrying this label, for a control the screen draws once per entry. */
    private static List<Button> buttonsNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .toList();
    }

    /** One button by its label, so a test cannot pass by finding a different control. */
    private static javafx.scene.control.Button buttonNamed(Scene scene, String label) {
        return scene.getRoot().lookupAll(".button").stream()
                .filter(javafx.scene.control.Button.class::isInstance)
                .map(javafx.scene.control.Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + label));
    }

    /** The table row whose cells contain {@code text}, so a click lands on a real row. */
    private static Node rowShowing(Scene scene, String text) {
        return scene.getRoot().lookupAll(".table-row-cell").stream()
                .filter(TableRow.class::isInstance)
                .filter(row -> row.lookupAll(".table-cell").stream()
                        .filter(javafx.scene.control.Labeled.class::isInstance)
                        .map(cell -> ((javafx.scene.control.Labeled) cell).getText())
                        .filter(Objects::nonNull)
                        .anyMatch(cellText -> cellText.contains(text)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row showing " + text));
    }

    private static Set<String> cellTexts(Scene scene) {
        return scene.getRoot().lookupAll(".table-cell").stream()
                .filter(javafx.scene.control.Labeled.class::isInstance)
                .map(node -> ((javafx.scene.control.Labeled) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> labelTexts(Scene scene) {
        return labelTexts(scene.getRoot());
    }

    /** The same, under one node, so an assertion cannot be satisfied by the screen around it. */
    private static Set<String> labelTexts(Node root) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * A genuine 1x1 PNG.
     *
     * <p>Genuine rather than a stub, for the reason the gallery's demo image is: the product
     * sniffs image bytes and refuses a fake, so a test that demonstrated the image path with
     * something the product would reject would be demonstrating nothing.
     */
    private static byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwA"
                        + "EhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
}
