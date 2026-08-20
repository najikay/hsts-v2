package common.dto.exam;

import java.io.Serializable;
import java.util.Locale;

/**
 * "I have this code, what am I about to sit?" (Common tier, E10.9 — F6.1, C-1).
 *
 * <p>Step one of the two-step entry. It carries the code and nothing else: which student
 * is asking comes from the session the server resolved from the socket, never from a
 * field, so this request cannot be aimed at somebody else.
 *
 * <p>The code is normalised here, once, on the way out: trimmed and upper-cased. C-1 makes
 * entry case-insensitive, and doing it in the record rather than in the screen means the
 * server sees the same shape whatever typed it, while the server still lowercases both
 * sides of its own comparison (production collation would, H2 would not).
 *
 * @param code the 4-character execution code, as the teacher read it out (S-17)
 */
public record ExamJoinRequest(String code) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** How many characters an execution code has (C-1). */
    public static final int CODE_LENGTH = 4;

    public ExamJoinRequest {
        code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * @return {@code true} when the code is four alphanumeric characters. A client checks
     *         this to enable its button; the server checks it again before touching the
     *         database, because a client check is a courtesy, not a guard
     */
    public boolean isWellFormed() {
        return code.length() == CODE_LENGTH && code.chars().allMatch(Character::isLetterOrDigit);
    }
}
