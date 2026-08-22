package common.dto.bank;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code QUESTION_CREATE} payload: a new question as the teacher typed it (Common tier,
 * E6.1 — F2.1/F2.2).
 *
 * <p><b>Inbound, so its answer key is not a leak.</b> It trips the shared correctness predicate
 * and is on the guard's allow-list for the least interesting reason there is: the teacher is
 * <em>submitting</em> the key, and the server already knows every key in the bank. The allow-list
 * records the inbound and outbound licences separately so that a future outbound DTO cannot be
 * waved through by pointing at this one.
 *
 * <p><b>No display id.</b> The server allocates the 5-digit serial (S-8, F2.2), which is why
 * creating cannot be expressed as an edit of a question that does not exist yet. <b>No author
 * id</b> either: authorship is the caller's session, so a question cannot be created in somebody
 * else's name (P-5).
 *
 * <h2>What the handler checks, and this record does not</h2>
 *
 * <p>Per the package javadoc, every rule in the contract's validation section belongs to
 * {@code QuestionValidator}, shared by create and edit: a non-blank stem of at most 4000
 * characters, exactly four non-blank answers of at most 500 each, {@code correctAnswer} in 1..4,
 * the four answers pairwise distinct after trim, whitespace-collapse, case folding and accent
 * folding, a non-blank topic of at most 100 characters, and a difficulty. Each failure is a
 * {@code VALIDATION} answer naming the offending field, because a teacher fixing three bad saves
 * in a row needs three different sentences. None of it is an exception thrown inside a
 * deserialization on a socket read thread.
 *
 * @param courseCode    the course to file the question under; the handler checks the caller
 *                      reaches it
 * @param text          the stem
 * @param answers       four options, ordered 1..4; never {@code null}, defensively copied
 * @param correctAnswer which option is right, 1..4
 * @param topic         the topic
 * @param difficulty    how hard it is
 * @param image         optional illustration bytes, or {@code null}; at most 2MB and PNG or
 *                      JPEG, sniffed from the bytes rather than trusted from a declared type
 *                      (E6.6)
 */
public record QuestionDraft(String courseCode,
                            String text,
                            List<String> answers,
                            int correctAnswer,
                            String topic,
                            Difficulty difficulty,
                            byte[] image) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Defensive copies in, so neither the list nor the array this record holds is the caller's. */
    public QuestionDraft {
        // NOT List.copyOf: that throws on a null ELEMENT, and this constructor runs on the
        // server's socket read thread during deserialization, where any throw kills the
        // connection (E1.11, found by Member A 2026-08-21). A null element must survive
        // construction so QuestionValidator can refuse it with a named VALIDATION sentence
        // instead of the teacher losing her work to a silent disconnect.
        answers = answers == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(answers));
        image = image == null ? null : image.clone();
    }

    /** @return the illustration, or {@code null}; a copy, so a caller cannot corrupt it. */
    @Override
    public byte[] image() {
        return image == null ? null : image.clone();
    }

    /** @return {@code true} when this draft brings an illustration with it (F2.1). */
    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    /**
     * Compares by value, illustration bytes included.
     *
     * <p>A record's generated {@code equals} compares a {@code byte[]} by reference and the
     * compact constructor clones, so two drafts built from identical inputs would never be
     * equal — invisible until the day a test asserts on a round-tripped draft. Content hashing
     * is safe because the array is cloned on the way in and on the way out and so cannot change
     * underneath a hash-based collection.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QuestionDraft that
                && correctAnswer == that.correctAnswer
                && Objects.equals(courseCode, that.courseCode)
                && Objects.equals(text, that.text)
                && Objects.equals(answers, that.answers)
                && Objects.equals(topic, that.topic)
                && difficulty == that.difficulty
                && Arrays.equals(image, that.image);
    }

    @Override
    public int hashCode() {
        return Objects.hash(courseCode, text, answers, correctAnswer, topic, difficulty) * 31
                + Arrays.hashCode(image);
    }

    /** Keeps the answer key and the illustration bytes out of every log line this could land in. */
    @Override
    public String toString() {
        return "QuestionDraft{courseCode=" + courseCode
                + ", topic=" + topic
                + ", difficulty=" + difficulty
                + ", answers=" + answers.size()
                + ", image=" + (hasImage() ? image.length + " bytes" : "none") + '}';
    }
}
