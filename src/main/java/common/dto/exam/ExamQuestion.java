package common.dto.exam;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * One question as a student sitting the exam sees it (Common tier, E10.2 ⚑ — F6.6).
 *
 * <p><b>This record is the v1 leak's grave.</b> It has a stem, four options, a position,
 * a worth and an optional illustration, and it has nowhere at all to put which option is
 * right. It is mapped from {@code server.db.projections.TakeExamQuestion}, whose query is
 * a JPQL constructor expression that never names {@code correct_answer} — so on this path
 * the answer key is not fetched, not held, and not droppable-by-accident, because there is
 * nothing to drop.
 *
 * <p>Adding correctness to a student's paper would mean editing this file, which is a
 * change a reviewer can see. It would also fail a test: the guard scans every record in
 * this package for a component name that reads like an answer key.
 *
 * @param questionVersionId the pinned version being asked; answers are saved against it,
 *                          so a grade computed next year still marks the exact wording
 *                          that was on the screen (C-2)
 * @param displayId         the 5-digit id students quote when they raise a hand (S-8)
 * @param ordinal           position in the paper, 1-based
 * @param points            what it is worth, out of the exam's 100 (S-11)
 * @param text              the stem
 * @param option1           first option
 * @param option2           second option
 * @param option3           third option
 * @param option4           fourth option
 * @param image             optional illustration bytes, or {@code null}
 */
public record ExamQuestion(long questionVersionId,
                           String displayId,
                           int ordinal,
                           int points,
                           String text,
                           String option1,
                           String option2,
                           String option3,
                           String option4,
                           byte[] image) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Every question has exactly four options (C-7/C-8). */
    public static final int OPTION_COUNT = 4;

    /** Defensive copy in, so the array this record holds is not the caller's. */
    public ExamQuestion {
        image = image == null ? null : image.clone();
    }

    /** @return the illustration, or {@code null}; a copy, so a caller cannot corrupt it. */
    @Override
    public byte[] image() {
        return image == null ? null : image.clone();
    }

    /** @return {@code true} when this question has an illustration to render (F2.1). */
    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    /**
     * @param index 1..4
     * @return that option's text
     * @throws IllegalArgumentException for anything outside 1..4 — the only four values a
     *         single-select question can ever be asked about
     */
    public String option(int index) {
        return switch (index) {
            case 1 -> option1;
            case 2 -> option2;
            case 3 -> option3;
            case 4 -> option4;
            default -> throw new IllegalArgumentException(
                    "A question has options 1.." + OPTION_COUNT + ", asked for " + index);
        };
    }

    /**
     * Compares by value, illustration bytes included.
     *
     * <p>A record's generated {@code equals} compares a {@code byte[]} by reference, and
     * the compact constructor clones — so two questions built from identical inputs would
     * never be equal. Invisible while every seeded question has a null image, and a
     * mystery in list assertions the day real assets land. Content hashing is safe because
     * the array is cloned on the way in and on the way out and so cannot change underneath
     * a hash-based collection.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ExamQuestion that
                && questionVersionId == that.questionVersionId
                && ordinal == that.ordinal
                && points == that.points
                && Objects.equals(displayId, that.displayId)
                && Objects.equals(text, that.text)
                && Objects.equals(option1, that.option1)
                && Objects.equals(option2, that.option2)
                && Objects.equals(option3, that.option3)
                && Objects.equals(option4, that.option4)
                && Arrays.equals(image, that.image);
    }

    @Override
    public int hashCode() {
        return Objects.hash(questionVersionId, displayId, ordinal, points,
                text, option1, option2, option3, option4) * 31 + Arrays.hashCode(image);
    }

    /** Keeps illustration bytes out of every log line this record could land in. */
    @Override
    public String toString() {
        return "ExamQuestion{ordinal=" + ordinal
                + ", displayId=" + displayId
                + ", questionVersionId=" + questionVersionId
                + ", points=" + points
                + ", image=" + (hasImage() ? image.length + " bytes" : "none") + '}';
    }
}
