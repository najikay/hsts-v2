package client.features.home;

import client.core.NavParams;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The principal dashboard (Presentation tier, E5.6 — T-1, S-7).
 *
 * <p>Read-only by construction. Nothing on this screen, and nothing in the
 * principal's rail, leads anywhere that changes data (F9.3) — the role browses
 * the bank, exams and results, and reads reports. The cards below therefore carry
 * no primary actions at all, not even disabled ones, because there is no action
 * this role will ever be given here.
 */
public final class PrincipalHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();

    @Override
    protected Parent build() {
        return DashboardPage.page(
                headerHost,
                DashboardPage.statGrid(
                        DashboardPage.statCard("Courses", "Arrives with E15"),
                        DashboardPage.statCard("Exams", "Arrives with E15"),
                        DashboardPage.statCard("Executions", "Arrives with E15"),
                        DashboardPage.statCard("School average", "Arrives with E15")),
                DashboardPage.card("Reports",
                        "Compare executions across teachers, courses and students (S-37).",
                        new EmptyState(Icons.REPORTS, "No reports yet",
                                "Reports need graded executions; they appear once exams have been "
                                        + "run and their grades approved.")),
                DashboardPage.card("What you can see",
                        "School-wide, read-only access (S-7).",
                        new EmptyState(Icons.BANK, "Browsing arrives with E15",
                                "Question bank, exams and results, all of them read-only. "
                                        + "No screen in this role can change data.")));
    }

    @Override
    public void onShow(NavParams params) {
        headerHost.getChildren().setAll(
                DashboardPage.header(DashboardPage.currentDisplayName(), LocalDateTime.now()));
    }
}
