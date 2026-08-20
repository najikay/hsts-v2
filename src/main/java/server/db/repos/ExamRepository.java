package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Exam;
import server.db.entities.ExamVersion;
import server.db.entities.ExamVersionQuestion;
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
    /**
     * The questions an exam version pinned, in presentation order.
     *
     * <p>Returns the rows of {@code exam_version_questions} — the versions recorded when the exam
     * was built, never the latest version of each question. That distinction is the whole point:
     * grading an attempt against a question edited after release would silently rewrite past
     * grades (PRD §6, C-2).
     *
     * <p>Carries points and question-version ids only, no answer key, so it needs no sanctioned
     * suffix; the key comes separately from
     * {@code QuestionRepository#findVersionsForGrading}.
     *
     * <p>Consumer: E12.1's auto-grading.
     *
     * @param session       the current session
     * @param examVersionId the exam version
     * @return the pinned questions ordered by {@code ordinal}
     */
    public List<ExamVersionQuestion> findPinnedQuestions(Session session, long examVersionId) {
        return session.createQuery("""
                        from ExamVersionQuestion
                        where id.examVersionId = :examVersionId
                        order by ordinal
                        """, ExamVersionQuestion.class)
                .setParameter("examVersionId", examVersionId)
                .getResultList();
    }

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
