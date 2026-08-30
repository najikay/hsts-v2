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
        @DisplayName("a teacher gets home, settings, the bank, the monitor, the bot, results, "
                + "the grading pair, her exams and the preview of one")
        void teacher() {
            // One bank route, not two. Routes.QUESTIONS is the versioned bank since the
            // retirement PR: the id stayed and the screen behind it changed, so the interim
            // Routes.BANK that used to sit beside it here is gone rather than renamed.
            assertThat(SessionRoutes.routesFor(Role.TEACHER))
                    .containsExactly(Routes.HOME_TEACHER, Routes.SETTINGS, Routes.QUESTIONS, Routes.QUESTION_EDIT,
                            Routes.RELEASES, Routes.MONITOR, Routes.BOT_MANAGER,
                            Routes.BOT_ANALYTICS, Routes.RESULTS, Routes.GRADING,
                            Routes.GRADE_REVIEW, Routes.EXAMS, Routes.EXAM_BUILD,
                            Routes.EXAM_PREVIEW);
        }

        @Test
        @DisplayName("a coordinator gets the same plus the approvals queue (E8)")
        void coordinator() {
            assertThat(SessionRoutes.routesFor(Role.COORDINATOR))
                    .containsExactly(Routes.HOME_COORDINATOR, Routes.SETTINGS, Routes.QUESTIONS, Routes.QUESTION_EDIT,
                            Routes.RELEASES, Routes.MONITOR, Routes.BOT_MANAGER,
                            Routes.BOT_ANALYTICS, Routes.RESULTS, Routes.GRADING,
                            Routes.GRADE_REVIEW, Routes.EXAMS, Routes.EXAM_BUILD,
                            Routes.EXAM_PREVIEW, Routes.APPROVALS);
        }

        /**
         * The queue is the coordinator's; the preview is not (2026-08-30, Findings.txt, U-53) ⚑.
         *
         * <p>This test asserted both until U-53, and the pair was the right shape while a
         * teacher could not see her own exam at all. It is now two different claims. The
         * <b>queue</b> stays coordinator-only, because it lists other people's exams and every
         * decision on it is a coordinator verb. The <b>preview</b> is a read of one exam, and
         * {@code ApprovalService.preview} has admitted the version's own author since E8, so
         * withholding the route only meant a teacher could not read what she had written.
         *
         * <p>What must not follow from that is a teacher who can decide, and that is asserted on
         * the screen rather than here: {@code ApprovalInteractionTest} shows an author a preview
         * with no Approve and no Send back on it.
         */
        @Test
        @DisplayName("only a coordinator is offered the approvals QUEUE (PRD §3)")
        void theQueueIsCoordinatorOnly() {
            assertThat(SessionRoutes.routesFor(Role.TEACHER))
                    .doesNotContain(Routes.APPROVALS);
            assertThat(SessionRoutes.routesFor(Role.STUDENT))
                    .doesNotContain(Routes.APPROVALS, Routes.EXAM_PREVIEW, Routes.EXAMS);
            assertThat(SessionRoutes.routesFor(Role.PRINCIPAL))
                    .doesNotContain(Routes.APPROVALS, Routes.EXAM_PREVIEW, Routes.EXAMS);
        }

        /**
         * The other half of the split above: both authoring roles reach the preview (U-53).
         *
         * <p>Offering it to one and not the other is how a coordinator finds a button that
         * throws, which is the argument {@code Routes.EXAM_BUILD} already carries one line up:
         * {@code Navigator.navigate} throws on an unregistered id rather than doing nothing, and
         * the builder's Preview is on both roles' screen.
         */
        @Test
        @DisplayName("⚑ both roles that may author an exam may preview one (U-53)")
        void bothAuthoringRolesPreview() {
            assertThat(SessionRoutes.routesFor(Role.TEACHER)).contains(Routes.EXAM_PREVIEW);
            assertThat(SessionRoutes.routesFor(Role.COORDINATOR)).contains(Routes.EXAM_PREVIEW);
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
                    .doesNotContain(Routes.QUESTIONS, Routes.RELEASES, Routes.MONITOR,
                            Routes.BOT_MANAGER, Routes.BOT_ANALYTICS,
                            // The teacher's results screen is a different route from her own
                            // grades, and only one of them is hers (E13.1 vs E14). Grading is
                            // hers to do, not hers to receive, and the review screen behind it
                            // carries the answer key (U-38) — CHECKED_FORM is her version of
                            // that paper and it is a different route on a different verb.
                            Routes.RESULTS, Routes.GRADING, Routes.GRADE_REVIEW);
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
                    .as("every one of her routes is a read: REPORT_SUBJECTS_GET and REPORT_GET "
                            + "behind Reports, BANK_LIST, DATA_EXAMS_GET and DATA_RESULTS_GET "
                            + "behind Data, and behind the three screens a Data row opens "
                            + "(U-44, 2026-08-30) QUESTION_GET, QUESTION_VERSIONS, "
                            + "QUESTION_IMAGE_GET and EXAM_PREVIEW_GET. Nine verbs and not one "
                            + "of them writes (S-7). "
                            + "Routes.QUESTIONS was absent before the retirement PR and is "
                            + "absent after it, and the reason got stronger rather than weaker: "
                            + "the id now serves BankView, which carries Delete and Edit, so "
                            + "her bank read stays the Data screen (ruling on #41)")
                    .containsExactly(Routes.HOME_PRINCIPAL, Routes.SETTINGS, Routes.REPORTS,
                            Routes.DATA, Routes.DATA_QUESTION, Routes.DATA_EXAM,
                            Routes.DATA_RESULTS);
        }

        @Test
        @DisplayName("⚑ and nobody else gets the three screens her Data rows open (U-44)")
        void theDataDetailsArePrincipalOnly() {
            for (Role role : List.of(Role.TEACHER, Role.COORDINATOR, Role.STUDENT)) {
                assertThat(SessionRoutes.routesFor(role))
                        .as("%s has no route into the principal's data browser", role)
                        .doesNotContain(Routes.DATA, Routes.DATA_QUESTION, Routes.DATA_EXAM,
                                Routes.DATA_RESULTS);
            }
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
