package client.ui.components;

import client.core.Navigator;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.util.Locale;
import java.util.Objects;

/**
 * The one way back out of a drill-in screen (Presentation tier, UI wave 1 — F-7).
 *
 * <h2>The convention</h2>
 *
 * <p>A <b>drill-in</b> is a screen showing one thing, reached from a list of such
 * things: one exam in the approval queue, one marked paper from My Grades, one
 * sitting from Releases. Every one of them gets this control, in the same place —
 * <b>top-left, above the title</b> — and it always reads the same word.
 *
 * <p>Before this, each drill-in solved the problem its own way or not at all. The
 * exam preview put "Back to approvals" in its <i>footer</i>, below a long
 * scrolling paper; the bot history put one on the right of its header; the
 * checked form, the execution monitor and the bot analytics had nothing, so the
 * only exit was the rail — which is not an exit, it is a change of subject, and
 * on a screen with no rail it is not even that.
 *
 * <p>Breadcrumbs and view toggles stay exactly as they are. They say <i>where you
 * are</i>; this says <i>how to leave</i>, and a reader should not have to derive
 * the second from the first.
 *
 * <h2>Why the label is just "Back"</h2>
 *
 * <p>Because that is the only label that cannot lie. The click prefers
 * {@link Navigator#back()}, which returns the user where they actually came from,
 * and a drill-in is reachable from more than one place — the approval preview
 * from the queue <i>and</i> from a notification. A control labelled "Back to
 * approvals" that returns you to your notifications has told you something
 * untrue. The parent route is kept as the <b>fallback</b> for the case with no
 * history (a deep link, a fresh session), and it is what the tooltip names, so
 * the destination is still discoverable without being promised.
 */
public final class BackLink {

    /** The style class every back affordance carries; see the wave-1 CSS section. */
    public static final String STYLE_CLASS = "hsts-back-link";

    /** The label. One word, sentence case, the same on every screen. */
    public static final String LABEL = "Back";

    private BackLink() {
    }

    /**
     * Builds the control for a drill-in whose parent is {@code parentRouteId}.
     *
     * @param navigator     the app navigator
     * @param parentRouteId where to go when there is no history to go back to
     * @param parentName    what to call that place in the tooltip ("Approvals")
     */
    public static Button to(Navigator navigator, String parentRouteId, String parentName) {
        Objects.requireNonNull(navigator, "navigator");
        Objects.requireNonNull(parentRouteId, "parentRouteId");

        return action(parentName, () -> {
            // History first: it is where the user actually came from. The parent is
            // the answer only when there is no history, which is why it is second
            // and why the label never names it.
            if (!navigator.back()) {
                navigator.navigate(parentRouteId);
            }
        });
    }

    /**
     * The same control for a drill-in that is a <b>mode of one screen</b> rather
     * than a route of its own.
     *
     * <p>The teacher's histogram is the case that named F-7: choosing it replaces
     * the results table, and the only route out was to notice that the segmented
     * control which brought you here is also what takes you away. That is a
     * toggle, not an exit. A view that fills the screen owes the reader the same
     * way back a drill-in route does, and it should be the same control, in the
     * same corner, reading the same word.
     *
     * @param targetName what the reader returns to, for the tooltip ("Table")
     * @param back       what going back does
     */
    public static Button action(String targetName, Runnable back) {
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(back, "back");

        Button button = Buttons.withIcon(LABEL, Icons.CHEVRON_LEFT, Buttons.GHOST, Buttons.SMALL);
        button.getStyleClass().add(STYLE_CLASS);
        button.setTooltip(new Tooltip("Back to " + targetName.toLowerCase(Locale.ROOT)));
        button.setAccessibleText(LABEL + ", to " + targetName);
        button.setOnAction(event -> back.run());
        return button;
    }
}
