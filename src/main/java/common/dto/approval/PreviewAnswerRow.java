package common.dto.approval;

import java.io.Serializable;
import java.util.Objects;

/**
 * Which option is right, for one question of an exam under review (Common tier, E8.4 —
 * F4.1).
 *
 * <p><b>This record carries an answer key, and it is the only one in this package that
 * does.</b> It reaches a coordinator through {@code EXAM_PREVIEW_GET} and nobody else: that
 * verb is {@code requireRole(COORDINATOR)} plus {@code requireCoordinatorOf} on the exam's
 * subject, or the version's own author. Approving an exam without being able to check its
 * answer key is approving a document, not an exam, so the key is part of the job here in
 * exactly the way it is part of authoring.
 *
 * <p><b>Why not {@code common.dto.grading.AnswerReviewRow}.</b> That row is a <em>marked
 * paper</em>: it carries {@code chosen}, {@code isCorrect} and {@code pointsAwarded}, three
 * facts that only exist once a student has sat the exam. Nothing has been sat here — this is
 * a paper that has never been given to anybody — so reusing it would mean sending a null
 * choice, a false correctness and a zero score for every question, and the first reader to
 * take those at face value would be reading a lie about an exam that has no attempts. The
 * convention that record established is followed instead of its shape: one place correctness
 * is serialized per audience, named so the audience is visible, with the guard in front of
 * it tested (see {@code CorrectnessLeakGuardTest} and the {@code ForAuthoring} suffix on the
 * repository read behind this).
 *
 * <p>Deliberately thin. The stem and the four options are already on the wire in this
 * preview's {@code common.dto.exam.ExamQuestion} list, which is the <em>student's</em> type;
 * duplicating them here would create a second rendering of the same question that could
 * disagree with the one the coordinator is being shown, which is the entire failure E8.4
 * exists to fix. So this pairs by {@link #questionVersionId} and adds one byte.
 *
 * @param questionVersionId the pinned question version this answers, matching the
 *                          {@code ExamQuestion} of the same id in the preview
 * @param ordinal           its position in the paper, 1-based, so a panel can be rendered
 *                          in exam order without joining anything
 * @param correctOption     the option that is right, 1..4
 */
public record PreviewAnswerRow(long questionVersionId, int ordinal, byte correctOption)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Every question has exactly four options (C-7/C-8). */
    public static final int OPTION_COUNT = 4;

    public PreviewAnswerRow {
        if (correctOption < 1 || correctOption > OPTION_COUNT) {
            // A key outside 1..4 is a corrupt bank row, not a user error, and a preview
            // that rendered it would tell a coordinator the exam is fine when it is not.
            throw new IllegalArgumentException(
                    "A correct option is 1.." + OPTION_COUNT + ", got " + correctOption);
        }
    }

    /** @return the label a side panel shows, "Q3 · option 2". */
    public String label() {
        return "Q" + ordinal + " · option " + correctOption;
    }

    /**
     * @param option an option index, 1..4
     * @return whether that option is the one marked correct
     */
    public boolean marks(int option) {
        return option == correctOption;
    }

    /** Keeps the key out of any log line this record could land in. */
    @Override
    public String toString() {
        return "PreviewAnswerRow{ordinal=" + ordinal
                + ", questionVersionId=" + questionVersionId + ", key=hidden}";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PreviewAnswerRow that
                && questionVersionId == that.questionVersionId
                && ordinal == that.ordinal
                && correctOption == that.correctOption;
    }

    @Override
    public int hashCode() {
        return Objects.hash(questionVersionId, ordinal, correctOption);
    }
}
