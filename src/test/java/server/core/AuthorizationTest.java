package server.core;

import common.dto.auth.Role;
import common.protocol.ErrorCode;
import ocsf.server.ConnectionToClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CallerContext} and the {@link Authorization} guards
 * (E3.5) — every pass and fail path of every implemented guard.
 *
 * <p>Two distinctions matter and are asserted separately everywhere:
 * "not signed in" is {@link ErrorCode#UNAUTHORIZED} (the client should send the
 * user to the login screen) while "signed in but not allowed" is
 * {@link ErrorCode#FORBIDDEN} (the client should show a refusal). Getting these
 * backwards would produce a logout loop, so they are pinned down.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationTest {

    @Mock
    private ConnectionToClient connection;

    private CallerContext anonymous() {
        return CallerContext.anonymous(connection);
    }

    private CallerContext caller(long userId, Role role) {
        return CallerContext.authenticated(connection, userId, role);
    }

    @Nested
    @DisplayName("CallerContext")
    class Context {

        @Test
        @DisplayName("an anonymous caller exposes no identity")
        void anonymousHasNoIdentity() {
            CallerContext caller = anonymous();

            assertThat(caller.isAuthenticated()).isFalse();
            assertThat(caller.role()).isEmpty();
            assertThat(caller.connection()).isSameAs(connection);
            assertThat(caller.hasAnyRole(Role.STUDENT)).isFalse();
            assertThat(caller.toString()).contains("anonymous");
        }

        @Test
        @DisplayName("reading the user id of an anonymous caller is a programming error, not a silent 0")
        void anonymousUserIdThrows() {
            assertThatIllegalStateException().isThrownBy(() -> anonymous().userId());
        }

        @Test
        @DisplayName("an authenticated caller exposes id, role and connection")
        void authenticatedExposesIdentity() {
            CallerContext caller = caller(7L, Role.TEACHER);

            assertThat(caller.isAuthenticated()).isTrue();
            assertThat(caller.userId()).isEqualTo(7L);
            assertThat(caller.role()).contains(Role.TEACHER);
            assertThat(caller.toString()).contains("userId=7").contains("TEACHER");
        }

        @Test
        @DisplayName("hasAnyRole matches any of the listed roles and nothing else")
        void hasAnyRoleSemantics() {
            CallerContext teacher = caller(1L, Role.TEACHER);

            assertThat(teacher.hasAnyRole(Role.TEACHER)).isTrue();
            assertThat(teacher.hasAnyRole(Role.PRINCIPAL, Role.TEACHER)).isTrue();
            assertThat(teacher.hasAnyRole(Role.STUDENT, Role.PRINCIPAL)).isFalse();
            assertThat(teacher.hasAnyRole()).isFalse();
            assertThat(teacher.hasAnyRole((Role[]) null)).isFalse();
        }

        @Test
        @DisplayName("a caller whose role is not known yet is authenticated but role-less")
        void authenticatedWithoutARole() {
            CallerContext caller = caller(3L, null);

            assertThat(caller.isAuthenticated()).isTrue();
            assertThat(caller.role()).isEmpty();
            assertThat(caller.hasAnyRole(Role.STUDENT)).isFalse();
        }
    }

    @Nested
    @DisplayName("requireAuthenticated")
    class RequireAuthenticated {

        @Test
        @DisplayName("passes for any signed-in caller")
        void passesWhenSignedIn() {
            assertThatCode(() -> Authorization.requireAuthenticated(caller(1L, Role.STUDENT)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects an anonymous caller with UNAUTHORIZED")
        void rejectsAnonymous() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireAuthenticated(anonymous()))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED))
                    .withMessageContaining("signed in");
        }

        @Test
        @DisplayName("rejects a null caller with UNAUTHORIZED rather than an NPE")
        void rejectsNullCaller() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireAuthenticated(null))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }

    @Nested
    @DisplayName("requireRole")
    class RequireRole {

        @ParameterizedTest
        @EnumSource(Role.class)
        @DisplayName("passes when the caller holds the required role")
        void passesForTheMatchingRole(Role role) {
            assertThatCode(() -> Authorization.requireRole(caller(1L, role), role))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when the caller holds one of several allowed roles")
        void passesForOneOfSeveral() {
            assertThatCode(() -> Authorization.requireRole(
                    caller(1L, Role.COORDINATOR), Role.TEACHER, Role.COORDINATOR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects the wrong role with FORBIDDEN and names what was needed")
        void rejectsTheWrongRole() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireRole(caller(1L, Role.STUDENT), Role.TEACHER))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                    .withMessageContaining("TEACHER");
        }

        @Test
        @DisplayName("names all acceptable roles when several are allowed")
        void namesAllAllowedRoles() {
            assertThatThrownBy(() -> Authorization.requireRole(
                    caller(1L, Role.STUDENT), Role.TEACHER, Role.PRINCIPAL))
                    .hasMessageContaining("TEACHER")
                    .hasMessageContaining("PRINCIPAL");
        }

        @Test
        @DisplayName("checks the session first: anonymous is UNAUTHORIZED, not FORBIDDEN")
        void anonymousIsUnauthorizedNotForbidden() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireRole(anonymous(), Role.TEACHER))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("an empty or null role list forbids everyone — fail closed")
        void emptyRoleListForbids() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireRole(caller(1L, Role.PRINCIPAL)))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireRole(caller(1L, Role.PRINCIPAL), (Role[]) null))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("a caller with no known role is forbidden, never waved through")
        void unknownRoleIsForbidden() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireRole(caller(1L, null), Role.TEACHER))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("requireSelf")
    class RequireSelf {

        @Test
        @DisplayName("passes when the caller is the subject")
        void passesForOwnData() {
            assertThatCode(() -> Authorization.requireSelf(caller(9L, Role.STUDENT), 9L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects another user's data with FORBIDDEN")
        void rejectsSomeoneElsesData() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireSelf(caller(9L, Role.STUDENT), 10L))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                    .withMessageContaining("your own");
        }

        @Test
        @DisplayName("rejects an anonymous caller with UNAUTHORIZED")
        void rejectsAnonymous() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireSelf(anonymous(), 1L))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }

    @Nested
    @DisplayName("course-scoped guards (TODO(E2) stubs)")
    class CourseScoped {

        @Test
        @DisplayName("they fail closed instead of permitting, and say why")
        void stubsRefuse() {
            CallerContext teacher = caller(1L, Role.TEACHER);

            assertThatThrownBy(() -> Authorization.requireTeachesCourse(teacher, "11"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("TODO(E2)")
                    .hasMessageContaining("requireTeachesCourse");
            assertThatThrownBy(() -> Authorization.requireEnrolled(teacher, "11"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("requireEnrolled");
        }

        @Test
        @DisplayName("they still check the session first")
        void stubsCheckTheSessionFirst() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(anonymous(), "11"));
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireEnrolled(anonymous(), "11"));
        }
    }

    /**
     * The one course-scoped guard that is no longer a stub (E8).
     *
     * <p>Two things have to hold for it to pass, and each of them is asserted failing on its
     * own: the caller is a {@code COORDINATOR}, and the directory says she coordinates
     * <em>this</em> subject. The second without the first is what would let a plain teacher
     * with a coordinators row approve; the first without the second is what would let the
     * Mathematics coordinator approve a Computer Science exam.
     */
    @Nested
    @DisplayName("requireCoordinatorOf (implemented in E8)")
    class CoordinatorOf {

        /** Rina coordinates Mathematics (10) and nothing else. */
        private final Authorization.SubjectCoordinators directory =
                (teacherId, subjectCode) -> teacherId == 3L && "10".equals(subjectCode);

        @AfterEach
        void uninstall() {
            Authorization.useSubjectCoordinators(null);
        }

        @Test
        @DisplayName("passes for the coordinator of that subject")
        void passesForHerOwnSubject() {
            assertThatCode(() -> Authorization.requireCoordinatorOf(
                    caller(3L, Role.COORDINATOR), "10", directory))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses another subject with FORBIDDEN, naming the subject and the next step")
        void refusesAnotherSubject() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), "20", directory))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                    .withMessageContaining("20")
                    .withMessageContaining("Ask that subject's coordinator");
        }

        @Test
        @DisplayName("a teacher who is not a coordinator is refused before the directory is asked")
        void refusesAPlainTeacher() {
            Authorization.SubjectCoordinators wouldSayYes = (teacherId, subjectCode) -> true;

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.TEACHER), "10", wouldSayYes))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                    .withMessageContaining("COORDINATOR");
        }

        @Test
        @DisplayName("an anonymous caller is UNAUTHORIZED, not FORBIDDEN")
        void anonymousIsUnauthorized() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(anonymous(), "10", directory))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("a missing subject refuses rather than acting as a wildcard")
        void blankSubjectRefuses() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), "  ", directory))
                    .withMessageContaining("not linked to a subject");
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), null, directory));
        }

        @Test
        @DisplayName("a null directory refuses: a guard that cannot check has not checked")
        void nullDirectoryRefuses() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), "10", null))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("with nothing installed the two-argument form still fails closed")
        void unwiredFailsClosed() {
            Authorization.useSubjectCoordinators(null);

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), "10"))
                    .satisfies(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("once installed, the two-argument form reads it, and hands the old one back")
        void installedDirectoryIsUsed() {
            Authorization.SubjectCoordinators previous =
                    Authorization.useSubjectCoordinators(directory);

            assertThatCode(() -> Authorization.requireCoordinatorOf(
                    caller(3L, Role.COORDINATOR), "10")).doesNotThrowAnyException();
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireCoordinatorOf(
                            caller(3L, Role.COORDINATOR), "20"));
            assertThat(previous)
                    .as("returned so a test can put back what it displaced")
                    .isSameAs(Authorization.SubjectCoordinators.UNWIRED);
        }

        @Test
        @DisplayName("the unwired default is the answer 'no', for every question")
        void unwiredDefaultSaysNo() {
            assertThat(Authorization.SubjectCoordinators.UNWIRED.coordinates(3L, "10")).isFalse();
        }
    }

    @Nested
    @DisplayName("AuthorizationException")
    class Failure {

        @Test
        @DisplayName("the two factories set the two codes")
        void factoriesSetCodes() {
            assertThat(AuthorizationException.unauthorized("a").errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            assertThat(AuthorizationException.forbidden("b").errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            assertThat(AuthorizationException.forbidden("b")).hasMessage("b");
        }

        @Test
        @DisplayName("it can carry any code, but never a null one")
        void codeIsMandatory() {
            assertThat(new AuthorizationException(ErrorCode.CONFLICT, "c").errorCode())
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThatNullPointerException()
                    .isThrownBy(() -> new AuthorizationException(null, "c"));
        }
    }
}
