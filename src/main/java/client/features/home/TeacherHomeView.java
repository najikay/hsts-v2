package client.features.home;

import client.core.NavParams;
import client.ui.screen.AbstractScreen;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
        session = new TeacherDashboardSession(dispatcher(), onFxThread())
                .onChange(this::render)
                // The live card re-read (U-63, NFR-18): sittings in progress and awaiting
                // grading both move while she is looking at them.
                .subscribeTo(eventBus());

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
        renderHeader();
        render();
        session.load();
    }

    /**
     * Renders the four cards, handing two of them the richer body the session
     * built for them (UI wave 2).
     *
     * <p>The bodies are positional and deliberately so: index 0 is the live
     * card and index 3 is the last closed one, which is the order
     * {@link TeacherDashboardSession#cards()} documents. A body is present only
     * when its detail read answered, so a live sitting whose monitor is slow
     * shows the plain count for a moment and then fills in — never a hole.
     */
    private void render() {
        List<Node> bodies = new ArrayList<>();
        bodies.add(session.liveDetail()
                .map(detail -> (Node) DashboardPage.liveBody(detail, ZoneId.systemDefault(),
                        DashboardPage.submittedRoll(cards)))
                .orElse(null));
        bodies.add(null);
        bodies.add(null);
        bodies.add(session.closedDetail().map(detail -> (Node) DashboardPage.closedBody(detail))
                .orElse(null));

        DashboardPage.fillCardGrid(cards, session.cards(), navigator()::navigate, bodies);
        renderHeader();
    }

    /**
     * Rebuilt on every settle, not only on show: the summary sentence is
     * composed from the numbers the cards loaded, so it is provisional until
     * they have landed.
     */
    private void renderHeader() {
        headerHost.getChildren().setAll(DashboardPage.header(
                DashboardPage.currentDisplayName(), LocalDateTime.now(), session.summary()));
    }
}
