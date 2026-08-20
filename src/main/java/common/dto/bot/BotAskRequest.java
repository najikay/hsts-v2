package common.dto.bot;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

/**
 * A student asking her course's bot something (Common tier, E16.11 — F12.5, C-4).
 *
 * <p>No student id: the caller is the session on the socket, and a student id in
 * this payload could only ever be a classmate's (P-5). {@link #sessionId} is
 * {@code null} for the first question of a new conversation and carries her own
 * session id when she is continuing one — a session id that is not hers answers
 * {@code NOT_FOUND}, indistinguishably from one that does not exist.
 *
 * <h2>{@link #integrityAcknowledged}, and why a boolean from the client is safe
 * here</h2>
 *
 * <p>This is the one field on this record a client could lie about, so it is worth
 * being precise about what lying would buy. It is the student's answer to the
 * cross-course integrity notice (C-4, ADR-018): "you are currently taking an
 * exam, continuing will inform that exam's teacher". Sending {@code true} without
 * having seen the notice does not unlock anything and does not suppress anything —
 * the server raises the alert either way, because it raises it from the attempt
 * state it can see rather than from this flag. All the flag decides is whether
 * the ask proceeds now or comes back asking her to confirm first. A client that
 * always sent {@code true} would have skipped its own warning dialog and reported
 * its user to her teacher, which is not an attack.
 *
 * <p>The same-course branch is not negotiable at all: it is refused from the
 * server's own view of her live attempts and there is no field that can affect it.
 *
 * @param courseCode            the course whose bot she is asking
 * @param sessionId             the conversation to continue, or {@code null} to start one
 * @param question              what she typed
 * @param integrityAcknowledged {@code true} when she has seen the C-4 notice for
 *                              this attempt and chose to continue
 */
public record BotAskRequest(String courseCode,
                            Long sessionId,
                            String question,
                            boolean integrityAcknowledged) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The longest question accepted; longer is a paste, not a question. */
    public static final int MAX_QUESTION = 2000;

    public BotAskRequest {
        Objects.requireNonNull(courseCode, "courseCode");
        courseCode = courseCode.trim().toUpperCase(Locale.ROOT);
        question = question == null ? "" : question.trim();
        if (sessionId != null && sessionId <= 0) {
            sessionId = null;
        }
    }

    /** A first question in a fresh conversation. */
    public static BotAskRequest first(String courseCode, String question) {
        return new BotAskRequest(courseCode, null, question, false);
    }

    /** A follow-up in an existing conversation. */
    public static BotAskRequest inSession(String courseCode, long sessionId, String question) {
        return new BotAskRequest(courseCode, sessionId, question, false);
    }

    /** @return the same ask, with the C-4 notice confirmed. */
    public BotAskRequest acknowledged() {
        return new BotAskRequest(courseCode, sessionId, question, true);
    }

    /** @return {@code true} when there is a course and a non-empty question. */
    public boolean isWellFormed() {
        return !courseCode.isBlank() && !question.isBlank();
    }

    /** @return {@code true} when the question is within {@link #MAX_QUESTION}. */
    public boolean isWithinLengthLimit() {
        return question.length() <= MAX_QUESTION;
    }

    /** @return {@code true} when this ask continues an existing conversation. */
    public boolean continuesSession() {
        return sessionId != null;
    }
}
