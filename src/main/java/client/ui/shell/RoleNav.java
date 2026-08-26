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
 * <p><b>As of U-1 there is nothing left to swap.</b> Take Exam and Live Monitor
 * were the last two placeholders, and both of their screens had been registered and
 * routable for weeks: a student's only way into an exam was the dashboard's code
 * card, and a teacher's only way into a live sitting was a Releases row or a C-4
 * alert, while the rail item beside each of them still read "Arrives with E10" /
 * "Arrives with E11". Every item on every rail is now live. The mechanism stays
 * where it is for the next feature that needs it.
 *
 * <p>The rail is not a permission boundary and never pretends to be — the server
 * re-authorises every request from the session (ARCHITECTURE §3). Hiding
 * Approvals from a teacher is a courtesy to the teacher, not a defence.
 */
public final class RoleNav {

    /**
     * The ids this rail reserved for screens that had not been built yet.
     *
     * <p>Kept because {@link Routes} names them: each of these constants is the reason
     * the screen behind it could go live as a swap here rather than as a rename
     * everywhere, and the {@code Routes} javadoc that says so should point at something
     * real. {@code exams} and {@code approvals} left the list in E8 and {@code releases}
     * in E9, each taking a {@link Routes} constant of its own.
     *
     * <p>{@code monitor} and {@code exam.take} left in U-1, and only one of them left
     * quietly: {@code ROUTE_MONITOR} had always been spelled the way {@link Routes#MONITOR}
     * spells it, while the take-exam placeholder read {@code "exam.take"} and the live route
     * has read {@code "attempt"} since E10, because that is what an "extra time added"
     * notification navigates to. Enabling that item was therefore a swap onto the route
     * constant and not a promotion of the string beside it, which is exactly why the
     * placeholder id is gone instead of being kept for the record.
     */
    static final String ROUTE_GRADING = "grading";
    static final String ROUTE_RESULTS = "results";
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
        // Live since E5.4 and unchanged by the retirement PR: the id and the label stayed put
        // while the screen behind them became the versioned bank (E6.9). The rail is the part
        // a teacher remembers, so it was the part that did not move.
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
        // Live since E9. The rail item had reserved this slot as a disabled "Arrives with
        // E9" since E5.4; enabling it moved it onto Routes.RELEASES, whose id is "release"
        // because that is what the "opens soon" notification navigates to.
        items.add(NavItem.of(Routes.RELEASES.id(), "Releases", Icons.RELEASE));
        // Live since E11, on the rail since U-1. The screen has been registered and routable
        // the whole time — a C-4 alert and a Releases row both open it — but the rail item
        // beside them still read "Arrives with E11", so the one destination a teacher would
        // look for during a sitting was the one place she could not click. Entering from here
        // carries no execution, which the screen answers with its own chooser state rather
        // than with a request for sitting zero.
        items.add(NavItem.of(Routes.MONITOR.id(), "Live Monitor", Icons.MONITOR));
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
                // Live since E10, on the rail since U-1. Until then the only door into an exam
                // was the dashboard's code card, so a student whose dashboard had scrolled or
                // whose sitting was not yet listed had a rail item named exactly what she wanted
                // and could not press it. The route id is Routes.TAKE_EXAM's ("attempt") and not
                // the placeholder's ("exam.take"): the flow starts at the code screen, which is
                // what TakeExamView.onShow builds whether or not a code was handed to it.
                NavItem.of(Routes.TAKE_EXAM.id(), "Take Exam", Icons.EXAMS),
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
                // Live since E15.2. The rail item has reserved this slot since E5.4 and its id
                // is still ROUTE_DATA: Routes.DATA was declared with the same string, so
                // enabling the feature was a swap here rather than a rename anywhere.
                NavItem.of(Routes.DATA.id(), "Data", Icons.BANK),
                // Live since E15.4. The rail item has reserved this slot since E5.4 and its id
                // is still ROUTE_REPORTS: Routes.REPORTS was declared with the same string, so
                // enabling the feature was a swap here rather than a rename anywhere.
                NavItem.of(Routes.REPORTS.id(), "Reports", Icons.REPORTS),
                settings());
    }

    private static NavItem dashboard(Role role) {
        return NavItem.of(Routes.home(role).id(), "Dashboard", Icons.DASHBOARD);
    }

    private static NavItem settings() {
        return NavItem.of(Routes.SETTINGS.id(), "Settings", Icons.SETTINGS);
    }
}
