package client.features.home;

import client.core.NavParams;
import client.ui.anim.Animations;
import client.ui.screen.AbstractScreen;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The teacher dashboard (Presentation tier, E5.6 — T-1; cards from UI wave 1, F-10).
 *
 * <p>Thin by construction: the greeting is {@link HomeGreeting}, the layout is
 * {@link DashboardPage}, and every number is {@link TeacherDashboardSession}'s,
 * which is FX-free and tested on its own.
 *
 * <p>What changed in wave 1: the four stat cards used to render an en dash and
 * name the epic that would one day fill them. Every one of those epics has landed,
 * so the placeholders are gone and the cards now count real sittings, real papers
 * waiting to be marked and real exams that have been sat, each opening the screen
 * it counted. The honesty rule that produced the placeholders is unchanged and
 * still visible in {@link DashboardCard.State}: a card that could not reach the
 * server says so rather than showing a zero.
 */
public final class TeacherHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final GridPane cards = new GridPane();

    private TeacherDashboardSession session;

    @Override
    protected Parent build() {
        session = new TeacherDashboardSession(dispatcher(), onFxThread()).onChange(this::render);

        return DashboardPage.page(
                headerHost,
                cards,
                DashboardPage.coursesCard("Your courses",
                        "The courses you teach. Questions and exams you author belong to these.",
                        DashboardPage.currentCourses(),
                        "No courses are assigned to you yet."));
    }

    @Override
    public void onShow(NavParams params) {
        // The screen instance is cached and revisited, so the greeting is
        // recomputed per visit rather than frozen at build time.
        headerHost.getChildren().setAll(
                DashboardPage.header(DashboardPage.currentDisplayName(), LocalDateTime.now()));
        render();
        session.load();
    }

    private void render() {
        DashboardPage.fillCardGrid(cards, session.cards(), navigator()::navigate);
    }
}
