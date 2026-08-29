package server.db.repos;

import org.hibernate.Session;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;
import server.db.projections.GradeExamLabel;
import server.db.projections.StudentResultRow;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads over {@code grades} (E2.11). */
public final class GradeRepository {

    /**
     * The grade for one attempt.
     *
     * <p>{@code attempt_id} is unique, so at most one row exists.
     *
     * <p>Consumers: E11 grading review; E11 publishing a result to the student.
     *
     * @param session   the current session
     * @param attemptId the attempt
     * @return the grade, or empty when grading has not run
     */
    public Optional<Grade> findByAttempt(Session session, long attemptId) {
        return session.createQuery("from Grade where attemptId = :attemptId", Grade.class)
                .setParameter("attemptId", attemptId)
                .uniqueResultOptional();
    }

    /**
     * One grade by its id, with no scoping at all.
     *
     * <p>The unscoped read, and the only one here: every other read on this repository has an
     * owner baked into its query. It exists because the teacher verbs cannot express their
     * ownership rule in SQL — "the executing teacher, or the exam's author" lives two joins
     * away in {@code exam_executions}, and the caller resolves it through
     * {@code ExecutionContext} rather than duplicating that join into every grading query.
     *
     * <p><b>The danger is in the name, which is the point.</b> {@code Unscoped} works the way
     * {@code ForAuthoring} and {@code ForGrading} do: it makes every call site confess at
     * review time, so nobody has to notice the absence of a filter. A read whose only warning
     * lives in its Javadoc is a read whose warning is invisible at the one moment it matters —
     * when somebody is scanning a diff for the word that should have made them stop.
     *
     * <p>The sentence stays as well, because the name says <em>that</em> it is dangerous and
     * only prose can say what to do about it: <b>a handler calling this must resolve ownership
     * before answering with anything it returns.</b> Student paths must not use it at all —
     * they have {@link #findForStudent}, which makes ownership the query, and which exists
     * precisely so E13.1's guarantee cannot be forgotten.
     *
     * <p>Consumers: E12.3's {@code GRADE_OVERRIDE} and E12.6's {@code GRADE_REVIEW_GET},
     * through {@code server.features.grading.GradeReviewService#contextOf}, which reads the
     * execution in the same breath and hands both to the gate.
     *
     * @param session the current session
     * @param gradeId the grade
     * @return the grade, or empty when no such row exists
     */
    public Optional<Grade> findByIdUnscoped(Session session, long gradeId) {
        return Optional.ofNullable(session.get(Grade.class, gradeId));
    }

