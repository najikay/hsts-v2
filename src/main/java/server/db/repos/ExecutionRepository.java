package server.db.repos;

import org.hibernate.Session;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStatus;
import server.db.entities.Participation;
import server.db.projections.ExecutionContext;
import server.db.projections.ExecutionReport;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads over {@code exam_executions} (E2.11). */
public final class ExecutionRepository {

    /**
     * Finds the executions using a 4-character code.
     *
     * <p>Returns a list, not one row, on purpose. Code uniqueness holds only among
     * non-closed executions and §5 states it is a <b>service</b> rule, because MySQL has no
     * partial unique index: a code is legitimately reused once its execution closes. A
     * method returning {@code Optional} here would be asserting a guarantee the schema does
     * not make, and would throw on perfectly valid data.
     *
     * <p>Codes are compared case-insensitively — students type them, and production
     * collation does this anyway while H2 does not.
     *
     * <p>Consumer: E10 join-by-code (C-1, S-18).
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return matching executions, newest first
     */
    /**
     * One execution by its id.
     *
     * <p>Consumer: E12.1's auto-grading, which needs the exam version an attempt was sat on —
     * the pinned one, never the exam's latest (PRD §6).
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the execution, or empty when no such row exists
     */
    public Optional<ExamExecution> findById(Session session, long executionId) {
        return Optional.ofNullable(session.find(ExamExecution.class, executionId));
    }

    public List<ExamExecution> findByCode(Session session, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        from ExamExecution where lower(code) = lower(:code) order by openAt desc
                        """, ExamExecution.class)
                .setParameter("code", code.trim())
                .getResultList();
    }

    /**
     * The one execution a student may currently join with this code.
     *
     * <p>Consumer: E10 join-by-code, which needs exactly the live one.
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return the live execution, or empty
     */
    public Optional<ExamExecution> findJoinable(Session session, String code) {
        return findByCode(session, code).stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.LIVE)
                .findFirst();
    }

    /**
     * Everything about one execution that a take-exam or monitoring decision needs (E10/E11).
     *
     * <p>One join across the execution, its pinned exam version, the exam identity row and
     * the course, because every rule in E10 needs several of those facts at once —
     * "is it live, is she enrolled in its course, how long does she get, what is it
     * called". Reading them in four calls would let a service decide from a picture that
     * changed halfway through.
     *
     * <p>Carries no questions and no answer key: the paper comes from
     * {@link QuestionRepository#findForTakeExam}, and that separation is E2.12.
     *
     * <p>Consumers: E10 join/start/resume, E11 extend/monitor.
     *
     * @param session     the current session
     * @param executionId the execution
     * @return the context, or empty when there is no such execution
     */
    public Optional<ExecutionContext> findContext(Session session, long executionId) {
        return session.createQuery("""
                        select new server.db.projections.ExecutionContext(
                            ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                            v.durationMinutes, v.studentText, ex.code, ex.status,
                            ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
                        from ExamExecution ex, ExamVersion v, Exam e, Course c
                        where ex.id = :executionId
                          and v.id = ex.examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                        """, ExecutionContext.class)
                .setParameter("executionId", executionId)
                .uniqueResultOptional();
    }

    /**
     * Every execution using this code, with its full context, newest first (E10.9).
     *
     * <p>Not filtered to LIVE, and that is the point: a student typing a code that belongs
     * to an execution which has closed, has not opened yet, or was cancelled deserves to be
     * told <em>that</em>, not "no such code". The four entry errors PRD §4 asks for
     * (wrong code, not live, not enrolled, wrong id) need this read to be able to tell the
     * first two apart, and a query that hid non-live rows could not.
     *
     * <p>Codes are legitimately reused once an execution closes (see {@link #findByCode}),
     * so this really can return several rows; the service takes the newest live one and
     * explains the rest.
     *
     * @param session the current session
     * @param code    the 4-character code as typed
     * @return matching executions with their context, newest first
     */
    public List<ExecutionContext> findContextsByCode(Session session, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        select new server.db.projections.ExecutionContext(
                            ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                            v.durationMinutes, v.studentText, ex.code, ex.status,
                            ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
                        from ExamExecution ex, ExamVersion v, Exam e, Course c
                        where lower(ex.code) = lower(:code)
                          and v.id = ex.examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                        order by ex.openAt desc
                        """, ExecutionContext.class)
                .setParameter("code", code.trim())
                .getResultList();
    }

