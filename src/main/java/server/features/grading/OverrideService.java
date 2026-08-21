package server.features.grading;

import common.dto.grading.GradeOverrideRequest;
import common.dto.grading.GradeReview;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.db.entities.Grade;
import server.db.entities.GradeStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * Moving a score by hand, with the reason attached (Logic tier, E12.3 — F8.3, S-23).
 *
 * <p>Three rules, and each one exists because of a specific way a marked exam can be
 * falsified.
 *
 * <p><b>An override is never silent.</b> The justification is stored as
 * {@code overrideReason} in the same write that moves the score, so there is no state in
 * which a grade differs from what the machine computed and nothing says why. The request type
 * makes it required and the handler refuses a blank one before this service is reached, so by
 * the time a score moves the reason already exists.
 *
 * <p><b>The auto score survives.</b> {@link Grade#override} sets {@code finalScore} and leaves
 * {@code autoScore} where it is, so the review screen can show both and a reader can see that
 * a mark was changed. Overwriting the auto score would make an override indistinguishable from
 * a correct machine mark, which is precisely the thing F8.3 wants visible.
 *
 * <p><b>Only an unapproved grade may move.</b> Overriding an {@code APPROVED} grade answers
 * {@code CONFLICT}: the student has already been told the result (C-3), and quietly changing a
 * published mark is a different act from correcting an unpublished one. Re-opening an approved
 * grade is a real workflow and a deliberate v1 non-goal — the contract says so, and it says so
 * because the alternative is a second notification path nobody has designed.
 *
 * <h2>What it answers with</h2>
 *
 * <p>The refreshed {@link GradeReview}, not an acknowledgement. The teacher's screen then
 * redraws from the server's own read of what it just wrote, so a client cannot show a new
 * score that the write did not actually produce.
 */
public class OverrideService {

    private static final Logger log = LoggerFactory.getLogger(OverrideService.class);

    /** What happened, in the three shapes the handler turns into three different answers. */
    public enum Outcome {

        /** The score moved; {@code review} carries the paper as it now stands. */
        OVERRIDDEN,

        /**
         * No such grade, or not this teacher's. One outcome for both, so the response cannot
         * be used to discover which grades exist (the contract's no-oracle rule, E13.1).
         */
        NOT_FOUND,

        /** The grade is already approved, so it may not be changed. Answers {@code CONFLICT}. */
        ALREADY_APPROVED
    }

    /**
     * The result of asking for an override.
     *
     * @param outcome what happened
     * @param review  the refreshed paper when {@code outcome} is {@link Outcome#OVERRIDDEN},
     *                otherwise {@code null} — there is nothing this caller may be shown
     */
    public record OverrideOutcome(Outcome outcome, GradeReview review) {

        static OverrideOutcome of(Outcome outcome) {
            return new OverrideOutcome(outcome, null);
        }
    }

    private final GradeReviewService reviews;

    public OverrideService(GradeReviewService reviews) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
    }

    /**
     * Applies an override on behalf of {@code teacherId}.
     *
     * <p>Assumes the payload has already been validated — non-blank justification, score in
     * range. That check belongs to the handler, which can answer with a sentence; reaching
     * here with a blank reason would be a programming error rather than a teacher's mistake.
     *
     * @param session   the current session, inside a transaction
     * @param teacherId the authenticated caller — never taken from the payload
     * @param request   the grade, the new score and the reason
     * @return what happened, with the refreshed review when the score moved
     * @throws NullPointerException if any argument is null
     */
    public OverrideOutcome override(Session session, long teacherId, GradeOverrideRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");

        Optional<GradeReviewService.ReviewContext> found =
                reviews.contextOf(session, request.gradeId());
        if (found.isEmpty() || !found.get().isOwnedBy(teacherId)) {
            // Unknown and unowned are the same answer, on purpose.
            log.debug("Teacher {} may not override grade {} (missing or not theirs)",
                    teacherId, request.gradeId());
            return OverrideOutcome.of(Outcome.NOT_FOUND);
        }

        GradeReviewService.ReviewContext context = found.get();
        Grade grade = context.grade();
        if (grade.getStatus() == GradeStatus.APPROVED) {
            log.info("Teacher {} tried to override approved grade {}", teacherId, request.gradeId());
            return OverrideOutcome.of(Outcome.ALREADY_APPROVED);
        }

        grade.override(request.newScore(), request.justification());
        log.info("Teacher {} overrode grade {}: auto {} -> {}",
                teacherId, request.gradeId(), grade.getAutoScore(), request.newScore());

        // Read back through the same assembler the review verb uses, so the teacher sees the
        // write rather than an echo of the request.
        return new OverrideOutcome(Outcome.OVERRIDDEN, reviews.review(session, context));
    }
}
