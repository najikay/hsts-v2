package client.ui.shell;

import client.core.Routes;
import client.ui.components.Icons;
import common.dto.auth.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The side rail each role gets (Presentation tier, E5.4 — F1.2/T-1).
 *
 * <p>"The UI presents a role-appropriate shell" is, concretely, this class: four
 * lists of {@link NavItem}. Nothing else in the client branches on {@link Role}
 * to decide what a user may see, which is why the requirement can be checked by
 * reading one file — and why the Principal's read-only menu (F9.3) is provably
 * free of anything that mutates.
 *
 * <p>Items whose screens have not been built yet are present but
 * {@linkplain NavItem#disabled disabled}, each naming the epic it arrives with.
 * The rails therefore already read as the finished app: enabling a feature is
 * swapping one {@code disabled(...)} for one {@code of(...)} plus its route
 * registration in {@code SessionRoutes}.
 *
 * <p>The rail is not a permission boundary and never pretends to be — the server
 * re-authorises every request from the session (ARCHITECTURE §3). Hiding
 * Approvals from a teacher is a courtesy to the teacher, not a defence.
 */
public final class RoleNav {

    /**
     * Route ids of screens that arrive in later epics; declared once, here.
     *
     * <p>{@code exams} and {@code approvals} left this list in E8: both now have a
     * {@link Routes} constant of their own, which is what an enabled item points at.
     */
    static final String ROUTE_RELEASES = "releases";
    static final String ROUTE_MONITOR = "monitor";
    static final String ROUTE_GRADING = "grading";
    static final String ROUTE_RESULTS = "results";
    static final String ROUTE_TAKE_EXAM = "exam.take";
    static final String ROUTE_MY_GRADES = "grades";
    static final String ROUTE_DATA = "data";
    static final String ROUTE_REPORTS = "reports";

    private RoleNav() {
    }

    /**
     * @param role the signed-in user's role
     * @return that role's rail, in display order; never empty
     * @throws NullPointerException when {@code role} is {@code null} — a shell
     *         without a role is a bug worth failing on, not a default menu
     */
    public static List<NavItem> itemsFor(Role role) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case TEACHER, COORDINATOR -> teaching(role);
            case STUDENT -> student();
            case PRINCIPAL -> principal();
        };
    }

    /**
     * The teaching rail. A coordinator <b>is</b> a teacher (PRD §3) and differs by
     * exactly one item, so the two rails are one list with a flag rather than two
     * lists to keep in sync.
     */
    private static List<NavItem> teaching(Role role) {
        boolean coordinator = role == Role.COORDINATOR;
        List<NavItem> items = new ArrayList<>();
        items.add(dashboard(role));
        // The legacy bank screen still works over the DAO, so this one is live.
        items.add(NavItem.of(Routes.QUESTIONS.id(), "Question Bank", Icons.BANK));
        // Live since E8, but only half of it: this screen shows where each submitted exam
        // stands and what a coordinator said about it (F4.2), which is the surface a
        // rejection notification has to land on. E7 replaces it with the exam builder and
        // list at the same route id, so the tooltip says what is here today rather than
        // letting the label over-promise.
        items.add(NavItem.of(Routes.EXAMS.id(), "Exams", Icons.EXAMS)
                .withTooltip("Where your submitted exams stand. The exam builder arrives with E7."));
        if (coordinator) {
            // Live since E8. The one item that separates a coordinator's rail from a
            // teacher's (PRD §3).
            items.add(NavItem.of(Routes.APPROVALS.id(), "Approvals", Icons.APPROVALS));
        }
        items.add(soon(ROUTE_RELEASES, "Releases", Icons.RELEASE, 9));
        items.add(soon(ROUTE_MONITOR, "Live Monitor", Icons.MONITOR, 11));
        // Live since E12. The rail item has reserved this slot since E5.4 and its id is still
        // ROUTE_GRADING: Routes.GRADING was declared with the same string, so enabling the
        // feature was a swap here rather than a rename anywhere.
        items.add(NavItem.of(Routes.GRADING.id(), "Grading", Icons.GRADING));
        // Live since E14. The rail item has reserved this slot since E5.4 and its id is
        // still ROUTE_RESULTS: Routes.RESULTS was declared with the same string, so
        // enabling the feature was a swap here rather than a rename anywhere.
        items.add(NavItem.of(Routes.RESULTS.id(), "Results", Icons.RESULTS));
        // Live since E16. A teacher's Study Bot is the manager screen; the
        // analytics view is reached from inside it, because it is a view of one
        // bot and a rail item that needed a course chosen first would be a dead end.
        items.add(NavItem.of(Routes.BOT_MANAGER.id(), "Study Bot", Icons.BOT));
        items.add(settings());
        return List.copyOf(items);
    }

    private static List<NavItem> student() {
        return List.of(
                dashboard(Role.STUDENT),
                soon(ROUTE_TAKE_EXAM, "Take Exam", Icons.EXAMS, 10),
                // Live since E13. The rail item has reserved this slot since E5.4 and its id
                // is still ROUTE_MY_GRADES: Routes.MY_GRADES was declared with the same
                // string, so enabling the feature was a swap here rather than a rename.
                NavItem.of(Routes.MY_GRADES.id(), "My Grades", Icons.RESULTS),
                // Live since E16. A student's Study Bot is the chat; her history is
                // one button away inside it.
                NavItem.of(Routes.BOT_CHAT.id(), "Study Bot", Icons.BOT),
                settings());
    }

    /** Read-only by construction: not one item here leads to a mutating screen (S-7, F9.3). */
    private static List<NavItem> principal() {
        return List.of(
                dashboard(Role.PRINCIPAL),
                soon(ROUTE_DATA, "Data", Icons.BANK, 15),
                soon(ROUTE_REPORTS, "Reports", Icons.REPORTS, 15),
                settings());
    }

    private static NavItem dashboard(Role role) {
        return NavItem.of(Routes.home(role).id(), "Dashboard", Icons.DASHBOARD);
    }

    private static NavItem settings() {
        return NavItem.of(Routes.SETTINGS.id(), "Settings", Icons.SETTINGS);
    }

    /** @return a disabled item whose tooltip names the epic that will enable it. */
    private static NavItem soon(String routeId, String label, String icon, int epic) {
        return NavItem.disabled(routeId, label, icon, "Arrives with E" + epic);
    }
}
