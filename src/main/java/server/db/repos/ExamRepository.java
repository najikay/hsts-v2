package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionStatus;

import java.util.List;
import java.util.Optional;

/** Reads over {@code exams} and {@code exam_versions} (E2.11). */
public final class ExamRepository {

    /**
     * Looks an exam up by the 6-digit id people type (S-10).
     *
     * <p>Consumers: E7 exam builder; the E2.15 seed loader's idempotency check.
     *
     * @param session   the current session
     * @param displayId the 6-character display id
     * @return the exam, or empty
     */
    public Optional<Exam> findByDisplayId(Session session, String displayId) {
        return session.createQuery("from Exam where displayId = :displayId", Exam.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    /**
     * One version of an exam by its number.
     *
     * <p>Consumers: E7 composition and E8 approval, which both act on a named version rather
     * than "the latest" — an approval race that landed on the wrong version is exactly what
     * {@code lock_version} on {@code exam_versions} exists to stop.
     *
     * @param session   the current session
     * @param examId    the exam's internal id
     * @param versionNo the 1-based version number
     * @return the version, or empty
     */
    public Optional<ExamVersion> findVersion(Session session, long examId, int versionNo) {
        return session.createQuery("""
                        from ExamVersion where examId = :examId and versionNo = :versionNo
                        """, ExamVersion.class)
                .setParameter("examId", examId)
                .setParameter("versionNo", versionNo)
                .uniqueResultOptional();
    }

    /**
     * Versions of an exam awaiting a coordinator's decision.
     *
     * <p>Consumer: E8's approval queue.
     *
     * @param session the current session
     * @param examId  the exam's internal id
     * @return pending versions, oldest first
     */
    public List<ExamVersion> findPendingVersions(Session session, long examId) {
        return session.createQuery("""
                        from ExamVersion
                        where examId = :examId and status = :status
                        order by versionNo
                        """, ExamVersion.class)
                .setParameter("examId", examId)
                .setParameter("status", ExamVersionStatus.PENDING)
                .getResultList();
    }
}
