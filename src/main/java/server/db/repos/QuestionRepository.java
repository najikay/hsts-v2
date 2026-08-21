package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Question;
import server.db.entities.QuestionVersion;
import server.db.projections.BankQuestionSummary;
import server.db.projections.BotBankQuestion;
import server.db.projections.QuestionOutline;
import server.db.projections.ReferencingExam;
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
     * The answer key of one whole exam version, in exam order (E8.4 ⚑ — F4.1).
     *
     * <p><b>Teacher-only, and named so.</b> A coordinator deciding whether to approve an exam
     * has to be able to check that its answers are right; approving a paper whose key you
     * cannot see is approving a document, not an exam. That is authoring work — nobody has
     * sat this paper and there is nothing to grade — so it takes the {@code ForAuthoring}
     * suffix E2.12 established rather than a fourth sanctioned name, and
     * {@code CorrectnessLeakGuardTest} keeps that honest.
     *
     * <p>By exam version rather than by a collection of ids, which is what distinguishes it
     * from {@link #findVersionsForGrading}: that one is handed the ids an attempt was sat on
     * and returns them in no particular order, because a grader pairs by id. This one is
     * asked "what is on this paper" and answers in {@code ordinal} order, because a side
     * panel is rendered in exam order and a caller that had to sort it would be re-deriving
     * the paper's order from something other than the paper.
     *
     * <p>Consumer: E8's exam preview, through {@code server.features.approval.ApprovalStore}.
     * Nothing student-facing may call it, and nothing does: the student's paper comes from
     * {@link #findForTakeExam}, whose projection has nowhere to put a key.
     *
     * @param session       the current session
     * @param examVersionId the exam version being reviewed
     * @return the pinned question versions, <b>answer key included</b>, in exam order
     */
    public List<QuestionVersion> findAnswerKeyForAuthoring(Session session, long examVersionId) {
        return session.createQuery("""
                        select qv from ExamVersionQuestion evq, QuestionVersion qv
                        where evq.id.examVersionId = :examVersionId
                          and qv.id = evq.id.questionVersionId
                        order by evq.ordinal
                        """, QuestionVersion.class)
                .setParameter("examVersionId", examVersionId)
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

    // ===================== E6 bank browse and delete ======================

    /**
     * One page of the question bank browse (E6.5 - F2.4, T-2.6).
     *
     * <p>Returns the <b>latest</b> version of each question, which is what the bank always
     * lists (F2.3) while exams stay pinned to whatever they were built from (C-2).
     *
     * <p>A constructor expression, and the reason is size rather than secrecy this time:
     * {@code hasImage} is computed with a {@code case} so the {@code MEDIUMBLOB} is never in
     * the SELECT list. Selecting the entity instead would move up to 2MB per row to render a
     * list of stems. It also happens to carry no correct answer, which is why the name does not
     * end {@code ForAuthoring}.
     *
     * <p>Consumer: E6.5's {@code BANK_LIST}.
     *
     * @param session the current session
     * @param query   scope and filters; see {@link BankQuery}
     * @param offset  rows to skip, {@code page * size}
     * @param limit   page size, already clamped by the caller
     * @return the page, ordered by display id so paging is stable
     */
    public List<BankQuestionSummary> findBankPage(Session session, BankQuery query,
                                                  int offset, int limit) {
        if (query.matchesNothing()) {
            return List.of();
        }
        var select = session.createQuery("""
                select new server.db.projections.BankQuestionSummary(
                    q.displayId, q.courseCode, c.name, qv.text, qv.topic, qv.difficulty,
                    qv.versionNo,
                    case when qv.image is null then false else true end,
                    qv.createdAt)
                """ + bankFrom() + bankWhere(query) + """
                order by q.displayId
                """, BankQuestionSummary.class);
        bindBank(select, query);
        return select.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /**
     * How many questions the same browse matches, for the pager (E6.5).
     *
     * <p>Separate from {@link #findBankPage} rather than derived from it, because "how many
     * pages are there" cannot be answered from one page of rows. The two share their FROM and
     * WHERE through {@link #bankFrom} and {@link #bankWhere} so a filter can never apply to one
     * and not the other, which would show a pager that scrolls to empty pages.
     *
     * @param session the current session
     * @param query   the same query passed to {@link #findBankPage}
     * @return the total matching questions
     */
    public long countBank(Session session, BankQuery query) {
        if (query.matchesNothing()) {
            return 0L;
        }
        var count = session.createQuery(
                "select count(q) " + bankFrom() + bankWhere(query), Long.class);
        bindBank(count, query);
        return count.getSingleResult();
    }

    /**
     * The exams that block deleting a question (E6.4 - F2.5, T-2.7).
     *
     * <p><b>One row per exam, not per exam version.</b> References live in
     * {@code exam_version_questions}, which is keyed on the version, and seed exam
     * {@code 101101} pins question {@code 11005} in both of its versions. A query without the
     * collapse tells the teacher "2 exams use it: Algebra Midterm, Algebra Midterm".
     *
     * <p>The name comes from the exam's latest version, because that is the name on her own
     * exam list. Matching is on any version: an old version still referencing the question is
     * still a reason the history would break.
     *
     * <p>Consumer: E6.4's {@code QUESTION_DELETE}.
     *
     * @param session    the current session
     * @param questionId the question's internal id, all versions
     * @return blocking exams by display id, empty when the question is free to delete
     */
    public List<ReferencingExam> findReferencingExams(Session session, long questionId) {
        return session.createQuery("""
                        select new server.db.projections.ReferencingExam(e.displayId, ev.name)
                        from Exam e, ExamVersion ev
                        where ev.examId = e.id
                          and ev.versionNo = (
                              select max(latest.versionNo) from ExamVersion latest
                              where latest.examId = e.id)
                          and exists (
                              select 1 from ExamVersionQuestion evq, ExamVersion referencing
                              where evq.id.examVersionId = referencing.id
                                and referencing.examId = e.id
                                and evq.questionId = :questionId)
                        order by e.displayId
                        """, ReferencingExam.class)
                .setParameter("questionId", questionId)
                .getResultList();
    }

    /**
     * A question by display id, <b>excluding soft-deleted ones</b> (E6.3, E6.4).
     *
     * <p>Deliberately not {@link #findByDisplayId}, which does not filter {@code deleted_at}
     * and must not start: the seed loader's idempotency check needs a soft-deleted question to
     * still count as existing, or a reseed would allocate its display id to a different
     * question. E6 needs the opposite answer to the same question, so it gets its own method
     * rather than a flag. T-2.8 is the case that separates them.
     *
     * @param session   the current session
     * @param displayId the 5-digit id
     * @return the question, or empty when unknown or soft-deleted
     */
    public Optional<Question> findActiveByDisplayId(Session session, String displayId) {
        return session.createQuery("""
                        from Question
                        where displayId = :displayId and deletedAt is null
                        """, Question.class)
                .setParameter("displayId", displayId)
                .uniqueResultOptional();
    }

    // ---------- the browse query, assembled once and shared by page and count ----------

    private static String bankFrom() {
        return """
                from Question q, QuestionVersion qv, Course c
                """;
    }

    /**
     * The WHERE shared by the page and the count.
     *
     * <p>Assembled rather than written as one block with {@code :topic is null or ...} clauses,
     * because an unfiltered browse is the common case and those disjunctions defeat the index
     * on every one of them. Each filter contributes a clause only when it is actually set.
     */
    private static String bankWhere(BankQuery query) {
        StringBuilder where = new StringBuilder("""
                where qv.questionId = q.id
                  and c.code = q.courseCode
                  and q.deletedAt is null
                  and qv.versionNo = (
                      select max(latest.versionNo) from QuestionVersion latest
                      where latest.questionId = q.id)
                """);
        if (!query.allCourses()) {
            where.append("  and q.courseCode in (:reachable)\n");
        }
        if (isSet(query.courseCode())) {
            where.append("  and q.courseCode = :courseCode\n");
        }
        if (isSet(query.topic())) {
            where.append("  and qv.topic = :topic\n");
        }
        if (query.difficulty() != null) {
            where.append("  and qv.difficulty = :difficulty\n");
        }
        if (isSet(query.search())) {
            // escape '!' so a teacher searching for '100%' or '_' gets those characters rather
            // than LIKE wildcards. '!' rather than a backslash, which HQL string literals and
            // the JDBC driver would each want their own escaping of.
            where.append("  and lower(qv.text) like :search escape '!'\n");
        }
        return where.toString();
    }

    private static void bindBank(org.hibernate.query.Query<?> query, BankQuery bank) {
        if (!bank.allCourses()) {
            query.setParameterList("reachable", bank.reachableCourses());
        }
        if (isSet(bank.courseCode())) {
            query.setParameter("courseCode", bank.courseCode());
        }
        if (isSet(bank.topic())) {
            query.setParameter("topic", bank.topic());
        }
        if (bank.difficulty() != null) {
            query.setParameter("difficulty", bank.difficulty());
        }
        if (isSet(bank.search())) {
            // Lowercased both sides rather than relying on the collation: H2 in MySQL mode does
            // not reproduce utf8mb4_unicode_ci, so a case test that passed here and failed on
            // MySQL would be exactly the drift the two-engine pair exists to catch.
            query.setParameter("search",
                    "%" + likeLiteral(bank.search().trim().toLowerCase(java.util.Locale.ROOT))
                            + "%");
        }
    }

    /**
     * Escapes the LIKE wildcards in text a teacher typed.
     *
     * <p>Free text is the one filter she composes herself (F2.4), so it is the one that can
     * contain {@code %} or {@code _}. Unescaped, searching {@code 100%} matches every stem
     * containing {@code 100}, and searching {@code _} matches everything. Not an injection
     * risk, since the value is a bound parameter either way; a correctness one.
     *
     * <p>The escape character itself goes first, or escaping would corrupt a search for it.
     *
     * @param search the lowercased, trimmed search text
     * @return the same text as a LIKE literal, for use with {@code escape '!'}
     */
    private static String likeLiteral(String search) {
        return search.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
