package common.dto.bot;

import java.io.Serializable;
import java.util.Objects;

/**
 * "You are taking an exam. Continuing will tell that exam's teacher." (Common
 * tier, E16.11 — C-4, ADR-018).
 *
 * <p>The cross-course branch of C-4, and the reason it is a <b>successful</b>
 * response rather than an error. Using another course's bot mid-exam is allowed:
 * the specification does not forbid it, and ADR-018 decided that surfacing it
 * beats over-blocking it. So the server is not refusing here, it is asking — and
 * an {@code ERROR} payload would have made the client guess, from a sentence,
 * which of three different {@code CONFLICT}s it was looking at.
 *
 * <p>A client that does not understand this type shows nothing and the ask simply
 * does not proceed, which is the safe direction: the ask cannot happen without an
 * acknowledgement, and an acknowledgement cannot be produced by not understanding
 * the question.
 *
 * <p>The student's answer comes back as {@code BotAskRequest.integrityAcknowledged},
 * and what that flag can and cannot buy is spelled out on that record: the alert
 * to her teacher is raised from the server's own view of her live attempts, not
 * from anything the client sends.
 *
 * @param courseName the course whose bot she is opening
 * @param message    the notice, already written for her (PRD §4.1)
 */
public record BotIntegrityNotice(String courseName, String message) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BotIntegrityNotice {
        Objects.requireNonNull(message, "message");
        courseName = courseName == null ? "" : courseName;
    }
}
