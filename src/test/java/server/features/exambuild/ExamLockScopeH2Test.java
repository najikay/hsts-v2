package server.features.exambuild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import server.db.RepositoryTestBase;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;
import server.db.repos.ExamBuildRepository;
import server.features.locks.EntityScopes;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production {@code exam-version} entity scope, against a real database (E18.5).
 *
 * <p><b>Why this test exists.</b> The predicate is three lines, and all three would stay green
 * under the two mistakes that matter. An {@code orElse(true)} on the not-found branch turns the
 * scope into the existence oracle it was written to prevent, and comparing the wrong id - the
 * exam's rather than the version's - filters on a number that is right often enough in a small
 * fixture to look correct. Neither shows up in a unit test of the repository, which answers what
 * it was asked, or of {@code EntityScopes}, which only knows a predicate was consulted. That is
 * the P-8 seam: two green suites either side of it and nothing crossing.
 *
 * <p>So this calls {@link ExamLockScope#of} itself rather than rebuilding the lambda. A copy
 * would agree with the test and prove nothing about the server (P-6).
 *
 * <p>Fixture is {@link RepositoryTestBase}'s: {@code dana} and {@code rina} both teach, so a
 * refusal here is about authorship rather than about one of them lacking the course.
 */
class ExamLockScopeH2Test extends RepositoryTestBase {

    private TestDatabase h2;

    @Override
    protected TestDatabase openDatabase() {
        h2 = TestDatabases.h2();
        return h2;
    }

    private EntityScopes.EntityScope scope() {
        return ExamLockScope.of(factory());
    }

    /**
     * @param authorId who writes it
     * @return the id of a fresh DRAFT version of a new exam authored by {@code authorId}
     */
    private long versionAuthoredBy(long authorId) {
        return inTx(session -> {
            ExamBuildRepository repository = new ExamBuildRepository();
            // A decoy exam first, and it is not decoration. One exam plus one version put both
            // sequences on the same number - theExamIdIsNotTheVersionId first ran with examId 3
            // and versionId 3 - and a predicate that compared the exam id would have passed every
            // assertion in this class. Skewing the sequences here means no test in the file can
            // be fooled by the two ids coinciding.
            repository.insertExam(session, COURSE_CALCULUS, authorId);
            long examId = repository.insertExam(session, COURSE_ALGEBRA, authorId);
            ExamVersion version = new ExamVersion(examId, 1, "Midterm: Algebra", 60,
                    "Read every question.", "Marking notes.",
                    ExamVersionStatus.DRAFT, Instant.now());
            session.persist(version);
            session.flush();
            return version.getId();
        });
    }

    @Test
    @DisplayName("the author reaches her own exam version")
    void authorReachesHerOwn() {
        long versionId = versionAuthoredBy(danaId);

        assertThat(scope().reaches(danaId, versionId)).isTrue();
    }

    @Test
    @DisplayName("another teacher does not reach it, though she teaches too")
    void anotherTeacherDoesNotReach() {
        long versionId = versionAuthoredBy(danaId);

        // Rina is a teacher and a coordinator, so this is authorship refusing her rather than a
        // role check. It is the case the whole scope exists for: without it LOCK_ACQUIRE would
        // answer her with Dana's name, which every other E7 verb refuses to do.
        assertThat(scope().reaches(rinaId, versionId)).isFalse();
    }

    @Test
    @DisplayName("an id no exam version has is out of scope, not an error")
    void unknownIdIsOutOfScope() {
        // Fails closed twice over: a probing caller learns nothing, and one hostile id inside a
        // snapshot serving many rows cannot take the whole answer down with an exception.
        assertThat(scope().reaches(danaId, 987_654L)).isFalse();
    }

    @Test
    @DisplayName("the exam's own id does not reach its version")
    void theExamIdIsNotTheVersionId() {
        long versionId = versionAuthoredBy(danaId);
        long examId = inTx(session -> new ExamBuildRepository()
                .findCompositionHeader(session, versionId)
                .orElseThrow()
                .examId());

        // The plant this test was written for. Both ids are small, sequential and authored by the
        // same person, so a predicate that compared the wrong one is only caught where they
        // differ - see the decoy in versionAuthoredBy, which is what makes them differ.
        assertThat(examId)
                .as("the fixture must keep the two sequences apart or this proves nothing")
                .isNotEqualTo(versionId);
        assertThat(scope().reaches(danaId, examId))
                .as("an exam id is not an exam version id, whoever wrote the exam")
                .isFalse();
    }

    @Test
    @DisplayName("a student reaches nothing, even on a version that exists")
    void studentReachesNothing() {
        long versionId = versionAuthoredBy(danaId);

        // Belt to LOCK_ACQUIRE's braces: the verb already refuses non-teaching roles. Asserted
        // here so the scope does not quietly become the only thing holding that line if the
        // role check is ever relaxed for coordinators.
        assertThat(scope().reaches(mayaId, versionId)).isFalse();
    }
}
