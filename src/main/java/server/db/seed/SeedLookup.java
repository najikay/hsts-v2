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
