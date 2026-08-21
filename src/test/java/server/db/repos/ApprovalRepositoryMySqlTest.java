package server.db.repos;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import server.db.TestDatabase;
import server.db.TestDatabases;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The approval repository contract on the real Flyway schema.
 *
 * <p>One thing only exists here, and it is the one the whole feature's conflict handling
 * rests on: two transactions really racing to decide the same version, against a database
 * that really enforces {@code lock_version}. H2 would let the assertion be written and would
 * not be evidence — the race that matters is between two connections, and the second one has
 * to lose in the engine the demo runs on.
 */
@EnabledIf("server.db.MySqlAvailability#isReachable")
class ApprovalRepositoryMySqlTest extends ApprovalRepositoryContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.mySql();
    }

    @Test
    @DisplayName("two coordinators deciding the same version: the second one loses, loudly ⚑")
    void theSecondDecisionLoses() {
        long versionId = pendingVersion(COURSE_ALGEBRA, 1, danaId);

        // Both read the row at lock_version 0, as two open screens would.
        ExamVersion first = versionEntity(versionId).orElseThrow();
        ExamVersion second = versionEntity(versionId).orElseThrow();
        assertThat(first.getLockVersion()).isZero();
        assertThat(second.getLockVersion()).isZero();

        runInTx(session -> {
            ExamVersion managed = session.get(ExamVersion.class, versionId);
            managed.approve();
        });

        // The loser writes against the version it read, and the engine refuses.
        assertThatThrownBy(() -> runInTx(session -> {
            ExamVersion stale = session.get(ExamVersion.class, versionId);
            // Force the write to carry the old version number, which is what a second
            // connection holding a detached copy would send.
            session.detach(stale);
            session.merge(rejectedCopy(versionId));
        })).satisfies(failure -> assertThat(hasStaleCause(failure))
                .as("the service turns exactly this into CONFLICT rather than INTERNAL")
                .isTrue());

        assertThat(versionEntity(versionId).orElseThrow().getStatus())
                .as("the first decision stands")
                .isEqualTo(ExamVersionStatus.APPROVED);
    }

    /**
     * @return a detached {@link ExamVersion} carrying {@code lock_version 0}, which is what a
     *         coordinator's second window would send after somebody else had written the row
     */
    private ExamVersion rejectedCopy(long versionId) {
        return inTx(session -> {
            ExamVersion row = session.get(ExamVersion.class, versionId);
            session.detach(row);
            ExamVersion copy = new ExamVersion(row.getExamId(), row.getVersionNo(), row.getName(),
                    row.getDurationMinutes(), row.getStudentText(), row.getTeacherText(),
                    ExamVersionStatus.REJECTED, row.getCreatedAt());
            setId(copy, versionId);
            return copy;
        });
    }

    /** Sets the generated id on a detached copy; there is no setter, and there should not be. */
    private static void setId(ExamVersion version, long id) {
        try {
            java.lang.reflect.Field field = ExamVersion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(version, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ExamVersion.id moved; this test needs updating", e);
        }
    }

    private static boolean hasStaleCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StaleStateException || cause instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }
}
