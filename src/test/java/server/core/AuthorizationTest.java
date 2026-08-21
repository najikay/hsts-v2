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
    @DisplayName("requireEnrolled, the last TODO(E2) stub")
    class CourseScoped {

        // requireTeachesCourse left this class in E6 and requireCoordinatorOf in E8, each when
        // its epic needed real data. requireEnrolled is E10's and stays here: a guard nothing
        // calls yet, implemented anyway, is an untested guard shipped for somebody else's epic.

        @Test
        @DisplayName("it fails closed instead of permitting, and says why")
        void stubRefuses() {
            CallerContext teacher = caller(1L, Role.TEACHER);

            assertThatThrownBy(() -> Authorization.requireEnrolled(teacher, "11"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("TODO(E2)")
                    .hasMessageContaining("requireEnrolled");
        }

        @Test
        @DisplayName("it still checks the session first")
        void stubChecksTheSessionFirst() {
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireEnrolled(anonymous(), "11"));
        }
    }

    /**
     * The course-scoped guard E6 implemented, and the bank's half of S-5.
     *
     * <p>Two things have to hold and each is asserted failing on its own: the caller is staff,
     * and the directory says she teaches <em>this</em> course. The second without the first is
     * what would let a student with a stray {@code course_teachers} row write questions; the
     * first without the second is what would let one teacher write into another's bank.
     *
     * <p>What is deliberately <b>not</b> tested here, because it is deliberately not this
     * guard's job: a coordinator reaching a course she does not teach. Her bank scope is her
     * coordinated subject (the lead's ruling of 2026-08-21), and that composition lives in
     * {@code QuestionService}. A test asserting it here would be asserting that this guard means
     * something wider than its name, which is exactly what it was designed not to mean.
     */
    @Nested
    @DisplayName("requireTeachesCourse (implemented in E6)")
    class TeachesCourse {

        /** Dana teaches Algebra (11) and Calculus (12), and nothing else. */
        private final Authorization.CourseTeachers directory =
                (teacherId, courseCode) -> teacherId == 1L
                        && ("11".equals(courseCode) || "12".equals(courseCode));

        @AfterEach
        void uninstall() {
            Authorization.useCourseTeachers(null);
        }

        @Test
        @DisplayName("passes for a teacher of the course")
        void passesForHerOwnCourse() {
            assertThatCode(() -> Authorization.requireTeachesCourse(
                    caller(1L, Role.TEACHER), "11", directory)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a teacher on a course she does not teach")
        void refusesAnotherTeachersCourse() {
            // S-5's whole point. She is a teacher, the course exists, and it is not hers.
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(1L, Role.TEACHER), "21", directory))
                    .withMessageContaining("21");
        }

        @Test
        @DisplayName("refuses a different teacher on a course that is not hers")
        void theDirectoryIsAskedAboutTheCaller() {
            // The id must reach the directory. A guard that looked the course up without the
            // caller would pass every teacher for every taught course.
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(2L, Role.TEACHER), "11", directory));
        }

        @Test
        @DisplayName("a coordinator who also teaches passes on her own course")
        void coordinatorWhoTeachesIsStaff() {
            // michal.sharon's shape: COORDINATOR by session role, and a course_teachers row.
            // The role check must not be TEACHER-only or the dual-hat case breaks.
            Authorization.CourseTeachers hers =
                    (teacherId, courseCode) -> teacherId == 4L && "22".equals(courseCode);
            assertThatCode(() -> Authorization.requireTeachesCourse(
                    caller(4L, Role.COORDINATOR), "22", hers)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a PRINCIPAL: F9.3 allows her literally zero mutating verbs")
        void refusesThePrincipal() {
            // The mutation the author did not plant, found by the audit. This guard fronts the
            // three mutating bank verbs, and PRD F9.3 is absolute about the principal having
            // none of them. Adding Role.PRINCIPAL to the requireRole list would break F9.3 and,
            // before this test, would have left the whole class green.
            Authorization.CourseTeachers lying = (teacherId, courseCode) -> true;
            assertThatThrownBy(() -> Authorization.requireTeachesCourse(
                    caller(7L, Role.PRINCIPAL), "11", lying))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(thrown -> assertThat(((AuthorizationException) thrown).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("the role check runs before the directory is ever asked")
        void roleIsCheckedBeforeTheDirectory() {
            // Ordering, pinned rather than commented. A directory consulted first would open a
            // query on every probe by an unauthorized caller, and the previous version of this
            // suite only asserted that *something* threw.
            Authorization.CourseTeachers mustNotBeCalled = (teacherId, courseCode) -> {
                throw new AssertionError("the directory was consulted before the role check");
            };
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(9L, Role.STUDENT), "11", mustNotBeCalled));
        }

        @Test
        @DisplayName("refuses a student outright, whatever the directory says")
        void refusesAStudentEvenWithARow() {
            // The role check runs first and independently. A directory that answered yes for a
            // student must not be enough, because the two sources can disagree and the stored
            // role is the one the session was built from.
            Authorization.CourseTeachers lying = (teacherId, courseCode) -> true;
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(9L, Role.STUDENT), "11", lying));
        }

        @Test
        @DisplayName("checks the session before anything else: anonymous is UNAUTHORIZED")
        void anonymousIsUnauthorizedNotForbidden() {
            // The CODE, not just the type. Getting these two backwards is what produces a client
            // logout loop, which is why this file's other guards assert it and why the audit
            // called its absence here an unjustified divergence from the reference shape.
            assertThatThrownBy(() -> Authorization.requireTeachesCourse(
                    anonymous(), "11", directory))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(thrown -> assertThat(((AuthorizationException) thrown).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("a missing course is a refusal, never a wildcard")
        void blankCourseRefuses() {
            for (String nothing : new String[] {null, "", "  "}) {
                // The message assertion is what gives this teeth. Without it the whole blank
                // branch could be deleted and the test would still pass, because a blank code
                // also fails the directory lookup and throws the other refusal.
                assertThatThrownBy(() -> Authorization.requireTeachesCourse(
                        caller(1L, Role.TEACHER), nothing, directory))
                        .as("course %s", nothing)
                        .isInstanceOf(AuthorizationException.class)
                        .hasMessageContaining("not linked to a course");
            }
        }

        @Test
        @DisplayName("a null directory refuses rather than throwing or permitting")
        void nullDirectoryRefuses() {
            assertThatThrownBy(() -> Authorization.requireTeachesCourse(
                    caller(1L, Role.TEACHER), "11", (Authorization.CourseTeachers) null))
                    .isInstanceOf(AuthorizationException.class)
                    .satisfies(thrown -> assertThat(((AuthorizationException) thrown).errorCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("the default directory is UNWIRED, pinned at the field and not by luck")
        void defaultIsUnwired() {
            // unwiredRefuses only proves the current value refuses, and @AfterEach happens to
            // reset it. This pins the initializer itself: change the field's default to a
            // permissive lambda and this fails.
            Authorization.CourseTeachers previous =
                    Authorization.useCourseTeachers((teacherId, courseCode) -> true);
            assertThat(previous).isSameAs(Authorization.CourseTeachers.UNWIRED);
            assertThat(Authorization.CourseTeachers.UNWIRED.teaches(1L, "11")).isFalse();
        }

        @Test
        @DisplayName("teachesCourse answers instead of throwing, for the NOT_FOUND paths")
        void booleanSiblingAnswers() {
            // Why it exists: on UPDATE and DELETE the service resolves the course from the
            // stored question, so a FORBIDDEN naming that course is an existence oracle, which
            // BANK_WIRE_CONTRACT §2 forbids. The service needs a way to ask without throwing.
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), "11", directory))
                    .isTrue();
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), "21", directory))
                    .isFalse();
        }

        @Test
        @DisplayName("teachesCourse fails closed on every missing input")
        void booleanSiblingFailsClosed() {
            assertThat(Authorization.teachesCourse(anonymous(), "11", directory)).isFalse();
            assertThat(Authorization.teachesCourse(null, "11", directory)).isFalse();
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), null, directory))
                    .isFalse();
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), "  ", directory))
                    .isFalse();
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), "11", null))
                    .isFalse();
        }

        @Test
        @DisplayName("a padded course code is stripped, so SQL and Java cannot disagree")
        void paddedCourseCodeIsStripped() {
            // courses.code2 is CHAR(2) under a PAD SPACE collation, so "11 " matches the row in
            // SQL while failing Java equality against the reachable-set list the browse filters
            // on. Two authorization answers for one input is the P-6 disease; stripping at the
            // boundary is the cheap end of it.
            assertThat(Authorization.teachesCourse(caller(1L, Role.TEACHER), " 11 ", directory))
                    .isTrue();
            assertThatCode(() -> Authorization.requireTeachesCourse(
                    caller(1L, Role.TEACHER), " 11 ", directory)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UNWIRED refuses everyone, which is what an unassembled server must do")
        void unwiredRefuses() {
            // The two-argument form with nothing installed. This is the state a server is in
            // between class-load and assembly, and the failure mode to avoid is a guard that
            // waves callers through until somebody remembers to wire it.
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(1L, Role.TEACHER), "11"));
        }

        @Test
        @DisplayName("the installed directory is what the two-argument form reads")
        void installedDirectoryIsUsed() {
            Authorization.useCourseTeachers(directory);

            assertThatCode(() -> Authorization.requireTeachesCourse(
                    caller(1L, Role.TEACHER), "12")).doesNotThrowAnyException();
            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(1L, Role.TEACHER), "21"));
        }

        @Test
        @DisplayName("installing returns the previous directory, so a test can put it back")
        void installReturnsThePrevious() {
            Authorization.CourseTeachers first = (teacherId, courseCode) -> true;
            Authorization.CourseTeachers previous = Authorization.useCourseTeachers(first);
            assertThat(Authorization.useCourseTeachers(previous)).isSameAs(first);
        }

        @Test
        @DisplayName("installing null goes back to refusing, not to the last real directory")
        void installingNullFailsClosed() {
            Authorization.useCourseTeachers(directory);
            Authorization.useCourseTeachers(null);

            assertThatExceptionOfType(AuthorizationException.class)
                    .isThrownBy(() -> Authorization.requireTeachesCourse(
                            caller(1L, Role.TEACHER), "11"));
        }

        @Test
        @DisplayName("the refusal says which course and what to do next (PRD §4.1)")
        void refusalIsUseful() {
            assertThatThrownBy(() -> Authorization.requireTeachesCourse(
                    caller(1L, Role.TEACHER), "21", directory))
                    .hasMessageContaining("21")
                    .hasMessageContaining("Ask the coordinator")
                    .hasMessageNotContaining("—");
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
