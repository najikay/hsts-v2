package client.core;

import client.ui.screen.ScreenFactory;
import common.dto.auth.LoginResult;
import common.dto.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link SessionRoutes} and the role half of {@link Routes} (E5.4).
 *
 * <p>No JavaFX toolkit is needed: registering a screen stores a supplier, it does
 * not build a node graph — which is exactly why the "which role can go where"
 * rule was put in a class that can be tested like this.
 */
class SessionRoutesTest {

    private Navigator navigator;
    private ScreenFactory screens;

    @BeforeEach
    void setUp() {
        navigator = new Navigator();
        screens = new ScreenFactory();
        Routes.registerPreLogin(navigator);
    }

    @Nested
    @DisplayName("route table")
    class Table {

        @Test
        @DisplayName("each role lands on its own dashboard")
        void homePerRole() {
            assertThat(Routes.home(Role.TEACHER)).isEqualTo(Routes.HOME_TEACHER);
            assertThat(Routes.home(Role.COORDINATOR)).isEqualTo(Routes.HOME_COORDINATOR);
            assertThat(Routes.home(Role.STUDENT)).isEqualTo(Routes.HOME_STUDENT);
            assertThat(Routes.home(Role.PRINCIPAL)).isEqualTo(Routes.HOME_PRINCIPAL);
            assertThatNullPointerException().isThrownBy(() -> Routes.home(null));
        }

