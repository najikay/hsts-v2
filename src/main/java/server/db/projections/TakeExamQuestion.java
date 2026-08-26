package server.db.projections;

import java.util.Arrays;
import java.util.Objects;

/**
 * One question as a student sitting the exam is allowed to see it (E2.12, F6.6).
 *
 * <h2>Why this type exists</h2>
 *
 * <p>The v1 system leaked correct answers to the client. ARCHITECTURE §5 requires the fix to
 * be structural rather than careful: "the take-exam DTO mapper cannot even see
 * {@code correct_answer} (separate projection), making the v1 'student sees answers' leak
 * structurally impossible."
 *
 * <p>So this record has <b>nowhere to put a correct answer</b>. It is not a
 * {@link server.db.entities.QuestionVersion} with a field ignored, and not a map with a key
 * left out — it is a type whose shape makes the mistake unrepresentable. Adding correctness
 * to a take-exam response would require editing this file, which is a deliberate act a
 * reviewer can see, not an oversight in a mapper somewhere.
 *
 * <p>The query that builds it is a JPQL constructor expression naming exactly these columns,
 * so {@code correct_answer} is never <em>fetched</em> either — it does not travel from the
 * database into the server's memory and then get dropped. See
 * {@code QuestionRepository.findForTakeExam}.
 *
 * <h2>Not a wire type</h2>
 *
 * <p>Deliberately not {@link java.io.Serializable}. The client-facing DTO belongs to
 * {@code common/dto} and does not exist yet; when E6 or E10 adds it, this projection maps
 * into it. Leaving this one unserialisable means it cannot become the wire format by
 * accident, which is how a server-side type ends up carrying fields nobody audited.
 *
 * @param questionVersionId the version being asked, and what an answer is saved against
 * @param displayId         the 5-digit question id students see and quote (S-8)
 * @param ordinal           position within the exam version, 1-based
 * @param points            what this question is worth, out of 100 for the exam
 * @param text              the question stem
 * @param answer1           first option
 * @param answer2           second option
 * @param answer3           third option
 * @param answer4           fourth option
 * @param image             optional illustration, or {@code null}
 */
public record TakeExamQuestion(long questionVersionId,
                               String displayId,
                               int ordinal,
                               int points,
                               String text,
                               String answer1,
                               String answer2,
                               String answer3,
                               String answer4,
                               byte[] image) {

    /** Defensive copy in, so the array this record hands out is not the caller's. */
    public TakeExamQuestion {
        image = image == null ? null : image.clone();
    }

    /**
     * @return the illustration, or {@code null} when the question has none; a copy, so
     *         mutating it cannot corrupt the projection
     */
    @Override
    public byte[] image() {
        return image == null ? null : image.clone();
    }

    /**
     * Compares by value, illustration bytes included.
     *
     * <p>A record's generated {@code equals} compares a {@code byte[]} component by
     * <em>reference</em>, and the compact constructor above clones — so two projections built
     * from identical inputs would hold two different arrays and never be equal - in list
     * assertions, {@code distinct()} and any hash-based collection, far from anything that looks
     * like the cause.
     *
     * <p><b>That day has arrived.</b> This paragraph said the risk was "invisible while
     * illustrations are {@code null}, which is every question in the seed today, and would start
     * failing the day real assets land under {@code docs/seed/img/}". B-8 landed the assets on
     * 2026-08-26, at {@code /seed/img/} on the classpath rather than under {@code docs/} - eleven
     * seeded version rows now carry bytes. So this override is no longer insurance against a
     * future fixture; it is load-bearing for the one in the database.
     *
     * <p>Content-based hashing is safe here precisely because the array cannot be reached
     * from outside: it is cloned on the way in and on the way out, so its contents cannot
     * change while the projection is a key.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof TakeExamQuestion that
                && questionVersionId == that.questionVersionId
                && ordinal == that.ordinal
                && points == that.points
                && Objects.equals(displayId, that.displayId)
                && Objects.equals(text, that.text)
                && Objects.equals(answer1, that.answer1)
                && Objects.equals(answer2, that.answer2)
                && Objects.equals(answer3, that.answer3)
                && Objects.equals(answer4, that.answer4)
                && Arrays.equals(image, that.image);
    }

    @Override
    public int hashCode() {
        return Objects.hash(questionVersionId, displayId, ordinal, points,
                text, answer1, answer2, answer3, answer4) * 31 + Arrays.hashCode(image);
    }
}
