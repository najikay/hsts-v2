package common.dto.exam;

import java.io.Serializable;

/**
 * "This is who I am, start my clock" (Common tier, E10.1 — S-18, F6.1).
 *
 * <p>Step two of entry, and the moment the timer starts. The national id is checked
 * against the <b>caller's own</b> user record: it is an identity confirmation, not a
 * lookup key, so typing a classmate's number identifies nobody and simply fails. The
 * server never resolves a student from this field.
 *
 * @param executionId the execution returned by {@code EXAM_JOIN}
 * @param nationalId  the student's own ת"ז, as typed
 */
public record AttemptStartRequest(long executionId, String nationalId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttemptStartRequest {
        // Trimmed here so a trailing space pasted from a form does not read as a mismatch
        // to a student who typed her number correctly.
        nationalId = nationalId == null ? "" : nationalId.trim();
    }

    /** @return {@code true} when there is something to check at all. */
    public boolean hasIdentity() {
        return !nationalId.isEmpty();
    }
}
