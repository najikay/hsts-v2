package client.ui.shell;

import client.core.Routes;
import common.dto.auth.CourseRef;
import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link RoleNav} — "the UI presents a role-appropriate shell" (F1.2 / T-1),
 * checked one role at a time (E5.4).
 *
 * <p>The nav matrix is the demo-visible half of the requirement, so it is
 * asserted literally: exact items, exact order, and exactly which of them are
 * live in this epic. A future epic enabling its screen has to come here and say
 * so, which is the point.
 *
 * <p>Since U-41 the matrix has five rows rather than four, because F1.2 derives the
 * shell from the role <i>and</i> the course relations and the coordinator is the role
 * where that second half changes the answer. {@link PureCoordinator} is that row.
 */
class RoleNavTest {

    @Nested
    @DisplayName("per-role menus")
    class Menus {

        @Test
        @DisplayName("teacher")
        void teacherRail() {
            assertThat(labels(Role.TEACHER)).containsExactly(
                    "Dashboard", "Question Bank", "Exams", "Releases", "Live Monitor",
                    "Grading", "Results", "Study Bot", "Settings");
        }

        @Test
        @DisplayName("coordinator, courses unknown — the teacher's rail plus Approvals")
        void coordinatorRail() {
            // The one-argument form means "this caller has no course list", which is not
            // the same as an empty one and must not narrow anything (U-41).
            assertThat(labels(Role.COORDINATOR)).containsExactly(
                    "Dashboard", "Question Bank", "Exams", "Approvals", "Releases",
                    "Live Monitor", "Grading", "Results", "Study Bot", "Settings");
            assertThat(labels(Role.COORDINATOR)).containsAll(labels(Role.TEACHER));
        }

        @Test
        @DisplayName("only the coordinator gets Approvals")
        void approvalsAreCoordinatorOnly() {
            assertThat(labels(Role.TEACHER)).doesNotContain("Approvals");
            assertThat(labels(Role.STUDENT)).doesNotContain("Approvals");
            assertThat(labels(Role.PRINCIPAL)).doesNotContain("Approvals");
        }

        @Test
        @DisplayName("student")
        void studentRail() {
            assertThat(labels(Role.STUDENT)).containsExactly(
                    "Dashboard", "Take Exam", "My Grades", "Study Bot", "Settings");
        }

        @Test
        @DisplayName("principal — read-only, nothing that authors or grades (F9.3)")
        void principalRail() {
            assertThat(labels(Role.PRINCIPAL)).containsExactly(
                    "Dashboard", "Data", "Reports", "Settings");
            assertThat(labels(Role.PRINCIPAL))
                    .doesNotContain("Question Bank", "Exams", "Grading", "Releases", "Take Exam");
        }

        @Test
        @DisplayName("each role's dashboard item points at that role's home route")
        void dashboardRoutesPerRole() {
            assertThat(first(Role.TEACHER).routeId()).isEqualTo(Routes.HOME_TEACHER.id());
            assertThat(first(Role.COORDINATOR).routeId()).isEqualTo(Routes.HOME_COORDINATOR.id());
            assertThat(first(Role.STUDENT).routeId()).isEqualTo(Routes.HOME_STUDENT.id());
            assertThat(first(Role.PRINCIPAL).routeId()).isEqualTo(Routes.HOME_PRINCIPAL.id());
        }

