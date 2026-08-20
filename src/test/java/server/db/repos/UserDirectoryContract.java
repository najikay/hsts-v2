package server.db.repos;

import common.dto.auth.CourseRef;
import common.dto.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.entities.Enrollment;
import server.db.projections.CourseSummary;
import server.features.auth.UserRecord;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code UserRepository}, {@code CourseRepository} and the adapter that turns them into the
 * login result (E2.11).
 *
 * <p>Tested together because the interesting behaviour is in the mapping, not in any one
 * query: the wire role is derived rather than stored, and the course list is a union.
 */
abstract class UserDirectoryContract extends RepositoryTestBase {

    private final UserRepository users = new UserRepository();
    private final CourseRepository courses = new CourseRepository();

    private RepositoryUserDirectory directory() {
        return new RepositoryUserDirectory(factory());
    }

    @Test
    @DisplayName("a known user comes back with the hash the server needs to verify")
    void findsAKnownUser() {
        UserRecord found = directory().findByUsername("dana.cohen").orElseThrow();

        assertThat(found.id()).isEqualTo(danaId);
        assertThat(found.username()).isEqualTo("dana.cohen");
        assertThat(found.passwordHash()).isEqualTo(FAKE_HASH);
        assertThat(found.displayName()).isEqualTo("דנה כהן");
    }

    @Test
    @DisplayName("usernames are matched regardless of case or surrounding spaces")
    void usernameIsNormalised() {
        // The login throttle keys on the normalised name. A case-sensitive directory would
        // let DANA.COHEN walk straight past a lockout on dana.cohen.
        assertThat(directory().findByUsername("DANA.COHEN")).isPresent();
        assertThat(directory().findByUsername("  Dana.Cohen  ")).isPresent();
    }

    @Test
    @DisplayName("an unknown or blank username is empty rather than an error")
    void unknownUserIsEmpty() {
        // "no such user" and "wrong password" must be indistinguishable to the caller (F1.1).
        assertThat(directory().findByUsername("nobody.here")).isEmpty();
        assertThat(directory().findByUsername("")).isEmpty();
        assertThat(directory().findByUsername(null)).isEmpty();
    }

    @Test
    @DisplayName("a teacher who coordinates a subject logs in as COORDINATOR")
    void coordinatorRoleIsDerived() {
        // rina is stored as TEACHER; the coordinators row is what makes her a coordinator.
        // The stored role has no COORDINATOR value at all (§5), so this can never drift.
        assertThat(directory().findByUsername("rina.barak").orElseThrow().role())
                .isEqualTo(Role.COORDINATOR);
    }

    @Test
    @DisplayName("a teacher who coordinates nothing stays a TEACHER")
    void plainTeacherKeepsTeacherRole() {
        assertThat(directory().findByUsername("dana.cohen").orElseThrow().role())
                .isEqualTo(Role.TEACHER);
    }

    @Test
    @DisplayName("students and the principal map straight through")
    void otherRolesMapDirectly() {
        assertThat(directory().findByUsername("maya.levi").orElseThrow().role()).isEqualTo(Role.STUDENT);
        assertThat(directory().findByUsername("principal.avia").orElseThrow().role())
                .isEqualTo(Role.PRINCIPAL);
    }

    @Test
    @DisplayName("a teacher's courses include the one she is enrolled in, not only those she teaches")
    void coursesAreTaughtAndEnrolled() {
        // dana teaches Algebra and Calculus and is enrolled in Databases. Reading UserRecord's
        // javadoc as "taught OR enrolled, by role" would return two of the three.
        UserRecord dana = directory().findByUsername("dana.cohen").orElseThrow();

        assertThat(dana.courses()).extracting(CourseRef::code)
                .containsExactlyInAnyOrder(COURSE_ALGEBRA, COURSE_CALCULUS, COURSE_DATABASES);
    }

    @Test
    @DisplayName("a course both taught and enrolled in appears once")
    void unionDoesNotDuplicate() {
        runInTx(session -> session.persist(new Enrollment(COURSE_ALGEBRA, danaId)));

        UserRecord dana = directory().findByUsername("dana.cohen").orElseThrow();

        assertThat(dana.courses()).extracting(CourseRef::code)
                .containsExactlyInAnyOrder(COURSE_ALGEBRA, COURSE_CALCULUS, COURSE_DATABASES);
    }

    @Test
    @DisplayName("a student's courses are the ones enrolled in, with their names")
    void studentCourses() {
        UserRecord maya = directory().findByUsername("maya.levi").orElseThrow();

        assertThat(maya.courses()).extracting(CourseRef::code)
                .containsExactlyInAnyOrder(COURSE_ALGEBRA, COURSE_JAVA);
        assertThat(maya.courses()).extracting(CourseRef::name).contains("אלגברה");
    }

    @Test
    @DisplayName("the principal is attached to no course")
    void principalHasNoCourses() {
        // School-wide read access is not modelled as membership (S-7).
        assertThat(directory().findByUsername("principal.avia").orElseThrow().courses()).isEmpty();
    }

    @Test
    @DisplayName("findById returns the display name the lock banner has to show (E18)")
    void findByIdReturnsTheDisplayName() {
        // UserDirectory.findById is a default method that answers empty. Inheriting it
        // compiles and passes every login test, and then every E18 lock banner in the
        // product reads "Another user" — so the override is what this pins.
        UserRecord dana = directory().findById(danaId).orElseThrow();

        assertThat(dana.displayName()).isEqualTo("דנה כהן");
        assertThat(dana.id()).isEqualTo(danaId);
        assertThat(dana.username()).isEqualTo("dana.cohen");
    }

    @Test
    @DisplayName("an unknown id is empty rather than an error or a placeholder")
    void findByIdOfAnUnknownUserIsEmpty() {
        // A user id that no longer exists (a deleted account) must degrade to
        // LockHolder.UNKNOWN_NAME one layer up, not fail a lock acquisition here.
        assertThat(directory().findById(-1L)).isEmpty();
        assertThat(directory().findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("a user found by id maps exactly as the same user found by name")
    void findByIdAgreesWithFindByUsername() {
        // Both go through one mapper, so the derived COORDINATOR role and the union of
        // taught and enrolled courses cannot depend on which way in the caller took.
        UserRecord byName = directory().findByUsername("rina.barak").orElseThrow();
        UserRecord byId = directory().findById(rinaId).orElseThrow();

        assertThat(byId).isEqualTo(byName);
        assertThat(byId.role()).isEqualTo(Role.COORDINATOR);
    }

    @Test
    @DisplayName("coordinated subjects are listed, not just counted")
    void coordinatedSubjectsAreReturned() {
        List<String> rinasSubjects = inTx(session -> users.findCoordinatedSubjects(session, rinaId));
        List<String> danasSubjects = inTx(session -> users.findCoordinatedSubjects(session, danaId));

        assertThat(rinasSubjects).containsExactly(SUBJECT_MATH);
        assertThat(danasSubjects).isEmpty();
    }

    @Test
    @DisplayName("the repository finds by username directly too")
    void repositoryFindByUsername() {
        Optional<String> name = inTx(session ->
                users.findByUsername(session, "maya.levi").map(user -> user.getFullName()));

        assertThat(name).contains("מאיה לוי");
    }

    @Test
    @DisplayName("course lookup for a user with nothing attached is empty, not null")
    void noCoursesIsEmptyList() {
        List<CourseSummary> none = inTx(session -> courses.findForUser(session, principalId));

        assertThat(none).isEmpty();
    }
}
