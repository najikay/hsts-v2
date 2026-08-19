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

    /** Route ids of screens that arrive in later epics; declared once, here. */
    static final String ROUTE_EXAMS = "exams";
    static final String ROUTE_APPROVALS = "approvals";
    static final String ROUTE_RELEASES = "releases";
    static final String ROUTE_MONITOR = "monitor";
    static final String ROUTE_GRADING = "grading";
    static final String ROUTE_RESULTS = "results";
    static final String ROUTE_BOT = "bot";
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
        items.add(soon(ROUTE_EXAMS, "Exams", Icons.EXAMS, 7));
        if (coordinator) {
            items.add(soon(ROUTE_APPROVALS, "Approvals", Icons.APPROVALS, 8));
        }
        items.add(soon(ROUTE_RELEASES, "Releases", Icons.RELEASE, 9));
        items.add(soon(ROUTE_MONITOR, "Live Monitor", Icons.MONITOR, 11));
        items.add(soon(ROUTE_GRADING, "Grading", Icons.GRADING, 12));
        items.add(soon(ROUTE_RESULTS, "Results", Icons.RESULTS, 14));
        items.add(soon(ROUTE_BOT, "Study Bot", Icons.BOT, 16));
        items.add(settings());
        return List.copyOf(items);
    }

    private static List<NavItem> student() {
        return List.of(
                dashboard(Role.STUDENT),
                soon(ROUTE_TAKE_EXAM, "Take Exam", Icons.EXAMS, 10),
                soon(ROUTE_MY_GRADES, "My Grades", Icons.RESULTS, 13),
                soon(ROUTE_BOT, "Study Bot", Icons.BOT, 16),
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
