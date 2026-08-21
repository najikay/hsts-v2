package common.dto.bank;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One historical version of a question, read-only (Common tier, E6.3 — F2.3).
 *
 * <p>Carried inside {@link VersionHistory}, newest first. It is the same content as
 * {@link QuestionDetail} minus the identity fields that do not vary between versions: the
 * display id, the course and the latest-version number all live once on the history that holds
 * these, rather than being repeated on every row of it.
 *
 * <p><b>It carries the answer key</b>, and is the second of the two outbound records licensed to
 * do so by {@code server.db.repos.BankWireLeakGuardTest}. The licence is the same as the
 * detail's and no wider: version history renders old versions for the same staff audience, on
 * the same scoped, staff-only verb ({@code QUESTION_VERSIONS}). A history that showed the stem
 * and options of v1 but hid which one was right would be a diff a teacher cannot read, which is
 * the entire use for it.
 *
 * @param versionNo     which version this is, 1-based and never reused
 * @param text          the stem as it read in this version
 * @param answers       the four options as they read in this version, ordered 1..4; never
 *                      {@code null}, defensively copied
 * @param correctAnswer which option was right in this version, 1..4
 * @param topic         the topic as it was
 * @param difficulty    the difficulty as it was
 * @param hasImage      whether this version had an illustration; the bytes come from
 *                      {@code QUESTION_IMAGE_GET}, which is addressed by version precisely so
 *                      history can show the picture that went with the words
 * @param authorName    who wrote this version
 * @param createdAt     when, UTC (ADR-010)
 */
public record QuestionVersionDetail(int versionNo,
                                    String text,
                                    List<String> answers,
                                    int correctAnswer,
                                    String topic,
                                    Difficulty difficulty,
                                    boolean hasImage,
                                    String authorName,
                                    Instant createdAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    public QuestionVersionDetail {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(difficulty, "difficulty");
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /**
     * @param index 1..4
     * @return that option's text as this version had it
     * @throws IllegalArgumentException for anything outside the range this version carries
     */
    public String answer(int index) {
        if (index < 1 || index > answers.size()) {
            throw new IllegalArgumentException(
                    "A question has answers 1.." + answers.size() + ", asked for " + index);
        }
        return answers.get(index - 1);
    }
}
