package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.BotBankQuestion;
import server.db.projections.QuestionOutline;
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
     * The course's bank questions, as the <b>study bot</b> may see them (E16.6 ⚑ —
     * S-28, F12.8).
     *
     * <p>A third audience for this table, and the third rule. The take-exam read
     * hides the key from a student sitting a paper; the authoring reads hand it to
     * the teacher who wrote it; this one hands the bot a question and its four
     * options and <b>no key at all</b> — {@code correct_answer} is not in the SELECT
     * list, so on this path it never leaves the database.
     *
     * <p>That is the lead's ruling for this feature, and the reasoning is recorded
     * on {@link BotBankQuestion}: S-28 allows the bank as study material, the
     * specification asks for "the questions", and a study bot that recites answer
     * keys for material that may be on next week's paper defeats the point of
     * having one. It is also why this method carries none of the sanctioned
     * correctness suffixes — there is no answer key here to declare an audience
     * for, and {@code CorrectnessLeakGuardTest} verifies that rather than assuming
     * it.
     *
     * <p>Latest version per question, soft-deleted questions excluded: a question a
     * teacher withdrew from the bank should not still be taught by the bot. Ordered
     * by display id so the material a prompt is built from is deterministic.
     *
     * <p>Consumer: E16's {@code ContextBuilder}, through {@code JpaBotStore}.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @param limit      the most questions to read; the context builder scores them
     *                   in memory, so this bounds the work rather than the relevance
     * @return the questions with their four options, without any correctness data
     */
    public List<BotBankQuestion> findBankForBot(Session session, String courseCode, int limit) {
        if (courseCode == null || courseCode.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        select new server.db.projections.BotBankQuestion(
                            q.displayId, qv.text, qv.a1, qv.a2, qv.a3, qv.a4)
                        from Question q, QuestionVersion qv
                        where q.courseCode = :courseCode
                          and q.deletedAt is null
                          and qv.questionId = q.id
                          and qv.versionNo = (
                              select max(v.versionNo) from QuestionVersion v where v.questionId = q.id)
                        order by q.displayId
                        """, BotBankQuestion.class)
                .setParameter("courseCode", courseCode)
                .setMaxResults(Math.max(1, limit))
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
