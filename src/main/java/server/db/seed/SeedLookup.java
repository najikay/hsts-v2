package server.db.seed;

import org.hibernate.Session;

import java.util.Optional;

/**
 * Resolving seed rows by their stable natural key (E2.15).
 *
 * <p>The one place the natural-key rule from {@link SeedSection} is actually implemented. No
 * section holds a database id in a constant, because it cannot: ids are
 * {@code AUTO_INCREMENT} and {@code DELETE} does not reset them, so the same eighteen people
 * are numbered differently after every reseed.
 *
 * <p>The {@code find} forms answer empty and drive idempotency: a section asks whether its row
 * is already there and inserts only if not. The {@code require} forms throw, and are for
 * dependencies a later section cannot proceed without, where a missing row means the section
 * order is wrong rather than that the database is partially seeded. That distinction is the
 * reason both exist: a silent {@code null} at that point would surface much later as a foreign
 * key violation naming a column nobody was thinking about.
 */
final class SeedLookup {

    private SeedLookup() {
        // static helper, no instances
    }

    static Optional<Long> findUserId(Session session, String username) {
        return session.createQuery(
                        "select u.id from User u where u.username = :username", Long.class)
                .setParameter("username", username)
                .uniqueResultOptional();
    }

    static long requireUserId(Session session, String username) {
        return findUserId(session, username).orElseThrow(() -> missing("user", username));
    }

