package client.features.home;

import client.core.NavParams;
import client.ui.screen.AbstractScreen;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The coordinator dashboard (Presentation tier, E5.6 - T-1, F4.1; cards from UI
 * wave 1, F-10).
 *
 * <p>A coordinator is a teacher who additionally approves her subject's exams
 * (PRD section 3), and the dashboard says exactly that: what is waiting for a
 * decision, and who it came from. Both numbers are one read of the approval queue
 * she already has a screen for, and both cards open that screen.
 *
 * <p>The four placeholder stats and the disabled "Open the queue" button are gone.
 * They named E8, E7, E9 and E12; all four have landed, so the honest thing is now
 * a real count rather than a dash.
 */
public final class CoordinatorHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final GridPane cards = new GridPane();

    private CoordinatorDashboardSession session;

    @Override
    protected Parent build() {
        session = new CoordinatorDashboardSession(dispatcher(), onFxThread())
                .onChange(this::render);

        return DashboardPage.page(
                headerHost,
                cards,
                DashboardPage.coursesCard("Your courses",
                        "You teach these, and you coordinate their subject.",
                        DashboardPage.currentCourses(),
                        "No courses are assigned to you yet."));
    }

    @Override
    public void onShow(NavParams params) {
        renderHeader();
        render();
        session.load();
    }

    private void render() {
        DashboardPage.fillCardGrid(cards, session.cards(), navigator()::navigate);
        renderHeader();
    }

    /**
     * Rebuilt on every settle, not only on show: the summary sentence is
     * composed from the numbers the cards loaded, so it is wrong until they
     * have, and a header that only rendered once would keep saying so.
     */
    private void renderHeader() {
        headerHost.getChildren().setAll(DashboardPage.header(
                DashboardPage.currentDisplayName(), LocalDateTime.now(), session.summary()));
    }
}
