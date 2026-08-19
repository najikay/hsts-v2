package client.features.home;

import client.core.NavParams;
import client.ui.components.EmptyState;
import client.ui.components.Icons;
import client.ui.screen.AbstractScreen;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The teacher dashboard (Presentation tier, E5.6 — T-1).
 *
 * <p>Thin by construction: the greeting is {@link HomeGreeting}, the layout is
 * {@link DashboardPage}, and the only data on the page is the course list the
 * login result actually carried. The four stat cards name the epics that will
 * fill them rather than showing invented numbers — see {@link DashboardPage} for
 * why that is a decision and not a shortcut.
 */
public final class TeacherHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();

    @Override
    protected Parent build() {
        return DashboardPage.page(
                headerHost,
                DashboardPage.statGrid(
                        DashboardPage.statCard("Exams in the drawer", "Arrives with E7"),
                        DashboardPage.statCard("Awaiting approval", "Arrives with E8"),
                        DashboardPage.statCard("Live now", "Arrives with E9"),
                        DashboardPage.statCard("Awaiting grading", "Arrives with E12")),
                DashboardPage.coursesCard("Your courses",
                        "The courses you teach. Questions and exams you author belong to these.",
                        DashboardPage.currentCourses(),
                        "No courses are assigned to you yet."),
                DashboardPage.card("Recent activity",
                        "Approvals, releases and grading updates land here.",
                        new EmptyState(Icons.INBOX, "Nothing yet",
                                "Once you build and release an exam, its activity appears in this list.")));
    }

    @Override
    public void onShow(NavParams params) {
        // The screen instance is cached and revisited, so the greeting is
        // recomputed per visit rather than frozen at build time.
        headerHost.getChildren().setAll(
                DashboardPage.header(DashboardPage.currentDisplayName(), LocalDateTime.now()));
    }
}
