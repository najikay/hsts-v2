package client.features.home;

import client.core.NavParams;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The coordinator dashboard (Presentation tier, E5.6 — T-1, F4.1).
 *
 * <p>A coordinator is a teacher who additionally approves her subject's exams
 * (PRD §3), and the dashboard says exactly that: the teacher's cards plus the
 * approval queue, which is the item her rail has and a teacher's does not.
 */
public final class CoordinatorHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();

    @Override
    protected Parent build() {
        EmptyState queue = new EmptyState(Icons.APPROVALS, "No exams waiting",
                "Exams submitted for approval in your subject will queue up here.");

        return DashboardPage.page(
                headerHost,
                DashboardPage.statGrid(
                        DashboardPage.statCard("Waiting for you", "Arrives with E8"),
                        DashboardPage.statCard("Exams in the drawer", "Arrives with E7"),
                        DashboardPage.statCard("Live now", "Arrives with E9"),
                        DashboardPage.statCard("Awaiting grading", "Arrives with E12")),
                DashboardPage.card("Approval queue",
                        "Exams your subject's teachers submitted, newest first.",
                        new VBox(14, queue,
                                DashboardPage.pendingAction("Open the queue", "Arrives with E8"))),
                DashboardPage.coursesCard("Your courses",
                        "You teach these, and you coordinate their subject.",
                        DashboardPage.currentCourses(),
                        "No courses are assigned to you yet."));
    }

    @Override
    public void onShow(NavParams params) {
        headerHost.getChildren().setAll(
                DashboardPage.header(DashboardPage.currentDisplayName(), LocalDateTime.now()));
    }
}
