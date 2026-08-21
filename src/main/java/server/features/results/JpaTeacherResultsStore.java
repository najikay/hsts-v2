package server.features.results;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.ExamExecution;
import server.db.entities.ExecutionStats;
import server.db.projections.AuthoredExam;
import server.db.projections.ExecutionContext;
import server.db.projections.StudentResultRow;
import server.db.repos.AttemptRepository;
import server.db.repos.ExamRepository;
import server.db.repos.ExecutionRepository;
import server.db.repos.GradeRepository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The production {@link TeacherResultsStore}: real transactions over the real repositories
 * (E14.1).
 *
 * <p>Thin on purpose, exactly as {@code JpaExamStore} is. It owns no rules — every one of them
 * is in {@link TeacherResultsService} — and does two things: opens a transaction through
 * {@link Transactions}, and hands the unit of work the repositories bound to that
 * transaction's session.
 *
 * <p>Constructed with a {@link SessionFactory} rather than reaching for the singleton, so the
 * H2 and MySQL contract suites can drive exactly this class against a throwaway database.
 */
public final class JpaTeacherResultsStore implements TeacherResultsStore {

    private final SessionFactory factory;

    private final ExamRepository exams = new ExamRepository();
    private final ExecutionRepository executions = new ExecutionRepository();
    private final AttemptRepository attempts = new AttemptRepository();
    private final GradeRepository grades = new GradeRepository();

    /** @param factory the session factory to open transactions on */
    public JpaTeacherResultsStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<TeacherResultsData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new SessionData(session)));
    }

    /** The repositories bound to one session. Alive for exactly one {@link #inTx} call. */
    private final class SessionData implements TeacherResultsData {

        private final Session session;

        private SessionData(Session session) {
            this.session = session;
        }

        @Override
        public List<AuthoredExam> examsAuthoredBy(long teacherId) {
            return exams.findAuthoredSummaries(session, teacherId);
        }

        @Override
        public List<ExecutionContext> executionsOfExamsAuthoredBy(long teacherId) {
            return executions.findContextsByExamAuthor(session, teacherId);
        }

        @Override
        public Optional<ExecutionContext> executionById(long executionId) {
            return executions.findContext(session, executionId);
        }

        @Override
        public Set<Long> executionsWithStatistics(Collection<Long> executionIds) {
            return new LinkedHashSet<>(executions.findIdsWithStatistics(session, executionIds));
        }

        @Override
        public Map<Long, Integer> participantsByExecution(Collection<Long> executionIds) {
            return attempts.countAttemptsByExecution(session, executionIds);
        }

        @Override
        public Map<Long, Integer> gradedByExecution(Collection<Long> executionIds) {
            return grades.countGradesByExecution(session, executionIds);
        }

        @Override
        public Optional<ExecutionStats> statisticsOf(long executionId) {
            return executions.findById(session, executionId).map(ExamExecution::getStats);
        }

        @Override
        public List<StudentResultRow> resultRowsOf(long executionId) {
            return grades.findResultRows(session, executionId);
        }
    }
}
