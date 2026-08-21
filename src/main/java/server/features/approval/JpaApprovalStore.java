package server.features.approval;

import common.dto.approval.PreviewAnswerRow;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import server.db.Transactions;
import server.db.entities.ExamVersion;
import server.db.entities.QuestionVersion;
import server.db.projections.ExamVersionContext;
import server.db.projections.TakeExamQuestion;
import server.db.repos.CourseRepository;
import server.db.repos.ExamRepository;
import server.db.repos.QuestionRepository;
import server.db.repos.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The production {@link ApprovalStore}: real transactions over the real repositories (E8).
 *
 * <p>Thin on purpose. It owns no rules — every one of them is in {@link ApprovalService} —
 * and does two things: opens a transaction through {@link Transactions}, and hands the unit
 * of work an {@link ApprovalData} that is the repositories bound to that transaction's
 * session.
 *
 * <p>The one thing it does beyond delegation is map {@link QuestionVersion} to
 * {@link PreviewAnswerRow}, and that is where it belongs: the entity is the widest
 * key-bearing type in the system, and this class is the boundary where it stops travelling.
 * Two lines here mean the answer key exists in exactly one shape everywhere above it.
 *
 * <p>Constructed with a {@link SessionFactory} rather than reaching for the singleton, so the
 * H2 and MySQL contract suites can drive exactly this class against a throwaway database.
 */
public final class JpaApprovalStore implements ApprovalStore {

    private final SessionFactory factory;

    private final ExamRepository exams = new ExamRepository();
    private final QuestionRepository questions = new QuestionRepository();
    private final CourseRepository courses = new CourseRepository();
    private final UserRepository users = new UserRepository();

    /** @param factory the session factory to open transactions on */
    public JpaApprovalStore(SessionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public <T> T inTx(Function<ApprovalData, T> work) {
        Objects.requireNonNull(work, "work");
        return Transactions.inTx(factory, session -> work.apply(new SessionData(session)));
    }

    /** The repositories bound to one session. Alive for exactly one {@link #inTx} call. */
    private final class SessionData implements ApprovalData {

        private final Session session;

        private SessionData(Session session) {
            this.session = session;
        }

        @Override
        public Optional<ExamVersionContext> versionContext(long examVersionId) {
            return exams.findVersionContext(session, examVersionId);
        }

        @Override
        public List<ExamVersionContext> pendingFor(long coordinatorId) {
            return exams.findPendingForCoordinator(session, coordinatorId);
        }

        @Override
        public List<ExamVersionContext> submittedByAuthor(long authorId) {
            return exams.findSubmittedByAuthor(session, authorId);
        }

        @Override
        public List<String> coordinatedSubjects(long teacherId) {
            return users.findCoordinatedSubjects(session, teacherId);
        }

        @Override
        public boolean coordinates(long teacherId, String subjectCode) {
            return courses.coordinates(session, teacherId, subjectCode);
        }

        @Override
        public Optional<Long> coordinatorOf(String subjectCode) {
            return courses.findCoordinatorOf(session, subjectCode);
        }

        @Override
        public List<TakeExamQuestion> questionsOf(long examVersionId) {
            return questions.findForTakeExam(session, examVersionId);
        }

        @Override
        public List<PreviewAnswerRow> answerKeyOf(long examVersionId) {
            List<QuestionVersion> pinned = questions.findAnswerKeyForAuthoring(session, examVersionId);
            Map<Long, Integer> positions = questions.findPinnedPositions(session, examVersionId);

            // The position comes from the join row, never from a counter over this list. Being in
            // exam order makes a counter LOOK right, and it is right only while the stored
            // positions happen to be contiguous and start at 1. V3 constrains `ord` as UNIQUE and
            // >= 1 and nothing more, so an exam with a gap put "Q3" beside a paper whose Q3 was a
            // different question, on the one screen whose whole purpose is checking the answers.
            // Latent until something removes a question from a version; E7's builder is that thing.
            List<PreviewAnswerRow> key = new ArrayList<>(pinned.size());
            for (QuestionVersion version : pinned) {
                Integer ordinal = positions.get(version.getId());
                if (ordinal == null) {
                    // Unreachable while the composite FK holds, which is why this throws rather
                    // than falling back to a counter: a silent fallback would restore the defect
                    // under another name the first time the two reads disagreed.
                    throw new IllegalStateException(
                            "question version " + version.getId() + " is on the answer key of exam "
                                    + "version " + examVersionId + " but has no pinned position");
                }
                key.add(new PreviewAnswerRow(version.getId(), ordinal, version.getCorrectAnswer()));
            }
            return List.copyOf(key);
        }

        @Override
        public Map<Long, Integer> questionCounts(List<Long> examVersionIds) {
            return exams.countQuestionsByVersion(session, examVersionIds);
        }

        @Override
        public Optional<ExamVersion> versionForUpdate(long examVersionId) {
            return Optional.ofNullable(session.get(ExamVersion.class, examVersionId));
        }

        @Override
        public void flush() {
            session.flush();
        }

        @Override
        public int supersedePending(long examId, long keepVersionId, String reason) {
            return exams.supersedePendingVersions(session, examId, keepVersionId, reason);
        }
    }
}
