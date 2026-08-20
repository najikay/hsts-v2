package server.features.results;

import common.dto.grading.GradeState;
import common.dto.grading.MyGrades;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.db.entities.Grade;
import server.db.entities.User;
import server.db.entities.UserRole;
import server.db.repos.GradeRepository;
import server.db.repos.UserRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResultsService} — E13.1, marked defence-critical because a student reaching another
 * student's grade is the failure that ends a demo.
 *
 * <p>The tests that carry the weight are the negative ones. They assert two things that are easy
 * to get subtly wrong: that the student id used is the one the service was given rather than
 * anything from a payload, and that a grade belonging to somebody else is reported the same way
 * as a grade that does not exist — because two different answers make a membership oracle.
 *
 * <p>Fixtures use the seeded execution 4821 rows: {@code maya.levi} (71, untouched) and
 * {@code yael.azulay} (auto 51, overridden to 55 with a justification that must never leave the
 * server).
 */
@ExtendWith(MockitoExtension.class)
class ResultsServiceTest {

    private static final long MAYA = 11;
    private static final long YAEL = 13;
    private static final String MAYA_NAME = "מאיה לוי";

    @Mock
    private Session session;
    @Mock
    private GradeRepository grades;
    @Mock
    private UserRepository users;

    private ResultsService service;

    @BeforeEach
    void setUp() {
        service = new ResultsService(grades, users);
    }

    private void stubStudent(long id, String name) {
        User user = new User("maya.levi", "$2a$12$hash", name, UserRole.STUDENT, "374301851");
        setField(user, "id", id);
        lenient().when(users.findById(session, id)).thenReturn(Optional.of(user));
    }

    private static Grade approved(long id, int autoScore, Integer finalScore, String reason) {
        Grade grade = new Grade(id * 10, autoScore);
        if (finalScore != null) {
            grade.override(finalScore, reason);
        }
        grade.approve(2L, Instant.parse("2026-08-06T09:00:00Z"));
        setField(grade, "id", id);
        return grade;
    }

    @Nested
    @DisplayName("a student's own grades")
    class OwnGrades {

        @Test
        @DisplayName("approved grades are returned as wire rows")
        void returnsApprovedGrades() {
            stubStudent(MAYA, MAYA_NAME);
            when(grades.findApprovedForStudent(session, MAYA))
                    .thenReturn(List.of(approved(1, 71, null, null)));

            MyGrades result = service.myGrades(session, MAYA);

            assertThat(result.grades()).hasSize(1);
            assertThat(result.grades().get(0).effectiveScore()).isEqualTo(71);
            assertThat(result.grades().get(0).studentName()).isEqualTo(MAYA_NAME);
            assertThat(result.grades().get(0).state()).isEqualTo(GradeState.APPROVED);
        }

        @Test
        @DisplayName("the query is scoped to the caller — the id is never taken from anywhere else")
        void scopedToTheCaller() {
            stubStudent(MAYA, MAYA_NAME);
            when(grades.findApprovedForStudent(session, MAYA)).thenReturn(List.of());

            service.myGrades(session, MAYA);

            // Exactly this student id reached the repository, and no unscoped read was used.
            verify(grades).findApprovedForStudent(session, MAYA);
            verify(grades, org.mockito.Mockito.never()).findAwaitingApproval(any(), anyLong());
        }

        @Test
        @DisplayName("H13.2 — a student who has sat nothing gets EMPTY, not null")
        void emptyWhenNothingApproved() {
            stubStudent(MAYA, MAYA_NAME);
            when(grades.findApprovedForStudent(session, MAYA)).thenReturn(List.of());

            MyGrades result = service.myGrades(session, MAYA);

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.grades()).isEmpty();
        }

        @Test
        @DisplayName("an overridden grade shows the adjusted score and the comment")
        void overriddenGradeShowsAdjustedScore() {
            stubStudent(YAEL, "יעל אזולאי");
            when(grades.findApprovedForStudent(session, YAEL))
                    .thenReturn(List.of(approved(2, 51, 55, "טעות סימן — ניקוד חלקי")));

            MyGrades result = service.myGrades(session, YAEL);

            assertThat(result.grades().get(0).autoScore()).isEqualTo(51);
            assertThat(result.grades().get(0).finalScore()).isEqualTo(55);
            assertThat(result.grades().get(0).effectiveScore()).isEqualTo(55);
        }

        @Test
        @DisplayName("the override justification never reaches the student wire")
        void justificationIsNeverWired() {
            stubStudent(YAEL, "יעל אזולאי");
            when(grades.findApprovedForStudent(session, YAEL))
                    .thenReturn(List.of(approved(2, 51, 55, "teacher-only audit text")));

            MyGrades result = service.myGrades(session, YAEL);

            // Two independent defences: the mapper passes null, and MyGrades strips it anyway.
            assertThat(result.grades().get(0).overrideReason()).isNull();
        }

        @Test
        @DisplayName("an authenticated caller with no user row is a server fault, not an empty list")
        void unknownCaller() {
            when(users.findById(session, 999L)).thenReturn(Optional.empty());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.myGrades(session, 999L))
                    .withMessageContaining("999");
        }
    }

    @Nested
    @DisplayName("looking up one grade — the checked-form gate")
    class SingleGrade {

        @Test
        @DisplayName("a student's own grade is found")
        void findsOwnGrade() {
            Grade own = approved(1, 71, null, null);
            when(grades.findForStudent(session, 1L, MAYA)).thenReturn(Optional.of(own));

            assertThat(service.findOwnGrade(session, 1L, MAYA)).contains(own);
        }

        @Test
        @DisplayName("⚑ another student's grade id comes back empty — the caller answers NOT_FOUND")
        void anotherStudentsGradeIsNotFound() {
            // The repository filters on student id, so YAEL's grade simply does not match MAYA.
            when(grades.findForStudent(session, 2L, MAYA)).thenReturn(Optional.empty());

            assertThat(service.findOwnGrade(session, 2L, MAYA)).isEmpty();
        }

        @Test
        @DisplayName("⚑ a grade that does not exist is indistinguishable from one that is not yours")
        void missingAndForbiddenLookIdentical() {
            when(grades.findForStudent(session, 2L, MAYA)).thenReturn(Optional.empty());
            when(grades.findForStudent(session, 9999L, MAYA)).thenReturn(Optional.empty());

            Optional<Grade> somebodyElses = service.findOwnGrade(session, 2L, MAYA);
            Optional<Grade> neverExisted = service.findOwnGrade(session, 9999L, MAYA);

            // Same answer, no way to tell the two apart — no membership oracle.
            assertThat(somebodyElses).isEqualTo(neverExisted).isEmpty();
        }

        @Test
        @DisplayName("the student id reaching the repository is the caller's, always")
        void lookupIsScoped() {
            when(grades.findForStudent(session, 5L, MAYA)).thenReturn(Optional.empty());

            service.findOwnGrade(session, 5L, MAYA);

            verify(grades).findForStudent(session, 5L, MAYA);
        }
    }

    // ===== helpers ========================================================
    // Ids are Hibernate-managed and the entities expose no setters for them, so fixtures set
    // them reflectively rather than production code growing test-only mutators.

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + name, e);
        }
    }
}
