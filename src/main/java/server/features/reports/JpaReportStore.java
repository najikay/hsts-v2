package server.features.reports;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.UserRole;
import server.db.projections.CourseSummary;
import server.db.projections.ExecutionReport;
import server.db.projections.PersonRef;
import server.db.projections.SchoolExam;
import server.db.repos.AttemptRepository;
import server.db.repos.CourseRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.UserRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The production {@link ReportStore}: real transactions over the real repositories (E15.3).
 *
 * <p>Thin on purpose, exactly as {@code JpaTeacherResultsStore} is. It owns no rules — every one
 * of them is in the strategies and in {@link ReportEngine} — and does two things: opens a
 * transaction through {@link Transactions}, and hands the unit of work the repositories bound to
 * that transaction's session.
 *
 * <p>Constructed with a {@link SessionFactory} rather than reaching for the singleton, so the H2
 * and MySQL contract suites can drive exactly this class against a throwaway database.
 *
 * <p>The two role constants below are the only place this feature spells out what a "teacher" and
 * a "student" are, and they are the <b>stored</b> roles rather than wire ones. A coordinator is
 * stored as a teacher and is listed as one, which is right: coordinator-ness is a row in
 * {@code coordinators} (section 5), and she writes exams like any other teacher.
 */
public final class JpaReportStore implements ReportStore {

    private final SessionFactory factory;

    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final UserRepository users = new UserRepository();
    private final server.db.repos.GradeRepository grades = new server.db.repos.GradeRepository();
    private final CourseRepository courseRepository = new CourseRepository();
    private final ExamRepository exams = new ExamRepository();

    /** @param factory the session factory to open transactions on */
    public JpaReportStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<ReportData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new SessionData(session)));
    }

    /** The repositories bound to one session. Alive for exactly one {@link #inTx} call. */
    private final class SessionData implements ReportData {

        private final Session session;

        private SessionData(Session session) {
            this.session = session;
        }

        @Override
        public List<PersonRef> teachers() {
            return users.findByRole(session, UserRole.TEACHER);
        }

        @Override
        public Optional<PersonRef> teacher(long teacherId) {
            return users.findRefByRole(session, teacherId, UserRole.TEACHER);
        }

        @Override
        public List<ExecutionReport> executionsByAuthor(long teacherId) {
            return executions.findReportRowsByAuthor(session, teacherId);
        }

        @Override
        public List<CourseSummary> courses() {
            return courseRepository.findAllSummaries(session);
        }

        @Override
        public Optional<CourseSummary> course(String courseCode) {
            return courseRepository.findSummary(session, courseCode);
        }

        @Override
        public List<ExecutionReport> executionsByCourse(String courseCode) {
            return executions.findReportRowsByCourse(session, courseCode);
        }

        @Override
        public List<PersonRef> students() {
            return users.findByRole(session, UserRole.STUDENT);
        }

        @Override
        public Optional<PersonRef> student(long studentId) {
            return users.findRefByRole(session, studentId, UserRole.STUDENT);
        }

        @Override
        public List<ExecutionReport> executionsByStudent(long studentId) {
            return executions.findReportRowsByStudent(session, studentId);
        }

        @Override
        public Map<Long, Integer> approvedScoresByStudent(long studentId) {
            return grades.findApprovedScoresByStudent(session, studentId);
        }

        @Override
        public Map<String, Integer> reportableCounts(ExecutionRepository.ReportGrouping grouping) {
            return executions.countReportableBy(session, grouping);
        }

        @Override
        public Map<Long, Integer> participantsByExecution(Collection<Long> executionIds) {
            return attempts.countAttemptsByExecution(session, executionIds);
        }

        @Override
        public List<SchoolExam> allExams() {
            return exams.findAllSummaries(session);
        }

        @Override
        public List<ExecutionReport> allClosedSittings() {
            return executions.findAllReportRows(session);
        }
    }
}
