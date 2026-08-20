package server.features.grading;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.AttemptStatus;
import server.db.entities.ExamAttempt;
import server.db.entities.Grade;
import server.db.repos.GradeRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Auto-grades submitted attempts and persists the {@code AUTO} grade (Logic tier, E12.1).
 *
 * <p>This is the persistence half of E12.1: it loads the pinned exam questions and the
 * student's answers, hands both to {@link AutoGrader} — which owns every scoring rule — and
 * writes the resulting {@link Grade}. No scoring arithmetic lives here, so the rules stay
 * testable without a database and this class stays testable without re-testing them.
 *
 * <p><b>Only a finished attempt is gradeable.</b> {@code SUBMITTED} and {@code TIMED_OUT} both
 * are; {@code IN_PROGRESS} is not, and asking is a caller bug rather than a data condition
 * (H12.7). A timed-out attempt with nothing saved grades to 0 like any other — it was sat and
 * failed, not absent (H12.4), which is also why it stays in the pass-rate denominator.
 *
 * <p><b>Grading is idempotent.</b> Re-grading an attempt that already has a grade returns the
 * existing row untouched rather than overwriting it. That matters because an overwrite would
 * silently discard a teacher's override and its justification — an audit-trail loss (F8.3,
 * S-23) dressed up as a recomputation.
 */
public class GradingService {

    private static final Logger log = LoggerFactory.getLogger(GradingService.class);

    private final GradingReads reads;
    private final GradeRepository grades;

    public GradingService(GradingReads reads, GradeRepository grades) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.grades = Objects.requireNonNull(grades, "grades");
    }

    /**
     * Scores {@code attempt} and persists an {@code AUTO} grade for it.
     *
     * @param session the current session, inside a transaction
     * @param attempt the attempt to grade; must be {@code SUBMITTED} or {@code TIMED_OUT}
     * @return the persisted grade — the existing one when this attempt was already graded
     * @throws NullPointerException  if either argument is null
     * @throws IllegalStateException if the attempt is still in progress, or has no id
     */
    public Grade autoGrade(Session session, ExamAttempt attempt) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(attempt, "attempt");

        Long attemptId = attempt.getId();
        if (attemptId == null) {
            throw new IllegalStateException("Cannot grade an attempt that has not been persisted");
        }
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            // H12.7: nothing is gradeable before it is submitted or timed out.
            throw new IllegalStateException(
                    "Attempt " + attemptId + " is still in progress and cannot be graded");
        }

        Optional<Grade> existing = grades.findByAttempt(session, attemptId);
        if (existing.isPresent()) {
            // Re-grading would overwrite autoScore and could strand an override's justification.
            log.debug("Attempt {} is already graded; returning the existing grade", attemptId);
            return existing.get();
        }

        long examVersionId = reads.examVersionOf(session, attempt.getExecutionId());
        List<AutoGrader.PinnedQuestion> pinned = reads.pinnedQuestions(session, examVersionId);
        Map<Long, Byte> selected = reads.selectedAnswers(session, attemptId);

        AutoGrader.Result result = AutoGrader.grade(pinned, selected);

        Grade grade = new Grade(attemptId, result.score());
        session.persist(grade);

        log.info("Auto-graded attempt {} (execution {}): {} / {}",
                attemptId, attempt.getExecutionId(), result.score(), AutoGrader.REQUIRED_TOTAL_POINTS);
        return grade;
    }
}
