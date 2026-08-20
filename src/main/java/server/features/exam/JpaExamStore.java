package server.features.exam;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.AttemptStatus;
import server.db.entities.User;
import server.db.projections.AnswerRow;
import server.db.projections.AttemptRecord;
import server.db.projections.AttemptRow;
import server.db.projections.ExecutionContext;
import server.db.projections.ParticipationCounts;
import server.db.projections.QuestionOutline;
import server.db.projections.TakeExamQuestion;
import server.db.repos.AttemptRepository;
import server.db.repos.CourseRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The production {@link ExamStore}: real transactions over the real repositories (E10/E11).
 *
 * <p>Thin on purpose. It owns no rules — every one of them is in
 * {@link AttemptService}, {@link ExtendService} or {@link MonitorService} — and does two
 * things: opens a transaction through {@link Transactions}, and hands the unit of work an
 * {@link ExamData} that is nothing but the repositories bound to that transaction's
 * session.
 *
 * <p>The repositories are stateless and shared; the session is not, which is why the
 * {@code ExamData} is created per call and documented as unusable afterwards.
 *
 * <p>Constructed with a {@link SessionFactory} rather than reaching for the singleton, so
 * the H2 and MySQL contract suites can drive exactly this class against a throwaway
 * database — the JPA half of the E10 rules is tested, not assumed.
 */
public final class JpaExamStore implements ExamStore {

    private final SessionFactory factory;

    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final CourseRepository courses = new CourseRepository();
    private final UserRepository users = new UserRepository();

    /** @param factory the session factory to open transactions on */
    public JpaExamStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<ExamData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new SessionData(session)));
    }

    /** The repositories bound to one session. Alive for exactly one {@link #inTx} call. */
    private final class SessionData implements ExamData {

        private final Session session;

        private SessionData(Session session) {
            this.session = session;
        }

        @Override
        public List<ExecutionContext> executionsByCode(String code) {
            return executions.findContextsByCode(session, code);
        }

        @Override
        public Optional<ExecutionContext> executionById(long executionId) {
            return executions.findContext(session, executionId);
        }

        @Override
        public Optional<StudentIdentity> user(long userId) {
            return users.findById(session, userId).map(SessionData::toIdentity);
        }

        @Override
        public boolean isEnrolled(long studentId, String courseCode) {
            return courses.isEnrolled(session, studentId, courseCode);
        }

        @Override
        public List<TakeExamQuestion> questionsOf(long examVersionId) {
            return questions.findForTakeExam(session, examVersionId);
        }

        @Override
        public List<QuestionOutline> outlineOf(long examVersionId) {
            return questions.findOutlineForTakeExam(session, examVersionId);
        }

        @Override
        public int questionCountOf(long examVersionId) {
            return questions.countForTakeExam(session, examVersionId);
        }

        @Override
        public boolean isOnPaper(long examVersionId, long questionVersionId) {
            return questions.isOnTakeExamPaper(session, examVersionId, questionVersionId);
        }

        @Override
        public Optional<AttemptRecord> attemptOf(long executionId, long studentId) {
            return attempts.findRecord(session, executionId, studentId);
        }

        @Override
        public Optional<AttemptRecord> attemptById(long attemptId) {
            return attempts.findRecordById(session, attemptId);
        }

        @Override
        public AttemptRecord createAttempt(long executionId, long studentId, Instant startedAt) {
            return attempts.createAttempt(session, executionId, studentId, startedAt);
        }

        @Override
        public int finalizeAttempt(long attemptId, AttemptStatus status, Instant endedAt, int actualMinutes) {
            return attempts.finalizeAttempt(session, attemptId, status, endedAt, actualMinutes);
        }

        @Override
        public List<AttemptRecord> liveAttemptsOf(long executionId) {
            return attempts.findInProgressAt(session, executionId);
        }

        @Override
        public List<AttemptRecord> allLiveAttempts() {
            return attempts.findAllInProgress(session);
        }

        @Override
        public List<AnswerRow> answersOf(long attemptId) {
            return attempts.findAnswers(session, attemptId);
        }

        @Override
        public void upsertAnswer(long attemptId, long questionVersionId, Byte selected, Instant savedAt) {
            attempts.upsertAnswer(session, attemptId, questionVersionId, selected, savedAt);
        }

        @Override
        public int countAnswered(long attemptId) {
            return attempts.countAnswered(session, attemptId);
        }

        @Override
        public ParticipationCounts participationOf(long executionId) {
            return attempts.countParticipation(session, executionId);
        }

        @Override
        public List<AttemptRow> rowsOf(long executionId) {
            return attempts.findRows(session, executionId);
        }

        @Override
        public Map<Long, Integer> answeredCountsOf(long executionId) {
            return attempts.countAnsweredByAttempt(session, executionId);
        }

        @Override
        public int addExtraMinutes(long executionId, int minutes) {
            return executions.addExtraMinutes(session, executionId, minutes);
        }

        @Override
        public void closeExecution(long executionId, ParticipationCounts counts) {
            executions.freezeParticipation(session, executionId, counts);
        }

        private static StudentIdentity toIdentity(User user) {
            return new StudentIdentity(user.getId(), user.getFullName(), user.getNationalId());
        }
    }
}
