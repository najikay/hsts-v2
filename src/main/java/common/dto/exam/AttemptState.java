package common.dto.exam;

/**
 * Where one student's sitting has got to, as the client is told it (Common tier, E10).
 *
 * <p>The wire mirror of {@code server.db.entities.AttemptStatus} with one extra value:
 * {@link #NOT_STARTED}, which has no stored counterpart because "no row" is how the
 * database says it. The client needs the distinction — a student who has not started sees
 * the identity screen, a student mid-attempt resumes, and a student whose attempt is over
 * sees a locked screen — so the absence is given a name rather than left as a null the
 * three screens each have to remember to check.
 *
 * <p>The two terminal values are deliberately distinct rather than one "finished": F6.4's
 * Time Up takeover and F6.10's Submitted screen are the same layout family with opposite
 * tone, and which one a returning student gets is decided by this value alone.
 */
public enum AttemptState {

    /** No attempt row exists: this student has not entered her identity yet. */
    NOT_STARTED,

    /** Live. Answers may still be saved, and the countdown is running (F6.2). */
    IN_PROGRESS,

    /** The student handed in before the deadline (F6.9/F6.10). */
    SUBMITTED,

    /** The server force-submitted on expiry, with or without a client present (F6.4). */
    TIMED_OUT;

    /** @return {@code true} once no further answer will ever be accepted. */
    public boolean isFinished() {
        return this == SUBMITTED || this == TIMED_OUT;
    }

    /** @return {@code true} while the exam form is live and editable. */
    public boolean isLive() {
        return this == IN_PROGRESS;
    }
}
