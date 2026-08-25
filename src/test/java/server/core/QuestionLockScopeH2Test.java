package server.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.Question;
import server.features.bank.QuestionLockKey;
import server.features.locks.EntityScopes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production {@code question} entity scope, against a real database (E18.9 — PR20 §5.3).
 *
 * <p><b>Why this test exists at all.</b> Every part of the predicate is already covered on its
 * own: {@code QuestionLockKey.displayIdOf} round-trips, {@code reachableCourseCodes} unions
 * taught with coordinated, {@code findActiveByDisplayId} skips soft-deleted rows. The
 * composition is the interesting bit and none of those cover it — a truncating inverse, a
 * course code compared unstripped, or an {@code orElse(true)} on the not-found branch would
 * leave every one of them green and the filter wrong. That is the P-8 shape: two green suites
 * either side of one seam, and neither crosses it.
 *
 * <p>So this calls {@link HSTSServer#questionLockScope} itself rather than rebuilding the
 * lambda, which would test a copy and prove nothing about the server (P-6).
 *
 * <p>The fixture is {@link RepositoryTestBase}'s: {@code dana} teaches Algebra (11) and
 * Calculus (12) and coordinates nothing; {@code rina} teaches Calculus and coordinates
 * Mathematics, which contains both; {@code maya} is a student; the principal holds no rows in
 * either table. Java (21) and Databases (22) belong to Computer Science, which neither teacher
 * reaches — those are the out-of-scope courses.
 */
class QuestionLockScopeH2Test extends RepositoryTestBase {

    private TestDatabase h2;

    @Override
    protected TestDatabase openDatabase() {
        h2 = TestDatabases.h2();
        return h2;
    }

    private EntityScopes.EntityScope scope() {
        return HSTSServer.questionLockScope(factory());
    }

    private long lockIdOf(String courseCode, short serial) {
        String displayId = courseCode + String.format("%03d", serial);
        runInTx(session -> session.persist(new Question(courseCode, serial, displayId)));
        return QuestionLockKey.of(displayId).entityId();
    }

    @Test
    @DisplayName("a teacher reaches a question in a course she teaches")
    void herOwnCourseIsReachable() {
        long algebra = lockIdOf(COURSE_ALGEBRA, (short) 1);

        assertThat(scope().reaches(danaId, algebra)).isTrue();
    }

    @Test
    @DisplayName("a teacher does NOT reach a question in a course she does not teach")
    void anotherCourseIsNotReachable() {
        // The defect in one line. Before the filter, a snapshot naming this question's holder
        // told Dana it exists and who is editing it - while QUESTION_GET answers her NOT_FOUND.
        long java = lockIdOf(COURSE_JAVA, (short) 1);

        assertThat(scope().reaches(danaId, java)).isFalse();
    }

    @Test
    @DisplayName("a coordinator reaches her subject's courses, including ones she does not teach")
    void coordinationWidensTheScope() {
        // rina teaches Calculus only, but coordinates Mathematics, which owns Algebra too.
        // A scope built from course_teachers alone would answer false here and blank the chip
        // for one of the five starred demo accounts.
        long algebra = lockIdOf(COURSE_ALGEBRA, (short) 1);
        long databases = lockIdOf(COURSE_DATABASES, (short) 1);

        assertThat(scope().reaches(rinaId, algebra))
                .as("reached by coordination, not by teaching")
                .isTrue();
        assertThat(scope().reaches(rinaId, databases))
                .as("Computer Science is not her subject")
                .isFalse();
    }

    @Test
    @DisplayName("a leading-zero display id resolves, which is what the padded inverse is for")
    void leadingZeroDisplayIdsResolve() {
        // Course "01" does not exist in the fixture, so this proves the resolution rather than
        // the reachability: displayIdOf must hand the bank "01003" and not "1003". With a
        // truncating inverse the lookup misses, the answer is false, and every lock on every
        // zero-leading course is hidden - silently, because absence is the normal answer.
        long padded = QuestionLockKey.of("01003").entityId();
        assertThat(padded).isEqualTo(1003L);

        runInTx(session -> session.persist(new Question(COURSE_ALGEBRA, (short) 3, "01003")));

        assertThat(scope().reaches(danaId, padded))
                .as("the question is Dana's Algebra row, found only if the id was padded back")
                .isTrue();
    }

    @Test
    @DisplayName("an id matching no question is out of scope, not an exception and not a pass")
    void unknownIdsAreOutOfScope() {
        assertThat(scope().reaches(danaId, 11_999L)).isFalse();
    }

    @Test
    @DisplayName("an id no display id could hold is refused quietly, not thrown")
    void impossibleIdsDoNotTakeTheAnswerDown() {
        // Reachable input: the snapshot payload carries raw longs. A throw here would travel up
        // through EditLockService.snapshot and take down an answer serving forty other rows.
        assertThat(scope().reaches(danaId, 1_000_000L)).isFalse();
        assertThat(scope().reaches(danaId, -5L)).isFalse();
    }

    @Test
    @DisplayName("a soft-deleted question is out of scope, like every other bank read")
    void softDeletedIsOutOfScope() {
        long algebra = lockIdOf(COURSE_ALGEBRA, (short) 1);
        runInTx(session -> session.createMutationQuery(
                        "update Question q set q.deletedAt = :now where q.displayId = :id")
                .setParameter("now", java.time.Instant.parse("2026-08-25T09:00:00Z"))
                .setParameter("id", "11001")
                .executeUpdate());

        assertThat(scope().reaches(danaId, algebra)).isFalse();
    }

    @Test
    @DisplayName("someone with no teaching and no coordination rows reaches nothing")
    void noRowsReachesNothing() {
        // The principal and any student. She never gets here in production - both list verbs
        // run requireRole(TEACHER, COORDINATOR) first, which is why the predicate carries no
        // reachesEveryCourse branch - and this records what it would answer if she did.
        long algebra = lockIdOf(COURSE_ALGEBRA, (short) 1);

        assertThat(scope().reaches(principalId, algebra)).isFalse();
        assertThat(scope().reaches(mayaId, algebra)).isFalse();
    }
}
