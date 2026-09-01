package server.features.release;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.ExecutionStatus;
import server.db.projections.ExamVersionContext;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.repos.AttemptRepository;
import server.db.repos.CourseRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The production {@link ReleaseStore}: real transactions over the real repositories (E9).
 *
 * <p>Thin on purpose, exactly as {@code JpaExamStore} is. It owns no rules — every one of
 * them is in {@link ReleaseService} or {@link ReleaseScheduler} — and does two things: opens
 * a transaction through {@link Transactions}, and hands the unit of work a
 * {@link ReleaseData} that is nothing but the repositories bound to that transaction's
 * session.
 *
 * <p>The repositories are stateless and shared; the session is not, which is why the
 * {@code ReleaseData} is created per call and documented as unusable afterwards.
 *
 * <p>Constructed with a {@link SessionFactory} rather than reaching for the singleton, so
 * the H2 and MySQL contract suites can drive exactly this class against a throwaway
 * database. That matters more here than usual: the two things E9 gets wrong if the JPA half
 * is assumed rather than tested are the partial code-uniqueness read and the guarded status
 * transition, and neither can fail in an in-memory double.
 */
public final class JpaReleaseStore implements ReleaseStore {

    private final SessionFactory factory;

    private final ExecutionRepository executions = new ExecutionRepository();
    private final ExamRepository exams = new ExamRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final CourseRepository courses = new CourseRepository();

    /** @param factory the session factory to open transactions on */
    public JpaReleaseStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<ReleaseData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new SessionData(session)));
    }

    /** The repositories bound to one session. Alive for exactly one {@link #inTx} call. */
    private final class SessionData implements ReleaseData {

        private final Session session;

        private SessionData(Session session) {
            this.session = session;
        }

        @Override
        public List<ExamVersionContext> releasableVersionsFor(long teacherId) {
            return exams.findReleasableForTeacher(session, teacherId);
        }

        @Override
        public java.util.Map<Long, Integer> questionCountsByVersion(
                java.util.Collection<Long> versionIds) {
            return exams.countQuestionsByVersion(session, versionIds);
        }

        @Override
        public boolean hasAnyExam(long teacherId) {
            return exams.hasAnyExamInTaughtCourses(session, teacherId);
        }

        @Override
        public Optional<ExamVersionContext> versionById(long examVersionId) {
            return exams.findVersionContext(session, examVersionId);
        }

        @Override
        public boolean teaches(long teacherId, String courseCode) {
            return courses.teaches(session, teacherId, courseCode);
        }

        @Override
        public boolean isCodeInUse(String code) {
            return executions.isCodeInUse(session, code);
        }

        @Override
        public long createExecution(long examVersionId, String code, Instant openAt,
                                    Instant closeAt, long createdBy) {
            return executions.create(session, examVersionId, code, openAt, closeAt, createdBy);
        }

        @Override
        public Optional<ExecutionContext> executionById(long executionId) {
            return executions.findContext(session, executionId);
        }

        @Override
        public List<ExecutionContext> executionsFor(long teacherId) {
            return executions.findContextsForTeacher(session, teacherId);
        }

        @Override
        public Map<Long, ParticipationCounts> participationOf(Collection<Long> executionIds) {
            return attempts.countParticipationByExecution(session, executionIds);
        }

        @Override
        public List<ExecutionContext> scheduledOpeningBy(Instant limit) {
            return executions.findScheduledOpeningBy(session, limit);
        }

        @Override
        public List<ExecutionContext> liveClosingBy(Instant limit) {
            return executions.findLiveClosingBy(session, limit);
        }

        @Override
        public int transition(long executionId, ExecutionStatus from, ExecutionStatus to) {
            return executions.transition(session, executionId, from, to);
        }

        @Override
        public List<Long> enrolledStudents(String courseCode) {
            return courses.findEnrolledStudentIds(session, courseCode);
        }
    }
}