    /**
     * A student's approved grades, newest first.
     *
     * <p><b>Scoped in the query, not by the caller.</b> The student id is a filter here rather
     * than something a handler checks afterwards, so no code path loads someone else's rows and
     * then remembers to drop them (E13.1 &#9873;). Only {@code APPROVED} rows are returned:
     * auto-checking alone publishes nothing (C-3, S-24).
     *
     * <p>Carries no correctness data — {@code Grade} is scores and audit fields — so this read is
     * outside {@code CorrectnessLeakGuardTest}'s remit by construction.
     *
     * <p>Consumer: E13.3's {@code MY_GRADES_GET}.
     *
     * @param session   the current session
     * @param studentId the student, always the authenticated caller
     * @return approved grades, most recently approved first
     */
    public List<Grade> findApprovedForStudent(Session session, long studentId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.studentId = :studentId
                          and g.status = :status
                        order by g.approvedAt desc, g.id desc
                        """, Grade.class)
                .setParameter("studentId", studentId)
                .setParameter("status", GradeStatus.APPROVED)
                .getResultList();
    }

    /**
     * Which exam each of these grades was for (contract amendment v1.1).
     *
     * <p>One read for the whole list rather than one per row. A student's grade list is short,
     * so the N+1 version would also have worked — and would have been the shape that quietly
     * became a problem the first time somebody reused it for a principal's report over a
     * whole cohort. Reading labels in bulk costs nothing here and stays correct there.
     *
     * <p>Deliberately keyed by grade id rather than returned in list order, so the caller pairs
     * rows explicitly and a grade whose joins do not resolve is simply unlabelled instead of
     * silently taking its neighbour's exam name.
     *
     * <p>Carries no scores and no correctness — see {@link GradeExamLabel} — so it is safe on
     * the student path it exists for.
     *
     * <p>A7 added {@code ex.createdBy} to the select. The execution was already one of the five
     * joins, so the teacher a student's card names costs a column here rather than a read of its
     * own — the rule this repository follows everywhere: one read for the list, never one per
     * row.
     *
     * <p>Consumers: E13.3's {@code MY_GRADES_GET} and E13.4's {@code CHECKED_FORM_GET}.
     *
     * @param session  the current session
     * @param gradeIds the grades to label; an empty collection returns an empty map
     * @return exam name and course code by grade id
     */
    public Map<Long, GradeExamLabel> findExamLabels(Session session, Collection<Long> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            // Same `in ()` reason as findByIds.
            return Map.of();
        }
        List<GradeExamLabel> labels = session.createQuery("""
                        select new server.db.projections.GradeExamLabel(g.id, v.name, e.courseCode,
                                                                       ex.createdBy)
                        from Grade g, ExamAttempt a, ExamExecution ex, ExamVersion v, Exam e
                        where g.id in (:ids)
                          and a.id = g.attemptId
                          and ex.id = a.executionId
                          and v.id = ex.examVersionId
                          and e.id = v.examId
                        """, GradeExamLabel.class)
                .setParameter("ids", gradeIds)
                .getResultList();

        Map<Long, GradeExamLabel> byGrade = new LinkedHashMap<>();
        for (GradeExamLabel label : labels) {
            byGrade.put(label.gradeId(), label);
        }
        return byGrade;
    }

    /**
     * One grade, but only if it belongs to this student.
     *
     * <p>The ownership check <b>is</b> the query. A grade id belonging to someone else comes back
     * empty and the handler answers {@code NOT_FOUND} — indistinguishable from an id that does
     * not exist, so probing reveals nothing (contract, E13.1 &#9873;).
     *
     * <p>Consumer: E13.4's {@code CHECKED_FORM_GET}, which applies its own two further conditions
     * (state approved, execution closed) on top of ownership.
     *
     * @param session   the current session
     * @param gradeId   the requested grade
     * @param studentId the student, always the authenticated caller
     * @return the grade when it is this student's, otherwise empty
     */
    public Optional<Grade> findForStudent(Session session, long gradeId, long studentId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.id = :gradeId
                          and g.attemptId = a.id
                          and a.studentId = :studentId
                        """, Grade.class)
                .setParameter("gradeId", gradeId)
                .setParameter("studentId", studentId)
                .uniqueResultOptional();
    }

    /**
     * Grades by id, for a bulk approval.
     *
     * <p>Returns what exists and says nothing about what does not: an id belonging to another
     * teacher's execution, and an id that was never a grade, both simply fail to appear. The
     * caller reports them the same way (`refused` in {@code ApproveResult}), which is the same
     * no-oracle rule the student reads follow.
     *
     * <p>Order is by id so a partial result is stable to compare against the request.
     *
     * <p>Consumer: E12.2's {@code GRADES_APPROVE}.
     *
     * @param session  the current session
     * @param gradeIds the requested grades; an empty collection returns an empty list
     * @return the grades that exist, by id
     */
    public List<Grade> findByIds(Session session, Collection<Long> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            // `in ()` is a syntax error on some engines and a full scan on others.
            return List.of();
        }
        return session.createQuery("""
                        from Grade where id in (:ids) order by id
                        """, Grade.class)
                .setParameter("ids", gradeIds)
                .getResultList();
    }

    /**
     * Every grade of one execution, whatever its status.
     *
     * <p>Two callers need this and both need the whole set rather than a filtered one:
     * deciding whether an approval <b>completed</b> an execution means asking whether any
     * {@code AUTO} row remains, and computing the frozen statistics means reading every final
     * score. Splitting it into a count and a projection would read the same rows twice inside
     * one transaction and invite the two to disagree.
     *
     * <p>Consumer: E12.2, when an approval may have completed the execution.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return its grades, by attempt id
     */
    public List<Grade> findAllForExecution(Session session, long executionId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId = :executionId
                        order by g.attemptId
                        """, Grade.class)
                .setParameter("executionId", executionId)
                .getResultList();
    }

    /**
     * Grades for an execution that a teacher has not approved yet.
     *
     * <p>Auto-grading produces {@code AUTO} rows and a teacher approves them into
     * {@code APPROVED} (C-3); this is the queue in between. Joined through
     * {@code exam_attempts} because a grade does not know its execution.
     *
     * <p>Consumer: E12.5's grading queue.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return grades awaiting approval, by attempt id
     */
    public List<Grade> findAwaitingApproval(Session session, long executionId) {
        return session.createQuery("""
                        select g from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId = :executionId
                          and g.status = :status
                        order by g.attemptId
                        """, Grade.class)
                .setParameter("executionId", executionId)
                .setParameter("status", GradeStatus.AUTO)
                .getResultList();
    }

    /**
     * How many grade rows exist behind each of these executions (E14.1 — F9.2).
     *
     * <p>What turns the results picker into a progress list: a sitting with eight participants
     * and six grades is visibly still being marked, and the teacher learns that before opening
     * it. Grouped for the same reason {@code AttemptRepository.countAttemptsByExecution} is —
     * one query for a whole screen rather than one per row.
     *
     * <p>Counts every grade, approved or not. "How much marking has happened" is the question;
     * how much of it has been signed off is {@code approvedCount}'s job in the grading queue,
     * and conflating them would make an execution look unmarked while a teacher was reviewing
     * it.
     *
     * <p>An execution with no grades is absent from the map rather than present with a zero.
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXAMS_GET}.
     *
     * @param session      the current session
     * @param executionIds the executions to count
     * @return execution id → grade rows; empty when {@code executionIds} is empty
     */
    public Map<Long, Integer> countGradesByExecution(Session session,
                                                     Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = session.createQuery("""
                        select a.executionId, count(g)
                        from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId in (:executionIds)
                        group by a.executionId
                        """, Object[].class)
                .setParameterList("executionIds", executionIds)
                .getResultList();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * How many grades in each of these executions a teacher has already approved (E12.5).
     *
     * <p>The mirror of {@link #countGradesByExecution}, and the second half of what a grading
     * queue row says: how many papers exist, and how many have been signed off. Together they
     * are the difference between "nothing marked yet", "half done" and "finished" — three
     * states a queue must tell apart, and which one count cannot.
     *
     * <p><b>In bulk, deliberately.</b> The per-execution alternative is one read each, which is
     * fine for a queue of four and is the shape that quietly becomes a problem the first time a
     * teacher has a term's worth. The queue is one read per fact rather than one per row.
     *
     * <p>An execution with no approved grades is <b>absent from the map</b> rather than present
     * with a zero — the same convention as its sibling, so a caller reads both the same way.
     *
     * <p>Carries no scores and no correctness data: it answers counts.
     *
     * <p>Consumer: E12.5's {@code GRADING_QUEUE_GET}.
     *
     * @param session      the current session
     * @param executionIds the executions to count
     * @return execution id → approved grades; empty when {@code executionIds} is empty
     */
    public Map<Long, Integer> countApprovedByExecution(Session session,
                                                       Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            // Same `in ()` reason as countGradesByExecution.
            return Map.of();
        }
        List<Object[]> rows = session.createQuery("""
                        select a.executionId, count(g)
                        from Grade g, ExamAttempt a
                        where g.attemptId = a.id
                          and a.executionId in (:executionIds)
                          and g.status = :status
                        group by a.executionId
                        """, Object[].class)
                .setParameterList("executionIds", executionIds)
                .setParameter("status", GradeStatus.APPROVED)
                .getResultList();

        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * Every marked student in one execution, by name (E14.1 — F9.2, T-10).
     *
     * <p>The teacher's results table, in one read: the grade, the attempt's recorded solving
     * time, <b>how the attempt ended</b> and the student's name, which is what the table shows
     * and what neither {@link #findAwaitingApproval} nor {@code AttemptRepository.findRows}
     * supplies on its own.
     *
     * <p>⚑ <b>B-16.</b> {@code a.status} joined this select on 2026-08-26, beside the
     * {@code a.actualMinutes} that was already here. T-10.2 asks for "score, submitted vs
     * timed out, solving time"; the solving time was being read and then dropped by the
     * service's mapper, and the status was never selected at all, so a timed-out paper read
     * exactly like a submitted one on the screen built to show the difference.
     *
     * <p><b>This read is not scoped to a teacher, and must not be.</b> Authorship is settled
     * one step earlier, on the execution ({@code ExecutionRepository.findContext} against
     * {@code exams.author}), because that is where the fact lives; a second filter here would
     * be a check that looks like scoping while depending on a caller passing the right id.
     * The service refuses before it ever reaches this method.
     *
     * <p>Ordered by student name, then grade id, so the table is stable across refreshes and a
     * teacher scanning for one student scans alphabetically. Carries no answers and no
     * correctness data, so it needs no sanctioned correctness suffix (E2.12).
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXECUTION_GET}.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return one row per grade, by student name; empty when nothing has been marked
     */
    public List<StudentResultRow> findResultRows(Session session, long executionId) {
        return session.createQuery("""
                        select new server.db.projections.StudentResultRow(
                            g.id, a.studentId, u.fullName, g.autoScore, g.finalScore,
                            g.status, g.overrideReason, g.teacherComment, g.approvedAt,
                            a.actualMinutes, a.status)
                        from Grade g, ExamAttempt a, User u
                        where g.attemptId = a.id
                          and u.id = a.studentId
                          and a.executionId = :executionId
                        order by u.fullName, g.id
                        """, StudentResultRow.class)
                .setParameter("executionId", executionId)
                .getResultList();
    }
}
