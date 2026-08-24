package client.features.home;

import client.core.NavParams;
import client.ui.screen.AbstractScreen;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;

/**
 * The principal dashboard (Presentation tier, E5.6 - T-1, S-7; cards from UI
 * wave 1, F-10).
 *
 * <p>Read-only by construction. Nothing on this screen, and nothing in the
 * principal's rail, leads anywhere that changes data (F9.3). The cards therefore
 * carry no actions: they are counts, and clicking one opens the read-only list it
 * counted.
 *
 * <p>The snapshot is deliberately two sizes rather than a school average. A single
 * school-wide mean is a number with no question attached, and it is the kind of
 * number that gets quoted; averages live on the Reports screen, which frames them
 * with the dimension and subject they are about.
 */
public final class PrincipalHomeView extends AbstractScreen {

    private final VBox headerHost = new VBox();
    private final GridPane cards = new GridPane();

    private PrincipalDashboardSession session;

    @Override
    protected Parent build() {
        session = new PrincipalDashboardSession(dispatcher(), onFxThread())
                .onChange(this::render);

        return DashboardPage.page(headerHost, cards);
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
