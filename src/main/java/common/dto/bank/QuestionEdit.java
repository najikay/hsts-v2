package common.dto.bank;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code QUESTION_UPDATE} payload: a question as the teacher wants the next version to read
 * (Common tier, E6.3 — F2.1/F2.3).
 *
 * <p><b>Inbound, like {@link QuestionDraft}</b>, and licensed by the guard on the same terms:
 * the key in it is one the teacher is submitting and the server already holds. The interesting
 * half of the allow-list is the outbound pair, not this one.
 *
 * <h2>{@code baseVersionNo} is the concurrency token, and it is the only one</h2>
 *
 * <p>It is the version the editor was opened on. The handler writes version n+1 only when
 * {@code baseVersionNo} is still the latest; two teachers who both opened v3 mean the second
 * save answers {@code CONFLICT} with a sentence telling her to reopen, backed underneath by
 * {@code uq_question_versions_no UNIQUE (question_id, version_no)}.
 *
 * <p>There is deliberately no {@code lockVersion} beside it. {@code questions.lock_version}
 * exists and carries {@code @Version}, but {@code questions} is the identity row: inserting a
 * version does not dirty it, Hibernate never increments it, and an echoed token would match
 * forever. Shipping an inert token next to a working one is how the working one stops being
 * trusted.
 *
 * <p>Editing never mutates a version (C-2, ADR-011). An exam pinned to version n keeps version
 * n, wording and key and picture, however many times the question is edited afterwards.
 *
 * @param displayId5    the question to edit
 * @param baseVersionNo the version the editor was loaded from; a stale one answers
 *                      {@code CONFLICT}
 * @param text          the stem as it should now read
 * @param answers       four options, ordered 1..4; never {@code null}, defensively copied
 * @param correctAnswer which option is right, 1..4
 * @param topic         the topic
 * @param difficulty    how hard it is
 * @param imageAction   what to do about the illustration; never {@code null}, see below
 * @param image         the new illustration bytes when {@code imageAction} is
 *                      {@link ImageAction#REPLACE}, otherwise ignored and normally {@code null}
 */
public record QuestionEdit(String displayId5,
                           int baseVersionNo,
                           String text,
                           List<String> answers,
                           int correctAnswer,
                           String topic,
                           Difficulty difficulty,
                           ImageAction imageAction,
                           byte[] image) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Defensive copies in, and a missing {@link ImageAction} means {@link ImageAction#KEEP}.
     *
     * <p>Normalised rather than null-checked because this record is deserialized on a socket
     * read thread: an older or garbled client that omits the field gets the conservative
     * instruction, which is the one that changes nothing about a picture nobody said to change.
     * Throwing here would turn a missing enum into a dropped connection instead of a
     * {@code VALIDATION} answer.
     */
    public QuestionEdit {
        imageAction = imageAction == null ? ImageAction.KEEP : imageAction;
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        answers = answers == null ? List.of() : List.copyOf(answers);
        image = image == null ? null : image.clone();
    }

    /** @return the new illustration, or {@code null}; a copy, so a caller cannot corrupt it. */
    @Override
    public byte[] image() {
        return image == null ? null : image.clone();
    }

    /** @return {@code true} when this edit actually carries replacement bytes. */
    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    /**
     * @return whether the illustration is being changed at all, which is what tells the handler
     *         to look at {@link #image()} rather than copy the previous version's blob forward
     */
    public boolean changesImage() {
        return imageAction != ImageAction.KEEP;
    }

    /** Compares by value, illustration bytes included; see {@link QuestionDraft#equals(Object)}. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QuestionEdit that
                && baseVersionNo == that.baseVersionNo
                && correctAnswer == that.correctAnswer
                && Objects.equals(displayId5, that.displayId5)
                && Objects.equals(text, that.text)
                && Objects.equals(answers, that.answers)
                && Objects.equals(topic, that.topic)
                && difficulty == that.difficulty
                && imageAction == that.imageAction
                && Arrays.equals(image, that.image);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayId5, baseVersionNo, text, answers, correctAnswer, topic,
                difficulty, imageAction) * 31 + Arrays.hashCode(image);
    }

    /** Keeps the answer key and the illustration bytes out of every log line this could land in. */
    @Override
    public String toString() {
        return "QuestionEdit{displayId5=" + displayId5
                + ", baseVersionNo=" + baseVersionNo
                + ", topic=" + topic
                + ", difficulty=" + difficulty
                + ", answers=" + answers.size()
                + ", imageAction=" + imageAction
                + ", image=" + (hasImage() ? image.length + " bytes" : "none") + '}';
    }
}
