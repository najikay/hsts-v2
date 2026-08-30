package client.features.home;

import client.core.NavParams;
import client.ui.screen.AbstractScreen;
import common.dto.auth.CourseRef;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

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
 *
 * <p><b>The courses card is conditional (2026-08-30, live session, U-41).</b> A
 * coordinator who teaches nothing — {@code rina.barak} coordinates Mathematics and
 * has zero {@code course_teachers} rows — was shown a "Your courses" card whose whole
 * content was "No courses are assigned to you yet.", a sentence that reads as a
 * missing seed rather than as the truth about her job. It is dropped for her, the same
 * ruling that takes the six teaching items off her rail ({@code RoleNav}). Both cards
 * above it stay: they are read from the approval queue, which is what she signs in
 * for, and they are full on her account.
 */
public final class CoordinatorHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final GridPane cards = new GridPane();

    private CoordinatorDashboardSession session;

    @Override
    protected Parent build() {
        session = new CoordinatorDashboardSession(dispatcher(), onFxThread())
                .onChange(this::render);

        // Read once, here: the shell records the signed-in user before it navigates
        // (ShellBoot.enter) and evicts every screen on sign-out, so a built dashboard
        // can never outlive the courses it was built from.
        List<CourseRef> courses = DashboardPage.currentCourses();
        if (courses.isEmpty()) {
            return DashboardPage.page(headerHost, cards);
        }
        return DashboardPage.page(
                headerHost,
                cards,
                DashboardPage.coursesCard("Your courses",
                        "You teach these, and you coordinate their subject.",
                        courses,
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
