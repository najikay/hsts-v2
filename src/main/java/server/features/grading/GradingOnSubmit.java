package server.features.grading;

import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.Transactions;
import server.db.entities.ExamAttempt;
import server.db.repos.AttemptRepository;
import server.features.exam.AttemptFinalizedListener;

import java.util.Objects;
import java.util.Optional;

/**
 * Auto-grades an attempt the moment it closes (Logic tier, E10.4 → E12.1 — F8.1).
 *
 * <p>The real listener that replaces {@link AttemptFinalizedListener#NO_OP}, and the piece that
 * turns exam-taking and grading from two features into one pipeline: a student hands in, and by
 * the time the teacher opens the grading queue the paper is already marked and waiting for a
 * signature.
 *
 * <p>It is a separate class from {@link GradingService} rather than an {@code implements} on it,
 * for two reasons that are really the same reason. {@code GradingService} takes a session and
 * knows nothing about transactions, which is what lets its rules be tested without a database;
 * and this class is nothing but the transaction plus the lookup, which is what lets it be
 * obviously correct at a glance. Merging them would give the grading rules a
 * {@code SessionFactory} they have no use for.
 *
 * <h2>Its own transaction, after the other one committed</h2>
 *
 * <p>The seam guarantees this runs <b>after</b> the transaction that closed the attempt has
 * committed, never inside it — so this opens one of its own. That ordering is the whole
 * safety property: a slow or failing grader must not be able to roll back a submission a
 * student has already been told succeeded. An ungraded attempt is recoverable in a way a
 * vanished submission is not.
 *
 * <h2>It never throws at its caller</h2>
 *
 * <p>Anything that escapes is logged and swallowed. {@link AttemptFinalizedListener#composite}
 * would catch it anyway and take-exam wraps the call as well, so this is the third of three
 * nets — but it is the one that can say something useful about <em>which</em> attempt failed to
 * grade, and the grade can be produced later by a teacher opening the paper. Letting an
 * exception past here would risk taking down the submit path for every student in the room
 * because one attempt had a bad row.
 *
 * <p>Grading writes an {@code AUTO} grade and nothing else: no notification, no push, nothing
 * the student can see. Publishing is a teacher's act (C-3, S-24) and happens at approval.
 */
public class GradingOnSubmit implements AttemptFinalizedListener {

    private static final Logger log = LoggerFactory.getLogger(GradingOnSubmit.class);

    private final SessionFactory sessionFactory;
    private final GradingService grading;
    private final AttemptRepository attempts;

    public GradingOnSubmit(SessionFactory sessionFactory,
                           GradingService grading,
                           AttemptRepository attempts) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.grading = Objects.requireNonNull(grading, "grading");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    @Override
    public void attemptFinalized(FinalizedAttempt attempt) {
        if (attempt == null) {
            return;
        }
        try {
            Transactions.runInTx(sessionFactory, session -> {
                Optional<ExamAttempt> row = attempts.findByExecutionAndStudent(
                        session, attempt.executionId(), attempt.studentId());
                if (row.isEmpty()) {
                    // The seam fires after the closing transaction committed, so the row is
                    // there under every ordinary sequence. If it is not, something deleted it
                    // between commit and now, and grading a paper that no longer exists is not
                    // the recovery.
                    log.error("Attempt {} finalised but its row was not found for execution {} "
                                    + "and student {}; not graded",
                            attempt.attemptId(), attempt.executionId(), attempt.studentId());
                    return;
                }
                grading.autoGrade(session, row.get());
            });
        } catch (RuntimeException e) {
            // A grade that has not been computed yet is recoverable; a submit path that threw
            // is not. E12.5's queue shows the paper as unmarked and a teacher can act on it.
            log.error("Auto-grading failed for attempt {} (execution {}); the submission itself "
                            + "is safe and the paper can still be graded by hand",
                    attempt.attemptId(), attempt.executionId(), e);
        }
    }
}