        @Test
        @DisplayName("a rail cannot be built without a role")
        void roleIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> RoleNav.itemsFor(null));
        }
    }

    @Nested
    @DisplayName("⚑ U-41: the coordinator's rail also reads her courses (F1.2)")
    class PureCoordinator {

        /** {@code rina.barak}: coordinates Mathematics 10, zero {@code course_teachers} rows. */
        private static final List<CourseRef> TEACHES_NOTHING = List.of();

        /** {@code michal.sharon}: coordinates Computer Science 20 and teaches Databases 22. */
        private static final List<CourseRef> DUAL_HAT = List.of(new CourseRef("22", "Databases 22"));

        @Test
        @DisplayName("teaches nothing — Dashboard, Question Bank, Approvals, Settings")
        void pureCoordinatorGetsFourItems() {
            // The six that are gone are the six scoped to courses she teaches, and each of
            // them opened an empty screen on this account. Question Bank stays because the
            // bank's read scope is her whole coordinated subject (BANK contract §7.3).
            assertThat(labels(Role.COORDINATOR, TEACHES_NOTHING)).containsExactly(
                    "Dashboard", "Question Bank", "Approvals", "Settings");
            assertThat(labels(Role.COORDINATOR, TEACHES_NOTHING)).doesNotContain(
                    "Exams", "Releases", "Live Monitor", "Grading", "Results", "Study Bot");
        }

        @Test
        @DisplayName("teaches nothing — Approvals is still there, because it never needed a course")
        void approvalsSurvive() {
            assertThat(routeOf(Role.COORDINATOR, TEACHES_NOTHING, "Approvals"))
                    .isEqualTo(Routes.APPROVALS.id());
            assertThat(routeOf(Role.COORDINATOR, TEACHES_NOTHING, "Question Bank"))
                    .isEqualTo(Routes.QUESTIONS.id());
            assertThat(routeOf(Role.COORDINATOR, TEACHES_NOTHING, "Dashboard"))
                    .isEqualTo(Routes.HOME_COORDINATOR.id());
        }

        @Test
        @DisplayName("teaches something — the full rail, unchanged")
        void dualHatCoordinatorKeepsEverything() {
            assertThat(labels(Role.COORDINATOR, DUAL_HAT)).containsExactly(
                    "Dashboard", "Question Bank", "Exams", "Approvals", "Releases",
                    "Live Monitor", "Grading", "Results", "Study Bot", "Settings");
        }

        @Test
        @DisplayName("no course list is not an empty course list")
        void anAbsentListNarrowsNothing() {
            // The failure this guards against is silent: a call site that forgets the
            // argument would take six items off a real coordinator's rail and nothing
            // would throw.
            assertThat(labels(Role.COORDINATOR, null))
                    .isEqualTo(labels(Role.COORDINATOR))
                    .hasSize(10);
        }

        @Test
        @DisplayName("the other three roles do not read the list at all")
        void otherRolesAreUnchanged() {
            for (Role role : List.of(Role.TEACHER, Role.STUDENT, Role.PRINCIPAL)) {
                assertThat(labels(role, TEACHES_NOTHING))
                        .as("%s with no courses", role)
                        .isEqualTo(labels(role));
                assertThat(labels(role, DUAL_HAT))
                        .as("%s with courses", role)
                        .isEqualTo(labels(role));
            }
        }

        @Test
        @DisplayName("the narrowed rail is as well formed as every other one")
        void theNarrowedRailIsWellFormed() {
            List<NavItem> items = RoleNav.itemsFor(Role.COORDINATOR, TEACHES_NOTHING);
            List<String> known = Routes.all().stream().map(client.core.Route::id).toList();

            assertThat(items).isNotEmpty();
            assertThat(items).extracting(NavItem::routeId).doesNotHaveDuplicates();
            assertThat(items).allSatisfy(item -> {
                assertThat(item.enabled()).as("%s is live", item.label()).isTrue();
                assertThat(item.label()).isNotBlank();
                assertThat(item.icon()).isNotBlank();
                assertThat(known).contains(item.routeId());
            });
        }

        @Test
        @DisplayName("a rail cannot be built without a role, list or no list")
        void roleIsStillRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> RoleNav.itemsFor(null, TEACHES_NOTHING));
            assertThatNullPointerException()
                    .isThrownBy(() -> RoleNav.itemsFor(null, null));
        }

        private String routeOf(Role role, List<CourseRef> courses, String label) {
            return RoleNav.itemsFor(role, courses).stream()
                    .filter(item -> item.label().equals(label))
                    .map(NavItem::routeId)
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("what is actually reachable in E5")
    class Enabled {

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("Dashboard and Settings are live for every role")
        void dashboardAndSettingsAreLive(Role role) {
            assertThat(enabledLabels(role)).contains("Dashboard", "Settings");
        }

        @Test
        @DisplayName("the teaching roles also get the bank, their exams, results and the study bot")
        void questionBankIsLiveForTeachers() {
            // Exams went live with E8.6 carrying its approval-status half only, and
            // Approvals with E8.3; the exam builder behind the same route id is E7's.
            // ⚑ U-1: Live Monitor joins them. E11 shipped the screen and registered the
            // route, and every path into it carried an execution, so the rail item that
            // could not was left saying "Arrives with E11" for four epics after it had.
            assertThat(enabledLabels(Role.TEACHER))
                    .containsExactly("Dashboard", "Question Bank", "Exams", "Releases",
                            "Live Monitor", "Grading", "Results", "Study Bot", "Settings");
            assertThat(enabledLabels(Role.COORDINATOR))
                    .containsExactly("Dashboard", "Question Bank", "Exams", "Approvals",
                            "Releases", "Live Monitor", "Grading", "Results", "Study Bot",
                            "Settings");
            // My Grades went live with E13.3; the rail item had reserved the slot as a
            // disabled "Arrives with E13" since E5.4.
            // ⚑ U-1: and Take Exam, whose screen has been the student's exam flow since
            // E10. Her only door into it was the dashboard's code card.
            assertThat(enabledLabels(Role.STUDENT))
                    .containsExactly("Dashboard", "Take Exam", "My Grades", "Study Bot",
                            "Settings");
            // Reports went live with E15.4 and Data with E15.2; both rail items had reserved
            // their slots as disabled "Arrives with E15" entries since E5.4. Her rail now has
            // nothing disabled on it, and nothing on it that writes (S-7).
            assertThat(enabledLabels(Role.PRINCIPAL))
                    .containsExactly("Dashboard", "Data", "Reports", "Settings");
        }

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("⚑ U-1: no rail carries a placeholder any more")
        void nothingIsDisabledAnywhere(Role role) {
            // The state of the app, asserted rather than assumed. Take Exam and Live Monitor
            // were the last two, and both had been routable for weeks: a rail item is the
            // one part of a screen a user is told to look for, so a live feature behind a
            // dead label is a feature nobody can find. Adding the next placeholder means
            // coming here and saying so, which is the point of asserting it.
            assertThat(RoleNav.itemsFor(role))
                    .as("%s", role)
                    .allSatisfy(item -> assertThat(item.enabled()).isTrue());
        }

        @Test
        @DisplayName("⚑ U-1: the two enabled items point at the live routes, not the placeholder ids")
        void theSwappedItemsPointAtTheRealRoutes() {
            // The take-exam placeholder read "exam.take" and the live route has read
            // "attempt" since E10, because that is what an "extra time added" notification
            // navigates to. Promoting the string beside the label instead of swapping onto
            // the route constant would have registered nothing and thrown on the click.
            assertThat(routeOf(Role.STUDENT, "Take Exam")).isEqualTo(Routes.TAKE_EXAM.id());
            assertThat(routeOf(Role.STUDENT, "Take Exam")).isEqualTo("attempt");
            assertThat(routeOf(Role.TEACHER, "Live Monitor")).isEqualTo(Routes.MONITOR.id());
            assertThat(routeOf(Role.COORDINATOR, "Live Monitor")).isEqualTo(Routes.MONITOR.id());
        }

        @Test
        @DisplayName("Study Bot points each role at its own half of the feature (E16)")
        void studyBotRoutesPerRole() {
            assertThat(routeOf(Role.TEACHER, "Study Bot")).isEqualTo(Routes.BOT_MANAGER.id());
            assertThat(routeOf(Role.COORDINATOR, "Study Bot")).isEqualTo(Routes.BOT_MANAGER.id());
            assertThat(routeOf(Role.STUDENT, "Study Bot")).isEqualTo(Routes.BOT_CHAT.id());
        }

        /** @return the route id behind one label on a role's rail. */
        private String routeOf(Role role, String label) {
            return RoleNav.itemsFor(role).stream()
                    .filter(item -> item.label().equals(label))
                    .map(NavItem::routeId)
                    .findFirst()
                    .orElseThrow();
        }

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("every disabled item explains itself with the epic it arrives with")
        void disabledItemsCarryAReason(Role role) {
            RoleNav.itemsFor(role).stream()
                    .filter(item -> !item.enabled())
                    .forEach(item -> assertThat(item.tooltipText())
                            .as("%s explains why it is unavailable", item.label())
                            .matches("Arrives with E\\d+"));
        }

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("no rail carries a duplicate route or an empty label")
        void railsAreWellFormed(Role role) {
            List<NavItem> items = RoleNav.itemsFor(role);

            assertThat(items).extracting(NavItem::routeId).doesNotHaveDuplicates();
            assertThat(items).allSatisfy(item -> {
                assertThat(item.label()).isNotBlank();
                assertThat(item.icon()).isNotBlank();
                assertThat(item.hasBadge()).as("badges arrive with E17").isFalse();
            });
        }

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("every enabled item points at a route that exists")
        void enabledItemsHaveRealRoutes(Role role) {
            List<String> known = Routes.all().stream().map(client.core.Route::id).toList();

            assertThat(RoleNav.itemsFor(role).stream()
                    .filter(NavItem::enabled)
                    .map(NavItem::routeId))
                    .allSatisfy(routeId -> assertThat(known).contains(routeId));
        }
    }

    private static List<String> labels(Role role) {
        return RoleNav.itemsFor(role).stream().map(NavItem::label).toList();
    }

    private static List<String> labels(Role role, List<CourseRef> courses) {
        return RoleNav.itemsFor(role, courses).stream().map(NavItem::label).toList();
    }

    private static List<String> enabledLabels(Role role) {
        return RoleNav.itemsFor(role).stream()
                .filter(NavItem::enabled)
                .map(NavItem::label)
                .toList();
    }

    private static NavItem first(Role role) {
        return RoleNav.itemsFor(role).get(0);
    }
}
