package client.ui.shell;

import client.core.Routes;
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
        @DisplayName("coordinator — the teacher's rail plus Approvals")
        void coordinatorRail() {
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
    @DisplayName("what is actually reachable in E5")
    class Enabled {

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("Dashboard and Settings are live for every role")
        void dashboardAndSettingsAreLive(Role role) {
            assertThat(enabledLabels(role)).contains("Dashboard", "Settings");
        }

        @Test
        @DisplayName("the teaching roles also get the (legacy) question bank and the study bot")
        void questionBankIsLiveForTeachers() {
            assertThat(enabledLabels(Role.TEACHER))
                    .containsExactly("Dashboard", "Question Bank", "Study Bot", "Settings");
            assertThat(enabledLabels(Role.COORDINATOR))
                    .containsExactly("Dashboard", "Question Bank", "Study Bot", "Settings");
            assertThat(enabledLabels(Role.STUDENT))
                    .containsExactly("Dashboard", "Study Bot", "Settings");
            assertThat(enabledLabels(Role.PRINCIPAL)).containsExactly("Dashboard", "Settings");
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
