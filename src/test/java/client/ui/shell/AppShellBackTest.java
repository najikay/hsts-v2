package client.ui.shell;

import client.core.Navigator;
import client.core.Routes;
import client.ui.components.BackLink;
import common.dto.auth.Role;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shell's navbar Back control, which is the systemic half of the lead's rule
 * that every screen not on the rail needs a way off it.
 *
 * <p>Six screens used to build their own back link and the screens that forgot
 * simply had no exit, which is the defect the manual test round kept finding in
 * new places. Moving the control into {@link AppShell} makes it a property of the
 * shell rather than a line each screen has to remember, and a property is
 * something a test can pin: the rule reads off {@code ShellState.items()}, so
 * these three cases cover every route the app will ever have.
 *
 * <p>A shell built directly rather than the whole app booted, because none of
 * this needs a server, a session or a screen: the control is a function of the
 * rail and the back-stack, and both are handed in here.
 */
@DisabledIfSystemProperty(named = "hsts.uitests", matches = "false")
class AppShellBackTest extends ApplicationTest {

    private Navigator navigator;
    private AppShell shell;
    private Scene scene;

    @BeforeAll
    static void headless() {
        // Monocle's software pipeline: no display server, no window manager.
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
        navigator = new Navigator();
        Routes.registerAll(navigator);
        shell = new AppShell(navigator, new ShellState());
        // A coordinator's rail is the widest one, so it is the strictest test of "is
        // this route on the rail": Approvals is on it and the exam preview is not.
        shell.setNavItems(RoleNav.itemsFor(Role.COORDINATOR));

        scene = new Scene(shell, 1200, 760);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    @DisplayName("a rail route shows no Back control, because the rail is already the way out")
    void aRailRouteHasNoBackControl() {
        navigateTo(Routes.APPROVALS.id());

        assertThat(shell.backControl())
                .as("a Back beside the highlighted rail item answers a question nobody asked")
                .isEmpty();
        assertThat(scene.getRoot().lookup("." + BackLink.STYLE_CLASS))
                .as("and nothing is left in the navbar for the eye to skip over")
                .isNull();
    }

    @Test
    @DisplayName("a route the rail cannot reach shows Back, and pressing it goes back ⚑")
    void aDrillInGetsABackControlThatGoesBack() {
        navigateTo(Routes.APPROVALS.id());
        navigateTo(Routes.EXAM_PREVIEW.id());

        Button back = shell.backControl().orElse(null);
        assertThat(back).as("the exam preview is on no rail, so the shell owes it an exit").isNotNull();
        assertThat(back.getText()).isEqualTo(BackLink.LABEL);

        clickOn(back);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigator.currentRouteId())
                .as("history first: it is where the user actually came from")
                .isEqualTo(Routes.APPROVALS.id());
        assertThat(shell.backControl())
                .as("and the control leaves with the screen that needed it")
                .isEmpty();
    }

    @Test
    @DisplayName("with no history Back goes to the role's home rather than doing nothing")
    void withNoHistoryBackGoesHome() {
        // A notification opened straight into a drill-in on a fresh session: the screen
        // is on no rail and there is nothing behind it. Doing nothing would be the
        // dead end the whole control exists to remove.
        interact(() -> navigator.reset(Routes.EXAM_PREVIEW.id()));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(navigator.canGoBack()).isFalse();

        Button back = shell.backControl().orElseThrow();
        clickOn(back);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(navigator.currentRouteId())
                .as("the first rail item is Dashboard on every role's rail (RoleNav)")
                .isEqualTo(Routes.HOME_COORDINATOR.id());
        assertThat(navigator.backStackDepth())
                .as("reset, not navigate: pressing Back must not build history out of a dead end")
                .isZero();
    }

    private void navigateTo(String routeId) {
        interact(() -> navigator.navigate(routeId));
        WaitForAsyncUtils.waitForFxEvents();
    }
}
