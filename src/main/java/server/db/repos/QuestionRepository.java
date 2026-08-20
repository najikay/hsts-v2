package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.QuestionOutline;
import server.db.projections.TakeExamQuestion;

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
     * The paper's shape without its content (E10.13/E10.14).
     *
     * <p>Positions, ids and points, for the answer-summary grid that the submit dialog, the
     * Submitted screen, the Time Up takeover and the live monitor all render. Those four
     * need a row of numbered chips, and building it from {@link #findForTakeExam} would
     * fetch every illustration in the exam to draw them. The monitor pays that cost on
     * every push, which is where it stops being merely wasteful.
     *
     * <p>Like its bigger sibling, this selects no correctness column.
     *
     * @param session       the current session
     * @param examVersionId the exam version being sat
     * @return the outline in exam order
     */
    public List<QuestionOutline> findOutlineForTakeExam(Session session, long examVersionId) {
        return session.createQuery("""
                        select new server.db.projections.QuestionOutline(
                            qv.id, q.displayId, evq.ordinal, evq.points)
                        from ExamVersionQuestion evq, QuestionVersion qv, Question q
                        where evq.id.examVersionId = :examVersionId
                          and qv.id = evq.id.questionVersionId
                          and q.id = qv.questionId
                        order by evq.ordinal
                        """, QuestionOutline.class)
                .setParameter("examVersionId", examVersionId)
                .getResultList();
    }

    /**
     * How many questions one exam version has, without fetching any of them.
     *
     * <p>The join screen shows "20 questions" before the student has identified herself,
     * and the paper must not exist on her machine at that point (S-18: the identity entry
     * is what starts the clock). Counting is also what keeps illustrations out of a
     * response that has no use for them: {@link #findForTakeExam} carries image bytes, and
     * a header that reused it would ship a megabyte to render a number.
     *
     * <p>Consumer: E10 join-by-code.
     *
     * @param session       the current session
     * @param examVersionId the exam version
     * @return the number of questions on the paper
     */
    public int countForTakeExam(Session session, long examVersionId) {
        return session.createQuery("""
                        select count(evq) from ExamVersionQuestion evq
                        where evq.id.examVersionId = :examVersionId
                        """, Long.class)
                .setParameter("examVersionId", examVersionId)
                .getSingleResult()
                .intValue();
    }

    /**
     * Whether a question version is actually on this paper (E10.3).
     *
     * <p>The autosave verb is handed a question id by the client, and a client can send
     * any number at all. Without this check a student could write rows against questions
     * from somebody else's exam, which would then be marked. A count rather than a load,
     * because this runs on every keystroke-ish autosave and the question's text and image
     * are of no interest to it.
     *
     * <p>Carries no correctness data and cannot: it answers a boolean.
     *
     * @param session           the current session
     * @param examVersionId     the exam version being sat
     * @param questionVersionId the question the client claims to be answering
     * @return {@code true} when that question is on that paper
     */
    public boolean isOnTakeExamPaper(Session session, long examVersionId, long questionVersionId) {
        return session.createQuery("""
                        select count(evq) from ExamVersionQuestion evq
                        where evq.id.examVersionId = :examVersionId
                          and evq.id.questionVersionId = :questionVersionId
                        """, Long.class)
                .setParameter("examVersionId", examVersionId)
                .setParameter("questionVersionId", questionVersionId)
                .getSingleResult() > 0;
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
