package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.TakeExamQuestion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Reads over the question bank (E2.11).
 *
 * <h2>The two kinds of read, and why they are named differently</h2>
 *
 * <p>{@link #findForTakeExam} returns {@link TakeExamQuestion}, which has no correctness
 * field. Everything else here returns {@link QuestionVersion}, which does.
 *
 * <p>That is the whole security boundary of E2.12, so it is in the method names rather than
 * in a comment someone has to find: anything ending {@code ForAuthoring} is <b>teacher-only
 * and must never be reachable from a student request</b>. The entity mapping cannot help
 * here — it already forbids navigating <em>to</em> a question version, but a repository that
 * hands one back directly walks straight past that.
 */
public final class QuestionRepository {

    /**
     * The questions of one exam version, as a student may see them.
     *
     * <p>A constructor expression, so {@code correct_answer} is not in the SELECT list at
     * all: it never leaves the database, which is a stronger statement than "the DTO drops
     * it". Consumer: E10 take-exam.
     *
     * @param session         the current session
     * @param examVersionId   the exam version being sat
     * @return the questions in exam order, without any correctness data
     */
    public List<TakeExamQuestion> findForTakeExam(Session session, long examVersionId) {
        return session.createQuery("""
                        select new server.db.projections.TakeExamQuestion(
                            qv.id, q.displayId, evq.ordinal, evq.points,
                            qv.text, qv.a1, qv.a2, qv.a3, qv.a4, qv.image)
                        from ExamVersionQuestion evq, QuestionVersion qv, Question q
                        where evq.id.examVersionId = :examVersionId
                          and qv.id = evq.id.questionVersionId
                          and q.id = qv.questionId
                        order by evq.ordinal
                        """, TakeExamQuestion.class)
                .setParameter("examVersionId", examVersionId)
                .getResultList();
    }

    /**
     * Looks a question up by the 5-digit id people type (S-8).
     *
     * <p>Consumers: E6 bank search, and the E2.15 seed loader's idempotency check.
     *
     * @param session   the current session
     * @param displayId the 5-character display id
     * @return the question, or empty
     */
    public Optional<Question> findByDisplayId(Session session, String displayId) {
        return session.createQuery(
                        "from Question where displayId = :displayId", Question.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    /**
     * One specific version of a question, correct answer included.
     *
     * <p><b>Teacher-only.</b> Consumers: E7 exam composition, which pins a version
     * deliberately (the seed's Algebra Midterm references question 11005 version 1, never
     * latest), and E11 grading.
     *
     * @param session    the current session
     * @param questionId the question's internal id
     * @param versionNo  the 1-based version number
     * @return the version, or empty
     */
    public Optional<QuestionVersion> findVersionForAuthoring(Session session, long questionId, int versionNo) {
        return session.createQuery("""
                        from QuestionVersion
                        where questionId = :questionId and versionNo = :versionNo
                        """, QuestionVersion.class)
                .setParameter("questionId", questionId)
                .setParameter("versionNo", versionNo)
                .uniqueResultOptional();
    }

    /**
     * The question versions behind an attempt, <b>answer key included</b>, for scoring it.
     *
     * <p>The {@code ForGrading} suffix is the third sanctioned audience in
     * {@code CorrectnessLeakGuardTest}: scoring an attempt is by definition comparing what the
     * student chose against what is right, so this read carries the key and says so in its name.
     * Nothing student-facing may call it — the key is consumed by {@code AutoGrader} and never
     * returned; what reaches a student is either a score or, through {@code CHECKED_FORM_GET},
     * their own marked paper under that verb's three conditions.
     *
     * <p>Takes the ids the exam version pinned rather than a question id, so a caller cannot
     * accidentally grade against a newer version than the one sat (PRD §6).
     *
     * <p>Consumer: E12.1's auto-grading, via {@code server.features.grading.GradingReads}.
     *
     * @param session            the current session
     * @param questionVersionIds the pinned version ids; an empty collection returns an empty list
     * @return the matching versions, in no guaranteed order — the caller pairs them by id
     */
    public List<QuestionVersion> findVersionsForGrading(Session session,
                                                        Collection<Long> questionVersionIds) {
        if (questionVersionIds == null || questionVersionIds.isEmpty()) {
            // An `in ()` with no values is a syntax error on some engines and a full scan on
            // others; neither is what an empty exam should do.
            return List.of();
        }
        return session.createQuery("""
                        from QuestionVersion
                        where id in (:ids)
                        """, QuestionVersion.class)
                .setParameter("ids", questionVersionIds)
                .getResultList();
    }

    /**
     * The newest version of a question, correct answer included.
     *
     * <p><b>Teacher-only.</b> Consumer: E6 editing, which creates version n+1 from the
     * current one (C-2/ADR-011).
     *
     * @param session    the current session
     * @param questionId the question's internal id
     * @return the highest-numbered version, or empty when the question has none
     */
    public Optional<QuestionVersion> findLatestVersionForAuthoring(Session session, long questionId) {
        return session.createQuery("""
                        from QuestionVersion
                        where questionId = :questionId
                        order by versionNo desc
                        """, QuestionVersion.class)
                .setParameter("questionId", questionId)
                .setMaxResults(1)
                .uniqueResultOptional();
    }
}