    /**
     * Grants extra minutes to a live execution (F7.1, S-20).
     *
     * <p>A read-modify-write through the entity rather than a bulk update, and deliberately
     * so: {@code exam_executions} carries {@code lock_version}, and two teachers pressing
     * "add 15 minutes" at the same moment must not silently produce one grant. The second
     * flush fails with an optimistic-lock exception, the handler answers {@code CONFLICT},
     * and the teacher is told to look again rather than left believing she granted time
     * nobody got.
     *
     * <p>The <em>attempt</em> deadlines need no update at all: they are derived from this
     * column every time they are read, so an extension applies to a student who is offline
     * the moment she comes back (E11.4).
     *
     * @param session     the current session
     * @param executionId the execution
     * @param minutes     minutes to add; must be positive (the service validates)
     * @return the new total of granted extra minutes
     * @throws IllegalStateException when the execution does not exist
     */
    public int addExtraMinutes(Session session, long executionId, int minutes) {
        ExamExecution execution = session.get(ExamExecution.class, executionId);
        if (execution == null) {
            throw new IllegalStateException("No execution " + executionId + " to extend");
        }
        execution.addExtraMinutes(minutes);
        session.flush();
        return execution.getExtraMinutes();
    }

    /**
     * Freezes the derived participation counts into the execution's documentation record
     * (S-21, F7.3, E11.5).
     *
     * <p>The counts are a {@code COUNT} over attempts for as long as the execution is live
     * — no counter columns, no increment races (§5). This is the one moment they stop being
     * derived: at close they are written once into the {@code participation} JSON so the
     * record survives even if attempts are later archived, and so every later reader gets
     * the same number.
     *
     * @param session     the current session
     * @param executionId the execution being closed
     * @param counts      the counts as of now
     */
    public void freezeParticipation(Session session, long executionId,
                                    server.db.projections.ParticipationCounts counts) {
        ExamExecution execution = session.get(ExamExecution.class, executionId);
        if (execution == null) {
            throw new IllegalStateException("No execution " + executionId + " to close");
        }
        execution.setParticipation(new Participation(
                (int) counts.started(), (int) counts.finished(), (int) counts.timedOut()));
        execution.setStatus(ExecutionStatus.CLOSED);
        session.flush();
    }

    /**
     * Every execution that still has somebody sitting it (E10.5 ⚑).
     *
     * <p>What the server re-arms its expiry timers from after a restart: a live attempt
     * whose timer died with the old process must still be force-submitted on time, and the
     * database is the only thing that survived. ARCHITECTURE §4 requires exactly this.
     *
     * @param session the current session
     * @return execution ids with at least one {@code IN_PROGRESS} attempt
     */
    /**
     * Every sitting of every exam one teacher wrote (E14.1 — F9.2, S-35).
     *
     * <p>The read that makes S-35 true rather than promised: the join runs
     * {@code exam_executions → exam_versions → exams} and filters on {@code exams.author}, so
     * an execution released by a colleague is returned to the exam's author, and an execution
     * of somebody else's exam is not returned at all. The caller's id is the only parameter;
     * there is no variant of this query that takes an exam id from a payload.
     *
     * <p><b>Cancelled executions are excluded.</b> A run that was called off was never sat, has
     * no results, and would render as an empty table a teacher has to interpret (H15.2 ⚑). The
     * three remaining states are all legitimate answers: scheduled and live executions appear
     * with no statistics and the screen says so.
     *
     * <p>Reuses {@link ExecutionContext} rather than defining a second projection: the picker
     * needs the exam name, the course, the code, the window and the state, which is exactly
     * what E10 already assembled. The {@code examName} is the released version's name, so a
     * sitting is labelled with what the students saw even after the exam is renamed.
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXAMS_GET}.
     *
     * @param session  the current session
     * @param authorId the teacher who wrote the exams, always the authenticated caller
     * @return her exams' sittings, most recently opened first; empty when there are none
     */
    public List<ExecutionContext> findContextsByExamAuthor(Session session, long authorId) {
        return session.createQuery("""
                        select new server.db.projections.ExecutionContext(
                            ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                            v.durationMinutes, v.studentText, ex.code, ex.status,
                            ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
                        from ExamExecution ex, ExamVersion v, Exam e, Course c
                        where v.id = ex.examVersionId
                          and e.id = v.examId
                          and c.code = e.courseCode
                          and e.authorId = :authorId
                          and ex.status <> :cancelled
                        order by ex.openAt desc, ex.id desc
                        """, ExecutionContext.class)
                .setParameter("authorId", authorId)
                .setParameter("cancelled", ExecutionStatus.CANCELLED)
                .getResultList();
    }