        @Test
        @DisplayName("the pre-login routes are Connect and Login, and they are full-bleed")
        void preLoginRoutes() {
            assertThat(Routes.preLogin()).containsExactly(Routes.CONNECT, Routes.LOGIN);
            assertThat(Routes.CONNECT.requiresShell()).isFalse();
            assertThat(Routes.LOGIN.requiresShell()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("every signed-in route renders inside the shell")
        void sessionRoutesAreShellHosted(Role role) {
            assertThat(SessionRoutes.routesFor(role)).allSatisfy(route ->
                    assertThat(route.requiresShell())
                            .as("%s is shell-hosted", route.id())
                            .isTrue());
        }

        @Test
        @DisplayName("route ids are unique across the whole table")
        void idsAreUnique() {
            assertThat(Routes.all()).extracting(Route::id).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("all dashboards share one breadcrumb label, so the rail reads the same")
        void dashboardsShareABreadcrumb() {
            assertThat(List.of(Routes.HOME_TEACHER, Routes.HOME_COORDINATOR,
                            Routes.HOME_STUDENT, Routes.HOME_PRINCIPAL))
                    .allSatisfy(route -> assertThat(route.breadcrumb()).isEqualTo("Dashboard"));
        }
    }

    @Nested
    @DisplayName("what each role may navigate to")
    class PerRole {

        @Test
        @DisplayName("a teacher gets home, settings, the bank, the monitor, the bot, results and her exams")
        void teacher() {
            assertThat(SessionRoutes.routesFor(Role.TEACHER))
                    .containsExactly(Routes.HOME_TEACHER, Routes.SETTINGS, Routes.QUESTIONS,
                            Routes.MONITOR, Routes.BOT_MANAGER, Routes.BOT_ANALYTICS,
                            Routes.RESULTS, Routes.GRADING, Routes.EXAMS);
        }

        @Test
        @DisplayName("a coordinator gets the same plus the approvals pair (E8)")
        void coordinator() {
            assertThat(SessionRoutes.routesFor(Role.COORDINATOR))
                    .containsExactly(Routes.HOME_COORDINATOR, Routes.SETTINGS, Routes.QUESTIONS,
                            Routes.MONITOR, Routes.BOT_MANAGER, Routes.BOT_ANALYTICS,
                            Routes.RESULTS, Routes.GRADING, Routes.EXAMS, Routes.APPROVALS,
                            Routes.EXAM_PREVIEW);
        }

        @Test
        @DisplayName("only a coordinator is offered the approvals screens (PRD §3)")
        void approvalsAreCoordinatorOnly() {
            assertThat(SessionRoutes.routesFor(Role.TEACHER))
                    .doesNotContain(Routes.APPROVALS, Routes.EXAM_PREVIEW);
            assertThat(SessionRoutes.routesFor(Role.STUDENT))
                    .doesNotContain(Routes.APPROVALS, Routes.EXAM_PREVIEW, Routes.EXAMS);
            assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                    .doesNotContain(Routes.APPROVALS, Routes.EXAM_PREVIEW, Routes.EXAMS);
        }

        @Test
        @DisplayName("a student gets take-exam, her own bot screens and her grades, "
                + "and no authoring route")
        void student() {
            assertThat(SessionRoutes.routesFor(Role.STUDENT))
                    .containsExactly(Routes.HOME_STUDENT, Routes.SETTINGS, Routes.TAKE_EXAM,
                            Routes.BOT_CHAT, Routes.BOT_HISTORY, Routes.MY_GRADES,
                            Routes.CHECKED_FORM)
                    .as("the teacher's half of the bot is not offered to her either (E16)")
                    .doesNotContain(Routes.QUESTIONS, Routes.MONITOR,
                            Routes.BOT_MANAGER, Routes.BOT_ANALYTICS,
                            // The teacher's results screen is a different route from her own
                            // grades, and only one of them is hers (E13.1 vs E14). Grading is
                            // hers to do, not hers to receive.
                            Routes.RESULTS, Routes.GRADING);
        }

        @Test
        @DisplayName("no teaching role is offered the take-exam screen (E10)")
        void teachersDoNotSitExams() {
            // The server checks enrolment and identity on every one of those verbs, so this
            // list decides what is offered and never what is permitted. It is still worth
            // pinning: an exam screen on a teacher's rail is a demo question nobody wants.
            assertThat(SessionRoutes.routesFor(Role.TEACHER)).doesNotContain(Routes.TAKE_EXAM);
            assertThat(SessionRoutes.routesFor(Role.COORDINATOR)).doesNotContain(Routes.TAKE_EXAM);
            assertThat(SessionRoutes.routesFor(Role.PRINCIPAL)).doesNotContain(Routes.TAKE_EXAM);
        }

        @Test
        @DisplayName("a principal gets no authoring route either (S-7)")
        void principal() {
            assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                    .containsExactly(Routes.HOME_PRINCIPAL, Routes.SETTINGS);
        }

        @Test
        @DisplayName("teaches() is the one place the two authoring roles are named")
        void teachesRoles() {
            assertThat(SessionRoutes.teaches(Role.TEACHER)).isTrue();
            assertThat(SessionRoutes.teaches(Role.COORDINATOR)).isTrue();
            assertThat(SessionRoutes.teaches(Role.STUDENT)).isFalse();
            assertThat(SessionRoutes.teaches(Role.PRINCIPAL)).isFalse();
        }

        @Test
        @DisplayName("a role is required")
        void roleIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> SessionRoutes.routesFor(null));
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("registers every route of the role, with a screen behind each")
        void registersRoutesAndScreens(Role role) {
            SessionRoutes.register(navigator, screens, role);

            for (Route route : SessionRoutes.routesFor(role)) {
                assertThat(navigator.isRegistered(route.id())).isTrue();
                assertThat(screens.isRegistered(route.id())).isTrue();
            }
        }

        @Test
        @DisplayName("a student's client never learns that the teacher routes exist")
        void unregisteredRoutesAreUnreachable() {
            SessionRoutes.register(navigator, screens, Role.STUDENT);

            assertThat(navigator.isRegistered(Routes.QUESTIONS.id())).isFalse();
            assertThat(navigator.isRegistered(Routes.HOME_TEACHER.id())).isFalse();
        }

        @Test
        @DisplayName("registering twice is a no-op, so logout → login does not explode")
        void registrationIsIdempotent() {
            SessionRoutes.register(navigator, screens, Role.TEACHER);

            assertThatCode(() -> SessionRoutes.register(navigator, screens, Role.TEACHER))
                    .doesNotThrowAnyException();
            assertThat(navigator.routes()).extracting(Route::id).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a different role signing in next adds its routes alongside")
        void signingInAsAnotherRole() {
            SessionRoutes.register(navigator, screens, Role.TEACHER);

            assertThatCode(() -> SessionRoutes.register(navigator, screens, Role.STUDENT))
                    .doesNotThrowAnyException();
            assertThat(navigator.isRegistered(Routes.HOME_STUDENT.id())).isTrue();
            assertThat(screens.registeredRouteIds())
                    .contains(Routes.HOME_TEACHER.id(), Routes.HOME_STUDENT.id());
        }

        @Test
        @DisplayName("homeFor reads the role out of the login result")
        void homeForALogin() {
            LoginResult login = new LoginResult(1, "u", "U", Role.PRINCIPAL, List.of());

            assertThat(SessionRoutes.homeFor(login)).isEqualTo(Routes.HOME_PRINCIPAL);
            assertThatNullPointerException().isThrownBy(() -> SessionRoutes.homeFor(null));
        }

        @Test
        @DisplayName("collaborators are required")
        void collaboratorsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SessionRoutes.register(null, screens, Role.STUDENT));
            assertThatNullPointerException()
                    .isThrownBy(() -> SessionRoutes.register(navigator, null, Role.STUDENT));
        }
    }
}
