package server.db.repos;

import org.hibernate.Session;
import server.db.projections.CourseSummary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Reads over {@code courses} and its two membership tables (E2.11).
 */
public final class CourseRepository {

    /**
     * Every course this user is attached to, whether by teaching it or by being enrolled in
     * it.
     *
     * <p><b>Both, not either.</b> {@code UserRecord}'s javadoc describes its courses as
     * "taught (teacher/coordinator) <em>or</em> enrolled in (student)", which reads as a
     * choice made by role. It is not: a teacher can be enrolled in another teacher's course,
     * and the nav has to show both. The union is taken here so no caller has to remember it.
     *
     * <p>Two queries rather than one HQL union: the union keeps the query portable across H2
     * and MySQL, and the merge is where the ordering is made deterministic.
     *
     * <p>Consumer: {@code RepositoryUserDirectory} building a login result.
     *
     * @param session the current session
     * @param userId  the user's internal id
     * @return the courses, by code, without duplicates
     */
    public List<CourseSummary> findForUser(Session session, long userId) {
        LinkedHashSet<CourseSummary> merged = new LinkedHashSet<>();
        merged.addAll(query(session, """
                select new server.db.projections.CourseSummary(c.code, c.name)
                from Course c, CourseTeacher ct
                where ct.id.courseCode = c.code and ct.id.teacherId = :userId
                order by c.code
                """, userId));
        merged.addAll(query(session, """
                select new server.db.projections.CourseSummary(c.code, c.name)
                from Course c, Enrollment e
                where e.id.courseCode = c.code and e.id.studentId = :userId
                order by c.code
                """, userId));
        return List.copyOf(merged);
    }

