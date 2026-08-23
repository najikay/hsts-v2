package common.dto.release;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Take an approved exam version out of the drawer (Common tier, E9 — F5.1/F5.2/F5.3).
 *
 * <p>Four components, and two of them carry rules that both tiers run.
 *
 * <h2>The code is the teacher's, and optional</h2>
 *
 * <p>The spec is explicit — §4 says the teacher <em>defines</em> a 4-character execution code,
 * T-5.3 has her typing one — so {@link #code} is here and the create dialog has a field for it.
 * It is <b>nullable</b>, and null means "you pick one": a teacher who has no opinion should not
 * have to invent a code, and the server's generator produces a readable one.
 *
 * <p>What the server's exclusive knowledge buys is <b>validation, not ownership</b>. §5 makes
 * uniqueness a service rule because the constraint is partial (a code is free again once its
 * sitting is over) and MySQL has no partial unique index, so "is this code free" can only be
 * answered inside the transaction that inserts. That is where a supplied code is checked, and
 * a clash comes back as {@link ReleaseCodeIssue#TAKEN} naming the way out. The shape rule
 * ({@link ReleaseCodeIssue#MALFORMED}) is a rule about a string, so it lives here and the
 * dialog runs it as she types.
 *
 * <p>Case is not part of the identity: codes are stored upper case and compared
 * case-insensitively (C-1), because students type them.
 *
 * <h2>What is deliberately still absent</h2>
 *
 * <p><b>No teacher id.</b> Who is releasing it is the session's answer, never a payload's
 * (P-5), so there is no field anybody could put a colleague's id into.
 *
 * <p>{@link #windowProblem(Instant, Duration)} is the F5.2 rule, written once here so the
 * client can grey out its Create button with the same arithmetic the server refuses with. The
 * client checking first is a courtesy; the server checks again because minutes pass between
 * opening a dialog and pressing a button.
 *
 * @param examVersionId the approved version to release; APPROVED is re-checked server-side
 * @param openAt        when students may start (S-15)
 * @param closeAt       when the window shuts; extensions move the effective end, not this
 * @param code          the 4-character code she chose, or {@code null} to have one generated
 *                      (C-1, S-16). Normalised to upper case; never a student's to see (S-17)
 */
public record ReleaseCreateRequest(long examVersionId, Instant openAt, Instant closeAt,
                                   String code)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Code length (C-1). */
    public static final int CODE_LENGTH = 4;

    /**
     * How far into the past an opening moment may sit and still be accepted.
     *
     * <p>Not zero, on purpose. A teacher who picks "now" in a date picker, reads the summary
     * and presses Create has spent thirty seconds doing something completely reasonable, and
     * a rule that refused her would be refusing the commonest way this screen is used in a
     * classroom. Five minutes is long enough for that and far too short to schedule anything
     * retroactively.
     */
    public static final Duration PAST_GRACE = Duration.ofMinutes(5);

    /** The shortest window that is worth creating: a one-second exam is a typo. */
    public static final Duration MIN_WINDOW = Duration.ofMinutes(1);

    /**
     * Normalises the code on the way in: blank becomes {@code null}, anything else is trimmed
     * and upper-cased.
     *
     * <p>Done in the compact constructor rather than at the two call sites, so a request built
     * from an empty text field and one built from a teacher who never touched it are the same
     * request. There is then exactly one representation of "generate one for me", and every
     * later check can be a null test.
     */
    public ReleaseCreateRequest {
        code = normalizeCode(code);
    }

    /**
     * The three-component form: release this version in this window, and pick me a code.
     *
     * <p>Kept because it is the commonest call and because every construction site written
     * before the code field existed keeps compiling and keeps meaning what it meant.
     *
     * @param examVersionId the approved version
     * @param openAt        when the window opens
     * @param closeAt       when it shuts
     */
    public ReleaseCreateRequest(long examVersionId, Instant openAt, Instant closeAt) {
        this(examVersionId, openAt, closeAt, null);
    }

    /** @return {@code true} when the teacher chose a code rather than leaving it to the server. */
    public boolean hasCode() {
        return code != null;
    }

    /**
     * What is wrong with the code she typed, or {@code null} when there is nothing wrong with
     * it <em>as a string</em> (C-1).
     *
     * <p>Only ever answers {@link ReleaseCodeIssue#MALFORMED}: whether a well-formed code is
     * already in use is a question about the database, and the server answers it inside the
     * transaction that inserts. A blank code is not a problem, it is a request.
     *
     * @return the shape problem, or {@code null}
     */
    public ReleaseCodeIssue codeProblem() {
        if (code == null) {
            return null;
        }
        return isWellFormedCode(code) ? null : ReleaseCodeIssue.MALFORMED;
    }

    /**
     * C-1's shape rule, in one place for both tiers.
     *
     * <p>Deliberately the <b>wide</b> rule — four ASCII letters or digits, case-insensitive —
     * rather than the narrower alphabet the server's generator draws from. That alphabet drops
     * the characters people mishear when a code is read out loud, which is a choice about
     * codes we invent; it is not a rule we may impose on a teacher who typed {@code 4821}, and
     * the seed and the demo both carry all-digit codes.
     *
     * @param candidate a code as typed; may be {@code null}
     * @return {@code true} when it is four letters or digits
     */
    public static boolean isWellFormedCode(String candidate) {
        if (candidate == null || candidate.length() != CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < CODE_LENGTH; index++) {
            char character = candidate.charAt(index);
            // ASCII only: a Hebrew letter is a letter to Character.isLetterOrDigit, and a code
            // that cannot be typed on the keyboard in the exam hall is not a code.
            if (character > 0x7F || !Character.isLetterOrDigit(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param raw a code as typed, or {@code null}
     * @return it trimmed and upper-cased, or {@code null} when there was nothing there. Case is
     *         not part of a code's identity (C-1), so there is one stored form
     */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * Why this window cannot be accepted, or {@code null} when it can (F5.2).
     *
     * <p>Returns the reason rather than a boolean because the four refusals are different
     * mistakes with different fixes, and PRD §4.1 requires each to say what to do next. The
     * sentences live in {@link ReleaseWindow} so the server and the client say the same ones.
     *
     * @param now   the clock reading to judge "in the past" against
     * @param grace how far back {@code openAt} may sit; {@link #PAST_GRACE} in production
     * @return the problem, or {@code null} when the window is legal
     */
    public ReleaseWindow windowProblem(Instant now, Duration grace) {
        if (openAt == null || closeAt == null) {
            return ReleaseWindow.MISSING;
        }
        if (!closeAt.isAfter(openAt)) {
            return ReleaseWindow.CLOSE_NOT_AFTER_OPEN;
        }
        if (Duration.between(openAt, closeAt).compareTo(MIN_WINDOW) < 0) {
            return ReleaseWindow.TOO_SHORT;
        }
        if (now != null && openAt.isBefore(now.minus(grace == null ? PAST_GRACE : grace))) {
            return ReleaseWindow.IN_THE_PAST;
        }
        return null;
    }

    /**
     * @param now the clock reading
     * @return {@code true} when this window passes every F5.2 rule with the production grace
     */
    public boolean isWindowLegal(Instant now) {
        return windowProblem(now, PAST_GRACE) == null;
    }
}
