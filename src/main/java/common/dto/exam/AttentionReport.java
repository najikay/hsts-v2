package common.dto.exam;

import java.io.Serializable;

/**
 * "My exam window lost focus, and it was away this long" (Common tier, E11.7 — F7.1b).
 *
 * <p>The whole payload of {@code ATTEMPT_ATTENTION}, and deliberately one number. There is no
 * attempt id and no student id: the server resolves the caller's live attempt from
 * {@code AttemptRegistry}, on the same rule every other take-exam verb follows (P-5 — an id in
 * a request could only ever be somebody else's).
 *
 * <p>Sent <b>on refocus</b>, never on blur, because the duration is the point: an absence is
 * only reportable once it has ended, and a client that announced every blur would flood the
 * teacher with events that have no length. Absences shorter than the client's flicker
 * threshold never become a report at all.
 *
 * <p><b>What this is not.</b> It is not a verdict, not a proof and not a penalty. A student
 * whose laptop showed a notification, whose screen reader took focus, or who was handed a
 * form by the invigilator produces exactly the same event as one who opened a browser.
 * F7.1b's honest limit is stated in the contract and in the copy the teacher reads: detection
 * runs on the student's own machine, so this is a deterrent and a visibility aid, not a
 * control.
 *
 * @param awayMillis how long the window was unfocused, in milliseconds; clamped at 0 because
 *                   a negative absence is a broken clock, not a shorter one
 */
public record AttentionReport(long awayMillis) implements Serializable {

    private static final long serialVersionUID = 1L;

    public AttentionReport {
        awayMillis = Math.max(0, awayMillis);
    }
}