    static Optional<Long> findQuestionId(Session session, String displayId) {
        return session.createQuery(
                        "select q.id from Question q where q.displayId = :displayId", Long.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    static long requireQuestionId(Session session, String displayId) {
        return findQuestionId(session, displayId).orElseThrow(() -> missing("question", displayId));
    }

    static Optional<Long> findQuestionVersionId(Session session, long questionId, int versionNo) {
        return session.createQuery("""
                        select qv.id from QuestionVersion qv
                        where qv.questionId = :questionId and qv.versionNo = :versionNo
                        """, Long.class)
                .setParameter("questionId", questionId)
                .setParameter("versionNo", versionNo)
                .uniqueResultOptional();
    }

    static long requireQuestionVersionId(Session session, String displayId, int versionNo) {
        long questionId = requireQuestionId(session, displayId);
        return findQuestionVersionId(session, questionId, versionNo)
                .orElseThrow(() -> missing("question version", displayId + " v" + versionNo));
    }

    /**
     * The highest version number a question currently has.
     *
     * <p>Seed §7.5's rule is "everywhere else use the latest version", so composition resolves
     * this rather than hardcoding 1. Written out because the alternative silently diverges from
     * the document the day a fourth question gains a second version.
     */
    static int latestQuestionVersionNo(Session session, long questionId) {
        return session.createQuery("""
                        select max(qv.versionNo) from QuestionVersion qv
                        where qv.questionId = :questionId
                        """, Integer.class)
                .setParameter("questionId", questionId)
                .uniqueResultOptional()
                .orElseThrow(() -> new IllegalStateException(
                        "question " + questionId + " has no versions"));
    }

    static Optional<Long> findExamId(Session session, String displayId) {
        return session.createQuery(
                        "select e.id from Exam e where e.displayId = :displayId", Long.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    static long requireExamId(Session session, String displayId) {
        return findExamId(session, displayId).orElseThrow(() -> missing("exam", displayId));
    }

    static Optional<Long> findExamVersionId(Session session, long examId, int versionNo) {
        return session.createQuery("""
                        select ev.id from ExamVersion ev
                        where ev.examId = :examId and ev.versionNo = :versionNo
                        """, Long.class)
                .setParameter("examId", examId)
                .setParameter("versionNo", versionNo)
                .uniqueResultOptional();
    }

    /**
     * The exam version an exam's display id and version number name.
     *
     * @param displayId the six-digit exam id
     * @param versionNo the version within that exam
     * @return its database id
     */
    static long requireExamVersionId(Session session, String displayId, int versionNo) {
        long examId = requireExamId(session, displayId);
        return findExamVersionId(session, examId, versionNo)
                .orElseThrow(() -> missing("exam version", displayId + " v" + versionNo));
    }

    /**
     * Executions sharing a code.
     *
     * <p>A list rather than an {@code Optional} on purpose: code uniqueness holds only among
     * <em>non-closed</em> executions, which is a service rule (E9, C-1) and not a database
     * constraint, because closed executions keep their codes forever. Returning an Optional here
     * would quietly assert a uniqueness the schema does not provide.
     */
    static java.util.List<Long> findExecutionByCode(Session session, String code) {
        return session.createQuery(
                        "select e.id from ExamExecution e where e.code = :code", Long.class)
                .setParameter("code", code)
                .getResultList();
    }

    /** @return the grade on an attempt, or empty. uq_grades_attempt makes it at most one. */
    static Optional<Long> findGradeId(Session session, long attemptId) {
        return session.createQuery(
                        "select g.id from Grade g where g.attemptId = :attemptId", Long.class)
                .setParameter("attemptId", attemptId)
                .uniqueResultOptional();
    }

    /** @return the attempt a student made on an execution, or empty */
    static Optional<Long> findAttemptId(Session session, long executionId, long studentId) {
        return session.createQuery("""
                        select a.id from ExamAttempt a
                        where a.executionId = :executionId and a.studentId = :studentId
                        """, Long.class)
                .setParameter("executionId", executionId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /** @return the bot for a course; uq_bots_course makes it at most one (S-30). */
    static Optional<Long> findBotByCourse(Session session, String course) {
        return session.createQuery(
                        "select b.id from Bot b where b.courseCode = :course", Long.class)
                .setParameter("course", course)
                .uniqueResultOptional();
    }

    /** @return a source on a bot with this title, or empty. Title is the natural key here. */
    static Optional<Long> findBotSourceId(Session session, long botId, String title) {
        return session.createQuery("""
                        select s.id from BotSource s
                        where s.botId = :botId and s.title = :title
                        """, Long.class)
                .setParameter("botId", botId)
                .setParameter("title", title)
                .uniqueResultOptional();
    }

    /**
     * A recorded message, keyed on bot plus student plus the question text.
     *
     * <p>Sessions and messages have no display id, so this is the natural key available. It is
     * a choice, flagged in the report: two identical questions from one student to one bot
     * would collapse into one. Acceptable for a fixed eight-row fixture and wrong the moment
     * the seed grows a repeat, which is the same trade §11 makes.
     */
    static Optional<Long> findBotMessageId(Session session, long botId, long studentId,
                                           String question) {
        return session.createQuery("""
                        select m.id from BotMessage m
                        where m.botId = :botId and m.studentId = :studentId
                          and m.question = :question
                        """, Long.class)
                .setParameter("botId", botId)
                .setParameter("studentId", studentId)
                .setParameter("question", question)
                .uniqueResultOptional();
    }

    /** @return when an execution's window opens */
    static java.time.Instant executionOpensAt(Session session, long executionId) {
        return session.createQuery(
                        "select e.openAt from ExamExecution e where e.id = :id",
                        java.time.Instant.class)
                .setParameter("id", executionId)
                .getSingleResult();
    }

    /** @return when an execution's window closes */
    static java.time.Instant executionClosesAt(Session session, long executionId) {
        return session.createQuery(
                        "select e.closeAt from ExamExecution e where e.id = :id",
                        java.time.Instant.class)
                .setParameter("id", executionId)
                .getSingleResult();
    }

    /**
     * Fails the load when a seeded invariant does not hold.
     *
     * <p>Shared so every section reports the same way, and so a violated expectation stops the
     * transaction rather than writing rows nobody checked.
     */
    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("seed: " + message);
        }
    }

    static boolean subjectExists(Session session, String code) {
        return session.createQuery(
                        "select count(s) from Subject s where s.code = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult() > 0;
    }

    static boolean courseExists(Session session, String code) {
        return session.createQuery(
                        "select count(c) from Course c where c.code = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult() > 0;
    }

    private static IllegalStateException missing(String what, String key) {
        return new IllegalStateException(
                "seed dependency missing: no " + what + " '" + key
                        + "'. A section ran before the section that creates it.");
    }
}
