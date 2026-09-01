package client.ui;

import client.core.NavParams;
import client.core.Route;
import client.core.Routes;
import client.core.ScreenManager;
import client.core.ServerEndpoint;
import client.core.ClientApp;
import client.core.FxTestHarness;
import client.events.PushEventBridge;
import client.features.connect.ConnectWiring;
import client.features.login.ShellBoot;
import client.net.RequestDispatcher;
import common.dto.auth.LoginRequest;
import common.dto.auth.LoginResult;
import common.protocol.Message;
import common.protocol.Verb;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Captures the README's screenshots from the REAL application against a REAL seeded server.
 *
 * <p>Not part of the ordinary build: it needs a live server carrying the demo seed and it
 * writes files, so it runs only when asked, and never in CI:
 *
 * <pre>
 *   java -jar target/hsts-server.jar --headless --port 5561 --no-discovery   (seeded hsts_db)
 *   ./mvnw -o test -Dtest=ScreenshotTourTest -Dhsts.screenshots=true \
 *          [-Dhsts.screenshots.dir=docs/screenshots] [-Dhsts.screenshots.port=5561]
 * </pre>
 *
 * <p>The tour signs in as each role over a real socket, walks the routes a reader should see,
 * and snapshots the scene at 1280x800. It never mutates the seed: it opens screens, it does not
 * press anything that writes (no attempts started, no bot questions asked, no approvals given).
 */
@EnabledIfSystemProperty(named = "hsts.screenshots", matches = "true")
class ScreenshotTourTest extends ApplicationTest {

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

    private static final int PORT =
            Integer.getInteger("hsts.screenshots.port", 5561);
    private static final Path OUT =
            Path.of(System.getProperty("hsts.screenshots.dir", "docs/screenshots"));

    @AfterEach
    void resetGlobalState() {
        FxTestHarness.resetGlobalState();
    }

    @Test
    void tour() throws Exception {
        Files.createDirectories(OUT);

        tourAs("dana.cohen", new Object[][] {
                {Routes.HOME_TEACHER, "teacher-dashboard"},
                {Routes.QUESTIONS, "question-bank"},
                {Routes.EXAMS, "exams"},
                {Routes.RELEASES, "releases"},
                {Routes.MONITOR, "live-monitor"},
                {Routes.GRADING, "grading"},
                {Routes.RESULTS, "results-histogram"},
                {Routes.SETTINGS, "settings"},
        });
        tourAs("rina.barak", new Object[][] {
                {Routes.APPROVALS, "approvals"},
        });
        tourAs("avi.mizrahi", new Object[][] {
                {Routes.BOT_MANAGER, "bot-manager"},
        });
        tourAs("maya.levi", new Object[][] {
                {Routes.HOME_STUDENT, "student-dashboard"},
                {Routes.TAKE_EXAM, "take-exam"},
                {Routes.MY_GRADES, "my-grades"},
                {Routes.BOT_CHAT, "study-bot"},
        });
        tourAs("principal.avia", new Object[][] {
                {Routes.DATA, "principal-data"},
                {Routes.REPORTS, "reports"},
        });
        captureByStudentReport();
    }

    /**
     * REPORTS A2: the by-student view with her scores and the histogram marker, captured
     * through the real segments and the real popup, as the principal.
     */
    private void captureByStudentReport() throws Exception {
        interact(() -> {
            Stage stage = new Stage();
            new ClientApp().start(stage);
            stage.setWidth(1280);
            stage.setHeight(800);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ScreenManager manager = ScreenManager.getInstance();
        ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(
                new ServerEndpoint("127.0.0.1", PORT), manager.eventBus(), null);
        interact(() -> {
            manager.setClient(wiring.client());
            manager.setDispatcher(wiring.dispatcher());
            wiring.dispatcher().setPushListener(new PushEventBridge(manager.eventBus()));
        });
        wiring.client().connect();
        Message answer = wiring.dispatcher()
                .send(Verb.LOGIN, new LoginRequest("principal.avia", "demo123")).get();
        interact(() -> ShellBoot.enter(manager, (LoginResult) answer.getPayload()));
        settle();
        interact(() -> manager.navigator().navigate(Routes.REPORTS.id(), NavParams.empty()));
        settle();
        // The real segment, then a real student from the real popup.
        clickOn((javafx.scene.control.ToggleButton) manager.scene().getRoot()
                .lookupAll(".toggle-button").stream()
                .filter(node -> node instanceof javafx.scene.control.ToggleButton toggle
                        && "By student".equalsIgnoreCase(toggle.getText()))
                .findFirst().orElseThrow());
        settle();
        clickOn(manager.scene().getRoot().lookup(".reports-subject-picker"));
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(lookup(".list-cell").queryAll().stream()
                .filter(node -> node instanceof javafx.scene.control.ListCell<?> cell
                        && cell.getText() != null && cell.getText().startsWith("Noa"))
                .findFirst().orElseThrow());
        settle();
        snap(manager, "reports-by-student");
        wiring.dispatcher().send(Verb.LOGOUT, null).get();
        wiring.client().disconnect();
        FxTestHarness.resetGlobalState();
    }

    private void tourAs(String username, Object[][] stops) throws Exception {
        interact(() -> {
            Stage stage = new Stage();
            new ClientApp().start(stage);
            stage.setWidth(1280);
            stage.setHeight(800);
        });
        WaitForAsyncUtils.waitForFxEvents();

        ScreenManager manager = ScreenManager.getInstance();
        ConnectWiring.Wiring wiring = ConnectWiring.forEndpoint(
                new ServerEndpoint("127.0.0.1", PORT), manager.eventBus(), null);
        interact(() -> {
            manager.setClient(wiring.client());
            manager.setDispatcher(wiring.dispatcher());
            wiring.dispatcher().setPushListener(new PushEventBridge(manager.eventBus()));
        });
        wiring.client().connect();

        RequestDispatcher dispatcher = wiring.dispatcher();
        Message answer = dispatcher.send(Verb.LOGIN, new LoginRequest(username, "demo123")).get();
        if (answer.isError()) {
            throw new AssertionError(username + " could not sign in: " + answer.errorMessage());
        }
        LoginResult login = (LoginResult) answer.getPayload();
        interact(() -> ShellBoot.enter(manager, login));
        settle();

        for (Object[] stop : stops) {
            Route route = (Route) stop[0];
            String name = (String) stop[1];
            interact(() -> manager.navigator().navigate(route.id(), NavParams.empty()));
            settle();
            snap(manager, name);
        }

        dispatcher.send(Verb.LOGOUT, null).get();
        wiring.client().disconnect();
        FxTestHarness.resetGlobalState();
    }

    /** Route entrance plus one real round trip of data, generously outwaited. */
    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        sleep(1200);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void snap(ScreenManager manager, String name) throws Exception {
        WritableImage[] shot = new WritableImage[1];
        interact(() -> shot[0] =
                manager.scene().getRoot().snapshot(new SnapshotParameters(), null));
        WritableImage image = shot[0];
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        File file = OUT.resolve(name + ".png").toFile();
        ImageIO.write(out, "png", file);
        System.out.println("captured " + file + " (" + width + "x" + height + ")");
    }
}