    /**
     * Whether this student may sit an exam of this course (S-15, F6.1).
     *
     * <p>A separate read rather than a scan of {@link #findForUser}, because that method
     * deliberately unions teaching and enrolment and a teacher who happens to teach a
     * course is emphatically not enrolled in it. The take-exam gate needs the narrow
     * question, and asking the wide one and filtering afterwards is how the wrong answer
     * eventually gets used.
     *
     * <p>Consumer: E10 join-by-code, which refuses a student who is not on the course with
     * its own message rather than a generic "no".
     *
     * @param session    the current session
     * @param studentId  the student
     * @param courseCode the 2-character course code
     * @return {@code true} when there is an enrolment row
     */
    public boolean isEnrolled(Session session, long studentId, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return false;
        }
        return session.createQuery("""
                        select count(e) from Enrollment e
                        where e.id.studentId = :studentId and e.id.courseCode = :courseCode
                        """, Long.class)
                .setParameter("studentId", studentId)
                .setParameter("courseCode", courseCode)
                .getSingleResult() > 0;
    }

    /**
     * Whether this teacher teaches this course (E16.9 — P-5).
     *
     * <p>The ownership half of every teacher verb in the study bot: the role gate
     * says "a teacher", this says "a teacher <em>of this course</em>", and the two
     * together are what stops one teacher managing another's bot. Deliberately the
     * narrow question, for the same reason {@link #isEnrolled} is: a teacher who
     * happens to be enrolled in a colleague's course is emphatically not one of its
     * teachers, and answering the wide question and filtering afterwards is how the
     * wrong answer eventually gets used.
     *
     * <p>Consumer: E16's bot management service, on every teacher verb.
     *
     * @param session    the current session
     * @param teacherId  the caller
     * @param courseCode the 2-character course code
     * @return {@code true} when there is a {@code course_teachers} row
     */
    public boolean teaches(Session session, long teacherId, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return false;
        }
        return session.createQuery("""
                        select count(ct) from CourseTeacher ct
                        where ct.id.teacherId = :teacherId and ct.id.courseCode = :courseCode
                        """, Long.class)
                .setParameter("teacherId", teacherId)
                .setParameter("courseCode", courseCode)
                .getSingleResult() > 0;
    }

    /**
     * Whether this teacher coordinates this subject (E8.1 — F4.1, S-1).
     *
     * <p>The half of every approval verb that the role gate cannot answer:
     * {@code requireRole(COORDINATOR)} says "a coordinator", this says "the coordinator
     * <em>of this subject</em>", and the two together are what stops the Mathematics
     * coordinator approving a Computer Science exam. It is the sibling of {@link #teaches}
     * one level up the tree — a subject owns courses, a course owns exams — and it is
     * deliberately the narrow question for the same reason: a coordinator who also teaches a
     * course in somebody else's subject is emphatically not that subject's coordinator.
     *
     * <p>The {@code coordinators} primary key is the subject alone (§5), so a subject has at
     * most one row here and this can never be ambiguous. That is also why a
     * {@code teacher → subjects} read is not enough on its own: the useful direction for a
     * guard is "may this person act on this subject", and asking the wide question and
     * filtering afterwards is how the wrong answer eventually gets used.
     *
     * <p>Consumers: {@code Authorization.requireCoordinatorOf}, through the directory the
     * server installs at assembly, and E8's approval service on every mutation.
     *
     * <p>See {@link #findCoordinatedCourseCodes}, which asks the other direction of the same
     * relationship for E6's bank scope. The two were written independently on 2026-08-21 and
     * checked against each other afterwards rather than left to drift; that javadoc carries the
     * reasoning for keeping both.
     *
     * @param session     the current session
     * @param teacherId   the caller, from the session and never from a payload
     * @param subjectCode the 2-character subject code
     * @return {@code true} when there is a {@code coordinators} row binding the two
     */
    public boolean coordinates(Session session, long teacherId, String subjectCode) {
        if (subjectCode == null || subjectCode.isBlank()) {
            return false;
        }
        return session.createQuery("""
                        select count(co) from Coordinator co
                        where co.teacherId = :teacherId and co.subjectCode = :subjectCode
                        """, Long.class)
                .setParameter("teacherId", teacherId)
                .setParameter("subjectCode", subjectCode)
                .getSingleResult() > 0;
    }

    /**
     * Who coordinates this subject (E8.2 — S-1, F4).
     *
     * <p>The recipient of every "an exam is waiting for you" notification. It is a single
     * {@code Optional} rather than a list because the {@code coordinators} primary key is the
     * subject alone (§5): one coordinator per subject is enforced by the schema, so a method
     * returning a list here would be asserting a looseness the table does not have, and the
     * first caller to loop over it would be writing a loop that can only ever run once.
     *
     * <p>Empty is a real answer, not an error: a subject can exist before anybody is made its
     * coordinator. The caller warns rather than throws, because a submission nobody can
     * approve is an administrative gap to fix, not a reason to fail the teacher's submit.
     *
     * <p>Consumer: E8.2's submit hook, notifying the coordinator.
     *
     * @param session     the current session
     * @param subjectCode the 2-character subject code
     * @return the coordinating teacher's user id, or empty when the subject has none
     */
    public Optional<Long> findCoordinatorOf(Session session, String subjectCode) {
        if (subjectCode == null || subjectCode.isBlank()) {
            return Optional.empty();
        }
        return session.createQuery("""
                        select co.teacherId from Coordinator co
                        where co.subjectCode = :subjectCode
                        """, Long.class)
                .setParameter("subjectCode", subjectCode)
                .uniqueResultOptional();
    }

    /**
     * The subject a course belongs to (E8.1).
     *
     * <p>One column, because that is the whole question an approval guard asks: an exam
     * names a course, a coordinator owns a subject, and this is the edge between them.
     * Loading the {@code Course} row to read two characters is the sort of thing that is
     * invisible until it is inside a queue render.
     *
     * <p>Consumer: E8's approval service, resolving the subject of the exam being decided.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @return its subject code, or empty when there is no such course
     */
    public Optional<String> findSubjectOf(Session session, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return Optional.empty();
        }
        return session.createQuery(
                        "select c.subjectCode from Course c where c.code = :courseCode", String.class)
                .setParameter("courseCode", courseCode)
                .uniqueResultOptional();
    }

    /**
     * The other teachers of a course (E16.9 — F12.3).
     *
     * <p>Who a "the study bot sources changed" notification goes to. Excludes the
     * person who made the change, because telling somebody what they just did is
     * noise, and noise is what makes people stop reading their notifications.
     *
     * <p>Consumer: E16's bot management service.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @param excluding  the teacher who made the change
     * @return the other teachers' ids, ascending; empty for a solo-taught course
     */
    public List<Long> findOtherTeachers(Session session, String courseCode, long excluding) {
        if (courseCode == null || courseCode.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        select ct.id.teacherId from CourseTeacher ct
                        where ct.id.courseCode = :courseCode and ct.id.teacherId <> :excluding
                        order by ct.id.teacherId
                        """, Long.class)
                .setParameter("courseCode", courseCode)
                .setParameter("excluding", excluding)
                .getResultList();
    }

    /**
     * A course's display name (E16.9).
     *
     * <p>One column, because that is all the caller wants: the bot's screens put the
     * course name in a header and in a notification sentence, and loading the whole
     * row to read one string is the sort of thing that is invisible until it is in a
     * loop.
     *
     * <p>Consumers: E16's bot services, for headers and notification copy.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @return its name, or empty when there is no such course
     */
    public Optional<String> findName(Session session, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return Optional.empty();
        }
        return session.createQuery(
                        "select c.name from Course c where c.code = :courseCode", String.class)
                .setParameter("courseCode", courseCode)
                .uniqueResultOptional();
    }

    /**
     * Every course in the school, by code (E15.3 - F9.4, spec 7.3.1).
     *
     * <p>Added under TEAM_SPLIT rule 5. The one read here with no user in its signature, and
     * deliberately so: the principal's "same course" report is school-wide, and a variant taking
     * a caller id would be a scope this feature does not have and could not honestly fill in.
     * Who may call it is decided by the role gate on {@code REPORT_SUBJECTS_GET}, which is the
     * whole of F9.3's authorization story.
     *
     * <p>A course with no exams is included and the picker says it has nothing to report. That
     * is E15.5's degenerate case answered in the picker rather than after a click (section 4.1).
     *
     * <p>Consumer: E15.3's {@code JpaReportStore}, for the BY_COURSE picker.
     *
     * @param session the current session
     * @return every course, by code
     */
    public List<CourseSummary> findAllSummaries(Session session) {
        return session.createQuery("""
                        select new server.db.projections.CourseSummary(c.code, c.name)
                        from Course c order by c.code
                        """, CourseSummary.class)
                .getResultList();
    }

    /**
     * One course as a report names it (E15.3).
     *
     * <p>{@link #findName} answers with a string, which is right for a header; a report's subject
     * needs the code beside the name so two courses with similar names are told apart in the
     * picker, and rebuilding the pair at each call site is how the two would eventually disagree.
     *
     * @param session    the current session
     * @param courseCode the 2-character course code
     * @return the course, or empty when there is no such row
     */
    public Optional<CourseSummary> findSummary(Session session, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return Optional.empty();
        }
        return session.createQuery("""
                        select new server.db.projections.CourseSummary(c.code, c.name)
                        from Course c where c.code = :courseCode
                        """, CourseSummary.class)
                .setParameter("courseCode", courseCode.strip())
                .uniqueResultOptional();
    }

    // ===================== E6 bank scope ==================================

    /**
     * The course codes a teacher may author in (E6 - S-5).
     *
     * <p>{@link #findForUser} cannot serve this: it merges taught courses with <em>enrolled</em>
     * ones, because a home screen shows both. Authoring scope must not, or a teacher who is also
     * enrolled somewhere could write questions into that course's bank.
     *
     * <p>Consumer: E6.5's {@code BANK_LIST} and E6.1's create.
     *
     * @param session   the current session
     * @param teacherId the caller
     * @return her course codes, sorted, possibly empty
     */
    public List<String> findTaughtCourseCodes(Session session, long teacherId) {
        return session.createQuery("""
                        select ct.id.courseCode from CourseTeacher ct
                        where ct.id.teacherId = :userId
                        order by ct.id.courseCode
                        """, String.class)
                .setParameter("userId", teacherId)
                .getResultList();
    }

    /**
     * The course codes a coordinator reaches: every course of every subject she coordinates.
     *
     * <h2>Why this is not "the courses she teaches"</h2>
     *
     * <p>Because a coordinator need not teach anything. {@code rina.barak} holds a
     * {@code coordinators} row for subject 10 and <b>zero {@code course_teachers} rows</b>,
     * deliberately, so that an implementation deriving coordinator-ness from the wrong table
     * fails (see {@code FacultySection}, roster decision 2026-08-20). Scoping her bank by
     * teaching would show one of the five starred demo accounts an empty question bank, while
     * F4.1 requires her to preview exams built from those very questions.
     *
     * <p>A dual-hat coordinator who also teaches, like {@code michal.sharon}, reaches her
     * subject's courses through this method and her taught ones through
     * {@link #findTaughtCourseCodes}; for her the two sets coincide. The service unions them
     * rather than choosing, so neither hat can take something away.
     *
     * <p><b>Sibling of {@link #coordinates}, and deliberately not a duplicate of it.</b> Both
     * encode the coordinator-to-subject relationship, and they were written the same afternoon by
     * two people who could not see each other's branch, so the overlap was checked rather than
     * assumed. {@link #coordinates} is a <em>guard</em>: may this person act on this subject, one
     * subject, yes or no. This is an <em>expansion</em>: which courses does she reach, all of them,
     * in one join. Building this from that would cost one query per subject; building that from
     * this does not work at all, since a guard needs a subject and this returns courses. Both
     * survive. A change to how coordination is stored touches both, which is why they name each
     * other.
     *
     * <p>Consumer: E6.5's {@code BANK_LIST}. Ruled by the lead 2026-08-21, BANK_WIRE_CONTRACT §7.3.
     *
     * @param session       the current session
     * @param coordinatorId the caller
     * @return the course codes, sorted, possibly empty
     */
    public List<String> findCoordinatedCourseCodes(Session session, long coordinatorId) {
        return session.createQuery("""
                        select c.code from Course c, Coordinator co
                        where co.subjectCode = c.subjectCode and co.teacherId = :userId
                        order by c.code
                        """, String.class)
                .setParameter("userId", coordinatorId)
                .getResultList();
    }

    /**
     * Everyone enrolled in one course (E9.2 — F11.1).
     *
     * <p>Who a "your exam opens soon" notification goes to. Ids only, deliberately: the
     * notifier routes to ids and nothing here needs a name, so the read that answers "who"
     * cannot become a read that hands a caller a table of students it had no reason to
     * load.
     *
     * <p>Ordered so the recipient list of a given course is the same on every run, which is
     * what lets a notification test assert a list rather than a set.
     *
     * <p>Consumer: E9.2's {@code ReleaseScheduler}, for the RELEASE_OPENING_SOON notice.
     *
     * @param session    the current session
     * @param courseCode the two-character course code
     * @return the enrolled students' ids, ascending; empty for an unknown or empty course
     */
    public List<Long> findEnrolledStudentIds(Session session, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return List.of();
        }
        return session.createQuery("""
                        select e.id.studentId from Enrollment e
                        where e.id.courseCode = :courseCode
                        order by e.id.studentId
                        """, Long.class)
                .setParameter("courseCode", courseCode)
                .getResultList();
    }

    private static List<CourseSummary> query(Session session, String hql, long userId) {
        return session.createQuery(hql, CourseSummary.class)
                .setParameter("userId", userId)
                .getResultList();
    }
}
