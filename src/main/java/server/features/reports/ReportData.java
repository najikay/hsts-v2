package server.features.reports;

import server.db.projections.CourseSummary;
import server.db.projections.ExecutionReport;
import server.db.projections.PersonRef;
import server.db.projections.SchoolExam;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reads a report needs, bound to one transaction (Logic tier, E15.3).
 *
 * <p>The {@code TeacherResultsData} pattern, for the same reason: a rule is written inside the
 * transaction that reads the truth it decides from. Handed out by {@link ReportStore} and valid
 * only for the length of one {@code inTx} call.
 *
 * <p>Every method is a read, and that is F9.3 made structural rather than promised. The
 * principal's whole feature is this interface, and there is no method on it that could change a
 * row; a mutating verb for her role cannot be written without first adding a method here, which
 * is a visible act in a reviewed file rather than a line inside a handler.
 *
 * <h2>Three methods per dimension, on purpose</h2>
 *
 * <p>The seam is explicit — {@code teachers}/{@code teacher}/{@code executionsByAuthor}, and the
 * same three for courses and students — rather than one {@code executionsFor(dimension, id)}
 * that switches internally. A switch inside the store would move the dimension knowledge from
 * the strategies (where it is one small class each, tested) into the data layer (where it is one
 * growing method, shared), which is precisely the shape S-37 asks us not to build.
 *
 * <p>The honest consequence, stated rather than glossed: <b>a fourth dimension adds three
 * methods here</b> and their implementations in {@link JpaReportStore}, alongside its strategy
 * class and its registration line. Nothing else moves — not the engine, not a DTO, not a
 * handler, not the screen, not the summary arithmetic. That is the extensibility claim measured
 * honestly, and E15's report says so in the same words.
 *
 * <p>Two implementations, deliberately: {@link JpaReportStore} over the real repositories, driven
 * against H2 and MySQL, and an in-memory fake in the tests, which is what lets every strategy and
 * the whole summary be proven without a database (TEAM_SPLIT section 3.2).
 */
public interface ReportData {

    // ===================== BY_TEACHER ====================================

    /**
     * Every teacher in the school, by name.
     *
     * <p>School-wide, because the principal's scope is (spec 7.3.1). Teachers with no closed
     * sitting are included; the count beside them is how the picker says so.
     *
     * @return the teachers; empty only in a school with none
     */
    List<PersonRef> teachers();

    /**
     * One teacher, by id.
     *
     * @param teacherId the subject of the report
     * @return her reference, or empty when no user with that id holds the teacher role
     */
    Optional<PersonRef> teacher(long teacherId);

    /**
     * Every reportable sitting of every exam this teacher <b>wrote</b>, oldest first.
     *
     * @param teacherId the subject of the report
     * @return her sittings; empty when none of her exams has closed with statistics
     */
    List<ExecutionReport> executionsByAuthor(long teacherId);

    // ===================== BY_COURSE =====================================

    /**
     * Every course in the school, by code.
     *
     * @return the courses; empty only before the reference data is seeded
     */
    List<CourseSummary> courses();

    /**
     * One course, by code.
     *
     * @param courseCode the two-character code
     * @return the course, or empty when there is no such row
     */
    Optional<CourseSummary> course(String courseCode);

    /**
     * Every reportable sitting of every exam in this course, oldest first.
     *
     * @param courseCode the two-character code
     * @return its sittings; empty when none has closed with statistics
     */
    List<ExecutionReport> executionsByCourse(String courseCode);

    // ===================== BY_STUDENT ====================================

    /**
     * Every student in the school, by name.
     *
     * @return the students; empty only in a school with none
     */
    List<PersonRef> students();

    /**
     * One student, by id.
     *
     * @param studentId the subject of the report
     * @return her reference, or empty when no user with that id holds the student role
     */
    Optional<PersonRef> student(long studentId);

    /**
     * Every reportable sitting this student sat, oldest first.
     *
     * <p>Membership is an attempt, not a grade: a paper that was never marked still means she
     * was in the room, and the sitting's frozen figures are still the class she sat with.
     *
     * @param studentId the subject of the report
     * @return her sittings; empty when she has sat none that closed with statistics
     */
    List<ExecutionReport> executionsByStudent(long studentId);

    // ===================== Shared by every dimension =====================

    /**
     * How many reportable sittings each subject of one dimension has (E15.5).
     *
     * <p>One query per dimension rather than one per subject. The keys match
     * {@code ReportSubject.id}: a user id in decimal, or a course code.
     *
     * @param grouping which population to count
     * @return subject id to count; a subject with none is absent rather than zero
     */
    Map<String, Integer> reportableCounts(server.db.repos.ExecutionRepository.ReportGrouping grouping);

    /**
     * How many students sat each of these executions.
     *
     * <p>A {@code COUNT} over attempts, so it counts the student whose paper was never marked
     * too. That is deliberately a different number from the statistics' population, and the gap
     * between them is something a principal should be able to see rather than something the
     * report quietly closes.
     *
     * @param executionIds the executions to count
     * @return execution id to attempts started; an execution nobody sat is absent
     */
    Map<Long, Integer> participantsByExecution(Collection<Long> executionIds);

    // ===================== The data browser (E15.2) ======================
    //
    // Two more reads, on this interface rather than on a second seam of their own, and that is
    // the point. S-7's structural claim is "the principal's whole feature is an interface with
    // no write method on it", and it stays true only for as long as everything her role can
    // reach arrives through here. A second data interface beside this one would be a second
    // file to check, and the first place a write could appear without anybody noticing.

    /**
     * Every exam in the school, for the data browser's Exams tab (E15.2 - F9.3).
     *
     * <p>School-wide and unfiltered. The screen narrows what it shows; nothing the client sends
     * decides what is read (spec 7.3.1).
     *
     * @return the exams ordered by display id; empty only before any exam is written
     */
    List<SchoolExam> allExams();

    /**
     * Every closed sitting in the school that has its statistics frozen (E15.2 - F9.3).
     *
     * <p>The same {@code REPORTABLE} definition the three report populations use, so the browse
     * and the reports cannot disagree about which sittings exist. Cancelled runs are excluded
     * here because they are excluded there (H15.2 ⚑).
     *
     * @return the sittings, newest first; empty when nothing has been sat and fully marked
     */
    List<ExecutionReport> allClosedSittings();
}
