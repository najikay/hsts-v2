package common.dto.bank;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code QUESTION_DELETE}: it happened, or here is who is using it (Common tier,
 * E6.4 — F2.5, T-2.7).
 *
 * <p>A refusal is an {@code OK} carrying {@code deleted = false} and a populated list, not an
 * error code. A question that is in use is not a fault the teacher committed and not a state she
 * can retry out of; it is information, and the dialog she needs names the exams so she can go
 * and look at them.
 *
 * <p><b>De-duplicated by exam, never by exam version.</b> Exam 101101 pins question 11005 in both
 * its v1 and its v2, so a list built from version rows would tell the teacher "2 exams use it:
 * Algebra Midterm, Algebra Midterm" — the demo case for T-2.7, and the reason this list holds
 * {@link BlockingExam} rather than a {@code List<String>} of version names.
 *
 * @param deleted       whether the question was soft-deleted
 * @param blockingExams the exams holding it, empty when it was deleted; never {@code null},
 *                      defensively copied, de-duplicated by exam server-side
 */
public record DeleteOutcome(boolean deleted, List<BlockingExam> blockingExams) implements Serializable {

    private static final long serialVersionUID = 1L;

    public DeleteOutcome {
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        blockingExams = blockingExams == null ? List.of() : List.copyOf(blockingExams);
    }

    /**
     * @return the outcome of a delete that went through. Named {@code succeeded} rather than
     *         {@code deleted} because a record's accessor already owns that name
     */
    public static DeleteOutcome succeeded() {
        return new DeleteOutcome(true, List.of());
    }

    /**
     * @param exams the exams standing in the way; at least one, de-duplicated by exam
     * @return the outcome of a delete that was refused because the question is in use
     */
    public static DeleteOutcome blockedBy(List<BlockingExam> exams) {
        return new DeleteOutcome(false, exams);
    }

    /** @return whether the refusal has exams to name, which is what the blocked dialog needs. */
    public boolean isBlocked() {
        return !deleted && !blockingExams.isEmpty();
    }
}