    /**
     * Which of these executions have their statistics frozen (E14.1 — F8.5).
     *
     * <p>{@code exam_executions.stats} is written once, when the last grade of an execution is
     * approved. The results picker needs to know which rows have it without loading the JSON
     * of every execution a teacher ever ran, and the answer is a column null-check: one query
     * for the whole list rather than one per row.
     *
     * <p>Deliberately not inferred from "graded == participants": grading being finished and
     * approval having completed are different events, and a picker that promised a histogram
     * it could not draw would be worse than one that says grading is not finished.
     *
     * <p>Consumer: E14.1's {@code RESULTS_EXAMS_GET}.
     *
     * @param session      the current session
     * @param executionIds the executions to ask about
     * @return the ids that carry frozen statistics; empty when {@code executionIds} is empty
     */
    public List<Long> findIdsWithStatistics(Session session, Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            // An `in ()` is a syntax error on one engine and a full scan on the other;
            // neither is the right answer to "none of them".
            return List.of();
        }
        return session.createQuery("""
                        select ex.id from ExamExecution ex
                        where ex.id in (:executionIds) and ex.stats is not null
                        """, Long.class)
                .setParameterList("executionIds", executionIds)
                .getResultList();
    }

    // ===================== The principal's reports (E15.3) ================
    //
    // Four reads added under TEAM_SPLIT rule 5 - repositories grow with their callers - and
    // every one of them shares the definition below of what a report may look at.

    /**
     * What a report is allowed to compare, written once (E15.3 - F9.4, F8.5, H15.2 ⚑).
     *
     * <p>Three conditions, and all three are in the {@code WHERE} clause rather than in a
     * filter afterwards, so no caller can forget one:
     *
     * <ul>
     *   <li><b>{@code status = CLOSED}.</b> A scheduled sitting has no figures, a live one has
     *       figures that are still moving, and a <b>cancelled</b> one was never sat at all
     *       (H15.2 ⚑). Testing for equality with CLOSED rather than inequality with CANCELLED
     *       is what makes that exclusion survive a fifth status being added.</li>
     *   <li><b>{@code stats is not null}.</b> The comparison is of frozen statistics, so a
     *       sitting whose grading never finished has nothing to contribute. It is left out
     *       rather than shown with blanks: a row of gaps in a comparison table is something a
     *       reader has to interpret, and half of them will interpret it as a zero.</li>
     *   <li>the join through the pinned version to the exam and its course, which is what
     *       carries the name the students actually saw.</li>
     * </ul>
     *
     * <p>The three subject filters are appended to this and differ in nothing else. That is the
     * repository half of "a new report type is a new strategy class": a fourth dimension is a
     * fourth {@code and} on the same six lines.
     */
    private static final String REPORTABLE = """
            from ExamExecution ex, ExamVersion v, Exam e, Course c
            where v.id = ex.examVersionId
              and e.id = v.examId
              and c.code = e.courseCode
              and ex.status = :closed
              and ex.stats is not null
            """;

    /** The select list every report row query shares, so the projection is built one way. */
    private static final String REPORT_ROW = """
            select new server.db.projections.ExecutionReport(
                ex.id, ex.code, ex.openAt, ex.closeAt, v.name, e.courseCode, c.name, ex.stats)
            """;

    /**
     * Oldest first, because a comparison across time reads left to right, and the id breaks
     * ties between two sittings opened in the same millisecond.
     */
    private static final String OLDEST_FIRST = " order by ex.openAt asc, ex.id asc";

    /**
     * Every reportable sitting of every exam one teacher <b>wrote</b> (E15.3 - F9.4).
     *
     * <p>Authorship rather than who released it, on E14's precedent: the frozen statistics
     * describe the paper, and the paper belongs to the person who wrote it (RESULTS_WIRE_CONTRACT
     * section 2). A sitting a colleague ran of her exam is hers to be reported on; a sitting she
     * ran of somebody else's is not.
     *
     * <p>Unscoped by caller, and that is not an oversight: the only caller is the principal's
     * report engine, whose scope is the whole school (spec 7.3.1, F9.3). The teacher id here is
     * the <em>subject</em> of the report, never the person asking for it, and the role gate on
     * {@code REPORT_GET} is where "may you ask this at all" is decided.
     *
     * <p>Consumer: E15.3's {@code ByTeacherStrategy}.
     *
     * @param session  the current session
     * @param authorId the teacher the report is about
     * @return her exams' reportable sittings, oldest first; empty when there are none
     */
    public List<ExecutionReport> findReportRowsByAuthor(Session session, long authorId) {
        return session.createQuery(REPORT_ROW + REPORTABLE + " and e.authorId = :authorId"
                        + OLDEST_FIRST, ExecutionReport.class)
                .setParameter("closed", ExecutionStatus.CLOSED)
                .setParameter("authorId", authorId)
                .getResultList();
    }

    /**
     * Every reportable sitting of every exam in one course (E15.3 - F9.4).
     *
     * <p>Filtered on the exam's course rather than on the version's, because a course is a
     * property of the exam identity row and does not move between versions (section 5).
     *
     * <p>Consumer: E15.3's {@code ByCourseStrategy}.
     *
     * @param session    the current session
     * @param courseCode the two-character course code the report is about
     * @return that course's reportable sittings, oldest first; empty when there are none
     */
    public List<ExecutionReport> findReportRowsByCourse(Session session, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return List.of();
        }
        return session.createQuery(REPORT_ROW + REPORTABLE + " and e.courseCode = :courseCode"
                        + OLDEST_FIRST, ExecutionReport.class)
                .setParameter("closed", ExecutionStatus.CLOSED)
                .setParameter("courseCode", courseCode.strip())
                .getResultList();
    }

    /**
     * Every reportable sitting one student sat (E15.3 - F9.4).
     *
     * <p>Membership is an <b>attempt</b>, not a grade. A student who sat a paper that was never
     * marked is still part of that sitting's history, and the sitting's frozen figures are still
     * the class she was measured against. Making the grade the join instead would silently drop
     * her from her own report, which is the sort of absence nobody notices.
     *
     * <p>An {@code exists} subquery rather than a fifth table in the join: one attempt per
     * student per sitting is a service rule rather than a unique index, and a join would return
     * the sitting twice if that rule were ever broken, which would double-count her in the
     * pooled summary.
     *
     * <p>Consumer: E15.3's {@code ByStudentStrategy}.
     *
     * @param session   the current session
     * @param studentId the student the report is about
     * @return the reportable sittings she sat, oldest first; empty when there are none
     */
    public List<ExecutionReport> findReportRowsByStudent(Session session, long studentId) {
        return session.createQuery(REPORT_ROW + REPORTABLE
                        + " and exists (select 1 from ExamAttempt a"
                        + " where a.executionId = ex.id and a.studentId = :studentId)"
                        + OLDEST_FIRST, ExecutionReport.class)
                .setParameter("closed", ExecutionStatus.CLOSED)
                .setParameter("studentId", studentId)
                .getResultList();
    }

    /**
     * How many reportable sittings each teacher, course and student has (E15.3 - F9.4, E15.5).
     *
     * <p>One grouped query per dimension rather than one count per subject, so a picker listing
     * eleven students costs one round trip and not eleven. The counts are what let a subject
     * with nothing to compare say so <em>in the picker</em>, which is E15.5's degenerate case
     * turned into a label instead of a dead end (section 4.1).
     *
     * <p>The keys are strings for all three, matching {@code ReportSubject.id}: a user id in
     * decimal, or a course code. One map type for three dimensions is what keeps the caller from
     * needing a branch per dimension to read its own counts.
     *
     * <p>Consumer: E15.3's {@code JpaReportStore}.
     *
     * @param session the current session
     * @param keyedBy which grouping to count: {@code AUTHOR}, {@code COURSE} or {@code STUDENT}
     * @return subject id to count; a subject with none is absent rather than zero
     */
    public Map<String, Integer> countReportableBy(Session session, ReportGrouping keyedBy) {
        Objects.requireNonNull(keyedBy, "keyedBy");
        List<Object[]> rows = session.createQuery(keyedBy.query(), Object[].class)
                .setParameter("closed", ExecutionStatus.CLOSED)
                .getResultList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put(String.valueOf(row[0]).strip(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * The three groupings {@link #countReportableBy} knows how to count (E15.3).
     *
     * <p>An enum rather than three methods because the three queries differ in one line and
     * their result handling is identical; three copies of the same mapping loop is how two of
     * them eventually stop agreeing about whether an absent subject is zero or missing.
     *
     * <p>The {@code Course} table is left out of these joins deliberately: a count needs no
     * display name, and joining for one would make a course with a missing row disappear from
     * its own count.
     */
    public enum ReportGrouping {

        /** By the teacher who wrote the exam (F9.4's "same teacher"). */
        AUTHOR("""
                select e.authorId, count(ex) from ExamExecution ex, ExamVersion v, Exam e
                where v.id = ex.examVersionId and e.id = v.examId
                  and ex.status = :closed and ex.stats is not null
                group by e.authorId
                """),

        /** By the exam's course (F9.4's "same course"). */
        COURSE("""
                select e.courseCode, count(ex) from ExamExecution ex, ExamVersion v, Exam e
                where v.id = ex.examVersionId and e.id = v.examId
                  and ex.status = :closed and ex.stats is not null
                group by e.courseCode
                """),

        /** By the student who sat it (F9.4's "same student"). */
        STUDENT("""
                select a.studentId, count(distinct ex.id)
                from ExamExecution ex, ExamAttempt a
                where a.executionId = ex.id
                  and ex.status = :closed and ex.stats is not null
                group by a.studentId
                """);

        private final String query;

        ReportGrouping(String query) {
            this.query = query;
        }

        String query() {
            return query;
        }
    }

    // ===================== The principal's data browser (E15.2) ===========

    /**
     * Newest first, because a browse is a filing cabinet rather than a trend: the sitting
     * somebody is looking for is usually the most recent one. See {@link #OLDEST_FIRST}, which
     * is the opposite ordering for the opposite reason.
     */
    private static final String NEWEST_FIRST = " order by ex.openAt desc, ex.id desc";

    /**
     * Every reportable sitting in the school (E15.2 - F9.3, H15.2 ⚑).
     *
     * <p>The same {@link #REPORTABLE} clause the three report populations share, with no subject
     * filter appended to it at all. That shared clause is the point of adding this read here
     * rather than writing a fifth query somewhere else: "closed, with statistics frozen" is one
     * definition with one home, so the principal's browse and her reports cannot disagree about
     * which sittings exist. In particular the H15.2 exclusion of cancelled runs is inherited
     * rather than restated.
     *
     * <p>Unscoped by caller, like its four neighbours above, and for the same reason: the only
     * caller is the principal, whose scope is the school (spec 7.3.1, F9.3).
     *
     * <p>Consumer: E15.2's {@code DATA_RESULTS_GET}.
     *
     * @param session the current session
     * @return every closed sitting carrying frozen statistics, newest first
     */
    public List<ExecutionReport> findAllReportRows(Session session) {
        return session.createQuery(REPORT_ROW + REPORTABLE + NEWEST_FIRST, ExecutionReport.class)
                .setParameter("closed", ExecutionStatus.CLOSED)
                .getResultList();
    }

    public List<Long> findExecutionIdsWithLiveAttempts(Session session) {
        return session.createQuery("""
                        select distinct a.executionId from ExamAttempt a where a.status = :status
                        """, Long.class)
                .setParameter("status", AttemptStatus.IN_PROGRESS)
                .getResultList();
    }

    // ===================== E9: the release manager =========================
    // Added by E9 under TEAM_SPLIT rule 5 (repositories grow with their callers).
    // Every read below builds the same ExecutionContext the take-exam and monitor
    // paths already use, rather than a second projection: the release list shows the
    // exam name, the course, the code, the window and the state, which is exactly
    // what E10 assembled. Consumers are named on each method.

    /**
     * The select, from and join conditions the release reads share.
     *
     * <p>One string rather than four copies, for the reason {@code ExamRepository}
     * keeps one: the projection has fifteen components of largely the same types, so
     * a hand-maintained copy that selected two of them in the wrong order would
     * compile, run, and quietly label every row with the wrong course.
     *
     * <p>It ends inside an open {@code where}, so every caller appends {@code and …}.
     */
    private static final String CONTEXT_SELECT = """
            select new server.db.projections.ExecutionContext(
                ex.id, ex.examVersionId, e.id, e.courseCode, c.name, v.name,
                v.durationMinutes, v.studentText, ex.code, ex.status,
                ex.openAt, ex.closeAt, ex.extraMinutes, ex.createdBy, e.authorId)
            from ExamExecution ex, ExamVersion v, Exam e, Course c
            where v.id = ex.examVersionId
              and e.id = v.examId
              and c.code = e.courseCode
            """;

    /** The states in which a code is spoken for. See {@link #isCodeInUse}. */
    private static final List<ExecutionStatus> CODE_HOLDING_STATES =
            List.of(ExecutionStatus.SCHEDULED, ExecutionStatus.LIVE);

    /**
     * Whether a 4-character code is currently spoken for (E9.1 — C-1, §5).
     *
     * <p>§5 makes code uniqueness a <b>service</b> rule rather than a schema one, because
     * MySQL has no partial unique index and the constraint is partial: a code is
     * legitimately reused once its execution is over ({@link #findByCode} exists for
     * exactly that reason, and the seed's fourth execution reuses the shape of the
     * first). This is the read that rule is enforced with, and the answer is
     * deliberately narrow.
     *
     * <p><b>Scheduled and live only.</b> A closed release cannot be entered, and a
     * cancelled one never could be, so neither holds its code hostage; keeping their
     * codes reserved would exhaust a 1.6-million-strong alphabet slowly and pointlessly
     * over a school's lifetime. What the rule has to guarantee is that a code a student
     * types identifies one release she could be sitting, and that is these two states.
     *
     * <p>Compared case-insensitively, like every other code read here (C-1).
     *
     * <p>Consumer: E9.1's code generation, which re-rolls on a collision inside the same
     * transaction as the insert.
     *
     * @param session the current session
     * @param code    the candidate code
     * @return {@code true} when a scheduled or live execution already uses it
     */
    public boolean isCodeInUse(Session session, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return session.createQuery("""
                        select count(ex) from ExamExecution ex
                        where lower(ex.code) = lower(:code) and ex.status in (:states)
                        """, Long.class)
                .setParameter("code", code.trim())
                .setParameterList("states", CODE_HOLDING_STATES)
                .getSingleResult() > 0;
    }

    /**
     * Takes an approved exam version out of the drawer (E9.1 — F5.1, S-2).
     *
     * <p>Always {@link ExecutionStatus#SCHEDULED}, even when the window opens in the
     * past: the status column is flipped by the scheduled check that also pushes the
     * transition, and a row inserted straight into {@code LIVE} would be live without
     * anybody having been told. There is exactly one place a release becomes live, and
     * it is not here.
     *
     * <p>The caller supplies the code, because the caller is the one holding the
     * {@link #isCodeInUse} answer from the same transaction.
     *
     * <p>Consumer: E9.1's {@code RELEASE_CREATE}.
     *
     * @param session       the current session
     * @param examVersionId the approved version being released; the service checks approval
     * @param code          the generated 4-character code (C-1)
     * @param openAt        when the window opens (S-15)
     * @param closeAt       when it shuts
     * @param createdBy     the releasing teacher, always the authenticated caller
     * @return the new execution's id
     */
    public long create(Session session, long examVersionId, String code,
                       Instant openAt, Instant closeAt, long createdBy) {
        ExamExecution execution = new ExamExecution(examVersionId, code, openAt, closeAt,
                ExecutionStatus.SCHEDULED, createdBy);
        session.persist(execution);
        session.flush();
        return execution.getId();
    }

    /**
     * Every release one teacher may act on (E9.4 — F5.4).
     *
     * <p>The scope is "she released it <b>or</b> she wrote the exam", which is the same
     * predicate {@code ExecutionContext.isOwnedBy} answers and the same one E11's extend
     * and monitor verbs gate on. Matching them is deliberate: a list built from a
     * narrower rule would hide releases she is allowed to close, and one built from a
     * wider rule would show her rows every action then refuses. Scoping in the query
     * rather than filtering afterwards means a caller that forgot the second step cannot
     * exist.
     *
     * <p><b>Cancelled releases are included</b>, unlike {@link #findContextsByExamAuthor}.
     * That read feeds the results screen, where a run nobody sat has no statistics to
     * show; this one is the release manager's own history, where "I called that one off"
     * is precisely the fact the teacher is looking for.
     *
     * <p>Consumer: E9.4's {@code RELEASE_LIST_GET}.
     *
     * @param session   the current session
     * @param teacherId the caller, from the session and never from a payload
     * @return her releases, soonest opening first; empty when she has released none
     */
    public List<ExecutionContext> findContextsForTeacher(Session session, long teacherId) {
        return session.createQuery(CONTEXT_SELECT + """
                          and (ex.createdBy = :teacherId or e.authorId = :teacherId)
                        order by ex.openAt desc, ex.id desc
                        """, ExecutionContext.class)
                .setParameter("teacherId", teacherId)
                .getResultList();
    }

    /**
     * Every scheduled release whose window opens at or before {@code limit} (E9.2).
     *
     * <p>One read for both halves of the scheduled check: the ones that should already
     * be live, and the ones opening soon enough to warn about. The caller partitions
     * them against its own {@link java.time.Clock}, which is what makes the whole
     * transition path testable by moving a test clock rather than by waiting.
     *
     * <p>Consumer: E9.2's {@code ReleaseScheduler}.
     *
     * @param session the current session
     * @param limit   the far edge of the window to look at
     * @return scheduled releases opening at or before it, soonest first
     */
    public List<ExecutionContext> findScheduledOpeningBy(Session session, Instant limit) {
        return session.createQuery(CONTEXT_SELECT + """
                          and ex.status = :scheduled and ex.openAt <= :limit
                        order by ex.openAt, ex.id
                        """, ExecutionContext.class)
                .setParameter("scheduled", ExecutionStatus.SCHEDULED)
                .setParameter("limit", limit)
                .getResultList();
    }

    /**
     * Every live release whose stored close time has passed (E9.2).
     *
     * <p>Filtered on {@code close_at} rather than on close plus extensions, because the
     * effective end is {@code close_at + extra_minutes} and no portable HQL adds a
     * column of minutes to a timestamp. The caller re-checks
     * {@link ExecutionContext#effectiveCloseAt()} and skips the extended ones, which is
     * the same arithmetic every other reader of an execution does and the reason it
     * lives on the projection. Over-fetching by the handful of extended releases costs
     * nothing; a query that guessed at the arithmetic would close an exam a teacher had
     * just added fifteen minutes to.
     *
     * <p>Consumer: E9.2's {@code ReleaseScheduler}.
     *
     * @param session the current session
     * @param limit   the moment to compare against, the server's clock reading
     * @return live releases whose stored window has ended, oldest first
     */
    public List<ExecutionContext> findLiveClosingBy(Session session, Instant limit) {
        return session.createQuery(CONTEXT_SELECT + """
                          and ex.status = :live and ex.closeAt <= :limit
                        order by ex.closeAt, ex.id
                        """, ExecutionContext.class)
                .setParameter("live", ExecutionStatus.LIVE)
                .setParameter("limit", limit)
                .getResultList();
    }

    /**
     * Moves a release from one state to another, if it is still in the first (E9.2/E9.3).
     *
     * <p>A status-guarded atomic UPDATE, not a read-modify-write, and for the same reason
     * finalising an attempt is one (§5): the writers genuinely race. The scheduled check
     * flipping a release to live and a teacher cancelling it are two threads acting on
     * the same row a second apart, and exactly one of them has to win. The loser sees
     * zero rows changed and reports honestly rather than overwriting a decision it never
     * saw.
     *
     * <p>That also makes both callers idempotent for free: closing a release twice, or a
     * retry after a dropped connection, changes nothing the second time and says so.
     *
     * <p>Deliberately does <b>not</b> go through the entity and its {@code lock_version}.
     * Optimistic locking answers "did the row change since I read it", which is the right
     * question for an extension a teacher typed a number into; here the question is "is
     * it still SCHEDULED", and the guard that answers it is in the statement.
     *
     * <p>Consumers: E9.2's opening transition, E9.3's cancel, and E9.3's close-early
     * before it hands the stragglers to {@code ExecutionCloseService}.
     *
     * @param session     the current session
     * @param executionId the release
     * @param from        the state it must still be in
     * @param to          the state to move it to
     * @return 1 when this caller won the transition, 0 when it was already elsewhere
     */
    public int transition(Session session, long executionId,
                          ExecutionStatus from, ExecutionStatus to) {
        return session.createMutationQuery("""
                        update ExamExecution set status = :to
                        where id = :executionId and status = :from
                        """)
                .setParameter("to", to)
                .setParameter("from", from)
                .setParameter("executionId", executionId)
                .executeUpdate();
    }
}
